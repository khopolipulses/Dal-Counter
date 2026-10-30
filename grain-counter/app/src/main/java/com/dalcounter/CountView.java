package com.dalcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;

/**
 * Shows the photo with numbered markers.
 * Pinch to zoom, drag to pan, double tap to zoom, tap an empty spot to add a missed grain, tap a marker to remove it.
 * Blue = counted, orange = worth a check, green = added by you. Markers pop in, the view can glide to one marker
 * (review), and a light sweep runs over the photo while it is being counted.
 */
public class CountView extends View {

    public interface Listener {
        void onMarkersChanged();
    }

    static final int BLUE = Ui.BLUE;
    static final int ORANGE = Ui.ACCENT;
    static final int GREEN = Ui.GREEN;

    private Bitmap bmp;
    private final Markers markers = new Markers();
    private float radius = 12f;
    private float band = 25f;
    private boolean editable = true;

    // image -> view: viewPoint = imagePoint * scale + offset
    private float scale = 1f, offX, offY, fitScale = 1f;
    private final Rect fitInsets = new Rect();

    private long appearStart;
    private boolean gliding;
    private long glideStart;
    private float gFromS, gFromX, gFromY, gToS, gToX, gToY;
    private static final long GLIDE_MS = 500;
    private Markers.Marker highlighted;
    private boolean scanning;
    private final long born = SystemClock.uptimeMillis();

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint();
    private LinearGradient sweepShader;
    private final Matrix sweepMove = new Matrix();
    private final Paint dim = new Paint();
    private final Matrix m = new Matrix();
    private final RectF photo = new RectF();
    private final Path tick = new Path();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener;
    private final ArrayList<View> followers = new ArrayList<View>();

    public CountView(Context ctx) {
        super(ctx);
        setBackgroundColor(Ui.CANVAS);
        ring.setStyle(Paint.Style.STROKE);
        label.setStyle(Paint.Style.FILL);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        labelOutline.setStyle(Paint.Style.STROKE);
        labelOutline.setColor(Color.WHITE);
        labelOutline.setTypeface(Typeface.DEFAULT_BOLD);
        halo.setStyle(Paint.Style.STROKE);
        dim.setColor(Color.argb(64, 0, 0, 0));

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                gliding = false;
                float target = Math.max(fitScale * 0.9f, Math.min(fitScale * 14f, scale * d.getScaleFactor()));
                float f = target / scale;
                offX = d.getFocusX() - (d.getFocusX() - offX) * f;
                offY = d.getFocusY() - (d.getFocusY() - offY) * f;
                scale = target;
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
                gliding = false;
                offX -= dx;
                offY -= dy;
                keepOnScreen();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                handleTap(e.getX(), e.getY());
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (bmp == null) return true;
                if (scale > fitScale * 1.2f) {
                    resetFit(true);
                } else {
                    float s = fitScale * 3f;
                    float ix = (e.getX() - offX) / scale, iy = (e.getY() - offY) / scale;
                    glideTo(s, e.getX() - ix * s, e.getY() - iy * s);
                }
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Views (glass panels) that show this view blurred and must redraw with it. */
    public void addFollower(View v) {
        followers.add(v);
    }

    public void setFitInsets(int left, int top, int right, int bottom) {
        fitInsets.set(left, top, right, bottom);
    }

    public Rect getFitInsets() {
        return new Rect(fitInsets);
    }

    // ------------------------------------------------------------------ data

    /** Shows the photo alone (while it is being counted). */
    public void showPhoto(Bitmap bitmap) {
        bmp = bitmap;
        markers.set(new float[0], new float[0], null, 25f);
        resetFit(false);
    }

    /** Shows a finished picture (history): zoom and pan only. */
    public void showPicture(Bitmap bitmap) {
        editable = false;
        showPhoto(bitmap);
    }

    /** Shows a freshly counted photo; the markers pop in one after another. */
    public void setData(Bitmap bitmap, DalCounter.Result r) {
        bmp = bitmap;
        radius = r.radius;
        band = r.band();
        markers.set(r.xs, r.ys, r.uncertain, band);
        if (scale == 1f && offX == 0 && offY == 0) resetFit(false);
        appearStart = SystemClock.uptimeMillis();
        fire();
    }

    /** Puts back markers saved earlier (after the app was recreated). */
    public void restoreMarkers(float[] data) {
        markers.restore(data, band);
        appearStart = 0;
        fire();
    }

    public Markers markers() {
        return markers;
    }

    public float[] exportMarkers() {
        return markers.export();
    }

    public int getCount() {
        return markers.size();
    }

    public boolean hasData() {
        return bmp != null;
    }

    public boolean undo() {
        boolean ok = markers.undo();
        if (ok) fire();
        return ok;
    }

    public void confirm(Markers.Marker mk) {
        int i = markers.indexOf(mk);
        if (i < 0) return;
        markers.confirm(i);
        fire();
    }

    public void remove(Markers.Marker mk) {
        int i = markers.indexOf(mk);
        if (i < 0) return;
        markers.remove(i);
        fire();
    }

    public void setHighlighted(Markers.Marker mk) {
        highlighted = mk;
        invalidate();
    }

    public void setScanning(boolean on) {
        scanning = on;
        invalidate();
    }

    private void fire() {
        if (listener != null) listener.onMarkersChanged();
        invalidate();
    }

    // ------------------------------------------------------------------ fitting and gliding

    private float[] fitTarget() {
        float aw = getWidth() - fitInsets.left - fitInsets.right, ah = getHeight() - fitInsets.top - fitInsets.bottom;
        if (bmp == null || aw <= 0 || ah <= 0) return new float[] {scale, offX, offY};
        float s = Math.min(aw / bmp.getWidth(), ah / bmp.getHeight());
        return new float[] {s, fitInsets.left + (aw - bmp.getWidth() * s) / 2f, fitInsets.top + (ah - bmp.getHeight() * s) / 2f};
    }

    public void resetFit(boolean animated) {
        if (bmp == null || getWidth() == 0) {
            invalidate();
            return;
        }
        float[] t = fitTarget();
        fitScale = t[0];
        if (animated) {
            glideTo(t[0], t[1], t[2]);
        } else {
            gliding = false;
            scale = t[0];
            offX = t[1];
            offY = t[2];
            invalidate();
        }
    }

    /** Glides so that this marker sits in the middle of the free area, big enough to judge. */
    public void focusOn(Markers.Marker mk) {
        float target = Math.min(fitScale * 14f, Math.max(fitScale * 1.5f, 46 * Ui.density / (radius * 1.15f)));
        float cx = fitInsets.left + (getWidth() - fitInsets.left - fitInsets.right) / 2f;
        float cy = fitInsets.top + (getHeight() - fitInsets.top - fitInsets.bottom) / 2f;
        glideTo(target, cx - mk.x * target, cy - mk.y * target);
    }

    private void glideTo(float s, float x, float y) {
        gFromS = scale;
        gFromX = offX;
        gFromY = offY;
        gToS = s;
        gToX = x;
        gToY = y;
        glideStart = SystemClock.uptimeMillis();
        gliding = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        resetFit(false);
    }

    /** Never lets the photo be dragged completely out of view. */
    private void keepOnScreen() {
        if (bmp == null) return;
        float l = offX, t = offY, r = offX + bmp.getWidth() * scale, b = offY + bmp.getHeight() * scale;
        float keepX = getWidth() * 0.25f, keepY = getHeight() * 0.25f;
        if (r < keepX) offX += keepX - r;
        else if (l > getWidth() - keepX) offX += getWidth() - keepX - l;
        if (b < keepY) offY += keepY - b;
        else if (t > getHeight() - keepY) offY += getHeight() - keepY - t;
    }

    private void handleTap(float sx, float sy) {
        if (bmp == null || !editable || scanning) return;
        float ix = (sx - offX) / scale, iy = (sy - offY) / scale;
        // Finger-sized target: at least 8 dp on screen, never smaller than the grain itself.
        float tol = Math.max(radius * 1.2f, 8 * Ui.density / scale);
        int idx = markers.hit(ix, iy, tol);
        if (idx >= 0) {
            markers.remove(idx);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (ix >= 0 && iy >= 0 && ix < bmp.getWidth() && iy < bmp.getHeight()) {
            markers.add(ix, iy);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } else {
            return;
        }
        fire();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    @android.annotation.SuppressLint("ClickableViewAccessibility") // taps call performClick in onSingleTapConfirmed
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }

    // ------------------------------------------------------------------ drawing

    static int kindColor(int kind) {
        if (kind == Markers.UNCERTAIN) return ORANGE;
        if (kind == Markers.MANUAL) return GREEN;
        return BLUE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        if (gliding) {
            float p = Math.min(1f, (now - glideStart) / (float) GLIDE_MS);
            float e = p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
            scale = gFromS + (gToS - gFromS) * e;
            offX = gFromX + (gToX - gFromX) * e;
            offY = gFromY + (gToY - gFromY) * e;
            if (p >= 1f) gliding = false;
            else again = true;
        }
        // Glass panels draw this view into their own small bitmap (software canvas): only real screen
        // frames tell them to refresh, otherwise they would keep redrawing each other.
        boolean screen = canvas.isHardwareAccelerated();
        if (bmp == null) {
            if (screen) for (View f : followers) f.invalidate();
            return;
        }
        m.reset();
        m.postScale(scale, scale);
        m.postTranslate(offX, offY);
        canvas.drawBitmap(bmp, m, bmpPaint);
        photo.set(offX, offY, offX + bmp.getWidth() * scale, offY + bmp.getHeight() * scale);

        if (scanning) {
            canvas.save();
            canvas.clipRect(photo);
            canvas.drawRect(photo, dim);
            float period = 1800f, p = ((now - born) % (long) period) / period;
            float band = Ui.dp(60), y = photo.top - band + (photo.height() + 2 * band) * p;
            if (sweepShader == null) { // made once, then moved down the photo
                sweepShader = new LinearGradient(0, -band, 0, band,
                        new int[] {Color.argb(0, 255, 255, 255), Color.argb(115, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                        null, Shader.TileMode.CLAMP);
                sweep.setShader(sweepShader);
            }
            sweepMove.setTranslate(0, y);
            sweepShader.setLocalMatrix(sweepMove);
            canvas.drawRect(photo.left, y - band, photo.right, y + band, sweep);
            canvas.restore();
            again = true;
        } else {
            again |= drawMarkers(canvas, now);
        }
        if (screen) for (View f : followers) f.invalidate();
        if (again && screen) postInvalidateOnAnimation();
    }

    /** Returns true while something is still animating. */
    private boolean drawMarkers(Canvas canvas, long now) {
        boolean again = false;
        float d = Ui.density;
        float rr = radius * scale * 1.15f;
        ring.setStrokeWidth(Math.max(1.6f * d, rr * 0.11f));
        boolean showNumbers = rr >= 11 * d;
        float textSize = Math.min(rr * 0.9f, 17 * d);
        label.setTextSize(textSize);
        labelOutline.setTextSize(textSize);
        labelOutline.setStrokeWidth(textSize * 0.18f);
        int n = markers.size(), w = getWidth(), h = getHeight();
        long sinceAppear = appearStart > 0 ? now - appearStart : Long.MAX_VALUE;
        if (appearStart > 0 && sinceAppear > 1000) appearStart = 0;
        for (int i = 0; i < n; i++) {
            Markers.Marker mk = markers.get(i);
            float x = mk.x * scale + offX, y = mk.y * scale + offY;
            if (x < -2 * rr || y < -2 * rr || x > w + 2 * rr || y > h + 2 * rr) continue;
            float grow = 1f, alpha = 1f;
            if (appearStart > 0) {                                     // staggered pop-in
                float delay = 550f * i / Math.max(1, n);
                float p = Math.max(0f, Math.min(1f, (sinceAppear - delay) / 300f));
                float c1 = 1.70158f, c3 = c1 + 1;
                grow = p <= 0 ? 0 : 1 + c3 * (float) Math.pow(p - 1, 3) + c1 * (float) Math.pow(p - 1, 2);
                alpha = p;
                again = true;
            }
            if (grow <= 0) continue;
            int color = kindColor(mk.kind);
            ring.setColor(color);
            ring.setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(x, y, rr * grow, ring);
            if (mk.kind == Markers.CONFIRMED && rr >= 8 * d) {         // small tick: checked by you
                float k = rr * 0.35f;
                tick.reset();
                tick.moveTo(x - k, y);
                tick.lineTo(x - k * 0.25f, y + k * 0.7f);
                tick.lineTo(x + k, y - k * 0.7f);
                ring.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(tick, ring);
            }
            if (showNumbers && alpha > 0.5f) {
                label.setColor(color);
                String t = String.valueOf(i + 1);
                canvas.drawText(t, x + rr, y - rr * 0.6f, labelOutline);
                canvas.drawText(t, x + rr, y - rr * 0.6f, label);
            }
            if (mk == highlighted) {                                   // review halo
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 200.0);
                halo.setStrokeWidth(Math.max(3 * d, rr * 0.18f));
                halo.setColor(Color.argb(Math.round(255 * (0.55f + 0.4f * pulse)), 255, 255, 255));
                canvas.drawCircle(x, y, rr * (1.45f + 0.15f * pulse), halo);
                again = true;
            }
        }
        return again;
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
            t.setColor(color);
            c.drawCircle(mk.x, mk.y, rr, r);
            String s = String.valueOf(i + 1);
            c.drawText(s, mk.x + rr, mk.y - rr * 0.6f, to);
            c.drawText(s, mk.x + rr, mk.y - rr * 0.6f, t);
        }

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setColor(Color.BLACK);
        title.setTextSize(40 * k);
        String text = "Total count: " + markers.size();
        float tw = title.measureText(text);
        Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        box.setColor(Color.argb(235, 255, 255, 255));
        c.drawRoundRect(new RectF(12 * k, 12 * k, 12 * k + tw + 32 * k, 72 * k), 16 * k, 16 * k, box);
        c.drawText(text, 28 * k, 56 * k, title);
        return out;
    }
}
