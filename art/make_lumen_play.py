"""Иконка и ТВ-баннер Lumen Play.

Запуск: python3 art/make_lumen_play.py <папка>
Пишет icon.png (512x512, из него ic_app.png 192x192) и banner.png (640x360). Шрифт Onest из assets.
Знак: светящийся треугольник «Play» на тёплом тёмном фоне, как луч проектора в тёмной комнате.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math, os, sys

OUT = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
ONEST = os.path.join(HERE, "..", "app", "src", "main", "assets", "fonts", "Onest-Variable.ttf")
S = 4


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def bg(w, h, radius):
    """Тёмный тёплый фон с мягким свечением из центра-слева."""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    cx, cy = w * 0.42, h * 0.5
    for y in range(h):
        for x in range(w):
            d = math.hypot((x - cx) / w, (y - cy) / h)
            t = min(1.0, d / 0.75)
            px[x, y] = lerp((74, 44, 26, 255), (14, 11, 9, 255), t ** 0.8)
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius, fill=255)
    img.putalpha(m)
    return img


def mark(size):
    """Треугольник Play с градиентом янтарь -> светлый, со свечением."""
    z = size * S
    tri = Image.new("L", (z, z), 0)
    d = ImageDraw.Draw(tri)
    # треугольник со скруглёнными углами: многоугольник + размытие + порог
    pts = [(z * 0.30, z * 0.20), (z * 0.30, z * 0.80), (z * 0.82, z * 0.50)]
    d.polygon(pts, fill=255)
    tri = tri.filter(ImageFilter.GaussianBlur(z * 0.035)).point(lambda v: 255 if v > 128 else 0)
    tri = tri.filter(ImageFilter.GaussianBlur(S * 0.7))
    grad = Image.new("RGBA", (z, z))
    gp = grad.load()
    for y in range(z):
        for x in range(z):
            t = min(1.0, max(0.0, (x / z - 0.28) / 0.6 * 0.7 + (y / z) * 0.3))
            gp[x, y] = lerp((255, 236, 205, 255), (242, 160, 90, 255), t)
    shape = Image.new("RGBA", (z, z), (0, 0, 0, 0))
    shape.paste(grad, (0, 0), tri)
    glow = Image.new("RGBA", (z, z), (242, 170, 100, 0))
    glow.putalpha(tri.filter(ImageFilter.GaussianBlur(z * 0.06)).point(lambda v: int(v * 0.75)))
    out = Image.alpha_composite(glow, shape)
    return out.resize((size, size), Image.LANCZOS)


def icon(n):
    img = bg(n, n, int(n * 0.22))
    m = mark(n)
    img = Image.alpha_composite(img, m)
    return img


def banner(w, h):
    img = bg(w, h, int(h * 0.12))
    m = mark(int(h * 0.5))
    img.alpha_composite(m, (int(h * 0.06), (h - m.size[1]) // 2))
    d = ImageDraw.Draw(img)
    x = int(h * 0.06) + m.size[0]
    size = int(h * 0.2)
    while True:   # самый крупный шрифт, который помещается с полями
        f = ImageFont.truetype(ONEST, size)
        try:
            f.set_variation_by_axes([640])
        except Exception:
            pass
        if d.textlength("Lumen Play", font=f) <= w - x - h * 0.12 or size < 20:
            break
        size -= 2
    d.text((x, h * 0.5), "Lumen Play", font=f, fill=(244, 239, 230, 255), anchor="lm")
    return img


os.makedirs(OUT, exist_ok=True)
icon(512).save(os.path.join(OUT, "icon.png"))
icon(192).save(os.path.join(OUT, "ic_app.png"))
banner(640, 360).save(os.path.join(OUT, "banner.png"))
print("ok")
