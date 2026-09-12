#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx4.py —— 直接导入 refresh-fingerprints.py 的 RX_LF，逐段验证它到底卡在哪。"""
import importlib.util
import re
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\refresh-fingerprints.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

ln = open(m.FP, "rb").read().decode("utf-8").split("\r\n")[5]
print("目标行 =", repr(ln))
print("RX_LF  读入后 =", repr(m.RX_LF.pattern))
print("RX_LF 匹配 =", bool(m.RX_LF.match(ln)))
ref = re.compile(r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$")
print("参照模式（现场手打）匹配 =", bool(ref.match(ln)))
print("两模式字符串相同 =", ref.pattern == m.RX_LF.pattern)
print()
print("逐段推进：")
seg = [
    (r"^(?P<ind>\s*LF  \s+)", "ind"),
    (r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})", "+hash"),
    (r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)", "+sp"),
    (r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)", "+n"),
    (r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$", "+$"),
]
for p, tag in seg:
    print(f"  {tag:6} {bool(re.match(p, ln))}")
