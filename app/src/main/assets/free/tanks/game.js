// Танки: кампания из 6 уровней. Цель: пробраться на противоположный конец поля и уничтожить вражеский флаг.
// Управление: крестовина и кнопка огня. Прогресс сохраняется (G.save).
var NC = 13, NR = 15, LEVELS = 6, LIVES = 3, DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
var scr = 'menu', lvl = 0, unlocked = 1, map, pl, foes, bullets, lives, spawnT, flag, held = -1, fireHeld = false, parts, end = null, endT = 0, L = {}, btns = {}, sP, sE, sF, sB, tm = 0;
function RU() { return G.lang === 'ru' || G.lang === 'rk'; }
function tx(ru, en) { return RU() ? ru : en; }
function init() {
  sP = G.sprite('tk_player', 64, function (c, s) { tankArt(c, s, '#FF8FC7', '#C23B83'); });
  sE = G.sprite('tk_enemy', 64, function (c, s) { tankArt(c, s, '#8FB5FF', '#2F5FCC'); });
  sF = G.sprite('tk_flag', 64, function (c, s) {
    c.fillStyle = '#6B4A2B'; c.fillRect(s * 0.2, s * 0.08, s * 0.08, s * 0.86);
    c.fillStyle = '#FF4D6D'; c.beginPath(); c.moveTo(s * 0.28, s * 0.1); c.lineTo(s * 0.9, s * 0.28); c.lineTo(s * 0.28, s * 0.5); c.closePath(); c.fill();
    c.fillStyle = '#fff'; c.beginPath(); c.arc(s * 0.5, s * 0.29, s * 0.07, 0, 7); c.fill();
  });
  sB = G.sprite('tk_bullet', 16, function (c, s) { c.fillStyle = '#FFE066'; c.fillRect(s * 0.3, s * 0.1, s * 0.4, s * 0.8); });
  unlocked = Math.max(1, Math.min(LEVELS, +(G.load('tanks_prog') || 1) || 1));
}
function tankArt(c, s, body, accent) {
  c.fillStyle = '#3A3A3A'; c.fillRect(s * 0.08, s * 0.1, s * 0.2, s * 0.82); c.fillRect(s * 0.72, s * 0.1, s * 0.2, s * 0.82);
  c.fillStyle = accent; for (var i = 0; i < 6; i++) { c.fillRect(s * 0.08, s * (0.12 + i * 0.13), s * 0.2, s * 0.05); c.fillRect(s * 0.72, s * (0.12 + i * 0.13), s * 0.2, s * 0.05); }
  c.fillStyle = body; c.fillRect(s * 0.26, s * 0.2, s * 0.48, s * 0.66);
  c.fillStyle = accent; c.fillRect(s * 0.36, s * 0.38, s * 0.28, s * 0.28);
  c.fillStyle = body; c.fillRect(s * 0.44, 0, s * 0.12, s * 0.5);
  c.strokeStyle = '#222'; c.lineWidth = 2; c.strokeRect(s * 0.26, s * 0.2, s * 0.48, s * 0.66);
}
// ----- генерация уровней: детерминированный ГСЧ, проверка пути поиском в ширину -----
function rng(seed) { return function () { seed |= 0; seed = seed + 0x6D2B79F5 | 0; var t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
function makeMap(level) {
  for (var attempt = 0; attempt < 60; attempt++) {
    var R = rng(level * 977 + attempt * 31 + 7), m = [], r, c;
    for (r = 0; r < NR; r++) { m.push([]); for (c = 0; c < NC; c++) m[r].push('.'); }
    var dens = 0.16 + level * 0.025, steel = 0.04 + level * 0.025;
    for (r = 2; r < NR - 2; r++) for (c = 0; c < NC; c++) { var v = R(); if (v < steel) m[r][c] = '#'; else if (v < steel + dens) m[r][c] = 'B'; }
    // флаг сверху по центру и щит вокруг
    m[0][6] = 'F';
    var sh = [[0, 5], [0, 7], [1, 5], [1, 6], [1, 7]];
    sh.forEach(function (p) { m[p[0]][p[1]] = (level >= 4 && (p[1] !== 6 || p[0] === 1) && R() < 0.5) ? '#' : 'B'; });
    // стартовая площадка
    for (r = NR - 2; r < NR; r++) for (c = 4; c <= 8; c++) m[r][c] = '.';
    if (reach(m)) return m;
  }
  var f = []; for (var rr = 0; rr < NR; rr++) f.push(new Array(NC).fill('.')); f[0][6] = 'F'; return f;
}
function reach(m) { // от старта до клетки под флагом: кирпич проходим (его можно разбить), сталь нет
  var seen = {}, q = [[6, NR - 1]]; seen[6 + ',' + (NR - 1)] = 1;
  while (q.length) {
    var p = q.shift(); if (p[1] <= 2) return true;
    for (var d = 0; d < 4; d++) { var x = p[0] + DX[d], y = p[1] + DY[d]; if (x < 0 || y < 0 || x >= NC || y >= NR || seen[x + ',' + y] || m[y][x] === '#' || m[y][x] === 'F') continue; seen[x + ',' + y] = 1; q.push([x, y]); }
  }
  return false;
}
function mk(x, y, dir, me) { return { x: x, y: y, dir: dir, me: me, cd: 0, inv: 0, ai: 0, fire: G.rand(0.6, 1.6) }; }
function startLevel(i) {
  lvl = i; map = makeMap(i + 1); pl = mk(6.5, NR - 0.5, 0, true); pl.inv = 1.5; foes = []; bullets = []; parts = []; lives = LIVES; spawnT = 1.5; end = null; flag = { x: 6, y: 0, alive: true }; held = -1; fireHeld = false; scr = 'play';
}
function layout() {
  var w = G.w, h = G.h, d = G.dpr;
  if (w >= h * 1.15) { L.t = Math.floor((h - 8 * d) / NR); L.fx = Math.floor((w - L.t * NC) / 2); L.fy = Math.floor((h - L.t * NR) / 2); L.land = true;
    L.pc = { x: L.fx / 2, y: h / 2 }; L.fc = { x: L.fx + L.t * NC + (w - L.fx - L.t * NC) / 2, y: h / 2 }; L.r = Math.min(L.fx / 2.1, h * 0.28, 90 * d); L.fr = Math.min((w - L.fx - L.t * NC) / 2.3, h * 0.2, 70 * d); }
  else { L.t = Math.floor(w / NC); L.fx = Math.floor((w - L.t * NC) / 2); L.fy = 40 * d; L.land = false; var by = L.fy + L.t * NR, rem = h - by;
    L.r = Math.min(w * 0.2, rem * 0.36, 90 * d); L.fr = Math.min(w * 0.15, rem * 0.28, 70 * d); L.pc = { x: w * 0.27, y: by + rem / 2 }; L.fc = { x: w * 0.75, y: by + rem / 2 }; }
}
function onTouch(pts) {
  if (scr !== 'play' || end) { held = -1; fireHeld = false; return; }
  layout(); held = -1; fireHeld = false;
  for (var i = 0; i < pts.length; i++) {
    var p = pts[i], dx = p.x - L.pc.x, dy = p.y - L.pc.y, dd = dx * dx + dy * dy;
    if (dd < L.r * L.r * 1.6 && dd > L.r * L.r * 0.04) held = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 1 : 3) : (dy > 0 ? 2 : 0);
    var fx = p.x - L.fc.x, fy = p.y - L.fc.y; if (fx * fx + fy * fy < L.fr * L.fr * 1.5) fireHeld = true;
  }
}
function inR(r, x, y) { return r && x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h; }
function onTap(x, y) {
  if (scr === 'menu') { for (var i = 0; i < LEVELS; i++) if (inR(btns['l' + i], x, y) && i < unlocked) startLevel(i); return; }
  if (end && endT > 0.6) {
    if (inR(btns.again, x, y)) { startLevel(end === 'win' && lvl + 1 < LEVELS ? lvl + 1 : lvl); return; }
    if (inR(btns.back, x, y)) { scr = 'menu'; return; }
  }
  if (!end && inR(btns.back, x, y)) scr = 'menu';
}
function solid(c, r) { if (c < 0 || r < 0 || c >= NC || r >= NR) return true; var v = map[r][c]; return v === 'B' || v === '#' || v === 'F'; }
function blocked(t, x, y) {
  var h = 0.44, c0 = Math.floor(x - h), c1 = Math.floor(x + h), r0 = Math.floor(y - h), r1 = Math.floor(y + h);
  for (var r = r0; r <= r1; r++) for (var c = c0; c <= c1; c++) if (solid(c, r)) return true;
  var all = foes.concat([pl]);
  for (var i = 0; i < all.length; i++) { var o = all[i]; if (o === t) continue; if (Math.abs(o.x - x) < 0.9 && Math.abs(o.y - y) < 0.9) return true; }
  return false;
}
function move(t, dir, dt, sp) {
  if (dir !== t.dir) { t.dir = dir; if (dir % 2 === 0) t.x = Math.round(t.x * 2) / 2; else t.y = Math.round(t.y * 2) / 2; }
  var nx = t.x + DX[dir] * sp * dt, ny = t.y + DY[dir] * sp * dt;
  if (!blocked(t, nx, ny)) { t.x = nx; t.y = ny; return true; } return false;
}
function shoot(t) { if (t.cd > 0) return; for (var i = 0; i < bullets.length; i++) if (bullets[i].o === t) return; bullets.push({ x: t.x + DX[t.dir] * 0.6, y: t.y + DY[t.dir] * 0.6, dir: t.dir, me: t.me, o: t }); t.cd = 0.25; }
function boom(x, y) { parts.push({ x: x, y: y, t: 0 }); }
function finish(r) { end = r; endT = 0; if (r === 'win') { unlocked = Math.max(unlocked, Math.min(LEVELS, lvl + 2)); try { G.save('tanks_prog', String(unlocked)); } catch (e) {} } }
function update(dt) {
  tm += dt;
  if (scr !== 'play') return;
  if (end) { endT += dt; return; }
  pl.cd -= dt; pl.inv -= dt;
  if (held >= 0) move(pl, held, dt, 3.4);
  if (fireHeld) shoot(pl);
  var cap = [2, 3, 3, 4, 4, 5][lvl], sp = 1.5 + lvl * 0.12, fi = Math.max(0.7, 1.9 - lvl * 0.2);
  spawnT -= dt;
  if (spawnT <= 0 && foes.length < cap) {
    var corners = [[0.5, 0.5], [12.5, 0.5], [3.5, 0.5], [9.5, 0.5]], k;
    for (k = 0; k < 4; k++) { var s = corners[G.randInt(4)]; if (!blocked(null, s[0], s[1])) { foes.push(mk(s[0], s[1], 2, false)); spawnT = 3; break; } }
    if (k === 4) spawnT = 1;
  }
  foes.forEach(function (e) {
    e.cd -= dt; e.ai -= dt; e.fire -= dt;
    var ok = move(e, e.dir, dt, sp);
    if (!ok || e.ai <= 0) {
      e.ai = G.rand(0.8, 2); var r = Math.random();
      if (r < 0.35) { e.dir = Math.abs(pl.x - e.x) > Math.abs(pl.y - e.y) ? (pl.x > e.x ? 1 : 3) : (pl.y > e.y ? 2 : 0); } else e.dir = G.randInt(4);
    }
    var al = (Math.abs(e.x - pl.x) < 0.6 && ((e.dir === 2 && pl.y > e.y) || (e.dir === 0 && pl.y < e.y))) || (Math.abs(e.y - pl.y) < 0.6 && ((e.dir === 1 && pl.x > e.x) || (e.dir === 3 && pl.x < e.x)));
    if (e.fire <= 0 || (al && e.fire < fi * 0.5)) { shoot(e); e.fire = G.rand(fi * 0.6, fi * 1.4); }
  });
  for (var i = bullets.length - 1; i >= 0; i--) {
    var b = bullets[i], rm = false;
    for (var s2 = 0; s2 < 3 && !rm; s2++) {
      b.x += DX[b.dir] * 10 * dt / 3; b.y += DY[b.dir] * 10 * dt / 3;
      var c = Math.floor(b.x), r2 = Math.floor(b.y);
      if (c < 0 || r2 < 0 || c >= NC || r2 >= NR) { rm = true; break; }
      var v = map[r2][c];
      if (v === 'B') { map[r2][c] = '.'; boom(b.x, b.y); rm = true; }
      else if (v === '#') rm = true;
      else if (v === 'F') { rm = true; if (b.me) { map[r2][c] = '.'; flag.alive = false; boom(c + 0.5, r2 + 0.5); finish('win'); return; } }
      if (rm) break;
      if (b.me) {
        for (var j = foes.length - 1; j >= 0; j--) if (Math.abs(foes[j].x - b.x) < 0.5 && Math.abs(foes[j].y - b.y) < 0.5) { boom(foes[j].x, foes[j].y); foes.splice(j, 1); rm = true; break; }
      } else if (pl.inv <= 0 && Math.abs(pl.x - b.x) < 0.5 && Math.abs(pl.y - b.y) < 0.5) {
        rm = true; lives--; boom(pl.x, pl.y);
        if (lives <= 0) { finish('lose'); return; }
        pl.x = 6.5; pl.y = NR - 0.5; pl.dir = 0; pl.inv = 2;
      }
    }
    if (rm) bullets.splice(i, 1);
  }
  parts = parts.filter(function (p) { p.t += dt; return p.t < 0.35; });
}
function rrect(c, r, label, on) {
  c.fillStyle = on === false ? '#D9C6E8' : '#FF8FC7'; c.strokeStyle = '#fff'; c.lineWidth = 3 * G.dpr; c.beginPath();
  c.moveTo(r.x + r.h / 2, r.y); c.arcTo(r.x + r.w, r.y, r.x + r.w, r.y + r.h, r.h / 2); c.arcTo(r.x + r.w, r.y + r.h, r.x, r.y + r.h, r.h / 2); c.arcTo(r.x, r.y + r.h, r.x, r.y, r.h / 2); c.arcTo(r.x, r.y, r.x + r.w, r.y, r.h / 2); c.closePath(); c.fill(); c.stroke();
  c.fillStyle = on === false ? '#8A76A8' : '#4B2E83'; c.font = 'bold ' + Math.round(r.h * 0.4) + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(label, r.x + r.w / 2, r.y + r.h / 2 + 1); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
}
function drawTank(c, img, t) { var tl = L.t; c.save(); c.translate(L.fx + t.x * tl, L.fy + t.y * tl); c.rotate(t.dir * Math.PI / 2); c.drawImage(img, -tl * 0.5, -tl * 0.5, tl, tl); c.restore(); }
function arrow(c, cx, cy, r, dir, on) {
  c.save(); c.translate(cx, cy); c.rotate(dir * Math.PI / 2); c.fillStyle = on ? '#FF8FC7' : 'rgba(255,255,255,0.8)'; c.strokeStyle = '#4B2E83'; c.lineWidth = 2 * G.dpr;
  c.beginPath(); c.moveTo(0, -r * 0.95); c.lineTo(r * 0.45, -r * 0.45); c.lineTo(-r * 0.45, -r * 0.45); c.closePath(); c.fill(); c.stroke(); c.restore();
}
function drawMenu(c) {
  var w = G.w, h = G.h, d = G.dpr, U = Math.min(h / 11, w / 6), cx = w / 2; btns = {};
  c.fillStyle = '#FFE6F3'; c.fillRect(0, 0, w, h); c.fillStyle = '#4B2E83'; c.textAlign = 'center'; c.textBaseline = 'middle';
  c.font = 'bold ' + Math.round(U * 0.7) + 'px sans-serif'; c.fillText(tx('Танки: кампания', 'Tanks: campaign'), cx, U * 0.7);
  c.font = Math.round(U * 0.34) + 'px sans-serif'; c.fillText(tx('Уничтожь вражеский флаг на другом конце поля', 'Destroy the enemy flag at the far end of the field'), cx, U * 1.35, w - 20 * d);
  var cols = h > w ? 2 : 3, bw = Math.min(U * 3.2, (w - 40 * d) / cols - 10 * d), bh = U * 1.2, gx = (w - cols * bw - (cols - 1) * 10 * d) / 2, y0 = U * 2.1;
  for (var i = 0; i < LEVELS; i++) { var r = { x: gx + (i % cols) * (bw + 10 * d), y: y0 + Math.floor(i / cols) * (bh + 12 * d), w: bw, h: bh }; btns['l' + i] = r; rrect(c, r, (i < unlocked ? '' : '🔒 ').replace('🔒 ', '') + tx('Уровень ', 'Level ') + (i + 1), i < unlocked ? true : false); }
  c.textAlign = 'left'; c.textBaseline = 'alphabetic';
}
function draw(c) {
  if (scr === 'menu') return drawMenu(c);
  layout(); var t = L.t, d = G.dpr; btns = {};
  c.fillStyle = '#2B1A5E'; c.fillRect(0, 0, G.w, G.h); c.fillStyle = '#000'; c.fillRect(L.fx, L.fy, t * NC, t * NR);
  for (var r = 0; r < NR; r++) for (var q = 0; q < NC; q++) {
    var v = map[r][q], x = L.fx + q * t, y = L.fy + r * t;
    if (v === 'B') {
      c.fillStyle = '#B5502B'; c.fillRect(x, y, t, t); c.fillStyle = '#E58A5B';
      c.fillRect(x + 1, y + 1, t * 0.46, t * 0.2); c.fillRect(x + t * 0.52, y + 1, t * 0.46, t * 0.2); c.fillRect(x + 1, y + t * 0.4, t * 0.22, t * 0.2); c.fillRect(x + t * 0.28, y + t * 0.4, t * 0.46, t * 0.2); c.fillRect(x + t * 0.8, y + t * 0.4, t * 0.18, t * 0.2); c.fillRect(x + 1, y + t * 0.78, t * 0.46, t * 0.2); c.fillRect(x + t * 0.52, y + t * 0.78, t * 0.46, t * 0.2);
    } else if (v === '#') { c.fillStyle = '#9AA3AE'; c.fillRect(x, y, t, t); c.fillStyle = '#E9EEF5'; c.fillRect(x + t * 0.1, y + t * 0.1, t * 0.8, t * 0.8); c.fillStyle = '#6B7480'; c.fillRect(x + t * 0.3, y + t * 0.3, t * 0.4, t * 0.4); }
    else if (v === 'F') c.drawImage(sF, x, y, t, t);
  }
  foes.forEach(function (e) { drawTank(c, sE, e); });
  if (pl.inv <= 0 || Math.floor(pl.inv * 10) % 2) drawTank(c, sP, pl);
  bullets.forEach(function (b) { c.drawImage(sB, L.fx + b.x * t - t * 0.12, L.fy + b.y * t - t * 0.12, t * 0.24, t * 0.24); });
  parts.forEach(function (p) { var k = p.t / 0.35; c.fillStyle = 'rgba(255,200,60,' + (1 - k) + ')'; c.beginPath(); c.arc(L.fx + p.x * t, L.fy + p.y * t, t * (0.2 + k * 0.5), 0, 7); c.fill(); });
  c.fillStyle = '#FFF'; c.font = 'bold ' + Math.round(17 * d) + 'px sans-serif'; c.textBaseline = 'middle';
  var hy = L.land ? 18 * d : L.fy / 2, hx = L.land ? 10 * d : L.fx;
  c.fillText(tx('Уровень ', 'Level ') + (lvl + 1) + '   ' + tx('Жизни: ', 'Lives: ') + lives, hx, hy); c.textBaseline = 'alphabetic';
  var bk = { x: L.land ? 10 * d : G.w - 110 * d, y: L.land ? 40 * d : 6 * d, w: 100 * d, h: 30 * d }; btns.back = bk;
  if (!end) rrect(c, bk, tx('Уровни', 'Levels'), true);
  c.fillStyle = 'rgba(255,255,255,0.12)'; c.beginPath(); c.arc(L.pc.x, L.pc.y, L.r, 0, 7); c.fill();
  for (var k = 0; k < 4; k++) arrow(c, L.pc.x + DX[k] * L.r * 0.52, L.pc.y + DY[k] * L.r * 0.52, L.r * 0.45, k, held === k);
  c.fillStyle = fireHeld ? '#FF5C8A' : '#E04E5E'; c.strokeStyle = '#fff'; c.lineWidth = 3 * d; c.beginPath(); c.arc(L.fc.x, L.fc.y, L.fr, 0, 7); c.fill(); c.stroke();
  c.fillStyle = '#fff'; c.font = 'bold ' + Math.round(L.fr * 0.45) + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(tx('ОГОНЬ', 'FIRE'), L.fc.x, L.fc.y); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
  if (end) {
    var ow = t * NC * 0.84, oh = t * 6, ox = L.fx + (t * NC - ow) / 2, oy = L.fy + t * NR / 2 - oh / 2;
    c.fillStyle = 'rgba(255,255,255,0.94)'; c.strokeStyle = '#4B2E83'; c.lineWidth = 3 * d; c.fillRect(ox, oy, ow, oh); c.strokeRect(ox, oy, ow, oh);
    c.fillStyle = '#4B2E83'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.font = 'bold ' + Math.round(t * 0.9) + 'px sans-serif';
    var last = end === 'win' && lvl + 1 >= LEVELS;
    c.fillText(end === 'win' ? (last ? tx('Кампания пройдена!', 'Campaign complete!') : tx('Флаг уничтожен!', 'Flag destroyed!')) : tx('Танк уничтожен', 'Tank destroyed'), ox + ow / 2, oy + t * 1.2, ow - 10 * d);
    var b1 = { x: ox + ow * 0.06, y: oy + t * 2.7, w: ow * 0.88, h: t * 1.3 }, b2 = { x: b1.x, y: b1.y + t * 1.5, w: b1.w, h: t * 1.3 };
    if (!last) { btns.again = b1; rrect(c, b1, end === 'win' ? tx('Следующий уровень', 'Next level') : tx('Ещё раз', 'Try again'), true); btns.back = b2; rrect(c, b2, tx('К уровням', 'Levels'), true); }
    else { btns.back = b1; rrect(c, b1, tx('К уровням', 'Levels'), true); }
    c.textAlign = 'left'; c.textBaseline = 'alphabetic';
  }
}
