#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-doc-edits.py —— CT 批次文档面（event-contract.md ＋ contract-specs/README.md）。

纪律：每题先断言「旧串命中 == 1 ∧ 新串命中 == 0」，再断言「新串 == 1 ∧ 旧串 == 0」；
      任一不符 ⇒ 整体不写盘。历史陈述一律**追加**、不改写（§11/§12/§13 与勘误表原文保留）。
"""
import argparse
import difflib
import hashlib
import sys

EOL = "\r\n"
WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"

EC = WT + r"\docs\contracts\event-contract.md"
RM = WT + r"\contract-specs\README.md"
VF = WT + r"\contract-specs\VERSION"

# ============ event-contract.md（CT-1 ①②、CT-2 ①）============
EC_J = [
    ('  "event_id": "UUID",                        // 全局唯一，ODS/DWD 按此去重（允许 at-least-once 投递）',
     '  "event_id": "UUID",                        // 源命名空间内唯一，ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）'),
    ('  "source_system": "mock-mall",              // 固定值：mock-mall',
     '  "source_system": "mock-mall",              // 形状约束：非空字符串，取值 = 该源的 source_registry.source_code（值域非契约所有，D-061）；mock-mall 为首个源取值'),
    ('* `event_id` 唯一；重复投递由 DWD 按 `event_id` 去重，因此允许 at-least-once。',
     '* `event_id` 在源命名空间内唯一；重复投递由 DWD 按 `(source_instance_id, event_id)` 去重，因此允许 at-least-once。'),
]

# ============ contract-specs/README.md ============
RM_J = [
    # CT-1 ④-a ＋ §2.4 版本串
    ('版本：`2.0.0`（见',
     '版本：`2.2.0`（见'),
    # CT-1 ④-b（§3 目录表 VERSION 行）
    ('| [`VERSION`](VERSION) | 总控 | — | `2.1.0` |',
     '| [`VERSION`](VERSION) | 总控 | — | `2.2.0` |'),
    # CT-1 ③（§5 建模决定）
    ('- **`source_system` 取 `const: "mock-mall"`**：§1 L16「固定值：mock-mall」，且 `EventContract.SOURCE_SYSTEM` 两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B 的 `synthetic=true` 与它的关系未定（Q3）。',
     '- **`source_system` 只受形状约束（`type: string` ＋ `minLength: 1`），不取 `const`／`enum`／`pattern`**（`D-061`，2026-09-12 CT 批次）：取值 = 该事件所属源的 `source_registry.source_code`，**值域非契约所有**——命名规则的单一所有者是 `source_registry` 侧校验（`D-035`）。原句「§1 L16『固定值：mock-mall』，且 `EventContract.SOURCE_SYSTEM` 两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`」是 **CT 批次前的历史陈述，原文保留**；其中平台侧常量 `EventContract.SOURCE_SYSTEM` 已按 `D-064 ①` 退休（行已删），对账测试已改为「形状守卫 ＋ `schema_version` 仍锁 `const`」的正向对照。§3.3 B 的 `synthetic=true` 与它的关系未定（Q3）。'),
    # §1 版本沿革（追加一段，原四段原文保留）
    ('均按 §3 的目录级版本规则） ｜ 建立任务：M1-5',
     '`2.1.0 → 2.2.0` 对应 CT 批次（`canonical-event.v1` 的加法变更：`order_created.items` 加性接受字符串形态；`source_system` **去掉** `const` 属放宽兼容性 ⇒ 仍按加法处理。见 §14），均按 §3 的目录级版本规则） ｜ 建立任务：M1-5'),
]

# §2.6 新增锚点规则（D-065）
RM_RULE_ANCHOR = '### 3.5 锚点书写规则（`D-065`，2026-09-12 CT 批次新增）'
RM_RULE = """### 3.5 锚点书写规则（`D-065`，2026-09-12 CT 批次新增）

- **规则**：本目录与 `docs/contracts/**` 内引用指导书章节时，锚点**必须带文件名或版本前缀**（如 `docs/contracts/event-contract.md §2.4 L77`、`V2.3 §4.1 L139`），**不得**只写 `§x.y Lzzz`——裸行号在指导书迭代后会指向错误段落。
- **门禁**：`scripts/check-bare-anchors.ps1`（判据 = `contract-specs/**` 内检出的裸锚点集合 **⊆** `scripts/contract-bare-anchors.allowlist.txt`），脚本内含**正向对照**（一条带文件名的锚点必须**不**计入）。
- **在册负债**：台账 `scripts/contract-bare-anchors.allowlist.txt` 共 **113 行 / 201 处**裸锚点，**只减不增**；`README.md` §11 勘误表（`F-29`）的审计口径是 **258 处**（扫码范围更宽，且含刻意保留的旧锚点原文）。**两数不同，不得互相印证**；本批只登记，不清理。
- **本批实际处置（单一实例）**：`contract-specs/schemas/canonical-event.v1.schema.json` 的 `items` 描述里 `§2.4 L77` / `§2.4 L84-L90` 补全为 `docs/contracts/event-contract.md §2.4 L77` / `… L84-L90`（**行号未改**）；其余裸锚点本批**不动**（专项 M1-5-R，时点 = P3-04 同批或之前）。
- **本细则不声称**：锚点「指对了地方」（只补文件名，未核语义）；非 markdown 载体的同款裸锚点已普查。
"""

RM_SEC14 = """## 14. CT 批次（`D-061`…`D-065`）落地：当前态版本串 `2.1.0` → `2.2.0`（2026-09-12）

**为什么有本节**：§11 是 M1-5 收口同步（`1.2.0 → 1.3.0`），§12 是 CT-0 勘误，§13 是 P2-07（`→ 2.0.0`）。本轮 CT 批次把三条裁决落到契约上：`source_system` 去 `const`（`D-061`）、`event_id` 去重语义改源命名空间内（`D-062`）、`order_created.items` 加性接受字符串形态（`D-063`），并退休平台侧常量（`D-064`）、登记锚点规则（`D-065`）。三者**均为加法**（`source_system` 去 `const` 是放宽兼容性，不是破坏性变更）⇒ 目录级版本按 §3 规则 minor 递增到 **`2.2.0`**。

### 14.1 逐项改动

| # | 位置 | 改动 |
|---|---|---|
| 1 | `VERSION` | `contract-specs 2.1.0` → `contract-specs 2.2.0`（`SHA256_VERSION_NEW`） |
| 2 | `schemas/canonical-event.v1.schema.json` | ① `properties.event_id.description` 首句：去重键改 `(source_instance_id, event_id)`；② `properties.source_system`：`const: "mock-mall"` → `type: string` ＋ `minLength: 1`，`description` 改为形状约束声明（**刻意不加 `pattern`/`enum`**，`D-061`）；③ `$defs.order_created.properties.items`：`type: array` → `oneOf: [{array（原定义原文保留）}, {string}]`；④ `$defs.order_created.description` **追加**处置句 |
| 3 | `docs/contracts/event-contract.md` | §1 L12 注释与 L16 注释改写；§4 L165 去重句改写（`event_id` → `(source_instance_id, event_id)`） |
| 4 | 平台侧代码 | `platform-common/.../contracts/EventContract.java`：删除 `SOURCE_SYSTEM` 常量行（`D-064 ①`）；`CanonicalEventSchemaParityTest`：旧「两侧常量一致」断言改为「**结构守卫**：`source_system` 不得含 `const` ＋ 形状断言」，并以 `schema_version` 仍锁 `const` 作**正向对照**；`SourceRegistryMigrationScriptTest` 仅改 `.as(...)` 理由串，**断言本体 `.contains("'mock-mall'")` 一字未改** |
| 5 | 本节 ＋ §3.5 | 新增锚点规则与在册负债登记（`D-065`） |

### 14.2 逐制品指纹（本节数值均为**本轮现算**，非转录）

| 制品 | sha256 |
|---|---|
| `contract-specs/VERSION`（**当前值**，内容 `contract-specs 2.2.0`） | `SHA256_VERSION_NEW` |
| `schemas/canonical-event.v1.schema.json`（**当前值**） | `SHA256_SCHEMA_NEW` |
| `docs/contracts/event-contract.md`（**当前值**） | `SHA256_EC_NEW` |
| `contract-specs` 目录内其余制品（`ingestion-manifest.v1`、`generation-artifact-manifest.v1`、`openapi/generator-api.v1.yaml`、`specs/surrogate-key.v1.json`、`specs/warehouse-namespace.v1.json`、`specs/warehouse-namespace.v2.json`） | **本批一字未改**（`warehouse-namespace.v1.json` 现算仍为 `463D9DC3503D563D8DD8EB844C1AB5EDE9AAEE073251F07957D410C13911AE5A`，与 §10 登记值相符） |

`VERSION` 变更前为 `contract-specs 2.1.0`（**22 B**，sha256 `605679A0B8BE10CDA8D1F60045C524145AAD1AA388766AF01ECD8ADEBA559798`）。注意：§10 表内 `2.0.0`/`2.1.0` 两行登记的是「21 B」，与本轮实测的 **22 B** 不符（该文件含 `CRLF`）⇒ **历史行原文保留不改**，此处按实测登记，登记口径差异属既有事实。

### 14.3 刻意保留（不改）

- §10 全部历史指纹行、§11 的 `1.2.0 → 1.3.0` 同步表、§12 的 CT-0 勘误全节、§13 的 P2-07 全节与 `1.3.0 → 2.0.0` 沿革；
- §5 建模决定里那句「`source_system` 取 `const`」的原句、schema 与 `event-contract.md` 中所有「固定值：mock-mall」「按 `event_id` 去重」的**历史冲突陈述**（均以追加方式标注处置，不删原文）；
- 四个 `DRAFT` 制品的状态与内容；`specs/**`、`openapi/**`、`schemas/ingestion-manifest.v1.schema.json`、`schemas/generation-artifact-manifest.v1.schema.json`。

### 14.4 未取证／不得声称

- 本批次**未**执行 T2 重跑（55 条黄金链端到端，= M1-11，排在本批之后）；`canonical-event.v1` 仍为 **`DRAFT`**——§4 的两条冻结门槛中，门槛 (1) 结构对账测试本轮已跑绿，门槛 (2) Q6 处置仍未决。
- `spark-jobs/**`、`tests/golden-dataset/**`、`mall-simulator/**` 与生成器 `ContractFormat.SOURCE_SYSTEM` **本批一字未改**；`D-064 ②③`（商城/生成器侧同名常量）与 `B-06` 仍未决。
- 字符串形态 `items` 的**解析归一**尚未实现（属 DWD，P2-04/P2-05）；本轮只把「契约接受」落成文字与 schema 分支。
"""


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    log = []
    ok = True

    def chk(name, cond, detail=""):
        nonlocal ok
        ok = ok and bool(cond)
        log.append(f"[{'OK ' if cond else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")

    files = {}

    # ---------- event-contract.md ----------
    raw = open(EC, "rb").read()
    t = raw.decode("utf-8")
    log.append(f"event-contract.md: sha256(原)={sha(raw)} bytes={len(raw)} CR={raw.count(13)} LF={raw.count(10)}")
    chk("EC 正向对照（写前基线）：`固定值：mock-mall` 原文命中 == 1（本次将要改写它）",
        t.count("固定值：mock-mall") == 1, f"命中 {t.count('固定值：mock-mall')}")
    chk("EC 正向对照（写前基线）：`按此去重` 原文命中 == 1",
        t.count("按此去重") == 1, f"命中 {t.count('按此去重')}")
    for i, (o, n) in enumerate(EC_J, 1):
        c1 = t.count(o)
        c2 = t.count(n)
        chk(f"EC 第 {i} 题：旧串命中 == 1", c1 == 1, f"命中 {c1}")
        chk(f"EC 第 {i} 题：新串命中 == 0（写前）", c2 == 0, f"命中 {c2}")
        t = t.replace(o, n, 1)
    chk("EC 正向对照（写后）：`固定值：mock-mall` 旧句已归零（它本身就是本次被改写的目标）",
        t.count("固定值：mock-mall") == 0, f"命中 {t.count('固定值：mock-mall')}")
    chk("EC 正向对照（写后）：`按此去重` 旧句已归零", t.count("按此去重") == 0,
        f"命中 {t.count('按此去重')}")
    chk("EC 正向对照（写后）：新句 `(source_instance_id, event_id)` 命中 == 2（§1 注释 ＋ §4 契约句，两处都改到）",
        t.count("(source_instance_id, event_id)") == 2, f"命中 {t.count('(source_instance_id, event_id)')}")
    files[EC] = t

    # ---------- README.md ----------
    raw = open(RM, "rb").read()
    r = raw.decode("utf-8")
    log.append("")
    log.append(f"README.md: sha256(原)={sha(raw)} bytes={len(raw)} CR={raw.count(13)} LF={raw.count(10)}")
    for i, (o, n) in enumerate(RM_J, 1):
        c1 = r.count(o)
        c2 = r.count(n)
        chk(f"RM 第 {i} 题：旧串命中 == 1", c1 == 1, f"命中 {c1}")
        chk(f"RM 第 {i} 题：新串命中 == 0（写前）", c2 == 0, f"命中 {c2}")
        r = r.replace(o, n, 1)

    # §2.6 规则：插在 §4 标题之前
    rm_anchor = "## 4. 制品清单"
    chk("RM §4 标题唯一", r.count(rm_anchor) == 1, f"命中 {r.count(rm_anchor)}")
    chk("RM §3.5 段落（写前必须 0）", r.count(RM_RULE_ANCHOR) == 0)
    r = r.replace(rm_anchor, RM_RULE.replace("\n", EOL) + EOL + rm_anchor, 1)

    # §14：追加到文末
    chk("RM §14 标记（写前必须 0）", r.count("## 14. CT 批次") == 0)
    chk("RM 文件末尾为 CRLF（追加前）", r.endswith(EOL))
    r = r + RM_SEC14.replace("\n", EOL)
    chk("RM §14 已追加", r.count("## 14. CT 批次（`D-061`…`D-065`）落地") == 1)
    files[RM] = r

    # ---------- VERSION ----------
    raw = open(VF, "rb").read()
    v = raw.decode("utf-8")
    log.append("")
    log.append(f"VERSION: sha256(原)={sha(raw)} bytes={len(raw)} 内容={v.strip()!r}")
    chk("VERSION 旧内容命中 == 1", v.count("contract-specs 2.1.0") == 1)
    chk("VERSION 新内容命中 == 0（写前）", v.count("contract-specs 2.2.0") == 0)
    v2 = v.replace("contract-specs 2.1.0", "contract-specs 2.2.0", 1)
    chk("VERSION 字节数不变（21→21 字符 ＋ 1 个 CR）", len(v2.encode("utf-8")) == len(raw),
        f"{len(raw)} → {len(v2.encode('utf-8'))}")
    files[VF] = v2

    if not ok:
        log.append("")
        log.append("SELFCHECK-FAIL：断言未通过 ⇒ **不写盘**")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        print(EOL.join(log))
        return 2

    log.append("")
    log.append("=== 行级差异（unified, n=0）===")
    for path, new in files.items():
        old_txt = open(path, "rb").read().decode("utf-8")
        d = list(difflib.unified_diff(old_txt.split(EOL), new.split(EOL), lineterm="", n=0))
        log.append(f"--- {path} ：diff 行数 = {len(d)}，行数 {len(old_txt.split(EOL))} → {len(new.split(EOL))}")
        for x in d[:60]:
            log.append("    " + x[:400])
        if len(d) > 60:
            log.append(f"    …（其余 {len(d) - 60} 行略）")

    log.append("")
    if args.apply:
        for path, new in files.items():
            open(path, "wb").write(new.encode("utf-8"))
        log.append(">>> 已写盘（--apply）")
    else:
        log.append(">>> 干跑（未加 --apply）；未写盘")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(EOL.join(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
