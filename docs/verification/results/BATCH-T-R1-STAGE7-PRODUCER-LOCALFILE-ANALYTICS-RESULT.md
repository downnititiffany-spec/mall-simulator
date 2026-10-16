# BATCH-T-R1-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Controller Review

> Overall: **FAIL_PRODUCTION_RUN_PUBLISH_FAILED**（按计划 §6 分类 = production FAIL；非 BLOCKED_ENV、非 harness issue、平台未再退出）
> Exact tested SHA: `81d8f938deb3a5e73e75e1e7bbc3082dd90585b8`（T-R1 前置修复 ADS_STAGING_PRESENT v2 / D-019）
> RunId: `stage7q1_20260918_152245`
> Outer harness attempt: `attempt-20260919_094931_381`（outcome=`ANALYTICS_CHAIN_FAILED`）
> HTTP/analytics attempt: `attempt-20260919_094931_830`（pipeline `runId=8`）
> Harness exit: **7**

## 1. Exact input handoff（与 Batch T 完全同源）

- producer attempt = `attempt-20260918_195657_077`（S-R1 钉死文件，只读复用）；
- producer rolling SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`；
- sourceLineCount = **1011**；LocalFile ingestion batchId = **10**：recordCount **1011** / quarantine **0** / error **0** / noNewData **false** / acceptedBytes 455814。

1011 行真实 producer rolling → LocalFile ingestion handoff 对同一物理文件再次成立。

## 2. Platform 全程存活（T-R1 诊断目标达成）

platform Spring Boot PID = **39964**，正常监听 8091：

- `platform.hasExited = false`（全运行期），`exitCode = null`；
- pollErrorCount = **0**（未发生任何一次 poll 失败，无需瞬态重试）；
- last working-set ≈ 393 MB；管线终态 FAILED 后 harness 按 PID+后代枚举仅停止 39964 与其后代 51344，未扫杀其它 Java。

**Batch T 的 platform 消失未复现**：本次 BUILD_DWS 真实跑完（SUCCESS），平台一路存活到 pipeline 自行进入可诊断终态。`BLOCKED_PLATFORM_EXIT_UNDIAGNOSED` 被一个有精确错误码、有完整日志链的生产终态失败取代。harness 诊断增强（`2acee3d`）按设计工作。

## 3. 链路真实推进（v2 修复在真实链上被验证）

Pipeline `runId=8`（attemptNo=1，idempotencyKey `stage7-stage7q1_20260918_152245-attempt-20260919_094931_830`）逐阶段：

| stage | status | records |
|---|---|---|
| WAIT_LANDING | SUCCESS | 1011 |
| INIT_SCHEMA | SUCCESS | 37 |
| LOAD_ODS | SUCCESS | 1011 |
| BUILD_DWD | SUCCESS | 294 |
| BUILD_DWS | SUCCESS | 0（usw 作业不回填 stage 计数；DWS 实际有行——BUILD_ADS 从 DWS 产出 46 行） |
| BUILD_ADS | SUCCESS | 46 |
| QUALITY_CHECK | SUCCESS | 10 |
| PUBLISH_METRIC | **FAILED** | 46，errorCode=`RUN_PUBLISH_FAILED` |

**v2 修复（D-019）的声明被本次实链证实**：QUALITY_CHECK SUCCESS——Batch T 卡死点解除。pub 作业 SUCCESS（46→46），写出 8 张 `__staging` 暂存分区（ads_active_trend 1 / ads_behavior_funnel 4 / ads_data_quality 4 / **ads_hot_product 0** / ads_operation_overview 1 / **ads_product_conversion 0** / ads_sale_trend 1 / ads_user_profile 35），四条发布检查全部 passed：

- `PUB_STAGING_READY`：「8 张暂存分区就绪，合计 46 行；0 行专题=ads_hot_product__staging,ads_product_conversion__staging」（v2 的合法空态语义真实生效）；
- `PUB_FORMAL_PARTITION_MATCH`：8 张正式分区行数与暂存一致（合计 46 行）；
- `PUB_POINTER_SWITCH`：本次切换 8 张（Hive 正式分区指针指向本次暂存路径，同快照重放 0 张）；
- `PUB_STAGING_PRUNE`：dt=20260918 无待清理历史暂存分区。

## 4. 失败点：mxp（metric 导出作业）`UNABLE_TO_INFER_SCHEMA`

PUBLISH_METRIC 含两个作业：pub（SUCCESS，见上）与 **mxp（FAILED，进程退出码 1）**。

platform.log 第 68–69 行原文：

```
stage PUBLISH_METRIC fail-fast: job mxp 失败（进程退出码非0(exit=1): [UNABLE_TO_INFER_SCHEMA] Unable to infer schema for Parquet. It must be specified manually.），不再提交剩余作业 []
pipeline 8 failed: 正式分区发布失败: mxp: 进程退出码非0(exit=1): [UNABLE_TO_INFER_SCHEMA] Unable to infer schema for Parquet. It must be specified manually.
```

mxp JobResult：`inputRecords=0, outputRecords=0, attemptNo=1, status=FAILED, elapsedMs=12770`（导出尚未开始即失败）。

**物证**（attempt `attempt-20260919_094931_830` spark-warehouse 实读）：

- `dw_ads.db/ads_hot_product__staging/` 仅有 `_SUCCESS`（+`.crc`），**没有任何 parquet 文件**（0 行写入的合法形态）；
- `dw_ads.db/ads_product_conversion__staging/` 同样只有 `_SUCCESS`；
- 其余 6 张表分区均有真实 parquet 文件。

**代码路径**（被测 SHA `81d8f93` 实码）：`MetricExportJob.scala:55` 用 `PartitionEvidence.collect` 收集 8 张正式表分区；`:75-78` 按 `MetricAdsSpec.TABLES` 顺序逐表 `spark.read.parquet(loc)` 直读路径。直读空目录（仅 `_SUCCESS`）时 Spark 无法从文件推断 schema，抛 `UNABLE_TO_INFER_SCHEMA`。按 TABLES 迭代顺序（overview→sale_trend→funnel→active_trend→**hot_product**→product_conversion→user_profile→data_quality），第一个 0 行表是 `ads_hot_product`，即首个触发读失败的位置（mxp 日志未回显具体路径，此处为目录物证 + 迭代顺序的结构性定位）。

**根因链**：D-019 v2 让「0 行合法空态」首次通过 QUALITY_CHECK 并完成发布指针切换 → 该流程第一次触达 mxp → mxp 对 0 行分区目录没有处理（文件级 schema 推断失败）。这是 v2 打开的合法通道暴露出的**既有下游缺口**，不是 v2 引入的逻辑回归；v1 时代 0 行在 QUALITY_CHECK 即被误拦，永远走不到 mxp。

## 5. Classification（T-R1 计划 §6）

| 候选分类 | 判定 | 依据 |
|---|---|---|
| **production FAIL** | ✔ **采用** | 平台存活、管线自行进入终态 FAILED、错误码 + 双日志（platform.log / mxp job log）+ 目录物证闭环 |
| BLOCKED_ENV | ✘ | 3307 隔离库、8091、磁盘、内存均正常；无任何环境性失败信号 |
| harness issue | ✘ | poll 无错误、fail-fast 正确捕获、teardown 只停本进程树、`git switch` 干净返回分支 |

Batch T 遗留的「platform 为何消失」问题：本次未复现，且不再阻塞——失败点已推进到 BUILD_DWS 之后的 PUBLISH_METRIC。该事件以「未复现 + 被更下游的可诊断生产失败取代」登记，是否关闭由总控裁决。

## 6. 边界（不得越界表述）

- T-R1 **未 PASS**：PUBLISH_METRIC 未完成 metric 导出，无 metric_value 落库结论，无发布完成结论。
- 本轮全部在 3307 隔离 MySQL；**3306 未触碰**，V29 在真库 3306 仍为「未执行」。
- 不证明 Flume→HDFS、WSL 单节点/REMOTE_CLUSTER 差异、浏览器 E2E、真实 LLM Provider、第二异构源、故障样本（见 `docs/verification/STAGE7-REMAINING-SCOPE-20260919.md`）。
- 未改质量阈值数值、未改 V1~V28、未 push。

## 7. 建议的下一道门（待总控裁决，不自行开工）

1. **新代码工作包**：mxp 0 行分区导出处理（对 0 行表用显式 schema 读或等价空态导出），配 spec 测试 + 三套件回归（default fresh / spark / isolated）；
2. 修复合入并登记后，以同一钉死输入复跑 **T-R2**（当前唯一未证环节：PUBLISH_METRIC mxp 导出 → metric_value 发布完成）；
3. T-R2 PASS 后才开放 BATCH-U（Flume→HDFS，现为 DRAFT，T-R1 关闭前不得置 READY）。
