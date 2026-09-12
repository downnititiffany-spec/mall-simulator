#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-readme-final.py —— README 第 3.5 行 & §14.1 表格行的占位/陈旧数字收敛（逐串断言，写前 vs 写后）。"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

REPL = [
    # ① §3.5 「本批实际处置」：把"待清"改为**实测结果**（守卫已跑，读数 199/112）
    ('- **本批实际处置（单一实例）**：`contract-specs/schemas/canonical-event.v1.schema.json` 的 `items` 描述里 `§2.4 L77` / `§2.4 L84-L90` 补全为 `docs/contracts/event-contract.md §2.4 L77` / `… L84-L90`（**行号未改**）；其余裸锚点本批**不动**（专项 M1-5-R，时点 = P3-04 同批或之前）。',
     '- **本批实际处置（单一实例）**：`contract-specs/schemas/canonical-event.v1.schema.json` 描述里的 `§2.4 L77` / `§2.4 L84` / `§2.4 L84-L90` 补全为 `docs/contracts/event-contract.md §2.4 L77` / `… L84` / `… L84-L90`（**行号一字未改**）。**守卫实测（改后）**：裸锚点 **199 处 / 112 行**（台账 `113 行`；台账仍余 2 条待收敛——`§2.4 L77` 计 1 行可删、`§2.4 L84` 实测 1 < 台账 2，`-WriteLedger` 收敛须先在看板登记，本批未做）。其余裸锚点本批**不动**（专项 M1-5-R，时点 = P3-04 同批或之前）。'),
    # ② §14.1 第 5 行：补上 3.5（原文只写"本节 ＋ §3.5"，与 ①②③④ 的粒度不一致）
    ('| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`） |',
     '| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`）；补全 `canonical-event.v1.schema.json` 描述里的 3 处裸锚点（`§2.4 L77` / `§2.4 L84` / `§2.4 L84-L90`，**行号未改**，加文件名前缀）——这三处是「补前缀」而非「新增锚点行」，故台账行数不增 |'),
    # ③ §14.2 指纹占位替换（本轮现算值）
    ('| `contract-specs/VERSION`（**当前值**，内容 `contract-specs 2.2.0`） | `SHA256_VERSION_NEW` |',
     '| `contract-specs/VERSION`（**当前值**，内容 `contract-specs 2.2.0`） | raw `5D349AFBF593CF7FFDF5E0BDEEA4CDE5CCB96D8E0397449CA9DF501F7A4AF320`（22 B）／LF-normalized `EB1755838292C33126D7B433CA859118EB4B1FDEEC1D07BB76B06702A2028940`（21 B，**与 §10 历史行同口径**） |'),
    ('| `schemas/canonical-event.v1.schema.json`（**当前值**） | `SHA256_SCHEMA_NEW` |',
     '| `schemas/canonical-event.v1.schema.json`（**当前值**） | raw `8A8F8A432678CBEF180D34E16E9922147A9F46A5483977760DA16C2756A9A384`（34,563 B）／LF-normalized `A70AF90110FBB5D36CA93F5C89C9E2E9D4C93A5F8C5973F7721D6464B2D7ADE5`（33,687 B） |'),
    ('| `docs/contracts/event-contract.md`（**当前值**） | `SHA256_EC_NEW` |',
     '| `docs/contracts/event-contract.md`（**当前值**） | raw `0BD7919DD5155B94C0EF91B558C721605991CB6AFA82D7214ED642DAB78458B6`（7,607 B）／LF-normalized `7F4A6E45FF5E68698AC96C1AF44F046F0045B5A19E0D75E4B3CFBE3B425B384E`（7,439 B） |'),
    # ④ §14.2 §10 对账段落：把 EOL 口径差异讲清（这是本批唯一"看似超范围"的读数）
    ('`VERSION` 变更前为 `contract-specs 2.1.0`（**22 B**，sha256 `605679A0B8BE10CDA8D1F60045C524145AAD1AA388766AF01ECD8ADEBA559798`）。注意：§10 表内 `2.0.0`/`2.1.0` 两行登记的是「21 B」，与本轮实测的 **22 B** 不符（该文件含 `CRLF`）⇒ **历史行原文保留不改**，此处按实测登记，登记口径差异属既有事实。',
     """**口径说明（本节两列并存的原因）**：本仓工作区全部制品为 `CRLF`，而 §10 历史登记值是**去 `CR` 后的 `LF` 规范化字节**——已用两条独立对照证实：
`VERSION` 的 `LF` 形态现算 `9784177F34E4B4A248F7D3840D73E294C35EE46ADCB76F429CAA87D4D8E0E30D`（21 B），与 §10 登记值**逐字符相同**；`specs/warehouse-namespace.v1.json` 的 `LF` 形态现算 `463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A`，与 §10 登记值**逐字符相同**（该文件本批**一字未改**，`git status` 为空）。故 §10 的「21 B」与本节测得的「22 B」是**两个口径**，不是改动，历史行原文保留不改。

`VERSION` 变更前为 `contract-specs 2.1.0`（raw 22 B，sha256 `605679A0B8BE10CDA8D1F60045C524145AAD1AA388766AF01ECD8ADEBA559798`；LF 21 B，`9784177F34E4B4A248F7D3840D73E294C35EE46ADCB76F429CAA87D4D8E0E30D` ＝ §10 登记值）。"""),
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
        print(f"       旧: {o[:96]}")
        t = t.replace(o, n, 1)
    if not ok:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    nb = t.encode("utf-8")
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
