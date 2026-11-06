// Захватчики (клон Space Invaders). Вести корабль пальцем, он стреляет сам.
var COLS = 6, ROWS = 3, LIVES = 3;
var px = 0, targetX = null, py = 0, shots = [], bombs = [], inv = [], dir = 1, ox = 0, oy = 0, speed = 0, fireT = 0, bombT = 1.5, lives = LIVES, inited = false, flash = 0, tm = 0, total = 0, stars = [];
var sShip, sInv, sShot;
function init() {
  sShip = G.sprite('ship', 64, function (c, s) {
    c.fillStyle = '#7CFF9B'; c.fillRect(s * 0.1, s * 0.62, s * 0.8, s * 0.26); c.fillRect(s * 0.28, s * 0.44, s * 0.44, s * 0.2); c.fillRect(s * 0.44, s * 0.2, s * 0.12, s * 0.26);
    c.fillStyle = '#2FBF62'; c.fillRect(s * 0.1, s * 0.8, s * 0.8, s * 0.08);
  });
  sInv = G.sprite('invader', 64, function (c, s) {
    c.fillStyle = '#FF7FB8'; c.fillRect(s * 0.2, s * 0.25, s * 0.6, s * 0.4); c.fillRect(s * 0.1, s * 0.35, s * 0.8, s * 0.3);
    c.fillRect(s * 0.28, s * 0.14, s * 0.1, s * 0.12); c.fillRect(s * 0.62, s * 0.14, s * 0.1, s * 0.12);
    c.fillRect(s * 0.18, s * 0.65, s * 0.12, s * 0.16); c.fillRect(s * 0.7, s * 0.65, s * 0.12, s * 0.16); c.fillRect(s * 0.38, s * 0.65, s * 0.24, s * 0.1);
    c.fillStyle = '#0B1030'; c.fillRect(s * 0.3, s * 0.38, s * 0.12, s * 0.12); c.fillRect(s * 0.58, s * 0.38, s * 0.12, s * 0.12);
  });
  sShot = G.sprite('shot', 32, function (c, s) { c.fillStyle = '#FFE066'; c.fillRect(s * 0.4, 0, s * 0.2, s); });
  for (var i = 0; i < 50; i++) stars.push({ x: Math.random(), y: Math.random() });
  reset();
}
function reset() {
  lives = LIVES; shots = []; bombs = []; inv = []; dir = 1; ox = 0; oy = 0; inited = false; flash = 0; tm = 0;
  for (var r = 0; r < ROWS; r++) for (var c = 0; c < COLS; c++) inv.push({ c: c, r: r, alive: true });
  total = inv.length;
}
function sz() { return Math.min(G.w, G.h) / 9; }
function onTouch(p) { targetX = p.length ? p[0].x : null; }
function hit() { lives--; flash = 0.5; bombs = []; if (lives <= 0) G.lose(); }
function update(dt) {
  var s = sz(), cell = s * 1.15; tm += dt;
  if (!inited) { px = G.w / 2; py = G.h - s * 1.2; ox = (G.w - COLS * cell) / 2; oy = s * 1.6; inited = true; }
  if (flash > 0) flash -= dt;
  if (targetX !== null) { var d = targetX - px, m = G.w * 1.4 * dt; px += Math.abs(d) < m ? d : (d > 0 ? m : -m); }
  px = Math.max(s / 2, Math.min(G.w - s / 2, px));
  var alive = inv.filter(function (a) { return a.alive; });
  if (!alive.length) { G.win(); return; }
  // шаг строя: скорость растёт, когда врагов остаётся меньше
  speed = (s * 0.9) * (1 + (total - alive.length) / total * 1.2);
  var minC = 99, maxC = -1, maxR = -1;
  alive.forEach(function (a) { if (a.c < minC) minC = a.c; if (a.c > maxC) maxC = a.c; if (a.r > maxR) maxR = a.r; });
  ox += dir * speed * dt;
  if (ox + minC * cell < s * 0.3) { ox = s * 0.3 - minC * cell; dir = 1; oy += s * 0.4; }
  if (ox + (maxC + 1) * cell > G.w - s * 0.3) { ox = G.w - s * 0.3 - (maxC + 1) * cell; dir = -1; oy += s * 0.4; }
  if (oy + (maxR + 1) * cell > py - s * 0.6) { G.lose(); return; }
  fireT -= dt; bombT -= dt;
  if (fireT <= 0) { fireT = 0.45; shots.push({ x: px, y: py - s / 2 }); }
  if (bombT <= 0) {
    bombT = G.rand(0.9, 1.6);
    var a = alive[G.randInt(alive.length)]; bombs.push({ x: ox + a.c * cell + cell / 2, y: oy + a.r * cell + cell });
  }
  shots.forEach(function (b) { b.y -= G.h * 0.9 * dt; }); shots = shots.filter(function (b) { return b.y > -s; });
  bombs.forEach(function (b) { b.y += G.h * 0.3 * dt; }); bombs = bombs.filter(function (b) { return b.y < G.h + s; });
  for (var i = shots.length - 1; i >= 0; i--) {
    for (var k = 0; k < alive.length; k++) {
      var e = alive[k], ex = ox + e.c * cell + cell / 2, ey = oy + e.r * cell + cell / 2;
      if (Math.abs(shots[i].x - ex) < cell * 0.42 && Math.abs(shots[i].y - ey) < cell * 0.42) { e.alive = false; shots.splice(i, 1); break; }
    }
  }
  for (i = bombs.length - 1; i >= 0; i--) {
    if (Math.abs(bombs[i].x - px) < s * 0.5 && Math.abs(bombs[i].y - py) < s * 0.5) { bombs.splice(i, 1); hit(); if (lives <= 0) return; }
  }
}
function heart(c, x, y, r, on) {
  c.fillStyle = on ? '#FF5C8A' : 'rgba(255,255,255,0.2)'; c.beginPath();
  c.moveTo(x, y + r * 0.9); c.bezierCurveTo(x - r * 1.6, y - r * 0.2, x - r * 0.7, y - r * 1.3, x, y - r * 0.4);
  c.bezierCurveTo(x + r * 0.7, y - r * 1.3, x + r * 1.6, y - r * 0.2, x, y + r * 0.9); c.fill();
}
function draw(c) {
  var s = sz(), cell = s * 1.15, d = G.dpr;
  c.fillStyle = '#0B0F2A'; c.fillRect(0, 0, G.w, G.h);
  stars.forEach(function (t) { c.fillStyle = 'rgba(255,255,255,0.5)'; c.fillRect(t.x * G.w, t.y * G.h, d, d); });
  if (!inited) return;
  var bob = Math.floor(tm * 2) % 2 ? s * 0.04 : 0;
  inv.forEach(function (a) { if (a.alive) c.drawImage(sInv, ox + a.c * cell + (cell - s) / 2, oy + a.r * cell + (cell - s) / 2 + bob, s, s); });
  shots.forEach(function (b) { c.drawImage(sShot, b.x - s * 0.08, b.y - s * 0.3, s * 0.16, s * 0.6); });
  bombs.forEach(function (b) { c.fillStyle = '#FF5C8A'; c.fillRect(b.x - s * 0.06, b.y - s * 0.2, s * 0.12, s * 0.4); });
  if (flash <= 0 || Math.floor(flash * 20) % 2) c.drawImage(sShip, px - s / 2, py - s / 2, s, s);
  for (var i = 0; i < LIVES; i++) heart(c, 20 * d + i * 26 * d, 24 * d, 9 * d, i < lives);
  var left = inv.filter(function (a) { return a.alive; }).length;
  c.fillStyle = '#EAF0FF'; c.font = 'bold ' + Math.round(20 * d) + 'px sans-serif'; c.textAlign = 'right'; c.fillText(left + ' ×', G.w - 14 * d, 30 * d); c.textAlign = 'left';
}
