# AI 辅助多商城数仓接入与建模工作台设计 V1.0

> 状态：`APPROVED_FOR_PLANNING`
> 日期：2026-09-11
> 总指导：`../../项目完整实施指导书 V2.2.md`
> 前置设计：`2026-09-11-mall-agnostic-platform-design.md`
> 产品边界：分析平台能力；不进入参考商城或模拟数据生成器。

---

## 1. 决策摘要

平台新增“AI 辅助的多商城数据仓库接入与建模工作台”。它解决的不是“让 AI 随意建表”，而是把新商城接入时原本需要人工完成的字段理解、语义对齐、分层建模和工作流编排，变成可解释、可审核、可验证、可回滚的草案流程。

采用两阶段方案：

1. **AI 映射助手**：对源元数据和脱敏样本做画像，产生 canonical event/field/enum 映射建议、置信度和待确认问题。
2. **受控 AI 数仓设计器**：产生 ODS/DIM/DWD/DWS/ADS 蓝图与工作流草案；确定性校验器验证，受控编译器生成制品，sandbox 小数据验证，人审批后发布。

不采用“大模型直接连接 Hive 并执行 DDL/SQL”。模型输出永远是 `DRAFT`，不是可执行命令。

## 2. 用户与场景

| 角色 | 目标 | 权限边界 |
|---|---|---|
| 普通运营员工 | 选择数据源、理解字段含义、使用已经发布的分析模型 | 可查看草案解释，不能批准或部署 |
| 数据开发 `data_dev` | 审核字段映射、粒度、质量规则、指标和 sandbox 结果 | 可修改/审核草案；按权限申请发布 |
| 管理员 `admin` | 管理凭据、资源预算、审批、发布与回滚 | 不能绕过结构校验和质量门 |
| 审计人员 | 查询输入指纹、模型、修改、审批、制品与发布证据 | 只读 |

典型场景：

- 新商城使用 `uid/goods_no/pay_fee/order_state`，平台需要映射到统一用户、商品、实付金额和订单语义。
- 新源提供数据库元数据、OpenAPI、JSONL/CSV 样本之一，平台先生成画像，不要求用户手写所有表名。
- AI 不确定“sale_price 是标价还是实付价”时，创建高优先级问题，而不是自行决定口径。
- 已发布的蓝图出现回归，管理员可回到前一不可变 release，ACTIVE 指标不受失败 sandbox 影响。

## 3. 目标与非目标

### 3.1 目标

- G1：同一套平台辅助接入不同商城，而不是为参考商城生成一套写死 SQL。
- G2：所有 AI 结论均有输入指纹、模型版本、prompt 版本、置信度、修改记录和审批人。
- G3：模型输出经过机器可判定的 Schema、语义、安全、DAG 和资源校验。
- G4：用 20–100 条固定小数据完成 sandbox；失败不改生产库、不激活新指标快照。
- G5：发布物不可变、可校验、可回滚，并能与人工接入基线进行量化对比。

### 3.2 非目标

- 不自动推断不可观察的经营口径或因果关系。
- 不允许模型执行任意 SQL、shell、SSH、HDFS 删除或生产表 DROP。
- 不在 AI 阶段重新定义 canonical 核心域和已冻结指标公式。
- 不承诺任何数据源零人工确认；低置信度和业务语义必须审核。
- 不把论文写作放入当前开发任务；只保留真实评测数据与证据。

## 4. 核心模型：固定骨架 + 源扩展

平台固定六个通用业务域：`user / product / behavior / order / payment / refund`。新商城优先映射到这些域；只有确实无法表达的字段进入 `<source>_ext` 扩展，不允许每次接入都重新发明一套核心模型。

数仓层次固定为：

```text
Landing(raw + manifest)
  -> ODS(payload_json 保真 + 接入元数据)
  -> DIM/DWD(统一主键、时间、金额、枚举和交易事实)
  -> DWS(固定主题聚合)
  -> ADS(版本化指标数据集)
  -> MetricStore ACTIVE snapshot
```

AI 可以建议表、字段和转换，但层次职责、发布路径、证据要求和核心指标公式由平台契约控制。

## 5. 总体流程与状态机

```text
① 连接源/上传样本
  -> ② 采样、画像、脱敏
  -> ③ AI 生成 SourceProfileDraft / WarehouseBlueprint / WorkflowDraft
  -> ④ 确定性校验
  -> ⑤ 人工确认问题与差异
  -> ⑥ 受控编译 DDL/SQL/DAG
  -> ⑦ sandbox 小链
  -> ⑧ 对账、质量、安全和资源证据
  -> ⑨ 审批
  -> ⑩ 版本化发布/回滚
```

主状态机：

```text
DISCOVERING -> DRAFT -> NEEDS_CONFIRMATION -> VALIDATING
-> SANDBOX_RUNNING -> REVIEW -> APPROVED -> DEPLOYED
```

异常状态：`VALIDATION_FAILED / SANDBOX_FAILED / REJECTED / ROLLED_BACK`。

状态约束：

- `DRAFT` 之前不得创建业务表。
- 存在未确认的 BLOCKING review item 时不能进入 `VALIDATING`。
- `VALIDATION_FAILED` 只能生成新 blueprint version 后重试，禁止覆盖原失败证据。
- `APPROVED` 的 checksum 必须与部署输入一致；任何修改使审批失效。
- `DEPLOYED` 只表示制品已部署，不代表某次业务流水线已经成功；两者状态分开。

## 6. 数据画像

画像由确定性代码完成，不能依赖大模型计数。输入可以是 JSON/JSONL/CSV 样本、OpenAPI 或只读数据库元数据。每个数据对象输出：

- 字段路径、推断类型、可空率、唯一率、样本基数、最小/最大值和长度。
- 枚举候选及频次、时间格式/时区候选、金额小数位/单位候选。
- 主键、外键和事件时间候选；关系只标为候选，不直接生效。
- PII 分类：姓名、手机号、邮箱、地址、证件、支付标识等。
- 样本 checksum、采样策略、截断说明和源 schema version。

采样限制：默认每对象最多 100 行、每字段最多 20 个脱敏样本值、总请求体受预算控制；secret、token、密码和高风险 PII 不发送给外部模型。

## 7. 结构化中间契约

### 7.1 `SourceProfileDraft`

```text
sourceCode, inputFingerprint, schemaVersion, timezone, currency
objects[]: sourceObject, canonicalDomain, primaryKeyCandidates[], eventTimeCandidate
mappings[]: sourcePath, targetField, sourceType, targetType, transform,
            nullPolicy, enumMap, confidence, rationale, reviewRequired
questions[]: code, severity, question, affectedMappings[]
```

### 7.2 `WarehouseBlueprint`

```text
blueprintNo, version, sourceId, canonicalModelVersion
tables[]: layer, logicalName, physicalName, grain, keys[], columns[],
          partitioning, retention, scdType, sourceDependencies[]
qualityRules[], metrics[], lineageEdges[], resourceEstimate, rollbackPlan
```

### 7.3 关键子对象

| 对象 | 必填内容 |
|---|---|
| `TableBlueprint` | layer、名称、粒度、主键、列、分区、保留期 |
| `ColumnMapping` | sourcePath、target、类型、转换、空值策略、置信度 |
| `QualityRuleDraft` | ruleCode、层、表达式类型、严重度、阈值、证据字段 |
| `MetricDraft` | metricCode、依赖字段、聚合方式、时间粒度、口径版本 |
| `WorkflowNodeDraft` | nodeId、nodeType、inputs、outputs、retryPolicy、resourceProfile |

所有对象使用 JSON Schema 版本化。未知字段默认拒绝，避免模型“多说一句”被执行器误读。

## 8. 持久化结构

| 表 | 关键字段 | 约束 |
|---|---|---|
| `ai_modeling_session` | id、session_no、source_id、source_profile_version、status、llm_provider、model、prompt_version、input_fingerprint、created_by、started_at、finished_at、error_code/detail | session_no 唯一；用户身份来自后端会话 |
| `source_profile_draft` | id、session_id、version、draft_json、confidence_summary、questions_json、status、checksum、created_at | `(session_id,version)` 唯一；只追加版本 |
| `mapping_review_item` | id、draft_id、source_path、target_field、inferred_type、confidence、rationale、status、reviewer、comment、reviewed_at | BLOCKING 未确认则不可校验 |
| `warehouse_blueprint` | id、session_id、blueprint_no、version、canonical_model_version、source_id、blueprint_json、status、checksum、created_at、approved_by、approved_at | checksum 绑定审批 |
| `workflow_definition` | id、blueprint_id、workflow_code、version、nodes_json、edges_json、status、checksum | DAG 无环；节点白名单 |
| `modeling_validation_result` | id、blueprint_id、check_code、severity、status、message、evidence_json、created_at | 结果不可覆盖 |
| `workflow_release` | id、blueprint_id、release_no、status、target_namespace、artifact_uri、artifact_checksum、deployed_by、deployed_at、previous_release_id、rollback_at | 发布物不可变；回滚只切指针 |

大 JSON 存原始结构，同时将状态、版本、checksum、外键、审计字段结构化，避免只能全文扫描。

## 9. AI 推理任务

AI 可以执行：

1. 根据画像对齐 canonical domain、字段和枚举。
2. 解释候选映射理由并给置信度，不足时产生问题。
3. 建议事实/维度表、粒度、SCD 类型、分区和代理键策略。
4. 建议质量规则、指标候选、血缘边和资源估算。
5. 解释 validator/sandbox 失败，并提出“修改草案”的建议。

AI 不能决定：

- GMV、净销售、退款、复购等冻结公式。
- 模糊字段在无证据时的强行映射。
- 删除/覆盖现有生产表或跳过质量门。
- 生产凭据、资源上限、审批身份和发布目标。
- 自由格式 SQL/shell 作为工作流节点。

## 10. 确定性校验器

`BlueprintValidator` 按固定顺序执行并返回全部问题：

1. JSON Schema 与版本兼容。
2. source、namespace、表和列命名白名单。
3. 核心域必填字段、类型与空值策略。
4. 主键稳定性、代理键策略、事实表 grain 唯一性。
5. 时间、时区、金额单位和 decimal 精度。
6. 指标依赖字段、公式模板和口径版本。
7. lineage 输入输出存在、DAG 无环、禁止跨 source namespace。
8. SQL AST 只允许 SELECT/INSERT OVERWRITE sandbox 目标等受控形态；禁止 DROP/TRUNCATE/ALTER 生产对象。
9. 分区/扫描量/并行度/超时在资源预算内。
10. PII 字段不可出现在日志、prompt 明文和非授权 ADS。

BLOCKING/ERROR 使验证失败；WARN 必须显示并被审核，但是否阻断由规则定义决定。

## 11. 受控编译与工作流

编译器不接受自由文本 SQL，而是从 `WarehouseBlueprint` 和已登记模板生成制品。允许的节点类型仅为：

`DISCOVER_SOURCE / LANDING_VALIDATE / ODS_LOAD / DIM_BUILD / DWD_BUILD / DWS_AGG / ADS_BUILD / QUALITY_CHECK / PUBLISH_METRIC`

每个节点必须声明输入、输出、幂等键、重试策略、超时、资源档位和失败行为。编译产物包含：

- versioned DDL/SQL/job definition；
- blueprint/workflow/schema/prompt 版本；
- artifact SHA-256；
- 逻辑与物理血缘；
- 资源估算和 rollback manifest。

禁止节点直接携带 UI 输入形成的命令；远端 Spark 仍调用固定 wrapper 与白名单参数。

## 12. Sandbox、发布和回滚

目标 namespace 为 `<prefix>_sandbox_<draftId>`，只处理 20–100 条固定数据。必须检查：接收/隔离数、主外键、金额、状态机、DWD-DWS-ADS 对账、指标范围、PII、分区、幂等重跑和资源消耗。

Sandbox 成功只进入 `REVIEW`。审批时冻结 blueprint checksum、validator 结果、sandbox runId、制品 checksum、审批人和时间。部署失败不得切换 ACTIVE release；回滚通过 `previous_release_id` 恢复前一发布指针，不就地修改历史制品。

## 13. API 契约

| 方法与路径 | 作用 | 返回/约束 |
|---|---|---|
| `POST /api/v1/ai-modeling/sessions` | 创建会话 | sessionId；绑定当前用户/source |
| `POST /sessions/{id}/discover` | 采样与画像 | taskId；异步 |
| `POST /sessions/{id}/generate` | 生成画像/蓝图草案 | taskId；不执行 SQL |
| `GET /sessions/{id}` | 状态与版本摘要 | 不返回未脱敏 secret |
| `GET /sessions/{id}/review-items` | 待审核映射 | 支持 severity/status 筛选 |
| `PUT /review-items/{id}` | 接受、修改或拒绝 | 乐观锁；记录 reviewer |
| `POST /sessions/{id}/validate` | 确定性校验 | taskId + validation summary |
| `POST /sessions/{id}/sandbox-runs` | 小数据验证 | taskId/runId/log/evidence |
| `POST /sessions/{id}/approve` | 审批 checksum | data_dev/admin；审计 |
| `POST /releases/{id}/deploy` | 版本化部署 | admin；幂等 |
| `POST /releases/{id}/rollback` | 回滚前一 release | admin；原因必填 |

长操作统一使用 taskId，提供状态、阶段、externalJobId、日志摘要、错误码和证据链接；不得让 HTTP 请求阻塞等待 Spark 完成。

## 14. 页面设计

七步向导：

1. 数据源：连接方式、对象和采样范围。
2. 数据画像：类型、空值、唯一率、枚举、PII 和异常。
3. 映射审核：源字段→目标字段、置信度、理由、问题和批量筛选。
4. 数仓蓝图：分层表、粒度、主键、分区、指标和可视化血缘。
5. 验证：规则问题按严重度定位到表/字段。
6. Sandbox：阶段、日志、对账、质量、资源和前后差异。
7. 发布：审批摘要、版本差异、部署状态和回滚入口。

页面对普通员工使用业务术语和解释，不暴露 Hive 参数；高级详情只对 data_dev/admin 展示。

## 15. 安全与失效策略

- 样本、列名和注释全部视为不可信内容，放入严格数据区而非系统指令区。
- prompt injection、SQL/shell 注入、路径穿越、循环 DAG、危险 DDL、PII 外发均有固定负例。
- LLM 超时、限流、网络、鉴权、格式错误分别记录稳定错误码；有限重试只用于幂等调用。
- 超预算或 provider 不可用时保留人工配置路径，状态明确显示降级原因。
- 审批人不能由请求体伪造；从认证上下文取得。修改已审批蓝图自动撤销审批。

## 16. 评测与验收

固定三个数据源：A 参考商城、B 异构合法源、C 歧义/恶意源。核心指标：

- 字段映射 precision/recall、必填字段 recall、枚举覆盖率；
- 蓝图 validator 通过率、编译首过率、sandbox 成功率；
- 人工修正项数、接入总耗时、token 与估算费用；
- 安全攻击拦截率、失败后 ACTIVE 不变、回滚成功率。

P5 人工接入 B 为基线，AI 接入同一 B 为实验组。数据、机器、版本和验收脚本必须相同；先记录原始结果，再决定论文中是否可写效率提升。

## 17. 依赖与阶段边界

P1–P3 提供确定性基础；P4 提供数据源/按源指标界面；P5 提供人工异构源基线。AIW 在 P5 后进入主实施，允许提前完成纯契约、评测集和 UI 原型，但不得抢占迁移号、核心契约或 P1–P5 热点文件。

本设计不改变当前首任务：仍从 `P1-01` 开始。详细任务见配套实施书。
