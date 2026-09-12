# M1-9 ②「第二家商城适配器」验收记录（泳道交付 + 独立复核 + 修复轮）

- 日期：2026-09-12
- 分支：`remediation/r1-boundary`　交付提交：`25b0fe7`（父 `f289528`）
- 本文件只记录**已实测**的事实；推断、未取证、裁决分别落在 §6 / §11 / §4。
- 上游：`docs/acceptance/m1-9-contract-first-20260912/README.md`（① 契约先行，含 T1–T8 判据表）
- 复核原件：`review-spec-compliance.md`（本目录，独立只读复核，**原样归档、未改一字**）

---

## 1. 任务与范围

**任务**：在生成器模块证明「换一家商城」不需要动引擎——第二家商城的路由、字段词表、信封、状态词、金额单位、能力缺口全部由**适配器**承载，引擎对"哪一家商城"零知识（硬约束 6/7/9）。

**范围（本轮做到什么）**

- 生成器模块 `synthetic-data-generator`：新增一个适配器 + 登记处 1 行 + 引擎/SPI 去商城化。
- 验收级别：**E1**（编译）+ **E2**（模块自动化测试）；第二家**是夹具**（`SecondMallFakeServer`，本机 `HttpServer`）。
- 独立复核：只读规格符合性复核（不跑构建）+ 总控自建复核器 `verify-v2.ps1` 实测。

**范围（本轮明确没做）**

- 没有接入任何**真实**第二家商城（P5 异种源 B 的范围，见看板 `P5-03`）。
- 没有改契约（`contract-specs` 仍 `1.3.0`；DRAFT 状态不变）、没有改数据仓库 DDL、没有跑集群（E4）、没有交付员工可用页面（E5）。
- 没有跑真实链路 T2（真实本地 MySQL/Spark/Hive 黄金链）——本轮的测试都在夹具与本机进程内。

## 2. 交付物

| 类别 | 产物 | 关键事实 |
|---|---|---|
| 泳道提交 | `25b0fe7` | `+2606/−75`，**15 个文件全部在代码侧**（`docs/**` = 0） |
| 适配器 | `adapter/SecondMallHttpAdapter.java` | 939 行新增：自有 `/open/v2` 路由、字段词表（`sku/title/unit_price_cents/state`）、信封 `{success,result,errMsg}`、状态词映射、整数分→元 |
| SPI/引擎去商城化 | `adapter/MallTargetAdapter.java`(22/0)、`adapter/TargetRoute.java`(30/0)、`engine/MallDispatchPlan.java`(24/25)、`engine/MallApiDispatchSink.java`(96/31)、`engine/MallApiGenerationEngine.java`(93/9)、`adapter/ExternalProduct.java`(10/1)、`engine/OperationJournalEntry.java`(1/1) | 路由改由适配器自报（`operationRoutes` 默认空表）；计划不再持有 `method/route` 分量 |
| 词表 SPI | `adapter/MallStatusVocabulary.java`（新文件，28/0） | 见 §6 S4：**规格未定义的新 SPI**，修复轮处置 |
| 登记处 | `config/GeneratorBeans.java` | `+3/−1`：1 条 import + 1 条既有注册行加逗号（**同一核**，无逻辑删除）+ 1 条新元素 |
| 测试 | `fixture/SecondMallFakeServer.java`(464/0)、`adapter/SecondMallAdapterOperationsTest.java`(332/0)、`engine/SecondMallDualTargetTest.java`(447/0)、`boundary/GeneratorBoundarySourcePolicyTest.java`(**+72/−0**) | 夹具为独立实现（非参考商城子类） |
| 复核器 v1（交付前冻结） | 本目录 `scripts/verify.ps1` | 199 行 / 14,279 B / CR=0 / sha256 `9EA99E262E47FD7B7175F4CEF59BE254918D522B565684000773C7AA04ECC507` |
| 复核器 v2（**判据修正件**） | 本目录 `scripts/verify-v2.ps1` | 255 行 / 21,490 B / CR=0 / sha256 `3BD69BCF855F56B2249991C3AD131E2F33FA646AFD716E9BD3CD842A34F062D6`；修正理由见 §5 勘误 E-1/E-2 |
| 复核原件 | 本目录 `review-spec-compliance.md` | 241 行 / 31,180 B / sha256 `AAA0358242AD6691E780A3142E246BDF1972109342D5A5D1D6EF7F64863218A4` |

> `.verify/**` 是本地工作目录（`.gitignore` 内，不入库）。所有作为结论依据的复核产物都已归档进本目录；本地原始日志的路径与哈希见 §3。

## 3. 方法与证据级别

| 项 | 级别 | 命令/方式 | 结果（原始证据） |
|---|---|---|---|
| 编译 | **E1** | `mvn -o test -f synthetic-data-generator/pom.xml` | BUILD SUCCESS |
| 模块自动化 | **E2** | 同上（**不加** `-DforkCount=0`） | 合计行 `Tests run: 111, Failures: 0, Errors: 0, Skipped: 0`；`Total time: 22.818 s`；**19** 条类级 `Tests run … -- in` 行 |
| 并发写者防护 | — | 跑测前后对 `synthetic-data-generator/src/**` 全量 sha256 | 前后文件 **95 行全等**（`hashes-before.txt` == `hashes-after.txt`，均 15,207 B / sha256 `566CC34A103A53E543512D5CEEFC5CCC0958D7100FC25F9D27C8E7659CB491DE`）⇒ 该结果确实属于 `25b0fe7` 的源码 |
| 独立复核（人读） | 静态 | 只读复核，**未跑构建** | `review-spec-compliance.md`：判据 8 通过 / 3 不通过 / 1 无法取证（其口径见该文件 §1） |
| 独立复核（机读） | E2 日志 + 源码扫描 | `scripts/verify-v2.ps1 -LaneCommit 25b0fe7 -LogPath …` | **PASS=16 / FAIL=1**，退出码 1（唯一 FAIL = V12a2 T8，裁决见 §4.4） |
| 真实链路 | **E3 未做** | — | 第二家是夹具；未跑真实本地商城链路 |
| 集群 1000 行 | **E4 未做** | — | 归 T2 复跑 |
| 员工可用页面 | **E5 未做** | — | 与 M1-9 无关 |

本地原始日志（不入库，哈希供后续核对）：

| 文件 | 字节 | sha256 |
|---|---|---|
| `.verify/m1-9-verify/e2-full.txt` | 23,568 | `FFB74D65ADF3668496EA34AE5FCCD4D8F16788B56385F501504E018485CA598F` |
| `.verify/m1-9-verify/e2-full.err.txt` | 277 | `B64EE4F4022CE0669F82B0A2407BAB130C7E6CA913707F29EA73F8EDF7D8A27E`（仅 `Picked up JAVA_TOOL_OPTIONS` + CDS 提示） |
| `.verify/m1-9-verify/verify-v1-asrun.txt` | 2,839 | `207538EE2A492DD9C4D3D3AAA15AF2CA409D5E3D0C2D5A621CB7A3C24BA98C6B`（v1：8 PASS / 5 FAIL，12:00:53） |
| `.verify/m1-9-verify/verify-v2-asrun.txt` | 0 | —（v2 首版**解析失败**，见 E-2） |
| `.verify/m1-9-verify/verify-v2-asrun-2.txt` | 4,097 | —（17 PASS / 0 FAIL，但 V11 证据串重复，作废重写） |
| `.verify/m1-9-verify/verify-v2-asrun-3.txt` | 0 | —（再次解析失败，见 E-2） |
| `.verify/m1-9-verify/verify-v2-asrun-4.txt` | 3,996 | `64D44ACCB7E87887CD40304687CED1EAA3F2C1B4C6CD4B4ADDF8547EF5F6DC09`（V12a2 修正前：17 PASS / 0 FAIL —— **该 0 FAIL 含一处判据误设，见 E-2**） |
| `.verify/m1-9-verify/verify-v2-asrun-5.txt` | 4,205 | `47E09B48960118B3340FF6110867A0D0BC47DAECF0CE825CC5AE9B175B89DEFF`（定稿：16 PASS / 1 FAIL） |

## 4. 裁决（总控）

### 4.1 S1 订单状态别名后缀 —— 判为**必须删除**
`SecondMallHttpAdapter:154-158` 的 `ORDER_STATE_ALIAS` 与 `readOrder` 产出的 `NEW(CREATED)`/`SETTLED(PAID)`/`VOID(CANCELLED)` 违反指导书 §4.1.1.3「商城原样文本」：别名是**我们发明的**第二个词表，会让同一流水字段同时存在 `PAID` 与 `SETTLED(PAID)` 两种口径（同族于 D12/F-25「静默改写证据」）。虽然当前 `ExternalOrder.paid()/cancelled()` 全仓库无调用点（无控制流影响），仍必须删除 ⇒ 修复轮 D-A。

### 4.2 S2 `config_json.format == "open-v2"` —— 判为**保留键但必须点名 + 门控自洽 + 声明非契约**
该键全仓库只出现在本适配器与两个新测试里；`contract-specs` 与迁移脚本 `V1__generator_meta.sql:18` 只说 `config_json` 是任意 JSON，**没有**规定键名。裁决三件：(a) 能力缺口的报错文案必须**点名 `format`**（否则漏写该键时使用者看不懂为何三能力全 UNDETERMINED）；(b) 同方法内 `behavior` 分支不得绕过同一门控（口径不自洽）；(c) 读取处必须写明「这是本适配器/夹具约定的键，**不是契约的一部分**」⇒ 修复轮 D-B。

### 4.3 S3/S4/S5/S6/S7 —— 逐条裁决
| 编号 | 复核发现 | 裁决 |
|---|---|---|
| S3 | 三能力位不探测直接 `ABSENT` | **保留 ABSENT 声明**（判据 V7 要求显式声明缺口），但 `reason` 必须明说「静态声明、未探测」，并与 `UNDETERMINED`（不知道）区别开 ⇒ D-C |
| S4 | 新增公开 SPI `MallStatusVocabulary` + 引擎 `instanceof` 旁路 | **删引擎旁路**：引擎对任何"商城词表"零知识（硬约束 7/11）。适配器侧类型可留；「有未映射词」这一信息若需要，改由既有 DTO/流水字段承载 ⇒ D-D |
| S5 | 累计型实例状态被当成"本次运行"事实（单例 + 只增不减） | **判为缺陷**：同一 JVM 先跑 A 家再跑 B 家会跨目标污染（正面冲突硬约束 9）。计数器改按运行作用域；本质为进程级累计的指标须改名并排除出「本次运行」报告 ⇒ D-E |
| S6 | 每单多打一次 listProducts（N+1）+ 价格缺失静默带 null 继续 | **判为缺陷**（静默降级）：去 N+1；价格缺失必须响亮 ⇒ D-F |
| S7 | `MISSING_STATE_WORD="(商城未给 state 字段)"` 混入"未映射状态词"集合 | 用独立标记/独立计数表示"字段缺失" ⇒ D-G |
| W1/W2 | 新测试用 `startsWith("NEW")` 与 `split("\\(")[0]` 主动截掉别名 | 改为**严格断言**（`assertEquals("NEW", …)`）——这是「契约未被强制」的最强反证，与 S1 同批修 ⇒ D-H |
| W3 | 新测试把「未探测即 ABSENT」固化成被测契约 | 断言改为「值 + 未探测说明」，不得把未探测断言成已测量 ⇒ D-H |

### 4.4 T8（变更面守卫）—— 判为**结构性转序，本轮不声称通过**
- T8 的**权威文本**（① 契约先行 `README.md:149`）：「T8｜**变更面守卫**：新增一家商城只动"1 个新适配器文件 ＋ 登记处 1 行"｜硬约束 7」。
- 实测：测试树内**没有**这样的守卫（含 `变更面`/`T8` 守卫方法或 `DisplayName` 的文件数 = **0**）。已交付的 `boundary/GeneratorBoundarySourcePolicyTest:181` 守的是「engine 无商城路由字面量」——那是硬约束 7 的**另一半**，不是 T8。
- 裁决：T8 的前置条件是「首家的写死已被拆除」，而**拆写死（引擎/SPI 去商城化）正是本轮主体内容**（引擎/计划/流水 5 个文件被改）⇒ T8 对「本轮」**结构性不可满足**；其实质是**下一家商城**的回归守卫，**转 `P5-03` 落地**。本轮的变更面守卫由复核器 V1 承担（15 文件 ⊆ 白名单、docs 侧 0）。
- 因此 §3 的机读结论 **PASS=16 / FAIL=1、退出码 1** 是按纪律如实报告的：FAIL 一律计不通过，不因"已裁决转序"而改成 PASS。

### 4.5 判据修正（v1 → v2）的治理口径
复核者提出治理质疑：「判据在交付后被追加/放宽，与 `d260fa9`『判据在泳道交付前冻结』的初衷相反」。答复记录在 §5 勘误 E-1/E-2，口径三条：

1. **v1 原件与 as-run 原样保留**（文本 sha256 与 2,839 B 输出都在 §2/§3），不做任何历史改写。
2. v1 的 5 条 FAIL 中，**3 条是判据口径缺陷**（V1 读工作区 `git status` 而非提交面、且受 stat-dirty 影响；V11 要求字面 `+1/−0` 而双适配器注册必然 `+3/−1`；V12b 正则取到**首条** `Tests run` 行）、1 条是**误报**（V10 的 3 处命中全在 `SecondMallFakeServer` 的 Javadoc 对照说明，非注释行 0 命中）、1 条是**真实未落**（V12a 的 T8）。
3. v2 **同时变严**：新增 V0（工作区与提交内容级漂移守卫，非零即宣告评审无效）、V13（未新增 `@Disabled/@Ignore/assumeTrue`）、V14（无 32 位十六进制凭据字面量）；V10 改成只看**非注释行**。且 v2 的修正使本轮结论**从 17/0 降为 16/1**（V12a2 按权威文本重写后如实 FAIL）。
4. 由此登记 **F-33**（v1 三条判据口径缺陷 + v2 修正件）与 **F-34**（T8 未落 + V10 注释误报），owner 单一；后续轮次的纪律：判据文本冻结后，**口径歧义必须在交付前用实测消歧**，否则按勘误链登记，不得静默放宽。

### 4.6 修复轮（D-A … D-H）
- 状态：**已派发实施中**（同一模块，单一实施泳道；不改文档、不改契约、不提交）。
- 完成判据：S1–S7 + W1–W3 逐条落地或逐条给出「不能修」的证据；E2 合计行 ≥111 且 0 失败/0 错误/0 跳过；**未新增** `@Disabled/@Ignore/assumeTrue`；既有测试断言若有改动须逐条说明「原来断言什么 / 现在断言什么 / 为何等价或更严」。
- 结果、原始日志与总控复核：**见 §13 补记**（本节在修复轮交付前不得被读成"已修"）。

## 5. 勘误（按时间顺序，append-only）

- **E-1（判据口径）**：v1 的三条口径缺陷见 §4.5 第 2 条；对应事实登记 **F-33**。v2 定稿 sha256 `3BD69BCF855F56B2249991C3AD131E2F33FA646AFD716E9BD3CD842A34F062D6`。
- **E-2（v2 自身的两次失误，均在出结论前发现）**：(a) v2 首版第 196 行嵌套引号不平衡 ⇒ 两次解析失败（as-run 落 0 B，记录已保留：`verify-v2-asrun.txt`、`verify-v2-asrun-3.txt`），用 `Parser::ParseFile` 定位到「行 196 列 9：字符串缺少终止符」后按行号拼接修复；(b) v2 首版把 T8 误按「engine 无商城字面量」断言，因而在 `verify-v2-asrun-4.txt` 得 17 PASS / 0 FAIL —— 该 0 FAIL **含一处判据误设**；按 T8 权威文本改写为「变更面守卫是否已落地为测试」后实测 FAIL（`asrun-5`）。两处失误均按纪律保留原始记录、不删不改。
- **E-3（复核报告的计数）**：`review-spec-compliance.md` §1 记「提交面 16 个文件（多 `RULINGS.md`）」。实测 `git diff --name-only 25b0fe7^..25b0fe7` = **15** 个、且 `25b0fe7^` == `f289528`；`RULINGS.md` 属 `f289528`（`1 file changed, 175 insertions(+)`）。16 这个数来自把 `f289528` 计入的区间。**报告原文不改**，此处登记勘误；报告该条的**实质结论（v1 的 V1 读工作区、覆盖不到提交面）成立**，不受影响。
- **E-4（既有残留）**：工作区里 `docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log` 长期为 ` M`（8091 进程运行期日志，F-26 已登记为既有残留），本轮不计入任何变更面。

## 6. 声明 vs 实现

| 声明 | 实现到什么程度 | 证据 |
|---|---|---|
| 「换一家商城不动引擎」 | 引擎/计划/流水已去商城化：engine 内商城路由字面量 0 命中（11 个文件），路由由适配器自报 | `verify-v2.ps1` V4/V5/V6 + 复核 §3 |
| 「第二家的路由/字段/信封/状态词/金额都不同且能跑通」 | 夹具层面成立（本机 `HttpServer`，`127.0.0.1:0` 随机端口） | T1–T7 用例 + E2 日志 |
| 「能力缺口不伪造成功」 | 三能力位声明 `ABSENT` + 调用响亮失败；**但声明是静态的、未探测** | S3 裁决 D-C |
| 「双目标不串台」 | 路由/流水不串台（V6/V7 + T6）；**但**存在累计型实例状态的跨运行污染 | S5 裁决 D-E |
| 「引擎对商城零知识」 | 字面量层面成立；**但**新增了引擎对 `MallStatusVocabulary` 的 `instanceof` 旁路 | S4 裁决 D-D |
| 「源码守卫有检出能力」 | 正向对照成立（参考适配器命中 15 处）；**盲区**：守卫正则要求 `"/` 后紧跟 `[a-z0-9]`，大写 `/API/…` 逃检（未取证的风险，非残留） | 复核 §6 R5 |
| T8 变更面守卫 | **未落**，转 `P5-03` | §4.4 |

## 7. 未解决（本轮不做完的事）

1. **修复轮结果未知**（§4.6）——在 §13 补记之前，S1–S7/W1–W3 一律按**未修**对待。
2. **真实第二家商城**不存在：本轮第二家是夹具 ⇒ 真实分页（夹具忽略 `offset/limit`，只支持 `category_code`）、幂等、限流、错误码体系**全部未取证**。
3. `refund` 能力不支持 ⇒ 涉及退款的链路只能走两步流水口径（限制已登记）。
4. T8 转序 `P5-03`（§4.4）。
5. `contract-specs` 变更批次（CT-1/CT-2/CT-3）与 **CT-0（F-31 勘误登记但未落地）** 仍挂在 P2 序列，与本轮无关但同属未闭环项。
6. 工作区并发写者（F-32，`codex.exe` 同目录写入）未根治 ⇒ 本轮以 V0 漂移守卫 + 前后指纹环绕取证。

## 8. 变更清单（本轮新增/修改的**文档与脚本**）

| 文件 | 说明 |
|---|---|
| `docs/acceptance/m1-9-second-adapter-20260912/README.md` | 本文件 |
| `docs/acceptance/m1-9-second-adapter-20260912/review-spec-compliance.md` | 独立只读规格符合性复核原件（原样归档） |
| `docs/acceptance/m1-9-second-adapter-20260912/scripts/verify.ps1` | 交付前冻结的复核器 v1（原样保留） |
| `docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-v2.ps1` | 判据修正件 v2（§4.5） |

> 代码侧变更全部在 `25b0fe7`（15 文件）与后续修复轮提交中，本文件不重复登记其逐行内容。

## 9. 反熵声明

- 本轮**只增不删**：新增复核器与复核原件，未改写 `verify.ps1`、未改写任何历史验收记录、未改写看板既有行。
- **单一 owner**：判据与复核器归 `verify*.ps1`（owner：总控）；规格符合性结论归 `review-spec-compliance.md`（owner：独立复核泳道）；看板只承载状态与指针，不复制证据细节。
- `.verify/**` 不入库 ⇒ 凡作为结论依据的产物均归档进本目录；本地日志仅以「路径 + 字节 + sha256」被引用。
- 并发写者场景下的取证纪律：跑测前后全量指纹（§3）+ V0 漂移守卫。

## 10. 不得声称

- 不得声称 **M1-9 完成**：② 是**夹具级**交付 + 独立复核 16/1（唯一 FAIL = T8 转序），且复核发现 7 处自造语义与 3 处弱断言；修复轮结果见 §13。
- 不得声称「已适配第二家**真实**商城」——第二家是夹具。
- 不得声称 T8 通过。
- 不得声称 S1–S7/W1–W3 已修（在 §13 补记之前）。
- 不得声称分页/幂等/限流/错误码已取证。
- 不得声称契约已变更（`contract-specs` 仍 `1.3.0`，DRAFT 不变）。
- 不得声称本轮提供了 E3/E4/E5 级别证据。
- 不得把 `git status` 层面的"干净"当作交付面证据（v1 的 V1 就是这样失效的，见 F-33）。

## 11. 未取证清单（honest）

1. 未跑真实第二家商城（无此对象）；夹具只覆盖其自身的契约。
2. 分页（`offset/limit`）、幂等键、限流、错误码分层：**均未取证**。
3. `refund` 路径未跑（能力声明为不支持）。
4. 未跑集群/真实链（E3/E4 未做）；未做真实 MySQL/Hive 落库核对。
5. 未测并发多目标同时运行的资源竞争（只测了同 JVM 顺序双目标）。
6. 未测异常路径下的流水完整性（网络中断、超时、半途失败）。
7. 大写路径字面量逃检（复核 §6 R5）未构造用例验证。
8. 未验证 `GeneratorMetaStoreTest:58` 的既有 `assumeTrue(false, …)` 在 `generator_meta` 不可达时是否会让「Skipped=0」失败（本次 E2 中未触发）。
9. 修复轮前的 S1–S7/W1–W3 全部按未修对待；修复轮的 E2 与总控复核见 §13。

## 12. 下一步

1. 修复轮落地（S1–S7/W1–W3）→ 总控独立复核（重跑 E2 + 源码级逐条核对）→ §13 补记 → 提交推送。
2. `P5-03`：T8 变更面守卫 + 真实第二家（异种源 B）。
3. 代码质量评审（在修复轮定稿后跑，避免评到将被改动的代码）。
4. 看板同步：M1-9 行状态 + §5.1 登记 F-33/F-34/F-35/F-36 + §6 日志行。
5. 回到 P2 序列（P2-01 ODS v2 规格 → CT-1/CT-2/CT-3 契约批次）。

---

## 13. 补记

（待修复轮交付与总控复核后追加；追加时只新增内容，不改上文。）

### 13.1 补记一（2026-09-12 12:5x）：修复轮验收 —— S1–S7 / W1–W3 处置与残余

**验收方式声明**：以下数值**全部由总控自己产生**；修复泳道的自述只作线索，**不作证据**。

1. **自跑 E2（修复后最终树，含死类型退休）**：`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` ＋ `BUILD SUCCESS`，`exit=0`，21.625 s。原始日志 `.verify/m1-9-verify/e2-final-20260912-123946.out.txt`（23,939 B，sha256 `505B8AC10D8E1733F389579AFABEEDFD56736086E70708F3E44F0D10E4EB386C`）。用例数轨迹：基线 **100** → 泳道交付 111 → 修复轮 **116**（新增 T8/T8b/T9，无改名、无删除、无跳过）。
2. **判据复核（判据先冻结、后执行）**：v2（sha256 `1E73812754A3A17F53EFDDAFCA7DA235A33C56CEF017CFE9352CFDDF07A165BA`，已入库）对**同一份日志**实测 **17 PASS / 1 FAIL**；唯一 FAIL 经根因诊断为**判据自身的解析缺陷（假红灯）**，见 13.3。据此出具**只含 (修-6) 的追加修订版** v3（**提交 `bd92f65`，冻结于执行之前**；288 行 / CR=0 / sha256 `AECB32FC322FB362C51C70C905B35EBE485AD75AE87B1B7BAB6E61E6456B5270`），对**同一份日志**实测 **18 PASS / 0 FAIL，`exit=0`**（报告 `.verify/m1-9-verify/w3-run-20260912.txt`，sha256 `58E69B80062890BEDB349E26AEDB3B2D2C24BD6A24D5DB6379AECB74D31B41F2`）。**v3 未放松任何阈值**，只把 W12 的解析改成能读真实 surefire 日志的形状，并**增**了一条正向对照。**两版读数一并报告，不掩盖 v2 的那条 FAIL。**
3. **变更面**：`git status --porcelain -- synthetic-data-generator/src` = **11 条（10 M ＋ 1 D）**，与泳道报告**逐文件 sha256 11/11 全等**（总控自算）。W1 实测「清单外=0、清单 18 条」。
4. **人读**（不仅看机械读数）：S1 原词透出与无兜底映射（`SecondMallHttpAdapter.java:158-175`、`:786-802`）；S6 响亮失败（`MallApiDispatchSink.java:382-393`）与新渲染（`:398-411`）；S2/S3 运维文案（`SecondMallHttpAdapter.java:576-595`）；新证据用例 T2b/T8/T9/「跨运行不串台」/「商城未给状态字段」的 `@DisplayName` 与断言方向（含反向断言：明细不得出现 `UNKNOWN`/`CREATED`）。
5. **复核器 W0 自证**：复核期间 `src` 指纹 **93 → 93、漂移 0**（含"文件被删"这一最坏情形）。

### 13.2 处置表（S1–S7 / W1–W3）

| 项 | 严重度 | 处置 | 总控实测证据（v3 读数） | 判定 |
|---|---|---|---|---|
| **S1** 订单状态 `原词(别名)` 复合格式 | 最重 | **删别名表**，只透出商城原词 | `ORDER_STATE_ALIAS=0`；别名词字面量 `"(CREATED\|PAID\|CANCELLED\|NEW)"=0`；正向对照 `readOrder=4` | **已消除** |
| **S1 同类面** 三个 DTO 造 `status="UNKNOWN"` | （人读发现，机械判据漏） | 空白 → `null`（"商城未给"），**不再编词**；需要非空处以响亮失败收口 | `adapter/**` 内 `= "UNKNOWN"`/`? "UNKNOWN"` = **0**、`"UNKNOWN"` 提及 = 0；正向对照 adapter 文件数 24、含 `record` 文件 21；新增用例「缺状态」「未给状态」 | **已消除** |
| **S2** 魔法串 `config_json.format=="open-v2"` 把门、报错不点名 | 重 | **单点常量** `CONFIG_FORMAT_KEY`/`CONFIG_FORMAT_VALUE`；运维话术点名键与取值；声明"非契约键" | 代码行 `"format"` 字面量 = **1**（单点定义；全文亦 1）；`CONFIG_FORMAT_KEY` 引用 7、`CONFIG_FORMAT_VALUE` 引用 6；文案 `:582-591` 逐字含 `config_json.<KEY>=<VALUE>`，并否掉「OPTIONS 通过＝已按格式声明」的误读 | **已消除**（键仍属约定，见 R-b） |
| **S3** 三能力位不探测直接 `ABSENT` | 中 | 按裁决**保留 `ABSENT`**，口径改为「静态声明（未探测）」并写进 Javadoc 与运维文案 | 「未探测/静态声明」命中 15、`ABSENT` 18；`:593-595` 原文含"不要把这三个 ABSENT 读成「测过所以没有」"；测试侧 `:384-391` 三条 `ABSENT` ＋ **新增** `BEHAVIOR==UNDETERMINED` 对照 | **口径已披露；语义未变** ⇒ 残余 **R-a** |
| **S4** 新公开 SPI ＋ 引擎 `instanceof` 旁路 | 中 | ① **删引擎旁路**；② 类型本体**退休**（零实现者、零消费者） | `engine/**` 内 `MallStatusVocabulary=0`、其 `instanceof=0`，正向对照 `operationRoutes=29`；全仓库该类型仅剩自身文件 2 处 ⇒ 已移出仓库（见 R-e） | **已消除** |
| **S5** 累计实例状态冒充"本次运行"事实 | 中 | **改按运行作用域**：改由方法参数与单次调用返回值承载，缺口记在运行级 sink | `unmappedStatusWords` 全 `src` 0（原仅存于已退休接口）；适配器实例字段仅 `credentialLookup`/`timeout`/`mapper`（无集合型累计）；`describeUnmappedStatuses(products, unmappedStateWords, stateFieldMissing)` 为 `static`；`ProductPage.unmappedStateWords()/stateFieldMissing()` 每次读取 `List.copyOf` 快照；用例「跨运行不串台」（同实例、两目标）命中 6 | **已消除**（诊断未丢） |
| **S6** 下单链路 N+1 补价 ＋ 价格静默降级 | 轻 | **整体删除**补价动作（不是"改成响亮"）：`createOrder` 不再读目录 | `catalogPricesBySku=0`；`createOrder:368-386` 仅组装 `buyer_ref`＋`lines` 后下单；「价格缺失」用例命中 11（含 T2b「必须响亮失败，绝不静默 null 或当成 0 元」） | **已消除** |
| **S7** 缺字段被编码成中文句子常量 | 轻 | **拆成独立通道**：字段缺失 vs 未登记词分列 | `MISSING_STATE_WORD=0`、`"(商城未给 state 字段)"=0`，正向对照 `stateFieldMissing=1`；实现侧 `:793-801` 两条通道互不混入 | **已消除** |
| **W1** `startsWith("NEW")` 前缀容忍 | 中 | 改**精确等值** | `startsWith("NEW")=0`；`assertEquals("NEW")=6` | **已改强** |
| **W2** `split("\\(")[0]` 自截断 | 中 | 改**精确等值** | `split("(")=0`；`assertEquals("SETTLED")=3`；正向对照断言总数 62 | **已改强** |
| **W3** 把"未探测即 ABSENT"固化成规格 | 中 | **重述为静态声明口径** ＋ 补 `UNDETERMINED` 反向对照 | 见 S3 行 | **已改强** |
| 断言总量（防"改口径变相削弱"） | — | — | 基线 → 工作区：`SecondMallAdapterOperationsTest` 79→**135**、`SecondMallDualTargetTest` 46→**66**、`GeneratorBoundarySourcePolicyTest` 20→20；新增行中削弱语句 **0**、疑似凭据 **0** | 通过 |

### 13.3 判据缺口三例（治理条目，教训入库）

1. **按"缺陷点"枚举，而非按"缺陷类"**：v1 的允许清单与 S1 检查是按复核报告点名的 14 个文件写的，于是同一**类**残留（三个 DTO 的 `status="UNKNOWN"` 造值）逃过机械检查，**只被人读抓到**。(修-1)/(修-2) 与 W15a/W15b 已补。教训：清单要么按类枚举，要么在判据里明写"本判据只覆盖点、类由人读负责"——不能靠人恰好想到。
2. **W12 假红灯（判据写错了被测对象的形状）**：W12 原按 `^Tests run: \d+, Failures` 逐行匹配合计行，而 **Maven surefire 的合计行带 `[INFO] ` 前缀**（实测原文 `[INFO] Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`，行尾 CRLF、末字符码 13）⇒ 该正则**在任何真实日志上都匹配不到**，W12 一律判 FAIL。三种候选正则的命中数（同一份日志）：裸 `Tests run: …\s*$` = 原文 0 / 去 CR 后 0；带 `\[INFO\] ` = 20 / 20；带 `(?m)^…$` = 0 / 1。**教训有二**：① 判据必须对着**真实产物的形状**写，并同时具备正向对照（v3 增"逐类行 ≥15"）；② 该缺陷**只有在"第一次真的喂它一份日志"时才暴露**——冻结前从未对真实日志跑过，就是"未取证"的一种，必须显式登记而不是默认为可用。
3. **泳道自报的偏离同样要被复核**：泳道在其报告 §6.2 自报「W1 白名单不含 `ExternalOrder`/`ExternalRefund`/`MallApiDispatchSink`，故运行 W1 必报越界」。总控核验：**该自报不成立**——冻结 v2 的允许清单 **18 条**逐字含这三个文件（其中 `ExternalOrder.java`/`ExternalRefund.java`/`ExternalUser.java` 正是 (修-1) 增补项），W1 实测 **PASS、清单外=0**。⇒ 复核对象不仅是"泳道声称做完的"，也包括"泳道声称做不到/必然失败的"。

### 13.4 残余（**接受的、未消除的**，勿读成已修）

- **R-a（S3）**：`CapabilityVerdict.ABSENT` 在本适配器承担两种含义——「探测后确认没有」与「静态声明没有（未探测）」。本次只做到**披露**（Javadoc ＋ 运维文案 ＋ 用例文案）：三态词汇表**未扩充**、未新增任何探测请求。只读 `ABSENT` 值而不读文案的消费方仍无法区分二者。
- **R-b（S2）**：`config_json.format=open-v2` 仍是**适配器与其夹具之间的约定**，不是契约键（`contract-specs/**` 未定义，全仓库无第三方消费者）。已在文案中声明"非契约键"；若需入规格，属 `P5` 异种源适配窗口。
- **R-c（夹具能力）**：`SecondMallFakeServer` **不实现 `OPTIONS`**（实测 `grep '"OPTIONS"'` 为空），因此 `test()` 对本夹具的 product/user/order 三段**实测判定为 `ABSENT`**（`aggregate()` 见 `MISSING` 即 ABSENT）。故新用例 T9 的"前提"锚在**声明门 `capabilities()`**（引擎预检实际使用的那一个），而非 `test()` 的实测值。这不是本轮引入的缺陷，本轮也未修（若要让 `test()` 实测三段呈 `SUPPORTED`，需夹具支持 `OPTIONS`，属新范围）。
- **R-d（HEAD-clean 的同类残留，只报不改）**：`ExternalOrder.created(...)` 仍返回**自造**的 `"CREATED"`（全仓库**零调用点**，该方法的 Javadoc 自陈商城下单应答只回 `orderId`）；`ReferenceMallHttpAdapter.java:245/259/278` 仍自造 `PAID`/`CANCELLED`/`COMPLETED`（参考商城 pay/cancel/refund 应答为 `ApiResponse<Void>`，`:240-241` 有自陈注释，且被既有用例钉住）⇒ 属既有口径，不在本轮范围，登记为残余。
- **R-e（口径收紧，记变更不追改上文）**：§6 的 D-D 裁决原文是「**适配器侧类型可留**」；本次在其后**退休**了该类型（`adapter/MallStatusVocabulary.java`，2013 B，sha256 `EAAD61AC47B456450222ADF4A968F413A84A66DC4F5788FAF587AA5AB982127E`，零实现者、零消费者、引擎零引用），文件移出仓库（备份 `D:\Develop_code\graduation-lane-backup\fix-round\files\retired-MallStatusVocabulary.java`，git 历史保留 `25b0fe7` 原文）。依据：内部代码退休、不涉契约与持久状态 ⇒ 取 delete-first；留一个"无人实现、无人消费、且 Javadoc 自陈曾是旁路"的公开类型，正是 S4 复发点。**上文 §6 原文不改**，以本条为准。
- **R-f（仪器豁免范围）**：W1b 的自我豁免正则仍写 `verify-fix(-v2)?\.ps1`；v3 已入库（提交 `bd92f65`）故不依赖豁免，正则未改（v3 冻结文本一字不动，避免"边跑边改判据"）。

### 13.5 未取证（诚实标注，勿外推）

1. **真实第二家商城未接入**：Part B 仍是夹具 `HttpServer`（`127.0.0.1:0`）；"适配了第二家真实商城"不成立。
2. **分页／幂等／限流／错误码**未实测；`behavior`／`reset_state` 仍为 `UNDETERMINED`（未探测）；S3 的三能力位是**静态声明**，不是探测结论。
3. 本轮证据级别 **E2（模块自动化）**，**不能**外推到 **E3（本地真实链）** 与 **E4（集群 1,000 行）**。T2 权威 55 条黄金链本轮**未跑**。
4. **W1–W3 的"改强"只做了文本/断言层实测**，未做变异测试（故意破坏实现，确认用例确实变红）。
5. **V 系列复核器（`scripts/verify.ps1`、`verify-v2.ps1`）本轮未运行**——它们面向 `25b0fe7` 的交付面；`verify-fix.ps1`（v1）**不可运行**（破坏性缺陷，见 F-37），只在档案里作为判据文本引用。W9–W12 中 v1 系列对当前 11 文件树的判定**未观测**。
6. 引擎侧"格式声明摘要"话术**未做**（刻意只保留适配器侧一处话术来源，避免第二份来源漂移）。
7. `describeUnmappedStatuses` 只验证了"词表经参数传入、不落实例字段"，未覆盖多目标**并发**下的其它共享点（本轮覆盖的是同实例跨**运行**串台）。

### 13.6 结论

- **可声称**：S1/S2/S4/S5/S6/S7 **已消除**；S1 同类面（DTO 造值）**已收口**；W1/W2 **已改强为精确等值**；W3 **已重述为静态声明口径**；S3 口径**已披露**（判定值按裁决不变）；变更面 11 条**全在允许清单内**；证据级别 **E2**，原始日志与判据报告路径/哈希见 13.1。
- **不可声称**：真实第二家商城已适配；M1-9 ② 整体 `DONE`（无真实异种源、无集群链 ⇒ 仍 `DONE_LIMITED`）；E3/E4 结论；残余 R-a…R-f 已消除。
- **后续**：`P5-03`（T8 变更面守卫 ＋ 真实第二家）→ 代码质量评审（在修复定稿后跑）→ 看板 `M1-9` 行与 §5.1 F-35/F-36 状态同步 ＋ 新增 **F-38**（判据缺口三例）。
