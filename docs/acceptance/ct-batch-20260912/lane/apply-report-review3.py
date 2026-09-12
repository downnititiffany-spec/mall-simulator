#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-report-review3.py —— REPORT.md 补丁事实从 `core.abbrev` 短哈希口径升级到 `--full-index` 口径。

背景（本轮实测发现，两个独立问题）：
  ① 首版导出用 `git diff`（受 `core.abbrev` 影响）⇒ `index` 行只写 **7 位** 缩写 blob 哈希，
     快照契约无法逐字符核对（7 位只是前缀，不构成"哪个 blob"的证明）；
  ② 我自己的核对脚本 `dbg-blob-vs-patch.py` 首版漏了 `re.M`，`re.search` 全库只命中第一段 ⇒
     "8 段只有 1 段被检查"却报"0/8 相符"，是**假 FAIL**（已修：加 `re.M` ＋ 断言 40 位宽）。
处置：改用 `git diff --full-index` 重导出（仍是纯 LF），并逐段把补丁 `index` 新侧哈希与
     `git hash-object` 对工作区文件的 blob 哈希逐一比对 ⇒ **8/8 逐字符相同**。
"""
import hashlib
import sys

RP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\REPORT.md"

OLD_DESC = ("| `ct-batch.patch` | 8 文件统一差异（`git diff` 导出，**未加 `--binary`**——8 个文件全为文本，无二进制段），"
            "sha256 / 字节数见 `fingerprints.txt`；**回滚依据**。**行尾 = 纯 LF**（`CRLF 行数 = 0 / 纯 LF 行数 = 272`，"
            "口径 `[IO.File]::ReadAllText` ＋ `[regex]::Matches($t,\"``r``n\")` / `(?<!``r)``n`，**不用** `ReadAllLines`/`Get-Content`），"
            "父侧 CRLF⇒LF 检出**可直接 `git apply`**（详证见 ⑥-11） |")
NEW_DESC = ("| `ct-batch.patch` | 8 文件统一差异（`git diff --full-index` 导出，**未加 `--binary`**——8 个文件全为文本，无二进制段），"
            "**35,861→36,389 B**，sha256 与逐段 blob 现算值见 `fingerprints.txt`；**回滚依据**。"
            "**行尾 = 纯 LF**（`CRLF 行数 = 0 / 纯 LF 行数 = 272 / CR 总数 = 0`，口径 `[IO.File]::ReadAllText` ＋ "
            "`[regex]::Matches`，**不用** `ReadAllLines`/`Get-Content`——它们会吃掉行尾并给出「带 CR=0」的假结论），"
            "父侧 CRLF⇒LF 检出**可直接 `git apply`**（详证见 ⑥-11） |")

OLD_611 = ("| 11 | （PLAN 未涉及） | **交付物 `ct-batch.patch` 行尾由 CRLF 改为纯 LF** | 首版补丁用 PowerShell 重定向落盘 ⇒ "
           "每行被 CRLF 终止（**CRLF 260 行 / 纯 LF 0 行**）。父侧检出为 LF（`core.autocrlf=true`、**无** `.gitattributes`）⇒ "
           "父侧 `git apply --check` **8/8 全部 `patch does not apply`**（16 条 error，首个在 `scripts/check-bare-anchors.ps1:109`）。"
           "父侧仅做行尾规范化（内容一字不改）后 30,902 B ⇒ `git apply --check` **8/8 全过、0 error、exit 0**，据此定位"
           "**缺陷只在补丁文件行尾**。本轮重导出为纯 LF：**35,861 B / CRLF 0 行 / 纯 LF 272 行 / CR 总数 0 / "
           "sha256 `D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495`** ⇒ **补丁文件行尾 = 纯 LF，父侧可直接 `git apply`**。"
           "注：本 worktree 8 个文件自身全为 CRLF（`CR == LF`，`core.autocrlf=true` 签出的正常现象），与补丁行尾是两件事；"
           "仓库 blob 仍为 LF |")
NEW_611 = """| 11 | （PLAN 未涉及） | **交付物 `ct-batch.patch` 行尾由 CRLF 改为纯 LF**；并改用 `--full-index` 使 blob 哈希可逐段核对 | **（a）行尾**：首版补丁用 PowerShell 重定向落盘 ⇒ 每行被 CRLF 终止（**CRLF 260 行 / 纯 LF 0 行**）。父侧检出为 LF（`core.autocrlf=true`、**无** `.gitattributes`）⇒ 父侧 `git apply --check` **8/8 全部 `patch does not apply`**（16 条 error，首个在 `scripts/check-bare-anchors.ps1:109`）。父侧仅做行尾规范化（内容一字不改）后 30,902 B ⇒ `git apply --check` **8/8 全过、0 error、exit 0**，据此定位**缺陷只在补丁文件行尾**。本轮改用 `Out-String` ＋ `-replace "`r`n","`n"` ＋ `[IO.File]::WriteAllText(…, UTF8Encoding($false))` 导出 ⇒ **CRLF 0 行 / 纯 LF 272 行 / CR 总数 0**；并在导出后**回读复核**「把整个文件按 CRLF→LF 规范化，逐字节不变」⇒ 已是纯 LF，父侧可直接 `git apply`。<br>**（b）blob 哈希位宽**：首版用 `git diff`（受 `core.abbrev` 影响）⇒ `index` 行只写 **7 位**缩写哈希（实测 `index bd6a4fa..bd6a4fa` 形态），**不足以构成"补丁 ≡ 哪个快照"的证明**。改用 `git diff --full-index` ⇒ `index` 行为 **40 位**完整 blob 哈希；随后逐段把「补丁 `index` 新侧哈希」与「`git hash-object` 对当前工作区文件算出的 blob 哈希」比对 ⇒ **8/8 逐字符相同**（`dbg-blob-vs-patch.py` / `.log`，含每段的两个哈希现值）。<br>**终态**：**36,389 B**，sha256 `DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945`，8 段，`CRLF 0 / 纯 LF 272 / CR 0`。<br>**注**：本 worktree 8 个文件自身全为 CRLF（`CR == LF`，`core.autocrlf=true` 签出的正常现象），与补丁行尾是两件事；git 记录的是 **LF 形态 blob**（`README.md` 的 LF 规范化 60,632 B 才是 blob 口径，raw 60,960 B 是检出 CRLF 口径） |"""

OLD_712 = "12. **锚点门禁第 4 次拦下（本轮新引入，已修）**"
NEW_712 = """12. **取证脚本自身的假 FAIL（本轮第 4 个工具坑，必须留痕）**：`dbg-blob-vs-patch.py` 首版用
    `re.search(r"^index ([0-9a-f]+)\\.\\.([0-9a-f]+)", s)` —— **漏了 `re.M`**，于是 `^` 只匹配整份补丁的第 1 个 `index` 行，
    其余 7 段全部取不到哈希 ⇒ 输出「**0/8 段相符**」这样一个**看起来很严重的 FAIL**。**实际它只检查了 1 段**，
    而真正的问题在别处（补丁写的是 7 位缩写哈希，与 64 位 sha256 本就不同域，拿前缀去比 sha256 是**口径错配**）。
    **处置**：① 正则加 `re.M`；② 改比 **40 位 `git hash-object` blob 哈希**（同域）；③ 加断言「`index` 行恰 8 条 ∧ 位宽 == 40」。
    修后 **8/8 逐字符相同**。**教训**：这类脚本的 FAIL 与 PASS **都要先自证"检查了几条"**，否则会把"没查到"当成"查出错"。
13. **锚点门禁第 4 次拦下（本轮新引入，已修）**"""
OLD_713 = "13. **遗留移交总控**"
NEW_713 = "14. **遗留移交总控**"

EDITS = [
    ("⑤ 补丁条目（--full-index）", OLD_DESC, NEW_DESC),
    ("⑥-11（行尾 ＋ blob 位宽）", OLD_611, NEW_611),
    ("⑦-12 新坑：漏 re.M 的假 FAIL", OLD_712, NEW_712),
    ("⑦-13 编号顺延", OLD_713, NEW_713),
]


def main():
    raw = open(RP, "rb").read()
    t = raw.decode("utf-8")
    print(f"REPORT.md = {len(raw):,} B  CR={raw.count(13)} LF={raw.count(10)}  行数={len(t.splitlines())}")
    if raw.count(13) != 0:
        print("[FAIL] 哨兵：应为纯 LF")
        return 2
    print("[OK ] 哨兵：纯 LF")
    applied, skipped, failed = [], [], []
    for tag, old, new in EDITS:
        if new in t:
            skipped.append(tag)
            continue
        c = t.count(old)
        if c != 1:
            failed.append(f"{tag}：旧串命中 = {c}（应然 1）")
            continue
        t = t.replace(old, new, 1)
        applied.append(tag)
    for x in applied:
        print(f"  [改] {x}")
    for x in skipped:
        print(f"  [跳] {x}（幂等）")
    for x in failed:
        print(f"  [FAIL] {x}")
    if failed:
        return 2
    for probe in ("DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945",
                  "36,389 B", "--full-index", "14. **遗留移交总控**", "13. **锚点门禁第 4 次拦下"):
        c = t.count(probe)
        print(f"  [{'OK ' if c >= 1 else 'FAIL'}] 收尾断言「{probe[:44]}…」命中 = {c}")
        if c < 1:
            return 2
    if not applied:
        print(">>> 无需改动")
        return 0
    nb = t.encode("utf-8")
    open(RP, "wb").write(nb)
    print(f">>> 已写盘  {len(nb):,} B  行数={len(t.splitlines())}  sha256={hashlib.sha256(nb).hexdigest().upper()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
