# 《项目完整实施指导书 V2.0》落地情况逐节核查（2026-09-11）

> **这份文件回答一个问题：指导书 2 的内容是否已经全部完成？**
>
> 结论：**没有全部完成**。按指导书 §2 四态逐条核对 §1–§31 后，共登记 **269 条可判定条目**：
> **132 条完成 / 90 条部分 / 42 条未做 / 5 条不适用**（统计口径与脚本见 §7.1）。
> 42 条"未做"可归并为 **25 组功能缺口**（§7.2）；影响论文第 5–8 章的四块最硬：
> ①**连接器插件体系整组缺失**（§10.1/§10.2，连带 §22.1 的 `/connectors` 接口）；
> ②**决策与 AI：五类决策模板整节缺失 + 真实 LLM 未接入 + 无 EFFECTIVE 正样本**（§20.5、§19.6/§19.7、§20.4）；
> ③**质量与交易口径：规则未版本化、7 项质量校验未实现、交易层 3 公式未实现**（§16.1、§16.2、§16.4）；
> ④**半成品**：DIM 2/5、ADS 8/10、`repeat_rate`、RFM 金额列、`AnomalyDetector` 未接生产、无数据源页、
> 表格无分页/排序、无路由级角色拦截（清单见 §7.3）。
>
> 同时要把话说完：**主体链路是通的**（132 条完成覆盖边界、契约、采集、ODS-DWD-DWS-ADS、质量门、指标发布、
> 看板、AI 安全问数、决策状态机、RBAC、审计、真实验收），所以"没全做完"≠"不能用"。
>
> 本文的每一条判定都给出了**可复核的证据**（源码 `file:line`、只读 SQL 结果、真机验收记录），
> **不以 `docs/remediation-status.md` 的自述为准**。凡"文档说完成但代码/数据不支持"的，一律按代码判。

---

## 0. 核查方法与口径

| 项 | 说明 |
|---|---|
| 核查对象 | `docs/项目完整实施指导书 V2.0.md`（1596 行，§1–§31） |
| 核查日期 | 2026-09-11，分支 `remediation/r1-boundary` |
| 判定口径 | 严格按指导书 §2 四态：**未开始 / 已设计 / 组件完成 / 链路完成**；下表把"未开始+已设计"合并显示为 **未做**，"组件完成"显示为 **部分**，"链路完成"显示为 **完成** |
| 证据来源 | ①源码/DDL/SQL 实读（`file:line`）；②真库只读 SQL（`analytics_meta` / `analytics_metric` / `mall_simulator`）；③真机验收记录 `docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/`（黄金链 run 30、快照 `S20260901_30`、R8 53/53 PASS、DOM 22/22+17/17） |
| 不作证据 | 指导书自述、`remediation-status.md` 勾选、README 章节名、类名/接口名/页面名本身 |
| 已核出的**登记错误**（本轮订正） | `metric_snapshot.version` 并非"恒 1"：真库 6 个快照 version 依次 1→2→3→4→5→6（`MetricPublishRepository.java:38-41,60` 取 `MAX(version)+1`）；`remediation-status.md` 原登记已按此订正 |

**一句话口径**：一条要求只有在"真实入口 → 真实出口"跑通、且失败与恢复也验证过时才算完成；
"有类、有表、有接口、有页面、有单测"最多只能算**组件完成**。

---

## 1. §1–§2：不产生代码条目，但决定了怎么读这份核查

| 节号 | 要求 | 判定 | 说明 |
|---|---|---|---|
| §1 | 收敛为两个边界清晰的程序（平台主体 / 商城工具） | 完成 | 边界裁决见 §3.3、§3.4、§7；商城侧平台代码已删（`mall-simulator/src/main/java/com/graduation/mall/` 仅剩 auth/common/config/controller/domain/generator/outbox），平台侧无商城业务引用 |
| §2 | 进度只能用四态，且"有类/有接口/有页面"不得写成完成 | 完成（本文件即按此执行） | 本文件所有"部分"判定均因该条 |
| §2.1 | （表格）当前项目实况 = **整改前基线** | 不适用（历史快照） | §2.1 记的是 2026-09-09 基线：R7「未完成」、R9「未开始」。**它不是当前状态**，不得当作现状引用。R7/R9 现均已链路完成（见 §5.1、§23–§26） |
| §2.2 | （列表）本次已核验的构建事实 | 不适用（历史快照） | 当时的 41 个 Spark 测试、大包告警等已被本轮数据取代（spark-jobs 现 46 succeeded / 0 failed；`BaseChart` 已拆分为按需加载的图表 chunk） |

---

## 2. §3–§11：工程边界、商城、契约、采集与 Landing

### 2.1 §3 边界与目录

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §3.1 | 两程序互不依赖、独立库/端口 | 部分 | `mall-simulator/src/main/resources/application.yml:2,8`；`analytics-server/platform-app/src/main/resources/application.yml`（8091）；`warehouse/migrations/init-three-dbs.sql:5-26` | 商城默认连 `mall_simulator` + `root`，未使用 `init-three-dbs.sql` 建的 `mall_business` / `mall_app` → "不同数据库账号"在默认配置下不成立 |
| §3.2 | 目标物理目录结构 | 部分（指导书自述属"可延后"） | `analytics-server/pom.xml:20-35`（6 模块同名拆分）；`Test-Path connectors` → False | 顶层目录名与指导书不同、无 `connectors/`（Flume 模板在 `ingestion/flume/`）。spec:113 声明"是否马上移动目录不是本阶段验收重点" |
| §3.3 | 商城清理平台代码 | 完成 | `mall-simulator/src/main/java/com/graduation/mall/` 仅 auth/common/config/controller/domain/generator/outbox | — |
| §3.4 | 前端分离 5 条 | 部分 | `web/src/router.js:5-15`（8091，9 页）；`mall-frontend/`（8090）；两侧 boundary 测试 | ③**无"数据源"页**：`web/src/views/` 仅 AiAssistant/Behavior/Decisions/Login/Ops/Overview/Pipeline/Products/Rfm/Sales，`Pipeline.vue:11` 用手填数字 ID（`v-model.number="runtimeProfileId"`，默认 1）配置数据源；④无"打开模拟商城"外链 |
| §3.5 | 唯一集成契约（8 字段信封） | 完成 | `platform-common/.../contracts/EventEnvelope.java`；`EventContract.java:16-33,53-68`；`EventContractValidator.java:56-70` | — |

**本轮新查出的边界泄漏（报告 A 未定位准确，此处按实读订正）**：
`analytics-server/connection-ingestion/.../ingestion/IngestionService.java:221` 的采集状态总览用
`environment.getProperty("mall.landing.path", "./landing")` 解析 Landing 根目录——
**平台生产代码读取了商城命名空间的配置键**（正常路径 `parseLandingRoot(profile.landingUri)` 在 `:209-217`）。
同一份 `status()` 输出因此可能与被激活档案的 `landingUri` 不一致。属 §3 边界 + §9.3⑤"状态接口未完全使用激活环境"的同一处缺陷。

另有**死资源残留**：`analytics-server/platform-app/src/main/resources/db/business/V1__init_mall.sql`（9637 字节，含 `mall_order` 建表）
仍在平台工程内；Flyway 只加载 `classpath:db/meta`（`application.yml:35` 注明"迁移由 MetaFlywayInitializer 显式执行"），无任何 Java/Flyway 引用。
当前不生效，但一旦被误配即破坏边界（§3.3 的"清理"应含此文件）。

### 2.2 §4–§7 技术栈、流水线、标识符、契约

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §4 | 技术栈与测试/可替换组件 | 部分 | `analytics-server/pom.xml:20-35`；`spark-jobs/pom.xml`（Maven + Scala 2.12.19 + Spark 3.5.1）；`web/package.json` | **Vitest 未落地**：前端测试实为 `node --test`（74/74），全仓无 vitest 依赖 → 论文技术栈若写 Vitest 即不实。（spark-jobs 用 Maven 而非 sbt 不违规，§4 未指定构建工具） |
| §5.1 | 一次日批流水线 12 步 | 完成 | `PipelineService.java:147,163`（异步 taskId）、`:459-470`（ADS 写 `{table}__staging`）、`:472-508`（质量门→发布）；`MySqlMetricStore.java:177-200`（事务内归档+激活）；`MetricAdsReader.java:41-59`（看板只读 ACTIVE） | 12 步逐项有落点 |
| §5.2 | 幂等/重试/恢复/attempt 保留 | 完成 | `PipelineService.java:58-60,119-170,173,199-280`；`V2__platform_pipeline_quality.sql:17`（uk_idempotency）；`PipelineRecoveryService.java:77-129`（重启对账）；真机证据 `docs/acceptance/.../16-reliability-matrix.tsv`（C1–C5-6 全 PASS，run 22 attempt 1 停于 BUILD_DWS、attempt 2 只重跑 BUILD_ADS/QUALITY_CHECK/PUBLISH_METRIC） | — |
| §6.1 | 7 个标识符 | 完成 | `EventEnvelope`（eventId/traceId）、`IngestionService.java:48`（batchNo）、`SparkStageExecutor.java:160`（externalJobId）、`metric_snapshot.snapshot_id`、`DecisionService.java:132`（decisionNo） | — |
| §6.2 | 时间规范 5 条 | 部分 | `TradeDwdJob.scala:12,23`（迟到退款动态覆盖历史分区）；`JobArgs.scala:10`（`yyyyMMdd`）；`AdsRows.java:20-27`（边界层 `yyyyMMdd`↔ISO）；`AiScope.java:44` | 无 `[start,end)` 左闭右开实现可查（唯一相关者 DataX 未实现）；生成器小时环 `SimulationEngine.java:289` 用 `!cur.isAfter(endTime)` 含端点 → 论文泛称"全部窗口左闭右开"不严谨 |
| §6.3 | 金额字符串 / `DECIMAL(18,2)` / BigDecimal | 完成 | `EventContract.java:19`（十进制字符串正则）；`warehouse/ddl/*.sql` 共 28 处 `DECIMAL(18,2)`；全仓 `double` 仅 `LlmProvider.java:8`（temperature） | 无 double 参与金额/比例计算 |
| §6.4 | 状态集中定义/状态机/可审计 | 部分 | `PipelineRun.java:65-68`、`SparkJobRun.java:72-78`、`IngestionBatch`；`OrderStateMachine.java`；`DecisionStateMachine.java` | Spark 侧另存订单状态白名单：`Cleaners.scala:12` + `OrderTradeCompiler.scala:125-129` 内联字面量 → 跨语言两份，新增状态需双改 |
| §7 | 模块总表与依赖方向 | 完成 | 各 `pom.xml`：warehouse-pipeline→connection-ingestion+platform-common；metric-analysis→platform-common；ai-decision→metric-analysis+platform-common；`@RestController` 仅出现在 platform-app | 无反向依赖；`metric-analysis` 不读 Landing；无模块依赖 mall-simulator（唯一例外是上面的 `mall.landing.path` 属性名） |

### 2.3 §8 模拟商城与生成器（外部工具）

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §8.1 | 10 张业务表与关键字段 | 部分 | `mall-simulator/.../db/migration/V1__init_mall.sql:7-129` | `category` 缺 `status`（`:18-24`）；`cart_item` 缺 `selected`（`:50-58`）；`mall_order` 缺 `pay_amount`（`:61-74`）；`event_outbox` 用 `published_at` 代 status（`:125`） |
| §8.2 | 商品丰富度 5 条 | 部分 | `V1__init_mall.sql:136-142`（8 一级 + 16 二级分类）、`:144-176`（**32** 商品）、`:178-179`（库存全 100）；`DistributionKit.java:53`（截断对数正态） | 商品 32 个（指导书建议 100~300）；库存无差异；无季节/促销/爆款/长尾标签字段 |
| §8.3 | GeneratorConfig 8 字段与校验 | 部分 | `GeneratorConfig.java:9-52`（字段齐 + 可复现键） | 缺上界校验：`userCount≤100000`、`eventsPerSecond 1~20`、`productCount≤在售商品数` 均未实现（只校验 >0） |
| §8.4 | 生成算法 9 点 | 完成 | `SimulationEngine.java:50`（15/35/50 活跃分层）、`:285-300`（24h 权重归一化抽样）、`:320-326`（行为链）；`DistributionKit.java:41-53`（Zipf + 截断对数正态）；`DirtyDataInjector.java:56-63,80-89`（7 类脏数据）；`OrderStateMachine`；`ScenarioRegistry`+`GenerationFactors`；单一 seed RNG | 9 点均有实现 |
| §8.5 | 外部输出 + 每次运行摘要 | 部分 | `OutboxPublisher.java:52-75`（滚动 JSONL）；`application.yml:32`（`{landing}/events/{yyyyMMddHH}.jsonl`）；`GenerationResult.java:10-29` | 摘要无 `generationRunId`、无产出文件清单 → 单次运行不可唯一定位 |
| §8.6 | 独立验收 5 条 | 部分 | 8090/8091 分离；`GeneratorDeterminismTest`、`ScenarioEffectTest`、`GoldenDatasetTest`（mall 侧 54/54 GREEN） | 账号未独立（商城默认 root）；平台工程残留 `db/business/V1__init_mall.sql` 死资源；平台 Java/Scala 源码确无 `mall_order`/`mock_mall` 引用 |

### 2.4 §9 RuntimeProfile

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §9.1 | runtime_profile 字段与三种类型 | 完成 | `RuntimeProfile.java:74-81`；`V7__platform_runtime_profile.sql`（18 列：landing/hdfs/hive/前缀/sparkMaster/deployMode/yarnQueue/ssh*/submitPath/jarUri/metricStore*/credentialRef/timezone/version） | — |
| §9.2 | 5 个服务接口 | 部分 | `RuntimeProfileServiceImpl.java:51`(create)、`:103`(test)、`:216`(activate)、`:253`(disable)、`:262`(getActive) | ①连通性分项**无耗时字段**（`CheckDetail` 只有 name/passed/detail，指导书要求"和耗时"）；②`disable` **无运行中任务校验** → 可在流水线执行中停用激活环境，与"有运行中任务时禁止停用"直接冲突 |
| §9.3 | 实现提示 5 条 | 部分 | ②`CredentialService.java` 存在；④`JobSubmitterFactory.java:37-61` 按档案选提交器 | ①`:46` 仍留 Windows 常量 `SPARK_SUBMIT_DEFAULT = "D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd"`（跨平台/集群部署会落到 `D:\`）；③`:157-161` LOCAL 把 Hive 判为 `SKIPPED(不适用)` 而非临时库读写删验证 → **激活时并未验证 Hive 可用**；⑤状态接口未用激活档案（实测位置为 `IngestionService.java:221`，见 §2.1 末） |

### 2.5 §10 采集与连接器

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §10.1 | `SourceConnector` 抽象 + 本地文件/Flume 落地 | **未做** | 全仓 grep `SourceConnector|connectorCode|FlumeLandingConnector|LocalFileConnector` → 0 命中（仅指导书 `:412-423`） | 6 个方法无实现；`LocalFileIngestor` 是具体实现而非该接口实现；无 DataX/Kafka 占位状态 → "首期本地文件 + Flume 插件"的**插件形态不成立** |
| §10.2 | `source_connector` 表/配置模型 | **未做** | `connector_id` 全仓仅指导书 `:429,445,448` 命中；Flyway 迁移清单无 connector 表 | 无表/实体/JSON Schema 校验/`config_json`/`credential_ref`/status/version；采集参数仍散落在 `runtime_profile` 与配置项 |
| §10.3 | 采集四表补齐列 | 部分 | `V1__platform_ingestion.sql:8-55`；`V8__platform_ingestion_r3.sql:16-19`（已补 `file_identity`） | 缺 `checksum`（`ingestion_batch_file`）、`connector_id`（`file_checkpoint`）、`reason_code`（`quarantine_record.reason` 未标准化） |
| §10.4 | 本地文件增量算法 7 点 | 完成（算法） | `LocalFileIngestor.java:33-41,70`（身份/截断→新版本）、`:87`（FileChannel 字节偏移）、`:128-145`（accepted/quarantine + 记录）、`:166-168`（残行不消费）、`:173-175`（落地后才推进 checkpoint） | 7 点逐项可对上；但**无任何自动化测试**（见 §10.7） |
| §10.5 | Flume 首期方案 | 部分 | `ingestion/flume/flume-taildir.conf:16-56`（TAILDIR + File Channel + HDFS sink + position 文件） | 指导书要求的**平台侧管理零实现**：配置模板管理/目标 URI/agent 状态/最近心跳/批次发现全无；无集群实录（验收清单自述待环境） |
| §10.6 | DataX 后续方案 | **不适用（首期范围外）** | 全仓 `DataX` 仅文档命中（`docs/compatibility-matrix.md:54` 等） | 属后续方案；但注意**不能声称"接口已预留"**——§10.1 的接口与配置模型同样不存在 |
| §10.7 | 采集验收（正常/重复/坏行/半行/追加/轮转 + 对账） | **未做（无自动化测试）** | `connection-ingestion/src/test` 实有 3 个类，全部是鉴权相关：`AuthInterceptorTest`、`AuthServiceRoleWhitelistTest`、`RolePermissionsTest`；全仓测试类名含 `Ingest/Checkpoint/Landing/Connector` 者 **0 个** | `LocalFileIngestorTest` 已删（`docs/thesis-materials/证据映射表.md:105` 有登记）。断点、半行、追加、轮转、对账、checkpoint 追溯**全部无回归保护**；论文若写"采集已验收"缺可复核证据（只能引真实小样本跑批记录） |

### 2.6 §11 Landing 适配

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §11 | `LandingObject` 统一抽象 / 目录布局 / 本地与 HDFS 一致 / 不可变原始数据 | 部分 | `LandingStorage.java`（`FileStat`=size,lastModified,directory）；`LocalLandingStorage.java`（manifest 覆盖写）；`HdfsLandingStorage.java:97-107`（`fs.create(...,true)`）；`IngestionService.java:90-92`（`accepted/{batchId}`、`quarantine/{batchId}`） | ①无 `LandingObject`（URI/size/checksum/recordCount/createdAt/batchNo）；②目录布局与指导书 `:492-493` 不符；③无"临时文件 + rename"原子落地；④manifest 覆盖写、无 checksum 去重；⑤无保留期与清理审计 → "不可变原始数据"无法保证，重放/追溯口径偏弱 |

---

## 3. §12–§14：数仓分层、口径与作业契约

### 3.1 §12 分层与四层建模

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §12.1 | 分层边界：ODS/DIM/DWD/DWS 不可页面直读，ADS 仅管理查询 | 完成 | `LocalSchemaInitJob.scala:34-302`（建库）；`AnalysisService.java:46`；`MetricExportJob.scala:14-19` | Landing 由 `LandingStorage` 独立承载，不是 Hive 层 |
| §12.2 | ODS 4 张表、dt/hour 分区、只做解析/路由/强转/审计/写分区 | 完成 | `OdsLoadSql.scala:74-82`；`warehouse/ddl/00-ods.sql:1-106`；`OdsLoadSql.scala:26-30,27-72,190-198` | 隔离行只计数、不入 reject 表（§12.2 未要求落表） |
| §12.3 | `dim_user` 按 event_time 取最新合法事件 | 完成 | `DimSql.scala:13-26` | — |
| §12.3 | `dim_product` 只接受建档/更新，库存事件不能建商品 | 完成 | `DimSql.scala:36-61`（`:61` event_type 白名单） | — |
| §12.3 | `dim_date` 预生成覆盖分析日期范围 | **未做** | `warehouse/ddl/02-dims.sql:40-52` 仅 DDL；全仓生产代码零引用（实测 grep `dim_date` = 0 命中） | 日期维无数据 |
| §12.3 | `dim_region` 静态版本化配置 | **未做** | `02-dims.sql:54-61` 仅 DDL | 地区维无载体，连带 `ads_region_sale` 无来源 |
| §12.3 | `dim_metric` 从 MySQL `metric_definition` 同步 | **未做** | `02-dims.sql:63-74` 仅 DDL；无同步作业 | 语义层由 `MetricAdsCatalog.java:33-46` 代偿，Hive 字典为空 |
| §12.3 | 首版每日全量快照（SCD1 折中） | 完成 | `DimSql.scala:15,38` | 论文须写明该折中 |
| §12.4 | 行为明细去重/枚举白名单/维度补全 | 完成 | `DwdSql.scala:9-38`（`:27` 去重、`:37` 枚举白名单） | — |
| §12.4 | `dwd_order_detail` 订单×商品 + 金额/标志字段 | 完成 | `TradeDwdJob.scala:46-63,130-145` | — |
| §12.4 | `dwd_reject_record` 汇总清洗坏数据 | 部分 | `DwdSql.scala:41-45`；`warehouse/ddl/01-dwd.sql:61` | DDL 声明 5 类 reason，实际只写 `DUPLICATE_EVENT` 一种 |
| §12.4-1 | 同 order_id 先按 event_id 去重 | 完成 | `TradeDwdJob.scala:34` | — |
| §12.4-2 | 按 event_time、ingest_time、event_id 稳定排序 | 部分 | `OrderTradeCompiler.scala:93-94` | 只按 eventTime，缺 ingest_time/event_id 决胜键 → 同秒事件顺序不定 |
| §12.4-3 | 订单状态机合法性校验，非法跳转进 reject | **未做** | `OrderTradeCompiler.scala:92-147` 仅折叠取最新；合法性只在 `mall-simulator/.../MallBusinessService.java:297,374` | DWD 侧非法流转被静默吞掉，无 reject 行 → 数仓无法自证状态合法性 |
| §12.4-4 | items 展开并核对 Σitems.amount 与订单总额 | 部分 | `TradeDwdJob.scala:46-53`；`AdsSql.scala:212-227` | 只比 `order_amount` vs `paid_amount`，**未核对 Σitems.amount** |
| §12.4-5 | order_paid 去重定 paid_at/paid_amount/final_paid_flag | 完成 | `OrderTradeCompiler.scala:107-109,120` | — |
| §12.4-6 | 退款按 refund_id 取最新汇总且不得超过实付 | 部分 | `OrderTradeCompiler.scala:112-117` | 无"退款 ≤ 实付"校验 → `net_paid_amount` 可为负；仅上游商城拦 |
| §12.4-7 | `net_paid_amount = paid − refund` | 完成 | `OrderTradeCompiler.scala:119,142` | — |
| §12.4-8 | 完全退款 flag=1；部分退款单列 | 完成 | `OrderTradeCompiler.scala:121-122,124-127` | — |
| §12.5 | 7 张 DWS 表齐备 | 完成 | `DwsSql.scala`（7 个方法）；`UserProductDwsJob.scala:53` | — |
| §12.5 | `dws_behavior_funnel_day` 粒度 = 日×分类×渠道 | 部分 | `DwsSql.scala:41-45`（硬编码 `-1 AS category_id, 'all' AS channel`） | 退化为单行"日"粒度，分类/渠道维度缺失 |
| §12.5 | `dws_trade_day` 含 GMV/退款/净销售/客单价 | 完成 | `DwsSql.scala:110` | — |
| §12.5 | `dws_region_sale_day` 地区×日 | 完成 | `UserProductDwsJob.scala:53`；`warehouse/ddl/03-dws.sql` | 下游 ADS 无载体（见 §12.6） |
| §12.5 | count distinct 口径入字典；不混自然日/到达日 | 完成（含 4 项未落地说明） | `docs/contracts/metric-dictionary.md:1-40`；DWS 仅 dt 一维 | 字典 16 项中 4 项"仅字典"（`metric-lineage.md:43-44`） |
| §12.6 | ADS 10 张表齐备 | 部分 | 实产 8 张（`AdsSql.scala:13-15` + `FunnelAdsJob.scala:39-46`）；另 2 张仅 `warehouse/ddl/04-ads.sql:83-102` | **ADS 实产 8/10**：`ads_category_sale`、`ads_region_sale` 无产出作业 |
| §12.6 | `ads_operation_overview` 字段齐（含 snapshot_id） | 完成 | `AdsSql.scala:42-64,28-31` | — |
| §12.6 | `ads_behavior_funnel` 字段齐 | 完成 | `AdsSql.scala:76-91` | stage 仅全站 4 行 |
| §12.6 | `ads_active_trend` dau/behavior_count | 完成 | `AdsSql.scala:67-70` | — |
| §12.6 | `ads_hot_product` 字段齐（含 rank） | 完成 | `AdsSql.scala:94-108` | — |
| §12.6 | `ads_product_conversion` 字段齐 | 完成 | `AdsSql.scala:111-123` | — |
| §12.6 | `ads_sale_trend` 补 `net_sale_amount` | **未做** | `AdsSql.scala:126-132`；`04-ads.sql:72-80` | DWS 已有 `net_sale_amount`（`DwsSql.scala:110`）却未落 ADS |
| §12.6 | `ads_category_sale` 分类销售结构 | **未做** | `04-ads.sql:83-92`（仅 DDL） | 页面 `byCategory` 恒空 + `UNKNOWN_DIMENSION_TABLE` |
| §12.6 | `ads_region_sale` 补 `order_count` | **未做** | `04-ads.sql:95-102`（无该列，且无产出作业） | 既缺列也无数据 |
| §12.6 | `ads_user_profile_m` R/F/M/分群/活跃/偏好/生命周期/版本 | 完成 | `AdsSql.scala:143-202`（`:175` rule_version） | 金额列缺失见 §13.4 |
| §12.6 | `ads_data_quality_m` 六类质量字段 | 完成 | `AdsSql.scala:208-255` | `EVENT_ID_UNIQUE` 的 Java/Spark 计数分歧已在验收账本登记 |

### 3.2 §13 指标口径与分析算法

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §13.1 | PV 只计 view（不混全部行为） | 完成 | `DwsSql.scala:57-58`；黄金值 `tests/golden-dataset/expected/golden-20260901-expected.json` | 原"14 条全行为"口径已订正 |
| §13.1 | UV/DAU/支付订单数/GMV/净销售/客单价/退款率 | 完成 | `AdsSql.scala:42-64`（分母 0 → NULL）；真机 `S20260901_30`：gmv 2042.00 / net_sale 1493.00 / aov 408.40 / pv 7 / uv 3 / dau 3 / paid_order_cnt 5 | — |
| §13.1 | 退款率 = 任意退款订单/支付订单（全额另立） | 完成 | `AdsSql.scala:42-64`（refund_rate + full_refund_rate）；真机 0.6000 / 0.2000 | — |
| §13.1 | 复购率 `repeat_rate` 落地 | **未做** | 字典 `metric-dictionary.md:30`；`V2__platform_pipeline_quality.sql:74` 有定义行；`metric-lineage.md:44` 记"未落地"；实测 ACTIVE 快照 `metric_value` **只有 10 个指标码、无 `repeat_rate`** | 有 DWS 原料、有标准答案（0.3333），缺 ADS 列 → 页面/发布通路都不存在 |
| §13.1 | `definition_version` 单一权威、跨层一致 | 完成 | `MetricPublishValidator.java:128-140`；`V1__metric_store.sql:16`；`AnalysisServiceTest.java:195` | — |
| §13.2 | 宽松漏斗 4 阶段 + 3 转化率 + overall | 完成 | `DwsSql.scala:41-54`；`AdsSql.scala:76-91` | — |
| §13.2 | 后阶段 > 前阶段不静默截断，应给数据说明 | 部分 | `DwsSql.scala:47-54`；`AnalysisService.java:210-223` | 既无数据说明，也未建严格漏斗版本 |
| §13.3 | 热度 = 1/2/3/5 × ln(1+x) | 完成 | `AdsSql.scala:100`；`ProductHeat.scala:7`；`FunnelHeatAnomalySpec.scala:25-30` | 生产 SQL 复写公式，未调用 `ProductHeat`（双所有者） |
| §13.3 | 权重必须保存在规则版本中 | **未做** | `AdsSql.scala:100,102` 字面常量；`ads_hot_product` 无 `rule_version` 列 | 权重调整无版本可溯 |
| §13.3 | 排行 `row_number(order by heat desc, buy desc, product_id asc)` | 部分 | `AdsSql.scala:102`（仅 `ORDER BY heat DESC`） | 缺决胜键 → 并列热度排名不确定 |
| §13.4 | 观察期 30/90；R 越小分越高 | 完成 | `AdsSql.scala:181-182,149`；`FunnelAdsJob.scala:30-31` | — |
| §13.4 | M 取净消费或 GMV 并固定版本 | 部分 | M 列 `AdsSql.scala:184`；`rule_version='rfm-v1'` `AdsSql.scala:175` | 取毛额 GMV 而非推荐净额；页面金额列仍缺 |
| §13.4 | `ntile(5)` 带稳定排序；样本少标"仅演示" | 部分 | `AdsSql.scala:181-184`；grep `仅演示` 仅命中指导书 `:649` | NTILE 无稳定排序 → 分位边界次序不定；无小样本标注 |
| §13.4 | 按 R/F/M 相对中位数划八类 | 部分 | `AdsSql.scala:152-164`；`QuartileStatsRfmSpec.scala:28-36` | 用 `ntile≥4/≤2` 代替"相对中位数" |
| §13.4 | 页面可读 RFM 金额 | **未做** | `RfmServiceTest.java:59`；`docs/thesis-materials/screenshot-list.md:67` | `ads_user_profile_m` 无金额列 → `amount` 恒 null + `RFM_AMOUNT_UNAVAILABLE`（拒绝用 m 分求和冒充金额，口径正确但功能缺失） |
| §13.5 | 数据量 ≥14 历史点才计算 z-score | 部分 | `AnomalyDetector.scala`（仅 `size<2` → None，阈值 2.5） | 无 14 点下限 |
| §13.5 | 非正态/极端值优先 IQR | 完成（算法层） | `AnomalyDetector.iqrBounds`；`FunnelHeatAnomalySpec.scala:48-51` | 仅单测覆盖，未接生产 |
| §13.5 | 同时设业务绝对阈值 | 完成 | `ai-decision/.../ai/evidence/AnomalyRules.java` | — |
| §13.5 | 输出含 metricCode/当前值/基线窗口/均值中位数/偏差/规则版本/严重度/snapshotId | 部分 | `EvidencePackage.java:137-139` | 缺基线窗口、均值/中位数、规则版本、snapshotId；无异常结果表 |
| §13.5 | 检测算法接入生产链路 | **未做** | 仅 `FunnelHeatAnomalySpec.scala:39-52` 引用；生产走 `AnomalyRules` 绝对阈值 | z-score/IQR/维度贡献生产不可达 → "算法有、链路无" |

### 3.3 §14 作业契约与流水线阶段

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §14.1 | `JobRegistry` 为唯一映射 | 完成 | `JobRegistry.scala:13-40`；`JobRunner.scala:16-90` | `hasCycle` 硬编码 false（`:46`） |
| §14.1 | 分配码 `lsi/dmb/tds` + `BUILD_DIM` 阶段 | 部分 | `JobRegistry.scala:13-40`（实为 `sci/dim/tdw`）；`SparkStageExecutor.java:41-48`（无 BUILD_DIM） | 码名与阶段名偏离指导书；dim 并入 BUILD_DWD |
| §14.1 | BUILD_ADS 产出 10 张 ADS 临时快照 | 部分 | `SparkStageExecutor.java:46`；`AdsSql.scala:13-15`（8 张） | 缺 2 张（同 §12.6） |
| §14.1 | 注册作业逐一写明输入/输出/分区/参数/统计 | 部分 | 各作业 `OUTPUT_TABLES`（`BehaviorDwdJob.scala:42`、`EventOdsLoadJob.scala:85`、`TradeDwdJob.scala:150`、`UserProductDwsJob.scala:53`）；`PipelineService.java:406-467` | 只有代码常量 + `contracted` 一句话，**无作业级契约文档** |
| §14.1 | 注册作业均实际可被调度 | 部分 | `LocalJsonParquetJob.scala:14`（`ljp` 已注册）不在 `SparkStageExecutor.java:41-48`；`scripts/run-spark-chain.ps1:48-52` 只跑 5 个 | `ljp` 永不被调用；dim/tdw/dqc/pub/mxp 不在遗留脚本内（平台流水线走 8 阶段，不走该脚本） |
| §14.2 | 10 项必需参数 | 部分 | `JobArgs.scala:1-52` | 缺 pipelineRunId/stageCode/windowStart/windowEnd/landingUri/warehousePrefix 共 6 项 |
| §14.2 | 可选参数 sourceBatchId/rebuildPartitions/dryRun | 部分 | extra 透传；`JobCommandBuilder.java:87-102` | 无显式字段与语义定义 |
| §14.2 | 未知参数报错 | **未做** | `JobArgs.scala:1-52`（extra 直通） | 拼错参数静默进 extra，不报错 |
| §14.2 | 缺必填项报错 | 完成 | `JobArgs.scala:39-40` | — |
| §14.2 | 日期格式严格校验 | 完成 | `JobArgs.scala:19,39-40` | — |
| §14.2 | 日志屏蔽凭据 | 部分 | `MetricExportJob.scala:17-19`；spark-jobs 全量 grep `password/jdbc` = 0 | 无统一脱敏实现与测试 |
| §14.3 | 作业最后输出一行机器可解析 JSON | 完成 | `JobRunner.scala:52-90`；`JobResultParser.java:76` | — |
| §14.3 | status/jobCode/stageCode/externalJobId | 部分 | `JobRunner.scala:56,62`；`SparkStageExecutor.java:77-81,157-159`；`V7__platform_runtime_profile.sql:43-45` | JSON 内无 `stageCode`/`externalJobId`（由 Java 侧落库补齐） |
| §14.3 | input/output/rejectRecords 对账计数 | 部分 | `JobRunner.scala:57-59`；`FunnelAdsJob.scala:48-52` | 键名 `rejectedRecords` 与指导书 `rejectRecords` 不一致 |
| §14.3 | outputPartitions 真实写入分区列表 | 完成 | `PartitionEvidence.scala:29-92`；`JobRunner.scala:65-74` | — |
| §14.3 | outputSnapshotId | 部分 | `JobRunner.scala:60`（键名 `snapshotId`） | 命名偏离 |
| §14.3 | startedAt/finishedAt/logUri | 部分 | `V7__platform_runtime_profile.sql:52-54`；`SparkStageExecutor.java:77-81` | 作业 JSON 内无这三字段 |
| §14.3 | errorCode/errorMessage | 部分 | `V7__platform_runtime_profile.sql:55-56`；`JobResultParser.java:97` | 作业 JSON 只有 `message` |
| §14.4-1 | 只写 `{table}__staging/snapshot_id/dt` | 完成 | `FunnelAdsJob.scala:11-22,39-46`；`AdsSql.scala:28-31` | — |
| §14.4-2 | 记录临时路径/行数/统计摘要 | 完成 | `JobRunner.scala:65-74`；`MetricExportJob.scala:80-82` | 无 checksum（仅路径 + 行数） |
| §14.4-3 | 质量模块只检查临时结果 | 完成 | `AdsQualityJob.scala:33-138` | — |
| §14.4-4 | 通过后元数据交换/受控 rename 发布 | 部分（指导书允许的替代路径） | `AdsPublishJob.scala:26-133`（ADD PARTITION + SET LOCATION） | 非原子交换，走元数据指针；指导书 `:716` 对不支持原子 rename 的存储**明确允许**该路径 |
| §14.4-5 | 发布幂等：同 snapshotId 重试不产生第二份 | 完成 | `AdsPublishJob.scala:68-70` + `PUB_FORMAL_PARTITION_MATCH`(BLOCKING) | 靠规则校验而非原子性 |
| §14.4-6 | 失败保留旧正式分区；临时分区按保留期清理 | 完成 | `AdsQualityJob.scala:20-21`；`AdsPublishJob.scala:88-108` | 清理无显式保留期配置项 |

---

## 4. §19–§22：AI 解释与问数、决策闭环、权限与审计、接口规范

### 4.1 §19 AI 辅助分析

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §19.1 | 指标解释：证据包 → 面向普通员工的摘要，不重算数字 | 完成 | `EvidenceTemplates.java:11-56`；`ExplanationService.java:153-169`；真机 `18-r8-accept-report.json`（templateVersion=evidence_v1、facts=10） | — |
| §19.1 | 异常归因提示：候选原因 + 核查步骤，只称"可能相关" | 完成（规则条数有限） | `AnomalyRules.java:45,60-66,76-79`；`EvidenceTemplates.java:160-174`；真机 limitations=9 | 仅 4 条阈值规则，无"维度贡献 → 候选原因"映射 |
| §19.1 | 自然语言问数：受限 SELECT + 结果解释 | 完成 | `TextToSqlService.java:34-36,96-168`；真机 D1–D4 EXECUTED 且有行 | — |
| §19.1 | 决策草稿：证据 + 规则建议 → DRAFT 任务 | 部分 | `DecisionService.java:123` 强制 DRAFT；`DecisionController.java:57` | ①无"证据+规则建议→草稿"生成路径（`createDraft` 仅控制器调用）；②`source` 硬编码 `"ai"`：真库 `decision_task` 8 行**全部** `source=ai`（含人工验收建的"R8 验收：…"任务）；③前端 `Decisions.vue` 无建议入口（只能列表 + 提交 DRAFT） |
| §19.2 | EvidencePackage 11 字段齐备 | 完成 | `EvidencePackage.java:52-143`；真机 C2–C8 | — |
| §19.2 | dataQuality 含"迟到率" | **未做** | 全仓 grep `迟到/late_rate/lateRate` 无命中；`DataQuality` 仅 gateStatus/ruleTotal/rulePassed/failedRules/warnings（`EvidencePackage.java:143`） | 字段与计算均未落地 |
| §19.2 | 结论必须引用 evidenceRef，无证据不输出数字 | 完成 | `EvidencePackage.java:102-141`；`ExplanationService.java:192-229` 数值守卫 | — |
| §19.3 | 六段固定模板先行 | 完成 | `EvidenceTemplates.java:11,56`；真机 sections=6 | — |
| §19.3 | LLM 只改写摘要；失败回退模板 | 完成 | `ExplanationService.java:34-35,84-85,209-224`；真机 providerUsed=template 仍出完整结论 | — |
| §19.4 | 十步流程（分类→白名单→目录→生成→AST→LIMIT/日期→EXPLAIN→只读执行→截断解释→审计） | 完成 | `TextToSqlService.java:34-36,96,163,168`；`18-r8-evidence-truncation-proof.json`（12/12） | "问题分类"未做显式枚举，按语义目录主题解析（实现口径，可接受） |
| §19.5 | 仅单 SELECT；禁 DML/DDL/CALL/SET | 完成 | `SqlPolicy.java:24-27`；`AiSqlSecurityTest`（496 行） | — |
| §19.5 | 禁多语句/注释/子查询/CTE/UNION/JOIN/窗口；禁 `SELECT *`；列白名单；递归遍历各子句 | 完成 | `SqlPolicy.java:26,28-35`；`SemanticCatalog.java`；`SqlSafetyValidator` | — |
| §19.5 | 函数白名单 SUM/AVG/COUNT/MAX/MIN/ROUND | 完成 | `SqlPolicy.java:69-77`（另扩展 ABS/COALESCE 等） | — |
| §19.5 | 强制真实日期范围，禁 `MAX(dt)` 子查询 | 完成 | `r8-evidence-security-decision.md` §2.2；`SemanticCatalog.dateRangePin`；`SqlSafetyValidator.parseStrictDate` | — |
| §19.5 | 自动 LIMIT ≤200；扫描 ≤90 天 | 完成 | `SqlPolicy.java:55-59`；真机（100000→200、>90 天拒绝） | — |
| §19.5 | EXPLAIN 超阈值拒绝 | 完成 | `QueryCostGuard`；契约 §2.5（缺 rows/异常一律 fail-closed） | — |
| §19.5 | JDBC 只读 + 超时 + 最大行；账号仅 SELECT | 完成 | `SqlExecutor.java:36,39,86-89,120-122`；真机 INSERT → ERROR 1142 denied | — |
| §19.6 | `ai_query_history` / `ai_call_log` 字段齐备 | 完成 | 实读两表列（`ai_query_history` 16 列含 feedback/errors/scope_*/explain_rows；`ai_call_log` 12 列含 provider/model/tokens/elapsed/status/error） | — |
| §19.6 | 不记密码/token/未脱敏个人信息 | 完成 | 两表列清单无密码/token；`TextToSqlService.java:290-291` desensitize | — |
| §19.6 | feedback 可写可读 | **未做** | `AiQueryHistory.java:56` 有字段但全仓无写/读路径；实测 `ai_query_history` 54 行 **feedback 全 NULL** | 反馈闭环未实现 |
| §19.6 | 真实模型调用留痕 | **未做** | 实测 `SELECT COUNT(*) FROM ai_call_log` = **0**；真机 providerUsed=template | 无真实 LLM 调用记录 |
| §19.7 | LlmProvider 接口 + Mock + 规则回退 + OpenAI 兼容 | 完成 | `LlmProvider.java`；`MockLlmProvider.java`；`RuleBasedSqlFallback`；`OpenAiCompatLlmProvider.java:24-41` | — |
| §19.7 | 密钥从环境/凭据读取 | 完成 | `OpenAiCompatLlmProvider.java:33-35`（`${llm.base-url}`/`${llm.api-key}`）；`application.yml` 无 `llm.*` → 空 key 时 healthCheck=false（自禁用） | — |
| §19.7 | 超时/重试上限/熔断/每日预算/traceId 防重扣 | **未做** | `OpenAiCompatLlmProvider.java:12`（`Duration` 仅 import 未用）、`:39-41`（RestClient 未设超时）；全仓 grep `resilience4j/CircuitBreaker/maxRetries/budget/dedupe` = 0 | 5 项治理机制全缺 |
| §19.7 | 真实 LLM 端到端可用（A/B 对照） | **未做** | `ai_call_log` 0 行；`docs/deployment.md:117`、根 `README.md:105` 记载无 `LLM_API_KEY`；`experiments/ai-eval-*.json` 为 Mock 基线 | 论文第 8 章对比实验**缺真实模型组** |

### 4.2 §20 决策闭环

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §20.1 | 决策必备要素（证据/动作/目标指标/负责人/目标值/时间/风险/审批人/评价） | 完成 | 实读 `decision_task` 列（evidence_package_id/target_metric_code/owner/target_value/due_date/risk/approved_by）+ `decision_evaluation`；`DecisionService.java:409-430` 缺项即拒 | — |
| §20.2 | `decision_task` 补 5 字段 | 完成 | 实读：baseline_snapshot_id/definition_version/evidence_package_id/approval_note/execution_note 全在 | — |
| §20.2 | `decision_evaluation` 补 8 字段 | 完成 | 实读：baseline/actual_snapshot_id、window_start/end、baseline/actual_period_value、sample_count、definition_version | — |
| §20.3 | 12 态状态机与合法流转 | 完成 | `DecisionStateMachine.java`（12 常量 + 转移表）；`DecisionStateMachineTest`；真机 F2–F6c 走通 DRAFT→PENDING_REVIEW→APPROVED→IN_PROGRESS→COMPLETED | — |
| §20.3 | 状态命名与指导书一致 | 部分 | 代码/前端/真库用 `PENDING_REVIEW`，指导书 §20.3 为 `PENDING_APPROVAL`（r8 契约 §2.6-5 登记为命名偏差） | 命名不一致，语义等价 |
| §20.3 | 非法流转被拒且失败留痕 | 完成 | 真机 400 `DECISION_STATE_ILLEGAL`；`DecisionController.java:146-158` | — |
| §20.3 | REJECTED/CANCELLED 必带原因 | 完成 | 真机空 body → 400 `PARAM_INVALID`；`DecisionService.requireReason` | — |
| §20.3 | 各状态在数据中真实可达 | 部分 | 实测 `decision_task` 8 行仅 `DRAFT`(4) + `INSUFFICIENT_DATA`(4)；其余 4 态只在验收瞬时出现，无 REJECTED/CANCELLED 落库行 | 6 态无持久化样本 → 论文状态分布表只能引验收记录 |
| §20.4 | 改善率公式 UP/DOWN + 阈值可配置记版本 | 完成 | `DecisionService.java:358-367`（`(actual-baseline)/abs(baseline)`，DOWN 取反）；`:60-72` 阈值与 definition-version；实测 `definition_version=r8-window-v1` | — |
| §20.4 | 等长窗口聚合（平均或合计） | 部分 | `DecisionService.java:448-463` 按窗口**端点单快照**取值；类注释承认 `MetricStore` 无区间聚合 API（契约 §2.6-6 登记） | 只有边界点观测，`window_start/end` 仅语义边界 |
| §20.4 | baseline=0/样本不足 → INSUFFICIENT_DATA，不判无效 | 完成 | `DecisionService.insufficientReason`；实测 4 行 INSUFFICIENT_DATA（actual_value NULL、baseline 0.6000、sample_count 2） | — |
| §20.4 | 结论文案不称因果 | 完成 | note 组装为"执行前后指标变化"；`AnomalyRules.java:45` | — |
| §20.4 | 存在真实 EFFECTIVE/PARTIAL/INEFFECTIVE 样本 | **未做** | 实测 `decision_evaluation` 4 行**全 INSUFFICIENT_DATA**；`baseline_snapshot_id = actual_snapshot_id`（`S20260901_22→S20260901_22`） | 无正向效果样本 → 论文效果评价章节无实测支撑 |
| §20.5 | 五类决策模板（发现→建议动作→目标指标→风险） | **未做（整节）** | 指导书 `:1185-1191`；全仓无 `decision_template`/模板映射（`cart_rate`/`stock_days` 仅见 `MetricCalculator.java:126` 与 `V2__platform_pipeline_quality.sql:67,77` 的指标定义）；前端无模板选型 | 整节未实现。（注：`cart_rate` 本身也**未落地为指标**，见 §13.1 → 该模板即便实现也无数据可依） |

### 4.3 §21 权限、凭据与审计

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §21.1 | permissionCode 角色矩阵逐行一致 | 完成 | `RolePermissions.java:9-20,34-62`（9 权限码 × 4 角色）；未知角色 fail-closed `:70-76` | — |
| §21.1 | 未授权访问被 HTTP 拒（401/403） | 完成 | 真机 A1–A4、B1–B7；analyst→approve 403；`AuthInterceptor` fail-closed | — |
| §21.1 | `data_dev` 角色账号真实存在 | **未做** | 实测 `sys_user` 仅 admin/operator/analyst 3 行 | 矩阵有码、无账号 → 该行未实测 |
| §21.1 | operator 流水线仅"只运行"粒度 | 部分 | `RolePermissions.java:52-58` 授予 `PIPELINE_RUN`（同时覆盖 POST 与 retry，`PipelineController.java:45,61`） | 权限码粒度无法区分"启动"与"重试" |
| §21.2 | 不收 X-User-Id、无 demo 兜底；写并清理 CurrentUserHolder | 完成 | `AuthInterceptor.java`；`CallerContext.java`；真机伪造头 → 401 且审计仍归 analyst | — |
| §21.3 | application.yml 不提交可用默认密码 | **未做** | `analytics-server/platform-app/src/main/resources/application.yml:9,17,21` 明文默认（`meta_app_pw_2026`/`metric_pub_pw_2026`/`metric_read_pw_2026`）；`V5__platform_users.sql:2-3` 种子口令 `admin123/operator123/analyst123` | 与"只提供环境变量占位"直接冲突（环境变量可覆盖，但默认值可用且已提交） |
| §21.3 | meta/publish/read/Hive/SSH/LLM 凭据分离引用 | 部分 | `application.yml:5-21` 三套 MySQL 凭据分离；`:26` SSH 仅空占位；无 Hive/LLM 凭据项（LOCAL 免认证、`llm.*` 未配置） | 6 类凭据仅 4 类有引用项 |
| §21.3 | 只读账号无 DDL/DML | 完成 | `metric_read` 仅 `SELECT ON analytics_metric.*`；真机 INSERT → ERROR 1142 | — |
| §21.3 | 日志对 JDBC 密码/token/Authorization/LLM key 脱敏 | 部分 | 审计摘要层脱敏：`UserAdminController.java:115`、`TextToSqlService.java:290`、`AiController.java:252` | 全仓**无 logback/log4j2 配置**（实测 0 个文件）→ 运行日志层无强制掩码 |
| §21.3 | 种子账号首启提示改密/生产可禁用 | 部分 | `V5__platform_users.sql:2-3` 注释 + `docs/deployment.md` 要求 | 无启动提示代码、无种子开关（grep `seed`/改密 无匹配）→ 仅文档约定 |
| §21.4 | `operation_audit_log` 13 列与规范一致 | 完成 | 实读列清单与指导书 §21.4 逐列一致 | — |
| §21.4 | 记录 actor/role/result/前后摘要 | 完成 | 实测 92 行 / 2 用户；role、result、before_digest、after_digest、ip、trace_id 均落库 | — |
| §21.4 | 覆盖 AI 查询、决策审批、用户管理 | 完成 | 实测：AI_QUERY 52、DECISION_* 27、USER_TOGGLE 5 | — |
| §21.4 | 覆盖环境激活/流水线运行重试/质量强制处理/快照激活 | **未做** | 实测 `SELECT DISTINCT action` 仅 9 个：AI_QUERY / DECISION_{CREATE,SUBMIT,APPROVE,REJECT,START,COMPLETE,EVALUATE} / USER_TOGGLE | 4 类场景无审计行 |

### 4.4 §22 接口规范

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §22 | 统一 `/api/v1` 前缀 + code/message/data/traceId | 完成 | `ApiResponse.java:6`；17 个 `@RestController` 均带前缀 | — |
| §22 | 长任务返回 **202 + taskId** | 部分 | `PipelineController.java:45-53` 实测为 **HTTP 200 + body(`taskId`+`PENDING`)**；异步语义确在（`PlatformBeans.java:31` 线程池、`PipelineService.java:163`、单测 `PipelineServiceTest:216-238`）；`web/src/api.js:45` 预留 `ACCEPTED` 分支无生产者 | 异步语义有、**202 状态码无**；`POST /ingestion/runs` 同步返回 |
| §22 | 稳定 errorCode | 完成 | `GlobalExceptionHandler`/`PlatformExceptionAdvice`；真机 `PARAM_INVALID`/`DECISION_STATE_ILLEGAL`/`FORBIDDEN_PERMISSION`/`SQL_QUESTION_UNSAFE` | — |
| §22 | snapshotId 服务端默认 ACTIVE 并回传 | 完成 | `MetricController`/`AnalysisController` 默认取 ACTIVE；真机空 body → `S20260901_22` | — |
| §22 | 普通用户不能读未发布快照 | 部分 | `MySqlMetricStore.java:100-110`（query 无 status 过滤）、`:136-142`（findSnapshot 亦无）；`EvidenceBuilder.java:207` 主动回退 ARCHIVED | 客户端可传任意 snapshotId（含 ARCHIVED/VERIFYING），无状态校验（已在验收账本登记为与契约的口径冲突） |
| §22.1 | `/runtime-profiles` GET/POST、`/{id}/test`、`/{id}/activate` | 完成 | `RuntimeProfileController.java:32-75`（另有 PUT/disable/active） | 但**前端无对应页面**（§3.4③） |
| §22.1 | `/connectors`、`/connectors/{id}/test` | **未做** | 17 个控制器全量扫描无 `connectors` 映射；DB 无 connector 表 | 连接器接口整组缺失（与 §10.1/§10.2 同源） |
| §22.2 | `POST /ingestion/runs` | 完成 | `IngestionController.java:37` | 同步返回，无 taskId |
| §22.2 | `GET /ingestion/runs/{id}` | **未做** | 仅 `/status:31`、`/batches:44` | 无按 id 查询单批次/隔离详情 |
| §22.2 | `/pipelines/runs`、`/{id}`、`/{id}/retry` | 部分 | 实现为 `/api/v1/pipeline-runs`（`PipelineController.java:31,45,55,61`） | 路径与 §22.2 不一致，功能等价 |
| §22.2 | `GET /spark-jobs/{id}/logs` | **未做** | 无任何映射 | 作业日志只落库/落文件（验收包 `03-spark-job-run.tsv`） |
| §22.3 | `GET /metrics/snapshots/active` | **未做** | `MetricController.java:36-65` 仅 overview/snapshots/health/quality | 当前快照元信息接口缺失 |
| §22.3 | `GET /analysis/overview` / `behavior` / `users/rfm` / `quality/runs/{runId}` | 部分（4 项路径不符） | 等价能力：`/dashboards/overview`（`AnalysisController.java:47`）、`/analysis/funnel`（`:76`）、`/analysis/rfm`（`:93`）、`/metrics/quality?limit=`（`MetricController.java:65`） | 功能在、路径与查询方式不符；`/quality/runs/{runId}` 无法按 runId 查 |
| §22.3 | `GET /analysis/sales`、`/analysis/products` | 完成 | `AnalysisController.java:56,65` | — |
| §22.4 | `POST /ai/explanations`、`POST /ai/queries` | 完成 | `AiController.java:95,115` | — |
| §22.4 | `GET /ai/queries/{id}` | **未做** | 无映射；仅 `/ai/audit/history:271`、`/ai/audit/calls:280`、`/ai/history/my:289` | 单条查询审计回读缺失 |
| §22.4 | `POST /decisions`（人工/AI 草稿） | 部分 | `DecisionController.java:52` 存在，但 `:57` `source` 硬编码 `"ai"` | 人工草稿无法登记为 human；真库 8 行全 `source=ai` |
| §22.4 | `/decisions/{id}/submit`、`approve`、`start|complete|evaluate` | 完成 | `DecisionController.java:61,71,81,92,100,112,124`；真机 F 组全绿 | — |

---

## 5. §15–§18：真实执行编排、质量门禁、指标库所有权、页面与视图模型

### 5.1 §15 R6 真实接线与执行证据

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §15.1 | `pipeline_run` 必要字段（幂等/profile/版本/编码/业务时间/尝试/状态/trace/错误） | 完成 | `db/meta/V2__platform_pipeline_quality.sql:3-18`；`V7__platform_runtime_profile.sql:62-70` | 列名 `runtime_profile_id`（语义等价 profile） |
| §15.1 | `pipeline_stage_run` 字段（run_id/stage/status/records/起止/错误） | 完成 | `V2__platform_pipeline_quality.sql:21-33`；`V15__stage_evidence_mediumtext.sql:12-13`；验收包 `02-pipeline-stage-run.tsv`（ev_json_valid=1） | 阶段证据为合法 JSON |
| §15.1 | `spark_job_run` 字段（含 `command_digest`） | 部分 | `V7__platform_runtime_profile.sql:38-59`；`V10__spark_job_run_output_partitions.sql:5-8` | **无 `command_digest` 列**，仅 `arguments_json`（`V7:47`） |
| §15.1 | 阶段不得只存最后一个作业 ID | 完成 | 验收包 `02-pipeline-stage-run.tsv`（`external_job_id` 全 NULL）；`03-spark-job-run.tsv`（同 run 10 行） | 权威源改为 `spark_job_run` |
| §15.2 | `SparkStageExecutor` 接入生产 | 完成 | `PipelineService.java:114-117`；`PlatformBeans.java:66-76`；`03-spark-job-run.tsv:1-10` | — |
| §15.2 | 生产不再本地算指标 | 完成（死类未删） | `PipelineService.java:373`；`platform-common/.../MetricCalculator.java:22` 全仓无调用点 | `MetricCalculator` 保留为死类 |
| §15.2 | DWS/ADS 非占位计数 | 完成 | `03-spark-job-run.tsv:2-10`；`02-pipeline-stage-run.tsv:4`（records 56 = 14+7+35） | — |
| §15.2 | 事件白名单统一 | 完成 | `EventContractValidator.java:19`；`DwdSql.scala:37` | — |
| §15.2 | 作业失败依赖阶段 fail-fast | 完成 | `SparkStageExecutor.java:198` | — |
| §15.2 | JVM 重启恢复 | 完成 | `PipelineRecoveryService.java:41`；`PipelineRecoveryServiceTest.java:40,79,102,113` | — |
| §15.3 R6-9~R6-15 | 契约统一 / 提交器工厂 / 生产只做编排 / 真实输出证据 / staging→质量→发布 / 启动恢复+管理员接口 / 小规模端到端验收 | 完成 | `EventContractValidator.java:19`；`JobSubmitterFactory.java:22,73`；`SparkStageExecutor.java:160-165,190-197`；`AdsPublishJob.scala:51,79,83,109`；`AdsQualityJob.scala:100`；`PipelineRecoveryService.java:41`；`PipelineAdminController.java:30,39,48,60`；验收包 `01..26`+`30-final-acceptance.md` | — |
| §15.4① | Controller 返回 `taskId` 且阶段可查 | 部分 | `PipelineService.java:114`（RunResult 返回 runId/currentStage/stages）；`PipelineServiceTest.java:216-226` | 无字面 `taskId` 字段，由 `runId` 承担；前端只展示 run 级状态（stage 明细未用） |
| §15.4②–⑥ | 每阶段真实 `spark_job_run`+非空 externalJobId / 不再读 Landing JSON 算 ADS / staging 与正式分区证据 / 质量失败不发布且重试只重跑失败阶段 / 重启恢复测试 | 完成 | `03-spark-job-run.tsv`（10 行 external_job_id 全非空）；`PipelineService.java:373`；`V10:5-8`+`26-prune-fix-verification.tsv`；`AdsQualityJob.scala:92,100`；`PipelineRecoveryServiceTest.java:79,102,113` | — |

### 5.2 §16 质量门禁

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §16.1 | `quality_rule_definition` 表（11 字段） | **未做** | 全仓（`analytics-server`/`spark-jobs`/`warehouse`）grep `quality_rule_definition` → **0 命中**（仅指导书 `:806-808`） | 规则定义仍硬编码在代码里（`QualityChecker.java:56,76,85,100`） |
| §16.1 | `data_quality_result` 保存 9 字段 | 完成 | `V2__platform_pipeline_quality.sql:36-49`；`V11__data_quality_layers.sql:5-9`；`V12__quality_detail_width.sql:6-8` | layer/severity/target_table/snapshotId 齐 |
| §16.2 | Landing JSON 可解析率 | **未做** | `QualityChecker.java:56,76,85,100`（仅 4 规则） | 无解析率规则 |
| §16.2 | ODS `event_id` 非空 | 部分 | `QualityChecker.java:76`（通用 null 率） | 非 event_id 专列，且作用于 Landing 而非 ODS |
| §16.2 | ODS schema 受支持 | **未做** | `OdsLoadSql.scala:194`（仅 `BAD_VERSION_OR_KEY` 拒收） | 无 schema 版本质量规则 |
| §16.2 | ODS `eventId` 唯一率 | 完成 | `QualityChecker.java:85`；`AdsQualityJob.scala:105-106` | `EVENT_ID_UNIQUE` 的 Java/Spark 计数分歧已登记 |
| §16.2 | DWD 行为 user/product/type/time 合法 | 部分 | `DwdSql.scala:9,37`；`Cleaners.scala:33-39` | `Cleaners` 谓词**主源码零引用**（grep `Cleaners.` 无命中）；reject 只产 `DUPLICATE_EVENT`（`DwdSql.scala:45`），无 BAD_ENUM/BAD_AMOUNT/FUTURE_TIME（DDL 注释见 `01-dwd.sql:61`） |
| §16.2 | DWD 订单行金额 = 数量×单价−折扣（<0.01） | **未做** | `AdsSql.scala:204-256`（只有 MAX−MAX 对账） | 无行级公式校验 |
| §16.2 | DWD 退款额 ≤ 实付额 | **未做** | 全仓无该规则 | 与 §12.4-6 同源缺口 |
| §16.2 | DWD 非法状态跳转 | **未做** | `Cleaners.scala:37` `validOrderStatus` 无调用点 | 与 §12.4-3 同源缺口 |
| §16.2 | DWS 汇总金额与 DWD 对账 | 部分 | `AdsQualityJob.scala:124`（ADS↔DWS **漏斗**对账） | 金额未对账 |
| §16.2 | ADS GMV ≥ 净销售 ≥ 0 / UV ≤ PV / 支付用户 ≤ 浏览用户 | **未做** | `AdsSql.scala:204-256` 无这些不等式 | — |
| §16.2 | 发布 staging 与 Hive ADS 行数一致 | 完成 | `AdsPublishJob.scala:79`；`MetricExportJob.scala:75` | — |
| §16.2 | 时效 `ingest_time − event_time` P95 | **未做** | 全仓无 P95 规则 | — |
| §16.3 | ERROR/BLOCKING 阻断、WARN 黄标、INFO 趋势 | 部分 | `V11__data_quality_layers.sql:7`（三级注释）；`AdsQualityJob.scala:48,87,100,124`（BLOCKING）、`:58,:106`（降级 ERROR） | 无 WARN 级；ERROR 语义混用；INFO 结果不落库（`PipelineService.java:694-722` 跳过） |
| §16.3 | 阈值版本化 | 部分 | `V2:43`（threshold 逐行入库）；`QualityChecker.java:56,76,85,100`（0.01/0.001/0.0005/0 **硬编码**） | 无规则版本号 → 改阈值会改写新旧运行的解释 |
| §16.4 | 采集层：来源=Landing 合法 + quarantine + parse error | 部分 | 验收包 `20-reconciliation.tsv`（L1 55=52+3） | 属一次性验收对账，无通用恒等式校验规则 |
| §16.4 | 交易层 3 公式（paid_amount / 退款分摊 / 净销售） | **未做** | 全仓无对应校验规则 | — |
| §16.4 | 发布层 行数一致 + 抽样 checksum | 部分 | `AdsPublishJob.scala:79`；`MetricExportJob.scala:75` | 无抽样 checksum |
| §16.4 | 质量失败/超时视为发布失败 | 完成 | `AdsQualityJob.scala:92,100`；`SparkStageExecutor.java:170-198` | 规则自身无超时概念 |
| §16.5 | 运维页显示 11 字段（run/规则/层次/检查数/错误数/错误率/阈值/严重度/通过/样例路径） | 部分 | `web/src/views/Ops.vue:132`；`web/src/utils/tables.js` `qualityRows` | 只展示 runId/ruleCode/passed/detail/createdAt，**缺层次/严重度/阈值/检查数/错误数/错误率/样例错误路径** |
| §16.5 | 普通运营页只显示更新时间 + 质量状态 | 完成 | `web/src/components/AnalysisContext.vue:6,10` | — |

### 5.3 §17 指标库所有权与发布

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §17.1 | 三数据源职责（meta 读写 / publish / read-only） | 完成 | `PlatformDataSources.java:25,141`；`application.yml` | — |
| §17.1 | 只读源缺失时 AI 必须失败（禁止回落 meta） | 完成 | `SqlExecutor.java:49,71-76`（fail-closed 抛异常） | `remediation-status.md:219①` 的旧登记（"分支仍在"）已过期，见本文 §7 文档订正 |
| §17.2 | snapshot/value 唯一所有者 `analytics_metric` | 完成 | `db/metric/V1__metric_store.sql:2-4`；`db/meta/V13__metric_definition_r7.sql:19-25` | meta 旧表保留为弃用副本（未 DROP，符合"先迁移后清理"） |
| §17.2 | 新写入只进 `analytics_metric`；独立 Metric Flyway initializer | 完成 | `V13:21-22`；`MetricFlywayInitializer.java:13,40` | — |
| §17.3 | `metric_snapshot` 10 列 / `metric_value` 列 + 唯一键含 period/dimensions | 完成 | `V1__metric_store.sql:8-29,32-45`（`uk_snapshot_metric_period_dim:44`）；version 递增见 `MetricPublishRepository.java:38-44,60`（真库 1→6 实测） | — |
| §17.3 | 10 张 `ads_*_m` 宽表 | 部分 | Hive `warehouse/ddl/04-ads.sql`（10 表）；MySQL `V2__metric_ads_materialized.sql:1-46` + `V3__metric_ads_r7.sql:2` | **MySQL 仅 8 张**，category/region 明确不建（已文档化，未造假） |
| §17.3 | 禁止 dt 单列主键 | 完成 | `V2__metric_ads_materialized.sql`（PK = snapshot_id + dt） | — |
| §17.4 | 6 组件（读/写/校验/激活/发布/存储） | 完成 | `AdsExportReader.java`；`MetricAdsWriter.java`；`MetricPublishValidator.java`；`MetricPublisher.java`；`MySqlMetricStore.java`；`MetricStore.java:9` | — |
| §17.5 | 8 步原子发布（旧 ACTIVE 失败保留） | 完成 | `MetricPublishRepository.java:50-68`；`MySqlMetricStore.java:28,149`；`MetricPublishValidator.java:224-226`；`V1:22,27`；`20-reconciliation.tsv` | — |
| §17.6 | `AdsMaterializer` 退役 / 不吞错 / 绑发布事务 / `source=spark-ads` | 完成 | `MetricPublisher.java:37`（退役说明）；`MySqlMetricStore.java:149`；`MetricAdsWriter.java:44,69`；`V1:20`；`MetricPublisherMySqlIT`（`-Dmetric.it=true` 1/1） | — |
| §17.7 | `MetricStore`/`MetricPublisher` 接口 + 按 `metric_store_type` 选实现 | 部分 | `MetricStore.java:9`；`V7__platform_runtime_profile.sql:26`；`RuntimeProfile.java:57` | 列存在但**无按类型选实现的工厂/路由代码** |
| §17.7 | ClickHouse 只留枚举 + 能力矩阵 + 接口 | **未做** | `V7:26` 注释仅 MYSQL/DORIS；`MetricStore.java:9` | 无 CLICKHOUSE 枚举、无能力矩阵文件（属扩展路线，但指导书明确要求"只留枚举"也未做） |
| §17.7 | 阶段一 MySQL 完整 | 完成 | `db/metric/V1-V3`；验收包 `11-metric-value-active.tsv` | — |

### 5.4 §18 前端与视图模型

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §18.1 | 分析/RFM 只读 MetricStore 与 metric mappers | 完成 | `AnalysisService.java:3-10,29-37`；`AnalysisController.java:47-93` | — |
| §18.1 | 禁止读 JSON / 查商城表 / 控制器聚合 / 页面公式 / 快照不一致 | 完成 | `AnalysisService.java:29-37`；`Behavior.vue:34,49`；`Products.vue:62`；`Sales.vue:61` | — |
| §18.2 | 运营总览 10 指标 + **环比** + 更新时间 + 质量状态 | 部分 | `Overview.vue:21-41`；`AnalysisContext.vue:6,10` | **无环比**：`AnalysisService.java` 全文 grep `previous/环比/mom/prev` = 0 命中 |
| §18.2 | 用户行为：活跃趋势/构成/漏斗/渠道分类 + 口径窗口可见 | 部分 | `Behavior.vue:12,18-34`；`analysis-viewmodel-r7-4.md` §3.4 | 行为构成、渠道/分类维度未发布（页面已注明 `Behavior.vue:49`） |
| §18.2 | 商品分析：热度/转化/销量/销售额/库存覆盖天数 | 部分 | `Products.vue:18-30`；`metric-lineage.md` §2 | 库存覆盖天数**无 ADS 载体**，页面不展示（`Products.vue:62`） |
| §18.2 | 销售分析：趋势/净销售/分类/地区/退款，GMV 与净销售并列 | 部分 | `Sales.vue:17-31,61` | `byCategory/byRegion` 恒空 + `UNKNOWN_DIMENSION_TABLE` |
| §18.2 | 用户分群 RFM 八类/消费额/最近购买/偏好/生命周期 | 部分 | `Rfm.vue:16,22,41,54` | 消费额列因 `ads_user_profile_m` 无金额列恒空 + `RFM_AMOUNT_UNAVAILABLE` |
| §18.2 | 流水线与运维：连接器/批次/run/stage/job/质量/日志/快照/操作 | 部分 | `Ops.vue:67,132,152`；`Pipeline.vue:5,29`；`web/src/api.js` | **无 stage / spark_job_run / 日志 / 失败原因 / 激活环境** 的页面与接口（后端 `PipelineController.java:55` 已返回 stages，前端未用）；无连接器页（§3.4③） |
| §18.2 | AI 助手 SQL **默认折叠**、仅分析角色可展开 | 部分 | `AiAssistant.vue:78`（`<pre>` 常显）；`App.vue:68-74`（仅 admin 特权） | 无折叠、无角色门禁 |
| §18.2 | 决策中心：草稿/审核/负责人/目标/截止日/执行记录/效果 | 部分 | `Decisions.vue:39-40,44-67,186`；`DecisionController.java:61-139` | 表头无截止日列（`dueDate` 仅创建时写入）；无模板选型（§20.5） |
| §18.3 | 信封 8 字段 + 图表语义数据 | 完成 | `analysis-viewmodel-r7-4.md` §2；`ApiResponse` | — |
| §18.4 | 路由按角色生成 + 服务端校验 | 部分 | `router.js:5-15`（仅 `requiresAuth`，**无角色 meta**）；`App.vue:68-74` | 无 `data_dev` 角色；归档快照对普通角色仍可读（§22 末条） |
| §18.4 | 取消旧请求 | 完成 | `useAnalysis.js:53`；`AiAssistant.vue:212` | — |
| §18.4 | 四态 loading/empty/error/stale | 完成 | `chartState.js:23-29`；`ChartState.vue:21`；`tests/chartState.test.js:18,28,40` | stale 仅单测覆盖（验收账本已登记） |
| §18.4 | 表格**分页/排序** + CSV 导出带 filter/snapshotId/生成时间 | 部分 | `utils/exportCsv.js:1-22`；`useAnalysis.js:97` | `DataTable.vue` 全文 grep `page/sort/分页/排序` = **0 命中** → 分页与排序未实现 |
| §18.4 | 路由懒加载 + BaseChart 按需注册 | 完成 | `router.js:5-15`；`BaseChart.vue`（echarts/core 按需）；分包 510 kB（账本 `:230`） | — |
| §18.4 | `/mall`、商品后台、生成器控制迁出 | 完成 | `web/tests/boundary.test.js:1-89`；`remediation-status.md:223-224,233,236` | — |
| §18.5 | Hive/MySQL/API/DOM/AI **五处同值断言** | 部分 | `.verify/r7-4-dom.py`；验收包 `18-r7-4-dom-report.json`（22 断言 0 失败 0 console 错误，黄金值 2042.00 / 5 / 7 / 60.00%）；`analysis-viewmodel-r7-4.md:121-125`（AI 腿记 R8） | 已自动化并执行的只有**四处**；AI EvidencePackage 腿未纳入同一同值断言（`18-r8-accept-report.json` 只断言 snapshotId，不含黄金值比对） |

---

## 6. §23–§31：测试分层、R7–R9 清单、组件与验收

### 6.1 §23 测试体系与提速

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §23.1 | L0 单测 / L1 黄金链 / L2 性能与环境 三级分层 | 部分 | L0：`mvn -o test -f analytics-server/pom.xml` **303/303**；`spark-jobs` 46 succeeded / 0 failed；`mall-simulator` 54/54；L1：验收包 run 30（01–04 流水线、15 四层行数、20 对账） | **L2 未做**：整改后平台无任何性能/规模档实测记录（`experiments/perf-*.json` 均为 2026-09-06 旧链）→ 论文表 8-3 无从填数 |
| §23.2 | 测试数据分档：黄金 55 / 中型 1k / 压测 10 万 / 100 万 | 部分 | `tests/golden-dataset/events/golden-20260901.jsonl`（55 行）；`tests/ai-questions/questions.jsonl`（100 题，safe 50 / blocked 50） | `medium-1k`、`perf-100k`、`perf-1m` **三个数据集不存在**（全仓无对应文件） |
| §23.3 | 容错与故障注入（Spark 失败、Flume 断点、LLM 异常） | 部分 | 真机 runs 26/27/28 均 FAILED 且被 StageGate/质量门阻断（故障证据保留）；`PipelineRecoveryServiceTest`、`DataQualityGateTest`、`LocalProcessSparkSubmitterLogTest` | **Flume 重启与文件轮转从未实测**（`connection-ingestion` 测试仅 3 个鉴权类，0 个 Flume 用例）；**"模型返回非 JSON"无专项用例**，且指导书 §23.3 要求的 `AiOutputValidator` 类**不存在** |
| §23.4 | 前端组件测试 + 端到端 + 同快照一致性 | 部分 | web `node --test` **74/74**；DOM 验收 **22/22**（平台）+ **17/17**（商城） | 未使用 Vitest（指导书指定）；Playwright 脚本位于 web 测试命令之外；**无"同一 snapshotId 下拦截网络请求仍一致"的断言** |
| §23.5 | 验收归档按 `日期/commit/runId` 命名 | 部分 | `docs/acceptance/r9-20260911-e272c8a-run30-S20260901_30/`（**62 文件**：37 顶层 + 13 平台截图 + 4 商城截图 + 8 脚本） | R1–R8 历史验收目录未按该命名规范，仅 R9 合规 |
| §23.6 | 实验结论回填 `experiments/` | 部分 | `experiments/README.md`（本轮补标"**整改前旧链，未重测**"⚠️）；`docs/v2-completeness-audit.md` 即本文件 | 旧链数字仍被 `docs/demo/demo-script.md`、`thesis-outline.md`、论文草稿引用（本轮已就地加⚠️标注，但**数字本身仍是旧链**） |

### 6.2 §24 R7–R9 工作清单（抽样复核，未复核条目不计为完成）

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §24.1-4 | 删除本地假计算路径 | **完成（本轮修复）** | 删除前全仓仅 2 处命中 `MetricCalculator`：自身 `MetricCalculator.java:22` + `PipelineService.java:373` 注释（注释已声称"删除 MetricCalculator 路径"）→ 本轮 `git rm` 该类，`mvn -o -DskipTests compile -f analytics-server/pom.xml` 7 模块编译通过 | 修复前的状态正是 §29.2 的"声明已删但仍在" |
| §24.3 | 指标表唯一所有者 = `analytics_metric` | 部分 | 写入路径唯一：`MySqlMetricStore` / `MetricPublishRepository` 只写 `analytics_metric` | `analytics-server/platform-app/src/main/resources/db/meta/V2__platform_pipeline_quality.sql:81,99` 仍在 **`analytics_meta` 建 `metric_snapshot` / `metric_value` 同名镜像表** → 双所有者残留 |
| §24.8-1 | 语义目录覆盖全部已发布 ADS | 部分 | `SemanticCatalog` 登记 5 张：`ads_operation_overview_m` / `ads_sale_trend_m` / `ads_behavior_funnel_m` / `ads_hot_product_m` / `ads_product_conversion_m` | 缺 `ads_active_trend_m`（活跃趋势）、RFM（`ads_user_profile_m`）、`ads_data_quality_m`；**无目录版本常量**（§24.8 要求口径版本可追溯） |
| §24.10-1 | 固定 seed 中型数据集落档 | 未做 | — | 无 `medium-1k` 等数据集文件（与 §23.2 同一缺口） |
| §24 其余条目 | — | **未逐条复核** | — | 本文件仅对上述 4 条做了 `file:line` / 真库级复核；其余条目**既不计完成也不计未做**，需按同一口径复核后才可写入论文 |

### 6.3 §25 现有类导航与接线

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §25.1 | 用导航表定位实现，避免另起炉灶 | 部分 | 各层主类均存在：采集 `IngestionService`、编排 `PipelineService`/`StageGate`、指标 `MySqlMetricStore`/`MetricPublisher`、AI `TextToSqlService`/`EvidenceBuilder`、决策 `DecisionService`/`DecisionStateMachine` | 导航未登记 R7 新增类（`DataQualityGate`、`QueryCostGuard`、`MySqlMetricStore`、`LocalProcessSparkSubmitter` 等），照导航读会漏掉关键实现 |
| §25.2 | 前端路由级角色拦截 | **未做** | `web/src/router.js:5-15` 仅 `requiresAuth`，**无 `meta.role`**；角色只用于菜单过滤 `App.vue:68-74` | 前端无路由守卫；越权靠后端 403 兜底（R8 越权 7/7 拦截为后端证据，非前端） |
| §25.3 | 提交器矩阵：LOCAL / SINGLE_NODE / REMOTE_CLUSTER | 部分 | LOCAL 真机跑通（`LocalProcessSparkSubmitter`，真机 10 条 Spark 作业） | `SshSparkSubmitter` **无任何单测/实测**；三环境矩阵未通过（LOCAL 之外均为 SKIPPED） |

### 6.4 §26 新增组件建议

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §26 | 按职责（明示**不要求**机械地一职责建一类） | 部分 | 各职责均有落点，判断标准按"职责是否落地"而非"是否有类" | ①**职责仍集中在 `PipelineService`（1125 行）**——§26 明列的反模式；②`AnalysisService` 单类承载 6 个端点；③审计写入与脱敏逻辑在 3 处内联（未收敛为组件）；④`AnomalyDetector` 有类**未接生产链路**（页面/发布路径 0 调用） |

### 6.5 §27 扩展路线

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §27 | Doris / ClickHouse / Kafka / Livy / DataX 等扩展 | **不适用（本期不实现，合规）** | 全仓对这 5 项 0 引用、0 依赖、0 配置；§27 原文定位为后续路线 | 论文"展望"可写，**正文不得写成已实现**；§10.6 DataX 同理不适用（采集用 Flume + 本地着陆区） |

### 6.6 §28 术语表与口径一致性

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §28 | 术语表与实现/论文口径一致 | 部分 | 本轮逐词 grep：`水位线`、`DataX`、`状态归并`、`IQR`、`z-score`、`externalJobId`、`临时分区`、`原子快照`、`审批` **9 词在实现与论文中 0 命中** | 术语表需按真实实现改写（如"原子快照"实际是 `metric_snapshot` 状态机 + 唯一 ACTIVE）；需求分析写"五类角色"而代码为四角色且 `data_dev` 无账号（§6.3 同源问题） |

### 6.7 §29 禁止模式

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §29.1 | 十类禁止模式（分页伪实现、假计算、Mock 当真实、把清单当实测等） | 完成 | 逐条复查：`DataTable.vue` 对 `page/sort/分页/排序` **0 命中**（确实没做，而非假做）；Mock 均在测试侧且标注 `Mock`；无"清单即实测"表述（本文件即反例约束） | — |
| §29.2 | 不得保留"已声明删除但仍在"的路径 | **完成（本轮修复）** | 修复前 `PipelineService.java:373` 注释称已删除 `MetricCalculator`，而类文件仍在 `platform-common`（实测 2 处命中）→ 本轮删除并编译通过；旧文档对 `GoldenE2ETest`/`AdsMaterializer`/`1.png` 的引用本轮一并订正 | — |

### 6.8 §30 最终验收清单（指导书原文 19 项）

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §30 | 6 域 / **19 项**自检 | **17 ✅ / 2 ⚠️ / 0 ❌** | `docs/acceptance/r9-…/30-final-acceptance.md`（本轮订正 7 处：项数 18→19、截图路径 `mall-home.png`→`17-screenshots/mall/02-mall.png`、备份 48→**54**、映射 68→**74**、文件数 61→**62**、`metric_snapshot.version` "恒 1"→`MAX(version)+1`、A 段"全部 YES"→**10 YES + 1 `—`**） | 2 ⚠️ = ①性能与环境档未重测（§23.1-L2）②决策无 EFFECTIVE 正样本（§20.4）。该项 19 项与 `30-final-acceptance.md` 的 20 行表（多 1 行自检项 C4）= 18 ✅ / 2 ⚠️ / 0 ❌ 不矛盾 |

### 6.9 §31 执行顺序

| 节号 | 要求 | 判定 | 证据 | 缺口 |
|---|---|---|---|---|
| §31 | 8 步执行顺序（边界→采集→数仓→指标库→看板→AI→决策→验收） | 完成 | `docs/remediation-status.md` 阶段表顺序与 §31 一致；R1–R9 按该顺序推进（`git log` 可核） | 连接器插件体系**不在 §31 的 8 步内**（属 §10/§22 缺口，与本项无关，勿混淆） |

---

## 7. 汇总

### 7.1 逐节判定统计（本文件表格实测，可复跑）

| 指导书范围 | 可判定条目 | 完成 | 部分 | 未做 | 不适用 |
|---|---:|---:|---:|---:|---:|
| §1–§2（口径与基线） | 4 | 2 | 0 | 0 | **2** |
| §3–§11（边界/商城/契约/采集/Landing） | 30 | 10 | 16 | 3 | 1 |
| §12–§14（数仓分层/口径/作业契约） | 79 | 41 | 26 | **12** | 0 |
| §15–§18（执行/质量门/指标库/页面） | 66 | 33 | 23 | **10** | 0 |
| §19–§22（AI/决策/权限审计/接口） | 70 | 42 | 13 | **15** | 0 |
| §23–§31（测试/R7-R9 清单/组件/验收） | 20 | 4 | 12 | 2 | 2 |
| **合计** | **269** | **132** | **90** | **42** | **5** |

复跑方式：按 `|` 分列取**第 3 列**判定值（去掉 `**` 后前缀匹配 `完成/部分/未做/不适用/未逐条`），**必须排除表头行与 `|---|` 分隔行**
（把 §7.1 自己的表头算进去会得到 270 / 完成 133 的假数）。
> 口径说明：一条指导书要求若拆成多个可独立验收的条目（如 §16.2 的 8 项校验），本表按**条目**计，因此"未做 42"与 §7.2 的
> "25 组功能缺口"是同一批事项的两种计法（组=同一功能的多条目合并）。

### 7.2 未做清单（42 条 → 25 组）

| # | 组 | 条数 | 缺口（结论） | 证据 |
|---|---:|---:|---|---|
| 1 | 连接器插件体系 | 3 | 无 `SourceConnector` 抽象、无 `source_connector` 表/配置模型、无 `/connectors` 接口 | §10.1/§10.2/§22.1 全仓 0 命中 |
| 2 | 采集侧验收自动化 | 1 | 正常/重复/坏行/半行/追加/轮转 + 对账 6 类用例全缺 | `connection-ingestion` 测试仅 3 个鉴权类 |
| 3 | DIM 维度表 | 3 | `dim_date`/`dim_region`/`dim_metric` **有 DDL 无数据** | 实产仅 `dim_user`/`dim_product`；真库 `dw_dim` 行查 |
| 4 | 订单状态机校验 | 1 | 非法状态跳转不进 reject | reject 表实测只写 `DUPLICATE_EVENT` |
| 5 | ADS 缺口 | 3 | `ads_sale_trend` 补 `net_sale_amount`；`ads_category_sale`/`ads_region_sale` 无产出 | Hive 实产 8/10；`byCategory/byRegion` 恒空 |
| 6 | 复购率 `repeat_rate` | 1 | 未落地 | `docs/contracts/metric-lineage.md:43-44` 自登记未落地 |
| 7 | 异常规则版本化 | 1 | 权重/阈值硬编码，无规则版本 | `QualityChecker` 阈值常量；无规则版本列 |
| 8 | RFM 金额列 | 1 | 页面无消费金额（M）列 | `Rfm.vue` 无金额列；后端视图模型缺字段 |
| 9 | 检测算法接生产 | 1 | `AnomalyDetector` 有类、**0 生产调用** | 全仓仅自身与测试引用 |
| 10 | 作业参数校验 | 1 | 未知参数不报错 | `JobCommandBuilder`/参数解析无白名单校验 |
| 11 | 质量含迟到率 | 1 | `dataQuality` 无"迟到率" | §19.2 要求项在实现 0 命中 |
| 12 | AI 反馈与调用留痕 | 2 | `feedback` 全 NULL（54 行历史全空）；`ai_call_log` **0 行** | 真库只读 SQL |
| 13 | LLM 治理与真实接入 | 2 | 无超时/重试上限/熔断/每日预算/`traceId` 防重扣；未接真实模型 | `Duration` 导入未用（`OpenAiCompatLlmProvider.java:12`）；`providerUsed=template` |
| 14 | 决策正样本 | 1 | 4 条评价全 `INSUFFICIENT_DATA` | `analytics_metric.decision_evaluation` 真库 |
| 15 | **五类决策模板** | 1 | §20.5 整节未实现 | 全仓 0 命中五类模板 |
| 16 | `data_dev` 账号 | 1 | 角色存在但**无账号** | `sys_user` 真库仅 admin/operator/analyst |
| 17 | 默认口令 | 1 | `application.yml` 提交可用默认口令；V5 种子弱口令 | `application.yml:9,17,21`；`V5__platform_users.sql:2-3` |
| 18 | 审计动作覆盖 | 1 | 缺环境激活/流水线重试/质量强制/快照激活 4 类 | 真库仅 9 类 action（`operation_audit_log`） |
| 19 | 接口缺口 | 4 | 无 `/spark-jobs/{id}/logs`、`/ingestion/runs/{id}`、`/metrics/snapshots/active`、`/ai/queries/{id}` | Controller 映射实读 |
| 20 | 质量规则表 | 1 | 无 `quality_rule_definition`（11 字段） | 全仓 0 命中该标识符 |
| 21 | **7 项质量校验** | 7 | Landing 可解析率 / ODS schema / 行金额一致性 / 退款≤实付 / 非法状态跳转 / ADS 关系式（GMV≥净销售≥0、UV≤PV）/ 时效 P95 | `QualityChecker` 实为 4 条规则 |
| 22 | 交易层 3 公式 | 1 | `paid_amount` / 退款分摊 / 净销售 未按 §16.4 实现 | `OrderTradeCompiler` 无对应公式 |
| 23 | 指标存储类型选择 | 1 | 无 `metric_store_type` 驱动的存储选择；ClickHouse 无枚举/能力矩阵 | `MySqlMetricStore` 唯一实现 |
| 24 | 中型数据集 | 1 | 无 `medium-1k`/`perf-100k`/`perf-1m` | 全仓无对应数据文件 |
| 25 | 路由级角色拦截 | 1 | `router.js` 无 `meta.role`，角色只过滤菜单 | `web/src/router.js:5-15`；`App.vue:68-74` |

### 7.3 部分完成清单（90 条 → 20 组重点"缺哪一半"）

| # | 模块/要求 | 已完成 | 缺的一半 |
|---|---|---|---|
| 1 | 两程序边界 §3.1 | 两进程/两端口/两前端已分 | 商城默认连 `mall_simulator`+`root`，未用三库账号方案 |
| 2 | 前端分离 §3.4 | 生成器与商城页已迁出 | **无"数据源"页**（`Pipeline.vue:11` 手填 `runtimeProfileId`）；无商城外链 |
| 3 | 采集服务 §9.2/§9.3 | 采集主链 + 状态接口可用 | `IngestionService.java:221` 用商城配置键 `mall.landing.path` 解析 Landing 根，与激活档案 `landingUri` 可能不一致 |
| 4 | Landing 抽象 §11 | 本地着陆区 + READY manifest | HDFS 路径一致性未实测（本地为唯一实测环境） |
| 5 | DWD 清洗 §12.4 | 去重/坏行进 reject | `Cleaners.scala` 谓词**0 处主链引用**；排序稳定性、items 展开核对、退款汇总取最新未实现 |
| 6 | DWS 粒度 §12.5 | 日粒度聚合可用 | `dws_behavior_funnel_day` 缺"分类×渠道"维度 → 页面渠道维度无载体 |
| 7 | 漏斗口径 §13.2 | 漏斗四态可查 | 后阶段>前阶段时静默截断，未给数据说明 |
| 8 | 排行与分群 §13.3/§13.4 | 热度排行 + RFM 八类 | 排行并列未按 `product_id` 稳定排序；`ntile(5)` 无稳定排序与"样本少仅演示"标注；M 口径版本未固定 |
| 9 | 作业契约 §14.1–§14.3 | run/stage/job 落库与 UI 可查 | 作业码用 `sci/dim/tdw` 而非 `lsi/dmb/tds`；`BUILD_ADS` 不产出 10 张；无 `outputSnapshotId`、无 `startedAt/finishedAt/logUri`、无 `errorCode/errorMessage` |
| 10 | 发布原子性 §14.4-4 | 状态机 + 唯一 ACTIVE | 未做元数据交换/受控 rename（直接 SQL 切换） |
| 11 | 质量分级 §16.3 | ERROR 阻断发布 | **无 WARN 等级**、INFO 未持久化、阈值未版本化 |
| 12 | 运维页 §16.5 | 5 字段可见 | 规则/层次/阈值/严重度/样例路径等 5 字段缺 |
| 13 | ADS 宽表 §17.3 | MySQL 物化 8 张 | 分类/地区 2 张缺（同 §7.2#5） |
| 14 | 页面 §18.2 | 10 页可用、四态齐 | 总览**无环比**（`AnalysisService` 对 `previous|环比` 0 命中）；行为构成/渠道/库存覆盖天数无数据；AI SQL 未折叠且无角色门；决策表缺"截止日" |
| 15 | 路由与表格 §18.4 | 懒加载 + 四态 + 取消旧请求 | 无路由级角色拦截；**表格无分页/排序** |
| 16 | 五处同值 §18.5 | 四处（Hive/MySQL/API/DOM）已自动化 | AI 腿未纳入同值断言 |
| 17 | 指标表唯一所有者 §24.3 | 写入唯一 | `analytics_meta` 仍存 `metric_snapshot`/`metric_value` **镜像表** |
| 18 | 语义目录 §24.8 | 5 张 ADS 已登记 | 缺活跃趋势/RFM/质量 3 张；无目录版本常量 |
| 19 | 提交器矩阵 §25.3 | LOCAL 真机跑通 | `SshSparkSubmitter` 无单测/未实测；三环境矩阵未通过 |
| 20 | 职责收敛 §26 | 各职责均有落点 | 仍集中在 `PipelineService`（1125 行，§26 明列反模式）；审计/脱敏逻辑 3 处内联 |

### 7.4 不适用清单（5 条，须在论文中说明"本期不实现"）

| 条目 | 原因 |
|---|---|
| §2.1 / §2.2 | 指导书自身的**整改前基线快照**（当时 R7 未完成、R9 未开始），非当前状态，不得当现状引用 |
| §10.6 DataX | 首期采集用 Flume + 本地着陆区；DataX 为后续路线，0 引用 |
| §27 Doris / ClickHouse / Kafka / Livy 扩展 | 指导书定位为扩展路线，本期不实现；正文不得写成已实现 |
| §24 其余条目（除 §24.1-4/§24.3/§24.8-1/§24.10-1） | 本轮未逐条复核 → **既不计完成也不计未做**，需按同一口径复核后才可写入论文 |
| §3.2 目标物理目录 | 指导书原文声明"是否马上移动目录不是本阶段验收重点"；顶层目录名与指导书不同，`connectors/` 不存在 |

### 7.5 自评 ✅ 但证据薄弱清单（引用时须加限定词）

| # | 自评结论 | 实际问题 | 证据 |
|---|---|---|---|
| 1 | §18.5 五处同值断言 | 实际自动化 **4 处**（AI 腿未纳入） | `18-r8-accept-report.json` 只断言 snapshotId |
| 2 | 质量 4/4 通过 | 真机为 **4 检查 / 3 通过 / `["EVENT_ID_UNIQUE"]` 失败** | 验收包 `16-quality*.tsv`；账本 `:196` |
| 3 | DOM 证据对应 run 30 | DOM 报告 `contextBefore/After` 显示快照 **`S20260901_22`**，非 run 30 的 `_30` | `18-r7-4-dom-report.json:383-384` |
| 4 | 无 ACTIVE 快照分支已验收 | **仅单测覆盖**，无真机态 | 账本已登记 |
| 5 | 快照读权限 | 归档快照可被普通角色读取（无权限校验） | `MySqlMetricStore.java:100-110,136-142`；`EvidenceBuilder.java:207` 回落 ARCHIVED |
| 6 | 运行状态可信 | `LocalProcessSparkSubmitter.status()` 恒返回 `SUBMITTED` | 源码实读 |
| 7 | D-R9-2 剪枝修复 | 无单元测试，仅脚本验证 | `.verify/r9-prune-fix-verify.ps1` |
| 8 | 文档映射全覆盖 | 74 条映射中 **57 已实测 / 9 部分 / 8 未实测** | 本轮实测 74 条 |
| 9 | 备份完整性 | 原文"48 个文件"→ 实测 **54** | `docs/backups/` 递归 54 |
| 10 | 验收包文件数 | 原文"61"→ 实测 **62**（37+13+4+8） | 递归统计 |
| 11 | `metric_snapshot.version` 恒 1 | **错误**：真库 6 快照 version 1→2→3→4→5→6 | `MetricPublishRepository.java:38-41,60` → 已订正 |
| 12 | A/B 对账全部 YES | A 段 11 行中 10 YES、1 行 `—`（`full_refund_rate` 无黄金基线） | `20-reconciliation.tsv:12` → 已订正 |
| 13 | 截图完整性 | 引用的 `17-screenshots/mall-home.png` **不存在** | 实为 `17-screenshots/mall/02-mall.png` → 已订正 |
| 14 | 性能与环境档 | 无整改后实测（旧链数据） | `experiments/perf-*.json` 时间戳 2026-09-06 |
| 15 | 决策效果评价 | 无 EFFECTIVE 正样本，基线==实际快照 | `decision_evaluation` 4 行真库 |

### 7.6 本轮订正台账（代码与文档）

| 文件 | 动作 |
|---|---|
| `analytics-server/platform-common/.../metric/MetricCalculator.java` | **删除**（§24.1-4/§29.2；删除前全仓仅 2 处命中，其中 1 处是"已删除"的注释）。验证：①`mvn -o -DskipTests compile -f analytics-server/pom.xml` 7 模块 SUCCESS；②删除后全量 `mvn -o test -f analytics-server/pom.xml` → **BUILD SUCCESS，38 个测试类 / 303/303 用例 GREEN**（warehouse-pipeline 96 + ai-decision 91 + platform-app 49 + metric-analysis 38 + connection-ingestion 29；platform-common 无测试类），0 失败 0 错误 |
| `docs/acceptance/r9-…/30-final-acceptance.md` | 7 处订正：项数 18→19、截图路径、备份 48→54、映射 68→74、文件数 61→62、`version` 非恒 1、A 段 10 YES + 1 `—`；补 DOM 快照错位提示 |
| `docs/remediation-status.md:238⑧` | 订正 `metric_snapshot.version` 记录（`MAX(version)+1`） |
| `docs/demo/demo-script.md` | 重写为**两进程真架构**：平台 8091 / 商城 8090、8 阶段、`analytics_metric`+`analytics_meta`、真实接口与登录鉴权、黄金答案 2042.00；旧链性能表加 ⚠️；删除不存在的 `GoldenE2ETest` 与 8090 上的平台调用 |
| `docs/thesis-materials/thesis-outline.md` | 重写：删除不存在的 `AdsMaterializer`/`AiOutputValidator`/`GoldenE2ETest`/`1.png`；改为真实类名与 11 个作业、DIM/ADS 实产缺口、74 条映射、53/53 安全证据；标注旧链数字 |
| `docs/thesis-draft/08-实验结果与分析.md:48` | "A/B 对账全部 YES" → A 段 10 YES + `full_refund_rate` 标 `—` |

### 7.7 建议补做清单（按性价比排序）

| 优先级 | 事项 | 预估工时 | 影响 |
|---|---|---|---|
| **P0（送审前必做）** | 论文/答辩材料按 §7.5 加限定词；把"未做/不适用"写进"不足与展望" | 1–2 小时 | 直接决定**学术诚信**，避免把清单/旧链当实测 |
| P0 | 补 `docs/remediation-status.md` 与验收包的一致性（残留 `:219①` 陈旧条目） | 0.5 小时 | 内部台账可信度 |
| P1（小工时高收益） | `data_dev` 账号 + 默认口令改环境变量（§21.1/§21.3） | 1–2 小时 | 安全章可直接写实测 |
| P1 | 删除 `analytics_meta` 的 `metric_snapshot`/`metric_value` 镜像表（§24.3） | 0.5 小时 | 唯一所有者可写"已收敛" |
| P1 | 前端路由级角色拦截（§25.2）+ 表格分页/排序（§18.4） | 2–4 小时 | 前端章"权限与体验"补齐 |
| P1 | `repeat_rate`（§13.1）+ RFM 金额列（§13.4） | 2–3 小时 | 指标字典 16 项可全部落地 |
| P1 | 审计动作补齐 4 类（§21.4） | 2–3 小时 | 审计覆盖面 |
| P2 | `quality_rule_definition` + 阈值/权重版本化 + WARN 等级（§16.1/§16.3） | 1 天 | 质量章核心论据 |
| P2 | 5 项质量校验（行金额、退款≤实付、非法状态跳转、ADS 关系式、时效 P95）（§16.2/§16.4） | 1–2 天 | 质量章与交易口径 |
| P2 | 五类决策模板（§20.5）+ AI 反馈闭环（§19.6） | 1–2 天 | 决策闭环完整度 |
| P2 | 真实 LLM 接入 + A/B/C/D 消融（§19.7/§23.6） | 1 天（含 API 成本） | AI 章"真实模型"证据 |
| P3（大工时/可选） | 连接器插件体系（§10.1/§10.2/§22.1） | 2–3 天 | 架构完整性，非论文必需 |
| P3 | Flume 故障注入 6 类用例（§10.7/§23.3） | 1 天 | 容错章证据 |
| P3 | 性能与环境分档（§23.1-L2/§23.2） | 视环境 | 需 1M+ 数据与足够内存；集群档**不适用** |

---

## 8. 结论：一句话回答"指导书 2 的内容全部完成了吗"

**没有全部完成。** 精确说法是：

- **主体链路已链路完成**（132 条判定为完成）：两程序边界与契约、采集与 Landing、ODS→DWD→DWS→ADS、
  质量门与发布门、指标库唯一写入与快照状态机、10 个页面与四态、AI 安全问数与证据链、决策 12 态状态机、
  RBAC 与审计、R9 真机验收（黄金链 run 30 / 快照 `S20260901_30` / R8 53/53）。
- **42 条未做，归并为 25 组功能缺口**；**90 条只完成一半**。最硬四块见文首：连接器插件体系、决策五模板 +
  真实 LLM + 正样本、质量规则版本化 + 7 项校验 + 交易层公式、以及一批"有表无数据/有算法未接线/有接口无页面"的半成品。
- **5 条不适用**（整改前基线快照、DataX、Doris/ClickHouse/Kafka/Livy、目标目录、§24 未复核条目），
  须在论文里明确写成"本期不实现/未复核"，不能算完成。
- 引用任何"已完成"结论前，请先看 §7.5 的 15 条证据薄弱项——那里列的正是"看起来完成、实际要加限定词"的地方。

本文所有判定均可按 `file:line`、真库只读 SQL、验收包文件复跑核实；与 `docs/remediation-status.md` 冲突时**以本文为准**。
