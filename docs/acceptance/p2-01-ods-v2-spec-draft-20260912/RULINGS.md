# P2-01 裁决记录（D-052…D-058）：ODS v2 公共列与 payload 保真

- 裁决人：总控（热点 Owner）｜日期：2026-09-12
- 裁决对象：`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/draft/p2-01-spec-draft.md` §7 的 **D-1…D-9**，
  以及该草案 §1.6 的三处原文不一致（C-1/C-2/C-3）
- 权威顺序（本次裁决一律遵守，冲突时上位优先）：① 已冻结契约（`specs/warehouse-namespace.v1.json`，`FROZEN-2026-09-11`）
  → ② 指导书 V2.3 → ③ 看板 V2.2 → ④ 专项设计（计划书 / 设计书，同级）→ ⑤ 指导书 V2.1
- 前置纪律：指导书 V2.3 `:45`「若实现与契约冲突，先提交契约变更决策，再修改实现」；冻结决策
  ② 档 B（配置化映射 + 原始 `payload_json` + 代理键）、③ 甲（分析骨架固定 + 注册表）、④ 甲（每源独立数仓命名空间）
- 生效方式：本记录即裁决正文；实施车道按本记录开工，**不得自行变更裁决**。P2-01 的看板状态据此由 `TODO` 改为 `READY`。

## 0. 一页速览

| 编号 | 对应问题 | 裁决（一句话） |
| --- | --- | --- |
| **D-052** | D-1 + C-1 | 看板 L220 出口证据**三件并列**；公共列取 **12 列**（计划书 L79 上位，"至少共享"） |
| **D-053** | D-2 + C-2 | `event_time` **保持 `STRING`**；改 `TIMESTAMP` 属破坏性变更，P2-01 不做 |
| **D-054** | D-3 + C-3 | **双写**：v1 `payload_*` 照现状填充 + 新增 4 列；`payload_json` 为 payload **唯一事实所有者**，DWD 切换排 **P2-04** |
| **D-055** | D-4 | 业务键**定义权归契约**；P2-01 **不动 DWD 去重键**（单键 `event_id`）＋ 补契约文字与**混源守卫** |
| **D-056** | D-5 + D-6 | `source_system` 走 **`spark-submit --extra` 注入通道**（值取自 `source_registry.source_code`）；`raw_event_type` 本轮**只保证列存在 + 源 A 平凡值**，映射与源 B 词汇**留 P5** |
| **D-057** | D-7 + D-8 | `items` **保留现状仅登记**；**新增 `landing_file`** 且 **`source_file` 改为真实值**（消灭常量 `'landing'`） |
| **D-058** | D-9 | P2-01 **只做加法扩列**；per-source 命名空间**另立在册任务（P2 内完成）**，不在 P2-01 落地 |

## 1. D-052（= D-1 + C-1）出口证据读法与公共列数

**裁决**
1. 看板 L220 出口证据列 `DDL/显式 schema/额外字段无 DDL 变更` 读作**三件并列**：
   (a) `warehouse/ddl/00-ods.sql` 与运行期显式 schema（`LocalSchemaInitJob`）**都**含全部公共列且逐项一致；
   (b) 结构由**显式 schema**声明（不靠推断/不靠约定）；(c) 新增列对既有列**无 DDL 变更**（不改名、不改类型、不删列）。
2. 公共列取 **12 列**（C-1：计划书 L79 在上位且写「至少共享」；`dt`/`hour` 为分区列）。

**依据**：看板 L220 三词并列的原文；计划书 L79 的「至少共享…12 列」；C-1 两处原文。

**后果与前置**：出口证据 = ①「12 列逐项对照表（DDL ↔ 显式 schema ↔ 计划书 L79）」②「新增 4 列清单」
③「对既有列零破坏」断言。分区列 `dt`/`hour` 不作为 `payload_json` 保真断言的对象（它们由平台派生，不来自落地区原文）。

**不得声称**：不得声称"12 列"是设计书的结论（设计书写 9 列，本裁决是按权威顺序取的更高位原文）。

## 2. D-053（= D-2 + C-2）`event_time` 类型

**裁决**：`event_time` **保持 `STRING`**。

**依据**：全链路现状为 `STRING`（`OdsLoadSql.scala:31`、`DwdSql.scala:22` 直接 `UNIX_TIMESTAMP(rn.event_time)`）；
改成 `TIMESTAMP` 是破坏性变更，与看板 L220「无 DDL 变更」直接冲突；设计书 L171 属 ④ 级，不得覆盖 ③ 级出口证据要求。

**后果与前置**：若将来要改类型 ⇒ 单独任务：契约变更 + 分区表新版本 + 转换器，且必须先有回滚方案。**不在 P2-01**。

**不得声称**：不得声称 `STRING` 是"更好的设计"（只是现状最优解：加法、可回滚）。

## 3. D-054（= D-3 + C-3）双写与唯一所有者

**裁决**
1. v1 的 `payload_*` 列**保留、类型不变、按现行为继续填充**（保证 DWD/DIM/DWS/ADS 零改动仍可跑）；
2. 新增 4 列：`raw_event_type STRING`、`landing_file STRING`、`payload_json STRING`、`payload_hash STRING`；
3. `payload_json` = 落地区 JSON 行 `payload` 键对应对象的**原始文本（原样字节）**，不解析重排、不美化/压缩；
4. `payload_hash` = `SHA-256(UTF-8(payload_json 原始字节))` 十六进制小写；**只用于冲突诊断，不参与去重**；
5. **唯一所有者规则**：`payload_json` 是 payload 的**唯一事实所有者**；`payload_*` 派生列在 P2-04 切换后进入
   **只读/待废弃**状态（退役属破坏性操作，需用户显式确认，不在本裁决授权范围内）；
6. DWD 切换到 `get_json_object(payload_json, …)` 排 **P2-04**。

**依据**：设计书 L226「保留 v1 DDL 与旧列一个版本」；草案 §3.2「payload 是解析后重写 ⇒ 保真缺口」；
反熵纪律「同一事实只允许一个所有者」。

**后果与前置**：P2-01 期间存在**两个 payload 所有者**（`payload_*` 与 `payload_json`），这是**被明确设定期限的过渡态**，
必须在 P2-04 收口；验收文档须写明该过渡态与截止点，不得让"重复所有者"长期化。

**不得声称**：不得声称 P2-01 完成即"payload 保真已闭环"（DWD 仍读派生列）。

## 4. D-055（= D-4）业务键定义权与 DWD 去重键

**裁决**
1. 业务键 `(source_system, event_id)` 的**定义所有者 = 契约**（`docs/contracts/event-contract.md` 与
   `canonical-event.v1.schema.json`），**不是** P2-01 车道；
2. **P2-01 本轮不改 DWD 去重键**（继续单键 `event_id`，`DwdSql.scala:31`）；
3. 但**先登记契约变更任务**（CT-2）：把去重语义明确为「**同一 source namespace 内 `event_id` 唯一**」，
   依据 = 冻结决策 ④（每源独立数仓命名空间 ⇒ 同一 ODS 表内 `source_system` 恒为单值）；
4. **混源守卫（硬条件）**：若将来允许同一 ODS 表混放多源 ⇒ **必须**改为 `(source_system, event_id)` 复合键，
   并同步契约、DDL 校验与 DWD；该守卫写入契约文字，作为不可绕过的前提。

**依据**：契约 `event-contract.md:165` 与 `canonical-event.v1.schema.json:10` 现写「按 `event_id` 去重」；
计划书 `:81` 写复合键 ⇒ 两处冲突未决，按指导书 V2.3 `:45` 必须先裁决；冻结决策 ④ 使单键在当前架构下**语义充分**。

**后果与前置**：CT-2 属契约变更批次（见 §8），与 F-31 一并落 `VERSION 1.4.0`；在 CT-2 落地前，
任何"业务键已定义"的说法都不成立。

**不得声称**：不得声称"单键去重已获契约冻结"（契约文字尚未改，仅在册待改）。

## 5. D-056（= D-5 + D-6）`source_system` 注入通道与 `raw_event_type` 取值

**裁决**
1. `source_system` **由平台注入**，走 `spark-submit --extra` 新参数（复用 `JobCommandBuilder` 的 `extra` 通道，
   先例 = `hiveDatabasePrefix`，`JobCommandBuilder.java:97` + `WarehouseNamespace.scala:88`）；注入值取自
   `source_registry.source_code`；
2. **不得**在 SQL 里信任行内 `source_system`；**不得**依赖 `mock-mall.v1.json`（G-12）；
3. `raw_event_type` 取值来源 = **落地区原始事件类型词汇**；源 B 的原始词汇在采集层已被丢弃
   ⇒ **本轮只保证"列存在 + 源 A 平凡值"，映射与源 B 词汇保真明确留 P5**（属泳道 A / P5 边界）。

**依据**：全仓 `--sourceSystem` 零命中（无既有注入通道）；`OdsLoadSql.scala:96` 现直接 select 行内值；
`hiveDatabasePrefix` 是唯一同构先例。

**后果与前置**：改动面包含 `JobCommandBuilder`/`PipelineService`（参数透传），须在 P2-01 验收中给出**透传实测**
（真实 `spark-submit` 命令行或等价证据），不得只做代码静态推断。

**不得声称**：不得声称 `raw_event_type` 已能表达异种源词汇（源 B 词汇保真未做）。

## 6. D-057（= D-7 + D-8）`items` 表示与 `landing_file`/`source_file`

**裁决**
1. `items`：**保留现状（不删 `OdsLoadSql.scala:205-212` 的 `itemsArrayType`、不改表示）**，仅登记为已知缺口；
   契约（数组）与真实数据（转义字符串）的不一致**已在册**（`contract-specs/README.md` Q5），
   与 CT-1/CT-3 同批处置；
2. `landing_file` **新增**，且 **`source_file` 必须改为真实值**——常量 `'landing'`（`SqlTemplateSpec.scala:39` 钉住）
   必须消失。取值实现优先 `input_file_name()`（**未实测**）；若实测不可用，允许填批次/清单标识，
   但**必须**在验收中写明实现方式与取值语义，且 `source_file` 与 `landing_file` **同源同值**。

**依据**：反熵纪律「不留语义错误的旧列」（一个填常量的 `source_file` 是第二事实所有者）；
「不得静默改写历史」⇒ 只改**取值**，不改列名/类型。

**后果与前置**：若 `input_file_name()` 在目标执行模式下不可用，属**实测结论**，须在验收 README 记录命令与输出。

**不得声称**：不得声称 `items` 表示问题已解决（只登记）；不得声称 `source_file` 必为真实文件名（取决于实测）。

## 7. D-058（= D-9）单源加法扩列 vs per-source 命名空间

**裁决**
1. P2-01 **只在现有单源库上做加法扩列**（`mock-mall` 独占 `dw_ods` 等），**不建 per-source 表/库**；
2. **per-source 命名空间不在 P2-01 落地，但必须在 P2 内完成**：按看板 L247 的两件事——
   ① 前缀所有权从 profile 级迁到源级（`source_registry`）并过 `WarehouseNamespace` 校验（四类错误码）；
   ② 在源保存/激活路径上校验前缀，禁止绕过；
3. 因 L247 明确要求"P2 必做"却**没有对应看板行**，本裁决**新立在册任务行**（见 §9 的看板变更）。

**依据**：冻结契约 `specs/warehouse-namespace.v1.json` 第 30 行 `sourceOfTruth`（运行期前缀仍取自
`runtime_profile.hive_database_prefix`，"由 `source_registry` 接管"明确排在 P2）；看板 L247。

**后果与前置**：**"每源命名空间已生效"在 P2 完成前不成立**（P5-03 的源 B 全链依赖此项）；
P2-01 的验收**不得**包含任何 per-source 命名的断言。

**不得声称**：不得声称 P2-01 已满足源级隔离；不得声称 L247 的提醒已被 P2-01 覆盖。

## 8. 契约变更任务清单（CT，实施前必须先办）

| 编号 | 任务 | 触发/排序 | 在册情况 |
| --- | --- | --- | --- |
| **CT-1** | `canonical-event.v1` 去掉 `source_system` 的 `const "mock-mall"`（放宽为普通字符串约束），并同步平台侧结构对账测试 | **P2 内、P2-01 之后、P5（源 B）之前**：留着 `const` 会让源 B 事件在校验层被拒 | 设计书内部冲突（L201 排 P3 vs L168-169 排 P2）⇒ 本裁决取 **P2 内**；Q3 部分在册 |
| **CT-2** | 去重语义文字化（D-055 的 3、4 两条：namespace 内单键 + 混源守卫） | 随契约批次 | **新**（本记录首发登记） |
| **CT-3** | `items` 表示不一致（契约数组 vs 真实字符串）的处置 | 随契约批次 | `contract-specs/README.md` **Q5** 在册 |
| **CT-4** | `canonical-event.v1` 冻结前置：B-06/Q6（采集层 51 行中约 25 行按契约属脏数据）与 Q12/Q16 | 冻结前 | B-06/Q6/Q12/Q16 在册，**未决** |
| **CT-0** | `contract-specs/README.md` 当前态版本串滞后（`:3` 与 `:56` 仍写 `1.2.0`，`VERSION` 已是 `1.3.0`）＝ **F-31** | **已随本轮修复**（勘误级，不升版；补记 + 哈希重登记） | 本轮登记并修复 |

**契约变更批次规则**：CT-1/CT-2/CT-3 与任何字段级改动一并落 `contract-specs/VERSION` **`1.3.0 → 1.4.0`**，
带日期补记、逐条"命中且仅命中 1 次"断言、前后 SHA-256 对照；**不允许多批次各改一次语义**（反熵：单一批次单次升版）。

## 9. 看板变更（随本记录落地）

1. P2-01 行：`TODO` → `READY`，追加「裁决 D-052…D-058 已下（指针 `RULINGS.md`）」；
2. 新增 P2 行：**源级数仓前缀所有权迁移**（看板 L247 的两件事），因 L247 声明"P2 必做"而原先无行；
3. §5.1 新增 **F-31**（契约 README 当前态版本串滞后，已修，勘误级）；
4. §6 追加本轮日志行。

## 10. 不得声称（整体）

- 不得声称 P2-01 已完成（本轮只下裁决，未写一行实现代码、未跑任何测试）。
- 不得声称"契约已改"：CT-1/CT-2/CT-3 **尚未落地**，契约仍是 `VERSION 1.3.0`（DRAFT 部分依旧 DRAFT）。
- 不得声称业务键、payload 保真、源级命名空间已闭环（分别见 D-055/D-054/D-058）。
- 不得声称草案 §7 的"临时默认"全部被采纳：D-8 的默认被**加强**（`source_file` 必须改真实值），
  D-4 的默认被**限定**（定义权归契约，且必须先补契约文字）。

## 11. 未取证（本裁决未证实的部分）

1. `input_file_name()` 在本项目执行模式下可用（D-057 的候选实现，**未实测**）；
2. `--extra` 通道对新增参数的透传在真实 `spark-submit` 下可用（只有 `hiveDatabasePrefix` 的同构先例）；
3. 12 列在 DDL 与显式 schema 两侧**当前**是否已逐项一致（草案 §3.5 指出列集有**两个所有者**，未做逐项对账实测）；
4. CT-1 所涉平台侧对账测试的具体断言行（未读该测试全文）；
5. 计划书 L148「为每个 source namespace 建 v2 表」的完整上下文（本裁决按其与冻结决策 ④ 的组合读法处理）；
6. 源 B 原始事件词汇在采集层的丢弃点（P5 范围，未定位到行）。
## 补记（2026-09-12 11:59）—— CT-0（F-31）状态更正：本轮**未**落地

- §8 表中 CT-0 写的「已随本轮修复」**不成立**，以本补记为准：本轮**没有**成功写入 `contract-specs/README.md`。
- 事实：该文件在本轮施工期间**被并发写者两度改动后删除**（`git status` 两度为 ` D`）；我的三次写入尝试均因文件内容在两次调用之间被外部改写而**在写入前中止**（`Swap1` 的「命中且仅命中 1 次」断言拦下，**未产生半成品写入**）。证据与恢复见看板 **F-32**。
- 因此 CT-0（契约 README 当前态版本串 `1.2.0` → `1.3.0`）状态改为：**登记，未落地**；落地前提是「同一工作区的并发写者边界先澄清」，否则每次写入都可能被外部覆盖。
- 其余 CT-1/CT-2/CT-3 排序**不受影响**（本就与 F-31 同批次，随 `VERSION 1.3.0 → 1.4.0` 落地）。
- 未取证：无文件审计/句柄级证据指认具体进程；F-32 只到「同一工作区有 Codex app-server 在跑 ＋ `contract-specs` 目录 ACL 含其沙箱主体」这一层。

## D-059 v2 新增列**末尾追加**；v1 十四列的名字与物理序号冻结（2026-09-12 12:5x）

- **裁决**：采纳施工泳道的保守解——v1 十四列的名字与物理序号**一格不动**，v2 新增列追加在 `ingest_batch_id` 之后。
- **理由**：D-052(c) 的约束对象是"不改名/不改类型/不删列"；**按列位置读取 Parquet 的既有外部消费方零感知**才是"加法扩列"的完整含义。计划书 L79 的"12 公共列"是**逻辑分组**叙述，不构成物理布局要求。
- **附加要求**（否则本条不算落地）：① `OdsV2Columns.scala` 注释写明"新增列位置＝末尾追加、列序单一所有者"；② 增一条**结构断言**：v1 十四列的名字与序号逐格等于 v1，且新增列序号紧随其后——这是防未来重排的机械守卫，不靠人读。
- **未取证**：新增列的序号区间取决于 v2 列集最终定稿，本条不冻结该区间；以 `OdsV2Columns.scala` 为单一所有者。

## D-060 端到端 Hive 断言只走真 `spark-submit` 进程；**禁止**为此改 `spark-jobs/pom.xml`（2026-09-12 12:5x）

- **触发**：施工泳道实测两个端到端 suite 在 `mvn test` 下 `beforeAll` ABORT（`Unable to instantiate SparkSession with Hive support because Hive classes are not found.`），并**自报根因**为「`spark-hive_2.12` 被声明为 `provided`，故不在测试 classpath」。
- **总控实测更正（该归因不成立）**：`spark-jobs/pom.xml` **从未声明** `spark-hive_2.12`——只有 `spark-core_2.12`/`spark-sql_2.12`/`hadoop-client`，三者 `provided`；而 Maven 的 test classpath **本就包含 `provided`**。⇒ 症状真实、**归因错误**。此为「泳道自报归因亦须复核」的第 2 例，与看板 F-38 ③ 同族。
- **且离线不可解析（实测）**：`D:\maven_repository` 内 `org\apache\spark\spark-hive_2.12` **只有 3.3.2**（无 3.5.1）；`datanucleus-core` **整目录缺失**；`hive-metastore` 只有 2.3.9。⇒ 任何"新增 test 域依赖"或"profile 提权"在 `mvn -o` 下**必然失败**；以 3.3.2 混 3.5.1 属伪造环境，**禁止**。
- **裁决**：否决 (a)(b)；端到端断言走 **(c) 真 `spark-submit` 进程**。可行性实测：`D:\Develop\spark-3.5.1-bin-hadoop3\jars\` 含 `spark-hive_2.12-3.5.1.jar` ＋ 14 个 `hive-*` ＋ 3 个 `datanucleus*` ＋ `derby-10.14.2.0.jar`，`bin\spark-submit.cmd` 在位；既有先例 `.verify\p1-06-t2-run.ps1`、`.verify\p1-06-t2-replay-batch31.ps1`。
- **硬条件**：① 临时 warehouse 与临时 Derby metastore 均置于 `target/` 之下，并以**响亮断言**保证解析出的 `warehouse.dir` 位于 `target\` 内——`D:\Develop_code\GraduationProject\spark-warehouse`（既有 6 库真仓库）与既有 metastore **绝不可写**；② jar 覆盖/还原严格按 `p2-01-ods-v2-20260912/ORDER-1.md` R1 (a)–(f)：备份并核对冻结 jar `234,038 B / mtime 2026-09-11 18:19:03 / sha256 F9E879AADEA71C9301B079FC70D714C3DEF6DE30902DB39FF92F428C596318A8`，跑完**还原并复验同一 sha256**（总控 2026-09-12 12:5x 复核该值未变）；③ 每轮 spark-submit 的完整命令行、全量 `--conf`、stdout/stderr、退出码全量落盘，判定从原始输出取证；④ 证据级别标注为 **E3（本地真实链）**——**不是** E2，也**不是** T2 权威 55 条链复跑（后者归 M1-11）。
- **禁止**：为"让 E2 也能建表"改动 `spark-jobs/pom.xml` 依赖；若确需，属新任务且须先入规格。
- **附带批准**：`JsonObjectSlicer` 的纯函数断言（A5/A5b/A4b）移入不建表的 suite，先取红。
- **已确认只读事实**（补 D-057）：`_metadata.file_path` 实测非空（`file:/D:/Develop_code/GraduationProject/tests/golden-dataset/events/golden-20260901.jsonl`），而 `input_file_name()` 在同一读法下返回**空串** ⇒ `landing_file` 必须取自 `_metadata.file_path`。

## 追认（2026-09-12 20:1x，父侧只读复算）—— D-054「新增 4 列」vs 实现「5 列」：**以 5 列为准**，本文件补齐该追认

- **缺口（实测）**：本文件此前 `raw_source_system` **0 命中**（正对照 `source_system` **8 命中** ⇒ 检索有效）。而实现侧确实多出该列：`spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala` 行 **48**（`CommonColumns`）与行 **69**（`V2NewColumns`）各声明一次 `Column("raw_source_system","STRING")`；文件头注释（行 **15**）自述为「加法扩列」新增 `raw_event_type` / `raw_source_system` / `landing_file` / `payload_json` / `payload_hash`。运行期证据见 `docs/acceptance/p2-01-ods-v2-20260912/IMPL-REPORT.md` 行 **190**（`E3_20_newcols_present` ⇒ 5 个新列俱全）与行 **193**（DESCRIBE 21 列）。
- **追认依据（两层，均已在库）**：① 看板行 **460 ⑥** 已由总控事后追认「静态 DDL 必须与运行时唯一所有者 `OdsV2Columns` 对齐」，该追认被 `docs/acceptance/p2-01-ods-v2-20260912/README.md` 行 **61** 明确引用；② 本轮独立复算确认 **ODS v2 列清单不在冻结契约文件内** —— `contract-specs/` 共 **9** 个文件中 `raw_source_system` **0 命中**（正对照 `source_system` **18 命中**），契约面只有 `canonical-event.v1.schema.json`、`generation-artifact-manifest.v1.schema.json`、`ingestion-manifest.v1.schema.json`、`specs/surrogate-key.v1.json`、`specs/warehouse-namespace.v1.json`、`specs/warehouse-namespace.v2.json`、`openapi/generator-api.v1.yaml`、`README.md`、`VERSION`。⇒ 本偏差属**裁决口径 vs 实现**，**不**触发契约升版流程。
- **裁决（追认，非新增）**：D-054 的「新增 4 列」按**冻结时点口径**理解；实现多出的第 5 列 `raw_source_system` 是 **D-056 的承载列**（`source_system` 只能来自平台注入通道，行内原值只保留在 `raw_source_system`，见 §5）。**两处均为准**：列数以实现（**5 列**）为准，D-054 文义不再逐字复述。**不得**为对齐「4」而回退实现（破坏性，且会使 D-056 失去承载列）。
- **未实测（诚实申报）**：本轮**未**连库、**未**建表、**未**跑任何链；上述实现面结论取自文件与既有 `IMPL-REPORT.md`，不是本轮新跑的读数。
- **效力范围**：本追认只解答「4 vs 5」这一处，**不**代表 P2-01 其余口径已被复核，也**不**改变 P2-01 的 `DONE_LIMITED`。