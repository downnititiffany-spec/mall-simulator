# Slice02: 场景化自动生成器 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 mall-simulator 内实现可复现的场景化数据生成器：分层用户、Zipf 商品曝光、24 小时流量曲线、有限状态行为链、11 类经营场景（ScenarioStrategy）、脏数据注入器，全部通过既有商城业务 Service 产生事件（§5.2.3、§20.2–§20.6）。

**Architecture:** `com.graduation.mall.generator` 独立包。生成器只依赖领域 Service（registerUser/addCartItem/createOrder/payOrder/cancelOrder/applyRefund/completeRefund/completeOrder）与 outbox 追加（view/favorite/search/cart_remove 为日志型行为事件，无业务表，直接 append）；禁止直接写订单表。确定性来自单一 `java.util.Random(seed)` 顺序消费 + 固定迭代顺序（List 遍历，不用 HashMap 驱动决策）。金额全部经服务计算。脏数据由 DirtyDataInjector 在模拟结束后写入 `landing/dirty/`，不触碰业务库。

**Tech Stack:** Java 17、Spring Boot 3.2.5、MyBatis-Plus、JUnit 5（复用 MallTestSupport 集成底座）。

**外部约束（V2.2 文稿）:** §20.3 活跃分层 15/35/50、Zipf 头部 20%≈80% 曝光；§20.1 状态链 VIEW→{VIEW,FAVORITE,CART,EXIT}、CART→{VIEW,ORDER_CREATED,CART_REMOVE,EXIT}、ORDER_CREATED→{PAID,CANCELLED}、PAID→{COMPLETED,REFUNDING}；§20.2 价格截断对数正态；§20.4 ScenarioStrategy + ExpectedEffect（不传给 AI 防泄漏）；§20.5 DirtyDataInjector 类型与期望处理；§20.6 验收（同种子同结果、事件格式一致、分布可观察、场景方向可识别、速率可调）。

---

### Task 1: 基础件（纯逻辑 + 单测）

**Files:**
- Create: `generator/GeneratorConfig.java` — record：userCount、eventsPerSecond、baseConversionRate、startTime/endTime(LocalDateTime)、randomSeed(long)、dirtyDataRate、scenario(String)、productCount；校验方法
- Create: `generator/DistributionKit.java` — 种子 RNG 包装：`weightedPick(double[])`、`zipfRank(s)`、`boundedLogNormal(min,median,max)`、`uniform(min,max)`、`nextInt/nextDouble`（全部走同一 Random 保证可复现）
- Create: `generator/HourlyTraffic.java` — `double[24]` 权重 + `weekendBoost()`
- Create: `generator/BehaviorChain.java` — 纯函数：`orderNext(current, rng, conversionProb, refundProb)`：ORDER_CREATED→PAID|CANCELLED；PAID→COMPLETED|REFUNDING；REFUNDING→REFUNDED；返回 null=终止
- Test: `generator/DistributionKitTest`、`generator/BehaviorChainTest`（固定 seed 断言序列可复现；转移概率 0/1 边界）

### Task 2: 场景体系

**Files:**
- Create: `generator/ScenarioCode.java` — 11 枚举：normal、weekend_growth、promotion、new_product_cold_start、hot_product、stock_shortage、price_increase、sales_decline、refund_rise、new_user_growth、old_user_churn
- Create: `generator/GenerationFactors.java` — record：conversionMultiplier、refundMultiplier、priceMultiplier、newUserRatio、oldUserWeight、hotProductIds、shortageProductIds、affectedCategories
- Create: `generator/ExpectedEffect.java` — record：`Map<String,String> directions`（如 gmv=UP、refund_rate=UP）
- Create: `generator/scenario/ScenarioStrategy.java` — 接口 `code()/factors(ScenarioContext)/expectedEffect()`
- Create: `generator/scenario/ScenarioRegistry.java` — 11 个实现注册（共享 `applyShift` 辅助）；每个场景改的是因子不是最终指标
- Test: `generator/scenario/ScenarioRegistryTest` — 11 场景齐全、code 唯一、normal 为中性因子

### Task 3: 服务扩展（阶段 3 需要的事件通道）

**Files:**
- Create: `domain/service/ProductManagementService.java` — `createProduct(name, categoryId, brandId, price, cost)`：校验 price≥cost、插入并 append product_created
- Create: `domain/service/InventoryService.java` — `adjustStock(productId, qty, changeType)`：条件更新并 append stock_changed

### Task 4: 脏数据注入器

**Files:**
- Create: `generator/DirtyDataInjector.java` — 按 dirtyDataRate 决定注入条数；类型：missing_field(缺 user_id)、illegal_enum(behavior_type=fly)、future_time(明天)、negative_qty(订单项 −1)、amount_mismatch(paid≠total)、duplicate_event_id(复用已生成正常 event_id)、unknown_schema(9.9)；每类记录期望处理（拒绝/隔离/去重/告警）；写入 `{landing}/dirty/{yyyyMMddHH}.jsonl`
- Test: `generator/DirtyDataInjectorTest` — 注入后文件 7 类齐全、期望处理映射正确、不触碰业务表

### Task 5: 模拟引擎 + 运行服务

**Files:**
- Create: `generator/SimulationEngine.java` — ①建用户（分层）②产品池（种子商品；productCount>0 时按分类模板对数正态补建）③按小时曲线×事件预算生成行为（view 88%/favorite 4%/cart_add 5%/cart_remove 1.5%/search 1.5%，场景系数调节；商品选择权=Zipf×分类偏好×价格接受×场景系数）④cart_add→下单（convProb）→PAID/CANCELLED→REFUNDING→REFUNDED⑤同一 seed 顺序消费 RNG；事件经既有 Service/outbox append
- Create: `generator/GeneratorRunService.java` — 组装（config → 场景 factors → engine → 脏数据 → GenerationResult）
- Create: `generator/GeneratorDtos.java`、`generator/GenerationResult.java`
- Create: `controller/GeneratorController.java` — POST /api/v1/generator/runs、GET /api/v1/generator/scenarios

### Task 6: 验证（本机 MySQL + JUnit）

- Test: `generator/GeneratorDeterminismTest` — 同一 seed 跑两遍：事件类型计数/gmv/订单数完全一致
- Test: `generator/ScenarioEffectTest` — promotion vs normal：gmv、订单数上升；refund_rise vs normal：退款率上升（NS 传播，非事务测试）
- [ ] `mvn test` 全绿（原 17 + 新增）
- [ ] 冒烟：启动应用 POST /generator/runs 跑 1 分钟规模场景，核对 landing/events 与 landing/dirty；GET /generator/scenarios
- [ ] 更新 README（阶段 3 ✅）、提交

**验收（本轮完成定义）：** 生成器可复现（同种子同结果）；促销/退款两场景方向可识别；行为、订单、支付、退款全部走业务 Service 产生事件；脏数据独立落 dirty 目录；API 可用；测试全绿。