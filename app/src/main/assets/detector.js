(function () {
  'use strict';
  var found = [], seen = Object.create(null);
  var mediaExt = /\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?#]|$)/i;
  var hlsExt = /\.m3u8(?:[?#]|$)/i;
  var fragmentExt = /\.(ts|m4s|aac)(?:[?#]|$)/i;
  var nonMediaExt = /\.(apk|html?|json|js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|zip)(?:[?#]|$)/i;
  var strongHint = /(?:^|[?&/])videoplayback(?:[?&/]|$)|[?&](?:mime|type|format)=video(?:%2F|\/|[&#]|$)|[?&](?:file|url|src|playlist|stream|hls|mp4|m3u8)=[^&#]*\.(mp4|m3u8|webm|m4v|mov)(?:[&#]|$)/i;
  var apiPath = /\/(?:hot_search|detail|chatRooms|unread|rtcOffer|collect|ping)(?:\/|$)|\/(?:report|notice|chat|user|command)\/|\/(?:video|api)\/(?:url|play|playurl|source|sources|info|detail)(?:\/|$)/i;
  var trace = {
    href: location.href, title: document.title, readyState: document.readyState,
    videos: [], blobs: [], iframes: [], hints: [], resources: 0, scripts: 0,
    scriptUrls: [], bodyText: '', playerText: '', shadowRoots: 0, frames: 0,
    capabilities: {}
  };
  var budget = {nodes: 0, roots: 0, frames: 0};
  function attr(el, name) { try { return el.getAttribute ? el.getAttribute(name) : null; } catch (e) { return null; } }
  function text(value, limit) {
    try { return String(value || '').replace(/\s+/g, ' ').trim().slice(0, limit); } catch (e) { return ''; }
  }
  function pathOf(url) { try { return new URL(url).pathname; } catch (e) { return ''; } }
  function isApi(url) { return apiPath.test(pathOf(url)) && !mediaExt.test(url); }
  function note(value, bucket) {
    if (!value || typeof value !== 'string') return;
    try {
      var url = new URL(value.trim(), document.baseURI).href;
      if (trace[bucket].length < 12 && trace[bucket].indexOf(url) < 0) trace[bucket].push(url);
    } catch (e) {}
  }
  function add(value, kind, title) {
    if (!value || typeof value !== 'string' || found.length >= 24) return;
    try {
      var raw = value.trim();
      if (raw.indexOf('blob:') === 0) { note(raw, 'blobs'); return; }
      var url = new URL(raw, document.baseURI).href;
      if (!/^https?:/i.test(url) || seen[url]) return;
      if (fragmentExt.test(url) || nonMediaExt.test(url) || isApi(url)) return;
      if (!mediaExt.test(url) && kind !== 'video' && !strongHint.test(url)) return;
      seen[url] = true;
      found.push({url: url, kind: hlsExt.test(url) ? 'hls' : (kind === 'video' ? 'video' : 'direct'), title: title || document.title || 'Video'});
    } catch (e) {}
  }
  function unescape(value) {
    try { return JSON.parse('"' + value.replace(/"/g, '\\"') + '"'); } catch (e) { return value.replace(/\\\//g, '/'); }
  }
  function sendEvent(type, el) {
    try {
      if (!window.Xtr3Detector || !window.Xtr3Detector.event) return;
      var info = {type: type, src: el.currentSrc || el.src || '', readyState: el.readyState, networkState: el.networkState,
        currentTime: el.currentTime, duration: el.duration, paused: el.paused, width: el.videoWidth, height: el.videoHeight};
      if (el.error) info.error = el.error.code;
      window.Xtr3Detector.event(JSON.stringify(info));
    } catch (e) {}
  }
  function watchVideo(el) {
    try {
      var seenMedia = window.__xtr3MediaSeen || (window.__xtr3MediaSeen = new WeakSet());
      if (seenMedia.has(el)) return;
      seenMedia.add(el);
      ['error', 'loadedmetadata', 'canplay', 'playing', 'waiting', 'stalled'].forEach(function (type) {
        el.addEventListener(type, function () { sendEvent(type, el); }, {passive: true});
      });
    } catch (e) {}
  }
  function videoInfo(el) {
    var value = el.currentSrc || el.src || attr(el, 'data-src') || '';
    var info = {src: value, currentSrc: el.currentSrc || '', readyState: el.readyState, networkState: el.networkState,
      duration: el.duration, currentTime: el.currentTime, paused: el.paused, width: el.videoWidth, height: el.videoHeight};
    try { if (el.error) info.error = el.error.code; } catch (e) {}
    if (trace.videos.length < 8) trace.videos.push(info);
    if (value && value.indexOf('blob:') === 0) note(value, 'blobs');
    watchVideo(el);
    add(value, 'video');
  }
  function collectPlayerText(root) {
    try {
      var nodes = root.querySelectorAll('.art-notice-inner, .art-notice, .notice, .swal2-title, .swal2-html-container, [role="alert"], .player-error, .artplayer-app, .art-video-player, .player');
      var parts = [];
      for (var i = 0; i < nodes.length && parts.length < 6; i++) {
        var t = text(nodes[i].innerText || nodes[i].textContent, 220);
        if (t && parts.indexOf(t) < 0) parts.push(t);
      }
      if (parts.length) trace.playerText = text(parts.join(' | '), 700);
    } catch (e) {}
  }
  function walk(root, depth) {
    if (!root || depth > 4 || budget.roots > 8) return;
    budget.roots++;
    var nodes;
    try { nodes = root.querySelectorAll('video, source, iframe, script[src], a[href], meta[content], canvas'); } catch (e) { return; }
    for (var i = 0; i < nodes.length && budget.nodes < 3000; i++) {
      var el = nodes[i]; budget.nodes++;
      var tag = (el.tagName || '').toLowerCase();
      if (tag === 'video') { videoInfo(el); continue; }
      if (tag === 'source') { add(attr(el, 'src') || el.src, 'video'); continue; }
      if (tag === 'iframe') {
        var src = attr(el, 'src');
        note(src || 'about:blank', 'iframes');
        try {
          if (el.contentDocument && budget.frames < 4) { budget.frames++; trace.frames++; walk(el.contentDocument, depth + 1); }
        } catch (e) {}
        continue;
      }
      if (tag === 'script') {
        var s = attr(el, 'src');
        if (s && trace.scriptUrls.length < 20) { try { trace.scriptUrls.push(new URL(s, document.baseURI).href); } catch (e) {} }
        continue;
      }
      if (tag === 'meta') {
        var name = (attr(el, 'property') || attr(el, 'name') || '').toLowerCase();
        if (name.indexOf('og:video') === 0 || name === 'twitter:player:stream') add(attr(el, 'content'), 'video');
        continue;
      }
      if (tag === 'a') { add(attr(el, 'href'), 'link'); continue; }
    }
    collectPlayerText(root);
    try {
      var all = root.querySelectorAll('*');
      for (var j = 0; j < all.length && j < 1500 && budget.roots <= 8; j++) {
        if (all[j].shadowRoot) { trace.shadowRoots++; walk(all[j].shadowRoot, depth + 1); }
      }
    } catch (e) {}
  }
  try {
    var nav = window.navigator || {};
    trace.capabilities = {
      mediaSource: typeof window.MediaSource !== 'undefined',
      webRTC: typeof window.RTCPeerConnection !== 'undefined',
      wasm: typeof window.WebAssembly !== 'undefined',
      emeAPI: typeof nav.requestMediaKeySystemAccess === 'function',
      siteSupportDRM: window.supportDRM === true,
      drmLevel: window.drmCapability && window.drmCapability.level || '',
      avc: false, hevc: false
    };
    if (window.MediaSource && typeof window.MediaSource.isTypeSupported === 'function') {
      try { trace.capabilities.avc = window.MediaSource.isTypeSupported('video/mp4;codecs="avc1.42E01E,mp4a.40.2"'); } catch (e) {}
      try { trace.capabilities.hevc = window.MediaSource.isTypeSupported('video/mp4;codecs="hvc1.1.6.L120.90"'); } catch (e) {}
    }
  } catch (e) {}
  walk(document, 0);
  try { if (document.body) trace.bodyText = text(document.body.innerText || document.body.textContent, 1200); } catch (e) {}
  if (window.performance && performance.getEntriesByType) {
    var entries = performance.getEntriesByType('resource');
    trace.resources = entries.length;
    entries.forEach(function (entry) {
      add(entry.name, 'resource');
      try {
        var u = new URL(entry.name, document.baseURI).href;
        if (!seen[u] && !isApi(u) && !nonMediaExt.test(u) && strongHint.test(u) && trace.hints.length < 20) trace.hints.push(u);
      } catch (e) {}
    });
  }
  var json = /["'](?:video_?url|videoUrl|contentURL|content_url|file|src|source|hls|hlsUrl|playlist|stream_url|download_url|mp4|m3u8)["']\s*:\s*["']((?:\\.|[^"'\\])*)["']/gi;
  var bare = /(?:https?:)?\\?\/\\?\/[^\s"'<>\\]+\.(?:mp4|m3u8|webm|m4v|mov)(?:\?[^\s"'<>\\]*)?/gi;
  var scripts = document.querySelectorAll('script:not([src])'), scriptBudget = 2000000;
  for (var k = 0; k < scripts.length && scriptBudget > 0; k++) {
    var scriptText = scripts[k].textContent.slice(0, scriptBudget); scriptBudget -= scriptText.length; trace.scripts++;
    var m; json.lastIndex = 0; bare.lastIndex = 0;
    while ((m = json.exec(scriptText)) !== null) add(unescape(m[1]), 'json');
    while ((m = bare.exec(scriptText)) !== null) add(unescape(m[0]), 'json');
  }
  if (window.Xtr3Detector) {
    if (window.Xtr3Detector.trace) window.Xtr3Detector.trace(JSON.stringify(trace));
    if (window.Xtr3Detector.report) window.Xtr3Detector.report(JSON.stringify(found));
  }
})();
