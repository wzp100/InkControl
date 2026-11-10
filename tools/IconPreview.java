import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 锁屏设备状态图标的草稿板：用与 LockCanvas.drawIcon 完全相同的 16 空间坐标绘制，
 * 再按「面板 2 倍缩放 + 1-bit 阈值」二值化输出，用来在装机之前判断图标在墨水屏上是否可辨。
 *
 * <p>为什么需要它：图标在 1-bit 面板上的成败取决于量化后的形状，光看矢量代码判断不了。
 * 装机一轮要构建 + 整机重启 + 抓送屏位图，约 3 分钟；这里 3 秒就能看结果，形状调顺了再上机。
 *
 * <p><b>坐标必须与 {@code LockCanvas.drawIcon} 手工保持一致</b>（同一个 16×16 逻辑空间），
 * 改一边记得同步另一边。改完的坐标最终要写回 LockCanvas，这里只是草稿。
 *
 * <p>用法：
 * <pre>
 *   JAVA_HOME=C:/Paths/java_jdk/jdk17
 *   "$JAVA_HOME/bin/javac" -encoding UTF-8 -d /tmp/iconpreview tools/IconPreview.java
 *   "$JAVA_HOME/bin/java" -Djava.awt.headless=true -cp /tmp/iconpreview IconPreview out.png
 * </pre>
 *
 * <p>输出一张横向排开四个图标的 PNG：wifi / 蓝牙 / 喇叭 / 飞机，每个都按设备实际像素放大 10 倍。
 */
public final class IconPreview {
    /** 超采样倍数：先在 16×SS 的网格上抗锯齿绘制，再缩到设备像素 */
    private static final int SS = 16;
    /** 逻辑尺寸，与 LockCanvas 的 16×16 图标空间一致 */
    private static final int SPAN = 16;
    /** 面板缩放：海信 A5 Pro 是 2 倍密度的 720×1440 面板，1 逻辑像素 = 2 设备像素 */
    private static final int DEV = 2;
    /** 二值化阈值（0-255）：墨水屏只有黑白，128 是中间值 */
    private static final int THRESHOLD = 128;

    private IconPreview() { }

    /** 圆弧采样：与 Skia 的 Path.addArc 同一角度约定——0° 在 3 点钟方向，y 轴向下，from→to 顺时针 */
    private static Path2D.Double arc(double cx, double cy, double r, double from, double to) {
        Path2D.Double p = new Path2D.Double();
        int n = 48;
        for (int i = 0; i <= n; i++) {
            double d = Math.toRadians(from + (to - from) * i / n);
            double x = cx + r * Math.cos(d), y = cy + r * Math.sin(d);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        return p;
    }

    private static Path2D.Double poly(float[] pts) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.closePath();
        return p;
    }

    private static BufferedImage canvas() {
        BufferedImage b = new BufferedImage(SPAN * SS, SPAN * SS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, b.getWidth(), b.getHeight());
        g.dispose();
        return b;
    }

    private static Graphics2D pen(BufferedImage b) {
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(SS, SS);
        g.setColor(Color.BLACK);
        return g;
    }

    private static void stroke(BufferedImage b, Path2D path, double width) {
        Graphics2D g = pen(b);
        // LockCanvas 用的是 1.5f * k，k = 图标缩放；这里的坐标已经是 16 空间，换算在 device() 里统一做
        g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);
        g.dispose();
    }

    private static void fill(BufferedImage b, Shape shape) {
        Graphics2D g = pen(b);
        g.fill(shape);
        g.dispose();
    }

    /** 三段弧 + 圆点。弧心在 (8,12.6)，225°→315° 是正上方那 90° */
    private static BufferedImage wifi() {
        BufferedImage b = canvas();
        Path2D.Double p = new Path2D.Double();
        p.append(arc(8, 12.6, 7.9, 225, 315), false);
        p.append(arc(8, 12.6, 5.4, 225, 315), false);
        p.append(arc(8, 12.6, 2.9, 225, 315), false);
        stroke(b, p, 1.5);
        fill(b, new Ellipse2D.Double(8 - 1.15, 13.4 - 1.15, 2.3, 2.3));
        return b;
    }

    /** 卢恩字母 Bjarkan：一条竖线 + 两组交叉斜线 */
    private static BufferedImage bt() {
        BufferedImage b = canvas();
        Path2D.Double p = new Path2D.Double();
        p.moveTo(8, 2.6);
        p.lineTo(8, 13.4);
        p.moveTo(8, 2.6);
        p.lineTo(11.8, 5.7);
        p.lineTo(4.2, 10.3);
        p.moveTo(8, 13.4);
        p.lineTo(11.8, 10.3);
        p.lineTo(4.2, 5.7);
        stroke(b, p, 1.5);
        return b;
    }

    /** 实心喇叭 + 右侧两道声波。声波从 -45° 扫 90°（正右半圆），画成正上方就变成头顶的角 */
    private static BufferedImage audio() {
        BufferedImage b = canvas();
        fill(b, poly(new float[]{1.2f, 5.2f, 4.2f, 5.2f, 8.6f, 1.6f, 8.6f, 14.4f, 4.2f, 10.8f, 1.2f, 10.8f}));
        Path2D.Double p = new Path2D.Double();
        p.append(arc(9.0, 8.0, 3.2, -45, 45), false);
        p.append(arc(9.0, 8.0, 5.6, -45, 45), false);
        stroke(b, p, 1.5);
        return b;
    }

    /** 俯视飞机：翼展 15 必须大于机身长 12.2，否则接近正方形，量化后只剩「十字」 */
    private static BufferedImage plane() {
        BufferedImage b = canvas();
        fill(b, poly(new float[]{8.0f, 2.0f, 9.0f, 5.4f, 15.5f, 7.0f, 15.5f, 9.2f, 9.0f, 8.0f,
                9.0f, 10.8f, 11.3f, 12.2f, 11.3f, 13.4f, 9.0f, 12.8f, 9.0f, 14.2f,
                7.0f, 14.2f, 7.0f, 12.8f, 4.7f, 13.4f, 4.7f, 12.2f, 7.0f, 10.8f,
                7.0f, 8.0f, 0.5f, 9.2f, 0.5f, 7.0f, 7.0f, 5.4f}));
        return b;
    }

    /** 缩到设备像素（SPAN × DEV），阈值二值化，再放大 mag 倍输出 */
    private static BufferedImage device(BufferedImage src, int mag) {
        int dev = SPAN * DEV;
        BufferedImage small = new BufferedImage(dev, dev, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, dev, dev, null);
        g.dispose();
        BufferedImage out = new BufferedImage(dev * mag, dev * mag, BufferedImage.TYPE_INT_RGB);
        Graphics2D o = out.createGraphics();
        o.setColor(Color.WHITE);
        o.fillRect(0, 0, out.getWidth(), out.getHeight());
        o.setColor(Color.BLACK);
        for (int y = 0; y < dev; y++) {
            for (int x = 0; x < dev; x++) {
                if ((small.getRGB(x, y) & 0xFF) < THRESHOLD) o.fillRect(x * mag, y * mag, mag, mag);
            }
        }
        o.dispose();
        return out;
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "icons_device.png";
        BufferedImage[] all = {wifi(), bt(), audio(), plane()};
        int mag = 10, gap = 20;
        int one = SPAN * DEV * mag;
        BufferedImage sheet = new BufferedImage(all.length * (one + gap) + gap, one + 2 * gap, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(230, 230, 230));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int i = 0; i < all.length; i++) g.drawImage(device(all[i], mag), gap + i * (one + gap), gap, null);
        g.dispose();
        ImageIO.write(sheet, "png", new File(out));
        System.out.println("已写入 " + out);
    }
}
