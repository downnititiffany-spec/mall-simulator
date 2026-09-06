# 指标字典（Metric Dictionary）— definition_version v1

> 依据：项目设计文稿 V2.2 §2.3、§21.3–§21.10。本字典是**唯一指标口径来源**，
> AI 模块与固定看板只能引用其中已定义口径；任何口径修改必须升 `definition_version` 并记录变更。
> 时间范围、过滤条件、统计粒度必须随每次查询明确声明。

## 1. 概念约定

* 时区：业务统计按 Asia/Shanghai 解释日期；`event_time` 决定指标归属日，`ingest_time` 只用于延迟统计。
* 金额：`DECIMAL(18,2)`；对账一律 BigDecimal，禁止 double。
* 去重口径：UV/DAU/转化率等一律按 `user_id` 去重，不能用事件行数替代用户数。
* 分母为 0：返回 `null` + “无可计算数据”，不返回 0% 或 ∞。

## 2. 首批指标清单（15 项）

| metric_code | 名称 | 口径 / 公式 | 粒度 | 时间字段 | 数据来源（DWS/ADS） |
|---|---|---|---|---|---|
| pv | 浏览量 | `count(view 行为事件)` | 日/小时 | event_time | dwd_user_behavior_detail |
| uv | 浏览用户数 | `count(distinct user_id where behavior_type='view')` | 日 | event_time | dwd_user_behavior_detail |
| dau | 日活跃用户数 | `count(distinct user_id where 当日存在任一有效行为)` | 日 | event_time | dwd_user_behavior_detail |
| fav_cnt | 收藏次数 | `count(favorite 事件)` | 日 | event_time | dwd_user_behavior_detail |
| cart_add_cnt | 加购次数 | `count(cart_add 事件)` | 日 | event_time | dwd_user_behavior_detail |
| cart_rate | 加购率 | `加购用户数 ÷ 浏览用户数`（用户去重口径） | 日 | event_time | dws_behavior_funnel_day |
| buy_rate | 购买转化率 | `支付用户数 ÷ 浏览用户数`（用户去重口径） | 日 | event_time | dws_behavior_funnel_day |
| paid_order_cnt | 支付订单数 | `count(distinct order_id where final_paid_flag=1)` | 日 | paid_at | dws_trade_day |
| gmv | 销售额(GMV) | `sum(paid_amount)`（有效支付订单） | 日/周/月 | paid_at | dws_trade_day |
| net_sale | 净销售额 | `sum(paid_amount) − sum(refund_completed.amount)` | 日/周/月 | paid_at | dws_trade_day + refund |
| avg_order_value | 客单价 | `GMV ÷ 支付订单数` | 日/周/月 | paid_at | dws_trade_day |
| refund_rate | 退款率 | `退款订单数 ÷ 支付订单数`（按订单去重） | 日/周/月 | paid_at | dws_trade_day / 退款表 |
| repeat_rate | 复购率（有效） | `观察期内有效支付订单数≥2 的用户数 ÷ 观察期内支付用户数`；取消不计购买，完全退款订单从“有效复购率”排除 | 观察期（默认30天） | paid_at | dws_user_trade_period |
| product_heat | 商品热度 | `1.0×ln(1+PV) + 2.0×ln(1+收藏数) + 3.0×ln(1+加购数) + 5.0×ln(1+支付件数)`（权重来自业务设定，存配置表） | 日 | event_time | dws_product_behavior_day |
| user_value_level | 用户价值等级 | RFM 分位数分层：R 反向五分位/ F、M 正向五分位 → 与中位分比较得到“重要价值/重要发展/重要保持/重要挽留/一般价值/一般发展/一般保持/一般挽留”八类 | 用户×观察期 | 截止日 | ads_user_profile |

> 复购率必须声明观察期；页面默认展示“有效复购率”，另可由口径参数切换“支付复购率”。

## 3. 对比与异常口径

* 同比/环比：`(current − previous) / previous`；previous=0 时不计算比例，只展示绝对差。
* 异常检测（z-score）：`z = (x_t − 滚动均值) / 滚动标准差`，窗口 7 或 14 个同口径周期，`|z| ≥ 2.5` 标记候选异常；标准差为 0 改用绝对变化阈值；偏态分布用 IQR（`Q1−1.5×IQR` / `Q3+1.5×IQR`）。业务阈值示例：退款率 >15%、库存覆盖 <3 天。异常只是待关注信号，不是因果结论。
* 维度贡献拆解：`维度贡献 = 当前维度值 − 基准维度值`；`贡献占比 = 维度变化量 ÷ 总变化量`。
* 决策效果：`improvementRate = (actual − baseline) / |baseline|`，baseline/actual 为执行前后各 N 个完整周期平均值；“越低越好”指标（退款率、流失率）方向取反。结果分级：ACHIEVED / PARTIALLY_ACHIEVED / NOT_ACHIEVED / INSUFFICIENT_DATA。前后对比不声称因果。

## 4. 每个指标返回结构

```json
{
  "metric_code": "gmv",
  "value": "226.30",
  "unit": "元",
  "period": "day:2026-09-01",
  "comparison_value": {"type": "DOD", "value": "-0.05"},
  "definition_version": "v1",
  "snapshot_id": "S20260901_01"
}
```

## 5. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09-06 | 建立首批 15 项指标与对比/异常/决策口径 |