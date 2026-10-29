package com.dalcounter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-Java dal / lentil counter. No Android dependencies, so the exact same code is unit-tested
 * on a desktop JVM and shipped inside the app.
 *
 * Pipeline: paper region -> orange mask -> clean-up -> connected components -> distance transform
 * -> smoothed peaks (one peak per lentil, splits touching pieces) -> small broken fragments added.
 */
public final class DalCounter {
    private DalCounter() {}

    public static final class Result {
        public int width, height;
        /** Marker centres in image pixels, already in reading order (top-to-bottom, left-to-right). */
        public float[] xs = new float[0];
        public float[] ys = new float[0];
        /** Typical lentil radius in pixels. */
        public float radius = 12f;
        /** True for markers that sit in a touching/overlapping group and deserve a second look. */
        public boolean[] uncertain = new boolean[0];
        /** Background found (plain sheet / tray). False = results are a best guess. */
        public boolean backgroundFound;
        /** How strongly grains stand out from the background (>=0.6 good, <0.3 poor). */
        public float contrast;
        /** Background noise (higher = grainier / darker photo). */
        public float noise;
        /** Mean background brightness 0..255. */
        public float brightness;
        /** Number of markers flagged uncertain. */
        public int uncertainCount;
        /** Pieces of printed text, lines and symbols that were recognised and not counted. */
        public int ignoredCount;

        public int count() {
            return xs.length;
        }

        /** Height of one row of markers for reading-order numbering, in image pixels. */
        public float band() {
            return Math.max(1f, 50f * Math.max(width, height) / REF_LONG_SIDE);
        }

        /** True when the photo is good enough that the count can be trusted. */
        public boolean reliable() {
            return count() > 0 && backgroundFound && contrast >= 0.35f && noise <= 0.08f;
        }

        /** Tells the user that printed text / lines / symbols were skipped, or null when there were none. */
        public String ignoredNote() {
            if (ignoredCount == 0) return null;
            return "Printed text, lines and symbols were ignored (" + ignoredCount + (ignoredCount == 1 ? " piece)." : " pieces).");
        }

        /** A problem with the photo itself (background, contrast, light), or null when the photo is fine. */
        public String photoTip() {
            String h = hint();
            return h == null || (uncertainCount > 0 && h.startsWith(uncertainCount + " grains touch")) ? null : h;
        }

        /** The single most useful tip for the user, or null when everything looks fine. */
        public String hint() {
            if (count() == 0) return "No grains found. Use one plain background and good light.";
            if (!backgroundFound) return "No plain background found. Put the grains on one plain sheet or tray.";
            if (contrast < 0.35f) {
                return "Grains look too similar to the background, so this count may be wrong. "
                        + "Use a plainer, contrasting background (dark for light grains, white for dark grains).";
            }
            if (noise > 0.08f) return "The photo is dark or grainy. Use more light for a safer count.";
            if (uncertainCount > 0) {
                return uncertainCount + " grains touch or overlap (shown in orange). Check them, "
                        + "or spread the grains out for an exact count.";
            }
            return null;
        }
    }

    /** Thresholds were tuned on 2576 px photos; everything is scaled relative to image size. */
    private static final float REF_LONG_SIDE = 2576f;

    /** Tunables (fractions of the typical lentil radius). Exposed so the test harness can sweep them. */
    /** A peak is its own grain only if the neck to a higher neighbour is at least this fraction lower. */
    public static float peakDynamics = 0.15f;
    public static float peakMinHeight = 0.3f;
    /** Blob area / typical grain area is rounded with this offset to get the expected grain count. */
    public static float CLUSTER_ROUNDING = 0.25f;
    /** Blobs that would need more grains than this are left alone (not a sensible pile). */
    private static final int MAX_CLUSTER_GRAINS = 150;
    public static float blurSigma = 1.0f; // in pixels at REF_LONG_SIDE

    public static Result count(int[] argb, int w, int h) {
        final float s = Math.max(w, h) / REF_LONG_SIDE;
        final int n = w * h;

        Seg seg = segment(argb, w, h, s);
        boolean[] open = open(seg.fg, w, h, 1);
        boolean[] mask = open.clone();
        fillHoles(mask, w, h);

        int[] lab = new int[n];
        int nLab = label(mask, w, h, lab);
        Result res = new Result();
        res.width = w;
        res.height = h;
        res.backgroundFound = seg.paperFound;
        res.contrast = seg.contrast;
        res.noise = seg.noise;
        res.brightness = seg.paperLum;
        if (nLab == 0) return res;

        double[] area = new double[nLab + 1];
        double[] sx = new double[nLab + 1];
        double[] sy = new double[nLab + 1];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = lab[y * w + x];
                if (l > 0) {
                    area[l]++;
                    sx[l] += x;
                    sy[l] += y;
                }
            }
        }

        // Printed text, lines and symbols on the sheet are not grains: drop them before measuring grains.
        res.ignoredCount = removeMarks(argb, seg.score, open, mask, lab, nLab, area, w, h, s);

        // Typical single-lentil area = median of the components that are not tiny crumbs.
        double[] all = filterAbove(Arrays.copyOfRange(area, 1, nLab + 1), 0);
        if (all.length == 0) return res;
        double med0 = median(all);
        double med = median(filterAbove(all, 0.5 * med0));
        float rad = (float) Math.sqrt(med / Math.PI);
        res.radius = rad;
        float thr = rad * peakMinHeight;

        // Smoothed distance transform: each lentil gets exactly one bright peak, even when touching.
        double[] d = edt(mask, w, h);
        float[] dist = new float[n];
        for (int i = 0; i < n; i++) dist[i] = (float) d[i];
        final float[] sm = gauss(dist, w, h, Math.max(0.7, blurSigma * s));

        // One peak per grain by "dynamics": a bump of the distance map is a separate grain only if the neck
        // between it and a higher bump is clearly narrower. Long single grains have no neck, so stay whole.
        List<float[]> pts = new ArrayList<float[]>();
        boolean[] hasPeak = new boolean[nLab + 1];
        int maskCount = 0;
        float maxV = 0;
        for (int i = 0; i < n; i++) {
            if (lab[i] > 0) {
                maskCount++;
                if (sm[i] > maxV) maxV = sm[i];
            }
        }
        final int Q = 16;
        int levels = (int) (maxV * Q) + 2;
        int[] start = new int[levels + 1];
        for (int i = 0; i < n; i++) if (lab[i] > 0) start[(int) (sm[i] * Q)]++;
        int acc0 = 0;
        for (int L = levels - 1; L >= 0; L--) {
            int c0 = start[L];
            start[L] = acc0;
            acc0 += c0;
        }
        int[] order = new int[maskCount];
        for (int i = 0; i < n; i++) if (lab[i] > 0) order[start[(int) (sm[i] * Q)]++] = i;

        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        float[] pv = new float[n];
        int[] pk = new int[n];
        int[] kept = new int[64];
        int nk = 0;
        final int[] dx4 = {-1, 1, 0, 0};
        final int[] dy4 = {0, 0, -1, 1};
        for (int t = 0; t < maskCount; t++) {
            int i = order[t];
            float v = sm[i];
            int x = i % w, y = i / w;
            int root = -1;
            for (int dir = 0; dir < 4; dir++) {
                int xx = x + dx4[dir], yy = y + dy4[dir];
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int j = yy * w + xx;
                if (parent[j] < 0) continue;
                int rj = j;
                while (parent[rj] != rj) {
                    parent[rj] = parent[parent[rj]];
                    rj = parent[rj];
                }
                if (root < 0) {
                    root = rj;
                } else if (rj != root) {
                    int hi = pv[root] >= pv[rj] ? root : rj;
                    int lo = hi == root ? rj : root;
                    if (pv[lo] - v >= Math.max(0.5f, peakDynamics * pv[lo])) {
                        if (nk == kept.length) kept = Arrays.copyOf(kept, nk * 2);
                        kept[nk++] = pk[lo];
                    }
                    parent[lo] = hi;
                    root = hi;
                }
            }
            if (root < 0) {
                parent[i] = i;
                pv[i] = v;
                pk[i] = i;
            } else {
                parent[i] = root;
            }
        }
        for (int t = 0; t < maskCount; t++) {
            int i = order[t];
            if (parent[i] == i) {
                if (nk == kept.length) kept = Arrays.copyOf(kept, nk * 2);
                kept[nk++] = pk[i];
            }
        }
        for (int q = 0; q < nk; q++) {
            int i = kept[q];
            if (sm[i] > thr) {
                hasPeak[lab[i]] = true;
                pts.add(new float[] {i % w, i / w, lab[i], 0});
            }
        }

        // Cluster correction: a blob clearly bigger than one grain hides more grains than the neck test saw.
        // Not when its outline is smooth and convex: that is one big grain (wheat among small seeds, a bean among
        // mustard), since touching grains always leave notches in the outline.
        int[] nPk = new int[nLab + 1];
        for (float[] q : pts) nPk[(int) q[2]]++;
        boolean[] big = new boolean[nLab + 1];
        for (int l = 1; l <= nLab; l++) {
            big[l] = nPk[l] > 0 && (Math.floor(area[l] / med + CLUSTER_ROUNDING) > nPk[l] || (nPk[l] == 1 && area[l] / med > 1.45));
        }
        boolean[] single = convexBlobs(lab, w, h, nLab, area, big);
        int[] need = new int[nLab + 1];
        int needTotal = 0;
        for (int l = 1; l <= nLab; l++) {
            if (nPk[l] == 0 || single[l]) continue;
            int expect = (int) Math.floor(area[l] / med + CLUSTER_ROUNDING);
            if (expect > nPk[l]) {
                need[l] = expect;
                needTotal += (int) area[l];
            }
        }
        if (needTotal > 0) {
            int[] cs = new int[nLab + 2];
            for (int l = 1; l <= nLab; l++) cs[l + 1] = cs[l] + (need[l] > 0 ? (int) area[l] : 0);
            int[] pix = new int[needTotal];
            int[] fp = Arrays.copyOf(cs, cs.length);
            for (int i = 0; i < n; i++) {
                int l = lab[i];
                if (l > 0 && need[l] > 0) pix[fp[l]++] = i;
            }
            for (int l = 1; l <= nLab; l++) {
                if (need[l] == 0) continue;
                int K = need[l];
                int from = cs[l], to = cs[l + 1];
                if (K > MAX_CLUSTER_GRAINS) continue;                 // a giant blob is not a countable pile
                final int stride = Math.max(1, (to - from) / 3000);   // keeps the work bounded
                double[] ccx = new double[K], ccy = new double[K];
                int k0 = 0;
                for (int q = pts.size() - 1; q >= 0; q--) {
                    float[] pt = pts.get(q);
                    if ((int) pt[2] == l) {
                        if (k0 < K) { ccx[k0] = pt[0]; ccy[k0] = pt[1]; k0++; }
                        pts.remove(q);
                    }
                }
                for (int k = k0; k < K; k++) {          // farthest-point seeding for the hidden grains
                    double bestD = -1;
                    int bi = from;
                    for (int t = from; t < to; t += stride) {
                        int i = pix[t];
                        double dmin = Double.MAX_VALUE;
                        for (int c2 = 0; c2 < k; c2++) {
                            double ddx = i % w - ccx[c2], ddy = i / w - ccy[c2];
                            dmin = Math.min(dmin, ddx * ddx + ddy * ddy);
                        }
                        if (dmin > bestD) { bestD = dmin; bi = i; }
                    }
                    ccx[k] = bi % w;
                    ccy[k] = bi / w;
                }
                for (int it = 0; it < 8; it++) {         // Lloyd iterations
                    double[] sxk = new double[K], syk = new double[K];
                    int[] nk2 = new int[K];
                    for (int t = from; t < to; t += stride) {
                        int i = pix[t];
                        double px = i % w, py = i / w, bd = Double.MAX_VALUE;
                        int bk = 0;
                        for (int k = 0; k < K; k++) {
                            double ddx = px - ccx[k], ddy = py - ccy[k], dd = ddx * ddx + ddy * ddy;
                            if (dd < bd) { bd = dd; bk = k; }
                        }
                        sxk[bk] += px; syk[bk] += py; nk2[bk]++;
                    }
                    for (int k = 0; k < K; k++) {
                        if (nk2[k] > 0) { ccx[k] = sxk[k] / nk2[k]; ccy[k] = syk[k] / nk2[k]; }
                    }
                }
                for (int k = 0; k < K; k++) pts.add(new float[] {(float) ccx[k], (float) ccy[k], l, 1});
                hasPeak[l] = true;
            }
        }

        // Small broken pieces that are too small to form a distance-transform peak still count.
        double minArea = Math.max(6, Math.round(25 * s * s));
        for (int l = 1; l <= nLab; l++) {
            if (!hasPeak[l] && area[l] >= minArea) {
                pts.add(new float[] {(float) (sx[l] / area[l]), (float) (sy[l] / area[l]), l, 0});
            }
        }

        // Uncertain = member of a touching/overlapping group whose size does not add up cleanly.
        int[] finalPk = new int[nLab + 1];
        for (float[] q : pts) finalPk[(int) q[2]]++;
        for (float[] q : pts) {
            int l = (int) q[2];
            double ratio = area[l] / med;
            int k = finalPk[l];
            boolean unc = q[3] > 0 || (k >= 2 && ratio < 0.85 * k) || (k == 1 && ratio > 1.45 && !single[l]);
            q[3] = unc ? 1 : 0;
        }
        // Odd ones out: a lone grain that looks like no other grain in the photo (size, thickness, colour) is
        // worth a look; real grains, even in a mix, come in groups that look alike.
        boolean[] odd = oddOnesOut(argb, lab, d, nLab, area, finalPk, w, h);
        for (float[] q : pts) if (odd[(int) q[2]]) q[3] = 1;
        sortReadingOrder(pts, 50f * s);
        res.xs = new float[pts.size()];
        res.ys = new float[pts.size()];
        res.uncertain = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            res.xs[i] = pts.get(i)[0];
            res.ys[i] = pts.get(i)[1];
            res.uncertain[i] = pts.get(i)[3] > 0;
            if (res.uncertain[i]) res.uncertainCount++;
        }
        res.backgroundFound = seg.paperFound;
        res.contrast = seg.contrast;
        res.noise = seg.noise;
        res.brightness = seg.paperLum;
        return res;
    }

    /**
     * For every blob holding exactly one marker: true when fewer than a few other such blobs look like it
     * (area within 1.6x, thickness within 1.4x, mean colour within 40). Skipped on very busy photos.
     */
    private static boolean[] oddOnesOut(int[] argb, int[] lab, double[] dist, int nLab, double[] area, int[] markers,
                                        int w, int h) {
        boolean[] odd = new boolean[nLab + 1];
        int[] ids = new int[nLab];
        int m = 0;
        for (int l = 1; l <= nLab; l++) if (markers[l] == 1 && area[l] > 0) ids[m++] = l;
        if (m < 8 || m > 4000) return odd;
        double[] thick = new double[nLab + 1], r = new double[nLab + 1], g = new double[nLab + 1], b = new double[nLab + 1];
        for (int i = 0; i < w * h; i++) {
            int l = lab[i];
            if (l == 0 || markers[l] != 1) continue;
            if (dist[i] > thick[l]) thick[l] = dist[i];
            int p = argb[i];
            r[l] += (p >> 16) & 255;
            g[l] += (p >> 8) & 255;
            b[l] += p & 255;
        }
        double[] la = new double[m], lt = new double[m], cr = new double[m], cg = new double[m], cb = new double[m];
        for (int k = 0; k < m; k++) {
            int l = ids[k];
            la[k] = Math.log(area[l]);
            lt[k] = Math.log(Math.max(0.5, thick[l]));
            cr[k] = r[l] / area[l];
            cg[k] = g[l] / area[l];
            cb[k] = b[l] / area[l];
        }
        final double sa = Math.log(1.6), st = Math.log(1.4);
        int needed = Math.max(2, (int) (0.03 * m));
        for (int k = 0; k < m; k++) {
            int alike = 0;
            for (int j = 0; j < m && alike < needed; j++) {
                if (j == k || Math.abs(la[j] - la[k]) > sa || Math.abs(lt[j] - lt[k]) > st) continue;
                double dr = cr[j] - cr[k], dg = cg[j] - cg[k], db = cb[j] - cb[k];
                if (dr * dr + dg * dg + db * db <= 40 * 40) alike++;
            }
            if (alike < needed) odd[ids[k]] = true;
        }
        return odd;
    }

    /** Blobs at least this convex (area / convex hull area) are one grain, however big. */
    public static float SINGLE_SOLIDITY = 0.9f;

    /** For the blobs marked in `check`: true when the blob is convex enough to be a single grain. */
    private static boolean[] convexBlobs(int[] lab, int w, int h, int nLab, double[] area, boolean[] check) {
        boolean[] single = new boolean[nLab + 1];
        int[] minY = new int[nLab + 1], maxY = new int[nLab + 1];
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxY, -1);
        boolean any = false;
        for (int l = 1; l <= nLab; l++) any |= check[l];
        if (!any) return single;
        for (int i = 0; i < w * h; i++) {
            int l = lab[i];
            if (l > 0 && check[l]) {
                int y = i / w;
                if (y < minY[l]) minY[l] = y;
                if (y > maxY[l]) maxY[l] = y;
            }
        }
        // Row extents of every checked blob, then the convex hull of the pixel corners.
        int[][] rowMin = new int[nLab + 1][], rowMax = new int[nLab + 1][];
        for (int l = 1; l <= nLab; l++) {
            if (!check[l] || maxY[l] < 0) continue;
            rowMin[l] = new int[maxY[l] - minY[l] + 1];
            rowMax[l] = new int[maxY[l] - minY[l] + 1];
            Arrays.fill(rowMin[l], Integer.MAX_VALUE);
            Arrays.fill(rowMax[l], -1);
        }
        for (int i = 0; i < w * h; i++) {
            int l = lab[i];
            if (l == 0 || rowMin[l] == null) continue;
            int x = i % w, r = i / w - minY[l];
            if (x < rowMin[l][r]) rowMin[l][r] = x;
            if (x > rowMax[l][r]) rowMax[l][r] = x;
        }
        for (int l = 1; l <= nLab; l++) {
            if (rowMin[l] == null) continue;
            int rows = rowMin[l].length;
            long[] px = new long[rows * 4];
            int np = 0;
            for (int r = 0; r < rows; r++) {
                if (rowMax[l][r] < 0) continue;
                int y = minY[l] + r;
                px[np++] = pack(rowMin[l][r], y);
                px[np++] = pack(rowMin[l][r], y + 1);
                px[np++] = pack(rowMax[l][r] + 1, y);
                px[np++] = pack(rowMax[l][r] + 1, y + 1);
            }
            double hull = hullArea(Arrays.copyOf(px, np));
            single[l] = hull > 0 && area[l] / hull >= SINGLE_SOLIDITY;
        }
        return single;
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    /** Area of the convex hull of points packed as (x << 32 | y), x and y >= 0 (Andrew's monotone chain). */
    private static double hullArea(long[] p) {
        Arrays.sort(p); // by x, then y
        int n = p.length;
        if (n < 3) return 0;
        long[] hull = new long[2 * n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        double a = 0;
        for (int i = 0; i < k - 1; i++) {
            a += (double) (hull[i] >> 32) * (int) hull[i + 1] - (double) (hull[i + 1] >> 32) * (int) hull[i];
        }
        return Math.abs(a) / 2;
    }

    private static long cross(long o, long a, long b) {
        long ox = o >> 32, oy = (int) o, ax = a >> 32, ay = (int) a, bx = b >> 32, by = (int) b;
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    // ---------------------------------------------------------------- printed text and symbols

    /** A mark's strokes are at most this fraction of the typical grain thickness. */
    public static float MARK_THIN = 0.5f;
    /** Thin pieces at least this long for their width (area / (pi * halfWidth^2)) are strokes, not broken grains. */
    public static float MARK_STROKE = 5f;
    /** Thin pieces with at least this much enclosed hole are letters like o, e, 0, 8 or drawn circles. */
    public static float MARK_HOLE = 0.2f;
    /** Thin specks smaller than this fraction of a grain are bits of print or dust (broken grains are bigger). */
    public static float MARK_SPECK = 0.1f;
    /** ...and the inside of that hole looks like the background (difference score below this). */
    public static float MARK_HOLE_EMPTY = 0.2f;

    /**
     * Finds printed text, lines and symbols (letters, digits, underlines, arrows, ticks, boxes, rulers...) and
     * removes them from the mask and the labels. Their strokes are much thinner than the grains in the same photo,
     * while a grain, a broken piece or a pile of touching grains is as thick as a grain. Small thin pieces next to
     * a mark (the rest of a word, i dots, full stops, hyphens) go with it.
     * Returns the number of pieces removed; their area is set to 0.
     */
    private static int removeMarks(int[] argb, float[] score, boolean[] open, boolean[] mask, int[] lab, int nLab,
                                   double[] area, int w, int h, float s) {
        final int n = w * h;
        // Half-thickness of every piece, measured with its holes still open (an "o" is a thin ring, not a disc).
        double[] du = edt(open, w, h);
        double[] thick = new double[nLab + 1];
        double[] inkArea = new double[nLab + 1];
        double[] holeScore = new double[nLab + 1];
        for (int i = 0; i < n; i++) {
            int l = lab[i];
            if (l > 0 && open[i]) {
                inkArea[l]++;
                if (du[i] > thick[l]) thick[l] = du[i];
            } else if (l > 0) {
                holeScore[l] += score[i];
            }
        }
        // Typical grain half-thickness: upper quartile of the pieces that are not crumbs, so that a page
        // with many letters still measures the grains.
        double med0 = median(Arrays.copyOfRange(area, 1, nLab + 1));
        int nc = 0;
        double[] tc = new double[nLab];
        for (int l = 1; l <= nLab; l++) if (area[l] > 0.5 * med0) tc[nc++] = thick[l];
        if (nc == 0) return 0;
        tc = Arrays.copyOf(tc, nc);
        Arrays.sort(tc);
        double typical = tc[Math.min(nc - 1, (int) (0.75 * nc))];
        if (typical < 2) return 0; // grains only a few pixels wide: strokes cannot be told apart
        double grainArea = Math.PI * typical * typical;
        double hairline = 3 * s; // printed strokes are a few pixels wide; no grain or broken piece is that thin

        boolean[] thin = new boolean[nLab + 1];
        boolean[] mark = new boolean[nLab + 1];
        boolean[] sure = new boolean[nLab + 1]; // clearly print (not just a speck): may pull its neighbours in
        int marks = 0;
        for (int l = 1; l <= nLab; l++) {
            thin[l] = inkArea[l] > 0 && thick[l] < MARK_THIN * typical;
            if (!thin[l]) continue;
            double stroke = inkArea[l] / (Math.PI * thick[l] * thick[l]);
            double hole = 1 - inkArea[l] / area[l];
            // A printed ring (o, e, 0, a drawn circle) has plain background inside; a grain with a light centre does not.
            boolean emptyHole = hole > 0 && holeScore[l] / (area[l] - inkArea[l]) < MARK_HOLE_EMPTY;
            sure[l] = stroke > MARK_STROKE || (hole > MARK_HOLE && emptyHole) || thick[l] <= hairline;
            // Tiny and nearly as thin as a hairline: a bit of print or dust (tiny seeds like mustard are thicker).
            boolean speck = area[l] < MARK_SPECK * grainArea && thick[l] <= 1.5 * hairline;
            if (sure[l] || speck) {
                mark[l] = true;
                marks++;
            }
        }
        if (marks == 0) return 0;

        // The rest of the same text: small thin pieces close to a mark, spreading letter by letter along a word.
        // "Close" = bounding boxes less than 2.5 grain half-widths apart.
        int[] minX = new int[nLab + 1], maxX = new int[nLab + 1], minY = new int[nLab + 1], maxY = new int[nLab + 1];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        for (int i = 0; i < n; i++) {
            int l = lab[i];
            if (l == 0) continue;
            int x = i % w, y = i / w;
            if (x < minX[l]) minX[l] = x;
            if (x > maxX[l]) maxX[l] = x;
            if (y < minY[l]) minY[l] = y;
            if (y > maxY[l]) maxY[l] = y;
        }
        int[] cand = new int[nLab];
        int ncand = 0;
        for (int l = 1; l <= nLab; l++) if (!mark[l] && thin[l] && area[l] < 0.35 * grainArea) cand[ncand++] = l;
        int[] queue = new int[nLab];
        int qn = 0;
        for (int l = 1; l <= nLab; l++) if (sure[l]) queue[qn++] = l;
        double near = 2.5 * typical;
        for (int q = 0; q < qn && ncand > 0; q++) {
            int m = queue[q];
            for (int c = ncand - 1; c >= 0; c--) {
                int l = cand[c];
                double gx = Math.max(0, Math.max(minX[l] - maxX[m], minX[m] - maxX[l]));
                double gy = Math.max(0, Math.max(minY[l] - maxY[m], minY[m] - maxY[l]));
                if (gx * gx + gy * gy <= near * near) {
                    mark[l] = true;
                    marks++;
                    queue[qn++] = l;
                    cand[c] = cand[--ncand];
                }
            }
        }
        marks += removeWatermark(argb, lab, mark, area, minX, maxX, minY, maxY, nLab, w, h, grainArea, true);
        marks += removeWatermark(argb, lab, mark, area, minX, maxX, minY, maxY, nLab, w, h, grainArea, false);
        for (int i = 0; i < n; i++) {
            if (lab[i] > 0 && mark[lab[i]]) {
                lab[i] = 0;
                mask[i] = false;
            }
        }
        for (int l = 1; l <= nLab; l++) if (mark[l]) area[l] = 0;
        return marks;
    }

    /** Camera watermarks sit within this fraction of the photo height from the top or bottom edge. */
    private static final float WATERMARK_BAND = 0.16f;

    /**
     * Phone camera watermarks ("Shot on ...", "AI QUAD CAMERA", the phone model, a date stamp, a small brand logo)
     * are a line of text in a corner at the bottom (or top) of the photo. When marks form such a line, the pieces
     * inside the line's box that have the text's colour (bold letters) or a badge shape (a square logo) go too.
     * Grains of another colour next to the watermark are kept.
     * Returns the number of extra pieces removed.
     */
    private static int removeWatermark(int[] argb, int[] lab, boolean[] mark, double[] area, int[] minX, int[] maxX,
                                       int[] minY, int[] maxY, int nLab, int w, int h, double grainArea,
                                       boolean bottom) {
        double band = WATERMARK_BAND * h;
        boolean[] line = new boolean[nLab + 1];
        int bx0 = Integer.MAX_VALUE, bx1 = -1, by0 = Integer.MAX_VALUE, by1 = -1, k = 0;
        double[] heights = new double[nLab];
        for (int l = 1; l <= nLab; l++) {
            if (!mark[l] || maxX[l] < 0) continue;
            boolean in = bottom ? minY[l] >= h - band : maxY[l] <= band;
            if (!in) continue;
            line[l] = true;
            bx0 = Math.min(bx0, minX[l]);
            bx1 = Math.max(bx1, maxX[l]);
            by0 = Math.min(by0, minY[l]);
            by1 = Math.max(by1, maxY[l]);
            heights[k++] = maxY[l] - minY[l] + 1;
        }
        if (k < 4) return 0;                                           // a few specks are not a line of text
        boolean corner = bx0 < 0.12 * w || bx1 > 0.88 * w;             // anchored to the left or right edge
        boolean edge = bottom ? by1 > h - 0.1 * h : by0 < 0.1 * h;     // and close to the top / bottom edge
        if (!corner || !edge) return 0;
        // Bold letters: tight box around the text. Logo badge: anywhere within one block height of it.
        double lineH = median(Arrays.copyOf(heights, k)), blockH = by1 - by0 + 1;
        double x0 = bx0 - 1.5 * lineH, x1 = bx1 + 1.5 * lineH, y0 = by0 - 0.5 * lineH, y1 = by1 + 0.5 * lineH;
        double lx0 = bx0 - blockH, lx1 = bx1 + blockH, ly0 = by0 - blockH, ly1 = by1 + blockH;
        boolean[] inBox = new boolean[nLab + 1], nearBox = new boolean[nLab + 1];
        int nIn = 0;
        for (int l = 1; l <= nLab; l++) {
            if (mark[l] || maxX[l] < 0 || area[l] > 3 * grainArea) continue;
            inBox[l] = minX[l] >= x0 && maxX[l] <= x1 && minY[l] >= y0 && maxY[l] <= y1;
            nearBox[l] = minX[l] >= lx0 && maxX[l] <= lx1 && minY[l] >= ly0 && maxY[l] <= ly1;
            if (nearBox[l]) nIn++;
        }
        if (nIn == 0) return 0;

        // Colour of the watermark text vs colour of each piece in the box.
        double tr = 0, tg = 0, tb = 0, tn = 0;
        double[] cr = new double[nLab + 1], cg = new double[nLab + 1], cb = new double[nLab + 1];
        for (int i = 0; i < argb.length; i++) {
            int l = lab[i];
            if (l == 0 || !(line[l] || nearBox[l])) continue;
            int p = argb[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (line[l]) { tr += r; tg += g; tb += b; tn++; }
            else { cr[l] += r; cg[l] += g; cb[l] += b; }
        }
        tr /= tn; tg /= tn; tb /= tn;
        int removed = 0;
        for (int l = 1; l <= nLab; l++) {
            if (!nearBox[l]) continue;
            double dr = cr[l] / area[l] - tr, dg = cg[l] / area[l] - tg, db = cb[l] / area[l] - tb;
            boolean textColour = inBox[l] && Math.sqrt(dr * dr + dg * dg + db * db) < 60;
            double boxFill = area[l] / ((double) (maxX[l] - minX[l] + 1) * (maxY[l] - minY[l] + 1));
            boolean badge = boxFill > 0.85;                            // square / rectangular logo (grains fill <= ~0.82)
            if (textColour || badge) {
                mark[l] = true;
                removed++;
            }
        }
        return removed;
    }

    /** Sorts markers top-to-bottom in bands, then left-to-right, so numbers are easy to follow. */
    public static void sortReadingOrder(List<float[]> pts, final float band) {
        final float b = Math.max(1f, band);
        Collections.sort(pts, new Comparator<float[]>() {
            @Override
            public int compare(float[] p, float[] q) {
                int rp = (int) (p[1] / b), rq = (int) (q[1] / b);
                if (rp != rq) return rp < rq ? -1 : 1;
                return Float.compare(p[0], q[0]);
            }
        });
    }

    // ---------------------------------------------------------------- adaptive segmentation

    private static final class Seg {
        boolean[] fg;          // grains: anything on the sheet that differs enough from the background
        float[] score;         // how different from the background each pixel is
        boolean paperFound;    // a clear plain background was found
        float paperLum;        // typical background brightness (0..255)
        float contrast;        // how strongly grains stand out (1 = clearly, <0.25 = hard to see)
        float noise;           // background noise level in score units
    }

    /** Local background colour/brightness maps on a coarse grid (they follow shading and colour casts). */
    private static final class Grid {
        int gw, gh, blk;
        float[] lum, rn, gn;
    }

    /** Below this grain contrast the difference map is smoothed before thresholding. */
    private static final float LOW_CONTRAST_SMOOTHING = 0.35f;
    private static final float CHROMA_SCALE = 0.25f;  // chromaticity distance that counts as "fully different"
    private static final float LUM_SCALE = 0.8f;       // relative brightness change that counts as "fully different"
    private static final float FAINT_LEVEL = 0.25f;    // difference score that still makes a separate faint grain
    private static final float LIGHT_SHEET = 150f;     // background brightness from which reflections are ignored
    private static final float GLARE_LEVEL = 0.035f;   // mean extra brightness over a wide patch that marks a reflection

    /**
     * How much brighter than the sheet each part of the photo is, averaged over patches several grains wide
     * (on the coarse background grid). A bright grain is small and averages away; a reflection does not.
     */
    private static float[] glareMap(int[] argb, int w, int h, Grid g) {
        int gw = g.gw, gh = g.gh, blk = g.blk;
        double[] excess = new double[gw * gh], cnt = new double[gw * gh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                float lum = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
                float ratio = lum / Math.max(8f, sample(g.lum, gw, gh, blk, x, y));
                int cell = (y / blk) * gw + (x / blk);
                excess[cell] += Math.max(0f, ratio - 1f);
                cnt[cell] += 1;
            }
        }
        int r = Math.max(2, Math.round(Math.max(w, h) / 1280f * 6)); // ~24 px at 1280 px
        double[] be = boxBlur(excess, gw, gh, r), bc = boxBlur(cnt, gw, gh, r);
        float[] out = new float[gw * gh];
        for (int i = 0; i < out.length; i++) out[i] = bc[i] > 0 ? (float) (be[i] / bc[i]) : 0f;
        return out;
    }

    /**
     * Learns what the plain background looks like in each part of the photo and scores every pixel by how
     * different it is (colour OR brightness, darker OR brighter). Nothing about the grain is assumed, so any
     * grain colour, dark or light, on any plain background, under any lighting, is handled.
     */
    private static Seg segment(int[] argb, int w, int h, float s) {
        final int n = w * h;
        Seg out = new Seg();
        out.fg = new boolean[n];

        // 1. The background is the biggest bright chromaticity cluster (r/(r+g+b), g/(r+g+b)).
        final int B = 40;
        int[] cnt = new int[B * B];
        double[] sumY = new double[B * B], sr = new double[B * B], sg = new double[B * B];
        int total = 0;
        for (int i = 0; i < n; i += 3) {
            int p = argb[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int sum = r + g + b;
            if (sum < 60) continue;
            float rn = (float) r / sum, gn = (float) g / sum;
            int k = Math.min(B - 1, (int) (rn * B)) * B + Math.min(B - 1, (int) (gn * B));
            cnt[k]++;
            sumY[k] += 0.299 * r + 0.587 * g + 0.114 * b;
            sr[k] += rn;
            sg[k] += gn;
            total++;
        }
        if (total == 0) return out;
        int best = -1;
        double bestY = -1;
        int biggest = 0;
        for (int k = 0; k < cnt.length; k++) {
            if (cnt[k] > cnt[biggest]) biggest = k;
            if (cnt[k] >= 0.06 * total) {
                double y = sumY[k] / cnt[k];
                if (y > bestY) { bestY = y; best = k; }
            }
        }
        if (best < 0) best = biggest;
        double wsum = 0, cr = 0, cg = 0, ysum = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                int bi = best / B + di, bj = best % B + dj;
                if (bi < 0 || bj < 0 || bi >= B || bj >= B) continue;
                int kk = bi * B + bj;
                wsum += cnt[kk]; cr += sr[kk]; cg += sg[kk]; ysum += sumY[kk];
            }
        }
        cr /= wsum; cg /= wsum;
        out.paperLum = (float) (ysum / wsum);

        // 2. Local background maps, refined once: first from the global colour, then from the first map.
        Grid g1 = buildGrid(argb, w, h, null, (float) cr, (float) cg, out.paperLum);
        Grid g2 = buildGrid(argb, w, h, g1, (float) cr, (float) cg, out.paperLum);

        // 3. Difference score for every pixel (0 = looks like background, 1 = clearly something else).
        //    Reflections (flash, lamp, window on a glossy table) are wide patches brighter than the sheet; inside
        //    them being brighter does not count, only colour does (grains there are still darker or coloured).
        float[] glare = out.paperLum >= LIGHT_SHEET ? glareMap(argb, w, h, g2) : null;
        float[] score = new float[n];
        float[] ratioArr = new float[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float fl = Math.max(8f, sample(g2.lum, g2.gw, g2.gh, g2.blk, x, y));
                float ratio = lum / fl;
                boolean reflection = ratio > 1f && glare != null && sample(glare, g2.gw, g2.gh, g2.blk, x, y) > GLARE_LEVEL;
                float dev = reflection ? 0f : Math.min(2f, Math.abs(ratio - 1f)) / LUM_SCALE;
                float dc = 0;
                int sum = r + g + b;
                if (sum >= 45) {
                    float dx = (float) r / sum - sample(g2.rn, g2.gw, g2.gh, g2.blk, x, y);
                    float dy = (float) g / sum - sample(g2.gn, g2.gw, g2.gh, g2.blk, x, y);
                    dc = (float) Math.sqrt(dx * dx + dy * dy) / CHROMA_SCALE;
                }
                score[i] = (float) Math.sqrt(dc * dc + dev * dev);
                ratioArr[i] = ratio;
            }
        }

        // 4. Find the sheet: the biggest region that looks like background (row/column extents).
        boolean[] paperLike = new boolean[n];
        double sq = 0;
        int sc = 0;
        for (int i = 0; i < n; i++) {
            paperLike[i] = score[i] < 0.2f && ratioArr[i] > 0.72f && ratioArr[i] < 1.5f;
            if (score[i] < 0.2f && (i & 3) == 0) { sq += score[i] * score[i]; sc++; }
        }
        out.noise = sc > 0 ? (float) Math.sqrt(sq / sc) : 0.05f;
        boolean[] paper = open(paperLike, w, h, Math.max(1, Math.round(3 * s)));
        int[] lab = new int[n];
        int c = label(paper, w, h, lab);
        boolean[] hull = new boolean[n];
        Arrays.fill(hull, true);
        if (c > 0) {
            int[] cn = new int[c + 1];
            for (int i = 0; i < n; i++) if (lab[i] > 0) cn[lab[i]]++;
            int big = 1;
            for (int k = 2; k <= c; k++) if (cn[k] > cn[big]) big = k;
            int[] minX = new int[h], maxX = new int[h], minY = new int[w], maxY = new int[w];
            Arrays.fill(minX, Integer.MAX_VALUE);
            Arrays.fill(maxX, -1);
            Arrays.fill(minY, Integer.MAX_VALUE);
            Arrays.fill(maxY, -1);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (lab[y * w + x] == big) {
                        if (x < minX[y]) minX[y] = x;
                        if (x > maxX[y]) maxX[y] = x;
                        if (y < minY[x]) minY[x] = y;
                        if (y > maxY[x]) maxY[x] = y;
                    }
                }
            }
            boolean[] hl = new boolean[n];
            int hc = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (x >= minX[y] && x <= maxX[y] && y >= minY[x] && y <= maxY[x]) {
                        hl[y * w + x] = true;
                        hc++;
                    }
                }
            }
            if (hc >= 0.2 * n) {
                double e = Math.max(2, Math.round(6 * s));
                double[] d = edt(hl, w, h);
                for (int i = 0; i < n; i++) hl[i] = d[i] > e;
                hull = hl;
                out.paperFound = cn[big] >= 0.2 * n;
            }
        }

        // 5. Grain threshold at half of the typical grain contrast: the true edge, so touching grains stay apart.
        //    Faint grains (low contrast) are denoised first by smoothing the difference map.
        float noise = out.noise;
        for (int pass = 0; pass < 2; pass++) {
            final float tauLo = Math.max(0.10f, 3.5f * noise);
            int[] hist = new int[151];
            int cand = 0;
            for (int i = 0; i < n; i++) {
                if (hull[i] && score[i] > tauLo) {
                    hist[Math.min(150, (int) (score[i] * 50))]++;
                    cand++;
                }
            }
            if (cand < 40) {
                out.contrast = 0;
                return out;
            }
            int target = (int) (cand * 0.6), acc = 0, bin = 0;
            for (; bin < 150; bin++) {
                acc += hist[bin];
                if (acc >= target) break;
            }
            out.contrast = Math.min(3f, (bin + 0.5f) / 50f);
            if (pass == 0 && out.contrast < LOW_CONTRAST_SMOOTHING) {
                score = gauss(score, w, h, Math.max(1.0, 1.6 * s));   // faint grains: average out the noise
                double sq2 = 0;
                int sc2 = 0;
                for (int i = 0; i < n; i += 4) {
                    if (paperLike[i]) { sq2 += score[i] * score[i]; sc2++; }
                }
                noise = sc2 > 0 ? (float) Math.sqrt(sq2 / sc2) : noise;
                continue;
            }
            out.score = score;
            final float thr = Math.min(0.6f, Math.max(tauLo, 0.5f * out.contrast));
            for (int i = 0; i < n; i++) out.fg[i] = hull[i] && score[i] > thr;

            // Mixed grains: a pale kind next to a dark kind (chickpeas with rajma) may reach the threshold set by
            // the dark ones only at its rim, or not at all. A faint object that is mostly below the threshold is
            // kept whole at a lower level; objects that are mostly strong keep their sharp edge (touching grains).
            final float low = Math.max(tauLo, FAINT_LEVEL);
            if (low < thr) {
                boolean[] faint = new boolean[n];
                for (int i = 0; i < n; i++) faint[i] = hull[i] && score[i] > low;
                int[] fl = new int[n];
                int nf = label(faint, w, h, fl);
                int[] strong = new int[nf + 1], all = new int[nf + 1];
                for (int i = 0; i < n; i++) {
                    if (fl[i] == 0) continue;
                    all[fl[i]]++;
                    if (out.fg[i]) strong[fl[i]]++;
                }
                for (int i = 0; i < n; i++) if (fl[i] > 0 && strong[fl[i]] < 0.5 * all[fl[i]]) out.fg[i] = true;
            }
            break;
        }
        return out;
    }

    /**
     * Builds the local background maps. A pixel feeds the map only if it looks like background:
     * right colour and not far darker/brighter than the map says (pass 2) or than the global level (pass 1).
     */
    private static Grid buildGrid(int[] argb, int w, int h, Grid ref, float cr, float cg, float paperLum) {
        final int blk = 4;
        Grid g = new Grid();
        g.blk = blk;
        g.gw = (w + blk - 1) / blk;
        g.gh = (h + blk - 1) / blk;
        int gw = g.gw, gh = g.gh;
        double[] nl = new double[gw * gh], nr = new double[gw * gh], ng = new double[gw * gh], den = new double[gw * gh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                int sum = r + gg + b;
                if (sum < 45) continue;
                float lum = 0.299f * r + 0.587f * gg + 0.114f * b;
                float rn = (float) r / sum, gn = (float) gg / sum;
                float rl, rr, rg;
                if (ref == null) {
                    rl = paperLum; rr = cr; rg = cg;
                } else {
                    rl = sample(ref.lum, gw, gh, blk, x, y);
                    rr = sample(ref.rn, gw, gh, blk, x, y);
                    rg = sample(ref.gn, gw, gh, blk, x, y);
                }
                float dx = rn - rr, dy = gn - rg;
                float ratio = lum / Math.max(8f, rl);
                if (dx * dx + dy * dy < 0.05f * 0.05f && ratio > 0.6f && ratio < 1.6f) {
                    int cell = (y / blk) * gw + (x / blk);
                    nl[cell] += lum; nr[cell] += rn; ng[cell] += gn; den[cell] += 1;
                }
            }
        }
        int rb = Math.max(3, Math.min(gw, gh) / 6);
        double[] bl = boxBlur(nl, gw, gh, rb), br = boxBlur(nr, gw, gh, rb), bg = boxBlur(ng, gw, gh, rb), bd = boxBlur(den, gw, gh, rb);
        g.lum = new float[gw * gh];
        g.rn = new float[gw * gh];
        g.gn = new float[gw * gh];
        for (int i = 0; i < gw * gh; i++) {
            boolean ok = bd[i] > 40;
            g.lum[i] = ok ? (float) (bl[i] / bd[i]) : paperLum;
            g.rn[i] = ok ? (float) (br[i] / bd[i]) : cr;
            g.gn[i] = ok ? (float) (bg[i] / bd[i]) : cg;
        }
        return g;
    }

    private static float sample(float[] c, int gw, int gh, int blk, int x, int y) {
        float gy = Math.min(gh - 1f, Math.max(0f, (y + 0.5f) / blk - 0.5f));
        float gx = Math.min(gw - 1f, Math.max(0f, (x + 0.5f) / blk - 0.5f));
        int y0 = (int) gy, x0 = (int) gx;
        int y1 = Math.min(gh - 1, y0 + 1), x1 = Math.min(gw - 1, x0 + 1);
        float fy = gy - y0, fx = gx - x0;
        float top = c[y0 * gw + x0] * (1 - fx) + c[y0 * gw + x1] * fx;
        float bot = c[y1 * gw + x0] * (1 - fx) + c[y1 * gw + x1] * fx;
        return top * (1 - fy) + bot * fy;
    }

    private static double[] boxBlur(double[] a, int w, int h, int r) {
        double[] t = new double[w * h], o = new double[w * h];
        for (int y = 0; y < h; y++) {
            double acc = 0;
            for (int x = -r; x < w; x++) {
                if (x + r < w) acc += a[y * w + x + r];
                if (x - r - 1 >= 0) acc -= a[y * w + x - r - 1];
                if (x >= 0) t[y * w + x] = acc;
            }
        }
        for (int x = 0; x < w; x++) {
            double acc = 0;
            for (int y = -r; y < h; y++) {
                if (y + r < h) acc += t[(y + r) * w + x];
                if (y - r - 1 >= 0) acc -= t[(y - r - 1) * w + x];
                if (y >= 0) o[y * w + x] = acc;
            }
        }
        return o;
    }

    // ---------------------------------------------------------------- morphology / labelling

    private static boolean[] open(boolean[] a, int w, int h, int iterations) {
        boolean[] cur = a;
        for (int i = 0; i < iterations; i++) cur = erode(cur, w, h);
        for (int i = 0; i < iterations; i++) cur = dilate(cur, w, h);
        return cur;
    }

    private static boolean[] erode(boolean[] a, int w, int h) {
        boolean[] o = new boolean[a.length];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                o[i] = a[i] && a[i - 1] && a[i + 1] && a[i - w] && a[i + w];
            }
        }
        return o;
    }

    private static boolean[] dilate(boolean[] a, int w, int h) {
        boolean[] o = new boolean[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                o[i] = a[i] || (x > 0 && a[i - 1]) || (x < w - 1 && a[i + 1])
                        || (y > 0 && a[i - w]) || (y < h - 1 && a[i + w]);
            }
        }
        return o;
    }

    /** Fills enclosed holes (e.g. the lighter centre of a lentil) in place. */
    private static void fillHoles(boolean[] a, int w, int h) {
        int n = w * h;
        boolean[] bg = new boolean[n];
        int[] stack = new int[n];
        int sp = 0;
        for (int x = 0; x < w; x++) {
            int t = x, b = (h - 1) * w + x;
            if (!a[t] && !bg[t]) { bg[t] = true; stack[sp++] = t; }
            if (!a[b] && !bg[b]) { bg[b] = true; stack[sp++] = b; }
        }
        for (int y = 0; y < h; y++) {
            int l = y * w, r = y * w + w - 1;
            if (!a[l] && !bg[l]) { bg[l] = true; stack[sp++] = l; }
            if (!a[r] && !bg[r]) { bg[r] = true; stack[sp++] = r; }
        }
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % w, y = i / w;
            if (x > 0 && !a[i - 1] && !bg[i - 1]) { bg[i - 1] = true; stack[sp++] = i - 1; }
            if (x < w - 1 && !a[i + 1] && !bg[i + 1]) { bg[i + 1] = true; stack[sp++] = i + 1; }
            if (y > 0 && !a[i - w] && !bg[i - w]) { bg[i - w] = true; stack[sp++] = i - w; }
            if (y < h - 1 && !a[i + w] && !bg[i + w]) { bg[i + w] = true; stack[sp++] = i + w; }
        }
        for (int i = 0; i < n; i++) if (!a[i] && !bg[i]) a[i] = true;
    }

    /** 4-connected component labelling. Returns the number of components; labels start at 1. */
    private static int label(boolean[] a, int w, int h, int[] lab) {
        int n = w * h;
        int[] stack = new int[n];
        int count = 0;
        for (int start = 0; start < n; start++) {
            if (!a[start] || lab[start] != 0) continue;
            count++;
            int sp = 0;
            stack[sp++] = start;
            lab[start] = count;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % w, y = i / w;
                if (x > 0 && a[i - 1] && lab[i - 1] == 0) { lab[i - 1] = count; stack[sp++] = i - 1; }
                if (x < w - 1 && a[i + 1] && lab[i + 1] == 0) { lab[i + 1] = count; stack[sp++] = i + 1; }
                if (y > 0 && a[i - w] && lab[i - w] == 0) { lab[i - w] = count; stack[sp++] = i - w; }
                if (y < h - 1 && a[i + w] && lab[i + w] == 0) { lab[i + w] = count; stack[sp++] = i + w; }
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- distance transform / blur

    private static final double INF = 1e20;

    /** Euclidean distance from every true pixel to the nearest false pixel (Felzenszwalb). */
    private static double[] edt(boolean[] a, int w, int h) {
        double[] f = new double[w * h];
        for (int i = 0; i < f.length; i++) f[i] = a[i] ? INF : 0;
        int m = Math.max(w, h);
        double[] col = new double[m], out = new double[m], z = new double[m + 1];
        int[] v = new int[m];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) col[y] = f[y * w + x];
            dt1d(col, out, h, v, z);
            for (int y = 0; y < h; y++) f[y * w + x] = out[y];
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) col[x] = f[y * w + x];
            dt1d(col, out, w, v, z);
            for (int x = 0; x < w; x++) f[y * w + x] = out[x];
        }
        for (int i = 0; i < f.length; i++) f[i] = Math.sqrt(f[i]);
        return f;
    }

    private static void dt1d(double[] f, double[] d, int n, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            double s;
            while (true) {
                int p = v[k];
                s = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
                if (s <= z[k]) k--;
                else break;
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            int p = v[k];
            d[q] = (double) (q - p) * (q - p) + f[p];
        }
    }

    private static float[] gauss(float[] src, int w, int h, double sigma) {
        int r = (int) (4 * sigma + 0.5);
        float[] k = new float[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) {
            double v = Math.exp(-0.5 * i * i / (sigma * sigma));
            k[i + r] = (float) v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) k[i] /= (float) sum;

        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int t = -r; t <= r; t++) {
                    int xx = x + t;
                    if (xx < 0) xx = -xx - 1;
                    else if (xx >= w) xx = 2 * w - xx - 1;
                    xx = Math.min(Math.max(xx, 0), w - 1);
                    acc += k[t + r] * src[row + xx];
                }
                tmp[row + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int t = -r; t <= r; t++) {
                    int yy = y + t;
                    if (yy < 0) yy = -yy - 1;
                    else if (yy >= h) yy = 2 * h - yy - 1;
                    yy = Math.min(Math.max(yy, 0), h - 1);
                    acc += k[t + r] * tmp[yy * w + x];
                }
                out[y * w + x] = acc;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- small helpers

    private static double median(double[] a) {
        if (a.length == 0) return 1;
        double[] c = a.clone();
        Arrays.sort(c);
        int m = c.length / 2;
        return (c.length % 2 == 1) ? c[m] : 0.5 * (c[m - 1] + c[m]);
    }

    private static double[] filterAbove(double[] a, double min) {
        int c = 0;
        for (double v : a) if (v > min) c++;
        double[] o = new double[c];
        int j = 0;
        for (double v : a) if (v > min) o[j++] = v;
        return o;
    }
}
