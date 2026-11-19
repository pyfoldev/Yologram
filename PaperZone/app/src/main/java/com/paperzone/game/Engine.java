package com.paperzone.game;

import java.util.ArrayList;
import java.util.Random;

/** Игровой мир: непрерывное движение, территории на мелкой сетке, ИИ ботов. Без Android-зависимостей. */
public class Engine {
    public static final int RES = 12;               // клеток сетки на 1 игровую единицу
    public static final float BASE_SPEED = 4.6f;   // единиц/сек
    static final float PT_STEP = 0.12f;            // шаг точек следа
    static final int SELF_SKIP = 15;               // сколько последних точек своего следа не опасны
    static final float START_R = 2.6f;             // радиус стартовой территории

    public static final int EV_DEATH = 1, EV_CAPTURE = 2, EV_SPAWN = 3;

    public static class Event { public int type; public Ent e, other; public float x, y; public int amount; }

    public static class Config {
        public float size = 40; public int bots = 4;
        public float skill0 = .3f, skill1 = .3f, ramp = 1;   // навык: от skill0 к skill1 за ramp секунд
        public float speed0 = .85f, speed1 = 1f;             // скорость бота от навыка
        public String[] names = {"Бот"}; public int paletteSize = 12;
        public int playerColor, playerSkin; public String playerName = "Игрок";
        public boolean autoPlayer; public long seed;
    }

    public static class Ent {
        public int id, skin, color; public String name; public boolean ai, alive;
        public float x, y, ang, speed = BASE_SPEED, tAng, turnRate = 5.4f; public boolean hasT;
        public float[] tx = new float[512], ty = new float[512]; public int tn;
        public float lastInX, lastInY; public int kills; public float respawnAt, born;
        // ИИ
        float skill, skillOff, greed = 1, aggr = .7f, aiT, idleT, homeT = -1, homeD = 99, homeA;
        float[] wx = new float[4], wy = new float[4]; int wn, wi;
        boolean planActive, planStarted, planAbort, huntOK; int huntId; float aiTarget;
        boolean dying;
    }

    public final Config cfg; public final float size; public final int W, H;
    public final byte[] owner; public final int[] cnt;
    public final Ent[] ents; public final Ent player;
    public float time; public boolean started;
    public final ArrayList<Event> events = new ArrayList<>();
    public int dx0 = 1 << 30, dy0 = 1 << 30, dx1 = -1, dy1 = -1; public boolean fullDirty = true;
    final Random rnd; final java.util.HashSet<String> used = new java.util.HashSet<>();
    int paintGain;

    public Engine(Config c) {
        cfg = c; size = c.size; W = H = (int) (size * RES);
        owner = new byte[W * H]; cnt = new int[c.bots + 3];
        rnd = new Random(c.seed == 0 ? System.nanoTime() : c.seed);
        ents = new Ent[c.bots + 1];
        for (int i = 0; i < ents.length; i++) { Ent e = new Ent(); e.id = i + 1; e.ai = i > 0; ents[i] = e; }
        player = ents[0];
        player.ai = c.autoPlayer; player.name = c.playerName; player.skin = c.playerSkin; player.color = c.playerColor;
        used.add(player.name);
        spawn(player, true);
        for (int i = 1; i < ents.length; i++) spawn(ents[i], false);
    }

    // ---------- утилиты ----------
    public int ownerAt(float x, float y) {
        if (x < 0 || y < 0 || x >= size || y >= size) return 0;
        return owner[(int) (y * RES) * W + (int) (x * RES)] & 0xFF;
    }
    public float pct(Ent e) { return cnt[e.id] * 100f / (W * H); }
    float curSkill() { return clamp(cfg.skill0 + (cfg.skill1 - cfg.skill0) * Math.min(1f, time / Math.max(1f, cfg.ramp)), 0, 1); }
    static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    static float angDiff(float to, float from) {
        float d = (to - from) % 6.2831853f; if (d > 3.1415927f) d -= 6.2831853f; if (d < -3.1415927f) d += 6.2831853f; return d;
    }
    float rr(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    void setCell(int i, int id) {
        int prev = owner[i] & 0xFF; if (prev == id) return;
        if (prev != 0) cnt[prev]--; owner[i] = (byte) id; if (id != 0) cnt[id]++; paintGain++;
        int x = i % W, y = i / W;
        if (x < dx0) dx0 = x; if (x > dx1) dx1 = x; if (y < dy0) dy0 = y; if (y > dy1) dy1 = y;
    }
    void paintDisc(float ux, float uy, float r, int id) {
        int cx = (int) (ux * RES), cy = (int) (uy * RES), rc = (int) Math.ceil(r * RES); float r2 = r * RES * r * RES;
        for (int y = Math.max(0, cy - rc); y <= Math.min(H - 1, cy + rc); y++)
            for (int x = Math.max(0, cx - rc); x <= Math.min(W - 1, cx + rc); x++) {
                float dx = x + .5f - ux * RES, dy = y + .5f - uy * RES;
                if (dx * dx + dy * dy <= r2) setCell(y * W + x, id);
            }
    }
    public void clearDirty() { dx0 = dy0 = 1 << 30; dx1 = dy1 = -1; fullDirty = false; }

    // ---------- появление ----------
    boolean areaFree(float x, float y, float r) {
        for (float a = -r; a <= r; a += 1.2f) for (float b = -r; b <= r; b += 1.2f) if (ownerAt(x + a, y + b) != 0) return false;
        for (Ent o : ents) if (o.alive && o.tn > 0) {
            for (int k = 0; k < o.tn; k += 4) { float dx = o.tx[k] - x, dy = o.ty[k] - y; if (dx * dx + dy * dy < r * r) return false; }
        }
        return true;
    }
    void spawn(Ent e, boolean center) {
        float m = 5f; float px = size / 2, py = size / 2; boolean ok = false;
        for (int t = 0; t < 260 && !ok; t++) {
            float r = t < 130 ? 6.5f : 4.2f;
            if (center) { px = rr(size * .35f, size * .65f); py = rr(size * .35f, size * .65f); }
            else { px = rr(m, size - m); py = rr(m, size - m); }
            ok = areaFree(px, py, r);
        }
        if (e.id != 1 && e.ai) {
            if (e.name != null) used.remove(e.name);
            e.name = pickName(); used.add(e.name); e.color = pickColor(); e.skin = rnd.nextInt(10);
            e.skillOff = rr(-.12f, .12f); e.greed = rr(.8f, 1.3f); e.aggr = rr(.35f, 1f);
        }
        e.skill = clamp(curSkill() + e.skillOff, 0, 1);
        e.speed = e.id == 1 ? BASE_SPEED : BASE_SPEED * (cfg.speed0 + (cfg.speed1 - cfg.speed0) * e.skill);
        e.alive = true; e.x = px; e.y = py; e.ang = rr(0, 6.28f); e.tAng = e.ang; e.hasT = false; e.tn = 0;
        e.lastInX = px; e.lastInY = py; e.kills = 0; e.born = time; e.planActive = false; e.idleT = rr(0, 1f);
        e.huntId = 0; e.homeT = -1;
        paintDisc(px, py, START_R, e.id);
        Event ev = new Event(); ev.type = EV_SPAWN; ev.e = e; ev.x = px; ev.y = py; events.add(ev);
    }
    String pickName() {
        for (int a = 0; a < 40; a++) { String n = cfg.names[rnd.nextInt(cfg.names.length)]; if (!used.contains(n)) return n; }
        return cfg.names[rnd.nextInt(cfg.names.length)] + (2 + rnd.nextInt(97));
    }
    int pickColor() {
        boolean[] u = new boolean[cfg.paletteSize]; for (Ent e : ents) if (e.name != null && e.color < u.length) u[e.color] = true;
        int n = 0; for (boolean b : u) if (!b) n++;
        if (n == 0) return rnd.nextInt(cfg.paletteSize);
        int k = rnd.nextInt(n);
        for (int i = 0; i < u.length; i++) if (!u[i] && k-- == 0) return i;
        return 0;
    }

    // ---------- шаг ----------
    public void start() { started = true; }

    public void update(float dt) {
        if (!started) return;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.02f)); float h = dt / steps;
        for (int i = 0; i < steps; i++) step(h);
    }

    void step(float h) {
        time += h;
        for (Ent e : ents) if (!e.alive && e.ai && e.id != 1 && time >= e.respawnAt) spawn(e, false);
        for (Ent e : ents) {
            if (!e.alive) continue;
            if (e.ai) aiUpdate(e, h);
            if (e.hasT) { float d = angDiff(e.tAng, e.ang), m = e.turnRate * h; e.ang += clamp(d, -m, m); }
            e.x += (float) Math.cos(e.ang) * e.speed * h; e.y += (float) Math.sin(e.ang) * e.speed * h;
        }
        collisions();
        for (Ent e : ents) if (e.alive) territory(e);
    }

    void territory(Ent e) {
        int ow = ownerAt(e.x, e.y);
        if (ow == e.id) {
            if (e.tn > 0) { if (e.tn >= 3) capture(e); else e.tn = 0; }
            e.lastInX = e.x; e.lastInY = e.y;
        } else {
            if (e.tn == 0) addPt(e, e.lastInX, e.lastInY);
            float dx = e.x - e.tx[e.tn - 1], dy = e.y - e.ty[e.tn - 1];
            if (dx * dx + dy * dy >= PT_STEP * PT_STEP) addPt(e, e.x, e.y);
        }
    }
    void addPt(Ent e, float x, float y) {
        if (e.tn == e.tx.length) { e.tx = java.util.Arrays.copyOf(e.tx, e.tn * 2); e.ty = java.util.Arrays.copyOf(e.ty, e.tn * 2); }
        e.tx[e.tn] = x; e.ty[e.tn] = y; e.tn++;
    }

    void collisions() {
        ArrayList<Ent[]> hits = null;
        for (Ent e : ents) {
            if (!e.alive) continue;
            if (e.x < 0.25f || e.y < 0.25f || e.x > size - .25f || e.y > size - .25f) { mark(e, null); continue; }
            for (Ent o : ents) {
                if (!o.alive || o.tn == 0) continue;
                if (o == e) {
                    int n = o.tn - SELF_SKIP;
                    for (int k = 0; k < n; k++) { float dx = o.tx[k] - e.x, dy = o.ty[k] - e.y; if (dx * dx + dy * dy < .1f) { mark(e, null); break; } }
                } else {
                    for (int k = 0; k < o.tn; k++) { float dx = o.tx[k] - e.x, dy = o.ty[k] - e.y; if (dx * dx + dy * dy < .16f) { mark(o, e); break; } }
                }
            }
        }
        for (Ent e : ents) if (e.dying) { e.dying = false; kill(e, (Ent) null, true); }
        for (int i = 0; i < pend.size(); i += 2) kill(pend.get(i), pend.get(i + 1), false);
        pend.clear();
    }
    final ArrayList<Ent> pend = new ArrayList<>();
    void mark(Ent v, Ent killer) {
        for (int i = 0; i < pend.size(); i += 2) if (pend.get(i) == v) return;
        pend.add(v); pend.add(killer);
    }
    void kill(Ent e, Ent killer, boolean ignored) {
        if (!e.alive) return;
        e.alive = false; e.tn = 0; e.hasT = false;
        int id = e.id; for (int i = 0; i < owner.length; i++) if ((owner[i] & 0xFF) == id) owner[i] = 0;
        cnt[id] = 0; fullDirty = true; e.respawnAt = time + 3.2f;
        if (killer != null && killer != e) killer.kills++;
        Event ev = new Event(); ev.type = EV_DEATH; ev.e = e; ev.other = killer != e ? killer : null; ev.x = e.x; ev.y = e.y; events.add(ev);
    }

    // Замыкание петли: заливка многоугольника по чётно-нечётному правилу
    final float[] xs = new float[1024];
    void capture(Ent e) {
        int n = e.tn + 1; float[] px = new float[n], py = new float[n];
        for (int i = 0; i < e.tn; i++) { px[i] = e.tx[i] * RES; py[i] = e.ty[i] * RES; }
        px[n - 1] = e.x * RES; py[n - 1] = e.y * RES;
        float minY = 1e9f, maxY = -1e9f; for (int i = 0; i < n; i++) { minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]); }
        paintGain = 0;
        float[] cr = new float[n + 2];
        for (int y = Math.max(0, (int) minY - 1); y <= Math.min(H - 1, (int) maxY + 1); y++) {
            float yc = y + .5f; int c = 0;
            for (int i = 0; i < n; i++) {
                int j = i + 1 == n ? 0 : i + 1; float y1 = py[i], y2 = py[j];
                if ((y1 <= yc && y2 > yc) || (y2 <= yc && y1 > yc)) cr[c++] = px[i] + (yc - y1) * (px[j] - px[i]) / (y2 - y1);
            }
            java.util.Arrays.sort(cr, 0, c);
            for (int k = 0; k + 1 < c; k += 2) {
                int xa = Math.max(0, (int) Math.ceil(cr[k] - .5f)), xb = Math.min(W - 1, (int) Math.floor(cr[k + 1] - .5f));
                for (int x = xa; x <= xb; x++) setCell(y * W + x, e.id);
            }
        }
        for (int i = 0; i < e.tn; i++) paintDisc(e.tx[i], e.ty[i], .36f, e.id);
        int gained = paintGain; e.tn = 0; e.planStarted = e.planStarted;
        for (Ent o : ents) if (o != e && o.alive && cnt[o.id] <= 0) kill(o, e, false);
        Event ev = new Event(); ev.type = EV_CAPTURE; ev.e = e; ev.x = e.x; ev.y = e.y; ev.amount = gained; events.add(ev);
    }

    public ArrayList<Ent> rank() {
        ArrayList<Ent> l = new ArrayList<>(); for (Ent e : ents) if (e.alive) l.add(e);
        l.sort((a, b) -> cnt[b.id] - cnt[a.id]); return l;
    }

    // ================= ИИ =================
    float exitDist(Ent e, float ang) {
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        for (float d = 0; d < 30; d += .25f) if (ownerAt(e.x + c * d, e.y + s * d) != e.id) return d;
        return 99;
    }
    // расстояние и направление до своей территории
    void homeInfo(Ent e) {
        if (e.homeT == time) return; e.homeT = time;
        if (ownerAt(e.x, e.y) == e.id) { e.homeD = 0; e.homeA = e.ang; return; }
        float best = 99, ba = e.ang;
        for (int k = 0; k < 24; k++) {
            float a = k * 0.2617994f, c = (float) Math.cos(a), s = (float) Math.sin(a);
            for (float d = .25f; d < best && d < 28; d += .25f) {
                float x = e.x + c * d, y = e.y + s * d;
                if (x < 0 || y < 0 || x > size || y > size) break;
                if (ownerAt(x, y) == e.id) { best = d; ba = a; break; }
            }
        }
        e.homeD = best; e.homeA = ba;
    }
    float threat(Ent e) {
        float best = 1e9f;
        for (Ent o : ents) {
            if (o == e || !o.alive) continue;
            float m = 1e9f;
            for (int k = 0; k < e.tn; k += 3) { float dx = o.x - e.tx[k], dy = o.y - e.ty[k]; float d = dx * dx + dy * dy; if (d < m) m = d; }
            { float dx = o.x - e.x, dy = o.y - e.y; float d = dx * dx + dy * dy; if (d < m) m = d; }
            float t = (float) Math.sqrt(m) / o.speed; if (t < best) best = t;
        }
        return best;
    }
    boolean blocked(Ent e, float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        for (float d = .6f; d <= 1.5f; d += .45f) {
            float x = e.x + c * d, y = e.y + s * d;
            if (x < 1.1f || y < 1.1f || x > size - 1.1f || y > size - 1.1f) return true;
            int n = e.tn - SELF_SKIP - 4;
            for (int k = 0; k < n; k += 2) { float dx = e.tx[k] - x, dy = e.ty[k] - y; if (dx * dx + dy * dy < .36f) return true; }
        }
        return false;
    }
    static final float[] OFFS = {0, .35f, -.35f, .7f, -.7f, 1.1f, -1.1f, 1.6f, -1.6f, 2.3f, -2.3f};
    float avoid(Ent e, float want) {
        for (float o : OFFS) if (!blocked(e, want + o)) return want + o;
        return want;
    }

    void aiUpdate(Ent e, float h) {
        e.aiT -= h;
        if (e.aiT <= 0) { e.aiT = .07f + .12f * (1 - e.skill) * rnd.nextFloat(); aiDecide(e); }
        float noise = (float) Math.sin(time * 1.9f + e.id * 3.1f) * .22f * (1 - e.skill);
        e.tAng = e.aiTarget + noise; e.hasT = true;
    }

    void aiDecide(Ent e) {
        float s = e.skill; boolean inside = ownerAt(e.x, e.y) == e.id && e.tn == 0;
        float want = e.ang;
        float margin = -.6f + 1.1f * s - (e.aggr - .7f) * .6f;
        // --- охота ---
        float hAng = 0; boolean hunt = false;
        if (s > .2f) {
            float bestT = 1e9f;
            for (Ent o : ents) {
                if (o == e || !o.alive || o.tn < 4) continue;
                if (Math.abs(o.x - e.x) + Math.abs(o.y - e.y) > 18) continue;
                homeInfo(o); float oh = o.homeD / o.speed;
                float md = 1e9f; int mk = 0;
                for (int k = Math.max(0, o.tn - 40); k < o.tn; k += 2) { float dx = o.tx[k] - e.x, dy = o.ty[k] - e.y; float d = dx * dx + dy * dy; if (d < md) { md = d; mk = k; } }
                float tm = (float) Math.sqrt(md) / e.speed;
                if (tm + .2f < oh * .85f && tm < bestT) {
                    if (e.huntId != o.id) { e.huntId = o.id; e.huntOK = rnd.nextFloat() < (.3f + .7f * s) * (.5f + .5f * e.aggr); }
                    if (e.huntOK) { bestT = tm; hunt = true; hAng = (float) Math.atan2(o.ty[mk] - e.y, o.tx[mk] - e.x); }
                }
            }
        }
        if (inside) {
            if (e.planActive && e.planStarted) { e.planActive = false; e.idleT = rr(0, 1.1f * (1 - s)); }
            if (hunt) { want = hAng; e.planActive = false; }
            else if (!e.planActive) {
                if (e.idleT > 0) { e.idleT -= .1f; want = e.ang + .5f; if (exitDist(e, e.ang) < 1.2f) want = e.ang + 1.2f; }
                else if (!makePlan(e)) want = e.ang + .6f;
            }
            if (!hunt && e.planActive) want = (float) Math.atan2(e.wy[e.wi] - e.y, e.wx[e.wi] - e.x);
            if (!hunt && e.planActive && Math.hypot(e.wx[e.wi] - e.x, e.wy[e.wi] - e.y) < .9f && e.wi < e.wn - 1) e.wi++;
        } else {
            homeInfo(e); float hd = e.homeD, th = threat(e);
            float needT = hd / e.speed + .3f;
            boolean danger = th < needT + margin;
            float cap = 7 + 20 * s * e.greed;
            if (danger || hd > cap || e.tn > 380) e.planAbort = true;
            if (e.planActive) e.planStarted = true;
            if (e.planActive && !e.planAbort) {
                if (Math.hypot(e.wx[e.wi] - e.x, e.wy[e.wi] - e.y) < 1.0f) { if (e.wi < e.wn - 1) e.wi++; else e.planAbort = true; }
                want = (float) Math.atan2(e.wy[e.wi] - e.y, e.wx[e.wi] - e.x);
            }
            if (!e.planActive || e.planAbort) want = e.homeA;
            if (hunt && !danger && hd < cap) want = hAng;
        }
        e.aiTarget = avoid(e, want);
    }

    boolean makePlan(Ent e) {
        float s = e.skill, g = e.greed, lmin = 2.6f + 1.8f * s, lmax = (4.2f + 7.5f * s) * g;
        float best = -1; float[] bx = new float[3], by = new float[3];
        for (int t = 0; t < 16; t++) {
            float ang = rr(0, 6.2832f), d0 = exitDist(e, ang); if (d0 > 30) continue;
            float L = rr(lmin, lmax), Wd = rr(lmin, lmax); int sg = rnd.nextBoolean() ? 1 : -1;
            float dxn = (float) Math.cos(ang), dyn = (float) Math.sin(ang), pxn = -dyn * sg, pyn = dxn * sg;
            float ax = e.x + dxn * (d0 + L), ay = e.y + dyn * (d0 + L), bx2 = ax + pxn * Wd, by2 = ay + pyn * Wd;
            float cx = bx2 - dxn * (L + d0 * .5f), cy = by2 - dyn * (L + d0 * .5f);
            float mg = 1.8f;
            if (ax < mg || ay < mg || ax > size - mg || ay > size - mg || bx2 < mg || by2 < mg || bx2 > size - mg || by2 > size - mg || cx < mg || cy < mg || cx > size - mg || cy > size - mg) continue;
            float gain = 0;
            for (float i = 0; i <= L; i += .8f) for (float j = 0; j <= Wd; j += .8f) {
                float x = e.x + dxn * (d0 + i) + pxn * j, y = e.y + dyn * (d0 + i) + pyn * j; int o = ownerAt(x, y);
                if (o != e.id) gain += o != 0 ? 1.8f : 1f;
            }
            float T = (d0 + 2 * L + Wd) / e.speed, v = gain / T;
            float m = 1e9f;
            for (Ent o : ents) {
                if (o == e || !o.alive) continue;
                float[][] pts = {{ax, ay}, {bx2, by2}, {(ax + bx2) / 2, (ay + by2) / 2}, {e.x + dxn * d0, e.y + dyn * d0}};
                for (float[] p : pts) { float dd = (float) Math.hypot(o.x - p[0], o.y - p[1]) / o.speed; if (dd < m) m = dd; }
            }
            if (m < T + .5f) v *= s > .3f ? .2f : .7f;
            v *= .75f + .5f * rnd.nextFloat();
            if (v > best) { best = v; bx[0] = ax; by[0] = ay; bx[1] = bx2; by[1] = by2; bx[2] = cx; by[2] = cy; }
        }
        if (best < 0) return false;
        e.wn = 3; e.wi = 0; for (int i = 0; i < 3; i++) { e.wx[i] = bx[i]; e.wy[i] = by[i]; }
        e.planActive = true; e.planStarted = false; e.planAbort = false; return true;
    }
}
