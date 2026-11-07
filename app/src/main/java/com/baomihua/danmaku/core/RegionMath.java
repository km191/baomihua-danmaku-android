package com.baomihua.danmaku.core;

/**
 * 弹幕显示区域计算。
 *
 * <p>桌面版按窗口高度百分比算；手机上要额外避开两样东西：OPPO 的挖孔/刘海（顶部）
 * 和全面屏手势条（底部）。所以百分比不是按整块屏幕算，而是按「安全区」算——
 * 这样默认 0%~100% 也不会把第一行弹幕画到摄像头里。
 *
 * <p>纯 Java，方便在 JVM 上验证各种机型尺寸。
 */
public final class RegionMath {

    private RegionMath() {
    }

    /**
     * @param screenHeight  屏幕真实高度（px）
     * @param safeTop       顶部系统栏/挖孔高度（px），没有就 0
     * @param safeBottom    底部导航/手势条高度（px），没有就 0
     * @param topPercent    显示区域上沿百分比
     * @param bottomPercent 显示区域下沿百分比
     * @param minHeight     至少留多高（px），避免算成 0
     * @return {top, height}，单位 px
     */
    public static int[] region(int screenHeight, int safeTop, int safeBottom, int topPercent,
                               int bottomPercent, int minHeight) {
        int height = Math.max(1, screenHeight);
        int topInset = clamp(safeTop, 0, Math.max(0, height / 4));
        int bottomInset = clamp(safeBottom, 0, Math.max(0, height / 4));
        int usable = Math.max(1, height - topInset - bottomInset);
        int min = clamp(minHeight, 0, height);

        int topPct = clamp(topPercent, 0, 95);
        int bottomPct = clamp(bottomPercent, 5, 100);
        if (bottomPct - topPct < 5) {
            bottomPct = Math.min(100, topPct + 5);
        }

        int top = topInset + Math.round(usable * (topPct / 100f));
        int regionHeight = Math.round(usable * ((bottomPct - topPct) / 100f));
        regionHeight = Math.max(min, regionHeight);

        top = Math.min(top, Math.max(0, height - Math.min(min, height)));
        regionHeight = Math.min(regionHeight, Math.max(1, height - top));
        return new int[]{top, regionHeight};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
