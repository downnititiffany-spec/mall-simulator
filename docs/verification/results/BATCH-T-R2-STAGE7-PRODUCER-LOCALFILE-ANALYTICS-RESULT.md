# BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Controller Review

> Overall: **FAIL_PRODUCTION_RUN_PUBLISH_FAILED（MP_ADS_WRITE）**——按计划 §6 最终分类 = production FAIL（attempt-4 终态；前 3 次 attempt 为 BLOCKED_ENV，被环境修复 A 解除，见 §9）。**被测修复 D-020（`1ae091b`）的声明范围在实链被证实**：mxp SUCCESS、两张合法 0 行表 `hive=0 export=0` 对账通过、无 UNABLE_TO_INFER_SCHEMA、MetricPublisher 成功消费 manifest；失败点后移至 MetricPublisher 写 ADS 宽表——metric 库历史迁移 V3 定义的 `ads_data_quality_m.error_rate NOT NULL` 拒绝 D-019 合法空态语义的 `error_rate=NULL` 行（§10–§12）。
> Exact tested SHA: `1ae091b2b118df03632a799ffb9fb664f19a3eda`（D-020 mxp 合法 0 行 ADS 导出修复；detach 后 `mvn -DskipTests package` 重建两 JAR，branch 在 finally 中恢复 `feature/v3-development`）
> RunId: `stage7q1_20260918_152245`
> Attempts: **4**（2026-09-19 11:12–13:31）
>
> | # | outer attempt | http attempt | platform PID | outcome | 失败点 |
> |---|---|---|---|---|---|
> | 1 | `attempt-20260919_112031_598` | `attempt-20260919_112032_022` | 49196（被 harness 正常清理） | `PIPELINE_TIMEOUT` | QUALITY_CHECK：dqc JVM 0-CPU 挂起 |
> | 2 | —（未进入 e2e） | `attempt-20260919_113916_916` | 63308 | `EXCEPTION / 平台未就绪` | 平台 JVM 出生 ~3s 被 CTRL_C 杀死（瞬时环境信号，attempt-3 同条件证伪"可复现"） |
> | 3 | `attempt-20260919_115603_058` | `attempt-20260919_115603_407` | 35588（存活至超时被清理） | `PIPELINE_TIMEOUT` | INIT_SCHEMA：driver JVM jar 自下载回环 stall（**jstack 铁证**） |
> | 4 | `attempt-20260919_132732_187` | `attempt-20260919_132732_589` | 82136（全程存活） | 终态 `FAILED`（exit 7） | PUBLISH_METRIC 第二步（MetricPublisher→MetricAdsWriter）：`MP_ADS_WRITE` `Column 'error_rate' cannot be null`；**mxp 作业本身 SUCCESS（D-020 证实）** |

## 1. Pinned input handoff（每次 attempt 均成立）

三次尝试的 producer 输入均为 T-R2 计划 §2 钉死的 S-R1 证据文件（`attempt-20260918_195657_077`，SHA256 `f32906…bbcc`，1011/1011/0，businessDate 20260918，mock-mall），e2e 预检逐次校验通过：

- attempt-1：ingestion batchId=**11**，**1011 accepted / 0 quarantine / 0 error / noNewData=false**（455814 bytes，单文件）；
- attempt-3：ingestion batchId=**12**，**1011 accepted / 0 quarantine / 0 error**（同文件同字节）；

Pipeline 使用新鲜 idempotency key（`stage7-stage7q1_20260918_152245-attempt-20260919_…`），runId **9**（attempt-1）/**10**（attempt-3），未复用 runId 5/8。meta/metric 双库为 runId 域内**复用**（非重建；幂等 prep 以进程内新生成口令重置 run 账号，口令只走 PowerShell Process env 不落盘——计划头部「口令通道注记」与 T-R1 先例一致）。3306 全程未触碰；8091 每次预检空闲；质量阈值未改；无 Fake executor。

## 2. attempt-1（11:12–11:31）：dqc 0-CPU 挂起（无 jstack，签名归因）

平台 PID 49196 全程存活（`hasExited=false`，pollErrorCount=0）。pipeline runId=9 真实推进：INIT_SCHEMA / LOAD_ODS / BUILD_DWD(dim,bdw,tdw) / BUILD_DWS / BUILD_ADS 共 **7 个作业全部 SUCCESS**（16–24s/作业，11:20:51–11:23:02），QUALITY_CHECK 的 dqc 于 11:23:02.540 提交（external `lp-1789788182531-a8f1fe`）后**再无任何进展**：进程存活 5.5 分钟、总 CPU 仅 **5.8s**、RSS 242MB（正常同环境 20–30s 完成，总 CPU 数十秒）。600s 管线预算于 ~11:30:51 耗尽，harness 记 `PIPELINE_TIMEOUT`、按 PID+后代枚举精确清理 49196 及 [51944,63120,56556,43128,41260]、恢复分支、exit 7——**harness 行为正确**。

当时 watcher（jstack 诊断器）尚未部署，故此挂起无线程转储；但其签名（JVM 存活、~5–6s CPU 后冻结、作业日志永不出盘）与 attempt-3 已定位的挂点完全一致，按同签名归因（见 §4）。

## 3. attempt-2（11:38–11:41）：平台 JVM 出生即被 CTRL_C 杀死（瞬时环境信号）

平台 PID 63308 于 11:39:16 spawn，证据采样 lastAliveAt=11:39:17（工作集 2.8MB），`platform.log` 最后一行为 11:39:18.932 的 Spring 启动横幅+「Starting AnalyticsApplication…PID 63308」，**`platform.log.err` 为 0 字节**，`exitCode = -1073741510`（0xC000013A `STATUS_CONTROL_C_EXIT`）。即 JVM 在初始化极早期被控制台 CTRL_C 中断杀死，应用自身零异常、零 stderr 输出；未创建任何 pipeline。

**非可复现证明**：attempt-3 在同机、同脚本、同并发条件（watcher 在场）下平台顺利通过出生期并存活至超时收口（见 §2 表），故 attempt-2 归类为**瞬时环境信号**，不构成可登记的复现缺陷；无任何项目/harness 代码改动介入两次尝试之间。

## 4. attempt-3（11:56–12:06）：INIT_SCHEMA jar 自下载回环 stall（jstack 铁证）

平台 PID 35588 健康（超时收口时 WS 453MB、pollErrorCount=0、`hasExited=false`——最后一次采样在清理前）。ingestion batchId=12 成功后 pipeline runId=10 于 11:56:12.505 进入 INIT_SCHEMA，spark-submit 提交 driver JVM（pid 58920，`D:\Develop\JAVA17\bin\java.exe`）。**该 JVM 再未完成 SparkContext 初始化**。

并行 watcher（`target/v25-it/stage7q1_20260918_152245/diag-tr2/watcher.log`）以 5s 采样捕获全过程并取得 3 份 jstack：

- `jstack-58920-115735.txt`（11:57:35，挂起 ~30s 判定后）：main 线程 `RUNNABLE`，卡在
  `SocketDispatcher.read0` ← `SocketChannelImpl.read` ← `NettyRpcEnv$FileDownloadChannel.read(NettyRpcEnv.scala:420)` ← `Utils.copyStream/downloadFile/doFetchFile/fetchFile` ← **`Executor.updateDependencies(Executor.scala:1155)`** ← `Executor.<init>` ← `LocalSchedulerBackend.start` ← `SparkContext.<init>` ← `SparkSessionFactory.create(SparkSessionFactory.scala:23)` ← `JobRunner`——即 **local 模式 executor 正在经回环 TCP 从 driver 自身文件服务器下载 job jar，native read 永久阻塞**。项目作业逻辑（JobRunner 分发之后）一行未执行。
- `jstack-58920-120415.txt`（12:04:15）：main 线程**仍在同一帧** `read0`，线程 CPU 计数 **1546.88ms 与 11:57:35 完全相同**——6 分 40 秒零进展（watcher 11:57:51 的 "resumed" 为 jstack attach 自身噪声，被两次 dump 的 CPU 计数证伪）。
- `jstack-58920-120512.txt`（12:05:12）：同帧仍阻塞（totalCPU 5.2s，增量同样仅 attach 噪声量级）。

netstat 快照：58920 仅有 4040（Spark UI）与 127.0.0.1:8574 两个 LISTENING，**无任何 ESTABLISHED 套接字**——阻塞中的下载连接对端已不存在而 read 永不唤醒。600s 预算 12:06:12 耗尽，harness 记 `PIPELINE_TIMEOUT`（stage=INIT_SCHEMA）、精确清理 35588 及 [73316,90404,87360,82224,58920]、exit 7。

## 5. 环境归因证据链（§6 分类依据）

1. **挂点在 Spark 框架层，项目代码未执行**：挂起位于 `SparkContext` 初始化的依赖下载（框架内建 local 模式回环自下载），早于任何本项目作业逻辑；被测 SHA `1ae091b` 的 diff 仅触及 `MetricExportJob` 导出分支与 `AdsQualityJob` 规则语义，不触及提交/初始化路径。
2. **间歇性（随机命中，非确定性）**：T-R1（今晨 09:49，SHA `81d8f93`，同命令形态、同 DB 对）7 作业 + dqc + pub 全 SUCCESS；attempt-1（11:20）前 7 作业 SUCCESS、第 8 个（dqc）挂；attempt-3（11:56）第 1 个作业即挂。今日平台链内 16 次 JVM 作业提交 2 次挂起（~12%/作业）。
3. **主机网络配置异常**：`netsh int ipv4 show dynamicport tcp` = **1024–15000**（Windows 默认 49152–65535）——Spark 全部随机端口落入低位拥挤区间；排除区间 5357/27339/50000-50059 本身不覆盖该范围，非直接原因。
4. **裸环境对照**：`spark-submit --class SparkPi --master local[2]`（同 JDK17、同 spark-submit.cmd）**9/9 全过（2–3s/次）**。对照不构成统计显著区分（若单 JVM 挂起概率 ~10%，9 连过概率 ~31%），仅证明裸提交当前可用；平台链内挂起签名与裸跑成功路径的差异（JVM 启动环境、并发量）未深入——归因到「主机回环网络状态 × Spark jar 自下载无超时」层面已足以支撑 BLOCKED_ENV。
5. **harness 无责**：三次 attempt 的预检、证据落盘、进程树精确清理、分支恢复、exit 7 全部按设计工作（attempt-2 的平台死亡亦被证据完整记录）。

## 6. 影响与边界

> ⚠️ 本节及 §7 是**第一次登记（3 次 BLOCKED_ENV）时**的状态快照；2026-09-19 13:07 执行环境修复 A 后复跑（attempt-4）已推翻「修复未被执行到」的表述并产生新 production FAIL，以 §9–§12 为准。

- T-R2 **未 PASS**：PUBLISH_METRIC 及其 0 行空态导出判据（manifest rowCount=0/checksum="0"、MP_* 对账）**从未被执行到**——修复既未被证实也未被证伪（developer tests 8/8 与三档回归已覆盖其逻辑面）。
- T-R1 事件 `FAIL_PRODUCTION_RUN_PUBLISH_FAILED` **保持开放**（其 §7 关闭条件 = T-R2 PASS，未满足）；BATCH-U 不开放。
- VERIFY_CURRENT_BATCH 保持开放，待环境修复后复跑 T-R2（编排脚本与钉定契约不变）。
- 边界不变：3306 未触碰、未改 V1~V28、未改质量阈值、未 force push；未把任何读取失败当空表（本次根本没有执行到 mxp）。

## 7. 修复选项（供总控/用户裁决，均未执行）

- **A. 恢复主机默认动态端口范围**：`netsh int ipv4 set dynamicport tcp start=49152 num=16384`（管理员；改主机网络配置，需明确批准，重启后需复核；该配置为持久设置，当初为何被改为 1024 起始未知，需一并排查是否有应用依赖）。
- **B. 平台 spark-submit 钉定端口**：如 `spark.driver.port`/`spark.port.maxRetries` 等显式 conf——改被测系统行为，需设计与总控批准（且钉死端口若本身落在坏区间，会把间歇性变成确定性，风险更高）。
- **C. 重试策略**：按 ~12%/作业挂起率，全链 9 作业成功率约 ~30–50%/attempt（每次 ~10 分钟）——统计上低效，且即使 PASS 其说服力弱（验证结果受运气支配）。
- **推荐**：A（一次性、影响面为环境而非被测系统），修复后直接复跑 T-R2。

## 8. 证据文件清单

- attempt-1：`target/v25-it/stage7q1_20260918_152245/localfile-e2e/attempt-20260919_112031_598/`、`http/attempt-20260919_112032_022/`（stage7-*-result.json、logs/platform.log）
- attempt-2：`http/attempt-20260919_113916_916/`（evidence `platform.exitCode=-1073741510`、`logs/platform.log` 845B、`platform.log.err` 0B）
- attempt-3：`localfile-e2e/attempt-20260919_115603_058/`、`http/attempt-20260919_115603_407/`
- jstack/watcher：`target/v25-it/stage7q1_20260918_152245/diag-tr2/`（watcher.log、jstack-58920-{115735,120415,120512}.txt）
- 编排与诊断脚本（临时）：`%TEMP%\tir2-exec.ps1`、`%TEMP%\tir2-watch.ps1`（不入库）

## 9. 环境修复 A 执行记录（2026-09-19 13:07，复跑前置）

- 执行前取证：IPv4/IPv6 动态端口范围均为 **start=1024, num=13977**（异常，非 Windows 默认）；残留进程干净（无 Spark JVM/平台，8091 空闲）；
- 经 UAC 提升执行 `netsh int ipv4 set dynamicport tcp start=49152 num=16384` 与 IPv6 同命令，验证生效：v4/v6 均变为 **start=49152, num=16384**（恢复 Windows 默认 49152–65535）；
- **回滚命令**（如需）：`netsh int ipv4 set dynamicport tcp start=1024 num=13977`（IPv6 同）；当初为何被改为 1024 起始仍未查明，如复跑后再挂起需排查是否有应用依赖低位临时端口；
- 修复脚本（临时）：`%TEMP%\dyport-fix.ps1`、结果 `%TEMP%\dyport-fix-result.txt`（不入库）。

## 10. attempt-4（修复 A 后复跑，13:26–13:31）：全链首次跑到发布第二步，production FAIL

编排/watcher 同通道（watcher=`watcher-rerun.log`，13:26:38 启动）。producer handoff 第 4 次成立：ingestion batchId=**13**，**1011 accepted / 0 quarantine / 0 error**（455814 bytes）。

**环境挂起未复现**：13:27:39–13:30:12 共 **12 个 Spark JVM 连续提交并全部退出**，watcher 零 HUNG-CANDIDATE（对照修复前 ~12%/作业挂起率）。

pipeline runId=**11**（attempt `attempt-20260919_132732_589`，平台 PID 82136 全程存活 `hasExited=false`、pollErrorCount=0）：

1. WAIT_LANDING(1011)/INIT_SCHEMA(37)/LOAD_ODS(1011)/BUILD_DWD(294)/BUILD_DWS(0)/BUILD_ADS(46)/QUALITY_CHECK(10) 七阶段全 SUCCESS；
2. **pub 作业 SUCCESS，四条 PUB_* 检查全 passed**：`PUB_STAGING_READY`（8 张暂存分区 46 行，点名 0 行专题 ads_hot_product/ads_product_conversion）、`PUB_FORMAL_PARTITION_MATCH`、`PUB_POINTER_SWITCH`（切换 8 张）、`PUB_STAGING_PRUNE`；
3. **mxp 作业 SUCCESS（D-020 声明范围实链证实）**：46 进/46 出/0 rejected，快照 S20260918_11，8 张 outputPartitions 中 `ads_hot_product rowCount=0`、`ads_product_conversion rowCount=0`；`MXP_EXPORT_ROWS` 逐表对账含 **`ads_hot_product_m: hive=0 export=0`**、**`ads_product_conversion_m: hive=0 export=0`**（真实空态导出，非跳过），非 0 行 6 表 hive=export 全对账，`MXP_EXPORT_COMPLETE` 合计 46 行/8 表 passed；全程无 UNABLE_TO_INFER_SCHEMA；
4. MetricPublisher 成功消费 manifest：快照 S20260918_11 登记 BUILDING，写入 ADS 宽表 42 行（overview 1 / sale_trend 1 / funnel 4 / active 1 / user_profile 35）；
5. **失败点**：`ads_data_quality_m` 批量 INSERT 抛 `DataIntegrityViolationException: Column 'error_rate' cannot be null`（`MetricAdsWriter.insertRows(MetricAdsWriter.java:136)`）——事务回滚删除已写入 42 行，快照发布失败 `MP_ADS_WRITE`，pipeline 11 终态 FAILED（errorCode=`RUN_METRIC_PUBLISH_FAILED`），harness exit 7、平台被精确清理、分支恢复。

**根因（DDL 铁证）**：`db/metric/V3__metric_ads_r7.sql` 历史已发布迁移定义 `error_rate DECIMAL(12,6) NOT NULL DEFAULT 0`；而 D-019 v2 的合法空态语义明确 0 行专题输出 `0/0/passed=1/error_rate=NULL` 行（0 错误/0 检查，误差率数学上未定义）。该 NULL 行经 dqc→ads_data_quality 暂存→mxp 导出→MetricPublisher 到达 `MetricAdsWriter` 时被 MySQL NOT NULL 约束拒绝。**该路径此前从未被任何实链触达**：Batch T（平台亡于 BUILD_DWS）、T-R1（mxp 先死于 schema 推断）、R4（50 行 fixture 八表全非空、无 NULL 行）。

## 11. 分类更新（§6 口径，attempt-4 终态）

- **production FAIL**：被测代码执行至真实业务终态失败；非 BLOCKED_ENV（环境挂起未复现，12/12 作业完成），非 harness issue（预检/落盘/清理/分支恢复全程正确）；
- **D-020（`1ae091b`）的修复声明成立**：mxp 0 行空态导出 + manifest 消费 + 非 0 行表不回归，全部在实链证实——T-R1 所指缺陷（mxp `UNABLE_TO_INFER_SCHEMA`）**已被实链证实修复**；但按计划 §7 事件关闭条件 = T-R2 PASS，门整体未 PASS，T-R1 事件的正式处置（关闭/转移）留总控裁决；
- **新缺陷接棒为当前验证门失败点**：metric 库 schema 与 D-019 合法空态语义的冲突（`MP_ADS_WRITE`）；
- 环境修复 A 效果：12 次 JVM 提交 0 挂起；单次全绿非统计铁证（原挂起率下 12 连过概率 ~24%），机制面（低位拥挤区间碰撞面移除）一致，后续批次继续观察。

## 12. 修复建议（供总控/用户裁决，均未执行）

- **D-021 候选（推荐）**：追加式 metric 迁移 `V11__ads_data_quality_error_rate_nullable.sql`——`ALTER TABLE ads_data_quality_m MODIFY error_rate DECIMAL(12,6) NULL DEFAULT NULL`，使 metric 库 schema 与 D-019 合法空态语义对齐；不改已发布 V1~V10（追加式，与 V29 先例同型）；配套 developer/IT 测试（含 NULL 行真实 MySQL 插入用例，归 isolated 档）；
- 备选 B：`MetricAdsWriter` 写入时 NULL 强转 0.0——**不建议**：0/0 ≠ 0.0，属伪造语义，违背 D-019 明文裁决；
- 备选 C：改 dqc 空态行输出 0.0——同 B，需总控推翻 D-019 既有裁决才可行；
- 批准后通道：修复 + 测试 + 三档回归 → 新门 **T-R3**（同钉定契约复跑：PUBLISH_METRIC 全链 SUCCESS + harness exit 0 为 PASS 判据；PASS 后按计划开放 BATCH-U）。

attempt-4 证据文件：`target/v25-it/stage7q1_20260918_152245/localfile-e2e/attempt-20260919_132732_187/`、`http/attempt-20260919_132732_589/`（`stage7-http-result.json`、`logs/platform.log` 65–131 行、`landing/logs/pipeline-11-PUBLISH_METRIC-{pub,mxp}-a1_*.log`）、`diag-tr2/watcher-rerun.log`。
