#!/usr/bin/env python3
"""把五笔单字码表和现有拼音词表合成 code<TAB>word，供键盘按编码出字。"""
import json
import sys
from collections import defaultdict

if len(sys.argv) < 4:
    sys.exit(f"usage: {sys.argv[0]} <source> <lexicon> <out>")

SOURCE = sys.argv[1]
LEXICON = sys.argv[2]
OUT = sys.argv[3]


def phrase_code(codes):
    n = len(codes)
    if n == 2:
        return (codes[0][:2] + codes[1][:2])[:4]
    if n == 3:
        return (codes[0][:1] + codes[1][:1] + codes[2][:2])[:4]
    return (codes[0][:1] + codes[1][:1] + codes[2][:1] + codes[-1][:1])[:4]


def han(text):
    return text and all("\u4e00" <= ch <= "\u9fff" for ch in text)


full = {}
rows = defaultdict(list)
with open(SOURCE, encoding="utf-8") as handle:
    for line in handle:
        parts = line.split()
        if len(parts) < 3 or not parts[1].isalpha() or not han(parts[0]):
            continue
        try:
            weight = int(parts[2])
        except ValueError:
            continue
        word, code = parts[0], parts[1].lower()
        # parts[1].isalpha() 已在上方校验，.lower() 保留字母性，无需重复判 code.isalpha()。
        if len(word) != 1:
            continue
        rows[code].append((weight, word))
        previous = full.get(word)
        if previous is None or len(code) > len(previous):
            full[word] = code

with open(LEXICON, encoding="utf-8") as handle:
    lexicon = json.load(handle)
for entry in lexicon.get("entries", []):
    word = entry.get("w")
    raw_freq = entry.get("f")
    if not word or raw_freq is None:
        continue
    try:
        freq = int(raw_freq)
    except (TypeError, ValueError):
        continue
    # 只收常用短词。生僻词仍可以拆开用单字打，避免码表大到第一次按键要等很久。
    if freq < 280 or not han(word) or len(word) < 2 or len(word) > 4:
        continue
    codes = [full.get(ch) for ch in word]
    if any(code is None for code in codes):
        continue
    code = phrase_code(codes)
    rows[code].append((freq, word))

with open(OUT, "w", encoding="utf-8") as handle:
    for code in sorted(rows):
        ranked = sorted(rows[code], key=lambda item: item[0], reverse=True)
        seen = set()
        phrases = []
        for _, word in ranked:
            if word in seen:
                continue
            seen.add(word)
            if len(word) == 1:
                handle.write(f"{code}\t{word}\n")
            elif len(phrases) < 8:
                phrases.append(word)
        for word in phrases:
            handle.write(f"{code}\t{word}\n")


def first(code):
    for line in open(OUT, encoding="utf-8"):
        key, word = line.rstrip("\n").split("\t", 1)
        if key == code:
            return word
    return None


checks = {
    "g": "一",
    "gg": "五",
    "x": "经",
    "r": "的",
    "q": "我",
    "wq": "你",
    "wqvb": "你好",
    "khlg": "中国",
    "trwu": "我们",
}
failed = False
for code, word in checks.items():
    got = first(code)
    print(f"{code} -> {got}")
    if got != word:
        failed = True
if failed:
    sys.exit(1)
