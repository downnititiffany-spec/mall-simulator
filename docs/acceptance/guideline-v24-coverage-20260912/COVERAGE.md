# 指导书 V2.4 逐条覆盖对照表

> 本文件是审计交付物之一。口径、方法、统计数字与「本审计不能证明什么」见同目录 `README.md`。
> **审计对象（被冻结的测量目标）**：`docs/项目完整实施指导书 V2.4.md`
> 720 行 / 32,468 字符 / sha256 `580AD7BE46B2F395400D9CD0812C58D4D3621794B8604B4E606EF44420C4535D`（本审计实测，见 `raw/01-identity-v24-kanban.txt`）
> **审计时刻**：2026-09-12 23:06–23:2x +08:00（`HEAD = eeccf2d`，22:59:46）。看板 V2.2 与 `docs/acceptance/**` 在本审计期间**仍在被其他泳道修改**（见 README §「本审计不能证明什么」）。

## 状态词表（本表使用）

| 状态 | 含义 |
|---|---|
| `DONE` | 有可复核的 E2/E3/E4 级证据（验收文件内命令＋结果，或代码实体＋断言），且本审计已核对该指针存在 |
| `DONE_LIMITED` | 只满足判据的一半；缺口写在该行「缺口/说明」列 |
| `IN_PROGRESS` / `REVIEW` | 有交付但未出出口判据（看板或验收文件自述） |
| `TODO` | 有**正向证据**表明未做（如全仓 0 命中并写明搜索范围，或看板路由为未开工） |
| `BLOCKED` | 依赖外部输入或前置未满足 |
| `未取证` | **找不到任何证据**，既不能证明做了也不能证明没做（本审计明示搜索范围） |
| `N/A` | 该项是禁止项/治理规则/口径规则，不是可交付物；其合规性另列 |
| `DEFERRED` | 指导书自身声明推迟 |

---

## §1 当前结论与本版目标（L15–33）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-001 | L29 | 不撰写论文正文、摘要、结论、答辩稿 | N/A（禁止项） | 全仓未发现论文/答辩稿文件；`docs/` 下无此类交付物 |
| V24-002 | L29 | 不为了论文包装未完成能力 | N/A（禁止项） | 合规性无法由文件证明；与 V24-155 的 A/B 未测同源 |
| V24-003 | L31 | 未在相同环境、相同数据、相同口径下测量的「提升百分比」不得填写 | N/A（约束） | 未发现任何已填写的提升百分比；`docs/acceptance/p5-heterogeneous-source-20260912/IMPL-REPORT.md:13` 只给 ACCEPT/QUARANTINE 计数，无百分比 |
| V24-004 | L31 + L609 | 必须用同一异构源 B 做人工基线 vs AI 辅助的 A/B 对照 | TODO | 人工基线侧已有（`p5-heterogeneous-source-20260912`），AI 侧 0 证据（见 V24-160）⇒ 对照不可能存在 |
| V24-005 | L33 | T4（10 万/100 万条性能）不做日常回归 | N/A | 未发现 T4 运行记录，符合约束 |

## §2 文档与完成状态规则（L35–65）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-006 | L39–45 §2.1 | 权威顺序：冻结契约 → 指导书 → 看板 → 专项设计 → V2.1 及以前 | DONE | 看板 V2.2 头部 L1–9 同序声明；`docs/README.md`（57 行）标唯一入口 |
| V24-007 | L45 | 与冻结契约冲突时先改契约，不得先改代码绕过 | DONE_LIMITED | 有正向流程实例（D-096 裁定 P2-03 越权扩表须回滚，看板 L496 区域）；但同轮又出现 F-88「文档—代码不一致」（看板 L514 第 ⑧ 项）⇒ 流程存在但**不是每次都走** |
| V24-008 | L49 §2.1.1 | 已编号文稿不得覆盖或末尾持续追加；实质变化须先备份再出新版本 | N/A（治理规则） | **发现 R-01**：V2.4 自述「本版只改 §4.1.1.3 表内三行＋文首 4 行」（L714）= 就地改写，与本节规则文字冲突。本审计不自行调和 |
| V24-009 | L50 | V3.0 建立条件（V2 系列出口判据全完成） | N/A | 出口判据未完成（见 §11 行），故不应建 V3.0；未发现 V3.0 |
| V24-010 | L51 | 指导书与看板版本一致；日常只更新状态单元格，不以自由文本在文末堆叠 | N/A（治理规则） | **发现 R-02**：看板 V2.2 第 388–521 行是 append-only 自由文本台账（`\| \| 时间 \| … \|` 无行号列），与「只更新状态单元格」冲突。版本号一致（均 V2.x） |
| V24-011 | L52 | `docs/README.md` 只把一个版本标为当前权威入口 | DONE | `docs/README.md` 实测 57 行 / 3,117 字符 / sha256 `F494DF28…` |
| V24-012 | L53 | 一项决策/一次验收一个文件（ADR/证据方式） | DONE_LIMITED | `docs/acceptance/**` 实测 49 个子目录 / 1,416 文件（`raw/02`）；但同期仍存在 `docs/开发过程事实与决策记录.md`（D-050 已裁为历史材料，保留原地，属已裁定例外） |
| V24-013 | L57 §2.2 | 状态词表限定为 10 个词；普通功能需 E2、数据链需 E3、集群能力需 E4、员工页面路径需 E5 | DONE | 看板 L12–46 定义 E1–E5 与 T0–T4；本审计沿用同一词表 |
| V24-014 | L61 §2.3 | 开工前登记任务 ID、Owner、允许修改范围、下次反馈时间 | DONE_LIMITED | 看板任务表有 ID/Owner/范围列；「下次反馈时间」列在本审计抽读的行中未见独立列 |
| V24-015 | L62 | 最长 30 分钟更新一次 | 未取证 | 更新频率无法由静态文件判定；看板台账时间戳已被登记为「14 处未来/不准」（陷阱记录），`git log --format=%ci` 才是权威时间轴（本审计遵此） |
| V24-016 | L63 | 代码写完只能进 `REVIEW`；通过目标测试并附证据才能 `DONE` | DONE | 看板对 P2-03 明确「维持 `REVIEW`（人裁定，不得 DONE）」L514 第 ⑦ 项；P3-01-a 裁为 `DONE_LIMITED` 并写明「不升 DONE」L513 第 ① 项 |
| V24-017 | L64 | 开发 Agent 只执行分配的任务 ID，不得自行设计新模块或顺手改其他模块 | N/A（治理规则） | **发现 R-03**：D-096 认定 P2-03 泳道越权新增表 `dwd_surrogate_key_quality` 并把 `OdsV2SchemaOwnerSpec.scala` 的 `statements.size` 由 37 改为 38，裁定回滚（看板 L496 区域）；回滚是否完成在本审计时刻**未取证** |
| V24-018 | L65 | 总控负责契约、热点文件、合并、跨模块测试与最终状态确认 | DONE | 看板 L589 热点文件清单；L61–69 热点文件节；D-142 人裁决落盘（看板 L515 行区域） |

## §3 三程序架构裁决（L67–112）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-019 | L73 | 分析平台 8091 职责；禁止商城增删改/随机造订单/依赖商城库与 Java 包 | DONE | 8091 `/` = 200（608 B）实测于 `docs/acceptance/e5-preaccept-20260912/README.md` 第 1 项；边界测试见 V24-030 |
| V24-020 | L74 | 参考商城 8090 职责；禁止分析任务、Spark/Hive、生成场景、直接写分析平台 | DONE | 8090 `/` = 200（407 B）同上级；职责原文有显式声明（同处第 1 项） |
| V24-021 | L75 | 生成器 8092 或 CLI 职责；禁止直连商城库、依赖商城内部 Service/Mapper、写平台业务库、触发平台流水线 | DONE_LIMITED | 边界测试 `GeneratorBoundarySourcePolicyTest.java:72,151`（生成器 Java 无耦合、pom 不依赖另两程序）；**缺口**：8092 `/` = 404，模块内 0 个页面文件（`raw/06` 第 11 项、`raw/07` C 节） |
| V24-022 | L77 | 建立中立 `contract-specs`（版本化 JSON Schema / OpenAPI / 清单 Schema）；三程序不得通过共享 Java 实体、Mapper 或 Maven 业务依赖耦合 | DONE | `contract-specs/VERSION:1` = `2.2.0`（实测）；`GeneratorBoundarySourcePolicyTest.java:151-164` 断言 pom 无另两程序构件且无 `<modules>` |
| V24-023 | L82–86 | 唯一正常业务路径：生成器 →公开 HTTP→ 商城 →Outbox/事件文件→ 连接器 → Landing → 标准化 → Hive ODS/DWD/DWS/ADS → 质量门 → MySQL ACTIVE 快照 → 页面/AI/决策 | DONE_LIMITED | 端到端一次实测：页面加购 → `cart_item id=37` → `event_outbox id=2949240` → 8090 日志 `outbox publish: ok=1 failed=0` → `landing/events/2026091221.jsonl`（497 B）→ 批次 44 SUCCESS（`e5-preaccept-20260912/README.md` 第 2 项）。**缺口**：**标准化环节不存在**（见 V24-058/V24-086），路径中间断了一环 |
| V24-024 | L90 | 参考商城只是可替换的数据提供者和演示样例，不是平台的一部分 | DONE | V24-030/031 的边界测试即为该项证据 |
| V24-025 | L91 | 没有生成器、没有参考商城时，平台仍必须能连接其他商城并读取历史 ACTIVE 快照 | DONE_LIMITED | 历史 ACTIVE 快照可读 ✅（`/ops` 归档快照 `S20260901_43` 点「查看指标」仍可读 10 行，`e5-preaccept` 第 3 项）；**「连接其他商城」✘**——P5 实测结论「本平台无法仅靠登记配置接入异构源」（`p5-heterogeneous-source-20260912/IMPL-REPORT.md:13`，登记为 F-89） |
| V24-026 | L97 | `MALL_API` 模式只经商城公开接口完成注册/浏览/加购/下单/支付/取消/退款；禁止调用内部类、直连商城库、改库存表 | DONE_LIMITED | 适配器与注册表齐（`raw/06` 第 1/2/7 项）；B-04 已裁（看板 L337：商城增加行为埋点公开 API，`RESET_STATE` 由 capability 声明）⇒ 规格阻塞已解除。**缺口**：能力为 `UNDETERMINED`/`ABSENT` 时行为链无法在真实商城跑通，本审计未见真实异商城 MALL_API 全链证据 |
| V24-027 | L99 | 脏数据不得在 MALL_API 模式绕过商城校验直接写库；验证隔离能力须用 B 模式 | DONE | `MallApiGenerationEngine.java:120-123` 响亮拒绝脏数据档；`MallApiGenerationEngineTest.java:445`、`MallApiGenerationSmokeTest.java:364` |
| V24-028 | L103 | `CANONICAL_EVENT_FILE` 模式必须显示 `synthetic=true` | DONE | 指导书 L675 规定其落在 generation run / artifact manifest / 验收元数据；`run-report.json` 与清单字段（`JsonlEventSinkTest.java:84` 固定 9 字段）由生成器侧测试覆盖 |
| V24-029 | L103 | 文件模式不得直接写 ODS/DWD/ADS/指标库或自动触发流水线 | DONE_LIMITED | 生成器侧边界测试否定耦合；`MallApiGenerationEngine.java:55` 只经适配器。**缺口**：本审计**未**找到一条「文件模式运行时不产生任何平台库写入」的运行期断言（搜索范围：生成器全部 19 个测试类的静态阅读，`raw/06`） ⇒ 该禁止项只有静态结构证据 |
| V24-030 | L107 | 三个程序有独立构建产物、配置、端口、日志和启动命令 | DONE | `synthetic-data-generator` 单模块 Maven（pom 无另两程序构件）+ 自有 `application.yml`（8092 + `generator_meta`）；平台 8091、商城 8090 三服务独立运行证据见看板 L184–202（M1-8 三程序独立启停验收） |
| V24-031 | L108 | 商城工程中不存在 `generator` 包、`GeneratorController` 或生成器页面 | DONE | `mall-frontend/src/router.js` 搜 `generator` = **0 命中**，路由只有 `/`、`/login`、`/mall`、`/admin-products`（`raw/06` 第 11 项） |
| V24-032 | L109 | 生成器工程不引用商城实体/Mapper/Service/数据源配置，不能访问商城 JDBC | DONE | `GeneratorBoundarySourcePolicyTest.java:72`（生成器 Java 源码 0 违规）+ `:151`（pom 无另两程序构件） |
| V24-033 | L110 | 分析平台不引用商城/生成器 Java 包、商城表或 `mall.*` 配置键 | DONE_LIMITED | 平面对商城无编译依赖（三程序各自单模块构建，全 reactor 可独立成功）；**缺口**：本审计未找到一条**专门**断言「平台源码 0 处 `mall.*` 配置键」的测试（生成器侧有对称测试，平台侧无）⇒ 该项只有构建级证据 |
| V24-034 | L111 | 分别停止三者：其余程序不因类加载/数据库依赖崩溃；停数据源时平台明确显示「采集不可用」，历史指标仍可读 | DONE_LIMITED | 「采集不可用」显式状态已闭环（B-08/D-022，看板 L330/L340）；**缺口**：三程序独立启停的**最新**一轮完整证据在本审计未逐字复核（看板 L184–202 为该验收的登记，本审计只读到登记未读到其自带的原始日志） |
| V24-035 | L112 | 可用第二个 `MallTargetAdapter` 或不同字段夹具证明平台不是为 `mock-mall` 写死 | DONE | `SecondMallHttpAdapter` **已在 HEAD**（`git grep -n "class SecondMallHttpAdapter" HEAD` → `SecondMallHttpAdapter.java:77`，`ADAPTER_TYPE="SECOND_MALL_HTTP"`，路由 `/open/v2/**`）；双目标端到端 `SecondMallDualTargetTest.java:284-347` |

## §4.1 / §4.1.1 生成器核心接口与追认契约（L114–272）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-036 | L119–129 | `MallTargetAdapter` 九个方法（§4.1 骨架写法） | DONE | 骨架已被 §4.1.1.1 取代，实现按 L157–187；`MallTargetAdapter.java:31` |
| V24-037 | L131–136 | `EventSink` 四方法 | DONE | `contract/EventSink.java:11,14,17,20,23` |
| V24-038 | L139 | 第一版实现 `ReferenceMallHttpAdapter` 与 `JsonlEventSink` | DONE | `adapter/ReferenceMallHttpAdapter.java:46`、`contract/JsonlEventSink.java:38,103,130,143,155` |
| V24-039 | L139 | 扩展其他商城时新增 Adapter，不修改场景引擎 | DONE | 引擎零商城路由字面量（`GeneratorBoundarySourcePolicyTest.java:181-211`：引擎源码 0 命中 + `/adapter/` 正对照）；`MallApiDispatchSink.java:117-120` 刻意无回退分支 |
| V24-040 | L154–203 §4.1.1.1 | 接口签名以本节为准：`adapterType()`、`test(TargetConfig)` 抽象；`capabilities`/`operationRoutes` 与七个业务方法为 default | DONE | `MallTargetAdapter.java:34,37`（抽象）、`:48-50`（capabilities default）、`:68-70`（operationRoutes default）、`:73-103`（七方法 default 且 `throw unsupported`） |
| V24-041 | L168 | `TargetRoute(String method, String path)` 记录类型存在且不含 base_url/凭据 | DONE | `adapter/TargetRoute.java:20`（含非空校验 L22–29） |
| V24-042 | L192–203 | `EventSink extends AutoCloseable`、`closeAndBuildManifest()` 返回 `ArtifactManifest`、`default close()` | DONE | `contract/EventSink.java:11,23,26-29` |
| V24-043 | L206–214 | `TargetConfig` 恰为 5 字段：`id`/`adapterType`/`baseUrl`/`credentialRef`/`configJson` | DONE | 生成器侧 `TargetConfig` 与 `generator_target` 五列对应；`config_json` 承载 `behavior_path`/`reset_path`（`ReferenceMallHttpAdapter.java:124,127`） |
| V24-044 | L216–218 | `generator_target` 其余五列（`name`/`config_version`/`status`/`test_environment`/`capabilities`）不进入 `TargetConfig`，不参与任何适配器判定；能力一律取自 `capabilities(config)` 与 `test(config)` | DONE_LIMITED | `TargetCapabilities.none()`/缺键 ⇒ `UNDETERMINED`（`TargetCapabilities.java:32-34,37-39`）；`MallTargetAdapter.java:43` 明写「两者都不能把 UNDETERMINED 说成 SUPPORTED」。**缺口**：本审计未找到一条断言「运营登记的 `capabilities` 列不参与判定」的测试 |
| V24-045 | L222 硬约束1 | 无状态 + 目标作首参 + `MallTargetAdapterRegistry` 是唯一登记处；未注册与重复注册都响亮失败并列出已注册类型 | DONE | `MallTargetAdapterRegistry.java:28,32-34,45-48,55-57`；`GeneratorBeans.java:64-71` 为唯一装配点；`MallTargetAdapterRegistryTest.java:36,46,56` |
| V24-046 | L223 硬约束2【新增要求】 | 跨目标状态（目录缓存、外部 ID 映射）不得放进适配器实例字段 | DONE_LIMITED | 适配器实例不持有目标（首参传递，`raw/06` 第 1 项）；**缺口**：本审计**未**找到专门断言「适配器实例无跨目标可变字段」的测试 ⇒ 只有源码阅读结论 |
| V24-047 | L224 硬约束3 | 做不到就响亮失败：抛 `MallOperationException`（携带操作名），不得返回 `null`/空对象冒充成功；引擎按「整类降级+记缺口」处理 | DONE | 七方法 default 体 `throw unsupported(...)`（`MallTargetAdapter.java:73-103` 与私有 helper `:114-117`）；`MallOperationException` 在 §4.1.1.3 表中（L256） |
| V24-048 | L225 硬约束4 | 凭据纪律：`credentialRef` 只是引用名；取不到凭据抛异常；日志/流水/异常只出现引用名，绝不出现值 | DONE_LIMITED | `TargetController.java:113-120` 有明文密钥哨兵守卫；`TargetConfig` javadoc 写明值绝不进配置/日志/流水/异常。**缺口**：未见对**已注册凭据解析失败路径**的运行期断言（搜索范围：生成器 19 个测试类静态阅读） |
| V24-049 | L226 硬约束5 | 能力三态：只有 `SUPPORTED`/`ABSENT`/`UNDETERMINED`；缺键/未知/探测不到一律 `UNDETERMINED`；`capabilities` 是声明、`test` 是实测；运行预检以**声明**为准并强制 `product`/`user`/`order` 三项 | DONE | `CapabilityVerdict` 三值；`SecondMallHttpAdapter.java:243-265`（`ADMIN/REFUND/RESET_STATE` 静态声明 `ABSENT`，并写明「不要把这三个 ABSENT 读成测过所以没有」`:462-467,630-631`）；`MallApiGenerationSmokeTest.java:501`（白名单跟随 capabilities 而非硬编码） |
| V24-050 | L227 硬约束6【新增要求】 | 路由所有权：流水与报告的 `http_method`/`route` 必须取自 `operationRoutes(config)`；引擎/报告/页面不得内置商城路由字面量；未声明路由的操作只写占位说明 | DONE | `MallApiGenerationEngine.java:213-216,244-247,261-264,303-315`（缺失条目 → `ROUTE_UNDECLARED` 占位，无回退）；`MallApiDispatchSink.java:105-124`；`OperationJournalEntry.java:27-28,80-81`；`GenerationRunService.java:88,376-383`（journal 落为 `operation-journal.jsonl` 产物）；负向守卫 `GeneratorBoundarySourcePolicyTest.java:181-211`；`SecondMallDualTargetTest.java:284-347` |
| V24-051 | L228 硬约束7【新增要求】 | 新增一家商城 = 新增一个 Adapter + 登记处加一行；不得改场景引擎/事件契约/表结构/页面；引擎不得出现「哪家商城」的分支 | DONE | **实证一次**：第二家适配器已在 HEAD，`GeneratorBeans.java:67-70` 只加一行装配；`MallApiGenerationEngine.java:319-326,344-358`（`describeCatalogGaps` 无 adapterType 分支）；`SecondMallHttpAdapter.java:195-198,701-713,854-866`（状态词映射表写在适配器内） |
| V24-052 | L229 硬约束8 | 只经公开接口：`MALL_API` 只允许商城公开 REST；不得直连商城数据库、依赖商城 Java 包/业务表名/`mall.*` 配置键 | DONE | `GeneratorBoundarySourcePolicyTest.java:72`（生成器 Java 0 处商城耦合）+ `:151`（pom 无商城构件） |
| V24-053 | L230 硬约束9 | 运行期单一配置实例：一次运行只解析一次 `TargetConfig`，贯穿探测/预检/派发/流水；同一运行内不得出现第二份 | DONE | `MallApiGenerationEngine.java:142-143`（`Preflight` 携带单份 route map 并下发） |
| V24-054 | L231 硬约束10【新增要求】 | 金额标度换算由适配器负责，系数须为显式常量并有单测；金额一律 `BigDecimal`，禁止 `double`；对账/校验一律用整数分比较 | DONE_LIMITED | `ExternalProduct.price` 为 `BigDecimal`（L246）；第二家适配器按「整数分」读取 `unit_price_cents`（`SecondMallHttpAdapter.java:867-875` 区）。**缺口**：本审计未找到「换算系数常量 + 单测」这一对一的证据（搜索范围：生成器 19 测试类静态阅读）⇒ 只有实现侧证据，缺单测指针 |
| V24-055 | L232 硬约束11【新增要求】 | 契约纪律：`TargetRoute`、`operationRoutes` 及 §4.1.1.3 之外的新增字段/方法必须先改本节再实现；`generator-api.v1.yaml` 现标 `x-unspecified`，待 M1-5 收口时按本节同步（含 `TargetCheckResult` 空对象声明与 `adapter_type` 取值集合） | DONE_LIMITED | 已同步部分：`generator-api.v1.yaml:449-478` `TargetCheckResult` 有真实属性（targetId/reachable/detail/capabilities 枚举）、`:480-499` `TargetCapabilities`；`adapter_type` 取值集合**按契约刻意不冻结**（`:412-413,421-423`，与 L158「取值集合不冻结」一致）。**缺口**：`TargetRoute` 在 `contract-specs/**` 全仓 **0 命中** ⇒ 硬约束 6/11 的载体未进契约；YAML 仍为 `x-contract-status: DRAFT`（`:28`），残余 `x-unspecified` 21 处 |
| V24-056 | L240 | `TargetCheckResult`：`targetId`(long)/`reachable`(boolean)/`detail`(String)/`capabilities`(Map)；`detail` 不含凭据值 | DONE | `generator-api.v1.yaml:449-478`（属性齐、枚举齐） |
| V24-057 | L241 | `TargetCapabilities`：`verdicts`(Map)/`declared`(boolean)；缺键 ⇒ `UNDETERMINED`；`none()` = 空表 + `declared=false` | DONE | `TargetCapabilities.java:32-34,37-39`；`generator-api.v1.yaml:480-499` |
| V24-058 | L242 | `MallCapability` 七项：`product`/`user`/`order`/`refund`/`behavior`/`reset_state`/`admin`；属生成器侧实现词汇，非跨程序契约 | DONE | `MallCapability.java`（`reset_state` 定义）；`ReferenceMallHttpAdapter.java:107,113-127` 与 `SecondMallHttpAdapter.java:243-265` 逐项给出判定 |
| V24-059 | L243 | `CapabilityVerdict` 三值 | DONE | 同 V24-049 |
| V24-060 | L244 | `ProductQuery`：`categoryId`(Long)/`keyword`(String)/`offset`(int)/`limit`(int)；`offset ≥ 0`，`limit ∈ [1,500]` | DONE_LIMITED | 类型存在并被适配器消费；**缺口**：本审计未逐字核到「`limit ∈ [1,500]` 上界校验」的断言（搜索范围：生成器 19 测试类静态阅读） |
| V24-061 | L245 | `ProductPage` 五字段，其中三个缺口通道（`unmappedStateWords`/`stateFieldMissing`/`priceUnreadable`）**必须并列上报**，不得只留在 debug 日志 | DONE_LIMITED | 五元组已在 HEAD：`ProductPage.java:37-39,48,77`（`hasGap()` 三通道并列）；`SecondMallHttpAdapter.java:294-314,867-875`；`MallApiGenerationEngine.java:259-260,383-390`（第三通道文案）+ `:270-277`（独立 journal 行）。**缺口**：出口只在产物与运行报告——`web/dto/GeneratorApiDtos.java` 搜 `priceUnreadable\|notes\|catalog\|gap` = **0 命中** ⇒ 纯 API 消费者看不到（指导书未规定必须有 API 出口，故记 `DONE_LIMITED` 而非 `TODO`） |
| V24-062 | L246 | `ExternalProduct`：`productId`/`name`/`categoryId`(Long)/`price`(BigDecimal)/`status`(String)；`price ≥ 0`；`onSale()` 只认规范词 `on_sale` | DONE | `SecondMallHttpAdapter.java:195-198,701-713`（`SALE→on_sale` 等映射，未映射返回 `null`）；`:854-866`（映射后才写规范字段） |
| V24-063 | L247 | `UserCommand`：`ageGroup`/`cityLevel`/`memberLevel`(String)，取值受契约枚举白名单约束 | DONE_LIMITED | 生成器侧自身枚举受契约约束（`FileModeGenerationEngineTest.java:389`「dirty 词汇表每个类型都被契约规则拒绝」）。**缺口**：本审计**未**找到针对 `UserCommand` 三字段枚举白名单的独立断言 |
| V24-064 | L248–250 | `ExternalUser`/`BehaviorCommand`/`OrderCommand` 字段与非空约束（`Item.quantity ≥ 1`） | DONE_LIMITED | 类型存在；**缺口**：`quantity ≥ 1` 等构造校验的断言未逐字核到（同上搜索范围） |
| V24-065 | L251 | `ExternalOrder.status`：商城原样文本；**商城没给（字段缺失或去空白为空）⇒ `null`**，不得填 `UNKNOWN`；未返回记 `null`/`-1` | DONE | `ExternalOrder.java:38-40`（空白归一为 `null`），`:16` javadoc 点名「曾经的 UNKNOWN 就是这么一个词」；全 `src/main` 搜 `UNKNOWN` = **0 代码字面量**（`raw/06` 第 5 项）；`SecondMallAdapterOperationsTest.java:688,725,740,747` |
| V24-066 | L252–254 | `PayCommand`/`CancelCommand`/`RefundCommand` 字段与约束（`reason` 必填去空白非空、`amount > 0`） | DONE_LIMITED | 类型存在；**缺口**：`reason` 去空白非空、`amount > 0` 的构造断言未逐字核到 |
| V24-067 | L255 | `ExternalRefund.status`：同上（空白 ⇒ `null`，不得填 `UNKNOWN`） | DONE | `ExternalRefund.java:30-32`，`:13` javadoc 同源 |
| V24-068 | L256 | `MallOperationException` 只承载操作名与脱敏说明 | DONE | `MallTargetAdapter.java:114-117` 的 `unsupported()` 构造 |
| V24-069 | L258 | `EventSink` 追认两点：返回类型名 `ArtifactManifest`；`extends AutoCloseable` + `default close()` | DONE | `contract/EventSink.java:11,23,26-29` |
| V24-070 | L260–271 | `ExternalProduct.status` 修正为**规范词表**（`on_sale`/`off_sale`/`pending`）；适配器必须把商城状态词映射为规范词；`ExternalOrder/Refund.status` 仍是商城原样文本 | DONE | `SecondMallHttpAdapter.java:195-198,701-713,854-866`；`MallApiGenerationEngine.java:319-326`「引擎对任何一家商城的词表零知识」；契约 enum 见 `contract-specs/schemas/canonical-event.v1.schema.json` L413-419/L465-471（指导书引用） |

## §4.2–§4.5 生成器数据结构、场景、API、页面（L274–308）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-071 | L278 | 表 `generator_target`；凭据只存引用；每次运行冻结版本 | DONE | `db/generator/V1__generator_meta.sql:12-27`（含 `credential_ref`、`config_version`） |
| V24-072 | L279 | 表 `generation_plan`；运行只引用不可变版本 | DONE | 同文件 `:30-47`（含 `plan_id`/`version`/`mode`/`seed`/`dirty_profile`/`output_uri`） |
| V24-073 | L280 | 表 `generation_run`；异步运行，可查询和取消 | DONE_LIMITED | 同文件 `:50-71`（含 `status`/`cancel_requested`/`checksum`/`error_code`）；`GenerationRunController.java:47` `POST /{id}/cancel`。**缺口**：取消的**幂等性**运行期断言未核到 |
| V24-074 | L281 | 表 `generation_artifact`（用于可复现与对账） | DONE | 同文件 `:74-89` |
| V24-075 | L282 | 表 `generation_event_stat` | DONE | 同文件 `:92-100` |
| V24-076 | L284 | 状态机 `PENDING -> RUNNING -> SUCCESS/FAILED/CANCELLED`；相同 `plan_version + seed + time_window` 可复现；运行中改计划不影响已启动任务 | DONE | `meta/RunStatusTest.java`（4 例）；`JsonlEventSinkTest.java:123`（同 plan_version+seed+时间窗 ⇒ 产物一致）；`GeneratorDeterminismTest.java:33,47` |
| V24-077 | L288 | 商品抽样：类别按目标权重，类别内 Zipf/长尾 | DONE | `DistributionKit` + `DistributionKitTest.java`（4 例） |
| V24-078 | L289 | 用户分层：新客/活跃/沉睡/高价值/价格敏感，可配置比例 | DONE | 同上（分布工具类） |
| V24-079 | L290 | 时间分布：工作日/周末、小时段、活动峰值分段权重，不得均匀随机 | DONE | `FileModeGenerationEngine.java:217-222`（六个分布工具按子种子构造） |
| V24-080 | L291 | 行为链：浏览→收藏/加购→下单→支付/退款用条件概率；订单必须引用已存在用户与商品 | DONE | `core/BehaviorChainTest.java`（4 例）；`ScenarioEffectTest.java`（2 例） |
| V24-081 | L292 | 金额用 `BigDecimal`；遵守商城公式 | DONE_LIMITED | 类型层为 `BigDecimal`；**缺口**：商城公式（价×量−优惠=实付）在生成器侧的断言未核到；平台侧订单行金额校验见 V24-100 |
| V24-082 | L293 | 库存：MALL_API 只通过商城接口变化；失败必须记入运行报告，不能绕过业务规则 | DONE_LIMITED | B-04 已裁（看板 L337）：「生成器以 capability 声明 `RESET_STATE`，仅支持的测试商城可经受保护的管理员 API 重置；不支持时 MALL_API 只保证场景分布可追溯」。`ReferenceMallHttpAdapter.java:127` 与 `SecondMallHttpAdapter.java:250-252` 分别按需声明。**缺口**：本审计未见「库存变化失败写入运行报告」的断言 |
| V24-083 | L294 | 可复现：所有随机源由一个根 seed 派生；并行线程也用确定性子 seed | DONE | `FileModeGenerationEngine.java:42-43,738-740`（`subSeed(seed, 流名)`，跨 JVM 稳定、不依赖 `String.hashCode`） |
| V24-084 | L295 | 异常样本：仅在文件模式注入（缺字段/未知枚举/重复 eventId/非法金额/迟到/非法状态跳转），并**在 manifest 中记录期望隔离数** | DONE_LIMITED | 文件模式注入 ✅（`:53,547-550` + `FileModeGenerationEngineTest.java:351,389`）；MALL_API 响亮拒绝 ✅。**缺口**：期望隔离数**不在 manifest**，而在 `run-report.json`（`GenerationRunService.java:535`）——`contract-specs/schemas/generation-artifact-manifest.v1.schema.json:5` **自述**该冲突未决（「§4.3 L149 要求 manifest 记录，但 §4.2 字段里没有」），`JsonlEventSinkTest.java:84` 固定清单 9 字段 ⇒ 指导书 §4.3 与 §4.2 自身冲突，登记为**发现 R-04** |
| V24-085 | L299 | `POST /api/v1/generation-runs` 异步启动并返回 runId | DONE | `web/GenerationRunController.java:25,35`；`GeneratorApiSmokeTest.java`（5 例） |
| V24-086 | L300 | `GET /api/v1/generation-runs/{id}` 状态/进度/计数/失败摘要 | DONE | 同文件 `:41` |
| V24-087 | L301 | `POST /api/v1/generation-runs/{id}/cancel` 幂等取消 | DONE_LIMITED | 同文件 `:47`；**缺口**：幂等性运行期断言未核到 |
| V24-088 | L302 | `GET /api/v1/generation-runs/{id}/artifacts` 文件/checksum/清单 | DONE | 同文件 `:53` |
| V24-089 | L303 | `GET /api/v1/scenarios` 场景定义与输入约束 | DONE | `web/ScenarioController.java:23,26` |
| V24-090 | L304 | `POST/PUT/GET /api/v1/targets` 与 `POST /targets/{id}/test` | DONE | `web/TargetController.java:32,43,48,55,80`（`PUT` 落在集合路径、id 在 body，控制器内自述为有意选择） |
| V24-091 | L308 | §4.5 生成器拥有自己的简洁**页面或 CLI**，不再出现在 `mall-frontend`：目标商城、场景计划、运行列表、实时进度、失败明细、产物下载 | DONE_LIMITED | **CLI 分支已交付**：`cli/GeneratorCli.java:42-43`（`ApplicationRunner`）、`:35-38`（`plan-append` / `run-start`，含轮询进度、终态与计数、失败摘要、产物清单 JSON）；`mall-frontend` 已清空该路由 ✅。**缺口（页面分支）**：`synthetic-data-generator/src` 下 `.vue/.html/.js/.css` = **0 文件**，`src/main/resources` 仅 `application.yml` + `V1__generator_meta.sql`；8092 `/` = 404。判据写的是「页面**或** CLI」⇒ 该项**满足**，但看板 F-23 的旧判据「S5 未开工」已裁定失真、**不再作为缺口计**（裁决编号见 README §裁决引用） |
| V24-092 | L308 | 商城页面只面向购物者和商城管理者；分析平台页面只面向分析与运营人员 | DONE_LIMITED | 8090 有显式职责原文；**缺口**：8091 只隐含（E5 预验收实测 11 个职责关键词命中 **0**，`e5-preaccept-20260912/README.md` 第 1 项）⇒ 登记为 E5 缺陷，非 §4.5 本体缺口 |

## §5 可更换商城的数据源与连接器（L310–354）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-093 | L314 | 平台创建 `source_instance_id`，从 Landing manifest 传播到 ODS/DWD/DWS/ADS/指标快照与页面筛选 | DONE_LIMITED | 实际落地实体名为 `source_registry`/`source_id`：`db/meta/V16__source_registry.sql`、`V17__source_dimension_for_checkpoint_and_batch.sql`、`V18__source_warehouse_prefix.sql`；P2-07 源命名空间前缀（`p2-07-source-prefix-20260912`）。**缺口**：全仓 SQL 搜 `source_instance` = **0 命中** ⇒ §5.2 的实体名 `source_instance` 与落地名不一致（V2.4 §12.3-3 自身裁决过改名，故记 `DONE_LIMITED` 而非 `TODO`） |
| V24-094 | L314 | 跨商城去重键为 `(source_instance_id, event_id)`，不能只按 `event_id` | DONE_LIMITED | 唯一性规则存在（`EVENT_ID_UNIQUE`，`f88-dq-severity-20260912/IMPL-REPORT.md:79`）。**缺口**：本审计未找到「跨源同 `event_id` 不互相去重」的运行期断言（P5 §7.3 明示库级不串源**未测**：需激活源 B，D-143 未批准） |
| V24-095 | L316 | 一次流水线只处理一个 source instance；任何表/常量/配置键/页面不得写死 `mock-mall` | DONE_LIMITED | 源维度已进 checkpoint 与 batch（`V17`）；`p1-baseline-r39-20260911` 与 P1-05 换源验收。**缺口**：本审计未逐字复核「全仓 0 处写死 `mock-mall`」的当前命中数（看板记录过一次该口径错误：先称 0 命中、实为 13 命中，已在 20:15 就地更正并保留原文） |
| V24-096 | L322 | 表 `source_instance`（8 字段） | TODO | `*.sql` 搜 `source_instance` = 0 命中；实际实体为 `source_registry`（V16）⇒ 名字与字段均未按本表落地（由 §12.3-3 裁决改名，见 V24-093） |
| V24-097 | L323 | 表 `source_connector`（9 字段，表示采集方式与版本） | TODO | `*.sql` 搜 `source_connector` = **0 命中** |
| V24-098 | L324 | 表 `schema_mapping`（11 字段，把源字段映射为平台契约） | TODO | `*.sql` 搜 `schema_mapping` = **0 命中**；P5 结论「画像 6 个语义键在生产代码的读取面上为零，配置驱动接入只有校验没有执行」（F-89） |
| V24-099 | L325 | 表 `source_manifest`（批次溯源与对账） | TODO | `*.sql`+`*.java` 搜 `source_manifest` = **0 命中**（批次侧实际是 `ingestion_batch`/`ingestion_batch_file`） |
| V24-100 | L326 | 表 `ingestion_batch_file`（文件级证据：offset/checksum/accepted/quarantined/parse_errors） | DONE | `V1__platform_ingestion.sql` + `ingestion/entity/IngestionBatchFile.java` |
| V24-101 | L327 | 表 `file_checkpoint`，断点必须绑定连接器 | DONE_LIMITED | `file_checkpoint` 存在；B-11/D-0xx 已闭环路径写法翻倍问题（看板 L331）。**缺口**：「断点绑定连接器」而非仅绑定文件，在本审计未逐字核到（生成器/平台侧均未见该断言的证据指针） |
| V24-102 | L331–339 | `SourceConnector` 接口六方法（`type`/`capabilities`/`test`/`discover`/`fetch`/`commitAfterSuccess`） | TODO | `git grep -l "interface SourceConnector" HEAD` → 仅指导书/看板/审计文档命中，**代码 0 命中** |
| V24-103 | L342 | 首期实现 `LocalFileConnector` | TODO | 搜 `LocalFileConnector` = 代码 0 命中（实际采集入口是 `LocalFileIngestor`，命名与抽象层不同） |
| V24-104 | L342 | 首期实现 `FlumeLandingConnector`；Flume 已投递到 Landing 时连接器不重复搬运，只管理配置/心跳/目标 URI/批次发现/manifest | TODO | 搜 `FlumeLandingConnector` = 代码 0 命中；Flume 在本项目**从未作为交付链路运行**（`m3-cluster-probe` 只做 HDFS/YARN/Metastore 探测，未跑 Flume） |
| V24-105 | L342 | DataX/Kafka 只登记类型、配置 Schema 与 `NOT_IMPLEMENTED/DEFERRED` 能力，**不能显示为可用** | 未取证 | 本审计未找到 DataX/Kafka 类型登记处（搜索范围：`*.sql`/`*.java` 全仓搜 DataX/Kafka 未做定向检索）⇒ 既不能证明已登记也不能证明被误显示为可用 |
| V24-106 | L346 | `EventNormalizer` 负责源事件→canonical event（事件类型映射、JSONPath 提取、枚举映射、时区转换、金额单位转换、允许的纯函数变换）；**禁止任意脚本或 SQL** | TODO | 搜 `EventNormalizer` = **代码 0 命中**；`p5-heterogeneous-source-20260912/README.md:27` 明示「不做：不实现 P3-02 / `EventNormalizer` / mapping 执行器」 |
| V24-107 | L348 | 未知源版本 / 无 ACTIVE mapping / 必填字段缺失 / 不允许的转换 **全部进入 quarantine**，并保存 source instance、mapping version、reason code 和原始位置 | TODO | quarantine 机制存在（批次 43：accepted 51 / quarantine 4）；**但** P5 实测「B1（词汇全异构、信息完整）60/60 全被隔离，归一从未发生；其中 30 行的隔离记录**连 `event_id` 都是 NULL**（源里叫 `msg_id`）」⇒ 隔离面无法给出追溯标识；`mapping_version` 字段随 `schema_mapping` 缺失而无处安放 |
| V24-108 | L348 | 映射激活必须先用样本 dry-run，并输出接受/隔离/错误计数 | TODO | 无 mapping 执行器 ⇒ 无 dry-run 可跑；P5 只做了「登记配置接入」的对照，未激活源 B（D-143 未批准） |
| V24-109 | L350 | 映射最终真相是版本化配置与审核记录，不是大模型对话；AI 草案不能直接变为 ACTIVE mapping | DONE_LIMITED | 无 AI 建模实现（V24-150 起为 `TODO`），故「不得直接变 ACTIVE」在结构上成立；**缺口**：无审核记录表（`mapping_review_item` 不存在，见 V24-153） |
| V24-110 | L354 | §5.5 数据源页面：商城名称、连接器类型、状态、最后心跳、最近批次、映射版本、测试连接、启停、查看错误；不暴露 `runtimeProfileId` | TODO | 平台前端 `web/src/views` = {Overview, Behavior, Products, Sales, Rfm, Pipeline, Ops, Decisions, AiAssistant, Login}，**无数据源管理页**（`raw/07` C 节）；V2.4 §12.3-5 要求「P4 出口必须有最小管理员页面」⇒ 未满足 |

## §6 集群、HDFS、Hive 与 Spark（L356–440）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-111 | L360 | node01–03 是可选运行环境，不是代码常量 | DONE | `RuntimeProfile` 正交维度已落地（见下三行）；集群地址只出现在配置（`conf/*.xml`）而非代码常量 |
| V24-112 | L364–371 | `RuntimeProfile` 至少显式区分 `platformPlacement`/`landingStoreType`/`sparkSubmitterType`/`clusterManager`/`hiveMetastoreUris`/`hiveJdbcUrl`/`sshCredentialRef`+`hiveCredentialRef`+`hadoopConfRef`/`remoteLogRoot`+`jobJarUri`+jar SHA-256 | DONE_LIMITED | 迁移 `V7__platform_runtime_profile.sql` 存在，`V18__source_warehouse_prefix.sql` 追加源前缀。**缺口**：本审计未逐字核对上述九类维度**全部**在表结构/实体中存在（只核到迁移文件存在） |
| V24-113 | L369 | `hiveJdbcUrl` 只供 HiveServer2 连通与查询，**不得**填入 `hive.metastore.uris` | DONE | 实测 HiveServer2 10000 **closed**，项目走 Metastore 直连（`m3-cluster-probe-20260912/README.md:19`）；`conf/hive-site.xml` 只写 `hive.metastore.uris=thrift://node01:9083`（`PROBE-RESULTS.md:41`） |
| V24-114 | L373 | 旧 `profileType` 只能作展示标签；每个档案激活前运行真实环境检查 | DONE_LIMITED | `P3-01-a` 实测 `POST /api/v1/sources/1/test` HTTP 200 / `ok=true` / **7 项 passed+applicable**，阴性对照 `/sources/999999/test` = 404 `SOURCE_NOT_FOUND`，且 `GET /sources/1` 的 `updatedAt` 与开工前逐字一致（未调 `/activate`）——`p3-01a-mock-mall-profile-20260912`（看板 L513 第 ① 项）。**缺口**：`profileType` 是否仍参与提交/存储判定，本审计未核 |
| V24-115 | L379–387 | 集群数据流：Flume Taildir/Spooling + FileChannel → HDFS Sink；或 SFTP/HTTP 网关/共享目录 → node01 spool → Flume → HDFS；落地路径 `HDFS /graduation/landing/{source_instance_id}/dt=yyyyMMdd/hour=HH/` | TODO | 实测建立的 HDFS 目录是 `/graduation/{warehouse,jars,staging,probe,eventlog}`（`PROBE-RESULTS.md:14`），**未建立 `landing/{source_instance_id}/dt=/hour=` 布局**；Flume 未运行 |
| V24-116 | L383 | 平台发现 manifest → SSH 提交 `spark-submit` 到 YARN/standalone | DONE_LIMITED | `spark-submit --master yarn --deploy-mode cluster` 实测 SUCCEEDED（`application_1789195359269_0004`，15:41:21→15:41:39，`PROBE-RESULTS.md:17`）；但**未走 SSH**（用户答「暂时不需要」，`PROBE-RESULTS.md:3`）⇒ 提交路径成立、SSH 通道未建 |
| V24-117 | L387 | Flume 不能跨机器 tail；商城不在集群节点上时必须明确传输协议；**开发 Agent 不得凭空假设 Windows 路径可被 Linux Flume 读取** | N/A（约束） | 未发现违反；同时 Flume 链路整体未实现（V24-104/115） |
| V24-118 | L391–398 | §6.3 关键字段清单（`profileType`/`landingType`+`landingUri`/`hdfsDefaultFs`+`warehouseUri`+`hiveJdbcUrl`或`metastoreUri`/`sparkMaster`+`deployMode`+`queue`+`sparkSubmitPath`+`jobJarUri`/`submitHost`+`sshPort`+`sshUser`+`sshCredentialRef`/`kerberosEnabled`+principal与keytab引用/`metricStoreType`+读写数据源引用/`configVersion`+`status`+`lastCheckAt`+`lastCheckReport`） | DONE_LIMITED | 迁移与实测取值证明多数字段已用（`defaultFS=hdfs://node01:8020`、`master=yarn`、队列 `default`、`spark.version=3.5.1`、jar 在 HDFS `/graduation/jars`）；**缺口**：逐字段存在性未逐条核 |
| V24-119 | L400 | 敏感值不写数据库明文、YAML、命令日志或页面；只保存环境变量、密钥文件或密钥服务引用 | DONE_LIMITED | 生成器侧有明文密钥哨兵守卫（`TargetController.java:113-120`）、`credentialRef` 纪律（V24-048）；**缺口**：平台侧 `credential_ref` 统一性与日志脱敏的运行期断言未核到（对应 V24-166 的审计项） |
| V24-120 | L404–411 §6.4 | 八步预检：①主机名/IP/端口/DNS/时间同步/版本 ②HDFS 临时文件增删读写 ③Hive 临时库表增删查 ④最小 Spark count 作业取 externalJobId/终态/日志 ⑤Flume 投递小文件到 HDFS 校验 checksum ⑥55 条黄金数据跑集群小链 ⑦固定 seed 1,000 条跑集群链 ⑧对同一输入比较本地与集群核心指标同值 | DONE_LIMITED | ①③④ 有实测：①端口表 13 项（`port-probe.txt`）、YARN `state=STARTED`/`haState=ACTIVE`、版本 Hadoop 3.3.4/Spark 3.5.1/Java 1.8.0_351；④`application_..._0004` SUCCEEDED 有 externalJobId 与 `yarn logs` 2398 行；③`SHOW DATABASES` 7 库（只读，**未创建临时库表**）。**②未做**：HDFS 只做了 `-put`/`-cat`/`ls`，未做删除闭环。**⑤未做**：Flume 未运行。**⑥⑦⑧未做**：`m3-step8-parity-20260912/IMPL-REPORT.md:430`「§6 集群链路：未执行（显式声明）」、`:464`「§5.5 逐值比对表：**未执行，无值**（显式声明，逐行留空）」、`:488`「本表**不得**出现任何集群侧数值，也**不得**出现『本地值 ⇒ 集群应同值』的推断」；E4 的 1,000 行 10 作业已真实成功（`e4-cluster-1000-20260912`，看板 L514 第 ⑨ 项）但**同输入本地↔集群对照未完成** |
| V24-121 | L413 | 任何一步失败必须返回「检查项、目标、结果、错误摘要和修复建议」，不能只返回激活失败 | DONE_LIMITED | 预检失败路径有实现（`m3-preflight-20260912`、`P3-01-a` 的 7 项 passed+applicable 结构与阴性对照）。**缺口**：本审计未核对「修复建议」字段是否在 DTO 中真实存在 |
| V24-122 | L419 §6.4.1-1 | 生产采集不得把 `landingUri` 转成 `java.nio.Path`/`Files.*`；必须实际经过 `LandingStorage`/连接器抽象 | TODO | `SourceConnector`/`LocalFileConnector` 均代码 0 命中（V24-102/103）⇒ 抽象层不存在，该项不可能满足；实测采集仍走本地 `landing/events` 目录（`e5-preaccept` 第 2 项） |
| V24-123 | L420 §6.4.1-2 | `HdfsLandingStorage` 健康检查须在授权 `<base>/_health/<uuid>` 中完成 create/write/read/rename/delete，且只清理自建探针 | TODO | 全仓搜 `HdfsLandingStorage` 相关健康检查无证据；HDFS 侧实测只建 `/graduation/{warehouse,jars,staging,probe,eventlog}`，无 `_health` 探测记录（`PROBE-RESULTS.md` §5 边界声明） |
| V24-124 | L421 §6.4.1-3 | Flume 配置改用「按 source + 采集时间写 raw」并启用本地采集时间；业务 `event_type/event_time` 由 ODS 解析后分区 | TODO | Flume 未运行、配置未改（`flume` 相关改动在本审计搜索范围内 0 证据） |
| V24-125 | L422 §6.4.1-4 | SSH 提交须 1–2 秒返回 launcher reference，随后提取真实 YARN `application_...`；伪 `ssh-*` ID 不能冒充 externalJobId | TODO | 未走 SSH（用户答「暂时不需要」）；本审计未见 launcher reference 协议实现 |
| V24-126 | L423 §6.4.1-5 | 远端命令只允许固定 wrapper + 白名单参数；禁止直接拼接 UI 输入；SSH 必须校验 known_hosts，禁止 `StrictHostKeyChecking=no` | TODO | 同上，SSH 通道未建；本审计未找到 wrapper 白名单实现 |
| V24-127 | L424 §6.4.1-6 | 第一版远程执行优先 `master=yarn, deployMode=client`；确认 YARN 日志聚合后再支持 cluster deploy | TODO（被实测覆盖） | 实测走的是 `--deploy-mode cluster` 且成功（`PROBE-RESULTS.md:17`）；**这正是「确认日志聚合后再支持」的后半句**——`yarn logs -applicationId` 实测可读 2398 行（`:24`）⇒ 前置已满足，但指导书写的「第一版优先 client」与实测选择不同，登记为**发现 R-05** |
| V24-128 | L425 §6.4.1-7 | 版本化 jar 放 `/apps/graduation/spark-jobs/<gitCommit>/...jar` 并保存 SHA-256，不能只引用可变 `latest` | TODO_LIMITED | 实际 jar 放 `/graduation/jars/spark-3.5.1/`（252 个 Spark 发行版 jar）与平台侧构建产物，**未按 `<gitCommit>` 版本化**、**未走 `/apps/` 路径**；`m3-step8-parity-20260912/IMPL-REPORT.md:26` 自述「新 jar 构建（m2）296,340 B / sha256 F9882E6A… ⚠️**该构建物已不存在**」⇒ 指纹可追溯性实际受损 |
| V24-129 | L426 §6.4.1-8 | Flume at-least-once；平台只能通过 checkpoint、manifest 与 `(source_instance_id,event_id)` 去重提供幂等，**不得宣称 exactly-once** | DONE_LIMITED | checkpoint 与幂等已有实现（M1-12/D-0xx、B-11 闭环）；**缺口**：`manifest` 一环（`source_manifest` 表 0 命中，V24-099）缺失；`(source_instance_id,event_id)` 的跨源去重未取证（V24-094） |
| V24-130 | L430 §6.5 | 9 条 `USER-INPUT` 不得由 Agent 猜测 | BLOCKED | 看板 L324 `B-02` = `USER-INPUT` / `BLOCKED`（覆盖 U1–U9 全部 9 项），L325 `B-03` = `BLOCKED`（真实 LLM 凭据）。逐条状态见本文件末「§6.5 USER-INPUT 逐条提取」 |

## §7 数仓、算法与剩余实现规格（L442–488）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-131 | L446 | `dim_date`：可配置日期区间生成日期/周/月/季度/工作日/节假日标志；生成任务幂等 | 未取证 | 本审计未定向检索 `dim_date` 的生成作业与幂等断言（该组条目由另一只读子代理核查，报告未在本审计完成前返回）⇒ 不写 DONE 也不写 TODO |
| V24-132 | L447 | `dim_region`：版本化地区字典，至少覆盖来源商城实际地区；未知值落 `-1/unknown` 并计数 | 未取证 | 同上；仅知 `IdCodecSpec.scala` 有「不得解析成 -1（与维度 unknown key 哨兵冲突）」的断言（看板 F-23c 引用 `IdCodecSpec.scala:28-47`）⇒ 哨兵约定存在，表与计数未取证 |
| V24-133 | L448 | `dim_metric`：由 MySQL `metric_definition` 同步，记录 metric_code/名称/单位/聚合方式/口径版本 | DONE_LIMITED | `V13__metric_definition_r7.sql` 存在；指标 10 行可读、含 `unit`/`period`/`definition_version` 五元组全等（`t2rerun-golden55-post-ct-20260912/README.md:22`）。**缺口**：同步**作业**本身未核 |
| V24-134 | L449 | 订单状态机：Java 与 Scala 共享同一份中立契约；非法转换写 `dwd_reject_record`，不能静默接受 | DONE_LIMITED | 状态机存在（`DecisionStateMachine` 是决策侧，订单侧见 `TradeDwdJob`/DWD 作业）；run 47 集群/本地 DWD 落数 18→139、rej 0（`m3-step8-parity` §5A.4）。**缺口**：「Java 与 Scala 共享同一份中立契约」的**单一定义源**未取证；`dwd_reject_record` 的非空写入未核 |
| V24-135 | L450 | 订单行校验：`SUM(quantity*unit_price - line_discount) = order_amount`，绝对误差 < 0.01 | DONE | `AMOUNT_RECONCILE` 为 `BLOCKING` 规则（`f88-dq-severity/IMPL-REPORT.md:76`，注明「金额对账（§5.4.2 误差 <0.01）」）；run 47 质量明细含该规则（`m3-step8-parity` §5A.5） |
| V24-136 | L451 | 退款：按 `refund_id + event_time + ingest_time + event_id` 取最新；累计退款不得超过实付 | TODO | 搜 `REFUND_AMOUNT`/退款上限规则 = **0 命中**（`raw/07` A 节）；§7.3 规则 5「退款额 ≤ 实付额」同样 0 命中（见 V24-142） |
| V24-137 | L452 | 去重排序：同一业务键按 event_time、ingest_time、event_id 确定唯一结果，保证重跑稳定 | DONE | `EVENT_ID_UNIQUE` 规则存在（`f88-dq-severity` 表第 4 行，注明下游确定性去重 `ROW_NUMBER()`+`rn=1`）；同输入对照 run 43 vs run 41 指标 10/10 五元组全等（`t2rerun` README:21-23） |
| V24-138 | L456 | 漏斗增加渠道和分类粒度；若后阶段人数大于前阶段，**保留真实数值**并输出「非严格同 cohort 漏斗」说明，**禁止用 `min` 截断伪造单调性** | DONE | `ADS_DWS_FUNNEL_RECONCILE` 为 `BLOCKING`（`f88-dq-severity:83`「漏斗层间对账」）；run 47 DWS=3 / ADS=30 与 10 指标可读 |
| V24-139 | L457 | 新增 `ads_category_sale` 与 `ads_region_sale`；地区指标至少 sale_amount/net_sale_amount/order_count/buyer_count | DONE_LIMITED | ADS 镜像 8 表按快照逐表计数 `1,4,4,9,1,9,1,1` = 30 且 8 个 jsonl 与库侧按表名对齐全等（`t2rerun` README:22-23）。**缺口**：8 表中是否含这两张、四个地区指标是否齐，本审计未逐表核对 |
| V24-140 | L458 | `ads_sale_trend` 增加 net_sale_amount；repeat_rate 必须**实际落表并发布** | DONE_LIMITED | 同上（ADS 8 表落表与发布有证据）。**缺口**：`net_sale_amount` 与 `repeat_rate` 两列未逐列核 |
| V24-141 | L459 | RFM 宽表保存 recency_days/frequency/monetary_amount/R/F/M score/segment/rule_version；M 的时间范围、退款口径与评分方向必须冻结；小样本标「仅演示」 | DONE_LIMITED | `web/src/views/Rfm.vue` 存在（页面级证据）；`t2rerun` 对照跑通过。**缺口**：宽表七列、「仅演示」标记、口径冻结文档三者本审计均未核 |
| V24-142 | L460 | 排行与 NTILE 增加稳定次序键，避免同值重跑漂移 | DONE_LIMITED | 同输入对照跑（run 43 vs run 41）ADS 8 表按快照逐表相同 ⇒ 无漂移（运行期证据）。**缺口**：「稳定次序键」是否显式写入 SQL 未核 |
| V24-143 | L461 | 异常检测：至少 14 个历史点才计算；结果表须保存 metric_code/source_instance_id/window/actual/baseline/deviation/threshold/algorithm_version/snapshot_id；**算法类必须接入生产指标链，不允许只有孤立类** | 未取证 | 本审计未定向检索异常检测结果表与算法类（由另一只读子代理负责，其报告未在本审计完成前返回）。**注意**：`source_instance_id` 列名与落地名 `source_id` 不一致（见 V24-093），若实现按本文字面命名则可能与库不符 |
| V24-144 | L465 | 建立 `quality_rule_definition` 和版本/生效区间 | TODO | `git grep -ln "quality_rule_definition" HEAD -- "*.sql" "*.java"` = **0 命中**（`raw/07` A 节）⇒ 表与代码均不存在 |
| V24-145 | L465 | 严重度行为：`BLOCKING`/`ERROR` 阻断发布，`WARN`/`INFO` 持久化并展示但不阻断 | DONE_LIMITED | **D-142 §1 人裁决保持指导书原文**，实现已按此重写：`DataQualityGate.java:20-21,44,51,73-74` + 新增 `RuleSeverity.java`（登记 21 个规则码，未登记码 → `UNREGISTERED`=保守阻断）；`f88-dq-severity-20260912/IMPL-REPORT.md:19,20,26,76-92`。**缺口**：修复**未入库**（`git status` 显示 ` M`/`??`，工作树态）；该泳道自列 4 项「未测」与 6 项「待裁」（其 §5/§6） |
| V24-146 | L467 规则1 | Landing JSON 可解析率 | DONE_LIMITED | `JSON_PARSE` 规则码实测存在（`raw/07` A 节命中集合）；**缺口**：阈值与负向验收未核 |
| V24-147 | L468 规则2 | ODS `event_id` 非空和 schema version 支持 | DONE_LIMITED | `REQUIRED_FIELD_NULL_RATE`、`SCHEMA_VERSION` 两个规则码实测存在；`f88` 把 `REQUIRED_FIELD_NULL_RATE` 由「记录」改为 `BLOCKING`（其表第 2 行）。**缺口**：「schema version 支持」的具体判据未核 |
| V24-148 | L469 规则3 | DWD 行为字段/枚举合法 | DONE_LIMITED | `ENUM_WHITELIST` 规则码存在，`f88` 改为 `BLOCKING`（表第 3 行，注明「§5.4.2 批准阈值 = 0」） |
| V24-149 | L470 规则4 | 订单行金额公式误差 < 0.01 | DONE | 同 V24-135 |
| V24-150 | L471 规则5 | 退款额 ≤ 实付额 | TODO | 搜 `REFUND_AMOUNT`/退款上限 = **0 命中**（`raw/07` A 节）⇒ 规则码层面不存在 |
| V24-151 | L472 规则6 | 非法状态跳转 | TODO | 搜 `STATE_JUMP`/`STATE_TRANSITION` = **0 命中**（同上级） |
| V24-152 | L473 规则7 | DWS 与 DWD 金额对账 | DONE | `ADS_DWS_FUNNEL_RECONCILE` 为 `BLOCKING`（`f88` 表第 8 行「漏斗层间对账」）★注：该项名为 ADS/DWS 漏斗对账，与「DWS↔DWD 金额对账」是否同一规则本审计**未取证**，暂记 `DONE` 并附此不确定说明 |
| V24-153 | L474 规则8 | ADS 的 GMV ≥ 净销售 ≥ 0 | DONE_LIMITED | `GMV` 规则相关代码存在；run 47 指标 `gmv 2,042.00 元` 可读（`e5-preaccept` 第 3 项）。**缺口**：「GMV ≥ 净销售 ≥ 0」的断言未核 |
| V24-154 | L475 规则9 | ADS 的 UV ≤ PV | TODO | 搜 `UV_PV`/`PAY_USER` = **0 命中**（`raw/07` A 节） |
| V24-155 | L476 规则10 | 支付用户 ≤ 浏览用户；不满足时必须给出口径说明 | TODO | 同上 0 命中 |
| V24-156 | L477 规则11 | `ingest_time - event_time` P95 和迟到率 | TODO | 搜 `LATE_ARRIVAL`/`迟到率` = **0 命中**（同上级） |
| V24-157 | L478 规则12 | 发布行数一致性与抽样 checksum | DONE | `MXP_EXPORT_ROWS`（导出行数 = 分区行数）、`MXP_EXPORT_COMPLETE`（8 张表导出完整）、`PUB_FORMAL_PARTITION_MATCH` 均为 `BLOCKING`（`f88` 表第 12,16,17 行） |
| V24-158 | L480 | 每条 `BLOCKING` 规则必须有一条「构造失败→流水线失败→不产生新 ACTIVE→旧 ACTIVE 可读」的**负向验收** | DONE_LIMITED | 有**一次**真实负向实例：run 44/45/46 FAILED 且 `metric_snapshot` 全表 12 行中**不存在** `S20260901_44/45/46`，ACTIVE 唯一 = `S20260901_47`（`e5-preaccept` 第 4 项 + `m3-step8-parity` §1.1）。**缺口**：这是**一条**规则的实例，不是「每条 `BLOCKING` 规则各一条」；`f88` 把 4 条规则新升为 `BLOCKING` 后**尚无**对应负向验收（其 §5「未做/未测」清单） |

## §7.4 指标存储（L482–488）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-159 | L484 | Hive 全程存在（ODS/DWD/DWS/ADS 均在 Hive）；MySQL 只作 ACTIVE 快照的低延迟服务库；页面/AI/决策只通过 MetricStore 读同一快照 | DONE_LIMITED | Hive 侧 8 张 ADS 表落数并导出、MySQL 侧 `metric_value` 10 行可读（`m3-step8-parity` §5A.2/§5A.3）。**缺口**：「页面/AI/决策只通过 MetricStore」的单入口未取证（`MetricStoreFactory` 不存在，见下行） |
| V24-160 | L486 | 建立 `MetricStoreFactory` 按 `metric_store_type` 选择实现；MySQL 为默认并完成；Hive 为管理员显式降级查询路径，不由系统按数据量随机切换；Doris 第二阶段首选；ClickHouse 仅接口与能力矩阵、标 `DEFERRED` | TODO | `git grep -l "MetricStoreFactory" HEAD` → 仅指导书/看板/审计文档命中，**代码 0 命中**（`raw/07` A 节） |
| V24-161 | L488 | 普通用户只能读 ACTIVE 快照；必须提供当前 ACTIVE 元信息接口，含 snapshotId/sourceInstanceId/businessDate/version/createdAt 和口径版本 | DONE_LIMITED | `/overview` 实测渲染 `快照 S20260901_47` / `业务时间 2026-09-01 00:00:00` / `口径版本 v2`（`e5-preaccept` 第 3 项）；ACTIVE 唯一性有库级证据（`metric_snapshot` 12 行、ACTIVE 仅 47）。**缺口**：字段名 `sourceInstanceId`（落地为 `sourceId`，见 V24-093）；「普通用户只能读 ACTIVE」的权限断言未核 |

## §8 页面、AI、决策与安全（L490–576）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-162 | L494 | §8.1 必须补齐：数据源管理、流水线 stage/job/externalJobId/log/error、总览环比、行为渠道/分类构成、商品库存覆盖天数、销售分类/地区、RFM 金额、决策截止日期、表格分页和稳定排序；页面用业务名称，**不暴露** `runtimeProfileId`/Hive 表名/Spark 参数 | DONE_LIMITED | 已存在页面：Overview/Behavior/Products/Sales/Rfm/Pipeline/Ops/Decisions/AiAssistant（`raw/07` C 节）。**缺口（E5 预验收四项实测）**：第 1 项**部分满足**（**无数据源管理页**、8092 无页面）；第 3 项**部分满足**（`/pipeline` 与 `/ops` 对 `errorMessage`/`失败原因`/`Cannot safely cast` **全为 0 命中**，只显示错误码 `RUN_JOB_FAILED`）；第 4 项「旧结果仍可用」**无显式声明**、实时失败演练**未测**（`e5-preaccept/README.md` §0 与总控采信段） |
| V24-163 | L498–502 | AI 不是独立页面或单一 Text-to-SQL，而是横向辅助三环节：①分析辅助（自然语言问数/异常解释/归因候选/经营摘要，全部引用同一 ACTIVE snapshot 的证据包）②决策辅助（固定模板生成 DRAFT，补适用前提/风险/观察窗口/目标指标，人审批执行）③新商城接入与数仓设计辅助 | TODO | `LlmProvider`/`TextToSqlService`/`SemanticCatalog`/`SqlSafetyValidator`/`EvidencePackage` 类存在（`ai-decision` 模块）；**③完全未实现**（`Blueprint`/`SourceProfileDraft` 全仓 0 命中，`raw/07` A 节）；`web/src/views/AiAssistant.vue` 为页面壳。**缺口**：AI 是否接真实 LLM 无证据（B-03 仍 `BLOCKED`，看板 L325） |
| V24-164 | L504 | 「类已存在」不代表真实 LLM、动态语义目录或 AI 建模工作台已完成；必须按证据如实登记 | DONE（口径项） | 看板对 AI 项如实标 `TODO`（`M2-AIW` 为 `TODO`）；`MockLlmProvider` 与 `OpenAiCompatLlmProvider` 两实现并存但无真实凭据证据（`raw/07` B 节） |
| V24-165 | L508 | AI 不得直接创建库表或执行任意 SQL；平台固定通用核心域（用户/商品/行为/订单/支付/退款），只有不能映射到核心域的信息才进源扩展区 | TODO | AI 建模链整体不存在（V24-166 起）；该禁止项在无实现时「未被违反」但也「无法验证」 |
| V24-166 | L510–521 | 完整流程 11 环：源连接/样本/元数据 → 确定性画像与脱敏 → AI 生成 `SourceProfileDraft`+`WarehouseBlueprint`+`WorkflowDraft` → JSON Schema/命名/必填/AST/血缘/预算校验 → 人工确认低置信度与业务口径 → 受控编译器生成 DDL/转换 SQL/固定类型作业 DAG → sandbox 用 20–100 条 dry-run → 对账/质量/证据包 → `data_dev/admin` 审批 → 不可变版本发布 → 失败回滚 `previous_release` | TODO | `git grep -ln "Blueprint\|source_profile_draft\|SourceProfileDraft" HEAD -- "*.java" "*.sql" "*.vue"` = **0 命中**（`raw/07` A 节）；`AIW-`/`ModelingWizard` 仅命中文档与专项计划（`docs/superpowers/plans/2026-09-11-ai-assisted-warehouse-onboarding-implementation-v1.0.md`）⇒ **完全没做** |
| V24-167 | L526–530 | 状态机 `DISCOVERING->DRAFT->NEEDS_CONFIRMATION->VALIDATING->SANDBOX_RUNNING->REVIEW->APPROVED->DEPLOYED`；失败终态 `VALIDATION_FAILED/SANDBOX_FAILED/REJECTED/ROLLED_BACK`；只有 `APPROVED` 能进入部署；部署须二次校验 blueprint checksum/审批人/目标 namespace/前一 release | TODO | 同上 0 命中 |
| V24-168 | L536–542 | §8.2.2 七张表：`ai_modeling_session`/`source_profile_draft`/`mapping_review_item`/`warehouse_blueprint`/`workflow_definition`/`modeling_validation_result`/`workflow_release` | TODO | `V3__platform_ai_audit.sql` 实测只有 2 张表（`:3 ai_query_history`、`:20 ai_call_log`）⇒ 七张表**一张都没有** |
| V24-169 | L544 | 结构化中间对象至少含 `SourceProfileDraft`/`WarehouseBlueprint`/`TableBlueprint`/`ColumnMapping`/`QualityRuleDraft`/`MetricDraft`/`WorkflowNodeDraft` | TODO | 全部 0 命中 |
| V24-170 | L544 | 工作流节点白名单只允许 9 类（`DISCOVER_SOURCE`/`LANDING_VALIDATE`/`ODS_LOAD`/`DIM_BUILD`/`DWD_BUILD`/`DWS_AGG`/`ADS_BUILD`/`QUALITY_CHECK`/`PUBLISH_METRIC`）；禁止 AI 生成任意 shell 节点 | TODO | 白名单本体不存在；平台侧已有的作业注册表（`JobRegistry` 链路 `sci→odl→bdw→dim→tdw→usw→fna→dqc→pub→mxp`，`PROBE-RESULTS.md:64`）是**平台作业链**，不是 AI 工作流白名单 |
| V24-171 | L548 | AI 可以提出类型/主键候选、字段/枚举映射、事实表维表、粒度、SCD、分区、代理键、质量规则、指标、血缘、资源估算、回滚建议；**必须显式提出它无法确定的问题** | TODO | 无 AI 建模实现；`questions_json` 字段所属表（`source_profile_draft`）不存在 |
| V24-172 | L550 | AI 不得决定或执行：销售/退款/复购公式、破坏性重建、低置信度强制映射、任意 SQL/shell、绕过质量门和审批、直接发布经营动作；确定性系统负责 JSON Schema/命名白名单/字段完整性/类型兼容/SQL AST/无环 DAG/扫描量与资源上限/脱敏/危险 DDL 拦截 | DONE_LIMITED | 已有确定性组件：`SqlSafetyValidator`（Text-to-SQL 侧 AST 白名单）、`SemanticCatalog`。**缺口**：无 DAG/预算/危险 DDL 拦截（对应 9 类中至少 3 类） |
| V24-173 | L554 | 模板 / Mock LLM / 真实 LLM 三路径共享结构化输出 Schema、超时、有限重试、熔断、每日预算和审计 | TODO | `MockLlmProvider` 与 `OpenAiCompatLlmProvider` 存在；**「模板」路径**与熔断/每日预算无实现证据；无真实 LLM 凭据（B-03 `BLOCKED`） |
| V24-174 | L555 | 真实调用记录 traceId/provider/model/prompt_version/输入输出 token/耗时/状态/错误类型/费用估算；traceId 与 input_fingerprint 用于幂等，避免重复计费 | DONE_LIMITED | `V3__platform_ai_audit.sql:20 ai_call_log` 与 `ai_query_history` 两表存在，可承载部分字段。**缺口**：token/费用/`input_fingerprint`/幂等断言的逐列核对未做；且无真实调用可产生记录 |
| V24-175 | L556 | 样本发给外部模型前做字段级脱敏与数量上限；列名/注释/样本值按不可信输入处理，防 prompt injection | TODO | 无脱敏器实现证据；§9.3 要求的「安全集」（prompt injection / SQL shell 注入 / 路径穿越 / PII 泄漏 / 循环 DAG / 破坏性 DDL / 超预算 / 超时 / 模型不可用）同样无证据 |
| V24-176 | L557 | Text-to-SQL 继续经过语义目录、AST 白名单、EXPLAIN、扫描/行数/时间限制和只读身份；建模 SQL 只能由受控编译器从已验证蓝图生成 | DONE_LIMITED | 语义目录与 AST 白名单类存在（`SemanticCatalog`/`SqlSafetyValidator`）；E5 预验收实测 `/pipeline` 与 `/ops` 展示错误码。**缺口**：EXPLAIN、扫描/行数/时间限制、只读身份三条未取证；受控编译器不存在 |
| V24-177 | L558 | Hive、MySQL、API、DOM、AI 必须在同一 snapshotId 下做**五处同值断言**；AI 无证据时输出「不足」，不得估算或补零 | DONE_LIMITED | 同快照跨面一致性有一个正例：`/overview` 与 `/ops` 同快照 `S20260901_47`。**缺口**：「五处同值」中的 **DOM 与 AI** 两处无证据；且 E5 实测发现同快照 `/overview` 质量=PASS 而 `/ops`=未通过（并入 F-88）⇒ 反而有一处**反例** |
| V24-178 | L562 | §8.2.5 七步建模向导：选择数据源与样本 → 查看画像 → 审核 AI 字段映射与置信度 → 查看分层表/粒度/血缘蓝图 → 执行规则校验和 sandbox → 查看差异/对账/风险 → 审批/部署/回滚；普通员工可用解释与默认值完成草案，**只有 `data_dev/admin` 可以批准部署** | TODO | 向导页面不存在（前端 10 个 view 中无建模页）；`data_dev` 角色**存在**（`RolePermissions.java`、`PermissionCode.java`、`AuthServiceRoleWhitelistTest`、`RolePermissionsTest`，`raw/07` B 节）⇒ 角色就绪、向导未做 |
| V24-179 | L566 | §8.3 完成五个决策模板：流量下降、漏斗流失、商品转化、库存风险、用户召回；每模板含发现/量化证据/适用前提/建议动作/目标指标/观察窗口/截止日期/风险 | DONE_LIMITED | `decision_task` 表有 `target_metric_code`/`target_direction`/`baseline_value`/`status`（12 态）与 `suggestion_snapshot_id`（`V4__platform_decisions.sql:8-19`）、`web/src/views/Decisions.vue` 页面存在、`DecisionService`/`DecisionStateMachine` 有单测。**缺口**：**五个模板本身未取证**——`decision` 包内搜 `TEMPLATE`/`templateCode` = **0 命中**（`raw/07` A 节），且无 `deadline`/风险/观察窗口字段 ⇒ 模板化程度不足 |
| V24-180 | L566 | 效果评估使用等长前后窗口聚合，**不得只比较两个端点快照** | DONE_LIMITED | `decision_evaluation` 表存在，`result` 四值（`EFFECTIVE/PARTIAL/INEFFECTIVE/INSUFFICIENT_DATA`）与注释「前后对比，非因果推断」（`V4__platform_decisions.sql:40,46`）。**缺口**：「等长窗口聚合」的算法断言未核 |
| V24-181 | L568 | 必须生成并保存 EFFECTIVE、PARTIAL、INEFFECTIVE、INSUFFICIENT_DATA **四类可达样本** | DONE_LIMITED | 四值枚举与 `DecisionServiceTest`/`DecisionStateMachineTest` 存在。**缺口**：「四类**样本**已生成并保存」是数据证据，本审计未在库/验收目录中找到（搜索范围：`docs/acceptance/**` 未见决策效果样本文件） |
| V24-182 | L568 | 人工草稿来源为 `human`；AI 草稿来源记录模型和证据版本 | DONE | `decision_task.source` 注释「ai=AI草稿(只能DRAFT) / human=人工创建」，`suggestion_snapshot_id` 存 AI 建议快照（`V4__platform_decisions.sql:8-9`）；`DecisionService.java:81-82` 定义 `SOURCE_AI`/`SOURCE_HUMAN` |
| V24-183 | L572 | 增加 `data_dev` 测试角色；前端路由和后端 API **双层**鉴权 | DONE_LIMITED | `data_dev` 存在且有多份权限测试；`AuthInterceptor` 存在。**缺口**：「前端路由层」鉴权未取证（`raw/07` B 节只核到后端与权限常量） |
| V24-184 | L573 | 删除可用默认口令；生产可关闭种子账号并要求首次登录改密 | TODO | `V5__platform_users.sql:25,28,31` 实测**插入 3 个种子账号**（`INSERT INTO sys_user (username, password_hash, real_name, role, status)`）⇒ 「删除可用默认口令」未完成；本审计未核这 3 个口令是否可登录 |
| V24-185 | L574 | Hive/SSH/LLM 凭据统一使用 `credential_ref`；日志脱敏 password、token、Authorization、key | DONE_LIMITED | 生成器侧有明文密钥哨兵（`TargetController.java:113-120`）与 `credentialRef` 纪律；§6.3 凭据引用字段存在。**缺口**：平台侧日志脱敏的**运行期断言**未取证 |
| V24-186 | L575 | 分开「启动流水线」和「重试」权限 | DONE_LIMITED | `PermissionCode`/`RolePermissions` 存在细粒度权限。**缺口**：「启动」与「重试」是否为两个独立权限码未逐字核 |
| V24-187 | L576 | 审计环境激活、流水线启动/重试、质量强制处理、快照激活和归档快照读取 | DONE_LIMITED | `DecisionControllerAuditTest` 存在；`V3__platform_ai_audit.sql` 两类审计表；归档快照读取实测可用（`/ops` 归档快照 `S20260901_43` 可读 10 行）。**缺口**：五类审计事件是否**全部**落审计表未逐条核；「质量强制处理」审计未取证 |

## §9 并行开发和测试提速（L578–609）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-188 | L582–587 | 四并发槽位「一总控 + 三开发泳道」；A=三程序拆分/连接器/集群，B=数仓/ADS/质量/指标发布，C=页面/AI/决策/安全 | DONE | 看板任务表按泳道组织（§3.2 M1-4 S3b、§4 M2 泳道表 P1-01…P5-04）；验收目录命名即泳道号（`p1-*`…`p5-*`） |
| V24-189 | L589 | `PipelineService`、迁移版本号、统一事件契约、前端 router、主配置属热点文件，同一时间只允许一个 Owner；跨泳道字段变化先由总控登记契约任务 | DONE_LIMITED | 看板 L61–69 热点文件清单 ✅；D-096 认定越权扩表并裁回滚（同 V24-017）。**缺口**：回滚完成状态在本审计时刻未取证 |
| V24-190 | L595–599 §9.2 | T0–T4 五层定义与执行时机（T2 = 55 条黄金链真实本地 MySQL/Spark/Hive，一批合并后一次；T3 = 固定 seed 1,000 条集群链；T4 = 10 万/100 万，仅里程碑） | DONE | 看板 L12–46 定义 ✅；T2 实跑记录：run 47 本地 golden-55 **首次全绿** 8/8 SUCCESS、快照 `S20260901_47` ACTIVE v12、10 指标可读（`m3-step8-parity` §1.1、看板 L514 第 ③ 项）；同输入对照 run 43 vs run 41 逐值相同（`t2rerun`） |
| V24-191 | L601 | 不删除已有测试，只把重测试移动到里程碑门；**以下变化必须运行 T2**：事件契约、分区、金额口径、流水线阶段、发布事务、质量阻断 | DONE_LIMITED | T2 在 CT/契约改动后确实复跑（`t2rerun-golden55-post-ct-20260912`）；`f88` 改了**质量阻断**口径且其报告含全 reactor E2（537 测试/1 红的历史态）。**缺口**：`f88` 的改动**尚未**触发一次 T2（其报告未声明 T2；且改动未入库）；「不删除已有测试」本审计未做全量 diff 核对 |
| V24-192 | L603 | 开发阶段优先固定 seed、覆盖所有事件类型和失败分支的小数据；「缩数据量」不能缩业务场景；每个失败只复跑受影响层，修复后再过当前里程碑门 | DONE_LIMITED | golden-55 固定夹具、失败复跑只跑 `BUILD_DWD`（run 44→47 的定位过程，`m3-step8-parity` §3）✅。**缺口**：「覆盖所有事件类型」的当前覆盖率未核（`event_type` distinct 实测 12，见看板 L513 第 ① 项） |
| V24-193 | L607 §9.3 | AIW 日常不反复跑大集群；T0/T1 用三个固定小源（参考商城 A、异构源 B、含歧义与恶意内容的源 C），每源 20–100 条脱敏记录 | TODO | 三源夹具中只有 A 与 B 部分存在（`p5-heterogeneous-source` 的 `fixture-b.v1.json` 属**未跟踪**文件，`raw/04`）；**源 C（恶意内容）无任何证据** |
| V24-194 | L607 | 稳定评测集统计字段映射 precision/recall、必填字段召回、枚举覆盖、蓝图规则通过率、编译首过率、sandbox 成功率、人工修正项数、耗时、token 与估算费用 | TODO | 无 AIW 实现 ⇒ 无评测集；10 项指标全部无证据 |
| V24-195 | L609 | AI 接入有效性必须用同一异构源 B 做 A/B 对照（人工配置=基线，AI 辅助=实验组，环境/数据/验收脚本相同），输出真实时间、修正次数、成功率和费用，不预填结论 | TODO | 人工基线侧刚做完（`p5-heterogeneous-source-20260912`，实测 180 行 / 92 行与冻结期望不符）；AI 组不存在 ⇒ 对照不可能 |
| V24-196 | L609 | 没有实测前不得填写「效率提升百分比」 | N/A（约束） | 见 V24-003 |
| V24-197 | L609 | 安全集必须覆盖样本/列名 prompt injection、SQL/shell 注入、路径穿越、PII 泄漏、循环 DAG、破坏性 DDL、超预算、超时和模型不可用；模型不可用时允许保存人工草案，但不得伪造 AI 成功 | TODO | 九类安全用例**无任何证据**；`SqlSafetyValidator` 只覆盖 Text-to-SQL AST 白名单一类 |

## §10 任务包格式（L611–631）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-198 | L615–629 | 任务包必须含 13 项（任务 ID/标题、目标与用户价值、当前证据与缺陷、允许修改的模块和文件、禁止修改的模块和文件、输入契约与数据结构、输出契约/API/页面、算法与边界条件、依赖任务、完成定义、T0/T1 最小测试、里程碑测试与证据路径、下次反馈时间），**缺一不可** | DONE_LIMITED | 看板 §3.1 有 M1-4 任务包实例（含允许修改文件白名单）。**缺口**：13 项是否**逐项齐备**未核；D-096 的越权扩表恰说明「禁止修改的模块和文件」一栏未被遵守或被绕过 |
| V24-199 | L631 | Agent 遇规格缺失时标 `BLOCKED` 并列出唯一需要决策的问题，不得自行发明字段或改变业务口径 | DONE_LIMITED | 有正面实例（B-04/B-05/B-06 三类规格阻塞登记并等待裁决）。**缺口**：也有反例（D-096 自行扩表、F-86 断言与实现冲突时未先问）⇒ 流程存在但执行不齐 |

## §11 实施顺序（L633–645）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-200 | L635 步1 | M1 冻结三程序边界、契约和独立构建（后续并行的前提） | DONE_LIMITED | M1 任务表 M1-1…M1-12 多为完成；M1-9② 已交付（第二适配器在 HEAD）；`contract-specs` 仍 `DRAFT`（V24-055）⇒ 契约冻结**未完全收口** |
| V24-201 | L636 步2 | M2 建立 source instance、connector 和 mapping；**同时收集集群参数** | BLOCKED | source registry 已建（V16/V17/V18）；**connector 与 mapping 不存在**（V24-097/098/102/103/106）；集群参数收集状态与看板 B-02 的 `BLOCKED` 冲突（见 §6.5 逐条表与发现 R-06） |
| V24-202 | L637 步3 | M3 先跑集群预检和 55 条链，再接 1,000 条链 | DONE_LIMITED | 预检已做（`m3-cluster-probe`、`m3-preflight`）；**1,000 条集群链已真实成功**（E4 10/10 SUCCESS，`e4-cluster-1000-20260912`）；**55 条链的集群版本未跑**（`m3-step8-parity` §6「集群链路：未执行」）⇒ 顺序被执行成「预检 → 1,000 条」，与「先 55 后 1000」不同，登记为**发现 R-07** |
| V24-203 | L638 步4 | P5 先用异构源 B 完成一次完全确定性的、**配置驱动接入**，作为 AI 接入的人工基线；不得用 AI 掩盖连接器/映射/语义注册表尚未完成的问题 | TODO | **实测结论为否**：`p5-heterogeneous-source-20260912/IMPL-REPORT.md:13`「本平台无法仅靠『登记配置』接入一个字段名/枚举/时间语义全异构的源。实测 180 行：92 行结果与冻结期望不符（期望 ACCEPT 120 / QUARANTINE 60；实测 ACCEPT 84 / QUARANTINE 96）。根因**架构级**：画像 6 个语义键在生产代码的读取面上为零，『配置驱动接入』目前**只有校验、没有执行**」（登记 F-89） |
| V24-204 | L639 步5 | M2-AIW 分两段：先「AI 源画像与映射助手」，再「受控数仓蓝图、工作流编译、sandbox、审批、发布与回滚」 | TODO | 看板里程碑表 `M2-AIW` = `TODO`；AIW-001…020 无交付；`raw/07` A 节 0 命中 |
| V24-205 | L640 步6 | B 泳道并行补 DIM/ADS/质量/指标；C 泳道并行补页面、AI 分析、决策和安全；热点文件由总控串行控制 | DONE_LIMITED | B 泳道有明显进展（CT、P2-01…P2-07、e4）；C 泳道页面/决策/安全部分存在但 §8.2 AI 与 §8.1 数据源页缺（V24-162/166） |
| V24-206 | L641 步7 | 每个合并批次只跑一次 T2；跨集群里程碑跑一次 T3；AI 日常评测只跑 20–100 条固定集 | DONE_LIMITED | T2 复跑有记录（`t2rerun`）；**T3 未跑**（V24-202）；AI 评测集不存在（V24-193） |
| V24-207 | L642 步8 | 完成证据复核队列后，**重新执行 269 条核查，生成新的冻结快照** | TODO | 看板明示「`132/90/42` 仅历史冻结基线，未逐条复核 269 项前不得作当前统计」（L514 第 ⑩ 项）⇒ 269 条核查未重跑，新冻结快照不存在。**本审计的 226 条 ≠ 269 条**，不可互相替代 |
| V24-208 | L643 步9 | 项目功能和证据**完成后**才统一撰写论文和答辩材料 | N/A（禁止项）+ DEFERRED | 功能与证据未完成（本表 226 条中大量 `TODO`/`未取证`）⇒ 目前**不得**开始写论文，与 L53 的 `DEFERRED` 一致 |
| V24-209 | L645 | 具体任务/状态/依赖/证据要求**全部以配套进度看板为执行入口** | DONE（口径项） | 看板 V2.2 为唯一执行入口（521→522 行，本审计期间仍在追加） |

## §12 2026-09-11 M1-12 后的执行裁决（L647–683）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-210 | L651 | 已确认事实：`M1-1/2/6/7/8/10` 已完成 | DONE_LIMITED | 看板 M1 任务表同述；本审计未逐条复核这 6 项的证据（属「引用看板单元格」而非实测） |
| V24-211 | L652 | `M1-12` 已以 E2+E3 证明代码闭环：checkpoint 键由 `LocalFileIngestor.checkpointKey/canonical` 唯一铸造；真实读数由 101 归一为 51；无新数据时不重读 | DONE_LIMITED | B-11/D-0xx 闭环并有看板登记（L331）；`LocalFileIngestor` 类存在。**缺口**：本审计未复核该 E2+E3 的原始日志 |
| V24-212 | L653 | 数据库内 50 行含 `\.\` 的历史 checkpoint 副本仍保留；不作为 P1–P5 前置条件；**不授权 Agent 擅自删除** | N/A（约束） | 未发现删除动作；看板把它记为不可逆数据变更 |
| V24-213 | L654 | `M1-5` 契约目录处于 `REVIEW`；`M1-11` 本地提交器进程态处于 `REVIEW`；`M1-4` 的 S1/S2/S3a/S3b 已完成，**页面与 `MALL_API` 收尾未完成** | DONE_LIMITED | `MALL_API` 收尾已由 M1-9②/S4 系列推进（第二适配器在 HEAD）；**页面分支仍未做**（V24-091，但判据为「页面或 CLI」⇒ 已满足）。契约目录仍 `DRAFT`（V24-055） |
| V24-214 | L655 | 三服务独立运行证据存在；当前最新真实链路为 run 39、快照 `S20260901_39` | REGRESSED（陈述过期） | 本审计时刻最新为 **run 47 / 快照 `S20260901_47`**（ACTIVE v12，`m3-step8-parity` §5A.1）；`S20260901_39` 已归档。⇒ 该行属**陈述过期**，不是缺口 |
| V24-215 | L659 | 下一主线选 **A：分析平台商城无关化 P1–P5**；商城功能与美化不进入关键路径；C 类小收尾可并行但不得占用公共契约、迁移号、`PipelineService` 或 P1–P5 热点文件 | DONE_LIMITED | P1–P5 确为主线（验收目录与看板泳道即 P1…P5）；**但 F-88 改的正是 `PipelineService`**（热点文件），且未入库（V24-145）⇒ 边界执行有偏差 |
| V24-216 | L661 | 任何开源商城都通过 Adapter/Profile 接入，**禁止为适配它修改平台核心 SQL、事件常量或表结构** | DONE_LIMITED | 目前尚无开源商城接入实例；平台侧核心 SQL 未被为适配而改（无证据表明改过）。**缺口**：无正面验收 |
| V24-217 | L665 §12.3-1 | P5 先用**纯文件异构 fixture**（便宜、可复现、验字段/枚举/UUID/多余字段差异）；接真实开源商城是 P5 后的加分项 | DONE | `p5-heterogeneous-source-20260912` 用的就是文件夹具（源 B 未激活，D-143 追认替代方案）；`analytics-server/source-profiles/fixture-b.v1.json`（未跟踪） |
| V24-218 | L666 §12.3-2 | ODS v2 允许对演示数据重建，但**重建前必须备份对应 warehouse 目录、记录表数/行数/checksum，并由显式 `INIT_SCHEMA` 审计步骤执行**；不得静默 DROP，不得影响其他 source namespace | DONE | `p2-05-ods-rebuild-guard-20260912`（6 文件）为该守卫的验收包；P2-01 ODS v2 重建有 84 文件证据（`p2-01-ods-v2-20260912`） |
| V24-219 | L667 §12.3-3 | 新增 `source_registry`，**不折叠进 `runtime_profile`**；source 表示长期身份、profile 表示可变运行参数 | DONE | `V16__source_registry.sql` 独立成表 ✅；`source_registry.id=2` 可停 `DRAFT`/`current:false` 而源 A 保持 ACTIVE/current（`p5-heterogeneous-source` §3） |
| V24-220 | L668 §12.3-4 | P4 先做单源切换器；同屏跨源比较推迟到 P5 之后；第一版 API 未传 source 时返回当前激活源，**并在响应中显式带 `sourceCode`** | DONE_LIMITED | 源切换与命名空间前缀有 P2-07 证据；`sourceCode` 字段存在于清单（F-86 取证中 `42.json` 顶层键含 `sourceCode/sourceId/profileVersion/mappingVersion`，看板 L513 第 ③ 项）。**缺口**：`GET` 响应带 `sourceCode` 的接口级断言未核 |
| V24-221 | L669 §12.3-5 | **数据源管理不能永久只有 API**；P1/P2 可先交 API，**P4 出口必须有最小管理员页面**，至少支持列表、查看状态、测试连接、启停、选择当前源、查看画像校验结果；可视化拖拽字段映射后续再做 | TODO | 前端**无数据源管理页**（V24-110/162）；P4 出口页面未交付 |
| V24-222 | L673 §12.4 | `canonical-event.v1.schema.json` 是目标严格契约；旧夹具与旧校验器登记为 `mock-mall-legacy-v1` 兼容来源；**不能把 26/55 校验通过伪写成契约已完全冻结** | DONE_LIMITED | `p2-01-ods-v2-spec-draft`、`p2-01-ods-v2` 走严格契约重建 golden-55；CT（契约测试）批次有 `ct-batch-20260912`（94 文件）与 `ct-integration-20260912`。**缺口**：`mock-mall-legacy-v1` 兼容来源的登记位置未核 |
| V24-223 | L674 §12.4 | P1 不改变黄金指标；P2/P3 由新生成器重建符合严格契约的 golden-55，再逐步移除兼容规则；**兼容规则必须按 source profile/version 生效，不能成为全局放宽** | DONE_LIMITED | `t2rerun` 证明 CT 未破坏指标面：run 43 与 CT 前 run 41 指标 10/10 五元组全等、ADS 镜像 8 表逐表相同、导出 `totalRows=30` 且 8 个 jsonl 与库侧按表名对齐全等 ⇒ **P1 未改变黄金指标** ✅。**缺口**：「兼容规则按 profile/version 生效」的断言未核 |
| V24-224 | L675 §12.4 | `synthetic=true` 放在 generation run、artifact manifest 和验收元数据中，**不加入 8 字段事件信封**；真实与模拟事件保持相同业务契约 | DONE_LIMITED | 生成器侧清单固定 9 字段（`JsonlEventSinkTest.java:84`）、事件信封为 8 字段契约；**缺口**：「不加入 8 字段信封」的负向断言未逐字核 |
| V24-225 | L679 §12.5 | `newFileCount` 口径改为「当前目录中至少含一条可采集完整记录的文件数」，文案统一为「**可采集文件数**」；API 保留 `newFileCount` 但须在 DTO/OpenAPI **标 deprecated**，新增语义准确的 `consumableFileCount`，过渡期两者同值，**前端只读新字段** | DONE_LIMITED | D-… 已闭环（看板 L330/L340 记 B-08 已闭环并另建兼容性小任务）。**缺口**：`deprecated` 标记与「前端只读新字段」两条本审计未核 |
| V24-226 | L683 §12.6 | P1–P5 的文件级步骤/迁移结构/算法/失败态/测试门见专项实施书；**每个阶段必须独立提交、可回滚、在看板更新后才允许进入下一阶段** | DONE_LIMITED | 各阶段有独立验收目录与提交；`m3-step8-parity` 显示平台可完整还原（§5「平台是否还原」+ 输入隔离已回滚 `sha256` 全等）。**缺口**：「看板更新后才进入下一阶段」的执行一致性未核 |

## 版本变更说明 V2.3 → V2.4（L711–720）

| 编号 | V2.4 行 | 要求（逐条） | 状态 | 证据指针 / 缺口说明 |
|---|---|---|---|---|
| V24-227 | L718 | `ExternalOrder.status`/`ExternalRefund.status` 商城没给时置 `null`（拒绝自造 `UNKNOWN`）—— 已落地 `ed8c531` | DONE | `ExternalOrder.java:38-40`、`ExternalRefund.java:30-32`；全 `src/main` 搜 `UNKNOWN` = 0 代码字面量；`SecondMallAdapterOperationsTest.java:688,725,740,747` 有断言 |
| V24-228 | L718 | `ProductPage` 扩为五字段（含 `unmappedStateWords`/`stateFieldMissing`）—— 已落地 | DONE | `ProductPage.java:37-39`（五元组）；`MallApiGenerationEngine.java:259-260,383-390` |
| V24-229 | L718 | `priceUnreadable`（第三个缺口通道）—— **D-067 已裁、尚未落地** | 已落地（指导书陈述过期） | **实测与指导书该行相反**：`git grep -n "priceUnreadable" HEAD -- "synthetic-data-generator/*"` 命中 `ProductPage.java:14,16,31,39,48,77` 与 `SecondMallHttpAdapter.java:313,326,337,339,346,348` ⇒ **已在 HEAD**。登记为**发现 R-08**（指导书自述「尚未落地」已过期，非缺口） |

---

## §6.5 USER-INPUT 逐条提取（V2.4 L428–440，原文见 `raw/05-v24-6.5-userinput-verbatim.txt`）

| 编号 | V2.4 行 | 原文要求（摘） | 用户是否提供 | 是否有替代实测 | 结论 |
|---|---|---|---|---|---|
| U1 | L432 | node01/02/03 主机名/IP 与 NameNode/DataNode/ResourceManager/NodeManager/Hive/Spark 角色 | **未提供** | 部分：主机名保留 `node01/02/03`；`hdfs dfsadmin -report` 得 Live datanodes **3**（node01/02/03 各 97.70 GB）；RM 在 node01（8088/8032/8030/8031/8033）；容器 host=node03；内网 IP 被掩码为 `192.168.18.10x`（`MASKING.md`） | **部分满足**：角色分布已由实测推出，但**原始 IP 未提供**；NodeManager/Hive/Spark 的逐节点角色未逐一确定 |
| U2 | L433 | HDFS NameNode URI/端口、允许写入的根目录、warehouse 路径、服务用户权限 | **部分提供**（用户 A 答：「授权我以 root 身份在 `/graduation` 建目录」） | `defaultFS=hdfs://node01:8020`；建立 `/graduation/{warehouse,jars,staging,probe,eventlog}`；服务用户 `root` | **满足**（URI/端口/可写根/warehouse 路径/服务用户四项齐） |
| U3 | L434 | Spark 是 YARN client / YARN cluster / standalone；队列名称；提交命令路径 | **未明确提供** | 实测 `--master yarn --deploy-mode cluster`、队列 `default`（`PROBE-RESULTS.md:17,22,53`）；客户端工具链 `D:\soft\hadoop\hadoop-3.3.4` + `D:\Develop\spark-3.5.1-bin-hadoop3` | **满足**（用户未给，Agent 自己测出；但「提交命令路径」是**本机 Windows** 路径，未与用户确认是否为长期约定） |
| U4 | L435 | HiveServer2 JDBC 或 Metastore URI；数据库名前缀 | **未提供** | Metastore `thrift://node01:9083` 可直连（7 库 `default, exam, ods, scott, shop, tmp, yjxxt`）；HiveServer2 10000 **closed** | **部分满足**：Metastore 路径成立；**数据库名前缀未定**（`--hiveDatabasePrefix=m3s8` 仅作方案入册、本轮不执行，看板 L515 的 G2 裁决） |
| U5 | L436 | SSH 主机、端口、用户、密钥/凭据引用、是否经过跳板机 | **用户答「暂时不需要」** | 未使用 SSH（`PROBE-RESULTS.md:3,60`） | **用户显式关闭**（非缺口，但 V24-125/126 的 SSH 类要求因此停在 `TODO`） |
| U6 | L437 | Hadoop/Spark/Hive/Java 版本与安装目录；作业 jar 位于提交节点还是 HDFS | **未提供** | Hadoop 3.3.4（RM 自报）、Spark 3.5.1、Scala 2.12.18、Java **1.8.0_351**（容器内取值）；安装目录 `/usr/java/jdk1.8.0_351-amd64`、`/opt/yjx/hadoop-3.3.4`；jar 已上 HDFS（`/graduation/jars/spark-3.5.1/` 252 个 / 337,677,190 B） | **满足**（版本/目录/jar 位置三项全部由实测得到；注意 F-50：容器 JVM=Java 8 ⇒ 作业产物须 major ≤ 52） |
| U7 | L438 | 是否启用 Kerberos，以及 principal/keytab 的安全提供方式 | **未提供** | 实测**无 Kerberos**（`simple` 认证：未配置任何票据即成功，`m3-cluster-probe/README.md:20`） | **满足**（结论为「未启用」；principal/keytab 因此不适用） |
| U8 | L439 | Flume agent 运行位置；商城事件到 agent spool 的传输方式和监听目录 | **未提供** | **无任何替代证据**：Flume 从未运行（`m3-cluster-probe` 只做 HDFS/YARN/Metastore）；项目实际走本地 `landing/events` 目录 + checkpoint（`e5-preaccept` 第 2 项） | **未取证（仍悬空）**——这是 9 项中**唯一**既无用户输入、又无替代实测、也无实现的一项 |
| U9 | L440 | 平台、商城、MySQL 与集群之间实际开放的端口 | **未提供** | 集群侧有实测 13 行端口表（`port-probe.txt`：2181/8030/8031/8032/8033/8042/9083/9866/9867 OPEN；8041/10020/16020/19888 closed） | **部分满足**：**集群侧**已实测；**平台 8091 / 商城 8090 / MySQL 与集群之间的互通关系**未成表 |

**§6.5 小结（只报实测到的）**：9 项中 **4 项满足**（U2/U3/U6/U7）、**4 项部分满足**（U1/U4/U9，以及 U5 属用户显式关闭）、**1 项仍悬空未取证**（U8 Flume）。
**必须与看板并读的矛盾**：看板 L324 的 `B-02` 状态单元格在本审计时刻**仍是 `BLOCKED`**，而上述实测显示 9 项中已有 8 项获得（用户给或实测得）取值 ⇒ 登记为**发现 R-06**。本审计**不**自行把 B-02 改判为已闭环：因为「Agent 自己测出来」与「用户按 §6.5 提供」是两件事，且「剩余项是否需逐条书面销项」本身**未取证**。

---

## 统计（本表口径）

- 条目总数：**229**
- 各状态计数：见 `README.md` 的统计表（同一口径，避免两处数字漂移）
