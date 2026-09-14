# 总控执行报告：S02 隔离档收口 ＋ 编译/测试基线（2026-09-14）

**执行者**：总控会话（唯一作者）。**依据裁决**：2026-09-14 用户裁决 A（S02 凭据收口＝**模板化落库＋真值只走环境变量**）、
裁决 B-2（K-04 保留 `-Host` 别名并加静态检查）、裁决 ①（K-05 `exit 6` 采认并追加日期化勘误）。
**前置**：`docs/design/项目设计文档 V2.3.md` §19 执行队列；`44a2481`（总控复核 L7 提交）为本次基线起点。

---

## 1. 交付物（4 处新增／追加，全部落在仓库内）

| # | 文件 | 性质 | 作用 |
|---|---|---|---|
| 1 | `scripts/it-isolation.env.template` | 新增 | 隔离档环境变量契约**模板**（只含占位符，不含任何真值） |
| 2 | `scripts/run-isolated-tests.ps1` | 新增 | 隔离档**唯一入口**：先过 6 道门禁，再把真值注入当前进程环境拉起 Maven，不落盘 |
| 3 | `scripts/check-ps1-automatic-vars.ps1` | 新增 | 参数名撞只读自动变量的静态检查（K-01 同型缺陷防复发） |
| 4 | `docs/acceptance/v25-s01-it-safety-20260914/L5-verification-report.md` | **追加**（原文未改） | 退出码 5/6 语义分离补记，标注「以此为准」 |

## 2. S02 未闭环项定位（事实）

* `scripts/it-prepare-isolation.ps1:193-215`：建库/授权后**只把两段档案片段打印**出来，由人手工抄成模块工作目录下的
  `*.local.properties`，且口令写成 `credref:<id>`。⇒ 手工环节是漏配源；且 `credref:` 在 analytics 侧尚无解析器（S04 未收口），
  两处并存本身就违反单一真相源。
* 取值顺序（源码事实，`itguard/IsolationGuard.java:68-83`）：系统属性 `-Dit.guard.<key>` → 环境变量 `IT_GUARD_<KEY>` → 档案。
  ⇒ **环境变量优先于档案**，因此残留手抄档案不会把本次运行带偏——这是选"环境变量唯一真相源"的技术依据。
* 键名照抄 `envName()`：`IT_GUARD_INSTANCEPORTS`（`instancePorts` 全大写、**无下划线**）、`IT_GUARD_RUNID`、`IT_GUARD_SERVERFINGERPRINT`、
  `IT_GUARD_URL/USER/PASSWORD`。mall 的 Spring 测试上下文另读 `${MALL_ISOLATION_URL|USER|PASSWORD|FLYWAY_ENABLED}`（**无默认值**，
  `mall-simulator/src/test/resources/application-test.yml`）。
* 派生名与 `it-prepare-isolation.ps1:89-92` 同源：`<runId>_mall` / `<runId>_generator` / `<runId>_mallapp` / `<runId>_genapp`。

## 3. 实测证据

### 3.1 静态检查器（`scripts/check-ps1-automatic-vars.ps1`）

| 用例 | 输入 | 期望 | 实测 |
|---|---|---|---|
| 正对照 | `%TEMP%\dsh-ps1-param-check\{pos1-collision.ps1, pos2-args.ps1, neg1-alias.ps1}` | 抓 2 处、不放行别名 | **exit 1**，精确报 `pos1:3 $Host`、`pos2:2 $Args`；`neg1` 的 `[Alias('Host','HostName')][string]$DbHost` **未误报**；`[Parameter(Mandatory = $true)]` 里的 `$true` **未误报** |
| 全仓 | 本仓库 | 通过 | **exit 0**：`196 个 *.ps1、113 个 param 块` 无冲突 |

> 说明：首版判据用括号配对判定 `param` 块范围，被 PowerShell 正则字面量里的 `(`/`)` 打乱，全仓误报 40 处（把 `$null/$false` 当参数名）。
> 已改为「`param` 块内按 `,` 切片段、只看片段首个 `$名字` token」并收窄清单，正对照与全仓两种输入均复测通过。**假阳性已消除，非"没人报所以通过"。**

### 3.2 隔离档入口门禁（`scripts/run-isolated-tests.ps1`，负对照矩阵）

| 用例 | 命令要点 | 期望 | 实测 |
|---|---|---|---|
| 语法 | `Parser::ParseFile` | 0 错误 | **PARSE OK（0 错误）**（首轮曾报 `:174 缺少右括号`，已修） |
| C1 | `-RunId v25it_20260914_ctl1 -DryRun -Module both` | 0，且不连库/不落盘 | **exit 0**；打印 6 道门禁与全部待注入变量，口令显示 `<未设置>` |
| C2 | `-DryRun -Port 3306` | 5 | **exit 5**：`端口 3306 不在允许清单 [3307] 内：拒绝` |
| C3 | `-RunId ab -DryRun` | 5 | **exit 5**：`RunId 'ab' 形状非法` |
| C4 | `-Module mall -Confirm`（无口令） | 5，且先于任何 SQL | **exit 5**：`缺口令：mall`（本脚本**无** `-Password` 参数，口令不得进进程列表） |
| C5 | `-Confirm` ＋ 伪口令 | 6（探针取数失败） | **exit 6**：`ERROR 1045 (28000): Access denied for user 'v25it_20260914_ctl1_mallapp'@'127.0.0.1'` |

> C5 附带实测结论：**`127.0.0.1:3307` 的 MySQL 此刻在跑**（返回的是 3307 上的 Access denied，而非连不上）。

### 3.3 编译基线（4 个 Maven 目标，逐目标一份原始日志）

| 目标 | `-f` | 实测 |
|---|---|---|
| `analytics-server` reactor | `analytics-server/pom.xml` | **exit 0**，`SUCCESS []=7`、`FAILURE []=0`、`BUILD SUCCESS` |
| `mall-simulator` | `mall-simulator/pom.xml` | **exit 0**，`BUILD SUCCESS` |
| `synthetic-data-generator` | `synthetic-data-generator/pom.xml` | **exit 0**，`BUILD SUCCESS` |
| `spark-jobs` | `spark-jobs/pom.xml` | **exit 0**，`BUILD SUCCESS` |

命令模板：`mvn.cmd -o "-Dmaven.repo.local=D:\maven_repository" -f <pom> test-compile`。
> 勘误一条：仓库根**没有 POM**（`analytics-server` 是聚合器，`mall-simulator`/`synthetic-data-generator`/`spark-jobs` 是独立工程），
> 所以「7/7」指的是 **analytics-server reactor 的 7 个 module**，不是"根 reactor"。首次在仓库根执行 `test-compile` 失败，
> 原始日志保留并改名自证：`raw/INVALID-ATTEMPT-root-reactor-no-root-pom.log`（**不计入基线**）。

### 3.4 测试静态清点（**清点≠实测**，仅用于界定覆盖缺口）

| 模块 | 测试 java | `@Test` | `@ParameterizedTest/@RepeatedTest` | `@Disabled/@Ignore` | `@Tag("it")` |
|---|---|---|---|---|---|
| analytics-server/platform-common | 13 | 59 | 0 | 0/0 | 0 |
| analytics-server/platform-app | 22 | 121 | 0 | 0/0 | 0 |
| analytics-server/warehouse-pipeline | 15 | 108 | 2 | 0/0 | 0 |
| analytics-server/metric-analysis | 10 | 59 | 0 | 0/0 | 0 |
| analytics-server/connection-ingestion | 18 | 150 | 1 | 0/0 | 0 |
| analytics-server/ai-decision | 10 | 91 | 0 | 0/0 | 0 |
| mall-simulator | 16 | 38 | 0 | 0/0 | 9 |
| synthetic-data-generator | 23 | 120 | 0 | 0/0 | 3 |
| spark-jobs | 0（**无 `src/test/java`**） | — | — | — | — |
| **合计** | **127** | **746** | **3** | **0** | **12** |

默认档排除机制（pom 事实）：`<v25.it.excluded.groups>it</v25.it.excluded.groups>` ⇒ surefire `<excludedGroups>it</excludedGroups>`；
`isolated-tests` profile 只把该属性置空（`mall-simulator/pom.xml:31,106,133-137`、`synthetic-data-generator/pom.xml:36,94,106-110`）。
⇒ 默认档实测 8（mall）/101（generator）**不含**被标 `it` 的类；按 `总数−默认档` 推算，**30（mall）/19（generator）个测试只能进隔离档跑**。

## 4. 结论分级（分项判定，不合并）

| 项 | 提交完成 | 测试通过 | 限定验收 | 完整验收 |
|---|---|---|---|---|
| S02 模板落库＋环境变量唯一真相源 | 是 | 是（门禁负对照 C1–C5＋静态检查正/负对照） | 是（门禁层与契约层） | **否**（隔离档真跑未执行） |
| 静态检查器（K-01 防复发） | 是 | 是（正对照抓 2 处＋全仓 196 文件通过） | 是 | 是（工具自身可复测） |
| 编译基线（4 目标） | 是 | 是（4× exit 0） | 是 | 是 |
| 默认真实测试数 | — | 部分（mall 8／generator 101 已实测；analytics-server 六模块**未测**） | 否 | **否** |

## 5. 未取证 / BLOCKED（显式登记，不得当作已完成）

1. **隔离档真跑未执行**：入口与 6 道门禁已实测，但「建库→跑 30/19 个 `it` 测试变绿」**一次都没跑**。归入下一步（F-88 隔离真链，同一批 3307 会话内做）。
2. **指纹不符 ⇒ exit 5 的分支未实测**：需真实存在的受限账号才能走到该分支（C5 只证明了"账号不存在⇒6"）。
3. **analytics-server 六模块默认真实测试数未测**：V19/V20 在树中，`test` 一旦触发 Flyway 就可能**前向迁移 3306（不可逆）**，
   在 S04/D-5 的"启动前门禁"落地前**不得**跑。当前状态：编译基线已过、运行数为**未测**。
4. **`credref:` 在 analytics 侧仍无解析器**（S04 收口项，二者只能留一个）。
5. **`-LogDir` 默认写 `%TEMP%\v25it-logs-<runId>`**：避免在仓库里到处建目录；出证据须显式给 `-LogDir`。
6. **`it-prepare-isolation.ps1:193-215` 的打印段未删除**：本次只新增入口，未改旧脚本的打印行为；要不要删打印、改成"引导用新入口"，属 S04 收口范围（未改，避免超出本次授权）。
