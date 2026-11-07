package com.baomihua.danmaku;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 运行诊断：把「服务起了没、窗口挂上没、会话读到没」这类事实记下来。
 *
 * <p>没有真机时最怕的就是静默失败——加窗口抛异常被 catch 掉、权限判断读错直接退出，
 * 用户只会看到「没有悬浮窗」。这里存最近 40 条事件，面板里能看、能复制。
 */
public final class Diag {

    private static final String TAG = "DanmuCompanion";
    private static final int CAPACITY = 40;
    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Diag() {
    }

    public static void add(String event) {
        String line = TIME.format(new Date()) + "  " + event;
        synchronized (EVENTS) {
            if (EVENTS.size() >= CAPACITY) {
                EVENTS.removeFirst();
            }
            EVENTS.addLast(line);
        }
        Log.i(TAG, event);
    }

    public static void error(String event, Throwable error) {
        String detail = error == null ? "" : (" [" + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage()) + "]");
        add("!! " + event + detail);
        if (error != null) {
            Log.e(TAG, event, error);
        }
    }

    public static List<String> recent(int count) {
        List<String> all;
        synchronized (EVENTS) {
            all = new ArrayList<>(EVENTS);
        }
        if (count >= all.size()) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - count, all.size()));
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : recent(CAPACITY)) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    public static void clear() {
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }
}
