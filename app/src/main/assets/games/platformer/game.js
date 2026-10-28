// Платформер с инерцией: скорость по земле (gsp), склоны, качение, заряженный рывок, пружины, кольца.
// Весь персонаж, уровень и графика — оригинальные. Все картинки заданы через G.sprite(имя, размер, рисунок)
// и заменяются в редакторе графики: hero, hero_ball, enemy, ring, spring, flag.
// Хост вызывает: init(G), update(dt), draw(ctx), onTouch(points)

// ---------- физика (единицы: пиксели и кадры при 60 fps) ----------
var ACC = 0.046875, DEC = 0.5, FRC = 0.046875, TOP = 6;
var ROLL_FRC = 0.0234375, ROLL_DEC = 0.125;
var SLOPE = 0.125, SLOPE_ROLL_UP = 0.078125, SLOPE_ROLL_DOWN = 0.3125;
var AIR_ACC = 0.09375, GRAV = 0.21875, JUMP = 6.5, JUMP_CUT = 4, MAX_FALL = 16;
var SPRING = 10;

// ---------- уровень ----------
var L = 6400;
// контрольные точки поверхности [x, y] (y вниз), между ними плавная кривая
var PTS = [[0,200],[500,200],[760,120],[1000,120],[1280,205],[1600,205],[1900,150],[2000,150],[2300,215],[2480,215],
           [2800,140],[3050,140],[3400,200],[3700,200],[3706,176],[3760,176],[3766,200],[4100,200],[4400,100],[4640,100],
           [4950,205],[5400,205],[5700,150],[5900,150],[6150,200],[6400,200]];
var GAPS = [[2640,2730],[3900,4000],[5460,5540]];            // ямы (падение = поражение)
var PLATS = [[2630,112,110],[5450,150,100]];                  // одноуровневые платформы [x, верх, ширина]
var SPRINGS = [900, 3200, 4700];
var RING_ROWS = [[300,170,6,30],[720,110,5,28],[1350,170,5,28],[2060,150,6,30],[2650,80,6,16],[3250,120,5,28],[4120,80,5,30],[4480,70,6,26],[5470,120,5,18],[5750,125,5,28],[6000,150,4,28]];
var FOES = [[1100,1230],[1700,1850],[2350,2470],[2900,3030],[3460,3650],[4150,4300],[5000,5150],[5420,5450]];
var FLAG_X = 6250;

var gyArr, rings, foes, plats, springs, hero, camX, camY, held, prev, tt, acc, ringCount, sHero, sBall, sFoe, sRing, sSpring, sFlag, btn;

function rr(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); c.fill();
}
function build() {
  gyArr = new Float32Array(L + 10);
  var k = 0;
  for (var x = 0; x < L + 10; x++) {
    while (k < PTS.length - 2 && x > PTS[k + 1][0]) k++;
    var a = PTS[k], b = PTS[Math.min(k + 1, PTS.length - 1)];
    var u = b[0] === a[0] ? 1 : Math.max(0, Math.min(1, (x - a[0]) / (b[0] - a[0])));
    u = u * u * (3 - 2 * u);
    gyArr[x] = a[1] + (b[1] - a[1]) * u;
  }
  GAPS.forEach(function (g) { for (var x = g[0]; x <= g[1]; x++) gyArr[x] = 1e5; });
}
function gy(x) { x = Math.round(x); return x < 0 ? gyArr[0] : (x >= L ? gyArr[L - 1] : gyArr[x]); }
// высота опоры в точке x для ног на высоте ref (платформы одноуровневые: считаются, если они не выше ног)
function gnd(x, ref) {
  var best = gy(x);
  for (var i = 0; i < plats.length; i++) {
    var p = plats[i];
    if (x >= p.x && x <= p.x + p.w && p.y >= ref - 8 && p.y < best) best = p.y;
  }
  return best;
}
function angleAt(x, ref) {
  var a = gnd(x - 4, ref), b = gnd(x + 4, ref);
  if (a > 1e4 || b > 1e4 || Math.abs(b - a) > 24) return 0;
  return Math.atan2(b - a, 8);
}

function init() {
  sHero = G.sprite('hero', 64, function (c, s) {
    c.fillStyle = '#FFE9C7'; c.beginPath(); c.ellipse(s / 2, s * 0.58, s * 0.34, s * 0.32, 0, 0, 7); c.fill();
    c.fillStyle = '#FFB3D1'; c.beginPath(); c.moveTo(s * 0.2, s * 0.4); c.lineTo(s * 0.22, s * 0.05); c.lineTo(s * 0.42, s * 0.28); c.fill();
    c.beginPath(); c.moveTo(s * 0.8, s * 0.4); c.lineTo(s * 0.78, s * 0.05); c.lineTo(s * 0.58, s * 0.28); c.fill();
    c.fillStyle = '#3E2670'; c.beginPath(); c.arc(s * 0.62, s * 0.52, s * 0.06, 0, 7); c.arc(s * 0.4, s * 0.52, s * 0.06, 0, 7); c.fill();
    c.fillStyle = '#FF7FB0'; c.beginPath(); c.arc(s * 0.3, s * 0.66, s * 0.05, 0, 7); c.arc(s * 0.72, s * 0.66, s * 0.05, 0, 7); c.fill();
  });
  sBall = G.sprite('hero_ball', 64, function (c, s) {
    c.fillStyle = '#FFB3D1'; c.beginPath(); c.arc(s / 2, s / 2, s * 0.4, 0, 7); c.fill();
    c.fillStyle = '#FFE9C7'; c.beginPath(); c.arc(s * 0.4, s * 0.38, s * 0.16, 0, 7); c.fill();
    c.strokeStyle = '#E2548E'; c.lineWidth = s * 0.06; c.beginPath(); c.arc(s / 2, s / 2, s * 0.4, 0, 7); c.stroke();
  });
  sFoe = G.sprite('enemy', 64, function (c, s) {
    c.fillStyle = '#6FB7FF'; rr(c, s * 0.1, s * 0.2, s * 0.8, s * 0.7, s * 0.3);
    c.fillStyle = '#fff'; c.beginPath(); c.arc(s * 0.36, s * 0.48, s * 0.1, 0, 7); c.arc(s * 0.64, s * 0.48, s * 0.1, 0, 7); c.fill();
    c.fillStyle = '#2B2B5E'; c.beginPath(); c.arc(s * 0.34, s * 0.5, s * 0.05, 0, 7); c.arc(s * 0.62, s * 0.5, s * 0.05, 0, 7); c.fill();
    c.fillStyle = '#3B82D9'; c.fillRect(s * 0.25, s * 0.15, s * 0.5, s * 0.08);
  });
  sRing = G.sprite('ring', 32, function (c, s) {
    c.strokeStyle = '#FFC93C'; c.lineWidth = s * 0.16; c.beginPath(); c.arc(s / 2, s / 2, s * 0.32, 0, 7); c.stroke();
  });
  sSpring = G.sprite('spring', 64, function (c, s) {
    c.fillStyle = '#9E9E9E'; c.fillRect(s * 0.2, s * 0.7, s * 0.6, s * 0.3);
    c.strokeStyle = '#E53935'; c.lineWidth = s * 0.08;
    for (var i = 0; i < 3; i++) { c.beginPath(); c.moveTo(s * 0.22, s * (0.3 + i * 0.14)); c.lineTo(s * 0.78, s * (0.38 + i * 0.14)); c.stroke(); }
    c.fillStyle = '#FFC93C'; c.fillRect(s * 0.15, s * 0.18, s * 0.7, s * 0.1);
  });
  sFlag = G.sprite('flag', 64, function (c, s) {
    c.fillStyle = '#795548'; c.fillRect(s * 0.45, s * 0.05, s * 0.08, s * 0.95);
    c.fillStyle = '#FF4F98'; c.beginPath(); c.moveTo(s * 0.53, s * 0.08); c.lineTo(s * 0.95, s * 0.25); c.lineTo(s * 0.53, s * 0.45); c.fill();
  });
  build();
  plats = PLATS.map(function (p) { return { x: p[0], y: p[1], w: p[2] }; });
  springs = SPRINGS.map(function (x) { return { x: x, t: 0 }; });
  rings = [];
  RING_ROWS.forEach(function (r) { for (var i = 0; i < r[2]; i++) rings.push({ x: r[0] + i * r[3], y: r[1], got: false }); });
  foes = FOES.map(function (f) { return { x: f[0], a: f[0], b: f[1], dir: 1, dead: false }; });
  hero = { x: 80, y: gy(80), xsp: 0, ysp: 0, gsp: 0, ground: true, ang: 0, roll: false, jumping: false, charge: 0, charging: false, face: 1, inv: 0 };
  held = { l: false, r: false, down: false, jump: false }; prev = { jump: false };
  camX = 0; camY = 40; tt = 0; acc = 0; ringCount = 0; btn = {};
}

function onTouch(pts) {
  var h = { l: false, r: false, down: false, jump: false };
  pts.forEach(function (p) {
    for (var k in btn) {
      var b = btn[k], m = b.s * 0.1;
      if (p.x >= b.x - m && p.x <= b.x + b.s + m && p.y >= b.y - m && p.y <= b.y + b.s + m) h[k] = true;
    }
  });
  held = h;
}

function hurt() {
  if (hero.inv > 0) return;
  if (ringCount <= 0) { G.lose(); return; }
  ringCount = 0; hero.inv = 120; hero.xsp = -hero.face * 2; hero.ysp = -4; hero.ground = false; hero.roll = false; hero.jumping = false;
}

function step() {
  var h = hero, jumpPressed = held.jump && !prev.jump;
  prev.jump = held.jump;
  var dir = (held.r ? 1 : 0) - (held.l ? 1 : 0);
  if (h.inv > 0) h.inv--;
  var ox = h.x;

  if (h.ground) {
    var ang = angleAt(h.x, h.y); h.ang = ang;
    // заряд рывка: стоя + вниз
    if (held.down && Math.abs(h.gsp) < 0.5 && !h.roll) {
      h.charging = true;
      if (jumpPressed) h.charge = Math.min(8, h.charge + 2);
      h.charge -= h.charge / 32;
      h.gsp = 0;
    } else if (h.charging) {
      h.charging = false;
      h.gsp = (8 + Math.floor(h.charge) / 2) * h.face; h.roll = true; h.charge = 0;
    } else {
      // ускорение / торможение
      if (!h.roll) {
        if (dir < 0) { if (h.gsp > 0) { h.gsp -= DEC; if (h.gsp <= 0) h.gsp = -0.5; } else if (h.gsp > -TOP) { h.gsp -= ACC; if (h.gsp < -TOP) h.gsp = -TOP; } h.face = -1; }
        else if (dir > 0) { if (h.gsp < 0) { h.gsp += DEC; if (h.gsp >= 0) h.gsp = 0.5; } else if (h.gsp < TOP) { h.gsp += ACC; if (h.gsp > TOP) h.gsp = TOP; } h.face = 1; }
        else { var f = Math.min(Math.abs(h.gsp), FRC); h.gsp -= f * Math.sign(h.gsp); }
        if (held.down && Math.abs(h.gsp) >= 1) h.roll = true;
      } else {
        if (dir !== 0 && Math.sign(h.gsp) !== dir) { h.gsp += ROLL_DEC * dir; }
        var f2 = Math.min(Math.abs(h.gsp), ROLL_FRC); h.gsp -= f2 * Math.sign(h.gsp);
        if (Math.abs(h.gsp) < 0.5) { h.roll = false; }
      }
    }
    // склон: вниз по склону разгоняет, вверх тормозит (сильнее без качения)
    var sl = Math.sin(ang), k;
    if (h.roll) k = (Math.sign(h.gsp) * sl > 0) ? SLOPE_ROLL_DOWN : SLOPE_ROLL_UP; else k = SLOPE;
    if (Math.abs(sl) > 0.02) h.gsp += k * sl;
    if (h.roll && Math.abs(h.gsp) > 16) h.gsp = 16 * Math.sign(h.gsp);
    // прыжок (перпендикулярно поверхности)
    if (jumpPressed && !h.charging) {
      h.xsp = h.gsp * Math.cos(ang) + JUMP * Math.sin(ang);
      h.ysp = h.gsp * Math.sin(ang) - JUMP * Math.cos(ang);
      h.ground = false; h.jumping = true; h.roll = false;
    } else {
      var nx = h.x + h.gsp * Math.cos(ang);
      var tol = Math.abs(h.gsp) * 1.1 + 4;
      var gnew = gnd(nx, h.y);
      if (gnew < h.y - tol - 6) { h.gsp = 0; }                       // стена
      else {
        h.x = nx;
        if (gnew - h.y > tol + 2 || gnew > 1e4) {                    // съехали с края — в воздух
          h.xsp = h.gsp * Math.cos(ang); h.ysp = h.gsp * Math.sin(ang); h.ground = false; h.roll = false;
        } else h.y = gnew;
        // слишком медленно на крутом склоне — отваливаемся
        if (Math.abs(sl) > 0.62 && Math.abs(h.gsp) < 2.5) { h.ground = false; h.xsp = h.gsp * Math.cos(ang); h.ysp = h.gsp * Math.sin(ang); h.roll = false; }
      }
    }
  } else {
    // воздух
    if (!h.jumping || true) {
      if (dir < 0) { if (h.xsp > -TOP) { h.xsp -= AIR_ACC; if (h.xsp < -TOP) h.xsp = -TOP; } h.face = -1; }
      else if (dir > 0) { if (h.xsp < TOP) { h.xsp += AIR_ACC; if (h.xsp > TOP) h.xsp = TOP; } h.face = 1; }
    }
    if (h.jumping && !held.jump && h.ysp < -JUMP_CUT) h.ysp = -JUMP_CUT;
    if (h.ysp < 0 && h.ysp > -4) h.xsp -= Math.floor(h.xsp / 0.125) / 256;
    var py = h.y;
    h.x += h.xsp; h.y += h.ysp; h.ysp = Math.min(MAX_FALL, h.ysp + GRAV);
    var g = gnd(h.x, py - 1);
    if (g < 1e4 && h.y >= g) {
      if (h.ysp >= 0 && py <= g + 6) {                                  // приземление
        h.y = g; h.ground = true; h.jumping = false;
        var a2 = angleAt(h.x, h.y); h.ang = a2;
        h.gsp = h.xsp * Math.cos(a2) + h.ysp * Math.sin(a2);
        if (Math.abs(a2) < 0.1) h.gsp = h.xsp;
        h.roll = false;
      } else if (h.y > g + 2 && g < py - 2) { h.x -= h.xsp; h.xsp = 0; } // ударились о стенку сбоку
    }
    if (h.y > 470) { G.lose(); return; }
  }
  if (h.x < 8) { h.x = 8; if (h.gsp < 0) h.gsp = 0; if (h.xsp < 0) h.xsp = 0; }

  // пружины
  for (var i = 0; i < springs.length; i++) {
    var sp = springs[i]; if (sp.t > 0) sp.t--;
    if (Math.abs(h.x - sp.x) < 16 && h.y >= gy(sp.x) - 18 && h.y <= gy(sp.x) + 6 && (h.ysp >= 0 || h.ground)) {
      h.ysp = -SPRING; h.ground = false; h.jumping = false; h.roll = false; sp.t = 12;
    }
  }
  // кольца
  for (i = 0; i < rings.length; i++) {
    var r = rings[i];
    if (!r.got && Math.abs(r.x - h.x) < 18 && Math.abs(r.y - (h.y - 14)) < 22) { r.got = true; ringCount++; }
  }
  // враги
  for (i = 0; i < foes.length; i++) {
    var e = foes[i]; if (e.dead) continue;
    e.x += e.dir * 0.6; if (e.x < e.a) e.dir = 1; if (e.x > e.b) e.dir = -1;
    var ey = gy(e.x), dx = Math.abs(e.x - h.x), dy = h.y - ey;
    if (dx < 18 && dy > -30 && dy < 8) {
      var attack = h.roll || h.jumping || (!h.ground && h.ysp > 0);
      if (attack) { e.dead = true; if (!h.ground) h.ysp = h.ysp > 0 ? -Math.min(6, h.ysp + 1) : -h.ysp * 0.6 + 0 ; } else hurt();
    }
  }
  if (h.x >= FLAG_X) { G.win(); return; }
}

function update(dt) {
  acc += dt; tt += dt;
  var n = 0;
  while (acc >= 1 / 60 && n++ < 5 && !G.done) { acc -= 1 / 60; step(); }
  var s = scaleOf(), vw = G.w / s, vh = G.h / s;
  var lead = hero.ground ? hero.gsp * 8 : hero.xsp * 8;
  var tx = Math.max(0, Math.min(L - vw, hero.x - vw * 0.45 + lead));
  camX += (tx - camX) * 0.15;
  var ty = Math.max(-120, Math.min(260 - vh * 0.7, hero.y - vh * 0.68));
  camY += (ty - camY) * 0.1;
}

// масштаб: на узком экране показываем не меньше ~260 px мира по ширине
function scaleOf() { return Math.min(G.h / 240, G.w / 260); }

function drawBtn(c, key, label, x, y, sz) {
  btn[key] = { x: x, y: y, s: sz };
  c.globalAlpha = held[key] ? 0.75 : 0.4;
  c.fillStyle = '#4B2E83'; rr(c, x, y, sz, sz, sz * 0.25);
  c.globalAlpha = 1; c.fillStyle = '#fff'; c.font = 'bold ' + Math.round(sz * 0.28) + 'px sans-serif'; c.textAlign = 'center';
  c.fillText(label, x + sz / 2, y + sz * 0.62); c.textAlign = 'left';
}

function draw(c) {
  var s = scaleOf(), vw = G.w / s, vh = G.h / s;
  var sky = c.createLinearGradient(0, 0, 0, G.h); sky.addColorStop(0, '#9FE0FF'); sky.addColorStop(1, '#FFE6F3');
  c.fillStyle = sky; c.fillRect(0, 0, G.w, G.h);
  // дальние холмы (параллакс)
  c.fillStyle = '#C9B8F5';
  c.beginPath(); c.moveTo(0, G.h);
  for (var px = 0; px <= G.w; px += 12) c.lineTo(px, G.h * 0.55 + Math.sin((px / s + camX * 0.3) / 70) * 22 * s);
  c.lineTo(G.w, G.h); c.fill();
  c.save(); c.scale(s, s); c.translate(-camX, -camY);
  // земля
  var x0 = Math.floor(camX / 4) * 4, bottom = camY + vh + 12;
  for (var x = x0; x < camX + vw + 8; x += 4) {
    var a = gy(x), b = gy(x + 4); if (a > 1e4 || b > 1e4) continue;
    c.fillStyle = '#B4773E'; c.beginPath(); c.moveTo(x, a); c.lineTo(x + 4.5, b); c.lineTo(x + 4.5, bottom); c.lineTo(x, bottom); c.fill();
    c.fillStyle = '#6FD36B'; c.beginPath(); c.moveTo(x, a); c.lineTo(x + 4.5, b); c.lineTo(x + 4.5, b + 7); c.lineTo(x, a + 7); c.fill();
  }
  plats.forEach(function (p) { c.fillStyle = '#8D6E63'; rr(c, p.x, p.y, p.w, 12, 5); c.fillStyle = '#6FD36B'; c.fillRect(p.x, p.y, p.w, 4); });
  c.drawImage(sFlag, FLAG_X - 16, gy(FLAG_X) - 56, 56, 56);
  springs.forEach(function (sp) { var sq = sp.t > 0 ? 0.6 : 1; c.drawImage(sSpring, sp.x - 16, gy(sp.x) - 28 * sq, 32, 28 * sq); });
  rings.forEach(function (r) { if (!r.got) { var w = 16 * Math.abs(Math.cos(tt * 4 + r.x * 0.05)) + 4; c.drawImage(sRing, r.x - w / 2, r.y - 8, w, 16); } });
  foes.forEach(function (e) { if (!e.dead) c.drawImage(sFoe, e.x - 14, gy(e.x) - 26, 28, 26); });
  // герой
  var h = hero;
  if (h.inv === 0 || Math.floor(h.inv / 4) % 2 === 0) {
    c.save(); c.translate(h.x, h.y - 16);
    if (h.roll || h.jumping || h.charging) {
      c.rotate(h.charging ? 0 : tt * 14 * (h.face || 1)); c.drawImage(sBall, -14, -14, 28, 28);
    } else {
      c.rotate(h.ground ? h.ang * 0.9 : 0); c.scale(h.face, 1); c.drawImage(sHero, -16, -14, 32, 32);
    }
    c.restore();
  }
  c.restore();
  // интерфейс
  var u = Math.min(G.w, G.h) * 0.16, m = u * 0.2;
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + Math.round(20 * G.dpr) + 'px sans-serif';
  c.fillText('○ ' + ringCount, 14, 30 * G.dpr);
  // левый большой палец: стрелки, ROLL над ними; правый: JUMP
  drawBtn(c, 'l', '\u25C0', m, G.h - u - m, u);
  drawBtn(c, 'r', '\u25B6', m * 2 + u, G.h - u - m, u);
  drawBtn(c, 'down', 'ROLL', m + u / 2 + m / 2, G.h - u * 2 - m * 2.4, u);
  drawBtn(c, 'jump', 'JUMP', G.w - u - m, G.h - u - m, u);
}
