#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""scan-dump.py —— 用守卫同款规则把**逐处裸锚点**或**逐处合规锚点**打印出来（供与守卫读数逐行对齐）。"""
import os
import re
import sys

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
ROOT = os.path.join(WT, "contract-specs")
RX_ANCHOR = re.compile(r"§\d+(?:\.\d+)*\s*L\d+")          # 守卫 L37：不收区间
RX_RANGE = re.compile(r"§\d+(?:\.\d+)*\s*L\d+-L\d+")      # 区间形态（守卫会截成前半段）
RX_FILE = re.compile(r"[A-Za-z0-9_\-\./\\]+\.(?:md|json|yaml|yml|java|scala|sql|txt|csv)\b")
RX_VER = re.compile(r"[Vv]\d+\.\d+")
WIN = 100

want = sys.argv[1] if len(sys.argv) > 1 else "bare"
only = sys.argv[2] if len(sys.argv) > 2 else ""

for dp, _, fns in os.walk(ROOT):
    for fn in sorted(fns):
        p = os.path.join(dp, fn)
        rel = "contract-specs\\" + os.path.relpath(p, ROOT)
        if only and only not in rel:
            continue
        txt = open(p, encoding="utf-8").read()
        nb = nh = 0
        for m in RX_ANCHOR.finditer(txt):
            ls = txt.rfind("\n", 0, m.start()) + 1
            pref = txt[ls:m.start()][-WIN:]
            bare = not (RX_FILE.search(pref) or RX_VER.search(pref))
            nh += 0 if bare else 1
            if bare:
                nb += 1
            if (bare and want == "bare") or ((not bare) and want == "ok"):
                ln = txt.count("\n", 0, m.start()) + 1
                tail = txt[m.end():m.end() + 8]
                isrng = bool(RX_RANGE.match(txt[m.start():m.end() + 8]))
                print(f"{rel}|{m.group(0)}{'  [区间前半段]' if isrng else ''}|行{ln}|{'裸' if bare else '合规'}|后随={tail!r}")
        print(f"### {rel}  裸={nb}  合规={nh}  合计={nb + nh}")
