#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-fp2.py —— 核查 fingerprints.txt 的行数/结尾表现，以及「置零摘要」复核为何不一致。"""
import hashlib

FP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\fingerprints.txt"
raw = open(FP, "rb").read()
print(f"文件 = {len(raw):,} B  CR={raw.count(13)} LF={raw.count(10)}")
print(f"结尾 4 字节 = {raw[-4:]!r}")
lines = raw.decode("utf-8").split("\r\n")
print(f"split('\\r\\n') 元素数 = {len(lines)}   末元素 = {lines[-1]!r}")
print(f"末元素为空？ {lines[-1] == ''}")
nonempty = [ln for ln in lines if ln != ""]
print(f"非空元素数 = {len(nonempty)}")
print()
for i in (66, 67, 68, 69, 70, 71, 72, 73):
    if i < len(lines):
        print(f"  [{i}] {lines[i][:96]!r}")
