# BATCH-T-R3-STAGE7-PRODUCER-LOCALFILE-ANALYTICS — Verification Plan

> 状态：**READY**
> Exact source/test SHA：`7850e9b403ec4e83c7b41edced513ccc8562a41f`
> Branch：`feature/v3-development`
> RunId：`stage7q1_20260918_152245`
> Predecessor：Batch T-R2 / production FAIL `MP_ADS_WRITE`（4 次 attempt 最终终态；结果：`docs/verification/results/BATCH-T-R2-STAGE7-PRODUCER-LOCALFILE-ANALYTICS-RESULT.md`，pipeline runId=11）
> 批准依据（2026-09-19）：总控裁决原文「**总控裁决：D-021 APPROVED。按 append-only metric Flyway V11 将 ads_data_quality_m.error_rate 改为 DECIMAL(12,6) NULL DEFAULT NULL；不改 V3、不做 NULL→0 转换、不改变 D-019。补 developer + isolated MySQL IT、跑三档回归，并在 T-R3 前完成 Independent Reviewer。完成后以修复后的精确 SHA 建 T-R3；BATCH-U 继续关闭。先将本地 badbf2a、520d673 推送到 feature/v3-development，再继续。**」前置条件已全部满足：修复（D-021）提交 `7850e9b`（metric append-only 迁移 `V11__ads_data_quality_error_rate_nullable.sql` + developer 迁移脚本测试 **3/3** + isolated 真库 IT `AdsDataQualityErrorRateNullableMySqlIT` **2/2** + run-tests.ps1 基线同步 analytics 1036→1039、隔离 11→13/60→62）；三档回归 default analytics **1039 MATCH** / mall 14 / generator 111（RunId `d021def_20260919_143033`，唯一红=既有 manifest patrol）、spark fresh **320/320** exit 0（`d021spark_20260919_143243`）、isolated fresh runId `d021iso_20260919_145240` **62/62** exit 0；**Independent Reviewer**（governance §1.4/§8、D-004、ADR-0001，钉定 7850e9b）**VERDICT: APPROVE**（9/9 项符合，2 条 LOW 均已在 D-021 落盘时处置）。
> Git 状态注记（D-001）：`7850e9b` 当前仅在本地（push 归 ChatGPT 角色，本裁决只授权了 badbf2a、520d673 的 push，已完成）。T-R3 按裁决以本地修复后精确 SHA 执行；push 待总控授权后补齐，不作为本门执行前置。
> 口令通道注记（与 T-R1/T-R2 相同）：执行前若进程环境口令缺失，由幂等 prep 重跑（`it-prepare-isolation.ps1`，ALTER USER 语义）以进程内新生成口令重置 run 账号，口令仍只走 PowerShell Process env、不落盘不进命令行。

## 1. Purpose

Repeat the exact pinned producer rolling → analytics chain after the `ads_data_quality_m.error_rate` nullable fix (D-021).

T-R2 attempt-4 已证明到 mxp 导出 SUCCESS（含两张 0 行表 catalog-backed 空态导出、manifest 被消费），失败点被精确定位在发布第二步 MP_ADS_WRITE：metric 库历史迁移 V3 的 `ads_data_quality_m.error_rate DECIMAL(12,6) NOT NULL DEFAULT 0` 拒绝 D-019 合法空态语义的 `error_rate=NULL` 质量行——`MetricAdsWriter.insertRows` 抛 `DataIntegrityViolationException`（platform.log 行 76/111/121 `Column 'error_rate' cannot be null`），事务回滚 42 行，pipeline runId=11 终态 FAILED。本门证明修复后全链闭环：

1. V11 在 fresh run-scoped metric 库上随 Flyway 真实应用（isolated IT 已证 `IS_NULLABLE=YES` / `decimal(12,6)` / `COLUMN_DEFAULT NULL`，3307）；
2. D-019 合法空态质量行（0/0/passed=1/error_rate=NULL）经 `MetricAdsWriter.insertRows` 真实入库，MP_ADS_WRITE 通过，不再回滚；
3. 发布侧其余检查（对账/激活/完整性判据）照旧全部通过，PUBLISH_METRIC SUCCESS，harness exit 0。

## 2. Pinned producer input

Do **not** use the mutable producer latest pointer for this rerun.

Pin（与 T-R1/T-R2 完全相同）：

`target/v25-it/stage7q1_20260918_152245/producer/attempt-20260918_195657_077/stage7-producer-result.json`

That evidence already proves:

- lineCount = 1011;
- uniqueEventIdCount = 1011;
- duplicateEventIdCount = 0;
- physical rolling SHA256 = `f329061712b21d322eb6f0bab2f8033b27d3402387d618ebd2136f897f28bbcc`;
- source_system = mock-mall;
- businessDate = 2026-09-18.

## 3. Safety and exact runtime

- 3307 only;
- no 3306 fallback（3306 冻结：零写入、不切 ACTIVE，V11 在真库 3306 的执行状态保持【未执行】）;
- fresh run-scoped analytics meta/metric databases (幂等 prep 可重建；fresh 库上 Flyway 将带上 V11，与 isolated IT 同路径);
- secrets only from current PowerShell Process env；
- 8091 must be free;
- fresh LocalFile attempt / fresh HTTP attempt / fresh Pipeline idempotency key;
- exact analytics platform and Spark JARs rebuilt from the detached T-R3 SHA;
- no quality threshold change;
- no Fake executor;
- no reuse of Pipeline runId 5/8/11.

## 4. Exact execution

Run in the PowerShell process that owns `V25_IT_META_PASSWORD` and `V25_IT_METRIC_PUBLISH_PASSWORD`（缺失时先按 T-R1/T-R2 先例做幂等 prep 重跑，口令只走进程 env）:

~~~powershell
cd "D:\Develop_code\GraduationProject-wt\v3-dev"

git switch --detach 7850e9b403ec4e83c7b41edced513ccc8562a41f
git rev-parse HEAD

mvn -f .\analytics-server\pom.xml -DskipTests package
mvn -f .\spark-jobs\pom.xml -DskipTests package

pwsh -NoProfile -File .\scripts\stage7-localfile-e2e.ps1 -RunId stage7q1_20260918_152245 -ProducerEvidence ".\target\v25-it\stage7q1_20260918_152245\producer\attempt-20260918_195657_077\stage7-producer-result.json" -Confirm

$BatchTR3Exit = $LASTEXITCODE

git switch feature/v3-development

"BATCH_T_R3_EXIT=$BatchTR3Exit"
~~~

## 5. PASS criteria

Same Batch T contract, extended by the D-019 empty-state quality-row assertions:

- physical producer input still 1011 unique / 0 duplicate;
- businessDate = 2026-09-18;
- ingestion recordCount = 1011;
- quarantineCount = 0;
- noNewData = false;
- fresh Pipeline run reaches SUCCESS **through PUBLISH_METRIC**（T-R2 attempt-4 已证到 mxp 导出 SUCCESS；本门必须跨过 MP_ADS_WRITE 到全链终态）;
- quality gate PASS（v2 `PUB_STAGING_READY` 放行 0 行专题照旧；质量语义与阈值不变）;
- mxp 导出 8 表全部完成：0 行表（ads_hot_product、ads_product_conversion）产出真实空 JSONL 且 manifest `rowCount=0`、`checksum="0"`；非 0 行表 rowCount 与 Hive 分区行数逐表对账一致（D-020 已证，本门为复证不回归）;
- MetricPublisher 消费 manifest：`MP_EXPORT_*` / `MP_ADS_ROWS_MATCH` 等检查通过；
- **D-021 断言（本门新增核心）**：`ads_data_quality_m` 中 D-019 合法空态质量行以 `error_rate=NULL` 真实落库（含 passed=1 行），`MP_ADS_WRITE` 通过、无 `DataIntegrityViolationException`、无 42 行回滚；发布侧对 NULL 行读回语义一致（不出现 NULL→0 改写痕迹）；
- outer harness exit 0 / outcome PASS。

## 6. Classification

Do not classify from the console line alone — use evidence only（同 T-R1/T-R2 §6：platform PID/exitCode/resource 快照 + 每次 poll 的 Pipeline stage/status + Spark/platform 日志 → production FAIL vs BLOCKED_ENV vs harness issue）。

特别地：若 MP_ADS_WRITE 再次失败，按 production FAIL 登记完整异常与 MP_* 检查明细；**不得**做 NULL→0 转换、**不得**改 V3/V11 之外的历史迁移、**不得**把写入失败静默当成空态放行（这些扩大/绕过语义均已被总控明令禁止或不在 D-021 范围）。

## 7. After PASS

T-R3 PASS 关闭 T-R2 的 production FAIL `MP_ADS_WRITE` 事件（同链复跑成功即证明该缺口被修复关闭），Stage 7「localfile 分析链」（mock-mall 纯交易源）验证门序列 T→T-R1→T-R2→T-R3 收敛；BATCH-U（Flume→HDFS）在 T-R3 PASS 前保持关闭（总控裁决「BATCH-U 继续关闭」），PASS 后按其 DRAFT 计划开放。T-R3 通过仍不证明：REMOTE_CLUSTER、浏览器 E2E、真实 LLM。
