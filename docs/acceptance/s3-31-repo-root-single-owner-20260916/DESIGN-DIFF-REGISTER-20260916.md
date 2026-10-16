# DESIGN-DIFF-REGISTER — S3-31：`spark-jobs` 测试树「仓库根查找」收敛为唯一所有者（2026-09-16）

**backlog 行（改前措辞，append-only 不删行）**：`repo 根查找已有第四份本地实现（RepoRoot 提到 test-jar 后删除四处副本）`｜状态：`development backlog`

**本项性质**：A 类（IMPLEMENTATION/ADDITIVE 的一个子形态：**内部代码退役 + 结构守卫新增**）——只改 `spark-jobs` **测试树**，零生产代码/DDL/迁移/前端/新依赖。

---

## §0 证明边界（先说清本项**不**证什么）

1. 本项只证 **`spark-jobs/src/test/scala` 这一棵树内**「向上找仓库根」的实现**恰好 1 份**（结构断言＋双向探针＋档级回归）。**不得**表述为「全仓库 repo 根查找已单所有者」。
2. 本轮**未**改动、**未**收编同反应堆 `analytics-server/connection-ingestion` 内的 **5** 处同款循环，也**未**触碰 `mall-simulator` / `synthetic-data-generator` 的 **2** 处副本（§2 F5/F6 实测登记，前者列为后续 A 类候选、后者判 B 类待总控裁决）。
3. 守卫断言的是**结构**（谁持有 walk-up 循环），**不是**「根找得对」的行为证明；行为面另有第 3 条用例的独立复算，但只覆盖 `SurrogateKeyVectorSupport` 一路。
4. 档级回归只跑 `spark` 档（两轮）；`default`/`isolated` 两档**本轮未重跑**（零 `analytics-server` 文件改动，且 `default` 档存在已登记环境性红）。
5. 只证 Scala `local[1]` ＋ in-memory catalog 下测试通过；**不得**表述为生产 Hive/Spark 集群已通过。
6. 「本项关闭该 backlog 行」**不成立**：该行的跨工程/同反应堆剩余副本仍在（§7），本项只关闭**工程内**部分。
7. 探针 A/B 的临时物（`S331ProbeScratch.scala`、所有者循环变量改名）均已删除/还原，`git status` 对 `P2TestSupport.scala` 与探针文件**静默**。

---

## §1 逐字锚点（改前状态，行号＝改前实测）

| # | 锚点 | 值 |
|---|---|---|
| A1 | 唯一所有者（保留） | `spark-jobs/src/test/scala/com/graduation/analytics/P2TestSupport.scala`：`lazy val repoRoot`，锚点 `GoldenRelative = "tests/golden-dataset/events/golden-20260901.jsonl"`（L28 区） |
| A2 | 重复实现①（本轮删除） | `SurrogateKeyVectorSupport.scala` L27-33：`lazy val repoRoot`（walk-up 9 行，锚点 `SpecRelative = "contract-specs/specs/surrogate-key.v1.json"`）；`lazy val specPath = repoRoot.resolve(SpecRelative)` |
| A3 | 重复实现②（本轮删除） | `WarehouseNamespaceSpec.scala` L193-199：`private def findRepoRoot()`（同款 walk-up，锚点同上），调用点 L188 `readSpec()` |
| A4 | 树内 walk-up 拥有者实测集合（改前） | `{P2TestSupport.scala, SurrogateKeyVectorSupport.scala, WarehouseNamespaceSpec.scala}`（**3 份**；由 RED 轮诊断打印，见 §6.1） |
| A5 | 所有者既有消费方（改前，**未动**） | `P2TestSupport.repoRoot` 被 8 个 spec 引用（`AdsSchemaOwnerSpec:125,202`、`DwdDimSchemaOwnerSpec:113,182`、`DwdSourceIdentitySpec:277`、`DwsSchemaOwnerSpec:90`、`DwsUvPvInvariantSpec:292`、`FixtureWriteShapeSpec:113`、`MetricAdsSpecTest:33`、`OdsV2SchemaOwnerSpec:207`）⇒ 所有者本身**不新增**，只把两份副本并入既有所有者 |
| A6 | 反应堆内**规范**所有者（JDK17，未动） | `analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/RepoRoot.java`（`locate()` L31-35，锚点：`contract-specs` 目录 ＋ `spark-jobs` 目录同时存在）；已由 `ai-decision:69`/`metric-analysis:64`/`platform-app:68`/`warehouse-pipeline:71` 以 `<type>test-jar</type>` 消费 |

---

## §2 改前实测（F1–F7，全部实跑/实测）

- **F1（树内副本数＝3，非 backlog 行所称"第四份"）**：RED 轮诊断打印 `Set(P2TestSupport.scala, SurrogateKeyVectorSupport.scala, WarehouseNamespaceSpec.scala)` ⇒ backlog 行括号里的「删除四处副本」是**陈旧措辞**（按现有树实测，工程内是 3 份、跨工程另计）。本项按实测数收口，不改写原行措辞，只在状态列 append 实测。
- **F2（三份实现彼此独立，任一份漂移不会互相发现）**：三处锚点文件不同（golden jsonl / surrogate-key.v1.json / warehouse-namespace.v1.json）、终止条件写法不同（`while (dir != null …)` vs `for (Path dir = start; …)`）、异常文案相同但无共享代码 ⇒ 典型"同一件事多份所有者"。
- **F3（副本①的行为面可独立复算）**：`SurrogateKeyVectorSupport.specPath` 指向真实契约向量文件，`specSha256`/`specText`/`specStatus` 由该文件**内容**导出 ⇒ 收编后可用「存在性 ＋ SHA-256 独立复算 ＋ status 非空」证明**定位未变**（§6.2 第 3 条）。
- **F4（副本②的行为面由其自身套件承担）**：`WarehouseNamespaceSpec.readSpec()` 读 `warehouse-namespace.v1.json`，文件缺失即抛 `IllegalStateException` ⇒ 收编若把根找错，该套件会直接红（§6.2/§6.4 均为绿）⇒ 行为保持有间接但真实的证据；**本项不声称**对它有独立复算。
- **F5（同反应堆内仍有 5 处同款循环，本轮未收编）**：`analytics-server/connection-ingestion` 内
  `IngestionBoundaryMatrixTest.java:462-466`（注释自称「已登记的仓库根查找重复项，见 S2-02A 用例同款注释」）、
  `IngestionFailureTaxonomyTest.java:445`、`IngestionServiceMappingTest.java:290`、`landing/FlumeSpoolConfigTest.java:220`、`mapping/MappingTestSupport.java:143`；
  形态为 `for (Path dir = start; dir != null; dir = dir.getParent())` ＋各自锚点文件。
  **收编前提实测**：`connection-ingestion/pom.xml:57` 引 `platform-common` 但**未**带 `<type>test-jar</type>` ⇒ 需先补该测试范围依赖（同 4 个既有模块形态）⇒ 已登记为**后续 A 类候选**，本轮不做（属另一工程/另一档证据面）。
- **F6（跨工程 2 处，判 B 类）**：
  `mall-simulator/src/test/java/com/graduation/mall/golden/GoldenDatasetTest.java:63`：`Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();` —— **不是 walk-up**，是"假定 CWD＝模块目录"的**位置假设**（CWD 一变即静默错根）；`synthetic-data-generator/src/test/java/com/graduation/generator/contract/GeneratorContractParityTest.java:280-282`：Java 版 walk-up。
  **不可就地统一的原因（实测）**：两工程各自是**独立 Maven 构建**（`spring-boot-starter-parent`，pom 内**无**任何 analytics-server 产物依赖，实测 `artifactId` 清单已核）；统一需引 test-jar ⇒ 引入**跨工程构建顺序/离线仓耦合**；更硬的一条：`spark-jobs` 是 JDK8（`<release>8</release>`），**无法**消费 JDK17 test-jar（class file 版本 61）⇒「统一成一份」在物理上就**不可能**覆盖全部副本，只能另造 JDK8 兼容产物（＝新的跨 JDK 基础组件，触门⑩邻域）⇒ 判 **B 类**，登记待总控裁决。
- **F7（更正一处先验误判）**：`synthetic-data-generator/.../engine/FileModeGenerationEngineTest.java:181` 的 `files.get(0).getParent()` **不是**仓库根查找（是临时 run 目录的父目录）⇒ generator 侧仓库根查找实为 **1 处**，非 2 处。按实测登记，不按先验估计。

---

## §3 口径声明（7 条，防越界）

1. **所有权口径**：树内「向上找仓库根」的**唯一所有者**＝`P2TestSupport.repoRoot`；其余文件只能**引用**它，不得自持循环。
2. **收编口径**：被收编的两处**删除**各自的 walk-up 与随之失效的导入（`Path`/`Paths`），**不保留别名**（保留 `repoRoot` 转发＝留下第二所有者表面，违 `delete-first`）。
3. **守卫口径**：守卫断言「拥有者集合**恰好**」等于单元素集合，而不是「不多于 1」或「包含 1」⇒ 既拦"多出来"，也拦"所有者自己不见了"（恒真/空集）。
4. **自指口径**：特征片段以**字符串拼装**给出（`"var " + "dir: Path = start"`），使守卫源文件**不含**该片段 ⇒ 无需"排除自身文件"兜底（照 S3-30 两次自伤的教训）。
5. **扫面口径**：扫描面＝`spark-jobs/src/test/scala/**/*.scala`，只认 `.scala`；扫面规模有下限断言（`> 30`）以防"扫了个空目录"造成恒真。
6. **证据口径**：RED/GREEN/探针逐轮实跑留档（`$env:TEMP\s331_*.log`）；档级证据取**全量 spark 档两轮**（量数轮 DRIFT＝有意、收口轮 MATCH），**不**以定向跑绿代替档级。
7. **退役口径**：本项只退役**测试树内部**重复实现（无持久化/无契约/无 API/无前端面）；跨工程与同反应堆剩余副本**不**在未获裁决前强行动手。

---

## §4 实现面

| 文件 | 变更 | 说明 |
|---|---|---|
| `spark-jobs/src/test/scala/com/graduation/analytics/RepoRootSingleOwnerSpec.scala` | **新增**（3 条） | 结构守卫：①树内所有权唯一 ②两处收编物引用所有者且不再自持 ③契约向量定位/摘要/status 行为保持 |
| `spark-jobs/src/test/scala/com/graduation/analytics/SurrogateKeyVectorSupport.scala` | 改 | 删 `lazy val repoRoot`（walk-up 9 行）⇒ `specPath = P2TestSupport.repoRoot.resolve(SpecRelative)`；保留 `Path`/`Files` 导入（仍用） |
| `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala` | 改 | `readSpec()` 改用所有者；删 `private def findRepoRoot()`；导入收紧 `{Files, Path, Paths}` → `Files` |
| `scripts/run-tests.ps1` | 改 | `$BaselineSpark 305 → 308` ＋ S3-31 溯源注释块（来源/盲区/红绿/探针/边界） |
| `docs/PROJECT_STATUS.md` | 改 | 计数链头、阶段段、滚动执行位置、backlog 行 append 实测 |
| `docs/status-history/开发过程事实与决策记录.md` | 改 | F-64 追加 |

**净效果**：树内 walk-up 拥有者 **3 → 1**（实测：改后仅 `P2TestSupport.scala` 命中特征片段）；`RepoRootSingleOwnerSpec` ＋3 条 ⇒ **spark 305 → 308**。

---

## §5 11 门逐门否

①DROP TABLE/COLUMN — **否**（零 DDL）。②改已有字段类型/既有业务语义 — **否**（零生产代码）。③改已发布 Flyway 迁移 — **否**（零迁移）。④写/迁移正式 3306 数据 — **否**（零 DB 接触）。⑤切 ACTIVE — **否**。⑥改 `contract-specs/**` 已有契约语义 — **否**（只**读**契约文件；未改一字）。⑦改 V3.0 总体架构 — **否**（测试树内部收敛）。⑧改正式项目范围 — **否**。⑨删除已发布功能 — **否**（删的是测试内部重复实现，非发布能力；`delete-first` 口径见 §3-2）。⑩引入 V3.0 未规划大型基础组件 — **否**（零新依赖；跨 JDK 共享产物方案被**明确拒绝**，见 F6）。⑪两种方案造成重大长期架构分叉 — **否**（未在跨工程层面强行选边，改判 B 类交裁决）—— **全否**。

---

## §6 实测记录（RED → GREEN → 牙齿探针 → 档级回归）

### §6.1 RED（先写守卫再看它红）

- 命令：`mvn -o -f spark-jobs/pom.xml test -Dsuites=com.graduation.analytics.RepoRootSingleOwnerSpec -Dp2.test.runId=s331_red1`（`JAVA_HOME=D:\Develop\JDK1.8`）
- 结果（`$env:TEMP\s331_red1.log`）：`Suites: completed 1, aborted 0`；**`Tests: succeeded 1, failed 2`**；`*** 2 TESTS FAILED ***`；`mvn exit=1`。
- 红的**两条**均为结构用例，诊断逐字：
  - ① `Set("…/P2TestSupport.scala", "…/SurrogateKeyVectorSupport.scala", "…/WarehouseNamespaceSpec.scala") was not equal to Set("…/P2TestSupport.scala") (RepoRootSingleOwnerSpec.scala:61)` ⇒ 实测树内 3 份所有者（F1）。
  - ② `" did not include substring "P2TestSupport.repoRoot" (RepoRootSingleOwnerSpec.scala:71)` ⇒ 被收编文件尚未引用所有者。
- 第 ③ 条（行为保持）在**改前即绿**——这是**如实记录**：它是"保持性"断言，不是本项的 RED 驱动；RED 驱动力来自 ①②。
- **参数/环境错误轮（不计入 RED/GREEN）**：`s331_green1.log`（套件名写成不存在的 `SurrogateKeyVectorSpec`）、`s331_green2.log`（`SurrogateKeySpec` 短名 ⇒ `ClassNotFoundException`，真实 FQCN 为 `com.graduation.analytics.sql.SurrogateKeySpec`）。二者均为**参数错误**，不作为任何结论依据。

### §6.2 GREEN

- 命令：`… -Dsuites=com.graduation.analytics.RepoRootSingleOwnerSpec,com.graduation.analytics.WarehouseNamespaceSpec,com.graduation.analytics.sql.SurrogateKeySpec -Dp2.test.runId=s331_green3`
- 结果（`$env:TEMP\s331_green3.log`）：`Total number of tests run: 20`；`Suites: completed 3, aborted 0`；**`Tests: succeeded 20, failed 0`**；`BUILD SUCCESS`；`mvn exit=0`。
- 覆盖：守卫 3 条 ＋ `WarehouseNamespaceSpec`（被收编②的行为面）＋ `SurrogateKeySpec`（被收编①的契约向量面，含真 Spark 比对与 SQL 表达式一致性）。

### §6.3 牙齿探针（两个方向，均真跑）

- **探针 A（"多出来"必须被抓）**：临时新增 `S331ProbeScratch.scala`（另写一份 walk-up，锚点用 golden jsonl）⇒ `s331_probeA1.log`：`Set("…/P2TestSupport.scala", "…/S331ProbeScratch.scala") was not equal to Set("…/P2TestSupport.scala")`，`Tests: succeeded 2, failed 1`（**仅①红，②③仍绿**）⇒ 守卫对"新出现的所有者"有牙齿，且失败定位精确。
- **探针 B（"所有者不见了"也必须被抓 ⇒ 防恒真）**：把所有者循环变量改名（`dir`→`cursor`，**功能不变**，仅特征片段不再匹配）⇒ `s331_probeB1.log`：**`Set() was not equal to Set("…/P2TestSupport.scala")`**，`Tests: succeeded 2, failed 1`（②③仍绿 ⇒ 失败是**归属**问题而非可用性问题）⇒ 守卫不是"恒真"，确实把 `P2TestSupport` 钉在所有者位置上。
- 探针均已回收：探针文件删除、`P2TestSupport.scala` 用 `git checkout --` 还原；回收后 `git status --porcelain` 仅剩本项 3 项预期变更（2 改 ＋ 1 新增）。
- 回收后复测结构：树内命中 walk-up 特征的文件**仅** `P2TestSupport.scala`。

### §6.4 档级回归（真跑，两轮；完成判据取**全量档**）

| 轮次 | RunId / 日志目录 | 计数 | 套件 | 结果 |
|---|---|---|---|---|
| 量数轮 | `s331_20260916_spark1` / `.verify\s331_spark1` | `Total number of tests run = 308`；`基线比对：tests=308 DRIFT(基线 305) ⇒ 基线漂移` | `Suites: completed 37, aborted 0` | `Tests: succeeded 308, failed 0`；`All tests passed = True`；`JDK8=True`；`[FAIL exit=7]`（**基线漂移，符合预期**） |
| 收口轮 | `s331_20260916_spark2` / `.verify\s331_spark2` | `Total number of tests run = 308`；`基线比对：tests=308 MATCH` | `Suites: completed 37, aborted 0` | `Tests: succeeded 308, failed 0`；`mvn exit=0`；**`spark PASS`**；**`[PASS exit=0]`** |

- 漂移来源**有意且已定位**：`RepoRootSingleOwnerSpec` ＋3 条（305 → 308），套件 36 → 37；基线据此更新为 308（`scripts/run-tests.ps1`）。
- 档级判定口径：**计数 MATCH ＋ 全绿 ⇒ 该档 PASS**；本轮 spark 档**无**任何红项可比（与 `default` 档存在已登记环境性红的情形不同）。

---

## §7 未测与边界（不得越界表述）

1. **未测：全仓库单所有者**。同反应堆 `connection-ingestion` 内 **5** 处、跨工程 **2** 处仍在（F5/F6 实测登记）。
2. **未测：跨工程统一方案的可行性**。只实测到"两工程是独立构建、无产物依赖"＋"JDK8 不能消费 JDK17 test-jar"两条硬事实；**未**评估其它统一方案（如抽 JDK8 兼容产物）的成本 ⇒ 不得称"只能如此"。
3. **未测：`default`/`isolated` 两档**（本轮零 `analytics-server` 文件改动，未重跑）。
4. **未测：CWD 变化下的行为**。守卫只证结构；"从别的目录启动 Maven 时根怎么找"未做行为级实测。
5. **未测：`.java` 测试源**。`spark-jobs` 现无 Java 测试源，守卫只扫 `.scala`；若将来新增 Java 测试源，守卫**不**覆盖（口径已写入 §3-5）。
6. **未测：`WarehouseNamespaceSpec` 的独立复算**。其行为保持由"自身套件绿 ＋ 结构断言"间接证明，未像 `SurrogateKeyVectorSupport` 那样做独立 SHA-256 复算。
7. **未测：真机/集群侧**任何结论（见 §0-5）。**不得**称「仓库根查找问题已彻底解决」。

---

## §8 结论与后续

- **判定**：**A 类**，**工程内（`spark-jobs` 测试树）已收口** —— 树内 walk-up 拥有者 **3 → 1**，另两份删除（含失效导入），新增结构守卫 3 条并做**双向**牙齿探针；`spark` 档两轮：量数轮 `308 DRIFT(+3)`、收口轮 `308 MATCH`、`spark PASS`、`[PASS exit=0]`。
- **backlog 行处置**：行**不删**，状态列 append 实测（工程内已收口；剩余部分见下），**不**标"清零"。
- **后续（已实测登记，不在本轮）**：
  1. 同反应堆 `connection-ingestion` **5** 处 → 后续 **A 类候选**（前提：给该模块补 `platform-common` 的 `<type>test-jar</type>`，同 4 个既有模块形态；需 `default` 档证据面）。
  2. 跨工程 **2** 处 → **B 类待总控裁决**（独立构建耦合 ＋ JDK8/JDK17 二进制不兼容，见 F6）。
- **登记文件**：`docs/acceptance/s3-31-repo-root-single-owner-20260916/DESIGN-DIFF-REGISTER-20260916.md`。
