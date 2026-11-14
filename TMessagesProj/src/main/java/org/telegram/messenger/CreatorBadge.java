package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.MaterialYou;

import java.util.ArrayList;

public class CreatorBadge {

    public static final String CREATOR_USERNAME = "pyfold";
    private static final String PREFS = "nullgram_creator";
    private static final String DEFAULT_EXTRAS = "nullgram, pyfdev, butwhy";

    public static boolean isCreator(TLRPC.User user) {
        if (user == null) {
            return false;
        }
        if (CREATOR_USERNAME.equalsIgnoreCase(user.username)) {
            return true;
        }
        if (user.usernames != null) {
            for (int i = 0; i < user.usernames.size(); i++) {
                final TLRPC.TL_username u = user.usernames.get(i);
                if (u != null && u.active && CREATOR_USERNAME.equalsIgnoreCase(u.username)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Drawable drawable(int sizePx) {
        return new BadgeDrawable(sizePx);
    }

    private static class BadgeDrawable extends Drawable {
        private final int size;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        BadgeDrawable(int size) {
            this.size = size;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            fill.setColor(MaterialYou.role(MaterialYou.R_PRIMARY));
            rect.set(b);
            final float r = Math.min(b.width(), b.height()) * 0.32f;
            canvas.drawRoundRect(rect, r, r, fill);
            text.setColor(MaterialYou.role(MaterialYou.R_ON_PRIMARY));
            text.setTextSize(b.height() * 0.66f);
            canvas.drawText("N", b.centerX(), b.centerY() - (text.descent() + text.ascent()) / 2f, text);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); text.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static String getExtrasText() {
        return prefs().getString("extras", DEFAULT_EXTRAS);
    }

    public static void setExtrasText(String text) {
        prefs().edit().putString("extras", text == null ? "" : text).apply();
    }

    public static ArrayList<TLRPC.TL_username> withExtras(TLRPC.User user, ArrayList<TLRPC.TL_username> original) {
        if (!isCreator(user)) {
            return original;
        }
        final ArrayList<TLRPC.TL_username> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
        final boolean self = user.id == UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        for (String raw : (self ? getExtrasText() : DEFAULT_EXTRAS).split("[,\\s]+")) {
            String name = raw.trim();
            if (name.startsWith("@")) {
                name = name.substring(1);
            }
            if (TextUtils.isEmpty(name) || name.equalsIgnoreCase(user.username)) {
                continue;
            }
            boolean exists = false;
            for (TLRPC.TL_username u : result) {
                if (name.equalsIgnoreCase(u.username)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                continue;
            }
            final TLRPC.TL_username extra = new TLRPC.TL_username();
            extra.username = name;
            extra.active = true;
            extra.editable = false;
            result.add(extra);
        }
        return result;
    }
}
