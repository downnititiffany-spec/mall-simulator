# M1-9 ②「第二家商城适配器」代码质量评审（只读泳道）

- 评审对象：`ed8c531`（提交时间 2026-09-12 12:45:21 +0800，父提交 `bd92f65`），分支 `remediation/r1-boundary`
- 评审范围：**只含模块 `synthetic-data-generator`**（含 `src/main`、`src/test` 与本轮 `docs/**` 变更面的比对），不评 `spark-jobs/**`、不评平台侧
- 评审角色：只读代码质量评审。未改任何被评文件、未提交、未推送、未 checkout/stash/reset、未删除任何文件
- 唯一写入产物：本文件
- 纪律说明：本泳道**没有执行删除类命令**。仓库根目录现存 4 个本人早前创建的临时 diff 文本（`.verify-tmp-adapter-diff.txt`、`.verify-tmp-fixture-diff.txt`、`.verify-tmp-optest-diff.txt`、`.verify-tmp-dual-diff.txt`），按本泳道纪律不得执行删除，**在此如实登记**，请总控处置（它们未被任何提交跟踪）。

---

## 0. 结论速览

| 严重度 | 条数 | 编号 |
|---|---|---|
| 高 | **2** | H1（§4.1.1.3 与代码对不上：DTO `status` 语义已改而规格未改）；H2（`priceUnreadable` 通道有收集无出口，证据静默丢失） |
| 中 | **7** | M1 `centsToYuan` Javadoc 与实现相反；M2 第二个规范化点与 Javadoc 重复；M3 部分目录报错未点名读不懂的商品；M4 `/targets/{id}/test` 把静态声明当实测值回给 API 消费方；M5 适配器自有的下单价格校验零覆盖；M6 T2b 的 `@DisplayName` 承诺大于用例实际断言；M7 三处死夹具 API（含一处 Javadoc 承诺了并不存在的覆盖） |
| 低 | **6** | L1 时间戳重复解析；L2 `assertEquals(100, CENTS_PER_YUAN)` 是弱版本；L3 `readOrder` 内联空白判断不随类常量走；L4 `putItem` 第二所有者 + 未实现工厂方法；L5 「属性名拼错 ⇒ 说不清哪个引用没设」；L6 参数/分隔符/O(n²) 可读性 |

**先看这一条**：**H1。** 本轮把三个 DTO 的"读不到状态 ⇒ 造一个 `UNKNOWN`"改成"空白 ⇒ `null`"，方向对、用例也钉住了（T8/T9c 有正反对照），但 `docs/项目完整实施指导书 V2.3.md` 是权威顺序第 ② 位，它 §4.1.1.3 第 251/255 行**至今逐字写着"读不到记 `UNKNOWN`"**，而 `ed8c531` 只改了 `docs/acceptance/**` 与看板，**没有改指导书**。于是权威文档与代码互相矛盾，而这条矛盾在验收记录里**没有被登记**（R-d 登记的是另外两处"仍然造值"，恰好不覆盖这一处"不再造值"）。这正是硬约束 11 的反面：**改了值语义却没先改规格**。后续任何人据指导书实现或复核，都会做回 `UNKNOWN`。

---

## 1. 范围与方法

### 1.1 被评对象与工作区状态（先记账）

| 项 | 实测值 | 取法 |
|---|---|---|
| `git rev-parse HEAD` | `bf6dce9135962a90181be2b95ac8aa3b3b3ef40a` | 直接命令 |
| 任务所述 HEAD | `ed8c531` | 任务描述 |
| `git merge-base --is-ancestor ed8c531 HEAD` | 成立（`ed8c531` 是 HEAD 的祖先） | 直接命令 |
| `git diff ed8c531 HEAD -- synthetic-data-generator` | **空** | 直接命令 |
| `git status --porcelain -- synthetic-data-generator` | **空**（模块工作区干净） | 直接命令 |
| `git status --porcelain` 全仓 | 仅 ` M docs/acceptance/p1-05-8091-swap-20260911/8091-stdout.log` 与未跟踪的 `spark-jobs/**` | 直接命令 |

**结论**：`ed8c531` 之后到 HEAD 之间，模块源码**逐字节未变**，因此"评审 HEAD 的模块现状"＝"评审 `ed8c531` 的改动面"。全仓那一条改动与 `spark-jobs/**` 未跟踪文件**不是本轮产生的**，本泳道未触碰，也不在评审范围内。

### 1.2 `ed8c531` 的真实变更面（`git show --name-status`）

```
M  docs/acceptance/m1-9-second-adapter-20260912/README.md
M  docs/项目实施进度与任务看板 V2.2.md
M  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ExternalOrder.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ExternalProduct.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ExternalRefund.java
D  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/MallStatusVocabulary.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/ProductPage.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/adapter/SecondMallHttpAdapter.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiDispatchSink.java
M  synthetic-data-generator/src/main/java/com/graduation/generator/engine/MallApiGenerationEngine.java
M  synthetic-data-generator/src/test/java/com/graduation/generator/adapter/SecondMallAdapterOperationsTest.java
M  synthetic-data-generator/src/test/java/com/graduation/generator/engine/SecondMallDualTargetTest.java
M  synthetic-data-generator/src/test/java/com/graduation/generator/fixture/SecondMallFakeServer.java
```

`src` 侧 11 条（10 M ＋ 1 D）与提交信息自述一致；另有 `docs` 侧 2 条。**指导书 `项目完整实施指导书 V2.3.md` 不在变更面内**（这一条是 H1 的事实基础）。

### 1.3 实际执行的命令与证据级别

| 命令 | 结果 |
|---|---|
| `git show ed8c531 --stat` / `--name-status` / `--numstat` | 用于变更面与文件级比对 |
| `git diff ed8c531 HEAD -- synthetic-data-generator` | 空 |
| `git status --porcelain` | 见 1.1 |
| `mvn -o test -f synthetic-data-generator/pom.xml`（`JAVA_HOME=D:\Develop\JAVA17`，`MAVEN_OPTS='-Xmx896m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'`，`JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'`） | **`Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` ＋ `BUILD SUCCESS`，`exit=0`，约 21 s**。用例分布实测：`SecondMallAdapterOperationsTest` 9、`SecondMallDualTargetTest` 6、`GeneratorBoundarySourcePolicyTest` 5、`MallApiGenerationEngineTest` 15 |
| 只读检索（`Select-String` / 源码阅读） | 见 §3 各条 `file:line` 原文 |

**说明**：该次 E2 是本人自跑的，与总控记录的 `116/0/0/0` 与 21.6 s 量级一致；但**本人这次运行未把原始 surefire 日志落盘**（输出即阅即弃），因此本文引用的 116/0/0/0 只有口头读数、没有可复核的日志文件。总控侧的存档日志是 `.verify/m1-9-verify/e2-final-20260912-123946.out.txt`（另一泳道产物，本泳道只登记路径、未复核其内容）。证据级别限定为 **E2（模块自动化）**，不外推到 E3/E4。

**未执行**：未跑冻结判据脚本（`verify*.ps1`）、未跑真实 8091/8090/8092 链路、未做变异测试、未改任何文件。

---

## 2. 逐文件发现

### 2.1 `adapter/SecondMallHttpAdapter.java`（1061 行，本轮改动主体）

#### H2（高）`priceUnreadable` 只进不出：被排除的商品在页/流水/报告里全部消失

**原文**（`readCatalog`，L312–321）：

```java
        // 报价读不懂的商品被排除在目录之外——但绝不静默：一件都不剩时响亮失败，
        // 否则引擎会拿着空目录继续跑，运行报告里看不出差别
        if (products.isEmpty() && !priceUnreadable.isEmpty()) {
            throw new MallOperationException("listProducts",
                    "本次目录 " + priceUnreadable.size() + " 件商品的报价都读不懂（"
                            + String.join("、", sortedDistinct(priceUnreadable))
                            + "）：目录为空即响亮失败，绝不静默返回空目录继续跑");
        }
        return new ProductPage(products, products.size(),
                sortedDistinct(unmappedWords), sortedDistinct(stateMissing));
```

`priceUnreadable` 在 `readCatalog` 里只出现在四处：L305 的 `new ArrayList<>()`、L307 传入 `toProduct`、L314 的"**整份**目录都读不懂才炸"、L317 拼进异常文本。**它没有被交给 `ProductPage`**——`ProductPage`（`adapter/ProductPage.java:24-25`）只有两个缺口通道：`unmappedStateWords` 与 `stateFieldMissing`。

而 `toProduct` 的 Javadoc（L782–783）对外承诺的是：

```java
     * @param priceUnreadable 商城没给 {@code unit_price_cents}（或给的不是整数分）的商品 ID 出口；
     *                        被记进去的商品<b>不会</b>出现在返回页里，但一定会被点名报出来
```

**"一定会被点名报出来"在部分缺价的场景下不成立**：当目录里既有可读价的商品、又有读不懂价的商品时（最现实的场景：商城上万件商品里有一件的 `unit_price_cents` 是 `"12.345"`），`products` 非空 ⇒ 不抛异常 ⇒ `priceUnreadable` 随局部变量一起消失。SKU 名单只进了 `log.debug`（L813），**不进页、不进运行预检的缺口行、不进运行报告**。

消费端坐实"页里没有这条通道"：引擎预检（`engine/MallApiGenerationEngine.java:259-260`）只取两个清单

```java
        String unmapped = describeUnmappedStatuses(page.products(),
                page.unmappedStateWords(), page.stateFieldMissing());
```

```java
        if (unmapped != null) {
            // 缺口单独占一行（SKIPPED，且不是真实调用）：这样"预检读了几次目录""在售几件""多少件因状态缺口被排除"
            // 三件事在流水里各自可数，不会被合并成一句话而失去可核对性。
            journal.append(MallDispatchPlan.OP_LIST_PRODUCTS, false, null, null, null, null,
                    OperationJournalEntry.STATUS_SKIPPED,
                    "商城状态词无法映射到规范状态词表/商城未给状态字段，%s 商品被排除在可用目录之外".formatted(unmapped),
                    false);
            notes.add(("目录缺口：%s 商品的状态词无法映射到规范状态词表（或商城未给状态字段）——"
```

（`MallApiGenerationEngine.java:266-273`——流水与运行说明里能报的价格类缺口只有"状态词"这一种。）

调用方拿不到任何价格缺口清单，因此也无从报出。

**为什么这是缺陷而不是口味**：状态词读不懂这件商品会被"排除 + 点名"（这正是 `ed8c531` 的修复目标），价格读不懂却只有"排除"。同一文件、同一函数、同一个"读不懂就排除"的策略，两条通道的取证能力**不对称**，且不对称的方向恰好是"少说话"。后果是运维看到"目录 100 件、在售 99 件"，无法分辨少的 1 件是商城下架、状态词未登记、还是报价读不懂——只能去翻 debug 日志。

**为什么归"高"**：①它是本轮修复主题（不让证据静默消失）在**同一函数内的漏网点**；②Javadoc 明确承诺了一个实现里不存在的出口，属"文档与代码相反"；③用例把"排除"钉住了，却**无法**证伪"点名"（见 2.6 的 M6），所以这条缺陷不会被现有 116 条用例中的任何一条变红。

**类别**：A（静默改写/丢失证据）＋ E（约定"排除即点名"未落在同一处）＋ F（用例只钉了排除、没钉点名）。

#### M1（中）`centsToYuan` 的 Javadoc 说"负数响亮失败"，实现接受负数

**原文**（L633–645）：

```java
     * @throws MallOperationException 非整数、负数、超长或非数字的金额
     */
    public static BigDecimal centsToYuan(String cents) {
        String text = cents == null ? "" : cents.trim();
        if (text.isEmpty()) {
            throw new MallOperationException(OPERATION_MONEY,
                    "商城未给出 unit_price_cents，无法得到规范单价");
        }
        if (!text.matches("-?\\d+")) {
            throw new MallOperationException(OPERATION_MONEY,
                    "unit_price_cents 必须是整数分，实际=" + abbreviateText(text)
                            + "（小数分说明商城口径不一致，本适配器不擅自取整）");
        }
```

正则 `-?\d+` **主动接受负号**，函数体内没有任何 `signum()` 检查，`new BigDecimal("-123").movePointLeft(2)` 正常返回 `-1.23`——即 Javadoc 承诺的"负数 ⇒ `MallOperationException`"是**假的**。

负数真正炸在哪：`toProduct`（L808–818）

```java
        BigDecimal price;
        try {
            price = centsToYuan(node.path(RESULT_UNIT_PRICE_CENTS).asText(null));
        } catch (MallOperationException e) {
            priceUnreadable.add(sku);
            log.debug("第二家商城的报价读不懂，商品已排除并登记为目录缺口：sku={} 原因={}", sku, e.getMessage());
            return null;
        }
        return new ExternalProduct(sku, node.path(RESULT_TITLE).asText(null),
```

它只 catch `MallOperationException`；而负价最终由 `adapter/ExternalProduct.java:37-39` 拦下：

```java
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("price 必须为非负金额：" + price);
        }
```

`IllegalArgumentException` **不是** `MallOperationException`（二者无继承关系，逐字确认过 `ExternalProduct` 与 `MallOperationException` 的定义）。后果链条：

1. `MallApiDispatchSink.write`（L148–165）只 catch `MallOperationException`，因此这个 `IllegalArgumentException` 会**穿透 `write()`**：`failed++` 不执行、该次调用**不写 `OPERATION_JOURNAL` 的 FAILED 行**、`notes` 不记录，整个异常直接冒到运行调用方。
2. 于是 `failed_count` 与实际失败次数**对不上**，硬约束 6 所依赖的"流水是这次真发出去的形状"出现空洞。
3. 异常文本也不合 M1 期望："price 必须为非负金额：-1.23" 既没有操作名，也没有 SKU。

**取证限制（必须先说清）**：夹具 `SecondMallFakeServer` 的 `itemJson` 生成的价格恒为非负，因此**当前无法用现有夹具构造负价**，这条路径没有被任何用例覆盖（我也没有构造新用例——本泳道只读）。所以本条是"读代码得到的确定事实＋未实测的后果推断"，不是实测结论。

**为什么归"中"**：真实商城返回负价的概率低，且失败方向是"炸"而非"静默写坏数据"；但"声明会抛的异常实际不抛、实际抛的类型不在声明的错误契约内"是硬错误，且会让流水少一行、计数少一次。

**类别**：E（错误契约/异常类型不统一）＋ A（本该进 `priceUnreadable` 的点名名单被绕过，SKU 取证丢失）。

#### M3（中）目录级失败话术只说"几件"，不点名是哪几件

**原文**（L315–318）：

```java
            throw new MallOperationException("listProducts",
                    "本次目录 " + priceUnreadable.size() + " 件商品的报价都读不懂（"
                            + String.join("、", sortedDistinct(priceUnreadable))
                            + "）：目录为空即响亮失败，绝不静默返回空目录继续跑");
```

这里其实**列出了 SKU**，问题在上一层：`listProducts` 抛出的这条 `MallOperationException` 会被 `write()` 捕获后只记 `e.getMessage()`，所以 SKU 是保留的——本条**经复核不成立**，撤回。

（保留说明：本条的原始怀疑是"只说件数不说 SKU"，核对 L316–317 后确认实现已列出 `sortedDistinct(priceUnreadable)`，故不作缺陷登记。此处保留复核痕迹，避免后人重复报警。）

#### M5（中）适配器**自己**的下单价格校验零覆盖

`readCreatedOrder`（L831–853）是 `ed8c531` 新增的"响亮失败"实现，三条失败路径：

```java
        JsonNode lines = result.path(RESULT_LINES);
        if (!lines.isArray() || lines.isEmpty()) {
            throw new MallOperationException("createOrder",
                    "应答 result 缺少 lines 明细，读不到商城记账单价：" + abbreviate(result)
                            + "；本适配器不接受\"下单成功但成交价未知\"");
        }
        for (JsonNode line : lines) {
            String cents = line.path(RESULT_UNIT_PRICE_CENTS).asText(null);
            if (cents == null || cents.isBlank()) {
                throw new MallOperationException("createOrder",
                        "应答 result 的明细行缺少 " + RESULT_UNIT_PRICE_CENTS + "（sku="
                                + line.path(RESULT_SKU).asText("?") + "）：价格缺失即响亮失败，"
                                + "绝不带 null 金额继续：" + abbreviate(result));
            }
```

要触发它们，夹具必须"下单**成功**（2xx + `success=true`）但应答里没有 `lines` 或没有行价"。实测夹具不具备这个形态：`SecondMallFakeServer.java` 中行价的唯一产出点是 L421–423

```java
                    lineNode.putNull("unit_price_cents");
                } else {
                    lineNode.put("unit_price_cents", item.unitPriceCents());
```

即"有的商品必给价；没价的商品在更早的 L401 就被 409 拒单"：

```java
                    fail(exchange, 409, "E_NO_PRICE",
                            "item has no price, cannot quote: " + item.sku());
```

全仓检索 `"lines"` / `unit_price_cents` 的用例命中只有 `SecondMallAdapterOperationsTest.java:287`（断言已建成订单的行数量），**没有任何用例**走到 `readCreatedOrder` 的三条失败分支。也就是说：**适配器为硬约束 3 新写的那段响亮失败，当前是零覆盖的死代码**，删掉它 116 条用例仍全绿。

**为什么归"中"**：这与 S6 的修复目标直接相关（"删掉价格静默降级"），却被"夹具侧拒单"这条**另一条路径**替代取证了（见 M6）。修复声称"新增用例钉住价格缺失必须响亮失败"，字面成立（确实有一个用例、确实是价格缺失、确实抛了），但**钉的是商城侧 409，不是适配器侧校验**。这两者一旦分离，未来夹具支持"下单成功不回 lines"时无人知道适配器会不会静默带 null。

**类别**：F（用例只覆盖了同类中的一条路径，留下"看起来已被钉住"的假绿灯）。

#### M2（中）第二处空白状态规范化，与 Javadoc 指认的所有者不一致

`readOrder`（L869–881）：

```java
        String mallState = result.path(RESULT_PAY_STATE).asText(null);
        String status = mallState == null || mallState.isBlank() ? null : mallState;
```

而 `readOrder` 的 Javadoc（L863–864）把这件事记在别人账上：

```java
     * 商城没给 {@code pay_state} 时保持 {@code null}（{@link ExternalOrder} 的紧凑构造器把它读成
     * "无法读取"），<b>不编造一个商城没给过的词</b>。</p>
```

两处都在做"空白 ⇒ null"：`ExternalOrder` 紧凑构造器（`ExternalOrder.java:38-40`）与 `readOrder` L875。因为 L875 已经把空白变成 `null`，构造器里的 `isBlank()` 分支在这条路径上**永远不会命中**——它只对"直接 new DTO 的调用方"生效。同一语义有两个所有者，且文档指认的那个不是当前路径上起作用的那个。

**为什么归"中"**：这不是"少一行代码"的洁癖问题。`ExternalOrder` 的 Javadoc 现在是"空白 ⇒ null"这条规则的**唯一文字所有者**，而测试 `SecondMallAdapterOperationsTest` 的用例（L478–499）走的是 `readOrder` 路径——也就是说：如果有人把构造器里那两行删掉（认为 L875 已经处理了），**用例仍然全绿**，而直接 `new ExternalOrder(...)`（例如未来新增的调用点、或 R-d 里 `created()` 那条路径）会退回"空白状态被当状态用"。这是"同一事实两份所有者 + 用例无法区分是谁在做"的组合。

**类别**：E（同一约定两个所有者）＋ F（用例无法区分哪个所有者在生效，无法防回归）。

#### L1（低）时间戳被解析两次，且该事实在报告里出现两次

L267–286：`listProducts` 先 `readCatalog`（内部 `call(...)` 解析 `/Date(...)/` 时间戳一次），随后若 `query.keyword()!=null` 又对 `page.products()` 做关键字过滤并 `new ProductPage(query.window(filtered), filtered.size(), page.unmappedStateWords(), page.stateFieldMissing())`。这一段**没有丢信息**（两个缺口清单原样带过去了），但它是"页被重建"的第二处：`total` 从 `page.total()` 改成 `filtered.size()`，于是同一次读取的"候选总数"在两条分支下语义不同（无关键字时是适配器看到的候选总数，有关键字时是过滤后的件数）。调用方若按 `total` 判断"是否发生分页截断"，关键字查询下会得到错误结论——而 `ProductPage.total` 的 Javadoc 明确写的是"**施加分页前适配器看到的候选总数**（用于运行报告说明"分页截断"是否发生）"。

**为什么归"低"**：当前没有调用点用关键字查询（引擎预检不带 keyword），所以无现存后果；但这是"同一字段两种口径"的潜在地雷。

#### L6（低）两个与参考适配器逐字重复的私有工具

`abbreviate` / `abbreviateText` / `stripTrailingSlash` / `encode` 在 `SecondMallHttpAdapter` 与 `ReferenceMallHttpAdapter` 中各有一份（各自 L1005/L1011、L985、L988 与 L449/L455、L712、L646 附近），实现逐字相同。适配器之间**刻意不共享代码**（两家商城"处处不同"的演示目标）是合理取舍，因此本条只作可读性提示，**不建议**在本轮合并：真要合并应走"抽公共基类/工具"的规格变更，而不是顺手抽。

#### L3（低）订单总额的内联判断不随类常量走

L876–879：

```java
        JsonNode total = result.path(RESULT_TOTAL_CENTS);
        BigDecimal totalYuan = total.isNumber() || total.isTextual()
                ? centsToYuan(total.asText())
                : null;
```

① `isNumber() || isTextual()` 没有对应的私有判据方法，与 L791（`state.isMissingNode() || state.isNull()`）风格不一致，属可读性；② `totalAmount` 在全仓**无消费点**（`grep totalAmount` 只命中定义、Javadoc 与两处测试，没有取值处），因此"读不到总额时静默变 null"当前无后果——但如果将来有人在报告里用它，`null`（商城没给）与 `null`（值存在但读不懂）就无法分辨，正是 H1 同族。登记为低，随 H1 一起处理即可。

### 2.2 `adapter/ExternalOrder.java` / `adapter/ExternalRefund.java`（S1 同类面修复）

#### H1（高）规格未同步：权威指导书仍要求"读不到记 `UNKNOWN`"

**代码侧（本轮改后）**：`ExternalOrder.java:13-20`

```java
 * @param status      商城侧状态原文（逐字符透出）。<b>商城没给状态字段（或给了空白）时是
 *                    {@code null}</b>：这个 {@code null} 是"商城没给"的标记，<b>不是商城原词、
 *                    不得当作状态使用</b>。这里永远不会出现生成器自己编的状态词
 *                    （曾经的 UNKNOWN 就是这么一个词）
```

`ExternalRefund.java:10-16`、`28-32` 同口径（`if (status != null && status.isBlank()) { status = null; }`）。

**规格侧（权威顺序第 ② 位，逐字未改）**：`docs/项目完整实施指导书 V2.3.md`

- L251：``| `ExternalOrder` | `orderId`、`userId`、`status`(String)、`totalAmount`(BigDecimal)、`itemCount`(int) | `status` 是商城原样文本；读不到记 `UNKNOWN`，未返回记 `null` / `-1` |``
- L255：``| `ExternalRefund` | `refundId`、`orderId`、`status`(String)、`amount`(BigDecimal) | `status` 是商城原样文本；读不到记 `UNKNOWN` |``

L260–271 的**补记（2026-09-12）**只修正了 `ExternalProduct.status` 的口径（映射到规范词表），**没有触碰这两行**。`ed8c531 --name-status` 证明本提交没有改指导书（§1.2）。

**为什么这是缺陷**：§4.1.1.3 的表头逐字写着"在已列字段之外新增字段，必须先改本节再实现"，硬约束 11 逐字写着"§4.1.1.3 之外的新增字段/方法，必须先改本节再实现"。这里改的**不是新增字段，而是既有字段的取值语义**——而项目自己已经把"DTO 字段语义属契约面"立成了先例：残余 **R-d** 正是因为 `ExternalOrder.created(...)` 仍造 `"CREATED"`、`ReferenceMallHttpAdapter` 仍造 `PAID`/`CANCELLED`/`COMPLETED` 而被登记为"既有口径、只报不改"。同一条尺子量下来，本轮把 `UNKNOWN` 拿掉同样动了 DTO 语义面，却既没改规格、也没登记为残余 ⇒ **一次不可见的规格偏离**。

**后果（具体、可指认）**：

1. 下一位按指导书实现/复核的人会（正确地）照 L251 做回 `"UNKNOWN"`，把刚修掉的缺陷重新引入，而"代码里有 `null`、规格里写 `UNKNOWN`"这件事没有任何机械判据会拦（W1 白名单只查字符串 `= "UNKNOWN"` 是否出现，查不出"规格要求出现"）。
2. 规格里 `null` 只有一个含义（"未返回"），代码里 `null` 现在承担两个含义（"未返回"与"商城给了空白"）——这与 `ExternalRefund` 自己的 Javadoc 讲的道理（"编一个词会让'商城说的是它'与'生成器猜的它'再也分不开"）是同一个病，只是换成了空值：**"商城给了个空字符串"与"商城压根没给"在代码里已不可分辨**。规范事件不消费这两个字段（补记 L267–268 已说明只进流水/报告），所以当前无数据后果，属取证口径问题。

**最小修法（不建议在本泳道落地，留总控裁决）**：把指导书 L251/L255 的"读不到记 `UNKNOWN`"改成与代码一致的口径（"读不到/未给一律 `null`，需要非空处由调用方响亮失败"），或把本处登记为 R-d 同族残余。**两条路选一条，不能两条都不选**——现在是"两条都不选"。

**类别**：D（值语义变更未先改规格）＋ A 的相邻面（不是造值，但把两种"没有"压成同一个值）。

#### L4b（低）同一文件内的自相矛盾：`created()` 仍造 `"CREATED"`

`ExternalOrder.java:27-30`：

```java
    /** 下单应答只有 {@code {"orderId": "..."}} 时的最小构造（实测：POST /orders 的应答只有 orderId） */
    public static ExternalOrder created(String orderId, String userId) {
        return new ExternalOrder(orderId, userId, "CREATED", null, -1);
    }
```

与同文件 L15–18 的"这里永远不会出现生成器自己编的状态词"直接相反。全仓**零调用点**（与残余 R-d 的记述一致），因此**无控制流后果**；但它是"删了一半"的痕迹：`ed8c531` 把 DTO 的造值拿掉了，却把这个造值工厂留在原地，而它恰好是 F-25/S1 的原始症状。按 delete-first，要么删掉这个零调用工厂，要么在 Javadoc 里显式写明它是豁免的例外。**登记为"已被 R-d 记述但本轮未收口"**，不计入本轮新增缺陷。

#### L5（低）测试把"造出来的词"钉成了契约

`test/adapter/MallTargetAdapterOperationsTest.java:127`（本提交未触碰，但在被评模块内、且与 H1 同类）：

```java
        assertEquals("PAID", paid.status(), "HTTP 成功即视为已支付，不读取不存在的字段");
```

断言的提示语自己承认"不读取不存在的字段"，却把参考适配器**硬编码**的 `"PAID"`（`ReferenceMallHttpAdapter.java:245`）钉成期望值。这是 W3 的同族形态：**测试成为自造语义的护城河**。它解释了为什么 S1 的修复只在第二家落地——参考商城那三个字面量被既有用例锁住了，动它要先动用例。

#### 附带更正（不是缺陷，是对验收记述的更正）

`docs/acceptance/m1-9-second-adapter-20260912/README.md:209`（S5 行）逐字引用的签名

```
`describeUnmappedStatuses(products, unmappedStateWords, stateFieldMissing)` 为 `static`
```

**与实际不符**：当前实现是 `MallApiGenerationEngine.java:338-340`

```java
    public static String describeUnmappedStatuses(List<ExternalProduct> products,
                                                  List<String> unmappedStateWords,
                                                  List<String> stateFieldMissing) {
```

即**三参（带 `products`）**；README 引的是两参旧形状。方向对（都是 static、都把词表放参数位），但验收记录里的"原文引用"与磁盘不一致，属复核记述瑕疵。同一行还写"适配器实例字段仅 `credentialLookup`/`timeout`/`mapper`"——这一点我逐字核对**成立**（`SecondMallHttpAdapter` 无集合型实例字段）。

### 2.3 `adapter/ProductPage.java`（新增两个字段与工厂）

#### D2（低，与 H1 同类）两个新增记录分量未先进规格

`ProductPage.java:24-25`：

```java
public record ProductPage(List<ExternalProduct> products, int total,
                          List<String> unmappedStateWords, List<String> stateFieldMissing) {
```

指导书 §4.1.1.3 L245 对该类型的登记仍是两字段：

```
| `ProductPage` | `products`(List)、`total`(int) | `total` 不小于 `products.size()` |
```

按该表表头与硬约束 11，"新增字段必须先改本节"。**为什么判低而不判高**：`ProductPage` 在指导书里的自述是"§4.1 `listProducts` 的返回类型；**指导书未定义字段**，此处为最小设计"，且验收 README §13 已把它登记为待收口项（D-015 家族）；也就是说这条**已经在制度上被看见了**，缺的是收口动作，不是发现。H1 不同：那两条是**表里已有的字段、明确的取值规定**，被静默改掉了。

**另需注意的一处口径**：`stateFieldMissing` 的 Javadoc 写

```java
 * @param stateFieldMissing 本次读取里<b>商城没给状态字段（或为空）</b>的商品 ID（去重、按字典序）。
```

而报告里渲染出来的文字是（`MallApiGenerationEngine.java:363`）：

```java
            detail.append("商城未给状态字段的商品：").append(String.join("、", missing));
```

两者不同：集合里同时装"字段缺失"和"字段给了但为空白"，报告只声称"未给字段"。**这是一个诚实的合并**（两种情况对运维的动作都是"去补商城侧数据"，Javadoc 已显式写出"（或为空）"），但报告文本比事实窄一点：运维照报告去商城查"为什么没给字段"，实际那条记录可能给了个空串。**判低**：无数据后果，仅措辞。

### 2.4 `adapter/ExternalProduct.java`

`ExternalProduct.java:37-39` 的负价校验本身是本轮新增的正确收口（"金额一律非负"）。它在 M1 里作为"负价真正的拦截点"出现：类型与适配器声明的错误契约不一致（`IllegalArgumentException` vs `MallOperationException`）。其余字段与 `onSale()` 的规范词判定与补记口径一致（`onSale()` 判规范词 `on_sale`）。

### 2.5 `engine/MallApiDispatchSink.java` / `engine/MallApiGenerationEngine.java`

#### 结论：本轮 S4/S5/S7 的引擎侧整改**经复核成立**

- S4：`engine/**` 内无 `MallStatusVocabulary`、无 `instanceof` 旁路；退休文件 `adapter/MallStatusVocabulary.java` 已删除（`D` 状态），全仓无残留引用。
- S5：缺口事实全部走**参数与返回值**。`describeUnmappedStatuses` 是 `static`（L338），计数在方法内按传入的 `products` 现算（L344–349），两个清单经 `distinctSorted` 去重排序（L369–374）保证可复现；`MallApiDispatchSink` 的构造器把 `productCatalog` 与 `operationRoutes` 各做一次 `List.copyOf` / `Map.copyOf`（L110、L113），`availableProducts = new ArrayDeque<>(...)` 是**每次运行新建**的实例（L111）——即"每次运行一个 sink 实例"的口径下不存在跨运行累计。适配器侧已无集合型实例字段（逐字核对成立）。
- S7：缺字段与未登记词两条通道在 `toProduct`（L793–802）与 `ProductPage`（两个独立分量）里确实分开。

#### 需登记的两处「相邻风险」（均非本轮新增，故不单列缺陷编号）

1. `rewriteProduct`（L367–383）用 `productCatalog.stream()...findFirst().ifPresent(product -> {...})` 包裹整个改写——**外键商品在目录里找不到时静默不改写**，而方法 Javadoc 的立场是"两种异常各有明确出口（响亮失败）"。当前`productRefByCanonical` 的值只来自目录（`dispatchProduct` L214），且目录不变，所以**现状不可达**；但"零 `ifPresent` 静默"与本类 L119–120 自述的"刻意没有回退分支"是同一件事的两半，建议下次触碰该文件时一并收口。
2. `TargetCapabilities.verdict`（`adapter/TargetCapabilities.java:38`）与 `TargetCheckResult.verdict`（`adapter/TargetCheckResult.java:30`）都用 `getOrDefault(capability, CapabilityVerdict.UNDETERMINED)`。这不是 Class A 命中：三态语义里"缺键 ⇒ 不知道"是规格明文（§4.1.1.3 L241"缺键 ⇒ `UNDETERMINED`"、硬约束 5"缺键、未知、探测不到一律 `UNDETERMINED`"），Javadoc 也逐字写了理由。登记为"已被规格授权的默认值"，防止后人误判为静默兜底。

### 2.6 测试与夹具（`SecondMallAdapterOperationsTest` 9 例 / `SecondMallDualTargetTest` 6 例 / `SecondMallFakeServer`）

#### 好的部分（先说清楚，避免"只挑刺"）

- **正向对照齐备**：T8 组 `mallStatusMissingKeepsNullAndNeverInventsWords`（L470–499）先断言"商城给了 `pay_state=NEW` ⇒ `status` 恰为 `"NEW"`"（L472–473），再断言"不回字段 ⇒ `null`"（L478–480），并用 `mall.orderJson(...).path("pay_state").asText()=="NEW"`（L483）证明"状态缺失是应答形态、不是夹具坏了"——这是**可证伪的独立对照**，不是同义反复。
- **反向断言**：`SecondMallDualTargetTest` 断言流水明细**不得**包含 `UNKNOWN`/`CREATED`（L376），把"不造值"从"实现里没有"升级为"产物里没有"。
- **空对照防护**：T7 组 L405–406 用 `assertFalse(probeRequests.isEmpty(), "test() 至少要探一次代表路由，否则\"没探那三项\"是因为它什么都没探（空对照）")` 挡掉了"因为什么都没探所以没探那三项"的假通过——这是本轮质量最高的一条断言。
- **能力声明不联网被独立钉住**：L395–396 `assertEquals(0, mall.exchanges().size(), "capabilities() 是声明口，不许联网")`。
- 全模块 `@Disabled` / `@Ignore` 检索：**0 命中**；`assumeTrue` 仅 `meta/GeneratorMetaStoreTest.java:58`（既有，且本次 E2 未触发跳过，`Skipped: 0`）。

#### M6（中）T2b 的 `@DisplayName` 承诺大于断言实际覆盖

`SecondMallAdapterOperationsTest.java:139-141`：

```java
    @Test
    @DisplayName("T2b 价格缺失：商城没给 unit_price_cents 时下单必须响亮失败，绝不静默 null 或当成 0 元")
    void missingPriceFailsLoudly() throws IOException {
```

用例实际断言的是 `e.getMessage().contains("E_NO_PRICE")`（L161）——`E_NO_PRICE` 是**夹具自己**在 `SecondMallFakeServer.java:401` 抛的 409。也就是说这条用例证明的是"**商城拒单时适配器会抛**"（`call()` 的非 2xx 分支，本来就有），而用例标题、注释（L171–172 "报价读不懂的那件**被排除并响亮报错**"）与提交信息（"新增用例钉住价格缺失必须响亮失败"）读起来像是在证明**适配器侧的校验与点名**。二者是不同的机制，见 M5、H2。

**为什么归"中"**：不反对用"商城拒单"作为端到端证据（它是真实的、且有 L168–170 的独立反证"商城侧 0 笔成交"）。问题在于**这是唯一一条覆盖"价格缺失"的用例**，而它把读者的注意力从"适配器侧那段新代码有没有被跑过"引开了。诚实标注应当是："本用例覆盖商城侧拒单路径；适配器侧 `readCreatedOrder` 校验未覆盖（见 M5）"。

#### M7（中）三处死夹具 API，其中一处 Javadoc 承诺了并不存在的覆盖

`fixture/SecondMallFakeServer.java`：

```java
244:    /** 新增一件商品，带一个合法的在售状态词（其余字段为第二家的形状） */
245:    public void putItem(String sku, String title, long categoryCode, long unitPriceCents) {
...
249:    /**
250:     * 新增一件<b>应答里没有 unit_price_cents</b> 的商品（价格缺失的夹具形态）。
251:     *
252:     * <p>存在的理由只有一个：验证适配器在"商城这条记录里没有价格"时<b>响亮失败</b>，
253:     * 而不是把 {@code null} 金额静默带进流水与运行报告。</p>
254:     */
255:    public void putItemWithoutPrice(String sku, String title, long categoryCode) {
...
259:    /** 新增一件<b>应答里没有 state 字段</b>的商品（字段缺失的夹具形态；与"读不懂的词"分开） */
260:    public void putItemWithoutState(String sku, String title, long unitPriceCents) {
```

全测试树检索这三个方法名：**零调用点**（用例走的是 `removeItemPrice(sku)` L149/L185 与 `overrideItemState(sku, null)` L446）。其中 `putItemWithoutState` 的注释与 `overrideItemState` 的 `@param state` 注释（L234–235）说的是**同一件事**，而真正被用的那个在别处——夹具里出现了一个"看起来覆盖了缺字段场景、实际从没被跑过"的 API。`putItem` 则是"新增指定商品"的**第二所有者**（`SecondMallFakeServer` 的构造器已按 `ITEM_COUNT` 造满目录，用例只用 `itemSkus()` 取既有商品）。

**为什么归"中"**：这不是风格问题。夹具 API 的 Javadoc 是"我们有覆盖"的书面声明，而覆盖率只认调用点。三个方法里至少 `putItemWithoutState` 会让人（包括未来的复核者）以为"缺 state 字段"这条路径有一个专门的夹具入口在用。**修法**：删掉零调用方法（delete-first），或让 M5 的新用例真的用起来。

#### L2（低）单测的"进制常量"断言是弱版本

```java
134:    /** 进制必须是 100：换算常量不许被改成 1000 之类而无人察觉 */
135:    private static final int CENTS_PER_YUAN_IS_EXPLICIT = 100;
...
123:        assertEquals(CENTS_PER_YUAN_IS_EXPLICIT, SecondMallHttpAdapter.CENTS_PER_YUAN);
```

这条断言的实际强度是"生产常量 == 单测里独立写的 100"，**不是**"换算结果使用 100 进制"。硬约束 10 要求"换算系数必须是适配器内的显式常量并有单测覆盖"——常量本身被覆盖了，但"换算真的按它算"只由 L110（585 分 ↔ 5.85 元的独立复算）间接证明：如果将来有人把 `movePointLeft(CENTS_PER_YUAN_LOG10)` 改成手写 `movePointLeft(3)` 而保留常量，L110 会红（好事）；但如果同时改了夹具的复算方式，就没有守卫了。建议改成"由 `CENTS_PER_YUAN` 推导出期望值再断言"（例如 `assertEquals(new BigDecimal("12345").movePointLeft(2)...)` 与 `centsToYuan` 对比，其中 2 由 `log10(CENTS_PER_YUAN)` 得出）。**低**：当前不存在错误。

#### L6b（低）断言里的冗余与脆弱形态

- `SecondMallDualTargetTest` 增补的 `assertTrue(routeResolutions <= 1)` / `assertTrue(routeResolutions >= 1)` 紧跟在已有的 `assertEquals(1, ...)` 之后，是同值的冗余（无害，但读起来像"三个不同的事实"）。
- `assertEquals(ITEM_COUNT - 1, page.products().size())`（L174）把预期绑定在夹具规模常量上，属可接受；但同一条用例后面又 `mall = new SecondMallFakeServer(null, null, 1)`（L183）**重绑字段**，使前置断言与后置断言作用在不同的夹具实例上，读用例时需要来回确认"这句用的是哪个 mall"。不影响正确性（前序断言已完成），属可读性。

### 2.7 `boundary/GeneratorBoundarySourcePolicyTest.java`（未在本提交变更面内，但为 W1 守卫）

`GeneratorBoundarySourcePolicyTest.java:181-238` 的 `engineSourcesCarryNoMallRouteLiterals` 仍然只扫 `path` 含 `/engine/` 的源文件，正则

```java
Pattern routeLiteral = Pattern.compile("\"/[a-z0-9][^\"]*(?:/api/|/open/)[^\"]*\"|/(?:api|open)/v[0-9]");
```

本轮**未被修改**（不在 `ed8c531` 变更面），验收 README 也如实记为 `20→20`（断言数未变）。两条已知盲区与验收记录一致：① 只覆盖 `/engine/`，不覆盖 `/service/`、`/web/`、`/report/`；② 只匹配"小写字母/数字开头且含 `/api/` 或 `/open/` 的引号字符串"，大写路径字面量逃检（README §13.5 已登记第 7 项）。**本泳道补充一条实测事实**：`engine/**` 当前确实 0 命中商城字面量（S4/S6 的整改成立），因此这是"守卫覆盖不足"，不是"已存在残留"。

### 2.8 `web/dto/GeneratorApiDtos.java` / `service/TargetProbeService.java`（未在本提交变更面内）

#### M4（中）`/targets/{id}/test` 把"静态声明"当"实测值"回给 API 消费方

**接口文档原文**（`web/dto/GeneratorApiDtos.java:132-140`，本提交**未改**）：

```java
     * <p>S4a 追加 {@code capabilities}（2026-09-11）：它是本端点的<b>实测结论</b>，键取自
     * {@code MallCapability}（{@code product/user/order/refund/behavior/reset_state/admin}），
     * 值只有三态 {@code SUPPORTED/ABSENT/UNDETERMINED}。
...
     * <p>注意区分：{@code generator_target.capabilities} 是运营方的<b>声明</b>，本字段是<b>实测</b>。
     * 两者不一致时以实测为准，且探测结果不写回目标配置行。</p>
```

**实际产出**（`service/TargetProbeService.java:51-63`）：`jsonCapabilities` 把 `TargetCheckResult.capabilities()` 里**每一项**平铺进响应体，**没有** `declared` 标记、没有分列、没有"哪些是声明"的字段：

```java
        for (MallCapability capability : MallCapability.values()) {
            CapabilityVerdict verdict = check.capabilities().get(capability);
            if (verdict != null) {
                json.put(capability.key(), verdict.name());
            }
        }
```

而 `SecondMallHttpAdapter.test()`（L467–479）产出的 7 项里有 **3 项是静态声明、不是测量**：

```java
        // 静态声明（未探测）：这三项没有代表路由、也就不发探测请求；取值与 capabilities() 同源，
        // 不写成 SUPPORTED，也不谎称是探测结果
        verdicts.put(MallCapability.ADMIN, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.REFUND, CapabilityVerdict.ABSENT);
        verdicts.put(MallCapability.RESET_STATE, CapabilityVerdict.ABSENT);
```

**后果**：`GET/POST /targets/{id}/test` 的响应体里 `refund=ABSENT`，而该端点的 DTO 文档逐字告诉消费方"本字段是实测"，且响应体自身没有任何字段能区分"测出来的没有"与"声明没有（没测）"。唯一披露在 `detail` 这句中文散文里（L593–595 有，且被 T7 的 L410–411 钉住，这是加分项），以及被评适配器的 Javadoc 里；**按结构化字段消费的人（页面、脚本、下游）拿到的是"实测 ABSENT"**。

**为什么归"中"而不归"高"**：①源码侧披露到位、且有用例钉住文案；②方向不是"把不知道说成支持"；③它**不是本轮引入**的（`GeneratorApiDtos` 早于本提交，静态 ABSENT 也是上一轮 S3 的既定口径，本轮按裁决只做"披露"）。但本轮的任务恰恰是"把静态声明与测量分开"（S3），而这正是这条通道**唯一没被覆盖的出口**：验收 README §13.2 的 S3 行说披露做到了"Javadoc / 运维文案 / 用例三处"，`TargetCheckResult` 类级 Javadoc 第 8 行也仍写着

```java
 * <p>{@code capabilities} 里的每一项都是<b>本次探测的实测判定</b>，与 {@code generator_target.capabilities}
```

**四处声明里有两处（DTO、`TargetCheckResult` 类级 Javadoc）仍在说"实测"**。同一事实两份相反说法，"披露三处"的结论因此不完整。

**可执行的最小修法**（供总控裁决，本泳道未实施）：①把 `TargetCheckResult` 类级 Javadoc 与 DTO 的那两句改成与适配器一致的"含静态声明项，逐项来源见 `detail`"；或 ②把 `target_id/reachable/detail/capabilities` 之外增一个 `declared_capabilities` 列表（**但这触发硬约束 11，需先改规格**——这正是 M4 与 D2/H1 的同一个坑：契约类字段的扩展现阶段必须先走规格）。

**类别**：B（用断言/声明冒充测量）＋ E（同一契约在四处有四种说法）。

### 2.9 `config/GeneratorBeans.java`（登记处，S4 的落点）

`GeneratorBeans.java:67-70` 逐字核对：三个适配器各 `new` 一次并进注册处（`FileModeTargetAdapter`、`ReferenceMallHttpAdapter`、`SecondMallHttpAdapter`），`MallTargetAdapterRegistry` 对重复 `adapter_type` 抛 `IllegalStateException("adapter_type 重复注册…一个类型只允许一个所有者")`、对未注册抛 `IllegalArgumentException` 并列出已注册类型——硬约束 1 成立。**适配器单例**这一事实也是本条评审中所有"实例字段/共享状态"判断的前提（`S5` 修复之所以必要，正是因为这里是单例）。

---

## 3. 缺陷类 A–F 复发扫描

扫描范围：模块全量 `src/main/java/**`（adapter / engine / config / service / web / report / cli / contract / meta / core）+ `src/test/java/**`，逐个类按"类不是点"的口径找同族形态。**命中必须给 `file:line` ＋ 原文 ＋ 后果**。

| 类 | 命中 | 证据（`file:line` ＋ 原文） | 本类最值得注意的一点 |
|---|---|---|---|
| **A 静默改写/发明证据** | **部分命中（1 高、2 中、若干既有）** | H2 `SecondMallHttpAdapter.java:320-321`（`priceUnreadable` 无出口）；M1 同文件 `:641` 正则接受负号 + `:811` 只 catch `MallOperationException` ⇒ 负价绕过点名名单；**既有**：`ExternalOrder.java:29` `"CREATED"`、`ReferenceMallHttpAdapter.java:245/259/278` `"PAID"/"CANCELLED"/"COMPLETED"`（R-d 已登记）、`MallTargetAdapterOperationsTest.java:127` 把造值钉成契约 | 本轮**没有**新增任何"造值/发明"形态（`= "UNKNOWN"` 在 `adapter/**` 已归零，实测）；A 类的复发全部是"证据静默丢失"这一半（H2）与"既有造值未收口"（R-d） |
| **B 用断言冒充测量** | **命中 1 中（结构性、非本轮新增）** | M4：`GeneratorApiDtos.java:132` 原文"它是本端点的<b>实测结论</b>"＋`:139`"本字段是<b>实测</b>"，而 `SecondMallHttpAdapter.java:473-475` 三项 `ABSENT` 未探测、`TargetCheckResult.java:10` 原文"里的每一项都是<b>本次探测的实测判定</b>" | 适配器内部口径已改对（Javadoc ＋ `describeProbe` L593–595 ＋ T7 L408–411 三处自报"静态声明（未探测）"），**但契约文档与 DTO 类仍写"实测"，且结构化响应无 `declared` 标记**。B 类的复发点从适配器搬到了契约文档层 |
| **C 进程级/跨运行状态冒充本次运行事实** | **未命中（本轮修复有效）** | ① `SecondMallHttpAdapter` 实例字段仅 `credentialLookup`/`timeout`/`mapper`，无集合型累计；②`ProductPage.java:28-33` 每次 `List.copyOf` 快照；③`MallApiGenerationEngine.java:338` `describeUnmappedStatuses` 为 `static`、计数在方法内现算（`:344-349`）；④`MallApiDispatchSink.java:110-113` 构造时 `List.copyOf`/`Map.copyOf`、`:111` `ArrayDeque` 每运行新建；⑤`MallApiDispatchSink.java:424-430` 缺口按事件类型去重（`recordedGapEventTypes`）记在运行级 sink | 唯一残余风险是"多目标**并发**"这一维度：`SecondMallDualTargetTest` 覆盖的是同实例**跨运行/顺序双目标**串台，README §13.5 第 7 项已如实标注并发未覆盖。本泳道**未新增证据**，同意"未取证" |
| **D 未先改规格的新公开接口 / 引擎拥有商城知识** | **命中 2（1 高 1 低）** | H1：`docs/项目完整实施指导书 V2.3.md:251` 原文"`status` 是商城原样文本；读不到记 `UNKNOWN`"、`:255` 同，而 `ExternalOrder.java:38-40`/`ExternalRefund.java:30-32` 已改成空白⇒`null`；D2：`ProductPage.java:24-25` 新增两个记录分量，指导书 `:245` 仍只有 `products`/`total`。**引擎侧未命中**：`engine/**` 内无商城路径字面量、无 `adapter_type` 分支、无词表常量（S4/S6/S7 整改成立） | 硬约束 11 的坑不在"新增字段"（那个项目已经会防），而在**改既有字段的取值语义**——本轮就是从这个缺口漏过去的 |
| **E 魔法串/未公开约定当契约** | **命中 2 中 1 低＋若干低** | M4（同一契约四种说法：DTO 说实测、`TargetCheckResult` 类级说实测、适配器 Javadoc 说声明、`detail` 散文说未探测）；M2（`ExternalOrder` 构造器与 `SecondMallHttpAdapter.java:875` 两个所有者）；L3（`readOrder` 内联判断不随常量）；**已改好的**：`SecondMallHttpAdapter.java:582-583` 报错话术逐字点名 `config_json.<KEY>=<VALUE>`（S2 修复成立，T9c 有正向对照 `:339-347`）；**跨模块同名常量**：`SecondMallFakeServer.java:121` `MISSING_UNIT_PRICE_CENTS = -1L` 与 `ExternalOrder.java:22` 的 `-1` 语义相同（"未回传"哨兵）却无共享定义 | S2 的"键名不点名"已修好；E 类的新复发点全在"同一事实谁说了算"上 |
| **F 测试把被测缺陷固化成契约 / 弱断言** | **命中 1 中＋2 低＋既有 1 低** | M6（T2b `@DisplayName` 承诺适配器侧响亮失败，实际只验夹具 409）；M7（`SecondMallFakeServer.java:255/260` 两个零调用夹具 API，其后者的 Javadoc 声称覆盖"字段缺失"场景）；M5（`readCreatedOrder` 三条新增失败路径零覆盖）；L2（`:123` 常量相等 ≠ 换算按常量算）；**既有**：`MallTargetAdapterOperationsTest.java:127` | 本轮**没有**新增 `@Disabled`/`@Ignore`/`assumeTrue`（全模块检索 0 命中新增），W1/W2 的 `startsWith`/`split("(")` 确实归零——F 类的复发形态变成了"**用另一条路径的失败冒充被测路径的失败**"（M6）与"**夹具 API 承诺了不存在的覆盖**"（M7） |

---

## 4. 与 S1–S7 / W1–W3 的映射：修好了吗？有没有新问题？

判定口径：以 `ed8c531` 的实际 diff ＋ 当前磁盘状态为准；**不引用冻结判据脚本的读数**（本泳道未跑脚本）。

| 项 | 处置（代码侧实测） | 新问题 |
|---|---|---|
| **S1** 订单状态别名复合格式 | **成立**。`ORDER_STATE_ALIAS` 已删；`readOrder`（`:874-875`）只透出商城原词；T8 组有正反对照（`:472-473` 正向 `NEW`，`:478-480` 缺失⇒`null`），`SecondMallDualTargetTest.java:376` 反向断言明细不含 `UNKNOWN`/`CREATED` | **H1**：同类面（三个 DTO 的 `UNKNOWN`→`null`）改了值语义但**未改规格**，指导书 L251/L255 与代码冲突且未登记为残余 |
| **S2** 魔法串把门且报错不点名 | **成立**。单点常量 `CONFIG_FORMAT_KEY`/`CONFIG_FORMAT_VALUE`（`:96/:104`）；`describeProbe`（`:582-583`）逐字点名键与取值；`:591` 明确否掉"OPTIONS 通过＝已按格式声明"的误读；门控口径已统一（`capabilities()` 与 `test()` 的 BEHAVIOR 都要求显式声明 `behavior_path`） | 键仍属未公开约定（R-b，已登记）。**未见新问题** |
| **S3** 三能力位不探测直接 `ABSENT` | **口径披露成立、语义按裁决不变**。`capabilities()` L211-215 与 `test()` L428-430 的 Javadoc、`describeProbe` L593-595 的运维文案、T7 L384-411 的用例三处一致自报"静态声明（未探测）" | **M4**：披露**没有覆盖** `/targets/{id}/test` 的响应体与 DTO 文档——那里仍写"实测"，且结构化字段无 `declared` 标记。即 S3 的"混同 ABSENT 与 UNDETERMINED 举证责任"在**契约文档层**仍未收口 |
| **S4** 新公开 SPI ＋ 引擎 `instanceof` 旁路 | **成立**。`MallStatusVocabulary.java` 已删（`D`）；`engine/**` 无该类型、无 `instanceof` 旁路 | 无。**但**见 D2：`ProductPage` 的两个新分量顶替了它的位置，同样未先进规格（低） |
| **S5** 累计实例状态冒充本次运行 | **成立**。词表改参数传入、`describeUnmappedStatuses` 为 `static` 且计数现算、适配器无集合字段、sink 每运行新建 | 无新问题；**并发**维度仍未取证（与该轮自述一致）。另：验收 README `:209` 引用的签名与实际不符（见 2.2 附带更正） |
| **S6** 下单内目录补价（N+1）＋价格静默降级 | **代码成立**：`catalogPricesBySku` 已不在 `createOrder` 路径上；`readCreatedOrder` 新增逐行校验 | **M5 ＋ M6**：新增的适配器侧校验**零覆盖**（夹具无"下单成功但不回行价"形态）；唯一的价格缺失用例走的是商城 409。**H2**：目录侧价格缺口的第二半（部分缺价时点名）没有出口 |
| **S7** 缺字段被编码成中文句子常量 | **成立**。`MISSING_STATE_WORD` 已删；`toProduct` L793-802 两通道互不混入；`ProductPage` 两个独立分量 | 低：报告措辞"商城未给状态字段的商品"比集合口径（含"给了空白"）窄一点（见 2.3） |
| **W1** 前缀断言 | **改强成立**。全测试树无 `startsWith("NEW")` 形态；T8 用 `assertEquals("NEW", status)` | 无 |
| **W2** `split("(")[0]` 自截断 | **改强成立**。全测试树无 `split("(")` 形态 | 无 |
| **W3** 把"未探测即 ABSENT"固化成规格 | **重述成立（适配器侧）**。T7 注释 L380-383 明确"本用例只断言到'声明成 ABSENT 且调用走响亮失败'，**不断言**'被探测定性为不存在'"，并新增 `BEHAVIOR==UNDETERMINED` 反向对照（L390-391）与"必须自报未探测"的文案断言（L410-411） | **M4**：W3 的重述没有波及 DTO/`TargetCheckResult` 类级 Javadoc 与响应体。另：提交信息称 `GeneratorBoundarySourcePolicyTest` 断言 `20→20`，而本人实测该文件 5 个用例、本轮未修改——"20"是断言条数不是用例数，两者不可混读（此点 README 口径正确，仅提示勿外推） |

**新增问题汇总**：H1、H2、M1–M7、L1–L6（其中 L4b/L5 属既有残余的未收口，已单独标注）。**没有一项是"把已修好的又改坏"**；新问题集中在三类接口缝上：①改值语义不动规格（H1/D2）；②新写的失败路径缺"能触发它的夹具形态"（H2/M5/M6/M7）；③一个事实多个所有者/多个说法（M2/M4）。

---

## 5. 未取证（诚实标注，勿外推）

1. **未跑冻结判据脚本**（`verify.ps1`/`verify-v2.ps1`/`verify-fix*.ps1`）：本文所有"成立/已消除"的判断均来自**读源码 ＋ 自跑 E2**，不是判据读数；判据的读数以总控与既有复核报告为准。
2. **E2 日志未落盘**：本人自跑得到 `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0` ＋ `BUILD SUCCESS`（`exit=0`），但**未保存原始 surefire 日志**，因此该读数**不可被第三方复核**；可复核的是总控存档的 `.verify/m1-9-verify/e2-final-20260912-123946.out.txt`（本泳道未打开该文件）。
3. **未做变异测试**：M6/M7/M5 的"杀掉实现仍全绿"是**读代码推断**（新增分支无夹具形态可触发），**没有真的把 `readCreatedOrder` 的校验删掉再跑一遍**。若总控要坐实，需要一次"故意删实现看用例是否变红"的实验。
4. **M1 的负价后果未实测**：夹具不产出负价，我**没有构造**新用例（只读纪律），因此"`IllegalArgumentException` 穿透 `write()` ⇒ 流水少一行、`failed_count` 少一次"是**读代码得出的推断**，未实测。
5. **H1 的规格面只核到 §4.1.1.3**：我逐字检索了指导书里的 `UNKNOWN`（命中 L251、L255、L447），**没有**全篇通读 707 行；若别处另有对 DTO `status` 空值口径的补充规定，本结论需相应修正。
6. **并发/多目标**：未测同一 JVM 内多目标**并发**运行；`SecondMallDualTargetTest` 覆盖的是顺序/跨运行。
7. **真实第二家商城**：不存在，全部证据来自夹具 `SecondMallFakeServer`（`HttpServer`）；"适配了真实第二家商城"**不成立**。E3/E4 未跑。
8. **`report/**`、`cli/**`、`meta/**`、`core/**`、`contract/**` 只做了按类检索**（造值形态、状态字面量、`getOrDefault` 兜底、`@Disabled`），**未逐文件通读**；例如 `report/RunReport.java` 的渲染措辞未逐句核对。
9. **本泳道未打开** `docs/acceptance/m1-9-second-adapter-20260912/scripts/verify-fix-v3.ps1`（除 grep 命中行外），未评价其判据内容。
10. **仓库根 4 个临时文件**（`.verify-tmp-*.txt`）未清理：本泳道被禁止执行删除类命令，已在文首登记。

---

## 6. 结论

### 6.1 可声称（有取证支撑）

- `ed8c531` 的 `src` 变更面 = **11 条（10 M ＋ 1 D）**，与提交信息自述一致；模块在 HEAD 处与 `ed8c531` **逐字节相同**（`git diff` 为空、`status` 为空）。
- 本人自跑 E2：**116 用例 / 0 失败 / 0 错误 / 0 跳过 ＋ BUILD SUCCESS ＋ exit=0**（日志未落盘，见 §5.2）。
- **S1（别名）／S2（格式键点名）／S4（instanceof 旁路与死类型）／S5（实例累计状态）／S6（N+1 与目录补价）／S7（中文句子常量）的代码侧整改成立**，W1/W2 的弱断言形态在全测试树已归零，W3 在**适配器侧**已重述为静态声明口径并有正反用例。
- **引擎侧无商城知识**：`engine/**` 无商城路径字面量、无按适配器类型的分支、无词表常量（实测检索）。
- 缺陷类复发扫描结果：**C 类未命中**；A 类**没有新增"发明值"形态**；E 类中 S2 的"报错不点名键"已修复；B 类复发点已从适配器内部**转移**到契约文档层（M4）；F 类复发形态变为"用另一条路径冒充被测路径"（M6）与"夹具 API 承诺不存在的覆盖"（M7）。
- 共发现 **2 高 / 7 中 / 6 低**，其中"新增缺陷"与"既有未收口"已逐条标注。

### 6.2 不可声称

- **不可声称"本轮修复无规格偏离"**：H1 明确——代码已改值语义、权威指导书 §4.1.1.3 L251/L255 未改、且未登记为残余。这是本次评审最需要处理的一条。
- **不可声称"价格缺失已被完整钉住"**：适配器侧 `readCreatedOrder` 校验零覆盖（M5），目录侧部分缺价的点名通道不存在（H2）。
- **不可声称"S3 的口径披露已覆盖全部出口"**：`/targets/{id}/test` 的响应体与 DTO 文档仍称"实测"（M4）。
- **不可声称"高/中/低以外无遗留"**：`ExternalOrder.created()` 造 `"CREATED"`、`ReferenceMallHttpAdapter` 造 `PAID/CANCELLED/COMPLETED`（R-d）、`MallTargetAdapterOperationsTest:127` 把造值钉成契约——三条**既有**事实本轮未动，本文只作登记。
- **不可声称任何 E3/E4 结论、任何并发结论、任何真实第二家商城结论**（未取证，见 §5）。

### 6.3 建议的处理顺序（仅供总控裁决，本泳道不实施）

1. **H1**：改指导书 L251/L255 的口径，或登记为 R-d 同族残余。二选一，不能都不做。
2. **H2**：给 `ProductPage` 一个价格缺口通道（或让 `toProduct` 的排除件数/名单进 `stateFieldMissing` 之外的第三条通道——**这触发硬约束 11，需先改规格**），或把 `toProduct` 的 Javadoc 从"一定会被点名报出来"改成与实现一致的"整份目录都读不懂时响亮失败、部分时只在 debug 留痕"（**改文档是零契约成本的最小收口**）。
3. **M5/M6/M7**：给夹具加一个"下单成功但不回 `lines`（或不回行价）"的开关，让 `readCreatedOrder` 的三条分支真的被跑一次；同时删掉两个零调用的夹具 API，或让新用例用起来。
4. **M4**：改 `GeneratorApiDtos`/`TargetCheckResult` 的两句"实测"，或在响应体里增 `declared` 标记（后者需先改规格）。
5. **M1/M2/L1–L6**：随手修，其中 M1（正则去掉 `-?` 或补 `signum()` 检查 + 把异常类型统一到 `MallOperationException`）性价比最高。

---

*本文件是本次评审的唯一写入产物。评审期间未执行 `git add/commit/push/checkout/stash/reset`、未运行构建除上述一次 E2 之外的任务、未修改任何被评文件、未启动或停止任何服务。*
