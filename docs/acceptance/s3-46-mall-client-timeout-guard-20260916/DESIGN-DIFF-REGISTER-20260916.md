# S3-46 设计差异登记：商城客户端「连接/请求超时」结构守卫（L85 适用范围核对）

- **日期**：2026-09-16
- **轮次**：S3-46（V3.0 持续执行模式，goal round 7→8 内的一次子任务）
- **分支 / worktree**：`feature/v3-development` / `D:\Develop_code\GraduationProject-wt\v3-dev`
- **起点 HEAD**：`167ca3cd895447c990ae890b00f3acfd67200f09`（S3-45 文档提交）
- **来源**：`docs/PROJECT_STATUS.md` backlog 行「**S3-19 遗留 R9**：**HTTP 请求级**统一超时（设计 L85）**仍未做**；S3-19 统一的是只读 DB 查询（语句）超时」＋ 设计文档 L85 依赖纪律末句
- **类别**：**A 类（IMPLEMENTATION/ADDITIVE 中的反熵守卫）** —— 新增 1 个测试类 ＋ 门禁基线数字与注释；**零生产 Java 改动、零 DB、零网络、零契约变更**
- **交付面**：`synthetic-data-generator/src/test/java/com/graduation/generator/adapter/MallHttpClientTimeoutGuardTest.java`（新增，4 用例）、`scripts/run-tests.ps1`（基线 `synthetic-data-generator` 106→110 ＋ S3-46 注释块）
- > 本轮的性质是「**先核对适用范围再定方案**」：核对结论是 backlog 行 R9 的措辞（「平台→商城」）在本仓库代码里**没有对应实现面**，而真实的商城客户端**早已有**连接/请求超时、却**没有任何测试钉住**。因此本轮的落点是**守卫**（把已实现的事实钉住），不是**实现**（无缺口可补）。这一结论**不等于**「R9 已关闭」（见 §7）。

---

## §1 开工前实测（真跑，改前）

| 实测项 | 命令 / 位置 | 改前事实 |
| --- | --- | --- |
| 设计原文（标尺） | `docs/design/项目设计文档 V3.0.md` L85 | 「平台不得依赖 mall-simulator 或 generator 的实现 jar；生成器不得依赖 `MallBusinessService`。交换协议用 JSON/HTTP，不跨库查询。公共契约不含某商城数据库实体。**每个客户端有连接/请求超时、有限重试、幂等与错误映射**。」 |
| backlog 行原文 | `docs/PROJECT_STATUS.md` L460 | 「**S3-19 遗留 R9**：**HTTP 请求级**统一超时（设计 L85）**仍未做**；S3-19 统一的是只读 DB 查询（语句）超时」，并带 S3-44 内联注（S3-44 做的是 AI Provider 的请求级超时；L85 的「每个客户端」指**平台→商城客户端**）⇒ 本行仍未关闭 |
| 全仓出站 HTTP 客户端清单 | `git grep -n 'HttpClient.newBuilder()\|HttpRequest.newBuilder('` ＋ `RestClient` 检索（main 树） | 仅 **3 个**：① `analytics-server/ai-decision/.../llm/OpenAiCompatLlmProvider.java`（`RestClient`＋`SimpleClientHttpRequestFactory`，S3-44 已做请求级超时）；② `synthetic-data-generator/.../adapter/ReferenceMallHttpAdapter.java`（`java.net.http.HttpClient`）；③ `synthetic-data-generator/.../adapter/SecondMallHttpAdapter.java`（同）。**`analytics-server` 里不存在「平台→商城」客户端** |
| 生成器商城客户端站点 | 同上，逐行 | 共 **8 个站点**：`HttpClient.newBuilder()` 4 个（Ref L353/L492、Second L478/L794）；`HttpRequest.newBuilder(` 4 个（Ref L300/L565、Second L564/L738） |
| 超时是否已设置 | 逐站点读语句 | **8/8 全部已设**：客户端站点均带 `.connectTimeout(timeout)`，请求站点均带 `.timeout(timeout)`（Ref 超时点 L301/L354/L493/L567；Second L479/L566/L739/L795） |
| 超时值来源 | `config/GeneratorBeans.java` L66 / L69 / L70 | `@Value("${generator.target.probe-timeout-ms:3000}") long probeTimeoutMs`，两家适配器**共用** `Duration.ofMillis(probeTimeoutMs)`；`synthetic-data-generator/src/main/resources/application.yml` 里**未声明**该键（取值＝内联默认 3000ms） |
| 超时是否有测试钉住 | `git grep -ln 'connectTimeout' -- '*src/test*'` | **0 个测试文件命中**（三个工程全算）；`git grep -ln 'RestClient\|HttpClient' -- '*src/test*'` 同样**空** ⇒ 「新加一个客户端忘了设超时」此前不会让任何用例变红 |
| L85 其余三项 | `git grep -nE '\bretry\b|backoff|maxAttempts'`、`-niE 'idempot'`、`-nE 'enum [A-Za-z]*Error|ErrorCode|FailureKind'`（生成器 main 树） | **有限重试 0 命中、幂等 0 命中、错误分类码 0 命中**；错误出口是 `MallOperationException`（只有 `operation` 与文本，Ref 19 处 / Second 23 处 `throw`） |

**结论（核对）**：L85 的「连接/请求超时」在**真实商城客户端**（生成器侧两家适配器）上**已经实现**；「平台→商城客户端」在本仓库**不存在**，故 backlog 行 R9 以「平台→商城」描述该缺口与代码事实不吻合 —— 这是**口径错位**，不是「超时未做」。真正的缺口有两类：①**无守卫**（已实现但无测试钉住）；②L85 的「有限重试／幂等／错误映射」在商城客户端上**未实现**（本轮只登记，不实现，见 §7）。

---

## §2 类别判定（11 个 HARD DECISION 门逐个核对）

| 门 | 是否触及 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | 否 | 未碰任何 DDL |
| ② 改已有字段类型或既有业务语义 | 否 | 零生产 Java 改动；未改超时键名、未改默认值（只是把它**钉住**） |
| ③ 改已发布 Flyway migration | 否 | 未碰 `V*.sql` |
| ④ 写/迁移正式 3306 数据 | 否 | 零连库 |
| ⑤ 切 ACTIVE | 否 | 未碰快照状态 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | 否 | `contract-specs/**` 零改动 |
| ⑦ 改 V3.0 总体架构 | 否 | 只加测试 |
| ⑧ 改正式项目范围 | 否 | 未新增客户端、未新增组件（正是**避免**凭空造一个「平台→商城客户端」） |
| ⑨ 删除已发布功能 | 否 | 无删除 |
| ⑩ 引入 V3.0 未规划大型基础组件 | 否 | 未引入任何依赖（测试只用 JUnit5＋AssertJ＋JDK，均为既有） |
| ⑪ 两种方案造成重大长期架构分叉 | 否 | 守卫只钉「设了超时」，不改变方案取向 |

**判定**：A 类，登记后自主实现（本文件即登记）。

---

## §3 口径

1. **「客户端」的解释**：按 L85 原文的交换协议落点解释 —— 与商城以 JSON/HTTP 交换的实现只有生成器的两家适配器；平台侧（`analytics-server`）唯一的出站客户端是 AI Provider（对 LLM，不是对商城）。**不**为了迎合 backlog 行的措辞去新建一个「平台→商城客户端」（那会撞门⑧/⑩），而是把措辞与代码事实的错位**登记**下来。
2. **站点判据＝源码文本 ＋ 语句边界 ＋ 注释剥离**：每个站点取「从站点起到下一个 `;`」的片段，片段内必须出现对应超时设置（客户端站点 `.connectTimeout(`；请求站点 `.timeout(`）；扫描前按字符剥离 `//`、`/* */`（含字符串/字符/文本块识别），注释里的超时设置**不算数**。
3. **集合相等防恒真**：站点所有者集合（文件→站点数）**恰好**等于已登记集合（两家适配器各 4 个站点）⇒ 新增/删除客户端文件或站点数变化都必须先登记，否则红。
4. **单属主**：超时键在 main 树里只能有**一处**声明（`GeneratorBeans`），且两家适配器共用同一个值；键名与默认值（`probe-timeout-ms:3000`）一并被钉住（改名/改默认值 ⇒ 红，必须先登记或裁决）。
5. **不越界表述**：守卫只证明「**设了**超时」，**不证明**超时值合适、**不证明**建连超时行为（无真实商城）；本守卫变绿**不等于** L85 全项满足。

---

## §4 实现面

- **新增** `synthetic-data-generator/src/test/java/com/graduation/generator/adapter/MallHttpClientTimeoutGuardTest.java`（329 行，CRLF，4 个 `@Test`）：
  1. `everyHttpClientIsBuiltWithConnectTimeout` —— 每个 `HttpClient.newBuilder()` 站点同语句必须有 `.connectTimeout(`；非空跑断言：客户端站点数**恰好 4**。
  2. `everyHttpRequestSetsItsOwnTimeout` —— 每个 `HttpRequest.newBuilder(` 站点同语句必须有 `.timeout(`；非空跑断言：请求站点数**恰好 4**。
  3. `clientSitesMatchRegisteredOwnerSet` —— 站点所有者集合（文件→站点数，Ref 4 / Second 4）**恰好**等于登记集合。
  4. `timeoutValueHasASingleConfigurationOwner` —— 超时键只有一处声明（`config/GeneratorBeans.java` 且恰好 1 次）、声明文本含默认值 `:3000`、两家适配器各取一次同一个值（`Duration.ofMillis(` 恰好 2 次）。
- **修改** `scripts/run-tests.ps1`（+29/−1，934 行）：`$BaselineDefault['synthetic-data-generator']` `106→110`；在 S3-45 注释块之后追加 S3-46 注释块（28 行，含边界声明）。未改任何命令语义。
- **零改动**：生产 Java、`pom.xml`、`application.yml`、`contract-specs/**`、Flyway、DB。

---

## §5 证据（真跑）

### 5.1 RED/GREEN 的诚实表述（重要）

本守卫是 **characterization guard**：被守的性质（超时已设）在写守卫**之前**就已成立，因此**没有**「实现前 RED」。

- 首跑（`RunId` 无，单类内环）：`Tests run: 4, Failures: 2` —— 失败原因是**本守卫自己的非空跑常量写错**（把「两站点合计 8」当成了**分类型**站点数，实际客户端 4 ＋ 请求 4），不是被守性质的违反。日志原文：`[必须真的扫到出站请求站点（S3-46 实测 8 个）] expected: 8 but was: 4`。
- 修正常量后：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（exit 0）。
- ⇒ 非恒真性**不靠**首跑 RED，而靠下表的**变异探针**证明。

### 5.2 变异探针（备份 → 单类真跑 → 记录失败用例名 → 还原 → sha256 校验）

| 探针 | 变异 | 预期 | 实测（失败用例） | 还原一致 |
| --- | --- | --- | --- | --- |
| P1 | 删掉 `send(...)` 里 OPTIONS 探测站点的 `.timeout(timeout)` | 仅②红 | `exit=1`，failed=`everyHttpRequestSetsItsOwnTimeout` | True |
| P2 | 新增第三个客户端文件（**已带**超时，`ProbeThirdMallHttpAdapter.java`） | 集合漂移 ⇒ ①②③红 | `exit=1`，failed=`clientSitesMatchRegisteredOwnerSet,everyHttpClientIsBuiltWithConnectTimeout,everyHttpRequestSetsItsOwnTimeout` | True（探针文件已删除） |
| P3 | 把一处 `.timeout(timeout)` **注释掉** | ②仍红（注释剥离生效） | `exit=1`，failed=`everyHttpRequestSetsItsOwnTimeout` | True |
| P4 | 超时默认值 `3000→5000` | ④红 | `exit=1`，failed=`timeoutValueHasASingleConfigurationOwner` | True |

P3 的意义：若注释**没有**被剥离，被注释掉的 `.timeout(` 仍会被当作「设了超时」而**绿** —— 实测红，说明剥离真的生效。

### 5.3 门禁（默认档，两轮）

- **量数轮** `-RunId s346-1 -LogDir .verify\s346-gate-1`：`analytics-server 983 (F=1 E=0 S=1) 明细 93+353+169+97+114+157` ⇒ **MATCH**；`mall-simulator 13` ⇒ **MATCH**；`synthetic-data-generator 110 (F=0 E=0 S=0)` ⇒ **DRIFT(基线 106)**，**+4 全部落在本轮新守卫**（生成器其余用例数一字未变）；`default 三棵树 1106（基线 1102）`；唯一红仍是已登记环境性用例。该轮因计数漂移记 **FAIL exit=7**，**只作量数依据、不作通过证据**。
- **收口轮** `-RunId s346-final -LogDir .verify\s346-gate-final`（基线改为 110 后）：`983 (F=1 E=0 S=1)` **MATCH**、`13 (F=0 E=0 S=0)` **MATCH**、`110 (F=0 E=0 S=0)` **MATCH**；`default 三棵树 合计 = 1106（基线 1106）`；`[FAIL exit=7] 所选档未全部通过。`
- **⚠ 唯一红未修、未掩盖**：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（物理位于 `platform-app` 模块）`expected: 43 but was: 0` —— 成因是 `landing/` 被 `.gitignore:30` 忽略、worktree 无 43 份冻结清单。**不得**称「门禁已通过」。

---

## §6 契约变更

**无**。`contract-specs/**` 零改动；零生产代码改动；零 DDL/DB/网络；未改任何契约语义。

---

## §7 未测与边界（不得越界表述）

1. 守卫**没有经典 RED**（characterization guard），非恒真性由 4 个变异探针证明（§5.2）；这**不等于**「测试先失败后通过」的 TDD 过程。
2. 判据是**源码文本 ＋ 语句边界 ＋ 注释剥离**，**不是**语义证明：把链式调用改写成等价形式、或把 `.timeout` 放到另一个语句里，都可能让它红（**这正是**防恒真的代价）。
3. 只钉「**设了**超时」：**不证明** 3000ms 合适、**不单独实测**建连超时/请求超时的行为（无真实商城可测；平台侧 AI Provider 的请求级超时行为由 S3-44 的测试钉住）。
4. 覆盖面只到 `synthetic-data-generator/src/main/java`：**不含** `spark-jobs`、`analytics-server` 的客户端面。
5. **L85 的「有限重试」「幂等」「错误映射」在商城客户端上未实现**（重试/幂等 0 命中；`MallOperationException` 无分类码）—— 本轮**只登记不做**：无真实商城可证「重试安全」，写操作自动重试有重复下单风险，属需要方案裁决的面（见 §8 台账）。
6. 超时键名 `probe-timeout-ms` 实际**兼管业务调用**（同一 `Duration` 同时用于 OPTIONS 探测与真实业务请求），命名与用途不吻合；改名＝改既有配置语义，新增第二个键＝同一取值两个属主（反熵不允许）⇒ 本轮**不动**，只登记。
7. `isolated` / `spark` 两档**未重跑**：新用例无 `@Tag("it")`，不在 isolated 选择面内（`<groups>it</groups>`），生成器亦不属 spark 档。
8. 默认档仍 `FAIL exit=7`，唯一红是 §5.3 的已登记环境性用例（未修、未用开关掩盖）。
9. 「平台→商城客户端不存在」是**当前仓库事实**；若 V3.0 正式范围要求该客户端，那是**新增组件/范围**问题，须走门⑧/⑩裁决，本轮**未**擅自新建。

---

## §8 顺带台账

- **R9 行内追加更正**（`docs/PROJECT_STATUS.md` L460，不删行、不改判类）：追加 S3-46 实测更正 —— 平台侧无商城客户端、真实商城客户端在生成器且超时已实现并有结构守卫、本行「平台→商城」措辞与代码事实错位、真正残余是 L85 的重试/幂等/错误映射。
- **新增 backlog 行**：S3-46 后 L85 商城客户端残余面（有限重试／幂等／错误映射未实现；`MallOperationException` 无分类码；超时键名 `probe-timeout-ms` 兼管业务调用；平台→商城客户端不存在这一事实的适用范围）。
- **探针零残留**：`ReferenceMallHttpAdapter.java`、`GeneratorBeans.java` 还原后 sha256 与改前一致（`还原一致=True`）；探针文件已删除；`git status --porcelain` 除本轮新建的守卫测试文件外无其他改动。
- **门禁基线**：`scripts/run-tests.ps1` 只改一个数字（106→110）＋注释块，未改命令语义。

---

## §9 复现命令

```powershell
# 0) 内环单类（生成器模块）
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f synthetic-data-generator\pom.xml test '-Dtest=MallHttpClientTimeoutGuardTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'

# 1) 改前事实核对（本轮 §1 的检索）
git grep -n 'HttpClient.newBuilder()\|HttpRequest.newBuilder(' -- 'synthetic-data-generator/src/main/**/*.java'
git grep -ln 'connectTimeout' -- '*src/test*'          # 改前：0 命中
git grep -niE 'idempot|\bretry\b|ErrorCode' -- 'synthetic-data-generator/src/main/**/*.java'   # 改前：0 命中

# 2) 变异探针（4 个；改前备份、改后还原并 sha256 校验）
pwsh -NoProfile -File .verify\s346_probes.ps1

# 3) 门禁两轮（默认档）
pwsh -NoProfile -File scripts\run-tests.ps1 -Suite default -RunId s346-1     -LogDir .verify\s346-gate-1     -Confirm   # 量数轮：DRIFT(基线 106)
pwsh -NoProfile -File scripts\run-tests.ps1 -Suite default -RunId s346-final -LogDir .verify\s346-gate-final -Confirm   # 收口轮：110 MATCH；三棵树 1106（基线 1106）
```
