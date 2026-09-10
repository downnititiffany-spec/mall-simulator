# 会话交接（2026-09-10 · R6 收口进行中）

> 权威指导：`docs/项目完整实施指导书 V2.0.md`（§15 R6 详细实施、§24 工作清单、§31 唯一优先级）。
> 进度登记：`docs/remediation-status.md`。基线：分支 `remediation/r1-boundary`。

## 1. 当前位置

已完成并提交（本次会话）：
- `0076fcc` R6-8b 黄金数据 30→55 行 + 全链真实对账（上一会话）
- `f6911b0` **R6-9/R6-10**：`EventContract` 12 类契约+ODS 路由唯一来源；`PipelineService` 删私有旧白名单；`RuntimeProfileSnapshot`（不可变）+ `JobSubmitterFactory`；日志名含 run/stage/job/attempt
- 最新提交 **R6-11 + R6-12/15 半程**：生产 `PipelineService` 接入真实 `SparkStageExecutor`（INIT_SCHEMA/LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS），阶段内 fail-fast，删除 `MetricCalculator`/`local-calculator` 路径，计数一律取 JobResult；`SparkStageExecutorFactory`（Bean）＋ `confsFor()` 显式钉 warehouse/metastore；迁移 `V9`（evidence 加宽 4000）＋ `evidenceJson()` 截断兜底；黄金夹具补末尾换行

测试：warehouse-pipeline 快速套件 **80/80 GREEN**；`analytics-server` 七模块 package SUCCESS。

## 2. 已取得的真实证据（生产链路，run 12）

正规流程：`disable → update → activate`（local-dev 档案 v2→v3 ACTIVE，写入 `spark_submit_path`/`spark_job_jar_uri`/`spark_master=local[2]`；激活含真实 `spark-submit --version`）。

| 阶段 | 结果 | 证据 |
|---|---|---|
| WAIT_LANDING | SUCCESS | manifest batch 18：accepted=51 / quarantined=4（=55 行对账成立）|
| LOAD_ODS | SUCCESS | `spark_job_run` odl `lp-1789030422172-1d2b14` input=51 output=51，log_uri 真实 |
| BUILD_DWD bdw | SUCCESS | input=15 output=14（行为 ODS 15 → DWD 14 + 1 重复拒绝）|
| BUILD_DWD dim | **FAILED** | `INSERT_COLUMN_ARITY_MISMATCH`：`dw_dim.dim_user` 6 列（旧 DDL）vs 当前 SQL 7 列（含 `source_batch_id`）|
| tdw / usw / fna | 未执行 | fail-fast 生效（依赖阶段不跑）|

## 3. 三个缺陷（前两个已修，第三个待新仓验证）

1. **旧 warehouse schema 漂移（环境遗留）**：生产 LOCAL 路径此前未指定仓位置 → Spark 用全局默认 `D:\Develop\tmp\spark-warehouse` + CWD `metastore_db`；该仓由**旧 DDL** 建表，而 `sci` 仅 `CREATE TABLE IF NOT EXISTS`（不演进既有表）。**已缓解**：`confsFor()` 将 warehouse/metastore 钉到 `./spark-warehouse` / `./derby-metastore`（新仓 → 当前 DDL 建表）。**遗留改进**：`sci` 应做幂等 schema 对账（`ALTER TABLE … ADD COLUMNS`）。
2. **evidence 列过窄（本次新代码缺陷，已修）**：`VARCHAR(500)` 装不下多作业证据 → `MysqlDataTruncation` → 阶段落库失败、run `RUN_INTERNAL`。见 `V9__platform_stage_evidence_widen.sql` + `evidenceJson()`。
3. **黄金夹具末行无换行（已修）**：采集器按 Taildir 语义保留尾部残行（§9.2 有意设计）→ 第 55 行丢失。补 LF 后重采 = 51+4=55 ✓。

## 4. 下一步（按序，含确切命令）

**步骤 1 — 完成 R6-15 生产链路验收（应用已停、新构建已就绪）**
```powershell
# 从仓库根启动（CWD 决定 ./spark-warehouse 与 ./derby-metastore）
cd D:\Develop_code\GraduationProject
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'; $env:SPARK_LOCAL_IP='127.0.0.1'
java -jar analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar   # 后台
# 登录 → 提交 → 轮询（businessTime=2026-09-01T00:00:00, runtimeProfileId=1, pipelineCode=ODS_TO_ADS）
# POST /api/v1/pipeline-runs（Header: Authorization: Bearer <token>, Idempotency-Key: <新值>）
```
预期：INIT_SCHEMA(sci) 在新仓建当前 schema → 全阶段 SUCCESS；随后用 spark-sql 核对 `dws_trade_day`（sale=2042.00 / refund=549.00 / net=1493.00）、`ads_operation_overview`、质量 4 规则（EVENT_ID_UNKNOWN 只记不阻断）。**注意**：采集用 batch 18（accepted/18，51 行）即可，无需重采。

**步骤 2 — R6-12 收尾**：Scala `JobResult` 增加 `outputPartitions`（各作业写入分区列表）＋ `JobResultParser`/实体/迁移同步，落 `spark_job_run`。

**步骤 3 — R6-13**：ADS 写 `{table}__staging/snapshot_id=…/dt=…` → 质量读 staging → 通过后发布正式分区（失败保旧分区 + 旧 ACTIVE 不变）。

**步骤 4 — R6-14**：`PipelineRecoveryService`（启动时 PENDING 重排、RUNNING 按 externalJobId 补齐/标记）。

**步骤 5 — R7**（§24.2-24.6）：先统一口径（PV 只计 view、refund_rate 计已发生退款 + 另建 full_refund_rate），再 Hive→MySQL staging→ACTIVE 原子发布，最后看板只读 MetricStore（删除 `AnalysisService.loadEvents`、`RfmService` 的 `mall_order`）。

## 5. 环境与纪律

- 环境：MySQL80 运行中（`analytics_meta`/`analytics_metric`，Flyway 已到 V9）；Spark `D:\Develop\spark-3.5.1-bin-hadoop3`；jar `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar`（Scala 未改，无需重打）。
- 登录：`admin/admin123`（`POST /api/v1/auth/login` → `data.token`，后续 `Authorization: Bearer`）。
- 每子阶段纪律：快速测试 → 真实冒烟 → 备份 `docs/backups/` → 登记 `docs/remediation-status.md` → git commit；**Mock/未验证不得写成真实**；口径分歧如实登记不"修正"。
- 已知口径分歧（已登记 expected notes）：`refund_rate` 字典=任意退款 0.60 vs ADS=完全退款 0.20；`pv` 字典=view 7 vs ADS=全行为 14。
