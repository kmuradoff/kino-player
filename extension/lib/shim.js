/**
 * Один объект API для Chrome, Edge, Яндекс Браузера, Brave и Firefox.
 * В Firefox есть browser.* с промисами; в Chromium с Manifest V3 chrome.* тоже отдаёт промисы.
 */
(function (root) {
  'use strict';
  root.ext = root.browser && root.browser.runtime ? root.browser : root.chrome;
})(typeof globalThis !== 'undefined' ? globalThis : this);
