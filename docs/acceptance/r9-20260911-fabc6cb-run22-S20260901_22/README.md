# R9 验收证据目录（20260911 / commit fabc6cb / run 22 / snapshot S20260901_22）

> 采集脚本：`.verify/r9-evidence-collect.ps1`；所有数字来自真实 MySQL / 真实 Hive / 真实 HTTP API / 真实磁盘，无 Mock。

| 文件 | 字节 | 内容 |
|---|---:|---|
| 01-pipeline-run.tsv | 134 | pipeline_run 整行（run 终态、attempt、目标快照、错误码） |
| 02-pipeline-stage-run.tsv | 762 | 八阶段逐阶段状态、records、externalJobId、证据长度与 JSON 合法性 |
| 03-spark-job-run.tsv | 4522 | spark_job_run 全列（作业码、外部作业 id、日志 URI、输入输出行数） |
| 04-spark-job-summary.tsv | 246 | 作业级成功/失败汇总 |
| 05-operation-audit-summary.tsv | 303 | 操作审计按动作×结果汇总（含 FAILED 越权尝试） |
| 06-operation-audit-last15.tsv | 1967 | 操作审计最近 15 条（actor/role/target/errorCode） |
| 07-decision-task-by-status.tsv | 30 | 决策 12 态分布 |
| 08-ai-query-history.tsv | 83 | AI 问数审计（用户 × 状态） |
| 09-ai-call-log.tsv | 15 | LLM 调用日志（provider/status/次数/耗时） |
| 10-metric-snapshot.tsv | 242 | 指标快照表（唯一 ACTIVE + 归档） |
| 11-metric-value-active.tsv | 521 | ACTIVE 快照指标值（含口径版本） |
| 12-ads-table-counts.tsv | 208 | 指标库 8 张 ADS 宽表行数 |
| 13-ads-overview-active.tsv | 68 | ACTIVE 快照概览宽表整行 |
| 14-ads-quality-active.tsv | 104 | ACTIVE 快照质量规则结果 |
| 15-hive-layers.txt | 1902 | Hive ODS/DWD/DWS/ADS 四层行数、分区、金额、质量、staging 隔离 |
| 16-api-responses.json | 82417 | 真实 HTTP 响应（指标/漏斗/商品/趋势/RFM/流水线/决策/AI 健康） |
| 18-r7-4-dom-report.json | 13552 | 归档证据 |
| 18-r7-4-mall-dom-report.json | 2555 | 归档证据 |
| 18-r8-accept-report.json | 10843 | 归档证据 |
| 18-r8-evidence-truncation-proof.json | 3984 | 归档证据 |
| 19-spark-jobs.jar | 215566 | 归档证据 |
| 20-reconciliation.tsv | 1301 | 黄金标准答案 vs 指标库对账 |
| ai-assistant-after-query.png | 168522 | 页面截图（Playwright 真机） |
| ai-assistant.png | 98291 | 页面截图（Playwright 真机） |
| behavior.png | 83247 | 页面截图（Playwright 真机） |
| decisions.png | 121726 | 页面截图（Playwright 真机） |
| hive-evidence.sql | 3606 | 归档证据 |
| mall- | 50583 | 页面截图（Playwright 真机） |
| ops.png | 367660 | 页面截图（Playwright 真机） |
| overview-error-state.png | 45350 | 页面截图（Playwright 真机） |
| overview-unknown-snapshot.png | 44495 | 页面截图（Playwright 真机） |
| overview.png | 78461 | 页面截图（Playwright 真机） |
| pipeline.png | 72113 | 页面截图（Playwright 真机） |
| products-unknown-snapshot.png | 86351 | 页面截图（Playwright 真机） |
| products.png | 81977 | 页面截图（Playwright 真机） |
| rfm.png | 79040 | 页面截图（Playwright 真机） |
| sales.png | 78449 | 页面截图（Playwright 真机） |

## 关键结论

- run 22 终态：
SUCCESS（attempt 4，errorCode=无）
- ACTIVE 快照：S20260901_22（业务日期 20260901）
- 黄金对账不一致项：0
- 产出时间：2026-09-11 10:36:03
