// Тетрис (порт пользовательского DOM-тетриса на canvas). Логика фигур, поворота, очков и скорости — как в оригинале.
// Хост вызывает: init(G), update(dt), draw(ctx), onTouch(points). Спрайты: b0..b6 (по типу фигуры) — заменяемые.
var GOAL_LINES = 5;                    // сколько линий нужно убрать для победы
var BW = 10, BH = 22, SPAWN_X = 4, SPAWN_Y = 1;
var SHAPES = [
  [[-1, 1], [0, 1], [1, 1], [0, 0]],   // T
  [[-1, 0], [0, 0], [1, 0], [2, 0]],   // линия
  [[-1, -1], [-1, 0], [0, 0], [1, 0]], // L
  [[1, -1], [-1, 0], [0, 0], [1, 0]],  // J
  [[0, -1], [1, -1], [-1, 0], [0, 0]], // S
  [[-1, -1], [0, -1], [0, 0], [1, 0]], // Z
  [[0, -1], [1, -1], [0, 0], [1, 0]]   // квадрат
];
var COLORS = ['#B07CF0', '#5CC8FF', '#FF9F43', '#4F7BFF', '#6FD36B', '#FF6B6B', '#FFD93D'];
var board, bag, cur, curIdx, curX, curY, nextIdx, score, level, lines, speed, fallT, held, prevHeld, rep, btn, spr, over;

function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function init() {
  spr = [];
  for (var i = 0; i < 7; i++) (function (i) {
    spr.push(G.sprite('b' + i, 32, function (c, s) {
      c.fillStyle = COLORS[i]; rr(c, 1, 1, s - 2, s - 2, 6);
      c.fillStyle = 'rgba(255,255,255,0.35)'; rr(c, 4, 4, s - 8, (s - 8) / 3, 3);
    }));
  })(i);
  board = []; for (i = 0; i < BW * BH; i++) board.push(0);
  bag = []; score = 0; level = 1; lines = 0; speed = 700; fallT = 0; over = false;
  held = { l: false, r: false, rot: false, down: false }; prevHeld = { rot: false }; rep = { l: 0, r: 0 }; btn = {};
  nextIdx = takeFromBag();
  spawn();
  window.addEventListener('keydown', function (e) {
    var k = e.keyCode; if (k === 37) move(-1, 0); else if (k === 39) move(1, 0); else if (k === 40) stepDown(); else if (k === 38) rotate();
  });
}
function takeFromBag() {
  if (!bag.length) {
    for (var i = 0; i < 7; i++) bag.push(i);
    for (var k = bag.length - 1; k > 0; k--) { var j = Math.floor(Math.random() * (k + 1)); var t = bag[k]; bag[k] = bag[j]; bag[j] = t; }
  }
  return bag.pop();
}
function spawn() {
  curIdx = nextIdx; cur = SHAPES[curIdx].map(function (p) { return [p[0], p[1]]; });
  nextIdx = takeFromBag(); curX = SPAWN_X; curY = SPAWN_Y;
}
function fits(x, y, p) {
  for (var i = 0; i < p.length; i++) {
    var nx = p[i][0] + x, ny = p[i][1] + y;
    if (nx < 0 || nx >= BW || ny < 0 || ny >= BH || board[nx + ny * BW]) return false;
  }
  return true;
}
function move(dx, dy) { if (!over && fits(curX + dx, curY + dy, cur)) { curX += dx; curY += dy; return true; } return false; }
function rotate() {
  if (over || curIdx === 6) return;
  var t = cur.map(function (p) { return [-p[1], p[0]]; });
  if (fits(curX, curY, t)) cur = t;
}
function stepDown() {
  if (over) return;
  if (move(0, 1)) return;
  if (curY === SPAWN_Y) { over = true; G.lose(); return; }   // не упала дальше стартовой строки
  for (var i = 0; i < cur.length; i++) board[(cur[i][0] + curX) + (cur[i][1] + curY) * BW] = curIdx + 1;
  score += 5 * level;
  clearRows();
  if (lines >= GOAL_LINES) { over = true; G.win(); return; }
  if (level < 10 && score >= 1000 * Math.pow(2, level - 1)) { level++; speed = Math.max(120, speed - 75); }
  spawn();
}
function clearRows() {
  var c = 0;
  for (var y = BH - 1; y >= 0; y--) {
    var full = true;
    for (var x = 0; x < BW; x++) if (!board[x + y * BW]) { full = false; break; }
    if (full) {
      for (var yy = y; yy > 0; yy--) for (x = 0; x < BW; x++) board[x + yy * BW] = board[x + (yy - 1) * BW];
      for (x = 0; x < BW; x++) board[x] = 0;
      c++; y++;                          // проверить эту же строку ещё раз
    }
  }
  if (c) { score += c * 40 * level; lines += c; }
}
function onTouch(pts) {
  var h = { l: false, r: false, rot: false, down: false };
  pts.forEach(function (p) {
    for (var k in btn) { var b = btn[k], m = b.s * 0.1; if (p.x >= b.x - m && p.x <= b.x + b.s + m && p.y >= b.y - m && p.y <= b.y + b.s + m) h[k] = true; }
  });
  held = h;
}
function update(dt) {
  if (over) return;
  if (held.rot && !prevHeld.rot) rotate();
  prevHeld.rot = held.rot;
  ['l', 'r'].forEach(function (k) {
    var dx = k === 'l' ? -1 : 1;
    if (held[k]) { if (rep[k] === 0) { move(dx, 0); rep[k] = 0.22; } else { rep[k] -= dt; if (rep[k] <= 0) { move(dx, 0); rep[k] = 0.07; } } } else rep[k] = 0;
  });
  fallT += dt;
  var interval = held.down ? 0.05 : speed / 1000;
  while (fallT >= interval && !over) { fallT -= interval; stepDown(); }
}
function drawBtn(c, key, label, x, y, sz) {
  btn[key] = { x: x, y: y, s: sz };
  c.globalAlpha = held[key] ? 0.8 : 0.45; c.fillStyle = '#4B2E83'; rr(c, x, y, sz, sz, sz * 0.25);
  c.globalAlpha = 1; c.fillStyle = '#fff'; c.font = 'bold ' + Math.round(sz * 0.42) + 'px sans-serif'; c.textAlign = 'center';
  c.fillText(label, x + sz / 2, y + sz * 0.66); c.textAlign = 'left';
}
function draw(c) {
  var u = Math.min(G.w, G.h) * 0.17, m = u * 0.2;
  var cs = Math.max(6, Math.min((G.h - u - m * 3) / BH, (G.w * 0.95) / (BW + 4.5)));
  var ox = (G.w - cs * (BW + 4.5)) / 2, oy = m;
  c.fillStyle = '#FFF0F7'; c.fillRect(ox, oy, cs * BW, cs * BH);
  c.strokeStyle = 'rgba(75,46,131,0.08)'; c.lineWidth = 1;
  for (var x = 1; x < BW; x++) { c.beginPath(); c.moveTo(ox + x * cs, oy); c.lineTo(ox + x * cs, oy + BH * cs); c.stroke(); }
  for (var y = 1; y < BH; y++) { c.beginPath(); c.moveTo(ox, oy + y * cs); c.lineTo(ox + BW * cs, oy + y * cs); c.stroke(); }
  for (y = 0; y < BH; y++) for (x = 0; x < BW; x++) { var v = board[x + y * BW]; if (v) c.drawImage(spr[v - 1], ox + x * cs, oy + y * cs, cs, cs); }
  if (!over) for (var i = 0; i < cur.length; i++) c.drawImage(spr[curIdx], ox + (cur[i][0] + curX) * cs, oy + (cur[i][1] + curY) * cs, cs, cs);
  // панель
  var px = ox + cs * (BW + 0.6);
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(cs * 0.7) + 'px sans-serif';
  c.fillText('NEXT', px, oy + cs * 0.9);
  var ns = SHAPES[nextIdx];
  for (i = 0; i < ns.length; i++) c.drawImage(spr[nextIdx], px + (ns[i][0] + 1) * cs * 0.8, oy + cs * 1.4 + (ns[i][1] + 1) * cs * 0.8, cs * 0.8, cs * 0.8);
  c.fillText('LV ' + level, px, oy + cs * 6.5);
  c.fillText('★ ' + score, px, oy + cs * 7.9);
  c.fillText(lines + '/' + GOAL_LINES, px, oy + cs * 9.3);
  // кнопки: левый большой палец — стрелки, правый — поворот и вниз
  var by = G.h - u - m;
  drawBtn(c, 'l', '◀', m, by, u);
  drawBtn(c, 'r', '▶', m * 2 + u, by, u);
  drawBtn(c, 'down', '▼', G.w - u - m, by, u);
  drawBtn(c, 'rot', '⟳', G.w - u * 2 - m * 2, by, u);
}
