# DEV-001 ＋ DEV-002 门禁修复泳道 · 交付报告

- 泳道目录：`docs/acceptance/dev001-dev002-gate-fix-20260914/`
- 分支：`remediation/r1-boundary`（HEAD = `f13f7ce3cd7a16c4f87fdf264b132f05f6d5badf`，= `origin/main`）
- 日期：2026-09-14
- 范围裁定：本轮只做 **DEV-001（K-10）＋ DEV-002（K-11）**；DEV-003／DEV-004／F-93／3306 迁移／ACTIVE 切换均**未触碰**。
- 提交状态：**未提交、未推送**（等总控复核）。本目录与三个改动文件都还在工作区。

---

## 1. 修改文件列表（与边界声明一致，无边界外文件）

```
 M analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuard.java
 M analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuardTest.java
 M scripts/run-isolated-tests.ps1
?? analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/IsolationGuardMySqlIT.java   （新增，296 行）
?? docs/acceptance/dev001-dev002-gate-fix-20260914/                                                            （本轮证据）
```

`git diff --stat`：

```
 .../analytics/testsupport/TestIsolationGuard.java  | 100 +++++++-
 .../testsupport/TestIsolationGuardTest.java        | 272 +++++++++++++++++++++
 scripts/run-isolated-tests.ps1                     |  55 ++++-
 3 files changed, 418 insertions(+), 9 deletions(-)
```

未越界项（均未改）：指导书 V2.8、设计 V2.5、`contract-specs/`、`docs/thesis-materials/`、`docs/acceptance/` 既有泳道、`remediation-status.md`、`docs/backups/`、Derby、worktree、任何 `*IT.java`（除新增的 DEV-001 证据用例外）。**仅 `docs/PROJECT_STATUS.md` 属可写的 PM 文档**（见 §9）。

---

## 2. DEV-001：改了什么

**根因（本轮在真实例上重新取证，不是引用旧结论）**：旧实现在写前校验里用 `SELECT @@version_major` 取主版本号。实测**宿主 3306 与 WSL 3307（均 MySQL 8.0.41）都直接报错**：

```
ERROR 1193 (HY000) at line 1: Unknown system variable 'version_major'
```

证据：`raw/3306-version-major-probe.txt`、`raw/3307-version-major-probe.txt`。该异常被 `catch (SQLException)` 包成「写前校验无法完成（连接/查询失败）」抛出，导致后面的**端口／账号／权限／指纹四项判据一次都不执行**。

### 2.1 DEV-001a（主修）：版本判据改用 `SELECT VERSION()` 解析，且 fail-closed

`TestIsolationGuard.java`

- `verifyBeforeWrite` 内 `int major = majorVersion(connection);`（原 `SELECT @@version_major`，L525）。
- 新增 `private static int majorVersion(Connection)`：读 `SELECT VERSION()`，解析不出主版本号时抛
  `IsolationViolationException("无法从 SELECT VERSION() 识别服务端主版本号（实际 \"…\"）：实例不可信，拒绝继续（写前校验不降级、不跳过）")`。
- 新增包级 `static int parseMajorVersion(String)`（解析不出返回 −1）与 `VERSION_MAJOR = ^(\d{1,3})(?:\.|$)`。
- 顺带勘误 L522-524 注释：删掉「`@@version_major` 是 MariaDB 的变量」这一**未实测**归因，改成两台真实例的实测事实（注释级改动，不改语义）。

**没有**删除/绕过任何校验；**没有**降低任何隔离安全条件；**没有**用 try/catch 吞异常换绿。

### 2.2 DEV-001b（同文件同调用链，本轮真实现场暴露）：`GRANT USAGE ON *.*` 被误判为「全局非只读权限」

真 3307 上每个受限账号的 `SHOW GRANTS` 都必然带一行 `GRANT USAGE ON *.* TO ...`（现场：`raw/layer4-3307-guard-facts.txt`）。旧解析把它当成一条全局非只读权限 ⇒ **任何合法账号都会被判「账号持有全局非只读权限」而拒绝**。

修法：新增 `NO_EFFECT_PRIVILEGES = Set.of("USAGE")`，在解析循环里跳过（注释说明 USAGE 是占位标记）。语义**只会更严不会更松**：跳过的是「无权限」标记，真实全局权限（`ALL PRIVILEGES ON *.*` 等）仍然照旧拒绝（单测反例覆盖）。

### 2.3 DEV-001c（同文件同方法，本轮真实现场暴露）：反引号库名导致「本库写权限」被误算成「本次测试库之外还有写权限」

`SHOW GRANTS` 的库名带反引号（`ON \`db\`.*`）。旧解析把反引号一起当库名 ⇒ ①合法账号被误判「本次测试库之外还有写权限」；②禁止清单库的写授权**检测不到**（名字对不上）。

修法：

- 新增 `static String unquoteIdentifier(String)`（去掉一层反引号、还原 ` `` ` 转义，null → `""`）。
- `collectPrivileges` 中先把 scope 的 `.*` 去掉再 `unquoteIdentifier`，得到纯库名；随后禁止清单判定、`schemasWithWritePrivilege` 判定都基于纯库名。

> **边界说明（提请总控裁定）**：DEV-001b／001c 与 DEV-001a 在**同一个文件、同一条写前校验调用链**上，且是「DEV-001a 修好之后判据才第一次真正执行」才暴露出来的。修法与 DEV-001 目标同向（更严、更真），没有新增文件、没有放宽任何条件。但严格说它们**超出了 DEV-001 字面范围**，故在此显式登记为「同轮附带修复」，请总控确认是否接受；若不接受，可单独回退（回退后合法账号会被误拒，即又回到假红）。

### 2.4 DEV-001 的测试（新增）

`TestIsolationGuardTest.java`：20 → **24** 个用例（假驱动 Proxy 单测），新增 4 个：

- `serverMajorVersionIsParsedFromVersionString` —— 版本串解析（8.0.41 / 8.4 / `unknown` → 不可用）。
- `grantScopeIdentifiersAreUnquoted` —— 反引号还原（含 ` `` ` 转义）。
- `mysql8SemanticsRunAllFourJudgements` —— MySQL 8 语义下四类判据全部执行且通过；夹具对 `@@version_major` 主动抛 `1193`，用来钉死「旧写法必炸」。
- `everyJudgementReallyRejects` —— **7 个反例**：URL 声明 3307 实连 3306、root 账号、`GRANT ALL PRIVILEGES ON \`mall_simulator_test\`.*`、`GRANT INSERT ON \`analytics_metric\`.*`、`GRANT ALL PRIVILEGES ON *.*`、指纹不匹配、`VERSION()` 不可解析。

新增 `IsolationGuardMySqlIT.java`（**真 3307 真链**，本报告 §4 层次 4）——这是「DEV-001 修好后四类判据真的会执行」的**真库**证据，不是 mock。

---

## 3. DEV-002：改了什么

**根因**：两套隔离判据接受的指纹词表不一致。analytics 侧 `TestIsolationGuard.fingerprintMatches(expected, uuid, hostname)` 接受 `server_uuid`／裸 hostname／`hostname:任意端口`；mall／generator 侧 itguard `IsolationGuard.fingerprintMatches(expected, hostname, port)` **不认 server_uuid**，只认 hostname／`hostname:port`／port／`127.0.0.1:port`／`localhost:port`。而 runner 注入的是 `@@server_uuid` ⇒ **mall 30/30 假红**（旧证据：`Tests run: 30, Failures: 0, Errors: 30`）。

修法（`scripts/run-isolated-tests.ps1`，+55/−9）：

1. **唯一权威实例身份**：门禁 6 的活体探针在真实例上取 `@@hostname:@@port`，作为**唯一身份**注入 `IT_GUARD_SERVERFINGERPRINT`（原来是 `@@server_uuid`）。探针同时保留 `@@server_uuid` 作为**独立的 fail-closed 检查**（跨目标漂移 → `Fail 5`）。
   - 现场：`[门禁6] 唯一的实例身份（注入 IT_GUARD_SERVERFINGERPRINT）= dahaishui:3307`。
2. **generator 补真实数据源键**：generator 没有测试用 `application.yml`，其 `@SpringBootTest` 走主 `src/main/resources/application.yml`（`jdbc:mysql://127.0.0.1:3306/generator_meta?...createDatabaseIfNotExist=true` ＋ flyway `classpath:db/generator`）——这既是假红来源，也是**潜在的 3306 写入风险**。现在为 generator 模块注入 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`＝本模块隔离库/账号；mall 分支显式 `Remove-Item Env:\SPRING_DATASOURCE_*`，避免上一模块残留串味。
3. `-DryRun` 预演输出补上「运行期由门禁6探针确定」的占位说明与 generator 三个键（口令打码）。
4. 头注释补齐 DEV-002 根因、唯一身份语义、generator 主配置风险。

**没有**放宽成「任意非 3306 都算安全」：端口白名单仍是 `-AllowedPorts @(3307)`、`$FormalPort = 3306`；3306 仍被拒（§5）。**没有**fake 的测试专用硬编码指纹（`$InstanceFingerprint` 是运行期由探针填的占位值）。

未改动的部分（有意为之）：两侧 `fingerprintMatches` 的实现本身没动（见 §8 N-3）。

---

## 4. 真实执行了哪些测试（4 层，全部真跑，无 skip／无禁用／无 mock 顶替）

| 层次 | 命令 | 结果 | 证据 |
|---|---|---|---|
| 1 定向单测 | `mvn -f analytics-server/pom.xml -pl platform-common test -Dtest=TestIsolationGuardTest` | **24 / 0F / 0E / 0S**，exit 0 | `raw/layer1-unit-maven.log`、`raw/layer1-unit-surefire.txt` |
| 2 mall 隔离档 | `scripts/run-isolated-tests.ps1 -RunId dev12fix_20260914_1945 -Module mall -Confirm` | **30 / 0F / 0E / 0S**，`mall exit=0`，runner `[PASS exit=0]` | `raw/layer2-mall-runner.log`、`raw/layer2-mall-maven.log` |
| 3 generator 隔离档 | 同上 `-Module generator -Confirm` | **19 / 0F / 5E / 0S**，`generator exit=1`，runner `[FAIL exit=7]` | `raw/layer3-generator-runner.log`、`raw/layer3-generator-maven.log` |
| 4 真 3307 门禁真链 | `mvn -f analytics-server/pom.xml -pl metric-analysis -am test -Dtest=IsolationGuardMySqlIT -DfailIfNoSpecifiedTests=false` | **6 / 0F / 0E / 0S**，exit 0，BUILD SUCCESS | `raw/layer4-3307-realchain.log`、`raw/layer4-3307-surefire.txt`、`raw/layer4-3307-surefire.xml`、`raw/layer4-3307-guard-facts.txt` |
| 回归 | `mvn -f analytics-server/pom.xml test`（默认档 6 模块） | **628 / 0F / 0E / 0S**，exit 0 | `raw/default-tier-analytics-server.log` |

### 4.1 修复前后对照（隔离档，逐模块）

| 模块 | 修复前（`docs/acceptance/v26-real-testcounts-20260914/raw/g3-03-*`） | 修复后（本轮） | 判读 |
|---|---|---|---|
| mall | 30 run / **0F / 30E** / 0S，exit 1 | 30 run / **0F / 0E** / 0S，exit 0 | **30 个假红全部转绿**（DEV-002 达成） |
| generator | 19 run / **5F / 14E** / 0S，exit 1 | 19 run / **0F / 5E** / 0S，exit 1 | **14 个转绿**（`GeneratorApiSmokeTest` 5 ＋ `MallApiGenerationSmokeTest` 9）；剩 5 红见 §8 N-1 |
| 合计 | 49 run，**0 通过**（44E/5F） | 49 run，**44 通过**（5E） | runner 仍 exit 7（因 generator 5 红），但**已是真实业务断言通过**，不再是门禁假红 |

### 4.2 默认档回归（诚实口径）

- 本轮前：624（81＋156＋134＋48＋91＋114）。
- 本轮后：**628**（85＋156＋134＋48＋91＋114）——增量**全部**来自 `TestIsolationGuardTest` 20 → 24 的 4 个新用例，其余模块逐模块不变。
- 四棵树合计口径随之变为：628 ＋ 8（mall）＋ 101（generator）＋ 111（spark-jobs）＝ **848**（原 844）。
- 仍**不允许**写「624 即全部测试」「全量全绿」；正确表述：默认档 628（本轮后）＋ 未入默认档的 DB 用例（隔离档 49）。

---

## 5. 3307 真链最终结果 与 3306 零写入

### 5.1 3307（唯一写入目标）真链事实

- 实例：`@@server_uuid=de8ebbea-aff4-11f1-8037-00155d5dba47`、`@@hostname=dahaishui`、`@@port=3307`、`8.0.41`。
- 门禁 6 探针身份：**`dahaishui:3307`**（runner 注入的唯一实例身份）。
- Layer 4 守护事实行（原始日志字节见 `raw/layer4-3307-realchain.log`，ASCII 抽取见 `raw/layer4-3307-guard-facts.txt`）：

```
[isolation-guard] <写前校验通过> testRunId=dev12fix_20260914_1945 account=dev12fix_20260914_1945_mallapp
  currentDb=dev12fix_20260914_1945_mall serverUuid=de8ebbea-aff4-11f1-8037-00155d5dba47
  hostname=dahaishui port=3307 serverVersionMajor=8
  privileges=[dev12fix_20260914_1945_mall.ALL PRIVILEGES] schemasWithWritePrivilege=[]
  fingerprintSha1=f228772cc9e4bc8f693f503dd6bdf09a61d370d9
```

  即：**四类判据在真 3307 上全部真实执行**（`serverVersionMajor=8` 来自 `SELECT VERSION()`；`port=3307` 来自 `SELECT @@port`；`privileges` 来自真 `SHOW GRANTS`；`fingerprintSha1` 来自 `@@server_uuid/@@hostname`），且 `@@hostname:@@port` 与注入身份**逐字相等**。

- Layer 4 的 6 个用例（全部真连，无 mock）：

| 用例 | 真实现场 | 结果 |
|---|---|---|
| `legacyVersionQueryIsUnknownOnRealMysql8` | 真跑 `SELECT @@version_major` → SQLException **errorCode 1193**；同一连接 `SELECT VERSION()` → `8.` 开头 | 通过 |
| `realMysql8ChainRunsAllFourJudgementsAndPasses` | 真连、四判据全过；`privileges` 只含本库授权、无反引号、无 `.USAGE`；`schemasWithWritePrivilege=[]` | 通过 |
| `wrongFingerprintIsRejectedOnRealInstance` | 声明 `127.0.0.1:3307` → 拒（消息含实到 `dahaishui`） | 通过 |
| `formalPortUrlIsRejectedWithoutConnecting` | URL 端口 3306 → **建立连接之前**拒绝，连接计数 = 0 | 通过 |
| `forbiddenDatabaseUrlIsRejectedWithoutConnecting` | URL 指向 3307 上真实存在的 `analytics_metric` → 建连前拒绝，连接计数 = 0 | 通过 |
| `wrongDatabaseCredentialIsRejectedFailClosed` | mall 账号去连 generator 库，MySQL 连接阶段拒 → 门禁 fail-closed（「写前校验无法完成」），不给任何「通过」 | 通过 |

### 5.2 3306（冻结）零写入：15 项判据 pre/post 双向一致

`raw/3306-pre-post-diff.txt`：15/15 一致，**不一致项 = 0**。关键项：

- Flyway 未推进：`analytics_meta.flyway_schema_history` 17 行 / max_rank 17 / max installed_on `2026-09-12 15:06:05`；`analytics_metric` 3 / 3 / `2026-09-10 20:04:15`。
- 五张表 `CHECKSUM TABLE` 与 pre 完全一致：`1247589000`、`3529054551`、`69673863`、`988794366`、`1338236703`。
- ACTIVE 未变：`S20260901_47` / version 12 / active_flag 1 / status ACTIVE / 10 条 metric_value；快照清单仍 12 行（10 ARCHIVED ＋ 1 FAILED ＋ 1 ACTIVE）。
- 计数未变：snapshots 12、metric_value 110、data_quality_result 467。
- 实例身份未变：uuid `85191145-…5629`、port 3306、host `dahaishui`、`8.0.41`、datadir 未变。

本轮对 3306 的全部动作只有 **只读 SELECT/CHECKSUM** 与**一次「URL 指向 3306 但未建连」的门禁拒绝**（Layer 4 用例，连接计数 0）。**未做**迁移、写入、ACTIVE 切换、写入型验收。

---

## 6. 四类安全判据是否真实执行（本轮结论）

| 判据 | 真实执行证据 | 结论 |
|---|---|---|
| 端口（`@@port` ∈ {3307}，3306 拒） | Layer 4 正向 `port=3307` ＋ 3306 URL 建连前拒绝（connects=0） | **已实测** |
| 账号（禁清单 root/正式账号） | 单测反例 `root` 被拒；真 3307 上的账号为 `dev12fix_…_mallapp`（非禁清单） | 真库**仅正向**，反例为假驱动 |
| 权限（真 `SHOW GRANTS` 解析） | Layer 4 正向取到真授权 `GRANT USAGE ON *.*` ＋ `GRANT ALL PRIVILEGES ON \`db\`.*`，解析为 `db.ALL PRIVILEGES`、越界写权限集为空 | **已实测**（含 DEV-001b/001c 两种真实形态） |
| 指纹（`@@server_uuid`/`@@hostname` 与注入身份一致） | Layer 4 正向 + 反例 `127.0.0.1:3307` 被拒 | **已实测** |
| 版本（`SELECT VERSION()` 主版本号） | Layer 4 正向 `serverVersionMajor=8`；旧写法 1193 现场复现 | **已实测** |

---

## 7. 3306 是否发生任何写入

**没有。** 依据：§5.2 的 15 项 pre/post 判据全一致（含 5 张表 CHECKSUM 与 Flyway max_rank），且 Layer 4 的 3306 用例在建连之前就被拒（connection 计数 0）。所有测试库/账号都在 3307。

---

## 8. 新发现问题（本轮记录，不越界修）

- **N-1（阻断 DEV-002 的「全绿」，但属后续泳道）**：generator 隔离档仍 5 红，全部是 `GeneratorMetaStoreTest` 的 5 个用例，`Caused by: java.sql.SQLSyntaxErrorException: Table 'dev12fix_20260914_1945_generator.generation_run' doesn't exist`（同族表：`generation_event_stat`／`generator_target`／`generator_plan`）。即：隔离档没有对 `_generator` 库跑 Flyway 迁移，而这 5 个用例走裸 JDBC 直查表。修复前它们的形态是 **5F**，现在是 **5E**，红的还是同 5 个用例（失败形态变了，不是新增红）。归属：DEV-003 类（analytics-server 无 surefire 配置／隔离档编排），本轮不动。
- **N-2（真库反例缺失，显式标注未测）**：「账号在本次测试库之外还有写权限」这条判据**在真 3307 上没有实测**。原因：3307 上没有任何非 root 账号持有跨库写授权（证据 `raw/3307-grant-matrix.txt` 全量授权矩阵），要造这个现场必须新增一条跨库 GRANT＝新增对象，超出本轮边界。该判据目前只有假驱动单测反例（`everyJudgementReallyRejects`）覆盖。
- **N-3（DEV-002 的残留不一致，建议下一轮立 DEV-004 类项）**：本轮统一的是**注入层语义**（runner 只注入一个 `hostname:port` 身份，两侧判据都接受），**两侧判据实现本身仍是两套**：analytics 侧 `fingerprintMatches` 仍接受 `server_uuid` 与 `hostname:<任意端口>`（即 `dahaishui:3306` 也会被接受），mall 侧仍接受 `port`／`127.0.0.1:port`／`localhost:port`。这不影响本轮结论（3306 由端口白名单在写前拒绝），但「一份权威判定」在实现层面还没做到。
- **N-4（新用例可能永远是「没人跑」的绿）**：新增的 `IsolationGuardMySqlIT` 名字不带 `Test*`/`*Test` 前缀后缀，analytics-server 又没有 surefire 配置 ⇒ **默认档不会自动跑它**，必须 `-Dtest=IsolationGuardMySqlIT` 显式调用（并且需要 `-Dsurefire.failIfNoSpecifiedTests=false`，否则 `platform-common` 会因「没有匹配用例」而 BUILD FAILURE）。建议后续把它接入隔离 profile（与 DEV-003 同批处理），否则它只是一份「手工证据」。
- **N-5（工具链坑，记录备查）**：本机 Maven 控制台输出的中文标签在不同编码下不可靠还原（同一日志按 UTF-8／GBK 读都得不到正确中文）。因此本泳道证据里所有 Maven 日志都是**字节级拷贝**，另附 ASCII 抽取件（`raw/layer4-3307-guard-facts.txt`），不做转码。

---

## 9. `docs/PROJECT_STATUS.md` 更新

按本轮授权（PM 文档中仅此文件可写）更新：DEV-001／DEV-002 标为**已修复并实测**，附改动文件、四层测试数量、3307 真链结果、3306 零写入结论与证据路径；`F-88` **保持「限定验收」不变**，8 项未取证缺失项照旧；隔离档 exit 7 与 generator 5 红如实登记（N-1）。**未提交**。

---

## 10. 结论

- **DEV-001 = 已修复并实测**：写前校验在真 MySQL 8 上不再因版本查询中断；四类判据在真 3307 上真实执行（Layer 4 六例全绿）；旧写法 1193 现场复现；默认档 628 全绿无回归。限定：账号禁清单反例、跨库写权限反例在真库上未测（假驱动覆盖）。
- **DEV-002 = 已修复并实测**：mall/generator/runner 共用唯一实例身份 `@@hostname:@@port`＝`dahaishui:3307`；mall 30 个假红全清（30/0E）；generator 14 个转绿；3306 仍被拒且零写入。限定：两侧判据词表仍未在实现层统一（N-3）；隔离档整体仍 exit 7（generator 5 红，N-1）。
- **不建议现在提交**：等总控复核（含 DEV-001b/001c 是否纳入本轮的裁定、N-3 是否立新项）。
- **F-88 仍是「限定验收」**，本轮**不改变**其等级。

---

## 11. 补记（2026-09-14 20:10–20:18）：DEV-002 N-3 收口 —— 唯一权威实例身份固定为 `hostname:port`

> 本节为**追加补记**，不改写 §1～§10 原文。§8 的 `N-3` 与 §10 中「两侧判据词表仍是两套」一句**以本节为准**：analytics 侧已收紧到 canonical-only；mall／generator 侧**未改**（见 11.5，先报告后改动的要求）。

### 11.1 本轮授权（总控 20:1x 复核裁定）

1. DEV-001 通过，可关闭；2. DEV-001b／001c 纳入 DEV-001，**不另立编号**；3. DEV-002 主体通过，N-3 属残留，**修完才能关闭**；4. N-3 **不新立 DEV 编号**；5. N-1／N-4 归 DEV-003，本轮不修；6. F-88 **继续限定验收**；7. 本轮**只做 DEV-002 最后一处最小收口**，不 commit／push。

### 11.2 改了什么（仅 2 文件，均在授权边界内）

`analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/TestIsolationGuard.java`：

- `fingerprintMatches`：`(String expected, String serverUuid, String hostname)` → **`(String expected, String hostname, int port)`**（`:741`）。语义＝登记值与实连 `@@hostname + ":" + @@port` **规范化后完全相等**才通过（两侧 `trim`，hostname 段忽略大小写）；`expected` 空／`hostname` 空／`port <= 0` 一律 `false`。旧实现另外接受 `server_uuid`、裸 `hostname`、`hostname:<任意端口>` 前缀，已全部删除。
- 调用点（`:555-558`）：`fingerprintMatches(expected, hostname, port)`；失败消息补为「登记=…，实际 hostname=… port=…（唯一权威身份形如 hostname:port，规范化后必须完全相等；独立漂移事实 server_uuid=… 不得替代指纹判定）」。
- 新增 `requireServerUuid(Connection)`（`:754`，fail-closed）：`@@server_uuid` 查询失败 ⇒ 交由上层包成「写前校验无法完成」；取到空值 ⇒ 直接 `IsolationViolationException`。该值仍是 `LiveFacts` 字段并计入 `sha1()`／`redactedSummary()`，但**不再参与指纹放行**。
- 文档同步：类注释（`@@version_major` 之外的判据描述）与 `verifyBeforeWrite` 的 ② 项改为「指纹＝`hostname:port`；`server_uuid` 独立漂移事实」，并显式写明「⑤端口门禁 与 ②指纹门禁 是两层独立约束，不靠一层兜底另一层」。
- **未改动**：`ALLOWED_INSTANCE_PORTS = Set.of(3307)`（`:100`）、`HOST_FORMAL_PORT = 3306`、`rejectForbiddenInstancePort`、禁止库／禁止账号清单、`verifyBeforeWrite` 判据顺序与 fail-closed 语义。

`.../TestIsolationGuardTest.java`：

- `FINGERPRINT`：`host-v25it:8b7f` → **`host-v25it:3307`**（`:49`），与夹具 `FakeMySql8` 的 `@@hostname=host-v25it`／`@@port=3307` 对齐。
- 原 `fingerprintMismatchIsRejected`（断言旧的宽松语义：裸 hostname／uuid／`host:<任意端口>` 均为 `true`）**重写**为 `fingerprintOnlyAcceptsCanonicalHostnamePort`（`:194`，15 条断言）。
- 新增 4 个用例（`:222`／`:241`／`:257`／`:271`，见 11.3）+ 夹具 `FakeMySql8` 支持 `@@server_uuid` 为空值（`rowsFor` 用 `Collections.singletonList`、`getString` 对 `null` 返回 `null`），否则 fail-closed 分支无法被测。
- 类内 `@Test` 计数 **28**，与 surefire 的 `Tests run: 28` 一致。

### 11.3 强制用例覆盖（逐条可查）

| 强制情形（总控） | 断言处 | 结果 |
|---|---|---|
| hostname ＋ 正确 3307 ⇒ PASS | `:196` `fingerprintMatches("dahaishui:3307","dahaishui",3307)` | true ✅ |
| hostname 相同但 3306 ⇒ FAIL | `:199` `("dahaishui:3307","dahaishui",3306)` | false ✅ |
| hostname 不同但端口 3307 ⇒ FAIL | `:201` `("dahaishui:3307","otherhost",3307)` | false ✅ |
| `server_uuid` 不得作为 `hostname:port` 的替代值 | `:203-206`（登记值＝uuid 一律 false）＋ **门禁级** `:222`（登记值＝夹具真实 uuid，逐字相同，仍被「服务实例指纹不匹配」拒绝） | false ✅ |
| 原有 `server_uuid` 独立漂移保护仍有效 | `:257` 缺 uuid ⇒ fail-closed 拒绝；`:271` uuid 变 ⇒ `fingerprintSha1` 必变 | 通过 ✅ |
| 追加：端口门禁与指纹门禁两层独立 | `:241` 登记指纹写 `host-v25it:3306`、实连 `3307` ⇒ 由**指纹门禁**拒（消息不含「宿主正式 MySQL 实例端口」） | 通过 ✅ |
| 追加：旧宽松形态一并收紧 | `:207-212` 裸 hostname／裸 `3307`／`127.0.0.1:3307`／`localhost:3307`／`dahaishui:3307` vs 实际 `dahaishui:3306` | 全部 false ✅ |
| 追加：缺失值不兜底 | `:213-218` `expected=""`／`null`、`hostname=null`／`"  "`、`port=-1` | 全部 false ✅ |

### 11.4 复跑（A～E，全部真跑；含 3306 只读复核）

| 层 | 命令 | 结果（run/F/E/S） | 证据 |
|---|---|---|---|
| A 定向单测 | `mvn -f analytics-server/pom.xml -pl platform-common test -Dtest=TestIsolationGuardTest -Dsurefire.failIfNoSpecifiedTests=false` | **28 / 0 / 0 / 0**，exit 0 | `raw/n3-layer1-unit-maven.log`、`raw/n3-layer1-unit-surefire.txt` |
| B mall 隔离档 | `scripts/run-isolated-tests.ps1 -RunId dev12fix_20260914_1945 -Module both -Confirm` | mall **30 / 0 / 0 / 0**，exit 0 | `raw/n3-layer2-3-runner.log`、`raw/n3-layer2-mall-maven.log` |
| C generator 隔离档 | 同上（同一轮 `-Module both`） | generator **19 / 0 / 0 / 0**，exit 0，runner `[PASS exit=0]` | `raw/n3-layer3-generator-maven.log`（**态依赖**，见 11.6） |
| D 真 3307 门禁真链 | `mvn -f analytics-server/pom.xml -pl metric-analysis -am test -Dtest=IsolationGuardMySqlIT -Dsurefire.failIfNoSpecifiedTests=false` | **6 / 0 / 0 / 0**，BUILD SUCCESS | `raw/n3-layer4-3307-realchain.log`、`raw/n3-layer4-3307-surefire.txt`、`raw/n3-layer4-3307-surefire.xml`、`raw/n3-layer4-3307-guard-facts.txt` |
| E 默认档回归 | `mvn -f analytics-server/pom.xml test` | **632 / 0 / 0 / 0**，exit 0 | `raw/n3-default-tier-analytics-server.log` |
| 3306 冻结复核 | `mysql -h 127.0.0.1 -P 3306 -u root` 只读 16 行探针 | 与 `raw/3306-readonly-post.txt` 规范化后**逐行一致（差异 0）** | `raw/n3-3306-readonly-recheck.txt` |

- 默认档 628 → **632**：逐模块 `platform-common 85 → 89`，其余 `156／134／48／91／114` 全不变；增量**全部**来自 `TestIsolationGuardTest` 24 → 28（+4）。
- 真 3307 真链的注入指纹仍是 runner 门禁 6 探针读到的 `dahaishui:3307`，本轮 6 例全绿 ⇒ canonical 形式在**真实例**上通过（不再依赖 uuid 兜底）；`fingerprintSha1=f228772cc9e4bc8f693f503dd6bdf09a61d370d9` 与收口前**逐字相同** ⇒ 事实采集未漂移，本次只改了「放行判据」。

### 11.5 只报不改（显式登记，供总控裁决）

1. **mall／generator 侧 `IsolationGuard.fingerprintMatches` 未改**：`mall-simulator\src\test\java\com\graduation\itguard\IsolationGuard.java:396-403` 与 `synthetic-data-generator\...\com\graduation\itguard\IsolationGuard.java:396-403` 是同源同段，除 `hostname`／`hostname:port` 外**仍接受**裸 `port`、`127.0.0.1:<port>`、`localhost:<port>`（后两者**与主机名无关**）。按「先报告再做最小修改」的要求：四条强制反例它**已满足**（同机 3306 → false；异机 → false；uuid 不在词表内 → false），本轮**未改**。
2. **陈旧提示未改**（超出 2 文件边界）：`scripts/it-prepare-isolation.ps1:199` 仍打印「…`SELECT @@hostname` 得到；若与宿主同名，用 port/uuid 区分」；`scripts/it-isolation.env.template:31` 仍写「登记实例指纹（`@@hostname` 或 `host:port`）」。按新语义这两处**会误导**（裸 hostname／uuid 现已一律被拒），但拒绝方向是 fail-closed，不影响运行。
3. **端口白名单未动**：3306 仍同时受「端口门禁（`ALLOWED_INSTANCE_PORTS`／`HOST_FORMAL_PORT`）」与「指纹门禁」两层约束；本轮新增的 `:241` 用例专门证明「实连 3307 时，登记指纹写 3306 也会被指纹门禁独立拦下」，即两层互不兜底。

### 11.6 generator 转绿的真实归因（**不得记作 DEV-003 已修**）

本轮 C 层 `19/0F/0E/0S`、runner `exit 0`，原因**不是** Flyway 编排被修好：

- 首跑（19:57，`raw/layer3-generator-maven.log`）：`GeneratorMetaStoreTest` 5 例报 `Table 'dev12fix_20260914_1945_generator.generation_run' doesn't exist`（另 3 张：`generation_event_stat`／`generator_target`／`generation_plan`）⇒ **5E**；同一进程稍后 generator Spring 上下文启动 Flyway：`Schema history table … does not exist yet` → `Creating Schema History table …` → `Migrating schema … to version "1 - generator meta"` → `Successfully applied 1 migration`（19:57:04）。
- 复跑（20:12，`raw/n3-layer3-generator-maven.log`）：`Schema dev12fix_20260914_1945_generator is up to date. No migration necessary.`，5 例全绿。
- 3307 只读取证：`flyway_schema_history` **1 行 / version 1 / `installed_on=2026-09-14 19:57:04`**；`generation_artifact`／`generation_event_stat`／`generation_plan`／`generation_run`／`generator_target` 的 `create_time` **全为 `19:57:04`**，早于复跑时点 20:12:34。

⇒ 结论：绿是**态依赖**（沿用上一轮残留的已迁移 schema）。**新建 runId 的隔离库上仍会复现 5 error**；DEV-003 子项 1（隔离库先迁移再跑用例）**未闭合**，本轮**未改任何 Flyway 编排**。

### 11.7 3306（冻结）零写入复核

- 只读复取 16 行与 `raw/3306-readonly-post.txt` **逐行一致（差异 0 项）**：uuid `85191145-1491-11f0-b4e2-60cf84d55629`／port `3306`／`dahaishui`／`8.0.41`；flyway `17/17`（meta）与 `3/3`（metric）；`snapshots=12`／`metric_values=110`／`dqr_rows=467`；ACTIVE `S20260901_47` version `12`／`value_rows=10`；CHECKSUM `1247589000`／`3529054551`／`69673863`／`988794366`／`1338236703`。
- 本轮**未执行**任何 3306 写语句、未迁移、未切换 ACTIVE。

### 11.8 结论（本补记）

- **DEV-002 = 已修复并实测关闭**：唯一权威实例身份固定为 `hostname:port`；`@@server_uuid` 只作独立 fail-closed 漂移事实，替代关系已在实现层与用例层双向消除；四层复跑 28／30／6／632 全绿，3306 零写入。
- **DEV-003 仍开**：子项 1（generator 隔离库 Flyway 编排）、子项 2（`IsolationGuardMySqlIT` 执行入口）；N-3 未新立编号，N-1／N-4 已归入 DEV-003。
- **F-88 仍限定验收**，未升级。
- **未提交、未 push**；等总控复核后再决定提交。

## 12. 补记（2026-09-14 20:28–20:44）：DEV-002 语义一致性收口 —— mall/generator 判据统一为 canonical `hostname:port`

### 12.0 本补记的效力（与 §11.5 / §11.8 的关系）

§11.8 写「DEV-002 = 已修复并实测关闭」，口径**过宽**：当时 §11.5 的 1、2 两条残留仍开着（mall/generator 判据未改、两处陈旧提示未改）。**以本节为准**：把这两条收口并复跑后，DEV-002 的关闭才成立。§11.5.1 与 §11.5.2 自本节起**视为已关闭**（保留原文，不重写）。

### 12.1 授权边界（总控本轮裁定）与实际越界检查

只允许：① 统一 mall/generator `fingerprintMatches`；② 仅改 `scripts/it-prepare-isolation.ps1`、`scripts/it-isolation.env.template` 中与实例指纹语义相关的提示；③ 复跑 5 项 + 8 条反例；④ 状态文件与本节补记。**不 commit、不 push**。

- 实际改动 = 授权范围内的 6 个文件（见 12.3），**无越界**；`run-isolated-tests.ps1`、`TestIsolationGuard*.java`、`IsolationGuardMySqlIT.java` 本轮**未再改**（沿用 §11 版本）。
- 越界检查：未动指导书 V2.8／设计 V2.5／`contract-specs`／`docs/thesis-materials`／其他验收泳道／3306／ACTIVE／Flyway 编排。

### 12.2 判据最终语义（三处守卫现在同一条语义）

| 守卫 | 位置 | 判据 |
|---|---|---|
| analytics | `analytics-server/platform-common/.../TestIsolationGuard.java:741` | `expected.trim().equalsIgnoreCase(hostname.trim() + ":" + port)`，且 `expected/hostname` 非空、`port > 0` |
| mall | `mall-simulator/src/test/java/com/graduation/itguard/IsolationGuard.java:420-425`（失败消息 `:382`） | 同上（逐字同源） |
| generator | `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolationGuard.java:420-425`（失败消息 `:382`） | 同上（与 mall 文件 **sha256 完全相同**） |

- **唯一权威实例身份 = `hostname:port`**（例 `dahaishui:3307`）；规范化 = 两侧去首尾空白 + hostname 段大小写归一。
- **一律拒绝**：裸 `hostname`、裸 `port`、`127.0.0.1:<port>`、`localhost:<port>`、`@@server_uuid`；不做前缀匹配、不做端口模糊匹配。
- 缺失/空白/`port<=0` 一律 **fail-closed**（`return false`，不兜底）。
- `@@server_uuid` 仍是**独立漂移事实**：由 runner 门禁 6 探针校验（`run-isolated-tests.ps1:227`），**不是** fingerprint 的替代值。
- **端口白名单独立存在、未删**：mall/generator `assertPortAllowed` + `HOST_FORMAL_PORT=3306`；analytics `ALLOWED_INSTANCE_PORTS={3307}`。两层互不兜底（§11.5.3 的 `:241` 用例仍覆盖）。
- 可见性变更：`fingerprintMatches` 由 `private static` 改为**包内可见** `static`，以便同包单测直接验证反例集（判据本身只多不少）。

### 12.3 最终修改文件列表（本轮增量）

| 文件 | 变化 | 大小/行 | sha256(前16) |
|---|---|---|---|
| `mall-simulator/src/test/java/com/graduation/itguard/IsolationGuard.java` | 判据重写 + `:39` 配置提示 + 失败消息 | 23,152 B / 459 行 | `35006DE37D34E8D4` |
| `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolationGuard.java` | 与上**逐字节相同** | 23,152 B / 459 行 | `35006DE37D34E8D4` |
| `mall-simulator/src/test/java/com/graduation/itguard/IsolationGuardFingerprintTest.java`（新增） | 5 例反例集（默认档，无 `@Tag`） | 4,526 B / 82 行 | `90D7FBD20E6D1BA9` |
| `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolationGuardFingerprintTest.java`（新增） | 与上**逐字节相同** | 4,526 B / 82 行 | `90D7FBD20E6D1BA9` |
| `scripts/it-prepare-isolation.ps1:199` | 陈旧提示 → canonical 表述 | 13,875 B / 216 行 | `ED5C2D165A9DC5CB` |
| `scripts/it-isolation.env.template:31-34` | 陈旧提示 → canonical 表述（4 行） | 4,637 B / 54 行 | `CEF5543C541D85A1` |

**陈旧提示改后原文**（两处统一为 `IT_GUARD_SERVERFINGERPRINT = hostname:port`）：

- `it-isolation.env.template:31-34`：`# IT_GUARD_SERVERFINGERPRINT=  # 登记实例指纹＝hostname:port（唯一权威形态，例 dahaishui:3307）` + `规范化后必须与实连实例 @@hostname:@@port 完全相等` + `裸 hostname／裸端口／127.0.0.1:port／localhost:port 一律拒绝` + `@@server_uuid 是门禁6 的独立漂移事实，**不是** fingerprint 替代值`。
- `it-prepare-isolation.ps1:199`：`serverFingerprint=<唯一权威形态 hostname:port，例 dahaishui:3307：… SELECT CONCAT(@@hostname, ':', @@port) 得到；…>`。**刻意不插值 `$DbHost`/`$Port`**——插值会打印 `127.0.0.1:3307`，而该形态现在是被拒的反例。两文件 `Parser` 解析错误数 = 0（实测）。
- 其他环境变量语义（`INSTANCEPORTS`／`RUNID`／`URL`／`USER`／`PASSWORD`／`MALL_ISOLATION_*`／`SPRING_DATASOURCE_*`）**未改**。

### 12.4 8 条强制反例（逐条实测）

单测（mall／generator 各一份，`IsolationGuardFingerprintTest`，5 个 `@Test`／25 条断言，两模块默认档各 `5 / 0 / 0 / 0` 全部通过）：

| # | 登记值（实连 `dahaishui:3307`） | 期望 | 实测 |
|---|---|---|---|
| 1 | `dahaishui:3307` | PASS | **PASS**（另含 `  DAHAISHUI:3307  ` 大小写/空白规范化 PASS、异机 canonical `otherhost:3307` PASS） |
| 2 | `dahaishui:3306` | FAIL | **FAIL** |
| 3 | `otherhost:3307` | FAIL | **FAIL**（另 `dahaishui2:3307`、`dahaishu:3307`、`dahaishui.local:3307` 均 FAIL ⇒ 无前缀匹配） |
| 4 | `dahaishui` | FAIL | **FAIL** |
| 5 | `3307` | FAIL | **FAIL** |
| 6 | `127.0.0.1:3307` | FAIL | **FAIL** |
| 7 | `localhost:3307` | FAIL | **FAIL** |
| 8 | `de8ebbea-aff4-11f1-8037-00155d5dba47`（`@@server_uuid`） | FAIL | **FAIL** |

**真跑反例（超出单测的实链证据）**：以 `IT_GUARD_SERVERFINGERPRINT=dahaishui`（裸 hostname）真跑 mall 隔离档 ⇒ **fail-closed**：`MallIsolationException: [flyway-before-migrate] 实例指纹不匹配：登记为 dahaishui，实际 hostname=dahaishui port=3307（…裸 hostname／裸端口／127.0.0.1:port／localhost:port 一律不接受…）`，Flyway 在 `beforeMigrate` 回调就被拦下，**30 例全部 ERROR、无一条进入 DDL/DML**，exit=1（非 0）。证据 `raw/n4-realpath-negative-bare-hostname-extract.txt`。

### 12.5 复跑（A～E，全部真跑，无 skip／无 mock 顶替）

| 层 | 命令 | 结果（run/F/E/S） | 证据 |
|---|---|---|---|
| A analytics 定向单测 | `mvn -f analytics-server/pom.xml -pl platform-common test -Dtest=TestIsolationGuardTest -Dsurefire.failIfNoSpecifiedTests=false` | **28 / 0 / 0 / 0**，exit 0 | `raw/n4-analytics-TestIsolationGuardTest-*.txt`、`...-maven.log` |
| B mall 隔离档（**新 runId**） | `it-prepare-isolation.ps1 -RunId dev002sem_20260914_2035 -Confirm -AllowRootOnIsolated` → `run-isolated-tests.ps1 -RunId dev002sem_20260914_2035 -Module both -Confirm` | mall **30 / 0 / 0 / 0**，exit 0 | `raw/n4-runner-both-newrunid.log`、`raw/n4-isolated-mall-newrunid.log` |
| C generator 隔离档（**同一新 runId**） | 同上（同一轮 `-Module both`） | generator **19 / 0F / 5E / 0S**，exit 1 ⇒ runner `[FAIL exit=7]` | `raw/n4-isolated-generator-newrunid.log`、`raw/n4-generator-dev003-flyway-order.txt` |
| D 真 3307 门禁真链 | `mvn -f analytics-server/pom.xml -pl metric-analysis -am test -Dtest=IsolationGuardMySqlIT -Dsurefire.failIfNoSpecifiedTests=false` | **6 / 0 / 0 / 0**，BUILD SUCCESS | `raw/n4-realchain-3307-*.txt` |
| E 默认档回归 | `mvn -f analytics-server/pom.xml test` | **632 / 0 / 0 / 0**，exit 0（89／156／134／48／91／114，与 §11.4 逐模块相同） | `raw/n4-analytics-default-tier-maven.log` |
| 附 1 mall 默认档 | `mvn -f mall-simulator/pom.xml test` | **13 / 0 / 0 / 0**（8 → 13，+5 = 新反例集） | `raw/n4-mall-default-tier-maven.log` |
| 附 2 generator 默认档 | `mvn -f synthetic-data-generator/pom.xml test` | **106 / 0 / 0 / 0**（101 → 106，+5 = 新反例集） | `raw/n4-generator-default-tier-maven.log` |
| 附 3 3306 冻结复核 | 只读探针（无写语句） | 10 项判据 **差异 0** | `raw/n4-3306-readonly-recheck.txt` |

- **「指纹假红」= 0**：runner 门禁 6 探针在真 3307 上读出 `@@hostname:@@port = dahaishui:3307` 并注入 `IT_GUARD_SERVERFINGERPRINT`，三层守卫在新严格判据下**全部放行**（mall `[flyway-before-migrate]` 与 `MallTestSupport.freshState` 的 live facts 均为 `hostname=dahaishui port=3307 runId=dev002sem_20260914_2035`）。
- 新反例集**不带 `@Tag("it")`**，只进默认档；隔离档 pom 的 `<groups>it</groups>` 不变 ⇒ 隔离档口径仍是 30（mall）／19（generator），**新增用例不污染隔离档计数**。
- 新 runId 隔离事实（`raw/n4-newrunid-instance-facts.txt`）：`@@hostname/@@port/@@server_uuid = dahaishui / 3307 / de8ebbea-aff4-11f1-8037-00155d5dba47`；`dev002sem_20260914_2035_generator` 6 表（含 `flyway_schema_history`，`installed_on=2026-09-14 20:32:18`）；`dev002sem_20260914_2035_mall` 13 表、`flyway_schema_history` 2 行 / max version 7；两个受限账号各自**只**在自己库上有 17 项表级权限（无跨库写授权）。

### 12.6 generator 新 runId 的 5E 真实归因（**修正 §11.6 的表述，DEV-003 仍开**）

总控预判「新 runId 会重新暴露 5 例」，**预判方向正确**，但**机制表述需修正**：不是「隔离库未执行 Flyway」，而是「**Flyway 执行得太晚**」——

1. 执行顺序（同一 JVM 单线程，日志行号即执行顺序，`raw/n4-generator-dev003-flyway-order.txt`）：`Running GeneratorMetaStoreTest` → 首次 `Table '…generation_event_stat' doesn't exist`（第 **56** 行）→ `Running GeneratorApiSmokeTest` → 首次 `Flyway Community Edition`（第 **284** 行）→ `Successfully applied 1 migration`（第 **294** 行，`20:32:18.241`）。
2. 该类**没有 Spring 上下文**：`GeneratorMetaStoreTest:60-70` 只有 `@Tag("it")` + `static {}` 门禁 + 自建 `DriverManagerDataSource`，从不触发 Flyway；本项目里唯一跑 Flyway 的地方是 `@SpringBootTest` 的 `GeneratorApiSmokeTest`／`MallApiGenerationSmokeTest`。
3. 3307 只读取证：5 张业务表 + `flyway_schema_history` 的 `CREATE_TIME` 全为 `20:32:18`（晚于该类的 5 个 error）；`flyway_schema_history` 1 行 / version 1 / `installed_on=2026-09-14 20:32:18`。
4. 逐类结果：`GeneratorMetaStoreTest` **5 run / 5 error**（4 张表缺失）；`GeneratorApiSmokeTest` **5 / 0**；`MallApiGenerationSmokeTest` **9 / 0** ⇒ 合计 19，**非 DEV-003 的 14 例全绿**。
5. 反向对照：`dev12fix_20260914_1945_generator` 上一轮先被 Flyway 建过表，故 §11.4 的复跑 19/19 全绿 ⇒ 绿是**态依赖**。

⇒ DEV-003 子项 1 的准确表述应为「**generator 隔离库的建表（Flyway）与 `GeneratorMetaStoreTest` 的执行没有编排关系**：首跑必红（5E），二次跑才绿」。本轮**未改任何 Flyway 编排**（越界），只把归因钉死。

### 12.7 3306（冻结）零写入复核

- 只读探针 10 项判据与 `raw/3306-readonly-post.txt` **差异 0**：uuid `85191145-1491-11f0-b4e2-60cf84d55629`／port `3306`／`dahaishui`／`8.0.41`；META flyway `17/17/…15:06:05`；METRIC flyway `3/3/…20:04:15`；`snapshots=12`／`metric_values=110`／`dq_rows=467`；ACTIVE `S20260901_47` version `12`／`active_flag=1`／`status=ACTIVE`／`value_rows=10`；5 张表 checksum `1247589000／3529054551／69673863／988794366／1338236703`。
- 本轮对 3306 **只发过只读 `SELECT`／`CHECKSUM TABLE`**，未迁移、未写、未切 ACTIVE。
- 3307 新增（本轮唯一写入目标）：库 `dev002sem_20260914_2035_mall`／`_generator`、账号 `..._mallapp`／`..._genapp`；**未删除任何既有库／账号**（清理仍归 F-93）。

### 12.8 只报不改（本轮新发现/复核，供总控裁决）

1. **仍是陈旧提示、但不在本轮授权边界内**：`mall-simulator/src/test/resources/application-test.yml:32` 与 `synthetic-data-generator/src/test/resources/it-guard.local.properties:26` 仍写 `serverFingerprint=<@@hostname 或 host:port>`（生成器那份是**入库文件**）。按新语义同样会误导，方向 fail-closed，不影响运行。
2. **陈旧构建产物**：`mall-simulator/target/test-classes/mall-isolation.local.properties`（gitignore 覆盖、非源码）仍是 L5 负向验证留下的 `enabled=true / serverFingerprint=127.0.0.1:3306 / testRunId=l5probe-…`。它不是运行期生效来源（本轮 B 层绿即证明环境变量优先），且在新判据下**双重被拒**（裸地址 + 3306）；未清理（`target/` 不在本轮边界）。
3. **`IsolationGuardMySqlIT` 仍未接入标准自动执行入口**：只能显式 `-Dtest=IsolationGuardMySqlIT` 跑（analytics-server 无 surefire 配置）⇒ DEV-003 子项 2 保持开启。

### 12.9 结论（本补记）

- **DEV-002 = 已修复并实测关闭**（以此为准）：三处守卫判据统一为 canonical `hostname:port`，`@@server_uuid` 与「裸地址/裸端口」全部降为被拒形态；8 条强制反例逐条实测 + 1 条真跑反例 fail-closed；A/B/D/E 与两个默认档全绿（28／30／6／632／13／106），**指纹假红 0**。关闭口径**不含** runner 级「`-Module both` 全绿」——该绿灯被 DEV-003 挡着（见下）。
- **DEV-003 仍开，两个子项都在**：① generator 隔离库建表与 `GeneratorMetaStoreTest` 无编排（新 runId 首跑 5E 已复现并归因，见 12.6）；② `IsolationGuardMySqlIT` 无标准自动执行入口。
- **F-88 仍限定验收**，未升级。
- **未提交、未 push**；等总控复核后再决定提交。

### 12.10 证据完整性自查（**2026-09-15 08:52–08:55 补记**，非本轮 20:28–20:44 当时的动作）

**时间口径声明**：本节是在**次日（2026-09-15 08:5x）**复核工作区时补做的脚本化自查，此前 §12.1–12.9 的所有实测结论均未改动；本节只新增「证据完整性」与「数字回查」两类事实。第 1 条中「原先追写的结论行已撤回」指的是 09-14 20:4x 写 §12 时的动作，撤回结果在本节复核时（09-15）重新哈希确认。

供总控独立复核用，全部为**脚本化复算**，非人工誊抄：

1. **原始日志按字节入库**：本轮 `raw/n4-*` 18 文件中，14 文件由 `%TEMP%\dev002n4-logs` 原样复制，逐份 `Get-FileHash -Algorithm SHA256` 与原始日志比对 —— **14/14 相同，漂移 0**；原始日志内**不含任何事后追写的结论文本**（原先追写到 `n4-3306-readonly-recheck.txt` 的比对结论行已撤回，该文件恢复为机器输出原样，结论移入清单与本节）。剩余 3 文件为生成/节选（`n4-generator-dev003-flyway-order.txt`、`n4-newrunid-instance-facts.txt`、`n4-realpath-negative-bare-hostname-extract.txt`，均由日志或只读 SQL 输出生成），1 文件为清单本身。
2. **清单**：`raw/n4-evidence-manifest.txt` 逐份登记「文件名｜字节｜sha256｜入库方式（[原样]/[节选]/[生成]）」，并声明原始负例日志 871,033 B 未入库、入库的只是节选。
3. **报告数字 vs 原始证据逐条回查（25 项判据）**：**24 PASS / 1 FAIL**，FAIL 项为写错的**匹配式**而非错误结论 —— 「632」**不是 Maven 打印的 reactor 汇总行**（多模块 `mvn test` 只有逐模块 `Results:` 汇总），已改为逐模块复算：`platform-common 89 / connection-ingestion 156 / warehouse-pipeline 134 / metric-analysis 48 / ai-decision 91 / platform-app 114`，6 行汇总 `F=E=S=0`，**合计 632**；且与 N-3 轮日志 `Compare-Object` **逐模块完全一致（差异 0）**。⇒ §12.5 的「632」口径应读作「六模块汇总行之和」，非单行输出。
4. 其余 PASS 项含：A 层 `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`；B 层 mall `30/0/0/0` ＋ `hostname=dahaishui`＋`port=3307`＋`runId=dev002sem_20260914_2035`；runner 日志尾部 `mall exit=0 / generator exit=1 [ERROR] Tests run: 19, Failures: 0, Errors: 5, Skipped: 0` 与 `[FAIL exit=7]`；C 层 `GeneratorApiSmokeTest 5/0/0/0` 与 `MallApiGenerationSmokeTest 9/0/0/0` 两行原文；D 层 `6/0/0/0`；附 1 `13/0/0/0`；附 2 `106/0/0/0`；真跑反例 `Errors: 30` ＋ `flyway-before-migrate`；3306 uuid／ACTIVE `S20260901_47`／snapshot checksum `69673863`；3307 uuid `de8ebbea-aff4-11f1-8037-00155d5dba47`。
5. **仍未取证（保持标注，不因本自查升级）**：8 条反例中 7 条仅有单测级证据（仅裸 hostname 有真跑证据）；「账号在本次测试库之外还有写权限」判据未在真 3307 实测；`IsolationGuardMySqlIT` 仍未接入标准自动执行入口；`spark-jobs` 111 **本轮未复跑**（862 中的 111 沿用既有口径）。
