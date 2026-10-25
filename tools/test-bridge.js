// Runs the app's JavaScript the way the phone's sandbox does (no require, no module, no Web Crypto)
// and walks a block through: start, a check-in, an answer, the end, the outcome, a kitten that came
// home, treats, a cat met, and one that wandered off; then the Forest round: Cancel, the stopwatch,
// pauses that end by themselves, extra time, notes, tags, the shop, missions and rewards.   node tools/test-bridge.js
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const dir = path.join(__dirname, '../android/app/src/main/assets/js');
const ctx = vm.createContext({ console });
for (const f of ['lines.js', 'engine.js', 'cats.js', 'news.js', 'bridge.js']) vm.runInContext(fs.readFileSync(path.join(dir, f), 'utf8'), ctx, { filename: f });
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
r = run({ op: 'edit', id: 'nope', patch: { note: 'x' } }, t + 43 * MIN);
ok('bad input comes back as the engine\'s error', () => assert.equal(r.error, 'edit'));

// ---------- the Forest round ----------
t += 86400000; // a new day
r = run({ op: 'start', intention: 'Oops', minutes: 25, tag: 'Work' }, t);
r = run({ op: 'cancel' }, t + 8000);
ok('Cancel in the first 10 seconds: no block, no history, nothing earned', () => {
  assert.equal(r.error, null);
  assert.equal(r.state.view.active, null);
  assert.equal(r.state.view.awaiting, null);
  assert.equal(r.state.history.length, 2);
});
r = run({ op: 'start', intention: 'Too late', minutes: 25 }, t + MIN);
r = run({ op: 'cancel' }, t + MIN + 11000);
ok('after 10 seconds Cancel is refused', () => assert.equal(r.error, 'cancel'));
r = run({ op: 'stop' }, t + 2 * MIN); r = run({ op: 'outcome', id: r.state.view.awaiting.id, outcome: null }, t + 2 * MIN);

r = run({ op: 'start', intention: 'Open ended', mode: 'stopwatch', tag: 'Study' }, t + 10 * MIN);
ok('a stopwatch block: three hours at most, the mode stays the default', () => {
  const a = r.state.view.active;
  assert.equal(a.mode, 'stopwatch');
  assert.equal(a.minutes, 180);
  assert.equal(a.tag, 'Study');
  assert.equal(r.state.view.settings.mode, 'stopwatch');
  assert.equal(a.cancelUntil, t + 10 * MIN + 10000);
});
r = run({ op: 'pause' }, t + 15 * MIN);
ok('a pause says when it ends by itself', () => {
  assert.equal(r.state.view.active.paused, t + 15 * MIN);
  assert.equal(r.state.view.active.pauseEnds, t + 20 * MIN);
  assert.equal(r.state.canPause, true);
});
r = run({ op: 'tick' }, t + 30 * MIN);
ok('after 5 minutes the block goes on by itself', () => {
  assert.equal(r.state.view.active.paused, null);
  assert.equal(r.state.view.active.pauses, 1);
});
r = run({ op: 'stop' }, t + 50 * MIN);
const sw = r.state.view.awaiting;
ok('stopping a stopwatch block after 10 minutes brings the kitten home', () => {
  assert.equal(sw.kitten, 'home');
  assert.equal(sw.stopped, false);
  assert.equal(sw.treats, 35); // 40 minutes less the 5-minute pause
});
r = run({ op: 'edit', id: sw.id, patch: { note: 'Got the outline done', tag: 'Writing' } }, t + 50 * MIN);
r = run({ op: 'outcome', id: sw.id, outcome: 'done' }, t + 50 * MIN);
ok('a note and a new tag on the block, kept in history', () => {
  assert.equal(r.state.history[0].note, 'Got the outline done');
  assert.equal(r.state.history[0].tag, 'Writing');
  assert.equal(r.state.history[0].mode, 'stopwatch');
});

r = run({ op: 'start', intention: 'Short one', mode: 'stopwatch' }, t + 60 * MIN);
r = run({ op: 'stop' }, t + 65 * MIN);
ok('a stopwatch block under 10 minutes: the kitten wanders off (too short)', () => {
  assert.equal(r.state.view.awaiting.kitten, 'gone');
  assert.equal(r.state.view.awaiting.goneWhy, 'short');
});
r = run({ op: 'outcome', id: r.state.view.awaiting.id, outcome: null }, t + 65 * MIN);

r = run({ op: 'settings', patch: { pause: 'off', overtime: true, mode: 'timer' } }, t + 66 * MIN);
r = run({ op: 'start', intention: 'Keep going', minutes: 10 }, t + 70 * MIN);
r = run({ op: 'pause' }, t + 71 * MIN);
ok('pausing turned off: Pause does nothing', () => {
  assert.equal(r.state.canPause, false);
  assert.equal(r.state.view.active.paused, null);
});
r = run({ op: 'tick' }, t + 80 * MIN + 1000);
ok('extra time: at the end the kitten comes home and the block keeps counting', () => {
  const a = r.state.view.active;
  assert.equal(a.over, t + 80 * MIN);
  assert.equal(a.kitten, 'home');
});
r = run({ op: 'stop' }, t + 95 * MIN);
ok('stopping in extra time is not stopping early', () => {
  assert.equal(r.state.view.awaiting.stopped, false);
  assert.equal(r.state.view.awaiting.kitten, 'home');
});
r = run({ op: 'outcome', id: r.state.view.awaiting.id, outcome: 'done' }, t + 95 * MIN);

ok('missions, achievements, the starter and monthly challenges are in the state', () => {
  assert.equal(r.state.missions.day.length, 3);
  assert.equal(r.state.achievements.length > 20, true);
  assert.equal(r.state.starter.steps.length, 7);
  assert.equal(r.state.monthly.at(-1).need, 20);
});
const first = r.state.achievements.find((a) => a.id === 'first');
ok('First Kitten is done and waiting to be collected', () => assert.equal(first.done && !first.claimed, true));
const before = r.state.treats.left;
r = run({ op: 'claim', id: 'first' }, t + 96 * MIN);
ok('collecting it adds its treats, once', () => {
  assert.equal(r.result.treats, 10);
  assert.equal(r.state.treats.left, before + 10);
  assert.equal(r.state.achievements.find((a) => a.id === 'first').claimed, true);
});
r = run({ op: 'claim', id: 'first' }, t + 96 * MIN);
ok('collecting twice is refused', () => assert.equal(r.error, 'claim'));

const box = r.state.treats.left;
r = run({ op: 'buy', id: 'box' }, t + 97 * MIN);
ok('buying a box: it is yours and the treats go down by its price', () => {
  assert.equal(r.error, null);
  assert.deepEqual(r.state.shop.owned, ['box']);
  assert.equal(r.state.treats.left, box - 20);
});
r = run({ op: 'buy', id: 'flurry' }, t + 97 * MIN);
ok('Flurry is only for sale in winter', () => assert.equal(r.error, 'season'));
r = run({ op: 'buy', id: 'counter' }, t + 97 * MIN);
ok('buying without enough treats is refused', () => assert.equal(r.error, 'treats'));
ok('the shop knows the café things, the themes and the shelter cats', () => {
  assert.equal(r.state.shop.items.find((x) => x.id === 'window').perch.length, 2);
  assert.equal(r.state.shop.themes.length, 6);
  assert.equal(r.state.shop.shelter.length, 4);
  assert.equal(r.state.shop.prices.shelter1, 1000);
});
ok('cats to meet say how far along you are', () => {
  const mochi = r.state.cats.find((c) => c.id === 'mochi');
  assert.equal(mochi.need, 60);
  assert.equal(mochi.unit, 'min');
  assert.equal(mochi.met, true); // 25 + 35 + 15 minutes by now
});
ok('the news that has come out by now', () => assert.equal(r.state.news.some((x) => x.id === 'forest-2026-10'), true));

KB('KB.load(null)');
let tt = new Date(2026, 9, 22, 9, 0, 0).getTime();
r = run({ op: 'tick' }, tt);
ok('a fresh doc tells nothing on its first look', () => assert.deepEqual(r.done, []));
r = run({ op: 'start', intention: 'One', minutes: 25 }, tt);
r = run({ op: 'tick' }, tt + 25 * MIN + 1000);
ok('the first kitten home: the starter step and First Kitten are told once', () => {
  const ids = r.done.filter((d) => d.kind === 'reward').map((d) => d.id);
  assert.equal(ids.includes('first'), true);
  assert.equal(ids.includes('starter-first'), true);
});
r = run({ op: 'outcome', id: r.state.view.awaiting.id, outcome: 'done' }, tt + 26 * MIN);
ok('and not again', () => assert.equal(r.done.filter((d) => d.kind === 'reward').length, 0));

// A look back beyond the newest 400 blocks: the Café asks for a period.
r = run({ op: 'period', from: tt, to: tt + 86400000 }, tt + 27 * MIN);
ok('a period of history on request', () => assert.deepEqual(r.result.map((b) => b.intention), ['One']));

// Leaving during a pause counts from when the pause ends (the app records the leave either way).
r = run({ op: 'start', intention: 'Paused away', minutes: 25 }, tt + 30 * MIN);
r = run({ op: 'pause' }, tt + 35 * MIN);
r = run({ op: 'away', at: tt + 36 * MIN, grace: 15000 }, tt + 38 * MIN);
ok('back during the pause: the kitten is still there', () => { assert.equal(r.result, null); assert.equal(r.state.view.active.kitten, 'growing'); });
r = run({ op: 'away', at: tt + 36 * MIN, grace: 15000 }, tt + 45 * MIN);
ok('still away after the pause ended: it wandered off, 15 s after the pause', () => {
  assert.equal(r.result.cat, 'miso');
  assert.equal(r.state.view.active.kitten, 'gone');
});

// Extra time with a kitten that already wandered off: the end ping doesn't say it came home (kavana e7b7e80).
r = run({ op: 'stop' }, tt + 46 * MIN); r = run({ op: 'outcome', id: r.state.view.awaiting.id, outcome: null }, tt + 46 * MIN);
r = run({ op: 'settings', patch: { overtime: true } }, tt + 47 * MIN);
r = run({ op: 'start', intention: 'Long one', minutes: 25 }, tt + 50 * MIN);
r = run({ op: 'away', at: tt + 52 * MIN, grace: 15000 }, tt + 55 * MIN);
ok('the end ping after the kitten left says Time\'s up, not came home', () => {
  const end = r.state.pings.find((p) => p.kind === 'end');
  assert.equal(r.state.view.active.kitten, 'gone');
  assert.equal(end.title, "🐱 Time's up");
});

// Past 2000 blocks the engine folds the oldest into doc.past (kavana 338f6f3). The app hands the engine
// the whole doc and saves the whole doc; its 400-block window is only what the screens draw.
const E2 = vm.runInContext('Kavana', ctx);
const big = E2.empty();
let at = new Date(2025, 0, 1, 9, 0, 0).getTime();
for (let i = 0; i < 2010; i++) {
  E2.start(big, { intention: `B${i}`, minutes: 2, cat: 'miso' }, at);
  E2.tick(big, at + 2 * MIN + 1000);
  const w = E2.awaiting(big);
  E2.outcome(big, w.id, 'done', at + 3 * MIN);
  at += 6 * 3600000; // four a day
}
const earned = E2.treats(big).earned;
KB(`KB.load(${JSON.stringify(JSON.stringify(big))})`);
r = run({ op: 'tick' }, at);
const saved = JSON.parse(KB('KB.dump()'));
ok('a doc past 2000 blocks: the oldest are folded, not dropped, and their treats stay', () => {
  assert.equal(saved.blocks.length <= 2000, true);
  assert.equal(Boolean(saved.past && saved.past.to), true);
  assert.equal(r.state.treats.earned, earned);
});
ok('the screens get the newest 400; a period asks the engine for more', () => {
  assert.equal(r.state.history.length, 400);
  const p = run({ op: 'period', from: 0, to: at }, at);
  assert.equal(p.result.length, saved.blocks.length);
});

// The title is optional, like Forest's (kavana e05217d): no title is fine, and the pings say something else.
KB(`KB.load(${JSON.stringify(JSON.stringify(E2.empty()))})`);
at = new Date(2025, 5, 1, 9, 0, 0).getTime();
r = run({ op: 'start', intention: '  ', minutes: 25, tag: 'Work', style: 'ask' }, at);
ok('a block without a title starts', () => {
  assert.equal(r.error, null);
  assert.equal(r.state.view.active.intention, '');
  assert.equal(r.state.view.recent.length, 0);
  assert.equal(r.state.pings.some((p) => p.kind === 'checkin' && /Still focusing\?/.test(p.title)), true);
});

console.log(`\n${pass} passed`);
