#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx2.py —— 用最简代码测试 RX_LF 为何不匹配（逐步放宽）。"""
import re

lines = open(r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fingerprints.txt",
             "rb").read().decode("utf-8").split("\r\n")
ln = lines[5]
print("目标行 =", repr(ln))
pats = [
    r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$",
    r"^\s*LF  \s+[0-9A-F]{64}\s+[\d,]+ B\s*$",
    r"LF  ",
    r"\s*LF\s+",
    r"^\s*(raw|LF)\s+([0-9A-F]{64})\s+([\d,]+ B)\s*$",
]
for p in pats:
    print(f"{bool(re.match(p, ln))!s:5}  {p}")
print()
print("行尾是否含 CR：", repr(ln[-3:]))
print("空格数（LF 之后）：", len(ln) - len(ln.lstrip()))
print("片段 repr：", repr(ln[:12]))
