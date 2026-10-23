(function () {
'use strict';
const K = KavanaWarp, RIG = /*RIG*/null;
const $ = (s) => document.querySelector(s);
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const opt = (o = {}) => ({ rig: RIG, base: 'cats/', ...o });
const FISH = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2.5 12c2.7-4.6 8.6-6 13-3.2L20.5 6v12l-5-2.8C11.1 18 5.2 16.6 2.5 12z"/></svg>';
const fish = (n) => `<span class="fish">${FISH}${n}</span>`;

// Kavana's cats (web/cats.js) and shop prices (web/engine.js E.SHOP)
const CATS = [
  { id: 'miso', name: 'Miso', about: 'An orange tabby who naps while you work.', how: 'Here from the start' },
  { id: 'pepper', name: 'Pepper', about: 'Quiet, curious, and always on time.', how: 'Finish your first block' },
  { id: 'mochi', name: 'Mochi', about: 'Soft as a cloud, sleeps in sunbeams.', how: 'Focus for 1 hour in all', prog: [25, 60, 'min'] },
  { id: 'pickles', name: 'Pickles', about: 'Shows up when you keep showing up.', how: 'Focus 3 days in a row', prog: [1, 3, 'days'] },
  { id: 'noodle', name: 'Noodle', about: 'Dressed up for a good break.', how: 'Take 3 breaks', prog: [0, 3, 'breaks'] },
  { id: 'earl', name: 'Earl Grey', about: 'A distinguished cat for long sessions.', how: 'Focus for 5 hours in all', prog: [25, 300, 'min'] },
  { id: 'biscuit', name: 'Biscuit', about: 'Says a lot, mostly "well done".', how: 'Mark 10 blocks Done', prog: [1, 10, 'done'] },
  { id: 'boo', name: 'Boo', about: 'Only comes out late. Boo!', how: 'Focus after 10 at night', prog: [0, 1, 'late block'] },
  { id: 'luna', name: 'Luna', about: 'A fluffy cloud with opinions.', price: 200 },
  { id: 'cocoa', name: 'Cocoa', about: 'Runs the café. Makes a good latte.', price: 250 },
  { id: 'olive', name: 'Olive', about: 'Wears her sweater all year round.', price: 300 },
  { id: 'dumpling', name: 'Dumpling', about: 'Round, folded, very sure of himself.', price: 350 },
  { id: 'maple', name: 'Maple', about: 'A gentle giant for long sessions.', price: 400 },
  { id: 'ziggy', name: 'Ziggy', about: 'Fast, spotted, always ready to start.', price: 450 },
  { id: 'pumpkin', name: 'Pumpkin', about: 'Lives in a jack-o\'-lantern. Only here for Halloween.', price: 300, event: true },
];
const TREATS = 25, MET = new Set(['miso', 'pepper']);
const ITEMS = [
  { id: 'box', name: 'Cardboard Box', at: [69, 97, 14], perch: [69, 90], price: 20 },
  { id: 'cushion', name: 'Cushion', at: [37, 95, 15], perch: [37, 89], price: 40 },
  { id: 'yarnbasket', name: 'Yarn Basket', at: [27, 79, 11], price: 40 },
  { id: 'rug', name: 'Rug', at: [52, 95, 40], floor: true, price: 60 },
  { id: 'plant', name: 'Plant', at: [6, 64, 14], price: 60 },
  { id: 'clock', name: 'Clock', at: [87, 27, 10], wall: true, price: 60 },
  { id: 'lamp', name: 'Lamp', at: [46, 63, 17], price: 80 },
  { id: 'window', name: 'Window', at: [27, 50, 25], wall: true, perch: [27, 48], price: 100 },
  { id: 'fishbowl', name: 'Fishbowl', at: [77, 77, 10], price: 120 },
  { id: 'armchair', name: 'Armchair', at: [87, 93, 23], perch: [87, 83], price: 150 },
  { id: 'bookshelf', name: 'Bookshelf', at: [91, 63, 17], perch: [88, 39.5], price: 180 },
  { id: 'cattree', name: 'Cat Tree', at: [12, 90, 27], perch: [12, 55.5], price: 240 },
  { id: 'counter', name: 'Café Counter', at: [66, 64, 29], perch: [66, 23], price: 300 },
];
const OWNED = new Set(['box', 'cushion']);
const byId = (id) => CATS.find((c) => c.id === id);
const shown = [];

// ---------- the same cat, today and living ----------
const hero = new K.Cat($('#hero'), 'miso', 'portrait', opt({ interactive: true, seed: 7 }));
const old = new K.Cat($('#old'), 'miso', 'portrait', opt({ old: true }));
let pick = 'miso', pose = 'portrait';
const picker = $('#picker');
for (const c of CATS) {
  const b = el('button', null, `<img src="cats/${c.id}-1.webp" alt="">`);
  b.type = 'button'; b.setAttribute('aria-label', c.name); b.setAttribute('aria-pressed', String(c.id === pick));
  b.addEventListener('click', () => {
    pick = c.id; picker.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    hero.morph(pick, pose); old.set(pick, pose);
  });
  picker.append(b);
}
$('#pose').addEventListener('click', (e) => {
  const b = e.target.closest('button'); if (!b) return;
  pose = b.dataset.pose; $('#pose').querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  hero.morph(pick, pose); old.set(pick, pose);
});

// ---------- the ring: 25 minutes in 24 seconds ----------
const growSize = (stage, p) => stage === 'nap' ? 0.55 + 0.13 * (p / 0.25) : stage === 'kitten' ? 0.68 + 0.18 * ((p - 0.25) / 0.5) : stage === 'grown' ? 0.86 + 0.14 * ((p - 0.75) / 0.25) : 1;
const SAYS = { nap: 'Miso just arrived. Stay in Kavana and it grows.', kitten: 'Miso is growing. Stay with it.', grown: 'Almost there. Miso is nearly grown up.', home: 'Miso came home. +25 treats.' };
const kit = new K.Cat($('#kitten'), 'miso', 'nap', opt({ interactive: true, seed: 3 }));
const growS = new K.Spring(0.75, 0.7, growSize('nap', 0));
let p = 0, hold = 0, holdUntil = 0, stage = 'nap';
const scrub = $('#scrub'), ring = $('#ring'), clock = $('#clock'), says = $('#says'), grow = $('#grow');
scrub.addEventListener('input', () => { p = scrub.value / 1000; hold = 0; holdUntil = performance.now() + 3500; });
K.tickers.add((dt) => {
  if (performance.now() > holdUntil) {
    if (p >= 1) { hold += dt; if (hold > 3.8) { p = 0; hold = 0; } } else p = Math.min(1, p + dt / 24);
    scrub.value = String(Math.round(p * 1000));
  }
  const st = p >= 1 ? 'home' : p < 0.25 ? 'nap' : p < 0.75 ? 'kitten' : 'grown';
  if (st !== stage) {
    stage = st;
    if (st === 'home') { kit.hop(2); kit.happy(1.3); kit.heart(); setTimeout(() => kit.heart(), 260); }
    else kit.morph('miso', st === 'grown' ? 'portrait' : st);
    says.textContent = SAYS[st];
  }
  growS.to = growSize(st, p);
  grow.style.transform = `scale(${growS.step(dt).toFixed(4)})`;
  ring.setAttribute('stroke-dashoffset', (622 * p).toFixed(1));
  const left = Math.round(25 * 60 * (1 - p));
  clock.textContent = `${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}`;
});

// ---------- over another app ----------
const booBox = $('#booCat'), booCard = $('#booCard');
const booCat = new K.Cat($('#boo'), 'miso', 'portrait', opt({ interactive: true, shadow: false, seed: 11 }));
const rise = new K.Spring(0.55, 0.7, 1.2), card = new K.Spring(0.6, 0.45, 0);
K.tickers.add((dt) => {
  booBox.style.transform = `translateY(${(rise.step(dt) * 100).toFixed(2)}%)`;
  const c = card.step(dt);
  booCard.style.transform = `scale(${(0.55 + 0.45 * c).toFixed(4)})`;
  booCard.style.opacity = clamp(c * 1.4, 0, 1).toFixed(3);
  booCard.style.pointerEvents = c > 0.5 ? 'auto' : 'none';
});
let seq = 0;
async function booPlay() {
  const my = ++seq, ok = () => my === seq;
  rise.x = rise.to = 1.2; rise.v = 0; card.x = card.to = 0; card.v = 0; booCat.ear.forEach((s) => { s.to = 0; });
  await wait(700); if (!ok()) return;
  rise.to = 0; await wait(380); if (!ok()) return;
  booCat.flick(0); booCat.flick(1); booCat.blink();
  card.to = 1; await wait(600); if (!ok()) return;
  booCat.tilt(0.14, 1.4);
  await wait(3200); if (!ok()) return;
  backToWork();
}
async function backToWork() {
  const my = ++seq, ok = () => my === seq;
  card.to = 0; booCat.happy(0.9); booCat.hop(1); booCat.heart();
  await wait(560); if (!ok()) return;
  rise.to = 1.25; await wait(2400); if (ok()) booPlay();
}
async function letMeIn() {
  const my = ++seq, ok = () => my === seq;
  card.to = 0; booCat.tilt(-0.2, 1.8); booCat.ear[0].to = -0.16; booCat.ear[1].to = 0.16;
  await wait(900); if (!ok()) return;
  rise.to = 1.25; await wait(2200); if (ok()) booPlay();
}
$('#booBack').addEventListener('click', backToWork);
$('#booLet').addEventListener('click', letMeIn);
$('#booReplay').addEventListener('click', booPlay);
booPlay();

// ---------- cats ----------
$('#fishCats').innerHTML = `${FISH}${TREATS}`;
$('#fishCafe').innerHTML = `${FISH}${TREATS}`;
$('#metBar').style.width = `${(MET.size / CATS.length) * 100}%`;
$('#metText').textContent = `${MET.size} of ${CATS.length} cats met`;
function cardFor(c) {
  const mine = MET.has(c.id), asleep = !mine && !c.price;
  const b = el('button', 'card' + (mine ? ' mine' : ''));
  b.type = 'button';
  const art = el('div', 'art', '<div class="catbox"><canvas aria-hidden="true"></canvas></div>');
  b.append(art, el('b', null, c.name));
  if (mine) b.append(el('small', null, 'Met'));
  else if (c.price) b.append(el('div', 'price', fish(c.price)));
  else {
    b.append(el('small', null, c.how));
    const bar = el('div', 'bar', `<i style="width:${Math.max(4, (c.prog[0] / c.prog[1]) * 100)}%"></i>`);
    b.append(bar);
  }
  b.setAttribute('aria-label', `${c.name}${mine ? ', met' : c.price ? `, ${c.price} treats` : `, ${c.how}`}`);
  b.addEventListener('click', () => openSheet(c));
  const cat = new K.Cat(art.querySelector('canvas'), c.id, asleep ? 'nap' : 'portrait', opt({ seed: 100 + CATS.indexOf(c) }));
  b.addEventListener('pointerenter', () => !asleep && cat.blink());
  return b;
}
for (const c of CATS) (c.event ? $('#gEvent') : c.price ? $('#gAdopt') : $('#gMeet')).append(cardFor(c));

const phCats = $('#ph-cats');
const sheetCat = new K.Cat($('#sheetCat'), 'miso', 'portrait', opt({ interactive: true, seed: 21 }));
const st = [0, 1, 2].map((i) => new K.Cat($('#st' + i), 'miso', ['nap', 'kitten', 'portrait'][i], opt({ seed: 30 + i, shadow: false })));
let sheetSeq = 0;
async function openSheet(c) {
  const my = ++sheetSeq, mine = MET.has(c.id), asleep = !mine && !c.price;
  sheetCat.set(c.id, asleep ? 'nap' : 'portrait');
  st[0].set(c.id, 'nap'); st[1].set(c.id, 'kitten'); st[2].set(c.id, 'portrait');
  $('#sheetName').textContent = c.name;
  $('#sheetAbout').textContent = c.about;
  const need = $('#sheetNeed');
  if (mine) need.innerHTML = `<b>Met</b><small>${c.how}. Pick ${c.name} before a block and ${c.name} grows up in the ring.</small>`;
  else if (c.price) need.innerHTML = `<b style="display:flex;justify-content:space-between;align-items:center;gap:8px">${c.event ? 'Halloween only, Oct 25 – Nov 1' : 'In the shop'} ${fish(c.price)}</b><small>${c.price - TREATS} more treats. A block that comes home pays one treat a minute.</small>`;
  else need.innerHTML = `<b>${c.how}</b><div class="bar"><i style="width:${Math.max(3, (c.prog[0] / c.prog[1]) * 100)}%"></i></div><small>${c.prog[0]} of ${c.prog[1]} ${c.prog[2]}</small>`;
  phCats.classList.add('open');
  await wait(650); if (my !== sheetSeq) return;
  if (asleep) { sheetCat.morph(c.id, 'portrait'); await wait(420); if (my !== sheetSeq) return; }
  sheetCat.flick(0); sheetCat.flick(1);
}
$('#sheetBack').addEventListener('click', () => { sheetSeq++; phCats.classList.remove('open'); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { sheetSeq++; phCats.classList.remove('open'); } });

// ---------- café ----------
const room = $('#room');
const place = (node, cx, by, w, z) => { node.style.left = `${cx - w / 2}%`; node.style.width = `${w}%`; node.style.bottom = `${100 - by}%`; node.style.zIndex = String(Math.round(z)); };
const things = ITEMS.map((x) => {
  const img = el('img', 'thing' + (x.floor ? ' flat' : '') + (OWNED.has(x.id) ? '' : ' away'));
  img.src = `cafe/${x.id}-1.webp`; img.alt = '';
  place(img, x.at[0], x.at[1], x.at[2], x.floor ? 1 : x.wall ? 2 : 10 + x.at[1]);
  room.append(img);
  return { x, img };
});
const perch = (id) => ITEMS.find((x) => x.id === id);
// Who sits where: today two cats on the two things you have, someday the whole café.
const SEATS = [
  { cat: 'miso', on: 'cushion', now: true }, { cat: 'pepper', on: 'box', now: true },
  { cat: 'mochi', on: 'window' }, { cat: 'pickles', on: 'armchair' }, { cat: 'noodle', on: 'cattree' },
  { cat: 'earl', on: 'bookshelf' }, { cat: 'biscuit', on: 'counter' },
  { cat: 'boo', at: [54, 93] }, { cat: 'luna', at: [44, 99], pose: 'nap' }, { cat: 'cocoa', at: [80, 99] },
];
const roomCats = SEATS.map((s, i) => {
  const box = el('div', 'roomcat' + (s.now ? '' : ' away'), '<div class="catbox"><canvas aria-hidden="true"></canvas></div>');
  const it = s.on && perch(s.on);
  const [x, y] = it ? it.perch : s.at;
  place(box, x, y, s.now ? 13 : 11, it ? 11 + it.at[1] : 10 + y + 0.5);
  room.append(box);
  const cat = new K.Cat(box.querySelector('canvas'), s.cat, s.pose || 'portrait', opt({ seed: 200 + i, shadow: !it }));
  cat.off = !s.now;
  box.addEventListener('click', () => cat.poke());
  return { s, box, cat };
});
function cafeTiles(all) {
  $('#tThings').textContent = all ? `${ITEMS.length} of ${ITEMS.length}` : `${OWNED.size} of ${ITEMS.length}`;
  $('#tCats').textContent = all ? String(SEATS.length) : '2';
}
cafeTiles(false);
let cafeSeq = 0;
$('#cafeSeg').addEventListener('click', async (e) => {
  const b = e.target.closest('button'); if (!b) return;
  const all = b.dataset.v === 'all', my = ++cafeSeq;
  $('#cafeSeg').querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  cafeTiles(all);
  const extra = things.filter((t) => !OWNED.has(t.x.id));
  if (!all) {
    extra.forEach((t) => t.img.classList.add('away'));
    roomCats.forEach((r) => { if (!r.s.now) { r.box.classList.add('away'); setTimeout(() => { r.cat.off = true; }, 300); } else r.box.style.width = '13%'; });
    return;
  }
  extra.sort((a, b) => (a.x.wall ? 0 : 1) - (b.x.wall ? 0 : 1) || a.x.at[1] - b.x.at[1]);
  for (const t of extra) { t.img.classList.remove('away'); await wait(70); if (my !== cafeSeq) return; }
  for (const r of roomCats) {
    if (r.s.now) { r.box.style.width = '11%'; continue; }
    r.cat.off = false; r.box.classList.remove('away');
    setTimeout(() => r.cat.hop(1), 260);
    await wait(110); if (my !== cafeSeq) return;
  }
});
const shop = $('#shop');
for (const x of ITEMS.filter((i) => !OWNED.has(i.id))) shop.append(el('div', null, `<img src="cafe/${x.id}-1.webp" alt=""><span>${x.name}</span>${fish(x.price)}`));

// ---------- the numbers ----------
const M = K.M, deg = (r) => Math.round(r * 180 / Math.PI), ms = (s) => Math.round(s * 1000);
const rows = [
  ['Breathing', 'Body, most at the belly; feet stay put', `${M.breath.period} s a breath, ${Math.round(M.breath.inhale * 100)}% of it in · ${M.breath.tall * 100}% taller, ${M.breath.belly * 100}% wider at the belly`],
  ['Napping', 'The whole loaf rises', `${M.nap.period} s a breath · ${M.nap.tall * 100}% taller · a z every 1.7 s`],
  ['Blink', 'The eyes squeeze shut from above', `every ${M.blink.every[0]}–${M.blink.every[1]} s · ${ms(M.blink.close)} ms shut, ${ms(M.blink.hold)} held, ${ms(M.blink.open)} open · 1 in ${Math.round(1 / M.blink.double)} twice`],
  ['Head', 'Turns from the neck', `drifts ±${deg(M.head.drift)}° · a curious ${deg(M.head.tilt)}° tilt every ${M.head.tiltEvery[0]}–${M.head.tiltEvery[1]} s · damping ${M.head.damping}, settles in ${M.head.settle} s`],
  ['Looking', 'Head leans toward your finger', `up to ${M.look.max} px of 512 · damping ${M.look.damping}, settles in ${M.look.settle} s`],
  ['Ears', 'One flicks from its base', `every ${M.ears.every[0]}–${M.ears.every[1]} s · damping ${M.ears.damping}, settles in ${M.ears.settle} s`],
  ['Tail', 'Swings from the base, most at the tip', `±${deg(M.tail.swing)}° over ${M.tail.period} s, the swing grows and shrinks over 7.3 s`],
  ['Hop (tap)', 'Crouch, jump, land with a squash', `${ms(M.hop.crouch)} ms crouch · ${M.hop.height} px for ${M.hop.time} s · damping ${M.squash.damping}, settles in ${M.squash.settle} s`],
  ['Purr (hold)', 'Eyes shut, head leans in, hearts', `${M.purr.hz} Hz shiver · eyes ${Math.round(M.purr.squint * 100)}% shut · a heart every 0.55 s`],
  ['Growing up', 'Nap → kitten → grown, one cat', `squash ${Math.round(-M.morph.depth * 100)}% in ${ms(M.morph.down)} ms, swap at the bottom, pop up with sparkles`],
];
const tb = $('#numRows');
for (const [a, b, c] of rows) { const tr = el('tr'); tr.append(el('td', null, a), el('td', null, b), el('td', 'num', c)); tb.append(tr); }

K.start();
})();
