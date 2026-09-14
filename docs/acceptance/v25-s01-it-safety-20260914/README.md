# V25-S01 写入型 IT 防误写门禁整改 — 验收证据

- **任务**：V25-S01（写入型 IT 防误写门禁整改）
- **分支 / HEAD**：`remediation/r1-boundary` / `3fcf90e30962ae528b2079dc5c40070f1f6b7257`
- **取证时间**：2026-09-14
- **工具链**：JDK `17.0.12`（`D:\Develop\JAVA17`）、Maven `3.9.14`、本地仓库 `D:\maven_repository`
- **声明级别**：**测试通过**（未达「限定验收」——见 §6 未取证清单）
- **数据库写入**：本任务全程对任何 MySQL 库**零写入**（无 DDL / 无 DML / 无建库建表）。正式库仅只读查询。

---

## 1. 缺陷本体（整改前）

两个写入型 IT 的 JDBC URL 直接写死正式指标库，清理用可复用常量做批量删除：

| 位置 | 整改前 | 风险 |
| --- | --- | --- |
| `MetricAdsMySqlIT.java:37`（注释原文） | `jdbc:mysql://127.0.0.1:3306/analytics_metric` ＋ 正式写账号 `metric_pub` | — |
| `MetricAdsMySqlIT.java:39`（注释原文） | 「任一时刻加 `-Dmetric.it=true` 就能删掉正式指标库上该档案的全部快照」 | **加一个开关即可误删生产快照** |

`runtime_profile_id` 是**可复用**的档案登记 ID：旧 cleanup 的 `DELETE ... WHERE runtime_profile_id = ?` 一次会删掉该档案下**所有**快照（含历史 ACTIVE），因此门禁不能只靠这个常量。

---

## 2. 交付物

### 2.1 共享测试安全 helper（新增）

| 文件 | 说明 |
| --- | --- |
| `analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuard.java`（956 行） | 默认拒绝 + 白名单校验 + `TestRunContext` / `WorkScope` / `DeletionTarget` / `ContentFingerprint` |
| `.../testsupport/TestIsolationGuardTest.java` | 20 个 T0 反向测试（不连库，`CountingDataSource` 探针） |
| `.../testsupport/IsolationProfileCondition.java` | 写入型 IT 的「默认关闭」`ExecutionCondition` |
| `.../testsupport/TestRunDigest.java` | 内容指纹用 sha1/md5 |
| `.../testsupport/RepoRoot.java` | 仓库根定位（**已有文件，本任务未改动**） |

放在 `platform-common` 的 **test-jar**（`analytics-server/platform-common/pom.xml:47-53`）中，被 metric-analysis / ai-decision / platform-app / warehouse-pipeline 共享。

### 2.2 两个写入型 IT 的整改

| 文件 | 关键改动 |
| --- | --- |
| `MetricAdsMySqlIT.java:62` | `@ExtendWith(IsolationProfileCondition.class)`，未登记隔离档案则整类 disabled |
| `MetricAdsMySqlIT.java:88-103` | 上下文在 `@BeforeAll` 装载（**不是** static 字段，见 §2.4），两处 `verifyBeforeWrite` 在任何 DML 之前 |
| `MetricAdsMySqlIT.java:182-192` | 只登记**本次运行自己的**档案：`profile_code = "v25it-" + testRunId()` |
| `MetricAdsMySqlIT.java:246-266` | `safeDeleteSnapshot`：先查 `DeletionTarget` 清单 → `assertDeletionTargets` 打印并逐条验范围 → 再按 `snapshot_id` 删 |
| `MetricAdsMySqlIT.java:241` | 档案删除带双条件：`WHERE id = ? AND profile_code = ?` |
| `MetricAdsMySqlIT.java:279` | URL 库名只来自 `context.metricDb()`；host/账号只来自系统属性，**无正式地址/正式账号兜底** || `MetricPublisherMySqlIT.java:63,88-101` | 同上（含 `publish` 与 `read` 两个数据源都过门禁） |
| `MetricPublisherMySqlIT.java:173-196` | 负向测试：给正式库 `analytics_metric` / 越界库 `analytics_metric_p103`，**写入之前**即失败 |
| `MetricPublisherMySqlIT.java:452` | `RejectingDataSource` 探针：任何 `getConnection()` 都不可能被调用 |

### 2.3 隔离判据（按用户 R2 裁决 / Q9 裁定更新）

判据是**连接级**的，**库名含 `test` 不作为安全证明**：

1. `TestIsolationGuard.java:100` — `ALLOWED_INSTANCE_PORTS = {3307}`（WSL 独立 MySQL 实例）
2. `TestIsolationGuard.java:103` — `HOST_FORMAL_PORT = 3306`，显式点名拒绝
3. `TestIsolationGuard.java:607` — `assertJdbcTargetAllowed`：**不开连接**就读 `DataSource` 的 JDBC URL，预检端口/主机/库名
4. `TestIsolationGuard.java:470` — `verifyBeforeWrite`：实连后核对 `SELECT DATABASE()` / `CURRENT_USER()` / `@@server_uuid` / `@@hostname` / `@@port` / `SHOW GRANTS`
5. `TestIsolationGuard.java:587` — `rejectForbiddenInstancePort`：端口不符即拒绝，3306 报错明确写「宿主正式 MySQL 实例端口」
6. `TestIsolationGuard.java:249` — `requiredProperty`：连接参数（host/账号）解析，「系统属性 → 隔离档案 → 拒绝」，**无默认值**

`TestRunContext`（`TestIsolationGuard.java:348`）要求 §9.4 全部字段同属一个登记隔离范围：
`testRunId, serverFingerprint, metaDb, metricDb, hiveNamespace, hdfsRoot, manifestRoot, credentialsRef, startedAt`。
**单改 JDBC URL 无法构造合法 context**（库名命中禁止清单即拒；路径必须含 `testRunId`；URI 写法一律拒）。

> **补充修复（本轮新增）**：原先 host/账号由两个 IT 各自直接读系统属性，而 `TestRunContext` 的 8 个字段
> 不含 host/账号，于是「档案里写了 host，测试仍报缺少 `-D…mysql.host`」形成断层——用户为跑测试只能在
> 命令行重复粘地址。现统一由 `TestIsolationGuard.requiredProperty` 解析，档案与系统属性同一来源，
> 且**两处都没有时依然拒绝**（有对应 T0 测试）。

### 2.4 为什么 context 装载在 `@BeforeAll`

static 字段在**类加载**时求值：缺配置会抛 `ExceptionInInitializerError`，把整个套件打红，而不是按 §9.4「默认关闭」。
放进 `@BeforeAll` 后顺序变为「`ExecutionCondition` 先判 → 未登记则 disabled，不执行任何连接」。

---

## 3. T0 反向测试证据（20/20 通过）

命令（`raw/t0-guard-suite.log`）：

```
java -cp "<platform-common target/classes + metric-analysis target/classes + 依赖闭包>"
     V25JUnitRunner com.graduation.analytics.testsupport.TestIsolationGuardTest \
                     com.graduation.analytics.metric.MetricAdsMySqlIT \
                     com.graduation.analytics.metric.publish.MetricPublisherMySqlIT
```

结果：`started=20 succeeded=20 failed=0 aborted=0 skipped=2`，退出码 `0`。

覆盖的拒绝路径（每项对应一个测试）：

| 拒绝路径 | 结果 |
| --- | --- |
| 配置缺失 → **立即拒绝**（`MissingConfigurationException`），不是 skip 后 PASS | PASS |
| 配置不完整（少一个键）→ 拒绝，**不用默认值补齐** | PASS |
| 正式库配置 → 构造 `TestRunContext` 时即拒（**无 `analytics_metric` fallback**） | PASS |
| 给正式库地址 → **在建立连接之前**失败（连接尝试 = 0） | PASS |
| `metaDb` 指向正式库 → 同样拒（**只改 JDBC URL 不算隔离**） | PASS |
| 声明库与登记范围不符 → 写前拒绝 | PASS |
| **实例端口白名单**：宿主 3306 / 省略端口 / 非本机主机一律拒；仅 3307 放行 | PASS |
| 账号最小权限：`root` / 正式写账号 `metric_pub` 在禁止清单内 | PASS |
| 集群路径护栏：`hdfsRoot` 指向 `hdfs:///graduation/**` 一律拒 | PASS |
| 隔离根不接受 `file://` 与 `hdfs://` URI 写法 | PASS |
| `testRunId` 形状非法 → 拒绝 | PASS |
| 隔离范围不一致（`hiveNamespace`/`hdfsRoot`/`manifestRoot`/`credentialsRef` 缺 `testRunId`）→ 拒 | PASS |
| `TRUNCATE` / 递归删仓库根 / 删历史 manifest → 一律拒 | PASS |
| 内容指纹：**计数相同但内容不同**必须能区分 | PASS |
| 服务实例指纹不匹配（同库名换实例）→ 拒绝 | PASS |
| cleanup 目标清单必须逐条匹配 `testRunId` 前缀与本次 `profileId` | PASS |
| `WorkScope` 拒绝空/共享范围 | PASS |

`skipped=2` 即两个写入型 IT **被默认关闭（disabled）而非报错**：

```
[SKIP ] MetricAdsMySqlIT :: 未提供测试隔离配置，本写入型 IT 默认关闭（MissingConfigurationException：… 本门禁**不**提供 analytics_metric 等正式库 fallback。）
[SKIP ] MetricPublisherMySqlIT :: 同上
```

---

## 4. 端到端负向证据：宿主 3306 配置在写入前被拒

固定 `testRunId=v25it-20260914-120000-a1b2`，用系统属性提供完整隔离档案，只改 `mysql.host`：

### 4.1 `-Dv25.it.mysql.host=127.0.0.1:3306`（`raw/t1-negative-profile-host-3306.log`）

```
[FAIL] [容器] MetricAdsMySqlIT :: IsolationViolationException:
       排除：实例端口 3306 这是宿主正式 MySQL 实例端口：宿主上的任何库
       （含 test_db / mall_simulator_test）都不是隔离环境。
       隔离必须落在 WSL 独立实例（端口 3307），库名含 test 不构成安全证明
    at TestIsolationGuard.rejectForbiddenInstancePort(TestIsolationGuard.java:596)
    at TestIsolationGuard.assertJdbcTargetAllowed(TestIsolationGuard.java:635)
    at TestIsolationGuard.verifyBeforeWrite(TestIsolationGuard.java:483)
    at MetricAdsMySqlIT.setUp(MetricAdsMySqlIT.java:102)   ← @BeforeAll，任何 DML 之前
```

**关键**：整份日志**不含** `Communications link failure` / `Access denied` / `Unknown database` 等任何连接层痕迹，
证明拒绝发生在**建立连接之前**——不是「先连上正式库再回滚」（§9.4 严禁的形态）。

### 4.2 `-Dv25.it.mysql.host=127.0.0.1:3307`（`raw/t1-profile-isolated-port-3307.log`）

```
IsolationViolation 未出现；改为 Communications link failure（3307 无监听）
即端口白名单放行后，测试确实会去连隔离实例并因环境不具备而失败（不是静默跳过、不是回退正式库）
```

### 4.3 档案不完整（`raw/t1-profile-incomplete-missing-mysql-host.log`）

缺 `-Dv25.it.mysql.host` 时：

```
MissingConfigurationException: 缺少测试隔离配置项 v25.it.mysql.host
（系统属性或隔离档案 integration.local.properties）：
拒绝运行（不用正式账号/正式地址兜底）
```

即**第二个 fail-closed 关口**：账号/地址缺失时绝不回退到正式库凭据。
（该关口现在由 `TestIsolationGuard.requiredProperty` 统一把关，系统属性与隔离档案是同一解析链。）

---

## 5. 正式库只读取证（证明全程零写入）

```sql
SELECT COUNT(*) FROM analytics_metric.metric_snapshot;              -- 12
SELECT COUNT(*) FROM analytics_metric.metric_value;                 -- 110
SELECT snapshot_id, runtime_profile_id, status, version, active_flag
  FROM analytics_metric.metric_snapshot WHERE active_flag = 1;      -- S20260901_47 / 1 / ACTIVE / 12 / 1
-- 全表行内容指纹（排序后拼接再 MD5）
452b7223a4a2b9dd0df7f3c883cdb74b
SELECT COUNT(*) FROM analytics_meta.runtime_profile;                -- 1
SELECT profile_code FROM analytics_meta.runtime_profile
 WHERE profile_code LIKE 'v25it-%';                                 -- 0 行
```

**结论**：正式库计数、ACTIVE 指针、行内容指纹与基线一致；且**没有任何本次测试的档案登记泄漏**（`v25it-%` 为 0 行）。
计数相等本身不足以证明零修改，故附行内容指纹（§9.4 要求）。

### 5.1 账号权限取证（只读 `SHOW GRANTS`）

```
metric_read@localhost : USAGE ON *.*
                        SELECT ON analytics_metric.*
                        SELECT ON analytics_verify_m3_parity.*
metric_pub@localhost  : USAGE ON *.*
                        SELECT,INSERT,UPDATE,DELETE,CREATE,REFERENCES,INDEX,ALTER
                          ON analytics_metric.*
                        SELECT,INSERT,UPDATE,DELETE,CREATE,REFERENCES,INDEX,ALTER
                          ON analytics_verify_m3_parity.*
```

- `metric_pub` 在正式库上持有 **INSERT/UPDATE/DELETE** —— 这正是整改前 `-Dmetric.it=true`
  能删掉生产快照的直接原因，故已在门禁里列入 `FORBIDDEN_ACCOUNTS`（`TestIsolationGuard.java:84`）。
- `metric_read` 是**最小权限**（仅 SELECT），但它绑定宿主 3306 正式实例，仍**不满足**实例级隔离，
  因此同样在禁止清单内（§9.4「库名含 test 不算安全证明」的同理：账号只读也不等于环境隔离）。
- 账号 `host` 均为 `localhost`（非 `%`），是唯一的环境侧缓解，但**不能**替代实例/端口隔离。

---

## 6. T1 命令与结果

### 6.1 受制裁命令（**未**加 `-Dmetric.it=true`）

```
D:\apache-maven-3.9.14\bin\mvn.cmd -o -Dmaven.repo.local=D:\maven_repository \
  -f analytics-server/pom.xml -pl metric-analysis -am test
```

- **退出码 = 1**，耗时 9s，`BUILD FAILURE`（`raw/t1-maven-metric-analysis-test.log`）
- 失败数：**编译失败，未进入测试执行**（`Tests run` 无输出）
- 根因：**其它泳道的 `platform-common` 测试源编译不过**，非本任务文件：
  - `analytics-server/platform-common/src/test/java/com/graduation/analytics/metric/RuleSeverityTest.java:129-207`
    —— `String` 无法转换为 `QualityRuleCatalog.FrozenRules`（14+ 处）
  - （更早一轮）`WarehouseNameLiteralScanner.java:70` —— `EnumMap<>` 类型参数无法推断
- 处置：**不修改他泳道文件**，记为「等编译绿」。测试源码属未跟踪新文件，由对应泳道自行收口。

同时确认：`metric-analysis` **主代码**当前也编译不过（`MetricPublishValidator.java:48` 找不到符号
`QualityRuleCatalog`——该泳道新引入 `RuleSeverity`/`FrozenRules` 尚未编译进 `target/classes`）。
本任务未改动该文件或任何产品主代码。

### 6.2 等价执行路径（为拿到真实 JUnit 结果）

因 Maven reactor 被卡，使用最小 JUnit 5 执行器直接跑 Jupiter 引擎
（`V25JUnitRunner`，置于仓库外 `%TEMP%\v25runner\`，只读类名参数、不改任何文件）：
结果见 §3 / §4。**这是等效替代，不是「测试已通过 Maven」**——Maven 侧仍记为未取证。

---

## 7. 说明与边界

- **未取证（重要）**：
  1. `mvn ... test` 绿色（阻塞于他泳道编译，见 §6.1）；
  2. 真库往返（写入 → 读回 → 清理）未在隔离实例上跑过一次——WSL 隔离实例属 **L3 / V25-W02/W03**，尚未交付；
  3. 真实 `SELECT DATABASE()` / `@@server_uuid` / `@@port` / `SHOW GRANTS` 的**实连**校验路径未取到通过样本
     （现有证据为「配置缺失」与「端口预检」两个 fail-closed 关口 + 零连接尝试证明）；
  4. Hive / HDFS 侧隔离（namespace、落地根、manifest 根）——**未做·环境不具备**；
  5. `-pl <module> -am` 是否够用（§9.5）未验证：上层命令自身即被卡住。
- **环境约束遵守**：未建库建表、未执行任何 DDL/DML、未启停任何服务、未用 `failsafe`/`verify`/`clean`/Flyway 目标、
  未触碰集群 `/graduation/**`、未 `git add/commit/push`。
- **凭据**：证据中不出现明文口令，一律以 `credentialsRef` 指代。

---

## 8. raw/ 清单

| 文件 | 内容 |
| --- | --- |
| `raw/t0-guard-suite.log` | T0：19 项门禁反向测试 + 两个 IT 默认关闭 |
| `raw/t1-maven-metric-analysis-test.log` | T1：受制裁 Maven 命令，退出码 1、他泳道编译错误 |
| `raw/t1-negative-profile-host-3306.log` | 端到端负向：宿主 3306 在写入前被拒，零连接尝试 |
| `raw/t1-profile-isolated-port-3307.log` | 端口放行后确实去连隔离实例（环境不具备） |
| `raw/t1-profile-incomplete-missing-mysql-host.log` | 缺账号/地址配置 → 拒绝，无正式库兜底 |

---

## 9. V25-S02 隔离档案（同一证据目录内的推进部分）

### 9.1 已交付

- `analytics-server/integration.local.properties.template` —— 已按 **R2 裁决 / Q9 裁定**改为
  WSL 独立实例：`mysql.host=127.0.0.1:3307`，`serverFingerprint=WSL-V25IT-ISOLATED`，
  库名 `analytics_meta_v25it` / `analytics_metric_v25it`，账号计划 `v25it_<run>_meta|_metric_pub|_metric_read`。
- 模板中 `hdfsRoot` 已从 `file:///…` 改为**本机绝对路径**（门禁现在直接拒绝 URI 写法）。
- 连接参数 4 个键（`mysql.host`、`metric.publish.username/password`、`metric.read.username/password`）
  已纳入门禁统一解析链，档案可直接驱动两个 IT 组装 JDBC URL，无需在命令行重复粘地址。

### 9.2 明确标注「等 W03 交付」的部分

| 项 | 状态 |
| --- | --- |
| WSL 独立 MySQL 实例（端口 3307）本体 | **等 W03 交付**（L3 / V25-W02、W03 负责建实例） |
| `analytics_meta_v25it` / `analytics_metric_v25it` 两个库与三个受限账号 | **等 W03 交付** |
| `serverFingerprint` 实际值（`@@server_uuid`） | **等 W03 交付**后才能填真值 |
| Hive namespace / HDFS 落地根 / manifest 根 | **未做·环境不具备**（本机无 Hive/HDFS；模板中已就地标注） |

### 9.3 纪律声明

**没有**用宿主 3306 上的任何库（含 `test_db`、`mall_simulator_test`）临时顶替隔离环境——
那正是 F-100 的复发形态。宿主 `analytics_metric` / `analytics_meta` 在本任务中**仅作只读参考**。
`scripts/it-prepare-isolation.ps1` 亦**未创建**：它要执行建库/建账号 DDL，属写入冻结范围，
必须随 W03 的隔离实例一起交付。

---

## 10. L5 复核（2026-09-14，独立泳道，只读复核 + 独立取证）

> 本节为**追加**内容，未改动第 1–9 节任何一行。完整报告见同目录 `L5-verification-report.md`。

### 10.1 复核范围与结论（四级分列）

| 项 | 提交完成 | 测试通过 | 限定验收 | 完整验收 | 结论 |
| --- | --- | --- | --- | --- | --- |
| R-1 mall-simulator | ✅ | ⚠️ 默认关闭时模块套件**红（30 ERROR / 0 skipped）＝设计意图**；绿需 3307，**未取证** | ✅ | ❌ | 「默认关闭 ＋ 写前拒绝」**独立复现成立** |
| R-4 synthetic-data-generator | ✅ | ⚠️ 默认关闭时**红（120 run / 20E / 0 skipped）**；绿需 3307，**未取证** | ✅ | ❌ | 同上；`assumeTrue` 假绿已被彻底移除 |
| R-6 脚本 | ✅ | n/a | ⚠️ 部分 | ❌ | `smoke-pipeline.ps1` 三类拒绝实测成立；**`it-prepare-isolation.ps1` 实测致命不可运行** |
| R-3 Spark 门禁 | ✅（总控） | ✅（总控实测） | ✅ | — | 本泳道只做只读一致性核对，**未重跑** |
| R-5 | — | — | — | — | **超出本泳道任务包，未复核** |

### 10.2 本泳道独立取得的关键证据（原始日志 `raw/l5-*.log`）

| 项 | 命令（摘要） | 结果 |
| --- | --- | --- |
| R-1 默认关闭 | `mvn -o -f mall-simulator/pom.xml -Dmaven.repo.local=D:\maven_repository test` | **exit 1**，30 ERROR / **0 skipped**；根因 `MallIsolationException: [spring-datasource:dataSource] 无法识别的 JDBC URL 形态…${MALL_ISOLATION_URL}` ⇒ 在**建 `DataSource` 之前**就拒 |
| R-1 写前拒绝（3306） | 同上 + `MALL_ISOLATION_URL=jdbc:mysql://127.0.0.1:3306/l5probe-…` + `-Dmall.it.*` + `-Dtest=AuthServiceTest` | **exit 1**，`assertUrlAllowed` 拒 3306；日志 **0** 命中 `Communications link failure` / `Access denied` / `HikariPool-` / `Connection refused` |
| R-4 默认关闭 | `mvn -o -f synthetic-data-generator/pom.xml -Dmaven.repo.local=D:\maven_repository test` | **exit 1**，`Tests run: 120, Failures: 0, Errors: 20, **Skipped: 0**`；`GeneratorMetaStoreTest` 5/5 `GuardViolation`（类初始化失败） |
| R-4 写前拒绝（3306） | 同上 + `-Dit.guard.enabled=true -Dit.guard.instancePorts=3307 -Dit.guard.url=jdbc:mysql://127.0.0.1:3306/l5probe-…` | **exit 1**，`GuardViolation` 拒 3306，耗时 **0.043 s**（无网络往返），反证模式 0 命中 |
| R-6 `smoke-pipeline.ps1` | `pwsh -File scripts/smoke-pipeline.ps1 -BusinessTime … -MetricDb mall_simulator` / 不给口令 / `-MysqlUser root` | 三例全部**exit 5**，文案分别为「不在允许清单」「未提供数据库口令…不再有 123456 兜底」「账号为 root」 |
| R-6 `run-demo.ps1` | `pwsh -File scripts/run-demo.ps1 -Clean -ConfirmCleanTarget -MallDbUser mall_app` | **exit 1**（三程序未启动）⇒ 停在健康检查，输出中 `DELETE FROM` **0** 命中；**清场分支本身未取证** |

**加强判据（本泳道新增）**：即使调用方把 `3306` 塞进配置（`-Dmall.it.mysql.host=127.0.0.1:3306`），守卫仍**无条件**拒绝并把 `3306` 显示在 `allowedPorts` 里 —— 配置**不能**把正式端口洗白（对应 `IsolationGuard.java:254-262` 的修复注释）。

### 10.3 本泳道发现的缺陷（`file:line`）

| # | 缺陷 | 位置 | 严重度 |
| --- | --- | --- | --- |
| 1 | **`it-prepare-isolation.ps1` 全线不可运行**：`[string]$Host` 与 PowerShell 只读自动变量 `$Host` 冲突，参数绑定即失败，4 种调用方式全部 `WriteError: 无法覆盖变量 Host…`；`-File` 下 exit 1，`-Command` 下**exit 0（静默失败）**。⇒ 3307 隔离环境准备的唯一入口不可用，R-1/R-4 的完整验收路线被结构性阻塞 | `scripts/it-prepare-isolation.ps1:28`（同类引用 `:156`/`:157`/`:165`） | **阻塞级** |
| 2 | 建库/建账号用 `-uroot`，与本文件 `:14-15`「绝不用 root / metric_pub / mall_app / meta_app」自述**矛盾**（作用于 3307 隔离实例） | `scripts/it-prepare-isolation.ps1:140` | 中（待裁决） |
| 3 | `GeneratorContractParityTest.constantsAndPatternsMatchContract` NPE —— 契约 `properties.source_system` **无 `const`**（属 D-061 有意设计），测试期望与其漂移。**既有缺陷**（工作区/repo/`f00462c` 三方 blob 同一，两文件相对 HEAD 无改动），与 R-1/R-4 整改**无关**，建议另开单 | `synthetic-data-generator/src/test/java/com/graduation/generator/contract/GeneratorContractParityTest.java:86` | 中 |
| 4 | 连不上库时把 mysql 的 `ErrorRecord` 强转 `[long]` ⇒ 脚本以 `无法将类型"ERROR 1045…"…转换为类型"System.Int64"` **崩溃 exit 1**，而非给出 exit 5 的清晰拒绝 ⇒ 「拒绝」与「脚本自身 bug」不可区分 | `scripts/smoke-pipeline.ps1:137`、`:138`、`:149`、`:194` | 中 |
| 5 | `application-test.yml` 走 `${MALL_ISOLATION_URL}` 占位符，而模块侧门禁档案（`mall-isolation.local.properties`）**对 Spring 不起作用**（Spring 不读该文件）—— 实测第一次尝试即因放档案而得到「URL 未识别」的误导性报错，须改用环境变量/系统属性 | `mall-simulator/src/test/resources/application-test.yml:6` vs `mall-simulator/src/test/java/com/graduation/itguard/IsolationGuard.java:115-142` | 中（口径不一致） |
| 6 | `run-demo.ps1` 的 `DELETE FROM mall_simulator.event_outbox` 在 `-Clean -ConfirmCleanTarget -MallDbUser mall_app` + `$env:MALL_DB_PASSWORD` 齐备时**对正式库仍可达**（已加目标显式化 + 影响面预览 + 显式确认，但无「仅限隔离库」硬校验） | `scripts/run-demo.ps1:122` | 中（待裁决） |
| 7 | 生产配置未收口（相邻，非 R-1 范围）：`mall-simulator/src/main/resources/application.yml:8-10` 仍 `127.0.0.1:3306/mall_simulator` + `${MALL_DB_USER:root}` + `createDatabaseIfNotExist=true` | 同上 | 中 |
| 8 | 范围外明文残留**复确认仍存在**（与 `external-write-surfaces.md` §3.1 结论一致，本泳道只报不改） | `accept-p1-baseline.ps1:28`、`accept-three-programs.ps1:9`、`start-all.ps1:19` | 中 |

### 10.4 复核方法学补充（前序泳道可复用）

1. **「既有 vs 新增」不需要干净工作树**：`git status --porcelain -- <file>` + `git hash-object <file>` + `git rev-parse <commit>:<file>` 三方比对 blob 即可判定（本泳道据此把 §10.3 #3 判为**既有缺陷**，证据强度＝已证）。
2. **反证搜索必须排除自写注释**：对 `l5-*.log` 搜索 `Communications link failure` 等模式时，须过滤掉自己写的 `###` 说明行，否则会自证（本泳道初版搜索即误命中 1 行）。
3. **哈希二次快照**：取证前后各做一次被测面内容哈希快照并逐行 diff（`l5-workspace-hash-snapshot.md` vs `…-after.log`，190 行完全一致），可证明「结论不受取证期间 HEAD 前移影响」——本泳道取证期间 HEAD 由 `f00462c` 前进到 `fd3cee5`（总控文档提交），此快照即该结论的依据。

### 10.5 未取证清单（**不得**读作通过）

R-1/R-4 套件**绿灯**；`verifyBeforeWrite` 的**实连成功**路径（`@@server_uuid`/`@@port`）；`run-demo.ps1` 的 `DELETE` 分支行为；`it-prepare-isolation.ps1 -Confirm` 真实建库；`metric_read` 在正式库的实际权限；R-3 的 Maven 实跑（构建槽纪律）；R-5 全部；`smoke-pipeline.ps1` 在凭据正确时的完整 7 步链路。取证所需条件与逐项原因见 `L5-verification-report.md` §9。

### 10.6 本泳道自我约束与一项违规自报

- 遵守：只读正式数据（**零 3306 写入**）、不做 git 写操作、不启停 8090/8091/8092 与数据库/HDFS/集群、只在两棵树跑 Maven、证据只追加、未实测一律标「未取证」。
- **违规自报**：为判定「既有 vs 新增」，本泳道曾执行 `git worktree add --detach D:\Develop_code\_gp_headcheck HEAD`（**仓库外**，2026-09-14T13:57:36，2070 文件 / 92.8 MB，无 dump / 无备份 / 无新增明文口令）。该工作树已**即时停用**（其中 Maven 运行已 kill，未产出被引用的结论）、**未自行删除**，等总控统一处置；此后改用 §10.4 #1 的仓库内方法。
- `raw/l5-r4-generator-HEAD-baseline.log` 为该被 kill 的尝试的原始留档，**其中的结论未被本报告引用**。
