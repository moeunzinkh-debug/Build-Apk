(function () {
  'use strict';
  var found = [], seen = Object.create(null);
  var media = /\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?#]|$)/i;
  var hint = /(?:videoplayback|m3u8|mp4|\/hls\/|\/video\/|\/stream\/|[?&](?:mime|type|format)=video|[?&](?:file|url|src)=[^&]*\.(?:mp4|m3u8))/i;
  var trace = {href: location.href, title: document.title, videos: [], blobs: [], iframes: [], hints: [], resources: 0, scripts: 0};
  function attr(el, name) { try { return el.getAttribute ? el.getAttribute(name) : null; } catch (e) { return null; } }
  function add(value, kind, title) {
    if (!value || typeof value !== 'string' || found.length >= 24) return;
    try {
      var url = new URL(value.trim(), document.baseURI).href;
      if (!/^https?:/i.test(url) || seen[url]) return;
      if (!media.test(url) && kind !== 'video' && !hint.test(url)) return;
      seen[url] = true;
      found.push({url: url, kind: /\.m3u8(?:[?#]|$)/i.test(url) ? 'hls' : (kind === 'video' ? 'video' : 'direct'), title: title || document.title || 'Video'});
    } catch (e) {}
  }
  function note(value, bucket) {
    if (!value || typeof value !== 'string') return;
    try {
      var url = new URL(value.trim(), document.baseURI).href;
      if (trace[bucket].length < 12 && trace[bucket].indexOf(url) < 0) trace[bucket].push(url);
    } catch (e) {}
  }
  function unescape(value) {
    try { return JSON.parse('"' + value.replace(/"/g, '\\"') + '"'); } catch (e) { return value.replace(/\\\//g, '/'); }
  }
  document.querySelectorAll('video, video source, a[href], meta[content]').forEach(function (el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'meta') {
      var name = (attr(el, 'property') || attr(el, 'name') || '').toLowerCase();
      if (name.indexOf('og:video') === 0 || name === 'twitter:player:stream') add(el.content, 'video');
      return;
    }
    var value = el.currentSrc || el.src || attr(el, 'data-src') || attr(el, 'data-video') || attr(el, 'href');
    if (tag === 'video') {
      var info = {src: value || '', currentSrc: el.currentSrc || '', readyState: el.readyState, networkState: el.networkState, duration: el.duration};
      try { if (el.error) info.error = el.error.code; } catch (e) {}
      if (trace.videos.length < 8) trace.videos.push(info);
      if (value && value.indexOf('blob:') === 0) note(value, 'blobs');
    }
    add(value, tag === 'video' || tag === 'source' ? 'video' : 'link');
  });
  document.querySelectorAll('iframe[src]').forEach(function (el) { note(attr(el, 'src'), 'iframes'); });
  if (window.performance && performance.getEntriesByType) {
    var entries = performance.getEntriesByType('resource');
    trace.resources = entries.length;
    entries.forEach(function (entry) {
      add(entry.name, 'resource');
      if (!seen[entry.name] && hint.test(entry.name) && trace.hints.length < 20) trace.hints.push(entry.name);
    });
  }
  var json = /["'](?:video_?url|videoUrl|contentURL|content_url|file|src|source|hls|hlsUrl|playlist|stream_url|download_url|mp4|m3u8)["']\s*:\s*["']((?:\\.|[^"'\\])*)["']/gi;
  var bare = /(?:https?:)?\\?\/\\?\/[^\s"'<>\\]+\.(?:mp4|m3u8|webm|m4v|mov)(?:\?[^\s"'<>\\]*)?/gi;
  var scripts = document.querySelectorAll('script:not([src])'), budget = 2000000;
  for (var i = 0; i < scripts.length && budget > 0; i++) {
    var text = scripts[i].textContent.slice(0, budget); budget -= text.length; trace.scripts++;
    var m; json.lastIndex = 0; bare.lastIndex = 0;
    while ((m = json.exec(text)) !== null) add(unescape(m[1]), 'json');
    while ((m = bare.exec(text)) !== null) add(unescape(m[0]), 'json');
  }
  if (window.Xtr3Detector) {
    if (window.Xtr3Detector.trace) window.Xtr3Detector.trace(JSON.stringify(trace));
    if (window.Xtr3Detector.report) window.Xtr3Detector.report(JSON.stringify(found));
  }
})();
