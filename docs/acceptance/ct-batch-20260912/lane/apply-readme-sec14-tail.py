#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-readme-sec14-tail.py —— 收尾：填 §14.1 占位、补 §14.5（超 PLAN 范围单列的守卫列宽修）。"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

SEC145 = """### 14.5 本批附带的一处**超 PLAN 范围**改动（单列，供总控判收）

`scripts/check-bare-anchors.ps1` L112 的裸锚点实况表用硬编码列宽 `PadRight(58)` 排版，而最长文件名 `generation-artifact-manifest.v1.schema.json` 长 **59** ⇒ 该行输出成 `…schema.json1 处`，**文件名与计数粘连**，守卫读数会被误读成"1 处"归属不明。本批把它改为**按实际最长文件名现算列宽**（`PadRight($nameW + 2)`）：纯排版，**不改任何计数逻辑与门禁判据**，改后守卫仍 `PASS`，实测裸锚点 `199 处 / 112 行`（修前修后同一读数）。

- 为什么必须单列：PLAN §2.5 只要求"补文件名前缀"，没要求改守卫；这一处改动**超出了 PLAN 授权范围**，故在 README 与批报告里都显式登记，不自评"顺带修好"。
- 未做：台账 `scripts/contract-bare-anchors.allowlist.txt` **一行未改**（D-065 裁决"只减不增"，且删减须先在看板登记，而看板 `docs/项目实施进度与任务看板 V2.2.md` 属总控面）。守卫提示的 2 条待收敛（1 行可删 ＋ 1 处次数下降）如实保留为**在册负债**。
"""

REPL = [
    ('| 1 | `VERSION` | `contract-specs 2.1.0` → `contract-specs 2.2.0`（`SHA256_VERSION_NEW`） |',
     '| 1 | `VERSION` | `contract-specs 2.1.0` → `contract-specs 2.2.0`（raw `5D349AFBF593CF7FFDF5E0BDEEA4CDE5CCB96D8E0397449CA9DF501F7A4AF320`） |'),
    # §14.4 末行之后追加 §14.5
    ('- 字符串形态 `items` 的**解析归一**尚未实现（属 DWD，P2-04/P2-05）；本轮只把「契约接受」落成文字与 schema 分支。',
     '- 字符串形态 `items` 的**解析归一**尚未实现（属 DWD，P2-04/P2-05）；本轮只把「契约接受」落成文字与 schema 分支。\n\n' + SEC145.rstrip("\n")),
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
            print(f"       旧: {o[:120]}")
        t = t.replace(o, nn, 1)
    if not ok:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    nb = t.encode("utf-8")
    ok2 = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok2 else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok2:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
