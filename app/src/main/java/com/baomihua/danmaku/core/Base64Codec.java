package com.baomihua.danmaku.core;

/**
 * 极简 Base64（标准字母表，带 = 填充）。
 *
 * <p>纯 Java 实现，不依赖 {@code android.util.Base64} 或 API 26 的
 * {@code java.util.Base64}，这样签名逻辑可以在 minSdk 23 和 JVM 测试里共用。
 */
public final class Base64Codec {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Codec() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(ALPHABET[(n >>> 18) & 0x3F]);
            out.append(ALPHABET[(n >>> 12) & 0x3F]);
            out.append(ALPHABET[(n >>> 6) & 0x3F]);
            out.append(ALPHABET[n & 0x3F]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int n = (data[i] & 0xFF) << 16;
            out.append(ALPHABET[(n >>> 18) & 0x3F]);
            out.append(ALPHABET[(n >>> 12) & 0x3F]);
            out.append("==");
        } else if (remaining == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(ALPHABET[(n >>> 18) & 0x3F]);
            out.append(ALPHABET[(n >>> 12) & 0x3F]);
            out.append(ALPHABET[(n >>> 6) & 0x3F]);
            out.append('=');
        }
        return out.toString();
    }
}
