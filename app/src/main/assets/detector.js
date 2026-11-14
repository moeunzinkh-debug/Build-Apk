(function () {
  'use strict';
  var found = [], seen = Object.create(null);
  var media = /\.(mp4|m3u8|webm|m4v|mov|ogv|mkv)(?:[?#]|$)/i;
  function add(value, kind, title) {
    if (!value || typeof value !== 'string' || found.length >= 24) return;
    try {
      var url = new URL(value.trim(), document.baseURI).href;
      if (!/^https?:/i.test(url) || seen[url]) return;
      if (!media.test(url) && kind !== 'video') return;
      seen[url] = true;
      found.push({url: url, kind: /\.m3u8(?:[?#]|$)/i.test(url) ? 'hls' : 'direct', title: title || document.title || 'Video'});
    } catch (e) {}
  }
  function unescape(value) {
    try { return JSON.parse('"' + value.replace(/"/g, '\\"') + '"'); } catch (e) { return value.replace(/\\\//g, '/'); }
  }
  document.querySelectorAll('video, video source, a[href], meta[content]').forEach(function (el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'meta') {
      var name = (el.getAttribute('property') || el.getAttribute('name') || '').toLowerCase();
      if (name.indexOf('og:video') === 0 || name === 'twitter:player:stream') add(el.content, 'video');
      return;
    }
    var value = el.currentSrc || el.src || el.getAttribute('data-src') || el.getAttribute('data-video') || el.getAttribute('href');
    add(value, tag === 'video' || tag === 'source' ? 'video' : 'link');
  });
  if (window.performance && performance.getEntriesByType) {
    performance.getEntriesByType('resource').forEach(function (entry) { add(entry.name, 'resource'); });
  }
  var json = /["'](?:video_?url|videoUrl|contentURL|content_url|file|src|source|hls|hlsUrl|playlist|stream_url|download_url|mp4|m3u8)["']\s*:\s*["']((?:\\.|[^"'\\])*)["']/gi;
  var bare = /(?:https?:)?\\?\/\\?\/[^\s"'<>\\]+\.(?:mp4|m3u8|webm|m4v|mov)(?:\?[^\s"'<>\\]*)?/gi;
  var scripts = document.querySelectorAll('script:not([src])'), budget = 2000000;
  for (var i = 0; i < scripts.length && budget > 0; i++) {
    var text = scripts[i].textContent.slice(0, budget); budget -= text.length;
    var m; json.lastIndex = 0; bare.lastIndex = 0;
    while ((m = json.exec(text)) !== null) add(unescape(m[1]), 'json');
    while ((m = bare.exec(text)) !== null) add(unescape(m[0]), 'json');
  }
  if (window.Xtr3Detector && window.Xtr3Detector.report) window.Xtr3Detector.report(JSON.stringify(found));
})();
