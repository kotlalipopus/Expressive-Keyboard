#!/usr/bin/env python3
"""Языковая модель для подсказок: пары слов из Google Books Ngrams (2-граммы, версия 20200217, CC BY 3.0).

Шарды читаются потоком (на диск не сохраняются), от каждого остаётся маленький файл с парами,
поэтому прерванную сборку можно продолжить.

  build_bigrams.py scan  <rus|eng> <каталог> [--workers N] [--shards 0,1,2]
  build_bigrams.py merge <rus|eng> <каталог> <выход.txt> [--max-pairs N]

Формат выхода: строка «предыдущее слово/вес слово/вес …», вес — round(-2·ln P(слово | предыдущее)).
Ещё пишется <выход>.names: слова, которые в книгах почти всегда с заглавной (имена, города).
"""
import argparse
import collections
import concurrent.futures as cf
import gzip
import http.client
import math
import os
import sys
import time
import urllib.request
import zlib

BASE = "https://storage.googleapis.com/books/ngrams/books/20200217"
TOTAL = {"rus": 69, "eng": 589}
DICT = {"rus": "ru", "eng": "en"}
MIN_YEAR = 1980
SHARD_MIN = 5          # реже — шум; итоговый порог выше, в merge
PREV_WORDS = 30000     # первое слово пары — среди стольких самых частых
NEXT_WORDS = 60000     # второе — среди стольких
TOP_NEXT = 12          # вариантов на одно предыдущее слово
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "dict")


def vocab(lang):
    with open(os.path.join(ASSETS, DICT[lang] + ".txt"), encoding="utf-8") as f:
        words = [w.strip() for w in f if w.strip()]
    return set(words[:PREV_WORDS]), set(words[:NEXT_WORDS])


def lines_of(url):
    """Строки шарда потоком; обрыв связи — продолжаем с того же байта."""
    d = zlib.decompressobj(31)
    pos = 0
    tail = b""
    attempts = 0
    while True:
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={pos}-"} if pos else {})
            with urllib.request.urlopen(req, timeout=120) as r:
                while True:
                    block = r.read(1 << 20)
                    if not block:
                        # соединение закрылось раньше конца файла: это обрыв, а не конец шарда
                        if not d.eof:
                            raise ConnectionError(f"обрыв на байте {pos}: {url}")
                        if tail:
                            yield tail
                        return
                    pos += len(block)
                    attempts = 0
                    data = tail
                    while block:
                        if d.eof:  # следующий блок gzip в том же файле
                            d = zlib.decompressobj(31)
                        data += d.decompress(block)
                        block = d.unused_data
                    parts = data.split(b"\n")
                    tail = parts.pop()
                    yield from parts
        except (OSError, http.client.HTTPException):
            attempts += 1
            if attempts > 8:
                raise
            time.sleep(5 * attempts)


def scan_shard(args):
    lang, n, out_dir = args
    out = os.path.join(out_dir, f"{lang}-{n:05d}.tsv.gz")
    if os.path.exists(out):
        return n, 0.0, 0
    prev_set, next_set = vocab(lang)
    url = f"{BASE}/{lang}/2-{n:05d}-of-{TOTAL[lang]:05d}.gz"
    pairs = collections.Counter()
    cap = collections.Counter()
    low = collections.Counter()
    t0 = time.time()
    for raw in lines_of(url):
        tab = raw.find(b"\t")
        if tab < 0:
            continue
        gram = raw[:tab]
        if b"_" in gram:  # разметка частей речи дублирует те же счётчики
            continue
        try:
            a, b = gram.decode("utf-8").split(" ")
        except ValueError:
            continue
        la, lb = a.lower(), b.lower()
        if la not in prev_set or lb not in next_set:
            continue
        count = 0
        for cell in raw[tab + 1:].split(b"\t"):
            y = cell.find(b",")
            if int(cell[:y]) >= MIN_YEAR:
                count += int(cell[y + 1:cell.find(b",", y + 1)])
        if count <= 0:
            continue
        pairs[la, lb] += count
        # заглавная в середине фразы (после строчного слова) — признак имени
        if a == la:
            if b == lb:
                low[lb] += count
            elif b[0].isupper() and b[1:] == lb[1:]:
                cap[lb] += count
    tmp = out + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8", compresslevel=6) as f:
        for (a, b), c in pairs.items():
            if c >= SHARD_MIN:
                f.write(f"{a}\t{b}\t{c}\n")
        for w, c in cap.items():
            f.write(f"^\t{w}\t{c}\n")
        for w, c in low.items():
            f.write(f"_\t{w}\t{c}\n")
    os.replace(tmp, out)
    return n, time.time() - t0, len(pairs)


def scan(a):
    os.makedirs(a.dir, exist_ok=True)
    shards = [int(x) for x in a.shards.split(",")] if a.shards else list(range(TOTAL[a.lang]))
    jobs = [(a.lang, n, a.dir) for n in shards]
    with cf.ProcessPoolExecutor(a.workers) as ex:
        done = 0
        for n, sec, count in ex.map(scan_shard, jobs):
            done += 1
            print(f"{done}/{len(jobs)} шард {n}: {sec:.0f} с, пар {count}", flush=True)


def merge(a):
    nexts = collections.defaultdict(collections.Counter)
    cap = collections.Counter()
    low = collections.Counter()
    for name in sorted(os.listdir(a.dir)):
        if not (name.startswith(a.lang) and name.endswith(".tsv.gz")):
            continue
        with gzip.open(os.path.join(a.dir, name), "rt", encoding="utf-8") as f:
            for line in f:
                p, w, c = line.rstrip("\n").split("\t")
                if p == "^":
                    cap[w] += int(c)
                elif p == "_":
                    low[w] += int(c)
                else:
                    nexts[p][w] += int(c)
    scored = []
    totals = {p: sum(c.values()) for p, c in nexts.items()}
    for p, c in nexts.items():
        for w, n in c.most_common(TOP_NEXT):
            scored.append((n, p, w))
    # при лимите остаются самые частые пары: от них больше всего пользы
    scored.sort(reverse=True)
    kept = collections.defaultdict(list)
    for n, p, w in scored[:a.max_pairs]:
        kept[p].append((n, w))
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        for p in sorted(kept):
            items = sorted(kept[p], reverse=True)
            cells = " ".join(f"{w}/{min(35, round(-2 * math.log(n / totals[p])))}" for n, w in items)
            f.write(f"{p} {cells}\n")
    names = sorted(w for w, c in cap.items() if c >= 200 and c >= 8 * low[w])
    with open(a.out + ".names", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(names) + "\n")
    print(f"пар {min(len(scored), a.max_pairs)}, слов {len(kept)}, имён {len(names)}, {os.path.getsize(a.out) / 1e6:.1f} МБ")


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan")
    s.add_argument("lang", choices=TOTAL)
    s.add_argument("dir")
    s.add_argument("--workers", type=int, default=4)
    s.add_argument("--shards", default="")
    m = sub.add_parser("merge")
    m.add_argument("lang", choices=TOTAL)
    m.add_argument("dir")
    m.add_argument("out")
    m.add_argument("--max-pairs", type=int, default=200000)
    a = ap.parse_args()
    {"scan": scan, "merge": merge}[a.cmd](a)


if __name__ == "__main__":
    main()
