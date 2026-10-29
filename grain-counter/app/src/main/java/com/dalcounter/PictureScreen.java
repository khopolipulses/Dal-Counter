package com.dalcounter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One saved count: the numbered picture, zoomable, with share and delete on glass. */
final class PictureScreen extends MainActivity.Screen {

    private final History.Entry entry;
    private FrameLayout root;
    private View back, actions, caption;
    private Uri shared;

    PictureScreen(MainActivity app, History.Entry entry) {
        super(app);
        this.entry = entry;
    }

    @Override
    boolean darkScreen() {
        return true;
    }

    @Override
    View build() {
        Context c = app;
        root = new FrameLayout(c);
        root.setBackgroundColor(Ui.CANVAS);
        CountView picture = new CountView(c);
        Bitmap b = BitmapFactory.decodeFile(History.get(app).image(entry.id).getPath());
        if (b != null) picture.showPicture(b);
        root.addView(picture);

        Glass.Panel backPanel = new Glass.Panel(c, picture, 22);
        picture.addFollower(backPanel);
        backPanel.addView(Ui.icon(c, "back", Color.WHITE, 20), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        backPanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.onBackPressed();
            }
        });
        Ui.pressable(backPanel);
        back = backPanel;
        root.addView(back, Ui.frame(Ui.dp(44), Ui.dp(44), Gravity.TOP | Gravity.START));

        Glass.Panel bar = new Glass.Panel(c, picture, 22);
        picture.addFollower(bar);
        LinearLayout row = Ui.row(c, Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        row.addView(button(c, "share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        row.addView(button(c, "trash", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        bar.addView(row);
        actions = bar;
        root.addView(actions, Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(44), Gravity.TOP | Gravity.END));

        Glass.Panel pill = new Glass.Panel(c, picture, 18);
        picture.addFollower(pill);
        LinearLayout text = Ui.column(c);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(9));
        text.addView(Ui.text(c, entry.title(), 17, Ui.BOLD, Color.WHITE));
        TextView sub = Ui.text(c, entry.subtitle(c), 12, Ui.REGULAR, Color.argb(180, 255, 255, 255));
        LinearLayout.LayoutParams sp = Ui.wrap();
        sp.topMargin = Ui.dp(3);
        text.addView(sub, sp);
        pill.addView(text);
        caption = pill;
        root.addView(caption, Ui.frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        onInsets();
        return root;
    }

    private static View button(Context c, String icon, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(c);
        f.addView(Ui.icon(c, icon, Color.WHITE, 20), Ui.frame(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        f.setOnClickListener(click);
        Ui.pressable(f);
        return f;
    }

    @Override
    void onInsets() {
        if (root == null) return;
        FrameLayout.LayoutParams b = (FrameLayout.LayoutParams) back.getLayoutParams();
        b.topMargin = app.insetTop + Ui.dp(8);
        b.leftMargin = Ui.dp(16);
        FrameLayout.LayoutParams a = (FrameLayout.LayoutParams) actions.getLayoutParams();
        a.topMargin = app.insetTop + Ui.dp(8);
        a.rightMargin = Ui.dp(16);
        ((FrameLayout.LayoutParams) caption.getLayoutParams()).bottomMargin = app.insetBottom + Ui.dp(16);
        root.requestLayout();
    }

    private void share() {
        try {
            if (shared == null) {
                Bitmap b = BitmapFactory.decodeFile(History.get(app).image(entry.id).getPath());
                if (b == null) throw new Exception("missing");
                shared = app.writeToGallery(b, "grain_count_" + entry.count + "_" + entry.date, false);
            }
            app.share(shared, "Grain count: " + entry.count);
        } catch (Throwable t) {
            app.toast("Could not share the picture", false);
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(app, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete this count?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        History.get(app).delete(entry.id);
                        app.onBackPressed();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
