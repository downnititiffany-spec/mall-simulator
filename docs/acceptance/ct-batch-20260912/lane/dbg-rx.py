#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-rx.py —— 验证 refresh-fingerprints.py 的 raw/LF 正则能否匹配台账前几行。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\refresh-fingerprints.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

lines = open(m.FP, "rb").read().decode("utf-8").split("\r\n")
for i in range(3, 9):
    ln = lines[i]
    print(f"[{i}] RAW={bool(m.RX_RAW.match(ln))} LF={bool(m.RX_LF.match(ln))} "
          f"indent={ln.startswith(' ')} repr={ln[:78]!r}")
