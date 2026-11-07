package com.baomihua.danmaku.core;

/**
 * 一条弹幕。
 */
public final class Danmaku implements Comparable<Danmaku> {

    /** 右向左滚动。 */
    public static final int MODE_SCROLL = 1;
    /** 底端固定。 */
    public static final int MODE_BOTTOM = 4;
    /** 顶端固定。 */
    public static final int MODE_TOP = 5;
    /** 逆向滚动（右向左的反向），少数来源会用。 */
    public static final int MODE_REVERSE = 6;

    public final long timeMs;
    public final String text;
    public final int mode;
    /** 0xRRGGBB。 */
    public final int color;

    public Danmaku(long timeMs, String text, int mode, int color) {
        this.timeMs = Math.max(0L, timeMs);
        this.text = text == null ? "" : text;
        this.mode = normalizeMode(mode);
        this.color = color & 0xFFFFFF;
    }

    public static int normalizeMode(int mode) {
        switch (mode) {
            case MODE_BOTTOM:
                return MODE_BOTTOM;
            case MODE_TOP:
                return MODE_TOP;
            case MODE_REVERSE:
                return MODE_REVERSE;
            default:
                return MODE_SCROLL;
        }
    }

    public boolean isFixed() {
        return mode == MODE_TOP || mode == MODE_BOTTOM;
    }

    @Override
    public int compareTo(Danmaku other) {
        int c = Long.compare(timeMs, other.timeMs);
        if (c != 0) {
            return c;
        }
        return text.compareTo(other.text);
    }

    @Override
    public String toString() {
        return "Danmaku{" + timeMs + "ms, mode=" + mode + ", " + text + "}";
    }
}
