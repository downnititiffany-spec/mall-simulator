#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-fp.py —— 复现 fingerprints.txt 自哈希不动点迭代，打印每轮 (sha, len) 与候选行差异。"""
import hashlib
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\refresh-fingerprints.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

def sha(b):
    return hashlib.sha256(b).hexdigest().upper()

lines = open(m.FP, "rb").read().decode("utf-8").split("\r\n")
si = next(i for i, ln in enumerate(lines)
          if m.RX_DELIV.match(ln) and m.RX_DELIV.match(ln).group("name") == m.SELF_NAME)
mm = m.RX_DELIV.match(lines[si])
ind, pre, sp2 = mm.group("ind"), f"{mm.group('name')}{mm.group('sp')}", mm.group("sp2")
print(f"si={si} ind={ind!r} pre={pre!r} sp2={sp2!r}")
lines[si] = f"{ind}{pre}{m.SELF_PH}{sp2}{0:,} B"
prev = None
for it in range(12):
    probe = (m.EOL.join(lines) + m.EOL).encode("utf-8")
    h, n = sha(probe), len(probe)
    cand = f"{ind}{pre}{h}{sp2}{n:,} B"
    same = (cand == lines[si])
    print(f"轮 {it}: sha={h[:12]}…  len={n:,}  候选==当前行？ {same}   prev_len={prev}")
    prev = n
    if same:
        print("  收敛")
        break
    lines[si] = cand
