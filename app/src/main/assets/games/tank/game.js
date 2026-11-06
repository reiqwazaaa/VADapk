// BATTLE_CITY_V1
// Танки (клон «Battle City» с NES/Dendy). Кнопки на экране: крестовина и «огонь». Победа: уничтожить 6 вражеских танков.
var MAP = ['.............', '.BB.BB.BB.BB.', '.BB.BB.BB.BB.', '.BB.BB.BB.BB.', '......#......', 'BB.BB...BB.BB', '#...B...B...#', '.BB.......BB.', '.BB.BB.BB.BB.', '.BB.BB.BB.BB.', '.....#.#.....', '...B.BBB.B...', '.....BEB.....'];
var N = 13, GOAL = 6, LIVES = 3, SPAWNS = [[0.5, 0.5], [6.5, 0.5], [12.5, 0.5]], DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
var map, pl, foes, bullets, kills, spawned, lives, spawnT, eagleDead, held, fireHeld, parts, flash, L = {};
var sP, sE, sEagle, sB;
function init() {
  sP = G.sprite('bc_player', 64, function (c, s) { tankArt(c, s, '#F2C230', '#B8860B'); });
  sE = G.sprite('bc_enemy', 64, function (c, s) { tankArt(c, s, '#C9CED6', '#E04E5E'); });
  sEagle = G.sprite('bc_eagle', 64, function (c, s) {
    c.fillStyle = '#4B2E83'; c.fillRect(0, 0, s, s); c.fillStyle = '#FFE066';
    c.beginPath(); c.moveTo(s * 0.5, s * 0.12); c.lineTo(s * 0.64, s * 0.4); c.lineTo(s * 0.92, s * 0.3); c.lineTo(s * 0.74, s * 0.62); c.lineTo(s * 0.6, s * 0.9); c.lineTo(s * 0.4, s * 0.9); c.lineTo(s * 0.26, s * 0.62); c.lineTo(s * 0.08, s * 0.3); c.lineTo(s * 0.36, s * 0.4); c.closePath(); c.fill();
  });
  sB = G.sprite('bc_bullet', 16, function (c, s) { c.fillStyle = '#FFFFFF'; c.fillRect(s * 0.3, s * 0.1, s * 0.4, s * 0.8); });
  reset();
}
function tankArt(c, s, body, accent) { // танк смотрит вверх
  c.fillStyle = '#3A3A3A'; c.fillRect(s * 0.08, s * 0.1, s * 0.2, s * 0.82); c.fillRect(s * 0.72, s * 0.1, s * 0.2, s * 0.82);
  c.fillStyle = accent; for (var i = 0; i < 6; i++) { c.fillRect(s * 0.08, s * (0.12 + i * 0.13), s * 0.2, s * 0.05); c.fillRect(s * 0.72, s * (0.12 + i * 0.13), s * 0.2, s * 0.05); }
  c.fillStyle = body; c.fillRect(s * 0.26, s * 0.2, s * 0.48, s * 0.66);
  c.fillStyle = accent; c.fillRect(s * 0.36, s * 0.38, s * 0.28, s * 0.28);
  c.fillStyle = body; c.fillRect(s * 0.44, s * 0.0, s * 0.12, s * 0.5);
  c.strokeStyle = '#222'; c.lineWidth = 2; c.strokeRect(s * 0.26, s * 0.2, s * 0.48, s * 0.66);
}
function reset() {
  map = MAP.map(function (r) { return r.split(''); });
  pl = mk(4.5, 12.5, 0, true); pl.inv = 1.5;
  foes = []; bullets = []; parts = []; kills = 0; spawned = 0; lives = LIVES; spawnT = 0.8; eagleDead = false; held = -1; fireHeld = false; flash = 0;
}
function mk(x, y, dir, me) { return { x: x, y: y, dir: dir, me: me, cd: 0, inv: 0, ai: 0, fire: G.rand(0.6, 1.4) }; }
function layout() {
  var w = G.w, h = G.h, d = G.dpr;
  if (w >= h * 1.15) { L.t = Math.floor(h / N); L.fx = Math.floor((w - L.t * N) / 2); L.fy = Math.floor((h - L.t * N) / 2); L.land = true;
    L.pc = { x: L.fx / 2, y: h / 2 }; L.fc = { x: L.fx + L.t * N + (w - L.fx - L.t * N) / 2, y: h / 2 }; L.r = Math.min(L.fx / 2.1, h * 0.28, 90 * d); L.fr = Math.min((w - L.fx - L.t * N) / 2.3, h * 0.2, 70 * d); }
  else { L.t = Math.floor(w / N); L.fx = Math.floor((w - L.t * N) / 2); L.fy = 40 * d; L.land = false; var by = L.fy + L.t * N, rem = h - by;
    L.r = Math.min(w * 0.2, rem * 0.34, 90 * d); L.fr = Math.min(w * 0.15, rem * 0.26, 70 * d); L.pc = { x: w * 0.27, y: by + rem / 2 }; L.fc = { x: w * 0.75, y: by + rem / 2 }; }
}
function onTouch(pts) {
  layout(); held = -1; fireHeld = false;
  for (var i = 0; i < pts.length; i++) {
    var p = pts[i], dx = p.x - L.pc.x, dy = p.y - L.pc.y;
    if (dx * dx + dy * dy < L.r * L.r * 1.6 && dx * dx + dy * dy > L.r * L.r * 0.04) held = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 1 : 3) : (dy > 0 ? 2 : 0);
    var fx = p.x - L.fc.x, fy = p.y - L.fc.y;
    if (fx * fx + fy * fy < L.fr * L.fr * 1.5) fireHeld = true;
  }
}
function solidAt(c, r) { if (c < 0 || r < 0 || c >= N || r >= N) return true; var v = map[r][c]; return v === 'B' || v === '#' || v === 'E'; }
function blocked(t, x, y) {
  var h = 0.44, c0 = Math.floor(x - h), c1 = Math.floor(x + h), r0 = Math.floor(y - h), r1 = Math.floor(y + h);
  for (var r = r0; r <= r1; r++) for (var c = c0; c <= c1; c++) if (solidAt(c, r)) return true;
  var all = foes.concat([pl]);
  for (var i = 0; i < all.length; i++) { var o = all[i]; if (o === t || o.dead) continue; if (Math.abs(o.x - x) < 0.9 && Math.abs(o.y - y) < 0.9) return true; }
  return false;
}
function move(t, dir, dt, sp) {
  if (dir !== t.dir) { // поворот: подравниваем по перпендикулярной оси к половинке клетки
    t.dir = dir;
    if (dir % 2 === 0) t.x = Math.round(t.x * 2) / 2; else t.y = Math.round(t.y * 2) / 2;
    if (blocked(t, t.x, t.y)) { /* не сдвигаемся при помехе */ }
  }
  var nx = t.x + DX[dir] * sp * dt, ny = t.y + DY[dir] * sp * dt;
  if (!blocked(t, nx, ny)) { t.x = nx; t.y = ny; return true; }
  return false;
}
function shoot(t) {
  if (t.cd > 0) return;
  for (var i = 0; i < bullets.length; i++) if (bullets[i].o === t) return;
  bullets.push({ x: t.x + DX[t.dir] * 0.6, y: t.y + DY[t.dir] * 0.6, dir: t.dir, me: t.me, o: t }); t.cd = 0.25;
}
function boom(x, y) { parts.push({ x: x, y: y, t: 0 }); }
function hitPlayer() {
  if (pl.inv > 0) return;
  lives--; boom(pl.x, pl.y); flash = 0.3;
  if (lives <= 0) { G.lose(); return; }
  pl.x = 4.5; pl.y = 12.5; pl.dir = 0; pl.inv = 2;
}
function update(dt) {
  if (G.done) return;
  pl.cd -= dt; pl.inv -= dt; flash -= dt;
  if (held >= 0) move(pl, held, dt, 3.4);
  if (fireHeld) shoot(pl);
  spawnT -= dt;
  if (spawnT <= 0 && spawned < GOAL && foes.length < 3) {
    for (var k = 0; k < 3; k++) { var sp = SPAWNS[(spawned + k) % 3]; if (!blocked(null, sp[0], sp[1])) { var e = mk(sp[0], sp[1], 2, false); foes.push(e); spawned++; spawnT = 2.5; break; } }
  }
  foes.forEach(function (e) {
    e.cd -= dt; e.ai -= dt; e.fire -= dt;
    var ok = move(e, e.dir, dt, 1.8);
    if (!ok || e.ai <= 0) { e.ai = G.rand(0.8, 2); var r = Math.random(); e.dir = r < 0.5 ? 2 : (r < 0.65 ? 1 : (r < 0.8 ? 3 : G.randInt(4))); }
    if (e.fire <= 0) { shoot(e); e.fire = G.rand(0.9, 1.9); }
  });
  for (var i = bullets.length - 1; i >= 0; i--) {
    var b = bullets[i], st = 0, rm = false;
    // двигаем мелкими шагами, чтобы не пролететь сквозь стену
    for (var s = 0; s < 3 && !rm; s++) {
      b.x += DX[b.dir] * 10 * dt / 3; b.y += DY[b.dir] * 10 * dt / 3;
      var c = Math.floor(b.x), r2 = Math.floor(b.y);
      if (c < 0 || r2 < 0 || c >= N || r2 >= N) { rm = true; break; }
      var v = map[r2][c];
      if (v === 'B') { map[r2][c] = '.'; boom(b.x, b.y); rm = true; }
      else if (v === '#') { rm = true; }
      else if (v === 'E' && b.me) { rm = true; }
      else if (v === 'E') { eagleDead = true; map[r2][c] = '.'; boom(c + 0.5, r2 + 0.5); rm = true; G.lose(); return; }
      if (rm) break;
      if (b.me) {
        for (var j = foes.length - 1; j >= 0; j--) if (Math.abs(foes[j].x - b.x) < 0.5 && Math.abs(foes[j].y - b.y) < 0.5) {
          boom(foes[j].x, foes[j].y); foes.splice(j, 1); kills++; rm = true;
          if (kills >= GOAL) { G.win(); return; } break;
        }
      } else if (Math.abs(pl.x - b.x) < 0.5 && Math.abs(pl.y - b.y) < 0.5) { rm = true; hitPlayer(); if (G.done) return; }
    }
    if (rm) bullets.splice(i, 1);
  }
  // пуля игрока и пуля врага взаимно гасятся
  for (i = bullets.length - 1; i >= 0; i--) for (var q = i - 1; q >= 0; q--) if (bullets[i] && bullets[q] && bullets[i].me !== bullets[q].me && Math.abs(bullets[i].x - bullets[q].x) < 0.3 && Math.abs(bullets[i].y - bullets[q].y) < 0.3) { bullets.splice(i, 1); bullets.splice(q, 1); i--; break; }
  parts = parts.filter(function (p) { p.t += dt; return p.t < 0.35; });
}
function drawTank(c, img, t) {
  var tl = L.t; c.save(); c.translate(L.fx + t.x * tl, L.fy + t.y * tl); c.rotate(t.dir * Math.PI / 2); c.drawImage(img, -tl * 0.5, -tl * 0.5, tl, tl); c.restore();
}
function arrow(c, cx, cy, r, dir, on) {
  c.save(); c.translate(cx, cy); c.rotate(dir * Math.PI / 2); c.fillStyle = on ? '#FF8FC7' : 'rgba(255,255,255,0.75)'; c.strokeStyle = '#4B2E83'; c.lineWidth = 2 * G.dpr;
  c.beginPath(); c.moveTo(0, -r * 0.95); c.lineTo(r * 0.45, -r * 0.45); c.lineTo(-r * 0.45, -r * 0.45); c.closePath(); c.fill(); c.stroke(); c.restore();
}
function draw(c) {
  layout(); var t = L.t, d = G.dpr;
  c.fillStyle = '#2B1A5E'; c.fillRect(0, 0, G.w, G.h);
  c.fillStyle = '#000'; c.fillRect(L.fx, L.fy, t * N, t * N);
  for (var r = 0; r < N; r++) for (var q = 0; q < N; q++) {
    var v = map[r][q], x = L.fx + q * t, y = L.fy + r * t;
    if (v === 'B') {
      c.fillStyle = '#B5502B'; c.fillRect(x, y, t, t); c.fillStyle = '#E58A5B';
      c.fillRect(x + 1, y + 1, t * 0.46, t * 0.2); c.fillRect(x + t * 0.52, y + 1, t * 0.46, t * 0.2);
      c.fillRect(x + 1, y + t * 0.4, t * 0.22, t * 0.2); c.fillRect(x + t * 0.28, y + t * 0.4, t * 0.46, t * 0.2); c.fillRect(x + t * 0.8, y + t * 0.4, t * 0.18, t * 0.2);
      c.fillRect(x + 1, y + t * 0.78, t * 0.46, t * 0.2); c.fillRect(x + t * 0.52, y + t * 0.78, t * 0.46, t * 0.2);
    } else if (v === '#') {
      c.fillStyle = '#9AA3AE'; c.fillRect(x, y, t, t); c.fillStyle = '#E9EEF5'; c.fillRect(x + t * 0.1, y + t * 0.1, t * 0.8, t * 0.8); c.fillStyle = '#6B7480'; c.fillRect(x + t * 0.3, y + t * 0.3, t * 0.4, t * 0.4);
    } else if (v === 'E') c.drawImage(sEagle, x, y, t, t);
  }
  if (eagleDead) { c.fillStyle = '#555'; c.fillRect(L.fx + 6 * t, L.fy + 12 * t, t, t); }
  foes.forEach(function (e) { drawTank(c, sE, e); });
  if (pl.inv <= 0 || Math.floor(pl.inv * 10) % 2) drawTank(c, sP, pl);
  bullets.forEach(function (b) { c.drawImage(sB, L.fx + b.x * t - t * 0.12, L.fy + b.y * t - t * 0.12, t * 0.24, t * 0.24); });
  parts.forEach(function (p) { var k = p.t / 0.35; c.fillStyle = 'rgba(255,200,60,' + (1 - k) + ')'; c.beginPath(); c.arc(L.fx + p.x * t, L.fy + p.y * t, t * (0.2 + k * 0.5), 0, 7); c.fill(); });
  // HUD
  c.fillStyle = '#FFF'; c.font = 'bold ' + Math.round(18 * d) + 'px sans-serif'; c.textBaseline = 'middle';
  var hy = L.land ? 16 * d : L.fy / 2, hx = L.land ? 10 * d : L.fx;
  c.fillText((G.lang === 'ru' || G.lang === 'rk' ? 'Жизни: ' : 'Lives: ') + lives + '    ' + (G.lang === 'ru' || G.lang === 'rk' ? 'Враги: ' : 'Enemies: ') + (GOAL - kills), hx, hy); c.textBaseline = 'alphabetic';
  // крестовина и кнопка огня
  c.fillStyle = 'rgba(255,255,255,0.12)'; c.beginPath(); c.arc(L.pc.x, L.pc.y, L.r, 0, 7); c.fill();
  for (var k = 0; k < 4; k++) arrow(c, L.pc.x + DX[k] * L.r * 0.52, L.pc.y + DY[k] * L.r * 0.52, L.r * 0.45, k, held === k);
  c.fillStyle = fireHeld ? '#FF5C8A' : '#E04E5E'; c.strokeStyle = '#fff'; c.lineWidth = 3 * d; c.beginPath(); c.arc(L.fc.x, L.fc.y, L.fr, 0, 7); c.fill(); c.stroke();
  c.fillStyle = '#fff'; c.font = 'bold ' + Math.round(L.fr * 0.45) + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(G.lang === 'ru' || G.lang === 'rk' ? 'ОГОНЬ' : 'FIRE', L.fc.x, L.fc.y); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
}
