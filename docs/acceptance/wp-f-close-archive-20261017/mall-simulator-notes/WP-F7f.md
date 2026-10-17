# WP-F7f 规约：AdsSql.categorySale 整单退款按行比例分摊（WP-F7e 质量门实证立项）

## 背景（总控已实证，勿重新论证）
- WP-F7d 已修复 `DwsSql.tradeDay/regionSaleDay` 的整单退款行扇出；WP-F7e 同数据复跑（run 6）中质量门 `ADS_CATEGORY_SALE_RECONCILE`（BLOCKING，容差 <0.01）实测：expectedNet=**273243.80**（= 修复后 dws_trade_day 净额，预言命中）vs ads_category_sale actualNet=**238087.15**（旧扇出），run 6 于 QUALITY_CHECK 被阻断、未发布。run 5 时两边同错故对账假过；只修 DWS 后该门必然阻断——F7d 对 categorySale 的递延裁定在全链发布口径下不可维持，总控裁定改在本包修复。
- 总控 DWD 实证（dt=20260918、final_paid_flag=1）：整单退款按 order 去重 Σ=91916.50；按 (category,order) 去重 Σ=119659.70（仍多计 27743.20）；原始行 Σ=127073.15。退款单 302 笔，其中跨 ≥2 品类 64 笔（退款合计 27743.20）、多行 77 笔。故 (category,order) 去重不精确，必须把整单退款**恰好分摊一次**到该单各行。
- region 维度不受此问题影响：city_level 是订单级属性，WP-F7d 的 (region,order) 去重已精确（run 6 的 ADS_REGION_SALE_RECONCILE 已通过）。

## 裁定（总控，2026-10-17）
整单退款在 ADS 品类层按**行金额比例分摊**：line_alloc = order_refund × line_amount / order_amount_total，并做**分级最大余数校正**使每单各行分摊之和与整单退款逐分相等（Σ品类净额与 dws_trade_day 逐分对账是 BLOCKING 门的硬要求，容差 <0.01，不允许比例直舍入的累计漂移）。本裁定仅作用于 `AdsSql.categorySale` 的净额计算，不动 DWD 明细结构、`OrderTradeCompiler` 语义与指标定义（品类净额 = 品类销售额 − 分摊退款，分摊口径在交付报告与 metric-dictionary 加性说明各记一处）。

## 任务
1. **修复 `AdsSql.categorySale`**（`spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala`）：保持现有投影列、category 归一化（NULL/≤0 → -1 未分类）、dim 关联与 sale_count/sale_amount 的行级 SUM 语义不变；仅把 facts CTE 的退款项由行级 `SUM(refund_amount)` 改为分摊口径：
   - 先按 order 聚合：order_refund = MAX(refund_amount)，order_amount_total = SUM(amount)（仅 final_paid_flag=1 且 dt 分区内，与现过滤一致）；
   - 每行 raw_alloc = order_refund × amount / order_amount_total（order_amount_total=0 时该单全行 raw_alloc=0，整单退款改记到该单 category_id 最小的一行——确定性兜底，不得除零、不得丢退款）；
   - 分校正：line_alloc = ROUND(raw_alloc, 2)，再算每单 residual = order_refund − Σline_alloc，把 residual 加到该单 amount 最大的一行（并列取 category_id 最小者，再并列取行序最小——须在实现中给出行级确定性键，如 product_id）；保证每单 Σline_alloc = order_refund 逐分相等；
   - 品类 net_sale_amount = Σamount − Σline_alloc（按归一化 category 分组）。
   实现可用窗口函数（SUM/ROW_NUMBER OVER PARTITION BY order_id）；SQL 须为 Spark SQL 兼容语法且与既有 AdsSql 风格一致；关键步骤加中文注释说明分摊与校正语义。
2. **回归测试**（新增 Spark 规格，建议 `AdsCategorySaleRefundAllocationSpec`）：夹具至少含——①跨品类多行退款单（2 品类、退款不能整除时验证逐分校正与两品类分摊和=整单退款）；②单品类多行退款单（退款全额归该品类，与 WP-F7d 口径一致）；③无退款单与未支付单（final_paid_flag=0 不计入）；④断言 Σ品类 net == 手算 trade net（逐分）、Σ品类 sale == 手算 gmv；⑤订单总额为 0 且有退款的兜底行（退款记最小 category 行、总额不丢）。既有 spark 基线 380 不回退。
3. **文档**：`docs/contracts/metric-dictionary.md` 末尾加性补一段品类净额分摊口径（不动既有条目与版本号语义，仅加性说明，注明 WP-F7f 裁定）；交付 `WP-F7f-REPORT.md`（改法、校正算法、测试清单与自述——数值由总控亲跑复核）。

## 边界
- 只用文件读写工具：不跑命令、不连数据库、不改仓库外文件。
- 不动 DwsSql（WP-F7d 成果）、不动质量规则目录与容差、不动 DWD/Compiler、不碰 3306/Docker/DeepSeek。
- 完成标记：WPF7F-DONE
