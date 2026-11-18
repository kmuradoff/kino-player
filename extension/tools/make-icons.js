/**
 * Рисует значки icons/icon{16,32,48,128}.png: синий скруглённый квадрат с белым треугольником «играть».
 * Без зависимостей: PNG собирается вручную через zlib. Запуск: node tools/make-icons.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const BG = [43, 91, 215];
const FG = [255, 255, 255];
const SS = 4; // сглаживание: подвыборок на сторону пикселя

function inside(x, y) {
  // Координаты 0..1. Скруглённый квадрат с радиусом 0.22.
  const r = 0.22;
  const cx = Math.min(Math.max(x, r), 1 - r);
  const cy = Math.min(Math.max(y, r), 1 - r);
  const bg = (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
  // Треугольник «играть», немного сдвинут вправо для оптического центра.
  const ax = 0.37, ay = 0.27, bx = 0.37, by = 0.73, qx = 0.76, qy = 0.5;
  const s1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
  const s2 = (qx - bx) * (y - by) - (qy - by) * (x - bx);
  const s3 = (ax - qx) * (y - qy) - (ay - qy) * (x - qx);
  const tri = (s1 <= 0 && s2 <= 0 && s3 <= 0) || (s1 >= 0 && s2 >= 0 && s3 >= 0);
  return { bg, tri };
}

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function png(size) {
  const rows = [];
  for (let y = 0; y < size; y++) {
    const row = Buffer.alloc(1 + size * 4);
    for (let x = 0; x < size; x++) {
      let a = 0, w = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const p = inside((x + (sx + 0.5) / SS) / size, (y + (sy + 0.5) / SS) / size);
          if (p.bg) { a++; if (p.tri) w++; }
        }
      }
      const n = SS * SS;
      const t = a ? w / a : 0;
      const o = 1 + x * 4;
      for (let i = 0; i < 3; i++) row[o + i] = Math.round(BG[i] * (1 - t) + FG[i] * t);
      row[o + 3] = Math.round((a / n) * 255);
    }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.concat(rows), { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const dir = path.join(__dirname, '..', 'icons');
fs.mkdirSync(dir, { recursive: true });
for (const s of [16, 32, 48, 128]) fs.writeFileSync(path.join(dir, 'icon' + s + '.png'), png(s));
console.log('icons written to', dir);
