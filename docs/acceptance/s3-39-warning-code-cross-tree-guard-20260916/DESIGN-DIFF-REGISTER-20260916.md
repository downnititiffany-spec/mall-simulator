# S3-39 设计差异登记：跨树「后端信封告警码 ↔ 前端展示文案」对账守卫（台账 L443 收口 ＋ 两处新登记发现）

> 本轮＝**纯测试新增（A 类）**：1 个新测试文件、0 行生产代码改动、0 行前端源码改动。
> 唯一非测试改动＝`scripts/run-tests.ps1` 的计数基线（956→960）与其口径注释。
> 判类依据见 §6；未测边界见 §7；遗留见 §8。

## §0 本轮主张与证据边界

**主张**：把「后端信封告警码 owner ↔ 前端展示文案表」这条**跨工程**边界用自动化守卫钉住，取代此前「只由前端单测自证」的状态。

**证据是什么**：`metric-analysis` 测试树里新增 `EnvelopeWarningCodeMirrorTest`（4 条用例，281 行），它用**既有** `RepoRoot`（单一 owner，未新增 walk-up 实现）读三份源码文本——
`analytics-server/metric-analysis/src/main/java/com/graduation/analytics/analysis/AnalysisViewModel.java`、
`web/src/utils/envelope.js`、`web/src/utils/context.js`——并以 6 个模块 `src/main/java` 下 203 个 Java 文件为扫描面做常量声明索引。
断言是**源码文本对账**，不是运行时行为。

**证据不是什么**（不得越界表述）：
- **不证**页面实际渲染（`web/node_modules` 不存在 ⇒ 无法 build/跑浏览器；本轮零前端改动 ⇒ 复用 S3-38 的 web 套件基线 118 仅作旁证）；
- **不证**运行时是否真的把某个码发给前端（守卫证的是「两侧码值集合/声明处形态」，不是调用链）；
- **不覆盖**非「常量声明」形态的码（见 §7 R-2）。

## §1 开工前实测（真跑，2026-09-16）

| # | 实测事实 | 命令/定位 |
| --- | --- | --- |
| F1 | 信封告警码 owner ＝ `AnalysisViewModel` 的 **8** 个 `WARN_*` 常量 | L46/49/52/55/65/68/71/74 |
| F2 | 前端 `WARNING_TEXT`（`envelope.js:62-71`）**8 键**，与 F1 码值集合逐字相同；`envelope.js:55-58` 的 KDoc 自称「与后端 `AnalysisViewModel` 的 `WARN_*` 常量一一对应」 | `git grep -n -e WARNING_TEXT -- web/src` |
| F3 | 前端第二张表 `WARNING_TEXT_EXTRA`（`context.js:12-17`）**4 键**（`ENVELOPE_MISSING`/`AI_EVIDENCE_PARTIAL`/`QUALITY_RULE_FAILED`/`NO_SNAPSHOT_SELECTED`），与 F2 **互斥** | 同上 |
| F4 | 展示链：`warningTextAll = WARNING_TEXT[code] \|\| WARNING_TEXT_EXTRA[code] \|\| String(code)`（`context.js:26-28`）⇒ **未知码原样打出**（不静默，但用户看到的是编码） | — |
| F5 | 跨树**无任何**对账守卫：`web/tests/envelope.test.js:127-144` 里 `ENVELOPE_WARNING_CODES` 是**前端镜像清单**（非 owner），后端加第 9 个码不会被发现 | 本轮新增守卫前 |
| F6 | **第二属主**：`ai-decision/.../evidence/EvidencePackage.java:58-76` 自持 **10** 个 `WARN_*` 字面量（不引用 `AnalysisViewModel`），其中 **4** 个与信封码同名（`NO_ACTIVE_SNAPSHOT`/`UNKNOWN_SNAPSHOT`/`UNKNOWN_DIMENSION_TABLE`/`QUALITY_STATUS_UNAVAILABLE`），另 **6** 个是 AI 专属码 | `git grep -n -E 'public static final String WARN_[A-Z0-9_]+ *='` |
| F7 | **第三声明处**：`ai-decision/.../sql/SqlPolicy.java:51` `public static final String NO_ACTIVE_SNAPSHOT = "NO_ACTIVE_SNAPSHOT";`（AI SQL 作用域错误码，无 `WARN_` 前缀）⇒ `NO_ACTIVE_SNAPSHOT` 共 **3** 处各自声明 | 同上 |
| F8 | AI 专属 6 码（`NO_COMPARISON_PERIOD`/`COMPARISON_WINDOW_UNSUPPORTED`/`ADS_READ_UNAVAILABLE`/`MIXED_DEFINITION_VERSIONS`/`TIME_RANGE_IGNORED`/`REQUESTED_PERIOD_NOT_SNAPSHOT_DATE`）在前端两张表里**都没有**键 | 逐键比对 |
| F9 | 但它们**当前并不作为独立码被前端消费**：`context.js:201` 的 AI 证据上下文 `warnings` ＝ `mergeWarnings(explanation.limitations, ['AI_EVIDENCE_PARTIAL'])`（**散文**），后端 `EvidenceTemplates.java:194-196` 把码**嵌进散文**（`"数据缺口：" + w`）⇒ 用户看到的是「数据缺口：ADS_READ_UNAVAILABLE」这类**混合行**，而不是独立编码；后端**码数组**（`evidence.warnings`）在 `web/src` 里无消费点 | `git grep -n -e warnings -- web/src/utils/context.js` |
| F10 | 消费方不复制字面量：信封 8 码在**两个声明类之外**无裸字面量（`AnalysisService`/`RfmService` 等一律引用常量） | `git grep -n -E '"(NO_ACTIVE_SNAPSHOT\|...)"' -- 'analytics-server/*/src/main/*'` |
| F11 | 跨模块重名源文件 **0** 个（203 文件），但「按文件名做键」未来会静默缩水 ⇒ 本轮把键改成「模块/文件名」并在重名时**显式抛错** | 实测分组 |

## §2 可复现命令

```powershell
# 定向（内环，非门禁证据）：JDK17
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -f analytics-server\pom.xml -pl metric-analysis -am test `
  '-Dtest=EnvelopeWarningCodeMirrorTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# 统一门禁（default 档，两轮口径：先量数、改基线、再收口）
& .\scripts\run-tests.ps1 -Suite default -RunId s339-count-1 -LogDir .verify\s339-count-1 -Confirm   # 量数轮（DRIFT）
& .\scripts\run-tests.ps1 -Suite default -RunId s339-final-1 -LogDir .verify\s339-final-1 -Confirm   # 收口轮（MATCH）
```

## §3 本轮冻结口径

1. **信封告警码**＝`AnalysisViewModel.WARN_*` 的**码值**集合（契约 `docs/contracts/analysis-viewmodel-r7-4.md` §16.4：所有新错误码统一 owner ＝ `AnalysisViewModel`）；
2. **展示侧两张表**＝`envelope.js` 的 `WARNING_TEXT`（信封码）＋ `context.js` 的 `WARNING_TEXT_EXTRA`（非信封接口/页面本地码），**互斥**；
3. 守卫只认「常量声明」形态 `public static final String NAME = "CODE";`（`NAME` 以 `WARN_` 开头者计入 owner 侧校验）；
4. 已存在的重复声明（F6/F7）**本轮不合并**——把重复「收敛成一份」需要跨模块引用（`ai-decision` → `metric-analysis`）或新公共产物，属架构选择，须先裁决；本轮只把**当前形态钉死**：声明处数量一变即红；
5. 缺口以「形态冻结」登记，**不静默修**（与 S3-14 白名单形态写死同款：修正即红 ⇒ 要求同步删登记）。

## §4 实现面

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/EnvelopeWarningCodeMirrorTest.java` | **新增 281 行**（4 条用例） | 见 §5 |
| `scripts/run-tests.ps1` | `+34/−1`（L111 基线 956→960 ＋ 33 行口径注释） | 计数基线；`+4` 全部落在 metric-analysis |
| 生产代码 | **0 行** | — |
| `web/**` | **0 行**（`git diff 7181652 -- web` 为空，实测） | — |

四条用例：

1. `envelopeOwnerCodesMatchFrontendWarningText`：`WARN_*` 码值集合 **==** `WARNING_TEXT` 键集合（双向，`containsExactlyInAnyOrderElementsOf`）；两侧都断言非空防「空跑」。
2. `frontendWarningTablesAreDisjoint`：两表键交集为空；`WARNING_TEXT_EXTRA` 非空。
3. `displayedCodesKeepRegisteredDeclarationSites`：展示侧 12 个码值的**声明处数量**＝已登记形态（`NO_ACTIVE_SNAPSHOT`×3；`UNKNOWN_SNAPSHOT`/`UNKNOWN_DIMENSION_TABLE`/`QUALITY_STATUS_UNAVAILABLE` 各×2；`RFM_*`×3＋`MULTIPLE_RULE_VERSIONS`＋`QUALITY_RULE_FAILED` 各×1；3 个页面本地码各 0）；失败消息打印实际声明处，便于定位。
4. `extractorsHaveTeeth`：解析器自检——owner 新增码、非 `WARN_` 前缀同名声明不被误当信封码、前端表漏键/多键、**表名找不到必须显式抛错**（禁止静默返回空集）、重复声明索引不串码。

定点加固：扫描面键＝「模块/文件名」，重名时 `IllegalStateException`（F11），避免同名文件互相覆盖使扫描面**悄悄缩水**。

## §5 证据矩阵（真跑）

| 项 | 证据 | 结果 |
| --- | --- | --- |
| 定向 GREEN（首轮） | 内环 maven，`Tests run: 4, Failures: 0, Errors: 0` | `BUILD SUCCESS` |
| **RED 探针 P1**（真实被测物：owner 加第 9 个 `WARN_PROBE_ONLY`） | 内环 maven | `Tests run: 4, Failures: 1` ＝**仅**用例 1 红（含实际键集合回显） |
| **RED 探针 P3+P4**（`WARNING_TEXT_EXTRA` 塞入信封码 ＋ `EvidencePackage` 再复制一个 `RFM_PERIOD_UNAVAILABLE`） | 内环 maven | `Tests run: 4, Failures: 2` ＝用例 2、3 红 |
| 探针复原 | `git checkout --` 三文件后 `Get-FileHash` 与探针前**逐字相同** | ✅（P1 探针：`AnalysisViewModel.java`；P3+P4：`context.js`＋`EvidencePackage.java`） |
| 复原后定向复跑 | 内环 maven | `Tests run: 4, Failures: 0` ／`BUILD SUCCESS` |
| **量数轮** `s339-count-1` | `.verify/s339-count-1/` | analytics-server `960 (F=1 E=0 S=1)` 明细 `93+350+169+**97**+94+157` ⇒ `DRIFT(基线 956)`，**+4 全在 metric-analysis（93→97）**；mall 13 MATCH；generator 106 MATCH；三棵树 1079（基线 1075）；`[FAIL exit=7]`，唯一红＝已登记环境性用例 ⇒ **只作量数依据，不作通过证据** |
| **收口轮** `s339-final-1` | `.verify/s339-final-1/` | analytics-server `960 MATCH`、mall `13 MATCH`、generator `106 MATCH`、三棵树 `1079 MATCH`；`[FAIL exit=7]`，唯一红仍＝`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61`（`expected: 43 but was: 0`，**已登记环境性红，未修、未复制 manifest、未用开关掩盖**） |

计数基线：`analytics-server 956→960`（metric-analysis 93→97）、**三棵树 1075→1079**；其余五模块 `93/350/169/94/157` 未变。

## §6 类别判定与 11 门逐门核对

**判 A 类**（IMPLEMENTATION/ADDITIVE：纯测试新增；无生产改动、无 DDL/迁移、零连库）。

| 门 | 是否触及 | 依据 |
| --- | --- | --- |
| ①DROP TABLE/COLUMN | 否 | 无 DDL |
| ②改已有字段类型/既有业务语义 | 否 | 未改任何生产代码，未改任何响应字段 |
| ③改已发布 Flyway migration | 否 | 未碰迁移 |
| ④写/迁移正式 3306 数据 | 否 | 零连库 |
| ⑤切 ACTIVE | 否 | — |
| ⑥改 `contract-specs/**` 已有契约语义 | 否 | 未触碰（`git diff 7181652 -- contract-specs` 为空） |
| ⑦改 V3.0 总体架构 | 否 | 仅新增测试 |
| ⑧改正式项目范围 | 否 | backlog 行 L443 是本轮指定面 |
| ⑨删除已发布功能 | 否 | 只增不删 |
| ⑩引入 V3.0 未规划大型基础组件 | 否 | 复用**既有** `RepoRoot`（`platform-common` test-jar 已在 `metric-analysis/pom.xml` 依赖内）；未新增 walk-up 实现 |
| ⑪两种方案造成重大长期架构分叉 | 否 | 未做架构选择；F6/F7 的「合并重复」**明确留待裁决**（§8） |

## §7 未测与边界（不得越界表述）

- **R-1** 守卫只证**源码文本**两侧一致；**页面实际渲染未验**（`web/node_modules` 不存在）。
- **R-2** 只认常量声明形态 ⇒ 若有人在 `warnings` 列表里直接写字符串字面量，守卫**看不见**。本轮已用字面量扫描（F10）确认**当前**信封 8 码无裸字面量散落，但这是**一次实测**，不是持续保证。
- **R-3** 本轮零前端改动（实测 `git diff 7181652 -- web` 为空）⇒ web 套件基线 **118**（S3-38）**原样复用**，本轮**未重跑** web 套件。
- **R-4** 已登记缺口（F6/F7/F8/F9）**未修**：不合并重复声明、不给 AI 专属 6 码补中文文案、不改后端散文。
- **R-5** 用例 3 是「缺口形态冻结」：**预期**在新增信封码/AI 码时变红 —— 那不是回归，而是要求同步裁决并更新本守卫与登记（与 S3-14 白名单同款设计）。
- **R-6** 守卫不校验**码值语义**（如 `QUALITY_RULE_FAILED` 在前端是「质量规则未通过」文案、在后端是 `AnomalyRules` 的**异常候选规则码**，两者是否同指**未经裁决**）——只钉声明处数量。

## §8 遗留（不删行、不改判类）

1. **F6/F7 重复声明**：同一码值 3～2 处各自声明。合并需跨模块引用或公共常量产物 ⇒ **待裁决**（新台账行，判类待定：并入 `AnalysisViewModel` 会让 `ai-decision` 依赖 `metric-analysis`，属架构选择，门⑩邻域，**本轮不动手**）。
2. **F8/F9 AI 专属 6 码无中文文案**：当前**未以独立码形态被消费**（只嵌在后端散文里）。补文案前必须先裁决「AI 证据包的码由谁展示」＝口径问题；本轮**不按猜测写语义**。
3. **后端散文内嵌原始码**（`EvidenceTemplates.java:194-196`「数据缺口：<码>」）：用户可见的降级行里是英文码。改它属**改既有响应文案** ⇒ 本轮不动，登记待批注。
4. **前端镜像清单**（`web/tests/envelope.test.js:127-144`）仍是第二份码清单；本轮守卫从**后端 owner 侧**对账，未删除该镜像（删除属另一轮）。

## §9 反熵声明

- **未新增第三份「码清单」作为 owner**：前端两张表的键被**读取**而非复制；守卫内的 12 项期望是**缺口形态冻结**（§7 R-5），不是语义 owner。
- **未擅自合并重复属主**（§8-1）、**未按猜测补文案语义**（§8-2）、**未改后端文案**（§8-3）。
- **扫描面防空跑**：三个防线 —— 两侧集合非空断言、`sources.size() > 100`、表名找不到**显式抛错**；并把「文件名做键」改成「模块/文件名＋重名抛错」（F11）以杜绝**扫描面缩水**。

## §10 顺带台账操作（`docs/PROJECT_STATUS.md`，不删行、不改判类）

1. **L443 更新描述**：本行「A 类候选」所指的守卫**已实施**（提交号见 `docs/status-history/开发过程事实与决策记录.md` F-72），并据实记录「做了哪些、没做哪些」。
2. **新增 1 行**：F6/F7/F8 的「同一码值多处声明 ＋ AI 专属码无展示文案」登记（不改任何既有行的判类）。
3. **滚动执行位置**：更新为 S3-39 后（S3-38 本轮事实已入 F-71，历史不丢）。
