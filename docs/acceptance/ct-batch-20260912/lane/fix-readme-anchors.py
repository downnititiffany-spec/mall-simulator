#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-anchors.py —— 修复 apply-readme-final.py 里我**自己新引入**的 4 处裸锚点。

背景（必须留痕）：apply-doc-edits.py 之后的守卫读数是 199 处 / 112 行（PASS）；我在 §3.5
补写「实测读数」、在 §14.1 第 5 行补写处置明细时，把三处锚点原样罗列了出来，其中 4 处不带
文件名 ⇒ 守卫立刻报「新增裸锚点 1 处 + 出现次数升高 1 处」= FAIL。
**这正是 D-065 守卫该有的行为（新增即拦截）；我第一版修复仍留了 2 处裸写法，同样被拦。**
本脚本按守卫给的修法一次改净：不再罗列裸锚点；§14.1 表格里改成**带文件名前缀**的三种写法。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"

REPL = [
    # ① §3.5 —— 不再罗列裸锚点写法；「台账仍余 2 条」用无量词表述，避免再次引入裸写作
    ('- **本批实际处置（单一实例）**：`contract-specs/schemas/canonical-event.v1.schema.json` 描述里的 `§2.4 L77` / `§2.4 L84` / `§2.4 L84-L90` 补全为 `docs/contracts/event-contract.md §2.4 L77` / `… L84` / `… L84-L90`（**行号一字未改**）。**守卫实测（改后）**：裸锚点 **199 处 / 112 行**（台账 `113 行`；台账仍余 2 条待收敛——`§2.4 L77` 计 1 行可删、`§2.4 L84` 实测 1 < 台账 2，`-WriteLedger` 收敛须先在看板登记，本批未做）。',
     '- **本批实际处置（单一实例）**：`contract-specs/schemas/canonical-event.v1.schema.json` 里**指向指导书 §2.4 的 3 处裸锚点**（涉及 `items` 容器与 `items` 字段的两行描述）补全为带文件名前缀的写法（形如 `docs/contracts/event-contract.md §2.4 L77`，**行号一字未改**；逐处明细见 §14.1 第 5 行）。**守卫实测（改后）**：裸锚点 **199 处 / 112 行**（台账 `113 行`）——台账仍余 2 条待收敛（1 条整行可删、1 条出现次数实测 1 < 台账 2）；收敛须在看板登记后跑 `-WriteLedger`，本批**未做**。'),
    # ② §14.1 第 5 行 —— 三处一律写成带文件名前缀的形态
    ('| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`）；补全 `canonical-event.v1.schema.json` 描述里的 3 处裸锚点（`§2.4 L77` / `§2.4 L84` / `§2.4 L84-L90`，**行号未改**，加文件名前缀）——这三处是「补前缀」而非「新增锚点行」，故台账行数不增 |',
     '| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`）；`canonical-event.v1.schema.json` 里 3 处指向 `docs/contracts/event-contract.md §2.4 L77` / `… §2.4 L84` / `… §2.4 L84-L90` 的裸锚点补全文件名前缀（**行号未改**）——属「补前缀」而非「新增锚点行」，故台账**行数不增**，但改后守卫实测裸锚点为 `199 处 / 112 行`（patching 前 `201 处 / 113 行`） |'),
]


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    print(f"README 写前 raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B")
    ok = True
    for i, (o, n) in enumerate(REPL, 1):
        c1, c2 = t.count(o), t.count(n)
        good = (c1 == 1 and c2 == 0)
        ok = ok and good
        print(f"[{'OK ' if good else 'FAIL'}] #{i} 旧串命中={c1}（应然 1） 新串命中={c2}（应然 0）")
        if not good:
            print(f"       旧: {o[:120]}")
        t = t.replace(o, n, 1)
    # 全量复核：本脚本写完后，README 里不得再有本次引入的裸写法
    for probe in ('`§2.4 L84`', '`§2.4 L77`', '`§2.4 L84-L90`'):
        n = t.count(probe)
        good = n == 0
        ok = ok and good
        print(f"[{'OK ' if good else 'FAIL'}] 修复后裸写法 {probe} 命中 = {n}（应然 0）")
    if not ok:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    nb = t.encode("utf-8")
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
