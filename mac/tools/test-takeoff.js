// Tries the engine change the Mac app needs (docs/engine-takeoff.patch) against a copy of the synced engine,
// in a temporary folder: nothing in the app's own files is touched. The Mac app's "Take them off" button
// appears only when the engine has E.takeOff.   node tools/test-takeoff.js
'use strict';
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const cp = require('node:child_process');

const res = path.join(__dirname, '../Chataltul/Resources/js');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'takeoff-'));
fs.mkdirSync(path.join(tmp, 'web'));
fs.copyFileSync(path.join(res, 'engine.js'), path.join(tmp, 'web/engine.js'));
cp.execFileSync('patch', ['-s', '-p1', '-i', path.join(__dirname, '../docs/engine-takeoff.patch')], { cwd: tmp });

const ctx = vm.createContext({ console, crypto: { getRandomValues: (b) => { for (let i = 0; i < b.length; i++) b[i] = Math.floor(Math.random() * 256); return b; } } });
for (const f of ['lines.js', 'cats.js']) vm.runInContext(fs.readFileSync(path.join(res, f), 'utf8'), ctx);
vm.runInContext(fs.readFileSync(path.join(tmp, 'web/engine.js'), 'utf8'), ctx);
const E = ctx.Kavana;
const MIN = 60000;
let n = 0;
const ok = (what, fn) => { fn(); n += 1; console.log(`ok  ${what}`); };

const t = new Date(2026, 9, 26, 10).getTime();
const doc = E.empty();
const b = E.start(doc, { intention: 'Away test', minutes: 25, style: 'ask' }, t);
const end0 = b.end;
const plan0 = b.plan.map((p) => p.at);
E.tick(doc, t + 10 * MIN);
const r = E.takeOff(doc, t + 3 * MIN, t + 9 * MIN, t + 10 * MIN);
ok('the end moves later by the minutes taken off', () => assert.equal(r.end, end0 + 6 * MIN));
ok('the check-ins still to come move with it', () => assert.deepEqual(E.running(doc).plan.map((p) => p.at), plan0.map((a) => a + 6 * MIN)));
ok('the time on the block leaves them out', () => assert.equal(E.view(doc, t + 10 * MIN).active.total, 25 * MIN));
ok('the kitten is still growing', () => assert.equal(E.view(doc, t + 10 * MIN).active.kitten, 'growing'));
ok('the block ends at the new time, with the kitten home', () => {
  E.tick(doc, end0 + 6 * MIN + 1000);
  assert.equal(E.view(doc, end0 + 6 * MIN + 1000).awaiting.kitten, 'home');
});
const doc2 = E.empty();
E.start(doc2, { intention: 'x', minutes: 25 }, t);
ok('a gap that starts before the block is refused', () => assert.throws(() => E.takeOff(doc2, t - MIN, t + 2 * MIN, t + 3 * MIN)));
ok('a gap that ends in the future is refused', () => assert.throws(() => E.takeOff(doc2, t + 2 * MIN, t + 9 * MIN, t + 3 * MIN)));
ok('an empty gap is refused', () => assert.throws(() => E.takeOff(doc2, t + 2 * MIN, t + 2 * MIN, t + 3 * MIN)));
E.pause(doc2, t + 4 * MIN);
ok('a paused block is refused', () => assert.throws(() => E.takeOff(doc2, t + 1 * MIN, t + 3 * MIN, t + 4 * MIN + 1000)));
const doc3 = E.empty();
ok('no block running is refused', () => assert.throws(() => E.takeOff(doc3, t, t + MIN, t + 2 * MIN)));
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`${n} passed`);
