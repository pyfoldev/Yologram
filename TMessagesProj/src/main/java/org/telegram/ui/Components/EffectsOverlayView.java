package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.NullgramConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.Random;

/**
 * Full-window effects drawn above every screen and never touched: falling snow, and iPhone-style
 * "vehicle motion cues" (dots at the screen edges that shift with the phone's acceleration).
 */
public class EffectsOverlayView extends View implements SensorEventListener {

    private static final int FLAKES = 130;
    private static final int DOTS_PER_EDGE = 7;
    private static final Random RANDOM = new Random();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    // snow
    private final float[] fx = new float[FLAKES], fy = new float[FLAKES], fr = new float[FLAKES];
    private final float[] fspeed = new float[FLAKES], fsway = new float[FLAKES], fphase = new float[FLAKES], falpha = new float[FLAKES];
    private final int[] ftype = new int[FLAKES];
    private final Bitmap[] sprites = new Bitmap[3];
    private boolean spritesDark;
    private boolean spritesReady;
    private boolean snowInit;
    private long lastFrame;
    private float wind;

    // motion cues
    private SensorManager sensorManager;
    private boolean listening;
    private final float[] lin = new float[3];
    private final float[] grav = {0f, -9.81f, 0f};
    private boolean hasGravity;
    private float filtX, filtY, filtBump;
    private float energy;
    private long lastMotionAt;
    private float cueAlpha;
    private boolean cueIdle;
    private long lastCueFrame;
    // one spring per dot so the whole field trails the motion like a liquid
    private static final int CUE_DOTS = 2 * 11 + 2 * 6;
    private final float[] dpx = new float[CUE_DOTS], dpy = new float[CUE_DOTS], dvx = new float[CUE_DOTS], dvy = new float[CUE_DOTS];

    public EffectsOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        dotRing.setStyle(Paint.Style.STROKE);
        dotRing.setStrokeWidth(dp(1.2f));
    }

    /** Adds, updates or removes the overlay according to the current settings. */
    public static void refresh(Activity activity) {
        if (activity == null) {
            return;
        }
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View existing = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof EffectsOverlayView) {
                existing = content.getChildAt(i);
                break;
            }
        }
        final boolean on = NullgramConfig.snow() || NullgramConfig.motionCues();
        if (!on) {
            if (existing != null) {
                content.removeView(existing);
            }
            return;
        }
        if (existing == null) {
            content.addView(new EffectsOverlayView(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ((EffectsOverlayView) existing).syncSensors();
            existing.invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncSensors();
        lastFrame = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSensors();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            syncSensors();
            lastFrame = 0;
            invalidate();
        } else {
            stopSensors();
        }
    }

    private void syncSensors() {
        if (NullgramConfig.motionCues() && getWindowVisibility() == VISIBLE) {
            if (!listening) {
                sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
                Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                boolean raw = false;
                if (s == null) {
                    s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    raw = true;
                }
                if (s != null) {
                    sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
                    listening = true;
                    final Sensor g = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                    if (g != null && !raw) {
                        sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_UI);
                    }
                }
            }
        } else {
            stopSensors();
        }
    }

    private void stopSensors() {
        if (listening && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        listening = false;
        hasGravity = false;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        final int type = e.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY) {
            System.arraycopy(e.values, 0, grav, 0, 3);
            hasGravity = true;
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            // no linear sensor: split gravity off with a slow filter
            for (int i = 0; i < 3; i++) {
                grav[i] = grav[i] * 0.92f + e.values[i] * 0.08f;
                lin[i] = e.values[i] - grav[i];
            }
            hasGravity = true;
        } else {
            lin[0] = e.values[0];
            lin[1] = e.values[1];
            lin[2] = e.values[2];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Computes where the dots should drift to, in px: opposite to the vehicle's acceleration in the screen plane. */
    private void computeCueTarget(float[] out, float dt) {
        // keep only the horizontal part of the acceleration (gravity removed along its axis)
        final float gn = (float) Math.sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]);
        final float gx = gn > 1f ? grav[0] / gn : 0f, gy = gn > 1f ? grav[1] / gn : -1f, gz = gn > 1f ? grav[2] / gn : 0f;
        final float along = lin[0] * gx + lin[1] * gy + lin[2] * gz;
        float ax = lin[0] - along * gx, ay = lin[1] - along * gy, az = lin[2] - along * gz;
        // upright phone: forward/back shows up on the z axis; flat phone: on the y axis
        final float upright = Math.min(1f, Math.abs(gy) * 1.15f);
        final float forward = upright * (-az) + (1f - upright) * ay;
        float sx = ax;
        final android.view.Display d = getDisplay();
        if (d != null) {
            final float rawX = ax, rawY = ay;
            switch (d.getRotation()) {
                case android.view.Surface.ROTATION_90:
                    sx = -rawY;
                    break;
                case android.view.Surface.ROTATION_180:
                    sx = -rawX;
                    break;
                case android.view.Surface.ROTATION_270:
                    sx = rawY;
                    break;
                default:
                    break;
            }
        }
        // low-pass filter to ignore hand tremor, then a dead zone so noise does not move the dots
        final float a = Math.min(1f, dt * 9f);
        filtX += (sx - filtX) * a;
        filtY += (forward - filtY) * a;
        // road bumps: movement along gravity makes the dots hop, which is what the eyes expect from a vehicle
        filtBump += (along - filtBump) * Math.min(1f, dt * 18f);
        final float dead = 0.12f;
        final float fx = Math.abs(filtX) < dead ? 0f : filtX - Math.signum(filtX) * dead;
        final float fy = Math.abs(filtY) < dead ? 0f : filtY - Math.signum(filtY) * dead;
        final float k = dp(11) * (0.75f + 0.6f * NullgramConfig.motionSens());
        final float max = dp(58);
        // soft saturation instead of a hard clamp
        out[0] = max * (float) Math.tanh(-fx * k / max);
        out[1] = max * (float) Math.tanh(fy * k / max) + dp(26) * (float) Math.tanh(-filtBump * 0.55f);
        final float mag = (float) Math.sqrt(fx * fx + fy * fy) + Math.abs(filtBump) * 0.5f;
        energy = Math.max(energy * 0.985f, mag);
    }

    private final float[] cueTarget = new float[2];

    private float getTopInsetForCues() {
        return 0f;
    }

    private void drawCueDot(Canvas canvas, int idx, float homeX, float homeY, float[] target, float dt, float radius, int fill, int ring) {
        // spring with a different stiffness per dot: the field trails the motion instead of moving as one sheet
        final float stiff = 70f + (idx % 7) * 14f;
        final float damp = 11f + (idx % 5) * 1.2f;
        dvx[idx] += ((target[0]) - dpx[idx]) * stiff * dt - dvx[idx] * damp * dt;
        dvy[idx] += ((target[1]) - dpy[idx]) * stiff * dt - dvy[idx] * damp * dt;
        dpx[idx] += dvx[idx] * dt;
        dpy[idx] += dvy[idx] * dt;
        final float x = homeX + dpx[idx], y = homeY + dpy[idx];
        dotFill.setColor(fill);
        dotFill.setAlpha((int) (235 * cueAlpha));
        dotRing.setColor(ring);
        dotRing.setAlpha((int) (170 * cueAlpha));
        canvas.drawCircle(x, y, radius, dotFill);
        canvas.drawCircle(x, y, radius, dotRing);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void buildSprites(boolean dark) {
        final int base = dark ? 0xFFFFFFFF : 0xFF8FB4E8;
        final int[] sizes = {24, 40, 64};
        for (int t = 0; t < sprites.length; t++) {
            if (sprites[t] != null) {
                sprites[t].recycle();
            }
            final int s = sizes[t];
            final Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(b);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (t < 2) {
                // soft round flake
                p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{base, (base & 0x00FFFFFF) | 0x99000000, base & 0x00FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(s / 2f, s / 2f, s / 2f, p);
            } else {
                // six-arm crystal
                p.setColor(base);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(s / 14f);
                final float cx = s / 2f, cy = s / 2f, r = s / 2f - s / 12f;
                for (int a = 0; a < 3; a++) {
                    final double ang = Math.PI * a / 3.0;
                    final float dx = (float) (Math.cos(ang) * r), dy = (float) (Math.sin(ang) * r);
                    c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, p);
                    // little branches on each arm
                    for (int sign = -1; sign <= 1; sign += 2) {
                        final float bx = cx + dx * 0.55f * sign, by = cy + dy * 0.55f * sign;
                        for (int side = -1; side <= 1; side += 2) {
                            final double ba = ang + side * Math.PI / 3.2;
                            c.drawLine(bx, by, bx + (float) Math.cos(ba) * r * 0.28f * sign, by + (float) Math.sin(ba) * r * 0.28f * sign, p);
                        }
                    }
                }
            }
            sprites[t] = b;
        }
        spritesDark = dark;
        spritesReady = true;
    }

    private void initFlake(int i, boolean randomY) {
        final int w = Math.max(getWidth(), 1), h = Math.max(getHeight(), 1);
        fx[i] = RANDOM.nextFloat() * w;
        fy[i] = randomY ? RANDOM.nextFloat() * h : -dp(8);
        final float roll = RANDOM.nextFloat();
        // mostly tiny crisp dots, some bigger ones, a rare crystal
        final int t = roll < 0.04f ? 2 : (roll < 0.22f ? 1 : 0);
        ftype[i] = t;
        final float depth = RANDOM.nextFloat();
        fr[i] = t == 2 ? dp(4.5f) : (t == 1 ? dp(2.6f) + depth * dp(1.3f) : dp(0.8f) + depth * dp(1.5f));
        fspeed[i] = dp(20 + depth * 42);
        fsway[i] = dp(5 + RANDOM.nextFloat() * 14);
        fphase[i] = RANDOM.nextFloat() * 6.2831f;
        falpha[i] = 0.5f + 0.5f * depth;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = System.nanoTime();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        boolean animate = false;
        cueIdle = false;

        if (NullgramConfig.snow()) {
            final boolean dark = Theme.isCurrentThemeDark();
            if (!spritesReady || dark != spritesDark) {
                buildSprites(dark);
            }
            if (!snowInit && getWidth() > 0) {
                for (int i = 0; i < FLAKES; i++) {
                    initFlake(i, true);
                }
                snowInit = true;
            }
            wind = wind * 0.995f + (RANDOM.nextFloat() - 0.5f) * 0.25f;
            final int w = getWidth(), h = getHeight();
            for (int i = 0; i < FLAKES; i++) {
                fy[i] += fspeed[i] * dt;
                fphase[i] += dt * 1.3f;
                final float x = fx[i] + (float) Math.sin(fphase[i]) * fsway[i] + wind * 6f;
                if (fy[i] > h + dp(20)) {
                    initFlake(i, false);
                }
                if (ftype[i] == 2) {
                    rect.set(x - fr[i], fy[i] - fr[i], x + fr[i], fy[i] + fr[i]);
                    paint.setAlpha((int) (255 * falpha[i]));
                    canvas.drawBitmap(sprites[2], null, rect, paint);
                } else {
                    dotPaint.setColor(dark ? 0xFFFFFFFF : 0xFF8FB4E8);
                    dotPaint.setAlpha((int) (255 * falpha[i]));
                    canvas.drawCircle(x, fy[i], fr[i], dotPaint);
                }
                if (x < -dp(40) || x > w + dp(40)) {
                    initFlake(i, false);
                }
            }
            animate = true;
        }

        if (NullgramConfig.motionCues()) {
            final long nowMs = System.currentTimeMillis();
            final float cdt = lastCueFrame == 0 ? 0.016f : Math.min(0.05f, (nowMs - lastCueFrame) / 1000f);
            lastCueFrame = nowMs;
            final float[] tgt = cueTarget;
            computeCueTarget(tgt, cdt);
            if (energy > 0.3f) {
                lastMotionAt = nowMs;
            }
            // "auto" shows the dots only while the phone is really moving, fading in and out
            final float wantAlpha = NullgramConfig.motionAlways() || nowMs - lastMotionAt < 6000 ? 1f : 0f;
            cueAlpha += (wantAlpha - cueAlpha) * Math.min(1f, cdt * (wantAlpha > cueAlpha ? 6f : 2.2f));
            cueIdle = wantAlpha == 0f && cueAlpha <= 0.02f;
            if (cueAlpha > 0.02f) {
                final int w = getWidth(), h = getHeight();
                final boolean dark = Theme.isCurrentThemeDark();
                final int fill = dark ? 0xFFFFFFFF : 0xFF2B2B2B;
                final int ring = dark ? 0xFF000000 : 0xFFFFFFFF;
                int idx = 0;
                // left and right edges, 9 dots each, alternating sizes
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 11; i++, idx++) {
                        final float homeX = side == 0 ? dp(10) : w - dp(10);
                        final float homeY = h * (i + 1f) / 12f;
                        drawCueDot(canvas, idx, homeX, homeY, tgt, cdt, dp(i % 2 == 0 ? 5.2f : 3.8f), fill, ring);
                    }
                }
                // top and bottom edges, 5 dots each, smaller
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 6; i++, idx++) {
                        final float homeX = w * (i + 1f) / 7f;
                        final float homeY = side == 0 ? dp(10) + getTopInsetForCues() : h - dp(10);
                        drawCueDot(canvas, idx, homeX, homeY, tgt, cdt, dp(3.6f), fill, ring);
                    }
                }
            }
            animate = true;
        }

        if (animate) {
            if (cueIdle && !NullgramConfig.snow()) {
                // nothing is moving: wake up a few times a second instead of every frame
                postInvalidateDelayed(120);
            } else {
                postInvalidateOnAnimation();
            }
        }
    }
}
