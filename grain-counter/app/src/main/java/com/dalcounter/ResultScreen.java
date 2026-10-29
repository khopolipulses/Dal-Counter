package com.dalcounter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.UUID;

/**
 * Counts one photo and lets the user make the count exact: the photo fills the screen under floating glass
 * controls, every doubtful grain is orange, and Review walks through them one by one (keep or remove).
 * The finished count is kept in History when the screen is left.
 */
final class ResultScreen extends MainActivity.Screen {

    private final Uri uri;
    private final float[] savedMarkers;
    private final String historyId;
    private DalCounter.Result result;
    private Uri savedUri;
    private boolean reviewedSome;

    private FrameLayout root;
    private CountView countView;
    private Glass.Panel backButton, shareButton, topCard, tipBanner, toolbar, reviewCard, countingPill;
    private LinearLayout topArea;
    private TextView countText, unitText, statusPill, tipText;
    private LinearLayout undoButton, reviewButton;
    private TextView reviewButtonText;
    private TextView reviewTitle, reviewSubtitle;
    private ProgressBar reviewProgress;
    private Markers.Marker reviewing;
    private int reviewTotal;
    private ValueAnimator countAnim;
    private int lastTop = -1, lastBottom = -1;

    ResultScreen(MainActivity app, Uri uri, float[] savedMarkers, String historyId) {
        super(app);
        this.uri = uri;
        this.savedMarkers = savedMarkers;
        this.historyId = historyId != null ? historyId : UUID.randomUUID().toString();
    }

    @Override
    boolean darkScreen() {
        return true;
    }

    // ------------------------------------------------------------------ layout

    @Override
    View build() {
        final Context c = app;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.CANVAS);
        countView = new CountView(c);
        countView.setListener(new CountView.Listener() {
            @Override
            public void onMarkersChanged() {
                markersChanged();
            }
        });
        root.addView(countView);

        backButton = roundButton(c, "back", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.onBackPressed();
            }
        });
        shareButton = roundButton(c, "share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });

        // top: big count, unit, status pill; tip banner under it
        topCard = panel(c, 26);
        LinearLayout top = Ui.column(c);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(Ui.dp(24), Ui.dp(10), Ui.dp(24), Ui.dp(12));
        countText = Ui.text(c, "–", 44, Ui.BOLD, Color.WHITE);
        unitText = Ui.text(c, "Counting…", 13, Ui.MEDIUM, Color.argb(180, 255, 255, 255));
        statusPill = Ui.text(c, "", 13, Ui.MEDIUM, Color.WHITE);
        statusPill.setPadding(Ui.dp(10), Ui.dp(5), Ui.dp(12), Ui.dp(5));
        statusPill.setCompoundDrawablePadding(Ui.dp(5));
        statusPill.setVisibility(View.GONE);
        statusPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusTapped();
            }
        });
        Ui.pressable(statusPill);
        top.addView(countText);
        LinearLayout.LayoutParams up = Ui.wrap();
        up.topMargin = Ui.dp(4);
        top.addView(unitText, up);
        LinearLayout.LayoutParams pp = Ui.wrap();
        pp.topMargin = Ui.dp(8);
        top.addView(statusPill, pp);
        topCard.addView(top);

        tipBanner = panel(c, 18);
        LinearLayout tipRow = Ui.row(c, Gravity.CENTER_VERTICAL);
        tipRow.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        tipRow.addView(Ui.icon(c, "bulb", Ui.YELLOW, 18), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        tipText = Ui.text(c, "", 13, Ui.MEDIUM, Color.WHITE);
        tipText.setLineSpacing(Ui.dp(1), 1f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(10);
        tipRow.addView(tipText, tl);
        View close = Ui.icon(c, "x", Color.argb(170, 255, 255, 255), 16);
        close.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTip(false);
            }
        });
        tipRow.addView(close, new LinearLayout.LayoutParams(Ui.dp(30), Ui.dp(30)));
        tipBanner.addView(tipRow);
        tipBanner.setAlpha(0f);
        tipBanner.setVisibility(View.GONE);

        topArea = Ui.column(c);
        topArea.setGravity(Gravity.CENTER_HORIZONTAL);
        topArea.addView(topCard, Ui.wrap());
        LinearLayout.LayoutParams bl = Ui.matchWidth();
        bl.topMargin = Ui.dp(10);
        bl.leftMargin = Ui.dp(16);
        bl.rightMargin = Ui.dp(16);
        topArea.addView(tipBanner, bl);
        root.addView(topArea, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(backButton, Ui.frame(Ui.dp(44), Ui.dp(44), Gravity.TOP | Gravity.START));
        root.addView(shareButton, Ui.frame(Ui.dp(44), Ui.dp(44), Gravity.TOP | Gravity.END));

        // bottom toolbar
        toolbar = panel(c, 30);
        LinearLayout tools = Ui.row(c, Gravity.CENTER);
        tools.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(4));
        undoButton = tool(c, "Undo", "undo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                countView.undo();
            }
        });
        reviewButton = tool(c, "Review", "review", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startReview();
            }
        });
        reviewButtonText = (TextView) reviewButton.getChildAt(1);
        View fit = tool(c, "Fit", "fit", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                countView.resetFit(true);
            }
        });
        View save = tool(c, "Save", "save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToGallery();
            }
        });
        for (View t : new View[] {undoButton, reviewButton, fit, save}) {
            tools.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        toolbar.addView(tools);
        toolbar.setAlpha(0f);
        root.addView(toolbar, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        reviewCard = buildReviewCard(c);
        root.addView(reviewCard, Ui.frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        countingPill = panel(c, 24);
        LinearLayout cr = Ui.row(c, Gravity.CENTER_VERTICAL);
        cr.setPadding(Ui.dp(18), Ui.dp(13), Ui.dp(20), Ui.dp(13));
        ProgressBar spin = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        cr.addView(spin, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        LinearLayout.LayoutParams cl = Ui.wrap();
        cl.leftMargin = Ui.dp(10);
        cr.addView(Ui.text(c, "Counting grains…", 15, Ui.MEDIUM, Color.WHITE), cl);
        countingPill.addView(cr);
        root.addView(countingPill, Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                updateFitInsets();
            }
        });
        onInsets();
        load();
        return root;
    }

    @Override
    void onInsets() {
        if (root == null) return;
        int top = app.insetTop, bottom = app.insetBottom;
        ((FrameLayout.LayoutParams) topArea.getLayoutParams()).topMargin = top + Ui.dp(4);
        for (View b : new View[] {backButton, shareButton}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
            lp.topMargin = top + Ui.dp(8);
            lp.leftMargin = Ui.dp(16);
            lp.rightMargin = Ui.dp(16);
        }
        for (View b : new View[] {toolbar, reviewCard}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
            lp.bottomMargin = bottom + Ui.dp(10);
            lp.leftMargin = Ui.dp(14);
            lp.rightMargin = Ui.dp(14);
        }
        ((FrameLayout.LayoutParams) countingPill.getLayoutParams()).bottomMargin = bottom + Ui.dp(28);
        root.requestLayout();
    }

    /** The photo fits between the floating card at the top and the toolbar at the bottom. */
    private void updateFitInsets() {
        int top = topArea.getTop() + topCard.getBottom() + Ui.dp(8);
        int bottom = root.getHeight() - toolbar.getTop() + Ui.dp(8);
        if (top == lastTop && bottom == lastBottom) return;
        lastTop = top;
        lastBottom = bottom;
        if (reviewing != null) return;
        countView.setFitInsets(Ui.dp(8), top, Ui.dp(8), bottom);
        countView.resetFit(false);
    }

    private Glass.Panel panel(Context c, float radius) {
        Glass.Panel p = new Glass.Panel(c, countView, radius);
        countView.addFollower(p);
        return p;
    }

    private Glass.Panel roundButton(Context c, String icon, View.OnClickListener click) {
        Glass.Panel p = panel(c, 22);
        p.addView(Ui.icon(c, icon, Color.WHITE, 20), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        p.setOnClickListener(click);
        Ui.pressable(p);
        return p;
    }

    private static LinearLayout tool(Context c, String title, String icon, View.OnClickListener click) {
        LinearLayout t = Ui.column(c);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        t.addView(Ui.icon(c, icon, Color.WHITE, 22), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView label = Ui.text(c, title, 11, Ui.MEDIUM, Color.WHITE);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.topMargin = Ui.dp(4);
        t.addView(label, lp);
        t.setOnClickListener(click);
        Ui.pressable(t);
        return t;
    }

    private Glass.Panel buildReviewCard(Context c) {
        Glass.Panel card = panel(c, 28);
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(8));

        LinearLayout header = Ui.row(c, Gravity.CENTER_VERTICAL);
        reviewTitle = Ui.text(c, "", 20, Ui.BOLD, Color.WHITE);
        header.addView(reviewTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = Ui.text(c, "Done", 17, Ui.MEDIUM, Ui.ACCENT);
        done.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endReview();
            }
        });
        Ui.pressable(done);
        header.addView(done);
        col.addView(header);

        reviewSubtitle = Ui.text(c, "", 14, Ui.REGULAR, Color.argb(190, 255, 255, 255));
        reviewSubtitle.setLineSpacing(Ui.dp(2), 1f);
        LinearLayout.LayoutParams sp = Ui.matchWidth();
        sp.topMargin = Ui.dp(8);
        col.addView(reviewSubtitle, sp);

        reviewProgress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        reviewProgress.setMax(1000);
        reviewProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        reviewProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(6));
        pl.topMargin = Ui.dp(12);
        col.addView(reviewProgress, pl);

        LinearLayout answers = Ui.row(c, Gravity.CENTER);
        View remove = answer(c, "Not a grain", "trash", Color.argb(40, 255, 255, 255), Ui.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.thud(v);
                reviewAnswer(false);
            }
        });
        View keep = answer(c, "Yes, a grain", "check", Ui.ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reviewAnswer(true);
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, Ui.dp(50), 1f);
        answers.addView(remove, al);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, Ui.dp(50), 1f);
        kl.leftMargin = Ui.dp(10);
        answers.addView(keep, kl);
        LinearLayout.LayoutParams ap = Ui.matchWidth();
        ap.topMargin = Ui.dp(16);
        col.addView(answers, ap);

        TextView skip = Ui.text(c, "Skip for now", 15, Ui.REGULAR, Color.argb(180, 255, 255, 255));
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, Ui.dp(12), 0, Ui.dp(8));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reviewSkip();
            }
        });
        Ui.pressable(skip);
        col.addView(skip, Ui.matchWidth());
        card.addView(col);
        card.setVisibility(View.GONE);
        return card;
    }

    private static View answer(Context c, String title, String icon, int bg, int fg, View.OnClickListener click) {
        LinearLayout b = Ui.row(c, Gravity.CENTER);
        b.setBackground(Ui.fill(bg, 14));
        b.addView(Ui.icon(c, icon, fg, 18), new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.leftMargin = Ui.dp(8);
        b.addView(Ui.text(c, title, 16, Ui.MEDIUM, fg), lp);
        b.setOnClickListener(click);
        Ui.pressable(b);
        return b;
    }

    // ------------------------------------------------------------------ counting

    private void load() {
        final long started = SystemClock.uptimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bmp = app.decode(uri);
                    final int w = bmp.getWidth(), h = bmp.getHeight();
                    final int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    app.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            countView.showPhoto(bmp);
                            countView.setScanning(true);
                        }
                    });
                    final DalCounter.Result res = DalCounter.count(px, w, h);
                    long wait = Math.max(0, 900 - (SystemClock.uptimeMillis() - started)); // let the sweep be seen
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            show(bmp, res);
                        }
                    }, wait);
                } catch (Throwable t) { // includes OutOfMemoryError on huge photos
                    app.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            countingPill.setVisibility(View.GONE);
                            unitText.setText("Could not read that photo");
                            app.toast("Could not read that photo", false);
                        }
                    });
                }
            }
        }).start();
    }

    private void show(Bitmap bmp, DalCounter.Result res) {
        if (app.isDestroyed()) return;
        result = res;
        countView.setScanning(false);
        countView.setData(bmp, res);
        if (savedMarkers != null) countView.restoreMarkers(savedMarkers);
        countingPill.animate().alpha(0f).setDuration(250).start();
        toolbar.setTranslationY(Ui.dp(30));
        toolbar.animate().alpha(1f).translationY(0).setStartDelay(150).setDuration(600).setInterpolator(Ui.SPRING).start();
        statusPill.setVisibility(View.VISIBLE);
        animateCount(countView.getCount());
        updateStatus();
        if (res.photoTip() != null) {
            tipText.setText(res.photoTip());
            setTip(true);
        }
        if (res.count() > 0) Ui.success(root);
    }

    /** The big number counts up to the result. */
    private void animateCount(final int n) {
        if (countAnim != null) countAnim.cancel();
        countAnim = ValueAnimator.ofInt(0, n);
        countAnim.setDuration(n == 0 ? 0 : 800);
        countAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        countAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                countText.setText(String.valueOf(a.getAnimatedValue()));
            }
        });
        countAnim.start();
    }

    // ------------------------------------------------------------------ status

    private void markersChanged() {
        if (result == null) return;
        savedUri = null; // the picture changed, so the next save/share must render it again
        if (countAnim == null || !countAnim.isRunning()) {
            countText.setText(String.valueOf(countView.getCount()));
            countText.setScaleX(1.12f);
            countText.setScaleY(1.12f);
            countText.animate().scaleX(1f).scaleY(1f).setDuration(400).setInterpolator(new OvershootInterpolator(3f)).start();
        }
        updateStatus();
    }

    private void updateStatus() {
        if (result == null) return;
        Markers m = countView.markers();
        int n = m.size(), toCheck = m.countKind(Markers.UNCERTAIN);
        String unit = n == 1 ? "grain" : "grains";
        if (result.ignoredCount > 0) unit += " · " + result.ignoredCount + " print marks ignored";
        unitText.setText(unit);
        undoButton.setEnabled(m.canUndo());
        undoButton.setAlpha(m.canUndo() ? 1f : 0.35f);
        reviewButton.setEnabled(toCheck > 0);
        reviewButton.setAlpha(toCheck > 0 ? 1f : 0.35f);
        reviewButtonText.setText(toCheck > 0 ? "Review " + toCheck : "Review");

        String text, icon;
        int color;
        if (n == 0) {
            text = "No grains found"; icon = "alert"; color = Ui.GRAY;
        } else if (toCheck > 0) {
            text = toCheck + " to check"; icon = "alert"; color = Ui.ACCENT;
        } else if (reviewedSome) {
            text = "Checked by you"; icon = "check.circle"; color = Ui.GREEN;
        } else if (result.photoTip() != null) {
            text = "Photo could be better"; icon = "bulb"; color = Ui.YELLOW;
        } else {
            text = "Looks exact"; icon = "check.circle"; color = Ui.GREEN;
        }
        statusPill.setText(text);
        statusPill.setTextColor(color);
        statusPill.setBackground(Ui.fill(Ui.withAlpha(color, 0.22f), 14));
        Drawable d = new Ui.Icon(icon, color);
        d.setBounds(0, 0, Ui.dp(14), Ui.dp(14));
        statusPill.setCompoundDrawables(d, null, null, null);
    }

    private void statusTapped() {
        if (result == null) return;
        if (countView.markers().countKind(Markers.UNCERTAIN) > 0) startReview();
        else if (result.photoTip() != null) setTip(tipBanner.getVisibility() != View.VISIBLE);
    }

    private void setTip(final boolean visible) {
        if (visible) {
            tipBanner.setVisibility(View.VISIBLE);
            tipBanner.setTranslationY(-Ui.dp(12));
            tipBanner.animate().alpha(1f).translationY(0).setDuration(450).setInterpolator(Ui.SPRING).start();
        } else {
            tipBanner.animate().alpha(0f).translationY(-Ui.dp(12)).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    tipBanner.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    // ------------------------------------------------------------------ review

    private void startReview() {
        Markers m = countView.markers();
        int first = m.next(Markers.UNCERTAIN, 0);
        if (first < 0) {
            app.toast("Nothing left to check", true);
            return;
        }
        reviewTotal = m.countKind(Markers.UNCERTAIN);
        if (tipBanner.getVisibility() == View.VISIBLE) setTip(false);
        reviewCard.setVisibility(View.VISIBLE);
        reviewCard.setAlpha(0f);
        reviewCard.setTranslationY(Ui.dp(40));
        reviewCard.animate().alpha(1f).translationY(0).setDuration(500).setInterpolator(Ui.SPRING).start();
        toolbar.animate().alpha(0f).translationY(Ui.dp(30)).setDuration(250).start();
        final Markers.Marker mk = m.get(first);
        reviewCard.post(new Runnable() {
            @Override
            public void run() {
                // While reviewing, the free area above the card is where the grain is shown.
                countView.setFitInsets(Ui.dp(8), lastTop, Ui.dp(8), root.getHeight() - reviewCard.getTop() + Ui.dp(12));
                goTo(mk);
            }
        });
        reviewing = mk;
    }

    private void goTo(Markers.Marker mk) {
        Markers m = countView.markers();
        reviewing = mk;
        countView.setHighlighted(mk);
        countView.focusOn(mk);
        int left = m.countKind(Markers.UNCERTAIN);
        reviewTitle.setText("Grain " + (m.indexOf(mk) + 1));
        reviewSubtitle.setText("Is this exactly one grain? " + left + " left to check. Missed one nearby? Tap the empty spot to add it.");
        reviewProgress.setProgress(Math.round(1000f * (reviewTotal - left) / Math.max(1, reviewTotal)), true);
    }

    private void reviewAnswer(boolean keep) {
        if (reviewing == null) return;
        Markers m = countView.markers();
        int from = Math.max(0, m.indexOf(reviewing));
        if (keep) countView.confirm(reviewing);
        else countView.remove(reviewing);
        reviewedSome = true;
        updateStatus();
        advance(keep ? from + 1 : from);
    }

    private void reviewSkip() {
        if (reviewing == null) return;
        Markers m = countView.markers();
        int next = m.next(Markers.UNCERTAIN, m.indexOf(reviewing) + 1);
        if (next >= 0 && m.get(next) != reviewing) goTo(m.get(next));
        else endReview();
    }

    private void advance(int from) {
        Markers m = countView.markers();
        int next = m.next(Markers.UNCERTAIN, from);
        if (next >= 0) {
            goTo(m.get(next));
        } else {
            reviewProgress.setProgress(1000, true);
            Ui.success(root);
            app.toast("All grains checked: " + m.size() + " is exact", true);
            endReview();
        }
    }

    private void endReview() {
        reviewing = null;
        countView.setHighlighted(null);
        countView.setFitInsets(Ui.dp(8), lastTop, Ui.dp(8), lastBottom);
        countView.resetFit(true);
        toolbar.animate().alpha(1f).translationY(0).setDuration(450).setInterpolator(Ui.SPRING).start();
        reviewCard.animate().alpha(0f).translationY(Ui.dp(40)).setDuration(300).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (reviewing == null) reviewCard.setVisibility(View.GONE);
            }
        }).start();
        updateStatus();
    }

    @Override
    boolean onBack() {
        if (reviewing != null) {
            endReview();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ output

    @Override
    void onHide() {
        keepInHistory();
    }

    @Override
    void save(Bundle out) {
        if (result == null || !countView.hasData()) return;
        out.putString("src", uri.toString());
        out.putFloatArray("mk", countView.exportMarkers());
        out.putString("hid", historyId);
    }

    private void keepInHistory() {
        if (result == null || !countView.hasData()) return;
        Bitmap picture = countView.renderExport();
        if (picture == null) return;
        Markers m = countView.markers();
        History.get(app).save(new History.Entry(historyId, System.currentTimeMillis(), m.size(),
                m.countKind(Markers.UNCERTAIN), result.ignoredCount), picture);
    }

    private Uri render() throws Exception {
        if (savedUri == null) {
            Bitmap out = countView.renderExport();
            if (out == null) throw new Exception("render failed");
            savedUri = app.writeToGallery(out, "grain_count_" + countView.getCount() + "_" + System.currentTimeMillis(), true);
        }
        return savedUri;
    }

    private void saveToGallery() {
        if (result == null) return;
        boolean already = savedUri != null;
        try {
            render();
            Ui.success(root);
            app.toast(already ? "Already in Pictures/GrainCounter" : "Saved to Pictures/GrainCounter", true);
        } catch (Throwable t) {
            savedUri = null;
            app.toast("Could not save the picture", false);
        }
    }

    private void share() {
        if (result == null) return;
        try {
            app.share(render(), "Grain count: " + countView.getCount());
        } catch (Throwable t) {
            savedUri = null;
            app.toast("Could not share the picture", false);
        }
    }
}
