package com.dalcounter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Every saved count, newest first. Tap to open, long-press to delete. */
final class HistoryScreen extends MainActivity.Screen {

    private LinearLayout content, list;

    HistoryScreen(MainActivity app) {
        super(app);
    }

    @Override
    View build() {
        Context c = app;
        FrameLayout root = new FrameLayout(c);
        root.addView(new Glass.Mesh(c));
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        content = Ui.column(c);
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        FrameLayout back = new FrameLayout(c);
        Ui.frost(back, 22);
        back.addView(Ui.icon(c, "back", Ui.ACCENT, 20), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.onBackPressed();
            }
        });
        Ui.pressable(back);
        content.addView(back, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout.LayoutParams tp = Ui.matchWidth();
        tp.topMargin = Ui.dp(12);
        content.addView(Ui.text(c, "History", 34, Ui.BOLD, Ui.label()), tp);
        LinearLayout.LayoutParams sp = Ui.matchWidth();
        sp.topMargin = Ui.dp(6);
        content.addView(Ui.text(c, "Kept only on this phone. Long-press a count to delete it.", 14, Ui.REGULAR, Ui.secondary()), sp);
        list = Ui.column(c);
        LinearLayout.LayoutParams lp = Ui.matchWidth();
        lp.topMargin = Ui.dp(20);
        content.addView(list, lp);
        onInsets();
        return root;
    }

    @Override
    void onInsets() {
        if (content != null) content.setPadding(Ui.dp(20), app.insetTop + Ui.dp(8), Ui.dp(20), app.insetBottom + Ui.dp(32));
    }

    @Override
    void onShow() {
        reload();
    }

    private void reload() {
        list.removeAllViews();
        List<History.Entry> entries = new ArrayList<History.Entry>(History.get(app).entries());
        if (entries.isEmpty()) {
            TextView empty = Ui.text(app, "No counts yet.", 17, Ui.REGULAR, Ui.secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(80), 0, 0);
            list.addView(empty, Ui.matchWidth());
            return;
        }
        List<View> shown = new ArrayList<View>();
        for (int i = 0; i < entries.size(); i++) {
            final History.Entry e = entries.get(i);
            View row = HomeScreen.historyRow(app, e);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Ui.thud(v);
                    confirmDelete(e);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = Ui.matchWidth();
            if (i > 0) lp.topMargin = Ui.dp(10);
            list.addView(row, lp);
            if (i < 8) shown.add(row);
        }
        Ui.reveal(shown, 0);
    }

    private void confirmDelete(final History.Entry e) {
        new AlertDialog.Builder(app, Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Delete this count?")
                .setMessage(e.title() + " · " + e.subtitle(app))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        History.get(app).delete(e.id);
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
