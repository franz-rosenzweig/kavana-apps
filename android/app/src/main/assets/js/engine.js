// Kavana's engine: everything a block does, as plain functions over one document (the "doc").
// The page keeps the doc in the browser. With an account it's also synced to the server, which
// loads this same file to merge copies and to work out when to send the pings.
(function (root) {
'use strict';
const E = {};
E.config = { MIN: 60000 }; // tests shrink the minute
const M = () => E.config.MIN;
const LINES = typeof module !== 'undefined' ? require('./lines.js') : root.KavanaLines;

const uid = () => {
  const b = new Uint8Array(9);
  (typeof crypto !== 'undefined' ? crypto : require('node:crypto').webcrypto).getRandomValues(b);
  return Array.from(b, (x) => 'abcdefghijkmnpqrstuvwxyz23456789'[x % 32]).join('');
};
const clean = (v, max) => String(v || '').replace(/\s+/g, ' ').trim().slice(0, max);
E.clean = clean;
E.STYLES = ['ask', 'nudge'];
E.INTERVALS = [0, 10, 15, 20, 30, 45, 60];
E.GOALS = [0, 30, 60, 90, 120, 180];
E.MORE = [5, 10, 15, 20];
E.BREAKS = [5, 10, 15];
E.OUTCOMES = ['done', 'partly', 'notreally'];
E.REASONS = ['phone', 'messages', 'email', 'web', 'tired', 'hungry', 'thoughts', 'people', 'other'];
E.SHARE = ['off', 'status', 'all']; // focus with friends: nothing / that I'm focusing / and on what
E.TAGS = ['Work', 'Study', 'Reading', 'Writing', 'Other'];
// The Cat Café shop, in treats (🐟, one per minute of a block whose kitten came home). Café things
// first, then the cats you can adopt. What they look like and where they go is in cats.js.
E.SHOP = {
  box: 20, cushion: 40, yarnbasket: 40, rug: 60, plant: 60, clock: 60, lamp: 80, window: 100,
  fishbowl: 120, armchair: 150, bookshelf: 180, cattree: 240, counter: 300,
  luna: 200, cocoa: 250, olive: 300, dumpling: 350, maple: 400, ziggy: 450,
  // Halloween (see E.EVENTS)
  candles: 40, jackolantern: 60, batgarland: 80, cauldron: 120, pumpkin: 300,
};
// Limited things: in the shop only from `from` to `to` ([month, day], both days in, local time), every
// year. What you got, you keep.
E.EVENTS = [
  { id: 'halloween', name: 'Halloween', from: [10, 25], to: [11, 1], items: ['pumpkin', 'jackolantern', 'candles', 'batgarland', 'cauldron'] },
];
const md = (t) => { const d = new Date(t); return (d.getMonth() + 1) * 100 + d.getDate(); };
E.season = (now) => E.EVENTS.find((e) => md(now) >= e.from[0] * 100 + e.from[1] && md(now) <= e.to[0] * 100 + e.to[1]) || null;
// The next event, if it starts within `days` days (for a "coming soon" in the shop).
E.soon = (now, days = 7) => {
  for (let i = 1; i <= days; i++) {
    const t = new Date(now); t.setDate(t.getDate() + i);
    const e = E.season(t.getTime());
    if (e && e !== E.season(now)) return { ...e, days: i };
  }
  return null;
};
E.limited = (id) => E.EVENTS.find((e) => e.items.includes(id)) || null;
E.forSale = (id, now) => { const e = E.limited(id); return !e || E.season(now) === e; };
const CAT_ID = /^[a-z]{2,12}$/;
const NAP_ID = /^[a-z0-9]{6,20}$/;

E.empty = () => ({
  v: 1,
  blocks: [],
  later: [],
  brk: null, brkU: 0,
  settings: {
    interval: 15, style: 'ask', goal: 0, daily: { on: false, time: '09:00', days: [0, 1, 2, 3, 4, 5, 6] },
    tz: '', share: 'off', friendPings: true, name: '', cat: true, companion: 'miso', catsSeen: null,
    tags: E.TAGS.slice(), tag: '', u: 0,
  },
  owned: [], // what you bought in the shop: { id, at, u }
  tally: {}, // small counters for things history doesn't keep (breaks, breathing, treats from cleared history)
  cleared: 0,
  u: 0,
});

// A doc from storage or the network: fill in anything missing, drop anything malformed.
E.normalize = (d) => {
  const doc = E.empty();
  if (!d || typeof d !== 'object') return doc;
  doc.blocks = Array.isArray(d.blocks) ? d.blocks.filter((b) => b && typeof b.id === 'string' && b.start && b.end).slice(-2000) : [];
  for (const b of doc.blocks) {
    b.checkins = Array.isArray(b.checkins) ? b.checkins : [];
    b.plan = Array.isArray(b.plan) ? b.plan : [];
    b.parked = Array.isArray(b.parked) ? b.parked : [];
  }
  doc.later = Array.isArray(d.later) ? d.later.filter((x) => x && typeof x.id === 'string').slice(0, 200) : [];
  doc.brk = d.brk && d.brk.end ? d.brk : null;
  doc.brkU = d.brkU || 0;
  doc.settings = { ...doc.settings, ...(d.settings || {}) };
  doc.settings.daily = { ...E.empty().settings.daily, ...(doc.settings.daily || {}) };
  if (!Array.isArray(doc.settings.tags)) doc.settings.tags = E.TAGS.slice();
  doc.owned = Array.isArray(d.owned) ? d.owned.filter((x) => x && E.SHOP[x.id] && x.at) : [];
  doc.cleared = d.cleared || 0;
  doc.tally = {};
  for (const [k, v] of Object.entries(d.tally && typeof d.tally === 'object' ? d.tally : {})) if (/^[a-z]{1,16}$/.test(k) && Number.isInteger(v) && v >= 0) doc.tally[k] = v;
  doc.u = d.u || 0;
  return doc;
};

// ---------- reading the doc ----------
const isOpen = (b) => !b.ended;
E.running = (doc) => doc.blocks.find(isOpen) || null;
E.awaiting = (doc) => doc.blocks.find((b) => b.ended && !b.closed) || null;
const drifts = (b) => b.checkins.filter((c) => c.answer === 'drifted').length;
const activeMs = (b, at) => at - b.start - (b.pausedTotal || 0); // time on the block, pauses left out
const touch = (doc, b, now) => { if (b) b.u = now; doc.u = now; };

// Every block invites a kitten (b.cat). It grows while you focus and comes home to the café when the
// block reaches its end (b.home). Stop early, or leave Kavana in the middle, and it wanders off (b.gone).
// Blocks from before the café have no b.cat: the finished ones count as home, with Miso.
const kitten = (b) => (b.home ? 'home' : b.gone ? 'gone' : !b.cat ? (b.ended ? (b.stopped ? 'gone' : 'home') : 'growing') : b.ended ? 'gone' : 'growing');
E.kitten = kitten;
const treatsOf = (b) => (kitten(b) !== 'home' ? 0 : b.cat ? b.treats || 0 : Math.max(1, Math.round(activeMs(b, b.ended) / M())));
const minutesOn = (b, at) => Math.max(1, Math.round(activeMs(b, at) / M()));
// Napping together (b.nap, the group's id; b.napN, how many friends were in it): if every kitten came
// home, each brings 5 more treats per friend. If anyone left, every kitten wandered off (E.napGone).
E.NAP_BONUS = 5;
const napBonus = (b) => (b.nap && b.napN && kitten(b) === 'home' ? E.NAP_BONUS * b.napN : 0);
// The block stops before its end: the kitten that was still growing wanders off.
function stopped(b, at, why) {
  if (b.home) b.treats = minutesOn(b, at); // came home already, then kept going: the extra minutes count
  else if (!b.gone && b.cat) { b.gone = at; b.goneWhy = why; }
}
// ---------- missions ----------
// Small goals that pay bonus treats: three a day (a different three every day, one of each kind) and
// one a week (weeks start on Sunday, like the café). Only blocks whose kitten came home count, by the
// day they started. What they pay is worked out from the blocks, like the treats themselves, so every
// device agrees and nothing more has to sync. A day or week in which you cleared your history is
// closed: what it had paid is banked, and it can't pay again.
const homeMin = (b) => (b.cat ? b.treats || 1 : Math.max(1, Math.round(activeMs(b, b.ended) / M()))); // minutes, also while a block that came home is extended
const hourOf = (t) => new Date(t).getHours();
const count = (f) => (bs) => bs.filter(f).length;
const MISSIONS = {
  two: { kind: 'a', text: 'Bring 2 kittens home', need: 2, pay: 10, have: (bs) => bs.length },
  three: { kind: 'a', text: 'Bring 3 kittens home', need: 3, pay: 15, have: (bs) => bs.length },
  hour: { kind: 'a', text: 'Focus for an hour', need: 60, pay: 15, unit: 'min', have: (bs) => bs.reduce((n, b) => n + homeMin(b), 0) },
  ninety: { kind: 'a', text: 'Focus for 90 minutes', need: 90, pay: 20, unit: 'min', have: (bs) => bs.reduce((n, b) => n + homeMin(b), 0) },
  long: { kind: 'b', text: 'Finish a 50-minute block', need: 1, pay: 15, have: count((b) => homeMin(b) >= 50) },
  early: { kind: 'b', text: 'Start a block before 9 am', need: 1, pay: 15, have: count((b) => hourOf(b.start) >= 4 && hourOf(b.start) < 9) },
  evening: { kind: 'b', text: 'Start a block after 6 pm', need: 1, pay: 10, have: count((b) => hourOf(b.start) >= 18) },
  done: { kind: 'b', text: 'Mark 2 blocks Done', need: 2, pay: 10, have: count((b) => b.outcome === 'done') },
  park: { kind: 'c', text: 'Park a thought in a block', need: 1, pay: 10, have: count((b) => b.parked.length > 0) },
  step: { kind: 'c', text: 'Start a block with a first step', need: 1, pay: 10, have: count((b) => Boolean(b.step)) },
  noon: { kind: 'c', text: 'Bring a kitten home before noon', need: 1, pay: 10, have: count((b) => hourOf(b.home || b.ended) < 12) },
  tags: { kind: 'c', text: 'Focus on 2 different tags', need: 2, pay: 10, have: (bs) => new Set(bs.map((b) => b.tag).filter(Boolean)).size },
  week5h: { kind: 'w', text: 'Focus for 5 hours this week', need: 300, pay: 50, unit: 'min', have: (bs) => bs.reduce((n, b) => n + homeMin(b), 0) },
  week4d: { kind: 'w', text: 'Bring kittens home on 4 days this week', need: 4, pay: 50, have: (bs) => new Set(bs.map((b) => dayOf(b.start))).size },
  week12: { kind: 'w', text: 'Bring 12 kittens home this week', need: 12, pay: 50, have: (bs) => bs.length },
};
E.MISSIONS = MISSIONS;
const dayOf = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
const weekOf = (t) => { const d = new Date(dayOf(t)); d.setDate(d.getDate() - d.getDay()); return d.getTime(); };
const ymd = (t) => { const d = new Date(t); return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`; };
const hash = (str) => [...str].reduce((h, c) => Math.imul(h ^ c.charCodeAt(0), 16777619) >>> 0, 2166136261);
const ofKind = (k) => Object.keys(MISSIONS).filter((id) => MISSIONS[id].kind === k);
// Which missions a day (or week) has: the same for everyone, from the date alone.
function missionsFor(from, week) {
  const h = hash(`${week ? 'w' : 'd'}${ymd(from)}`);
  if (week) { const w = ofKind('w'); return [w[h % w.length]]; }
  return ['a', 'b', 'c'].map((k, i) => { const ids = ofKind(k); return ids[(h >>> (i * 5)) % ids.length]; });
}
const homeBlocks = (doc) => doc.blocks.filter((b) => kitten(b) === 'home');
function progress(ids, list) {
  return ids.map((id) => {
    const m = MISSIONS[id];
    const have = m.have(list);
    return { id, text: m.text, need: m.need, unit: m.unit || null, pay: m.pay, have: Math.min(have, m.need), done: have >= m.need };
  });
}
// What missions have paid, over all the blocks.
function missionPay(doc) {
  const days = new Map(), weeks = new Map();
  for (const b of homeBlocks(doc)) {
    for (const [map, from] of [[days, dayOf(b.start)], [weeks, weekOf(b.start)]]) {
      if (from < doc.cleared) continue; // closed by a clear
      if (!map.has(from)) map.set(from, []);
      map.get(from).push(b);
    }
  }
  let n = 0;
  for (const [map, week] of [[days, false], [weeks, true]]) {
    for (const [from, list] of map) for (const p of progress(missionsFor(from, week), list)) if (p.done) n += p.pay;
  }
  return n;
}
// Today's and this week's missions, and how far along they are.
E.missions = (doc, now) => {
  const d = dayOf(now), w = weekOf(now);
  const home = homeBlocks(doc);
  const end = new Date(d); end.setDate(end.getDate() + 1);
  const wEnd = new Date(w); wEnd.setDate(wEnd.getDate() + 7);
  return {
    day: progress(missionsFor(d, false), home.filter((b) => dayOf(b.start) === d)),
    week: progress(missionsFor(w, true), home.filter((b) => weekOf(b.start) === w)),
    dayClosed: d < doc.cleared, weekClosed: w < doc.cleared,
    dayEnds: end.getTime(), weekEnds: wEnd.getTime(),
  };
};

// Treats: earned (by minutes, napping together and missions), spent, and what's left to spend.
const blockPay = (doc) => doc.blocks.reduce((n, b) => n + treatsOf(b) + napBonus(b), 0);
const earnedOf = (doc) => blockPay(doc) + missionPay(doc);
E.treats = (doc) => {
  const missions = missionPay(doc);
  const earned = (doc.tally.banked || 0) + blockPay(doc) + missions;
  const spent = doc.owned.reduce((n, x) => n + (E.SHOP[x.id] || 0), 0);
  return { earned, spent, left: Math.max(0, earned - spent), missions };
};
E.owns = (doc, id) => doc.owned.some((x) => x.id === id);

function lineFor(b, n) {
  const seed = [...b.id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  return LINES[(seed + n * 5) % LINES.length];
}

// The pings a block will get from `from` to its end. Ask: every interval. Nudge: every 60-140%
// of the interval, so it doesn't feel like a metronome. None in the last minute (the end ping covers it).
function planFrom(b, from) {
  const plan = [];
  if (!b.interval) return plan;
  let n = b.checkins.length ? b.checkins[b.checkins.length - 1].n : 0;
  let t = from;
  for (;;) {
    t += b.interval * M() * (b.style === 'nudge' ? 0.6 + 0.8 * Math.random() : 1);
    if (b.end - t < M()) break;
    n += 1;
    plan.push(b.style === 'nudge' ? { n, at: Math.round(t), kind: 'nudge', line: lineFor(b, n) } : { n, at: Math.round(t), kind: 'checkin' });
  }
  return plan;
}

// Move time forward: check-ins whose time came, blocks that ended, a pause nobody came back to.
// Returns true when the doc changed (so the caller saves it).
E.tick = (doc, now) => {
  let changed = false;
  for (const b of doc.blocks) {
    if (b.ended) {
      // Nobody answered "How did it go?" within 12 hours: file it without an outcome.
      if (!b.closed && now - b.ended > 12 * 60 * M()) { b.closed = true; touch(doc, b, now); changed = true; }
      continue;
    }
    if (b.paused) {
      if (now - b.paused > 120 * M()) {
        stopped(b, b.paused, 'stop');
        b.ended = b.paused; b.paused = null; b.stopped = true; b.plan = [];
        touch(doc, b, now); changed = true;
      }
      continue;
    }
    while (b.plan.length && b.plan[0].at <= now) {
      const p = b.plan.shift();
      b.checkins.push({ n: p.n, at: p.at, kind: p.kind, line: p.line, answer: null });
      touch(doc, b, now); changed = true;
    }
    if (now >= b.end) {
      b.ended = b.end; b.plan = [];
      if (b.cat && !b.gone) { b.home = b.home || b.end; b.treats = minutesOn(b, b.end); } // the kitten comes home
      touch(doc, b, now); changed = true;
    }
  }
  if (doc.brk && now >= doc.brk.end) { doc.brk = null; doc.brkU = now; doc.u = now; changed = true; }
  return changed;
};

function blockView(b) {
  const last = b.checkins[b.checkins.length - 1];
  const nudge = b.style === 'nudge';
  return {
    id: b.id, intention: b.intention, minutes: b.minutes, interval: b.interval, start: b.start, end: b.end,
    ended: b.ended || null, stopped: Boolean(b.stopped), drifts: drifts(b),
    pending: !nudge && last && last.answer == null ? last.n : null,
    next: !b.ended && b.plan.length ? b.plan[0].at : null,
    style: b.style || 'ask', line: nudge && last ? last.line : null,
    step: b.step || null, paused: b.paused || null, total: b.end - b.start - (b.pausedTotal || 0),
    parked: b.parked.map((x) => x.text), with: b.with || null,
    cat: b.cat || 'miso', tag: b.tag || null, kitten: kitten(b), goneWhy: b.goneWhy || null, goneBy: b.goneBy || null, treats: treatsOf(b),
    nap: b.nap || null, napN: b.napN || 0, bonus: napBonus(b),
  };
}

// The same shape the screens have always drawn from.
E.view = (doc, now) => {
  const run = E.running(doc);
  const wait = E.awaiting(doc);
  const last = doc.blocks.filter((b) => b.ended && b.closed).sort((a, b) => b.ended - a.ended)[0];
  const offer = !run && !wait && !doc.brk && last && now - last.ended < 10 * M() && activeMs(last, last.ended) >= 10 * M()
    ? { id: last.id, minutes: activeMs(last, last.ended) > 40 * M() ? 10 : 5 } : null;
  const seen = new Set();
  const recent = [];
  for (const b of [...doc.blocks].sort((a, b) => b.start - a.start)) {
    if (seen.has(b.intention.toLowerCase())) continue;
    seen.add(b.intention.toLowerCase());
    recent.push(b.intention);
    if (recent.length === 4) break;
  }
  return {
    now,
    settings: doc.settings,
    active: run ? blockView(run) : null,
    awaiting: !run && wait ? blockView(wait) : null,
    onBreak: !run && doc.brk ? { ...doc.brk } : null,
    breakOffer: offer,
    recent,
    later: doc.later.filter((x) => !x.del).map(({ id, text }) => ({ id, text })),
  };
};

E.history = (doc) => doc.blocks.filter((b) => b.ended).sort((a, b) => b.start - a.start).map((b) => ({
  id: b.id, start: b.start, intention: b.intention, minutes: b.minutes,
  actual: Math.max(1, Math.round(activeMs(b, b.ended) / M())), stopped: Boolean(b.stopped),
  outcome: b.outcome || null, drifts: drifts(b), checkins: b.checkins.length, style: b.style || 'ask',
  answered: b.checkins.filter((c) => c.answer).length,
  whys: b.checkins.filter((c) => c.answer === 'drifted' && c.why).map((c) => c.why),
  parked: b.parked.map((x) => x.text),
  cat: b.cat || 'miso', tag: b.tag || null, kitten: kitten(b), goneWhy: b.goneWhy || null, goneBy: b.goneBy || null,
  treats: treatsOf(b) + napBonus(b), nap: b.nap || null, napN: b.napN || 0,
}));

// ---------- changing the doc (each takes `now`, throws on bad input) ----------
const bad = (what) => { throw Object.assign(new Error(what), { bad: true }); };

function closeAll(doc, now) {
  doc.brk = null; doc.brkU = now;
  for (const b of doc.blocks) {
    if (!b.ended) { stopped(b, b.paused || now, 'stop'); b.ended = b.paused || now; b.paused = null; b.stopped = true; b.plan = []; touch(doc, b, now); }
    if (!b.closed) { b.closed = true; touch(doc, b, now); }
  }
}

E.start = (doc, o, now) => {
  const intention = clean(o.intention, 140);
  if (!intention) bad('intention');
  let minutes = Math.round(Number(o.minutes));
  // Joining a friend: end at the same moment they do.
  const end = o.withEnd && o.withEnd > now + M() ? o.withEnd : now + minutes * M();
  minutes = Math.max(1, Math.round((end - now) / M()));
  if (!(minutes >= 1 && minutes <= 240)) bad('minutes');
  const style = E.STYLES.includes(o.style) ? o.style : doc.settings.style;
  // the kitten invited to grow during the block (none with cat mode off)
  const cat = o.cat === null ? undefined : CAT_ID.test(o.cat || '') ? o.cat : doc.settings.companion || 'miso';
  const tag = clean(o.tag, 20);
  const nap = cat && NAP_ID.test(o.nap || '') ? o.nap : undefined; // napping together (joining a friend)
  closeAll(doc, now);
  const b = {
    id: uid(), intention, step: clean(o.step, 140) || undefined, minutes, style,
    interval: doc.settings.interval, start: now, end, checkins: [], plan: [], parked: [], pausedTotal: 0,
    with: clean(o.withName, 40) || undefined, cat, tag: tag || undefined, nap, u: now,
  };
  b.plan = planFrom(b, now);
  doc.blocks.push(b);
  if (doc.blocks.length > 2000) { const before = earnedOf(doc); doc.blocks.splice(0, doc.blocks.length - 2000); bank(doc, before); }
  doc.settings = { ...doc.settings, style, companion: cat || doc.settings.companion, tag, u: now }; // what you used last is the default next time
  if (o.fromLater) E.doneLater(doc, o.fromLater, now);
  doc.u = now;
  return b;
};

E.stop = (doc, now) => {
  const b = E.running(doc);
  if (b) { stopped(b, b.paused || now, 'stop'); b.ended = b.paused || now; b.paused = null; b.stopped = true; b.plan = []; touch(doc, b, now); }
};

// You left Kavana in the middle of a block (the page knows from its visibility): the kitten wanders
// off at `at`. The block itself goes on. The page may only find out later, after the block has ended
// (and the kitten came home): then it's undone.
E.away = (doc, at, now) => {
  let b = null;
  for (let i = doc.blocks.length - 1; i >= 0 && !b; i--) { const x = doc.blocks[i]; if (x.start <= at && (!x.ended || x.ended > at)) b = x; }
  if (!b || !b.cat || b.gone || (b.home && b.home <= at) || (b.paused && b.paused <= at)) return null;
  delete b.home; b.treats = 0;
  b.gone = at; b.goneWhy = 'away';
  touch(doc, b, now);
  return b;
};

// Napping together. The server keeps the group; the page tells the engine what it learns.
// The block is in a group with n friends (the one who started it learns this after someone joins):
E.napWith = (doc, blockId, napId, n, now) => {
  const b = doc.blocks.find((x) => x.id === blockId);
  if (!b || !b.cat || !NAP_ID.test(napId || '')) return null;
  const napN = Math.max(b.napN || 0, Math.min(20, Math.round(Number(n) || 0)));
  if (b.nap === napId && (b.napN || 0) === napN) return null;
  b.nap = napId; b.napN = napN;
  touch(doc, b, now);
  return b;
};
// Someone in the group left or stopped at `at`: this kitten wanders off too, unless it was home already.
E.napGone = (doc, napId, at, who, now) => {
  const b = doc.blocks.find((x) => x.nap === napId || x.id === napId);
  if (!b || !b.cat || b.gone || (b.home && b.home <= at) || at < b.start) return null;
  delete b.home; b.treats = 0;
  b.gone = at; b.goneWhy = 'nap'; b.goneBy = clean(who, 40) || undefined;
  touch(doc, b, now);
  return b;
};

E.buy = (doc, id, now) => {
  if (!E.SHOP[id]) bad('item');
  if (!E.forSale(id, now)) bad('season');
  if (E.owns(doc, id)) bad('owned');
  if (E.treats(doc).left < E.SHOP[id]) bad('treats');
  doc.owned.push({ id, at: now, u: now });
  doc.u = now;
};

// Treats from blocks that leave the doc (and the missions they did) stay yours: `before` is what
// the blocks had earned before they left.
function bank(doc, before) {
  const n = before - earnedOf(doc);
  if (n > 0) doc.tally.banked = (doc.tally.banked || 0) + n;
}

E.pause = (doc, now) => {
  const b = E.running(doc);
  if (b && !b.paused && !b.nap) { b.paused = now; touch(doc, b, now); } // no pausing while napping together
};

E.resume = (doc, now) => {
  const b = E.running(doc);
  if (!b || !b.paused) return;
  const d = now - b.paused; // the end and every ping move back by the pause
  b.end += d;
  for (const p of b.plan) p.at += d;
  b.pausedTotal = (b.pausedTotal || 0) + d;
  b.paused = null;
  touch(doc, b, now);
};

E.extend = (doc, id, more, now) => {
  const b = E.awaiting(doc);
  if (!b || b.id !== id || !E.MORE.includes(Number(more)) || E.running(doc)) bad('extend');
  b.pausedTotal = (b.pausedTotal || 0) + (now - b.ended); // the time on the end screen doesn't count
  b.end = now + Number(more) * M();
  b.minutes += Number(more);
  b.ended = null;
  b.stopped = false;
  b.plan = planFrom(b, now);
  touch(doc, b, now);
};

E.answer = (doc, id, n, answer, why, now) => {
  const b = doc.blocks.find((x) => x.id === id);
  if (!b || !['yes', 'drifted'].includes(answer)) bad('answer');
  const c = b.checkins.find((x) => x.n === Number(n)) || b.checkins[b.checkins.length - 1];
  if (!c) bad('answer');
  c.answer = answer;
  if (answer === 'drifted' && E.REASONS.includes(why)) c.why = why;
  touch(doc, b, now);
  return drifts(b);
};

E.outcome = (doc, id, outcome, now) => {
  const b = doc.blocks.find((x) => x.id === id && x.ended);
  if (!b) bad('outcome');
  b.outcome = E.OUTCOMES.includes(outcome) ? outcome : null;
  b.closed = true;
  touch(doc, b, now);
};

E.park = (doc, text, now) => {
  const t = clean(text, 200);
  const b = E.running(doc) || E.awaiting(doc);
  if (!b || !t) bad('park');
  b.parked = b.parked.concat({ at: now, text: t }).slice(-30);
  touch(doc, b, now);
  E.addLater(doc, t, now); // parked thoughts land on the Later list too
};

E.addLater = (doc, text, now) => {
  const t = clean(text, 200);
  if (!t) bad('text');
  if (doc.later.some((x) => !x.del && x.text.toLowerCase() === t.toLowerCase())) return;
  doc.later.unshift({ id: uid(), text: t, at: now, u: now });
  // Crossed-off items stay as tombstones for a month so other devices learn about them.
  doc.later = doc.later.filter((x) => !x.del || now - x.u < 30 * 86400000).slice(0, 200);
  doc.u = now;
};

E.doneLater = (doc, id, now) => {
  const x = doc.later.find((y) => y.id === id);
  if (x) { x.del = true; x.text = ''; x.u = now; doc.u = now; }
};

E.startBreak = (doc, minutes, now) => {
  if (!E.BREAKS.includes(Number(minutes)) || E.running(doc)) bad('break');
  doc.brk = { minutes: Number(minutes), start: now, end: now + Number(minutes) * M() };
  doc.brkU = now; doc.u = now;
  E.count(doc, 'breaks', now);
};

E.count = (doc, key, now) => { if (/^[a-z]{1,16}$/.test(key)) { doc.tally[key] = (doc.tally[key] || 0) + 1; doc.u = now; } };

E.stopBreak = (doc, now) => { doc.brk = null; doc.brkU = now; doc.u = now; };

E.clearHistory = (doc, now) => {
  const before = earnedOf(doc);
  doc.cleared = now;
  doc.blocks = doc.blocks.filter((b) => !b.ended);
  bank(doc, before);
  doc.u = now;
};

E.setSettings = (doc, patch, now) => {
  const s = { ...doc.settings };
  if ('interval' in patch) {
    if (!E.INTERVALS.includes(Number(patch.interval))) bad('interval');
    s.interval = Number(patch.interval);
    const b = E.running(doc);
    if (b && !b.paused) { // applies to the block that's running too, counted from its last check-in
      const last = b.checkins.length ? b.checkins[b.checkins.length - 1].at : b.start;
      b.interval = s.interval;
      b.plan = planFrom(b, Math.max(last, now - b.interval * M() + M()));
      touch(doc, b, now);
    }
  }
  if ('goal' in patch) {
    if (!E.GOALS.includes(Number(patch.goal))) bad('goal');
    s.goal = Number(patch.goal);
  }
  if ('daily' in patch) {
    const d = patch.daily || {};
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(String(d.time))) bad('time');
    const days = [...new Set((Array.isArray(d.days) ? d.days : []).map(Number))].filter((x) => x >= 0 && x <= 6).sort();
    s.daily = { on: Boolean(d.on), time: d.time, days };
  }
  if ('tz' in patch) s.tz = clean(patch.tz, 64);
  if ('share' in patch) {
    if (!E.SHARE.includes(patch.share)) bad('share');
    s.share = patch.share;
  }
  if ('friendPings' in patch) s.friendPings = Boolean(patch.friendPings);
  if ('name' in patch) s.name = clean(patch.name, 30);
  if ('cat' in patch) s.cat = Boolean(patch.cat);
  if ('companion' in patch && CAT_ID.test(patch.companion)) s.companion = patch.companion;
  if ('catsSeen' in patch && Array.isArray(patch.catsSeen)) s.catsSeen = [...new Set(patch.catsSeen.filter((x) => CAT_ID.test(x)))].slice(0, 30);
  if ('tags' in patch) {
    if (!Array.isArray(patch.tags)) bad('tags');
    const seen = new Set();
    s.tags = patch.tags.map((t) => clean(t, 20)).filter((t) => t && !seen.has(t.toLowerCase()) && seen.add(t.toLowerCase())).slice(0, 12);
    if (s.tag && !s.tags.includes(s.tag)) s.tag = '';
  }
  if ('tag' in patch) s.tag = clean(patch.tag, 20);
  s.u = now;
  doc.settings = s;
  doc.u = now;
};

// ---------- what the server should send, and when ----------
// Cat mode (on unless turned off) gives the pings a cat and a "Boo!".
E.pings = (doc, now) => {
  const out = [];
  const cat = doc.settings.cat !== false;
  const b = E.running(doc);
  if (b && !b.paused) {
    for (const p of b.plan) {
      if (p.at <= now) continue;
      out.push(p.kind === 'nudge'
        ? { at: p.at, kind: 'nudge', tag: `kavana-${b.id}`, title: `${cat ? '🐱 ' : ''}${b.intention}`, body: p.line, block: b.id, n: p.n }
        : {
          at: p.at, kind: 'checkin', tag: `kavana-${b.id}`, block: b.id, n: p.n,
          title: `${cat ? '🐱 Boo! ' : ''}Still on: ${b.intention}?`, body: `${Math.max(1, Math.round((b.end - p.at) / M()))} min left. Tap to answer.`,
          actions: [{ action: 'yes', title: 'Yes' }, { action: 'drifted', title: 'Drifted' }],
        });
    }
    out.push({ at: b.end, kind: 'end', tag: `kavana-${b.id}`, block: b.id, title: cat ? "🐱 Boo! Time's up. How did it go?" : 'How did it go?', body: `${b.intention}. Tap to log it.` });
  }
  if (doc.brk && doc.brk.end > now) {
    out.push({ at: doc.brk.end, kind: 'break-over', tag: 'kavana-break', title: cat ? "🐱 Boo! Break's over" : "Break's over", body: "It's time to focus. Ready for the next block?" });
  }
  for (const x of out) if (cat) x.cat = 1;
  return out;
};

// The daily "Time to focus?" ping, as the server needs it.
E.daily = (doc) => {
  const d = doc.settings.daily;
  const next = doc.later.find((x) => !x.del);
  return {
    on: Boolean(d.on), time: d.time, days: d.days, tz: doc.settings.tz,
    body: next ? `Up next: ${next.text}` : 'Pick one thing and start a block.',
    cat: doc.settings.cat !== false,
  };
};

// What a friend may see (nothing unless they share), worked out from their doc.
E.presence = (doc, now) => {
  const share = doc.settings.share;
  if (share === 'off') return null;
  const b = E.running(doc);
  const out = { status: 'idle' };
  if (b) {
    out.status = b.paused ? 'paused' : 'focusing';
    out.end = b.end;
    out.left = b.end - (b.paused || now); // a paused clock doesn't run down
    out.paused = b.paused || null;
    out.id = b.id;
    out.nap = b.cat ? b.nap || b.id : null; // the group to join (cat mode off: just focus together)
    if (share === 'all') out.intention = b.intention;
  } else if (doc.brk && doc.brk.end > now) {
    out.status = 'break';
    out.end = doc.brk.end;
  }
  return out;
};

// ---------- merging two copies of the same doc (sync) ----------
// Records carry `u` (last change). Per record the newer copy wins, crossed-off Later items
// win as tombstones, and a "clear history" removes ended blocks that started before it.
E.merge = (a, b) => {
  a = E.normalize(a);
  b = E.normalize(b);
  const newer = (x, y) => (!x ? y : !y ? x : (y.u || 0) > (x.u || 0) ? y : x);
  const byId = (xs, ys, join = newer) => {
    const m = new Map();
    for (const x of xs) m.set(x.id, x);
    for (const y of ys) m.set(y.id, join(m.get(y.id), y));
    return [...m.values()];
  };
  // A kitten that wandered off stays gone, even if the other copy is newer for another reason
  // (unless it had come home before then).
  const block = (x, y) => {
    const w = newer(x, y);
    const l = w === x ? y : x;
    if (!l || !l.gone || w.gone || (w.home && w.home <= l.gone)) return w;
    const out = { ...w, gone: l.gone, goneWhy: l.goneWhy, treats: 0 };
    if (l.goneBy) out.goneBy = l.goneBy;
    delete out.home;
    return out;
  };
  const out = E.empty();
  out.cleared = Math.max(a.cleared, b.cleared);
  out.blocks = byId(a.blocks, b.blocks, block).filter((x) => !(x.ended && x.start < out.cleared)).sort((x, y) => x.start - y.start);
  // Started on two devices at once: the newest one runs, the other counts as stopped.
  const open = out.blocks.filter(isOpen);
  for (const x of open.slice(0, -1)) { stopped(x, x.paused || x.start, 'stop'); x.ended = x.paused || x.start; x.stopped = true; x.plan = []; x.closed = true; }
  out.later = byId(a.later, b.later).sort((x, y) => y.at - x.at);
  out.owned = byId(a.owned, b.owned).sort((x, y) => x.at - y.at);
  out.settings = (b.settings.u || 0) > (a.settings.u || 0) ? b.settings : a.settings;
  if ((b.brkU || 0) > (a.brkU || 0)) { out.brk = b.brk; out.brkU = b.brkU; } else { out.brk = a.brk; out.brkU = a.brkU; }
  for (const k of new Set([...Object.keys(a.tally), ...Object.keys(b.tally)])) out.tally[k] = Math.max(a.tally[k] || 0, b.tally[k] || 0);
  out.u = Math.max(a.u, b.u);
  return out;
};

if (typeof module !== 'undefined') module.exports = E; else root.Kavana = E;
})(this);
