package com.baomihua.danmaku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmakuEngine;
import com.baomihua.danmaku.core.Settings;

import java.util.List;
import java.util.Locale;

/**
 * 悬浮窗里的弹幕画布。
 *
 * <p>排版全部交给 {@link DanmakuEngine}，这里只负责按帧取当前播放时间、
 * 把引擎算好的坐标画成带描边/阴影的文字。
 */
public final class DanmakuView extends View implements Choreographer.FrameCallback {

    private final DanmakuEngine engine;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF toastRect = new RectF();

    private PlaybackTracker tracker;
    private Settings settings = Settings.defaults();
    private float scaledDensity = 2f;
    private float density = 2f;
    private int screenHeightPx = 1080;

    private boolean frameScheduled;
    private long lastDrawnPosition = -1L;
    private long appliedOffsetMs = Long.MIN_VALUE;
    private long testBannerUntil;
    private String toastText;
    private long toastUntil;

    public DanmakuView(Context context) {
        super(context);
        setWillNotDraw(false);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        strokePaint.setTypeface(textPaint.getTypeface());
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        toastPaint.setColor(0xD910141B);

        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;

        engine = new DanmakuEngine(new DanmakuEngine.Measurer() {
            @Override
            public float measure(String text, float textPx) {
                textPaint.setTextSize(textPx);
                return textPaint.measureText(text);
            }
        });
        engine.setViewport(Math.max(1, getWidth()), Math.max(1, getHeight()));
    }

    public void setTracker(PlaybackTracker tracker) {
        this.tracker = tracker;
    }

    public PlaybackTracker tracker() {
        return tracker;
    }

    public DanmakuEngine engine() {
        return engine;
    }

    /** 应用显示设置；屏幕高度用于等比缩放基准。 */
    public void applySettings(Settings s, int screenHeight) {
        this.settings = s;
        this.screenHeightPx = Math.max(1, screenHeight);
        DanmakuEngine.Config cfg = engine.config();
        cfg.fontPx = Math.max(10f, s.fontSp * scaledDensity);
        cfg.referenceHeightPx = screenHeightPx;
        cfg.scaleWithViewport = s.scaleWithViewport;
        cfg.areaRatio = s.areaRatio();
        cfg.speed = s.speed;
        cfg.density = s.density;
        cfg.mergeDuplicates = s.mergeDuplicates;
        cfg.timeOffsetMs = s.timeOffsetMs;
        cfg.allowTop = true;
        // 底部弹幕只在“全屏显示区域”时出现，和桌面版规则一致
        cfg.allowBottom = s.allowBottom && s.areaPercent >= 100;
        engine.applyConfig(cfg);
        // 时间偏移变了要重新对齐时间轴，否则已上屏的弹幕位置会错乱
        if (appliedOffsetMs != cfg.timeOffsetMs) {
            appliedOffsetMs = cfg.timeOffsetMs;
            if (tracker != null) {
                engine.syncTo(tracker.positionMs());
            }
        }
        invalidate();
    }

    public void setSource(List<Danmaku> list) {
        engine.load(list);
        if (tracker != null) {
            engine.syncTo(tracker.positionMs());
        }
        lastDrawnPosition = -1L;
        invalidate();
        schedule();
    }

    /** 显示 N 毫秒的测试横幅。 */
    public void showTestBanner(long durationMs) {
        testBannerUntil = SystemClock.uptimeMillis() + Math.max(1200L, durationMs);
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        if (!isAttachedToWindow()) {
            post(new Runnable() {
                @Override
                public void run() {
                    showTestBanner(800);
                }
            });
        }
        invalidate();
        schedule();
    }

    public void clearSource() {
        engine.load(null);
        lastDrawnPosition = -1L;
        invalidate();
    }

    public void showToast(String text, long durationMs) {
        toastText = text;
        toastUntil = SystemClock.uptimeMillis() + Math.max(800, durationMs);
        invalidate();
        schedule();
    }

    public int visibleCount() {
        return engine.size();
    }

    public int loadedCount() {
        return engine.loadedCount();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        engine.setViewport(Math.max(1, w), Math.max(1, h));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        schedule();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE) {
            lastDrawnPosition = -1L;
            invalidate();
            schedule();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        frameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        if (!isAttachedToWindow()) {
            return;
        }
        long position = tracker == null ? 0L : tracker.positionMs();
        engine.frame(position);
        boolean moved = position != lastDrawnPosition;
        lastDrawnPosition = position;
        boolean testActive = SystemClock.uptimeMillis() < testBannerUntil;
        if (toastText != null && SystemClock.uptimeMillis() > toastUntil) {
            toastText = null;
            invalidate();
        }
        if ((moved || testActive) && (settings.enabled || toastText != null || testActive)) {
            invalidate();
        }
        schedule();
    }

    private void schedule() {
        if (frameScheduled) {
            return;
        }
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        if (!isAttachedToWindow()) {
            // 窗口刚 addView 完还没 attach，等挂上再来
            post(new Runnable() {
                @Override
                public void run() {
                    schedule();
                }
            });
            return;
        }
        // 没有弹幕、也没有提示时不必空转，省电
        if (engine.loadedCount() == 0 && toastText == null
                && SystemClock.uptimeMillis() >= testBannerUntil) {
            return;
        }
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean testActive = SystemClock.uptimeMillis() < testBannerUntil;
        if (!settings.enabled) {
            if (toastText != null) {
                drawToast(canvas);
            }
            if (testActive) {
                drawTestBanner(canvas);
            }
            return;
        }
        int alpha = (int) (255 * Math.max(0f, Math.min(1f, settings.opacity)));
        textPaint.setAlpha(alpha);
        strokePaint.setAlpha(Math.min(255, (int) (alpha * 0.85f)));

        float shadowRadius = 0f;
        if (settings.shadow > 0f) {
            shadowRadius = 0.22f * settings.fontSp * scaledDensity * settings.shadow;
            textPaint.setShadowLayer(shadowRadius, shadowRadius * 0.35f, shadowRadius * 0.45f,
                    ((int) (0x99 * settings.shadow) << 24) | 0x000000);
        } else {
            textPaint.clearShadowLayer();
        }

        float strokeWidth = settings.strokeDp * density;
        int count = engine.size();
        for (int i = 0; i < count; i++) {
            DanmakuEngine.Sprite sprite = engine.spriteAt(i);
            if (sprite.text == null || sprite.text.isEmpty()) {
                continue;
            }
            textPaint.setTextSize(sprite.textPx);
            textPaint.setColor(0xFF000000 | (sprite.color & 0xFFFFFF));
            if (strokeWidth > 0.4f) {
                strokePaint.setTextSize(sprite.textPx);
                strokePaint.setStrokeWidth(strokeWidth);
                strokePaint.setColor(0xE6000000);
                canvas.drawText(sprite.text, sprite.x, sprite.baselineY, strokePaint);
            }
            canvas.drawText(sprite.text, sprite.x, sprite.baselineY, textPaint);
        }
        if (toastText != null) {
            drawToast(canvas);
        }
        if (testActive) {
            drawTestBanner(canvas);
        }
    }

    /** 5 秒测试横幅：能看见它，就说明悬浮窗真的画在这层了。 */
    private void drawTestBanner(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float boxW = Math.min(w * 0.86f, textSizePx(22f) * 15f);
        float boxH = textSizePx(22f) * 4.4f;
        float left = (w - boxW) / 2f;
        float top = (h - boxH) / 2f;
        toastPaint.setAlpha(225);
        toastPaint.setColor(0xE6FF7A45);
        RectF box = new RectF(left, top, left + boxW, top + boxH);
        canvas.drawRoundRect(box, boxH * 0.16f, boxH * 0.16f, toastPaint);
        textPaint.clearShadowLayer();
        textPaint.setAlpha(255);
        textPaint.setColor(0xFF1A1207);
        textPaint.setTextSize(textSizePx(18f));
        String line1 = "悬浮窗在这 ✓";
        float x1 = (w - textPaint.measureText(line1)) / 2f;
        canvas.drawText(line1, x1, top + boxH * 0.40f, textPaint);
        textPaint.setTextSize(textSizePx(11f));
        String line2 = getWidth() + "×" + getHeight() + " · 能看到这条就说明叠加成功";
        float x2 = (w - textPaint.measureText(line2)) / 2f;
        canvas.drawText(line2, x2, top + boxH * 0.74f, textPaint);
    }

    private float textSizePx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }

    private void drawToast(Canvas canvas) {
        long remaining = toastUntil - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            toastText = null;
            return;
        }
        float alpha = Math.min(1f, remaining / 600f);
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f,
                getResources().getDisplayMetrics());
        textPaint.setAlpha((int) (230 * alpha));
        textPaint.clearShadowLayer();
        textPaint.setTextSize(textSize);
        float padding = textSize * 0.75f;
        float width = textPaint.measureText(toastText) + padding * 2f;
        float height = textSize * 2.0f;
        float left = (getWidth() - width) / 2f;
        float top = Math.max(textSize * 0.8f, getHeight() * 0.02f);
        toastRect.set(left, top, left + width, top + height);
        toastPaint.setAlpha((int) (205 * alpha));
        canvas.drawRoundRect(toastRect, height / 2f, height / 2f, toastPaint);
        textPaint.setColor(0xFFE8ECF3);
        textPaint.setAlpha((int) (235 * alpha));
        canvas.drawText(toastText, left + padding, top + height * 0.66f, textPaint);
    }

    /** 便于日志排查。 */
    public String debugInfo() {
        DanmakuEngine.Stats stats = engine.stats();
        return String.format(Locale.US, "loaded=%d active=%d dropped=%d", stats.loaded,
                stats.active, stats.dropped);
    }
}
