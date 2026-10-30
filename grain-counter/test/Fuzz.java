import com.dalcounter.DalCounter;
import com.dalcounter.Markers;

/**
 * Fuzz and property tests. Usage: java Fuzz [cases] [digest-file]
 *
 * Images come from a seeded SplitMix64 generator with integer-only drawing, so the Swift engine (FuzzTests.swift)
 * builds the very same pixels; both print one digest line per case, and CI checks that the files are identical.
 * Every case must finish without an exception, with markers inside the image and consistent arrays.
 * The marker list is driven through random edits; undo must always restore the previous state exactly.
 */
public class Fuzz {
    static long state;

    static long next() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform integer in [0, n). */
    static int below(int n) {
        return (int) Long.remainderUnsigned(next(), n);
    }

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    static final int[][] SIZES = {{1, 1}, {1, 1280}, {1280, 1}, {2, 2}, {3, 700}, {700, 3}, {5, 5}, {64, 48}, {1280, 960}, {960, 1280}};

    static int[] image(int kase, int[] wh) {
        int w, h;
        if (kase < SIZES.length) {
            w = SIZES[kase][0];
            h = SIZES[kase][1];
        } else {
            w = 1 + below(420);
            h = 1 + below(420);
        }
        wh[0] = w;
        wh[1] = h;
        int[] px = new int[w * h];
        int bg = rgb(150 + below(106), 150 + below(106), 150 + below(106));
        int kind = below(8);
        for (int i = 0; i < px.length; i++) px[i] = bg;
        switch (kind) {
            case 0: // flat colour, maybe black
                int c = below(2) == 0 ? rgb(0, 0, 0) : rgb(below(256), below(256), below(256));
                for (int i = 0; i < px.length; i++) px[i] = c;
                break;
            case 1: // white noise
                for (int i = 0; i < px.length; i++) px[i] = rgb(below(256), below(256), below(256));
                break;
            case 2: // checkerboard of random cell size
                int cell = 1 + below(40), dark = rgb(below(120), below(120), below(120));
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (((x / cell) + (y / cell)) % 2 == 0) px[y * w + x] = dark;
                break;
            default: { // discs (grains), some touching, plus thin strokes (print) and specks
                int n = below(60), col = rgb(below(200), below(160), below(120));
                for (int k = 0; k < n; k++) {
                    int cx = below(w), cy = below(h), r = 1 + below(Math.max(1, Math.min(w, h) / 6 + 1));
                    for (int y = Math.max(0, cy - r); y <= Math.min(h - 1, cy + r); y++) {
                        for (int x = Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) {
                            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = col;
                        }
                    }
                }
                int strokes = below(10);
                for (int k = 0; k < strokes; k++) {
                    int x0 = below(w), y0 = below(h), len = below(200), dir = below(4);
                    for (int t = 0; t < len; t++) {
                        int x = x0 + (dir == 0 || dir == 2 ? t : 0), y = y0 + (dir >= 1 ? t : 0);
                        if (x < w && y < h) px[y * w + x] = rgb(20, 20, 25);
                    }
                }
                int glare = below(3);
                for (int k = 0; k < glare; k++) { // bright soft patch
                    int cx = below(w), cy = below(h), r = 5 + below(80);
                    for (int y = Math.max(0, cy - r); y <= Math.min(h - 1, cy + r); y++) {
                        for (int x = Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) {
                            int d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                            if (d2 <= r * r) {
                                int p = px[y * w + x], lift = 60 * (r * r - d2) / (r * r);
                                px[y * w + x] = rgb(Math.min(255, ((p >> 16) & 255) + lift), Math.min(255, ((p >> 8) & 255) + lift),
                                        Math.min(255, (p & 255) + lift));
                            }
                        }
                    }
                }
            }
        }
        return px;
    }

    static long fnv(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (8 * i)) & 255;
            h *= 0x100000001B3L;
        }
        return h;
    }

    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] a) throws Exception {
        int cases = a.length > 0 ? Integer.parseInt(a[0]) : 300;
        java.io.PrintWriter out = a.length > 1 ? new java.io.PrintWriter(a[1]) : null;
        state = 12345;
        for (int k = 0; k < cases; k++) {
            int[] wh = new int[2];
            int[] px = image(k, wh);
            int w = wh[0], h = wh[1];
            DalCounter.Result r;
            try {
                r = DalCounter.count(px, w, h);
            } catch (Throwable t) {
                check(false, "case " + k + " (" + w + "x" + h + ") threw " + t);
                continue;
            }
            int n = r.count();
            check(r.xs.length == n && r.ys.length == n && r.uncertain.length == n, "case " + k + " array sizes");
            check(n <= w * h, "case " + k + " more markers than pixels");
            int unc = 0;
            for (int i = 0; i < n; i++) {
                boolean in = r.xs[i] >= 0 && r.xs[i] < w && r.ys[i] >= 0 && r.ys[i] < h && !Float.isNaN(r.xs[i]) && !Float.isNaN(r.ys[i]);
                check(in, "case " + k + " marker " + i + " outside image: " + r.xs[i] + "," + r.ys[i]);
                if (r.uncertain[i]) unc++;
            }
            check(unc == r.uncertainCount, "case " + k + " uncertainCount");
            check(r.radius > 0 && !Float.isNaN(r.radius) && !Float.isInfinite(r.radius), "case " + k + " radius " + r.radius);
            r.hint();
            r.photoTip();
            long d = 0xcbf29ce484222325L;
            d = fnv(d, n);
            d = fnv(d, r.uncertainCount);
            d = fnv(d, r.ignoredCount);
            d = fnv(d, Float.floatToIntBits(r.radius));
            for (int i = 0; i < n; i++) {
                d = fnv(d, Float.floatToIntBits(r.xs[i]));
                d = fnv(d, Float.floatToIntBits(r.ys[i]));
                d = fnv(d, r.uncertain[i] ? 1 : 0);
            }
            if (out != null) out.println(k + " " + w + "x" + h + " " + n + " " + Long.toHexString(d));
        }
        if (out != null) out.close();
        markers();
        System.out.println(fails == 0 ? "FUZZ: ALL PASSED (" + cases + " images)" : "FUZZ: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    /** Random add / remove / confirm / undo; undo must restore the exported state exactly. */
    static void markers() {
        state = 777;
        for (int round = 0; round < 200; round++) {
            Markers m = new Markers();
            int n0 = below(30);
            float[] xs = new float[n0], ys = new float[n0];
            boolean[] u = new boolean[n0];
            for (int i = 0; i < n0; i++) {
                xs[i] = below(1000);
                ys[i] = below(1000);
                u[i] = below(3) == 0;
            }
            m.set(xs, ys, u, 1 + below(80));
            java.util.ArrayList<float[]> history = new java.util.ArrayList<float[]>();
            for (int step = 0; step < 120; step++) {
                float[] before = m.export();
                int op = below(5);
                boolean changed = false;
                if (op == 0) {
                    m.add(below(1000), below(1000));
                    changed = true;
                } else if (op == 1 && m.size() > 0) {
                    m.remove(below(m.size()));
                    changed = true;
                } else if (op == 2 && m.size() > 0) {
                    int i = below(m.size());
                    changed = m.get(i).kind != Markers.CONFIRMED;
                    m.confirm(i);
                } else if (op == 3) {
                    boolean could = m.canUndo();
                    boolean did = m.undo();
                    check(could == did, "undo result matches canUndo");
                    if (did) {
                        float[] expect = history.remove(history.size() - 1);
                        check(java.util.Arrays.equals(expect, m.export()), "undo restores the previous state (round " + round + ")");
                    }
                    continue;
                } else if (op == 4 && m.size() > 0) {
                    int kind = below(4), from = below(m.size() + 3) - 1, i = m.next(kind, from);
                    check(i == -1 || m.get(i).kind == kind, "next() returns a marker of that kind");
                    if (i == -1) for (int j = 0; j < m.size(); j++) check(m.get(j).kind != kind, "next() == -1 only when none left");
                    continue;
                }
                if (changed) history.add(before);
            }
        }
    }
}
