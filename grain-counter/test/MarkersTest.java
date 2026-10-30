import com.dalcounter.Markers;

/** Unit tests for the editable marker list (the logic behind tap-to-add / tap-to-remove / undo). */
public class MarkersTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        Markers m = new Markers();
        // three markers out of order; two share a row band (band=50)
        m.set(new float[] {300, 100, 200}, new float[] {210, 20, 30}, new boolean[] {false, true, false}, 50f);
        check(m.size() == 3, "set() keeps all markers");
        check(m.get(0).x == 100 && m.get(1).x == 200 && m.get(2).x == 300, "reading order: rows first, then left-to-right");
        check(m.get(0).kind == Markers.UNCERTAIN && m.get(1).kind == Markers.AUTO, "uncertain flags follow their markers when sorted");
        check(m.countKind(Markers.UNCERTAIN) == 1, "countKind");
        check(!m.canUndo(), "nothing to undo initially");

        check(m.hit(101, 22, 5) == 0, "hit finds the nearest marker inside tolerance");
        check(m.hit(150, 150, 5) == -1, "hit returns -1 when nothing is near");
        check(m.hit(110, 20, 5) == -1, "hit respects the tolerance radius");

        m.add(150, 25);
        check(m.size() == 4 && m.get(1).x == 150 && m.get(1).kind == Markers.MANUAL, "add() inserts a MANUAL marker in reading order");
        m.remove(m.hit(300, 210, 5));
        check(m.size() == 3, "remove()");
        check(m.canUndo(), "can undo after edits");

        check(m.undo() && m.size() == 4, "undo restores the removed marker");
        check(m.get(3).x == 300 && m.get(3).y == 210, "restored marker is back in its place");
        check(m.undo() && m.size() == 3 && m.countKind(Markers.MANUAL) == 0, "undo removes the added marker");
        check(!m.undo(), "undo on an empty stack returns false");

        // export / restore (process death while the camera app is open)
        m.add(500, 500);
        float[] data = m.export();
        Markers r = new Markers();
        r.restore(data, 50f);
        check(r.size() == m.size(), "restore() brings back every marker");
        boolean same = true;
        for (int i = 0; i < m.size(); i++) same &= r.get(i).x == m.get(i).x && r.get(i).y == m.get(i).y && r.get(i).kind == m.get(i).kind;
        check(same, "restored markers are identical (position and kind)");
        check(!r.canUndo(), "undo history is not restored (by design)");

        // robustness
        Markers e = new Markers();
        e.set(new float[0], new float[0], new boolean[0], 50f);
        check(e.size() == 0 && e.hit(1, 1, 10) == -1 && !e.undo(), "empty list is safe");
        e.restore(null, 50f);
        check(e.size() == 0, "restore(null) is safe");
        e.restore(new float[] {1, 2}, 50f);
        check(e.size() == 0, "truncated data is ignored, not crashed on");
        e.set(new float[] {5}, new float[] {5}, null, 0f);
        check(e.size() == 1, "null uncertain array and zero band are safe");

        // review: confirm a doubtful marker, find the next one, undo the confirmation
        Markers v = new Markers();
        v.set(new float[] {10, 20, 30}, new float[] {5, 5, 5}, new boolean[] {true, false, true}, 50f);
        check(v.next(Markers.UNCERTAIN, 0) == 0 && v.next(Markers.UNCERTAIN, 1) == 2, "next() finds doubtful markers in order");
        v.confirm(0);
        check(v.get(0).kind == Markers.CONFIRMED && v.size() == 3, "confirm() keeps the marker and marks it checked");
        check(v.next(Markers.UNCERTAIN, 0) == 2 && v.next(Markers.UNCERTAIN, 3) == 2, "next() skips checked ones and wraps");
        check(v.undo() && v.get(0).kind == Markers.UNCERTAIN, "undo reverts a confirmation");
        v.confirm(0);
        v.confirm(2);
        check(v.next(Markers.UNCERTAIN, 0) == -1, "next() returns -1 when everything is checked");
        Markers vr = new Markers();
        vr.restore(v.export(), 50f);
        check(vr.get(2).kind == Markers.CONFIRMED, "checked state survives export/restore");

        // many edits keep undo order right
        Markers b = new Markers();
        b.set(new float[0], new float[0], null, 50f);
        for (int i = 0; i < 100; i++) b.add(i * 10, (i % 7) * 60);
        for (int i = 0; i < 40; i++) b.remove(0);
        int left = b.size();
        for (int i = 0; i < 40; i++) b.undo();
        check(left == 60 && b.size() == 100, "100 adds, 40 removes, 40 undos -> back to 100");
        System.out.println(fails == 0 ? "MARKERS: ALL PASSED" : "MARKERS: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
