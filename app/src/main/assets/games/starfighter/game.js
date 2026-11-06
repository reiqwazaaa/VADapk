// Звёздный истребитель. Хост вызывает: init(G), update(dt), draw(ctx), onTouch(points)
var GOAL = 10, LIVES = 3;
var tx = 0, ty = 0, targetX = null, bullets = [], foes = [], fireT = 0, spawnT = 0, kills = 0, lives = LIVES, inited = false, stars = [], flash = 0, booms = [], tm = 0;
var sShip, sFoe, sLaser;
function init() {
  // корабль: белый корпус, голубая кабина, пламя двигателя рисуется отдельно
  sShip = G.sprite('ship', 64, function (c, s) {
    c.fillStyle = '#9FB4FF'; c.beginPath(); c.moveTo(s * 0.5, 2); c.lineTo(s * 0.92, s * 0.88); c.lineTo(s * 0.5, s * 0.74); c.lineTo(s * 0.08, s * 0.88); c.closePath(); c.fill();
    c.fillStyle = '#EAF0FF'; c.beginPath(); c.moveTo(s * 0.5, 2); c.lineTo(s * 0.62, s * 0.78); c.lineTo(s * 0.5, s * 0.74); c.lineTo(s * 0.38, s * 0.78); c.closePath(); c.fill();
    c.fillStyle = '#3AD6FF'; c.beginPath(); c.ellipse(s * 0.5, s * 0.4, s * 0.09, s * 0.16, 0, 0, 7); c.fill();
    c.fillStyle = '#FF5C8A'; c.fillRect(s * 0.12, s * 0.78, s * 0.1, s * 0.1); c.fillRect(s * 0.78, s * 0.78, s * 0.1, s * 0.1);
  });
  sFoe = G.sprite('alien', 64, function (c, s) {
    c.fillStyle = '#7CFF9B'; c.beginPath(); c.ellipse(s * 0.5, s * 0.42, s * 0.34, s * 0.3, 0, 0, 7); c.fill();
    c.fillStyle = '#2FBF62'; c.beginPath(); c.ellipse(s * 0.5, s * 0.62, s * 0.46, s * 0.14, 0, 0, 7); c.fill();
    c.fillStyle = '#0B1030'; c.beginPath(); c.arc(s * 0.38, s * 0.4, s * 0.07, 0, 7); c.arc(s * 0.62, s * 0.4, s * 0.07, 0, 7); c.fill();
    c.fillStyle = '#FFE066'; c.beginPath(); c.arc(s * 0.22, s * 0.64, 3, 0, 7); c.arc(s * 0.5, s * 0.7, 3, 0, 7); c.arc(s * 0.78, s * 0.64, 3, 0, 7); c.fill();
    c.strokeStyle = '#7CFF9B'; c.lineWidth = 2; c.beginPath(); c.moveTo(s * 0.4, s * 0.16); c.lineTo(s * 0.34, s * 0.04); c.moveTo(s * 0.6, s * 0.16); c.lineTo(s * 0.66, s * 0.04); c.stroke();
  });
  sLaser = G.sprite('laser', 32, function (c, s) {
    c.fillStyle = 'rgba(255,90,140,0.35)'; c.fillRect(s * 0.3, 0, s * 0.4, s);
    c.fillStyle = '#FF5C8A'; c.fillRect(s * 0.42, 0, s * 0.16, s); c.fillStyle = '#fff'; c.fillRect(s * 0.47, 2, s * 0.06, s - 4);
  });
  kills = 0; lives = LIVES; bullets = []; foes = []; booms = []; inited = false; flash = 0; tm = 0;
  for (var i = 0; i < 60; i++) stars.push({ x: Math.random(), y: Math.random(), z: 0.3 + Math.random() * 0.7 });
}
function size() { return Math.min(G.w, G.h) / 9; }
function onTouch(p) { targetX = p.length ? p[0].x : null; }
function hurt() { lives--; flash = 0.4; if (lives <= 0) G.lose(); }
function update(dt) {
  var s = size(); tm += dt;
  if (!inited) { tx = G.w / 2; ty = G.h - s * 1.3; inited = true; }
  stars.forEach(function (t) { t.y += dt * 0.12 * t.z; if (t.y > 1) { t.y = 0; t.x = Math.random(); } });
  if (flash > 0) flash -= dt;
  booms = booms.filter(function (b) { b.t += dt; return b.t < 0.4; });
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
    if (dead) { booms.push({ x: f.x, y: f.y, t: 0 }); foes.splice(i, 1); kills++; if (kills >= GOAL) { G.win(); return; } continue; }
    if (Math.abs(f.x - tx) < s * 0.7 && Math.abs(f.y - ty) < s * 0.7) { booms.push({ x: f.x, y: f.y, t: 0 }); foes.splice(i, 1); hurt(); if (lives <= 0) return; continue; }
    if (f.y > G.h + s) { foes.splice(i, 1); hurt(); if (lives <= 0) return; }
  }
}
function heart(c, x, y, r, on) {
  c.fillStyle = on ? '#FF5C8A' : 'rgba(255,255,255,0.2)'; c.beginPath();
  c.moveTo(x, y + r * 0.9); c.bezierCurveTo(x - r * 1.6, y - r * 0.2, x - r * 0.7, y - r * 1.3, x, y - r * 0.4);
  c.bezierCurveTo(x + r * 0.7, y - r * 1.3, x + r * 1.6, y - r * 0.2, x, y + r * 0.9); c.fill();
}
function draw(c) {
  var s = size(), d = G.dpr;
  var g = c.createLinearGradient(0, 0, 0, G.h); g.addColorStop(0, '#0B1030'); g.addColorStop(1, '#2B1A5E'); c.fillStyle = g; c.fillRect(0, 0, G.w, G.h);
  stars.forEach(function (t) { c.fillStyle = 'rgba(255,255,255,' + (0.3 + t.z * 0.6) + ')'; c.fillRect(t.x * G.w, t.y * G.h, d * (1 + t.z * 1.5), d * (1 + t.z * 1.5)); });
  foes.forEach(function (f) { c.drawImage(sFoe, f.x - s / 2, f.y - s / 2, s, s); });
  bullets.forEach(function (b) { c.drawImage(sLaser, b.x - s * 0.1, b.y - s * 0.35, s * 0.2, s * 0.7); });
  booms.forEach(function (b) { var k = b.t / 0.4; c.strokeStyle = 'rgba(255,220,100,' + (1 - k) + ')'; c.lineWidth = 3 * d; c.beginPath(); c.arc(b.x, b.y, s * (0.2 + k * 0.6), 0, 7); c.stroke(); });
  if (inited) {
    var fl = 0.7 + 0.3 * Math.sin(tm * 40);
    c.fillStyle = '#FFB347'; c.beginPath(); c.moveTo(tx - s * 0.12, ty + s * 0.36); c.lineTo(tx + s * 0.12, ty + s * 0.36); c.lineTo(tx, ty + s * (0.36 + 0.35 * fl)); c.closePath(); c.fill();
    if (flash <= 0 || Math.floor(flash * 20) % 2) c.drawImage(sShip, tx - s / 2, ty - s / 2, s, s);
  }
  for (var i = 0; i < LIVES; i++) heart(c, 20 * d + i * 26 * d, 24 * d, 9 * d, i < lives);
  c.fillStyle = '#EAF0FF'; c.font = 'bold ' + Math.round(20 * d) + 'px sans-serif'; c.textAlign = 'right';
  c.fillText(kills + ' / ' + GOAL, G.w - 14 * d, 30 * d); c.textAlign = 'left';
}
