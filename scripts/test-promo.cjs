// Vérifie les ressources, la détection de langue, la bascule et la lecture des aperçus.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const store = path.join(root, 'store');
const html = fs.readFileSync(path.join(store, 'index.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
assert.equal(scripts.length, 2);

const assets = ['icon.png', 'circuit-ions.gif', 'circuit-ions.mp4', 'circuit-ions.jpg'];
for (const name of ['airpods_white', 'open_ear_black', 'shokz', 'peugeot']) {
  assets.push(`${name}.mp4`, `${name}.jpg`);
}
for (const name of ['style-minimal', 'style-lovely', 'style-fanciest']) {
  assets.push(`${name}.jpg`);
  assert.ok(fs.existsSync(path.join(store, 'gifs', `${name}.gif`)), `asset manquant : gifs/${name}.gif`);
}
for (const name of assets) {
  assert.ok(fs.existsSync(path.join(store, 'assets', name)), `asset manquant : ${name}`);
}
const resolveAsset = reference => {
  const match = reference.match(/^assets\/(style-(?:minimal|lovely|fanciest))\.gif$/);
  return match ? path.join(store, 'gifs', `${match[1]}.gif`) : path.join(store, reference);
};

assert.ok(html.includes('poster="assets/circuit-ions.jpg"'));
assert.ok(html.includes('<source src="assets/circuit-ions.mp4" type="video/mp4">'));
assert.ok(!html.includes('<img src="assets/circuit-ions.gif"'));
assert.ok(html.includes("url('assets/circuit-ions.jpg')"));
assert.equal((html.match(/media="\(prefers-reduced-motion: reduce\)"/g) || []).length, 3);
assert.equal((html.match(/class="popup-demo"/g) || []).length, 4);
assert.equal((html.match(/class="signal-popup"/g) || []).length, 1);
assert.ok(html.includes('class="popup-name">AirPods Pro'));
assert.ok(html.includes('class="popup-battery"'));
assert.ok(html.includes('85&nbsp;%') && html.includes('--&nbsp;%'));
assert.ok(html.includes('Exemple'));
assert.ok(html.includes('rendus depuis les vraies vues'));
assert.ok(html.includes('https://github.com/mondary/Android_PKbluetooth/releases/latest/download/Android_PKbluetooth.apk'));
assert.ok(html.includes('https://ko-fi.com/pouark'));

for (const match of html.matchAll(/(?:src|poster|href)="(assets\/[^"]+)"/g)) {
  assert.ok(fs.existsSync(resolveAsset(match[1])), `ressource locale manquante : ${match[1]}`);
}
for (const match of html.matchAll(/url\('(assets\/[^']+)'\)/g)) {
  assert.ok(fs.existsSync(resolveAsset(match[1])), `ressource CSS manquante : ${match[1]}`);
}

function simulate(languages, stored, blocked = false, motion = 'full') {
  const buttons = ['fr', 'en'].map(lang => ({
    dataset: { language: lang }, attrs: {},
    setAttribute(key, value) { this.attrs[key] = value; },
    addEventListener(type, fn) { this.click = fn; }
  }));
  const nodes = {
    'meta[name="description"]': { content: '' },
    'meta[property="og:title"]': { content: '' },
    'meta[property="og:description"]': { content: '' },
    'meta[property="og:locale"]': { content: '' }
  };
  function video(labels) {
    return {
      dataset: labels || {}, attrs: {}, plays: 0, pauses: 0,
      setAttribute(key, value) { this.attrs[key] = value; },
      play() { this.plays++; return Promise.resolve(); },
      pause() { this.pauses++; }
    };
  }
  const previewVideos = [video({ labelFr: 'Lire', labelEn: 'Play' })];
  const heroVideo = video();
  const media = [heroVideo, ...previewVideos];
  const storage = { value: stored };
  const writes = [];
  const observers = [];
  const document = {
    documentElement: { lang: 'fr' },
    title: '',
    hidden: false,
    listeners: {},
    addEventListener(type, fn) { this.listeners[type] = fn; },
    querySelector(selector) { return nodes[selector] || null; },
    querySelectorAll(selector) {
      if (selector === '[data-language]') return buttons;
      if (selector === 'video[data-label-fr]') return previewVideos;
      if (selector === '.signal-video, .popup-demo video') return media;
      return [];
    }
  };
  const localStorage = {
    getItem() { if (blocked) throw new Error('blocked'); return storage.value; },
    setItem(key, value) {
      if (blocked) throw new Error('blocked');
      storage.value = value;
      writes.push(value);
    }
  };
  const extra = {};
  if (motion === 'full') {
    extra.matchMedia = () => ({ matches: false });
    extra.IntersectionObserver = class {
      constructor(fn) { this.fn = fn; observers.push(this); }
      observe() {}
    };
  } else if (motion === 'reduced') {
    extra.matchMedia = () => ({ matches: true });
    extra.IntersectionObserver = class {
      constructor(fn) { observers.push(this); }
      observe() {}
    };
  } else if (motion === 'no-observer') {
    extra.matchMedia = () => ({ matches: false });
  }
  const context = vm.createContext({
    document, navigator: { languages, language: 'fr-FR' }, localStorage, ...extra
  });
  scripts.forEach(source => vm.runInContext(source, context));
  return {
    document, buttons, previewVideos, heroVideo, media, storage, writes, observers,
    intersect: (target, isIntersecting) => observers[0].fn([{ target, isIntersecting }])
  };
}

let result = simulate(['de-DE', 'en-GB'], null);
assert.equal(result.document.documentElement.lang, 'en');
assert.equal(result.buttons[0].attrs['aria-pressed'], 'false');
assert.equal(result.buttons[1].attrs['aria-pressed'], 'true');
assert.equal(result.document.title, 'PK Bluetooth — The signal becomes personal');
assert.equal(result.previewVideos[0].attrs['aria-label'], 'Play');
assert.equal(result.writes.length, 0);

result.buttons[0].click();
assert.equal(result.document.documentElement.lang, 'fr');
assert.equal(result.buttons[0].attrs['aria-pressed'], 'true');
assert.equal(result.previewVideos[0].attrs['aria-label'], 'Lire');
assert.equal(result.storage.value, 'fr');
assert.equal(result.document.title, 'PK Bluetooth — Le signal devient personnel');

assert.equal(simulate(['en-US'], 'fr').document.documentElement.lang, 'fr');
assert.equal(simulate(['de-DE'], null, true).document.documentElement.lang, 'fr');

const motion = simulate(['fr'], null, false, 'full');
motion.intersect(motion.heroVideo, true);
assert.equal(motion.heroVideo.plays, 1);
motion.intersect(motion.previewVideos[0], true);
assert.equal(motion.previewVideos[0].plays, 1);
motion.document.hidden = true;
motion.document.listeners.visibilitychange();
assert.equal(motion.heroVideo.pauses, 1);
assert.equal(motion.previewVideos[0].pauses, 1);
motion.intersect(motion.heroVideo, false);
assert.equal(motion.heroVideo.pauses, 2);

const reduced = simulate(['fr'], null, false, 'reduced');
assert.equal(reduced.observers.length, 0);
reduced.media.forEach(item => assert.equal(item.plays, 0));

const noObserver = simulate(['fr'], null, false, 'no-observer');
assert.equal(noObserver.heroVideo.plays, 0);

console.log('Landing : ressources, langues, métadonnées, liens et lecture des aperçus vérifiés.');

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
