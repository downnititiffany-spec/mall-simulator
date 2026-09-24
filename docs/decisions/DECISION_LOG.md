# Decision Log

> 本文件记录当前正式执行决策。普通实现决策追加在此；重大、长期、跨模块或难以回滚的决策另建 ADR，并在本表索引。
> V3.0 指导书与设计文档保持冻结；本日志不得静默覆盖其正式范围、架构或契约语义。

## 2026-09-16

### D-001 — GitHub 远端唯一写入者

**Decision**：毕业设计后续开发中，GitHub 远端写入只由当前 ChatGPT 执行。Code Agent、Codex Work、Independent Reviewer 均只读远端仓库。

**Reason**：避免多执行者同时 push、相互覆盖、测试对象漂移，并确保设计与实现修改有单一责任人。

**Operational consequence**：验证方必须针对精确 commit SHA 测试；发现问题只返回证据，不直接修远端代码。

### D-002 — 所有项目决策必须落盘

**Decision**：任何正式项目决策都必须进入 GitHub。聊天上下文只作为工作缓存，不构成正式决策源。

**Storage rule**：普通实现决策进入本 Decision Log；重大决策进入 `docs/decisions/ADR-*.md`；当前事实进入 `docs/PROJECT_STATUS.md`。

**Reason**：降低长上下文压缩、换会话、记忆偏差造成的实现漂移。

### D-003 — 设计实现与独立验证分离

**Decision**：ChatGPT 负责 Architect + Implementer；Code Agent 负责 Execution Tester；Codex Work 负责 Adversarial Verifier。

**Boundary**：ChatGPT 写生产代码与 developer tests；Code Agent 主要跑既有测试和本地环境；Codex Work 主动设计反例、故障注入和临时探针。两类验证者均不得写远端 Git。

**ADR**：见 `ADR-0001-development-verification-separation.md`。

### D-004 — 重大模块启用 Independent Reviewer

**Decision**：遇到高风险或长期影响模块时，增加新的独立 Reviewer Chat。由 ChatGPT 主动识别触发点并提供完整 Reviewer Prompt。

**Typical triggers**：重大 Flyway/数据模型、鉴权/安全、核心 Spark 指标语义、AI SQL/LLM 安全、状态机/幂等/恢复、跨模块正式契约、发布回滚、跨多个核心模块的高风险变更。

### D-005 — 指导书后续转为验收规范型

**Decision**：V3.0 不原地重写。下一正式版本 V3.1 起，指导书主要描述阶段目标、完成定义、不变量、测试矩阵、边界、故障注入、证据要求与通过标准，不再承担具体代码实现说明书职责。

**Reason**：设计与实现已由 ChatGPT 直接承担，测试方需要独立验收规范而不是照抄实现步骤。

### D-006 — 当前聊天的模块级连续开发停止条件

**Decision**：普通实现中，不因子任务、单个 commit 或普通 backlog 完成而停。当前模块持续实现到代码面完整并需要 Code Agent/Codex Work 独立测试时才形成 Test Handoff；HARD DECISION 或工具/依赖阻塞除外。

**Reason**：避免原先“一小段实现 → 汇报 → 等待下一任务”的人工派工节奏。

### D-007 — 当前开发分支

**Decision**：继续以 `feature/v3-development` 作为当前远端开发主线；仅 ChatGPT 对该分支写入。`main` 不自动合并，必须由用户明确批准。

**Current baseline when recorded**：`d3a7a0b13d4ec24801230293df5b115a413c07cf`（S3-52 后）。

### D-008 — S3-53 AI evidenceId 形状兼容

**Decision**：`buildAiEvidenceContext` 对证据包 ID 采用“顶层优先、嵌套回退”的只读归一化：先读取 `/ai/queries` 当前响应顶层 `evidenceId`；若顶层缺失或为占位值，再读取 `explanation.evidence.evidenceId`。两处都无真实值时返回 `null`，不得生成 ID。

**Reason**：`EvidencePackage` 本身拥有 `evidenceId` 字段，而当前前端只搬运顶层 `evidenceId`；这会使嵌套 EvidencePackage 已给出真实 ID 时，页面仍显示“未提供”，且 AI 建议转决策草稿无法使用已有 evidence package 锚点。该改动只做响应形状兼容，不改变后端值语义、决策契约或锚点优先级。

**Invariants**：

- 顶层真实 `evidenceId` 仍优先，保持现有 `/ai/queries` 行为；
- `unknown` / `UNKNOWN` / 空白仍不是合法 evidenceId；
- 真实 evidence package ID 优先于 snapshot 锚点；
- 决策草稿请求中 evidence package 与 snapshot 锚点仍二选一；
- 前端不得构造、改写或猜测 evidenceId。

**Implementation commits**：`0054870`（生产逻辑）+ `0ebd0e8`（developer tests）。

### D-009 — 独立测试改为批量延迟验证

**Decision**：完成一个小模块后，不再默认暂停开发等待 Code Agent / Codex Work 的测试结果。每个工作项先把实现、developer tests、关键不变量和后续验证要求登记到统一 `docs/verification/DEFERRED_TEST_PLAN.md`，然后继续实现其它并列或仅依赖稳定接口的工作项。

**Stop rule**：只有当后续工作直接依赖尚未验证的运行行为/数据库形状/协议形状/性能边界，或当前改动属于会向多模块扩散错误的共享基础设施、高风险状态/权限/安全/迁移边界时，才暂停受影响链路先取测试结果；其它并列模块继续。

**Batch point**：功能簇完成、阶段收口、进入真实 DB/Spark/Hive/E2E、合并 main 前，或延迟验证队列过长到显著增加定位成本时，集中跑一批。

**Reason**：把本地测试作为独立验证队列，而不是把每个小实现变成人工停工点；同时保留精确 commit SHA 和独立测试计划，避免测试延期后无法定位回归。

### D-010 — Git commit 主题显式携带精确时间

**Decision**：从本决策起，由 ChatGPT 创建的 GitHub commit message 必须在主题中显式带项目时间戳，统一格式为 `[YYYY-MM-DD HH:mm:ss +08:00]`。

**Reason**：GitHub 列表页经常只显示“几分钟前/几天前”的相对时间；虽然 commit 元数据本身保存精确 author/committer 时间，但把时间写进主题后，用户无需点进详情即可按分钟/秒核对开发顺序。

**Identity**：通过当前 GitHub 连接写入仓库时，GitHub 使用连接账户的身份记录提交；当前实测提交的 author/committer 均显示 `downnititiffany-spec`。ChatGPT 不伪造额外作者身份。

**Timezone**：项目研发记录继续使用 `+08:00`，与 V3 阶段既有开发事实记录保持一致。

### D-011 — Web 统一验证入口

**Decision**：`web/package.json` 以 `npm run verify` 作为前端统一验证入口，固定顺序为 `npm test` 后 `npm run build`。

**Reason**：延迟批量验证模式需要一个稳定、低歧义的前端入口；测试失败时不应继续把 build 成功误读为功能通过，因此单测先于生产构建。

**Boundary**：该入口只统一现有 Node 测试与 Vite production build，不代表真实浏览器/E2E、后端联调或权限链已经验证。

**Implementation commits**：`e36a350`（package script）+ `db810d6`（结构守卫）。

### D-012 — S3-54 AI 结论展示只消费后端 summary

**Decision**：`buildAiEvidenceContext` 必须显式搬运 `explanation.summary` 为页面结论字段；缺失或空白时返回 `null`，由页面显示既有“后端未给出结论文本”降级文案。不得从查询结果、证据字段或前端规则自行生成结论。

**Reason**：`AiAssistant.vue` 已读取 `evidenceContext.summary`，但上下文构造器此前从未返回该字段，导致后端已经给出 `ExplanationResult.summary` 时页面仍固定显示缺失文案。

**Boundary**：本改动只修展示链路，不改变 LLM/模板解释生成、证据数值、provider 判定、Text-to-SQL 或后端契约。

## 2026-09-17

### D-013 — AI 证据锚点 ID 保持后端原始形状

**Decision**：所有用于决策草稿锚点的 `evidenceId` / `snapshotId` 必须是后端返回的**精确字符串**。前端不得对 ID 做 `trim()`、数字转字符串或其它“修复后再接受”的归一化；`unknown` 任意大小写、空串、带前后空白、非字符串都视为无效。

**Reason**：S3-55 在 `context.js` 已把 ID 读取收紧为严格字符串，但 `decisionDraft.js` 仍先经过 `draftText()`，会把 `" EV-... "` / `" S... "` trim 成合法形状，形成第二个归一化所有者并绕过严格边界。S3-56 删除这层 ID trim，只让普通业务文本继续使用 `draftText()`。

**Invariants**：

- `context.js#isRealSnapshotId` 是当前前端 ID 真实性的单一判据；
- evidence package ID 与 snapshot ID 均不做 trim；
- 数字 ID 不自动转字符串；
- evidence package 真实值仍优先于 snapshot；
- 无真实锚点时仍 fail-closed，不构造决策草稿请求。

**Implementation commits**：`617642c`（S3-55 上下文严格读取）+ `776bc74`（S3-56 草稿锚点去二次归一化）+ `88c715e`（developer tests）。

### D-014 — Explanation provider provenance 由调用链直接记录

**Decision**：`ExplanationResult` 新增 `providerUsed`，其值由 `ExplanationService` 在**最终结果被采用时**直接记录。模型输出实际被采用时记录 `LlmProvider.providerName()`；provider 不可用、调用失败、数值守卫拒绝等最终回退模板的情况一律记录 `template`。`AiController` 只搬运该字段，不再通过“摘要文本是否等于模板”反推来源。

**Reason**：正式设计 §13.1 明确指出“通过文本是否变化推断 providerUsed”不可靠；模型完全可能输出与模板逐字相同的文本，而模型调用失败后模板成功也不能记成真实模型成功。

**Boundary**：这是加性响应字段与来源事实修正，不改变模型提示词、数值守卫、EvidencePackage、权限或 SQL 安全策略。

**Implementation commits**：`97ed8ac`（ExplanationService）+ `d7780d4`（AiController）+ `a134df8`（developer tests）。

### D-015 — AI Text-to-SQL 数据库超时统一使用 QUERY_TIMEOUT

**Decision**：Text-to-SQL 的 `EXPLAIN` 与真实只读执行若发生数据库查询超时，统一使用已有平台稳定码 `QUERY_TIMEOUT`，状态记 `FAILED` 并写入 `ai_query_history`。`EXPLAIN` 的非超时故障仍按 `SQL_COST_TOO_HIGH` fail-closed；真正的成本超阈值语义不变。

**Reason**：此前 `QueryCostGuard` 会把 Spring `QueryTimeoutException` 包装成 `SQL_COST_TOO_HIGH`，而 `SqlExecutor` 的 `SQLTimeoutException` 会落泛化 `FAILED` 且 `errorCode=null`，导致容量/超时故障与治理拒绝混淆。

**Boundary**：本轮只统一 AI QueryResult/审计错误码，不宣称已把 `/api/v1/ai/queries` 改成 HTTP 504；该接口仍按既有“返回 QueryResult 状态”形状工作。全局非 AI 读接口的 504 映射保持不变。

**Implementation commits**：`054408e`（TextToSqlService）+ `bc3dac5`（QueryCostGuard）+ `0c5df95`（developer tests）。

### D-016 — AI operation audit 的 FAILED 必须记失败

**Decision**：`AiController` 对 `operation_audit_log` 的问数成功/失败判据统一为：`REJECT*`、`ERROR*`、`FAIL*` 或 null 结果/状态均记 failure；`EXECUTED`、`REPAIRED`、`GENERATED` 等非失败状态记 success。

**Reason**：旧实现只匹配 `REJECT`/`ERROR`，导致 `TextToSqlService` 的 `FAILED`（例如无 ACTIVE、只读源缺失、数据库超时）被错误写成 `audit.success`，与实际 QueryResult 和审计文案“拒绝/失败”自相矛盾。

**Boundary**：只修审计分类，不改变 QueryResult 状态机或业务 API 返回形状。

**Implementation commits**：`a6e3639`（生产逻辑）+ `eecd683`（developer tests）。

### D-017 — Commit 主题时间戳以实时项目时钟为准

**Decision**：D-010 的时间戳从本条起必须在每个提交前读取实时 `+08:00` 项目时钟，不再按连续操作耗时人工预估秒数。Git commit 元数据始终是最终权威时间。

**Correction note**：本轮连续开发中 `d7780d4`、`054408e`、`bc3dac5`、`0c5df95` 的主题时间由人工顺延填写，可能与 GitHub commit 元数据相差数分钟；不重写共享历史。后续主题时间改用实时项目时钟，避免再次出现这种偏差。

### D-018 — AI explanation 回退原因必须匹配真实失败类型

**Decision**：`ExplanationService` 的模板回退必须区分至少三类原因：模型/Provider 调用失败、摘要形状/长度守卫拒绝、数值守卫拒绝。Provider 已被真实调用但失败时，不得再返回“未调用大模型”；Provider 超时/限流/网络/鉴权/格式失败不得冒充“数值校验失败”。所有最终回退仍保持 `providerUsed=template`。

**Reason**：S3-57 已把“最终采用来源”从文本推断改成真实调用链，但 `rewriteSummary()` 仍用一个 `null` 同时表示形状失败、数值失败和 Provider 异常，调用方统一追加“数值校验失败”；问数解释的异常路径又复用硬编码“未调用大模型”的模板说明。这会让用户看到与真实执行事实相反的失败原因。

**Safety boundary**：对外只暴露稳定、可解释的原因类别；不把 Provider 原始异常 message、响应体、URL、密钥或堆栈拼进 API limitation。原始异常仍只进入既有日志/审计路径。本项不新增自动重试、不改变 Provider 调用次数、不改变 SQL/权限/快照安全策略，也不新增公开响应字段。

**Implementation commits**：`8ab0058`（生产逻辑）+ `97d6e83`（developer tests）。

## 2026-09-19

### D-019 — ADS_STAGING_PRESENT v2：合法空态暂存分区 ≠ 发布缺失

**Decision**：质量规则 `ADS_STAGING_PRESENT` 升级为 v2：暂存分区**就绪** = 该 snapshot+dt 的分区存在**且** Hive Location 可读；`rowCount=0` 是合法空态（不再误判 BLOCKING），发布时输出良构的 `0/0/passed=1/error_rate=NULL` 行；**分区缺失或无 Location 仍为 BLOCKING**。实现为追加式新迁移 `V29__quality_rule_ads_staging_present_v2.sql`（不改任何已发布迁移 V1~V28），运行时按 `quality_rule_definition` 唯一键 `(source_scope, rule_code, version)` 取最高 enabled 版本；dqc 与 publish 预检共用 `PartitionEvidence.missingLocatedTables` 判据。

**Reason**：Batch T 的 producer 输入是 mock-mall 纯交易事件，而 mock-mall 无通用行为端点（B-04）⇒ 纯交易日的行为主题 ADS 暂存表合法为空；v1 把「表存在但 0 行」误判为 BLOCKING，QUALITY_CHECK 必然假失败——被阻塞的是缺陷形态而非真实数据缺失。0 行合法 ≠ 放水：结构缺失（分区/Location 不存在）在 v2 下依旧拦截，且「0 行合法」只适用于本规则。

**Boundary**：不改质量阈值数值；不改已发布迁移 V1~V28；V29 在真库 3306 上**未执行**（3306 冻结，迁移头部标注不变，3306 零写入）；其他质量规则语义不变。T-R1 通过不证明 Flume/HDFS/REMOTE_CLUSTER/浏览器 E2E/真实 LLM。

**Evidence**：spark 档 312/312（JDK8）；default fresh analytics **1036 MATCH**（基线 1035→1036，spark 308→312 在 runner 内补记）/ mall 14 / generator 111，唯一红仍是既有 manifest patrol；isolated 档 fresh runId `tir1iso_20260919_093537` **60/60**（mall 30 + generator 19 + analytics 11），其中 analytics-schema Flyway 在全新 3307 meta 库 `tir1iso_20260919_093537_analytics_meta` 上应用至 **version v29 成功**——V29 已获真实 MySQL 证据（3306 仍未执行）。

**Implementation commits**：`81d8f93`（16 文件修复 + 基线同步）。

### D-020 — mxp 合法 0 行 ADS 暂存分区的导出走 catalog-backed 空态路径

**Decision**：`MetricExportJob` 导出循环以 `PartitionEvidence.collect`（metastore 侧 `COUNT(*)`，缺失分区不进结果映射）为判据：仅当该 snapshot+dt 分区**存在且真实 0 行**（hiveRows==0）时，改用 `spark.table(...).select(契约列).limit(0)` 从 catalog 构造 0 行 DataFrame 导出，不对物理路径做文件级 schema 推断；非 0 行与分区缺失（countOf 缺项记 -1）一律保持既有 `spark.read.parquet(loc)` 物理读取路径。0 行导出走既有空文件路径：真实 0 字节 JSONL + manifest rowCount=0 + checksum="0"。

**Reason**：T-R1 实证：当天无行为事实时（mock-mall 纯交易源，无通用行为端点 B-04）行为主题暂存分区目录只有 `_SUCCESS`、无 parquet 数据文件，`spark.read.parquet` 的文件级 schema 推断必然抛 `UNABLE_TO_INFER_SCHEMA`，PUBLISH_METRIC 因此 FAILED（`FAIL_PRODUCTION_RUN_PUBLISH_FAILED`）——D-019 打开合法空态通道后首次触达 mxp 暴露的既有下游缺口，非 v2 逻辑回归。

**Boundary**（总控裁决原文边界）：只处理「已确认 Hive 分区合法存在、snapshot pin 正确、真实行数为 0」的导出情况；**禁止**把合法空态扩大成「所有 schema 读取失败都按空表处理」——路径丢失、schema 损坏、非法分区照旧 fail-closed；不通过 catch `UNABLE_TO_INFER_SCHEMA` 跳过表；不放宽 snapshot pinning（MXP_SNAPSHOT_PINNED）、checksum（MP_EXPORT_CHECKSUM）、行数对账（MXP_EXPORT_ROWS / MP_ADS_ROWS_MATCH）与 8 表完整性判据（MXP_EXPORT_COMPLETE / MP_MANIFEST_TABLES）。发布侧按既有设计兼容合法空表：`MP_REQUIRED_TABLES_NONEMPTY` 只要求概览/趋势/漏斗/活跃四表非空（代码注释明示「其它表允许 0 行」），`AdsExportReader` 对 0 字节文件返回空行集、`MetricAdsWriter.insertRows` 对空集返回 0、`MetricPublishValidator` 0=0 对账通过；最终以 T-R2 实链复证。不碰 3306、不改 V1~V28、不改质量阈值。

**Evidence**：developer spec `MetricExportZeroRowSpec` **8/8**（0 行 pub 放行＋mxp 空态导出 SUCCESS＋负向对照证明 0 行分区物理读取确实抛 UNABLE_TO_INFER_SCHEMA＋非 0 行 6 表不回归＋0 字节 JSONL/rowCount=0/checksum="0"＋manifest 总账）；三档回归：spark fresh **320/320**（基线 312→320，RunId devmxpfull_20260919_1046）→ MATCH PASS（devmxpmatch_20260919_1050）；default analytics **1036 MATCH** / mall 14 / generator 111（devmxpdef_20260919_1055，唯一红=既有 manifest patrol）；isolated fresh runId `tir2iso_20260919_105926` **60/60**（mall 30 + generator 19 + analytics 11；口令通道按 T-R1 先例由幂等 prep 以进程内新生成口令重置 run 账号，口令只走 PowerShell Process env）。

**Implementation commits**：`1ae091b`（MetricExportJob.scala catalog-backed 空态分支 + MetricExportZeroRowSpec.scala + spark 基线 312→320）。
### D-021 — ads_data_quality_m.error_rate 放宽为可空（append-only metric Flyway V11），使 D-019 合法空态可入库

> 编号注记：本仓库现行决策系列（docs/decisions/DECISION_LOG.md）的 D-021 与历史过程记录 docs/status-history/开发过程事实与决策记录.md:242 中另一无关系列的「D-021（跨程序身份语义，B-07）」撞号；两者分属不同文档、不同系列，本条目属现行 DECISION_LOG 系列，特此消歧。

**Decision**（总控裁决原文）：「总控裁决：D-021 APPROVED。按 append-only metric Flyway V11 将 ads_data_quality_m.error_rate 改为 DECIMAL(12,6) NULL DEFAULT NULL；不改 V3、不做 NULL→0 转换、不改变 D-019。补 developer + isolated MySQL IT、跑三档回归，并在 T-R3 前完成 Independent Reviewer。完成后以修复后的精确 SHA 建 T-R3；BATCH-U 继续关闭。先将本地 badbf2a、520d673 推送到 feature/v3-development，再继续。」

**Reason**：T-R2 attempt-4 实链证据：D-019 打开的合法空态质量行（0/0/passed=1/error_rate=NULL）被 metric 库历史迁移 V3 的 `ads_data_quality_m.error_rate DECIMAL(12,6) NOT NULL DEFAULT 0` 拒绝——`MetricAdsWriter.insertRows` 抛 `DataIntegrityViolationException`（platform.log `Column 'error_rate' cannot be null`，行 76/111/121），MP_ADS_WRITE 失败，事务回滚 42 行，pipeline runId=11 终态 FAILED。这是 D-020（mxp 空态导出）打通后发布侧最后一个下游缺口，非新逻辑回归；表结构属已发布迁移治理（V3 不可改），故以 append-only 迁移放宽。

**Boundary**：不改 V3、不改 V1~V28 任何已发布迁移（V1~V10 blob-hash 逐一复核字节不变）；不做 NULL→0 转换（保留 NULL 空态语义）；不改变 D-019 质量语义与阈值数值；无 Java 生产代码改动（`MetricAdsWriter` 将 `row.get(column)` 原样传 JDBC，SQL NULL 自然流动；无 error_rate 读侧消费者——AnalysisService.quality 只读 rule_code/passed/rule_version，PipelineService 侧 error_rate 属 meta 库 `data_quality_result` 另表、保持 NOT NULL 不在范围）；V11 标注【真库执行状态：未执行】3306 冻结（零写入、不切 ACTIVE），真实 MySQL 证据只来自 3307 隔离库；不 force push；不自行宣布完整验收。

**Evidence**：新增 `V11__ads_data_quality_error_rate_nullable.sql`（单语句 `ALTER TABLE ads_data_quality_m MODIFY COLUMN error_rate DECIMAL(12,6) NULL DEFAULT NULL`）；developer 迁移脚本测试 `AdsDataQualityErrorRateNullableMigrationScriptTest` **3/3**（全量体匹配、append-only+版本序、负向对照）；isolated 真库 IT `AdsDataQualityErrorRateNullableMySqlIT` **2/2**（IS_NULLABLE=YES/COLUMN_TYPE decimal(12,6)/COLUMN_DEFAULT NULL + flyway v11&v3 success=1；D-019 形状 NULL 行经 `MetricAdsWriter.insertRows` 真实入库读回）；三档回归：default analytics **1039 MATCH**（F=1 既有 manifest patrol，RunId `d021def_20260919_143033`，基线 1036→1039）/ mall 14 / generator 111；spark fresh **320/320** exit 0（RunId `d021spark_20260919_143243`）；isolated fresh runId `d021iso_20260919_145240` **62/62** exit 0（mall 30 + generator 19 + analytics 13 = IsolationGuard 6 + MetricAds 2 + MetricPublisher 3 + 新增 AdsDataQualityErrorRateNullable 2；首轮 `d021iso_20260919_144858` 被 external kill 于 schema 步骤中止，属基础设施中断无测试结果，不记成败）；口令通道按 T-R1/T-R2 先例只走 PowerShell Process env。**Independent Reviewer**（governance §1.4/§8、D-004、ADR-0001，钉定 7850e9b 独立新会话复核）：**VERDICT: APPROVE**，9/9 项符合（含 V1~V10/V3 blob-hash 独立复核、迁移脚本测试离线复跑 3/3、error_rate 读侧 NULL-safety 穷举 grep）；2 条 LOW（本 DECISION_LOG 条目缺失→本条即补；编号撞号消歧→见顶部注记）。

**Implementation commits**：`7850e9b`（V11 迁移 + AdsDataQualityErrorRateNullableMigrationScriptTest + AdsDataQualityErrorRateNullableMySqlIT + run-tests.ps1 基线 1036→1039 与隔离 11→13/60→62 及 `$expectedMap` 单一来源对齐）。

## 2026-09-20

### D-022 — G31-01 测试隔离加固：配置兜底默认清零（双层防线）+ 共享启动前门禁 + PID 复用拒绝清理 + metricread 只读分权

**Decision**：G31-01（指导书 §5，01.1~01.5）按以下五项收口：(1) **01.1 配置同源**——`platform-app/application.yml` 剥离全部 9 个 `PLATFORM_*` 键的 3306 兜底默认值，改为裸 `${PLATFORM_*}` 占位（缺变量 = 平台 Spring 上下文启动即 IllegalArgumentException，内层 fail-fast 防线）；外层防线为新增共享脚本 `scripts/assert-platform-env.ps1`（13 个必填变量 + URL 必须 `jdbc:mysql://127.0.0.1:3307/` + 库名前缀白名单 + 值含 `:3306` 即拒，exit 12，纯字符串核对无网络 I/O，负例结构性不可能触碰 3306），供平台启动 driver 在 detached 启动**之前**与负例子进程演练复用。(2) **01.2 资源账本 + PID 复用拒绝**——新增 `scripts/stop-platform-by-pidfile.ps1`：以 pidfile + identity.json（pid/name/marker=runId）为身份证据，进程在但命令行缺 marker ⇒ exit 5 拒杀（防 PID 复用误杀）、name 不符 ⇒ exit 5、pid 已不在 ⇒ ALREADY_GONE 幂等 exit 0（仍查端口释放 + 3306 标记清查）；停止采用 CIM ParentProcessId 闭包的 owned-tree children-first 停止（不做 java 按名组杀），写 ledger JSON（credentialRefs 只记名字不记值）。(3) **01.3 driver 交接**——session-1（start）与 session-2（cleanup/负例回归）为独立 pwsh 进程，session-1 主动退出前留 pidfile/identity/ledger-run，session-2 可独立清理；清理失败绝不允许 PASS（exit 1）。(4) **5.1.4 只读分权**——prep 新增第 4 条 secret 通道 `V25_IT_METRIC_READ_PASSWORD`（进程 env → prep 建 SELECT-only 账号 metricread），`PLATFORM_METRIC_READ_USER/PASSWORD` 与 publish 账号分离；通道缺省时保持旧行为（向后兼容）。(5) **01.4 定性为 audit-only**——`run-tests.ps1` 现有门禁（N>0 且 F=E=0 且模块退出码 0；spark 仅认 TestSuite.txt + mtime ≥ run start；TestSuite.txt 缺失即 fail；S3-51 互斥锁 REFUSE exit 5）已覆盖报告新鲜度/失败分类要求，本批不加码不改语义，仅登记审计结论。

**Reason**：BATCH-V attempt-1 的 3306 侧效应根因有两个：driver 漏配 env 块（外层防线缺失）+ application.yml 存在 3306 默认值（内层防线形同虚设，缺 env 时静默回落正式库）。只修 driver 不修 yml，则下一次遗漏仍击穿冻结边界；故按 5.1.2 把两层都钉死。BATCH-W 的清理脚本缺身份核对，PID 复用窗口下有误杀无关进程风险，故 01.2 要求拒绝式清理 + 资源账本。metric publish 与 read 共用写账号不符合 5.1.4 最小权限，故拆分 SELECT-only metricread（分析 API 只读路径不再持有写口令）。

**Boundary**：3306 全程零接触（含只读探测——负例由 assert-platform-env 纯字符串核对结构性排除）；不执行任何 DROP；数据域复用 `stage7q1_20260918_152245` 四库经幂等 prep（对象已存在则跳过）达成，不清理旧 runId；jar 旧 SHA pin（9e79f1b3…）因 yml 剥离已失效，改为**记录 SHA 不钉定**（pin 语义回归 git SHA）；不改架构、不改指标语义、不改 run-tests.ps1 门禁语义（01.4 audit-only）；V25_IT_* 口令只在进程内生成/读取（零落盘/零 argv/零 git/零日志），平台 secret env 值不入日志。

**Evidence**：RunId `g3101_20260920_003500`（session-1 outcome=PLATFORM_RUNNING，session-2 outcome=CLEANUP_AND_NEGATIVES_PASS）。01.1：负例 N1（缺 META_PASSWORD）/N2（META_URL 指向 3306）/N3（PUBLISH_URL 错库名前缀）全部 exit 12 于启动前（378/371/344 ms），零平台 JVM、8091 空闲；正例门禁 `assert-platform-env：OK（13/13 …）`；F2b 启动后日志核查 0 次 `:3306`。01.2：C1 owned-tree 停止（cmd 34068/conhost 13800/java 29800）+ 端口释放 + ledger outcome=STOPPED；C2 PID 复用牺牲进程拒杀 exit 5（牺牲进程存活）；C3 名字不符拒杀 exit 5；C4 ALREADY_GONE 幂等 exit 0。01.3：两 driver 分进程交接成立。5.1.4：grant audit 证实 metricread（`stage7q1_202_0598ff44_metricread`，32 字符哈希回退命名）仅 `GRANT SELECT`、无任何写权限、无 meta 库权限；读探针 `/api/v1/metrics/snapshots` 返回 3 条 + 日志含 metric-read-ds 连接池。回归：fresh suite RunId `dev003c_20260920_002959_f9380b` analytics **1039 MATCH** / mall 14 / generator 111（=1164，唯一红仍为既有环境性 `IngestionManifestRuntimePatrolTest`）；隔离档沿用 `d021iso_20260919_145240` 62/62 基线（本批未动隔离测试数）。新增 01.5 文档 `docs/verification/ISOLATED-EXECUTION-MINIMAL-GUIDE.md`（零密钥）。结果文档 `docs/verification/results/G31-01-TEST-ISOLATION-RESULT.md`。

**Implementation commits**：`00dac1a`（application.yml 兜底默认剥离 + EnvCredentialService 注释 + assert-platform-env.ps1 + stop-platform-by-pidfile.ps1 + it-prepare-isolation.ps1 metricread 通道，5 文件 +401/−13）。

### D-029 — 源画像生命周期门按画像语法分派顶层必备键（v2 复用 Loader 权威键集），解锁第二来源 fixture-shop-b 的源级激活

**Decision**：`SourceProfileValidator`（`POST /api/v1/sources/{id}/activate` 与 `/{id}/test` 的画像门）在「可解析为 JSON 对象」之后按与 `MappingProfileLoader.load` **逐字相同的结构判据**（`fieldMappings` 是否为 JSON 对象）分派顶层必备键：v2（V2_STRICT）画像必备键 = 公开后的 `MappingProfileLoader.V2_STRICT_TOP_LEVEL_KEYS`（8 键，唯一权威清单，排序成稳定列表），v1 扁平画像仍用设计 §4.2 的 9 键清单（顺序逐字、缺键文案逐字不变）；`ProfileCheck` record 新增 `requiredTopLevelKeys` 与 `syntaxName`（V2_STRICT/V1_COMPATIBILITY）两个组件，`SourceRegistryServiceImpl` 的 `profile_required_top_level_keys` 检查项明细随语法回填（v1 的「设计 §4.2 的 9 个顶层必备键齐全」逐字保留）。修复前 v2 画像激活源必 409 `SOURCE_PROFILE_INVALID`（误按 v1 九键清单缺 canonical/eventTypeMapping/fieldMapping/identityPolicy/quarantinePolicy）；摄取路径（Loader→Pointer→SourceMapper）本就 v2-capable，只有生命周期门卡死 v1。

**Reason**：G31-02/02.2 真机流程（RunId g3102_20260920_014827）：fixture-shop-b.v2.json 三方哈希对齐的 mapping 激活已成功（dry-run 38/38 eligible、fault 5 隔离不变式、空样本拒绝、漂移 409 `MAPPING_PROFILE_CHANGED` 全部通过），但源级 activate 409——`SourceProfileValidator` 是 P1-03 时代的 v1 门，从未分派画像版本。设计 V3.1 02.2 验收「B注册、画像预览/激活」要求 v2 画像可激活；若不改门则第二来源永远 DRAFT，02.3~02.6 全部被阻断。修门不修摄取路径（摄取路径已正确），也不放宽任何既有校验（v1 行为逐字不变）。

**Boundary**：分派判据与键集**只此一处**——validator 不自创第二份 v2 键清单，直接引用 Loader 公开常量（新增恒等测试钉死）；不新增/删除任何键、不改 v1 九键清单与顺序、不改任何 409/错误码语义；`ProfileCheck` 新组件对既有调用方（仅 `SourceRegistryServiceImpl` 一处消费）向后兼容（早退分支填空表/null）；不动摄取侧 `MappingProfileLoader.load` 的任何解析行为（仅常量改名公开 + javadoc）；3306 零接触、不 push（授权已用尽）。

**Evidence**：定向回归 `SourceProfileValidatorTest` **12/12**（+4：v2 键集≡Loader 集且不含 v1 专属键 / v2 全键通过且 syntaxName=V2_STRICT / v2 缺 amountPolicy 只报 v2 键不误报 v1 键 / v1 口径与 V1_COMPATIBILITY 不变）+ `SourceRegistryServiceTest` **32/32**（「9 个顶层必备键齐全」逐字断言仍绿）；default 三档回归 RunId `dev003c_20260920_020324_00d2c3`：analytics **1043 MATCH**（基线 1039→1043，唯一红=既有环境性 `IngestionManifestRuntimePatrolTest`，与 D-022 基线运行同一红）/ mall 14 / generator 111。修复后 jar 重建并以 `stop-platform-by-pidfile.ps1`（G31-01 C1 收口脚本，身份核对通过、8091 释放、:3306 清查零命中）+ g3102-start driver 重启平台，02.2 源级激活复跑证据见 `target/v25-it/g3102_20260920_014827/022-evidence/`。

**Implementation commits**：`8b4e4da`（MappingProfileLoader 常量公开改名 + SourceProfileValidator 分派 + SourceRegistryServiceImpl 明细回填 + SourceProfileValidatorTest +4 + run-tests.ps1 基线 1039→1043）。

### D-023 — 第二来源 warehousePrefix=`fxsb`（≤24 字符、与 `dw` 命名空间区分、语义可读）

**Decision**：fixture-shop-b（sourceId=2）注册时 `warehousePrefix=fxsb`，仓库名空间为 `fxsb_ods/fxsb_dwd/fxsb_dws/fxsb_ads`（含派生库），与 mock-mall 的 `dw_*` 完全平行、互不重叠。ingestMode=FILE，profilePath=`analytics-server/source-profiles/fixture-shop-b.v2.json`，timezone=Asia/Shanghai，currency=CNY，status=DRAFT 起步。

**Reason**：02.3/02.4 要求两源并存且同 ID 不串表；前缀是唯一隔离边界，须短、可读、与既有 `dw` 无前缀冲突；`fxsb` 满足 ≤24 字符命名约束且在 B 腿全部读写路径（spark-read、MySQL readback、文件系统 inventory）中可精确 grep。

**Boundary**：不新增共享表；不改 `dw_*` 任何对象；fxsb_* 仅由 B 腿流水线写入；不清理旧 runId。

**Evidence**：注册报文与源列表见 `target/v25-it/g3102_20260920_014827/022-evidence/`；B 腿 MySQL readback 9/9（fxsb_ods/dwd/dws/ads 四层）与 02.3 oracle 41/41；02.4 A-B-A 后 `fxsb_*` 文件级不变（132 文件/218724 字节 before=after，`025-evidence/16-fxsb-warehouse-after.json`）。

**Implementation commits**：无（验证批次决策，无代码变更；源注册为运行期 API 操作）。

### D-024 — B 夹具在 A 金样本缺失面上补齐 product_created/behavior/user_registered，使 8 类事件映射全面覆盖

**Decision**：`fixtures/source-b/fixture-shop-b/normal.jsonl`（含 `fault.jsonl` 与手算预言 `ORACLE.md`）在 mock-mall A 金样本只有交易+行为衍生的面上补齐 product_created / behavior 原始事件 / user_registered 三类，使 B 画像 8 类 eventTypeMapping 全部有真实样本执行路径；运行期使用运行目录副本，仓库制品不参与运行时写入。

**Reason**：映射覆盖证明必须「每一类映射都被执行过」，否则 02.2 的 eligible 只对 5/8 类有意义；A 金样本不携带这三类是历史事实而非缺陷，故补面在 B 夹具而非改 A 制品（不改已验证制品）。

**Boundary**：不改 mock-mall 金样本与 `mock-mall.v1.json`；B 夹具带 synthetic/fixture 标记，不称真实外部商城；预言值全部手算入 `ORACLE.md`，不允许「跑出来什么就是什么」。

**Evidence**：`fixtures/source-b/fixture-shop-b/ORACLE.md`；02.3 执行 oracle 41/41 + MySQL readback 9/9（`022-evidence/`、`023-evidence/`）；dry-run 38/38 eligible（022 fault/normal 双样本）。

**Implementation commits**：无（夹具为批次资产，随 G31-02 docs 提交收口）。

### D-025 — 故障集取 5 类互异映射违规；同源重复 event_id 案例剔除（属去重语义非映射违规）

**Decision**：`fault.jsonl` 取 5 类互异违规：未映射事件类型 / FEN 金额非整数 / 事件时间格式不匹配 / 必填字段缺失 / 不支持的 schema 版本；剔除「同源内重复 event_id」案例——同源重复属 DWD 去重键语义（D-012 一族），与映射违规混测会污染归因。

**Reason**：故障注入要的是「每类违规独立可见且 reason 可归因」；去重与映射是两个机制，合并样本会使 quarantine reason 分布不可解释。

**Boundary**：不放宽任何 quarantine 不变式（隔离行不进 ODS、batch quarantineCount 精确、SUCCESS 语义不变）；故障样本只进 dry-run，不进正式摄取流。

**Evidence**：`022-evidence/` fault dry-run：5 行全部隔离、reason 与类别一一对应、isolated 行零泄漏；02.2 验收「预览错误/覆盖率」即以此为准。

**Implementation commits**：无（验证批次决策）。

### D-026 — A-B-A 回程每腿换新落盘文件名（checkpoint 键含绝对路径）；登记每源检查点的跨源重扫语义与防御墙结果

**Decision**：A-B-A 各腿落盘文件名互不相同（B 腿 2026091821.jsonl → A 回程 attempt-1 2026091822.jsonl → 清洁复跑 2026091823.jsonl），因为 LandingInput 检查点键含**绝对路径**，同名重放会被判已处理而静默 0 行。同时登记执行中钉死的两个检查点事实：(1) 检查点键为 (runtime_profile_id, source_id, 绝对路径)——切源后**同一路径文件在新 sourceId 下会被重扫**；(2) 重扫的防御行为正确：B 残留文件 38 行在 A 画像下全部隔离（quarantineCount=38、零入仓），attempt-1 的 A 文件被 batch 16 消费后在其 checkpoint 下不再被 batch 17 重扫；完全消费的文件连扫描都不进入（batch 18 对 2026091823.jsonl 复跑 recordCount=0/fileCount=0）。

**Reason**：attempt-1 的 batch 16 出现 fileCount=2/quarantineCount=38，根因是 B 残留文件在切源后按 (profile,source=1) 重新可见——这不是缺陷（防御墙完整起效），但必须登记成语义事实，否则后续任何「切源后重放旧文件」的操作都会被误读为数据污染。清洁复跑（025）以「归档残留 + 新文件名」把两个语义都钉进证据。

**Boundary**：不修改检查点实现与键序；不清理 landing 历史归档（archive-b15/、archive-a16-attempt1/、archive-b17-consumed/ 均保留出处）；不把「重扫即隔离」宣传为可依赖的数据修复手段——正确操作仍是先归档再切源。

**Evidence**：`024-evidence/`（attempt-1：fileCount=2/quarantine=38、dashboard 零 B 残留）；`025-evidence/06-ingest-run.json`（batch 17：17/0/SUCCESS/fileCount=1）；`025-evidence/28-landing-final-hygiene.json`（batch 18 幂等 0/0 + 三处归档 + events/ 终态为空）；`025-run.log` 全 26 项。

**Implementation commits**：无（验证批次决策）。

### D-030 — dry-run 预览严于生产 Loader：预览定位为 advisory preflight，激活/摄取以 Loader 语义为准；遗留 A 画像不在验证中途改写

**Decision**：映射 dry-run 报告与生产 Loader 的接受面差异**登记为已知语义而非缺陷**，处置三条：(1) 激活与摄取的权威语义 = `MappingProfileLoader`（batch 17 实测 17/17 全收、0 隔离），dry-run 报告定位为 **advisory preflight**（错误/覆盖率预览），02.4 的预检断言放宽为 `processed=17 && systemErrors==0`；(2) 已提交且已验证的 A 画像 `mock-mall.v1.json` **不在验证中途改写**——补 amountPolicy 单位声明等属遗留画像治理，另立后续工作项，不随 02.4 静默变更（避免「验证中途换被测物」）；(3) 向导（02.5）展示预览时必须携带 `activationEligible=false` ≠「必然拒绝激活」的语义说明，以 Loader 实际结果为激活门槛。

**Reason**：attempt-1（024）dry-run 对 A 画像报 26 条 preview violations（reasonCounts: EMPTY_FIELD 11 / PROFILE_INVALID 15；requiredCoverage 0.877、activationEligible=false），其中三类均为预览比 Loader 严：MISSING_AMOUNT_POLICY（遗留画像无 amountPolicy 单位声明）、ITEMS_REQUIRES_ITEM_MAP（Loader 接受 items 为 JSON 字符串）、EMPTY_FIELD（status/created_at/paid_at 可选映射字段在 stage7q1 金样本中同样缺省）。若按预览阻断激活，则平台自己的黄金路径都会被预览否决——证明「预览=门」是错误模型；但预览的 processed/systemErrors/requiredCoverage 对管理员仍有真实预警价值，故保留为 advisory。

**Boundary**：不改 MappingDryRunService/Loader 任何校验代码（本决策是语义登记，不是放宽代码）；不改已提交画像制品；预览报告的 profileChecksum 权威性（sha256 of 提交字节）不变；激活漂移 409 `MAPPING_PROFILE_CHANGED` 不变。

**Evidence**：`024-evidence/05-dry-run-a-fixture.json`（26 violations 明细与 reasonCounts）vs `025-evidence/04-dry-run-a2.json`（同画像 processed=17/systemErrors=0/previewViolations=26）+ `025-evidence/06-ingest-run.json`（Loader batch 17：17 收 0 隔离 SUCCESS）；伴生澄清：repeat_rate=0.0 为平台正确值——DwsSql `valid_order_count` 按 S3-03（设计 §11.2 L433「完全退款不算有效复购订单」）只计 `final_refunded_flag=0`，u9001 第二单 910003 全额退款 → 0/2=0.0，手工预言 0.5 误用「支付复购率」变体，修正后 14/14 全绿（`025-evidence/27-close-verify.json`）。

**Implementation commits**：无（语义登记 + 证据修正，无代码变更）。

### D-031 — 02.5 接入向导面收口：冻结表 +4 行、dry-run 报告回查端点不接线（api.js 路径占位符一律 `${id}`）、预置回填输入框、激活文案按调用前 current 区分幂等与真切换

> 编号注记：早先会话曾为 02.4/02.5 产物预留 D-027/D-028 编号但未落条目（相应事实最终以 D-023~D-026、D-029、D-030 登记）；为免与历史过程记录中的引用撞号，D-027/D-028 永久空缺，本条自 D-031 续编。

**Decision**：指导书 V3.1 02.5「最小管理员接入向导」按四条子决策落地。(1) Java 冻结表（`ControllerPermissionCoverageTest`）新增 4 行 RUNTIME_MANAGE 期望：`GET /api/v1/sources`、`POST /api/v1/sources/{id}/activate`、`POST /api/v1/sources/{id}/mappings/dry-run`、`POST /api/v1/sources/{id}/mappings/activate`；dry-run 报告回查 `GET /api/v1/sources/{id}/mappings/dry-runs/{reportId}` **不接入前端**——向导直接消费 dry-run 响应体，且**双占位符路径**（`{sourceId}`+`{reportId}`）在跨树对账中不可调和：Java 侧 `normalize()` 把一切占位符折叠为 `{id}`，web 侧守卫按 `${expr}` 原名归一，两套归一对双占位符无法逐字对账。(2) 因此 api.js 全部路径占位符统一写作 `${id}`（原名参数），这是跨树对账成立的命名约定。(3) 向导第 2 步「预置样本」与「手工输入 sampleRef」互斥语义 = **最后操作的一个生效**：选预置即把引用回填输入框（输入框始终显示实际将提交的 ref，仍可再编辑），手输值经 `||` 优先级覆盖预置。(4) 激活结果文案以**调用前**捕获的源 current 状态区分「幂等」（响应 current=true 且调用前已 current）与「真切换」（响应 current=true 但调用前非 current）——激活响应的 current 是切换后视图恒为 true，不能单独作为判据。

**Reason**：E2E 实测暴露两处组件语义缺口：负例路径上预置选择被残留手输值遮蔽（互斥从未实现），真切换后文案误报「已是当前源」（current 恒真）；冻结表若收下报告回查端点则 web 判据 B 永远无法对账（前端物理上无法以单占位符表达双占位符路径）。预置清单只收 fixture-shop-b 受控集三条（boundary 守卫禁止分析前端出现商城字样，A-B-A 双腿样本仍可经手工输入引用）。

**Boundary**：不新增任何服务端端点/权限码（向导面全部复用 02.1/02.2 既有 RUNTIME_MANAGE 面）；不改 Java normalize 与 web normalize 的既有归一语义；预置清单是展示建议不是安全边界（边界在服务端 sample-root fail-close 校验）。

**Evidence**：web 套件 **329/329**（含 permissionReconcile 判据 A/C0/C1/B/D 全绿、boundary、sourceWizard 14 测试）；`ControllerPermissionCoverageTest` **5/5**（scannerSelfCheck/runtimeProfilesFullyGuarded 等全绿）；Playwright E2E **12/12**（`target/v25-it/g3102_20260920_014827/026-wizard-e2e/`：7 截图 + wizard-e2e-result.json + api-captures.json；①同画像幂等 changed=false（checksum 3ee7d8fa…、报告 dr-20260920015208-976f54b8）、②真切换文案、负例 eligible=false violations=5 → 409 MAPPING_ACTIVATION_INELIGIBLE fail-closed）；收尾恢复 source 1 current=true（A-B-A 收口态保持）。

**Implementation commits**：本笔（fix(stage7) 02.5 向导面）。

### D-032 — 02.6 时效警告派生口径：从指标载荷 period 取最大业务日期与本地今日比较，角色无关；滞后 ≥1 天才提示且绝不宣称「最新」

**Decision**：指导书 V3.1 02.6「关闭商城/生成器后历史指标可读；来源停机显示时效警告，不假报最新」的落地口径：(1) 派生输入 = `/metrics/overview` 载荷本身（metrics cell 的 `period` 字段，day 取该日、window 取观察期两端的最大 ISO 日期），**不用** `/sources` 端点——分析师无 RUNTIME_MANAGE 权限，时效提示必须角色无关；(2) 比较基准 = **本地壁钟今日**（`localIsoDayOffset(0)`，与页面日期控件同源）；滞后 ≥1 天渲染琥珀色横幅（业务时点 + 滞后天数 + 「历史指标仍可读，但以下数字不代表最新业务日」）；滞后 0 天或业务时点在未来 → 返回 null 不渲染；(3) **不假报最新**的精确语义：用户可见文案中「最新」只允许出现在否定式「不代表最新」中（单测把否定式剥除后断言零残留），同日不渲染横幅也**绝不**主动宣称「最新」（前端不为来源是否停更背书）；(4) 派生与文案唯一属主 `web/src/utils/staleness.js`，畸形/未知 period（hour: 等非声明形态）忽略不猜（与 metricPeriod.js 同律）。

**Reason**：壁钟比较是唯一无需任何额外权限/端点的信号源，且快照载荷本就同时携带 period（cell 级）与 businessTime（快照级）双证据（本次实测两者一致 = 2026-09-18，壁钟 2026-09-20，滞后恰 2 天）；停更检测的权威事实（源是否 current/暂停）在管理员专属面里，若时效警告依赖它则分析师/决策角色在来源停机时反而看不到警告——与本条款意图相反。

**Boundary**：不做「数据是新的」正向断言；不改 /metrics 端点契约；不为时效提示新增轮询或定时器（随页面 load 派生一次，重新加载即刷新）；跨日滞后数随壁钟自然增长，不缓存。

**Evidence**：web 套件 **329/329**（+5：overviewStaleness.test.js 纯逻辑 4 测 + 不假报最新断言 + Overview 接线/角色无关锚点）；独立性证据 `target/v25-it/g3102_20260920_014827/027-independence/`：TCP 探测 mall :8090 与 generator :8092 均 refused、平台 :8091 在听（阳性对照），关闭双上游后 `/metrics/overview` 14 cells + `/metrics/snapshots`（ACTIVE S20260918_17 businessTime 2026-09-18）照常可读，壁钟滞后 lag=2 天，cell 日期与 ACTIVE 快照 businessTime 日期一致性 PASS（**9/9**）；真实 Chromium 横幅 DOM 证据 **5/5**（`027-independence/shots/overview-staleness.png` + banner-shot-result.json：横幅可见、事实文案、零「最新」正断言）。

**Implementation commits**：本笔（fix(stage7) 02.6 时效横幅）。

### D-033 — G31-03 业务正样本夹具：生成器文件夹具（99+71 事件）+ 独立 plain-Python oracle + E4 未来业务日钉执行窗口 + NTILE 确定性

**Decision**：G31-03（03.1~03.7）输入件按以下口径固定：(1) **夹具形态** = 生成器文件夹具（`fixtures/source-a-e3/`）：`generate.py` 确定性生成 canonical JSONL（E3=99 行、E4=71 行，均在 E3 档 20~100 上限内），经 `EventContractValidator` 全量规则离线核对（信封 7 字段+payload 对象、12 类型白名单、schema_version=1.0、金额字符串、行为枚举、各类型 payload 必填字段；校验器**无未来日期守卫**已核），不引入 mock-mall 尚不存在的行为接口——符合「合成行为只能来自生成器文件夹具或实际已存在的业务端点」约束；(2) **oracle** = `oracle.py` + `ORACLE.md`：**plain-Python 独立重述**冻结语义（OrderTradeCompiler 退款口径、漏斗四段、热度公式 1·log1p(pv)+2·log1p(fav)+3·log1p(cart)+5·log1p(buy) 与 (−heat,−buy,product_id) 排序、RFM v2 NTILE 桶序、决策 rate 4dp HALF_UP/DOWN 取负与 0.05/0 阈值），**零平台 import**，全部期望值手推+脚本双算对齐；(3) **排序确定性**：排行并列样本（g3p02/g3p03 heat 同为 14.370443）按 (−heat, −buy, product_id) 落序，RFM 依赖 Spark NTILE 追加 `user_id ASC` 稳定键（AdsSql rfm-v2）→ oracle 给**精确桶值**而非一致性检查；(4) **E4 业务日 2026-09-21（晚于壁钟）**：合法（校验器无时间守卫），唯一真约束是 DecisionService 的 insufficientReason 下界（actual.businessDate 必须晚于 complete 壁钟日）→ E4 链必须钉死在壁钟=2026-09-20 当天完成，ORACLE.md 写明过期需以新 T 重生成；(5) 每条腿（E3/E4）**单次流水线运行**承载对应 businessDate 的 ADS 计算，E3=2026-09-18、E4=2026-09-21。

**Reason**：指导书 03.x 的验收全部是**数值可对账**断言（排行至少3商品并含并列、分页稳定、漏斗可对账、RFM 原值+窗口、四类评价可达、基线0不输出无限改善率）——只有「输入事件逐条可枚举 + 期望值由独立实现算出」才能把平台输出和 oracle 逐 cell 对表；复用平台自身代码算期望等于用被测物验证被测物。并列与 NTILE 稳定键是 03.1/03.2 显式要求，E3 商品/行为设计（5 商品、30 views/6 fav/8 cart/12 paid、6 买家 3 复购）即为覆盖这些断言而反推。

**Boundary**：不改平台代码（纯输入件+文档）；不新增 ADS 表；E3/E4 落盘 hash 清单 `MANIFEST-SHA256.txt`（生成器、双 JSONL、oracle、ORACLE.md 五件）；E4 71 行中 2 单退款全额（g4r0001/g4r0002）用于 D3 INEFFECTIVE 方向；`fixtures/` 属输入件入 git，运行期产物（landing/staging/evidence）不入。

**Evidence**：`fixtures/source-a-e3/MANIFEST-SHA256.txt`（五件 sha256+行数：e3=99/e4=71/generate.py=257/oracle.py=304/ORACLE.md=90）；oracle 预算值：A 快照 pv 30/uv 10/gmv 1010/net 930/aov 84.1667/refund 0.0833/repeat 0.3333，漏斗 10→6→6→8→6，排行 g3p01(16.968247)>g3p02=g3p03(14.370443)>g3p04(7.154615)>g3p05(4.564348)，RFM 6 行（g3u01 5/5/5 重要价值…g3u06 1/2/1 一般保持 新用户）；B 快照 gmv 1200/net 1000/aov 92.3077/refund 0.1538；四决策 D1 EFFECTIVE(+0.0967)、D2 PARTIAL(+0.0333)、D3 INEFFECTIVE(−0.8463)、D4 EFFECTIVE(+0.1881)、D1 先评 INSUFFICIENT_DATA（完成后尚未发布新快照）。

### D-034 — 03.5 跨源证据守卫：suggestionSnapshotId 解析回源比对，跨源快照拒绝为 SOURCE_MISMATCH 并留审计

**Decision**：指导书 03.5「跨源证据拒绝并留审计」落地口径：`DecisionService` 提交链（createDraft/submit）在请求携带 `suggestionSnapshotId` 时，**先解析后接受**：(1) 按 snapshot_id 查 `metric_snapshot`，不存在 → `PARAM_INVALID`（snapshotId 参数化注入面，不当「未知源」处理）；(2) 由快照行取 `runtime_profile_id` → `runtime_profile.source_id`，与**当前 ACTIVE runtime_profile 的 source_id**（即本次决策实际将引用的证据源）比对，不一致 → 新业务码 `SOURCE_MISMATCH`(409)，异常文案携带 双方 source_id 与快照 id；(3) 校验点放在 service 层业务校验段（与 baseline/actual 可观测性检查同段），controller 既有包装器自动把业务异常落 `decision_audit` FAILED 行（error_code=SOURCE_MISMATCH）——审计留痕不新增机制；(4) 不校验通过时**不消耗**快照/不触碰 source_mapping_active（对 source 2 只读引用，S20260918_15 作为跨源参照样本）。

**Reason**：03.5 要求「跨源证据拒绝并留审计」，而决策引用的快照是唯一能伪装成证据的入口——若只在前端隐藏跨源快照选项，构造 API 调用仍可把别源快照塞进 suggestionSnapshotId；service 层解析回源是唯一权威闸门。用当前 ACTIVE profile 的 source_id 作为基准而不是「决策人自选源」，与 D-031 语义一致：决策的评价对比必须同源同定义，跨源对比在 03.6 由 oracle 显式约束（等长同源同定义）。

**Boundary**：不改快照发布/激活机制；不改 controller 包装器；SOURCE_MISMATCH 单测覆盖（跨源 id、不存在 id、同源 id 三态）；不把 source_id 暴露给前端新字段（复用既有 409 错误结构）。

**Evidence**：单测 DecisionServiceSourceMismatchTest（待本批提交）；运行期证据 = 50-*.json（03.5 负向批），以 S20260918_15（source 2）对 ACTIVE S20260918_17（source 1 数据域）发起决策提交，断言 409/SOURCE_MISMATCH + audit FAILED 行存在。

### D-035 — G31-03 E3/E4 夹具按运行契约修正并支持可复现的未来业务日

**Decision**：保留 D-033 的事件场景规模和业务金额，修正其后发现的四处实现口径，并消除 E4 过期：(1) 用户/商品/订单实体 ID 使用纯数字字符串以符合已冻结 `IdCodec` 正则；首版 `g3u01/g3p01/g3o0101` 在真实 run 18 中无法抽取 BIGINT，DWD 主键为空，质量闸正确阻断；事件、trace、支付及退款 ID 保持原样；(2) AOV oracle 按 `dws_trade_day.avg_order_value DECIMAL(18,2)` 量化，E3=84.17、E4=92.31，不再保留旧 4 位展示值；决策改善率以服务库发布精度计算仍为 0.0967；(3) 漏斗仍为 view/intent/order/pay 四行，加购独立发布 `cart_rate`，不作为第五阶段；(4) RFM `r_ntile>2 && m_ntile<4 && f_ntile<4` 分类对齐 SQL 为“一般挽留”；(5) E4 日期由 `generate.py --e4-date YYYY-MM-DD` 显式固定，缺省取本地明日，oracle 从 E4 事件本身提取日期并要求 71 行同一业务日。D1 完成日必须早于 E4 业务日；若日期过期，重新生成、重算 oracle 并更新 SHA 清单。

**Reason**：E3/E4 是 G31-03 的输入真值。只更新文件哈希而保留错误 AOV、漏斗阶段、RFM 标签或已过期日期，会让后续全链路对账稳定地产生错误结论。D-033 保留历史原貌；本条覆盖其已过期的字段精度、分类描述、日期与摘要数值。

**Boundary**：仅修正独立夹具、oracle、夹具说明和本台账；不改 Spark 生产 SQL、ADS 表结构、指标定义或决策算法；不把本地 oracle 运行写成真实 Spark/ADS 验收。

**Evidence**：`fixtures/source-a-e3/ORACLE.md`、`generate.py`、`oracle.py`、`MANIFEST-SHA256.txt`；生成器与 oracle 的本地校验只证明输入件内部可复现。真实 EventContractValidator→Spark→ADS→决策流程仍需 G31-03 运行证据。

### D-037 — 商品维表按业务日生成 as-of 完整快照

**Decision**：`dim_product` 以业务日 `dt` 为截止点，读取 `ods_product_event.dt <= dt` 中合法的 `product_created/product_updated` 事件，为当天重建完整商品状态；每个 `payload_product_id` 按 `event_time DESC, ingest_batch_id DESC, event_id DESC` 稳定取最新记录。`DimensionBuildJob` 即使当天没有商品事件，只要截止日前存在可用商品历史也必须写当天维表分区。作业 `inputRecords` 继续统计当天 ODS 用户/商品事件总行数（库存事件仍计入，保持既有结果契约）；`productAsOfEligible` 另行记录可进入商品维表的截止日历史行数。商品维表仍以 `product_id` 为键，不增加 `source_system` 维度，以免与当前 DWD/ADS 的旧键关联形态不兼容；跨源自然键冲突需待 DWD 复合键全链改造时一并决策。

**Reason**：商品是缓慢变化维度。只读当天商品事件会使无商品变更日的 `dim_product` 分区为空，导致已存在商品的销售/行为事实无法取得名称；用“截止日历史 + 最新状态”生成快照，既保持每日可连接的维度，又避免未来分区事件污染历史业务日。稳定次序键使相同时间戳的多条更新可重复选出同一条记录。

**Boundary**：不改 ODS/DIM 表结构、ADS 指标公式、用户维表时态口径或当前 DWD 业务键；不声称跨商城同 ID 已完成隔离；当截止日前不存在任何有效商品建档/更新事件时，不创建虚假的商品行。该次 Spark 测试为 `local[1] + in-memory catalog`，不代表生产 Hive/HDFS 实链。

**Evidence**：`ProductDimensionAsOfSpec` 真实执行 Spark SQL：创建日商品写入、次日无商品事件仍延续商品、第三日更新生效、回跑第二日不读未来更新；`SqlTemplateSpec` + `DimDwdChainExecSpec` 定向组合 **48/48 PASS**（JDK8，ScalaTest；三套件，48 tests）。随后统一入口 `scripts/run-tests.ps1 -Suite spark` fresh **321/321 PASS**（40 suites，JDK8，exit 0；RunId `dev003c_20260923_112300_9053c1`，基线 320→321 MATCH）。测试证明边界仅为 Scala `local[1] + in-memory catalog`，不代表生产 Hive/HDFS 或真实 `spark-submit`。

> 编号注记：本记录续 D-035 使用 D-037；D-036 已在历史过程资料中用于另一事项，不复用编号。

