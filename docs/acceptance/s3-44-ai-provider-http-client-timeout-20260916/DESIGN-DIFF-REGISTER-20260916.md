# S3-44 设计差异登记 —— AI Provider HTTP 客户端「可测化 + 请求级超时 + 失败关闭」（阶段6 ④⑤，A 类）

- 日期：2026-09-16
- 轮次：S3-44（V3.0 持续执行模式，goal `goal-dd741915-9d85-4d36-a1ca-c92f6a534702`）
- 分支/worktree：`feature/v3-development` @ `D:\Develop_code\GraduationProject-wt\v3-dev`
- 起点 HEAD：`0a864f2d23f1e44c4ff02343809d426f59f55a31`（S3-43 收口，HEAD == origin）
- 来源：阶段6 完成标准（指导书 L202）与设计 V3.0 **L552**「返回结果显式携带 providerUsed/model/token/耗时/错误…**模型超时后模板成功不记真实模型成功**」、
  **L581**「真实模型试验需正样本、**拒绝样本**、**超时**和**非法 JSON**，不是只检查 Provider 类存在」、**L771**「AI **超时/非法输出/拒绝** ⇒ 标明 template 或 REJECTED，**不假称真实模型成功**」。
  S3-19 已把 AI SQL 侧的查询超时做掉，但**真实 Provider 客户端**这一面此前**零测试**：`OpenAiCompatLlmProvider` 在 ai-decision 的 11 个测试类里**没有任何用例**。
- 类别：**A 类（实现/加性）** —— 同一个类内做三件事：**失败关闭**（空报文/缺 choices/空 content 一律抛错，不再冒充成功）、
  **错误映射**（429≠AUTH、超时=TIMEOUT、5xx=NETWORK、坏报文=FORMAT）、**请求级超时**（`llm.timeout-ms`，默认 30000ms，连接与读取同源）；
  并**新增** 1 个测试文件（20 条用例，进程内 stub HTTP 服务器）。**零** DDL、**零**连库、**零** `contract-specs/**`、
  **零**前端、**零**门禁脚本改动、**零**新依赖、**零**契约变更（§6）。
- 交付面：`.../ai/llm/OpenAiCompatLlmProvider.java`（+126/−12 行，219 行 / 9894 B）
  ＋ `.../ai/llm/OpenAiCompatLlmProviderTest.java`（**新增**，361 行 / 17145 B / **20** 条用例）
  ＋ `docs/**`（本登记、`PROJECT_STATUS`、历史 F-77）

> **本轮不是 S3-19 遗留 R9。** 设计 **L85**「每个客户端有连接/请求超时、有限重试、幂等与错误映射」讲的是**平台→商城客户端**；
> R9（`docs/PROJECT_STATUS.md` L446）与 AI Provider 是**两类不同的超时**，本轮**不得**被读成 R9 已关闭（§7⑨）。

---

## §1 开工前实测（改前取证，可复现）

命令均在 worktree 根执行（`Set-Location D:\Develop_code\GraduationProject-wt\v3-dev`）。

| 编号 | 实测（改前） | 取证 |
|---|---|---|
| F1 | 起点 HEAD `0a864f2`、工作区**干净**；该类**改前 105 行**，**无任何 `requestFactory`**（`RestClient.builder().baseUrl(...).build()`）⇒ **无连接超时、无读取超时** | `git show HEAD:analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/llm/OpenAiCompatLlmProvider.java` |
| F2 | 改前 L92＝`String content = resp.path("choices").path(0).path("message").path("content").asText();` —— 缺 `choices`／空数组／缺 `content` 时 Jackson `MissingNode.asText()` 返回 **`""`**，随后 `new AiResponse("", …)` **当成功返回**（「空内容冒充真实模型成功」） | 同上（L92-96） |
| F3 | 改前失败分类**只有两支**（L99-100）：`e instanceof HttpClientErrorException ? "AUTH" : "NETWORK"` ⇒ **429 被当鉴权失败**，超时/5xx/坏报文**全归 NETWORK**，`TIMEOUT`/`FORMAT`/`RATE_LIMITED` 三个类型**从未产出** | 同上（L97-103） |
| F4 | `LlmProvider.LlmException.type()` 的**消费方为零**：无 API 暴露、无持久化词汇表（`ExplanationService` 只用 `healthCheck()`/`providerName()`，`logCall` 记的是 provider 名而非 type） ⇒ 分类语义是**类内**行为 | `git grep -n 'LlmException\|\.type()' -- analytics-server` |
| F5 | 该类在 ai-decision **11 个测试类里零覆盖**：改前 `git grep -ln 'OpenAiCompatLlmProvider' -- analytics-server/*/src/test` **零命中**；`ExplanationEvidenceTest:132` 只用**假** `LlmException("TIMEOUT","timeout")` 测上层回退 ⇒ **没有任何证据**证明真实 Provider 能**产出** TIMEOUT | 同上 |
| F6 | 该类在**全仓生产代码**里无人构造（除 Spring 容器）：`git grep -n 'new OpenAiCompatLlmProvider' -- analytics-server/*/src/main` **零命中** ⇒ 改构造签名**零外部调用面** | 本仓 grep（§9 命令） |
| F7 | 死代码（**改前就存在**，非本轮引入）：`private final ObjectMapper objectMapper = new ObjectMapper();` 与 `import ObjectMapper/Duration/JsonProcessingException` 在 HEAD **无任何使用点** | 同上（HEAD 全文 grep） |
| F8 | Spring Web **6.1.14** 的 `SimpleClientHttpRequestFactory` **只有 4 个 setter、没有任何 timeout getter** ⇒ 无法用 getter 断言「两个超时都设了」，只能用**行为**断言 | `javap -classpath D:\maven_repository\org\springframework\spring-web\6.1.14\spring-web-6.1.14.jar org.springframework.http.client.SimpleClientHttpRequestFactory` |
| F9 | 异常层次（javap 实测）：`UnknownContentTypeException extends RestClientException`、`ResourceAccessException extends RestClientException`、`HttpMessageNotReadableException extends HttpMessageConversionException`、`HttpServerErrorException`/`HttpClientErrorException` 属 `RestClientResponseException` ⇒ 分类可**按类型层次**判定，**不需要**看 message 文本 | 同上（4 次 javap） |
| F10 | 设计 V3.0 对 AI 的验收要求含**拒绝样本与超时**（L581）、**超时不得假称真实模型成功**（L771）；`QueryTimeoutPolicy` 既有统一口径是 **30s** ⇒ 本轮默认超时取 **30000ms** 与之同量级 | `docs/design/项目设计文档 V3.0.md` L552/L581/L771 |

---

## §2 类别判定（为什么是 A 类，而不是 HARD DECISION）

| # | 硬门禁 | 本轮是否触碰 | 依据 |
|---|---|---|---|
| ① | DROP TABLE/COLUMN | **否** | 无 DDL、无 SQL |
| ② | 改已有字段类型/既有业务语义 | **否** | 只改**一个类内部**的失败处理与超时；`LlmProvider` 接口**一字未改**；对外契约（`AiResponse` 四字段）不变 |
| ③ | 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ | 写/迁移正式 3306 数据 | **否** | **零连库**（stub 是**进程内** `HttpServer`，端口 `127.0.0.1:0`） |
| ⑤ | 切 ACTIVE | **否** | 未触碰运行时/激活面 |
| ⑥ | 改 `contract-specs/**` 已有契约语义 | **否** | §6：本轮主题在 `contract-specs/**` **零命中** |
| ⑦ | 改 V3.0 总体架构 | **否** | 仍是一个 `@Component` + `LlmProvider` 实现；**不引入**HTTP 客户端框架/熔断器/重试库 |
| ⑧ | 改正式项目范围 | **否** | **不加**新端点、**不改**接口清单；只让既有 Provider 行为与设计 L552/L771 一致 |
| ⑨ | 删除已发布功能 | **否** | 未删任何测试/端点/配置项；删的是**本类内两处未实测分支**与**改前即存在的死代码**（§3⑦、§5） |
| ⑩ | 引入 V3.0 未规划大型基础组件 | **否** | **零新依赖**：测试用 JDK 自带 `com.sun.net.httpserver.HttpServer`，生产用 Spring 既有 `SimpleClientHttpRequestFactory` |
| ⑪ | 两种方案造成重大长期架构分叉 | **否** | 超时实现只有一种落点（`RestClient` 的 requestFactory）；不引入第二套 HTTP 发送路径 |

判类：**A 类（实现/加性）** ⇒ 登记 → 自主设计 → 实现 → 测试 → commit → 继续。

---

## §3 本轮冻结的口径（逐条落在代码/断言上）

① **失败关闭（新增）**：`parseSuccess(JsonNode)` —— `resp == null` ⇒ `FORMAT`「模型响应为空报文」；`choices` 非数组或为空 ⇒ `FORMAT`「模型响应缺少 choices 或 choices 为空」；
   `content` 为空白 ⇒ `FORMAT`「模型响应 content 为空」。理由：F2 实测的 `MissingNode.asText()=""` 语义会把「缺字段」伪装成「空字符串成功」。
② **分类判据只用异常类型层次**（不看 message 文本）：已知 `LlmException` 原样透传 → `HttpClientErrorException`（**429 ⇒ RATE_LIMITED**、**401/403 ⇒ AUTH**、其余 4xx ⇒ NETWORK）
   → `RestClientResponseException`（**含 5xx** ⇒ NETWORK）→ 超时（`HttpTimeoutException`/`SocketTimeoutException`/`TimeoutException` 作为 cause ⇒ **TIMEOUT**）
   → `ResourceAccessException` ⇒ NETWORK → `UnknownContentTypeException` 或 cause 为 `HttpMessageConversionException` ⇒ **FORMAT** → 兜底 NETWORK。
   实测到的异常面写进 javadoc（429＝`$TooManyRequests`、500＝`$InternalServerError`(cause=null)、`text/plain`＝`UnknownContentTypeException`(cause=null)、
   坏 JSON＝裸 `RestClientException` + cause `HttpMessageNotReadableException`、连接失败＝`ResourceAccessException`）。
③ **429 不是 AUTH**：限流与鉴权失败是两类（改前 F3 把 429 记成 AUTH）。
④ **未实测分支不留**：删除改前的「裸 `RestClientException` ⇒ FORMAT」兜底思路 —— 变异探针 **P4/C 证明**它在任何实测输入上都不被触发（§5），
   且「未识别的 HTTP 客户端故障」保守归 **NETWORK** 比伪装成**报文格式**问题更安全。同理 `JsonProcessingException` 半支**未实测** ⇒ 从 `hasCause` 中移除（只留实测到的 `HttpMessageConversionException`）。
⑤ **请求级超时同源**：`llm.timeout-ms`（默认 **30000**，`<=0` 落回默认）**同时**设为**连接**与**读取**超时（只设一个仍可能在另一阶段无限等待）；
   实现落点＝`SimpleClientHttpRequestFactory` + `RestClient.builder().requestFactory(...)`。
⑥ **构造注入而非 setter**：`(baseUrl, apiKey, model, timeoutMs)` 四参构造（Spring 单构造注入；测试可注入短超时）；`normalizeBaseUrl`/`normalizeTimeoutMs`/`requestFactory` 为**包私有静态**，便于直接断言。
⑦ **不引入重试**：设计与 L85 的「有限重试」属**平台→商城客户端**面；本轮若顺手加重试，会把 AI 调用变成**隐式放大**（一次失败变多次计费调用）且无实测依据 ⇒ **不做**，登记为口径「本轮明确不做重试」。
⑧ **同文件内加性清理**：删除 F7 的**改前死代码**（未使用的 `objectMapper` 字段与 3 个未使用 import）——**零行为变化**（编译 + 20 用例实测）。

---

## §4 实现面（本轮改了什么）

| 文件 | 变更 | 说明 |
|---|---|---|
| `.../ai/llm/OpenAiCompatLlmProvider.java` | **+126 / −12 行**（105 → 219 行） | 新增 `parseSuccess` 失败关闭；重写 `classify` 五段判据；新增 `DEFAULT_TIMEOUT_MS`/`normalizeTimeoutMs`/`requestFactory` 与四参构造；删死代码（§3⑧）；javadoc 写实测异常面 |
| `.../ai/llm/OpenAiCompatLlmProviderTest.java` | **新增** 361 行 / **20** 条 | 进程内 stub（`com.sun.net.httpserver.HttpServer`，`127.0.0.1:0`，守护单线程）记录**对端实际收到**的 method/path/body/auth/content-type；副作用可编程（状态码、body、content-type、延迟） |

`LlmProvider.java`（接口）、`MockLlmProvider.java`、`ExplanationService.java`、`TextToSqlService.java`：**零改动**。

---

## §5 证据（真跑，非推断）

**异常面实测（改前先量，避免"猜着写分类"）**：临时在 `complete()` 的通用 catch 里打点，只跑 6 条相关用例，日志实测：
`UnknownContentTypeException`（`text/plain`，cause=null）／`HttpClientErrorException$TooManyRequests`（429）／`HttpServerErrorException$InternalServerError`（500，cause=null）／
`RestClientException`（坏 JSON，cause=`HttpMessageNotReadableException`）。打点**已还原**（`sha256` 复核一致）。

**RED-1（先测后码，16 条用例）**：`mvn -pl ai-decision -am test -Dtest=OpenAiCompatLlmProviderTest` ⇒
`Tests run: 16, Failures: 5, Errors: 0`、`maven exit=1`，5 个红**逐条正是缺口**：
① `rejectsEmptyPayload` 期望 FORMAT 实得 NETWORK；② `rejectsMissingChoices` **什么都没抛**；③ `rejectsBlankContent` **什么都没抛**（②③＝「空内容冒充成功」的实测复现）；
④ `rejectsNonJsonPayload` 期望 FORMAT 实得 NETWORK；⑤ `mapsTooManyRequestsToRateLimited` 期望 RATE_LIMITED 实得 **AUTH**。

**RED-2（先测后码，超时面）**：加入超时用例后**编译期红** ⇒ 13 errors（`找不到符号`：`normalizeTimeoutMs`/`DEFAULT_TIMEOUT_MS`；`无法应用构造函数`：三参构造已不适用）
⇒ 证明「超时开关与四参构造**由测试先提出**」，非事后补测。（第二处编译红＝既有 11 个旧用例仍在用三参构造，同轮修为测试内 `provider(...)` 辅助构造。）

**GREEN**：`mvn -pl ai-decision -am test -Dtest=OpenAiCompatLlmProviderTest` ⇒ **`Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、`exit=0`**。

**变异探针 7 条（打在真实被测物上，逐条 maven 复跑，探针后按**字节**还原并复跑基线）**：

| 探针 | 打在 | 预期红 | 实测 |
|---|---|---|---|
| P1 | 删掉 `.requestFactory(requestFactory(timeoutMs))` | 超时用例 | `exit=1`，红＝`timesOutInsteadOfWaitingForever` ✅ |
| P2 | `if (code == 429) {` ⇒ `if (false) {` | 限流映射 | `exit=1`，红＝`mapsTooManyRequestsToRateLimited` ✅ |
| P3 | `if (content.isBlank()) {` ⇒ `if (false) {` | 空 content 关闭 | `exit=1`，红＝`rejectsBlankContent` ✅ |
| P4 | 关掉 `UnknownContentTypeException` 半支 | 非 JSON 拒绝 | `exit=1`，红＝`rejectsNonJsonPayload` ✅ |
| P5 | `if (resp == null) {` ⇒ `if (false) {` | 空报文关闭 | `exit=1`，红＝`rejectsEmptyPayload` ✅ |
| P6 | 关掉 `RestClientResponseException` 半支 | 5xx 映射 | `exit=0` **存活** ❗（见下） |
| P7 | 关掉 `hasCause(HttpMessageConversionException)` 半支 | 坏 JSON 拒绝 | `exit=1`，红＝`rejectsMalformedJsonPayload` ✅ |

⇒ **6/7 按预期红**；探针后 `git status --porcelain` 只剩本轮预期改动、基线复跑 **20/20 GREEN**。
**P6 存活如实登记（不隐去）**：500 的异常 `HttpServerErrorException$InternalServerError` **cause=null**（已实测），删掉该分支后它落到**末尾兜底 NETWORK**，结果同值
⇒ 该分支对**全部实测输入**都是**冗余**的，它的价值只是把「带状态码的响应不得落入 FORMAT 分支」的**顺序意图显式化**，**不是**被测试钉住的独立行为。保留（不改语义），但**不声称**它有独立测试覆盖。

**探针暴露的真实覆盖缺口（已修）**：P4 第一版打在「裸 `RestClientException` 分支」上**存活** ⇒ 说明我原以为「坏 JSON 走裸 RestClientException 兜底」是**错的**
⇒ 先**实测**异常面（见上）再补 `rejectsMalformedJsonPayload` 用例（`{"choices":[{"message":` + `application/json`），并由 **P7** 把它钉住。

**统一门禁（本轮 Java 树有改动 ⇒ 必须重跑，不沿用）**：`.\scripts\run-tests.ps1 -Suite default -RunId s344-1 -LogDir .verify -AllowCountDrift -Confirm`
⇒ `analytics-server 980 (F=1 E=0 S=1)` 明细 **`93+350+169+97+114+157`** ⇒ **`DRIFT(基线 960)`**（**+20 全部落在 ai-decision 94→114 ＝ 本轮新用例**）；
`mall-simulator 13 MATCH`／`synthetic-data-generator 106 MATCH`／**三棵树 `1099`（基线 `1079`）**；
其中 `OpenAiCompatLlmProviderTest` 计入 ai-decision 的 114（**本单类 20/20 绿**，见上）；`[FAIL exit=7]`（**唯一红＝已登记环境性红**
`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61` `expected: 43`，**未修、未复制 manifest、未用开关掩盖**）。
该轮因计数漂移记 FAIL ⇒ **只作量数依据、不作通过证据**。
**换算基线**：`scripts/run-tests.ps1` `$BaselineDefault['analytics-server']` **960 → 980**（并追加同轮注释块说明漂移量与边界），**未改任何命令语义**。
**收口轮 `s344-final-1`**（改基线后复跑、**不加** `-AllowCountDrift`）⇒ `tests=980 MATCH`／`13 MATCH`／`106 MATCH`／**三棵树 `1099`（基线 `1099`）**、
`ai-decision Tests run: 114, Failures: 0, Errors: 0` ⇒ **计数全部 MATCH**；`[FAIL exit=7]` 仍因**同一已登记环境性红**（与 S3-43 收口轮同一根因、非本轮引入）。
`spark`／`isolated` **未重跑**（本轮无 Spark/隔离档改动）。

---

## §6 契约变更（先改契约再改代码）

**无**。实测 `git grep -n 'OpenAiCompatLlmProvider\|llm.timeout-ms\|LLM_API_KEY' -- docs/contracts contract-specs` ⇒ 本轮主题在契约目录**零命中**；
`LlmProvider` 接口签名（`complete`/`healthCheck`/`providerName` + `AiRequest`/`AiResponse`/`LlmException`）**一字未改**，
`LlmException.type()` 的全部取值集合（TIMEOUT/RATE_LIMITED/NETWORK/AUTH/FORMAT）**沿用既有注释口径**、无新增取值 ⇒ 无契约面冲突，故无需先改契约文件。

---

## §7 未测与边界（不得越界表述）

① 本轮证明的是**本类对一个"协议形状 stub"的行为**：**不**证明真实供应商可用性、**不**证明密钥有效、**不**证明模型输出质量；
   **真实 Provider 调用仍为 未测**（无 key、无外网），阶段6 ②（真实调用）**保持未测**。
② stub 只**记录对端实际收到了什么**（method/path/body/auth/content-type）与**按指令回放**，**不复刻**供应商语义（真实 429 body 结构、真实 usage 字段差异、真实流式/分片都未覆盖）。
③ **连接超时的行为未单独实测**：F8 实测该类**无 getter**，且「建连阶段超时」难以确定性构造（不可路由地址在不同网络下行为不同，会变成 flaky 测试）
   ⇒ 只实测了**读取超时到点即 `TIMEOUT`**（P1 钉住）＋**同一数值同源设置连接与读取**的代码事实；**不得**表述为「连接超时已端到端验证」。
④ **不证明默认 30000ms 对真实网络"足够"或"不过大"**：这是配置口径，真实网络下的合适值仍需真实调用实测（环境阻塞）。
⑤ **`llm.timeout-ms` 默认 30000 是行为变更**：改前**无界等待**（可能长时间挂住调用线程）。缓解手段＝一行配置；影响面仅 `llm.mock-enabled=false` 的真实 Provider bean（`mock-enabled=true` 时该类根本不装配）。
⑥ `LlmException` **不携带 cause**（根因只在日志里）：这是**既有形态**，本轮未改 ⇒ 上层拿不到底层异常类型，**不得**声称「上层可按底层异常细分处理」。
⑦ 本轮**不覆盖** `ExplanationService` 的端到端行为（它继续用既有假 Provider 用例）；**不新增** `@SpringBootTest`；**不碰** web 前端与页面文案
   ⇒ **不得**声称「解释页已显示 provider/timeout 状态」。
⑧ 未测：真实超时后底层连接是否被正确关闭/复用、真实 5xx 重试语义；**本轮明确不做重试**（§3⑦）。
⑨ **不得**把本轮读成 S3-19 遗留 **R9**（`PROJECT_STATUS` L446「HTTP 请求级统一超时」）已关闭：L85 的「每个客户端」是**平台→商城客户端**面，
   与 AI Provider 是**两类超时**；R9 **仍然开放**（§6 同纪律）。也**不得**读成「AI 的真实调用已验收」。
⑩ 阶段6 其余项（①④⑤③）本轮未新增改动；**未测项**与阶段7/8 仍需 MySQL/HDFS/Spark/真实网络环境的面**保持不变**。

---

## §8 顺带台账（**不删行、不改判类**）

- `docs/PROJECT_STATUS.md`：阶段6 区块按本轮实测更新（AI Provider 三件事：失败关闭／错误映射／请求级超时，以及**未测边界**）；
  L446 的 R9 行**原行内**追加说明「S3-44 做的是 **AI Provider** 超时，与 R9（平台→商城客户端）**不是同一件事**，R9 未关闭」（**不改判类**）；
  backlog **新增 1 行**登记本轮暴露的**环境阻塞面**：「AI Provider 连接超时行为、默认超时值合适性、真实供应商拒绝/超时/坏报文回放 **未实测**（需真实 endpoint + key）」；
  滚动执行位置块同步（5 段 / 9 行）。
- 未动：`LlmProvider.java` 接口、`MockLlmProvider.java`、`ExplanationService.java`、`TextToSqlService.java`、`web/src/**`、
  `contract-specs/**`、`scripts/run-tests.ps1`（除计数基线，见 §5 门禁）、所有 `V*.sql`。

---

## §9 复现命令

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev'
# 改前取证（起点 0a864f2）
git show 0a864f2:analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/llm/OpenAiCompatLlmProvider.java | Select-Object -Skip 90 -First 15
git grep -n 'new OpenAiCompatLlmProvider' -- analytics-server/*/src/main      # 零命中
git grep -ln 'OpenAiCompatLlmProvider' -- analytics-server/*/src/test         # 改前零命中
& 'D:\Develop\JAVA17\bin\javap.exe' -classpath 'D:\maven_repository\org\springframework\spring-web\6.1.14\spring-web-6.1.14.jar' org.springframework.http.client.SimpleClientHttpRequestFactory
# 单类实测（内环，不是门禁证据）
$env:JAVA_HOME='D:\Develop\JAVA17'; $env:PATH="D:\Develop\JAVA17\bin;$env:PATH"
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -f analytics-server\pom.xml -pl ai-decision -am test '-Dtest=OpenAiCompatLlmProviderTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# 统一门禁（Java 树有改动 ⇒ 重跑）
.\scripts\run-tests.ps1 -Suite default -RunId <runId> -LogDir .verify -Confirm
# 文件体检（绝对路径；Set-Location 不改变 .NET CWD）
foreach($p in @('D:\Develop_code\GraduationProject-wt\v3-dev\analytics-server\ai-decision\src\main\java\com\graduation\analytics\ai\llm\OpenAiCompatLlmProvider.java','D:\Develop_code\GraduationProject-wt\v3-dev\analytics-server\ai-decision\src\test\java\com\graduation\analytics\ai\llm\OpenAiCompatLlmProviderTest.java')){
  $b=[IO.File]::ReadAllBytes($p); $t=[IO.File]::ReadAllText($p)
  "{0}: 字节={1} 行={2} CRLF={3} 裸LF={4} 结尾换行={5}" -f (Split-Path $p -Leaf),$b.Length,(Get-Content $p).Count,([regex]::Matches($t,"`r`n")).Count,(($b|Where-Object{$_ -eq 10}).Count - ([regex]::Matches($t,"`r`n")).Count),$t.EndsWith("`n")
}
```
