const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/detector.js'), 'utf8');

function run({elements = [], resources = [], scripts = [], base = 'https://xstree.com/vplay/35835512.html'} = {}) {
  let reported = [];
  const document = {
    baseURI: base,
    title: 'Test video',
    querySelectorAll(selector) {
      if (selector === 'script:not([src])') return scripts.map(textContent => ({textContent}));
      return elements;
    }
  };
  const window = {Xtr3Detector: {report: json => { reported = JSON.parse(json); }}};
  const performance = {getEntriesByType: () => resources.map(name => ({name}))};
  window.performance = performance;
  vm.runInNewContext(script, {document, window, performance, URL});
  return reported;
}

test('detects a currentSrc from a playing video', () => {
  const result = run({elements: [{tagName: 'VIDEO', currentSrc: 'https://cdn.test/movie.mp4?token=1'}]});
  assert.equal(result.length, 1);
  assert.equal(result[0].url, 'https://cdn.test/movie.mp4?token=1');
  assert.equal(result[0].kind, 'direct');
});

test('detects HLS from network resources and ignores transport fragments', () => {
  const result = run({resources: ['https://cdn.test/master.m3u8', 'https://cdn.test/seg001.ts', 'https://cdn.test/seg002.ts']});
  assert.equal(result.length, 1);
  assert.equal(result[0].kind, 'hls');
});

test('detects escaped JSON video URLs', () => {
  const result = run({scripts: ['var player = {"video_url":"https:\\/\\/cdn.test\\/v.mp4?a=1\\u0026b=2"};']});
  assert.equal(result.length, 1);
  assert.equal(result[0].url, 'https://cdn.test/v.mp4?a=1&b=2');
});

test('ignores blob URLs and deduplicates', () => {
  const result = run({elements: [
    {tagName: 'VIDEO', currentSrc: 'blob:https://xstree.com/abc'},
    {tagName: 'VIDEO', src: 'https://cdn.test/a.mp4'},
    {tagName: 'SOURCE', src: 'https://cdn.test/a.mp4'}
  ]});
  assert.equal(result.length, 1);
});

test('resolves relative source URLs', () => {
  const result = run({elements: [{tagName: 'SOURCE', src: '../media/a.mp4'}]});
  assert.equal(result[0].url, 'https://xstree.com/media/a.mp4');
});

test('caps results to avoid bridge flooding', () => {
  const resources = Array.from({length: 50}, (_, i) => `https://cdn.test/${i}.mp4`);
  assert.equal(run({resources}).length, 24);
});
