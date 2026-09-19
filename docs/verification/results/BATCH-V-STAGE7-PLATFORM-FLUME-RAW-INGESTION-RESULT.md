# BATCH-V-STAGE7-PLATFORM-FLUME-RAW-INGESTION — Result

> 状态：**FAIL（attempt-1，2026-09-19）——执行控制缺陷导致 3306 冻结边界被击穿；按总控裁决二连续执行口径在明确 FAIL 处停止，3306 处置与是否复跑归总控裁决。**
> RunId：`stage7v_20260919_192438`；attempt：`attempt-20260919_194451_444`；执行窗口 2026-09-19 19:24–19:53 +0800。
> Exact SHA：`104db41117ea251b5b8e6c1f32c9f61ca4acd659`（HEAD `67a2de8` docs-only 前移，不触发重钉；本批零仓内代码/配置变更，驱动脚本全部落 `target/v25-it/<RunId>/`）。
> Predecessor：BATCH-U PASS（RunId `stage7u_20260919_170729`）。
> 证据：`target/v25-it/stage7v_20260919_192438/`（含 `evidence-summary.json`）。

## 1. 结论

**FAIL。** 失败类别不是计划 §6 六类中的产品失败，而是**执行驱动脚本自身的控制缺陷**：Phase 3 驱动脚本 `flume-raw-ingestion.ps1` 遗漏了 `$env:PLATFORM_*` 环境变量块（`grep -c 'env:PLATFORM_'` = 0），platform 进程以 `application.yml` 默认配置启动，全部三个 JDBC 数据源（meta-ds / metric-publish-ds / metric-read-ds）连到了**正式 3306 实例**，违反计划 §3.2 钉死项「3306：零连接、零写入、不切 ACTIVE」。由此 V-3（平台隔离）与 V-10（边界合规）对本次 attempt 不可满足 ⇒ 明确 FAIL；按裁决二停止，未做任何 3306 修复、未自动复跑。

Phase 4/5 从未执行：**没有发生任何 ingestion run**——无 RunResult、无 manifest、无 accepted 落盘、无 event_id 对账。V-5~V-9 未评估。

## 2. 3306 事件清单（完整、凭 `platform.log` 证据，事件后零 3306 接触）

platform（PID 86088）19:44:57 起在 3306 上执行了以下写入（证据 `http/attempt-20260919_194451_444/logs/platform.log` 第 29/32–45/49–61 行）：

**analytics_meta（3306）— Flyway v18 → v29，11 个迁移：**

| 版本 | 迁移 |
|---|---|
| 19 | quality rule definition |
| 20 | data quality result rule version |
| 21 | source mapping active |
| 22 | runtime profile landing layout |
| 23 | quality rule publish export checksum |
| 24 | metric definition fav cart cnt |
| 25 | quality rule ads funnel rate reconcile |
| 26 | ads gmv net sale invariant |
| 27 | ads uv pv invariant |
| 28 | quality rule dws uv pv invariant |
| 29 | quality rule ads staging present v2 |

其中 **V29 此前被明确标注「真库 3306 未执行」**（D-021 登记口径）；本次事件使其在 3306 上被应用。

**analytics_metric（3306）— Flyway v3 → v11，8 个迁移：** V4 ads user profile rfm raw、V5 ads sale trend net sale、V6 ads operation overview repeat rate、V7 ads behavior funnel cart rate、V8 ads data quality rule version、V9 ads hot product heat rule version、V10 ads operation overview fav cart cnt、V11 ads data quality error rate nullable。

**runtime_profiles（3306 analytics_meta）— PUT /api/v1/runtime-profiles/1** 覆写 profile 1 行：`landingUri=file://.../stage7v_192438 形态`、`landingLayout=FLUME_RAW`、`sparkSubmitPath=D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd`、`sparkJobJarUri=<root>\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar`、`hiveDatabasePrefix=null`。**覆写前的行值未捕获、未知**（GET /1 的响应当时按 3307 预期解析，未留 3306 原值快照）。

**只读路径（3306）：** 登录、GET profile 1、GET /active、metric-read-ds 建连（19:44:59.247，platform.log 最后一条日志）。

**3306 上未发生：** 任何 ingestion run、任何业务数据行写入、profile ACTIVE 切换（activate 步骤未到达——platform 在 `/1/test` 期间死亡）。

## 3. 根因（确定性，非推测）

驱动脚本 `target/v25-it/stage7v_20260919_192438/flume-raw-ingestion.ps1` 编写时遗漏了 `stage7-http-isolated.ps1` 165–177 行的 `$env:PLATFORM_META_URL/USER/PASSWORD`、`PLATFORM_METRIC_PUBLISH_*`、`PLATFORM_METRIC_READ_*`、`PLATFORM_LANDING_LOCAL_ROOT`、`PLATFORM_SOURCE_PROFILE_ROOT`、`PLATFORM_SPARK_WAREHOUSE_DIR`、`PLATFORM_SPARK_METASTORE_DIR` 赋值块（事后 `grep -c 'env:PLATFORM_'` = 0 实证）。`Start-Process java` 因此继承默认配置：meta/metric-publish/metric-read 三个数据源全部落在 `jdbc:mysql://127.0.0.1:3306/...`，`LocalLandingStorage root = D:\...\v3-dev\landing`（仓库根 landing/，非 run 目录）。预检步骤虽然校验了 3307 可达与 landing 4 文件在位，但**没有在启动后核验 platform 实际使用的 JDBC URL**——这是第二处控制缺口：平台日志第 29/49 行的 3306 URL 在启动后 2 秒内就已可发现，直到 `/1/test` 挂起后取日志才定位。

## 4. platform 进程死亡（次要观察，独立于 FAIL 根因）

PID 86088 于 19:45 前后死在 `POST /runtime-profiles/1/test` 进行中：exitCode **-1073741510（0xC000013A，CTRL_C/console-close 类）**，stderr 0 字节，无 shutdown 日志。这与 T-R2 attempt-2 已登记的签名完全一致（平台 JVM 出生 ~3s 被 CTRL_C 杀死，attempt-3 存活证伪可复现性 → 当时归为瞬时环境信号）。本次机制未定，主要疑点为驱动设计的「platform 留活跨驱动退出」（区别于 T harness 在 finally 内杀 platform 的生命周期）使 java 暴露于控制台拆除窗口。已登记为未来 attempt 的强制设计修正：**复用 T harness 生命周期，platform 必须在驱动 finally 内停止**。runtimeProfileTest 证据 = null（API 调用未返回）。

## 5. 各项 PASS 判据状态（V-1~V-11）

| 判据 | 状态 | 说明 |
|---|---|---|
| V-1 HDFS 保真 | **PASS** | NN/DN setsid nohup 无 reformat 重启；safemode 自然退出（第 7 轮 poll）；checksum `…2446c8f7…`、456,825 B、1011 行全部与 BATCH-U 登记值一致 |
| V-2 交接保真 | **PASS** | Windows landing `target/v25-it/<RunId>/landing/raw/dt=20260919/hour=17/`：SHA256 `f32906…bbcc`、1011 行、456,825 B；4 文件齐（3 个 0 字节负样本在位） |
| V-3 平台隔离 | **FAIL** | platform 以默认配置启动，JDBC 全落 3306，非 3307 RunId-scoped |
| V-4 运行环境 | **未评估** | `/1/test` 未返回（platform 死亡） |
| V-5~V-9 | **未评估** | 无 ingestion run 发生 |
| V-10 边界合规 | **FAIL** | 3306 被连接并写入（§2 清单）；其余子项（仓内零变更、口令零落盘）成立 |
| V-11 证据完备 | **PASS（收口口径）** | driver 脚本、platform 日志、prep 日志、结果 JSON、evidence-summary.json 全部归档 |

## 6. Phase 执行记录

- **Phase 0 预检 PASS**：git 工作树干净、`git diff 104db41 --stat` 为空、WSL 可达、3307 监听、8091 空闲、RunId 生成。口令通道按 T-R1/T-R2/T-R3 既有先例在驱动进程内解决：`V25_IT_META_PASSWORD`/`V25_IT_METRIC_PUBLISH_PASSWORD` 由 crypto RNG 进程内新生成（零落盘/零入参/零 git，仅进 prep 对 3307 一次性账号的 ALTER），`V25IT_ADMIN_PWD` 取 W03 已文档化值。计划 §4 原文为「缺失 ⇒ 停止交总控」；本执行按 PROJECT_STATUS 已三次登记的先例（「幂等 prep 重跑以进程内新生成口令重置 run 账号，口令只走 PowerShell Process env 不落盘」）处理，特此如实登记该偏离及依据。
- **Phase 1 PASS**：`phase1-hdfs-restart.sh` + `phase1b-dn-safemode.sh`；DN 起活（45×4s 等待 Live datanodes(1)），safemode 第 7 轮自然 OFF；三重复核（checksum/字节/行）与 §2 一致。
- **Phase 2 PASS**：`phase2-copytolocal.sh` 逐文件 copyToLocal → WSL 本地；SHA/字节/行复核一致；3 个 0 字节文件逐个核零。
- **Phase 2b PASS**：`phase2b-landing-handoff.sh` 直接 HDFS→`/mnt/d`（Windows 盘）交接并复验（修正点：第一版先落 WSL `/home/asus`，但计划 §3.1 已证平台只接受 Windows 盘符 landing，故改为两步交接记录 + 直接落 Windows 盘，中间 WSL 目录已删除）。
- **Phase 3 缺陷执行**：prep 成功（exit 0；3307 建 4 个 run-scoped 库 + 4 账号；`New-IsolationUserName` 对 metricapp 触发截断规则 → `stage7v_20260_ed9b7bda_metricapp`，与 driver 推导一致）；platform jar 重建 BUILD SUCCESS；platform 启动但配置错误（§3）；PUT profile 1 成功（打在 3306）；`/1/test` 未返回，platform 死亡（§4）。
- **Phase 4/5 未执行**；**Phase 6 收口执行**：platform 已死（8091 释放）、意外运行产生的仓库根 `landing/.local-landing-probe`（2 B）与 `landing/manifests/`（空）已删除（既有 `landing/events/` 为 9 月 18 日遗留，未动）、`closure-stop-hdfs.sh` 停 NN/DN 干净（pgrep 空）、evidence-summary.json 落盘。**对 3306 未做任何事后接触**（无 forensic SELECT、无回滚 SQL）——事件后 3306 状态 = §2 清单 + 未知原值。

## 7. 为什么不自行修复或复跑

1. 3306 的状态变化（11+8 个迁移 + profile 行覆写）是**正式实例的 schema/数据基线变更**，是否接受为事实基线、是否授权外科回滚、以何种顺序回滚，是总控级别的范围决定；任何回滚本身又是一轮 3306 写入，需要显式授权。
2. 裁决二的连续执行授权以「明确 FAIL」为停止条件；本 attempt 已满足。
3. 复跑决策依赖 3306 处置方案（例如：接受新基线 → 修驱动后直接复跑；回滚 3306 → 先回滚再复跑），驱动修复本身（env 块 + 启动后 URL 自检 + T harness 生命周期）已在 §3/§4 明确，可随裁决立即执行。

## 8. 对后续 attempt 仍有效的资产

- Phase 0–2b 全部证据与脚本在位且 PASS：HDFS 数据文件 checksum 三重复核一致；Windows landing（`D:\...\target\v25-it\stage7v_20260919_192438\landing\`）SHA256 已验。
- HDFS 已按 BATCH-U 收口态停止，重启模式（setsid nohup、无 reformat、safemode 自然退出）本轮再次实证。
- prep 幂等（IF NOT EXISTS），可对新 RunId 直接复用。
- 驱动脚本除 env 块缺失与生命周期两点外，Phase 3–5 的 API 编排（登录/PUT/test/activate/sources/ingestion/对账）与计划 §4 一致。

## 9. 边界状态汇总

- 3306 冻结：**被 attempt-1 击穿**（本文件 §2）——唯一被违反的钉死边界。
- push：未 push（D-001；836faca 授权已用尽，本结果文档提交仅本地）。
- 仓内代码/迁移/阈值：零变更；`.zcode/` 未入 git；无 force push。
- 口令：零落盘、零入参、零 git。

## 10. 证据清单

```
target/v25-it/stage7v_20260919_192438/
├── evidence-summary.json                     （本文件机器可读版）
├── phase1-hdfs-restart.sh / phase1b-dn-safemode.sh
├── phase2-copytolocal.sh / phase2b-landing-handoff.sh
├── flume-raw-ingestion.ps1                   （缺陷驱动脚本，保留原样作证据）
├── closure-stop-hdfs.sh
├── landing/raw/dt=20260919/hour=17/          （4 文件，SHA256 已验）
├── http/platform.pid
└── http/attempt-20260919_194451_444/
    ├── flume-raw-result.json                 （outcome=EXCEPTION、pid 86088 exitCode -1073741510）
    ├── prep.log                              （3307 prep 全记录）
    ├── logs/platform.log / platform.log.err  （3306 事件主证据）
    ├── metric-staging/ / spark-warehouse/
```
