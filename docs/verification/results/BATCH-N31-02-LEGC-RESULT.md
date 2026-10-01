# BATCH-N31-02 LEG C — 分类与城市等级销售供数

- 批次：N31-02；裁决：D-058（总控批准按推荐契约整体实现）。
- 执行日期：2026-09-29。
- 技术结论：**PASS_WITH_LIMITATION**。获批的分类/城市等级数据链已从 Spark ADS 贯通至 MySQL 镜像、销售 API、Vue 销售页和结构化 AI 证据来源；真实小链对账通过。由于本次使用的 99 行既有有效夹具不含坏行，且夹具没有分类名称字典，未覆盖计划里的坏样本验收和可读分类名称；未知维度主要由 Spark 定向回归覆盖。此结论不是远程/多节点验收，也不是 N31-02 的总控签收。
- 计划：`docs/verification/batches/BATCH-N31-02-CONTINUOUS-CHAIN-PLAN.md`；当前状态：`docs/PROJECT_STATUS.md`、`docs/verification/CURRENT_BATCH.md`。
- 持久化证据：`v3-archive/n3102/legc-20260929-7c11/`；运行原始根：`target/v25-it/n3102e_20260929_173000_7c11/`（ignored）。证据清单及 SHA-256 见归档目录 `MANIFEST-SHA256SUMS.txt`。

## 1. 获批实现范围

按 D-058：分类只按叶子分类聚合；`category_id=-1` 或缺失分类维度归入唯一 unknown；地区按源字段 `city_level` 展示为“城市等级”，不冒称省市行政区或地图；不跨日求和日级 distinct 买家/订单数；金额占比按所选窗口的汇总金额重新计算；Hive ADS 与 MySQL 服务镜像走新增的加性迁移，不修改既有 Flyway V1–V33；查询固定 `snapshotId`。

实现包括：Hive `dw_ads.ads_category_sale` / `dw_ads.ads_region_sale`；MetricAdsCatalog、导出及发布映射；MySQL `ads_category_sale_m` / `ads_region_sale_m`（metric schema 新增 V13）；服务端分类/城市等级窗口聚合；Vue 销售页两类图表；EvidenceBuilder 证据引用指向对应 ADS 源表与快照窗口。没有扩展 Text2SQL 白名单，也没有接入真实 LLM。

## 2. 本次发现并修复的问题

真实链路的地区维度最初全部落到 unknown。根因不是分类/地区 ADS SQL，而是 `DimensionBuildJob` 仅在当日 `userInput > 0` 时构建 `dim_user`。夹具用户注册于 2026-09-17，分析业务日为 2026-09-18；当日没有新注册事件，条件分支跳过用户 as-of 快照，导致 DWD 用户关联拿不到既有用户的 `city_level`。

修复后，用户维度像商品维度一样始终按业务日回看 ODS 历史并生成完整 as-of 快照。新增回归先能复现 `user=0->0`、`city_level=unknown`，修复后断言 `user=0->1` 且 `city_level=tier2`。既有同日 ODS 输入数仍准确记录为 0，避免将历史快照行数误报成当日新增。

## 3. 真实小链与对账结果

- 新隔离 RunId：`n3102e_20260929_173000_7c11`；运行在 WSL 单节点 HDFS/Flume/Spark，平台 8091，MySQL 仅使用本批 run-scoped 3307 schema。
- 受控输入：99 行、34,192 字节；Flume→HDFS 字节/行数匹配；摄取 accepted=99、quarantine=0；manifest `batchId=1` READY。
- 流水线 `pipelineRunId=1` SUCCESS，产生 `snapshotId=S20260918_1`；血缘 `sourceId=1 → batchId=1 → runId=1 → snapshotId=S20260918_1`。ADS 导出 10 张表、33 行；分类和城市等级表各 3 行；核心质量规则 4/4 通过。
- 唯一 ACTIVE 快照为 `S20260918_1`；overview 返回 14 项指标。销售 API 固定同一 snapshot，业务日筛选 2026-09-18 至 2026-09-18。

| 分类 ID | 数量 | 销售额 | 净销售额 | 窗口金额占比 |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 7 | 670.00 | 670.00 | 0.6634 |
| 200 | 4 | 290.00 | 210.00 | 0.2871 |
| 300 | 1 | 50.00 | 50.00 | 0.0495 |

| 城市等级 | 销售额 | 净销售额 | 窗口金额占比 |
| --- | ---: | ---: | ---: |
| tier1 | 610.00 | 530.00 | 0.6040 |
| tier2 | 330.00 | 330.00 | 0.3267 |
| tier3 | 70.00 | 70.00 | 0.0693 |

独立事件级 oracle、ADS 导出、MySQL 只读回查和 API 的分类/城市等级金额均一致；金额合计 GMV=1010.00、净销售额=930.00、已支付订单数=12，与销售汇总一致。比例由 API 按本次窗口汇总后重算，比例并非 MySQL 镜像表中持久化列。浏览器断言两类标题、城市等级说明、三张图表 canvas、API snapshot 一致及零页面异常。页面证据只截取视口，图表本身以 DOM/API 与对账数据断言为主，不把截图表述成完整可视化证明。

## 4. 测试

- Spark `ProductDimensionAsOfSpec`：3/3；新增“前一业务日注册、当日无新用户事件仍生成完整快照”回归。
- Spark `CategoryRegionAdsSpec`：1/1；真实 Spark SQL 执行两种 ADS，覆盖 unpaid 排除、缺失分类归 unknown、缺失 city level 归 unknown、质量 reconcile 以及错误金额被拒绝。
- Java `AnalysisServiceTest`：41/41；包含窗口加总、占比重算及非可加 distinct 边界。
- Java `EvidenceBuilderTest` + `ExplanationEvidenceTest`：16/16；证据引用指向正确分类/地区 ADS 表、快照和时间窗口。
- Java `MetricPublisherBuildFailureCompensationTest`：3/3；补偿路径保持失败快照不激活（不替代新 V13 schema 上的独立故障注入）。
- 前端定向测试 `node --test tests/chartOptions.test.js tests/aiEvidenceWindow.test.js`：29/29。
- 真实小链 driver：PASS；platform jar SHA-256 `89d9272b508d7e9d8f1917654a5476a90e4da4093e475f68afe22d33c129c61f`，Spark job jar SHA-256 `68589344d8fb2f4b56cdc93842f601a4005576c0edacfeadcb88f8aaba10d11b`。

本轮采取受影响测试 + 一次新隔离 runId 真实端到端链路；未运行全量 `all-tests`，没有重复重放 A/B/D 已验收腿。Scala 本地测试通过不等于远程多节点/Hive 生产集群通过。

## 5. 限制与后续改进

1. **计划样本偏差**：原计划为 30–50 行有效样本并另含 5–10 行坏样本；本次为复用连续链而使用 99 行全有效夹具（0 quarantine）。真实失败/坏数据路径没有在本腿重测，因此整体标记 `PASS_WITH_LIMITATION`，不宣称完整满足样本构成。
2. **分类名称缺失**：源夹具只带分类 ID，不带分类名称维度；API 对 ID 100/200/300 返回 `category_name=UNKNOWN`。系统没有伪造名称。后续应补充独立分类字典/商城适配映射并用真实名称数据复验。
3. **未知维度覆盖边界**：本次真实业务日的浏览器数据不含 unknown 分类/城市等级；其归并语义由 Spark 定向测试覆盖，而非声称本次生产形态浏览器端到端覆盖。
4. **页面证据范围**：截图为单视口，分类图表未完整落入截图；标题、canvas 数量、API 响应和数值 oracle 均已断言。若要作为答辩演示证据，应补一张滚动至分类图表的截图。
5. **故障保旧覆盖**：N31-02 腿②已验证发布故障时旧 ACTIVE 可读，发布补偿单测 3/3；本轮没有在新增 V13 分类/地区镜像表上再执行一次端到端故障注入，因此此处引用既有通用发布证据，不标称本腿新故障链通过。
6. **WAIT_LANDING 证据字段**：运行态摄取结果含 HDFS `acceptedDir`/manifest URI；但 pipeline stage 的 `WAIT_LANDING` 摘要只记录相对 `acceptedUri`，本次 stage 证据本身未带完整 `hdfs://` scheme。A3 的 HDFS 写入与 A4 的摄取 URI 和后续 Spark 处理均有独立证据；建议后续增强 stage evidence 的 URI 可观测性。
7. **环境边界**：仅证明 WSL 单节点环境；不证明远程集群、共享外部 HMS 生产部署或真实 LLM。真实 LLM 仍受 D-039 BLOCKED 条件约束。

## 6. 安全、运行态与变更边界

- 本批应用访问与 SQL 仅针对 run-scoped MySQL 3307 schema；未连接或执行 SQL 于 3306。只读端口检查观察到本机有 3306 LISTEN PID 10004；本批未停止或接触该进程。平台日志对 `3306` 零命中。
- 页面及 API 运行结束后平台 8091 已释放；不停止常驻 HDFS/3307。无数据库 dump，因为仅创建本批隔离 schema 且未改正式 schema/数据。
- 本切片直接源码修改仅 `DimensionBuildJob.scala` 与 `ProductDimensionAsOfSpec.scala`（其余 dirty pool 继承自此前批次，未重置/覆盖）；构建产物/临时驱动位于 ignored `target/`。没有 stage、commit 或 push。
- 冻结 V3.0 指导书与设计正文未修改；V3.1 仍为待审草稿；本结果不构成总控对 N31-02 整批或产品最终验收的签收。

## 7. 追加勘误：腿③工作树变更归属（2026-09-29）

N31-01 状态审计发现 §6 的“本切片直接源码修改仅 DimensionBuildJob.scala 与 ProductDimensionAsOfSpec.scala”低估了腿③实际实现变更。以本批 HEAD 300c4c9b7bfc25b8a058c62ad89f349bb15121b8 对照当前工作树，D-058 批准后至本结果写成前，另有 52 个新增/修改的产品源代码、测试、迁移、数仓 DDL 与前端路径，均属于分类/城市等级供数实现与验证；上述两个文件是用户维度 as-of 缺陷修复的重点路径，并非全部腿③改动。逐路径清单见 docs/verification/results/BATCH-N31-01-STATE-CALIBRATION-AUDIT-20260929.md §3.2。

此勘误只修正变更归属和文件数量，不改变 §1–§5 所述测试结果、真实链路结果或 PASS_WITH_LIMITATION 结论。原始结果文件已在 .bak-20260929-n3101-audit 备份。
