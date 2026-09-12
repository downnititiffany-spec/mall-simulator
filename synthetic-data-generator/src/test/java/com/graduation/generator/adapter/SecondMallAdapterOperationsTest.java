package com.graduation.generator.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.graduation.generator.fixture.SecondMallFakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-9 ②：<b>第二家商城适配器</b>的操作级验收（T1–T5、T7）。
 *
 * <p>它回答的问题只有一个：<b>"换一家商城"在实现上真的成立吗</b>？参考商城的
 * {@code MallTargetAdapterOperationsTest} 证明的是"能跑通参考商城"，本类必须证明
 * "换一套字段词表 / 单位 / 信封 / 状态词 / 路由 / 能力后，同一个 SPI 仍然成立"。</p>
 *
 * <p>证据一律来自<b>本地真套接字</b>（{@link SecondMallFakeServer}）而不是打桩：
 * 信封、状态码、金额单位这些东西一旦换成 mock，被测的就是 mock 的假设而不是实现的解析能力。
 * 夹具与参考商城的夹具<b>各写各的</b>——拷贝过来的夹具会把两家的形状差异一起搬过来，
 * 于是"能换商城"就失去证据价值。</p>
 *
 * <p>与参考商城的词表对照（本类里刻意<b>不出现</b>参考商城的任何路径与词表）：</p>
 * <table border="1">
 *   <caption>第二家的词表</caption>
 *   <tr><th>维度</th><th>第二家</th></tr>
 *   <tr><td>路由前缀</td><td>{@code /open/v2/**}</td></tr>
 *   <tr><td>信封</td><td>{@code {success,result,errMsg}}</td></tr>
 *   <tr><td>商品</td><td>{@code sku}/{@code title}/{@code unit_price_cents}/{@code state=SALE}</td></tr>
 *   <tr><td>用户</td><td>{@code buyer_ref}</td></tr>
 *   <tr><td>订单</td><td>{@code order_no}/{@code pay_state=NEW|SETTLED|VOID}/{@code total_cents}</td></tr>
 * </table>
 */
class SecondMallAdapterOperationsTest {

    private static final String ENV_NAME = "SECOND_MALL_TOKEN";
    private static final String TOKEN = "test-token";
    private static final int ITEM_COUNT = 60;

    static {
        // 测试用的假令牌：不是任何真实凭据。生产形态走环境变量，测试里没法给已启动的 JVM 注入环境变量，
        // 因此用系统属性同键覆盖（GeneratorBeans 的解析顺序：系统属性优先，其次环境变量）。
        System.setProperty(ENV_NAME, TOKEN);
    }

    private SecondMallFakeServer mall;

    @AfterEach
    void stopMall() {
        if (mall != null) {
            mall.close();
        }
    }

    // ---------- T1：字段词表不同，规范 DTO 字段照旧被填满 ----------

    @Test
    @DisplayName("T1 字段词表不同（sku/title/unit_price_cents/state）而规范 DTO 字段不变")
    void differentFieldWordsStillFillCanonicalDto() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        ProductPage page = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));

        assertEquals(ITEM_COUNT, page.total(), "目录规模必须来自第二家真实应答");
        assertEquals(ITEM_COUNT, page.products().size());
        ExternalProduct first = page.products().get(0);
        String sku = mall.itemSkus().get(0);
        JsonNode raw = mall.itemJson(sku);

        // 规范 DTO 的每个字段都必须有值，且值来自第二家的字段（不是别名巧合、不是 null）
        assertEquals(sku, first.productId(), "productId 必须来自第二家的 sku");
        assertEquals(raw.path("title").asText(), first.name(), "name 必须来自第二家的 title");
        assertEquals(raw.path("category_code").asLong(), first.categoryId(), "categoryId 必须来自第二家的 category_code");
        assertNotNull(first.price());
        assertNotNull(first.status());
        // 反向对照：夹具用的确实是第二家的字段名与被测实现解析的是同一份原文
        assertTrue(raw.has("sku") && raw.has("unit_price_cents") && raw.has("state"),
                "夹具必须用第二家的字段名（sku/unit_price_cents/state），否则 T1 没有对照物：" + raw);
        assertFalse(raw.has("productId") || raw.has("price"),
                "夹具不许顺带复刻参考商城的字段名，否则\"字段词表不同\"就没被真的检验：" + raw);
        assertEquals(1, mall.hits(SecondMallFakeServer.ITEMS_ROUTE), "目录只读一次");
    }

    // ---------- T2：金额标度（整数分 → 元）精确、无浮点尾差 ----------

    @Test
    @DisplayName("T2 整数分 → 元换算精确：无浮点尾差，小数分响亮拒绝")
    void centsAreConvertedWithoutFloatTail() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        // 边界一：夹具自己的商品价（585 分等，均非整元）
        ProductPage page = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));
        long cents = mall.firstUnitPriceCents();
        BigDecimal expected = new BigDecimal(String.valueOf(cents)).movePointLeft(2);
        assertEquals(expected, page.products().get(0).price(), "分 → 元必须与独立复算一致");
        assertEquals(mall.firstUnitPriceYuan().toPlainString(),
                page.products().get(0).price().toPlainString(), "toPlainString 必须逐字相等（无尾差）");

        // 边界二：0 分（免费商品也要能表达，不能被当成"缺失"）
        assertEquals("0.00", SecondMallHttpAdapter.centsToYuan("0").toPlainString());
        // 边界三：大额（19 位分，超过 int 甚至超过 double 的精确整数范围）
        BigDecimal huge = SecondMallHttpAdapter.centsToYuan("9223372036854775807");
        assertEquals("92233720368547758.07", huge.toPlainString(), "大额也必须逐字精确");
        // 边界四：非整元、以 0 结尾（1 分 = 0.01，10 分 = 0.10，仍要两位）
        assertEquals("0.01", SecondMallHttpAdapter.centsToYuan("1").toPlainString());
        assertEquals("0.10", SecondMallHttpAdapter.centsToYuan("10").toPlainString());
        assertEquals("123.45", SecondMallHttpAdapter.centsToYuan("12345").toPlainString());
        assertEquals(CENTS_PER_YUAN_IS_EXPLICIT, SecondMallHttpAdapter.CENTS_PER_YUAN);

        // 边界五（响亮失败）：小数分说明商城口径不一致，必须抛而不是静默取整
        MallOperationException halfCent = assertThrows(MallOperationException.class,
                () -> SecondMallHttpAdapter.centsToYuan("12.345"));
        assertTrue(halfCent.getMessage().contains("整数分"), halfCent.getMessage());
        assertThrows(MallOperationException.class, () -> SecondMallHttpAdapter.centsToYuan("12,3"));
        assertThrows(MallOperationException.class, () -> SecondMallHttpAdapter.centsToYuan(""));
        assertThrows(MallOperationException.class, () -> SecondMallHttpAdapter.centsToYuan(null));
    }

    /** 进制必须是 100：换算常量不许被改成 1000 之类而无人察觉 */
    private static final int CENTS_PER_YUAN_IS_EXPLICIT = 100;

    // ---------- T2b：价格缺失 ⇒ 响亮失败（绝不静默 null） ----------

    @Test
    @DisplayName("T2b 价格缺失：商城没给 unit_price_cents 时下单必须响亮失败，绝不静默 null 或当成 0 元")
    void missingPriceFailsLoudly() throws IOException {
        mall = new SecondMallFakeServer(null, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        // 夹具形态先被独立钉住：目录里这件商品**整条 unit_price_cents 字段都没有**
        // （不是 0、也不是 null 文本）；正文里那个 null 才是"价格缺失"的判据。
        String sku = mall.itemSkus().get(0);
        mall.removeItemPrice(sku);
        JsonNode raw = mall.itemJson(sku);
        assertFalse(raw.has("unit_price_cents"),
                "夹具必须先去掉价格字段，否则这条用例没有对照物：" + raw);
        assertFalse(mall.itemHasPrice(sku), "夹具侧事实：这件商品没有价格：" + sku);

        ExternalUser buyer = adapter.createSyntheticUser(target, new UserCommand("25-34", "tier2", "gold"));

        // 1) 下单时这件商品没有可信单价 ⇒ 商城拒单、适配器必须抛，绝不返回含 null 金额的订单
        MallOperationException e = assertThrows(MallOperationException.class,
                () -> adapter.createOrder(target, OrderCommand.single(buyer.userId(), sku, 1)),
                "价格缺失必须响亮失败：返回订单（哪怕金额是 null）会把\"读不懂的金额\"静默带进流水");
        assertTrue(e.getMessage().contains("E_NO_PRICE") && e.getMessage().contains(sku),
                "异常必须点名缺失的价格与哪件商品（错误码 + SKU）：" + e.getMessage());
        assertTrue(e.getMessage().contains("no price") || e.getMessage().contains("价格")
                        || e.getMessage().contains("单价"),
                "异常必须直说价格/单价缺失（不许只丢一句\"失败\"）：" + e.getMessage());

        // 2) 商城侧"确实没有留下成交"必须被独立证明：否则第 1 条的抛可能只是别的原因
        assertEquals(0, mall.orderCount(),
                "价格缺失时商城侧不许留下一笔成交（订单数必须 0，实际：" + mall.orderCount() + "）");
        assertNull(mall.orderJson("ON1"), "价格缺失时不该建成任何订单快照");
        // 3) 反向对照：目录读取本身仍然正常；报价读不懂的那件**被排除并响亮报错**，
        //    而不是留在目录里带着 null 金额继续跑（与"读不懂的状态词"同一形态：排除 + 点名）
        ProductPage page = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));
        assertEquals(ITEM_COUNT - 1, page.products().size(),
                "报价读不懂的那件必须被排除在可用目录之外（目录 " + ITEM_COUNT + " 件 → 在售 "
                        + page.products().size() + " 件）");
        assertTrue(page.products().stream().noneMatch(p -> sku.equals(p.productId())),
                "报价读不懂的商品不许留在可用目录里：" + sku);
        assertTrue(page.products().stream().allMatch(p -> p.price() != null),
                "可用目录里不许有任何金额为 null 的商品：" + page.products().stream()
                        .filter(p -> p.price() == null).map(ExternalProduct::productId).toList());
        // 目录成空时更不许"静默返回空目录"：那是把"读不懂"伪装成"商城没货"
        mall = new SecondMallFakeServer(null, null, 1);
        String onlySku = mall.itemSkus().get(0);
        mall.removeItemPrice(onlySku);
        TargetConfig singleItemTarget = target(mall.baseUrl(), FORMAT_DECLARED);
        MallOperationException empty = assertThrows(MallOperationException.class,
                () -> adapter().listProducts(singleItemTarget, ProductQuery.firstPage(1)),
                "整份目录的报价都读不懂时必须响亮失败，绝不静默返回空目录");
        assertTrue(empty.getMessage().contains(onlySku),
                "空目录失败必须点名是哪件商品读不懂：" + empty.getMessage());
    }

    // ---------- T3：信封不同仍能解析 / 响亮失败 ----------

    @Test
    @DisplayName("T3 第二家的信封 {success,result,errMsg} 能解析；成功位为 false 或非 2xx 都响亮失败")
    void differentEnvelopeParsesAndFailsLoudly() throws IOException {
        // 3a：正常信封（success=true + result）能被解析成业务对象
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);
        ExternalUser user = adapter.createSyntheticUser(target, new UserCommand("25-34", "tier2", "gold"));
        assertNotNull(user.userId());
        assertTrue(user.userId().startsWith("BR"), "buyer_ref 必须原样成为外部用户 ID：" + user.userId());
        assertEquals(1, mall.hits(SecondMallFakeServer.MEMBERS_ROUTE));
        mall.close();

        // 3b：HTTP 200 但 success=false ⇒ 必须抛（只按状态码判成功率的实现会在这里放行）
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT, Long.MAX_VALUE,
                SecondMallFakeServer.FAIL_ORDER_SUCCESS_FALSE);
        SecondMallHttpAdapter adapter2 = adapter();
        TargetConfig target2 = target(mall.baseUrl(), FORMAT_DECLARED);
        ExternalUser buyer = adapter2.createSyntheticUser(target2, new UserCommand("25-34", "tier2", "gold"));
        MallOperationException successFalse = assertThrows(MallOperationException.class,
                () -> adapter2.createOrder(target2, OrderCommand.single(buyer.userId(), mall.itemSkus().get(0), 1)),
                "success=false 绝不能当成成功");
        assertTrue(successFalse.getMessage().contains("E_ITEM_OFF_SHELF"),
                "异常里必须带商城自己的错误码/原话：" + successFalse.getMessage());
        assertTrue(successFalse.getMessage().contains("item is not on sale"),
                "异常里必须带商城原话（errMsg）：" + successFalse.getMessage());
        mall.close();

        // 3c：HTTP 400 + E_NO_STOCK ⇒ 抛，且错误码与原话都在异常里
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT, Long.MAX_VALUE,
                SecondMallFakeServer.FAIL_ORDER_NO_STOCK);
        SecondMallHttpAdapter adapter3 = adapter();
        TargetConfig target3 = target(mall.baseUrl(), FORMAT_DECLARED);
        ExternalUser buyer3 = adapter3.createSyntheticUser(target3, new UserCommand("25-34", "tier2", "gold"));
        MallOperationException noStock = assertThrows(MallOperationException.class,
                () -> adapter3.createOrder(target3, OrderCommand.single(buyer3.userId(), mall.itemSkus().get(0), 1)));
        assertTrue(noStock.getMessage().contains("E_NO_STOCK"), noStock.getMessage());
        assertTrue(noStock.getMessage().contains("no stock for"), noStock.getMessage());
        assertEquals(400, mall.statuses().get(mall.statuses().size() - 1));

        // 3d：凭据不可用 ⇒ 发请求之前就拒绝（绝不匿名发）
        SecondMallHttpAdapter noCredential = new SecondMallHttpAdapter(name -> null, Duration.ofSeconds(2));
        MallOperationException missing = assertThrows(MallOperationException.class,
                () -> noCredential.listProducts(target3, ProductQuery.firstPage(5)));
        assertTrue(missing.getMessage().contains(ENV_NAME), "必须点名凭据引用名：" + missing.getMessage());
        assertFalse(missing.getMessage().contains(TOKEN), "异常里绝不能出现凭据值：" + missing.getMessage());
    }

    // ---------- T4：状态词表不同；映射归适配器，引擎不按字面量判定 ----------

    @Test
    @DisplayName("T4 第二家的状态词（SALE/NEW/SETTLED/VOID）与规范词不同：商品状态被映射，订单状态原样透出")
    void differentStateWordsAreMappedAtAdapterOnly() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        // 4a：商品状态词映射（F-25）——商城说 SALE，规范 DTO 必须是 on_sale
        ExternalProduct product = adapter.listProducts(target, ProductQuery.firstPage(3)).products().get(0);
        assertEquals("SALE", mall.itemJson(product.productId()).path("state").asText(),
                "夹具确实用的是第二家的词（正向对照）");
        assertEquals("on_sale", product.status(), "F-25：映射必须落在适配器，规范字段只装规范词");
        // 规范词表就是契约里的三个（此处显式写出来，防止有人把映射表偷偷扩成"什么都收"）
        assertTrue(java.util.Set.of("on_sale", "off_sale", "pending")
                .contains(product.status()), "规范字段只能是契约枚举里的词");
        assertEquals("on_sale", SecondMallHttpAdapter.toCanonicalProductStatus("SALE"));
        assertEquals("off_sale", SecondMallHttpAdapter.toCanonicalProductStatus("OFF_SHELF"));
        assertEquals("pending", SecondMallHttpAdapter.toCanonicalProductStatus("PENDING"));

        // 4b：订单状态词只进流水（ExternalOrder.status 是商城原样文本），且原词可读
        ExternalUser buyer = adapter.createSyntheticUser(target, new UserCommand("25-34", "tier2", "gold"));
        ExternalOrder created = adapter.createOrder(target,
                OrderCommand.single(buyer.userId(), product.productId(), 2));
        // 订单状态 = 商城原文的**逐字符**透出（指导书 V2.3 §4.1.1.3"商城原样文本"）：
        // 这里必须是逐字相等的 "NEW"，不许加括号别名、不许大小写改写、不许加前后缀——
        // 用 startsWith 断言会把 "NEW(CREATED)" 这种"拼了别名"的实现放过去，所以这里写死全等。
        assertEquals("NEW", created.status(), "下单后必须是第二家原文 NEW 的逐字符透出：" + created.status());
        // 反向对照（商城侧）：不是应答好看，商城自己真的记着 NEW
        assertEquals("NEW", mall.orderJson(created.orderId()).path("pay_state").asText(),
                "商城侧下单后就是 NEW（正向对照：上面的 NEW 确实来自商城）");
        assertNotNull(created.orderId());
        assertTrue(created.orderId().startsWith("ON"), created.orderId());

        ExternalOrder paid = adapter.pay(target, new PayCommand(created.orderId(), buyer.userId()));
        assertEquals("SETTLED", paid.status(),
                "支付后必须是第二家原文 SETTLED 的逐字符透出（不是参考商城的 PAID，也不是 PAID(SETTLED)）");
        assertEquals("SETTLED", mall.orderJson(created.orderId()).path("pay_state").asText(),
                "商城侧状态真的迁移了（不是只有应答好看）");
        // 明细条数取"应答里的行数"（1 行），件数是行内的 quantity（2）——两者不是一个东西，
        // 用 2 件下单正好把"行数/件数被混为一谈"这种错法挡住
        assertEquals(1, paid.itemCount(), "itemCount 是明细行数，必须来自商城应答");
        assertEquals(2, mall.orderJson(created.orderId()).path("lines").get(0).path("quantity").asInt(),
                "商城收到的件数必须是 2（订单命令里的 quantity 真的发出去了）");

        // 4b-2：第三个状态词 VOID 也要逐字透出（另起一单来作废，因为商城侧 SETTLED 之后不能再 VOID）
        ExternalOrder toCancel = adapter.createOrder(target,
                OrderCommand.single(buyer.userId(), product.productId(), 1));
        assertEquals("NEW", toCancel.status(), "新单仍是商城原文 NEW");
        ExternalOrder cancelled = adapter.cancel(target,
                new CancelCommand(toCancel.orderId(), buyer.userId(), "测试用：作废"));
        assertEquals("VOID", cancelled.status(), "作废后必须是第二家原文 VOID 的逐字符透出（不是 CANCELLED）");
        assertEquals("VOID", mall.orderJson(toCancel.orderId()).path("pay_state").asText(),
                "商城侧真的迁移到了 VOID（正向对照）");
        // 商城侧原词分布要和"逐字透出"对得上：只有 SETTLED/VOID 两个原词，各 1 单。
        // 拼过别名的实现会让这里的桶名/桶数都对不上（这也是别名为什么必须删掉）。
        assertEquals(Map.of("SETTLED", 1, "VOID", 1), mall.orderStateHistogram(),
                "商城侧只该有 SETTLED/VOID 两个原词，各 1 单：" + mall.orderStateHistogram());

        // 4c：引擎侧不按字面量判定 —— 适配器不认识的状态词映射成 null（而不是把原词塞进规范字段）
        mall.overrideItemState(mall.itemSkus().get(5), "ARCHIVED");
        ProductPage afterArchive = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));
        ExternalProduct unknown = afterArchive.products()
                .stream().filter(p -> "SKU00006".equals(p.productId())).findFirst().orElseThrow();
        assertNull(unknown.status(), "映射不到必须是 null，绝不能是商城原词 ARCHIVED");
        assertFalse(unknown.onSale(), "映射不到的不能被当成在售");
        // 4d：原词必须留痕（供引擎报成目录缺口），而不是无声丢弃。
        //     留痕的**载体是本次读取的返回页**（ProductPage），不是适配器实例上的字段：
        //     适配器是单例无状态的，"上一轮读到过什么"不许跨运行留在它身上。
        assertTrue(afterArchive.unmappedStateWords().contains("ARCHIVED"),
                "本次读取的返回页必须报出读不懂的商城原词：" + afterArchive.unmappedStateWords());
        assertEquals(List.of("ARCHIVED"), afterArchive.unmappedStateWords(),
                "原词清单是把商城原词照实列出（去重、字典序），不是把规范词或商品 ID 混进去");
        // 4e：第二次读取（状态已改回 SALE）的返回页里**不许**还留着上一轮的原词——
        //     这是"事实随返回值走、不随单例走"的正向对照
        mall.overrideItemState(mall.itemSkus().get(5), "SALE");
        ProductPage afterRevert = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));
        assertEquals("on_sale", afterRevert.products().stream()
                        .filter(p -> "SKU00006".equals(p.productId())).findFirst().orElseThrow().status(),
                "改回 SALE 后必须重新映射成 on_sale");
        assertTrue(afterRevert.unmappedStateWords().isEmpty(),
                "上一轮读到的原词不许留在下一次读取的结果里：" + afterRevert.unmappedStateWords());
        assertTrue(afterRevert.stateFieldMissing().isEmpty(),
                "商城明明给了 state 字段，不该被记成\"没给状态字段\"：" + afterRevert.stateFieldMissing());
    }

    // ---------- T5：路由不同，且流水记的是适配器自报的路由 ----------

    @Test
    @DisplayName("T5 operationRoutes 报的是第二家的 /open/v2 路由，而不是参考商城的 /api/v1")
    void routesAreTheSecondMallsOwn() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, SecondMallFakeServer.DEFAULT_BEHAVIOR_PATH, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(),
                "{\"format\":\"open-v2\",\"behavior_path\":\"" + SecondMallFakeServer.DEFAULT_BEHAVIOR_PATH + "\"}");

        Map<String, TargetRoute> routes = adapter.operationRoutes(target);

        assertEquals(new TargetRoute("GET", "/open/v2/items"), routes.get("listProducts"));
        assertEquals(new TargetRoute("POST", "/open/v2/members"), routes.get("createSyntheticUser"));
        assertEquals(new TargetRoute("POST", "/open/v2/orders"), routes.get("createOrder"));
        assertEquals(new TargetRoute("POST", "/open/v2/orders/{orderId}/settle"), routes.get("pay"));
        assertEquals(new TargetRoute("POST", "/open/v2/orders/{orderId}/void"), routes.get("cancel"));
        assertEquals(new TargetRoute("POST", SecondMallFakeServer.DEFAULT_BEHAVIOR_PATH), routes.get("emitBehavior"));
        // 第二家没有退款接口 ⇒ 路由表里不该有条目（能力 ABSENT 与路由缺失必须一致）
        assertFalse(routes.containsKey("refund"), "退款：第二家没有该接口，路由表不许凭空给一条");
        // 参考商城的路由禁止出现在第二家的路由表里（反向对照；此处只断言前缀，不复制参考商城的路径）
        routes.values().forEach(route -> assertFalse(route.path().startsWith("/api/"),
                "第二家的路由表里混进了参考商城的路径：" + route));

        // 未声明埋点路径 ⇒ 该操作没有路由（引擎据此记"适配器未声明路由"，而不是猜一个）
        Map<String, TargetRoute> withoutBehavior = adapter.operationRoutes(target(mall.baseUrl(), FORMAT_DECLARED));
        assertFalse(withoutBehavior.containsKey("emitBehavior"), "没声明就不许编一个埋点路由出来");

        // 真发出去的形状与自报的形状一致：真打一次埋点，看夹具收到的是自报的路径
        adapter.emitBehavior(target, new BehaviorCommand("BR1", "SKU00001", "SESS1", "view", "app"));
        assertEquals(1, mall.hits("POST " + SecondMallFakeServer.DEFAULT_BEHAVIOR_PATH),
                "自报的埋点路由必须就是真正被打到的路由，实际请求：" + mall.exchanges());
    }

    // ---------- T7：能力差异 ⇒ 缺口而不是伪造成功 ----------

    @Test
    @DisplayName("T7 第二家缺 admin/refund/reset_state：能力声明为 ABSENT，调用走响亮失败而不是假成功")
    void capabilityDifferencesAreDeclaredAndNeverFaked() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        // config_json 里显式声明格式：第二家的字段/单位/状态词是"哪一版接口"的一部分，
        // 没有声明就不该被当成已知事实（能力判 UNDETERMINED，见 T7 末尾的反向断言）
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        TargetCapabilities capabilities = adapter.capabilities(target);
        assertEquals(CapabilityVerdict.SUPPORTED, capabilities.verdict(MallCapability.PRODUCT));
        assertEquals(CapabilityVerdict.SUPPORTED, capabilities.verdict(MallCapability.USER));
        assertEquals(CapabilityVerdict.SUPPORTED, capabilities.verdict(MallCapability.ORDER));
        // 第二家没有改价/改库存、退款单、重置状态这三类接口 ⇒ 静态声明为 ABSENT。
        // 口径必须说清："值（ABSENT） + 静态声明（未探测）"——即这是适配器**不联网**给出的声明，
        // 不是一次探测的结论；本用例只断言到"声明成 ABSENT 且调用走响亮失败"，
        // **不断言**"被探测定性为不存在"（那需要真的发探测请求，本适配器刻意不发）。
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.ADMIN),
                "第二家没有改价/改库存接口 ⇒ 静态声明 ABSENT（未探测；不是 UNDETERMINED 的\"没证实\"）");
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.REFUND),
                "退款：静态声明 ABSENT（未探测）");
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.RESET_STATE),
                "重置状态：静态声明 ABSENT（未探测）");
        assertEquals(CapabilityVerdict.UNDETERMINED, capabilities.verdict(MallCapability.BEHAVIOR),
                "没声明 behavior_path ⇒ UNDETERMINED（没证实，但不是没有）");

        // 反向对照：这三项 ABSENT 是**静态声明（未探测）**，不是探测结论 —— 声明式能力判定不许联网，
        // 因此 capabilities() 之后商城侧一条请求都不该多出来
        assertEquals(0, mall.exchanges().size(),
                "capabilities() 是声明口，不许联网：实际收到的请求=" + mall.exchanges());
        // 同理：test() 里对这三项**不发探测请求**，也从不谎称测过（它只探 product/user/order/behavior）
        int beforeProbe = mall.exchanges().size();
        TargetCheckResult check = adapter.test(target);
        List<String> probeRequests = mall.exchanges().subList(beforeProbe, mall.exchanges().size());
        assertTrue(probeRequests.stream().noneMatch(request -> request.contains("admin")
                        || request.contains("refund") || request.contains("reset")),
                "对 admin/refund/reset_state 不许发探测请求（发了就只能算\"未证实\"，不能算\"不存在\"）："
                        + probeRequests);
        assertFalse(probeRequests.isEmpty(),
                "test() 至少要探一次代表路由，否则\"没探那三项\"是因为它什么都没探（空对照）：" + probeRequests);
        // test() 的结果同样把这三项写成静态声明（未探测），而不是"测出来的不存在"
        assertEquals(CapabilityVerdict.ABSENT, check.verdict(MallCapability.REFUND),
                "test() 里退款仍是静态声明 ABSENT（未探测）：" + check.detail());
        assertTrue(check.detail().contains("未探测") || check.detail().contains("静态声明"),
                "test() 的文案必须自报\"未探测/静态声明\"，不许把声明说成测量：" + check.detail());

        // 不支持的必须响亮失败：绝不能返回一个"成功"对象让调用方记账
        MallOperationException refund = assertThrows(MallOperationException.class,
                () -> adapter.refund(target, new RefundCommand("ON1", "BR1", new BigDecimal("1.00"), "test")));
        assertNotNull(refund.getMessage());
        // 商城侧也要能证明"没有这条路由"（第二家真的没有退款接口，不是生成器不想调）
        assertEquals(0, mall.hits("POST /open/v2/orders/ON1/refund"));
        // 白名单随之缩小：引擎按能力判定摘掉退款/商品管理类事件，而不是"跳过还照记成功"
        java.util.Set<String> allowed = com.graduation.generator.engine.MallApiGenerationEngine
                .mallBackedEventTypes(capabilities);
        assertFalse(allowed.contains("refund_created"), "退款事件不许进白名单：" + allowed);
        assertFalse(allowed.contains("refund_completed"), "退款事件不许进白名单：" + allowed);
        assertFalse(allowed.contains("product_updated"), "改价/改库存类事件不许进白名单：" + allowed);
        assertTrue(allowed.contains("order_created") && allowed.contains("order_paid"),
                "有订单能力 ⇒ 订单类事件必须在白名单里：" + allowed);
        assertFalse(allowed.contains("behavior"), "行为埋点没声明 ⇒ 不许进白名单：" + allowed);

        // 反向对照：没声明格式时不许"乐观地"判 SUPPORTED —— 能力判定只能按已证实的事实
        TargetCapabilities undeclared = adapter.capabilities(target(mall.baseUrl(), "{}"));
        assertEquals(CapabilityVerdict.UNDETERMINED, undeclared.verdict(MallCapability.PRODUCT),
                "没声明 open-v2 格式 ⇒ UNDETERMINED，而不是乐观的 SUPPORTED");
    }

    // ---------- T8：商城没给状态字段 ⇒ 保持"未给"，绝不造词 ----------

    @Test
    @DisplayName("T8 缺状态：商城没给 state/pay_state 时保持\"未给\"（null），绝不产出 UNKNOWN 这类编出来的商城词")
    void missingStateNeverInventsAMallWord() throws IOException {
        mall = new SecondMallFakeServer(null, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();
        TargetConfig target = target(mall.baseUrl(), FORMAT_DECLARED);

        // 0) 夹具形态先被独立钉住：这件商品的应答里**整条 state 字段都没有**（不是空串、不是怪词）
        String sku = mall.itemSkus().get(0);
        mall.overrideItemState(sku, null);
        JsonNode rawItem = mall.itemJson(sku);
        assertFalse(rawItem.has("state"), "夹具必须先去掉 state 字段，否则这条用例没有对照物：" + rawItem);

        // 1) "商城没给字段"与"给了一个读不懂的词"是两件事：前者**没有商城原词可报**，
        //    因此不许出现在 unmappedStateWords（那个列表里每个词都必须是商城真的说过的）
        ProductPage page = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT));
        assertTrue(page.unmappedStateWords().isEmpty(),
                "没给字段不是\"给了读不懂的词\"，这里不许凭空冒出一个商城原词：" + page.unmappedStateWords());
        assertTrue(page.stateFieldMissing().contains(sku),
                "缺状态字段的商品必须进 stateFieldMissing（可核对的缺口事实）：" + page.stateFieldMissing());
        // 这一行本身要留着（不静默丢商品），但它的规范状态必须是 null：不是 UNKNOWN、也不是任何词
        ExternalProduct missingState = page.products().stream()
                .filter(p -> sku.equals(p.productId())).findFirst()
                .orElseThrow(() -> new AssertionError("缺状态字段的商品不许从目录里静默消失：" + sku));
        assertNull(missingState.status(),
                "商城没给 state 字段 ⇒ 规范状态必须是 null（\"未给\"），实际=" + missingState.status());
        assertFalse(missingState.onSale(), "\"未给\"不许被当成在售");
        long usable = page.products().stream().filter(ExternalProduct::onSale).count();
        assertEquals(ITEM_COUNT - 1, usable,
                "缺状态字段的商品必须被排除在可用目录之外（目录 " + ITEM_COUNT + " 件 → 可用 " + usable + " 件）");

        // 2) 订单侧的正向对照：商城给了 pay_state 时必须逐字符透出（否则下面的 null 没有对照物）
        ExternalUser buyer = adapter.createSyntheticUser(target, new UserCommand("25-34", "tier2", "gold"));
        String sellable = mall.itemSkus().get(1);
        ExternalOrder withState = adapter.createOrder(target, OrderCommand.single(buyer.userId(), sellable, 1));
        assertEquals("NEW", withState.status(),
                "商城给了 pay_state=NEW ⇒ 必须原样透出（这条正向对照是下面那条断言的前提）");

        // 3) 商城从此不回 pay_state 字段 ⇒ status 必须保持 null：不是 UNKNOWN、不是任何编出来的词
        mall.omitOrderStateInResponse();
        ExternalOrder withoutState = adapter.createOrder(target, OrderCommand.single(buyer.userId(), sellable, 1));
        assertNull(withoutState.status(),
                "商城没给状态字段时 status 必须是 null（\"未给\"），实际=" + withoutState.status()
                        + "；编一个词会让\"商城说的是它\"与\"生成器猜的它\"在同一个字段里再也分不开");
        assertFalse(withoutState.paid(), "\"未给\"不许被读成\"已支付\"");
        assertFalse(withoutState.cancelled(), "\"未给\"不许被读成\"已取消\"");
        assertEquals("NEW", mall.orderJson(withoutState.orderId()).path("pay_state").asText(),
                "商城内部状态照旧是它自己的词 ⇒ \"状态缺失\"是应答形态，不是夹具坏了");
        assertEquals("NEW", mall.orderJson(withState.orderId()).path("pay_state").asText(),
                "那笔带状态的正向对照订单在商城侧也仍是 NEW（只创建、没支付）");

        // 4) 支付应答同样不给状态字段：调用成功照样成功，但状态不造词
        ExternalOrder paid = adapter.pay(target, new PayCommand(withoutState.orderId(), buyer.userId()));
        assertNull(paid.status(), "支付应答没给状态字段 ⇒ 保持 null，不许写 UNKNOWN：" + paid.status());
        assertFalse(paid.paid(), "不许把\"未给\"当成\"已支付\"");
        assertEquals("SETTLED", mall.orderJson(withoutState.orderId()).path("pay_state").asText(),
                "商城侧确实收下了这次支付（state=SETTLED）——状态字段缺失与调用成不成功是两件事");

        // 5) 退款单同口径（第二家没有退款接口，这里只能直接观测 DTO 的\"未给\"语义）
        ExternalRefund refund = new ExternalRefund("RF1", withoutState.orderId(), null, new BigDecimal("1.00"));
        assertNull(refund.status(), "退款状态没给就是 null：绝不许出现 UNKNOWN 这类编出来的词");
        assertFalse(refund.completed(), "\"未给\"不许被读成\"已完成\"");
        // 空白状态词也按"没给"处理：它同样不是商城说过的词
        assertNull(new ExternalOrder("ON9", "BR9", "   ", null, -1).status(),
                "空白状态词不是商城原词，必须落成\"未给\"而不是留着空白串冒充状态");
        assertNull(new ExternalRefund("RF9", "ON9", "   ", null).status(), "退款单同理");
    }

    // ---------- T9：格式声明缺失时，运维话术必须点名键名与可接受取值 ----------

    @Test
    @DisplayName("T9 格式声明缺失/不匹配：运维看得到的话术必须点名 config_json.format=open-v2（键名与取值都走常量）")
    void formatGateIsNamedInOperatorFacingText() throws IOException {
        mall = new SecondMallFakeServer(TOKEN, null, ITEM_COUNT);
        SecondMallHttpAdapter adapter = adapter();

        // 9a：base_url 与凭据都没问题，只是 config_json 里没有格式声明 —— 这正是"运维只能看到一串
        //     UNDETERMINED、却不知道缺哪个键"的场景，话术必须直接告诉他键名与可接受取值
        TargetConfig undeclaredTarget = target(mall.baseUrl(), "{}");
        assertEquals(CapabilityVerdict.UNDETERMINED,
                adapter.capabilities(undeclaredTarget).verdict(MallCapability.PRODUCT),
                "声明门：没声明格式 ⇒ 三段一律 UNDETERMINED（这条断言是下面话术断言的前提）");
        assertEquals(0, mall.exchanges().size(),
                "能力声明不许联网：运维在配置写对之前不该已经在打商城了：" + mall.exchanges());
        TargetCheckResult undeclared = adapter.test(undeclaredTarget);
        assertTrue(undeclared.detail().contains(SecondMallHttpAdapter.CONFIG_FORMAT_KEY),
                "话术必须点名缺的是哪个键（经常量断言，不硬编码字面量）：" + undeclared.detail());
        assertTrue(undeclared.detail().contains(SecondMallHttpAdapter.CONFIG_FORMAT_VALUE),
                "话术必须点名该键可接受的取值（经常量断言，不硬编码字面量）：" + undeclared.detail());
        assertTrue(undeclared.detail().contains(
                        SecondMallHttpAdapter.CONFIG_FORMAT_KEY + "=" + SecondMallHttpAdapter.CONFIG_FORMAT_VALUE),
                "键名与取值必须以 \"<键>=<取值>\" 的形态连在一起出现，运维才能照着改：" + undeclared.detail());
        assertTrue(undeclared.detail().contains(SecondMallHttpAdapter.CONFIG_BEHAVIOR_PATH),
                "behavior 还要另外声明哪个键，也要在同一段话术里说清：" + undeclared.detail());

        // 9b：声明了、但取值不对 ⇒ 与"根本没声明"同一个结论，话术要回显当前那份声明
        TargetConfig wrongValueTarget = target(mall.baseUrl(),
                "{\"" + SecondMallHttpAdapter.CONFIG_FORMAT_KEY + "\":\"open-v1\"}");
        assertEquals(CapabilityVerdict.UNDETERMINED,
                adapter.capabilities(wrongValueTarget).verdict(MallCapability.PRODUCT),
                "声明了但取值不对 ⇒ 同样是 UNDETERMINED，绝不降级成 ABSENT"
                        + "（ABSENT 会被读成\"商城没有这个接口\"，而事实是配置写错了）");
        TargetCheckResult wrongValue = adapter.test(wrongValueTarget);
        assertTrue(wrongValue.detail().contains("open-v1"),
                "话术必须回显当前那份声明，运维才知道自己写的是什么：" + wrongValue.detail());
        assertTrue(wrongValue.detail().contains(SecondMallHttpAdapter.CONFIG_FORMAT_VALUE),
                "取值不对时同样要点名可接受取值：" + wrongValue.detail());

        // 9c：正向对照 —— 声明正确时声明门给 SUPPORTED，话术也必须说"已声明"，不能反过来吓人
        TargetConfig declaredTarget = target(mall.baseUrl(), FORMAT_DECLARED);
        assertTrue(adapter.capabilities(declaredTarget).isSupported(MallCapability.PRODUCT),
                "声明正确 + base_url/凭据没问题 ⇒ 声明门给 SUPPORTED（否则 9a/9b 的 UNDETERMINED 没有对照物）");
        TargetCheckResult declared = adapter.test(declaredTarget);
        assertTrue(declared.detail().contains("已声明"),
                "声明成立时话术要明说已声明，而不是继续提示缺失：" + declared.detail());
        assertTrue(declared.detail().contains(SecondMallHttpAdapter.CONFIG_FORMAT_KEY)
                        && declared.detail().contains(SecondMallHttpAdapter.CONFIG_FORMAT_VALUE),
                "无论成立与否，这段话术都要能自解释（键名 + 取值）：" + declared.detail());
    }

    /** {@code config_json}：显式声明第二家的接口格式（能力判定的输入之一） */
    private static final String FORMAT_DECLARED = "{\"format\":\"open-v2\"}";

    // ---------- 夹具（辅助） ----------

    private SecondMallHttpAdapter adapter() {
        return new SecondMallHttpAdapter(name -> {
            String property = System.getProperty(name);
            return property != null ? property : System.getenv(name);
        }, Duration.ofSeconds(5));
    }

    private static TargetConfig target(String baseUrl, String configJson) {
        return new TargetConfig(21L, SecondMallHttpAdapter.ADAPTER_TYPE, baseUrl, ENV_NAME, configJson);
    }
}
