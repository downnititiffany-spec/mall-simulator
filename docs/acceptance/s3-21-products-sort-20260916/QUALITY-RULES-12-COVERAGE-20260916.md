# 设计 §12.3 质量 12 项覆盖复测（S3-21 滚动检索证据）

- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 复测提交：`c4c09dc`（F-53 / S3-20 之后、S3-21 开工前）
- 复测动因：`docs/PROJECT_STATUS.md` backlog 里「12 条质量规则覆盖缺口」一行引自 **V25 期**文档
  （`docs/acceptance/v25-r01-coverage-20260914/MATRIX.md:145` 等），S3-05…S3-20 期间已陆续新增规则码，
  该行**可能已过期**。滚动检索要求「以实测为准」，故逐条重测，不沿用旧结论。

## 1. 判据（唯一权威：设计 V3.0 §12.3 L497-510 逐字清单）

| # | 设计行 | 逐字要求 |
|---|---|---|
| 1 | L499 | 「Landing JSON可解析率及隔离数量。」 |
| 2 | L500 | 「ODS event_id非空与schema版本支持。」 |
| 3 | L501 | 「行为必填/类型/枚举。」 |
| 4 | L502 | 「订单行金额公式误差<0.01。」 |
| 5 | L503 | 「成功累计退款≤实付。」 |
| 6 | L504 | 「状态转换合法。」 |
| 7 | L505 | 「DWD→DWS金额对账。」 |
| 8 | L506 | 「同归属口径ADS GMV≥净销售≥0。」 |
| 9 | L507 | 「同过滤条件UV≤PV。」 |
| 10 | L508 | 「支付/浏览用户比及cohort解释：宽松口径异常不一概作为阻断规则。」 |
| 11 | L509 | 「采集延迟P95和迟到率，明确未来时间/负延迟处理。」 |
| 12 | L510 | 「发布表数、行数、必填和checksum一致性。」 |

（同段 L512 另要求：每条规则记录作用域/阈值/版本/阶段/实际值/passed/原始+生效严重度；
付款 vs 订单、订单项公式、DWD/DWS 对账三者**独立**；L518 `EVENT_ID_UNIQUE` `dupRateMax=0.0005`
**不得为测试通过改大**。）

## 2. 逐条实测结论（命令全部在本机执行，命中数为实测值）

| # | 结论 | 实测证据 |
|---|---|---|
| 1 | **已实现**（在**采集映射层**，不是 `quality_rule_definition` 规则码） | `git grep -n JSON_PARSE_ERROR -- analytics-server` ⇒ 命中；`MappingReason.java:34` 定义、`MappingExecutor.java:95` 违规即隔离、`MappingExecutorTest.java:549`「raw 事件不是合法 JSON ⇒ JSON_PARSE_ERROR + 隔离」 |
| 2 | **已实现** | `git grep -n SCHEMA_VERSION -- analytics-server` ⇒ 命中（`MappingExecutor.java:58,172-176,304-310` schema_version 存在/类型/版本支持）；质量码侧 `EVENT_ID_UNIQUE`（`QualityRuleCatalog.RULE_EVENT_ID_UNIQUE`） |
| 3 | **已实现** | `git grep -n "RULE_REQUIRED_FIELD\|RULE_ENUM_WHITELIST" -- analytics-server spark-jobs` ⇒ `QualityRuleCatalog.java:68,69`（+ L133/L137 登记定义） |
| 4 | **已实现** | `git grep -n ORDER_ITEM_AMOUNT_FORMULA -- analytics-server spark-jobs` ⇒ `QualityRuleCatalog.RULE_ORDER_ITEM_FORMULA`（L63）及登记定义 |
| 5 | **未实现（无一致性规则码）** | `git grep -h -o -E "RULE_[A-Z_]*REFUND[A-Z_]*" -- spark-jobs analytics-server \| Sort-Object -Unique` ⇒ **仅 2 个**：`RULE_REFUND_RATE_HIGH`、`RULE_FULL_REFUND_RATE_HIGH`（`ai-decision/.../AnomalyRules.java:39-40,60-61`）= 退款率**异常阈值**，**不是**「成功累计退款 ≤ 实付」的一致性校验 |
| 6 | **未实现（无规则码）** | `git grep -n -i -E "STATE_JUMP\|STATE_TRANSITION" -- spark-jobs analytics-server` ⇒ **0 命中**。（注意：`status_transition_allowed` **是**存在的检查项，但属**源注册状态机** `SourceRegistryServiceImpl.java:236`，与**订单状态转换**无关，不得据它判本项已实现。） |
| 7 | **已实现** | `git grep -n DWD_DWS_AMOUNT -- analytics-server spark-jobs` ⇒ `QualityRuleCatalog.RULE_DWD_DWS_AMOUNT`（L65）+ 种子 `V19__quality_rule_definition.sql:117`（`scope=*`、stage `DWS`、`BLOCKING`、`{"maxAbsDiff":0.01}`） |
| 8 | **未实现（无规则码）** | `git grep -n -E "sale_amount *>=? *net_sale\|net_sale_amount *<=? *sale_amount" -- spark-jobs analytics-server` ⇒ **0 命中**；读侧口径亦明示不做启发式纠正（S3-20 登记 §8） |
| 9 | **未实现（无规则码）** | `git grep -n -E "uv *<=? *pv" -- spark-jobs analytics-server` ⇒ **0 命中**；（`ads_operation_overview` 同行已有 `uv`/`pv` 两列，见 `MetricAdsSpec.scala:30` ⇒ 缺口只在规则侧，**不需要新列**） |
| 10 | **部分实现（对账形态，非阈值）** | 规则码 `ADS_DWS_FUNNEL_RATE_RECONCILE` 存在且 `BLOCKING`：`QualityRuleCatalog.java:81,169`、种子迁移 `V25__quality_rule_ads_funnel_rate_reconcile.sql:2,57`。它判的是「ADS 与 DWS 的比率**是否同一个数**」，**不**对支付/浏览比本身设阈值——与设计 L508 末句「宽松口径异常**不一概**作为阻断规则」一致 ⇒ 记为**部分**，不得称「支付/浏览用户比规则已实现」 |
| 11 | **未实现（且已登记待裁决）** | `git grep -n -i -E "LATE_ARRIVAL\|late_arrival\|迟到率\|LATE_EVENT_RATE" -- spark-jobs analytics-server` ⇒ **0 命中**；`git grep -n -i p95 -- spark-jobs analytics-server` ⇒ **0 命中**。已有待裁决记录：`docs/acceptance/p2-02-multiformat-time-20260912/SUGGESTED-RULINGS-20260912.md:148`「问题 B（时间类 DQ 规则）…（**待裁决 D-108**）」 |
| 12 | **已实现** | 规则码族 `MP_EXPORT_FILES`/`MP_EXPORT_CHECKSUM`/`MP_MANIFEST_TABLES`/`MP_REQUIRED_TABLES_NONEMPTY`/`MP_ROW_SHAPE_CONSISTENT`/`MXP_EXPORT_ROWS`/`PUB_FORMAL_PARTITION_MATCH` 等（`QualityRuleCatalog` L75-109；种子 `V23__quality_rule_publish_export_checksum.sql` 加性登记） |

**汇总（实测口径）**：**已实现 6 项**（1/2/3/4/7/12）、**部分实现 1 项**（10，对账形态）、
**未实现 5 项**（**5 退款≤实付、6 状态转换、8 GMV≥净销售≥0、9 UV≤PV、11 延迟P95/迟到率**）。

> 与 V25 期旧判定（`MATRIX.md:145` 记「实测规则码集合 = 8 个」）的差异：S3-05…S3-10 期间新增了
> `ORDER_ITEM_AMOUNT_FORMULA`（对应④）、`DWD_DWS_AMOUNT_RECONCILE`（⑦）、`ADS_DWS_FUNNEL_RATE_RECONCILE`（⑩部分）
> 与发布/导出规则族（⑫），故 ④⑦⑫ 已由「未做/部分」转为**已实现**，①③仍由采集映射层承载。
> 该旧行的「12 条覆盖缺口」表述因此**已过期**，`docs/PROJECT_STATUS.md` 按本复测更新描述（不删行、不改判类）。

## 3. 未实现/部分项的可实施性排序（S3-22 起的滚动候选）

| 项 | 判类 | 需要「发明新业务语义」？ | 需动到的所有者 | 备注 |
|---|---|---|---|---|
| **8（L506 GMV≥净销售≥0）** | A 类（新增规则码 + **加性**种子迁移） | **否**——设计 L506 已逐字给定不等式，`ads_operation_overview` 同一行即有 `sale_amount`/`net_sale_amount` | `QualityRuleCatalog` + 新加性迁移 + `AdsSql.dataQuality` UNION 分支 + Java/Scala 两套漂移守卫 | 语义最紧、缺口最明确 ⇒ **优先** |
| **9（L507 UV≤PV）** | A 类（同上） | **否**——同表同一行即有 `uv`/`pv` | 同上 | 可与 8 同批实现，但**必须两条独立规则码**（L512 禁合并） |
| 5（L503 退款≤实付） | A 类 | **是**——「成功累计退款」的累计窗口/归属期需先冻结（设计 L428 未裁决） | 规则码 + 退款归属期口径 | 受「退款归属期需冻结」约束 |
| 6（L504 状态转换合法） | A 类 | **是**——合法转换矩阵需新定义（设计只给要求名） | ODS/DWD 侧新增状态机校验 | 语义需新定义 ⇒ 建议先登记待批注 |
| 11（L509 延迟P95/迟到率） | A 类 | **是**——未来时间/负延迟处理需定义，且**已有待裁决 D-108** | 新指标 + 规则码 | 命中 D-108 ⇒ 先不动 |
| 10（L508 剩余部分） | A 类 | **部分**——阈值与严重度需定义（设计已提示不概作阻断） | 规则码 + 严重度 | 现为对账形态，扩为阈值需设计批注 |

## 4. 边界（不得越界表述）

1. 本文件是**滚动检索证据**，不是「12 项已通过验收」：未实现的 5 项与部分实现的第 10 项**未测业务结论**，
   不得因本文件存在而声称质量专题完成。
2. 「已实现」= 存在**可执行校验点**（规则码/映射违规码）；**不等于**在真实数据上跑过并通过
   （真实 DB/真实 Spark 运行仍属未测，见 `docs/PROJECT_STATUS.md` 未测清单）。
3. 本文件**不改**任何规则码、阈值、严重度、迁移或口径；`docs/PROJECT_STATUS.md` 中原「12 条覆盖缺口」行
   按本复测**更新描述**（不改判类、不删行）。
4. 本文件所有命令均为 `git grep`（**只读**），未连库、未跑 Spark、未改代码。
