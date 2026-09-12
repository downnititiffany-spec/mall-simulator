#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-lines.py —— 打印「待替换正文」与「替换后正文」里所有相关行的行号＋内容，供判据校准。"""
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "m", r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fix-readme-review3.py")
m = importlib.util.module_from_spec(spec)
sys.modules["m"] = m
src = open(spec.origin, encoding="utf-8").read().replace('if __name__ == "__main__":', "if False:")
exec(compile(src, spec.origin, "exec"), m.__dict__)

PATS = ["各有一份 `EventContract.java` 常量副本",
        "两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B",
        "（历史陈述「两侧常量值经核对一致",
        '原句（`2.1.0` 期）：「- **`source_system` 取 `const: "mock-mall"`**',
        'SOURCE_SYSTEM="mock-mall"']


def dump(t, label):
    print(f"########## {label} ##########")
    for ln_no, ln in enumerate(t.split("\r\n"), 1):
        hits = [p for p in PATS if p in ln]
        if hits:
            print(f"L{ln_no}: 命中 {len(hits)} 条 → {[h[:22] for h in hits]}")
            print(f"        {ln[:300]}")


t = open(m.RM, encoding="utf-8").read()
dump(t, "改动前")
t2 = t.replace(m.D1_OLD, m.D1_NEW.replace("\n", "\r\n"), 1)
anchor = m.D1_NEW.replace("\n", "\r\n")
t2 = t2.replace(anchor, anchor + m.D1_NOTE.replace("\n", "\r\n"), 1)
t2 = t2.replace(m.D2_OLD.replace("\n", "\r\n"), m.D2_NEW.replace("\n", "\r\n"), 1)
dump(t2, "模拟改动后")
