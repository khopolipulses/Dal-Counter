import com.dalcounter.DalCounter;
import java.util.Random;

/** Degenerate-input stress test: nothing may throw, hang or return nonsense. */
public class Robust {
    static int fails = 0;
    static void run(String name, int w, int h, java.util.function.IntBinaryOperator px, int maxCount) {
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) a[y * w + x] = 0xFF000000 | px.applyAsInt(x, y);
        long t0 = System.nanoTime();
        try {
            DalCounter.Result r = DalCounter.count(a, w, h);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = r.count() <= maxCount && r.xs.length == r.ys.length && r.xs.length == r.uncertain.length;
            for (int i = 0; i < r.count(); i++) ok &= r.xs[i] >= 0 && r.xs[i] < w && r.ys[i] >= 0 && r.ys[i] < h;
            System.out.printf("%-34s %5dx%-5d count=%-4d hint=%s %s (%d ms)%n", name, w, h, r.count(),
                    r.hint() == null ? "-" : "yes", ok ? "OK" : "FAIL", ms);
            if (!ok) fails++;
        } catch (Throwable t) {
            System.out.printf("%-34s %5dx%-5d EXCEPTION %s%n", name, w, h, t);
            fails++;
        }
    }
    static int rgb(int r, int g, int b) { return (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b)); }

    public static void main(String[] args) {
        Random rnd = new Random(1);
        run("1x1 white", 1, 1, (x, y) -> rgb(255, 255, 255), 0);
        run("2x2 mixed", 2, 2, (x, y) -> rgb(x * 200, y * 200, 50), 4);
        run("7x5 gray", 7, 5, (x, y) -> rgb(120, 120, 120), 0);
        run("all white 1600x900", 1600, 900, (x, y) -> rgb(255, 255, 255), 0);
        run("all black 1600x900", 1600, 900, (x, y) -> 0, 0);
        run("flat orange 1600x900", 1600, 900, (x, y) -> rgb(240, 140, 50), 5);
        run("white noise 1600x900", 1600, 900, (x, y) -> rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)), 5000);
        run("gradient 1600x900", 1600, 900, (x, y) -> rgb(x * 255 / 1600, y * 255 / 900, 128), 200);
        run("half white half dark 1600x900", 1600, 900, (x, y) -> x < 800 ? rgb(230, 230, 230) : rgb(20, 20, 20), 200);
        run("one big orange disc", 1600, 900, (x, y) -> (x - 800) * (x - 800) + (y - 450) * (y - 450) < 90000 ? rgb(240, 140, 50) : rgb(225, 225, 228), 3);
        run("thin line on white", 1600, 900, (x, y) -> Math.abs(y - 450) < 2 ? rgb(0, 0, 0) : rgb(225, 225, 228), 3);
        run("chequerboard 1600x900", 1600, 900, (x, y) -> ((x / 40 + y / 40) & 1) == 0 ? rgb(230, 230, 230) : rgb(200, 90, 40), 700);
        run("portrait 900x1600 white", 900, 1600, (x, y) -> rgb(235, 235, 235), 0);
        run("huge 4000x3000 white", 4000, 3000, (x, y) -> rgb(235, 235, 235), 0);
        run("panorama 3200x400", 3200, 400, (x, y) -> rgb(235, 235, 235), 0);
        System.out.println(fails == 0 ? "ALL ROBUSTNESS CHECKS PASSED" : fails + " ROBUSTNESS FAILURES");
        if (fails > 0) System.exit(1);
    }
}
