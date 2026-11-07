package com.baomihua.danmaku;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * 播放进度跟踪：对应桌面版的 PlayerSampler。
 *
 * <p>每 {@link #POLL_MS} 采一次媒体会话，拿到标题、时长、播放状态和位置；
 * 两次采样之间用上次的速度外推，这样弹幕能逐帧平滑移动。
 * 播放器不支持媒体会话时，可以用手动校准起一个本地时钟。
 */
public final class PlaybackTracker {

    public static final long POLL_MS = 400L;

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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Snapshot last = new Snapshot();

    private long anchorPos;
    private long anchorAt = SystemClock.elapsedRealtime();
    private boolean anchorPlaying;
    private float anchorSpeed = 1f;
    private boolean manual;
    private Runnable tick;
    private boolean running;

    public PlaybackTracker(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start(Runnable onTick) {
        this.tick = onTick;
        if (running) {
            return;
        }
        running = true;
        handler.post(pollRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(pollRunnable);
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
            return Math.max(0L, anchorPos + (long) ((now - anchorAt) * anchorSpeed));
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

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                poll();
            } catch (Exception ignored) {
                // 播放器会话随时可能消失，忽略
            }
            if (running) {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void poll() {
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
        if (chosen != null) {
            PlaybackState state = chosen.getPlaybackState();
            MediaMetadata metadata = chosen.getMetadata();
            snapshot.hasSession = true;
            snapshot.packageName = chosen.getPackageName() == null ? "" : chosen.getPackageName();
            if (state != null) {
                snapshot.playbackState = state.getState();
                snapshot.playing = state.getState() == PlaybackState.STATE_PLAYING;
                snapshot.speed = state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1f;
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
            long position = state != null ? state.getPosition() : 0L;
            anchorPos = Math.max(0L, position);
            anchorAt = SystemClock.elapsedRealtime();
            anchorPlaying = snapshot.playing;
            anchorSpeed = snapshot.speed;
            manual = false;
        } else {
            snapshot.hasSession = false;
            snapshot.speed = anchorSpeed;
            snapshot.packageName = "";
            if (manual) {
                snapshot.playing = anchorPlaying;
            } else {
                // 关键：会话没了就把时钟停在当前位置，
                // 否则 positionMs() 会一直外推，退出播放器后弹幕继续往前跑。
                anchorPos = positionMs();
                anchorAt = SystemClock.elapsedRealtime();
                anchorPlaying = false;
                snapshot.playing = false;
            }
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
