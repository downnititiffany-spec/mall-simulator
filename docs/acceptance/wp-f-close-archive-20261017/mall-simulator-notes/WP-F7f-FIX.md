# WP-F7f-FIX 修复规约：质量门空日对账分支与 tradeDay 空值语义对齐（总控首轮验收判不合格后立）

## 总控首轮验收证据（勿重新论证，照此修复）
- 总控亲跑 spark 档量数轮（RunId `wpf7fgate20261017a`，-AllowCountDrift）：**386 跑、385 成功、1 失败**，唯一红 = 新增 `AdsCategorySaleRefundAllocationSpec` 的空分区用例（`AdsCategorySaleRefundAllocationSpec.scala:290`，`check.passed should be(true)` 失败）。其余 5 例（含跨品类分摊逐分校正、全站闭环对账、质量门非空日通过、正对照扇出探针）全过——`AdsSql.categorySale` 修复本体成立，本修复轮**不得改动** AdsSql 与该测试文件。
- 根因（总控已实证）：`DwsSql.tradeDay` 空日聚合行语义为 `sale_amount=NULL、net_sale_amount=NULL、refund_amount=0、order_count=0`，且已被既有规格冻结（`DwsTradeDayMultiLineRefundSpec`「空值语义自洽」用例逐项断言 NULL）。而 `AdsQualityJob.salesDimensionReconcileCheck` 的 `emptyDayMatches` 要求 `expectedNet.exists(_.signum() == 0)`（净额须为 0），其上方注释「net_sale_amount=0」的前提与真实语义不符——空日对账恒假，属 stage7 以来从未被空日对账用例覆盖的存量隐患；任一无支付订单日走发布链都会被该 BLOCKING 门误阻断。

## 总控裁定（2026-10-17，本修复轮扩围一处）
WP-F7f 原边界「不动质量规则」指规则目录与容差；本处是同一对账函数的空日判据与其自身注释意图（空日应通过）的对齐，不动规则码、目录与 0.01 容差，不动 DwsSql 冻结语义。故本修复轮破例扩围**仅此一处谓词**。

## 任务（仅文件读写，不跑命令、不连库）
1. 修改 `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` 中 `salesDimensionReconcileCheck` 的空日判据：
   - `emptyDayMatches` 中净额条件由 `expectedNet.exists(_.signum() == 0)` 改为接受 NULL 或 0：`expectedNet.isEmpty || expectedNet.exists(_.signum() == 0)`（等价写法可，但语义必须恰为此）。
   - 同步修正其上方英文注释：空日 trade 行实为 `sale_amount=NULL` 且 `net_sale_amount=NULL`（tradeDay 空值语义，见 DwsTradeDayMultiLineRefundSpec），净额为 0 的形态一并接受；维度侧正确表示仍是 0 行。其余注释与代码不动。
   - 该私有函数为 category/region 两门共用，一处修改同时修复两门——在报告中写明。
2. 在 `WP-F7f-REPORT.md`（仓库根副本与 notes 镜像两份保持逐字一致）末尾加一节「FIX 轮（总控首轮验收后）」：写明首轮 386/385/1 的失败点、根因、裁定与本处改法。
3. 不得改动：`AdsSql.scala`、`DwsSql.scala`、`AdsCategorySaleRefundAllocationSpec.scala`、规则目录/容差、其他任何文件。

## 完成标记
回复末行：WPF7F-FIX-DONE
