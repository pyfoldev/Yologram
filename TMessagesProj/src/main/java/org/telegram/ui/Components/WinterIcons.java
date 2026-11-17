package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.MaterialYou;
import org.telegram.ui.ActionBar.Theme;

/** Flat winter icons that follow the theme: Material Symbols pine and a drawn snowman. */
public class WinterIcons {

    /** Pine tree from Material Symbols, tinted with the theme colour. */
    public static Drawable tree(Context context) {
        final Drawable d = context.getResources().getDrawable(R.drawable.winter_tree).mutate();
        d.setColorFilter(new PorterDuffColorFilter(MaterialYou.role(MaterialYou.R_PRIMARY), PorterDuff.Mode.SRC_IN));
        return d;
    }

    public static Drawable flake(Context context) {
        final Drawable d = context.getResources().getDrawable(R.drawable.winter_flake).mutate();
        d.setColorFilter(new PorterDuffColorFilter(MaterialYou.role(MaterialYou.R_PRIMARY), PorterDuff.Mode.SRC_IN));
        return d;
    }

    public static Drawable snowman() {
        return new SnowmanDrawable();
    }

    private static class SnowmanDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            final float w = b.width(), h = b.height();
            final float cx = b.left + w / 2f;
            final boolean dark = Theme.isCurrentThemeDark();
            final int body = dark ? 0xFFEFF3FA : 0xFFFFFFFF;
            final int edge = dark ? 0x00000000 : MaterialYou.role(MaterialYou.R_OUTLINE_VARIANT);
            final float rBottom = w * 0.31f, rMid = w * 0.23f, rHead = w * 0.17f;
            final float yBottom = b.top + h - rBottom - h * 0.03f;
            final float yMid = yBottom - rBottom * 0.72f - rMid * 0.55f;
            final float yHead = yMid - rMid * 0.62f - rHead * 0.55f;
            // body circles, with a hairline edge on light themes so white reads on white
            p.setStyle(Paint.Style.FILL);
            p.setColor(body);
            canvas.drawCircle(cx, yBottom, rBottom, p);
            canvas.drawCircle(cx, yMid, rMid, p);
            canvas.drawCircle(cx, yHead, rHead, p);
            if (edge != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(edge);
                canvas.drawCircle(cx, yBottom, rBottom, p);
                canvas.drawCircle(cx, yMid, rMid, p);
                canvas.drawCircle(cx, yHead, rHead, p);
            }
            p.setStyle(Paint.Style.FILL);
            // scarf
            p.setColor(MaterialYou.role(MaterialYou.R_PRIMARY));
            canvas.drawRoundRect(cx - rMid * 0.75f, yHead + rHead * 0.78f, cx + rMid * 0.75f, yHead + rHead * 0.78f + h * 0.07f, h * 0.035f, h * 0.035f, p);
            canvas.drawRoundRect(cx + rMid * 0.1f, yHead + rHead * 0.78f, cx + rMid * 0.1f + w * 0.075f, yHead + rHead * 0.78f + h * 0.2f, w * 0.035f, w * 0.035f, p);
            // eyes, carrot, buttons
            p.setColor(0xFF2A2A33);
            canvas.drawCircle(cx - rHead * 0.4f, yHead - rHead * 0.18f, w * 0.022f, p);
            canvas.drawCircle(cx + rHead * 0.4f, yHead - rHead * 0.18f, w * 0.022f, p);
            canvas.drawCircle(cx, yMid - rMid * 0.05f, w * 0.022f, p);
            canvas.drawCircle(cx, yMid + rMid * 0.4f, w * 0.022f, p);
            p.setColor(0xFFFF8A3D);
            final android.graphics.Path nose = new android.graphics.Path();
            nose.moveTo(cx - rHead * 0.12f, yHead + rHead * 0.05f);
            nose.lineTo(cx + rHead * 0.75f, yHead + rHead * 0.22f);
            nose.lineTo(cx - rHead * 0.12f, yHead + rHead * 0.38f);
            nose.close();
            canvas.drawPath(nose, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
