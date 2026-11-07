package com.baomihua.danmaku;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 国产 ROM（重点 ColorOS / realme UI）的设置页跳转。
 *
 * <p>ColorOS 把「自启动」「耗电管理」「后台弹出界面」拆在几个不同的系统应用里，
 * 而且包名在 ColorOS 11→12 之间从 {@code com.coloros.*} 换成了 {@code com.oplus.*}。
 * 这里按「新包名 → 旧包名 → AOSP 通用页 → 应用详情页」的顺序逐个尝试，
 * 打不开就换下一个，最后还能落到应用详情页，不会把用户卡死。
 */
public final class OemSettings {

    public enum Hop {
        /** 显示在其他应用上层。 */
        OVERLAY,
        /** 通知使用权（读媒体会话）。 */
        NOTIFICATION_ACCESS,
        /** ColorOS 自启动 / 后台运行。 */
        AUTO_START,
        /** ColorOS 耗电管理（允许后台活动）。 */
        POWER_USAGE,
        /** 系统电池优化白名单。 */
        BATTERY_OPTIMIZATION,
        /** ColorOS「后台弹出界面」权限。 */
        BACKGROUND_POPUP,
        /** 应用详情页兜底。 */
        APP_DETAILS
    }

    private OemSettings() {
    }

    /**
     * 打开与 {@code hop} 对应的设置页；返回是否成功启动了一个页面。
     * 失败只记诊断，不抛异常。
     */
    public static boolean open(Context context, Hop hop) {
        if (context == null) {
            return false;
        }
        Intent[] candidates = candidates(context, hop);
        for (Intent intent : candidates) {
            if (intent == null) {
                continue;
            }
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Diag.add("打开设置页 " + hop + " -> " + describe(intent));
                return true;
            } catch (Throwable e) {
                Diag.add("设置页打不开 " + hop + " -> " + describe(intent) + "："
                        + e.getClass().getSimpleName());
            }
        }
        Diag.error("没有可用的设置页：" + hop, null);
        return false;
    }

    /** 系统是否已经把这个包放进电池优化白名单。 */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true;
            }
            PowerManager manager = (PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isIgnoringBatteryOptimizations(
                    context.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    public static String batteryStateText(Context context) {
        return isIgnoringBatteryOptimizations(context) ? "已放行" : "未放行";
    }

    private static Intent[] candidates(Context context, Hop hop) {
        String pkg = context.getPackageName();
        switch (hop) {
            case OVERLAY:
                return new Intent[]{
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + pkg)),
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                        details(context)
                };
            case NOTIFICATION_ACCESS: {
                Intent[] list = new Intent[Build.VERSION.SDK_INT >= 30 ? 3 : 2];
                int i = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                    detail.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(context, MediaWatcher.class).flattenToString());
                    list[i++] = detail;
                }
                list[i++] = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                list[i] = details(context);
                return list;
            }
            case AUTO_START:
                return colorOsStartup(context, pkg);
            case POWER_USAGE:
                return colorOsPower(context, pkg);
            case BATTERY_OPTIMIZATION:
                return new Intent[]{
                        new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + pkg)),
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        details(context)
                };
            case BACKGROUND_POPUP:
                // ColorOS 没有稳定的公开页面，直接落到应用详情，用户在「权限」里找。
                return new Intent[]{
                        details(context)
                };
            case APP_DETAILS:
            default:
                return new Intent[]{details(context)};
        }
    }

    /** ColorOS 11 及更早用 com.coloros.*，ColorOS 12 起改用 com.oplus.*。 */
    private static Intent[] colorOsStartup(Context context, String pkg) {
        return new Intent[]{
                startup("com.oplus.safecenter", ".permission.startup.StartupAppListActivity", pkg),
                startup("com.oplus.safecenter", ".startupapp.StartupAppListActivity", pkg),
                startup("com.coloros.safecenter", ".permission.startup.StartupAppListActivity", pkg),
                startup("com.coloros.safecenter", ".startupapp.StartupAppListActivity", pkg),
                startup("com.oppo.safe", ".permission.startup.StartupAppListActivity", pkg),
                details(context)
        };
    }

    private static Intent[] colorOsPower(Context context, String pkg) {
        return new Intent[]{
                power("com.oplus.battery", ".powermanager.fuelgaue.PowerUsageModelActivity", pkg),
                power("com.oplus.battery", ".powermanager.fuelgaue.PowerConsumptionActivity", pkg),
                power("com.oplus.battery", ".powermanager.fuelgaue.PowerConsumptionActivity", pkg),
                power("com.coloros.oppoguardelf",
                        ".powermanager.fuelgaue.PowerUsageModelActivity", pkg),
                power("com.coloros.oppoguardelf",
                        ".powermanager.fuelgaue.PowerConsumptionActivity", pkg),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                details(context)
        };
    }

    private static Intent startup(String pkgName, String cls, String targetPkg) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkgName, cls));
        // 部分 ColorOS 版本靠这个 extra 直接定位到当前应用，多余时会被忽略。
        intent.putExtra("packageName", targetPkg);
        intent.putExtra("pkg", targetPkg);
        return intent;
    }

    private static Intent power(String pkgName, String cls, String targetPkg) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkgName, cls));
        intent.putExtra("packageName", targetPkg);
        return intent;
    }

    private static Intent details(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    private static String describe(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().flattenToShortString();
        }
        String action = intent.getAction();
        return action == null ? "?" : action;
    }
}
