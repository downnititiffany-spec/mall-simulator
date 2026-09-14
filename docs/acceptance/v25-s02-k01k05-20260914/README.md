# V25-S02 / K-01～K-05 泳道 L7 验收记录（2026-09-14）

> 泳道：L7（唯一写入者）。分支：`remediation/r1-boundary`。本轮**未推送**（总控评审后由总控推送）。
> 范围：`docs/design/项目设计文档 V2.3.md:94-102` 定义的 K-01～K-05，以及 `:896` 的 V25-S02（测试隔离配置）。
> **K-06～K-09 未触碰**（`git status` 可核：本轮改动只落在下述文件）。

---

## ① 一句话结论

**K-02/K-03/K-04/K-05 已改完并取得实测证据，K-01 已取得实测证据（判定为"脚本用法/别名冲突"，非缺陷）；S02 只补齐了测试隔离维度，宿主 3306 正式库在本轮前后指纹逐字段一致（零写入）。**

---

## ② 四级状态（分别判定，互不替代）

| 级别 | 状态 | 依据 |
|---|---|---|
| **提交完成** | ✅ 完成 | 19 个既有文件已改 + 1 个新目录（`docs/acceptance/v25-s02-k01k05-20260914/`），全部为显式路径，无 `git add -A`；未推送 |
| **测试通过** | ✅ 通过 | 默认档 `mvn test`：mall **0F/0E/0S**（8 tests）、generator **0F/0E/0S**（101 tests），退出码均 0；隔离档在缺隔离档案时**硬失败**（30 errors / 19 errors），即"隔离不成立就不给绿" |
| **限定验收** | ✅ 达成 | 五个问题的**门禁行为**全部有实测矩阵：K-01 四模式退出码、K-02 双档对照、K-03 单测前后对照、K-04 A～G 七用例全 PASS、K-05 四用例退出码 5/5/5/6 全 PASS；宿主 3306 前后指纹一致 |
| **完整验收** | ❌ **未达成** | ① 未做真实端到端链路跑（未启动三程序、未跑一次真实 `pipeline-runs`）；② S02 隔离档案仍是"人工粘贴"，未实现 `-WriteProfiles` 自动落盘；③ 越界项（`spark-jobs/**` 算法、`warehouse/migrations/**`、3306 正式库）本轮一律未动，因此"整仓完整验收"无从谈起 |

> 说明：**"测试通过"不等于"完整验收"**。默认档变绿的一部分来源是"把 12 个真实依赖 DB/Spring 上下文的测试类从默认档**排除**"（见 ③ K-02），这是**排除**而非删除/跳过；隔离档证明这些类在隔离档案缺失时仍会硬失败，所以不是"删判据换绿"。

---

## ③ 逐项：现象 → 根因（`file:line`）→ 改动 → 实测命令与退出码 → 剩余边界

### K-01　`it-prepare-isolation.ps1` 参数/退出码行为取证

**现象**
- 用 `-Host 127.0.0.1` 调用 `scripts/it-prepare-isolation.ps1` 时抛
  `WriteError: 无法覆盖变量 Host ，因为它是只读变量或常量。`，而且**调用方仍得到退出码 0**，"失败了却是绿的"。

**根因（`file:line`）**
- `scripts/it-prepare-isolation.ps1` 的 `param` 里给了 `-Host` 这个**别名**，与 PowerShell 的**只读自动变量** `$Host` 同名 → 参数绑定阶段就写 `$Host`，必然抛错。（探针原文：`raw/k01-p1-file-host-param.txt`、`raw/k01-p2-command-host-param.txt`）
- 退出码被吞的机制：`pwsh -Command "& script.ps1 …"` 这一层**不传递内层脚本的退出码**，外层 `pwsh` 自身以 0 退出（`raw/k01-p5-command-noexit.txt`、`raw/k01-p4-command-exit0-after-error.txt`）。
- 该缺陷属**脚本用法/调用方式**层面：脚本自身的退出码契约是对的（缺参=1、参数形状非法=2），错的是"用 `-Host` 这个别名"和"用 `-Command` 包装并据此判定成败"。

**改动**
- **未改脚本代码**。K-01 的交付物是**行为取证**。直接在脚本里删掉 `Host` 别名会破坏既有调用方（越界修改），因此本轮只登记用法契约，把"用 `-DbHost`/`-HostName`、用 `pwsh -File`"写成硬约束。

**实测命令与退出码**

| 用例 | 命令 | 退出码 | 输出行数 | 证据 |
|---|---|---|---|---|
| A 正常 | `pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId k01probe-20260914-a1b2` | **0** | 24 | `raw/k01-a-normal-file.log` |
| B 缺 `-RunId` | 同上，不带 `-RunId` | **1** | 1 | `raw/k01-b-missing-runid.log` |
| C `-RunId` 形状非法 | 同上，`-RunId ab` | **2** | 2 | `raw/k01-c-bad-runid.log` |
| D 未知参数 | 同上，`-NoSuchSwitch` | **1** | 1 | `raw/k01-d-unknown-param.log` |
| E `-Command` + `-File` | `pwsh -NoProfile -Command "& { pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId … }"` | **0** | 24 | `raw/k01-e-command-file.log` |
| F `-Command` + 点源 | `pwsh -Command "& { . scripts/it-prepare-isolation.ps1 -RunId … }"` | **0** | 24 | `raw/k01-f-command-dotsource.log` |
| G `-Command` + `-Host` | `pwsh -Command "& { … -Host 127.0.0.1 … }"` | **0** ⚠️ | 24 | `raw/k01-g-command-hostalias.log` |
| H `-File` + `-Host` | `pwsh -File … -Host 127.0.0.1` | **0** ⚠️ | 24 | `raw/k01-h-file-hostalias.log` |
| I `-Command` + `-File` + `-Host` | 组合 | **0** ⚠️ | 24 | `raw/k01-i-command-file-flag.log` |

- ⚠️ = 内层确实抛了 `WriteError`（原文见 `raw/k01-p1-file-host-param.txt` … `raw/k01-p5-command-noexit.txt`），但外层仍是 0。**这就是"退出码 0 不可信"的直接证据。**
- 机器可读汇总：`raw/k01-exitcode-matrix.json`（`-File` 四模式）、`raw/k01-exitcode-matrix-command.json`（`-Command` 五模式）。

**剩余边界**
- `-Host` 别名冲突**未修**（留给总控裁决：是删别名还是保留并只写文档）。
- 结论只覆盖 `it-prepare-isolation.ps1` 一个脚本；仓库内其它脚本是否存在同型"别名撞自动变量"，本轮**未普查**（列入 ④ 未取证清单）。

---

### K-02　默认 `mvn test` 会打真库 → 必须默认不打真库；隔离档必须硬失败

**现象**
- 默认 `mvn test` 直接跑依赖真实 MySQL/Spring 上下文的测试，读的是正式库口径；且为了让它变绿存在"跳过/删除判据"的诱因。
- 只把 `-Dgroups=it` 加到命令行**不成立**：Maven 用户属性会覆盖插件配置，实测 `Tests run: 0 / BUILD SUCCESS`——**假绿**。

**根因（`file:line`）**
- `mall-simulator/pom.xml` 与 `synthetic-data-generator/pom.xml` 的 Surefire 配置里没有分组排除项，默认档会把全部测试（含需 DB 的）纳入。
- 12 个测试类直接依赖真实数据库/Spring 上下文，但**没有任何 `@Tag`**，无法按分组表达"这是集成级"。

**改动**
1. **两个 pom 各新增一个属性**（默认档用它排除分组，隔离档把它清空）：
   - `mall-simulator/pom.xml`：`<properties>` 内新增 `<v25.it.excluded.groups>it</v25.it.excluded.groups>`；Surefire `<configuration><excludedGroups>${v25.it.excluded.groups}</excludedGroups></configuration>`；新增 `isolated-tests` profile：`<properties><v25.it.excluded.groups></v25.it.excluded.groups></properties>` + Surefire `<groups>it</groups>`。
   - `synthetic-data-generator/pom.xml`：同上（属性并入**已有**的 `<properties>` 块）。
   - 踩坑记录：两次编辑都先产出 `Duplicated tag: 'properties'`，已修正；现已无重复标签。
2. **12 个测试类加 `@Tag("it")`**（仅是**分组标注**，未删、未 `@Disabled`、未跳过）：
   - mall-simulator（9）：`auth/AdminProductApiTest`、`auth/AuthHttpTest`、`auth/AuthServiceTest`、`auth/UserAdminServiceTest`、`controller/OutboxControllerTest`、`domain/service/MallBusinessServiceTest`、`domain/service/OutboxTransactionTest`、`outbox/EventOutboxServiceTest`、`outbox/OutboxPublisherTest`
   - synthetic-data-generator（3）：`meta/GeneratorMetaStoreTest`、`web/GeneratorApiSmokeTest`、`web/MallApiGenerationSmokeTest`
   - （尚未提交的历史层）`src/test/resources/application-test.yml` 早已改为 `${MALL_ISOLATION_URL}` / `${MALL_ISOLATION_USER}` / `${MALL_ISOLATION_PASSWORD}` / `flyway.enabled: ${MALL_ISOLATION_FLYWAY_ENABLED:false}`。
3. **新增证据工具**（为什么新建而不是改既有：见 ⑤ 的新增文件理由）：
   - `tools/mvn-isolated.cmd`：在**自己的命令行**上设 `set MAVEN_ARGS=-Pisolated-tests`，再 `call "D:\apache-maven-3.9.14\bin\mvn.cmd" %*`，`exit /b %ERRORLEVEL%`。**必须 ASCII-only**（`.cmd` 里的中文注释在 GBK 控制台代码页下会被当命令执行，实测报 `'传入的命令。' is not recognized…`）。
   - `verify-k02.ps1`：三档 driver（`default` / `isolated-hard-reject` / `isolated-run`），每模块先删 `target/surefire-reports`（杀掉陈旧绿灯），解析 surefire XML 出 per-class JSON + summary。

**实测命令与退出码**

默认档（`-Phase default`，driver 内部命令：
`"D:\apache-maven-3.9.14\bin\mvn.cmd" -o '-Dmaven.repo.local=D:\maven_repository' -f <module>/pom.xml test`）：

| 模块 | 退出码 | classes | tests | failures | errors | skipped | 证据 |
|---|---|---|---|---|---|---|---|
| `mall-simulator` | **0** | 2 | 8 | 0 | 0 | 0 | `raw/k02-confirm-default-mall-simulator.log` + `-perclass.json` |
| `synthetic-data-generator` | **0** | 16 | 101 | 0 | 0 | 0 | `raw/k02-confirm-default-synthetic-data-generator.log` + `-perclass.json` |

隔离档但**故意不给隔离档案**（`-Phase isolated-hard-reject`）——必须硬失败：

| 模块 | 退出码 | classes | tests | failures | errors | skipped | 证据 |
|---|---|---|---|---|---|---|---|
| `mall-simulator` | **1** | 9 | 30 | 0 | **30** | 0 | `raw/k02-after-hard-reject-mall-simulator.log`（863 KB 堆栈）+ `-perclass.json` |
| `synthetic-data-generator` | **1** | 3 | 19 | 0 | **19** | 0 | `raw/k02-after-hard-reject-synthetic-data-generator.log`（384 KB）+ `-perclass.json` |

- **`skipped = 0` 是关键数字**：说明这是"真实失败"而不是"被 skip 掉的绿"。
- 改动**前**基线（同一 driver，未加 `@Tag`/属性时）：mall 38 tests / **30 errors**，generator 120 tests / **20 errors**，两模块退出码均 1 —— 证据 `raw/k02-baseline-zerotest-summary.json`、`raw/k02-baseline-zerotest-mall-errormessages.txt`。（文件名里的 `zerotest` 是指"当时无法只跑目标分组"，原始错误消息已留档。）
- 汇总 JSON：`raw/k02-confirm-default-summary.json`、`raw/k02-after-hard-reject-summary.json`。

**修 `-P` 传递的取证**（这是本轮最硬的一处排障）
- 现象：driver 里传 `-Pisolated-tests` 时 Maven 报 `[ERROR] Unknown lifecycle phase "-"`。
- 四轮排查（原始探针文件已删除，结论见 `verify-k02.ps1` 文件头注释）：(a) `pwsh -File x.ps1 -Pisolated-tests` → 内层 mvn 拿到 `-P` + `isolated-tests`（被拆成两个 argv）；(b) 脚本内 splat `@profArgs` 或字面量 `'-Pisolated-tests'` → Maven 收到 `-` + `Pisolated-tests` → 报未知生命周期阶段；(c) **同一个脚本不带 `-P` 时正常**（DEBUG-C：退出 0 / 8 tests 绿）→ 证明故障在 pwsh→`.cmd` 这一层 argv 编组，不在被测代码；(d) `:test -Dgroups=it` → `Tests run: 0 / BUILD SUCCESS`（假绿，见上）。
- 结论：`-P` **不能**穿过 `pwsh → mvn.cmd` 边界；改为在 `.cmd` 内部设 `MAVEN_ARGS`，driver **不再自己传 `-P`**（两边都传会重复标志再次失败）。

**剩余边界**
- 默认档的 classes 从 11→2（mall）、19→16（generator）：这是"把 9+3 个集成级类**排除**"，不是删除；这些类仍存在于树中并被 `isolated-tests` profile 纳入。
- `isolated-tests` 档的**全绿**路径本轮**未取证**：它需要隔离档案与 `credref:` 凭据引用真正落盘，而这一步目前靠人工粘贴（见 S02 剩余边界）。已取证的是"缺档案必硬失败"这一半，这半边正是防假绿的关键。
- 只覆盖 `mall-simulator` / `synthetic-data-generator` 两个模块；`analytics-server` 是否有同型测试未纳入本轮（列入 ④）。

---

### K-03　契约测试自我违背契约（`GeneratorContractParityTest`）

**现象**
- `GeneratorContractParityTest` 失败 1 项：它把一个**实现常量**当成契约条款来断言，等于"用实现反证契约"。

**根因（`file:line`）**
- 断言把 `mock-mall`（`ContractFormat.SOURCE_SYSTEM` 的实现取值）写成契约要求，并断言 schema 里 `source` 存在 `const`；但契约 schema 的 `source` 在提交 `5690ffb` 中**已按设计去掉 `const`**（`contract-specs/schemas/canonical-event.v1.schema.json:38-42` 为 `source`，`:44` 为 `schema_version` 的 `const: 1.0`）。
- 旧断言把"实现细节 + 已废弃的 schema 形状"绑死，任何一边正当演进都会互相打架。

**改动**
- `synthetic-data-generator/src/test/java/com/graduation/generator/contract/GeneratorContractParityTest.java`：测试重命名为 `versionSourceShapeAndPatternsMatchContract`，断言改为**只对齐契约里真实存在的东西**：
  - `ContractFormat.SCHEMA_VERSION == schema.properties.schema_version.const`；
  - `source.has("const")` **为假**（D-061：`source` 不再是 const）；
  - `source.type == "string"`、`source.minLength == 1`；
  - `ContractFormat.SOURCE_SYSTEM` **非空**；
  - envelope 的 `source_system == ContractFormat.SOURCE_SYSTEM` 且 `!= SCHEMA_VERSION`；
  - `AMOUNT` / `ISO8601` 两条正则与 `$defs` 对齐。
- `synthetic-data-generator/src/main/java/com/graduation/generator/contract/ContractFormat.java:35-44`：**仅改 Javadoc**（说明 `SOURCE_SYSTEM` 是实现取值、不是契约条款）。常量 `SOURCE_SYSTEM = "mock-mall"` **未改**。

**实测命令与退出码**
- 改动前：`GeneratorContractParityTest` 1 error（基线证据沿用 K-02 的 `raw/k02-baseline-zerotest-synthetic-data-generator-perclass.json`）。
- 改动后（同一 mvn 命令，只跑该类）：**退出码 0 / 7 tests / 0F 0E 0S / BUILD SUCCESS**，证据 `raw/k03-after-paritytest.log`。

**剩余边界**
- **不得**重新把 `mock-mall` 硬编成契约断言，**不得**给 `contract-specs/schemas/canonical-event.v1.schema.json` 的 `source` 加回 `const`。
- 只覆盖 canonical-event v1 一份 schema；其余 schema 的同类"实现反证契约"问题未普查（列入 ④）。

---

### K-04　`run-demo.ps1` 清场门禁（宿主 3306 只读，必须挡死）

**现象**
- 清场动作（删 `event_outbox`）以"商城自有库"为由，但**没有实例维度**的门禁：只要 `-MysqlPort 3306` 就能删到**宿主正式实例**的 `mall_simulator`。
- 失败路径还会说"已清场"（或静默继续），把"删失败"包装成"删成功"。

**根因（`file:line`）**
- 改动前 `scripts/run-demo.ps1` 的清场逻辑没有端口白名单、没有实例指纹校验、没有删除结果校验。
- 两个 MySQL 实例的 `@@hostname` **都是 `dahaishui`**，所以指纹必须带 **port + uuid + datadir** 才能区分（这是本项目最容易踩的坑）。

**改动（`scripts/run-demo.ps1`）**
- 新增/收紧参数：`:40` `[int]$MysqlPort = 3307`、`:41` `[string[]]$AllowedMysqlPorts = @(3307)`、`:42` `[string]$ExpectedServerUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47'`、`:43` `[string]$RunId = ''`、`:52` `[string[]]$AllowedCleanDbs = @('mall_simulator')`、`:53` `[switch]$ConfirmCleanTarget`。
- 门禁顺序（`[0.5]` 段）：
  1. 端口白名单 `:144-148`（`拒绝清场：端口 {0} …（白名单 …）`，`:146`）；
  2. 库名必须带本次 `RunId` 前缀 `:149-151`（`:150`）；
  3. 库名白名单 `:152-154`（`:153` `拒绝清场：目标库 '{0}' 不在商城自有库白名单 {1} 内。`）；
  4. 账号不得为 `root` `:155-157`（`:156`）；
  5. 实例指纹必须等于登记的 3307 实例 `:160-167`（只读探针 `SELECT CONCAT(@@port,'|',@@server_uuid,'|',@@datadir,'|',@@hostname)`，`Invoke-FingerprintProbe` 在 `:139-142`）；
  6. 预览行数 `:173-175`；
  7. 真正 DML `:180-187`，成功 `:185` `[0.5] 已清商城 event_outbox：删除 {0} 行…`，失败 `:182` `清 event_outbox **失败**`。
- `$cleanTargetOk` 同时闸住落地目录清理 `:191`（仅 repo 根 `landing\events` 下 2 小时内的 `*.jsonl`）。
- 指纹探针用 `$gateCon`（`--protocol=TCP`），清场句柄用 `$cleanCon`（`--host=127.0.0.1 --port=<n> -u<user>`），口令走 `$env:MYSQL_PWD`，**不上命令行、不回显**。

**实测命令与退出码**
- 驱动：`pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k04.ps1`
- 方法：`tools/mysql-stub.cmd` 冒充 mysql 客户端（**不建立任何连接**），记录每次调用的 argv 与 `MYSQL_PWD=<set|unset>`；`tools/stub-web-server.ps1` 提供登录/就绪类 HTTP 应答。因此这批证据的合法边界是：**(1) 退出码/裁决文本、(2) stub 被调用的次数与 argv**，不是真实 SQL 效果。

| 用例 | 期望 | 退出码 | stub 调用数 | 断言失败数 | 证据 |
|---|---|---|---|---|---|
| A 端口 3306 拒绝 | 拒绝，0 次调用 | 0 | 0 | 0 | `raw/k04-a-refuse-host3306.txt` |
| B 库名未带 runId 前缀 | 拒绝，0 次调用 | 0 | 0 | 0 | `raw/k04-b-refuse-no-runid-prefix.txt` |
| C 未提供口令 | 跳过清场，0 次调用 | 0 | 0 | 0 | `raw/k04-c-skip-no-password.txt` |
| D 库名不在白名单 | 拒绝，0 次调用 | 0 | 0 | 0 | `raw/k04-d-refuse-db-not-in-whitelist.txt` |
| E 指纹不符（uuid 全 0） | 拒绝；**仅 1 次只读探针、无 DML** | 0 | 1 | 0 | `raw/k04-e-refuse-fingerprint-mismatch.txt` |
| F 删除语句失败 | 如实报失败，**不得称"已清场"** | 0 | 3 | 0 | `raw/k04-f-delete-fails.txt` |
| G 全绿（隔离实例） | 放行；指纹+预览+删除 3 次调用，DML 带 `--port 3307` | 0 | 3 | 0 | `raw/k04-g-happy-isolated.txt` |

- 七个用例**全部 PASS**，机器可读矩阵 `raw/k04-gate-matrix.json`（含 `failedAssertions` 列）。F 的 3 次调用是"指纹 / 预览 / 删除"，F 用例断言里同时有 `!已清商城 event_outbox`（禁止谎报）。
- 排障留档：v1 驱动发现 **R-6 白名单与 K-04 runId 前缀要求互斥**（`mall_simulator` 永远不可能带 runId 前缀）→ 新增 `-AllowedCleanDbs` 作为显式扩展口；v3/v4 修掉 `.cmd` 中文注释被当命令、`$Args` 参数歧义、env 不继承（stub 日志改固定路径 `%TEMP%\k04-stub-invocations.tsv`）。

**剩余边界（两处有意的行为变更，需总控确认）**
1. `-AllowedCleanDbs` 是对外可扩展的白名单：门禁①（实例指纹）与门禁②（runId 前缀）**仍然强制**，但"库名白名单"不再恒等于 `mall_simulator`。
2. `run-demo.ps1` 的演示态清场对 **3306 / `mall_simulator` 变成原理上不可能**（设计如此）。
3. `scripts/start-all.ps1` 同步整改：`:19` 原为 `$MallDbPassword = $(if ($env:MALL_DB_PASSWORD) { $env:MALL_DB_PASSWORD } else { '123456' })`，现改为 `[string]$MallDbPassword = ''` + `:23` 仅从 `$env:MALL_DB_PASSWORD` 继承；`:75-80` 在需要启动商城却无口令时**拒绝启动并 `exit 4`**，不拉起任何进程（原 `:14` 注释里的"默认取本机 LOCAL 演示口令"已删）。**该 `exit 4` 分支尚未实测**（列入 ④）。

---

### K-05　`smoke-pipeline.ps1` 只读取数失败必须立即响亮失败

**现象**
- 只读查询失败时，`Q()` 只打印一行诊断然后**照常返回**，于是：
  - `[long](Q '…' | Select-Object -First 1)` 把**空流变成 0**（基线被读成 0）；
  - `$blockingFail = @($rules | Where-Object …)` 在 `$rules` 为空时得到 **0 条** ⇒ **质量门禁把"查不到"当成"全过"**，脚本继续走完并可能给出绿。
- 这是"删掉/跳过判据换绿色"的同型缺陷，只是换成了"**读不到就不判**"。

**根因（`file:line`）**
- `scripts/smoke-pipeline.ps1` 的 `Q()`（`≈:100`）在 mysql 退出码非 0 时未 `throw`；下游三处调用点（基线 `MAX(id)`、总行数、规则集）没有"取数失败"这一状态。

**改动（`scripts/smoke-pipeline.ps1`）**
- 新增 `class SmokeQueryException`：携带 `MysqlExit` / `RawError` / `Target` 三个字段（`:113` 起）。
- `Q()` 失败即 `throw`，异常里保留 **mysql 原始退出码 + 原始 stderr 文本 + 目标库/账号/SQL**。
- 外层 `Invoke-SafeQuery` fail-fast 包装：把该异常翻译成**退出码 6** 并**立即停止**——不再聚合、不再打印任何门禁结论；返回值用 `,$result` 避免单元素数组被展开成标量（这是 PowerShell 的经典坑）。
- 全部调用点转换（`≈:137,:138,:149,:216-218,:228,:237,:242`）；两处解析自检也从 `exit 5` 改成 `exit 6`。
- 退出码语义写进文件头 `:32-33`：`5 = 目标/凭据在执行前就不明确`，`6 = 执行中只读取数真的失败`。
- 顺带在 `:26-30` 记录：`-MetricDb` 是白名单（默认只允许 `analytics_metric` / `analytics_metric_v25it`），口令**无默认值**（默认账号 `metric_read`，`root` 直接拒绝）。

**实测命令与退出码**
- 驱动：`pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k05.ps1`
- 方法：同样用 `tools/mysql-stub.cmd`（`K04_STUB_MODE=fail` / `K04_STUB_MSG` 注入确定性失败）+ `tools/stub-web-server.ps1`（让流程走到"记录基线"那一步）。合法证据边界同 K-04。

| 用例 | 期望 | 实测退出码 | stub 调用数 | 断言失败数 | 证据 |
|---|---|---|---|---|---|
| A 未提供任何口令 | 执行前拒绝 | **5** | 0 | 0 | `raw/k05-a-refuse-no-password.txt` |
| B `-MetricDb` 不在白名单 | 执行前拒绝 | **5** | 0 | 0 | `raw/k05-b-refuse-db-not-in-whitelist.txt` |
| C 账号为 `root` | 执行前拒绝 | **5** | 0 | 0 | `raw/k05-c-refuse-root-account.txt` |
| D 凭据齐备但取数真的失败 | **立即 `exit 6`**，保留原始退出码与原文，且**不再打印任何门禁结论** | **6** | 1 | 0 | `raw/k05-d-query-fails.txt` |

- 四用例**全部 PASS**；矩阵 `raw/k05-exitcode-matrix.json`，driver 全程控制台 `raw/k05-driver-console.txt`。
- D 的原始输出（`raw/k05-d-query-fails.txt` 尾部）逐字保留：
  ```
  [FAIL] 只读取数失败，立即停止（不继续聚合、不给门禁结论）。
         阶段      : 基线 pipeline_run 最大 id
         mysql 退出 : 7
         原始文本   : ERROR 2013 (HY000): Lost connection to MySQL server during query (stub)
         目标       : MetricDb=analytics_metric 账号=metric_read sql=SELECT COALESCE(MAX(id),0) FROM analytics_meta.pipeline_run
         退出码 6 = 只读取数失败/结果不可解析（V25-S02/K-05）。
  ```
  同时断言 `!阶段状态`（没走到阶段汇总）、`!已发布`、`!BLOCKING`、`!证据`（没落盘"链路结论"证据）——即**失败后确实没有继续聚合**。
- 注入的 stub 退出码故意设为 `7`（不是 1/2），用来证明脚本**原样透传**了客户端退出码，而不是自己编了一个。

**剩余边界**
- **A/B/C 的"零调用"是靠 stub 日志为空证明的**，不是靠代码审查；真实 mysql 客户端的"拒绝前不连接"未单独取证。
- `exit 6` 是 L7 自定的语义（原文档只写 2/3/4/5）→ 需总控确认（见 §待裁决）。
- 只读了 `analytics_meta` / `analytics_metric` 两个库；`spark-jobs` 侧读失败的同型问题未纳入本轮（列入 ④）。

---

### S02　测试隔离配置（V25-S02，`docs/design/项目设计文档 V2.3.md:896`）

**S02 约束（必须遵守）**：补缺**只能**动测试隔离配置及其脚本；**生产数据源语义一律不得改**。
因此以下"未整改"是**合规的未整改**，不是遗漏：
- `synthetic-data-generator/src/main/resources/application.yml`：3306 / `generator_meta` / `${GENERATOR_DB_USER:root}` / `${GENERATOR_DB_PASSWORD:123456}` / flyway enabled —— **保持原样**。
- `platform-app/src/main/resources/application.yml:7,15,19`：3306 URL 可被环境变量覆盖 —— **保持原样**。

**隔离维度清单（逐个标注"已隔离 / 硬编或默认"）**

| 维度 | 现状 | 位置 | 判定 |
|---|---|---|---|
| **实例（server）** | 隔离实例固定 3307，`run-demo.ps1` 默认 `$MysqlPort=3307`、白名单 `@(3307)`、期望 uuid `de8ebbea-aff4-11f1-8037-00155d5dba47` | `scripts/run-demo.ps1:40,41,42` | ✅ 已隔离（且指纹强制） |
| **实例指纹口径** | 必须 `port\|uuid\|datadir\|hostname` 四段；两实例 `@@hostname` 同为 `dahaishui`，只比 hostname 会误判 | `scripts/run-demo.ps1:161`；`docs/acceptance/.../fingerprint-3306-readonly.sql` | ✅ 已隔离 |
| **库（database）** | 隔离库名必须是本次 runId 命名（`<runId>_mall` / `<runId>_generator`）；宿主正式库 `mall_simulator` 只在显式扩展白名单时可见 | `scripts/run-demo.ps1:43,52`；`scripts/it-prepare-isolation.ps1` | ✅ 已隔离 |
| **账号** | 本库受限账号 `<runId>_mallapp` / `<runId>_genapp`，`root` 被显式拒绝 | `scripts/run-demo.ps1:155-157`；`scripts/it-prepare-isolation.ps1` | ✅ 已隔离 |
| **凭据传递** | 口令走 `$env:MYSQL_PWD`，不进 argv、不回显；隔离配置只放 `credref:<id>` 引用 | `scripts/run-demo.ps1:159`；`scripts/it-prepare-isolation.ps1:144-155,203,212` | ✅ 已隔离 |
| **Spring 测试数据源** | `${MALL_ISOLATION_URL}` / `${MALL_ISOLATION_USER}` / `${MALL_ISOLATION_PASSWORD}`，`flyway.enabled: ${MALL_ISOLATION_FLYWAY_ENABLED:false}`（默认 **关**，防隔离档误迁移） | `mall-simulator/src/test/resources/application-test.yml`（71 行） | ✅ 已隔离 |
| **生成器隔离门禁档案** | classpath 档案只有 `enabled=false` + 注释模板（键名 `runId/url/user/password/instancePorts`） | `synthetic-data-generator/src/test/resources/it-guard.local.properties` | ⚠️ **默认关**；开启需人工粘贴（见下） |
| **测试分组** | 默认档 `<excludedGroups>` 排除 `it`；`isolated-tests` profile 清空该属性并 `<groups>it</groups>` | `mall-simulator/pom.xml`、`synthetic-data-generator/pom.xml` | ✅ 已隔离（本轮新增） |
| **命名空间** | 3307 上隔离库/账号一律带 runId 前缀；3306 上 `v25it_*` 库/账号必须为 0 | `docs/acceptance/.../fingerprint-3306-readonly.sql` | ✅ 已隔离（实测为 0） |
| **HDFS / checkpoint / snapshot 命名空间** | **未纳入本轮**：属 `spark-jobs/**` 与平台侧运行档，本轮不得触碰 | — | ❌ **未取证**（列入 ④） |
| **运行档（runtime_profile）** | 3306 上 `runtime_profile_v25it_rows = 0`、`runtime_profile_total_rows = 1`（隔离档未污染正式表） | `docs/acceptance/.../fingerprint-3306-readonly.sql` | ✅ 实测通过 |

**3306 正式库前后指纹（本轮最关键的"零写入"证据）**

- 脚本：`docs/acceptance/v25-s02-k01k05-20260914/fingerprint-3306.ps1 -Phase before|after`；只读 SQL 文件 sha256 = `73DD90C59EBB3F3ECE6EDEDF27A1C7C49AA6E49B52D5D25E9045C4DC0822A402`（前后一致）。
- 脚本**先断言**实例指纹以 `3306|85191145-1491-11f0-b4e2-60cf84d55629|` 开头，且**拒绝**任何非 `SET`/`SELECT` 语句——即"不可能误写正式库"。
- 两次实例指纹（完整四段）：`3306|85191145-1491-11f0-b4e2-60cf84d55629|C:\ProgramData\MySQL\MySQL Server 8.0\Data\|dahaishui`
- 逐字段对照（`Compare-Object`，排除 `phase`/时间戳/命令回显行后**零差异**）：

| 指标 | before（14:36:44） | after（15:07:30） |
|---|---|---|
| `metric_snapshot_rows` | 12 | 12 |
| `metric_value_rows` | 110 | 110 |
| `metric_snapshot_fp` | `c224e4e9d44c98f5389607f7beb9d6d2` | 同 |
| `metric_value_fp` | `a1a073114d8232c5fcb11c56157466c2` | 同 |
| `active_pointer` | `id=27 / S20260901_47 / version=12 / active_flag=1` | 同 |
| `flyway_metric_count` / latest | 3 / `3@2026-09-10 20:04:15` | 同 |
| `flyway_meta_count` / latest | 17 / `18@2026-09-12 15:06:05` | 同 |
| `v25it_databases_on_3306` | 0 | 0 |
| `v25it_accounts_on_3306` | 0 | 0 |
| `runtime_profile_v25it_rows` | 0 | 0 |
| `runtime_profile_total_rows` | 1 | 1 |

- 证据：`raw/s02-fingerprint-3306-before.txt` / `-after.txt`（+ 各自 `-console.txt`）。
- 3307 隔离实例本轮含：`analytics_meta`、`analytics_meta_v25it_20260914_1358_l4e3`、`analytics_metric`、`analytics_metric_v25it_20260914_1358_l4e3`、`mall_simulator`、`mysql`、`performance_schema`、`sys`（runId 命名的库已按隔离约定建立）。
- **本轮 3306 全程零写入**：所有临时改动只落 `$env:TEMP` 或仓内被 gitignore 的目录。

**剩余边界（S02 未闭环项）**
- `scripts/it-prepare-isolation.ps1:193-216` 目前**只打印**两份隔离档案片段（`mall-isolation.local.properties`、`it-guard.local.properties`），**没有** `-WriteProfiles` 开关自动落盘。本轮考虑过加"默认关"的 `-WriteProfiles`，**未实现**——原因：它要往模块工作目录写含 `credref:` 引用的文件，属"写凭据配置"，需总控先确认落盘路径与 gitignore 覆盖；宁可保留人工粘贴步并**如实登记**，也不做未经确认的自动写盘。
- 因为上一条，**`isolated-tests` 档的"全绿"未取证**（缺档案 → 硬失败，这半边已取证）。
- 生产侧默认值（generator `application.yml` 的 3306/root/123456；platform-app 的 3306 可覆盖 URL）按 S02 约束**保持原样**，登记为**待总控裁决的残留风险**。
- HDFS / Spark checkpoint / snapshot 命名空间未纳入（越界）。

---

## ④ 未取证清单（逐条说明"为什么没测"）

| # | 未取证项 | 为什么没测 |
|---|---|---|
| 1 | 真实端到端链路（启动三程序 + 真跑一次 `pipeline-runs` 到 `PUBLISH`） | K-06～K-09 属其它泳道，启动三程序会占用 8090/8091/8092 并触发平台侧对正式库的读写，越界；本轮只做门禁行为与单模块测试 |
| 2 | `isolated-tests` 档**全绿** | 需要隔离档案 + `credref:` 凭据真正落盘，而落盘工具（`-WriteProfiles`）未实现（见 S02 剩余边界）；已取证的是其反面"缺档案必硬失败" |
| 3 | `scripts/start-all.ps1` 新 `exit 4`（无口令拒绝启动）分支 | 需真实启动商城进程才能走到；本轮不启动三程序，且该分支要拉起 JVM。代码路径已读，**运行结果未取** |
| 4 | 真实 mysql 客户端"拒绝前零连接" | 用 stub 证明零调用（stub 日志为空）；真实客户端的连接层行为未单独抓包/查 `performance_schema` |
| 5 | HDFS / Spark checkpoint / snapshot 命名空间隔离 | 属 `spark-jobs/**`，本轮明令不得触碰 |
| 6 | `analytics-server` 模块的同类测试分组问题 | K-02 范围只列 `mall-simulator` / `synthetic-data-generator`；`analytics-server` 未纳入 |
| 7 | 仓库内其它脚本"参数别名撞 PowerShell 自动变量"的普查 | 只取证了 `it-prepare-isolation.ps1 -Host` 一例 |
| 8 | 其余 schema 的"实现反证契约"同类问题 | K-03 只覆盖 `canonical-event.v1` |
| 9 | `spark-jobs` 侧读失败（无 `Rows`/异常吞掉）的同型问题 | 越界，未触碰 |
| 10 | `run-demo.ps1` 落地目录清理（`landing\events`，2h 内 `*.jsonl`）的真实删除行为 | 会真删共享目录里的文件；本轮只用 stub 验证门禁判定，未做真实删除 |
| 11 | 3307 隔离实例上的真实 DDL/DML 效果 | 本轮不向任何实例写数据（K-04/K-05 全用 stub）；保持"读证据"口径 |
| 12 | `scripts/accept-p1-baseline.ps1:27-28`（`root`/`123456`）、`scripts/accept-three-programs.ps1:9`（`123456`）明文兜底 | **越界**：属其它泳道脚本，本轮只登记为发现项 |

---

## ⑤ 本轮新增/修改文件完整路径清单

### 新增（`docs/acceptance/v25-s02-k01k05-20260914/`）

| 路径 | 用途 |
|---|---|
| `docs/acceptance/v25-s02-k01k05-20260914/README.md` | 本文件（六段式验收记录） |
| `docs/acceptance/v25-s02-k01k05-20260914/verify-k02.ps1` | K-02 三档 driver（default / isolated-hard-reject / isolated-run），删陈旧 surefire 报告 → 跑 mvn → 解析 per-class/summary |
| `docs/acceptance/v25-s02-k01k05-20260914/verify-k04.ps1` | K-04 A～G 门禁用例 driver（断言 DSL：`stubCalls=n`、裸文本、`!text`、`s:argv`、`!s:argv`） |
| `docs/acceptance/v25-s02-k01k05-20260914/verify-k05.ps1` | K-05 四用例退出码矩阵 driver |
| `docs/acceptance/v25-s02-k01k05-20260914/fingerprint-3306.ps1` | 3306 只读指纹采集（`-Phase before\|after`），先断言实例身份、拒绝非只读语句 |
| `docs/acceptance/v25-s02-k01k05-20260914/fingerprint-3306-readonly.sql` | 纯 `SET`/`SELECT` 指纹 SQL（sha256 `73DD90C5…A402`） |
| `docs/acceptance/v25-s02-k01k05-20260914/tools/mvn-isolated.cmd` | K-02 用：在自身命令行设 `MAVEN_ARGS=-Pisolated-tests` 再 `call mvn.cmd %*`（绕开 pwsh→`.cmd` 的 `-P` argv 编组缺陷）；ASCII-only |
| `docs/acceptance/v25-s02-k01k05-20260914/tools/mysql-stub.cmd` | 假 mysql 客户端（argv 走临时文件交接）；ASCII-only |
| `docs/acceptance/v25-s02-k01k05-20260914/tools/mysql-stub.ps1` | 假 mysql 客户端实现：不建连接，记录 argv + `MYSQL_PWD=<set\|unset>`，按 `K04_STUB_*` 注入确定性结果 |
| `docs/acceptance/v25-s02-k01k05-20260914/tools/stub-web-server.ps1` | `HttpListener` 替身平台（登录/outbox/pipeline-runs/ingestion），让 K-04/K-05 能走到待测代码点 |

**为什么新建而不是改既有脚本**：既有 `scripts/*` 是被验收对象，**不能既当被测物又当量具**；证据工具必须与被测脚本解耦、可单独审计、可重复运行。此外 `tools/mvn-isolated.cmd` 与 `tools/mysql-stub.cmd` 必须 ASCII-only（中文会让 cmd 在被测解释器里当命令执行），这一约束不适合塞进既有脚本。

`raw/` 下全部原始证据（命令、退出码、mvn/mysql 原始日志、前后指纹）：

```
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-a-normal-file.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-b-missing-runid.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-c-bad-runid.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-d-unknown-param.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-e-command-file.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-f-command-dotsource.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-g-command-hostalias.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-h-file-hostalias.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-i-command-file-flag.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-p1-file-host-param.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-p2-command-host-param.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-p3-command-swallowed.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-p4-command-exit0-after-error.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-p5-command-noexit.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-exitcode-matrix.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k01-exitcode-matrix-command.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-summary.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-driver.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-mall-errormessages.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-mall-simulator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-mall-simulator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-synthetic-data-generator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-baseline-zerotest-synthetic-data-generator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-confirm-default-summary.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-confirm-default-mall-simulator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-confirm-default-mall-simulator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-confirm-default-synthetic-data-generator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-confirm-default-synthetic-data-generator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-default-summary.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-default-mall-simulator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-default-mall-simulator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-default-synthetic-data-generator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-default-synthetic-data-generator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-hard-reject-summary.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-hard-reject-mall-simulator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-hard-reject-mall-simulator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-hard-reject-synthetic-data-generator.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k02-after-hard-reject-synthetic-data-generator-perclass.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k03-after-paritytest.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-a-refuse-host3306.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-b-refuse-no-runid-prefix.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-c-skip-no-password.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-d-refuse-db-not-in-whitelist.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-e-refuse-fingerprint-mismatch.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-f-delete-fails.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-g-happy-isolated.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-gate-matrix.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-stub-http-server.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k04-stub-http-server.log.err
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-a-refuse-no-password.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-b-refuse-db-not-in-whitelist.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-c-refuse-root-account.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-d-query-fails.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-exitcode-matrix.json
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-driver-console.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-stub-http-server.log
docs/acceptance/v25-s02-k01k05-20260914/raw/k05-stub-http-server.log.err
docs/acceptance/v25-s02-k01k05-20260914/raw/s02-fingerprint-3306-before.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/s02-fingerprint-3306-before-console.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/s02-fingerprint-3306-after.txt
docs/acceptance/v25-s02-k01k05-20260914/raw/s02-fingerprint-3306-after-console.txt
```

### 修改（既有文件，19 个）

```
mall-simulator/pom.xml
mall-simulator/src/test/java/com/graduation/mall/auth/AdminProductApiTest.java
mall-simulator/src/test/java/com/graduation/mall/auth/AuthHttpTest.java
mall-simulator/src/test/java/com/graduation/mall/auth/AuthServiceTest.java
mall-simulator/src/test/java/com/graduation/mall/auth/UserAdminServiceTest.java
mall-simulator/src/test/java/com/graduation/mall/controller/OutboxControllerTest.java
mall-simulator/src/test/java/com/graduation/mall/domain/service/MallBusinessServiceTest.java
mall-simulator/src/test/java/com/graduation/mall/domain/service/OutboxTransactionTest.java
mall-simulator/src/test/java/com/graduation/mall/outbox/EventOutboxServiceTest.java
mall-simulator/src/test/java/com/graduation/mall/outbox/OutboxPublisherTest.java
synthetic-data-generator/pom.xml
synthetic-data-generator/src/main/java/com/graduation/generator/contract/ContractFormat.java
synthetic-data-generator/src/test/java/com/graduation/generator/contract/GeneratorContractParityTest.java
synthetic-data-generator/src/test/java/com/graduation/generator/meta/GeneratorMetaStoreTest.java
synthetic-data-generator/src/test/java/com/graduation/generator/web/GeneratorApiSmokeTest.java
synthetic-data-generator/src/test/java/com/graduation/generator/web/MallApiGenerationSmokeTest.java
scripts/run-demo.ps1
scripts/smoke-pipeline.ps1
scripts/start-all.ps1
```

`git diff --stat HEAD`：19 files changed, 375 insertions(+), 55 deletions(-)。**未 `git add -A`，未 rebase/force push，未推送。**

---

## ⑥ 复现步骤

**前置**：JDK17 `D:\Develop\JAVA17`；Maven `D:\apache-maven-3.9.14\bin\mvn.cmd`（离线 `-o`，本地仓 `D:\maven_repository`）；mysql 客户端 `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`；工作目录 `D:\Develop_code\GraduationProject`。**全仓同时只允许一个 Maven 进程。**

```powershell
# ── K-01：四模式 + 五模式退出码矩阵 ─────────────────────────────
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId k01probe-20260914-a1b2   # 0
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1                                  # 1
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId ab                        # 2
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId k01probe-20260914-a1b2 -NoSuchSwitch  # 1
pwsh -NoProfile -File scripts/it-prepare-isolation.ps1 -RunId k01probe-20260914-a1b2 -Host 127.0.0.1  # 内层 WriteError，外层 0 ← 陷阱
# 对照：-DbHost / -HostName 可以正常用

# ── K-02：默认档（必须 0F/0E/0S）──────────────────────────────
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k02.ps1 -Phase default
# 期望：mall-simulator 0 / 2 classes / 8 tests / 0F 0E 0S
#       synthetic-data-generator 0 / 16 classes / 101 tests / 0F 0E 0S

# ── K-02：隔离档但故意不给隔离档案（必须硬失败，且 skipped=0）──
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k02.ps1 -Phase isolated-hard-reject
# 期望：mall-simulator exit 1 / 30 errors；synthetic-data-generator exit 1 / 19 errors
# 直接跑单模块（等价底层命令）：
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' -f mall-simulator/pom.xml test
docs\acceptance\v25-s02-k01k05-20260914\tools\mvn-isolated.cmd -o '-Dmaven.repo.local=D:\maven_repository' -f mall-simulator/pom.xml test

# ── K-03：契约测试 ────────────────────────────────────────────
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' -f synthetic-data-generator/pom.xml test '-Dtest=GeneratorContractParityTest'
# 期望：exit 0 / 7 tests / 0F 0E 0S / BUILD SUCCESS

# ── K-04：清场门禁 A～G（用 stub，不连任何实例）───────────────
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k04.ps1
# 期望：7/7 `### case verdict: PASS`；矩阵 raw/k04-gate-matrix.json

# ── K-05：仅读取数失败矩阵（用 stub + 替身 HTTP）─────────────
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\verify-k05.ps1
# 期望：退出码 5 / 5 / 5 / 6，4/4 PASS；矩阵 raw/k05-exitcode-matrix.json

# ── S02：3306 正式库前后指纹（必须逐字段一致）────────────────
# 口令经 MYSQL_PWD 传入，不要写进命令行：
$env:MYSQL_PWD = '<3306 root 口令>'
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\fingerprint-3306.ps1 -Phase before
pwsh -NoProfile -File docs\acceptance\v25-s02-k01k05-20260914\fingerprint-3306.ps1 -Phase after
Compare-Object `
  ((Get-Content docs\acceptance\v25-s02-k01k05-20260914\raw\s02-fingerprint-3306-before.txt) | Where-Object { $_ -notmatch '^### (collected at|command|phase)' }) `
  ((Get-Content docs\acceptance\v25-s02-k01k05-20260914\raw\s02-fingerprint-3306-after.txt)  | Where-Object { $_ -notmatch '^### (collected at|command|phase)' })
# 期望：无输出（零差异）
```

**环境硬约束（复现时必须遵守）**
- **3306 宿主实例只读**（`@@server_uuid=85191145-1491-11f0-b4e2-60cf84d55629`、`@@datadir=C:\ProgramData\MySQL\MySQL Server 8.0\Data\`）；唯一合法写入目标是 WSL2 隔离实例 **3307**（`@@server_uuid=de8ebbea-aff4-11f1-8037-00155d5dba47`、`@@datadir=/data/mysql-isolated/data/`）。
- 两个实例的 `@@hostname` **都是 `dahaishui`** → 指纹**必须**带 **port + uuid + datadir**。
- 任何让 `platform-app` 连上 3306 的路径都必须**拒绝**（树里有 `V19`/`V20` 迁移，误启动一次＝对正式库做不可逆前向迁移）。
- 全仓**同时只允许一个 Maven 进程**；临时文件只放 `$env:TEMP` 或仓内 gitignore 目录。
- pwsh 里给 mvn 传本地仓必须加引号：`'-Dmaven.repo.local=D:\maven_repository'`（不加会报 `No plugin found for prefix '.repo.local=D'`）。
- 不在命令行上传口令：用 `$env:MYSQL_PWD` / `$env:MALL_DB_PASSWORD`。
