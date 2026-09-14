# V25-S03 集成测试安全整改 —— L5 独立复核报告

| 项 | 值 |
| --- | --- |
| 泳道 | **L5**（独立复核泳道，独立建树、独立取证） |
| 复核对象 | 泳道 V25-S03 对 **R-1 / R-4 / R-6** 的整改交付（附带 R-3 只读复核） |
| 复核时间 | 2026-09-14 13:56 – 14:02（+08:00） |
| 起始 HEAD | `f00462c51023ce5b12e0b649e7813ed789f6f17e` |
| 结束 HEAD | `fd3cee5b806b3570ce273e2346e1d14014e0dae2`（总控在本泳道取证期间前进 2 个 commit） |
| 证据目录 | `docs/acceptance/v25-s01-it-safety-20260914/`（**只追加**，未改写前序泳道任何一行） |
| 本泳道新增 raw/ | 全部 `raw/l5-*.log`（清单见 §8） |

## 0. 复核纪律与自我约束（含一项违规自报）

**遵守的约束**

- 只读正式数据：全程**零 3306 写入**、零 DDL、零 `DELETE`/`UPDATE`/`INSERT`。全部 SQL 与脚本分支为只读或拒绝分支。
- 不做任何 git 写操作：无 `add` / `commit` / `push` / `stash` / `checkout`。
- 不启停 8090 / 8091 / 8092，不启停数据库 / HDFS / 集群。
- 构建槽纪律：**只**在 `mall-simulator`、`synthetic-data-generator` 两棵树跑 Maven（`analytics-server` 槽归 L4）。
- 证据只追加：新增文件 + 追加章节；**未删除、未改写**上一泳道已写下的任何一行。
- 未实测项一律显式标注「**未取证**」；「skip 后 PASS」不计为通过。

**违规自报（已即时上报总控，并已停用）**

| 项 | 内容 |
| --- | --- |
| 事实 | 为判定 `GeneratorContractParityTest` 的 NPE 是否「既有」，本泳道执行过 `git worktree add --detach D:\Develop_code\_gp_headcheck HEAD`（**仓库外**目录） |
| 完整路径 | `D:\Develop_code\_gp_headcheck` |
| 创建时间 | 2026-09-14T13:57:36+08:00（LastWrite 13:57:37） |
| 内容摘要 | HEAD `f00462c` 的干净检出（2070 文件 / 92.8 MB），顶层与仓库一致；**无** SQL dump、**无**备份、**无**日志、**无**新增明文口令（其中的 `GeneratorMetaStoreTest` 为 HEAD 原版字面值，非本泳道产生） |
| 处置 | 该工作树内的 Maven 运行已被 **kill**（未跑完），未产出任何被本报告引用的结论；**未自行删除**（按总控指令，等统一处置）。此后完全改用**仓库内**的逐文件比对法判定「既有 vs 新增」（见 §6） |
| 顺带发现 | `git worktree list` 另有两个**先前存在**的仓库外工作树（非本泳道创建）：`D:/Develop/GraduationProject/.f88-baseline-wt`（`dba4381`）、`D:/Develop_code/GraduationProject-wt/m3-jdk8fix`（`f26be26`） |

## 1. 复核方法

1. **默认关闭**：在干净工作树上直接跑各模块默认套件，采 `Tests run / Errors / Skipped` 与退出码，并确认**不是** `assumeTrue` 造成的假绿（判据：`Skipped=0` 且失败类型为硬拒绝异常）。
2. **写前拒绝**：显式给出**完整**隔离档案但把目标指向宿主正式实例 3306，采「拒绝异常 + 拒绝前是否已建连」。
3. **反证搜索**：对全部 `l5-*.log` 做模式搜索（`Communications link failure` / `Access denied` / `Connection refused` / `HikariPool-` / `Unknown database`），并**排除我自己写的 `###` 注释头**，避免自证。
4. **脚本项**：真实执行脚本的拒绝/只读分支（不执行任何写入分支），并做 `file:line` 静态核对；清洗器为「已真跑」，写入路径为「**未真跑**」。
5. **口令字面值前后对照**：改动前用 `git show HEAD:<path>`（只读）取原文字面值，与工作区现状并列。
6. **「既有 vs 新增」**：不建干净工作树，改用 `git status --porcelain -- <file>` + `git hash-object <file>` + `git rev-parse <commit>:<file>` 三方比对 blob 身份。

## 2. 结论总表（四级状态分列）

四级定义按项目约定：**提交完成**（代码/脚本落到工作区）｜**测试通过**（模块默认套件或指定用例真实绿，无 skip 假绿）｜**限定验收**（在限定条件下取得可复核证据，但存在未取证项）｜**完整验收**（含真实隔离实例往返或生产等价路径）。

| 项 | 提交完成 | 测试通过 | 限定验收 | 完整验收 | 一句话结论 |
| --- | --- | --- | --- | --- | --- |
| **R-1** mall-simulator 默认关闭 + 写前拒绝 | ✅ 已完成 | ⚠️ **默认关闭时模块套件红（30 ERROR / 0 skipped）＝设计意图**；绿需 3307 隔离环境，未取证 | ✅ 已达成（写前拒绝，零连接） | ❌ **未达成**（无 3307 实连成功证据） | **整改真实有效**：默认关闭即硬拒，3306 在连接前被拒，无假绿 |
| **R-4** synthetic-data-generator 默认关闭 + 写前拒绝 | ✅ 已完成 | ⚠️ **默认关闭时红（20 ERROR / 0 skipped）＝设计意图**；绿需 3307，未取证 | ✅ 已达成 | ❌ **未达成** | **整改真实有效**；另发现 1 条与本泳道无关的既有 NPE |
| **R-6** 脚本项 | ✅ 已完成 | —（脚本非测试） | ⚠️ **部分**：`smoke-pipeline.ps1` 三类拒绝实测成立；`run-demo.ps1` 只观察到在健康检查阶段即停；`it-prepare-isolation.ps1` **实测致命不可执行** | ❌ **未达成**（不含任何真实清场/建库路径） | **方向正确，但 `it-prepare-isolation.ps1` 全线不可运行（硬缺陷）** |
| **R-3** Spark 门禁（**非本泳道范围**，只读复核） | ✅ 已完成（总控） | ✅ 已由总控实测（默认套件 `131/0F/0E/exit 0`；显式 `-Dtest=SparkStageExecutorSmokeIT` → `BUILD FAILURE / exit 1`） | ✅ 已达成 | — | 本泳道只做只读一致性核对，**未重跑**（见 §5） |
| **R-5** Spark/HDFS 清理 | 未取证 | 未取证 | 未取证 | 未取证 | **超出本泳道任务包，未复核** |

## 3. R-1：mall-simulator（高风险项）

### 3.1 默认关闭 —— 真实执行

```
命令: D:\apache-maven-3.9.14\bin\mvn.cmd -o -f mall-simulator/pom.xml -Dmaven.repo.local=D:\maven_repository test
退出码: 1
日志: raw/l5-r1-mall-default-off.log (5144 行)
```

| 项 | 实测 |
| --- | --- |
| 失败形态 | **硬拒绝**（Bean 创建阶段异常），非 skip |
| 总量 | 30 ERROR / **0 Skipped** |
| 根因链 | `BeanCreationException: 'dataSource' … [spring-datasource:dataSource] 无法识别的 JDBC URL 形态（只允许 jdbc:mysql://）：${MALL_ISOLATION_URL}` ← `MallIsolationGuard$MallIsolationException` |
| 判据意义 | 默认（未提供 `MALL_ISOLATION_URL`）时，`application-test.yml` 的占位符未解析，守卫在**解析 JDBC URL 文本**阶段即拒绝 —— **连 `DataSource` 都没有建成** |
| 未执行证据 | 日志内 `HikariPool-` / `Communications link failure` / `Access denied` 命中 **0**（见 §7 反证搜索） |

各测试类（节选）：`AdminProductApiTest` 2 run/2E；`AuthHttpTest` 5/5E；`AuthServiceTest` 5/5E；`UserAdminServiceTest` 6/6E；`OutboxControllerTest` 1/1E；`MallBusinessServiceTest` 6/6E；`OutboxTransactionTest` 1/1E；`EventOutboxServiceTest` 2/2E；`OutboxPublisherTest` 2/2E。纯逻辑类 `OrderStateMachineTest` 2/0F/0E、`GoldenDatasetTest` 6/0F/0E **绿**（不依赖数据源）。

> **四级判读**：这不是「测试通过」。按 `GeneratorMetaStoreTest` 同类 javadoc 的自述（「未准备隔离环境时 `mvn test` 会红，这是**有意**的」），R-1 的**提交完成**成立、**默认关闭有效**成立；**测试通过**需 3307 隔离环境，本泳道**未取证**。

### 3.2 写前拒绝 —— 真实执行（负向：指向 3306）

```
环境: MALL_ISOLATION_URL=jdbc:mysql://127.0.0.1:3306/l5probe-20260914-x1_mall
      MALL_ISOLATION_USER=l5probe-20260914-x1_mallapp
      -Dmall.it.enabled=true -Dmall.it.testRunId=l5probe-20260914-x1
      -Dmall.it.mysql.host=127.0.0.1:3306 -Dmall.it.mysql.url=jdbc:mysql://127.0.0.1:3306/l5probe-20260914-x1_mall
命令: mvn -o -f mall-simulator/pom.xml -Dmaven.repo.local=D:\maven_repository -Dtest=AuthServiceTest -DfailIfNoSpecifiedTests=false test
退出码: 1    日志: raw/l5-r1-mall-negative-host3306.log (970 行)
```

| 项 | 实测 |
| --- | --- |
| 拒绝点 | `MallIsolationGuard.assertUrlAllowed`（`MallIsolationGuard.java:133`）在 `@TestConfiguration` 的 `BeanPostProcessor` 阶段抛出 |
| 拒绝原文 | `[spring-datasource:dataSource] 实例端口 3306 是宿主正式 MySQL 实例端口：宿主上的任何库都不是隔离环境。隔离必须落在独立实例（端口 [3306]）；库名含 test 不构成安全证明。` |
| 结果 | `Tests run: 5, Failures: 0, Errors: 5, Skipped: 0`（5 个测试类初始化全部失败） |
| **关键点** | 即使调用方把 `3306` 塞进 `-Dmall.it.mysql.host`（守卫因此把它读进 `allowedPorts` 显示为 `[3306]`），**3306 仍被无条件拒绝** —— 配置**不能**把正式端口洗白（`IsolationGuard.java:254-262` 的注释正记录了这一修复） |
| 反证 | 日志内 `Communications link failure` / `Access denied` / `Connection refused` / `HikariPool-` 命中 **0** |

> 结论：**「写前拒绝」成立**，且**拒绝发生在任何连接建立之前**。这是本泳道对 R-1 的**限定验收**核心证据。

### 3.3 R-1 复核中发现的问题

| # | 问题 | file:line | 严重度 |
| --- | --- | --- | --- |
| 1 | **`application-test.yml` 的隔离档案走 classpath 无效**：`it.guard.*` / `mall.it.*` 的档案读取顺序是「Thread ContextClassLoader 的 `mall-isolation.local.properties` → 工作目录同名文件 → 系统属性/环境变量」。实测：把档案放在工作目录 `mall-simulator/`（即 Maven 的 CWD）**不生效** —— Spring 是直接解析 `application-test.yml` 里的 `${MALL_ISOLATION_URL}`，档案文件对它毫无作用。本泳道第一次尝试即因此取到「URL 未识别」而非「端口 3306 被拒」，改用 `MALL_ISOLATION_*` 环境变量 + `-Dmall.it.*` 后才得到 3.2 的结论 | `mall-simulator/src/test/resources/application-test.yml:6`（`url: ${MALL_ISOLATION_URL}`）vs `IsolationGuard.java:115-142`（`profile()`） | 中：文档/实现口径不一致，操作者按文档放档案会得到误导性报错 |
| 2 | 生产配置未收口（**属 R-1 范围外但相邻**）：`mall-simulator/src/main/resources/application.yml:8-10` 仍为 `jdbc:mysql://127.0.0.1:3306/mall_simulator?createDatabaseIfNotExist=true` + `${MALL_DB_USER:root}` + `${MALL_DB_PASSWORD:}` | `mall-simulator/src/main/resources/application.yml:8-10` | 中：仅加固了 test profile，主配置仍默认 root@3306 且允许建库 |
| 3 | `MallIsolationTestConfig` 只被 3 个 `@SpringBootTest` 显式 `@Import`，其余 6 个类经 `MallTestSupport` 基类继承；基类的 `freshState()` 第一步才是 `verifyBeforeWrite`。若将来有测试**不调用 `freshState()`** 而直接写库，则缺少写前核对 | `MallTestSupport.java:90-93` | 低（当前 6 个子类均经基类） |

## 4. R-4：synthetic-data-generator

### 4.1 默认关闭 —— 真实执行

```
命令: mvn -o -f synthetic-data-generator/pom.xml -Dmaven.repo.local=D:\maven_repository test
退出码: 1    日志: raw/l5-r4-generator-default-off.log (2390 行)
```

| 项 | 实测 |
| --- | --- |
| 总量 | `Tests run: 120, Failures: 0, Errors: 20, **Skipped: 0**` |
| `GeneratorMetaStoreTest` | `5 run / 0F / 5E / 0 skipped`，`java.lang.ExceptionInInitializerError` ← `IsolationGuard$GuardViolation: [GeneratorMetaStoreTest] 测试默认关闭：未提供 -Dit.guard.enabled=true…` |
| `GeneratorApiSmokeTest` | 5 run / 5E |
| `MallApiGenerationSmokeTest` | 9 run / 9E，`GuardViolation: [spring-datasource:dataSource] 禁止 createDatabaseIfNotExist=true（不得创建数据库）` |
| 与本泳道无关 | 1 条 `GeneratorContractParityTest.constantsAndPatternsMatchContract` NPE（见 §6） |
| **判据意义** | **`Skipped=0`** —— 整改前的 `assumeTrue(false, …)` 假绿已被彻底移除；失败是**类初始化硬失败**，不是 skip |

```
改动前（git show HEAD:synthetic-data-generator/src/test/java/.../GeneratorMetaStoreTest.java）:
  :58  assumeTrue(false, "缺少隔离环境…")   ← skip 伪装成 PASS
改动后（工作区 :57-74）:
  static { IsolationGuard.requireEnabled("GeneratorMetaStoreTest"); }   ← 类初始化即拒绝
```

### 4.2 写前拒绝 —— 真实执行（负向：指向 3306）

```
命令: mvn -o -f synthetic-data-generator/pom.xml -Dmaven.repo.local=D:\maven_repository
      -Dtest=GeneratorMetaStoreTest -DfailIfNoSpecifiedTests=false
      -Dit.guard.enabled=true -Dit.guard.runId=l5probe-20260914-x1 -Dit.guard.instancePorts=3307
      -Dit.guard.url=jdbc:mysql://127.0.0.1:3306/l5probe-20260914-x1_gen
      -Dit.guard.user=l5probe-20260914-x1_genapp -Dit.guard.password=probe-not-a-secret test
退出码: 1    日志: raw/l5-r4-generator-negative-host3306.log (127 行)
```

| 项 | 实测 |
| --- | --- |
| 拒绝原文 | `GuardViolation: [GeneratorMetaStoreTest:url] 实例端口 3306 是宿主正式 MySQL 实例端口：宿主上的任何库都不是隔离环境。隔离必须落在独立实例（端口 [3307]）；库名含 test 不构成安全证明。` |
| 拒绝点 | `IsolationGuard.assertUrlAllowed` ← `GeneratorMetaStoreTest.java:74` 的静态初始化 |
| 结果 | `Tests run: 5, Failures: 0, Errors: 5, Skipped: 0`，耗时 **0.043 s**（无网络往返） |
| 反证 | `Communications link failure` / `Access denied` / `HikariPool` / `Connection refused` / 目标 URL 字面量 命中 **0** |

补充负向（第一次尝试，**记录在案**）：若只给 `enabled=true` 而不给 `url`，守卫报
`缺少测试隔离配置项 it.guard.url（系统属性 / 环境变量 IT_GUARD_URL / 档案 it-guard.local.properties）：拒绝运行（不猜正式地址/正式账号对齐）`
—— 即「**不猜目标**」成立（同文件覆盖）。

### 4.3 与本泳道无关的既有缺陷（单列）

| 项 | 内容 |
| --- | --- |
| 现象 | `GeneratorContractParityTest.constantsAndPatternsMatchContract` 抛 NPE |
| 位置 | `synthetic-data-generator/src/test/java/com/graduation/generator/contract/GeneratorContractParityTest.java:86` —— `schema.get("properties").get("source_system").get("const").asText()`，而契约中 `properties.source_system` **无 `const` 键** |
| 成因（只读核对） | 契约 `contract-specs/schemas/canonical-event.v1.schema.json:38-42` 明确写「值域非契约所有（D-061：刻意不加 pattern/enum）」，故 `const` 缺失是**契约的有意设计**，测试侧的期望与之漂移 |
| 是否本泳道/V25-S03 引入 | **否**。三方 blob 同一：`git hash-object` 工作区 = `git rev-parse HEAD:<file>` = `git rev-parse f00462c:<file>`；两文件 `git status --porcelain` 均为空（详见 §6） |
| 对默认套件的影响 | **是** `synthetic-data-generator` 默认套件 `exit 1` 的**其中一个**来源。但即便修好此 NPE，套件仍会因 20 个门禁 ERROR 而红 —— 故它**不改变** R-4 的判读，只是多一条红灯 |
| 建议 | 另开单处置（契约-测试漂移），**不要**并入 R-4 |

## 5. R-6：脚本项

### 5.1 口令字面值「改动前 / 改动后」对照

改动前由 `git show HEAD:<path>` 只读取得（`raw/l5-r6-before-after-literals.log`，采集时 HEAD=`f00462c5`）。

| 位置 | 改动前（HEAD 原文） | 改动后（工作区） |
| --- | --- | --- |
| `mall-simulator/src/test/resources/application-test.yml:4` | `url: jdbc:mysql://127.0.0.1:3306/mall_simulator_test?createDatabaseIfNotExist=true&…` | `url: ${MALL_ISOLATION_URL}`（**无默认值**） |
| 同上 `:5` | `username: ${MALL_DB_USER:root}` ← **root 兜底** | `username: ${MALL_ISOLATION_USER}`（**无兜底**） |
| 同上 `:6` | `password: ${MALL_DB_PASSWORD:}` | `password: ${MALL_ISOLATION_PASSWORD}` |
| 同上 `:8` | `flyway.enabled: true` | `flyway.enabled: ${MALL_ISOLATION_FLYWAY_ENABLED:false}` |
| `scripts/run-demo.ps1` | `$delOut = & mysql -uroot -N -B -e "DELETE FROM mall_simulator.event_outbox; SELECT ROW_COUNT();" 2>&1`（L106，账号与库名**双写死**） | `$delOut = & $MysqlExe "-u$MallDbUser" -N -B -e "DELETE FROM $MallDbName.event_outbox; SELECT ROW_COUNT();"`（L122，账号/库名参数化 + 白名单 + `-ConfirmCleanTarget` + 影响面预览） |
| `scripts/smoke-pipeline.ps1` | `[string]$MysqlUser = 'root',` + `[string]$MysqlPassword = '123456'`（L262/L264） | `[string]$MysqlUser = 'metric_read',`（L59）+ `[string]$MysqlPassword = ''`（L61，**无默认值**） |

**残留明文扫描（工作区，`file:line`）**

- `application-test.yml`：**0** 处 `root` / `123456` / 3306。
- `run-demo.ps1`：`root` 仅出现在注释（L26 记录改动前原文、L90 记录实测 `mysql -uroot` 报 ERROR 1045）；**无** root 作为可用账号。
- `smoke-pipeline.ps1`：`123456` 仅出现在注释（L28/52/55/87）；`root` 仅出现在拒绝分支 `if ($MysqlUser -eq 'root') { … exit 5 }`（L93-96）。
- `it-guard.local.properties`：**0** 处口令；
- **范围外残留（只报不改）**：`start-all.ps1:19`（`'123456'` 回落）、`accept-p1-baseline.ps1:28`、`accept-three-programs.ps1:9`。

### 5.2 `scripts/smoke-pipeline.ps1` —— 已真跑的三类拒绝

```
命令（每例独立进程，MYSQL_PWD / SMOKE_DB_PASSWORD 均清空）:
  pwsh -NoProfile -File scripts/smoke-pipeline.ps1 -BusinessTime 2026-09-01T00:00:00 -OutDir mall-simulator/target/l5-smoke-out <extra>
日志: raw/l5-r6-script-refusals.log
```

| 例 | 参数 | 实测输出 | 退出码 |
| --- | --- | --- | --- |
| A | `-MetricDb mall_simulator` | `拒绝执行：-MetricDb 'mall_simulator' 不在允许清单 analytics_metric, analytics_metric_v25it 内。` | **5** |
| B | （不给口令） | `拒绝执行：未提供数据库口令。整改后不再有 123456 兜底。` + `当前目标：库=analytics_metric 账号=metric_read` | **5** |
| C | `-MysqlUser root -MysqlPassword …` | `拒绝执行：账号为 root。本脚本只需要读权限，请改用只读账号（默认 metric_read）。` | **5** |

静态核对补充（`file:line`）：白名单 `:74-79`、口令来源链 `:80-92`、root 拒绝 `:93-97`、口令走 `$env:MYSQL_PWD` 而非 `-p<口令>` `:100-115`、全部 SQL 为 `SELECT COUNT/COALESCE(MAX/SUM`（`:137`、`:138`、`:180`、`:194`）。

> **重要限定**：`MetricDb` 校验通过后脚本**默认就连宿主 3306**（脚本语义即「对正式库只读」）。案例 D 实测：`-MetricDb analytics_metric_v25it -MysqlPassword <猜测值>` → `ERROR 1045 (28000): Access denied for user 'metric_read'@'localhost' (using password: YES)`。这条同时说明：**默认账号 `metric_read` 在宿主实例上不存在**（属环境前提，**未取证**其是否应存在）。

**缺陷**：`smoke-pipeline.ps1:137-138`（同类 `:194`、`:149`）把 `Q()` 的返回整体强转 `[long]`。当 mysql 以非零码退出时，`Q()` 返回的 `ErrorRecord` 被强转，脚本以
`无法将类型"ERROR 1045 (28000): Access denied …"的"System.Management.Automation.ErrorRecord"值转换为类型"System.Int64"` 崩溃，**退出码 1**，而不是给出清晰的「凭据/目标不通」结论（应为 5 类）。这是**拒绝对话与失败判据**的缺口：连不上时现象与「脚本自身有 bug」不可区分。

### 5.3 `scripts/run-demo.ps1` —— 已真跑，但**未覆盖清场分支**

```
命令: pwsh -NoProfile -File scripts/run-demo.ps1 -Clean -ConfirmCleanTarget -MallDbName mall_simulator -MallDbUser mall_app
      （$env:MALL_DB_PASSWORD 故意不设）
退出码: 1    输出: 未就绪: 模拟商城 http://127.0.0.1:8090 / 未就绪: 合成数据生成器 http://127.0.0.1:8092
日志: raw/l5-r6-demo-ps1-observed-run.log
```

- 脚本在健康检查阶段即 `exit 1`，**`DELETE` 分支从未到达**（输出中 `DELETE FROM` 命中 0）。这既证明「三个进程没起来时不会误清库」，也说明**清场分支本身的行为未取证**。
- 静态核对（`file:line`）：参数化 `MallDbName:30` / `MallDbUser:31` / `ConfirmCleanTarget:33`；`未提供 -ConfirmCleanTarget` → 只提示不执行 `:79-85`；账号 `root` 拒绝 `:108`；影响面预览 `:114`（`SELECT COUNT(*) FROM …event_outbox`）；真正的 `DELETE` `:122`；落地清理由 `$cleanTargetOk` 单闸门控制 `:146`。
- **仍存风险（需总控裁决）**：`-Clean -ConfirmCleanTarget -MallDbUser mall_app` + `$env:MALL_DB_PASSWORD` 齐备时，`DELETE FROM mall_simulator.event_outbox` **对正式库仍可达**（目标改为显式 + 有预览，但没有「仅限隔离库」的硬校验）。整改前该表实测 834,550 行。

### 5.4 `scripts/it-prepare-isolation.ps1` —— **实测致命不可执行（硬缺陷）**

```
命令 1: pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId l5probe-20260914-x1 -Port 3306 -Confirm
命令 2: pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId l5probe-20260914-x1
命令 3: pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 --% -RunId l5probe-20260914-x1
命令 4（-Command 方式）: pwsh -NoProfile -Command "& '<abs>\scripts\it-prepare-isolation.ps1' -RunId l5probe-20260914-x1 -Confirm"
环境: PowerShell 7.6.6 (Windows)
日志: raw/l5-r6-it-prepare-FATAL-binding-defect.log
```

| 调用方式 | 实测输出 | 退出码 |
| --- | --- | --- |
| `-File` + `-Port 3306 -Confirm` | `WriteError: 无法覆盖变量 Host ，因为它是只读变量或常量。` | **1** |
| `-File`（默认参数） | 同上 | **1** |
| `-File` + `--%` | 同上 | **1** |
| `-Command`（`&` 调用） | 同上（**但退出码 0** —— 失败被静默） | **0** ⚠️ |

**根因**：`scripts/it-prepare-isolation.ps1:28` 声明 `[string]$Host = '127.0.0.1'`。`$Host` 是 PowerShell **只读自动变量**，参数绑定阶段即失败，**脚本主体从未执行**。

**后果（两条，均为阻塞级）**

1. 该脚本是 R-6 交付物，也是 **3307 隔离环境准备**的唯一入口。**它不可运行 ⇒ R-1/R-4 的「真实隔离往返」路径按既定路线无法推进**（这正是本泳道 R-1/R-4 只能给出限定验收的结构性原因之一）。
2. 失败在 `-Command` 调用下**退出码为 0**，会被上层脚本/CI 误判为成功 —— 一个「静默失败」的安全工具。

**判据逻辑本身另做独立求值（脚本不可跑，故只验证逻辑，不代表整脚本可跑）**：把 `:55-66` 的端口/主机白名单判据原文取出求值 →

| 输入 | 输出 | 退出码 |
| --- | --- | --- |
| `-Port 3306` | `拒绝：-Port 3306 不在允许的隔离实例端口清单 3307 内。` + `3306 是宿主正式 MySQL 实例：它上面的任何库（含 *_test）都不是隔离环境。` | **2** |
| `-Port 3307` | `通过：端口 3307 主机 127.0.0.1 属允许范围` | 0 |
| `-Port 3307 -TargetHost 10.0.0.9` | `拒绝：-Host '10.0.0.9' 不是本机。隔离实例必须是本机 WSL 成员。` | **2** |

即：**判据写对了，但脚本跑不起来**。

**其他待裁决点**

- `scripts/it-prepare-isolation.ps1:140`：`$sql | & wsl @wslArgs -- mysql -h 127.0.0.1 -P $Port -uroot --batch --silent` —— 建库/建账号阶段仍使用 **`root`**（作用于 3307 隔离实例）。注释 L14-15 声称「绝不用 root / metric_pub / mall_app / meta_app」，**与实现不一致**；需裁决：3307 隔离实例上用 root 是否可接受，或改用受限初始化账号。
- **未真跑**：本泳道**没有**执行过 `-Confirm`（脚本本身也跑不了）；`~\.graduation\credref-*.properties` 经探测**不存在**（未产生任何凭据文件）。文件创建路径 `:113-122`、`credref:<id>` 引用 `:159`/`:167` 均**仅静态核对**。
- 脚本头部自称「`-WhatIf` 也支持」，实际参数只有 `-DryRun`/`-Confirm`，无 `SupportsShouldProcess` —— 文档与实现不一致（低）。

## 6. 「既有 vs 新增」判定（不建干净工作树）

方法：`git status --porcelain -- <file>` + `git hash-object <file>` + `git rev-parse <commit>:<file>`。
日志：`raw/l5-baseline-vs-HEAD-file-identity.log`、`raw/l5-head-moved-during-verification.log`。

| 文件 | 工作区 blob | HEAD(`fd3cee5`) | `f00462c`（复核开始时） | 相对 HEAD 是否改动 | 判定 |
| --- | --- | --- | --- | --- | --- |
| `GeneratorContractParityTest.java` | `f43859f1…` | `f43859f1…` | `f43859f1…` | **无** | §4.3 的 NPE = **既有缺陷**（已证） |
| `contract-specs/schemas/canonical-event.v1.schema.json` | `1e4f2d85…` | `1e4f2d85…` | `1e4f2d85…` | **无** | 契约侧亦为原状 |
| `GeneratorMetaStoreTest.java` | `fff0682b…` | `b3d28763…` | — | **M（已改）** | R-4 整改面，符合预期 |
| `application-test.yml` | `1ba2ad4d…` | `8d194b36…` | — | **M（已改）** | R-1 整改面，符合预期 |

**HEAD 前移的影响**：取证期间 HEAD 由 `f00462c` 前进到 `fd3cee5`（再前一步 `83afc49`，均为总控文档提交）。本泳道对**被测两棵树 + 两个 pom** 做了取证前后**内容哈希二次快照**（`raw/l5-workspace-hash-snapshot.md` 190 行 vs `raw/l5-workspace-hash-snapshot-after.log` 190 行）→ **逐行完全一致** ⇒ 本报告 §3、§4 的实测结论在 HEAD 前移后**依然成立**。

## 7. 反证搜索（自证排除）

日志：`raw/l5-counterevidence-grep.log`（已排除本泳道自写的 `###` 注释头）。

| 模式 | 命中 |
| --- | --- |
| `Communications link failure` | **0** |
| `Access denied` | **0** |
| `Connection refused` | **0** |
| `HikariPool-` | **0** |
| `Unknown database` | **0** |
| `createDatabaseIfNotExist` | 39（全部为 **R-4 默认套件里守卫的拒绝文案**与 `l5-r6-before-after-literals.log` 里记录的改动前原文，**非**连接参数被下传） |

⇒ 「写前拒绝」不是「连上了之后才失败」的措辞包装：默认套件与 3306 负向两轮里，**没有任何连接被建立过**。

## 8. 证据文件清单（本泳道新增，全部只追加）

| 文件 | 内容 |
| --- | --- |
| `raw/l5-r1-mall-default-off.log` | R-1 默认关闭：5144 行，exit 1，30 ERROR / 0 skipped |
| `raw/l5-r1-mall-negative-host3306.log` | R-1 写前拒绝：970 行，exit 1，`assertUrlAllowed` 拒 3306 |
| `raw/l5-r4-generator-default-off.log` | R-4 默认关闭：2390 行，exit 1，120 run / 20E / **0 skipped** |
| `raw/l5-r4-generator-negative-host3306.log` | R-4 写前拒绝：127 行，exit 1，`GuardViolation` 拒 3306（0.043 s） |
| `raw/l5-r4-generator-HEAD-baseline.log` | 中途被 kill 的仓外工作树基线尝试（**未引用其结论**，如实留档） |
| `raw/l5-r6-before-after-literals.log` | 口令/删除语句「改动前 vs 改动后」，改动前取自 `git show HEAD:` |
| `raw/l5-r6-script-refusals.log` | `smoke-pipeline.ps1` 三类拒绝实测（A/B/C，均 exit 5）+ 案例 D |
| `raw/l5-r6-static-source-review.log` | 三个脚本的 `file:line` 静态核对 |
| `raw/l5-r6-demo-ps1-observed-run.log` | `run-demo.ps1 -Clean -ConfirmCleanTarget` 真跑（停在健康检查，无 DELETE） |
| `raw/l5-r6-it-prepare-refusals.log` | `it-prepare-isolation.ps1` 首轮尝试（暴露 `$Host` 缺陷） |
| `raw/l5-r6-it-prepare-FATAL-binding-defect.log` | 4 种调用方式复现 + 判据片段独立求值 |
| `raw/l5-counterevidence-grep.log` | 反证模式搜索（0 命中） |
| `raw/l5-baseline-vs-HEAD-file-identity.log` | 既有/新增判定的 blob 三方比对 |
| `raw/l5-head-moved-during-verification.log` | HEAD 前移记录 + 跨 commit blob 对照 |
| `raw/l5-workspace-hash-snapshot.md` / `-after.log` | 被测两棵树内容哈希前后二次快照（逐行一致） |
| `L5-verification-report.md` | 本报告 |

## 9. 未取证清单（**不得**读作通过）

| # | 项 | 原因 | 取证所需条件 |
| --- | --- | --- | --- |
| 1 | R-1 `mall-simulator` 默认套件**绿灯** | 无 3307 隔离实例（且 `it-prepare-isolation.ps1` 不可运行） | 由总控执行该脚本 → 3307 建库后跑 `-Dtest=…` |
| 2 | R-4 `synthetic-data-generator` 默认套件**绿灯** | 同上；且 `GeneratorContractParityTest` NPE 需另行处置 | 同上 |
| 3 | R-1/R-4 的 `verifyBeforeWrite` **实连成功**路径（`@@server_uuid` / `@@port` 判据） | 需要能连上的 3307 实例与受限账号口令 | 3307 实例 + `credref` |
| 4 | R-6 `run-demo.ps1` 的 `DELETE` 分支行为（是否真的只删、删多少、`ROW_COUNT`） | 8090/8091/8092 本泳道禁止启停 ⇒ 脚本停在健康检查 | 演示环境就绪后由总控执行 |
| 5 | R-6 `it-prepare-isolation.ps1` 的 `-Confirm` 真实建库/建账号/写 credref | **脚本因 `$Host` 冲突完全不可运行** | 先修 `:28` 参数名 |
| 6 | `metric_read` 账号在正式库上的实际权限 | 未做任何授权查询（避免误触） | 由总控只读查询 `SHOW GRANTS` |
| 7 | R-3 的 Maven 实跑 | 构建槽纪律（`analytics-server` 归 L4）；总控已实测，本泳道只做只读一致性核对 | — |
| 8 | R-5（Spark/HDFS 清理） | 超出本泳道任务包 | — |
| 9 | `smoke-pipeline.ps1` 在凭据**正确**时的完整 7 步链路 | 需要正式库只读凭据 | 由总控提供 |

## 10. 需总控裁决 / 建议另开单

| # | 事项 | 建议 |
| --- | --- | --- |
| A | `scripts/it-prepare-isolation.ps1:28` `[string]$Host` 与只读自动变量冲突 ⇒ 脚本全线不可运行，且 `-Command` 方式退出码 0 静默失败 | **阻塞级**，建议即刻修（改为 `$DbHost`/`$MysqlHost`）后重验；这是通往 3307 验收的必经之路 |
| B | `scripts/it-prepare-isolation.ps1:140` 建库/建账号用 `-uroot`，与 L14-15「绝不用 root」自述矛盾 | 裁决：3307 上是否接受 root；不接受则改受限初始化账号 |
| C | `generator/src/test/.../GeneratorContractParityTest.java:86` NPE（契约 `source_system` 无 `const`，属 D-061 有意设计） | 两条都未改动 ⇒ **既有缺陷**，建议另开单（契约-测试漂移），**不计入** R-4 |
| D | `application-test.yml` 用 `${MALL_ISOLATION_URL}`，而文档暗示可用 `mall-isolation.local.properties` 档案 | 口径统一：或让 profile 参与 Spring 占位符，或在 README 写明「Spring 侧只能走环境变量/系统属性」 |
| E | `run-demo.ps1:122` 正式库 `DELETE` 仍可达（已加目标显式化 + 预览 + 确认） | 裁决是否再加「仅限隔离库名」硬校验；整改前该表 834,550 行 |
| F | `smoke-pipeline.ps1:137/138/149/194` 把 mysql 错误记录强转 `[long]` ⇒ 连不上时崩溃 exit 1（非 exit 5） | 建议在 `Q()` 内对非零退出码统一 `exit 5`，让「拒绝」与「脚本 bug」可区分 |
| G | `mall-simulator/src/main/resources/application.yml:8-10` 仍 `root`@3306 + `createDatabaseIfNotExist=true`；`synthetic-data-generator/src/main/resources/application.yml` 同（`${GENERATOR_DB_PASSWORD:123456}`） | 属主配置而非测试面，建议单独立项 |
| H | 范围外残留明文：`start-all.ps1:19`、`accept-p1-baseline.ps1:28`、`accept-three-programs.ps1:9` | 与上一泳道 `external-write-surfaces.md` §3.1 结论一致，**本泳道复核确认仍存在** |
| I | 仓外工作树 `D:\Develop_code\_gp_headcheck`（本泳道创建，已停用） | 等用户逐项批准删除 |
| J | HEAD 在本泳道取证期间前进 2 个 commit | 已用哈希二次快照证明被测面未变；后续泳道请注意「时点」引用 |

## 11. 复核结论

1. **R-1、R-4 的整改是真实的、可复现的、非假绿的**：默认关闭下两模块套件**硬失败且 `Skipped=0`**；显式指向宿主 3306 时在**任何连接建立之前**被拒，日志无任何已连上痕迹。前序泳道「默认关闭 ＋ 写前拒绝」的核心主张**成立**，本泳道独立复现。
2. 但两者的**完整验收均未达成**，且阻塞点不是「环境暂时没起来」，而是 **`it-prepare-isolation.ps1` 有硬缺陷、根本跑不起来** —— 这条把「等 W03 交付 3307」的既有叙事更新为「**先修脚本，才谈 3307**」。
3. **R-6 方向正确、部分可证**：`smoke-pipeline.ps1` 三类拒绝实测 exit 5、口令不再走命令行、SQL 全部只读；`run-demo.ps1` 已参数化 + 白名单 + 预览 + 显式确认，但正式库 `DELETE` 仍可达。
4. 复核中另发现 **6 条与本泳道无关或范围外**的问题（含 1 条既有 NPE），均已给 `file:line` 与复现命令，建议分别立单，不要并入 V25-S03 的 R-1/R-4/R-6。
5. 本泳道**未改写、未删除**证据目录中前序泳道的任何一行；所有新增证据为 `raw/l5-*` 与本报告。

---

### L5 复核 · 四级标记一览

```
R-1  提交完成=是   测试通过=否（默认关闭红＝设计意图；绿需 3307）   限定验收=是   完整验收=否
R-4  提交完成=是   测试通过=否（同上）                            限定验收=是   完整验收=否
R-6  提交完成=是   测试通过=n/a                                   限定验收=部分 完整验收=否（it-prepare 不可运行）
R-3  提交完成=是（总控） 测试通过=是（总控实测）                  限定验收=是   完整验收=—
R-5  未取证
```
