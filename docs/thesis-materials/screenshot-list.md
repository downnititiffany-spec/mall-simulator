# 截图与图表清单（论文采图指引）

> 每条给出：内容、采集入口/命令、建议位置（章）。截图上标注环境与时间更佳（§27.3 可复现要求）。
>
> **2026-09-11 校订**：原"已采集 2026-09-06 共 11 张"的口径已更正。目录
> `docs/thesis-materials/screenshots/` 实存 **12 个文件 = 4 张 `ppt-*` + 8 张 `web-*`**；
> 其中 **4 张 `ppt-*.png` 内容完全相同**（MD5 均为 `91BAAB8C7972BC0F7EDFD4863C72734C`，各 99030B）
> ——即同一张图重复占位四次，**不是四张不同的 PPT 截图**；8 张 `web-*.png` 采于 2026-09-06，
> 对应**旧快照**，与 run 22 时点 ACTIVE `S20260901_22` 的数值不一致（当前 ACTIVE 已是 `S20260901_30`）。
>
> **当前权威截图来源（run 22 时点，2026-09-11）**：
> `docs/acceptance/r9-20260911-fabc6cb-run22-S20260901_22/17-screenshots/`（13 张 PNG + 1 个文件名异常项 `mall-`），
> 与 `.verify/r7-4-dom/` 下 13 张同名 PNG 一一对应，由 `.verify/r7-4-dom.py`（DOM 断言 **22/22 PASS**）采集。
> **论文正文截图建议全部改用这一套**，并标注 `snapshot=S20260901_22`、`runId=22`。
>
> **R9 定稿截图来源（run 30 时点，2026-09-11，提交 `e272c8a`）**：
> `docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/17-screenshots/`（13 张 PNG，Playwright 真机，
> PNG 魔数校验通过），对应唯一 ACTIVE 快照 **`S20260901_30`**（黄金口径与 `S20260901_22` 逐项相同）。
> 论文若要与最终代码版本对齐，采用这一套并标注 `snapshot=S20260901_30`、`runId=30`、`commit=e272c8a`。

## 0. 权威截图清单（建议论文直接采用）

| 文件（`…/17-screenshots/` 与 `.verify/r7-4-dom/` 同名） | 内容 | 对应路由/状态 |
|---|---|---|
| `overview.png` | 运营大盘（GMV 2,042.00元 / 支付订单数 5单 / PV 7次 / 退款率 60.00%；快照 S20260901_22；口径 v2） | `/overview` ready |
| `overview-unknown-snapshot.png` | 未知快照 → **空态**（不回退 ACTIVE） | `/overview` empty |
| `overview-error-state.png` | 接口失败 → **错误态** | `/overview` error |
| `behavior.png` | 用户行为分析（漏斗 + 行为分布） | `/behavior` |
| `products.png` | 商品热度 TopN | `/products` |
| `products-unknown-snapshot.png` | 商品页**空态** | `/products` empty |
| `rfm.png` | RFM 用户分层 | `/rfm` |
| `sales.png` | 销售趋势 | `/sales` |
| `pipeline.png` | 流水线八阶段明细 | `/pipeline` |
| `ops.png` | 运维中心（采集/批次/质量） | `/ops` |
| `decisions.png` | 决策中心 | `/decisions` |
| `ai-assistant.png` | 智能分析助手（证据区） | `/ai` |
| `ai-assistant-after-query.png` | 提问后结果（SQL 证据 + 六段解释） | `/ai` after query |

> 覆盖 9 个业务路由 + 3 张状态图；**缺 `login` 与"加载态/过期态(stale)"**——五态中
> `loading`/`stale` 只有单测（`web/tests/chartState.test.js`），无 DOM 截图；"无权限态"在页面级也未实测。
> 若要写"五态均已截图"，需先补采（见 `证据映射表.md` 缺口 P3-4）。
> 证据包内 `mall-`（50583B，文件名被截断）为商城侧截图，引用前需改名。

## A. 数据链路类（第五章/第七章）

| # | 内容 | 采集方式 |
|---|---|---|
| A1 | 商城下单页面（商品/加购/订单/退款） | **商城前端已存在**：`mall-frontend/` 独立工程，运行于 http://127.0.0.1:8090（真实页面 `/mall`、`/admin-products`、`/generator`，已由 `.verify/r7-4-mall-dom.py` **17/17** 实测）。**旧稿写的"当前无商城 UI，用 API 响应改图或补最小页"已失效**——不得再用"改图/示意"冒充 |
| A2 | Outbox 滚动日志样例（landing/events/2026*.jsonl 首 5 行） | 文本截图 |
| A3 | 采集批次状态（SUCCESS/记录数/断点） | GET /api/v1/ingestion/status + batches |
| A4 | 流水线**八阶段**明细 | GET /api/v1/pipeline-runs/22（或前端 `/pipeline` 页）；八阶段 = WAIT_LANDING→INIT_SCHEMA→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC |
| A5 | 快照版本列表（ACTIVE/ARCHIVED） | GET /api/v1/metrics/snapshots（当前 `S20260901_22` ACTIVE，`_23/_24` ARCHIVED） |
| A6 | 数据质量规则结果 | `analytics_metric.ads_data_quality`；**真值 4 条规则 3 过 1 败（`EVENT_ID_UNIQUE`）**，见证据包 `14-ads-quality-active.tsv` |
| A7 | MySQL 指标行 | `SELECT * FROM analytics_metric.metric_value WHERE active_flag IS NOT NULL` —— **指标库是 `analytics_metric`**（旧稿写 `mall_simulator.metric_value`，那是商城业务库，**已更正**）；快照表 `analytics_metric.metric_snapshot` |

## B. 看板类（第七章）

| # | 页面 | 入口 | 建议截图时机 |
|---|---|---|---|
| B1 | 运营大盘（指标卡+趋势） | http://127.0.0.1:5173/overview | 有 ACTIVE 快照后（现为 `S20260901_22`） |
| B2 | 转化漏斗 | /behavior | 同上 |
| B3 | 商品热度 TopN | /products | 同上 |
| B4 | 销售趋势表 | /sales | 同上 |
| B5 | 智能分析助手（问题+结果+SQL 证据） | /ai | 输入推荐问题截图（含证据区展开态）；真实运行 `providerUsed=template`（无 LLM key），须如实标注 |
| B6 | 危险 SQL 拦截反馈 | /ai 输入"删除订单数据" | 展示安全能力；**证据以 `.verify/r8-accept.ps1` E 组 7 条为准**，不要引 Mock 评测 |
| B7 | 决策中心（列表+效果评价） | /decisions | 真机当前仅 `DRAFT`/`INSUFFICIENT_DATA`，**不要摆拍 `EFFECTIVE`** |
| B8 | RFM 分层 | /rfm | 注意 `ads_user_profile_m` 无金额列 → RFM amount 恒 null（`RFM_AMOUNT_UNAVAILABLE`），截图会体现该告警 |
| B9 | 运维中心 | /ops | 采集批次/质量/运行画像 |

## C. 实验类（第八章）

| # | 图表 | 数据来源 | 呈现 |
|---|---|---|---|
| C1 | Text-to-SQL 评测统计表 | `experiments/ai-eval-*.json`（最新 `-2026-09-10-210756`） | 表：总 100 / 安全 50/50 / **平均 12ms** / 8 类桶。⚠️ **Mock 基线**，且"越权 50/50"为**假绿**（R8 缺陷②），须加限定说明 |
| C2 | 性能对比（柱状） | `perf-web-tier1.json` | ⚠️ **只有优化后数据（21–29ms）**，"优化前 3.9s/3886/3728/7383ms"**无留档**，双柱对比图暂不可画；补测后方可（见 `证据映射表.md` 缺口 P0-3） |
| C3 | 数据规模扩展曲线（LOCAL） | `experiments/spark-scale-local-20260906.json`（14,400 / 64,800 / 129,600 事件；odl 10.9/13.7/11.1s） | 点线图。旧稿写的"1.1万/10.8万"是另一套口径（`acceptance-checklist.md:28-33`），需统一；"待补 50 万档"仍待环境 |
| C4 | 危险请求拦截明细表 | `tests/ai-questions/questions.jsonl` + `.verify/r8-accept-report.json` E 组 | 表：类型/行为/结果。⚠️ 不要用 ai-eval 的 blocked 50 条（假绿） |
| C5 | 决策评价口径 | `DecisionService.java:528-544/358-367/63-69` + `07-decision-task-by-status.tsv` | 表：窗口/样本不足/改善率算法/分级阈值。⚠️ 原"基线 3702.50 → 4320.00 (+16.68%)"等示例**无证据，已删** |
| C6 | 场景方向验证（促销 vs 正常） | `ScenarioEffectTest` 输出 | 表：GMV/订单数对比 |

## D. 代码/架构类

| # | 内容 | 来源 |
|---|---|---|
| D1 | 整体架构图 | ⚠️ 旧稿写"文稿 `1.png`"——**仓库根目录不存在 `1.png`（只有 `README.md`）**，该引用已失效。请基于 `docs/design/项目设计文稿 V2.2.md` 重绘，或直接引用真实代码路径 |
| D2 | 数仓血缘图 | `warehouse/README.md`（存在）+ `docs/contracts/metric-lineage.md`；本地真实链由 `sci` 作业建 32 张表（含 8 张 `__staging`） |
| D3 | 关键代码片段 | `OpenAiCompatLlmProvider`（式调用）、`SqlSafetyValidator`/`SqlPolicy`（四层校验）、`MySqlMetricStore.java:149-198`（同事务原子切换）、`MetricExportJob.scala:137-167`（递归清理） |
| D4 | Spark 作业 DAG/UI（集群后） | Spark UI 截图（**待环境**） |

## 采图纪律

- 看板截图统一在生成 3 天数据后（`scripts/run-demo.ps1` 一键出数据）；当前 ACTIVE 快照为
  `S20260901_22`，截图必须与该快照一致，**不得使用 2026-09-06 的旧截图充当当前状态**；
- 每张截图在论文中标注：时间/数据规模/本机配置（CPU/内存/MySQL 版本/**快照号**）；
- 集群相关图（D4、部分 C3）在环境就绪后补拍，**不得用替代图冒充**；
- PPT 类截图请从 `docs/presentation/index.html`（122KB）重新采集：现有 4 张 `ppt-*.png`
  内容完全相同，属占位重复（详见文件头说明）。
