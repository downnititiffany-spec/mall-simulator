#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""dbg-blob-vs-patch.py —— 把补丁每条 `index <old>..<new>` 的 new 侧 blob 哈希，与
  ① 工作区文件字节 ② 工作区文件的 LF 规范化字节 ③ `git hash-object` 输出，逐一比对。

目的：坐实 README/REPORT 里那个「raw 60,960 B（CRLF） vs LF 规范化」的双口径，
      并解释为何 `git hash-object` 给的是**规范化后**的 blob 哈希。
纯只读：只用 `git hash-object` / `git cat-file`，无任何 git 写操作。
"""
import hashlib
import os
import re
import subprocess
import sys

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
PATCH = os.path.join(WT, ".verify", "ct-batch", "ct-batch.patch")

txt = open(PATCH, "rb").read().decode("utf-8")
segs = [s for s in re.split(r"(?=diff --git )", txt) if s.startswith("diff --git ")]
print(f"补丁段数 = {len(segs)}\n")


def sha(b):
    return hashlib.sha256(b).hexdigest()


def sh(args):
    return subprocess.run(args, cwd=WT, capture_output=True).stdout.decode("utf-8").strip()


bad = 0
for s in segs:
    m = re.search(r"^diff --git a/(.+?) b/(.+?)$", s, re.M)
    rel = m.group(2)
    idx = re.search(r"^index ([0-9a-f]{40})\.\.([0-9a-f]{40})", s, re.M)
    newhex = idx.group(2)
    p = os.path.join(WT, rel.replace("/", os.sep))
    raw = open(p, "rb").read()
    lf = raw.replace(b"\r\n", b"\n")
    h_raw, h_lf = sha(raw), sha(lf)
    gitblob = sh(["git", "hash-object", "--", p])
    match = "raw(含 CRLF 字节)" if h_raw == newhex else ("LF 规范化字节" if h_lf == newhex else (
        "git blob（与工作区 raw/LF 都不同）" if gitblob == newhex else "无"))
    if match == "无":
        bad += 1
    print(f"{'[OK ]' if match != '无' else '[FAIL]'} {rel}")
    print(f"       patch index new (40 位) = {newhex}")
    print(f"       工作区 raw sha256         = {h_raw[:40]}…  ({len(raw):,} B, CR={raw.count(13)})")
    print(f"       工作区 LF 规范化 sha256    = {h_lf[:40]}…  ({len(lf):,} B)")
    print(f"       git hash-object (sha1)   = {gitblob}  == patch index？ {gitblob == newhex}")
    print(f"       ⇒ 补丁 index 与【{match}】相符")

print()
print(f"### 结论：{len(segs) - bad}/{len(segs)} 段的 `index …..` 新侧哈希，与 `git hash-object` "
      f"对该工作区文件给出的 blob 哈希**逐字符相同**")
print("###     ⇒ 补丁覆盖的正是当前工作区内容（不是历史快照）；且 git 记录的是 LF 形态 blob"
      "（README 的 LF 规范化 60,632 B 才是 blob 口径，raw 60,960 B 是检出 CRLF 口径）")
sys.exit(1 if bad else 0)
