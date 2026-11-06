// Арканоид: води платформу пальцем, разбей все кирпичи. 3 жизни.
var COLS = 7, ROWS = 3, LIVES = 3;
var bricks = [], px = 0, targetX = null, bx = 0, by = 0, vx = 0, vy = 0, stuck = true, stuckT = 0.8, lives = LIVES, spd = 0, inited = false, parts = [];
var COLORS = ['#FF7FB8', '#FFB347', '#7CFF9B', '#6FB7FF'];
function init() { reset(); }
function reset() {
  bricks = []; for (var r = 0; r < ROWS; r++) for (var c = 0; c < COLS; c++) bricks.push({ c: c, r: r, hp: r === 0 ? 2 : 1 });
  lives = LIVES; inited = false; stuck = true; stuckT = 0.8; parts = [];
}
function L() { // раскладка: поле на весь экран с отступами
  var w = G.w, h = G.h, d = G.dpr, top = 46 * d, m = 10 * d;
  var bw = (w - 2 * m) / COLS, bh = Math.min(bw * 0.45, h * 0.05);
  return { w: w, h: h, d: d, top: top, m: m, bw: bw, bh: bh, pw: Math.max(w * 0.24, 70 * d), ph: 14 * d, py: h - 54 * d, br: Math.max(7 * d, Math.min(w, h) * 0.018) };
}
function onTouch(p) { targetX = p.length ? p[0].x : null; }
function launch(l) { var a = (G.rand(-0.35, 0.35)); vx = Math.sin(a) * spd; vy = -Math.cos(a) * spd; stuck = false; }
function update(dt) {
  var l = L(); spd = Math.min(l.w, l.h) * (1.1 + (21 - bricks.length) * 0.012);
  if (!inited) { px = l.w / 2; inited = true; }
  if (targetX !== null) px += (targetX - px) * Math.min(1, dt * 22);
  px = Math.max(l.pw / 2, Math.min(l.w - l.pw / 2, px));
  parts = parts.filter(function (p) { p.t += dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy += l.h * dt; return p.t < 0.5; });
  if (stuck) {
    bx = px; by = l.py - l.br - l.ph / 2;
    stuckT -= dt; if (stuckT <= 0) launch(l);
    return;
  }
  var steps = 3, sdt = dt / steps;
  for (var s = 0; s < steps; s++) {
    bx += vx * sdt; by += vy * sdt;
    if (bx < l.br) { bx = l.br; vx = Math.abs(vx); }
    if (bx > l.w - l.br) { bx = l.w - l.br; vx = -Math.abs(vx); }
    if (by < l.br + 4 * l.d) { by = l.br + 4 * l.d; vy = Math.abs(vy); }
    // платформа
    if (vy > 0 && by + l.br >= l.py - l.ph / 2 && by - l.br <= l.py + l.ph / 2 && Math.abs(bx - px) <= l.pw / 2 + l.br) {
      var k = Math.max(-1, Math.min(1, (bx - px) / (l.pw / 2))), a = k * 1.05;
      vx = Math.sin(a) * spd; vy = -Math.cos(a) * spd; by = l.py - l.ph / 2 - l.br;
    }
    // кирпичи
    for (var i = 0; i < bricks.length; i++) {
      var b = bricks[i], x0 = l.m + b.c * l.bw, y0 = l.top + b.r * (l.bh + 3 * l.d);
      var nx = Math.max(x0, Math.min(bx, x0 + l.bw - 3 * l.d)), ny = Math.max(y0, Math.min(by, y0 + l.bh));
      var dx = bx - nx, dy = by - ny;
      if (dx * dx + dy * dy < l.br * l.br) {
        if (Math.abs(dx) > Math.abs(dy)) vx = dx > 0 ? Math.abs(vx) : -Math.abs(vx); else vy = dy > 0 ? Math.abs(vy) : -Math.abs(vy);
        b.hp--;
        for (var q = 0; q < 4; q++) parts.push({ x: x0 + l.bw / 2, y: y0 + l.bh / 2, vx: G.rand(-1, 1) * l.w * 0.2, vy: G.rand(-1, 0) * l.h * 0.2, t: 0, col: COLORS[b.r] });
        if (b.hp <= 0) bricks.splice(i, 1);
        break;
      }
    }
    if (!bricks.length) { G.win(); return; }
    if (by > l.h + l.br) {
      lives--; if (lives <= 0) { G.lose(); return; }
      stuck = true; stuckT = 0.9; return;
    }
  }
}
function heart(c, x, y, r, on) {
  c.fillStyle = on ? '#FF4D8D' : '#E6CFE0'; c.beginPath();
  c.moveTo(x, y + r * 0.9); c.bezierCurveTo(x - r * 1.6, y - r * 0.2, x - r * 0.7, y - r * 1.3, x, y - r * 0.4);
  c.bezierCurveTo(x + r * 0.7, y - r * 1.3, x + r * 1.6, y - r * 0.2, x, y + r * 0.9); c.fill();
}
function draw(c) {
  var l = L(), d = l.d;
  c.fillStyle = '#FFF4DE'; c.fillRect(0, 0, l.w, l.h);
  bricks.forEach(function (b) {
    var x0 = l.m + b.c * l.bw, y0 = l.top + b.r * (l.bh + 3 * d);
    c.fillStyle = COLORS[b.r]; c.beginPath(); c.rect(x0, y0, l.bw - 3 * d, l.bh); c.fill();
    c.strokeStyle = '#4B2E83'; c.lineWidth = 2 * d; c.strokeRect(x0, y0, l.bw - 3 * d, l.bh);
    if (b.hp > 1) { c.fillStyle = 'rgba(75,46,131,0.35)'; c.fillRect(x0 + 3 * d, y0 + 3 * d, l.bw - 9 * d, l.bh * 0.3); }
  });
  parts.forEach(function (p) { c.fillStyle = p.col; c.globalAlpha = 1 - p.t / 0.5; c.fillRect(p.x, p.y, 4 * d, 4 * d); c.globalAlpha = 1; });
  c.fillStyle = '#FF8FC7'; c.strokeStyle = '#4B2E83'; c.lineWidth = 2.5 * d;
  c.beginPath(); c.rect(px - l.pw / 2, l.py - l.ph / 2, l.pw, l.ph); c.fill(); c.stroke();
  c.fillStyle = '#4B2E83'; c.beginPath(); c.arc(bx, by, l.br, 0, 7); c.fill();
  c.fillStyle = '#FFF'; c.beginPath(); c.arc(bx - l.br * 0.3, by - l.br * 0.3, l.br * 0.3, 0, 7); c.fill();
  for (var i = 0; i < LIVES; i++) heart(c, 20 * d + i * 26 * d, 24 * d, 9 * d, i < lives);
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(18 * d) + 'px sans-serif'; c.textAlign = 'right'; c.fillText(String(bricks.length), l.w - 14 * d, 30 * d); c.textAlign = 'left';
}
