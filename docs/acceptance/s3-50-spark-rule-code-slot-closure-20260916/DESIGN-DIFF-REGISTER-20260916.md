# S3-50 登记件：S3-49 守卫的**逃逸面**收口（槽位形态闭集 ＋ 调用点静态首参）

- 轮次：S3-50（阶段6 反熵 · 后继项）
- 日期：2026-09-16
- 工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）
- 开工前 HEAD：`0e56a28455cf9cdb323db1a798ab46aab934ba5f`（＝ `origin/feature/v3-development`）
- 差异类别：**A 类（纯加性 / 测试-only）** —— 零生产代码改动、零契约改动、零 DDL、零迁移、零连库、零新依赖
- 来源：`docs/PROJECT_STATUS.md` backlog **L546**「S3-49 后继残余面」①（拼接码／资源树／`test` 树无判据）②（只做词法扫描，不解析语法）
  ＋ 本轮探针新发现的**真实缺陷**（首版口径被 P3 反例证伪，见 §5.3）

---

## §1 开工前实测（改前事实，全部本轮实跑取得）

> 量数探针 `S350SlotProbeTest`（临时类，量完即删：源文件 ＋ `target/test-classes` 类文件 ＋ surefire 报告**三处**清空，实测残留 **0**）；日志 `.verify/s350-probe-counts2.log`。

- **F1 扫描面规模**：`spark-jobs/src/main/scala` ＝ **36** 个 `.scala`；大写 token 字面量站点 **156** 处；去重 token **52** 个。
- **F2 登记表规模**：`RuleSeverity.registeredCodes()` ＝ **38** 码（S3-49 新增的只读入口，本轮**不改**）。
- **F3 已登记码站点**：**24** 处（去重 **21** 码）＝ 首参 20 ＋ 策略表 3 ＋ 查表 1。
- **F4 `QualityCheck(` 出现点**：**21** ＝ **20 个调用点**（首参**全部**为静态双引号字面量）＋ **1 个类型声明**（`spark-jobs/src/main/scala/com/graduation/analytics/job/JobResult.scala:20` `case class QualityCheck(`）。
- **F5 已登记码的槽位形态分布（宿主口径）**：`{QUALITY_CHECK_ARG=20, STRATEGY_SET=3, MAP_GET=1}`，**UNKNOWN ＝ 0**。
  - 这说明**今日逃逸面为空**，但**改前没有任何判据钉住它** —— S3-49 的判据①只做「整串 token 是否已登记／是否在非规则表」的**闭集**判定，形态与首参**不在判据面内**。
- **F6 24 个已登记槽位逐条（file:line → code［宿主 ⇒ 形态］）**：
  - `AdsPublishJob.scala`：`:53 PUB_STAGING_READY`、`:81 PUB_FORMAL_PARTITION_MATCH`、`:85 PUB_POINTER_SWITCH`、`:111 PUB_STAGING_PRUNE`［`QualityCheck` ⇒ 首参］
  - `AdsQualityJob.scala`：`:58 ADS_STAGING_PRESENT`、`:68 ADS_STAGING_SNAPSHOT_ISOLATION`、`:89 ADS_STAGING_KEY_NOT_NULL`、`:102 PUB_DQ_BLOCKING_RULES`、`:108 PUB_DQ_EVENT_ID_UNIQUE`、`:126 ADS_DWS_FUNNEL_RECONCILE`、`:263 ADS_DWS_FUNNEL_RATE_RECONCILE`、`:318/:327 ADS_GMV_NET_SALE_INVARIANT`、`:380/:389 ADS_UV_PV_INVARIANT`、`:452/:463 DWS_UV_PV_INVARIANT`［`QualityCheck` ⇒ 首参］
  - `AdsQualityJob.scala:94`：`AMOUNT_RECONCILE`、`REQUIRED_FIELD_NULL_RATE`、`ENUM_WHITELIST`［`Set` ⇒ 策略表］
  - `AdsQualityJob.scala:107`：`EVENT_ID_UNIQUE`［`byRule.get` ⇒ 按码查表］
  - `MetricExportJob.scala`：`:61 MXP_SNAPSHOT_PINNED`、`:85 MXP_EXPORT_ROWS`、`:96 MXP_EXPORT_COMPLETE`［`QualityCheck` ⇒ 首参］
- **F7 覆盖边界（本轮实测收口，不是推测）**：
  - `spark-jobs/src/main/resources` **不存在**、`spark-jobs/src/test/resources` **不存在**（各 0 文件）⇒ 这两棵树**无覆盖对象**（空集），不是「未覆盖」而是「不存在」。
  - `spark-jobs/src/test`（Scala 测试树）＝ **39** 个 `.scala`、大写 token 命中 **389** 处、去重 **97** 个 token（多为夹具码/期望值）⇒ **刻意排除**（与库名门禁同口径：测试里的码是**断言期望值**，不是第二处所有者）。
- **F8 插值字面量普查**（用于否掉两个被考虑的方案，见 §3）：插值字面量 **365** 处（单引号 `s"` **307** ＋ 三引号 `s"""` **58**）。
- **F9 改前门禁基线**：analytics-server **994**（明细 97＋353＋172＋97＋118＋157）／mall-simulator **13**／synthetic-data-generator **110**；三棵树合计 **1117**。
- **F10 改前定向真跑**：`SparkRuleCodeRegistryGuardTest` 4/4 ＋ `RuleSeverityTest` 14/14 ＋ `WarehouseNameLiteralGateTest` 9/9 ⇒ **27/27**、`BUILD SUCCESS`。

---

## §2 11 项 HARD DECISION GATE 逐项判定

| # | 门 | 判定 | 依据 |
|---|---|---|---|
| ① | `DROP TABLE/COLUMN` | **不触** | 无任何 DDL 改动（`git diff` 生产树 0 命中） |
| ② | 改已有字段类型或既有业务语义 | **不触** | 只加测试判据；生产判定语义一字未改（`RuleSeverity` 未动） |
| ③ | 改已发布 Flyway migration | **不触** | 未触及 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **不触** | 零连库（本轮无 `@SpringBootTest`、无 JDBC） |
| ⑤ | 切 ACTIVE | **不触** | 未触及 ACTIVE 指针 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **不触** | 契约面 0 命中（见 §6） |
| ⑦ | 改 V3.0 总体架构 | **不触** | 只加测试支撑类 |
| ⑧ | 改正式项目范围 | **不触** | 是 S3-49 已立守卫的**逃逸面收口**，来源为已登记 backlog 行 |
| ⑨ | 删除已发布功能 | **不触** | 无删除（`+298/-5` 中 `-5` 全为 javadoc/常量注释改写） |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **不触** | 零新依赖 |
| ⑪ | 两种方案造成重大长期架构分叉 | **不触** | 见 §3：被否方案是**同一判据的两种实现口径**，改口径不产生架构分叉 |

> **唯一有「待裁决」味道的事**：`AdsQualityJob.scala:94` 的策略表是「哪些 Landing 规则阻断发布」的 Spark 侧**第二声明**（Java 侧 `RuleSeverity` 为所有者）。本轮**只登记不合并**（合并＝改 Spark 判定语义，属门②邻域），见 §8。

---

## §3 口径（本轮冻结）

### 3.1 两条新判据（S3-49 判据①②③不变，新判据编号 **④⑤**）

- **判据④ `qualityCheckCallSitesTakeStaticLiteralFirstArgs()`**：`spark-jobs/src/main/scala/**` 内**每一个** `QualityCheck(` **调用点**的首参必须是**静态双引号字面量**（允许换行/缩进，只要跳过空白后的第一个字符是 `"`）；且**类型声明**（`case class QualityCheck(`）必须被**单独认出来**，否则定义点会被算成调用点、判据失真。
  - 非空性标尺：出现点 ≥ **21**、调用点 ≥ **20**（实测恰为 21／20）。
- **判据⑤ `registeredCodeLiteralSitesHaveOnlyKnownSlotForms()`**：**已登记规则码**的字面量只能落在三类**已清点槽位**：
  1. `QualityCheck("CODE", …)` 首参（实测 **20**）
  2. 策略表 `Set("CODE", …)`（实测 **3**，`AdsQualityJob.scala:94`）
  3. 按码查表 `map.get("CODE")`（实测 **1**，`AdsQualityJob.scala:107`）

  第四种形态（含把码塞进 `Map(...)`、把首参改成动态构码等）**即红**；此外**首参槽位数必须等于调用点数**（两次**独立**扫描必须自洽 —— 防「两条判据各自对、合起来漏」）；策略表/按码查表槽位各有**实测下限**（3／1）。

### 3.2 「宿主调用」的判定方式（本轮的关键实现口径）

**平衡括号回扫**：从字面量向左扫描，遇 `)` 深度 ＋1，遇**深度 0 的 `(`** 即宿主调用的左括号，取其前面的**被调名**（可带限定名，如 `byRule.get`）；被调名**精确匹配**才归入形态：

| 被调名 | 形态 | 说明 |
|---|---|---|
| `QualityCheck` | `QUALITY_CHECK_ARG` | 精确匹配；`QualityCheckRow` 等不算 |
| `Set` | `STRATEGY_SET` | **精确匹配**；`ruleSet(`／`Set.apply(`／`Set.map(` 都**不算** |
| `*.get` | `MAP_GET` | 限定名后缀 `.get` |
| 其它 | `UNKNOWN` | 已登记码落在 UNKNOWN ⇒ 判据⑤红 |

### 3.3 为什么不是「固定窗口内最近锚点」（**这是被探针证伪的首版口径**）

首版实现＝「字面量前 120 字符窗口内最后出现的锚点形态」。**P3 探针实测证伪**（详见 §5.3）：
`AdsQualityJob.scala:107` 的 `byRule.get("EVENT_ID_UNIQUE")` 之前约 **95** 字符处还有一个**无关的** `byRule.get(r)`（L106 消息拼接里），窗口口径把那个 `.get(` 当成宿主 ⇒ 「把查表键改成动态构码」这条变异**不红**。
⇒ 改为「平衡括号回扫 ＋ 精确被调名」后，P2/P3/P6/P7 才按预期红。**这是本轮最实的收益：探针不是走形式，它抓到了判据本身的假阴性。**

### 3.4 被否掉的三个候选口径（含实测反例，避免后人重走）

1. **「插值字面量不得拼接标识符」** ⇒ **否**：全仓实测该判定会**误报**。`AdsSql.scala:23` 的 `s"${table}__staging"` 拼接的是**表名**（`def staging(ns, table) = ns.table("ads", s"${table}__staging")`），不是规则码；且用 `-match`（大小写不敏感）会额外误命中 9 处，须 `-cmatch`。判「拼接＝违规」会制造**假 RED**，且会迫使合法表名拼接改造 ⇒ 收益为负。
2. **「插值字面量文本不得含已登记码」** ⇒ **否**：`AdsQualityJob.scala:384` 的消息文本 `s"…NULL 由 ADS_STAGING_KEY_NOT_NULL 判定，本规则不重复判定）"` **提到**了一个码（并非构造码）⇒ 该判定今日即为 RED，属**假 RED**。
3. **「窄口径：只抓 `QualityCheck(` 首参」**（S3-49 原始候选）⇒ 已在 S3-49 否掉（会漏策略表与查表共 4 处站点），本轮继续沿用**宽口径**并把这两类**显式命名**。

### 3.5 探针纪律（沿用 S3-49）

每个变异探针必须：**字节备份 → 唯一锚点命中数断言（必须 ＝ 1，否则明写「探针未执行」）→ 替换 → UTF-8 无 BOM 写回 → 定向真跑 → 解析**失败用例名** → 字节还原 → sha256 复核（`还原一致=True`）→ `git status` 无残留**。

---

## §4 实现面（3 个文件，`+298/-5`）

| 文件 | 变更 | 说明 |
|---|---|---|
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/SparkRuleCodeScan.java` | **+166/-6** | 测试支撑（**测试树**）：新增槽位清点 `slots(Path)`／宿主回扫 `hostOf`／精确形态映射 `formOfHost`／调用点扫描 `callSites(Path)`（含 `staticLiteral` 与 `typeDeclaration` 标注）／`lineAt`；`Slot` 记录升级为含 `host`；**类 javadoc 写明 P3 缺陷与口径修正** |
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/SparkRuleCodeRegistryGuardTest.java` | **+95/-4** | 新增判据④⑤ ＋ 5 个非空性标尺常量（`MIN_QUALITY_CHECK_OCCURRENCES=21`／`MIN_QUALITY_CHECK_CALL_SITES=20`／`MIN_REGISTERED_SLOTS=24`／`MIN_STRATEGY_SET_SLOTS=3`／`MIN_MAP_GET_SLOTS=1`）；javadoc 由「三条判据」改为「**五条判据（缺一不可）**」 |
| `scripts/run-tests.ps1` | **+42/-1** | 门禁基线 `analytics-server` **994→996** ＋ S3-50 说明块（1035→**1075** 行，CRLF 1075／裸 LF 0；**未改任何命令语义**） |

**零生产代码改动**（本节的可验证含义）：
```powershell
git diff --stat -- spark-jobs analytics-server/*/src/main   # ⇒ 空
```
（`RuleSeverity`、`SparkRuleCodeScan` 之外的任何生产文件均未进入本轮 diff；S3-49 引入的 `RuleSeverity.registeredCodes()` 本轮**只读复用**。）

---

## §5 证据

### 5.1 诚实表述（不得越界）

- **本轮无经典 RED**（与 S3-49 同性质）：被守的性质（20/20 静态首参、24 处已登记码全在三类槽位内、已登记槽位 UNKNOWN ＝ 0）在**写断言之前就已成立** ⇒ 属 **characterization guard**，**首跑即绿不构成判据有效的证据**；**不得**表述为「先失败后通过」。
- 判据的**非恒真性**只由 **§5.3 的 7 条变异探针**（逐条单独注入、逐条还原）支撑。
- 收口轮门禁为 `[FAIL exit=7]`（唯一红＝**已登记环境性用例**，见 §5.4）⇒ **不得**表述「门禁已通过」，只能表述为「计数 MATCH ＋ 唯一红为已登记环境性用例」。

### 5.2 定向真跑（改后）

```
mvn -o -pl platform-common -am test -Dtest=SparkRuleCodeRegistryGuardTest,RuleSeverityTest,WarehouseNameLiteralGateTest
⇒ Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
   守卫 6 条（S3-49 的 4 条 ＋ 本轮 2 条）／RuleSeverityTest 14／WarehouseNameLiteralGateTest 9
```
日志：`.verify/s350-targeted-2.log`。（记一笔：`-Dtest` 多类名必须用**逗号**分隔，用 `+` 会 0 用例静默空跑，且被 `-Dsurefire.failIfNoSpecifiedTests=false` 掩盖。）

### 5.3 变异探针（7 条，逐条单独注入；`.verify/s350-probes.json` ＋ `s350-probe-P*.log`）

| 探针 | 注入 | 期望 | 实测（红＝失败用例名） | 锚点命中 | 还原一致 |
|---|---|---|---|---|---|
| P1 | `QualityCheck("PUB_STAGING_READY"…` → 首参动态构码 | 判据④红 | **④＋⑤红**（`qualityCheckCallSitesTakeStaticLiteralFirstArgs`、`registeredCodeLiteralSitesHaveOnlyKnownSlotForms`） | 1 | True |
| P2 | `Set("AMOUNT_RECONCILE"…` → `Set.apply("AMOUNT_RECONCILE"…` | 判据⑤红 | **仅⑤红** | 1 | True |
| P3 | `byRule.get("EVENT_ID_UNIQUE")` → `byRule.apply(…)` | 判据⑤红 | **仅⑤红**（**首版口径下为绿 ⇒ 缺陷**） | 1 | True |
| P4 | `case class QualityCheck(` → 改名 | 判据④红 | **仅④红** | 1 | True |
| P5 | 首参**换行**（`QualityCheck(\n  "PUB_STAGING_READY"…`） | **反例：不红** | **绿**（6/6，`Failures: 0`） | 1 | True |
| P6 | 新增第四种形态 `Map("PUB_STAGING_PRUNE" -> 1)` | 判据⑤红 | **仅⑤红** | 1 | True |
| P7 | 策略表 3 码**整块注释掉** | 判据⑤红 | **仅⑤红**（同时证明剥注释生效） | 1 | True |

- P1 的双红是**预期内的耦合**：把首参改成动态构码后，该字面量的**宿主**也随之变成 `constructedRuleCode` ⇒ 判据⑤同时红。**如实记录，不修饰**。
- P2／P3／P6／P7 只红⑤、P4 只红④ ⇒ 两条判据**互不掩盖**。
- **P3 是本轮探针发现的真实缺陷**：首版「窗口最近锚点」口径下 P3 为**绿**（假阴性）；
  `AdsQualityJob.scala:106` 的 `byRule.get(r)` 距 `:107` 的 `"EVENT_ID_UNIQUE"` 约 **95** 字符（＜窗口 120）⇒ 被误判为宿主。
  修复＝§3.2 的平衡括号回扫 ＋ 精确被调名；修复后 P2/P3/P6/P7 全红、P5 仍绿。
- 全部 7 条：锚点命中 **1**、`还原一致=True`、`git status -- spark-jobs` **无输出**（无探针残留）。

### 5.4 门禁（默认档，各档独立计数；`.verify/s350-final/`）

- **量数轮 `s350-1`**（跑的是**首版口径**实现；用例数同为 6 ⇒ 计数依据仍成立，收口轮跑最终实现）：
  - analytics-server **996**（`F=1 E=0 S=1`）明细 `99+353+172+97+118+157` ⇒ **DRIFT（基线 994）**
  - **+2 全部落在 `platform-common`（97→99）**，其余模块**一字未变**（与本轮改动面一致：两条新用例同在该模块）
  - mall-simulator **13 MATCH**；synthetic-data-generator **110 MATCH**；**default 三棵树 1119（基线 1117）**
  - 唯一红＝**已登记环境性用例** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（`F=1`；`expected: 43 but was: 0`；成因：`landing/` 被 gitignore ⇒ 工作树无该历史档案）
  - 该轮因**计数漂移**记 `[FAIL exit=7]` ⇒ **只作量数依据，不作通过证据**
- **收口轮 `s350-final`**（跑**最终实现**；`RunId=s350-final`，日志目录 `.verify/s350-final/`）：

  ```
  --- default-tests 摘要（本轮 fresh）---
    analytics-server           exit=1  Tests run: 996 (F=1 E=0 S=1)  模块汇总行 6 明细 99+353+172+97+118+157
                               tests=996 MATCH
    mall-simulator             exit=0  Tests run: 13 (F=0 E=0 S=0)  模块汇总行 1 明细 13
                               tests=13 MATCH
    synthetic-data-generator   exit=0  Tests run: 110 (F=0 E=0 S=0)  模块汇总行 1 明细 110
                               tests=110 MATCH
    default 三棵树                合计 = 1119（基线 1119）
  === 最终摘要（各档独立，不合并计数） ===
    default   FAIL （1119 个用例）
              失败项：analytics-server
  [FAIL exit=7] 所选档未全部通过。
  ```

  - **三棵树计数全部 MATCH**（996／13／110，合计 1119 ＝ 本轮新基线），**analytics-server 的 +2 全部落在 `platform-common`**（99＝97＋本轮 2 条；其余五个模块明细与改前逐字相同）。
  - **唯一红**（逐字）：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61 [本地档案 39（P1-05 前置 4，40-43.json）；P1-05 期望实测件数= 43]`（`platform-app` 模块汇总 `Tests run: 157, Failures: 1`；`F=1`）＝ **已登记环境性用例**（成因：`landing/` 被 gitignore ⇒ 工作树无该历史档案）。
  - ⇒ 本轮门禁判定：**计数 MATCH ＋ 唯一红为已登记环境性用例**，脚本自身仍报 **`[FAIL exit=7]`** ⇒ **不得**表述为「门禁已通过」（与 S3-48／S3-49 同口径）。
  - 记录一笔（工具事实，非判据）：`run-tests.ps1` 的摘要走 `Write-Host`（信息流），外层若用 `2>&1 | Out-File` 会**抓不到摘要**（本轮实测 wrapper 文件 0 字节）；需 `*>&1` 或直接从 harness 子进程 stdout 日志取；本轮证据取自后者（`L1425 tests=996 MATCH` … `L1442 GATE-EXIT=7`）。
  - `spark` 档／`isolated` 档**未重跑**（两条新用例无 `@Tag("it")`，不在 isolated 选择面内；本轮无 Spark 生产改动）。

---

## §6 契约变更

**无。** 本轮未触及 `contract-specs/**`（0 命中）、未改任何已发布契约语义；`docs/contracts/**` 亦未改。

---

## §7 未测与边界（不得夸大）

1. **无经典 RED**（characterization guard）⇒ 非恒真性**只**由 §5.3 的 7 条探针支撑；**不得**表述「这条守卫已被完整证明有效」。
2. **仍不解析 Scala 语法**：槽位宿主＝**文本**平衡括号回扫 ⇒ 字符串字面量/字符字面量里的括号可能干扰判定（今日 24 个槽位实测未受影响，但这是**未穷举**的边界）；不处理宏、不处理跨文件间接调用（如经变量/工厂构造的码）。
3. **不证明**规则语义/阈值正确、**不证明**真集群上这些规则真的会跑（`spark` 档本轮**未重跑**）。
4. `src/test` 树**刻意排除**（39 文件/389 命中/97 token 属夹具期望值）；这一取舍意味着「**测试树里出现的码**」不在任何判据面内。
5. `spark-jobs` 的 `resources` 树实测**不存在**（0 文件）⇒ 「资源树没判据」这条**今日无对象**；若未来新增 `resources` 且其中出现规则码，本守卫**不会**发现（覆盖边界随事实变化而变，须重测）。
6. 判据④只钉「首参是静态字面量」，**不钉**首参**是不是**规则码（那是判据①的职责）、**不钉**传入的码与检查语义是否匹配（如给 `PUB_*` 检查挂 `ADS_*` 码不会被发现）。
7. 判据⑤的「三类槽位」是**今日实测的闭集**：新增第四类**合法**形态时必须改判据并登记（这是**刻意**的设计 —— 逃逸面必须显式化，但也就意味着**任何**新形态都会先红一次，需人工裁定）。
8. `AdsQualityJob.scala:94` 的策略表是「哪些 Landing 规则阻断发布」的 **Spark 侧第二声明**（Java 侧 `RuleSeverity` 为所有者）⇒ 本轮**只钉形态、不钉两侧集合一致**，合并**待裁决**。
9. `RuleSeverity.registeredCodes()` 暴露的是**代码内默认目录**，不代表某次 run 的**冻结**规则集（S3-49 已登记）。
10. 门禁 `isolated` 档／真库**未测**（两条新用例无 `@Tag("it")`，不在 isolated 选择面内）；收口轮 `[FAIL exit=7]` **不得**读作「门禁已通过」。
11. **不得**表述「L85 已满足」或「Spark 规则码的所有逃逸面已封死」。

---

## §8 顺带台账（登记，不在本轮处理）

- `AdsQualityJob.scala:94` 的 `Set("AMOUNT_RECONCILE", "REQUIRED_FIELD_NULL_RATE", "ENUM_WHITELIST")` ＝ 「哪些 Landing 规则阻断发布」的 **Spark 侧第二声明**（S3-49 台账③）⇒ **仍待裁决**：合并＝改 Spark 判定语义（门②邻域），须先冻结「阻断集合的唯一所有者」口径。
- 判据⑤的「形态闭集」本身是**新的显式逃逸舱**：第三/第四类形态若出现，必须**显式登记**（与 S3-49 的非规则 token 表同性质的纪律）。
- `AdsQualityJob.scala:384` 的消息文本**提到**规则码（非构造码）⇒ 记录在案：任何「字面量文本不得含码」的判据都会在此**假 RED**（§3.4-2）。
- `AdsSql.scala:23` 的 `s"${table}__staging"` ＝ **表名**拼接（非码）⇒ 记录在案：任何「插值不得拼接」的判据都会在此**假 RED**（§3.4-1）。

---

## §9 复现命令

```powershell
$wt = 'D:\Develop_code\GraduationProject-wt\v3-dev'
$env:JAVA_HOME = 'D:\Develop\JAVA17'

# 1) 定向真跑（守卫 6 条 + 受影响的两个既有类）
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f "$wt\analytics-server\pom.xml" -pl platform-common -am test `
  '-Dtest=SparkRuleCodeRegistryGuardTest,RuleSeverityTest,WarehouseNameLiteralGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
# 期望：Tests run: 29, Failures: 0 / BUILD SUCCESS

# 2) 变异探针（7 条：字节备份 → 锚点唯一断言 → 注入 → 定向真跑 → 还原 → sha256 复核）
& "$wt\.verify\s350-probes.ps1"        # 结果落 .verify\s350-probes.json

# 3) 门禁（默认档；收口轮 RunId 见 §5.4）
& "$wt\scripts\run-tests.ps1" -Suite default -RunId s350-final -LogDir "$wt\.verify\s350-final" -Confirm
# 期望：analytics-server 996 MATCH / mall 13 MATCH / generator 110 MATCH / 三棵树 1119 = 基线；
#       仍 [FAIL exit=7]（唯一红＝已登记环境性用例 IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched）
# 注意：本脚本摘要走 Write-Host（信息流）；外层若要落盘请用 *>&1，2>&1 抓不到摘要（本轮实测）。
```
