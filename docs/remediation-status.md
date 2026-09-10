# 整改状态登记（对标《项目整改实施指导书 V1.0》）

> 依据整改书 §29 进度表与 §28 自检模板维护；每阶段仅在有验收证据后置为"完成"。
> 基线：Git tag `v0.9-protype-baseline`（2026-09-06，整改分支 `remediation/r1-boundary`）。

## 现状差距核对（2026-09-06 审计）

| 整改项 | 要求（整改书） | 现状 | 证据 |
|---|---|---|---|
| 看板只读 MetricStore | §15.3/16.2 删除 `AnalysisService.loadEvents()` | ❌ AnalysisService 仍从 `landing/events` 实时聚合 | `AnalysisService.java:96 loadEvents` |
| RFM 数据源 | §5.4 禁止直查商城业务表 | ❌ RfmService `FROM mall_order` | `RfmService.java` |
| 商城内购口径 | §11.3/12.3 商品 buy 来自订单明细 | ❌ 商品热度 buy 来自行为计数（无 buy 枚举） | spark-jobs ads/hotProduct |
| 漏斗真实数值 | §12.1/12.2 不得硬编码 order/pay | ❌/⚠ Spark 链 DWS 漏斗未完整入链 | `DwsSql` vs 链脚本 |
| runtimeProfileId | §8.4 删除硬编码 | ❌ PipelineService 5 处 `runtimeProfileId=1L` | grep |
| 身份来源 | §18.2 禁用 X-User-Id | ⚠ 已改 CurrentUserHolder 优先，残留 3 处 header | AiController/决策 |
| 三库边界 | §7 三个 database | ❌ 单库 mall_simulator | 配置 |
| analytics-server | §6 独立平台工程 | ❌ 未创建 | — |
| 采集 manifest | §9.3 | ✅ READY 清单：batchId/accept/checkpoint 全闭环（见 R3） | IngestionService+manifest |
| 采集字节偏移 | §9.2 中文断点 | ✅ FileChannel 字节偏移+CRLF+创建时间戳身份（见 R3） | LocalFileIngestor |
| 异步流水线 | §13.1 taskId | ❌ 同步执行大事件 | PipelineService |
| 质量门阻断 | §14.1 | ✅ 金额对账阻断发布 | QualityChecker+测试 |
| 只读账号 | §7.2/17.3 | ✅ mall_reader 实测写被拒 | ReaderAccountSecurityTest |
| 黄金数据 | §21 | ✅ 30 事件 9 指标对账 | GoldenE2ETest |
| 决策状态机/效果 | §18 | ✅ 12 态+基线锁定+方向取反 | DecisionServiceTest |

## 执行计划（严格 R0→R9）

- [x] **R0 冻结与基线**：文档备份（docs/backups/ 12 份）、git tag v0.9-protype-baseline、整改分支、README 状态标注（已完成）
- [x] **R1 应用和数据库边界**：三库创建（mall_business/analytics_meta/analytics_metric）+ 四账号隔离（mall_app/meta_app/metric_pub/metric_read，init-three-dbs.sql）；analytics-server 父工程 + 6 模块骨架（platform-app 可独立启动 8091）；迁移按整改书 §7.3 拆三套 Flyway 集合（business/meta/metric）；平台代码迁移 81 文件；三数据源接线（meta 主 + metricReadDataSource 只读，metric_read 实测 CREATE 被拒 1142）；边界测试（grep com.graduation.mall = 空）；互停验证（平台停止后商城 200；商城停止后平台启动 200）。验收证据：
  - platform-app 启动：`/api/v1/health`=200，`Tomcat started on port 8091`
  - Flyway：analytics_meta V1-V5 全绿，16 张平台表 + 15 条指标字典 + admin/operator/analyst 种子
  - 提交：71189d1（骨架）、b589134（代码迁移）、1eee26f（启动+迁移修复）、后续互停/接线提交
- [x] **R2 RuntimeProfile**：实体/表/Service/API；Local/HDFS LandingStorage；LocalProcess/Ssh JobSubmitter。验收证据：
  - V7 迁移生效：`runtime_profile` 表 + `spark_job_run` 表 + `pipeline_run`/`metric_snapshot` 补列（runtime_profile_version/input_batch_id/target_snapshot_id/current_stage/error_message/created_by/started_at/finished_at），local-dev 种子 DRAFT
  - `/api/v1/runtime-profiles` 冒烟：list/create/update/disable/activate/getActive 全通；ACTIVE 更新被拒 400（PARAM_INVALID）；SINGLE_NODE 缺 Hive 配置激活被拒（ACTIVATE_CHECK_FAILED，适用项未通过即阻断）；LOCAL 的 Hive 项如实 SKIPPED(不适用) 不伪装通过
  - 激活流程（§8.3）：四步测试（Landing 读写 / Hive SELECT 1 / 最小 Spark 真实 spark-submit 可执行 / MetricStore 查询）全过 → 旧 ACTIVE→DISABLED、版本 +1 → new ACTIVE（local-dev ACTIVE v2）
  - R2d：PipelineService 删除硬编码 `setRuntimeProfileId(1L)`，改用 run.getRuntimeProfileId() + runtimeProfileService.get() 取实际 profile_version；pipeline_run/metric_snapshot 同跑实际 profile_version=2、target_snapshot_id 溯源、started/finished 落库；幂等同键重跑返回原 run（1 行）
  - 硬编码清理：Connection-Ingestion 新增 RuntimeProfile/CredentialService/LandingStorage(Local+HDFS)/JobSubmitter(LocalProcess+Ssh via JSch)/SparkJobRun 实体+mapper；MapperScan 加 runtime.mapper；AdsMaterializer 物化表缺失（R7 建表）WARN 跳过不让发布回滚
  - 双进程回归：平台 8091=200、商城 8090=200；流水线 daily-full 七阶段 SUCCESS
- [x] **R3 采集**：字节偏移、accepted/quarantine/manifest、WAIT_LANDING 认 manifest。验收证据（2026-09-07 实测）：
  - V8 迁移：`file_checkpoint` 加 `id AUTO PK + runtime_profile_id + file_identity` + 复合唯一键 `uk_ckpt(runtime_profile_id, file_path, file_identity)`（三 ALTER 均 `ALGORITHM=INPLACE, LOCK=SHARED` 规避 AUTO_INCREMENT 与 LOCK=NONE 冲突）；`ingestion_batch` 加 runtime_profile_id；`pipeline_stage_run` 加 evidence 列；Flyway version 8 全绿
  - 采集字节偏移（§9.2）：LocalFileIngestor 重写为 FileChannel 逐字节扫 LF/CRLF；仅整行校验成功才推进 endOffset；尾部无 LF 残行回退留待文件增长；Windows file identity=创建时间戳，文件删除重建即新版本从头读；checkpoint 唯一键=profile+绝对路径+identity，累计 37 文件全部 runtime_profile_id=1
  - 全量采集：35 文件 402,859,259 字节 → accepted 866,680 行 / 0 隔离 / 0 错误，SUCCESS（batch2）
  - 幂等重采：断点已到尾部时再跑 recordCount=0（batch3/10 验证）
  - 增量断点恢复：向已采文件 append 3 行（中文商品名事件）→ 只采 3 条新增（batch9），既有 1000 条不重采；中文 399 行逐字零错位
  - 隔离闸门：坏 JSON（1 行）、未知 schema_version 9.9/1.1（各 1）只进 quarantine/{batchId} + quarantine_record（reason 正确），accepted 全为有效行
  - checksum：manifest checksum=CRC32(acceptedBytes) 十六进制，`bc2e1132` 与 zlib 独立重算 `0xbc2e1132` 逐字节一致
  - manifest（§9.3）：批号/status=READY/files(start/end offset)/acceptedBytes/schemaVersions/acceptedUri/checksum 全字段落盘
  - 流水线整改（WAIT_LANDING 认 manifest + LOAD_ODS 只读 accepted）：`findReadyManifest` 取最新含数据 READY 清单（空批次跳过）；WAIT_LANDING evidence 落库 `{batchId,acceptedUri,checksum,acceptedRecords,schemaVersions}`；七阶段 SUCCESS：batch8 1000 条 WAIT_LANDING 1000→ODS 1000→DWD 1000→DWS 200→ADS 16→QUALITY 4 规则→PUBLISH 16，snapshotId=3
  - 质量门按设计阻断演示：金额对账失败 → PIPELINE_QUALITY_FAILED 新指标未发布（非回调 bug，为构造数据不配对所致；配对数据即 SUCCESS）
  - 幂等：相同 Idempotency-Key 重发返回原 runId=6 不重跑
  - 提交：427427c（R3 采集整改）
- [x] **R4 ODS/DWD**：全主题 ODS、维度、行为/交易 DWD、reject 表、迟到重算。验收证据（2026-09-07 实测 spark-submit + spark-sql）：
  - 设计修正（R4 核心）：ODS 模板 SQL 改为从显式 Schema 视图读取（EventLandingSchema.structType 注册 `landing_valid` 视图），替代 `json.\`path\`` 自动推断——后者会缺失 payload 中未出现的字段（reason/completed_at）导致 FIELD_NOT_FOUND（§10.2 显式 StructType 要求）
  - OdsLoadSql 四主题模板（user/product/behavior/trade）全部 INSERT OVERWRITE PARTITION(dt,hour)，白名单+event_id/event_time 非空校验；EventOdsLoadJob 单入口：input=原始行数，accepted=通过校验，rejected=input-accepted
  - **odl 真实验证（spark-submit local[2] + 本地 warehouse）**：input=36 / output=35 / rejected=1，message `accepted=35 rejectedVersionKeys=1 topics=4` SUCCESS（§10.3：总输入=成功+隔离 36=35+1）
  - §10.3 每种事件类型≥1 黄金记录：ODS 分区（spark-sql）user 08=2/09=1，product 08=3/09=1，behavior 10=13，trade 11=4/12=4/13=3/14=2 + 迟到 20260902 h09=2
  - 幂等重跑（§10.3 第 4 条）：同参重跑 odl 输出仍 input=36/output=35，spark-sql 复查 behavior=13/trade=15 不重复
  - **dim 维度（新建 DimensionBuildJob + DimSql userSnapshot/productSnapshot）**：user 3→2（u1 事件更新覆盖 member_level=platinum）、product 4→2（同 product_id 取最新，p101 无线耳机Pro 138.00 / p102 保温杯 88.00），source_batch_id=42 落库（§11.2 维度只从 ODS 事件流，禁止直连商城 DB）
  - **tdw 交易 DWD（新建 TradeDwdJob + OrderTradeCompiler 纯状态机）**：15 交易事件 → 6 行 dwd_order_detail，迟到退款动态分区重算
  - §11.4 断言（spark-sql 核实）：
    1) event_id 重复 10 次→1 有效行：bdw input=13（含 b1×10）→ dwd_user_behavior_detail 4 行（b1/b2/b3/b4 各 1），dwd_reject_record 落 b1 DUPLICATE_EVENT
    2) 双商品订单 1 个订单展开 2 行：order 1001 → product 101/102 两行
    3) 取消不入 GMV：1002 CANCELLED final_paid_flag=0
    4) 全额退款 GMV 保留支付额、净销售 0：1003 REFUNDED paid=150.00 refund=150.00 net=0.00 final_paid=1 final_refunded=1
    5) 迟到退款重算原业务日：1005 退款事件 event_time=2026-09-02 迟到，但 dwd_order_detail dt=20260901（订单创建日），REFUNDED net=0
    6) 部分退款：1004 REFUNDING paid=100 refund=30 net=70
  - 维度 LEFT JOIN：dwd_order_detail 关联 dim_product(dim_product_id)/dim_user(city_level)（COALESCE 兜底 -1/unknown）
  - **R4h 流水线 evidence（§10.3 最后一条：流水线详情能看输入/输出/隔离数）**：LOAD_ODS evidence 落 `{odsInputRecords,odsAcceptedRecords,odsRejectedRecords,odsQuarantinedRecords}`；BUILD_DWD evidence 落 `{dwdInputRecords,behaviorEvents,behaviorUnique,duplicateRejected,contractBad}`。实测 run=11：LOAD_ODS input=3/accepted=3/rejected=0；BUILD_DWD behaviorEvents=2/behaviorUnique=1/duplicateRejected=1（2 重复→1 有效）records=2（Java 侧口径与 Spark bdw 一致）
  - 单测 35/35 绿（SqlTemplateSpec 含 OdsLoadSql 5 新签名 / OrderTradeCompilerSpec 7 / JobArgsRegistrySpec 8 作业+依赖 / 等）；JobRegistry 8 作业：odl/bdw/dim/tdw/usw/fna/ljp/sci，依赖 dim→[odl]、tdw→[odl,dim]、bdw→[odl]
  - ODS 商品表扩列（payload_category_name/payload_parent_category_id/payload_parent_category_name）同步三处：模板 OdsLoadSql / 本地 LocalSchemaInitJob / 生产 warehouse/ddl/00-ods.sql
  - 验证 warehouse 迁至 `.verify/r4-wh`（spark-jobs/target 会被 mvn clean 清除）；黄金数据 `.verify/r4-landing/events.jsonl`（36 事件，gitignore）
  - 提交：本期提交（feat: ODS/DWD 全主题 + dim/tdw 作业 + pipeline evidence）
- [x] **R5 DWS/ADS**：真实调用全部核心 DWS、修复漏斗/热度、八张核心 ADS、层间对账。验收证据（2026-09-07 全新 `.verify/r5-wh` 全链重放 spark-submit + spark-sql 比对黄金）：
  - R5a DwsSql 重写 7 模板（userBehaviorDay 加 buy=订单明细 SUM(quantity)/funnelDay 订单源 order/pay 用户替代硬编码 0/productBehaviorDay buy 改订单源/tradeDay 有效支付口径+avg 分母 0→NULL/新增 productSaleDay/userTradePeriod/regionSaleDay）
  - R5b AdsSql 重写 8 模板（funnel 4 stage 收编内联 UNION/productConversion buy_users 取 dws_product_sale_day.buyer_count/新增 userProfile RFM 八类+lifecycle/dataQuality 4 规则与 QualityChecker 同名同阈值）
  - R5c 生产 DDL 对齐：LocalSchemaInitJob 补 dws_user_behavior_day.buy BIGINT + 新增 ads_user_profile/ads_data_quality；warehouse/ddl/04-ads.sql 删除 ads_behavior_funnel/ads_active_trend 普通 dt 列（与分区列冲突）
  - R5d 作业扩能：UserProductDwsJob 依次产出 7 张 DWS、FunnelAdsJob 依次产出 8 张 ADS、JobRegistry usw→[bdw,tdw]
  - R5e 单测：SqlTemplateSpec 补 6 项新断言、JobArgsRegistrySpec 同步 usw 依赖，41/41 绿
  - R5f 真实验证（.verify/r5-wh 全新重放，36 事件→35 接受/1 隔离；行为 4 行/拒重 1；订单 6 行）：
    - dws_user_behavior_day：u1(1,1,0,0,buy5,1h) u2(0,0,1,1,buy1,1h) ✓
    - dws_behavior_funnel_day：view1/intent2/order2/pay2，intent_rate 2.0000/order 1.0000/pay 1.0000（order/pay 来自订单明细，无硬编码 0）✓
    - dws_product_behavior_day：101(1,1,1,0,buy3) 102(0,0,0,1,buy3) ✓
    - dws_trade_day：order4/buyer2/sale586/refund260/net326/avg146.50 ✓
    - dws_product_sale_day：101(3,388,2) 102(3,198,1)；dws_user_trade_period：u1(3单486) u2(1单100)；dws_region_sale_day：tier1(1,3,486) tier2(1,1,100) ✓
    - ads_operation_overview：pv4/uv1/dau2/order4/sale586/net326/avg146.50/refund_rate0.5000；ads_sale_trend：4/2/586/146.50（与 dws_trade_day 对账一致）✓
    - ads_behavior_funnel：view(1,NULL)/intent(2,2.0)/order(2,1.0)/pay(2,1.0) ✓
    - ads_hot_product：top2={101 无线耳机Pro, 102 保温杯} heat 9.0109 并列（名称非"商品-{id}"）✓
    - ads_product_conversion：101 pv1/buy2/conv2.0；102 pv0/buy1/conv NULL ✓
    - ads_user_profile：2 用户 r/f/m 五分位 + 八类标签 + lifecycle + rule_version=rfm-v1 ✓
    - ads_data_quality：AMOUNT_RECONCILE(4,0)✓/REQUIRED_FIELD_NULL_RATE(4,0)✓/EVENT_ID_UNIQUE(4,1)真实拒重/ENUM_WHITELIST(4,0)✓
  - 修复：userProfile 观察期 yyyyMMdd→yyyy-MM-dd 转换（DATEDIFF 混比失败）、F/M 正向五分位排序方向（ASC）、favorite_category 子查询去聚合（rn=1 直接投影）
  - 提交：R5 特征提交（spark-jobs 7 DWS + 8 ADS + DDL 对齐 + 单测）
- [ ] **R6 流水线**：异步 taskId、JobSubmitter、externalJobId、分阶段恢复、幂等
  - 验证策略（R6 提速）：四级测试体系固化为 `docs/r6-verification-strategy.md` —— L0 快速测试（10-30s 无 Spark）/ L1 模块集成（20-100 条）/ L2 真实小链（50-500 条有 Spark 3-10min）/ L3 完整性能（≥10 万，推迟 R9）；缩小数据量不缩业务场景；跨层契约变化才跑完整小链
  - R6-1 JobResultParser（新，`pipeline/spark/JobResultParser.java`）：逐行只认含 jobCode+status 的 JSON 行取最后一条；exitCode≠0→FAILED 带 "exit=N"；无结果行+exit0→FAILED「未找到 JobResult 结果行」。8 测试 GREEN（0.03s）
  - R6-2 JobCommandBuilder（新，`pipeline/spark/JobCommandBuilder.java`）：cmd 顺序 submitPath→--master→（非 LOCAL 才 deploy-mode/queue）→--conf 每对占两元素且在 --class 前→--class JobRunner→jar→--runtimeProfileId/--jobCode/--businessDate/--attemptNo→可选 inputVersion/outputSnapshotId→extra；jar 无 scheme 时 LOCAL 绝对化 file:///。7 测试 GREEN（0.02s）
  - R6-3 FakeJobSubmitter（test 替身，可编程 externalJobId/status/logs/healthCheck/cancel + 命令记录）+ 5 自测 GREEN（0.01s）
  - R6-4 PipelineService 可测化重构（编译通过）：注入 `@Qualifier("pipelineExecutor")` Executor；run()/retry() 异步（insert PENDING→executor.execute→立即返回 PENDING taskId，§13.1）；幂等锁 ConcurrentHashMap+二次检查+DuplicateKeyException 兜底（§13.4 并发同键单任务）；数据准备提升到 execute() 顶部幂等重读；QUALITY_CHECK 移入 stage action；completedStages 跳过（重试成功阶段不重复写记录）；`PlatformBeans` 新增 pipelineExecutor bean（core2/max4/queue64）
  - R6-5 PipelineServiceTest（8 断言 GREEN，2.2s 无 Spark/DB）：①异步首提立即返 taskId+PENDING 不阻塞 ②同幂等键返原任务且不新增 updateById ③WAIT_LANDING→LOAD_ODS 七阶段顺序 SUCCESS ④阶段失败→FAILED（RUN_EMPTY_DATA）不发布 ⑤重试只重跑失败阶段（WAIT_LANDING 1 次/LOAD_ODS 2 次，attemptNo=2，§13.4）⑥质量失败→PIPELINE_QUALITY_FAILED 无 PUBLISH/快照/物化 ⑦attemptNo 递增 ⑧并发同键双线程仅 1 次 insert 同 runId
  - 快速测试合集 28/28 GREEN：`.verify/r6-fast-tests-green.log`（JobResultParser 8 + JobCommandBuilder 7 + FakeJobSubmitter 5 + PipelineService 8，2.3s）
  - R6-6 SparkStageExecutor（新，`pipeline/spark/SparkStageExecutor.java`）：阶段→作业序列映射（LOAD_ODS→odl；BUILD_DWD→bdw,dim,tdw；BUILD_DWS→usw；BUILD_ADS→fna；与 spark-jobs JobRegistry 依赖对齐）；JobCommandBuilder 构建命令→JobSubmitter 提交→externalJobId 非空+argumentsJson 快照落 spark_job_run（§13.3/§21.10 溯源）→轮询日志 JobResultParser 解析；判定：CANCELLED→失败、进程态 FAILED（退出码非0）覆盖日志声称 SUCCESS（§13.2 阶段不能自证）、超时无结果行→失败；修 LocalProcessSparkSubmitter 日志文件名 bug（写 {logPrefix}-{jobId}.log 读 {externalJobId}.log 不一致 → 统一 {externalJobId}.log，§13.3 日志可溯源）。8 测试 GREEN（0.02s）
  - 快速测试合集 36/36 GREEN：`.verify/r6-fast-tests-green.log`（+SparkStageExecutor 8，6.7s）
  - R6-7 真实 Spark 冒烟（L2，golden 30 条，`.verify/r6-7b-sci-odl-pwsh-smoke.log` + `.verify/r6-7c-real-spark-smoke-logs/`）：
    - 7a `LocalProcessSparkSubmitterLogTest`（3 测试 GREEN）：真实 `cmd /c echo` 提交→logs() 按 {externalJobId}.log 读回同文件、日志文件按 externalJobId 命名（轮询等文件出现，修复异步守护线程竞态）、缺失文件返回可读消息
    - 7b PowerShell 真实 spark 冒烟：sci SUCCESS（output=29 表，elapsedMs=5402）+ odl SUCCESS（input=30→output=21→rejected=9，elapsedMs=10625，accepted=21 topics=4），独立 warehouse 隔离
    - 7c `SparkStageExecutorSmokeTest`（1 测试 GREEN，26.9s 真实 spark）：真实 LocalProcessSparkSubmitter 启动 spark-submit→sci 建表→executor.executeStage(LOAD_ODS, confs) 真跑 odl；断言 externalJobId `lp-` 前缀非空、JobResult 30/21/9、spark_job_run insert(SUBMITTED)+updateById(SUCCESS) 落库、argumentsJson 含 --jobCode=odl/--businessDate=20260901、warehouse 出现 part-* parquet 分区（写出目标分区 §四）
    - **调试根因**：仓库固定目录 warehouse 为空但 odl SUCCESS——嵌入式 Derby 元数据库（javax.jdo）持久化在 spark-submit CWD 的 `metastore_db/`，首次 sci 建表把表位置绑定到首个 warehouse URI，之后幂等 skip 重建→odl 写旧位置。修复：confs 注入 `spark.hadoop.javax.jdo.option.ConnectionURL=jdbc:derby:<固定路径>;create=true` 隔离元数据库 + 整目录每次重建 + sci 后 2s 等 Derby 锁释放；冒烟目录固定 `tests/r6-smoke-warehouse/`（非 @TempDir，证据可事后查验）
  - SparkStageExecutor 新增 confs 重载（executeStage/executeJob 透传 → JobCommandBuilder --conf）
  - R6-8a **跨层契约对齐（Bug 修复，golden 30 条 rejected 9→0）**：`OdsLoadSql.eventTypeToTable` 原用旧命名（user_created/user_updated/product_status_changed/inventory_changed/refund_requested）与权威契约 12 类（docs/contracts/event-contract.md §3 + mall-simulator EventContract.java）不符 → mall-simulator/golden 的 `user_registered×3 + refund_created×1 + stock_reserved×4 + stock_released×1 = 9 条`合法事件被误拒（7b/7c rejected=9 实为契约漂移）。修复：eventTypeToTable 回归契约 12 类映射（user_registered→ods_user_event；product_created/product_updated/stock_*→ods_product_event；behavior→ods_behavior_event；order_*/refund_*→ods_trade_event），user/product/trade 三处 FromLanding WHERE 同步契约命名；`DimSql.productSnapshot` 只取建档事件（product_created/product_updated）生成商品快照，防库存事件（无商品名称/价格字段）污染维度为 UNKNOWN（原"最近事件"语义在新 12 类全量入库后会让最新 stock_changed 覆盖商品信息——修复增加 event_type 白名单）；OrderTradeCompiler 注释与测试 `refund_requested→refund_created` 契约命名统一
  - R6-8a 验证：spark-jobs 单测 41/41 GREEN（含 SqlTemplateSpec 断言同步契约命名 + productSnapshot 白名单断言）；真实 spark 冒烟 `.verify/r6-8a-contract-smoke.log`：sci SUCCESS（output=29）+ odl SUCCESS（`accepted=30 rejectedVersionKeys=0` **rejected=0**，此前误拒 9 条已全收，input=30→output=30），ods_user_event/ods_product_event 分区写出 ✓
  - **R6-8b 黄金数据扩充（30→55 行）与全链真实对账（2026-09-08 实测）**：golden 由 30 行扩至 55 行（`tests/golden-dataset/events/golden-20260901.jsonl`，新增 evt-031..055），原 30 条原样保留；新增场景：用户更新（u1 二次 user_registered 12:00 platinum，方案 a：同 user_id 第二条注册更晚时间）、商品更新（p1 product_updated 279.00）、cart_remove、重复 event_id（037 复用 golden-evt-008 且时间更晚 → DWD rn=1 去重保留 008、037 写 DUPLICATE_EVENT 拒绝）、非法枚举（038 behavior purchase → DWD 白名单过滤不入明细）、全额退款单（O1004 REFUNDED final_refunded=1）、迟到退款（O1005 refund_completed 09-02T00:30 → ODS dt=20260902 分区，TradeDwdJob 全分区读重算归 orderDate 20260901）、脏数据 3 行（053 非 JSON 计入 input 被 rejected / 054 schema_version=2.0 / 055 缺 event_id）
    - **odl 实测**（`.verify/r6-8b-odl-probe.ps1`）：`input=55 output=52 rejected=3 message="accepted=52 rejectedVersionKeys=3 topics=4"`，四主题分区全部写出（PERMISSIVE 语义实测确认：非 JSON 行计入 input 且被 rejected，非丢弃）
    - **标准答案重算**（`tests/golden-dataset/expected/golden-20260901-expected.json`，dataset=golden-20260901-r6-8b）：gmv=2042.00 / net_sale=1493.00（refund=549.00：50+399+100）/ avg=408.40 / refund_rate=0.60（字典任意退款口径）/ buy_rate=1.0000 / repeat_rate=0.3333（u3 两单）；pv=7 / uv=3 / dau=3 / fav=2 / cart_add=3；行为明细 14 行（16 - 037 去重 - 038 过滤）
    - **全链真实冒烟**（`.verify/r6-8b-full-chain-smoke.ps1`）：sci→odl→bdw→dim→tdw→usw→fna 七作业全 SUCCESS；bdw input=16→output=14 rejected=1（DUPLICATE_EVENT）；dim user=4→3 product=14→4；tdw 18→7（1001 双品展开 2 行）；usw 14→3；fna 1→4；ods_trade_event/dt=20260902 分区确认写出（迟到退款）
    - **DWS/ADS 内容对账**（spark-sql 实测）：dws_trade_day order=5 buyer=3 sale=2042.00 refund=549.00 net=1493.00 avg=408.40 ✓；dwd_order_detail 1002 fp=0、1004 REFUNDED refund=399、1005 REFUNDING refund=100（迟到归位 09-01）、1006 PAID ✓；behavior view=7/cart_add=3/cart_remove=1/favorite=2/search=1=14 行 ✓；reject DUPLICATE_EVENT=1 ✓；ads_data_quality 4 规则（AMOUNT_RECONCILE/ENUM_WHITELIST/REQUIRED_FIELD_NULL_RATE passed=1，EVENT_ID_UNIQUE error=1 passed=0 **只记不阻断**）✓；dim_user u1=platinum（用户更新生效）✓；dim_product p1=279.00（商品更新生效）✓；ads_behavior_funnel view=3/intent=2/order=3/pay=3 ✓；dws_user_trade_period u1=2单/1496.00 u2=1/279.00 u3=2/267.00 ✓；ads_hot_product p3(11.09)>p2(10.40)>p1(10.06) ✓；ads_product_conversion p1=2买/2pv p2=1/3 p3=1/1 p4=0/1 ✓；ads_active_trend dau=3 behavior=14 ✓；ads_sale_trend 5/3/2042.00/408.40 ✓；dws_product_sale_day p1=2件/578 p2=3件/1197 p3=3件/267 buyer 2/1/1 ✓；dws_region_sale_day tier1=1496 tier2=279 tier3=267 ✓
    - **口径登记（预存在分歧，不修正任一方）**：① refund_rate 字典=任意退款订单 3/5=0.60 vs ads_operation_overview final_refunded=1（仅 O1004）=0.20（实测 0.2000）；② pv 字典=view 数=7 vs ads_operation_overview 全行为明细行数=14（实测 14）；均写入 expected notes
    - **冒烟脚本 bug 修复**：`$argsList += $extra` 把 `--periodStart=... --periodEnd=...` 整串当一个参数 → JobArgs 解析后 periodStart 含空格、userTradePeriod WHERE 无匹配 → dws_user_trade_period 空分区 0 行；修复为 `($extra -split ' ')` 逐一入参数组后重跑 usw/fna，user_trade_period 恢复 3 行 ✓
    - SparkStageExecutorSmokeTest 硬编码同步 30→55（inputRecords/头注释 4 处）；spark-jobs 单测回归 41/41 GREEN（Scala 未改，jar 未重打）
    - 提交：R6-8b commit（见下）
  - **R6-9 统一事件契约（V2.0 §15.3）**：`EventContract` 升级为"12 类契约 + ODS 主题路由"唯一 Java 侧来源（`ODS_TABLE_BY_TYPE` / `EVENT_TYPES` / `isKnownType` / `odsTable` / `UNKNOWN_EVENT_TYPE`）；`PipelineService` 删除私有旧白名单（原 `user_created/user_updated/product_status_changed/inventory_changed/refund_requested` 等旧命名，R6-8a 漂移根因）改用 `EventContract`；LOAD_ODS 证据新增 `unknownEventType` 计数（未知类型显式拒绝、不悄悄跳过）。验证：`EventContractTest` 28 GREEN（12 类全集/主题语义/**跨语言契约锁**——直接比对 scala `OdsLoadSql.eventTypeToTable` 键集取值逐项一致/12 类过统一校验器/未知类型显式拒绝）
  - **R6-10 提交器工厂（V2.0 §15.3）**：新增 `RuntimeProfileSnapshot`（不可变档案快照，冻结 type/master/ssh/jar/凭据引用）+ `JobSubmitterFactory`（LOCAL/SINGLE_NODE→`LocalProcessSparkSubmitter`，REMOTE_CLUSTER→`SshSparkSubmitter`；缺 `spark_submit_path`/`ssh_host`/`ssh_user` fail-fast，不再猜默认路径 §8.4；工厂只管构造，不碰 DAG/落库）；`LocalProcessSparkSubmitter` 日志名升级为 `{runId}-{stage}-{jobCode}-a{attempt}__{externalJobId}.log`（R6-12 可检索），`logs(jobId)` 按 `*__{jobId}.log` 后缀兜底仍可溯源。验证：`JobSubmitterFactoryTest` 8 GREEN + `LocalProcessSparkSubmitterLogTest` 4 GREEN（真实子进程）
  - R6-9/R6-10 回归：warehouse-pipeline 快速套件 **76/76 GREEN**（`mvn -pl warehouse-pipeline -am test -Dtest=!SparkStageExecutorSmokeTest`）
  - **R6-11 生产编排接线（V2.0 §15.3，本次最大改造）**：`PipelineService` 收敛为**只做状态机与阶段编排**——
    - 四个计算阶段（LOAD_ODS/BUILD_DWD/BUILD_DWS/BUILD_ADS）全部改由 `SparkStageExecutor` 提交**真实 spark-jobs**（odl；bdw+dim+tdw；usw；fna），阶段职责/参数：odl `--landingDir`（accepted 目录 URI）+`--batchId`，usw/fna `--periodStart/--periodEnd`=业务日、fna `--topN=50`
    - **删除本地假计算路径**：`MetricCalculator`/`MetricDataset`/`metricStore`/`snapshotMapper`/`valueMapper`/`adsMaterializer` 全部移出生产编排，`source=local-calculator` 快照不再产生（§15.4："生产服务不再引用 MetricCalculator 或读取 Landing JSON 算 ADS"）；Java 侧仅保留两处非计算职责：WAIT_LANDING 的 READY manifest 门（§9.3）与 QUALITY_CHECK 质量门（§5.4.1）
    - **阶段内 fail-fast**：`SparkStageExecutor.executeStage` 任一作业失败即停止本阶段剩余作业（实测日志 `stage BUILD_DWD fail-fast: job dim 失败（…），不再提交剩余作业 [tdw]`）；失败证据先落库再阻断（`StageOutcome.deferredFailure`），run 置 FAILED、依赖阶段保持未执行
    - **真实输出证据（R6-12 前半）**：阶段 `records` = 该阶段各作业 `outputRecords` 之和（只取 JobResult，禁止常量/输入数冒充）；阶段 evidence 逐作业记录 `jobCode/externalJobId/status/inputRecords/outputRecords/rejectedRecords/logUri`；`SparkJobRun.logUri` 落真实日志路径
    - 提交路径统一到不可变 `RuntimeProfileSnapshot`（`JobCommandBuilder`/`SparkStageExecutor` 同步改造）；新增 `SparkStageExecutorFactory`（快照→提交器→执行器），Bean 注册于 `PlatformBeans`，超时/轮询可配（`platform.spark.job-timeout-ms`/`poll-interval-ms`）
    - PUBLISH_METRIC 语义收敛为"登记本次 ADS 快照"：记录 `adsSnapshotId` + ADS 作业证据 + `mysqlPublish: deferred-to-R7（Hive→MySQL staging→ACTIVE 原子切换）`；缺 ADS 证据时**拒绝登记**（RUN_PUBLISH_NO_ADS），不再假装发布
    - 验证：warehouse-pipeline 快速套件 **80/80 GREEN**（新增 ⑨fail-fast：BUILD_DWD 失败→BUILD_DWS/BUILD_ADS/PUBLISH 无阶段记录、executeStage 仅调用 2 次；⑩计数取 JobResult：BUILD_DWD records=8×3）；`analytics-server` 七模块 `mvn -DskipTests package` 全 SUCCESS
    - **边界如实登记**：ADS 目前仍由 fna 直接写正式分区，staging/原子发布属 R6-13；Hive→MySQL 发布属 R7（R6 期间 `metric_snapshot`/`metric_value` 无新数据，看板数据源将在 R7 恢复为真实 ADS）
  - **R6-12/R6-15 生产链路真实验收（进行中，2026-09-10 实测，run 12）**：按正规流程 `disable → update → activate`（档案 local-dev 置 `spark_submit_path`/`spark_job_jar_uri`/`spark_master=local[2]`，§8.4 路径来自档案而非硬编码；激活检查含真实 `spark-submit --version` 与 metric 查询，v2→v3 ACTIVE），经 `POST /api/v1/ingestion/runs` 采集 55 行黄金数据 → `POST /api/v1/pipeline-runs` 提交生产流水线。**已取得真实生产证据**：
    - `WAIT_LANDING` SUCCESS（manifest batch 18：accepted=51 / quarantined=4）
    - `LOAD_ODS` SUCCESS：`spark_job_run` row1 jobCode=odl externalJobId=`lp-1789030422172-1d2b14` **input=51 output=51**，log_uri=`landing/logs/pipeline-12-LOAD_ODS-odl-a1__lp-….log`（真实日志文件名含 run/stage/job/attempt，R6-12 生效）
    - `BUILD_DWD` bdw SUCCESS：input=15 output=14（行为 ODS 15 行 → DWD 14 行 + 1 重复拒绝，与 R6-8b 黄金口径一致）
    - `dim` FAILED：`INSERT_COLUMN_ARITY_MISMATCH` — `dw_dim.dim_user` 表 6 列（旧 DDL 建）vs 当前 SQL 7 列（含 `source_batch_id`）
    - **缺陷①（环境/历史遗留，非代码）**：LOCAL 生产路径此前未显式指定仓位置，Spark 用全局默认 `D:\Develop\tmp\spark-warehouse` + CWD 下 `metastore_db`；该 warehouse 由**旧版 DDL** 建表，而 `sci` 是 `CREATE TABLE IF NOT EXISTS`（不演进既有表）→ 表结构漂移使 dim 写入失败。修复方向：R6-11 已加 `SparkStageExecutorFactory.confsFor()` 把 warehouse/metastore 钉到显式配置（`platform.spark.warehouse-dir` / `metastore-dir`，默认 `./spark-warehouse` / `./derby-metastore`），下次运行即用全新仓位置建当前 schema；**遗留改进**：`sci` 应做幂等 schema 对账（`ALTER TABLE … ADD COLUMNS` 补齐缺失列），避免既有环境再次漂移
    - **缺陷②（本次新代码缺陷，已修）**：`pipeline_stage_run.evidence` 为 `VARCHAR(500)`，R6-11 起证据含逐作业明细（3 作业 JSON >500 字节）→ `MysqlDataTruncation` → 阶段落库失败并把 run 置 `RUN_INTERNAL`（run 12 实测）。修复：迁移 `V9__platform_stage_evidence_widen.sql` 加宽至 4000 + 代码侧 `evidenceJson()` 截断兜底（保留头部并标注截断长度）
    - **缺陷③（黄金数据夹具，已修）**：`tests/golden-dataset/events/golden-20260901.jsonl` 末行无换行符 → 采集器按 Taildir 语义有意不消费尾部残行（§9.2 防止半行入库）→ 第 55 行（缺 event_id 的 055）既未 accepted 也未 quarantined，51+3=54≠55 违反 §16.4 对账公式。**这是夹具问题而非代码缺陷**（JSONL 记录应以换行结尾）：补末尾 LF 后重采（batch 18）= **51 accepted + 4 quarantined = 55** 对账成立
    - 本阶段验证：warehouse-pipeline 快速套件 **80/80 GREEN**；`analytics-server` 七模块 `-DskipTests package` BUILD SUCCESS
  - **R6-15 生产链路端到端验收完成（2026-09-10 实测，run 13，全阶段 SUCCESS）**：新构建（INIT_SCHEMA 自举 + `confsFor()` 钉 warehouse/metastore）启动平台 → `POST /api/v1/pipeline-runs` → 八阶段全绿：
    | 阶段 | 作业 | 真实计数（spark_job_run） |
    |---|---|---|
    | INIT_SCHEMA | sci | output=29 表（新仓 `./spark-warehouse` + `./derby-metastore` 按当前 DDL 建表） |
    | LOAD_ODS | odl | input=51 output=51 |
    | BUILD_DWD | bdw / dim / tdw | 15→14(+1 拒重) / 18→7 / 18→7 |
    | BUILD_DWS | usw | 14→3 |
    | BUILD_ADS | fna | 1→4 |
    | QUALITY_CHECK | —（本地门） | 4 规则，`corePassed=true`（AMOUNT_RECONCILE check=5 error=0；EVENT_ID_UNIQUE error=1 passed=0 只记不阻断） |
    | PUBLISH_METRIC | —（快照登记） | `adsSnapshotId=S20260901_13` + ADS 作业证据；`mysqlPublish=deferred-to-R7` |
    - **Hive 内容与黄金基准逐项一致**（spark-sql 实测）：`dws_trade_day` order=5 buyer=3 sale=2042.00 refund=549.00 net=1493.00 avg=408.40；`ads_operation_overview` pv=14 uv=3 dau=3 order=5 sale=2042.00 net=1493.00 refund_rate=0.2000；`ads_data_quality` 4 规则同上；`dwd_order_detail` 7 行（6 行 fp=1）；`dws_user_trade_period` u1=2/1496.00 u2=1/279.00 u3=2/267.00（复购 1/3）
    - `pipeline_run` 13：status=SUCCESS、current_stage=SUCCESS、target_snapshot_id=S20260901_13、runtime_profile_version=3、error_code=NULL
    - **R6 完成条件对照（§15.4）**：①Controller 返回 taskId 且阶段变化可查 ✓ ②每个 Spark 阶段有真实 `spark_job_run`+externalJobId ✓ ③生产服务不再引用 MetricCalculator/不算本地 ADS ✓ ④Hive 正式分区证据 ✓、**staging 证据 ✗（待 R6-13）** ⑤质量失败阻断发布 ✓（跑通路径）、重试只重跑失败阶段 ✓（R6-11 实现，**重启恢复 ✗ 待 R6-14**）
- [ ] **R7 指标与看板**：Hive→MySQL staging→原子切换、看板只读 MetricStore、页面/AI 数值一致
- [ ] **R8 AI/安全/决策**：EvidencePackage、AST 全字段校验+EXPLAIN、最小权限、身份、DRAFT 创建
- [ ] **R9 完整验收与论文证据**：端到端黄金链、恢复/安全实验、README/验收/论文一致性

## 验收纪律（整改书 §23/26）

新最终验收至少含：双进程双库、READY manifest、taskId、非空 externalJobId、Hive 四主题有数、
层间对账、Hive ADS=MySQL ACTIVE=页面=AI 一致、质量失败保旧快照、决策越权 403、
AI 查商城表被 DB 拒绝、停模型后看板可用。Mock 数据不得写成真实实验。

## UI 美化（ui-ux-pro-max 设计系统）

- 风格：Enterprise Gateway / Data-Dense Dashboard（Navy #1E40AF + 琥珀 #D97706 + Fira Sans/Code）
- 应用范围：全局令牌 → App 壳（侧栏/顶栏/卡片/表格）→ 各分析页（大盘先行，逐页铺开）
- 验收：npm build + headless 截图对比、可访问性（对比度 4.5:1、焦点可见、reduced-motion）