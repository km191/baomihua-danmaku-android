package com.baomihua.danmaku.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 弹幕排版/调度引擎：与 Android 无关，可在纯 JVM 上跑测试和渲染预览。
 *
 * <p>渲染器每帧把「当前播放时间」喂进来，引擎负责：
 * <ul>
 *   <li>按时间把弹幕分配到轨道，滚动弹幕不重叠、不追尾；</li>
 *   <li>顶部/底部固定弹幕居中显示；</li>
 *   <li>显示区域、字号、速度、密度、合并重复、时间偏移；</li>
 *   <li>跳转/暂停后的重新同步。</li>
 * </ul>
 * 引擎只算坐标，颜色/描边/阴影/透明度由渲染层负责。
 */
public final class DanmakuEngine {

    /** 文本宽度测量：Android 用 Paint.measureText，JVM 测试用 FontMetrics。 */
    public interface Measurer {
        float measure(String text, float textPx);
    }

    public static final int DENSITY_SPARSE = 0;
    public static final int DENSITY_NORMAL = 1;
    public static final int DENSITY_DENSE = 2;

    /** 与 Settings 对齐的可调参数。 */
    public static final class Config {
        public float fontPx = 44f;
        public float speed = 1f;
        public float areaRatio = 0.5f;
        /** 从顶部往下排几行；0 表示按 areaRatio 自动。 */
        public int maxRows = 12;
        public int density = DENSITY_NORMAL;
        public boolean scaleWithViewport = true;
        public long timeOffsetMs = 0L;
        public boolean mergeDuplicates = true;
        public boolean allowTop = true;
        public boolean allowBottom = false;
        public int maxTextLength = 120;
        public long fixedDurationMs = 4500L;
        /** 等比缩放基准高度（1080p）。 */
        public float referenceHeightPx = 1080f;

        public Config copy() {
            Config c = new Config();
            c.fontPx = fontPx;
            c.speed = speed;
            c.areaRatio = areaRatio;
            c.maxRows = maxRows;
            c.density = density;
            c.scaleWithViewport = scaleWithViewport;
            c.timeOffsetMs = timeOffsetMs;
            c.mergeDuplicates = mergeDuplicates;
            c.allowTop = allowTop;
            c.allowBottom = allowBottom;
            c.maxTextLength = maxTextLength;
            c.fixedDurationMs = fixedDurationMs;
            c.referenceHeightPx = referenceHeightPx;
            return c;
        }
    }

    /** 一帧里的一条可见弹幕。复用对象，避免每帧分配。 */
    public static final class Sprite {
        public String text = "";
        public float x;
        public float baselineY;
        public float textPx;
        public int color = 0xFFFFFF;
        public boolean fixed;
    }

    public static final class Stats {
        public int loaded;
        public int dropped;
        public int active;
        public int currentIndex;

        @Override
        public String toString() {
            return "loaded=" + loaded + " dropped=" + dropped + " active=" + active;
        }
    }

    private static final class Item {
        long timeMs;
        String text;
        int mode;
        int color;
        int repeat = 1;
    }

    private static final class Active {
        Item item;
        String display;
        int lane;
        float width;
        long spawnMs;
        long endMs;
        boolean fixed;
        boolean reverse;
    }

    private static final class Lane {
        long scrollSpawnMs = Long.MIN_VALUE;
        float scrollWidth;
        long fixedEndMs = Long.MIN_VALUE;
    }

    private final Measurer measurer;
    private final Config cfg = new Config();

    private int width = 1080;
    private int height = 1080;
    private float fontPx = 44f;
    private float laneHeight = 60f;
    private int laneCount = 8;
    private float pxPerMs = 0.15f;
    private float gapPx = 26f;

    private final List<Item> items = new ArrayList<>();
    private final List<Active> active = new ArrayList<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final List<Sprite> sprites = new ArrayList<>();
    private int cursor;
    private long lastVideoMs = -1L;
    private long dropped;
    private boolean dirtyLanes = true;

    public DanmakuEngine(Measurer measurer) {
        this.measurer = measurer;
    }

    public Config config() {
        return cfg;
    }

    public void applyConfig(Config next) {
        cfg.fontPx = next.fontPx;
        cfg.speed = next.speed;
        cfg.areaRatio = next.areaRatio;
        cfg.maxRows = next.maxRows;
        cfg.density = next.density;
        cfg.scaleWithViewport = next.scaleWithViewport;
        cfg.timeOffsetMs = next.timeOffsetMs;
        cfg.mergeDuplicates = next.mergeDuplicates;
        cfg.allowTop = next.allowTop;
        cfg.allowBottom = next.allowBottom;
        cfg.maxTextLength = next.maxTextLength;
        cfg.fixedDurationMs = next.fixedDurationMs;
        cfg.referenceHeightPx = next.referenceHeightPx;
        dirtyLanes = true;
        recomputeLayout();
    }

    public void setViewport(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (w == width && h == height) {
            return;
        }
        width = w;
        height = h;
        dirtyLanes = true;
        recomputeLayout();
        active.clear();
    }

    public int viewportWidth() {
        return width;
    }

    public int viewportHeight() {
        return height;
    }

    private void recomputeLayout() {
        fontPx = cfg.fontPx;
        if (cfg.scaleWithViewport && cfg.referenceHeightPx > 0) {
            fontPx = cfg.fontPx * (height / cfg.referenceHeightPx);
        }
        fontPx = Math.max(10f, Math.min(fontPx, height * 0.16f));
        laneHeight = fontPx * 1.32f;
        int fitRows = Math.max(1, (int) (height / laneHeight));
        int areaRows;
        if (cfg.maxRows > 0) {
            // 行数模式：固定从顶部往下排 N 行，屏幕放不下就取能放下的最大行数。
            areaRows = Math.min(cfg.maxRows, fitRows);
        } else {
            int areaHeight = Math.max(1, (int) (height * clamp(cfg.areaRatio, 0.15f, 1f)));
            areaRows = Math.max(1, (int) (areaHeight / laneHeight));
        }
        laneCount = Math.max(1, Math.min(areaRows, fitRows));
        float durationSec = 9.0f / Math.max(0.3f, Math.min(cfg.speed, 3.0f));
        pxPerMs = width / (durationSec * 1000f);
        gapPx = fontPx * 0.65f;
        lanes.clear();
        for (int i = 0; i < laneCount; i++) {
            lanes.add(new Lane());
        }
        dirtyLanes = false;
    }

    /** 装载一批弹幕：过滤、合并重复、按时间排序。 */
    public void load(List<Danmaku> list) {
        items.clear();
        active.clear();
        cursor = 0;
        lastVideoMs = -1L;
        dropped = 0;
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Danmaku> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        String lastKey = null;
        long lastKeyTime = 0;
        for (Danmaku d : sorted) {
            if (d == null || d.text.trim().isEmpty()) {
                continue;
            }
            String text = d.text.trim();
            if (text.length() > cfg.maxTextLength) {
                text = text.substring(0, cfg.maxTextLength) + "…";
            }
            if (cfg.mergeDuplicates) {
                String key = text.replaceAll("\\s+", "");
                if (key.equals(lastKey) && d.timeMs - lastKeyTime <= 8000L) {
                    Item prev = items.get(items.size() - 1);
                    prev.repeat++;
                    lastKeyTime = d.timeMs;
                    continue;
                }
                lastKey = key;
                lastKeyTime = d.timeMs;
            }
            Item it = new Item();
            it.timeMs = d.timeMs;
            it.text = text;
            it.mode = d.mode;
            it.color = d.color;
            items.add(it);
        }
    }

    public int loadedCount() {
        return items.size();
    }

    /** 跳转后重新同步：清屏，并把游标移到当前时间之后的第一条。 */
    public void syncTo(long videoMs) {
        active.clear();
        for (Lane lane : lanes) {
            lane.scrollSpawnMs = Long.MIN_VALUE;
            lane.scrollWidth = 0f;
            lane.fixedEndMs = Long.MIN_VALUE;
        }
        cursor = lowerBound(videoMs);
        lastVideoMs = videoMs;
    }

    private int lowerBound(long videoMs) {
        int lo = 0;
        int hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (items.get(mid).timeMs + cfg.timeOffsetMs < videoMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * 每帧推进。{@code videoMs} 是播放器的当前播放位置；
     * 时间倒退超过 1.5 秒或前进超过 1.8 秒才视为跳转/卡顿，重新同步。
     * 阈值放宽是为了让采样抖动、暂停/恢复的一次对齐或偶发掉帧不至于把整屏弹幕清掉。
     */
    public void frame(long videoMs) {
        if (dirtyLanes) {
            recomputeLayout();
        }
        if (lastVideoMs < 0) {
            syncTo(videoMs);
            return;
        }
        long delta = videoMs - lastVideoMs;
        if (delta == 0L && !active.isEmpty()) {
            return;
        }
        if (delta < -1500L || delta > 1800L) {
            syncTo(videoMs);
            return;
        }
        lastVideoMs = videoMs;
        step(videoMs);
    }

    private void step(long videoMs) {
        // 1. 移除已经离开屏幕的
        for (int i = active.size() - 1; i >= 0; i--) {
            Active a = active.get(i);
            if (videoMs >= a.endMs) {
                Active last = active.remove(active.size() - 1);
                if (i < active.size()) {
                    active.set(i, last);
                }
            }
        }

        // 2. 投放到期弹幕
        int maxActive = maxActiveForDensity();
        while (cursor < items.size()) {
            Item it = items.get(cursor);
            long due = it.timeMs + cfg.timeOffsetMs;
            if (due > videoMs) {
                break;
            }
            cursor++;
            if (active.size() >= maxActive && !isFixed(it)) {
                dropped++;
                continue;
            }
            if (!spawn(it, videoMs)) {
                dropped++;
            }
        }

        // 3. 生成坐标（复用 Sprite 对象，避免每帧分配）
        for (int i = 0; i < active.size(); i++) {
            Active a = active.get(i);
            Sprite s;
            if (i < sprites.size()) {
                s = sprites.get(i);
            } else {
                s = new Sprite();
                sprites.add(s);
            }
            float p = (videoMs - a.spawnMs) / (float) Math.max(1L, a.endMs - a.spawnMs);
            s.text = a.display;
            s.color = a.item.color;
            s.textPx = fontPx;
            s.fixed = a.fixed;
            if (a.fixed) {
                s.x = (width - a.width) / 2f;
            } else if (a.reverse) {
                s.x = -a.width + (width + a.width) * p;
            } else {
                s.x = width - (width + a.width) * p;
            }
            s.baselineY = Math.min(a.lane, laneCount - 1) * laneHeight + fontPx;
        }
        while (sprites.size() > active.size()) {
            sprites.remove(sprites.size() - 1);
        }
    }

    private int maxActiveForDensity() {
        switch (cfg.density) {
            case DENSITY_SPARSE:
                return 24;
            case DENSITY_DENSE:
                return 140;
            default:
                return 64;
        }
    }

    private boolean isFixed(Item it) {
        return it.mode == Danmaku.MODE_TOP || it.mode == Danmaku.MODE_BOTTOM;
    }

    private boolean spawn(Item it, long nowMs) {
        boolean fixed = isFixed(it);
        if (fixed && it.mode == Danmaku.MODE_TOP && !cfg.allowTop) {
            return false;
        }
        if (fixed && it.mode == Danmaku.MODE_BOTTOM && !cfg.allowBottom) {
            return false;
        }
        String display = it.repeat > 1 ? it.text + " ×" + it.repeat : it.text;
        float w = Math.max(1f, measurer.measure(display, fontPx));
        if (w > width * 1.6f) {
            return false;
        }
        int lane = pickLane(it.mode, nowMs, w);
        if (lane < 0) {
            return false;
        }
        Active a = new Active();
        a.item = it;
        a.display = display;
        a.lane = lane;
        a.width = w;
        a.spawnMs = nowMs;
        a.fixed = fixed;
        a.reverse = it.mode == Danmaku.MODE_REVERSE;
        if (fixed) {
            a.endMs = nowMs + cfg.fixedDurationMs;
            lanes.get(lane).fixedEndMs = Math.max(lanes.get(lane).fixedEndMs, a.endMs);
        } else {
            long travel;
            if (a.reverse) {
                travel = (long) ((width + w) / pxPerMs);
            } else {
                travel = (long) ((width + w) / pxPerMs);
            }
            a.endMs = nowMs + travel;
            lanes.get(lane).scrollSpawnMs = nowMs;
            lanes.get(lane).scrollWidth = w;
        }
        active.add(a);
        return true;
    }

    private int pickLane(int mode, long nowMs, float w) {
        if (mode == Danmaku.MODE_BOTTOM) {
            for (int i = laneCount - 1; i >= 0; i--) {
                if (laneFree(i, nowMs, w)) {
                    return i;
                }
            }
            return -1;
        }
        for (int i = 0; i < laneCount; i++) {
            if (laneFree(i, nowMs, w)) {
                return i;
            }
        }
        return -1;
    }

    private boolean laneFree(int index, long nowMs, float w) {
        Lane lane = lanes.get(index);
        if (lane.fixedEndMs > nowMs) {
            return false;
        }
        if (lane.scrollSpawnMs == Long.MIN_VALUE) {
            return true;
        }
        long elapsed = nowMs - lane.scrollSpawnMs;
        if (elapsed < 0) {
            return false;
        }
        // 上一条的尾巴必须已经离开右边缘，且新弹幕不会追上它
        float travelled = elapsed * pxPerMs;
        return travelled >= lane.scrollWidth + gapPx;
    }

    public int size() {
        return Math.min(sprites.size(), active.size());
    }

    /** 当前可用轨道数（= 弹幕行数）。 */
    public int laneCount() {
        return laneCount;
    }
    public Sprite spriteAt(int index) {
        return sprites.get(index);
    }

    public Stats stats() {
        Stats s = new Stats();
        s.loaded = items.size();
        s.dropped = (int) Math.min(Integer.MAX_VALUE, dropped);
        s.active = active.size();
        s.currentIndex = cursor;
        return s;
    }

    /** 清屏但保留装载的数据（例如暂停显示开关）。 */
    public void clearScreen() {
        active.clear();
        sprites.clear();
        for (Lane lane : lanes) {
            lane.scrollSpawnMs = Long.MIN_VALUE;
            lane.scrollWidth = 0f;
            lane.fixedEndMs = Long.MIN_VALUE;
        }
    }

    /** 当前时间点附近的下一条弹幕时间，用于判断是否还有内容。 */
    public long nextDueMs() {
        if (cursor >= items.size()) {
            return -1L;
        }
        return items.get(cursor).timeMs;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
