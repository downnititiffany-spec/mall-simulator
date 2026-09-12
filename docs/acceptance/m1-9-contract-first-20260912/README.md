# M1-9 契约先行：§4.1 追认与补全（2026-09-12，总控执行）

> 任务：看板 M1-9「第二 `MallTargetAdapter` 或不同字段夹具，证明未写死 `mock-mall`（V2.1 §3.4-6）」的**必办项 ① 契约先行**。
> 依据：指导书 §2.1（实现与契约冲突先提交契约变更决策）、看板 M1-9 必办项 ①、决策记录 D-041（契约先行授权）。
> 交付物：`docs/项目完整实施指导书 V2.3.md`（V2.2 的迭代版，新增 §4.1.1）＋本文件 ＋ 看板 §5.1 裁决 D-048/D-049/D-050。
> **本轮只做契约与载体，不写一行 Java/SQL，不启停任何服务**；M1-9 ②（第二适配器）**未开工**。

---

## 1. 任务与范围

| 维度 | 内容 |
|---|---|
| 目标 | 把 S4a/S4b 在 §4.1 空白处做出的实现侧选择**追认为契约**，并补齐"新增一家商城"必须遵守的硬约束，使 M1-9 ② 有据可依 |
| 允许修改 | `docs/项目完整实施指导书 V2.3.md`（新建）、`docs/README.md`、`docs/项目实施进度与任务看板 V2.2.md`、`docs/项目实施进度与任务看板.md`（仅加冻结指针）、`docs/backups/m1-9-contract-first-20260912/**`、本目录 |
| 明确不做 | 不改任何 Java/SQL/配置/契约 JSON；不动 8090/8091/8092；不实现第二适配器；不删除任何文件；不改写任何历史证据正文 |
| 完成判据 | ① §4.1.1 落盘且 §4.1 正文逐字未变（哈希留证）；② §4.1.1 每条【追认】都能指到 `path`；每条【新增要求】都标注"尚未实现"；③ 载体唯一（指导书一份、看板一份）；④ 契约-vs-代码审计表含此前未登记的偏离 |

## 2. 交付物

| 文件 | 类型 | 说明 |
|---|---|---|
| `docs/项目完整实施指导书 V2.3.md` | 新建 | V2.2 ＋ §4.1.1「核心接口的追认与补全」＋ 7 处头部自指更新（29,922 字符 / 696 行，LF、无 BOM） |
| `docs/backups/m1-9-contract-first-20260912/` | 新建 | 生成 V2.3 前的 V2.2 冷备 ＋ `MANIFEST.md`（SHA256、回滚步骤），依 §2.1.1「先备份当前文件」 |
| `docs/README.md` | 改 | 当前权威入口改为 V2.3；历史清单补 V2.2 指导书；登记 V2.2 看板为唯一入口与历史看板漂移 |
| `docs/项目实施进度与任务看板 V2.2.md` | 改 | 权威指针改指 V2.3；M1-9 行 `TODO`→`IN_PROGRESS`＋证据；M1-4 状态勘误（指针化）；§5.1 新增 D-048/D-049/D-050；§6 变更日志 |
| `docs/项目实施进度与任务看板.md` | 改（4 行） | 顶部加**冻结指针**（只读，后续不再更新） |
| `raw/v2.3-diff-*.txt` | 原始证据 | `git diff --no-index --stat/-U0`（V2.2 → V2.3），用于核对"除头部自指与 §4.1.1 外无其他改动" |
| `raw/v2.3-selfcheck-*.txt` | 原始证据 | 9 项结构自检（§4.1 正文未变 / §4.1.1 唯一 / 两个文件的 SHA256 / LF / 无 BOM / 冷备清单） |

## 3. 方法与证据级别

- **证据级别：文档级（D）**。本轮**没有**任何 E1（编译）/E2（测试）/E3（真机）执行；§4.1.1 的每条【追认】都是**只读代码取证**（`read` 取 `path:line`），不是运行结果。**不得**把本轮当作 M1-9 的功能证据。
- 取证方式：逐条读源文件与配置文件；对每条断言记录 `path`（关键处到行号）；对不能确认的写进 §11 未取证清单。
- 变更自证：V2.3 与 V2.2 的差异用 `git diff --no-index` 全量留档，人工可核对只有两类改动（头部 7 处自指、§4.1 之后新增 §4.1.1）。
- 反熵口径：本轮做的是**所有者收敛**（同一份契约只留一个载体、同一份任务状态只留一个入口），不是删代码；不触发删除授权（见 §9）。

## 4. 契约-vs-代码审计（§4.1 的空白处到底被怎么填的）

| # | 项 | 契约原状 | 实现证据（只读取证） | 登记状态 |
|---|---|---|---|---|
| **D1** | 8 个方法多出 `TargetConfig` 首参 | §4.1 L120–128：**只有** `test(TargetConfig)` 带首参 | `adapter/MallTargetAdapter.java`：`capabilities`／`listProducts`／`createSyntheticUser`／`emitBehavior`／`createOrder`／`pay`／`cancel`／`refund` | 验收 README §6 D1 **只写了"七项业务方法"**，少登记 `capabilities` ⇒ 本轮按 **8 个**追认 |
| **D2** | 15 个 DTO ＋ 2 个枚举的字段均由实现自定 | §4.1 只有签名（0 个字段名） | `adapter/` 下 `TargetConfig`…`MallOperationException` 等类型 | 验收 README §6 D2 已登记但少列 `TargetCheckResult`／`TargetCapabilities`／`MallCapability`／`CapabilityVerdict` ⇒ 本轮补齐为最小字段表 |
| **J1** | `capabilities()` 语义被改写 | §4.1 L121 无参、无三态、无 `declared` | `TargetCapabilities`（`verdicts`＋`declared`，缺键⇒`UNDETERMINED`）、`CapabilityVerdict`（三态）、`ReferenceMallHttpAdapter` 由 `TargetConfig` **本地推导、不联网**、`FileModeTargetAdapter` 同样 | **此前未登记** ⇒ 本轮登记为【追认】＋硬约束 5 |
| **J2** | 新增 §4.1 没有的方法 `String adapterType()` | §4.1 九方法无此项 | `MallTargetAdapter.java`、`MallTargetAdapterRegistry`（注册/查找键） | **此前未登记** ⇒ 本轮追认；`contract-specs` 明说 `adapter_type` 取值集合未冻结 ⇒ 无需改机器可读契约 |
| **J3** | `EventSink` 多继承 `AutoCloseable` ＋ `default close()`；返回类型 `Manifest`→`ArtifactManifest` | §4.1 L131–136 | `contract/EventSink.java` | 类名改名已由决策记录裁决（"只动 Java 类名"）；`close()` **此前未登记** ⇒ 本轮一并追认 |
| **J4** | `generator_target` 有 10 列，进 `TargetConfig` 的只有 5 列 | §4.2 L145 列出 10 列 | `meta/GeneratorMetaStore` 的目标列映射 vs `TargetConfig` 五字段 | **此前未登记** ⇒ 本轮明确：`name`/`config_version`/`status`/`test_environment`/`capabilities` 不参与适配器判定 |
| **R1** | 运行流水的 `http_method`/`route` 是**引擎内置的参考商城字面量** | 契约未规定 | `engine/MallDispatchPlan.java` 的路由常量与事件→操作表；`engine/MallApiGenerationEngine` 预检处亦写死方法与路由 | **此前未登记**（与已关闭的 D12 同属"证据真实性"类）⇒ 本轮立为【新增要求】：路由所有权归适配器 |
| **R2** | 预检强制 `product`/`user`/`order` 三能力、且必须先读到商品目录 | 契约未规定 | `engine/MallApiGenerationEngine` 预检分支 | 【追认】为现有语义（能力不满足即拒绝启动，不降级成文件模式）；"没有商品目录接口的商城接不进来"作为**已知边界**留在 §11 |
| **R3** | `MallCapability` 是编译期枚举（7 键） | 契约未规定 | `adapter/MallCapability.java` | 【追认】并写明：新增能力名必须先改 §4.1.1 再实现 |

**"未写死 `mock-mall`"目前证到什么程度（本轮结论，勿越读）**：全仓**没有** `if (adapterType == "REFERENCE_MALL_HTTP")` 一类分支；换商城＝新增实现 ＋ 在 `config/GeneratorBeans` 的登记处加一行（本轮回读确认只有两行登记），运行期未注册类型会 400 并列出已注册类型。**但**字段词表／金额标度／信封／状态词表／路由仍全部是参考商城字面量，两个现有夹具（`fixture/FakeMallServer` 与 `MallTargetAdapterOperationsTest.FakeMall`）逐字复刻同一套词表——**它们不能充当"不同字段夹具"**。这正是 M1-9 ② 要做的事。

## 5. §4.1.1 的【追认】条款 ↔ 代码（每条都能指到 `path`）

| §4.1.1 条款 | 代码依据 |
|---|---|
| 无状态＋目标作首参＋唯一登记处 | `adapter/MallTargetAdapter.java`（`TargetConfig` 作首参的 8 个方法）、`adapter/MallTargetAdapterRegistry.java`（未注册/重复注册响亮失败）、`config/GeneratorBeans.java`（登记处，两行） |
| 做不到就抛 `MallOperationException` | `MallTargetAdapter.unsupported(...)`；默认方法体统一抛 |
| 凭据纪律（只写引用名） | `adapter/ReferenceMallHttpAdapter` 的 `requireCredential`/`resolveToken`（异常只含引用名）；`config/GeneratorBeans` 的解析顺序（系统属性→环境变量） |
| 能力三态＋声明/实测分离 | `TargetCapabilities`、`CapabilityVerdict`、`ReferenceMallHttpAdapter.capabilities(...)`（纯本地推导）、`MallApiGenerationEngine.preflight(...)`（以声明为准） |
| 只经公开接口 | `ReferenceMallHttpAdapter` 的公开路由集与 `PUBLIC_ROUTES`；`GeneratorBoundarySourcePolicyTest` 的源码级负向守卫（禁止商城包/库表/`mall.*`/平台端口） |
| 运行期单一 `TargetConfig` | `service/GenerationRunService`（每运行解析一次并贯穿探测/预检/派发）、`service/TargetProbeService`（探测时另建一份，仅用于 `test`） |
| `generator_target` 五列不进 `TargetConfig` | `meta/GeneratorMetaStore` 的目标列映射 vs `TargetConfig` 五字段；能力判定只走 `capabilities(config)` |
| `EventSink` 两点 | `contract/EventSink.java`（`extends AutoCloseable`、`ArtifactManifest closeAndBuildManifest()`） |
| DTO 最小字段表 | `adapter/` 下 15 个类型＋2 个枚举（构造校验：`ProductQuery` 的 `limit∈[1,500]`、`ExternalProduct.price≥0`、`RefundCommand.amount>0`、`CancelCommand.reason` 必填等） |

## 6. §4.1.1 的【新增要求】（**当前实现尚未满足**，排在 M1-9 ②）

1. **路由所有权**：流水/报告的 `http_method`/`route` 必须取自适配器 `operationRoutes(config)`；引擎/报告/页面不得内置任何商城路由字面量；未声明即写占位说明。**现状**：`MallDispatchPlan` 与 `MallApiGenerationEngine` 预检仍写参考商城字面量 ⇒ 换商城会在流水里谎报路由。
2. **跨目标状态不得进适配器实例**（目录缓存、外部 ID 映射归运行期/仓储）。
3. **新增商城＝新增 Adapter ＋ 登记处一行**，不得改场景引擎/事件契约/表结构/页面；引擎里不得出现"哪家商城"的业务分支。
4. **金额与精度**：不同商城标度不同（元/分）时由适配器换算到与参考商城同口径，换算系数是显式常量且有单测；金额只用 `BigDecimal`；对账用整数分。
5. **契约纪律**：`TargetRoute`/`operationRoutes` 及最小字段表之外的新增字段，必须先改 §4.1.1 再实现；`contract-specs` 的 `x-unspecified` 待 M1-5 收口同步。

> **口径纪律**：写进契约 ≠ 已交付。§6 的 5 条在 M1-9 ② 落地并跑出 E2 之前，任何报告都不得声称"路由已由适配器所有"。

## 7. 载体裁决与漂移事实

**F-24（新发现，反熵类）：唯一执行入口落后于现实约 30 小时。**
- 事实：`docs/项目实施进度与任务看板 V2.2.md`（自述"V2.2 §11 指定的**唯一**任务执行入口"）的 M1-4 行停在 `IN_PROGRESS`；而 2026-09-11 20:00 起 M1-4 泳道的证据段（S4b、补库存、U1/U3、D12、D10）全部写在 `docs/项目实施进度与任务看板.md` 里。
- 根因（只读取证）：未编号看板**自己的头部**写着「本文件是 **V2.1 §11** 末句指定的唯一任务执行入口」⇒ 它是 **V2.1 期**看板；`docs/README.md` 也把它列为"V2.2 更新前历史快照"。M1-4 的 S4b 轮的开工时点（20:00）早于 V2.2 文档集成型（20:28–20:39），此后沿用旧文件未纠。
- 处置：**不搬运**证据正文（避免同一事实两份所有者），只做"状态单元格 ＋ 证据指针"；历史文件顶部加冻结指针。
- 残差：两份看板的**创建先后与是否曾有口头裁决**未取证（§11）。

**D-048（契约载体）**：指导书**不就地改**。依 §2.1.1「已编号文稿不得覆盖…生成一份结构完整的新版本」⇒ 迭代出 `docs/项目完整实施指导书 V2.3.md`；V2.2 冷备 ＋ 哈希留证，正文逐字未改。**理由**：就地改会让"V2.2"这一版本身份失去意义，也会让所有引用 V2.2 的验收记录失去可比对对象。**可推翻**：若用户要求把 §4.1.1 直接并入 V2.2，回滚步骤见冷备 `MANIFEST.md`。

**D-049（看板唯一入口）**：`docs/项目实施进度与任务看板 V2.2.md` 是唯一执行入口；`docs/项目实施进度与任务看板.md` 冻结只读。看板版本号**不升**（未改变需求范围，符合 §2.1.1"范围改变时建立下一版本看板"）。

**D-050（新事实/决策载体）**：新事实与决策写**独立 ADR/验收文件**＋看板 §5.1/§6，不再扩写 `docs/开发过程事实与决策记录.md`（依 V2.2 看板头部与 `docs/README.md` 规则 7）。此前同日写入该文件的 F-21–F-23/D-046/D-047 **保留原地、不追溯迁移**（历史文本不改写）。

## 8. 变更清单（可逐条核对）

| 文件 | 改动 |
|---|---|
| `docs/项目完整实施指导书 V2.3.md` | 新建；＝ V2.2 ＋ 头部 7 处自指（标题/版本日期/实施基线/配套看板/历史文档/stage 自述/权威顺序自指）＋ §4.1.1（128 行插入）；`git diff --no-index` 全量留档 |
| `docs/README.md` | 3 处：权威入口 →V2.3；看板条目加漂移说明；历史清单补 V2.2 指导书 |
| `docs/项目实施进度与任务看板 V2.2.md` | 5 处：权威指针 →V2.3；M1-9 行状态与证据；M1-4 状态勘误（指针化）；§5.1 ＋3 行裁决；§6 ＋1 行变更日志 |
| `docs/项目实施进度与任务看板.md` | 1 处：顶部 4 行冻结指针（此后只读） |
| `docs/backups/m1-9-contract-first-20260912/` | V2.2 冷备 ＋ `MANIFEST.md` |

## 9. 反熵声明（Anti-Entropy Declaration）

```
删除类：contract-carrying（权威文稿的所有者收敛）——本轮零删除、零破坏性操作
旧路径：① 在"未编号（V2.1 期）看板"里写 M1-4 状态与证据；② 就地修改已编号指导书
新所有者：① 看板 V2.2 ＝唯一状态/证据入口；② 指导书 V2.3 §4.1.1 ＝该区域的唯一契约
保留行为：两份历史文件逐字保留（V2.2 指导书哈希留证、旧看板只加指针）；所有既有验收记录仍可定位
退役行为：向旧看板追加状态；就地改编号指导书（此后一律走 V2.x 迭代）
外部边界：无（纯仓库内文稿；无跨仓库消费者）
真值风险：无数据库/数据文件改动；未启停服务；未触碰 8090/8091/8092
需用户确认：无破坏性动作故不触发；但 D-048（换版本 vs 就地改）与 D-049（冻结旧看板）属**可推翻裁决**，已在此登记
```

**验证计划**：① 主路径——M1-9 ② 实现时以 §4.1.1 为唯一契约（`operationRoutes` 等【新增要求】必须落地）；② 残留引用检查——全仓 grep 确认不再有"把状态写进旧看板"的指引（旧看板内除指针外无新增）；③ 负向检查——旧看板冻结后除指针外零改动；④ 边界检查——本轮 diff 内零 Java/SQL/配置改动。

## 10. 不得声称（既有结论的边界）

- M1-9 **未完成**：本轮只完成必办项 ①，第二适配器与"不同 source profile"（依赖 P5-01）**均未开工**。
- §4.1.1 的【追认】是**只读取证**，不是运行证据；本轮无 E1/E2/E3。
- 不得说"生成器已支持任意商城"：字段/单位/信封/状态词/路由仍写死在参考商城实现里（第 4 节末段）。
- 不得说"看板已完全同步"：旧看板的 §3.5/§3.9–§3.12 仍是 M1-4 的详细证据所在（只读），V2.2 看板只做指针。
- M1-4 仍是 `DONE_LIMITED`（页面分支未做、`behavior`/`reset_state` 仍 `UNDETERMINED`），本轮不改变其状态。

## 11. 未取证清单

1. **第二家真实商城的行为**：环境里只有 `mall-simulator`（8090）；本轮未联机、未启停服务 ⇒ 未取证。
2. **两份看板的创建先后／是否曾有口头裁决指定旧看板**：只读到"两份文件各自自述"与 `docs/README.md` 的清单 ⇒ 未取证（处置按 D-049，可推翻）。
3. **`OperationJournalEntry` 全文是否已能承载"适配器自定义路由"**：只从调用点推断字段（`operation`/`method`/`route`/`realHttp`/`localAccounting`）⇒ 未逐行读，M1-9 ② 前须补。
4. **`JsonlEventSink`/`ArtifactManifest` 与 §4.1／schema 的逐字段一致性**（S3 范畴）⇒ 本轮未读，未取证。
5. **8090 对 `OPTIONS` 的真实应答**（属 E3）⇒ 本轮未测。
6. **前端/页面是否写死商城名**（§5 页面要求）⇒ 本轮未扫前端目录。
7. **`generator_target` 现有行内容**：本轮禁 SQL ⇒ 未查（引用验收 README 的既有记录，不是本轮实测）。
8. **两个现有夹具逐字复刻参考商城词表**：本轮的语句来自只读代码取证（子代理与总控各一次）；**未**逐行复核这两个文件的全部字面量。

## 12. 下一步（M1-9 ②，E2 可跑）

按 §4.1.1 实现**第二适配器**（新增文件：适配器 ＋ 独立夹具 `SecondMallFakeServer` ＋ 用例；登记处加一行），验收断言草案：

| # | 断言 | 支撑的条款 |
|---|---|---|
| T1 | 字段词表不同（如 `sku`/`unit_price_cents`/`state`）而规范事件字段不变 | 硬约束 7 |
| T2 | 金额标度不同（分）换算精确、无浮点尾差 | 硬约束 10 |
| T3 | 信封不同（如 `{success,result,errMsg}`）仍能解析/响亮失败 | 硬约束 3 |
| T4 | 状态词表不同（如 `PAID_OK`/`CANCELED`）原样透出，引擎不按字面量判定 | 硬约束 7 |
| T5 | 路由不同（如 `/open/v2/...`）且流水记录的是**适配器自报**路由 | 硬约束 6 |
| T6 | 双目标并存：一次运行一份 `TargetConfig`，流水不串台 | 硬约束 9 |
| T7 | 能力差异（缺 `admin`/`refund`）⇒ 白名单缩小并记缺口，不伪造成功 | 硬约束 3/5 |
| T8 | 变更面守卫：新增一家商城只动"1 个新适配器文件 ＋ 登记处 1 行" | 硬约束 7 |

同一轮或紧随其后：确认 `OperationJournalEntry` 的承载能力（§11-3）；`contract-specs` 的 `x-unspecified` 同步排在 M1-5 收口时做。

---

**附带发现（登记为残差，不在本轮修改范围）**：`docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log` 是**被 8091 进程持续写入**的已跟踪文件（本轮 `git status` 显示它有 34 行新增）。它属于运行期日志，不应随代码/文稿提交；本轮提交**未**包含该文件，建议后续裁决"证据目录内的运行期日志是否改为不跟踪"。
