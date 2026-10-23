require('../living/warp.js');
const K = globalThis.KavanaWarp, G = K.G, rig = require('../rig/cats-rig.json');
const cases = {
  blink: { k: 0.94 }, tiltL: { th: -0.24 }, tiltR: { th: 0.24 }, look: { hx: 16, hy: 10, th: 0.1 },
  ears: { ears: [0.25, -0.25] }, tail: { tail: 0.22 }, tailNeg: { tail: -0.22 }, squash: { S: -0.25 }, stretch: { S: 0.2 },
  all: { k: 0.94, th: 0.24, hx: 16, hy: 10, ears: [0.25, -0.25], tail: 0.22, S: -0.2, bv: 0.025, bx: 0.03 }, real: { k: 0.94, th: 0.2, hx: 14, hy: 8, ears: [0.15, -0.15], tail: 0.18, S: -0.2, bv: 0.015, bx: 0.025 }, headEars: { th: 0.24, hx: 16, hy: 10, ears: [0.25, -0.25] }, ears15: { ears: [0.15, -0.15] }, look14: { hx: 14, hy: 8 }, tailOnly: { tail: 0.18 }, tailSquash: { tail: 0.22, S: -0.2 },
};
const base = { k: 0, ears: [0, 0], tail: 0, th: 0, hx: 0, hy: 0, bv: 0, bx: 0, S: 0, nap: false, lean: 0, jx: 0, hop: 0 };
const out = new Float32Array((G + 1) * (G + 1) * 2);
const fs = require('fs');
// cells that hold part of the cat (any pixel with alpha > 40)
function solid(key) {
  const px = fs.readFileSync(__dirname + '/../rig/raw/' + key + '.rgba'), c = new Uint8Array(G * G);
  for (let y = 0; y < 512; y++) for (let x = 0; x < 512; x++) if (px[(y * 512 + x) * 4 + 3] > 40) c[Math.min(G - 1, Math.floor(y / 512 * G)) * G + Math.min(G - 1, Math.floor(x / 512 * G))] = 1;
  return c;
}
let bad = 0;
for (const [key, r] of Object.entries(rig)) {
  const W = K.weights(key, r), res = [], S = solid(key);
  for (const [name, c] of Object.entries(cases)) {
    K.deform(out, r, W, { ...base, ...c, nap: key.includes('nap') });
    let flips = 0, minRatio = 9;
    for (let y = 0; y < G; y++) for (let x = 0; x < G; x++) {
      const a = y * (G + 1) + x, b = a + 1, d = a + G + 1;
      const area = (out[b*2]-out[a*2])*(out[d*2+1]-out[a*2+1]) - (out[d*2]-out[a*2])*(out[b*2+1]-out[a*2+1]);
      // rest area in clip space: cell is (512/G)/608*2 wide; y flips sign
      const rest = -(((512 / G) / 608 * 2) ** 2);
      if (!S[y * G + x]) continue;
      const ratio = area / rest; if (ratio <= 0) { flips++; if (process.env.WHERE && key === process.env.WHERE && name === (process.env.CASE || 'tiltR')) console.log('  flip at cell', x, y); } minRatio = Math.min(minRatio, ratio);
    }
    if (flips || minRatio < 0.08) res.push(`${name}: ${flips} flips, min ${minRatio.toFixed(2)}`);
  }
  if (res.length) { bad++; console.log(key.padEnd(18), res.join(' | ')); }
}
console.log(bad ? `${bad} pictures with squeezed cells` : 'no folds in any picture');
