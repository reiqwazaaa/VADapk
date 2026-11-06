// ===== Шахматный движок: 64 клетки, r=0 это 8-я горизонталь, c=0 это вертикаль a. Фигуры: 1..6 = P N B R Q K, белые +, чёрные - =====
var Chess = (function () {
  var KN = [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]];
  var KG = [[-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1]];
  var DG = [[-1, -1], [-1, 1], [1, -1], [1, 1]], OR = [[-1, 0], [1, 0], [0, -1], [0, 1]];
  var EP = 2, CASTLE = 4, DOUBLE = 8;
  var CM = []; for (var i = 0; i < 64; i++) CM.push(15);
  CM[60] = 12; CM[63] = 14; CM[56] = 13; CM[4] = 3; CM[7] = 11; CM[0] = 7; // права рокировки, снимаемые ходом с/на эти клетки
  var VAL = [0, 100, 320, 330, 500, 900, 0];

  function fromFEN(fen) {
    var p = { b: new Array(64).fill(0), turn: 1, castle: 0, ep: -1, half: 0, full: 1, hist: [] };
    var parts = fen.split(' '), rows = parts[0].split('/'), map = { p: 1, n: 2, b: 3, r: 4, q: 5, k: 6 };
    for (var r = 0; r < 8; r++) {
      var c = 0;
      for (var i = 0; i < rows[r].length; i++) {
        var ch = rows[r][i];
        if (ch >= '1' && ch <= '8') c += +ch;
        else { var lo = ch.toLowerCase(); p.b[r * 8 + c] = (ch === lo ? -1 : 1) * map[lo]; c++; }
      }
    }
    p.turn = parts[1] === 'b' ? -1 : 1;
    var cs = parts[2] || '-';
    if (cs.indexOf('K') >= 0) p.castle |= 1; if (cs.indexOf('Q') >= 0) p.castle |= 2;
    if (cs.indexOf('k') >= 0) p.castle |= 4; if (cs.indexOf('q') >= 0) p.castle |= 8;
    p.ep = parts[3] && parts[3] !== '-' ? (8 - +parts[3][1]) * 8 + parts[3].charCodeAt(0) - 97 : -1;
    p.half = +(parts[4] || 0); p.full = +(parts[5] || 1);
    return p;
  }
  function toFEN(p) {
    var s = '', L = ' PNBRQK';
    for (var r = 0; r < 8; r++) {
      var e = 0;
      for (var c = 0; c < 8; c++) {
        var v = p.b[r * 8 + c];
        if (!v) { e++; continue; }
        if (e) { s += e; e = 0; }
        s += v > 0 ? L[v] : L[-v].toLowerCase();
      }
      if (e) s += e;
      if (r < 7) s += '/';
    }
    var cs = (p.castle & 1 ? 'K' : '') + (p.castle & 2 ? 'Q' : '') + (p.castle & 4 ? 'k' : '') + (p.castle & 8 ? 'q' : '');
    var ep = p.ep < 0 ? '-' : String.fromCharCode(97 + (p.ep & 7)) + (8 - (p.ep >> 3));
    return s + ' ' + (p.turn === 1 ? 'w' : 'b') + ' ' + (cs || '-') + ' ' + ep + ' ' + p.half + ' ' + p.full;
  }
  function startPos() { return fromFEN('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'); }

  // клетка sq атакована цветом by?
  function attacked(p, sq, by) {
    var b = p.b, r = sq >> 3, c = sq & 7, i, rr, cc, v;
    var pr = r + (by === 1 ? 1 : -1);
    if (pr >= 0 && pr < 8) {
      if (c > 0 && b[pr * 8 + c - 1] === by) return true;
      if (c < 7 && b[pr * 8 + c + 1] === by) return true;
    }
    for (i = 0; i < 8; i++) {
      rr = r + KN[i][0]; cc = c + KN[i][1];
      if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] === 2 * by) return true;
      rr = r + KG[i][0]; cc = c + KG[i][1];
      if (rr >= 0 && rr < 8 && cc >= 0 && cc < 8 && b[rr * 8 + cc] === 6 * by) return true;
    }
    for (i = 0; i < 4; i++) {
      rr = r + DG[i][0]; cc = c + DG[i][1];
      while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
        v = b[rr * 8 + cc];
        if (v) { if (v === 3 * by || v === 5 * by) return true; break; }
        rr += DG[i][0]; cc += DG[i][1];
      }
      rr = r + OR[i][0]; cc = c + OR[i][1];
      while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
        v = b[rr * 8 + cc];
        if (v) { if (v === 4 * by || v === 5 * by) return true; break; }
        rr += OR[i][0]; cc += OR[i][1];
      }
    }
    return false;
  }
  function kingSq(p, col) { for (var s = 0; s < 64; s++) if (p.b[s] === 6 * col) return s; return -1; }
  function inCheck(p, col) { var k = kingSq(p, col); return k >= 0 && attacked(p, k, -col); }

  // псевдолегальные ходы: int = from | to<<6 | promo<<12 | flags<<15
  function gen(p, caps) {
    var out = [], b = p.b, us = p.turn, s, pc, a, r, c, i, rr, cc, ts, v, d;
    function pawn(f, t, promo) {
      if (promo) { out.push(f | t << 6 | 5 << 12); out.push(f | t << 6 | 4 << 12); out.push(f | t << 6 | 3 << 12); out.push(f | t << 6 | 2 << 12); }
      else out.push(f | t << 6);
    }
    for (s = 0; s < 64; s++) {
      pc = b[s]; if (pc * us <= 0) continue;
      a = pc * us; r = s >> 3; c = s & 7;
      if (a === 1) {
        var dr = -us, r1 = r + dr, start = us === 1 ? 6 : 1, last = us === 1 ? 0 : 7;
        if (r1 < 0 || r1 > 7) continue;
        if (b[r1 * 8 + c] === 0 && (!caps || r1 === last)) {
          pawn(s, r1 * 8 + c, r1 === last);
          if (!caps && r === start && b[(r + 2 * dr) * 8 + c] === 0) out.push(s | (r + 2 * dr) * 8 + c << 6 | DOUBLE << 15);
        }
        for (d = -1; d <= 1; d += 2) {
          cc = c + d; if (cc < 0 || cc > 7) continue;
          ts = r1 * 8 + cc;
          if (b[ts] * us < 0) pawn(s, ts, r1 === last);
          else if (ts === p.ep) out.push(s | ts << 6 | EP << 15);
        }
      } else if (a === 2 || a === 6) {
        var T = a === 2 ? KN : KG;
        for (i = 0; i < 8; i++) {
          rr = r + T[i][0]; cc = c + T[i][1];
          if (rr < 0 || rr > 7 || cc < 0 || cc > 7) continue;
          v = b[rr * 8 + cc];
          if (v * us > 0) continue;
          if (caps && !v) continue;
          out.push(s | (rr * 8 + cc) << 6);
        }
      } else {
        for (i = 0; i < 8; i++) {
          if ((a === 3 && i >= 4) || (a === 4 && i < 4)) { if (a === 3 && i >= 4) break; }
          var dd = i < 4 ? DG[i] : OR[i - 4];
          if (a === 3 && i >= 4) continue;
          if (a === 4 && i < 4) continue;
          rr = r + dd[0]; cc = c + dd[1];
          while (rr >= 0 && rr < 8 && cc >= 0 && cc < 8) {
            v = b[rr * 8 + cc];
            if (v * us > 0) break;
            if (!caps || v) out.push(s | (rr * 8 + cc) << 6);
            if (v) break;
            rr += dd[0]; cc += dd[1];
          }
        }
      }
    }
    if (!caps) {
      if (us === 1 && b[60] === 6) {
        if ((p.castle & 1) && !b[61] && !b[62] && b[63] === 4 && !attacked(p, 60, -1) && !attacked(p, 61, -1) && !attacked(p, 62, -1)) out.push(60 | 62 << 6 | CASTLE << 15);
        if ((p.castle & 2) && !b[59] && !b[58] && !b[57] && b[56] === 4 && !attacked(p, 60, -1) && !attacked(p, 59, -1) && !attacked(p, 58, -1)) out.push(60 | 58 << 6 | CASTLE << 15);
      } else if (us === -1 && b[4] === -6) {
        if ((p.castle & 4) && !b[5] && !b[6] && b[7] === -4 && !attacked(p, 4, 1) && !attacked(p, 5, 1) && !attacked(p, 6, 1)) out.push(4 | 6 << 6 | CASTLE << 15);
        if ((p.castle & 8) && !b[3] && !b[2] && !b[1] && b[0] === -4 && !attacked(p, 4, 1) && !attacked(p, 3, 1) && !attacked(p, 2, 1)) out.push(4 | 2 << 6 | CASTLE << 15);
      }
    }
    return out;
  }

  function make(p, m) {
    var f = m & 63, t = (m >> 6) & 63, pr = (m >> 12) & 7, fl = m >> 15, b = p.b, pc = b[f];
    var u = { m: m, cap: b[t], castle: p.castle, ep: p.ep, half: p.half, pc: pc };
    if (fl & EP) { u.cs = (f >> 3) * 8 + (t & 7); u.cap = b[u.cs]; b[u.cs] = 0; }
    b[t] = pr ? pr * p.turn : pc; b[f] = 0;
    if (fl & CASTLE) {
      if ((t & 7) === 6) { b[f + 1] = b[f + 3]; b[f + 3] = 0; } else { b[f - 1] = b[f - 4]; b[f - 4] = 0; }
    }
    p.castle &= CM[f] & CM[t];
    p.ep = fl & DOUBLE ? (f + t) >> 1 : -1;
    p.half = (pc === 1 || pc === -1 || u.cap) ? 0 : p.half + 1;
    if (p.turn === -1) p.full++;
    p.turn = -p.turn;
    p.hist.push(u);
  }
  function undo(p) {
    var u = p.hist.pop(), m = u.m, f = m & 63, t = (m >> 6) & 63, fl = m >> 15, b = p.b;
    p.turn = -p.turn;
    if (p.turn === -1) p.full--;
    b[f] = u.pc; b[t] = 0;
    if (fl & EP) b[u.cs] = u.cap; else b[t] = u.cap;
    if (fl & CASTLE) {
      if ((t & 7) === 6) { b[f + 3] = b[f + 1]; b[f + 1] = 0; } else { b[f - 4] = b[f - 1]; b[f - 1] = 0; }
    }
    p.castle = u.castle; p.ep = u.ep; p.half = u.half;
  }
  function legal(p) {
    var ms = gen(p, false), out = [], us = p.turn;
    for (var i = 0; i < ms.length; i++) { make(p, ms[i]); if (!inCheck(p, us)) out.push(ms[i]); undo(p); }
    return out;
  }
  function legalFrom(p, sq) { return legal(p).filter(function (m) { return (m & 63) === sq; }); }
  function isMate(p) { return inCheck(p, p.turn) && legal(p).length === 0; }
  function isStalemate(p) { return !inCheck(p, p.turn) && legal(p).length === 0; }
  function insufficient(p) {
    var minors = [], i, v;
    for (i = 0; i < 64; i++) {
      v = Math.abs(p.b[i]);
      if (v === 1 || v === 4 || v === 5) return false;
      if (v === 2 || v === 3) minors.push(i);
    }
    if (minors.length <= 1) return true;
    if (minors.length === 2 && p.b[minors[0]] * p.b[minors[1]] < 0 && Math.abs(p.b[minors[0]]) === 3 && Math.abs(p.b[minors[1]]) === 3) {
      return ((minors[0] >> 3) + (minors[0] & 7)) % 2 === ((minors[1] >> 3) + (minors[1] & 7)) % 2;
    }
    return false;
  }
  function key(p) { return p.b.join(',') + p.turn + p.castle + p.ep; }
  function sqName(s) { return String.fromCharCode(97 + (s & 7)) + (8 - (s >> 3)); }
  function moveStr(m) { return sqName(m & 63) + sqName((m >> 6) & 63) + (((m >> 12) & 7) ? ' nbrq'[((m >> 12) & 7) - 1] : ''); }
  function perft(p, d) {
    if (d === 0) return 1;
    var ms = legal(p), n = 0;
    if (d === 1) return ms.length;
    for (var i = 0; i < ms.length; i++) { make(p, ms[i]); n += perft(p, d - 1); undo(p); }
    return n;
  }

  // ---------- оценка и поиск ----------
  function evaluate(p) { // с точки зрения ходящего
    var b = p.b, sc = 0, mat = 0, bw = 0, bb = 0, s, v, a, us, r, c, rr, d;
    for (s = 0; s < 64; s++) { v = b[s]; if (v && Math.abs(v) > 1 && Math.abs(v) < 6) mat += VAL[Math.abs(v)]; }
    var endg = mat < 2600;
    for (s = 0; s < 64; s++) {
      v = b[s]; if (!v) continue;
      a = Math.abs(v); us = v > 0 ? 1 : -1; r = s >> 3; c = s & 7;
      rr = us === 1 ? 7 - r : r; d = Math.max(Math.abs(r - 3.5), Math.abs(c - 3.5));
      var x = VAL[a];
      if (a === 1) x += rr * rr * 2 + (c === 3 || c === 4 ? rr * 3 : 0);
      else if (a === 2) x += (3.5 - d) * 12;
      else if (a === 3) { x += (3.5 - d) * 6; if (us === 1) bw++; else bb++; }
      else if (a === 4) x += (rr === 6 ? 18 : 0) + (c === 3 || c === 4 ? 4 : 0);
      else if (a === 5) x += (3.5 - d) * 3;
      else if (a === 6) x += endg ? (3.5 - d) * 14 : -rr * 12 + (c <= 2 || c >= 6 ? 14 : 0);
      sc += us * x;
    }
    if (bw > 1) sc += 30; if (bb > 1) sc -= 30;
    return p.turn === 1 ? sc : -sc;
  }
  function order(p, ms) {
    var b = p.b, sc = [], i, m, v;
    for (i = 0; i < ms.length; i++) {
      m = ms[i]; v = 0;
      var cap = b[(m >> 6) & 63];
      if (cap || (m >> 15) & EP) v = 10 * VAL[Math.abs(cap || 1)] - VAL[Math.abs(b[m & 63])] / 10 + 10000;
      if ((m >> 12) & 7) v += 9000;
      sc.push(v);
    }
    var idx = ms.map(function (_, k) { return k; });
    idx.sort(function (x, y) { return sc[y] - sc[x]; });
    return idx.map(function (k) { return ms[k]; });
  }
  var nodes = 0, deadline = 0, ABORT = { abort: 1 };
  function qs(p, alpha, beta) {
    if ((++nodes & 1023) === 0 && deadline && Date.now() > deadline) throw ABORT;
    var us = p.turn, stand = evaluate(p);
    if (stand >= beta) return beta;
    if (stand > alpha) alpha = stand;
    var ms = order(p, gen(p, true));
    for (var i = 0; i < ms.length; i++) {
      make(p, ms[i]);
      if (inCheck(p, us)) { undo(p); continue; }
      var v = -qs(p, -beta, -alpha);
      undo(p);
      if (v >= beta) return beta;
      if (v > alpha) alpha = v;
    }
    return alpha;
  }
  function search(p, depth, alpha, beta, ply) {
    if ((++nodes & 1023) === 0 && deadline && Date.now() > deadline) throw ABORT;
    var us = p.turn, chk = inCheck(p, us);
    if (chk) depth++;
    if (depth <= 0) return qs(p, alpha, beta);
    if (p.half >= 100) return 0;
    var ms = order(p, gen(p, false)), n = 0, best = -99999;
    for (var i = 0; i < ms.length; i++) {
      make(p, ms[i]);
      if (inCheck(p, us)) { undo(p); continue; }
      n++;
      var v = -search(p, depth - 1, -beta, -alpha, ply + 1);
      undo(p);
      if (v > best) best = v;
      if (v > alpha) alpha = v;
      if (alpha >= beta) break;
    }
    if (n === 0) return chk ? -30000 + ply : 0;
    return best;
  }
  // Лучший ход: опции {depth, ms (лимит времени), blunder (0..1: шанс сыграть не лучший из топ-ходов)}
  function think(p, o) {
    var root = legal(p);
    if (!root.length) return null;
    if (root.length === 1) return root[0];
    root = order(p, root);
    var best = root[0], maxD = o.depth || 3;
    deadline = o.ms ? Date.now() + o.ms : 0;
    var scored = [];
    try {
      for (var d = 1; d <= maxD; d++) {
        var alpha = -99999, curBest = null, list = [];
        for (var i = 0; i < root.length; i++) {
          make(p, root[i]);
          var v = -search(p, d - 1, -99999, -alpha + (o.blunder ? 60 : 0), 1);
          undo(p);
          list.push([root[i], v]);
          if (v > alpha) { alpha = v; curBest = root[i]; }
        }
        best = curBest || best; scored = list;
        list.sort(function (a, b) { return b[1] - a[1]; });
        root = list.map(function (x) { return x[0]; });
        if (alpha > 29000) break;
      }
    } catch (e) { if (e !== ABORT) throw e; }
    if (o.blunder && scored.length > 1 && Math.random() < o.blunder) {
      // не лучший, но разумный ход: из первых четырёх, не подставляющий мат в 1
      var pick = scored.slice(0, 4), tries = 6;
      while (tries--) {
        var mv = pick[Math.floor(Math.random() * pick.length)][0];
        make(p, mv); var bad = hasMateIn1(p); undo(p);
        if (!bad) return mv;
      }
    }
    return best;
  }
  function hasMateIn1(p) { // у ходящего есть мат в один ход?
    var ms = legal(p);
    for (var i = 0; i < ms.length; i++) { make(p, ms[i]); var m = isMate(p); undo(p); if (m) return true; }
    return false;
  }
  function matingMoves(p) { // ходы, ставящие мат сразу
    var ms = legal(p), out = [];
    for (var i = 0; i < ms.length; i++) { make(p, ms[i]); if (isMate(p)) out.push(ms[i]); undo(p); }
    return out;
  }
  // ходы белых (ходящего), после которых при любом ответе есть мат в 1: решения «мат в 2»
  function mateIn2Keys(p) {
    var us = p.turn, ms = legal(p), keys = [];
    for (var i = 0; i < ms.length; i++) {
      make(p, ms[i]);
      var replies = legal(p), ok = replies.length > 0 && !isStalemate(p) ? true : false;
      if (replies.length === 0) ok = false; // мат или пат за один ход: это не «мат в 2»
      for (var j = 0; ok && j < replies.length; j++) {
        make(p, replies[j]);
        if (!hasMateIn1(p)) ok = false;
        undo(p);
      }
      undo(p);
      if (ok) keys.push(ms[i]);
    }
    return keys;
  }
  return { fromFEN: fromFEN, toFEN: toFEN, startPos: startPos, gen: gen, make: make, undo: undo, legal: legal, legalFrom: legalFrom,
    inCheck: inCheck, isMate: isMate, isStalemate: isStalemate, insufficient: insufficient, key: key, moveStr: moveStr, sqName: sqName,
    perft: perft, think: think, evaluate: evaluate, hasMateIn1: hasMateIn1, matingMoves: matingMoves, mateIn2Keys: mateIn2Keys, attacked: attacked, kingSq: kingSq };
})();

// ===== Графика шахмат: фигуры рисуются контурами (без юникод-глифов), доска, ввод ходов =====
function cRR(c, x, y, w, h, r) {
  c.beginPath(); c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
}
function drawPiece(c, a, white, x, y, s) {
  var fill = white ? '#FFFDF7' : '#5A3D99', line = white ? '#4B2E83' : '#2D1B55', det = white ? '#4B2E83' : '#E9DDFF';
  c.save(); c.translate(x, y); c.scale(s / 100, s / 100);
  c.lineWidth = 3.4; c.lineJoin = 'round'; c.fillStyle = fill; c.strokeStyle = line;
  function F() { c.fill(); c.stroke(); }
  function P(pts) { c.beginPath(); c.moveTo(pts[0][0], pts[0][1]); for (var i = 1; i < pts.length; i++) c.lineTo(pts[i][0], pts[i][1]); c.closePath(); F(); }
  function Ci(cx, cy, r) { c.beginPath(); c.arc(cx, cy, r, 0, 6.2832); F(); }
  function R(rx, ry, w, h, r) { cRR(c, rx, ry, w, h, r); F(); }
  if (a === 1) { Ci(50, 31, 12); P([[41, 42], [59, 42], [65, 66], [70, 78], [30, 78], [35, 66]]); }
  else if (a === 2) { P([[30, 78], [37, 54], [30, 48], [24, 40], [36, 32], [40, 20], [46, 12], [52, 22], [62, 26], [70, 42], [70, 58], [66, 78]]); c.fillStyle = det; c.beginPath(); c.arc(47, 30, 2.8, 0, 6.2832); c.fill(); c.fillStyle = fill; }
  else if (a === 3) { P([[36, 76], [43, 50], [57, 50], [64, 76]]); R(38, 46, 24, 7, 3); c.beginPath(); c.ellipse(50, 33, 12, 16, 0, 0, 6.2832); F(); Ci(50, 14, 5); c.strokeStyle = det; c.beginPath(); c.moveTo(44, 38); c.lineTo(55, 24); c.stroke(); c.strokeStyle = line; }
  else if (a === 4) { R(28, 20, 11, 14, 1); R(44.5, 20, 11, 14, 1); R(61, 20, 11, 14, 1); R(28, 32, 44, 9, 2); P([[32, 76], [36, 40], [64, 40], [68, 76]]); }
  else if (a === 5) { P([[33, 76], [26, 38], [38, 52], [44, 30], [50, 48], [56, 30], [62, 52], [74, 38], [67, 76]]); Ci(26, 33, 5); Ci(44, 25, 5); Ci(56, 25, 5); Ci(74, 33, 5); R(32, 62, 36, 8, 3); }
  else { P([[34, 76], [38, 42], [62, 42], [66, 76]]); R(34, 38, 32, 8, 3); R(46, 8, 8, 28, 2); R(38, 16, 24, 8, 2); }
  R(24, 74, 52, 13, 5);
  c.restore();
}
var BOARD_L = '#FFF3E0', BOARD_D = '#EFA9CE';
function sqXY(sq, g) { // левый верхний угол клетки на экране
  var r = sq >> 3, cc = sq & 7, cs = g.size / 8;
  if (g.flip) { r = 7 - r; cc = 7 - cc; }
  return { x: g.bx + cc * cs, y: g.by + r * cs, s: cs };
}
function xySq(x, y, g) {
  var cs = g.size / 8, cc = Math.floor((x - g.bx) / cs), r = Math.floor((y - g.by) / cs);
  if (cc < 0 || cc > 7 || r < 0 || r > 7) return -1;
  if (g.flip) { r = 7 - r; cc = 7 - cc; }
  return r * 8 + cc;
}
// st: {sel, moves, last:[from,to], check: sq}
function drawBoard(c, p, g, st) {
  var cs = g.size / 8, sq, o, i;
  c.fillStyle = '#4B2E83'; cRR(c, g.bx - cs * 0.12, g.by - cs * 0.12, g.size + cs * 0.24, g.size + cs * 0.24, cs * 0.18); c.fill();
  for (sq = 0; sq < 64; sq++) {
    o = sqXY(sq, g);
    c.fillStyle = (((sq >> 3) + (sq & 7)) & 1) ? BOARD_D : BOARD_L; c.fillRect(o.x, o.y, cs + 0.5, cs + 0.5);
  }
  if (st.last) for (i = 0; i < 2; i++) { o = sqXY(st.last[i], g); c.fillStyle = 'rgba(255,225,60,0.45)'; c.fillRect(o.x, o.y, cs, cs); }
  if (st.check >= 0) { o = sqXY(st.check, g); var gr = c.createRadialGradient(o.x + cs / 2, o.y + cs / 2, cs * 0.1, o.x + cs / 2, o.y + cs / 2, cs * 0.62); gr.addColorStop(0, 'rgba(255,30,60,0.85)'); gr.addColorStop(1, 'rgba(255,30,60,0)'); c.fillStyle = gr; c.fillRect(o.x, o.y, cs, cs); }
  if (st.sel >= 0) { o = sqXY(st.sel, g); c.fillStyle = 'rgba(255,200,0,0.6)'; c.fillRect(o.x, o.y, cs, cs); }
  for (sq = 0; sq < 64; sq++) { var v = p.b[sq]; if (v) { o = sqXY(sq, g); drawPiece(c, Math.abs(v), v > 0, o.x, o.y, cs); } }
  if (st.moves) for (i = 0; i < st.moves.length; i++) {
    var to = (st.moves[i] >> 6) & 63; o = sqXY(to, g);
    c.fillStyle = 'rgba(75,46,131,0.5)'; c.strokeStyle = 'rgba(75,46,131,0.55)'; c.lineWidth = cs * 0.08;
    c.beginPath();
    if (p.b[to] || (st.moves[i] >> 15) & 2) { c.arc(o.x + cs / 2, o.y + cs / 2, cs * 0.42, 0, 6.2832); c.stroke(); }
    else { c.arc(o.x + cs / 2, o.y + cs / 2, cs * 0.14, 0, 6.2832); c.fill(); }
  }
}
// окно выбора фигуры при превращении
function promoRects(g) {
  var cs = g.size / 8, w = cs * 4.4, x0 = g.bx + (g.size - w) / 2, y0 = g.by + g.size / 2 - cs * 0.75, out = [], i;
  for (i = 0; i < 4; i++) out.push({ x: x0 + cs * 0.2 + i * cs * 1.0, y: y0 + cs * 0.25, s: cs, p: [5, 4, 3, 2][i] });
  return { x: x0, y: y0, w: w, h: cs * 1.5, items: out };
}
function drawPromo(c, st, white, g) {
  var pr = promoRects(g), i;
  c.fillStyle = 'rgba(255,255,255,0.96)'; c.strokeStyle = '#4B2E83'; c.lineWidth = 3; cRR(c, pr.x, pr.y, pr.w, pr.h, 14); c.fill(); c.stroke();
  for (i = 0; i < 4; i++) drawPiece(c, pr.items[i].p, white, pr.items[i].x, pr.items[i].y, pr.items[i].s);
}
// Касание доски. Возвращает ход (int) если его нужно сыграть, иначе null. Обновляет st.sel/st.moves/st.promo.
function boardTap(st, p, x, y, g) {
  var i;
  if (st.promo) {
    var pr = promoRects(g), pick = 0;
    for (i = 0; i < 4; i++) { var it = pr.items[i]; if (x >= it.x && x <= it.x + it.s && y >= it.y && y <= it.y + it.s) pick = it.p; }
    var base = st.promo.base; st.promo = null;
    if (pick) { st.sel = -1; st.moves = []; return base | pick << 12; }
    return null;
  }
  var sq = xySq(x, y, g);
  if (sq < 0) { st.sel = -1; st.moves = []; return null; }
  if (st.sel >= 0) {
    var cand = st.moves.filter(function (m) { return ((m >> 6) & 63) === sq; });
    if (cand.length > 1) { st.promo = { base: cand[0] & ~(7 << 12) }; return null; }
    if (cand.length === 1) { st.sel = -1; st.moves = []; return cand[0]; }
  }
  if (p.b[sq] * p.turn > 0) { st.sel = sq; st.moves = Chess.legalFrom(p, sq); } else { st.sel = -1; st.moves = []; }
  return null;
}
function fitText(c, text, maxW, size, bold) { // подбирает размер шрифта по ширине
  var s = size;
  while (s > 8) { c.font = (bold ? 'bold ' : '') + s + 'px sans-serif'; if (c.measureText(text).width <= maxW) break; s -= 1; }
  return s;
}
function drawBtn(c, r, label, on) {
  c.fillStyle = on === false ? '#D9C6E8' : '#FF8FC7'; c.strokeStyle = '#fff'; c.lineWidth = 3;
  cRR(c, r.x, r.y, r.w, r.h, r.h / 2); c.fill(); c.stroke();
  c.fillStyle = on === false ? '#8A76A8' : '#4B2E83'; fitText(c, label, r.w - 16, Math.round(r.h * 0.42), true);
  c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(label, r.x + r.w / 2, r.y + r.h / 2 + 1); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
}
function inRect(r, x, y) { return x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h; }

// ===== Шахматы с Юта-тян: 3 уровня, радуется при победе и грустит при проигрыше =====
var G, P, st, g = {}, scr = 'menu', level = 1, colorSel = 2, me = 1, stats, over = null, aiT = 0, rep = [], bub = '', bubT = 0, mood = 'normal', moodT = 0, btns = {}, thinking = false;
function RU() { return G.lang === 'ru' || G.lang === 'rk'; }
function tx(ru, en) { return RU() ? ru : en; }
function pick(a) { return a[G.randInt(a.length)]; }
function say(ru, en, m, t) { bub = tx(ru, en); bubT = t || 5; if (m) { mood = m; moodT = t || 5; } }
function init(api) {
  G = api;
  try { stats = JSON.parse(G.load('chess_stats') || 'null'); } catch (e) { stats = null; }
  if (!stats || !stats.w) stats = { w: [0, 0, 0], l: [0, 0, 0], d: [0, 0, 0] };
  var lv = G.load('chess_level'); if (lv !== null) level = +lv || 0;
}
function saveStats() { try { G.save('chess_stats', JSON.stringify(stats)); G.save('chess_level', String(level)); } catch (e) {} }
function yutaImg(m, s) { // спрайт Юты-тян; запасной рисунок — простое личико
  return G.sprite('yuta_' + m, s, function (c, z) {
    c.fillStyle = '#FFD9E8'; c.beginPath(); c.arc(z / 2, z / 2, z * 0.46, 0, 6.2832); c.fill();
    c.fillStyle = '#4B2E83'; c.beginPath(); c.arc(z * 0.35, z * 0.45, z * 0.05, 0, 6.2832); c.arc(z * 0.65, z * 0.45, z * 0.05, 0, 6.2832); c.fill();
    c.strokeStyle = '#4B2E83'; c.lineWidth = z * 0.03; c.beginPath();
    if (m === 'sad') c.arc(z / 2, z * 0.75, z * 0.12, 3.6, 5.8); else c.arc(z / 2, z * 0.6, z * 0.14, 0.3, 2.85);
    c.stroke();
  });
}
function newGame() {
  me = colorSel === 2 ? (G.randInt(2) ? 1 : -1) : (colorSel === 0 ? 1 : -1);
  P = Chess.startPos(); rep = [Chess.key(P)]; over = null; thinking = false;
  st = { sel: -1, moves: [], last: null, check: -1, promo: null };
  scr = 'play'; aiT = 0.6;
  say(pick(['Давай сыграем! Я постараюсь~', 'Ура, партия! Удачи тебе!', 'Я готова! Ходи первым, если белые~']), pick(["Let's play! I'll do my best~", 'Yay, a game! Good luck!', "I'm ready! Show me your move~"]), 'wink', 4);
  if (P.turn !== me) aiT = 0.8;
}
function layout() {
  var w = G.w, h = G.h, d = G.dpr, m = 10 * d;
  if (w >= h * 1.15) { g.size = Math.min(h - 2 * m, w * 0.6); g.bx = m; g.by = (h - g.size) / 2; g.px = g.bx + g.size + m * 1.5; g.pw = w - g.px - m; g.land = true; g.py = m; g.ph = h - 2 * m; }
  else { g.size = Math.min(w - 2 * m, h - 220 * d); g.bx = (w - g.size) / 2; g.by = 76 * d; g.px = m; g.pw = w - 2 * m; g.land = false; g.py = g.by + g.size + 12 * d; g.ph = h - g.py - m; }
  g.flip = me === -1;
}
function status() { // состояние после хода: мат/пат/ничья
  if (Chess.isMate(P)) return P.turn === me ? 'lose' : 'win';
  if (Chess.isStalemate(P)) return 'draw_stale';
  if (Chess.insufficient(P)) return 'draw_mat';
  if (P.half >= 100) return 'draw_50';
  var k = Chess.key(P), n = 0; for (var i = 0; i < rep.length; i++) if (rep[i] === k) n++;
  if (n >= 3) return 'draw_rep';
  return null;
}
function afterMove() {
  var m = P.hist[P.hist.length - 1].m;
  st.last = [m & 63, (m >> 6) & 63]; st.sel = -1; st.moves = [];
  st.check = Chess.inCheck(P, P.turn) ? Chess.kingSq(P, P.turn) : -1;
  rep.push(Chess.key(P));
  var s = status();
  if (s) { endGame(s); return true; }
  if (st.check >= 0 && P.turn === me) say('Шах! Осторожнее~', 'Check! Careful~', 'wink', 3);
  return false;
}
function endGame(s) {
  over = s; var L = level;
  if (s === 'win') { stats.w[L]++; try { G.save('chess_win', String(L)); } catch (e) {} say('Ой, ты выиграл(а)! Грустно… но ты молодец!', 'Aw, you won! So sad… but well played!', 'sad', 99); }
  else if (s === 'lose') { stats.l[L]++; say('Ура, я выиграла! Мат! Сыграем ещё?', 'Yay, I won! Checkmate! Again?', 'happy', 99); }
  else { stats.d[L]++; say('Ничья! Хорошая партия!', "It's a draw! Nice game!", 'normal', 99); }
  saveStats();
}
function update(dt) {
  if (bubT < 90) { bubT -= dt; if (bubT <= 0) { bub = ''; mood = 'normal'; } }
  if (scr !== 'play' || over) return;
  if (P.turn !== me) {
    aiT -= dt;
    if (aiT <= 0) {
      var opt = level === 0 ? { depth: 2, ms: 300, blunder: 0.4 } : level === 1 ? { depth: 3, ms: 500 } : { depth: 6, ms: 1200 };
      var mv = Chess.think(P, opt);
      if (mv === null) return;
      Chess.make(P, mv); afterMove();
    }
  }
}
// ----- рисование -----
function bubble(c, x, y, w, h) {
  var d = G.dpr;
  c.fillStyle = '#FFFFFF'; c.strokeStyle = '#FF8FC7'; c.lineWidth = 2.5 * d; cRR(c, x, y, w, h, 14 * d); c.fill(); c.stroke();
  c.fillStyle = '#4B2E83';
  var fs = 15 * d; c.font = 'bold ' + fs + 'px sans-serif';
  var words = bub.split(' '), lines = [], line = '';
  for (var i = 0; i < words.length; i++) { var t = line ? line + ' ' + words[i] : words[i]; if (c.measureText(t).width > w - 20 * d && line) { lines.push(line); line = words[i]; } else line = t; }
  lines.push(line);
  while (lines.length * fs * 1.25 > h - 8 * d && fs > 9 * d) { fs -= d; c.font = 'bold ' + fs + 'px sans-serif'; }
  var y0 = y + h / 2 - (lines.length - 1) * fs * 0.625;
  c.textBaseline = 'middle'; for (i = 0; i < lines.length; i++) c.fillText(lines[i], x + 10 * d, y0 + i * fs * 1.25); c.textBaseline = 'alphabetic';
}
function draw(c) {
  layout(); var d = G.dpr;
  c.fillStyle = '#FFE6F3'; c.fillRect(0, 0, G.w, G.h);
  btns = {};
  if (scr === 'menu') return drawMenu(c);
  // Юта-тян и реплика
  var ys = g.land ? 90 * d : 60 * d, yx = g.land ? g.px : g.bx, yy = g.land ? g.py : 8 * d;
  c.drawImage(yutaImg(over === 'lose' ? 'happy' : over === 'win' ? 'sad' : mood, 128), yx, yy, ys, ys);
  var bw = g.land ? g.pw - ys - 8 * d : g.size - ys - 8 * d, bh = g.land ? ys : 60 * d;
  if (bub) bubble(c, yx + ys + 8 * d, yy, bw, bh);
  c.fillStyle = '#4B2E83'; c.font = 'bold ' + 12 * d + 'px sans-serif';
  var lvName = [tx('Лёгкий', 'Easy'), tx('Средний', 'Normal'), tx('Сложный', 'Hard')][level];
  drawBoard(c, P, g, st);
  if (st.promo) drawPromo(c, st, me === 1, g);
  if (P.turn !== me && !over) { c.fillStyle = 'rgba(75,46,131,0.8)'; c.font = 'bold ' + 14 * d + 'px sans-serif'; c.fillText(tx('Юта-тян думает…', 'Yuta-chan is thinking…') + ' (' + lvName + ')', g.bx, g.by + g.size + 22 * d - (g.land ? 0 : 6 * d)); }
  else if (!over) { c.fillStyle = 'rgba(75,46,131,0.8)'; c.font = 'bold ' + 14 * d + 'px sans-serif'; c.fillText(tx('Твой ход', 'Your move') + ' (' + lvName + ')', g.bx, g.by + g.size + 22 * d - (g.land ? 0 : 6 * d)); }
  // кнопки
  var bwid = g.land ? g.pw : (g.size - 8 * d) / 2, bhei = 44 * d, bx0 = g.land ? g.px : g.bx, by0 = g.land ? g.py + g.ph - bhei * 2 - 8 * d : g.py + 20 * d;
  if (g.land) { btns.undo = { x: bx0, y: by0, w: bwid, h: bhei }; btns.menu = { x: bx0, y: by0 + bhei + 8 * d, w: bwid, h: bhei }; }
  else { btns.undo = { x: bx0, y: by0, w: bwid, h: bhei }; btns.menu = { x: bx0 + bwid + 8 * d, y: by0, w: bwid, h: bhei }; }
  drawBtn(c, btns.undo, tx('Отмена хода', 'Undo'), P.hist.length >= 2 && !over && P.turn === me);
  drawBtn(c, btns.menu, over ? tx('Новая партия', 'New game') : tx('Сдаться', 'Resign'));
  if (over) {
    var t = over === 'win' ? tx('Победа!', 'You win!') : over === 'lose' ? tx('Юта-тян победила', 'Yuta-chan wins') : tx('Ничья', 'Draw');
    var ow = g.size * 0.8, oh = 60 * d, ox = g.bx + (g.size - ow) / 2, oy = g.by + g.size / 2 - oh / 2;
    c.fillStyle = 'rgba(255,255,255,0.93)'; c.strokeStyle = '#4B2E83'; c.lineWidth = 3 * d; cRR(c, ox, oy, ow, oh, 16 * d); c.fill(); c.stroke();
    c.fillStyle = '#4B2E83'; var fs = fitText(c, t, ow - 20 * d, 26 * d, true); c.font = 'bold ' + fs + 'px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText(t, ox + ow / 2, oy + oh / 2); c.textAlign = 'left'; c.textBaseline = 'alphabetic';
  }
}
function drawMenu(c) {
  var d = G.dpr, w = G.w, h = G.h, U = Math.min(h / 12.5, w / 7), cx = w / 2, y = U * 0.2;
  c.fillStyle = '#4B2E83'; c.textAlign = 'center'; c.textBaseline = 'middle';
  var t = tx('Шахматы с Юта-тян', 'Chess with Yuta-chan'); c.font = 'bold ' + fitText(c, t, w - 20 * d, U * 0.7, true) + 'px sans-serif'; c.fillText(t, cx, y + U * 0.5); y += U * 1.1;
  c.drawImage(yutaImg(mood, 128), cx - U * 0.9, y, U * 1.8, U * 1.8); y += U * 1.9;
  var s = tx('Выбери сложность', 'Choose difficulty'); c.font = 'bold ' + U * 0.38 + 'px sans-serif'; c.fillStyle = '#4B2E83'; c.fillText(s, cx, y + U * 0.25); y += U * 0.6;
  var names = [tx('Лёгкий', 'Easy'), tx('Средний', 'Normal'), tx('Сложный', 'Hard')], bw = (w - 40 * d) / 3, i;
  for (i = 0; i < 3; i++) { var r = { x: 10 * d + i * (bw + 10 * d), y: y, w: bw, h: U * 0.9 }; btns['lv' + i] = r; drawBtn(c, r, names[i], i === level ? true : (i === level ? true : null)); if (i !== level) dim(c, r); }
  y += U * 1.1;
  s = tx('Твой цвет', 'Your colour'); c.font = 'bold ' + U * 0.38 + 'px sans-serif'; c.fillStyle = '#4B2E83'; c.fillText(s, cx, y + U * 0.25); y += U * 0.6;
  names = [tx('Белые', 'White'), tx('Чёрные', 'Black'), tx('Случайно', 'Random')];
  for (i = 0; i < 3; i++) { r = { x: 10 * d + i * (bw + 10 * d), y: y, w: bw, h: U * 0.9 }; btns['co' + i] = r; drawBtn(c, r, names[i], true); if (i !== colorSel) dim(c, r); }
  y += U * 1.2;
  r = { x: cx - (w * 0.42), y: y, w: w * 0.84, h: U * 1.0 }; btns.start = r; drawBtn(c, r, tx('Играть', 'Play'), true); y += U * 1.2;
  c.fillStyle = 'rgba(75,46,131,0.8)'; c.font = U * 0.32 + 'px sans-serif';
  var L = level; c.fillText(tx('Побед: ', 'Wins: ') + stats.w[L] + '   ' + tx('Поражений: ', 'Losses: ') + stats.l[L] + '   ' + tx('Ничьих: ', 'Draws: ') + stats.d[L], cx, y + U * 0.2);
  c.textAlign = 'left'; c.textBaseline = 'alphabetic';
}
function dim(c, r) { c.fillStyle = 'rgba(255,230,243,0.55)'; cRR(c, r.x, r.y, r.w, r.h, r.h / 2); c.fill(); }
function onTap(x, y) {
  if (scr === 'menu') {
    for (var i = 0; i < 3; i++) { if (btns['lv' + i] && inRect(btns['lv' + i], x, y)) { level = i; saveStats(); } if (btns['co' + i] && inRect(btns['co' + i], x, y)) colorSel = i; }
    if (btns.start && inRect(btns.start, x, y)) newGame();
    return;
  }
  if (btns.undo && inRect(btns.undo, x, y)) {
    if (!over && P.turn === me && P.hist.length >= 2) {
      Chess.undo(P); Chess.undo(P); rep.pop(); rep.pop();
      var h = P.hist.length ? P.hist[P.hist.length - 1].m : null;
      st = { sel: -1, moves: [], last: h === null ? null : [h & 63, (h >> 6) & 63], check: -1, promo: null };
      say('Хорошо, возьми ход назад~', 'Okay, take it back~', 'wink', 3);
    }
    return;
  }
  if (btns.menu && inRect(btns.menu, x, y)) {
    if (!over) { stats.l[level]++; saveStats(); }
    scr = 'menu'; mood = 'normal'; bub = ''; bubT = 99; over = null; return;
  }
  if (over || P.turn !== me) return;
  var mv = boardTap(st, P, x, y, g);
  if (mv !== null) { Chess.make(P, mv); if (!afterMove()) aiT = 0.5; }
}
