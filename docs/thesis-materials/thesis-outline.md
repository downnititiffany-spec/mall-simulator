# 论文章节素材映射（§15.1 九章）

> 用途：写作时按章取素材；**所有数字来自 experiments/ 与验收清单，未实测不写结论**。
> 本文件已于 2026-09-11 按仓库真实实现逐条复核，删除/替换了失效类名与旧链数字（复核记录见 `docs/v2-completeness-audit.md`）。

## 第一章 绪论
- 背景与问题：文稿 §1.2（5 个问题点）
- 研究内容/创新点：§9.1-9.6 六项 → 对应实现证据：
  - 9.1 面向数仓语义的 Text-to-SQL → `SemanticCatalog`（语义/别名/少样本，**当前只登记 5 张已发布 ADS**）+ 评测集 `tests/ai-questions/questions.jsonl`（100 题，safe 50 / blocked 50）
  - 9.2 多级校验与修复 → `SqlPolicy` + `SqlSafetyValidator`（AST 全树校验）+ `QueryCostGuard`（EXPLAIN fail-closed）；"修复"为拒答后重生成，不夸大
  - 9.3 证据链报告 → `EvidenceBuilder` + `ExplanationService`（数值全部带 `evidenceRef`，`templateVersion=evidence_v1`）
  - 9.4 大数据与智能交互分层 → `MySqlMetricStore`/`MetricPublisher`（AI 与页面**只读指标库 `analytics_metric`**，不查 Hive 明细、不查商城库）
  - 9.5 场景驱动可复现数据 → `SimulationEngine`（`randomSeed` 可复现）+ 11 场景；**生成器属模拟商城进程（:8090），不属平台**
  - 9.6 指标快照决策闭环 → `DecisionService` + 12 态状态机（`DecisionStateMachine`）+ 窗口效果评价（样本不足返回 `INSUFFICIENT_DATA`）

## 第二章 相关技术
- 版本选型：文稿 §4.1 对照表 → 实测版本（JDK17 / Spring Boot 3.2.5 / MySQL **8.0.41**（实测 `SELECT VERSION()`）/ Scala 2.12.19 / Spark 3.5.1）
- 强调"论文写真实版本"：`docs/deployment.md` 与各 `pom.xml` 为准；**Parquet Snappy 未启用**（全仓 0 处），不要在论文里写启用

## 第三章 需求分析
- 角色/用例/指标：文稿 §2.1/2.3 + `docs/contracts/metric-dictionary.md`（**16 项口径**：v1 + v2 分列，注意 `refund_rate` 有 v2 与 `full_refund_rate` v1 两套，引用时必须标版本）
- 角色口径：代码内 `RolePermissions` 为 4 角色（admin/operator/analyst/data_dev），**真库 `sys_user` 只有 admin/operator/analyst 三个账号，`data_dev` 无账号**——需求章写"四类角色"时需说明该差异

## 第四章 系统总体设计
- 架构图/部署图/模块图：文稿 §3 图 → **不要引用 `1.png`（不存在）**；用 `docs/acceptance/r9-*/17-screenshots/` 真机截图或自行绘图
- 模块构成：平台 `analytics-server` = 7 个 reactor 模块（`connection-ingestion`/`warehouse-pipeline`/`metric-analysis`/`ai-decision`/`platform-common`/`platform-app` + 父 POM）；模拟商城 `mall-simulator` 独立进程/独立库/独立前端
- 两进程两端口两库边界：`docs/compatibility-matrix.md` L0 段（平台 8091 / 商城 8090；`analytics_meta`+`analytics_metric` / `mall_simulator`）

## 第五章 数仓与 Spark 设计实现（核心章）
- 表清单：`warehouse/ddl/00-ods.sql`…`04-ads.sql`，Hive 库 `dw_ods`/`dw_dwd`/`dw_dim`/`dw_dws`/`dw_ads`
  - **声明 vs 实建要分开写**：DDL 声明 ODS 4 / DWD 3 / DIM 5 / DWS 7 / ADS 10；**实际每日产出** DIM 只有 `dim_user`/`dim_product`（`dim_date`/`dim_region`/`dim_metric` 有建表无数据），ADS 只有 8 张（`ads_category_sale`/`ads_region_sale` 无产出，页面以 `UNKNOWN_DIMENSION_TABLE` 如实降级）
- 血缘：`docs/contracts/metric-lineage.md`（权威，标注了未落地指标）+ `warehouse/README.md`
- Spark 作业源码：`spark-jobs/src/main/scala/...`（**11 个作业** + 基类 `WarehouseJob`）；作业码 `sci`/`dim`/`tdw`/…（注意与指导书早期命名 `lsi`/`dmb`/`tds` 的差异）
- 口径：event_id 去重 / 有效支付 / 分母 0→NULL——`MetricAdsSpecTest`、`OrderTradeCompilerSpec` 断言可引
- 质量规则：`QualityChecker`（Java 侧 4 条）+ `AdsQualityJob`/`DataQualityGate`（阻断发布）；**真机结果为 4 检查 / 3 通过 / `["EVENT_ID_UNIQUE"]` 失败**（阈值 0.0005，非核心规则**不阻断**），不要写成 4/4 全绿
- 真实执行证据：`docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/`（01–04 流水线、10–14 指标库、15 Hive 四层）

## 第六章 智能分析模型设计实现（核心章）
- 语义层 `SemanticCatalog` / 流程 `TextToSqlService` / 校验 `SqlPolicy` + `SqlSafetyValidator` + `QueryCostGuard`
- 证据解释 `EvidenceBuilder` + `ExplanationService`（六段模板 `evidence_v1`、解释 `explain_v1`）
- **不要引用 `AiOutputValidator`（该类不存在）**；模型输出异常的处理是"抛错 → 回落模板"，且**缺"模型返回非 JSON"专项用例**（已在审计登记）
- 降级路径 `RuleBasedSqlFallback`：无 `LLM_API_KEY` 时全链可跑（真机 `providerUsed=template`，`ai_call_log` 为 0 行——如实写"设计了真实模型接入，未实测"）

## 第七章 系统实现
- 页面截图点：`docs/thesis-materials/screenshot-list.md`
- 接口：`docs/api-overview.md`；**注意实现路径与指导书 §22 的差异**（`/pipeline-runs` 而非 `/pipelines/runs`、`/dashboards/overview` 而非 `/analysis/overview`、`/analysis/rfm` 而非 `/analysis/users/rfm`；无 `/connectors`、无 `/spark-jobs/{id}/logs`）
- 审计：`operation_audit_log`（`analytics_meta`，13 列，真机 92 行 / 9 类 action）、`ai_query_history`、`ai_call_log`（0 行）

## 第八章 实验与结果分析（论文心脏）
- 黄金对账：55 行黄金 JSONL（`tests/golden-dataset/events/golden-20260901.jsonl`）→ 8 阶段 → ACTIVE 快照；对账表 `…/20-reconciliation.tsv`
  - 标准答案：`pv 7`/`uv 3`/`dau 3`/`paid_order_cnt 5`/`gmv 2042.00`/`net_sale 1493.00`/`aov 408.40`/`refund_rate 0.6000`/`full_refund_rate 0.2000`/`buy_rate 1.0000`
  - **不要写"A/B 对账全部 YES"**：A 段 11 行中 `full_refund_rate` 因黄金文件未列该口径标 `—`，仅 B 段（指标库 vs ADS 宽表）为 YES
  - 真库断言：`MetricPublisherMySqlIT`（`-Dmetric.it=true`，1/1）与 `AnalysisGoldenMySqlIT`（6/6）；**`GoldenE2ETest` 不存在，不要引用**
- Text-to-SQL：`experiments/ai-eval-*.json`（valid/executed/blocked/avg_ms；**Mock 基线**）
- 性能：`experiments/perf-web-tier1.json` 与 1.1 万/10.8 万事件流水线耗时均为 **2026-09-06 整改前旧链数据**，本轮未重测 P95 → 论文中标 ⚠️ 并注明旧链
  （3.9s/7.4s → 21ms/29ms 的"优化前后对比"属旧链口径，引用需注明）
- 数据规模：LOCAL 1.1 万/10.8 万事件（<1s / 1.3s）；1M–100M 与集群分档**未做**
- 安全：R8 真机验收 **53/53 PASS**（A–F 六组，含越权 7/7、问数攻击集 7/7）；**不要引用 100 题 Mock 的"50/50 拦截率 100%"**（`screenshot-list.md:74` 已标注为假绿，不作证据）
- **待补**：Spark 集群分档性能（100 万–1 亿）、真实 LLM 消融 A/B/C/D、AI 数值事实一致率人工评分、决策 EFFECTIVE 正样本（真库 4 条评价全为 `INSUFFICIENT_DATA`）

## 第九章 总结与展望
- 成果：R1–R9 整改链路 + R9 验收包（62 文件）；不足：集群验证、真实模型、实时化、连接器插件体系未做
- 展望：Kafka/SeaTunnel/Structured Streaming、Doris 指标服务（**仅枚举规划，未实现**）、KMeans 可选实验

## 写作纪律（§15.2）
- 每章按"问题→方法→实现→实验→分析"闭环组织；
- 实验数字 → 引用 `experiments/` 原始 JSON；截图 → 按 `screenshot-list.md` 采集；
- 引用借鉴项目（text-to-sql-main 等）时保持来源说明（§16 风险控制）；
- 未实测项一律写成"设计了实验、待环境执行"，禁止把 Mock 基线、旧链数据、指导书清单当成实测结果。
