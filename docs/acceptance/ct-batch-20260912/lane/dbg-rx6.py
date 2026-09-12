#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx6.py —— 对照测试：干净串 vs 台账串，同一模式。再用字符类试。"""
import importlib.util
import re
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\refresh-fingerprints.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

good = "    LF  " + "A" * 64 + "    57128 B"
bad = open(m.FP, "rb").read().decode("utf-8").split("\r\n")[5]
P = r"^(?P<ind>\s*LF  \s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$"
for tag, s in (("干净串", good), ("台账串", bad)):
    print(f"{tag}: 全文匹配={bool(re.match(P, s))}")
    for pl in (r"^     LF  ", r"^\s*LF  ", r"^\s*LF\s+", r"^\s*LF +", r"^\s*L", r"^\s*"):
        print(f"    {pl:14} → {bool(re.match(pl, s))}")
    print(f"    前 8 字符 == '    LF  ' ? {s[:8] == '    LF  '}   repr={s[:8]!r}")
    print()
print("用 re.fullmatch 再试台账串：", bool(re.fullmatch(P, bad)))
mo = re.match(r"^(?P<ind>\s*LF\s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$", bad)
print("把 '  ' 换成 \\s+ 后命中：", bool(mo), mo.groupdict() if mo else "")
