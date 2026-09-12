#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx5.py —— 用代码点亮校验和搜出模式串里那个「不是 0x20」的字符。"""
pat = r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$"
print("模式前 12 个码位：")
for i, ch in enumerate(pat[:12]):
    print(f"  [{i}] U+{ord(ch):04X} {ch!r}")
ref = "^(?P<ind>" + "\\s*" + "LF" + "  " + "\\s+)"
print()
print("目标前缀期望：'    LF  ' =", [f"U+{ord(c):04X}" for c in "    LF  "])
print("模式前 12 是否以 '    LF  ' 结尾？", pat[:12])
print()
import re
print("re.match(r'^    LF  ', line) =", bool(re.match(r"^    LF  ", "    LF  D25A")))
print("re.match(r'^(?P<ind>\\s*LF  \\s+)', line) =", bool(re.match(r"^(?P<ind>\s*LF  \s+)", "    LF  D25A")))
