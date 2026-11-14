package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.MaterialYou;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class HomeWallpaper {

    public static final int REQUEST_CODE = 7312;
    private static final String FILE = "home_wallpaper.jpg";

    private static Bitmap bitmap;
    private static boolean loaded;
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint scrim = new Paint();

    private static File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), FILE);
    }

    public static boolean has() {
        ensureLoaded();
        return bitmap != null;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (file().exists()) {
                bitmap = BitmapFactory.decodeFile(file().getAbsolutePath());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static synchronized void reload() {
        loaded = false;
        bitmap = null;
        ensureLoaded();
    }

    public static void remove() {
        file().delete();
        reload();
    }

    public static void pick(Activity activity) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        activity.startActivityForResult(Intent.createChooser(intent, MaterialYou.s("Wallpaper", "Обои")), REQUEST_CODE);
    }

    public static void draw(Canvas canvas, int w, int h) {
        ensureLoaded();
        final Bitmap b = bitmap;
        if (b == null || w <= 0 || h <= 0) {
            return;
        }
        final float s = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        canvas.save();
        canvas.translate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
        canvas.scale(s, s);
        canvas.drawBitmap(b, 0, 0, paint);
        canvas.restore();
        scrim.setColor(ColorUtils.setAlphaComponent(MaterialYou.role(MaterialYou.R_SURFACE), 120));
        canvas.drawRect(0, 0, w, h, scrim);
    }

    public static boolean onPicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return false;
        }
        final Uri uri = data.getData();
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            final Bitmap bmp;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            reload();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
