// Vérifie la détection de langue, la bascule et les liens locaux sans ouvrir d'interface.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'store/index.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
assert.equal(scripts.length, 2);
assert.ok(fs.existsSync(path.join(root, 'store/assets/icon.png')));
assert.ok(html.includes('https://github.com/mondary/Android_PKbluetooth/releases/latest/download/Android_PKbluetooth.apk'));
assert.ok(html.includes('https://ko-fi.com/pouark'));
assert.ok((html.match(/class="popup-demo"/g) || []).length >= 5);
assert.ok(html.includes('class="popup-name">AirPods Pro'));
assert.ok(html.includes('class="popup-battery"'));
assert.ok(html.includes('85%') && html.includes('--%'));
assert.ok(html.includes('Aperçus web reconstitués'));

function simulate(languages, stored, blocked = false, motion = null) {
  const buttons = ['fr', 'en'].map(lang => ({dataset:{language:lang}, attrs:{}, setAttribute(key, value){this.attrs[key] = value}, addEventListener(type, fn){this.click = fn}}));
  const nodes = {'meta[name="description"]':{}, 'meta[property="og:title"]':{}, 'meta[property="og:description"]':{}};
  const numbers = [{}, {}, {}];
  const videos = [{dataset:{labelFr:'Lire',labelEn:'Play'},attrs:{},plays:0,pauses:0,setAttribute(k,v){this.attrs[k]=v},play(){this.plays++;return Promise.resolve()},pause(){this.pauses++}}];
  const document = {documentElement:{lang:'fr'}, title:'', hidden:false, listeners:{},addEventListener(k,fn){this.listeners[k]=fn},querySelector:s=>nodes[s],querySelectorAll:s=>s==='[data-language]'?buttons:(s==='video[data-label-fr]'||s==='.popup-demo video')?videos:numbers};
  const storage = {getItem(){if(blocked)throw Error('blocked');return stored},setItem(key,value){if(blocked)throw Error('blocked');stored=value}};
  let callback;
  const extra = motion===null?{}:{matchMedia:()=>({matches:motion==='reduced'}),IntersectionObserver:class {constructor(fn){callback=fn} observe(){}}};
  const context = vm.createContext({document,navigator:{languages,language:'fr-FR'},localStorage:storage,...extra});
  scripts.forEach(source => vm.runInContext(source, context));
  return {document,buttons,numbers,videos,intersect:isIntersecting=>callback([{target:videos[0],isIntersecting}])};
}
let result = simulate(['de-DE','en-GB'], null);
assert.equal(result.document.documentElement.lang, 'en');
assert.equal(result.buttons[1].attrs['aria-pressed'], 'true');
assert.equal(result.numbers[1].textContent, '02 / BATTERY');
assert.equal(result.videos[0].attrs['aria-label'], 'Play');
result.buttons[0].click();
assert.equal(result.document.documentElement.lang, 'fr');
assert.equal(result.buttons[0].attrs['aria-pressed'], 'true');
assert.equal(result.numbers[1].textContent, '02 / BATTERIE');
assert.equal(result.videos[0].attrs['aria-label'], 'Lire');
assert.equal(simulate(['en-US'], 'fr').document.documentElement.lang, 'fr');
assert.equal(simulate(['de-DE'], null, true).document.documentElement.lang, 'fr');
const motion = simulate(['fr'], null, false, 'full');
motion.intersect(true);
assert.equal(motion.videos[0].plays, 1);
motion.document.hidden = true;
motion.document.listeners.visibilitychange();
assert.equal(motion.videos[0].pauses, 1);
motion.intersect(false);
assert.equal(motion.videos[0].pauses, 2);
assert.equal(simulate(['fr'], null, false, 'reduced').videos[0].plays, 0);
for (const name of ['airpods_white','open_ear_black','shokz','peugeot']) {
  for (const extension of ['mp4','jpg']) assert.ok(fs.existsSync(path.join(root, 'store/assets', `${name}.${extension}`)));
}
console.log('Landing : langues, état des boutons, métadonnées et liens vérifiés.');

if (process.argv[2]) {
  const hub = fs.readFileSync(process.argv[2], 'utf8');
  assert.ok(hub.includes("data-category=\"android\""));
  const start = hub.indexOf('// Entrée maintenue indépendamment');
  const end = hub.indexOf('const frText=', start);
  assert.ok(start >= 0 && end > start);
  const apps = [], en = {apps:{},ui:{categories:['All','Mac','Chrome','Web']}};
  vm.runInNewContext(hub.slice(start, end), {apps,en,encodeURIComponent});
  const app = apps.find(item=>item.name === 'PK Bluetooth');
  assert.equal(app.platform, 'android');
  assert.equal(app.url, './pk-bluetooth/index.html');
  assert.ok(app.icon.startsWith('data:image/'));
  assert.ok(en.apps[app.name].description);
  assert.equal(en.ui.categories[4], 'Android');
  assert.equal(apps.filter(item=>item.platform==='android' && item.name.toLowerCase().includes('bluetooth')).length, 1);
  assert.ok(fs.existsSync(process.argv[3] || path.join(path.dirname(process.argv[2]), 'pk-bluetooth/index.html')));
  console.log('Hub : fiche, catégorie Android, traduction et lien local vérifiés.');
}
