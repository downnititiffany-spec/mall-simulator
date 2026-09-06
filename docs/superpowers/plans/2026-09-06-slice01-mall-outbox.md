# Slice01: 契约基准 + 最小商城 + Outbox + 事件发布 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 D:\Develop_code\GraduationProject 建立 Git 仓库与文档契约，并实现可独立运行的 mall-simulator（Spring Boot 3 + MySQL 8）：商品/购物车/下单/支付/退款业务 + 事务 Outbox + 按小时滚动 JSON 事件日志 + 黄金数据对账测试。

**Architecture:** mall-simulator 是独立进程、独立数据库（mall_simulator）的 Spring Boot 3 应用。领域 Service 在同一事务中写业务表与 event_outbox；OutboxPublisher 定时把未发布事件写为 `landing/events/{yyyyMMddHH}.jsonl` 并标记 published_at。状态机抽取为纯 Java 类 OrderStateMachine 以支持无 DB 单元测试；EventIdGenerator + Clock 接口注入保证可复现（黄金数据确定性）。

**Tech Stack:** JDK 17、Spring Boot 3.2.5、MyBatis-Plus 3.5.7（spring-boot3 starter）、Flyway、MySQL 8.0.41（root/123456，凭据仅经环境变量）、JUnit 5、Lombok、Jackson。

**外部约束（来自 V2.2 文稿）:** §5.2.4 信封契约、§20.1 状态机（CREATED→PAID→COMPLETED；旁路 CANCELLED、REFUNDING→REFUNDED）、§21.2 金额 BigDecimal/DECIMAL(18,2)、§19.1 目录边界、§28.4 黄金数据。

---

### Task 1: 仓库初始化与契约文档

**Files:**
- Create: `.gitignore`、`README.md`
- Create: `docs/contracts/event-contract.md`、`docs/contracts/metric-dictionary.md`
- Create: `docs/superpowers/plans/2026-09-06-slice01-mall-outbox.md`（本文件）

- [ ] **Step 1: 已执行 git init 于仓库根；.gitignore 忽略 target/、node_modules/、.env.local、landing/、借鉴项目/、text-to-sql/、backups/、tmp/、*.pdf**
- [ ] **Step 2: 契约文档已产出（事件信封 12 类事件、15 项指标字典），本文件即计划**
- [ ] **Step 3: Commit**

```bash
git add .gitignore README.md docs/ && git commit -m "docs: slice01 契约基准——事件契约与指标字典 v1"
```

### Task 2: mall-simulator Maven 骨架

**Files:**
- Create: `mall-simulator/pom.xml`
- Create: `mall-simulator/src/main/resources/application.yml`
- Create: `mall-simulator/src/main/resources/db/migration/V1__init_mall.sql`
- Create: `mall-simulator/src/main/java/com/graduation/mall/MallSimulatorApplication.java`
- Create: `mall-simulator/src/test/resources/application-test.yml`

- [ ] **Step 1: pom.xml** — parent spring-boot-starter-parent 3.2.5；dependencies：web、validation、mybatis-plus-spring-boot3-starter 3.5.7、mysql-connector-j、flyway-core+flyway-mysql、lombok、spring-boot-starter-test；属性 `java.version=17`
- [ ] **Step 2: application.yml** — 端口 8090；数据源 `jdbc:mysql://127.0.0.1:3306/mall_simulator?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true`，账号 `${MALL_DB_USER:root}`，密码 `${MALL_DB_PASSWORD:}`；`mall.landing.path=${MALL_LANDING_PATH:./landing}`；`mall.outbox.batch-size=200`、`poll-seconds=10`；`spring.flyway.enabled=true`
- [ ] **Step 3: V1__init_mall.sql** — 建 10 张表：mall_user、category、product、inventory、cart_item、mall_order、order_item、payment、refund、event_outbox。金额 `DECIMAL(18,2)`，event_outbox.event_id VARCHAR(64) UNIQUE，published_at DATETIME(3) NULL，payload JSON。首版商品目录种子数据（Insert 8 个一级分类、每类若干商品、库存）随迁移入库
- [ ] **Step 4: application-test.yml** — 同 URL 但库名 `mall_simulator_test`（createDatabaseIfNotExist=true），Flyway 开启
- [ ] **Step 5: 验证编译**

```bash
cd mall-simulator && MALL_DB_PASSWORD=$(cat ../.env.local 2>/dev/null | grep MALL_DB_PASSWORD | cut -d= -f2) mvn -q compile
```

- [ ] **Step 6: Commit**

```bash
git add mall-simulator && git commit -m "feat(mall): Maven 骨架与 Flyway V1 建表"
```

### Task 3: 领域层（实体/枚举/Mapper + 状态机）

**Files:**
- Create: `mall-simulator/src/main/java/com/graduation/mall/domain/enums/`（OrderStatus、EventType、BehaviorType、ProductStatus、RefundStatus、PaymentStatus、MemberLevel、CityLevel、AgeGroup）
- Create: `mall-simulator/src/main/java/com/graduation/mall/domain/entity/`（MallUser、Category、Product、Inventory、CartItem、MallOrder、OrderItem、Payment、Refund、EventOutbox，MyBatis-Plus 注解）
- Create: `mall-simulator/src/main/java/com/graduation/mall/domain/mapper/`（10 个 Mapper extends BaseMapper）
- Create: `mall-simulator/src/main/java/com/graduation/mall/domain/state/OrderStateMachine.java`（纯逻辑）
- Test: `mall-simulator/src/test/java/com/graduation/mall/domain/OrderStateMachineTest.java`

- [ ] **Step 1: OrderStateMachine** — `static void validateTransition(OrderStatus from, OrderStatus to)`：合法边 CREATED→PAID、CREATED→CANCELLED、PAID→COMPLETED、PAID→REFUNDING、COMPLETED→REFUNDING、REFUNDING→REFUNDED；其余抛 `IllegalOrderStateException`（含 message 与行号定位）
- [ ] **Step 2: OrderStateMachineTest** — 6 条合法流转逐条断言通过；非法流转 `PAID→CANCELLED`、`CREATED→REFUNDING`、`REFUNDED→PAID` 断言抛异常
- [ ] **Step 3: 运行测试** — `mvn -q -Dtest=OrderStateMachineTest test` 全绿
- [ ] **Step 4: Commit**

### Task 4: Outbox 层

**Files:**
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventContract.java`（常量：SCHEMA_VERSION="1.0"、SOURCE_SYSTEM="mock-mall"、事件类型与行为类型枚举常量、金额正则）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventEnvelope.java`（record：event_id/event_type/event_time/ingest_time/source_system/schema_version/trace_id/payload(Map<String,Object>)；`toJson()` 用 ObjectMapper 输出固定字段序）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventPayloadFactory.java`（12 类事件 payload 构造，金额 BigDecimal → 字符串）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventIdGenerator.java` + `UuidEventIdGenerator`
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventClock.java`（Clock 包装：`now()`→OffsetDateTime）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/EventOutboxService.java`（`append(TraceContext, eventType, aggregateType, aggregateId, payloadBuilder)`：构造 event_id/ingest_time 并 insert outbox；`markPublished(eventId)` 按 event_id 且 published_at IS NULL 更新）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/RollingJsonEventWriter.java`（`write(EventEnvelope)`：追加 `{landing}/events/{yyyyMMddHH}.jsonl`，行尾 \n，UTF-8）
- Create: `mall-simulator/src/main/java/com/graduation/mall/outbox/OutboxPublisher.java`（`publishOnce()`：批量取 published_at IS NULL 的事件（batch-size）→ writer 逐行写 → 每行成功后 markPublished；`pendingCount()`；@Scheduled(fixedDelayString="${mall.outbox.poll-seconds}000") 自动执行 + Controller 手动触发）

- [ ] **Step 1: 编写 EventOutboxServiceTest（集成，@SpringBootTest + @DynamicPropertySource 指向 mall_simulator_test）** — ① append 后 outbox 行存在且 envelope JSON 字段齐全；② markPublished 后再 append 相同 event_id 抛唯一键异常
- [ ] **Step 2: OutboxPublisherTest** — 生成 N 个事件 → publishOnce → writer 文件行数=N、published_at 已设置 → 再次 publishOnce 行数不变；用固定 EventIdGenerator 断言 event_id 确定性
- [ ] **Step 3: 运行测试全绿后 Commit**

### Task 5: 业务服务与 Controller

**Files:**
- Create: `mall-simulator/src/main/java/com/graduation/mall/domain/service/MallBusinessService.java`（用户注册、商品查询、加购、下单（校验库存/金额计算/写订单+订单项+outbox+预扣库存）、支付、取消、退款申请/完成；全部 @Transactional，trace_id 由 TraceContext 携带，事件由 EventOutboxService.append 生成）
- Create: `mall-simulator/src/main/java/com/graduation/mall/controller/MallController.java`（/api/v1/mall/users|products|cart/items|orders|orders/{id}/pay|cancel|refunds|orders 列表）
- Create: `mall-simulator/src/main/java/com/graduation/mall/controller/OutboxController.java`（GET /api/v1/mall/outbox/status → pendingCount+lastFile；POST /api/v1/mall/outbox/publish → publishOnce 结果）
- Create: `mall-simulator/src/main/java/com/graduation/mall/config/AppConfig.java`（EventIdGenerator、Clock bean；Clock 默认系统时区 Asia/Shanghai）

- [ ] **Step 1: OutboxTransactionTest** — 固定 EventIdGenerator（注入固定 UUID）：先在 outbox 预插同 event_id 行 → 调 createOrder → 断言抛异常且 **mall_order 无新增行**（同事务回滚证据）
- [ ] **Step 2: MallBusinessService 集成测试** — 下单→支付→完成全链路；金额 = Σ(quantity×unit_price)；库存预扣正确；支付后重复支付拒绝；退款金额 > 已付金额拒绝
- [ ] **Step 3: 运行测试全绿后 Commit**

### Task 6: 黄金数据与对账测试

**Files:**
- Create: `tests/golden-dataset/events/golden-20260901.jsonl`（手写固定事件：3 用户注册、4 商品建档、10 行为（6 view/1 favorite/2 cart_add/1 search）、3 订单（1 支付 179.80、1 取消、1 支付 46.50 后部分退款 20.00）、2 库存事件）
- Create: `tests/golden-dataset/expected/golden-20260901-expected.json`（标准答案：pv=6、uv=3、dau=3、paid_order_cnt=2、gmv=226.30、net_sale=206.30、avg_order_value=113.15、refund_rate=0.5、repeat_rate=0）
- Test: `mall-simulator/src/test/java/com/graduation/mall/golden/GoldenDatasetTest.java`（解析 jsonl 逐行校验信封必需字段/枚举/金额格式；按指标字典口径计算并与 expected.json 断言一致）

- [ ] **Step 1: 编写 golden 文件（事件与标准答案手工核算）**
- [ ] **Step 2: GoldenDatasetTest 全绿**
- [ ] **Step 3: Commit**

### Task 7: 全量验证与冒烟

- [ ] **Step 1: `mvn test`（MALL_DB_PASSWORD 环境变量）全部通过**
- [ ] **Step 2: 后台启动 `mvn spring-boot:run` → curl 冒烟：注册用户→建商品（种子已在迁移）→下单→支付→退款→GET outbox/status→POST outbox/publish→检查 landing/events/{yyyyMMddHH}.jsonl 行数与内容→MySQL select event_outbox published_at 非空**
- [ ] **Step 3: 停止应用，提交全部**

**验收（本轮完成定义）：** 契约文档与指标字典入库；mvn test 全绿（状态机/事务回滚/Publisher/黄金数据对账）；冒烟链路（下单→事件落盘→published_at）证据截图记录于本轮总结。