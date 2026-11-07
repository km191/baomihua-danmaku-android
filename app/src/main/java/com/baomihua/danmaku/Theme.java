package com.baomihua.danmaku;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * 配色与尺寸常量。深色面板 + 爆米花橙点缀，和桌面版托盘面板一个调子。
 */
public final class Theme {

    public static final int BG = 0xFF0F1218;
    public static final int CARD = 0xFF171B23;
    public static final int BORDER = 0xFF242B38;
    public static final int TEXT = 0xFFE8ECF3;
    public static final int DIM = 0xFF8E98A8;
    public static final int ACCENT = 0xFFFF7A45;
    public static final int ACCENT_SOFT = 0x33FF7A45;
    public static final int BLUE = 0xFF4EA1FF;
    public static final int GREEN = 0xFF41C08A;
    public static final int RED = 0xFFF2555A;

    private Theme() {
    }

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics()));
    }

    public static float dpF(Context c, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                c.getResources().getDisplayMetrics()));
    }

    /** 圆角背景。 */
    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpF(c, radiusDp));
        return d;
    }

    /** 带描边的圆角背景，用于卡片。 */
    public static GradientDrawable card(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dpF(c, 16));
        d.setStroke(dp(c, 1), BORDER);
        return d;
    }

    /** 胶囊背景。 */
    public static GradientDrawable pill(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpF(c, 999));
        return d;
    }
}
