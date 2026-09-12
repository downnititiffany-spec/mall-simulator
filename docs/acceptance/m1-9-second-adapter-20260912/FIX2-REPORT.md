# M1-9-R2 修复轮实施报告（FIX2-REPORT）

> 本报告只写**实测到的事实**。凡未实测的，一律进 §5「未取证/未做」，不进结论列。
> 施工单：`docs/acceptance/m1-9-second-adapter-20260912/RULINGS-20260912.md`（D-066 ~ D-069）。
> 权威规格：`docs/项目完整实施指导书 V2.4.md` §4.1.1.3（L238–262；L245 三通道；L251/255「商城没给 ⇒ null，绝不 UNKNOWN」）。
> 评审输入：`docs/acceptance/m1-9-second-adapter-20260912/review-code-quality.md`（626 行）。

---

## 1. 开工声明

| 项 | 内容 |
|---|---|
| Task ID | **M1-9-R2**（M1-9 第二家商城适配器 · 代码质量评审修复轮） |
| Owner | 本泳道实施 agent（子代理；**不是**评审、**不是**裁决者；只执行 D-066~D-069 已裁事项） |
| 分支 | `remediation/r1-boundary`（git **只读**，本轮未执行任何 git 写操作） |
| 开工时间 | **2026-09-12 13:05**（首条动作 = 改动前 sha256 基线清单 `13:05:12`，改动前源快照 `13:05:15`；首条基线 E2 日志 `13:05:59`） |
| 收工时间 | 2026-09-12 13:30（末条证据 = 最终 E2 验收日志 `13:30:17`）；`evidence/` 目录共 **22** 个文件（原始日志，未经改写） |
| 允许写 | `synthetic-data-generator/**`、本报告、`docs/acceptance/m1-9-second-adapter-20260912/evidence/**` |
| 禁写（自查通过） | `spark-jobs/**`、`analytics-server/**`、`contract-specs/**`、`docs/项目完整实施指导书*`、`docs/项目*看板*`、其它 `docs/**` —— 本轮**零写入** |
| 文件级纪律（自查通过） | **未删除、未移动任何文件**；`git status --porcelain` 中本泳道只出现 8 个 `M`（§4），其余 `M`/`??` 属 P2-01 / P2-07 车道，未被本泳道触碰；未出现"不是我创建的文件出现/消失" |
| 进程纪律（自查通过） | 未停/未起 8090(pid 41228)、8091(pid 47132)、8092(pid 19268)；**不声称**改动已进入运行中的 8092 |
| 改动前备份 | `%TEMP%\m1-9-r2-backup\src-snapshot\`（改动前 93 个源文件原文，仅用于 §4 的"前"哈希）<br>`%TEMP%\m1-9-r2-backup\post-edit\`（本轮改动后版本 = 每次变异测试的**还原源**） |
| 规格前置 | D-067「规格先行」已由裁决方完成（V2.4 L245 已写三通道），本泳道只做代码与用例 |

---

## 2. 逐条处置表

| 编号 | 问题（施工单/评审口径） | 处置 | 证据 | 状态 |
|---|---|---|---|---|
| **H2** | `ProductPage` 只有两缺口通道，与 V2.4 L245（三通道）不符；报价读不懂的商品 ID 没有出口 | ① `ProductPage` 加第 5 分量 `priceUnreadable`（record 紧凑构造器统一归一 + 兜 `total ≥ products.size()`）；② **两个真实出口**都接上：`listProducts` 关键字分支（`page.priceUnreadable()` 原样带过）、`readCatalog`（`toProduct` 捕获报价异常 ⇒ `priceUnreadable.add(sku)` + `return null`）；③ 三通道**并列**上报、逐通道去重 + 字典序（`sortedDistinct`）；④ 引擎缺口行按通道分别给"补法"（状态词补映射表 / 报价读不懂查商城计价口径）；⑤ `hasStateVocabularyGap()` → `hasGap()`（三通道任一非空） | 用例：T2c、T2e、`SecondMallDualTargetTest.unreadablePriceIsNamedAndExcluded`<br>变异：MUT-6（出口置空 ⇒ 3 红）、MUT-H2 | **已修**（`contract-specs/**` 未动；**不声称**参考适配器已上报缺口——`ReferenceMallHttpAdapter.java:182` 缺口恒空属已知未实现，见 D-067 §4） |
| **M1** | `centsToYuan` 正则 `-?\d+` 收负数；Javadoc 承诺 `MallOperationException`，真实守卫是 `ExternalProduct.java:37-39` 的 `IllegalArgumentException` ⇒ 穿透 `MallApiDispatchSink.write` 的 catch 面 | ① 正则收紧为 `\d+`（带符号/小数分/非数字一律拒）；② 异常家族统一到 `MallOperationException`，Javadoc `@throws` 与实现**同时**为真；③ 单位口径写成显式常量 `CENTS_PER_YUAN` + `CENTS_PER_YUAN_LOG10`（`requirePowerOfTen` 保证一致）；④ 注释写明 `ExternalProduct` 的非负是**类型不变量**，不是输入校验 | 变异 **MUT-1**：回退 `-?\d+` ⇒ 5 红，其中 `：261`/`：382`/dual`:300` 的报文是 `IllegalArgumentException: price 不得为负数：-5.85` **穿透到引擎层**——施工单点名的 catch 面失效被当场复现 | **已修** |
| **M2** | 「空白 ⇒ null」有两个所有者（`ExternalOrder` 构造器 vs `readOrder`） | 收敛为**单一所有者 = `ExternalOrder` 构造器**（该文件**零改动**，逐字保留）；`readOrder` 只做「商城原样透出（含空白）」，删掉重复归一并在注释里写明归属；新增走**适配器路径**的空白状态验收（T8 第 6 步 `BLANK_STATE`） | 变异 **MUT-4**（删构造器那两行）：全量红 `missingStateNeverInventsAMallWord:750`，`expected: <null> but was: <   >`——施工单要求的"删掉必须变红"成立；同时**纠正评审前提**（评审推演"删掉仍全绿"在实测下不成立） | **已修**（`ExternalRefund` 同形态本轮未改，随 D-068「只登记」） |
| **M4** | `GeneratorApiDtos.java:132/139`、`TargetCheckResult.java:10` 写"实测"，而 3 项能力位是**静态声明未探测** | 措辞先行：改成"静态声明（未探测）"/"能力判定"，与适配器 Javadoc、`describeProbe`、T7 三处自报口径一致 | §4 的 sha256 前→后；`contract-specs/**` 零改动 | **措辞已改**；`declared` 标记属**契约变更**，按 D-068「不在本轮，登记为 CT 之后契约窗口项」**未做** |
| **M5** | 夹具不具备"下单成功但无 `lines`/无行价"的形态 ⇒ `readCreatedOrder` 三条失败路径零覆盖 | ① 夹具加 `OrderResponseShape` 7 值开关（`AS_IS`/`OMIT_STATE`/`BLANK_STATE`/`OMIT_LINES`/`EMPTY_LINES`/`OMIT_LINE_PRICE`/`RESULT_NOT_OBJECT`，逐值与 `orderJson` 深拷贝隔离）；② 新增 T2d 让 4 条出口真的被跑（无 `lines` / 空 `lines` / 行无单价 / 非对象应答）+ 负数分；③ T2d 判据**必须能区分**"适配器自己发现"与"商城拒单" | 变异 **MUT-2**（把"缺 `lines`"判据改坏 ⇒ 红 `:318`；**MUT-3**（逐行价格守卫 `if (false)`）第 1 次**全绿** ⇒ 判据太弱，补强为"必须含『明细行』且含违规 `sku`"后同一次变异**变红** `:338` | **已修**（并因此发现并堵掉一个真实覆盖漏洞：原断言 `contains("unit_price_cents")` 被 `centsToYuan` 自己的报文满足） |
| **M6** | T2b 的 `@DisplayName` 过度承诺：只断言夹具自造的 409 `E_NO_PRICE`，却写成"商城没给 `unit_price_cents` 时下单必须响亮失败" | DisplayName 对齐为「T2b 商城缺价拒单（409 E_NO_PRICE）…」，并在用例里写明"**不**证明适配器自己发现明细行缺价——那条路径由 T2d 的 `OMIT_LINE_PRICE` 覆盖" | 变异 **MUT-3 第 1 次运行（全绿）**：把**适配器侧**逐行价格守卫改成 `if (false)` 时，T2b 连同等 19 条定向用例**全部全绿** ⇒ 评审"用夹具自造 409 冒充被测路径"的结论**实测成立**；补强后同一次变异红 | **已修**（改名 + 指名所有者 + 做出"杀实现 ⇒ 变红"记录） |
| **M7** | 三个零调用夹具 API（`putItem`、`putItemWithoutPrice`、`putItemWithoutState`），其中 `putItemWithoutState` 的 Javadoc 声称覆盖"字段缺失"实为死 API，`putItem` 是"新增商品"的第二所有者（L4） | 按 delete-first **方法级退休**（**未删文件**）；类 Javadoc 的 `{@link #putItemWithoutState(...)}` 改指真正在用的 `overrideItemState(sku, null)` | 退休前实测：全测试树检索 4 处命中 = 3 定义 + 1 `{@link}`，**0 调用点**；变异 **MUT-9**：把三个 API 原样贴回 ⇒ 全量 **120 条仍全绿** ⇒ 零调用点实测成立 | **已删**（反熵声明见 §7） |
| **L1** | 关键字分支的 `total` 与全量分支两口径 | 关键字分支改为 `page.total()` 原样带过（不再用切片件数），并加注释点明两口径缺陷；新增 T2e 覆盖（命中 / 完全不命中两种形态） | 变异 **MUT-7**：回退 `filtered.size()` ⇒ 红 `keywordBranchKeepsCatalogTotalAndGapChannels:401`，`expected: <60> but was: <1>` | **已修** |
| **L2** | 弱断言 `assertEquals(100, CENTS_PER_YUAN)` 钉的是常量字面值、不是行为 | 删掉该断言；改为**从常量派生**的行为断言（按 `CENTS_PER_YUAN` 推 1 元的分数、按换算位数推 `movePointLeft`） | 变异 **MUT-5**：`CENTS_PER_YUAN_LOG10` 改成 3（`CENTS_PER_YUAN` 仍 100）⇒ **8 条错误** `Arithmetic Rounding necessary`（含 `centsAreConvertedWithoutFloatTail:107`），而旧的弱断言在此变异下会保持全绿 ⇒ 强弱已被实测区分 | **已修** |
| **L3** | `readOrder` 内联空白判断不随类常量/语义走 | 抽成具名谓词 `isPresent(JsonNode)`（缺失/显式 null 都算"没给"）与 `isReadableCents(JsonNode)`（数字或文本），口径集中在一处 | 变异 **MUT-4**（同 M2）覆盖 `isPresent` 分支；`isReadableCents` 由 T2/T2c 覆盖 | **已修**；L3 的另一半（`totalAmount` 的 null 二义）**未改**，随 H1 的契约项登记（§6-6） |
| **L4** | `putItem` 第二所有者 + 未实现工厂方法 | `putItem` 已随 M7 退休；`ProductPage.empty()` 与 2 参便捷构造器（零调用点）**保留**并写明理由：它们是记录类型上的常量式便捷入口，不构成"同一事实的第二所有者"，退休与否属"未实现 API 清理"口径，请裁决（§6-4） | MUT-9（同 M7）；零调用点检索输出 | **一半已修**（`putItem`）；另一半**保留并登记** |
| **L5** | 「属性名拼错 ⇒ 说不清哪个引用没设」：`credential_ref` 未设置 vs 设置成空白串报同一句 | `credentialState` 把空白 `ref` 归一成 `""`（不是 `null`），`requireCredential` 按事实分三种话术（没配 / 配了空白 / 环境变量没注入），三种都只出现引用名、绝不出现取值 | T3 第 3e 步分别断言；变异 **MUT-L5**：两分支合并成一句 ⇒ 红 `differentEnvelopeParsesAndFailsLoudly:485` | **已修** |
| **L6** | 参数/分隔符/O(n²) 可读性；两份逐字相同的私有工具（`abbreviate`/`abbreviateText`/`stripTrailingSlash`/`encode`） | **不改**（评审原文明确"适配器之间刻意不共享代码是合理取舍……**不建议**在本轮合并：真要合并应走规格变更"）；已按评审口径保留重复实现 | 评审 §0 L6 段（`review-code-quality.md:277`） | **按评审口径不改**（理由已写）；若"参数/分隔符/O(n²)"另有所指，评审未给行号，本轮未逐条核对（§5-8） |
| **L6b** | 冗余断言（同一条事实既断 `<=1` 又断 `>=1`）；`mall` 字段被二次赋值 | 删冗余断言；第二家夹具改用局部变量（`try (SecondMallFakeServer singleItemMall = …)`），不再重绑 `mall` 字段 | `SecondMallDualTargetTest` 用例结构调整；全量绿 | **已修** |
| 额外① | `readCreatedOrder` 里重复的对象 guard（与调用方 `createOrder` 的 `requireText` 同一事实两份所有者） | 删除重复所有者（**未删文件**），并在方法 Javadoc 写明"非对象应答由调用方 `requireText` 挡" | 变异 **MUT-8**：贴回后 19 条定向用例**全绿** ⇒ 确为冗余；`RESULT_NOT_OBJECT` 形态仍被 T2d 钉住（报文含"对象"） | **已删**（披露项） |
| 额外② | `readCatalog` 的 `total` 口径 | 取 `products.size() + 读不懂报价的件数` = **"商城这次给了多少件"**，三通道件数可对账，且满足 L245 的唯一约束 `total ≥ products.size()`（第二家应答是裸数组，没有声明总数） | T2/T2b/T2c/T2e 的行内报文（"目录 60 件 → 在售 59 件"） | **已定口径并披露**（§6-5 请确认） |

---

## 3. 实测证据

### 3.1 验收（模块内全量，E2）

命令逐字（施工单规定，`MAVEN_OPTS` 前置）：

```powershell
$env:MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o test -f synthetic-data-generator/pom.xml
```

| 轮次 | 日志（`evidence/`） | 逐字结果 |
|---|---|---|
| 改动前基线 | `R2-e2-20260912-130559.log` | `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` |
| **最终（本次交付的字节）** | **`R2-e2-final3-20260912-133017.log`** | **`Tests run: 120, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `Total time: 25.412 s`**，退出码 **0** |

本泳道两个测试类的分项（最终日志逐字）：

```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.398 s -- in com.graduation.generator.adapter.SecondMallAdapterOperationsTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.550 s -- in com.graduation.generator.engine.SecondMallDualTargetTest
```

（改动前同两类的分项为 9 条 / 6 条 ⇒ 本轮净增 4 条：T2c、T2d、T2e、`unreadablePriceIsNamedAndExcluded`。）

### 3.2 变异测试（10 次，全部"先破坏、后观察、再从备份还原并核对 sha256"）

统一命令模板：`mvn -o test -f synthetic-data-generator/pom.xml [-Dtest=…]`，`MAVEN_OPTS` 同 §3.1。
"定向"= `-Dtest=SecondMallAdapterOperationsTest,SecondMallDualTargetTest`（19 条）。

| # | 突变点（故意改坏被保护的实现） | 范围 | 观察到的红（逐字用例:行号 / 报文要点） | 日志 | 还原核对 |
|---|---|---|---|---|---|
| MUT-1 | `centsToYuan` 正则回退 `\d+` → `-?\d+`（M1） | 定向 | 5 红：`:139`、`:344` `Expected MallOperationException to be thrown, but nothing was thrown`；`:261`、`:382`、dual`:300` `IllegalArgumentException: price 不得为负数：-5.85`（**穿透到引擎层**） | `R2-mut-M1-20260912-132131.log` | B379F809…1323 一致 |
| MUT-2 | 缺 `lines` 判据 `!isArray() \|\| isEmpty()` → `isArray() && isEmpty()`（M5） | 定向 | 红 `unreadableCreatedOrderFailsLoudly:318` `nothing was thrown` | `R2-mut-M5a-20260912-132152.log` | 一致 |
| MUT-3 | 逐行价格守卫改成 `if (false)`（M5/M6 关键） | 定向 | **第 1 次全绿**（19 条）⇒ 判据不足以区分；补强 T2d 判据后**同一次变异红** `:338`（报文只来自 `centsToYuan` 的通用句） | `R2-mut-M5b-M6-20260912-132218.log`（全绿）/ `…132241.log`（红） | 一致 |
| MUT-4 | 删 `ExternalOrder` 构造器的空白归一（M2） | 全量 | 红 `missingStateNeverInventsAMallWord:750` `expected: <null> but was: <   >` | `R2-mut-M2-20260912-132303.log` | 6E4D5546…DE80 一致 |
| MUT-5 | `CENTS_PER_YUAN_LOG10` 100 → 3（L2） | 定向 | 8 错误 `Arithmetic Rounding necessary`（`centsAreConvertedWithoutFloatTail:107`、`missingPriceFailsLoudly:220`、`:75`、`:504`、`:388`、`:702`、`:261`、`:370`），且 `CENTS_PER_YUAN` 仍为 100 ⇒ 旧弱断言会保持全绿 | `R2-mut-L2-20260912-132349.log` | 一致 |
| MUT-6 | `priceUnreadable` 出口置空 `List.of()`（H2） | 定向 | 3 红：`negativePriceIsRejectedAndNamed:271`、`keywordBranchKeepsCatalogTotalAndGapChannels:389`、dual `unreadablePriceIsNamedAndExcluded:304` | `R2-mut-H2-20260912-132409.log` | 一致 |
| MUT-7 | 关键字分支 `total` 回退 `filtered.size()`（L1） | 定向 | 红 `keywordBranchKeepsCatalogTotalAndGapChannels:401` `expected: <60> but was: <1>` | `R2-mut-L1-20260912-132439.log` | 一致 |
| MUT-8 | 贴回 `readCreatedOrder` 的重复对象 guard | 定向 | **全绿**（19 条）⇒ 冗余（同一事实的第二所有者）确认 | `R2-mut-M5-guardrestore-20260912-132509.log` | 04F9DB84…1154 一致 |
| MUT-9 | 贴回 3 个退休夹具 API（M7/L4） | 全量 | **全绿**（120 条）⇒ 退休时**零调用点**确认 | `R2-mut-M7-restoredeadapis-20260912-132544.log` | A62712AD…81CD 一致 |
| MUT-L5 | 凭据"没配/配了空白"两分支合并成一句（L5） | 定向 | 红 `differentEnvelopeParsesAndFailsLoudly:485`（报文退化成"目标未配置 credential_ref"） | `R2-mut-L5-20260912-132736.log` | B379F809…1323 一致 |

**还原核对方式**：变异前把 6 个会参与变异的文件复制到 `%TEMP%\m1-9-r2-backup\post-edit\` 并记录 sha256；每次变异后 `Copy-Item` 还原 + `Get-FileHash` 比对（6/6 一致，输出见 §4 与收工核对）。施工单要求的"至少一次『故意删掉被保护实现 ⇒ 用例变红』"在 M5（MUT-2/MUT-3 后段）、M6（MUT-3 前段"全绿"= 冒充的实测证据）、M7（MUT-9）三处均有落盘记录。

### 3.3 覆盖漏洞与自我纠错（诚实记录）

- MUT-3 第 1 次运行**全绿**，说明 T2d 原判据 `assertTrue(msg.contains("unit_price_cents"))` 被 `centsToYuan` 自己的报文（"商城未给出 unit_price_cents"）满足 ⇒ 该断言**不能区分**"适配器主动发现明细行缺价"与"换算函数兜底"。补强为"必须含『明细行』**且**含违规 `sku`"后，同一次变异立刻变红（`:338`）。这是本轮唯一一次"用例先假通过"的记录。
- 同一份 MUT-3 全绿日志顺带成为 **M6 的实测证据**：适配器侧守卫被杀时 T2b 毫无反应 ⇒ T2b 从未覆盖适配器路径，只是断言了夹具自造的 409。

### 3.4 两次"全量红"的完整披露（都不是代码缺陷）

| 时间 | 日志 | 真相 | 处置 |
|---|---|---|---|
| 13:27:54 | `R2-e2-final-20260912-132754.log` | **Maven 增量编译时间戳导致的假失败**：变异还原用 `Copy-Item`（保留旧 mtime），源文件 mtime 早于变异编译出的 `.class` ⇒ Maven 判定"无需重编译"，跑的是**被变异的旧 class**（报文退化成合并话术）。源文件 sha256 自始正确（当时已核对 = B379F809…1323） | 把 8 个改动文件的 mtime 置为当前，强制重编译（日志 `R2-e2-final2-…132848.log` 内 `Changes detected - recompiling the module! / Compiling 70 source files`），并直接校验 `.class` 内含修复文案 |
| 13:28:48 | `R2-e2-final2-20260912-132848.log` | **与本泳道无关的全量抖动**：红的是 `ReferenceMallHttpAdapterTest.probeReportsSupportedCapabilitiesFromRealResponses:64`（`/api/v1/mall/orders/0/pay` 一路探针 `FAILED` ⇒ `order` 由 `SUPPORTED` 变 `UNDETERMINED`）。该文件 sha256 与基线**逐字一致**（`24D26392…A403F4`），不在本泳道改动清单内，夹具用 `127.0.0.1:0`（临时端口） | 单独连跑 3 次全绿（`R2-flake-refadapter-20260912-133000.log`，3×`Tests run: 8, Failures: 0`）；随后全量 **120 绿**（§3.1 最终日志）。记为"全量运行存在低概率抖动"，**不计入本泳道改动**，请 R3/评审复测确认（§6-8） |

---

## 4. 变更文件清单（sha256 前 → 后 + 行数）

全部改动位于 `synthetic-data-generator/**`；**无新增文件、无删除文件、无移动文件**。

| 文件 | sha256（前） | sha256（后） | 行数 |
|---|---|---|---|
| `src/main/java/com/graduation/generator/adapter/ProductPage.java` | `2BB4DF5FC16F00E113C0527683F65504BFCA3251F9CAD3861A72B8F292EB384E` | `BCE3F0E36FA1B315C2C88A4082CC25F5A92D812585141FA90693C361D730D8D9` | 57 → 79 |
| `src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java` | `6B3E7FB1C8DE51083A53CCEDA0FCA8033E8CA794FE0C515F6EB0BC4BFAF986F2` | `B379F809F4E145E1B5EA8634F33602F972F6494EEEDCB65D1A387B6464271323` | 1061 → 1151 |
| `src/main/java/com/graduation/generator/adapter/TargetCheckResult.java` | `20FA1C1AEF433788C8EDC20BF6CE9BCEBFAC314D27FA4D1F9AFD05663A73A87B` | `EE3E29E1CA95D7EFC7F58369732C2106DBC84156AE3A33644159189B0567D0D8` | 32 → 39 |
| `src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java` | `41ACFF060B09ABB6C608C709A4E1A5251730ED7D1B70216509A98F78938ABEB9` | `04F9DB84F6B5951202B1CF34F518A5E87A368A418F842E9195ACBE316FDD1154` | 543 → 569 |
| `src/main/java/com/graduation/generator/web/dto/GeneratorApiDtos.java` | `731871068DE24491B7025F69ED31EE2A4D84F200E58F4BB2E7A60628BC8F1B28` | `726111B925698E20F6929DCF778B1965A18569546877034416628A2A8BDA8963` | 149 → 155 |
| `src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java` | `F74D56A8898A9726862A1D1073E0E5FE280EE427B8F51480AF29D8E1566F87EC` | `F34EBDBC91F12D437AAD94D9FE884D1A8DC356F2D56E0835AD781E35F3FC9456` | 572 → 839 |
| `src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java` | `8F0B367D7997BB4A0086B8E82A829399DE7B8A6BDFB0F86774E6B769A31A186E` | `D0B167203D3E8C8EF6642BEA1C5F764B251B1A493A3024B3052FA456CD84BE3F` | 568 → 633 |
| `src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java` | `0440A73360F40632BCF7CC7F075809126609C9CC93468D3F16696FD36CC23CA8` | `A62712AD129716B269CC7C28B4383D13C56F2634EEF490C05E83089C958F81CD` | 569 → 623 |

**M2 的落点说明（重要，避免误读）**：`src/main/java/com/graduation/generator/adapter/ExternalOrder.java` **本轮零改动**，其 sha256 与开工基线清单、与 `HEAD` **逐字一致**（`6E4D5546E383B9690E8EBCA65262945188D411CC6954BD2B8181FE460E02DE80`，`git diff` 对该文件无输出）。M2 的"收敛到单一所有者"全部落在**适配器侧**：删掉 `readOrder` 里的重复空白归一，只做"商城原样透出"，并在该方法注释里写明归属（唯一所有者 = `ExternalOrder` 构造器）。MUT-4 实测证明起作用的实现确实只有构造器那一处（删掉它 ⇒ `missingStateNeverInventsAMallWord:750` 变红）。

未改动（D-068「只登记、本轮不改」，逐字保留）：`ExternalOrder.created()` 造 `"CREATED"`、`ReferenceMallHttpAdapter:245/259/278` 造 `PAID/CANCELLED/COMPLETED`、`MallTargetAdapterOperationsTest:127` 把造值钉成契约。
未触碰：`ExternalProduct.java`（37-39 行不动）、`MallApiDispatchSink.java`、`TargetProbeService.java`、`contract-specs/**`、`spark-jobs/**`、`analytics-server/**`。

---

## 5. 未取证 / 未做（诚实清单）

1. **`declared` 标记未做**——属契约变更（`contract-specs/openapi/generator-api.v1.yaml`），按 D-068 顺延到 CT 批次之后。M4 只改了措辞。
2. **显式 JSON `null` 的 `pay_state` 在 HTTP 层未取证**：新增的 `isPresent` 谓词会把"显式 null"也判成"没给"（旧的 `asText(null)` 在显式 null 时可能把文本 `"null"` 当值），但夹具没有产出"显式 null 状态字段"的形态 ⇒ 该改进只在单元/读码层面成立。
3. **小数分（`12.5`）报价在 HTTP 层不可达**：夹具 `overrideItemPriceCents(String, long)` 只收整数 ⇒ "小数分响亮拒绝"只有单元层证据（直接调 `centsToYuan`）。
4. **没有真实第二家商城**：Part B 的"商城"是 `127.0.0.1:0` 上由 `com.sun.net.httpserver.HttpServer` 起的夹具；分页、幂等、限流、错误码全谱面**均未取证**。
5. **并发/多商城并行未取证**；无任何集群、百万行级别结论。
6. **运行态未验证**：不声称改动进入运行中的 8092（未重启、未热载）；也未验证 8090/8091。
7. **契约零改动**（`contract-specs/**`），因此"契约文档也不再写实测"这件事只做到 DTO/类 Javadoc 层。
8. **L6 的"参数/分隔符/O(n²)"未逐条核对**：评审该条只在汇总表出现（`review-code-quality.md:17`），未给行号；本轮按 §0 明细段的结论处理（重复私有工具**不合并**）。
9. **控制台中文乱码未消除**：JVM 默认字符集为 GBK，Maven 控制台中文经 `Tee-Object` 落盘后乱码；ASCII 部分（`Tests run: …`、用例名、行号、`BUILD SUCCESS`）可靠。为保持施工单规定命令**逐字不变**，未追加 `-Dfile.encoding=UTF-8`。
10. **未做**：README 口径更新（写范围外）、`scripts/verify-fix-v3.ps1` 更新（写范围外）、H1 的规格外实现、任何"顺手清理"。
11. **H2 的范围**：只到"适配器读取 + 引擎缺口行 + Javadoc 与实现同时为真"；参考适配器缺口恒空属已知未实现，**未声称**其已上报缺口。

---

## 6. 阻塞 / 待裁

1. **`scripts/verify-fix-v3.ps1`（R1 冻结）**：按 R1 的 116 条与旧文件清单校验，可能把本轮 8 个文件报成"清单外"。本泳道无 `docs/**`/脚本写权限 ⇒ 请裁决是否另开一条更新该脚本（或在其白名单里加入本轮 8 个文件）。
2. **README 中 §209 陈旧引文**：位于允许写范围之外，未改。
3. **L6 合并（两份逐字相同的私有工具）**：评审明确"不建议本轮合并，需走规格变更" ⇒ 若裁决要合并，请开规格变更单，而不是顺手抽公共类。
4. **`ProductPage.empty()` 与 2 参便捷构造器零调用点**：本轮**保留**（理由见 §2 L4）；若口径是"零调用点即退休"，请裁 delete-first。
5. **两处口径请确认**：① `readCatalog` 的 `total = 商城这次给了多少件`（含报价读不懂而被排除的件）；② `readCreatedOrder` 内重复对象 guard 已删（唯一所有者 = 调用方 `createOrder` 的 `requireText`）。
6. **L3 的另一半**：`totalAmount` 的 null 二义（"0 元"与"未知"不可区分）随 H1 的契约项，本轮无消费点、未改。
7. **参考适配器缺口恒空**（`ReferenceMallHttpAdapter.java:182`）属已知未实现，需在后续轮次或规格里明确"它不上报缺口"。
8. **全量运行的低概率抖动**（§3.4 ②，未改文件 `ReferenceMallHttpAdapterTest`）：建议复测确认是否为环境（CPU/临时端口）问题，本轮只做到"单跑 3/3 绿 + 全量复跑绿"。

---

## 7. 反熵声明（Anti-Entropy Declaration）

- **退休决定（方法级，0 个文件删除）**：
  1. `SecondMallFakeServer.putItem(String, long, String)` —— "新增指定商品"的**第二所有者**（构造器已按 `ITEM_COUNT` 造满目录）。
  2. `SecondMallFakeServer.putItemWithoutPrice(String)` —— 零调用点；真实验形态走 `removeItemPrice(sku)`。
  3. `SecondMallFakeServer.putItemWithoutState(String)` —— 零调用点，且其 Javadoc 声称覆盖"字段缺失"，实际覆盖走 `overrideItemState(sku, null)`。
  4. `SecondMallHttpAdapter.readCreatedOrder` 内的对象类型 guard —— 与调用方 `createOrder` 的 `requireText` 同一事实的第二所有者。
- **依据（实测，不是推演）**：全测试树检索 = 4 处命中（3 定义 + 1 `{@link}`）、**0 调用点**；MUT-9 把三个夹具 API 原样贴回后全量 **120 绿**；MUT-8 把 guard 贴回后定向 19 绿。
- **验证计划（已执行）**：上述两次"贴回"实验即验证；`{@link}` 已改指真正在用的 `overrideItemState`，无悬空引用；`mvn -o test` 全量 120 绿（§3.1）。
- **残余风险**：① 若将来需要"新增任意商品"的夹具能力，需**新写** API（当前 `ITEM_COUNT` + `overrideItem*` + `removeItemPrice` 覆盖现有 12 条用例的全部形态）；② guard 删除后，"应答 result 不是对象"的响亮失败由调用方保证，已由 T2d 的 `RESULT_NOT_OBJECT` 形态钉住（报文含"对象"）；③ 三个退休 API 的名字若出现在外部（本仓库外的脚本/文档），会失效——本仓库内检索为 0 命中。

---

**结论（只到实测边界）**：D-066~D-069 中除"`declared` 标记（契约项，按裁决顺延）"与"L6 合并（评审明确不建议本轮做）"外，H2、M1、M2、M4、M5、M6、M7、L1–L5、L6b 均已实施并留有可复核证据；模块内全量 120 条全绿。**不声称** M1-9 整体完成、**不声称**已适配真实第二家商城、**不声称**契约已变更、**不声称**运行中的 8092 含本轮改动。
