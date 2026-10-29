package com.dalcounter;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Single-screen grain counter: take, pick or share a photo, count the grains on the phone (no internet),
 * fix any miss by tapping, then save or share the numbered picture.
 */
public class MainActivity extends Activity implements CountView.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_CAMERA = 2;
    /** Photos are processed at this long-side size: fast on a phone and as accurate as full size. */
    private static final int MAX_SIDE = 1280;

    private CountView view;
    private TextView total;
    private TextView hintView;
    private Button undoButton;

    private Uri sourceUri;
    private Uri cameraUri;
    private Uri savedUri;
    private String hint;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();

        if (state != null) {
            String cam = state.getString("cam");
            if (cam != null) cameraUri = Uri.parse(cam); // the camera app may still deliver a result
            String src = state.getString("src");
            if (src != null) {
                load(Uri.parse(src), state.getFloatArray("mk"), state.getFloat("rad", 12f),
                        state.getFloat("band", 25f), state.getString("hint"));
            }
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putString("cam", cameraUri.toString());
        if (sourceUri != null && view.hasData()) {
            out.putString("src", sourceUri.toString());
            out.putFloatArray("mk", view.exportMarkers());
            out.putFloat("rad", view.getRadius());
            out.putFloat("band", view.getBand());
            if (hint != null) out.putString("hint", hint);
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(32, 32, 32));

        LinearLayout top = row();
        top.addView(button("Gallery", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        }));
        top.addView(button("Camera", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCamera();
            }
        }));
        root.addView(top);

        total = new TextView(this);
        total.setTextColor(Color.WHITE);
        total.setTextSize(28);
        total.setTypeface(Typeface.DEFAULT_BOLD);
        total.setGravity(Gravity.CENTER);
        total.setText("Grain Counter");
        total.setPadding(0, dp(4), 0, dp(2));
        root.addView(total, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        hintView = new TextView(this);
        hintView.setTextSize(13);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(dp(10), 0, dp(10), dp(4));
        hintView.setVisibility(View.GONE);
        root.addView(hintView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        view = new CountView(this);
        view.setListener(this);
        root.addView(view, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tip = new TextView(this);
        tip.setTextColor(Color.LTGRAY);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setText("Pinch to zoom. Tap an empty spot to add a missed grain, tap a circle to remove it.");
        tip.setPadding(dp(8), dp(4), dp(8), 0);
        root.addView(tip);

        LinearLayout bottom = row();
        undoButton = button("Undo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.undo();
            }
        });
        undoButton.setEnabled(false);
        bottom.addView(undoButton);
        bottom.addView(button("Fit", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.resetFit();
            }
        }));
        bottom.addView(button("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        }));
        bottom.addView(button("Share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        }));
        root.addView(bottom);

        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(6), dp(6), dp(6), dp(6));
        return l;
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onMarkersChanged(int count, boolean canUndo) {
        total.setText("Count: " + count);
        undoButton.setEnabled(canUndo);
        savedUri = null; // the picture changed, so the next save/share must render it again
    }

    private void showHint(String text) {
        hint = text;
        if (text == null) {
            hintView.setText("Clean photo: this count should be exact.");
            hintView.setTextColor(Color.rgb(120, 220, 140));
        } else {
            hintView.setText(text);
            hintView.setTextColor(Color.rgb(255, 190, 80));
        }
        hintView.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ input

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent in) {
        if (in == null) return;
        String type = in.getType();
        if (Intent.ACTION_SEND.equals(in.getAction()) && type != null && type.startsWith("image/")) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); // photo shared to this app from the gallery
            if (u != null) load(u, null, 0f, 0f, null);
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose a photo"), REQ_PICK);
        } catch (Exception e) {
            toast("No gallery app found");
        }
    }

    private void openCamera() {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "grains_" + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCounter");
        cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (cameraUri == null) {
            toast("Cannot create the photo file");
            return;
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            getContentResolver().delete(cameraUri, null, null);
            cameraUri = null;
            toast("No camera app found");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PICK && result == RESULT_OK && data != null) {
            load(data.getData(), null, 0f, 0f, null);
        } else if (req == REQ_CAMERA) {
            if (result == RESULT_OK && cameraUri != null) {
                load(cameraUri, null, 0f, 0f, null);
            } else if (cameraUri != null) {
                getContentResolver().delete(cameraUri, null, null); // cancelled: drop the empty entry
                cameraUri = null;
            }
        }
    }

    // ------------------------------------------------------------------ counting

    /** Decodes and counts off the UI thread. With saved markers it only decodes (state restore). */
    private void load(final Uri uri, final float[] savedMarkers, final float savedRadius,
                      final float savedBand, final String savedHint) {
        if (uri == null || busy) return;
        busy = true;
        sourceUri = uri;
        total.setText("Counting...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bmp = decode(uri);
                    final DalCounter.Result res;
                    if (savedMarkers == null) {
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        int[] px = new int[w * h];
                        bmp.getPixels(px, 0, w, 0, 0, w, h);
                        res = DalCounter.count(px, w, h);
                    } else {
                        res = null;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            if (isDestroyed() || isFinishing()) return;
                            if (res != null) {
                                view.setData(bmp, res);
                                showHint(res.hint());
                            } else {
                                view.setRestored(bmp, savedRadius, savedBand, savedMarkers);
                                showHint(savedHint);
                            }
                        }
                    });
                } catch (Throwable t) { // includes OutOfMemoryError on huge photos
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            total.setText("Could not read that image");
                        }
                    });
                }
            }
        }).start();
    }

    /** Decodes with EXIF rotation applied and the long side limited to MAX_SIDE. */
    private Bitmap decode(Uri uri) throws IOException {
        ContentResolver cr = getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            if (in != null) in.close();
        }
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap b;
        in = cr.openInputStream(uri);
        try {
            b = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            if (in != null) in.close();
        }
        if (b == null) throw new IOException("decode failed");

        int rotation = 0;
        in = cr.openInputStream(uri);
        try {
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {
            // no EXIF: assume upright
        } finally {
            if (in != null) in.close();
        }

        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight()));
        if (rotation != 0 || scale < 1f) {
            Matrix mx = new Matrix();
            mx.postRotate(rotation);
            mx.postScale(scale, scale);
            Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
            if (t != b) b.recycle();
            b = t;
        }
        return b;
    }

    // ------------------------------------------------------------------ output

    private void save(boolean share) {
        if (!view.hasData()) {
            toast("Load a photo first");
            return;
        }
        try {
            if (savedUri == null) {
                Bitmap out = view.renderExport();
                if (out == null) throw new IOException("render failed");
                savedUri = writePng(out);
            }
            if (share) {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("image/png");
                s.putExtra(Intent.EXTRA_STREAM, savedUri);
                s.putExtra(Intent.EXTRA_TEXT, "Grain count: " + view.getCount());
                s.setClipData(ClipData.newRawUri("Counted image", savedUri));
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(s, "Share counted image"));
            } else {
                toast("Saved to Pictures/GrainCounter");
            }
        } catch (Throwable t) {
            savedUri = null;
            toast("Could not save the image");
        }
    }

    private Uri writePng(Bitmap bmp) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "grain_count_" + view.getCount() + "_" + System.currentTimeMillis() + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCounter");
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("insert failed");
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IOException("write failed");
        } catch (IOException e) {
            cr.delete(uri, null, null); // do not leave a broken file in the gallery
            throw e;
        } finally {
            if (os != null) os.close();
        }
        return uri;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
