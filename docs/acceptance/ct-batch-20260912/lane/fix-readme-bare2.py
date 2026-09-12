#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-bare2.py（v3）—— 最后一处**我自己写进 README** 的裸锚点：L288 的 `§2.4 L84-L90`。

门禁（守卫同款逐处扫描）把 README 的 43 处裸锚点全部列出后，判定如下：
  L104 `§2.4 L77`  —— 台账内既存条目（`README.md|§2.4 L77|1`），Q5 历史行，**本批不得动**（保留）；
  L288 `§2.4 L84-L90` —— 本批新写（§14.1 第 5 行），就是守卫报的「新增裸锚点」⇒ **必须补前缀**。
改法：写成 `docs/contracts/event-contract.md §2.4 L84-L90`（补文件名，行号一字未改）。
"""
import hashlib
import re
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

OLD = '`items[]` 子对象描述里的 `§2.4 L84-L90` **仍为裸写法、本批不动**'
NEW = '`items[]` 子对象描述里的 `docs/contracts/event-contract.md §2.4 L84-L90` **仍是裸写法（只加了文件名未动行号）、本批不动**'


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    print(f"README 写前 raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={raw.count(13)} LF={raw.count(10)}")
    nn = NEW.replace("\n", EOL)
    c1, c2 = t.count(OLD), t.count(nn)
    ok = (c1 == 1 and c2 == 0)
    print(f"[{'OK ' if ok else 'FAIL'}] 旧串命中={c1}（应然 1） 新串命中={c2}（应然 0）")
    if not ok:
        print(f"       旧: {OLD}")
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    t = t.replace(OLD, nn, 1)
    # 逐行自检：期望残留恰 1 处无前缀形态，且必须在 L104（既存 Q5 行）
    rxA = re.compile(r"§2\.4 L(?:77|84)(?:-L90)?")
    rxF = re.compile(r"[A-Za-z0-9_\-\./\\]+\.(?:md|json|yaml|yml|java|scala|sql|txt|csv)\b")
    bad = []
    for ln_no, ln in enumerate(t.split(EOL), 1):
        for m in rxA.finditer(ln):
            if not rxF.search(ln[:m.start()]):
                bad.append((ln_no, m.group(0)))
    print(f"逐行自检：无前缀形态残留 = {len(bad)} 处（期望恰 1 处，且必须在 L104）→ {bad}")
    expect_ok = (len(bad) == 1 and bad[0] == (104, "§2.4 L77"))
    print(f"[{'OK ' if expect_ok else 'FAIL'}] 残留恰为 L104 既存 `§2.4 L77`")
    if not expect_ok:
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
