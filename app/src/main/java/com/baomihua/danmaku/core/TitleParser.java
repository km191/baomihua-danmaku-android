package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从播放器给出的标题里认出「片名 / 季 / 集」。
 *
 * <p>桌面版需要读播放器缓存和窗口标题，安卓版拿到的通常是媒体会话的标题，
 * 形如「某某动画 第二季 第05集」「[组名] 某某 - 05 [1080p]」「某某 S02E05」。
 * 这里把常见写法都归一化成同一个结构，供搜索与匹配使用。
 */
public final class TitleParser {

    public static final class TitleInfo {
        /** 去掉季集信息后的作品名，可能为空。 */
        public final String series;
        /** 提炼出的「关键标题」：只留作品名本身，用来搜索。 */
        public final String key;
        /** 1 起；0 表示未知。 */
        public final int season;
        /** 1 起；0 表示未知。 */
        public final int episode;
        public final String raw;

        TitleInfo(String series, String key, int season, int episode, String raw) {
            this.series = series == null ? "" : series;
            this.key = key == null ? "" : key;
            this.season = season;
            this.episode = episode;
            this.raw = raw == null ? "" : raw;
        }

        public boolean hasEpisode() {
            return episode > 0;
        }

        /** 用于搜索的关键词：优先关键标题，其次片名，最后原文。 */
        public String searchKeyword() {
            if (!key.isEmpty()) {
                return key;
            }
            return series.isEmpty() ? raw : series;
        }

        /** 换一个片名做同一季同一集的查询（搜索回退时用）。 */
        public TitleInfo withSeries(String newSeries) {
            String s = newSeries == null ? "" : newSeries.trim();
            return new TitleInfo(s, s, season, episode, raw);
        }

        /**
         * 搜索用的候选关键词：先精确的关键标题，再逐步放宽。
         * 自动匹配会按顺序尝试，命中就停。
         */
        public List<String> searchQueries() {
            List<String> out = new ArrayList<>(4);
            // 1) 最干净的关键标题（去掉首尾标点，比如「学生会也有洞！」→「学生会也有洞」）
            String clean = stripEdges(key);
            addQuery(out, clean);
            addQuery(out, key);
            addQuery(out, stripEdges(series));
            addQuery(out, series);
            // 2) 片名里带副标题/分隔符时，只取第一段再试一次
            if (series.contains(" ")) {
                addQuery(out, stripEdges(series.substring(0, series.indexOf(' '))));
            }
            // 3) 去掉空格的写法（有些源按紧凑写法建索引）
            addQuery(out, clean.replace(" ", ""));
            return out;
        }

        private static void addQuery(List<String> out, String candidate) {
            if (candidate == null) {
                return;
            }
            String c = candidate.trim();
            if (c.length() < 2 || c.length() > 60) {
                return;
            }
            if (!out.contains(c)) {
                out.add(c);
            }
        }

        public String displaySeason() {
            return season > 0 ? "第" + season + "季" : "季数未知";
        }

        public String displayEpisode() {
            return episode > 0 ? "第" + episode + "集" : "集数未知";
        }

        @Override
        public String toString() {
            return "TitleInfo{series='" + series + "', key='" + key + "', season=" + season
                    + ", episode=" + episode + "}";
        }
    }

    // 预告片/花絮/OP/ED 等非正片，匹配时要降权。
    private static final Pattern SPECIAL =
            Pattern.compile("(?i)(预告|花絮|片头|片尾|主题曲|opening|ending|\\bop\\d?\\b|\\bed\\d?\\b|pv|cm|"
                    + "访谈|特典|制作|番外|彩蛋|宣传|预告片)");

    private static final Pattern SEASON_S_E = Pattern.compile("(?i)s(\\d{1,2})\\s*[._-]?\\s*e(\\d{1,3})");
    private static final Pattern SEASON_WORD = Pattern.compile(
            "(?i)(?:season\\s*(\\d{1,2})|(\\d{1,2})(?:st|nd|rd|th)\\s*season)");
    private static final Pattern SEASON_CN =
            Pattern.compile("第\\s*([0-9一二三四五六七八九十百零两]{1,4})\\s*(?:季|部|期)");
    private static final Pattern SEASON_S = Pattern.compile("(?i)(?<![a-z])s(\\d{1,2})(?!\\d)");
    private static final Pattern SEASON_ROMAN = Pattern.compile("[\\s\\-_\\[（(【]*(Ⅱ|Ⅲ|Ⅳ|II|III|IV)[\\s\\-_\\]）)】]*$");

    private static final Pattern EP_CN = Pattern.compile(
            "第\\s*([0-9一二三四五六七八九十百零两]{1,4})\\s*(?:集|话|話|期|回|节|節)");
    /** 「更新至12集」「共12话」这种不带“第”的集数写法。 */
    private static final Pattern EP_NUM_UNIT = Pattern.compile(
            "(?<![\\d.])(\\d{1,4})\\s*(?:集|话|話)(?![\\d])");
    private static final Pattern EP_MARK = Pattern.compile(
            "(?i)(?:episode|ep|e)\\s*[._-]?\\s*(\\d{1,4})(?!\\d)");
    private static final Pattern EP_BRACKET = Pattern.compile("[\\[【(（]\\s*(\\d{1,4})\\s*(?:集|话|話)?\\s*[\\]】)）]");
    private static final Pattern EP_TRAILING = Pattern.compile("(?:^|[\\s_\\-.\\[（(])(\\d{1,4})(?:v\\d)?\\s*(?:[\\]）)]|$)"); 
    private static final Pattern EP_HASH = Pattern.compile("#\\s*(\\d{1,4})");

    /** 方括号/圆括号包住的标签。 */
    private static final Pattern GROUP_TAG =
            Pattern.compile("[\\[【(（]\\s*([^\\]】)）]{1,30})\\s*[\\]】)）]");

    private static final String[] GROUP_HINTS = {
            "字幕", "汉化", "压制", "翻译", "新番", "raws", "subs", "baha", "b-global",
            "netflix", "amzn", "crunchy", "lilith", "喵萌", "lolihouse", "sakurato", "kawaiika",
            "erai", "hanabi", "jiburi", "vcb", "moozzi", "ma10p", "bilibili", "acfun", "jysub",
            "lolihouse", "kaguya", "sweet", "dmhy", "nyaa"
    };

    private TitleParser() {
    }

    public static TitleInfo parse(String rawTitle) {
        String raw = rawTitle == null ? "" : rawTitle.trim();
        String work = normalize(stripGroupTags(raw));

        int season = 0;
        int episode = 0;

        Matcher m = SEASON_S_E.matcher(work);
        if (m.find()) {
            season = toInt(m.group(1), 0);
            episode = toInt(m.group(2), 0);
            work = removeMarker(work, m.start(), m.end());
        }

        Matcher sw = SEASON_WORD.matcher(work);
        if (sw.find()) {
            String g = sw.group(1) != null ? sw.group(1) : sw.group(2);
            season = toInt(g, season);
            work = work.substring(0, sw.start()) + " " + work.substring(sw.end());
        }

        Matcher sc = SEASON_CN.matcher(work);
        if (sc.find()) {
            int value = toInt(sc.group(1), 0);
            boolean isPart = sc.group(0).contains("部");
            // “第一部”多半是作品名的一部分（如“进击的巨人 第一部”少见），仅在无季信息时采用
            if (value > 0 && season == 0) {
                season = value;
            }
            work = work.substring(0, sc.start()) + " " + work.substring(sc.end());
            if (isPart && work.trim().isEmpty()) {
                work = raw;
            }
        }

        if (season == 0) {
            Matcher ss = SEASON_S.matcher(work);
            if (ss.find()) {
                season = toInt(ss.group(1), 0);
                work = work.substring(0, ss.start()) + " " + work.substring(ss.end());
            }
        }

        if (season == 0) {
            Matcher sr = SEASON_ROMAN.matcher(work);
            if (sr.find()) {
                String r = sr.group(1).toUpperCase();
                season = r.equals("II") || r.equals("Ⅱ") ? 2 : r.equals("III") || r.equals("Ⅲ") ? 3 : 4;
                work = work.substring(0, sr.start());
            }
        }

        Matcher ec = EP_CN.matcher(work);
        if (ec.find()) {
            episode = toInt(ec.group(1), 0);
            work = removeMarker(work, ec.start(), ec.end());
        }

        if (episode == 0) {
            Matcher en = EP_NUM_UNIT.matcher(work);
            if (en.find()) {
                int candidate = toInt(en.group(1), 0);
                if (candidate > 0 && candidate <= 999) {
                    episode = candidate;
                    work = removeMarker(work, en.start(), en.end());
                }
            }
        }

        if (episode == 0) {
            Matcher em = EP_MARK.matcher(work);
            if (em.find()) {
                int candidate = toInt(em.group(1), 0);
                // 避免把 1080p / 2160p 之类当集数
                String before = work.substring(Math.max(0, em.start() - 3), em.start());
                if (!before.matches("(?i).*(p|k)$") && candidate <= 2000) {
                    episode = candidate;
                    work = removeMarker(work, em.start(), em.end());
                }
            }
        }

        if (episode == 0) {
            Matcher eh = EP_HASH.matcher(work);
            if (eh.find()) {
                episode = toInt(eh.group(1), 0);
                work = removeMarker(work, eh.start(), eh.end());
            }
        }

        if (episode == 0) {
            Matcher eb = EP_BRACKET.matcher(work);
            int best = -1;
            int bestStart = -1;
            int bestEnd = -1;
            while (eb.find()) {
                int candidate = toInt(eb.group(1), 0);
                if (candidate > 0 && candidate <= 1000 && eb.start() > bestStart) {
                    best = candidate;
                    bestStart = eb.start();
                    bestEnd = eb.end();
                }
            }
            if (best > 0) {
                episode = best;
                work = removeMarker(work, bestStart, bestEnd);
            }
        }

        if (episode == 0) {
            Matcher et = EP_TRAILING.matcher(work);
            int best = -1;
            int bestStart = -1;
            int bestEnd = -1;
            while (et.find()) {
                int candidate = toInt(et.group(1), 0);
                if (candidate > 0 && candidate <= 1000) {
                    best = candidate;
                    bestStart = et.start(1);
                    bestEnd = et.end(1);
                }
            }
            if (best > 0) {
                episode = best;
                work = removeMarker(work, bestStart, bestEnd);
            }
        }

        // 兜底：发布名里常见「[组名] 作品名 05 [1080p]」这种裸数字集数
        if (episode == 0) {
            String[] tokens = work.split("\\s+");
            int bestScore = -1;
            int bestIndex = -1;
            int bestValue = 0;
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].trim();
                if (!token.matches("\\d{1,4}")) {
                    continue;
                }
                int value;
                try {
                    value = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value <= 0 || value > 999) {
                    continue;
                }
                if (value >= 1900 && value <= 2100) {
                    continue; // 年份
                }
                int score = (token.length() >= 2 || token.startsWith("0")) ? 2 : 1;
                if (score > bestScore || (score == bestScore && i > bestIndex)) {
                    bestScore = score;
                    bestIndex = i;
                    bestValue = value;
                }
            }
            if (bestIndex >= 0) {
                episode = bestValue;
                StringBuilder sb = new StringBuilder();
                if (bestIndex > 0) {
                    // 集数前面的才是片名，后面的（分集副标题）丢掉
                    for (int i = 0; i < bestIndex; i++) {
                        sb.append(tokens[i]).append(' ');
                    }
                } else {
                    for (int i = 0; i < tokens.length; i++) {
                        if (i != bestIndex) {
                            sb.append(tokens[i]).append(' ');
                        }
                    }
                }
                work = sb.toString();
            }
        }

        String series = cleanup(work);
        if (series.isEmpty()) {
            series = cleanup(normalize(stripGroupTags(raw)));
        }
        String key = extractKey(series);
        if (key.isEmpty()) {
            key = series;
        }
        return new TitleInfo(series, key, season, episode, raw);
    }

    public static boolean isSpecial(String title) {
        return title != null && SPECIAL.matcher(title).find();
    }

    /**
     * 归一化：全角转半角、括号统一、去掉分辨率/编码/字幕组等噪声。
     */
    static String stripGroupTags(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw.replaceAll("★[^★]{0,24}★", " ");
        Matcher m = GROUP_TAG.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1).toLowerCase(Locale.ROOT);
            boolean looksGroup = false;
            for (String hint : GROUP_HINTS) {
                if (content.contains(hint)) {
                    looksGroup = true;
                    break;
                }
            }
            if (!looksGroup) {
                // 「喵萌奶茶屋」「某某字幕组」这类以组/社/屋/番结尾的短标签
                looksGroup = content.matches(".{0,8}(组|社|屋|番)$");
            }
            if (!looksGroup) {
                // 纯英文/数字的短标签（[1080p]、[CHT]、[Ma10p]）也一并去掉；
                // 但纯数字的 [05] 是集数，必须保留
                looksGroup = content.matches("[a-z0-9\\s\\-_\\.]{1,24}") && !content.matches("\\d{1,4}");
            }
            m.appendReplacement(sb, looksGroup ? " " : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String s = input;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                sb.append(' ');
            } else if (c == '【') {
                sb.append('[');
            } else if (c == '】') {
                sb.append(']');
            } else if (c == '（') {
                sb.append('(');
            } else if (c == '）') {
                sb.append(')');
            } else if (c == '《' || c == '》' || c == '「' || c == '」' || c == '『' || c == '』') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        s = sb.toString()
                .replaceAll("(?i)\\b(1080p|720p|2160p|480p|4k|8k)\\b", " ")
                .replaceAll("(?i)\\b(x264|x265|h264|h265|hevc|avc|aac|flac|ddp?|web-?dl|blu-?ray|bdrip|"
                        + "hdtv|webrip|remux|10bit|60fps|hdr|dolby|atmos)\\b", " ")
                .replaceAll("(简体|繁体|简繁|中字|双语|内嵌|外挂|字幕组|字幕社|压制|高清|超清|蓝光|"
                        + "全集|合集|连载|更新至|在线观看|免费观看|完整版)", " ")
                .replaceAll("[\\[\\]]", " ");
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * 去掉集数标记。如果标记前面已经有像样的片名，就把标记之后的内容整段丢掉
     * ——「学生会也有洞！ 第 3 集 《太好了呢》《泳池大扫除的泳装回！》」里后面的都是分集副标题。
     */
    private static String removeMarker(String work, int start, int end) {
        if (start > 0) {
            String prefix = work.substring(0, start).trim();
            String cleaned = cleanup(prefix);
            if (cleaned.length() >= 2 && hasLetterOrCjk(cleaned)) {
                return prefix;
            }
        }
        return work.substring(0, start) + " " + work.substring(end);
    }

    /** 去掉首尾标点，避免把「学生会也有洞！」整串丢去搜索。 */
    static String stripEdges(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("^[\\s!！?？.。,，、;；:：'\"“”‘’·…~～\\-—_|/\\\\]+", "")
                .replaceAll("[\\s!！?？.。,，、;；:：'\"“”‘’·…~～\\-—_|/\\\\]+$", "");
    }

    private static String cleanup(String work) {
        String s = work
                .replaceAll("(?i)\\b(完整版|高清|超清|蓝光|国语|粤语|日语|中配|原声)\\b", " ")
                .replaceAll("^[\\s\\-_.、,，:：|/\\\\]+", "")
                .replaceAll("[\\s\\-_.、,，:：|/\\\\]+$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        // 只剩单个数字/单字时视为无效
        if (s.length() <= 1 && !hasChinese(s)) {
            return "";
        }
        return s;
    }

    /** 片段级噪声：整体等于这些词时丢掉，避免把它们带进搜索词。 */
    private static final String[] NOISE_SEGMENTS = {
            "新番", "月新番", "合集", "全集", "连载", "更新", "更新至", "正片", "预告", "预告片",
            "特典", "花絮", "番外", "彩蛋", "访谈", "制作", "简日", "繁日", "简繁", "中日", "日语",
            "国语", "粤语", "中配", "原声", "双语", "中字", "字幕", "外挂", "内嵌", "字幕组",
            "高清", "超清", "蓝光", "完整版", "无修正", "生肉", "熟肉", "在线观看", "免费观看",
            "op", "ed", "sp", "pv", "cm", "ova", "oad", "tv", "web", "bd", "hd", "hdr", "remux"
    };

    /**
     * 从清洗过的片名里提炼「关键标题」：逐段丢掉噪声，只留作品名。
     * 例：「喵萌奶茶屋 药屋少女的呢喃 05 简日」→「药屋少女的呢喃」。
     */
    static String extractKey(String series) {
        if (series == null || series.isEmpty()) {
            return "";
        }
        String[] parts = series.split("[\\s\\-_/|·,，。:：;；!?！？~～]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String seg = part.trim();
            if (seg.isEmpty() || isNoiseSegment(seg)) {
                continue;
            }
            if (!hasLetterOrCjk(seg)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(seg);
        }
        String key = sb.toString().trim();
        if (key.length() > 32) {
            key = key.substring(0, 32).trim();
        }
        return stripEdges(key);
    }

    private static boolean isNoiseSegment(String seg) {
        String lower = seg.toLowerCase(Locale.ROOT);
        for (String noise : NOISE_SEGMENTS) {
            if (lower.equals(noise)) {
                return true;
            }
        }
        // 纯数字，或「12集」「全12集」这类残留
        if (lower.matches("\\d{1,4}")) {
            return true;
        }
        if (lower.matches("(全)?\\d{1,4}(集|话|話|期|回|节|節)?(全)?")) {
            return true;
        }
        // 单个非中文符号/字母
        return seg.length() == 1 && !hasChinese(seg);
    }

    private static boolean hasLetterOrCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 基本汉字 + 扩展 A + 兼容汉字。不用 Character.UnicodeScript：
            // 那个 API 在 Android 上要 24 才可用，而这里支持到 23。
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0xF900 && c <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }

    static int toInt(String token, int fallback) {
        if (token == null) {
            return fallback;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        if (t.matches("[0-9]+")) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        int cn = chineseToInt(t);
        return cn > 0 ? cn : fallback;
    }

    private static int chineseToInt(String t) {
        final String digits = "零一二三四五六七八九";
        int total = 0;
        int current = 0;
        boolean any = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int d = digits.indexOf(c);
            if (d >= 0) {
                current = d;
                any = true;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
                any = true;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
                any = true;
            } else if (c == '两') {
                current = 2;
                any = true;
            } else {
                return 0;
            }
        }
        return any ? total + current : 0;
    }

    /**
     * 把标题拆开成多个可比较的片段（用于匹配打分）。
     */
    public static List<String> tokens(String input) {
        List<String> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        for (String part : input.split("[\\s\\-_.,:;!?/\\\\|()\\[\\]{}<>《》「」·~]+")) {
            String t = part.trim().toLowerCase();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
