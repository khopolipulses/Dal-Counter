import com.dalcounter.DalCounter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Sweep the tunables over all test photos. Usage: java Grid maxSide img1 img2 ... */
public class Grid {
    static int[] px(String f, int maxSide, int[] wh) throws Exception {
        BufferedImage src = ImageIO.read(new File(f));
        double sc = Math.min(1.0, (double) maxSide / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * sc), h = (int) Math.round(src.getHeight() * sc);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        wh[0] = w; wh[1] = h;
        return img.getRGB(0, 0, w, h, null, 0, w);
    }
    public static void main(String[] a) throws Exception {
        int maxSide = Integer.parseInt(a[0]);
        int k = a.length - 1;
        int[][] data = new int[k][]; int[][] dim = new int[k][2];
        for (int i = 0; i < k; i++) data[i] = px(a[i + 1], maxSide, dim[i]);
        float[] spacing = {0.08f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f};
        float[] minH = {0.2f, 0.3f, 0.4f};
        float[] sig = {0.8f, 1.0f, 1.5f};
        System.out.println("spacing minH sigma -> counts per image");
        for (float sp : spacing) for (float mh : minH) for (float sg : sig) {
            DalCounter.peakDynamics = sp; DalCounter.peakMinHeight = mh; DalCounter.blurSigma = sg;
            StringBuilder sb = new StringBuilder(String.format("%.2f  %.1f  %.1f  ->", sp, mh, sg));
            for (int i = 0; i < k; i++) sb.append(' ').append(DalCounter.count(data[i], dim[i][0], dim[i][1]).count());
            System.out.println(sb);
        }
    }
}
