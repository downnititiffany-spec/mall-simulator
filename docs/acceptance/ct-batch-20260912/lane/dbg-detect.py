#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-detect.py —— 定位「补记文本是否真进了替换后正文」以及引文行判定为何为 0。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fix-readme-review3.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

t = open(m.RM, encoding="utf-8").read()
for tag, pat in (("批次补记（2026-09-12", "批次补记（2026-09-12"),
                 ("原句（`2.1.0` 期", "原句（`2.1.0` 期"),
                 ("'SOURCE_SYSTEM=\"mock-mall\"'", 'SOURCE_SYSTEM="mock-mall"')):
    print(f"{tag:40} 命中 = {t.count(pat)}")
print()
n = t.count("批次补记（2026-09-12")
print(f"D1_NOTE 在 D1_NEW 之内 = {m.D1_NEW.count('批次补记（2026-09-12')}")
print(f"D1_NEW 末尾 200 字 = {m.D1_NEW[-200:]!r}")
print()
t2 = t.replace(m.D1_OLD, m.D1_NEW.replace("\n", "\r\n"), 1)
print(f"替换后『批次补记（2026-09-12』命中 = {t2.count('批次补记（2026-09-12')}")
i = t2.find("SOURCE_SYSTEM")
while i >= 0:
    ln0 = t2.rfind("\n", 0, i) + 1
    ln1 = t2.find("\n", i)
    ln = t2[ln0:ln1]
    print(f"  命中 @ 行内：{ln[:70]!r} … 引文行={ln.lstrip().startswith('>')}")
    i = t2.find("SOURCE_SYSTEM", i + 1)
