package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DisappearingMedia;
import org.telegram.messenger.NullgramConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.camera.CameraController;
import org.telegram.messenger.camera.CameraView;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.Locale;

/**
 * Yologram camera: a full-screen camera of its own with photo and video, flash, grid, timer, zoom and lens switch.
 * Everything it records is saved to Pictures/Yologram and Movies/Yologram.
 */
public class YologramCameraActivity extends BaseFragment {

    private FrameLayout root;
    private CameraView cameraView;
    private GridOverlay grid;
    private TextView flashChip, gridChip, timerChip, modeChip, zoomChip, hint, clock;
    private ShutterView shutter;

    private boolean videoMode;
    private boolean recording;
    private boolean taking;
    private boolean gridOn = NullgramConfig.cameraGrid();
    private int timerSeconds;            // 0, 3, 10
    private int zoomStep;                // 0..3
    private long recordStart;
    private File recordFile;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recording) {
                final long s = (SystemClock.elapsedRealtime() - recordStart) / 1000;
                clock.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
                AndroidUtilities.runOnUIThread(this, 500);
            }
        }
    };

    @Override
    public boolean onFragmentCreate() {
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        hasOwnBackground = true;
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        fragmentView = root;

        grid = new GridOverlay(context);
        grid.setVisibility(gridOn ? View.VISIBLE : View.GONE);

        // top bar
        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final TextView close = chip(context, "✕", true);
        close.setOnClickListener(v -> finishFragment());
        flashChip = chip(context, "⚡ " + label("Off", "Выкл"), false);
        flashChip.setOnClickListener(v -> cycleFlash());
        gridChip = chip(context, "▦", gridOn);
        gridChip.setOnClickListener(v -> {
            gridOn = !gridOn;
            grid.setVisibility(gridOn ? View.VISIBLE : View.GONE);
            style(gridChip, gridOn);
        });
        timerChip = chip(context, "⏱ " + label("Off", "Выкл"), false);
        timerChip.setOnClickListener(v -> {
            timerSeconds = timerSeconds == 0 ? 3 : timerSeconds == 3 ? 10 : 0;
            timerChip.setText("⏱ " + (timerSeconds == 0 ? label("Off", "Выкл") : timerSeconds + " " + label("s", "с")));
            style(timerChip, timerSeconds != 0);
        });
        top.addView(close, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        top.addView(flashChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        top.addView(timerChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        top.addView(gridChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));

        clock = new TextView(context);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(15);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setVisibility(View.GONE);

        hint = new TextView(context);
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);

        // bottom bar
        modeChip = chip(context, "", false);
        modeChip.setOnClickListener(v -> {
            if (recording) {
                return;
            }
            videoMode = !videoMode;
            updateMode();
        });
        zoomChip = chip(context, "1×", false);
        zoomChip.setOnClickListener(v -> {
            zoomStep = (zoomStep + 1) % 4;
            final float[] z = {0f, 0.25f, 0.5f, 0.8f};
            if (cameraView != null) {
                cameraView.setZoom(z[zoomStep]);
            }
            zoomChip.setText(new String[]{"1×", "2×", "3×", "5×"}[zoomStep]);
        });
        final TextView flip = chip(context, "⟲", false);
        flip.setOnClickListener(v -> {
            if (cameraView != null && !recording) {
                cameraView.switchCamera();
            }
        });
        shutter = new ShutterView(context);
        shutter.setOnClickListener(v -> onShutter());

        final FrameLayout bottom = new FrameLayout(context);
        bottom.addView(modeChip, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 24, 0, 0, 0));
        bottom.addView(shutter, LayoutHelper.createFrame(76, 76, Gravity.CENTER));
        final LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(zoomChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 8, 0));
        right.addView(flip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        bottom.addView(right, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 20, 0));

        root.addView(grid, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 16, 12 + statusBarDp(), 16, 0));
        root.addView(clock, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 64 + statusBarDp(), 0, 0));
        root.addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 24, 0, 24, 150));
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM, 0, 0, 0, 32));
        updateMode();

        if (hasPermission()) {
            startCamera();
        } else {
            hint.setText(label("Camera access is needed", "Нужен доступ к камере"));
            if (getParentActivity() != null && Build.VERSION.SDK_INT >= 23) {
                getParentActivity().requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 7731);
            }
        }
        return fragmentView;
    }

    private int statusBarDp() {
        return (int) (AndroidUtilities.statusBarHeight / AndroidUtilities.density);
    }

    private static String label(String en, String ru) {
        return MaterialYou.s(en, ru);
    }

    private boolean hasPermission() {
        final Activity a = getParentActivity();
        return a != null && (Build.VERSION.SDK_INT < 23 || a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 7731 && hasPermission() && cameraView == null) {
            startCamera();
        }
    }

    private void startCamera() {
        if (cameraView != null || root == null) {
            return;
        }
        hint.setText("");
        cameraView = new CameraView(root.getContext(), NullgramConfig.cameraFront(), false);
        cameraView.setFocusable(true);
        cameraView.setDelegate(() -> updateFlashLabel());
        root.addView(cameraView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void updateMode() {
        modeChip.setText(videoMode ? label("Video", "Видео") : label("Photo", "Фото"));
        shutter.setVideo(videoMode);
        hint.setText(videoMode ? label("Tap to start and stop recording", "Нажмите, чтобы начать и закончить запись") : "");
    }

    private void cycleFlash() {
        if (cameraView == null || cameraView.getCameraSession() == null) {
            return;
        }
        try {
            final String next = cameraView.getCameraSession().getNextFlashMode();
            cameraView.getCameraSession().setCurrentFlashMode(next);
            updateFlashLabel();
        } catch (Throwable t) {
            // some lenses have no flash
        }
    }

    private void updateFlashLabel() {
        if (cameraView == null || cameraView.getCameraSession() == null || flashChip == null) {
            return;
        }
        final String mode = cameraView.getCameraSession().getCurrentFlashMode();
        final boolean off = mode == null || "off".equals(mode);
        flashChip.setText("⚡ " + (off ? label("Off", "Выкл") : "auto".equals(mode) ? label("Auto", "Авто") : label("On", "Вкл")));
        style(flashChip, !off);
    }

    private void onShutter() {
        if (cameraView == null || taking) {
            return;
        }
        if (videoMode) {
            if (recording) {
                stopVideo();
            } else {
                countdown(this::startVideo);
            }
        } else {
            countdown(this::takePhoto);
        }
    }

    private void countdown(Runnable action) {
        if (timerSeconds == 0) {
            action.run();
            return;
        }
        taking = true;
        final int[] left = {timerSeconds};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (left[0] <= 0) {
                hint.setText("");
                hint.setTextSize(13);
                taking = false;
                action.run();
                return;
            }
            hint.setText(String.valueOf(left[0]--));
            hint.setTextSize(48);
            AndroidUtilities.runOnUIThread(step[0], 1000);
        };
        step[0].run();
    }

    private void takePhoto() {
        final File file = AndroidUtilities.generatePicturePath(false, "jpg");
        taking = true;
        cameraView.startTakePictureAnimation(true);
        CameraController.getInstance().takePicture(file, false, cameraView.getCameraSessionObject(), orientation -> {
            taking = false;
            saveToGallery(file, false);
        });
    }

    private void startVideo() {
        recordFile = AndroidUtilities.generateVideoPath(false);
        cameraView.setRecordFile(recordFile);
        if (cameraView.startRecording(recordFile, () -> saveToGallery(recordFile, true))) {
            recording = true;
            recordStart = SystemClock.elapsedRealtime();
            shutter.setRecording(true);
            clock.setVisibility(View.VISIBLE);
            hint.setText("");
            AndroidUtilities.runOnUIThread(tick, 500);
        }
    }

    private void stopVideo() {
        recording = false;
        shutter.setRecording(false);
        clock.setVisibility(View.GONE);
        cameraView.stopRecording();
        updateMode();
    }

    private void saveToGallery(File file, boolean video) {
        if (file == null || !file.exists()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                ok = DisappearingMedia.store(file, video, "Yologram_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"), video ? "video/mp4" : "image/jpeg");
            } catch (Throwable ignore) {
            }
            final boolean saved = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), saved ? label("Saved to gallery", "Сохранено в галерею") : label("Could not save", "Не удалось сохранить"), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (recording) {
            stopVideo();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (cameraView != null) {
            cameraView.destroy(true, null);
            cameraView = null;
        }
    }

    // ------------------------------------------------------------------ small views
    private TextView chip(Context context, String text, boolean on) {
        final TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        style(t, on);
        return t;
    }

    private void style(TextView t, boolean on) {
        t.setTextColor(on ? 0xFF1B1B1B : Color.WHITE);
        t.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(18), on ? 0xFFFFFFFF : 0x66000000));
    }

    private static class GridOverlay extends View {
        private final Paint p = new Paint();

        GridOverlay(Context context) {
            super(context);
            p.setColor(0x66FFFFFF);
            p.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.75f)));
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            final int w = getWidth(), h = getHeight();
            for (int i = 1; i <= 2; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
        }
    }

    private static class ShutterView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean video, recording;

        ShutterView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            ring.setStrokeWidth(AndroidUtilities.dp(4));
        }

        void setVideo(boolean v) {
            video = v;
            invalidate();
        }

        void setRecording(boolean r) {
            recording = r;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - AndroidUtilities.dp(4);
            c.drawCircle(cx, cy, r, ring);
            fill.setColor(video ? 0xFFE53935 : Color.WHITE);
            if (recording) {
                final float s = r * 0.5f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, AndroidUtilities.dp(8), AndroidUtilities.dp(8), fill);
            } else {
                c.drawCircle(cx, cy, r - AndroidUtilities.dp(8), fill);
            }
        }
    }
}
