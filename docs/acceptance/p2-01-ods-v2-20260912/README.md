# P2-01（ODS v2 公共列与 payload 原样保真）总控验收记录

| 项 | 值 |
|---|---|
| 任务 | **P2-01** ODS v2 公共列与 payload 原样保真（档 B／甲） |
| 交付提交 | `9679f5e`（**2026-09-12 14:41:25**）「P2-01 ODS v2 加法双写 · 续跑泳道交付入库并验收 = DONE_LIMITED（总控独立复核）；登记 F-45；消除跨泳道 USING 根因」 |
| 交付物 | 本目录（`docs/acceptance/p2-01-ods-v2-20260912/`）：`IMPL-REPORT.md`、`ORDER-1.md`、`p2-01-readonly-evidence.md`、`evidence/`（E3 真链 5 份日志 + `checks.tsv` + `summary.txt` + 变更/证据清单）、`harness/` |
| 状态 | **`DONE_LIMITED`**（总控复核后维持） |
| 本文件 | 总控（父侧）于 **2026-09-12 19:44–19:55** 追加 —— **ORDER-1 §6 要求总控追加的验收 README 此前缺失**，本文件即补齐物（该缺失由只读侦察泳道 `94792974` 实测 `Test-Path = False` 报出） |
| 自检脚本 | `tools/p2-01-close-verify.ps1`（只读、可复算、25 条判据 + 1 条正向对照 + 末尾条数自检） |
| 读数落盘 | `raw/close-verify-20260912-194356.txt`（首次尝试失败：`raw\` 尚不存在）、`…-194449.txt`（判据 23 PASS / 1 FAIL：缺本 README ⇒ **exit 1**，另暴露分类 bug ⇒ 假绿 exit 0）、`…-194529.txt`（同上，分类已修 ⇒ exit 1）、`…-194633.txt`（暴露 ArrayList 缺陷 ⇒ 非 0 退出）、**`…-194648.txt`（最终：判据 25 PASS / 0 FAIL、正向对照判红、`exit 0`）**；文件名一律取**脚本内部自报时点** |

---

## §1 复核结论

**① 交付成立（在 §3/§5 限定的范围内）** —— 依据是**泳道已落盘的外部制品**（非转述）：

- `evidence/e3-20260912-141554-summary.txt`：`E3_RESULT=PASS`；E3 用的 jar 是**构建副本** `spark-jobs/target/p2-01-built/spark-jobs-0.1.0-SNAPSHOT-p2-01-e3.jar`（284,147 B / sha256 `2ac3b6512715b305…` / classes=105）⇒ **在产 jar 未被 E3 消费**；
- `evidence/e3-20260912-141554-checks.tsv`：表头 1 行 + **判据 51 行，`pass` 列全为 `1`**（0 条非 1）；其中 `E3_20_newcols_present` 列出 5 个新列、`E3_31`/`E3_33` 的 payload 逐字节与 hash 对源切片均 `=0`、`E3_40_v1_col_order` 前 14 列逐格等于 v1；
- **隔离硬约束成立**：`summary.txt` 的 `realWarehouseBefore` 与 `realWarehouseAfter` **逐字符相同**（`FILES=2006 SHA256=3c7e3deaf99072de920124501ca742a71f6490246e47ad2da7af7acb25ea20ae`），E3 的 warehouse/Derby 全部隔离在 `spark-jobs/target/p2-01-e3/20260912-141554/` 下 ⇒ **真 `spark-warehouse` 未被写入**（D-060 硬约束）；
- 本轮总控**未复跑** Maven 与真链（见 §3）⇒ 本条是「**外部制品复核 + 独立结构复核**」，**不是**第二轮独立复现。

**② 本轮总控新增的列级证据（与 R2 的 E3-f 归因联动）**：

- `git show 9679f5e^:warehouse/ddl/00-ods.sql` 与 `git show 9679f5e:…` 解析 `ods_behavior_event`：**父提交 14 列 → `9679f5e` 19 列**，末尾追加的正是 `raw_event_type, raw_source_system, landing_file, payload_json, payload_hash`；v1 十四列**名字与顺序逐格不变**（D-059 的「末尾追加」成立）；
- 一次 Spark 真读（`spark-sql -f`，exit 0 / 8.3 s，R2 期间所取）实测 parquet 为**同名同序 19 列** ⇒ R2 期间 ODS 层 **+3,199,580 B** 可归因到 P2-01 的**加法双写**；
- CT 提交 `5690ffb` 对 `warehouse/ddl` 与 `spark-jobs/src/main` 的 diff **各 0 行**；run 41 窗口（09:23–09:31）的 HEAD = `3199a26`（09:19:24）＜ 14:41:25 ⇒ **CT 被排除**、run 41 的树不可能含这 5 列。

**③ 脚本自检值**：`tools/p2-01-close-verify.ps1` 当前输出 **判据 PASS 25 / FAIL 0**、正向对照按预期判红、打印条数 = 判据 + 对照、`exit 0`；修复前的一版如实留下 `PASS 23 / FAIL 1`（缺本 README）与 `exit 1`。

---

## §2 本次实测读数（父侧，2026-09-12 19:44–19:55；命令见 §6）

| 面 | 应然（外部登记/冻结值） | 实测 | 判定 |
|---|---|---|---|
| `warehouse/ddl/00-ods.sql` | P2-01 后登记 sha256 `2BEBE55F…6863`、6,178 B | `2BEBE55FF09580443937F945A31DACF856BBBD898FB7C1B953DC6C7465816863`、6,178 B（mtime 09-12 13:01:45） | PASS |
| `01-dwd.sql`/`02-dims.sql`/`03-dws.sql`/`04-ads.sql` | 草案 §5.5(2) 基线 `8ECF37D5…`3509B／`AD2D0881…`2965B／`D1BE5E71…`4194B／`3057DE99…`5207B，**均未被 P2-01 触碰** | 逐项相同（mtime 全为 09-11 18:01:27） | PASS |
| `ods_behavior_event` 列数 | 14（父提交）→ 19（`9679f5e`） | 14 → 19，v2 5 列全在，v1 前缀逐格相同 | PASS |
| 5 个 v2 列名的引入面 | 代码/DDL 面（`spark-jobs/src/main` + `warehouse/ddl`）**唯一** `9679f5e` | 5/5 均为 `9679f5e` 唯一；**全仓**另有 14（`raw_event_type`）～19（`payload_json`）个提交命中 —— 多出的是**后续文档/证据引用** | PASS（口径必须限定，见 §5） |
| CT 排除 | 0 行 | `warehouse/ddl` 0 行、`spark-jobs/src/main` 0 行 | PASS |
| run 41 的树 | ≠ `9679f5e` | `3199a26`（2026-09-12 09:19:24 +0800） | PASS |
| E3 制品 | `E3_RESULT=PASS`、51 判据全 pass | 一致（`realWarehouse` before == after） | PASS |
| 在产 jar `spark-jobs-0.1.0-SNAPSHOT.jar` | D-060 冻结 `234,038 B / 2026-09-11 18:19:03 / F9E879AA…` ⇒ **判据「仍等于冻结值」应为 False** | **285,256 B / 2026-09-12 16:15:07 / `4F29735547D6DDEF…`** | PASS（冻结确已失效 ⇒ 见 §5 F-81） |

---

## §3 复核方式与**本轮未做**（边界）

- **未跑** Maven（任何模块）、**未跑** Spark 真链／`spark-submit`、**未连** MySQL/Hive、**未启停** 8090/8091/8092、**未写**任何库、**未动** `landing/`、`spark-warehouse/`、`metric-staging/`；
- 本轮只用：`git` 只读对象与 diff、文件指纹、泳道已落盘制品；**一次既有 `spark-sql -f` DESCRIBE 读数**为 R2 期间所取（已登记于 R2 目录），本轮**未重跑** Spark；
- **跑前** ODS 的物理列集**未**做文件级实测（旧文件已被 run 43 覆盖，866 个 parquet 的 mtime 全落 17:35:18–17:35:39）⇒ §1② 的「14 列」是**代码级**（父提交 DDL + 提交时点）证据，**不是**旧 parquet 的直读；
- 本轮**未**逐列比对字节占比 ⇒ 「+70% 由这 5 列造成」的**因果量化未做**（只有列级归因，无字节级分解）。

---

## §4 规格面收敛状态与需补记项（**本轮只登记，不代替裁决**）

1. **裁决文件标题与正文不一致**：`p2-01-ods-v2-spec-draft-20260912/RULINGS.md` 标题写「D-052…D-058」，正文实含 **D-059**（v2 新增列末尾追加）与 **D-060**（端到端 Hive 断言只走真 `spark-submit`、禁改 pom、`landing_file` 取自 `_metadata.file_path`）⇒ 实际 **9 条**；看板行 220 只引 7 条 ⇒ 建议补记（口径小瑕，无实质分叉）。
2. **D-054 冻结「新增 4 列」vs 实现「5 列」**：实现多出 `raw_source_system`，该串在 `RULINGS.md` **零命中**；**看板行 460 ⑥ 已由总控事后追认**（原文：「静态 DDL 必须与运行时唯一所有者 `OdsV2Columns.scala` 的 **5 个新列**逐列一致，且由 `OdsV2SchemaOwnerSpec` 对账钉住；`numstat` +31/−4」）⇒ **有事实授权、无 RULINGS 正文条目**。**建议**：在 `RULINGS.md` 追加一条（如 `D-061`）把「5 列」正式收编；**本记录不代替该裁决**。
3. **文档漂移**：`OdsV2Columns.CommonColumns` 的 KDoc 写「12 列」而实际 **13 条**（D-052 的 12 列是**逻辑分组**）—— 由泳道 `IMPL-REPORT §5` 反熵声明 #7 自报，**措辞未改**；登记待办。
4. **RULINGS 的时点性描述已过期**：其断言「契约仍 1.3.0」已被 CT 批次超越（现 `contract-specs` **2.2.0**，看板行 477）；`order_created.items` 已由 **CT-3** 改为**加性 `oneOf(array|string)`** ⇒ 与 D-057「`items` 保留现状仅登记」**在契约层并存**（契约承认双形态、代码层按 D-057 不动 `itemsArrayType`）。
5. **列序所有者**：`spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala`（183 行；`CommonColumns`/`V1Shared`/`V2NewColumns`/`dataColumns = v1Columns ++ V2NewColumns`/`allColumns = dataColumns ++ PartitionColumns`）为**列序单一所有者**，静态 DDL 与其**两个所有者**由 `OdsV2SchemaOwnerSpec` 对账钉住。
6. **F-45 已登记**：泳道曾声称「模块外改动 `warehouse/ddl/00-ods.sql` 已在 ORDER-1 登记例外」，总控实测 `ORDER-1.md` **零改动、全文无「例外」二字** ⇒ 声明不实；改动本身被**接受并事后追认**，并立规则「凡声称『已登记/已写入某文件』必须给出 file:line，总控按行核对」。

---

## §5 未测与**不得声称**（本轮口径）

- 不得声称本轮**独立复现**了 E3（本轮只读 + 复用泳道制品）；
- 不得声称在产 jar 等于 D-060 冻结值（实测已失效）；`runtime_profile` **无 jar 指纹列**（F-81 未修）⇒ 库内仍无法证明「某快照由哪版作业发布」；
- 不得声称 P2-01 已在**真实数仓**落地（E4 记录显示本地 `dw_ods` 的 v2 对齐发生在**其后**的本机 smoke 路径）；
- 不得声称 P2-02…P2-06 已开工；**P2-07 的 E2 未复跑**（看板行 460 ⑤）；`itemsArrayType` 在**代码层**仍按 D-057 保留；
- 不得声称「列集只有一个所有者」（现为**两个**：静态 `00-ods.sql` + 运行期 `OdsV2Columns`）；
- 不得声称「5 列引入面在全仓唯一」—— 该唯一性**仅在** `spark-jobs/src/main` + `warehouse/ddl` 限定面成立；全仓另有 14–19 个提交因**文档引用**命中；
- 不得声称「E3-f 已通过」（R2 判定仍为红）；也不得声称「CT 导致 ODS 变宽」（CT 已排除）；
- 不得声称看板任务表状态已对齐（F-87：行 220 曾长期写 `READY` 而台账行 460 已是 `DONE_LIMITED`，本轮已就地更正）。

---

## §6 复算（只读；任何一条失败都不会被隐藏）

```powershell
# ① 全量自检（25 条判据 + 1 条正向对照；期望 PASS 25 / FAIL 0、正向对照判红、exit 0）
pwsh -NoProfile -File docs\acceptance\p2-01-ods-v2-20260912\tools\p2-01-close-verify.ps1

# ② 列数与引入面（口径必须限定路径）
git show '9679f5e^:warehouse/ddl/00-ods.sql'   # ods_behavior_event = 14 列
git show '9679f5e:warehouse/ddl/00-ods.sql'    #                    = 19 列
git log --format=%h -S payload_json -- spark-jobs/src/main warehouse/ddl   # ⇒ 仅 9679f5e
git log --format=%h -S payload_json                                        # ⇒ 14–19 个（含文档引用）

# ③ CT 排除
git diff 5690ffb^ 5690ffb -- warehouse/ddl          # 0 行
git diff 5690ffb^ 5690ffb -- spark-jobs/src/main    # 0 行

# ④ DDL 指纹登记（草案 §5.5(2) 口径）
Get-ChildItem warehouse\ddl\*.sql | ForEach-Object { '{0}  {1}  {2}B' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash, $_.Name, $_.Length }
```

**退出码语义**：`0` = 全部判据成立且正向对照按预期判红；`1` = 存在红项（红项在输出中逐条列出，不隐藏、不改写）。

---

## §7 自检脚本自身的缺陷迭代（诚实留痕：**4 处**，全部由「正向对照 + 条数自检」暴露）

| # | 缺陷 | 表现 | 后果 | 处置 |
|---|---|---|---|---|
| 1 | `Where-Object` 里误用**陈旧循环变量** `$r`（应为 `$_`）做 `CONTROL-*` 分类 | 25 条全被判为「对照」、判据计 0 条 | **假绿：判据全 FAIL 却 `exit 0`** | 改 `$_`；判据/对照分别计数后 `exit` 由判据决定 |
| 2 | `git log -S` **未限定路径** | 「引入面唯一」写成全仓口径 | 口径**过宽**：`payload_json` 全仓命中 19 个提交（含后续**文档/证据**引用），实际限定面仅 `9679f5e` | 限定 `spark-jobs/src/main warehouse/ddl`，并增设判据 `LOG-S-scope-required` |
| 3 | 真仓库隔离比对取**整行**（含 `realWarehouseBefore=` 前缀） | before/after 值相同却判 FAIL | 假红 | 按 `=` 切分后**比值**；Readings 只打印值 |
| 4 | ArrayList `+= ,@(...)` 把整个数组当一个元素 | `Measure-Object -Sum` 报「不是数值」 | 脚本中途非 0 退出 | 改 `[void]$List.Add([int]$n)` |

**说明**：本记录**不隐藏**脚本曾经的假绿与假红；修复前后的每一版读数都在 `raw/` 中留档（见文件头表格），最终版为 `raw/close-verify-20260912-194648.txt`。