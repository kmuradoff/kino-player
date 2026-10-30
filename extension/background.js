/**
 * Фоновый скрипт: следит за видеозапросами вкладок, собирает сведения о видео со страниц
 * и отправляет выбранное на проектор (popup, контекстное меню, Alt+Shift+L).
 *
 * В Chromium это service worker (библиотеки грузятся через importScripts), в Firefox это
 * фоновая страница, и библиотеки перечислены в manifest.json.
 */
'use strict';
if (typeof importScripts === 'function' && typeof LumenStreams === 'undefined') {
  importScripts('lib/shim.js', 'lib/streams.js', 'lib/projector.js');
}

const MAX_STREAMS_PER_TAB = 40;
const MAX_PENDING = 500;
const REPORT_WAIT_MS = 350;
const SESSION_KEY = 'tabs';
/** Заголовки, которые нужны потоку. Куки не передаются. */
const KEEP_HEADERS = ['referer', 'origin', 'user-agent'];

/** tabId -> {streams: [...], drm: время последнего запроса лицензии или 0} */
let tabs = {};
/** tabId -> {frameId -> {videos, at}}; живёт только в памяти, собирается заново при каждом запросе. */
const frames = {};
/** requestId -> заголовки, которые браузер отправил */
const pending = new Map();

const ready = (async () => {
  try {
    if (ext.storage.session) {
      const r = await ext.storage.session.get(SESSION_KEY);
      if (r && r[SESSION_KEY]) tabs = Object.assign(r[SESSION_KEY], tabs);
    }
  } catch (e) {
    // нет storage.session: держим всё в памяти
  }
})();

let saveTimer = null;
function persist() {
  if (!ext.storage.session || saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    ext.storage.session.set({ [SESSION_KEY]: tabs }).catch(() => {});
  }, 500);
}

function tabState(tabId) {
  if (!tabs[tabId]) tabs[tabId] = { streams: [], drm: 0 };
  return tabs[tabId];
}

function pickHeaders(list) {
  const out = {};
  for (const h of list || []) {
    const name = (h.name || '').toLowerCase();
    if (KEEP_HEADERS.includes(name) && h.value) {
      out[name === 'user-agent' ? 'User-Agent' : name === 'referer' ? 'Referer' : 'Origin'] = h.value;
    }
  }
  return out;
}

function headerValue(list, name) {
  const h = (list || []).find(x => (x.name || '').toLowerCase() === name);
  return h ? h.value : '';
}

/** Полный размер файла: из Content-Range "bytes 0-1/12345", иначе Content-Length. */
function totalSize(list) {
  const range = headerValue(list, 'content-range');
  const m = /\/(\d+)\s*$/.exec(range || '');
  if (m) return Number(m[1]);
  return Number(headerValue(list, 'content-length')) || 0;
}

// ---- Наблюдение за запросами ----

const WATCH = { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other', 'object'] };
const sendOpts = ['requestHeaders'];
// Chromium отдаёт Referer и Origin только с extraHeaders; Firefox такой опции не знает.
if (ext.webRequest.OnBeforeSendHeadersOptions && ext.webRequest.OnBeforeSendHeadersOptions.EXTRA_HEADERS) {
  sendOpts.push('extraHeaders');
}

ext.webRequest.onBeforeSendHeaders.addListener(d => {
  if (d.tabId < 0) return;
  if (pending.size > MAX_PENDING) pending.clear();
  pending.set(d.requestId, pickHeaders(d.requestHeaders));
  if (LumenStreams.looksLikeLicense(d.url, d.method)) {
    ready.then(() => {
      tabState(d.tabId).drm = Date.now();
      persist();
    });
  }
}, WATCH, sendOpts);

ext.webRequest.onHeadersReceived.addListener(d => {
  if (d.tabId < 0) return;
  const sent = pending.get(d.requestId);
  if (d.statusCode >= 300 && d.statusCode < 400) return; // за редиректом придёт настоящий ответ
  pending.delete(d.requestId);
  if (d.statusCode >= 400) return;
  const kind = LumenStreams.classify(d.url, {
    contentType: headerValue(d.responseHeaders, 'content-type'),
    requestType: d.type,
    contentLength: totalSize(d.responseHeaders),
  });
  if (!LumenStreams.isSendable(kind)) return;
  const entry = {
    url: d.url,
    kind,
    time: Date.now(),
    frameId: d.frameId,
    requestType: d.type,
    contentLength: totalSize(d.responseHeaders),
    headers: sent || {},
  };
  ready.then(() => {
    const st = tabState(d.tabId);
    st.streams = st.streams.filter(s => s.url !== entry.url);
    st.streams.push(entry);
    if (st.streams.length > MAX_STREAMS_PER_TAB) st.streams.splice(0, st.streams.length - MAX_STREAMS_PER_TAB);
    persist();
  });
}, WATCH, ['responseHeaders']);

ext.webRequest.onErrorOccurred.addListener(d => pending.delete(d.requestId), WATCH);

// Новая страница во вкладке: прошлые потоки больше не нужны.
ext.webRequest.onBeforeRequest.addListener(d => {
  if (d.tabId < 0) return;
  ready.then(() => {
    delete tabs[d.tabId];
    delete frames[d.tabId];
    persist();
  });
}, { urls: ['<all_urls>'], types: ['main_frame'] });

ext.tabs.onRemoved.addListener(tabId => {
  ready.then(() => {
    delete tabs[tabId];
    delete frames[tabId];
    persist();
  });
});

// ---- Видео со страницы ----

ext.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'videos') {
    if (!sender.tab || sender.id !== ext.runtime.id) return;
    const t = (frames[sender.tab.id] = frames[sender.tab.id] || {});
    t[sender.frameId || 0] = { videos: Array.isArray(msg.videos) ? msg.videos.slice(0, 20) : [], at: Date.now() };
    return;
  }
  if (sender.id !== ext.runtime.id || sender.tab) return; // остальное только из окна расширения
  if (msg.type === 'collect') {
    collect(msg.tabId).then(sendResponse, e => sendResponse({ error: e.message, items: [] }));
    return true;
  }
  if (msg.type === 'send') {
    sendItem(msg.tabId, msg.item).then(() => sendResponse({ ok: true }), e => sendResponse({ ok: false, code: e.code, message: LumenProjector.describe(e) }));
    return true;
  }
});

function wait(ms) {
  return new Promise(r => setTimeout(r, ms));
}

/** Просит все кадры вкладки рассказать о своих видео и ждёт ответы. */
async function gatherVideos(tabId) {
  frames[tabId] = {};
  try {
    await ext.tabs.sendMessage(tabId, { type: 'report' });
  } catch (e) {
    // на странице нет скрипта (служебная страница или вкладка открыта до установки)
  }
  await wait(REPORT_WAIT_MS);
  const out = [];
  for (const [frameId, f] of Object.entries(frames[tabId] || {})) {
    for (const v of f.videos) out.push(Object.assign({}, v, { frameId: Number(frameId) }));
  }
  // Играющее сейчас первым, потом самое крупное на экране.
  out.sort((a, b) => (b.playing - a.playing) || (b.area - a.area));
  return out;
}

/** Вариант для списка: что отправлять и как это подписать. */
function itemFor(video, stream, tabTitle) {
  const url = stream ? stream.url : video && video.src;
  return {
    id: (video ? video.frameId + ':' + video.key : '') + '|' + (url || ''),
    url: url || '',
    kind: stream ? stream.kind : (url ? LumenStreams.classify(url) || 'progressive' : null),
    headers: stream ? stream.headers : {},
    title: (video && video.title) || tabTitle || LumenStreams.shortName(url),
    fileName: LumenStreams.shortName(url),
    pageUrl: (video && video.pageUrl) || '',
    frameId: video ? video.frameId : (stream ? stream.frameId : 0),
    videoKey: video ? video.key : null,
    position: video && video.duration > 0 ? video.currentTime : 0,
    duration: video ? video.duration : 0,
    width: video ? video.width : 0,
    height: video ? video.height : 0,
    playing: !!(video && video.playing),
    subtitles: video ? video.tracks : [],
    drm: !!(video && video.drm),
  };
}

/**
 * Всё, что можно отправить с вкладки: сначала видео страницы (играющее первым) со своим
 * лучшим потоком, затем остальные найденные потоки.
 */
async function collect(tabId) {
  await ready;
  let tabTitle = '';
  let tabUrl = '';
  try {
    const tab = await ext.tabs.get(tabId);
    tabTitle = tab.title || '';
    tabUrl = tab.url || '';
  } catch (e) {
    // вкладка закрыта
  }
  const videos = await gatherVideos(tabId);
  const st = tabState(tabId);
  const streams = st.streams;
  const items = [];
  const used = new Set();

  for (const v of videos) {
    const own = v.src ? LumenStreams.classify(v.src) : null;
    let stream = null;
    if (v.src && LumenStreams.isSendable(own)) {
      // У видео прямая ссылка: берём заголовки из перехваченного запроса, если он был.
      const seen = streams.find(s => LumenStreams.sameStream(s.url, v.src));
      stream = seen || { url: v.src, kind: own, headers: {}, frameId: v.frameId };
    } else if (!v.drm) {
      stream = LumenStreams.pickBest(streams.filter(s => !used.has(s.url)), { videoSrc: v.src, frameId: v.frameId });
    }
    const item = itemFor(v, stream, tabTitle);
    if (!item.headers.Referer && item.url) item.headers = Object.assign({ Referer: v.pageUrl || tabUrl }, item.headers);
    if (!item.url && !item.drm) continue;
    if (item.url && used.has(item.url)) continue;
    if (item.url) used.add(item.url);
    items.push(item);
  }

  const drmRecent = !!st.drm && Date.now() - st.drm < 10 * 60 * 1000;
  // При DRM лишние потоки не показываем: это зашифрованные манифесты, проектор их не сыграет.
  const anyDrm = drmRecent || items.some(i => i.drm);
  const rest = anyDrm ? [] : LumenStreams.rank(streams.filter(s => !used.has(s.url)), {}).slice(0, 5);
  for (const s of rest) {
    const item = Object.assign(itemFor(null, s, tabTitle), { extra: true, pageUrl: tabUrl });
    if (!item.headers.Referer && tabUrl) item.headers = Object.assign({ Referer: tabUrl }, item.headers);
    items.push(item);
  }

  return { items, drm: anyDrm, title: tabTitle, url: tabUrl };
}

function fail(code) {
  return new LumenProjector.ProjectorError(code, code);
}

/** Отправляет вариант на проектор и ставит видео на странице на паузу. */
async function sendItem(tabId, item) {
  if (!item) throw fail('no_video');
  if (item.drm) throw fail('drm');
  if (!item.url || !/^https?:\/\//i.test(item.url)) throw fail('no_video');
  const cfg = await LumenProjector.load();
  if (!cfg.address) throw fail('not_configured');
  if (!cfg.token) throw fail('not_paired');

  const headers = {};
  for (const [k, v] of Object.entries(item.headers || {})) {
    if (KEEP_HEADERS.includes(k.toLowerCase()) && typeof v === 'string' && v) headers[k] = v;
  }
  if (!headers['User-Agent'] && typeof navigator !== 'undefined') headers['User-Agent'] = navigator.userAgent;

  const payload = {
    url: item.url,
    type: LumenStreams.protocolType(item.kind),
    headers,
    title: (item.title || '').slice(0, 300),
    pageUrl: item.pageUrl || '',
    position: Math.max(0, Math.floor((item.position || 0) * 10) / 10),
    subtitles: (item.subtitles || []).filter(s => /^https?:\/\//i.test(s.url)).slice(0, 10),
  };
  try {
    await LumenProjector.play(cfg.address, cfg.token, payload);
  } catch (e) {
    if (e.code === 'unauthorized') await LumenProjector.save({ token: '' });
    throw e;
  }
  try {
    await ext.tabs.sendMessage(tabId, { type: 'pause', key: item.videoKey }, { frameId: item.frameId || 0 });
  } catch (e) {
    // кадр уже ушёл: не страшно
  }
}

// ---- Отправка без окна: контекстное меню и горячая клавиша ----

async function notify(tabId, ok, text) {
  try {
    await ext.action.setBadgeBackgroundColor({ color: ok ? '#1f6f43' : '#b3261e', tabId });
    await ext.action.setBadgeText({ text: ok ? 'OK' : '!', tabId });
    setTimeout(() => ext.action.setBadgeText({ text: '', tabId }).catch(() => {}), 4000);
  } catch (e) {
    // значок недоступен
  }
  try {
    await ext.tabs.sendMessage(tabId, { type: 'toast', text, error: !ok }, { frameId: 0 });
  } catch (e) {
    // скрипта на странице нет
  }
}

/** Отправить лучшее с вкладки; [frameId] и [srcUrl] уточняют видео из контекстного меню. */
async function quickSend(tabId, frameId, srcUrl) {
  try {
    const { items, drm } = await collect(tabId);
    let item = null;
    if (frameId != null) {
      item = items.find(i => !i.extra && i.frameId === frameId && srcUrl && LumenStreams.sameStream(i.url, srcUrl))
        || items.find(i => !i.extra && i.frameId === frameId);
    }
    item = item || items[0];
    if (!item) throw fail(drm ? 'drm' : 'no_video');
    await sendItem(tabId, item);
    await notify(tabId, true, 'Отправлено на проектор: ' + (item.title || item.fileName));
  } catch (e) {
    await notify(tabId, false, LumenProjector.describe(e));
  }
}

const MENU_ID = 'lumen-send-video';
ext.runtime.onInstalled.addListener(() => {
  Promise.resolve(ext.contextMenus.removeAll()).catch(() => {}).then(() => {
    ext.contextMenus.create({ id: MENU_ID, title: 'Отправить на Lumen Play', contexts: ['video'] });
  });
});

ext.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== MENU_ID || !tab || tab.id < 0) return;
  quickSend(tab.id, info.frameId || 0, info.srcUrl);
});

ext.commands.onCommand.addListener(async (command, tab) => {
  if (command !== 'send-video') return;
  let t = tab;
  if (!t) {
    const list = await ext.tabs.query({ active: true, currentWindow: true });
    t = list[0];
  }
  if (t && t.id >= 0) quickSend(t.id);
});
