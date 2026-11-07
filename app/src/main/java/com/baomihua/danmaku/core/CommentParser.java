package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕响应解析。
 *
 * <p>支持三种实际会遇到的文件格式：
 * <ul>
 *   <li>弹弹play API v2 的评论 JSON（{@code comments:[{p:"时间,模式,颜色,用户", m:"文本"}]}）；</li>
 *   <li>B 站 / 弹弹play XML（{@code <d p="时间,模式,颜色,...">文本</d>}）；</li>
 *   <li>AcFun 风格 XML（{@code <comment p="时间,颜色,模式,用户,...">文本</comment>}，颜色在第二位）。</li>
 * </ul>
 */
public final class CommentParser {

    private static final Pattern D_TAG =
            Pattern.compile("<d\\s+p=\"([^\"]*)\"[^>]*>(.*?)</d>", Pattern.DOTALL);
    private static final Pattern COMMENT_TAG =
            Pattern.compile("<comment\\s+p=\"([^\"]*)\"[^>]*>(.*?)</comment>", Pattern.DOTALL);

    private CommentParser() {
    }

    /** 弹弹play API v2 的 {@code /api/v2/comment/{id}} 响应体。 */
    public static List<Danmaku> parseDandanplayJson(String json) {
        List<Danmaku> out = new ArrayList<>();
        Object root = MiniJson.parseQuietly(json);
        Map<String, Object> obj = MiniJson.asObject(root);
        if (obj == null) {
            return out;
        }
        List<Object> comments = MiniJson.asArray(obj.get("comments"));
        if (comments == null) {
            // 兼容 {"data":{"comments":[...]}} 这种包装
            Map<String, Object> data = MiniJson.asObject(obj.get("data"));
            if (data != null) {
                comments = MiniJson.asArray(data.get("comments"));
            }
        }
        if (comments == null) {
            return out;
        }
        for (Object item : comments) {
            Map<String, Object> c = MiniJson.asObject(item);
            if (c == null) {
                continue;
            }
            String m = MiniJson.str(c, "m");
            if (m == null) {
                m = MiniJson.str(c, "text");
            }
            if (m == null || m.trim().isEmpty()) {
                continue;
            }
            String p = MiniJson.str(c, "p");
            long timeMs = 0L;
            int mode = Danmaku.MODE_SCROLL;
            int color = 0xFFFFFF;
            if (p != null) {
                String[] parts = p.split(",", -1);
                timeMs = (long) Math.round(parseDouble(parts, 0, 0.0) * 1000.0);
                mode = (int) parseDouble(parts, 1, 1.0);
                color = (int) parseDouble(parts, 2, 16777215.0);
            }
            out.add(new Danmaku(timeMs, unescapeXml(m).trim(), mode, color));
        }
        return out;
    }

    /** B 站 / 弹弹play XML。 */
    public static List<Danmaku> parseXml(String xml) {
        List<Danmaku> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Matcher matcher = D_TAG.matcher(xml);
        while (matcher.find()) {
            String p = matcher.group(1);
            String text = unescapeXml(matcher.group(2));
            if (text.trim().isEmpty()) {
                continue;
            }
            String[] parts = p.split(",", -1);
            long timeMs = (long) Math.round(parseDouble(parts, 0, 0.0) * 1000.0);
            int mode = (int) parseDouble(parts, 1, 1.0);
            int color = (int) parseDouble(parts, 2, 16777215.0);
            out.add(new Danmaku(timeMs, text.trim(), mode, color));
        }
        if (!out.isEmpty()) {
            return out;
        }
        Matcher ac = COMMENT_TAG.matcher(xml);
        while (ac.find()) {
            String p = ac.group(1);
            String text = unescapeXml(ac.group(2));
            if (text.trim().isEmpty()) {
                continue;
            }
            String[] parts = p.split(",", -1);
            long timeMs = (long) Math.round(parseDouble(parts, 0, 0.0) * 1000.0);
            int color = (int) parseDouble(parts, 1, 16777215.0);
            int mode = (int) parseDouble(parts, 2, 1.0);
            out.add(new Danmaku(timeMs, text.trim(), mode, color));
        }
        return out;
    }

    /**
     * 自动判断格式：先试 JSON，再试 XML。失败返回空列表（不抛异常）。
     */
    public static List<Danmaku> parseAuto(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String trimmed = body.trim();
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            List<Danmaku> json = parseDandanplayJson(trimmed);
            if (!json.isEmpty()) {
                return json;
            }
            // 有些服务把 comments 放在数组里，或直接返回数组。
            List<Object> arr = MiniJson.asArray(MiniJson.parseQuietly(trimmed));
            if (arr != null) {
                List<Danmaku> wrapped = new ArrayList<>();
                for (Object item : arr) {
                    Map<String, Object> c = MiniJson.asObject(item);
                    if (c == null) {
                        continue;
                    }
                    String m = MiniJson.str(c, "m");
                    String p = MiniJson.str(c, "p");
                    if (m == null || p == null) {
                        continue;
                    }
                    String[] parts = p.split(",", -1);
                    wrapped.add(new Danmaku(
                            (long) Math.round(parseDouble(parts, 0, 0.0) * 1000.0),
                            m.trim(),
                            (int) parseDouble(parts, 1, 1.0),
                            (int) parseDouble(parts, 2, 16777215.0)));
                }
                if (!wrapped.isEmpty()) {
                    return wrapped;
                }
            }
            return new ArrayList<>();
        }
        return parseXml(trimmed);
    }

    private static double parseDouble(String[] parts, int index, double fallback) {
        if (parts == null || index >= parts.length) {
            return fallback;
        }
        try {
            return Double.parseDouble(parts[index].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String unescapeXml(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 8) {
                sb.append(c);
                i++;
                continue;
            }
            String entity = s.substring(i + 1, semi);
            switch (entity) {
                case "lt":
                    sb.append('<');
                    break;
                case "gt":
                    sb.append('>');
                    break;
                case "amp":
                    sb.append('&');
                    break;
                case "quot":
                    sb.append('"');
                    break;
                case "apos":
                    sb.append('\'');
                    break;
                case "nbsp":
                    sb.append(' ');
                    break;
                default:
                    if (entity.startsWith("#")) {
                        try {
                            int code = entity.startsWith("#x") || entity.startsWith("#X")
                                    ? Integer.parseInt(entity.substring(2), 16)
                                    : Integer.parseInt(entity.substring(1));
                            sb.append((char) code);
                        } catch (NumberFormatException e) {
                            sb.append('&').append(entity).append(';');
                        }
                    } else {
                        sb.append('&').append(entity).append(';');
                    }
                    break;
            }
            i = semi + 1;
        }
        return sb.toString();
    }
}
