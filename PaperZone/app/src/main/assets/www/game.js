/* Paper Zone — интерфейс, графика, управление */
(function () {
  'use strict';
  const { World, TICK } = window.PaperEngine;
  const $ = id => document.getElementById(id);
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

  // ====================== Данные ======================
  const PALETTE = ['#ff4d5e', '#ff8a3d', '#ffc83d', '#8bd94f', '#26c281', '#27c2c9', '#3d9bff', '#6a6bff', '#a259ff', '#ff5fb4', '#a0674b', '#5b6b7f'];
  const SKINS = ['Кубик', 'Кот', 'Робот', 'Призрак', 'Пришелец', 'Панда', 'Лис', 'Монстр', 'Крутой', 'Король'];
  const NAMES = ['Нова', 'Блиц', 'Рекс', 'Мила', 'Тень', 'Волк', 'Искра', 'Ярик', 'Зевс', 'Лиса', 'Дымка', 'Борз', 'Орёл', 'Пикси',
    'Гром', 'Алиса', 'Макс', 'Стрела', 'Ураган', 'Мишка', 'Фокс', 'Кай', 'Вега', 'Тигр', 'Снайпер', 'Кроха', 'Сфинкс', 'Ниндзя'];

  // size — размер поля; target — доля захвата для победы; t3/t2 — время (сек) на 3 и 2 звезды
  const LEVELS = [
    { size: 50, bots: 3, skill: 0.10, speed: 0.78, target: 10, t3: 45, t2: 90 },
    { size: 60, bots: 4, skill: 0.22, speed: 0.82, target: 12, t3: 60, t2: 120 },
    { size: 70, bots: 5, skill: 0.34, speed: 0.86, target: 15, t3: 80, t2: 150 },
    { size: 80, bots: 5, skill: 0.46, speed: 0.89, target: 18, t3: 100, t2: 190 },
    { size: 90, bots: 6, skill: 0.56, speed: 0.92, target: 20, t3: 120, t2: 220 },
    { size: 100, bots: 7, skill: 0.66, speed: 0.94, target: 22, t3: 140, t2: 260 },
    { size: 110, bots: 7, skill: 0.75, speed: 0.96, target: 25, t3: 170, t2: 300 },
    { size: 120, bots: 8, skill: 0.84, speed: 0.98, target: 27, t3: 200, t2: 340 },
    { size: 130, bots: 9, skill: 0.92, speed: 1.0, target: 30, t3: 230, t2: 380 },
    { size: 150, bots: 10, skill: 1.0, speed: 1.0, target: 33, t3: 270, t2: 440 },
  ];
  const ENDLESS = { size: 140, bots: 11, skill0: 0.35, rampSec: 420 };

  // ====================== Сохранение ======================
  const KEY = 'paperzone_v1';
  const S = { skin: 0, color: 6, name: 'Игрок', unlocked: 1, stars: {}, best: { pct: 0, kills: 0 }, sound: true };
  try { Object.assign(S, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) { /* нет хранилища */ }
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) { /* ignore */ } };

  // ====================== Цвета ======================
  function hex2rgb(h) { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
  function mix(rgb, to, t) { return 'rgb(' + rgb.map((c, i) => Math.round(c + (to[i] - c) * t)).join(',') + ')'; }
  const PAL = PALETTE.map(h => { const r = hex2rgb(h); return { base: h, light: mix(r, [255, 255, 255], 0.5), pale: mix(r, [255, 255, 255], 0.78), dark: mix(r, [0, 0, 0], 0.3), deep: mix(r, [0, 0, 0], 0.5), rgb: r }; });

  // ====================== Звук ======================
  let actx = null;
  function beep(freq, dur, type, vol, slide) {
    if (!S.sound) return;
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      if (actx.state === 'suspended') actx.resume();
      const o = actx.createOscillator(), g = actx.createGain();
      o.type = type || 'sine'; o.frequency.value = freq;
      if (slide) o.frequency.linearRampToValueAtTime(freq + slide, actx.currentTime + dur);
      g.gain.value = vol || 0.08; g.gain.linearRampToValueAtTime(0, actx.currentTime + dur);
      o.connect(g); g.connect(actx.destination); o.start(); o.stop(actx.currentTime + dur);
    } catch (e) { /* ignore */ }
  }
  const sfx = {
    capture() { beep(520, 0.12, 'triangle', 0.09, 300); setTimeout(() => beep(780, 0.14, 'triangle', 0.08, 200), 90); },
    kill() { beep(300, 0.18, 'square', 0.07, -120); setTimeout(() => beep(620, 0.2, 'square', 0.06, 200), 110); },
    die() { beep(360, 0.5, 'sawtooth', 0.09, -300); },
    win() { [523, 659, 784, 1046].forEach((f, i) => setTimeout(() => beep(f, 0.2, 'triangle', 0.1), i * 120)); },
    click() { beep(660, 0.05, 'square', 0.04); },
  };

  // ====================== Рисование персонажей ======================
  function rrect(c, x, y, w, h, r) {
    c.beginPath(); c.moveTo(x + r, y); c.lineTo(x + w - r, y); c.quadraticCurveTo(x + w, y, x + w, y + r);
    c.lineTo(x + w, y + h - r); c.quadraticCurveTo(x + w, y + h, x + w - r, y + h); c.lineTo(x + r, y + h);
    c.quadraticCurveTo(x, y + h, x, y + h - r); c.lineTo(x, y + r); c.quadraticCurveTo(x, y, x + r, y); c.closePath();
  }
  function circle(c, x, y, r, fill) { c.beginPath(); c.arc(x, y, r, 0, Math.PI * 2); c.fillStyle = fill; c.fill(); }
  function eye(c, x, y, r, lx, ly) {
    circle(c, x, y, r, '#fff');
    circle(c, x + lx * r * 0.38, y + ly * r * 0.38, r * 0.58, '#1b1f33');
    circle(c, x + lx * r * 0.38 - r * 0.18, y + ly * r * 0.38 - r * 0.2, r * 0.2, '#fff');
  }
  function stroke(c, w, col) { c.lineWidth = w; c.strokeStyle = col; c.lineCap = 'round'; c.lineJoin = 'round'; c.stroke(); }
  function tri(c, a, b, d, fill) { c.beginPath(); c.moveTo(a[0], a[1]); c.lineTo(b[0], b[1]); c.lineTo(d[0], d[1]); c.closePath(); c.fillStyle = fill; c.fill(); }

  // Голова в локальных координатах [-50..50]
  function drawHead(c, cx, cy, size, skin, pal, lx, ly) {
    c.save(); c.translate(cx, cy); const k = size / 100; c.scale(k, k);
    const body = () => {
      rrect(c, -50, -50, 100, 100, 24);
      const g = c.createLinearGradient(0, -50, 0, 50); g.addColorStop(0, pal.light); g.addColorStop(0.18, pal.base); g.addColorStop(1, pal.base);
      c.fillStyle = g; c.fill(); stroke(c, 7, pal.dark);
    };
    switch (skin) {
      case 0: // Кубик
        body(); eye(c, -20, -8, 14, lx, ly); eye(c, 20, -8, 14, lx, ly);
        c.beginPath(); c.arc(0, 12, 16, 0.2 * Math.PI, 0.8 * Math.PI); stroke(c, 6, pal.deep); break;
      case 1: // Кот
        tri(c, [-48, -22], [-44, -66], [-10, -48], pal.dark); tri(c, [48, -22], [44, -66], [10, -48], pal.dark);
        tri(c, [-42, -30], [-40, -54], [-22, -44], '#ffb3c7'); tri(c, [42, -30], [40, -54], [22, -44], '#ffb3c7');
        body(); eye(c, -21, -8, 13, lx, ly); eye(c, 21, -8, 13, lx, ly);
        tri(c, [-7, 10], [7, 10], [0, 18], '#ff7d9e');
        c.beginPath(); c.moveTo(0, 18); c.quadraticCurveTo(-8, 28, -15, 22); c.moveTo(0, 18); c.quadraticCurveTo(8, 28, 15, 22); stroke(c, 4, pal.deep);
        c.beginPath(); [[-52, 8, -30, 12], [-52, 20, -30, 18], [52, 8, 30, 12], [52, 20, 30, 18]].forEach(l => { c.moveTo(l[0], l[1]); c.lineTo(l[2], l[3]); }); stroke(c, 3, 'rgba(0,0,0,.35)'); break;
      case 2: // Робот
        c.beginPath(); c.moveTo(0, -50); c.lineTo(0, -70); stroke(c, 6, pal.dark); circle(c, 0, -74, 9, '#ff5a5a');
        body(); rrect(c, -38, -26, 76, 36, 13); c.fillStyle = '#1c2236'; c.fill();
        circle(c, -19 + lx * 3, -8 + ly * 3, 10, '#5ff1ff'); circle(c, 19 + lx * 3, -8 + ly * 3, 10, '#5ff1ff');
        circle(c, -22 + lx * 3, -12 + ly * 3, 3.5, '#fff'); circle(c, 16 + lx * 3, -12 + ly * 3, 3.5, '#fff');
        rrect(c, -22, 20, 44, 15, 6); c.fillStyle = '#1c2236'; c.fill();
        c.beginPath(); [-11, 0, 11].forEach(x => { c.moveTo(x, 22); c.lineTo(x, 33); }); stroke(c, 3, '#8c96b8');
        circle(c, -42, 38, 3.5, pal.dark); circle(c, 42, 38, 3.5, pal.dark); break;
      case 3: { // Призрак
        c.beginPath(); c.moveTo(-46, 42); c.lineTo(-46, -4); c.arc(0, -4, 46, Math.PI, 0); c.lineTo(46, 42);
        c.quadraticCurveTo(34, 56, 23, 42); c.quadraticCurveTo(11, 56, 0, 42); c.quadraticCurveTo(-11, 56, -23, 42); c.quadraticCurveTo(-34, 56, -46, 42); c.closePath();
        const g = c.createLinearGradient(0, -50, 0, 50); g.addColorStop(0, '#fff'); g.addColorStop(1, pal.pale); c.fillStyle = g; c.fill(); stroke(c, 7, pal.base);
        c.fillStyle = '#1b1f33'; [-17, 17].forEach(x => { c.beginPath(); c.ellipse(x + lx * 3, -8 + ly * 3, 8, 12, 0, 0, 7); c.fill(); });
        circle(c, -14 + lx * 3, -13 + ly * 3, 3, '#fff'); circle(c, 20 + lx * 3, -13 + ly * 3, 3, '#fff');
        c.beginPath(); c.ellipse(0, 20, 7, 9, 0, 0, 7); c.fillStyle = '#1b1f33'; c.fill();
        circle(c, -30, 12, 7, 'rgba(255,110,150,.45)'); circle(c, 30, 12, 7, 'rgba(255,110,150,.45)'); break; }
      case 4: // Пришелец
        [[-1], [1]].forEach(s => { c.beginPath(); c.moveTo(s[0] * 22, -48); c.lineTo(s[0] * 34, -70); stroke(c, 5, pal.dark); circle(c, s[0] * 35, -73, 8, '#fff36b'); });
        body(); c.fillStyle = '#12151f';
        [-1, 1].forEach(s => { c.beginPath(); c.ellipse(s * 22 + lx * 2, -2 + ly * 2, 15, 25, s * 0.5, 0, 7); c.fill(); circle(c, s * 22 - 4 + lx * 2, -12 + ly * 2, 4.5, '#fff'); });
        c.beginPath(); c.moveTo(-10, 34); c.quadraticCurveTo(0, 40, 10, 34); stroke(c, 4, pal.deep); break;
      case 5: // Панда
        circle(c, -38, -42, 19, '#1b1f33'); circle(c, 38, -42, 19, '#1b1f33');
        body(); c.beginPath(); c.ellipse(0, 4, 40, 38, 0, 0, 7); c.fillStyle = '#fff'; c.fill();
        c.fillStyle = '#1b1f33'; [-1, 1].forEach(s => { c.save(); c.translate(s * 19, -2); c.rotate(-s * 0.35); c.beginPath(); c.ellipse(0, 0, 11, 15, 0, 0, 7); c.fill(); c.restore(); });
        circle(c, -19 + lx * 2, -3 + ly * 2, 5.5, '#fff'); circle(c, 19 + lx * 2, -3 + ly * 2, 5.5, '#fff');
        circle(c, -19 + lx * 3, -3 + ly * 3, 2.6, '#1b1f33'); circle(c, 19 + lx * 3, -3 + ly * 3, 2.6, '#1b1f33');
        c.beginPath(); c.ellipse(0, 16, 9, 6, 0, 0, 7); c.fillStyle = '#1b1f33'; c.fill();
        c.beginPath(); c.moveTo(0, 21); c.quadraticCurveTo(-9, 32, -16, 27); c.moveTo(0, 21); c.quadraticCurveTo(9, 32, 16, 27); stroke(c, 3.5, '#1b1f33'); break;
      case 6: // Лис
        tri(c, [-48, -20], [-42, -68], [-8, -48], pal.dark); tri(c, [48, -20], [42, -68], [8, -48], pal.dark);
        tri(c, [-42, -30], [-39, -56], [-22, -44], '#fff'); tri(c, [42, -30], [39, -56], [22, -44], '#fff');
        body(); c.save(); rrect(c, -50, -50, 100, 100, 24); c.clip();
        tri(c, [0, 56], [-60, 0], [60, 0], '#fff'); c.restore(); rrect(c, -50, -50, 100, 100, 24); stroke(c, 7, pal.dark);
        c.fillStyle = '#1b1f33'; [-1, 1].forEach(s => { c.save(); c.translate(s * 21 + lx * 2, -12 + ly * 2); c.rotate(s * 0.3); c.beginPath(); c.ellipse(0, 0, 8, 6, 0, 0, 7); c.fill(); c.restore(); });
        circle(c, 0, 26, 7, '#1b1f33'); break;
      case 7: // Монстр
        tri(c, [-34, -44], [-26, -70], [-12, -46], '#fff'); tri(c, [34, -44], [26, -70], [12, -46], '#fff');
        body(); eye(c, 0 + lx * 2, -12 + ly * 2, 25, lx * 1.4, ly * 1.4);
        rrect(c, -32, 22, 64, 20, 8); c.fillStyle = '#3a1226'; c.fill();
        [-22, -4, 14].forEach(x => tri(c, [x, 22], [x + 16, 22], [x + 8, 33], '#fff')); break;
      case 8: // Крутой
        body(); c.fillStyle = '#12151f';
        rrect(c, -42, -24, 36, 28, 9); c.fill(); rrect(c, 6, -24, 36, 28, 9); c.fill();
        c.fillRect(-8, -18, 16, 6); c.beginPath(); c.moveTo(-42, -16); c.lineTo(-50, -20); c.moveTo(42, -16); c.lineTo(50, -20); stroke(c, 5, '#12151f');
        c.beginPath(); c.moveTo(-34, -16); c.lineTo(-24, -20); c.moveTo(14, -16); c.lineTo(24, -20); stroke(c, 4, 'rgba(255,255,255,.7)');
        c.beginPath(); c.moveTo(-18, 24); c.quadraticCurveTo(2, 36, 22, 18); stroke(c, 6, pal.deep); break;
      default: // Король
        c.beginPath(); c.moveTo(-34, -48); c.lineTo(-40, -78); c.lineTo(-18, -62); c.lineTo(0, -84); c.lineTo(18, -62); c.lineTo(40, -78); c.lineTo(34, -48); c.closePath();
        c.fillStyle = '#ffcf33'; c.fill(); stroke(c, 5, '#c98a00'); circle(c, 0, -62, 5.5, '#ff4d5e'); circle(c, -22, -57, 4, '#3d9bff'); circle(c, 22, -57, 4, '#8bd94f');
        body(); eye(c, -20, -4, 13, lx, ly); eye(c, 20, -4, 13, lx, ly);
        c.beginPath(); c.arc(0, 12, 18, 0.15 * Math.PI, 0.85 * Math.PI); stroke(c, 6, pal.deep);
        c.beginPath(); c.moveTo(-22, 8); c.quadraticCurveTo(-10, 2, 0, 8); c.quadraticCurveTo(10, 2, 22, 8); stroke(c, 4, 'rgba(0,0,0,.4)');
    }
    c.restore();
  }

  // ====================== Игра ======================
  const canvas = $('game'), ctx = canvas.getContext('2d');
  let DPR = 1, CW = 0, CH = 0;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2.5);
    CW = window.innerWidth; CH = window.innerHeight;
    canvas.width = Math.round(CW * DPR); canvas.height = Math.round(CH * DPR);
  }
  window.addEventListener('resize', resize); resize();

  let G = null; // текущая партия
  let screen = 'menu';
  const particles = [], floaters = [];

  function startGame(mode, li) {
    const cfg = mode === 'endless' ? ENDLESS : LEVELS[li];
    const world = new World({
      w: cfg.size, h: cfg.size, bots: cfg.bots, names: NAMES, paletteSize: PAL.length,
      playerColor: S.color, playerName: (S.name || 'Игрок').trim() || 'Игрок', playerSkin: S.skin,
      skill: mode === 'endless' ? t => ENDLESS.skill0 + (1 - ENDLESS.skill0) * Math.min(1, t / ENDLESS.rampSec) : () => cfg.skill,
      botSpeed: mode === 'endless' ? s => 0.82 + 0.18 * s : () => cfg.speed,
      respawn: true, respawnDelay: 3500,
    });
    const p = world.player;
    G = { mode, li, cfg, world, acc: 0, over: false, paused: false, hudT: 0, miniT: 0, shake: 0,
      cam: { x: p.x + 0.5, y: p.y + 0.5, vc: 18 }, maxPct: 0, ended: false, endAt: 0 };
    particles.length = 0; floaters.length = 0;
    show(null); $('hud').classList.remove('hidden'); $('hint').classList.remove('hidden');
    $('goal').textContent = mode === 'endless' ? 'Бесконечный режим' : 'Уровень ' + (li + 1) + ' · цель ' + cfg.target + '%';
    $('bar').style.display = mode === 'endless' ? 'none' : '';
    mini.width = mini.height = cfg.size; mctx = mini.getContext('2d'); mimg = mctx.createImageData(cfg.size, cfg.size);
    screen = 'game';
  }

  // ---- ввод ----
  const KEYDIR = { ArrowUp: 0, KeyW: 0, ArrowRight: 1, KeyD: 1, ArrowDown: 2, KeyS: 2, ArrowLeft: 3, KeyA: 3 };
  function turn(d) {
    if (!G || G.over || G.paused || screen !== 'game') return;
    const wasStarted = G.world.started;
    G.world.turn(G.world.player, d);
    if (!wasStarted && G.world.started) $('hint').classList.add('hidden');
  }
  window.addEventListener('keydown', e => {
    if (e.code in KEYDIR && document.activeElement !== $('nameInput')) { turn(KEYDIR[e.code]); e.preventDefault(); }
    else if (e.code === 'Escape' || e.code === 'KeyP') window.__back();
  });
  let tp = null;
  canvas.addEventListener('pointerdown', e => { tp = { x: e.clientX, y: e.clientY, id: e.pointerId }; try { canvas.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ } });
  canvas.addEventListener('pointermove', e => {
    if (!tp || e.pointerId !== tp.id) return;
    const dx = e.clientX - tp.x, dy = e.clientY - tp.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) > 16) {
      turn(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 1 : 3) : (dy > 0 ? 2 : 0));
      tp.x = e.clientX; tp.y = e.clientY;
    }
  });
  const endTouch = e => { if (tp && e.pointerId === tp.id) tp = null; };
  canvas.addEventListener('pointerup', endTouch); canvas.addEventListener('pointercancel', endTouch);

  // ---- цикл ----
  let last = performance.now();
  function frame(now) {
    const dt = Math.min(100, now - last); last = now;
    if (screen === 'game' && G && !G.paused) update(dt);
    if (G && (screen === 'game' || screen === 'dlg')) render(now);
    drawMenuHeroes(now);
    requestAnimationFrame(frame);
  }

  function update(dt) {
    const w = G.world;
    G.acc += dt;
    let steps = 0;
    while (G.acc >= TICK && steps < 4) { G.acc -= TICK; steps++; if (!G.over || true) w.step(); handleEvents(); checkWin(); }
    if (steps === 4) G.acc = 0;
    // камера
    const p = w.player, cam = G.cam;
    const cnt = w.cnt[p.id] || 0;
    const tvc = clamp(16 + Math.sqrt(Math.max(cnt, 25)) * 0.5, 16, 34);
    cam.vc += (tvc - cam.vc) * Math.min(1, dt * 0.002);
    const t = p.alive ? entT(p) : 1;
    const tx = (p.px + (p.x - p.px) * t) + 0.5, ty = (p.py + (p.y - p.py) * t) + 0.5;
    const k = Math.min(1, dt * 0.012);
    cam.x += (tx - cam.x) * k; cam.y += (ty - cam.y) * k;
    // частицы
    for (let i = particles.length - 1; i >= 0; i--) {
      const q = particles[i]; q.life -= dt; if (q.life <= 0) { particles.splice(i, 1); continue; }
      q.x += q.vx * dt * 0.001; q.y += q.vy * dt * 0.001; q.vy += 6 * dt * 0.001; q.rot += q.vr * dt * 0.001;
    }
    for (let i = floaters.length - 1; i >= 0; i--) { const f = floaters[i]; f.life -= dt; f.y -= dt * 0.0016; if (f.life <= 0) floaters.splice(i, 1); }
    G.shake = Math.max(0, G.shake - dt * 0.004);
    G.hudT -= dt; if (G.hudT <= 0) { G.hudT = 160; updateHud(); }
    G.miniT -= dt; if (G.miniT <= 0) { G.miniT = 350; drawMini(); }
    if (G.ended && performance.now() >= G.endAt && !G.shown) { G.shown = true; G.onEnd(); }
  }

  function entT(e) {
    const w = G.world;
    const iv = TICK / e.speed;
    return clamp((w.time + G.acc - e.lastMove) / iv, 0, 1);
  }

  function handleEvents() {
    const w = G.world, p = w.player;
    for (const ev of w.events.splice(0)) {
      if (ev.t === 'death') {
        const col = PAL[ev.e.color];
        for (let i = 0; i < 34; i++) {
          const a = Math.random() * 6.283, sp = 2 + Math.random() * 6;
          particles.push({ x: ev.x + 0.5, y: ev.y + 0.5, vx: Math.cos(a) * sp * 1000, vy: Math.sin(a) * sp * 1000 - 2000, life: 700 + Math.random() * 600, rot: Math.random() * 6, vr: (Math.random() - 0.5) * 12000, s: 0.18 + Math.random() * 0.25, c: i % 3 ? col.base : col.light });
        }
        if (ev.e === p) {
          sfx.die(); try { navigator.vibrate && navigator.vibrate(60); } catch (e) { /* ignore */ }
          G.over = true; G.ended = true; G.endAt = performance.now() + 1100; G.shown = false;
          G.killer = ev.killer; G.onEnd = showDefeat; G.shake = 1;
        } else if (ev.killer === p) {
          sfx.kill(); toast('Ты съел ' + ev.e.name + '!');
        }
      } else if (ev.t === 'capture' && ev.e === p) {
        if (ev.gained > 3) {
          sfx.capture();
          const pct = ev.gained / w.N * 100;
          floaters.push({ x: p.x + 0.5, y: p.y - 0.8, text: '+' + (pct < 10 ? pct.toFixed(1) : Math.round(pct)) + '%', life: 1100, c: PAL[p.color].base });
        }
      }
    }
  }

  function checkWin() {
    if (!G || G.over || G.mode !== 'level') return;
    const w = G.world, p = w.player;
    if (p.alive && w.cnt[p.id] / w.N * 100 >= G.cfg.target) {
      G.over = true; G.ended = true; G.endAt = performance.now() + 700; G.shown = false; G.onEnd = showWin;
      sfx.win();
    }
  }

  let toastT = 0;
  function toast(t) { const el = $('toast'); el.textContent = t; el.classList.add('show'); clearTimeout(toastT); toastT = setTimeout(() => el.classList.remove('show'), 1500); }

  function updateHud() {
    const w = G.world, p = w.player;
    const pct = (w.cnt[p.id] || 0) / w.N * 100;
    G.maxPct = Math.max(G.maxPct, pct);
    $('pct').textContent = pct.toFixed(1) + '%';
    if (G.mode === 'level') $('bar').firstElementChild.style.width = clamp(pct / G.cfg.target * 100, 0, 100) + '%';
    const sec = Math.floor(w.time / 1000);
    $('stats').textContent = '⚔ ' + p.kills + '   ⏱ ' + Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');
    const top = w.rank().slice(0, 5);
    let html = '';
    for (const e of top) html += '<div class="' + (e === p ? 'me' : '') + '"><b style="background:' + PAL[e.color].base + '"></b><em>' + esc(e.name) + '</em><span>' + (w.cnt[e.id] / w.N * 100).toFixed(1) + '%</span></div>';
    if (p.alive && top.indexOf(p) < 0) html += '<div class="me"><b style="background:' + PAL[p.color].base + '"></b><em>' + (w.rank().indexOf(p) + 1) + '. ' + esc(p.name) + '</em><span>' + pct.toFixed(1) + '%</span></div>';
    $('board').innerHTML = html;
  }
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---- мини-карта ----
  const mini = $('mini'); let mctx = null, mimg = null;
  function drawMini() {
    if (!G) return;
    const w = G.world, d = mimg.data, o = w.owner;
    for (let i = 0, n = w.N; i < n; i++) {
      const id = o[i], j = i * 4;
      if (id) { const c = PAL[w.ents[id - 1].color].rgb; d[j] = c[0]; d[j + 1] = c[1]; d[j + 2] = c[2]; d[j + 3] = 255; }
      else { d[j] = 230; d[j + 1] = 236; d[j + 2] = 243; d[j + 3] = 255; }
    }
    const p = w.player;
    if (p.alive) { const i = (p.y * w.W + p.x) * 4; for (let k = -1; k <= 1; k++) for (let l = -1; l <= 1; l++) { const x = p.x + k, y = p.y + l; if (x >= 0 && y >= 0 && x < w.W && y < w.H) { const j = (y * w.W + x) * 4; d[j] = d[j + 1] = d[j + 2] = 255; d[j + 3] = 255; } } void i; }
    mctx.putImageData(mimg, 0, 0);
  }

  // ====================== Отрисовка ======================
  function render(now) {
    const w = G.world, cam = G.cam, W = w.W, H = w.H;
    const c = ctx;
    c.setTransform(DPR, 0, 0, DPR, 0, 0);
    const cs = Math.min(CW, CH) / cam.vc;
    let sx = 0, sy = 0;
    if (G.shake > 0) { sx = (Math.random() - 0.5) * 14 * G.shake; sy = (Math.random() - 0.5) * 14 * G.shake; }
    const ox = CW / 2 - cam.x * cs + sx, oy = CH / 2 - cam.y * cs + sy;
    const X = x => Math.round(ox + x * cs), Y = y => Math.round(oy + y * cs);

    // за пределами арены
    c.fillStyle = '#2c3144'; c.fillRect(0, 0, CW, CH);
    // пол
    const fx0 = X(0), fy0 = Y(0), fx1 = X(W), fy1 = Y(H);
    c.fillStyle = '#f1f4f8'; c.fillRect(fx0, fy0, fx1 - fx0, fy1 - fy0);

    const vx0 = clamp(Math.floor((0 - ox) / cs), 0, W - 1), vx1 = clamp(Math.ceil((CW - ox) / cs), 0, W - 1);
    const vy0 = clamp(Math.floor((0 - oy) / cs), 0, H - 1), vy1 = clamp(Math.ceil((CH - oy) / cs), 0, H - 1);

    // сетка
    c.beginPath();
    for (let x = vx0; x <= vx1 + 1; x++) { const px = X(x) + 0.5; c.moveTo(px, Math.max(0, fy0)); c.lineTo(px, Math.min(CH, fy1)); }
    for (let y = vy0; y <= vy1 + 1; y++) { const py = Y(y) + 0.5; c.moveTo(Math.max(0, fx0), py); c.lineTo(Math.min(CW, fx1), py); }
    c.lineWidth = 1; c.strokeStyle = '#dbe2ea'; c.stroke();

    // территории (по горизонтальным сериям)
    const own = w.owner, ents = w.ents;
    for (let y = vy0; y <= vy1; y++) {
      let x = vx0; const row = y * W;
      while (x <= vx1) {
        const id = own[row + x]; if (!id) { x++; continue; }
        let x2 = x + 1; while (x2 <= vx1 && own[row + x2] === id) x2++;
        c.fillStyle = PAL[ents[id - 1].color].base;
        c.fillRect(X(x), Y(y), X(x2) - X(x), Y(y + 1) - Y(y));
        x = x2;
      }
    }
    // «объём» — боковые грани снизу
    const sh = Math.max(2, Math.round(cs * 0.2));
    for (let y = vy0; y <= vy1 + 0; y++) {
      let x = vx0; const row = y * W;
      while (x <= vx1) {
        const id = own[row + x];
        if (!id || (y < H - 1 && own[row + W + x] === id)) { x++; continue; }
        let x2 = x + 1;
        while (x2 <= vx1 && own[row + x2] === id && !(y < H - 1 && own[row + W + x2] === id)) x2++;
        c.fillStyle = PAL[ents[id - 1].color].dark;
        c.fillRect(X(x), Y(y + 1), X(x2) - X(x), sh);
        x = x2;
      }
    }
    // светлая кромка сверху у границ территорий
    c.fillStyle = 'rgba(255,255,255,.28)';
    const hl = Math.max(1, Math.round(cs * 0.07));
    for (let y = vy0; y <= vy1; y++) {
      let x = vx0; const row = y * W;
      while (x <= vx1) {
        const id = own[row + x];
        if (!id || (y > 0 && own[row - W + x] === id)) { x++; continue; }
        let x2 = x + 1;
        while (x2 <= vx1 && own[row + x2] === id && !(y > 0 && own[row - W + x2] === id)) x2++;
        c.fillRect(X(x), Y(y), X(x2) - X(x), hl);
        x = x2;
      }
    }

    // следы
    for (const e of ents) {
      if (!e.alive || !e.trail.length) continue;
      const pal = PAL[e.color], tr = e.trail;
      c.fillStyle = pal.light;
      for (let k = 0; k < tr.length - 1; k++) {
        const i = tr[k], x = i % W, y = (i / W) | 0;
        if (x < vx0 - 1 || x > vx1 + 1 || y < vy0 - 1 || y > vy1 + 1) continue;
        c.fillRect(X(x), Y(y), X(x + 1) - X(x), Y(y + 1) - Y(y));
      }
      // хвост до головы
      const t = entT(e), dx = e.x - e.px, dy = e.y - e.py;
      if (t > 0.5 && (dx || dy)) {
        const hx = e.px + dx * t + 0.5, hy = e.py + dy * t + 0.5; // центр головы
        const ex = e.px + dx * 0.5 + 0.5, ey = e.py + dy * 0.5 + 0.5; // граница клетки
        const x0 = Math.min(hx, ex) - (dy ? 0.5 : 0), x1 = Math.max(hx, ex) + (dy ? 0.5 : 0);
        const y0 = Math.min(hy, ey) - (dx ? 0.5 : 0), y1 = Math.max(hy, ey) + (dx ? 0.5 : 0);
        c.fillRect(X(x0), Y(y0), X(x1) - X(x0), Y(y1) - Y(y0));
      }
    }

    // граница арены
    c.lineWidth = Math.max(3, cs * 0.18); c.strokeStyle = '#161a28';
    c.strokeRect(fx0 - c.lineWidth / 2, fy0 - c.lineWidth / 2, fx1 - fx0 + c.lineWidth, fy1 - fy0 + c.lineWidth);

    // головы (сортировка по Y)
    const alive = ents.filter(e => e.alive).sort((a, b) => a.y - b.y);
    for (const e of alive) {
      const t = entT(e);
      const hx = e.px + (e.x - e.px) * t + 0.5, hy = e.py + (e.y - e.py) * t + 0.5;
      const px = ox + hx * cs, py = oy + hy * cs;
      if (px < -cs * 3 || px > CW + cs * 3 || py < -cs * 3 || py > CH + cs * 3) continue;
      // тень
      c.fillStyle = 'rgba(0,0,0,.22)'; c.beginPath(); c.ellipse(px, py + cs * 0.52, cs * 0.5, cs * 0.18, 0, 0, 7); c.fill();
      const dir = e.dir < 0 ? 2 : e.dir, DXs = [0, 1, 0, -1], DYs = [-1, 0, 1, 0];
      const bob = Math.sin((now + e.id * 200) / 160) * cs * 0.03;
      drawHead(c, px, py - cs * 0.12 + bob, cs * 1.12, e.skin, PAL[e.color], DXs[dir], DYs[dir]);
      // имя
      c.font = '800 ' + Math.max(11, Math.round(cs * 0.5)) + 'px system-ui,sans-serif'; c.textAlign = 'center'; c.textBaseline = 'alphabetic';
      c.lineWidth = Math.max(3, cs * 0.13); c.strokeStyle = 'rgba(15,20,40,.75)'; c.lineJoin = 'round';
      const ny = py - cs * (e.skin === 2 || e.skin === 9 ? 1.2 : 0.95);
      c.strokeText(e.name, px, ny); c.fillStyle = e.id === 1 ? '#ffe14d' : '#fff'; c.fillText(e.name, px, ny);
    }

    // частицы
    for (const q of particles) {
      c.save(); c.translate(ox + q.x * cs, oy + q.y * cs); c.rotate(q.rot); c.globalAlpha = clamp(q.life / 400, 0, 1);
      c.fillStyle = q.c; const s = q.s * cs; c.fillRect(-s / 2, -s / 2, s, s); c.restore();
    }
    c.globalAlpha = 1;
    // всплывающий текст
    for (const f of floaters) {
      c.globalAlpha = clamp(f.life / 500, 0, 1);
      c.font = '900 ' + Math.round(cs * 0.95) + 'px system-ui,sans-serif'; c.textAlign = 'center';
      const fx = ox + f.x * cs, fy = oy + f.y * cs;
      c.lineWidth = cs * 0.2; c.strokeStyle = 'rgba(20,24,45,.8)'; c.strokeText(f.text, fx, fy); c.fillStyle = '#fff'; c.fillText(f.text, fx, fy);
    }
    c.globalAlpha = 1;
  }

  // ====================== Меню / экраны ======================
  const screens = { menu: $('menu'), levels: $('levels'), chars: $('chars'), dlg: $('dlg') };
  function show(name) {
    for (const k in screens) screens[k].classList.toggle('hidden', k !== name);
    if (name === 'menu' || name === 'levels' || name === 'chars') { $('hud').classList.add('hidden'); screen = name; }
    else if (name === 'dlg') screen = 'dlg';
    if (name === 'menu') $('menuName').textContent = S.name || 'Игрок';
    if (name === 'levels') buildLevels();
    if (name === 'chars') buildChars();
  }

  // фон меню — плавающие квадратики
  (function () {
    const bg = document.querySelector('.bgsquares');
    for (let i = 0; i < 14; i++) {
      const el = document.createElement('i'), s = 30 + Math.random() * 70;
      el.style.cssText = 'left:' + Math.random() * 100 + '%;width:' + s + 'px;height:' + s + 'px;background:' + PALETTE[i % PALETTE.length] + ';animation-duration:' + (14 + Math.random() * 16) + 's;animation-delay:-' + Math.random() * 20 + 's';
      bg.appendChild(el);
    }
  })();

  const heroC = $('menuHero'), heroX = heroC.getContext('2d');
  const prevC = $('charPreview'), prevX = prevC.getContext('2d');
  function drawMenuHeroes(now) {
    if (screen === 'menu') {
      heroX.clearRect(0, 0, 240, 240);
      const t = now / 1000;
      heroX.fillStyle = 'rgba(0,0,0,.25)'; heroX.beginPath(); heroX.ellipse(120, 205, 70 - Math.sin(t * 3) * 4, 14, 0, 0, 7); heroX.fill();
      drawHead(heroX, 120, 110 + Math.sin(t * 3) * 6, 150, S.skin, PAL[S.color], Math.cos(t * 1.3), Math.sin(t * 1.7) * 0.6);
    } else if (screen === 'chars') {
      prevX.clearRect(0, 0, 300, 300); const t = now / 1000;
      prevX.fillStyle = 'rgba(0,0,0,.25)'; prevX.beginPath(); prevX.ellipse(150, 262, 90 - Math.sin(t * 3) * 5, 17, 0, 0, 7); prevX.fill();
      drawHead(prevX, 150, 140 + Math.sin(t * 3) * 7, 190, S.skin, PAL[S.color], Math.cos(t * 1.3), Math.sin(t * 1.7) * 0.6);
    }
  }

  function buildLevels() {
    const g = $('levelGrid'); g.innerHTML = '';
    LEVELS.forEach((L, i) => {
      const b = document.createElement('button'), locked = i >= S.unlocked, st = S.stars[i] || 0;
      b.className = 'lv' + (locked ? ' locked' : '') + (i === S.unlocked - 1 ? ' cur' : '');
      b.innerHTML = '<div class="n">' + (i + 1) + '</div><div class="s">' + '★'.repeat(st) + '<u>' + '★'.repeat(3 - st) + '</u></div><div class="d">Цель: ' + L.target + '% поля<br>Боты: ' + L.bots + ' · ' + ['новички', 'лёгкие', 'средние', 'средние+', 'хитрые', 'опасные', 'сильные', 'очень сильные', 'мастера', 'легенды'][i] + '</div>';
      if (!locked) b.onclick = () => { sfx.click(); startGame('level', i); };
      g.appendChild(b);
    });
  }

  function buildChars() {
    $('nameInput').value = S.name || '';
    const sg = $('skinGrid'); sg.innerHTML = '';
    SKINS.forEach((n, i) => {
      const b = document.createElement('button'), cv = document.createElement('canvas');
      cv.width = cv.height = 96; b.className = 'sk' + (i === S.skin ? ' sel' : ''); b.title = n; b.appendChild(cv);
      drawHead(cv.getContext('2d'), 48, 52, 66, i, PAL[S.color], 0, 0.3);
      b.onclick = () => { S.skin = i; save(); sfx.click(); buildChars(); };
      sg.appendChild(b);
    });
    const cr = $('colorRow'); cr.innerHTML = '';
    PALETTE.forEach((h, i) => {
      const b = document.createElement('button'); b.className = 'cl' + (i === S.color ? ' sel' : ''); b.style.background = h;
      b.onclick = () => { S.color = i; save(); sfx.click(); buildChars(); };
      cr.appendChild(b);
    });
  }
  $('nameInput').addEventListener('input', e => { S.name = e.target.value.slice(0, 12); save(); });

  // ---- диалоги ----
  function dialog(title, body, stars, buttons) {
    $('dTitle').textContent = title; $('dBody').textContent = body;
    $('dStars').innerHTML = stars == null ? '' : '★'.repeat(stars) + '<u>' + '★'.repeat(3 - stars) + '</u>';
    const bt = $('dBtns'); bt.innerHTML = '';
    for (const b of buttons) { const el = document.createElement('button'); el.className = 'btn ' + b[1]; el.textContent = b[0]; el.onclick = () => { sfx.click(); b[2](); }; bt.appendChild(el); }
    screens.dlg.classList.remove('hidden'); screen = 'dlg';
  }
  const hideDlg = () => { screens.dlg.classList.add('hidden'); screen = 'game'; };
  const toMenu = () => { G = null; screens.dlg.classList.add('hidden'); show('menu'); };
  const again = () => { const g = G; screens.dlg.classList.add('hidden'); startGame(g.mode, g.li); };

  function fmtT(sec) { return Math.floor(sec / 60) + ':' + String(Math.floor(sec % 60)).padStart(2, '0'); }

  function showDefeat() {
    const w = G.world, p = w.player; const pct = G.maxPct;
    let body = (G.killer ? 'Тебя съел ' + G.killer.name + '.\n' : 'Ты разбился.\n') + 'Макс. территория: ' + pct.toFixed(1) + '%\nУбито: ' + p.kills + '   Время: ' + fmtT(w.time / 1000);
    if (G.mode === 'endless') {
      let rec = false;
      if (pct > S.best.pct) { S.best.pct = +pct.toFixed(1); rec = true; }
      if (p.kills > S.best.kills) { S.best.kills = p.kills; rec = true; }
      if (rec) save();
      body += '\nРекорд: ' + S.best.pct + '% · ⚔ ' + S.best.kills + (rec ? '  — новый!' : '');
    }
    dialog('Ты проиграл', body, null, [['Заново', 'green', again], ['В меню', 'gray', toMenu]]);
  }

  function showWin() {
    const w = G.world, p = w.player, L = G.cfg, sec = w.time / 1000;
    const stars = sec <= L.t3 ? 3 : sec <= L.t2 ? 2 : 1;
    const li = G.li;
    if ((S.stars[li] || 0) < stars) S.stars[li] = stars;
    if (li + 1 < LEVELS.length && S.unlocked < li + 2) S.unlocked = li + 2;
    save();
    const btns = [];
    if (li + 1 < LEVELS.length) btns.push(['Следующий уровень', 'green', () => { screens.dlg.classList.add('hidden'); startGame('level', li + 1); }]);
    btns.push(['Ещё раз', 'blue', again], ['В меню', 'gray', toMenu]);
    dialog(li + 1 === LEVELS.length ? 'Ты чемпион!' : 'Уровень пройден!',
      'Захвачено: ' + (w.cnt[p.id] / w.N * 100).toFixed(1) + '%\nВремя: ' + fmtT(sec) + '   Убито: ' + p.kills + '\n3★ — быстрее ' + fmtT(L.t3) + ', 2★ — быстрее ' + fmtT(L.t2), stars, btns);
  }

  function pauseGame() {
    if (!G || G.over || screen !== 'game') return;
    G.paused = true;
    dialog('Пауза', G.mode === 'endless' ? 'Бесконечный режим' : 'Уровень ' + (G.li + 1) + ': цель ' + G.cfg.target + '%', null, [
      ['Продолжить', 'green', () => { hideDlg(); G.paused = false; last = performance.now(); }],
      ['Заново', 'orange', again], ['В меню', 'gray', toMenu]]);
  }

  // ---- кнопки ----
  $('bEndless').onclick = () => { sfx.click(); startGame('endless'); };
  $('bLevels').onclick = () => { sfx.click(); show('levels'); };
  $('bChar').onclick = () => { sfx.click(); show('chars'); };
  $('bCharDone').onclick = () => { sfx.click(); if (!(S.name || '').trim()) S.name = 'Игрок'; save(); show('menu'); };
  $('bSound').onclick = () => { S.sound = !S.sound; save(); $('bSound').textContent = 'Звук: ' + (S.sound ? 'вкл' : 'выкл'); sfx.click(); };
  $('bSound').textContent = 'Звук: ' + (S.sound ? 'вкл' : 'выкл');
  document.querySelectorAll('[data-back]').forEach(b => { b.onclick = () => { sfx.click(); show('menu'); }; });
  $('pauseBtn').onclick = pauseGame;

  // Кнопка «Назад» (Android) и сворачивание
  window.__back = function () {
    if (screen === 'game') { pauseGame(); return true; }
    if (screen === 'dlg') {
      if (G && G.paused) { hideDlg(); G.paused = false; last = performance.now(); return true; }
      toMenu(); return true;
    }
    if (screen === 'levels' || screen === 'chars') { show('menu'); return true; }
    return false;
  };
  window.__pause = function () { if (screen === 'game') pauseGame(); };
  document.addEventListener('visibilitychange', () => { if (document.hidden) window.__pause(); });

  show('menu');
  requestAnimationFrame(frame);
  window.__dbg = { get G() { return G; }, startGame, S, PAL };
})();
