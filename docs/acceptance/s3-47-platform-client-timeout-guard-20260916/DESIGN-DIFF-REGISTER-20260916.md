# S3-47 设计差异登记：平台侧出站客户端「连接/请求超时」结构守卫（S3-46 覆盖面收口）

- **日期**：2026-09-16
- **轮次**：S3-47（V3.0 持续执行模式，goal round 8/40 内的一次子任务）
- **分支 / worktree**：`feature/v3-development` / `D:\Develop_code\GraduationProject-wt\v3-dev`
- **起点 HEAD**：`dfef8a7ac174cf708de11f9fc479154e5911a6e5`（S3-46 文档提交）
- **代码提交**：`a3a2842`（本守卫测试 ＋ `scripts/run-tests.ps1` 基线，2 文件 +409/−1）
- **来源**：S3-46 登记 §7 **边界③④**（S3-46 的结构守卫**只覆盖 `synthetic-data-generator` 的商城客户端**；平台侧 AI Provider **无守卫**）＋ 设计文档 L85 依赖纪律末句「**每个客户端有连接/请求超时、有限重试、幂等与错误映射**」的**平台侧覆盖面**
- **类别**：**A 类（IMPLEMENTATION/ADDITIVE 中的反熵守卫）** —— 新增 1 个测试类（4 用例）＋ 门禁基线数字与注释块；**零生产 Java 改动、零 `pom.xml`、零 `application.yml`、零 DB、零网络、零契约变更**
- **交付面**：`analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/llm/PlatformOutboundClientTimeoutGuardTest.java`（新增，377 行／4 用例）、`scripts/run-tests.ps1`（`$BaselineDefault` 的 `analytics-server` 项 983→987 ＋ S3-47 注释块）
- > 本轮的性质是「**把已实现的事实钉住**」：平台侧出站客户端**只有 1 个**、它**早已**把连接与读取超时同源接上、值**早已**来自唯一注入键。缺口**不是**「超时未做」，而是**没有任何用例钉住这一形态** —— 将来新加一个「忘了设超时」的出站客户端，此前不会让任何用例变红。本轮的落点是**守卫**。这一结论**不等于**「L85 已满足」（见 §7）。

---

## §1 开工前实测（真跑，改前）

| 实测项 | 命令 / 位置 | 改前事实 |
| --- | --- | --- |
| 设计原文（标尺） | `docs/design/项目设计文档 V3.0.md` L85 | 「平台不得依赖 mall-simulator 或 generator 的实现 jar；生成器不得依赖 `MallBusinessService`。交换协议用 JSON/HTTP，不跨库查询。公共契约不含某商城数据库实体。**每个客户端有连接/请求超时、有限重试、幂等与错误映射**。」 |
| backlog 行原文 | `docs/PROJECT_STATUS.md` L467（追加后为 L474） | 「**S3-19 遗留 R9**：**HTTP 请求级**统一超时（设计 **L85**）**仍未做**；S3-19 统一的是**只读 DB 查询（语句）超时**」，带 S3-46 内联注（S3-46 做的是生成器侧商城客户端的结构守卫）⇒ 本行仍未关闭 |
| 扫描面规模 | `analytics-server/*/src/main/java/**/*.java` 计数 | **203** 个 Java 文件（ai-decision 37／connection-ingestion 92／metric-analysis 13／platform-app 22／platform-common 22／warehouse-pipeline 17） |
| 出站 HTTP 客户端构造点 | 全 main 树逐文件检索 `RestClient.builder()`／`RestClient.create(`／`HttpClient.newBuilder()`／`WebClient.builder()` | **恰好 1 个**：`analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/llm/OpenAiCompatLlmProvider.java` L54 `RestClient.builder()`（同语句 L56 `.requestFactory(requestFactory(timeoutMs))`、L57 `.build()`） |
| 该站点是否已接超时 | 同文件 L54–L57 逐行 | **已接**：`.requestFactory(requestFactory(timeoutMs))` 与 `RestClient.builder()` **同一条语句**（到 `;` 为止） |
| 请求工厂 | 同文件 L84–L90 | `static ClientHttpRequestFactory requestFactory(long timeoutMs)`：L86 `new SimpleClientHttpRequestFactory()`、L87 `factory.setConnectTimeout(timeout)`、L88 `factory.setReadTimeout(timeout)`、L90 `return factory;` ⇒ **连接与读取同源**（同一入参） |
| 超时值来源 | 同文件 L39 / L50 / L76–L78 | L39 `public static final long DEFAULT_TIMEOUT_MS = 30_000L;`；L50 `@Value("${llm.timeout-ms:30000}") long timeoutMs`（构造器参数，绑定名 `timeoutMs`）；L76–L78 `normalizeTimeoutMs`（非正数回落 `DEFAULT_TIMEOUT_MS`） |
| 配置是否声明该键 | `analytics-server/platform-app/src/main/resources/application.yml` 检索 `llm` | **未声明**（0 命中）⇒ 取值＝内联默认 **30000ms** |
| 其它客户端形态 | 全 main 树检索 `RestTemplate`／`WebClient`／`OkHttp`／`HttpClient\.`／`HttpURLConnection`／`new URL(` | **零命中** ⇒ 上文那个「1」是**当前仓库事实**，不是抽样结果 |
| 测试树是否钉住该形态 | `analytics-server` 测试树检索 `setConnectTimeout`／`setReadTimeout`／`SimpleClientHttpRequestFactory`／`RestClient` | **零命中（4 个关键词全 0）** ⇒ 「新加一个客户端忘了设超时」此前**不会**让任何用例变红 |
| 既有相关用例（行为面） | `ai-decision/.../llm/OpenAiCompatLlmProviderTest.java`（361 行） | 钉 `DEFAULT_TIMEOUT_MS`、`normalizeTimeoutMs(-1/0/1500)` 与「超时到点即失败」的**行为**；**不**钉「平台里每个出站客户端都带超时」这一**形态** |
| `RepoRoot` 可用性 | `analytics-server/platform-common/src/test/java/com/graduation/analytics/testsupport/RepoRoot.java`（41 行）＋ ai-decision `pom.xml` L63–71 | `platform-common` 以 **test-jar（test 作用域）**暴露；`AiSqlDriftTest` 已在 ai-decision 测试里使用 ⇒ 站点定位可**复用唯一所有者**，**不**新增「向上找仓根」实现（S3-45 口径） |

**结论（核对）**：平台侧「连接/请求超时」**已实现**且**唯一**：1 个客户端站点、1 个工厂、两个 setter 同源、1 个注入键、1 个默认常量。真正的缺口是**无守卫**（无任何测试钉住形态）；L85 的「有限重试／幂等／错误映射」在平台侧**亦未实现**（本轮**只登记**，见 §7）。

---

## §2 类别判定（11 个 HARD DECISION 门逐个核对）

| 门 | 是否触发 | 依据 |
| --- | --- | --- |
| ① DROP TABLE / COLUMN | **否** | 零 SQL、零迁移、零 DB 连接 |
| ② 改已有字段类型或既有业务语义 | **否** | 零生产 Java 改动；`@Value` 内联默认（30000）与 `DEFAULT_TIMEOUT_MS`（`30_000L`）**一字未动**，本轮只**断言二者相等**，**未合并**（合并会改既有配置语义 ⇒ 登记不动） |
| ③ 改已发布 Flyway migration | **否** | `**/db/migration/**` 零改动 |
| ④ 写/迁移正式 3306 数据 | **否** | 零 DB 访问；3306 新写入/迁移/ACTIVE 切换**仍冻结** |
| ⑤ 切 ACTIVE | **否** | 未触及 profile／激活开关 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | `contract-specs/**` 与 `docs/contracts/**` **零改动**（本轮**无契约变更**） |
| ⑦ 改 V3.0 总体架构 | **否** | 无新模块、无新依赖、无新客户端 |
| ⑧ 改正式项目范围 | **否** | 未新增「平台→商城客户端」（那属**新组件/范围**问题，见 §7 ⑥，本轮**未**擅自新建） |
| ⑨ 删除已发布功能 | **否** | 纯新增文件 ＋ 门禁基线数字 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖；测试只用既有 `platform-common` test-jar（`RepoRoot`）与 JDK/AssertJ |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 只有一种落点：结构守卫；被否掉的备选（合并两处 30000 声明／给 AI 调用加重试）都属**门②**邻域，本轮**不碰、只登记** |

**判定**：**A 类**（ADDITIVE），无门触发 ⇒ 登记 → 自主设计 → 实现 → 实测 → 提交，**不需要**总控裁决。

---

## §3 口径

1. **站点判据＝源码文本 ＋ 语句边界（到下一个 `;`）＋ 注释剥离**：按字符扫描，处理 `//`、`/* */`、字符串/字符/文本块；注释里的「站点」**不算数**（否则把接线注释掉就能骗过守卫 —— P3 实测证明剥离生效）。
2. **集合相等防恒真**：站点所有者集合（文件 → 站点数）**恰好等于**登记集合 `{ai-decision/.../llm/OpenAiCompatLlmProvider.java: 1}` —— **多一处即红、少一处亦红** ⇒ 新增出站客户端必须**显式登记**（P2 实测）。
3. **形态 → 接线映射显式登记**：`RestClient.builder()`／`RestClient.create(` → `.requestFactory(`；`HttpClient.newBuilder()` → `.connectTimeout(`；`WebClient.builder()` → `.clientConnector(`。新增形态**必须显式登记**，否则①红。
4. **同源设置**：请求工厂站点**所在方法**（文本判据：向上最近的 4 空格缩进方法签名 → 向下第一个 4 空格缩进 `}`）必须**同时**含 `setConnectTimeout(` 与 `setReadTimeout(`（P4 实测：只删一个 setter ⇒ 仅③红）。
5. **单值一致性**：注入键在 main 树**恰好一处**、`DEFAULT_TIMEOUT_MS` 声明**恰好一处**、两处数值**必须相等**、客户端必须把**注解绑定的参数名**（不是字面量）交给工厂（P5 实测：内联默认改 5000 ⇒ 仅④红）。
6. **扫不掉不能过**：被扫描的 main Java 文件数 **> 150**（实测 203）⇒ 路径写错/扫描面塌缩会红，而不是「零命中即通过」。
7. **复用唯一所有者**：站点定位用 `RepoRoot.path("analytics-server")`（S3-45 确立的唯一所有者），**不**在新测试里重造「向上找仓根」（S3-45 守卫会因新增第二处 walk-up 而红 ⇒ 本轮实测确认未触发）。
8. **门禁只允许 analytics-server +4**（落在 ai-decision 114→118）；`mall-simulator` 与 `synthetic-data-generator` **不允许**变动。

---

## §4 实现面

| 文件 | 变更 | 说明 |
| --- | --- | --- |
| `analytics-server/ai-decision/src/test/java/com/graduation/analytics/ai/llm/PlatformOutboundClientTimeoutGuardTest.java` | **新增**（377 行／17420 B／4 用例，CRLF、零裸 LF、末尾有换行、无 BOM） | 用例：①`everyOutboundClientSiteCarriesItsTimeoutWiring`（每个站点同语句必须挂该形态的接线 ＋ 站点总数 ＝ 1 ＋ 扫描规模下限）；②`clientSitesMatchRegisteredOwnerSet`（所有者集合**恰好**等于登记集合）；③`requestFactorySetsConnectAndReadTimeoutTogether`（工厂站点恰好 1 个、所在方法同时含两个 setter、所有者 `startsWith(PROVIDER_FILE + ":")`）；④`timeoutComesFromASingleConfiguredKeyWithAPinnedDefault`（注解所有者 `containsExactly(PROVIDER_FILE)`、常量所有者 ＝ `PROVIDER_FILE`、内联默认 ＝ 常量 ＝ `30_000L`、客户端把绑定参数名交给工厂）。自带 `stripComments`（**等长空白**替换，行号仍对得上原文）、`statementAt`、`enclosingMethod` 工具；站点定位 `RepoRoot.path("analytics-server")` |
| `scripts/run-tests.ps1` | **+32/−1**（934→965 行） | `$BaselineDefault['analytics-server']` **983→987**；在 `$BaselineIsolated` 之前插入 S3-47 注释块（记录量数轮 `s347-1`、`983→987`／`1106→1110`、5 条探针、诚实边界、本登记文件路径） |
| 生产 Java／`pom.xml`／`application.yml`／`contract-specs/**`／`docs/contracts/**`／Flyway／web／DB | **零改动** | `ai-decision/pom.xml` 已含 `platform-common` test-jar（test 作用域），无需新增依赖；`llm.timeout-ms` **仍未**在 `application.yml` 声明这一事实**保持不动** |

**测试位置口径**：新增类落在 `ai-decision`（被测生产类所在模块），与 `OpenAiCompatLlmProviderTest` 同包同目录。

---

## §5 证据（真跑）

### 5.1 RED/GREEN 的诚实表述（重要）

**本守卫是 characterization guard**：被守性质在写守卫**之前**就已经成立（唯一站点**早已**挂工厂、工厂**早已**同源设置）。因此**没有**经典 RED —— **如实登记**，**不得**表述为「先失败后通过的 TDD 过程」。首跑即 GREEN：

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
exit=0
```

非空跑常量**按实测取**（不是猜的）：客户端站点 **1**／请求工厂站点 **1**／被扫描 main Java 文件 **203**。非恒真性由 §5.2 的 5 条变异探针证明。

### 5.2 变异探针（备份 → 单类真跑 → 记录失败用例名 → 还原 → sha256 校验）

| 探针 | 变异 | 预期 | 实测失败用例 | 还原 |
| --- | --- | --- | --- | --- |
| **P1** | 删掉客户端语句里的 `.requestFactory(requestFactory(timeoutMs))` | ①④红 | `timeoutComesFromASingleConfiguredKeyWithAPinnedDefault`、`everyOutboundClientSiteCarriesItsTimeoutWiring`（`Tests run: 4, Failures: 2`） | `还原一致=True` |
| **P2** | 新增一个「**不带超时**」的出站客户端文件 | ①②红（集合漂移逃不掉） | `clientSitesMatchRegisteredOwnerSet`、`everyOutboundClientSiteCarriesItsTimeoutWiring`（`Failures: 2`） | `还原一致=True` |
| **P3** | 把 `.requestFactory(requestFactory(timeoutMs))` **注释掉** | ①④红（注释剥离生效） | `timeoutComesFromASingleConfiguredKeyWithAPinnedDefault`、`everyOutboundClientSiteCarriesItsTimeoutWiring`（`Failures: 2`） | `还原一致=True` |
| **P4** | 删掉工厂里的 `factory.setReadTimeout(timeout);` | **仅③**红 | `requestFactorySetsConnectAndReadTimeoutTogether`（`Failures: 1`） | `还原一致=True` |
| **P5** | 内联默认 `30000` → `5000` | **仅④**红 | `timeoutComesFromASingleConfiguredKeyWithAPinnedDefault`（`Failures: 1`） | `还原一致=True` |

⇒ **5/5 按预期红**。每条探针均以**字节备份**（`ReadAllBytes`）→ 单类实跑 → 从 surefire 报告取失败名 → `WriteAllBytes` **按字节还原** → sha256 比对（`还原一致=True`）；探针文件已删除，探针后 `git status --porcelain` **只剩本守卫测试文件**、无探针残留。

### 5.3 门禁（默认档，两轮）

| 轮次 | RunId | analytics-server | mall-simulator | synthetic-data-generator | 三棵树 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 量数轮 | `s347-1` | **987**（`F=1 E=0 S=1`）明细 `93+353+169+97+118+157` ⇒ `DRIFT(基线 983)` | `13` `MATCH` | `110` `MATCH` | **1110**（基线 1106） | `[FAIL exit=7]`，**只作量数依据** |
| 收口轮 | `s347-final` | **987** `MATCH` | `13` `MATCH` | `110` `MATCH` | **1110（基线 1110）** | `[FAIL exit=7]`，**唯一红＝已登记环境性红** |

- `+4` **全部落在本守卫**（ai-decision 114→118），其余五个模块**一字未变**。
- 收口轮唯一的红是**已登记环境性红**：`platform-app` 的 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（`expected: 43 but was: 0`；`landing/` 被 `.gitignore:30` 忽略、本工作区无 `landing/manifests`）—— **未修、未复制 manifest、未用开关掩盖**。
- 摘要里的 `S=1` 与该红**无关**：是 `connection-ingestion` 的**既有** skip。
- `spark`／`isolated` 档**未重跑**（新用例无 `@Tag("it")`，不在 isolated 选择面内；`spark` 档不涉及 Java 平台模块）。

---

## §6 契约变更

**无**。`contract-specs/**`、`docs/contracts/**` 零改动；新增产物只有**测试**与**门禁基线数字/注释**，不引入任何新契约、不改任何既有契约语义。

---

## §7 未测与边界（不得越界表述）

1. **没有经典 RED**（characterization guard），非恒真性由 5 条变异探针证明 ⇒ **不得**表述为「先失败后通过的 TDD 过程」。
2. 判据是**源码文本片段 ＋ 语句边界**，**不是**语义等价性证明：等价的 `while`/链式改写、或给 `requestFactory` 方法换缩进风格，都会让守卫变红 —— 这是**防恒真的必要代价**，属**已知限制**。
3. 守卫只钉「**超时被接上**」：**不证明** 30000ms 是合适的值、**不单独实测**建连/读取超时的**行为**（真实供应商**仍为未测**：无 key、无外网出口）。
4. 只覆盖 `analytics-server/*/src/main/java` ⇒ **不含** `spark-jobs`（Scala／JDK8）、`mall-simulator`、`synthetic-data-generator`（生成器侧由 S3-46 的守卫覆盖）⇒ **不得**称「全仓出站客户端超时已全部纳入守卫」。
5. **L85 的「有限重试」「幂等」「错误映射」在平台侧同样未实现**（本轮**只登记、不实现**）。AI 侧加重试＝把一次失败放大成多次**计费**调用，且无实测依据 ⇒ **不得**因本守卫变绿就声称「L85 已满足」「请求级统一超时已收口」。
6. 超时键 `llm.timeout-ms` 在 `application.yml` **未声明**（全部走内联默认），且内联默认与 `DEFAULT_TIMEOUT_MS` 是**同一数值的两处声明**：本轮只**钉住相等**、**未合并**（删内联默认＝改既有配置语义、删常量＝改类内语义，均属**门②**邻域）⇒ **只登记不动**。
7. 形态映射表只覆盖 `SimpleClientHttpRequestFactory`（`HttpComponentsClientHttpRequestFactory` 等未登记）：换成别的工厂形态时，①会红、需显式登记 —— 但**本轮不证明**该形态在别处不存在，只证明**当前** main 树里没有。
8. 「平台→商城客户端」在本仓库**不存在**；若正式范围要求它，属**范围/新组件**问题（门⑧/⑩），本轮**未**擅自新建。
9. `spark`／`isolated` 档**未重跑**；门禁仍 `[FAIL exit=7]`（唯一已登记环境性红）⇒ **不得**简写成「门禁已通过」，**亦不得**称「本轮让门禁变绿」。

---

## §8 顺带台账

- 滚动执行位置块（5 段／9 行，`docs/PROJECT_STATUS.md` L23/25/27/29/31）同步为本轮口径。
- 阶段 6 记录：在 `### 已完成` 之前追加 **7 条 S3-47 bullet**（现 L392–L398）。
- backlog 行 **L467**（「**S3-19 遗留 R9**」）**在原行末单元格内**追加「**（S3-47 更新描述，不删行、不改判类，2026-09-16）**」实测更正 —— 平台侧的请求级超时**不是没做**、已加结构守卫钉住，但**本行仍不关闭**（重试／幂等／错误映射两侧均未实现），**原判类保留**（追加后现 L474）。
- **新增 1 行**（紧接 S3-46 行下方，现 L529）＝「**S3-47 后 L85 平台侧残余面**」（有限重试／幂等／错误映射未实现 ＋ `LlmException.type()` 零消费方 ＋ `llm.timeout-ms` 未在 `application.yml` 声明且两处声明同一数值 ＋ 形态映射表未覆盖非 `SimpleClientHttpRequestFactory` 工厂 ＋ 「每个客户端」的适用范围属门⑧/⑩；判类＝**待裁决/待批注**）。
- `docs/status-history/开发过程事实与决策记录.md` **追加 F-80**。
- 本文件：`docs/acceptance/s3-47-platform-client-timeout-guard-20260916/DESIGN-DIFF-REGISTER-20260916.md`。

---

## §9 复现命令

```powershell
# 0) 内环单类（ai-decision 模块）
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f 'D:\Develop_code\GraduationProject-wt\v3-dev\analytics-server\pom.xml' `
  -pl ai-decision -am test '-Dtest=PlatformOutboundClientTimeoutGuardTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'

# 1) 改前事实核对（本轮 §1 的检索；工作目录＝worktree）
git grep -n 'RestClient.builder()\|RestClient.create(\|HttpClient.newBuilder()\|WebClient.builder()' -- 'analytics-server/*/src/main/java'
git grep -n 'setConnectTimeout\|setReadTimeout\|SimpleClientHttpRequestFactory\|RestClient' -- 'analytics-server/*/src/test/java'
git grep -n 'llm' -- 'analytics-server/platform-app/src/main/resources/application.yml'

# 2) 变异探针（5 条；改前字节备份、改后按字节还原并 sha256 校验）
pwsh -NoProfile -File '.verify\s347_probes.ps1'

# 3) 门禁两轮（默认档）
pwsh -NoProfile -File 'scripts\run-tests.ps1' -Suite default -RunId s347-1 -LogDir '.verify\logs' -AllowCountDrift -Confirm
pwsh -NoProfile -File 'scripts\run-tests.ps1' -Suite default -RunId s347-final -LogDir '.verify\logs' -Confirm
```
