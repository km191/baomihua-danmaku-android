package com.baomihua.danmaku;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * 通知使用权服务。
 *
 * <p>安卓上要读取「别的 app 的播放进度」，唯一不需要 root 的正路是
 * {@link MediaSessionManager#getActiveSessions}，而它要求调用方拥有通知使用权。
 * 所以这里挂一个 NotificationListenerService 仅仅为了拿到这个权限；
 * 不读通知内容、不上传任何东西。
 */
public class MediaWatcher extends NotificationListenerService {

    private static final String TAG = "MediaWatcher";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知使用权已连接");
    }

    public static boolean isEnabled(Context context) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            ComponentName mine = new ComponentName(context, MediaWatcher.class);
            return flat.contains(mine.flattenToString()) || flat.contains(mine.flattenToShortString());
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前系统的媒体会话；没有权限时返回空列表，不抛异常。 */
    public static List<MediaController> activeSessions(Context context) {
        try {
            MediaSessionManager manager = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return Collections.emptyList();
            }
            ComponentName mine = new ComponentName(context, MediaWatcher.class);
            List<MediaController> list = manager.getActiveSessions(mine);
            return list == null ? Collections.<MediaController>emptyList() : list;
        } catch (SecurityException e) {
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
