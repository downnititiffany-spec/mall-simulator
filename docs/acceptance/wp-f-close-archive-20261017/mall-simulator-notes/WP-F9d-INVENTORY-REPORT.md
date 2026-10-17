# WP-F9d 盘点报告：F9-GAP-09 异动下沉 + F9-GAP-10 dim_metric 同步

> **规约依据**：`~/workspace/projects/mall-simulator-notes/WP-F9d-INVENTORY.md`  
> **基线分支**：`codex/cloud-v3-development`（commit `60a48fa`，开发主线）  
> **盘点性质**：只读静态代码/DDL/设计契约盘点与落地草案（执行方仅文件读写工具，零命令执行、零实库连接、不触碰 3306/3307、不修改仓库源码与 DDL）  
> **盘点对象**：
> 1. `F9-GAP-09`：统计异动检测结果 ADS 持久化（`ads_metric_anomaly_m`）
> 2. `F9-GAP-10`：指标维度表 `dim_metric` 同步（MySQL `metric_definition` ↔ Hive `dim_metric`）

---

## 一、盘点对象一：F9-GAP-09 统计异动检测结果 ADS 持久化

### 1.1 设计口径与逐字摘录

设计文档与指标字典对异动检测的设计口径有严密、量化的文字定义，关键要求如下：

1. **《项目设计文档 V3.0》§11.6「异常与贡献」逐字摘录**（[`docs/design/项目设计文档 V3.0.md:459-464`](file:///home/hatch/workspace/projects/mall-simulator/docs/design/项目设计文档 V3.0.md#L459-L464)）：
   > 「**至少14个可比历史点**；默认可采用**滚动均值z-score（|z|≥2.5为候选）**或经版本选择的中位数/MAD，不能同一规则无记录随机切算法。标准差/MAD为0用批准绝对阈值；**缺窗口不生成有置信度的统计异常**。IQR留作后续对照，不把旧7点方案同时视为当前默认。」  
   > 「**异常结构**：`metricCode`、`sourceId`、`window`、`actual`、`baseline`、`deviation`、`threshold`、`algorithmVersion`、`snapshotId`、`evidenceRefs`、`limitations`。维度贡献为当前−基准，同口径总变化为0时不算占比；正负变化抵消可使贡献占比超过100%，不强行截断。**只说明关联和核查方向，不做未经实验的因果结论**。」

2. **《指标字典（Metric Dictionary）》§3「对比与异常口径」逐字摘录**（[`docs/contracts/metric-dictionary.md:38-42`](file:///home/hatch/workspace/projects/mall-simulator/docs/contracts/metric-dictionary.md#L38-L42)）：
   > 「异常检测（z-score）：`z = (x_t − 滚动均值) / 滚动标准差`，**窗口 7 或 14 个同口径周期，`|z| ≥ 2.5` 标记候选异常**；标准差为 0 改用绝对变化阈值；偏态分布用 IQR（`Q1−1.5×IQR` / `Q3+1.5×IQR`）。业务阈值示例：退款率 >15%、库存覆盖 <3 天。**异常只是待关注信号，不是因果结论**。」

3. **设计口径核心要素提炼**：
   - **历史窗口要求**：基线要求至少 14 个历史对比点（窗口不足时严格 fail-closed，禁止虚构置信度）。
   - **算法选型**：首选滚动均值 Z-Score（`|z| >= 2.5`），方差为 0 回落至绝对阈值；备选 IQR / MAD。
   - **结构规范**：必须包含 11 个标准字段（指标码、源 ID、窗口、当期值、基线、偏差、阈值、算法版本、快照 ID、证据链引用、局限性声明）。
   - **严禁过度推断**：任何异动仅作为关联与核查线索，禁止给出未经控制变量实验的因果结论。

---

### 1.2 现行实现算法与差异清单

经对当前分支代码全链路静态检索，现行异动计算与消费流程位于 `analytics-server/ai-decision` 模块，其实现真相如下：

#### 1. 现行实现细节与代码锚点
- **数据输入与时间序列构造**（[`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java:106-107, 151-195`](file:///home/hatch/workspace/projects/mall-simulator/analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidenceBuilder.java#L106-L195)）：
  - 构造入口：`EvidenceBuilder.build()` 通过 `AnalysisService.overview(snapshotId)` 获取当前快照的标量指标值。
  - 上期基准对比：`EvidenceBuilder.compare()` 查找前一业务日（`day.minusDays(1)`）的已发布快照 `previousDaySnapshot`。
  - **诚实边界声明**（`EvidenceBuilder.java:152-155` 原文）：
    > 「上期对比：**只支持单日**。当前实现的诚实边界是 `metric_value` 只提供「快照 × 单日聚合」，所以本方法签名只接受一个业务日（不是区间）——跨天请求在 `build` 里就登记为不支持，从结构上避免"拿某一天的值冒充多日窗口"。」
  - 当无前一日快照时，向证据包登记警告 `WARN_NO_COMPARISON_PERIOD`，`deltaRates` 返回空 Map。
- **异动判定逻辑**（[`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/AnomalyRules.java:22-138`](file:///home/hatch/workspace/projects/mall-simulator/analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/AnomalyRules.java#L22-L138)）：
  - **根本未实现 Z-Score、MAD 或 IQR 统计窗口算法**；全量判定均基于内存写死的绝对阈值与单日环比变化率：
    1. `refund_rate > 0.30`（`RULE_REFUND_RATE_HIGH`，严重级别 `MEDIUM`，阈值 `REFUND_RATE_MAX = 0.30`）
    2. `full_refund_rate > 0.15`（`RULE_FULL_REFUND_RATE_HIGH`，严重级别 `HIGH`，阈值 `FULL_REFUND_RATE_MAX = 0.15`）
    3. `buy_rate < 0.30`（`RULE_BUY_RATE_LOW`，严重级别 `MEDIUM`，阈值 `BUY_RATE_MIN = 0.30`）
    4. 核心指标日环比偏移 `|deltaRate| >= 0.30`（`RULE_PERIOD_SHIFT`，严重级别 `LOW`，针对 `gmv`, `net_sale`, `pv`, `uv`, `dau`, `paid_order_cnt` 六大指标）
    5. 质量门未通过规则（`RULE_QUALITY_RULE_FAILED`，质量门 BLOCKING 阻断则为 `HIGH`，否则为 `MEDIUM`）
  - 非因果文本声明：每条异常结论后置拼接 `"（可能相关，不构成因果）"`（`AnomalyRules.java:45`）。
- **输出结构**（[`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidencePackage.java:139-142`](file:///home/hatch/workspace/projects/mall-simulator/analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/evidence/EvidencePackage.java#L139-L142)）：
  - 数据模型为 `record AnomalyCandidate(ruleCode, metricCode, severity, observed, threshold, deviation, statement, evidenceRef)`。
- **消费方与下游流向**：
  - `EvidenceTemplates.java:160-178, 203-216`：渲染至分析报告第 ⑤ 节「核查行动」以及报告执行摘要中。
  - `ReportInstanceService.java:383-386, 461, 202`：在生成 `ORDER_ANALYSIS` 或 `PRODUCT_ANOMALY` 报告实例时注入并序列化存入 `report_instance.artifact_json` 供前端展示。

#### 2. 设计口径 vs 现行实现差异清单

| 维度 | 设计 V3.0 / 指标字典口径 | 现行代码实现（EvidenceBuilder / AnomalyRules） | 差异定性 |
|---|---|---|---|
| **算法原理** | 滚动 14 点均值 Z-Score（`\|z\|>=2.5`）/ MAD / IQR 偏态分布 | 纯硬编码阈值（退款率>30%、转化率<30%、日环比变动>30%） | **完全未对齐**（统计模型退化为固定规则引擎） |
| **时间窗口** | 至少 14 个连续可比历史周期点 | 仅比对业务日前 1 天单点（DOD 单日环比）；缺日前快照直接警告并置空 | **严重不足**（1 个历史点无法计算均值与方差） |
| **算法版本控制**| 结构中声明 `algorithmVersion`，明确算法选择与版本迁移记录 | 无算法版本字段，硬编码在静态类 `AnomalyRules` | **缺少版本追踪** |
| **输出字段** | 11 列（含 `sourceId`, `window`, `baseline`, `algorithmVersion`, `limitations` 等）| 8 列 `AnomalyCandidate`（仅记录观测值、阈值、规则码、偏离量、证据链与语句） | **缺少 5 个审计字段** |
| **计算与存储层位**| 原设想沉淀为数仓离线 ADS 宽表（`ads_metric_anomaly_m`） | Spring Boot Java 内存计算，作为 JSON 字段内嵌于 `report_instance` | **未在数仓 / 指标库建表持久化** |
| **因果边界控制**| 明确核查方向，严禁无实验推断因果 | 逐句追加固定后缀 `"（可能相关，不构成因果）"` | **形式上对齐约束** |

---

### 1.3 数据就绪度与单业务日现实

评估在数仓离线端（Spark 作业）或平台侧实现 14 天滑动窗口异动检测的**客观数据就绪度**：

1. **现行已发布 ADS 时间序列输入源盘点**：
   - `ads_sale_trend_m`（粒度：`[snapshot_id, dt]`，包含 `order_count`, `buyer_count`, `sale_amount`, `avg_order_value`, `net_sale_amount`）。
   - `ads_active_trend_m`（粒度：`[snapshot_id, dt]`，包含 `dau`, `behavior_count`）。
   - `ads_operation_overview_m`（粒度：`[snapshot_id, dt]`，包含 `pv`, `uv`, `dau`, `refund_rate`, `full_refund_rate`, `repeat_rate` 等 14 项全站标量）。
2. **当前真实业务日数量级与现实约束**：
   - **单业务日运行现实**：依据统一流水线日常批次与近期压测执行事实（如 `WP-F7c-REPORT.md:21-27` 大规模测试 `S20260918_5`），批次作业均以**单业务日**（如 `businessDate = 20260918`）切片驱动。
   - **ADS 分区行数现状**：在单业务日批次执行后，`ads_sale_trend_m` 与 `ads_active_trend_m` 在该快照内**通常仅存在 1 行记录**（即当前业务日自身的汇总数据）。
   - **窗口类统计算法的可运行性**：
     - 若要在数仓或离线端执行 14 天滚动均值 Z-Score：
       $$\mu = \frac{1}{N}\sum_{i=1}^N x_i, \quad \sigma = \sqrt{\frac{1}{N-1}\sum_{i=1}^N (x_i - \mu)^2}, \quad z = \frac{x_t - \mu}{\sigma}$$
     - 当历史分区点数 $N < 14$（现实中通常 $N = 1$）时，样本标准差 $\sigma$ 无法计算（自由度为 0 导致分母为 0）；
     - 即使退化到绝对阈值判定，在仅有 1 个离散点的情况下，所谓“滚动窗口”已名存实亡。
   - **结论**：在当前评测与运行环境下，**缺乏跨连续 14 个业务日的历史物化分区积累**。任何在数仓端强行编写的 14 天滑动窗口 Spark SQL（如 `OVER (PARTITION BY ... ORDER BY dt ROWS BETWEEN 13 PRECEDING AND CURRENT ROW)`）在单业务日分区下都只会退化为单行自身比较，无法产出具备统计置信度的 Z-Score 结论。

---

### 1.4 落地草案 vs 维持现状关闭理由

#### 方案 A：若立项下沉实施（ADS 持久化落地草案）
若总控决策仍需将统计异动检测结果物理下沉到 MySQL 指标库及数仓 ADS 层，推荐草案如下：

1. **表结构设计草案（`ads_metric_anomaly_m`）**：
   - **物理表名**：`ads_metric_anomaly_m`（MySQL `analytics_metric`）及 `ads_metric_anomaly`（Hive数仓）。
   - **主键与粒度**：复合主键 `(snapshot_id, dt, metric_code, rule_code)`，粒度为「快照 × 业务日 × 指标 × 规则」。
   - **字段定义草案**：
     ```sql
     CREATE TABLE IF NOT EXISTS ads_metric_anomaly_m (
         snapshot_id        VARCHAR(64)   NOT NULL COMMENT '快照ID',
         dt                 VARCHAR(10)   NOT NULL COMMENT '业务日期 yyyy-MM-dd',
         metric_code        VARCHAR(64)   NOT NULL COMMENT '指标编码',
         rule_code          VARCHAR(64)   NOT NULL COMMENT '规则编码(ZSCORE_ALERT/THRESHOLD_HIGH等)',
         severity           VARCHAR(16)   NOT NULL COMMENT '告警级别(LOW/MEDIUM/HIGH)',
         observed_value     DECIMAL(18,4) NULL     COMMENT '当期观测值',
         baseline_value     DECIMAL(18,4) NULL     COMMENT '基准值(14日滚动均值或上期值)',
         deviation_value    DECIMAL(18,4) NULL     COMMENT '偏差量(observed - baseline)',
         threshold_value    DECIMAL(18,4) NULL     COMMENT '判定阈值(|z|阈值2.5或固定阈值)',
         window_size        INT           NOT NULL DEFAULT 1 COMMENT '计算窗口天数(1/7/14)',
         algorithm_version  VARCHAR(32)   NOT NULL COMMENT '算法版本(zscore-v1/rule-v1)',
         statement          VARCHAR(255)  NOT NULL COMMENT '文字描述(含非因果声明)',
         evidence_ref       VARCHAR(128)  NULL     COMMENT '证据链引用',
         created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
         PRIMARY KEY (snapshot_id, dt, metric_code, rule_code)
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计异动检测结果表';
     ```
2. **Flyway 版本顺延**：
   - 物理路径：`analytics-server/platform-app/src/main/resources/db/metric/`。
   - 版本编号：当前已达 `V10__...`（及 meta 库 `V20`），指标库顺延创建 `V11__ads_metric_anomaly.sql`。
   - Catalog 扩展：在 `MetricAdsCatalog.java` 的 `ALL` 列表中注册 `ads_metric_anomaly_m`，白名单写入校验生效。
3. **计算层位选型权衡**：
   - *选项 1：Spark ADS 离线直算*：
     - 代价：需要重写 Spark 作业（如 `AdsAnomalyJob.scala`），跨分区扫描近 14 天 `ads_operation_overview`；在单业务日运行时直接因缺历史分区失败或全部输出 NULL，且大幅增加批处理耗时。
   - *选项 2：平台侧发布时落库（MetricPublisher / EvidenceBuilder）*：
     - 优势：直接复用现行 `AnomalyRules.evaluate()` 计算结果，在指标发布阶段批量 `INSERT` 到 `ads_metric_anomaly_m`；与前端、AI 模块当前输出百分之百同源无缝衔接。
4. **一致性验收判据草案**：
   - 表内行数与 `EvidencePackage.anomalies()` 列表大小完全相等；
   - `observed_value`、`threshold_value`、`deviation_value` 与内存计算结果精度逐分一致（四位小数对齐）。

#### 方案 B：维持现状关闭理由（推荐裁决）
1. **数据事实约束（反形式主义）**：
   - 现阶段模拟环境与压测场景以单日数据生成和单业务日流水线为主，根本不存在连续 14 天的真实生产分区历史；
   - 强行建立 14 天 Z-Score ADS 表，产出的字段要么全为 `NULL`，要么全为“窗口不足降级警告”，既不具备统计解释力，又徒增一张结构复杂的空表。
2. **现行架构已闭环且完全满足需求**：
   - AI 模块当前通过 `AnomalyRules` 在内存中轻量计算，已完整打通了从指标快照到 `EvidencePackage`，再到 Markdown 报告（`EvidenceTemplates`）和持久化报告制品（`report_instance.artifact_json`）的全链路；
   - 现行实现已严格声明单日边界（`WARN_NO_COMPARISON_PERIOD`）与非因果红线，架构诚实，没有造成任何性能瓶颈。
3. **关闭建议**：总控裁定 `F9-GAP-09` 维持现状关闭，不立项开发数仓 ADS 异动表，后续若引入多月真实连续流水线后再行评估。

---

## 二、盘点对象二：F9-GAP-10 指标维度表 `dim_metric` 同步

### 2.1 现状与代码 / DDL 证据

#### 1. 数仓 DDL 与运行时 Schema 所有权现状
- **静态 DDL 已存在**（[`warehouse/ddl/02-dims.sql:72-83`](file:///home/hatch/workspace/projects/mall-simulator/warehouse/ddl/02-dims.sql#L72-L83)）：
  ```sql
  CREATE EXTERNAL TABLE IF NOT EXISTS ${WAREHOUSE_PREFIX}_dim.dim_metric (
      metric_code        STRING,
      metric_name        STRING,
      formula            STRING COMMENT '计算口径公式',
      grain              STRING COMMENT '统计粒度',
      default_time_field STRING,
      allowed_dimensions STRING,
      definition_version STRING COMMENT '指标字典版本（v1...）'
  )
  COMMENT '指标口径字典（Hive 侧副本，唯一来源在平台 MySQL metric_definition）'
  STORED AS PARQUET
  LOCATION '/user/hive/warehouse/${WAREHOUSE_PREFIX}_dim.db/dim_metric';
  ```
  *注：DDL 注释已开宗明义声明「唯一来源在平台 MySQL metric_definition」。*
- **运行时初始化排除 `dim_metric`**（[`spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala:74-96`](file:///home/hatch/workspace/projects/mall-simulator/spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala#L74-L96)）：
  - `LocalSchemaInitJob` 作为数仓 Schema 的唯一代码所有者，仅注册创建了 `dim_user`、`dim_product`、`dim_date`、`dim_region` 四张维表；**显式不建 `dim_metric`**。
- **守卫测试断言锁定**（[`spark-jobs/src/test/scala/com/graduation/analytics/DwdDimSchemaOwnerSpec.scala:30-31, 105-115, 284-297`](file:///home/hatch/workspace/projects/mall-simulator/spark-jobs/src/test/scala/com/graduation/analytics/DwdDimSchemaOwnerSpec.scala#L30-L297)）：
  - 代码显式声明：`val UnownedDimTables: Set[String] = Set("dim_metric")`。
  - 测试用例严密断言：
    1. `LocalSchemaInitJob` 的受管表清单中**必须不包含** `dim_metric`；
    2. 全仓 Spark 作业中**必须没有任何写入 `dim_metric` 的代码**（写入引用计数严格为 0）。

#### 2. MySQL `metric_definition` 元数据表维护方式与字段清单
- **实体与表归属**（[`analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/dict/MetricDefinition.java:16-34`](file:///home/hatch/workspace/projects/mall-simulator/analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/dict/MetricDefinition.java#L16-L34)）：
  - 表名：`metric_definition`，归属于 **`analytics_meta`** 库（通过 `AnalyticsApplication.java:32` 的 `@MapperScan` 纳入持久化扫描）。
  - 类文档确认（L11-14）：
    > 「字典表属 **analytics_meta**（§17.2 表所有权），流水线发布前要用它做「指标码/口径版本」对账。原先放在未扫描的 `metric.mapper` 包内等于「有 mapper 但没人能用」，属 R7-1 的遗留缺口。」
- **字段清单对比（MySQL vs Hive）**：

| MySQL `metric_definition` 字段 | Java 实体属性类型 | Hive `dim_metric` 对应字段 | 字段语义与差异说明 |
|---|---|---|---|
| `metric_code` | `String` (PK) | `metric_code` (STRING) | 指标英文唯一编码（如 gmv, uv） |
| `metric_name` | `String` | `metric_name` (STRING) | 指标中文名称（如 销售额） |
| `formula` | `String` | `formula` (STRING) | 计算公式描述 |
| `grain` | `String` | `grain` (STRING) | 统计粒度（日/周/月） |
| `default_time_field` | `String` | `default_time_field` (STRING) | 默认业务时间字段（如 event_time） |
| `unit` | `String` | *无* | 指标单位（元 / 人 / %），Hive 侧遗漏 |
| *无* | *无* | `allowed_dimensions` (STRING) | 允许分析维度，MySQL 侧未收录 |
| `definition_version` | `String` | `definition_version` (STRING) | 指标定义版本（如 v1） |

- **行数口径**：依据契约文档 `docs/contracts/metric-dictionary.md:14-33`，基准口径首批指标共计 **15 项**。

---

### 2.2 消费方全仓检索与 PROJECT_STATUS 登记上下文

1. **消费方全仓检索结论**：
   - **数仓侧（Hive / Spark）**：全仓检索结论为**绝对零消费（0 处引用）**。没有任一 SQL、DWD/DWS/ADS 作业或 Spark 测试去关联（JOIN）或读取 `dim_metric`。所有的数仓聚合指标在 `AdsSql.scala` 中均由 Spark 代码直算，不依赖数仓维度表来解析公式。
   - **应用服务侧（Java / Web）**：
     - `AnalysisService.java:93, 775-791`：仅有一处直接调用 `definitionMapper.selectList(null)`，用于在前端“查看指标口径”弹窗中展示指标名称、公式与单位。
     - `AnalysisService.java:250`：用于将英文 `metricCode` 解析为中文 `metricName`。
2. **PROJECT_STATUS 中的原始登记上下文**（[`docs/PROJECT_STATUS.md:3259`](file:///home/hatch/workspace/projects/mall-simulator/docs/PROJECT_STATUS.md#L3259) 原文）：
   > 「`LocalSchemaInitJob` 建立唯一 schema owner，`DimSql` 与 `DimensionBuildJob` 每次完整覆盖五行并核对输出数；扩展 schema owner/投影/Frozen 守卫及 DIM 重跑用例。**dim_metric 的 MySQL 同步仍未解决**。」
   - **登记历史背景**：2026-10-12 在规范化 `dim_region` 等静态字典表的所有权时，数仓团队发现静态 DDL 中残留了 `dim_metric`，但 `LocalSchemaInitJob` 无法接管其写入（因为它是元数据字典而非 ODS 事件衍生），因此作为未闭环遗留项记入了技术账本。

---

### 2.3 落地草案 vs 以代码/MySQL为权威关闭理由

#### 方案 A：若立项同步实施（落地草案）
1. **同步方向判定**：
   - **严格为单向同步：MySQL (`analytics_meta.metric_definition`) → Hive (`dim_metric`)**。
   - 判定依据：指标口径是由平台管理面/文档契约定义的元数据，`02-dims.sql:81` 亦明确规定 MySQL 是唯一真相来源。数仓绝无反向修改指标定义的业务场景，“双向同步”属于伪需求，且会引入严重的数据冲突和双脑分裂风险。
2. **作业层位与实现方案**：
   - 方式：在 `DimensionBuildJob.scala` 增设子阶段，或在 `LocalSchemaInitJob.scala` 中扩展 schema owner 并利用 Spark JDBC 读取 MySQL `metric_definition`，转写为 Parquet 覆盖写入 `/user/hive/warehouse/.../dim_metric`。
   - 依赖修改：必须修改 `DwdDimSchemaOwnerSpec.scala`，将 `dim_metric` 从 `UnownedDimTables` 白名单移除并加入 owner 管理。
3. **对账判据草案**：
   - 行数对账：`COUNT(dim_metric) == COUNT(metric_definition)`（恒等于 15）；
   - 字段对账：逐行的 `(metric_code, formula, definition_version)` 计算 SHA-256 哈希值必须两端一致。

#### 方案 B：以代码目录/MySQL为权威、数仓不建表关闭（强烈推荐裁决）
1. **零下游消费事实**：
   - 数仓内部离线计算完全基于代码化的算子与 SQL，数仓侧没有任何任务需要与 `dim_metric` 进行关联查询；
   - 平台的指标展示与口径查询完全由 Spring Boot 直连 MySQL `analytics_meta` 承载，链路短且延迟低。
2. **消除无价值的跨组件同步负担**：
   - 为一张仅有 15 行且长期静态的配置表引入 Spark JDBC 依赖与跨存储同步机制，会增加流水线故障点（网络波动、凭据管理、Schema 漂移）；
   - 现行测试 `DwdDimSchemaOwnerSpec` 已通过 `UnownedDimTables` 明确防护了其不被非法写入。
3. **关闭与清理建议**：
   - 总控裁定 `F9-GAP-10` 正式关闭；
   - 认定 MySQL `metric_definition` 与 `metric-dictionary.md` 为唯一权威来源，数仓侧无需建表同步；
   - 后续在 `02-dims.sql` 中将废弃的 `dim_metric` DDL 标记废弃或移除，同步解除 `PROJECT_STATUS.md:3259` 挂账。

---

## 三、总控待裁定项清单

为推动上述两项遗留账目最终定案，提请总控进行以下两项明确裁定：

### 待裁定项 1（针对 F9-GAP-09 统计异动检测结果 ADS 持久化）：
> **裁定问题**：
> 是否采信「当前模拟数据缺乏连续 14 个业务日历史分区、现行 `EvidenceBuilder` 内存阈值判定与非因果声明已在报告制品中闭环」的事实，**直接维持现状关闭 F9-GAP-09**，还是立项实施新增 `ads_metric_anomaly_m` 表与平台侧落库改造？

### 待裁定项 2（针对 F9-GAP-10 指标维度表 `dim_metric` 同步）：
> **裁定问题**：
> 是否采信「数仓离线计算对此表零消费、指标口径由平台 MySQL `metric_definition` 单一权威提供」的事实，**裁定数仓侧不建表同步、正式关闭 F9-GAP-10 并结清 PROJECT_STATUS L3259 挂账**，还是立项编写 Spark 单向同步作业？

---

WPF9DINV-DONE
