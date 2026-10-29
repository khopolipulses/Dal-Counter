package com.dalcounter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Hosts the screens (Home, Result, History, picture) in a navigation stack with iOS-style push and pop,
 * and does the work that needs the activity: picking, the camera, saving and sharing.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_CAMERA_PERMISSION = 3;
    /** Photos are processed at this long-side size: fast on a phone and as accurate as full size. */
    static final int MAX_SIDE = 1280;

    /** One screen in the stack. */
    abstract static class Screen {
        final MainActivity app;
        View view;

        Screen(MainActivity app) {
            this.app = app;
        }

        abstract View build();

        void onShow() {}

        void onHide() {}

        /** Handles Back itself (e.g. leaves review mode); false = pop the screen. */
        boolean onBack() {
            return false;
        }

        /** True when the screen is dark (photo), so the status bar icons must be light. */
        boolean darkScreen() {
            return Ui.dark;
        }

        void onInsets() {}

        void save(Bundle out) {}
    }

    private FrameLayout container;
    FrameLayout overlay;
    private final ArrayList<Screen> stack = new ArrayList<Screen>();
    int insetTop, insetBottom;
    private boolean animating;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        edgeToEdge();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.dark ? Color.BLACK : Color.rgb(250, 240, 230));
        container = new FrameLayout(this);
        overlay = new FrameLayout(this);
        root.addView(container);
        root.addView(overlay);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
                overlay.setPadding(0, insetTop, 0, 0);
                for (Screen s : stack) s.onInsets();
                return in;
            }
        });
        setContentView(root);

        push(new HomeScreen(this), false);
        if (state != null) {
            String src = state.getString("src");
            if (src != null) {
                push(new ResultScreen(this, Uri.parse(src), state.getFloatArray("mk"), state.getString("hid")), false);
            }
        } else {
            handleIntent(getIntent());
        }
    }

    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Dark icons on light screens, light icons over photos. */
    @SuppressWarnings("deprecation")
    private void statusIcons(boolean darkScreen) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(darkScreen ? 0 : light, light);
            }
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            f = darkScreen ? f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            d.setSystemUiVisibility(f);
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
        Screen top = top();
        if (top != null) top.save(out);
    }

    // ------------------------------------------------------------------ navigation

    Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.25f, 1f);

    void push(Screen s) {
        push(s, true);
    }

    private void push(final Screen s, boolean animated) {
        if (animating) return;
        final Screen below = top();
        s.view = s.build();
        container.addView(s.view);
        stack.add(s);
        statusIcons(s.darkScreen());
        s.onShow();
        if (below == null) return;
        below.onHide();
        if (!animated) {
            below.view.setVisibility(View.INVISIBLE);
            return;
        }
        animating = true;
        int w = container.getWidth() > 0 ? container.getWidth() : getResources().getDisplayMetrics().widthPixels;
        s.view.setTranslationX(w);
        s.view.setElevation(Ui.dp(16));
        s.view.animate().translationX(0).setDuration(420).setInterpolator(EASE).withEndAction(new Runnable() {
            @Override
            public void run() {
                below.view.setVisibility(View.INVISIBLE);
                below.view.setTranslationX(0);
                below.view.setAlpha(1f);
                s.view.setElevation(0);
                animating = false;
            }
        }).start();
        below.view.animate().translationX(-w * 0.3f).alpha(0.6f).setDuration(420).setInterpolator(EASE).start();
    }

    void pop() {
        if (stack.size() < 2 || animating) return;
        animating = true;
        final Screen gone = stack.remove(stack.size() - 1);
        final Screen back = top();
        gone.onHide();
        int w = container.getWidth();
        back.view.setVisibility(View.VISIBLE);
        back.view.setTranslationX(-w * 0.3f);
        back.view.setAlpha(0.6f);
        back.view.animate().translationX(0).alpha(1f).setDuration(380).setInterpolator(EASE).start();
        gone.view.setElevation(Ui.dp(16));
        gone.view.animate().translationX(w).setDuration(380).setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        container.removeView(gone.view);
                        animating = false;
                    }
                }).start();
        statusIcons(back.darkScreen());
        back.onShow();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        Screen t = top();
        if (t != null && t.onBack()) return;
        if (stack.size() > 1) pop();
        else super.onBackPressed();
    }

    void toast(String message, boolean ok) {
        Ui.toast(overlay, message, ok);
    }

    // ------------------------------------------------------------------ input

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent in) {
        if (in == null) return;
        String type = in.getType();
        if (Intent.ACTION_SEND.equals(in.getAction()) && type != null && type.startsWith("image/")) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); // photo shared to this app from the gallery
            if (u != null) push(new ResultScreen(this, u, null, null), stack.size() > 0);
        }
    }

    void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose a photo"), REQ_PICK);
        } catch (Exception e) {
            toast("No gallery app found", false);
        }
    }

    /**
     * Opens the app's own camera. Phone camera apps (Xiaomi, Vivo, Oppo, Realme...) can stamp "Shot on ..."
     * watermarks on their photos; photos taken here never pass through them, so they are always clean.
     */
    @SuppressWarnings("deprecation") // overridePendingTransition: the replacement needs API 34
    void openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            return;
        }
        startActivityForResult(new Intent(this, CameraActivity.class), REQ_CAMERA);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req != REQ_CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            toast("Allow camera access to take a photo", false);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if ((req == REQ_PICK || req == REQ_CAMERA) && result == RESULT_OK && data != null && data.getData() != null) {
            push(new ResultScreen(this, data.getData(), null, null));
        }
    }

    // ------------------------------------------------------------------ photos

    /** Decodes with EXIF rotation applied and the long side limited to MAX_SIDE. */
    Bitmap decode(Uri uri) throws IOException {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = cr.openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        if (b == null) throw new IOException("decode failed");

        int rotation = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (Exception ignored) {
            // no EXIF: assume upright
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

    /** Writes a picture to Pictures/GrainCounter and returns its Uri. */
    Uri writeToGallery(Bitmap bmp, String name, boolean png) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name + (png ? ".png" : ".jpg"));
        v.put(MediaStore.Images.Media.MIME_TYPE, png ? "image/png" : "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCounter");
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("insert failed");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null || !bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : 92, os)) {
                throw new IOException("write failed");
            }
        } catch (IOException e) {
            cr.delete(uri, null, null); // do not leave a broken file in the gallery
            throw e;
        }
        return uri;
    }

    void share(Uri uri, String text) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("image/*");
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.putExtra(Intent.EXTRA_TEXT, text);
        s.setClipData(ClipData.newRawUri("Counted image", uri));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Share counted image"));
    }
}
