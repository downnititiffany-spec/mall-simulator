# P2-05 只读取证读数（RAW READINGS）

- 任务行（看板 V2.2 L224，原文逐字）：
  `P2-05 | ODS 重建守卫与失败保旧 ACTIVE | TODO | 总控/B | P2-01 | 备份清单、限定 namespace、审计、失败恢复。现状 EventOdsLoadJob.scala:121 仍直接 INSERT OVERWRITE（守卫未实现）。`
- 取证时点：**2026-09-12 20:15:42**（脚本内 `Get-Date` 实测值，见 `raw/RUN-20260912-2015.txt` 首行）
- 仓库：`D:\Develop_code\GraduationProject`；分支 `remediation/r1-boundary`；HEAD `052c6bc32f8369172f7c09eb4f25ce885d10903e`
- 证据级别：**E0（只读取证；未编译、未跑 Spark、未起服务、未写任何库表或目录）**
- 可复算脚本：`raw/collect-evidence-p2-05.ps1`（纯只读）
- 原始输出：`raw/RUN-20260912-2015.txt`（30,624 B，sha256 `0276A9EA2AD07819E27F995EB4C988FA9BAFB4D5B338F6AD71472AF3E377CF51`，431 行）

> **并发写入声明**：`EventOdsLoadJob.scala` 取证时 **mtime 2026-09-12 14:05:38，非并发写入对象**；
> 但同模块 `DwdSql.scala` / `DimSql.scala` 正被 P2-03 泳道写入（mtime 19:59:52）。
> 本报告的行号均为**取证时点读数**；实施前按 D-091 Q17 重取。

---

## 一、前提核对（任务表所述 vs 实测）

| 编号 | 任务表所述 | 实测结论 | 判定 |
|---|---|---|---|
| Q-1 | `EventOdsLoadJob.scala:121` 仍直接 `INSERT OVERWRITE` | **L121 逐字为** `spark.sql(sql) // INSERT OVERWRITE 幂等：分区内重跑内容相同，不重复累加（§10.3 第 4 条）` | **前提成立**（R-01） |
| Q-2 | 「守卫未实现」 | ODS 层**无**预检、无备份、无 namespace 复核、无审计写入、无失败回滚 | **前提成立**（R-02 / R-05 / R-09 / R-11） |
| Q-3 | 「限定 namespace」 | `WarehouseNamespace` **确为库名唯一所有者**；施工面不含第二份库名规则 | **前提成立**（R-04） |
| Q-4 | 「备份清单」 | 真库**无**任何备份/清单类表（`information_schema` 实测仅 `operation_audit_log` 命中 `audit`） | **载体缺失**（R-11） |
| Q-5 | 「审计」 | 既有审计载体存在（`operation_audit_log`），但**无任何重建类动作码** | **载体存在、动作码缺失**（R-09 / R-11） |
| Q-6 | 「失败保旧 ACTIVE」 | 快照层已有 FAILED 先例（`S20260901_38` FAILED 未成为 ACTIVE）；但 **ODS 写入本身无失败保护** | **部分成立**（详见 §三） |

---

## 二、关键读数逐项（命令 + 输出）

### R-01 `INSERT OVERWRITE` 的调用点与 blob

```
spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala
blob=fb43b45883dbc311a94fd8de7613bbe0bd802ae7  lines=166  mtime=2026-09-12 14:05:38

L121:      spark.sql(sql) // INSERT OVERWRITE 幂等：分区内重跑内容相同，不重复累加（§10.3 第 4 条）
L18 : *  5. INSERT OVERWRITE 幂等（重跑相同 inputVersion 分区内容不重复，§10.3）；
L130-132: JobResult.success(code, inputCount, outputCount, … PartitionEvidence.collect(spark, EventOdsLoadJob.outputTables(ns), args.outputSnapshotId))
```

> **精确表述（比任务表更严）**：L121 本身是 `spark.sql(sql)` 调用，`INSERT OVERWRITE` 关键字不在 L121。
> 真正的 `INSERT OVERWRITE` 语句体在 `OdsLoadSql.scala:174`（见 R-02）。
> 任务表「现状 `EventOdsLoadJob.scala:121` 仍直接 INSERT OVERWRITE」在**语义上正确、在字面上是间接引用**；本报告按可复算形式记录两处行号。

### R-02 实际语句体 —— **ODS 分区规格里没有 `snapshot_id`**

`OdsLoadSql.scala`（blob `5097cb32294aed04e61a94079e93aee9c9898065`，292 行），L171-182 逐字：

```sql
INSERT OVERWRITE TABLE ${ns.ods}.$table PARTITION (dt, hour)
SELECT
<renderSelect(odsSelect(table, sourceSystem), batchId)>,
  REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', '') AS dt,
  SUBSTR(event_time, 12, 2) AS hour
FROM landing_valid
WHERE schema_version = '1.0'
  AND <whereClause>
  AND event_id IS NOT NULL AND event_time IS NOT NULL
```

**这是 P2-05 的核心结构事实**：目标分区是 `(dt, hour)`，**不含 `snapshot_id`**。
因此一次重建会**原地覆盖**同 `dt/hour` 的既有分区数据 —— 没有暂存区、没有新旧并存、没有回退余地。

**对照：ADS 层有隔离，ODS 层没有**

| 层 | 写入形态 | 隔离 |
|---|---|---|
| ODS | `INSERT OVERWRITE TABLE ${ns.ods}.$table PARTITION (dt, hour)`（`OdsLoadSql.scala:174`） | **无** |
| ADS | `${table}__staging/snapshot_id=S/dt=D`（`AdsSql.scala:22-23` `staging()`；`AdsSql.scala:31` 暂存、`:32` 正式） | **有**（暂存 → `PUBLISH_METRIC` 发布） |

`AdsSql.scala`（blob `f817135b8a97a08e373e830e85c0f72b48fee726`）L22-23 逐字：
```scala
  /** R6-13 暂存表（分区 snapshot_id + dt，物理路径 {table}__staging/snapshot_id=S/dt=D） */
  def staging(ns: WarehouseNamespace, table: String): String = ns.table("ads", s"${table}__staging")
```
`PipelineService.java:465-466` 逐字：
```
// §14.4：ADS 先写 {table}__staging/snapshot_id=S/dt=D，正式分区由 PUBLISH_METRIC 发布。
```

### R-03 `spark-jobs/src/main` 全域 `INSERT OVERWRITE` 清点

命中 17 处（完整清单见 `raw/RUN-20260912-2015.txt` R-03 段），覆盖 ODS/DWD/DIM/DWS/ADS 全部层。
**结论：`INSERT OVERWRITE` 是本项目唯一的发布机制**；除 ADS 外，各层均直接写正式分区。

### R-04 namespace 唯一所有者确认

`WarehouseNamespace.scala`（blob `562430cbeee1313cb57e6619458ab693636aa2d0`，128 行）**确为库名唯一所有者**：
- `PrefixPattern = "^[a-z][a-z0-9_]{0,23}$"`
- `LayerSuffixes = Seq("ods","dwd","dim","dws","ads")`
- `ArgKey = "hiveDatabasePrefix"`；`fromArgs`（L126-127）；`table(layer, table)`（L56-60）
- 4 个错误码：`WAREHOUSE_PREFIX_PATTERN` / `WAREHOUSE_PREFIX_UNDERSCORE` / `WAREHOUSE_PREFIX_RESERVED` / `WAREHOUSE_PREFIX_LAYER_SUFFIX`

**前缀的下发通道**：`JobCommandBuilder.java`（blob `db0c7118e5be3fcd2f64262b7e4c56f5b26a2f95`）L121-126 逐字：
```java
        // 按源解析出的前缀显式下发：作业侧 JobRunner 启动前复核，两侧同一份规格。
        // 参数名/语义保持 P1-04 冻结的形状（`--hiveDatabasePrefix`），故 spark-jobs 侧零改动。
        cmd.add("--" + WarehouseNamespace.ARG_KEY + "=" + source.namespace().prefix());
        // A12：源编码与库名并列下发，取自同一个 RunSourceIdentity（见 ARG_SOURCE_SYSTEM 注释）。
        // 这里不做"缺值就跳过"：RunSourceIdentity 构造时就拒了空值，故参数必然存在。
        cmd.add("--" + ARG_SOURCE_SYSTEM + "=" + source.sourceCode());
```
> 即：**前缀确实按源下发**，且**库名与源编码取自同一个 `RunSourceIdentity`** —— 这是 P2-05「限定 namespace」可复用的既有机制。

### R-05 ODS 目标表分区规格（`warehouse/ddl/00-ods.sql`）

blob `65b0fb3bf7f938a8dee7868b67c8769b25dc4a03`，133 行。四张 ODS 表分区规格逐字：
```
L40 : PARTITIONED BY (dt STRING COMMENT '业务日期 yyyyMMdd', hour STRING COMMENT '小时 HH')
L72 : PARTITIONED BY (dt STRING, hour STRING)
L99 : PARTITIONED BY (dt STRING, hour STRING)
L131: PARTITIONED BY (dt STRING, hour STRING)
```
**四张表全部只有 `dt` + `hour`，无 `snapshot_id`** —— 与 R-02 互为印证。

### R-06 暂存发布路径盘点

`git grep -n -I "__staging"` 在 `analytics-server/warehouse-pipeline/src/main` 与 `spark-jobs/src/main` 的命中**全部集中于 ADS**：
`FunnelAdsJob.scala:13`、`LocalSchemaInitJob.scala:171/174-212`、`AdsSql.scala:22-23/100`、`PipelineService.java:465`。
**ODS/DWD/DIM/DWS 四层无任何暂存机制。**

### R-07 指导书 V2.4 权威条文

blob `732abb9bf7c621828ac6f7d859b9226f4b7b1f46`，720 行。

**依据 A（重建授权与四项前置）— §12.3 L666，逐字：**
> 2. **ODS v2 允许对演示数据重建。** 重建前必须备份对应 warehouse 目录、记录表数/行数/checksum，并由显式的 `INIT_SCHEMA` 审计步骤执行；不得静默 DROP，也不得影响其他 source namespace。

**依据 B（BLOCKING 负向验收）— §7.4 L480，逐字：**
> 每条 BLOCKING 规则必须有一条“构造失败→流水线失败→不产生新 ACTIVE→旧 ACTIVE 可读”的负向验收。

**依据 C（当前 ACTIVE 元信息字段）— L488：** 当前 ACTIVE meta API 须含 snapshotId、sourceInstanceId、businessDate、version、createdAt、口径版本。

**依据 D（审计范围）— §8.4 L576：**
> 审计环境激活、流水线启动/重试、质量强制处理、快照激活和归档快照读取。

**依据 E（沙箱不污染主库）— L703：** 沙箱失败不得影响 ACTIVE 数仓 / 指标快照。

**依据 F（权威顺序）— L37-45：** ① 冻结契约 → ② 指导书 → ③ 看板 → ④ 专项设计 → ⑤ 历史文档。

**依据 G（源级命名空间）— §5.1 L314 / L316：** `source_instance_id` 从 Landing manifest 传播到 ODS…页面筛选；
第一阶段可限制「一次流水线只处理一个 source instance」，但任何表、常量、配置键和页面不得写死 `mock-mall`。

### R-08 `INIT_SCHEMA` 审计步骤的现有落点

```
PipelineService.java:407  StageOutcome initOutcome = runSparkStage(run, executor, snapshot, "INIT_SCHEMA", …)
PipelineService.java:409  if (initOutcome != null && !completedStages.contains("INIT_SCHEMA")) {
PipelineService.java:412      updateStageEvidence(run.getId(), "INIT_SCHEMA", evidence);
SparkStageExecutor.java:44 "INIT_SCHEMA", List.of("sci"),
```
阶段序（`PipelineService.java:206`）：
`WAIT_LANDING → INIT_SCHEMA → LOAD_ODS → BUILD_DWD → BUILD_DWS → BUILD_ADS → QUALITY_CHECK → PUBLISH_METRIC`

> **关键**：`INIT_SCHEMA` **今天就存在且已被审计**（写入 stage evidence），它映射到 `sci` = `LocalSchemaInitJob`。
> V2.4 L666 要求「由显式的 `INIT_SCHEMA` 审计步骤执行」—— **载体已具备**，缺的是「备份清单 + 表数/行数/checksum 记录」这一步的内容。
> 且 `INIT_SCHEMA` 位于 `LOAD_ODS` **之前**，天然是重建守卫的挂载点。

### R-09 既有审计载体

`analytics-server/platform-app/src/main/resources/db/meta/V14__r8_identity_decision.sql`（blob `1b2a1da48ac567b1a2e7477456f248df413b6b22`）L35-53 建表逐字要点：
```sql
CREATE TABLE IF NOT EXISTS operation_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(64) NULL,
    user_id VARCHAR(64) NOT NULL COMMENT '操作者（取自登录会话，禁止请求头伪造）',
    role VARCHAR(32) NULL,
    action VARCHAR(64) NOT NULL COMMENT '动作码：DECISION_CREATE/SUBMIT/APPROVE/REJECT/START/COMPLETE/CANCEL/EVALUATE、USER_CREATE/USER_TOGGLE/USER_RESET_PASSWORD、AI_QUERY',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型：DECISION_TASK/SYS_USER/AI_QUERY',
    resource_id VARCHAR(64) NULL,
    before_digest VARCHAR(512) NULL COMMENT '变更前摘要（仅状态与关键字段，禁止密码/token）',
    after_digest VARCHAR(512) NULL COMMENT '变更后摘要',
    reason VARCHAR(512) NULL,
    ip VARCHAR(64) NULL,
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    …
) COMMENT='操作审计日志（R8-3 §21.4：决策全流程/用户管理/AI 查询）';
```

**既有复用先例**：`SourceAuditActions.java`（blob `833fb92607dcac9aab507774e21d43a1f93748a5`）L10-11 逐字：
> 既满足 D-035「复用同一张 `operation_audit_log`、不建第二张审计表/第二个服务」，
> 又不动被冻结的模块。这一点作为**偏差**已上报父会话裁决。

其已定义动作码：`SOURCE_CREATE` / `SOURCE_UPDATE` / `SOURCE_ACTIVATE` / `SOURCE_PAUSE`，资源类型 `SOURCE_REGISTRY`。

### R-10 迁移台账（现存 V 号）

`analytics-server/platform-app/src/main/resources/db/meta/`：`V1__platform_ingestion` … `V18__source_warehouse_prefix`（共 18 个，无 V6）。
`analytics-server/platform-app/src/main/resources/db/metric/`：`V1__metric_store`、`V2__metric_ads_materialized`、`V3__metric_ads_r7`。

### R-11 真库只读读数（**本条纠正了一处文档层假设**）

```
-- flyway_schema_history --
17  18  source warehouse prefix  1  2026-09-12 15:06:05
16  17  source dimension for checkpoint and batch  1  2026-09-12 09:12:11
15  16  source registry  1  2026-09-11 19:56:28

-- source_registry --
id  source_code  warehouse_prefix  status  profile_version
1   mock-mall    dw                ACTIVE  1.0

-- metric_snapshot（近 5 条）--
id  snapshot_id     status    active_flag  pipeline_run_id
26  S20260901_43    ACTIVE    1            43
25  S20260901_42    ARCHIVED  NULL         42
24  S20260901_41    ARCHIVED  NULL         41
23  S20260901_39    ARCHIVED  NULL         39
22  S20260901_38    FAILED    NULL         38

-- operation_audit_log 动作码分布 --
AI_QUERY 52 | DECISION_CREATE 8 | DECISION_START 8 | USER_TOGGLE 5 | DECISION_SUBMIT/APPROVE/COMPLETE/EVALUATE 各 4 | DECISION_REJECT 3

-- 备份/清单/重建类表检索（information_schema）--
analytics_meta  operation_audit_log      ← 唯一命中（因表名含 audit）
```

> **纠正**：`V18__source_warehouse_prefix.sql` 头注释自称「P2-07 本轮不重启进程 ⇒ **本轮未在真库执行**」。
> **实测 V18 已执行**（`installed_rank=17`，`success=1`，`installed_on=2026-09-12 15:06:05`），
> `source_registry.warehouse_prefix` 已是 `NOT NULL` 且值为 `dw`。
> 这是**取证时点读数对文档层声明的纠正**，本泳道只记录、不改写历史文档。

---

## 三、P2-05 面前的结构性事实

1. **ODS 是本项目唯一「无隔离写入」的层**：`PARTITION (dt, hour)` + `INSERT OVERWRITE` ⇒ 重建即原地覆盖。
   ADS 的 `__staging/snapshot_id=S` 隔离**不覆盖 ODS**，因此「失败保旧」在 ODS 层目前**无机制可依**。
2. **失败保护存在层级错配**：`metric_snapshot` 的 ACTIVE/ARCHIVED/FAILED 语义（run 38 FAILED 未成 ACTIVE）保护的是**指标快照层**；
   但 ODS/DWD/DWS 的物理分区在流水线失败时**已经被覆盖**。即：**旧 ACTIVE 快照「可读」可能指向已被覆盖的下层数据** ——
   这是 L480「旧 ACTIVE 可读」在**物理层**是否真的成立的**未实测盲区**。
3. **「限定 namespace」的机制已具备**：`WarehouseNamespace` 是唯一所有者，前缀按源下发，且库名与源编码同源（`JobCommandBuilder.java:123/126`）。
   缺的不是机制，而是**守卫**（复核 + 拒绝）。
4. **「审计」载体已具备、动作码缺失**：`operation_audit_log` 在库、在表、有成熟复用先例（P1-03 `SourceAuditActions`）；
   但**零**重建类动作码，且真库**零**行重建审计。
5. **「备份清单」载体完全缺失**：真库无任何备份/清单类表；`warehouse/**` 下亦未见备份目录约定（本泳道未逐目录清点，见 §四）。
6. **`INIT_SCHEMA` 是天然的挂载点**：它已在阶段序中、已被审计、且位于 `LOAD_ODS` 之前。

---

## 四、未实测清单（不得当作已验证）

| 项 | 状态 | 原因 |
|---|---|---|
| E1 编译 | 未实测 | 本泳道禁止 Maven |
| E2 模块自动化 | 未实测 | 本泳道禁止 Maven |
| E3 本地真实链 | 未实测 | 本泳道禁止 `spark-submit` |
| E4 集群 1,000 行 | 未实测 | 本泳道禁止集群操作 |
| E5 页面 | 未实测 | 本泳道禁止起服务 |
| `spark-warehouse/` 现有目录结构与体积 | 未实测 | 未逐目录清点（属实施轮前置） |
| HDFS 侧 warehouse 路径 | 未实测 | 当前为本地模式，集群路径未取证 |
| 「失败后旧 ACTIVE 可读」在物理层是否成立 | 未实测 | 需真实链构造失败并比对分区 |
| ODS 分区被覆盖后旧快照能否重算 | 未实测 | 需真实链 |
| `operation_audit_log` 能否承载重建审计（字段是否够用） | 未实测 | 需 DDL 层面的裁定，非本泳道 |
| `INIT_SCHEMA` 现有 evidence 内容是否含表数/行数 | 未实测 | 未读 `LocalSchemaInitJob` 全文 |

---

## 五、只读取证命令清单（总控一键复算）

```pwsh
cd D:\Develop_code\GraduationProject
pwsh -NoProfile -File docs/acceptance/p2-05-ods-rebuild-guard-20260912/raw/collect-evidence-p2-05.ps1
```

单点复算（每条独立可跑）：

| 结论 | 单条命令 |
|---|---|
| L121 是 `spark.sql(sql)`、INSERT OVERWRITE 在 `OdsLoadSql:174` | `Select-String -LiteralPath spark-jobs/src/main/scala/com/graduation/analytics/job/EventOdsLoadJob.scala -Pattern 'spark\.sql\(sql\)'` |
| ODS 分区无 snapshot_id | `(Get-Content spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala -Encoding UTF8)[173]` |
| 四张 ODS 表分区规格 | `Select-String -LiteralPath warehouse/ddl/00-ods.sql -Pattern 'PARTITIONED BY'` |
| ADS 有暂存隔离（对照） | `Select-String -LiteralPath spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala -Pattern 'staging'` |
| 指导书 L666 重建授权 | `(Get-Content 'docs/项目完整实施指导书 V2.4.md' -Encoding UTF8)[665]` |
| 指导书 L480 负向验收 | `(Get-Content 'docs/项目完整实施指导书 V2.4.md' -Encoding UTF8)[479]` |
| 真库 ACTIVE 快照与 FAILED 先例 | `mysql --host=127.0.0.1 --user=root --password=123456 --batch --raw --execute="SELECT id,snapshot_id,status,active_flag FROM analytics_metric.metric_snapshot ORDER BY id DESC LIMIT 5;"` |
| V18 已执行 | `mysql --host=127.0.0.1 --user=root --password=123456 --batch --raw --execute="SELECT version,success,installed_on FROM analytics_meta.flyway_schema_history ORDER BY installed_rank DESC LIMIT 2;"` |
| 无备份清单类表 | `mysql --host=127.0.0.1 --user=root --password=123456 --batch --raw --execute="SELECT TABLE_SCHEMA,TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('analytics_meta','analytics_metric') AND TABLE_NAME LIKE '%backup%';"` |
