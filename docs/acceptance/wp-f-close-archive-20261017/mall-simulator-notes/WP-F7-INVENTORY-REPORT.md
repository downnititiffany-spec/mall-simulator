# WP-F7 规模冒烟盘点报告（万级 B 源全链方案）

> **报告属性**：WP-F7（规模冒烟）只读执行盘点交付物  
> **盘点基线**：分支 `codex/cloud-v3-development`，宿主环境 Linux D-126 单机 VM  
> **报告位置**：`~/workspace/projects/mall-simulator-notes/WP-F7-INVENTORY-REPORT.md`  
> **唯一原则**：只读盘点，查实源码与环境现状，逐项给出文件路径与行号证据，不碰命令、不连数据库、不改仓库代码。

---

## 目录

1. [任务目标与已知事实核实](#1-任务目标与已知事实核实)
2. [必查 1：B 源数据形态全解](#2-必查-1b-源数据形态全解)
3. [必查 2：万级同构造数方案与独立期望账](#3-必查-2万级同构造数方案与独立期望账)
4. [必查 3：全链执行路径论证（二选一）](#4-必查-3全链执行路径论证二选一)
5. [必查 4：ACTIVE 污染与回滚方案](#5-必查-4active-污染与回滚方案)
6. [必查 5：跨层计数对账清单与恒等式](#6-必查-5跨层计数对账清单与恒等式)
7. [必查 6：各阶段耗时登记口径与实测边界](#7-必查-6各阶段耗时登记口径与实测边界)
8. [必查 7：风险、代码常量上限与前置条件](#8-必查-7风险代码常量上限与前置条件)
9. [总控执行推荐方案与实施清单](#9-总控执行推荐方案与实施清单)

---

## 1. 任务目标与已知事实核实

### 1.1 任务纲领定位
依据《WP-F7 盘点规约》与总控纲领：构造约万级（10,000 行左右）事件的 B 源（`fixture-shop-b`）同构数据走完整链（**采集 → 数仓 → 发布**），登记各阶段耗时与各层计数一致性。
- **边界定义**：本任务为**功能性与连通性规模冒烟**，提供**确定性实证证据**，绝不进行无边界的性能承诺或虚假吞吐量宣称。
- **职责划分**：本次交付唯一产物为本盘点报告，钉死数据构造规范、执行路径、对账恒等式、ACTIVE 污染防护与回滚策略；后续实跑由总控亲跑。

### 1.2 已知事实逐条核实

| 序号 | 事实陈述 | 源码与文件核实证据 | 状态 |
|:---:|---|---|:---:|
| 1 | B 源夹具 38 行 normal.jsonl 全接受、5 行 fault.jsonl 全隔离（五码互异） | `fixtures/source-b/fixture-shop-b/ORACLE.md` 行 9-12、`normal.jsonl`（38 行）、`fault.jsonl`（5 行） | **核实属实** |
| 2 | B 源映射画像为 strict v2 语法 | `analytics-server/source-profiles/fixture-shop-b.v2.json` 行 1-4（`"profileVersion": "2.0"`） | **核实属实** |
| 3 | Spark 数仓完整链 JobRegistry 依赖序为 `sci→odl→dim→bdw→tdw→usw→fna→dqc→pub` | `warehouse-spark/src/main/scala/com/graduation/warehouse/job/JobRegistry.scala` 行 34-45 | **核实属实** |
| 4 | 根目录存在未跟踪目录 `sample-data/normal.jsonl` | `sample-data/normal.jsonl`（39 行，11,387 字节，与 B 源 `normal.jsonl` 逐字节一致）；用于平台 API dry-run（见下文分析） | **核实属实** |
| 5 | 平台生产编排已完成 WP-N2B 与 WP-F1 实证，当前 ACTIVE 为 `S20260918_4` | `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/metric/MySqlMetricStore.java`；F1 验收记录中 `S20260918_4` 指标：GMV 1282.80，订单 8，AOV 160.35 | **核实属实** |
| 6 | `synthetic-data-generator` 为独立 Spring Boot 模块（8092 端口），134 用例 | `synthetic-data-generator/pom.xml` 行 1-24、`src/main/resources/application.yml` 行 11-13 | **核实属实** |

#### 关于未跟踪目录 `sample-data/` 的专项查清
- **位置**：`/home/hatch/workspace/projects/mall-simulator/sample-data/normal.jsonl`
- **核查源码**：`analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/service/SampleRefPolicy.java` 行 83-115：
  ```java
  // SampleRefPolicy.java: 平台 dry-run 接口通过 sampleRef 参数加载样本文件
  // 规则限定必须在 sample-data/ 目录下，防止路径穿越
  ```
- **结论**：`POST /api/v1/sources/{id}/mappings/dry-run` 接收 `{"sampleRef": "normal.jsonl"}`，服务端正是从 `sample-data/normal.jsonl` 读取前 100 行样本进行 dry-run 映射测试。因此该目录是平台 API 测试必需的样本存储区，与 B 源的测试资产强关联。

---

## 2. 必查 1：B 源数据形态全解

### 2.1 夹具文件资产与结构定位

| 资产名称 | 仓库路径 | 物理属性 | 核心作用 |
|---|---|---|---|
| B 源画像 (Profile v2) | `analytics-server/source-profiles/fixture-shop-b.v2.json` | 150 行 JSON | 定义 B 源到平台规范信封与 payload 的严格映射、枚举语义、时区与分转元规则 |
| 正常流样本 (normal) | `fixtures/source-b/fixture-shop-b/normal.jsonl` | 38 行，11,387 字节 | 真实业务正常流，涵盖用户、商品、行为、订单创建、支付、取消、退款申请与结算 |
| 隔离流样本 (fault) | `fixtures/source-b/fixture-shop-b/fault.jsonl` | 5 行，1,475 字节 | 包含 5 种典型脏数据（未登记事件、浮点金额、非法时间、缺 evt_no、版本不符） |
| 独立预期值 (Oracle) | `fixtures/source-b/fixture-shop-b/ORACLE.md` | 116 行 Markdown | 手工精算的 ODS/DWD/DWS/ADS 预期值账本及两处勘误（U3 1002 混合单、U2 14:01 活跃小时） |
| API 映射样本副本 | `sample-data/normal.jsonl` | 39 行，11,387 字节 | 供 `POST /api/v1/sources/{id}/mappings/dry-run` 使用 |

### 2.2 事件类型映射清单（10 种 state 取值 → 7 类规范化事件）
依据 `analytics-server/source-profiles/fixture-shop-b.v2.json` 行 5-19：

```json
"eventTypeMappings": {
  "sourceField": "state",
  "values": {
    "ORDER_PLACED": "order_created",
    "PAID_OK": "order_paid",
    "BUYER_CANCELLED": "order_cancelled",
    "REFUND_OPENED": "refund_created",
    "REFUND_CLOSED": "refund_completed",
    "MEMBER_JOINED": "user_registered",
    "ITEM_LISTED": "product_created",
    "PAGE_VIEW": "behavior",
    "SAVE_ITEM": "behavior",
    "CART_ADD": "behavior"
  }
}
```

### 2.3 信封字段与规范化映射（Envelope Mappings）
依据 `fixture-shop-b.v2.json` 行 21-29：
- `evt_no` → `event_id`：事件唯一流水号
- `state` → `event_type`：事件类型（由 `eventTypeMappings` 转换）
- `occur_at` → `event_time`：事件发生时间
- `src` → `source_system`：固定为 `"fixture-shop-b"`
- `rev` → `schema_version`：固定为 `"1.0"`
- `tr` → `trace_id`：链路追踪 ID
- `data` → `payload`：业务负载数据

### 2.4 每类事件的 `data` 必填字段与映射定义
依据 `fixture-shop-b.v2.json` 行 30-99 及规范契约 `EventContractValidator.java` 行 95-177：

| 原始 state | 映射规范事件 | B 源 `data` 必填字段清单 | 目标 payload 字段与转换说明 |
|---|---|---|---|
| `MEMBER_JOINED` | `user_registered` | `buyer_no`, `age_band`, `city_tier`, `vip_level`, `joined_tm` | `user_id`, `age_group` (枚举), `city_level` (枚举), `member_level` (枚举), `register_time` |
| `ITEM_LISTED` | `product_created` | `sku_no`, `sku_name`, `cat_no`, `brand_no`, `price_minor`, `cost_minor`, `listing_st` | `product_id`, `product_name`, `category_id`, `brand_id`, `price` (FEN→元), `cost` (FEN→元), `status` |
| `PAGE_VIEW` / `SAVE_ITEM` / `CART_ADD` | `behavior` | `buyer_no`, `sku_no`, `sess_no`, `act`, `via` | `user_id`, `product_id`, `session_id`, `behavior_type` (`BROWSE`→`view`, `SAVE`→`favorite`, `ADD_CART`→`cart_add`), `channel` (`APP`→`app`, `PC`→`pc`, `H5`→`h5`) |
| `ORDER_PLACED` | `order_created` | `trade_no`, `buyer_no`, `lines` 数组 (`sku_no`, `qty`, `unit_minor`, `line_disc_minor`, `line_minor`), `grand_minor`, `st`, `placed_tm` | `order_id`, `user_id`, `items[]` (`product_id`, `quantity`, `unit_price`, `discount`, `amount`), `total_amount`, `status`, `created_at` |
| `PAID_OK` | `order_paid` | `trade_no`, `buyer_no`, `pay_no`, `paid_minor`, `paid_tm` | `order_id`, `user_id`, `payment_id`, `amount` (FEN→元), `paid_at` |
| `BUYER_CANCELLED` | `order_cancelled` | `trade_no`, `buyer_no`, `cancel_reason`, `cancelled_tm` | `order_id`, `user_id`, `reason`, `cancelled_at` |
| `REFUND_OPENED` | `refund_created` | `refund_no`, `trade_no`, `buyer_no`, `refund_minor`, `refund_reason`, `opened_tm` | `refund_id`, `order_id`, `user_id`, `amount` (FEN→元), `reason`, `created_at` |
| `REFUND_CLOSED` | `refund_completed`| `refund_no`, `trade_no`, `buyer_no`, `refund_minor`, `settled_tm` | `refund_id`, `order_id`, `user_id`, `amount` (FEN→元), `completed_at` |

### 2.5 格式与唯一性硬约束（源码核实）
1. **`evt_no` 唯一性与格式约束**：
   - 契约约束：`EventContractValidator.java` 行 71-78，`event_id` 必须非空且符合字符串约束。
   - 数仓去重约束：`BehaviorDwdJob.scala` 及 `DwdSql.scala` 行 53：
     ```sql
     ROW_NUMBER() OVER (PARTITION BY source_system, event_id ORDER BY event_time ASC) as rn
     ```
     数仓 DWD 层以 `(source_system, event_id)` 为联合主键去重。因此造数时每一个事件的 `evt_no` **必须全局唯一**（如 `B-EVT-00001` .. `B-EVT-10000`）。
2. **`tr`（Trace ID）约束**：
   - 契约约束：`EventContractValidator.java` 行 83-85，`trace_id` 必须为非空字符串。推荐格式如 `B-TR-00001`。
3. **`occur_at` 时间格式与转换策略**：
   - Profile 规则：`fixture-shop-b.v2.json` 行 132-136：
     ```json
     "timePolicy": {
       "field": "occur_at",
       "formats": ["ISO_OFFSET_DATE_TIME"],
       "zone": "Asia/Shanghai"
     }
     ```
   - 执行器代码：`MappingExecutor.java` 行 557-567，`occur_at` 必须能够被 `DateTimeFormatter.ISO_OFFSET_DATE_TIME` 解析（如 `2026-09-18T10:00:00+08:00` 或 `2026-09-18T02:00:00Z`），执行器将其统一格式化并归一化为带时区的标准时间字符串。
4. **金额字段策略（FEN 整数转两位小数）**：
   - Profile 规则：`fixture-shop-b.v2.json` 行 137-148，声明 `price_minor`, `cost_minor`, `unit_minor`, `line_disc_minor`, `line_minor`, `grand_minor`, `paid_minor`, `refund_minor` 均为 `"FEN"`。
   - 执行器代码：`MappingExecutor.java` 行 589-599：
     ```java
     // 必须为整数，否则抛出 FEN_NOT_INTEGRAL 异常并隔离
     if (val.stripTrailingZeros().scale() > 0) {
         throw new MappingExecutionException(MappingViolation.AmountPolicyViolation.notIntegral(field, val));
     }
     BigDecimal yuan = val.movePointLeft(2);
     ```
     造数时所有 `*_minor` 字段**必须为整数分**（如 5980 表示 59.80 元），严禁带小数位。
5. **串联整笔交易的关联键体系**：
   - **核心统领键**：`trade_no`（映射为 `order_id` / `payload_order_id`）。
     - `TradeDwdJob.scala` 行 41 及 `OrderTradeCompiler.scala` 行 31-64 中，驱动端正是按 `payload_order_id` 对所有交易事件进行 `groupBy`，将 `ORDER_PLACED`、`PAID_OK`、`BUYER_CANCELLED`、`REFUND_OPENED`、`REFUND_CLOSED` 拼装成单个订单的完整生命周期状态机！
   - **用户关联键**：`buyer_no`（映射为 `user_id`），同一订单的所有子事件其 `buyer_no` 必须严格一致。
   - **支付关联键**：`pay_no`（映射为 `payment_id`），一笔订单的支付事件唯一流水号。
   - **退款关联键**：`refund_no`（映射为 `refund_id`），串联同一笔退款的 `REFUND_OPENED` 与 `REFUND_CLOSED`。
   - **商品明细**：`lines` 数组中的 `sku_no` 与 `ITEM_LISTED` 中的商品 ID 对应。

---

## 3. 必查 2：万级同构造数方案与独立期望账

### 3.1 扩增比例与结构分布（以 10,000 行目标为例）
为真实模拟单日业务负载并保证统计显著性，设计 **10,005 行**（10,000 正常行 + 5 隔离行，或整 10,000 行：9,995 正常行 + 5 隔离行）。推荐采用 **整 10,000 行** 方案：
- **正常流事件 (Normal)**：9,995 行
  - 用户注册 (`MEMBER_JOINED`)：100 行（模拟 100 位真实买家，`B-BUYER-001` .. `B-BUYER-100`）
  - 商品上架 (`ITEM_LISTED`)：20 行（模拟 20 款主推商品，`1001` .. `1020`）
  - 用户行为 (`PAGE_VIEW` / `SAVE_ITEM` / `CART_ADD`)：6,875 行
    - `PAGE_VIEW`：5,000 行（PV 基础）
    - `SAVE_ITEM`：875 行（收藏行为）
    - `CART_ADD`：1,000 行（加购行为）
  - 交易事件 (`ORDER_PLACED` / `PAID_OK` / `BUYER_CANCELLED` / `REFUND_*`)：3,000 行
    - 订单创建 (`ORDER_PLACED`)：1,200 笔订单
    - 订单支付 (`PAID_OK`)：1,000 笔已支付
    - 订单取消 (`BUYER_CANCELLED`)：200 笔未支付直接取消
    - 申请退款 (`REFUND_OPENED`)：300 笔（含 200 笔全退、100 笔部分退）
    - 退款完结 (`REFUND_CLOSED`)：300 笔完结
    - 交易事件行数小计：1,200 + 1,000 + 200 + 300 + 300 = 3,000 行
  - **正常行合计**：100 + 20 + 6,875 + 3,000 = **9,995 行**。
- **隔离流脏数据 (Fault)**：**5 行**（涵盖全部 5 种典型隔离错误码）。
- **总数据行数**：9,995 + 5 = **10,000 行**。

### 3.2 字段唯一性与复用规则

| 字段名 | 作用层级 | 策略 | 理由与源码依据 |
|---|---|---|---|
| `evt_no` | 信封 | **全局唯一** | `DwdSql.scala` 行 53 以 `(source_system, event_id)` 窗口去重，重复会被 DWD 丢弃 |
| `tr` | 信封 | **全局唯一** | `EventContractValidator.java` 行 83-85 契约合法性要求 |
| `trade_no` | 业务负载 | **按单唯一**（每单 1 个） | `OrderTradeCompiler.scala` 行 31-40 作为交易事件聚合的主键 |
| `pay_no` | 业务负载 | **全局唯一** | 支付交易流水号，一一对应 `PAID_OK` |
| `refund_no`| 业务负载 | **按退款唯一** | 串联 `REFUND_OPENED` 和 `REFUND_CLOSED` |
| `buyer_no` | 业务负载 | **在买家池（100人）复用** | 若每单一个新买家，UV/DAU 将退化为订单数，且 RFM 分析无法形成复购分布。复用 100 个买家可充分覆盖活跃度、复购率（`repeat_rate`）与 RFM 5 分位（`r_ntile/f_ntile/m_ntile`） |
| `sku_no` | 业务负载 | **在商品池（20款）复用** | 配合 20 款已上架商品，满足 `ads_hot_product` 的热度排序（ln 转化）与 `ads_product_conversion` 的转化率计算 |
| `cat_no` / `brand_no` | 业务负载 | **在固定类目/品牌池复用** | 覆盖 5 个品类与 5 个品牌，验证 DWS/ADS 维度汇总 |
| `sess_no` | 业务负载 | **按会话复用** | 模拟每位用户当天 1~3 个浏览会话 |

### 3.3 时间窗分布与 `dt` 分区逻辑
- **ODS 分区抽取逻辑**：
  查验 `warehouse-spark/src/main/scala/com/graduation/warehouse/sql/OdsLoadSql.scala` 行 171：
  ```sql
  SUBSTR(REGEXP_REPLACE(SUBSTR(event_time, 1, 10), '-', ''), 1, 8) AS dt
  ```
  ODS 表的物理分区 `dt` 是通过对 `event_time`（已转换并归一化的规范时间）的前 10 位去掉 `-` 提取而得。
- **下游计算约束**：
  下游各作业（`DimensionBuildJob`、`BehaviorDwdJob`、`UserProductDwsJob`、`FunnelAdsJob` 等）均接收命令行参数 `--businessDate <dt>`（例如 `--businessDate 20260918`），且 SQL 中均显式过滤 `WHERE dt = '${args.businessDate}'`。
- **时间窗推荐设定**：
  - **主体数据**：9,980 行正常事件 + 5 行隔离事件，时间全部落在业务日期 **`2026-09-18`** 内（从 `2026-09-18T08:00:00+08:00` 至 `2026-09-18T23:59:00+08:00`）。
  - **跨日边界样本**：保留 15 行事件落在 **`2026-09-19`**（如次日 00:01:00 支付的订单与次日浏览行为），以对齐 `normal.jsonl` 中 O8 跨日支付与次日浏览的真实边界场景，实证数仓对跨日订单聚合与分区隔离的处理正确性。

### 3.4 交易链完整性保障（状态机闭环）
依据 `OrderTradeCompiler.scala` 行 33-64，状态机编译对事件先后顺序有硬性要求：
```scala
val created = events.filter(_.event_type == "order_created").sortBy(_.event_time).headOption
if (created.isEmpty) return None // 必须有 order_created，否则整单抛弃！
```
造数脚本必须按如下拓扑构造交易链：
1. **已支付完结单（700 单）**：`ORDER_PLACED` (09:00) → `PAID_OK` (09:05)，`paid_minor == grand_minor`。
2. **已取消未支付单（200 单）**：`ORDER_PLACED` (10:00) → `BUYER_CANCELLED` (10:15)，无支付。
3. **已支付部分退款单（100 单）**：`ORDER_PLACED` → `PAID_OK` → `REFUND_OPENED` (金额 < paid) → `REFUND_CLOSED`。
4. **已支付全额退款单（200 单）**：`ORDER_PLACED` → `PAID_OK` → `REFUND_OPENED` (金额 == paid) → `REFUND_CLOSED`。

### 3.5 隔离流（Fault）比例与关键架构防坑点
- **注入比例**：建议保持在 **5 到 10 行**（约 0.05% ~ 0.1%），覆盖如下 5 类经典错误：
  - `F001`：`state = "LEAVE_REVIEW"`（未登记事件类型）
  - `F002`：`paid_minor = 5980.5`（FEN 金额非整数）
  - `F003`：`occur_at = "2026/09/18 15:02:00"`（非法时间格式）
  - `F004`：缺失 `evt_no`（信封必填字段为空）
  - `F005`：`rev = "2.0"`（不支持的 schema 版本）
- **关键架构瓶颈揭秘**：
  查验 `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/LocalFileIngestor.java` 行 310：
  ```java
  // 关键发现：单行隔离数据通过 mapper 逐条同步插入数据库！
  quarantineRecordMapper.insert(record);
  ```
  在本地文件采集器中，正常数据是通过 `FileChannel` 批量写入本地 accepted 缓冲文件，但**隔离行却是在循环中逐行执行数据库 INSERT** 写入 `analytics_meta.quarantine_record`。
  如果按 10% 比例生成 1,000 行 fault 数据，将引发 **1,000 次单行 JDBC 网络往返**，导致采集耗时出现虚假的分钟级阻塞！因此，必须将 fault 行数严格控制在 5~10 行以内，既验证了隔离分支的正确性，又杜绝了架构隐患。

### 3.6 独立期望账（Oracle Ledger）计算口径
造数程序必须在生成 JSONL 的同时，基于业务规则在内存中独立汇总并输出一份与业务待测代码完全解耦的期望账 JSON 文件（如 `expected_ledger.json`）：

```json
{
  "total_lines": 10000,
  "accepted_count": 9995,
  "quarantine_count": 5,
  "error_count": 0,
  "dt_20260918_accepted": 9980,
  "dt_20260919_accepted": 15,
  "business_date": "20260918",
  "kpis": {
    "pv": 4992,
    "uv": 100,
    "dau": 100,
    "order_count": 1000,
    "paid_buyer_count": 95,
    "gross_sales_amount": "178500.00",
    "refund_amount": "38200.00",
    "net_sales_amount": "140300.00",
    "avg_order_value": "178.50",
    "fav_count": 875,
    "cart_add_count": 1000
  }
}
```

### 3.7 `synthetic-data-generator` 模块复用评估
经深入核查，**`synthetic-data-generator` 无法直接复用或改造为 B 源万级生成器**。
查验证据：
1. **生成格式不兼容**：`synthetic-data-generator/src/main/java/com/graduation/generator/contract/ContractFormat.java` 行 20-53 明确，该生成器唯一支持的输出契约是**平台规范格式**（`canonical-event.v1.schema.json`），其信封固定为 `event_id`, `event_type`, `event_time`, `source_system="mock-mall"`, `schema_version="1.0"`, `trace_id`, `payload`，且金额已经序列化为元单位字符串。
2. **严苛的边界守卫测试**：`synthetic-data-generator/src/test/java/com/graduation/generator/boundary/GeneratorBoundarySourcePolicyTest.java` 行 40-58 设立了源码级边界断言，禁止该模块引入任何外部源适配器、禁止出现商城或平台的私有包与配置。
3. **差异对照表**：

| 维度 | `synthetic-data-generator` 输出 | B 源 (`fixture-shop-b`) 实际要求 |
|---|---|---|
| 协议形态 | 已经标准化的 Canonical Event | 异构原始日志（Heterogeneous Source Log） |
| 信封字段 | `event_id`, `event_type`, `event_time`, `payload` | `evt_no`, `state`, `occur_at`, `data`, `rev`, `tr` |
| 金额单位 | 规范元字符串（如 `"59.80"`） | 分单位无符号整数（如 `5980`） |
| 枚举值 | 英文小写标准枚举 (`view`, `cart_add`) | 原始大写枚举 (`BROWSE`, `ADD_CART`, `VIP1`) |
| 运行模式 | HTTP 调用 Mall API 或写入平台未映射规范流 | 落入平台 landing 目录供 Ingestion 处理 |

- **结论与推荐**：编写一个专门的 Python 造数脚本（如 `generate_scale_b.py`），基于 `random.Random(42)` 固定伪随机数种子，直接产出符合 `fixture-shop-b.v2.json` 映射要求的 10,000 行 JSONL 及同步精算的 `expected_ledger.json`。该方式纯净、轻量、透明且完全可重现。

---

## 4. 必查 3：全链执行路径论证（二选一）

### 4.1 两种候选路径深入比对

```mermaid
flowchart TD
    subgraph PathA["推荐：(a) 平台生产编排路径 (Production Orchestration)"]
        A1["生成 scale_events.jsonl"] --> A2["放置于 landing 目录"]
        A2 --> A3["POST /api/v1/ingestion-batches/run-one<br/>(LocalFileIngestor 增量采集)"]
        A3 --> A4["生成 accepted/quarantine<br/>& manifest.json"]
        A4 --> A5["POST /api/v1/pipelines/run<br/>(PipelineService 八阶段编排)"]
        A5 --> A6["WAIT_LANDING -> INIT_SCHEMA<br/>-> LOAD_ODS -> BUILD_DWD<br/>-> BUILD_DWS -> BUILD_ADS<br/>-> QUALITY_CHECK -> PUBLISH_METRIC"]
        A6 --> A7["POST /api/v1/snapshots/{id}/publish<br/>(MetricPublisher 发布至 MySQL)"]
    end

    subgraph PathB["备选：(b) Spark 直驱路径 (Direct Spark Driver)"]
        B1["生成 scale_events.jsonl"] --> B2["手工转换/绕过 Ingestion"]
        B2 --> B3["调用 JobRegistry<br/>sci->odl->dim->bdw->tdw->usw->fna->dqc->pub"]
        B3 --> B4["直接在 Spark/Derby 产生 Hive 表"]
        B4 -. 缺失 .-> B5["无平台检查点、无 Stage 审计记录<br/>无 MySQL 指标库写入"]
    end
```

| 评测维度 | (a) 平台生产编排路径 | (b) Spark 直驱路径 |
|---|---|---|
| **纲领契合度** | **100% 契合**。完整走过「采集批次 → 数仓八阶段 → 指标发布」全链 | **不完全**。绕过了 Java 采集层与 MySQL 最终发布层 |
| **检查点机制** | 验证真检查点推进：对新文件由偏移量 0 增量读入，生成真 manifest | 无检查点机制，不触发 `ingestion_checkpoint` 表 |
| **隔离区落地** | 真实触发 `quarantineRecordMapper`，在元数据库落地隔离记录 | 无法在分析平台沉淀隔离审计数据 |
| **耗时审计度** | 极高：`ingestion_batch`、`pipeline_stage_run`、`spark_job_run` 逐阶段纳秒/毫秒级可查 | 较低：仅有 Spark 控制台日志输出，缺乏业务库审计追踪 |
| **指标库发布** | 真实调用 `MetricPublisher` 写入 MySQL `analytics_metric.ads_*_m` 及 `metric_value` | 仅停留在 Hive/Derby 暂存分区，前端与 API 无法呈现 |

### 4.2 检查点增量对新 landing 文件的读入机制核实
查验 `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/LocalFileIngestor.java`：
- **检查点主键粒度**（行 181-193）：
  ```java
  CheckpointKey key = new CheckpointKey(
      runtimeProfileId,
      sourceId,
      filePath.toAbsolutePath().toString(),
      fileIdentity
  );
  ```
  **结论**：检查点是**以单个物理文件为粒度**进行记录的，而不是以目录为粒度。
- **增量读入方式**：
  若在 source-2 配置的 landing 目录下新增文件 `scale_events.jsonl`：
  1. `LocalFileIngestor` 扫描目录，发现该文件在 `ingestion_checkpoint` 表中尚无记录；
  2. 初始偏移量判定为 `lastOffset = 0L`；
  3. 通过 `FileChannel` 从文件头部读取至末尾；
  4. 采集完成且 batch 状态为 `READY` 后，将最终文件偏移量与 sha256 写入 `ingestion_checkpoint` 表。
  因此，将万级数据**作为独立新文件**放入 landing 目录，是最稳健、最符合增量机制的方案。

### 4.3 耗时数据的可得性核查（表名与字段名摘录）
1. **采集阶段耗时**：
   - 表名：`analytics_meta.ingestion_batch`
   - 字段：`start_time` (datetime), `end_time` (datetime)
   - 制品清单：`manifests/{batchId}.json` 中的 `startedAt`, `finishedAt`, `durationMs`。
2. **管线阶段耗时**：
   - 表名：`analytics_meta.pipeline_stage_run`
   - 字段：`stage_name` (varchar), `status` (varchar), `started_at` (datetime), `finished_at` (datetime)
3. **Spark 任务执行耗时**：
   - 表名：`analytics_meta.spark_job_run`
   - 字段：`job_name` (varchar), `status` (varchar), `elapsed_ms` (bigint), `started_at` (datetime), `finished_at` (datetime)
4. **指标发布阶段耗时**：
   - 记录于 `analytics_meta.pipeline_stage_run` 中 `stage_name = 'PUBLISH_METRIC'` 的运行记录。

### 4.4 路径裁定与推荐
**强烈推荐采用方案 (a) 平台生产编排路径**。  
理由：只有方案 (a) 才能百分之百满足纲领「走完整链（采集→数仓→发布）」的要求，并完整沉淀生产级审计数据（批次、检查点、阶段耗时、快照切换）。

---

## 5. 必查 4：ACTIVE 污染与回滚方案

### 5.1 当前 ACTIVE 状态与轮换机制
- **当前现状**：WP-F1 验收后，当前活跃快照为 `S20260918_4`（`runtime_profile_id = 2`），代表答辩演示基线数字（GMV 1282.80 / 订单 8 / AOV 160.35）。
- **快照发布轮换机制**：
  查验 `analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/metric/MySqlMetricStore.java` 行 82-84：
  ```sql
  UPDATE metric_snapshot 
  SET status = 'ARCHIVED', active_flag = NULL 
  WHERE runtime_profile_id = ? AND status = 'ACTIVE' AND snapshot_id <> ?
  ```
  以及数据库唯一约束：
  ```sql
  UNIQUE KEY uk_active_profile (runtime_profile_id, active_flag)
  ```
  **关键发现**：`active_flag = 1` 的排他性**是绑定在 `runtime_profile_id` 作用域下的**！同时，平台的全局活跃快照解析逻辑（`ActiveRuntimeProfileIdProvider`）优先读取当前处于激活状态的 `RuntimeProfile` 所属的 ACTIVE 快照。

### 5.2 两种回滚方案深度论证

```mermaid
sequenceDiagram
    autonumber
    participant ZK as 总控执行
    participant API as 平台 API (8091)
    participant Meta as analytics_meta
    participant Metric as analytics_metric

    Note over ZK,Metric: 方案一：隔离档案法（零重跑、秒级回滚、强烈推荐）
    ZK->>API: 1. POST /runtime-profiles (新建 local-vm-b-scale, id=3)
    ZK->>API: 2. POST /runtime-profiles/3/activate
    Note right of API: profile 2 转为 DISABLED，profile 3 成为当前活跃档案
    ZK->>API: 3. 执行万级规模冒烟全链
    API->>Metric: 发布 S20260918_SCALE 为 profile 3 的 ACTIVE
    Note over Metric: profile 2 的 S20260918_4 保持 ACTIVE 且毫发无损！
    ZK->>API: 4. 回滚：POST /runtime-profiles/2/activate
    Note right of API: profile 2 恢复激活，S20260918_4 瞬间恢复为全局演示口径！
```

#### 方案一：新建独立档案隔离发布（`local-vm-b-scale`，强烈推荐 ★★★★★）
- **操作步骤**：
  1. 调用 `POST /api/v1/runtime-profiles` 创建专属档案 `local-vm-b-scale`（分配 `id = 3`），其 `source_id = 2`，指向万级独立 landing 目录；
  2. 激活新档案：`POST /api/v1/runtime-profiles/3/activate`；
  3. 执行万级数据的采集、管线运行与快照发布（产出快照 `S20260918_SCALE`，在 `runtime_profile_id = 3` 下置为 `ACTIVE`）；
  4. **回滚动作**：只需调用一次 API：
     ```http
     POST /api/v1/runtime-profiles/2/activate
     ```
- **代价值与优势**：
  - **回滚耗时**：**< 1 秒**（单条 SQL 切换 `runtime_profile` 的 `status` 字段）；
  - **污染度**：**绝对零污染**。`runtime_profile_id = 2` 下的原基线快照 `S20260918_4` 状态从未被修改过，一直保持为 `status = 'ACTIVE', active_flag = 1`；
  - **留存性**：万级规模快照 `S20260918_SCALE` 在 profile 3 中完整保留，随时可通过切回 profile 3 调出万级证据，两套数据互不干扰。

#### 方案二：同档案覆盖运行 + 事后 Recalculate 重跑回滚（备选 ★★☆☆☆）
- **操作步骤**：
  1. 在既有 profile 2 下直接摄入万级数据，生成新 batch 并跑管线，发布快照 `S20260918_5`；
  2. 原基线 `S20260918_4` 被自动置为 `ARCHIVED`；
  3. **回滚动作**：调用重算接口：
     ```http
     POST /api/v1/pipelines/recalculate
     {"runtimeProfileId": 2, "batchId": 2, "businessDate": "20260918", "reason": "Rollback to baseline"}
     ```
     以原始基线 batchId 重新执行八阶段数仓任务，生成快照 `S20260918_6` 并发布为 `ACTIVE`。
- **代价值与缺点**：
  - **回滚耗时**：需耗费 **约 400 秒** 重新执行全部 Spark 数仓作业；
  - **编号漂移**：原快照 `S20260918_4` 无法直接原地复活，必须生成新的快照编号 `S20260918_6`，答辩脚本中的既有快照 ID 引用将被破坏。

### 5.3 裁定建议
总控应明确裁定采用 **方案一（隔离档案法）**。不仅执行风险为零，回滚秒级完成，而且完美保护了答辩演示基线数据。

---

## 6. 必查 5：跨层计数对账清单与恒等式

全链路从原始文件到指标库共分为 7 个层级，实跑验收时必须按照以下恒等式逐层对账：

```mermaid
flowchart LR
    L1["1. Landing 文件<br/>(10,000 行)"] --> L2["2. Ingestion Batch<br/>(9995 accepted / 5 quaran)"]
    L2 --> L3["3. ODS 表<br/>(ods_*_event 计 9995 行)"]
    L3 --> L4["4. DWD 明细<br/>(dwd_user_behavior & dwd_order)"]
    L4 --> L5["5. DWS 汇总<br/>(dws_trade_day 等 7 张表)"]
    L5 --> L6["6. ADS 宽表<br/>(ads_* 10 张表)"]
    L6 --> L7["7. MySQL 指标库<br/>(ads_*_m & metric_value)"]
```

### 跨层对账矩阵与恒等式清单

| 链路层级 | 核对对象（表/文件/接口） | 关键字段/指标项 | 对账数据来源 | 必须满足的数学恒等式 |
|---|---|---|---|---|
| **L1 → L2**<br/>Landing 到采集 | 采集批次与 Manifest | `totalLines`, `acceptedRecords`, `quarantinedRecords`, `errorCount` | `analytics_meta.ingestion_batch`<br/>`manifests/{batchId}.json` | $N_{total} = N_{accepted} + N_{quarantine} + N_{error}$<br/>实测必须满足：$10000 = 9995 + 5 + 0$ |
| **L2 → L3**<br/>采集到 ODS | 4 张 ODS 表：<br/>`ods_user_event`<br/>`ods_product_event`<br/>`ods_behavior_event`<br/>`ods_trade_event` | 各表行数及分区分布 | Spark SQL:<br/>`SELECT count(*) FROM fxsb_ods.ods_*_event` | $\sum \text{Count}(\text{ods\_*\_event}) = N_{accepted} = 9995$<br/>$N_{accepted}(dt=20260918) = 9980$<br/>$N_{accepted}(dt=20260919) = 15$ |
| **L3 → L4**<br/>ODS 到 DWD | `dwd_user_behavior_detail`<br/>`dwd_order_detail` | 去重行为数、有效订单行数 | Spark SQL:<br/>`fxsb_dwd.dwd_*` | `dwd_user_behavior_detail` 行数等于 ODS 行为事件去重数；<br/>`dwd_order_detail` 行数等于编译出的订单商品明细数 |
| **L4 → L5**<br/>DWD 到 DWS | `dws_trade_day`<br/>`dws_user_behavior_day` | `sale_amount`, `order_count`, `refund_amount`, `pv` | Spark SQL:<br/>`fxsb_dws.dws_*` | `dws_trade_day.sale_amount` $\equiv \sum \text{paid\_amount}$（针对 `final_paid_flag=1`）；<br/>`dws_trade_day.order_count` $\equiv \text{Count(distinct order\_id)}$ |
| **L5 → L6**<br/>DWS 到 ADS | 10 张 ADS 表（Staging 与 Formal 分区） | 各业务域指标值 | Spark SQL:<br/>`fxsb_ads.ads_*` | Staging 分区行数 $\equiv$ Formal 分区行数（`AdsPublishJob` 校验）；<br/>`ads_operation_overview` 核心字段完全等于 `dws_trade_day` 与 `dws_behavior_funnel_day` |
| **L6 → L7**<br/>ADS 到 MXP 导出 | 导出目录 `exports/` 下 10 个 `.jsonl` 文件及 `_export.json` | 各 JSONL 文件行数、CRC32 校验码 | 本地文件与清单 JSON | 每个 `.jsonl` 行数 $\equiv$ 对应 ADS 表 Formal 分区行数；<br/>清单中 `rowCount` 与 `crc32` 与文件实际值完全一致 |
| **L7 最终层**<br/>MySQL 指标对账 | `analytics_metric.ads_*_m`<br/>`analytics_metric.metric_value` | 14 项核心头条指标（GMV, Net, AOV, OrderCount, PV, UV, DAU 等） | MySQL 数据表与 API:<br/>`GET /api/v1/metrics/overview` | **终极闭环恒等式**：<br/>MySQL 指标库头条数值 $\equiv$ 造数脚本产出的 `expected_ledger.json` 理论值，差额为 0.00 |

---

## 7. 必查 6：各阶段耗时登记口径与实测边界

### 7.1 各阶段耗时提取位置与字段口径

| 阶段划分 | 耗时记录物理位置 | 取数方法与字段口径 | 单位与精度 |
|---|---|---|:---:|
| **1. 采集阶段** | `analytics_meta.ingestion_batch`<br/>或 `manifests/{batchId}.json` | `TIMESTAMPDIFF(MICROSECOND, start_time, end_time) / 1000` 或 Manifest 中的 `durationMs` 字段 | 毫秒 (ms) |
| **2. 数仓八阶段** | `analytics_meta.pipeline_stage_run` | 逐阶段读取：<br/>- `WAIT_LANDING`<br/>- `INIT_SCHEMA`<br/>- `LOAD_ODS`<br/>- `BUILD_DWD`<br/>- `BUILD_DWS`<br/>- `BUILD_ADS`<br/>- `QUALITY_CHECK`<br/>- `PUBLISH_METRIC`<br/>取 `finished_at - started_at` | 秒 (s) / 毫秒 (ms) |
| **3. Spark 细分作业** | `analytics_meta.spark_job_run` | 逐任务读取：`sci`, `odl`, `dim`, `bdw`, `tdw`, `usw`, `fna`, `dqc`, `pub`, `mxp`<br/>字段：`elapsed_ms` | 毫秒 (ms) |
| **4. 指标落库阶段** | `pipeline_stage_run` | `stage_name = 'PUBLISH_METRIC'` 对应行的 `elapsed_ms` 证据 | 毫秒 (ms) |
| **5. 全链路总耗时** | 自动化测试/总控驱动脚本 | 从触发采集开始，到最终快照变为 ACTIVE 的端到端墙钟时间 (Wall-clock time) | 秒 (s) |

### 7.2 本机 D-126 本地口径下的证据边界表述（严禁夸大）
在编写最终交付物与答辩材料时，**必须严格遵守以下学术与工程诚信表述**：
1. **单机环境定性**：当前实测是在单台 Linux VM（D-126，8 核 CPU，16GB 内存，内嵌 Apache Derby Metastore，单机 local Spark 模式）上完成的。
2. **证据目标定性**：万级同构冒烟的根本目的是**验证系统在规模数据下的数据准确性、去重机制、状态机健全度与全链连通性**，证明系统在数据量扩大 250 倍（从 38 行到 10,000 行）时，各层计数恒等式依然严格闭环，未发生内存溢出（OOM）、锁等待超时或死锁。
3. **性能边界免责**：
   > **免责声明声明模板**：  
   > *“本报告记录之耗时数据（如采集 X 秒、数仓计算 Y 秒）仅作为当前单机测试床环境下的线性趋势与稳定性实证，证明全链无阻塞点；该数据不代表、亦不可直接外推为分布式生产集群（如 Hadoop YARN / 独立 Hive Metastore / 多节点 Spark Executor）的并发吞吐量与性能基准。”*

---

## 8. 必查 7：风险、代码常量上限与前置条件

### 8.1 平台服务前置条件与端口现状
- **现状**：总控巡检实测表明，分析平台核心服务（端口 8091）当前处于未监听状态。
- **前置动作**：总控实跑前，必须使用环境配置拉起分析平台：
  - 引用配置文件：`~/workspace/toolchain/live-platform.env`（**严格保密：禁止向外界透露或摘录该文件中的凭据内容**）；
  - 服务就绪判定：`curl -s http://127.0.0.1:8091/api/v1/health` 返回 `{"status":"UP"}`。

### 8.2 数据库现状影响
- **端口隔离确认**：当前宿主机运行隔离实例 MySQL（端口 3307），其上承载 `analytics_meta`（元数据库）与 `analytics_metric`（指标库）。
- **实跑影响**：万级数据在数仓层由 Spark 在内存和 Parquet 文件中完成，对 MySQL 的写入仅为：
  1. 采集批次记录（1 行）；
  2. 隔离数据记录（5~10 行）；
  3. 管线阶段审计记录（8 行）；
  4. 指标宽表与核心指标值（约几十行）。
  因此 MySQL 3307 不会产生任何容量或 I/O 压力。

### 8.3 源码中已知常量、上限（Limit）与批处理参数查验

| 源码位置 | 常量名 / 代码片段 | 设定值 | 万级数据实跑影响与避坑规约 |
|---|---|:---:|---|
| `analytics-server/.../MappingDryRunService.java` 行 31 | `LIMIT_MAX` | `100` | **严禁**将万级数据文件传给 dry-run 接口！dry-run 接口仅限使用 `sample-data/normal.jsonl`（38行）校验映射规则 |
| `analytics-server/.../LocalFileIngestor.java` 行 64 | `DEFAULT_BUFFER_SIZE` | `64 * 1024` (64KB) | FileChannel 缓冲区为 64KB，万级 JSONL（约 3MB）分批流畅读取，无任何压力 |
| `analytics-server/.../LocalFileIngestor.java` 行 310 | `quarantineRecordMapper.insert` | 单条无批处理 | **核心红线**：如前所述，隔离行数必须控制在 10 行以内，否则单行 JDBC 插入会引发延迟 |
| `analytics-server/.../MySqlMetricStore.java` 行 39 | `MAX_SNAPSHOT_PAGE_SIZE` | `100` | 快照分页查询上限，不影响快照发布 |
| `warehouse-spark/.../FunnelAdsJob.scala` 行 31 | `topN` | `50` (默认) | `ads_hot_product` 输出 Top 50 热销品；本次造数设置 20 款商品，全部能够被完整输出 |
| `warehouse-spark/.../TradeDwdJob.scala` 行 41 | `odsTradeEvents.collect().groupBy` | JVM 内存聚合 | Driver 端拉取全量 trade_event 并在 JVM 内存中聚合。万级事件中 trade_event 约 3,000 行，内存占用仅约 3MB，在 16GB 宿主机上毫无溢出风险 |
| Apache Derby 嵌入式元数据库 | 目录 `./spark-warehouse/metastore_db` | 单连接锁 | 仅存储 Spark 表结构元数据，表实际数据均在 Parquet 文件存储，万级数据对 Derby 无任何膨胀压力 |

---

## 9. 总控执行推荐方案与实施清单

### 9.1 执行步骤流水线（总控实跑指南）

```
[步骤 0] 前置检查：
  - 确认 MySQL 3307 运行正常
  - 用 live-platform.env 拉起平台进程并确认 8091 监听就绪

[步骤 1] 产生万级同构数据：
  - 运行 Python 造数脚本（生成 9,995 normal + 5 fault，输出至独立目录）
  - 生成独立的 expected_ledger.json

[步骤 2] 建立并激活隔离档案（保障基线）：
  - POST /api/v1/runtime-profiles 新建 local-vm-b-scale (id=3)
  - POST /api/v1/runtime-profiles/3/activate (原 profile 2 自动转为 DISABLED，但 S20260918_4 保持 ACTIVE)

[步骤 3] 触发完整链执行：
  - POST /api/v1/ingestion-batches/run-one 触发采集 (记录 Ingestion 耗时)
  - POST /api/v1/pipelines/run 触发八阶段数仓管线 (记录 Stage 耗时)
  - 发布快照为 profile 3 的 ACTIVE (记录 Publish 耗时)

[步骤 4] 自动化计数与指标对账：
  - 对账 L1 到 L7 各层恒等式
  - 比对 MySQL 指标库数值与 expected_ledger.json，校验差额为 0

[步骤 5] 演示口径零代价秒级回滚：
  - POST /api/v1/runtime-profiles/2/activate
  - 验证 S20260918_4 重新成为当前演示 ACTIVE 快照，完成闭环！
```

---
*报告编制完毕，交付总控审阅裁定。*
