# V25-E3 隔离真链验收（2026-09-14）

> 目标（指导书 V2.5 §1.1 / §6.5 / §9.4，本项目最高优先级）：**在完全隔离的 MySQL 实例上跑通「应用 + 真链」，且对宿主正式实例 3306 零接触、零写入，并留下可核查证据。**
> 本目录是泳道 **L4** 的自包含交付物。所有结论都能追到 `文件:行` 或 `raw/` 下的原始日志。

---

## 0. 一句话结论

**达成**：在 `127.0.0.1:3307`（WSL2 内独立 MySQL 8.0.41 实例，`@@server_uuid=de8ebbea-aff4-11f1-8037-00155d5dba47`）上，用**仅通过启动期环境变量覆盖**的方式，把 `platform-app-0.1.0-SNAPSHOT.jar` 指向 `testRunId=v25it-20260914-1358-l4e3` 命名的两套库，Flyway 在该隔离库建表到 meta **v18** / metric **v3**，随后跑通「采集 → 流水线（8 阶段、10 个真 Spark 作业）→ 指标读取」三步链路并拿到非空指标；全程对宿主正式实例 `127.0.0.1:3306` **零写入**（三路独立证据，见 §7）。

**同时必须说清的边界**：这不等于「完整验收」（见 §2），且本轮**未运行任何自动化测试**；F-88 的闭合**未被本泳道证实**（§9）。

---

## 1. 四级状态（各自独立判定，互不代替）

| 级别 | 判定 | 依据（可核查） |
|---|---|---|
| **① 提交完成** | ✅ **是** | 本目录自包含：`scripts/`（8 个 `.ps1` + 1 个 `.sql`）+ `raw/`（24 个文件）+ `raw/http/`（19 个 HTTP 记录）+ 本 README。README 中每条结论均给出 `文件:行` 或 `raw/` 路径。命令与退出码清单见 `raw/50-commands-and-exit-codes.txt`。 |
| **② 测试通过** | ❌ **否（未运行）** | 本轮**没有执行任何自动化测试**（`mvn test` / `verify` / IT 均未跑），因此不存在可引用的测试退出码或测试报告。**「构建成功 / 打包成功」≠「测试通过」**：被实测 jar 是 `-DskipTests` 打出来的（构建时间 13:58:40）。另：`platform-app/src/test/java/.../QualityRuleVersionMi*.java`（mtime 14:05:32）晚于被实测工件，属**其它泳道新增**，本泳道既未运行也未据其下结论。 |
| **③ 限定验收** | ✅ **在明确限定内达成** | 限定集 = ｛隔离实例 3307、被实测工件 SHA256 `7BE717A9…7561`、仅启动期 env 覆盖、3 步链路、单机 local[2] Spark｝。在该限定集内：链路端到端 SUCCESS（`raw/22-chain2-summary.json`）、指标非空（`raw/http/43-metrics-overview.json`）、3306 零写入（`raw/33-zero-write-comparison.txt` + `raw/35-3306-window-absence.txt`）。**限定外均未验收**：未在 8090 正式端口部署、未接 HDFS/HMS/集群、未验证并发/幂等/回滚、未跑性能。 |
| **④ 完整验收** | ❌ **否** | 缺项至少 5 条：<br>① 正式环境（8090 + 正式库）部署未验证；<br>② F-88 未被证实闭合（写侧无实现，§9）；<br>③ V19/V20 迁移晚于被实测工件，**未纳入**本轮实测（`raw/40-out-of-repo-audit.txt:10`）；<br>④ 自动化测试未运行（②）；<br>⑤ 「一次成功」不构成稳定性结论：本轮只有 2 次链路，其中 1 次被质量门禁合法阻断（§6）。 |

> **口径纪律**：「探活成功」「一次 HTTP 200」都不等于任何级别的验收。本 README 只在给出对应原始证据时才使用「达成/通过/一致」这类词，其余一律写「未取证 / 未运行 / 推断（并标注推断）」。

---

## 2. 两个实例的身份（指纹必须带端口/uuid，不能只看 hostname）

| | 隔离实例（**唯一合法写入目标**） | 宿主正式实例（**禁写**） |
|---|---|---|
| 地址 | `127.0.0.1:3307` | `127.0.0.1:3306` |
| `@@server_uuid` | `de8ebbea-aff4-11f1-8037-00155d5dba47` | `85191145-1491-11f0-b4e2-60cf84d55629` |
| `@@hostname` | `dahaishui` | `dahaishui`（**相同**！） |
| `@@datadir` | `/data/mysql-isolated/data/` | `C:\ProgramData\MySQL\MySQL Server 8.0\Data\` |
| 出处 | `raw/01-create-isolation.txt:1`、`raw/02-isolated-account-connectivity.txt:4` | `raw/35-3306-window-absence.txt:2` |

> ⚠️ **两个实例的 `@@hostname` 都是 `dahaishui`，仅凭 hostname 无法区分。** 因此本目录所有指纹都强制包含 **端口 + uuid + datadir**；凡引用「隔离/正式」的结论都以这三元组为准。这是本轮刻意的取证要求。

隔离库与账号（仅在 3307 上创建，`raw/01-create-isolation.txt`）：

```
metaDb   : analytics_meta_v25it_20260914_1358_l4e3
metricDb : analytics_metric_v25it_20260914_1358_l4e3
用户      : v25it_1358_l4e3_meta        → metaDb.*   (SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP)
           v25it_1358_l4e3_metric_pub  → metricDb.* (同上)
           v25it_1358_l4e3_metric_read → metricDb.* (仅 SELECT)
口令      : 每轮随机生成，只写在仓库内且被 gitignore 的 target/e3-run/<runId>/credref.properties，以子进程环境变量注入
```

最小权限实测（`raw/02-isolated-account-connectivity.txt`）：`v25it_1358_l4e3_meta` 连上后 `cur_db` 是自己的库，跨库探测被拒 `ERROR 1142 (42000) SELECT command denied ... table 'metric_snapshot'`；`SELECT COUNT(*) FROM mysql.user WHERE user LIKE 'v25it%'` 在 **3306 上 = 0**（`raw/35-3306-window-absence.txt` 末段 `v25it_users_3306 = 0`）。

---

## 3. 怎么做到「指向隔离实例」——只动启动参数，不动提交配置

**机制**：`application.yml` 里的数据源 URL 本身就写成 `${PLATFORM_META_URL:jdbc:mysql://127.0.0.1:3306/analytics_meta...}` 形式（`analytics-server/platform-app/src/main/resources/application.yml:7`、`:15`、`:19`），即 **官方提供的 env 覆盖位**；配置类用 `Environment` 取值（`PlatformDataSources.java:46`、`:61`、`:85`），Spring Boot relaxed binding 使 `PLATFORM_META_URL` 等环境变量优先生效。

**做法**：`scripts/10-start-app-isolated.ps1:48-60` 在**子进程环境变量**里注入全部 3307 地址与账号口令，然后启动：

```
java -Dfile.encoding=UTF-8 -Xmx2g -jar analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar \
     --server.address=127.0.0.1 --server.port=8091
```

**生效证据**（`raw/10-app-8091-console.log:1-14`，口令已打码）：

```
meta.url           = jdbc:mysql://127.0.0.1:3307/analytics_meta_v25it_20260914_1358_l4e3?...
meta.user          = v25it_1358_l4e3_meta  password=<redacted:24 chars>
metric.publish.url = jdbc:mysql://127.0.0.1:3307/analytics_metric_v25it_20260914_1358_l4e3?...
metric.read.url    = jdbc:mysql://127.0.0.1:3307/analytics_metric_v25it_20260914_1358_l4e3?...
landing.local-root = D:\Develop_code\GraduationProject\target\e3-run\v25it-20260914-1358-l4e3\landing
spark.warehouse    = ...\target\e3-run\v25it-20260914-1358-l4e3\spark-warehouse
绑定               = 127.0.0.1:8091
```

同日志 `:2`/`:4`/`:6`（脚本自报生效 URL）、`:39`/`:69`/`:90`（`meta-ds` / `metric-publish-ds` / `metric-read-ds` 三个连接池各自 `Added connection`）、`:44` 与 `:71`（Flyway 报告 `Database: jdbc:mysql://127.0.0.1:3307/...`）显示真正连的都是 **3307**。`:84` `Started AnalyticsApplication in 7.193 seconds`（PID 62984，绑定 `127.0.0.1:8091`）。

**未改动任何已提交文件**（`raw/40-out-of-repo-audit.txt` 第 4.2 段，`git diff --stat` 全为空）：
`application.yml`、`db/meta/**`（V1–V18）、`db/metric/**`、`warehouse-pipeline/**`、`platform-common/**`、`spark-jobs/**`、`mall-simulator/**`、`contract-specs/**`。

**工作目录**：应用 CWD = `D:\Develop_code\GraduationProject\target\e3-run\v25it-20260914-1358-l4e3`（仓内、gitignore），Spark warehouse / Derby metastore / landing 全在该目录下（`raw/10-app-8091-console.log:8-12`）。

---

## 4. 隔离库的 Flyway 结果（建表证据）

| 库 | 迁移集合 | 应用脚本数 | 终态版本 | 表数 | 出处 |
|---|---|---|---|---|---|
| `analytics_meta_v25it_20260914_1358_l4e3` | `classpath:db/meta` | 17 | **v18** | 21 | `raw/03-isolated-flyway-and-seeds.txt`、`raw/10-app-8091-console.log:66-67`（`Successfully applied 17 migrations` + `analytics_meta 迁移完成: 执行 17 个脚本，版本 18`） |
| `analytics_metric_v25it_20260914_1358_l4e3` | `classpath:db/metric` | 3 | **v3** | 11 | `raw/10-app-8091-console.log:79-80`（`Successfully applied 3 migrations` + `analytics_metric 迁移完成: 执行 3 个脚本，当前版本 3`） |

- 迁移执行器：`MetaFlywayInitializer.java:23-31`（注入的 `metaDataSource` 即 env 覆盖后的 3307 连接）与 `MetricFlywayInitializer.java:38-46`；Spring Boot 自带 flyway 关闭（`application.yml:43-44`）。
- 迁移清单逐条（`raw/03-isolated-flyway-and-seeds.txt:1-27`）：meta `installed_rank 1..17` 对应版本号 **1,2,3,4,5,7,8,9,10,11,12,13,14,15,16,17,18**（**无 V6**，`success=1` 全部成功）；metric `1,2,3`。
- 表清单实况（**21 张 meta 表 / 11 张 metric 表**，含 `flyway_schema_history`）见 **`raw/06-isolated-table-inventory.txt`**（只读 `information_schema`，逐表列出）。其中**没有 `quality_rule_definition`**——这正是 F-88 的 schema 缺口（§9）。
- ⚠️ **取证脚手架自身的缺陷（如实披露）**：`raw/03-isolated-flyway-and-seeds.txt` 里另有 3 条只读查询**失败**——`runtime_profile` 用了不存在的列名 `profile_type`（`ERROR 1054`）、`pipeline_definition` 与 `metric_definition` 打在了不存在的库上（`ERROR 1146`，`metric_definition` 实际在 meta 库）。它们是**本泳道导出脚本的列名/库名写错**，只读、无任何副作用；正确数据分别在 `raw/04-mirror-runtime-profile-from-3306.txt` 与 `raw/22b-isolated-meta-evidence-run2.txt:170-177`。
- 隔离库里的 `runtime_profile` 是本泳道从 3306 **只读**读出后、在 3307 上镜像重建的（`raw/04-mirror-runtime-profile-from-3306.txt`、SQL 见 `raw/04b-mirror-runtime-profile.sql`）：`id=1, profile_code=local-dev, status=ACTIVE, spark_master=local[2], spark_submit_path=D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd, spark_job_jar_uri=D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar, source_id=1, version=3`。若不做这一步，`RuntimeProfileServiceImpl.findActive()` 会 fail-closed 直接拒绝（无 ACTIVE 档案）。

---

## 5. 被实测工件（不可变引用）

| 工件 | 大小 | mtime | SHA256 |
|---|---|---|---|
| `analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar` | 33159377 | 2026-09-14 13:58:40 | `7BE717A974DE613574DE9829EC93627345E83BF87FC27837342C5E3EC9397561` |
| `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar` | 296359 | 2026-09-12 21:58:08 | `71C2BCCB54066996E7197DEA004E88F16B4DF912481CB723B4D84CC33CE0C966` |

出处 `raw/40-out-of-repo-audit.txt:3-10`（含 jar 内 `db/meta` 18 个脚本清单）。
**jar 内的迁移只到 V18**；源码里 mtime 14:02:53 / 14:03:11 的 `V19__quality_rule_definition.sql`、`V20__data_quality_result_rule_version.sql` **不在**该 jar 内 —— 本轮实测与它们无关。

---

## 6. 三步链路（两条，都留了 HTTP 状态 + 脱敏响应体）

HTTP 原始记录：`raw/http/*.json`（每条含 `step / status / url / body`，token 已脱敏）；汇总：`raw/20-chain-summary.json`、`raw/22-chain2-summary.json`。

### 6.1 链路 1：`runId=1` → 被质量门禁**合法**阻断（FAILED）

| 步 | 调用 | HTTP | 关键结果 |
|---|---|---|---|
| 1 | `POST /api/v1/auth/login` | 200 | 取到 token（`raw/http/00-login.json`） |
| 2 | `POST /api/v1/ingestion/runs`（投 2 个文件） | 200 | `batchId=1` `ing-20260914140158-5f085e03`，**QUARANTINED**，records=1051，quarantine=4（`raw/20-chain-summary.json`） |
| 3 | `POST /api/v1/pipeline-runs` → 轮询 `GET /api/v1/pipeline-runs/1` | 200 | 271 s 后终态 **FAILED**，`errorCode=PIPELINE_QUALITY_FAILED`，`targetSnapshotId=S20260901_1` |
| 4 | `GET /api/v1/metrics/overview` | 200 | `data: []`（未发布 ⇒ 无指标，符合预期，`raw/http/03-metrics-overview.json`） |
| 5 | `GET /api/v1/metrics/quality?limit=20` | 200 | 返回质量行（`raw/http/03b-metrics-quality.json`） |

阶段明细（`raw/21-isolated-meta-evidence.txt`）：WAIT_LANDING → INIT_SCHEMA → LOAD_ODS → BUILD_DWD → BUILD_DWS → BUILD_ADS **全部 SUCCESS（真 Spark 作业）**，第 7 阶段 `QUALITY_CHECK FAILED / PIPELINE_QUALITY_FAILED`，未进入 PUBLISH。

**根因已证，且是预期行为**：`EVENT_ID_UNIQUE` 重复率 `0.010989 = 1/91 > 阈值 0.0005`，`passed=0` ⇒ 观察项被升为 BLOCKING 并停止发布。
重复是**数据事实**，已做文件级取证（`raw/07-dup-source-proof.txt`，纯只读）：

```
源文件  landing/events/golden-r73-clean-20260910.jsonl  55 行 / 18430 B  sha256=2351BCC3…B11C
解析    JSON 成功 54/55；event_id 非空 53/54；distinct event_id = 52
重复    golden-evt-008 出现 2 次（trace_id=golden-trace-008 与 golden-trace-037，同 user_id=1、同 event_time=2026-09-01 10:01:00）
入选    accepted/1/….jsonl   51 行 / 17419 B（与日志 collected=51 acceptedBytes=17419 逐字节吻合）
隔离    quarantine/1/….jsonl  4 行 / 1011 B（非法枚举 1、schema_version 2.0 1、JSON 无法解析 1、缺 event_id 1）
```
证据链：`raw/10-app-8091-console.log:93`（`collected=51 quarantined=4 acceptedBytes=17419 offset 0→18430`）→ `:103`（`rules=4 corePassed=false ruleFingerprint=6bc272d7…3ac6`）→ `:104`（`pipeline 1 failed: 阻断级 Landing 质量规则未通过，正式分区未发布`）→ `raw/22b-isolated-meta-evidence-run2.txt:82-114`（id=3 `error_rate=0.010989 … FAILED`）。
⇒ **这不计入缺陷**，而是质量门禁「宁可不出数、也不出错数」的正确拦截，同时它本身就是 §9 观察 3 的实测样本。**未取证**：`check_count=91` 的精确构成（两个投递文件中哪些事件被该规则纳入）——dqc 侧输入明细未导出，故只声明「91 条纳入检查的事件中有 1 条重复」。

### 6.2 链路 2：`runId=2` → **端到端 SUCCESS**

为避免复用同一文件路径（采集是**检查点/偏移量**驱动，`file_checkpoint` 以绝对路径为键，已消费路径不会再读），把同一份 `gen-s3b-1000-20260911.jsonl` 复制成**新路径** `gen-s3b-1000-20260911-r2.jsonl` 投递，并先证明二者字节一致：

```
源文件   SHA256 = 5397907A6016936805FFAB694E9799B25154251849DA7EC933C18064BEBB0257
投递副本 SHA256 = 5397907A6016936805FFAB694E9799B25154251849DA7EC933C18064BEBB0257   （deliveredFileSameAsSource=true）
```
出处 `raw/22-chain2-summary.json`（`deliveredFileSha256` / `deliveredFileSameAsSource`）。

| 步 | 调用 | HTTP | 关键结果 |
|---|---|---|---|
| 1 | `POST /api/v1/auth/login` | 200 | `raw/http/40-login.json` |
| 2 | `POST /api/v1/ingestion/runs` | 200 | `batchId=2` `ing-20260914140837-a3484b12`，**SUCCESS**，records=1000，quarantine=**0**，files=1，`noNewData=false`，acceptedDir=…`\landing\accepted\2` |
| 3 | `POST /api/v1/pipeline-runs` → 轮询 | 200 | 291 s 后终态 **SUCCESS**，`currentStage=SUCCESS`，`targetSnapshotId=S20260901_2`，`errorCode=null` |
| 4 | `GET /api/v1/metrics/overview` | 200 | **10 个指标非空**，响应体 1394 B（`raw/22-chain2-console.log:71`；记录文件 `raw/http/43-metrics-overview.json` 1730 B） |
| 5 | `GET /api/v1/metrics/quality?limit=20` | 200 | 响应体 8725 B（`raw/22-chain2-console.log:72`；记录文件 `raw/http/43b-metrics-quality.json` 10353 B） |

8 个阶段全部 SUCCESS，`raw/22b-isolated-meta-evidence-run2.txt:12-33`：

```
WAIT_LANDING 1000 → INIT_SCHEMA 37 → LOAD_ODS 1000 → BUILD_DWD 159 → BUILD_DWS 10
→ BUILD_ADS 30 → QUALITY_CHECK 6 → PUBLISH_METRIC 60
```

**10 个真 Spark 作业**（run 2 = `spark_job_run.id` 8–17，共 10 行；run 1 = id 1–7 共 7 行，合计 17 行。出处 `raw/22b-…run2.txt:34-57`，`submitter_type=**local-process**`，全部 SUCCESS）：

```
run2: sci(INIT_SCHEMA 0→37) odl(LOAD_ODS 1000→1000) bdw(13→13) dim(18→8) tdw(281→138)
      usw(13→10) fna(1→30) dqc(QUALITY_CHECK 30→6, rejected 8) pub(30→30) mxp(30→30)
run1: sci odl(1051→1051) bdw(28→27, rejected 1) dim tdw usw fna   ← 无 dqc/pub/mxp（质量门禁前终止）
```

提交参数实证（`raw/22b-…run2.txt:58-81` 的 `arguments_json` 原文）：
`["D:\\Develop\\spark-3.5.1-bin-hadoop3\\bin\\spark-submit.cmd","--master","local[2]","--conf","spark.sql.warehouse.dir=file:///D:/Develop_code/GraduationProject/target/e3-run/v25it-20260914-1358-l4e3/spark-warehouse", …]`
⇒ Spark 提交器是**真进程** `LocalProcessSparkSubmitter`（非 mock），warehouse 落在本泳道仓内运行目录。作业日志：`target/e3-run/v25it-20260914-1358-l4e3/landing/logs/pipeline-2-*.log`。

指标落库并回读（`raw/22b-…run2.txt:178-210`）：

```
metric_snapshot : S20260901_2 | ACTIVE | source=spark-ads | pipeline_run_id=2 | 2026-09-14 14:13:27
metric_value    : 10 行 / 10 个 metric_code（pv=5, uv=5, dau=10, paid_order_cnt=1, gmv=88.16,
                  net_sale=88.16, avg_order_value=88.16, refund_rate=0(v2), full_refund_rate=0, buy_rate=0.2）
```
旁证（应用侧同一动作的日志）：`raw/10-app-8091-console.log:129` `metric publish: 快照 S20260901_2 发布成功（ADS 30 行，指标值 10 条，旧 ACTIVE=…）`。
⇒ 指标是**写到隔离 metric 库再从隔离库读出来**的，「写—读」闭环在同一隔离实例内成立。

### 6.3 顺带探测到的两件事（**不属于任务要求的三步链路，如实列出**）

1. `GET /api/v1/metrics/summary` 返回 **403**（`raw/http/43c-metrics-summary.json`）。经全仓检索，**源码里根本不存在该端点**（`/summary` 在 `MetricController.java` 中无映射，该文件只有 `/overview` `:36`、`/snapshots` `:52`、`/health` `:59`、`/quality` `:65`）。403 而非 404 的原因是鉴权拦截器对「无权限注解的 handler（含未映射路径的兜底 handler）」一律 fail-closed 拒绝（`AuthInterceptor.java:88-94`）。**这是本泳道探错了端点**，不是产品缺陷；但「未映射路径表现为 403 而非 404」是可记录的行为（仅探测 1 个路径，**未取证**是否为普遍行为）。
2. `GET /api/v1/metrics/quality` 需要 `OPS_LOG_VIEW`（`MetricController.java:66`），admin 具备该权限，故 200。

---

## 7. 宿主正式实例 3306 的零写入结论（三路独立证据）

### 7.1 连接面：应用从未连过 3306
`raw/30-app-connections-during-window.txt`（14:15:08 采样，应用 PID 62984）：

```
指向 3306 的已建立连接数 = 0
指向 3307 的已建立连接数 = 30
进程命令行 = "D:\Develop\JAVA17\bin\java.exe" ... -jar ...platform-app-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8091
应用控制台日志中 3306 出现次数 = 1
  L14:   任何 3306 出现即为门禁失败：URL 中只有 3307
```
> 那唯一 1 次出现是启动脚本自己打印的**提示语**（`raw/10-app-8091-console.log:14`），不是连接目标。此点已逐字列出，避免被误读。

### 7.2 内容面：3306 关键表 before/after 逐字节一致
`raw/33-zero-write-comparison.txt`：同一份只读 SQL（`scripts/fingerprint-3306.sql`）在窗口前后各跑一次，**不一致项数 = 0**。

| KEY | BEFORE | AFTER |
|---|---|---|
| `metric_snapshot_rows` | 12 | 12 |
| `metric_value_rows` | 110 | 110 |
| `metric_snapshot_fp` | `c224e4e9d44c98f5389607f7beb9d6d2` | 同左 |
| `metric_value_fp` | `a1a073114d8232c5fcb11c56157466c2` | 同左 |
| `active_pointer` | `id=27 / S20260901_47 / version=12 / active_flag=1` | 同左 |
| `flyway_3306_count` | 3 | 3 |
| `flyway_3306_latest` | `3 / 3@2026-09-10 20:04:15` | 同左 |
| `runtime_profile_v25it_rows` | 0 | 0 |

指纹算法（本泳道自定义并显式声明，SQL 见 `scripts/fingerprint-3306.sql:1-7`）：
行指纹 `h = MD5(CONCAT_WS('|', IFNULL(CAST(col AS CHAR),'\N') … 全列按 ORDINAL_POSITION))`；表指纹 `fp = MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR ''))`（按行指纹排序 ⇒ **行序无关**，集合语义）；`group_concat_max_len = 1073741824` 防止静默截断。
> ⚠️ **跨泳道不可比**：另一泳道曾记录指纹 `452b7223a4a2b9dd0df7f3c883cdb74b`（见 `docs/acceptance/v25-s01-it-safety-20260914/README.md`），但**没有留下任何 SQL 或算法说明**，无法复现 ⇒ 与本轮指纹**不可比**，本 README 不据此宣称互证。**未取证。**
> 上文 `metric_snapshot_rows=12 / metric_value_rows=110 / ACTIVE id=27 S20260901_47 v12` 与该泳道 README 记录的计数**一致**，这是**弱旁证**（只说明两边盯的是同一状态），不等于算法一致。

### 7.3 窗口面：3306 上不存在任何「本窗口内新行 / 本轮标识」
7.2 只覆盖 `analytics_metric` 两张表，而应用真正会写的 `analytics_meta.pipeline_run / spark_job_run / …` **没有窗口前 baseline**。因此补一路不需要 baseline 的判据（`raw/35-3306-window-absence.txt`，脚本动态生成 140 条**全 SELECT** 语句）：

- `analytics_meta` / `analytics_metric` 两库共 **23 张带时间戳列的表**，每一列在窗口 `[2026-09-14 13:50:00, 14:20:00]` 内的行数**全部为 0**（`inwin_* = 0`）；各表 `MAX(时间列)` 的最大值止于 `2026-09-12 22:08:06`（`quarantine_record`）与 `2026-09-12 21:29:27`（`metric_snapshot`/`metric_value`）。
- 本轮标识在 3306 上一律 0 行：`source_data_version LIKE 'v25it-%'` = 0、`target_snapshot_id='S20260901_2'` = 0、`batch_no LIKE 'ing-20260914%'` = 0、`mysql.user LIKE 'v25it%'` = 0、`metric_snapshot.snapshot_id='S20260901_2'` = 0。
- 3306 的 `analytics_meta.flyway_schema_history` 计数 17、最新 `installed_on = 2026-09-12 15:06:05`（窗口前）。

### 7.4 三路结论与局限
三路证据相互独立（连接面 / 内容面 / 窗口面），一致指向：**本轮对宿主正式实例 3306 零写入、零建库、零建账号、零迁移**。

如实标注局限：
- 7.3 证明的是「窗口内没有新行」，不覆盖窗口外历史；也不覆盖 3306 上与本项目无关的其它库。
- `analytics_meta` 侧**没有窗口前的全表内容指纹**，所以「不改计数只改旧行」的写入方式本检查发现不了；本轮不存在此类写入者（应用是唯一写入方，且其连接面只有 3307）。
- 本次未逐条落盘的命令退出码见 `raw/50-commands-and-exit-codes.txt`（`00-create-isolation.ps1` 的退出码未落盘，属**推断**）。

---

## 8. 独立结论：**Flyway「有能力」碰 3306 吗？**（与 §7 分开回答）

**能 —— 而且默认就会。** 本轮零写入**完全依赖启动期覆盖**，不依赖任何内置保护：

- `application.yml:7`：`platform.meta.url` 默认值 = `jdbc:mysql://127.0.0.1:3306/analytics_meta?...`；`:15`、`:19` 的 metric 默认值同理指向 **3306**。
- `MetaFlywayInitializer.java:23-31` 在 `@PostConstruct` 里直接对**注入的** `metaDataSource` 执行 `Flyway.migrate()`（`locations=classpath:db/meta`）；该 DataSource 的 URL 正是 `PlatformDataSources.java:46` 从 `Environment` 读到的 `platform.meta.url`（`:61`/`:85` 同理）。
- ⇒ **不带 `PLATFORM_*` 覆盖直接启动该 jar，meta 迁移集合（本 jar 内 V1–V18，若用新 jar 则 V1–V20）会被应用到 `127.0.0.1:3306/analytics_meta`**，metric 同理。这不是假设性风险：迁移脚本内容即 `CREATE TABLE` / `ALTER TABLE`（例如 `db/meta/V20__data_quality_result_rule_version.sql:59` 的 ALTER）。
- 因此「3306 的 flyway 历史前后一致」（§7.2）只能说明**本轮没有新迁移被应用**，**不能**说明「本项目从来不会写 3306」。两者是**两个不同结论**，本 README 分开陈述。
- 建议（供总控裁决）：给「非隔离启动」加一道显式闸门（例如启动时断言 `platform.meta.url` 的端口 ∈ 白名单，或要求 `PLATFORM_ALLOW_HOST_DB=true` 才允许连 3306）。本轮**未实现**任何此类闸门（本泳道禁改提交配置与主代码）。

---

## 9. F-88 三项观察（只读，详见 `raw/05-f88-observations.txt`）

样本 = 26 条 `data_quality_result`（链路 1 的 4 条 + 链路 2 的 22 条，覆盖 LANDING / ADS_STAGING / PUBLISH 三层）。

| 观察项 | 结论 | 关键依据 |
|---|---|---|
| ① 每 run 的质量规则版本/指纹冻结 | **内存内按 run 冻结一次，但完全没有落库**；指纹两 run 一致（确定性）。目录来自**代码常量**而非 DB ⇒ 无跨发布不可追溯的保证 | `PipelineService.java:346-348`、`:369-374`；`QualityChecker.java:202-203`；`QualityRuleCatalog.java:290`、`:304-326`；`QualityRuleDefinition.java:120-133`；实测指纹 `6bc272d7…3ac6`（`raw/10-app-8091-console.log:95` 与 `:107` 两 run 相同）；`data_quality_result` 仅 14 列、无 `rule_version`/`rule_fingerprint`（`raw/22b-…run2.txt:138-158`） |
| ② 「未登记规则码」路径 | **判定路径可达且语义明确，但类型化异常是死代码**（`UnknownRuleException`/`UnregisteredRuleException` 全仓无 `throw` 站点）；本轮 26 行涉及 15 个规则码，**15/15 已登记**，未触发 | `RuleSeverity.java:274-280`、`:160`、`:62`、`:326-331`；`DataQualityGate.java:84-101`、`:115-118`、`:208-214`；`QualityChecker.java:212-215`；`QualityRuleCatalog.java:111-200` |
| ③ `data_quality_result.severity` 落地 | 落的是**归一化后的有效档位**（不是字面量）：26 行 = **BLOCKING 23 / WARN 3**；同码同定义随 `passed` 变档（`EVENT_ID_UNIQUE`：run 1 passed=0 → BLOCKING 升级；run 2 passed=1 → WARN）；也存在「WARN 且 passed=0 不阻断」的行 | `QualityChecker.java:196-217`（`:203` 写 `verdict.effectiveSeverity()`、`:210` 把原因写进 detail）；`RuleSeverity.java:284-293`、`:374-376`；`QualityRuleCatalog.java:138-143`、`:152-155`、`:158-160`；`raw/22b-…run2.txt:82-137` |

**F-88 是否闭合：本泳道判定「未闭合、也未证实」**，理由三条：
1. schema 侧在本轮窗口末尾被别的改动补齐（`V19__quality_rule_definition.sql` mtime **14:02:53**、`V20__data_quality_result_rule_version.sql` mtime **14:03:11**），二者**晚于**被实测工件（jar mtime 13:58:40，jar 内无 V19/V20）——`raw/40-out-of-repo-audit.txt:8-10`。
2. 同时**没有任何 main 源码**在该 jar 之后被修改（同文件 4.2 段 git diff 为空）⇒ **写侧（写 `rule_version`/`rule_fingerprint` 的代码）尚未见到**；本轮 26 行也确实是在「没有这 4 列」的表上插入成功的。
3. 因此正确表述是：**「schema 补齐（V19/V20）+ 写侧未实现」**，需要一次「用包含 V19/V20 的工件重跑」才能验证写侧；本泳道**未**做、也**不**据此宣称闭合。→ 需总控裁决（§11）。

---

## 10. 仓库外产物审计（硬约束：仓库外不得新建任何文件/目录）

命令与完整输出：`raw/40-out-of-repo-audit.txt`。

**本泳道自身：零仓库外产物。**
- 已在仓库内：证据 `docs/acceptance/v25-e3-isolated-chain-20260914/`、运行目录 `target/e3-run/v25it-20260914-1358-l4e3/`（`target/` 已 gitignore）。
- 曾考虑过的仓库外凭据路径 `%USERPROFILE%\.graduation\credref-<runId>.properties` **已放弃**，实测不存在（`raw/40-out-of-repo-audit.txt:20`）。
- `D:\e3-run` / `D:\e3-evidence` / `D:\e3-artifacts` / `D:\maven_jars` 均不存在（`:21-24`）；`D:\` 根目录今日新建的 `*.dump/*.sql/*.jar/*.log/*.txt/*.csv/*.jsonl` = **0 条**（`:27-28`）；`C:\` 根目录今日新建目录 = **0 条**（`:29-30`）。
- **已披露的唯一仓库外副作用**：Spark/Derby 自己在 OS 临时目录创建的 `%LOCALAPPDATA%\Temp\spark-<uuid>\…`（`raw/40-…:31-39`，当前残留 186 个）。路径非本泳道选择（由 `spark-submit`/Derby 决定），作业日志里有其自删告警（`ShutdownHookManager … Exception while deleting Spark temp dir`，每个作业日志末尾一条，属**已知无害噪声**）。本 README 不声称「绝对零外部写入」，只声称「本泳道未主动在仓库外创建任何文件/目录」。

**【必须上报】发现两个仓库外目录，但**不是**本泳道产物（创建时间早于本泳道开工 3 天，本泳道未使用、未修改、未删除）**：

| 路径 | 创建时间 | 内容摘要 | 是否含正式库数据 |
|---|---|---|---|
| `D:\p103-e3\` | 2026-09-11 20:40:31 | 562 个文件：`analytics_meta_p103.sql`(3.2 MB)、`analytics_metric_p103.sql`(34 KB)、`real-meta-before/after.sql` ×多轮、`real-metric-*.sql`、`base.zip`(33 MB)、`verify-run5*.txt`、`run5-console.txt` | **是**（`analytics_meta` / `analytics_metric` 的完整 dump） |
| `D:\p105-e3\` | 2026-09-12 08:57:51 | 382 个文件：`analytics_meta_p105.sql`(3.3 MB)、`analytics_metric_p105.sql`(34 KB)、`e3-p105.ps1`、`e3-p105-r2.ps1`、`e3-evidence-extra.ps1`、`base.zip` 解包出的 `base\BOOT-INF\lib\*.jar` | **是**（同上，另有整包应用依赖） |

这两处命中任务包明令禁止的 `D:\p*-e3` 形态，是**更早的 E3 尝试**留下的（早于本泳道，非本轮产生）。按纪律：**本泳道不删除、不移动、不复用**，仅上报路径+时间+内容摘要+是否含正式库数据。若这两处含有可用于复现旁证指纹 `452b7223…` 的 `real-meta-before/after.sql`，其归属与处置请总控裁决（本泳道**未**读取其内容，故不判断）。

**其它泳道并行的仓库内改动**（`git status`，与本泳道无关，仅列示以免误归属）：仓库根新增 `after-reboot-cleanup.ps1`、`orphan*.ps1`、`optimize*.ps1`、`post-*.ps1`、`*-src.txt`、`orphan-cleanup-report.txt`，以及 `docs/acceptance/v25-stray-consolidation-20260914/`（`raw/40-…:43-57`）；`db/meta/V19`、`V20` 与 `QualityRuleVersionMi*.java`（§9）。**本泳道新增文件仅限本目录与 `target/e3-run/`（`raw/40-…:4.3` 段逐条列出）。**

**本泳道自建进程的收尾**（`raw/31-residual-process-classification.txt`）：8091 应用 java（PID 62984）已于 **14:15:09** 用 `Stop-Process -Id 62984` 停止，8091 监听已释放；`SparkSubmit` 残留 JVM = **0**；`raw/30-…` 里那行 `残留 java 进程: 55804` 经核查是 **JetBrains DataGrip 的 JBR 进程**（启动于 11:46:08，早于本泳道），**非本泳道残留**。注意：采样时点 8090/8092 **均无监听**，本泳道从未启动或停止 8090/8092；其窗口前状态**未取证**。

---

## 11. 缺陷与待裁决清单

### 11.1 缺陷（`文件:行`）

| # | 缺陷 | 位置 | 影响 |
|---|---|---|---|
| D-1 | 质量规则的「版本/指纹」**不落库**：`data_quality_result` 只有 14 列，无 `rule_version`/`effective_severity`/`compat_policy_version`/`rule_fingerprint`；指纹只存在于应用日志 | 写侧 `QualityChecker.java:196-217`；读侧 `PipelineService.java:346-348`；表结构见 `raw/22b-…run2.txt:138-158`；缺口自述 `PipelineService.java:340-345` | 事后无法从库复算「该 run 用了哪一版口径」；日志滚动即永久不可考。**F-88 核心** |
| D-2 | 规则目录是**代码常量**而非 DB 表，`DEFAULT` 直接 `FrozenRules.of(DEFINITIONS)` | `QualityRuleCatalog.java:290`（`CATALOG_VERSION="qrc-1"`） | 只有「同发布内一致」，没有「跨发布不被追溯改写」的保证 |
| D-3 | `severity` 一列**承担两种语义**（声明档位 / 有效档位），易被误读为声明值 | 落库 `QualityChecker.java:203`；声明值 `QualityRuleCatalog.java:138-143`、`:158-160` | 用该列统计会得出错误分布（例：run 1 的 `EVENT_ID_UNIQUE` 实为「观察项因超阈值升级」，库里只见 `BLOCKING`）。旁证：V20 才补的 `effective_severity` 正说明应分列 |
| D-4 | `UnknownRuleException` / `UnregisteredRuleException` **声明但全仓无 `throw` 站点** | `RuleSeverity.java:326-331`、`DataQualityGate.java:208-214` | 「未登记规则码」只有值域上报（`GateDecision` FAIL + 理由文本），没有可 catch 的类型化异常；调用方难以区分「未登记」与「普通阻断」 |
| D-5 | 「非隔离启动即写 3306」**没有闸门**（见 §8） | `application.yml:7`/`:15`/`:19` 默认值；`MetaFlywayInitializer.java:23-31`；`PlatformDataSources.java:46`/`:61`/`:85` | 任何人只要忘了 `PLATFORM_*` 覆盖，Flyway 就会把迁移应用到正式库。本轮零写入**全靠纪律**，无技术保护 |
| D-6 | 取证脚手架：`scripts/30-stop-and-postcheck.ps1` 首版把 `mysql -t` 的表头行 `\| k \| v \|` 当数据行，导致一次**假不一致**（exit 5） | 该脚本 `Parse()`（已修，注释留痕）；现象记录 `raw/50-commands-and-exit-codes.txt` C 段 | 属**本泳道自身**缺陷，非产品问题；原始指纹文件未改动，仅解析层修复，第 2 次运行不一致项 = 0 |
| D-7 | 后台作业层把**任何非零退出码归一化为 1**，`exit 4` 与「崩溃」在作业层不可区分 | 实测探针见 `raw/50-commands-and-exit-codes.txt` A 段（前台 `$LASTEXITCODE=4`，同一命令作后台作业报 `exit code: 1`） | 引用「退出码」作判据时必须以脚本末尾输出为锚，不能只看作业状态 |
| D-8 | 取证脚手架：导出脚本有 3 条只读查询写错（列名 `profile_type` 不存在；`pipeline_definition`/`metric_definition` 打到不存在的库），在 `raw/03-…` 里留下 3 条 `ERROR 1054/1146` | `raw/03-isolated-flyway-and-seeds.txt`（原始输出保留未改）；正确数据见 `raw/04-…`、`raw/22b-…:170-177` | 属**本泳道自身**缺陷，只读、无副作用；已在 §4 显式披露，避免读者把 ERROR 行误认为产品故障 |

产品侧另有 1 项行为记录（**不计缺陷**）：未映射的 `/api/v1/**` 路径返回 **403 FORBIDDEN_PERMISSION** 而非 404（`AuthInterceptor.java:88-94`；样本 `raw/http/43c-metrics-summary.json`；仅探测 1 个路径，**未取证**普遍性）。

### 11.2 需总控裁决 / 需他人补做的

1. **F-88 复测**（最高优先）：用**包含 V19/V20 的工件**重跑隔离链路，验证写侧是否真的写入 4 列、其 `rule_fingerprint` 是否与 Java 侧 `QualityRuleDefinition.checksum()` 逐字节一致。本泳道未获授权改迁移/主代码，故**停在观察**。
2. **V19/V20 的归属与评审**：这两份迁移在本轮窗口内由其它泳道加入（mtime 14:02:53 / 14:03:11），与 F-88 修复直接相关；是否已过总控批准、是否属于 L4 的交付面，请裁决。
3. **D-5 闸门**：是否要求「非隔离启动必须显式确认」的技术保护（本泳道建议加，但禁改主代码/配置）。
4. **D-4**：未登记规则码是否应改为类型化异常 + 明确的 HTTP 错误码。
5. **自动化测试**：本轮未运行任何测试（§2 ②）。是否需要由本泳道补跑 `mvn test`（注意：部分 IT 可能直接连 3306，跑之前必须先确认不会写正式库）。
6. **`D:\p103-e3` / `D:\p105-e3`**（§10）：仓库外含正式库 dump 的目录，处置（归档到仓库内？清理？）由总控决定；本泳道按纪律**未删未动**。
7. **旁证指纹不可比**：`452b7223a4a2b9dd0df7f3c883cdb74b` 无 SQL 可复现（§7.2）。若需跨泳道互证，需原泳道补出算法或 SQL。

### 11.3 未取证清单（凡未实测，一律标「未取证」，不写「应该/大概」）

| 项 | 为什么未取证 |
|---|---|
| 自动化测试结果 | 本轮未运行（§2 ②） |
| V19/V20 应用后的写侧行为 | 不在被实测 jar 内（§9） |
| 「未登记规则码」的实际触发效果 | 需改 `spark-jobs`（禁改）或跑单测（未跑） |
| 未映射路径一律 403 是否普遍 | 只探测了 1 个路径（§6.3） |
| 8090/8092 在窗口前的监听状态 | 本泳道未记录；只知采样时点无监听（§10） |
| 跨泳道指纹互证 | 对方未留 SQL，算法不可复现（§7.2） |
| `00-create-isolation.ps1` 的退出码 | 未落盘，判定 0 属推断（`raw/50-…` D 段） |
| `EVENT_ID_UNIQUE.check_count=91` 的精确构成 | dqc 侧输入明细未导出；只声明「91 条纳入检查的事件中有 1 条重复」（`raw/07-dup-source-proof.txt` §5） |
| Spark 作业侧是否会写入「声明档位字面量」 | 本轮 26 行的 severity 与目录一致，只是**未观察到**漂移，不足以反推「永不漂移」（`raw/05-f88-observations.txt` 观察 3） |
| Spark/Derby 在 OS 临时目录的残留是否会被完全清理 | 只观察到作业日志中的自删失败告警（§10），未跟踪其最终状态 |
| 「改旧行不改计数」型写入 | 现有判据无法发现（§7.4），本轮无此类写入者 |

---

## 12. 复现步骤（命令 + 退出码）

完整清单（含退出码语义实测）见 **`raw/50-commands-and-exit-codes.txt`**。摘要：

```powershell
# 0) 前置：WSL2 内已起 3307 隔离实例；$env:V25IT_ADMIN_PWD='123456'
cd D:\Develop_code\GraduationProject

# 1) 3306 零写入 baseline（只读）
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --host=127.0.0.1 --port=3306 --user=root `
    -t -e "source docs\acceptance\v25-e3-isolated-chain-20260914\scripts\fingerprint-3306.sql" `
    > docs\acceptance\v25-e3-isolated-chain-20260914\raw\baseline-3306-before.txt

# 2) 在 3307 上建 runId 命名的库与受限账号（拒绝端口非 3307）
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\00-create-isolation.ps1 `
     -RunId v25it-20260914-1358-l4e3 -Confirm -AllowRootOnIsolated

# 3) 以环境变量覆盖方式启动 8091（不修改任何提交配置）
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\10-start-app-isolated.ps1   # 后台

# 4) 三步链路（run 1）
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\20-run-chain-isolated.ps1
# 5) 只读导出隔离库证据
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\21-dump-isolated-evidence.ps1
# 6) 第二步干净链路（run 2，新文件路径 + 同内容校验）
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\22-run-chain2-isolated.ps1
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\21-dump-isolated-evidence.ps1 `
     -OutName 22b-isolated-meta-evidence-run2.txt
# 7) 停 8091 + 3306 after 指纹 + 逐项比对
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\30-stop-and-postcheck.ps1
# 8) 3306「窗口内无新行」独立判据（全 SELECT）
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\35-3306-window-absence-check.ps1
# 9) 仓库外产物审计 + 工件身份 + 变更面
pwsh -NoProfile -File docs\acceptance\v25-e3-isolated-chain-20260914\scripts\40-out-of-repo-audit.ps1
```

退出码（实测口径见 `raw/50-…` A 段：后台作业会把非零归一化为 1）：
`20-` 链路由脚本内部分支决定（链路 1 走 `exit 4` ⇒ 作业层显示 1；链路 2 走 `exit 0`）；`30-` 第 1 次 `5`（假不一致，已修）、第 2 次 `0`；`35-` `0`；`40-` `0`。

---

## 13. 证据清单（raw/ 文件 → 支撑哪条结论）

| 文件 | 支撑 |
|---|---|
| `raw/baseline-3306-before.txt` / `raw/baseline-3306-after.txt` | §7.2 前后指纹原始读数 |
| `raw/01-create-isolation.txt` | §2 隔离实例指纹、库/账号/授权清单 |
| `raw/02-isolated-account-connectivity.txt` | §2 最小权限实测（跨库被拒 1142） |
| `raw/03-isolated-flyway-and-seeds.txt` | §4 隔离库迁移与种子 |
| `raw/04-mirror-runtime-profile-from-3306.txt` + `04b-…sql` | §4 隔离库 ACTIVE 档案来源（只读自 3306） |
| `raw/05-f88-observations.txt` | §9 F-88 三项观察全文 |
| `raw/06-isolated-table-inventory.txt` | §4 两库表清单实况（meta 21 / metric 11，无 `quality_rule_definition`） |
| `raw/07-dup-source-proof.txt` | §6.1 链路 1 阻断根因的文件级取证（golden-evt-008 重复 / 55→51+4 字节自洽） |
| `raw/10-app-8091-console.log` | §3 生效数据源 URL、§4 Flyway、§6 阶段/规则冻结、§7.1 无 3306 |
| `raw/20-chain-console.log` + `20-chain-summary.json` | §6.1 链路 1（含 HTTP 状态与终态 FAILED） |
| `raw/21-isolated-meta-evidence.txt` | §6.1 run 1 的 stage/job/质量行原始导出 |
| `raw/22-chain2-console.log` + `22-chain2-summary.json` | §6.2 链路 2（SUCCESS + 文件同源校验） |
| `raw/22b-isolated-meta-evidence-run2.txt` | §6.2/§9 run 2 的 stage/job/质量/指标原始导出（含 severity 分布、列清单） |
| `raw/http/*.json`（00/01/01b/01c/02/02b/02c/03/03b + 40/41/41b/41c/42/42b/42c/43/43b/43c） | §6 每次 `/api/v1/...` 调用的状态码 + 脱敏响应体 |
| `raw/30-app-connections-during-window.txt` | §7.1 连接面（3306=0 / 3307=30）+ 停止证据 |
| `raw/31-residual-process-classification.txt` | §10 残留进程归属（55804 = DataGrip） |
| `raw/33-zero-write-comparison.txt` | §7.2 逐项 before/after 比对（不一致项 = 0） |
| `raw/35-3306-window-absence.txt` + `35-3306-window-absence.sql` | §7.3 窗口面无新行 + 无本轮标识（140 条全 SELECT） |
| `raw/40-out-of-repo-audit.txt` | §5 工件身份、§10 仓库外审计与变更面 |
| `raw/50-commands-and-exit-codes.txt` | §12 命令与退出码语义（含后台归一化实测） |
| `scripts/*.ps1`、`scripts/fingerprint-3306.sql` | 上述所有读数的可复现来源 |

---

*本文件由泳道 L4 生成于 2026-09-14。所有「一致 / 通过 / 达成」均以文中引用的 `raw/` 原始产物为依据；凡未实测项已集中列在 §11.3。*
