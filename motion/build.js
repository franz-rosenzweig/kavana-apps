const fs = require('fs');
const page = fs.readFileSync('page.html', 'utf8');
const warp = fs.readFileSync('warp.js', 'utf8');
const js = fs.readFileSync('page.js', 'utf8').replace('/*RIG*/null', fs.readFileSync('rig.json', 'utf8').trim());
fs.writeFileSync('index.html', page.replace('/*WARP*/', () => warp).replace('/*PAGE*/', () => js));
console.log('index.html', fs.statSync('index.html').size, 'bytes');
