package com.paperzone.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Locale;

public class GameView extends View {
    // ---------- данные ----------
    static final int[] BASE = {0xFFFF8FB1, 0xFFFF7A7A, 0xFFFFB066, 0xFFFFD966, 0xFFB5E86B, 0xFF6FDC9A,
            0xFF5FD3D3, 0xFF6FB7FF, 0xFF8C96FF, 0xFFC08CFF, 0xFFFF8CD9, 0xFF9FB0C7};
    static final String[] SKIN_NAMES = {"Кубик", "Кот", "Робот", "Призрак", "Пришелец", "Панда", "Лис", "Монстр", "Крутой", "Король"};
    static final String[] NAMES = {"Нова", "Блиц", "Рекс", "Мила", "Тень", "Волк", "Искра", "Ярик", "Зевс", "Лиса", "Дымка", "Борз",
            "Орёл", "Пикси", "Гром", "Алиса", "Макс", "Стрела", "Ураган", "Мишка", "Фокс", "Кай", "Вега", "Тигр", "Снайпер", "Кроха"};
    static final String[] LEVEL_TAGS = {"новички", "лёгкие", "средние", "средние+", "хитрые", "опасные", "сильные", "очень сильные", "мастера", "легенды"};

    static class Lvl {
        final float size; final int bots; final float skill, speed; final int target; final float t3, t2;
        Lvl(float size, int bots, float skill, float speed, int target, float t3, float t2) {
            this.size = size; this.bots = bots; this.skill = skill; this.speed = speed; this.target = target; this.t3 = t3; this.t2 = t2;
        }
    }
    static final Lvl[] LEVELS = {
            new Lvl(36, 3, .10f, .78f, 10, 50, 100), new Lvl(42, 4, .22f, .82f, 12, 70, 140), new Lvl(48, 5, .34f, .86f, 15, 95, 190),
            new Lvl(54, 5, .46f, .89f, 18, 120, 240), new Lvl(60, 6, .56f, .92f, 20, 145, 290), new Lvl(66, 7, .66f, .94f, 22, 170, 340),
            new Lvl(72, 7, .75f, .96f, 25, 195, 390), new Lvl(78, 8, .84f, .98f, 27, 220, 440), new Lvl(84, 9, .92f, 1f, 30, 250, 500),
            new Lvl(96, 10, 1f, 1f, 33, 290, 580)};

    // цвета по индексам палитры
    final int[] cTerr = new int[BASE.length], cTrail = new int[BASE.length], cTrailEdge = new int[BASE.length],
            cLight = new int[BASE.length], cDark = new int[BASE.length];

    // ---------- состояние ----------
    enum Scr { MENU, LEVELS, CHARS, GAME }
    Scr scr = Scr.MENU;
    final Activity act; final SharedPreferences sp;
    int skin, color, unlocked = 1; String name; boolean sound = true; float bestPct; int bestKills;
    final int[] stars = new int[10];

    final float d; // плотность
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), bmpP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final RectF rf = new RectF(); final Path path = new Path();
    long last = System.nanoTime(); float t;

    // кнопки (немедленный режим)
    static class Btn { RectF r; Runnable a; }
    ArrayList<Btn> btns = new ArrayList<>(), prev = new ArrayList<>(); int pressed = -1;

    // игра
    Engine eng; Lvl lvl; int lvlIdx = -1; boolean endless;
    Bitmap tbmp; int[] pix;
    float camX, camY, zoom = 14f; boolean over, paused; float endT; boolean endShown; Engine.Ent killer;
    float maxPct, hudMsgT; String hudMsg = "";
    boolean jActive; int jId = -1; float jx0, jy0, jx, jy; boolean started;
    final ArrayList<float[]> parts = new ArrayList<>(); final ArrayList<Object[]> floats = new ArrayList<>();
    Dlg dlg;
    static class Dlg { String title; String[] lines; int stars = -1; String[] labels; int[] colors; Runnable[] acts; }
    float shake;

    // звук
    AudioTrack[] snd = new AudioTrack[4];

    public GameView(Activity a) {
        super(a);
        act = a; d = a.getResources().getDisplayMetrics().density;
        sp = a.getSharedPreferences("paperzone", Context.MODE_PRIVATE);
        loadSave();
        for (int i = 0; i < BASE.length; i++) {
            cTerr[i] = Faces.mix(BASE[i], Color.WHITE, .10f); cTrail[i] = Faces.mix(BASE[i], Color.WHITE, .62f);
            cTrailEdge[i] = Faces.mix(BASE[i], Color.WHITE, .35f); cLight[i] = Faces.mix(BASE[i], Color.WHITE, .35f);
            cDark[i] = Faces.mix(BASE[i], Color.BLACK, .28f);
        }
        tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextAlign(Paint.Align.CENTER);
        setKeepScreenOn(true);
        initSounds();
    }

    // ---------- сохранение ----------
    void loadSave() {
        skin = sp.getInt("skin", 0); color = sp.getInt("color", 7); unlocked = sp.getInt("unlocked", 1);
        name = sp.getString("name", "Игрок"); sound = sp.getBoolean("sound", true);
        bestPct = sp.getFloat("bestPct", 0); bestKills = sp.getInt("bestKills", 0);
        String[] s = sp.getString("stars", "").split(",");
        for (int i = 0; i < 10 && i < s.length; i++) try { stars[i] = Integer.parseInt(s[i].trim()); } catch (Exception e) { /* пусто */ }
    }
    void save() {
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < 10; i++) sb.append(i > 0 ? "," : "").append(stars[i]);
        sp.edit().putInt("skin", skin).putInt("color", color).putInt("unlocked", unlocked).putString("name", name).putBoolean("sound", sound)
                .putFloat("bestPct", bestPct).putInt("bestKills", bestKills).putString("stars", sb.toString()).apply();
    }

    // ---------- звук ----------
    void initSounds() {
        try {
            snd[0] = tone(new float[]{520, 780}, .09f);   // захват
            snd[1] = tone(new float[]{330, 660}, .1f);    // убийство
            snd[2] = tone(new float[]{300, 200, 120}, .16f); // смерть
            snd[3] = tone(new float[]{523, 659, 784, 1046}, .13f); // победа
        } catch (Throwable e) { /* звук необязателен */ }
    }
    AudioTrack tone(float[] freqs, float segSec) {
        int rate = 22050, n = (int) (rate * segSec), total = n * freqs.length; short[] buf = new short[total];
        for (int f = 0; f < freqs.length; f++) for (int i = 0; i < n; i++) {
            float env = 1f - (float) i / n; buf[f * n + i] = (short) (Math.sin(2 * Math.PI * freqs[f] * i / rate) * 7000 * env);
        }
        AudioTrack a = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                total * 2, AudioTrack.MODE_STATIC);
        a.write(buf, 0, total); return a;
    }
    void play(int i) {
        if (!sound || snd[i] == null) return;
        try { snd[i].stop(); snd[i].setPlaybackHeadPosition(0); snd[i].play(); } catch (Throwable e) { /* ignore */ }
    }

    // ---------- старт игры ----------
    void startGame(boolean endlessMode, int li) {
        endless = endlessMode; lvlIdx = li; lvl = endless ? null : LEVELS[li];
        Engine.Config c = new Engine.Config();
        c.names = NAMES; c.paletteSize = BASE.length; c.playerColor = color; c.playerSkin = skin; c.playerName = name.trim().isEmpty() ? "Игрок" : name.trim();
        if (endless) { c.size = 90; c.bots = 11; c.skill0 = .35f; c.skill1 = 1f; c.ramp = 420; c.speed0 = .82f; c.speed1 = 1f; }
        else { c.size = lvl.size; c.bots = lvl.bots; c.skill0 = c.skill1 = lvl.skill; c.speed0 = c.speed1 = lvl.speed; }
        eng = new Engine(c);
        tbmp = Bitmap.createBitmap(eng.W, eng.H, Bitmap.Config.ARGB_8888); pix = new int[eng.W * eng.H];
        eng.events.clear();
        camX = eng.player.x; camY = eng.player.y; zoom = 14f; over = paused = endShown = false; dlg = null; started = false; maxPct = 0;
        parts.clear(); floats.clear(); jActive = false; shake = 0; hudMsgT = 0;
        refreshBitmap(true); scr = Scr.GAME; btns.clear(); prev.clear();
    }

    // ---------- bitmap территории ----------
    int ow(int x, int y) { return (x < 0 || y < 0 || x >= eng.W || y >= eng.H) ? 0 : eng.owner[y * eng.W + x] & 0xFF; }
    void refreshBitmap(boolean full) {
        int W = eng.W, H = eng.H, x0, y0, x1, y1;
        if (full || eng.fullDirty) { x0 = 0; y0 = 0; x1 = W - 1; y1 = H - 1; }
        else if (eng.dx1 < 0) return;
        else { x0 = Math.max(0, eng.dx0 - 6); y0 = Math.max(0, eng.dy0 - 6); x1 = Math.min(W - 1, eng.dx1 + 6); y1 = Math.min(H - 1, eng.dy1 + 6); }
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int o = eng.owner[y * W + x] & 0xFF, col = 0;
            if (o != 0) {
                int ci = eng.ents[o - 1].color % BASE.length; col = cTerr[ci];
                if (ow(x, y + 1) != o || ow(x, y + 2) != o || ow(x, y + 3) != o || ow(x, y + 4) != o) col = cDark[ci];
                else if (ow(x, y - 1) != o) col = cLight[ci];
                else if (((x * 73856093) ^ (y * 19349663)) % 131 == 0) col = Faces.mix(col, Color.WHITE, .35f);
            }
            pix[y * W + x] = col;
        }
        tbmp.setPixels(pix, y0 * W + x0, W, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        eng.clearDirty();
    }

    // ---------- ввод ----------
    @Override public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked(); float x = ev.getX(ev.getActionIndex()), y = ev.getY(ev.getActionIndex());
        boolean ui = scr != Scr.GAME || dlg != null;
        if (ui) {
            if (a == MotionEvent.ACTION_DOWN) { pressed = hit(x, y); }
            else if (a == MotionEvent.ACTION_UP) { int h = hit(x, y); if (h >= 0 && h == pressed) { Runnable r = prev.get(h).a; pressed = -1; r.run(); } pressed = -1; }
            else if (a == MotionEvent.ACTION_CANCEL) pressed = -1;
            return true;
        }
        // игра
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            if (x > getWidth() - 62 * d && y > getHeight() - 70 * d) { pause(); return true; }
            if (!jActive) { jActive = true; jId = ev.getPointerId(ev.getActionIndex()); jx0 = jx = x; jy0 = jy = y; }
        } else if (a == MotionEvent.ACTION_MOVE && jActive) {
            int i = ev.findPointerIndex(jId); if (i < 0) return true;
            jx = ev.getX(i); jy = ev.getY(i);
            float dx = jx - jx0, dy = jy - jy0, len = (float) Math.hypot(dx, dy);
            if (len > 5 * d && !over) {
                eng.player.tAng = (float) Math.atan2(dy, dx); eng.player.hasT = true;
                if (!eng.started) eng.start();
                started = true;
            }
            float maxL = 36 * d; if (len > maxL) { jx0 = jx - dx / len * maxL; jy0 = jy - dy / len * maxL; }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || a == MotionEvent.ACTION_POINTER_UP) {
            if (ev.getPointerId(ev.getActionIndex()) == jId) jActive = false;
        }
        return true;
    }
    int hit(float x, float y) { for (int i = prev.size() - 1; i >= 0; i--) if (prev.get(i).r.contains(x, y)) return i; return -1; }

    boolean onBack() {
        if (scr == Scr.GAME) {
            if (dlg != null) { if (paused) resume(); else toMenu(); return true; }
            pause(); return true;
        }
        if (scr == Scr.LEVELS || scr == Scr.CHARS) { scr = Scr.MENU; return true; }
        return false;
    }
    void pauseIfPlaying() { if (scr == Scr.GAME && dlg == null && !over) pause(); }
    void pause() {
        if (over || dlg != null) return;
        paused = true;
        dialog(endless ? "Пауза" : "Уровень " + (lvlIdx + 1), new String[]{endless ? "Бесконечный режим" : "Цель: " + lvl.target + "% поля"}, -1,
                new String[]{"Продолжить", "Заново", "В меню"}, new int[]{0xFF4CD964, 0xFFFFA63D, 0xFF8A94B8},
                new Runnable[]{this::resume, () -> startGame(endless, lvlIdx), this::toMenu});
    }
    void resume() { paused = false; dlg = null; last = System.nanoTime(); }
    void toMenu() { dlg = null; paused = false; scr = Scr.MENU; eng = null; }

    void dialog(String title, String[] lines, int st, String[] labels, int[] colors, Runnable[] acts) {
        Dlg g = new Dlg(); g.title = title; g.lines = lines; g.stars = st; g.labels = labels; g.colors = colors; g.acts = acts; dlg = g;
    }

    // ---------- обновление игры ----------
    void updateGame(float dt) {
        if (dlg != null && paused) return;
        eng.update(dt);
        for (Engine.Event ev : eng.events) handle(ev);
        eng.events.clear();
        refreshBitmap(false);
        Engine.Ent pl = eng.player;
        float pct = eng.pct(pl); maxPct = Math.max(maxPct, pct);
        if (!over && !endless && pl.alive && pct >= lvl.target) {
            over = true; endT = .7f; endShown = false; play(3);
        }
        float areaU = eng.cnt[pl.id] / (float) (Engine.RES * Engine.RES);
        float tz = Math.max(12.5f, Math.min(24f, 12f + (float) Math.sqrt(Math.max(areaU, 20)) * .62f));
        zoom += (tz - zoom) * Math.min(1, dt * 1.2f);
        float k = Math.min(1, dt * 7f); camX += (pl.x - camX) * k; camY += (pl.y - camY) * k;
        for (int i = parts.size() - 1; i >= 0; i--) {
            float[] q = parts.get(i); q[6] -= dt; if (q[6] <= 0) { parts.remove(i); continue; }
            q[0] += q[2] * dt; q[1] += q[3] * dt; q[3] += 9 * dt; q[4] += q[5] * dt;
        }
        for (int i = floats.size() - 1; i >= 0; i--) { Object[] f = floats.get(i); float life = (float) f[3] - dt; f[3] = life; f[2] = (float) f[2] - dt * 1.2f; if (life <= 0) floats.remove(i); }
        shake = Math.max(0, shake - dt * 2.5f); hudMsgT -= dt;
        if (over) { endT -= dt; if (endT <= 0 && !endShown) { endShown = true; if (pl.alive) showWin(); else showDefeat(); } }
    }

    void handle(Engine.Event ev) {
        Engine.Ent pl = eng.player;
        if (ev.type == Engine.EV_DEATH) {
            int ci = ev.e.color % BASE.length;
            for (int i = 0; i < 26; i++) {
                float a = (float) (Math.random() * 6.283), sp = 1.5f + (float) Math.random() * 5f;
                parts.add(new float[]{ev.x, ev.y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - 2, (float) Math.random() * 6, (float) (Math.random() - .5) * 12,
                        .6f + (float) Math.random() * .6f, i % 2 == 0 ? ci : ci + 100});
            }
            if (ev.e == pl && !over) { over = true; endT = 1.1f; endShown = false; killer = ev.other; shake = 1; play(2); }
            else if (ev.other == pl) { play(1); hudMsg = "Ты съел " + ev.e.name + "!"; hudMsgT = 1.6f; }
        } else if (ev.type == Engine.EV_CAPTURE && ev.e == pl && ev.amount > 40) {
            play(0); float pc = ev.amount * 100f / (eng.W * eng.H);
            floats.add(new Object[]{pl.x, pl.y - 1.2f, 0f, 1.1f, String.format(Locale.US, pc < 10 ? "+%.1f%%" : "+%.0f%%", pc)});
        }
    }

    String fmtT(float s) { return (int) (s / 60) + ":" + String.format(Locale.US, "%02d", (int) s % 60); }

    void showDefeat() {
        Engine.Ent pl = eng.player; float sec = eng.time;
        String l1 = killer != null ? "Тебя съел " + killer.name : "Ты разбился";
        String l2 = String.format(Locale.US, "Макс. территория: %.1f%%", maxPct), l3 = "Убито: " + pl.kills + "   Время: " + fmtT(sec);
        String[] lines;
        if (endless) {
            boolean rec = false; if (maxPct > bestPct) { bestPct = Math.round(maxPct * 10) / 10f; rec = true; } if (pl.kills > bestKills) { bestKills = pl.kills; rec = true; }
            if (rec) save();
            lines = new String[]{l1, l2, l3, String.format(Locale.US, "Рекорд: %.1f%% · убийств %d%s", bestPct, bestKills, rec ? "  — новый!" : "")};
        } else lines = new String[]{l1, l2, l3};
        dialog("Ты проиграл", lines, -1, new String[]{"Заново", "В меню"}, new int[]{0xFF4CD964, 0xFF8A94B8},
                new Runnable[]{() -> startGame(endless, lvlIdx), this::toMenu});
    }

    void showWin() {
        Engine.Ent pl = eng.player; float sec = eng.time; int st = sec <= lvl.t3 ? 3 : sec <= lvl.t2 ? 2 : 1; int li = lvlIdx;
        if (stars[li] < st) stars[li] = st; if (li + 1 < LEVELS.length && unlocked < li + 2) unlocked = li + 2; save();
        ArrayList<String> lb = new ArrayList<>(); ArrayList<Integer> lc = new ArrayList<>(); ArrayList<Runnable> la = new ArrayList<>();
        if (li + 1 < LEVELS.length) { lb.add("Следующий уровень"); lc.add(0xFF4CD964); la.add(() -> startGame(false, li + 1)); }
        lb.add("Ещё раз"); lc.add(0xFF4AA8FF); la.add(() -> startGame(false, li));
        lb.add("В меню"); lc.add(0xFF8A94B8); la.add(this::toMenu);
        int[] cols = new int[lc.size()]; for (int i = 0; i < cols.length; i++) cols[i] = lc.get(i);
        dialog(li + 1 == LEVELS.length ? "Ты чемпион!" : "Уровень пройден!",
                new String[]{String.format(Locale.US, "Захвачено: %.1f%%", eng.pct(pl)), "Время: " + fmtT(sec) + "   Убито: " + pl.kills,
                        "3★ быстрее " + fmtT(lvl.t3) + " · 2★ быстрее " + fmtT(lvl.t2)}, st,
                lb.toArray(new String[0]), cols, la.toArray(new Runnable[0]));
    }

    // ================= ОТРИСОВКА =================
    @Override protected void onDraw(Canvas c) {
        long now = System.nanoTime(); float dt = Math.min(.05f, (now - last) / 1e9f); last = now; t += dt;
        prev = btns; btns = new ArrayList<>();
        if (scr == Scr.GAME && eng != null) { updateGame(dt); drawGame(c); if (dlg != null) drawDlg(c); }
        else {
            drawMenuBg(c);
            if (scr == Scr.MENU) drawMenu(c); else if (scr == Scr.LEVELS) drawLevels(c); else drawChars(c);
        }
        postInvalidateOnAnimation();
    }

    // ----- примитивы UI -----
    void text(Canvas c, String s, float x, float y, float sizeDp, int col) {
        tp.setTextSize(sizeDp * d); tp.setStyle(Paint.Style.FILL); tp.setColor(col); tp.setTextAlign(Paint.Align.CENTER); c.drawText(s, x, y, tp);
    }
    void textO(Canvas c, String s, float x, float y, float sizeDp, int col, int outline, float ow) {
        tp.setTextSize(sizeDp * d); tp.setTextAlign(Paint.Align.CENTER);
        tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(ow * d); tp.setStrokeJoin(Paint.Join.ROUND); tp.setColor(outline); c.drawText(s, x, y, tp);
        tp.setStyle(Paint.Style.FILL); tp.setColor(col); c.drawText(s, x, y, tp);
    }
    void button(Canvas c, String label, float l, float tt, float r, float b, int col, Runnable act) {
        Btn bt = new Btn(); bt.r = new RectF(l, tt, r, b); bt.a = act; btns.add(bt);
        boolean pr = pressed >= 0 && pressed < prev.size() && prev.get(pressed).r.equals(bt.r);
        float off = pr ? 5 * d : 0, dep = 6 * d, rad = Math.min((b - tt) * .32f, 22 * d);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(Faces.mix(col, Color.BLACK, .35f));
        rf.set(l, tt + dep, r, b + dep - off); c.drawRoundRect(rf, rad, rad, p);
        p.setShader(new LinearGradient(0, tt + off, 0, b + off, Faces.mix(col, Color.WHITE, .25f), col, Shader.TileMode.CLAMP));
        rf.set(l, tt + off, r, b + off); c.drawRoundRect(rf, rad, rad, p); p.setShader(null);
        float sz = Math.min(20f, (r - l) / d / Math.max(6, label.length()) * 1.9f);
        textO(c, label, (l + r) / 2, (tt + b) / 2 + off + sz * d * .35f, sz, Color.WHITE, Faces.mix(col, Color.BLACK, .4f), 3);
    }
    void star(Canvas c, float cx, float cy, float r, int col) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            float a = (float) (-Math.PI / 2 + i * Math.PI / 5), rr2 = i % 2 == 0 ? r : r * .45f;
            float x = cx + (float) Math.cos(a) * rr2, y = cy + (float) Math.sin(a) * rr2; if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close(); p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(col); c.drawPath(path, p);
    }
    void lock(Canvas c, float cx, float cy, float s) {
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * .16f); p.setColor(0xFF5A6285);
        rf.set(cx - s * .28f, cy - s * .55f, cx + s * .28f, cy + s * .1f); c.drawArc(rf, 180, 180, false, p);
        p.setStyle(Paint.Style.FILL); rf.set(cx - s * .42f, cy - s * .12f, cx + s * .42f, cy + s * .48f); c.drawRoundRect(rf, s * .1f, s * .1f, p);
    }
    void roundBtn(Canvas c, float cx, float cy, float r, int col, Runnable act) {
        Btn bt = new Btn(); bt.r = new RectF(cx - r, cy - r, cx + r, cy + r); bt.a = act; btns.add(bt);
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(Faces.mix(col, Color.BLACK, .3f)); c.drawCircle(cx, cy + 3 * d, r, p);
        p.setColor(col); c.drawCircle(cx, cy, r, p);
    }

    void drawMenuBg(Canvas c) {
        int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL); p.setShader(new LinearGradient(0, 0, w * .3f, h, 0xFF79D6F5, 0xFFC7A4FF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p); p.setShader(null);
        for (int i = 0; i < 12; i++) {
            float s = (30 + (i * 37) % 60) * d, sp = 25 + (i * 13) % 30, x = ((i * 211) % 100) / 100f * w;
            float y = h + s - ((t * sp * d + i * 97 * d) % (h + 2 * s)), rot = t * 20 + i * 40;
            p.setColor((BASE[i % BASE.length] & 0x00FFFFFF) | 0x55000000);
            c.save(); c.rotate(rot, x, y); rf.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2); c.drawRoundRect(rf, s * .25f, s * .25f, p); c.restore();
        }
    }

    void drawMenu(Canvas c) {
        int w = getWidth(), h = getHeight();
        String paper = "PAPER"; int[] lc = {0xFFFF5F7A, 0xFFFFB23D, 0xFFFFE14D, 0xFF7FE65C, 0xFF3FD7E6};
        tp.setTextSize(52 * d); float tw = tp.measureText(paper), x = w / 2f - tw / 2, y = h * .13f;
        for (int i = 0; i < 5; i++) {
            String ch = paper.substring(i, i + 1); float cw = tp.measureText(ch);
            tp.setTextAlign(Paint.Align.LEFT); tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(8 * d); tp.setColor(0x55302070); c.drawText(ch, x, y + 4 * d, tp);
            tp.setStyle(Paint.Style.FILL); tp.setColor(lc[i]); c.drawText(ch, x, y, tp); x += cw;
        }
        textO(c, "ZONE", w / 2f, y + 52 * d, 44, Color.WHITE, 0x66302070, 6);
        float hs = Math.min(w * .38f, h * .19f), cy = h * .335f + (float) Math.sin(t * 3) * 5 * d;
        p.setShader(null); p.setColor(0x33000000); rf.set(w / 2f - hs * .42f, h * .335f + hs * .6f, w / 2f + hs * .42f, h * .335f + hs * .72f); c.drawOval(rf, p);
        Faces.draw(c, skin, w / 2f, cy, hs, BASE[color], cLight[color], cDark[color], (float) Math.cos(t * 1.3), (float) Math.sin(t * 1.7) * .6f);
        float bw = Math.min(w * .84f, 360 * d), bl = (w - bw) / 2, bh = 56 * d, y0 = h * .58f, gap = 15 * d;
        button(c, name, bl + bw * .2f, y0 - 12 * d - 34 * d, bl + bw * .8f, y0 - 12 * d, 0xFF7C6FE0, this::askName);
        button(c, "Бесконечный режим", bl, y0, bl + bw, y0 + bh, 0xFF2FBF55, () -> startGame(true, -1));
        button(c, "Уровни", bl, y0 + bh + gap, bl + bw, y0 + 2 * bh + gap, 0xFF2F8CF0, () -> scr = Scr.LEVELS);
        button(c, "Персонаж и цвет", bl, y0 + 2 * (bh + gap), bl + bw, y0 + 3 * bh + 2 * gap, 0xFF8B4DE8, () -> scr = Scr.CHARS);
        button(c, sound ? "Звук: вкл" : "Звук: выкл", bl + bw * .25f, y0 + 3 * (bh + gap), bl + bw * .75f, y0 + 3 * (bh + gap) + 40 * d, 0xFF6C76A0, () -> { sound = !sound; save(); });
        text(c, "Без рекламы • Без интернета", w / 2f, h - 16 * d, 13, 0xCCFFFFFF);
    }

    void header(Canvas c, String title) {
        int w = getWidth(); float top = 18 * d;
        roundBtn(c, 14 * d + 24 * d, top + 24 * d, 24 * d, 0xFF6C76A0, () -> scr = Scr.MENU);
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(Color.WHITE);
        float bx = 14 * d + 24 * d, by = top + 24 * d; c.drawLine(bx + 4 * d, by - 9 * d, bx - 5 * d, by, p); c.drawLine(bx - 5 * d, by, bx + 4 * d, by + 9 * d, p);
        textO(c, title, w / 2f, top + 34 * d, 28, Color.WHITE, 0x66302070, 5);
    }

    void drawLevels(Canvas c) {
        int w = getWidth(), h = getHeight(); header(c, "Уровни");
        float top = 84 * d, pad = 14 * d, gap = 10 * d, cw = (w - 2 * pad - gap) / 2f, ch = Math.min(100 * d, (h - top - pad - 4 * gap) / 5f);
        for (int i = 0; i < 10; i++) {
            float l = pad + (i % 2) * (cw + gap), tt = top + (i / 2) * (ch + gap); final int li = i; boolean locked = i >= unlocked;
            Btn bt = new Btn(); bt.r = new RectF(l, tt, l + cw, tt + ch); bt.a = () -> { if (li < unlocked) startGame(false, li); }; btns.add(bt);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x33000000); rf.set(l, tt + 5 * d, l + cw, tt + ch + 5 * d); c.drawRoundRect(rf, 18 * d, 18 * d, p);
            p.setColor(locked ? 0xAAB9BFD8 : (i == unlocked - 1 ? 0xFFFFFFFF : 0xEEF4F6FF)); rf.set(l, tt, l + cw, tt + ch); c.drawRoundRect(rf, 18 * d, 18 * d, p);
            if (i == unlocked - 1) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xFF4CD964); c.drawRoundRect(rf, 18 * d, 18 * d, p); }
            text(c, String.valueOf(i + 1), l + 34 * d, tt + ch * .5f + 12 * d, 34, locked ? 0xFF8A90AE : 0xFF3B4290);
            if (locked) lock(c, l + cw - 30 * d, tt + 30 * d, 26 * d);
            else for (int s = 0; s < 3; s++) star(c, l + cw - 82 * d + s * 24 * d, tt + 24 * d, 11 * d, s < stars[i] ? 0xFFFFC21F : 0xFFD6DAEC);
            Lvl L = LEVELS[i];
            text(c, "Цель " + L.target + "% • " + L.bots + " бота", l + cw * .64f, tt + ch - 32 * d, 12, 0xFF5B628F);
            text(c, LEVEL_TAGS[i], l + cw * .64f, tt + ch - 14 * d, 12, 0xFF8A90AE);
        }
    }

    void drawChars(Canvas c) {
        int w = getWidth(), h = getHeight(); header(c, "Персонаж");
        float hs = Math.min(w * .42f, h * .19f), cy = 84 * d + hs * .62f + (float) Math.sin(t * 3) * 5 * d;
        p.setShader(null); p.setColor(0x33000000); rf.set(w / 2f - hs * .42f, cy + hs * .55f, w / 2f + hs * .42f, cy + hs * .66f); c.drawOval(rf, p);
        Faces.draw(c, skin, w / 2f, cy, hs, BASE[color], cLight[color], cDark[color], (float) Math.cos(t * 1.3), (float) Math.sin(t * 1.7) * .6f);
        float y = cy + hs * .85f;
        button(c, name, w * .25f, y, w * .75f, y + 40 * d, 0xFF7C6FE0, this::askName);
        y += 40 * d + 16 * d;
        float pad = 16 * d, gap = 8 * d, tile = Math.min((w - 2 * pad - 4 * gap) / 5f, 74 * d);
        float gx = (w - (5 * tile + 4 * gap)) / 2;
        for (int i = 0; i < 10; i++) {
            float l = gx + (i % 5) * (tile + gap), tt = y + (i / 5) * (tile + gap); final int si = i;
            Btn bt = new Btn(); bt.r = new RectF(l, tt, l + tile, tt + tile); bt.a = () -> { skin = si; save(); }; btns.add(bt);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(i == skin ? 0xFFFFFFFF : 0x88FFFFFF); rf.set(l, tt, l + tile, tt + tile); c.drawRoundRect(rf, 14 * d, 14 * d, p);
            if (i == skin) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xFF4CD964); c.drawRoundRect(rf, 14 * d, 14 * d, p); }
            Faces.draw(c, i, l + tile / 2, tt + tile * .55f, tile * .66f, BASE[color], cLight[color], cDark[color], 0, .3f);
        }
        y += 2 * tile + gap + 18 * d;
        float cr = Math.min((w - 2 * pad - 5 * gap) / 12f, 24 * d), cgx = (w - (6 * 2 * cr + 5 * gap)) / 2;
        for (int i = 0; i < 12; i++) {
            float cx = cgx + cr + (i % 6) * (2 * cr + gap), cyy = y + cr + (i / 6) * (2 * cr + gap); final int ci = i;
            Btn bt = new Btn(); bt.r = new RectF(cx - cr, cyy - cr, cx + cr, cyy + cr); bt.a = () -> { color = ci; save(); }; btns.add(bt);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x33000000); c.drawCircle(cx, cyy + 3 * d, cr, p);
            p.setColor(BASE[i]); c.drawCircle(cx, cyy, cr, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(i == color ? 5 * d : 2.5f * d); p.setColor(i == color ? Color.WHITE : 0x88FFFFFF); c.drawCircle(cx, cyy, cr - 1.5f * d, p);
        }
        float bw = Math.min(w * .84f, 360 * d);
        button(c, "Готово", (w - bw) / 2, h - 76 * d, (w + bw) / 2, h - 20 * d, 0xFF2FBF55, () -> { save(); scr = Scr.MENU; });
    }

    void askName() {
        act.runOnUiThread(() -> {
            final EditText et = new EditText(act); et.setText(name); et.setSelectAllOnFocus(true); et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
            new AlertDialog.Builder(act).setTitle("Твоё имя").setView(et).setPositiveButton("OK", (dd, ww) -> {
                String s = et.getText().toString().trim(); if (!s.isEmpty()) { name = s; save(); }
            }).setNegativeButton("Отмена", null).show();
        });
    }

    // ----- игровой экран -----
    float sx(float wx) { return getWidth() / 2f + (wx - camX) * ppu; }
    float sy(float wy) { return getHeight() / 2f + (wy - camY) * ppu; }
    float ppu = 30;

    void drawGame(Canvas c) {
        int w = getWidth(), h = getHeight(); Engine.Ent pl = eng.player;
        ppu = Math.min(w, h) / zoom;
        float shx = 0, shy = 0; if (shake > 0) { shx = (float) (Math.random() - .5) * 16 * d * shake; shy = (float) (Math.random() - .5) * 16 * d * shake; }
        c.save(); c.translate(shx, shy);
        // фон за ареной
        p.setStyle(Paint.Style.FILL); p.setShader(new LinearGradient(0, 0, 0, h, 0xFF8FE3C9, 0xFF6CC7DE, Shader.TileMode.CLAMP)); c.drawRect(-30, -30, w + 30, h + 30, p); p.setShader(null);
        float L = sx(0), T = sy(0), R = sx(eng.size), B = sy(eng.size);
        // пол арены
        p.setColor(0x22000000); rf.set(L, T + 6 * d, R, B + 6 * d); c.drawRoundRect(rf, ppu * .6f, ppu * .6f, p);
        p.setColor(0xFFDCF3F6); rf.set(L, T, R, B); c.drawRoundRect(rf, ppu * .6f, ppu * .6f, p);
        // сетка
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, ppu * .04f)); p.setColor(0xFFC9E6EB);
        int g0 = Math.max(0, (int) ((camX - w / 2f / ppu) / 2) * 2), g1 = Math.min((int) eng.size, (int) (camX + w / 2f / ppu) + 2);
        for (int g = g0; g <= g1; g += 2) { float x = sx(g); c.drawLine(x, Math.max(T, 0), x, Math.min(B, h), p); }
        g0 = Math.max(0, (int) ((camY - h / 2f / ppu) / 2) * 2); g1 = Math.min((int) eng.size, (int) (camY + h / 2f / ppu) + 2);
        for (int g = g0; g <= g1; g += 2) { float y = sy(g); c.drawLine(Math.max(L, 0), y, Math.min(R, w), y, p); }
        // территории
        rf.set(L, T, R, B); c.drawBitmap(tbmp, null, rf, bmpP);
        // граница арены
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(ppu * .35f); p.setColor(0xFFFFFFFF); rf.set(L - ppu * .17f, T - ppu * .17f, R + ppu * .17f, B + ppu * .17f); c.drawRoundRect(rf, ppu * .7f, ppu * .7f, p);
        // следы
        for (Engine.Ent e : eng.ents) if (e.alive && e.tn > 0) drawTrail(c, e);
        // головы
        Engine.Ent[] order = eng.ents.clone();
        java.util.Arrays.sort(order, (a, b) -> Float.compare(a.y, b.y));
        for (Engine.Ent e : order) if (e.alive) drawHead(c, e);
        // частицы
        p.setStyle(Paint.Style.FILL);
        for (float[] q : parts) {
            int ci = (int) q[7]; boolean lt = ci >= 100; ci %= 100;
            p.setColor((lt ? cLight[ci % BASE.length] : BASE[ci % BASE.length])); p.setAlpha((int) (255 * Math.min(1, q[6] / .3f)));
            c.save(); c.translate(sx(q[0]), sy(q[1])); c.rotate((float) Math.toDegrees(q[4])); float s = ppu * .22f; c.drawRect(-s, -s, s, s, p); c.restore();
        }
        p.setAlpha(255);
        for (Object[] f : floats) {
            tp.setAlpha(255); textO(c, (String) f[4], sx((float) f[0]), sy((float) f[1]) + (float) f[2] * ppu, 26, Color.WHITE, 0xCC27305E, 6);
        }
        c.restore();
        drawHud(c);
        // джойстик
        if (jActive && !over) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3 * d); p.setColor(0x66FFFFFF); c.drawCircle(jx0, jy0, 30 * d, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0x88FFFFFF); float dx = jx - jx0, dy = jy - jy0, len = (float) Math.hypot(dx, dy), m = Math.min(len, 30 * d);
            if (len > 0) c.drawCircle(jx0 + dx / len * m, jy0 + dy / len * m, 14 * d, p);
        }
    }

    void drawTrail(Canvas c, Engine.Ent e) {
        int ci = e.color % BASE.length; path.reset();
        float px = sx(e.tx[0]), py = sy(e.ty[0]); path.moveTo(px, py);
        for (int i = 1; i < e.tn; i++) {
            float x = sx(e.tx[i]), y = sy(e.ty[i]); path.quadTo(px, py, (px + x) / 2, (py + y) / 2); px = x; py = y;
        }
        path.lineTo(sx(e.x), sy(e.y));
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(ppu * .62f); p.setColor(cTrailEdge[ci]); c.drawPath(path, p);
        p.setStrokeWidth(ppu * .46f); p.setColor(cTrail[ci]); c.drawPath(path, p);
    }

    void drawHead(Canvas c, Engine.Ent e, float... unused) {
        int ci = e.color % BASE.length; float x = sx(e.x), y = sy(e.y);
        if (x < -ppu * 3 || x > getWidth() + ppu * 3 || y < -ppu * 3 || y > getHeight() + ppu * 3) return;
        float hs = ppu * 1.25f;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x33000000); rf.set(x - hs * .5f, y + hs * .32f, x + hs * .5f, y + hs * .52f); c.drawOval(rf, p);
        float bob = (float) Math.sin(t * 9 + e.id) * ppu * .03f;
        Faces.draw(c, e.skin, x, y - hs * .18f + bob, hs, BASE[ci], cLight[ci], cDark[ci], (float) Math.cos(e.ang), (float) Math.sin(e.ang));
        String nm = e.id == 1 ? "Ты" : e.name; if (e.id == 1 && !eng.player.name.isEmpty()) nm = eng.player.name;
        float ny = y - hs * (e.skin == 2 || e.skin == 9 || e.skin == 4 ? .98f : .78f);
        textO(c, nm, x, ny, Math.max(11, ppu * .42f / d), e.id == 1 ? 0xFFFFF176 : Color.WHITE, 0xCC27305E, 3.2f);
    }

    void drawHud(Canvas c) {
        int w = getWidth(), h = getHeight(); Engine.Ent pl = eng.player; float pct = eng.pct(pl);
        // верхняя полоса прогресса
        float bl = 16 * d, br = w - 16 * d, bt = 14 * d, bb = bt + 26 * d, rad = 13 * d;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x55FFFFFF); rf.set(bl, bt, br, bb); c.drawRoundRect(rf, rad, rad, p);
        float frac = endless ? Math.min(1, pct / 50f) : Math.min(1, pct / lvl.target);
        int ci = pl.color % BASE.length; float fw = Math.max(26 * d, (br - bl) * frac);
        p.setShader(new LinearGradient(bl, 0, br, 0, cLight[ci], BASE[ci], Shader.TileMode.CLAMP)); rf.set(bl, bt, bl + fw, bb); c.drawRoundRect(rf, rad, rad, p); p.setShader(null);
        textO(c, String.format(Locale.US, "%.0f%%", pct), bl + 24 * d, bb - 6 * d, 15, Color.WHITE, 0xAA27305E, 3);
        if (!endless) { star(c, br - 14 * d, (bt + bb) / 2, 9 * d, 0xFFFFC21F); text(c, lvl.target + "%", br - 40 * d, bb - 7 * d, 13, 0xFF27305E); }
        else text(c, "бесконечный режим", br - 80 * d, bb - 7 * d, 12, 0xFF27305E);
        // статистика
        textO(c, "Убито: " + pl.kills + "   " + fmtT(eng.time), 16 * d + 78 * d, bb + 22 * d, 13, Color.WHITE, 0xAA27305E, 3);
        // лидеры
        ArrayList<Engine.Ent> rk = eng.rank(); int n = Math.min(5, rk.size()); float lx = w - 150 * d, ly = bb + 10 * d;
        p.setColor(0x55000000); rf.set(lx, ly, w - 12 * d, ly + (n * 18 + 8) * d); c.drawRoundRect(rf, 12 * d, 12 * d, p);
        for (int i = 0; i < n; i++) {
            Engine.Ent e = rk.get(i); float yy = ly + (18 * i + 17) * d;
            p.setColor(BASE[e.color % BASE.length]); rf.set(lx + 8 * d, yy - 9 * d, lx + 17 * d, yy); c.drawRoundRect(rf, 3 * d, 3 * d, p);
            tp.setTextAlign(Paint.Align.LEFT); tp.setTextSize(11.5f * d); tp.setStyle(Paint.Style.FILL); tp.setColor(e == pl ? 0xFFFFF176 : Color.WHITE);
            String nm = e.name.length() > 8 ? e.name.substring(0, 8) : e.name; c.drawText(nm, lx + 23 * d, yy, tp);
            tp.setTextAlign(Paint.Align.RIGHT); c.drawText(String.format(Locale.US, "%.1f%%", eng.pct(e)), w - 18 * d, yy, tp); tp.setTextAlign(Paint.Align.CENTER);
        }
        // мини-карта
        float ms = 84 * d, ml = 12 * d, mt = h - ms - 14 * d;
        p.setColor(0xCCDCF3F6); rf.set(ml, mt, ml + ms, mt + ms); c.drawRoundRect(rf, 10 * d, 10 * d, p);
        rf.set(ml + 3 * d, mt + 3 * d, ml + ms - 3 * d, mt + ms - 3 * d); c.drawBitmap(tbmp, null, rf, bmpP);
        if (pl.alive) { p.setColor(Color.WHITE); c.drawCircle(rf.left + pl.x / eng.size * rf.width(), rf.top + pl.y / eng.size * rf.height(), 3.5f * d, p); p.setColor(0xFF27305E); c.drawCircle(rf.left + pl.x / eng.size * rf.width(), rf.top + pl.y / eng.size * rf.height(), 2f * d, p); }
        // пауза
        p.setColor(0x66000000); rf.set(w - 58 * d, h - 62 * d, w - 12 * d, h - 16 * d); c.drawRoundRect(rf, 14 * d, 14 * d, p);
        p.setColor(Color.WHITE); c.drawRoundRect(w - 44 * d, h - 50 * d, w - 37 * d, h - 28 * d, 2 * d, 2 * d, p); c.drawRoundRect(w - 31 * d, h - 50 * d, w - 24 * d, h - 28 * d, 2 * d, 2 * d, p);
        if (!started && !over) {
            p.setColor(0xBB27305E); rf.set(w * .1f, h * .72f, w * .9f, h * .72f + 52 * d); c.drawRoundRect(rf, 18 * d, 18 * d, p);
            text(c, "Води пальцем — машина поедет за ним", w / 2f, h * .72f + 32 * d, 16, Color.WHITE);
        }
        if (hudMsgT > 0) textO(c, hudMsg, w / 2f, h * .22f, 24, Color.WHITE, 0xCC27305E, 6);
    }

    void drawDlg(Canvas c) {
        int w = getWidth(), h = getHeight(); p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x99101733); c.drawRect(0, 0, w, h, p);
        float cw = Math.min(w * .9f, 380 * d), n = dlg.labels.length, lines = dlg.lines.length;
        float ch = 70 * d + lines * 24 * d + (dlg.stars >= 0 ? 62 * d : 0) + n * 62 * d + 20 * d, l = (w - cw) / 2, tt = (h - ch) / 2;
        p.setColor(0x44000000); rf.set(l, tt + 8 * d, l + cw, tt + ch + 8 * d); c.drawRoundRect(rf, 26 * d, 26 * d, p);
        p.setColor(0xFFF4F6FF); rf.set(l, tt, l + cw, tt + ch); c.drawRoundRect(rf, 26 * d, 26 * d, p);
        text(c, dlg.title, w / 2f, tt + 46 * d, 28, 0xFF3B4290);
        float y = tt + 76 * d;
        if (dlg.stars >= 0) { for (int s = 0; s < 3; s++) star(c, w / 2f + (s - 1) * 50 * d, y + 22 * d, 22 * d, s < dlg.stars ? 0xFFFFC21F : 0xFFD6DAEC); y += 62 * d; }
        for (String s : dlg.lines) { text(c, s, w / 2f, y + 10 * d, 14.5f, 0xFF4B5280); y += 24 * d; }
        y += 10 * d;
        for (int i = 0; i < n; i++) { final Runnable a = dlg.acts[i]; button(c, dlg.labels[i], l + 20 * d, y, l + cw - 20 * d, y + 50 * d, dlg.colors[i], a); y += 62 * d; }
    }
}
