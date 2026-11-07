package com.baomihua.danmaku;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.baomihua.danmaku.core.Settings;

/**
 * 开机自启：只在用户明确开启、并且权限齐全时拉起悬浮窗服务。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Settings settings = App.prefs(context).settings();
        if (!settings.autoStart) {
            return;
        }
        if (!App.canDrawOverlays(context)) {
            return;
        }
        try {
            OverlayService.start(context);
        } catch (Exception ignored) {
            // Android 12+ 可能限制后台启动前台服务，失败就算了
        }
    }
}
