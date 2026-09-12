# M1-9 ②「第二家商城适配器」规格符合性复核（只读独立复核）

- 复核对象提交：`25b0fe7`（父提交 `2c07b4a`）
- 复核角色：只读独立复核员。**未写任何代码、未改任何跟踪内文件、未提交、未运行 Maven/构建/测试、未运行待验脚本**
- 冻结判据：`docs/acceptance/m1-9-second-adapter-20260912/scripts/verify.ps1`（引入提交 `d260fa9`，本提交内**未被修改**，`git diff --name-status 2c07b4a..25b0fe7 -- docs/acceptance/m1-9-second-adapter-20260912/` 为空）。**本复核只按该文件的字面判据出结论，不执行它**
- 权威规格：`docs/项目完整实施指导书 V2.3.md` §4.1.1
- 唯一写入产物：本文件（`.verify/` 已被 `.gitignore:61` 忽略，不触碰跟踪面）

## 0. 复核起点状态（先记账，不修复）

`git diff --name-status 2c07b4a..25b0fe7` 实际 **16 个文件**，而任务描述称 15 个。多出的 1 个是
`A docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md`（文档侧，+175 行）。

复核期间工作区发生过变化（疑似并发代理写入），按时间顺序记录，**未做任何修复**：

1. 复核开始时 `git status --porcelain` 为：
   ```
    M contract-specs/README.md
    M docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log
   ```
2. 复核结束时 `git status --porcelain` 为：
   ```
    M docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log
   ?? docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-v2.ps1
   ```
   即 `contract-specs/README.md` 的改动在复核过程中消失（被还原或提交），并**新出现一个未跟踪的复核脚本 `verify-v2.ps1`**（20748 字节，mtime 2026/9/12 12:02:22；`verify.ps1` mtime 11:27:29 未变）。
   `verify-v2.ps1` 第 17–18 行自述把 V12a 拆成 "V12a1 标识齐全 / V12a2 T8 实质"，等于**第三方也独立发现了本文 §1 的 V12a 结论**。但按任务约定，冻结判据只有 `verify.ps1`，该文件**不计入判据**，仅作为旁证记录。

结论：`8091-stdout.log` 是 8091 进程在跑的运行期日志（持续写入），已被登记为既有残留，不计入本次变更面。

## 1. V1–V12 逐条判定

判定口径：**严格按 `verify.ps1` 里实现的那一行表达式**，而不是判据标题的中文。因此下表在"实现口径"列写清实际比的是什么。

| 判据 | 判定 | 判据实现口径（verify.ps1 行号） | 证据（file:line + 原文） |
|---|---|---|---|
| V1 | **FAIL** | L64–79：只看 `git status --porcelain`（工作区），排除 `docs/*`，其余路径必须落在 L50–63 的 **12 条**白名单内 | 提交面 16 个文件中有 **4 个不在白名单**：`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md`（docs 侧，被 L73 排除，不计）；**代码侧 3 个**：`adapter/MallStatusVocabulary.java`（新增）、`adapter/ExternalProduct.java`（改）、`engine/OperationJournalEntry.java`（改）。注释 L79 自称"新增适配器/夹具/用例 + 既有 **7** 个文件"＝12，实际既有文件是 10 个。**按实现口径**（只看工作区）当前 `porcelain` 里代码侧改动为 0，V1 会 PASS——这正是要报告的偏差：判据覆盖不到提交面 |
| V2 | PASS | L85–91：`record TargetRoute(String method, String path)` + `isBlank` + `IllegalArgumentException` | `adapter/TargetRoute.java:22` `public record TargetRoute(String method, String path) {`；紧凑构造器对两参 `isBlank()` 抛 `IllegalArgumentException` |
| V3 | PASS | L94–98：`operationRoutes` 默认方法命中 **==1** 且接口内 `"/api/｜"/open/` 命中 **==0** | `adapter/MallTargetAdapter.java:68` `default Map<String, TargetRoute> operationRoutes(TargetConfig config) { return Map.of(); }`；接口文件内无任何商城路径字面量 |
| V4 | PASS | L101–106：`MallDispatchPlan` 内 `"/api/｜"/open/` ==0 且 `String method｜String route` ==0 | 提交 diff 把 record 分量从 5 个减为 3 个：`-public record MallDispatchPlan(MallCapability capability, String operation, String method, String route, boolean mallBacked)` → `+public record MallDispatchPlan(MallCapability capability, String operation, boolean mallBacked)`；`PRODUCTS_ROUTE/USERS_ROUTE/ORDERS_ROUTE` 三个常量已删 |
| V5 | PASS | L109–120：`engine/**` 内 `"/api/｜"/open/` 命中文件数 ==0，且参考适配器命中 >0 | 引擎目录更严的扫描也干净：在 `engine/**` 上跑 `"/` 得到 **0 命中**（不止 `/api/`、`/open/`）；正向对照 `adapter/ReferenceMallHttpAdapter.java:54-60` 有 `PRODUCTS_PATH` 等常量命中 |
| V6 | PASS | L123–129：sink+engine 内 `operationRoutes` 命中 ≥1 且占位串 `（适配器未声明路由）` 命中 ≥1 | `engine/MallApiDispatchSink.java:64-65` `ROUTE_UNDECLARED_METHOD="（未声明）"` / `ROUTE_UNDECLARED_PATH="（适配器未声明路由）"`，`:122-125` `routeOf` 用 `operationRoutes.getOrDefault(...)`；`engine/MallApiGenerationEngine.java:312` 同占位串 |
| V7 | PASS | L132–139：`SECOND_MALL_HTTP` ≥1、`"/open/v2` ≥1、`MallCapability.ADMIN` ≥1、`MallCapability.REFUND` ≥1 | `adapter/SecondMallHttpAdapter.java:77` `ADAPTER_TYPE = "SECOND_MALL_HTTP"`；`:99-103` 五个 `/open/v2/...` 常量；`:206-208` `verdicts.put(MallCapability.ADMIN/REFUND/RESET_STATE, CapabilityVerdict.ABSENT)` |
| V8 | PASS | L142–146：`CENTS_PER_YUAN｜_CENTS｜movePointLeft` ≥1 且 `doubleValue()｜(double)` ==0 | `SecondMallHttpAdapter.java:86-89` `CENTS_PER_YUAN=100`/`CENTS_PER_YUAN_LOG10=2`/`YUAN_SCALE`；`:557-573` `centsToYuan` 全程 `BigDecimal`，非整数分（`"12.345"`）响亮抛 `MallOperationException`；`:576-578` `yuanToCents` 用 `movePointRight(...).longValueExact()` |
| V9 | PASS | L149–153：`"SALE"｜SALE\b` ≥1 且 `on_sale` ≥1 | `SecondMallHttpAdapter.java:149-152` `CANONICAL_PRODUCT_STATUS = Map.of("SALE","on_sale","OFF_SHELF","off_sale","PENDING","pending")`；`:589-594` `toCanonicalProductStatus` |
| V10 | PASS | L156–161：夹具内 `/api/v1/mall｜FakeMallServer｜FakeMall\b` ==0 且 `/open/v2` ≥1 | `test/fixture/SecondMallFakeServer.java` 是独立 `HttpServer`（`:261,271,287,341,357` 五条自有用字面量路由），与参考夹具 `test/fixture/FakeMallServer.java:213-358` 的 `/api/v1/mall/...` 完全不重叠，类名/字段/状态词均未复刻 |
| V11 | **FAIL** | L164–171：`git diff --numstat`（**无提交范围**，即工作区 vs HEAD）必须 `+1/-0` | 提交面实测 `git diff --numstat 2c07b4a..25b0fe7 -- .../config/GeneratorBeans.java` = **`3  1`**。内容是 `+import SecondMallHttpAdapter`、`+new SecondMallHttpAdapter(...)`、既有注册行尾 `+","`，语义上确为"1 行注册"，但判据要求 `adds==1 && dels==0`，**字面不成立**。**附加风险**：判据不带 `$LaneCommit`，当泳道已提交、工作区干净时 `numstat` 为空 → `$adds=-1` → 必然 FAIL。即该判据只在"泳道改动仍未提交"的瞬间可过 |
| V12a | **FAIL** | L174–180：T1–T8 八个标识在 3 个测试文件里各自命中 ≥1 | 实测命中数：`T1=4, T2=5, T3=6, T4=2, T5=3, T6=5, T7=4, **T8=0**` → `$missing` 含 `T8`。T8 只以草稿形式存在于 `docs/acceptance/m1-9-contract-first-20260912/README.md:149` 与 `docs/项目实施进度与任务看板 V2.2.md:85`，测试代码里没有该标识 |
| V12b | **未取证（无法判定）** | L181–199：需 `-LogPath` 指向日志，正则取 `Tests run: N, Failures: F, Errors: E, Skipped: S`，要求 F=E=S=0 且 N≥100 | 本复核**禁止跑 Maven**，也未获得日志路径 → 无从判定。**已知会踩的点（预警，非结论）**：`test/meta/GeneratorMetaStoreTest.java:58` 存在 `assumeTrue(false, ...)`（本提交未触碰，属既有代码），一旦 `generator_meta` 不可达就会把用例记成 **skipped**，直接顶掉 V12b 的"Skipped 必须为 0"。请总控跑全模块 E2 日志时确认该用例是否被跳过 |

小结：**12 条中 3 条不通过（V1 实现口径覆盖不到提交面、V11 字面 +3/-1、V12a 缺 T8 标识），1 条无法取证（V12b），8 条通过。**

## 2. 自造语义清单（规格里没有、本次新引入的语义）

按"自造程度 × 后果严重度"排列。**每一条都给出位置、原文、规格依据、后果**。

### S1（最重）订单状态 = 商城原词 + 括号别名 `SETTLED(PAID)` / `NEW(CREATED)` / `VOID(CANCELLED)`

- 位置：`synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java:154-158` 与 `:747-761`（`readOrder`）
  ```java
  /** 订单支付状态：第二家的词 → 流水里可读的原文（订单状态机归商城，这里只标注等价词，不翻译规范枚举） */
  private static final Map<String, String> ORDER_STATE_ALIAS = Map.of(
          "NEW", "CREATED", "SETTLED", "PAID", "VOID", "CANCELLED");
  ...
  String status = mallState == null || mallState.isBlank() ? "UNKNOWN"
          : mallState + (ORDER_STATE_ALIAS.containsKey(mallState) ? "(" + ORDER_STATE_ALIAS.get(mallState) + ")" : "");
  ```
- 规格依据：§4.1.1.3 规定 `ExternalOrder.status` 是"**商城原样文本**"；`adapter/ExternalOrder.java:8-9` 自己的 Javadoc 也写"用**商城回传的原文**（如 `PAID`/`CANCELLED`），**不由生成器翻译成自己的枚举**"。而 `SecondMallHttpAdapter.java:744-745` 却写"回传**商城原文**……（附上可读的等价词）"——把"原词"和"译文"塞进同一个字段，等于自认这已不是原样文本。
- 为什么是"自造"：规格从未定义 `原词(等价词)` 这种复合格式；`ORDER_STATE_ALIAS` 的三个映射对（`NEW→CREATED`/`SETTLED→PAID`/`VOID→CANCELLED`）也是本次新写的对照表，无处可对。
- 后果：
  1. 流水/报告里同一个字段出现**两种口径**——参考商城写 `status=PAID`（`ReferenceMallHttpAdapter.java:245` 字面量 `"PAID"`），第二家写 `status=SETTLED(PAID)`。按状态词做聚合/对账的消费方会被分成两个桶。
  2. 这是**静默改写证据**（与已关闭的 D12、未关闭的 F-25 同族）：规格要的是"原样"，实际产出的是"原样＋生成器补充的解释"，且没有任何地方声明该字段可能带括号。
  3. **幸运的是当前无控制流影响**：`ExternalOrder.paid()`（`:38-40`，`"PAID".equals(status)`）与 `cancelled()`（`:34-36`）在全仓库**没有任何调用点**（`paid()/cancelled()` 搜索仅命中定义处与 8091 的 `cancelled()` 同名方法），所以别名不会让状态判断翻车。这是一条"现在只污染证据、一旦有人用上 `paid()` 就变成逻辑错误"的定时炸弹。
  4. **测试主动容忍了它**——见 §4。

### S2（重）能力声明被一个未公开的魔法串 `config_json.format == "open-v2"` 把门

- 位置：`SecondMallHttpAdapter.java:82-83` `CONFIG_FORMAT = "open-v2"`；`:212` `boolean formatMatches = CONFIG_FORMAT.equals(declaredFormat(config.configJson()));`；`:213-217` 仅当 `usableBase && credentialAvailable && formatMatches` 才把 `PRODUCT/USER/ORDER` 判 `SUPPORTED`；`:191-199` Javadoc；反向断言在 `test/adapter/SecondMallAdapterOperationsTest.java:312-314`
- 规格依据：硬约束 #5 只规定三态 `SUPPORTED/ABSENT/UNDETERMINED`；§4.1.1 与 §4.1.1.3 **没有任何** `config_json.format` 键的定义。
- 为什么是"自造"：全仓库搜索 `open-v2`，命中只有 `SecondMallHttpAdapter.java` 与两个新测试文件；规格、`contract-specs/**`、迁移脚本 `src/main/resources/db/generator/V1__generator_meta.sql:18`（只写 `config_json TEXT NULL COMMENT '适配器扩展配置（JSON）'`）**都没有**这个键，也没有任何面向使用者的登记说明。
- 后果：
  1. 运维把 `adapter_type=SECOND_MALL_HTTP`、`base_url`、凭据都配对，但 `config_json` 没写这个未公开的键 → 三个核心能力全 `UNDETERMINED` → 引擎在 `engine/MallApiGenerationEngine.java:230` 附近以 `IllegalStateException` 收口。**这是响亮失败，方向正确**，但 `:230` 的错误话术只提 `base_url/凭据引用/config_json`，**不点名 `format` 键与其取值**，排障只能读源码。
  2. "这家商城是 `open-v2` 版接口"被编码成了一个字符串常量而不是能力探测/版本契约，换一家同类商城要重新发明一个魔法串。
  3. 同一方法内 `behavior` 分支（`:218-220`）**不受** `formatMatches` 约束，`PRODUCT/USER/ORDER` 受约束 —— 门控口径不自洽（同一份 `config_json` 有的键要过格式门、有的不要）。

### S3（中）三个能力位不探测直接写死 `ABSENT`

- 位置：`SecondMallHttpAdapter.java:206-208` + `:411-414`
  ```java
  verdicts.put(MallCapability.ADMIN, CapabilityVerdict.ABSENT);
  verdicts.put(MallCapability.REFUND, CapabilityVerdict.ABSENT);
  verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.ABSENT);
  ```
  同文件 `test()` 处注释"按已知事实收口"。
- 规格依据：硬约束 #5 的三态语义里，`ABSENT` 的正当来源是"**探测到/已证实不存在**"，`UNDETERMINED` 才是"没证实"。而同一适配器的 `test()` 对这三项**不发任何探测请求**。
- 为什么是"自造"：规格没有"适配器可以凭声明把能力直接判 ABSENT 而不取证"这一条；`"这是已知事实"` 是注释里的自述理由，不是规格条文。
- 后果：`ABSENT` 与 `UNDETERMINED` 在报告里承担完全不同的举证责任（前者是"没有"，后者是"不知道"），此处把前者当后者的默认值用，等于**用断言冒充测量**。与 S2 的 `UNDETERMINED` 混用后，"这家商城到底是不是没有退款"这个问题在证据链上不可分辨。方向上不伪造成功（V7 意图达成），但归因不实。

### S4（中）新增公开 SPI 类型 `MallStatusVocabulary` 与"未映射词"通道

- 位置：`adapter/MallStatusVocabulary.java`（新文件，28 行，`List<String> unmappedStatusWords()`）；`MallApiGenerationEngine.java:11,323,342-343` 用 `adapter instanceof MallStatusVocabulary` 取值
- 规格依据：硬约束 #11：§4.1.1.3 之外的新字段/新方法必须**先改规格**。§4.1.1.3 与 §4.1 没有 `MallStatusVocabulary`。
- 为什么是"自造"：全仓库 `MallStatusVocabulary` 只出现在这个新接口、它的实现者、引擎的 `instanceof` 判据，以及 `ExternalProduct` 的 Javadoc 里；规格/契约文件均无。
- 后果：这是一条**只对实现了该接口的适配器生效**的旁路通道（`instanceof`）。没实现它的适配器在报告里就没有"被排除的商品叫什么"这一行，缺口描述**取决于适配器是否碰巧实现了这个未登记接口**——同类能力在两家适配器间口径不一致，且这个不一致没有任何契约承载。
- 附带：`instanceof` 分发本身就是"按实现类型分支"的味道，与硬约束 #7"引擎里不许有'哪一家商城'的分支"相邻（此处按**能力接口**而非按商城名分支，尚可辩护，但类型是本次新造的）。

### S5（中）累计型实例状态被当成"本次运行"的事实

- 位置：`SecondMallHttpAdapter.java:167-168` `private final Map<String,Integer> unmappedStatusWords = new ConcurrentHashMap<>();`；`:602-605` `recordUnmappedStatus`（`merge(word,1,Integer::sum)`）；`:610-619`
  ```java
  public List<String> unmappedStatusWords() {
      return unmappedStatusWords.keySet().stream().sorted().toList();
  }
  ```
  消费端：`MallApiGenerationEngine.java:260` `describeUnmappedStatuses(page.products(), adapter)`、`:266-275` 生成 `SKIPPED` 缺口行与说明。
- 规格依据：硬约束 #2——跨目标的状态不得放在适配器实例字段里。适配器是单例（`config/GeneratorBeans.java:70`，`new SecondMallHttpAdapter(...)` 装进 `MallTargetAdapterRegistry`），而这份 map **只增不减、跨运行、跨 target 共享**。
- 为什么是"自造"：报告话术是"本次目录里哪些词读不懂"（`MallApiGenerationEngine.java:265` 的 note 文本"目录 %d 件，在售 %d 件"配 `:266-275` 的排除说明），但数据源是全生命周期累计集合，规格从未定义这种"累计词表"。
- 后果：**跨目标/跨运行的证据污染**。A 商城见过的 `ARCHIVED` 会出现在 B 商城本次运行的缺口说明里；第 N 次运行会报出第 1..N-1 次见过的词。方向上是"报告多说话"而非"少说话"，但按硬约束 #2 的口径，这正是不该放在实例字段里的东西（应随运行传入/随运行返回）。

### S6（轻）下单链路里的"顺手补价"与静默降级

- 位置：`SecondMallHttpAdapter.java:764-775` `catalogPricesBySku`，被 `createOrder` 调用；失败路径 `catch (RuntimeException)` 只在 debug 记一行后带着 `null` 价格继续
- 规格依据：规格没有"下单前额外拉一次目录补单价"的动作定义；§4.1 的操作名表里 `createOrder` 就是一次下单调用。
- 后果：
  1. **N+1 请求**：每一次 `createOrder` 都额外打一次 `listProducts`（`ProductQuery.firstPage(ProductQuery.MAX_LIMIT)`），真实商城里这是下单路径上的额外负载。
  2. **静默降级**：目录读取失败时价格字段为 `null` 仍然下单，成功/失败在流水里看不出差别（价格由商城自算）。"响亮失败"（硬约束 #3）的精神在这里被 `catch` 掉了。

### S7（轻）夹具的 `state` 缺失被编码成一个中文句子常量

- 位置：`SecondMallHttpAdapter.java:622` `MISSING_STATE_WORD = "(商城未给 state 字段)"`
- 后果：一个**不是状态词的字符串**被塞进"未映射状态词"集合，因此它会像真正的商城词一样出现在缺口说明里。轻微，但让人误以为商城真的返回过这个值。

### 复核结论：自造点共 **7** 处（S1–S7）。任务提示的两处（`format == "open-v2"`、`SETTLED(PAID)` 别名后缀）属实且分别是 S2 与 S1；另有 5 处（S3–S7）是本次自查新发现的。

## 3. 硬编码残留扫描

扫描范围与正则按任务给定：`engine/**`、`config/**`、`adapter/MallTargetAdapter.java`，正则 `"/api/|"/open/|/api/v1/mall|on_sale|SALE`（`on_sale` 作为规范词，允许出现在非适配器层）。

| 扫描对象 | 结果 | 证据 |
|---|---|---|
| `engine/**` 全部 `.java` | **0 命中**（更严口径） | 在 `src/main/java/com/graduation/generator/engine` 上跑 `"/`（任意以 `/` 开头的路径字面量）→ **No matches found**。因此 `"/api/`、`"/open/`、`/api/v1/mall` 必然为 0 |
| `engine/**` 商城名/adapterType 残留 | **0 命中** | 跑 `REFERENCE_MALL｜SECOND_MALL｜reference_mall｜second_mall｜mall-simulator｜/api/v1｜mall\.` → **No matches found**。引擎里没有"哪一家商城"的分支（硬约束 #7 达成） |
| `config/**` | **0 命中** | 在 `src/main/java/com/graduation/generator/config` 上跑 `"/｜adapterType｜Mall` → 仅 import 与 bean 装配（`GeneratorBeans.java:5,8,9,10,53,54,60,64,67,69,70`），无任何路径字面量、无 adapterType 字符串 |
| `adapter/MallTargetAdapter.java` | **0 命中** | 接口内无商城路径字面量（V3 已核） |
| `engine/**` 里的 `on_sale/off_sale/pending` | 允许（规范词） | `MallApiDispatchSink.java:354` Javadoc 引规范枚举、`:389-394` 用 `ContractEnums.PRODUCT_STATUS` 校验；`MallApiGenerationEngine.java` 无状态字面量 |
| 非适配器层的 `on_sale` | 允许但需登记 | `engine/FileModeGenerationEngine.java:364,394` 写 `"on_sale"`（文件模式造数，非商城路由/非适配器层）；`contract/ContractEnums.java:19` 是规范词表定义；`adapter/ExternalProduct.java:42` `onSale()` 是规范词比较。**均属规范词，不是商城词表残留** |

**残留扫描结论：干净。** 唯一边界瑕疵在守卫自身（见 §6 R5）。

## 4. 被削弱的测试（`git diff 2c07b4a..25b0fe7`）

- 变更的既有测试文件只有 1 个：`test/boundary/GeneratorBoundarySourcePolicyTest.java`，**纯新增 +72 行、0 删除**。
- 全 diff 内**没有**新增 `@Disabled` / `@Ignore` / `assumeTrue`；**没有**删除或放宽既有断言。
- 因此"删除/放宽/跳过"这一类削弱：**未发现**（0 处）。

但发现 **2 处"新写就写弱了"的断言**，它们直接为 S1 的越界语义开了口子（按任务口径"新测试不算削弱"，故不计入上表，但必须登记）：

### W1 `SecondMallAdapterOperationsTest.java:212-214`
```java
// 订单状态 = 商城原词（NEW），括号里只是给流水看的人读别名（CREATED）——原词必须在前，不许被别名顶掉
assertTrue(created.status().startsWith("NEW"), "下单后必须保留第二家原词 NEW：" + created.status());
assertFalse(created.status().startsWith("CREATED"), "不许把别名当成商城原词：" + created.status());
```
注释声称"订单状态 = 商城原词（NEW）"，但断言只验**前缀**，因此 `NEW(CREATED)` 通过。若真按 §4.1.1.3 的"商城原样文本"取值，这里应写 `assertEquals("NEW", created.status())`。**这是"契约未被强制"的最强反证：测试作者知道会带括号，于是把断言降级成前缀匹配。**

### W2 `SecondMallAdapterOperationsTest.java:219`
```java
assertEquals("SETTLED", paid.status().split("\\(")[0], "支付后必须回 SETTLED（不是参考商城的 PAID）");
```
用 `split("\\(")[0]` 主动截掉别名再比，等于**在断言里承认 `status` 带尾巴**。同文件 `:220-221` 反而更强地验了商城侧真值（`assertEquals("SETTLED", mall.orderJson(...).path("pay_state").asText())`），说明夹具侧口径清楚，只有 DTO 侧被放宽。

### W3（附）能力归因的断言把"未探测"钉成了规格
`SecondMallAdapterOperationsTest.java:288-291` 断言 `ADMIN/REFUND/RESET_STATE == ABSENT` 且注释写"不是 `UNDETERMINED`：这是已知事实"。这条断言把 S3 的自造语义**固化成了被测契约**：任何人将来想让这三项改回 `UNDETERMINED`（更保守、更符合三态语义），都必须先改测试。测试在这里成了自造语义的护城河。

### 与 V12b 相关的既有风险
`test/meta/GeneratorMetaStoreTest.java:58` 的 `assumeTrue(false, ...)` 不在本提交 diff 内（既有代码），但会让 V12b 的"Skipped=0"条件在 `generator_meta` 不可达时失败。**这不是泳道的削弱，是判据与既有测试的兼容性风险**，请总控在跑 E2 时留意。

## 5. 凭据与密钥

- **未发现真实凭据泄漏。** 允许清单内的用法：
  - `SECOND_MALL_TOKEN` 只作为**引用名**出现（`SecondMallAdapterOperationsTest.java` 用 `ENV_NAME`/系统属性名指向它；`SecondMallFakeServer` 侧用 `ANY_TOKEN="*"` 表示不校验具体值）。
  - 测试里的令牌值是假值 `test-token`、`ut-token-dual-*`。
- `SecondMallHttpAdapter.java:634-694` `call()` 每次请求都设 `Authorization: Bearer <token>`，令牌只来自 `credentialLookup`（生产为 `System::getenv`），**源码内无字面量令牌**。
- 有一条**负向**断言确认令牌不进异常文本（这是加分项，不是风险）。
- 未发现 32 位十六进制样式的凭据字面量。

## 6. 不自洽与风险

### R1 适配器 ↔ 夹具自洽性：路由/字段/状态词/金额/分页/幂等

| 维度 | 适配器 | 夹具 `SecondMallFakeServer.java` | 是否自洽 |
|---|---|---|---|
| 路由 | `:99-103` `ITEMS_PATH=/open/v2/items`、`MEMBERS_PATH=/open/v2/members`、`ORDERS_PATH=/open/v2/orders`、`SETTLE_PATH=ORDERS_PATH+"/{orderId}/settle"`、`VOID_PATH=ORDERS_PATH+"/{orderId}/void"` | `:261,271,287,341,357` 分别匹配 `GET /open/v2/items`、`POST /open/v2/members`、`POST <behavior_path>`、`POST /open/v2/orders`、`settle`/`void` 正则 | **自洽**（含埋点路径由 `config_json.behavior_path` 声明，夹具默认 `DEFAULT_BEHAVIOR_PATH="/open/v2/track"`，与 `:241` 一致） |
| 字段名 | `:107-136`（`buyer_ref/sku/quantity/unit_price_cents/category_code/member_tier/track_type/channel/visit_id/remark`；信封 `success/result/errMsg/error_code`；结果 `sku/title/category_code/unit_price_cents/state/order_no/pay_state/total_cents/lines`） | 同名字段（`pay_state` 取 `NEW/SETTLED/VOID`，商品 `state=SALE`） | **自洽** |
| 状态词 | 商品 `SALE/OFF_SHELF/PENDING → on_sale/off_sale/pending`（`:149-152`）；订单 `NEW/SETTLED/VOID` 加别名（`:155-158`） | 商品 `state=SALE`；订单 `pay_state ∈ {NEW,SETTLED,VOID}` | 商品侧自洽；**订单侧适配器比夹具多造了一层别名**（S1），夹具只给原词 |
| 金额单位 | 收 `unit_price_cents`（整数分，`:557-578`），拒绝小数分 | 单价 `500 + i*85` 分；`firstUnitPriceYuan()` 独立用 `movePointLeft(2)` 重算；价格不符返回 409 `E_PRICE_MISMATCH` | **自洽**，且夹具独立重算（不是复制适配器结果），这是好设计 |
| 分页 | `listProducts` 客户端做窗口（`ProductQuery.window`），DTO 的 `total` 只被断言 `≥ products.size()` | `/open/v2/items` **忽略 offset/limit**，只支持 `category_code` 过滤 | **未真正取证**：第二家的服务端分页行为在夹具里根本不存在，"分页"这一维度没有被测试覆盖（夹具不实现，等于把分页问题排除在证据之外） |
| 幂等 | 无显式幂等键 | 无幂等语义 | **未取证** |

### R2 跨目标污染（硬约束 #2 相关）：有，见 S5

适配器为单例（`GeneratorBeans.java:70`），`unmappedStatusWords` 是实例字段且只增不减（`:167-168, 602-605, 617-618`），引擎在每次运行里当成"本次事实"读（`MallApiGenerationEngine.java:260, 342-343`）。同一 JVM 内先后跑 A、B 两家商城或同一家跑两次，**后一次的报告会掺入前一次/另一家的词**。注意：这不影响规范事件流（规范事件的状态词仍只走 `ContractEnums.PRODUCT_STATUS` 校验），污染面是**流水/报告的缺口说明**。

### R3 跨目标路由污染：未发现

流水 `http_method`/`route` 全部来自 `operationRoutes(config)`（`MallApiDispatchSink.java:122-125`、`MallApiGenerationEngine.java:217, 300-312`），无参考商城回落；`MallDispatchPlan` 的路由分量与参考商城常量已删除；引擎内无任何商城路径/名称字面量（§3）。`engine/MallDispatchPlan.java:55-57` 的 `ORDER_PAID`/`ORDER_CANCELLED` 只是事件类型键，不带路由。**硬约束 #6 达成。**

### R4 `SecondMallHttpAdapter` 的自述表格与实现一致但暴露了口径差

`:43` Javadoc 表格写 `状态词 | on_sale/PAID | SALE/SETTLED`——注意它把参考商城侧的订单词写作 `PAID`，而第二家侧写 `SETTLED`，**表格本身已经承认两家订单词不同却没有说明产物里会出现兼容并蓄的 `SETTLED(PAID)`**（S1 的文档化缺口）。

### R5 守卫的正则覆盖边界（不是残留，是检出能力边界）

`test/boundary/GeneratorBoundarySourcePolicyTest.java:182`
```java
Pattern routeLiteral = Pattern.compile("\"/[a-z0-9][^\"]*(?:/api/|/open/)[^\"]*\"|/(?:api|open)/v[0-9]");
```
`"/` 后必须紧跟 **小写字母或数字**，且必须含小写 `/api/` 或 `/open/`。因此 `"/API/v1/mall/products"` 这类**大写**字面量逃过检出。冻结判据 V5 的正则（`"/api/|"/open/`）同样大小写敏感、且要求闭合引号在路径紧前。真实风险很低（商城路径惯例是小写），但这是"守卫有检出能力"这一正向对照无法覆盖的盲区，登记备查。

### R6 判据与交付时序的冲突（V11 结构性风险）

`verify.ps1` 的 V11 用 `git diff --numstat`（**无提交范围**）。泳道一旦提交（现状即是），`numstat` 归零 → `$adds=-1` → V11 必然 FAIL，与代码是否正确无关。也就是说该判据**只在"改动仍在工作区"时可过**。同一文件其余判据都是读文件内容（与提交状态无关），所以这是 V11 独有的一条"越复核越失败"的判据。建议总控明确 V11 的受理时点（或改用 `git diff <parent>..<commit> --numstat`），否则冻结判据集合本身无法整体通过。

### R7 `verify-v2.ps1` 的出现本身是一条治理信号

复核过程中工作区多出一个未跟踪的 `docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-v2.ps1`（见 §0）。它把 V12a 拆成 V12a1/V12a2、把 V11 改成"最小加法编辑（同核）"、并新增 V0/V13/V14。**判据在交付之后被追加/放宽**，与"判据在泳道交付前冻结"（`d260fa9` 提交信息）的初衷相反。本复核不采用它，但请总控决定：是让 v2 取代 v1（则需重新走一次冻结），还是保留 v1 并把 v2 记为"复核过程中的讨论稿"。

## 7. 未取证（honest 清单）

1. **未运行构建与测试**：`mvn`、`java`、任何测试执行均被本次复核纪律禁止 → **所有用例是否通过、是否可编译，均未取证**。V12b 因此无法判定。
2. **未运行冻结复核脚本** `verify.ps1`（任务明确禁止）→ 上表判定全部由**读源码 + 按判据字面复算**得出，未取其实际输出。若总控要正式结论，需自己在工作区跑一次并注意 R6 的时序问题。
3. **无真实第二家商城**：环境里不存在真实第二家商城，全部证据来自 `SecondMallFakeServer` 夹具（自述见 `docs/acceptance/m1-9-contract-first-20260912/README.md` §11）。"生成器真的能对接第二家真商城"**未取证**。
4. **服务端分页/限流/幂等**：见 R1 表——夹具不实现服务端分页与幂等，这两项**未取证**。
5. **`test()` 探测路径的真实响应**：`:372-423` 的探测（`OPTIONS` on `probeRoutes`）只在夹具语义下被理解，真实第二家是否支持 `OPTIONS` 探测**未取证**；`ADMIN/REFUND/RESET_STATE` 不探测而写 `ABSENT`（S3）也没有实测支撑。
6. **端到端（DB + 8091 + 8092）链路**：登记 `adapter_type=SECOND_MALL_HTTP` 的目标、经 `GenerationRunService` 跑一次真实 MALL_API 生成、产出物落到 Hive/ODS 的整链路**未取证**（且 `config_json.format` 的登记方式无文档，见 S2）。
7. **T8 的实质**：`boundary/GeneratorBoundarySourcePolicyTest.java:181` `engineSourcesCarryNoMallRouteLiterals()` 确实覆盖了"引擎无商城字面量 + 适配器正向对照"的实质，但它**不校验"新增一家商城只动 1 个适配器文件 + 登记处 1 行"**（这正是 T8 草稿的字面要求，见 `docs/acceptance/m1-9-contract-first-20260912/README.md:149`）。因此"变更面守卫"是否算达成，**属口径问题，本复核只报事实：标识缺失、字面断言缺失**。
8. **`RULINGS.md` 的性质**：该文件（`docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md`，本次提交新增 +175 行）属另一个验收包的裁决文件，本次复核**未审其内容**，只登记它落在 V1 白名单之外。

## 附：本次复核未触碰的面

未执行 `git add/commit/push/checkout/stash/reset`；未运行 `mvn/java/spark-submit`；未运行待验脚本；未修改任何跟踪内文件；唯一写入是本文件。
