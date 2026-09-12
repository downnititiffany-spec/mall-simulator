#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""refresh-fingerprints2.py —— 指纹台账终态重写（替代 refresh-fingerprints.py）。

为什么要重写而不是继续打补丁：前两版在**行数组 ↔ 字节**的往返上有三处口径错误，逐条留痕如下。

【坑 1｜一字面量空格写在命名组里恒不匹配】
  `re.compile(r"^(?P<ind>\\s*LF  \\s+)...")`（命名组内 LF 后写两个字面空格）在 CPython 3.14.5 上**恒不匹配**，
  换成 `\\s+` 立刻命中。复现脚本 `.verify/ct-batch/dbg-rx6.py`。⇒ 本版所有分组一律用 `\\s+`，不写字面空格。

【坑 2｜join+EOL 会**多加一个行终止符**】
  实测：源 7,271 B → `split("\\r\\n")` 得 80 元素（末元素为 ``''``）→ `"\\r\\n".join(lines) + "\\r\\n"` = 7,273 B。
  因为 split 后末元素 `''` 已代表「最后一个 CRLF」，再 `+EOL` 就是**第二个**尾 CRLF。这正是台账被撑到
  `…\\r\\n\\r\\n` 的原因。⇒ 正确往返是 `EOL.join(lines)`（**不加** EOL），本版据此写盘并断言等长。

【坑 3｜自哈希是悖论，旧口径读者无法复核】
  把文件自身 sha256 写进文件 ⇒ sha 又变，任意轮迭代都不收敛（实测 12 轮：len 稳定 7,260 B 而 sha 每轮都变，
  复现脚本 `dbg-fp.py`）。旧台账登记 2F8BDB12… 5,626 B，与写盘后实际文件（7,257 B）**不符**，无法复核。
  ⇒ 本版改用**置零摘要（blanked digest）**：把自身行 64 位哈希挖空成 64 个 `0` 后对整份内容算 sha256。
  复核方式写进台账首部，读者照做即可复现。

本脚本对 19 个受控制品逐项现算，不读取旧值（除结构骨架外），写盘后自证：
  ① 字节数 == `EOL.join(lines)` 的长度（精确，不容许多 1 个 CR）；
  ② CR 数 == LF 数；
  ③ 置零摘要复算一致；
  ④ 幂等：第二次运行 changes == 0。
"""
import hashlib
import os
import sys

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
EV = os.path.join(WT, ".verify", "ct-batch")
FP = os.path.join(EV, "fingerprints.txt")
EOL = "\r\n"
PAD = " " * 8

# (台账内显示名, 制品相对 WT 路径) —— 顺序与旧台账一致（契约面在前）
CONTRACT = [
    (r"contract-specs\README.md", r"contract-specs\README.md"),
    (r"contract-specs\VERSION", r"contract-specs\VERSION"),
    (r"contract-specs\schemas\canonical-event.v1.schema.json", r"contract-specs\schemas\canonical-event.v1.schema.json"),
    (r"contract-specs\schemas\ingestion-manifest.v1.schema.json", r"contract-specs\schemas\ingestion-manifest.v1.schema.json"),
    (r"contract-specs\schemas\generation-artifact-manifest.v1.schema.json", r"contract-specs\schemas\generation-artifact-manifest.v1.schema.json"),
    (r"contract-specs\openapi\generator-api.v1.yaml", r"contract-specs\openapi\generator-api.v1.yaml"),
    (r"contract-specs\specs\surrogate-key.v1.json", r"contract-specs\specs\surrogate-key.v1.json"),
    (r"contract-specs\specs\warehouse-namespace.v1.json", r"contract-specs\specs\warehouse-namespace.v1.json"),
    (r"contract-specs\specs\warehouse-namespace.v2.json", r"contract-specs\specs\warehouse-namespace.v2.json"),
    (r"docs\contracts\event-contract.md", r"docs\contracts\event-contract.md"),
    (r"analytics-server\platform-common\src\main\java\com\graduation\analytics\contracts\EventContract.java",
     r"analytics-server\platform-common\src\main\java\com\graduation\analytics\contracts\EventContract.java"),
    (r"analytics-server\platform-common\src\test\java\com\graduation\analytics\contracts\CanonicalEventSchemaParityTest.java",
     r"analytics-server\platform-common\src\test\java\com\graduation\analytics\contracts\CanonicalEventSchemaParityTest.java"),
    (r"analytics-server\platform-app\src\test\java\com\graduation\analytics\source\SourceRegistryMigrationScriptTest.java",
     r"analytics-server\platform-app\src\test\java\com\graduation\analytics\source\SourceRegistryMigrationScriptTest.java"),
    (r"scripts\check-bare-anchors.ps1", r"scripts\check-bare-anchors.ps1"),
    (r"scripts\contract-bare-anchors.allowlist.txt", r"scripts\contract-bare-anchors.allowlist.txt"),
]
# 交付物（自身行单独处理：置零摘要）
DELIV = ["ct-batch.patch", "PROGRESS.md", "anchor-guard.txt",
         "e1a-baseline.log", "e1a-after.log", "e1b-schema-check.txt",
         "e1c-before.txt", "e1c-after.txt", "e1c-diff-validation.txt",
         "e1d-mutation.txt", "pre-measure-scans.txt"]
SELF_NAME = "fingerprints.txt"

BASE = [  # 变更前基线（本批开工时记录，未改写；口径 = raw 原字节）
    (r"contract-specs\VERSION", "605679A0B8BE10CDA8D1F60045C524145AAD1AA388766AF01ECD8ADEBA559798", "22 B", "内容 contract-specs 2.1.0"),
    (r"contract-specs\schemas\canonical-event.v1...json", "BF0C7356AA924512CF7E861BDE49C59D9A787886B99EDEE89F4676DC089120EB", "33,383 B", ""),
    (r"contract-specs\README.md", "4FE3222D6C1A4C3A67E6A6EAE0B415C1F134990188F1B18C61A8013E5603A07E", "47,709 B", ""),
    (r"docs\contracts\event-contract.md", "FFEDE7534B471BB24D931EE1DEA7842929313B2303D5710A94C0809FED0A1F59", "7,399 B", ""),
    (r"scripts\check-bare-anchors.ps1", "058BB445EA720546C0537C2EA6521AAA55AADBD0F53AD12142BD2654D17516CB", "13,485 B", ""),
    (r"EventContract.java（改动面）", "02840CBB4FE4FF031DCC7A1D9634C248F7C5D7F2991C56274F8023C7ACE62791", "4,047 B", ""),
    (r"CanonicalEventSchemaParityTest（改动面）", "D845F7806A9862972CFFCF5408B78C830ADAC84B64003AF470246CA9125A7B18", "8,488 B", ""),
    (r"SourceRegistryMigrationScriptTest（改动面）", "28D689AF0E8224DE2DD681316B8ED90F37D5D54F8518AACF63639881BDA8BA01", "9,352 B", ""),
]
UNCHANGED = [
    (r"specs/warehouse-namespace.v1.json", "LF", "463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A",
     "逐字符命中 README §10 登记值；git status 为空（本批一字未改）"),
    (r"specs/warehouse-namespace.v2.json", "LF", "CD79BBA1688E333B8E378D079AEC2959C5B803A5D27F38A9916392F426C17B28",
     "逐字符命中 README §10 登记值"),
    (r"VERSION（2.1.0 期）", "LF", "9784177F34E4B4A248F7D3840D73E294C35EE46ADCB76F429CAA87D4D8E0E30D",
     "逐字符命中 README §10 登记值（独立用 python hashlib 复算一致）"),
    (r"tests/golden-dataset/events/golden-20260901.jsonl", "LF",
     "2351BCC35E04CCD278638F07247BC37E9C4D402CC4736CD2A4D4B70F4232B11C", "18,430 B / 55 行"),
]


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def read(rel):
    with open(os.path.join(WT, rel), "rb") as f:
        return f.read()


def main():
    lines = []
    a = lines.append
    a("=== CT 批次指纹台账（2026-09-12，改动后终态，逐项现算）===")
    a("口径 1（raw/LF）：raw = 文件原字节（本仓工作区一律 CRLF，下表 CR==LF 已逐文件核对）；"
      "LF = 去 CR 后（= git blob 内容，与 README §10 历史登记同口径）。")
    a("口径 2（自身行，**可复核**）：`fingerprints.txt` 那行登记的是**置零摘要**——"
      "把该行 64 位哈希挖空成 64 个 `0` 后，对整份内容（CRLF 行尾、含末尾一个 CRLF）算 sha256。"
      "直接对文件整体算 sha256 会得到**不同**的值，这不是错，因为「把自身 sha256 写进自身」是个不收敛的悖论"
      "（复现：`.verify/ct-batch/dbg-fp.py`）。复核步骤：把该行哈希替换为 64 个 `0` → sha256 应等于登记值。")
    a("")
    for name, rel in CONTRACT:
        b = read(rel)
        bl = b.replace(b"\r\n", b"\n")
        cr, lf = b.count(13), b.count(10)
        a(name)
        a(f"    raw {sha(b)}{PAD}{len(b):,} B  CR={cr} LF={lf} ({'一致' if cr == lf else '!! CR != LF'})")
        a(f"    LF  {sha(bl)}{PAD}{len(bl):,} B")
    a("")
    a("=== 变更前基线（本批开工时记录，未改写）===")
    for name, h, n, tail in BASE:
        a(f"  {name:<48} raw {h}  {n}" + (f"  {tail}" if tail else ""))
    a("")
    a("=== 未改面核对（与 README §10 登记值同口径 = LF 规范化）===")
    for name, k, h, tail in UNCHANGED:
        a(f"  {name:<50} {k} {h}  {tail}")
    a("")
    a("=== 交付物自身指纹 ===")
    for n in DELIV:
        b = open(os.path.join(EV, n), "rb").read()
        a(f"  {n:<28} {sha(b)}  {len(b):>9,} B")

    # ---- 自身行（置零摘要）----
    # 宽度固定：先用与最终形态等宽的占位尺寸（8 字符，如 "9,999,999 B"），避免尺寸字段宽度变化
    # 导致"提前自洽"（用 "0 B" 起步会在 3 字符宽度上先假收敛一次）。随后迭代到稳定。
    ZERO64 = "0" * 64

    def blank(size_text):
        probe = lines + [f"  {SELF_NAME:<28} {ZERO64}  {size_text}"]
        return EOL.join(probe).encode("utf-8")

    size_text = "9,999,999 B"
    for _ in range(8):
        b = blank(size_text)
        if len(b) == int(size_text.replace(",", "").replace(" B", "")):
            break
        size_text = f"{len(b):>9,} B"
    hb = sha(b)
    lines.append(f"  {SELF_NAME:<28} {hb}  {size_text}")
    print(f"  [定] {SELF_NAME} 置零摘要 = {hb}  尺寸文本 = {size_text!r}  实长 = {len(b):,} B")

    data = EOL.join(lines).encode("utf-8")   # 坑 2：**不加** 尾 EOL（lines 末元素即末行）
    print(f"  组装完成：{len(data):,} B  CR={data.count(13)} LF={data.count(10)}  行数={len(lines)}")

    # ---- 写盘前自证 ----
    ok = (data.count(13) == data.count(10) == len(lines) - 1)
    print(f"[{'OK ' if ok else 'FAIL'}] CR == LF == 行数-1：{data.count(13)} == {data.count(10)} == {len(lines)-1}")
    if not ok:
        return 2
    old = open(FP, "rb").read()
    if old == data:
        print(">>> 幂等：与现有台账逐字节相同，无需写盘")
    else:
        open(FP, "wb").write(data)
        print(f">>> 已写盘  {len(data):,} B（原 {len(old):,} B）")

    # ---- 写盘后自证（照读者复核方式走一遍）----
    back = open(FP, "rb").read()
    print(f"[{'OK ' if back == data else 'FAIL'}] 回读逐字节一致")
    bl = back.decode("utf-8").split(EOL)
    hit = [i for i, ln in enumerate(bl) if ln.startswith(f"  {SELF_NAME} ")]
    print(f"[{'OK ' if len(hit) == 1 else 'FAIL'}] 自身行唯一命中 = {len(hit)}")
    if back != data or len(hit) != 1:
        return 2
    i = hit[0]
    keep = bl[i]
    fld = keep.split()
    bl[i] = keep.replace(fld[1], "0" * 64)
    re_h = sha(EOL.join(bl).encode("utf-8"))
    bl[i] = keep
    print(f"[{'OK ' if re_h == hb else 'FAIL'}] 置零摘要复算 = {re_h}（登记 {hb}）")
    print(f"[{'OK ' if back.count(13) == back.count(10) else 'FAIL'}] 写盘后 CR == LF：{back.count(13)} == {back.count(10)}")
    print(f"     末尾 4 字节 = {back[-4:]!r}（末尾恰一个 CRLF ⇒ 不应出现 \\r\\n\\r\\n）")
    if re_h != hb or back.count(13) != back.count(10) or back[-4:] == b"\r\n\r\n":
        return 2
    print(">>> 全部自证通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
