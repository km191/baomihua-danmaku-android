package com.baomihua.danmaku.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 厂商识别：OPPO / 一加 / realme 都跑 ColorOS 系，后台策略和权限页面名字一样。
 *
 * <p>纯 Java，不碰 Android API，方便在 JVM 上直接测。识别只看厂商与品牌字符串，
 * 不读系统属性、不联网。ColorOS 的版本号从 {@code Build.DISPLAY} 等字段里捞，
 * 捞不到就留空——面板只把它当参考信息，不参与功能判断。
 */
public final class OemProfile {

    public static final int FAMILY_OTHER = 0;
    /** OPPO ColorOS、一加 ColorOS、realme UI 共用一套设置路径。 */
    public static final int FAMILY_COLOR_OS = 1;

    private static final Pattern COLOR_OS_VERSION = Pattern.compile(
            "(?i)(?:coloros|oplusrom|color\\s*os)[\\s_\\-]*v?([0-9]{1,2}(?:\\.[0-9]{1,2}){0,2})");

    private final String manufacturer;
    private final String brand;
    private final String model;
    private final int family;
    private final String label;

    private OemProfile(String manufacturer, String brand, String model, int family, String label) {
        this.manufacturer = manufacturer;
        this.brand = brand;
        this.model = model;
        this.family = family;
        this.label = label;
    }

    public static OemProfile of(String manufacturer, String brand, String model) {
        String m = clean(manufacturer);
        String b = clean(brand);
        String md = clean(model);
        String haystack = (m + " " + b).toLowerCase(Locale.US);

        if (haystack.contains("oneplus")) {
            return new OemProfile(m, b, md, FAMILY_COLOR_OS, "一加 ColorOS");
        }
        if (haystack.contains("realme")) {
            return new OemProfile(m, b, md, FAMILY_COLOR_OS, "realme UI");
        }
        if (haystack.contains("oppo") || haystack.contains("oplus")
                || haystack.contains("coloros")) {
            return new OemProfile(m, b, md, FAMILY_COLOR_OS, "OPPO ColorOS");
        }
        return new OemProfile(m, b, md, FAMILY_OTHER, display(m));
    }

    public boolean isColorOs() {
        return family == FAMILY_COLOR_OS;
    }

    public int family() {
        return family;
    }

    public String manufacturer() {
        return manufacturer;
    }

    public String brand() {
        return brand;
    }

    public String model() {
        return model;
    }

    /** 面板顶部显示的厂商名，例如「OPPO ColorOS」。 */
    public String label() {
        return label;
    }

    /** 诊断里的一行，例如「OPPO PJD110」。 */
    public String deviceLine() {
        if (model.isEmpty()) {
            return manufacturer.isEmpty() ? label : manufacturer;
        }
        if (manufacturer.isEmpty()) {
            return model;
        }
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    /**
     * 从若干系统字段里找 ColorOS 版本，例如 "ColorOS 14.0.1" → "14.0.1"。
     * 找不到返回空串；多个字段按顺序取第一个命中的。
     */
    public static String extractColorOsVersion(String... sources) {
        if (sources == null) {
            return "";
        }
        for (String source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            Matcher matcher = COLOR_OS_VERSION.matcher(source);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if ("unknown".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private static String display(String manufacturer) {
        if (manufacturer.isEmpty()) {
            return "Android";
        }
        return Character.toUpperCase(manufacturer.charAt(0)) + manufacturer.substring(1);
    }
}
