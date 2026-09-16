# S3-49 登记件：「Spark 规则码字面量 ↔ 登记集」跨模块漂移门禁

- 日期：2026-09-16
- 轮次：V3.0 持续执行模式 第 10 轮（`<goal_round>` 10/40，目标 `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支 / worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`057f3d432edb7ee4766e552b9abe890be482d927`（＝ `origin/feature/v3-development`）
- 代码提交：`c41f340bedcf3f9492334862a19a696cbf94ea74`
- 来源：`docs/PROJECT_STATUS.md` backlog 行 L496「**「Spark 规则码字面量 ↔ 登记集」没有自动守卫**」（原文出处 `PROJECT_STATUS:211`）
- 类别判定：**A 类（加性）** —— 新增 2 个测试/测试支撑类（`platform-common` 测试树）＋ 1 个**加性生产只读访问器**（`RuleSeverity.registeredCodes()`）＋ 测试树一处可见性放宽 ＋ 门禁基线数字同步；零契约变更、零 DB/迁移、零 Spring 装配、零命令语义改动
- 交付面：`analytics-server/platform-common`（测试树为主）＋ `scripts/run-tests.ps1`（基线数字与说明块）

---

## §1 开工前实测（改前事实，全部本轮实跑取得）

| # | 事实 | 实测值 | 取证方式 |
|---|---|---|---|
| 1 | 是否存在任何测试扫过 Spark 侧规则码 | **不存在**（全仓 grep 无 Spark + 规则码集合核对） | 测试树全量检索 ＋ `SparkRuleCodeRegistryGuardTest` 落地前编译期即无同类类 |
| 2 | 规则码唯一所有者 | `RuleSeverity.REGISTERED`（`platform-common` 生产类，L74-94，`Set.of` 共 **38** 码） | 读源码 ＋ `RuleSeverityTest.ALL_REGISTERED_CODES` 断言（16+16+3+3） |
| 3 | 登记表可枚举性（改前） | **无**公开枚举入口（只有 `registered(String)` 布尔判定） | 读源码 |
| 4 | Spark 生产树规模 | `spark-jobs/src/main/scala` 下 **36** 个 `.scala` | 词法剥离后逐文件统计 |
| 5 | Spark 大写字面量（去重） | **52** 个大写 token | 词法剥离（逐行注释/块注释/字符串感知）后正则提取 |
| 6 | 其中「规则码」字面量 | **21** 个（去重）／**24** 处站点 | 同上 ＋ 与 `RuleSeverity.REGISTERED` 交集 |
| 7 | 规则码站点写法分布 | `QualityCheck("<CODE>"` 首参 **20** 处；**另有两处非 `QualityCheck` 写法**：`AdsQualityJob.scala:94` 的 `Set("AMOUNT_RECONCILE","REQUIRED_FIELD_NULL_RATE","ENUM_WHITELIST")`（3 码）与 `:107` 的 `byRule.get("EVENT_ID_UNIQUE")`（1 码） | 逐站点定位 |
| 8 | 前缀族（Spark 承载）覆盖 | 登记表中 `ADS_`/`DWS_`/`PUB_`/`MXP_` 共 **17** 码，**全部**在 Spark 有站点（双向一致） | 集合比对 |
| 9 | 非规则大写 token | **31** 个（层/阶段名、严重度、类型字面量、业务枚举、解析器错误码、命名约束常量） | 同上，逐类给出处（见 §3） |
| 10 | 历史缺口 | S3-10 的 `ADS_DWS_FUNNEL_RATE_RECONCILE` 漏登记进读侧登记表，读侧按 §7.3.1 判「未登记规则」**拒发**，当时是**人工**发现 | backlog 行原文 ＋ 历史记录 |
| 11 | 同族另一半是否已收口 | 已由 **S3-30** 收口（`MetricAdsSpecTest` 硬编码 Java 列清单镜像 ⇒ 改读唯一所有者） | 既有登记件 |
| 12 | 定向真跑（改前基线） | `WarehouseNameLiteralGateTest` 9/9、`RuleSeverityTest` 14/14 | 本轮实跑 |

---

## §2 11 项 HARD DECISION GATE 逐项判定

| Gate | 判定 | 依据 |
|---|---|---|
| ① DROP TABLE/COLUMN | **未触发** | 无任何 DDL 语句改动 |
| ② 改已有字段类型或既有业务语义 | **未触发** | 唯一生产改动是**新增**只读访问器；`RuleSeverity.registered`/`of`/`UNREGISTERED` 语义一字未动（`RuleSeverityTest` 14/14 复核） |
| ③ 改已发布 Flyway migration | **未触发** | 未触碰任何 `V*.sql` |
| ④ 写/迁移正式 3306 数据 | **未触发** | 无任何连库代码、无 DB 写入；本守卫纯文本扫描 |
| ⑤ 切 ACTIVE | **未触发** | 未触碰 source/mapping ACTIVE 指针 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **未触发** | `contract-specs/**` 零改动 |
| ⑦ 改 V3.0 总体架构 | **未触发** | 无新组件、无新进程、无分层调整；新增一个静态访问器 |
| ⑧ 改正式项目范围 | **未触发** | 只补既有 backlog 行的自动守卫 |
| ⑨ 删除已发布功能 | **未触发** | 无删除；非规则 token 表是**新增**显式表 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **未触发** | 零新依赖（仅用 JDK 现有 `java.util.regex`/`java.nio.file`） |
| ⑪ 两方案造成重大长期架构分叉 | **未触发** | 方案唯一：读唯一所有者；未引入第二份码表 |

**结论：11 门全部未触发 ⇒ 本轮为 A 类，可自主实施，无需裁决。**

---

## §3 口径（先说清"什么算规则码"，再说判据）

- **规则码的唯一所有者**：`com.graduation.analytics.metric.RuleSeverity.REGISTERED`（Java 侧）。Spark 侧只是**执行者**，它写的码必须落在该集合内，否则读侧按 V2.5 §7.3.1 判「未登记规则」而拒发。
- **扫描面**：`spark-jobs/src/main/scala/**/*.scala`（生产树，**不含** `src/test`）。测试里的码是断言期望值，不是第二处所有者 —— 与库名门禁（`WarehouseNameLiteralGateTest`）同一口径。
- **提取口径（刻意取宽）**：字符串字面量内容匹配 `^[A-Z][A-Z0-9_]{3,}$` 即记一处（保留 `file:line`）。**不采用**「只抓 `QualityCheck(` 首参」的窄口径：实测存在两类非 `QualityCheck` 站点（§1 第 7 项），窄口径会漏掉它们，而这两处写的码同样会进库、同样会被读侧判定。
- **注释剥离复用唯一实现**：`WarehouseNameLiteralScanner.CommentSyntax.strip(text, SLASH)`。**不另写**第二份剥离器 —— 两套剥离口径本身就是新的第二所有者。剥离只把注释区间替换为空格，行号不变，故命中位置可直接引用。
- **非规则 token 表（显式、闭集、每条须仍在源码出现）** 分五类共 31 条：
  1. 层/阶段名：`ADS_STAGING`（`QualityCheck` 第二实参的 stage 名）、`PUBLISH`；
  2. 严重度/结论：`BLOCKING`/`ERROR`/`INFO`/`WARN`/`SUCCESS`/`FAILED`/`COMPLETED`/`CANCELLED`；
  3. 类型/字面量：`STRING`/`BIGINT`/`NULL`/`HASH64`；
  4. 业务枚举（Landing 清洗状态机取值）：`CREATED`/`PAID`/`REFUNDED`/`REFUNDING`；
  5. 解析器错误码与命名约束常量：`PAYLOAD_*` ×8、`EMPTY_LINE`、`WAREHOUSE_PREFIX_*` ×4。
  这张表**不是**第二份规则码清单：规则码所有者仍是 `RuleSeverity.REGISTERED`；本表只声明「哪些 token **不是**规则码」，且受判据②约束（不得陈旧）。
- **逃生舱**：`REGISTERED_WITHOUT_SPARK_SITE`（**今日为空**）。留口子是 fail-closed 的必要条件：真出现「Java 侧读侧规则」时必须**显式登记豁免并写明理由**，而不是让守卫去猜；豁免项自身也受反向检查（必须已登记，且**确实不在** Spark 里）。
- **量数口径**：默认档三棵树各档独立计数、不合并（沿用总控 2026-09-15 裁决）。`spark`/`isolated` 档本轮**未重跑**。

---

## §4 实现面（5 个文件）

| 文件 | 改动 | 规模 |
|---|---|---|
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/SparkRuleCodeScan.java` | **新增**（测试支撑）：扫描面枚举、词法剥离复用、宽口径提取、`Lit(file,line,code)`、去重与计数 | 130 行 / 6468 字节 / 纯 LF |
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/SparkRuleCodeRegistryGuardTest.java` | **新增**（守卫）：4 条用例 ＋ 非规则 token 表（31）＋ 逃生舱（0）＋ 非空性标尺 | 239 行 / 15204 字节 / 纯 LF |
| `analytics-server/platform-common/src/main/java/com/graduation/analytics/metric/RuleSeverity.java` | **加性**：新增 `public static Set<String> registeredCodes()`（唯一所有者只读暴露，含语义边界 javadoc） | +17 / −0 |
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralScanner.java` | 类/`CommentSyntax`/`strip` 可见性放宽到 `public`（**仅测试树**，javadoc 写明原因与边界） | +15 / −4 |
| `scripts/run-tests.ps1` | 基线 `analytics-server` 990→994 ＋ S3-49 说明块（39 行）；命令语义未动 | +39 / −1（1036 行） |

**TDD 顺序（经典 RED → GREEN）**：先写引用 `RuleSeverity.registeredCodes()` 的守卫 ⇒ 编译 RED `SparkRuleCodeRegistryGuardTest.java:[158,45] 找不到符号`（`mvn` 单模块定向，exit=1）⇒ 再加访问器 ⇒ 定向 27/27 绿。

**为什么必须由生产类暴露枚举**：守卫要做**双向**核对。若守卫自己另抄一份码表，就是**再造一个所有者** —— 正是 S3-30/S3-49 要消除的漂移面。

---

## §5 证据

### 5.1 诚实表述（不得越界）

- 本守卫是 **characterization guard**：被守性质（两侧码集合一致）在写断言**之前**就已成立（§1 第 8 项实测量：17/17 前缀族码都有站点，21 个字面量全部已登记）⇒ **首跑即绿**，**没有**"先红后绿"的经典 RED 可用于证明判据有效。**"首跑绿"本身不构成判据有效的证据。**
- 本轮**唯一的经典 RED** 是 TDD 的编译期 RED（访问器不存在），它只证明「生产入口是守卫必需的」，**不证明**判据非恒真。
- 判据的有效性**只**由 §5.2 的 7 条变异探针证明（每条单独注入、单独红、注回后 sha256 复原一致）。
- 本守卫**只证明**「两侧的码字面量集合没有各说各话」；**不证明**规则语义正确、阈值合理、真集群上这些规则真的会跑。
- 收口轮 `s349-final` 仍是 `[FAIL exit=7]`（唯一红＝已登记环境性用例）⇒ **不得**表述为"门禁已通过"。

### 5.2 变异探针（7 条，逐条单独注入）

方法：字节级备份 → 唯一锚点计数断言（必须 =1，否则记「探针未执行、不得据此声称结论」）→ `Replace` → UTF-8 无 BOM 写回 → 单类定向 maven 跑 → 解析失败方法名 → 字节还原 → sha256 复核。

| 探针 | 注入 | 实测红 | 与预期 |
|---|---|---|---|
| P1 | Spark 新增一处未登记码站点（`AdsPublishJob.scala`） | **仅**判据①（报错带 `spark-jobs/.../AdsPublishJob.scala:53 -> S349_PROBE_NEW_CODE` 定位） | 符合 |
| P2 | Spark 侧把已登记码改名（`MXP_EXPORT_ROWS`→`MXP_EXPORT_ROWS_S349`） | 判据①＋③ | 符合 |
| P3 | 把非规则 token `BLOCKING` 从表中删掉（模拟「新出现大写 token 未列入」） | **仅**判据① | 符合 |
| P4 | 把真实站点整行注释掉（`MetricExportJob.scala`） | **仅**判据③（正向**不红** ⇒ 注释被词法排除，端到端生效） | 符合 |
| P5 | 非规则 token 表新增源码里不存在的陈旧项 | **仅**判据② | 符合 |
| P6 | 逃生舱塞入一个其实仍在 Spark 里的码 | **仅**判据③（豁免项陈旧检查） | 符合 |
| P7 | Java 登记表新增一个无站点的 `ADS_` 码 | **仅**判据③（反向判据从**登记侧**同样生效） | 符合 |

- 7/7 探针锚点命中均 =1；7/7 `还原一致=True`；探针结束后 `git status --porcelain` 仅剩本轮 5 个预期文件（**无探针残留**）。
- 探针日志：`.verify/s349-probe-P1..P7.log`；结果汇总：`.verify/s349-probes-result.log`；定义：`.verify/s349-probes.json`；脚本：`.verify/s349_probes.ps1`。
- **探针口径的诚实说明**：P3 不是"在 Spark 里真的新写一个 token"，而是"把已有非规则 token 的表项删掉" —— 两者对判据①的输入等价（该 token 变成「未登记且不在表里」）。这样做的原因是避免向 Scala 源码注入无意义文本；结论「新出现的未允许大写 token 会让判据①红」由 P1（真实新增未登记码站点直接红）与 P3 共同支撑。

### 5.3 门禁（默认档，各档独立计数）

| 轮次 | RunId | 命令语义 | 结果 |
|---|---|---|---|
| 量数轮 | `s349-1` | `-Suite default` | analytics-server **994** (F=1 E=0 S=1) 明细 `97+353+172+97+118+157` ⇒ **DRIFT(基线 990)**，**+4 全部落在 platform-common**（93→97，其余模块一字未变）；mall-simulator 13 MATCH；generator 110 MATCH；三棵树 **1117**（基线 1113）⇒ 记 FAIL，**只作量数依据、不作通过证据** |
| 基线同步 | — | `run-tests.ps1` 990→994 | 仅数字＋说明块；AST 解析 0 错误；CRLF=1035、裸 LF=0、行数 1036 |
| 收口轮 | `s349-final` | `-Suite default` | analytics-server **994 MATCH**、mall **13 MATCH**、generator **110 MATCH**、三棵树 **1117 = 基线**；唯一红仍为**已登记环境性用例** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（`expected: 43 but was: 0`，成因 `landing/` 被 `.gitignore` 排除）⇒ `[FAIL exit=7]` |

- 定向真跑（收口用）：`SparkRuleCodeRegistryGuardTest` 4/4、`RuleSeverityTest` 14/14、`WarehouseNameLiteralGateTest` 9/9 ⇒ 合计 **27/27，BUILD SUCCESS**。
- `spark`（308）／`isolated`（mall 30/generator 19/analytics 6）档**本轮未重跑**：新用例无 `@Tag("it")`，不在 isolated 选择面内；未改动任何 Spark 生产代码。

---

## §6 契约变更

**无。** `contract-specs/**` 零改动（该目录只读）；`docs/contracts/**` 零改动 —— 新增的 `RuleSeverity.registeredCodes()` 是**同模块内**的只读枚举入口，不构成外部契约；无 API/DTO/表结构/迁移变更。

---

## §7 未测与边界（不得夸大）

1. **扫描面边界**：只覆盖 `spark-jobs/src/main/scala` 的**双引号整串**大写 token。拼接码（`s"$prefix_CODE"`）、`src/main/resources`、`src/test` 树**未覆盖**（实测生产树无拼接码写法，但守卫本身对此无判据）。
2. **不做语法解析**：只做词法剥注释 ＋ 文本扫描，不解析 Scala 语法；「某字面量是不是规则码」的判断权在显式非规则 token 表，守卫不做猜测。
3. **不证明运行期行为**：不证明规则语义/阈值正确，不证明真集群上这些规则真的会跑（`spark` 档未重跑）。
4. **`registeredCodes()` 语义边界**：返回**代码内默认目录**（与 `registered(String)` 同口径），**不代表**某次 run 的冻结规则集（冻结口径属 `QualityRuleCatalog.FrozenRules`）。
5. **未测项**：`isolated` 档、真库/真集群、web 前端（范围外且 `web/node_modules` 缺失）均未测。
6. **量数轮 FAIL 的性质**：`s349-1` 的 FAIL 是**计数漂移**导致，不是新缺陷；收口轮计数 MATCH 但仍有 1 个已登记环境性红，故两轮**都不是**"全绿"。

---

## §8 顺带台账（登记，不在本轮处理）

1. **`AdsQualityJob.scala:94` 是「哪些 Landing 规则阻断发布」的 Spark 侧第二声明**：`Set("AMOUNT_RECONCILE","REQUIRED_FIELD_NULL_RATE","ENUM_WHITELIST")` 与 Java 侧 `RuleSeverity` 的 BLOCKING 判定**语义重复**。本轮**只登记不合并**（合并＝改 Spark 判定语义，落在门②邻域，需裁决）。本守卫只钉「这三个码是已登记码」，**不钉**它们与 Java 侧阻断集合一致。
2. **测试支撑类可见性放宽**：`WarehouseNameLiteralScanner` 提升为 `public`（仅测试树，不进生产制品），为的是复用**唯一**注释剥离实现。行为未动，由既有 `lexerFamilies` 用例继续钉住。
3. **非规则 token 表是 fail-closed 的显式逃生舱**：P3/P5 实测证明「删表项 ⇒ 判据①红」「加陈旧项 ⇒ 判据②红」，即这张表**不能**被用来悄悄放宽判据。
4. **门禁脚本注释块的体量**：本文件与 `run-tests.ps1` 说明块继续按 S3-01 以来的格式追加；`run-tests.ps1` 已 1036 行，后续轮次可考虑把历史说明块归档到 `docs/acceptance/**`（**本轮不动**，避免与"只改一个数字＋注释"的口径冲突）。

---

## §9 复现命令

```powershell
$wt='D:\Develop_code\GraduationProject-wt\v3-dev'
$env:JAVA_HOME='D:\Develop\JAVA17'

# 1) 定向真跑（守卫 + 受影响的两个既有类）
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f "$wt\analytics-server\pom.xml" -pl platform-common -am test `
  '-Dtest=SparkRuleCodeRegistryGuardTest,RuleSeverityTest,WarehouseNameLiteralGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
# 期望：27/27，BUILD SUCCESS（注意：三个类名必须用逗号分隔；用 '+' 会静默空跑）

# 2) 变异探针（7 条，逐条注入/还原，带唯一锚点断言与 sha256 复核）
& "$wt\.verify\s349_probes.ps1"
# 结果见 .verify\s349-probes-result.log

# 3) 门禁（默认档；收口轮 RunId 见 §5.3）
& "$wt\scripts\run-tests.ps1" -Suite default -RunId s349-final -LogDir "$wt\.verify\s349-final" -Confirm
# 期望：analytics-server 994 MATCH / mall 13 MATCH / generator 110 MATCH / 三棵树 1117=基线；
#       仍 [FAIL exit=7]（唯一红＝已登记环境性用例 IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched）
```
