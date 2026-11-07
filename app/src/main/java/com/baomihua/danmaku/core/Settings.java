package com.baomihua.danmaku.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好。字段与桌面版托盘面板一致，序列化成一份 JSON 存起来。
 */
public final class Settings {

    /** 总开关。 */
    public boolean enabled = true;
    /** 显示区域：从顶部向下 25/50/75/100 %（旧配置用；maxRows>0 时忽略）。 */
    public int areaPercent = 50;
    /** 弹幕行数：从屏幕顶部往下排几行；默认 12 行。 */
    public int maxRows = 12;
    /** 字号，单位 sp。 */
    public int fontSp = 22;
    /** 速度倍率。 */
    public float speed = 1.0f;
    /** 不透明度 0~1。 */
    public float opacity = 1.0f;
    /** 描边宽度，单位 dp。 */
    public float strokeDp = 0.6f;
    /** 阴影强度 0~1。 */
    public float shadow = 1.0f;
    /** 等比缩放：字号随窗口大小变化。 */
    public boolean scaleWithViewport = true;
    /** 密度：0 稀疏 / 1 标准 / 2 密集。 */
    public int density = DanmakuEngine.DENSITY_NORMAL;
    /** 屏蔽关键词，逗号或换行分隔。 */
    public String blocked = "";
    /** 合并重复弹幕。 */
    public boolean mergeDuplicates = true;
    /** 时间偏移（毫秒），正数让弹幕晚出现。 */
    public int timeOffsetMs = 0;
    /** 弹幕服务地址（弹弹play v2 兼容）。 */
    public String apiBase = "";
    /** 官方 v2 鉴权：0 无 / 1 签名 / 2 凭证。 */
    public int apiAuthMode = 0;
    /** 官方 v2 的 AppId；AppSecret 不在这里，单独加密存储。 */
    public String apiAppId = "";
    /** 显示悬浮球。 */
    public boolean bubble = true;
    /** 开机自启。 */
    public boolean autoStart = false;
    /** 在画面上显示搜索/加载状态提示。 */
    public boolean showStatusToast = true;
    /** 只显示顶部弹幕（关闭底部）。 */
    public boolean allowBottom = false;

    /** 覆盖区域（屏幕纵向百分比），用于把弹幕限制在视频画面内。 */
    public int regionTopPercent = 0;
    public int regionBottomPercent = 100;

    public static Settings defaults() {
        return new Settings();
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 3);
        m.put("enabled", enabled);
        m.put("areaPercent", areaPercent);
        m.put("maxRows", maxRows);
        m.put("fontSp", fontSp);
        m.put("speed", speed);
        m.put("opacity", opacity);
        m.put("strokeDp", strokeDp);
        m.put("shadow", shadow);
        m.put("scaleWithViewport", scaleWithViewport);
        m.put("density", density);
        m.put("blocked", blocked);
        m.put("mergeDuplicates", mergeDuplicates);
        m.put("timeOffsetMs", timeOffsetMs);
        m.put("apiBase", apiBase);
        m.put("apiAuthMode", apiAuthMode);
        m.put("apiAppId", apiAppId);
        m.put("bubble", bubble);
        m.put("autoStart", autoStart);
        m.put("showStatusToast", showStatusToast);
        m.put("allowBottom", allowBottom);
        m.put("regionTopPercent", regionTopPercent);
        m.put("regionBottomPercent", regionBottomPercent);
        return MiniJson.write(m);
    }

    public static Settings fromJson(String json) {
        Settings s = defaults();
        if (json == null || json.trim().isEmpty()) {
            return s;
        }
        Map<String, Object> m = MiniJson.asObject(MiniJson.parseQuietly(json));
        if (m == null) {
            return s;
        }
        s.enabled = MiniJson.bool(m, "enabled", s.enabled);
        s.areaPercent = clampInt((int) MiniJson.lng(m, "areaPercent", s.areaPercent), 25, 100);
        s.maxRows = clampInt((int) MiniJson.lng(m, "maxRows", s.maxRows), 1, 30);
        s.fontSp = clampInt((int) MiniJson.lng(m, "fontSp", s.fontSp), 10, 48);
        s.speed = clampFloat((float) asDouble(m.get("speed"), s.speed), 0.3f, 3f);
        s.opacity = clampFloat((float) asDouble(m.get("opacity"), s.opacity), 0.15f, 1f);
        s.strokeDp = clampFloat((float) asDouble(m.get("strokeDp"), s.strokeDp), 0f, 4f);
        s.shadow = clampFloat((float) asDouble(m.get("shadow"), s.shadow), 0f, 1f);
        s.scaleWithViewport = MiniJson.bool(m, "scaleWithViewport", s.scaleWithViewport);
        s.density = clampInt((int) MiniJson.lng(m, "density", s.density), 0, 2);
        String blocked = MiniJson.str(m, "blocked");
        if (blocked != null) {
            s.blocked = blocked;
        }
        s.mergeDuplicates = MiniJson.bool(m, "mergeDuplicates", s.mergeDuplicates);
        s.timeOffsetMs = clampInt((int) MiniJson.lng(m, "timeOffsetMs", s.timeOffsetMs), -60000, 60000);
        String api = MiniJson.str(m, "apiBase");
        if (api != null) {
            s.apiBase = api.trim();
        }
        s.apiAuthMode = clampInt((int) MiniJson.lng(m, "apiAuthMode", s.apiAuthMode), 0, 2);
        String appId = MiniJson.str(m, "apiAppId");
        if (appId != null) {
            s.apiAppId = appId.trim();
        }
        s.bubble = MiniJson.bool(m, "bubble", s.bubble);
        s.autoStart = MiniJson.bool(m, "autoStart", s.autoStart);
        s.showStatusToast = MiniJson.bool(m, "showStatusToast", s.showStatusToast);
        s.allowBottom = MiniJson.bool(m, "allowBottom", s.allowBottom);
        s.regionTopPercent = clampInt((int) MiniJson.lng(m, "regionTopPercent", s.regionTopPercent), 0, 95);
        s.regionBottomPercent = clampInt((int) MiniJson.lng(m, "regionBottomPercent", s.regionBottomPercent), 5, 100);
        if (s.regionBottomPercent - s.regionTopPercent < 15) {
            s.regionBottomPercent = Math.min(100, s.regionTopPercent + 15);
        }
        return s;
    }

    public Settings copy() {
        return fromJson(toJson());
    }

    /** 显示区域百分比 → 引擎比例（maxRows>0 时引擎不再使用）。 */
    public float areaRatio() {
        return areaPercent / 100f;
    }

    /** 屏蔽词列表。 */
    public String[] blockedWords() {
        if (blocked == null || blocked.trim().isEmpty()) {
            return new String[0];
        }
        String[] raw = blocked.split("[,，;；\\n\\r]+");
        int count = 0;
        for (String r : raw) {
            if (!r.trim().isEmpty()) {
                count++;
            }
        }
        String[] out = new String[count];
        int i = 0;
        for (String r : raw) {
            if (!r.trim().isEmpty()) {
                out[i++] = r.trim();
            }
        }
        return out;
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Double) {
            return (Double) o;
        }
        return fallback;
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
