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
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
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
    private final Path clipPath = new Path();
    private Bitmap bitmap;
    private Drawable animated;
    private File remoteFile;
    private boolean remoteMode;
    private long remoteUid;
    private LinearLayout musicPill;
    private ImageView musicIcon;
    private TextView musicLabel;
    private boolean musicLoading;

    /** Shows another user's banner (null: they have none, only music) and lets the visitor play their track. */
    public void setRemote(File f, long uid) {
        remoteMode = true;
        remoteFile = f;
        remoteUid = uid;
        setOnClickListener(null);
        setOnLongClickListener(null);
        setClickable(false);
        update();
    }

    private String musicKey() {
        return remoteMode ? "u" + remoteUid : "me";
    }

    private boolean hasMusic() {
        return remoteMode ? org.telegram.messenger.NullgramSync.hasMusic(remoteUid) : org.telegram.messenger.NullgramSync.musicFile().exists();
    }

    private void updateMusicPill() {
        if (musicPill == null) {
            return;
        }
        musicPill.setVisibility(hasMusic() ? VISIBLE : GONE);
        final boolean playing = ProfileMusic.isPlaying(musicKey());
        musicIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        musicIcon.setColorFilter(MaterialYou.role(MaterialYou.R_ON_PRIMARY_CONTAINER));
        musicLabel.setTextColor(MaterialYou.role(MaterialYou.R_ON_PRIMARY_CONTAINER));
        musicLabel.setText(musicLoading ? MaterialYou.s("Loading…", "Загрузка…") : playing ? MaterialYou.s("Playing", "Играет") : MaterialYou.s("Music", "Музыка"));
        musicPill.setBackground(Theme.createRoundRectDrawable(dp(20), MaterialYou.role(MaterialYou.R_PRIMARY_CONTAINER)));
    }

    private void toggleMusic() {
        final String key = musicKey();
        if (!remoteMode) {
            ProfileMusic.toggle(key, org.telegram.messenger.NullgramSync.musicFile());
            updateMusicPill();
            return;
        }
        if (ProfileMusic.isPlaying(key)) {
            ProfileMusic.stop();
            updateMusicPill();
            return;
        }
        if (musicLoading) {
            return;
        }
        musicLoading = true;
        updateMusicPill();
        org.telegram.messenger.NullgramSync.fetchMusic(remoteUid, file -> {
            musicLoading = false;
            if (isAttachedToWindow()) {
                ProfileMusic.toggle(key, file);
            }
            updateMusicPill();
        });
    }

    private static boolean looksAnimated(byte[] head, int length) {
        if (length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return true;
        }
        if (length >= 16 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            for (int i = 12; i + 4 <= Math.min(length, 128); i++) {
                if (head[i] == 'A' && head[i + 1] == 'N' && head[i + 2] == 'I' && head[i + 3] == 'M') {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAnimatedFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            final byte[] head = new byte[128];
            final int n = in.read(head);
            return n > 0 && looksAnimated(head, n);
        } catch (Exception e) {
            return false;
        }
    }

    private void stopAnimation() {
        if (animated instanceof android.graphics.drawable.AnimatedImageDrawable) {
            ((android.graphics.drawable.AnimatedImageDrawable) animated).stop();
        }
        if (animated != null) {
            animated.setCallback(null);
        }
        animated = null;
    }

    private void applyAnimated(Drawable d) {
        stopAnimation();
        bitmap = null;
        shader = null;
        imagePaint.setShader(null);
        animated = d;
        if (d != null) {
            d.setCallback(this);
            if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                final android.graphics.drawable.AnimatedImageDrawable a = (android.graphics.drawable.AnimatedImageDrawable) d;
                a.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                a.start();
            }
        }
        hint.setVisibility(GONE);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == animated || super.verifyDrawable(who);
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

        musicPill = new LinearLayout(context);
        musicPill.setOrientation(LinearLayout.HORIZONTAL);
        musicPill.setGravity(Gravity.CENTER_VERTICAL);
        musicPill.setPadding(dp(12), dp(8), dp(16), dp(8));
        musicIcon = new ImageView(context);
        musicPill.addView(musicIcon, LayoutHelper.createLinear(22, 22));
        musicLabel = new TextView(context);
        musicLabel.setTextSize(14);
        musicLabel.setTypeface(org.telegram.messenger.AndroidUtilities.bold());
        musicPill.addView(musicLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        musicPill.setOnClickListener(v -> toggleMusic());
        musicPill.setVisibility(GONE);
        addView(musicPill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 20, 0, 0, 20));

        setOnClickListener(v -> pick());
        setOnLongClickListener(v -> {
            if (bitmap == null && animated == null) return false;
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
        stopAnimation();
        bitmap = bmp;
        shader = bmp != null ? new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        imagePaint.setShader(shader);
        hint.setVisibility(bmp != null || remoteMode ? GONE : VISIBLE);
        invalidate();
        updateMusicPill();
    }

    public void update() {
        updateMusicPill();
        final File f = remoteMode ? remoteFile : bannerFile();
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        dashPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        if (f == null || !f.exists()) {
            currentKey = null;
            apply(null);
            return;
        }
        final String key = f.getAbsolutePath() + ":" + f.lastModified();
        if (key.equals(currentKey) && (bitmap != null || animated != null)) {
            return;
        }
        currentKey = key;
        if (android.os.Build.VERSION.SDK_INT >= 28 && isAnimatedFile(f)) {
            hint.setVisibility(GONE);
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                Drawable d = null;
                try {
                    d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f), (decoder, info, src) -> {
                        decoder.setTargetSampleSize(Math.max(1, info.getSize().getWidth() / 1080));
                    });
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                final Drawable result = d;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    if (key.equals(currentKey)) {
                        applyAnimated(result);
                        updateMusicPill();
                    }
                });
            });
            return;
        }
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
        if (animated instanceof android.graphics.drawable.AnimatedImageDrawable) {
            ((android.graphics.drawable.AnimatedImageDrawable) animated).start();
        }
        updateMusicPill();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.onActivityResultReceived);
        if (animated instanceof android.graphics.drawable.AnimatedImageDrawable) {
            ((android.graphics.drawable.AnimatedImageDrawable) animated).stop();
        }
        ProfileMusic.stopIf(musicKey());
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
        if (animated != null) {
            final int iw = animated.getIntrinsicWidth(), ih = animated.getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                clipPath.reset();
                clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clipPath);
                final float scale = Math.max(rect.width() / iw, rect.height() / ih);
                canvas.translate(rect.left + (rect.width() - iw * scale) / 2f, rect.top);
                canvas.scale(scale, scale);
                animated.setBounds(0, 0, iw, ih);
                animated.draw(canvas);
                canvas.restore();
            }
            outlinePaint.setColor(MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT));
            canvas.drawRoundRect(rect.left + dp(0.5f), rect.top + dp(0.5f), rect.right - dp(0.5f), rect.bottom - dp(0.5f), r, r, outlinePaint);
        } else if (bitmap != null && shader != null) {
            final float scale = Math.max(rect.width() / bitmap.getWidth(), rect.height() / bitmap.getHeight());
            matrix.reset();
            matrix.setScale(scale, scale);
            matrix.postTranslate(rect.left + (rect.width() - bitmap.getWidth() * scale) / 2f, rect.top);
            shader.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, r, r, imagePaint);
            outlinePaint.setColor(MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT));
            canvas.drawRoundRect(rect.left + dp(0.5f), rect.top + dp(0.5f), rect.right - dp(0.5f), rect.bottom - dp(0.5f), r, r, outlinePaint);
        } else if (remoteMode) {
            imagePaint.setShader(null);
            imagePaint.setColor(MaterialYou.role(MaterialYou.R_SURFACE_HIGH));
            canvas.drawRoundRect(rect, r, r, imagePaint);
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
            // an animated GIF or WebP that fits the server limit is kept as it is, so the banner keeps moving
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while (raw.size() <= 1_400_000 && (n = in.read(buf)) > 0) {
                    raw.write(buf, 0, n);
                }
                final byte[] all = raw.toByteArray();
                if (all.length > 0 && all.length <= 1_400_000 && looksAnimated(all, all.length)) {
                    try (FileOutputStream out = new FileOutputStream(bannerFile())) {
                        out.write(all);
                    }
                    org.telegram.messenger.NullgramSync.pushBanner(bannerFile());
                    return true;
                }
            }
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
