package com.dalcounter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/** Glass surfaces: a live blurred panel over the photo, and the softly drifting background behind the menus. */
final class Glass {
    private Glass() {}

    /**
     * Frosted glass that really blurs whatever `source` shows behind it (works on every Android version):
     * the part of the source under the panel is drawn small, box-blurred, scaled back up, tinted and edged.
     */
    static final class Panel extends FrameLayout {
        private static final int DOWN = 8; // blur is computed at 1/8 size: cheap and very smooth

        private View source;
        private final float radius;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final int[] here = new int[2], there = new int[2];
        private Bitmap small;
        private Canvas smallCanvas;
        /** The blurred picture is reused until the source changes or the panel moves over it. */
        private boolean dirty = true;
        private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        private int[] buf, tmp;

        Panel(Context c, View source, float radiusDp) {
            super(c);
            this.source = source;
            this.radius = Ui.dp(radiusDp);
            setWillNotDraw(false);
            tint.setColor(Color.argb(110, 22, 22, 26));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, Ui.dp(0.8f)));
            edge.setColor(Color.argb(60, 255, 255, 255));
            setElevation(Ui.dp(10));
            Ui.rounded(this, radiusDp);
        }

        void setSource(View v) {
            source = v;
            sourceChanged();
        }

        /** The view behind the glass drew something new. */
        void sourceChanged() {
            dirty = true;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            sheen.setShader(new LinearGradient(0, 0, w * 0.6f, h, Color.argb(36, 255, 255, 255), Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP));
            int sw = Math.max(1, w / DOWN), sh = Math.max(1, h / DOWN);
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            smallCanvas = new Canvas(small);
            dirty = true;
            buf = new int[sw * sh];
            tmp = new int[sw * sh];
        }

        @Override
        protected void onDraw(Canvas c) {
            if (small != null && source != null && source.getWidth() > 0) {
                getLocationInWindow(here);
                source.getLocationInWindow(there);
                int dx = there[0] - here[0], dy = there[1] - here[1];
                if (dirty || dx != lastX || dy != lastY) blurSource(dx, dy);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(small, null, rect, paint);
                c.restore();
            }
            c.drawPath(clip, tint);
            c.drawPath(clip, sheen);
            rect.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
            c.drawRoundRect(rect, radius, radius, edge);
            rect.inset(-edge.getStrokeWidth() / 2, -edge.getStrokeWidth() / 2);
            super.onDraw(c);
        }

        private void blurSource(int dx, int dy) {
            dirty = false;
            lastX = dx;
            lastY = dy;
            {
                Canvas sc = smallCanvas;
                sc.save();
                sc.drawColor(Ui.CANVAS);
                sc.scale(1f / DOWN, 1f / DOWN);
                sc.translate(dx, dy);
                source.draw(sc);
                sc.restore();
                blur(small, buf, tmp, 2);
            }
        }

        /** Two passes of a box blur (≈ gaussian) on a small bitmap. */
        private static void blur(Bitmap b, int[] px, int[] t, int r) {
            int w = b.getWidth(), h = b.getHeight();
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 2; pass++) {
                box(px, t, w, h, r, true);
                box(t, px, w, h, r, false);
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
        }

        private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
            int n = horizontal ? w : h, lines = horizontal ? h : w;
            for (int line = 0; line < lines; line++) {
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int k = -r; k <= r; k++) {
                    int i = Math.min(n - 1, Math.max(0, k));
                    int p = in[horizontal ? line * w + i : i * w + line];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; cnt++;
                }
                for (int i = 0; i < n; i++) {
                    out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (sr / cnt) << 16 | (sg / cnt) << 8 | (sb / cnt);
                    int add = in[horizontal ? line * w + Math.min(n - 1, i + r + 1) : Math.min(n - 1, i + r + 1) * w + line];
                    int sub = in[horizontal ? line * w + Math.max(0, i - r) : Math.max(0, i - r) * w + line];
                    sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                    sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                    sb += (add & 255) - (sub & 255);
                }
            }
        }
    }

    /** Slowly drifting warm colour blobs behind the content (a soft "mesh gradient"). */
    static final class Mesh extends View {
        private final Paint base = new Paint();
        private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] tints = {
                Color.rgb(255, 158, 51),  // dal orange
                Color.rgb(255, 204, 89),  // wheat gold
                Color.rgb(245, 115, 102), // masoor red
                Color.rgb(140, 191, 115), // moong green
        };
        private final float[][] spots = {{0.15f, 0.12f, 1.0f}, {0.9f, 0.3f, 0.9f}, {0.2f, 0.75f, 0.8f}, {0.85f, 0.9f, 1.0f}};
        private float t;
        private ValueAnimator clock;
        private final RadialGradient[] shaders = new RadialGradient[4];
        private final android.graphics.Matrix move = new android.graphics.Matrix();

        Mesh(Context c) {
            super(c);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            clock = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            clock.setDuration(24000);
            clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.setInterpolator(new LinearInterpolator());
            clock.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    if (isShown()) invalidate();
                }
            });
            clock.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (clock != null) clock.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            base.setShader(new LinearGradient(0, 0, 0, h,
                    Ui.dark ? Color.rgb(26, 20, 18) : Color.rgb(255, 247, 237),
                    Ui.dark ? Color.rgb(10, 10, 13) : Color.rgb(250, 237, 224), Shader.TileMode.CLAMP));
            int a = Ui.dark ? 77 : 107;
            for (int i = 0; i < 4; i++) { // unit-size blobs, placed and scaled with a matrix while drawing
                int col = tints[i];
                shaders[i] = new RadialGradient(0, 0, 1, Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)),
                        Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, base);
            for (int i = 0; i < 4; i++) {
                float[] s = spots[i];
                float size = Math.max(w, h) * 0.42f * s[2];
                float x = w * s[0] + (float) Math.sin(t + i * 1.7) * Ui.dp(40);
                float y = h * s[1] + (float) Math.cos(t * (i % 2 == 0 ? 1 : -1) + i) * Ui.dp(50);
                if (shaders[i] == null) continue;
                move.setScale(size, size);
                move.postTranslate(x, y);
                shaders[i].setLocalMatrix(move);
                blob.setShader(shaders[i]);
                c.drawCircle(x, y, size, blob);
            }
        }
    }
}
