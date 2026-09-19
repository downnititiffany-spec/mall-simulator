# BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Controller Review

> Overall: **BLOCKED_ENV**（按计划 §6 分类 = 环境基础设施故障；非 production FAIL、非 harness issue。T-R2 PASS 判据未达成也未被证伪——被测 mxp 0 行导出修复在三次尝试中**均未被执行到**（挂起发生在框架层，早于/绕过 PUBLISH_METRIC），T-R1 事件 `FAIL_PRODUCTION_RUN_PUBLISH_FAILED` 因此**保持开放**，VERIFY_CURRENT_BATCH 保持开放）
> Exact tested SHA: `1ae091b2b118df03632a799ffb9fb664f19a3eda`（D-020 mxp 合法 0 行 ADS 导出修复；detach 后 `mvn -DskipTests package` 重建两 JAR，branch 在 finally 中恢复 `feature/v3-development`）
> RunId: `stage7q1_20260918_152245`
> Attempts: **3**（2026-09-19 11:12–12:06，全部 harness exit 7）
>
> | # | outer attempt | http attempt | platform PID | outcome | 失败点 |
> |---|---|---|---|---|---|
> | 1 | `attempt-20260919_112031_598` | `attempt-20260919_112032_022` | 49196（被 harness 正常清理） | `PIPELINE_TIMEOUT` | QUALITY_CHECK：dqc JVM 0-CPU 挂起 |
> | 2 | —（未进入 e2e） | `attempt-20260919_113916_916` | 63308 | `EXCEPTION / 平台未就绪` | 平台 JVM 出生 ~3s 被 CTRL_C 杀死（瞬时环境信号，attempt-3 同条件证伪"可复现"） |
> | 3 | `attempt-20260919_115603_058` | `attempt-20260919_115603_407` | 35588（存活至超时被清理） | `PIPELINE_TIMEOUT` | INIT_SCHEMA：driver JVM jar 自下载回环 stall（**jstack 铁证**） |

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
