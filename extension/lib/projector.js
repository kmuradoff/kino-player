/**
 * Связь с Lumen Play на проекторе по протоколу docs/send-protocol.md.
 * Используется и фоновым скриптом, и окном расширения.
 */
(function (root) {
  'use strict';

  const PORT = 8765;
  const STORE_KEY = 'projector';

  /** Ошибка запроса с кодом: unreachable, unauthorized, bad_code, no_pairing, locked, bad_request, http. */
  class ProjectorError extends Error {
    constructor(code, message, extra) {
      super(message || code);
      this.code = code;
      Object.assign(this, extra || {});
    }
  }

  /**
   * "192.168.1.50", "192.168.1.50:8765" или "http://192.168.1.50:8765/" -> "http://192.168.1.50:8765".
   * null, если адрес не похож на адрес в сети.
   */
  function baseUrl(address) {
    let a = String(address || '').trim();
    if (!a) return null;
    if (!/^https?:\/\//i.test(a)) a = 'http://' + a;
    let u;
    try {
      u = new URL(a);
    } catch (e) {
      return null;
    }
    if (u.protocol !== 'http:' || !u.hostname || u.username || u.password) return null;
    return 'http://' + u.hostname + ':' + (u.port || PORT);
  }

  /** Короткая запись для поля ввода: без схемы и без порта по умолчанию. */
  function displayAddress(address) {
    const b = baseUrl(address);
    if (!b) return String(address || '');
    return b.replace(/^http:\/\//, '').replace(':' + PORT, '');
  }

  async function request(address, path, opts) {
    const o = opts || {};
    const base = baseUrl(address);
    if (!base) throw new ProjectorError('bad_address', 'Неверный адрес проектора');
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), o.timeout || 4000);
    const headers = {};
    if (o.body !== undefined) headers['Content-Type'] = 'application/json';
    if (o.token) headers['Authorization'] = 'Bearer ' + o.token;
    let res;
    try {
      res = await fetch(base + path, {
        method: o.method || (o.body !== undefined ? 'POST' : 'GET'),
        headers,
        body: o.body !== undefined ? JSON.stringify(o.body) : undefined,
        signal: ctrl.signal,
        credentials: 'omit',
        cache: 'no-store',
      });
    } catch (e) {
      throw new ProjectorError('unreachable', 'Проектор не отвечает');
    } finally {
      clearTimeout(timer);
    }
    let data = null;
    try {
      data = await res.json();
    } catch (e) {
      data = null;
    }
    if (!res.ok) {
      const code = (data && data.error) || ({ 401: 'unauthorized', 403: 'bad_code', 409: 'no_pairing', 429: 'locked', 400: 'bad_request' }[res.status]) || 'http';
      throw new ProjectorError(code, (data && data.message) || ('Ошибка ' + res.status), { status: res.status, retryAfter: data && data.retryAfter, attemptsLeft: data && data.attemptsLeft });
    }
    return data || {};
  }

  const api = {
    info: (address, timeout) => request(address, '/lumen/info', { timeout }),
    pair: (address, client) => request(address, '/lumen/pair', { body: { client } }),
    confirm: (address, code) => request(address, '/lumen/pair/confirm', { body: { code: String(code) } }),
    play: (address, token, payload) => request(address, '/lumen/play', { body: payload, token, timeout: 8000 }),
    control: (address, token, action, position) =>
      request(address, '/lumen/control', { body: position == null ? { action } : { action, position }, token }),
    state: (address, token) => request(address, '/lumen/state', { token, timeout: 3000 }),
  };

  /**
   * Перебор подсети /24 запросом /lumen/info. [prefix]: "192.168.1".
   * [onFound]({address, info}) зовётся сразу на каждую находку. Возвращает список найденных.
   */
  async function scan(prefix, onFound, opts) {
    const o = opts || {};
    const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(String(prefix || '').trim().replace(/\.$/, ''));
    if (!m || m.slice(1).some(n => Number(n) > 255)) throw new ProjectorError('bad_address', 'Неверная подсеть');
    const net = m.slice(1).join('.');
    const hosts = [];
    for (let i = 1; i < 255; i++) hosts.push(net + '.' + i);
    const found = [];
    let next = 0;
    async function worker() {
      while (next < hosts.length) {
        if (o.signal && o.signal.aborted) return;
        const address = hosts[next++];
        try {
          const info = await api.info(address, o.timeout || 1200);
          if (info && info.app === 'Lumen Play') {
            const hit = { address, info };
            found.push(hit);
            if (onFound) onFound(hit);
          }
        } catch (e) {
          // нет ответа: здесь проектора нет
        }
        if (o.onProgress) o.onProgress(next, hosts.length);
      }
    }
    await Promise.all(Array.from({ length: o.concurrency || 32 }, worker));
    return found;
  }

  /** Подсеть для перебора: из сохранённого адреса, иначе самая частая домашняя. */
  function guessPrefix(address) {
    const b = baseUrl(address);
    const m = b && /^http:\/\/(\d+\.\d+\.\d+)\.\d+:/.exec(b);
    return m ? m[1] : '192.168.1';
  }

  /** Имя клиента для плашки на проекторе: "Яндекс Браузер на Windows". */
  function clientName(nav) {
    const n = nav || (typeof navigator !== 'undefined' ? navigator : { userAgent: '' });
    const ua = n.userAgent || '';
    let browser = 'Chrome';
    if (/YaBrowser\//.test(ua)) browser = 'Яндекс Браузер';
    else if (/Edg\//.test(ua)) browser = 'Edge';
    else if (/OPR\//.test(ua)) browser = 'Opera';
    else if (/Firefox\//.test(ua)) browser = 'Firefox';
    else if (n.brave) browser = 'Brave';
    let os = '';
    if (/Windows/.test(ua)) os = 'Windows';
    else if (/Mac OS X|Macintosh/.test(ua)) os = 'macOS';
    else if (/CrOS/.test(ua)) os = 'ChromeOS';
    else if (/Linux/.test(ua)) os = 'Linux';
    return os ? browser + ' на ' + os : browser;
  }

  /** Сохранённые настройки: {address, token, name, version}. */
  async function load() {
    try {
      const r = await root.ext.storage.local.get(STORE_KEY);
      return Object.assign({ address: '', token: '', name: '', version: '' }, r && r[STORE_KEY]);
    } catch (e) {
      return { address: '', token: '', name: '', version: '' };
    }
  }

  async function save(patch) {
    const cur = await load();
    const next = Object.assign(cur, patch);
    await root.ext.storage.local.set({ [STORE_KEY]: next });
    return next;
  }

  /** Понятный текст ошибки для человека. */
  function describe(err) {
    switch (err && err.code) {
      case 'unreachable': return 'Проектор не отвечает. Проверьте, что он включён и в той же сети, что и компьютер.';
      case 'unauthorized': return 'Проектор не узнал этот браузер. Подключитесь заново в настройках.';
      case 'bad_code': return 'Код не подходит. Посмотрите код на экране проектора.' +
        (Number.isFinite(err.attemptsLeft) ? ' Осталось попыток: ' + err.attemptsLeft + '.' : '');
      case 'background_blocked': return 'Проектор не дал открыть плеер из фона. Откройте Lumen Play на проекторе или разрешите ему показ поверх других приложений, затем отправьте снова.';
      case 'forbidden_origin': return 'Проектор отклонил запрос. Обновите расширение и Lumen Play.';
      case 'no_pairing': return 'Код устарел. Нажмите «Подключить» ещё раз.';
      case 'locked': return 'Слишком много неверных кодов. Подождите ' + Math.ceil((err.retryAfter || 300) / 60) + ' мин.';
      case 'bad_address': return err.message;
      case 'not_configured': return 'Проектор не настроен. Нажмите на значок расширения и укажите адрес.';
      case 'not_paired': return 'Браузер не подключён к проектору. Нажмите на значок расширения и подключитесь.';
      case 'drm': return 'Это видео защищено (DRM). Такие видео отправить на проектор нельзя.';
      case 'no_video': return 'На странице нет видео, которое можно отправить. Запустите воспроизведение и попробуйте ещё раз.';
      default: return (err && err.message) || 'Что-то пошло не так.';
    }
  }

  root.LumenProjector = { PORT, ProjectorError, baseUrl, displayAddress, scan, guessPrefix, clientName, load, save, describe, ...api };
})(typeof globalThis !== 'undefined' ? globalThis : this);
