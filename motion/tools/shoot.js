// Drives headless Chromium over CDP: opens a page, runs steps, saves screenshots, prints console errors.
// usage: node shoot.js <url> <outdir> <width> <height> <dark 0|1> '<steps json>'
// steps: [{wait: ms}, {eval: "js"}, {shot: "name", sel?: "css selector"}]
const fs = require('fs');
const { spawn } = require('child_process');
const [url, outDir, W, H, dark, stepsJson] = process.argv.slice(2);
const steps = JSON.parse(stepsJson || '[]');
const port = 9300 + Math.floor(Math.random() * 500);
const prof = fs.mkdtempSync(outDir + '/prof-');
const chrome = spawn('/Applications/Chromium.app/Contents/MacOS/Chromium', [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${prof}`, '--no-first-run', '--no-default-browser-check',
  '--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist', '--hide-scrollbars', 'about:blank',
], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  let list;
  for (let i = 0; i < 200; i++) { try { list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (list.length) break; } catch {} await sleep(200); }
  const page = list.find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0; const pending = new Map(); const errors = [];
  ws.addEventListener('message', (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
    if (d.method === 'Runtime.exceptionThrown') errors.push('EXC ' + (d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text));
    if (d.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(d.params.type)) errors.push(d.params.type + ' ' + d.params.args.map((a) => a.value ?? a.description).join(' '));
    if (d.method === 'Log.entryAdded' && d.params.entry.level === 'error') errors.push('LOG ' + d.params.entry.text + ' ' + (d.params.entry.url || ''));
  });
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send('Runtime.enable'); await send('Log.enable'); await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: +W, height: +H, deviceScaleFactor: 2, mobile: +W < 600 });
  await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: dark === '1' ? 'dark' : 'light' }] });
  await send('Page.navigate', { url });
  await sleep(2500);
  for (const s of steps) {
    if (s.wait) await sleep(s.wait);
    if (s.eval) { const r = await send('Runtime.evaluate', { expression: s.eval, awaitPromise: true, returnByValue: true }); if (r.result?.result?.value !== undefined) console.log('eval:', JSON.stringify(r.result.result.value)); if (r.result?.exceptionDetails) errors.push('EVAL ' + r.result.exceptionDetails.text); }
    if (s.shot) {
      let clip;
      if (s.sel) {
        const r = await send('Runtime.evaluate', { expression: `(() => { const e = document.querySelector(${JSON.stringify(s.sel)}); e.scrollIntoView({block:'center'}); const b = e.getBoundingClientRect(); return {x:b.left+scrollX,y:b.top+scrollY,width:b.width,height:b.height}; })()`, returnByValue: true });
        await sleep(400);
        clip = { ...r.result.result.value, scale: 1 };
      }
      const r = await send('Page.captureScreenshot', { format: 'png', clip, captureBeyondViewport: !s.sel });
      fs.writeFileSync(`${outDir}/${s.shot}.png`, Buffer.from(r.result.data, 'base64'));
      console.log('shot', s.shot);
    }
  }
  console.log(errors.length ? errors.join('\n') : 'no console errors');
  ws.close(); chrome.kill(); fs.rmSync(prof, { recursive: true, force: true });
  process.exit(0);
})().catch((e) => { console.error(e); chrome.kill(); process.exit(1); });
