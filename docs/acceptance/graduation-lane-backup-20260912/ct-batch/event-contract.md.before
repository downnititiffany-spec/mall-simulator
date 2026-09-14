# 事件契约（Event Contract）— schema_version 1.0

> 依据：项目设计文稿 V2.2 §5.2.4、§20.1、§24.1。本文档是首版采集链路的唯一事件定义来源。
> 变更必须升版本：新字段 → `schema_version=1.1` 起；破坏性变更 → 新主版本并增加转换器。

## 1. 统一事件信封

所有事件（商城人工操作或自动生成器产生）序列化为 JSON 单行（JSON Lines），经事务 Outbox 写入滚动日志，由 Flume 采集到 Landing，进入 ODS。

```json
{
  "event_id": "UUID",                        // 全局唯一，ODS/DWD 按此去重（允许 at-least-once 投递）
  "event_type": "order_paid",                // 见 §3 事件类型枚举
  "event_time": "2026-09-05T20:15:31+08:00", // 业务时间（带时区），指标归属依据
  "ingest_time": "2026-09-05T20:15:32+08:00",// 该行的采集时间（Java 侧生成），链路延迟 = ingest_time - event_time
  "source_system": "mock-mall",              // 固定值：mock-mall
  "schema_version": "1.0",                   // 未知版本 → 进入隔离区（quarantine_record），不进入 DWD
  "trace_id": "UUID",                        // 一次业务操作一个 trace_id，贯穿 商城→Outbox→日志→批次→ODS
  "payload": {}
}
```

协议约定：

* 时间一律 ISO-8601 带时区（业务统一 Asia/Shanghai，`+08:00`）；解析失败视为脏数据。
* 金额一律十进制字符串（保持精度，禁止 double 序列化），DB 使用 `DECIMAL(18,2)`。
* 所有 ID（user_id/order_id/product_id 等）为数值字符串；`event_id`/`trace_id` 为 UUID 字符串。
* payload 中业务字段以 §2 中每类事件的字段为准，多出的未知字段不阻止消费（向后兼容），缺失必需字段视为脏数据。

## 2. 事件类型与 payload 字段

统一约定（通用字段，全部事件必填）：

| 字段 | 类型 | 说明 |
|---|---|---|
| user_id（如适用） | string | 用户 ID |
| product_id（如适用） | string | 商品 ID |

### 2.1 user_registered — 用户注册

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| user_id | string | 是 | 用户 ID |
| age_group | string | 是 | 年龄段：`under18/18-24/25-34/35-44/45+` |
| city_level | string | 是 | 城市等级：`tier1/tier2/tier3/other` |
| member_level | string | 是 | 会员等级：`normal/silver/gold/platinum` |
| register_time | string | 是 | 注册时间（ISO-8601） |

### 2.2 product_created / product_updated — 商品建档与变更

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| product_id | string | 是 | 商品 ID |
| product_name | string | 是 | 商品名称 |
| category_id | string | 是 | 末级分类 ID |
| brand_id | string | 是 | 品牌 ID |
| price | string | 是 | 售价（decimal 字符串），price ≥ cost |
| cost | string | 是 | 成本价，留作库存周转等口径 |
| status | string | 是 | `on_sale/off_sale/pending` |

### 2.3 behavior — 用户行为

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| user_id | string | 是 | 用户 ID |
| product_id | string | 是 | 商品 ID（search 事件也指向被检索/点击的商品） |
| session_id | string | 是 | 会话 ID |
| behavior_type | string | 是 | `view/favorite/cart_add/cart_remove/search`（枚举） |
| channel | string | 是 | 渠道：`app/pc/h5` |

### 2.4 order_created — 订单创建

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| order_id | string | 是 | 订单 ID |
| user_id | string | 是 | 用户 ID |
| items | array | 是 | 订单项数组，见下 |
| total_amount | string | 是 | 订单总额 = Σ item.amount（decimal） |
| status | string | 是 | 固定 `CREATED` |
| created_at | string | 是 | 下单时间 |

订单项（items[]）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| product_id | string | 是 | 商品 ID |
| quantity | number | 是 | 数量（正整数） |
| unit_price | string | 是 | 成交单价 |
| discount | string | 是 | 优惠分摊（默认 "0.00"） |
| amount | string | 是 | = quantity × unit_price − discount |

### 2.5 order_paid — 支付成功

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| order_id | string | 是 | 订单 ID |
| user_id | string | 是 | 用户 ID |
| payment_id | string | 是 | 支付流水 ID |
| amount | string | 是 | 支付金额（= 订单总额） |
| paid_at | string | 是 | 支付时间 |

### 2.6 order_cancelled — 订单取消

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| order_id | string | 是 | 订单 ID |
| user_id | string | 是 | 用户 ID |
| reason | string | 是 | 取消原因（code 或文本） |
| cancelled_at | string | 是 | 取消时间 |

### 2.7 refund_created — 退款申请

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| refund_id | string | 是 | 退款单 ID |
| order_id | string | 是 | 订单 ID |
| user_id | string | 是 | 用户 ID |
| amount | string | 是 | 申请退款金额 ≤ 已付金额 |
| reason | string | 是 | 退款原因 |
| created_at | string | 是 | 申请时间 |

### 2.8 refund_completed — 退款完成

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| refund_id | string | 是 | 退款单 ID |
| order_id | string | 是 | 订单 ID |
| user_id | string | 是 | 用户 ID |
| amount | string | 是 | 实际退款金额 |
| completed_at | string | 是 | 完成时间 |

### 2.9 stock_reserved / stock_released — 库存预留与释放

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| product_id | string | 是 | 商品 ID |
| quantity | number | 是 | 数量（正整数） |
| order_id | string | 条件 | 关联订单（预留时必填，释放时如有） |
| reserved_qty / available_qty | string | 是 | 操作后快照（decimal 字符串） |

### 2.10 stock_changed — 库存变动（入库/出库/调整）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| product_id | string | 是 | 商品 ID |
| change_type | string | 是 | `inbound/outbound/adjust` |
| quantity | number | 是 | 变动数量（正数） |
| available_qty | string | 是 | 变动后可用库存快照 |

## 3. 事件类型枚举（event_type 取值全集）

```text
user_registered  product_created  product_updated
behavior  (behavior_type: view | favorite | cart_add | cart_remove | search)
order_created  order_paid  order_cancelled
refund_created  refund_completed
stock_reserved  stock_released  stock_changed
```

合计 **12 类**事件。未知 event_type → 隔离（quarantine），不进入 DWD。

## 4. 一致性约束（验收依据）

* 同一业务事务内写入的 outbox 事件必须与业务表同时提交或同时回滚（同库同事务）。
* `event_id` 唯一；重复投递由 DWD 按 `event_id` 去重，因此允许 at-least-once。
* `order_paid.amount` 必须等于该订单 `order_created.total_amount`。
* `refund_completed.amount` 不得大于对应订单已支付金额。
* 金额字段（total_amount/amount/unit_price/discount/price/cost/available_qty/reserved_qty）必须是 `^\d+(\.\d{1,2})?$` 的十进制字符串。
* 每个事件必须同时包含 event_time 与 ingest_time；业务统计按 event_time，链路延迟按 ingest_time。