#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ledger-range-check.py —— 台账里有没有「区间形态」锚点（§2.4 L84-L90）？"""
import os
import re

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
LEDGER = os.path.join(WT, "scripts", "contract-bare-anchors.allowlist.txt")
RX_RANGE = re.compile(r"L\d+-L\d+")
lines = [l for l in open(LEDGER, encoding="utf-8").read().splitlines() if l.strip() and not l.startswith("#")]
rng = [l for l in lines if RX_RANGE.search(l)]
print(f"台账数据行 = {len(lines)}；含区间形态的行 = {len(rng)}")
for l in rng:
    print("   ", l)
