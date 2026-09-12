#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-seq.py —— 与 fix-readme-review3.py 完全相同的常量/顺序，独立复算各判据并打印中间量。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fix-readme-review3.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

EOL = m.EOL
t = open(m.RM, "rb").read().decode("utf-8")
print("初始行数 =", len(t.splitlines()))
print("D1_OLD 命中 =", t.count(m.D1_OLD))
t = t.replace(m.D1_OLD, m.D1_NEW.replace("\n", EOL), 1)
a = m.D1_NEW.replace("\n", EOL)
print("锚命中 =", t.count(a), " 行数 =", len(t.splitlines()))
t = t.replace(a, a + m.D1_NOTE.replace("\n", EOL), 1)
print("补记命中 =", t.count(m.D1_NOTE.replace("\n", EOL)), " 行数 =", len(t.splitlines()))
o = m.D2_OLD.replace("\n", EOL)
n = m.D2_NEW.replace("\n", EOL)
print("D2_OLD 命中 =", t.count(o), " 行数 =", len(t.splitlines()))
t = t.replace(o, n, 1)
print("D2_NEW 命中 =", t.count(n), " 行数 =", len(t.splitlines()))
print("『原句（』命中 =", t.count("原句（"))
print("『`2.1.0` 期』命中 =", t.count("`2.1.0` 期"))
for i, ln in enumerate(t.splitlines(), 1):
    if "2.1.0" in ln or "原句" in ln:
        print(f"  L{i}: {ln[:130]}")
print()
n_note = n_main = 0
for ln in t.splitlines():
    k = ln.count('SOURCE_SYSTEM="mock-mall"')
    if ("批次补记（2026-09-12 CT 批次" in ln) or ("原句（" in ln and "`2.1.0` 期" in ln):
        n_note += k
    else:
        n_main += k
print(f"n_note={n_note} n_main={n_main}")
f1 = t.count("各有一份 `EventContract.java` 常量副本")
f2 = t.count("两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B")
q1 = t.count("（历史陈述「两侧常量值经核对一致")
q2 = t.count('原句（`2.1.0` 期）：「- **`source_system` 取 `const: "mock-mall"`**')
print(f"f1={f1} f2={f2} q1={q1} q2={q2}")
