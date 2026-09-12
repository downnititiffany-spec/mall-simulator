#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-report-e7.py —— 逐字符比对脚本里的 E_OLD 与 REPORT 实际行，找出首个不同码位。"""
import difflib
import sys

sys.path.insert(0, r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch")
RP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\REPORT.md"
E_OLD = ("- **E1-a 未覆盖模块**：`platform-app` 未纳入本批测试面（原因见 ② E1-a），其内含 "
         "`SourceRegistryMigrationScriptTest` 的断言本体**已由代码审读确认未改**，但**本轮无该模块的测试读数**。")

lines = open(RP, "rb").read().decode("utf-8").split("\n")
tgt = [ln for ln in lines if "E1-a 未覆盖模块" in ln]
print(f"命中行数 = {len(tgt)}")
if not tgt:
    raise SystemExit(0)
actual = tgt[0]
print(f"脚本 E_OLD 长度 = {len(E_OLD)}   实际行长度 = {len(actual)}")
print(f"相等？ {E_OLD == actual}")
for i, (a, b) in enumerate(zip(E_OLD, actual)):
    if a != b:
        print(f"首个不同：idx={i}  脚本={a!r} U+{ord(a):04X}   实际={b!r} U+{ord(b):04X}")
        print(f"  脚本上下文 = {E_OLD[max(0,i-25):i+25]!r}")
        print(f"  实际上下文 = {actual[max(0,i-25):i+25]!r}")
        break
else:
    print("前 min 长度全同；差异在长度（尾部）：")
    print(f"  脚本尾部 = {E_OLD[len(actual):]!r}")
    print(f"  实际尾部 = {actual[len(E_OLD):]!r}")
print()
for ln in difflib.unified_diff([E_OLD], [actual], "脚本E_OLD", "实际行", lineterm="", n=0):
    print(ln)
