/**
 * Проверка распознавания ссылок и выбора лучшего потока. Без сети.
 * Запуск: node --test extension/test
 */
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const S = require('../lib/streams.js');

test('classify: HLS и DASH по расширению, типу ответа и запросу', () => {
  assert.equal(S.classify('https://cdn.example.com/v/master.m3u8'), 'hls');
  assert.equal(S.classify('https://cdn.example.com/v/master.m3u8?token=abc'), 'hls');
  assert.equal(S.classify('https://cdn.example.com/v/stream', { contentType: 'application/vnd.apple.mpegurl; charset=utf-8' }), 'hls');
  assert.equal(S.classify('https://cdn.example.com/hls/master.m3u8/index'), 'hls');
  assert.equal(S.classify('https://cdn.example.com/play?format=m3u8'), 'hls');
  assert.equal(S.classify('https://cdn.example.com/v/manifest.mpd'), 'dash');
  assert.equal(S.classify('https://cdn.example.com/v/x', { contentType: 'application/dash+xml' }), 'dash');
});

test('classify: обычный файл', () => {
  assert.equal(S.classify('https://example.com/movie.mp4'), 'progressive');
  assert.equal(S.classify('https://example.com/movie.webm', { requestType: 'media' }), 'progressive');
  assert.equal(S.classify('https://example.com/get?id=5', { contentType: 'video/mp4', requestType: 'media' }), 'progressive');
  assert.equal(S.classify('https://example.com/file.mp4', { contentType: 'application/octet-stream', requestType: 'media' }), 'progressive');
  assert.equal(S.classify('https://example.com/initial-cut.mp4'), 'progressive');
});

test('classify: сегменты и кусочки не считаются видео целиком', () => {
  assert.equal(S.classify('https://cdn.example.com/v/seg-12.ts'), 'segment');
  assert.equal(S.classify('https://cdn.example.com/v/chunk_5.m4s'), 'segment');
  assert.equal(S.classify('https://cdn.example.com/v/x', { contentType: 'video/mp2t' }), 'segment');
  assert.equal(S.classify('https://cdn.example.com/v/init.mp4'), 'segment');
  assert.equal(S.classify('https://cdn.example.com/v/segment-3.mp4'), 'segment');
  assert.equal(S.classify('https://r1.example.com/videoplayback?mime=video%2Fmp4&range=0-1000', { contentType: 'video/mp4', requestType: 'xmlhttprequest' }), 'segment');
  // Маленький mp4 через XHR это кусочек плеера на MSE.
  assert.equal(S.classify('https://cdn.example.com/v/part.mp4', { contentType: 'video/mp4', requestType: 'xmlhttprequest', contentLength: 900000 }), 'segment');
  // Большой mp4 через XHR это файл целиком.
  assert.equal(S.classify('https://cdn.example.com/v/film.mp4', { contentType: 'video/mp4', requestType: 'xmlhttprequest', contentLength: 900 * 1024 * 1024 }), 'progressive');
});

test('classify: не видео', () => {
  assert.equal(S.classify('https://example.com/app.js'), null);
  assert.equal(S.classify('https://example.com/a.png', { contentType: 'image/png' }), null);
  assert.equal(S.classify('https://example.com/movie.mp4', { contentType: 'text/html' }), null);
  assert.equal(S.classify('blob:https://example.com/123'), null);
  assert.equal(S.classify('data:video/mp4;base64,AAAA'), null);
  assert.equal(S.classify('ftp://example.com/movie.mp4'), null);
  assert.equal(S.classify('not a url'), null);
  assert.equal(S.classify('https://example.com/subs/ru.vtt'), 'subtitle');
  assert.equal(S.isSendable('subtitle'), false);
  assert.equal(S.isSendable('segment'), false);
});

test('looksLikeLicense: серверы лицензий DRM', () => {
  assert.equal(S.looksLikeLicense('https://lic.example.com/widevine/getLicense', 'POST'), true);
  assert.equal(S.looksLikeLicense('https://example.com/api/license', 'POST'), true);
  assert.equal(S.looksLikeLicense('https://example.com/playready?x=1', 'GET'), true);
  assert.equal(S.looksLikeLicense('https://example.com/license.txt', 'GET'), false);
  assert.equal(S.looksLikeLicense('https://example.com/api/licenses-page', 'POST'), false);
});

test('pickBest: главный плейлист HLS важнее вариантов, аудио и субтитров', () => {
  const t = 1_000_000;
  const streams = [
    { url: 'https://cdn.example.com/v/master.m3u8', kind: 'hls', time: t, frameId: 0 },
    { url: 'https://cdn.example.com/v/1080p/chunklist.m3u8', kind: 'hls', time: t + 300, frameId: 0 },
    { url: 'https://cdn.example.com/v/audio/ru.m3u8', kind: 'hls', time: t + 400, frameId: 0 },
    { url: 'https://cdn.example.com/v/subtitles/ru.m3u8', kind: 'hls', time: t + 450, frameId: 0 },
  ];
  assert.equal(S.pickBest(streams).url, 'https://cdn.example.com/v/master.m3u8');
});

test('pickBest: первый из пары плейлистов без подсказок в имени', () => {
  const t = 2_000_000;
  const streams = [
    { url: 'https://cdn.example.com/abc/xyz.m3u8', kind: 'hls', time: t, frameId: 0 },
    { url: 'https://cdn.example.com/abc/xyz_720.m3u8', kind: 'hls', time: t + 200, frameId: 0 },
  ];
  assert.equal(S.pickBest(streams).url, 'https://cdn.example.com/abc/xyz.m3u8');
});

test('pickBest: совпадение с currentSrc и кадр видео решают', () => {
  const t = 3_000_000;
  const streams = [
    { url: 'https://ads.example.net/promo.mp4', kind: 'progressive', time: t, frameId: 0, requestType: 'media', contentLength: 3e6 },
    { url: 'https://cdn.example.com/film.mp4', kind: 'progressive', time: t - 1000, frameId: 4, requestType: 'media', contentLength: 2e9 },
  ];
  assert.equal(S.pickBest(streams, { videoSrc: 'https://cdn.example.com/film.mp4' }).url, 'https://cdn.example.com/film.mp4');
  assert.equal(S.pickBest(streams, { frameId: 4 }).url, 'https://cdn.example.com/film.mp4');
});

test('pickBest: манифест важнее файла, старое уступает свежему', () => {
  const t = 4_000_000;
  const streams = [
    { url: 'https://cdn.example.com/old/master.m3u8', kind: 'hls', time: t - 10 * 60 * 1000, frameId: 0 },
    { url: 'https://cdn.example.com/new/master.m3u8', kind: 'hls', time: t, frameId: 0 },
    { url: 'https://cdn.example.com/trailer.mp4', kind: 'progressive', time: t, frameId: 0, requestType: 'media' },
  ];
  const ranked = S.rank(streams);
  assert.equal(ranked[0].url, 'https://cdn.example.com/new/master.m3u8');
  assert.equal(ranked[1].url, 'https://cdn.example.com/old/master.m3u8');
});

test('rank: сегменты отброшены, дубли схлопнуты', () => {
  const streams = [
    { url: 'https://cdn.example.com/a.m3u8', kind: 'hls', time: 1 },
    { url: 'https://cdn.example.com/a.m3u8', kind: 'hls', time: 5 },
    { url: 'https://cdn.example.com/s1.ts', kind: 'segment', time: 6 },
  ];
  const ranked = S.rank(streams);
  assert.equal(ranked.length, 1);
  assert.equal(ranked[0].time, 5);
  assert.equal(S.pickBest([]), null);
});

test('protocolType и shortName', () => {
  assert.equal(S.protocolType('hls'), 'hls');
  assert.equal(S.protocolType('segment'), 'auto');
  assert.equal(S.shortName('https://example.com/films/%D0%9A%D0%B8%D0%BD%D0%BE.mp4?x=1'), 'Кино.mp4');
  assert.equal(S.sameStream('https://a.com/v.mp4?t=1', 'https://a.com/v.mp4?t=2'), true);
  assert.equal(S.sameStream('https://a.com/v.mp4', 'https://b.com/v.mp4'), false);
});
