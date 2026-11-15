const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/observer.js'), 'utf8');

function harness({bridgeThrows = false, responseMime = 'video/mp4', responseUrl = '', status = 200} = {}) {
  const network = [], playback = [], events = [], fetchCalls = [], timers = [];
  let bodyReads = 0, xhrBodyReads = 0, appends = 0, plays = 0, blobId = 0;
  const bridge = {
    network: json => { if (bridgeThrows) throw Error('bridge unavailable'); network.push(JSON.parse(json)); },
    playback: json => { if (bridgeThrows) throw Error('bridge unavailable'); playback.push(JSON.parse(json)); },
    event: json => { if (bridgeThrows) throw Error('bridge unavailable'); events.push(JSON.parse(json)); }
  };
  class Headers { constructor(type) { this.type = type; } get(name) { return name.toLowerCase() === 'content-type' ? this.type : null; } }
  class Response {
    constructor(requestUrl) { this.ok = status >= 200 && status < 300; this.status = status; this.url = responseUrl || requestUrl; this.headers = new Headers(responseMime); }
    text() { bodyReads++; return Promise.resolve('must not be read'); }
    arrayBuffer() { bodyReads++; return Promise.resolve(new ArrayBuffer(0)); }
  }
  class XMLHttpRequest {
    constructor() { this.listeners = {}; this.status = status; }
    open(method, url) { this.method = method; this.url = url; return 'open-result'; }
    setRequestHeader() {}
    send(body) { this.body = body; this.responseURL = responseUrl || this.url; return 'send-result'; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    getResponseHeader(name) { return name.toLowerCase() === 'content-type' ? responseMime : null; }
    get responseText() { xhrBodyReads++; return 'must not be read'; }
    fire(type) { for (const fn of this.listeners[type] || []) fn(); }
  }
  class SourceBuffer { appendBuffer(data) { appends++; this.data = data; return undefined; } }
  class MediaSource { addSourceBuffer(mime) { this.mime = mime; return new SourceBuffer(); } }
  class Blob { constructor(type = '', size = 0) { this.type = type; this.size = size; } }
  class MediaStream {}
  class HTMLMediaElement {
    constructor() { this.paused = true; this.readyState = 0; this._srcObject = null; this.listeners = {}; }
    get srcObject() { return this._srcObject; }
    set srcObject(value) { this._srcObject = value; }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    play() { plays++; return Promise.resolve('play-result'); }
    fire(type) { for (const fn of this.listeners[type] || []) fn(); }
  }
  const videos = [];
  const document = {baseURI: 'https://xstree.com/vplay/35835513.html', addEventListener() {}, querySelectorAll: selector => selector === 'video' ? videos : []};
  const window = {
    Xtr3Detector: bridge, fetch: (input, init) => { fetchCalls.push({input, init}); return Promise.resolve(new Response(typeof input === 'string' ? input : input.url)); },
    XMLHttpRequest, MediaSource, SourceBuffer, Blob, MediaStream, HTMLMediaElement,
    URL: class extends URL { static createObjectURL() { return `blob:https://xstree.com/${++blobId}`; } }
  };
  window.top = window;
  const navigator = {serviceWorker: {controller: null}};
  vm.runInNewContext(script, {
    window, document, location: {href: document.baseURI}, navigator, URL,
    MediaSource, SourceBuffer, Blob, MediaStream, HTMLMediaElement, WeakMap, WeakSet,
    setTimeout: fn => { timers.push(fn); return timers.length; }, clearTimeout: () => {}
  });
  return {
    window, document, network, playback, events, fetchCalls, videos,
    get bodyReads() { return bodyReads; }, get xhrBodyReads() { return xhrBodyReads; },
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
