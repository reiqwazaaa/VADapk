// Змейка. Хост вызывает: init(G), update(dt), draw(ctx), onSwipe(dir 0↑ 1→ 2↓ 3←)
var N = 14, GOAL = 8, STEP = 0.17;
var DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
var cell = 1, ox = 0, oy = 0, body = [], dir = 1, nextDir = 1, acc = 0, food = [0, 0], eaten = 0;
var sHead, sBody, sFood;

function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function heart(c, cx, cy, r) {
  c.beginPath(); c.moveTo(cx, cy + r);
  c.bezierCurveTo(cx - r * 1.6, cy - r * 0.1, cx - r * 0.7, cy - r * 1.1, cx, cy - r * 0.35);
  c.bezierCurveTo(cx + r * 0.7, cy - r * 1.1, cx + r * 1.6, cy - r * 0.1, cx, cy + r);
  c.closePath(); c.fill();
}

function init() {
  sHead = G.sprite('head', 64, function (c, s) {
    c.fillStyle = '#FF4F98'; rr(c, 4, 4, s - 8, s - 8, 22);
    c.fillStyle = '#3E2670'; c.beginPath(); c.arc(s * 0.35, s * 0.45, s * 0.08, 0, 7); c.arc(s * 0.65, s * 0.45, s * 0.08, 0, 7); c.fill();
  });
  sBody = G.sprite('body', 64, function (c, s) { c.fillStyle = '#FF8FC7'; rr(c, 4, 4, s - 8, s - 8, 22); });
  sFood = G.sprite('food', 64, function (c, s) { c.fillStyle = '#FF4F7B'; heart(c, s / 2, s / 2, s * 0.38); });
  body = [[7, 7], [6, 7], [5, 7]]; dir = 1; nextDir = 1; acc = 0; eaten = 0;
  spawn();
}
function layout() {
  cell = Math.min(G.w, G.h) / N; ox = (G.w - cell * N) / 2; oy = (G.h - cell * N) / 2;
}
function spawn() {
  for (;;) {
    var x = G.randInt(N), y = G.randInt(N);
    if (!body.some(function (b) { return b[0] === x && b[1] === y; })) { food = [x, y]; return; }
  }
}
function onSwipe(d) { if (d !== (dir + 2) % 4) nextDir = d; }
function update(dt) {
  layout(); acc += dt;
  while (acc >= STEP && !G.done) { acc -= STEP; step(); }
}
function step() {
  dir = nextDir;
  var h = body[0], nx = h[0] + DX[dir], ny = h[1] + DY[dir];
  if (nx < 0 || ny < 0 || nx >= N || ny >= N) { G.lose(); return; }
  var eating = nx === food[0] && ny === food[1];
  var tail = body[body.length - 1];
  for (var i = 0; i < body.length; i++) {
    var s = body[i];
    if (s[0] === nx && s[1] === ny && !(s === tail && !eating)) { G.lose(); return; }
  }
  body.unshift([nx, ny]);
  if (eating) {
    eaten++;
    if (eaten >= GOAL) { G.win(); return; }
    spawn();
  } else body.pop();
}
function draw(c) {
  layout();
  c.fillStyle = '#FFF0F7'; c.fillRect(ox, oy, cell * N, cell * N);
  c.fillStyle = '#FFE0EF';
  for (var y = 0; y < N; y++) for (var x = 0; x < N; x++) if ((x + y) % 2 === 0) c.fillRect(ox + x * cell, oy + y * cell, cell, cell);
  c.drawImage(sFood, ox + food[0] * cell, oy + food[1] * cell, cell, cell);
  for (var i = body.length - 1; i >= 0; i--) {
    c.drawImage(i === 0 ? sHead : sBody, ox + body[i][0] * cell, oy + body[i][1] * cell, cell, cell);
  }
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * G.dpr) + 'px sans-serif';
  c.fillText('♥ ' + eaten + '/' + GOAL, ox + 12, oy + 28 * G.dpr);
}
