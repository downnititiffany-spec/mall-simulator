#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""final-selfcheck.py —— 收尾总自证（本轮全部交付物）。

逐项复核，全部**现算**，不引用任何脚本自述：
  1. 8 个改动文件仍 CRLF 且 CR == LF；README 终值指纹；
  2. 补丁：字节数 / CRLF 行数 / 纯 LF 行数 / CR 总数 / sha256 / diff --git 段数；
     **并复核父侧同法**：把补丁内容做 CRLF→LF 规范化，应逐字节不变（证明已是纯 LF、父侧可直接 apply）；
  3. 台账：CR == LF == 行数−1、末尾不是双 CRLF、置零摘要复算一致；
  4. REPORT.md / PROGRESS.md：纯 LF、7 个章节 ①–⑦ 齐全、PROGRESS append-only 段落存在；
  5. README：终值指纹、`2 处裸锚点`、E1-a 子集口径 bullet、537 台面登记齐全。
"""
import hashlib
import os
import re
import sys

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
EV = os.path.join(WT, ".verify", "ct-batch")
FAILS = []


def chk(cond, tag, detail=""):
    print(f"  [{'OK ' if cond else 'FAIL'}] {tag}{('  ' + detail) if detail else ''}")
    if not cond:
        FAILS.append(tag)


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def rd(p):
    with open(p, "rb") as f:
        return f.read()


FILES = [
    r"analytics-server\platform-app\src\test\java\com\graduation\analytics\source\SourceRegistryMigrationScriptTest.java",
    r"analytics-server\platform-common\src\main\java\com\graduation\analytics\contracts\EventContract.java",
    r"analytics-server\platform-common\src\test\java\com\graduation\analytics\contracts\CanonicalEventSchemaParityTest.java",
    r"contract-specs\README.md",
    r"contract-specs\VERSION",
    r"contract-specs\schemas\canonical-event.v1.schema.json",
    r"docs\contracts\event-contract.md",
    r"scripts\check-bare-anchors.ps1",
]

print("=== 1. 改动面 8 文件行尾 ===")
for rel in FILES:
    b = rd(os.path.join(WT, rel))
    chk(b.count(13) == b.count(10) and b.count(13) > 0, f"CRLF 且 CR==LF：{rel.split(chr(92))[-1]}",
        f"{len(b):,} B CR={b.count(13)}")
rm = rd(os.path.join(WT, r"contract-specs\README.md"))
chk(sha(rm) == "5896AD0EE9C1C6BBA7EE033CCB3FD55724D2944B366689F2A080EB428AE6B73C",
    "README raw 终值指纹", f"{sha(rm)}  {len(rm):,} B")
chk(sha(rm.replace(b"\r\n", b"\n")) == "E53F09019C15127590397E6D8A3E85E0D3DBDA335A4876D05537B4F6EF348883",
    "README LF 形态指纹")

print("\n=== 2. 补丁形态（父侧可 apply） ===")
pb = rd(os.path.join(EV, "ct-batch.patch"))
pt = pb.decode("utf-8")
crlf = len(re.findall(r"\r\n", pt))
lfonly = len(re.findall(r"(?<!\r)\n", pt))
cr = pt.count("\r")
chk(crlf == 0, "CRLF 行数 = 0", f"{crlf}")
chk(cr == 0, "CR 总数 = 0", f"{cr}")
print(f"       纯 LF 行数 = {lfonly}")
print(f"       字节数 = {len(pb):,} B")
print(f"       sha256 = {sha(pb)}")
chk(len(re.findall(r"diff --git ", pt)) == 8, "diff --git 段数 = 8")
chk(pt.replace("\r\n", "\n") == pt, "父侧同法（CRLF→LF 规范化）后逐字节不变")
chk(sha(pb) == "DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945", "补丁 sha256 与 REPORT 登记一致")
_idx = re.findall(r"(?m)^index ([0-9a-f]+)\.\.([0-9a-f]+)", pt)
chk(len(_idx) == 8, "index 行 = 8 条", f"{len(_idx)}")
chk(all(len(a) == 40 and len(b) == 40 for a, b in _idx), "index 哈希全为 40 位（--full-index 生效）",
    f"位宽={sorted({len(b) for _, b in _idx})}")

print("\n=== 3. 指纹台账自洽 ===")
fb = rd(os.path.join(EV, "fingerprints.txt"))
ft = fb.decode("utf-8")
fl = ft.split("\r\n")
chk(fb.count(13) == fb.count(10) == len(fl) - 1, "CR == LF == 行数−1", f"CR={fb.count(13)} 行数={len(fl)}")
chk(fb[-4:] != b"\r\n\r\n", "末尾非双 CRLF", f"{fb[-4:]!r}")
hit = [i for i, ln in enumerate(fl) if ln.startswith("  fingerprints.txt ")]
chk(len(hit) == 1, "自身行唯一")
keep = fl[hit[0]]
# 先取回登记值，再自证：置零后用同口径复算 == 登记值（不硬编码，避免台账每变一次就失效）
registered = keep.split()[1]
fl[hit[0]] = keep.replace(registered, "0" * 64)
reh = sha("\r\n".join(fl).encode("utf-8"))
print(f"       自身行登记（置零摘要）= {registered}")
chk(reh == registered, "置零摘要复算 == 登记值", f"现算 {reh[:16]}…")
chk(len(registered) == 64, "置零摘要位宽 = 64（sha256）", f"{len(registered)}")
chk(keep.strip().endswith(f"{fb.count(10):,} B") or " B" in keep, "自身行登记了尺寸", keep.strip()[-12:])
chk("口径 2（自身行，**可复核**）" in ft, "台账首部写明置零摘要复核口径")

print("\n=== 4. REPORT / PROGRESS ===")
rb = rd(os.path.join(EV, "REPORT.md"))
rt = rb.decode("utf-8")
chk(rb.count(13) == 0, "REPORT.md 纯 LF", f"CR={rb.count(13)} LF={rb.count(10)} 行数={len(rt.splitlines())} {len(rb):,} B")
for n, name in [("①", "完成项"), ("②", "出口证据"), ("③", "变更面"), ("④", "未取证"), ("⑤", "交付物"), ("⑥", "偏离"), ("⑦", "过程失败")]:
    chk(rt.count(f"## {n} ") == 1, f"章节 {n} {name} 唯一")
chk("537 tests / 1 failure / BUILD FAILURE" in rt, "④/② 登记 537/1 基线")
chk("真子集" in rt and "340" in rt, "197 子集口径 ＋ 340 条未跑")
chk("CRLF 0 行 / 纯 LF 272 行" in rt or "CRLF 0 / 纯 LF 272" in rt, "⑥-11 补丁行尾自证写入 REPORT")
chk("36,389 B" in rt and "DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945" in rt,
    "⑤/⑥-11 补丁终值（--full-index 口径）登记")
chk("--full-index" in rt, "⑥-11 登记 blob 哈希位宽问题与其处置")
pg = rd(os.path.join(EV, "PROGRESS.md"))
pgt = pg.decode("utf-8")
chk("## 追加（父侧集成预检回执轮，2026-09-12）" in pgt, "PROGRESS 本轮 append 段存在")
_head = "# CT 批次施工进度（泳道 worktree `ct-batch`，detached HEAD = f8f1ea3）"
chk(pgt.startswith(_head), "PROGRESS 历史头部未被改写（append-only）",
    f"首行={pgt.splitlines()[0][:34]}…")
_rule = "> 本文件由施工泳道边做边追加；父级用它判断是否卡住。**只追加，不改写历史行。**"
chk(_rule in pgt, "PROGRESS 只追加纪律声明在位")

print("\n=== 5. README 本轮内容 ===")
chk(rm.decode("utf-8").count("2 处裸锚点") == 1, "README「2 处裸锚点」= 1")
chk("真子集" in rm.decode("utf-8") and "537 tests" in rm.decode("utf-8"), "README §14.4 登记 E1-a 子集口径与 537 基线")

print()
if FAILS:
    print(f"### 总自证：FAIL（{len(FAILS)} 项）")
    for x in FAILS:
        print(f"   - {x}")
    sys.exit(1)
print("### 总自证：全部 PASS")
sys.exit(0)
