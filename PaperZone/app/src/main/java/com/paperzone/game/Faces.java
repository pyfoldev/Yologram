package com.paperzone.game;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Рисование голов персонажей (10 штук) в локальных координатах [-50..50]. */
final class Faces {
    private Faces() {}
    static final int INK = 0xFF1B1F33;
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();
    private static final Path PA = new Path();
    private static int B, L, D;

    static int mix(int a, int b, float t) {
        return Color.argb(255, Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static void fill(int col) { P.setStyle(Paint.Style.FILL); P.setShader(null); P.setColor(col); }
    private static void stk(float w, int col) {
        P.setStyle(Paint.Style.STROKE); P.setShader(null); P.setStrokeWidth(w); P.setColor(col);
        P.setStrokeCap(Paint.Cap.ROUND); P.setStrokeJoin(Paint.Join.ROUND);
    }
    private static void circ(Canvas c, float x, float y, float r, int col) { fill(col); c.drawCircle(x, y, r, P); }
    private static void rr(Canvas c, float l, float t, float r, float b, float rad, int col) {
        fill(col); R.set(l, t, r, b); c.drawRoundRect(R, rad, rad, P);
    }
    private static void oval(Canvas c, float cx, float cy, float rx, float ry, float rot, int col) {
        fill(col); c.save(); c.rotate(rot, cx, cy); R.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(R, P); c.restore();
    }
    private static void tri(Canvas c, float ax, float ay, float bx, float by, float cx, float cy, int col) {
        PA.reset(); PA.moveTo(ax, ay); PA.lineTo(bx, by); PA.lineTo(cx, cy); PA.close(); fill(col); c.drawPath(PA, P);
    }
    private static void line(Canvas c, float ax, float ay, float bx, float by, float w, int col) {
        stk(w, col); c.drawLine(ax, ay, bx, by, P);
    }
    private static void eye(Canvas c, float x, float y, float r, float lx, float ly) {
        circ(c, x, y, r, Color.WHITE);
        circ(c, x + lx * r * .38f, y + ly * r * .38f, r * .58f, INK);
        circ(c, x + lx * r * .38f - r * .18f, y + ly * r * .38f - r * .2f, r * .2f, Color.WHITE);
    }
    private static void smile(Canvas c, float cx, float cy, float r, float w, int col) {
        stk(w, col); R.set(cx - r, cy - r, cx + r, cy + r); c.drawArc(R, 36, 108, false, P);
    }
    private static void body(Canvas c, boolean ext) {
        if (ext) rr(c, -50, -34, 50, 66, 24, mix(D, Color.BLACK, .25f));
        P.setStyle(Paint.Style.FILL); P.setShader(new LinearGradient(0, -50, 0, 50, L, B, Shader.TileMode.CLAMP));
        R.set(-50, -50, 50, 50); c.drawRoundRect(R, 24, 24, P); P.setShader(null);
        stk(6, D); c.drawRoundRect(R, 24, 24, P);
    }

    /** base — цвет головы, light/dark — оттенки; (lx,ly) — куда смотрят глаза. */
    static void draw(Canvas c, int skin, float cx, float cy, float size, int base, int light, int dark, float lx, float ly) {
        B = base; L = light; D = dark;
        c.save(); c.translate(cx, cy); float k = size / 100f; c.scale(k, k);
        switch (skin) {
            case 0:
                body(c, true); eye(c, -20, -8, 14, lx, ly); eye(c, 20, -8, 14, lx, ly); smile(c, 0, 12, 16, 6, mix(D, Color.BLACK, .3f)); break;
            case 1:
                tri(c, -48, -22, -44, -66, -10, -48, D); tri(c, 48, -22, 44, -66, 10, -48, D);
                tri(c, -42, -30, -40, -54, -22, -44, 0xFFFFB3C7); tri(c, 42, -30, 40, -54, 22, -44, 0xFFFFB3C7);
                body(c, true); eye(c, -21, -8, 13, lx, ly); eye(c, 21, -8, 13, lx, ly);
                tri(c, -7, 10, 7, 10, 0, 18, 0xFFFF7D9E);
                PA.reset(); PA.moveTo(0, 18); PA.quadTo(-8, 28, -15, 22); PA.moveTo(0, 18); PA.quadTo(8, 28, 15, 22);
                stk(4, mix(D, Color.BLACK, .3f)); c.drawPath(PA, P);
                line(c, -52, 8, -30, 12, 3, 0x59000000); line(c, -52, 20, -30, 18, 3, 0x59000000);
                line(c, 52, 8, 30, 12, 3, 0x59000000); line(c, 52, 20, 30, 18, 3, 0x59000000); break;
            case 2:
                line(c, 0, -50, 0, -70, 6, D); circ(c, 0, -74, 9, 0xFFFF5A5A);
                body(c, true); rr(c, -38, -26, 38, 10, 13, 0xFF1C2236);
                circ(c, -19 + lx * 3, -8 + ly * 3, 10, 0xFF5FF1FF); circ(c, 19 + lx * 3, -8 + ly * 3, 10, 0xFF5FF1FF);
                circ(c, -22 + lx * 3, -12 + ly * 3, 3.5f, Color.WHITE); circ(c, 16 + lx * 3, -12 + ly * 3, 3.5f, Color.WHITE);
                rr(c, -22, 20, 22, 35, 6, 0xFF1C2236);
                line(c, -11, 22, -11, 33, 3, 0xFF8C96B8); line(c, 0, 22, 0, 33, 3, 0xFF8C96B8); line(c, 11, 22, 11, 33, 3, 0xFF8C96B8); break;
            case 3: {
                PA.reset(); PA.moveTo(-46, 42); PA.lineTo(-46, -4); R.set(-46, -50, 46, 42); PA.arcTo(R, 180, 180); PA.lineTo(46, 42);
                PA.quadTo(34, 56, 23, 42); PA.quadTo(11, 56, 0, 42); PA.quadTo(-11, 56, -23, 42); PA.quadTo(-34, 56, -46, 42); PA.close();
                P.setStyle(Paint.Style.FILL); P.setShader(new LinearGradient(0, -50, 0, 50, Color.WHITE, mix(B, Color.WHITE, .75f), Shader.TileMode.CLAMP));
                c.drawPath(PA, P); stk(6, B); c.drawPath(PA, P);
                oval(c, -17 + lx * 3, -8 + ly * 3, 8, 12, 0, INK); oval(c, 17 + lx * 3, -8 + ly * 3, 8, 12, 0, INK);
                circ(c, -14 + lx * 3, -13 + ly * 3, 3, Color.WHITE); circ(c, 20 + lx * 3, -13 + ly * 3, 3, Color.WHITE);
                oval(c, 0, 20, 7, 9, 0, INK); circ(c, -30, 12, 7, 0x73FF6E96); circ(c, 30, 12, 7, 0x73FF6E96); break; }
            case 4:
                line(c, -22, -48, -34, -70, 5, D); circ(c, -35, -73, 8, 0xFFFFF36B); line(c, 22, -48, 34, -70, 5, D); circ(c, 35, -73, 8, 0xFFFFF36B);
                body(c, true);
                oval(c, -22 + lx * 2, -2 + ly * 2, 15, 25, -29, 0xFF12151F); oval(c, 22 + lx * 2, -2 + ly * 2, 15, 25, 29, 0xFF12151F);
                circ(c, -26 + lx * 2, -12 + ly * 2, 4.5f, Color.WHITE); circ(c, 18 + lx * 2, -12 + ly * 2, 4.5f, Color.WHITE);
                PA.reset(); PA.moveTo(-10, 34); PA.quadTo(0, 40, 10, 34); stk(4, mix(D, Color.BLACK, .3f)); c.drawPath(PA, P); break;
            case 5:
                circ(c, -38, -42, 19, INK); circ(c, 38, -42, 19, INK);
                body(c, true); oval(c, 0, 4, 40, 38, 0, Color.WHITE);
                oval(c, -19, -2, 11, 15, 20, INK); oval(c, 19, -2, 11, 15, -20, INK);
                circ(c, -19 + lx * 2, -3 + ly * 2, 5.5f, Color.WHITE); circ(c, 19 + lx * 2, -3 + ly * 2, 5.5f, Color.WHITE);
                circ(c, -19 + lx * 3, -3 + ly * 3, 2.6f, INK); circ(c, 19 + lx * 3, -3 + ly * 3, 2.6f, INK);
                oval(c, 0, 16, 9, 6, 0, INK);
                PA.reset(); PA.moveTo(0, 21); PA.quadTo(-9, 32, -16, 27); PA.moveTo(0, 21); PA.quadTo(9, 32, 16, 27); stk(3.5f, INK); c.drawPath(PA, P); break;
            case 6:
                tri(c, -48, -20, -42, -68, -8, -48, D); tri(c, 48, -20, 42, -68, 8, -48, D);
                tri(c, -42, -30, -39, -56, -22, -44, Color.WHITE); tri(c, 42, -30, 39, -56, 22, -44, Color.WHITE);
                body(c, true);
                c.save(); PA.reset(); R.set(-50, -50, 50, 50); PA.addRoundRect(R, 24, 24, Path.Direction.CW); c.clipPath(PA);
                tri(c, 0, 56, -60, 0, 60, 0, Color.WHITE); c.restore();
                stk(6, D); R.set(-50, -50, 50, 50); c.drawRoundRect(R, 24, 24, P);
                oval(c, -21 + lx * 2, -12 + ly * 2, 8, 6, -17, INK); oval(c, 21 + lx * 2, -12 + ly * 2, 8, 6, 17, INK);
                circ(c, 0, 26, 7, INK); break;
            case 7:
                tri(c, -34, -44, -26, -70, -12, -46, Color.WHITE); tri(c, 34, -44, 26, -70, 12, -46, Color.WHITE);
                body(c, true); eye(c, lx * 2, -12 + ly * 2, 25, lx * 1.4f, ly * 1.4f);
                rr(c, -32, 22, 32, 42, 8, 0xFF3A1226);
                tri(c, -22, 22, -6, 22, -14, 33, Color.WHITE); tri(c, -4, 22, 12, 22, 4, 33, Color.WHITE); tri(c, 14, 22, 30, 22, 22, 33, Color.WHITE); break;
            case 8:
                body(c, true); rr(c, -42, -24, -6, 4, 9, 0xFF12151F); rr(c, 6, -24, 42, 4, 9, 0xFF12151F);
                fill(0xFF12151F); c.drawRect(-8, -18, 8, -12, P);
                line(c, -42, -16, -50, -20, 5, 0xFF12151F); line(c, 42, -16, 50, -20, 5, 0xFF12151F);
                line(c, -34, -16, -24, -20, 4, 0xB3FFFFFF); line(c, 14, -16, 24, -20, 4, 0xB3FFFFFF);
                PA.reset(); PA.moveTo(-18, 24); PA.quadTo(2, 36, 22, 18); stk(6, mix(D, Color.BLACK, .3f)); c.drawPath(PA, P); break;
            default:
                PA.reset(); PA.moveTo(-34, -48); PA.lineTo(-40, -78); PA.lineTo(-18, -62); PA.lineTo(0, -84); PA.lineTo(18, -62); PA.lineTo(40, -78); PA.lineTo(34, -48); PA.close();
                fill(0xFFFFCF33); c.drawPath(PA, P); stk(5, 0xFFC98A00); c.drawPath(PA, P);
                circ(c, 0, -62, 5.5f, 0xFFFF4D5E); circ(c, -22, -57, 4, 0xFF3D9BFF); circ(c, 22, -57, 4, 0xFF8BD94F);
                body(c, true); eye(c, -20, -4, 13, lx, ly); eye(c, 20, -4, 13, lx, ly); smile(c, 0, 8, 18, 6, mix(D, Color.BLACK, .3f));
                PA.reset(); PA.moveTo(-22, 8); PA.quadTo(-10, 2, 0, 8); PA.quadTo(10, 2, 22, 8); stk(4, 0x66000000); c.drawPath(PA, P);
        }
        c.restore();
    }
}
