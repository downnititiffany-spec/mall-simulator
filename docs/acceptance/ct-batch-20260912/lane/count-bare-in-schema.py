#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""count-bare-in-schema.py —— 按守卫同款正则（§x.y Lnnn，且前面无文件名/版本前缀）数本 schema 的裸锚点。"""
import re
import sys

P = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\schemas\canonical-event.v1.schema.json"
t = open(P, encoding="utf-8").read()
# 与守卫同款：锚点 = §数字(.数字)* + 空格 + L数字(-L数字)?
ANCHOR = re.compile(r"§\d+(?:\.\d+)*(?:[ \t]+L\d+(?:-L\d+)?)")
# 合规前缀：紧邻锚点前 1 个非空白字符序列里含「文件名」或「版本号」
PREFIX = re.compile(r"(?:[\w./\\-]+\.(?:md|json|yaml|yml|sql|java|scala|ps1|txt|VERSION)|v\d+\.\d+(?:\.\d+)?)\s*$")

bare = {}
pref = 0
for m in ANCHOR.finditer(t):
    before = t[max(0, m.start() - 80):m.start()]
    if PREFIX.search(before):
        pref += 1
    else:
        bare[m.group(0)] = bare.get(m.group(0), 0) + 1
print(f"锚点总数 = {pref + sum(bare.values())}；合规（带前缀）= {pref}；裸锚点 = {sum(bare.values())} 处 / {len(bare)} 行")
for k, v in sorted(bare.items()):
    print(f"    {k:20} × {v}")
print()
print("--- 逐处裸锚点上下文 ---")
for m in ANCHOR.finditer(t):
    before = t[max(0, m.start() - 80):m.start()]
    if not PREFIX.search(before):
        seg = t[max(0, m.start() - 55):m.end() + 20].replace("\r", "").replace("\n", " ")
        print(f"    …{seg}…")
