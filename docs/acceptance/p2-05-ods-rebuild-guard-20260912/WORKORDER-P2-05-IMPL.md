# 施工单草案 —— P2-05（ODS 重建守卫与失败保旧 ACTIVE）

- 制品类型：**施工单草案（work order draft）**
- 对应看板行：`docs/项目实施进度与任务看板 V2.2.md` **L224**
- 起草时点：**2026-09-12 20:16**（`Get-Date` 实测）
- 依据：同目录 `draft/p2-05-spec-draft.md`、`RAW-READINGS.md`、`raw/RUN-20260912-2015.txt`
- **本单是「要求清单」，不含实现。** 本泳道未写任何代码。
- **开工前置**：§5 待裁决清单中 D-1、D-2、D-3、D-5 **必须先获总控裁定**；未裁定前**不得开工**。

---

## 1. 施工范围（只碰这些）

| 允许改 | 说明 |
|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala` | 守卫调用点（L121 所在方法） |
| `spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala` | `INIT_SCHEMA`（`sci`）内的备份清单与守卫步骤 |
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala` | **仅在 D-1/D-4 裁定需要时** |
| `spark-jobs/src/test/scala/**` 中 ODS 相关用例 | 新增/调整断言 |
| `analytics-server/**` | **仅在 D-5 裁定「获准改 Java 侧」时**；否则禁碰 |

**明确禁止改**（命中即停，见 §6）：
`contract-specs/**`（`warehouse-namespace.v2.json` 已冻结）、`spark-jobs/pom.xml`（D-060）、
`docs/项目实施进度与任务看板 V2.2.md`、`docs/项目完整实施指导书 V2.4.md`、`docs/contracts/**`。

**模块单写者规则**（P2-03 RULINGS D-091）：本行实施期间 **不得与其他泳道并发写 `spark-jobs/**`**；开工前须确认 P2-03 已落盘。

**迁移号纪律**：本行**无权指派任何迁移号**。若 D-3 裁定需要新表，须先取得总控分配的号位。

---

## 2. 施工项 A1…A6

> 等级标注：`E1` 编译 / `E2` 模块自动化 / `E3` 本地真实链（真实 `spark-submit` 进程，D-060）/ `E4` 集群 1,000 行 / `E5` 页面。

### A1 备份清单（依赖 D-2 粒度、D-3 载体）

**要求**：重建前置步骤产出清单，至少含 V2.4 L666 的三项字面要求：
① 备份对应 warehouse 目录；② 表数 + 行数；③ checksum。
并记录备份位置与可恢复性说明。
**验收判据**：
- E2：清单生成函数对给定命名空间产出**条目数 == 库内实际表数**（不得用常量冒充）；
- E3：对当前 `dw_*` 库真实执行一次清单采集，**表数/行数与 `SHOW TABLES` / `SELECT COUNT(*)` 实测值一致**（计数只能来自真实执行结果，参照 `PartitionEvidence.scala:9-10` 的既有纪律）。
**证据级别**：E1 + E2 + **E3 必需**（清单的价值就在于与真实一致）。

### A2 namespace 限定守卫（依赖无）

**要求**：重建目标库名前缀必须 == 当前源解析出的前缀；不等即**拒绝执行**（fail-closed）。
前缀来源**只能是**既有的 `WarehouseNamespace.fromArgs`（`--hiveDatabasePrefix`），
源身份**只能是**已下发的 `--sourceSystem`；二者由 `JobCommandBuilder.java:123/126` 同源产出，**不新增第二个前缀来源**。
守卫必须能拒绝「前缀缺省落回 `dw`」（与 D-071 的 `不设 DEFAULT` 同向）。
**验收判据**：
- E2：**负向用例**——传入不属于当前源的前缀 → 抛错且**不执行任何写操作**（须断言未触达写路径）；
- E2：**负向用例**——前缀为 `dw` 但源身份与 `dw` 的归属源不符 → 拒绝；
- E2：**正向用例**——合法源前缀 → 通过；
- `spark-jobs/src/main` 内**不得**出现写死的 `mock-mall`（V2.4 L316）。
**证据级别**：E1 + E2 必需；**E3 建议**。
**本行不可能**：E4（真库仅 1 个源，「不影响其他 namespace」不可证伪，见规格草案 §7）。

### A3 显式重建意图（不得隐式覆盖）

**要求**：重建必须是**显式动作**；未携带显式意图时，`LOAD_ODS` 走非重建路径。
**硬约束**：**不得静默 DROP**（L666 明文）。
**须裁定项**：现状「重跑即 `INSERT OVERWRITE`」是否为隐式重建？若是，本项即为 D-1/D-4 裁定的直接后果。
**验收判据**：E2 断言——无显式意图时**不**执行 `INSERT OVERWRITE`；有意图时**先过守卫再执行**（断言调用顺序）。
**证据级别**：E1 + E2 必需。

### A4 审计（依赖 D-6 动作码裁定）

**要求**：重建全流程留审计：意图、守卫结果、清单摘要、执行结果、失败原因。
**优先复用** `operation_audit_log`（D-035：不建第二张审计表；先例 `SourceAuditActions.java:10-11`）。
**硬约束**：**审计写入失败不得静默**——审计写不进去，重建应失败（否则等于无审计）。
**验收判据**：
- E2：成功路径写 1 条 `result=SUCCESS`；失败路径写 1 条 `result=FAILED`；
- E2：**负向用例**——审计写入抛错 → 重建整体失败，且**不产生新 ACTIVE**；
- E3：真库中可查到本次重建的审计行（`action`/`resource_type`/`created_at`/`result` 齐备）。
**证据级别**：E1 + E2 + **E3 建议**。

### A5 失败保旧 ACTIVE —— 负向验收（**本行的核心交付**）

**要求**：按 V2.4 L480 四段式构造：**「构造失败 → 流水线失败 → 不产生新 ACTIVE → 旧 ACTIVE 可读」**。
**验收判据（逐段，缺一不可）**：
1. **构造失败**：注入一个可复现的失败（如守卫拒绝、或质量门 BLOCKING 触发）；
2. **流水线失败**：断言流水线状态为失败阶段，且**未执行 `PUBLISH_METRIC`**；
3. **不产生新 ACTIVE**：断言 `metric_snapshot` 中 `active_flag=1` 的行**仍是失败前那一行**（快照号不变）；
   - 基线实测：当前 ACTIVE = `S20260901_43`（`id=26`，`pipeline_run_id=43`）；
   - 已有 FAILED 先例可对照：`S20260901_38`（`status=FAILED`，`active_flag` 为 `NULL`）；
4. **旧 ACTIVE 可读**：断言失败后旧 ACTIVE 仍可被读取，**且按 D-1 裁定的语义级别**（元数据级 or 端到端级）给出证据。

**证据级别**：E1 + E2 + **E3 必需**（D-060：端到端 Hive 断言只认真实 `spark-submit` 进程）。
**未决语义**：失败时是「保旧 ACTIVE 不变」还是「回滚到备份」——**待 D-4 裁定**；本项须按裁定结果分别定义证据面。
**本行不可能**：E4（非必需）；「不影响其他 source namespace」的证伪（单源库）。

### A6 守卫挂载点（依赖 D-5）

**要求**：守卫建议挂载于既有 `INIT_SCHEMA` 阶段（`SparkStageExecutor.java:44` → `sci`），
理由：该阶段已存在、已被 stage evidence 审计（`PipelineService.java:412` `updateStageEvidence`）、
且位于 `LOAD_ODS` **之前**（`PipelineService.java:206` 阶段序）。
**硬约束**：若 D-5 裁定本行属 C 类小项，则**不得改 `PipelineService.java` / `SparkStageExecutor.java`**，
守卫必须整体落在 `spark-jobs` 侧的 `INIT_SCHEMA` 作业内。
**验收判据**：E2 断言——守卫在 `LOAD_ODS` 写入之前被调用；守卫失败时 `LOAD_ODS` 零写入。
**证据级别**：E1 + E2 必需。

---

## 3. 证据级别总表

| 项 | E1 | E2 | E3 | E4 | E5 |
|---|---|---|---|---|---|
| A1 备份清单 | 必需 | 必需 | **必需** | 非必需 | N/A |
| A2 namespace 守卫 | 必需 | 必需（含负向） | 建议 | **不可能** | N/A |
| A3 显式重建意图 | 必需 | 必需 | 建议 | 非必需 | N/A |
| A4 审计 | 必需 | 必需（含负向） | 建议 | 非必需 | N/A |
| A5 失败保旧 ACTIVE | 必需 | 必需 | **必需** | 非必需 | N/A |
| A6 挂载点 | 必需 | 必需 | — | — | N/A |

**不可证伪项（须书面留档）**：V2.4 L666「不得影响其他 source namespace」——真库 `source_registry` 实测**只有 1 行**（`mock-mall`，`warehouse_prefix=dw`），
无第二个源可作对照，该条**在单源库上不可证伪**。请总控裁定取证方式。

---

## 4. 硬约束

1. 不得改 `spark-jobs/pom.xml`（D-060）。
2. 不得改 `contract-specs/**` 已冻结规则本体（`warehouse-namespace.v2.json` 为 `FROZEN-2026-09-12`）。
3. **不得指派迁移号**；需新表时先取得总控分配的号位。
4. 不得改 `WarehouseNamespace` 的规则本体（前缀/下划线/保留字/层后缀四类拒绝）；本行只做**消费方**。
5. 不得新增第二个前缀来源或第二个审计表（D-035、反熵）。
6. 不得在 `spark-jobs/src/main` 内写死 `mock-mall`（V2.4 L316）。
7. 不得静默 DROP（V2.4 L666 明文）。
8. 计数只能来自真实执行结果，禁止用输入数或常量冒充输出数（既有纪律，见 `PartitionEvidence.scala:9-10`）。
9. 审计写入失败不得静默吞掉。
10. 不得重启 8090/8091/8092，不得对真库做 DDL/DML（P2-03 RULINGS 停止条件 3）。
11. 端到端 Hive 断言只认真实 `spark-submit` 进程（D-060）。
12. `spark-jobs/**` 模块单写者：开工前确认无并发写入者（D-091）。

---

## 5. 停下报告条件（命中即停并回报）

1. **D-1 / D-2 / D-3 / D-5 未获裁定**而需开始实现 → 停。
2. 需要改 `contract-specs/**` 已冻结规则本体才能实现 → 停。
3. 需要**自行指派迁移号**才能继续 → 停（迁移号只由总控分配）。
4. 需要改 `PipelineService` / `SparkStageExecutor` 但 D-5 未裁定 → 停。
5. 需要重启 8090/8091/8092 或对真库做 DDL/DML → 停（P2-03 停止条件 3）。
6. 需要新增第二个审计表或第二个前缀来源 → 停（D-035、反熵）。
7. 需要改 `WarehouseNamespace` 规则本体 → 停（契约冻结）。
8. 发现「旧 ACTIVE 可读」在物理层**不成立**且无法在授权范围内修复 → 停并上报（这是本行最重要的发现通道）。
9. **单源库下无法证伪「不影响其他 namespace」** → 停（结构性盲区，不得用 mock 顶替，也不得声称已验证）。
10. 发现 `INSERT OVERWRITE` 路径在 `spark-jobs` 内存在**第二处**未被守卫覆盖的 ODS 写入口 → 停（避免守卫被绕过）。
11. 发现既有 `S20260901_43`（当前 ACTIVE）被本次施工波及 → 立即停并上报（真库当前唯一 ACTIVE 快照）。

---

## 6. 开工前置检查清单（逐条打勾后才可开工）

- [ ] D-1 已裁定（「旧 ACTIVE 可读」语义级别）→ 裁定号：______
- [ ] D-2 已裁定（备份清单粒度）→ 裁定号：______
- [ ] D-3 已裁定（清单载体 + **迁移号已由总控分配**）→ 裁定号：______
- [ ] D-4 已裁定（失败语义：保旧 or 回滚）→ 裁定号：______
- [ ] D-5 已裁定（是否获准改 Java 侧编排）→ 裁定号：______
- [ ] D-6 已裁定（审计动作码与资源类型命名）→ 裁定号：______
- [ ] P2-03 泳道已落盘、`spark-jobs/**` 无并发写入者
- [ ] 已按 D-091 Q17 重取 `EventOdsLoadJob.scala` / `OdsLoadSql.scala` / `warehouse/ddl/00-ods.sql` 的行号与 blob 指纹（**不得沿用本报告行号**）
- [ ] 已确认当前 ACTIVE 快照号仍为 `S20260901_43`（失败保旧验收的基线）
- [ ] 已确认「不影响其他 namespace」盲区的取证方式

---

## 7. 只读取证命令（供总控复算本单的现状依据）

```pwsh
cd D:\Develop_code\GraduationProject
pwsh -NoProfile -File docs/acceptance/p2-05-ods-rebuild-guard-20260912/raw/collect-evidence-p2-05.ps1
```

---

## 8. 本单的未实测声明

本施工单**未实施任何一项**。全部验收判据均为**待执行**。
**未实测**：E1/E2/E3/E4/E5 全部；`spark-warehouse/` 目录结构与体积；HDFS 侧 warehouse 路径；
「失败后旧 ACTIVE 在物理层可读」；ODS 分区被覆盖后旧快照能否重算；`operation_audit_log` 字段能否承载重建审计；`INIT_SCHEMA` 现有 evidence 内容。
状态：**规格与施工单草案已产出（E0 级，非验收）**。
