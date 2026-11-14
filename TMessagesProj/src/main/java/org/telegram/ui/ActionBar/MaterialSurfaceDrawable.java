package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

public class MaterialSurfaceDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Rect padding = new Rect();
    private final float radius;
    private final boolean topOnly;
    private final float[] radii = new float[8];

    public MaterialSurfaceDrawable(float radiusPx, boolean topOnly, Rect padding) {
        this.radius = radiusPx;
        this.topOnly = topOnly;
        this.padding.set(padding);
        paint.setColor(Color.WHITE);
        for (int i = 0; i < 4; i++) radii[i] = radiusPx;
        for (int i = 4; i < 8; i++) radii[i] = topOnly ? 0 : radiusPx;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        rect.set(b.left + padding.left, b.top + padding.top, b.right - padding.right, b.bottom - padding.bottom);
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    @Override
    public boolean getPadding(@NonNull Rect out) {
        out.set(padding);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return paint.getColorFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
