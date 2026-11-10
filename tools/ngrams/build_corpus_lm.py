#!/usr/bin/env python3
"""Языковая модель подсказок из нескольких корпусов сразу: разговорные субтитры (OpenSubtitles, OPUS),
веб, новости и Википедия (Leipzig Corpora Collection, CC BY 4.0) и пары из Google Books Ngrams (уже посчитаны
build_bigrams.py). Каждый корпус считается отдельно, вероятности смешиваются с весами — так разговорная речь не
тонет в книжной, а книжная закрывает то, чего нет в субтитрах.

  build_corpus_lm.py count <имя> <вход> <каталог>     — посчитать пары и тройки одного корпуса
        вход: .txt.gz (строка — предложение) или .tar.gz Лейпцига (берётся *-sentences.txt)
        у субтитров каждая 100-я строка уходит в <каталог>/heldout.txt и в счёт не идёт
  build_corpus_lm.py books <каталог_шардов> <каталог>   — пары Google Books (rus-*.tsv.gz) в тот же формат
  build_corpus_lm.py eval <каталог> --mix subs=0.6,web=0.2,books=0.2 [--tri]
  build_corpus_lm.py build <каталог> --mix … --bi out_bi.txt --tri out_tri.txt [--top 64]
        --top — сколько продолжений хранить у самых частых слов (в assets лежит модель с --top 64)

Формат выхода как у build_bigrams.py: «слово следующее/вес …», вес — round(-2·ln P); тройки — «слово слово следующее/вес …».
"""
import argparse
import gzip
import io
import math
import os
import re
import sys
import tarfile
from collections import defaultdict

import numpy as np

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "dict")
VOCAB = 60000        # слова модели — самые частые из ru.txt
PREV = 40000         # первое слово пары
CTX = 25000          # оба слова контекста тройки
TOP_NEXT = 12
TOP_TRI = 8
WORD = re.compile(r"[а-яё]+(?:-[а-яё]+)*|[^\sа-яё]+")
SHIFT = 16
MASK = (1 << SHIFT) - 1


def vocab():
    with open(os.path.join(ASSETS, "ru.txt"), encoding="utf-8") as f:
        words = [w.strip() for w in f if w.strip()][:VOCAB]
    return words, {w: i for i, w in enumerate(words)}


def lines_of(path):
    if path.endswith(".tar.gz"):
        with tarfile.open(path, "r:gz") as t:
            for m in t:
                if m.name.endswith("-sentences.txt"):
                    with io.TextIOWrapper(t.extractfile(m), encoding="utf-8", errors="replace") as f:
                        for line in f:
                            yield line.split("\t", 1)[-1]
                    return
    else:
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
            yield from f


class Acc:
    """Счётчики ключей: копятся в буфере, время от времени сворачиваются numpy.unique."""

    def __init__(self):
        self.buf = []
        self.keys = np.zeros(0, np.int64)
        self.cnt = np.zeros(0, np.int64)

    def add(self, k):
        self.buf.append(k)
        if len(self.buf) >= 8_000_000:
            self.flush()

    def flush(self):
        if not self.buf:
            return
        k, c = np.unique(np.array(self.buf, np.int64), return_counts=True)
        self.buf = []
        allk = np.concatenate([self.keys, k])
        allc = np.concatenate([self.cnt, c.astype(np.int64)])
        order = np.argsort(allk, kind="stable")
        allk, allc = allk[order], allc[order]
        uk, idx = np.unique(allk, return_index=True)
        self.keys, self.cnt = uk, np.add.reduceat(allc, idx)
        # память: при большом числе ключей одиночки отбрасываются (для топа вариантов они не нужны)
        if len(self.keys) > 60_000_000:
            keep = self.cnt > 1
            self.keys, self.cnt = self.keys[keep], self.cnt[keep]


def count(a):
    words, idx = vocab()
    os.makedirs(a.dir, exist_ok=True)
    bi, tri, uni = Acc(), Acc(), np.zeros(VOCAB, np.int64)
    held = open(os.path.join(a.dir, "heldout.txt"), "w", encoding="utf-8") if a.holdout else None
    n = 0
    for line in lines_of(a.input):
        n += 1
        if a.lines and n > a.lines:
            break
        if held is not None and n % 100 == 0:
            if n <= 3_000_000 and a.part[0] == 0:
                held.write(line.strip() + "\n")
            continue
        if n % a.part[1] != a.part[0]:
            continue
        p2 = p1 = -1
        for tok in WORD.findall(line.lower()):
            i = idx.get(tok)
            if i is None and "ё" in tok:
                i = idx.get(tok.replace("ё", "е"))
            if i is None:
                # знак препинания или неизвестное слово — граница: пары через него не считаются
                p2 = p1 = -1
                continue
            uni[i] += 1
            if p1 >= 0 and p1 < PREV:
                bi.add((p1 << SHIFT) | i)
                if p2 >= 0 and p2 < CTX and p1 < CTX:
                    tri.add((p2 << (2 * SHIFT)) | (p1 << SHIFT) | i)
            p2, p1 = p1, i
        if n % 1_000_000 == 0:
            print(f"{a.name}: {n // 1_000_000} млн строк", flush=True)
    bi.flush()
    tri.flush()
    np.savez(os.path.join(a.dir, f"{a.name}.npz"), uni=uni, bk=bi.keys, bc=bi.cnt, tk=tri.keys, tc=tri.cnt)
    print(f"{a.name}: строк {n}, пар {len(bi.keys)}, троек {len(tri.keys)}")


def books(a):
    words, idx = vocab()
    keys, cnts = [], []
    for name in sorted(os.listdir(a.shards)):
        if not (name.startswith("rus") and name.endswith(".tsv.gz")):
            continue
        with gzip.open(os.path.join(a.shards, name), "rt", encoding="utf-8") as f:
            for line in f:
                p, w, c = line.rstrip("\n").split("\t")
                i, j = idx.get(p), idx.get(w)
                if i is None or j is None or i >= PREV:
                    continue
                keys.append((i << SHIFT) | j)
                cnts.append(int(c))
    k = np.array(keys, np.int64)
    c = np.array(cnts, np.int64)
    order = np.argsort(k)
    k, c = k[order], c[order]
    uk, ix = np.unique(k, return_index=True)
    np.savez(os.path.join(a.dir, "books.npz"), uni=np.zeros(VOCAB, np.int64), bk=uk, bc=np.add.reduceat(c, ix),
             tk=np.zeros(0, np.int64), tc=np.zeros(0, np.int64))
    print(f"books: пар {len(uk)}")


def merge(parts):
    k = np.concatenate([p[0] for p in parts])
    c = np.concatenate([p[1] for p in parts])
    order = np.argsort(k, kind="stable")
    k, c = k[order], c[order]
    uk, i = np.unique(k, return_index=True)
    return uk, np.add.reduceat(c, i) if len(k) else c


def load(d, mix):
    """Корпус — <имя>.npz или части <имя>.p0.npz, <имя>.p1.npz … (count --part), части складываются."""
    out = {}
    for name in mix:
        one = os.path.join(d, f"{name}.npz")
        files = [one] if os.path.exists(one) else sorted(
            os.path.join(d, f) for f in os.listdir(d) if f.startswith(f"{name}.p") and f.endswith(".npz"))
        zs = [np.load(f) for f in files]
        bk, bc = merge([(z["bk"], z["bc"]) for z in zs])
        tk, tc = merge([(z["tk"], z["tc"]) for z in zs])
        out[name] = (bk, bc, tk, tc)
    return out


def table(keys, cnts, ctx_shift, top, min_total=3):
    """Контекст → [(вероятность, следующее)] по одному корпусу (топ по частоте)."""
    ctx = keys >> SHIFT
    nxt = (keys & MASK).astype(np.int64)
    res = {}
    if len(keys) == 0:
        return res
    starts = np.flatnonzero(np.r_[True, ctx[1:] != ctx[:-1]])
    ends = np.r_[starts[1:], len(keys)]
    for s, e in zip(starts, ends):
        c = cnts[s:e]
        tot = c.sum()
        if tot < min_total:
            continue
        o = np.argsort(-c)[: top * 3]
        res[int(ctx[s])] = [(c[i] / tot, int(nxt[s + i])) for i in o]
    return res


def mixed(tables, mix, top):
    """Смесь корпусов: P(w|ctx) = Σ λ·P_корпуса(w|ctx); если корпус контекста не знает, его доля делится между остальными."""
    out = {}
    for ctx in set().union(*[t.keys() for t in tables.values()]):
        have = [n for n in tables if ctx in tables[n]]
        lam = sum(mix[n] for n in have)
        acc = defaultdict(float)
        for n in have:
            for p, w in tables[n][ctx]:
                acc[w] += mix[n] / lam * p
        out[ctx] = sorted(((p, w) for w, p in acc.items()), reverse=True)[:top]
    return out


def build_tables(a):
    mix = dict((k, float(v)) for k, v in (x.split("=") for x in a.mix.split(",")))
    data = load(a.dir, mix)
    top = max(TOP_NEXT, getattr(a, "top", TOP_NEXT))
    bi = mixed({n: table(d[0], d[1], SHIFT, top) for n, d in data.items()}, mix, top)
    # глубина по частоте первого слова: у самых частых слов продолжений много (по ним ранжируется дописывание
    # после первых букв), у редких хватает дюжины. Номер слова в словаре — его место по частоте
    if top > TOP_NEXT:
        def depth(ctx):
            return top if ctx < 3000 else max(TOP_NEXT, top // 2) if ctx < 12000 else TOP_NEXT
        # совсем редкие продолжения (реже 1 из 2500) сверх обычной дюжины только раздувают файл
        bi = {c: [pw for k, pw in enumerate(v[:depth(c)]) if k < TOP_NEXT or pw[0] >= 0.0004] for c, v in bi.items()}
    tri = {}
    if a.tri:
        tmix = {n: mix[n] for n, d in data.items() if len(d[2])}
        tri = mixed({n: table(data[n][2], data[n][3], SHIFT, TOP_TRI, min_total=a.tri_min) for n in tmix}, tmix, TOP_TRI)
        # в файл идут только уверенные продолжения частых контекстов — иначе десятки МБ имён из субтитров
        tri = {c: [(p, w) for p, w in v if p >= a.tri_p][:5] for c, v in tri.items()}
        tri = {c: v for c, v in tri.items() if v}
    return bi, tri


def predict(bi, tri, p2, p1):
    out = []
    if p2 >= 0 and p1 >= 0:
        for _, w in tri.get((p2 << SHIFT) | p1, []):
            if w not in out:
                out.append(w)
    for _, w in bi.get(p1, []):
        if w not in out:
            out.append(w)
    return out[:3]


def evaluate(a):
    words, idx = vocab()
    bi, tri = build_tables(a)
    total = top1 = top3 = 0
    with open(a.held or os.path.join(a.dir, "heldout.txt"), encoding="utf-8") as f:
        sents = [l for l in f][: a.limit]
    for line in sents:
        p2 = p1 = -1
        for tok in WORD.findall(line.lower()):
            i = idx.get(tok)
            if i is None:
                p2 = p1 = -1
                continue
            if p1 >= 0:
                guess = predict(bi, tri, p2, p1)
                total += 1
                if guess[:1] == [i]:
                    top1 += 1
                if i in guess:
                    top3 += 1
            p2, p1 = p1, i
    print(f"{a.mix}{' +тройки' if a.tri else ''}: top-1 {100 * top1 / total:.1f}%, top-3 {100 * top3 / total:.1f}% ({total} слов)")


def build(a):
    words, idx = vocab()
    bi, tri = build_tables(a)
    with open(a.bi, "w", encoding="utf-8", newline="\n") as f:
        for ctx in sorted(bi, key=lambda c: words[c]):
            cells = " ".join(f"{words[w]}/{min(35, max(0, round(-2 * math.log(p))))}" for p, w in bi[ctx])
            f.write(f"{words[ctx]} {cells}\n")
    if a.tri:
        kept = 0
        with open(a.tri, "w", encoding="utf-8", newline="\n") as f:
            for ctx in sorted(tri, key=lambda c: (words[c >> SHIFT], words[c & MASK])):
                # тройка нужна, только если меняет первое место по сравнению с парой
                b = [w for _, w in bi.get(ctx & MASK, [])[:3]]
                t = [w for p, w in tri[ctx] if p >= 0.04]
                if not t or t[:3] == b[: len(t[:3])]:
                    continue
                kept += 1
                cells = " ".join(f"{words[w]}/{min(35, max(0, round(-2 * math.log(p))))}" for p, w in tri[ctx] if p >= 0.04)
                f.write(f"{words[ctx >> SHIFT]} {words[ctx & MASK]} {cells}\n")
        print(f"троек записано {kept}, {os.path.getsize(a.tri) / 1e6:.1f} МБ")
    print(f"пар: {len(bi)} слов, {os.path.getsize(a.bi) / 1e6:.1f} МБ")


def heldout_phrases(a):
    """Отложенные субтитры → src/test/resources/heldout_ru.txt: отрезки от знака до знака, от 3 слов, только слова словаря."""
    words, idx = vocab()
    out = []
    with open(os.path.join(a.dir, "heldout.txt"), encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i % 7:
                continue
            cur = []
            for tok in WORD.findall(line.lower()):
                if tok in idx:
                    cur.append(tok)
                    continue
                if len(cur) >= 3:
                    out.append(" ".join(cur))
                cur = []
            if len(cur) >= 3:
                out.append(" ".join(cur))
            if len(out) >= a.count:
                break
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out[: a.count]) + "\n")
    print(f"фраз {min(len(out), a.count)}")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("count")
    c.add_argument("name")
    c.add_argument("input")
    c.add_argument("dir")
    c.add_argument("--holdout", action="store_true")
    c.add_argument("--lines", type=int, default=0)
    c.add_argument("--part", default="0/1", help="k/N — считать только строки с номером ≡ k (mod N), для параллельного запуска")
    b = sub.add_parser("books")
    b.add_argument("shards")
    b.add_argument("dir")
    for cmd in ("eval", "build"):
        e = sub.add_parser(cmd)
        e.add_argument("dir")
        e.add_argument("--mix", required=True)
        e.add_argument("--tri", action="store_true") if cmd == "eval" else e.add_argument("--tri", default="")
        e.add_argument("--limit", type=int, default=20000)
        e.add_argument("--top", type=int, default=TOP_NEXT, help="продолжений пары у самых частых слов (у редких — меньше)")
        e.add_argument("--tri-min", type=int, default=60, help="минимум вхождений контекста тройки в корпусе")
        e.add_argument("--tri-p", type=float, default=0.08, help="минимальная вероятность продолжения тройки")
        e.add_argument("--held", default="", help="другой файл отложенных предложений (по умолчанию <каталог>/heldout.txt)")
        if cmd == "build":
            e.add_argument("--bi", required=True)
    h = sub.add_parser("heldout")
    h.add_argument("dir")
    h.add_argument("out")
    h.add_argument("--count", type=int, default=1500)
    a = ap.parse_args()
    if a.cmd == "count":
        a.part = tuple(int(x) for x in a.part.split("/"))
        if a.part[1] > 1:
            a.name = f"{a.name}.p{a.part[0]}"
    {"count": count, "books": books, "eval": evaluate, "build": build, "heldout": heldout_phrases}[a.cmd](a)


if __name__ == "__main__":
    main()
