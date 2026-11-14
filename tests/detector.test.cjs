const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/detector.js'), 'utf8');

function el(tagName, attrs = {}, props = {}) {
  return Object.assign({
    tagName: tagName.toUpperCase(),
    getAttribute: name => (name in attrs ? attrs[name] : null),
    addEventListener: () => {},
    shadowRoot: null,
    contentDocument: null
  }, props);
}

function run({elements = [], all = [], playerNodes = [], resources = [], scripts = [], bodyText = '', base = 'https://xstree.com/vplay/35835513.html', windowProps = {}} = {}) {
  let reported = [], traced = null, events = [];
  const document = {
    baseURI: base,
    title: 'Test video',
    readyState: 'complete',
    body: {innerText: bodyText},
    querySelectorAll(selector) {
      if (selector === 'script:not([src])') return scripts.map(textContent => ({textContent}));
      if (selector === '*') return all;
      if (selector.startsWith('video,')) return elements;
      if (selector.includes('.art-notice')) return playerNodes;
      return [];
    }
  };
  const bridge = {
    report: json => { reported = JSON.parse(json); },
    trace: json => { traced = JSON.parse(json); },
    event: json => { events.push(JSON.parse(json)); }
  };
  const window = Object.assign({Xtr3Detector: bridge, navigator: {}}, windowProps);
  const performance = {getEntriesByType: () => resources.map(name => ({name}))};
  window.performance = performance;
  vm.runInNewContext(script, {
    document, window, performance, URL, location: {href: base},
    WeakSet, Set, Map, navigator: window.navigator
  });
  return {reported, traced, events};
}

test('detects a currentSrc from a playing video', () => {
  const {reported} = run({elements: [el('video', {}, {currentSrc: 'https://cdn.test/movie.mp4?token=1'})]});
  assert.equal(reported.length, 1);
  assert.equal(reported[0].url, 'https://cdn.test/movie.mp4?token=1');
  assert.equal(reported[0].kind, 'video');
});

test('detects HLS from network resources and ignores transport fragments', () => {
  const {reported} = run({resources: ['https://cdn.test/master.m3u8', 'https://cdn.test/seg001.ts', 'https://cdn.test/seg002.ts']});
  assert.equal(reported.length, 1);
  assert.equal(reported[0].kind, 'hls');
});

test('detects escaped JSON video URLs', () => {
  const {reported} = run({scripts: ['var player = {"video_url":"https:\\/\\/cdn.test\\/v.mp4?a=1\\u0026b=2"};']});
  assert.equal(reported.length, 1);
  assert.equal(reported[0].url, 'https://cdn.test/v.mp4?a=1&b=2');
});

test('ignores blob URLs and deduplicates', () => {
  const {reported, traced} = run({elements: [
    el('video', {}, {currentSrc: 'blob:https://xstree.com/abc'}),
    el('video', {src: 'https://cdn.test/a.mp4'}),
    el('source', {src: 'https://cdn.test/a.mp4'})
  ]});
  assert.equal(reported.length, 1);
  assert.equal(traced.blobs.length, 1);
});

test('resolves relative source URLs', () => {
  const {reported} = run({elements: [el('source', {src: '../media/a.mp4'})]});
  assert.equal(reported[0].url, 'https://xstree.com/media/a.mp4');
});

test('detects HLS hidden in a query parameter', () => {
  const {reported} = run({resources: ['https://cdn.test/play?file=master.m3u8']});
  assert.equal(reported.length, 1);
  assert.equal(reported[0].kind, 'hls');
});

test('rejects the exact API/app false positives from the xstree log', () => {
  const resources = [
    'https://120.76.173.51/video/hot_search?cmd=app',
    'https://120.76.173.51/video/detail?cmd=app',
    'https://120.76.173.51/command/rtcOffer?cmd=app',
    'https://120.76.173.51/user/info?cmd=app&rpk=secret',
    'https://xiaoming.pkcfcf.cn:12581/report/play?cmd=app',
    'https://xiaoming.pkcfcf.cn:12581/notice/list?cmd=app',
    'https://xiaoming.pkcfcf.cn:12581/chat/chatRooms?cmd=app',
    'https://xstree.com/api/chat/unread?cmd=app',
    'https://www.googletagmanager.com/gtag/js?id=G-S3J22V1F3N',
    'https://www.google-analytics.com/g/collect?v=2',
    'https://xstree.com/static/app/bigtree.apk'
  ];
  const {reported, traced} = run({resources, bodyText: '请下载APP观看'});
  assert.equal(reported.length, 0);
  assert.equal(traced.videos.length, 0);
  assert.ok(traced.bodyText.includes('APP'));
});

test('finds video inside an open shadow root', () => {
  const shadowVideo = el('video', {}, {currentSrc: 'https://cdn.test/shadow.mp4'});
  const shadowRoot = {querySelectorAll: selector => selector.startsWith('video,') ? [shadowVideo] : []};
  const host = el('div', {}, {shadowRoot});
  const {reported, traced} = run({elements: [], all: [host]});
  assert.equal(reported.length, 1);
  assert.equal(reported[0].url, 'https://cdn.test/shadow.mp4');
  assert.ok(traced.shadowRoots >= 1);
});

test('records player notice and DRM capability flags without requesting keys', () => {
  const {reported, traced} = run({
    playerNodes: [el('div', {}, {textContent: 'DRM required / 请使用APP'})],
    bodyText: 'Open in app to watch',
    windowProps: {supportDRM: true, drmCapability: {level: 'L3'}, MediaSource: {isTypeSupported: () => true}}
  });
  assert.equal(reported.length, 0);
  assert.ok(traced.playerText.includes('DRM'));
  assert.equal(traced.capabilities.siteSupportDRM, true);
  assert.equal(traced.capabilities.drmLevel, 'L3');
});

test('caps results to avoid bridge flooding', () => {
  const resources = Array.from({length: 50}, (_, i) => `https://cdn.test/${i}.mp4`);
  assert.equal(run({resources}).reported.length, 24);
});
