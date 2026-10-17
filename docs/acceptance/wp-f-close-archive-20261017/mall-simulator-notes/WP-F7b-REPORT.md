# WP-F7b 实施报告：万级 B 源确定性造数方案与交付物说明

> **报告属性**：WP-F7b 实施包文档交付物  
> **执行模式**：agy 纯文件读写交付（不运行命令、不连数据库、不改仓库任何文件）  
> **代码交付物**：`~/workspace/projects/mall-simulator-notes/wpf7-scale/generate_scale_b.py`  
> **文档交付物**：`~/workspace/projects/mall-simulator-notes/WP-F7b-REPORT.md`  

---

## 1. 概述与核心原则

依据《WP-F7b 规约：万级 B 源造数脚本》，本项目为分析平台 B 源（`fixture-shop-b`）构建确定性万级规模冒烟造数脚本及元数据期望账本。

### 1.1 核心约束贯彻
1. **纯标准库与确定性**：仅依赖 Python 3 标准库（`json`, `hashlib`, `random`, `sys`, `os`, `collections`）；固定随机种子 `random.Random(42)`；输出前执行 `(occur_at, evt_no)` 复合键稳定排序，采用紧凑分隔符 `separators=(',', ':')` 序列化，保证同一脚本在任意 Linux 环境重复运行产出**逐字节一致**。
2. **单业务日闭环**：全部 10,000 行事件严格落在同一业务日 **`2026-09-18 08:00:00+08:00` 至 `23:59:59+08:00`** 内，不掺跨日行，确保跨层计数与金额恒等式干净闭环。
3. **期望账实时精算**：`expected_ledger.json` 中各项指标（PV、订单数、GMV、退款额、净额、客单价等）均由脚本自生成数据实时聚合统计并写入，严禁硬编码静态数值。
4. **强校验自检守门**：脚本内置 11 项完备断言，覆盖行数、分布、订单生命周期、金额算术、时间偏序与全局唯一性，任一失败以非零退出并打印失败项。

---

## 2. 精确事件分布表（合计 10,000 行）

全量数据由 **9,995 行正常事件** 与 **5 行典型隔离事件** 构成，严格符合规约规定的精确配比：

| 事件类型 (`state`) | 归一化目标规范事件 | 行数 | 业务含义与池复用策略 |
|---|---|:---:|---|
| **MEMBER_JOINED** | `user_registered` | **100** | 买家池 100 位用户（包含夹具 4 位买家及 96 位 19 位数字规则买家，每人 1 行） |
| **ITEM_LISTED** | `product_created` | **20** | 商品池 20 款 SKU（`1001`..`1020`，含夹具 3 款商品，开市前 08:16-08:26 集中上架） |
| **PAGE_VIEW** | `behavior` | **5,000** | 页面浏览行为（`act="BROWSE"`，`sess_no` 模拟 1~20 号会话，`via` 覆盖 APP/PC/H5） |
| **SAVE_ITEM** | `behavior` | **875** | 商品收藏行为（`act="SAVE"`） |
| **CART_ADD** | `behavior` | **1,000** | 加入购物车行为（`act="ADD_CART"`） |
| **ORDER_PLACED** | `order_created` | **1,200** | 订单创建（1~2 个商品行，每单独立 19 位 `trade_no`，覆盖 100 位买家） |
| **PAID_OK** | `order_paid` | **1,000** | 订单支付成功（`paid_minor == grand_minor`，独立 19 位 `pay_no`） |
| **BUYER_CANCELLED** | `order_cancelled` | **200** | 未支付订单买家主动取消（`cancel_reason="BUYER_CHANGED_MIND"`） |
| **REFUND_OPENED** | `refund_created` | **300** | 发起退款（含 200 笔全额退款 + 100 笔部分退款，独立 19 位 `refund_no`） |
| **REFUND_CLOSED** | `refund_completed` | **300** | 退款结算完结（与 `REFUND_OPENED` 严格同 `refund_no` 与 `refund_minor`） |
| **正常行小计** | — | **9,995** | 100 + 20 + 6,875 (行为) + 3,000 (订单链) = 9,995 |
| **隔离行 (Fault)** | — | **5** | 复刻 `fault.jsonl` 的 5 种典型错误形态 |
| **总数据行数** | — | **10,000** | **9,995 + 5 = 10,000** |

### 2.1 订单队列构成（1,200 笔订单精确构成）
- **700 笔已支付无退款单**：`ORDER_PLACED` + `PAID_OK`（共 1,400 行）；
- **200 笔未支付取消单**：`ORDER_PLACED` + `BUYER_CANCELLED`（共 400 行）；
- **100 笔已支付部分退款单**：`ORDER_PLACED` + `PAID_OK` + `REFUND_OPENED` + `REFUND_CLOSED`（`0 < refund_minor < paid_minor`，共 400 行）；
- **200 笔已支付全额退款单**：`ORDER_PLACED` + `PAID_OK` + `REFUND_OPENED` + `REFUND_CLOSED`（`refund_minor == paid_minor`，共 800 行）。
- **订单链行数恒等式**：1,400 + 400 + 400 + 800 = **3,000 行**。

### 2.2 隔离行形态复刻表（5 行）
编号接续正常行（正常行截止至 `B-EVT-19995` / `B-TR-19995`）：

| 序号 | `evt_no` | `tr` | 错误形态描述 | 错误注入字段与实际取值 | 预期隔离原因 |
|:---:|---|---|---|---|---|
| F1 | `B-EVT-19996` | `B-TR-19996` | 未登记的事件类型 | `"state": "LEAVE_REVIEW"` | 未映射源事件，画像枚举拒绝 |
| F2 | `B-EVT-19997` | `B-TR-19997` | FEN 金额非整数 | `"paid_minor": 5980.5` | `FEN_NOT_INTEGRAL` 策略违规 |
| F3 | `B-EVT-19998` | `B-TR-19998` | 非法时间格式 | `"occur_at": "2026/09/18 15:02:00"` | 无法匹配 ISO_OFFSET 声明格式 |
| F4 | *(缺字段)* | `B-TR-19999` | 缺失信封必填字段 | 缺失 `"evt_no"` 键 | `EMPTY_FIELD: event_id` 契约拒绝 |
| F5 | `B-EVT-20000` | `B-TR-20000` | Schema 版本不符 | `"rev": "2.0"` | 源画像限定版本为 1.0 |

---

## 3. 字段格式与夹具来源对照

脚本完全严格对照 `fixtures/source-b/fixture-shop-b/normal.jsonl` 与 `fault.jsonl` 的实际字段形态构建：

### 3.1 信封结构（Envelope）
- `evt_no`: 事件全局唯一流水号，正常行 `B-EVT-10001` 至 `B-EVT-19995`，隔离行接续 `B-EVT-19996`..`20000`（F4 除外）；
- `state`: 原始状态枚举，见分布表；
- `occur_at`: 事件发生时刻，标准 `2026-09-18T%H:%M:%S+08:00` 格式（符合 `ISO_OFFSET_DATE_TIME`）；
- `src`: 固定为 `"fixture-shop-b"`；
- `rev`: 固定为 `"1.0"`（F5 故意置为 `"2.0"` 触发隔离）；
- `tr`: 追踪链路 ID，`B-TR-10001` 至 `B-TR-20000` 全局唯一递增。

### 3.2 业务数据载荷（Data Payload）与实体约束
- **买家标识 (`buyer_no`)**：19 位纯数字字符串，100 人买家池循环复用；
- **交易统领键 (`trade_no`)**：19 位纯数字字符串（如 `2100918000000000001`），按单全局唯一；
- **支付流水号 (`pay_no`)**：19 位纯数字字符串（如 `2100918100000000001`），已支付订单一一对应全局唯一；
- **退款流水号 (`refund_no`)**：19 位纯数字字符串（如 `2100918200000000001`），串联同一笔退款的 `REFUND_OPENED` 与 `REFUND_CLOSED`；
- **商品标识 (`sku_no`)**：`"1001"` 至 `"1020"` 字符串，所有行为行与订单行所引用的商品必须已在 `ITEM_LISTED` 中上架；
- **金额单位 (`*_minor`)**：全部为无符号整型分（FEN），`unit_minor` 严格等于对应商品的 `price_minor`；优惠金额 `line_disc_minor` 必须为小于行总额的非负整数；`line_minor = qty * unit_minor - line_disc_minor`；`grand_minor = sum(line_minor)`。

---

## 4. 期望账（Expected Ledger）计算口径

`expected_ledger.json` 由脚本在内存汇总后直接计算输出，各字段口径定义如下：

```json
{
  "total_lines": 10000,
  "accepted_count": 9995,
  "quarantine_count": 5,
  "per_state_counts": {
    "MEMBER_JOINED": 100,
    "ITEM_LISTED": 20,
    "PAGE_VIEW": 5000,
    "SAVE_ITEM": 875,
    "CART_ADD": 1000,
    "ORDER_PLACED": 1200,
    "PAID_OK": 1000,
    "BUYER_CANCELLED": 200,
    "REFUND_OPENED": 300,
    "REFUND_CLOSED": 300
  },
  "accepted_per_state_counts": { ... },
  "quarantine_per_state_counts": {
    "LEAVE_REVIEW": 1,
    "PAID_OK": 1,
    "PAGE_VIEW": 1,
    "MEMBER_JOINED": 1,
    "ITEM_LISTED": 1
  },
  "raw_per_state_counts": { ... },
  "created_orders": 1200,
  "paid_orders": 1000,
  "cancelled_orders": 200,
  "gmv_yuan": "<实时计算：Σ paid_minor / 100，保留2位小数字符串>",
  "refund_completed_yuan": "<实时计算：Σ refund_minor / 100 (针对 REFUND_CLOSED)，保留2位小数字符串>",
  "net_yuan": "<实时计算：(Σ paid_minor - Σ refund_minor) / 100，保留2位小数字符串>",
  "aov_yuan": "<实时计算：gmv / paid_orders，保留2位小数字符串>",
  "pv": 5000,
  "fav_count": 875,
  "cart_add_count": 1000,
  "distinct_behavior_buyers": 100,
  "distinct_paid_buyers": 100,
  "scale_events_sha256": "<写入 scale_events.jsonl 后即时计算所得 SHA256 哈希>",
  "business_date": "20260918",
  "error_count": 0
}
```

---

## 5. 脚本自检清单（11 项强保证断言）

`generate_scale_b.py` 在落盘前执行严格的全量业务规则与数据结构自检，任一不符立即阻断退出：

1. **总行数与分组恒等式**：$N_{total} = 10,000$，$N_{accepted} = 9,995$，$N_{quarantine} = 5$；
2. **事件分布精确匹配**：验证 10 种正常事件的行数与规约完全一致；
3. **订单总量与队列类型构成**：验证 1,200 笔订单中恰好包含 700 笔已支付无退款、200 笔未支付取消、100 笔已支付部分退款、200 笔已支付全额退款；
4. **商品明细与价格一致性**：每单 1~2 个商品行，引用的 `sku_no` 必须存在于已上架列表，`unit_minor` 必须等于该商品标价；
5. **金额算术恒等式**：
   - 每行：$line\_minor = qty \times unit\_minor - line\_disc\_minor$（$0 \le line\_disc\_minor < qty \times unit\_minor$）；
   - 整单：$grand\_minor = \sum line\_minor$；
   - 支付：$paid\_minor = grand\_minor$；
   - 退款：全额退款 $refund\_minor = paid\_minor$，部分退款 $0 < refund\_minor < paid\_minor$；
   - 退款事件对齐：同一订单的 `REFUND_OPENED` 与 `REFUND_CLOSED` 必须具有完全相同的 `refund_no` 与 `refund_minor`；
6. **订单生命周期时间偏序**：
   - 支付单：$placed\_tm < paid\_tm$；
   - 退款单：$paid\_tm < opened\_tm < settled\_tm$；
   - 取消单：$placed\_tm < cancelled\_tm$；
7. **全局业务时间窗**：所有正常事件严格落在 `2026-09-18 08:00:00` 至 `23:59:59` 内；
8. **排序整体不递减**：经过 `(occur_at, evt_no)` 排序后，相邻事件满足 $occur\_at_i \le occur\_at_{i+1}$；
9. **全局唯一性断言**：
   - `evt_no` 全局唯一（9,999 个，除 F4 缺失行）；
   - `tr` 全局唯一（10,000 个全部独立）；
   - `trade_no`、`pay_no`、`refund_no` 全局唯一；
10. **隔离行错误形态特异性断言**：
    - F1 必须为 `state="LEAVE_REVIEW"`；
    - F2 必须为浮点金额（`paid_minor=5980.5`）；
    - F3 必须为斜杠时间格式（`2026/09/18 15:02:00`）；
    - F4 必须缺失 `evt_no` 字段；
    - F5 必须为不支持的协议版本（`rev="2.0"`）；
11. **产出制品校验**：`scale_events_sha256` 必须与生成的 `scale_events.jsonl` 文件哈希完全吻合。

---

## 6. 验收运行指南（由总控在验收轮亲跑）

```bash
# 运行确定性造数脚本（输出至指定目录，例如 output/）
python3 ~/workspace/projects/mall-simulator-notes/wpf7-scale/generate_scale_b.py ~/workspace/projects/mall-simulator-notes/wpf7-scale/output

# 预期 stdout 终端输出示例：
# SCALE-GEN-OK
# total_lines=10000 accepted=9995 quarantine=5
# orders: created=1200 paid=1000 cancelled=200 refunds_closed=300
# behaviors: pv=5000 fav=875 cart_add=1000
# financials: gmv=... refund_completed=... net=... aov=...
# buyers: distinct_behavior=100 distinct_paid=100
# scale_events_sha256=...
```

*交付完毕，提交总控验收。*
