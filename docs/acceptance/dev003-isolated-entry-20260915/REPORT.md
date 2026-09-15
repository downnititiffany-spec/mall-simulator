# DEV-003a／DEV-003b 验收报告 —— generator 首跑 schema 编排 ＋ 隔离档统一入口接入 analytics

- 泳道：`docs/acceptance/dev003-isolated-entry-20260915/`
- 时间：2026-09-15 10:34–10:45（+0800）
- 采集者：代码 Agent（本轮唯一动作 DEV-003a／DEV-003b，未扩范围）
- 唯一隔离实例：`127.0.0.1:3307`（canonical `dahaishui:3307`，`@@server_uuid = de8ebbea-aff4-11f1-8037-00155d5dba47`，MySQL 8.0.41）
- 宿主 `3306`：**全程只读、零写入**（本轮未对其执行任何 DDL／DML／迁移／切换）
- 治理基线：指导书 V2.8 ＋ 设计 V2.5（只读，未改动）；代码基线 `eb6bd27`（＝ `origin/main`）
- **本轮改动未提交、未 push**（提交由总控复核后另行执行）

---

## 0. 两条任务的原始要求与完成判据

| 编号 | 原始表述 | 本轮判据 | 结论 |
|---|---|---|---|
| DEV-003a | 「generator 新 runId 的隔离库在 `GeneratorMetaStoreTest` 执行前没有完成 Flyway 初始化」 | 任意**全新 runId** 首次执行时，generator 隔离测试不依赖历史库状态，在相关测试执行前完成必要 schema migration | **达成**（§2、§3） |
| DEV-003b | 「`IsolationGuardMySqlIT` 当前只能显式 `-Dtest=IsolationGuardMySqlIT` 点名执行」 | 接入项目统一隔离测试入口，标准命令自动发现并执行，不依赖人工记类名 | **达成**（§4、§5） |

**总控 2026-09-15 复核裁决的 DEV-003 内部子项编号（不新立主编号，不碰 DEV-004／DEV-005）**：

| 子项 | 内容 | 状态 |
|---|---|---|
| **DEV-003a** | generator fresh schema／Flyway 前置初始化 | **CLOSED**（本报告 §2／§3） |
| **DEV-003b** | `IsolationGuardMySqlIT` 接入标准 `isolated-tests` 入口 | **CLOSED**（本报告 §4／§5） |
| **DEV-003c** | `spark-jobs` **111 个 ScalaTest 缺少项目级统一自动执行入口** | **OPEN** |
| **DEV-003d** | **其余未打标 `*IT` 尚未纳入标准自动执行入口** | **OPEN** |

**DEV-003 总体：部分收口 —— a／b 已关闭；整体尚未关闭。** 不得由「a／b 已关闭」推出「DEV-003 已结束」。

**范围外（本轮未做、不得据此宣称已完成）**：GitHub Actions／CI、DEV-003c／DEV-003d 本身、`SourceRegistryMigrationMySqlIT`（DEV-004 双重必败）、`AnalysisGoldenMySqlIT`（库名硬编码）。

---

## 1. 缺陷机制（DEV-003a，修前实测归因）

`GeneratorMetaStoreTest`（`synthetic-data-generator/src/test/java/com/graduation/generator/meta/GeneratorMetaStoreTest.java`）**走裸 JDBC**（`DriverManagerDataSource`，无 Spring 上下文 ⇒ 从不触发 Spring 侧 Flyway）；而同一 Maven 进程内它**先于**所有 `@SpringBootTest` 类执行。于是：

- **全新 runId 首跑**：表还不存在 ⇒ 5 个用例全 error；
- **同库二次跑**：表已被上一轮 Spring 上下文的 Flyway 建好 ⇒ 19 个用例全绿。

即「**状态依赖的假绿**」。这与用例顺序、与「Flyway 是否从未执行」都不同——修前实测证明 Flyway 只是**执行得太晚**。

## 2. 修法（DEV-003a）：测试编排前置，不改业务逻辑、不改用例顺序

新增 `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java`（7110 B／128 行，`implements BeforeAllCallback`），在 `GeneratorMetaStoreTest` 上以
`@ExtendWith(com.graduation.itguard.IsolatedSchemaInitializer.class)`（`GeneratorMetaStoreTest.java:74`）挂接。执行序为：

1. `requireEnabled`（隔离开关）→ `require(url/user/password)` → `assertUrlAllowed`：**缺配置即硬失败（不 skip）**；
2. `IsolationGuard.verifyBeforeWrite(ds, expectedDb, "isolated-schema-init:<Class>")`——**门禁在 DDL 之前**，不绕过任何隔离判据；
3. `Flyway.migrate()`（`locations=classpath:db/generator`，`baselineOnMigrate=true`）；
4. `assertSchemaReady`：查 `information_schema` 复核 5 张表（`generator_target`／`generation_plan`／`generation_run`／`generation_artifact`／`generation_event_stat`）确实存在，缺任一即 `IllegalStateException`（**不 catch「表不存在」把它变成通过**）；
5. 打印 `[DEV-003a] <Class> 前置编排完成：database=… runId=… | flyway 本次执行迁移数=… 目标版本=…` 与逐行 `flyway_schema_history`。

**禁止项逐条对照（总控原文）**：

| 禁止项 | 本方案 |
|---|---|
| 预先人工建表 | 未建任何表；建表动作全部由 Flyway 在测试执行前完成 |
| 复用旧 runId | 验收用**全新** runId `dev003_20260915_1110`（pre 取数：0 表、0 历史，见 §3） |
| 在测试里 catch「table doesn't exist」 | 未出现；`assertSchemaReady` 只做**正向断言**（表必须在） |
| 把 `GeneratorMetaStoreTest` 改成跳过 | 未改 `assumeTrue`／`@Disabled`；该类 5 例真实执行 |
| 依赖前一个 SpringBootTest 恰好先执行 | 改为扩展在**本类执行前**自足完成迁移；两者无顺序依赖 |
| 为让测试绿把执行顺序写死 | surefire 未加任何 `runOrder`／`dependencies`；迁移由扩展自带 |

「优先修测试编排，不要修改业务逻辑去适应缺表」——本轮**未改任何 main 源码**，唯一新增文件在 `src/test/java`。

## 3. 实测证据

### 3.1 修前红案（可复现的缺陷现场）

runId `dev003a_20260915_1040`（全新），`-Module generator`：模块 `Tests run: 19, Failures: 0, Errors: 5, Skipped: 0`，runner `exit 7`。

- 首个 `doesn't exist`：日志 `:56`（`Table 'dev003a_20260915_1040_generator.generation_event_stat' doesn't exist`），次个 `:67`（`generation_run`）——**`GeneratorMetaStoreTest` 先跑**；
- Flyway 直到 Spring 上下文才执行：`:284-294`，`10:34:49.198 Creating Schema History table …`、`10:34:49 Successfully applied 1 migration … now at version v1`；
- 原始日志：`raw/00-prefix-RED-generator-firstrun-no-schema-init.log`（43,018 B）。

### 3.2 修后：全新 runId **首次**执行即绿（runId `dev003_20260915_1110`）

**首跑前取数（`raw/01-…console…txt` §[2]）**

```
dev003_20260915_1110_generator  tables_now = 0
dev003_20260915_1110_mall       tables_now = 0
flyway_hist_generator = 0
flyway_hist_mall      = 0
```

**首跑后取数（`raw/03-isolated-instance-facts.txt`、`raw/01-…console…txt` §[6]）**

```
dev003_20260915_1110_generator  tables_after = 6      ← 5 业务表 ＋ flyway_schema_history
dev003_20260915_1110_mall       tables_after = 13
version  description      installed_on          success
1        generator meta   2026-09-15 10:42:04   1
```

**日志时序（`raw/11-isolated-generator.log`）**——编排在用例之前、且本次迁移**真的发生**（迁移数=1，而非 `No migration necessary`）：

```
:38  10:42:04.854 Successfully applied 1 migration to schema `dev003_20260915_1110_generator`, now at version v1
:39  [DEV-003a] GeneratorMetaStoreTest 前置编排完成：database=dev003_20260915_1110_generator
     account=dev003_20260915_1110_genapp@% hostname=dahaishui port=3307 runId=dev003_20260915_1110
     | flyway 本次执行迁移数=1 目标版本=1
:40  [DEV-003a] GeneratorMetaStoreTest flyway_schema_history: rank=1 version=1 description=generator meta
     installed_on=2026-09-15 10:42:04.0 success=true
:46  [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.generator.meta.GeneratorMetaStoreTest
:86  [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.generator.web.GeneratorApiSmokeTest
:149 [INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.generator.web.MallApiGenerationSmokeTest
:153 [INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0          ← generator 模块汇总
```

**结果**：generator 隔离档 **19 run / 0 F / 0 E / 0 S**，其中 `GeneratorMetaStoreTest` **5 / 0 / 0 / 0**，且这是该 runId 的**第一次**执行（无二次跑补绿）。

## 4. 修法（DEV-003b）：把真库 IT 接进项目统一隔离入口

**分类口径（沿用 V25-S02／K-02 既有形态，未新造机制）**

- `analytics-server/metric-analysis/pom.xml`：新增 `<properties><v25.it.excluded.groups>it</v25.it.excluded.groups></properties>`（`:78`）＋ surefire `<excludedGroups>${v25.it.excluded.groups}</excludedGroups>`（`:90`）⇒ **默认档排除 `@Tag("it")`**；
- 新增 profile `isolated-tests`（`:100`）：清空该属性（`:103`）＋ `<groups>it</groups>`（`:113`）＋ **必须显式** `<includes><include>**/*IT.java</include></includes>`（`:117`）。
  类名 `IsolationGuardMySqlIT` **不在** surefire 默认包含式（`Test*`／`*Test`／`*Tests`／`*TestCase`）内，缺 `includes` 就是 `Tests run: 0 / BUILD SUCCESS` 的假绿；
- `IsolationGuardMySqlIT.java` 加 `import org.junit.jupiter.api.Tag;`（`:6`）与 `@Tag("it")`（`:47`），并把类 javadoc 的「入口」段（`:35`）由「必须 `-Dtest=` 点名」改写为「由隔离档自动收集」；
- `scripts/run-isolated-tests.ps1`（唯一入口）新增 `-Module analytics|all`（`ValidateSet` `:69`）与 analytics 目标（`:139-147`）：库／账号**复用** `${RunId}_mall`／`${RunId}_mallapp`（**不新建任何 3307 对象**），命令 `-f analytics-server\pom.xml -pl metric-analysis -am test` ＋ 全局 `-Pisolated-tests`；因 `IsolationGuardMySqlIT` 只读环境变量 `DEV001_IT_*`（无 `*.local.properties` 档案），循环内按本 runId 注入 `DEV001_IT_URL/USER/PASSWORD/RUNID/FINGERPRINT`（`:312`），非 analytics 目标则清除（`:319`）；
- **零用例硬门禁**（`:334-342`）：跑完强制在日志里找 `-- in …IsolationGuardMySqlIT` 的 `Tests run:` 行；找不到即把该模块退出码置 7（「被点名的隔离类没跑到」不允许以 `BUILD SUCCESS` 混过）。这正是 DEV-003b 要消灭的假绿形态之一；
- 默认档**刻意不写** `<includes>`：默认包含式保持有效，故本模块默认档选中集合与本改动前**逐一致**（见 §5.3 的 48）。

**为什么把 profile 属性写成用户属性**：把 `it` 字面量写死进 `<configuration>` 后，`-DexcludedGroups=` 无法清除它，隔离档会拿到 `Tests run: 0`；写成 `${v25.it.excluded.groups}` 才能被 profile 清空。

## 5. 实测证据（DEV-003b 与回归）

### 5.1 标准入口自动执行（无需人工类名）

```
=== 运行 analytics 隔离套件：mvn -o -Dmaven.repo.local=... -f analytics-server\pom.xml -pl metric-analysis -am test （MAVEN_ARGS=-Pisolated-tests）===
:77  [INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0                    ← platform-common（同 reactor 内，无 *IT）
:110 [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.analytics.metric.IsolationGuardMySqlIT
:114 [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0                      ← metric-analysis（隔离档只选 @Tag("it")）
  analytics exit=0   [IsolationGuardMySqlIT] [INFO] Tests run: 6, …
[PASS exit=0] 全部门块隔离套件通过。
```

命令为 `pwsh -NoProfile -File scripts/run-isolated-tests.ps1 -RunId dev003_20260915_1110 -Module all -Confirm`（**无 `-Dtest=`**），runner 退出码 **0**。原始日志 `raw/12-isolated-analytics.log`。

### 5.2 mall ／ generator 隔离档（同一次 `-Module all`）

| 模块 | Tests run | F | E | S | exit | 日志 |
|---|---|---|---|---|---|---|
| mall | 30 | 0 | 0 | 0 | 0 | `raw/10-isolated-mall.log:297` |
| generator | 19 | 0 | 0 | 0 | 0 | `raw/11-isolated-generator.log:153` |
| analytics（IT） | 6 | 0 | 0 | 0 | 0 | `raw/12-isolated-analytics.log:110` |

### 5.3 默认档回归（必须**不受**隔离档改动影响）

| 树 | Tests run | F／E／S | exit | 与基线比对 | 日志 |
|---|---|---|---|---|---|
| `analytics-server` 6 模块 | **632** = 89＋156＋134＋48＋91＋114 | 0／0／0 | 0 | **与「语义一致性收口轮」逐模块相同**；`metric-analysis` **48**（改动前亦 48）⇒ 默认档选中集合未变 | `raw/20-default-analytics-reactor.log` |
| `synthetic-data-generator` | **106** | 0／0／0 | 0 | 与基线相同 | `raw/21-default-generator.log` |
| `mall-simulator` | **13** | 0／0／0 | 0 | 与基线相同 | `raw/22-default-mall.log` |
| `spark-jobs` | **111**（ScalaTest） | — | — | **沿用既有证据口径，本轮未复跑，不属于本轮 fresh 实测结果** | — |

默认档内 `*IT` 类被选中数：analytics **0**、generator **0**、mall **0**（脚本按 `-- in .*\bIT\b` 判定）。

**当前默认档汇总口径（必须逐项拆开写，禁止只写 `862`，禁止把 111 说成本轮 fresh 实测）**：

```text
当前默认档汇总口径：862
= 632  analytics-server（本轮实测）
+  13  mall（本轮实测）
+ 106  generator（本轮实测）
+ 111  spark-jobs（沿用既有证据口径，本轮未复跑）
```

> **`spark-jobs` 111：沿用既有证据口径，本轮未复跑，不属于本轮 fresh 实测结果。**

隔离档本轮实测数字只按「被选中并真跑」计：mall 30 ＋ generator 19 ＋ metric-analysis 的 IT 6 ＝ **55**；analytics 那次调用**不得**写成「隔离 IT 95」——正确写法是「**metric-analysis IT 6 ＋ reactor 依赖 platform-common 89**」（89 是默认档用例，只是随 `-am` 一起进了同一 reactor）。

### 5.4 3306 与实例身份

- 3306：本轮全程未执行任何读写（无 `mysql -P3306` 调用、无迁移、无切换）；隔离实例身份经 runner 门禁 6 探针确认为 `dahaishui:3307`，`@@server_uuid` 与既有登记一致（`raw/03-isolated-instance-facts.txt`）。
- 本轮新增 3307 对象仅限 `dev003_*` 前缀库／账号（prep 脚本幂等创建，未触碰既有 `dev002sem_*`／`dev12fix_*`／正式库）。

## 6. 未取证／未测（显式标注，不得读成「已验证」）

1. `spark-jobs` **111：沿用既有证据口径，本轮未复跑，不属于本轮 fresh 实测结果**（862 中的 111 即此项，见 §5.3 拆解块）；
2. `IsolationGuardMySqlIT` 在 `-Module analytics` 单模块下的表现**未单独跑**（本轮为 `-Module all` 组合内执行）；
3. 未打标的 5 个 `*IT`（`AnalysisGoldenMySqlIT`、`MetricAdsMySqlIT`、`MetricPublisherMySqlIT`、`SourceRegistryMigrationMySqlIT`、`SparkStageExecutorSmokeIT`）**仍未进入任何自动入口**；其中 `AnalysisGoldenMySqlIT` 库名硬编码、`SourceRegistryMigrationMySqlIT`（DEV-004）结构性必败，本轮**未处理**；
4. 「账号在本次测试库之外还有写权限」判据在真 3307 上**未实测**（沿用 `IsolationGuardMySqlIT` 类内既有标注）；
5. `IsolatedSchemaInitializer` **只被 `GeneratorMetaStoreTest` 使用**；其它裸 JDBC IT 的复用**未验证**；
6. Windows 工作区／Git blob 两套哈希口径问题（既有登记）对本 lane 同样适用：`evidence-manifest.txt` 内为**工作区口径** SHA256。

### 6.1 本轮登记、**不阻塞提交**的已知局限（总控 2026-09-15 复核：接受／登记，本轮不重构）

| # | 事项 | 处置 |
|---|---|---|
| L-1 | `isolated-tests` profile 只存在于 `metric-analysis`，`-pl metric-analysis -am` 会把 reactor 依赖 `platform-common` 的 89 个默认档用例一并跑进来 | **接受**；文案统一为「metric-analysis IT 6 ＋ reactor 依赖 platform-common 89」，**禁止**把 95 说成「隔离 IT 95」 |
| L-2 | 零用例硬门禁（runner `:334-342`）依赖 Surefire 的 `-- in <class>` 文本格式；Surefire 升级或改输出模式后可能误判 | 登记为已知局限；当前形态 **fail-closed**（最多假红，不会假绿），风险可接受 |
| L-3 | `IsolatedSchemaInitializer.REQUIRED_TABLES` 硬编码 5 张表名（当前与 `db/generator` 一致） | 登记为**维护性风险**，不在本轮重构；后续迁移增表时再考虑从 Flyway／schema metadata 派生 |
| L-4 | `scripts/run-isolated-tests.ps1` 自身没有自动化测试（本轮 `-Module all` 初版漏选即由此暴露） | 登记；建议在接 GitHub Actions 之前单独给 runner 的参数组合补 Pester／PowerShell 测试，**不塞进本轮** |

## 7. 本轮不做的事（与总控边界一致）

- 未新建 `.github/workflows/**`、未接 CI、未新增任何流水线文件；
- 未修改任何 main 源码、未修改 mall 侧 Flyway 编排、未修改 `synthetic-data-generator/pom.xml`（其 surefire 沿用既有 `<excludedGroups>`＋profile 形态）、未修改 `analytics-server/pom.xml`（其 `<argLine>-Djdk.attach.allowAttachSelf=true</argLine>` 未被触碰）；
- 未改 `contract-specs/**`、`scripts/check-bare-anchors.ps1`、`remediation-status.md`、`docs/thesis-materials/**`、已有 `docs/acceptance/**` 其它泳道、`docs/backups/`、派生工作树、指导书 V2.8、设计 V2.5；
- 未 commit、未 push（等待总控复核）。

## 8. 本轮改动文件（5 路径：4 改 1 新）

| 路径 | 类型 | 说明 |
|---|---|---|
| `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java` | 新增 | 7110 B／128 行，`BeforeAllCallback`：门禁 → Flyway → 表存在性自检 |
| `synthetic-data-generator/src/test/java/com/graduation/generator/meta/GeneratorMetaStoreTest.java` | 改 | ＋`@ExtendWith`（`:74`）与 DEV-003a 说明（`:57`）；`+15` 行 |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/IsolationGuardMySqlIT.java` | 改 | ＋`import Tag`（`:6`）、`@Tag("it")`（`:47`）、入口 javadoc 改写（`:35`）；`+13 −3` |
| `analytics-server/metric-analysis/pom.xml` | 改 | ＋`<properties>`／surefire `excludedGroups`／`isolated-tests` profile；`+52` |
| `scripts/run-isolated-tests.ps1` | 改 | ＋analytics 目标、`-Module analytics\|all`、`DEV001_IT_*` 注入、零用例硬门禁；`+84 −15` |

`git status --porcelain`（落库前）：4 改 1 新 ＋ 本泳道目录（未跟踪）；无其它改动。全文见 `raw/04-code-diff-and-status.txt`。

## 9. 证据清单与自查

- 清单：`raw/evidence-manifest.txt`（逐份 字节数 ＋ SHA256 ＋ 用途）；
- 敏感词扫描：`raw/05-sensitive-scan.txt` —— 命中仅为掩码形态 `<x******…长度 24>`、键名（`IT_GUARD_PASSWORD` 等）与引用名 `credref:<runId>-<module>`；**以本轮真实口令做全 lane 子串搜索：0 命中**（无明文口令落盘）；
- 自查中发现并当场修掉的自身缺陷（如实登记）：① 锚点手抄导致补丁 0 匹配（改为**一切锚点从目标文件本身取**）；② 新增 analytics 目标块时多写一个 `}`，`Parser` 报 `148 行 意外的标记 }`（已删并复检：解析错误 0）；③ `-Module all` 初版只选中 analytics（mall／generator 条件漏 `'all'`），**在正式取证前由「日志目录只有 analytics 一份」暴露并修复**，复跑 `-DryRun` 确认三目标齐全后才做 §3.2／§5 取证；
- 证据为 append-only：本 lane 内文件一经生成不再改写；如后续发现差错，另立勘误文件并注明「以此为准」。

## 10. 落库（总控 2026-09-15 复核通过后执行）

- **commit 1（代码／配置，5 路径，逐路径显式 `git add`，未用 `git add -A`）**：`analytics-server/metric-analysis/pom.xml`、`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/IsolationGuardMySqlIT.java`、`scripts/run-isolated-tests.ps1`、`synthetic-data-generator/src/test/java/com/graduation/generator/meta/GeneratorMetaStoreTest.java`、`synthetic-data-generator/src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java` —— message `fix(it): close DEV-003a DEV-003b isolated test bootstrap and entry`。
- **commit 2（证据／状态）**：`docs/PROJECT_STATUS.md` ＋ 本泳道 `docs/acceptance/dev003-isolated-entry-20260915/**` —— message `docs(acceptance): archive DEV-003a DEV-003b isolated test evidence`。
- 两个 commit 完成后 `git status --porcelain` 必须 **0 行**，随后 `git push origin HEAD:main`。
- 本轮**只**闭合 DEV-003a／DEV-003b；DEV-003c（`spark-jobs` 111 自动入口）／DEV-003d（其余未打标 `*IT`）**仍 OPEN**，DEV-003 总体保持开启；F-88 **仍为限定验收**，不升级。
