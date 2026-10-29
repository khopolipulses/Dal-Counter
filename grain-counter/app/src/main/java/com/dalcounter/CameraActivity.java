package com.dalcounter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The app's own camera (plain Camera2, no libraries). Photos never pass through the phone's camera app,
 * so no "Shot on ..." watermark, date stamp or frame can be added to them.
 * Returns the saved photo's Uri (Pictures/GrainCounter) as the result data.
 */
public class CameraActivity extends Activity {

    /** Photos are counted at 1280 px, so a ~3 MP shot is plenty and saves quickly. */
    private static final int WANTED_LONG_SIDE = 2560;

    private PreviewView preview;
    private View shutter;
    private HandlerThread thread;
    private Handler handler;
    private OrientationEventListener orientationListener;
    private volatile int deviceOrientation = 0;

    private String cameraId;
    private int sensorOrientation;
    private Size previewSize;
    private Size jpegSize;
    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean capturing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        Ui.init(this);
        TextView tip = Ui.text(this, "Hold the phone flat, straight above the grains", 14, Ui.MEDIUM, Color.WHITE);
        tip.setGravity(Gravity.CENTER);
        tip.setBackground(Ui.fill(Color.argb(140, 30, 30, 34), 18));
        tip.setPadding(dp(16), dp(10), dp(16), dp(10));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = dp(48);
        root.addView(tip, tl);

        // iOS-style controls: Cancel on the left, a big round white shutter in the middle.
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0)}));
        bar.setPadding(dp(20), dp(28), dp(20), dp(36));
        TextView cancel = Ui.text(this, "Cancel", 17, Ui.MEDIUM, Color.WHITE);
        cancel.setPadding(dp(8), dp(12), dp(8), dp(12));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        Ui.pressable(cancel);
        bar.addView(cancel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        FrameLayout shutterView = new FrameLayout(this);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setStroke(dp(4), Color.WHITE);
        shutterView.setBackground(ringShape);
        View inner = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.WHITE);
        inner.setBackground(dot);
        shutterView.addView(inner, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        shutterView.setContentDescription("Take photo");
        shutterView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
        Ui.pressable(shutterView);
        shutter = shutterView;
        bar.addView(shutter, new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER));
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        // The screen stays portrait; the photo still gets the way the phone was held (flat = last known way).
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = (o + 45) / 90 * 90 % 360;
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
        }
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        closeCamera();
        thread.quitSafely();
        thread = null;
        handler = null;
        super.onPause();
    }

    // ------------------------------------------------------------------ camera

    @SuppressLint("MissingPermission") // MainActivity only opens this screen once the permission is granted
    private void openCamera() {
        if (device != null || handler == null) return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!chooseCamera(manager)) {
                fail("No camera found");
                return;
            }
            preview.setAspect(sensorOrientation % 180 == 0 ? previewSize.getWidth() : previewSize.getHeight(),
                    sensorOrientation % 180 == 0 ? previewSize.getHeight() : previewSize.getWidth());
            reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    savePhoto(r);
                }
            }, handler);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    device = null;
                    fail("Cannot open the camera");
                }
            }, handler);
        } catch (Exception e) { // CameraAccessException, SecurityException, IllegalArgumentException
            fail("Cannot open the camera");
        }
    }

    /** Picks the main back camera, a ~3 MP JPEG size and a matching preview size. */
    private boolean chooseCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id;
                break;
            }
            if (fallback == null) fallback = id;
        }
        if (cameraId == null) cameraId = fallback;
        if (cameraId == null) return false;

        CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] jpegs = map.getOutputSizes(ImageFormat.JPEG);
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        if (jpegs == null || jpegs.length == 0 || previews == null || previews.length == 0) return false;

        // JPEG: prefer 4:3 (the full sensor); the smallest one that is still >= WANTED_LONG_SIDE.
        jpegSize = null;
        Size largest43 = null, largest = null;
        for (Size s : jpegs) {
            if (largest == null || area(s) > area(largest)) largest = s;
            if (!is43(s)) continue;
            if (largest43 == null || area(s) > area(largest43)) largest43 = s;
            if (longSide(s) >= WANTED_LONG_SIDE && (jpegSize == null || area(s) < area(jpegSize))) jpegSize = s;
        }
        if (jpegSize == null) jpegSize = largest43 != null ? largest43 : largest;

        // Preview: same shape as the photo, at most 1920 px long, as large as possible.
        previewSize = null;
        for (Size s : previews) {
            boolean sameShape = Math.abs((float) s.getWidth() / s.getHeight()
                    - (float) jpegSize.getWidth() / jpegSize.getHeight()) < 0.02f;
            if (!sameShape || longSide(s) > 1920) continue;
            if (previewSize == null || area(s) > area(previewSize)) previewSize = s;
        }
        if (previewSize == null) previewSize = previews[0];
        return true;
    }

    private static boolean is43(Size s) {
        return Math.abs((float) longSide(s) / Math.min(s.getWidth(), s.getHeight()) - 4f / 3f) < 0.02f;
    }

    private static int longSide(Size s) {
        return Math.max(s.getWidth(), s.getHeight());
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List, ...) is simple and works on every API level we support
    private void startPreview() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || device == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface surface = new Surface(st);
            final CameraDevice d = device;
            d.createCaptureSession(Arrays.asList(surface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    session = s;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        s.setRepeatingRequest(b.build(), null, handler);
                    } catch (Exception e) {
                        fail("Cannot start the camera");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("Cannot start the camera");
                }
            }, handler);
        } catch (Exception e) {
            fail("Cannot start the camera");
        }
    }

    private void takePicture() {
        final CameraDevice d = device;
        final CameraCaptureSession s = session;
        if (d == null || s == null || capturing) return;
        capturing = true;
        shutter.setEnabled(false);
        shutter.animate().alpha(0.4f).scaleX(0.9f).scaleY(0.9f).setDuration(150).start();
        preview.animate().alpha(0.3f).setDuration(80).withEndAction(new Runnable() { // shutter flash
            @Override
            public void run() {
                preview.animate().alpha(1f).setDuration(220).start();
            }
        }).start();
        try {
            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + deviceOrientation) % 360); // back camera
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            s.capture(b.build(), null, handler);
        } catch (Exception e) {
            captureFailed();
        }
    }

    /** Runs on the camera thread: writes the JPEG to Pictures/GrainCounter and returns its Uri. */
    private void savePhoto(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        byte[] jpeg;
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            jpeg = new byte[buf.remaining()];
            buf.get(jpeg);
        } finally {
            img.close();
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "grains_" + System.currentTimeMillis() + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GrainCounter");
        ContentResolver cr = getContentResolver();
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new Exception("open failed");
            try {
                os.write(jpeg);
            } finally {
                os.close();
            }
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null); // do not leave a broken file in the gallery
            captureFailed();
            return;
        }
        final Uri done = uri;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setResult(RESULT_OK, new Intent().setData(done));
                finish();
            }
        });
    }

    private void captureFailed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                capturing = false;
                shutter.setEnabled(true);
                shutter.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
                Toast.makeText(CameraActivity.this, "Could not take the photo", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void closeCamera() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    private void fail(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                Toast.makeText(CameraActivity.this, message, Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Camera preview that keeps the photo's shape (no stretching), as large as fits. */
    private static final class PreviewView extends TextureView {
        private int ratioW = 3, ratioH = 4;

        PreviewView(Context ctx) {
            super(ctx);
        }

        void setAspect(final int w, final int h) {
            post(new Runnable() {
                @Override
                public void run() {
                    ratioW = w;
                    ratioH = h;
                    requestLayout();
                }
            });
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
            if (w * ratioH > h * ratioW) w = h * ratioW / ratioH;
            else h = w * ratioH / ratioW;
            setMeasuredDimension(w, h);
        }
    }
}
