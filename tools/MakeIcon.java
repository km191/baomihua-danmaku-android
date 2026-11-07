import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成各密度的应用图标 PNG（与 drawable/ic_launcher_foreground.xml 同一套造型）。
 * API 26+ 用自适应图标，低版本用这些 PNG。
 */
public final class MakeIcon {

    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "app/src/main/res");
        int[][] densities = {
                {48, 0}, {72, 1}, {96, 2}, {144, 3}, {192, 4}
        };
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int i = 0; i < dirs.length; i++) {
            int size = densities[i][0];
            BufferedImage img = draw(size);
            File dir = new File(res, dirs[i]);
            dir.mkdirs();
            File out = new File(dir, "ic_launcher.png");
            ImageIO.write(img, "png", out);
            System.out.println("写出 " + out.getPath() + " (" + size + "px)");
        }
    }

    private static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size / 108f;
        g.scale(s, s);

        // 圆角底板
        g.setPaint(new GradientPaint(0, 0, new Color(0x2A1C2E), 108, 108, new Color(0x121018)));
        g.fill(new RoundRectangle2D.Float(0, 0, 108, 108, 26, 26));

        // 弹幕条
        bar(g, 14, 26, 44, new Color(0xFF7A45));
        bar(g, 24, 42, 46, new Color(0xE8ECF3));
        bar(g, 14, 58, 38, new Color(0x4EA1FF));

        // 爆米花桶
        Path2D bucket = new Path2D.Float();
        bucket.moveTo(40, 74);
        bucket.lineTo(74, 74);
        bucket.lineTo(69, 98);
        bucket.lineTo(45, 98);
        bucket.closePath();
        g.setColor(new Color(0xF6EFE3));
        g.fill(bucket);
        g.setColor(new Color(0xFF7A45));
        for (int i = 0; i < 3; i++) {
            Path2D stripe = new Path2D.Float();
            float x = 43 + i * 13;
            stripe.moveTo(x, 74);
            stripe.lineTo(x + 6, 74);
            stripe.lineTo(x + 4, 98);
            stripe.lineTo(x - 2, 98);
            stripe.closePath();
            g.fill(stripe);
        }

        // 玉米花
        g.setColor(new Color(0xFFF6DC));
        g.fill(new Ellipse2D.Float(41, 67, 14, 14));
        g.fill(new Ellipse2D.Float(52, 65, 16, 16));
        g.fill(new Ellipse2D.Float(65, 69, 12, 12));
        g.dispose();
        return img;
    }

    private static void bar(Graphics2D g, float x, float y, float w, Color color) {
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, w, 10, 10, 10));
    }

    private MakeIcon() {
    }
}
