# AI 辅助多商城数仓接入与建模实施书 V1.0

> 状态：`READY_AFTER_P5`（AIW-001～005 可提前准备；生产接线等待 P1–P5）
> 设计依据：`../specs/2026-09-11-ai-assisted-warehouse-onboarding-design-v1.0.md`
> 总指导：`../../项目完整实施指导书 V2.2.md`
> 进度入口：`../../项目实施进度与任务看板 V2.2.md`
> 当前首任务仍为商城无关化 `P1-01`；本书不授权立即跳过 P1–P5。

---

## 1. 总体完成定义

新商城 B 的字段、枚举、ID 和源表结构与参考商城不同。用户提供只读元数据/Schema 和 20–100 条脱敏样本后，平台可以生成结构化映射与数仓蓝图，列出待确认问题，通过确定性校验，在独立 sandbox 跑通并形成对账证据；审批后生成不可变 release，部署失败不影响旧 ACTIVE，已部署版本可回滚。

任何一项仅有表、类、接口或页面而没有对应 E2/E3/E5 证据，只能记为 `DONE_LIMITED`。

## 2. 模块和代码位置

| 模块 | 新增职责 | 建议包/目录 |
|---|---|---|
| `ai-decision` | LLM 编排、结构化草案、prompt 版本、解释 | `com.graduation.analytics.ai.modeling` |
| `connection-ingestion` | 元数据/样本读取、画像、脱敏 | `...source.discovery`、`...source.profile` |
| `warehouse-pipeline` | 蓝图校验、受控编译、DAG、sandbox、release | `...warehouse.blueprint`、`...warehouse.compiler`、`...warehouse.release` |
| `platform-app` | 迁移、Controller、认证/RBAC、异步任务接线 | `...controller.ai`、`db/platform/` |
| `web` | 七步建模向导 | `src/views/modeling/`、`src/api/modeling.js` |
| `contract-specs` | JSON Schema/OpenAPI | `ai-modeling/`、`openapi/` |
| `docs/acceptance` | 小数据、攻击集、人工/AI A-B 证据 | 每次验收独立目录 |

已存在且复用：`ai/llm/LlmProvider.java`、`OpenAiCompatLlmProvider.java`、`TextToSqlService.java`、`SemanticCatalog.java`、`ai/sql/SqlSafetyValidator.java`、`ai/evidence/EvidencePackage.java`、流水线 `JobSubmitter/JobResult`。复用不等于直接扩展其自由文本接口；AI 建模必须使用单独的结构化契约。

## 3. 波次、依赖与并行

| 波次 | 任务 | 并行方式 | 合并门 |
|---|---|---|---|
| W0 契约 | AIW-001～005 | Schema、画像算法、攻击夹具可并行；迁移号由总控串行 | C0 |
| W1 映射助手 | AIW-006～009 | LLM 适配与审核 API 可在契约冻结后并行 | C1/E2/E5 |
| W2 蓝图 | AIW-010～013 | validator 与 compiler 先串行冻结接口，再并行实现 | C2/E2 |
| W3 Sandbox/治理 | AIW-014～017 | sandbox 与审计 UI 可并行；发布器最后接线 | C3/E3 |
| W4 产品/验收 | AIW-018～020 | UI、攻击集、A/B 评测可并行 | C4/E3/E5 |

热点文件 `PipelineService`、迁移号、主配置、前端 router、公共 JSON Schema 同一时间只允许一个 Owner。每项 30–120 分钟；超过两小时未形成证据必须拆分。

## 4. 公共状态和错误码

状态：`DISCOVERING / DRAFT / NEEDS_CONFIRMATION / VALIDATING / VALIDATION_FAILED / SANDBOX_RUNNING / SANDBOX_FAILED / REVIEW / APPROVED / REJECTED / DEPLOYED / ROLLED_BACK`。

稳定错误码至少包括：

- `MODEL_INPUT_TOO_LARGE`、`MODEL_PROVIDER_UNAVAILABLE`、`MODEL_OUTPUT_INVALID`
- `PROFILE_SCHEMA_INVALID`、`PII_REDACTION_FAILED`
- `MAPPING_REVIEW_REQUIRED`、`BLUEPRINT_SCHEMA_INVALID`
- `BLUEPRINT_NAME_FORBIDDEN`、`BLUEPRINT_GRAIN_INVALID`、`BLUEPRINT_DAG_CYCLE`
- `BLUEPRINT_SQL_UNSAFE`、`BLUEPRINT_RESOURCE_EXCEEDED`
- `SANDBOX_QUALITY_FAILED`、`SANDBOX_RECONCILE_FAILED`
- `APPROVAL_REQUIRED`、`APPROVAL_STALE`、`RELEASE_CHECKSUM_MISMATCH`

前端按 code 映射业务说明；后端日志保留 traceId，不返回 secret 或完整 prompt。

## 5. 任务清单

### AIW-001 结构化契约与 JSON Schema

- **目标**：冻结 `SourceProfileDraft`、`WarehouseBlueprint`、`WorkflowDefinition`、review item 和 validation result。
- **允许范围**：`contract-specs/ai-modeling/**`；不得改已冻结 canonical event。
- **实现提示**：每个 Schema 含 `$id`、`schemaVersion`、`additionalProperties:false`、枚举和长度/数量上限；金额用 decimal 字符串，时间用带时区 ISO-8601。
- **边界**：workflow nodeType 只允许九种白名单；transform 只允许登记的纯函数码，不接受表达式脚本。
- **测试**：一套合法 A/B 样本、缺必填、多余字段、危险 node、循环边引用等负例；Schema validator 全绿。
- **完成证据**：契约版本、SHA-256、示例和 parity test。

### AIW-002 持久化迁移与 Repository

- **目标**：建立七张建模/发布表。
- **允许范围**：总控分配的一个新迁移文件、`ai-decision`/`warehouse-pipeline` repository；禁止自行选择迁移号。
- **数据结构**：严格按设计 §8；JSON 用支持大文本的类型，状态/checksum/version/审计列结构化。
- **算法**：乐观锁或 `(id,version)`；draft 只增版本，不 UPDATE 覆盖正文；审批绑定 checksum。
- **测试**：真实 MySQL migration、唯一键、非法状态、版本追加、审批失效、release previous 指针。
- **完成证据**：E2；表结构导出不能含凭据明文。

### AIW-003 数据源元数据采样器

- **目标**：统一读取 FILE JSONL/CSV、OpenAPI 和只读 DB metadata，产生原始 `DiscoverySnapshot`。
- **接口**：`SourceMetadataSampler.sample(SourceRef, SamplingPolicy)`。
- **限制**：默认每对象 100 行、字节上限、超时、只读；路径必须在 source 允许根目录；DB 账户不得有写权限。
- **算法**：确定性 reservoir/首段采样策略必须记录；相同输入和 seed 产生相同样本 checksum。
- **测试**：空源、大字段、坏 JSON、CSV 引号、超时、路径穿越、数据库拒绝写。

### AIW-004 画像与 PII 脱敏

- **目标**：确定性计算类型、空值率、唯一率、枚举候选、时间/金额候选、主外键候选和 PII 标签。
- **建议类**：`SourceProfiler`、`TypeInferencer`、`KeyCandidateDetector`、`PiiClassifier`、`SampleRedactor`。
- **算法**：类型按 boolean→integer→decimal→timestamp→string 的无损顺序；唯一率=`distinct/non_null`；枚举需基数阈值；候选关系只在包含率和类型兼容均达阈值时提出。
- **安全**：手机号/邮箱/证件/地址/secret 先脱敏再进入 prompt；脱敏失败阻断外部调用。
- **测试**：中英文列名、混合类型、时区、金额精度、PII 和误报边界。

### AIW-005 固定评测集与 prompt 攻击集

- **目标**：建立 A/B/C 三源的 20–100 条小样本和人工真值。
- **允许范围**：专用 test resources/acceptance fixture；不得放真实个人数据。
- **内容**：B 改字段名/枚举/UUID/多余字段；C 含歧义金额、缺主键、恶意列名和值、循环关系建议、危险 SQL 字符串。
- **产出**：字段映射 truth、必填字段 truth、预期问题、预期拦截错误码。
- **完成证据**：fixture checksum 固定；不同测试不可自行改 truth。

### AIW-006 建模 LLM 适配与结构化输出

- **目标**：复用 `LlmProvider`，但增加 `ModelingPromptAssembler`、`StructuredOutputParser`、`PromptTemplateRegistry`。
- **输入**：只接收已脱敏 `DiscoveryProfile` 和版本化 canonical catalog；不发送凭据和整表。
- **输出**：只接受 AIW-001 Schema；解析失败为 `MODEL_OUTPUT_INVALID`，不得提取代码块后直接执行。
- **治理**：记录 provider/model/prompt_version/token/耗时/error/input_fingerprint/traceId；有限重试只重放相同幂等输入。
- **测试**：Mock 合法/截断/多余字段/拒答/注入输出；provider down 降级为人工草案入口。

### AIW-007 `SourceProfileDraft` 生成服务

- **目标**：合并确定性画像与 AI 建议，产生首版草案。
- **规则**：画像统计值永远取本地计算；AI 只能补语义建议和解释。相同 session 重新生成产生 version+1。
- **置信度**：保留模型分数，但系统对金额、身份、退款、时区和低于阈值项强制 `reviewRequired=true`。
- **测试**：重复请求幂等、输入变化新指纹、低置信度、模型无响应和草案版本链。

### AIW-008 映射置信度与问题生成

- **目标**：把不确定项转成可回答的问题，而非让开发者读 prompt。
- **问题类型**：金额含义、订单状态、退款冲减、匿名身份合并、事件时间/时区、枚举多对一、缺必填字段。
- **算法**：BLOCKING 优先；相同 sourcePath/target/questionCode 去重；已确认答案绑定 draft version。
- **测试**：歧义 C 必须出现预期问题；答案改变后旧审批无效。

### AIW-009 审核 API 与最小页面

- **API**：session GET、review-items GET/PUT、generate；遵循设计 §13。
- **页面**：画像表、映射左右列、置信度、理由、问题、接受/修改/拒绝；支持按 BLOCKING 筛选。
- **权限**：普通员工只读；data_dev/admin 审核。reviewer 从会话身份获取。
- **测试**：越权 403、伪造 reviewer 无效、乐观锁冲突、所有 BLOCKING 未完成时 validate 返回 409。
- **完成证据**：E2 + E5；此时只到映射草案，不宣称自动建仓。

### AIW-010 `WarehouseBlueprint` 生成器

- **目标**：基于已审核 mapping 和固定 canonical 模型生成分层蓝图。
- **规则**：ODS 保留 payload_json；DWD/DIM 明确 grain/keys；DWS/ADS 指标只能引用注册表口径；源扩展表显式标记。
- **输出**：表、列、分区、SCD、质量、指标、血缘、资源估算和 rollback plan。
- **测试**：参考源 A、异构源 B、缺关键业务语义 C；不得出现跨 source 物理表。

### AIW-011 `BlueprintValidator`

- **目标**：实现设计 §10 的十类确定性检查。
- **接口**：`ValidationReport validate(WarehouseBlueprint, ValidationContext)`，一次返回全部问题。
- **算法**：DAG 用 Kahn 或 DFS 检环；名称 regex+保留字；grain/主键唯一；decimal/timezone 类型规则；资源阈值；PII 输出策略。
- **测试**：每个 error code 至少一正一负；问题排序稳定，重跑报告 checksum 一致。

### AIW-012 受控 DDL/SQL 编译器

- **目标**：从合法蓝图生成版本化制品，不接受自由 SQL。
- **接口**：`CompiledArtifact compile(ApprovedBlueprint, CompilerContext)`。
- **规则**：模板参数化引用 namespace/field/semantic code；AST 二次检查；sandbox 与 production 目标分别渲染；禁止 DROP/TRUNCATE/任意函数。
- **产出**：DDL、转换 SQL、job args、lineage、manifest、SHA-256。
- **测试**：golden file、危险标识符、跨库、模板注入、相同蓝图字节一致。

### AIW-013 工作流 DAG 编译与验证

- **目标**：将 workflow draft 编译为固定节点和依赖，不让模型控制命令。
- **规则**：节点 ID 唯一、输入必须由前序输出、无环、必须含 QUALITY_CHECK，PUBLISH_METRIC 只能在质量后。
- **运行信息**：每节点 timeout/retry/resourceProfile/idempotencyKey；Spark 节点复用 JobSubmitter externalJobId/log。
- **测试**：缺质量门、发布前置错误、循环、孤儿节点、重复输出、失败恢复。

### AIW-014 Sandbox 执行器

- **目标**：在 `<prefix>_sandbox_<draftId>` 用 20–100 条固定数据执行编译制品。
- **边界**：独立 namespace/临时目录/指标 staging；禁止切 ACTIVE；失败保留证据并按保留期清理自己的资源。
- **算法**：taskId 异步、阶段恢复、幂等临时分区；成功后记录行数/checksum/耗时/资源。
- **测试**：成功、质量失败、Spark 进程失败、取消、重试、同 idempotencyKey、旧 ACTIVE 不变。

### AIW-015 对账与建模证据包

- **目标**：形成可审核的 `ModelingEvidencePackage`，不复用分析数值证据包冒充。
- **内容**：输入/输出行数、隔离数、主外键、金额、状态机、DWD-DWS-ADS 对账、规则结果、资源、版本/checksum、warnings。
- **规则**：缺值写 warning/UNKNOWN，不用 0 代替；evidenceRef 指向 sandbox 表/列@run。
- **测试**：故意缺证据、金额不平、规则失败、报告稳定排序。

### AIW-016 审批、RBAC 与审计

- **目标**：实现 `REVIEW -> APPROVED/REJECTED`；修改蓝图使旧审批失效。
- **权限**：审核映射与部署权限分离；审批身份来自 SecurityContext；原因必填。
- **审计**：创建、AI 调用、映射修改、validate、sandbox、审批、部署、回滚都记录 actor/traceId/before-after checksum。
- **测试**：越权、身份伪造、自审批策略（按最终 RBAC 决策）、过期 checksum、重复审批。

### AIW-017 不可变 Release、部署与回滚

- **目标**：生成 `workflow_release`，部署只引用已审批 artifact checksum。
- **算法**：先验证制品和目标，再部署，再原子切 release 指针；失败保留旧 ACTIVE。回滚切向 previous_release，不修改历史制品。
- **禁止**：自动删除旧 namespace；清理另立有保留期和 scoped confirmation 的任务。
- **测试**：校验和错、部署中断、重复 deploy、回滚、无 previous、部署后流水线失败但 release/metric 状态区分。

### AIW-018 七步建模向导

- **目标**：实现设计 §14；普通员工也能理解，专业配置折叠到高级区。
- **页面**：source、profile、mapping、blueprint/lineage、validation、sandbox、release。
- **交互**：每一步显示保存状态、未决问题和下一步条件；长任务轮询 taskId，可取消；错误显示稳定 code+建议。
- **测试**：键盘/焦点、空态、长字段、100 条映射分页、刷新恢复、角色差异、E5 完整路径。

### AIW-019 故障与安全攻击集

- **目标**：把 AI/编译/发布边界做成自动回归。
- **用例**：列名/样本 prompt injection、模型返回 SQL/shell、路径穿越、PII、危险 DDL、未知 transform、循环 DAG、资源爆炸、provider 超时/限流、伪造用户、过期审批。
- **断言**：明确 error code、无生产写入、无 secret 日志、旧 ACTIVE 可读、审计存在。
- **执行**：T1 小数据每批；涉及真实 Spark/发布的负例集中到一次 T2。

### AIW-020 人工与 AI 辅助 A/B 验收

- **目标**：同一异构源 B 比较 P5 人工接入与 AI 辅助接入。
- **控制变量**：同 commit、机器、数据/checksum、source contract、验收脚本、冷/热启动说明。
- **测量**：总耗时、有效人工操作时长、字段修正数、问题数、映射 P/R、编译首过、sandbox 成功、token/费用。
- **结果规则**：保留原始 JSON/日志；没有显著或一致提升就如实写，不为了论文修改口径。
- **完成证据**：E3+E5 独立 acceptance 目录和结论摘要。

## 6. API 请求/响应最小契约

创建会话请求只接受 `sourceId`、`inputRef`、`samplingPolicyId`，用户 ID 由后端注入。所有异步写操作返回：

```json
{
  "taskId": "...",
  "resourceId": "...",
  "status": "DISCOVERING",
  "traceId": "..."
}
```

任务查询至少返回 `status/stage/progress/startedAt/updatedAt/errorCode/errorMessage/externalJobId/logSummary/evidenceRef`。错误消息可读，但判断逻辑只使用稳定 errorCode。

## 7. 合并门

### C0 契约门

- AIW-001～005 完成；Schema、迁移、评测 truth、错误码冻结。
- 不触碰生产流水线；只跑 T0/T1。

### C1 映射助手门

- AIW-006～009 完成；A/B/C 都能生成/审核草案。
- LLM 不可用可降级人工；越权和 PII 负例通过。

### C2 蓝图编译门

- AIW-010～013 完成；合法蓝图确定性编译，所有危险蓝图被稳定拒绝。
- 同一输入产物 checksum 一致。

### C3 Sandbox/治理门

- AIW-014～017 完成；真实 55 条小链成功与至少一条质量失败负例。
- 失败不切 ACTIVE，部署可回滚，审计完整。

### C4 产品验收门

- AIW-018～020 完成；七步 E5、攻击集、人工/AI A-B 报告齐全。
- 重新核对文档完成状态，不能用“接口存在”代替功能完成。

## 8. 测试速度规则

- AIW-001～013 默认不启动 Spark；使用纯 Java、Mock LLM 和 20–100 条 fixture。
- 一个合并波次只跑一次真实 55 条 sandbox/T2，不按每个任务重复。
- 只有编译器、DAG、sandbox、发布事务或指标口径变化需要 T2；页面样式、文案、单 DTO 只跑受影响测试。
- 1,000 条集群链只在 C3/C4 需要验证远程执行时跑一次；10 万/100 万只属于最终性能里程碑。
- 失败先跑最小复现；修复后跑本模块，波次合并后再过合并门。

## 9. 开工前置清单

1. P1–P5 已完成，至少有 A/B 两源确定性接入和人工基线。
2. canonical core、source profile、semantic registry、metric definition 已版本化。
3. 总控分配迁移号、prompt/Schema 版本和热点文件 Owner。
4. LLM provider 的测试 key/预算/脱敏策略已配置；无 key 时使用 Mock，不伪造真实调用。
5. Sandbox 根 namespace、资源上限、保留期和可清理范围明确。
6. 看板将 AIW-001 移为 `READY` 并登记 Owner/允许范围/反馈时间后才能编码。
