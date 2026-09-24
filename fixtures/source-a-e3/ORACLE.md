# E3/E4 独立 Oracle（BATCH-G3103）

> **D-035 夹具口径修正（2026-09-20，执行期发现）**：首版夹具实体 id 用 `g3u01/g3p01/g3o0101`
> 样式（数字嵌在中间），不满足冻结 `IdCodec` 契约 `^[A-Za-z]*([0-9]+)$`（可选字母前缀+纯数字）
> → `REGEXP_EXTRACT` 不匹配 → DWD `user_id/product_id/order_id` 全 NULL → 质量门
> `ADS_STAGING_KEY_NOT_NULL`/`REQUIRED_FIELD_NULL_RATE` 正确阻断（run 18 FAILED，证据留存于
> g3103 attempt `e3-leg/06-pipeline-final.json`）。夹具改用符合契约的纯数字实体 id。业务事件设计和金额总量不变；oracle 同步修正 ADS 金额精度（DWS DECIMAL(18,2)）、漏斗四阶段（加购率单列）及 RFM 最后一类标签。本文下文沿用 `g3uXX` 可读标签，对应关系：
> g3u01..15→93001..93015，g4u01..07→93021..93027，g3p01..05→93101..93105，
> g3o0001/0002→940001/940002，g3o0101..14→940101..940114，g4o0101..13→942101..942113。
> 事件 id（g3e/g4e）、trace、payment、refund id 不经 IdCodec，保持原样。

来源：`generate.py`（确定性夹具，99 + 71 行）→ `oracle.py`（纯 Python 重述冻结口径，**不 import 平台代码**）。
冻结口径来源：`DwsSql.funnelDay/productBehaviorDay/tradeDay/userTradePeriod`、`AdsSql.operationOverview/funnel/hotProduct/userProfile(rfm-v2)`、`DecisionService.evaluate/grade`、`OrderTradeCompiler`（退款只计 refund_completed、按 refund_id 去重、归属订单业务日 = F-35 同日退款）。

## E3（A 腿，快照 A，businessTime 2026-09-18）

文件跨 2026-09-17 + 2026-09-18；09-17 仅入 ODS/DWD（8 注册 + 5 商品 + 2 浏览 + 1 支付单 g3o0001@100 + 1 取消单），证明跨日装载；ADS 单 dt=09-18（单日语义，pipeline 不刷 09-17 分区）。

09-18 关键构造：
- 行为 45：view 30（pv=30，10 个用户 uv=10=dau）、favorite 6（p1/p2/p3 各 2）、cart_add 8（5 个用户 cart_users=6，p1=3/p2=2/p3=2/p4=1）、cart_remove 1（不计次）。
- 交易：12 支付单 gmv=1010（u01×4@100 + u02×3@90 + u03×2@80 + u04@70 + u05@60 + u06@50）；取消单 g3o0113/u07、g3o0114/u08（order_users=6+2=8）；g3o0109 全额退款 80（同日，F-35）→ net_sale=930。
- dim：09-18 product_updated ×5 → dim_product(09-18) 带名（DEF-08：只覆盖当日事件）。

### 快照 A 14 cells（oracle 输出，机器可核对）

| code | period | value | 口径备注 |
|---|---|---|---|
| pv / uv / dau | day:2026-09-18 | 30 / 10 / 10 | view 事件数 / 去重 / 全行为去重 |
| fav_cnt / cart_add_cnt | day:2026-09-18 | 6 / 8 | 事件条数（S3-08） |
| paid_order_cnt / gmv / net_sale | day:2026-09-18 | 12 / 1010 / 930 | 有效支付；退款只扣已完成 |
| avg_order_value | day:2026-09-18 | 84.17 | 1010/12，按 DWS DECIMAL(18,2) |
| refund_rate / full_refund_rate | day:2026-09-18 | 0.0833 / 0.0833 | 1/12（部分=全额，本夹具全为全额） |
| repeat_rate | window:2026-09-18..2026-09-18 | 0.3333 | 有效复购 S3-03：2/6（u03 全额退款单不计 valid） |
| buy_rate / cart_rate | day:2026-09-18 | 0.6 / 0.6 | pay/view=6/10；cart/view=6/10 |

### 漏斗（03.1 可对账）

view=10 → intent=6 → order=8 → pay=6；intent_rate=0.6、order_rate=1.3333（8/6，取消单计入 order）、pay_rate=0.75、overall_buy_rate=0.6。加购另以 `cart_users=6`、`cart_rate=0.6` 发布，不是漏斗 stage（S3-04）。

### 商品排行（03.1 ≥3 商品 + 并列）

| rank | product | pv/uv/fav/cart/buy | heat |
|---|---|---|---|
| 1 | g3p01 | 12/5/2/3/4 | 16.968247 |
| 2 | g3p02 | 6/3/2/2/3 | **14.370443** |
| 3 | g3p03 | 6/2/2/2/3 | **14.370443**（与 g3p02 全同 → 并列，product_id ASC 定序） |
| 4 | g3p04 | 4/4/0/1/1 | 7.154615 |
| 5 | g3p05 | 2/2/0/0/1 | 4.564348 |

并列断言：heat(g3p02)==heat(g3p03)（4 输入全同），稳定键 (heat DESC, buy DESC, product_id ASC) → g3p02 rank2、g3p03 rank3。分页稳定性：hot_product API 分页两次结果序列一致。

### RFM（03.2 原值 + 确定性分档）

rfm-v2：NTILE 排序键带 user_id ASC（稳定），r=6−r_ntile、f=f_ntile（ASC=订单多桶号大）、m=m_ntile（ASC=金额大桶号大）；6 行 NTILE(5) 桶宽 [2,1,1,1,1]。窗口=单日 [09-18, 09-18] → r_days 全 0；u03 valid=1（全额退款单剔除）。

| user | f_count | m_amount | r/f/m | value_group | lifecycle |
|---|---|---|---|---|---|
| g3u01 | 4 | 400 | 5/5/5 | 重要价值 | 活跃 |
| g3u02 | 3 | 270 | 5/4/4 | 重要价值 | 活跃 |
| g3u03 | 2 | 160 | 4/3/3 | 一般发展 | 活跃 |
| g3u04 | 1 | 70 | 3/1/2 | 一般保持 | 新用户 |
| g3u05 | 1 | 60 | 2/1/1 | 一般保持 | 新用户 |
| g3u06 | 1 | 50 | 1/2/1 | 一般挽留 | 新用户 |

distinct 人数：buyer_count=6=pay_users=6；repeat_users=2。不足场景：无支付日（如 09-17 未发布）→ 指标不可计算而非 0。

## E4（B 腿，快照 B，businessTime 取自 `e4-events.jsonl`）

71 行全部使用同一业务日。运行前执行 `python generate.py --e4-date YYYY-MM-DD`；日期必须晚于决策完成日。默认值为执行当天的次日。先完成 D1 并记录完成日，再发布 E4 对应快照；若执行跨过所选 E4 日期，重新生成 E4、运行 oracle 并更新哈希清单。oracle 从 E4 事件自身读取业务日，不维护第二份硬编码日期。

### 快照 B 14 cells

| code | period | value |
|---|---|---|
| pv / uv / dau | day:E4_DATE | 31 / 8 / 8 |
| fav_cnt / cart_add_cnt | day:E4_DATE | 2 / 1 |
| paid_order_cnt / gmv / net_sale | day:E4_DATE | 13 / 1200 / 1000 |
| avg_order_value | day:E4_DATE | 92.31（1200/13，按 DECIMAL(18,2)） |
| refund_rate / full_refund_rate | day:E4_DATE | 0.1538 / 0.1538（2/13） |
| repeat_rate | window:E4_DATE..E4_DATE | 0.5714（4/7） |
| buy_rate / cart_rate | day:E4_DATE | 0.875 / 0.125 |

### 四决策（03.4/03.6）

基线全部=快照 A（评估时 A 为 ACTIVE 时 approve 锚定）。评估在 complete 之后：

| 决策 | metric/方向/target | 评于 B 前 | 评于 B 后 |
|---|---|---|---|
| D1 | avg_order_value UP 90 | INSUFFICIENT_DATA「完成后尚未发布新快照」（actual==baseline 快照） | EFFECTIVE（92.31≥90 达标路径；rate=0.0967）→ 补数据后重评可达 ✓ |
| D2 | pv UP 无 target | — | PARTIAL（30→31，rate=0.0333 ∈ (0,0.05)） |
| D3 | refund_rate DOWN | — | INEFFECTIVE（0.0833→0.1538，rate=−0.8463 <0） |
| D4 | gmv UP 无 target | — | EFFECTIVE（1010→1200，rate=0.1881 ≥0.05 阈值路径） |

四类结果全覆盖：EFFECTIVE（D1/D4 两路径：达标/阈值）、PARTIAL（D2）、INEFFECTIVE（D3）、INSUFFICIENT_DATA（D1 首评）→ 补数据重评（D1 再 EVALUATING → EFFECTIVE，验证 INSUFFICIENT_DATA 非终态，不冻结）。
比例语义：先合并分子分母再相除（evaluate 内 rate 公式即此）；基线 0 → 「改善率无定义」（本夹具基线全非 0，0 基线分支由 G31-01 单测覆盖）。

## 跨源证据（03.5）

E4 属源 1（mock-mall）；跨源拒绝用源 2 已发布快照 **S20260918_15**（fixture-shop-b，G31-02 遗留，只引用不新增）作为 suggestionSnapshotId → 期望 SOURCE_MISMATCH 拒绝 + 审计 FAILED（D-034 新守卫）。
