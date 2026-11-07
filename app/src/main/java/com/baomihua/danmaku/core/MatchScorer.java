package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给「搜索结果」打分：片名像不像、季数对不对、集数对不对。
 *
 * <p>桌面版特别强调「多季剧分别匹配」——不同季各绑定来源，其他季的弹幕不会因为数量多就被选中。
 * 这里用标题里的季信息做硬约束：查询是第 2 季时，候选标题写着第 1 季要扣分，写着第 2 季要加分。
 */
public final class MatchScorer {

    public static final class Candidate {
        public final Models.Anime anime;
        public final Models.Episode episode;
        public final double score;
        public final String reason;

        Candidate(Models.Anime anime, Models.Episode episode, double score, String reason) {
            this.anime = anime;
            this.episode = episode;
            this.score = score;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return String.format("%.2f %s", score, episode);
        }
    }

    private MatchScorer() {
    }

    /**
     * 从搜索到的作品列表里排出最合适的（作品, 分集）组合。
     */
    public static List<Candidate> rank(TitleParser.TitleInfo query, List<Models.Anime> animes) {
        List<Candidate> out = new ArrayList<>();
        if (animes == null) {
            return out;
        }
        for (Models.Anime anime : animes) {
            double animeScore = titleSimilarity(query.series.isEmpty() ? query.raw : query.series, anime.title);
            int animeSeason = seasonOf(anime.title);
            if (query.season > 0 && animeSeason > 0) {
                if (animeSeason == query.season) {
                    animeScore += 0.25;
                } else {
                    animeScore -= 0.45 * (Math.abs(animeSeason - query.season));
                }
            }
            if (TitleParser.isSpecial(anime.title)) {
                animeScore -= 0.3;
            }
            if (anime.episodes.isEmpty()) {
                out.add(new Candidate(anime, new Models.Episode(anime.animeId, anime.animeId, anime.title, ""),
                        clamp(animeScore), "作品级"));
                continue;
            }
            for (Models.Episode ep : anime.episodes) {
                double score = animeScore;
                String reason = "作品名";
                if (query.hasEpisode()) {
                    int epNo = episodeOf(ep.title);
                    if (epNo > 0) {
                        if (epNo == query.episode) {
                            score += 0.35;
                            reason = "集数一致";
                        } else {
                            score -= 0.12;
                        }
                    } else {
                        String needle = String.valueOf(query.episode);
                        if (ep.title.replaceAll("\\s", "").contains(needle)) {
                            score += 0.15;
                            reason = "标题含集数";
                        }
                    }
                }
                out.add(new Candidate(anime, ep, clamp(score), reason));
            }
        }
        Collections.sort(out, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Double.compare(b.score, a.score);
            }
        });
        return out;
    }

    /** 0 表示标题里没写季。 */
    public static int seasonOf(String title) {
        if (title == null || title.isEmpty()) {
            return 0;
        }
        TitleParser.TitleInfo info = TitleParser.parse(title);
        // 注意：标题里只写了「第2季」时，parse 会把季吃掉，且集数可能是 2 而误判；这里只看季字段
        if (info.season > 0) {
            return info.season;
        }
        return 0;
    }

    /** 0 表示没解析出集数。 */
    public static int episodeOf(String title) {
        if (title == null || title.isEmpty()) {
            return 0;
        }
        TitleParser.TitleInfo info = TitleParser.parse(title);
        if (info.episode > 0) {
            return info.episode;
        }
        // “第05话”“05”这种，parse 后 series 会变空，episode 已提取
        return 0;
    }

    /**
     * 片名相似度：字符二元组 Dice 系数 + 包含关系加成。
     */
    public static double titleSimilarity(String a, String b) {
        String na = normalizeTitle(a);
        String nb = normalizeTitle(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0;
        }
        if (na.equals(nb)) {
            return 1.0;
        }
        if (na.contains(nb) || nb.contains(na)) {
            double shorter = Math.min(na.length(), nb.length());
            double longer = Math.max(na.length(), nb.length());
            return 0.72 + 0.25 * (shorter / longer);
        }
        Set<String> ga = bigrams(na);
        Set<String> gb = bigrams(nb);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String g : ga) {
            if (gb.contains(g)) {
                common++;
            }
        }
        return 2.0 * common / (ga.size() + gb.size());
    }

    private static String normalizeTitle(String s) {
        if (s == null) {
            return "";
        }
        String t = TitleParser.normalize(s).toLowerCase();
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s.length() == 1) {
            out.add(s);
            return out;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1.5, v));
    }

    /** 统计候选里的季分布，供“其他季弹幕更多”时仍优先当前季使用。 */
    public static Map<Integer, Integer> seasonHistogram(List<Candidate> candidates) {
        Map<Integer, Integer> map = new HashMap<>();
        for (Candidate c : candidates) {
            int season = seasonOf(c.anime.title);
            Integer prev = map.get(season);
            map.put(season, prev == null ? 1 : prev + 1);
        }
        return map;
    }
}
