# 设计 §12.3 质量 12 项覆盖复测（S3-23 滚动检索证据）

- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 复测起点提交：`2540bd2`（F-55 / S3-22 之后、S3-23 开工前）
- 上一轮复测：`docs/acceptance/s3-22-ads-gmv-net-sale-20260916/QUALITY-RULES-12-COVERAGE-20260916.md`
  （记「已实现 7 / 部分 1 / 未实现 4」）
- 本轮动因：S3-23 把第 9 项从「未实现（无规则码）」变为**在产阻断规则**，故第 9 行的结论
  **必须重测**；其余各行按「不沿用旧结论、逐条重测」原则一并复核。

## 0. 权威锚点与更正（append-only）

### 0.1 锚点

「12 条质量规则」的唯一权威锚点 = **设计 V3.0 §12.3 标题行 L497**（`### 12.3 质量12项`），
逐条要求 = **L499–L510**，记录要素 = **L512**。指导书 V3.0 全文**没有** 12 项清单
（S3-22 已按 append-only 更正「指导书 §7-6」这一错误引用）。本轮**再次实测核对**：
`docs/guidance/项目完整实施指导书 V3.0.md` 中出现「质量」的行仍仅 L30/L56/L68/L69/L149/L156/L163/L171/L262，
无清单、无「§7-6」节名 ⇒ 该更正仍然有效，本轮不新增第二份更正说明。

### 0.2 新增更正：上一轮引用的 3 个**常量名**不存在（码正确、名字写错）

本轮逐行复测时用 `git grep` 核验上一轮（S3-22）覆盖文件里给出的**常量名**，实测结果：

| 上一轮写法 | 实测结论 |
|---|---|
| `QualityRuleCatalog.RULE_REQUIRED_FIELD_NULL_RATE` | **不存在**；实测常量名 = `RULE_REQUIRED_FIELD`（`QualityRuleCatalog.java:68`），码 = `REQUIRED_FIELD_NULL_RATE` |
| `QualityRuleCatalog.RULE_ORDER_ITEM_AMOUNT_FORMULA` | **不存在**；实测常量名 = `RULE_ORDER_ITEM_FORMULA`（`:63`），码 = `ORDER_ITEM_AMOUNT_FORMULA` |
| `QualityRuleCatalog.RULE_DWD_DWS_AMOUNT_RECONCILE` | **不存在**；实测常量名 = `RULE_DWD_DWS_AMOUNT`（`:65`），码 = `DWD_DWS_AMOUNT_RECONCILE` |
| `MappingReason.java:34` 定义 | 该文件不是常量表（枚举/字段形态），实测**违规码**为 `MappingReason.JSON_PARSE_ERROR` 等 |
| `MappingExecutorTest.java:549` | 本轮未在该行确认对应断言；实测到的隔离用例为 `:174`/`:216`/`:334`/`:359` 等 |

**码与语义本身没错**（三个码都真实存在且档位/阈值已登记），错的是**常量名与行号**。
自本轮起按**实测常量名与行号**表述；旧记录**不删不改**，以本条更正覆盖。该更正**不改变**
任何一行的「已实现/未实现」结论。

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

（L512 另要求：每条规则记录作用域/阈值/版本/阶段/实际值/passed/原始+生效严重度；
付款 vs 订单、订单项公式、DWD/DWS 对账三者**独立**；L518 `EVENT_ID_UNIQUE` `dupRateMax=0.0005`
**不得为测试通过改大**。）

## 2. 逐条实测结论（命令全部在本机执行，命中数为实测值）

| # | 结论 | 实测证据 |
|---|---|---|
| 1 | **已实现**（在**采集映射层**，不是 `quality_rule_definition` 规则码） | 本轮实测：`MappingExecutor.java:95` `ctx.violate(MappingReason.JSON_PARSE_ERROR, "raw", detail)`；`MappingExecutor.java:38-39` javadoc「只要存在 1 条**违例**…就隔离整事件：不产出 canonical」（隔离语义 = 不入 canonical/入拒绝记录） |
| 2 | **已实现** | 本轮实测：`MappingExecutor.java:58` `SCHEMA_VERSION = "schema_version"`、`:172-176`（映射该字段）、`:300-314`（规则 6：契约版本必须是画像声明的受支持版本，否则 `UNSUPPORTED_SCHEMA_VERSION` 整事件隔离）；另有质量码 `EVENT_ID_UNIQUE` 覆盖非空 |
| 3 | **已实现** | 本轮实测：`QualityRuleCatalog.java:68` 常量 `RULE_REQUIRED_FIELD`（码 `REQUIRED_FIELD_NULL_RATE`，`V19__quality_rule_definition.sql:146`，阈值 `{"nullRateMax":0.001}`）＋ `:69` `RULE_ENUM_WHITELIST`（码 `ENUM_WHITELIST`）+ `QualityChecker.java:116` 在产判定 |
| 4 | **已实现** | 本轮实测：`QualityRuleCatalog.java:63` 常量 `RULE_ORDER_ITEM_FORMULA`（码 `ORDER_ITEM_AMOUNT_FORMULA`，`V19__quality_rule_definition.sql:139`，阈值 `{"maxAbsDiff":0.01}`）；`DataQualityGateTest.java:224` 引用该码 |
| 5 | **未实现（无一致性规则码）** | 本轮重测：全仓 `RULE_[A-Z_]*REFUND[A-Z_]*` 仅 `AnomalyRules.java:39-40` 的 `RULE_REFUND_RATE_HIGH`/`RULE_FULL_REFUND_RATE_HIGH`（AI 侧**异常阈值**，`AnomalyRules.java:60-61` 按上限告警，**不是**「成功累计退款 ≤ 实付」）；「成功累计退款」的**累计窗口/归属期**未冻结（设计 L428 未裁决）⇒ 结论未变 |
| 6 | **未实现（无规则码）** | 本轮重测：`STATE_JUMP`/`STATE_TRANSITION`/`状态转换合法` 在 `spark-jobs`/`analytics-server` **0 命中**（`status_transition_allowed` 属**源注册状态机**，与订单状态无关） |
| 7 | **已实现** | 本轮实测：`QualityRuleCatalog.java:65` 常量 `RULE_DWD_DWS_AMOUNT`（码 `DWD_DWS_AMOUNT_RECONCILE`，`V19__quality_rule_definition.sql:117`，阈值 `{"maxAbsDiff":0.01}`） |
| 8 | **已实现（S3-22 新增）** | 规则码 `ADS_GMV_NET_SALE_INVARIANT`（BLOCKING）：目录条目 + `RuleSeverity` 三处 + 加性迁移 `V26__quality_rule_ads_gmv_net_sale_invariant.sql` + 在产判定 `AdsQualityJob.gmvNetSaleInvariantCheck` 已接线；本轮守卫实测仍为 39 条目录中的阻断码之一 |
| 9 | **已实现（本轮 S3-23 新增）** | 规则码 `ADS_UV_PV_INVARIANT`（BLOCKING）：`QualityRuleCatalog.java`（常量 + `fixed(...)` 目录条目，定义 38→39）、`RuleSeverity.java`（`REGISTERED`/`of`/`rationale`）、**加性**迁移 `V27__quality_rule_ads_uv_pv_invariant.sql`、在产判定 `AdsQualityJob.uvPvInvariantCheck` 且已接线进 `run()`（`dqc` 规则数 8→9）。守卫实测：`RuleSeverityPathConsistencyTest` 输出 `catalogDefinitions=39 comparisons=78 blockingCodesOnFailure=35`；真链路实测打印 `ADS_UV_PV_INVARIANT\|BLOCKING\|passed=true\|check=1\|err=0\|单行（uv ≤ pv）：pv 实际值=7, uv 实际值=3`；行为边界见 `s3-23-ads-uv-pv-20260916/DESIGN-DIFF-REGISTER-20260916.md` §3/§6 |
| 10 | **部分实现（对账形态，非阈值）** | 本轮重测：规则码 `ADS_DWS_FUNNEL_RATE_RECONCILE`（BLOCKING）判「ADS 与 DWS 的比率是否同一个数」，**不**对支付/浏览比设阈值——与 L508 末句「宽松口径异常**不一概**作为阻断规则」一致 ⇒ 仍为**部分**，不得称「支付/浏览用户比规则已实现」 |
| 11 | **未实现（且已登记待裁决）** | 本轮重测：`LATE_ARRIVAL`/`late_arrival`/`迟到率`/`p95` 仍 0 命中；待裁决记录 `docs/acceptance/p2-02-multiformat-time-20260912/SUGGESTED-RULINGS-20260912.md:148`「问题 B（时间类 DQ 规则）…（**待裁决 D-108**）」 |
| 12 | **已实现** | `MP_EXPORT_FILES`/`MP_EXPORT_CHECKSUM`/`MP_MANIFEST_TABLES`/`MP_REQUIRED_TABLES_NONEMPTY`/`MP_ROW_SHAPE_CONSISTENT`/`MXP_EXPORT_ROWS`/`PUB_FORMAL_PARTITION_MATCH` 等 + 种子 `V23__quality_rule_publish_export_checksum.sql` |

**汇总（实测口径）**：**已实现 8 项**（1/2/3/4/7/8/**9**/12）、**部分实现 1 项**（10，对账形态）、
**未实现 3 项**（**5 退款≤实付、6 状态转换、11 延迟P95/迟到率**）。

> 与上一轮（S3-22：7 / 1 / 4）的唯一差异：**第 9 项由未实现转为已实现**（S3-23）。
> 其余各条结论**逐条重测后未变**。

## 3. 第 9 项本轮落地的边界（不得越界表述）

1. 作用域 = **ADS 大盘暂存分区**（`ads_operation_overview__staging`，本次快照 + 本次 dt）；
   `dws_product_behavior_day` 的**同型**不变量（`DwsSql.scala:96-97` 同样是
   `behavior_type = 'view'` 的 `pv`/`uv`）**本轮未落** ⇒ 不得称「全仓 UV≤PV 已守卫」。
2. **未测**：真实 `spark-submit` + 真实 Hive metastore；`V27` 在真库（3306）的执行；
   真实 HTTP/页面呈现；孤立档（3307 无监听）。
3. 「同过滤条件」这一限定词在实现上被**结构守卫**钉住（生产 SQL 两列过滤谓词逐字相等），
   同时用 `dau > uv` 必须通过的用例把**不同过滤条件**的列排除在判据之外。

## 4. 剩余项的可实施性排序（S3-24 起的滚动候选）

| 项 | 判类 | 需要「发明新业务语义」？ | 备注 |
|---|---|---|---|
| **9 的 DWS 同型站点**（`dws_product_behavior_day`） | A 类（新增规则码 + **加性**迁移） | **否**——同过滤条件，逐行成立 | **下一优先候选**；与已落地的 ADS 站**必须独立成码**（L512），且需先实测该表是否有既有非空守卫（NULL 唯一所有者） |
| 5（L503 退款≤实付） | A 类 | **是**——「成功累计退款」的累计窗口/归属期需先冻结（设计 L428 未裁决） | 受「退款归属期需冻结」约束；跨业务日归属口径**待总控** |
| 6（L504 状态转换合法） | A 类 | **是**——合法转换矩阵需新定义（设计只给要求名） | 语义需新定义 ⇒ 先登记再实现 |
| 11（L509 延迟P95/迟到率） | A 类 | **是**——未来时间/负延迟处理需定义，且**已有 D-108 待裁决** | 命中待裁决 ⇒ 暂不动 |
| 10（L508 剩余部分） | A 类 | **部分**——阈值与严重度需定义（设计已提示不概作阻断） | 现为对账形态；扩为阈值需批注 |

## 5. 边界（不得越界表述）

1. 本文件是**滚动检索证据**，不是「12 项已通过验收」：未实现的 3 项与部分实现的第 10 项
   **未测业务结论**，不得因本文件存在而声称质量专题完成。
2. 「已实现」= 存在**可执行校验点**（规则码/映射违规码）**且**（本轮新码）已被在产链路调用；
   **不等于**在真实数据/真实集群上跑过并通过。
3. 本轮 S3-23 **只**新增第 9 项（ADS 站点）；**没有**顺带实现 DWS 同型站点或任何其它项；
   **没有**改动既有任何规则码、阈值、严重度、已发布迁移或口径。
4. 本文件与 S3-23 登记文件中的所有「未测」项，以 `docs/PROJECT_STATUS.md` 未测清单为准。
