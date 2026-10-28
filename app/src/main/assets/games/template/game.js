// Шаблон новой игры. Хост вызывает (все функции необязательные):
//   init(G)        — один раз при старте
//   update(dt)     — каждый кадр, dt в секундах
//   draw(ctx)      — рисование на canvas (размеры G.w x G.h)
//   onTap(x, y)    — касание
//   onSwipe(dir)   — свайп: 0 вверх, 1 вправо, 2 вниз, 3 влево
//   onTouch(pts)   — список текущих касаний [{x,y}]
// G.win() — победа, G.lose() — поражение.
// G.sprite(имя, размер, (ctx,размер)=>рисунок) — спрайт по умолчанию; в редакторе графики его можно заменить картинкой.
var hits = 0, GOAL = 5, bx = 0, by = 0, sBall;
function init() {
  sBall = G.sprite('ball', 64, function (c, s) { c.fillStyle = '#FF4F98'; c.beginPath(); c.arc(s / 2, s / 2, s * 0.4, 0, 7); c.fill(); });
  place();
}
function place() { bx = G.rand(60, Math.max(61, G.w - 60)); by = G.rand(100, Math.max(101, G.h - 60)); }
function onTap(x, y) {
  if (Math.hypot(x - bx, y - by) < 60) { hits++; if (hits >= GOAL) G.win(); else place(); }
}
function update(dt) {}
function draw(c) {
  c.fillStyle = '#FFF0F7'; c.fillRect(0, 0, G.w, G.h);
  c.drawImage(sBall, bx - 40, by - 40, 80, 80);
  c.fillStyle = '#4B2E83'; c.font = 'bold 40px sans-serif';
  c.fillText(hits + '/' + GOAL, 20, 50);
}
