# G31-02 · 来源 B（fixture-shop-b）独立预期值（Oracle）

> 本文件是 **02.1 任务交付物**：全部预期值由人工从已读源码（`OrderTradeCompiler` / `DwsSql` / `AdsSql` /
> `TradeDwdJob` / `MappingExecutor` / 契约 schema）推导并**手工计算**，不调用任何待测计算器。
> 运行期校验（02.3/02.4）逐项对照本文件；任何不符先怀疑实现，再怀疑推导，并在结果卷记录。

## 1. 夹具文件身份

| 文件 | 行数 | SHA256 |
|------|-----|--------|
| `normal.jsonl` | 38 | `783a7609a8a315d7c2258d83a817edfaab629f5dc6f131ca26da5a58704ffb7e` |
| `fault.jsonl` | 5 | `626a76171f7ccad598e762c41bec532a9bf65a20d1dd2fddc1a77bcfb0a37216` |
| 画像 `fixture-shop-b.v2.json` | — | 激活时以 dry-run/activate 报告中的 profileChecksum 为准（三方向校验） |

夹具事实（脚本独立复核）：38 个 `evt_no` 全局唯一；8 订单均满足 Σ行金额=grand_minor=paid_minor（或取消无支付）；
ODS 归一化分布 dt=20260918 × 36 行、dt=20260919 × 2 行（B-EVT-0035 跨日支付、B-EVT-0036 次日浏览）。

## 2. 与来源 A 的关系（指导书 §6 约束）

- **复用 A 的原始 ID**：用户 `2100917055761973250 / 2100917057120927745 / 2100917057850736642 / 2100917056193986562`，
  商品 `1001/1002/1003`，订单 `2100917058…`（8 个），退款 `2100917060316987394 / 2100917059050307585`。
- **跨源同 event_id**：B-EVT-0037/0038 的 `evt_no` 故意取 A 金样本的
  `95d20dd2-b564-4dcd-9076-32e0cb295654`（A 中为 order_created）与
  `c6556173-4a50-4ed7-8fa6-3f166e5fc77a`（A 中为 stock_changed）。
  预期：`(source_system, event_id)` 复合键下 `dw_ods`（mock-mall）与 `fxsb_ods`（fixture-shop-b）
  各自保留一行，事件类型不同、互不淘汰。
- **表达差异**（指导书要求字段名/枚举/金额/时间各≥1 处不同）：字段名 `evt_no/state/occur_at/paid_minor`；
  枚举 `BROWSE→view` 等；金额单位 **分（FEN 整数）** vs A 的十进制字符串；时间 `Z` 偏移 + 无小数秒 vs A 的
  7 位小数秒。
- A 金样本**不含** product_created / behavior 事件；B 补齐这两类，使 8 类事件全映射面被覆盖。

## 3. 逐事件映射预期（抽样级）

| 行 | 原始 | 规范化结果要点 |
|----|------|----------------|
| 0001-0004 | MEMBER_JOINED | user_registered；age_group 35-44/25-34/18-24/45+；city tier3/tier1/tier2/tier3；member silver/gold/normal/platinum |
| 0005-0007 | ITEM_LISTED | product_created；price 29.90/59.80/298.00；status 原样 on_sale |
| 0008-0015 | PAGE_VIEW/SAVE_ITEM/CART_ADD | behavior；behavior_type view/favorite/cart_add；channel app/pc/h5 |
| 0016/0018/… | ORDER_PLACED | order_created；items[].amount "59.80" 等（FEN→2 位小数）；status CREATED |
| 0019 | PAID_OK @`03:12:00Z` | event_time 归一为 `2026-09-18T11:12:00+08:00`（时刻不变、墙钟转东八区、无小数秒）；payload.paid_at 原样透传 `…Z` |
| 0034/0035 | O8 创建 23:59:59 / 支付次日 00:00:30 | 订单归 dt=20260918（created 日），paid_amount 298.00 计入 D 日聚合（TradeDwdJob 全量 ODS 编译） |
| 0022+0023 | 部分退款 100.00 | O3 状态 REFUNDING、final_refunded_flag=0 |
| 0026+0027 | 全额退款 298.00 | O4 状态 REFUNDED、final_refunded_flag=1 |
| 0028+0029 | 取消未支付 | O5 状态 CANCELLED、final_paid_flag=0 |

## 4. D 日（2026-09-18）人工汇总预期

### 4.1 ODS（fxsb_ods.ods_trade_event）
- dt=20260918 分区 **36 行**；dt=20260919 分区 **2 行**；source_system 全部 = `fixture-shop-b`。

### 4.2 DWD（fxsb_dwd）
- `dwd_user_behavior_detail` dt=20260918：**10 行**（0008-0015 + 0037 + 0038）；去重键 (source_system,event_id)。
- `dwd_order_detail` dt=20260918：**9 行**（8 订单、O7 两行商品）；final_paid_flag=1 的行 8 行（O5 除外）。

### 4.3 DWS（fxsb_dws，dt=20260918）
| 表 | 预期 |
|----|------|
| dws_trade_day | order_count=7，buyer_count=4，sale_amount=1222.90，refund_amount=398.00，net_sale_amount=824.90，avg_order_value=174.70 |
| dws_behavior_funnel_day | view_users=4，intent_users=2，cart_users=1，order_users=4，pay_users=4；intent_rate=0.5000，order_rate=2.0000，pay_rate=1.0000，overall_buy_rate=1.0000，cart_rate=0.2500 |
| dws_user_behavior_day | U1: pv3/fav0/cart0/hours2/buy2；U2: pv3/fav0/cart1/hours1/buy3；U3: pv1/fav1/cart0/hours1/buy3；U4: pv1/fav0/cart0/hours1/buy2 |
| dws_product_behavior_day | 1001: pv3/uv2/fav1/cart0/buy3；1002: pv3/uv2/fav0/cart1/buy3；1003: pv2/uv2/fav0/cart0/buy3 |
| dws_product_sale_day | 1001: qty3/8970.00/买家2；1002: qty3/17940.00/买家1；1003: qty3/89400.00/买家2 |
| dws_user_trade_period | U1: 1单/59.80/valid1；U2: 2单/179.40/valid2；U3: 2单/387.70/valid2；U4: 2单/596.00/valid1（O4 全退剔除） |
| dws_region_sale_day | tier1: 买家1/单2/179.40/179.40；tier2: 买家1/单2/387.70/287.70；tier3: 买家2/单3/655.80/357.80 |

金额算术：sale = 59.80+59.80+298.00+298.00+119.60+89.70+298.00 = **1222.90**；refund = 100.00+298.00 = **398.00**。

### 4.4 ADS（8 张，staging 与正式同值）
| 表 | 预期 |
|----|------|
| ads_operation_overview | pv=8，uv=4，dau=4，order_count=7，sale=1222.90，net=824.90，aov=174.70；refund_rate=2/7≈0.285714；full_refund_rate=1/7≈0.142857；repeat_rate=2/4=0.5000；repeat_period=2026-09-18~2026-09-18；fav_cnt=1，cart_add_cnt=1 |
| ads_active_trend | dau=4，behavior_count=10 |
| ads_behavior_funnel | 4 stage 行 view/intent/order/pay = 4/2/4/4，rate NULL/0.5000/2.0000/1.0000；各行 overall_buy_rate=1.0000、overall_cart_rate=0.2500 |
| ads_hot_product（topN≥3） | rank1=1002（heat=ln4+3·ln2+5·ln4≈10.3972）> rank2=1001（ln4+2·ln2+5·ln4≈9.7041）> rank3=1003（ln3+5·ln4≈8.0301）；名称取自 fxsb dim_product（非 UNKNOWN）；rule_version=v1 |
| ads_product_conversion | 1001: pv_users2/buy_users2/rate1.0；1002: 2/1/0.5；1003: 2/2/1.0 |
| ads_sale_trend | 1 行：order7/buyer4/sale1222.90/aov174.70/net824.90 |
| ads_user_profile | U1: r_ntile1→r=5,f1,m1→一般发展，新用户，fav_cat=11，active 高；U2: r_ntile3→r=3,f3,m2→一般挽留，活跃，fav_cat=22；U3: r_ntile4→r=2,f4,m3→一般保持，活跃，fav_cat=11（1:1 平局 category_id ASC）；U4: r_ntile2→r=4,f2,m4→重要发展，活跃，fav_cat=22；rule_version=rfm-v2 |
| ads_data_quality | AMOUNT_RECONCILE check=7/err0/passed1；REQUIRED_FIELD_NULL_RATE check=10/err0/passed1；EVENT_ID_UNIQUE check=10/err0/passed1；ENUM_WHITELIST check=10/err0/passed1；rule_version=1 |

RFM 排序键复核（4 行 NTILE(5) → 桶号 1..4）：
- r_ntile：DATEDIFF(pe,last_buy) 全 0 → 按 user_id 字符串升序 U1(…5576…) < U4(…5619…) < U2(…5712…) < U3(…5785…)。
- f_ntile：order_count ASC, user_id ASC → U1(1), U4(2), U2(2), U3(2)。
- m_ntile：sale_amount ASC → U1(59.80), U2(179.40), U3(387.70), U4(596.00)。
- value_group CASE 用 **r_ntile** 原值（非反转 r）：U1 m1<4,r1≤2,f1<4→一般发展；U2 m2<4,r3>2,f3<4→一般挽留；
  U3 m3<4,r4>2,f4≥4→一般保持；U4 m4≥4,r2≤2,f2<4→重要发展。

### 4.5 质量门 / 发布
- `ADS_STAGING_PRESENT` 8/8（8 张 ADS 暂存分区存在且 Location 可读）。
- 阻塞规则集 passed（无 0 行假失败来源：D 日有真实行为与订单行）。

## 5. fault.jsonl 预期（02.2 隔离区）

| 行 | 违规 | 预期处置 |
|----|------|----------|
| F001 | state=LEAVE_REVIEW 未登记 | 未映射事件类型 → 隔离（violation 记录枚举/映射失败，记实际码） |
| F002 | paid_minor=5980.5 | FEN 非整数 → 隔离（FEN_NOT_INTEGRAL 族） |
| F003 | occur_at=`2026/09/18 15:02:00` | 无匹配声明格式 → 隔离（BAD_TIME_FORMAT / NO_DECLARED_FORMAT_MATCHED） |
| F004 | 缺 evt_no | 必填 envelope 缺失 → 隔离（EMPTY_FIELD: event_id） |
| F005 | rev=2.0 | schema_version≠1.0 → 隔离（版本不支持） |

预期：5 行全 quarantine、0 accepted；normal+fault 合跑时 processed=43、invariant processed=accepted+quarantined+systemErrors 成立。

## 6. A-B-A 回程锚点（02.4）

- Phase A（mock-mall 金样本，快照 SA1）：80 支付订单 / 35 买家 / sale 15112.10 / net 11859.40 / AOV 188.90；
  漏斗下单 49→支付 35（0.7143）；RFM 8 段合计 35；PV/UV/DAU=0。
- Phase B 后 `fxsb_*` 六库存在且为 B 预期；`dw_*` 库 A 数据不变（仅 mock-mall 行）。
- Phase A 回程（SA2）：锚点值必须**逐项等于 SA1**；fxsb 库留存但 ACTIVE 当前源为 mock-mall；
  同 order_id `2100917058081423362` 在 dw_dwd（A 金额口径）与 fxsb_dwd（B 59.80）两库互不串写。
