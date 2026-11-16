import io
import math
import os
from PIL import Image, ImageDraw, ImageFont

from templates import TEMPLATES, PALETTES

FONT_CANDIDATES = [
    os.environ.get("FONT_PATH", ""),
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]
FONT_PATH = next((f for f in FONT_CANDIDATES if f and os.path.exists(f)), None)
SS = 2  # суперсэмплинг
CLEAR = (0, 0, 0, 0)


def _font(size):
    return ImageFont.truetype(FONT_PATH, size) if FONT_PATH else ImageFont.load_default()


def _rgb(c):
    c = c.lstrip("#")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def _dark(c, k=0.68):
    return tuple(int(v * k) for v in _rgb(c))


class Ctx:
    """Рисование в нормализованных координатах 0..1."""

    def __init__(self, s, pal):
        self.s = s
        self.img = Image.new("RGBA", (s, s), CLEAR)
        self.d = ImageDraw.Draw(self.img)
        _, m, l, ol, t, lab, tl = pal
        self.c = {"m": _rgb(m), "l": _rgb(l), "ol": _rgb(ol), "t": _rgb(t),
                  "lab": _rgb(lab), "tl": _rgb(tl), "k": _dark(m), "clear": CLEAR,
                  "gold": (255, 213, 74), "red": (229, 57, 53), "w": (255, 255, 255)}
        self.w = max(2, int(s * 0.022))

    def col(self, f):
        return None if f is None else self.c[f]

    def P(self, pts):
        return [(x * self.s, y * self.s) for x, y in pts]

    def _o(self, fill):
        return (0, 0, 0, 0) if fill == "clear" else self.c["ol"]

    def rr(self, x0, y0, x1, y1, r, fill, ow=True):
        s = self.s
        self.d.rounded_rectangle((x0 * s, y0 * s, x1 * s, y1 * s), radius=r * s, fill=self.col(fill),
                                 outline=self.c["ol"] if ow else None, width=self.w)

    def el(self, x0, y0, x1, y1, fill, ow=True, color=None, w=None):
        s = self.s
        self.d.ellipse((x0 * s, y0 * s, x1 * s, y1 * s), fill=self.col(fill),
                       outline=(self.c[color] if color else self._o(fill)) if ow else None,
                       width=int(w * s) if w else self.w)

    def pg(self, pts, fill, ow=True):
        self.d.polygon(self.P(pts), fill=self.col(fill), outline=self.c["ol"] if ow else None, width=self.w)
        if ow:  # polygon() игнорирует width -> дорисуем контур
            p = self.P(pts)
            self.d.line(p + [p[0]], fill=self.c["ol"], width=self.w, joint="curve")

    def ch(self, box, a0, a1, fill):
        s = self.s
        b = tuple(v * s for v in box)
        self.d.chord(b, a0, a1, fill=self.col(fill), outline=self.c["ol"], width=self.w)

    def ln(self, pts, fill="ol", w=0.03):
        self.d.line(self.P(pts), fill=self.c[fill], width=int(w * self.s), joint="curve")


def _poly(n, r, cx=.5, cy=.5, rot=0.0, r2=None):
    pts = []
    for i in range(n * (2 if r2 else 1)):
        a = rot + 2 * math.pi * i / (n * (2 if r2 else 1)) - math.pi / 2
        rad = r if (not r2 or i % 2 == 0) else r2
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    return pts


# Каждый предмет возвращает (область текста, угол, режим цвета: t|tl)
def o_badge(c):
    c.el(.06, .06, .94, .94, "m"); c.el(.12, .12, .88, .88, None, color="l", w=.012)
    return (.16, .34, .84, .66), 0, "t"

def o_cap(c):
    c.ch((.1, .15, .9, 1.05), 180, 360, "m")
    c.pg([(.08, .6), (.92, .6), (.98, .74), (.02, .74)], "l")
    c.el(.46, .13, .54, .21, "l")
    return (.2, .3, .8, .55), 0, "t"

def o_bag(c):
    c.el(.3, .06, .7, .5, None, w=.03)
    c.pg([(.16, .3), (.84, .3), (.9, .94), (.1, .94)], "m")
    c.rr(.24, .5, .76, .78, .04, "lab")
    return (.26, .53, .74, .75), 0, "tl"

def o_watch(c):
    c.rr(.36, .02, .64, .98, .05, "l"); c.rr(.12, .24, .88, .76, .14, "m"); c.rr(.2, .31, .8, .69, .08, "lab")
    return (.22, .38, .78, .62), 0, "tl"

def o_ticket(c):
    c.rr(.04, .22, .96, .78, .06, "m")
    c.el(-.05, .42, .09, .58, "clear"); c.el(.91, .42, 1.05, .58, "clear")
    c.ln([(.18, .3), (.18, .7)], "l", .012)
    return (.24, .34, .88, .66), -4, "t"

def o_shield(c):
    c.pg([(.5, .04), (.88, .16), (.88, .52), (.5, .97), (.12, .52), (.12, .16)], "m")
    c.rr(.2, .3, .8, .58, .04, "lab")
    return (.22, .32, .78, .56), 0, "tl"

def o_egg(c):
    c.el(.16, .05, .84, .95, "m"); c.el(.27, .15, .38, .34, "l", ow=False)
    return (.25, .4, .75, .66), 0, "t"

def o_ball(c):
    c.pg([(.25, .82), (.75, .82), (.84, .96), (.16, .96)], "l")
    c.el(.08, .04, .92, .88, "m"); c.el(.2, .12, .38, .28, "l", ow=False)
    c.pg(_poly(4, .07, .86, .18, r2=.02), "l", ow=False)
    return (.18, .38, .82, .62), 0, "t"

def o_gift(c):
    c.el(.28, .06, .5, .3, "lab"); c.el(.5, .06, .72, .3, "lab")
    c.rr(.12, .4, .88, .94, .03, "m"); c.rr(.08, .26, .92, .42, .03, "l"); c.rr(.45, .26, .55, .94, 0, "lab")
    c.rr(.18, .52, .82, .82, .03, "lab")
    return (.2, .55, .8, .79), 0, "tl"

def o_coin(c):
    c.el(.06, .08, .94, .96, "k"); c.el(.06, .04, .94, .9, "m"); c.el(.14, .12, .86, .82, None, color="l", w=.014)
    return (.2, .36, .8, .6), 0, "t"

def o_flag(c):
    c.pg([(.12, .15), (.96, .5), (.12, .85)], "m"); c.rr(.06, .04, .13, .98, .01, "l")
    return (.2, .36, .66, .64), 0, "t"

def o_cube(c):
    c.pg([(.1, .34), (.3, .1), (.9, .1), (.7, .34)], "l"); c.pg([(.7, .34), (.9, .1), (.9, .7), (.7, .94)], "k")
    c.rr(.1, .34, .7, .94, .02, "m")
    return (.13, .46, .67, .8), 0, "t"

def o_chat(c):
    c.pg([(.2, .6), (.16, .95), (.5, .66)], "m"); c.rr(.04, .08, .96, .7, .18, "m")
    return (.12, .22, .88, .56), 0, "t"

def o_phone(c):
    c.rr(.26, .02, .74, .98, .09, "m"); c.rr(.31, .1, .69, .9, .04, "lab")
    return (.32, .38, .68, .62), 0, "tl"

def o_price(c):
    c.pg([(.04, .5), (.3, .16), (.96, .16), (.96, .84), (.3, .84)], "m"); c.el(.1, .44, .2, .56, "clear")
    return (.33, .3, .92, .7), 0, "t"

def o_banner(c):
    c.pg([(.02, .3), (.2, .3), (.2, .78), (.02, .78), (.1, .54)], "k")
    c.pg([(.98, .3), (.8, .3), (.8, .78), (.98, .78), (.9, .54)], "k")
    c.rr(.14, .2, .86, .7, .02, "m")
    return (.18, .3, .82, .6), 0, "t"

def o_halo(c):
    c.el(.14, .2, .86, .94, "m"); c.el(.28, .02, .72, .16, None, color="gold", w=.035)
    return (.2, .46, .8, .7), 0, "t"

def o_horns(c):
    c.pg([(.18, .4), (.1, .03), (.4, .2)], "red"); c.pg([(.82, .4), (.9, .03), (.6, .2)], "red")
    c.el(.12, .14, .88, .94, "m")
    return (.18, .44, .82, .7), 0, "t"

def o_mail(c):
    c.rr(.04, .2, .96, .82, .04, "m"); c.pg([(.04, .2), (.5, .56), (.96, .2)], "l")
    c.rr(.2, .38, .8, .72, .03, "lab")
    return (.22, .41, .78, .69), 0, "tl"

def o_plate(c):
    c.rr(.02, .26, .98, .74, .07, "lab"); c.rr(.06, .31, .94, .69, .04, None); c.rr(.02, .26, .13, .74, .07, "m")
    return (.16, .34, .9, .66), 0, "tl"

def o_stop(c):
    c.pg(_poly(8, .47, rot=math.pi / 8), "m"); c.pg(_poly(8, .39, rot=math.pi / 8), None)
    return (.2, .4, .8, .6), 0, "t"

def o_tv(c):
    c.ln([(.5, .14), (.3, .02)], "ol", .02); c.ln([(.5, .14), (.7, .02)], "ol", .02)
    c.rr(.3, .8, .7, .92, .02, "l"); c.rr(.04, .12, .96, .8, .08, "m"); c.rr(.1, .2, .9, .72, .05, "lab")
    return (.14, .3, .86, .62), 0, "tl"

def o_card(c):
    c.rr(.04, .18, .96, .82, .08, "m"); c.rr(.04, .3, .96, .42, 0, "k"); c.rr(.12, .52, .28, .68, .02, "l")
    return (.32, .52, .9, .74), 0, "t"

def o_jar(c):
    c.rr(.24, .04, .76, .22, .03, "l"); c.rr(.14, .2, .86, .95, .12, "m"); c.rr(.22, .46, .78, .78, .03, "lab")
    return (.24, .49, .76, .75), 0, "tl"

def o_seal(c):
    c.pg(_poly(24, .48, r2=.42), "m"); c.el(.14, .14, .86, .86, None, color="l", w=.012)
    return (.2, .4, .8, .6), 0, "t"

DRAWERS = [o_badge, o_cap, o_bag, o_watch, o_ticket, o_shield, o_egg, o_ball, o_gift, o_coin, o_flag, o_cube,
           o_chat, o_phone, o_price, o_banner, o_halo, o_horns, o_mail, o_plate, o_stop, o_tv, o_card, o_jar, o_seal]
assert len(DRAWERS) == 25


def _wrap(d, text, w, h):
    words = text.split() or [" "]
    for size in range(int(h), 9, -2):
        f = _font(size)
        lines, cur = [], ""
        for wd in words:
            t = (cur + " " + wd).strip()
            if d.textlength(t, font=f) <= w:
                cur = t
            else:
                if cur:
                    lines.append(cur)
                cur = wd
        lines.append(cur)
        lh = int(size * 1.12)
        if all(d.textlength(l, font=f) <= w for l in lines) and lh * len(lines) <= h:
            return f, lines, lh
    return _font(9), [text[:24]], 11


def render(template_id: int, text: str, size: int = 512) -> bytes:
    t = TEMPLATES[template_id - 1]
    s = size * SS
    c = Ctx(s, PALETTES[t["pal"]])
    area, angle, mode = DRAWERS[t["obj"]](c)
    x0, y0, x1, y1 = (v * s for v in area)
    w, h = int(x1 - x0), int(y1 - y0)
    layer = Image.new("RGBA", (w, h), CLEAR)
    ld = ImageDraw.Draw(layer)
    f, lines, lh = _wrap(ld, text, w, h)
    y = (h - lh * len(lines)) / 2
    for line in lines:
        ld.text(((w - ld.textlength(line, font=f)) / 2, y), line, font=f, fill=c.c[mode] + (255,))
        y += lh
    if angle:
        layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    c.img.alpha_composite(layer, (int((x0 + x1) / 2 - layer.width / 2), int((y0 + y1) / 2 - layer.height / 2)))
    out = c.img.resize((size, size), Image.LANCZOS)
    buf = io.BytesIO()
    out.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def to_webp(png: bytes) -> bytes:
    buf = io.BytesIO()
    Image.open(io.BytesIO(png)).save(buf, "WEBP", lossless=True)
    return buf.getvalue()


def gallery(page: int, per_page: int = 20, text: str = "Текст") -> bytes:
    cell, cols = 200, 5
    ids = list(range(page * per_page + 1, min((page + 1) * per_page, len(TEMPLATES)) + 1))
    rows = math.ceil(len(ids) / cols)
    img = Image.new("RGB", (cell * cols, cell * rows), (38, 36, 56))
    d = ImageDraw.Draw(img)
    for i, tid in enumerate(ids):
        tile = Image.open(io.BytesIO(render(tid, text, cell - 40)))
        x, y = (i % cols) * cell + 20, (i // cols) * cell + 4
        img.paste(tile, (x, y), tile)
        lab = str(tid)
        d.text((x + (cell - 40) / 2 - d.textlength(lab, font=_font(20)) / 2, y + cell - 36), lab,
               font=_font(20), fill=(235, 235, 240))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()
