/**
 * Скрипт страницы (во всех кадрах): сообщает фоновому скрипту о видео в этом кадре,
 * ставит видео на паузу после отправки и показывает короткую подсказку.
 * Ничего на странице не меняет, пока его не попросят.
 */
(function () {
  'use strict';
  const ext = globalThis.browser && globalThis.browser.runtime ? globalThis.browser : globalThis.chrome;
  if (!ext || !ext.runtime || globalThis.__lumenPlayContent) return;
  globalThis.__lumenPlayContent = true;

  const keys = new WeakMap();
  let counter = 0;
  let lastPush = 0;

  function keyOf(v) {
    let k = keys.get(v);
    if (!k) {
      k = String(++counter);
      keys.set(v, k);
    }
    return k;
  }

  /** Все <video> кадра, включая открытые shadow root. */
  function videos() {
    const out = Array.from(document.querySelectorAll('video'));
    const walk = rootNode => {
      for (const el of rootNode.querySelectorAll('*')) {
        if (el.shadowRoot) {
          out.push(...el.shadowRoot.querySelectorAll('video'));
          walk(el.shadowRoot);
        }
      }
    };
    if (out.length === 0 && document.body && document.body.childElementCount < 5000) walk(document);
    return out;
  }

  function trackList(v) {
    return Array.from(v.querySelectorAll('track'))
      .filter(t => /^https?:/.test(t.src) && (!t.kind || t.kind === 'subtitles' || t.kind === 'captions'))
      .map(t => ({ url: t.src, lang: t.srclang || '', label: t.label || t.srclang || '' }));
  }

  function titleFor(v) {
    const own = v.getAttribute('title') || v.getAttribute('aria-label');
    if (own) return own.trim();
    let t = document.title;
    if (window !== window.top) {
      // Во фрейме заголовок часто пустой или технический.
      if (!t || t.length < 3) t = '';
    }
    return (t || '').trim();
  }

  function describe(v) {
    const src = v.currentSrc || v.src || '';
    const r = v.getBoundingClientRect();
    return {
      key: keyOf(v),
      src: /^https?:/.test(src) ? src : '',
      blob: src.startsWith('blob:') || (!src && v.readyState > 0),
      currentTime: Number.isFinite(v.currentTime) ? v.currentTime : 0,
      duration: Number.isFinite(v.duration) ? v.duration : (v.duration === Infinity ? -1 : 0),
      paused: v.paused,
      playing: !v.paused && !v.ended && v.readyState > 2,
      width: v.videoWidth || 0,
      height: v.videoHeight || 0,
      area: Math.round(Math.max(0, r.width) * Math.max(0, r.height)),
      muted: v.muted,
      title: titleFor(v),
      tracks: trackList(v),
      drm: !!v.mediaKeys,
      pageUrl: location.href,
    };
  }

  function report() {
    const list = videos()
      .filter(v => v.readyState > 0 || v.currentSrc || v.src)
      .map(describe)
      // Крошечные превью при наведении не нужны.
      .filter(d => d.area > 160 * 90 || d.playing || d.width >= 320);
    if (list.length === 0) return;
    try {
      ext.runtime.sendMessage({ type: 'videos', videos: list, top: window === window.top });
    } catch (e) {
      // расширение перезагрузили: этот скрипт уже не нужен
    }
  }

  // События видео не всплывают, но ловятся на погружении.
  document.addEventListener('playing', () => {
    const now = Date.now();
    if (now - lastPush > 1500) {
      lastPush = now;
      report();
    }
  }, true);

  function findByKey(key) {
    return videos().find(v => keys.get(v) === key) || null;
  }

  function toast(text, isError) {
    if (window !== window.top || !document.body) return;
    const old = document.getElementById('__lumen-play-toast');
    if (old) old.remove();
    const el = document.createElement('div');
    el.id = '__lumen-play-toast';
    el.textContent = text;
    el.setAttribute('role', 'status');
    el.style.cssText = [
      'position:fixed', 'z-index:2147483647', 'right:16px', 'bottom:16px', 'max-width:360px',
      'padding:10px 14px', 'border-radius:10px', 'font:14px/1.4 system-ui,-apple-system,sans-serif',
      'color:#fff', 'box-shadow:0 4px 18px rgba(0,0,0,.35)', 'pointer-events:none',
      'background:' + (isError ? '#b3261e' : '#1f6f43'),
    ].join(';');
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 4000);
  }

  ext.runtime.onMessage.addListener(msg => {
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'report') report();
    else if (msg.type === 'pause') {
      const v = msg.key ? findByKey(msg.key) : null;
      (v ? [v] : videos().filter(x => !x.paused)).forEach(x => { try { x.pause(); } catch (e) {} });
    } else if (msg.type === 'toast') toast(msg.text, msg.error);
  });
})();
