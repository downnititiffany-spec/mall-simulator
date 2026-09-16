# S3-45 设计差异登记 —— connection-ingestion「仓库根查找」收敛到唯一所有者 ＋ 结构守卫（阶段6 反熵，A 类）

- 日期：2026-09-16
- 轮次：S3-45（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`c0c2f162dee5b8c3291ba205cf2624ab59cc0820`（S3-44 收口，HEAD == origin）
- 来源：backlog 行「repo 根查找重复实现的**剩余部分**」（`docs/PROJECT_STATUS.md` L498）的 ① 项 —— 该行**自身**已写明处理方向
  ＝「给 `connection-ingestion` 补 `platform-common` 的 `<type>test-jar</type>`（同 ai-decision／metric-analysis／platform-app／
  warehouse-pipeline 既有形态）＋ 5 处替换 ＋ Java 侧结构守卫；需 `default` 档证据面」。反熵治理判类＝**`code-retirement`**
  （同一职责的多个**内部测试副本**收敛到一个所有者），指导书阶段6 反熵口径。
- 类别：**A 类（实现/加性）** —— 只动 `connection-ingestion` 的**测试树**与**测试作用域**依赖：pom 加 1 个 `test-jar`
  依赖、5 处本地 walk-up 删除并改调唯一所有者、新增 1 个结构守卫测试类（3 条）。**零**生产代码、**零** DDL、**零**连库、
  **零** `contract-specs/**`、**零**前端、**零**契约语义变更、**零**第三方新依赖。
- 交付面：`analytics-server/connection-ingestion/pom.xml`（+9 行）＋ 5 个被收编测试文件
  （`ingestion/IngestionBoundaryMatrixTest`／`ingestion/IngestionFailureTaxonomyTest`／`ingestion/IngestionServiceMappingTest`／
  `landing/FlumeSpoolConfigTest`／`mapping/MappingTestSupport`）＋ **新增**
  `analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/ingestion/RepoRootSingleOwnerTest.java`（167 行／3 条）
  ＋ `scripts/run-tests.ps1`（基线 980→983 ＋ S3-45 注释块）＋ `docs/**`（本登记、`PROJECT_STATUS`、历史 F-78）。

> **本轮只覆盖 `analytics-server` 的 Java 测试树。** `spark-jobs`（Scala／JDK8，另有 `RepoRootSingleOwnerSpec` 守护）与两个
> **独立 Maven 工程**（`mall-simulator` 的 `user.dir` 父目录假设、`synthetic-data-generator` 的 Java walk-up）的同款查找
> **不在本轮范围**，仍是 **B 类待总控裁决**（§7②、§8），本轮**一字未动**。

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| 编号 | 实测（改前） | 取证 |
|---|---|---|
| F1 | 起点 HEAD `c0c2f16`、工作区**干净**；该模块 pom L55-59 引 `platform-common`（L57）但**既无** `<type>test-jar</type>`、**也无** `<scope>test</scope>` ⇒ 该模块**测试**类路径上根本没有 `com.graduation.analytics.testsupport.RepoRoot`（这正是 5 份副本被逼出来的原因） | `git show HEAD:analytics-server/connection-ingestion/pom.xml` |
| F2 | 该模块测试树内 **5** 处同款 walk-up（形态 `for (Path dir = start; dir != null; dir = dir.getParent())`）：`IngestionBoundaryMatrixTest.java:464`、`IngestionFailureTaxonomyTest.java:447`、`IngestionServiceMappingTest.java:292`、`landing/FlumeSpoolConfigTest.java:222`、`mapping/MappingTestSupport.java:145` | `git grep -n 'dir\.getParent()' HEAD -- '*.java' '*.scala'` ⇒ **恰好 6 处**＝上述 5 处 ＋ `RepoRoot.java:33`（工作区同命令 ⇒ **1 处**） |
| F3 | 5 份副本**锚点各不相同**：3 处锚 `contract-specs/schemas/canonical-event.v1.schema.json`（`IngestionBoundaryMatrixTest.java:79`／`IngestionFailureTaxonomyTest.java:79`／`IngestionServiceMappingTest.java:69`），`FlumeSpoolConfigTest.java:32-35` 锚 `ingestion/flume/flume-spooldir.conf`＋`README`（**静态字段** `CONF`／`README` 在**类初始化**时就求根），`MappingTestSupport.java:140` 锚 `contractPath()` | `git show HEAD:<file>`（行号见左） |
| F4 | 副本自带注释**已自称待删**（`IngestionBoundaryMatrixTest.java:462` 附近「已登记的仓库根查找重复项，见 S2-02A 用例同款注释」）；`FlumeSpoolConfigTest` 另有一句**错话**（「`RepoRoot` 只在 `platform-app` 的测试类路径上」）⇒ 本轮随方法一并删除 | 同上 |
| F5 | 生产树零命中：`analytics-server/*/src/main` 内 `dir.getParent()` **0** 处 ⇒ 本轮**不触**生产代码 | `git grep -n 'dir\.getParent()' HEAD -- 'analytics-server/*/src/main'`（零输出） |
| F6 | 唯一所有者由 `platform-common` **测试源**提供：`.../src/test/java/com/graduation/analytics/testsupport/RepoRoot.java`（41 行、CRLF），对外 `path()`／`path(String relative)`，私有 `locate()` 从 `user.dir` 向上要求**同时**存在 `contract-specs/` 与 `spark-jobs/`，否则 `IllegalStateException` | 读文件 ＋ `git show HEAD:<file>` |
| F7 | `MappingTestSupport.repoFile(String)`（`HEAD:...:127`）**有 4 个外部消费者**：`MappingActivationServiceTest:69`、`MappingDryRunServiceTest`、`SourceMapperActivationTest:179/180/199`（含 `repoFile(".")`）、`SourceMapperTest:65/120` ⇒ 直接删方法会波及 4 个测试类 ⇒ 保留为**一行转发**并登记 | `git grep -n 'repoFile(\|repoText(' HEAD -- 'analytics-server/connection-ingestion/src/test'` |
| F8 | 计数基线（改前，S3-44 已登记）：analytics-server **980** 明细 `93+350+169+97+114+157`；spark **308**；isolated `mall=30/generator=19/analytics=6` | `scripts/run-tests.ps1` L110-114／L551-552 |
| F9 | 测试源规模：`analytics-server` 测试 **130** 份（ai-decision 12／connection-ingestion **40**／metric-analysis 15／platform-app 30／platform-common 14／warehouse-pipeline 19），生产 **203** 份 ⇒ 守卫「扫描 > 100 文件」的下限有真实余量，**不是空扫** | `Get-ChildItem -Recurse -Filter *.java` 实测 |
| F10 | 本轮主题在契约里**零命中**：`git grep -n 'RepoRoot\|walk-up\|test-jar' -- docs/contracts contract-specs` 零输出；`contract-specs/**` 本轮**一字未动**（③ 用例只**读**被 pin 的那份契约并断言 `$id`） | §6 |

**改前 RED 取证（先落测试，未提前实现）**：

- **RED-1**（pom 未补 test-jar 时编译）⇒ `程序包 com.graduation.analytics.testsupport 不存在`、`BUILD FAILURE`、`exit=1`
  （`.verify/s345-red-1.log`）—— 直接实证 F1 的缺口**真实存在**，不是推测。
- **RED-2**（pom 补完后、5 处副本尚未收编）⇒ `Tests run: 3, Failures: 2, Errors: 0`（`.verify/s345-red-2.log`）：
  ① 所有者集合实测 **6** 个文件（5 副本 ＋ `RepoRoot.java`）≠ 期望恰好 `RepoRoot.java`；
  ② 逐字「`.../mapping/MappingTestSupport.java 不应再自持 walk-up 实现`」。

---

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

| # | 硬门禁 | 本轮是否触碰 | 依据 |
|---|---|---|---|
| ① | DROP TABLE/COLUMN | **否** | 无 DDL、无 SQL |
| ② | 改已有字段类型/既有业务语义 | **否** | **只改测试**；`RepoRoot` 既有公开方法签名未变；pom 只**加**一个 `test` 作用域依赖，既有依赖一字未改 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **否** | **零连库**（守卫只读源码文本与文件系统） |
| ⑤ | 切 ACTIVE | **否** | 未触碰运行时/激活面 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | 一字未动；③ 用例只**读** `canonical-event.v1.schema.json` 并断言 `$id`（§1 F10） |
| ⑦ | 改 V3.0 总体架构 | **否** | 仍是「一个测试基座类 ＋ 模块测试作用域依赖」；**不引入**新组件、新插件、新构建步骤 |
| ⑧ | 改正式项目范围 | **否** | 不加端点／不改接口清单／不改前端；只删测试内重复实现 |
| ⑨ | 删除已发布功能 | **否** | 删的是**测试内重复实现**（5 处 walk-up）与 1 句**错注释**；**未删任何用例/断言** —— 被收编 5 个测试类的用例数（`IngestionBoundaryMatrixTest` 3／`IngestionFailureTaxonomyTest` 6／`IngestionServiceMappingTest` 3／`FlumeSpoolConfigTest` 9／`MappingTestSupport` 非测试类）**一字未变**（§5 目标集 76 条全绿即证）。反熵判类＝`code-retirement` |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 复用 `platform-common` **已有** test-jar 形态（与 4 个模块既有构型一致）；**零**第三方新依赖 |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 单一落点＝`platform-common` 测试源里的 `RepoRoot`；**未**在 `connection-ingestion` 另造第二套 |

---

## §3 本轮冻结的口径（逐条落在断言上）

① **单一所有权判据＝集合相等**：扫描 `analytics-server/*/src/test/java/**/*.java` 中含 walk-up 特征片段的文件，
   集合**恰好等于** `{analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/RepoRoot.java}`
   —— 多一处即红（P1 实证）、少一处亦红（P2 实证）。**不允许**「打印一下就过」。
② **特征片段字符串拼装**（`"for (Path dir = start; dir != null; dir = dir." + "getParent())"`），避免守卫**匹配到自己**
   （S3-30／S3-31 同款教训）；`RepoRoot` 常量的判据同理。
③ **扫不掉也不能过**：扫描规模断言 `> MIN_SCANNED_TEST_FILES(=100)`（实测 130 份）＋「生产树零命中」⇒ 守卫不是
   「扫了 0 个文件所以通过」。
④ **被收编 5 文件逐文件双断言**：不得再含 walk-up ＋ 必须引用 `RepoRoot`（防「删了但没接管」与「接管了却仍留着」两种半成品）。
⑤ **所有者可用性断言**：`RepoRoot.path()` 真能定位仓根（该目录下**同时**有 `contract-specs/`、`spark-jobs/`、
    `analytics-server/pom.xml`），且能解析**被 pin** 的契约文件 `contract-specs/schemas/canonical-event.v1.schema.json`
    （存在＋以仓根为前缀＋含 `$id`）。
⑥ `MappingTestSupport.repoFile` 保留为**一行转发**（`return RepoRoot.path(repoRelative);`），类 javadoc 改真话
    （「S3-45 起唯一所有者是 `RepoRoot`，`repoFile`/`repoText` 只是转发」）—— 保留有据（§1 F7 的 4 个消费者）。
⑦ **计数只允许 +3**：允许 `connection-ingestion` +3（新守卫 3 条），**不允许**其它五模块与另两棵树变动；门禁实测确认
    （§5）。

---

## §4 实现面（本轮改了什么，行数取 `git diff --numstat`）

| 文件 | 变更 | 说明 |
|---|---|---|
| `analytics-server/connection-ingestion/pom.xml` | **+9 / −0** | 既有 `platform-common` 依赖**之后**新增 test 作用域 `test-jar` 依赖块（含「S3-45：复用 platform-common 测试作用域的『仓库根定位』单一所有者 RepoRoot…改前 5 份，锚点文件还各不相同」注释） |
| `.../ingestion/IngestionBoundaryMatrixTest.java` | **+3 / −17**（461 行 / 27541 B） | 删本地 `repoRoot()`／`repoFile()`（改前 L462-474）；调用点（L319／L359）改 `RepoRoot.path()`／`RepoRoot.path(CONTRACT_PATH)`；删 `java.nio.file.Paths` import、加 `RepoRoot` import |
| `.../ingestion/IngestionFailureTaxonomyTest.java` | **+3 / −21**（440 行 / 26108 B） | 同上（删改前 L445-457；调用点 L266／L346） |
| `.../ingestion/IngestionServiceMappingTest.java` | **+3 / −23**（283 行 / 15991 B） | 同上（删改前 L290-302；调用点 L142／L245） |
| `.../landing/FlumeSpoolConfigTest.java` | **+4 / −18**（215 行 / 11519 B） | 删本地 `repoRoot()`（改前 L220-228）⇒ 静态字段 `CONF`／`README` 改 `RepoRoot.path(...)`；**顺带删除**含错话的注释段（「RepoRoot 只在 platform-app 的测试类路径上」） |
| `.../mapping/MappingTestSupport.java` | **+5 / −14**（143 行 / 5594 B） | 删本地 `repoRoot()`（改前 L143-151）；`repoFile` 变一行转发；类 javadoc 与 `repoText` 注释改真话 |
| `.../ingestion/RepoRootSingleOwnerTest.java` | **新增** 167 行 / 8376 B / **3** 条 | 结构守卫（§3 ①-⑤），sha256 前缀 `13E7FEDE6BECFA74…` |
| `scripts/run-tests.ps1` | **+27 / −1**（906 行） | `$BaselineDefault['analytics-server'] 980→983` ＋ S3-45 注释块（含对 S3-44 注释「connection-ingestion F=1」**模块归属笔误**的**追加更正**，不改已提交的 S3-44 原文） |

EOL/编码实测（写后逐个复核）：上表 7 个文件**全部 CRLF、零裸 LF、末尾有换行**；`run-tests.ps1` 906 行全 CRLF。

---

## §5 证据（真跑，非推断）

### 5.1 定向红/绿（内环，不作门禁证据）

| 阶段 | 结果 | 日志 |
|---|---|---|
| RED-1（pom 未补 test-jar，守卫先落地） | `程序包 com.graduation.analytics.testsupport 不存在`／`BUILD FAILURE`／`exit=1` | `.verify/s345-red-1.log` |
| RED-2（pom 补齐、5 副本未收编） | `Tests run: 3, Failures: 2, Errors: 0`：① 所有者集合 **6** 个；② `MappingTestSupport.java 不应再自持 walk-up 实现` | `.verify/s345-red-2.log` |
| **GREEN**（收编后，目标集一次跑） | **`Tests run: 76, Failures: 0, Errors: 0`／`BUILD SUCCESS`／`exit=0`**：`RepoRootSingleOwnerTest` 3／`IngestionBoundaryMatrixTest` 3／`IngestionFailureTaxonomyTest` 6／`IngestionServiceMappingTest` 3／`FlumeSpoolConfigTest` 9／`MappingActivationServiceTest` 16／`MappingDryRunServiceTest` 20／`SourceMapperActivationTest` 5／`SourceMapperTest` 11 | `.verify/s345-green1.log` |

### 5.2 变异探针（四条；每条＝字节备份 → 单类 maven 实跑 → 从日志/surefire 报告取失败名 → 字节还原 → sha256 比对）

| 探针 | 打在 | 预期 | 实测 |
|---|---|---|---|
| P1 | 新增**第二个所有者**（`ingestion/StrayWalkUpProbe.java`，同款 walk-up） | ① 红 | `exit=1`、`Failures: 1`：`onlyPlatformCommonOwnsRepoRootLookup:92`，实际集合 `["...StrayWalkUpProbe.java", "...RepoRoot.java"]`，`but some elements were not expected` ✅ |
| P2 | 把所有者改写成**等价的 `while` 循环**（`Path dir = start; while (dir != null) { …; dir = dir.getParent(); }`） | ① 红（**防恒真**） | `exit=1`、`Failures: 1`：实际集合 **`[]`**、`but could not find … RepoRoot.java` ✅（语义等价但文本不匹配即红 —— 这是「文本判据」的**已知代价**，§7③） |
| P3 | 把 walk-up **重新插回**被收编文件（`MappingTestSupport`） | ② 红（① 亦红） | `exit=1`、`Failures: 2`：② 消息逐字「`analytics-server/connection-ingestion/src/test/java/com/graduation/analytics/mapping/MappingTestSupport.java 不应再自持 walk-up 实现`」 ✅ |
| P4 | 守卫常量 `CONTRACT_REL` 指向**不存在**的文件（`….schema.json.S345-PROBE-MISSING`） | ③ 红 | `exit=1`、`Failures: 1`：`ownerResolvesPinnedContractFile:128`「契约文件必须真实存在：…S345-PROBE-MISSING」 ✅ |

四条探针**全部** `还原一致=True`（sha256 与备份一致），四轮结束后 `git status --porcelain` 只剩本轮**有意**改动
（无残留探针文件、无残留 `StrayWalkUpProbe.java`）。原始日志：`.verify/s345-probe-P1.log`／`P2b`／`P3`／`P4`。

### 5.3 统一门禁（`default` 档两轮，本文件是门禁基线故必须重跑）

| 轮次 | 命令要点 | 结果 |
|---|---|---|
| **量数轮 `s345-1`** | `-Suite default -LogDir .verify/s345-gate -AllowCountDrift -Confirm` | analytics-server `983 (F=1 E=0 S=1)` 明细 `93+**353**+169+97+114+157` ⇒ `tests=983 DRIFT(基线 980) -AllowCountDrift 放行`；**+3 全部落在 connection-ingestion（350→353）＝本轮新守卫 3 条**；mall-simulator 13 `MATCH`；synthetic-data-generator 106 `MATCH`；三棵树 1102（基线 1099）⇒ 该轮因漂移记 FAIL，**只作量数依据、不作通过证据** |
| **收口轮 `s345-final-2`** | `-Suite default -LogDir .verify/s345-gate-final2 -Confirm`（**无** `-AllowCountDrift`，对**最终字节**重跑） | analytics-server `983` **`MATCH`**／mall-simulator `13` **`MATCH`**／synthetic-data-generator `106` **`MATCH`**；`default 三棵树 合计 = 1102（基线 1102）`；最终摘要仍 `[FAIL exit=7]`，**只**由那 1 条**已登记环境性**用例命中（`platform-app` `com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，`expected: 43 but was: 0`，**未修、未用开关掩盖**） |

> 该轮摘要里的 `S=1` 与上条红**无关**：它是 `connection-ingestion` 的**既有** 1 条 skip
> （`mapping.dryrun.SampleRefPolicyTest`，S3-44 量数轮同款，两轮一致）。**更正**：S3-44 注释块里写的
> 「connection-ingestion F=1」属**模块归属笔误** —— 该红在 `platform-app`（157 条那一格），
> `connection-ingestion` 350（S3-44 轮）与本轮 353 **均 F=0**；此更正已**追加**进 `run-tests.ps1` 的 S3-45 注释块，
> **未改写**已提交的 S3-44 原文。

---

## §6 契约变更（先改契约再改代码）

**无。** 实测 `git grep -n 'RepoRoot\|walk-up\|向上找仓根\|test-jar' -- docs/contracts contract-specs` **零命中**；
`contract-specs/schemas/canonical-event.v1.schema.json` 本轮**只读**（守卫断言其存在与 `$id`），`docs/contracts/**` 未改。
故本轮不存在「契约先于代码」的改动面。

---

## §7 未测与边界（不得越界表述）

① 守卫只覆盖 **`analytics-server` 的 Java 测试树**；`spark-jobs`（Scala／JDK8）与两个**独立 Maven 工程**
   （`mall-simulator`／`synthetic-data-generator`）的同款查找**未被本轮守卫覆盖**，仍为 **B 类待总控裁决**
   （`docs/PROJECT_STATUS.md` L498 ② ＋ 本轮新增行）。
② **不得**据本轮声称「repo 根查找重复实现已全部清除」或「全仓单一所有者已达成」—— 只在 `analytics-server` Java 测试树内成立。
③ 判据是**源码文本片段**＋集合相等，**不是**语义等价性证明：把所有者改写成**等价 `while` 循环**会让 ① 变红（P2 实测
   `actual=[]`）。这是**防恒真**的必要代价（宁可误报不可漏报），但**意味着**该守卫对「实现形态变化」敏感 ——
   属**已知限制**，不属缺陷；反向也**不**成立：守卫不改动任何生产/运行语义。
④ ③ 只证明「所有者能定位到**被 pin 的那份**契约文件」，**不**证明各消费者读到的内容都正确，**不**证明 schema 语义。
⑤ 唯一红的 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（`expected 43 but was 0`）是**既有环境性问题**
   （`landing/manifests` 历史清单被 `.gitignore:30` 排除、本工作区不存在那 43 份），**本轮未修**，也不得记为本轮引入或本轮修复。
⑥ **零连库、零外网、零 HTTP**：本轮没有任何真实数据库/集群/网络行为被验证。
⑦ 内环 76 条全绿**不是**门禁通过证据；门禁证据是 §5.3 的收口轮（`MATCH`），而收口轮整体仍是 `[FAIL exit=7]`
   —— 不得简写成「门禁已通过」。
⑧ `spark`／`isolated` 档**未重跑**（本轮只改 `analytics-server` 的 default 档计数与注释；命令语义未改）。

---

## §8 顺带台账（**不删行、不改判类**）

- `docs/PROJECT_STATUS.md` **L498**（backlog 行「repo 根查找重复实现的**剩余部分**」）**在原行末单元格内**追加
  「**（S3-45 更新描述，不删行、不改判类，2026-09-16）**：① 已按本行候选完成 —— `connection-ingestion` pom 补
  `<type>test-jar</type>` ＋ 5 处 walk-up 全部删除并改调 `RepoRoot` ＋ 新增 `RepoRootSingleOwnerTest`（3 条结构守卫），
  default 档实测 `connection-ingestion 350→353`、三棵树 `1099→1102`；② 跨工程 2 处（`mall-simulator`／
  `synthetic-data-generator`）与 `spark-jobs` Scala 面**仍为 B 类待总控裁决，本轮未动**」。**原判类保留**。
- **新增 1 行**（紧接 S3-44 行下方，判类＝development backlog）：「S3-45 后 repo 根查找的**跨树残余面**与守卫覆盖边界」，
  内容＝① `mall-simulator`／`synthetic-data-generator` 两工程的独立构建耦合问题（引 test-jar 的构建顺序/离线仓风险）；
  ② `spark-jobs` JDK8 无法消费 JDK17 test-jar（class file 版本 61）；③ 本守卫是**文本判据**、对等价重写敏感（P2 已实测）；
  ④ 守卫**未**覆盖 `.scala` 与两个独立工程的测试树。
- 滚动执行位置块（`docs/PROJECT_STATUS.md` L23-31）同步为本轮口径；阶段6 区块**新增 7 条** S3-45 记录。
- `docs/status-history/开发过程事实与决策记录.md` **追加 F-78**（事实记录，不回改历史）。

---

## §9 复现命令

```powershell
# 0) 位置
Set-Location D:\Develop_code\GraduationProject-wt\v3-dev

# 1) 改前取证：全仓 walk-up 计数（HEAD 应 6 处、工作区应 1 处）
git grep -n 'dir\.getParent()' HEAD -- '*.java' '*.scala'
git grep -n 'dir\.getParent()' -- '*.java' '*.scala'

# 2) 内环：守卫单类（注意每个 -D 参数都加单引号，否则 maven 会吃掉参数）
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f analytics-server\pom.xml -pl connection-ingestion -am test `
  '-Dtest=RepoRootSingleOwnerTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# 3) 统一门禁（default 档；本文件是门禁基线，改了就两轮：量数轮 + 收口轮）
.\scripts\run-tests.ps1 -Suite default -RunId s345-1 -LogDir .verify\s345-gate -AllowCountDrift -Confirm
.\scripts\run-tests.ps1 -Suite default -RunId s345-final-2 -LogDir .verify\s345-gate-final2 -Confirm

# 4) 文件体检（绝对路径；Set-Location 不改变 .NET 进程 CWD）
$p='D:\Develop_code\GraduationProject-wt\v3-dev\analytics-server\connection-ingestion\src\test\java\com\graduation\analytics\ingestion\RepoRootSingleOwnerTest.java'
$t=[System.IO.File]::ReadAllText($p)
"裸LF=$(([regex]::Matches($t,"(?<!`r)`n")).Count) CRLF=$(([regex]::Matches($t,"`r`n")).Count) 尾=$($t.EndsWith("`r`n"))"
```
