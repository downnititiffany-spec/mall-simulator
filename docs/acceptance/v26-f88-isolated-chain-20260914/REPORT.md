# V26-F88 隔离真链验收报告

> 泳道：**F-88 隔离真链验收**（隔离实例 3307 上的真实链路 + 四列落库取证 + 3306 零写入核对）
> 分支：`remediation/r1-boundary`　HEAD：`37561dc`（其上含写侧提交 `8853730`）
> runId：`v25f88_20260914_1624`
> 观测窗口：`2026-09-14 16:20:00` ~ `2026-09-14 23:59:59`
> 报告生成：`2026-09-14 16:33` （北京时间）
> 证据根目录：`docs/acceptance/v26-f88-isolated-chain-20260914/`

---

## 0. 一句话结论

**在隔离实例 127.0.0.1:3307 上，V19/V20 已真实落地，`data_quality_result` 的 4 个新列在本次真实链路的 4 行结果上全部非空落库；其中 1 行实证了 F-88 的核心契约「声明 `WARN` 但生效 `BLOCKING`」；3306 前后指纹 27 项逐项全等、零写入。**

**但本条链路的终态是 `FAILED / PIPELINE_QUALITY_FAILED`（按设计在 Landing 质量闸门被阻断），因此本次取证只覆盖 Landing 层 4 条规则；「F-88 已闭合」不成立**（详见 §7 分级结论）。

---

## 1. 硬约束遵守情况（铁律自查）

| 铁律 | 要求 | 本次实际 | 判定 |
|---|---|---|---|
| 1 | 3306 只读 `SELECT`，绝无写/DML/DDL | 仅两次只读指纹 + 只读旁证；前后 27 项指纹全等；3306 上无本次库/账号/连接；`performance_schema` 窗口内写语句 0 条 | ✅ 遵守 |
| 2 | 只在仓库内写文件；临时文件仅 `$env:TEMP`；不用 `git add -A` | 新增文件全部位于 `docs/acceptance/v26-f88-isolated-chain-20260914/`；`git status --short` 仅 1 条 `?? docs/acceptance/v26-f88-isolated-chain-20260914/`；提交将按显式路径 `git add` | ✅ 遵守 |
| 3 | Maven 全局串行；JDK17；参数加引号；禁止在 analytics-server 根跑无限制 `mvn test` | 仅 1 次 Maven 调用（`-pl platform-app -am package -DskipTests`），exit 0；未跑任何 `mvn test` | ✅ 遵守 |
| 4 | 起 8091 前自检 DB host/port/库名；启动后立即 SQL 确认落在 3307；收尾停净 | GATE 通过（见 §4.3）；启动后 3307 上 20 条本 run 账号连接、3306 上 0 条；收尾 8091 监听 0、PID 已退出、8090/8091/8092 全空闲 | ✅ 遵守 |
| 5 | 未实测不写结论 | 本报告 §6 列 8 项 `未取证`、§7 分级判定，未把「链路跑通」写成「F-88 已闭合」 | ✅ 遵守 |

---

## 2. 关键身份常量（全部实测读取，非引用任务书）

| 项 | 实测值 | 来源 |
|---|---|---|
| 被测 jar | `analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar` | — |
| jar size / mtime | `33169747` / `2026-09-14 16:24:59` | `raw/02-artifact-identity.txt` |
| **jar sha256** | `86B39A592FE9689D9B300BDC2B1F7A749EA6A1B1356316931DDCF91FE8C61473` | 同上 |
| E3 旧 jar sha256（对照） | `7BE717A974DE613574DE9829EC93627345E83BF87FC27837342C5E3EC9397561` | E3 README |
| V19 文件 | `V19__quality_rule_definition.sql` size `14387` sha256 `14CFB29531C4C8B7EE551B5C9FDEECD240B6C37DB827DEA3A36A3085864E02F1` | 本次现取 |
| V20 文件 | `V20__data_quality_result_rule_version.sql` size `5973` sha256 `A2052CA5EAFF6F268502EBC9F61CF39D072040133EFDE048BE349E965AD2981C` | 本次现取 |
| 隔离实例指纹 | `3307\|de8ebbea-aff4-11f1-8037-00155d5dba47\|/data/mysql-isolated/data/\|8.0.41` | 每次连接前断言 |
| 宿主实例指纹 | `3306\|85191145-1491-11f0-b4e2-60cf84d55629\|C:\ProgramData\MySQL\MySQL Server 8.0\Data/\|dahaishui\|8.0.41` | 只读 |
| 本次隔离库 | `analytics_meta_v25f88_20260914_1624` / `analytics_metric_v25f88_20260914_1624` | — |
| 本次隔离账号 | `v25f88_20260914_1624_meta` / `..._metric_pub` / `..._metric_read`（仅 SELECT） | 口令仅落 `target/f88-run/<runId>/credref.properties`（gitignore） |
| 期望 `rule_fingerprint` | `6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6` | **两路交叉核对**，见 §5.0 |
| 期望 `compat_policy_version` | `compat-v1` | 同上 |
| 目录版本 | `qrc-1` | 仅见于日志，未落库（见 §6-6） |

---

## 3. 执行步骤总表（命令 + 归档 + exit code）

| 步 | 命令（要点） | 原始输出归档 | exit |
|---|---|---|---|
| S1a | `00-fingerprint-3306-readonly.ps1 -Phase before -WinStart '2026-09-14 16:20:00' -WinEnd '2026-09-14 23:59:59'` | `raw/00-fingerprint-3306-before.txt` | **0** |
| S1b | `01-snapshot-3307.ps1` | `raw/01-snapshot-3307-before.txt` | **0** |
| S2 | `mvn.cmd -o -Dmaven.repo.local=... -f analytics-server/pom.xml -pl platform-app -am package -DskipTests` | `raw/02-mvn-package.log` + `raw/02-artifact-identity.txt` | **0**（`BUILD SUCCESS`，8.478 s） |
| S3a | `10-create-isolation.ps1 -RunId v25f88_20260914_1624` | `raw/10-create-isolation.txt` | **0** |
| S3b | `20-start-app-isolated.ps1 -RunId ...`（含启动前 GATE） | `raw/20-app-8091-console.log` | **0** |
| S3b' | `15-seed-runtime-profile.ps1 -RunId ...`（隔离准备，写 3307） | `raw/15-seed-runtime-profile.txt` | **0**（首跑有脚本缺陷，见 §8.2） |
| S3c | `25-verify-app-window.ps1 -RunId ...` | `raw/25-app-window-verification.txt` | **0** |
| S4 | `30-run-chain-isolated.ps1 -RunId ...` | `raw/30-chain-console.log` + `raw/30-chain-summary.json` + `raw/http/*.json` | **4**（终态非 SUCCESS，预期内） |
| S5 | `35-frozen-rules-evidence.ps1` | `raw/35-frozen-rules-from-app-log.txt` | **0** |
| S5 | `40-assert-four-columns.ps1 -RunId ... -PipelineRunId 1` | `raw/40-four-column-assertions.txt` | **0** |
| S5b | `70-v20-nobackfill-probe.ps1` | `raw/70-v20-nobackfill-probe.txt` | **0** |
| S6 | `50-diff-3306-zero-write.ps1` | `raw/50-fingerprint-3306-after.txt` + `raw/50-3306-zero-write-diff.txt` | **0** |
| S7 | `60-stop-app.ps1 -RunId ...` | `raw/60-stop-app-8091.txt` | **0** |

---

## 4. 分步取证正文

### 4.1 S1 前置快照

**3306 before（`raw/00-fingerprint-3306-before.txt`，exit 0，采集于 16:24:16，SQL sha256 `3777ACC66976B610412C2B2F3643377B2BADA18F9F13FE2AA0B765E7CC1DF706`）**：27 项读数，关键值：

```
dqr_rows                      467
dqr_severity_null_rows        48
dqr_fp                        7d40e747353c83a3f058a037c9599b11
dqr_v20_columns_present_on_3306   0
dqr_column_count_on_3306      14
qrd_table_present_on_3306     0            # V19 定义表不存在
meta_table_count_on_3306      22
flyway_meta_count             17
flyway_meta_versions          1,2,3,4,5,7,8,9,10,11,12,13,14,15,16,17,18   # 最高 V18
metric_snapshot_rows / fp     12 / c224e4e9d44c98f5389607f7beb9d6d2
metric_value_rows / fp        110 / a1a073114d8232c5fcb11c56157466c2
active_pointer                id=27 / S20260901_47 / version=12 / active_flag=1
v25it_databases_on_3306       0
v25f88_databases_on_3306      0
```

⇒ 独立印证了 V20 迁移注释里「本机实测 467 行，其中 48 行连 severity 都是 NULL」，且 3306 上 V20 四列不存在、V19 表不存在。

**3307 before（`raw/01-snapshot-3307-before.txt`，exit 0）**：`analytics_meta`（空库，无 `flyway_schema_history`——脚本按预期容错并继续）、E3 历史库 `analytics_meta_v25it_20260914_1358_l4e3`（V1–V18 共 17 条、`data_quality_result` 14 列 / 26 行 / V20 四列 0）、`analytics_metric*` 等。

### 4.2 S2 重建 jar 并证明 V19/V20 在工件内

`raw/02-artifact-identity.txt`：

* `MVN_EXIT=0`，`BUILD SUCCESS`，`Finished at 2026-09-14T16:24:59+08:00`；
* 新 jar `sha256=86B39A59…61473`，与 E3 实测 jar `7BE717A9…7561` **不同** ⇒ 本泳道实测的是新工件；
* `jar tf` 明确列出 `BOOT-INF/classes/db/meta/V19__quality_rule_definition.sql` 与 `BOOT-INF/classes/db/meta/V20__data_quality_result_rule_version.sql`（E3 旧 jar 内只有 V1–V18）；
* 内层 `warehouse-pipeline-0.1.0-SNAPSHOT.jar` sha256 `19BC95B1…29D2`；写侧 4 个字段/方法名在 class 常量池中核对为 True；
* **边界**：本步用 `-DskipTests`，surefire 逐模块打印 `Tests are skipped.` ⇒ **本步不构成任何测试通过**。

### 4.3 S3 建隔离库 + 起 8091（含启动前 GATE）

**建库（`raw/10-create-isolation.txt`，exit 0）**：创建 2 库 3 账号；发布账号权限 `ALTER,CREATE,DELETE,DROP,INDEX,INSERT,REFERENCES,SELECT,UPDATE`，只读账号仅 `SELECT`。

**启动前 GATE（`raw/20-app-8091-console.log` 开头）**——这是本泳道对 E3 脚本的加固点（E3 无此自检）：

```
[GATE-G1] application.yml 占位符检查：meta/publish/read 三处 env 覆盖位 + 默认值指向 3306 的处数 = 3
  meta.url           = jdbc:mysql://127.0.0.1:3307/analytics_meta_v25f88_20260914_1624?...
  metric.publish.url = jdbc:mysql://127.0.0.1:3307/analytics_metric_v25f88_20260914_1624?...
  metric.read.url    = jdbc:mysql://127.0.0.1:3307/analytics_metric_v25f88_20260914_1624?...
  landing.local-root = ...\target\f88-run\v25f88_20260914_1624\landing
  spark.warehouse    = ...\target\f88-run\v25f88_20260914_1624\spark-warehouse
  工作目录(CWD)       = ...\target\f88-run\v25f88_20260914_1624
  绑定               = 127.0.0.1:8091
[GATE] 自检通过：host=127.0.0.1 / port=3307 / 库名含本次 runId / 无 3306。
```

**Flyway 落地（同日志）**：

```
16:26:29.880 Database: jdbc:mysql://127.0.0.1:3307/analytics_meta_v25f88_20260914_1624 (MySQL 8.0)
16:26:31.443 Migrating ... to version "19 - quality rule definition"
16:26:31.484 Migrating ... to version "20 - data quality result rule version"
16:26:31.523 Successfully applied 19 migrations ... now at version v20
16:26:31.524 c.g.a.config.MetaFlywayInitializer : analytics_meta 迁移完成: 执行 19 个脚本，版本 20
16:26:33.485 Started AnalyticsApplication in 6.473 seconds
```

**S3 事后断言（`raw/25-app-window-verification.txt`，exit 0）**：

* `flyway_schema_history` 末 3 条 = `18 / 19 / 20`（`installed_rank 17/18/19`，全 `success=1`），`A2 断言 19/20 出现数 = 2`；
* `A3 max_version = 20，total_migrations = 19`；
* `data_quality_result` 列数 **18**，V20 四列位于 `ORDINAL_POSITION 15..18`，全部 `IS_NULLABLE=YES`、`COLUMN_DEFAULT=NULL`；
* V19 `quality_rule_definition` 存在，**35 行 / 35 个 rule_code / version 全为 1**；
* **连接落点（铁律 4 的关键取证）**：3307 上本 run 账号连接 **10 + 10 = 20** 条，`DB` 分别为本次两库；**3306 上本 run 前缀连接数 = 0**；
* `GET /api/v1/health` → `HTTP 200 {"code":"OK","app":"analytics-server","stage":"R1-skleton"}`；
* 8091 监听 PID `62948`。

> **隔离准备披露**：V7 播下的 `local-dev` profile 是 `DRAFT` 且 `spark_*` 全 NULL，`RuntimeProfileServiceImpl.findActive` 会 fail-closed，链路第一步就会断。故按 E3 同源做法把该行补成 `ACTIVE`（**仅写 3307**）。与 E3 不同的是：**本泳道完全不读 3306 取这些字段**（铁律 1 把 3306 的 SELECT 限定为「只为前后指纹对比」），改为从仓库内工件取真值并记录 sha256——`spark-submit.cmd` `343A088F…9F1C`、`spark-jobs-0.1.0-SNAPSHOT.jar` `71C2BCCB…C966`（与 E3 记录值一致）。落库后 `spark_submit_path` 的 `HEX()` 为 `443A5C44…636D64`，与 E3 历史正确值**逐字节相同**。

### 4.4 S4 真实链路（终态非 SUCCESS，属预期）

`raw/30-chain-summary.json` + `raw/http/*.json`（10 个文件，每步响应原样存档）：

```
healthHttp      200
loginHttp       200
ingestionHttp   200   batchId=1 batchNo=ing-20260914162819-8f7fa800
                      status=QUARANTINED recordCount=1051 quarantineCount=4 errorCount=0 fileCount=2
pipelineCreateHttp 200  pipelineRunId=1
runStatus       FAILED
runStage        FAILED
runErrorCode    PIPELINE_QUALITY_FAILED
runSnapshot     S20260901_1
elapsedSec      221
overviewHttp    200   （body data=[] —— 因未发布，无指标）
qualityHttp     200
```

**阶段账（`raw/http/03b-pipeline-run-final.json`）**：

| stageCode | status | errorCode |
|---|---|---|
| WAIT_LANDING | SUCCESS | |
| INIT_SCHEMA | SUCCESS | |
| LOAD_ODS | SUCCESS | |
| BUILD_DWD | SUCCESS | |
| BUILD_DWS | SUCCESS | |
| BUILD_ADS | SUCCESS | |
| **QUALITY_CHECK** | **FAILED** | **PIPELINE_QUALITY_FAILED** |

⇒ 6 个数据阶段全部 SUCCESS，失败发生在**其后的质量闸门**。轮询轨迹见 `raw/http/03c-pipeline-run-poll-trace.txt`。

**为什么只有 4 条规则（已核到源码，属设计而非缺陷）**：`QualityChecker.check` 实现的就是 Landing 层 4 条（`QualityChecker.java:100/116/130/147` → R1 `AMOUNT_RECONCILE`、R2 `REQUIRED_FIELD_NULL_RATE`、R3 `EVENT_ID_UNIQUE`、R4 `ENUM_WHITELIST`），日志 `quality check: rules=4`（`QualityChecker.java:152-153`）即 `results.size()`。而 ADS_STAGING_* / PUB_* / MXP_* / MP_* 这些规则来自 Spark 作业结果（`JobResultParser`）与 `MetricPublishValidator`，在**发布前质量门**才执行；本次因 Landing 阻断级规则未过，编排在 `QUALITY_CHECK` 后即终止（日志 `pipeline 1 failed: 阻断级 Landing 质量规则未通过，正式分区未发布`），因此未进入 PUBLISH / METRIC_PUBLISH 阶段，这些层的行未产生。

---

## 5. S5 六条断言（SQL + 原始结果）

归档：`raw/40-four-column-assertions.txt`（本步 exit 0）。实例前置断言 `3307|de8ebbea-…`。

### 5.0 规则指纹「两路交叉核对」（不是只硬编码常量）

**路 1 — 应用日志**（`raw/35-frozen-rules-from-app-log.txt`，exit 0）：

```
L96  PipelineService : pipeline 1: 规则冻结 ruleFingerprint=6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6 catalog=qrc-1 compatPolicy=compat-v1
L104 QualityChecker  : quality check: rules=4 corePassed=false ruleFingerprint=6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6
```

**路 2 — 库内读回**：`S5-A2b` 断言「库内 distinct `rule_fingerprint` = 期望常量」→ **PASS**。

两路相等（且等于期望常量）⇒ 交叉核对成立。

### 5.1 断言全文

**A1 本次 run 行数与逐行明细**（`SQL: SELECT id,rule_code,layer,severity,effective_severity,rule_version,compat_policy_version,LEFT(rule_fingerprint,8),LENGTH(rule_fingerprint),passed FROM … WHERE run_id=1 ORDER BY id`）：

```
| id | rule_code                | layer   | severity | effective_severity | rule_version | compat_policy_version | fp8      | fp_len | passed |
|  1 | AMOUNT_RECONCILE         | LANDING | BLOCKING | BLOCKING           |            1 | compat-v1             | 6bc272d7 |     64 |      1 |
|  2 | REQUIRED_FIELD_NULL_RATE | LANDING | BLOCKING | BLOCKING           |            1 | compat-v1             | 6bc272d7 |     64 |      1 |
|  3 | EVENT_ID_UNIQUE          | LANDING | WARN     | BLOCKING           |            1 | compat-v1             | 6bc272d7 |     64 |      0 |
|  4 | ENUM_WHITELIST           | LANDING | BLOCKING | BLOCKING           |            1 | compat-v1             | 6bc272d7 |     64 |      1 |
```

`row_count = 4`。**四列在 4/4 行上均非空**（`fp_len` 全为 64）。这就是 F-88 要的「四列真的持久化」。

**A2 `SUM(rule_fingerprint = '6bc272…3ac6')` = 行数？**

```
fp_match_rows = 4   fp_null_rows = 0   total_rows = 4      ⇒ PASS
库内 distinct rule_fingerprint = 6bc272d7324b435d2a3c7d1afabfb5f9ca18310bad03970219b0ef132f4f3ac6   ⇒ PASS（= 期望常量）
```
异常行：**无**。

**A3 `SUM(compat_policy_version = 'compat-v1')` = 行数？**

```
cp_match_rows = 4   cp_null_rows = 0   total_rows = 4      ⇒ PASS
库内 distinct compat_policy_version = compat-v1
```
异常行：**无**。

**A4 `SUM(rule_version IS NOT NULL)` = 行数？**

```
rv_notnull_rows = 4   rv_null_rows = 0   total_rows = 4      ⇒ PASS
未登记行判据（severity IS NULL AND rule_version IS NULL）行数 = 0
rule_version 分布：version=1 → 4 行（AMOUNT_RECONCILE, ENUM_WHITELIST, EVENT_ID_UNIQUE, REQUIRED_FIELD_NULL_RATE）
```
本次**未出现未登记规则码**行。⇒ 未登记分支未被本次链路覆盖（见 §6-1）。

**A5 `SUM(severity <> effective_severity)` —— 最高价值证据**

```
total_rows = 4   diff_rows = 1   sev_null_rows = 0   eff_null_rows = 0

声明档位 × 生效档位 交叉分布：
| declared | effective | passed | rows_cnt |
| BLOCKING | BLOCKING  |      1 |        3 |
| WARN     | BLOCKING  |      0 |        1 |
```

**`diff_rows = 1 > 0` ⇒ 逐行抄原值**（`raw/40-four-column-assertions.txt` S5-A5c 原文）：

```
| id | rule_code       | declared_severity | effective_severity | rule_version | compat_policy_version | fp8      | passed | check_count | error_count | error_rate | threshold | detail_head
|  3 | EVENT_ID_UNIQUE | WARN              | BLOCKING           |            1 | compat-v1             | 6bc272d7 |      0 |          91 |           1 |   0.010989 | <=0.0005  | event_id 重复率=0.010989（超批准阈值，阻断） FAILED [阈值判定未通过 ⇒ 由观察项升为阻断（§7.3.1 line 522「超过阈值阻断」）]
```

⇒ **这一行就是 F-88 的存在理由**：`severity` 保留规则**声明**档位 `WARN`，`effective_severity` 记录经 `RuleSeverity.resolve` 判定后的**生效**档位 `BLOCKING`。修复前该行两列都会是 `WARN`（E3 历史库 id=7 实证：`EVENT_ID_UNIQUE` 存的是 `WARN`）。**声明与生效两列可以不同，且本次真链路上确实不同。** 也正是这条规则让本次 run 判为 `FAILED`。

**A6 本次 run 之前的老行四列是否全 NULL**

```
other_run_rows = 0   rv_notnull = 0   eff_notnull = 0   cp_notnull = 0   fp_notnull = 0
本表 run_id 分布：run_id=1 → 4 行（16:31:58.970 ~ 16:31:58.971）—— 仅本次 run
```

⇒ 本隔离库是本次 Flyway V1→V20 一次性新建，库里**不存在**「V20 之前产出的行」，**A6 在本实例上无样本可验**。按铁律 5，此处记为 **不适用 / 未取证**，不编造结论；改由 §5.2 的专用 probe 用真 DDL 验证。

**A6 旁证（比 NULL 更强）**：

```
V20 四列在 analytics_meta / E3 历史库 上各存在几列 = 0 / 0   （total_matched=4，即四列名在两个库里一个都不存在）
E3 历史库 data_quality_result 行数 = 26
```

### 5.2 S5b V20「不回填」专用探测（真 DDL，仅在 3307）

归档 `raw/70-v20-nobackfill-probe.txt`（exit 0）。步骤：新建 probe 库 `analytics_meta_f88v20probe_20260914_1632` → `CREATE TABLE … LIKE <E3历史库>.data_quality_result`（14 列、V20 之前的真实形态）→ `INSERT … SELECT` 灌入 **26 行真实历史行** → 对 probe 库**逐字节执行仓库内 V20 文件本体**（`sha256=A2052CA5…981C`，未做任何改写）。

```
P4 列数 14 -> 18                        ⇒ PASS
P6 行数不变 26 -> 26                    ⇒ PASS
P6b 四列非空行数 = 0（行级 0）           ⇒ PASS      rv_nn=0 eff_nn=0 cp_nn=0 fp_nn=0
P5 四列无 DEFAULT（COLUMN_DEFAULT 全 NULL）⇒ PASS
```

`P7` 逐行原值显示 26 行**全部**四列为 `NULL`（含历史 `severity='WARN'` 的 3 行），行数、`severity` 原值均未被改动。

⇒ **V20「四列全可空、无 DEFAULT、不回填既有行」这条最关键约束，在真实 DDL 下成立。**

---

## 6. S6 3306 零写入核对

归档 `raw/50-3306-zero-write-diff.txt`（exit 0）+ `raw/50-fingerprint-3306-after.txt`（同 SQL，sha256 与 before 相同 `3777ACC6…F706`，窗口相同 `[2026-09-14 16:20:00 , 2026-09-14 23:59:59]`）。

**逐项 diff 结果**：

```
[汇总] 键数 before=27  after=27  仅 before=0  仅 after=0  值变化=0
[结论] 3306 指纹 27 项全等 ⇒ 零写入（PASS）
```

27 项逐项 `IDENTICAL`（原文见归档），其中与写入最相关的：

| KEY | BEFORE | AFTER |
|---|---|---|
| `metric_snapshot_fp` | `c224e4e9d44c98f5389607f7beb9d6d2` | 同 |
| `metric_value_fp` | `a1a073114d8232c5fcb11c56157466c2` | 同 |
| `dqr_rows` / `dqr_fp` | `467` / `7d40e747353c83a3f058a037c9599b11` | 同 |
| `dqr_severity_null_rows` | `48` | 同 |
| `dqr_v20_columns_present_on_3306` / `dqr_column_count_on_3306` | `0` / `14` | 同 |
| `qrd_table_present_on_3306` | `0` | 同 |
| `meta_table_count_on_3306` | `22` | 同 |
| `flyway_meta_count` / `flyway_meta_versions` | `17` / `1,2,…,18` | 同 |
| `flyway_meta_checksum_sum` | `-178375320` | 同 |
| `active_pointer` | `id=27 / S20260901_47 / version=12 / active_flag=1` | 同 |
| `dqr_rows_created_in_window` / `pipeline_run_rows_in_window` | `0` / `0` | 同 |
| `v25f88_databases_on_3306` / `v25f88_accounts_on_3306` | `0` / `0` | 同 |

**追加只读旁证**（全部 `SELECT`）：

* `P1` 3306 上 `%v25f88_20260914_1624%` 库 **0**、 `%v25%` 库 **0**、账号 **0** / **0**；
* `P2` 本 run 前缀实时连接 **0**；
* `P3` `analytics_meta/analytics_metric` 关键表 `CREATE_TIME` 全为历史值（`2026-09-07 ~ 2026-09-11`），**无新 DDL**；
* `P4` `analytics_meta.flyway_schema_history` 全量 17 条，最高仍为 `V18 - source warehouse prefix`（`installed_on 2026-09-12 15:06:05`）⇒ **V19/V20 未在 3306 上执行**；
* `P5` 窗口内触及 `analytics_meta`/`analytics_metric` 的写语句（`INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|REPLACE`）**0 条**；
* `P6` `analytics_meta` 表数 **22**（与 before 一致）。

⇒ **3306 零写入成立**。

---

## 7. S7 收尾：8091 已停净

归档 `raw/60-stop-app-8091.txt`（exit 0）：

```
停前：8091 监听 PID = 62948  (java, D:\Develop\JAVA17\bin\java.exe, start 16:26:26)
      cmdline = "...java.exe" -Dfile.encoding=UTF-8 -Xmx2g -jar ...\platform-app-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8091
      3307 上本 run 账号连接数 = 30      health = HTTP 200
停止：Stop-Process -Id 62948 -Force
停后：8091 监听条数 = 0                PID 62948 已退出
      3307 上本 run 账号连接数 = 0     health = 不可达（连接被拒绝）
端口总览：8090 = 0(空闲)  8091 = 0(空闲)  8092 = 0(空闲)
[结论] 8091 已停净 = PASS
```

剩余 java 进程均为 IDE 自带，**非本泳道启动、未误杀**：`55804`/`66224`（DataGrip JBR）、`58580`/`76836`（IntelliJ JDK1.8）。

---

## 8. 本泳道改动的代码 + 原因

### 8.1 主代码：**未改动任何一行**

`git status --short` 仅 `?? docs/acceptance/v26-f88-isolated-chain-20260914/`。未改 `QualityChecker`、未改 `RuleSeverity` 语义、**未改任何迁移文件 V19/V20**（V19 sha256 `14CFB295…02F1`、V20 `A2052CA5…981C` 为原样）。

本次**未发现写侧代码缺陷**：真实链路上四列按「声明/生效」契约正确落库（§5.1 A1/A5）。

### 8.2 脚本级改动（按裁决 R4，允许写在 `scripts/`）

全部新增于 `docs/acceptance/v26-f88-isolated-chain-20260914/scripts/`，共 13 个文件（12 个 `.ps1` + 1 个 `fingerprint-3306.sql`）。两点说明：

1. **自研脚本缺陷（已自行发现并修正，如实记账）**：`15-seed-runtime-profile.ps1` 首跑用 `-replace '\\','\\\\'` 生成 `spark_submit_path` 的 SQL 字面量。PowerShell 的 `-replace` 是**正则**替换，替换串里的 `\` 不参与转义，结果写出 **4 个**反斜杠，落库成非法的 `D:\\Develop\\…`。检查落库值时发现，改为字面替换 `$SparkSubmit.Replace('\','\\')`（现见该文件 `15-seed-runtime-profile.ps1:76` 附近的注释）后复跑；最终值 `HEX()=443A5C446576656C6F70…636D64` 与 E3 历史正确值**逐字节相同**。首跑的错误值未单独归档（被复跑覆盖）——这是本泳道证据集的一个缺口，在此显式披露。
2. **对 E3 脚本的两处刻意收紧**（非功能改动，属验收纪律）：
   - `20-start-app-isolated.ps1` 增加**启动前 GATE**（G1 占位符存在性 / G2 注入 URL 的 host+port+库名逐一断言 / G3 拒绝任何 `3306` 出现 / G3b 落点路径必须在 scratch 内），任一不通过即打印 `[GATE-FAIL]` 并 `exit 9`，**不启动 java**（E3 版无此自检）；
   - 隔离准备**不读 3306** 取 profile 真值，改从仓库内工件取并记 sha256（理由见 §4.3）。
   - 另有 `10-create-isolation.ps1` 因 E3 版 `$suffix` 按 `-` 切分、与下划线 runId 不兼容而重写。

---

## 9. `未取证` 清单（未实测，不写结论）

1. **未登记规则码分支**：本次 `severity IS NULL AND rule_version IS NULL` 行数 = **0**，`applyVersionedSeverity`（`QualityChecker.java:263-280`）里「无判定/未登记 ⇒ `rule_version` 与 `severity` 置 NULL、但仍写 `compat_policy_version`/`rule_fingerprint`」这条分支**未被本次链路覆盖**。
2. **多层多码普适性**：本次仅 4 行、仅 `layer=LANDING`、仅 4 个 rule_code、`rule_version` 全为 1。ADS_STAGING_* / PUB_* / MXP_* / MP_* 等约 31 条规则未参与本次 run ⇒ 四列在这些层/码上的落库未被本次取证。
3. **成功路径与发布路径**：链路终态 `FAILED`，未进入 PUBLISH / METRIC_PUBLISH；`GET /api/v1/metrics/overview` 返回 `data=[]`（无指标）⇒ 「发布成功后四列仍正确落库」「指标可查」**未取证**。
4. **3306 上的迁移结果**：按铁律 1，3306 只读。V19/V20 **未在 3306 执行**（`flyway_meta_count=17`、最高 V18、`dqr_column_count_on_3306=14`、`qrd_table_present_on_3306=0`）⇒ 真库迁移后的形态与写侧在真库上的行为**未取证**。
5. **全量规则映射**：35 条 V19 定义与写侧「单条规则 checksum / version 选择」未逐条核对（仅核对到本次 4 行 `rule_version=1` 与 `quality_rule_definition.version=1` 一致）。
6. **`catalog=qrc-1` 无落库列**：目录版本仅出现在应用日志（`PipelineService` 冻结行），`data_quality_result` 四列里没有承载它的列 ⇒ 无法从库内回查「本次 run 用的目录版本」。这是设计如此还是缺口，本泳道不裁。
7. **binlog 位点未做前后比对**：`performance_schema` 写语句排查（§6 P5）为空只是旁证；3306 的 binlog 位点未取 before 侧，无法做位点级零写入证明。
8. **无任何测试通过**：S2 用 `-DskipTests`，本泳道未执行任何单测/集成测试。

---

## 10. `须总控裁决` 清单

1. **验收口径**：S4 要求「跑一次真实链路」。本次链路**跑完 6 个数据阶段并在质量闸门按设计失败**（`PIPELINE_QUALITY_FAILED`）。这满足「跑通一次真实链路」的口径，还是必须要求终态 `SUCCESS`？若必须 SUCCESS，则需另造一个「Landing 规则全过」的样本重跑（会改变本次四列的证据样本）。
2. **F-88 是否可判闭合**：A5 的 1 行「声明 WARN / 生效 BLOCKING」是否足以认定写侧契约已闭合？或须补「多层 + 未登记码 + 成功路径」样本？
3. **未登记码判据落空**：判据 `severity IS NULL AND rule_version IS NULL` 本次 0 行。是否需要我专门构造未登记码场景（例如往隔离库塞一条不在 V19 定义表里的 `rule_code`）单独验收？该动作会写入 3307，需授权。
4. **遗留物处置**：probe 库 `analytics_meta_f88v20probe_20260914_1632`、本次隔离库 `analytics_meta_v25f88_20260914_1624` / `analytics_metric_v25f88_20260914_1624` 及 3 个账号，是否立即清理？（清理命令已记在 `raw/70-v20-nobackfill-probe.txt` 末尾与 `raw/10-create-isolation.txt`。）我默认**保留**以便你复核。
5. **两处对 E3 脚本的刻意收紧**（启动前 GATE、隔离准备不读 3306）是否认可？若要求与 E3 完全一致，我可回退。
6. **证据缺口披露**：`15-seed-runtime-profile.ps1` 首跑错误值被复跑覆盖、未独立留档（§8.2-1）。是否需要我在报告外补一条缺陷记账，或重跑一次留档？
7. **是否补写侧单测**：本次未发现写侧缺陷故未改主代码。是否需要我在本泳道补一条针对「未登记码 ⇒ `severity`/`rule_version` 为 NULL 而 `compat_policy_version`/`rule_fingerprint` 非空」的写侧单测（会新增测试文件，属主代码范畴，需你授权）？

---

## 11. 分级结论（四项分别判定，不合并、不外推）

| 判定项 | 结论 | 依据 |
|---|---|---|
| **提交完成** | **是（待总控提交）** | 本泳道 43 个证据/脚本文件 + 本报告已在工作树；`git status --short` 仅本泳道目录；**未 push**（按分工由总控集中推送） |
| **测试通过** | **否 / 不适用** | 本泳道**未执行任何测试**：S2 为 `-DskipTests`（surefire 打印 `Tests are skipped.`）。「构建成功」**不等于**「测试通过」 |
| **限定验收** | **是** | 就三条限定目标而言证据充分且可复核：①隔离实例 3307 上 V19/V20 真实落地（`flyway` 19 条、版本 20、四列存在）；②`data_quality_result` 四列在真实链路 4/4 行非空落库，含 1 行实证「声明 WARN / 生效 BLOCKING」；③3306 前后指纹 27 项全等、零写入 |
| **完整验收** | **否** | 未覆盖：未登记码分支（§9-1）、多层多码（§9-2）、成功/发布路径（§9-3）、3306 真库迁移（§9-4）；且链路终态为 `FAILED` |

> **显式声明**：本次「链路跑到了质量闸门并按设计阻断、四列确实落库」**不等于「F-88 已闭合」**。F-88 的完整闭合还需覆盖 §9 的未取证项，并须总控就 §10-1/§10-2 给出裁决。

---

## 12. 证据归档清单（43 个文件 + 本报告，均位于 `docs/acceptance/v26-f88-isolated-chain-20260914/`）

**scripts/（12）**：`00-fingerprint-3306-readonly.ps1`、`01-snapshot-3307.ps1`、`10-create-isolation.ps1`、`15-seed-runtime-profile.ps1`、`20-start-app-isolated.ps1`、`25-verify-app-window.ps1`、`30-run-chain-isolated.ps1`、`35-frozen-rules-evidence.ps1`、`40-assert-four-columns.ps1`、`50-diff-3306-zero-write.ps1`、`60-stop-app.ps1`、`70-v20-nobackfill-probe.ps1`、`fingerprint-3306.sql`

**raw/（20）**：`00-fingerprint-3306-before.txt`、`01-snapshot-3307-before.txt`、`02-artifact-identity.txt`、`02-mvn-package.log`、`10-create-isolation.txt`、`15-seed-runtime-profile.txt`、`20-app-8091-console.log`、`25-app-window-verification.txt`、`30-chain-console.log`、`30-chain-summary.json`、`35-frozen-rules-from-app-log.txt`、`40-console.log`、`40-four-column-assertions.txt`、`50-3306-zero-write-diff.txt`、`50-console.log`、`50-fingerprint-3306-after.txt`、`60-console.log`、`60-stop-app-8091.txt`、`70-console.log`、`70-v20-nobackfill-probe.txt`

**raw/http/（10）**：`00-health.json`、`01-login.json`、`02-ingestion-run.json`、`02b-ingestion-status.json`、`02c-ingestion-batches.json`、`03-pipeline-run-created.json`、`03b-pipeline-run-final.json`、`03c-pipeline-run-poll-trace.txt`、`04-metrics-overview.json`、`04b-metrics-quality.json`

**计数复核**：`scripts=13`、`raw=20`、`raw/http=10`，合计 **43**；加本 `REPORT.md` 共 **44**。

### 12.1 凭据卫生（已逐项核查）

* `raw/http/01-login.json` 的 `token` 已替换为 `"<REDACTED>"`；全 `raw/` 目录扫描 `eyJ[A-Za-z0-9_-]{10,}` **无残留**。
* 隔离账号口令为随机生成，**只写** `target/f88-run/v25f88_20260914_1624/credref.properties`；`git check-ignore -v` 确认其被 `.gitignore:2:target/` 忽略。证据里只有 `raw/10-create-isolation.txt` 的「口令不回显」与 `raw/20-app-8091-console.log:12` 的 `password=<redacted:24 chars>`，**无任何生成口令明文**。
* **3306 生产口令不在仓库内**：`scripts/00-fingerprint-3306-readonly.ps1:30-31` 在未提供 `$env:HOST3306_PWD` 时 `throw` 并明确「不回退任何默认口令」；`git grep "HOST3306_PWD\s*=\s*'"` 全仓库 **0 命中**。
* 需要说明的既有惯例：脚本中出现的 `MYSQL_PWD = '123456'` 全部指向**隔离实例 3307**（本机隔离实例的固定开发口令），与 E3 泳道既有做法一致（E3 车道同样硬编码 5 处）。

## 13. 复现方式

```powershell
$env:JAVA_HOME='D:\Develop\JAVA17'
$lane='docs\acceptance\v26-f88-isolated-chain-20260914\scripts'
$env:HOST3306_PWD='<3306只读口令>'     # 仅进程环境，勿写入仓库
pwsh -NoProfile -File "$lane\00-fingerprint-3306-readonly.ps1" -Phase before -WinStart '2026-09-14 16:20:00' -WinEnd '2026-09-14 23:59:59'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o "-Dmaven.repo.local=D:\maven_repository" -f analytics-server/pom.xml -pl platform-app -am package -DskipTests
pwsh -NoProfile -File "$lane\10-create-isolation.ps1" -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\20-start-app-isolated.ps1" -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\15-seed-runtime-profile.ps1"  -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\25-verify-app-window.ps1"     -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\30-run-chain-isolated.ps1"    -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\35-frozen-rules-evidence.ps1"
pwsh -NoProfile -File "$lane\40-assert-four-columns.ps1"   -RunId v25f88_20260914_1624 -PipelineRunId 1
pwsh -NoProfile -File "$lane\70-v20-nobackfill-probe.ps1"
pwsh -NoProfile -File "$lane\50-diff-3306-zero-write.ps1"  -RunId v25f88_20260914_1624
pwsh -NoProfile -File "$lane\60-stop-app.ps1"              -RunId v25f88_20260914_1624
```

`30-run-chain-isolated.ps1` 的退出码约定：`0` 终态 SUCCESS；`4` 终态非 SUCCESS（本次为 4）；`2` 登录取不到 token；`3` 取不到 pipelineRunId。**退出码 4 不代表本次验收任务失败**，只表示链路终态不是 SUCCESS。
