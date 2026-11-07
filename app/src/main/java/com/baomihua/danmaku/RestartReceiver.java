package com.baomihua.danmaku;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * ColorOS 把任务从最近任务里划掉、或内存紧张时，会直接把前台服务收走。
 * 服务自己 {@code START_STICKY} 不一定能活，所以这里用一个短闹钟把自己拉回来。
 *
 * <p>闹钟只在「用户确实开着悬浮窗」时才会生效；关掉悬浮窗会取消它。
 */
public final class RestartReceiver extends BroadcastReceiver {

    public static final String ACTION_REVIVE = "com.baomihua.danmaku.action.REVIVE";
    private static final int REQUEST_CODE = 0x1D0F;

    public static void schedule(Context context, long delayMs) {
        try {
            AlarmManager manager = (AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);
            if (manager == null) {
                return;
            }
            long at = System.currentTimeMillis() + Math.max(0L, delayMs);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context));
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, at, pending(context));
            }
            Diag.add("已安排 " + Math.max(0L, delayMs) + "ms 后自动恢复");
        } catch (Throwable e) {
            Diag.error("安排自动恢复失败", e);
        }
    }

    public static void cancel(Context context) {
        try {
            AlarmManager manager = (AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);
            if (manager != null) {
                manager.cancel(pending(context));
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, RestartReceiver.class);
        intent.setAction(ACTION_REVIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REVIVE.equals(intent.getAction())) {
            return;
        }
        Prefs prefs = App.prefs(context);
        if (!prefs.isServiceWanted()) {
            Diag.add("自动恢复跳过：用户已经关掉悬浮窗");
            return;
        }
        if (!App.canDrawOverlays(context)) {
            Diag.add("自动恢复跳过：悬浮窗权限没了");
            return;
        }
        if (OverlayService.isRunning()) {
            Diag.add("自动恢复跳过：服务还在");
            return;
        }
        try {
            OverlayService.start(context);
            Diag.add("系统收回后，闹钟把悬浮窗拉回来了");
        } catch (Throwable e) {
            Diag.error("自动恢复启动失败", e);
        }
    }
}
