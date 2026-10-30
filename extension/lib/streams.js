/**
 * Распознавание видеопотоков по запросам вкладки и выбор лучшего для отправки.
 * Без обращений к сети и к API браузера: тот же файл грузит фоновый скрипт и node-тест.
 */
(function (root) {
  'use strict';

  const HLS_TYPES = ['application/x-mpegurl', 'application/vnd.apple.mpegurl', 'audio/mpegurl', 'audio/x-mpegurl'];
  const DASH_TYPES = ['application/dash+xml'];
  const PROGRESSIVE_TYPES = ['video/mp4', 'video/webm', 'video/x-matroska', 'video/quicktime', 'video/ogg', 'video/x-m4v'];
  const SEGMENT_TYPES = ['video/mp2t', 'video/iso.segment', 'audio/aac', 'audio/mp4'];
  const SUBTITLE_TYPES = ['text/vtt', 'application/x-subrip', 'text/srt'];

  /** Запросы <video src> в Chrome и Firefox приходят с типом "media". */
  const MEDIA_REQUEST = 'media';
  /** Обычный файл, найденный через XHR/fetch, считается видео только от этого размера. */
  const MIN_XHR_PROGRESSIVE = 20 * 1024 * 1024;
  /** Поток старше самого свежего на столько считается остатком прошлого видео. */
  const STALE_MS = 2 * 60 * 1000;

  function mimeOf(contentType) {
    return String(contentType || '').toLowerCase().split(';')[0].trim();
  }

  function parse(url) {
    try {
      const u = new URL(url);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
      return u;
    } catch (e) {
      return null;
    }
  }

  function extOf(pathname) {
    const last = pathname.toLowerCase().split('/').pop() || '';
    const dot = last.lastIndexOf('.');
    return dot < 0 ? '' : last.slice(dot + 1);
  }

  /** Признаки кусочка потока (сегмент, диапазон байтов), а не целого видео. */
  function looksLikeSegment(u) {
    const p = u.pathname.toLowerCase();
    const q = u.search.toLowerCase();
    if (/\.(ts|m4s|m4a|m4f|cmfv|cmfa|aac)$/.test(p)) return true;
    if (/(^|[\/_\-.])(seg|segment|chunk|frag|fragment|init)[\-_]?\d*(\.|\/|$)/.test(p)) return true;
    if (/[?&](range|bytes|sq|segment)=/.test(q)) return true;
    if (/\/range\/\d+-\d*/.test(p)) return true;
    return false;
  }

  /**
   * Тип ссылки: "hls", "dash", "progressive", "segment", "subtitle" или null (не видео).
   * [info]: {contentType, requestType, contentLength}; всё необязательно.
   */
  function classify(url, info) {
    const u = parse(url);
    if (!u) return null;
    const ct = mimeOf(info && info.contentType);
    const reqType = info && info.requestType;
    const size = Number(info && info.contentLength) || 0;
    const ext = extOf(u.pathname);
    const p = u.pathname.toLowerCase();
    const q = u.search.toLowerCase();

    if (HLS_TYPES.includes(ct) || ext === 'm3u8' || p.includes('.m3u8/')) return 'hls';
    if (DASH_TYPES.includes(ct) || ext === 'mpd' || p.includes('.mpd/')) return 'dash';
    if (/(^|[?&])(format|type|ext)=(m3u8|hls)(&|$)/.test(q)) return 'hls';
    if (/(^|[?&])(format|type|ext)=(mpd|dash)(&|$)/.test(q)) return 'dash';
    if (SUBTITLE_TYPES.includes(ct) || ext === 'vtt' || ext === 'srt') return 'subtitle';
    if (SEGMENT_TYPES.includes(ct) || ext === 'ts' || ext === 'm4s') return 'segment';

    const videoByType = PROGRESSIVE_TYPES.includes(ct);
    const videoByExt = ['mp4', 'webm', 'mkv', 'mov', 'm4v', 'ogv'].includes(ext);
    if (!videoByType && !videoByExt) return null;
    if (ct && !videoByType && !ct.startsWith('video/') && ct !== 'application/octet-stream') return null;
    if (looksLikeSegment(u)) return 'segment';
    if (reqType && reqType !== MEDIA_REQUEST && reqType !== 'other' && reqType !== 'object') {
      // Плееры на MSE тянут кусочки mp4 через XHR; целым файлом считаем только большой ответ.
      return size >= MIN_XHR_PROGRESSIVE ? 'progressive' : 'segment';
    }
    return 'progressive';
  }

  /** Подходит ли тип для отправки на проектор. */
  function isSendable(kind) {
    return kind === 'hls' || kind === 'dash' || kind === 'progressive';
  }

  /** Признаки DRM в запросе: сервер лицензий Widevine, PlayReady, FairPlay. */
  function looksLikeLicense(url, method) {
    const u = parse(url);
    if (!u) return false;
    const s = (u.hostname + u.pathname + u.search).toLowerCase();
    if (/widevine|playready|fairplay|clearkey|drmtoday|ezdrm|axinom|buydrm|vualto|irdeto|expressplay/.test(s)) return true;
    return String(method || '').toUpperCase() === 'POST' && /(^|[\/_\-.])(license|licence|drm|getlicense)([\/_\-.?]|$)/.test(s);
  }

  /** Имя файла из ссылки для подписи в списке. */
  function shortName(url) {
    const u = parse(url);
    if (!u) return '';
    const parts = u.pathname.split('/').filter(Boolean);
    const last = decodeURIComponent(parts.pop() || u.hostname);
    return last.length > 48 ? last.slice(0, 45) + '...' : last;
  }

  function sameStream(a, b) {
    if (!a || !b) return false;
    if (a === b) return true;
    const ua = parse(a);
    const ub = parse(b);
    return !!ua && !!ub && ua.origin === ub.origin && ua.pathname === ub.pathname;
  }

  /**
   * Очки потока: чем больше, тем вероятнее это то самое видео целиком.
   * [stream]: {url, kind, time, frameId, requestType, contentLength}.
   * [ctx]: {all: все потоки вкладки, videoSrc: currentSrc играющего видео, frameId: его кадр}.
   */
  function score(stream, ctx) {
    const c = ctx || {};
    const all = c.all || [stream];
    const p = (parse(stream.url) || { pathname: '' }).pathname.toLowerCase();
    let s = 0;

    if (stream.kind === 'hls' || stream.kind === 'dash') {
      s += 100;
      if (/master|manifest|playlist|main|index/.test(p) && !/chunklist|variant/.test(p)) s += 20;
      if (/chunklist|variant|media[_\-]?\d|\/(\d{3,4}p?|[\d.]+k?bps)\//.test(p)) s -= 15;
      if (/audio|aac|mp4a|\/a\d*\//.test(p)) s -= 40;
      if (/subtitle|subs?[_\-\/]|vtt|caption/.test(p)) s -= 60;
      // Плеер сначала грузит главный плейлист, потом варианты: тот, что пришёл перед другим, вероятнее главный.
      const followed = all.some(o => o !== stream && o.kind === stream.kind && o.time > stream.time && o.time - stream.time < 15000);
      if (followed) s += 12;
    } else if (stream.kind === 'progressive') {
      s += 60;
      if (stream.requestType === MEDIA_REQUEST) s += 20;
      const mb = (Number(stream.contentLength) || 0) / (1024 * 1024);
      if (mb > 0) s += Math.min(20, Math.round(Math.log2(1 + mb) * 2));
    } else {
      return -Infinity;
    }

    if (c.videoSrc && sameStream(stream.url, c.videoSrc)) s += 200;
    if (c.frameId != null && stream.frameId === c.frameId) s += 30;

    const newest = all.reduce((m, o) => Math.max(m, o.time || 0), 0);
    if (newest && stream.time && newest - stream.time > STALE_MS) s -= 30;
    return s;
  }

  /** Отправляемые потоки по убыванию очков; одинаковые ссылки схлопываются в последнюю. */
  function rank(streams, ctx) {
    const byUrl = new Map();
    for (const st of streams || []) {
      if (!isSendable(st.kind)) continue;
      const prev = byUrl.get(st.url);
      if (!prev || (st.time || 0) >= (prev.time || 0)) byUrl.set(st.url, st);
    }
    const list = Array.from(byUrl.values());
    const c = Object.assign({}, ctx, { all: list });
    return list
      .map(st => ({ stream: st, score: score(st, c) }))
      .sort((a, b) => b.score - a.score || (b.stream.time || 0) - (a.stream.time || 0))
      .map(x => Object.assign({}, x.stream, { score: x.score }));
  }

  /** Лучший поток или null. */
  function pickBest(streams, ctx) {
    return rank(streams, ctx)[0] || null;
  }

  /** Тип для протокола из типа классификатора. */
  function protocolType(kind) {
    return kind === 'hls' || kind === 'dash' || kind === 'progressive' ? kind : 'auto';
  }

  const api = {
    classify,
    isSendable,
    looksLikeLicense,
    looksLikeSegment: url => { const u = parse(url); return !!u && looksLikeSegment(u); },
    shortName,
    sameStream,
    score,
    rank,
    pickBest,
    protocolType,
  };

  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.LumenStreams = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
