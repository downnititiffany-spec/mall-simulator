# S3-16 设计差异登记（2026-09-16）

> 子项：**服务层消费 RFM 原值（R/F/M）与观察窗口**（阶段4 查询与分析服务，`PROJECT_STATUS` 登记项 L217）
> 分支：`feature/v3-development`　前置：`12d8ece`（F-48，S3-15）
> 证据目录：`.verify/s316/**`（gitignored）与 `$env:TEMP\s316-*.log`

## 0. 一句话结论

Hive ADS（`r_days`/`f_count`/`m_amount`/`period_start`/`period_end`）→ mxp 清单 → MySQL 迁移 `V4` → Java 白名单**四层早在 S2-06/S3-01 已贯通**，唯一缺口是**服务层从未读这五列**：`RfmService` 把 `amount` 恒置 `null` 并**无条件**挂 `RFM_AMOUNT_UNAVAILABLE`，R 值走服务端 `calc_date − last_buy_date` 自算，F 原值与观察窗口**整个不返回**。本轮把「消费方」补齐，**只动服务层与文档**，不新增迁移、不改 DDL、不碰 Spark 主链。

## 1. 设计原文（逐字引用，标尺）

1. **指导书 V3.0 §7 阶段4 L156**：「MetricStore/专题服务返回明确 source、snapshot、definitionVersion、时间和质量信息。」
2. **指导书 V3.0 §7 阶段4 L159**（本子项邻域）：「鉴权来自当前会话，拒绝伪造用户/来源；发布账号与只读查询账号分离。」
3. **设计 V3.0 §9.3 L334**：「| ads_user_profile | ads_user_profile_m | 历史已发布，RFM完整性需补 |」⇒ 完整性补齐的**落点**就是本行（原值列已于 S2-06 落到两侧）。
4. **设计 V3.0 §11.2 L435**：「| RFM | R距最后有效购买天数；F观察期有效订单数；M观察期金额 | M精确退款口径见G-05 |」⇒ R/F/M 三者都是**原值语义**，不是分档。
5. **设计 V3.0 §11.4 L449**（后半句）：「**M不明时保留null+警告，不能把缺列当0。**」⇒ M **可得时必须给真值**；只有「不明」才允许 null+警告。
6. **设计 V3.0 §15 L676**：「| 用户/RFM | R/F/M原值与score、8群体、观察期、复购 | 小样本仅演示；M缺失null |」⇒ 页面要读的是**原值 + 观察期**。
7. **设计 V3.0 §16.2 L703**：「| GET analysis/sales、products、funnel、users、rfm | 日期/TopN/snapshot筛选，返回专题ViewModel |」
8. **设计 V3.0 §16.4 L732**：「…RFM_AMOUNT_UNAVAILABLE为明确数据不足；…**所有新错误码统一owner，不复制到Java/Scala/页面多处无校验常量。**」⇒ 本轮新增编码的 owner 只能是 `AnalysisViewModel`。
9. **`docs/contracts/analysis-viewmodel-r7-4.md` 前言 L4**：「本文件是 R7-4 后端与前端唯一契约，两侧实现必须逐字段对齐；**若实现中确需改动，先改本文件再改代码。**」⇒ 本轮按此程序**先**改契约文档再加性扩字段。
10. **`PROJECT_STATUS.md` L217（登记项原文）**：「新增 `r_days`/`f_count`/`m_amount`/`period_start`/`period_end` **尚无消费方**：`RfmService`/`AnalysisViewModel` 仍按旧 12 列直通，审计 §13.4「RFM 金额缺失／用 m 分求和冒充金额」在服务层**仍未修** | **阶段4 开发项**（非阻塞），进入阶段4 服务层时按设计 §13.4 消费原值，禁止再用分档求和冒充金额」。

## 2. 本轮冻结的事实（实测，非推测）

### 2.1 五列已在四层贯通（本轮开工前的既有事实，逐层点名）

| 层 | 位置 | 实测证据 |
|---|---|---|
| Hive ADS SQL | `spark-jobs/src/main/scala/.../sql/AdsSql.scala` | L326-330 `DATEDIFF(pe, tp.last_buy_date) AS r_days` / `tp.order_count AS f_count` / `tp.sale_amount AS m_amount` / `tp.ps AS period_start` / `tp.pe AS period_end` |
| Hive 建表（唯一所有者） | `.../job/LocalSchemaInitJob.scala` | L171-172 `calc_date STRING, r_days INT, f_count BIGINT, m_amount DECIMAL(18,2), period_start STRING, period_end STRING)` |
| 参考副本 | `warehouse/ddl/04-ads.sql` | L134-138 五列齐备（含注释语义） |
| mxp 清单（Spark 侧镜像） | `.../metric/MetricAdsSpec.scala` | L44-47 `Seq(... "r_days", "f_count", "m_amount", "period_start", "period_end")` |
| MySQL 迁移 | `db/metric/V4__ads_user_profile_rfm_raw.sql` | L13-17 五列 `ADD COLUMN`（`NOT NULL DEFAULT 0/''`） |
| Java 白名单 | `.../metric/MetricAdsCatalog.java` | L47-51 `ads_user_profile_m` 列清单含五列 |

⇒ 本轮**不需要**任何迁移/DDL/Spark 改动；缺口在**读取侧**。

### 2.2 服务层缺口（本轮开工前实测，逐条点名）

| 现象 | 位置 | 实测 |
|---|---|---|
| M 原值不读、恒 null | `RfmService.java` L113 / L120-121 | `new RfmSegment(..., null, ...)` 硬编码 `null` |
| 金额警告无条件挂 | `RfmService.java` L136 | `warnings.add(WARN_RFM_AMOUNT_UNAVAILABLE)` 不看列是否存在 |
| R 原值不读、服务端自算 | `RfmService.java` L100/L166-176 | `acc.addRecency(recencyDays(row))`，基准日取 `calc_date`（非 `period_end`） |
| F 原值不读 | `RfmSegment` 定义 L57-58 | 无 F 字段 |
| 观察窗口不返回 | `RfmProfile` L74-77、`AnalysisService.UsersData/RfmData` L120-125 | 无窗口字段 |

⇒ 这正是 `PROJECT_STATUS` L217 登记项的**准确含义**（登记项里「用 m 分求和冒充金额」的措辞与当前代码不符：现状既没有分档求和也没有金额，而是 null+警告；本轮按**代码实测**为准，不沿用该措辞）。

### 2.3 既有测试夹具不含五列（⇒ 旧用例天然覆盖「列缺失降级」路径）

`RfmServiceTest.profileRow(...)`（L153-165）与 `AnalysisServiceTest.adsRows()`（L373-382）构造的行**没有** `m_amount`/`r_days`/`f_count`/`period_start`/`period_end` ⇒ 旧断言（`amount()==null`、`warnings==[RFM_AMOUNT_UNAVAILABLE]`）走的是「旧快照 / 未回填」路径。本轮**新增**用例覆盖「列存在」路径。

## 3. 为什么是 A 类（逐门核对）

| # | 门 | 本轮是否触及 | 依据 |
|---|---|---|---|
| ① | DROP TABLE/COLUMN | 否 | 无任何 DDL 变更（`git status` 实证见 §5.4） |
| ② | 改已有字段类型或既有业务语义 | 否 | 只**新增**返回字段与**新增**降级编码；既有字段（`snapshotId`/`qualityStatus`/`segments[].users`/`share`/`avgRecencyDays`）语义不变 |
| ③ | 改已发布 Flyway migration | 否 | `db/metric/V1-V10`、`db/meta/V1-V25` 字节未动（§5.4 证据） |
| ④ | 写/迁移正式 3306 数据 | 否 | 零连库（全部用例 Mockito 替身） |
| ⑤ | 切 ACTIVE | 否 | 不涉及发布链 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | 否 | `contract-specs/**` 未动；本轮只**加性**补 `docs/contracts/analysis-viewmodel-r7-4.md`（该文件 L4 明文给出「先改本文件再改代码」的加性程序） |
| ⑦ | 改 V3.0 总体架构 | 否 | 服务层消费既有列，无分层/组件变化 |
| ⑧ | 改正式项目范围 | 否 | 正是登记项 L217 要求的范围 |
| ⑨ | 删除已发布功能 | 否 | 无删除；旧路径仅作为**列缺失时的降级路径**保留 |
| ⑩ | 引入 V3.0 未规划大型基础组件 | 否 | 零新依赖 |
| ⑪ | 两种方案造成重大长期架构分叉 | 否 | 唯一方案：`r_days`/`m_amount`/`f_count`/`period_*` 为原值所有者，服务端不再自造原值 |

**结论：A 类（实现补齐 + 加性字段/编码）** ⇒ 登记 → 自主设计 → 实现 → 测试 → commit → 继续。

## 4. 本轮实施清单

1. `AdsRows`：新增 `asLongOrNull`（区分「列缺失/不可解析」与真值 0）与 `asTrimmedOrNull`（空白视为缺失）两个取值助手。
2. `RfmService`：
   - `RfmSegment` 追加 `orders`（Σ`f_count`，F 原值；列缺失时 null）；
   - `amount` 改为 Σ`m_amount`（M 原值，`scale=2` HALF_UP；列缺失时仍 null）；
   - `avgRecencyDays` **优先**逐行 `r_days`（原值所有者），仅当该行无 `r_days` 时才退回既有 `calc_date − last_buy_date` 回算；
   - `RfmProfile` 追加 `periodStart`/`periodEnd`（观察窗口，行间不一致或缺列 → null，**不猜**）；
   - 警告按实测生成：`RFM_AMOUNT_UNAVAILABLE` 仅在「无任何行带可读 `m_amount`」时挂；新增 `RFM_RAW_VALUES_UNAVAILABLE`（R/F 原值缺失，R 已回算）与 `RFM_PERIOD_UNAVAILABLE`（观察窗口不可用）。
3. `AnalysisViewModel`：新增两个编码常量（**唯一 owner**），保持既有编码语义不变。
4. `AnalysisService`：`UsersData`/`RfmData` 追加 `periodStart`/`periodEnd` 透传。
5. `docs/contracts/analysis-viewmodel-r7-4.md`：按 L4 程序先做**加性**契约补充（v1.1 说明 + §3.5/§3.6 字段 + §2 编码列表）。
6. 文档：`PROJECT_STATUS.md`（关闭 L217 登记项、加 S3-16 事实条目、计数口径）与 `docs/status-history/开发过程事实与决策记录.md`（F-49）。

**不在本轮范围（登记不实施）**：`web/src/utils/envelope.js` 的告警文案表（前端属阶段5；现状对未知编码回退原样展示，功能不受影响）；`AnalysisGoldenMySqlIT` 真库断言（D 类不入门禁）；其他快照的历史数据回填。

## 5. 反熵守卫与「无回归」证据

### 5.1 RED（先红后绿，两轮）

**RED-1（行为红，只用既有访问器 ⇒ 必是断言失败而非编译失败）**
新增 `RfmServiceTest.rawValuesAreConsumedWhenColumnsExist`（3 行原值齐备夹具：`m_amount`=100.50/50.00/0.00、
`f_count`=3/1/0、`r_days`=7/9/31、`calc_date`=20260901 且 `last_buy_date`=2026-09-01 ⇒ **旧口径回算恒为 0 天**，
可与原值 7/9 天区分），断言 `amount()==150.50`、`avgRecencyDays()==8.0`、`warnings()` 为空。

实测（`mvn -o -f analytics-server\pom.xml test -Dtest=RfmServiceTest`）：

```
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0  -- in ...RfmServiceTest
[ERROR] com.graduation.analytics.analysis.RfmServiceTest.rawValuesAreConsumedWhenColumnsExist
[ERROR]   RfmServiceTest.rawValuesAreConsumedWhenColumnsExist:163
java.lang.AssertionError:
Expecting actual not to be null
	at ...RfmServiceTest.rawValuesAreConsumedWhenColumnsExist(RfmServiceTest.java:163)
```

⇒ 断言点 `:163` 即 `amount()`（当前恒 `null`），**红在行为上**（另外 5 条既有用例仍绿，说明夹具本身可用）。
日志：`$env:TEMP\s316-red1.log`（副本 `.verify/s316/red1-mvn.log`）。

**RED-2（编译红，如实留痕）**
补齐新访问器（`orders()`/`periodStart()`/`periodEnd()`）、两个新编码常量、逐行回退用例与「窗口不一致」用例，
更新三条既有警告期望（含 `AnalysisServiceTest`）。实测：

```
[ERROR] .../RfmServiceTest.java:[61,31] 找不到符号
[ERROR] .../RfmServiceTest.java:[175,45] 找不到符号
```

⇒ Java 的「新增记录组件/常量」在编译期不存在，**TDD 红只能表现为编译失败**（该项目既有先例：S3-15 RED #0）。
日志：`$env:TEMP\s316-red2.log`。

### 5.2 GREEN

实现后**同一命令 + `AnalysisServiceTest`** 实测：

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ...AnalysisServiceTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in ...RfmServiceTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS   （mvnExit=0）
```

⇒ 新增 **4** 条（`RfmServiceTest` 6→9：原值齐备／窗口不一致／M 列缺失／R 列逐行缺失；
`AnalysisServiceTest` 9→10：原值与窗口透传），日志 `$env:TEMP\s316-green1.log`。

### 5.3 门禁（fresh 真跑，门禁版本 ＝ 待提交版本）

`run-tests.ps1 -Suite default -RunId s316_20260916_def2`（首次 `s316_20260916_def` 为改基线前的量数档）：

| 树 | 实测 | 基线 | 判定 |
|---|---|---|---|
| analytics-server | `914`（F=1 E=0 S=1，明细 `90+350+163+72+92+147`；改基线前实测亦为 `914` ⇒ DRIFT `909`，改基线后 MATCH） | 914 | MATCH |
| mall-simulator | 13（F=0） | 13 | MATCH |
| synthetic-data-generator | 106（F=0） | 106 | MATCH |
| 三棵树合计 | 1033 | 1033 | MATCH |

套件结果：**`[FAIL exit=7]`** —— 唯一红是**已登记的环境性红**
`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（期望 43 条历史 manifest／实测 0，本工作树无
`landing/manifests`），**未修、未复制 manifest、未用开关掩盖**；`analytics-server` 的 F=1 即此条，无新增红。
基线 `909 → 914`（＝本轮新增 5 条 Java 用例：`RfmServiceTest` +4、`AnalysisServiceTest` +1）。

**未跑 spark 档**：本轮**零 Spark 改动**（§5.4 证据里 `spark-jobs/**` 无改动）⇒ 该档不可能受影响。

### 5.4 不得越界表述

`git status --porcelain` 实证（受保护面**零改动**）：`contract-specs/`、`db/`、`warehouse/`、`spark-jobs/`、
`docs/guidance/`、`docs/design/` 全部**无输出**；本轮改动面＝6 个 Java 源 + `scripts/run-tests.ps1` +
3 个文档（`PROJECT_STATUS.md`、契约 v1.1、status-history F-49）+ 本登记目录。

- 本轮**不**声称：真实发布链上 `m_amount` 非零、真镜像列存在性、真实业务数据下 RFM 数值正确；
- 本轮**不**声称：「服务层消费原值」等于「页面显示正确」（前端属阶段5）；
- 本轮**不**声称：`RFM_AMOUNT_UNAVAILABLE` 从此不再出现（**列缺失的旧快照仍会出现**，这正是设计意图）。

## 6. 未测与边界

| # | 未测/边界 | 原因与证据域 |
|---|---|---|
| 1 | 真库 `m_amount` 非零、真镜像列存在性 | 隔离档 3307 无监听；`MetricPublisherMySqlIT`/`MetricAdsMySqlIT`/`AnalysisGoldenMySqlIT`（D 类，永久排除）本轮**未运行**；「原值可得」只由静态链 + Mockito 夹具证明 |
| 2 | 真实业务数据下 RFM 数值正确性 | 本轮只判服务层**忠实消费**已落库列，不判上游算法（S2-06/S3-01 的口径由 Spark 侧用例负责） |
| 3 | 前端文案 | `web/src/utils/envelope.js` 的 `WARNING_TEXT` 无两新码 ⇒ 回退 `String(code)`；属阶段5 |
| 4 | 契约 JSON 示例的端到端 | v1.1 只改**文档**；前端与后端「逐字段对齐」的实测属阶段5 |
| 5 | `r_days` 为 0 与缺列的区分 | 已由 `asLongOrNull`（null vs 0）覆盖单测，但**真库** `NOT NULL DEFAULT 0` 迁移下的「旧行回填 0」表现未测（迁移文本层面该列不可空 ⇒ 旧快照真库回填值可能就是 0，**这属已登记未测项**，不在本轮判据内） |

## 7. 检索证据（「未改即证据」）

- **门⑥核对（本轮开工前实测）**：`git grep -n -i -E "viewmodel|analysis-viewmodel" -- contract-specs/` 只命中
  **1** 行 —— `contract-specs/README.md:136`「**ADS / 分析视图模型契约**：归
  `docs/contracts/analysis-viewmodel-r7-4.md`，R7-4 工作项范围，**本目录不复制**」；该目录实际文件只有
  `README.md`、`VERSION`、`openapi/generator-api.v1.yaml`、`schemas/{canonical-event,generation-artifact-manifest,ingestion-manifest}.v1.schema.json`、
  `specs/{surrogate-key,warehouse-namespace}.v1|v2.json` ⇒ **本目录不承载分析 ViewModel 契约语义**，
  故本轮**加性**补 `docs/contracts/analysis-viewmodel-r7-4.md` **不触门⑥**（该文件 L4 明文给出
  「先改本文件再改代码」程序；`contract-specs/**` 本身**字节未动**，见 §5.4）。
- **消费侧空洞的精确形态（`git grep` on `HEAD`，即本轮改前状态）**：`m_amount|r_days|f_count|period_start|period_end`
  在 `analytics-server/` 的改前命中**全部不是读取点**，逐类如下 ——
  ① **白名单/规格**：`MetricAdsCatalog.java:50`；
  ② **迁移文本**：`db/metric/V4__ads_user_profile_rfm_raw.sql:13-17`（另 `V6` 的 `repeat_period_*` 属**另一张表**）；
  ③ **守卫测试**：`MetricAdsCatalogDdlConsistencyTest`、`AiSqlDriftTest:122`（断言 V4 的 ALTER 列被解析进来）；
  ④ **IT 夹具已备列**：`MetricPublisherMySqlIT:300-304` 的画像行**早已带这五列**（S3-01 补的，注释明写
  「夹具缺列会让本 IT 在不该失败的地方失败」）—— 但该 IT 属**未运行**清单；
  ⑤ **同名不同义**：`SemanticCatalog.java:78-79`／`MetricPublisher.java:67-69` 的 `repeat_period_start/end`
  属 `ads_operation_overview`，**与本轮五列无关**。
  ⇒ 结论：**main 侧零读取点**，`RfmService`/`AnalysisService` 从未引用这五列（本轮首次消费）。
- **记录组件调用面（改动面可控的依据）**：`new RfmSegment`/`new RfmProfile` **仅** `RfmService.java`；
  `new UsersData`/`new RfmData` **仅** `AnalysisService.java` —— 加性字段无编译波及（默认档 reactor
  6 模块 + `platform-app` 全量编译通过即为实测依据）。
- **受保护面零改动**：见 §5.4 的 `git status --porcelain` 输出（`contract-specs/`、`db/`、`warehouse/`、
  `spark-jobs/`、`docs/guidance/`、`docs/design/` 均无输出）。

## 8. 遗留 / 后续（登记，不擅自实施）

1. **前端告警文案表**（`web/src/utils/envelope.js`）补两新码中文文案 ⇒ 阶段5。
2. **`PROJECT_STATUS` 阶段4 其余登记项仍开放**：`ads_sale_trend_m.net_sale_amount` 消费方（L229）、
   「支付复购率」变体（L235, R-2）、复购率观察期缺省（L236, R-3）、`repeat_rate`/`repeat_period_*` 消费方（L238）、
   `cart_rate` 进概览 API（L245）。
3. **指导书 §7 阶段4 L156 的 `source` 字段**（信封/契约里**仍无** `source`）⇒ 阶段4 下一个候选开发项
   （需先定范围：`metric_snapshot.source`/`source_id` 的暴露形态）。
4. **L157 归档读取授权**、**L158 分页/限流/超时/异步任务反馈** ⇒ 阶段4 尚未评估的验收点。
5. 旧快照（真库）五列的**回填策略**：本轮的降级路径只为「列缺失」兜底，**不**做历史数据回填。

