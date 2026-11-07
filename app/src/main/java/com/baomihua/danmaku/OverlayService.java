package com.baomihua.danmaku;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.Settings;
import com.baomihua.danmaku.core.TitleParser;

import java.util.List;
import java.util.Locale;

/**
 * 悬浮窗服务：把弹幕叠在别的播放器画面上。
 *
 * <p>这是桌面版「Overlay + Playback」的安卓版：
 * 前台服务常驻 → 一个不可触摸的全屏（或指定纵向区间）悬浮窗画弹幕 →
 * 媒体会话负责播放进度 → 标题变化时重新匹配片名。
 */
public final class OverlayService extends Service {

    public static final String ACTION_START = "com.baomihua.danmaku.action.START";
    public static final String ACTION_STOP = "com.baomihua.danmaku.action.STOP";
    public static final String ACTION_TOGGLE = "com.baomihua.danmaku.action.TOGGLE";

    private static final String CHANNEL_ID = "overlay_status";
    private static final int NOTIFICATION_ID = 0x1D0D;

    private static OverlayService instance;

    public static OverlayService get() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
            // 服务没在运行
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private DanmakuView danmakuView;
    private TextView bubbleView;
    private FloatingPanel floatingPanel;
    private View floatingPanelView;
    private WindowManager.LayoutParams floatingPanelParams;
    private boolean activityVisible;
    private WindowManager.LayoutParams danmakuParams;
    private WindowManager.LayoutParams bubbleParams;

    private PlaybackTracker tracker;
    private DanmuRepository repository;
    private Prefs prefs;
    private Settings settings;

    private int screenWidth = 1080;
    private int screenHeight = 1920;

    private String currentTitle = "";
    private String pendingTitle = "";
    private int pendingCount;
    private boolean loading;
    private String loadedLabel = "";
    private String lastSessionKey = "";
    private long lastRemountAt;
    private long lastSessionAt;
    private boolean sawSession;
    private boolean danmakuVisible = true;
    private long pausedSince;
    private long resolveGeneration;
    private String lastNotificationText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = App.prefs(this);
        settings = prefs.settings();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        readScreenSize();
        repository = new DanmuRepository(this);
        tracker = new PlaybackTracker(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Diag.add("收到停止指令");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android 14 起前台服务必须带类型。先按 specialUse 起，失败再退回默认类型，
        // 两条都失败才放弃——而且要把原因露出来。
        boolean foreground = false;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                foreground = true;
                Diag.add("前台服务已就绪(specialUse) startId=" + startId);
            } catch (Throwable e) {
                Diag.error("startForeground(specialUse) 失败", e);
            }
        }
        if (!foreground) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动…"));
                foreground = true;
                Diag.add("前台服务已就绪(默认类型) startId=" + startId);
            } catch (Throwable e) {
                Diag.error("startForeground 失败", e);
                Status.get().set(Status.FAILED, "前台服务启动失败", describe(e));
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        boolean allowed = App.canDrawOverlays(this);
        Diag.add("canDrawOverlays=" + allowed);
        // 注意：不少国产 ROM 上这个查询和真实权限不一致。所以这里不直接退出，
        // 而是真的去挂窗口，以 addView 的结果为准（失败会在 addWindow 里报出来）。
        if (!allowed) {
            Diag.add("系统查询说没权限，仍然尝试挂载窗口");
        }

        if (ACTION_TOGGLE.equals(action)) {
            toggleDanmaku();
        }

        boolean attached = attachOverlay();
        Status.get().serviceRunning = attached;
        if (!attached) {
            // 挂不上就停服务，但把失败原因留在状态里给面板显示
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "";
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }

    // ------------------------------------------------------------ 悬浮窗

    private void readScreenSize() {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        } catch (Exception ignored) {
            screenWidth = getResources().getDisplayMetrics().widthPixels;
            screenHeight = getResources().getDisplayMetrics().heightPixels;
        }
    }

    private boolean attachOverlay() {
        readScreenSize();
        settings = prefs.settings();
        Diag.add("屏幕 " + screenWidth + "x" + screenHeight + "，区域 " + settings.regionTopPercent
                + "%~" + settings.regionBottomPercent + "%");
        if (danmakuView == null) {
            danmakuView = new DanmakuView(this);
            danmakuView.setTracker(tracker);
            danmakuView.setVisibility(View.VISIBLE);
            danmakuParams = buildDanmakuParams();
            if (!addWindow(danmakuView, danmakuParams, "弹幕窗")) {
                danmakuView = null;
                return false;
            }
        } else if (danmakuView.getParent() == null) {
            danmakuParams = buildDanmakuParams();
            if (!addWindow(danmakuView, danmakuParams, "弹幕窗")) {
                return false;
            }
        }
        danmakuView.applySettings(settings, screenHeight);
        updateDanmakuVisibility();
        updateWindowGeometry();
        updateBubble();

        tracker.start(tickRunnable);
        Status status = Status.get();
        status.serviceRunning = true;
        status.mediaTitle = "";
        status.count = 0;
        status.set(Status.WAITING_MEDIA, "等待播放", "在任意播放器里打开视频，会自动识别片名");
        // 立刻在屏幕上打一条提示：这条能看到，就说明悬浮窗真的画出来了
        danmakuView.showToast("悬浮窗已开启 · 等待识别片名", 4000);
        return true;
    }

    private WindowManager.LayoutParams buildDanmakuParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                computeRegionHeight(),
                overlayType(),
                overlayFlags(),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = computeRegionTop();
        return params;
    }

    /** 挂窗口，失败要把真实原因露出来，不再静默。 */
    private boolean addWindow(View view, WindowManager.LayoutParams params, String what) {
        try {
            windowManager.addView(view, params);
            Diag.add(what + "已挂载 " + params.width + "x" + params.height
                    + " @" + params.x + "," + params.y + " type=" + params.type);
            return true;
        } catch (Throwable e) {
            Diag.error(what + "挂载失败", e);
            Status.get().set(Status.FAILED, what + "打不开",
                    describe(e) + "（确认「显示在其他应用上层」已授权）");
            return false;
        }
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int overlayFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    }

    private int computeRegionHeight() {
        float top = settings.regionTopPercent / 100f;
        float bottom = settings.regionBottomPercent / 100f;
        int height = (int) (screenHeight * Math.max(0.05f, bottom - top));
        return Math.max(Theme.dp(this, 40), height);
    }

    private int computeRegionTop() {
        return (int) (screenHeight * (settings.regionTopPercent / 100f));
    }

    private void updateWindowGeometry() {
        if (danmakuView == null || danmakuParams == null) {
            return;
        }
        danmakuParams.height = computeRegionHeight();
        danmakuParams.y = computeRegionTop();
        try {
            windowManager.updateViewLayout(danmakuView, danmakuParams);
        } catch (Exception ignored) {
        }
    }

    private void updateBubble() {
        updateBubbleVisibility();
    }

    private void addBubble() {
        if (bubbleView != null) {
            return;
        }
        final TextView bubble = new TextView(this);
        bubble.setText("弹");
        bubble.setTextColor(0xFF16110D);
        bubble.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(Theme.round(this, Theme.ACCENT, 999));
        bubble.setAlpha(0.86f);
        bubble.setElevation(Theme.dpF(this, 6));

        bubbleParams = new WindowManager.LayoutParams(
                Theme.dp(this, 44),
                Theme.dp(this, 44),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = screenWidth - Theme.dp(this, 56);
        bubbleParams.y = screenHeight / 2;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;
            private boolean dragging;
            private boolean longPressed;
            private final Runnable longPress = new Runnable() {
                @Override
                public void run() {
                    longPressed = true;
                    openPanel();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        dragging = false;
                        longPressed = false;
                        handler.postDelayed(longPress, 480);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > Theme.dp(v.getContext(), 4)
                                || Math.abs(dy) > Theme.dp(v.getContext(), 4)) {
                            dragging = true;
                            handler.removeCallbacks(longPress);
                        }
                        if (dragging) {
                            bubbleParams.x = (int) Math.max(0, Math.min(screenWidth - Theme.dp(get(), 44),
                                    startX + dx));
                            bubbleParams.y = (int) Math.max(0, Math.min(screenHeight - Theme.dp(get(), 44),
                                    startY + dy));
                            try {
                                windowManager.updateViewLayout(bubble, bubbleParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPress);
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPress);
                        if (!dragging && !longPressed) {
                            // 单击：就地弹出一个小面板，不离开当前播放画面
                            toggleFloatingPanel();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            windowManager.addView(bubble, bubbleParams);
            bubbleView = bubble;
        } catch (Exception ignored) {
            bubbleView = null;
        }
    }

    private void removeBubble() {
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {
            }
            bubbleView = null;
        }
    }

    private void openPanel() {
        hideFloatingPanel();
        Intent intent = new Intent(this, PanelActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ------------------------------------------------------------ 浮动设置面板

    /** 点悬浮球：就地弹出小面板，播放器继续播、弹幕继续跑。 */
    public void toggleFloatingPanel() {
        if (floatingPanelView != null) {
            hideFloatingPanel();
        } else {
            showFloatingPanel();
        }
    }

    private void showFloatingPanel() {
        if (floatingPanelView != null) {
            return;
        }
        if (!App.canDrawOverlays(this)) {
            Diag.add("系统查询说没权限，仍然尝试弹小面板");
        }
        settings = prefs.settings();
        floatingPanel = new FloatingPanel(this, panelHost, settings);
        floatingPanelView = floatingPanel.root();

        int width = Math.min(screenWidth - Theme.dp(this, 28), Theme.dp(this, 384));
        int height = (int) (screenHeight * 0.68f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(Theme.dp(this, 260), width),
                Math.max(Theme.dp(this, 320), height),
                overlayType(),
                // 需要可取焦点（搜索框要弹输入法），所以不能加 NOT_FOCUSABLE；
                // NOT_TOUCH_MODAL 让面板外的触摸仍然传给下面的播放器。
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = (int) (screenHeight * 0.07f);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        floatingPanelParams = params;
        try {
            windowManager.addView(floatingPanelView, params);
            floatingPanelView.requestFocus();
        } catch (Exception e) {
            floatingPanelView = null;
            floatingPanel = null;
            Status.get().set(Status.FAILED, "小面板打不开", String.valueOf(e.getMessage()));
        }
    }

    private void hideFloatingPanel() {
        if (floatingPanel != null) {
            floatingPanel.release();
            floatingPanel = null;
        }
        if (floatingPanelView != null) {
            try {
                windowManager.removeView(floatingPanelView);
            } catch (Exception ignored) {
            }
            floatingPanelView = null;
        }
    }

    public boolean isFloatingPanelOpen() {
        return floatingPanelView != null;
    }

    /** 小面板回调：改设置、搜索、选源、校准。 */
    private final FloatingPanel.Host panelHost = new FloatingPanel.Host() {
        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void onSettingsChanged(Settings changed) {
            settings = changed;
            prefs.save(changed);
            if (danmakuView != null) {
                danmakuView.applySettings(settings, screenHeight);
            }
            updateWindowGeometry();
            updateNotification();
        }

        @Override
        public void onSearch(String keyword, DanmuRepository.SearchCallback callback) {
            repository.search(keyword, 0, callback);
        }

        @Override
        public void onPick(DanmuRepository.Hit hit) {
            pickHit(hit);
        }

        @Override
        public void onCalibrate(long positionMs) {
            calibrate(positionMs);
        }

        @Override
        public void onRematch() {
            rematch();
        }

        @Override
        public void onStop() {
            hideFloatingPanel();
            stopSelf();
        }

        @Override
        public void onOpenFullPanel() {
            openPanel();
        }

        @Override
        public void onClose() {
            hideFloatingPanel();
        }
    };

    private void pickHit(final DanmuRepository.Hit hit) {
        repository.loadEpisode(hit.animeTitle, hit.episode, new DanmuRepository.Callback() {
            @Override
            public void onReady(String label, List<Danmaku> list, boolean fromCache) {
                manualLoad(label, list);
                TitleParser.TitleInfo info = TitleParser.parse(Status.get().mediaTitle);
                repository.rememberManual(
                        info.series.isEmpty() ? hit.animeTitle : info.series,
                        info.season, hit.animeTitle, null, hit.episode);
            }

            @Override
            public void onFailed(String reason) {
                Status.get().set(Status.FAILED, "载入失败", reason);
                showToast(reason);
            }

            @Override
            public void onProgress(String note) {
                Status.get().set(Status.SEARCHING, note, Status.get().mediaTitle);
            }
        });
    }

    private void showToast(String text) {
        if (danmakuView != null && settings.showStatusToast) {
            danmakuView.showToast(text, 4000);
        }
    }

    /** 悬浮球该出现就出现——播放时始终可用，只有打开自家全屏面板时才让位。 */
    private void updateBubbleVisibility() {
        boolean want = settings.bubble && !activityVisible;
        boolean attached = bubbleView != null && bubbleView.getParent() != null;
        if (want && !attached) {
            if (bubbleView != null) {
                // 之前挂过但掉线了，先清掉引用再重建
                removeBubble();
            }
            addBubble();
        } else if (!want && bubbleView != null) {
            removeBubble();
        }
    }

    private void logSessionChange(PlaybackTracker.Snapshot snapshot) {
        String key = snapshot.hasSession
                ? (snapshot.packageName + "|" + snapshot.mediaTitle() + "|" + snapshot.playing)
                : "none";
        if (!key.equals(lastSessionKey)) {
            lastSessionKey = key;
            Diag.add(snapshot.hasSession
                    ? "媒体会话: " + snapshot.packageName + " / " + snapshot.mediaTitle()
                    : "没有媒体会话（播放器可能不提供）");
        }
    }

    private boolean hasDanmaku() {
        return danmakuView != null && danmakuView.loadedCount() > 0;
    }

    /** 弹幕是否该露出来：自己面板在前台、或者播放已结束，都收起。 */
    private void updateDanmakuVisibility() {
        if (danmakuView == null) {
            return;
        }
        boolean show = danmakuVisible && !activityVisible;
        danmakuView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showDanmaku() {
        if (!danmakuVisible) {
            danmakuVisible = true;
            Diag.add("显示弹幕");
        }
        updateDanmakuVisibility();
    }

    private void hideDanmaku(String reason) {
        if (!danmakuVisible) {
            return;
        }
        danmakuVisible = false;
        updateDanmakuVisibility();
        Diag.add("隐藏弹幕：" + reason);
    }

    /** 每 400ms 体检一次：窗口被系统收走了就重新挂上。 */
    private void ensureOverlayAlive() {
        if (danmakuView == null) {
            return;
        }
        if (danmakuView.getParent() == null) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRemountAt < 3000L) {
                return;   // 别每 400ms 反复重试
            }
            lastRemountAt = now;
            Diag.add("弹幕窗掉线，重新挂载");
            danmakuParams = buildDanmakuParams();
            addWindow(danmakuView, danmakuParams, "弹幕窗");
            danmakuView.applySettings(settings, screenHeight);
            danmakuView.setVisibility(activityVisible ? View.GONE : View.VISIBLE);
        }
        if (floatingPanelView != null && floatingPanelView.getParent() == null) {
            Diag.add("小面板掉线，关闭");
            hideFloatingPanel();
        }
    }

    // ------------------------------------------------------------ 每次采样

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            onTick();
        }
    };

    private void onTick() {
        PlaybackTracker.Snapshot snapshot = tracker.latest();
        Status status = Status.get();
        status.serviceRunning = danmakuView != null;
        status.playing = snapshot.playing;
        status.positionMs = tracker.positionMs();
        status.durationMs = snapshot.durationMs;
        status.speed = snapshot.speed;

        ensureOverlayAlive();
        logSessionChange(snapshot);

        // 播放没了就别再画：会话消失（退出播放器）等 2.5 秒再收，
        // 停止/出错立刻收；重新开始播时再露出来。
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (snapshot.hasSession) {
            lastSessionAt = nowElapsed;
            sawSession = true;
        }
        boolean stopped =
                snapshot.playbackState == android.media.session.PlaybackState.STATE_STOPPED
                        || snapshot.playbackState == android.media.session.PlaybackState.STATE_ERROR;
        if (snapshot.playing) {
            pausedSince = 0L;
        } else if (pausedSince == 0L) {
            pausedSince = nowElapsed;
        }
        long pausedFor = pausedSince == 0L ? 0L : nowElapsed - pausedSince;
        if (sawSession) {
            int action = com.baomihua.danmaku.core.PlaybackGate.decide(snapshot.hasSession, stopped,
                    snapshot.playing, hasDanmaku(), danmakuVisible, nowElapsed, lastSessionAt,
                    pausedFor);
            if (action == com.baomihua.danmaku.core.PlaybackGate.ACTION_HIDE) {
                hideDanmaku(snapshot.hasSession ? "播放器已停止" : "播放器已退出");
                if (status.state == Status.READY) {
                    status.set(Status.WAITING_MEDIA, "播放已结束", "下次开始播放会自动接上");
                }
            } else if (action == com.baomihua.danmaku.core.PlaybackGate.ACTION_SHOW) {
                showDanmaku();
            }
        }

        String title = snapshot.mediaTitle();
        if (!snapshot.hasSession) {
            if (!MediaWatcher.isEnabled(this)) {
                if (status.state != Status.READY) {
                    status.set(Status.FAILED, "需要「通知使用权」",
                            "授权后才能读到播放器标题和进度");
                }
            } else if (!loading && status.state != Status.READY) {
                status.set(Status.WAITING_MEDIA, "等待播放",
                        "没读到播放器的媒体会话；可以播放/暂停一次，或用「校准」手动对时");
            }
        } else if (!title.isEmpty()) {
            status.mediaTitle = title;
            maybeResolve(title);
        }

        updateBubbleVisibility();
        if (floatingPanel != null) {
            floatingPanel.refreshStatus();
        }
        updateNotification();
        status.publish();
    }

    private void maybeResolve(String title) {
        if (title.equals(currentTitle)) {
            return;
        }
        if (!title.equals(pendingTitle)) {
            pendingTitle = title;
            pendingCount = 1;
            return;
        }
        pendingCount++;
        if (pendingCount < 2) {
            return;
        }
        currentTitle = title;
        pendingTitle = "";
        startResolve(title);
    }

    private void startResolve(final String title) {
        final long generation = ++resolveGeneration;
        Diag.add("开始匹配: " + title);
        loading = true;
        Status.get().set(Status.SEARCHING, "正在识别并匹配", title);
        if (danmakuView != null && settings.showStatusToast) {
            danmakuView.showToast("正在匹配：" + shorten(title), 4000);
        }
        repository.resolve(title, new DanmuRepository.Callback() {
            @Override
            public void onProgress(String note) {
                if (generation != resolveGeneration) {
                    return;
                }
                Status.get().set(Status.SEARCHING, note, title);
                if (danmakuView != null && settings.showStatusToast) {
                    danmakuView.showToast(note, 4000);
                }
            }

            @Override
            public void onReady(String label, List<Danmaku> list, boolean fromCache) {
                if (generation != resolveGeneration) {
                    return;
                }
                loading = false;
                loadedLabel = label;
                if (list == null || list.isEmpty()) {
                    Status status = Status.get();
                    status.count = 0;
                    status.episodeLabel = label;
                    status.set(Status.FAILED, "这个来源没有弹幕", label);
                    if (danmakuView != null) {
                        danmakuView.clearSource();
                        if (settings.showStatusToast) {
                            danmakuView.showToast("这个来源没有弹幕，可以在面板里手动搜索", 5000);
                        }
                    }
                    return;
                }
                if (danmakuView != null) {
                    danmakuView.setSource(list);
                }
                showDanmaku();
                Diag.add("匹配成功: " + label + " / " + list.size() + " 条" + (fromCache ? "（缓存）" : ""));
                Status status = Status.get();
                status.count = list.size();
                status.episodeLabel = label;
                status.mediaTitle = title;
                status.set(Status.READY, "已加载 " + list.size() + " 条弹幕",
                        label + (fromCache ? " · 缓存" : ""));
                if (danmakuView != null && settings.showStatusToast) {
                    danmakuView.showToast("弹幕已加载：" + list.size() + " 条", 3200);
                }
            }

            @Override
            public void onFailed(String reason) {
                Diag.add("匹配失败: " + reason);
                if (generation != resolveGeneration) {
                    return;
                }
                loading = false;
                Status status = Status.get();
                status.count = 0;
                status.set(Status.FAILED, "没能匹配到弹幕", reason);
                if (danmakuView != null) {
                    danmakuView.clearSource();
                    if (settings.showStatusToast) {
                        danmakuView.showToast(reason, 6000);
                    }
                }
            }
        });
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 24 ? text : text.substring(0, 24) + "…";
    }

    // ------------------------------------------------------------ 面板调用

    /** 自家全屏面板在前台时，弹幕和悬浮球先让开；离开后自动回来。 */
    public void setPanelVisible(boolean visible) {
        activityVisible = visible;
        updateDanmakuVisibility();
        if (visible) {
            hideFloatingPanel();
        }
        updateBubbleVisibility();
    }

    public boolean isPanelVisible() {
        return activityVisible;
    }

    // ------------------------------------------------------------ 自检接口（给面板用）

    public boolean isOverlayAttached() {
        return danmakuView != null && danmakuView.getParent() != null;
    }

    public String overlayInfo() {
        if (danmakuView == null || danmakuParams == null) {
            return "未创建";
        }
        String size = danmakuView.getWidth() + "×" + danmakuView.getHeight();
        return "已挂载 " + size + (danmakuView.getVisibility() == View.VISIBLE ? "" : "（已隐藏）");
    }

    public int loadedCount() {
        return danmakuView == null ? 0 : danmakuView.loadedCount();
    }

    public int visibleCount() {
        return danmakuView == null ? 0 : danmakuView.visibleCount();
    }

    public String sessionInfo() {
        PlaybackTracker.Snapshot snapshot = tracker.latest();
        if (!snapshot.hasSession) {
            return "无媒体会话";
        }
        String pkg = snapshot.packageName == null || snapshot.packageName.isEmpty()
                ? "未知应用" : snapshot.packageName;
        return pkg + (snapshot.playing ? " · 播放中" : " · 暂停");
    }

    public String screenInfo() {
        return screenWidth + "×" + screenHeight;
    }

    /** 面板里「重新挂载」：把弹幕窗拆掉重来，用于窗口被系统收走的情况。 */
    public void remountOverlay() {
        Diag.add("手动重新挂载悬浮窗");
        if (danmakuView != null) {
            try {
                windowManager.removeView(danmakuView);
            } catch (Throwable ignored) {
            }
            danmakuView = null;
        }
        readScreenSize();
        attachOverlay();
        Status.get().publish();
    }

    /** 在屏幕上打一条显眼的测试横幅，验证悬浮窗是否真的画出来了。 */
    public void showOverlayTest() {
        if (danmakuView == null || danmakuView.getParent() == null) {
            remountOverlay();
        }
        if (danmakuView != null) {
            showDanmaku();
            lastSessionAt = android.os.SystemClock.elapsedRealtime();
            danmakuView.showTestBanner(5000);
            Diag.add("已显示悬浮窗测试横幅");
        }
    }

    public void reloadSettings() {
        settings = prefs.settings();
        if (danmakuView != null) {
            danmakuView.applySettings(settings, screenHeight);
        }
        updateWindowGeometry();
        updateBubble();
        updateNotification();
    }

    public void rematch() {
        currentTitle = "";
        pendingTitle = "";
        pendingCount = 0;
        String title = tracker.latest().mediaTitle();
        if (title.isEmpty()) {
            Status.get().set(Status.WAITING_MEDIA, "等待播放", "当前没有检测到正在播放的视频");
            return;
        }
        startResolve(title);
    }

    public void manualLoad(String animeTitle, List<Danmaku> list) {
        loading = false;
        currentTitle = tracker.latest().mediaTitle();
        loadedLabel = animeTitle;
        if (danmakuView != null) {
            danmakuView.setSource(list);
            showDanmaku();
            if (settings.showStatusToast) {
                danmakuView.showToast("已手动载入：" + list.size() + " 条", 3200);
            }
        }
        Status status = Status.get();
        status.count = list.size();
        status.episodeLabel = animeTitle;
        status.set(Status.READY, "已手动载入 " + list.size() + " 条弹幕", animeTitle);
    }

    public void calibrate(long positionMs) {
        tracker.calibrate(positionMs);
        if (danmakuView != null) {
            danmakuView.showToast("已校准到 " + formatTime(positionMs), 2600);
        }
        Status.get().positionMs = positionMs;
        Status.get().publish();
    }

    public void toggleDanmaku() {
        settings.enabled = !settings.enabled;
        prefs.save(settings);
        if (danmakuView != null) {
            danmakuView.applySettings(settings, screenHeight);
        }
        updateNotification();
        if (danmakuView != null) {
            danmakuView.showToast(settings.enabled ? "弹幕已显示" : "弹幕已隐藏", 1600);
        }
    }

    public boolean danmakuEnabled() {
        return settings.enabled;
    }

    public long currentPositionMs() {
        return tracker.positionMs();
    }

    public void setManualPlaying(boolean playing) {
        tracker.setManualPlaying(playing);
    }

    public String loadedLabel() {
        return loadedLabel;
    }

    public String debugInfo() {
        return danmakuView == null ? "" : danmakuView.debugInfo();
    }

    public static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持弹幕悬浮窗在后台运行");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
    }

    private PendingIntent serviceIntent(String action) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, action.hashCode(), intent, flags);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_danmaku)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, PanelActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(
                        notifyIcon(), getString(R.string.action_toggle),
                        serviceIntent(ACTION_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(
                        notifyIcon(), getString(R.string.action_stop),
                        serviceIntent(ACTION_STOP)).build());
        return builder.build();
    }

    private android.graphics.drawable.Icon notifyIcon() {
        return android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_danmaku);
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Status status = Status.get();
        String text;
        if (status.state == Status.READY) {
            text = status.headline + (loadedLabel.isEmpty() ? "" : " · " + loadedLabel);
        } else {
            text = status.headline;
        }
        if (text.equals(lastNotificationText)) {
            return;
        }
        lastNotificationText = text;
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        readScreenSize();
        if (danmakuView != null) {
            danmakuView.applySettings(settings, screenHeight);
        }
        updateWindowGeometry();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tracker != null) {
            tracker.stop();
        }
        hideFloatingPanel();
        removeBubble();
        if (danmakuView != null) {
            try {
                windowManager.removeView(danmakuView);
            } catch (Exception ignored) {
            }
            danmakuView = null;
        }
        if (repository != null) {
            repository.shutdown();
        }
        handler.removeCallbacksAndMessages(null);
        instance = null;
        Status status = Status.get();
        status.serviceRunning = false;
        status.count = 0;
        if (status.state == Status.READY || status.state == Status.SEARCHING) {
            status.set(Status.IDLE, "未启动", "");
        } else {
            // 保留失败原因，别把「弹幕窗打不开」这类信息抹掉
            status.publish();
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
