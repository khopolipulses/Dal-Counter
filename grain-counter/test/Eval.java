import com.dalcounter.DalCounter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Scores the counter against ground truth.  Usage: java Eval <benchDir> [maxSide] [nameFilter] */
public class Eval {
    public static void main(String[] a) throws Exception {
        DalCounter.peakDynamics = Float.parseFloat(System.getProperty("dy", String.valueOf(DalCounter.peakDynamics)));
        DalCounter.peakMinHeight = Float.parseFloat(System.getProperty("mh", String.valueOf(DalCounter.peakMinHeight)));
        DalCounter.blurSigma = Float.parseFloat(System.getProperty("sg", String.valueOf(DalCounter.blurSigma)));
        DalCounter.CLUSTER_ROUNDING = Float.parseFloat(System.getProperty("cr", String.valueOf(DalCounter.CLUSTER_ROUNDING)));
        File dir = new File(a[0]);
        int maxSide = a.length > 1 ? Integer.parseInt(a[1]) : 1600;
        String filter = a.length > 2 ? a[2] : "";
        File[] jpgs = dir.listFiles((d, n) -> n.endsWith(".jpg") && n.matches(".*(" + filter + ").*"));
        Arrays.sort(jpgs);
        System.out.printf("%-22s %5s %5s %5s | %4s %4s %4s | %6s%n", "case", "truth", "found", "diff", "TP", "FP", "FN", "ms");
        int exact = 0, within2 = 0, total = 0;
        double sumAbsPct = 0;
        for (File f : jpgs) {
            String name = f.getName().replace(".jpg", "");
            List<String> tl = Files.readAllLines(new File(dir, name + ".truth").toPath());
            double rad = Double.parseDouble(tl.get(0).split(" ")[2]);
            List<double[]> truth = new ArrayList<>();
            for (int i = 1; i < tl.size(); i++) {
                String[] p = tl.get(i).split(" ");
                if (Double.parseDouble(p[2]) >= 0.5) truth.add(new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            }
            BufferedImage src = ImageIO.read(f);
            double sc = Math.min(1.0, (double) maxSide / Math.max(src.getWidth(), src.getHeight()));
            int w = (int) Math.round(src.getWidth() * sc), h = (int) Math.round(src.getHeight() * sc);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.nanoTime();
            DalCounter.Result r = DalCounter.count(px, w, h);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            // greedy one-to-one matching within 0.9 * radius
            double tol = 0.9 * rad * sc;
            boolean[] used = new boolean[r.count()];
            int tp = 0;
            for (double[] t : truth) {
                int best = -1;
                double bd = tol * tol;
                for (int i = 0; i < r.count(); i++) {
                    if (used[i]) continue;
                    double dx = r.xs[i] - t[0] * sc, dy = r.ys[i] - t[1] * sc;
                    double d2 = dx * dx + dy * dy;
                    if (d2 < bd) { bd = d2; best = i; }
                }
                if (best >= 0) { used[best] = true; tp++; }
            }
            int fn = truth.size() - tp, fp = r.count() - tp;
            int diff = r.count() - truth.size();
            total++;
            if (diff == 0) exact++;
            if (Math.abs(diff) <= 2) within2++;
            sumAbsPct += truth.size() == 0 ? Math.abs(diff) : 100.0 * Math.abs(diff) / truth.size();
            String flag = diff == 0 ? "" : (Math.abs(diff) <= 2 ? "~" : " <== CHECK");
            if (System.getProperty("diag") != null) flag += String.format("  [bg=%b contrast=%.2f noise=%.3f bright=%.0f unc=%d]", r.backgroundFound, r.contrast, r.noise, r.brightness, r.uncertainCount);
            System.out.printf("%-22s %5d %5d %+5d | %4d %4d %4d | %6d %s%n", name, truth.size(), r.count(), diff, tp, fp, fn, ms, flag);
        }
        System.out.printf("%nexact: %d/%d   within +-2: %d/%d   mean abs error: %.2f%%%n", exact, total, within2, total, sumAbsPct / total);
    }
}
