package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.NullgramConfig;

import java.util.ArrayList;
import java.util.Random;

public class SeasonOverlayView extends View {

    private static final String TAG = "nullgram_season_overlay";

    private static final String[] NEW_YEAR = {"❄️", "❄️", "❄️", "❄️", "❄️", "⛄", "🎄", "🎅", "🎁", "⭐"};
    private static final String[] HALLOWEEN = {"🎃", "🎃", "👻", "🕷️", "🍬", "🕸️", "🦇", "🦇"};

    private static class Particle {
        float x, y, vx, vy, rot, vr, scale, phase;
        int type;
    }

    private final Random random = new Random();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap[] bitmaps = new Bitmap[0];
    private int season;
    private long last;

    public SeasonOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public static void refresh(Activity activity) {
        if (activity == null) {
            return;
        }
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        final View existing = content.findViewWithTag(TAG);
        final int season = NullgramConfig.season();
        final boolean on = season != NullgramConfig.SEASON_OFF && NullgramConfig.seasonEffects();
        if (!on) {
            if (existing != null) {
                content.removeView(existing);
            }
            return;
        }
        if (existing instanceof SeasonOverlayView) {
            ((SeasonOverlayView) existing).setSeason(season);
        } else {
            final SeasonOverlayView view = new SeasonOverlayView(activity);
            view.setTag(TAG);
            view.setSeason(season);
            content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    public void setSeason(int season) {
        if (this.season == season) {
            return;
        }
        this.season = season;
        final String[] emoji = season == NullgramConfig.SEASON_HALLOWEEN ? HALLOWEEN : NEW_YEAR;
        final ArrayList<String> distinct = new ArrayList<>();
        for (String e : emoji) {
            if (!distinct.contains(e)) distinct.add(e);
        }
        bitmaps = new Bitmap[distinct.size()];
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(88);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < bitmaps.length; i++) {
            final Bitmap b = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(b);
            c.drawText(distinct.get(i), 56, 56 - (p.descent() + p.ascent()) / 2f, p);
            bitmaps[i] = b;
        }
        particles.clear();
        if (getWidth() > 0) {
            spawnAll();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bitmaps.length > 0) {
            spawnAll();
        }
    }

    private int typeIndex(int slot) {
        final String[] emoji = season == NullgramConfig.SEASON_HALLOWEEN ? HALLOWEEN : NEW_YEAR;
        final String name = emoji[slot % emoji.length];
        int index = 0;
        final ArrayList<String> distinct = new ArrayList<>();
        for (String e : emoji) {
            if (!distinct.contains(e)) distinct.add(e);
        }
        index = distinct.indexOf(name);
        return Math.max(0, index);
    }

    private void spawnAll() {
        particles.clear();
        final int count = season == NullgramConfig.SEASON_HALLOWEEN ? 12 : 26;
        for (int i = 0; i < count; i++) {
            final Particle p = new Particle();
            respawn(p, true, i);
            particles.add(p);
        }
    }

    private void respawn(Particle p, boolean anywhere, int slot) {
        final String[] emoji = season == NullgramConfig.SEASON_HALLOWEEN ? HALLOWEEN : NEW_YEAR;
        p.type = typeIndex(slot);
        final String name = emoji[slot % emoji.length];
        final boolean snow = "❄️".equals(name);
        final boolean bat = "🦇".equals(name);
        p.x = random.nextFloat() * Math.max(1, getWidth());
        p.y = anywhere ? random.nextFloat() * Math.max(1, getHeight()) : -dp(40);
        p.phase = random.nextFloat() * 6.28f;
        p.rot = random.nextFloat() * 360f;
        if (bat) {
            p.y = random.nextFloat() * getHeight() * 0.35f;
            p.vx = (random.nextBoolean() ? 1 : -1) * dp(70 + random.nextInt(90));
            p.vy = 0;
            p.vr = 0;
            p.scale = 0.34f + random.nextFloat() * 0.2f;
        } else if (snow) {
            p.vy = dp(34 + random.nextInt(60));
            p.vx = 0;
            p.vr = random.nextFloat() * 40f - 20f;
            p.scale = 0.16f + random.nextFloat() * 0.2f;
        } else {
            p.vy = dp(22 + random.nextInt(40));
            p.vx = 0;
            p.vr = random.nextFloat() * 30f - 15f;
            p.scale = 0.38f + random.nextFloat() * 0.3f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmaps.length == 0 || particles.isEmpty()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        final int w = getWidth(), h = getHeight();
        final String[] emoji = season == NullgramConfig.SEASON_HALLOWEEN ? HALLOWEEN : NEW_YEAR;

        for (int i = 0; i < particles.size(); i++) {
            final Particle p = particles.get(i);
            final boolean bat = "🦇".equals(emoji[i % emoji.length]);
            p.phase += dt * 1.3f;
            p.y += p.vy * dt;
            p.x += (p.vx != 0 ? p.vx : (float) Math.sin(p.phase) * dp(18)) * dt;
            p.rot += p.vr * dt;
            if (bat) {
                p.y += (float) Math.sin(p.phase * 3) * dp(26) * dt;
                if (p.x < -dp(40) || p.x > w + dp(40)) {
                    respawn(p, false, i);
                    p.y = random.nextFloat() * h * 0.35f;
                    p.x = p.vx > 0 ? -dp(30) : w + dp(30);
                }
            } else if (p.y > h + dp(40)) {
                respawn(p, false, i);
            }
            final Bitmap b = bitmaps[p.type];
            final float size = dp(56) * p.scale;
            canvas.save();
            canvas.translate(p.x, p.y);
            if (!bat) canvas.rotate(p.rot);
            if (bat && p.vx < 0) canvas.scale(-1, 1);
            final float s = size / b.getWidth();
            canvas.scale(s, s);
            paint.setAlpha(bat ? 235 : 210);
            canvas.drawBitmap(b, -b.getWidth() / 2f, -b.getHeight() / 2f, paint);
            canvas.restore();
        }
        postInvalidateDelayed(32);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        last = 0;
    }
}
