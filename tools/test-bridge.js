// Runs the app's JavaScript the way the phone's sandbox does (no require, no module, no Web Crypto)
// and walks a block through: start, a check-in, an answer, the end, the outcome, a kitten that came
// home, treats, a cat met, and one that wandered off.   node tools/test-bridge.js
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const dir = path.join(__dirname, '../android/app/src/main/assets/js');
const ctx = vm.createContext({ console });
for (const f of ['lines.js', 'engine.js', 'cats.js', 'bridge.js']) vm.runInContext(fs.readFileSync(path.join(dir, f), 'utf8'), ctx, { filename: f });
const KB = (code) => vm.runInContext(code, ctx);
const run = (op, now) => JSON.parse(KB(`KB.run(${JSON.stringify(JSON.stringify(op))}, ${now})`));

let pass = 0;
const ok = (what, fn) => { fn(); pass += 1; console.log(`ok  ${what}`); };
const MIN = 60000;
let t = new Date(2026, 9, 21, 10, 0, 0).getTime();

KB('KB.load(null)');
let r = run({ op: 'tick' }, t);
ok('a new doc: nothing running, Miso is the companion', () => {
  assert.equal(r.error, null);
  assert.equal(r.state.view.active, null);
  assert.equal(r.state.companion, 'miso');
  assert.deepEqual(r.state.cats.filter((c) => c.met).map((c) => c.id), ['miso']);
});
ok('the first tick marks the cats already met as seen, without a celebration', () => {
  assert.deepEqual(r.meets, []);
  assert.equal(r.changed, true);
});

r = run({ op: 'start', intention: '  Write   the intro ', minutes: 25, style: 'ask' }, t);
const id = r.result.id;
ok('start: a running block with a growing kitten and its pings', () => {
  assert.equal(r.error, null);
  const a = r.state.view.active;
  assert.equal(a.intention, 'Write the intro');
  assert.equal(a.kitten, 'growing');
  assert.equal(a.cat, 'miso');
  const kinds = r.state.pings.map((p) => p.kind);
  assert.deepEqual(kinds, ['checkin', 'end']); // 15-minute check-ins in a 25-minute block: one, then the end
  assert.equal(r.state.pings[0].at, t + 15 * MIN);
  assert.match(r.state.pings[0].title, /^🐱 Boo! Still on: Write the intro\?$/);
});

r = run({ op: 'tick' }, t + 15 * MIN + 1000);
ok('the check-in comes due: pending in the view, gone from the pings', () => {
  assert.equal(r.state.view.active.pending, 1);
  assert.deepEqual(r.state.pings.map((p) => p.kind), ['end']);
});

r = run({ op: 'answer', id, n: 1, answer: 'drifted', why: 'phone' }, t + 15 * MIN + 5000);
ok('answer Drifted with a reason', () => {
  assert.equal(r.error, null);
  assert.equal(r.result.drifts, 1);
  assert.equal(r.state.view.active.pending, null);
});

r = run({ op: 'park', text: 'Reply to that email' }, t + 16 * MIN);
ok('park a thought: on the block and on the Later list', () => {
  assert.deepEqual(r.state.view.active.parked, ['Reply to that email']);
  assert.equal(r.state.view.later[0].text, 'Reply to that email');
});

r = run({ op: 'tick' }, t + 25 * MIN + 1000);
ok('the end: waiting for "How did it go?", the kitten came home with 25 treats', () => {
  assert.equal(r.state.view.active, null);
  assert.equal(r.state.view.awaiting.id, id);
  assert.equal(r.state.view.awaiting.kitten, 'home');
  assert.equal(r.state.view.awaiting.treats, 25);
});
ok('finishing a first block meets Pepper', () => assert.deepEqual(r.meets, ['pepper']));

r = run({ op: 'outcome', id, outcome: 'done' }, t + 26 * MIN);
ok('outcome Done: in history, stats and treats', () => {
  assert.equal(r.state.view.awaiting, null);
  assert.equal(r.state.history[0].outcome, 'done');
  assert.equal(r.state.stats.today, 25);
  assert.equal(r.state.treats.left >= 25, true);
  assert.equal(r.state.view.breakOffer.minutes, 5);
});

r = run({ op: 'settings', patch: { companion: 'pepper' } }, t + 27 * MIN);
r = run({ op: 'start', intention: 'Second', minutes: 25 }, t + 30 * MIN);
const id2 = r.result.id;
ok('the next block brings the new companion', () => assert.equal(r.state.view.active.cat, 'pepper'));

r = run({ op: 'away', at: t + 35 * MIN }, t + 40 * MIN);
ok('away: the kitten wanders off, the block goes on', () => {
  assert.equal(r.result.cat, 'pepper');
  assert.equal(r.state.view.active.kitten, 'gone');
  assert.equal(r.state.view.active.goneWhy, 'away');
});

r = run({ op: 'stop' }, t + 41 * MIN);
r = run({ op: 'outcome', id: id2, outcome: null }, t + 41 * MIN);
ok('a stopped block earns nothing', () => {
  assert.equal(r.state.history[0].id, id2);
  assert.equal(r.state.history[0].treats, 0);
});

const dump = KB('KB.dump()');
KB(`KB.load(${JSON.stringify(dump)})`);
r = run({ op: 'tick' }, t + 42 * MIN);
ok('the doc survives a dump and a load', () => assert.equal(r.state.history.length, 2));

r = run({ op: 'nope' }, t + 43 * MIN);
ok('an unknown op is an error, not a crash', () => assert.equal(r.error, 'op'));
r = run({ op: 'start', intention: '', minutes: 25 }, t + 43 * MIN);
ok('bad input comes back as the engine\'s error', () => assert.equal(r.error, 'intention'));

console.log(`\n${pass} passed`);
