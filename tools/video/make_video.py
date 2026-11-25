"""Генератор коротких вертикальных видео (1080x1920, 15 c) в стиле Material 3 Expressive.

Использование:
    python make_video.py content.json out.mp4

content.json:
{
  "hook": ["Клавиатура,", "которая тебя", "удивит"],        # 3 строки хука
  "badge": "Обновление 11.1.6",
  "title": "Expressive Keyboard",
  "points": [["Заголовок", "Пояснение"], ... 3 шт],
  "typing": "Привет! Это Expressive Keyboard",             # что «печатается» в макете
  "clip": "clips/demo.mp4",                                  # необязательно: запись экрана вместо макета
  "clip_start": 0,                                           # секунда в клипе, с которой начать
  "cta": "@materialyouboss"
}
"""
import json
import math
import os
import random
import subprocess
import sys

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

W, H, FPS, DUR = 1080, 1920, 30, 15
FONTS = "C:/Windows/Fonts/"
FF = imageio_ffmpeg.get_ffmpeg_exe()

BG = (254, 247, 255)
TEXT = (29, 27, 32)
TEXT_VAR = (73, 69, 79)

# «Material You»: меняем тему на каждой фиче -> (primary, container, on_container, surface)
THEMES = [
    ((103, 80, 164), (234, 221, 255), (33, 0, 93), (247, 242, 250)),
    ((0, 109, 59), (176, 244, 190), (0, 33, 14), (240, 249, 241)),
    ((156, 64, 105), (255, 216, 228), (62, 0, 33), (255, 244, 247)),
]
BLOB_COLORS = [(234, 221, 255), (255, 216, 228), (176, 244, 190), (255, 224, 178), (200, 230, 255)]

PHONE_W, PHONE_H = 620, 1150
PHONE_X, PHONE_Y = (W - PHONE_W) // 2, 450
BEZEL = 18
SCR_W, SCR_H = PHONE_W - 2 * BEZEL, PHONE_H - 2 * BEZEL


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


F_HUGE = font("segoeuib.ttf", 132)
F_BIG = font("segoeuib.ttf", 88)
F_TITLE = font("segoeuib.ttf", 62)
F_BODY = font("segoeui.ttf", 46)
F_CHIP = font("segoeuib.ttf", 40)
F_CTA = font("segoeuib.ttf", 58)
F_SMALL = font("segoeui.ttf", 38)
F_KEY = font("segoeuib.ttf", 30)
F_TYPE = font("segoeui.ttf", 34)


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def lerp(a, b, k):
    return a + (b - a) * k


def mix(c1, c2, k):
    return tuple(int(lerp(c1[i], c2[i], k)) for i in range(3))


def seg(t, start, length):
    return clamp((t - start) / length)


def ease_out(t):
    return 1 - (1 - clamp(t)) ** 3


def ease_in(t):
    return clamp(t) ** 3


def spring(t):
    """Пружина: быстрый выстрел с перелётом и затуханием (0..1+)."""
    t = clamp(t)
    if t <= 0:
        return 0.0
    if t >= 1:
        return 1.0
    return 1 - math.exp(-6 * t) * math.cos(14 * t)


def rrect(d, box, r, fill):
    d.rounded_rectangle(box, radius=r, fill=fill)


# ---------- примитивы с антиалиасингом (рисуем на 2x и уменьшаем) ----------

def paste_aa(img, draw_fn, cx, cy, size):
    """draw_fn(draw, s) рисует в квадрате s×s (2x). Результат центрируется в (cx, cy)."""
    s = size * 2
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer), s)
    layer = layer.resize((size, size), Image.LANCZOS)
    img.paste(layer, (int(cx - size / 2), int(cy - size / 2)), layer)


def blob(img, cx, cy, r, color, rot, petals):
    size = int(r * 2.6)

    def fn(d, s):
        pts = []
        for i in range(120):
            a = i / 120 * math.tau
            rr = s / 2 * 0.72 * (1 + 0.16 * math.cos(petals * a + rot))
            pts.append((s / 2 + rr * math.cos(a), s / 2 + rr * math.sin(a)))
        d.polygon(pts, fill=color)

    paste_aa(img, fn, cx, cy, size)


def text_layer(s, f, fill):
    tmp = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    l, t, r, b = tmp.textbbox((0, 0), s, font=f)
    pad = 20
    layer = Image.new("RGBA", (r - l + 2 * pad, b - t + 2 * pad), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((pad - l, pad - t), s, font=f, fill=fill)
    return layer


def pop_text(img, s, f, fill, cx, cy, k, rot=0.0, alpha=1.0):
    """Слово «выпрыгивает» с пружиной. k = прогресс 0..1 (spring уже применён)."""
    if k <= 0.01:
        return
    layer = text_layer(s, f, fill)
    sc = max(k, 0.01)
    layer = layer.resize((max(1, int(layer.width * sc)), max(1, int(layer.height * sc))), Image.BICUBIC)
    if rot:
        layer = layer.rotate(rot * (1 - min(k, 1)) * 6 + rot, expand=True, resample=Image.BICUBIC)
    if alpha < 1:
        a = layer.getchannel("A").point(lambda v: int(v * alpha))
        layer.putalpha(a)
    img.paste(layer, (int(cx - layer.width / 2), int(cy - layer.height / 2)), layer)


# ---------- фон ----------

BLOBS = [
    (160, 260, 360, 0, 6, 0.0), (930, 520, 300, 1, 8, 1.3), (880, 1180, 420, 2, 5, 2.1),
    (150, 1500, 360, 3, 7, 3.4), (700, 1820, 300, 4, 6, 4.2),
]


def background(t, theme):
    img = Image.new("RGB", (W, H), mix(BG, theme[1], 0.25))
    d = ImageDraw.Draw(img)
    for y in range(0, H, 8):  # лёгкий вертикальный градиент
        d.rectangle((0, y, W, y + 8), fill=mix(BG, theme[1], 0.1 + 0.35 * y / H))
    for x, y, r, ci, pet, ph in BLOBS:
        ox, oy = math.sin(t * 0.8 + ph) * 70, math.cos(t * 0.6 + ph) * 90
        rr = r * (1 + 0.08 * math.sin(t * 1.4 + ph))
        col = mix(BLOB_COLORS[ci], theme[1], 0.4)
        blob(img, x + ox, y + oy, rr, col, t * 0.5 + ph, pet)
    return img


# ---------- макет клавиатуры ----------

ROWS = ["йцукенгшщзх", "фывапролдж", "ячсмитьбю"]


def keyboard_geometry():
    keys = {}
    kb_top = SCR_H - 520
    pad = 14
    kw = (SCR_W - 2 * pad) / 11
    for ri, row in enumerate(ROWS):
        n = len(row)
        x0 = pad + (11 - n) * kw / 2
        for ci, ch in enumerate(row):
            keys[ch] = (x0 + ci * kw, kb_top + 70 + ri * 118, kw - 8, 104)
    keys[" "] = (pad + 2 * kw, kb_top + 70 + 3 * 118, kw * 7 - 8, 104)
    return keys, kb_top


KEYS, KB_TOP = keyboard_geometry()


def draw_screen_mock(t, tt, theme, typing):
    """Макет экрана с чатом и клавиатурой. tt — секунды с начала сцены телефона."""
    prim, cont, oncont, surf = theme
    scr = Image.new("RGB", (SCR_W, SCR_H), surf)
    d = ImageDraw.Draw(scr)
    # статус-бар и шапка
    d.text((40, 24), "9:41", font=F_KEY, fill=TEXT)
    rrect(d, (SCR_W - 140, 30, SCR_W - 40, 52), 11, TEXT_VAR)
    rrect(d, (30, 100, SCR_W - 30, 230), 40, cont)
    d.ellipse((50, 120, 110, 180 + 0), fill=prim)
    d.text((130, 126), "Чат", font=F_TITLE, fill=oncont)
    # сообщение собеседника
    rrect(d, (30, 270, 420, 350), 34, cont)
    d.text((56, 286), "Попробуй новую клаву", font=F_TYPE, fill=oncont)
    # поле ввода
    rrect(d, (30, KB_TOP - 130, SCR_W - 30, KB_TOP - 30), 50, (255, 255, 255))
    n = int(max(0, tt - 0.4) * 8)
    shown = typing[:n]
    d.text((60, KB_TOP - 108), shown + ("|" if int(t * 2.5) % 2 == 0 else ""), font=F_TYPE, fill=TEXT)
    # панель клавиатуры
    rrect(d, (0, KB_TOP, SCR_W, SCR_H), 0, mix(surf, cont, 0.55))
    pressed = shown[-1].lower() if shown else None
    since = (max(0, tt - 0.4) * 8) % 1  # 0..1 от последнего нажатия
    for ch, (x, y, w, h) in KEYS.items():
        is_p = ch == pressed and since < 0.55 and n > 0 and n <= len(typing)
        k = 1 - since / 0.55 if is_p else 0
        col = mix(surf, (255, 255, 255), 0.85)
        if is_p:
            col = mix(col, prim, k)
        grow = 6 * k
        rrect(d, (x - grow, y - grow, x + w + grow, y + h + grow), 26 + int(10 * k), col)
        if ch != " ":
            fc = mix(TEXT, (255, 255, 255), k)
            tw = d.textlength(ch, font=F_KEY)
            d.text((x + w / 2 - tw / 2, y + h / 2 - 22 - 40 * k), ch, font=F_KEY, fill=fc)
    # пузырь-превью над нажатой клавишей
    if pressed in KEYS and pressed != " " and since < 0.45 and n > 0:
        x, y, w, h = KEYS[pressed]
        k = 1 - since / 0.45
        bx, by = x + w / 2, y - 60 - 20 * (1 - k)
        rrect(d, (bx - 44, by - 62, bx + 44, by + 30), 30, prim)
        tw = d.textlength(pressed.upper(), font=F_TITLE)
        d.text((bx - tw / 2, by - 56), pressed.upper(), font=F_TITLE, fill=(255, 255, 255))
    return scr


class Clip:
    """Читает записанный экран как поток RGB-кадров, обрезая под экран телефона."""

    def __init__(self, path, start, seconds):
        self.size = SCR_W * SCR_H * 3
        self.proc = subprocess.Popen(
            [FF, "-v", "quiet", "-ss", str(start), "-t", str(seconds), "-i", path,
             "-vf", f"fps={FPS},scale={SCR_W}:{SCR_H}:force_original_aspect_ratio=increase,crop={SCR_W}:{SCR_H}",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            stdout=subprocess.PIPE,
        )
        self.last = Image.new("RGB", (SCR_W, SCR_H), (30, 30, 30))

    def frame(self):
        buf = self.proc.stdout.read(self.size)
        if len(buf) == self.size:
            self.last = Image.frombytes("RGB", (SCR_W, SCR_H), buf)
        return self.last


MASK = Image.new("L", (SCR_W, SCR_H), 0)
ImageDraw.Draw(MASK).rounded_rectangle((0, 0, SCR_W - 1, SCR_H - 1), radius=70, fill=255)


def draw_phone(img, screen, y_off, tilt):
    d = ImageDraw.Draw(img)
    x, y = PHONE_X, int(PHONE_Y + y_off)
    # тень
    sh = Image.new("RGBA", (PHONE_W + 120, PHONE_H + 120), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((60, 80, PHONE_W + 60, PHONE_H + 80), radius=100, fill=(40, 20, 80, 70))
    from PIL import ImageFilter
    sh = sh.filter(ImageFilter.GaussianBlur(28))
    img.paste(sh, (x - 60, y - 60), sh)
    rrect(d, (x, y, x + PHONE_W, y + PHONE_H), 92, (28, 27, 31))
    img.paste(screen, (x + BEZEL, y + BEZEL), MASK)
    d.ellipse((x + PHONE_W / 2 - 16, y + BEZEL + 14, x + PHONE_W / 2 + 16, y + BEZEL + 46), fill=(28, 27, 31))


# ---------- конфетти ----------

def make_confetti(n=70):
    rnd = random.Random(7)
    return [dict(vx=rnd.uniform(-900, 900), vy=rnd.uniform(-1500, -500), r=rnd.uniform(0, 6.28),
                 vr=rnd.uniform(-8, 8), c=rnd.choice(BLOB_COLORS + [(103, 80, 164), (156, 64, 105), (0, 109, 59)]),
                 s=rnd.uniform(18, 34), shape=rnd.choice(["r", "c"])) for _ in range(n)]


CONFETTI = make_confetti()


def draw_confetti(img, t0, t, origin):
    if t < t0:
        return
    dt = t - t0
    if dt > 3:
        return
    d = ImageDraw.Draw(img)
    ox, oy = origin
    for p in CONFETTI:
        x = ox + p["vx"] * dt * 0.55
        y = oy + p["vy"] * dt * 0.55 + 1400 * dt * dt * 0.5
        if y > H + 50:
            continue
        s = p["s"]
        if p["shape"] == "c":
            d.ellipse((x - s / 2, y - s / 2, x + s / 2, y + s / 2), fill=p["c"])
        else:
            a = p["r"] + p["vr"] * dt
            ca, sa = math.cos(a), math.sin(a)
            pts = [(x + ca * u - sa * v, y + sa * u + ca * v)
                   for u, v in ((-s, -s / 2), (s, -s / 2), (s, s / 2), (-s, s / 2))]
            d.polygon(pts, fill=p["c"])


# ---------- сцены ----------

HOOK_END, PHONE_IN, PHONE_OUT, CTA_START = 2.3, 2.4, 11.7, 11.9
FEAT_START, FEAT_LEN = 3.2, 2.85


def draw_frame(t, c, clip):
    fi = int(clamp((t - FEAT_START) / FEAT_LEN, 0, 2.999)) if t >= FEAT_START else 0
    theme = THEMES[fi % 3]
    img = background(t, theme)
    d = ImageDraw.Draw(img)
    prim, cont, oncont, surf = theme

    # --- 1. Хук: слова выпрыгивают по очереди, потом улетают ---
    if t < HOOK_END + 0.5:
        out = ease_in(seg(t, HOOK_END - 0.1, 0.5))
        for i, line in enumerate(c["hook"][:3]):
            k = spring(seg(t, 0.15 + i * 0.28, 0.55))
            col = prim if i == 2 else TEXT
            pop_text(img, line, F_HUGE if i == 2 else F_BIG, col, W / 2 - out * W * (1 if i % 2 else -1),
                     640 + i * 190, k, rot=(-3 if i % 2 else 3))
        # чип версии
        kb = spring(seg(t, 1.1, 0.5))
        if kb > 0:
            bw = 600 * min(kb, 1.15)
            rrect(d, (W / 2 - bw / 2, 1250, W / 2 + bw / 2, 1350), 50, prim)
            if kb > 0.95:
                tw = d.textlength(c["badge"], font=F_CHIP)
                d.text((W / 2 - tw / 2, 1272), c["badge"], font=F_CHIP, fill=(255, 255, 255))

    # --- 2. Телефон + фичи ---
    if PHONE_IN <= t < PHONE_OUT + 0.7:
        tin = spring(seg(t, PHONE_IN, 0.9))
        tout = ease_in(seg(t, PHONE_OUT, 0.6))
        y_off = (1 - tin) * 1500 + tout * 1700 + math.sin(t * 1.6) * 8
        tt = t - FEAT_START
        screen = clip.frame() if clip else draw_screen_mock(t, tt, theme, c["typing"])
        draw_phone(img, screen, y_off, 0)
        d = ImageDraw.Draw(img)

        # заголовок приложения сверху
        pop_text(img, c["title"], F_TITLE, TEXT, W / 2, 150 - tout * 300, spring(seg(t, PHONE_IN + 0.3, 0.6)))

        # подписи фич: карточка над телефоном + пояснение под
        for i, (head, body) in enumerate(c["points"][:3]):
            s0 = FEAT_START + i * FEAT_LEN
            kin = spring(seg(t, s0, 0.55))
            kout = ease_in(seg(t, s0 + FEAT_LEN - 0.3, 0.3)) if i < 2 else tout
            k = kin * (1 - kout)
            if k <= 0.02 or t < s0:
                continue
            th = THEMES[i % 3]
            cw = 900
            cx, cy = W / 2, 300 + (1 - kin) * -200
            sc = max(k, 0.01)
            w2, h2 = cw * sc / 2, 90 * sc
            rrect(d, (cx - w2, cy - h2, cx + w2, cy + h2), int(60 * sc), th[0])
            if sc > 0.9:
                n = str(i + 1)
                d.ellipse((cx - w2 + 24, cy - 56, cx - w2 + 136, cy + 56), fill=(255, 255, 255))
                tw = d.textlength(n, font=F_TITLE)
                d.text((cx - w2 + 80 - tw / 2, cy - 44), n, font=F_TITLE, fill=th[0])
                d.text((cx - w2 + 168, cy - 44), head, font=F_TITLE, fill=(255, 255, 255))
            # пояснение
            ky = PHONE_Y + PHONE_H + 70 + (1 - kin) * 80 + tout * 300
            lines = _wrap(d, body, F_BODY, 900)
            for j, ln in enumerate(lines[:2]):
                pop_text(img, ln, F_BODY, TEXT, W / 2, ky + j * 62, k)

    # --- 3. CTA ---
    if t >= CTA_START:
        origin = (W / 2, 1050)
        draw_confetti(img, CTA_START + 0.3, t, origin)
        d = ImageDraw.Draw(img)
        pop_text(img, "Хочешь так же?", F_BIG, TEXT, W / 2, 560, spring(seg(t, CTA_START, 0.55)), rot=-2)
        pop_text(img, "Больше обзоров", F_TITLE, prim, W / 2, 690, spring(seg(t, CTA_START + 0.25, 0.55)))
        pop_text(img, "и обновлений в Telegram", F_BODY, TEXT_VAR, W / 2, 770, spring(seg(t, CTA_START + 0.4, 0.55)))
        k = spring(seg(t, CTA_START + 0.6, 0.7))
        pulse = 1 + 0.035 * math.sin((t - CTA_START) * 7) if k >= 1 else 1
        bw, bh = 880 * k * pulse, 190 * k * pulse
        cx, cy = W / 2, 1050
        if bw > 4:
            rrect(d, (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), int(bh / 2), prim)
        if k > 0.95:
            # значок самолётика
            px, py = cx - bw / 2 + 120, cy
            pts = [(-1, 0.05), (1.05, -0.85), (0.45, 0.9), (0.08, 0.22)]
            d.ellipse((px - 62, py - 62, px + 62, py + 62), fill=(255, 255, 255))
            d.polygon([(px + x * 30, py + y * 30) for x, y in pts], fill=prim)
            d.text((px + 100, cy - 38), c["cta"], font=F_CTA, fill=(255, 255, 255))
        # стрелка-подсказка
        ky = spring(seg(t, CTA_START + 1.2, 0.6))
        pop_text(img, "Подписывайся, ссылка в профиле", F_SMALL, TEXT_VAR, W / 2, 1290, ky)
        bounce = abs(math.sin((t - CTA_START) * 5)) * 24
        if ky > 0.9:
            ax, ay = W / 2, 1420 - bounce
            d.polygon([(ax - 50, ay), (ax + 50, ay), (ax, ay - 70)], fill=prim)
    return img


def _wrap(d, s, f, maxw):
    words, lines, cur = s.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if d.textlength(trial, font=f) <= maxw:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def main():
    content = json.load(open(sys.argv[1], encoding="utf-8"))
    content.setdefault("typing", "Привет! Это Expressive Keyboard")
    content.setdefault("hook", ["Клавиатура,", "которая тебя", "удивит"])
    out = sys.argv[2]
    clip = None
    if content.get("clip"):
        base = os.path.dirname(os.path.abspath(sys.argv[1]))
        path = content["clip"] if os.path.isabs(content["clip"]) else os.path.join(base, content["clip"])
        clip = Clip(path, content.get("clip_start", 0), PHONE_OUT - PHONE_IN + 2)
    p = subprocess.Popen(
        [FF, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-shortest",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "19", "-c:a", "aac", "-b:a", "96k", out],
        stdin=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    for f in range(FPS * DUR):
        t = f / FPS
        # кадры клипа читаем только пока телефон на экране, чтобы поток не убегал
        use_clip = clip if PHONE_IN <= t < PHONE_OUT + 0.7 else None
        p.stdin.write(draw_frame(t, content, use_clip).tobytes())
    p.stdin.close()
    p.wait()
    print("OK", out)


if __name__ == "__main__":
    main()
