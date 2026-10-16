# BATCH-G31-04-FAULT-RECOVERY 批次计划（指导书 V3.1 §7 G31-04 故障恢复）

- 指导书条目：§7 G31-04「故障恢复」（V3.1 主序 G31-00→…→G31-07 的第 5 批；V3.1 为用户 2026-09-19 消息，非仓内文件）
- 前序锚点：G31-03 已收口（2026-09-25，ACTIVE=S20260921_20，DEC=10，浏览器 leg ALL PASS）；交接项 1~5 全部完成
- 既有 L1 基线：PROJECT_STATUS 2026-09-23 条目 —— `PipelineServiceTest` + `PipelineRecoveryServiceTest` 41/41 PASS，边界声明「不代表真实进程中断、数据库恢复或 Spark 子进程恢复验收通过；需待 G31-03 稳定链路后再按隔离 run 做小规模故障演练」——**本批即该演练**
- 批次 ID：`BATCH-G31-04-FAULT-RECOVERY`
- 数据域：**全新** runId 域 `g3104_<ts>_analytics_meta / _analytics_metric`（3307，it-prepare-isolation 幂等新建；不复用、不污染 stage7q1/g3103 域）
- 证据根：`target/v25-it/g3104_<ts>/`
- 被测代码：feature/v3-development 当前 worktree（含 16 处既有未提交改动，原样保留）——演练前重建 platform-app jar 与 spark-jobs jar，登记 SHA
- Stage 7 总清单对位：范围清单 #6「故障样本：源停机、重放、阶段失败、发布中断后旧快照仍读得到」中「阶段失败（真实 spark-submit 子进程形态）」+「发布中断后旧快照」的进程级变体；「源停机/重放」由既有采集断点修正证据与后续批次覆盖

## 1. 演练项（04.1~04.4）

| 项 | 故障注入（真实形态） | 预期恢复语义（代码依据） | 证据 |
|---|---|---|---|
| 04.1 | 无（基线复验） | 当前 worktree 下 L1 单测仍全绿 | `PipelineServiceTest`+`PipelineRecoveryServiceTest` 计数（目标 run 目录） |
| 04.2 | pipeline 运行中 **kill 真实 spark-submit 子 java 进程**（CommandLine 匹配 spark-jobs jar 的进程，不用 Fake executor） | 阶段真实失败 → run FAILED（子进程退出码/错误留痕）→ 管理员 `POST /api/v1/admin/pipeline-runs/{id}/retry-from-stage` → 全阶段 SUCCESS | drill JSON + run 终态 + stage 证据 |
| 04.3 | pipeline 运行中 **kill 平台 java 进程**（真实进程中断，非优雅停机）→ 重启平台 | 启动对账（`PipelineRecoveryService.reconcileOnStartup`，R6-14/§23.1）：RUNNING→FAILED/RUN_INTERRUPTED、未结束 spark_job_run→UNKNOWN/JOB_ORPHANED、RECOVERY 审计入最早阶段证据；`GET /api/v1/admin/pipeline-runs/recovery-report` 可读；**中断期间旧 ACTIVE 快照仍可读**（overview 值不变）；清理孤立 Spark 子进程后 `resume` → SUCCESS | recovery-report JSON + DB 回读 + overview 前后对比 |
| 04.4 | **重启 WSL MySQL 3307**（记录原 cmdline，stop→start 同参恢复） | 平台进程不死（Hikari 池自愈）；DB 恢复后 overview 仍服务旧 ACTIVE；受影响中断 run 经 resume → SUCCESS | mysqld stop/start 记录 + 平台存活证明 + 恢复后 run SUCCESS |

运行序列（全部同一 g3104 域、同一平台实例，除 04.3 的重启外不重启平台）：
1. run 1 = golden 全链基线（ingestion→8 阶段→SUCCESS→ACTIVE S1）——同时在**当前 worktree 构建**上复证正常链。
2. 04.2 = run 2（新增 1 条 benign 事件）中途杀 Spark 子进程 → retry-from-stage → SUCCESS → ACTIVE S2。
3. 04.3 = run 3（新增 1 条 benign 事件）中途杀平台 → 重启 → 对账/审计/旧快照断言 → resume → SUCCESS → ACTIVE S3。
4. 04.4 = 杀 mysqld（平台存活断言）→ 起回 → run 4（或 resume 受影响 run）→ SUCCESS。
5. 收口：G31-04 证据归档；g3103 常驻栈按 item3-restore-g3103.ps1 恢复（mini-gate：S20260921_20 ACTIVE 唯一、DEC≥10、USR=3）。

## 2. 环境与安全边界

- 平台：8091（启动前 ledger 式停止 g3103 常驻栈——与 item2 停栈同法，记录恢复路径）；stub LLM 18080 保持存活（恢复脚本依赖其 /healthz）。
- 复用既有机制，不新造轮子：it-prepare-isolation.ps1（域准备）、isolation-naming.ps1、assert-platform-env.ps1（F2a/F2b 守卫）、stage7-http-isolated.ps1 的进程树收集/证据模式（但本批驱动脚本自行管理平台生命周期，不用其「结束即停平台」行为）。
- 口令通道：V25_IT_* 单进程内 New-RandomSecret 生成（零落盘/零 argv/零日志）；V25IT_ADMIN_PWD 用 W03 文档化值且只进 WSL mysql 进程 env；3306 永久零接触（含只读）；F2b 日志守卫断言 3307 JDBC URL + 零 :3306。
- MySQL 3307 重启前必须先从 /proc/<pid>/cmdline 抓取完整原命令并存证，恢复失败则如实登记 BLOCKED，不猜测、不强启。
- 平台杀进程只针对本批驱动启动并登记 PID 的 platform java 进程；Spark 子进程只杀 CommandLine 匹配 `spark-jobs` jar 的进程；绝不全量扫杀 java.exe。
- 每次故障注入的**实际故障形态**以取证为准（退出码/日志/DB 行），不按预期脚本化描写；race-lost 一律如实记录（同 item2 惯例）。
- 未 commit/push（授权未恢复）；3306/ACTIVE 历史快照零改动；g3103/stage7q1 域零写入。

## 3. 边界（本批显式声明）

- 单机 local[*] Spark + 本机 Derby metastore + WSL 单节点 3307；不证明集群/Hive/HDFS 故障恢复。
- 04.2/04.3 的 kill 是进程级真实中断，但不模拟断电/磁盘故障；跨资源 exactly-once 不在本批（PLAN §6 既有声明仍有效）。
- 发布中断的「publisher fail-closed 校验和」形态已由交接项2登记（RUN_METRIC_PUBLISH_FAILED→旧快照可读）；本批 04.3 补充的是**进程中断面**（平台死亡→重启→旧 ACTIVE 仍可读），不重复校验和形态。
- 「源停机/同文件重放」不在本批执行窗口内（由采集断点修正既有证据覆盖一部分；完整重放演练若时间不允许移交后续批次，如实登记）。
- 真实 LLM 仍 BLOCKED，与本批无关。

## 4. PASS 判定

- 04.2：run 2 以真实子进程失败态进入 FAILED（错误证据非 Fake）→ retry-from-stage 后全 8 阶段 SUCCESS。
- 04.3：重启后 recovery-report 三项（interruptedRunning/orphanJobsMarkedUnknown/requeuedPending 或 resume 后成功）与 DB 行一致；RUN_INTERRUPTED+JOB_ORPHANED 精确落行；重启前后 overview 的 snapshotId 与 14 指标值逐位不变；resume 后 SUCCESS。
- 04.4：mysqld 停止期间平台 java 进程存活；恢复后 API 正常且受影响 run resume→SUCCESS。
- 全部：F2b 守卫零 :3306；V25_IT_* 零落盘；证据 JSON 全部落 target 并在 PROJECT_STATUS 登记路径。
