package com.dalcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Start screen: take or choose a photo, see recent counts, and learn how to get an exact count. */
final class HomeScreen extends MainActivity.Screen {

    private LinearLayout content;
    private LinearLayout recent;
    private List<View> cards;
    private boolean revealed;

    HomeScreen(MainActivity app) {
        super(app);
    }

    @Override
    View build() {
        Context c = app;
        FrameLayout root = new FrameLayout(c);
        root.addView(new Glass.Mesh(c));
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(c);
        content.setPadding(Ui.dp(20), 0, Ui.dp(20), Ui.dp(32));
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        View header = header(c);
        View hero = hero(c);
        LinearLayout actions = Ui.column(c);
        actions.addView(Ui.filledButton(c, "Take Photo", "camera", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.openCamera();
            }
        }), Ui.matchWidth());
        LinearLayout.LayoutParams lp = Ui.matchWidth();
        lp.topMargin = Ui.dp(12);
        actions.addView(Ui.glassButton(c, "Choose from Photos", "photos", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.pickImage();
            }
        }), lp);
        View recentHeader = recentHeader(c);
        recent = Ui.column(c);
        View tips = tips(c);

        cards = new ArrayList<View>(Arrays.asList(header, hero, actions, recentHeader, recent, tips));
        int[] gaps = {0, 8, 16, 28, 10, 28};
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout.LayoutParams p = Ui.matchWidth();
            p.topMargin = Ui.dp(gaps[i]);
            content.addView(cards.get(i), p);
            cards.get(i).setAlpha(0f);
        }
        onInsets();
        return root;
    }

    @Override
    void onInsets() {
        if (content != null) content.setPadding(Ui.dp(20), app.insetTop + Ui.dp(12), Ui.dp(20), app.insetBottom + Ui.dp(32));
    }

    @Override
    void onShow() {
        reloadRecent();
        if (!revealed) {
            revealed = true;
            Ui.reveal(cards, 80);
        }
    }

    private View header(Context c) {
        LinearLayout row = Ui.row(c, Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(c, "Grain Counter", 34, Ui.BOLD, Ui.label());
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout history = new FrameLayout(c);
        Ui.frost(history, 22);
        ImageView icon = Ui.icon(c, "clock", Ui.ACCENT, 22);
        history.addView(icon, Ui.frame(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        history.setContentDescription("History");
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.push(new HistoryScreen(app));
            }
        });
        Ui.pressable(history);
        row.addView(history, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        return row;
    }

    private View hero(Context c) {
        LinearLayout card = Ui.row(c, Gravity.CENTER_VERTICAL);
        Ui.frost(card, 28);
        card.setPadding(Ui.dp(20), Ui.dp(20), Ui.dp(20), Ui.dp(20));
        FrameLayout badge = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Color.rgb(255, 184, 64), Color.rgb(255, 115, 38)});
        g.setCornerRadius(Ui.dp(18));
        badge.setBackground(g);
        badge.setElevation(Ui.dp(3));
        badge.addView(Ui.icon(c, "grains", Color.WHITE, 30), Ui.frame(Ui.dp(30), Ui.dp(30), Gravity.CENTER));
        card.addView(badge, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        LinearLayout text = Ui.column(c);
        text.addView(Ui.text(c, "Count any grain", 24, Ui.BOLD, Ui.label()));
        TextView body = Ui.text(c, "Dal, rice, wheat, seeds or beans, even mixed. Counted on your phone in a second; nothing is uploaded.",
                14, Ui.REGULAR, Ui.secondary());
        body.setLineSpacing(Ui.dp(2), 1f);
        LinearLayout.LayoutParams bp = Ui.matchWidth();
        bp.topMargin = Ui.dp(6);
        text.addView(body, bp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(16);
        card.addView(text, tp);
        return card;
    }

    private View recentHeader(Context c) {
        LinearLayout row = Ui.row(c, Gravity.BOTTOM);
        row.addView(Ui.text(c, "Recent", 22, Ui.BOLD, Ui.label()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView all = Ui.text(c, "See All", 17, Ui.REGULAR, Ui.ACCENT);
        all.setPadding(Ui.dp(8), Ui.dp(4), 0, Ui.dp(2));
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.push(new HistoryScreen(app));
            }
        });
        Ui.pressable(all);
        row.addView(all);
        return row;
    }

    private View tips(Context c) {
        LinearLayout card = Ui.column(c);
        Ui.frost(card, 24);
        card.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(10));
        card.addView(Ui.text(c, "For an exact count", 17, Ui.MEDIUM, Ui.label()));
        String[][] rows = {
                {"spread", "Spread the grains so they do not touch"},
                {"plain", "Use one plain background that differs from the grains"},
                {"above", "Hold the phone straight above, in even light"},
                {"review", "Review the orange rings: then the count is exact"},
                {"tap", "Tap to add a missed grain, tap a ring to remove one"},
        };
        for (String[] r : rows) {
            LinearLayout row = Ui.row(c, Gravity.CENTER_VERTICAL);
            row.addView(Ui.icon(c, r[0], Ui.ACCENT, 20), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Ui.dp(14);
            row.addView(Ui.text(c, r[1], 14, Ui.REGULAR, Ui.secondary()), lp);
            LinearLayout.LayoutParams rp = Ui.matchWidth();
            rp.topMargin = Ui.dp(12);
            rp.bottomMargin = Ui.dp(2);
            card.addView(row, rp);
        }
        return card;
    }

    private void reloadRecent() {
        recent.removeAllViews();
        List<History.Entry> list = History.get(app).entries();
        if (list.isEmpty()) {
            LinearLayout empty = Ui.row(app, Gravity.CENTER_VERTICAL);
            Ui.frost(empty, 20);
            empty.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(18));
            empty.addView(Ui.icon(app, "clock", Ui.tertiary(), 20), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = Ui.dp(10);
            empty.addView(Ui.text(app, "Your counts will appear here.", 15, Ui.REGULAR, Ui.secondary()), lp);
            recent.addView(empty, Ui.matchWidth());
            return;
        }
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            LinearLayout.LayoutParams lp = Ui.matchWidth();
            if (i > 0) lp.topMargin = Ui.dp(10);
            recent.addView(historyRow(app, list.get(i)), lp);
        }
    }

    /** A saved count as a glass row: thumbnail, number, when, and whether it was fully checked. */
    static View historyRow(final MainActivity a, final History.Entry e) {
        LinearLayout row = Ui.row(a, Gravity.CENTER_VERTICAL);
        Ui.frost(row, 20);
        row.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap t = History.get(a).thumbnail(e.id);
        if (t != null) thumb.setImageBitmap(t);
        thumb.setBackground(Ui.fill(Ui.withAlpha(Ui.GRAY, 0.2f), 12));
        Ui.rounded(thumb, 12);
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
        LinearLayout text = Ui.column(a);
        text.addView(Ui.text(a, e.title(), 19, Ui.MEDIUM, Ui.label()));
        LinearLayout.LayoutParams sp = Ui.wrap();
        sp.topMargin = Ui.dp(4);
        text.addView(Ui.text(a, e.subtitle(a), 13, Ui.REGULAR, Ui.secondary()), sp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(14);
        row.addView(text, tp);
        row.addView(Ui.icon(a, "chevron", Ui.tertiary(), 16), new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.push(new PictureScreen(a, e));
            }
        });
        Ui.pressable(row);
        return row;
    }
}
