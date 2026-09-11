# R9 验收证据目录（20260911 / commit e272c8a / run 30 / snapshot S20260901_30）

> 采集脚本：`.verify/r9-evidence-collect.ps1`（已随本目录 `scripts/` 归档，可重跑复现）；所有数字来自真实 MySQL / 真实 Hive / 真实 HTTP API / 真实磁盘，无 Mock。

| 文件 | 字节 | 内容 |
|---|---:|---|
| 01-mall-login.png | 27177 | 页面截图（Playwright 真机） |
| 01-pipeline-run.tsv | 142 | pipeline_run 整行（run 终态、attempt、目标快照、错误码） |
| 02-mall.png | 176622 | 页面截图（Playwright 真机） |
| 02-pipeline-stage-run.tsv | 762 | 八阶段逐阶段状态、records、externalJobId、证据长度与 JSON 合法性 |
| 03-admin-products.png | 265842 | 页面截图（Playwright 真机） |
| 03-spark-job-run.tsv | 2492 | spark_job_run 全列（作业码、外部作业 id、日志 URI、输入输出行数） |
| 04-generator.png | 50583 | 页面截图（Playwright 真机） |
| 04-spark-job-summary.tsv | 225 | 作业级成功/失败汇总 |
| 05-operation-audit-summary.tsv | 303 | 操作审计按动作×结果汇总（含 FAILED 越权尝试） |
| 06-operation-audit-last15.tsv | 1967 | 操作审计最近 15 条（actor/role/target/errorCode） |
| 07-decision-task-by-status.tsv | 30 | 决策 12 态分布 |
| 08-ai-query-history.tsv | 83 | AI 问数审计（用户 × 状态） |
| 09-ai-call-log.tsv | 15 | LLM 调用日志（provider/status/次数/耗时） |
| 10-metric-snapshot.tsv | 491 | 指标快照表（唯一 ACTIVE + 归档） |
| 11-metric-value-active.tsv | 521 | ACTIVE 快照指标值（含口径版本） |
| 12-ads-table-counts.tsv | 836 | 指标库 8 张 ADS 宽表行数 |
| 13-ads-overview-active.tsv | 68 | ACTIVE 快照概览宽表整行 |
| 14-ads-quality-active.tsv | 104 | ACTIVE 快照质量规则结果 |
| 15-hive-layers.txt | 2059 | Hive ODS/DWD/DWS/ADS 四层行数、分区、金额、质量、staging 隔离 |
| 16-api-responses.json | 89526 | 真实 HTTP 响应（指标/漏斗/商品/趋势/RFM/流水线/决策/AI 健康） |
| 18-r7-4-dom-report.json | 13552 | 平台 8 页真机 DOM 验收（22/22 PASS） |
| 18-r7-4-mall-dom-report.json | 2555 | 商城页面真机 DOM 验收（17/17 PASS） |
| 18-r8-accept-report.json | 10843 | R8 真机验收（A–F 六组，53/53 PASS） |
| 18-r8-evidence-truncation-proof.json | 3984 | 阶段证据截断修复复验（12/12 PASS） |
| 19-spark-jobs.jar | 215866 | 产生本次 run 的作业 jar 快照（可与重建产物比对） |
| 20-reconciliation.tsv | 1301 | 黄金标准答案 vs 指标库对账 |
| 21-reliability-experiments.tsv | 3242 | §23.3 强制失败测试（C1 幂等 / C2 并发同键 / C3 断点续跑 / C4 质量阻断隔离 / C5 杀进程重启恢复 / D-R9-1 暂存清理修复） |
| 22-qualityfail-run26.json | 20080 | C4 质量失败 run 全量响应（终态、阻断规则、错误码） |
| 23-qualityfail-isolation.sql | 751 | C4 隔离核对 SQL 原文（正式 ADS 空分区 vs 黄金分区完好） |
| 24-fulltest-analytics-server.log | 39695 | 平台全量 mvn test 原始日志（7 模块 303/303，BUILD SUCCESS；测试条数的唯一落点） |
| 25-resume-run26.json | 20080 | C5 重启后 resume 续跑全量响应（attemptNo 递增、仅失败阶段重跑） |
| 26-prune-fix-verification.tsv | 3330 | D-R9-1 暂存清理 dt 限定修复验证（异日期暂存分区存活 + 同日期历史回收） |
| 27-prune-fix-verify.log | 1743 | D-R9-1 验证过程日志（埋点分区清单、spark-sql 输出、发布检查项原文） |
| 28-prunefix-run.json | 49609 | D-R9-1 验证 run 全量响应 |
| 29-resume-evidence.tsv | 1491 | C5 续跑实验原始证据（analytics_meta 直读：attempt/阶段时间戳/快照表） |
| 30-final-acceptance.md | 16049 | §30 最终验收清单逐项判定（16 ✅ / 2 ⚠️ / 0 ❌，含未达标边界清单） |
| 31-metric-publish-it-regression.log | 9645 | 指标发布真库集成测试原始日志（PV/退款率口径回归，1/1 PASS、0 skipped） |
| 32-spark-jobs-tests.log | 7839 | Scala 作业单测原始日志（D-R9-1/D-R9-2 修复后：succeeded 46 / failed 0） |
| ai-assistant-after-query.png | 168522 | 页面截图（Playwright 真机） |
| ai-assistant.png | 98291 | 页面截图（Playwright 真机） |
| behavior.png | 83247 | 页面截图（Playwright 真机） |
| decisions.png | 121726 | 页面截图（Playwright 真机） |
| hive-evidence.sql | 3606 | 第 3 步 Hive 复核所用 SQL 原文 |
| ops.png | 367660 | 页面截图（Playwright 真机） |
| overview-error-state.png | 45350 | 页面截图（Playwright 真机） |
| overview-unknown-snapshot.png | 44495 | 页面截图（Playwright 真机） |
| overview.png | 78461 | 页面截图（Playwright 真机） |
| pipeline.png | 72113 | 页面截图（Playwright 真机） |
| products-unknown-snapshot.png | 86351 | 页面截图（Playwright 真机） |
| products.png | 81977 | 页面截图（Playwright 真机） |
| r7-4-dom.py | 18343 | 平台 8 页真机 DOM 验收脚本（22/22） |
| r7-4-mall-dom.py | 6105 | 商城页面真机 DOM 验收脚本（17/17） |
| r8-accept.ps1 | 16013 | R8 真机验收脚本（53/53） |
| r8-evidence-truncation-proof.ps1 | 10461 | 证据截断修复复验脚本（12/12） |
| r9-evidence-collect.ps1 | 20722 | 本目录采集脚本（可重跑复现全部证据） |
| r9-prune-fix-verify.ps1 | 10464 | D-R9-1/D-R9-2 修复端到端复验脚本（P0–P8） |
| r9-reliability.ps1 | 12007 | §23.3 可靠性/故障注入脚本（C1–C5，含 -WithRestart） |
| r9-resume-experiment.ps1 | 4665 | 续跑实验脚本（C5a–C5f） |
| README.md | 5116 | 本索引 |
| rfm.png | 79040 | 页面截图（Playwright 真机） |
| sales.png | 78449 | 页面截图（Playwright 真机） |

## 关键结论

- run 30 终态：
SUCCESS（attempt 1，errorCode=无）
- ACTIVE 快照：S20260901_30（业务日期 20260901）
- 黄金对账不一致项：0
- 产出时间：2026-09-11 11:17:37


