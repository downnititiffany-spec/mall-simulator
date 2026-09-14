# V25-S02 / K-01～K-05 泳道 L7 提交（`98393f0`）总控复核报告

日期：2026-09-14　复核人：总控（controller）　复核对象：`98393f0`（待推送，`origin/main = e632be1`）

本报告只做**复核**，不改写泳道原文。所有结论附总控自己的命令/输出，未实测项一律标注未取证。

---

## 1. 复核对象与四级状态

| 项 | 结论 | 依据 |
|---|---|---|
| 提交完成 | ✅ | `98393f0`：90 files changed, +10943 −55（19 改 / 71 新增），工作树干净，`未推=1` |
| 测试通过 | ✅（总控独立复跑） | 见 V-3：mall `exit 0 / Tests run: 8 / 0F 0E 0S / BUILD SUCCESS`；generator `exit 0 / 101 / 0F 0E 0S / BUILD SUCCESS` |
| 限定验收 | ✅ | 门禁行为在单测 + `mysql-stub` 层面全部有实测矩阵（V-8）；宿主 3306 前后状态一致（V-5、V-6） |
| 完整验收 | ❌ | 缺 ① 设计文档 V2.4 线性版本登记 ② F-88 写侧闭环 ③ S02 两项未闭环（见 §3 B-1/U-4） |

---

## 2. 总控独立取证

### V-1 范围与越界
- 逐条核对 `git show --numstat`：改动仅落在 `mall-simulator/`、`synthetic-data-generator/`、`scripts/`、`docs/acceptance/v25-s02-k01k05-20260914/`。
- **越界 0**：未触碰 `spark-jobs/`、`warehouse/migrations/`、`docs/毕业设计指导书 V2.6.md`、`docs/design/项目设计文档 V2.3.md`、V2.5 看板、`docs/contracts/`、`contract-specs/`。
- 新增 `@Tag("it")` 12 个类；**新增 `@Disabled`/`@Ignore` = 0**；**删除 `@Test`/`@ParameterizedTest` = 0**（未用"删测试/跳过"换绿色）。

### V-2 main 代码核对（自述 vs 事实）
- `synthetic-data-generator/src/main/java/com/graduation/generator/contract/ContractFormat.java` 是 main 代码，逐行看 diff：**仅 Javadoc 改动**，`public static final String SOURCE_SYSTEM = "mock-mall";` 是上下文行、未改。
- 结论：泳道自述「K-03 只改断言与 Javadoc；SOURCE_SYSTEM 未改」**成立**。

### V-3 默认档独立复跑（总控自己的命令）
```
mvn -o "-Dmaven.repo.local=D:\maven_repository" -f mall-simulator/pom.xml test
mvn -o "-Dmaven.repo.local=D:\maven_repository" -f synthetic-data-generator/pom.xml test
```
| 模块 | exit | Tests run | F / E / S | 结果 |
|---|---|---|---|---|
| mall-simulator | 0 | 8 | 0 / 0 / 0 | BUILD SUCCESS |
| synthetic-data-generator | 0 | 101 | 0 / 0 / 0 | BUILD SUCCESS |

日志：`raw/controller/mvn-mall-simulator-attempt2.log`、`raw/controller/mvn-synthetic-data-generator-attempt2.log`。
⇒ 与泳道记录的默认档数字**一致**（mall 8、generator 101，`skipped=0`）。

### V-4 总控自身两次无效复跑（如实登记，防止把无效证据当结论）
| 尝试 | 现象 | 性质 |
|---|---|---|
| ATTEMPT0 | `-pl mall-simulator,synthetic-data-generator` ⇒ `Could not find the selected project in the reactor: mall-simulator` | 这两个是**独立 pom**，不是根 reactor 模块；命令本身错 |
| ATTEMPT1 | `-Dmaven.repo.local=D:\maven_repository` 未加引号 ⇒ Maven 报 `No plugin found for prefix '.repo.local=D'` | 参数被 Maven 解析成插件前缀；命令本身错 |

留档：`raw/controller/mvn-reactor-ATTEMPT0-NOT-A-MODULE.log`、`mvn-*-ATTEMPT1-INVALID-ARGS.log`。
**两次都不是产品/泳道缺陷，也不计入"测试通过"的证据**；"测试通过"只由 V-3 的 attempt2 支撑。

### V-5 宿主 3306 只读独立取证（总控自己的 SQL）
```
3306 | @@server_uuid=85191145-1491-11f0-b4e2-60cf84d55629 | datadir=C:\ProgramData\MySQL\MySQL Server 8.0\Data\ | @@hostname=dahaishui
metric_snapshot ACTIVE : id=27 / S20260901_47 / version=12 / active_flag=1
analytics_meta.data_quality_result 4 新列(rule_version/effective_severity/compat_policy_version/rule_fingerprint) 存在数 = 0
analytics_meta.quality_rule_definition 表存在 = 0
analytics_meta.flyway_schema_history  CAST(version AS UNSIGNED) >= 18 : 1 行；version IN ('19','20') : 0 行
information_schema.schemata LIKE 'v25it%' = 0 ；analytics_meta.runtime_profile profile_code LIKE 'v25it-%' = 0
```
⇒ 3306 **未迁移**（V19/V20 未落）、**无本轮 runId 痕迹**、ACTIVE 指针未动。
留档：`raw/controller-3306-before-maven.txt`。

### V-6 独立复跑泳道自己的取证脚本
- 执行 `docs/acceptance/v25-s02-k01k05-20260914/fingerprint-3306.ps1 -Phase after -OutDir …\raw\controller`。
- 与泳道 `raw/s02-fingerprint-3306-after.txt` **逐字段一致**（排除 `### phase` / `### collected at`）。
- 留档：`raw/controller/s02-fingerprint-3306-after.txt`。
⇒ 泳道「3306 before/after 零差异」的采集**可复现**。

### V-7 门禁代码逐行核对
- `scripts/run-demo.ps1`：门序 = 实例端口白名单(`:144`) → runId 前缀(`:149`) → 库名白名单(`:152`) → 非 root(`:155`) → 实例指纹(`:160-168`) → 影响面预览(`:171-175`) → DML(`:180`)；清场连接串**显式** `--host=127.0.0.1 --port=$MysqlPort`（`:134`），不再吃 mysql 客户端 3306 默认值。
- 默认值：`-AllowedMysqlPorts = @(3307)`（`:41`）、`-ExpectedServerUuid = de8ebbea-aff4-11f1-8037-00155d5dba47`（`:42`）、`-AllowedCleanDbs = @('mall_simulator')`（`:52`）。
- `scripts/start-all.ps1`：明文口令兜底 `'123456'` 已删除（`:22-24`），无口令 ⇒ 提示 + `exit 4`（`:76-80`）。
- `scripts/smoke-pipeline.ps1`：退出码契约写入脚本头（`:32`）＝ `0/2/3/4/5/6`，并说明 5（执行前目标/凭据不明）与 6（执行中只读取数失败/结果不可解析）的分界（`:44`）。

### V-8 原始矩阵核对
- `raw/k04-gate-matrix.json`：A～G 七例 `failedAssertions=0`；a/b/c/d 四例 `stubCalls=0`（**拒绝发生在下发任何语句之前**）；e 指纹不符 `stubCalls=1`（仅一次只读探针）；f/g `stubCalls=3`。
- `raw/k05-exitcode-matrix.json`：退出码 5/5/5/6，`failed=0`；取数失败例 `stubCalls=1`。
- `raw/k04-f-delete-fails.txt` 原文含 `PASS 断言输出不得含 '已清商城 event_outbox'` ⇒ 「删失败不得称已清场」有反证断言，不是靠人眼。

---

## 3. 总控发现的瑕疵 / 与历史文档的偏差

### B-1（未取证）影响面预览的**真实行数**未被验证
`raw/k04-f-delete-fails.txt` 原文打印：`待清目标预览：… 当前 3307|de8ebbea-…|dahaishui 行，将全部删除。`
预览取值用 `Select-Object -Last 1`（`run-demo.ps1:177`），在 stub 返回里吃到的是**实例指纹行**——即该用例设的 `K04_STUB_PREVIEW_ROWS=7` 未生效。
该用例的关键断言（DML 显式带 `--port 3307`、失败如实上报、无"已清场"字样）**不受影响**；但「先预览再删」这一门的**数字正确性属未取证**。

### B-2 `-Host` 别名仍在（建议保留 + 加静态检查）
`scripts/it-prepare-isolation.ps1:36`：`[Alias('Host','HostName')][string]$DbHost`。
原缺陷是**参数名**叫 `$Host`（撞 PowerShell 只读自动变量）；别名本身不会产生 `$Host` 变量 ⇒ 保留别名兼容既有调用无害。
建议补一道静态检查（禁 `[string]$Host` 形态的参数声明）防同型复发。

### B-3（有意偏离，须出日期化勘误）
`docs/acceptance/v25-s01-it-safety-20260914/L5-verification-report.md:336` 原建议「在 `Q()` 内对非零退出码统一 `exit 5`」；本泳道改为 **5 / 6 语义分离**。
判断：6 更准（执行前拒绝 vs 执行中取数失败可区分），**以本轮为准**；按"历史证据 append-only"纪律，只追加勘误，不修改 S01 原文。

### B-4（总控已修正）取证工具新增了明文默认口令
`fingerprint-3306.ps1:34` 原为 `$env:MYSQL_PWD = if ($env:HOST3306_PWD) { … } else { '123456' }`。
这与本仓库正在做的 V25-S05「存量明文默认口令收口」冲突，也与同泳道 K-04/K-05「无口令即拒绝」姿态不一致。
总控修正：未设 `HOST3306_PWD` 即 `throw`，不回退任何默认口令。实测：
- 无口令 ⇒ `exit 1`，报错原文 `未提供 3306 只读口令：请设置环境变量 HOST3306_PWD…`；
- 有口令（`MYSQL_PWD` 注入）⇒ `exit 0`，指纹与修正前**逐字段一致**。
留档：`raw/controller/fingerprint-3306-credential-fix-check.txt`、`raw/controller/s02-fingerprint-3306-controller-before-credential-fix.txt`。
**该修正以总控后续提交落库，不改写 `98393f0`。**

### B-5（治理提示）默认档覆盖面的副作用
默认档由 mall 38 测试 → 8、generator 120 → 101（依赖真库/Spring 上下文的 12 个类移入 `@Tag("it")`）。
这是有意设计；但当前隔离档在**无隔离档案**时 100% 硬失败（mall 30 errors / generator 19 errors，`skipped=0`），意味着这 12 个类**在默认环境下无处可跑**。属 S02 未闭环项的一部分，登记待处置。

---

## 4. 未取证 / BLOCKED（总控侧）

| 编号 | 未取证项 | 原因 / 需要什么 |
|---|---|---|
| U-1 | K-04 影响面预览的真实行数 | stub 未模拟该查询出数（B-1）；真值需在 3307 上跑一次 `-Clean` 演练（写操作，需你批准） |
| U-2 | `start-all.ps1` 的 `exit 4` 分支 | 与泳道同——只做代码核对，未实跑 |
| U-3 | 「拒绝前零连接」的网络层证据 | K-04/K-05 全部用 `tools/mysql-stub.*`，`stubCalls=0` 是**桩的调用日志**，非 socket 层证据 |
| U-4 | 隔离档 `-Pisolated-tests` 实跑 | 缺隔离档案 ⇒ 硬失败是预期；总控只核对了泳道日志与 pom 属性 |
| U-5 | 3306「语句级零写」 | 总控核对的是**前后状态一致**（V-5/V-6）；语句级依据来自泳道 stub 日志与脚本显式 `--port` |

---

## 5. 复核结论

1. **可推送**：`98393f0` 范围无越界、无偷删/禁用测试、main 代码仅注释、默认档测试由总控独立复跑通过。
2. 「**限定验收 ✅**」的成立范围 ＝ 门禁行为在单测与 stub 层面成立；**不是**在真实隔离实例上的端到端成立（那属 F-88 写侧闭环那一环）。
3. `98393f0` 与本次总控修正一并推送；本文档随修正同批入库。
4. 后续：B-3 勘误 → 归入设计文档 V2.4 的登记；B-1/U-1 → 与「F-88 写侧闭环 + 3307 真链复跑」合并处理；B-4 已闭环。
