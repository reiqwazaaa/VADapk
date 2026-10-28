// Лабиринт: собери все звёздочки. Хост вызывает: init(G), update(dt), draw(ctx), onSwipe(dir)
var LAYOUT = [
  '###########', '#....#....#', '#.##.#.##.#', '#.........#', '#.##.#.##.#', '#....#....#',
  '#.##.#.##.#', '#.........#', '#.##.#.##.#', '#....#....#', '###########'
];
var N = 11, DX = [0, 1, 0, -1], DY = [-1, 0, 1, 0];
var wall = [], star = [], starsLeft = 0, px = 5, py = 7, dir = -1, want = -1, pAcc = 0, eAcc = 0, enemies = [];
var cell = 1, ox = 0, oy = 0;
var sPlayer, sEnemy, sStar, sWall;

function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function init() {
  sPlayer = G.sprite('player', 64, function (c, s) {
    c.fillStyle = '#FF9ACB'; c.beginPath(); c.arc(s / 2, s * 0.56, s * 0.36, 0, 7); c.fill();
    c.beginPath(); c.moveTo(s * 0.14, s * 0.4); c.lineTo(s * 0.2, s * 0.08); c.lineTo(s * 0.42, s * 0.25); c.fill();
    c.beginPath(); c.moveTo(s * 0.86, s * 0.4); c.lineTo(s * 0.8, s * 0.08); c.lineTo(s * 0.58, s * 0.25); c.fill();
    c.fillStyle = '#3E2670'; c.beginPath(); c.arc(s * 0.38, s * 0.54, s * 0.06, 0, 7); c.arc(s * 0.62, s * 0.54, s * 0.06, 0, 7); c.fill();
  });
  sEnemy = G.sprite('enemy', 64, function (c, s) {
    c.fillStyle = '#5CC8FF'; rr(c, s * 0.12, s * 0.15, s * 0.76, s * 0.72, s * 0.3);
    c.fillStyle = '#fff'; c.beginPath(); c.arc(s * 0.36, s * 0.45, s * 0.1, 0, 7); c.arc(s * 0.64, s * 0.45, s * 0.1, 0, 7); c.fill();
    c.fillStyle = '#2B2B5E'; c.beginPath(); c.arc(s * 0.38, s * 0.47, s * 0.05, 0, 7); c.arc(s * 0.66, s * 0.47, s * 0.05, 0, 7); c.fill();
  });
  sStar = G.sprite('star', 64, function (c, s) {
    c.fillStyle = '#FFC93C'; c.beginPath(); c.moveTo(s / 2, s * 0.1);
    c.quadraticCurveTo(s * 0.55, s * 0.45, s * 0.9, s / 2); c.quadraticCurveTo(s * 0.55, s * 0.55, s / 2, s * 0.9);
    c.quadraticCurveTo(s * 0.45, s * 0.55, s * 0.1, s / 2); c.quadraticCurveTo(s * 0.45, s * 0.45, s / 2, s * 0.1); c.fill();
  });
  sWall = G.sprite('wall', 64, function (c, s) { c.fillStyle = '#B79BF0'; rr(c, 3, 3, s - 6, s - 6, 14); });
  wall = []; star = []; starsLeft = 0;
  for (var r = 0; r < N; r++) {
    wall.push([]); star.push([]);
    for (var k = 0; k < N; k++) {
      wall[r].push(LAYOUT[r][k] === '#');
      star[r].push(LAYOUT[r][k] === '.' && (r + k) % 2 === 0);
    }
  }
  star[py][px] = false;                 // сначала убираем звёздочку под игроком...
  for (r = 0; r < N; r++) for (k = 0; k < N; k++) if (star[r][k]) starsLeft++;   // ...и только потом считаем
  enemies = [{ x: 1, y: 1, last: 1 }, { x: 9, y: 1, last: 3 }];
}
function layout() { cell = Math.min(G.w, G.h) / N; ox = (G.w - cell * N) / 2; oy = (G.h - cell * N) / 2; }
function onSwipe(d) { want = d; }
function free(x, y) { return x >= 0 && y >= 0 && x < N && y < N && !wall[y][x]; }
function update(dt) {
  layout(); pAcc += dt; eAcc += dt;
  while (pAcc >= 0.17 && !G.done) {
    pAcc -= 0.17;
    if (want >= 0 && free(px + DX[want], py + DY[want])) dir = want;
    if (dir >= 0 && free(px + DX[dir], py + DY[dir])) {
      px += DX[dir]; py += DY[dir];
      if (star[py][px]) {
        star[py][px] = false; starsLeft--;
        if (starsLeft <= 0) { G.win(); return; }
      }
      if (enemies.some(function (e) { return e.x === px && e.y === py; })) { G.lose(); return; }
    }
  }
  while (eAcc >= 0.30 && !G.done) {
    eAcc -= 0.30;
    for (var i = 0; i < enemies.length; i++) {
      var e = enemies[i], opts = [];
      for (var d = 0; d < 4; d++) if (free(e.x + DX[d], e.y + DY[d])) opts.push(d);
      var noBack = opts.filter(function (d) { return d !== (e.last + 2) % 4; });
      var pool = noBack.length ? noBack : opts;
      if (!pool.length) continue;
      var pick;
      if (Math.random() < 0.55) {
        pick = pool.slice().sort(function (a, b) {
          return Math.abs(e.x + DX[a] - px) + Math.abs(e.y + DY[a] - py) - (Math.abs(e.x + DX[b] - px) + Math.abs(e.y + DY[b] - py));
        })[0];
      } else pick = pool[G.randInt(pool.length)];
      e.x += DX[pick]; e.y += DY[pick]; e.last = pick;
      if (e.x === px && e.y === py) { G.lose(); return; }
    }
  }
}
function draw(c) {
  layout();
  c.fillStyle = '#F4ECFF'; c.fillRect(ox, oy, cell * N, cell * N);
  for (var r = 0; r < N; r++) for (var k = 0; k < N; k++) {
    var x = ox + k * cell, y = oy + r * cell;
    if (wall[r][k]) c.drawImage(sWall, x, y, cell, cell);
    else if (star[r][k]) c.drawImage(sStar, x + cell * 0.2, y + cell * 0.2, cell * 0.6, cell * 0.6);
  }
  enemies.forEach(function (e) { c.drawImage(sEnemy, ox + e.x * cell + cell * 0.08, oy + e.y * cell + cell * 0.08, cell * 0.84, cell * 0.84); });
  c.drawImage(sPlayer, ox + px * cell + cell * 0.08, oy + py * cell + cell * 0.08, cell * 0.84, cell * 0.84);
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * G.dpr) + 'px sans-serif';
  c.fillText('★ ' + starsLeft, ox + 12, oy + 28 * G.dpr);
}
