#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-trace.py —— 复刻 fix-readme-review3.py 的替换序列并对最终文本逐条报数（先于门禁判据）。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fix-readme-review3.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

EOL = m.EOL
t = open(m.RM, encoding="utf-8").read()
print("步骤 0：D1_OLD 命中 =", t.count(m.D1_OLD.replace("\n", EOL)))
t = t.replace(m.D1_OLD.replace("\n", EOL), m.D1_NEW.replace("\n", EOL), 1)
anchor = m.D1_NEW.replace("\n", EOL)
print("步骤 1：锚命中 =", t.count(anchor))
t = t.replace(anchor, anchor + m.D1_NOTE.replace("\n", EOL), 1)
print("步骤 2：补记命中 =", t.count(m.D1_NOTE.replace("\n", EOL)))
print("步骤 3：D2_OLD 命中 =", t.count(m.D2_OLD.replace("\n", EOL)))
t = t.replace(m.D2_OLD.replace("\n", EOL), m.D2_NEW.replace("\n", EOL), 1)
print("步骤 4：D2_NEW 命中 =", t.count(m.D2_NEW.replace("\n", EOL)))
print()
lines = t.split(EOL)
print("总行数 =", len(lines))
n_note = n_main = 0
for ln in lines:
    k = ln.count('SOURCE_SYSTEM="mock-mall"')
    if ("批次补记（2026-09-12 CT 批次" in ln) or ("原句（" in ln and "`2.1.0` 期" in ln):
        n_note += k
        print(f"  [引文行] +{k}  {ln[:90]}")
    else:
        n_main += k
        if k:
            print(f"  [正文行] +{k}  {ln[:90]}")
print(f"n_note={n_note}  n_main={n_main}")
f1 = t.count("各有一份 `EventContract.java` 常量副本")
f2 = t.count("两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B")
q1 = t.count("（历史陈述「两侧常量值经核对一致")
q2 = t.count('原句（`2.1.0` 期）：「- **`source_system` 取 `const: "mock-mall"`**')
print(f"fake_raw = {f1} + {f2} = {f1 + f2}    quoted = {q1} + {q2} = {q1 + q2}")
