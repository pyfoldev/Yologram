package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ProfileBannerView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public static final int REQUEST_CODE = 7311;

    private final BaseFragment fragment;
    private final TextView hint;
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Bitmap bitmap;
    private File remoteFile;

    public void setRemote(File f) {
        remoteFile = f;
        setOnClickListener(null);
        setOnLongClickListener(null);
        setClickable(false);
        update();
    }
    private BitmapShader shader;

    public ProfileBannerView(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setWillNotDraw(false);

        hint = new TextView(context);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(15);
        hint.setText(MaterialYou.s("＋  Add a banner photo", "＋  Добавить фото-баннер"));
        addView(hint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(dp(1));
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(dp(1.5f));
        dashPaint.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));

        setOnClickListener(v -> pick());
        setOnLongClickListener(v -> {
            if (bitmap == null) return false;
            final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle(MaterialYou.s("Remove banner?", "Убрать баннер?"));
            builder.setMessage(MaterialYou.s("The photo is removed from this device.", "Фото будет удалено с этого устройства."));
            builder.setPositiveButton(MaterialYou.s("Remove", "Убрать"), (d, w) -> {
                bannerFile().delete();
                org.telegram.messenger.NullgramSync.pushBanner(null);
                update();
            });
            builder.setNegativeButton(MaterialYou.s("Cancel", "Отмена"), null);
            builder.show();
            return true;
        });
        update();
    }

    private static File bannerFile() {
        return org.telegram.messenger.NullgramSync.bannerFile();
    }

    private static final android.util.LruCache<String, Bitmap> CACHE = new android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private String currentKey;

    private void apply(Bitmap bmp) {
        bitmap = bmp;
        shader = bmp != null ? new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        imagePaint.setShader(shader);
        hint.setVisibility(bmp != null || remoteFile != null ? GONE : VISIBLE);
        invalidate();
    }

    public void update() {
        final File f = remoteFile != null ? remoteFile : bannerFile();
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        dashPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        if (!f.exists()) {
            currentKey = null;
            apply(null);
            return;
        }
        final String key = f.getAbsolutePath() + ":" + f.lastModified();
        if (key.equals(currentKey) && bitmap != null) {
            return;
        }
        currentKey = key;
        final Bitmap cached = CACHE.get(key);
        if (cached != null) {
            apply(cached);
            return;
        }
        hint.setVisibility(GONE);
        // decode off the UI thread, scaled down to roughly the screen width
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            Bitmap bmp = null;
            try {
                final BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= 1080) {
                    sample *= 2;
                }
                final BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (bmp != null) {
                    CACHE.put(key, bmp);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            final Bitmap result = bmp;
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                if (key.equals(currentKey)) {
                    apply(result);
                }
            });
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.onActivityResultReceived);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.onActivityResultReceived);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.onActivityResultReceived && args.length >= 3 && args[0] instanceof Integer) {
            final int requestCode = (Integer) args[0];
            final int resultCode = (Integer) args[1];
            final Intent data = (Intent) args[2];
            if (requestCode == REQUEST_CODE && onPicked(resultCode, data)) {
                update();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int inner = Math.max(0, width - dp(16));
        final int height = Math.min(dp(240), Math.max(dp(120), inner * 7 / 16)) + dp(16);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(dp(8), dp(8), getWidth() - dp(8), getHeight() - dp(8));
        final float r = MaterialYou.radius(26);
        if (bitmap != null && shader != null) {
            final float scale = Math.max(rect.width() / bitmap.getWidth(), rect.height() / bitmap.getHeight());
            matrix.reset();
            matrix.setScale(scale, scale);
            matrix.postTranslate(rect.left + (rect.width() - bitmap.getWidth() * scale) / 2f, rect.top);
            shader.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, r, r, imagePaint);
            outlinePaint.setColor(MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT));
            canvas.drawRoundRect(rect.left + dp(0.5f), rect.top + dp(0.5f), rect.right - dp(0.5f), rect.bottom - dp(0.5f), r, r, outlinePaint);
        } else {
            canvas.drawRoundRect(rect, r, r, dashPaint);
        }
    }

    private void pick() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        fragment.startActivityForResult(Intent.createChooser(intent, MaterialYou.s("Banner photo", "Фото баннера")), REQUEST_CODE);
    }

    public static boolean onPicked(int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
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
            while (bounds.outWidth / (sample * 2) >= 1280) {
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
            try (FileOutputStream out = new FileOutputStream(bannerFile())) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            org.telegram.messenger.NullgramSync.pushBanner(bannerFile());
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
