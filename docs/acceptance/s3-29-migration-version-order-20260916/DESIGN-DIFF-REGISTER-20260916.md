# S3-29 设计差异登记：DDL 守卫的迁移**解析顺序**由字典序改为版本序（backlog L377 残留）

- **日期**：2026-09-16
- **分支/提交**：`feature/v3-development`（禁止 merge main）；代码提交见文末「提交」段
- **类别判定**：**A 类（实现/加性，测试守卫内部）**，11 条 HARD DECISION GATE **逐门否**（见 §5）
- **一句话**：`AiSqlDriftTest.ddlTables()` 原以 `Path.sorted()`（**字典序**）拼接迁移 SQL，
  `V10__…` 排在 `V2__…` 之前；本轮把「解析顺序」收归**版本序**唯一所有者
  （`migrationVersion(Path)` ＋ `migrationFilesOrdered(Path)`），并新增带牙齿的自检
  `迁移按版本序解析而非字典序`。

---

## §0 证明边界（先说清「本文证明了什么、没证明什么」）

1. 本项**只动测试守卫代码**（`analytics-server/ai-decision/src/test/**`）：**零** 生产代码、**零** DDL、**零** 迁移、
   **零** Java 主源码、**零** SQL 语义、**零** 前端、**零** 新依赖。
2. 因此本项**不改变任何生产行为**；它改变的是**守门人的判定依据**（读哪些迁移、按什么顺序读）。
3. 本项**不修复**任何「真实漂移」；它让未来可能出现的「依赖前序/按序覆盖」类迁移**不会**因错序被静默读错。
4. **未测**：真实 MySQL 8 上应用迁移的行为、`db/meta` 目录的同类读取点（本项未改，见 §2 F5）、
   `isolated`/`spark` 两档、任何 IT。
5. 本登记内的所有行号均为**写入时实测**（`Select-String` 实读）；文件增长后行号会漂移。
6. 「GREEN」仅指本项**目标用例**与 **default 档**的实测结论，**不**代表 V3.0 全量验收通过。
7. 术语：**字典序**＝`String.compareTo` 的 `Path.sorted()`；**版本序**＝文件名前缀 `V<n>__` 的 `n` 数值升序。

---

## §1 逐字锚点（本项「该做」的依据，全部实读）

| 来源 | 位置（写入时） | 逐字要点 |
| --- | --- | --- |
| `docs/PROJECT_STATUS.md` backlog | 本项行（S3-29 时 L377） | 「`AiSqlDriftTest.ddlTables()` 原先只解析 `CREATE TABLE`、**忽略加性 `ALTER TABLE … ADD COLUMN`** ⇒ …（S3-02 已修复）」＋ 状态列**逐字残留**：「**残留**：迁移文件名按**字典序**排序（V10+ 会错序，当前对 Set 语义无害，**未改**）」 |
| 代码（改前） | `analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/AiSqlDriftTest.java:69` | `for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList())` —— **唯一**拼接点，字典序 |
| 代码（类注释） | 同文件 L31-33 | 「DDL 为唯一事实来源…（**全部**迁移，含后续 `ALTER TABLE ... ADD COLUMN` 的加性迁移…）」 |
| 迁移目录 | `analytics-server/platform-app/src/main/resources/db/metric/V*.sql` | 现存 10 个：`V1, V2, V3, V4, V5, V6, V7, V8, V9, V10` ⇒ 字典序下 `V10` 排第 **1** 位（实测，见 §6.3） |
| 项目纪律 | 本仓库既有守卫风格 | 每条守卫自带「牙齿自检」（如 `迁移解析覆盖后续ALTER加列`、`RepoRoot` 唯一所有者注释 L129-132）⇒ 本项沿用同一形态 |

---

## §2 改前实测（F1–F6，全部真跑）

- **F1 唯一性**：全仓 `git grep -n 'Files.list' -- analytics-server` ＋ `'.sorted()'` 交叉核对，
  以 `.sql` 后缀 ＋ `sorted()` 读取迁移目录的点**共 2 处**：
  ① 本项 `AiSqlDriftTest.java:69`（`db/metric`，`V1..V10`，**非零填充** ⇒ 错序真实存在）；
  ② `platform-common/src/test/java/com/graduation/analytics/warehouse/WarehouseNameLiteralGateTest.java:275`
  （`warehouse/ddl`，**零填充** `01-…` 且该用例只用文件集合做逐文件文本断言，**顺序无语义**）。
  ⇒ **本项只改 ①**；②不属同类残留（命名有序且顺序不参与判定），**不改、不引入共享工具**，避免制造第三所有者。
- **F2 缺失行为**：改前 `AiSqlDriftTest` **无**任何用例断言「解析顺序」，即错序不会被任何用例发现（守卫静默）。
- **F3 影响面**：`ddlTables()` 的产物是 `Map<表, Set<列>>`，`ALTER` 只做 `cols.add(...)` ⇒ 当前**错序对 Set 语义无害**
  （与 backlog 原文一致，**不夸大**为「已造成误判」）。
- **F4 但是顺序本身是判定依据**：`ALTER` 应用必须先于「比对列集合」；一旦后续迁移出现
  「依赖前序/按序覆盖/改名」语义，字典序会**静默**改变结论 —— 这正是本项要钉住的点。
- **F5 其它目录**：`db/meta/**` 的迁移读取点（`QualityRuleVersionMigrationScriptTest`、`SourceRegistryMigrationScriptTest` 等）
  **不**按文件名顺序拼接判定（不经 `.sorted()` 语义路径）⇒ 本项**不扩范围**。
- **F6 用例规模**：改前 `AiSqlDriftTest` 共 **7** 条用例（GREEN 后 8 条，见 §6.2 实测名单）。

---

## §3 口径声明（本轮冻结）

1. **版本序唯一定义**：文件名匹配 `V(\d+)__.*\.sql`（大小写不敏感），`n` 为解析顺序键；
   同版本（同 `n`）时以文件名字典序作**确定性兜底**（保证结果稳定、可复现）。
2. **异常文件名不静默**：不匹配 `V<n>__` 的 `.sql` 给 `Integer.MAX_VALUE` ⇒ 排最后，且被 §6 的
   **严格升序**断言暴露（`MAX_VALUE` 不会大于自身，重复即失败）⇒「不能解析的文件名」不允许悄悄混入。
3. **顺序是判定依据的一部分**：解析顺序必须**确定性**且**语义有序**，故收归唯一所有者
   `migrationFilesOrdered(Path)`；`ddlTables()` **只**经该所有者读目录，不再各自排序。
4. **不加依赖、不改生产**：只用 JDK（`Comparator`/`Pattern`）；**不**引入工具类进主源码树，
   避免让测试守卫的顺序口径变成生产契约。
5. **牙齿必须自证**：新用例含**反证**断言「真实目录上版本序 ≠ 字典序」，否则该用例失去牙齿（届时显式失败并提示）。
6. **不回溯改判**：backlog 该行的**历史部分**（S3-02 已修复 CREATE/ALTER 解析）**保持原样不动**，
   本项按 **append-only** 追加更新描述（见 §4 与 F-62）。
7. **口径不扩张**：本项**不**声称「DDL 守卫已完备」——它仍只覆盖 `db/metric` 目录的
   `CREATE` ＋ `ADD COLUMN` 两类语句（DWS/DIM、`db/meta`、`R7_ADDED_COLUMNS` 覆盖面等另见 backlog 其它行）。

---

## §4 实现面（改了什么、没改什么）

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/AiSqlDriftTest.java` | **改**（唯一代码文件，+63/-4） | 新增 `MIGRATION_NAME` 正则、`migrationVersion(Path)`、`migrationFilesOrdered(Path)`、`import java.util.Comparator`；`ddlTables()` 改为经所有者读目录；新增用例 `迁移按版本序解析而非字典序`（3 段断言：单元性质／真实目录严格升序／牙齿反证） |

- **未改**：任何 `src/main/**`（生产代码）、任何 DDL／迁移／`db/**`、任何其它测试文件、`web/**`、
  `scripts/**`、`pom.xml`（**零新依赖**）、`docs/contracts/**`（本项不动契约：不涉及 API 形态或业务口径）。
- **为何不动契约**：契约文件管的是**对外可见的接口语义**；本项只改测试守卫如何读 DDL，
  对外语义**零变化** ⇒ 无契约条目可加（若强行加会制造「为了改而改」的假条目）。此判断登记在此以备复核。

---

## §5 HARD DECISION GATE 逐门核对

| 门 | 内容 | 判定 | 依据 |
| --- | --- | --- | --- |
| ① | DROP TABLE/COLUMN | **否** | 无任何 DDL 改动 |
| ② | 改已有字段类型或既有业务语义 | **否** | 生产代码零改动；`ddlTables()` 产物集合语义不变（Set 成员相同） |
| ③ | 改已发布 Flyway migration | **否** | `db/**` 零改动 |
| ④ | 写/迁移正式 3306 数据 | **否** | 零连库 |
| ⑤ | 切 ACTIVE | **否** | 不涉及 |
| ⑥ | 改 `contract-specs/**` 既有契约语义 | **否** | `contract-specs/**` 零触碰；`docs/contracts/**` 本轮亦未改 |
| ⑦ | 改 V3.0 总体架构 | **否** | 测试守卫内部实现 |
| ⑧ | 改正式项目范围 | **否** | 属 backlog 已登记残留的收口 |
| ⑨ | 删除已发布功能 | **否** | 只增不减；原字典序行为被**更强**的版本序替代且被用例钉住 |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | 零新依赖 |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 单文件局部；§3.3 已明确「不进主源码」避免第三所有者 |

**结论**：A 类，可直接实施，无需总控裁决。

---

## §6 证据（RED → GREEN → 变异探针 → 档级回归）

### §6.1 RED（真跑：先加用例，方法不存在）

- 事实：改前数轮尝试的命令参数错误与本次 RED **不得混同**，此处只登记有效轮次。
- **RED 有效轮次**：`mvn -o -pl ai-decision -am -Dtest=AiSqlDriftTest test`（`s329_red3.log`）
  ⇒ `exit=1`、`BUILD FAILURE`、`COMPILATION ERROR`，定位 `AiSqlDriftTest.java:[136,20] / [136,61] / [140,23] / [141,26] / [153,42]`
  「找不到符号：方法 `migrationVersion(java.nio.file.Path)`」等 ⇒ **行为缺失（编译红）**。
  （控制台按 GBK 解码中文时显示为乱码，`ERROR` 行与列号可读，不伪造成「断言失败」。）

### §6.2 GREEN（真跑）

- **目标用例**：`mvn -o -pl ai-decision -am -Dtest=AiSqlDriftTest test`（`s329_green1.log`）
  ⇒ `exit=0`、`BUILD SUCCESS`、`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`。
- **真跑用例名单（surefire XML 实读，8 条）**：`AI查询必须锁定单一快照`、`规则回退与少样本SQL只引用真实表和真实列`、
  `迁移解析覆盖后续ALTER加列`、`语义层不声明非ADS表`、`AI查询必须带参数化日期区间`、`语义层字段与DDL完全一致`、
  `少样本落在白名单表上`、**`迁移按版本序解析而非字典序`**（本项新增，**确认已真跑**）。
- **档级回归**：`default` 档 `s329_20260916_def1` ⇒ 见 §6.4。

### §6.3 变异探针（证明新用例有牙齿，真跑）

- 手法：把 `migrationFilesOrdered()` 的比较器**临时**换回字典序 `Comparator.comparing(p -> p.getFileName().toString())`，
  其余不动（`s329_mutation1.log`）。
- 结果：`exit=1`、`Tests run: 8, Failures: 1`，红项**正是**新用例
  `AiSqlDriftTest.迁移按版本序解析而非字典序`，`AssertionFailedError` 于 `AiSqlDriftTest.java:146`，
  消息含实测序列 **`[10, 1, 2, 3, 4, 5, 6, 7, 8, 9]`**。
- 该序列**同时**证明两件事：① 新用例的牙齿有效；② **真实目录下字典序确实错序**（V10 被排到第 1 位），
  即 backlog 所述残留是**真实存在**的，不是纸面推测。
- 还原：换回 `Comparator.comparingInt(AiSqlDriftTest::migrationVersion).thenComparing(...)`，
  并以 §6.2 的绿与 §6.4 的档级回归作为「已还原且仍绿」的实测凭证（**不**以「我记得改了回来」作凭证）。

### §6.4 档级回归（default 档，真跑）

- 命令：`& .\scripts\run-tests.ps1 -Suite default -RunId s329_20260916_def1 -LogDir .verify/s329_def1 -Confirm`
  （PowerShell 7；**未加** `-AllowCountDrift`）。
**量数轮 `s329_20260916_def1`（基线仍是 949，**未加** `-AllowCountDrift`）**：
  `analytics-server exit=1 Tests run: 950 (F=1 E=0 S=1)`、明细 **`93+350+163+93+94+157`**、`tests=950 **DRIFT**（基线 949）`；
  mall `13 MATCH`；generator `106 MATCH`；**三棵树 1069（基线 1068）** ⇒ **漂移量 = +1，来源唯一且已知**：
  `ai-decision` **93 → 94**，即本项新增的 `迁移按版本序解析而非字典序` 这 1 条（**有意的口径更新**，不是意外漏网）；
  唯一红仍＝**已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
  （`expected: 43 but was: 0`，`:61`；platform-app `Tests run: 157, Failures: 1`）⇒ `[FAIL exit=7]` **预期**。
- **基线更新（真跑，属本轮口径变更的一部分）**：`scripts/run-tests.ps1` 的 `$BaselineDefault['analytics-server']`
  `949 → **950**`，并追加 S3-29 注释块（记录量数轮 RunId、漂移来源、边界、收口轮 RunId）。`$BaselineSpark` **保持 303**
  （**本轮零 Scala 改动，spark 档未重跑**）。
- **收口轮 `s329_20260916_def2`（基线已更新为 950/1069，**未加** `-AllowCountDrift`）**：
  `analytics-server exit=1 Tests run: 950 (F=1 E=0 S=1)`、明细 `93+350+163+93+94+157`、**`tests=950 MATCH`**；
  mall `13 MATCH`；generator `106 MATCH`；**三棵树 1069（基线 1069）** ⇒ **计数全 MATCH、无 DRIFT**；
  唯一红仍为同一条已登记环境性红（同 `:61`）⇒ 结论「**计数 MATCH ＋ 唯一红＝该已登记环境性红**」，
  `[FAIL exit=7]` **预期**。**`-AllowCountDrift` 全程未用**（本轮不需要用它来掩盖任何计数变化）。
- **本轮未跑**：`isolated` 档、`spark` 档、任何 IT。**0 次连库**。

---

## §7 未测与边界（不得越界表述）

1. 本项**只证明** `AiSqlDriftTest` 的迁移解析顺序为版本序并有用例钉住；**不**证明 DDL 守卫整体完备。
2. `V10+` 语义「无害」的**当前结论依赖**「所有 ALTER 都只做加列」这一事实；若未来出现按序语义，
   本项的作用是「顺序已正确」，**不**代表「按序语义已被正确实现或验证」。
3. `warehouse/ddl`（`WarehouseNameLiteralGateTest`）的读取点**未改**、也**未加**顺序断言（顺序无语义）。
4. `db/meta/**` 迁移读取点**未改**（不经字典序判定路径）。
5. **未测**：真实 MySQL 8 应用迁移、`isolated` 档、`spark` 档、任何 IT、任何连库。
6. 本项**不**触碰 `contract-specs/**`、`README.md`、指导书/设计 V3.0、V2.x 历史文档、`scripts/run-isolated-tests.ps1`、
   `IngestionManifestRuntimePatrolTest`、`ReferenceMapping`/`map_layer_types()`、已发布 Flyway 迁移、3306/3307 数据。
7. backlog 该行的**历史描述**保留不动；新增「S3-29 更新描述」为 append-only 追加，**不删行、不改判类**。

---

## §8 结论与下一候选

- **结论**：backlog「迁移文件名按字典序排序」残留**已收口**（版本序唯一所有者 ＋ 8 条用例中含带牙齿的顺序自检），
  判 **A 类**；改前错序为**实测事实**（§6.3 序列 `[10, 1, …]`）。
- **下一轮选取方式**：按 `docs/PROJECT_STATUS.md` backlog **表序**重扫，取**第一个**「既未被他项阻塞、
  也未被判为需总控裁决」的项；本登记**不**预先承诺具体项（避免行号漂移导致误引）。
