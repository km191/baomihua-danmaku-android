package com.baomihua.danmaku;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行状态的单一数据源：面板和悬浮窗都从这里读，读到变化就刷新。
 */
public final class Status {

    public static final int IDLE = 0;
    public static final int WAITING_MEDIA = 1;
    public static final int SEARCHING = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;
    public static final int NO_CONFIG = 5;

    public interface Listener {
        void onStatusChanged();
    }

    private static final Status INSTANCE = new Status();

    public int state = IDLE;
    public String headline = "未启动";
    public String detail = "";
    public String mediaTitle = "";
    public String episodeLabel = "";
    public int count;
    public long positionMs;
    public long durationMs;
    public boolean playing;
    public boolean serviceRunning;
    public float speed = 1f;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Status() {
    }

    public static Status get() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void set(int newState, String newHeadline, String newDetail) {
        state = newState;
        headline = newHeadline == null ? "" : newHeadline;
        detail = newDetail == null ? "" : newDetail;
        publish();
    }

    public void publish() {
        for (Listener listener : listeners) {
            listener.onStatusChanged();
        }
    }

    public boolean hasDanmaku() {
        return count > 0 && state == READY;
    }

    public String stateLabel() {
        switch (state) {
            case WAITING_MEDIA:
                return "等待播放";
            case SEARCHING:
                return "正在匹配";
            case READY:
                return "已就绪";
            case FAILED:
                return "需要处理";
            case NO_CONFIG:
                return "未配置";
            default:
                return "未启动";
        }
    }
}
