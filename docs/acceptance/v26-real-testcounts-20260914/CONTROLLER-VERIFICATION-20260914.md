# 总控独立复核与裁决 —— 「真实测试数」泳道（lane R / code R）

- 日期：2026-09-14
- 复核基线：`HEAD = 064b733`（分支 `remediation/r1-boundary`）
- 复核人：总控会话（本文件唯一作者）
- 被复核交付物：`docs/acceptance/v26-real-testcounts-20260914/REPORT.md` + `raw/` 55 文件（泳道自述，**未提交未推送**）
- 本文件配套原始件：`raw/57-controller-independent-rerun-default-suite.log`（我亲取的默认档复跑全文）、`raw/58-3306-after-fingerprint.txt`（我亲取的 3306 after 指纹）

> 纪律：本节所有「已核」结论均由我**另行重取**（源码带 `file:line`、3307 直连、3306 before/after 同源 SQL、自己跑 Maven、自己按 XML 求和），不引用泳道自述作为证据。凡我未重取的，一律写「仅日志/未复核」。

---

## 1. 我独立重取的事实

| # | 事项 | 我的手段 | 结果 |
|---|---|---|---|
| 1 | 默认档真实测试数 | 我在主树自跑 `analytics-server` 全 reactor `mvn -o test`（**无** `integration.local.properties`，跑前实测该文件不存在） | `BUILD SUCCESS`，exit 0，`Total time: 37.178 s`；逐模块 **platform-common 81 / connection-ingestion 156 / warehouse-pipeline 134 / metric-analysis 48 / ai-decision 91 / platform-app 114 = 624**，`Failures 0, Errors 0, Skipped 0` ⇒ **与泳道逐模块完全一致** |
| 2 | `*IT.java` 是否被默认档执行 | 在我自己的复跑日志里逐类点名计数 | 5 个类命中 **全 0 次**；`Running com` 共 **72** 行 |
| 3 | 默认档是否碰库 | 同一日志计数 `jdbc:mysql` / `3306` / `3307` / `Connection refused` / `Communications link failure` | **全部 0 次** |
| 4 | 3306 是否零写入 | `scripts/00-3306-readonly-fingerprint.sql`（**与 before 同一份**）before 16:50:26 → after 17:11:36，区间**覆盖我整次 Maven 复跑**；机器逐行比对 | 稳定键 **22 行逐行一致**，仅 `captured_at` 不同；binlog `LAPTOP-8F8T3J1B-bin.000134` Position **46780 → 46780**；`Com_alter_table/create_user/drop_table/grant/truncate/replace` 全 0 不变，`Com_insert 73 / update 17 / delete 40 / create_table 35` 不变；三表 `CHECKSUM` = `1338236703 / 69673863 / 988794366` 不变；`analytics` 库 9 / 表 153 / `max_flyway_meta` 18 / `quality_rule_definition` 0 / `data_quality_result` 仍 14 列（无 V20 四列）不变；仅 `Questions 2293→2305`、`Uptime` 变（读计数与运行时长） |
| 5 | `TestIsolationGuard` 写前校验在 MySQL 8 上是否必败 | 3307 直连执行 `SELECT @@version_major` | `ERROR 1193 (HY000): Unknown system variable 'version_major'` ⇒ **复现**。源码顺序：`:509 @@server_uuid → :510 @@hostname → :511 @@version_major`，而 `:521 rejectForbiddenInstancePort`、`:524 FORBIDDEN_ACCOUNTS`、`:531 collectPrivileges`、`:534 fingerprintMatches` 全在它**之后** |
| 6 | 指纹词汇表是否不对称 | 读两侧实现 | analytics `TestIsolationGuard.fingerprintMatches(:689-697)` 接受 `server_uuid` **或** `hostname` **或** `hostname:<...>` 前缀；mall `IsolationGuard.fingerprintMatches(:396-402)` 只接受 `hostname` / `hostname:port` / 纯端口 / `127.0.0.1:port` / `localhost:port`，调用点 `:377` 传 `(expected, hostname, port)` ⇒ **从不接受 server_uuid**。runner `scripts/run-isolated-tests.ps1:50` 默认值即 server_uuid，`:154`/`:222` 注入它，`:223-225` 只设 `IT_GUARD_URL/USER/PASSWORD`（**不设** `SPRING_DATASOURCE_*`） |
| 7 | mall 30/30 假红是否真由该注入值造成 | 读原始日志锚点 | `raw/g3-03-isolated/isolated-mall.log` 逐字含 `MallIsolationException: [flyway-before-migrate] 实例指纹不匹配：登记为 de8e…`，合计 `Tests run: 30, Failures: 0, Errors: 30`；`raw/g3-04-fingerprint-probe/probe-mall.log`（仅把该值改为 `3307`）逐字含 `Tests run: 30, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 8 | `SourceRegistryMigrationMySqlIT` 是否结构性必败 | 读源码 | `:131-132` 逐字 `META_DB, META_DB`（metaDb 与 metricDb 同值）；`:148-165` 清单止于 `V18__source_warehouse_prefix.sql`；`:249` 断言 newest == V18；`:44` `@EnabledIfSystemProperty(p1.it)`；`:59` `requiredProperty("p1.it.metaDb", …)` 无默认值（fail-fast）⇒ 双重必败 |
| 9 | `AnalysisGoldenMySqlIT` 为何 BLOCKED | 读源码 + 3307 直查 | `:95-96` 硬编码 `analytics_metric` / `analytics_meta`；`:332` 仅 `mysql.host` 可覆盖；`:107-108` 键名 `metric.read.user` / `meta.app.user`；`:82` `@EnabledIfSystemProperty("metric.it")`。3307 上**不存在** `analytics_meta` 库（实存 `analytics_meta_v25it_…` / `_v25f88_…` / `_f88v20probe`），`analytics_metric` 仅一张 `__v25_w03_probe` ⇒ `Access denied … to database 'analytics_meta'` 根因成立 |
| 10 | 键名不一致 | 读源码 | `MetricAdsMySqlIT:100-101` 与 `MetricPublisherMySqlIT:99-100` 用 `metric.publish.username` / `metric.publish.password` / `metric.read.username` / `metric.read.password`；`AnalysisGoldenMySqlIT:107-108` 用 `metric.read.user` / `metric.read.password` / `meta.app.user` / `meta.app.password` ⇒ **一套配置无法同时满足** |
| 11 | 4 个 IT「绿构建·零执行」的机制 | 读源码 | `IsolationProfileCondition:30-39`：仅捕获 `MissingConfigurationException` → `disabled`（`:35-38`），`IsolationViolationException` 按 `:24` Javadoc 原样抛出。设计口径见 V2.5 §9.4「类级条件负责不执行」 |
| 12 | K-08 真实数 | 我按 15 个 XML 的 `tests=` 独立求和 + 读 `TestSuite.txt` | 逐套件 `7+5+7+3+5+9+5+10+8+7+6+11+22+6`（14 真 Spec）= **111**，`DiscoverySuite` 0 ⇒ **111**；`TestSuite.txt` = `Total number of tests run: 111` / `Suites: completed 15, aborted 0` / `succeeded 111, failed 0`；`com.graduation.analytics.MetricAdsSpecTest.txt` = `Tests run: 0`（该文件是全目录**唯一**含 `Tests run:` 者）⇒ 报告 mtime 09-14 17:02（主树） |
| 13 | `generator-output/` 归属 | 我列目录 + 查 gitignore | 根 `generator-output/` 7 文件（`e4c1000-*`、`m1-4-d10-failpath-*`，09-12）、`synthetic-data-generator/generator-output/` 148 文件（`cli-smoke-*`、`m1-4-d12-journal-*`，09-11~09-12）；分别被 `.gitignore:74` 与 `synthetic-data-generator/.gitignore:4` 覆盖 ⇒ 他线旧产物，**不得删** |
| 14 | mall / generator 默认档与隔离档口径自洽 | 读日志合计行 | mall 默认 `Tests run: 8` + 隔离 30 = **38**（=我的静态清点）；generator 默认 `Tests run: 101` + 隔离 19 = **120**（=我的静态清点）⇒ 无未解释差额 |
| 15 | 3307 收尾 | 我直连 | `@@server_uuid = de8ebbea-aff4-11f1-8037-00155d5dba47`；`v26it%` 库 **0**、`v26it%` 账号 **0**；既存 `analytics_metric` / `analytics_meta_*` / `mall_simulator` 未动 |

---

## 2. 采认与订正

### 2.1 采认（我另行重取且一致）
1. 默认档 `analytics-server` **624 / 0F / 0E / 0S**，逐模块 81/156/134/48/91/114。
2. 静态 − 实测闭合算式：591 静态 `@Test` − 5 个 `*IT` 的 19 = 572，+52 参数化/动态 = 624；差额来源为 `*IT.java` 不匹配 surefire 默认 include（我复核：5 类命中 0 次、`Running` 72 行）。
3. 默认档零碰库（日志 0 命中）且 **3306 位点级零写入**（见 §1#4）。
4. G3-04 探针把 mall 由 30E 变 30 全绿，机制为指纹注入值。
5. K-08 主树读数 111/15 及其「`Tests run:` 必得 0 或漏算」的机制。
6. G2 全部 exit 1 / 0 通过；`@@version_major`、`META_DB, META_DB`、硬编码库名、键名不一致四个根因。
7. `generator-output/` 属他线证据，保留正确。

### 2.2 订正（泳道口径需要改写的三处）
- **R-C1**：`*IT.java` 未被默认拾取（**没跑**）与 4 个 IT 点名时「绿构建·零执行」（**跑了、按设计 disabled**）是**两件独立的事**，报告已分列，正确；但对外口径必须**禁止**把 624 说成「全部测试」。→ 统一写法：「默认档 624 + 未入默认档的 19 个 DB 用例」。
- **R-C2**：`Tests run: 0` 出现在 `MetricAdsSpecTest` 是 surefire 误拾 Scala 类所致，**不代表 ScalaTest 没跑**；`spark-jobs` 的真实数只认 `TestSuite.txt` 的 `Total number of tests run:` 与逐套件 XML。
- **R-C3**：`100/14` 属**附属工作树** `GraduationProject-wt\m3-jdk8fix` 的 09-12 旧跑动（日志内 `Finished at: 2026-09-12T16:12:17`、classpath 指向该 worktree），仅因收集日期是 09-14 而被归入该证据目录 ⇒ **不得引用为当前主树值**；证据目录日期 ≠ 跑动日期 ≠ 工作树。

---

## 3. 裁决（行动项）

| 编号 | 裁决 | 依据 | 归属 |
|---|---|---|---|
| **R-01** | **采认 G1 结论**：默认档 624 全绿，且为**位点级**零写入 3306。这是本项目第一份「默认档不碰正式库」的实测证明。 | §1#1/#3/#4 | 已闭合 |
| **R-02** | **K-10（新登记，优先级最高·阻断级）**：`TestIsolationGuard:511 SELECT @@version_major` 在 MySQL 8 必败 ⇒ 写入型 IT 在 3306 与 3307 上都到不了任何断言；`:521/:524/:531/:534` 这些**真正的安全判据在真库上一次都没执行过**。修法：改 `SELECT VERSION()` 解析或改用 `@@version`（两者都是 MySQL/MariaDB 共有）；**本轮不改**，另立泳道，并须补一条「在真库上跑通 L521-L541」的取证。 | §1#5 | 新泳道（代码变更） |
| **R-03** | **K-11（新登记·阻断级）**：runner 注入 `server_uuid` 而 mall/generator 门禁不认 ⇒ **30/30 假红**。修法**已闭合**：注入 `hostname:port`（如 `dahaishui:3307`）可同时满足 `TestIsolationGuard:689-697`（认 hostname 前缀）与 mall `:396-402`（认 host:port）；同批须补 generator 的 `SPRING_DATASOURCE_*`（runner `:223-225` only IT_GUARD_*）。修完必须复跑 `run-isolated-tests.ps1` 取「mall 30 全绿 / generator ≥14 转绿」的实测。 | §1#6/#7 | 新泳道（脚本变更，R4 授权范围内） |
| **R-04** | **K-13（新登记·阻断级）**：`SourceRegistryMigrationMySqlIT` 双重必败（`context():131-132` 同库；`EXPECTED_META_SCRIPTS:148-165` 止于 V18 而树内已有 V19/V20）。**直接牵连 F-88**：V20 迁移在本仓库**没有任何 IT 覆盖**，D-5「一次误启动即迁移 3306」目前只有静态守卫、无测试守卫。→ 与 F-93 合并整改，登记为 F-88 收口的前置项。 | §1#8 | 与 F-93 合并 |
| **R-05** | **`AnalysisGoldenMySqlIT` 判 BLOCKED（环境性）**，非泳道缺陷：黄金值依赖只在 3306 存在的历史快照，且库名硬编码导致无法重定向到隔离库。整改要求：库名走 `metric.it.*` 配置键；否则该类**永远不可在隔离库执行**。**泳道绝未指向 3306，行为正确。** | §1#9 | 新泳道（小改） |
| **R-06** | **键名不一致采纳为配置契约缺陷**：`metric.publish.username|metric.read.username` vs `metric.read.user|meta.app.user`。V2.4 须登记**唯一权威键名表**，旧键保留为别名并加静态检查（沿用 K-04 的处理方式）。 | §1#10 | V2.4（本总控） |
| **R-07** | **K-08 可关闭**：主树实测 111/15，我独立求和一致。**但口径必须同时登记**：任何以 `Tests run:` 汇总 `spark-jobs` 的做法必得 0 或漏算。 | §1#12 | 关闭 + V2.4 记口径 |
| **R-08** | **K-12（新登记）**：`spark-jobs` 是独立 POM，不在 `analytics-server` reactor，也不在 `run-isolated-tests.ps1`（只覆盖 mall/generator）内 ⇒ 111 个 ScalaTest 与 19 个 DB 用例**都没有自动执行入口**。建议增加聚合入口；本轮只登记不实施。 | §1#12 + REPORT §5 | 登记 |
| **R-09** | **订正我此前的指示（见 §4 勘误）**：4 个 IT 的「绿构建·零执行」是 `IsolationProfileCondition` 的**设计行为**（V2.5 §9.4），**不判为门禁缺陷**；仅 `SparkStageExecutorSmokeIT` 走 `SparkItGuard` 硬失败。 | §1#11 | 口径订正 |
| **R-10** | **今后泳道在控制方复核前不得删除隔离库/账号**（改为「留库 + 交付口令/配置」），否则 G2/G3 末态无法复取。本轮删除已实测 `v26it%` = 0/0、既存库未动，**采认本次**。 | §1#15 | 流程约定 |
| **R-11** | **采信泳道的两处自我披露**：G2 首轮 `MANGLEDARGS`（PowerShell `@('a='+$x,'b='+$y)` 逗号优先级低于 `+`，7 元素变 10 个）——双轮留档、以二轮为准，记「证据残缺 + 踩坑」；`%TEMP%\v25-it-system-properties.marker` 实为来源 label（`TestIsolationGuard:220-222`）而非被读取的文件——澄清正确。 | 泳道自述 + 源码 | 采信 |
| **R-12** | 泳道**未改任何 POM/代码/脚本**（我核 `git status`：56 文件全在 `docs/acceptance/` 内） ⇒ 符合「只取证不改动」边界。 | `git status` | 采信 |

---

## 4. 勘误（含我自己的）

1. **我自己的勘误（重要）**：我在派单时要求泳道「期望并必须逐条记录 5 个 IT 以 `MissingConfigurationException` **失败**」——这**是我的预判错误**。实际：`IsolationProfileCondition:35-38` 只把 `MissingConfigurationException` 转成 `disabled`，故 4 个 IT 点名时是**绿构建·零执行**（skip），只有 `SparkStageExecutorSmokeIT`（走 `SparkItGuard`）硬失败。源码层面这属设计行为（V2.5 §9.4），**不是门禁失效**；真正的门禁失效是 R-02 的 `@@version_major`。
2. **我自己的勘误（次要）**：我第一轮核 `g3-04` 证据时按**文件名**匹配 `*g3-04*`，未命中（该编号在**目录名**上） ⇒ 我一度误判「探针日志缺失」。已改按目录遍历复核，探针证据存在且有效。
3. **口径勘误（对外必须改）**：任何把默认档 `624` 表述为「本项目全部自动化测试」的说法作废；正确口径见 §5。
4. **历史证据勘误**：`100/14` 不得当作主树现值（R-C3）。

---

## 5. 分级结论（本泳道）

| 维度 | 判定 | 依据 |
|---|---|---|
| **提交完成** | ✅ 是（文件已暂存，**提交与推送由总控在本文件之后执行**） | `git status`：56 文件暂存、无其他残留 |
| **测试通过** | ⚠️ **仅默认档**（`analytics-server` **624**、`mall-simulator` **8**、`synthetic-data-generator` **101**、`spark-jobs` **111**，合计 **844** 全绿；其中 `analytics-server` 624 与 `spark-jobs` 111 已由我独立复取） | §1 |
| **限定验收** | ✅ 可给（范围为：默认档测试数结论 + 静态-实测对齐 + 门禁失败根因定位 + 未取证清单） | 本文件 §1-§3 |
| **完整验收** | ❌ **不通过** | 五条硬理由：① 5 个 `*IT` 默认档 0 执行；② 4 个 IT 点名时绿构建零执行、1 个硬失败；③ 隔离档 runner `exit 7`（mall 30 全红系配置缺陷、generator 19 未过）；④ 写入型 IT 被 `@@version_major` 卡死，**0 个真实断言执行**；⑤ `SourceRegistryMigrationMySqlIT` 结构性必败 |

**统一对外口径（后续一律照此写）**：
- 默认档：`analytics-server 624` + `mall-simulator 8` + `synthetic-data-generator 101` + `spark-jobs 111`（独立 POM、须 JDK8）= **844**。
- 未入默认档：`*IT.java` **19** 个用例（0 通过）、mall `@Tag("it")` **30** 个（探针下 30 全绿）、generator `@Tag("it")` **19** 个（探针下 5E） = **68** 个（**0 通过**）。
- 禁止表述：❌「全量测试 624 全绿」❌「隔离真跑已通过」❌「规则版本化已闭合」。

---

## 6. 我未复核（不得当作我已背书）

- mall `8` / generator `101` 两个默认档读数：**仅取泳道日志**（`raw/g3-01-*`），我未复跑 Maven。
- G2 的「Maven `-D` 到达 fork JVM」「JDBC 连接成功建立而非认证失败」：仅凭异常类型推断，我未复取。
- 3307 上 `GRANT USAGE ON *.*` 是否会被 `collectPrivileges` 判为非只读：**未取证**（被 R-02 提前挡住）。
- `runtime_profile` 归属冲突的实测后果：**未取证**。
- `MetricPublisherMySqlIT.productionDatabaseIsRejectedBeforeAnyWrite` 负向用例：**未取证**。
- `spark-jobs` 的 JDK17 表现、真实 `spark-submit` 场景：**未取证**。
- `SparkStageExecutorSmokeIT` 用嵌入式 Derby（非 MySQL）这一事实为静态阅读，我未实跑。

---

## 7. 决策点（需总负责人拍板）

1. **是否立刻另立「门禁修复」泳道**（R-02 + R-03 合并）：两处改动都小，但不修则**隔离真跑与写入型验收在本项目内不可能通过**，且「完整验收」永久不可达。建议：先修 R-02/R-03 → 复跑 `run-isolated-tests.ps1` 取实测 → 再评估 R-04。
2. **是否把 R-04（V19/V20 无 IT 覆盖）并入 F-93**：这直接决定 F-88 能否从「限定验收」升到「完整验收」。
3. **是否现在写 V2.4 设计文档**（唯一作者=本总控）：需登记 K-10~K-13、权威键名表、K-08 口径、证据 provenance 陷阱、PowerShell `-replace`/逗号优先级两个踩坑。
