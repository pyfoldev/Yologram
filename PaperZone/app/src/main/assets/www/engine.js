/* Paper Zone — игровой движок (логика территорий + ИИ ботов). Без DOM. */
(function (root) {
  'use strict';
  const DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0]; // 0 вверх, 1 вправо, 2 вниз, 3 влево
  const TICK = 110; // мс на одну клетку
  const ri = (a, b) => a + Math.floor(Math.random() * (b - a + 1));
  const rr = (a, b) => a + Math.random() * (b - a);
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

  class Ent {
    constructor(id) {
      this.id = id; this.name = ''; this.skin = 0; this.color = 0; this.ai = true; this.alive = false;
      this.x = 0; this.y = 0; this.px = 0; this.py = 0; this.nx = 0; this.ny = 0; this.dir = -1;
      this.queue = []; this.trail = [];
      this.speed = 1; this.acc = 0; this.lastMove = 0; this.kills = 0; this.respawnAt = 0;
      this.skillOff = 0; this.skillNow = 0.5; this.greed = 1; this.aggr = 0.7;
      this.plan = null; this.idle = 0; this.needSlack = 0; this.cap = 20;
      this.huntId = 0; this.huntOK = false; this._dying = false; this._homeTick = -1; this._homeD = 999;
      this.noPlan = 0; this.born = 0;
    }
  }

  class World {
    /**
     * o: {w,h,bots,names[],paletteSize,playerColor,playerName,playerSkin,
     *     skill:(tSec)=>0..1, botSpeed:(skill)=>0.7..1, respawn, respawnDelay, autoPlayer}
     */
    constructor(o) {
      this.o = o; this.W = o.w; this.H = o.h; const N = (this.N = this.W * this.H);
      this.owner = new Uint8Array(N); this.trail = new Uint8Array(N);
      this.vis = new Int32Array(N); this.stamp = 0; this.q = new Int32Array(N);
      this.dd = new Int16Array(N); this.dr = new Int8Array(N); this.fd = new Int8Array(N);
      this.buf = new Int32Array(N); this.cnt = new Int32Array(o.bots + 3);
      this.time = 0; this.tick = 0; this.started = false; this.events = []; this.ents = [];
      this.usedNames = new Set();
      const p = new Ent(1);
      p.ai = !!o.autoPlayer; p.name = o.playerName || 'Игрок'; p.skin = o.playerSkin | 0; p.color = o.playerColor | 0;
      p.speed = 1; this.ents.push(p); this.usedNames.add(p.name);
      for (let i = 0; i < o.bots; i++) {
        const b = new Ent(i + 2);
        this.ents.push(b);
      }
      this.player = p;
      this.spawn(p, true);
      for (let i = 1; i < this.ents.length; i++) { this.ents[i].alive = false; this.spawn(this.ents[i]); }
    }

    idx(x, y) { return y * this.W + x; }
    curSkill() { return clamp(this.o.skill(this.time / 1000), 0, 1); }

    pickName() {
      const names = this.o.names || ['Бот'];
      for (let a = 0; a < 40; a++) {
        const n = names[ri(0, names.length - 1)];
        if (!this.usedNames.has(n)) return n;
      }
      return names[ri(0, names.length - 1)] + ri(2, 99);
    }
    pickColor() {
      const used = new Set();
      for (const e of this.ents) if (e.alive || e.name) used.add(e.color);
      const free = [];
      for (let i = 0; i < this.o.paletteSize; i++) if (!used.has(i)) free.push(i);
      return free.length ? free[ri(0, free.length - 1)] : ri(0, this.o.paletteSize - 1);
    }

    areaFree(cx, cy, m) {
      const W = this.W, own = this.owner, tr = this.trail;
      for (let y = cy - m; y <= cy + m; y++) {
        for (let x = cx - m; x <= cx + m; x++) {
          const i = y * W + x;
          if (own[i] || tr[i]) return false;
        }
      }
      return true;
    }

    spawn(e, center) {
      const W = this.W, H = this.H;
      let pos = null;
      for (let m = 7; m >= 4 && !pos; m--) {
        for (let a = 0; a < 140 && !pos; a++) {
          let cx, cy;
          if (center) { cx = ri(Math.floor(W * 0.3), Math.floor(W * 0.7)); cy = ri(Math.floor(H * 0.3), Math.floor(H * 0.7)); }
          else { cx = ri(m, W - 1 - m); cy = ri(m, H - 1 - m); }
          if (cx < m || cy < m || cx > W - 1 - m || cy > H - 1 - m) continue;
          if (this.areaFree(cx, cy, m)) pos = [cx, cy];
        }
      }
      if (!pos) pos = [ri(5, W - 6), ri(5, H - 6)];
      const cx = pos[0], cy = pos[1];
      if (e.ai && e.id !== 1) {
        if (e.name) this.usedNames.delete(e.name);
        e.name = this.pickName(); this.usedNames.add(e.name);
        e.color = this.pickColor(); e.skin = ri(0, 9);
        e.skillOff = rr(-0.12, 0.12); e.greed = rr(0.8, 1.3); e.aggr = rr(0.35, 1);
      }
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) this.setOwner(this.idx(cx + dx, cy + dy), e.id, e);
      e.alive = true; e.x = e.px = cx; e.y = e.py = cy; e.trail = []; e.queue = [];
      e.dir = e.ai ? ri(0, 3) : -1; e.kills = 0; e.plan = null; e.idle = ri(0, 3);
      e.acc = Math.random(); e.lastMove = this.time; e.born = this.time; e.huntId = 0; e.noPlan = 0;
      if (e.ai) this.refreshAI(e);
      this.events.push({ t: 'spawn', e });
    }

    refreshAI(e) {
      const s = clamp(this.curSkill() + e.skillOff, 0, 1);
      e.skillNow = s;
      if (e.id !== 1 || e.ai) e.speed = e.id === 1 ? 1 : this.o.botSpeed(s);
      e.needSlack = -3 + 4 * s - (e.aggr - 0.7) * 1.2;
      e.cap = Math.round(8 + 24 * s * e.greed);
    }

    setOwner(i, id, forEnt) {
      const own = this.owner, prev = own[i];
      if (prev === id) return;
      if (prev) {
        this.cnt[prev]--;
      }
      own[i] = id;
      if (id) this.cnt[id]++;
    }

    turn(e, d) {
      if (!e.alive) return;
      if (e.dir < 0) { e.dir = d; if (!this.started) this.started = true; e.lastMove = this.time; return; }
      const last = e.queue.length ? e.queue[e.queue.length - 1] : e.dir;
      if (d === last || d === ((last + 2) & 3)) return;
      if (e.queue.length < 2) e.queue.push(d);
    }

    kill(e, killer) {
      if (!e.alive) return;
      e.alive = false;
      for (const i of e.trail) this.trail[i] = 0;
      e.trail = [];
      const id = e.id, own = this.owner;
      for (let i = 0; i < this.N; i++) if (own[i] === id) own[i] = 0;
      this.cnt[id] = 0;
      e.respawnAt = this.time + (this.o.respawnDelay || 3500);
      if (killer && killer !== e) killer.kills++;
      this.events.push({ t: 'death', e, killer: killer && killer !== e ? killer : null, x: e.x, y: e.y });
    }

    step() {
      if (!this.started) return;
      this.time += TICK; this.tick++;
      const W = this.W, H = this.H, ents = this.ents, trailArr = this.trail;
      if (this.o.respawn !== false) {
        for (const e of ents) if (!e.alive && e.ai && e.id !== 1 && this.time >= e.respawnAt) this.spawn(e);
      }
      if (this.tick % 40 === 0) for (const e of ents) if (e.alive && e.ai) this.refreshAI(e);

      const movers = [];
      for (const e of ents) {
        if (!e.alive) continue;
        e.acc += e.speed;
        if (e.acc < 1) continue;
        e.acc -= 1;
        if (e.ai) this.aiStep(e);
        else if (e.queue.length) e.dir = e.queue.shift();
        if (e.dir < 0) continue;
        e.nx = e.x + DX[e.dir]; e.ny = e.y + DY[e.dir];
        movers.push(e);
      }

      const victims = [];
      const mark = (v, k) => { if (v._dying) return; v._dying = true; victims.push([v, k]); };
      for (const m of movers) {
        if (m.nx < 0 || m.ny < 0 || m.nx >= W || m.ny >= H) { mark(m, null); continue; }
        const t = trailArr[m.ny * W + m.nx];
        if (t) { if (t === m.id) mark(m, null); else mark(ents[t - 1], m); }
      }
      for (let i = 0; i < movers.length; i++) {
        for (let j = i + 1; j < movers.length; j++) {
          const a = movers[i], b = movers[j];
          const same = a.nx === b.nx && a.ny === b.ny;
          const swap = a.nx === b.x && a.ny === b.y && b.nx === a.x && b.ny === a.y;
          if (same || swap) {
            const ca = this.cnt[a.id], cb = this.cnt[b.id];
            if (ca > cb) mark(b, a); else if (cb > ca) mark(a, b); else { mark(a, b); mark(b, a); }
          }
        }
      }
      for (const [v, k] of victims) { this.kill(v, k); v._dying = false; }

      for (const m of movers) {
        if (!m.alive) continue;
        m.px = m.x; m.py = m.y; m.x = m.nx; m.y = m.ny; m.lastMove = this.time;
        const i = m.y * W + m.x;
        if (this.owner[i] === m.id) {
          if (m.trail.length) this.capture(m);
        } else {
          trailArr[i] = m.id; m.trail.push(i);
        }
      }
    }

    // Замыкание петли: захват всех областей, кроме самой большой («внешней»)
    capture(e) {
      const W = this.W, H = this.H, own = this.owner, id = e.id, trail = this.trail;
      const tr = e.trail; let gained = 0;
      for (let k = 0; k < tr.length; k++) { const i = tr[k]; trail[i] = 0; this.setOwner(i, id); gained++; }
      const stamp = ++this.stamp, vis = this.vis, q = this.q, buf = this.buf;
      let bl = 0; const comps = [];
      for (let k = 0; k < tr.length; k++) {
        const ti = tr[k], tx = ti % W, ty = (ti / W) | 0;
        for (let d = 0; d < 4; d++) {
          const nx = tx + DX[d], ny = ty + DY[d];
          if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
          const si = ny * W + nx;
          if (vis[si] === stamp || own[si] === id) continue;
          const start = bl; vis[si] = stamp; let qh = 0, qt = 0; q[qt++] = si;
          while (qh < qt) {
            const i = q[qh++]; buf[bl++] = i;
            const x = i % W, y = (i / W) | 0;
            if (x > 0) { const j = i - 1; if (vis[j] !== stamp && own[j] !== id) { vis[j] = stamp; q[qt++] = j; } }
            if (x < W - 1) { const j = i + 1; if (vis[j] !== stamp && own[j] !== id) { vis[j] = stamp; q[qt++] = j; } }
            if (y > 0) { const j = i - W; if (vis[j] !== stamp && own[j] !== id) { vis[j] = stamp; q[qt++] = j; } }
            if (y < H - 1) { const j = i + W; if (vis[j] !== stamp && own[j] !== id) { vis[j] = stamp; q[qt++] = j; } }
          }
          comps.push([start, bl]);
        }
      }
      if (comps.length > 1) {
        let bi = 0, bs = -1;
        for (let c = 0; c < comps.length; c++) { const s = comps[c][1] - comps[c][0]; if (s > bs) { bs = s; bi = c; } }
        const lost = new Set();
        for (let c = 0; c < comps.length; c++) {
          if (c === bi) continue;
          for (let k = comps[c][0]; k < comps[c][1]; k++) {
            const i = buf[k]; const prev = own[i];
            if (prev && prev !== id) lost.add(prev);
            this.setOwner(i, id); gained++;
          }
        }
        for (const pid of lost) if (this.cnt[pid] <= 0) this.kill(this.ents[pid - 1], e);
      }
      e.trail = [];
      this.events.push({ t: 'capture', e, gained });
    }

    rank() { return this.ents.filter(e => e.alive).sort((a, b) => this.cnt[b.id] - this.cnt[a.id]); }

    // ---------- ИИ ----------
    // Расстояние (в шагах) до ближайшей клетки своей территории от (sx,sy)
    homeFrom(e, sx, sy, rootDir, maxD) {
      const W = this.W, H = this.H, own = this.owner, tr = this.trail, id = e.id;
      const si = sy * W + sx;
      if (own[si] === id) return 0;
      const stamp = ++this.stamp, vis = this.vis, q = this.q, dd = this.dd, dr = this.dr;
      let qh = 0, qt = 0; vis[si] = stamp; q[qt++] = si; dd[si] = 0; dr[si] = rootDir;
      while (qh < qt) {
        const i = q[qh++]; const d = dd[i]; if (d >= maxD) return 999;
        const x = i % W, y = (i / W) | 0, cd = dr[i];
        for (let k = 0; k < 3; k++) {
          const nd = k === 0 ? cd : k === 1 ? (cd + 1) & 3 : (cd + 3) & 3;
          const nx = x + DX[nd], ny = y + DY[nd];
          if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
          const ni = ny * W + nx;
          if (vis[ni] === stamp) continue;
          vis[ni] = stamp;
          if (own[ni] === id) return d + 1;
          if (tr[ni] === id) continue;
          dd[ni] = d + 1; dr[ni] = nd; q[qt++] = ni;
        }
      }
      return 999;
    }

    // Кратчайший путь к следу жертвы; возвращает {d, dir} первого шага
    huntFrom(e, victimId, maxD) {
      const W = this.W, H = this.H, tr = this.trail, id = e.id;
      const stamp = ++this.stamp, vis = this.vis, q = this.q, dd = this.dd, dr = this.dr, fd = this.fd;
      const si = e.y * W + e.x; let qh = 0, qt = 0; vis[si] = stamp; q[qt++] = si; dd[si] = 0; dr[si] = e.dir < 0 ? 0 : e.dir;
      while (qh < qt) {
        const i = q[qh++]; const d = dd[i]; if (d >= maxD) return null;
        const x = i % W, y = (i / W) | 0, cd = dr[i];
        for (let k = 0; k < 3; k++) {
          const nd = k === 0 ? cd : k === 1 ? (cd + 1) & 3 : (cd + 3) & 3;
          const nx = x + DX[nd], ny = y + DY[nd];
          if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
          const ni = ny * W + nx;
          if (vis[ni] === stamp) continue;
          vis[ni] = stamp;
          const t = tr[ni];
          if (t === id) continue;
          const first = d === 0 ? nd : fd[i];
          if (t === victimId) return { d: d + 1, dir: first };
          dd[ni] = d + 1; dr[ni] = nd; fd[ni] = first; q[qt++] = ni;
        }
      }
      return null;
    }

    tryHunt(e) {
      const s = e.skillNow; let best = null;
      for (const o of this.ents) {
        if (o === e || !o.alive || o.trail.length === 0) continue;
        if (Math.abs(o.x - e.x) + Math.abs(o.y - e.y) > 24) continue;
        if (o._homeTick !== this.tick) { o._homeTick = this.tick; o._homeD = this.homeFrom(o, o.x, o.y, o.dir < 0 ? 0 : o.dir, 60); }
        const hd = o._homeD; if (hd >= 999) continue;
        const maxD = Math.min(Math.floor((hd / o.speed - 0.5) * e.speed), 34);
        if (maxD < 1) continue;
        if (e.huntId !== o.id) { e.huntId = o.id; e.huntOK = Math.random() < (0.3 + 0.7 * s) * (0.5 + 0.5 * e.aggr); }
        if (!e.huntOK) continue;
        const r = this.huntFrom(e, o.id, maxD);
        if (r && (!best || r.d < best.d)) best = r;
      }
      return best ? best.dir : -1;
    }

    makePlan(e, cands) {
      const W = this.W, H = this.H, own = this.owner, id = e.id, s = e.skillNow, g = e.greed;
      const enemies = this.ents.filter(o => o !== e && o.alive);
      const lmax = Math.max(3, Math.round((3 + 8 * s) * g)), lmin = Math.max(2, Math.round(2 + 2 * s));
      let best = null, bs = -1;
      for (const c of cands) {
        const d = c.d; let cx = e.x, cy = e.y, k = 0;
        for (;;) {
          cx += DX[d]; cy += DY[d]; k++;
          if (cx < 1 || cy < 1 || cx >= W - 1 || cy >= H - 1) { k = -1; break; }
          if (own[cy * W + cx] !== id) break;
          if (k > 30) { k = -1; break; }
        }
        if (k < 0) continue;
        for (let t = 0; t < 6; t++) {
          const L = ri(lmin, lmax), Wd = ri(lmin, lmax), side = Math.random() < 0.5 ? (d + 1) & 3 : (d + 3) & 3;
          const ax = cx + DX[d] * (L - 1), ay = cy + DY[d] * (L - 1);
          const qx = ax + DX[side] * Wd, qy = ay + DY[side] * Wd;
          const rx = cx + DX[side] * Wd, ry = cy + DY[side] * Wd;
          if (qx < 2 || qy < 2 || qx >= W - 2 || qy >= H - 2 || rx < 2 || ry < 2 || rx >= W - 2 || ry >= H - 2) continue;
          let gain = 0, en = 0;
          for (let i = 0; i < L; i++) {
            for (let j = 0; j <= Wd; j++) {
              const x = cx + DX[d] * i + DX[side] * j, y = cy + DY[d] * i + DY[side] * j;
              const o = own[y * W + x];
              if (o !== id) { gain++; if (o) en++; }
            }
          }
          const T = k * 0.4 + 2 * L + Wd;
          let v = (gain + en * 0.8) / T;
          if (enemies.length) {
            let dm = 1e9;
            const pts = [[cx, cy], [ax, ay], [qx, qy], [rx, ry]];
            for (const o of enemies) for (const p of pts) {
              const dist = (Math.abs(o.x - p[0]) + Math.abs(o.y - p[1])) / o.speed;
              if (dist < dm) dm = dist;
            }
            if (dm <= T + 1) v *= s > 0.3 ? 0.2 : 0.7;
          }
          v *= 0.75 + 0.5 * Math.random();
          if (v > bs) { bs = v; best = { d, side, L, W: Wd, abort: false, started: false }; }
        }
      }
      return best;
    }

    insideMove(e, cands) {
      const own = this.owner, id = e.id;
      const ow = cands.filter(c => own[c.ni] === id);
      if (ow.length) {
        if (ow[0].d === e.dir && Math.random() < 0.8) return ow[0];
        return ow[ri(0, ow.length - 1)];
      }
      return cands[0];
    }

    aiStep(e) {
      const W = this.W, H = this.H, own = this.owner, trail = this.trail;
      if (e.dir < 0) e.dir = ri(0, 3);
      const s = e.skillNow;
      const dirs = [e.dir, (e.dir + 1) & 3, (e.dir + 3) & 3];
      const cands = [];
      for (const d of dirs) {
        const nx = e.x + DX[d], ny = e.y + DY[d];
        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
        const ni = ny * W + nx;
        if (trail[ni] === e.id) continue;
        cands.push({ d, nx, ny, ni, arr: 0, slack: 0 });
      }
      if (!cands.length) return; // неизбежная гибель
      const outside = e.trail.length > 0;
      const enemies = this.ents.filter(o => o !== e && o.alive);

      let tm = 1e9;
      if (outside && enemies.length) {
        const tr = e.trail;
        for (const o of enemies) {
          const sp = o.speed;
          for (let k = 0; k < tr.length; k++) {
            const i = tr[k];
            const d = (Math.abs(o.x - (i % W)) + Math.abs(o.y - ((i / W) | 0))) / sp;
            if (d < tm) tm = d;
          }
        }
      }
      const evalC = c => {
        c.arr = own[c.ni] === e.id ? 1 : 1 + this.homeFrom(e, c.nx, c.ny, c.d, 60);
        let dn = 1e9;
        for (const o of enemies) { const d = (Math.abs(o.x - c.nx) + Math.abs(o.y - c.ny)) / o.speed; if (d < dn) dn = d; }
        c.slack = Math.min(tm, dn) - c.arr;
      };

      // охота на следы соперников
      if (s > 0.2) {
        const hd = this.tryHunt(e);
        if (hd >= 0) {
          const c = cands.find(c => c.d === hd);
          if (c) {
            if (!outside) { e.dir = c.d; return; }
            evalC(c);
            if (c.slack >= e.needSlack - 1.5) { e.dir = c.d; return; }
          }
        }
      }

      if (!outside) {
        if (e.plan && e.plan.started) { e.plan = null; e.idle = ri(0, Math.round(5 * (1 - s)) + 1); }
        let c = null;
        if (!e.plan) {
          if (e.idle > 0) e.idle--;
          else {
            e.plan = this.makePlan(e, cands);
            if (!e.plan) e.noPlan++;
          }
        }
        if (e.plan) {
          c = cands.find(c => c.d === e.plan.d);
          if (!c) e.plan = null;
        }
        if (!c) {
          if (e.noPlan > 3) { c = cands.find(c => own[c.ni] !== e.id) || cands[0]; e.noPlan = 0; }
          else c = this.insideMove(e, cands);
        }
        e.dir = c.d;
        return;
      }

      // вне своей территории
      const pl = e.plan; if (pl) pl.started = true;
      const tl = e.trail.length;
      for (const c of cands) evalC(c);
      const need = e.needSlack;
      let choice = null;
      if (pl && !pl.abort) {
        const pref = tl < pl.L ? pl.d : tl < pl.L + pl.W ? pl.side : -1;
        if (pref >= 0) {
          const c = cands.find(c => c.d === pref);
          if (c && c.slack >= need && c.arr <= e.cap) choice = c; else pl.abort = true;
        }
      }
      if (!choice && s < 0.6 && Math.random() < 0.02 * (1 - s)) choice = cands[ri(0, cands.length - 1)];
      if (!choice) {
        let bestSafe = null, bestAny = null;
        for (const c of cands) {
          if (c.slack >= need) { if (!bestSafe || c.arr < bestSafe.arr) bestSafe = c; }
          if (!bestAny || c.slack > bestAny.slack || (c.slack === bestAny.slack && c.arr < bestAny.arr)) bestAny = c;
        }
        choice = bestSafe || bestAny;
      }
      e.dir = choice.d;
    }
  }

  root.PaperEngine = { World, TICK, DX, DY };
  if (typeof module !== 'undefined') module.exports = root.PaperEngine;
})(typeof window !== 'undefined' ? window : globalThis);
