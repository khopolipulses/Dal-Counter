package com.dalcounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The editable list of counted grains. Plain Java (no Android classes) so it is unit-tested on a desktop JVM.
 * Keeps reading order (top-to-bottom, left-to-right), supports tap-to-add / tap-to-remove and undo.
 */
public final class Markers {
    public static final int AUTO = 0;       // found by the engine, looks certain
    public static final int UNCERTAIN = 1;  // found by the engine inside a touching/overlapping group
    public static final int MANUAL = 2;     // added by the user
    public static final int CONFIRMED = 3;  // found by the engine and checked by the user

    public static final class Marker {
        public final float x, y;
        public final int kind;

        Marker(float x, float y, int kind) {
            this.x = x;
            this.y = y;
            this.kind = kind;
        }
    }

    /** One undoable edit: an add, a remove, or a marker replaced by another (confirmed). */
    private static final class Op {
        final boolean added;
        final Marker marker;
        final Marker replaced;

        Op(boolean added, Marker marker) {
            this(added, marker, null);
        }

        Op(boolean added, Marker marker, Marker replaced) {
            this.added = added;
            this.marker = marker;
            this.replaced = replaced;
        }
    }

    private final ArrayList<Marker> list = new ArrayList<Marker>();
    private final ArrayList<Op> undoStack = new ArrayList<Op>();
    private float band = 25f;

    /** Replaces everything with the engine's result. */
    public void set(float[] xs, float[] ys, boolean[] uncertain, float band) {
        list.clear();
        undoStack.clear();
        this.band = Math.max(1f, band);
        for (int i = 0; i < xs.length; i++) {
            boolean u = uncertain != null && i < uncertain.length && uncertain[i];
            list.add(new Marker(xs[i], ys[i], u ? UNCERTAIN : AUTO));
        }
        sort();
    }

    public int size() {
        return list.size();
    }

    public Marker get(int i) {
        return list.get(i);
    }

    /** Current position of this marker (positions change when markers are added or removed), or -1. */
    public int indexOf(Marker m) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == m) return i;
        return -1;
    }

    public int countKind(int kind) {
        int c = 0;
        for (Marker m : list) if (m.kind == kind) c++;
        return c;
    }

    /** Index of the marker nearest to (x, y) within tol, or -1. */
    public int hit(float x, float y, float tol) {
        int best = -1;
        float bestD = tol * tol;
        for (int i = 0; i < list.size(); i++) {
            Marker m = list.get(i);
            float dx = m.x - x, dy = m.y - y;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD) {
                bestD = d2;
                best = i;
            }
        }
        return best;
    }

    public void add(float x, float y) {
        Marker m = new Marker(x, y, MANUAL);
        list.add(m);
        undoStack.add(new Op(true, m));
        sort();
    }

    public void remove(int index) {
        Marker m = list.remove(index);
        undoStack.add(new Op(false, m));
    }

    /** Marks a marker as checked by the user (it is a real grain). */
    public void confirm(int index) {
        Marker old = list.get(index);
        if (old.kind == CONFIRMED) return;
        Marker m = new Marker(old.x, old.y, CONFIRMED);
        list.set(index, m);
        undoStack.add(new Op(true, m, old));
    }

    /** Index of the first marker of this kind at or after `from` (wrapping around), or -1. */
    public int next(int kind, int from) {
        int n = list.size();
        for (int k = 0; k < n; k++) {
            int i = ((from + k) % n + n) % n;
            if (list.get(i).kind == kind) return i;
        }
        return -1;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Reverts the last add or remove. Returns false if there was nothing to undo. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        Op op = undoStack.remove(undoStack.size() - 1);
        if (op.replaced != null) {
            int i = list.indexOf(op.marker);
            if (i >= 0) list.set(i, op.replaced);
        } else if (op.added) {
            list.remove(op.marker);
        } else {
            list.add(op.marker);
            sort();
        }
        return true;
    }

    /** Flat (x, y, kind) triples, for saving instance state. */
    public float[] export() {
        float[] out = new float[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            Marker m = list.get(i);
            out[i * 3] = m.x;
            out[i * 3 + 1] = m.y;
            out[i * 3 + 2] = m.kind;
        }
        return out;
    }

    public void restore(float[] data, float band) {
        list.clear();
        undoStack.clear();
        this.band = Math.max(1f, band);
        if (data != null) {
            for (int i = 0; i + 2 < data.length; i += 3) list.add(new Marker(data[i], data[i + 1], (int) data[i + 2]));
        }
        sort();
    }

    private void sort() {
        final float b = band;
        Collections.sort(list, new Comparator<Marker>() {
            @Override
            public int compare(Marker p, Marker q) {
                int rp = (int) (p.y / b), rq = (int) (q.y / b);
                if (rp != rq) return rp < rq ? -1 : 1;
                int c = Float.compare(p.x, q.x);
                return c != 0 ? c : Float.compare(p.y, q.y); // full order: numbers never swap after an undo
            }
        });
    }
}
