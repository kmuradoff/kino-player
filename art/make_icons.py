"""Иконка и ТВ-баннер «Кино».

Запуск: python3 art/make_icons.py <папка>
Скрипт пишет в папку player_icon.png (192x192) и player_banner.png (640x360); их копии лежат в
app/src/main/res/drawable-nodpi/ под именами ic_app.png и banner.png.
Нужны Pillow и шрифт Avenir Next (входит в macOS).
"""
from PIL import Image, ImageDraw, ImageFont
import os, sys

OUT = sys.argv[1]
AVENIR = "/System/Library/Fonts/Avenir Next.ttc"
S = 4  # суперсэмплинг для гладких краёв


def avenir(size, bold=True):
    # в Avenir Next.ttc: 0 Bold, 2 Demi Bold, 7 Regular (порядок может отличаться, поэтому ищем по имени)
    want = "Demi Bold" if bold else "Medium"
    for i in range(12):
        try:
            f = ImageFont.truetype(AVENIR, size, index=i)
        except OSError:
            break
        if f.getname()[1] == want:
            return f
    return ImageFont.truetype(AVENIR, size)


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.35 + y / h * 0.65)
            px[x, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img


def rounded(img, r):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], r, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def glyph_player(size):
    """Экран с кнопкой «плей» и лучом проектора."""
    g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    w = int(size * 0.05)
    # экран
    d.rounded_rectangle([size * 0.18, size * 0.24, size * 0.82, size * 0.70], radius=size * 0.07,
                        outline=(255, 255, 255, 255), width=w)
    # «плей» со скруглёнными углами (рисуем крупнее и сглаживаем)
    cx, cy, r = size * 0.515, size * 0.47, size * 0.13
    tri = [(cx - r * 0.75, cy - r), (cx + r, cy), (cx - r * 0.75, cy + r)]
    d.polygon(tri, fill=(255, 255, 255, 255))
    d.line(tri + [tri[0]], fill=(255, 255, 255, 255), width=int(size * 0.03), joint="curve")
    # подставка
    d.rounded_rectangle([size * 0.38, size * 0.76, size * 0.62, size * 0.80], radius=size * 0.02, fill=(255, 255, 255, 200))
    return g


APPS = {
    "player": dict(top=(255, 122, 89), bottom=(96, 24, 64), glyph=glyph_player,
                   title="Кино", sub="плеер для проектора"),
}


def make_icon(app, px):
    a = APPS[app]
    big = px * S
    bg = gradient(big, big, a["top"], a["bottom"])
    icon = rounded(bg, int(big * 0.22))
    icon.alpha_composite(a["glyph"](big))
    return icon.resize((px, px), Image.LANCZOS)


def make_banner(app, w, h):
    a = APPS[app]
    W, H = w * S, h * S
    ban = gradient(W, H, a["top"], a["bottom"]).convert("RGBA")
    gs = int(H * 0.66)
    ban.alpha_composite(a["glyph"](gs), (int(H * 0.06), (H - gs) // 2))
    d = ImageDraw.Draw(ban)
    x = int(H * 0.06) + gs + int(H * 0.02)
    room = W - x - int(H * 0.08)

    def fit(text, size, bold):
        f = avenir(size, bold)
        while f.getlength(text) > room and size > 8:
            size = int(size * 0.94)
            f = avenir(size, bold)
        return f

    d.text((x, H * 0.50), a["title"], font=fit(a["title"], int(H * 0.21), True), anchor="ls",
           fill=(255, 255, 255))
    d.text((x, H * 0.66), a["sub"], font=fit(a["sub"], int(H * 0.095), False), anchor="ls",
           fill=(255, 255, 255, 215))
    ban = rounded(ban.convert("RGB"), int(H * 0.06))
    return ban.resize((w, h), Image.LANCZOS)


os.makedirs(OUT, exist_ok=True)
for app in APPS:
    make_icon(app, 192).save(f"{OUT}/{app}_icon.png")
    make_banner(app, 640, 360).save(f"{OUT}/{app}_banner.png")
print("ok")
