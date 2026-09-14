# Q01 迁移落地记录（待裁-8 / 待裁-9 / 待裁-10）+ 一处实测更正

**泳道**：`V25-Q01`（F-88 质量门严重度口径）  
**日期**：2026-09-14  
**约束**：DB 冻结（不对 3306 执行任何 DDL/DML、不起停服务）；本文件所有库侧数字均为**只读 `SELECT` 实测**。

---

## 0. 必须最先看的一条：待裁-10 的「13 个未登记码」实测是 **5 个**，不是 13

### 0.1 实测

对 `analytics_meta.data_quality_result` 全表按 `rule_code` 分组（467 行）：

```
总行数 = 467
不同 rule_code 数 = 15
```

15 个码（附实测档位与失败行数）：

| rule_code | 实测 severity | 行数 | 其中 passed=0 | MIN(run_id) | MAX(run_id) |
|---|---|---|---|---|---|
| ADS_DWS_FUNNEL_RECONCILE | BLOCKING | 23 | 3 | 16 | 47 |
| ADS_STAGING_KEY_NOT_NULL | BLOCKING | 23 | 2 | 16 | 47 |
| ADS_STAGING_PRESENT | BLOCKING | 23 | 3 | 16 | 47 |
| **ADS_STAGING_SNAPSHOT_ISOLATION** | **BLOCKING / ERROR** | 1 / 22 | 0 / 19 | 16 | 47 |
| AMOUNT_RECONCILE | NULL / BLOCKING / INFO | 12 / 26 / 1 | 1 / 3 / 0 | 1 | 47 |
| ENUM_WHITELIST | NULL / ERROR / INFO | 12 / 26 / 1 | 0 | 1 | 47 |
| EVENT_ID_UNIQUE | NULL / BLOCKING / ERROR | 12 / 1 / 26 | 3 / 1 / 19 | 1 | 47 |
| **MXP_EXPORT_COMPLETE** | **BLOCKING** | 14 | 0 | 21 | 47 |
| **MXP_EXPORT_ROWS** | **BLOCKING** | 112 | 0 | 21 | 47 |
| **MXP_SNAPSHOT_PINNED** | **BLOCKING** | 14 | 0 | 21 | 47 |
| PUB_DQ_BLOCKING_RULES | BLOCKING | 23 | 4 | 16 | 47 |
| PUB_DQ_EVENT_ID_UNIQUE | ERROR | 20 | 14 | 16 | 47 |
| **PUB_FORMAL_PARTITION_MATCH** | **BLOCKING** | 18 | 0 | 16 | 47 |
| PUB_STAGING_READY | BLOCKING | 18 | 0 | 16 | 47 |
| REQUIRED_FIELD_NULL_RATE | NULL / ERROR / INFO | 12 / 26 / 1 | 0 | 1 | 47 |

与 `RuleSeverity.REGISTERED`（33 码）逐码求差：

```
库中有、REGISTERED 中无  = 5 个
    ADS_STAGING_SNAPSHOT_ISOLATION
    MXP_EXPORT_COMPLETE
    MXP_EXPORT_ROWS
    MXP_SNAPSHOT_PINNED
    PUB_FORMAL_PARTITION_MATCH

REGISTERED 中有、库中未出现 = 23 个
    ADS_STAGING_PRESENT, ADS_DWS_FUNNEL_RECONCILE, PUB_DQ_BLOCKING_RULES,
    PUB_STAGING_READY, PUB_POINTER_SWITCH, PUB_STAGING_PRUNE,
    MP_MANIFEST_TABLES, MP_MANIFEST_SNAPSHOT, MP_HIVE_PATH_PINNED, MP_EXPORT_FILES,
    MP_ADS_ROWS_MATCH, MP_REQUIRED_TABLES_NONEMPTY, MP_ROW_SHAPE_CONSISTENT,
    MP_OVERVIEW_CORE_NOT_NULL, MP_METRIC_DICT_VERSION, MP_VALUE_MATCH_ADS,
    MP_METRIC_VALUE_COUNT, MP_ACTIVE_SNAPSHOT, MP_ADS_ROWS_DB_MATCH,
    MP_METRIC_VALUE_DB_MATCH, MP_ADS_WRITE_MATCH, MP_OLD_ACTIVE_ARCHIVED
```

（另有 2 个码只在目录中、既不在库里也在此 23 个之内 —— 它们属 REGISTERED 之外的自有码：
`ORDER_ITEM_AMOUNT_FORMULA`、`DWD_DWS_AMOUNT_RECONCILE`，即目录 35 = 33 REGISTERED + 这 2 个。）

### 0.2 我自己的证据错在哪

`raw/q01-version-scope.md` §2.3 写的是「实测…不在 `RuleSeverity.REGISTERED`（33 码）里的码**约有 13 个**」，随后列的却是

```
ADS_DWS_FUNNEL_RECONCILE, ADS_STAGING_KEY_NOT_NULL, ADS_STAGING_PRESENT,
ADS_STAGING_SNAPSHOT_ISOLATION, AMOUNT_RECONCILE, ENUM_WHITELIST, EVENT_ID_UNIQUE,
MXP_*, PUB_DQ_*, PUB_FORMAL_PARTITION_MATCH, PUB_STAGING_READY, REQUIRED_FIELD_NULL_RATE
```

这份清单**自相矛盾**：

1. 它列的 10 个具名码里，**8 个恰恰在 `REGISTERED` 中**（开头那句自己声明「不在 REGISTERED 里」）；
2. 它用 `MXP_*` / `PUB_DQ_*` 两个**通配符**冒充枚举，不是可核对的码表；
3. 它把「**库中出现的码**」与「**目录登记的码**」两个不同的集合混成了一句。

后果：那句「实测…约有 13 个」不成立，**13 这个数字是未实测的数字**。真实答案是 **5**，且它列出的 5 个真实未登记码里只命中了 2 个（`ADS_STAGING_SNAPSHOT_ISOLATION`、`PUB_FORMAL_PARTITION_MATCH`）。

**这不是措辞问题，是「用估计冒充测量」**——正是本泳道明令禁止的写法。已在本文件更正；`q01-version-scope.md` §2.3 的正文**不改写**（留作原始记录），以本文件为准。

**原始取证**：`raw/q01-dq-result-all-codes-grouped.txt`（全表 `GROUP BY rule_code, severity` 的只读输出，26 行；旧文件名含「13」二字，已随本次更正改名，文件名内保留了更正说明）。

---

## 1. 待裁-10 条件② 红线检查：**通过，未触发停止条件**

裁决原文红线：若这些码中**任一**属于「必需字段 / 主键 / 金额」语义、却要登记为 `WARN`，**必须先停下报总控**（§7.3.1 line 522 禁止把必需/PK/金额降为 WARN）。

5 个真实未登记码在 `QualityRuleCatalog.DEFAULT` 中的档位：

| rule_code | 目录 stage | 目录 severity | severity_mode | 是否降为 WARN | 语义判定 |
|---|---|---|---|---|---|
| ADS_STAGING_SNAPSHOT_ISOLATION | ADS | **WARN** | FIXED | 是（但非红线语义） | 「历史暂存快照存在」本身；**不是** PK/必需/金额。目录 rationale 明确「设阻断会造成发布死锁；混入其他快照由 `MXP_SNAPSHOT_PINNED`(BLOCKING) 承担」 |
| PUB_FORMAL_PARTITION_MATCH | PUBLISH | BLOCKING | FIXED | 否 | 行数对账（正式分区行数 = 暂存分区行数） |
| MXP_SNAPSHOT_PINNED | PUBLISH | BLOCKING | FIXED | 否 | 快照钉住（防混入其他快照） |
| MXP_EXPORT_ROWS | PUBLISH | BLOCKING | FIXED | 否 | 行数对账（导出文件行数 = Hive 分区行数） |
| MXP_EXPORT_COMPLETE | PUBLISH | BLOCKING | FIXED | 否 | 行数一致性（8 张表导出完成且行数一致） |

**结论**：唯一登记为 WARN 的 `ADS_STAGING_SNAPSHOT_ISOLATION` **不属**必需/PK/金额语义；四个行数/快照类码（含金额相关对账）全部为 BLOCKING。**未触发停止条件，未上报阻断**。

> 附一处**实测冲突**（不影响红线，但必须记账）：`ADS_STAGING_SNAPSHOT_ISOLATION` 在库中有 **19 行 `passed=0`**，而目录档位是 WARN+FIXED ⇒ 这 19 行**不阻断**。这与库中该码的既有字面档位（`ERROR`）不同。判定：目录口径为准（WARN），理由是 line 522 只禁止**降**必需/PK/金额，本条不属该三类；且目录给出了「设阻断会造成发布死锁」的依据。**此项若总控认为应阻断，需发新 version 并显式裁决**（现有 version=1 登记不动）。

---

## 2. 交付物：两个迁移脚本（已落盘，未执行）

### 2.1 V19 — 规则定义的版本化载体

`analytics-server/platform-app/src/main/resources/db/meta/V19__quality_rule_definition.sql`（14387 B，146 行）

- **新建** `quality_rule_definition` 一张表；字段逐条对齐 §7.3.1 line 520 要求（`rule_code`/`version`/`source_scope`/`stage`/`severity`/`threshold_json`/`enabled`/`effective_from`/`effective_to`/`checksum`），另加 `severity_mode`（line 522 的「超阈值即阻断」语义载体）与 `rationale`（纯说明，**不参与** checksum）。
- 唯一键 `uk_rule_scope_version (source_scope, rule_code, version)`；按 D-10 不新增 `source_id` 列，源作用域由 `source_scope` 表达。
- **种子 35 行**，`version` 全为 1。

**种子为什么是 35 行而不是「库里出现过的 15 个码」**：本表登记的是**契约全集**（`QualityRuleCatalog.DEFAULT.definitions()`），不是历史数据子集。§7.3.1 line 524 规定未登记码一律停止发布 ⇒ 登记必须**先于**该码首次产出结果；只登记历史出现过的码，会让 `MP_*` / `MXP_*` 等尚未在本机产出结果的码在下次启用时被自己的读侧集体判为「未登记」。

**种子与 Java 目录同源（关键）**：35 行的每个字段由 `QualityRuleCatalog.DEFAULT` **直接导出**，不是手抄：

```
生成脚本：docs/acceptance/f88-dq-severity-20260912/raw/_gen_seed_rows.jsh
导出物  ：docs/acceptance/f88-dq-severity-20260912/raw/q01-catalog-seed-rows.tsv
导出时实测 fingerprint = 6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6
```

该指纹与运行期 E2 日志里 `frozen rules fingerprint` 的实测值**相同** ⇒ 导出物确实是生产口径，不是另算的一份。

**`EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE` 的登记不得被误读**：登记为 `severity=WARN` + `severity_mode=THRESHOLD_OBSERVATION` + `dupRateMax=0.0005`。**不是降格放行**——未超阈值是观察项，**超阈值即阻断**（`RuleSeverity.resolve`）。阈值 0.0005 来自设计文稿 §5.4.2，§7.3.1 line 522 规定未经新裁决不得修改，**本迁移一个阈值都没改**。

### 2.2 V20 — 结果行携带规则版本

`analytics-server/platform-app/src/main/resources/db/meta/V20__data_quality_result_rule_version.sql`

对 `data_quality_result` 新增 4 列，全部 `NULL` 可空、**无 DEFAULT**、**不回填**：

| 列 | 类型 | 语义 |
|---|---|---|
| `rule_version` | INT NULL | 本行所用规则版本（对应 V19 `version`） |
| `effective_severity` | VARCHAR(16) NULL | 本行**实际生效**档位（经 `RuleSeverity.resolve` 条件判定）。与既有 `severity`（规则**声明**档位）不是一回事：声明 WARN 超阈值时生效 BLOCKING |
| `compat_policy_version` | VARCHAR(32) NULL | 判定所用兼容策略版本（目录常量 `compat-v1`） |
| `rule_fingerprint` | CHAR(64) NULL | 本次 run 冻结的**整个规则集**指纹；单条规则指纹在 V19 `checksum` |

四列逐列的 COMMENT 均含「**本列为版本化引入前记录，无版本信息，不得解读为 WARN/PASS**」。

**为什么四列一个迁移、而 V19/V20 分两个文件**：「一次迁移一件事」针对**不同表/不同变更**——建定义表与增结果列是两件事，故两个文件；V20 内这 4 列是**同一件事**（line 520 把「冻结完整规则版本与指纹」列为一项），拆开会产出三类「只加了一部分列」的中间态，每个中间态都让结果行处于半可解释状态。

### 2.3 硬约束遵守情况（可核对）

| 约束 | 实测 |
|---|---|
| 4 列全部 NULLable、NULL 默认 | `ADD COLUMN` = 4；执行语句中 `NOT NULL` = **0**；`DEFAULT` 作为**列定义** = **0** |
| 严禁回填猜测值 | 执行语句中 `UPDATE` = 0、`INSERT` = 0、`DELETE` = 0；`V20` 仅 1 条语句（`ALTER TABLE`） |
| 只写迁移文件、不对 3306 执行 DDL | 本文所有库侧交互均为只读 `SELECT`；下面 §3 有「未执行」的正面证据 |
| 两个迁移不得合并 | 两个独立文件，号位 19 / 20 |

---

## 3. 「未在真库执行」的正面证据（只读实测）

### 3.1 四个目标列当前**不存在**

```sql
SELECT COUNT(*) FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='analytics_meta' AND TABLE_NAME='data_quality_result'
  AND COLUMN_NAME IN ('rule_version','effective_severity','compat_policy_version','rule_fingerprint');
-- 实测结果：0
```

### 3.2 Flyway 历史只到 V18

`analytics_meta.flyway_schema_history` 实测 17 行，`success` 全为 1，最高 `version = 18`（`source warehouse prefix`）。**无 19 / 20**。

### 3.3 正在跑的进程里不含本次迁移

| PID | 启动时间 | 是什么 | 是否含 V19/V20 |
|---|---|---|---|
| 62984 | 2026-09-14 13:59:37 | `platform-app-0.1.0-SNAPSHOT.jar --server.port=8091` | **否** |
| 81816 | 2026-09-14 14:02:22 | `spark-3.5.1`（他泳道 Spark） | 不适用 |
| 55804 | 2026-09-14 11:46:08 | DataGrip `RemoteJdbcServer` | 不适用 |

8091 所用的 jar 构建于 **13:58:40**（早于本次两个迁移文件的落盘时间），`jar tf` 实测其中 `db/meta/` 只到 `V18__source_warehouse_prefix.sql`，**不含 V19/V20**。

⇒ 结论：**本次两个迁移从未在任何进程中被 Flyway 应用过**，两个文件目前**只是仓库存档**。它们将在**下次平台（8091）启动**时由 `MetaFlywayInitializer`（`classpath:db/meta`）对真实 `analytics_meta` 执行——这是必须记账的前向效应。

> 注：8091 与 81816 两个进程**不是本泳道启动的**（本泳道受 DB 冻结约束，未起停任何服务）。此处只做只读登记，未对其做任何操作。

### 3.4 历史行现状（V20 落地后必须仍是 100% NULL）

```sql
SELECT COUNT(*) FROM analytics_meta.data_quality_result;                      -- 467
SELECT COUNT(*) FROM analytics_meta.data_quality_result WHERE severity IS NULL; -- 48
```

⇒ V20 落地后，`null_rule_version` / `null_effective_severity` / `null_compat_policy_version` / `null_rule_fingerprint` 应全部 = **467**。若出现非 NULL，即为有人回填了猜测值。

### 3.5 预检脚本（可让总控在应用前自行核对）

`raw/q01-v19-preflight-checks.sql`：把「V19 会失败的地方」全部改写成**只读 `SELECT`**，结果集**必须为空**才算通过。含 10 条：`severity` 取值域、`severity_mode` 取值域、`THRESHOLD_OBSERVATION` 两条不变式、`threshold_json` 的 `JSON_VALID`、`checksum` 形状（64 位小写十六进制）、唯一键重复、种子行数=35、V20 目标列可空性、以及 §3.4 的「零回填」断言。

---

## 4. 测试与门禁

新增静态门禁（**不连库、不执行任何 DDL**）：

`analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/QualityRuleVersionMigrationScriptTest.java`

6 条用例，逐条钉住：

1. `migrationVersionsAreAssignedAndUnique` — `db/meta` 号位不重复且含 19、20（重复会让 Flyway 拒绝启动）；
2. `v20AddsOnlyNullableColumnsAndNeverBackfills` — 四列必须 `NULL` 且类型后**紧跟** `COMMENT`（中间容不下 `DEFAULT x` / `NOT NULL`）；`ADD COLUMN` 恰 4 次；执行语句无 `UPDATE`/`INSERT`/`DELETE`/`REPLACE`/`MODIFY`/`DROP`；恰 1 条语句；
3. `v20DocumentsNullMeaningAndUnappliedState` — 四列逐列带「不得解读为 WARN/PASS」判读禁令；脚本必须写明未在 3306 执行；
4. `v19DeclaresTheContractTableAndUniqueKey` — line 520 要求的 11 个字段齐备；唯一键确为 `(source_scope, rule_code, version)`；不 `DROP`、不改既有表；
5. `v19SeedMatchesTheJavaCatalogExactly` — **核心用例**：V19 的 35 行种子逐字段（码/版本/作用域/阶段/档位/模式/阈值/enabled/生效期/指纹）等于 `QualityRuleCatalog.DEFAULT.definitions()`，顺序无关；`version` 全为 1；
6. `seedSeverityModesSatisfyTheirInvariants` — `THRESHOLD_OBSERVATION` 必为 `WARN` 且带阈值；档位取值域合法。

第 5 条是**防「第二份严重度所有者」的机器化守卫**：若将来有人在 SQL 里手改档位而不改 Java 目录（或反之），该用例立刻变红。

### 4.1 实测结果

| 命令 | 结果 |
|---|---|
| `mvn -o -pl platform-app -am -DskipTests test-compile` | 7/7 模块 **BUILD SUCCESS**，exit **0** |
| `mvn -o -pl platform-app -am -Dtest=QualityRuleVersionMigrationScriptTest test` | `Tests run: 6, Failures: 0, Errors: 0`，exit **0**，surefire 报告已落盘 |
| `mvn -o -pl platform-app -am -Dtest=<本泳道 6 个门禁类> test` | `Tests run: 32, Failures: 0, Errors: 0`，exit **0** |

6 个门禁类一并回归（确认本次新增未破坏既有迁移门禁）：

```
PlatformMallBoundarySourcePolicyTest          3 / 0F / 0E
RuleSeverityPathConsistencyTest               4 / 0F / 0E
QualityRuleVersionMigrationScriptTest         6 / 0F / 0E   ← 本次新增
FileCheckpointSourceMigrationScriptTest       7 / 0F / 0E
SourceRegistryMigrationScriptTest             6 / 0F / 0E
SourceWarehousePrefixMigrationScriptTest      6 / 0F / 0E
```

日志：`raw/e8-migration-gate-test.log`、`raw/e9-migration-and-guard-regression.log`

> 首轮实测踩到一个**真实的门禁自伤**：V20 的注释里刻意写了「不设 `DEFAULT`」作为说明，而门禁用「全文不得出现 `DEFAULT`」去钉，把说明本身判成违规。修法**不是**放宽成「注释里可以出现」——那样会连真违规一起放过；而是改成钉**列定义结构**（类型后紧跟 `COMMENT`，中间容不下任何子句）。首轮 1F → 修正后 6/6 green，证据在 `e8-migration-gate-test.log`。

---

## 5. 未做 / 未测（显式声明）

**未做**

1. V19/V20 **未在真库执行**（DB 冻结）；两脚本仅为仓库存档，待下次 8091 启动由 Flyway 应用。
2. **写侧未接入版本化**：`data_quality_result` 的写入路径尚未填这 4 列。⇒ **不得声称「版本化已闭合」**。V20 落地后、写侧接入前新产出的行同样是 NULL，那些 NULL 的含义是「写侧尚未接入」，与历史 NULL（「版本化引入前记录」）**含义不同**。
3. `quality_rule_definition` **尚无 Java 读取方**：目录仍是 `QualityRuleCatalog.DEFINITIONS` 硬编码。`QualityRuleCatalog` 类注已写明「将来把 `definitions()` 换成 mapper 查询即可，调用方无需改动」（过渡兼容，**带移除条件**才不构成第二所有者）。
4. V19 的 `rationale` 列留 NULL（依据的唯一所有者在 Java 目录与 `RuleSeverity.rationale`，不在本表再存一份）。

**未测**

5. **两脚本的 SQL 语法与执行结果均未在 MySQL 上验证过**（未执行任何 DDL）。静态检查（门禁 6 条 + 预检脚本 10 条）**不能替代**执行验证。`EXPLAIN` 语法校验已写入预检脚本 Q0，但**本轮未运行**。
6. V19 的 `INSERT IGNORE` 在真库上的幂等性未测。
7. `quality_rule_definition` 与 `QualityRuleCatalog` 的**运行期**对账未测（只有静态对账，即门禁第 5 条）。
8. V20 落地后 467 行的「零回填」未测（列还不存在，见 §3.1）。
9. `severity_mode` 列宽 `VARCHAR(32)` 是否足够容纳将来新增的模式名未测。
10. DataGrip（PID 55804）与 8091（PID 62984）是否会与 Flyway 迁移互相干扰未测。

---

## 6. 需要总控裁决的两点

1. **§0 的计数更正**：待裁-10 批准的「13 个未登记码」实测为 **5 个**。我按「契约全集 35 行」落地了 V19 种子（理由见 §2.1），**而不是**按「库里出现过的码」。若总控认为种子应改为「仅登记库中出现过的 15 码」，需明确指示——但那会让 `MP_*`/`MXP_*` 等码在下次启用时被自己的读侧判为未登记，我不建议。
2. **§1 附注的 `ADS_STAGING_SNAPSHOT_ISOLATION`**：库中该码有 19 行 `passed=0`，目录档位 WARN+FIXED ⇒ 不阻断。若总控认为该码应阻断（其库中既有字面档位是 `ERROR`），需**发新 version 并显式裁决**；现有 version=1 登记不动。
