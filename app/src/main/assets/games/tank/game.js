// Танчики. Хост вызывает: init(G), update(dt), draw(ctx), onTouch(points)
var GOAL = 10, LIVES = 3;
var tx = 0, ty = 0, targetX = null, bullets = [], foes = [], fireT = 0, spawnT = 0, kills = 0, lives = LIVES, inited = false;
var sTank, sFoe, sBullet;
function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function init() {
  sTank = G.sprite('tank', 64, function (c, s) {
    c.fillStyle = '#FF7FB8'; rr(c, 8, 22, s - 16, s - 26, 12);
    c.fillStyle = '#E04E94'; rr(c, s * 0.38, 4, s * 0.24, s * 0.5, 6);
    c.fillStyle = '#4B2E83'; c.fillRect(8, 30, 8, s - 38); c.fillRect(s - 16, 30, 8, s - 38);
  });
  sFoe = G.sprite('enemy', 64, function (c, s) {
    c.fillStyle = '#6FB7FF'; rr(c, 8, 6, s - 16, s - 26, 12);
    c.fillStyle = '#3B82D9'; rr(c, s * 0.38, s * 0.45, s * 0.24, s * 0.5, 6);
    c.fillStyle = '#fff'; c.beginPath(); c.arc(s * 0.36, s * 0.3, 6, 0, 7); c.arc(s * 0.64, s * 0.3, 6, 0, 7); c.fill();
  });
  sBullet = G.sprite('bullet', 32, function (c, s) { c.fillStyle = '#FFC93C'; c.beginPath(); c.arc(s / 2, s / 2, s * 0.3, 0, 7); c.fill(); });
  kills = 0; lives = LIVES; bullets = []; foes = []; inited = false;
}
function size() { return Math.min(G.w, G.h) / 9; }
function onTouch(p) { targetX = p.length ? p[0].x : null; }
function update(dt) {
  var s = size();
  if (!inited) { tx = G.w / 2; ty = G.h - s * 1.3; inited = true; }
  if (targetX !== null) {
    var d = targetX - tx, m = G.w * 1.4 * dt;
    tx += Math.abs(d) < m ? d : (d > 0 ? m : -m);
  }
  tx = Math.max(s / 2, Math.min(G.w - s / 2, tx));
  fireT -= dt; spawnT -= dt;
  if (fireT <= 0) { fireT = 0.35; bullets.push({ x: tx, y: ty - s / 2 }); }
  if (spawnT <= 0) { spawnT = 1.0; foes.push({ x: G.rand(s, G.w - s), y: -s, vx: G.rand(-0.25, 0.25) * s }); }
  bullets.forEach(function (b) { b.y -= G.h * 0.9 * dt; });
  bullets = bullets.filter(function (b) { return b.y > -s; });
  for (var i = 0; i < foes.length; i++) {
    var f = foes[i]; f.y += G.h * 0.16 * dt; f.x += f.vx * dt * 3;
    if (f.x < s / 2 || f.x > G.w - s / 2) f.vx = -f.vx;
  }
  for (i = foes.length - 1; i >= 0; i--) {
    f = foes[i]; var dead = false;
    for (var j = bullets.length - 1; j >= 0; j--) {
      if (Math.abs(bullets[j].x - f.x) < s * 0.5 && Math.abs(bullets[j].y - f.y) < s * 0.5) { bullets.splice(j, 1); dead = true; break; }
    }
    if (dead) { foes.splice(i, 1); kills++; if (kills >= GOAL) { G.win(); return; } continue; }
    if (Math.abs(f.x - tx) < s * 0.7 && Math.abs(f.y - ty) < s * 0.7) {
      foes.splice(i, 1); lives--; if (lives <= 0) { G.lose(); return; } continue;
    }
    if (f.y > G.h + s) { foes.splice(i, 1); lives--; if (lives <= 0) { G.lose(); return; } }
  }
}
function draw(c) {
  var s = size();
  c.fillStyle = '#FFF4DE'; c.fillRect(0, 0, G.w, G.h);
  foes.forEach(function (f) { c.drawImage(sFoe, f.x - s / 2, f.y - s / 2, s, s); });
  bullets.forEach(function (b) { c.drawImage(sBullet, b.x - s * 0.15, b.y - s * 0.15, s * 0.3, s * 0.3); });
  if (inited) c.drawImage(sTank, tx - s / 2, ty - s / 2, s, s);
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * G.dpr) + 'px sans-serif';
  c.fillText('♥ ' + lives + '   ⚔ ' + kills + '/' + GOAL, 12, 28 * G.dpr);
}
