# 分析平台商城无关化 P1–P5 实施书

> 状态：`READY`
> 设计依据：`../specs/2026-09-11-mall-agnostic-platform-design.md`
> 总指导：`../../项目完整实施指导书 V2.1.md`
> 进度入口：`../../项目实施进度与任务看板.md`
> 当前实现基线：`076f5d2`；最新真实链路 run 39 / `S20260901_39`。
> 本文件只规定实现，不授权删除历史数据、编写论文或美化商城。

---

## 1. 目标、成功判据与边界

目标是让分析平台在不修改核心 Java/Scala/SQL 模板的情况下接入字段、枚举和 ID 形状不同的商城。参考商城与独立生成器只是两个可选数据提供者；平台没有它们仍可运行并读取历史 ACTIVE 快照。

最终成功必须同时满足：

1. 源 A（当前参考商城）改造前后核心指标逐值一致。
2. 源 B 只新增源画像、登记数据和测试夹具，不修改核心 Java/Scala 即可跑通。
3. 每个 source 有独立 Hive namespace，指标服务表每行带 source 标识。
4. UUID、雪花、纯数字、前缀数字 ID 均能生成稳定代理键并成功关联。
5. ODS 原样保留 payload；额外字段不需要 DDL 变更。
6. 普通员工可在页面选择数据源，管理员可测试、启停并查看映射校验。

禁止事项：

- 不直接连接或修改任何商城业务库。
- 不把 source code、事件名、行为枚举和库名写回 SQL 字面量。
- 不在多个模块各保存一份可独立修改的同义词表。
- 不让开发 Agent 自行决定迁移号、兼容期、质量阈值或指标口径。
- 不执行历史 checkpoint 清理；该事项独立于 P1–P5。

## 2. 总体交付波次

| 波次 | 任务 | 可并行项 | 合并门 |
|---|---|---|---|
| W0 | 契约/迁移/表名/源标识冻结 | 只读审查可并行；文件修改串行 | G0 契约门 |
| W1 | P1 源登记与 warehouse namespace | UI 原型、测试夹具设计可并行 | G1/G2，随后一次 T2 |
| W2 | P2 ODS 保真与代理键 | Java/Scala 测试向量可分工，但 owner 只有一个 | G1/G2，随后一次 T2 |
| W3 | P3 词汇与语义注册表 | 采集映射、Spark 模板可在契约冻结后并行 | G3，随后一次 T2 |
| W4 | P4 指标按源与最小页面 | 后端与页面在 API 冻结后并行 | G3/G4 |
| W5 | P5 异构源验收 | 夹具、验收脚本、页面验证可并行 | G4/E3/E5 |

每个任务控制在 30–120 分钟。超过两小时仍无法形成独立证据时，必须拆任务，不允许以一个“大任务”连续运行十几个小时。

## 3. 公共数据契约

### 3.1 `source_registry`

由总控分配迁移号。字段：

| 字段 | 类型/约束 | 规则 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 平台内部 ID |
| source_code | VARCHAR(64) UNIQUE NOT NULL | `[a-z][a-z0-9-]{1,63}`，不可改名 |
| display_name | VARCHAR(128) NOT NULL | 页面显示 |
| ingest_mode | VARCHAR(16) NOT NULL | 当前仅 FILE；JDBC/HTTP/KAFKA 为能力状态 |
| profile_path | VARCHAR(255) NOT NULL | 仓库相对路径，禁止 `..` 与绝对路径 |
| timezone | VARCHAR(64) NOT NULL | IANA timezone |
| currency | CHAR(3) NOT NULL | ISO 4217 |
| status | VARCHAR(16) NOT NULL | DRAFT/ACTIVE/PAUSED/DISABLED |
| profile_version | VARCHAR(32) NOT NULL | 与源画像一致 |
| created_at/updated_at | DATETIME(3) NOT NULL | 审计 |

`runtime_profile` 增 `source_id`。存量 active profile 回填当前参考源；兼容期可空，但启动流水线时不可空。

### 3.2 warehouse namespace

建立唯一值对象 `WarehouseNamespace`，只接受 source registry 与 runtime profile 的受控前缀。命名：

```text
<prefix>_ods / <prefix>_dwd / <prefix>_dim / <prefix>_dws / <prefix>_ads
```

前缀只允许小写字母、数字和下划线，长度受限；不得把任意请求参数直接拼入 SQL。所有 22 个文件/244 处旧 `dw_*` 引用只能从这一对象取得。测试扫描主源码，除兼容迁移和测试夹具外不允许出现裸 `dw_ods` 等字面量。

### 3.3 ODS v2 公共列

四主题 ODS 至少共享：`event_id`、`source_system`、`schema_version`、`raw_event_type`、`event_time`、`ingest_time`、`ingest_batch_id`、`landing_file`、`payload_json`、`payload_hash`、`dt`、`hour`。

`payload_hash = SHA-256(UTF-8 原始 payload 字节)`；不可对 JSON 重排后再算，否则无法证明原样保真。去重仍以 `(source_system,event_id)` 为业务键，hash 用于冲突诊断，不代替业务键。

### 3.4 代理键

统一测试向量文件保存至少 12 组输入：空白、大小写、中文、UUID、雪花 ID、纯数字、前缀数字、最大长度和两个 source 的相同 raw id。算法：

```text
normalized = UPPER(TRIM(raw_id))
material = source_code + "|" + entity_type + "|" + normalized
digest = SHA-256(UTF-8(material))
surrogate = digest 前 8 字节按无符号解释，清除符号位，0 映射为 1
```

Java 与 Scala 分别实现，但必须读取同一测试向量。空 raw id 不生成代理键，进入 DQ/reject；相同输入稳定、不同 source 不碰撞。维表同时保留 `raw_*_id`。

## 4. P1：源登记与库名收口

### P1-01 冻结基线

- 输入：run 39 / `S20260901_39`。
- 输出：核心指标 JSON、当前库表清单、关键表行数、当前 active profile、warehouse 路径和校验和清单。
- 禁止：为了得到“好看基线”修改历史证据。
- DoD：基线文件可由脚本重新读取；明确哪些数值来自 1,000 条严格生成器数据，哪些来自旧 fixture。
- 测试：只读，无 Spark 重跑。

### P1-02 建表与回填

- 由总控创建迁移：`source_registry`、`runtime_profile.source_id`、必要索引和外键。
- 种子源 `mock-mall` 的 `profile_path` 指向受控相对路径；状态 ACTIVE。
- 回填只覆盖 source_id 为空的存量 profile，不改已有版本和激活时间。
- DoD：迁移首次/重复启动均成功；回滚设计不删除业务数据。
- 测试：迁移单测 + 真实 MySQL schema 查询。

### P1-03 元数据模型/API

- 新增 SourceRegistry entity/mapper/repository/service/controller；禁止 controller 直接访问 mapper。
- API：list/get/create/update/test/pause/activate；source_code 创建后不可改。
- 返回 DTO 不包含 credential 值和绝对本机路径。
- 激活前校验 profile 文件存在、Schema 合法、sourceCode/version 一致。
- 失败码：SOURCE_NOT_FOUND、SOURCE_CODE_IMMUTABLE、SOURCE_PROFILE_INVALID、SOURCE_IN_USE。
- DoD：并发激活只有一个当前源；每次变更有审计。

### P1-04 warehouse 名称唯一所有者

- Java/Scala 建立单点名称对象和白名单校验。
- INIT_SCHEMA、全部 JobArgs/SQL、质量、发布和验收查询统一消费它。
- `hiveDatabasePrefix` 必须真正生效；不再只是表中字段。
- DoD：源码扫描除唯一 owner 外无裸库名字面量；非法前缀在提交 Spark 前失败。

### P1-05 checkpoint 与 manifest 加 source

- checkpoint 查询/唯一键逻辑加入 source/connector 维度；已有路径 canonical owner 保持不变。
- manifest 写 sourceCode/sourceId/profileVersion/mappingVersion。
- 同一路径被两个源登记时不会互相推进断点。
- 不清理现有 50 行历史副本。

### P1-06 P1 验收

- T0：source code/namespace/profile path 校验纯单测。
- T1：迁移、API、两源同路径 checkpoint 隔离；20 条夹具。
- T2：只跑一次 golden-55 本地链；源 A 指标与 P1-01 基线逐值一致。
- 证据：迁移结果、API 响应、库名清单、runId、snapshotId、指标 diff。
- 出口：P1 所有任务 `VERIFIED_L2`；否则不得进入会改 ODS 的 P2。

## 5. P2：ODS payload 保真与代理键

### P2-01 ODS v2 DDL

- 为每个 source namespace 建 v2 表；不在原多源表上混写。
- payload_json 保存原始对象字符串，raw_event_type 保存源词汇，source_system 由平台注册表注入而非信任外部值。
- schema 不匹配时只生成“需要重建”的计划，必须经 INIT_SCHEMA 审计步骤执行。

### P2-02 Landing 解析

- 显式 StructType 只解析信封和原始 payload 字符串；不可依赖 Spark 自动推断。
- event_time 依 source profile 的格式列表依次解析；最终统一 UTC，保留 source timezone 元数据。
- 未知版本/无法解析时间/缺 eventId 进入 quarantine，保留原因码和原位置。

### P2-03 代理键接线

- 用 `SurrogateKeys` 代替单一前缀剥离 `IdCodec`。
- 用户、商品、订单、退款等实体使用 entity_type 区分命名空间。
- DWD 和 DIM 均保留 raw id；join 使用代理键，但对账能回到 raw id。
- 禁止 `CAST(raw_id AS BIGINT)` 作为通用接入策略。

### P2-04 DWD 映射骨架

- 首阶段可为 mock-mall profile 实现字段投影，但入口必须是 `SourceProfile`，不能读取全局常量。
- 映射缺失输出 NULL 并让质量规则统计；不能用看似合理的默认金额/状态填充。
- items 字符串和 JSON array 的兼容只对 legacy profile 开启并有命中计数。

### P2-05 重建与回退

- 重建前创建目标 source warehouse 备份清单；记录表、分区、行数、路径、checksum。
- 重建只允许目标 namespace，拒绝空前缀、根目录、通配符和当前未选择源。
- 失败后旧 ACTIVE 指标仍可读；新 snapshot 不激活。

### P2-06 P2 验收

- Java/Scala 代理键测试向量逐项同值。
- UUID/雪花/纯数字/前缀数字 join 成功率 100%。
- 多余字段只进入 payload_json，不需要 DDL。
- 同一源同一事件重放不重复；两个源同 eventId 不互相去重。
- T2 只跑一次；源 A 核心指标逐值一致。

## 6. P3：源画像与分析语义注册表

### P3-01 SourceProfile loader

- 只读取仓库内受控相对路径；拒绝路径穿越、未知版本、重复 canonical target、非法转换。
- 文件加载后生成不可变对象，并以 sourceCode+profileVersion 缓存；文件变化不影响在跑 run。
- 每个 pipeline run 保存 profile checksum/version。

### P3-02 EventNormalizer

- 顺序固定：解析信封→校验来源→事件类型映射→字段提取→枚举映射→时间/金额转换→canonical 校验→写 accepted/quarantine。
- 允许转换白名单：trim、case、decimal scale、epoch/timezone、受控 enum map、JSONPath 读取。
- 禁止脚本、反射、任意 SQL、网络调用。
- 失败原因码分别表示 UNKNOWN_EVENT_TYPE、FIELD_REQUIRED、ENUM_UNKNOWN、TIME_PARSE、AMOUNT_PARSE、PROFILE_VERSION。

### P3-03 legacy 兼容策略

- `mock-mall-legacy-v1` 只为旧 golden/landing 提供 source-scoped 兼容。
- 严格新 golden-55 由独立生成器输出；其 `synthetic=true` 只在 run/manifest/验收记录。
- 记录每种兼容规则命中数；当严格数据完成 T2 后关闭兼容，再删除兼容代码。
- 不能把兼容行为加入全局 canonical schema。

### P3-04 语义注册表

- `semantic_registry`：source、domain、raw_code、canonical_code、label、funnel_stage、weight、version、effective_from/to、status。
- `dimension_registry`：dimension_code、source、resolver_type、resolver_config、label、enabled、version。
- resolver 只允许 payload/dim/constant 三类受控策略，禁止自由 SQL。
- 迁移 seed 与 source profile 不重复拥有同一事实：profile 管源词汇到 canonical；DB 管 canonical 到分析含义。

### P3-05 SQL 模板去业务字面量

- 漏斗、状态、指标通过注册表 join 或受控参数得到语义。
- 自动扫描 SQL/Scala 主源码，`'view'/'favorite'/'cart_add'` 等只可出现在 seed/fixture/profile，不可在通用模板。
- 注册表缺语义时指标为 unavailable 并输出原因，不能默认为 0。

### P3-06 P3 验收

- 未知事件类型隔离且 reason 正确；新增映射无需改 Java/Scala。
- 源 A 指标不变；关闭 legacy 后严格 golden-55 0 非预期隔离。
- profile dry-run 输出 input/accepted/quarantine/reason counts/field coverage/enum coverage。
- I1 源码扫描门通过。

## 7. P4：指标、接口与页面按源

### P4-01 指标定义按源

- `metric_definition` 增 source_code（NULL 为通用）和 required_for_overview。
- 同 code 的 source-specific 定义覆盖通用定义；版本和生效日期必须唯一。
- 发布质量门读取当前 source 的 required 集，不能再使用全局常量集合。

### P4-02 指标事实带 source

- metric snapshot/value/ADS 服务表均能定位 source；唯一键加入 source 或 snapshot 已不可变绑定 source。
- 一次 publish 只能属于一个 source；切换 ACTIVE 只影响同 source 的快照。
- 源 B 发布失败不能让源 A 的 ACTIVE 失效。

### P4-03 API

- `/sources`：列表、详情、能力、状态、profile 校验。
- `/metrics/*?sourceCode=`：显式按源；省略时用当前激活源，并在响应 envelope 回传 sourceCode、snapshotId。
- `/pipelines`、`/ingestion/status` 和 AI EvidencePackage 都带 sourceCode。
- 非管理员不可修改源配置；普通分析用户可切换可见源。

### P4-04 最小数据源管理页面

- 管理员：列表、状态、最后采集时间、可采集文件数、测试连接、启停、设为当前源、查看画像版本和 dry-run 结果。
- 普通员工：只显示源选择器和业务名称，不显示 profile path、SSH/Hive 凭据或 runtimeProfileId。
- 首版不做拖拽字段映射；错误以业务语言显示，并可展开技术 traceId 供管理员排查。

### P4-05 页面与 AI 同源

- 所有页面切换源后清空旧请求并以 sourceCode 重新加载，防止 A/B 数据混屏。
- AI 问数与决策从页面当前源和 snapshot 构建 EvidencePackage；不接受模型自行换源。
- Hive/MySQL/API/DOM/AI 五处同值断言加入 sourceCode。

### P4-06 P4 验收

- A 源要求 UV、B 源不要求 UV 时，两者发布门分别正确。
- 两源 ACTIVE 独立；切换源后指标、AI 证据和决策来源一致。
- E5：普通员工从登录到选源、看总览、问数全程不接触技术 ID。

## 8. P5：异构源 B 端到端证明

### P5-01 fixture B 规格

固定 seed，55 条左右，覆盖用户、商品、浏览、收藏/加购、下单、支付、取消、退款。必须与源 A 存在真实差异：

- 原事件名：`product_viewed/add_to_cart/payment_success/contract_signed` 等。
- 字段：`buyer_id/item_id/pay_money/created_at`。
- ID：UUID 用户、前缀商品、雪花订单。
- 枚举：`browse/wishlist/purchased`。
- 额外字段：`coupon_code`，只保真不进入第一版标准指标。
- 至少一个未知事件和一个未知枚举，用于验证隔离。

### P5-02 接入变更约束

接入源 B 的变更只允许：source profile JSON、source registry seed/API 数据、fixture、验收脚本和证据文档。若必须修改核心 Java/Scala，P5 判失败，回到 P1–P3 修平台抽象，不能把补丁伪装成 Adapter。

### P5-03 全链验收

1. profile dry-run。
2. Landing/manifest/checkpoint。
3. ODS payload 保真与 quarantine。
4. DWD 代理键与 100% 合法行关联。
5. DWS/ADS/质量门。
6. MySQL ACTIVE 发布。
7. 页面切换源 B。
8. AI/决策 EvidencePackage source/snapshot 一致。
9. 再查询源 A，指标仍与基线一致。

### P5-04 开源商城后续入口

P5 通过后再选择开源商城。筛选项：许可证、维护状态、Java/MySQL 兼容、可部署性、订单/退款数据可导出、接口稳定和文档完整。接入必须在独立仓库/数据库/进程完成，只新增 Adapter/Profile/映射；不开启时平台照常工作。

## 9. 集群工作的并行位置

P1–P5 先在本地小链证明语义正确。集群 Lane 可并行做只读配置收集和 fake 测试，但以下依赖串行：source/namespace 冻结后才能定 HDFS 路径；manifest v1 冻结后才能改 Flume；真实 externalJobId 协议完成后才能跑 YARN smoke。

集群第一条真实路径只跑 100–500 条、一天、1–2 个分区：Flume→HDFS raw→READY manifest→Spark on YARN→Hive→MySQL。通过后再跑固定 seed 1,000 条；10 万/100 万/100 万属于最终性能阶段。

## 10. 合并门与回报格式

### G0 契约门

输入/输出/失败态/表/API/DoD 已冻结；迁移号与热点文件 owner 已分配。

### G1 代码门

只有允许目录有 diff；无 TODO 占位、默认口令、跨商城库依赖、通用 SQL 业务字面量。

### G2 快测门

Agent 回报准确命令、测试数、失败数、耗时和报告路径；不得只写“测试通过”。

### G3 合并门

总控跑受影响 reactor、契约和 API 测试；Agent 不自行解决公共契约冲突。

### G4 里程碑门

每个 P 阶段合并后只跑一次 golden-55；只有集群相关变化才跑 cluster smoke。

### G5 最终门

P5 完成后执行一次全模块回归和 269 条重新核查；性能测试在功能/口径冻结后另跑。

每次回报固定四项：已完成；正在做；新发现问题；下一步与下次检查点。超过一个反馈周期的阻塞必须标 BLOCKED。

## 11. 当前可直接派发的第一个任务包

```text
任务 ID：P1-01
标题：冻结 source A 商城无关化前基线
目标：建立 P1–P5 全部回归的只读比较基准。
允许修改：新增 docs/acceptance/p1-baseline-*/ 与只读验收脚本；更新进度看板。
禁止修改：任何 Java/Scala/Vue/SQL/数据库数据；禁止重跑大数据。
输入：run 39、snapshot S20260901_39、当前 ACTIVE runtime profile。
输出：指标 JSON、库表/分区/行数清单、active profile、warehouse 路径、证据 README。
边界：区分严格生成器 1,000 条与旧 fixture 的来源；不得混写。
最小测试：脚本二次读取结果一致；文件 JSON 可解析；所有查询只读。
完成定义：总控复核证据可定位、口径完整后标 DONE。
反馈：开工登记；30 分钟内首次回报。
```

P1-01 完成后，总控依次派发 P1-02 和 P1-04；两者不可由不同 Agent 同时修改迁移/公共配置。
