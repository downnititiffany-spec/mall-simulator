# 设计 §12.3 质量 12 项覆盖复测（S3-22 滚动检索证据）

- 日期（项目内）：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 复测起点提交：`8763667`（F-54 / S3-21 之后、S3-22 开工前）
- 上一轮复测：`docs/acceptance/s3-21-products-sort-20260916/QUALITY-RULES-12-COVERAGE-20260916.md`
  （记「已实现 6 / 部分 1 / 未实现 5」）
- 本轮动因：S3-22 把第 8 项从「未实现（无规则码）」变为**在产阻断规则**，
  故第 8 行的结论**必须重测**，其余各行按「不沿用旧结论、逐条重测」原则一并复核。

## 0. 引用更正（append-only）

S3-21 期在 `docs/PROJECT_STATUS.md` backlog 行与 F-54 事实记录里，把「12 条质量规则」的权威来源写成
**「指导书 §7-6」**。本轮**实测核对**：指导书 V3.0（281 行）全文出现「质量」的行仅
L30/L56/L68/L69/L149/L156/L163/L171/L262，**没有** 12 项清单、也**没有**「至少分别实现和验收」的措辞
⇒ 该引用**是错的**（且「§7-6」不指向任何质量清单）。唯一权威锚点是：

- **设计 V3.0 §12.3 标题行 L497**（`### 12.3 质量12项`），逐条要求 = **L499–L510**；
- 记录要素与「不得合并校验」的要求 = **L512**。

自本轮起按正确锚点表述；旧记录**不删不改**，以本条更正说明覆盖。

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
| 1 | **已实现**（在**采集映射层**，不是 `quality_rule_definition` 规则码） | `MappingReason.java:34` 定义、`MappingExecutor.java:95` 违规即隔离、`MappingExecutorTest.java:549` |
| 2 | **已实现** | `MappingExecutor.java:58,172-176,304-310`（schema_version 存在/类型/版本支持）+ 质量码 `EVENT_ID_UNIQUE` |
| 3 | **已实现** | `QualityRuleCatalog.java` `RULE_REQUIRED_FIELD_NULL_RATE` / `RULE_ENUM_WHITELIST` + 登记定义 |
| 4 | **已实现** | `QualityRuleCatalog.RULE_ORDER_ITEM_AMOUNT_FORMULA` + 登记定义 |
| 5 | **未实现（无一致性规则码）** | 全仓 `RULE_[A-Z_]*REFUND[A-Z_]*` 仅 `RULE_REFUND_RATE_HIGH`/`RULE_FULL_REFUND_RATE_HIGH`（异常阈值，**不是**「成功累计退款 ≤ 实付」） |
| 6 | **未实现（无规则码）** | `STATE_JUMP`/`STATE_TRANSITION` 0 命中（`status_transition_allowed` 属**源注册状态机**，与订单状态无关） |
| 7 | **已实现** | `QualityRuleCatalog.RULE_DWD_DWS_AMOUNT_RECONCILE` + 种子 `V19__quality_rule_definition.sql` |
| 8 | **已实现（本轮 S3-22 新增）** | 规则码 `ADS_GMV_NET_SALE_INVARIANT`（BLOCKING）：`QualityRuleCatalog.java`（常量 + `fixed(...)` 目录条目）、`RuleSeverity.java`（`REGISTERED`/`of`/`rationale`）、**加性**迁移 `V26__quality_rule_ads_gmv_net_sale_invariant.sql`、在产判定 `AdsQualityJob.gmvNetSaleInvariantCheck` 且已接线进 `run()`。守卫实测：`RuleSeverityPathConsistencyTest` 输出 `catalogDefinitions=38 comparisons=76 blockingCodesOnFailure=34`，新码在 BLOCKING 清单内；链路实测打印 `ADS_GMV_NET_SALE_INVARIANT\|BLOCKING\|passed=true\|check=1\|err=0`（黄金值 2042.00/1493.00）。行为边界见 `s3-22-ads-gmv-net-sale-20260916/DESIGN-DIFF-REGISTER-20260916.md` §0/§7 |
| 9 | **未实现（无规则码）** | 全仓 `uv *<=? *pv` 相关判定 0 命中；本轮**另行实测确认**：`AdsQualityJob.keyPredicates` 对 `ads_operation_overview` **只**要求 `pv/uv/dau` 非空（`sale_amount`/`net_sale_amount` 此前无任何在产断言，S3-22 只补了金额不变量，**未**补 UV≤PV）⇒ 缺口仍真实存在；`uv`/`pv` 两列已在同一行（`MetricAdsSpec`）⇒ **不需要新列** |
| 10 | **部分实现（对账形态，非阈值）** | 规则码 `ADS_DWS_FUNNEL_RATE_RECONCILE`（BLOCKING）判「ADS 与 DWS 的比率是否同一个数」，**不**对支付/浏览比设阈值——与 L508 末句「宽松口径异常**不一概**作为阻断规则」一致 ⇒ 仍为**部分**，不得称「支付/浏览用户比规则已实现」 |
| 11 | **未实现（且已登记待裁决）** | `LATE_ARRIVAL`/`late_arrival`/`迟到率`/`p95` 均 0 命中；待裁决记录 `docs/acceptance/p2-02-multiformat-time-20260912/SUGGESTED-RULINGS-20260912.md:148`「问题 B（时间类 DQ 规则）…（**待裁决 D-108**）」 |
| 12 | **已实现** | `MP_EXPORT_FILES`/`MP_EXPORT_CHECKSUM`/`MP_MANIFEST_TABLES`/`MP_REQUIRED_TABLES_NONEMPTY`/`MP_ROW_SHAPE_CONSISTENT`/`MXP_EXPORT_ROWS`/`PUB_FORMAL_PARTITION_MATCH` 等 + 种子 `V23__quality_rule_publish_export_checksum.sql` |

**汇总（实测口径）**：**已实现 7 项**（1/2/3/4/7/**8**/12）、**部分实现 1 项**（10，对账形态）、
**未实现 4 项**（**5 退款≤实付、6 状态转换、9 UV≤PV、11 延迟P95/迟到率**）。

> 与上一轮（S3-21：6 / 1 / 5）的唯一差异：**第 8 项由未实现转为已实现**（S3-22）。
> 其余各条结论**逐条重测后未变**。

## 3. 剩余项的可实施性排序（S3-23 起的滚动候选）

| 项 | 判类 | 需要「发明新业务语义」？ | 备注 |
|---|---|---|---|
| **9（L507 UV≤PV）** | A 类（新增规则码 + **加性**迁移） | **否**——设计 L507 已逐字给定不等式，同一行即有 `uv`/`pv`（本轮已实测确认） | **下一优先**：与第 8 项同族、同层、同落点（`AdsQualityJob`），但**必须独立成码**（L512 禁合并） |
| 5（L503 退款≤实付） | A 类 | **是**——「成功累计退款」的累计窗口/归属期需先冻结（设计 L428 未裁决） | 受「退款归属期需冻结」约束；跨业务日归属口径**待总控** |
| 6（L504 状态转换合法） | A 类 | **是**——合法转换矩阵需新定义（设计只给要求名） | 语义需新定义 ⇒ 先登记再实现 |
| 11（L509 延迟P95/迟到率） | A 类 | **是**——未来时间/负延迟处理需定义，且**已有 D-108 待裁决** | 命中待裁决 ⇒ 暂不动 |
| 10（L508 剩余部分） | A 类 | **部分**——阈值与严重度需定义（设计已提示不概作阻断） | 现为对账形态；扩为阈值需批注 |

## 4. 边界（不得越界表述）

1. 本文件是**滚动检索证据**，不是「12 项已通过验收」：未实现的 4 项与部分实现的第 10 项
   **未测业务结论**，不得因本文件存在而声称质量专题完成。
2. 「已实现」= 存在**可执行校验点**（规则码/映射违规码）**且**（本轮新码）已被在产链路调用；
   **不等于**在真实数据/真实集群上跑过并通过。
3. 本轮 S3-22 **只**新增第 8 项；**没有**顺带实现第 9 项或任何其它项；**没有**改动既有任何规则码、
   阈值、严重度、已发布迁移或口径。
4. 本文件与 S3-22 登记文件中的所有「未测」项，以 `docs/PROJECT_STATUS.md` 未测清单为准。
