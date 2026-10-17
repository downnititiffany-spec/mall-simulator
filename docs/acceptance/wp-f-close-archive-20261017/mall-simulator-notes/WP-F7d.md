# WP-F7d 规约：退款金额行粒度扇出修复（WP-F7c 发现 2）

## 背景（总控已实证，勿重新论证）
WP-F7c 万级实跑发布净额错误：退款被按订单行数倍增。证据链：Σ(退款×行数)=126675.15 与发布隐含退款（扣基线 398.00 后）分毫不差。机制已定位：
- `spark-jobs/.../job/TradeDwdJob.scala`：dwd_order_detail 为订单**行**粒度，每行携带整单级字段 `refund_amount`/`net_paid_amount`/`paid_amount`/`order_amount`（源头 `algorithm/OrderTradeCompiler.scala` 按 trade 编译，本身正确）。
- `spark-jobs/.../sql/DwsSql.scala` 的 `tradeDay` 对行粒度表直接 `SUM(refund_amount)`、`SUM(amount)`：amount 是行级故 GMV 正确，refund_amount 是整单级故倍增。

## 任务
1. **盘点**：全仓检索所有对 `dwd_order_detail` 做 `SUM(refund_amount)` / `SUM(net_paid_amount)` / `SUM(paid_amount)` / `SUM(order_amount)` 的聚合点（DwsSql、AdsSql 及其他 SQL/Job），逐项判定粒度安全性，写入报告。amount（行级）与件数类 SUM 不在修复面。
2. **修复**：受影响聚合改为先按订单去重再汇总（如对 (order_id, refund_amount) 先 DISTINCT/子查询按 order 取一行再 SUM，或等价的 trade 粒度子查询）。优先在 DWS 聚合层修，**不动 DWD 明细结构与 OrderTradeCompiler 语义**；若发现某聚合的正确修法必须在别层，报告中说明理由。注意 final_paid_flag 过滤口径与既有注释口径（DwsSql.scala:149-187 一带）不得改变。
3. **回归测试**：新增 Spark 测试——构造多行订单（≥2 行）+ 部分退款场景，断言 dws_trade_day 的 refund_amount 只计一次、net_sale_amount 正确；既有 spark 全量测试（基线 372）不得回退。
4. **交付**：代码改动 + 测试 + `WP-F7d-REPORT.md`（盘点表、每处改法、测试名与结果自述——数值由总控亲跑复核，不采信自述）。

## 边界
- 只用文件读写工具：不跑命令、不连数据库、不改仓库外文件。
- 不改指标定义口径、不改契约、不碰 3306/Docker/DeepSeek。
- 完成后回复末行输出：WPF7D-DONE
