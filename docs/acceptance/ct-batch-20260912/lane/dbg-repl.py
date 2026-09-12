#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-repl.py —— 精确复刻 fix-readme-review3.py 的替换步骤，逐步打印各判据的中间量。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fix-readme-review3.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

t = open(m.RM, "rb").read().decode("utf-8")
print("step0 D1_OLD =", t.count(m.D1_OLD))
t = t.replace(m.D1_OLD, m.D1_NEW, 1)
a = m.D1_NEW
print("step1 anchor =", t.count(a))
t = t.replace(a, a + m.D1_NOTE, 1)
print("step2 NOTE =", t.count(m.D1_NOTE))
print("step2 行数 =", len(t.splitlines()))
t = t.replace(m.D2_OLD, m.D2_NEW, 1)
print("step3 行数 =", len(t.splitlines()))

lines = t.splitlines()
n_note = n_main = 0
for i, ln in enumerate(lines, 1):
    k = ln.count('SOURCE_SYSTEM="mock-mall"')
    c_batch = "批次补记（2026-09-12 CT 批次" in ln
    c_orig = "原句（" in ln
    c_ver = "`2.1.0` 期" in ln
    if c_batch or (c_orig and c_ver):
        n_note += k
        if k:
            print(f"  引文 L{i}: k={k} batch={c_batch} orig={c_orig} ver={c_ver}")
    else:
        n_main += k
        if k:
            print(f"  正文 L{i}: k={k} batch={c_batch} orig={c_orig} ver={c_ver}")
print(f"n_note={n_note} n_main={n_main}")
f1 = t.count("各有一份 `EventContract.java` 常量副本")
f2 = t.count("两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B")
q1 = t.count("（历史陈述「两侧常量值经核对一致")
q2 = t.count('原句（`2.1.0` 期）：「- **`source_system` 取 `const: "mock-mall"`**')
print(f"f1={f1} f2={f2} q1={q1} q2={q2}")
