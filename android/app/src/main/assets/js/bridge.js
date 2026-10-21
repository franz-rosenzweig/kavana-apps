// The app's side of Kavana's engine. lines.js, engine.js and cats.js are the web app's own files,
// copied as they are (tools/sync-web.sh). This file runs after them in the app's JavaScript
// sandbox, keeps the doc, applies one change at a time and hands back everything the screens draw.
// The helpers below (catStats, metCats, stats, checkMeets) follow web/app.js, so both agree.
(function (root) {
'use strict';
// The sandbox has no Web Crypto. Block ids only need to be unique, not secret.
if (typeof root.crypto === 'undefined') {
  root.crypto = { getRandomValues: (b) => { for (let i = 0; i < b.length; i++) b[i] = Math.floor(Math.random() * 256); return b; } };
}
const E = root.Kavana;
const K = root.KavanaCats;
let doc = E.empty();

const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`; };
const catsOn = () => doc.settings.cat !== false;

function catStats(h) {
  const days = [...new Set(h.map((b) => dayKey(b.start)))].map((k) => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d).getTime(); }).sort((a, b) => a - b);
  let best = 0, run = 0;
  days.forEach((t, i) => { run = i && Math.round((t - days[i - 1]) / 86400000) === 1 ? run + 1 : 1; best = Math.max(best, run); });
  return {
    finished: h.filter((b) => !b.stopped).length,
    totalMin: h.reduce((n, b) => n + b.actual, 0),
    bestStreak: best,
    breaks: doc.tally.breaks || 0,
    done: h.filter((b) => b.outcome === 'done').length,
    night: h.filter((b) => { const hr = new Date(b.start).getHours(); return hr >= 22 || hr < 4; }).length,
  };
}
// Met once, met for good (even after Clear My History), plus the ones adopted in the shop.
function metCats(h) {
  const s = catStats(h);
  const seen = doc.settings.catsSeen || [];
  return K.CATS.filter((c) => (c.adopt ? E.owns(doc, c.id) : c.rule(s) || seen.includes(c.id)));
}
function companion(met) {
  const c = K.byId(doc.settings.companion);
  return met.includes(c) ? c : K.CATS[0];
}
// Today, the last 7 days, days in a row, and check-ins on track (web/app.js stats()).
function stats(blocks, now) {
  const today = dayKey(now);
  const weekAgo = now - 7 * 86400000;
  const out = { today: 0, week: 0, weekBlocks: 0, streak: 0, answered: 0, drifted: 0 };
  const days = new Set();
  for (const b of blocks) {
    days.add(dayKey(b.start));
    if (dayKey(b.start) === today) out.today += b.actual;
    if (b.start >= weekAgo) { out.week += b.actual; out.weekBlocks += 1; out.answered += b.answered || 0; out.drifted += b.drifts || 0; }
  }
  const d = new Date(now);
  if (!days.has(dayKey(d))) d.setDate(d.getDate() - 1); // a streak still counts until today ends
  while (days.has(dayKey(d))) { out.streak += 1; d.setDate(d.getDate() - 1); }
  return out;
}
// New cats to celebrate. The first time, everything already met is marked seen without a fuss.
function checkMeets(now) {
  if (!catsOn()) return [];
  const met = metCats(E.history(doc)).map((c) => c.id);
  const seen = doc.settings.catsSeen;
  if (!seen) { E.setSettings(doc, { catsSeen: met }, now); return []; }
  const fresh = met.filter((id) => !seen.includes(id));
  if (fresh.length) E.setSettings(doc, { catsSeen: [...seen, ...fresh] }, now);
  return fresh;
}

const OPS = {
  tick: () => null,
  start: (o, t) => { const b = E.start(doc, o, t); return { id: b.id }; },
  stop: (o, t) => E.stop(doc, t),
  pause: (o, t) => E.pause(doc, t),
  resume: (o, t) => E.resume(doc, t),
  extend: (o, t) => E.extend(doc, o.id, o.more, t),
  answer: (o, t) => ({ drifts: E.answer(doc, o.id, o.n, o.answer, o.why, t) }),
  outcome: (o, t) => E.outcome(doc, o.id, o.outcome, t),
  park: (o, t) => E.park(doc, o.text, t),
  addLater: (o, t) => E.addLater(doc, o.text, t),
  doneLater: (o, t) => E.doneLater(doc, o.id, t),
  startBreak: (o, t) => E.startBreak(doc, o.minutes, t),
  stopBreak: (o, t) => E.stopBreak(doc, t),
  clearHistory: (o, t) => E.clearHistory(doc, t),
  settings: (o, t) => E.setSettings(doc, o.patch || {}, t),
  away: (o, t) => { const b = E.away(doc, o.at, t); return b ? { cat: b.cat || 'miso' } : null; },
  count: (o, t) => E.count(doc, o.key, t),
};

function state(now) {
  const history = E.history(doc);
  const met = metCats(history);
  const metIds = new Set(met.map((c) => c.id));
  return {
    now,
    view: E.view(doc, now),
    history: history.slice(0, 400),
    treats: E.treats(doc),
    stats: stats(history, now),
    cats: K.CATS.map((c) => ({ id: c.id, name: c.name, about: c.about, how: c.how || null, adopt: Boolean(c.adopt), met: metIds.has(c.id) })),
    companion: companion(met).id,
    pings: E.pings(doc, now),
    daily: E.daily(doc),
  };
}

root.KB = {
  // The doc from the app's file (a JSON string), or a new one.
  load(json) { doc = E.normalize(json ? JSON.parse(json) : null); return 'ok'; },
  dump() { return JSON.stringify(doc); },
  // Speeds the clock up for the debug build's tests: a minute of `ms` milliseconds.
  minute(ms) { E.config.MIN = ms; return 'ok'; },
  // One change (or just a tick) at `now`. Returns what the screens need, and whether to save.
  run(opJson, now) {
    const op = JSON.parse(opJson);
    const before = doc.u;
    let result = null, error = null;
    E.tick(doc, now);
    if (!OPS[op.op]) error = 'op';
    else {
      try { result = OPS[op.op](op, now) || null; } catch (e) { error = e && e.bad ? e.message : String(e && e.stack || e); }
    }
    E.tick(doc, now);
    const meets = checkMeets(now);
    return JSON.stringify({ changed: doc.u !== before, result, error, meets, state: state(now) });
  },
};
})(this);
