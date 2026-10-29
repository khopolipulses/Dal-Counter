import com.dalcounter.DalCounter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Desktop harness: java DalCounterTest <image> [out.png]  (downscales to 1600 px like the app). */
public class DalCounterTest {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int maxSide = Integer.getInteger("maxSide", 1600);
        double sc = Math.min(1.0, (double) maxSide / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * sc), h = (int) Math.round(src.getHeight() * sc);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        DalCounter.peakDynamics = Float.parseFloat(System.getProperty("dy", String.valueOf(DalCounter.peakDynamics)));
        DalCounter.peakMinHeight = Float.parseFloat(System.getProperty("mh", String.valueOf(DalCounter.peakMinHeight)));
        DalCounter.blurSigma = Float.parseFloat(System.getProperty("sg", String.valueOf(DalCounter.blurSigma)));
        long t0 = System.nanoTime();
        DalCounter.Result r = DalCounter.count(px, w, h);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println(args[0].replaceAll(".*/", "") + "  " + w + "x" + h + "  count=" + r.count()
                + "  radius=" + String.format("%.1f", r.radius) + "px  time=" + ms + "ms");

        if (System.getProperty("dump") != null) {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(System.getProperty("dump"))) { // dumpPts
                for (int i = 0; i < r.count(); i++) pw.println(r.xs[i] + " " + r.ys[i]);
            }
        }
        if (args.length > 1) {
            Graphics2D d = img.createGraphics();
            d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float k = Math.max(w, h) / 1600f;
            d.setFont(new Font("SansSerif", Font.BOLD, Math.round(15 * k)));
            float rr = r.radius * 1.15f;
            for (int i = 0; i < r.count(); i++) {
                d.setColor(new Color(0, 90, 255));
                d.setStroke(new BasicStroke(2.2f * k));
                d.drawOval(Math.round(r.xs[i] - rr), Math.round(r.ys[i] - rr), Math.round(2 * rr), Math.round(2 * rr));
                d.setColor(new Color(200, 0, 0));
                d.drawString(String.valueOf(i + 1), r.xs[i] + rr, r.ys[i] - rr + 6 * k);
            }
            d.setColor(Color.WHITE);
            d.fillRect(10, 10, Math.round(430 * k), Math.round(56 * k));
            d.setColor(Color.BLACK);
            d.setFont(new Font("SansSerif", Font.BOLD, Math.round(40 * k)));
            d.drawString("Total count: " + r.count(), 20, Math.round(52 * k));
            d.dispose();
            ImageIO.write(img, "png", new File(args[1]));
        }
    }
}
