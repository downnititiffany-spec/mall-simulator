# 截图与图表清单（论文采图指引）

> 每条给出：内容、采集入口/命令、建议位置（章）。截图上标注环境与时间更佳（§27.3 可复现要求）。
> **已采集 2026-09-06**：本次共 11 张（PPT 4 + 看板 7）存于 `docs/thesis-materials/screenshots/`，
> 由 `scripts/shot.js`（playwright-core 无头）自动产出，重跑需先起后端并 `scripts/run-demo.ps1` 出数据。
> 看板截图数据规模：1 天窗口 80 用户（GMV 15,647.36 / PV 57,107 等），复采请以相同口径记录。

## A. 数据链路类（第五章/第七章）

| # | 内容 | 采集方式 |
|---|---|---|
| A1 | 商城下单页面（商品/加购/订单/退款） | http://127.0.0.1:8090 商城 API + 前端 5173（当前无商城 UI，用 API 响应改图或补最小页） |
| A2 | Outbox 滚动日志样例（landing/events/2026*.jsonl 首 5 行） | 文本截图 |
| A3 | 采集批次状态（SUCCESS/记录数/断点） | GET /api/v1/ingestion/status + batches |
| A4 | 流水线 7 阶段明细 | GET /api/v1/pipeline-runs/{id}（或前端 pipeline 页） |
| A5 | 快照版本列表（ACTIVE/ARCHIVED） | GET /api/v1/metrics/snapshots |
| A6 | 数据质量规则结果 | data_quality_result 表（SELECT 样例） |
| A7 | MySQL metric_value 指标行 | SELECT * FROM mall_simulator.metric_value LIMIT 12 |

## B. 看板类（第七章）

| # | 页面 | 入口 | 建议截图时机 |
|---|---|---|---|
| B1 | 运营大盘（指标卡+趋势） | http://127.0.0.1:5173/overview | 生成 3 天数据后 |
| B2 | 转化漏斗 | /behavior | 同上 |
| B3 | 商品热度 TopN | /products | 同上 |
| B4 | 销售趋势表 | /sales | 同上 |
| B5 | 智能分析助手（问题+结果+SQL 证据） | /ai | 输入推荐问题截图（含证据区展开态） |
| B6 | 危险 SQL 拦截反馈（FAILED 状态） | /ai 输入"删除订单数据" | 展示安全能力 |
| B7 | 决策中心（列表+效果评价） | /decisions | 完成一次闭环后 |

## C. 实验类（第八章）

| # | 图表 | 数据来源 | 呈现 |
|---|---|---|---|
| C1 | Text-to-SQL 评测统计表 | experiments/ai-eval-*.json | 表：总/安全执行/拦截/修复/耗时 |
| C2 | 性能优化前后对比（柱状） | perf-web-tier1.json + 优化前记录 | 双柱：3.9s vs 21ms 等，log 轴 |
| C3 | 数据规模扩展曲线（LOCAL） | 1.1万/10.8万：流水线耗时 | 点线图（待补 50万档） |
| C4 | 危险请求拦截明细表 | questions.jsonl blocked 50 条 + 结果 | 表：类型/行为/结果 |
| C5 | 决策效果示例 | decisions 列表 + evaluation JSON | 卡片/表：基线/实际/改善率 |
| C6 | 场景方向验证（促销 vs 正常） | ScenarioEffectTest 输出 | 表：GMV/订单数对比 |

## D. 代码/架构类

| # | 内容 | 来源 |
|---|---|---|
| D1 | 整体架构图 | 文稿 1.png + 重绘 |
| D2 | 数仓血缘图 | warehouse/README.md（odl→bdw→usw→fna） |
| D3 | 关键代码片段 | LlmClient 式调用（OpenAiCompatLlmProvider）、四层校验、快照发布事务 |
| D4 | Spark 作业 DAG/UI（集群后） | Spark UI 截图（待环境） |

## 采图纪律
- 看板截图统一在生成 3 天数据后（`scripts/run-demo.ps1` 一键出数据）；
- 每张截图在论文中标注：时间/数据规模/本机配置（CPU/内存/MySQL 版本）；
- 集群相关图（D4、部分 C3）在环境就绪后补拍，不得用替代图冒充。