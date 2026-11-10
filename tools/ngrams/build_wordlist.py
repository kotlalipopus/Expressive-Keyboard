#!/usr/bin/env python3
"""Частотный список слов из предложений Leipzig Corpora Collection (CC BY 4.0) для языков без готового словаря.

  build_wordlist.py <выход.txt> <корпус.tar.gz> [<корпус.tar.gz> …] [--script ethiopic] [--max 30000] [--min 2]

Берутся только слова нужной письменности; порядок — по убыванию частоты (как в остальных dict/*.txt).
"""
import argparse
import collections
import io
import re
import sys
import tarfile

SCRIPTS = {
    # буквы и огласовки, без знаков препинания (። ፣) и эфиопских цифр
    "ethiopic": "[ሀ-ፚ፝-፟ᎀ-᎙ⶀ-⷟]+",
}


def sentences(path):
    with tarfile.open(path, "r:gz") as t:
        for m in t:
            if m.name.endswith("-sentences.txt"):
                with io.TextIOWrapper(t.extractfile(m), encoding="utf-8", errors="replace") as f:
                    for line in f:
                        yield line.split("\t", 1)[-1]


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("corpora", nargs="+")
    ap.add_argument("--script", default="ethiopic")
    ap.add_argument("--max", type=int, default=30000)
    ap.add_argument("--min", type=int, default=2)
    a = ap.parse_args()
    word = re.compile(SCRIPTS[a.script])
    c = collections.Counter()
    for p in a.corpora:
        for s in sentences(p):
            c.update(word.findall(s))
    words = [w for w, n in c.most_common() if n >= a.min and len(w) >= 1][: a.max]
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(words) + "\n")
    print(f"{len(words)} слов, всего разных {len(c)}")


if __name__ == "__main__":
    main()
