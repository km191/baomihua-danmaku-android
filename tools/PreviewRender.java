import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmakuEngine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 用 AWT 把弹幕引擎真实渲染成 PNG，用来在没有手机的情况下检查排版效果。
 * 输出：docs/preview-danmaku.png（四帧拼接）
 */
public final class PreviewRender {

    private static final int FRAME_W = 620;
    private static final int FRAME_H = 1100;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String fontName = pickFont();
        Font base = new Font(fontName, Font.BOLD, 26);

        DanmakuEngine engine = new DanmakuEngine(new DanmakuEngine.Measurer() {
            @Override
            public float measure(String text, float textPx) {
                BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = probe.createGraphics();
                Font f = base.deriveFont(textPx);
                g.setFont(f);
                FontMetrics fm = g.getFontMetrics();
                float w = fm.stringWidth(text);
                g.dispose();
                return w;
            }
        });
        DanmakuEngine.Config cfg = engine.config();
        cfg.fontPx = 26f;
        cfg.referenceHeightPx = FRAME_H;
        cfg.scaleWithViewport = false;
        cfg.areaRatio = 0.55f;
        cfg.speed = 1f;
        cfg.density = DanmakuEngine.DENSITY_DENSE;
        cfg.allowTop = true;
        engine.setViewport(FRAME_W, FRAME_H);
        engine.applyConfig(cfg);

        List<Danmaku> list = new ArrayList<>();
        String[] pool = {
                "前方高能预警", "这个作画太强了", "哈哈哈笑死", "泪目QAQ", "名场面来了",
                "这段配乐绝了", "第一次看，别剧透", "三刷路过", "弹幕护体", "此处应有掌声",
                "我哭了", "经费在燃烧", "分镜好绝", "这里伏笔", "考试周还来看", "妈妈问我为什么跪着看",
                "太燃了吧", "这个转场", "神作预定", "笑死我了", "好耶", "这一嗓子", "回忆杀",
                "cp发糖了", "此条弹幕已被屏蔽", "8说了，开饭", "这画面值了", "隔壁的都馋哭了"
        };
        long t = 800;
        int i = 0;
        int count = 0;
        // 0~40 秒，平均每秒 2~4 条
        while (t < 42000L) {
            list.add(new Danmaku(t, pool[i % pool.length], Danmaku.MODE_SCROLL, 0xFFFFFF));
            t += 180 + (i * 37 % 260);
            i++;
            count++;
            if (count % 23 == 0) {
                list.add(new Danmaku(t, "【顶部固定】这一幕封神", Danmaku.MODE_TOP, 0xFFD166));
                t += 400;
                i++;
            }
        }
        engine.load(list);
        System.out.println("装载弹幕 " + engine.loadedCount() + " 条");

        long[] moments = {9000L, 15000L, 22000L, 30000L};
        final int gutter = 18;
        final int captionH = 34;
        BufferedImage sheet = new BufferedImage(FRAME_W * 2 + gutter * 3,
                FRAME_H * 2 + captionH * 2 + gutter * 3, BufferedImage.TYPE_INT_RGB);
        Graphics2D sheetG = sheet.createGraphics();
        sheetG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sheetG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        sheetG.setColor(new Color(0x08090C));
        sheetG.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());

        engine.syncTo(moments[0] - 4000);
        long simulated = moments[0] - 4000;
        for (int index = 0; index < moments.length; index++) {
            long target = moments[index];
            while (simulated < target) {
                simulated += 16;
                engine.frame(simulated);
            }
            BufferedImage frame = renderFrame(engine, base, simulated);
            int x = gutter + (index % 2) * (FRAME_W + gutter);
            int y = gutter + (index / 2) * (FRAME_H + captionH + gutter);
            // 标题
            sheetG.setColor(new Color(0x8E98A8));
            sheetG.setFont(base.deriveFont(13f));
            sheetG.drawString(String.format("t = %.1fs   屏上 %d 条", simulated / 1000.0,
                    engine.size()), x + 6, y + 2);
            sheetG.drawImage(frame, x, y + 10, null);
            sheetG.setColor(new Color(0x2A3140));
            sheetG.setStroke(new BasicStroke(1f));
            sheetG.drawRect(x - 1, y + 9, FRAME_W + 1, FRAME_H + 1);
            System.out.println("t=" + (simulated / 1000) + "s 可见弹幕 " + engine.size() + " 条");
        }
        sheetG.dispose();

        File out = new File(args.length > 0 ? args[0] : "docs/preview-danmaku.png");
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        ImageIO.write(sheet, "png", out);
        System.out.println("已输出 " + out.getAbsolutePath() + " (" + sheet.getWidth() + "x"
                + sheet.getHeight() + ")");
    }

    private static BufferedImage renderFrame(DanmakuEngine engine, Font base, long timeMs) {
        BufferedImage img = new BufferedImage(FRAME_W, FRAME_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // ---- 假的视频画面（代表被叠加的播放器）
        g.setPaint(new GradientPaint(0, 0, new Color(0x13233A), FRAME_W, FRAME_H,
                new Color(0x2A1220)));
        g.fillRect(0, 0, FRAME_W, FRAME_H);
        // 远景光斑
        for (int i = 0; i < 7; i++) {
            int cx = (int) (FRAME_W * (0.15 + 0.12 * i));
            int cy = (int) (FRAME_H * (0.28 + 0.06 * Math.sin(i * 1.7)));
            int r = 60 + i * 26;
            g.setColor(new Color(255, 255, 255, 10 + i * 2));
            g.fill(new Ellipse2D.Float(cx - r / 2f, cy - r / 2f, r, r));
        }
        // 人物剪影
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(new Ellipse2D.Float(FRAME_W * 0.30f, FRAME_H * 0.42f, 190, 210));
        g.fill(new RoundRectangle2D.Float(FRAME_W * 0.26f, FRAME_H * 0.58f, 280, 420, 90, 90));
        g.setColor(new Color(255, 255, 255, 16));
        for (int i = 0; i < 5; i++) {
            g.fillRect(0, (int) (FRAME_H * 0.16f) + i * 14, FRAME_W, 3);
        }

        // ---- 播放器 UI（表示这是别人家的播放器）
        g.setColor(new Color(0, 0, 0, 90));
        g.fillRect(0, FRAME_H - 64, FRAME_W, 64);
        g.setColor(new Color(255, 255, 255, 190));
        g.setFont(base.deriveFont(13f));
        g.drawString("▶ 12:34 / 24:00", 18, FRAME_H - 30);
        g.setColor(new Color(255, 255, 255, 60));
        g.fillRoundRect(150, FRAME_H - 38, FRAME_W - 200, 5, 5, 5);
        g.setColor(new Color(0x4EA1FF));
        g.fillRoundRect(150, FRAME_H - 38, (int) ((FRAME_W - 200) * 0.42f), 5, 5, 5);

        // ---- 弹幕
        for (int i = 0; i < engine.size(); i++) {
            DanmakuEngine.Sprite s = engine.spriteAt(i);
            Font f = base.deriveFont(s.textPx);
            g.setFont(f);
            Color fill = new Color(s.color == 0 ? 0xFFFFFF : s.color);
            // 描边
            g.setColor(new Color(0, 0, 0, 200));
            g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.font.GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), s.text);
            java.awt.Shape outline = gv.getOutline(s.x, s.baselineY);
            g.draw(outline);
            // 阴影
            g.setColor(new Color(0, 0, 0, 110));
            g.fill(gv.getOutline(s.x + 2f, s.baselineY + 2f));
            // 正文
            g.setColor(fill);
            g.fill(outline);
        }

        // 区域提示线
        g.setColor(new Color(0x4EA1FF));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{6f, 6f}, 0f));
        g.draw(new RoundRectangle2D.Float(4, 4, FRAME_W - 8, FRAME_H - 8, 18, 18));
        g.setFont(base.deriveFont(11f));
        g.setColor(new Color(255, 255, 255, 120));
        g.drawString("悬浮窗覆盖区域 · " + String.format("%.3f", timeMs / 1000.0) + "s",
                14, FRAME_H - 74);
        g.dispose();
        return img;
    }

    private static String pickFont() {
        String[] wanted = {"Microsoft YaHei", "微软雅黑", "Noto Sans CJK SC", "SimHei", "SimSun"};
        List<String> available = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String w : wanted) {
            if (available.contains(w)) {
                return w;
            }
        }
        return Font.SANS_SERIF;
    }

    private PreviewRender() {
    }
}
