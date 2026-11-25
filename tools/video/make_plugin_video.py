"""Обзор плагина визуальных эффектов (1080x1920, ~55 c) в стиле Material 3 Expressive.

Использование:
    python make_plugin_video.py "C:/path/to/demo.mp4" out-plugin.mp4

Берёт запись экрана (клавиатура с включёнными эффектами) и режет её на сцены:
на каждый эффект — свой отрезок записи, подпись и описание.
"""
import math
import subprocess
import sys

from PIL import Image, ImageDraw

import make_video as mv
from make_video import (FF, FPS, H, W, TEXT, TEXT_VAR, clamp, ease_in, mix, pop_text, rrect, seg, spring,
                        _wrap, background, draw_confetti, F_BIG, F_BODY, F_CHIP, F_CTA, F_HUGE, F_SMALL, F_TITLE)

THEMES = mv.THEMES + [
    ((0, 95, 175), (212, 227, 255), (0, 28, 58), (244, 248, 255)),
    ((130, 80, 0), (255, 221, 170), (42, 24, 0), (255, 248, 240)),
]

# (название, описание, секунда в записи, где эффект виден лучше всего)
EFFECTS = [
    ("Keys Cafe", "Мягкое свечение под каждой нажатой клавишей", 0.3),
    ("Glow", "Неоновый ореол вокруг клавиш", 4.3),
    ("Sparks", "Разлетающиеся искры при нажатии", 9.0),
    ("Background", "Фоновые частицы: снег, матрица, пузыри, линии, звёзды, сакура", 16.5),
    ("Ambient Bloom", "Пятно света следует за последней клавишей", 20.5),
    ("Ambient Breath", "Клавиатура «дышит» мягким свечением", 24.5),
    ("Cyberpunk Wave", "Неоновая сетка искажается от нажатий", 28.3),
    ("Popup symbols", "Всплывающий символ над клавишей", 32.5),
    ("Falling letters", "Буквы падают вниз от нажатой клавиши", 40.0),
    ("Confetti", "Взрыв конфетти при нажатии", 44.5),
    ("Presets", "Готовые цветовые схемы одним тапом", 53.0),
]

HOOK_END = 2.6
SCENE = 4.3
CTA_START = HOOK_END + SCENE * len(EFFECTS)
DUR = int(CTA_START + 5.2)

CARD_W, CARD_H = 960, 800
CARD_X, CARD_Y = (W - CARD_W) // 2, 650
MASK = Image.new("L", (CARD_W, CARD_H), 0)
ImageDraw.Draw(MASK).rounded_rectangle((0, 0, CARD_W - 1, CARD_H - 1), radius=64, fill=255)


class Clip:
    def __init__(self, path, start, seconds):
        self.size = CARD_W * CARD_H * 3
        self.proc = subprocess.Popen(
            [FF, "-v", "quiet", "-ss", str(start), "-t", str(seconds), "-i", path,
             "-vf", f"fps={FPS},crop=720:600:0:50,scale={CARD_W}:{CARD_H}",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            stdout=subprocess.PIPE,
        )
        self.last = Image.new("RGB", (CARD_W, CARD_H), (30, 30, 30))

    def frame(self):
        buf = self.proc.stdout.read(self.size)
        if len(buf) == self.size:
            self.last = Image.frombytes("RGB", (CARD_W, CARD_H), buf)
        return self.last

    def close(self):
        self.proc.stdout.close()
        self.proc.kill()


def scene_index(t):
    return int(clamp((t - HOOK_END) / SCENE, 0, len(EFFECTS) - 0.001))


def draw_card(img, frame, theme, k, y_off):
    d = ImageDraw.Draw(img)
    pad = 14
    x, y = CARD_X, CARD_Y + y_off
    sc = 0.9 + 0.1 * min(k, 1.1)
    if sc < 0.92:
        return
    w, h = CARD_W * sc, CARD_H * sc
    cx, cy = x + CARD_W / 2, y + CARD_H / 2
    rrect(d, (cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad), 78, theme[0])
    f = frame.resize((int(w), int(h)), Image.BILINEAR)
    m = MASK.resize((int(w), int(h)), Image.BILINEAR)
    img.paste(f, (int(cx - w / 2), int(cy - h / 2)), m)


def draw_frame(t, clip):
    si = scene_index(t) if t >= HOOK_END else 0
    theme = THEMES[si % len(THEMES)]
    if t >= CTA_START:
        theme = THEMES[0]
    img = background(t, theme)
    d = ImageDraw.Draw(img)
    prim = theme[0]

    # --- хук ---
    if t < HOOK_END + 0.5:
        out = ease_in(seg(t, HOOK_END - 0.1, 0.5))
        for i, line in enumerate(["Визуальные", "эффекты", "для клавиатуры"]):
            k = spring(seg(t, 0.15 + i * 0.28, 0.55))
            pop_text(img, line, F_HUGE if i == 1 else F_BIG, prim if i == 1 else TEXT,
                     W / 2 - out * W * (1 if i % 2 else -1), 700 + i * 200, k, rot=(-3 if i % 2 else 3))
        kb = spring(seg(t, 1.1, 0.5))
        if kb > 0:
            bw = 620 * min(kb, 1.15)
            rrect(d, (W / 2 - bw / 2, 1330, W / 2 + bw / 2, 1430), 50, prim)
            if kb > 0.95:
                s = "Плагин Expressive Keyboard"
                tw = d.textlength(s, font=mv.F_KEY)
                d.text((W / 2 - tw / 2, 1362), s, font=mv.F_KEY, fill=(255, 255, 255))

    # --- сцены эффектов ---
    if HOOK_END <= t < CTA_START + 0.7:
        tin = spring(seg(t, HOOK_END, 0.8))
        tout = ease_in(seg(t, CTA_START, 0.6))
        s0 = HOOK_END + si * SCENE
        name, desc, _ = EFFECTS[si]
        kin = spring(seg(t, s0, 0.5))
        kout = ease_in(seg(t, s0 + SCENE - 0.25, 0.25)) if si < len(EFFECTS) - 1 else tout
        k = kin * (1 - kout)

        pop_text(img, "Визуальные эффекты", F_SMALL, TEXT_VAR, W / 2, 130 - tout * 300, tin)

        # чип: номер + название
        if k > 0.02:
            sc = max(k, 0.01)
            cw = 860
            w2, h2 = cw * sc / 2, 82 * sc
            cy = 300
            rrect(d, (W / 2 - w2, cy - h2, W / 2 + w2, cy + h2), int(60 * sc), prim)
            if sc > 0.9:
                n = str(si + 1)
                d.ellipse((W / 2 - w2 + 20, cy - 56, W / 2 - w2 + 132, cy + 56), fill=(255, 255, 255))
                tw = d.textlength(n, font=F_TITLE)
                d.text((W / 2 - w2 + 76 - tw / 2, cy - 44), n, font=F_TITLE, fill=prim)
                d.text((W / 2 - w2 + 166, cy - 44), name, font=F_TITLE, fill=(255, 255, 255))
            lines = _wrap(d, desc, F_BODY, 900)
            for j, ln in enumerate(lines[:3]):
                pop_text(img, ln, F_BODY, TEXT, W / 2, 470 + j * 62, k)

        # карточка с записью экрана
        draw_card(img, clip.frame() if clip else Image.new("RGB", (CARD_W, CARD_H), (30, 30, 30)), theme,
                  tin * (1 - tout), (1 - tin) * 1400 + tout * 1700 + math.sin(t * 1.6) * 6)
        d = ImageDraw.Draw(img)

        # индикатор прогресса
        n = len(EFFECTS)
        for i in range(n):
            x = W / 2 + (i - (n - 1) / 2) * 56
            y = 1600 + tout * 400
            if i == si:
                rrect(d, (x - 22, y - 11, x + 22, y + 11), 11, prim)
            else:
                d.ellipse((x - 10, y - 10, x + 10, y + 10), fill=mix(prim, (255, 255, 255), 0.6))

    # --- CTA ---
    if t >= CTA_START:
        origin = (W / 2, 1050)
        draw_confetti(img, CTA_START + 0.8, t, origin)
        d = ImageDraw.Draw(img)
        pop_text(img, "Хочешь так же?", F_BIG, TEXT, W / 2, 560, spring(seg(t, CTA_START + 0.5, 0.55)), rot=-2)
        pop_text(img, "Больше обзоров", F_TITLE, prim, W / 2, 690, spring(seg(t, CTA_START + 0.75, 0.55)))
        pop_text(img, "и обновлений в Telegram", F_BODY, TEXT_VAR, W / 2, 770, spring(seg(t, CTA_START + 0.9, 0.55)))
        k = spring(seg(t, CTA_START + 1.1, 0.7))
        pulse = 1 + 0.035 * math.sin((t - CTA_START) * 7) if k >= 1 else 1
        bw, bh = 880 * k * pulse, 190 * k * pulse
        cx, cy = W / 2, 1050
        if bw > 4:
            rrect(d, (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), int(bh / 2), prim)
        if k > 0.95:
            px, py = cx - bw / 2 + 120, cy
            pts = [(-1, 0.05), (1.05, -0.85), (0.45, 0.9), (0.08, 0.22)]
            d.ellipse((px - 62, py - 62, px + 62, py + 62), fill=(255, 255, 255))
            d.polygon([(px + x * 30, py + y * 30) for x, y in pts], fill=prim)
            d.text((px + 100, cy - 38), "@materialyouboss", font=F_CTA, fill=(255, 255, 255))
        ky = spring(seg(t, CTA_START + 1.7, 0.6))
        pop_text(img, "Подписывайся, ссылка в профиле", F_SMALL, TEXT_VAR, W / 2, 1290, ky)
        if ky > 0.9:
            ax, ay = W / 2, 1420 - abs(math.sin((t - CTA_START) * 5)) * 24
            d.polygon([(ax - 50, ay), (ax + 50, ay), (ax, ay - 70)], fill=prim)
    return img


def main():
    src, out = sys.argv[1], sys.argv[2]
    p = subprocess.Popen(
        [FF, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-shortest",
         "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "19", "-c:a", "aac", "-b:a", "96k", out],
        stdin=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    clip, clip_si = None, -1
    for f in range(FPS * DUR):
        t = f / FPS
        in_scenes = HOOK_END <= t < CTA_START + 0.7
        si = scene_index(t)
        if in_scenes and si != clip_si:
            if clip:
                clip.close()
            length = SCENE + (0.8 if si == len(EFFECTS) - 1 else 0.1)
            clip, clip_si = Clip(src, EFFECTS[si][2], length), si
        p.stdin.write(draw_frame(t, clip if in_scenes else None).tobytes())
    p.stdin.close()
    p.wait()
    print("OK", out)


if __name__ == "__main__":
    main()
