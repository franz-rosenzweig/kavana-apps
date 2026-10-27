// Checks that what the Mac app carries is the web's: the engine files are byte for byte the recorded
// commit's, and the sound list is the web's own KINDS. Needs a clone of the web repo: KAVANA_REPO, or
// kavana-web or kavana beside this repo. With none, it says SKIP and passes (nothing to compare with).
//   node tools/check-web.js
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const cp = require('node:child_process');

const res = path.join(__dirname, '../Chataltul/Resources');
const root = path.join(__dirname, '../..');
const candidates = [process.env.KAVANA_REPO, path.join(root, '../kavana-web'), path.join(root, '../kavana')].filter(Boolean);
const repo = candidates.find((d) => fs.existsSync(path.join(d, '.git')));
if (!repo) { console.log('SKIP the web repo isn\'t here, so the sound list and engine files weren\'t compared (set KAVANA_REPO)'); process.exit(0); }

const git = (...a) => cp.execFileSync('git', ['-C', repo, ...a], { encoding: 'utf8', maxBuffer: 1 << 26 });
const commit = fs.readFileSync(path.join(res, 'js/WEB_COMMIT'), 'utf8').trim();
let failed = 0;
const ok = (what, cond) => { console.log(`${cond ? 'PASS' : 'FAIL'} ${what}`); if (!cond) failed += 1; };

// 1. the engine files are the recorded commit's, unedited
for (const f of ['engine.js', 'lines.js', 'cats.js', 'news.js']) {
  ok(`${f} is the web's, at ${commit.slice(0, 7)}`, git('show', `${commit}:web/${f}`) === fs.readFileSync(path.join(res, 'js', f), 'utf8'));
}

// 2. the sound list is the web's current KINDS (HEAD of the clone), not just the recorded commit's
const src = git('show', 'HEAD:web/sounds.js');
const ctx = vm.createContext({ console });
vm.runInContext(src, ctx);
const web = ctx.KavanaSound;
const mine = JSON.parse(fs.readFileSync(path.join(res, 'sounds.json'), 'utf8'));
ok('the sound list is the web\'s KINDS', JSON.stringify(mine.kinds) === JSON.stringify(web.KINDS));
ok('the sound groups are the web\'s', JSON.stringify(mine.groups) === JSON.stringify(web.GROUPS));
const meows = JSON.parse(src.match(/const MEOWS = (\[[^\]]*\])/)[1].replace(/'/g, '"'));
ok('the meows are the web\'s', JSON.stringify(mine.meows) === JSON.stringify(meows));
for (const m of mine.meows) ok(`${m}.mp3 is in the app`, fs.existsSync(path.join(res, 'audio', `${m}.mp3`)));
const head = git('rev-parse', 'HEAD').trim();
if (head !== commit) console.log(`INFO the web clone is at ${head.slice(0, 7)}, the app was synced from ${commit.slice(0, 7)}`);

// 3. every painting the engine's cats name is in the app
const catsCtx = vm.createContext({ console });
vm.runInContext(fs.readFileSync(path.join(res, 'js/cats.js'), 'utf8'), catsCtx);
for (const c of catsCtx.KavanaCats.CATS) for (const k of ['portrait', 'kitten', 'nap']) {
  const rel = c[k];
  if (rel) ok(`${c.id} ${k} painting is in the app`, fs.existsSync(path.join(res, rel)));
}

console.log(failed ? 'WEB CHECK FAILED' : 'web check passed');
process.exit(failed ? 1 : 0);
