// Бесконечная змейка: свайпы меняют направление, еда растит змейку, золотое яблоко даёт 3 очка. Рекорд сохраняется.
var cols, rows, cs, ox, oy, snake, dir, queue, food, gold, goldT, score, hi = 0, stepT, state, deadT, apples, restartRect = null;
var DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
function RU() { return G.lang === 'ru' || G.lang === 'rk'; }
function tx(ru, en) { return RU() ? ru : en; }
function init() { hi = +(G.load('snake_hi') || 0) || 0; reset(); }
function geom() {
  var top = 56 * G.dpr, m = 8 * G.dpr;
  var c = Math.floor(Math.min((G.w - 2 * m) / 14, (G.h - top - m) / 14));
  cs = Math.max(12 * G.dpr, c);
  cols = Math.max(8, Math.min(30, Math.floor((G.w - 2 * m) / cs))); rows = Math.max(8, Math.min(40, Math.floor((G.h - top - m) / cs)));
  ox = Math.floor((G.w - cols * cs) / 2); oy = top;
}
function reset() {
  geom(); var cx = Math.floor(cols / 2), cy = Math.floor(rows / 2);
  snake = [{ x: cx, y: cy }, { x: cx, y: cy + 1 }, { x: cx, y: cy + 2 }]; dir = 0; queue = []; score = 0; apples = 0; stepT = 0; state = 'ready'; gold = null; goldT = 0; deadT = 0;
  placeFood();
}
function free(x, y) { for (var i = 0; i < snake.length; i++) if (snake[i].x === x && snake[i].y === y) return false; return !(food && food.x === x && food.y === y) && !(gold && gold.x === x && gold.y === y); }
function randCell() { for (var k = 0; k < 400; k++) { var x = G.randInt(cols), y = G.randInt(rows); if (free(x, y)) return { x: x, y: y }; } return null; }
function placeFood() { food = null; food = randCell(); }
function onSwipe(d) {
  if (state === 'over') return;
  if (state === 'ready') state = 'play';
  var last = queue.length ? queue[queue.length - 1] : dir;
  if (d === last || (d + 2) % 4 === last) return;
  if (queue.length < 2) queue.push(d);
}
function onTap(x, y) {
  if (state === 'ready') state = 'play';
  if (state === 'over' && deadT > 0.5 && restartRect && x >= restartRect.x && x <= restartRect.x + restartRect.w && y >= restartRect.y && y <= restartRect.y + restartRect.h) reset();
}
function die() { state = 'over'; deadT = 0; if (score > hi) { hi = score; try { G.save('snake_hi', String(hi)); } catch (e) {} } }
function update(dt) {
  if (state === 'over') { deadT += dt; return; }
  if (state !== 'play') return;
  if (gold) { goldT -= dt; if (goldT <= 0) gold = null; }
  stepT += dt;
  var iv = Math.max(0.065, 0.17 - apples * 0.0025);
  while (stepT >= iv && state === 'play') {
    stepT -= iv;
    if (queue.length) dir = queue.shift();
    var h = snake[0], nx = h.x + DX[dir], ny = h.y + DY[dir];
    if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) { die(); return; }
    var grow = false;
    for (var i = 0; i < snake.length - 1; i++) if (snake[i].x === nx && snake[i].y === ny) { die(); return; }
    snake.unshift({ x: nx, y: ny });
    if (food && nx === food.x && ny === food.y) {
      grow = true; score++; apples++; placeFood();
      if (!gold && apples % 5 === 0) { gold = randCell(); goldT = 6; }
    } else if (gold && nx === gold.x && ny === gold.y) { grow = true; score += 3; gold = null; }
    if (!grow) snake.pop();
    if (score > hi) hi = score;
  }
}
function circ(c, x, y, r, col) { c.fillStyle = col; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); }
function draw(c) {
  geom(); var d = G.dpr, i;
  c.fillStyle = '#FFE6F3'; c.fillRect(0, 0, G.w, G.h);
  c.fillStyle = '#FFF8FC'; c.fillRect(ox, oy, cols * cs, rows * cs);
  c.fillStyle = 'rgba(255,143,199,0.10)';
  for (var y = 0; y < rows; y++) for (var x = 0; x < cols; x++) if ((x + y) & 1) c.fillRect(ox + x * cs, oy + y * cs, cs, cs);
  c.strokeStyle = '#4B2E83'; c.lineWidth = 3 * d; c.strokeRect(ox, oy, cols * cs, rows * cs);
  if (food) { var fx = ox + (food.x + 0.5) * cs, fy = oy + (food.y + 0.5) * cs; circ(c, fx, fy, cs * 0.38, '#FF4D6D'); circ(c, fx - cs * 0.1, fy - cs * 0.12, cs * 0.1, 'rgba(255,255,255,0.7)'); c.fillStyle = '#4CAF50'; c.fillRect(fx - 1, fy - cs * 0.55, 3 * d, cs * 0.22); }
  if (gold) { var gx = ox + (gold.x + 0.5) * cs, gy = oy + (gold.y + 0.5) * cs, pul = 1 + 0.12 * Math.sin(Date.now() / 120); circ(c, gx, gy, cs * 0.42 * pul, '#FFC93C'); circ(c, gx - cs * 0.1, gy - cs * 0.12, cs * 0.12, '#FFF3B0'); }
  for (i = snake.length - 1; i >= 0; i--) {
    var s = snake[i], px = ox + s.x * cs, py = oy + s.y * cs, k = i / snake.length;
    c.fillStyle = i === 0 ? '#E0449A' : 'rgb(' + Math.round(255 - 40 * k) + ',' + Math.round(130 + 40 * k) + ',' + Math.round(190 + 20 * k) + ')';
    c.beginPath(); c.moveTo(px + cs * 0.3, py + cs * 0.06); c.arcTo(px + cs * 0.94, py + cs * 0.06, px + cs * 0.94, py + cs * 0.94, cs * 0.3); c.arcTo(px + cs * 0.94, py + cs * 0.94, px + cs * 0.06, py + cs * 0.94, cs * 0.3); c.arcTo(px + cs * 0.06, py + cs * 0.94, px + cs * 0.06, py + cs * 0.06, cs * 0.3); c.arcTo(px + cs * 0.06, py + cs * 0.06, px + cs * 0.94, py + cs * 0.06, cs * 0.3); c.closePath(); c.fill();
    if (i === 0) { // глаза смотрят по ходу движения
      var ex = DX[dir], ey = DY[dir], sx = -ey, sy = ex, cx0 = px + cs / 2 + ex * cs * 0.12, cy0 = py + cs / 2 + ey * cs * 0.12;
      for (var sg = -1; sg <= 1; sg += 2) { circ(c, cx0 + sx * sg * cs * 0.2, cy0 + sy * sg * cs * 0.2, cs * 0.13, '#fff'); circ(c, cx0 + sx * sg * cs * 0.2 + ex * cs * 0.04, cy0 + sy * sg * cs * 0.2 + ey * cs * 0.04, cs * 0.06, '#222'); }
    }
  }
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * d) + 'px sans-serif'; c.textBaseline = 'middle';
  c.fillText(tx('Очки: ', 'Score: ') + score, 14 * d, 28 * d); c.textAlign = 'right'; c.fillText(tx('Рекорд: ', 'Best: ') + hi, G.w - 14 * d, 28 * d); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
  restartRect = null;
  var cx = G.w / 2, cy = oy + rows * cs / 2;
  if (state === 'ready') { c.textAlign = 'center'; c.fillStyle = 'rgba(75,46,131,0.85)'; c.font = 'bold ' + Math.round(22 * d) + 'px sans-serif'; c.fillText(tx('Проведи пальцем, чтобы начать', 'Swipe to start'), cx, cy + cs * 3); c.textAlign = 'left'; }
  if (state === 'over') {
    c.fillStyle = 'rgba(255,255,255,0.85)'; c.fillRect(ox, oy, cols * cs, rows * cs);
    c.textAlign = 'center'; c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(30 * d) + 'px sans-serif'; c.fillText(tx('Игра окончена', 'Game over'), cx, cy - 50 * d);
    c.font = 'bold ' + Math.round(20 * d) + 'px sans-serif'; c.fillText(tx('Очки: ', 'Score: ') + score + '   ' + tx('Рекорд: ', 'Best: ') + hi, cx, cy - 10 * d);
    restartRect = { x: cx - 100 * d, y: cy + 20 * d, w: 200 * d, h: 54 * d };
    c.fillStyle = '#FF8FC7'; c.strokeStyle = '#fff'; c.lineWidth = 3 * d; var r = restartRect;
    c.beginPath(); c.moveTo(r.x + r.h / 2, r.y); c.arcTo(r.x + r.w, r.y, r.x + r.w, r.y + r.h, r.h / 2); c.arcTo(r.x + r.w, r.y + r.h, r.x, r.y + r.h, r.h / 2); c.arcTo(r.x, r.y + r.h, r.x, r.y, r.h / 2); c.arcTo(r.x, r.y, r.x + r.w, r.y, r.h / 2); c.closePath(); c.fill(); c.stroke();
    c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * d) + 'px sans-serif'; c.fillText(tx('Играть снова', 'Play again'), cx, r.y + r.h * 0.64); c.textAlign = 'left';
  }
}
