package com.baomihua.danmaku.core;

/**
 * 什么时候该让弹幕出现在屏幕上。
 *
 * <p>独立出来是为了能离线测：媒体会话消失（退出播放器）、播放器停止、
 * 暂停很久、播放中，这几种情况的取舍很容易写错——之前的 bug 就是
 * 「会话没了但时钟还在跑」，结果退出播放器后弹幕继续在桌面上滚。
 */
public final class PlaybackGate {

    /** 什么都不用做。 */
    public static final int ACTION_KEEP = 0;
    /** 显示弹幕。 */
    public static final int ACTION_SHOW = 1;
    /** 隐藏弹幕。 */
    public static final int ACTION_HIDE = 2;

    /** 会话消失后等这么久再隐藏，避免播放器切集/切后台时闪一下。 */
    public static final long SESSION_GAP_MS = 2500L;

    /** 一直暂停（例如退出播放器但会话还没释放）超过这个时长就收起。 */
    public static final long PAUSE_TIMEOUT_MS = 20000L;

    private PlaybackGate() {
    }

    /**
     * @param hasSession   当前有没有媒体会话
     * @param stopped      会话是否处于「停止 / 出错 / 播放结束」状态
     * @param playing      是否正在播放
     * @param hasData      是否已经加载了弹幕
     * @param visible      弹幕当前是否可见
     * @param nowMs        当前单调时钟
     * @param lastSessionAt 最后一次看到会话的时间
     * @param pausedForMs  已经连续暂停了多久（播放中传 0）
     */
    public static int decide(boolean hasSession, boolean stopped, boolean playing,
                             boolean hasData, boolean visible, long nowMs, long lastSessionAt,
                             long pausedForMs) {
        if (hasSession) {
            if (stopped) {
                return visible ? ACTION_HIDE : ACTION_KEEP;
            }
            if (playing) {
                return hasData && !visible ? ACTION_SHOW : ACTION_KEEP;
            }
            // 暂停很久：多半是已经退出播放器，会话还挂着
            if (visible && pausedForMs >= PAUSE_TIMEOUT_MS) {
                return ACTION_HIDE;
            }
            return ACTION_KEEP;
        }
        // 没有会话：等一小会儿再隐藏
        if (visible && nowMs - lastSessionAt >= SESSION_GAP_MS) {
            return ACTION_HIDE;
        }
        return ACTION_KEEP;
    }
}
