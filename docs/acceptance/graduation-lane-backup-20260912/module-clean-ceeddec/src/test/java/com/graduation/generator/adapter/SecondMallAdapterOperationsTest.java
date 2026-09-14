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
        // 订单状态 = 商城原词（NEW），括号里只是给流水看的人读别名（CREATED）——原词必须在前，不许被别名顶掉
        assertTrue(created.status().startsWith("NEW"), "下单后必须保留第二家原词 NEW：" + created.status());
        assertFalse(created.status().startsWith("CREATED"), "不许把别名当成商城原词：" + created.status());
        assertNotNull(created.orderId());
        assertTrue(created.orderId().startsWith("ON"), created.orderId());

        ExternalOrder paid = adapter.pay(target, new PayCommand(created.orderId(), buyer.userId()));
        assertEquals("SETTLED", paid.status().split("\\(")[0], "支付后必须回 SETTLED（不是参考商城的 PAID）");
        assertEquals("SETTLED", mall.orderJson(created.orderId()).path("pay_state").asText(),
                "商城侧状态真的迁移了（不是只有应答好看）");
        // 明细条数取"应答里的行数"（1 行），件数是行内的 quantity（2）——两者不是一个东西，
        // 用 2 件下单正好把"行数/件数被混为一谈"这种错法挡住
        assertEquals(1, paid.itemCount(), "itemCount 是明细行数，必须来自商城应答");
        assertEquals(2, mall.orderJson(created.orderId()).path("lines").get(0).path("quantity").asInt(),
                "商城收到的件数必须是 2（订单命令里的 quantity 真的发出去了）");

        // 4c：引擎侧不按字面量判定 —— 适配器不认识的状态词映射成 null（而不是把原词塞进规范字段）
        mall.overrideItemState(mall.itemSkus().get(5), "ARCHIVED");
        ExternalProduct unknown = adapter.listProducts(target, ProductQuery.firstPage(ITEM_COUNT)).products()
                .stream().filter(p -> "SKU00006".equals(p.productId())).findFirst().orElseThrow();
        assertNull(unknown.status(), "映射不到必须是 null，绝不能是商城原词 ARCHIVED");
        assertFalse(unknown.onSale(), "映射不到的不能被当成在售");
        // 4d：原词必须留痕（供引擎报成目录缺口），而不是无声丢弃
        assertTrue(adapter.unmappedStatusWords().contains("ARCHIVED"),
                "适配器必须报出读不懂的商城原词：" + adapter.unmappedStatusWords());
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
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.ADMIN),
                "第二家没有改价/改库存接口 ⇒ ABSENT（不是 UNDETERMINED：这是已知事实）");
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.REFUND));
        assertEquals(CapabilityVerdict.ABSENT, capabilities.verdict(MallCapability.RESET_STATE));
        assertEquals(CapabilityVerdict.UNDETERMINED, capabilities.verdict(MallCapability.BEHAVIOR),
                "没声明 behavior_path ⇒ UNDETERMINED（没证实，但不是没有）");

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
