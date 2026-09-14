# V25-R01 覆盖矩阵（首版）

- 任务：`V25-R01 修复覆盖矩阵`（看板 V2.5 §3 L61；看板 §4 L104 登记为 IN_PROGRESS / Owner=审计泳道）
- 覆盖对象：`docs/项目完整实施指导书 V2.5.md`（769 行 / 74,100 B / sha256 `33A2C5B3234B1C724C6A9C9C0492B2480D443FA392D2DB72C3F4902805E55F6F`）§1–§14 全文
- 执行入口：`docs/项目实施进度与任务看板 V2.5.md`（122 行 / 16,593 B / sha256 `9E9CC76571A57E74647D8342F0EFA6168BD12B4977F53EDDD477B7D2702373AE`）
- 采集时刻：2026-09-14 12:08–12:12 +08:00
- 代码基线：`HEAD = 6062434792cf086600b67b38b562737ee6f65eb0`（2026-09-14 12:06:44 +0800，commit msg = `V2.5 看板开工登记`）+ 38 行未提交工作树。`git status --porcelain` 行数 = 38；worktree = 3
- 口径与方法、四级状态、证据强度、本次未覆盖范围：见同目录 `README.md`
- 原始取证（只读）：`raw/01-identity-git.txt`、`raw/02-acceptance-inventory.txt`、`raw/03-ruleseverity-version-boundary.txt`、`raw/04-v25-object-presence.txt`、`raw/05-269-baseline.txt`

## 判定纪律（本矩阵自用，来自 V2.5 与看板原文）

1. **不出现「扫到类名/表名 / HTTP 200 / 单次 SUCCESS 即算覆盖」的判定**。每一行的「断言/测试」列必须能指出**可证伪的断言**；指不出就写「无断言」，状态落 `未取证`。
2. **不计算任何完成率、不产出「X 完成／Y 部分／Z 未做」汇总**（V2.5 L15：本版不重新宣称 132/90/42 为当前统计）。
3. **不修订 V2.5 自身**。发现 V2.5 与实测冲突时写「冲突」并给两侧原值，由总控裁决。
4. **§2 历史成果一律引用看板 §2 的限定语，不得升级为完整验收**（看板 L17–L24 六行均为 `DONE_LIMITED`）。
5. **V25-R01 不沿用旧覆盖表结论**（V2.5 L732 原文：「禁止从旧覆盖表的 DONE 直接复制结论」）。本矩阵所有行均按 `代码 file:line` + 断言 + 运行证据重新推导；旧 229 行表 `docs/acceptance/guideline-v24-coverage-20260912/COVERAGE.md` 仅作历史件引用，不作依据。
6. 本矩阵**粒度**：以 V2.5 的「一条可判定要求」为行。V2.5 自身编号的子项（§4.1.1.2 十一条、§5.6 D1–D14、§7.3 十二项、§9.2 T0–T4、§11 九条、§13 AIW-001～020、§13.3 六条）**逐条点名列在行内，不抽样**。
7. **原 269 条**为 `docs/v2-completeness-audit.md` 在 commit `34f37a8` 的冻结快照（`docs/README.md` L48）。本版**不逐条重审**（那是 V25-R02，V2.5 L714）；见文末「§269 交叉映射」——**全部 269 条现状 = 未映射·待归并，不得自动关闭**。

## 列定义

| 列 | 含义 |
|---|---|
| `ID` | 本矩阵行号，`§章节-序号` |
| `V2.5` | V2.5 行号（必要时含原文摘要） |
| `代码` | `file:line`；无实现写「无」 |
| `断言/测试` | 可证伪的断言名或 `类#方法`；无断言写「无断言」 |
| `运行证据` | 只引用**已存在**的证据文件路径；无则写「未取证」 |
| `E` | 证据级别 E1 编译 / E2 模块自动化测试 / E3 本地真实链 / E4 集群 / E5 员工可用页面 |
| `状态` | V2.5 §2.2 L65：`TODO/READY/IN_PROGRESS/BLOCKED/REVIEW/DONE_LIMITED/DONE/REGRESSED/DEFERRED/N/A`，外加本矩阵保留标记 `未取证` |
| `提交` | 代码提交独立登记：`已提交` / `未提交(dirty)` / `无代码` |
| `测试` | 测试通过独立登记：`通过` / `失败` / `未跑` / `不适用` |
| `限定验收` | 限定语下的验收：`无` / `有(<限定语>)` / `未取证` |
| `完整验收` | 无条件验收：`无` / `未取证` |
| `缺口或替代条款` | 缺什么；或替代/降级条款出处 |

> 「提交／测试／限定验收／完整验收」四列相互独立，**前者不得代替后者**（V2.5 L731「四项状态分别登记…不得以已推送文件数量推导完成度」）。

---

## §1 目标、范围与论文边界（V2.5 L13–42）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §1-1 | L13–33 项目目标：三程序（分析平台 / 模拟商城 / 合成数据生成器）+ Spark 数仓 + 智能分析 | 三程序顶层目录均存在（`analytics-server` / `mall-simulator` / `synthetic-data-generator`）；见 §3-1 行 | 无直接断言 | `raw/02-acceptance-inventory.txt`（`docs/acceptance` 51 目录） | — | DONE_LIMITED | 已提交 | 不适用 | 有（看板 §2 L18：三程序拆分基础） | 未取证 | 三程序"基础"已拆分；三程序**产品级**独立可用属看板 §3 任务范围，本行不升级 |
| §1-2 | L34–42 §1.1 论文与答辩正文 | 无（V2.5 L716「不得由 Agent 发明」） | 无断言 | 未取证 | — | DEFERRED | 无代码 | 不适用 | 无 | 未取证 | 看板 §3 L67 `V25-X04` = DEFERRED；依赖「项目完成 + 学校要求」；论文素材缺口见 `README.md` §「未覆盖范围」 |
| §1-3 | L15 不沿用旧版 132/90/42 统计 | 本矩阵 | 本矩阵**零完成率数字**（可全文检索验证） | `MATRIX.md` 本文件 | — | DONE_LIMITED | 无代码 | 不适用 | 有（仅指导书口径） | 未取证 | 旧 269 条计数仍冻结在 `docs/v2-completeness-audit.md` L488，属历史快照，非当前统计 |

## §2 权威顺序、状态词表与完成定义（V2.5 L43–74）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §2-1 | L45–53 §2.1 五层权威顺序（契约 > 本指导书 > 看板已确认状态 > 专项设计 > 旧版） | 无代码 | 无断言（治理条款） | `docs/README.md` L7–L12（新版切换说明） | — | DONE_LIMITED | 无代码 | 不适用 | 有（文档层） | 未取证 | **冲突**：V2.5 L4 与看板 L4 均写「当前基线：HEAD 8983616」，实测 HEAD = `6062434` ⇒ 基线指针漂移一个提交，需总控裁决是否改文（本矩阵不自行修文） |
| §2-2 | L55–61 §2.1.1 既有历史裁决降级为历史证据 | `docs/项目实施进度与任务看板.md`、`docs/开发过程事实与决策记录.md` | 无断言 | `raw/01-identity-git.txt`（未提交清单不含上述两文件 ⇒ 未被改写） | — | DONE_LIMITED | 已提交 | 不适用 | 有 | 未取证 | 无 |
| §2-3 | L63–65 §2.2 状态词表 10 个取值 + 「代码、表、接口或页面单独存在不等于完成」 | 本矩阵 | 本矩阵状态列取值全在词表内 | `MATRIX.md` 本文件 | — | DONE_LIMITED | 无代码 | 不适用 | 有 | 未取证 | 无 |
| §2-4 | L67–73 §2.3 完成定义 | 本矩阵「提交/测试/限定验收/完整验收」四列 | 本矩阵每行四列必须分别登记 | `MATRIX.md` 本文件 | — | DONE_LIMITED | 无代码 | 不适用 | 有 | 未取证 | 无 |

## §3 三程序边界、数据流与输入契约（V2.5 L75–121）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §3-1 | L77–85 §3.1 三程序独立（端口 8091/8090/8092、独立库、互不依赖） | 三顶层目录存在；`mall-frontend/src/views/*.vue` 4 个；`web/` 为分析前端 | 无当前断言 | `raw/02-acceptance-inventory.txt`；`raw/04-v25-object-presence.txt` H 段（`analysis-web/src/pages` **不存在**，真实分析前端为 `web/`） | — | DONE_LIMITED | 已提交 | 不适用 | 有（看板 §2 L18「三程序拆分基础」） | 未取证 | **当前 8090/8091/8092 均无监听**（看板 §5 L120 只读实测）⇒ 三程序"独立启停"的**当前时点**可运行性未取证；历史证据仅对当时有效 |
| §3-2 | L87–99 §3.2 数据流：源→Landing→ODS→DWD→DWS→ADS→MySQL 指标库→页面 | `LocalFileIngestor.java`、`OdsLoadSql.scala`、`DwdSql.scala`、`DwsSql.scala`、`AdsSql.scala`、`MySqlMetricStore.java` | 见 §5/§7 各行 | `docs/acceptance/m3-step8-parity-20260912/`（看板 §2 L20，限定为历史 run 47 链） | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：历史 run 47 链，**不是当前 WSL 验收**） | 未取证 | 采「本地文件」路径的当前时点链路未跑（`README.md` 声明的只读约束：不跑真实链路） |
| §3-3 | L101–111 §3.3 输入契约 A：`MALL_API`（商城接口取数） | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | V2.5 §3.3 A 的落地任务在看板 §3 中**无独立任务行**，由 `V25-P01`（数据源页面）与 §5.3 连接器间接承接 ⇒ **未映射·待归并** |
| §3-4 | L109–111 §3.3 输入契约 B：`CANONICAL_EVENT_FILE`（规范事件文件，异构 B） | 无 | 无断言 | `docs/acceptance/p5-heterogeneous-source-20260912/IMPL-REPORT.md` | E3 | DONE_LIMITED | 无代码 | 未跑 | 有（**限定**：看板 §2 L22「已发现 92/180 不符，不能算 P5 成功」） | 未取证 | 异构 B 反例必须保留、不得改期望洗成成功（V2.5 L466）；闭环任务 = 看板 §3 L51 `V25-E02`（TODO） |
| §3-5 | L113–120 §3.4 六条独立验收 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 六条独立验收的**当前时点**全部未取证；其中"页面/员工"腿依赖 `V25-E03`（TODO） |

## §4 平台执行签名与硬约束（V2.5 L122–296）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §4-1 | L124–139 §4.1/§4.1.1 唯一执行签名（每阶段必须经统一执行器） | `SparkStageExecutor.java`、`PipelineService.java:114-117`、`PlatformBeans.java:66-76` | 历史断言（旧 269 条 §15.2 行） | `docs/acceptance/m3-step8-parity-20260912/` | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（限定：历史链） | 未取证 | 当前时点未重跑 |
| §4-2 | L143–191 §4.1.1.1 `code` 与 `TargetConfig` 表（11 行） | `JobCommandBuilder.java`、`RuntimeProfile*.java`、`spark-jobs/src/main/scala/.../JobRegistry.scala` | 见 §6-3/§6-4 行 | `raw/04-v25-object-presence.txt` | E2 | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | `SINGLE_NODE` 缺省 master 缺陷见 §6-5（V25-W05） |
| §4-3 | L207–219 §4.1.1.2 十一条硬约束：**1** 无状态、**2** 跨目标状态、**3** 响亮失败 | `PipelineService.java`（1125 行，V2.5 L726 记集中风险）、`SparkStageExecutor.java:170-198` | 历史断言 | `docs/acceptance/m3-step8-parity-20260912/` | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | 「职责集中在 PipelineService」为 V2.5 自认反模式，未拆 |
| §4-4 | L207–219 硬约束：**4** 凭据、**5** 能力三态、**6** 路由所有权 | `CredentialService.java`；能力三态无独立类 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 「能力三态」缺可证伪载体；`V25-U01`（TODO）承接凭据 |
| §4-5 | L207–219 硬约束：**7** 新增商城、**8** 只经公开接口 | 三程序边界测试 | 历史断言（`web/tests/boundary.test.js`） | 未取证（当前时点未跑前端测试） | — | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | — |
| §4-6 | L207–219 硬约束：**9** 单一配置、**10** 金额精度、**11** 契约纪律 | `EventContract.java:19`、`warehouse/ddl/*.sql` | 历史断言（十进制字符串正则 / `DECIMAL(18,2)`） | 未取证（当前时点未跑） | — | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | §5.6 D7 追加「BigDecimal 从原始文本解析、不经 double、超精度 UNNECESSARY 拒绝」——**新要求，未实现** |
| §4-7 | L221–259 §4.1.1.3 DTO 表 | `runtime/`、`pipeline/` DTO 类 | 无独立断言 | 未取证 | E1 | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | DTO 字段与 §5.7 `NormalizeResult` 不重叠，见 §5-14 |
| §4-8 | L261–271 §4.2 五表 + 状态机 | `synthetic-data-generator/src/main/resources/db/generator/V1__generator_meta.sql:12,30,50,74,92`；`GeneratorMetaStore.java:100,105,111,116,142,148,169,246,250,258` | 见第三版更正 A：库内实测行数 422/430/184/551/947 | `raw/10-crossdb-census-and-74-invariants.txt` R3/R5/R6 | — | DONE_LIMITED | 已提交 | 未跑 | 有（限定） | 未取证 | **原行文字保留**：首版记为「六表 + 未查库」。**12:4x–13:0x 跨库实测更正为：五个对象、存在且已投产**（详见 §「第三版更正与新增」更正 A）。行数只证「在用」，不证「符合 §4.2 字段规格」 |
| §4-9 | L273–282 §4.3 场景算法八条 | `AdsSql.scala`、`DwsSql.scala`、`AnomalyDetector.scala` | 见 §7-7～§7-10 | 未取证 | — | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 八条中「异常检测接生产链」仍缺（`V25-D03` TODO） |
| §4-10 | L284–291 §4.4 API | `PipelineController.java`、`AnalysisController.java`、`MetricController.java`、`AiController.java`、`DecisionController.java` | 无当前断言 | 未取证 | — | TODO | 已提交 | 未跑 | 未取证 | 未取证 | **当前无在跑服务**（看板 §5 L120）⇒ 任何依赖 HTTP 200 的旧结论在本时点**失效**；本矩阵不使用 HTTP 200 作为覆盖判据 |
| §4-11 | L293–295 §4.5 页面 | `web/src/views/`（Overview/Behavior/Sales/Products/Rfm/Decisions/AiAssistant/Ops/Pipeline/Login） | 无当前断言 | 未取证 | E5 | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 「数据源」页缺失（旧 269 条 §3.4③ 已登记）；`V25-P01`（TODO）承接 |

## §5 需求、契约、映射与完成定义（V2.5 L297–386）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §5-1 | L299–303 §5.1 需求不丢失：269 条与旧 M1/P1–P5 逐条登记，不可静默丢弃 | 无 | 无断言 | `docs/v2-completeness-audit.md` L482–L488（冻结于 `34f37a8`） | — | IN_PROGRESS | 无代码 | 不适用 | 有（口径已立） | 未取证 | **269 条全部为「未映射·待归并」**（见文末 §269 交叉映射）；`V25-R02`（TODO）执行逐条重审 |
| §5-2 | L305–314 §5.2 元数据结构六表 | `raw/04-v25-object-presence.txt` A 段实测：`source_registry` **存在**（`V16__source_registry.sql:20`）；`source_connector` / `schema_mapping` / `source_manifest` / `quality_rule_definition` / `source_profile` / `mapping_version` 均 **0 命中** | 无断言（表不存在 ⇒ 无可证伪断言） | `raw/04-v25-object-presence.txt` L4–L16 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 六表中 **5 张缺**（`source_connector`/`schema_mapping`/`source_manifest`/`quality_rule_definition`/`source_profile`）。承接：`V25-C01`（READY）、`V25-I01`（TODO）、`V25-D04`（TODO） |
| §5-3 | L316–329 §5.3 连接器接口 | 实测：`SourceConnector` / `LocalFileConnector` / `FlumeLandingConnector` **0 命中**；`LandingStorage` 接口 **已存在**（`analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/runtime/storage/LandingStorage.java:12`）、`LocalLandingStorage.java:24`、`HdfsLandingStorage.java:23` | 无断言（无连接器接口 ⇒ 无可证伪断言） | `raw/04-v25-object-presence.txt` B 段 L25–L46 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | **对照更正**：`LandingStorage`/`HdfsLandingStorage` **已存在**（旧 269 条 §11 行同此），所以 `V25-I01`「实现 LandingStorage/HDFS 接口」应理解为**补齐 §5.3 连接器接口与能力声明**，不是从零新建；该差异需总控确认（本矩阵不改任务文字） |
| §5-4 | L331–337 §5.4 标准化与映射 | `EventNormalizer` **0 命中**；`SourceProfileLoader` / `ProfileValidator` / `MappingCompiler` / `NormalizeResult` **均 0 命中** | 无断言 | `raw/04-v25-object-presence.txt` B 段 L41–L46 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 四个独立职责**全部无载体**；承接 `P3-02-a`（TODO，依赖 C01/C02） |
| §5-5 | L339–341 §5.5 数据源页面 | 无（旧 269 条 §3.4③：`web/src/views/` 无「数据源」页） | 无断言 | 未取证 | E5 | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-P01`（TODO） |
| §5-6 | L345 §5.6 前言：必须**先执行 V25-C01** 冻结载体，才允许 P3-02 写转换代码；不得就地重解释 `profile.v1`、不自动升级 `contract-specs/VERSION` | `contract-specs/`；未见新 profile schema major | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 看板 §3 L84 `V25-C01` = **READY**（非 IN_PROGRESS）⇒ 「先行」尚未开工；看板 §3 L86–L88 `P3-02-a/b/c` = TODO 且依赖 C01/C02 ⇒ 顺序纪律当前未被违反也未被验证 |
| §5-7 | L349 D1 canonical owner：`docs/contracts/event-contract.md` 为语义权威，`canonical-event.v1.schema.json` 为机器投影；先消除已登记偏差；一份派生字典供 Java/Scala/测试共用 | 契约文件存在；派生字典无独立载体（历史同一性由 `CanonicalEventSchemaParityTest` 承担） | 历史断言 `CanonicalEventSchemaParityTest`（旧 269 条 §3.5 行） | 未取证（当前时点未跑测试） | E2 | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 「派生字典供三方共用」当前为测试对账而非单一机器可读派生件 ⇒ 缺口 |
| §5-8 | L350 D2 字段映射与冲突：`sourcePath→targetPath+type+transforms`；区分 envelope/payload；同事件组同 `targetPath` 双写 ⇒ `PROFILE_INVALID`；禁模糊搜索 | 无 | **无断言**（`PROFILE_INVALID` 实测 0 命中，见 `raw/04` E 段——命中项均为 `SOURCE_PROFILE_INVALID`，不同码） | `raw/04-v25-object-presence.txt` E 段 L57–L62 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-C01`（READY）+ `P3-02-a`（TODO） |
| §5-9 | L351 D3 `@keep`：只保留 payload 源扩展字段、保持源叶键原名、不去 `ext_`、不补单位；同名冲突拒绝；源完整原文另行永久保真 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §5-6 |
| §5-10 | L352 D4 时间：`timePolicy.field` 显式源路径 + 必须有 `event_time` 映射；`formats` 有序首成功者生效并记录 `formatId`；epoch 明确 SECONDS/MILLIS 不按位数猜；无时区用 profile IANA zone；DST 重叠/空洞不猜、隔离并记原因；不改 D-112 首匹配规则 | `JobArgs.scala:10`（`yyyyMMdd`）、`AdsRows.java:20-27`；`TIME_AMBIGUOUS_LOCAL` 0 命中 | 无断言 | `raw/04-v25-object-presence.txt` E 段 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | `TIME_AMBIGUOUS_LOCAL` / `formatId` 无实现 |
| §5-11 | L353 D5 缺失/null/空串：必填缺失→`EMPTY_FIELD`+`MISSING`；必填 null→`EMPTY_FIELD`+`NULL`；trim 后空串→`EMPTY_FIELD`+`BLANK`；可选缺失默认不输出并计 missing；可选 null 仅在目标 Schema 允许时保留否则 `OMIT`；可选空串不得自动变 0/null；不得有绕过 required 的开关 | `EMPTY_FIELD` 存量存在；D5 细分 `reason_detail` 无实现 | 无断言 | 未取证 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 三态细分（MISSING/NULL/BLANK）无载体 |
| §5-12 | L354 D6 原因码：保留 7 个存量码不改意义；新增 `PROFILE_INVALID`/`PROFILE_VERSION`/`JSON_PARSE_ERROR`/`UNKNOWN_EVENT_TYPE`/`TYPE_MISMATCH`/`ENUM_UNRESOLVED`/`TIME_AMBIGUOUS_LOCAL`；细分用 `reason_detail` 固定子码；旧计划码映射到存量码；旧已落盘码只读保留 | `raw/04` E 段实测：7 个新码**全 0 命中**（唯一 grep 命中是 `SOURCE_PROFILE_INVALID`，与之无关） | 无断言 | `raw/04-v25-object-presence.txt` E 段 L55–L62 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 7 个新主码 + `reason_detail` 子码目录**全部未落**；承接 `V25-C02`（TODO，依赖 C01） |
| §5-13 | L355 D7 金额：`type=DECIMAL`+`sourceUnit/targetUnit`；FEN/100、YUAN 恒等；禁 unit 与自由 scale 同时定倍率；BigDecimal 从 JSON **原始数字文本/字符串**解析不经 double；整数分要求整数；输出两位小数字符串；超精度用 `UNNECESSARY` 拒绝而非静默四舍五入；负值按契约拒绝；null 先走 D5 | `EventContract.java:19`（十进制字符串正则）为**存量**能力，不满足 D7 新语义（无 unit、无 UNNECESSARY、无 mapping 层） | 无断言 | 未取证 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | D7 全部子项无实现 |
| §5-14 | L356 D8 raw/accepted/id/version：raw 保存全部输入；accepted 新版本写规范事件不覆盖历史；`event_id` 来自显式源标识映射、缺失**不得随机生成**；内容哈希只作完整性校验不替代身份；`mappingVersion` 不可变 + 另存 `mappingChecksum`；同版本内容变化拒绝 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-15 | L357 D9 嵌套与数组：首版仅对象字段路径 + 固定整数下标；不支持递归 JSONPath/filter/script/wildcard；订单 items 允许 `ITEM_MAP` 保数量/顺序、不笛卡尔展开；任一必填 item 失败隔离整事件；不得丢订单项绕过缺口 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-16 | L358 D10 dry-run：默认不写正式业务表/checkpoint/ACTIVE，只产隔离报告；必须显示能力限制，不能只返回 `true` | `raw/04` C 段实测：`mappings/dry-run` / `mappings/{version}/activate` **0 命中** | 无断言 | `raw/04-v25-object-presence.txt` C 段 L48 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-C01` + `P3-02-a` |
| §5-17 | L359 D11 分期：a=契约与纯归一器+dry-run；b=采集接线/manifest/重放；c=两源分层与指标；**a 通过不得把 P3-02/P5 整体标 DONE** | 无 | 无断言 | 看板 §3 L86–L88（a/b/c 三行独立） | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 分期纪律已在看板体现（三行独立任务） |
| §5-18 | L360 D12 原因码 owner：总控拥有中立原因码契约与迁移号；数据层 owner 同步 `warehouse/ddl/01-dwd.sql` 注释与 Java/Scala 派生守卫，不能两边各定义；未知原因码拒绝发布规则版本 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-C02`（TODO，依赖 C01） |
| §5-19 | L361 D13 基线测试归属：门禁注释问题归 `V25-T01`；历史 manifest 固定夹具归 `V25-T02`；**禁止清理真实 `landing/manifests/40–43.json` 来修测试** | `WarehouseNameLiteralGateTest.java:49,51,83-92`（无注释剥离）；`IngestionManifestSourceSchemaTest.java:157-168`（读实时目录） | 两个门禁断言见 §9-6/§9-7 行 | `raw/04-v25-object-presence.txt` F 段（40–43.json **均存在**，mtime 2026-09-12） | E2 | IN_PROGRESS | 未提交(dirty) | **失败**（`local-readiness-20260914.md` L13、L18 记 2 项失败） | 无 | 未取证 | 看板 §4 L73/L74 登记两任务 IN_PROGRESS，阻塞于 `platform-common` 主代码当前不可编译（见 §7-11） |
| §5-20 | L362 D14 顺序：本节裁决可先行；业务接线必须等 IT 安全与隔离环境就绪 + F-88 当前门验收；纯函数实现可在冻结契约后进行，不运行正式库写入 | 无 | 无断言 | 看板 §3 L86–L88 依赖列（`P3-02-a` dep=C01/C02） | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 顺序纪律**当前未被违反**（尚有未完成前置） |
| §5-21 | L364 枚举三态：未登记源词→`BAD_ENUM` 隔离；登记为 null = 已观测但语义未确认→记 `ENUM_UNRESOLVED` 画像警告、不冒造业务值；关键枚举未确认不得激活；可选且目标允许 null 可带警告接受；`source_system` 由 `source_registry` 解析不可被源指定；legacy `mock-mall` 限定 profile/version 不全局放宽 | `ENUM_UNRESOLVED` 0 命中（`raw/04` E 段）；`source_registry` 表存在（`V16__source_registry.sql:20`） | 无断言 | `raw/04-v25-object-presence.txt` A/E 段 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 三态第一态（`BAD_ENUM`）存量存在；第三态 `ENUM_UNRESOLVED` 无载体 |
| §5-22 | L366 ID 保原始字符串（不去 U/P 前缀、不丢前导零）；需要代理键的 DWD/DIM 只调用已冻结 `SurrogateKey`，不得在归一器重造 `HASH64`；`source_id` 来自当前冻结源配置；跨源相同 `event_id`/订单号/用户号/商品号必须独立 | `SurrogateKey.scala`（存量）；归一器不存在 | 历史断言（旧 269 条 §12 代理键行） | `docs/acceptance/p2-03-surrogate-key-20260912/` | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（限定：历史） | 未取证 | 「跨源相同 ID 独立」的验收 = 看板 §3 L51 `V25-E02`（TODO）⇒ 当前未取证 |
| §5-23 | L370 §5.7 新增源映射版本逻辑记录（9 字段 + 唯一键 `(source_id,mapping_version)` + 激活版本不可变）；物理表用 §5.2 `schema_mapping`，迁移号由总控分配；`source_instance` 复用 `source_registry.id`，禁建同义主表；无隔离实现前不得宣称多租户 | `schema_mapping` **0 命中** | 无断言 | `raw/04-v25-object-presence.txt` A 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 表缺；`source_instance` 纪律已遵守（未建同义表） |
| §5-24 | L372 四职责 `SourceProfileLoader → ProfileValidator → MappingCompiler → EventNormalizer` 独立；Compiler 只编译白名单路径与纯变换；先 Set 检查目标碰撞再按事件组预编译查找表，复杂度 ~O(记录数×映射字段数+订单项数×项字段数)；**不得每行重读 profile / 访问网络 / 初始化编译器** | 四类**全 0 命中** | 无断言 | `raw/04-v25-object-presence.txt` B 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 性能断言无法证伪（无实现）⇒ 状态必须为 TODO，不得写 DONE_LIMITED |
| §5-25 | L374 `NormalizeResult` 至少含 `outcome=ACCEPT/QUARANTINE`、`canonicalEvent`、`violations[]`、`warnings[]`、`rawReference`、`sourceId`、`mappingVersion`、`mappingChecksum`；`Violation` 含 `reasonCode/detailCode`、`sourcePath/targetPath`、`rawUri`、`lineNo`、`byteOffset`；日志不输出原始敏感值；无 `event_id` 时用 raw 文件+offset 追溯，不凭空补 | `NormalizeResult` **0 命中** | 无断言 | `raw/04-v25-object-presence.txt` B 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-26 | L376 批次清单版本化：分开 `rawChecksum/rawBytes` 与 `canonicalChecksum/canonicalBytes`（不用同一 checksum 混指两种文件）；`inputRecords=acceptedRecords+quarantinedRecords`；`reasonCounts` 是违规条数可大于隔离行数；系统 I/O 错误单列 `systemErrors` 并失败整批，不得伪装成业务隔离；记录 raw/accepted/quarantine URI、`sourceId/code`、`profileVersion`、`mappingVersion/checksum`、`contractVersion`、事件统计与时间范围 | `ingestion-manifest.v1.schema.json`（15 required + 4 新增可选）；`IngestionManifestSourceSchemaTest` | `IngestionManifestSourceSchemaTest#newSourceFieldsAreNotRequired:78-83`（`required` 仍 15 项）、`#fieldTypesMatchDecision:88-100` | `raw/04-v25-object-presence.txt` F 段 | E2 | TODO | 未提交(dirty) | **失败**（`:168` backfilled 断言） | 未取证 | 未取证 | §5.7 新字段（`rawChecksum`/`canonicalBytes`/`systemErrors`/`reasonCounts` 双口径）**未在 schema 中**；现有 4 个新键是 P1-05 存量（`sourceCode/sourceId/profileVersion/mappingVersion`），**不等于** §5.7 清单要求 |
| §5-27 | L379 `POST /api/v1/sources/{sourceId}/mappings/dry-run`：入参 `mappingVersion`+平台受控 `sampleRef`+`maxRecords`(1–100)；**禁止任意文件路径或外部 URL**；返回 13 个字段 | 0 命中 | 无断言 | `raw/04-v25-object-presence.txt` C 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-28 | L380 `GET /api/v1/sources/{sourceId}/mappings/dry-runs/{reportId}`：仅该源权限用户可读脱敏样本与原因；与既有 sources API 命名冲突时只增兼容路由 | 0 命中 | 无断言 | `raw/04-v25-object-presence.txt` C 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-29 | L381 `POST /api/v1/sources/{sourceId}/mappings/{version}/activate`：管理员/`data_dev`；需通过该 checksum 的 dry-run、所有关键语义确认、版本状态校验及审计；**普通员工不能激活映射** | 0 命中；`data_dev` 角色在旧 269 条 §18.4 行登记为**不存在** | 无断言 | `raw/04-v25-object-presence.txt` C 段；`docs/v2-completeness-audit.md` L396 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 依赖 `V25-U01`（TODO，含 `data_dev` 角色）⇒ 权限负例当前无法取证 |
| §5-30 | L383 `fieldCoverage` / `enumCoverage` 分母分子定义；空分母返回 `null`+`NOT_APPLICABLE` 不得补成 100%；画像声明覆盖率单列不混用分母 | 0 命中 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §5-31 | L385 a 阶段单测要求（每种字段态、金额 FEN/YUAN/数字文本、时区、冲突、数组、未知枚举/第三态、同输入确定性、恶意路径各有正反例） | 无 | **无任何对应测试类**（`raw/04` B 段四职责 0 命中） | `raw/04-v25-object-presence.txt` B 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `P3-02-a`（TODO） |
| §5-32 | L385 b 阶段验收（raw 与输入字节一致、规范输出可回溯、崩溃后从 raw 重放、checkpoint 不丢不重、不同 `mappingVersion` 不覆盖旧产物） | 无 | 无断言 | 未取证 | E3 | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `P3-02-b`（TODO） |
| §5-33 | L385 c 阶段验收（两源相同业务 ID 独立 namespace/快照/指标；仅改 profile 接 B、不改核心 Java/Scala SQL 模板） | 无 | 无断言 | `docs/acceptance/p5-heterogeneous-source-20260912/IMPL-REPORT.md`（**92/180 不符，不能算 P5 成功**） | E3 | TODO | 无代码 | 未跑 | 有（**限定**：看板 §2 L22） | 未取证 | 承接 `P3-02-c`（TODO）+ `V25-E02`（TODO） |

## §6 WSL 单节点主环境、档案与迁移（V2.5 L387–477）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §6-1 | L389–395 §6.1 主档 = `LINUX_LOCAL`+HDFS+`LOCAL_PROCESS`+`LOCAL`+thrift Metastore；Windows 快测档 = `WINDOWS_LOCAL`+`LOCAL`+隔离嵌入式 Derby | `RuntimeProfile*.java`、`RuntimeProfileServiceImpl.java` | 无断言 | `docs/acceptance/v25-w01-env-inventory-20260914/raw/10-wsl-internal-inventory.txt`（只读 WSL 枚举：Ubuntu/docker-desktop 均 WSL2 且 Stopped） | E1 | IN_PROGRESS | 已提交 | 未跑 | 有（**限定**：`local-readiness-20260914.md` L41「尚未进入 Ubuntu 核对 Java/Spark/MySQL 安装状态」） | 未取证 | WSL 内运行时版本未核对 ⇒ 「主档可用」未取证；承接 `V25-W03`（TODO） |
| §6-2 | L397–411 §6.2 档案字段与切换 | `V7__platform_runtime_profile.sql`（18 列） | 历史断言（旧 269 条 §9.1 行） | `docs/acceptance/v25-w01-env-inventory-20260914/` | E1 | DONE_LIMITED | 已提交 | 未跑 | 有（限定：历史） | 未取证 | 当前时点未重跑 |
| §6-3 | L413–425 §6.3 源码迁移（Windows → WSL Linux 路径） | 无（`JobSubmitterFactory.java:46` 仍留 `SPARK_SUBMIT_DEFAULT = "D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd"`，旧 269 条 §9.3① 行） | 无断言 | `docs/v2-completeness-audit.md` L101 | — | TODO | 未提交(dirty) | 未跑 | 未取证 | 未取证 | 硬编码 Windows 路径未清除 ⇒ 跨平台迁移未开始；承接 `V25-W02`/`W04`（均 TODO） |
| §6-4 | L427–446 §6.4 `RuntimeProfile` 正交能力矩阵（runtimeProfile × submitter × landing × metastore × metric store） | `RuntimeProfileSnapshot`、`JobCommandBuilder.java` | 无配置矩阵测试（`local-readiness-20260914.md` L33 明确要求补 `LOCAL/SINGLE_NODE/REMOTE_CLUSTER` 配置矩阵测试） | 未取证 | E2 | TODO | 已提交 | **未跑** | 未取证 | 未取证 | 承接 `V25-W05`（看板 §3 = **READY**，非 IN_PROGRESS） |
| §6-5 | L444 §6.4 已登记缺陷：`SINGLE_NODE` 选本地提交器而 `isLocal()` 不识别它 ⇒ 可能落到 yarn 默认值，必须修复并用配置矩阵测试覆盖 | `RuntimeProfileSnapshot.isLocal()` 仅识别 `LOCAL`（`local-readiness-20260914.md` L33 原文）；`JobCommandBuilder` master 缺省给 `SINGLE_NODE` 选 `yarn` | **无断言**（修复未开始） | `docs/acceptance/local-readiness-20260914.md` L33 | — | READY | 未提交(dirty) | **未跑** | 未取证 | 未取证 | 这是 V2.5 **自认的已知缺陷**，不是本矩阵新发现；承接 `V25-W05` |
| §6-6 | L448–458 §6.5 数据流 / Flume / 连接器 | `ingestion/flume/flume-taildir.conf`（模板存在，旧 269 条 §10.5 行）；`FlumeLandingConnector` **0 命中** | 无断言 | `raw/04-v25-object-presence.txt` B 段 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 平台侧 Flume 管理零实现；承接 `V25-I01`（TODO）+`I02`（TODO，依赖 I01） |
| §6-7 | L460–468 §6.6 预检与真实小链：固定 55 条黄金集，冻结内容/checksum/事件类型/分区/接受隔离期望及指标 oracle；**不能把旧 51 或 52 接受数硬套给新契约**；已有异构 B 180 条反例保留，**不改期望把失败洗成成功**；需要用户提供的**仅为**：宿主资源限制、允许的数据目录、已有库是否可复用及账号引用、是否可以重启 WSL | `tests/golden-dataset/events/golden-20260901.jsonl`（55 行，旧 269 条 §23.2 行）；WSL 链未跑 | 无当前断言 | `docs/acceptance/p5-heterogeneous-source-20260912/IMPL-REPORT.md`（92/180 不符） | E3 | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 承接 `V25-E01`（TODO）。**用户输入边界已由本行 4 项取代** V2.4 §6.5 的旧九项清单，不再重复索取 |
| §6-8 | L470–476 §6.7 延后的集群能力（远程多节点/YARN/SSH、Doris、ClickHouse/DataX/Kafka 等） | 见 §7-14/§7-15 | 无断言 | 看板 §3 L64–L67（`V25-X01`～`X04` 全 DEFERRED） | E4 | DEFERRED | 部分已提交 | 未跑 | 有（**限定**：看板 §2 L21 集群 1000 条 10 作业；**同输入对照与完整导入未闭环**） | 未取证 | **不得**把看板 §2 的集群事实升级为完整 E4；历史 10/10 不作全验收（看板 §3 L64） |

## §7 数仓分层、质量规则与指标存储（V2.5 L478–539）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §7-1 | L480–488 §7.1 DIM 与交易 DWD：`dim_user`（按 event_time 取最新合法事件）、`dim_product`（只接受建档/更新，库存事件不能建商品）、`dim_date`（预生成覆盖分析日期范围）、`dim_region`（静态版本化配置）、`dim_metric`（从 MySQL `metric_definition` 同步）、首版每日全量快照 SCD1 折中 | `DimSql.scala:13-26`（user）、`:36-61`（product）；`02-dims.sql:40-52`（`dim_date` **仅 DDL**）、`:54-61`（`dim_region` **仅 DDL**）、`:63-74`（`dim_metric` **仅 DDL**） | 历史断言（旧 269 条 §12.3 六行） | `docs/v2-completeness-audit.md` L131–L136 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：user/product 完成；`dim_date`/`dim_region`/`dim_metric` **未做**） | 未取证 | 承接 `V25-D01`（TODO）。本行**不合并**为「DIM 完成」 |
| §7-2 | L480–498 §7.1/§7.2 交易 DWD 八条（同 `order_id` 先按 `event_id` 去重；按 `event_time`/`ingest_time`/`event_id` 稳定排序；订单状态机合法性校验非法跳转进 reject；items 展开并核对 Σitems.amount；`order_paid` 去重定 `paid_at/paid_amount/final_paid_flag`；退款按 `refund_id` 取最新汇总且不得超过实付；`net_paid_amount = paid − refund`；完全退款 flag=1、部分退款单列） | `TradeDwdJob.scala:34,46-63,130-145`；`OrderTradeCompiler.scala:92-147`；`DwdSql.scala:9-45` | 历史断言（旧 269 条 §12.4-1～8 行） | `docs/v2-completeness-audit.md` L140–L147 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：-1/-5/-7/-8 完成；-2 缺 `ingest_time`/`event_id` 决胜键；-3 **未做**；-4 **未核对 Σitems.amount**；-6 无「退款 ≤ 实付」校验 ⇒ `net_paid_amount` 可为负） | 未取证 | 承接 `V25-D01`（TODO） |
| §7-3 | L490–498 §7.2 DWS/ADS：7 张 DWS 表齐备；`dws_behavior_funnel_day` 粒度 = 日×分类×渠道；`dws_trade_day` 含 GMV/退款/净销售/客单价；`dws_region_sale_day` 地区×日；count distinct 口径入字典、不混自然日/到达日 | `DwsSql.scala`（7 方法）；`DwsSql.scala:41-45`（硬编码 `-1 AS category_id, 'all' AS channel`）；`DwsSql.scala:110` | 历史断言（旧 269 条 §12.5 五行） | `docs/v2-completeness-audit.md` L148–L152 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：漏斗粒度退化为单行「日」；分类/渠道维度缺失） | 未取证 | 承接 `V25-D02`（TODO，依赖 D01） |
| §7-4 | L490–498 §7.2 ADS 10 张表齐备 + 逐表字段要求（`ads_operation_overview` 含 `snapshot_id`；`ads_behavior_funnel`；`ads_active_trend` dau/behavior_count；`ads_hot_product` 含 rank；`ads_product_conversion`；`ads_sale_trend` 补 `net_sale_amount`；`ads_category_sale`；`ads_region_sale` 补 `order_count`；`ads_user_profile_m` R/F/M/分群/活跃/偏好/生命周期/版本；`ads_data_quality_m` 六类质量字段） | `AdsSql.scala:13-15`（8 张）；`FunnelAdsJob.scala:39-46`；`warehouse/ddl/04-ads.sql:83-102`（另 2 张仅 DDL） | 历史断言（旧 269 条 §12.6 十行） | `docs/v2-completeness-audit.md` L153–L163 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：实产 **8/10**；`ads_category_sale`、`ads_region_sale` 无产出作业；`net_sale_amount` 未落 ADS；`ads_user_profile_m` 无金额列） | 未取证 | 承接 `V25-D02`（TODO）。**不得**写「ADS 完备」 |
| §7-5 | L499–501 §7.3 建立 `quality_rule_definition` 和版本/生效区间；BLOCKING/ERROR 阻断发布，WARN/INFO 持久化并展示但不阻断 | `quality_rule_definition` **0 命中**（`raw/04` A 段）；规则定义硬编码在 `QualityChecker.java:56,76,85,100` | 无断言（表不存在） | `raw/04-v25-object-presence.txt` A 段 L7；`docs/v2-completeness-audit.md` L340 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-D04`（TODO，依赖 D01/D02/Q01） |
| §7-6 | L503–515 §7.3 至少分别实现和验收 **12 条**质量规则：①Landing JSON 可解析率 ②ODS `event_id` 非空与 schema version 支持 ③DWD 行为字段/枚举合法 ④订单行金额公式误差 < 0.01 ⑤退款额 ≤ 实付额 ⑥非法状态跳转 ⑦DWS 与 DWD 金额对账 ⑧ADS GMV ≥ 净销售 ≥ 0 ⑨ADS UV ≤ PV ⑩支付用户 ≤ 浏览用户（不满足须给口径说明）⑪`ingest_time − event_time` P95 与迟到率 ⑫发布行数一致性与抽样 checksum | 实测存在的规则码集合 = `{AMOUNT_RECONCILE, ENUM_WHITELIST, EVENT_ID_UNIQUE, GMV, JSON_PARSE, REFUND, REQUIRED_FIELD_NULL_RATE, SCHEMA_VERSION}`（8 个，`QualityChecker.java` + `AdsQualityJob.scala`） | 无逐条 oracle 断言 | `docs/v2-completeness-audit.md` L342–L354 | E2 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：旧 269 条逐条判定，**不是本时点重新验收**） | 未取证 | 12 条中旧判定：②部分、④**未做**、⑤**未做**、⑥**未做**、⑦部分（仅漏斗对账）、⑧**未做**、⑨**未做**、⑩**未做**、⑪**未做**、⑫无抽样 checksum、①**未做**。承接 `V25-D04` |
| §7-7 | L516 §7.3 每条 BLOCKING 规则必须有一条「构造失败→流水线失败→不产生新 ACTIVE→旧 ACTIVE 可读」的负向验收 | `QualityChecker.java`、`DataQualityGate.java`（未提交，+183 行） | `QualityCheckerSeverityTest`（未跟踪新文件）；`DataQualityGateTest.java`（已修改） | **未取证**（本轮不跑 Maven；看板 §4 L73 记 "E2 待跑"） | E2 | IN_PROGRESS | 未提交(dirty) | **未跑** | 未取证 | 未取证 | 负向测试文件存在但**未取运行证据**（`README.md` 只读约束：不跑 Maven 测试）；承接 `V25-D04`/`Q02`（TODO） |
| §7-8 | L520 §7.3.1 `quality_rule_definition` 需含 `rule_code`、`version`、`source_scope`、`stage`、`severity`、`threshold_json`、`enabled`、`effective_from/to`、`checksum`；`(scope,rule_code,version)` 唯一；一次 run 冻结完整规则版本与指纹；结果记录该版本、实际值、阈值、passed、原始及有效严重度、兼容策略版本 | 表不存在；**代码内有等价物但非表**：`QualityRuleCatalog.java`（未跟踪新文件，`CATALOG_VERSION="qrc-1"`、`COMPAT_POLICY_VERSION="compat-v1"`、`FrozenRules` 含 `fingerprint()`、作用域常量 `LANDING/DWD/DWS/ADS/PUBLISH/METRIC_PUBLISH`）；`QualityRuleDefinition.java`（含 `version()`/`enabled()`/`sha256Hex`） | `RuleSeverityTest.java`（未跟踪新文件） | **未取证**（`platform-common` 主代码当前不可编译 ⇒ E1/E2 均无法跑；看板 §4 L82 实测 `QualityRuleDefinition.java:84` 找不到 `isKnownSeverity`） | E2 | IN_PROGRESS | 未提交(dirty) | **未跑（且当前不可编译）** | 未取证 | 未取证 | 承接 `V25-Q01`（IN_PROGRESS，看板 §4 L82 登记为总控独占热点文件） |
| §7-9 | L522 §7.3.1 执行顺序（选择作用域/版本 → 计算指标与阈值判定 → 决定严重度 → 汇总门禁）；必填/主键/金额对账失败**不能被全局规则码映射降 WARN**；原始重复事件仅在**确定性去重已证且重复率不超批准阈值**时为观察项，超过阈值阻断；`EVENT_ID_UNIQUE` 历史阈值 `0.0005` 未经新裁决不修改 | **版本化路径已接线**：`DataQualityGate.java:88` 调 `RuleSeverity.resolve(rules, r.getRuleCode(), r.getPassed())`；`DataQualityGate.java:68` 缺省仍为 `QualityRuleCatalog.DEFAULT.freeze(null)`（**代码内快照，非 DB 持久化的 run 冻结记录**）；`DataQualityGate.java:189` `UnregisteredRuleException extends RuleSeverity.UnknownRuleException` | `DataQualityGateTest.java`（已修改，未跑）；`RuleSeverityTest.java` | **未取证** | E2 | IN_PROGRESS | 未提交(dirty) | **未跑** | 未取证 | 未取证 | **版本边界缺口（实测，非推测）**：①`RuleSeverity.of(ruleCode)` 单参全局映射**仍被生产路径调用**——`PipelineService.java:712,748,808` 与 `MetricPublishValidator.java:201,219,225`，且 `RuleSeverity.java:243` `blocks(String severity)`、`:97` `of(...)` 已被标注 `@Deprecated`，javadoc 原文引 `V2.5 §7.3.1 line 520：全局 ruleCode 覆盖不是最终完成形态`；②因此**同一规则码在 DQ 门（版本化）与流水线/发布侧（全局）可能得到不同严重度** ⇒ 新增矛盾，需总控裁决统一；③`DataQualityGate.java:68` 缺省 `freeze(null)` = 无持久化 run 冻结记录 |
| §7-10 | L524 §7.3.1 历史兼容需 `(source_id, rule_code, rule_version 或明确批次范围, compatPolicyVersion)`；无版本且不在批准范围不得自动降级；保留原始结果字段不回填历史结论；接口同时展示兼容解释；**未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则** | `RuleSeverity.UnknownRuleException`（未跟踪）；`DataQualityGate.UnregisteredRuleException:189`；`QualityRuleCatalog.COMPAT_POLICY_VERSION="compat-v1"` | `RuleSeverityTest`（未跑） | **未取证** | E2 | IN_PROGRESS | 未提交(dirty) | **未跑** | 未取证 | 未取证 | 「停止发布」的**生产调用侧**是否已改用 `resolve` 并按未知码抛异常**未取证**（无运行证据）⇒ 本行不得判 DONE |
| §7-11 | L526 §7.3.1 历史 staging 存在可观察；真正当前输入/输出混快照必须阻断；付款 vs 订单总额、订单项公式、DWD↔DWS 金额是**三种独立校验**，不能用一个 `AMOUNT_RECONCILE` 测试替代全部；退款以退款业务标识取最新成功状态再累计，避免重放累计两次 | 实测只有 `AMOUNT_RECONCILE` 一个码 | 无独立三校验断言 | `docs/v2-completeness-audit.md` L350 | — | TODO | 已提交 | 未跑 | 未取证 | 未取证 | **三种独立校验当前不独立**（只有 1 个码）⇒ 缺口；承接 `V25-D01`/`D04` |
| §7-12 | L528 §7.3.1 每条 BLOCKING/ERROR 验一次：构造失败 → 本次流水线失败 → 不生成新 ACTIVE → 原 ACTIVE 数值/指纹**可读且不变**；失败必须可定位到 stage/rule，不只显示 `RUN_JOB_FAILED` | 无 `stage`/`rule` 级失败定位断言 | 无断言 | 未取证（`docs/acceptance/m3-step8-parity-20260912/` 为历史 run 47 链，非本条验收） | E3 | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 承接 `V25-Q02`（TODO，依赖 Q01/S02/W03） |
| §7-13 | L532 §7.4 第一版 Hive 全程存在（ODS/DWD/DWS/ADS 均在 Hive）；MySQL 不是数仓替代品而是 ACTIVE 快照低延迟服务库；页面、AI 和决策只通过 `MetricStore` 读同一快照 | `warehouse/ddl/*.sql`；`MySqlMetricStore.java`；`AnalysisService.java:3-10,29-37` | 历史断言（旧 269 条 §17/§18.1 行） | `docs/v2-completeness-audit.md` L369–L386 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：历史） | 未取证 | 当前时点未重跑 |
| §7-14 | L534 §7.4 建立 `MetricStoreFactory` 按 `metric_store_type` 选择实现；MySQL 默认；Hive 为管理员显式降级查询路径、**不由系统按数据量随机切换**；Doris 第二阶段首选；ClickHouse 仅接口与能力矩阵、标记 DEFERRED | `MetricStoreFactory` **0 命中**（`raw/04` B 段）；`MetricStore.java:9` 接口存在 | 无断言 | `raw/04-v25-object-presence.txt` B 段 L42 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 「按类型选实现的工厂/路由」缺失（旧 269 条 §17.7 行同此）；承接 `V25-X02`（DEFERRED）/`X03`（DEFERRED） |
| §7-15 | L536 §7.4 默认读当前授权源作用域的 ACTIVE；归档历史快照通过**显式入口**按 source 权限读取并审计，响应醒目标记 `ARCHIVED`，不混入默认查询；失败不改变原 ACTIVE；成功发布才原子归档旧 ACTIVE；唯一性按发布作用域（至少 `source_id + runtime_profile_id`）建 DB 约束，**不凭全库最新一行选择** | `MySqlMetricStore.java:177-200`（事务内归档+激活）；`MetricPublishRepository.java:50-68` | 历史断言（旧 269 条 §17.5 行） | `docs/v2-completeness-audit.md` L375 | E3 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：历史） | 未取证 | 「按 source 权限读归档」依赖 `V25-P02`（TODO，依赖 P3-02-c）⇒ 当前未取证 |
| §7-16 | L538 §7.4 必须提供 ACTIVE 元信息：`snapshotId`、`sourceId`、`sourceCode`、`businessDate`、`version`、`createdAt`、`definitionVersion`；旧 `sourceInstanceId` 仅作兼容别名、禁双身份；Hive 降级仅管理员显式启用且必须固定相同源/日期/口径/快照数据 | `MetricPublishValidator.java:128-140`（`definition_version` 单一权威，旧 269 条 §13.1 行） | 历史断言 | `docs/v2-completeness-audit.md` L173 | E2 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：历史） | 未取证 | `sourceCode`/`businessDate` 是否进入 ACTIVE 元信息未取证 |

## §8 页面、AI、决策与安全（V2.5 L540–627）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §8-1 | L542–544 §8.1 页面：管理员可管理源、员工可看懂页面（不填技术参数、失败有解释和旧结果标签、停商城仍查历史） | `web/src/views/*.vue` 10 个；无「数据源」页 | 无当前断言 | 未取证（**当前无在跑服务**，看板 §5 L120；本轮不做页面验收） | E5 | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 承接 `V25-E03`（TODO，依赖 E01/P01/P02）；**本矩阵不得用历史截图替代当前 E5** |
| §8-2 | L546–554 §8.2 AI 定位（只读 SQL AST/EXPLAIN/限额；真实调用与 mock 分开；同快照数值） | `SqlExecutor.java:49,71-76`（fail-closed）；`QueryCostGuard`；`TextToSqlService`/`EvidenceBuilder` | 历史断言（旧 269 条 §17.1/§13.5 行） | `docs/v2-completeness-audit.md` L368,L402 | E2 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：历史；AI 腿未纳入同一同值断言——旧 269 条 §18.5 行记"已自动化的只有四处"） | 未取证 | 「五处同值断言」只有四处；`V25-A01`（TODO，真实凭据待提供） |
| §8-3 | L556–580 §8.2.1 受控 AI 数仓设计器（先画像映射，后蓝图/编译/sandbox/审批/回滚） | `WarehouseBlueprint`/`BlueprintValidator`/`SourceProfileDraft`/`ModelingWizard`/`SandboxExecutor` **全部 0 命中**（`raw/04` D 段） | 无断言 | `raw/04-v25-object-presence.txt` D 段 L49–L54 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接 `V25-A03`（TODO）；V2.5 L718 明确 AI 建仓属后续独立出口、不阻止先交可用底座，**但仍属 V2 系列未完成要求** |
| §8-4 | L582–594 §8.2.2 AI 建模数据结构（7 张 AIW 表等） | 0 命中 | 无断言 | `raw/04-v25-object-presence.txt` D 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §8-5 | L596–600 §8.2.3 责任边界（AI 不得绕过人工审批/审计） | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §8-6 | L602–608 §8.2.4 AI 调用/安全/一致性 | `LlmProvider.java:8`（temperature，旧 269 条 §6.3 行）；「模型返回非 JSON」专项用例缺失、`AiOutputValidator` 类不存在（旧 269 条 §23.3 行） | 无断言 | `docs/v2-completeness-audit.md` L414 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 承接 `V25-A01` |
| §8-7 | L610–612 §8.2.5 建模向导（七步） | `ModelingWizard` 0 命中 | 无断言 | `raw/04-v25-object-presence.txt` D 段 | E5 | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §8-8 | L614–618 §8.3 决策（五模板、草稿/审批/执行/效果、等长窗口） | `DecisionController.java:52-139`；`DecisionStateMachine.java`；`Decisions.vue` | 历史断言（旧 269 条 §22.4 行）；`source` 硬编码 `"ai"`（`:57`） | `docs/v2-completeness-audit.md` L311 | E2 | DONE_LIMITED | 已提交 | 未跑 | 有（**限定**：无模板选型、无截止日列、无 EFFECTIVE 正样本——旧 269 条 §30 行记 ⚠️） | 未取证 | 承接 `V25-A02`（TODO，依赖 D02/A01） |
| §8-9 | L620–626 §8.4 安全与审计（`data_dev` 角色、禁默认口令、启动与重试分权、脱敏、跨源拒绝） | 旧 269 条 §18.4/§28 行：`data_dev` 角色**不存在**；`sys_user` 仍 3 行种子 `password_hash`；前端 `router.js:5-15` 无 `meta.role` | 无当前断言 | `docs/v2-completeness-audit.md` L396,L434,L453 | — | TODO | 已提交 | 未跑 | 未取证 | 未取证 | 承接 `V25-U01`（TODO，依赖 S01/P01） |

## §9 泳道、测试分层与证据保全（V2.5 L628–683）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §9-1 | L630–639 §9.1 并行泳道与独占文件纪律 | 看板 §4（`V25-Q01` 标注「总控泳道(Q01独占热点文件)」） | 无断言（治理条款） | 看板 §4 L82 | — | IN_PROGRESS | 不适用 | 不适用 | 有（治理层） | 未取证 | 治理条款无需 E 级证据；本行仅登记存在 |
| §9-2 | L641–649 §9.2 **T0** 单测/静态/编译（每小任务提交前） | `analytics-server/**/src/test/**`、`spark-jobs/**` | `mvn -o -pl <module> test`；旧 269 条 §23.1 行记 L0 = 303/303（历史） | `docs/acceptance/local-readiness-20260914.md` L11–L23（09-14 实测：后端 566 = 564 通过 / **2 失败**；spark-jobs 111 通过 / 15 套件；分析前端 74；商城前端 6） | E2 | IN_PROGRESS | 未提交(dirty) | **失败**（2 项） | 有（限定：09-14 单次汇总） | 未取证 | **E1 当前不成立**：`platform-common` 主代码不可编译（看板 §4 L82 实测 `QualityRuleDefinition.java:84` 找不到 `isKnownSeverity`）⇒ T0 当前未通过 |
| §9-3 | L646–647 §9.2 **T1** 模块测试/内存或 20–100 条（每泳道交付前） | 同上 | 无当前断言 | `docs/acceptance/local-readiness-20260914.md` L11–L23 | E2 | IN_PROGRESS | 未提交(dirty) | **未跑**（仅 T0 汇总） | 未取证 | 未取证 | 无 |
| §9-4 | L648 §9.2 **T2** 固定 55 条黄金链 WSL HDFS/Metastore/Spark/MySQL 真实全链（相关合并批次一次）；**LOCAL 快测不能替代** | 无 | 无断言 | 未取证（WSL 链从未跑过；`local-readiness-20260914.md` L5 原文「尚未验证当前完整应用在 WSL 中启动、采集和发布」） | E3 | TODO | 不适用 | 未跑 | 未取证 | 未取证 | 承接 `V25-E01`（TODO） |
| §9-5 | L649 §9.2 **T3** WSL 固定 seed 1,000 条（WSL 完整小链后一次，多节点 DEFERRED）；**T4** 10 万/100 万条（性能里程碑，不做日常回归） | 无 | 无断言 | 未取证；旧 269 条 §23.2 行记 `medium-1k`/`perf-100k`/`perf-1m` **三个数据集不存在**，§23.1 行记 L2 未做 | E3/E4 | DEFERRED | 不适用 | 未跑 | 未取证 | 未取证 | 承接 `V25-L01`（TODO）；T4 属性能里程碑 |
| §9-6 | L661–667 §9.4 数据库 IT **防误写门禁** | `AnalysisGoldenMySqlIT.java`（已修改）、`MetricAdsMySqlIT`、`MetricPublisherMySqlIT`（旧 269 条 §17.6 行；`local-readiness-20260914.md` L34 记两 IT 仍含直连 `analytics_metric` 与 cleanup 路径、本轮未运行） | 无防误写负向断言 | `docs/acceptance/local-readiness-20260914.md` L34 | E2 | IN_PROGRESS | 未提交(dirty) | **未跑** | 未取证 | 未取证 | 缺「正式库写前被拒」负向测试；承接 `V25-S01`（IN_PROGRESS，看板 §4 L75「下一动作：先交『正式库写前被拒』负向测试」）+ `S02`（TODO，依赖 S01） |
| §9-7 | L669–676 §9.5 轻量回归和证据保全：每次验收独立目录保存 README、命令/退出码、JDK/工具版本、git commit + dirty patch 指纹、jar hash、配置/夹具 hash、结果明细和未测范围；日志脱敏；**target 报告只作中间产物，不能作为唯一永久证据** | 本任务 `docs/acceptance/v25-r01-coverage-20260914/`（README + MATRIX + raw/） | 本目录即为本条的践行样本 | `raw/01-identity-git.txt`（commit + 38 行 dirty + 文件 hash）；`raw/02-acceptance-inventory.txt`（证据目录盘点） | — | DONE_LIMITED | 无代码 | 不适用 | 有（本任务目录） | 未取证 | **对照缺口**：`docs/acceptance/v25-w01-env-inventory-20260914/` 有 `raw/` 10 个文件但**无 README**；`guideline-v24-coverage-20260912/` 亦**无 README**（父级批评事实成立）；`v25-t01-t02-baseline-20260914/`、`v25-s01-it-safety-20260914/` **目录不存在**（见 §9-9） |
| §9-8 | L678–682 §9.6 并发与反馈（每任务开工登记、按登记时间反馈） | 看板 §4（38 行登记表） | 无断言 | 看板 §4 L71–L110 | — | IN_PROGRESS | 不适用 | 不适用 | 有（治理层） | 未取证 | 看板 §4 已为 38 个任务包预留行位；**已填 6 行**（T01/T02/S01/S02/W01/Q01/R01），其余 31 行为「未分配/未开工」 |
| §9-9 | L669–676（证据可定位性推论） | 无 | 无断言 | `raw/02-acceptance-inventory.txt` L23–L24 实测：看板 §4 L73/L74 声明的 `docs/acceptance/v25-t01-t02-baseline-20260914/` **不存在**；L75 声明的 `docs/acceptance/v25-s01-it-safety-20260914/` **不存在** | — | TODO | 不适用 | 不适用 | 未取证 | 未取证 | **新增矛盾**：看板 §4 两条在办任务的「证据链接」指向尚未创建的目录 ⇒ 这两个泳道当前**无可定位证据**。是否允许在交付前先建目录由总控裁决 |

## §10 任务包格式（V2.5 L684–704）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §10-1 | L688–702 §10 任务包 13 字段（ID/Owner/状态/依赖/允许范围/变更边界/验收判据/测试层级/风险/证据/反馈时间/阻塞/下一动作） | 看板 §3 表头 = `ID｜任务｜状态｜依赖｜允许范围｜验收判据｜测试/E｜章节`（7 列）；看板 §4 表头 = `ID｜Owner｜当前子步骤｜上次反馈｜下次反馈｜提交/dirty指纹｜测试结果｜证据链接｜阻塞或下一动作`（9 列） | 无断言 | 看板 V2.5 L29、L71 | — | DONE_LIMITED | 不适用 | 不适用 | 有（限定：字段被拆到 §3+§4 两张表承载，未逐字段同名） | 未取证 | **口径差异**：13 字段未在单张表内齐备（如「风险」「变更边界」无独立列）。属指导书↔看板的字段映射差异，需总控裁决是否补齐（本矩阵不改文） |

## §11 实施顺序与阶段出口（V2.5 L706–718）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §11-1 | L708 第 1 条：修 T01/T02 两项基线失败 + S01/S02 IT 安全与全链隔离；只读环境盘点与契约裁决可并行 | 看板 §3 L30–L33（T01/T02/S01/S02） | 见 §5-19、§9-6 | 看板 §4 L73–L76 | E2 | IN_PROGRESS | 未提交(dirty) | **失败**（2 项） | 未取证 | 未取证 | 当前阶段 = 第 1 条仍在办 |
| §11-2 | L709 第 2 条：`W01～W05` 准备 WSL 软件/路径/存储及运行档案；`Q01/Q02` 收口质量规则与失败保快照 | 看板 §3 L34–L42 | 见 §6-1～§6-5、§7-8 | `docs/acceptance/v25-w01-env-inventory-20260914/raw/*`（10 文件，**无 README**） | E1 | IN_PROGRESS | 未提交(dirty) | 未跑 | 有（限定：仅 W01 只读盘点） | 未取证 | `W02`～`W05` 全部 TODO/READY，未开工 |
| §11-3 | L710 第 3 条：`C01/C02` 冻结映射契约与中立原因码；`P3-02-a` 纯映射/dry-run，`b` 持久化与重放，`c` 两源验证 | 看板 §3 L43–L48 | 见 §5-6～§5-33 | 未取证 | — | READY | 无代码 | 未跑 | 未取证 | 未取证 | `C01` = READY；`C02`/`P3-02-a/b/c` = TODO |
| §11-4 | L711 第 4 条：`I01/I02` 实现 `FlumeLandingConnector`/`HdfsLandingStorage` 并接入采集编排；`E01` 跑 WSL 55 条完整链 | 看板 §3 L49–L50 | 见 §6-6、§9-4 | 未取证 | E3 | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | **对照更正**：`HdfsLandingStorage.java:23` **已存在**（`raw/04` B 段）⇒ 该条的真实缺口是「Flume 连接器 + 接入编排」，不是 HDFS 存储类缺失；需总控确认任务文字 |
| §11-5 | L712 第 5 条：`P01/P02` 完成 P4 数据源页面与按源指标；`E02` 异构 B 全链；`E03` 员工 E5 | 看板 §3 L50–L52 | 见 §5-5、§7-15、§8-1 | 未取证 | E5 | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §11-6 | L713 第 6 条：`D01～D04` 补交易/DIM/ADS/异常算法；`A01～A03` 补真实 AI 分析、五决策模板、受控建仓；**AIW-001～020 原专项细目全部保留，不能仅交一个壳页面** | 看板 §3 L53–L59 | 见 §7-1～§7-12、§8-2～§8-8、§13 各行 | 未取证 | E2/E5 | TODO | 无代码 | 未跑 | 未取证 | 未取证 | `A03` 单行承接 AIW-001～020（见 §13 各行） |
| §11-7 | L714 第 7 条：`R01/R02` 重建可复核覆盖矩阵，再逐条重审 269 条形成新冻结快照；变更或延期必须记录替代条款和批准依据 | 本目录（R01 首版） | 本矩阵存在且每行含代码/断言/证据/四状态 | `MATRIX.md` 本文件；`raw/01`–`raw/05` | — | IN_PROGRESS | 无代码 | 不适用 | 有（R01 首版） | 未取证 | **R01 未完成**：本版为按 12:35 反馈时点交出的**首版**；269 条逐条重审属 `R02`（TODO） |
| §11-8 | L715 第 8 条：远程多节点/YARN/SSH、Doris、ClickHouse 按 DEFERRED 保留；需演示时重新开启验收 | 看板 §3 L64–L67 | 见 §6-8 | 看板 §2 L21 | E4 | DEFERRED | 不适用 | 未跑 | 未取证 | 未取证 | 无 |
| §11-9 | L716 第 9 条：工程出口完成后再讨论论文；日期与学校清单**不得由 Agent 发明** | 无 | 无断言 | 未取证 | — | DEFERRED | 无代码 | 不适用 | 未取证 | 未取证 | 与 §1-2 同一事项 |
| §11-10 | L718 当前阶段「可用产品」出口七项（三程序独立；人工配置接 B 并按源隔离；WSL 真 HDFS/Metastore/Spark/MySQL 小链可复现；错误可解释且失败保旧快照；管理员可管理源、员工可看懂页面；质量与权限负例通过；源码/构建/配置/数据证据可定位） | 见各专项行 | **无任一出口项当前有完整验收** | 未取证 | E3/E5 | TODO | 未提交(dirty) | **未跑** | 未取证 | 未取证 | 逐项映射：①→§3-1（当前无在跑服务）②→§5-33/§7-15 ③→§9-4/§6-1 ④→§7-12/§9-6 ⑤→§8-1/§5-5 ⑥→§7-6/§8-9 ⑦→§9-7 |

## §12 执行治理与历史裁决迁移（V2.5 L720–732）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §12-1 | L722–724 §12 治理：并发/独占/反馈/证据纪律 | 看板 §1、§4 | 无断言 | 看板 V2.5 L7–L13、L71–L110 | — | IN_PROGRESS | 不适用 | 不适用 | 有（治理层） | 未取证 | 无 |
| §12-2 | L725–730 历史裁决迁移（**必须引用、不得重算为新缺口**）：B-13 是已勘误的假警报，不安排数据恢复/基准重建；`AUTO_INCREMENT` 间隙、`TABLE_ROWS` 估算和文件 mtime 不能单独证明删除；F-99 同名表比较必须带 server/schema/table；P5 首期仍用固定异构文件；ODS 演示重建需备份/表数/行数/checksum/namespace/授权；`newFileCount`→`consumableFileCount` 兼容项；`synthetic=true` 只进 generation run/产物 manifest/验收元信息 | 无 | 无断言（裁决条款） | `docs/acceptance/b13-db-forensics-20260912/`（19 文件） | — | DONE_LIMITED | 不适用 | 不适用 | 有（历史裁决） | 未取证 | 本矩阵已将上述各项**登记为已裁决**，未计入缺口 |
| §12-3 | L726 §12 任务行未单列的既有要求仍按 §3～§8 与原 269 项逐条登记，不可静默丢弃；`V25-R01` 负责一对一交叉表，**禁止从旧覆盖表的 DONE 直接复制结论** | 本矩阵 | 本矩阵每行均标注推导口径；旧 229 行表未被引用为结论 | `MATRIX.md` 本文件；历史件 `docs/acceptance/guideline-v24-coverage-20260912/COVERAGE.md`（**保留不删**，仅作历史引用；其目录**缺 README**） | — | IN_PROGRESS | 不适用 | 不适用 | 有（口径已立） | 未取证 | 269 条逐条一对一映射在本版 = **未映射·待归并**（见文末） |
| §12-4 | L731 四项状态分别登记 | 本矩阵四列 | 本矩阵每行四列独立填写 | `MATRIX.md` 本文件 | — | DONE_LIMITED | 不适用 | 不适用 | 有 | 未取证 | 无 |
| §12-5 | L732 V25-R01 禁从旧覆盖表复制 DONE | 本矩阵 | 全文无「沿用旧覆盖表结论」行 | `MATRIX.md` 本文件 | — | DONE_LIMITED | 不适用 | 不适用 | 有 | 未取证 | 无 |

## §13 AI 辅助多商城数仓接入（V2.5 L734–756）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §13-1 | L736–738 §13.1 定位（AI 建仓为 V2 系列未完成要求，属后续独立出口） | 无 | 无断言 | `docs/acceptance/v25-document-release-20260914.md` L23 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §13-2 | L740–745 §13.2 AI 建仓对象与流程（画像→映射→蓝图→编译→sandbox→审批→回滚） | 同 §8-3（全 0 命中） | 无断言 | `raw/04-v25-object-presence.txt` D 段 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 无 |
| §13-3 | L747–754 §13.3 完成定义六条 | 无 | 无断言 | 未取证 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 由 `V25-A03`（TODO）承接；与看板 §3 L59 判据「先画像映射，后蓝图/编译/sandbox/审批/回滚；同 B 人工 vs AI 实测」对齐 |
| §13-4 | **AIW-001** 结构化契约与 JSON Schema | 0 命中 | 无断言 | `docs/superpowers/plans/2026-09-11-ai-assisted-warehouse-onboarding-implementation-v1.0.md:61`（专项定义） | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 承接任务：**`V25-A03` 单行**（看板 §3 L59「AIW-001～020 原专项文件范围」）⇒ **无逐条 Owner/证据位**，属 20 条细分未拆解 |
| §13-5 | **AIW-002** 持久化迁移与 Repository | 0 命中 | 无断言 | 同上 L70 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-6 | **AIW-003** 数据源元数据采样器 | 0 命中 | 无断言 | 同上 L79 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-7 | **AIW-004** 画像与 PII 脱敏 | 0 命中 | 无断言 | 同上 L87 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-8 | **AIW-005** 固定评测集与 prompt 攻击集 | `tests/ai-questions/questions.jsonl`（100 题：safe 50 / blocked 50，旧 269 条 §23.2 行） | 无断言 | 同上 L95；`docs/v2-completeness-audit.md` L413 | — | TODO | 部分已提交 | 未跑 | 未取证 | 未取证 | 同 §13-4；现有 100 题**不等于** AIW-005 的攻击集 |
| §13-9 | **AIW-006** 建模 LLM 适配与结构化输出（解析失败为 `MODEL_OUTPUT_INVALID`，不得提取代码块后直接执行） | 0 命中 | 无断言 | 同上 L103,L107 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4；与 §8-6 同一缺口 |
| §13-10 | **AIW-007** `SourceProfileDraft` 生成服务 | 0 命中 | 无断言 | 同上 L111 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-11 | **AIW-008** 映射置信度与问题生成 | 0 命中 | 无断言 | 同上 L118 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-12 | **AIW-009** 审核 API 与最小页面 | 0 命中 | 无断言 | 同上 L125 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-13 | **AIW-010** `WarehouseBlueprint` 生成器 | 0 命中 | 无断言 | 同上 L133 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-14 | **AIW-011** `BlueprintValidator` | 0 命中 | 无断言 | 同上 L140 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-15 | **AIW-012** 受控 DDL/SQL 编译器 | 0 命中 | 无断言 | 同上 L147 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-16 | **AIW-013** 工作流 DAG 编译与验证 | 0 命中 | 无断言 | 同上 L155 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-17 | **AIW-014** Sandbox 执行器 | 0 命中 | 无断言 | 同上 L162 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-18 | **AIW-015** 对账与建模证据包 | 0 命中 | 无断言 | 同上 L169 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-19 | **AIW-016** 审批、RBAC 与审计 | 0 命中 | 无断言 | 同上 L176 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-20 | **AIW-017** 不可变 Release、部署与回滚 | 0 命中 | 无断言 | 同上 L183 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-21 | **AIW-018** 七步建模向导 | 0 命中 | 无断言 | 同上 L190 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-22 | **AIW-019** 故障与安全攻击集 | 0 命中 | 无断言 | 同上 L197 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-23 | **AIW-020** 人工与 AI 辅助 A/B 验收 | 0 命中 | 无断言 | 同上 L204 | — | TODO | 无代码 | 未跑 | 未取证 | 未取证 | 同 §13-4 |
| §13-24 | L269 专项文件自身出口（W0–W4 五波次 + 第 6 节「看板将 AIW-001 移为 READY 并登记 Owner/允许范围/反馈时间后才能编码」） | 无 | 无断言 | 同上 L231–L251、L269 | — | READY | 无代码 | 未跑 | 未取证 | 未取证 | **门槛未满足**：看板 §3 未把 AIW-001 单列并移为 READY；`V25-A03` 整行状态 = TODO ⇒ 按专项文件自述，AI 建仓**当前不得编码** |

## §14 V2.4→V2.5 变更摘要（V2.5 L760–768）

| ID | V2.5 | 代码 | 断言/测试 | 运行证据 | E | 状态 | 提交 | 测试 | 限定验收 | 完整验收 | 缺口或替代条款 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| §14-1 | L760–768 §14 变更摘要自身可核（新版本对旧版差异有记录） | `docs/backups/v25-preupdate-20260914-090606/`（V2.4 + V2.2 看板 + 旧 `docs/README.md`） | 无断言 | `docs/acceptance/v25-document-release-20260914.md` L11–L13（逐项 SHA-256 校验；V2.4 sha256 `580AD7BE46B2F395400D9CD0812C58D4D3621794B8604B4E606EF44420C4535D`） | — | DONE_LIMITED | 已提交 | 不适用 | 有（文档层） | 未取证 | **基线漂移**：`local-readiness-20260914.md` L7 与 `v25-document-release-20260914.md` L17 均写「代码基线 8983616」，实测 HEAD = `6062434`（2026-09-14 12:06:44，即 V2.5 看板自身开工登记的提交）⇒ 该差异**由看板登记提交本身造成**，属可解释漂移，但不改文即与 `raw/01` 冲突，需总控裁决 |

---

## §269 交叉映射（原 269 条 → V2.5）

- 冻结来源：`docs/v2-completeness-audit.md`（720 行；`> 结论` L5；合计行 L488），快照 commit `34f37a8`（出处：`docs/README.md` L48 原文「269 条统计是 `34f37a8` 的冻结快照，不随代码变化自动更新」）。
- **状态口径**：V2.5 L714 把「逐条重审 269 条形成新冻结快照」交给 `V25-R01/R02`。本版（R01 首版）**未逐条重审**，因此下表**每一条的正确状态是「未映射·待归并」**——不得因为旧文件写过 `完成` 就自动关闭（V2.5 L732 原文：禁止从旧覆盖表的 DONE 直接复制结论）。
- 与旧 229 行的关系：`docs/acceptance/guideline-v24-coverage-20260912/COVERAGE.md` 的 229 行**不是** 269 条的重新验收（该目录**无 README**，且存在指针错位）。该目录**保留不删**，仅作历史件。

| 269 条分组（`docs/v2-completeness-audit.md` 行号） | 条数 | 旧文件计数（仅历史引用） | 本版状态 | 承接任务 |
|---|---:|---|---|---|
| §1–§2 口径与基线（L482） | 4 | 2 / 0 / 0 / 0 / 2 | **未映射·待归并** | `V25-R02`（TODO） |
| §3–§11 边界/商城/契约/采集/Landing（L483） | 30 | 10 / 16 / 3 / 1 | **未映射·待归并** | `V25-R02` + `C01/C02/I01/I02/P01` |
| §12–§14 数仓分层/口径/作业契约（L484） | 79 | 41 / 26 / 12 / 0 | **未映射·待归并** | `V25-R02` + `D01～D04` |
| §15–§18 执行/质量门/指标库/页面（L485） | 66 | 33 / 23 / 10 / 0 | **未映射·待归并** | `V25-R02` + `D04/Q01/Q02/U01/E03` |
| §19–§22 AI/决策/权限审计/接口（L486） | 70 | 42 / 13 / 15 / 0 | **未映射·待归并** | `V25-R02` + `A01/A02/A03` |
| §23–§31 测试/R7–R9 清单/组件/验收（L487） | 20 | 4 / 12 / 2 / 2 | **未映射·待归并** | `V25-R02` + `L01`/`X01` |
| **合计** | **269** | （旧计数，非当前统计） | **269 条全部待归并** | `V25-R02` |

**旧 M1/P1–P5 阶段映射**：`M1`（三程序独立/契约同步/第二适配器）、`P1`（边界/CI）、`P2`（数仓语义）、`P3`（映射执行）、`P4`（数据源页面）、`P5`（异构源验证）在 V2.5 中**已无同名任务行**，其要求被拆入 `V25-T01/T02/S01/S02/W0x/Q0x/C0x/I0x/E0x/P0x/D0x/A0x/U0x`。**在逐条归并完成前，M1/P1–P5 的条目同样为「未映射·待归并」**，承接 `V25-R02`。

---

## 12:18 即时更正（审计期间环境变动，本矩阵自曝）

审计采集期间（12:08→12:18）同一工作树被其他泳道持续改动 ⇒ **本矩阵的部分行在写出后 10 分钟内已被环境超越**。按「不掩盖既有事实」原则，逐项登记，不改写上文行文：

| 变动 | 实测 | 受影响的矩阵行 | 更正后口径 |
|---|---|---|---|
| 新增 2 个提交 | `7296fda`（12:09:55）、`09d7046`（12:12:36），均只改看板 | 全文「基线 HEAD 6062434 + 38 行」 | 现为 `HEAD 09d70468d2f5e229e33cc150c2731dd64a750b20` + **59 行** dirty；`raw/07-baseline-drift.txt` |
| **看板 V2.5 被就地更新** | 122 行 / 16,593 B / sha256 `9E9CC765…02373AE` → **124 行 / 19,127 B / sha256 `71A99A60E687CB02D43784F84EC2D58B0B69A99982D2EB27E3844187E209CF6B`** | 本矩阵所有「看板 §3 L…/§4 L…」行号引用 | 全部指向**修订版 `9E9CC765`**（12:06:05）。指导书 V2.5 **未变**（sha256 一致）⇒ V2.5 行号引用仍有效 |
| `docs/acceptance/v25-t01-t02-baseline-20260914/` **已出现** | 48 文件 / 272,937 B / mtime 12:15:29，含 `README.md` 与 `final-03-platform-common-test.log`、`final-04a/04b`、`final-05/06/07`、`baseline-exit-codes.txt`、`baseline-git-head.txt` | §5-19、§5-26、§9-1、§9-2、上文缺口 #1 前半 | 缺口 #1 **前半已消解**：T01/T02 泳道已交付带退出码的运行证据目录。本矩阵**未复核**其内容（超出本轮时点），故 §5-19/§5-26 的「测试=失败/未跑」**降级为「已有归档运行证据，待 R01 下一版复核」**，不改为「通过」 |
| `docs/acceptance/v25-w01-env-inventory-20260914/` **新增 README.md** | 10 文件 → 11 文件 / 85,323 B / mtime 12:10:28；看板新 L34 记 `V25-W01 = DONE_LIMITED`（71 项已确定 / 10 项未取证 / 12 项待用户确认） | §9-7 对照缺口、`README.md` §4「已知未达标」第 1 条 | 该目录**已补 README** ⇒ README 缺失的批评**仅剩** `guideline-v24-coverage-20260912/` 一处 |
| `docs/acceptance/v25-s01-it-safety-20260914/` | 仍 **NOT-EXIST** | 缺口 #1 后半、§9-9 | **仍成立** |
| `docs/acceptance/b13-db-forensics-20260912/README.md` 被修改 | `git status` = ` M` | §12-2 | 既有证据目录**正被其他泳道改写**（非本任务所为）；引用该目录时须带时点 |
| 新增看板纪律 | 新 L9「不得从『有文件/已提交/测试通过』直接推导全功能完成」；新 L34 W01 行；新增「盘点类任务副作用」纪律（启动已停止的发行版属状态变更）、「构建槽」纪律（同一工作树同一时刻仅一泳道跑 Maven） | 全文 | 与本矩阵「四级状态独立」口径一致，**不冲突** |
| 本任务自身的只读约束 | 本轮**未**修改任何既有证据目录、未启停服务、未跑 Maven、未写库 | — | `raw/06-selfcheck.txt` 末段自检 |

> 说明：本矩阵是 12:08–12:15 时点的**快照**。任何读者使用时必须连同 `raw/01`、`raw/07` 的时点一起引用；「当前」二字在本目录内一律指该时点。

## 本版新增的缺口与矛盾（供 12:35 反馈）

> 只列**实测发现**，每条给 `V2.5 行号` 或看板行号 + 判据 + 现状 + 证据指针。

1. **【矛盾·高】看板 §4 声明的两个证据目录不存在**。判据：看板 §4 L73/L74「证据链接 `docs/acceptance/v25-t01-t02-baseline-20260914/`」、L75「`docs/acceptance/v25-s01-it-safety-20260914/`」。现状：两目录 `Test-Path` = False。证据：`raw/02-acceptance-inventory.txt` L23–L24。影响：这两条在办任务**当前无可定位证据**，与 §9.5 证据保全纪律冲突。
2. **【矛盾·高】同一规则码在两个生产路径得到不同严重度**。判据：V2.5 L520 要求「一次 run 冻结完整规则版本与指纹」。现状：`DataQualityGate.java:88` 已走版本化 `RuleSeverity.resolve(rules, …)`，而 `PipelineService.java:712,748,808` 与 `MetricPublishValidator.java:201,219,225` 仍走**已 `@Deprecated`** 的全局 `RuleSeverity.of(ruleCode)`（`RuleSeverity.java:90` javadoc 自引 V2.5 §7.3.1 line 520）。证据：`raw/03-ruleseverity-version-boundary.txt`。
3. **【缺口·高】E1 当前不成立**。判据：V2.5 §9.2 T0「每小任务提交前」编译必须绿。现状：`platform-common` 主代码不可编译（`QualityRuleDefinition.java:84` 找不到 `isKnownSeverity`）。证据：看板 §4 L82（12:08 实测）+ `raw/03`。连带：T01/T02 的 E2 取证被阻塞（看板 §4 L73/L74 自述「阻塞：等 Q01 编译绿方可 E2 取证」）。
4. **【缺口·高】`DataQualityGate` 的 run 冻结规则集来自代码内快照而非持久化记录**。判据：V2.5 L520「一次 run 冻结完整规则版本与指纹」+ L524「需 `(source_id, rule_code, rule_version …, compatPolicyVersion)`」。现状：`DataQualityGate.java:68` 缺省 `QualityRuleCatalog.DEFAULT.freeze(null)`；`quality_rule_definition` 表 **0 命中**（`raw/04` A 段）。⇒ 无 DB 级「该 run 用了哪一版规则」的可审计载体。
5. **【缺口·中】§5.7 的清单新字段未落 schema**。判据：V2.5 L376 要求分开 `rawChecksum/rawBytes` 与 `canonicalChecksum/canonicalBytes`、单列 `systemErrors`、`reasonCounts`。现状：`contract-specs/schemas/ingestion-manifest.v1.schema.json` 的 4 个新增键是 P1-05 存量的 `sourceCode/sourceId/profileVersion/mappingVersion`（`IngestionManifestSourceSchemaTest.java:56-57`），**不含** §5.7 新字段。证据：`raw/04` F 段 + 源码。
6. **【缺口·中】§5.2 六表中 5 张不存在**：`source_connector`、`schema_mapping`、`source_manifest`、`quality_rule_definition`、`source_profile` 全 0 命中；仅 `source_registry` 存在（`V16__source_registry.sql:20`）。证据：`raw/04` A 段。⇒ §5.3/§5.4/§5.6 D1–D14/§5.7 的物理载体尚未开始。
7. **【矛盾·中】指导书任务文字与实测对象不一致（需裁决，不要自行改文）**：V2.5 §11.4 与看板 §3 L49 要求「实现 `FlumeLandingConnector`/`HdfsLandingStorage`」，但 `HdfsLandingStorage.java:23`、`LandingStorage.java:12`、`LocalLandingStorage.java:24` **均已存在**。⇒ 该任务真实范围应表述为「补 §5.3 连接器接口 + Flume 连接器 + 接入采集编排」。证据：`raw/04` B 段 L28–L40。
8. **【矛盾·低】基线指针漂移**：V2.5 L4 与看板 L4 均写「当前基线：HEAD 8983616」，实测 HEAD = `6062434`（该提交即看板自身开工登记）。判据：`raw/01-identity-git.txt`。可解释，但需总控决定是否改文。
9. **【缺口·低】§10 任务包 13 字段未在单表齐备**：看板 §3 用 7 列、§4 用 9 列承载；「风险」「变更边界」等无独立列。判据：看板 L29/L71 表头 vs V2.5 L688–702。证据：`MATRIX.md` §10-1 行。
10. **【治理·中】AIW-001～020 无逐条 Owner/证据位**：看板仅 `V25-A03` 一行（§3 L59）承接 20 条细目；专项文件自述「看板将 AIW-001 移为 READY 并登记 Owner/允许范围/反馈时间后才能编码」（`docs/superpowers/plans/2026-09-11-ai-assisted-warehouse-onboarding-implementation-v1.0.md:269`），该门槛未满足。证据：`MATRIX.md` §13-4～§13-24 行。

## 需要用户或总控裁决才能继续（每条只问一个问题）

> **【12:5x 更新】本表 Q1–Q10 已全部由总控裁决，且经按用户裁决 ① 的四类判据复核，十条全部不命中 (a) 架构分叉 / (b) 数据口径 / (c) 论文内容范围 / (d) 破坏性操作 ⇒ 均降级为「总控裁决·留档」，不再上升用户。** 裁决原文与据此动作见 `DECISIONS.md` §2；当前仍未决、需用户裁决的只剩 `DECISIONS.md` §1 的 E-01/E-02/E-03 三条。下表作为**历史提问留档**保留，不再作为待办。

| # | 问题（单一问题） | 依据 | 不裁决的后果 |
|---|---|---|---|
| Q1 | 看板 §4 里 `v25-t01-t02-baseline-20260914/` 与 `v25-s01-it-safety-20260914/` 两个证据目录是**先建空目录再回填**，还是**改为指向实际交付目录**？ | 缺口 #1 | 两条在办任务无可定位证据，§9.5 纪律无法核对 |
| Q2 | 版本化严重度（`RuleSeverity.resolve`）与全局映射（`RuleSeverity.of`）在过渡期**以哪一个为门禁唯一判据**？ | 缺口 #2 | 同一规则码两种结论，质量门与发布侧可能给出相反判定 |
| Q3 | `platform-common` 当前不可编译是否**允许 R01 在矩阵里把它登记为 E1 不通过并继续交付**（即先交文档、不等编译绿）？ | 缺口 #3 | 若必须先绿，R01 首版需整体顺延 |
| Q4 | V2.5 §11.4 / 看板 §3 L49 的任务文字是否**修订为「补连接器接口 + Flume 连接器」**（因 `HdfsLandingStorage` 已存在）？ | 缺口 #7 | 任务范围被高估，验收判据与实测对象错位 |
| Q5 | V2.5 L4 与看板 L4 的基线 `8983616` 是否**统一改为 `6062434`**？ | 缺口 #8 | 所有引用基线的证据指纹与文档冲突 |
| Q6 | §5.7 清单新字段（`rawChecksum`/`canonicalChecksum`/`systemErrors`/`reasonCounts`）是**并入 `ingestion-manifest.v1`** 还是走新 major？ | 缺口 #5；V2.5 L345「不自动升级 `contract-specs/VERSION`」 | C01 冻结作业范围不明，schema 变更方向未定 |
| Q7 | 是否**授权把 AIW-001～020 拆成 20 个可登记任务行**，还是维持 `V25-A03` 单行？ | 缺口 #10 | 20 条细目无 Owner/反馈位，反馈纪律无法逐条执行 |
| Q8 | §10 的 13 字段是否**要求看板单表齐备**？ | 缺口 #9 | 任务包字段口径与指导书长期不一致 |
| Q9 | 269 条逐条归并是否**确认由 `V25-R02` 独占**（R01 只交「未映射·待归并」桶）？ | V2.5 L714 | 若 R01 也需逐条，则本版交付范围需重估 |
| Q10 | 是否**授权只读查询 `information_schema`** 以核对 §4.2 六表/§7.4 约束的当前实际结构（不写库）？ | V2.5 §4.2/§7.4 断言缺口 | 六表与唯一性约束只能标「未取证」 |

---

## 第二版更正与新增（12:5x 时点）

> 纪律：**双时点并列，不抹掉旧结论**（总控 Q3 要求）。旧结论保留在上文，本节只**追加**新时点实测与其对旧结论的处置。
> 本版新增证据：`raw/08-old229-pointer-audit.txt`、`raw/09-information-schema-readonly.txt`；本版新增交付：`VERIFY-T01-T02.md`、`CORRECTION-229.md`、`ADS-FIRST-RELEASE.md`、`DECISIONS.md`。

### A. 四条硬缺口的双时点更新

| # | 12:08–12:18 旧结论（上文原文，保留） | 12:5x 新时点实测 | 处置 |
|---|---|---|---|
| **#1** 看板 §4 两个证据目录不存在 | 两目录 `Test-Path` = False | `v25-t01-t02-baseline-20260914/` **已于 12:15:29 交付**（48 文件 / 272,937 B，含 `README.md`）；`v25-s01-it-safety-20260914/` **至 12:38 仍不存在** | 前半**已消解**（且本版已复核其内容 → `VERIFY-T01-T02.md`）；后半**仍成立**。按总控 Q1，登记行为**预期交付路径**，交付后就地更正 |
| **#2** 同一规则码两路径两种严重度 | 6 处调用点仍用 `@Deprecated` 全局 `RuleSeverity.of` | 未变（`PipelineService.java:712,748,808`、`MetricPublishValidator.java:201,219,225`） | **已裁决**（总控 Q2）：门禁唯一判据 = 版本化 `resolve`；六处**归 Q01 泳道迁移**。矩阵状态由「待裁决」改为「已裁决·待迁移」 |
| **#3** E1 当前不成立（`platform-common` 不可编译） | 12:08：`QualityRuleDefinition.java:84` 找不到 `isKnownSeverity` | **12:19:17 `final-12` EXIT=0 / BUILD SUCCESS / 79 tests / 0 F**（含 `RuleSeverityTest` 14/0）⇒ platform-common 整模块绿 | 旧结论**仅在 12:08 时点成立**；本版并记新时点，**删旧结论以外的推断**（见下「#3 不能证明什么」） |
| **#5** §5.7 新增字段未落 schema | 4 个新键是 P1-05 存量的 `sourceCode/sourceId/profileVersion/mappingVersion` | 未变 | **已裁决**（总控 Q6）：先并入 `ingestion-manifest.v1` 作**可选扩展**，不自动升 major、不改 `contract-specs/VERSION`；升 major 与否留 `V25-C01` |

**#3 不能证明什么**：`final-12` 的绿是 **12:19:17 那一次运行的属性**；其后 12:20:53/12:21:03 同模块即因**其他泳道**新建的 `TestIsolationGuard*` 转红（`raw/final-17`：该文件不在 HEAD，`git cat-file -e HEAD` 退出码 128）。且当前 HEAD 已推进到 `3fcf90e` + 61 行 dirty ⇒ 「E1 现在绿」这句话**必须带时点**，不能作为仓库不变量。

### B. §4.2 对象个数更正（本版新增，源自指导书原文复核）

- **V2.5 §4.2（L261–271）表体实测为 5 个对象**：`generator_target`、`generation_plan`、`generation_run`、`generation_artifact`、`generation_event_stat` ⇒ 上文与旧反馈中的「**六表**」表述**按原文订正为 5 个**（总控 Q10 提问文字亦沿用了「六表」，一并订正）。依据：`MATRIX.md` §4 行 + V2.5 L261–271。
- 只读核对结果（`raw/09`）：在 `meta_app` **可见范围内 0 命中**。**口径**：这只是「可见范围内不存在」，**不是**「全库不存在」——`information_schema` 只显示当前账号有权限的对象（`raw/09` 权限边界更正段）。

### C. 本版新增缺口（12:5x 实测）

11. **【矛盾·高】ACTIVE 指标快照的权威库与配置不一致**。判据：`application.yml:15` 默认 `PLATFORM_METRIC_PUBLISH_URL → …/analytics_metric`；实测 `analytics_metric` 对 `meta_app` **不可见（0 表）**，而 `analytics_meta` 存在 `metric_snapshot`(12 列)/`metric_value`/`metric_definition`，§7.4 的唯一性约束实际都落在 `analytics_meta`。⇒ 「页面读哪个库」「约束建在哪张表」存在分叉。命中用户裁决判据 (a)+(b) ⇒ **E-03 上升用户**（`DECISIONS.md` §1）。
12. **【矛盾·中】ADS 表名 `_m` 后缀不一致**：DDL 为 `ads_user_profile`(`04-ads.sql:105`)、`ads_data_quality`(`:124`)，而代码/旧审计用 `ads_user_profile_m`/`ads_data_quality_m`（`MetricAdsSpec.scala:42,45`）。⇒ 「表存在」与「作业写入的表存在」可能不是同一张表。处置：**D-09 自行决定**（以 DDL 名为准）。
13. **【缺口·中】T02 从未在 Maven 下执行**：`final-04a/04b`、`final-10a/10b` 均因 `-pl platform-app -am` + `-Dtest=` 在 platform-common 处 `No tests matching pattern` 而 SKIPPED，且所传 `-DfailIfNoSpecifiedTests=false` **不是** surefire 的键（正确键 `-Dsurefire.failIfNoSpecifiedTests=false`，报错原文即如此写）。⇒ T02 证据强度 = 探针级已证 + Maven 级未取证。详见 `VERIFY-T01-T02.md` §2。
14. **【矛盾·中】spark-jobs `EXIT=0` 但 `Tests run: 0`**：`final-16`（12:21:38）BUILD SUCCESS 且测试数为 **0**，与 `local-readiness-20260914.md` L18–L20「111 通过 / 15 套件」互斥 ⇒ 记为**矛盾·未取证**（本任务不跑 Maven），任何引用该 `EXIT=0` 处必须同时引测试数。见 `VERIFY-T01-T02.md` §4。
15. **【事实·中】旧 229 表三条批评的核对结果**：`缺 README` **成立**（`COVERAGE.md` 第 3 行指向同目录不存在的 `README.md`）；`指针错位` **不能按路径断链复现**（229 行中无法解析的 token 仅 8 行且全非路径）；`证据覆盖不足` **部分成立**（104 行行内无路径型指针）。**本版不产出任何完成率**。见 `CORRECTION-229.md`。

### D. 本版未取的证据面（明确登记，不用推断补）

1. §7.4 约束在 `analytics_metric` 的实际结构 —— **受权限限制未取证**（不可与「不存在」混同）。
2. 跨库同名表枚举（总控提示「同名表在 11 个库里」）—— `meta_app` 权限下**无法枚举**。
3. 旧 229 行指针的**语义正确性**（是否指向正确对象/行号）—— 仅验可解析性。
4. T02 的 E2/E3 级证据、spark-jobs 真实测试数、S01 交付物 —— 均**未取证**。
5. 本版**未**执行任何 Maven、未启停服务、未写库、未改既有证据目录、未 git 提交。

---

## 第三版更正与新增（12:4x–13:0x 时点，总控 v3 范围①–⑤）

> 证据：`raw/10-crossdb-census-and-74-invariants.txt`（总控 12:4x 跨库普查原文 ＋ 本泳道 root 只读独立复核 R1–R13）。
> 引用总控普查时须注明「**总控 12:4x 代跑**」。**旧结论原文一律保留**，本节只追加。

### 更正 A（高）§4.2 五个对象**存在且已投产** —— 首版结论与本泳道「可见范围内 0 命中」、总控普查「9 个 schema 都不存在」**三方均需更正**

| 时点 | 结论 | 为何错 |
|---|---|---|
| 首版 12:08–12:12 | 「§5.2 六表中 5 张不存在；仅 `source_registry` 存在」 | 代码/schema 检索范围未覆盖生成器程序目录 |
| 第二版 12:5x | 更正为「**可见范围内** 0 命中，不写全库不存在」 | 方向正确但仍受限：`meta_app` 只可见 `analytics_meta` |
| 总控 12:4x 普查 | 「五个对象在**全部 9 个 schema** 中都不存在」 | **过滤条件仍是 `analytics_*` 前缀** ⇒ 漏掉生成器库 |
| **第三版实测** | **五个对象全部位于 schema `generator_meta`，且均有数据、均有代码在用** | — |

实测事实（R3/R5/R6）：
- 库结构：`generator_meta` 共 **6 张表** = `generator_target`(**422** 行) / `generation_plan`(**430**) / `generation_run`(**184**) / `generation_artifact`(**551**) / `generation_event_stat`(**947**) + `flyway_schema_history`。
- 建表 DDL：`synthetic-data-generator/src/main/resources/db/generator/V1__generator_meta.sql`（L12/L30/L50/L74/L92，Flyway 迁移）⇒ **§4.2 有正式物理载体**。
- 代码在用：`GeneratorMetaStore.java:100,105,111,116,142,148,169,246,250,258`、`GenerationRunService.java:531`、`MallCapability.java:7`、`MallTargetAdapter.java:10,25`、`TargetCheckConfig.java:4`、`Artifact.java:7,31`、`JsonlEventSink.java:178,186`。
- **纪律教训（同一错误第二次）**：**按前缀/按账号过滤的普查不能得出「不存在」结论**。任何存在性结论必须写明覆盖的 schema 集合与过滤条件，否则只能写「在 X 范围内未命中」。

**仍然未取证**：这 5 张表的**列结构与约束是否逐项符合 V2.5 §4.2 规格**（本轮只测存在性与行数）；行数只证「在用」，不证「合规」。

### 更正 B（中）`metric_snapshot` 出现在 **11** 个 schema（普查记 9 个）
- meta 族 5 个（`analytics_meta`/`_p103`/`_p105`/`_p105it`/`_v17probe`，各 **12 列**，唯一键仅 `PRIMARY(id)`+`uk_snapshot_id`）
- metric 族 4 个（`analytics_metric`/`_p103`/`_p105`/`analytics_verify_m3_parity`，各 **15 列**，**独有** `uk_active_profile(runtime_profile_id, active_flag)`）
- **`mall_simulator`、`mall_simulator_test`（各 11 列）** ← 普查未计入
- ⇒ 总控提示的「同名表在 11 个库里」在本表名上**字面成立**（R4/R7）。

### 裁决落地：E-03 **消解**（ACTIVE 权威库 = `analytics_metric`）
- 原 E-03 观测保留（配置指 `analytics_metric`、当时 `meta_app` 只可见 `analytics_meta` 的 12 列副本）。
- 总控跨库普查（12:4x）判定：**`analytics_metric` 为权威库**（与 `application.yml:15` 默认值一致），`analytics_meta` 的 12 列 `metric_snapshot` 是**历史副本** ⇒ 性质是「**历史重复表 ＋ schema 漂移**」，**不是架构分叉**。
- ⇒ 由 `DECISIONS.md` §1 移出，改为「已由总控跨库普查消解」。**不再需要用户裁决。**
- 本泳道独立复核支持该判定：权威侧 15 列**独有** `definition_version varchar(16) NOT NULL`（R13）⇒ §7.16 要求的 ACTIVE 元信息 `definitionVersion` **确实存在且非空**；此前基于 meta 族 12 列写的「无 `definitionVersion`」**按此更正**（那 12 列是历史副本）。

### §7.4 不变量实测（授权项②，`analytics_metric`，零写）
| 检查 | 实测 | 判读 |
|---|---|---|
| 约束是否存在 | metric 族 4 库均有 `uk_active_profile(runtime_profile_id, active_flag)` | ✔ 与总控一致 |
| `active_flag` 语义 | **NULL = 11 行 / 1 = 1 行** | ACTIVE 行置 1、非 ACTIVE 置 NULL（借 MySQL 唯一键允许多 NULL 达成「每档案恰一行 ACTIVE」） |
| 不变量①「每档案至多一行 ACTIVE」 | `profiles_with_multi_active = 0` | ✔ 成立 |
| 不变量②「不得有档案无 ACTIVE 行」 | `profiles_without_active = 0` | ✔ 成立 |
| 规模 | `total_rows = 12` / `distinct_profiles = 1` / `active_rows = 1` | ⇒ **样本极小**：不变量只在 1 个档案上成立，**不得**表述为「已通过并发/压力验证」；并发发布下的原子性**仍未取证** |

- **D-10 裁决照此修订落档**：§7.4「按发布作用域唯一」**以现有 `uk_active_profile(runtime_profile_id, active_flag)` 满足，不新增 `source_id` 列**（首版最小可实现）；§7.4 字面措辞待 `V25-C01` 按等价列改写，**不因此开迁移**。实测支持：meta 族与 metric 族**均无 `source_id` 列**（仅 `source varchar(32)`）。

### 缺口 #12 处置：ADS 表名以库内 `_m` 为准
- 实测 `analytics_metric` 共 **11 张表** = **8 张 `ads_*_m`** + `metric_snapshot` + `metric_value` + `flyway_schema_history`（R8）：`ads_active_trend_m`/`ads_behavior_funnel_m`/`ads_data_quality_m`/`ads_hot_product_m`/`ads_operation_overview_m`/`ads_product_conversion_m`/`ads_sale_trend_m`/`ads_user_profile_m`。
- ⇒ **代码（`MetricAdsSpec.scala:42,45`）与库一致**，用 `_m`；不一致的是 **DDL 文字**（`warehouse/ddl/04-ads.sql` 无后缀）⇒ **待 `V25-C01` 冻结时订正 DDL 文字**，`ADS-FIRST-RELEASE.md` 表名按 `_m` 读。

### 新风险登记（总控 v3 范围④）
16. **【风险·中高】9 个 `analytics_*` 历史/探针库并存，另有 2 个商城库含同名表**：
    实测并存库 = `analytics_meta`(+`_p103`/`_p105`/`_p105it`/`_v17probe`)、`analytics_metric`(+`_p103`/`_p105`)、`analytics_verify_m3_parity`（合计 9 个），外加 `mall_simulator` / `mall_simulator_test` 亦含 `metric_snapshot`（共 **11** 个 schema 有同名表）。
    ⇒ **论文与运维口径不得混用**：任何「指标快照行数 / ADS 产出 / §7.4 唯一性」结论必须**写明 schema 名**，禁止用裸表名；`_p103`/`_p105`/`_p105it`/`_v17probe`/`verify_m3_parity`/`mall_simulator*` 一律**不得**作为 E3/E4 证据来源。权威库 = **`analytics_metric`**（依据：`application.yml:15` 默认值 ＋ 独有 `uk_active_profile` ＋ 15 列结构含 `definition_version`）。

    **【销毁边界·必须遵守】历史重复表属 `persistent-state`，不在任何泳道的「清理」授权内**：
    `analytics_meta`(+`_p103`/`_p105`/`_p105it`/`_v17probe`) 及其他 schema 中的同名 `metric_snapshot`/`metric_value`、`mall_simulator(_test)` 中的同名表，**一律不得**由本任务或后续清理动作执行 `DROP`/`TRUNCATE`/批量 `DELETE`（属不可逆 live-state）。
    本任务**只做只读登记**，未执行也未生成任何破坏性命令；若确需清理，须由用户对**具体库名+表名**给出作用域确认，并附备份/回滚说明（`Anti-Entropy` 判定：`persistent-state` → `confirmation-first`）。当前推荐动作**只有两项非破坏性的**：① 配置层加启动自检（检测所连库是否为权威结构）；② `V25-C01` 订正 DDL 文字。
17. **【风险·中】同一表名两种结构（schema 漂移）已实际存在**：`metric_snapshot` 在 meta 族 12 列 / metric 族 15 列 / 商城族 11 列 ⇒ 若连接串被环境变量改成 `_p105` 等库，应用会**静默读到缺列的表**（`definition_version`、`failure_reason`、`active_flag` 缺失）。属可复现的口径风险，建议由运维/配置泳道加一条启动自检（**本任务只登记，不实现**）。

### 本版仍未取证（随更正同步收缩）
1. ~~§7.4 在 `analytics_metric` 的实际结构~~ → **已取证（本版）**，但**并发原子性**仍未取证（样本 1 档案）。
2. ~~跨库同名表枚举~~ → **已取证（本版，11 个 schema）**。
3. §4.2 五表与 V2.5 §4.2 的**字段级一致性**（列结构/约束）—— 未取证。
4. 旧 229 行指针的语义正确性 —— 未取证（只验可解析性）。
5. T02 的 E2/E3、spark-jobs 真实测试数、S01 交付物 —— 未取证。
6. 本版**未**执行任何 Maven、未启停服务、**未写库**（仅只读 SELECT 与 information_schema）、未改既有证据目录、未 git 提交。

