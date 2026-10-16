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

### D-038 — G31-03 运行期偏差的诚实处置：跨源夹具改道、stale-jar 重启、N1 审计缺失语义与 token 使用注记

**Decision**：(1) **跨源夹具改道**：ORACLE.md「跨源证据」段原方案以 fixture-shop-b（source 2）已发布快照 S20260918_15 作 suggestionSnapshotId；运行期实测本 attempt 的 metric DB 中 `S20260918_15.runtime_profile_id=1`（该快照实属源 1 档案；源 2 在本库无任何已发布快照，runtime_profile 仅 id=1/sourceId=1）→ 不伪造跨源数据，改道为：注册并**临时激活**源 2 运行环境档案 `fxsb-probe-g3103`（id=4，LOCAL，克隆档案 1 配置），以真实源 1 快照 S20260918_19 作证据，D-034 守卫在 submit 与 create 双路径各返回 409 SOURCE_MISMATCH（N5），随后重新激活 `local-dev`（id=1）并断言恢复。无任何 DB 伪造：快照归属是真实数据，跨源性由真实激活切换产生，且完全可恢复。(2) **stale-jar 重启**：03.5 负向批执行时运行中的 jar（02:06 构建）早于 D-034 守卫提交 2a11b60（05:46），为让负向证据跑在含守卫的当前代码上，同 attempt 内重建并重启（jar SHA256 `631fd38f…`，wrapperPid 25320，restartedAt 2026-09-20T06:53:59）；重启 mini-gate = S20260918_19 ACTIVE 唯一 + 决策数保留 + USR=3，F2b 零 3306。原则：运行证据只在当前代码上产生，旧进程上的结果不作数。(3) **N1 审计缺失语义**：N1_unauthorized_approve（analyst 发起审批动作）被 AuthInterceptor 在 Controller 之前以 403 FORBIDDEN_PERMISSION 拒绝；DecisionController 的 action() 包装器只审计到达服务层的异常 → N1 在 `decision_audit` 无 FAILED 行是**设计内缺失**而非漏记，51-audit-negatives.json 以零行断言固化该事实（审计窗内仅有 N2~N5 的 DECISION_CREATE/START/SUBMIT 行，id 52~56）。若未来要求前置拦截也留痕，须改 AuthInterceptor 并同步回归，不在本次范围。(4) **token 注记**：03.x API 腿全部以真实 `/api/v1/auth/login` 取 token 后仅在进程内作 `Authorization: Bearer` 使用，证据 JSON 与日志不落 token 明文；N2 证明伪造创建者头 `X-User-Id: 999` 被忽略（R8-3 已删除该通道，身份一律取登录会话）；浏览器 leg 的竞态守卫 route.fetch 使用 localStorage `analytics_token` 会话 token 直连 8091。

**Reason**：计划与运行实况的偏差若不入台账，复现者会按 ORACLE.md 原方案（以 S20260918_15 为跨源证据）重跑并得到相反结论；stale-jar 若不记录，负向证据会被质疑跑在无守卫代码上；N1 的「无审计行」若不固化语义，会被误判为审计遗漏缺陷。

**Boundary**：不改 AuthInterceptor、审计包装器或 DecisionService；`fxsb-probe-g3103` 为运行期探针档案，批内即恢复 ACTIVE=local-dev，不留持久配置变更；本条只登记事实与语义，不改变 D-034 的守卫口径。

**Evidence**：`target/v25-it/g3103_20260920_052106/50-decision-negatives.json`（fixtureAdaptation 原文、N1~N5）、`51-audit-negatives.json`（note 零行断言 + id 52~56 审计行）、`restart-result.json`、`platform.identity.pre-restart.json`、`g3103-033.py`（Bearer 组装）、`30-context-pinning.json` raceGuard.browser、ORACLE.md「跨源证据」段（原方案）与 50-*.json fixtureAdaptation（改道）对照。

### D-039 — 03.7/C6 启用本地 stub LLM（OpenAI 兼容，127.0.0.1:18080）：零外呼零费用，真实模型仍 BLOCKED

**Decision**：本地以 `stub_llm.py` 提供 OpenAI 兼容 `POST /v1/chat/completions`（127.0.0.1:18080），平台经 `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL=stub-local` 环境变量接入（g3103-restart-llm.ps1，2026-09-25T00:45:15 重启，jar SHA `f9ce00a9…`，wrapperPid 46124）。stub 按（对 ExplanationService/TextToSqlService 源码核实过的）三类 system 契约应答：(1) 指标查询助手（SQL 生成与修复）→ `{intent,metrics,sql,assumptions}`，sql 为单表 SELECT + `snapshot_id` 等值 + dt 范围 + `ORDER BY dt LIMIT 7` 形态（该形态已过 SqlSafetyValidator）；(2) 输出 JSON（narrative）→ summary+suggestions（suggestions 非空是 UI「转决策草稿」按钮的 gate）+ limitations 明示「本地 stub 模型生成，无真实大模型」；(3) 只做措辞改写（/ai/explanations）→ 纯文本一句、零数字。stub 零外呼、零费用；`stub-local-key` 为非敏感演示值，stub 日志只记后 4 位（`key=...-key`），平台 secret env 不入日志。重启 mini-gate = S20260921_20 ACTIVE 唯一 + DEC|9 + USR|3（取代旧 S20260918_19-ACTIVE 断言；浏览器 leg 创建 DC-20260925005911-2892 后 DEC=10）。LLM 未配置时平台走 rule-based/template 回退并如实标注（limitations「规则回退模式：模型服务不可用」）；healthCheck（LLM_API_KEY && LLM_BASE_URL 非空）过后不静默回退。真实大模型接入仍 **BLOCKED**：需 provider 选型/凭据引用/数据外发许可/费用上限，缺一不得自行启用，也不得伪造模型输出宣称通过。

**Reason**：指导书 03.7/C6 要求浏览器端 AI 助手全流程（提问→解释→转决策草稿）可验收，而真实模型凭据在本环境不可得；narrative 的 limitations 字段把「本地 stub」如实透出给用户，使 UI 证据与事实一致，避免「假 AI」通过验收。

**Boundary**：`stub_llm.py` 属运行期输入件（attempt 目录），不入平台代码；不改动平台 AI 服务实现；`providerUsed` 字段如实区分 stub-local / rule-based / template；本条不宣称任何真实大模型能力。

**Evidence**：`stub_llm.py`；`stub_llm.log`（09-20 20:13 首启、09-20 20:44:46 与 09-25 00:45:15/00:59:10 的 sql_gen/narrative 实时请求，table/cols 与问题对应，key 掩码）；`restart-result-llm.json`（llmProbe: POST /ai/queries → providerUsed=stub-local, status=EXECUTED, suggestions≥1；miniGate；F2b 零 3306）；`platform.identity.json`（restartedAt 2026-09-25T00:45:06）；`80-02-chip-click.json`/`80-03-ai-enter.json` + 70-05/70-06 截图（浏览器腿 AI 交互）；`_probe_aiquery.json`（无 LLM 配置时的 rule-based/template 回退对照）；`ledger-stop-llm.json`（旧进程受控停止）。

### D-040 — G31-04 发现平台数据正确性缺陷 F-G4-1：ODS 装载对增量批次按 STATIC 整表覆写丢失历史分区（本批不改码，变更请求交总控）

**Decision**：G31-04 故障恢复演练（RunId `g3104b_20260925_104346`）暴露：同一业务日第二次起的摄入使已发布指标回归——S1 14 指标 → S2/S3/S4 均为 12 指标，行为类 pv/uv/dau/cart_add_cnt/fav_cnt 全部归零、buy_rate/cart_rate 消失，交易类指标因陈旧数据幸存而逐位不变。定性为**平台缺陷 F-G4-1**（非故障注入、非驱动产物）：`OdsLoadSql.insert`（spark-jobs `OdsLoadSql.scala:174`）对四张 ODS 表执行动态分区 `INSERT OVERWRITE TABLE … PARTITION (dt, hour)`，而 `EventOdsLoadJob` 未设置 `spark.sql.sources.partitionOverwriteMode`（默认 STATIC = 整表覆写）⇒ 增量 landing 批次（如 1 条良性 user_registered）到达即清空目标表全部历史分区；run2 SJR 实测 `odl` 在零故障注入下 SUCCESS 1>1，随后磁盘 ods_behavior_event/ods_trade_event/ods_product_event 0 parquet、ods_user_event 1 parquet、dwd_user_behavior_detail 仅剩 `_SUCCESS`，并链式传导 dim（维表仅 1 用户/0 商品）→ dwd → dws → ads → 发布快照。设计文档 §10.2/§10.3 与代码注释宣称的「INSERT OVERWRITE 幂等」只在 landing 全量重放同一分区内容时成立。修复属冻结 V3.0 设计的增量摄入语义级变更，按交接规则**本批不改码**，走变更请求交总控裁决；候选：① odl 显式 dynamic 覆写（仅覆写本批命中分区）；② INSERT INTO + ingest_batch_id 幂等去重；③ 规定 landing 必须全量重放。对比佐证：`TradeDwdJob.scala:26` 显式 dynamic 且 `:69` 空输入跳写 ⇒ 交易侧以**陈旧数据**幸存（SJR tdw 0>7 即 dwd_order_detail 保留 run1 的 7 行），并非正确性通过。

**Reason**：缺陷在正常 LOAD_ODS 路径即可触发，影响任何同业务日多次运行场景；G31-03 mini-gate USR=3 跨 3 个业务日未暴露，正因跨业务日运行不受影响。若不登记，后续批次可能把「陈旧交易指标 + 归零行为指标」误当恢复语义验证通过，或把该回归误判为恢复缺陷。

**Boundary**：本批次零代码修改、不宣称修复；在该缺陷闭环前，同业务日多次运行的指标一致性**不得**作为验收口径；04.3 的「重启前后指标逐位不变」以 S2(12) 对 S2(12) 如实成立（中断期间旧 ACTIVE 快照读数不变），不受本缺陷影响；变更请求不包含对冻结设计文档的原地修改。

**Evidence**：`target/v25-it/g3104b_20260925_104346/F-G4-1-REGRESSION-FINDING.md`（完整证据链）；`drill-logs/sql-regression-probe.{sql,out.txt}`（44 行 SJR 逐作业计数）；`drill-state.json` legs run2-retry/run3-resume/run4-final（S1~S4 指标表）；结果文档 `docs/verification/results/G31-04-FAULT-RECOVERY-RESULT.md` §5。


### D-041 — G31-05 发现平台缺陷：file_checkpoint.file_identity 列宽（VARCHAR(64)）装不下 HDFS 档文件身份（≈94 字符），HDFS 档断点续读失效（本批不改码，变更请求交总控）

**Decision**：G31-05 05.3（平台对 HDFS landing 的摄取，RunId `g3105_20260925_121316`）暴露：`HdfsLandingStorage` 生成的文件身份为 `hdfs:MD5-of-0MD5-of-512CRC32C:<64 hex>` ≈ 94 字符，而 `file_checkpoint.file_identity` 由 V8 迁移定义为 `VARCHAR(64)`（`V8__platform_ingestion_r3.sql:17`）→ 断点提交时 `MysqlDataTruncation: Data too long for column 'file_identity'`（`FileCheckpointMapper.insert`；`LocalFileIngestor.upsertCheckpoint` 路径）。平台按 at-least-once 设计如实记录「批次 1 manifest 已发布但文件断点提交失败……本轮数据仍可交付，后续可能重复投递」：**批次交付不受影响，但 HDFS 档的同文件断点续读失效**——同文件重扫将全量重复投递。LOCAL 档（本地路径 identity，短于 64）不受影响：05.5 attempt-2 摄取 `noNewData=true`（batchId=2 manifest READY 复用）为证。修复属 schema 变更（加宽列或改 identity 格式），按交接规则本批不改码，走变更请求交总控裁决；候选：① `file_identity` 加宽至 VARCHAR(128)+（需新迁移，追加式）；② identity 改为定长摘要（hash 后进列）；③ checkpoint 键改承载 identity。登记为缺陷而非放水：交付语义（at-least-once）与日志口径均为设计内行为，门判据不受影响。

**Reason**：该缺陷使 HDFS 档在真实增量采集场景下失去幂等防线（noNewData 永不成立），与 F-G4-1（D-040）同为「增量场景」语义缺口；若不登记，后续 HDFS 批次可能把重复投递误判为 Flume 重放语义缺陷，或把断点失效误当通过。

**Boundary**：本批零代码修改、不宣称修复；不改变 05.3 批判据（batchId=1 50/0 manifest READY 已达成）；在该缺陷闭环前，HDFS 档的「同文件重扫幂等」不得作为验收口径；变更请求不包含对已发布迁移（V8）的原地修改，只允许追加式新迁移。

**Evidence**：`target/v25-it/g3105_20260925_121316/logs/platform-start1.log` L106-113（identity 明文 94 字符 + `Data too long for column 'file_identity'` 全栈）；`evidence/ingestion-hdfs.json`（batchId=1 50/0 SUCCESS 照常交付）；`evidence/ingestion-local2.json`（LOCAL 档 noNewData=true 对照）；`analytics-server/platform-app/src/main/resources/db/meta/V8__platform_ingestion_r3.sql:17`；结果文档 `docs/verification/results/G31-05-WSL-SINGLE-NODE-CHAIN-RESULT.md` §4。

### D-042 — WSL 单节点档两条运行约束（运维绑定）：mxp exportDir 必须传 file:/// URI；Derby create=true 不得预建 metastore 目录

**Decision**：G31-05 全程沉淀两条 WSL 档（Linux 路径 + defaultFS=HDFS + 嵌入式 Derby）运行约束，后续任何 WSL/HDFS 批次必须遵守：(1) **MetricExportJob 的 `--exportDir` 必须传 `file:///` 绝对 URI**——该作业以 Hadoop `new Path(exportDir)` 定位导出目录，无 scheme 的 `/mnt/d/…` 会按 defaultFS（HDFS）解析而「导出消失」（05.4 首轮 mxp 即因此不可恢复丢失，重跑 `file:///mnt/d/…` 成功）；Windows 平台管线的 `D:\…` 由本地 FS 解析、不受影响，故非产品缺陷，不改码。(2) **Derby `create=true` 的 metastore 目录不得预建**——预建空目录（哪怕 0 文件）即触发 `XJ041→XBM0J: Directory already exists`，SessionHiveMetaStoreClient 实例化失败、sci 失败；本批同一陷阱出现两次（05.4 WSL `metastore_db`、05.5 attempt-1 Windows `derby-metastore-w`），均为预备脚本 `mkdir -p` 所致，删除空目录后即恢复。配套：WSL 档 stop-dfs/stop-daemon 必须带与本 run 启动一致的 `HADOOP_PID_DIR`（否则 pid 文件找不到、stop 静默无效——交接项5 已记录，05.6 复证）。

**Reason**：两条均为「环境配置错法在两个平台（Windows/Linux）上表现不同」的陷阱，不绑定记录则下批大概率重蹈；mxp 首轮导出已实际丢失，代价已付。

**Boundary**：不宣称 mxp/平台代码缺陷、不触发代码变更；约束只绑定「WSL 单节点 HDFS 档 + 嵌入式 Derby」运行形态；Windows 平台管线行为不适用本条（file:/// 约束在 Windows 侧非必需，Derby 约束为跨平台通用）。

**Evidence**：`target/v25-it/g3105_20260925_121316/scripts/spark-mxp-rerun.sh`（file:/// 重跑成功）+ `evidence/ads-diff.txt`/`chain-summary.txt`（首轮 DIFF-MISSING → 重跑 7+1 全同）；`evidence/pipeline-local.json` + `landing/logs/pipeline-1-INIT_SCHEMA-sci-*.log`（attempt-1 XBM0J 全栈）与 `evidence/pipeline-local2.json`（attempt-2 SUCCESS 对照）；`scripts/hdfs-stop.sh`（带 HADOOP_PID_DIR 停机，HDFS-PROCS-GONE）；结果文档 §4。

### D-043 — G31-07 交付形态与推荐裁定边界：「一次可复现产品验收」以当日实链＋只读终验＋界面引用构成；G31-06 真实 AI 正式 BLOCKED 终验登记；推荐裁定=限定通过候选，签收权归总控

**Decision**：G31-07（部署交接与本版验收）按以下边界执行并收口：(1) **验收运行形态**——按 V3.0 L188「按最终首版范围做一次可复现产品验收」，本版验收=「当日实链（G31-05 WSL HDFS 整链 + G31-04 故障恢复，均为 2026-09-25 证据时钟当日真实执行且全 PASS）+ 对常驻平台栈的只读终验腿（9 腿：健康/双账号登录/profiles+runs 留证/ACTIVE=S20260921_20 唯一/overview 14 指标==item4 oracle 容差 0.0005/F2b 零 :3306/stub LLM 探针/进程身份链）+ 界面证据引用（BATCH-W/G31-02 向导/G31-03 C6）」三部分构成，不重跑任何整链；07.2 终验驱动 3 轮迭代（载荷 `data` 直接数组形状、PowerShell 递归 `,$found` 包装+管道成员枚举）均为验证工具层缺陷，产品代码零改动。(2) **G31-06 正式终验登记**——真实 AI provider 无受控凭据（provider/凭据引用/数据外发许可/费用上限四要素缺），按「缺少真实模型凭据时不能伪造建议来宣称通过」约束正式 BLOCKED，不重试、不伪造；stub 本地替身（零外呼零费用）已交付并纳入终验腿 8（providerUsed=stub-local）。(3) **交付物**——`docs/handover/deployment-freeze-20260925.md`（07.1 冻结）、`docs/verification/results/G31-07-DEPLOY-HANDOVER-ACCEPTANCE-RESULT.md`（07.3 验收报告）、滚动清单对账（07.4）。(4) **推荐裁定=限定通过候选**：通过面=阶段 7 五任务全部有可复核证据+终验 9/9 全绿+交接冻结齐备；限定面=F-G4-1（D-040）与 D-041 两平台缺陷未修、真实 LLM 不可用、REMOTE_CLUSTER/YARN/多节点/共享 HMS/真实外部商城未验、论文答辩材料 post-freeze 未写。**最终裁定与签收权归总控（V3.0 L212+L243：代码 Agent 不可自行宣布完整验收），本报告仅为候选。**

**Reason**：V3.1 指导书末批原文不可恢复，验收形态从冻结 V3.0 阶段 8 L185-191 推导（推导链见批次计划 §0）；「不重跑整链」依据 V3.0 滚动验证清单模式——同日实链证据已落盘可复核，重复执行不增加证据力徒增环境风险；验收报告若把未交付项隐去即构成 L191 禁止的伪装，故限定面逐条列明并给出候选裁定而非宣称通过。

**Boundary**：本批零产品代码修改、不 commit 不 push、3306 零接触（F2b 断言）；「限定通过候选」不是验收通过结论，总控可退回/追加验证项；本批不证明 REMOTE_CLUSTER、真实 LLM、YARN/多节点、共享 HMS、真实外部商城授权样例；论文/答辩材料按 L190 在结果冻结后统一写，不在本版交付物内；Doris 对比不伪装成本版能力；终验全程零写库（除登录会话与 AI 探针两条设计内记录）、零重启。

**Evidence**：`target/v25-it/g3107_20260925_142915/`（scripts/g3107-acceptance.ps1 + evidence/10 份 JSON + drill-state.json outcome=PASS）；`docs/handover/deployment-freeze-20260925.md`；`docs/verification/results/G31-07-DEPLOY-HANDOVER-ACCEPTANCE-RESULT.md`；`docs/verification/STAGE7-REMAINING-SCOPE-20260919.md`（07.4 对账）；批次计划 `docs/verification/batches/BATCH-G31-07-HANDOVER-ACCEPTANCE-PLAN.md` §0 推导链。

### D-044 — 总控裁定（2026-09-25）：G31-07 证据包与部署交接签收完成、本版产品验收暂不签「限定通过」，F-G4-1/D-041 先修后验（G31-08/G31-09 两个短批次）；并更正 G31-07 登记中的「16 个未提交文件」错误计数

**Decision**：总控 2026-09-25 对 G31-07 验收报告作出**分对象裁定**并给出修复路线：(1) **G31-07 证据包与部署交接：签收完成**——终验 9/9 检查有记录支持，其检查对象是已运行平台的状态，并非重新执行一次完整数据链。(2) **本版产品验收：暂不签「限定通过」**——F-G4-1（同业务日再次增量运行可能清空历史 ODS 数据，D-040）与 D-041（HDFS 文件身份写不进 checkpoint，重试可能重复摄取）直接影响持续分析的正确性与幂等性，属验收拦截项而非可保留的限定条款；应先修复并做小规模针对性回归，再重跑受影响链路与 G31-07 终验，不必每改一次都重跑全部大规模测试。(3) **真实 LLM 与 REMOTE_CLUSTER 继续明确标为未验收范围**——stub 能力不得写成真实模型通过；WSL 单节点通过不得写成远程集群通过。(4) **修复拆两个短批次**：G31-08 先修同日增量写入，判据=**原有数据保留＋新增数据计入＋重跑不重复**；G31-09 再修 checkpoint 列宽与索引约束，判据=**同一 HDFS 文件重试不重读**。**硬约束：仅开启动态分区覆盖不足以保证同一分区内旧记录不被覆盖，G31-08 修复方案必须覆盖同分区内旧记录保留。**(5) **登记更正**：G31-07 冻结文档 §1 / 验收报告 / D-043 / PROJECT_STATUS / CURRENT_BATCH 所述「16 个未提交文件」不准确——该计数来自本会话初始 git 快照（质量规则 v2 改动集，后已随 8b4e4da/2a11b60/b25b47f 等提交收编）；冻结时点工作树实际承载 **34 个已跟踪修改**（30 个产品/夹具/脚本 + 4 个登记文档，+1412/−172）与一批新增未跟踪文件（9 个新 Java 源/测试、交接/计划/结果文档、备份等）。该在途改动集按交接指令原样保留、G31-04~07 各批零触碰；**提交前必须按批次列出确切文件归属，避免把其他在途工作一起提交**（归属清单见 G31-07 验收报告 §9 附录）。

**Reason**：「验收工作完成」与「产品通过验收」是两个裁定对象，须分开签收；F-G4-1 触发路径是正常 LOAD_ODS 增量（任何同业务日多次运行场景即触发），D-041 使 HDFS 档失去幂等防线，二者都落在「持续分析正确性」底线上，不能以限定条款形式带病通过；「16 文件」错误计数若不更正，后续提交将按错误清单执行，存在把在途工作混入提交的实际风险。

**Boundary**：本裁定追加于 G31-07 已有证据之上，不改写其已登记结论（验收报告原文保留、以 §9 追加裁定节）；G31-08/G31-09 未闭环前，同业务日多次运行指标一致性与 HDFS 同文件重扫幂等**不得**作为验收口径；本裁定不构成对冻结 V3.0 设计文档的原地修改授权；不 commit 不 push（等用户安排）；3306 零接触不变。

**Evidence**：总控裁定原文（本会话 2026-09-25）；`git status --porcelain` 取证（34 M + untracked 清单，diff --stat 合计 +1412/−172）与 `git show --stat b25b47f`；`git log --oneline -3 -- …/HdfsLandingStorage.java`（该文件最后提交=b25b47f，现行 M 为其后在途改动）；D-040 / D-041；G31-07 验收报告 §9 与冻结文档更正注记。

### D-045 — G31-08（D-044④ 第一短批）F-G4-1/D-040 修复语义裁定：分区作用域「读-合并-去重-覆写」＋ new-wins ＋ 空批零写入；判据 G1–G6 由 OdsMergeIncrementalSpec 7/7 钉住，全 spark 套件 329/329

**Decision**：G31-08 按 D-044④ 判据（原有数据保留＋新增数据计入＋重跑不重复；硬约束=同分区旧记录不被覆盖）落地修复并收口：(1) **修复语义**——`EventOdsLoadJob` 四 ODS 写出改为分区作用域「读-合并-去重-覆写」：`newRows`（现行 SELECT 模板输出）∪（目标表 semi-join 命中分区 `(dt,hour)` anti-join `newRows.event_id`）整体 `INSERT OVERWRITE … PARTITION (dt,hour)` 写回命中分区；写期间 `partitionOverwriteMode=dynamic`、`try/finally` 还原防跨作业泄漏；**空批零写入**（旧实现空 STATIC INSERT 会清表）；非命中分区物理不动；同批重放 anti-join 幂等；批内不去重保持 v1 现状。(2) **new-wins 显式语义**：同 event_id 重投递时旧副本让位新批（`ingest_batch_id` 取新批）——at-least-once 下重投递=同一事件的再确认，取新批即「重跑不重复」，三项判据在该语义下同时成立。(3) **模板字节不变**：`OdsLoadSql` 四条 `*FromLanding`（insert() 委托 selectFromLanding()、topicWhere 返回原 where 串）字节稳定，A9/A9d/SqlTemplateSpec 契约零变化；merge 段 SELECT 列序=`OdsV2Columns.columnNames`（与 A12d 同源）。(4) **判据钉住**：新增 `OdsMergeIncrementalSpec` 7/7（G1 原有保留/G2 新增计入/G3 重放幂等/G4 同分区旧记录并存=硬约束判别/G5a 单主题不清他表/G5b 全拒绝零写入/new-wins+G6 conf 还原；批次 A→B→B-replay→C→D→E 自 golden 克隆；自带会话**不预置**覆盖模式——内置 SQLConf 键 getOption 恒 Some(出厂 STATIC)，以「跑前捕获==跑后还原」为 G6 判据）；全 spark 套件 **329/329、41 套件、JDK8=True**，`$BaselineSpark` 322→329 按惯例留痕（首跑 DRIFT=基线比对待同步，非测试失败；MATCH 复跑 exit=0）。

**Reason**：D-044④ 硬约束排除「仅开动态覆盖」方案（同分区旧行仍被覆写）；整表覆写缺陷已在 G31-04 run2 实测（3 表 parquet 归零）。anti-join 合并以表内 event_id 唯一性为前提（与 v2 现行查询去重语义一致），unionByName 类型面仅 ingest_batch_id INT→BIGINT 无损加宽。回归套件自带会话不预置 `partitionOverwriteMode` 是判别力前提：P2TestSupport 预置 dynamic 会掩盖「作业自设自还」这一被测事实。

**Boundary**：测试域=in-memory catalog+`USING parquet`（spark-hive provided），**≠ 在产 Hive metastore**——「在产成立」归 D-044⑤ 合并重跑 + G31-07 终验重跑，本批不宣称；平台链路（LOCAL/HDFS 摄取与编排）本批未重跑；G31-09（D-041 checkpoint 列宽，追加式迁移 V32）未动，**HDFS 同文件重扫幂等继续不得作为验收口径**；不改已发布迁移、3306 零接触、未 commit 未 push；触碰文件归属清单见批次结果 §5（提交时不得混入其他在途工作）。

**Evidence**：`spark-jobs/src/test/scala/com/graduation/analytics/OdsMergeIncrementalSpec.scala`（7/7 全绿，Run completed 22.5s）；spark 套件日志 `v25tests-dev003c_20260925_161641_10ddd8`（329 DRIFT 报告）与 `v25tests-dev003c_20260925_162007_afe66c`（MATCH PASS exit=0）；`scripts/run-tests.ps1` §2026-09-25 基线条目；计划 `docs/verification/batches/BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-PLAN.md`；结果 `docs/verification/results/BATCH-G31-08-FG41-SAMEDAY-INCREMENTAL-RESULT.md`；D-040 / D-044。

### D-046 — G31-09（D-044④ 第二短批）D-041 修复收口：V32 追加式加宽 file_identity 64→255，94 字符字节账双证钉死；schema 层幂等前提闭合，行为级「同一 HDFS 文件重试不重读」归 D-044⑤

**Decision**：G31-09 按 D-044④ 判据落地修复并收口：(1) **修复形态**——追加式迁移 `V32__file_checkpoint_identity_width.sql` 单条 `ALTER TABLE file_checkpoint MODIFY COLUMN file_identity VARCHAR(255) NOT NULL DEFAULT ''`：MODIFY 自动维护所属唯一键 `uk_ckpt_source`/索引/外键，无 DROP/ADD INDEX；存量 LOCAL 行语义不变；V8/V17 已发布内容零改动（静态门禁 `FileCheckpointIdentityWidthMigrationScriptTest` 5/5 钉住）。**宽度 255 依据**：`uk_ckpt_source(runtime_profile_id, source_id, file_path VARCHAR(500), file_identity)` utf8mb4 单键字节上限 3072 —— 8+8+2000+1020=3036 ≤ 3072（取 300 则 3216 超限，迁移直接建键失败）；94 字符 + 161 余量。(2) **身份真实长度钉死 94 字符**（`hdfs:` 5 + 算法名 `MD5-of-0MD5-of-512CRC32C` 24 + `:` 1 + 64 hex）：`FileChecksum.getBytes()` 经 `WritableUtils.toByteArray` 返回 `DataOutputBuffer.getData()` 的**原始缓冲**（不按写入量裁剪）——write() 实写 28 字节（4+8+16）但 ByteArrayOutputStream 初容量 32 未扩容 → 返回 byte[32] 含 4 字节零余量 → 64 hex；**双证**=hadoop-common 3.3.4 javap 字节码链 + 平台运行时实测（attempt-3 `expected: 86 but was: 94`）。长度推导修正轨迹：计划文档 ≈95（估算，保留原文）→ 运行时实测 94 → 中间 86 推导（「toByteArray 会裁剪」错误假设）被字节码推翻，**以 94 为准**。(3) **判据分层**——schema 层前提（94 字符身份原样落库 + uk_ckpt_source 语义不变 + 真库迁移生效）本批闭合：isolated 档 RunId `g3109iso_20260925_180727` 全绿（隔离 62/62 MATCH + schema 门 `AnalyticsIsolationFlywayIT` 2/2 + p1.it `SourceRegistryMigrationMySqlIT` 7/7 V1→V32 真迁移）；default 档 1150（F=0 E=0 S=2）MATCH exit=0，基线 1096→1150 同步（+5=本批静态门；+49=09-23 后各批已在自有档验证未回同步的存量，归因留痕供提交清单；并修正基线图 1101 既有笔误）。**行为级判据「同一 HDFS 文件重试不重读」本批不宣称**，归 D-044⑤ 合并重跑（真实 HDFS/Flume/平台链 + 正式库 V32 应用 + G31-07 终验重跑）。(4) **顺手修复被暴露的三个潜伏缺陷**：① `SourceRegistryMigrationMySqlIT.context()` 自 31d5ed5 收紧 TestRunContext 构造校验后传占位登记值**从未可绿**——改 requiredProperty 真实 scope 登记且 METRIC_DB≠META_DB；② `FROZEN_RUNTIME_PROFILE_COLUMNS` 缺 V22 `landing_layout`（该 IT 自 V22 落地未实跑的漂移）——补齐冻结 25 列；③ run-tests.ps1 基线图 1101 与自身注释 1150 矛盾（半截同步笔误）——随本次同步修正。

**Reason**：D-041 的根因是纯存储宽度缺陷（V8 取值域按 LOCAL 时间戳设计，HDFS 接入后身份 94 字符超宽 → checkpoint INSERT MysqlDataTruncation → 断点行落不下来 → 同文件重试全量重读），修复最小充分动作就是加宽列，任何夹带（重建索引/改语义/数据修复）都扩大变更面违反复冻结论；255 是 uk_ckpt_source 字节预算下最大可行宽度，既修复又留余量。长度推导坚持双证（字节码+运行时）是因为计划估算 ≈95 与中间推导 86 相互矛盾，不钉死会污染后续回归断言与交接文档。潜伏缺陷 ①② 意味着 p1.it 隔离面自 31d5ed5/V22 起实际处于「从未真正跑过」状态，本批首次让它真实通过，属验证基建修复而非放水。

**Boundary**：per-run 3307 库 ≠ 正式 analytics_meta——V32 上正式库归 D-044⑤ 窗口，3306 全程零接触（V32 永不在 3306 运行）；行为级 HDFS 同文件重扫幂等在 D-044⑤ 闭环前**继续不得作为验收口径**（D-044④ 边界延续）；身份串格式所有权在 `HdfsLandingStorage`（格式变更=新变更请求）；不改已发布迁移、冻结 V3.0 文档零触碰、未 commit 未 push；触碰文件归属清单见批次结果 §6（提交时不得混入其他在途工作，分组另见 G31-07 验收报告 §9 / G31-08 结果 §5）。

**Evidence**：`analytics-server/platform-app/src/main/resources/db/meta/V32__file_checkpoint_identity_width.sql`；静态门禁 `analytics-server/platform-app/src/test/java/com/graduation/analytics/migration/FileCheckpointIdentityWidthMigrationScriptTest.java`（5/5）；isolated attempt-4 日志 `C:\Users\ASUS\AppData\Local\Temp\g3109-iso-run4.log`（RunId `g3109iso_20260925_180727`，`=== SUMMARY isolated=0 p1it=0 ===`，62/62 MATCH + schema 门 2/2 + p1.it 7/7）与明细 `...\v25tests-g3109iso_20260925_180727\isolated-mvn\`；attempt-1~3 日志 `g3109-iso-run{,2,3}.log`（attempt-3 = 86→94 实测证据 + V22 漂移暴露）；default MATCH 复跑日志 `v25tests-dev003c_20260925_181516_4c125f`（analytics 1150 F=0 E=0 S=2 MATCH，三树 1275=基线 1275，exit=0）；`scripts/run-tests.ps1` §2026-09-25 G31-09 基线条目；计划 `docs/verification/batches/BATCH-G31-09-CHECKPOINT-IDENTITY-PLAN.md`；结果 `docs/verification/results/BATCH-G31-09-CHECKPOINT-IDENTITY-RESULT.md`；D-041 / D-044 / D-045。

### D-047 — G31-10（D-044⑤ 合并重跑）收口：V32 正式 3307 落地、F-G4-1/D-041 行为级证据取得、终验锚点迁移 S20260921_20 → S20260901_23；M3 暴露「发布无条件 + consumed manifest 保持 READY」语义待总控裁定

**Decision**：G31-10 按用户批准的四停机判据执行 D-044⑤ 合并批次并收口（零源代码改动，全部为链路行为证据）：(1) **V32 正式落地**——平台以正式 3307 `analytics_meta` 受控启动，Flyway 自动迁移 `FLY|32|file checkpoint identity width|success=1`；前态先证（V1–V31 共 30 行、`file_checkpoint.file_identity` varchar|64、checkpoint 19 行）、迁移后 V8–V31 逐行与前态全等（30→31 行仅新增 32）、列宽 varchar|255；迁移前双库 dump 于 attempt `backup/`（meta sha256 `7edab792…`、metric `b79e5571…` + `restore.md` 恢复命令），全程 3306 零接触。(2) **F-G4-1 行为级闭合**——M1 基线（golden 50 → S_A=S20260901_21，与 G31-04 S1 指纹 14/14 全等）→ M2 同日增量（oracle-first：`oracle.py --s1-check` 13/13 先行、`oracle-expected-sB.json` run2 前钉档；批 B 3 条 → run2 → S_B=S20260901_22 14/14==钉档，pv 7→8/fav_cnt 2→3/同分区合并不清零）→ M3 重放（ingest noNewData=true && recordCount=0；run3 值不变）——D-045 分区作用域「读-合并-去重-覆写」在真实 Spark/parquet 表成立。(3) **D-041 行为级解除**——P5 HDFS 腿：profile1 仅改 landingUri 切 `hdfs://127.0.0.1:19000/landing/g3110_20260925_191753`（G31-05 05.3 机制）→ ingest#1 2/0 + checkpoint 恰 1 行 `hdfs:…` 且 LENGTH=CHAR_LENGTH=**94** 落 varchar(255) 列 → **ingest#2 同文件重试 noNewData=true && recordCount=0（未重读，行为级判据成立）** → ingest#3 新文件 1/0 未误拦 + checkpoint 2 行均 94 → profile 切回 file://（05.5）→ HDFS 停机数据保留；P5 后无任何 pipeline run。(4) **oracle 三方对账 + 终验重跑**——P6：交付副本（accepted/）与钉档输入逐字节 sha256 全等、交付副本复算==钉档值（14/14 tol 0.0005）、metric 库恰 1 行 ACTIVE=`S20260901_23` + 14 metric_value、API overview 同锚 14 码、三方全等 + definitionVersion 一致（9/9）；P7：g3107 变体 9 腿全绿（leg5 ACTIVE 唯一=`S20260901_23`、leg6 指纹==P6 oracle、leg7 F2b 零 `:3306` + 3307 双 URL + landing 有据、leg8 stub-local/EXECUTED、身份链 java(34828)←wrapper(30348)）。**终验锚点正式从 `S20260921_20` 迁移为 `S20260901_23`**（后续恢复脚本 mini-gate 硬编码须同步，item3-restore 同款风险显式登记于结果 §6）。(5) **M3 语义偏差暴露（待总控裁定）**——计划预期重放 run3 被 `RUN_EMPTY_LANDING` fail-closed 拒绝；实际 `PipelineService` 发布无条件（publish 不经门禁）+ consumed manifest 保持 READY + `LandingManifestSelector` 扫描最新 READY 非空同源清单 → run3 以重放批再发布并产出值不变的新快照 S_C（D-045 去重保证零重复计数）。本批按修订语义收口（断言 noNewData + valuesUnchanged + ACTIVE 漂移如实记录）；是否将发布收紧为 fail-closed（对账 noNewData 时拒绝发布）**不是修复缺陷而是产品语义裁定**，留总控。(6) **run4 flake 诚实登记**——F1 首跑 id 24 于 BUILD_DWD 命中已知环境坑 spark-local-loopback-jar-download-hang（RUN_JOB_FAILED，非质量门行为），换独立 Idempotency-Key 重试 id 25 后取得期望 FAILED/PIPELINE_QUALITY_FAILED，期间旧 ACTIVE（S_C）overview 仍 14/14 可读（判据3 后半成立）。

**Reason**：D-044 将 F-G4-1/D-041/V32 正式库/终验四事合并为一个批次是因为它们共享同一受控停机窗口与同一新终验锚点，逐项重跑全量链只会重复同一链路证据；合并后每项仍保持独立判据与独立证据（四停机判据逐条对答见结果 §3）。oracle-first（run2 前钉档）+ P6 交付副本逐字节复核的组合，既满足「期望值先于被测行为独立产生」又满足「输入=实际交付副本」，两者缺一即退化为自证。M3 偏差不在本批强行修复：发布语义是产品行为，验证批无权改语义，且 D-045 去重已保证该现状不产生数据错误（仅多产出一个值相同的快照），如实暴露交总控裁定是唯一诚实路径。

**Boundary**：全部"通过"限**当前 WSL 单节点环境**（本地 smoke Hadoop conf + 单机 Spark local + 3307 双库），不证明远程集群或共享 Hive Metastore；终验登录与 AI 探针产生设计内审计记录（非"完全只读"）；真实 LLM（G31-06）维持唯一 BLOCKED 未验收。P5 运行时放行：常驻平台 JVM 无 `HADOOP_USER_NAME`，以 HDFS 服务端 `chmod 777` 放行 landing 树（conf 零改动、平台零重启；复跑需先停平台或按同法放行）。3306 永久冻结零接触、V32 永不在 3306 运行；`V25_IT_*` 口令零落盘零 argv 零 git；3307 root 口令仅经 WSL `MYSQL_PWD`；push 授权已用尽，仅本地提交（提交域=docs，attempt 证据域 `target/` 不跟踪、盘内留存）；触碰文件归属清单见批次结果 §8。

**Evidence**：计划 `docs/verification/batches/BATCH-G31-10-D044-5-MERGED-RERUN-PLAN.md`；结果 `docs/verification/results/BATCH-G31-10-D044-5-MERGED-RERUN-RESULT.md`（§2 验收→证据映射表、§3 四判据对答、§5 偏差登记、§6 锚点迁移）；attempt `target/v25-it/g3110_20260925_191753/`：`drill-state.json`（P1–P4/P5 legs：anchorS_A/S_B/S_C、m1、m2-run2、m3、f1-run4-flake、f1、p5、p5-d041、v32、start）、`precheck/02-flyway-before.txt`、`logs/sql-v32-after.out.txt`、`backup/`、`jars/SHA256S.txt`（spark-jobs `71c0fc88…`、platform-app `8366bc12…`）、`evidence/p5-*.json|txt`、`oracle/oracle.py`+`oracle-expected-sB.json`+`p6-three-way.json`（9/9）+`p6-api-overview.json`、`terminal/acceptance-drill-state.json`（P7 9/9 PASS）与 `evidence/`（9 腿散件）；D-040 / D-041 / D-044 / D-045 / D-046。

### D-048 — 总控安全裁定落盘并执行收口：3307 root 凭据已进 tracked 文档构成泄露 → 轮换完成（三 root 条目、TCP/socket 双路正负验证、数据完整性五点核对全等 G31-10 收口态）＋ 当前文档改凭据引用（credref 通道）＋ 待交付材料扫描 22 处命中全定性；轮换前凭据漂移异常成因无法归因、如实登记；M3 语义裁定归 G31-11

**Decision**：总控 2026-09-25 裁定「需要轮换该凭据、将当前文档改为凭据引用，并检查待交付材料；**不擅自改写历史提交**」及「应按批次保存完整源码，并将必要的脱敏证据归档到持久位置，不能只依赖会被构建清理的 target/。数据库备份继续受控保存，不直接公开上传」——本条登记该裁定的执行收口：(1) **凭据轮换完成**。WSL 3307 隔离实例（MySQL 8.0.41）三 root 条目 `root@%`/`root@127.0.0.1`/`root@localhost`（均 mysql_native_password）全部改设新值；验证 = TCP 路径新值 tcp_new=1、socket 路径新值 sock_new=1、**旧值在 TCP 与 socket 双路均被拒（负测）**；数据完整性五点核对与 G31-10 收口态全等：正式库 `stage7q1_20260918_152245_analytics_meta` flyway 31 行（rank 31 = V32「file checkpoint identity width」success=1）、`file_checkpoint.file_identity` varchar(255)、metric 库 ACTIVE 唯一 `S20260901_23`、sys_user=3、decision_task=12。新值仅存仓库根 `credref-mysql3307-root.properties`（gitignored，`.gitignore:86 credref-*.properties`），零落文档/日志/git/argv。(2) **三处 tracked 凭据面改为凭据引用**（各自编辑前备份 `*.bak-20260925-d048`）：① `docs/acceptance/v25-w02-w03-wsl-runtime-20260914/raw/scripts/start-isolated-mysql-3307.sh` 头部新增 `MYSQL3307_ROOT_PASSWORD` env 必填 fail-fast 块，9 处 `-p123456`→`-p"$ROOTPW"`、2 处 `BY '123456'`→`BY '$ROOTPW'`，字面量归零；以真实值重跑全绿（tcp_ok/sock_ok/读写往返，rc=0）。② 同 acceptance 目录 `README.md` §4.2 五处（口令行/URL 形式/命令行/二进制调用/接入结论）改为「已轮换作废（2026-09-25 D-048）」+ credref/env 引用；`raw/53b-w03-mysql-accounts-and-connectivity.txt` 等**历史证据原件按证据完整性原则保留原样不改写**，以 blockquote 声明其中口令为当时值（已作废）。③ `docs/verification/batches/BATCH-G31-10-D044-5-MERGED-RERUN-PLAN.md` 两处（§头部正式库对、§5）改 credref 引用。(3) **待交付材料扫描分类**：全仓 tracked 文件 `123456` 命中 22 处（docs/acceptance 之外）逐一定性——a) 测试向量子串非凭据：`123456789` CRC 校验值（MetricExportManifestChecksumTest / MetricExportChecksumSpec / PROJECT_STATUS:607）、`0123456789abcdef…` MD5Hash 字符串（AnalyticsIsolationFlywayIT:127，其 DB 配置走 `TestIsolationGuard.requiredProperty`、无硬编码凭据）；b) 3306 时代开发库凭据残留于历史文档/脚本（status-history、guidance/history、handover-2026-09-07、accept/smoke/start 脚本、generator README/测试等）——属既有裁定范围、不在本条（3307）处置面；c) docs/acceptance/** 历史记录原件保留（同 ② 原则）；target/ 不跟踪不入交付。(4) **不擅自改写历史提交**：历史 runner 脚本（g3110-driver/g3110-precheck/p6-three-way 等）内嵌旧凭据原样保留（历史事实），归档交付面以脱敏副本提供（归档批执行）；git 历史零改写（无 rebase/amend/filter-branch）。(5) **总控更正随裁登记**：`PipelineService.java:641` 已有 F-88 发布前质量门，G31-10 结果 §5-1 所述「发布无条件（publish 不经门禁）」表述不准——实际缺口在 **consumed-input 识别**（consumed manifest 保持 READY 且选择器重扫 → no-new-input 场景仍再发布），非「无质量门」；M3 语义裁定 = no-new-input → 不发布/no-op/ACTIVE 不变、消费状态**独立记录**（既非 manifest READY 单独判定、亦非值相等去重）、多待处理批次逐批处理不遗漏、失败重试绑定原批、显式重算入口且必须带理由，落地与四场景验证（无新输入不发布 / 有新输入能发布 / 失败重试仍有效 / 连续两个待处理批次不遗漏）+ 一次受影响终验 = **G31-11 批次**，真实 AI 与远程集群不并入。

**Reason**：凭据进入 tracked 文档即构成泄露，改文档不能撤销泄露，轮换是唯一有效处置；轮换与引用化必须同批完成，否则文档中的值立即成为下一次泄露源。三 root 条目必须同批同值（`root@%` 管 TCP、`root@localhost` 管 socket/本机维护，漏一条目即留后门）；旧值双路负测是「确实死透」的直接证据；数据完整性五点核对证明轮换未伤数据（V32 迁移成果与终验锚 `S20260901_23` 均未受影响）。历史证据原件不改写 + 声明标注失效，是答辩可追溯性与安全收口的平衡点（「不擅自改写历史提交」的延伸）。

**Boundary**：**异常登记（无法归因，如实披露）**——轮换窗口内出现凭据漂移：21:52:18 patched start 脚本 S4 探针以旧值 TCP 认证成功，约 5 分钟后同值 TCP 被拒；期间仅两次中止的轮换尝试（逐行解析重建显示未执行任何 ALTER），错误日志无 SHUTDOWN/重启且 verbosity=2 不记录认证失败 → **成因无法归因**；以 pass-3（socket 路径）对三 root 条目统一重设新值收口，泄露面闭合。pass-2 中途自锁（`root@localhost` 改值后脚本自身 `MYSQL_PWD` 旧值失效 → 中止于 `root@'%'` 前）属操作过程记录，非数据事件（credref 先写后改值，无「无凭据窗口」）。本条全程 3306 零接触；3307 root 口令仅经 WSL `MYSQL_PWD`/credref 通道，不入任何日志/转录/文档；push 授权已用尽、仅本地提交；归档义务（源码快照 + 脱敏证据 → 仓库外持久位置 `v3-archive/g3110/`、DB 备份受控不上传）按裁定另行批次执行。

**Evidence**：`target/v25-it/g3110_20260925_191753/evidence/d048-rotation-evidence.md`（§1 背景 / §2 前态+异常登记+pass-2 中止 / §3 动作 / §4 验证表 / §5 文档收口 / §6 错误日志取证）；轮换脚本 `…/scripts/d048-rotate-pass3.sh`（credref 读取 + 40-hex 校验 + 三 ALTER + 双路验证 + 双路负测）；重跑日志 `…/evidence/d048-startscript-rerun.log`；编辑前备份 `*.bak-20260925-d048`（start 脚本 / W03 README / plan doc / DECISION_LOG / CURRENT_BATCH / PROJECT_STATUS）；扫描定性依据 = tracked 全集 grep `123456` 逐文件上下文复核（AnalyticsIsolationFlywayIT:127 / MetricExportManifestChecksumTest 等）；总控裁定原文（本会话 2026-09-25）。

### D-049 — G31-11 M3 发布语义收紧落地（消费台账 V33 + FIFO 选择器 + 显式重算入口）＋ 发布前质量门陈旧行缺陷修复（D-049x）＋ V33 error 1567 修复决策；按 D-048 §(5) 四场景+受影响终验全 PASS，请求总控签收

**Decision**：按 D-048 §(5) M3 语义裁定实现并验证，子决策逐条登记——**(a) 消费台账**：新增表 `pipeline_batch_consumption`（V33，唯一键 (source_id, batch_id)），列 status/consumed_by_run_id/first_consumed_by_run_id/target_snapshot_id/publish_count/recalc_count/last_recalc_reason/last_recalc_by/last_recalc_at/consumed_at/created_via(PIPELINE|BACKFILL_V33)；**仅在 PUBLISH_METRIC ok 后写入**，FAILED run 零写入。**(b) 选择器重写**：`LandingManifestSelector` 改 FIFO（最旧未消费 READY 非空同源 manifest），retry 钉批胜过 FIFO，返回 `Selection(manifest, readyButConsumedCount)`。**(c) 无新输入语义**：READY 清单非空但全部已消费 → no-op SUCCESS + WAIT_LANDING 证据 `{noNewInput:true,reason:"ALREADY_CONSUMED",batchId,consumedByRunId,readyButConsumedCount}`、无新快照、ACTIVE 不变、`input_batch_id` NULL 如实落库；「READY 清单完全不存在」仍 = `RUN_EMPTY_LANDING` fail-closed——两种"无输入"语义分裂显式登记。**(d) 重试绑定原批**：FAILED run 无消费行 → 批次保持待处理，FIFO 重选与 retry 钉批两级路径收敛同一批；钉批已被他 run 消费 → no-op 不重复发布。**(e) 显式重算入口**：`POST /api/v1/admin/pipeline-runs/recalculate` body `{runtimeProfileId, batchId, operator, reason}`，reason 空白 → 400 PARAM_INVALID；钉批来源扩为「证据 ?? run.input_batch_id」；所选批 ≠ 请求批 → `RUN_RECALC_BATCH_UNAVAILABLE` fail-closed；重算 run 豁免 no-op 检查；台账 recalc_count/last_recalc_* 更新。**(f) V33 回填**：INSERT…SELECT 从 SUCCESS 且 input_batch_id/source_id 双非空 run 回填（created_via='BACKFILL_V33'），防历史已消费批重发布移动终验锚点 S20260901_23；预检「SUCCESS 且 input_batch_id 非空但 source_id NULL」期望 0 实测 0。**(g) 已知窗口**：发布 ok 后、台账写入前崩溃 → 批次未消费 → 重跑重发布同值（D-045 去重兜底、ACTIVE 前移一槽）——登记为已知窗口非缺陷。**(h) 平台 UI 零改动**。**(i) retry 理由**：retry-from-stage 记录 reason → `pipeline_run.recalc_reason`。**(j) recalc_count 口径**：=「带理由的显式再发布」计数，/recalculate 与 retry-from-stage 均计入。**(x) 发布前质量门陈旧行缺陷修复**：`DataQualityGate` 原读 run 全量历史 `data_quality_result` 行，重试链陈旧 `passed=0` 行导致修复后重试永久拦截（隔离栈 run4 实测同 (AMOUNT_RECONCILE, LANDING) 三行并存 id 79/83 fail + 87 pass）→ 改为只判每 (规则码, 层) max(id) 最新行，历史行保留不阻断；+4 单测（基线 1165→1169）。**(V33 修复决策)**：V33 首跑 error 1567（`ON DUPLICATE KEY UPDATE id = …` 非法自指）→ success=1 系受控手工 SQL UPDATE 直接置位（**G31-12 更正：非 `flyway repair` CLI 所为**，checksum -75448211 原样保留）+ 手工显式 VALUES 逐行回填达 EXPECTBF=2，jar 不重建（纯 SQL 数据层）；V33 源文件已由 G31-12（D-050a）以「去重构造 + checksum 重锚 535846146」修正。**(提交归属策略)**：组① = 12 个纯 G31-11 文件（4 新增 + 8 纯修改，`DataQualityGateTest` +76 行逐行核验纯）；`scripts/run-tests.ps1` MIXED（G31-08 时代注释翻新无法与 1169 基线提升分离）不入组、随工作树登记；G31-10/D-048 在途文件不触碰；`*.bak-*`/游离 PROJECT_STATUS/`.zcode`/`target/` 永不提交。

**Reason**：D-048 §(5) 裁定消费状态须**独立记录**——既非 manifest READY 单独判定（consumed manifest 保持 READY 会被重选重发布，G31-10 §5-1 缺口），亦非值相等去重（掩盖真实再消费）；台账 + FIFO + 钉批三分结构使「无新输入」「有新输入」「重试」「显式重算」四路径各有唯一权威判定源。FIFO 取最旧而非最新，保证多待处理批逐批消化零跳批（判据4）；FAILED 不写台账使批次自动回到待处理集，与 retry 钉批收敛（判据3）。回填是锚点防线：无台账的历史消费批会被新选择器当作未消费重发布、直接移动终验锚 S20260901_23。D-049x 属执行暴露的存量缺陷：不修则场景3 重试链在数据修复后仍永久 FAILED，判据3 无法闭合——同批修复并单测钉住，非静默夹带。

**Boundary**：发布型腿按计划由 P2 隔离栈承担，**不在正式栈重跑**（正式栈仅 no-op 腿 + G31-07 非发布型腿重跑，映射表落结果文档 §2.5）；全部结论限 WSL 单节点环境，真实 AI 与远程集群不并入（G31-06 仍 BLOCKED）；3306 永久冻结零接触；3307 root 仅经 credref/WSL env 通道；push 授权已用尽仅本地提交；BAD 巡检计数（=8）随 no-op 按设计每执行 +1（runs 4,12,15,16,17,19,20 pre-S3-36 source_id NULL ∪ no-op run 26/27），属形状说明非缺陷；陈旧 READY manifest {23,25,26,27} 隔离处置（其消费 run 无 source_id 双证不参与回填，保持 READY 会被 FIFO 重选移动锚点），过程中两轮误隔离事故（PowerShell 管道 `,@()` 展平）在 quarantine README 事故补记留痕；驱动脚本 PowerShell 四陷阱（单元素展开/管道展平/`$h`-`$H` 大小写互踩/`-like '*.jar'` 尾锚）已修复留痕，属驱动层。

**Evidence**：隔离栈 `target/v25-it/g3111iso_20260926_073200/evidence/driver-state.json`（outcome PASS；run1/2/5 发布 + run3-recalc 显式重算 publish_count 1→2/recalc_count 0→1 + run4 三尝试链 FAILED→钉批 FAILED→修复 SUCCESS + run6-noop ALREADY_CONSUMED 指纹前后全等 + 台账 4 行批 1–4 连续）；正式栈 `target/v25-it/g3110_20260925_191753/restart-result-g3111.json` + `evidence-g3111/`（attempt 4 PASS：V33 success=1 checksum -75448211、回填 2==EXPECTBF、run 27 no-op 快照 11→11 台账 2→2 ACTIVE 唯一 S20260901_23、Phase E 指纹 14/14 == P6 oracle、F2b 零 :3306、AI 探针 stub-local/EXECUTED）；全 reactor 基线 1150→1165→1169 F=0 E=0 S=2；结果文档 `docs/verification/results/BATCH-G31-11-M3-PUBLISH-SEMANTICS-RESULT.md`；编辑前备份 `*.bak-20260926-g3111`（DECISION_LOG / CURRENT_BATCH / PROJECT_STATUS）。


---

### D-050 — G31-12：总控复核四项处置（V33 可重复升级 ＋ 重算目标批次校验旁路修复 ＋ FIFO 两批同待处理真实链路证据 ＋ 正常修复流程证据）

**Decision**：总控 G31-11 复核裁定「主要功能已实现，复核发现问题待修，最终签收暂缓」后，G31-12 仅覆盖其四项（真实 LLM、远程集群继续独立登记）。子裁定：

**(a) V33 可重复升级（复核第 1 项）**：重写 V33 回填为**按构造去重**——先对 `(source_id, input_batch_id)` GROUP BY 聚合（`COUNT(*)`=publish_count、MIN/MAX run id 给出 first/last 消费 run），再 `INSERT IGNORE` 落行：语句内不存在重复键 ⇒ 不会 1567；重复执行或撞既有行（含人工补偿行）⇒ IGNORE 跳过绝不改写。原 ODKU 语句注释留痕；文件头记录原 checksum `-75448211`（现场补偿态）与权威新 checksum **`535846146`**。升级验证 = 小型历史夹具 IT `PipelineBatchConsumptionUpgradeMySqlIT` 3/3（V32 历史态起 flyway 升级，**含「同批多次成功发布」批 24 形态 r1/r2 → publish_count=2**）；静态门禁 `PipelineBatchConsumptionMigrationScriptTest` 同步收紧（禁自指 ODKU、断言新写法）。正式库 live 重锚 `-75448211` → `535846146`（success=1 保持；credref 通道；3306 零接触）——「从已有 V32 数据正常升级」自此闭合。

**(b) 重算目标批次校验旁路修复（复核第 2 项）**：`PipelineService` 执行路径**先冻结本 run 原请求批次**（`recalculate()` 预置值 / 重试首跑写入值——它是请求意图），输入选择后**仅当选中批次 == 冻结值才写回 `run.input_batch_id`**；不等（钉住清单缺失、选择器 FIFO 回落他批）时**保持原值不写**，由 WAIT_LANDING 重算断言按冻结值比对 fail-closed `RUN_RECALC_BATCH_UNAVAILABLE`（消息明示「重算不得静默改换输入批次」）。消除「先写选中值再比较 B==B」旁路。单测负例 +1（基线 1169→1170）。

**(c) FIFO 两批同待处理真实链路证据（复核第 3 项）**：证据序列必须先让 **A、B 两批同时待处理**（两批摄取完成 → 断言台账空、均 READY、零快照），再连续执行两次流水线 → run1 必取 A（最旧）、run2 取 B。旧「取最新」实现在该序列下必然失败，方构成 FIFO 证明；既有选择器单测保留。

**(d) 正常修复流程证据（复核第 4 项）**：以**环境临时故障**（spark-jobs jar 移走，输入零接触）验证正常恢复路径：故障前/FAILED 后/重试成功后三次「输入文件+manifest」SHA256 校验和**三联全等**；jar SHA 验真还原后**原 run 原地 retry-from-stage SUCCESS**。g3111 直接改写 accepted 文件的实验**按原貌保留**、结论限定为「人工订正数据后计算可恢复」，不作为正常数据修复流程验收证据（G31-11 结果 §9②）。

**(e) G31-11 状态改登记**：CURRENT_BATCH / PROJECT_STATUS 改口「主要功能已实现，复核发现问题待修，最终签收暂缓」；G31-11 结果文档 §9 补记更正两处不准确表述（success=1 系手工 SQL UPDATE 直接置位 + 显式 VALUES 逐行回填，非 `flyway repair` CLI、`INSERT…SELECT` 现场从未成功执行），正文按原貌保留。

**Reason**：V33 首版 ODKU 自指（`id=id`）在语句内同键重复时不可消解，1567 是**结构性**缺陷——`flyway repair` CLI 只处理 checksum 漂移，对本缺陷无效；现场成功的是手工置位+回填，报告不得归功 repair（措辞即事实，错误概括会让「从 V32 正常升级」被误判为已闭合）。按构造去重使同语句从任意 V32 态幂等升级、1567 根除，才配得上「可重复执行的升级方案」。写后校验旁路的本质是 `run.input_batch_id` 一列承载两种含义（请求意图/实际选中）而「先写后比」丢掉了意图——冻结-比对-受控写回让选中值只能**确认**意图、不能**改写**意图。FIFO 证据要「旧取最新实现也必败」才算证明，两批同待处理+台账空前置断言正是让旧实现必然暴露的序列。恢复类验收必须区分「改数据」（人工订正，侵入输入）与「修环境」（零输入接触）——校验和三联全等 + jar SHA 验真把「输入零改动」变成可复核事实。

**Boundary**：全部结论限 WSL 单节点环境；真实 LLM（G31-06 仍 BLOCKED）与远程集群继续独立登记不并入；V32/V33 永不在 3306 运行、3306 全程零接触；正式库 live 重锚仅 UPDATE `flyway_schema_history` 一行（checksum/success），经 credref 通道零回显；g3112 全程隔离栈、发布型腿不上正式栈；push 授权已用尽仅本地提交；`INSERT IGNORE` 将数据类错误降级为告警的代价已在 V33 头注释登记（源/目标列均为受控字面量与直拷，实际风险可忽略，故接受）；`*.bak-20260926-g3112` 备份永不入提交组。

**Evidence**：隔离栈 `target/v25-it/g3112iso_20260926_192745/evidence/driver-state.json`（outcome PASS）——腿③ `leg3-pre` `{manifestsReady:[1,2], consumptionEmpty:true, SNAPCNT|0}` + `RUN|1|SUCCESS|1|S20260901_1` + `RUN|2|SUCCESS|2|S20260901_2` + 台账 CONS 批 1→2 连续（顺序 A→B 零遗漏）；腿④ 校验和三联全等 `c27df0d7090c75854d97096f160cbbc33a976f7652490554b2926ef6fa4b87a0`（evidence/input-checksums-b3-cp{1,2,3}-*.txt）+ `RUN|3|FAILED|3|…|RUN_JOB_FAILED|1`（stage=INIT_SCHEMA，jar 移走）→ 台账仍 {1,2}/ACTIVE 不变 → 还原 SHA `71c0fc88…` 验真 → `RUN|3|SUCCESS|3|S20260901_3|…|2|G31-12-env-fault-cleared-retry-same-run` + `CONS|3|3|3|S20260901_3|1|1|…|PIPELINE` + ACTIVE→S20260901_3；腿⑤ manifest1 移除（原件保全 `leg5-manifest1-moved.json` SHA `fbdb8c4a…`）→ `RUN|4|FAILED|1|…|RUN_RECALC_BATCH_UNAVAILABLE|1|…`（**input_batch_id 保持原请求值 1，未被改写为回落值 4**）+ 台账仍 {1,2,3}（批 4 未消费）+ manifests/4.json 仍 READY + ACTIVE 不变 S20260901_3；夹具 IT 3/3 + 静态门禁绿；正式库 flyway 33 行 success=1 checksum `535846146`（重锚复核 SQL 留证 evidence-g3112）；全 reactor 基线 **1170 F=0 E=0 S=2**；计划 `docs/verification/batches/BATCH-G31-12-MASTER-REVIEW-FIXES-PLAN.md`；结果 `docs/verification/results/BATCH-G31-12-MASTER-REVIEW-FIXES-RESULT.md`；编辑前备份 `*.bak-20260926-g3112`（G31-11 结果文档 / CURRENT_BATCH / PROJECT_STATUS / DECISION_LOG）。

### D-051 — G31-13：工作树 git 历史欠账收口决策（分组本地提交 ＋ 门值补同步 ＋ 永不提交清单）

**Decision**：将「暂不 commit」窗口（G31-03 后 ~ 2026-09-24，G31-04 计划 L9）遗留的 35 个已跟踪修改 + 67 个未跟踪路径按归属**分组本地提交**收口，执行 G31-07 结果 §9 预留指令「按改动线分组单独 commit，逐文件 diff 复核」：①connection-ingestion 存储缝线（item5/HDFS 支撑，11 M+9 新+pom）②metric-analysis D-042 导出路径线（5 M+1 新 MetricExportPath）③warehouse-pipeline Spark 本地档加固（2 M）④ai-decision 日期令牌守卫（2 M）⑤web 每图空态线（6 M+1 新）⑥fixtures 精度注记（2 M）；G31-08 代码（spark-jobs 2 M+1 新）与 G31-09 代码（V32 SQL+迁移静态门禁+2 IT）各按其结果文档归属清单独立成组；10 份批次文档 + deployment-freeze + 指导书 V3.1 分组提交；`scripts/run-tests.ps1` 作为共享门值补 G31-12 同步行 **1169→1170**（+1 重算旁路负例 D-050②；G31-12 实际基线 1170 未回同步，本批补齐，message 记录 G31-08 322→329 / G31-09 1096→1150 / G31-11 1150→1169 累积链）。**run-tests.ps1 提交裁定**：G31-11「MIXED run-tests.ps1 不入组」系该批提交分组决定，被本批欠账收口目标覆盖——它是 tracked 共享测试门，脱离版本库即失去门值意义；`scripts/g3111-formal-restart.ps1`（untracked，正式栈专用）维持不入库。**永不提交清单**：全部 `*.bak-*`（30 件）、`.zcode/`、`.zcodeignore`、仓库根游离 `PROJECT_STATUS.md`、`scripts/g3111-formal-restart.ps1`、`credref-*.properties`。

**Reason**：D-048 要求「按批次保存完整源码」，欠账悬置使 G31-04~G31-09 的代码与证据只在 target/ 构建产物与工作树中存在，一次误清理即不可恢复；G31-07 §9 已预留分组提交指令、G31-11 §167 已登记原状，收口条件成熟。基线携带论证：analytics-server 1170（F=0 E=0 S=2）系 G31-12 收口（2026-09-26）在当前字节状态工作树的全 reactor 结果，git 提交零字节改动 → 结论对提交后树成立；web 375/375（2026-09-27 复跑，含新增 chartStateForRows/aiButtonStyle 用例）与夹具 sha256 5/5 当日复核；危险扫描（jdbc:mysql:3306 / 明文口令 / MYSQL3307_ROOT_PASSWORD / PRIVATE KEY）双清零。逐文件 diff 复核完成，六组内容与 G31-07 §9 / G31-08 §5 / G31-09 §6 归属清单逐字吻合，未发现越界内容。

**Boundary**：仅本地提交（push 授权已用尽）；绝不 `git add -A`/bulk-tree，每组显式路径；不改写历史提交；3306 永久冻结零接触；本批零产品/测试字节改动（run-tests.ps1 门值数字与注释行除外）；不产生新验收判据——G31-04~G31-09 结论以各自结果文档为准，本批仅收口 git 呈现形态；`*.bak-*` 永不提交。

**Evidence**：计划 `docs/verification/batches/BATCH-G31-13-WORKTREE-DEBT-CLOSURE-PLAN.md`（§1 审计结论 35 M+67 ?? 全定性表）；web 测试 375/375（node --test 2026-09-27）；夹具 `fixtures/source-a-e3/MANIFEST-SHA256.txt` sha256sum -c 5/5 OK；提交序列 17 组（R 注册→六代码组→五文档组→G31-08/09 代码+文档→门值→C 收口），逐组 SHA 记录于 CURRENT_BATCH/PROJECT_STATUS 收口条目；编辑前备份 `*.bak-20260927-g3113` 三件（DECISION_LOG / CURRENT_BATCH / PROJECT_STATUS）。

### D-052 — 总控签收登记（2026-09-27）：G31-13 Git 清账与归档签收 ＋ G31-12 四项证据签收并解除 G31-11 暂缓签收；裁定原文备份落盘；范围限已记录证据、非产品最终验收

**Decision**：总控 2026-09-27 复核裁定（原文逐字备份 `docs/decisions/rulings/MASTER-RULING-20260927-G3113-SIGNOFF.md`）：**(1) G31-13 Git 清账与归档签收**——17 组提交已落库；`g3113` 清单总控独立核验 **9/9 哈希一致**，bundle 验证为完整历史；当前无已跟踪或暂存改动，49 个未跟踪路径仍按「永不提交」清单管理。**(2) G31-12 四项复核证据签收，解除 G31-11 的暂缓签收**——留存的隔离链结果为 PASS，支持 V33 升级、重算钉批、FIFO 双待处理批和原 run 故障恢复四项结论。**(3) 归档边界登记**——归档快照截于 `8ab5824`，之后的登记提交 `7bcbde1` 不在该 bundle 中（不影响 17 组清账的备份范围，但远端仍未收到这些提交）；含历史 `*.bak-*` 的归档继续只作受控本地副本、不公开上传。**(4) 本条登记动作**——按裁定指令「将本裁定备份后登记到动态状态与决策记录」执行：裁定原文备份落盘（新目录 `docs/decisions/rulings/`）+ 本条 + CURRENT_BATCH/PROJECT_STATUS 状态翻转（G31-11 暂缓签收解除、G31-12/G31-13 签收完成），下一动作 = 审定 V3.1 范围。

**Reason**：签收权归总控，代码 Agent 不得自行宣布验收；本条仅登记裁定事实并解锁 V3.1 范围审定，不产生新的技术判据。裁定原文逐字备份是「登记以保全」的最小充分动作，与 DECISION_LOG 概括登记互为凭据。

**Boundary**：裁定**限于已记录的 WSL 单节点与隔离环境证据，不等于整个毕业设计产品最终验收通过**（裁定原文明示；总控本轮未重连数据库、未重跑 Java 测试）；V3.1 仍为待审稿——提交≠发布，权威版本仍 V3.0；真实 LLM 仍 BLOCKED（G31-06/D-039）；远程集群、连续 WSL 发布链及分类/地区供数**不因本次签收写成已完成**；3306 永久冻结零接触；push 授权已用尽仅本地提交（远端未收到 17 组提交与登记提交）；`*.bak-*` 备份永不提交。

**Evidence**：裁定原文备份 `docs/decisions/rulings/MASTER-RULING-20260927-G3113-SIGNOFF.md`；g3113 归档 `v3-archive/g3113/`（MANIFEST-SHA256 9 项，总控独立复核 9/9 一致）；提交序列 `b1c02b6..8ab5824`（17 组）+ `7bcbde1`（归档登记）；编辑前备份 `*.bak-20260927-d052` 三件（DECISION_LOG / CURRENT_BATCH / PROJECT_STATUS）。

### D-053 — V3.1 范围审定（工作级，D-052 后续动作）：N31-01 残项④门控挂起 ＋ N31-02 审定为下一可执行批次 ＋ N31-04 维持 BLOCKED；V3.1 §9 裁决①由 D-052 关闭、②③④开放

**Decision**：按 D-052 裁定指令「再审定 V3.1 范围」，对指导书 V3.1（待审稿 `docs/guidance/项目完整实施指导书 V3.1.md`）的工作包范围逐项审定并登记现状映射：**(1) N31-01（状态校准与签收准备）**：①归档核验已完成（g3112/g3113 两轮，g3113 MANIFEST 9/9 总控独立复核一致、归档已存在未重建）；②「G31-11 暂缓与 G31-12 处置分开报告 + 总控裁定」已完成——D-052 裁定 G31-11 暂缓签收**解除**、G31-12/G31-13 签收，V3.1 §9 待裁决①就此关闭；③脏工作树归属已完成（G31-13：35 M+67 ?? 全定性落库，残留 49 路径=永不提交清单，无「未知」项）；④「对照 G31-12 源码与冻结设计 V3.0 列出待纳入设计 V3.1 的确切差异清单」**待办且门控**——V3.1 §2.3 规定本草稿通过总控审阅后才编制设计 V3.1，故不提前开工，N31-01 除④外退出标准已达成。**(2) N31-02（单节点连续数仓链与维度专题）审定为下一可执行批次**：连续 WSL 链同 runId 贯穿采集→仓库→发布→页面、最小共享 HMS 子判据单列（资源不足时诚实标记未通过）、同源第二批+无新输入重放、HDFS checkpoint 同文件不重读保持、**分类/地区供数专题**（已知开口：`AnalysisService.sales` 返回两空数组 + `UNKNOWN_DIMENSION_TABLE`，须 ADS→服务库→API→页面贯通+独立 oracle，禁止删告警或前端聚合冒充）；启动时点待总控对本审定确认或下达开工指令。**(3) N31-03（普通员工可用性与业务决策复核）**：待执行，排 N31-02 后（其 §5 允许与轻量页面切片并行）。**(4) N31-04（真实模型接入）**：维持 `BLOCKED_EXTERNAL_INPUT`（provider/受控凭据引用/外发范围/费用上限四要素缺，D-039），不阻塞 N31-02/03。**(5) N31-05（最终验收与版本发布）**：待 N31-02/03 证据齐后按最小终验执行，PASS/BLOCKED/OUT_OF_SCOPE 逐项标注；设计 V3.1 与指导书审定稿双发布+README 更新由总控执行。

**Reason**：D-052 解除 G31-11 暂缓签收使 N31-01 的签收准备三项即时达成；范围审定把「下一步做什么」收敛到唯一可执行批次 N31-02，并把被 V3.1 §2.3 门控的设计差异清单显式挂起，避免在 V3.1 未获总控正式审阅前越权编制设计文档或擅自开工大批次。本审定为工作级范围确认，不改变范围与验收门槛的正式效力（权威仍 V3.0，V3.1 §2.3、§7）。

**Boundary**：V3.1 全程保持待审稿身份，本审定不构成正式版本发布或权威切换；N31-02 开工前需总控确认本审定（或直接指令）；真实 LLM/远程集群/外部商城/论文答辩不在本审定执行面；3306 永久冻结零接触；push 授权已用尽仅本地提交；`*.bak-*` 永不提交。

**Evidence**：指导书 V3.1 §2/§5/§9（工作包定义、版本门控条款、待裁决清单）；D-052 裁定（§9①关闭）；G31-13 收口登记（N31-01③）；`v3-archive/g3112|g3113` 归档核验（N31-01①）；编辑前备份 `*.bak-20260927-d053` 两件（DECISION_LOG / CURRENT_BATCH）。

### D-054 — N31-02 开工授权登记（2026-09-27）：总控批准按 D-053 范围启动 N31-02，四腿有序推进（①连续小链→②同源第二批/重放/失败保旧→③分类地区契约冻结→④共享 HMS 单列）；开工授权≠V3.1 发布，权威仍 V3.0

**Decision**：总控 2026-09-27 开工裁定（原文逐字备份 `docs/decisions/rulings/MASTER-RULING-20260927-N3102-START.md`）：**批准启动 N31-02，按 D-053 所列范围执行**；明示「这是工作批次开工授权，不是发布 V3.1 指导书；正式权威仍为 V3.0」；授权「不必为每个普通技术步骤再等一次确认」，四腿按序推进：**(腿① 连续小链)** 用同一受控输入从 Flume→HDFS→平台摄取→Spark 分层→ADS→隔离 3307 的 ACTIVE→API→页面完成一次对账；以 `sourceId、manifest batchId、pipeline runId、snapshotId` 串起血缘，**不要求 Flume 和平台强行共用一个字面相同的 runId**；**独立 oracle 必须在发布前确定**。**(腿② 同源第二批＋重放＋失败保旧)** 复用已有小样本和 G31-11/12 机制，只跑受影响的定向测试；不得把不同运行腿拼接成「一次连续链通过」。**(腿③ 分类/地区专题先冻结实现契约)** 列清粒度、来源字段、`unknown` 归属、ADS 与 MySQL 表、发布映射、API、页面和 oracle；V3.0 已要求该专题但其服务库镜像尚未定型；**新增表或改变指标口径前，先交设计差异供总控审定**；该切片停在那里，不妨碍腿①②继续。**(腿④ 共享 HMS 单列判据)** 可在 WSL 单节点尝试 Spark 与 Hive 共用 Metastore；资源不足就如实记未通过，**不用嵌入式 Derby 的结果冒称共享 HMS 通过**，也不因此否定已单独通过的连续链。

**Reason**：D-053 已审定 N31-02 为下一可执行批次，本裁定把「启动待总控确认」解除为可执行状态并一次性授予普通技术步骤的决策权，消除逐步请示的停顿；先定 oracle 再发布是防后验篡改判据的核心次序约束；腿③契约先行的门控把「新增表/改口径」这一未定设计决策显式回交总控，其余工作照常，符合「只有触及未定的表结构或业务口径时停下该切片」的裁定边界。

**Boundary**：全程只用隔离环境，**3306 永久冻结零接触**；不格式化既有 HDFS；不覆盖历史证据；不 push（仅本地提交、按批次归因分组、绝不 `-A`）；V25_IT_* 口令零落盘、3307 root 口令仅 credref 通道；`*.bak-*` 永不入库；不修改已冻结的 V3.0 正文；不将待审 V3.1 写成已发布；完成连续链后继续推进下一切片；只有触及未定的表结构或业务口径时停下该切片提交具体差异，其余工作照常进行。

**Evidence**：裁定原文备份 `docs/decisions/rulings/MASTER-RULING-20260927-N3102-START.md`；执行计划 `docs/verification/batches/BATCH-N31-02-CONTINUOUS-CHAIN-PLAN.md`；分腿结果 `docs/verification/results/BATCH-N31-02-LEG*-RESULT.md`（随腿登记）；编辑前备份 `*.bak-20260927-d054` 三件（DECISION_LOG / CURRENT_BATCH / PROJECT_STATUS）。

### D-055 — N31-02 腿① run5 崩溃修复决策：PipelineService 对 hdfs:// profile 弃用无条件 LandingUri.resolve，改走 LandingStorage 抽象（本地路径所有者不变）；选择器/事件读取双后端等语义改造 + 存储版单测 4 例

**Decision**：腿① run5 驱动崩溃链（症状=driver `Get-FailedStage` 对零行 SQL 输出 `Value cannot be null` 退出 1；真因=`PipelineService.java:484` 对 landingUri 无条件 `LandingUri.resolve` → `PlatformBizException 暂不支持的 landingUri 协议：hdfs://`，run 在调度前 internal error、pipeline_stage_run 零行）修复如下：**(a) 执行入口**：`execute()` 内 `LandingStorageResolver.forProfile(profile)` 按 profile.landing_uri 分派；`localLanding = "local".equals(type())` 才允许 `LandingUri.resolve`（LandingUri 仍是本地路径唯一所有者，V2.1 §5.4），hdfs profile 的 `landingRoot=null`。**(b) 输入选择**：`manifestForRun` 增加 LandingStorage 参数，`landingRoot != null ? select(Path 版) : select(存储版)`——`LandingManifestSelector` 新增存储重载（同源归属/READY 非空/FIFO/钉住优先/已消费留痕语义逐条不变），扫描与钉住读取经 `list/open/exists`，扫描逻辑共享化（`newScanState/consider/warnIfNothingAttributable`），存储 I/O 故障按 §8.2 契约降级为「无清单」告警不炸选择。**(c) accepted 读取**：manifest 的 acceptedUri 对两后端均为相对值（`IngestionService.java:410` 唯一写点 `accepted/{batchId}`）——local 解析为 java.nio Path（行为不变），hdfs 保留相对路径、字节读取新增 `readAcceptedEvents(LandingStorage, ...)`（list→.jsonl→open 流式逐行），行语义收口到共享 `acceptEventLine`（坏行跳过/订单总额照收/业务日切片，与本地版同一实现）；目录存在性统一判定 `isLandingDirectory`（stat().directory() ↔ Files.isDirectory）。**(d) Spark 输入**：odsExtra `landingDir` 统一给可寻址 URI——hdfs 经 `storage.uri(relative)` 得 `hdfs://host:port/.../accepted/{batchId}`，经 JobCommandBuilder `--k=v` 透传（spark-jobs 泛化读 args，零改动），Spark/Hadoop 原生读 hdfs://。**(e) LOAD_ODS 预检**：两后端同一错误码 `RUN_LOAD_FAILED`「accepted 目录不存在」。**(f) 测试**：`LandingManifestSelectorTest` 增 MemLandingStorage 假存储（满足 LandingStorage 全部抽象契约）+ 4 例（FIFO+消费跳过 / 全消费 null+count / 钉住语义 / 缺目录+坏清单 fail-closed）。warehouse-pipeline `mvn test` BUILD SUCCESS（含 PipelineServiceTest 48/48），platform-app 重打包。

**Reason**：run5 的 `PlatformBizException` 是平台对 hdfs:// profile 的**结构性拒绝**——采集侧（IngestionService）早已支持 HDFS 落地，而流水线侧仍在 `Path.resolve(hdfs://…)`，链路在腿①这一步必然断裂；LandingStorage 抽象（§8.2）正是为「source/events 之外的统一落地层入口」而建，本修复是让流水线回到该契约，而非新增旁路。选择器/行语义/预检全部**共享同一实现**而非复制两份，是防「两后端两套口径」漂移的关键；acceptedUri 保持相对值使清单成为后端无关凭据（证据里的 acceptedUri 继续可比对）。driver 侧 `Get-FailedStage` 零行守卫同步修复（零 stage 行是真实状态而非脚本错误），保证后续腿再遇平台错误时能拿到真实错误码而非崩溃。

**Boundary**：仅 local/hdfs 两后端行为；local 路径行为字节级不变（landingRoot 解析、Files.isDirectory、readAllLines 读取路径全部保留原实现）；不触碰采集侧（IngestionService）与 spark-jobs（args 泛化透传已存在）；全程隔离环境、3306 零接触、不格式化既有 HDFS（clusterID 不变）、不覆盖历史证据；push 授权已用尽仅本地提交；`*.bak-*` 永不提交；本条为腿① run6 重跑的平台前置修复，腿①链路验收以 run6 证据为准。

**Evidence**：根因链 `target/v25-it/n3102iso_20260927_123837/logs/platform.log:416-418`（PlatformBizException hdfs:// 拒绝）+ driver run5 输出（exit 1 Value cannot be null）；修改文件 `PipelineService.java`（imports/执行入口/manifestForRun/accepted 解析与读取/odsExtra/LOAD_ODS 预检）+ `LandingManifestSelector.java`（存储重载+共享扫描）+ `LandingManifestSelectorTest.java`（MemLandingStorage+4 例，17/17）+ `PipelineServiceTest`（48/48）；构建日志 `analytics-server` reactor warehouse-pipeline BUILD SUCCESS（2026-09-27 17:41）+ platform-app package；编辑前备份 `DECISION_LOG.md.bak-n3102-d055`。
