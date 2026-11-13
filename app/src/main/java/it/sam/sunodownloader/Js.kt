package it.sam.sunodownloader

/**
 * Script JavaScript eseguiti nella WebView (porting 1:1 di quelli della versione desktop).
 * NB: niente simbolo dollaro qui dentro, Kotlin lo interpreterebbe come template.
 */
object Js {

    /** Iniettato in ogni pagina PRIMA del codice di Suno: intercetta l'audio gia' decifrato
     *  (WebCrypto, Blob, MediaSource, WebAudio), raccoglie i brani dalle risposte JSON
     *  e tiene muto il player. */
    const val HOOK = """
(() => {
  if (window.__sc) return;
  const C = window.__sc = {dec: [], blobs: [], mse: [], sbs: [], dai: [], clips: {}, order: [], media: [], urls: []};
  const AUDIO_URL = /\.m3u8|\.mp3|\.m4a|\.aac|\.mp4|\.ts(\?|#|(?![\s\S]))|audio|stream/i;
  const noteUrl = (u) => { try { u = String(u || ''); if (u && AUDIO_URL.test(u) && C.urls.indexOf(u) < 0) C.urls.push(u); } catch (e) {} };
  const copy = (x) => {
    try {
      if (x instanceof ArrayBuffer) return x.slice(0);
      if (ArrayBuffer.isView(x)) return x.buffer.slice(x.byteOffset, x.byteOffset + x.byteLength);
    } catch (e) {}
    return null;
  };
  try {
    const sub = crypto && crypto.subtle;
    if (sub) {
      const od = sub.decrypt.bind(sub);
      sub.decrypt = function () {
        return od.apply(null, arguments).then(r => {
          try { if (r && r.byteLength > 20000) C.dec.push(r.slice(0)); } catch (e) {}
          return r;
        });
      };
    }
  } catch (e) {}
  try {
    const oc = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      try { if (o instanceof Blob && o.size > 20000) o.arrayBuffer().then(b => C.blobs.push(b)); } catch (e) {}
      return oc.apply(this, arguments);
    };
  } catch (e) {}
  // --- MediaSource: un registro per ogni flusso (SourceBuffer), senza pezzi doppi
  const sbRec = (sb, mime) => {
    if (!sb.__sc) { sb.__sc = {mime: mime || '?', parts: [], keys: {}, dup: 0, offs: {}}; C.sbs.push(sb.__sc); }
    return sb.__sc;
  };
  const keyOf = (b) => {
    const u = new Uint8Array(b), n = u.length;
    let k = n + ':';
    for (const p of [0, n >> 2, n >> 1, (3 * n) >> 2, Math.max(0, n - 48)]) {
      for (let i = p; i < Math.min(n, p + 48); i++) k += u[i].toString(16);
      k += '|';
    }
    return k;
  };
  try {
    if (window.MediaSource) {
      const oadd = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function (mime) {
        const sb = oadd.apply(this, arguments);
        try { sbRec(sb, mime); } catch (e) {}
        return sb;
      };
    }
    if (window.SourceBuffer) {
      const oa = SourceBuffer.prototype.appendBuffer;
      SourceBuffer.prototype.appendBuffer = function (d) {
        try {
          const b = copy(d);
          if (b) {
            const r = sbRec(this);
            const k = keyOf(b);
            if (r.keys[k]) r.dup++; else { r.keys[k] = 1; r.parts.push(b); }
            r.offs[String(this.timestampOffset)] = 1;
          }
        } catch (e) {}
        return oa.apply(this, arguments);
      };
    }
  } catch (e) {}
  // --- elementi audio a cui viene assegnata una sorgente (anche se non sono nella pagina)
  try {
    const ds = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (ds && ds.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      configurable: true, enumerable: ds.enumerable, get: ds.get,
      set: function (v) { try { if (C.media.indexOf(this) < 0) C.media.push(this); } catch (e) {} return ds.set.call(this, v); }
    });
  } catch (e) {}
  try {
    const AC = window.BaseAudioContext || window.AudioContext;
    if (AC && AC.prototype.decodeAudioData) {
      const odd = AC.prototype.decodeAudioData;
      AC.prototype.decodeAudioData = function (d) {
        const b = copy(d); if (b && b.byteLength > 20000) C.dai.push(b);
        return odd.apply(this, arguments);
      };
    }
  } catch (e) {}
  // --- audio muto (come --mute-audio su desktop)
  try {
    const op = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      try { this.muted = true; this.volume = 0; } catch (e) {}
      try { if (C.media.indexOf(this) < 0) C.media.push(this); } catch (e) {}
      return op.apply(this, arguments);
    };
  } catch (e) {}
  try {
    const oconn = AudioNode.prototype.connect;
    AudioNode.prototype.connect = function (dest) {
      try {
        if (window.AudioDestinationNode && dest instanceof AudioDestinationNode) {
          const g = this.context.createGain(); g.gain.value = 0;
          oconn.call(this, g); oconn.call(g, dest); return dest;
        }
      } catch (e) {}
      return oconn.apply(this, arguments);
    };
  } catch (e) {}
  // --- brani dalle risposte JSON (sostituisce page.on("response") di Playwright)
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![\s\S])/i;
  const str = (v) => (typeof v === 'string' ? v : '');
  const author = (o) => {
    let handle = '', name = '';
    for (const k of ['handle', 'user_handle', 'author_handle', 'creator_handle', 'username']) {
      const v = o[k]; if (typeof v === 'string' && v.trim()) { handle = v; break; }
    }
    for (const k of ['user', 'author', 'creator', 'owner', 'profile', 'user_profile']) {
      const v = o[k];
      if (v && typeof v === 'object' && !Array.isArray(v)) {
        handle = handle || str(v.handle) || str(v.username);
        name = name || str(v.display_name) || str(v.name);
      }
    }
    name = name || str(o.display_name) || str(o.user_display_name);
    return [String(handle).replace(/^@/, '').trim().toLowerCase(), String(name).trim()];
  };
  const fmtDur = (d) => {
    const s = Math.round(Number(d));
    if (!isFinite(s)) return '';
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  };
  const walk = (o, depth) => {
    if (depth > 14 || !o || typeof o !== 'object') return;
    if (Array.isArray(o)) { for (const v of o) if (v && typeof v === 'object') walk(v, depth + 1); return; }
    const id = o.id;
    if (typeof id === 'string' && UUID.test(id) && ('title' in o) &&
        ('audio_url' in o || 'image_url' in o || 'image_large_url' in o || 'video_url' in o)) {
      const meta = (o.metadata && typeof o.metadata === 'object') ? o.metadata : {};
      const a = author(o);
      const cid = id.toLowerCase();
      if (!C.clips[cid]) C.order.push(cid);
      C.clips[cid] = {id: cid, title: str(o.title).trim(), artist: a[1], handle: a[0],
        duration: meta.duration ? fmtDur(meta.duration) : '', date: str(o.created_at).slice(0, 10),
        image: str(o.image_url) || str(o.image_large_url), audio: str(o.audio_url)};
    }
    for (const k in o) { const v = o[k]; if (v && typeof v === 'object') walk(v, depth + 1); }
  };
  try {
    const of = window.fetch;
    window.fetch = function () {
      try { const a = arguments[0]; noteUrl(a && a.url ? a.url : a); } catch (e) {}
      return of.apply(this, arguments).then(r => {
        try {
          const ct = (r.headers && r.headers.get('content-type')) || '';
          if (ct.indexOf('json') >= 0) r.clone().json().then(j => walk(j, 0)).catch(() => {});
        } catch (e) {}
        return r;
      });
    };
  } catch (e) {}
  try {
    const oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) { noteUrl(u); return oo.apply(this, arguments); };
    const os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      this.addEventListener('load', function () {
        try {
          const ct = this.getResponseHeader('content-type') || '';
          if (ct.indexOf('json') < 0) return;
          let j = this.response;
          if (this.responseType === '' || this.responseType === 'text') j = JSON.parse(this.responseText);
          if (j && typeof j === 'object') walk(j, 0);
        } catch (e) {}
      });
      return os.apply(this, arguments);
    };
  } catch (e) {}
})();
"""

    /** Sceglie il buffer audio piu' grande, lo parcheggia in window.__scBest e ne restituisce la dimensione. */
    const val GRAB = """
(() => {
  const C = window.__sc;
  if (!C) return JSON.stringify({stats: null, best: null});
  const isAudio = (b) => {
    if (!b || b.byteLength < 12) return false;
    const u = new Uint8Array(b, 0, 12);
    const s = String.fromCharCode.apply(null, u);
    return s.slice(4, 8) === 'ftyp' || s.startsWith('ID3') || s.startsWith('OggS') ||
           s.startsWith('RIFF') || s.startsWith('fLaC') ||
           (u[0] === 0xFF && (u[1] & 0xE0) === 0xE0) ||
           (u[0] === 0x1A && u[1] === 0x45 && u[2] === 0xDF && u[3] === 0xA3);
  };
  const cands = [];
  for (const k of ['dec', 'blobs', 'dai']) for (const b of C[k]) if (isAudio(b)) cands.push([k, b]);
  const box = (b) => { try { return String.fromCharCode.apply(null, new Uint8Array(b, 4, 4)); } catch (e) { return '?'; } };
  const sbs = [];
  for (const r of C.sbs) {
    const tot = r.parts.reduce((a, b) => a + b.byteLength, 0);
    sbs.push({mime: r.mime, n: r.parts.length, dup: r.dup, kb: Math.round(tot / 1024),
              first: r.parts.length ? box(r.parts[0]) : '', offs: Object.keys(r.offs).length});
    if (!r.parts.length || !isAudio(r.parts[0]) || /avc|hev|hvc|vp8|vp9|av01/i.test(r.mime)) continue;   // flussi video
    const out = new Uint8Array(tot); let o = 0;
    for (const b of r.parts) { out.set(new Uint8Array(b), o); o += b.byteLength; }
    cands.push(['mse ' + r.mime, out.buffer]);
  }
  const stats = {dec: C.dec.length, blobs: C.blobs.length, dai: C.dai.length, sbs: sbs};
  if (!cands.length) return JSON.stringify({stats: stats, best: null});
  cands.sort((a, b) => b[1].byteLength - a[1].byteLength);
  window.__scBest = new Uint8Array(cands[0][1]);
  return JSON.stringify({stats: stats, best: {kind: cands[0][0], size: window.__scBest.length}});
})()
"""

    private const val CHUNK = """
(() => {
  const u = window.__scBest;
  if (!u) return '';
  const s = u.subarray(__OFF__, __OFF__ + __LEN__);
  let bin = '';
  for (let i = 0; i < s.length; i += 0x8000) bin += String.fromCharCode.apply(null, s.subarray(i, i + 0x8000));
  return btoa(bin);
})()
"""

    /** Un pezzo dell'audio catturato, in base64 (si legge a pezzi per non intasare il ponte JS). */
    fun chunk(offset: Int, length: Int): String =
        CHUNK.replace("__OFF__", offset.toString()).replace("__LEN__", length.toString())

    const val META = """
(() => {
  const g = (n) => {
    const m = document.querySelector('meta[property="' + n + '"]') || document.querySelector('meta[name="' + n + '"]');
    return (m && m.content) || '';
  };
  return JSON.stringify({title: g('og:title'), image: g('og:image'), desc: g('og:description'), doc: document.title});
})()
"""

    const val ARTIST = """
(() => {
  const a = document.querySelector('main a[href^="/@"]') || document.querySelector('a[href^="/@"]');
  if (!a) return '';
  const t = (a.textContent || '').trim();
  return t || a.getAttribute('href').slice(2).split(/[/?#]/)[0];
})()
"""

    private const val PLAY = """
(() => {
  let list = Array.from(document.querySelectorAll('button[aria-label*="play" i], button[title*="play" i]'));
  if (!list.length) list = Array.from(document.querySelectorAll('button')).filter(b => /(^|\s)play(\s|(?![\s\S]))/i.test(b.textContent || ''));
  if (list.length) {
    try { list[Math.min(__N__, list.length - 1)].click(); } catch (e) {}
    return 'click';
  }
  const ev = {key: ' ', code: 'Space', keyCode: 32, which: 32, bubbles: true};
  try { document.body.dispatchEvent(new KeyboardEvent('keydown', ev)); document.body.dispatchEvent(new KeyboardEvent('keyup', ev)); } catch (e) {}
  return 'space';
})()
"""

    fun play(attempt: Int): String = PLAY.replace("__N__", attempt.toString())

    const val SCROLL = """
(() => {
  window.scrollTo(0, document.documentElement.scrollHeight);
  const root = document.querySelector('main') || document;
  for (const el of root.querySelectorAll('div,section,ul')) {
    if (el.scrollHeight > el.clientHeight + 50) {
      const oy = getComputedStyle(el).overflowY;
      if (oy === 'auto' || oy === 'scroll') el.scrollTop = el.scrollHeight;
    }
  }
  for (const b of root.querySelectorAll('button')) {
    if (/load more|show more|carica altr|mostra altr/i.test(b.textContent || '')) { try { b.click(); } catch (e) {} }
  }
  return 'ok';
})()
"""

    const val DOM_SONGS = """
(() => {
  const re = /\/song\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;
  const out = {};
  for (const a of document.querySelectorAll('a[href*="/song/"]')) {
    const m = (a.getAttribute('href') || '').match(re);
    if (!m) continue;
    const id = m[1].toLowerCase();
    const it = out[id] || (out[id] = {id: id, title: '', handle: '', image: ''});
    const t = (a.textContent || '').trim();
    if (t && (!it.title || t.length < it.title.length)) it.title = t.slice(0, 200);
    if (!it.image) { const img = a.querySelector('img'); if (img) it.image = img.currentSrc || img.src || ''; }
    if (!it.handle) {
      let el = a.parentElement;
      for (let d = 0; el && d < 8; d++, el = el.parentElement) {
        const ids = new Set();
        for (const x of el.querySelectorAll('a[href*="/song/"]')) {
          const mm = (x.getAttribute('href') || '').match(re); if (mm) ids.add(mm[1].toLowerCase());
        }
        if (ids.size > 1) break;
        const h = el.querySelector('a[href^="/@"]');
        if (h) { it.handle = h.getAttribute('href').slice(2).split(/[/?#]/)[0].toLowerCase(); break; }
      }
    }
  }
  return JSON.stringify(Object.values(out));
})()
"""

    private const val STATE = """
(() => {
  const C = window.__sc;
  if (!C) return JSON.stringify({n: 0});
  const list = Array.from(new Set([].concat(C.media || [], Array.from(document.querySelectorAll('audio,video')))));
  let m = null;
  for (const x of list) {
    const s = x.currentSrc || x.src || '';
    const d = isFinite(x.duration) ? x.duration : 0;
    if (/\/sil-/i.test(s) || d < 5) continue;   // file di silenzio usato da Suno per "sbloccare" l'audio
    if (!m || d > m.duration) m = x;
  }
  const out = {n: list.length, urls: (C.urls || []).slice(-8)};
  if (!m) return JSON.stringify(out);
  if (__BOOST__) {
    // velocita' massima (muto): il player scarica tutto il brano invece di 30-40 s alla volta
    try { m.muted = true; } catch (e) {}
    for (const r of [16, 8, 4]) { try { if (m.playbackRate < r) m.playbackRate = r; break; } catch (e) {} }
    if (m.paused && !m.ended) { try { const p = m.play(); if (p && p.catch) p.catch(() => {}); } catch (e) {} }
  }
  let bufEnd = 0;
  try { for (let i = 0; i < m.buffered.length; i++) bufEnd = Math.max(bufEnd, m.buffered.end(i)); } catch (e) {}
  const src = m.currentSrc || m.src || '';
  out.dur = isFinite(m.duration) ? m.duration : -1;
  out.cur = m.currentTime; out.bufEnd = bufEnd; out.paused = m.paused; out.ended = m.ended; out.rate = m.playbackRate;
  out.src = /^https?:/i.test(src) ? src : '';
  return JSON.stringify(out);
})()
"""

    /** Stato del player (durata, quanto e' gia' scaricato, sorgente); con boost lo manda a 16x. */
    fun state(boost: Boolean): String = STATE.replace("__BOOST__", if (boost) "true" else "false")

    /** Brani raccolti finora dalle risposte JSON. */
    const val CLIPS = """
(() => {
  const C = window.__sc;
  if (!C) return '[]';
  return JSON.stringify(C.order.map(id => C.clips[id]));
})()
"""
}
