package com.baomihua.danmaku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.baomihua.danmaku.core.Settings;

/**
 * 开机自启与升级后恢复。
 *
 * <p>ColorOS 对 {@code BOOT_COMPLETED} 有延迟，也会因为「自启动」没授权而直接不投递；
 * 所以这里只做「有机会就拉起」，失败不报错，剩下的交给看护任务和用户手动打开。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !updated) {
            return;
        }

        Prefs prefs = App.prefs(context);
        Settings settings = prefs.settings();
        Diag.add("收到系统广播：" + action + "（autoStart=" + settings.autoStart
                + "，wanted=" + prefs.isServiceWanted() + "）");

        if (updated) {
            // 升级安装后系统会杀掉进程，用户之前开着的话直接接回来
            if (prefs.isServiceWanted()) {
                RestartJobService.ensureScheduled(context);
                startQuietly(context, "升级后恢复");
            }
            return;
        }

        if (!settings.autoStart) {
            return;
        }
        if (!App.canDrawOverlays(context)) {
            Diag.add("开机自启跳过：没有悬浮窗权限");
            return;
        }
        RestartJobService.ensureScheduled(context);
        startQuietly(context, "开机自启");
    }

    private void startQuietly(Context context, String why) {
        try {
            OverlayService.start(context);
            Diag.add(why + "：已请求启动悬浮窗");
        } catch (Throwable e) {
            // Android 12+ / ColorOS 可能限制后台启动前台服务，失败就等下次
            Diag.error(why + "启动失败", e);
        }
    }
}
