package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析/生成器。
 *
 * <p>故意不依赖 org.json / Gson：核心层要能在纯 JVM 上跑单元测试，
 * 也要在 Android 上零依赖运行。只支持标准 JSON（含 unicode 转义、数字、布尔、null）。
 */
public final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("json is null");
        }
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw p.error("trailing content");
        }
        return v;
    }

    /** 宽松解析：失败时返回 null 而不是抛异常。 */
    public static Object parseQuietly(String text) {
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> o, String key) {
        if (o == null) {
            return null;
        }
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Double) {
            return trimNumber((Double) v);
        }
        return String.valueOf(v);
    }

    public static long lng(Map<String, Object> o, String key, long fallback) {
        if (o == null) {
            return fallback;
        }
        Object v = o.get(key);
        if (v instanceof Double) {
            return (long) ((Double) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static long lng(Object v, long fallback) {
        if (v instanceof Double) {
            return (long) ((Double) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> o, String key, boolean fallback) {
        if (o == null) {
            return fallback;
        }
        Object v = o.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v);
        }
        return fallback;
    }

    private static String trimNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    // ---------------------------------------------------------------- 生成

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Object entryObj : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entryObj;
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 解析

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("unexpected end");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw error("expected key");
            }
            String key = readString();
            skipWs();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw error("expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw error("expected ',' or ']'");
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("bad escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw error("bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
    }

    private Double readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("expected value");
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw error("expected " + literal);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end");
        }
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') {
                pos++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + pos);
    }
}
