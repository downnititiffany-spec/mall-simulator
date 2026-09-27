# G31-04 故障恢复演练结果（04.1~04.4）

> 结论：**04.1~04.4 恢复机制判据全部真实满足（PASS）**；演练同时发现一项与本批判据无关的**平台数据正确性缺陷 F-G4-1**（ODS 装载增量批次覆写丢失历史，详见 §5），已登记并走变更请求，不在本批修复。本结论只适用于下述工作树、构建产物与隔离环境。

## 1. 执行范围

- 执行时间：2026-09-25 09:57–10:59（Asia/Shanghai；attempt-1 09:57 起，attempt-2 10:43–10:58，收口 10:59）。
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，分支 `feature/v3-development`，代码基线 HEAD `b25b47f0ec5ea85bb3d5989d287ae21befcdf039`；工作树携带 G31-00~G31-04 各批次未提交改动（按「暂不 commit」指令保留），本次 JAR 从该工作树打包。
- 演练 RunId：attempt-1 `g3104_20260925_095733`（驱动缺陷中止，档案保留）；attempt-2 `g3104b_20260925_104346`（正式）。
- JAR：platform-app SHA-256 `f6d0c3a407f55dc50b3e984e7114601a5a361aadd0d9c6ed8d2f28521a7ce6ce`；spark-jobs SHA-256 `b5554d7e93426a4d8d2658e2a07c9cde898f3f5cac0a20512155ab9e356c1ee8`（见 `target/v25-it/g3104_20260925_095733/jar-sha256.txt`）。
- 驱动：`target/v25-it/g3104b_20260925_104346/g3104-driver.ps1` + `g3104b-finish-044.ps1`；状态机 `drill-state.json`（outcome=PASS，26 legs）。

## 2. 隔离与运行形态

- MySQL 8.0.41 于 WSL 独立数据目录监听 `127.0.0.1:3307`（原 cmdline 358 字符存档于 `drill-logs/mysqld-cmdline.txt`）；Windows MySQL `3306` 全程零接触（F2b 守卫断言零 `:3306`）。
- 平台进程在 Windows/JDK 17 运行（8091）；Spark 3.5.1 `spark-submit` 真实子进程、`local[1]`、本地 Derby metastore + 本地 warehouse。
- stub LLM（127.0.0.1:18080，D-039）与前端 dev server 全程保持运行，未受演练影响。

## 3. 演练结果

- **04.1 基线复验**：当前 worktree 重建双 jar 后 L1 单测 `Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（`target/v25-it/g3104_20260925_095733/g3104-unit-console.log`）。
- **04.2 kill 真实 Spark 子进程**：run2 BUILD_DWD 期间 kill spark-submit 子 java（pid 51304，CommandLine 匹配 spark-jobs jar；被杀作业=`dim`）→ run FAILED（errorCode `RUN_JOB_FAILED`，failedStage=BUILD_DWD，SJR dim=`JOB_EXECUTION_FAILED`「未找到 JobResult 结果行」）→ 管理员 `POST /retry-from-stage` → 8 阶段全 SUCCESS → ACTIVE S2。
- **04.3 kill 平台进程 + 重启对账**：run3 dim 运行中 kill 平台 java → 孤立 Spark 子进程经 CommandLine 匹配台账清理 → 同 jar 重启 → `reconcileOnStartup`：run3 RUNNING→`RUN_INTERRUPTED` + `[RECOVERY]` 证据、SJR dim→`UNKNOWN`/`JOB_ORPHANED`；`GET /recovery-report` 可读且与 DB 行一致（interruptedRunning=1、孤立作业置 UNKNOWN=1）；中断期间旧 ACTIVE 快照（S2）可读且前后逐位相等 → 清理后 `resume` → SUCCESS → ACTIVE S3。
- **04.4 重启 WSL MySQL 3307**：mysqladmin stop 后平台 java 进程存活（HikariPool `meta-ds` 连接校验失败刷屏日志为证），API 优雅降级（login 返回 `INTERNAL 系统繁忙`）→ 同参恢复 mysqld（pid 422）→ API 自愈恢复服务 → run4 因 DB 中断期状态写被吞而滞留 RUNNING → `mark-failed` + `resume` → SUCCESS（attempt=2）→ ACTIVE S4。
- **收口**：演练平台停止（身份核验后）；g3103 常驻栈按 `target/v25-it/item3/item3-restore-g3103.ps1 -Confirm` 恢复 **PASS**：mini-gate S20260921_20 ACTIVE 唯一、DEC=12（≥10，实际值记录）、USR=3、F2b 零 3306、stub LLM 探针 `providerUsed=stub-local`（suggestions=2）；日志 `target/v25-it/item3/restore-g3103-run-20260924.log`。

## 4. 门修正与 attempt-1 归因（诚实记录）

- **attempt-1 驱动缺陷（非平台）**：`g3104-driver.ps1` 两处 `[Math]::Min(240, "$($victim.CommandLine)")` 漏写 `.Length`，PowerShell 将整个命令行字符串当 Int32 绑定 → ParameterBindingException 中止。残留平台/Spark 进程按 CommandLine 匹配台账清理（`ledger` 存档）；attempt-2 驱动以精确串补丁修正并实测通过（`make-attempt2-driver.ps1` 存档补丁锚点）。
- **C-G4（门修正）**：attempt-2 主驱动停 mysqld 后仅等 3 秒即 `pgrep` 判定——mysqld 尚在 InnoDB flush，驱动如实 `[REFUSE exit=7]`（pid 仍存在）。事后核验 shutdown 真实完成（ping refused + NO_MYSQLD_PROC）；由 `g3104b-finish-044.ps1` 三重复核真实停机后继续完成 04.4 后半段。属演练脚本等待窗口不足，非平台缺陷。

## 5. 发现 F-G4-1（平台缺陷，非故障注入产物；本批不修复）

S1（14 指标，pv=7/uv=3/dau=3/cart_add_cnt=3/fav_cnt=2/buy_rate=1/cart_rate=0.6667）→ S2 起（12 指标）行为类指标全部归零、buy_rate/cart_rate 消失；交易类指标逐位不变。S3、S4 同 S2。

- **定性**：平台数据正确性缺陷。根因在**正常 LOAD_ODS 路径**，与 kill/retry/resume 无关（SJR 显示 run2 的 `odl` 在无任何故障注入下 SUCCESS 1>1）。
- **根因**：`OdsLoadSql.insert`（spark-jobs `OdsLoadSql.scala:174`）对四张 ODS 表执行动态分区 `INSERT OVERWRITE TABLE … PARTITION (dt, hour)`，且 `EventOdsLoadJob` 未设 `spark.sql.sources.partitionOverwriteMode`（默认 STATIC=整表覆写）。增量 landing 批次（如 1 条良性注册事件）到达即清空该表全部历史分区。对比 `TradeDwdJob.scala:26` 显式设 dynamic、`:69` 空输入跳过写——故交易侧指标以**陈旧数据**幸存（dwd_order_detail 保留 run1 7 行），行为侧 `DwdSql.behaviorClean`（`DwdSql.scala:34`）无守卫被 0 行覆写清空，链式传导至 dim（run2 维表仅 1 用户/0 商品）、dws、ads、发布快照。
- **证据**：`target/v25-it/g3104b_20260925_104346/F-G4-1-REGRESSION-FINDING.md`（完整链条）；`drill-logs/sql-regression-probe.{sql,out.txt}`（44 条 SJR 逐作业计数）；演练 `spark-warehouse/`（ods_behavior_event/ods_trade_event/ods_product_event 0 parquet、ods_user_event 1 parquet、dwd_user_behavior_detail 仅 `_SUCCESS`）；`drill-state.json` legs `run2-retry`/`run3-resume`/`run4-final`（S1/S2/S3/S4 指标表）。
- **影响面**：同业务日多次运行（增量摄入）必然丢失 ODS 历史并回归已发布指标；跨业务日运行不受影响（G31-03 mini-gate USR=3 跨 3 个业务日因此未暴露）。
- **处置**：修复涉及冻结 V3.0 设计 §10.2/§10.3 明文「INSERT OVERWRITE 幂等」口径的增量场景语义，属语义级变更 → 按交接指令**走变更请求交总控**（候选方案：odl 设 dynamic 分区覆写 / INSERT INTO + ingest_batch_id 幂等去重 / 规定 landing 全量重放），登记于 `docs/decisions/DECISION_LOG.md` D-040。本批不改码。

## 6. 边界

- 本结果**不代表**：断电/磁盘损坏级故障、跨资源 exactly-once（PLAN §6 既有声明）、源停机/同文件重放完整演练、发布校验和 fail-closed 形态（交接项 2 已另行登记）、真实大模型（仍 BLOCKED，D-039）、Hive/HDFS/远程集群形态。
- F-G4-1 未修复：当前代码在同业务日第二次起的增量摄入上会回归已发布指标；在该缺陷闭环前，多次运行同一业务日的指标一致性**不可**作为验收口径。
- V25_IT_* 口令零落盘；平台 secret env 不入日志；`.zcode/` 不提交；未 commit、未 push。
