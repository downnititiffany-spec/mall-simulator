#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-report-review.py —— REPORT.md 本轮（父侧审查回执）定向更新。

REPORT.md 是**纯 LF** 文件（实测 CR=0 / LF=229）——与本仓其它制品（CRLF）不同，
故本脚本一律按 LF 读写，并断言 CR == 0，防止误改成 CRLF 把行尾口径搞乱。

每条替换都做「旧串命中 == 1」断言；若整段已替换过（幂等重跑）则整条跳过并报 [跳]。
"""
import hashlib
import sys

RP = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\REPORT.md"
NL = "\n"

# ---------- 缺陷 4：E1-a 子集口径 ＋ 主检出 537/1 基线 ----------
A_OLD = "- 原始日志：`e1a-baseline.log`（40,639 B）、`e1a-after.log`（40,849 B）。耗时 8.175 s → 20.476 s 系机器并发负载差异，**不作为性能结论**。"
A_NEW = A_OLD + """

**总控在主检出按 PLAN §3 字面命令（含 `-am`）实测的改动前基线（本轮登记，父侧读数）**：

| 模块 | 改动前 | 说明 |
|---|---|---|
| `platform-common` | 41 / 0 | |
| `connection-ingestion` | 156 / 0 | |
| `warehouse-pipeline` | 111 / 0 | **未在本 worktree 跑**（本批测试面之外） |
| `metric-analysis` | 38 / 0 | 同上 |
| `ai-decision` | 91 / 0 | 同上 |
| `platform-app` | 100 / **1** | 同上；唯一红点为既有失败 |
| 合计（7 模块 / 6 个有测试） | **537 tests / 1 failure / BUILD FAILURE** | |

唯一红点：`IngestionManifestSourceSchemaTest`（断言原文 `Expecting empty but was: ["40.json"]`）——`landing/manifests/40.json` 被回填所致，`landing/**` 属 `.gitignore` 面，与本批改动**无因果关系**（同族现象见 ⑦-2）。

**由此必须同时成立的三句（防误读）**：

1. **PLAN §3 的字面判据在主检出动工前即不成立**：其要求 `Failures: 0` ＋ `BUILD SUCCESS`，而总控实测全量为 **537 / 1 / BUILD FAILURE**。
2. **本批 `197 / 0` 只是该字面命令的真子集读数**（`41 + 156`，差 `warehouse-pipeline` / `metric-analysis` / `ai-decision` / `platform-app` 共 **340** 条），**不得**被解读为「PLAN 门禁已过」。
3. **`warehouse-pipeline` / `metric-analysis` / `ai-decision` / `platform-app` 本批未跑、且在 worktree 内不可跑**（`platform-app` 另含既有 Windows flaky，见 ⑦-3），故本批对这 340 条**无读数、不声称**。

本批对 E1-a 的可声称范围因此收紧为：**在 `platform-common,connection-ingestion` 子集上，改动前后用例数相等（41 / 156）且全绿**；「PLAN §3 E1-a 门禁通过」这一结论**本批无权给出**。"""

# ---------- 新增「父侧审查处置」小节（缺陷 1/2/3/4/5） ----------
B_ANCHOR = "### E1-b　schema 自洽性（`Draft202012Validator.check_schema()`）"
REVIEW = """### 父侧审查回执处置（审查轮，5 条缺陷逐条落账）

父侧集成预检共判出 5 条缺陷，**逐条自核后全部属实、全部已处置**（无一条以「实测反驳」结案）：

| # | 父侧判据 | 自核结论 | 处置 | 落点 |
|---|---|---|---|---|
| 1 | `contract-specs/README.md:43` 与 `:71` 两句在 CT-1 落地后变为**假陈述** | **属实**（RULINGS `:183` / `:191` 明令「必须逐句更新」，否则 README 与 schema 互相矛盾） | 就地把 `:43` 改写为当前态（含「已退休 / 未决」边界）；原句以**批次补记引用块**逐字保留 ＋ 追加日期化处置标注（append-only，不改写历史行） | `README.md:43`、`README.md:45-46` |
| 2 | README 自述「**3 处**裸锚点」与实测不符 | **属实**（实测本批补前缀 = **2 处**：`§2.4 L77` ×1 ＋ `§2.4 L84-L90` ×1，同在 `items` 容器那一行描述里；守卫读数 201→199 处、113→112 行逐项相符） | **选 (b)：按实测改成 2 处**——不改判据、不回改守卫读数；(a)「保留 3 处并改判据」被否，因判据本无错，错的是我先前把台账里 `§2.4 L84` 的**出现次数 2** 误当成「3 个独立裸锚点行」 | `README.md` §3.5「**2 处裸锚点**」 |
| 3 | §14.3 措辞与补丁实际形态不符 | **属实**：补丁对 §5 那条决策句是**同行 `-`/`+` 整句替换**，而原措辞暗示「原句保留 ＋ 追加标注」 | 改写 §14.3 使其与 `git diff` 实际形态一致，并补记：schema / `event-contract.md` 的历史冲突陈述属**就地改写**；同时以引用块逐字保留原句 ＋ 标注处置（与缺陷 1 同款 append-only 纪律） | `README.md` §14.3 |
| 4 | **E1-a 判据口径**：`197 / 0` 是子集读数，主检出全量为 `537 / 1 / BUILD FAILURE` | **属实**（并已在 ② E1-a 全文登记，含三条防误读声明） | 新增一节把「子集 ≠ 门禁通过」写死，并把 340 条未跑模块**显式列为无读数** | `README.md` §14.4 新增 bullet；本报告 ② E1-a |
| 5 | **交付物形态**：`ct-batch.patch` 自身是 CRLF（260 行），父侧检出为 LF ⇒ `git apply --check` **8/8 全部 does not apply** | **属实**（成因：早前用 PowerShell 重定向把 `git diff` 结果落盘，PowerShell 按 CRLF 终止每行） | 改用 `$t = (git diff -- <8 文件> | Out-String) -replace "``r``n","``n"` 后 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` 导出；**仅规范化行尾，内容一字未改**（父侧同法验证：30,902 B ⇒ `git apply --check` 8/8 全过、0 error、exit 0） | 见 ⑤「补丁行尾」 |

**同行附带发现（本轮自查新引入，已修）**：缺陷 1 的重写稿里含无前缀的 `§1 L16「固定值：mock-mall」`，被锚点门禁第 4 次拦下（`[门禁失败] 台账内锚点出现次数升高 1 处——README.md :: §1 L16 台账=2 实测=3`，`EXITCODE=1`）⇒ 只给**本轮新增的**那处补文件名前缀；§8 第 3 条 Q3 那处（历史基线，台账已登记 2 处）**刻意不动**。修后门禁复位 `PASS（199 处 / 112 行）`。**门禁再次证明其非空转**。

**本轮新踩并留痕的两个工具坑（均属取证脚本缺陷，非产品缺陷）**：

- **Python universal-newlines 静默改写**：`open(p, encoding="utf-8")` 默认 `newline=None`，会把 `\\r\\n` **静默翻译**成 `\\n`（README 读数短 321 字符 = CR 数；`split("\\r\\n")` 只得 1 段）。**处置**：一律 `open(p,"rb").read().decode("utf-8")` 或 `newline=""`，切行用 `splitlines()`。
- **`join + EOL` 多加一个行终止符**：源 7,271 B → `split("\\r\\n")` 得 80 元素（末元素 `''`）→ `EOL.join(...) + EOL` = 7,273 B，把台账尾巴撑成 `\\r\\n\\r\\n`。**处置**：正确往返是 `EOL.join(lines)`（**不加** EOL），并把「字节数 == join 长度」写成写盘前断言。

---

"""
B_NEW = REVIEW + B_ANCHOR

# ---------- 契约面指纹：README 终值 ----------
C_OLD = "- `contract-specs/README.md`：**`709137F9095D0FC0B5DCDA6C0FE203A8DB727E107D82C1ED48E95FDB568B6291`**（57,690 B；CR=LF=321）。"
C_NEW = ("- `contract-specs/README.md`：**`5896AD0EE9C1C6BBA7EE033CCB3FD55724D2944B366689F2A080EB428AE6B73C`**"
         "（60,960 B；CR=LF=**328**；LF 规范化形态 `E53F09019C15127590397E6D8A3E85E0D3DBDA335A4876D05537B4F6EF348883`，60,632 B）。"
         "演进链（逐轮现算，均留在 `fix-readme-*.log`）：`4FE3222D…`（47,709 B，本批开工）→ `709137F9…`（57,690 B，CT-1 落地）"
         "→ `FD2EF8BB…`（57,449 B）→ `1CEF75E2…`（59,847 B）→ `C715D6E7…`（60,913 B）→ **现态 `5896AD0E…`（60,960 B）**。")

# ---------- 变更面 numstat ----------
D_OLD = """`--numstat`（+91 / −20）：

```
1	1	…SourceRegistryMigrationScriptTest.java
0	1	…EventContract.java
9	4	…CanonicalEventSchemaParityTest.java
57	3	contract-specs/README.md
1	1	contract-specs/VERSION
15	6	contract-specs/schemas/canonical-event.v1.schema.json
3	3	docs/contracts/event-contract.md
5	1	scripts/check-bare-anchors.ps1
```

"""
D_NEW = """`--numstat`（终态现算；括号内为**首版提交审查时**的旧读数，留痕以便对照——README 因两轮改写而增长）：

```
1	1	analytics-server/platform-app/src/test/java/com/graduation/analytics/source/SourceRegistryMigrationScriptTest.java
0	1	analytics-server/platform-common/src/main/java/com/graduation/analytics/contracts/EventContract.java
9	4	analytics-server/platform-common/src/test/java/com/graduation/analytics/contracts/CanonicalEventSchemaParityTest.java
65	4	contract-specs/README.md                                    （首版 +57 / −3）
1	1	contract-specs/VERSION
15	6	contract-specs/schemas/canonical-event.v1.schema.json
3	3	docs/contracts/event-contract.md
5	1	scripts/check-bare-anchors.ps1
```

合计（终态现算）：**+99 / −21**（首版 +91 / −20）。README 由 `57 / 3` 变 `65 / 4`，增量全部来自本轮两处缺陷处置：① `:43` 改写 ＋ §3 批次补记引用块；② §14.3 改写 ＋ 原句引用块 ＋ §14.4 新增 E1-a 子集口径 bullet。**除 README 外 7 个文件的行数增减与首版逐项相同**（本批后两轮只动 README 与取证脚本）。

"""
E_OLD = "7. **E1-a 未覆盖模块**：`platform-app` 未纳入本批测试面（原因见 ② E1-a），其内含 `SourceRegistryMigrationScriptTest` 的断言本体**已由代码审读确认未改**，但**本轮无该模块的测试读数**。"
E_NEW = ("- **E1-a 未覆盖模块**：`platform-app` 未纳入本批测试面（原因见 ② E1-a 与 ⑦-3），其内含 "
         "`SourceRegistryMigrationScriptTest` 的断言本体**已由代码审读确认未改**，但**本轮无该模块的测试读数**。"
         "**进一步收紧**（缺陷 4 登记）：`warehouse-pipeline` / `metric-analysis` / `ai-decision` / `platform-app` "
         "共 340 条**本批一律未跑**，主检出全量改动前读数为 **537 tests / 1 failure / BUILD FAILURE**；"
         "本批 `41 + 156 = 197 / 0` 是 PLAN §3 字面命令的**真子集**，**不得**当作「PLAN 门禁已过」。")
E_OLD += "\n8. **无 E3/E4/E5**（PLAN §3 明示不产生）；无模块级自动化以外的证据。"
E_NEW += "\n8. **无 E3/E4/E5**（PLAN §3 明示不产生）；无模块级自动化以外的证据。"

# ---------- §14.3 与 §3.5 的锚点补前缀（README 侧，此处仅登记） ----------
F_OLD = "| `ct-batch.patch` | 8 文件统一差异（`git diff --binary` 导出），sha256 见 `fingerprints.txt`；**回滚依据** |"
F_NEW = ("| `ct-batch.patch` | 8 文件统一差异（`git diff` 导出，**未加 `--binary`**——8 个文件全为文本，无二进制段），"
         "sha256 / 字节数见 `fingerprints.txt`；**回滚依据**。**行尾 = 纯 LF**（`CRLF 行数 = 0 / 纯 LF 行数 = 272`，"
         "口径 `[IO.File]::ReadAllText` ＋ `[regex]::Matches($t,\"``r``n\")` / `(?<!``r)``n`，**不用** `ReadAllLines`/`Get-Content`），"
         "父侧 CRLF⇒LF 检出**可直接 `git apply`**（详证见 ⑥-11） |")

EDITS = [
    ("E1-a 子集口径 ＋ 537/1 基线", A_OLD, A_NEW),
    ("父侧审查回执处置小节", B_ANCHOR, B_NEW),
    ("README 终态指纹", C_OLD, C_NEW),
    ("numstat 终态", D_OLD, D_NEW),
    ("④-7 E1-a 未覆盖模块收紧", E_OLD, E_NEW),
    ("⑤ 补丁行尾条目", F_OLD, F_NEW),
]


def main():
    raw = open(RP, "rb").read()
    t = raw.decode("utf-8")
    print(f"REPORT.md = {len(raw):,} B  CR={raw.count(13)} LF={raw.count(10)}  行数={len(t.splitlines())}")
    if raw.count(13) != 0:
        print("[FAIL] 哨兵：REPORT.md 应为纯 LF（CR=0），实测 CR != 0 ⇒ 不写盘")
        return 2
    print("[OK ] 哨兵：纯 LF 文件（CR == 0）")

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
        print(f"  [跳] {x}（幂等：新串已在文内）")
    for x in failed:
        print(f"  [FAIL] {x}")
    if failed:
        return 2

    nb = t.encode("utf-8")
    if nb.count(13) != 0:
        print("[FAIL] 写后 CR != 0 ⇒ 不写盘")
        return 2
    if not applied:
        print(">>> 无需改动")
        return 0
    open(RP, "wb").write(nb)
    print(f">>> 已写盘  {len(nb):,} B（原 {len(raw):,} B）  行数={len(t.splitlines())}  sha256={hashlib.sha256(nb).hexdigest().upper()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
