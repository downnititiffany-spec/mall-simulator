#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-report-review2.py —— REPORT.md 收尾两条：⑥-11（补丁行尾）与 ⑦-10/11/12（本轮过程留痕）。"""
import hashlib
import sys

RP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\REPORT.md"

SIX_ANCHOR = "\n---\n\n## ⑦ 过程失败与处置（含未取证项与后续移交）"
SIX_NEW = """
| 11 | （PLAN 未涉及） | **交付物 `ct-batch.patch` 行尾由 CRLF 改为纯 LF** | 首版补丁用 PowerShell 重定向落盘 ⇒ 每行被 CRLF 终止（**CRLF 260 行 / 纯 LF 0 行**）。父侧检出为 LF（`core.autocrlf=true`、**无** `.gitattributes`）⇒ 父侧 `git apply --check` **8/8 全部 `patch does not apply`**（16 条 error，首个在 `scripts/check-bare-anchors.ps1:109`）。父侧仅做行尾规范化（内容一字不改）后 30,902 B ⇒ `git apply --check` **8/8 全过、0 error、exit 0**，据此定位**缺陷只在补丁文件行尾**。本轮重导出为纯 LF：**35,861 B / CRLF 0 行 / 纯 LF 272 行 / CR 总数 0 / sha256 `D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495`** ⇒ **补丁文件行尾 = 纯 LF，父侧可直接 `git apply`**。注：本 worktree 8 个文件自身全为 CRLF（`CR == LF`，`core.autocrlf=true` 签出的正常现象），与补丁行尾是两件事；仓库 blob 仍为 LF |

""" + SIX_ANCHOR.lstrip("\n")

SEVEN_OLD = "9. **遗留移交总控**"
SEVEN_NEW = """9. **本轮（父侧审查回执）新踩的工具坑 · 第 1 个：Python universal-newlines 静默改写字节**。`open(p, encoding="utf-8")` 默认 `newline=None`，会把 `\\r\\n` **静默翻译**成 `\\n`：README 读数因此短 **321** 字符（恰等于 CR 数），`t.split("\\r\\n")` 只切出 **1** 段、`t.count("\\r")` 为 **0**。**处置**：改用 `open(p,"rb").read().decode("utf-8")`，切行一律 `splitlines()`，并加哨兵「CR == LF ∧ 行数 ≥ 300」。**已逐个审计**早前 7 个 README 改写脚本——全部用的是 `open(RM,"rb")`，**未受影响**；受影响的只有 `fix-readme-review3.py` v1 与 `refresh-fingerprints.py` v2，两者均在本轮重写。**这条坑的危险性在于它不报错**：读数会"看起来很合理"，只是悄悄少掉 CR。
10. **本轮新踩的工具坑 · 第 2 个：`EOL.join(lines) + EOL` 多出一个行终止符**。实测源 7,271 B → `split("\\r\\n")` 得 80 元素（末元素 `''`）→ `join + EOL` = 7,273 B，把台账尾巴撑成 `\\r\\n\\r\\n`（末元素 `''` 已代表最后一个 CRLF，再 `+EOL` 就是第二个）。**处置**：正确往返是 `EOL.join(lines)`（**不加** EOL），并把「字节数 == join 长度 ∧ CR == LF == 行数−1」写成**写盘前**断言。台账已按此**整份重建**（`refresh-fingerprints2.py`，7,973 B / CR=LF=78 / 行数 79，且二次运行报「幂等：逐字节相同」）。
11. **本轮新踩的工具坑 · 第 3 个：命名组里写字面空格恒不匹配 ＋ 自哈希悖论**。① `re.compile(r"^(?P<ind>\\s*LF  \\s+)…")`（命名组内写两个字面空格）在 CPython 3.14.5 上**恒不匹配**，换 `\\s+` 即命中——复现脚本 `dbg-rx6.py`；② 台账里登记"文件自身 sha256"是**不收敛的悖论**（实测 12 轮：长度稳定 7,260 B 而 sha 每轮都变，复现脚本 `dbg-fp.py`），旧台账登记的 `2F8BDB12… 5,626 B` 与写盘后实际文件（7,257 B）**不符、无法复核**。**处置**：改用**置零摘要**口径——把自身行 64 位哈希挖空成 64 个 `0` 后对整份内容算 sha256（现态 `C737A317B5094F1CB80C201A7C5E0D933DBD1BF0A74B1D198524B1558D481556`），复核步骤写进台账首部，读者照做即可复现。
12. **锚点门禁第 4 次拦下（本轮新引入，已修）**：缺陷 1 的重写稿里含无前缀的 `§1 L16「固定值：mock-mall」` ⇒ `[门禁失败] 台账内锚点出现次数升高 1 处——README.md :: §1 L16 台账=2 实测=3`、`EXITCODE=1`。**处置**：只给**本轮新增的**那处（§14.3 引文行）补文件名前缀；§8 第 3 条 Q3 那处（`README.md:101`，历史基线，台账已登记 2 处）**刻意不动、不改写历史行**。修后门禁复位 `PASS（裸锚点 199 处 / 112 行，全部在台账内；台账 113 行 ≤ 基线 113）`、`EXITCODE=0`。**四次拦下全部是真实缺陷，非门禁误报。**
13. **遗留移交总控**"""

EDITS = [
    ("⑥-11 补丁行尾", SIX_ANCHOR, SIX_NEW),
    ("⑦-9 起补本轮三条留痕", SEVEN_OLD, SEVEN_NEW),
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
    # 收尾断言：⑥ 表尾编号 11 存在、⑦ 编号 13 存在、旧编号 9 不再作为「遗留移交」标题
    for probe in ("| 11 | （PLAN 未涉及） |", "13. **遗留移交总控**", "D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495", "CRLF 0 行 / 纯 LF 272 行"):
        c = t.count(probe)
        print(f"  [{'OK ' if c >= 1 else 'FAIL'}] 收尾断言「{probe[:40]}…」命中 = {c}")
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
