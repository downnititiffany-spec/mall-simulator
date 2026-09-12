#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-fp3.py —— 一刀定位：join+EOL 与源字节是否等长；并复现 refresh 的 lines 演变是否新增元素。"""
EOL = "\r\n"
FP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fingerprints.txt"
raw = open(FP, "rb").read()
lines = raw.decode("utf-8").split(EOL)
body = EOL.join(lines) + EOL
print(f"源字节            = {len(raw):,} B   split 元素 = {len(lines)}")
print(f"join+EOL 重算      = {len(body.encode('utf-8')):,} B")
print(f"等长？ {len(raw) == len(body.encode('utf-8'))}")
print(f"源末尾 4 字节      = {raw[-4:]!r}")
print(f"末元素 == '' ？    {lines[-1] == ''}")
print(f"源中 CRLF 数       = {raw.count(13)}   （= split 元素数 - 1 ？ {len(lines)-1}）")
print()
print("== 关键：分块长度核对（把文件按 200 B 切，看是否与已知结构吻合）==")
import re
RX_DELIV = re.compile(r"^(?P<ind>\s+)(?P<name>[A-Za-z0-9_.\-]+\.(?:patch|md|txt|log))(?P<sp>\s+)(?P<h>[0-9A-F]{64})(?P<sp2>\s+)(?P<n>[\d,]+ B)\s*$")
for i, ln in enumerate(lines):
    if ln == "" or ln.strip() == "":
        print(f"  [空行] index={i} repr={ln!r}")
print(f"  总元素 {len(lines)}，末尾空元素个数 = {sum(1 for x in lines[::-1] if x == '' )}")
