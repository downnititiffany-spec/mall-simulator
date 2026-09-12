#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-counts.py —— 把 README 里「3 处裸锚点」的**计数错误**改成实测的 2 处。

根因（必须留痕）：我先把 PLAN §2.5 #4 的「1 处」写成了「3 处」——把 `§2.4 L84` 的出现次数
（HEAD 里 2 次，但都出现在**同一行**里，且该行同时含 `§2.4 L77`）错当成"3 个独立裸锚点行"。
逐字复核 HEAD blob 后确认：本批实际补前缀的裸锚点出现次数 = **2**（`§2.4 L77` ×1 ＋ `§2.4 L84-L90` ×1，
同在 `items` 那一行描述里），与守卫读数「201 → 199 处」「113 → 112 行」逐项相符。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

REPL = [
    ('里**指向指导书 §2.4 的 3 处裸锚点**（涉及 `items` 容器与 `items` 字段的两行描述）补全为带文件名前缀的写法',
     '里**指向指导书 §2.4 的 2 处裸锚点**（同在 `items` 容器那一行描述里：`§2.4 L77` 与 `§2.4 L84-L90`）补全为带文件名前缀的写法'),
    ('| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`）；`canonical-event.v1.schema.json` 里 3 处指向 `docs/contracts/event-contract.md §2.4 L77` / `… §2.4 L84` / `… §2.4 L84-L90` 的裸锚点补全文件名前缀（**行号未改**）——属「补前缀」而非「新增锚点行」，故台账**行数不增**，但改后守卫实测裸锚点为 `199 处 / 112 行`（patching 前 `201 处 / 113 行`） |',
     '| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`）；`canonical-event.v1.schema.json` 里 `items` 那一行描述中的 **2 处**裸锚点补全文件名前缀（`§2.4 L77` → `docs/contracts/event-contract.md §2.4 L77`；`§2.4 L84-L90` → 同前缀，**行号未改**）——属「补前缀」而非「新增锚点行」，且这 2 处补完后该行不再有裸锚点，故守卫去重行数**反降 1**（`113 → 112`）、出现次数 `201 → 199`；`items[]` 子对象描述里的 `§2.4 L84-L90` **仍为裸写法、本批不动** |'),
]


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    print(f"README 写前 raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={raw.count(13)} LF={raw.count(10)}")
    ok = True
    for i, (o, n) in enumerate(REPL, 1):
        nn = n.replace("\n", EOL)
        c1, c2 = t.count(o), t.count(nn)
        good = (c1 == 1 and c2 == 0)
        ok = ok and good
        print(f"[{'OK ' if good else 'FAIL'}] #{i} 旧串命中={c1}（应然 1） 新串命中={c2}（应然 0）")
        if not good:
            print(f"       旧: {o[:150]}")
        t = t.replace(o, nn, 1)
    if not ok:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    # 正向对照：改后不得残留「3 处裸锚点」表述，且「2 处裸锚点」恰 1 处
    c3 = t.count("3 处裸锚点")
    c4 = t.count("2 处裸锚点")
    print(f"[{'OK ' if c3 == 0 else 'FAIL'}] 残留「3 处裸锚点」= {c3}（应然 0）")
    print(f"[{'OK ' if c4 == 1 else 'FAIL'}] 「2 处裸锚点」= {c4}（应然 1）")
    if c3 != 0 or c4 != 1:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    nb = t.encode("utf-8")
    ok2 = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok2 else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok2:
        return 2
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
