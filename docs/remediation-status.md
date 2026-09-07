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
  - 提交：待 R3 提交哈希
- [ ] **R4 ODS/DWD**：全主题 ODS、维度、行为/交易 DWD、reject 表、迟到重算
- [ ] **R5 DWS/ADS**：真实调用全部核心 DWS、修复漏斗/热度、八张核心 ADS、层间对账
- [ ] **R6 流水线**：异步 taskId、JobSubmitter、externalJobId、分阶段恢复、幂等
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