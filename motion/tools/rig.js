// Turns the found eyes/ears/tails (auto.json) plus hand fixes into the rig every app reads (cats-rig.json).
// usage: node rig.js <auto.json> <rawdir> <out.json> [markdir]
const fs = require('fs');
const path = require('path');
const [autoFile, rawDir, outFile, markDir] = process.argv.slice(2);
const auto = JSON.parse(fs.readFileSync(autoFile, 'utf8'));
const N = 512;

// Tails by hand where the finder missed or stopped short: [base x, base y, tip x, tip y]. null = no tail to move.
const TAIL = {
  'miso-1': [401, 435, 457, 341], 'pepper-1': null, 'mochi-1': null, 'pickles-1': [112, 430, 62, 300],
  'earl-1': [415, 445, 470, 335], 'biscuit-1': [396, 445, 445, 300], 'boo-1': [440, 390, 480, 225],
  'luna-1': [110, 420, 48, 355], 'dumpling-1': null, 'maple-1': [430, 430, 478, 300], 'pumpkin-1': null,
  'miso-kitten-1': [405, 380, 445, 320], 'mochi-kitten-1': [105, 425, 55, 350], 'pickles-kitten-1': [395, 430, 440, 320],
  'earl-kitten-1': [400, 440, 450, 300], 'boo-kitten-1': [427, 410, 469, 307], 'luna-kitten-1': [95, 435, 48, 380],
  'cocoa-kitten-1': [410, 420, 465, 282], 'dumpling-kitten-1': null, 'maple-kitten-1': [395, 445, 440, 340],
  'ziggy-kitten-1': [400, 445, 412, 311], 'pumpkin-kitten-1': [400, 445, 447, 340],
};
Object.assign(TAIL, {
  'flurry-kitten-1': [380, 420, 452, 318], 'shelter1-1': [415, 430, 455, 325], 'shelter2-1': [395, 430, 445, 345],
});
// Eyes by hand: a wink has one open eye, so the finder misses it; `blink` lists the eyes that blink
// (both still place the head).
const EYES = { 'shelter1-1': { eyes: [[179, 222, 22, 24], [307, 222, 22, 24]], blink: [0] } };
// Eyes with a colored ring around a dark middle: the ring closes too.
const RING = { 'pepper-1': 1.4, 'pepper-kitten-1': 1.35, 'dumpling-1': 1.3, 'dumpling-kitten-1': 1.3, 'ziggy-1': 1.2, 'ziggy-kitten-1': 1.2, 'biscuit-1': 1.1, 'biscuit-kitten-1': 1.25 };
// No ears to flick (folded ears, a pumpkin hat).
const NO_EARS = new Set(['dumpling-1', 'dumpling-kitten-1', 'pumpkin-1', 'pumpkin-kitten-1']);

function ears(px, eyes, box) {
  const mid = (eyes[0][0] + eyes[1][0]) / 2, d = eyes[1][0] - eyes[0][0], out = [null, null];
  for (let y = box.y0; y < eyes[0][1]; y++) for (let x = box.x0; x <= box.x1; x++) {
    if (px[(y * N + x) * 4 + 3] < 128) continue;
    const s = x < mid - 0.35 * d ? 0 : x > mid + 0.35 * d ? 1 : -1;
    if (s >= 0 && !out[s]) out[s] = [x, y + 8];
  }
  return out;
}

const rig = {};
for (const [id, a] of Object.entries(auto)) {
  const px = fs.readFileSync(path.join(rawDir, id + '.rgba'));
  const r = { ground: a.box.y1, mid: Math.round((a.box.x0 + a.box.x1) / 2), top: a.box.y0, w: a.box.x1 - a.box.x0 };
  const found = EYES[id] ? EYES[id].eyes : a.eyes;
  if (found) {
    const k = RING[id] || 1;
    r.eyes = found.map(([x, y, rx, ry]) => [x, y, Math.round(rx * k), Math.round(ry * k)]);
    const [l, rr] = r.eyes, d = rr[0] - l[0];
    const hx = (l[0] + rr[0]) / 2, hy = (l[1] + rr[1]) / 2;
    // The head as an ellipse around the eyes, and the neck it turns on.
    r.head = [Math.round(hx), Math.round(hy + 0.05 * d), Math.round(1.25 * d), Math.round(1.0 * d)];
    r.neck = [Math.round(hx), Math.round(Math.min(hy + 0.95 * d, r.ground - 40))];
    if (!NO_EARS.has(id)) r.ears = ears(px, r.eyes, a.box);
    const t = id in TAIL ? TAIL[id] : a.tail && a.tail.slice(0, 4);
    if (t) r.tail = t;
    if (EYES[id] && EYES[id].blink) r.eyes = EYES[id].blink.map((i) => r.eyes[i]);
  }
  rig[id] = r;
}
fs.writeFileSync(outFile, JSON.stringify(rig));
console.log(Object.keys(rig).length, 'pictures,', Object.values(rig).filter((r) => r.tail).length, 'tails,', Object.values(rig).filter((r) => r.eyes).length, 'with eyes');

if (markDir) for (const [id, r] of Object.entries(rig)) {
  const out = Buffer.from(fs.readFileSync(path.join(rawDir, id + '.rgba')));
  const put = (x, y, c) => { x = Math.round(x); y = Math.round(y); if (x < 0 || y < 0 || x >= N || y >= N) return; const i = (y * N + x) * 4; out.set([...c, 255], i); };
  const ell = (cx, cy, rx, ry, c, w = 3) => { for (let a = 0; a < 6.283; a += 0.005) for (let t = 0; t < w; t++) put(cx + (rx + t) * Math.cos(a), cy + (ry + t) * Math.sin(a), c); };
  const line = (x0, y0, x1, y1, c) => { for (let k = 0; k <= 1; k += 0.003) for (let t = -2; t <= 2; t++) put(x0 + (x1 - x0) * k + t, y0 + (y1 - y0) * k, c); };
  if (r.eyes) for (const [x, y, rx, ry] of r.eyes) ell(x, y, rx, ry, [0, 220, 90]);
  if (r.head) ell(r.head[0], r.head[1], r.head[2], r.head[3], [255, 200, 0], 2);
  if (r.neck) ell(r.neck[0], r.neck[1], 7, 7, [255, 0, 255]);
  if (r.ears) for (const e of r.ears) if (e) ell(e[0], e[1], 7, 7, [255, 40, 40]);
  if (r.tail) { line(...r.tail, [0, 160, 255]); ell(r.tail[2], r.tail[3], 7, 7, [0, 160, 255]); }
  line(r.mid - 150, r.ground, r.mid + 150, r.ground, [255, 0, 255]);
  fs.writeFileSync(path.join(markDir, id + '.rgba'), out);
}
