package com.dalcounter;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Design tokens and small building blocks shared by every screen: colours, type, glass, buttons, icons, motion. */
final class Ui {
    private Ui() {}

    static float density = 1f;
    static boolean dark;

    static final int ACCENT = Color.rgb(255, 159, 10);
    static final int BLUE = Color.rgb(10, 132, 255);
    static final int GREEN = Color.rgb(48, 209, 88);
    static final int RED = Color.rgb(255, 69, 58);
    static final int YELLOW = Color.rgb(255, 214, 10);
    static final int GRAY = Color.rgb(142, 142, 147);
    /** Behind photos: always dark, so grains and coloured rings stand out. */
    static final int CANVAS = Color.rgb(15, 15, 18);

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static int label() {
        return dark ? Color.WHITE : Color.rgb(17, 17, 20);
    }

    static int secondary() {
        return dark ? Color.argb(160, 235, 235, 245) : Color.argb(150, 60, 60, 67);
    }

    static int tertiary() {
        return dark ? Color.argb(90, 235, 235, 245) : Color.argb(80, 60, 60, 67);
    }

    static int withAlpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ------------------------------------------------------------------ text

    static TextView text(Context c, String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ------------------------------------------------------------------ glass

    /** Frosted card over the soft background: translucent fill, hairline edge, soft shadow. */
    static void frost(View v, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(dark ? Color.argb(60, 255, 255, 255) : Color.argb(150, 255, 255, 255));
        d.setStroke(Math.max(1, dp(0.8f)), dark ? Color.argb(40, 255, 255, 255) : Color.argb(200, 255, 255, 255));
        v.setBackground(d);
        rounded(v, radiusDp);
        v.setElevation(dp(dark ? 2 : 6));
    }

    static void rounded(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
                o.setAlpha(0.25f);
            }
        });
        v.setClipToOutline(true);
    }

    static GradientDrawable fill(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    // ------------------------------------------------------------------ buttons

    /** Sinks a little under the finger and springs back, with a light tap. Keeps the view clickable. */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // returns false: the normal click still happens
    static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                if (!view.isEnabled()) return false;
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.85f).setDuration(110)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                            .setInterpolator(new OvershootInterpolator(2.5f)).start();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) tap(view);
                }
                return false;
            }
        });
    }

    static void tap(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    static void thud(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static void success(View v) {
        v.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS);
    }

    /** Big filled call to action: icon + title on the accent colour. */
    static LinearLayout filledButton(Context c, String title, String icon, View.OnClickListener click) {
        LinearLayout b = row(c, Gravity.CENTER);
        b.setBackground(fill(ACCENT, 16));
        b.setMinimumHeight(dp(56));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.addView(icon(c, icon, Color.WHITE, 20), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(c, title, 17, MEDIUM, Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        b.addView(t, lp);
        b.setElevation(dp(4));
        rounded(b, 16);
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    /** Same shape as filledButton, in glass. */
    static LinearLayout glassButton(Context c, String title, String icon, View.OnClickListener click) {
        LinearLayout b = row(c, Gravity.CENTER);
        frost(b, 16);
        b.setMinimumHeight(dp(56));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.addView(icon(c, icon, label(), 20), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        b.addView(text(c, title, 17, MEDIUM, label()), lp);
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    static LinearLayout row(Context c, int gravity) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static ImageView icon(Context c, String name, int color, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(name, color));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setMinimumWidth(dp(sizeDp));
        v.setMinimumHeight(dp(sizeDp));
        return v;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static FrameLayout.LayoutParams frame(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    // ------------------------------------------------------------------ motion

    static final TimeInterpolator SPRING = new OvershootInterpolator(0.9f);

    /** Springy entrance: fades and lifts views in one after another. */
    static void reveal(List<View> views, long delay) {
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(24));
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay + 60L * i).setDuration(650)
                    .setInterpolator(SPRING).start();
        }
    }

    /** A short message in a glass capsule that slides down and fades away (like a system HUD). */
    static void toast(final FrameLayout overlay, String message, boolean ok) {
        Context c = overlay.getContext();
        final LinearLayout pill = row(c, Gravity.CENTER_VERTICAL);
        pill.setBackground(fill(dark ? Color.argb(235, 44, 44, 48) : Color.argb(240, 255, 255, 255), 22));
        pill.setElevation(dp(10));
        pill.setPadding(dp(14), dp(11), dp(18), dp(11));
        pill.addView(icon(c, ok ? "check.circle" : "alert", ok ? GREEN : ACCENT, 18), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(c, message, 15, MEDIUM, label());
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(8);
        pill.addView(t, lp);
        FrameLayout.LayoutParams flp = frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        flp.topMargin = overlay.getPaddingTop() + dp(56);
        overlay.addView(pill, flp);
        pill.setAlpha(0f);
        pill.setTranslationY(-dp(30));
        pill.setScaleX(0.9f);
        pill.setScaleY(0.9f);
        pill.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(SPRING)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        pill.animate().alpha(0f).translationY(-dp(20)).setStartDelay(2000).setDuration(300)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        overlay.removeView(pill);
                                    }
                                }).start();
                    }
                }).start();
    }

    // ------------------------------------------------------------------ icons

    /** Thin rounded line icons on a 24 x 24 grid, in the spirit of SF Symbols. */
    static final class Icon extends Drawable {
        private final String name;
        private final int color;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path p = new Path();
        private final RectF r = new RectF();

        Icon(String name, int color) {
            this.name = name;
            this.color = color;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);
            solid.setStyle(Paint.Style.FILL);
            solid.setColor(color);
        }

        @Override
        public void draw(Canvas c) {
            float k = Math.min(getBounds().width(), getBounds().height()) / 24f;
            if (k <= 0) return;
            c.save();
            c.translate(getBounds().left + (getBounds().width() - 24 * k) / 2f, getBounds().top + (getBounds().height() - 24 * k) / 2f);
            c.scale(k, k);
            stroke.setStrokeWidth(2f);
            stroke.setColor(color);
            p.reset();
            switch (name) {
                case "back": line(15, 5, 8, 12); line(8, 12, 15, 19); break;
                case "chevron": line(9, 5, 16, 12); line(16, 12, 9, 19); break;
                case "share":
                    line(12, 3, 12, 15); line(8, 7, 12, 3); line(12, 3, 16, 7);
                    p.moveTo(8, 10); p.lineTo(6, 10); p.lineTo(6, 21); p.lineTo(18, 21); p.lineTo(18, 10); p.lineTo(16, 10);
                    break;
                case "undo":
                    line(4, 9, 9, 4); line(4, 9, 9, 14);
                    p.moveTo(4, 9); p.lineTo(14, 9);
                    r.set(9, 9, 20, 20); p.arcTo(r, -90, 180, false); p.lineTo(10, 20);
                    break;
                case "review":
                    for (int i = 0; i < 3; i++) {
                        float y = 6 + 6 * i;
                        line(4, y, 5.5f, y + 1.5f); line(5.5f, y + 1.5f, 8, y - 1);
                        line(11, y + 0.5f, 20, y + 0.5f);
                    }
                    break;
                case "fit":
                    p.moveTo(4, 9); p.lineTo(4, 4); p.lineTo(9, 4);
                    p.moveTo(15, 4); p.lineTo(20, 4); p.lineTo(20, 9);
                    p.moveTo(20, 15); p.lineTo(20, 20); p.lineTo(15, 20);
                    p.moveTo(9, 20); p.lineTo(4, 20); p.lineTo(4, 15);
                    break;
                case "save": line(12, 4, 12, 15); line(7, 10, 12, 15); line(12, 15, 17, 10); line(5, 20, 19, 20); break;
                case "camera":
                    r.set(3, 7, 21, 19.5f); p.addRoundRect(r, 3, 3, Path.Direction.CW);
                    p.moveTo(8.5f, 7); p.lineTo(10, 4.5f); p.lineTo(14, 4.5f); p.lineTo(15.5f, 7);
                    p.addCircle(12, 13.2f, 3.6f, Path.Direction.CW);
                    break;
                case "photos":
                    r.set(3, 5, 21, 19); p.addRoundRect(r, 2.5f, 2.5f, Path.Direction.CW);
                    p.addCircle(8.5f, 9.5f, 1.6f, Path.Direction.CW);
                    p.moveTo(3.5f, 17); p.lineTo(9, 12.5f); p.lineTo(13, 15.5f); p.lineTo(16, 12.5f); p.lineTo(20.5f, 16.5f);
                    break;
                case "clock": p.addCircle(12, 12, 9, Path.Direction.CW); line(12, 7, 12, 12); line(12, 12, 15.5f, 14); break;
                case "check": line(5, 12.5f, 10, 17); line(10, 17, 19, 7); break;
                case "check.circle":
                    c.drawCircle(12, 12, 10, solid);
                    stroke.setColor(Color.WHITE);
                    line(7.5f, 12.5f, 10.5f, 15.5f); line(10.5f, 15.5f, 16.5f, 9);
                    break;
                case "alert":
                    c.drawCircle(12, 12, 10, solid);
                    stroke.setColor(Color.WHITE);
                    line(12, 7, 12, 13); c.drawCircle(12, 16.8f, 1.3f, whiteFill());
                    break;
                case "x": line(6, 6, 18, 18); line(18, 6, 6, 18); break;
                case "trash":
                    line(4, 7, 20, 7); p.moveTo(9, 7); p.lineTo(9, 4); p.lineTo(15, 4); p.lineTo(15, 7);
                    p.moveTo(6, 7); p.lineTo(7, 20); p.lineTo(17, 20); p.lineTo(18, 7);
                    line(10, 11, 10, 16); line(14, 11, 14, 16);
                    break;
                case "bulb":
                    p.addCircle(12, 9.5f, 5.5f, Path.Direction.CW);
                    line(9.5f, 15, 9.5f, 17); line(14.5f, 15, 14.5f, 17); line(9.5f, 17, 14.5f, 17); line(10.5f, 20, 13.5f, 20);
                    break;
                case "grains": // hero glyph: a little heap of round grains
                    float[][] g = {{8, 8}, {16, 8}, {12, 12}, {6, 15}, {18, 15}, {12, 19}, {8.5f, 11.5f}};
                    for (float[] q : g) c.drawCircle(q[0], q[1], 2.6f, solid);
                    break;
                case "spread":
                    p.addCircle(7, 7, 3, Path.Direction.CW); p.addCircle(17, 7, 3, Path.Direction.CW);
                    p.addCircle(7, 17, 3, Path.Direction.CW); p.addCircle(17, 17, 3, Path.Direction.CW);
                    break;
                case "plain": r.set(4, 4, 20, 20); p.addRoundRect(r, 3, 3, Path.Direction.CW); break;
                case "above":
                    p.moveTo(4, 9); p.lineTo(4, 4); p.lineTo(9, 4);
                    p.moveTo(15, 4); p.lineTo(20, 4); p.lineTo(20, 9);
                    p.moveTo(20, 15); p.lineTo(20, 20); p.lineTo(15, 20);
                    p.moveTo(9, 20); p.lineTo(4, 20); p.lineTo(4, 15);
                    c.drawCircle(12, 12, 2.5f, solid);
                    break;
                case "tap": p.addCircle(12, 12, 7, Path.Direction.CW); c.drawCircle(12, 12, 2.5f, solid); break;
                default: c.drawCircle(12, 12, 3, solid);
            }
            c.drawPath(p, stroke);
            c.restore();
        }

        private Paint whiteFill() {
            Paint w = new Paint(Paint.ANTI_ALIAS_FLAG);
            w.setColor(Color.WHITE);
            return w;
        }

        private void line(float x0, float y0, float x1, float y1) {
            p.moveTo(x0, y0);
            p.lineTo(x1, y1);
        }

        @Override
        public void setAlpha(int a) {
            stroke.setAlpha(a);
            solid.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter f) {
            stroke.setColorFilter(f);
            solid.setColorFilter(f);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(24);
        }
    }
}
