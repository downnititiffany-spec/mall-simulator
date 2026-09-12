#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ledger-vs-scan.py —— 把台账（文件|锚点|次数）与我现在按守卫同款"行长局部 + 前 100 字符窗口"规则
逐文件独立复算的结果**并列对比**。目的：把"守卫读数 / 台账自述 / 独立复算"三者关系摆到台面上，
不做互相印证，也不掩盖差异。

规则（照抄守卫 `check-bare-anchors.ps1` L37-L60 与台账表头 L152）：
  锚点 = §<节号> L<行号>（行内，不前跨行）
  裸锚点 = 该锚点**所在行内、锚点之前**的文本里，既无文件名(*.md|json|yaml|yml|java|scala|sql|txt|csv)
           也无版本号(V<x>.<y>)；且只回看**最多 100 字符**。
"""
import os
import re
from collections import Counter

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
ROOT = os.path.join(WT, "contract-specs")
LEDGER = os.path.join(WT, "scripts", "contract-bare-anchors.allowlist.txt")

RX_ANCHOR = re.compile(r"§\d+(?:\.\d+)*\s*L\d+(?:-L\d+)?")
RX_FILE = re.compile(r"[A-Za-z0-9_\-\./\\]+\.(?:md|json|yaml|yml|java|scala|sql|txt|csv)\b")
RX_VER = re.compile(r"[Vv]\d+\.\d+")
WIN = 100


def scan():
    per = {}
    for dp, _, fns in os.walk(ROOT):
        if "\\.git" in dp or "\\target" in dp or "\\node_modules" in dp:
            continue
        for fn in fns:
            p = os.path.join(dp, fn)
            rel = "contract-specs\\" + os.path.relpath(p, ROOT)
            txt = open(p, encoding="utf-8").read()
            cnt = Counter()
            for m in RX_ANCHOR.finditer(txt):
                ls = txt.rfind("\n", 0, m.start()) + 1
                pref = txt[ls:m.start()][-WIN:]
                if not (RX_FILE.search(pref) or RX_VER.search(pref)):
                    cnt[m.group(0)] += 1
            if cnt:
                per[rel] = cnt
    return per


def ledger():
    d = {}
    base = None
    for ln in open(LEDGER, encoding="utf-8").read().splitlines():
        if not ln.strip():
            continue
        if ln.startswith("#"):
            m = re.match(r"^#\s*行数基线（只减不增）\s*=\s*(\d+)", ln)
            if m:
                base = int(m.group(1))
            continue
        f, a, n = ln.split("|")
        d[(f.strip(), a.strip())] = int(n)
    return d, base


sc = scan()
lg, base = ledger()
scan_total = sum(sum(c.values()) for c in sc.values())
scan_rows = sum(len(c) for c in sc.values())
lg_total = sum(lg.values())
print(f"独立复算（守卫同款规则）: 文件 {len(sc)} 个；裸锚点 {scan_total} 处 / {scan_rows} 行")
print(f"台账自述              : 数据行 {len(lg)} 行；出现次数合计 {lg_total} 处；表头行数基线 = {base}")
print()
for f in sorted(set(list(sc) + [k[0] for k in lg])):
    a = sum(sc.get(f, {}).values())
    b = sum(v for (k, _), v in lg.items() if k == f)
    flag = "  一致" if a == b else f"  !! 差 {a - b:+d}"
    print(f"  {f:68} 复算 {a:4d}  台账 {b:4d}{flag}")
print()
print("--- 台账有、复算无（或复算次数更低）的条目 ---")
for (f, an), v in sorted(lg.items()):
    got = sc.get(f, {}).get(an, 0)
    if got != v:
        print(f"  {f} :: {an}  台账={v} 复算={got}")
print()
print("--- 复算有、台账无的条目 ---")
for f, c in sorted(sc.items()):
    for an, v in sorted(c.items()):
        if (f, an) not in lg:
            print(f"  {f} :: {an}  复算={v} 台账=缺")
