/**
 * Окно расширения: состояние проектора, видео со страницы, кнопка «На проектор»,
 * управление плеером, настройки адреса и подключение по коду.
 */
'use strict';

const $ = id => document.getElementById(id);
const P = LumenProjector;

let cfg = { address: '', token: '', name: '', version: '' };
let tabId = null;
let online = false;
let statePoll = null;
let lastState = null;
let scanCtrl = null;

function showMessage(text, kind) {
  const el = $('message');
  if (!text) {
    el.hidden = true;
    return;
  }
  el.textContent = text;
  el.className = 'message' + (kind ? ' ' + kind : '');
  el.hidden = false;
}

function fmtTime(sec) {
  if (!Number.isFinite(sec) || sec <= 0) return '0:00';
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60) % 60;
  const h = Math.floor(sec / 3600);
  const mm = h ? String(m).padStart(2, '0') : String(m);
  return (h ? h + ':' : '') + mm + ':' + String(s).padStart(2, '0');
}

function kindLabel(kind) {
  return { hls: 'HLS', dash: 'DASH', progressive: 'Файл' }[kind] || 'Видео';
}

// ---- Проектор ----

function setStatus(state, text) {
  $('dot').className = 'dot' + (state ? ' ' + state : '');
  $('projStatus').textContent = text;
}

async function refreshProjector() {
  $('projName').textContent = cfg.name || 'Проектор';
  if (!cfg.address) {
    online = false;
    setStatus('bad', 'Адрес не указан');
    return;
  }
  try {
    const info = await P.info(cfg.address);
    online = true;
    if (info.name && info.name !== cfg.name) cfg = await P.save({ name: info.name, version: info.version || '' });
    $('projName').textContent = info.name || 'Проектор';
    setStatus(cfg.token ? 'ok' : '', cfg.token ? 'В сети, подключён' : 'В сети, нужно подключиться');
  } catch (e) {
    online = false;
    setStatus('bad', 'Не отвечает: ' + P.displayAddress(cfg.address));
  }
  updatePairUi();
  if (online && cfg.token) startStatePoll();
}

async function pollState() {
  if (!cfg.address || !cfg.token) return;
  try {
    lastState = await P.state(cfg.address, cfg.token);
    renderState();
  } catch (e) {
    if (e.code === 'unauthorized') {
      cfg = await P.save({ token: '' });
      setStatus('', 'В сети, нужно подключиться');
      updatePairUi();
      stopStatePoll();
    }
    $('player').hidden = true;
  }
}

function renderState() {
  const s = lastState;
  const active = s && s.state !== 'stopped' && (s.playing || s.duration > 0 || s.position > 0 || s.title);
  $('player').hidden = !active;
  if (!active) return;
  $('nowTitle').textContent = s.title || 'Lumen Play';
  $('nowPos').textContent = fmtTime(s.position);
  $('nowDur').textContent = s.duration > 0 ? fmtTime(s.duration) : 'эфир';
  $('icoPause').hidden = !s.playing;
  $('icoPlay').hidden = !!s.playing;
  const label = s.playing ? 'Пауза' : 'Играть';
  $('ctlToggle').title = label;
  $('ctlToggle').setAttribute('aria-label', label);
}

function startStatePoll() {
  stopStatePoll();
  pollState();
  statePoll = setInterval(pollState, 2000);
}

function stopStatePoll() {
  if (statePoll) clearInterval(statePoll);
  statePoll = null;
}

async function control(action) {
  try {
    await P.control(cfg.address, cfg.token, action);
    setTimeout(pollState, 300);
  } catch (e) {
    showMessage(P.describe(e), 'error');
  }
}

// ---- Видео со страницы ----

function itemMeta(item) {
  const parts = [];
  const span = (cls, text) => {
    const s = document.createElement('span');
    if (cls) s.className = cls;
    s.textContent = text;
    return s;
  };
  if (item.drm) return [span('drm', 'Защищено DRM, отправить нельзя')];
  if (item.playing) parts.push(span('live', 'идёт сейчас'));
  parts.push(span('', kindLabel(item.kind)));
  if (item.width && item.height) parts.push(span('', item.width + '×' + item.height));
  if (item.duration > 0) parts.push(span('', fmtTime(item.duration)));
  else if (item.duration < 0) parts.push(span('', 'эфир'));
  if (item.extra) parts.push(span('', item.fileName));
  const out = [];
  parts.forEach((p, i) => {
    if (i) out.push(document.createTextNode(' · '));
    out.push(p);
  });
  return out;
}

function renderItems(data) {
  const list = $('items');
  list.textContent = '';
  const items = (data && data.items) || [];
  $('drmNote').hidden = !(data && data.drm && !items.some(i => !i.drm));
  $('empty').hidden = items.length > 0 || !$('drmNote').hidden;
  items.forEach((item, i) => {
    const li = document.createElement('li');
    li.className = 'item' + (i === 0 && !item.drm ? ' first' : '');
    const text = document.createElement('div');
    text.className = 'text';
    const title = document.createElement('div');
    title.className = 'title';
    title.textContent = item.title || item.fileName || 'Видео';
    title.title = item.url || '';
    const meta = document.createElement('div');
    meta.className = 'meta';
    itemMeta(item).forEach(n => meta.appendChild(n));
    text.append(title, meta);
    li.appendChild(text);
    if (!item.drm) {
      const btn = document.createElement('button');
      btn.className = i === 0 ? 'primary' : '';
      btn.textContent = 'На проектор';
      btn.addEventListener('click', () => sendItem(item, btn));
      li.appendChild(btn);
    }
    list.appendChild(li);
  });
}

async function loadItems() {
  if (tabId == null) return;
  $('refreshBtn').disabled = true;
  try {
    const data = await ext.runtime.sendMessage({ type: 'collect', tabId });
    renderItems(data);
  } catch (e) {
    renderItems(null);
  } finally {
    $('refreshBtn').disabled = false;
  }
}

async function sendItem(item, btn) {
  if (!cfg.address || !cfg.token) {
    showMessage(P.describe({ code: cfg.address ? 'not_paired' : 'not_configured' }), 'error');
    openSettings();
    return;
  }
  btn.disabled = true;
  btn.textContent = 'Отправляю...';
  showMessage('');
  const r = await ext.runtime.sendMessage({ type: 'send', tabId, item }).catch(e => ({ ok: false, message: e.message }));
  btn.disabled = false;
  btn.textContent = 'На проектор';
  if (r && r.ok) {
    showMessage('Отправлено на проектор.', 'ok');
    startStatePoll();
  } else {
    showMessage((r && r.message) || 'Не получилось отправить.', 'error');
    if (r && r.code === 'unauthorized') {
      cfg = await P.load();
      updatePairUi();
    }
  }
}

// ---- Настройки ----

function openSettings() {
  $('main').hidden = true;
  $('settings').hidden = false;
  $('address').value = P.displayAddress(cfg.address);
  if (!$('prefix').value) $('prefix').value = P.guessPrefix(cfg.address);
  updatePairUi();
}

function closeSettings() {
  if (scanCtrl) scanCtrl.abort();
  $('settings').hidden = true;
  $('main').hidden = false;
}

function updatePairUi() {
  const paired = !!cfg.token;
  $('pairState').textContent = paired
    ? 'Этот браузер подключён к ' + (cfg.name || 'проектору') + '.'
    : 'Чтобы отправлять видео, подключите этот браузер к проектору по коду с экрана.';
  $('pairBtn').hidden = paired;
  $('unpairBtn').hidden = !paired;
  if (paired) $('codeBox').hidden = true;
}

async function useAddress(address) {
  const b = P.baseUrl(address);
  if (!b) {
    showMessage('Неверный адрес проектора.', 'error');
    return false;
  }
  try {
    const info = await P.info(address);
    if (info.app !== 'Lumen Play') throw new P.ProjectorError('http', 'По этому адресу не Lumen Play.');
    const changed = P.baseUrl(cfg.address) !== b;
    cfg = await P.save({ address: P.displayAddress(address), name: info.name || '', version: info.version || '', token: changed ? '' : cfg.token });
    $('address').value = cfg.address;
    showMessage('Найден ' + (info.name || 'проектор') + ', Lumen Play ' + (info.version || '') + '.', 'ok');
    updatePairUi();
    refreshProjector();
    return true;
  } catch (e) {
    showMessage(P.describe(e), 'error');
    return false;
  }
}

async function scan() {
  if (scanCtrl) {
    scanCtrl.abort();
    return;
  }
  const found = $('found');
  found.textContent = '';
  scanCtrl = new AbortController();
  $('scanBtn').textContent = 'Стоп';
  $('scanProgress').hidden = false;
  $('scanProgress').textContent = 'Ищу...';
  let count = 0;
  try {
    await P.scan($('prefix').value, hit => {
      count++;
      const li = document.createElement('li');
      const label = document.createElement('span');
      label.textContent = (hit.info.name || 'Lumen Play') + ' (' + hit.address + ')';
      const btn = document.createElement('button');
      btn.textContent = 'Выбрать';
      btn.addEventListener('click', () => useAddress(hit.address));
      li.append(label, btn);
      found.appendChild(li);
    }, {
      signal: scanCtrl.signal,
      onProgress: (done, total) => { $('scanProgress').textContent = 'Проверено ' + Math.min(done, total) + ' из ' + total; },
    });
    $('scanProgress').textContent = count ? 'Готово.' : 'В этой подсети Lumen Play не найден. Проверьте подсеть или введите адрес.';
  } catch (e) {
    $('scanProgress').textContent = P.describe(e);
  } finally {
    scanCtrl = null;
    $('scanBtn').textContent = 'Найти';
  }
}

async function startPair() {
  const typed = $('address').value.trim();
  if (typed && P.baseUrl(typed) !== P.baseUrl(cfg.address)) {
    if (!(await useAddress(typed))) return;
  }
  if (!cfg.address) {
    showMessage(P.describe({ code: 'not_configured' }), 'error');
    return;
  }
  try {
    await P.pair(cfg.address, P.clientName());
    $('codeBox').hidden = false;
    $('code').value = '';
    $('code').focus();
    showMessage('');
  } catch (e) {
    showMessage(P.describe(e), 'error');
  }
}

async function confirmPair() {
  const code = $('code').value.trim();
  if (!/^\d{4}$/.test(code)) {
    showMessage('Нужно 4 цифры.', 'error');
    return;
  }
  try {
    const r = await P.confirm(cfg.address, code);
    if (!r.token) throw new P.ProjectorError('http', 'Проектор не выдал ключ.');
    cfg = await P.save({ token: r.token });
    showMessage('Подключено. Теперь можно отправлять видео.', 'ok');
    updatePairUi();
    refreshProjector();
  } catch (e) {
    showMessage(P.describe(e), 'error');
  }
}

async function unpair() {
  cfg = await P.save({ token: '' });
  stopStatePoll();
  $('player').hidden = true;
  showMessage('Браузер отключён. Ключ можно удалить и на проекторе в настройках Lumen Play.', '');
  updatePairUi();
  refreshProjector();
}

// ---- Доступ к сайтам (Firefox выдаёт его только по запросу) ----

const ORIGINS = { origins: ['<all_urls>'] };

async function checkPermission() {
  try {
    const ok = await ext.permissions.contains(ORIGINS);
    $('perm').hidden = ok;
    return ok;
  } catch (e) {
    return true;
  }
}

// ---- Старт ----

async function init() {
  $('settingsBtn').addEventListener('click', () => ($('settings').hidden ? openSettings() : closeSettings()));
  $('backBtn').addEventListener('click', closeSettings);
  $('refreshBtn').addEventListener('click', loadItems);
  $('checkBtn').addEventListener('click', () => useAddress($('address').value));
  $('address').addEventListener('keydown', e => { if (e.key === 'Enter') useAddress($('address').value); });
  $('scanBtn').addEventListener('click', scan);
  $('pairBtn').addEventListener('click', startPair);
  $('confirmBtn').addEventListener('click', confirmPair);
  $('code').addEventListener('keydown', e => { if (e.key === 'Enter') confirmPair(); });
  $('unpairBtn').addEventListener('click', unpair);
  $('ctlToggle').addEventListener('click', () => control(lastState && lastState.playing ? 'pause' : 'play'));
  $('ctlStop').addEventListener('click', () => control('stop'));
  $('permBtn').addEventListener('click', async () => {
    // Запрос разрешения должен идти прямо из нажатия, без await перед ним.
    const granted = await ext.permissions.request(ORIGINS).catch(() => false);
    if (granted) {
      $('perm').hidden = true;
      loadItems();
    }
  });

  cfg = await P.load();
  try {
    const [tab] = await ext.tabs.query({ active: true, currentWindow: true });
    tabId = tab ? tab.id : null;
  } catch (e) {
    tabId = null;
  }
  await checkPermission();
  if (!cfg.address) openSettings();
  refreshProjector();
  loadItems();
}

window.addEventListener('unload', () => {
  stopStatePoll();
  if (scanCtrl) scanCtrl.abort();
});

init();
