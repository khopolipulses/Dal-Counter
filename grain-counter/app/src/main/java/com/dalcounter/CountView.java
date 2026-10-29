package com.dalcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Shows the photo with numbered markers.
 * Pinch to zoom, drag to pan, tap an empty spot to add a missed grain, tap a marker to remove it.
 * Blue = certain, orange = in a touching/overlapping group (check it), green = added by you.
 */
public class CountView extends View {

    public interface Listener {
        void onMarkersChanged(int count, boolean canUndo);
    }

    private static final int BLUE = Color.rgb(0, 90, 255);
    private static final int ORANGE = Color.rgb(255, 130, 0);
    private static final int GREEN = Color.rgb(0, 170, 70);

    private Bitmap bmp;
    private final Markers markers = new Markers();
    private float radius = 12f;
    private float band = 25f;

    private final Matrix m = new Matrix();
    private final Matrix inv = new Matrix();
    private final float[] vals = new float[9];
    private final RectF rect = new RectF();
    private float fitScale = 1f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener;
    private final float density;

    public CountView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        label.setStyle(Paint.Style.FILL);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        labelOutline.setStyle(Paint.Style.STROKE);
        labelOutline.setColor(Color.WHITE);
        labelOutline.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setColor(Color.LTGRAY);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(16 * density);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float target = Math.max(fitScale * 0.9f, Math.min(fitScale * 14f, cur * d.getScaleFactor()));
                float f = target / cur;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                keepOnScreen();
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) return true;
                m.postTranslate(-dx, -dy);
                keepOnScreen();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Shows a freshly counted photo. */
    public void setData(Bitmap bitmap, DalCounter.Result r) {
        bmp = bitmap;
        radius = r.radius;
        band = r.band();
        markers.set(r.xs, r.ys, r.uncertain, band);
        resetFit();
        fire();
    }

    /** Shows a photo with markers saved earlier (after the app was recreated). */
    public void setRestored(Bitmap bitmap, float radius, float band, float[] data) {
        bmp = bitmap;
        this.radius = radius;
        this.band = band;
        markers.restore(data, band);
        resetFit();
        fire();
    }

    public float[] exportMarkers() {
        return markers.export();
    }

    public float getRadius() {
        return radius;
    }

    public float getBand() {
        return band;
    }

    public int getCount() {
        return markers.size();
    }

    public int getUncertainCount() {
        return markers.countKind(Markers.UNCERTAIN);
    }

    public boolean hasData() {
        return bmp != null;
    }

    public boolean undo() {
        boolean ok = markers.undo();
        if (ok) fire();
        return ok;
    }

    public void resetFit() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) {
            invalidate();
            return;
        }
        fitScale = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        m.reset();
        m.postScale(fitScale, fitScale);
        m.postTranslate((getWidth() - bmp.getWidth() * fitScale) / 2f,
                (getHeight() - bmp.getHeight() * fitScale) / 2f);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resetFit();
    }

    private float currentScale() {
        m.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    /** Never lets the photo be dragged completely out of view. */
    private void keepOnScreen() {
        if (bmp == null) return;
        rect.set(0, 0, bmp.getWidth(), bmp.getHeight());
        m.mapRect(rect);
        float keepX = getWidth() * 0.25f, keepY = getHeight() * 0.25f;
        float dx = 0, dy = 0;
        if (rect.right < keepX) dx = keepX - rect.right;
        else if (rect.left > getWidth() - keepX) dx = getWidth() - keepX - rect.left;
        if (rect.bottom < keepY) dy = keepY - rect.bottom;
        else if (rect.top > getHeight() - keepY) dy = getHeight() - keepY - rect.top;
        if (dx != 0 || dy != 0) m.postTranslate(dx, dy);
    }

    private void fire() {
        if (listener != null) listener.onMarkersChanged(markers.size(), markers.canUndo());
        invalidate();
    }

    private void handleTap(float sx, float sy) {
        if (bmp == null) return;
        float[] p = {sx, sy};
        if (!m.invert(inv)) return;
        inv.mapPoints(p);
        // Finger-sized target: at least 8 dp on screen, never smaller than the grain itself.
        float tol = Math.max(radius * 1.2f, 8 * density / currentScale());
        int idx = markers.hit(p[0], p[1], tol);
        if (idx >= 0) {
            markers.remove(idx);
        } else if (p[0] >= 0 && p[1] >= 0 && p[0] < bmp.getWidth() && p[1] < bmp.getHeight()) {
            markers.add(p[0], p[1]);
        } else {
            return;
        }
        fire();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(32, 32, 32));
        if (bmp == null) {
            canvas.drawText("Pick a photo or take one", getWidth() / 2f, getHeight() / 2f, hint);
            return;
        }
        canvas.drawBitmap(bmp, m, bmpPaint);

        float sc = currentScale();
        float rr = radius * sc * 1.15f;
        ring.setStrokeWidth(Math.max(1.5f * density, rr * 0.1f));
        boolean showNumbers = rr >= 11 * density;
        float textSize = Math.min(rr * 0.9f, 18 * density);
        label.setTextSize(textSize);
        labelOutline.setTextSize(textSize);
        labelOutline.setStrokeWidth(textSize * 0.18f);

        float[] q = new float[2];
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < markers.size(); i++) {
            Markers.Marker mk = markers.get(i);
            q[0] = mk.x;
            q[1] = mk.y;
            m.mapPoints(q);
            if (q[0] < -rr || q[1] < -rr || q[0] > w + rr || q[1] > h + rr) continue;
            int color = kindColor(mk.kind);
            ring.setColor(color);
            canvas.drawCircle(q[0], q[1], rr, ring);
            if (showNumbers) {
                label.setColor(color == BLUE ? Color.rgb(200, 0, 0) : color);
                String t = String.valueOf(i + 1);
                canvas.drawText(t, q[0] + rr, q[1] - rr * 0.6f, labelOutline);
                canvas.drawText(t, q[0] + rr, q[1] - rr * 0.6f, label);
            }
        }
    }

    private static int kindColor(int kind) {
        if (kind == Markers.UNCERTAIN) return ORANGE;
        if (kind == Markers.MANUAL) return GREEN;
        return BLUE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    /** The processed photo with every marker, its number and the total, ready to save or share. */
    public Bitmap renderExport() {
        if (bmp == null) return null;
        Bitmap out = bmp.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return null;
        Canvas c = new Canvas(out);
        float k = Math.max(out.getWidth(), out.getHeight()) / 1600f;
        float rr = radius * 1.15f;

        Paint r = new Paint(Paint.ANTI_ALIAS_FLAG);
        r.setStyle(Paint.Style.STROKE);
        r.setStrokeWidth(2.2f * k);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(15 * k);
        Paint to = new Paint(t);
        to.setStyle(Paint.Style.STROKE);
        to.setColor(Color.WHITE);
        to.setStrokeWidth(3f * k);

        for (int i = 0; i < markers.size(); i++) {
            Markers.Marker mk = markers.get(i);
            int color = kindColor(mk.kind);
            r.setColor(color);
            t.setColor(color == BLUE ? Color.rgb(200, 0, 0) : color);
            c.drawCircle(mk.x, mk.y, rr, r);
            String s = String.valueOf(i + 1);
            c.drawText(s, mk.x + rr, mk.y - rr * 0.6f, to);
            c.drawText(s, mk.x + rr, mk.y - rr * 0.6f, t);
        }

        Paint box = new Paint();
        box.setColor(Color.WHITE);
        Paint boxLine = new Paint();
        boxLine.setStyle(Paint.Style.STROKE);
        boxLine.setColor(Color.BLACK);
        boxLine.setStrokeWidth(2f * k);
        c.drawRect(10 * k, 10 * k, 440 * k, 66 * k, box);
        c.drawRect(10 * k, 10 * k, 440 * k, 66 * k, boxLine);
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setColor(Color.BLACK);
        title.setTextSize(40 * k);
        c.drawText("Total count: " + markers.size(), 20 * k, 52 * k, title);
        return out;
    }
}
