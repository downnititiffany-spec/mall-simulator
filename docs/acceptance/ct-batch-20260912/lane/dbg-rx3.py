#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx3.py —— 逐字符打印目标行前 12 个字符的码位，定位 RX_LF 为何不匹配。"""
lines = open(r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fingerprints.txt",
             "rb").read().decode("utf-8").split("\r\n")
ln = lines[5]
for i, ch in enumerate(ln[:14]):
    print(f"  [{i}] {ch!r}  U+{ord(ch):04X}")
print("python 里 'LF  ' =", [f"U+{ord(c):04X}" for c in "LF  "])
print("源码字面量检查：本文件里写的模式 =", repr(r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$"))
print("注意：上面 'LF' 之后是两个空格？ =", repr(r"LF  "))
