/* Passive media observation. Never reads request/response bodies, credentials, or DRM keys. */
(function () {
  'use strict';
  if (window.__xtr3Observer) return;
  var mediaExt = /\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?#]|$)/i;
  var hlsExt = /\.m3u8(?:[?#]|$)/i;
  var fragmentExt = /\.(ts|m4s|aac|mpd)(?:[?#]|$)/i;
  var nonMediaExt = /\.(apk|html?|json|js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|zip)(?:[?#]|$)/i;
  var apiPath = /\/(?:hot_search|detail|chatRooms|unread|rtcOffer|collect|ping)(?:\/|$)|\/(?:report|notice|chat|user|command)\/|\/(?:video|api)\/(?:url|play|playurl|source|sources|info|detail)(?:\/|$)/i;
  var hint = /(?:^|[?&/])videoplayback(?:[?&/]|$)|[?&](?:mime|type|format)=video(?:%2F|\/|[&#]|$)|[?&](?:file|url|src|playlist|stream|hls|mp4|m3u8)=[^&#]*\.(mp4|m3u8|webm|m4v|mov)(?:[&#]|$)/i;
  var state = {
    href: location.href, frame: 'child', playing: false, buffered: false, peer: false, protected: false,
    candidates: [], blobs: [], sourceBuffers: [], appends: 0, bufferedBytes: 0, fragments: 0,
    fetches: 0, xhrs: 0, serviceWorker: false
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
      candidateCount: state.candidates.length};
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
      if (!kind || seen[url] || state.candidates.length >= 24) return;
      seen[url] = true;
      var item = {url: url, kind: kind, mime: baseMime(mime), source: source};
      state.candidates.push(item);
      var b = bridge();
      if (b && b.network) b.network(JSON.stringify(item));
    } catch (e) {}
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
        if (method === 'GET' && result && typeof result.then === 'function') {
          result = result.then(function (response) {
            try {
              var type = '';
              try { type = response.headers.get('content-type') || ''; } catch (e) {}
              if (response.ok) inspectResponse(response.url || url, type, 'fetch');
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
        if (meta && meta.method === 'GET') {
          state.xhrs++;
          xhr.addEventListener('load', function () {
            try {
              if (xhr.status < 200 || xhr.status >= 300) return;
              var mime = '';
              try { mime = xhr.getResponseHeader('content-type') || ''; } catch (e) {}
              inspectResponse(xhr.responseURL || meta.url, mime, 'xhr');
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
    }
  };
})();
