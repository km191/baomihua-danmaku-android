package com.baomihua.danmaku;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;

/**
 * 播放进度跟踪：对应桌面版的 PlayerSampler。
 *
 * <p>每 {@link #POLL_MS} 采一次媒体会话，拿到标题、时长、播放状态和位置；
 * 两次采样之间用上次的速度外推，这样弹幕能逐帧平滑移动。
 *
 * <p>两个细节决定了「流不流畅」：
 * <ul>
 *   <li>采样在独立线程做，{@code getActiveSessions()/getPlaybackState()} 的 binder 调用
 *       不会卡住渲染帧；</li>
 *   <li>播放器上报的位置常常是整秒或带抖动，不能每次采样都把时间轴硬拉过去。
 *       采样之间让本地时钟自由外推，只有真正拖动进度条（偏差超过 2.5 秒）才硬同步；
 *       暂停/恢复时各对齐一次，避免整秒量化把弹幕一顿一顿地拽回去。</li>
 * </ul>
 */
public final class PlaybackTracker {

    public static final long POLL_MS = 400L;

    /** 偏差超过这个值才认为是用户跳转，直接硬同步。 */
    private static final long HARD_SYNC_MS = 2500L;

    public static final class Snapshot {
        public boolean hasSession;
        public String title = "";
        public String subtitle = "";
        public String album = "";
        public String packageName = "";
        public long durationMs;
        public boolean playing;
        public float speed = 1f;
        public long positionMs;
        /** android.media.session.PlaybackState 的状态码。 */
        public int playbackState = android.media.session.PlaybackState.STATE_NONE;

        /** 拼一份用于识别片名的文本。 */
        public String mediaTitle() {
            String base = title == null ? "" : title.trim();
            String sub = subtitle == null ? "" : subtitle.trim();
            if (sub.isEmpty()) {
                return base;
            }
            if (!base.isEmpty() && sub.contains(base)) {
                return sub;
            }
            return (base + " " + sub).trim();
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread pollThread;
    private Handler pollHandler;

    private Snapshot last = new Snapshot();

    private long anchorPos;
    private long anchorAt = SystemClock.elapsedRealtime();
    private boolean anchorPlaying;
    private float anchorSpeed = 1f;
    private boolean manual;
    private boolean wasPlaying;

    private Runnable tick;
    private volatile boolean running;

    public PlaybackTracker(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start(Runnable onTick) {
        this.tick = onTick;
        if (running) {
            return;
        }
        running = true;
        ensurePollThread();
        pollHandler.post(pollRunnable);
    }

    public void stop() {
        running = false;
        Handler handler = pollHandler;
        pollHandler = null;
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
        }
        HandlerThread thread = pollThread;
        pollThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    public Snapshot latest() {
        return last;
    }

    public boolean isPlaying() {
        return anchorPlaying;
    }

    public boolean hasSession() {
        return last.hasSession;
    }

    /** 当前播放位置（毫秒），随播放状态外推。 */
    public long positionMs() {
        long now = SystemClock.elapsedRealtime();
        if (anchorPlaying) {
            long elapsed = Math.max(0L, now - anchorAt);
            return Math.max(0L, anchorPos + (long) (elapsed * anchorSpeed));
        }
        return Math.max(0L, anchorPos);
    }

    /** 手动校准：把播放器当前时间告诉伴侣（对应桌面版的「手动时间校准」）。 */
    public void calibrate(long positionMs) {
        anchorPos = Math.max(0L, positionMs);
        anchorAt = SystemClock.elapsedRealtime();
        anchorPlaying = true;
        anchorSpeed = 1f;
        manual = true;
        last.positionMs = anchorPos;
    }

    public void setManualPlaying(boolean playing) {
        if (!manual) {
            return;
        }
        anchorPos = positionMs();
        anchorAt = SystemClock.elapsedRealtime();
        anchorPlaying = playing;
    }

    private synchronized void ensurePollThread() {
        if (pollThread != null) {
            return;
        }
        pollThread = new HandlerThread("danmu-media-poll", Process.THREAD_PRIORITY_BACKGROUND);
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            Snapshot snapshot = null;
            try {
                snapshot = readSnapshot();
            } catch (Throwable ignored) {
                // 播放器会话随时可能消失，忽略
            }
            if (snapshot != null && running) {
                final Snapshot ready = snapshot;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applySnapshot(ready);
                    }
                });
            }
            Handler handler = pollHandler;
            if (running && handler != null) {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    /** 在后台线程读媒体会话，不碰时间轴状态。 */
    private Snapshot readSnapshot() {
        List<MediaController> sessions = MediaWatcher.activeSessions(context);
        MediaController chosen = null;
        for (MediaController controller : sessions) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                chosen = controller;
                break;
            }
        }
        if (chosen == null && !sessions.isEmpty()) {
            chosen = sessions.get(0);
        }

        Snapshot snapshot = new Snapshot();
        if (chosen == null) {
            snapshot.hasSession = false;
            snapshot.packageName = "";
            return snapshot;
        }

        PlaybackState state = chosen.getPlaybackState();
        MediaMetadata metadata = chosen.getMetadata();
        snapshot.hasSession = true;
        snapshot.packageName = chosen.getPackageName() == null ? "" : chosen.getPackageName();
        if (state != null) {
            snapshot.playbackState = state.getState();
            snapshot.playing = state.getState() == PlaybackState.STATE_PLAYING;
            snapshot.speed = state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1f;
            snapshot.positionMs = Math.max(0L, state.getPosition());
        }
        if (metadata != null) {
            snapshot.title = text(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            String display = text(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            if (!display.isEmpty()) {
                snapshot.title = display;
            }
            snapshot.subtitle =
                    text(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            snapshot.album = text(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            snapshot.durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        return snapshot;
    }

    /** 回到主线程：让本地时钟自由外推，只在跳转/暂停/恢复时对齐。 */
    private void applySnapshot(Snapshot snapshot) {
        if (!running || snapshot == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (snapshot.hasSession) {
            long reported = Math.max(0L, snapshot.positionMs);
            long predicted = positionMs();
            if (snapshot.playing) {
                if (!wasPlaying) {
                    // 开始播放或从暂停恢复：对齐到播放器当前位置
                    anchorPos = reported;
                } else if (Math.abs(reported - predicted) > HARD_SYNC_MS) {
                    // 真正的拖动进度条：硬同步，引擎会跟着清屏重排
                    anchorPos = reported;
                } else {
                    // 整秒量化/轻微抖动：保持本地时钟，画面才不会一顿一顿
                    anchorPos = predicted;
                }
                anchorAt = now;
                anchorPlaying = true;
                anchorSpeed = snapshot.speed;
            } else {
                // 暂停：停在当前位置，不追整秒量化的上报值
                anchorPos = predicted;
                anchorAt = now;
                anchorPlaying = false;
                anchorSpeed = snapshot.speed;
            }
            wasPlaying = snapshot.playing;
            manual = false;
            snapshot.positionMs = anchorPos;
        } else if (manual) {
            snapshot.speed = anchorSpeed;
            snapshot.playing = anchorPlaying;
            snapshot.positionMs = positionMs();
        } else {
            // 会话没了就把时钟停在当前位置，退出播放器后弹幕不会再往前跑。
            anchorPos = positionMs();
            anchorAt = now;
            anchorPlaying = false;
            snapshot.playing = false;
            snapshot.positionMs = anchorPos;
        }
        last = snapshot;
        if (tick != null) {
            tick.run();
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
