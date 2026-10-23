// Finds the eyes, head and ground line in each painted cat (512x512 RGBA raw files).
// usage: node eyes.js <rawdir> <out.json> [markdir]
const fs = require('fs');
const path = require('path');
const [rawDir, outFile, markDir] = process.argv.slice(2);
const N = 512;

function sat(mask) {
  const s = new Int32Array((N + 1) * (N + 1));
  for (let y = 0; y < N; y++) {
    let row = 0;
    for (let x = 0; x < N; x++) { row += mask[y * N + x]; s[(y + 1) * (N + 1) + x + 1] = s[y * (N + 1) + x + 1] + row; }
  }
  return (x0, y0, x1, y1) => {
    x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(N, x1); y1 = Math.min(N, y1);
    return s[y1 * (N + 1) + x1] - s[y0 * (N + 1) + x1] - s[y1 * (N + 1) + x0] + s[y0 * (N + 1) + x0];
  };
}

function erode(mask, r) {
  const q = sat(mask), out = new Uint8Array(N * N), full = (2 * r + 1) ** 2;
  for (let y = r; y < N - r; y++) for (let x = r; x < N - r; x++) out[y * N + x] = q(x - r, y - r, x + r + 1, y + r + 1) === full ? 1 : 0;
  return out;
}

function dilate(mask, r) {
  const q = sat(mask), out = new Uint8Array(N * N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) out[y * N + x] = q(x - r, y - r, x + r + 1, y + r + 1) > 0 ? 1 : 0;
  return out;
}

function components(mask) {
  const lab = new Int32Array(N * N), out = [];
  for (let i = 0; i < N * N; i++) {
    if (!mask[i] || lab[i]) continue;
    const id = out.length + 1, st = [i];
    lab[i] = id;
    const c = { n: 0, sx: 0, sy: 0, x0: N, y0: N, x1: 0, y1: 0 };
    while (st.length) {
      const j = st.pop(), x = j % N, y = (j / N) | 0;
      c.n++; c.sx += x; c.sy += y;
      c.x0 = Math.min(c.x0, x); c.x1 = Math.max(c.x1, x); c.y0 = Math.min(c.y0, y); c.y1 = Math.max(c.y1, y);
      for (const k of [j - 1, j + 1, j - N, j + N]) if (k >= 0 && k < N * N && mask[k] && !lab[k]) { lab[k] = id; st.push(k); }
    }
    c.cx = c.sx / c.n; c.cy = c.sy / c.n;
    out.push(c);
  }
  return out;
}

const lum = (p, i) => 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];

const OPT = {
  'pumpkin-1': { yMax: 0.5 },
  'biscuit-1': { color: (r, g, b) => b > 120 && b > r + 50 },
};
function analyse(px, id) {
  const o = OPT[id] || {};
  const alpha = new Uint8Array(N * N), dark = new Uint8Array(N * N);
  for (let i = 0; i < N * N; i++) {
    alpha[i] = px[i * 4 + 3] > 128 ? 1 : 0;
    dark[i] = alpha[i] && (o.color ? o.color(px[i * 4], px[i * 4 + 1], px[i * 4 + 2]) : lum(px, i * 4) < 70) ? 1 : 0;
  }
  // The body without whiskers and sparkles: erode, keep the biggest piece's box.
  const core = components(erode(alpha, 5)).sort((a, b) => b.n - a.n)[0];
  const box = { x0: core.x0 - 5, y0: core.y0 - 5, x1: core.x1 + 5, y1: core.y1 + 5 };
  const H = box.y1 - box.y0;
  const r = 9;
  const blobs = components(erode(dark, r)).map((c) => ({
    x: c.cx, y: c.cy, w: c.x1 - c.x0 + 1 + 2 * r, h: c.y1 - c.y0 + 1 + 2 * r, n: c.n,
  })).filter((b) => b.w >= 26 && b.w <= 120 && b.h >= 26 && b.h <= 130 && b.y > box.y0 + H * 0.15 && b.y < box.y0 + H * (o.yMax || 0.75));
  let best = null;
  for (let i = 0; i < blobs.length; i++) for (let j = 0; j < blobs.length; j++) {
    const a = blobs[i], b = blobs[j];
    if (a.x >= b.x) continue;
    const dx = b.x - a.x;
    if (dx < 60 || dx > 260) continue;
    const score = Math.abs(a.y - b.y) * 2 + Math.abs(a.w - b.w) + Math.abs(a.h - b.h) - Math.min(a.n, b.n) * 0.02;
    if (!best || score < best.score) best = { score, l: a, r: b };
  }
  const eyes = best && [best.l, best.r].map((e) => [Math.round(e.x), Math.round(e.y), Math.round(e.w / 2), Math.round(e.h / 2)]);
  // The tail: what's left of the cat after opening it with a wide brush, low down, the biggest piece.
  const R = 24, open = dilate(erode(alpha, R), R);
  const rest = new Uint8Array(N * N);
  for (let i = 0; i < N * N; i++) rest[i] = alpha[i] && !open[i] && ((i / N) | 0) > box.y0 + H * 0.35 ? 1 : 0;
  const pieces = components(erode(rest, 3)).filter((c) => c.n > 250).sort((a, b) => b.n - a.n);
  let tail = null;
  if (pieces.length && eyes) {
    const t = pieces[0], mid = (eyes[0][0] + eyes[1][0]) / 2;
    // tip: the piece's point farthest from the middle; base: the point nearest the opened body
    let tip = null, base = null, far = -1, near = 1e9;
    for (let y = t.y0; y <= t.y1; y++) for (let x = t.x0; x <= t.x1; x++) {
      const i = y * N + x; if (!rest[i]) continue;
      const d = Math.hypot(x - mid, y - box.y1);
      if (d > far && Math.abs(x - mid) > 40) { far = d; tip = [x, y]; }
      let m = 1e9; for (let dy = -12; dy <= 12; dy += 4) for (let dx = -12; dx <= 12; dx += 4) { const j = (y + dy) * N + x + dx; if (j >= 0 && j < N * N && open[j]) m = Math.min(m, Math.hypot(dx, dy)); }
      if (m < 13) { const dd = Math.hypot(x - mid, y - box.y1) ; if (dd < near) { near = dd; base = [x, y]; } }
    }
    if (tip && base && Math.hypot(tip[0] - base[0], tip[1] - base[1]) > 40) tail = [base[0], base[1], tip[0], tip[1], t.n];
  }
  // Ears: the highest point of the cat on each side of the eyes' middle.
  let ears = null;
  if (eyes) {
    const mid = (eyes[0][0] + eyes[1][0]) / 2, top = [null, null];
    for (let y = box.y0; y < eyes[0][1]; y++) for (let x = box.x0; x <= box.x1; x++) {
      if (!alpha[y * N + x]) continue; const s = x < mid ? 0 : 1; if (!top[s]) top[s] = [x, y];
    }
    ears = top;
  }
  return { box, eyes, tail, ears };
}

function mark(px, res) {
  const out = Buffer.from(px);
  const put = (x, y, c) => {
    x = Math.round(x); y = Math.round(y);
    if (x < 0 || y < 0 || x >= N || y >= N) return;
    const i = (y * N + x) * 4;
    out[i] = c[0]; out[i + 1] = c[1]; out[i + 2] = c[2]; out[i + 3] = 255;
  };
  const ell = (cx, cy, rx, ry, c) => { for (let a = 0; a < 6.283; a += 0.01) for (let t = 0; t < 3; t++) put(cx + (rx + t) * Math.cos(a), cy + (ry + t) * Math.sin(a), c); };
  if (res.eyes) for (const [x, y, rx, ry] of res.eyes) ell(x, y, rx, ry, [0, 220, 90]);
  if (res.tail) { const [bx, by, tx, ty] = res.tail; for (let k = 0; k <= 1; k += 0.005) for (let t = -2; t <= 2; t++) put(bx + (tx - bx) * k + t, by + (ty - by) * k, [0, 160, 255]); ell(tx, ty, 6, 6, [0, 160, 255]); ell(bx, by, 6, 6, [255, 255, 0]); }
  if (res.ears) for (const e of res.ears) if (e) ell(e[0], e[1] + 6, 6, 6, [255, 60, 60]);
  const b = res.box;
  for (let x = b.x0; x <= b.x1; x++) { put(x, b.y1, [255, 0, 255]); put(x, b.y1 - 1, [255, 0, 255]); }
  return out;
}

const res = {};
for (const f of fs.readdirSync(rawDir).filter((f) => f.endsWith('.rgba')).sort()) {
  const px = fs.readFileSync(path.join(rawDir, f));
  const id = f.replace('.rgba', '');
  const r = analyse(px, id);
  res[id] = r;
  if (markDir) fs.writeFileSync(path.join(markDir, f), mark(px, r));
  console.log(id.padEnd(20), r.eyes ? JSON.stringify(r.eyes) : 'NO EYES', 'tail', JSON.stringify(r.tail), 'ears', JSON.stringify(r.ears));
}
fs.writeFileSync(outFile, JSON.stringify(res, null, 1));
