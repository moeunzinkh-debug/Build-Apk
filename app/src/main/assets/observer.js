/* Media observation + bounded source grabbing.
 * It may clone small JSON/JS/text metadata responses and extract only media URLs.
 * It never forwards request bodies, media bodies, credentials, cookies, or DRM keys. */
(function () {
  'use strict';
  if (window.__xtr3Observer) return;
  var mediaExt = /\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?#]|$)/i;
  var hlsExt = /\.m3u8(?:[?#]|$)/i;
  var fragmentExt = /\.(ts|m4s|aac|mpd)(?:[?#]|$)/i;
  var nonMediaExt = /\.(apk|html?|json|js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|zip)(?:[?#]|$)/i;
  var apiPath = /\/(?:hot_search|detail|chatRooms|unread|rtcOffer|collect|ping)(?:\/|$)|\/(?:report|notice|chat|user|command)\/|\/(?:video|api)\/(?:url|play|playurl|source|sources|info|detail)(?:\/|$)/i;
  var hint = /(?:^|[?&/])videoplayback(?:[?&/]|$)|[?&](?:mime|type|format)=video(?:%2F|\/|[&#]|$)|[?&](?:file|url|src|playlist|stream|hls|mp4|m3u8)=[^&#]*\.(mp4|m3u8|webm|m4v|mov)(?:[&#]|$)/i;
  var MAX_META = 256 * 1024, MAX_META_READS = 24;
  var state = {
    href: location.href, frame: 'child', playing: false, buffered: false, peer: false, protected: false,
    candidates: [], blobs: [], sourceBuffers: [], appends: 0, bufferedBytes: 0, fragments: 0,
    fetches: 0, xhrs: 0, serviceWorker: false, metaReads: 0
  };
  try { state.frame = window.top === window ? 'main' : 'child'; } catch (e) {}
  var seen = Object.create(null), mediaSources = new WeakSet(), elementModes = new WeakMap();
  var watched = new WeakSet(), xhrMeta = new WeakMap(), lastState = '', notifications = 0, timer = null;
  function bridge() { try { return window.Xtr3Detector; } catch (e) { return null; } }
  function absolute(value) {
    try {
      if (value && typeof value === 'object' && typeof value.url === 'string') value = value.url;
      if (typeof value !== 'string' || value.length > 8192) return '';
      var url = new URL(value, document.baseURI).href;
      return /^https?:/i.test(url) ? url : '';
    } catch (e) { return ''; }
  }
  function baseMime(value) { return String(value || '').split(';')[0].trim().toLowerCase(); }
  function hlsMime(value) { var m = baseMime(value); return /mpegurl/.test(m); }
  function videoMime(value) { var m = baseMime(value); return m.indexOf('video/') === 0 && m !== 'video/mp2t'; }
  function binaryMime(value) { var m = baseMime(value); return !m || m === 'application/octet-stream' || m === 'binary/octet-stream'; }
  function metadataMime(value) {
    var m = baseMime(value);
    return m === 'application/json' || /\+json$/.test(m) || m === 'application/javascript'
      || m === 'application/x-javascript' || m === 'text/javascript' || m === 'text/plain';
  }
  function nonMediaMime(value) {
    var m = baseMime(value);
    return /^(text|image|audio)\//.test(m) || m === 'application/json' || /\+json$/.test(m)
      || m === 'application/javascript' || m === 'application/xml' || /\+xml$/.test(m)
      || m === 'application/pdf' || m === 'application/zip' || m.indexOf('android.package-archive') >= 0;
  }
  function excluded(url) {
    try { var p = new URL(url).pathname; return nonMediaExt.test(p) || (apiPath.test(p) && !mediaExt.test(p)); }
    catch (e) { return true; }
  }
  function classify(url, mime, trustedVideo) {
    if (!url || excluded(url) || fragmentExt.test(url)) return '';
    if (hlsExt.test(url) || hlsMime(mime)) return 'hls';
    if (nonMediaMime(mime)) return '';
    if (mediaExt.test(url) || videoMime(mime) || trustedVideo) return 'video';
    // An opaque binary response needs a URL hint; a path containing /video/ is not enough.
    return binaryMime(mime) && hint.test(url) ? 'video' : '';
  }
  function summary() {
    return {href: state.href, frame: state.frame, playing: state.playing,
      buffered: state.buffered, peer: state.peer, protected: state.protected,
      sourceBuffers: state.sourceBuffers, blobs: state.blobs, appends: state.appends,
      bufferedBytes: state.bufferedBytes, fragments: state.fragments,
      fetches: state.fetches, xhrs: state.xhrs, serviceWorker: state.serviceWorker,
      metaReads: state.metaReads, candidateCount: state.candidates.length};
  }
  function notify() {
    if (timer !== null || notifications >= 40) return;
    try {
      timer = setTimeout(function () {
        timer = null;
        try {
          var b = bridge();
          if (!b || !b.playback) return;
          var encoded = JSON.stringify(summary());
          if (encoded !== lastState && notifications < 40) {
            lastState = encoded; notifications++; b.playback(encoded);
          }
        } catch (e) {}
      }, 350);
    } catch (e) { timer = null; }
  }
  function candidate(value, mime, source, trustedVideo) {
    try {
      var url = absolute(value), kind = classify(url, mime, trustedVideo === true);
      if (!kind || seen[url] || state.candidates.length >= 24) return false;
      seen[url] = true;
      var item = {url: url, kind: kind, mime: baseMime(mime), source: source};
      state.candidates.push(item);
      var b = bridge();
      if (b && b.network) b.network(JSON.stringify(item));
      return true;
    } catch (e) { return false; }
  }
  function decode(value) {
    try {
      value = String(value || '').replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/\\u002f/gi, '/').replace(/&amp;/g, '&');
      if (/^https?%3A%2F%2F/i.test(value) || /^%2F%2F/i.test(value)) value = decodeURIComponent(value);
    } catch (e) {}
    return value;
  }
  function sensitiveKey(key) {
    return /key|token|secret|password|license|drm|widevine|cookie|authorization/i.test(key);
  }
  function publicText(text) {
    // The bare-URL/atob passes must not resurrect values from sensitive fields.
    var safe = text.replace(/(["']?([a-z0-9_.$-]{1,40})["']?\s*[:=]\s*)(["'])((?:\\.|[^"'\\]){1,8192})\3/gi,
      function (all, prefix, key, quote) { return sensitiveKey(key) ? prefix + quote + '[redacted]' + quote : all; });
    return safe.replace(/(["']?([a-z0-9_.$-]{1,40})["']?\s*[:=]\s*)atob\(\s*["'][A-Za-z0-9+/=]{16,8192}["']\s*\)/gi,
      function (all, prefix, key) { return sensitiveKey(key) ? prefix + '"[redacted]"' : all; });
  }
  function extractFromText(text, source) {
    if (typeof text !== 'string' || !text || text.length > MAX_META) return;
    var count = 0, m;
    // Normalize escaped slashes so bare CDN URLs embedded in JS/JSON are still visible.
    text = text.replace(/\\\//g, '/');
    var keyRe = /["']?([a-z0-9_.$-]{1,40})["']?\s*[:=]\s*["']((?:\\.|[^"'\\]){1,8192})["']/gi;
    while (count < 24 && (m = keyRe.exec(text)) !== null) {
      var key = m[1].toLowerCase(), value = decode(m[2]), mime = '';
      if (sensitiveKey(key)) continue;
      if (/^(hls|hlsurl|m3u8)$/.test(key)) mime = 'application/vnd.apple.mpegurl';
      else if (/^(mp4|videourl|video_url|playurl|play_url|vodurl|vod_url)$/.test(key)) mime = 'video/mp4';
      if (mime) { if (candidate(value, mime, source, false)) count++; }
      else if (mediaExt.test(value) || hint.test(value)) { if (candidate(value, '', source, false)) count++; }
    }
    var safe = publicText(text);
    var bareRe = /(?:https?:)?\\?\/\\?\/[^"'\s<>\\]{1,8192}?\.(?:mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:\?[^"'\s<>\\]{0,8192})?/gi;
    while (count < 24 && (m = bareRe.exec(safe)) !== null) {
      if (candidate(decode(m[0]), '', source, false)) count++;
    }
    var atobRe = /atob\(\s*["']([A-Za-z0-9+/=]{16,8192})["']\s*\)/g;
    var decoder = typeof window.atob === 'function' ? window.atob : null;
    if (decoder) while (count < 24 && (m = atobRe.exec(safe)) !== null) {
      try { if (candidate(decode(decoder(m[1])), '', source, false)) count++; } catch (e) {}
    }
  }
  function noteBlob(value, kind, mime, size) {
    try {
      if (state.blobs.length >= 12) return;
      var item = {url: String(value), kind: kind, mime: baseMime(mime), size: Number(size) || 0};
      for (var i = 0; i < state.blobs.length; i++) if (state.blobs[i].url === item.url) return;
      state.blobs.push(item); notify();
    } catch (e) {}
  }
  function videoEvent(el, type) {
    try {
      var src = el.currentSrc || el.src || '';
      if (type === 'playing' || (!el.paused && el.readyState >= 2)) state.playing = true;
      if (type === 'encrypted' || el.mediaKeys) state.protected = true;
      var mode = elementModes.get(el);
      if (el.srcObject && typeof MediaStream !== 'undefined' && el.srcObject instanceof MediaStream) {
        state.peer = true; mode = 'WebRTC MediaStream';
      } else if (String(src).indexOf('blob:') === 0) {
        mode = mode || 'MSE / blob'; state.buffered = true;
        noteBlob(src, mode, '', 0);
      }
      candidate(src, '', 'video-element', true);
      if (type === 'playing') scheduleGrab();
      notify();
      var b = bridge();
      if (b && b.event && (type === 'playing' || type === 'error' || type === 'encrypted')) {
        b.event(JSON.stringify({type: type, href: location.href, frame: state.frame,
          src: src, transport: mode || 'direct', readyState: el.readyState, networkState: el.networkState,
          duration: el.duration, width: el.videoWidth, height: el.videoHeight,
          error: el.error ? el.error.code : 0, protected: state.protected}));
      }
    } catch (e) {}
  }
  function watch(el) {
    try {
      if (!el || watched.has(el)) return;
      watched.add(el);
      ['playing', 'loadedmetadata', 'waiting', 'stalled', 'error', 'encrypted'].forEach(function (type) {
        el.addEventListener(type, function () { videoEvent(el, type); }, {passive: true});
      });
    } catch (e) {}
  }
  function inspectResponse(value, mime, source) {
    try {
      var url = absolute(value);
      if (fragmentExt.test(url) || baseMime(mime) === 'video/mp2t') {
        if (state.fragments < 100000) state.fragments++;
        return;
      }
      candidate(url, mime, source, false);
    } catch (e) {}
  }
  function readMetaText(response) {
    try {
      if (response.body && typeof response.body.getReader === 'function' && typeof TextDecoder !== 'undefined') {
        var reader = response.body.getReader(), decoder = new TextDecoder(), parts = [], total = 0, exceeded = false;
        function pump() {
          return reader.read().then(function (chunk) {
            if (chunk.done) return exceeded ? '' : parts.join('') + decoder.decode();
            var value = chunk.value, size = value && (value.byteLength || value.length) || 0;
            if (total + size > MAX_META) {
              exceeded = true;
              try { reader.cancel(); } catch (e) {}
              return '';
            }
            total += size;
            try { parts.push(decoder.decode(value, {stream: true})); } catch (e) {}
            return pump();
          });
        }
        return pump().catch(function () { return ''; });
      }
      var length = parseInt(response.headers.get('content-length') || '0', 10);
      if (length > 0 && length <= MAX_META && typeof response.text === 'function') {
        return response.text().then(function (text) { return typeof text === 'string' && text.length <= MAX_META ? text : ''; });
      }
    } catch (e) {}
    return Promise.resolve('');
  }

  if (typeof window.fetch === 'function') {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = '', method = 'GET';
      try {
        url = absolute(input);
        method = String(init && init.method || input && input.method || 'GET').toUpperCase();
        state.fetches++;
        try { state.serviceWorker = !!(navigator.serviceWorker && navigator.serviceWorker.controller); } catch (e) {}
      } catch (e) {}
      var result = originalFetch.apply(this, arguments);
      try {
        if (result && typeof result.then === 'function') {
          result = result.then(function (response) {
            try {
              var type = '';
              try { type = response.headers.get('content-type') || ''; } catch (e) {}
              if (response.ok) {
                inspectResponse(response.url || url, type, 'fetch');
                if (metadataMime(type) && state.metaReads < MAX_META_READS && typeof response.clone === 'function') {
                  try {
                    var copy = response.clone(); state.metaReads++;
                    readMetaText(copy).then(function (text) { extractFromText(text, 'fetch-code'); }).catch(function () {});
                  } catch (e) {}
                }
              }
            } catch (e) {}
            return response;
          });
        }
      } catch (e) {}
      return result;
    };
  }
  if (window.XMLHttpRequest) {
    var proto = window.XMLHttpRequest.prototype, open = proto.open, send = proto.send;
    if (typeof open === 'function') proto.open = function (method, url) {
      try { xhrMeta.set(this, {method: String(method || 'GET').toUpperCase(), url: absolute(url)}); } catch (e) {}
      return open.apply(this, arguments);
    };
    if (typeof send === 'function') proto.send = function () {
      var xhr = this;
      try {
        var meta = xhrMeta.get(xhr);
        if (meta) {
          state.xhrs++;
          xhr.addEventListener('load', function () {
            try {
              if (xhr.status < 200 || xhr.status >= 300) return;
              var mime = '';
              try { mime = xhr.getResponseHeader('content-type') || ''; } catch (e) {}
              inspectResponse(xhr.responseURL || meta.url, mime, 'xhr');
              if (metadataMime(mime) && state.metaReads < MAX_META_READS) {
                try {
                  var length = parseInt(xhr.getResponseHeader('content-length') || '0', 10);
                  if (!length || length <= MAX_META) {
                    if (!xhr.responseType || xhr.responseType === 'text') {
                      var text = xhr.responseText; state.metaReads++;
                      extractFromText(text, 'xhr-code');
                    } else if (xhr.responseType === 'json' && xhr.response) {
                      state.metaReads++;
                      walkConfig(xhr.response, 0, {nodes: 0}, 'xhr-code');
                    }
                  }
                } catch (e) {}
              }
            } catch (e) {}
          }, {passive: true});
        }
      } catch (e) {}
      return send.apply(this, arguments);
    };
  }
  if (window.MediaSource && window.MediaSource.prototype) {
    var addBuffer = window.MediaSource.prototype.addSourceBuffer;
    if (typeof addBuffer === 'function') window.MediaSource.prototype.addSourceBuffer = function (mime) {
      var buffer = addBuffer.apply(this, arguments);
      try {
        mediaSources.add(this); state.buffered = true;
        if (state.sourceBuffers.length < 12 && state.sourceBuffers.indexOf(String(mime)) < 0) state.sourceBuffers.push(String(mime));
        notify();
      } catch (e) {}
      return buffer;
    };
  }
  if (window.SourceBuffer && window.SourceBuffer.prototype) {
    var append = window.SourceBuffer.prototype.appendBuffer;
    if (typeof append === 'function') window.SourceBuffer.prototype.appendBuffer = function (data) {
      var result = append.apply(this, arguments);
      try {
        state.appends++; state.bufferedBytes += data && (data.byteLength || data.length) || 0;
        state.buffered = true; notify();
      } catch (e) {}
      return result;
    };
  }
  if (window.URL && typeof window.URL.createObjectURL === 'function') {
    var create = window.URL.createObjectURL;
    window.URL.createObjectURL = function (object) {
      var url = create.apply(this, arguments);
      try {
        if (mediaSources.has(object)) { state.buffered = true; noteBlob(url, 'MSE', '', 0); }
        else if (typeof Blob !== 'undefined' && object instanceof Blob && /^video\//i.test(object.type)) {
          noteBlob(url, 'Blob', object.type, object.size);
        }
      } catch (e) {}
      return url;
    };
  }
  if (window.HTMLMediaElement && window.HTMLMediaElement.prototype) {
    var mediaProto = window.HTMLMediaElement.prototype;
    var descriptor = Object.getOwnPropertyDescriptor(mediaProto, 'srcObject');
    if (descriptor && descriptor.configurable && descriptor.set && descriptor.get) {
      Object.defineProperty(mediaProto, 'srcObject', {configurable: true, enumerable: descriptor.enumerable,
        get: function () { return descriptor.get.call(this); },
        set: function (value) {
          descriptor.set.call(this, value);
          try {
            if (value && typeof MediaStream !== 'undefined' && value instanceof MediaStream) {
              state.peer = true; elementModes.set(this, 'WebRTC MediaStream'); watch(this); notify();
            }
          } catch (e) {}
        }
      });
    }
    var play = mediaProto.play;
    if (typeof play === 'function') mediaProto.play = function () {
      try { watch(this); } catch (e) {}
      return play.apply(this, arguments);
    };
  }
  try {
    document.addEventListener('load', function (event) {
      if (event.target && event.target.tagName === 'VIDEO') watch(event.target);
    }, {capture: true, passive: true});
    document.addEventListener('encrypted', function (event) {
      if (event.target && event.target.tagName === 'VIDEO') videoEvent(event.target, 'encrypted');
    }, {capture: true, passive: true});
  } catch (e) {}

  function walkConfig(value, depth, budget, source) {
    if (!value || depth > 4 || budget.nodes > 400) return;
    source = source || 'player-config';
    if (typeof value === 'string') {
      if (mediaExt.test(value) || hint.test(value)) candidate(decode(value), '', source, false);
      return;
    }
    if (typeof value !== 'object' || value.nodeType) return;
    budget.nodes++;
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
      if (sensitiveKey(key)) continue;
      var item; try { item = value[key]; } catch (e) { continue; }
      if (typeof item === 'string') {
        var mime = /^(hls|m3u8)$/i.test(key) ? 'application/vnd.apple.mpegurl'
          : /^(mp4|videourl|video_url|playurl|play_url|vodurl|vod_url)$/i.test(key) ? 'video/mp4' : '';
        if (mime || mediaExt.test(item) || hint.test(item)) candidate(decode(item), mime, source, false);
      } else if (typeof item === 'object') walkConfig(item, depth + 1, budget, source);
    }
  }
  var lastGrab = 0;
  function grab(force) {
    try {
      var now = Date.now();
      if (force !== true && now - lastGrab < 5000) return state.candidates;
      lastGrab = now;
      state.href = location.href;
      var videos = document.querySelectorAll('video');
      for (var i = 0; i < videos.length && i < 8; i++) {
        var el = videos[i]; watch(el);
        candidate(el.currentSrc || el.src || el.getAttribute('data-src'), '', 'video-element', true);
      }
      var dataNodes = document.querySelectorAll('[data-src],[data-url],[data-file],[data-video],[data-play-url],[data-hls],[data-source],[data-original]');
      var attrs = ['data-src','data-url','data-file','data-video','data-play-url','data-hls','data-source','data-original'];
      for (var d = 0; d < dataNodes.length && d < 80; d++) {
        for (var a = 0; a < attrs.length; a++) {
          var value = dataNodes[d].getAttribute && dataNodes[d].getAttribute(attrs[a]);
          if (value) candidate(decode(value), /hls/.test(attrs[a]) ? 'application/vnd.apple.mpegurl' : '', 'dom-code', false);
        }
      }
      var scripts = document.querySelectorAll('script:not([src]),script[type="application/json"],script[type="application/ld+json"]');
      for (var s = 0; s < scripts.length && s < 40; s++) extractFromText(scripts[s].textContent || '', 'inline-code');
      var frames = document.querySelectorAll('iframe[srcdoc]');
      for (var f = 0; f < frames.length && f < 8; f++) extractFromText(frames[f].getAttribute('srcdoc') || '', 'iframe-code');
      var metas = document.querySelectorAll('meta[property^="og:video"],meta[name="twitter:player:stream"],meta[name="video_url"]');
      for (var m = 0; m < metas.length && m < 12; m++) candidate(metas[m].getAttribute('content'), '', 'dom-code', false);
      var globals = ['player_data','player_aaaa','vod_player','cms_player','playerConfig','playConfig','videoConfig',
        '__INITIAL_STATE__','__NUXT__','__NEXT_DATA__','artplayer','art','dp','ckplayer','videojs','hls','dash','player'];
      var budget = {nodes: 0};
      for (var g = 0; g < globals.length; g++) { try { walkConfig(window[globals[g]], 0, budget); } catch (e) {} }
      if (window.performance && performance.getEntriesByType) {
        var entries = performance.getEntriesByType('resource');
        for (var r = 0; r < entries.length && r < 400; r++) candidate(entries[r].name, '', 'resource', false);
      }
      state.serviceWorker = !!(navigator.serviceWorker && navigator.serviceWorker.controller);
    } catch (e) {}
    return state.candidates;
  }

  var grabTimer = null, grabs = 0;
  function scheduleGrab() {
    if (grabTimer !== null || grabs >= 3) return;
    try {
      grabTimer = setTimeout(function () {
        grabTimer = null; grabs++;
        // Already bounded to three load/playing grabs; bypass the polling throttle so a
        // playing event shortly after DOMContentLoaded still gets a real deep scan.
        grab(true);
      }, 1200);
    } catch (e) { grabTimer = null; }
  }
  try {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scheduleGrab, {once: true, passive: true});
    else scheduleGrab();
  } catch (e) {}

  window.__xtr3Observer = {
    snapshot: function () {
      try {
        state.href = location.href;
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length && i < 8; i++) { watch(videos[i]); videoEvent(videos[i], 'scan'); }
        state.serviceWorker = !!(navigator.serviceWorker && navigator.serviceWorker.controller);
      } catch (e) {}
      return summary();
    },
    collect: function () {
      try {
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length && i < 8; i++) {
          var el = videos[i]; watch(el); candidate(el.currentSrc || el.src, '', 'video-element', true);
        }
      } catch (e) {}
      return state.candidates;
    },
    grab: grab
  };
})();
