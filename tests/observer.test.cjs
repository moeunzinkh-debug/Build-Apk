const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/observer.js'), 'utf8');

function harness({bridgeThrows = false, responseMime = 'video/mp4', responseUrl = '', status = 200,
  responseBody = '', contentLength = 0, scripts = [], dataNodes = [], metas = [], frames = [],
  globals = {}, resources = []} = {}) {
  const network = [], playback = [], events = [], fetchCalls = [], timers = [];
  let bodyReads = 0, cloneReads = 0, xhrBodyReads = 0, appends = 0, plays = 0, blobId = 0;
  const bridge = {
    network: json => { if (bridgeThrows) throw Error('bridge unavailable'); network.push(JSON.parse(json)); },
    playback: json => { if (bridgeThrows) throw Error('bridge unavailable'); playback.push(JSON.parse(json)); },
    event: json => { if (bridgeThrows) throw Error('bridge unavailable'); events.push(JSON.parse(json)); }
  };
  class Headers {
    constructor(type) { this.type = type; }
    get(name) {
      name = name.toLowerCase();
      if (name === 'content-type') return this.type;
      if (name === 'content-length') return contentLength ? String(contentLength) : null;
      return null;
    }
  }
  class Response {
    constructor(requestUrl, isClone = false) { this.ok = status >= 200 && status < 300; this.status = status; this.url = responseUrl || requestUrl; this.headers = new Headers(responseMime); this.isClone = isClone; }
    clone() { return new Response(this.url, true); }
    text() { if (this.isClone) cloneReads++; else bodyReads++; return Promise.resolve(responseBody || 'must not be read'); }
    arrayBuffer() { bodyReads++; return Promise.resolve(new ArrayBuffer(0)); }
  }
  class XMLHttpRequest {
    constructor() { this.listeners = {}; this.status = status; this.responseType = ''; }
    open(method, url) { this.method = method; this.url = url; return 'open-result'; }
    setRequestHeader() {}
    send(body) { this.body = body; this.responseURL = responseUrl || this.url; return 'send-result'; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    getResponseHeader(name) {
      name = name.toLowerCase();
      if (name === 'content-type') return responseMime;
      if (name === 'content-length') return contentLength ? String(contentLength) : null;
      return null;
    }
    get responseText() { xhrBodyReads++; return responseBody || 'must not be read'; }
    fire(type) { for (const fn of this.listeners[type] || []) fn(); }
  }
  class SourceBuffer { appendBuffer(data) { appends++; this.data = data; return undefined; } }
  class MediaSource { addSourceBuffer(mime) { this.mime = mime; return new SourceBuffer(); } }
  class Blob { constructor(type = '', size = 0) { this.type = type; this.size = size; } }
  class MediaStream {}
  class HTMLMediaElement {
    constructor() { this.paused = true; this.readyState = 0; this._srcObject = null; this.listeners = {}; this.attrs = {}; }
    get srcObject() { return this._srcObject; }
    set srcObject(value) { this._srcObject = value; }
    getAttribute(name) { return this.attrs[name] || null; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    play() { plays++; return Promise.resolve('play-result'); }
    fire(type) { for (const fn of this.listeners[type] || []) fn(); }
  }
  const videos = [];
  const document = {
    baseURI: 'https://xstree.com/vplay/35835513.html', readyState: 'complete', addEventListener() {},
    querySelectorAll(selector) {
      if (selector === 'video') return videos;
      if (selector.startsWith('[data-src]')) return dataNodes;
      if (selector.startsWith('script:not([src])')) return scripts;
      if (selector.startsWith('meta[')) return metas;
      if (selector === 'iframe[srcdoc]') return frames;
      return [];
    }
  };
  const window = Object.assign({
    Xtr3Detector: bridge, fetch: (input, init) => { fetchCalls.push({input, init}); return Promise.resolve(new Response(typeof input === 'string' ? input : input.url)); },
    XMLHttpRequest, MediaSource, SourceBuffer, Blob, MediaStream, HTMLMediaElement,
    URL: class extends URL { static createObjectURL() { return `blob:https://xstree.com/${++blobId}`; } },
    performance: {getEntriesByType: type => type === 'resource' ? resources.map(name => ({name})) : []}
  }, globals);
  window.top = window;
  const navigator = {serviceWorker: {controller: null}};
  vm.runInNewContext(script, {
    window, document, location: {href: document.baseURI}, navigator, URL,
    performance: window.performance,
    MediaSource, SourceBuffer, Blob, MediaStream, HTMLMediaElement, WeakMap, WeakSet,
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout: () => {}
  });
  return {
    window, document, network, playback, events, fetchCalls, videos,
    get bodyReads() { return bodyReads; }, get cloneReads() { return cloneReads; }, get xhrBodyReads() { return xhrBodyReads; },
    get appends() { return appends; }, get plays() { return plays; },
    flush() { while (timers.length) timers.shift()(); },
    video() { const v = new HTMLMediaElement(); videos.push(v); return v; }
  };
}

test('observes an extensionless MP4 fetch without reading or changing the response body', async () => {
  const h = harness();
  const input = 'https://cdn.test/movie?id=1';
  const response = await h.window.fetch(input, {headers: {Authorization: 'Bearer private'}});
  assert.equal(response.url, input);
  assert.equal(h.bodyReads, 0);
  assert.equal(h.network.length, 1);
  assert.equal(h.network[0].url, input);
  assert.equal(h.network[0].mime, 'video/mp4');
  assert.equal(h.network[0].source, 'fetch');
  assert.ok(!JSON.stringify(h.network).includes('private'));
});

test('rejects JSON even when its URL looks like a media file', async () => {
  const h = harness({responseMime: 'application/json', responseUrl: 'https://cdn.test/movie.mp4'});
  await h.window.fetch('https://cdn.test/movie.mp4');
  assert.equal(h.network.length, 0);
});

test('captures HLS MIME even when the playlist URL has no extension', async () => {
  const h = harness({responseMime: 'application/vnd.apple.mpegurl', responseUrl: 'https://cdn.test/playlist?id=2'});
  await h.window.fetch('https://cdn.test/playlist?id=2');
  assert.equal(h.network[0].kind, 'hls');
});

test('an observed MP4 MIME is not mislabelled as HLS by an unrelated query parameter', async () => {
  const url = 'https://v26-reading-videocdn302.qznovelvod.com/hash/video/tos/cn/id/?a=8662&mime_type=video_mp4&format=master.m3u8';
  const h = harness({responseMime: 'video/mp4', responseUrl: url});
  await h.window.fetch(url);
  assert.equal(h.network.length, 1);
  assert.equal(h.network[0].kind, 'video');
  assert.equal(h.network[0].mime, 'video/mp4');
});

test('does not turn xstree JSON APIs or POST requests into videos', async () => {
  const h = harness({responseMime: 'application/json', responseUrl: 'https://120.76.173.51/video/detail?cmd=app'});
  await h.window.fetch('https://120.76.173.51/video/detail?cmd=app', {method: 'POST'});
  assert.equal(h.network.length, 0);
  const fragment = harness({responseMime: 'video/mp2t', responseUrl: 'https://cdn.test/seg001.ts'});
  await fragment.window.fetch('https://cdn.test/seg001.ts');
  assert.equal(fragment.network.length, 0);
  assert.equal(fragment.window.__xtr3Observer.snapshot().fragments, 1);
});

test('observes XHR load metadata without touching responseText', () => {
  const h = harness();
  const xhr = new h.window.XMLHttpRequest();
  assert.equal(xhr.open('GET', 'https://cdn.test/movie'), 'open-result');
  assert.equal(xhr.send(null), 'send-result');
  xhr.fire('load');
  assert.equal(h.network[0].source, 'xhr');
  assert.equal(h.xhrBodyReads, 0);
});

test('classifies blob/MSE playback without offering the blob URL as a download', () => {
  const h = harness();
  const ms = new h.window.MediaSource();
  const buffer = ms.addSourceBuffer('video/mp4; codecs="avc1.42E01E"');
  buffer.appendBuffer(new Uint8Array(12));
  const video = h.video();
  video.src = h.window.URL.createObjectURL(ms);
  video.paused = false; video.readyState = 4;
  video.play();
  const state = h.window.__xtr3Observer.snapshot();
  assert.equal(state.buffered, true);
  assert.equal(state.appends, 1);
  assert.equal(state.bufferedBytes, 12);
  assert.equal(state.blobs[0].kind, 'MSE');
  assert.equal(state.candidateCount, 0);
  assert.equal(h.window.__xtr3Observer.collect().length, 0);
  assert.equal(h.appends, 1);
  assert.equal(h.plays, 1);
});

test('identifies WebRTC srcObject and protected media without reporting keys', () => {
  const h = harness();
  const video = h.video();
  video.srcObject = new h.window.MediaStream();
  video.mediaKeys = {secret: 'never report this'};
  video.play();
  const state = h.window.__xtr3Observer.snapshot();
  assert.equal(state.peer, true);
  assert.equal(state.protected, true);
  assert.equal(state.candidateCount, 0);
  h.flush();
  assert.ok(!JSON.stringify(h.playback).includes('never report this'));
  assert.ok(!JSON.stringify(h.events).includes('never report this'));
});

test('bridge failure does not break the player fetch or MSE append', async () => {
  const h = harness({bridgeThrows: true, responseUrl: 'https://cdn.test/movie.mp4'});
  const response = await h.window.fetch('https://cdn.test/movie.mp4');
  assert.equal(response.status, 200);
  const buffer = new h.window.MediaSource().addSourceBuffer('video/mp4');
  buffer.appendBuffer(new Uint8Array(4));
  assert.equal(h.appends, 1);
  assert.equal(h.bodyReads, 0);
});

test('deduplicates and bounds observed URLs', async () => {
  const h = harness();
  for (let i = 0; i < 40; i++) await h.window.fetch(`https://cdn.test/${i}.mp4`);
  await h.window.fetch('https://cdn.test/0.mp4');
  assert.equal(h.window.__xtr3Observer.collect().length, 24);
  assert.equal(h.network.length, 24);
});

test('grabs a media source hidden in a small JSON fetch response without consuming the original body', async () => {
  const body = '{"play_url":"https:\\/\\/cdn.test\\/hidden.mp4?sign=1","license":"https://drm.test\\/key"}';
  const h = harness({responseMime: 'application/json', responseUrl: 'https://xstree.com/api/video/detail',
    responseBody: body, contentLength: body.length});
  const response = await h.window.fetch('https://xstree.com/api/video/detail');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(response.url, 'https://xstree.com/api/video/detail');
  assert.equal(h.bodyReads, 0);
  assert.equal(h.cloneReads, 1);
  assert.equal(h.network.length, 1);
  assert.equal(h.network[0].url, 'https://cdn.test/hidden.mp4?sign=1');
  assert.equal(h.network[0].source, 'fetch-code');
  assert.ok(!JSON.stringify(h.network).includes('drm.test'));
  assert.ok(!JSON.stringify(h.network).includes('license'));
});

test('grabs a media source from XHR JSON text and skips sensitive keys', () => {
  const body = '{"token":"secret-token","password":"secret-pass","video_url":"https://cdn.test/xhr-source.m3u8"}';
  const h = harness({responseMime: 'application/json', responseUrl: 'https://xstree.com/api/video/detail',
    responseBody: body, contentLength: body.length});
  const xhr = new h.window.XMLHttpRequest();
  xhr.open('POST', 'https://xstree.com/api/video/detail');
  xhr.send('request body must not be read');
  xhr.fire('load');
  assert.equal(h.network.length, 1);
  assert.equal(h.network[0].url, 'https://cdn.test/xhr-source.m3u8');
  assert.equal(h.network[0].kind, 'hls');
  assert.equal(h.network[0].source, 'xhr-code');
  assert.ok(!JSON.stringify(h.network).includes('secret'));
});

test('grab() finds player globals, inline code, data attributes and resource timing', () => {
  const h = harness({
    globals: {player_data: {url: 'https://cdn.test/global.mp4', token: 'secret'}, hls: {url: 'https://cdn.test/global.m3u8'}},
    scripts: [{textContent: 'var player_aaaa={"url":"https:\\/\\/cdn.test\\/inline.mp4"};'}],
    dataNodes: [{getAttribute: name => name === 'data-hls' ? 'https://cdn.test/attr.m3u8' : null}],
    metas: [{getAttribute: () => 'https://cdn.test/meta.mp4'}],
    frames: [{getAttribute: () => '<source src="https://cdn.test/srcdoc.mp4">'}],
    resources: ['https://cdn.test/timing.mp4']
  });
  const found = h.window.__xtr3Observer.grab();
  const urls = found.map(item => item.url);
  for (const url of ['https://cdn.test/global.mp4', 'https://cdn.test/global.m3u8', 'https://cdn.test/inline.mp4',
    'https://cdn.test/attr.m3u8', 'https://cdn.test/meta.mp4', 'https://cdn.test/srcdoc.mp4', 'https://cdn.test/timing.mp4']) {
    assert.ok(urls.includes(url), url);
  }
  assert.ok(!JSON.stringify(found).includes('secret'));
});

test('oversized metadata responses are not read into the grabber', async () => {
  const h = harness({responseMime: 'application/json', responseUrl: 'https://xstree.com/api/video/detail',
    responseBody: '{"video_url":"https://cdn.test/big.mp4"}', contentLength: 300 * 1024});
  await h.window.fetch('https://xstree.com/api/video/detail');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.bodyReads, 0);
  assert.equal(h.network.length, 0);
});

test('bare URL and atob passes do not resurrect sensitive fields', async () => {
  const secret = Buffer.from('https://drm.test/license.mp4').toString('base64');
  const body = '{"license":"https:\\/\\/drm.test\\/key.mp4","video_url":"https:\\/\\/cdn.test\\/safe.mp4"}'
    + `; var license = atob("${secret}");`;
  const h = harness({responseMime: 'application/json', responseUrl: 'https://xstree.com/api/video/detail',
    responseBody: body, contentLength: body.length, globals: {atob: value => Buffer.from(value, 'base64').toString('utf8')}});
  await h.window.fetch('https://xstree.com/api/video/detail');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.network.map(item => item.url), ['https://cdn.test/safe.mp4']);
  assert.ok(!JSON.stringify(h.network).includes('drm.test'));
});

test('polling grab is throttled but forced grab rescans immediately', () => {
  const h = harness({globals: {player_data: {url: 'https://cdn.test/first.mp4'}}});
  assert.equal(h.window.__xtr3Observer.grab().length, 1);
  h.window.player_data = {url: 'https://cdn.test/second.mp4'};
  assert.equal(h.window.__xtr3Observer.grab().length, 1);
  const forced = h.window.__xtr3Observer.grab(true);
  assert.equal(forced.length, 2);
  assert.equal(forced[1].url, 'https://cdn.test/second.mp4');
});

test('a playing event after load still triggers a bounded deep grab', () => {
  const dataNodes = [];
  const h = harness({dataNodes});
  h.flush();
  assert.equal(h.network.length, 0);
  dataNodes.push({getAttribute: name => name === 'data-src' ? 'https://cdn.test/late.mp4' : null});
  const video = h.video();
  video.play();
  video.fire('playing');
  h.flush();
  assert.equal(h.network.length, 1);
  assert.equal(h.network[0].url, 'https://cdn.test/late.mp4');
  assert.equal(h.network[0].source, 'dom-code');
});
