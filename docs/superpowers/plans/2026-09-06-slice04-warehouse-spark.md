# Slice04: Hive 四层数仓 + Scala Spark 作业（第一阶段首批） — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付阶段 5 的两块核心资产：①`warehouse/ddl/`——ODS/DWD/DWS/ADS 四层 Hive 建表 SQL（§6，Parquet+Snappy、dt 分区、审计字段）；②`spark-jobs/`——独立 Scala 2.12 + Spark 3.5 Maven 工程（§4.4 约束 1：Spark 主任务用 Scala），包含 §24.5 作业骨架与首批 4 个作业（EventOdsLoadJob/BehaviorDwdJob/UserProductDwsJob/FunnelAdsJob），算法与 SQL 抽成可本机单元测试的纯对象。

**Architecture:** spark-jobs 与 Web 后端完全解耦（§19.2）。作业实现 `WarehouseJob` trait，统一入口参数 `--runtimeProfileId/--businessDate/--inputVersion/--outputSnapshotId/--attemptNo`（§24.5），禁止写死节点地址；输出先写临时分区，发布流程切换。业务算法（分位数、RFM、漏斗、热度、异常）与 SQL 模板为无 Spark 依赖的纯 Scala 对象 → 本机 mvn 编译 + scalatest 全绿。Spark 依赖 scope=provided，作业 JAR 由 `SshSparkSubmitter`（阶段 6）提交。

**Tech Stack:** Scala 2.12.19、Spark 3.5.1 (provided)、Hadoop client 3.3.4 (provided)、scala-maven-plugin、ScalaTest 3.2.19、Maven。

**外部约束（V2.2 文稿）:** §4.4.1 Span 主任务 Scala；§21.2 金额 DECIMAL(18,2)/BigDecimal、event_time 归属、event_id 去重、分母为 0 返回 null；§21.4 宽松用户漏斗 + 会话顺序漏斗；§21.5 Retention/复购；§21.6 RFM 分位数八类；§21.7 热度对数缩放；§21.9 z-score/IQR/贡献拆解；§24.5 作业入口参数；§24.6 推荐作业清单。

---

### Task 1: warehouse/ddl 建表 SQL

**Files:**
- Create: `warehouse/ddl/00-ods.sql`（ods_user_event、ods_product_event、ods_behavior_event、ods_trade_event：外部表、审计字段 source_system/source_file/ingest_batch_id/ingest_time、dt/hour 分区）
- Create: `warehouse/ddl/01-dwd.sql`（dwd_user_behavior_detail、dwd_order_detail、dwd_reject_record）
- Create: `warehouse/ddl/02-dims.sql`（dim_user/dim_product/dim_date/dim_region/dim_metric）
- Create: `warehouse/ddl/03-dws.sql`（dws_user_behavior_day、dws_behavior_funnel_day、dws_product_behavior_day、dws_product_sale_day、dws_trade_day、dws_user_trade_period、dws_region_sale_day）
- Create: `warehouse/ddl/04-ads.sql`（ads_operation_overview、ads_behavior_funnel、ads_active_trend、ads_hot_product、ads_product_conversion、ads_sale_trend、ads_category_sale、ads_region_sale、ads_user_profile、ads_data_quality）
- Create: `warehouse/README.md`（分层职责、血缘、字段口径提示、与 MySQL 指标库关系 §3.4）

### Task 2: spark-jobs 工程骨架

**Files:**
- Create: `spark-jobs/pom.xml`（scala-maven-plugin 4.8.1、spark-core/sql 3.5.1 + hadoop-client 3.3.4 provided、scalatest 3.2.19、scalatest-maven-plugin）
- Create: `src/main/scala/com/graduation/analytics/job/JobArgs.scala`（case class + 解析器：必填校验、businessDate 格式）
- Create: `src/main/scala/com/graduation/analytics/job/JobResult.scala`（inputRecords/outputRecords/rejectedRecords/snapshotId/attemptNo/code）
- Create: `src/main/scala/com/graduation/analytics/job/WarehouseJob.scala`（trait：code/validate/run）
- Create: `src/main/scala/com/graduation/analytics/job/SparkSessionFactory.scala`（配置：warehouse 目录、广播阈值、shuffle 分区、时区 Asia/Shanghai）
- Create: `src/main/scala/com/graduation/analytics/job/JobRunner.scala`（main：解析参数 → 注册作业表 → 执行 → 打印 JobResult JSON）

### Task 3: 纯算法对象（无 Spark 依赖，本机单测）

**Files:** `src/main/scala/com/graduation/analytics/algorithm/`
- `Cleaners.scala`（时间标准化、枚举映射、空值/金额校验谓词）
- `QuartileStats.scala`（分位点计算、反向分位打分——RFM R 维度）
- `RfmScorer.scala`（R/F/M → 1..5 分 → 八类标签，§21.6）
- `FunnelComputer.scala`（宽松用户漏斗：view/intent/order/pay 各阶段人数与转化率，分母 0 → None）
- `ProductHeat.scala`（heat = 1×ln(1+PV)+2×ln(1+fav)+3×ln(1+cart)+5×ln(1+pay)，§21.7）
- `AnomalyDetector.scala`（z-score 窗口检测、IQR 检测、维度贡献拆解，§21.9）
- Test: `algorithm/*Test.scala`（scalatest：分位数数值、RFM 标签边界、漏斗分母零、热度权重、z-score 阈值）

### Task 4: SQL 模板对象（返回 SQL 字符串，可单测关键子句）

**Files:** `src/main/scala/com/graduation/analytics/sql/`
- `OdsLoadSql.scala`（Landing JSON → ODS 主题表：event_type 路由、审计字段）
- `DwdSql.scala`（行为清洗：event_id 去重 row_number、时间标准化、枚举校验、reject 记录；订单明细：状态展开、金额校验）
- `DwsSql.scala`（用户日宽表、漏斗 DWS、商品行为日 + 热度、交易日、user_trade_period）
- `AdsSql.scala`（经营大盘、活跃趋势、热门排行 rank、商品转化、销售趋势、分类/地区、用户画像 RFM 表、质量大盘）
- Test: `sql/SqlTemplateTest.scala`（关键子句断言：event_id 去重、dt 分区过滤、broadcast hint、漏斗 RIGHT 口径）

### Task 5: 首批 4 个作业

**Files:** `src/main/scala/com/graduation/analytics/job/`
- `EventOdsLoadJob.scala`（Landing → ODS：schema 解析、隔离统计）
- `BehaviorDwdJob.scala`（ODS 行为 → DWD 明细 + reject 记录）
- `UserProductDwsJob.scala`（DWD → 用户日/商品日/漏斗 DWS）
- `FunnelAdsJob.scala`（DWS → ads_behavior_funnel）
- Test: 作业注册表/依赖列表断言（4 作业 code 唯一、依赖顺序正确）

### Task 6: 构建与验收

- [ ] `mvn verify`（后台）：编译 + scalatest 全绿 + 打包 job jar（provided 依赖不打入）
- [ ] 核对 jar 内容不包含 spark 依赖
- [ ] 更新 README（阶段 5 ✅ 指定"集群验证"子项说明），提交

**验收（本轮完成定义）：** warehouse DDL 覆盖四层全部表（含 §6 表清单）；spark-jobs 独立编译打包通过；算法/参数/SQL 单测全绿（本机无 Spark 也能验证逻辑）；首批 4 作业具备统一入口契约；与阶段 6（SSH spark-submit 提交器）的接口已定义（作业名 + 参数键）。