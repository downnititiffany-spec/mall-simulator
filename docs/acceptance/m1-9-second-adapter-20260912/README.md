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
