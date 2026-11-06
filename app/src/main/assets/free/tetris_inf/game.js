// Бесконечный тетрис: 10x20, уровни, следующая фигура, рекорд. Управление кнопками на экране.
var BW = 10, BH = 20;
var SHAPES = [
  [[-1, 1], [0, 1], [1, 1], [0, 0]], [[-1, 0], [0, 0], [1, 0], [2, 0]], [[-1, -1], [-1, 0], [0, 0], [1, 0]],
  [[1, -1], [-1, 0], [0, 0], [1, 0]], [[0, -1], [1, -1], [-1, 0], [0, 0]], [[-1, -1], [0, -1], [0, 0], [1, 0]], [[0, -1], [1, -1], [0, 0], [1, 0]]
];
var COLORS = ['#B07CF0', '#5CC8FF', '#FF9F43', '#4F7BFF', '#6FD36B', '#FF6B6B', '#FFD93D'];
var board, bag, cur, curIdx, curX, curY, nextIdx, score, level, lines, fallT, held, prevHeld, rep, btn = {}, spr, over, hi = 0, L = {}, restartRect = null, flashRows = [], flashT = 0;
function RU() { return G.lang === 'ru' || G.lang === 'rk'; }
function tx(ru, en) { return RU() ? ru : en; }
function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r); c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function init() {
  spr = [];
  for (var i = 0; i < 7; i++) (function (i) {
    spr.push(G.sprite('b' + i, 32, function (c, s) { c.fillStyle = COLORS[i]; rr(c, 1, 1, s - 2, s - 2, 6); c.fillStyle = 'rgba(255,255,255,0.35)'; rr(c, 4, 4, s - 8, (s - 8) / 3, 3); }));
  })(i);
  hi = +(G.load('tetris_hi') || 0) || 0;
  restart();
}
function restart() {
  board = []; for (var i = 0; i < BW * BH; i++) board.push(0);
  bag = []; score = 0; level = 1; lines = 0; fallT = 0; over = false; flashRows = [];
  held = { l: false, r: false, rot: false, down: false }; prevHeld = { rot: false }; rep = { l: 0, r: 0 };
  nextIdx = takeFromBag(); spawn();
}
function takeFromBag() {
  if (!bag.length) { for (var i = 0; i < 7; i++) bag.push(i); for (var k = 6; k > 0; k--) { var j = Math.floor(Math.random() * (k + 1)), t = bag[k]; bag[k] = bag[j]; bag[j] = t; } }
  return bag.pop();
}
function spawn() {
  curIdx = nextIdx; cur = SHAPES[curIdx].map(function (p) { return [p[0], p[1]]; }); nextIdx = takeFromBag(); curX = 4; curY = 1;
  if (!fits(curX, curY, cur)) endGame();
}
function endGame() { over = true; if (score > hi) { hi = score; try { G.save('tetris_hi', String(hi)); } catch (e) {} } }
function fits(x, y, p) {
  for (var i = 0; i < p.length; i++) { var nx = p[i][0] + x, ny = p[i][1] + y; if (nx < 0 || nx >= BW || ny < 0 || ny >= BH || board[nx + ny * BW]) return false; }
  return true;
}
function move(dx, dy) { if (!over && fits(curX + dx, curY + dy, cur)) { curX += dx; curY += dy; return true; } return false; }
function rotate() {
  if (over || curIdx === 6) return;
  var t = cur.map(function (p) { return [-p[1], p[0]]; });
  var kicks = [0, -1, 1, -2, 2];
  for (var i = 0; i < kicks.length; i++) if (fits(curX + kicks[i], curY, t)) { cur = t; curX += kicks[i]; return; }
}
function stepDown(soft) {
  if (over) return;
  if (move(0, 1)) { if (soft) score++; return; }
  for (var i = 0; i < cur.length; i++) board[(cur[i][0] + curX) + (cur[i][1] + curY) * BW] = curIdx + 1;
  clearRows(); if (!over) spawn();
}
function clearRows() {
  var c = 0;
  for (var y = BH - 1; y >= 0; y--) {
    var full = true; for (var x = 0; x < BW; x++) if (!board[x + y * BW]) { full = false; break; }
    if (full) { for (var yy = y; yy > 0; yy--) for (x = 0; x < BW; x++) board[x + yy * BW] = board[x + (yy - 1) * BW]; for (x = 0; x < BW; x++) board[x] = 0; c++; y++; }
  }
  if (c) { score += [0, 100, 300, 500, 800][c] * level; lines += c; level = 1 + Math.floor(lines / 10); }
  if (score > hi) hi = score;
}
function onTouch(pts) {
  var h = { l: false, r: false, rot: false, down: false };
  pts.forEach(function (p) { for (var k in btn) { var b = btn[k], m = b.s * 0.1; if (p.x >= b.x - m && p.x <= b.x + b.s + m && p.y >= b.y - m && p.y <= b.y + b.s + m) h[k] = true; } });
  held = h;
}
function onTap(x, y) {
  if (over && restartRect && x >= restartRect.x && x <= restartRect.x + restartRect.w && y >= restartRect.y && y <= restartRect.y + restartRect.h) restart();
}
function update(dt) {
  if (over) return;
  if (held.rot && !prevHeld.rot) rotate(); prevHeld.rot = held.rot;
  ['l', 'r'].forEach(function (k) {
    var dx = k === 'l' ? -1 : 1;
    if (held[k]) { if (rep[k] === 0) { move(dx, 0); rep[k] = 0.22; } else { rep[k] -= dt; if (rep[k] <= 0) { move(dx, 0); rep[k] = 0.07; } } } else rep[k] = 0;
  });
  fallT += dt;
  var interval = held.down ? 0.04 : Math.max(0.08, 0.75 - (level - 1) * 0.06);
  while (fallT >= interval && !over) { fallT -= interval; stepDown(held.down); }
}
function icon(c, kind, cx, cy, r) { // стрелки и поворот рисуются контурами
  c.fillStyle = '#fff'; c.strokeStyle = '#fff'; c.lineWidth = r * 0.25; c.beginPath();
  if (kind === 'l') { c.moveTo(cx - r, cy); c.lineTo(cx + r * 0.6, cy - r); c.lineTo(cx + r * 0.6, cy + r); c.fill(); }
  else if (kind === 'r') { c.moveTo(cx + r, cy); c.lineTo(cx - r * 0.6, cy - r); c.lineTo(cx - r * 0.6, cy + r); c.fill(); }
  else if (kind === 'down') { c.moveTo(cx, cy + r); c.lineTo(cx - r, cy - r * 0.6); c.lineTo(cx + r, cy - r * 0.6); c.fill(); }
  else { c.arc(cx, cy, r * 0.8, -2.6, 1.4); c.stroke(); c.beginPath(); c.moveTo(cx + r * 0.9, cy + r * 0.5); c.lineTo(cx - r * 0.1, cy + r * 0.9); c.lineTo(cx + r * 0.1, cy - r * 0.1); c.fill(); }
}
function drawBtn(c, key, x, y, sz) {
  btn[key] = { x: x, y: y, s: sz };
  c.globalAlpha = held[key] ? 0.85 : 0.5; c.fillStyle = '#4B2E83'; rr(c, x, y, sz, sz, sz * 0.25); c.globalAlpha = 1;
  icon(c, key, x + sz / 2, y + sz / 2, sz * 0.27);
}
function draw(c) {
  var w = G.w, h = G.h, d = G.dpr, land = w >= h * 1.15, u, cs, ox, oy;
  c.fillStyle = '#FFE6F3'; c.fillRect(0, 0, w, h);
  if (land) { u = Math.min(h * 0.28, w * 0.11); cs = Math.floor((h - 16 * d) / BH); ox = Math.floor((w - cs * BW) / 2); oy = Math.floor((h - cs * BH) / 2); }
  else { u = Math.min(w * 0.2, h * 0.1); cs = Math.floor(Math.min((h - u - 40 * d) / BH, (w - 20 * d) / (BW + 4))); ox = Math.floor((w - cs * (BW + 4)) / 2); oy = 10 * d; }
  c.fillStyle = '#FFF8FC'; c.fillRect(ox, oy, cs * BW, cs * BH);
  c.strokeStyle = 'rgba(75,46,131,0.08)'; c.lineWidth = 1;
  for (var x = 1; x < BW; x++) { c.beginPath(); c.moveTo(ox + x * cs, oy); c.lineTo(ox + x * cs, oy + BH * cs); c.stroke(); }
  for (var y = 1; y < BH; y++) { c.beginPath(); c.moveTo(ox, oy + y * cs); c.lineTo(ox + BW * cs, oy + y * cs); c.stroke(); }
  c.strokeStyle = '#4B2E83'; c.lineWidth = 3 * d; c.strokeRect(ox, oy, cs * BW, cs * BH);
  for (y = 0; y < BH; y++) for (x = 0; x < BW; x++) { var v = board[x + y * BW]; if (v) c.drawImage(spr[v - 1], ox + x * cs, oy + y * cs, cs, cs); }
  var i;
  if (!over) {
    var gy = curY; while (fits(curX, gy + 1, cur)) gy++;
    c.globalAlpha = 0.25; for (i = 0; i < cur.length; i++) c.drawImage(spr[curIdx], ox + (cur[i][0] + curX) * cs, oy + (cur[i][1] + gy) * cs, cs, cs); c.globalAlpha = 1;
    for (i = 0; i < cur.length; i++) c.drawImage(spr[curIdx], ox + (cur[i][0] + curX) * cs, oy + (cur[i][1] + curY) * cs, cs, cs);
  }
  // панель: следующая фигура, очки, уровень, рекорд
  var px = land ? ox + cs * BW + 14 * d : ox + cs * BW + cs * 0.5, py = oy;
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(Math.max(12 * d, cs * 0.6)) + 'px sans-serif'; var fh = Math.max(14 * d, cs * 0.75);
  c.fillText(tx('Далее', 'Next'), px, py + fh);
  var ns = SHAPES[nextIdx];
  for (i = 0; i < ns.length; i++) c.drawImage(spr[nextIdx], px + (ns[i][0] + 1) * cs * 0.75, py + fh * 1.4 + (ns[i][1] + 1) * cs * 0.75, cs * 0.75, cs * 0.75);
  var ty = py + fh * 1.4 + cs * 3.2;
  c.fillText(tx('Уровень ', 'Level ') + level, px, ty); c.fillText(tx('Очки ', 'Score ') + score, px, ty + fh * 1.3);
  c.fillText(tx('Линии ', 'Lines ') + lines, px, ty + fh * 2.6); c.fillText(tx('Рекорд ', 'Best ') + hi, px, ty + fh * 3.9);
  // кнопки
  btn = {};
  if (land) {
    var m = 14 * d, mid = (h - u) / 2;
    drawBtn(c, 'l', m, mid, u); drawBtn(c, 'r', m + u * 1.15, mid, u);
    drawBtn(c, 'rot', w - u * 2.15 - m, mid, u); drawBtn(c, 'down', w - u - m, mid, u);
  } else {
    var by = h - u - 12 * d;
    drawBtn(c, 'l', 12 * d, by, u); drawBtn(c, 'r', 12 * d + u * 1.15, by, u);
    drawBtn(c, 'rot', w - u * 2.15 - 12 * d, by, u); drawBtn(c, 'down', w - u - 12 * d, by, u);
  }
  restartRect = null;
  if (over) {
    c.fillStyle = 'rgba(255,255,255,0.88)'; c.fillRect(ox, oy, cs * BW, cs * BH);
    c.fillStyle = '#4B2E83'; c.textAlign = 'center'; var cx = ox + cs * BW / 2, cy = oy + cs * BH / 2;
    c.font = 'bold ' + Math.round(cs * 1.1) + 'px sans-serif'; c.fillText(tx('Игра окончена', 'Game over'), cx, cy - cs * 2);
    c.font = 'bold ' + Math.round(cs * 0.8) + 'px sans-serif'; c.fillText(tx('Очки: ', 'Score: ') + score, cx, cy - cs * 0.6); c.fillText(tx('Рекорд: ', 'Best: ') + hi, cx, cy + cs * 0.5);
    restartRect = { x: cx - cs * 3.5, y: cy + cs * 1.5, w: cs * 7, h: cs * 1.8 };
    c.fillStyle = '#FF8FC7'; c.strokeStyle = '#fff'; c.lineWidth = 3 * d; c.beginPath(); c.moveTo(restartRect.x + cs * 0.9, restartRect.y); c.arcTo(restartRect.x + restartRect.w, restartRect.y, restartRect.x + restartRect.w, restartRect.y + restartRect.h, cs * 0.9); c.arcTo(restartRect.x + restartRect.w, restartRect.y + restartRect.h, restartRect.x, restartRect.y + restartRect.h, cs * 0.9); c.arcTo(restartRect.x, restartRect.y + restartRect.h, restartRect.x, restartRect.y, cs * 0.9); c.arcTo(restartRect.x, restartRect.y, restartRect.x + restartRect.w, restartRect.y, cs * 0.9); c.closePath(); c.fill(); c.stroke();
    c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(cs * 0.75) + 'px sans-serif'; c.fillText(tx('Играть снова', 'Play again'), cx, restartRect.y + restartRect.h * 0.66); c.textAlign = 'left';
  }
}
