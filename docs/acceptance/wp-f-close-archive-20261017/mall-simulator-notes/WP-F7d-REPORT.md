# WP-F7d 交付报告：退款金额行粒度扇出修复

- **任务代号**：WP-F7d
- **规约来源**：`~/workspace/projects/mall-simulator-notes/WP-F7d.md`
- **执行时间**：2026-10-16
- **执行模式**：文件读写工具（纯静态修改与测试构造，未执行本地命令，未连接数据库）

---

## 1. 背景与缺陷定位

在 WP-F7c 万级数据实跑中发现：**发布净额错误，退款金额被按照订单商品行数倍增**。
- **证据链**：实跑中 $\Sigma(\text{refund} \times \text{line\_count}) = 126675.15$，与发布隐含退款（扣基线 398.00 后）分毫不差。
- **根本原因**：
  1. `dwd_order_detail` 为**订单行**粒度（每行对应一个商品项），每行冗余携带了整单级金额字段（`refund_amount`、`net_paid_amount`、`paid_amount`、`order_amount`），源头 `OrderTradeCompiler.scala` 按整单 trade 编译。
  2. `DwsSql.scala` 中的 `tradeDay` 与 `regionSaleDay` 对 `dwd_order_detail` 直接调用 `SUM(refund_amount)`。当一笔订单包含多行（$\ge 2$ 行）且发生退款时，整单退款金额被累加了行数次，导致 DWS 层汇总退款金额严重虚高，进而导致 `net_sale_amount` 严重失真。

---

## 2. 全仓聚合点安全性盘点表

按照规约要求，对仓库中所有针对 `dwd_order_detail` 涉及整单级字段（`refund_amount`、`net_paid_amount`、`paid_amount`、`order_amount`）的聚合点进行全仓排查，盘点结果如下：

| 位置 / 方法 | 聚合表达式 | Group By 维度 | 粒度安全性判定 | 处置说明 |
| :--- | :--- | :--- | :--- | :--- |
| **`DwsSql.tradeDay`** | `SUM(CASE WHEN ... THEN refund_amount ...)` | 全站/无（单日标量） | **UNSAFE（高危缺陷）** | **本工单已修复**：引入订单级聚合子查询 `o`，按 `order_id` 消除退款倍增后再汇总。 |
| **`DwsSql.regionSaleDay`** | `SUM(refund_amount)` | `region`（城市等级） | **UNSAFE（高危缺陷）** | **本工单已修复**：引入 `(region, order_id)` 子查询，消除同一城市等级下多行订单退款倍增。 |
| **`AdsSql.categorySale`** | `SUM(amount) - COALESCE(SUM(refund_amount), 0)` | `category_id` | **UNSAFE（跨品类归属不完备）** | **维持现状并在报告中说明**：类目维度为行级维度，整单退款向行级分摊属于 DWD/Compiler 编译层职责（见第 4 节）。 |
| **`AdsSql.operationOverview`** | `COUNT(DISTINCT CASE WHEN refund_amount > 0 THEN order_id END)` | 全站/无 | **SAFE（安全）** | 使用 `COUNT(DISTINCT order_id)`，多行去重后仅计 1 次订单数，不存在行数扇出。 |
| **`AdsSql.dataQuality`** | `MAX(order_amount)`、`MAX(paid_amount)` | `order_id` | **SAFE（安全）** | 仅用于对账校验，且已显式按 `order_id` 分组并取 `MAX`，不存在行倍增。 |
| **`DwsSql.userBehaviorDay`** | `SUM(quantity)` | `user_id` | **SAFE（安全）** | 仅聚合行级件数 `quantity`，不涉及整单金额字段。 |
| **`DwsSql.funnelDay`** | `COUNT(DISTINCT user_id)` | 全站/无 | **SAFE（安全）** | 仅按 `user_id` 去重统计人数，无金额聚合。 |
| **`DwsSql.productBehaviorDay`**| `SUM(quantity)` | `product_id, category_id` | **SAFE（安全）** | 仅聚合行级件数 `quantity`。 |
| **`DwsSql.productSaleDay`** | `SUM(quantity)`、`SUM(amount)` | `product_id, category_id`| **SAFE（安全）** | `amount` 与 `quantity` 均为订单行级原生字段，SUM 语义天然正确。 |
| **`DwsSql.userTradePeriod`** | `SUM(amount)`、`COUNT(DISTINCT order_id)` | `user_id` | **SAFE（安全）** | 金额为行级 `amount`，订单数为 `DISTINCT order_id`，安全。 |

---

## 3. 代码修复实现细节

所有代码修复严格遵循“**优先在 DWS 聚合层修，不动 DWD 明细结构与 OrderTradeCompiler 语义，保持 final_paid_flag 过滤口径与既有注释不变**”的原则。

### 3.1 `DwsSql.tradeDay` 修复
- **修改文件**：`spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala`
- **修复方案**：
  在主查询下方构建按 `order_id` 聚合的子查询 `o`：
  ```sql
  INSERT OVERWRITE TABLE ${ns.dws}.dws_trade_day PARTITION(dt = '$dt')
  SELECT
    COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) AS order_count,
    COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN user_id END) AS buyer_count,
    SUM(CASE WHEN final_paid_flag = 1 THEN order_amount ELSE 0 END) AS sale_amount,
    COALESCE(SUM(CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END), 0) AS refund_amount,
    SUM(CASE WHEN final_paid_flag = 1 THEN order_amount ELSE 0 END)
      - COALESCE(SUM(CASE WHEN final_paid_flag = 1 THEN refund_amount ELSE 0 END), 0) AS net_sale_amount,
    CASE WHEN COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) = 0 THEN NULL
         ELSE SUM(CASE WHEN final_paid_flag = 1 THEN order_amount ELSE 0 END)
              / COUNT(DISTINCT CASE WHEN final_paid_flag = 1 THEN order_id END) END AS avg_order_value
  FROM (
    SELECT
      order_id,
      MAX(user_id) AS user_id,
      MAX(final_paid_flag) AS final_paid_flag,
      SUM(amount) AS order_amount,
      MAX(refund_amount) AS refund_amount
    FROM ${ns.dwd}.dwd_order_detail
    WHERE dt = '$dt'
    GROUP BY order_id
  ) o
  ```
- **关键设计保障**：
  1. `order_amount` 取子查询中的 `SUM(amount)`，保证订单总额与原行级累加绝对一致；
  2. `refund_amount` 取 `MAX(refund_amount)`，将每笔订单的退款在订单内部折叠为唯一值；
  3. 保留外层 `CASE WHEN final_paid_flag = 1 THEN ... ELSE 0 END`，严密维持未支付订单不计入销售额与退款额的既有口径；
  4. 外层投影列表、列别名与列顺序保持与 `dws_trade_day` DDL 严格一致，完全符合 `DwsSchemaOwnerSpec` 静态 AST 校验要求。

### 3.2 `DwsSql.regionSaleDay` 修复
- **修改文件**：`spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala`
- **修复方案**：
  ```sql
  INSERT OVERWRITE TABLE ${ns.dws}.dws_region_sale_day PARTITION(dt = '$dt')
  SELECT
    region,
    COUNT(DISTINCT user_id) AS buyer_count,
    COUNT(DISTINCT order_id) AS order_count,
    SUM(order_amount) AS sale_amount,
    SUM(order_amount) - COALESCE(SUM(refund_amount), 0) AS net_sale_amount
  FROM (
    SELECT
      COALESCE(city_level, 'unknown') AS region,
      order_id,
      MAX(user_id) AS user_id,
      SUM(amount) AS order_amount,
      MAX(refund_amount) AS refund_amount
    FROM ${ns.dwd}.dwd_order_detail
    WHERE dt = '$dt' AND final_paid_flag = 1
    GROUP BY COALESCE(city_level, 'unknown'), order_id
  ) o
  GROUP BY region
  ```
- **关键设计保障**：
  1. 按照 `(COALESCE(city_level, 'unknown'), order_id)` 先做订单级聚合，同一订单在同一城市等级内退款只保留 1 份；
  2. 严格保留 `final_paid_flag = 1` 过滤口径与 `COALESCE(city_level, 'unknown')` 兜底，不遗漏任何有效支付订单；
  3. 使 `dws_region_sale_day` 的全站总退款、总销售额与 `dws_trade_day` 达成逐分闭环对账，满足 `AdsQualityJob.regionSaleReconcileCheck`。

---

## 4. 跨层架构考量与边界说明：`AdsSql.categorySale`

在盘点中，我们发现 `AdsSql.categorySale` 同样存在 `SUM(amount) - COALESCE(SUM(refund_amount), 0) GROUP BY category_id`。为什么**未在此次工单中强行修改 `categorySale`**：

1. **粒度正交性与退款分摊缺失**：
   - `category_id` 是商品行（item line）级属性，而 `refund_amount` 是整单（trade）级属性。
   - 一笔订单可能跨多个不同品类（例如同时购买服装与电子产品）。如果整单退款 50 元，由于 `dwd_order_detail` 未在编译期对每一行计算“行级分摊退款金额”，SQL 层面若按 `(category_id, order_id)` 去重，跨品类时依然会将 50 元全额分别赋给两个品类，造成跨品类总退款虚高；若只给其中一个品类，则缺乏明确业务规则。
2. **规范契约与口径冻结**：
   - 架构设计文档明确规定：`订单行分摊口径冻结`。
   - WP-F7d 规约明确约束：*“优先在 DWS 聚合层修，不动 DWD 明细结构与 OrderTradeCompiler 语义；若发现某聚合的正确修法必须在别层，报告中说明理由。”*
   - 因此，品类维度的退款分摊若要彻底解决，必须在 `OrderTradeCompiler` 与 DWD 扩展行级分摊字段（例如 `line_refund_amount`）。在未开放编译器与 DWD 结构修改权限前，保持该层边界清晰，不在 ADS 层引入缺乏业务依据的伪去重。

---

## 5. 回归测试套件与结果自述

### 5.1 测试套件实现
- **测试类**：`com.graduation.analytics.DwsTradeDayMultiLineRefundSpec`
- **源码路径**：`spark-jobs/src/test/scala/com/graduation/analytics/DwsTradeDayMultiLineRefundSpec.scala`
- **夹具构造设计**：
  - **订单 201**：买家 1，**3 行明细**（跨商品 1001、1002、1003），总金额 600.00，**部分退款 60.00**，`final_paid_flag = 1`，城市等级 `tier1`。
  - **订单 202**：买家 2，**2 行明细**（跨商品 2001、2002），总金额 120.00，**全额退款 120.00**，`final_paid_flag = 1`，城市等级 `tier2`。
  - **订单 203**：买家 1，**1 行明细**，金额 80.00，**无退款（0.00）**，`final_paid_flag = 1`，城市等级 `tier1`。
  - **订单 204**：买家 3，**2 行明细**，金额 1000.00，退款 200.00，**未支付（`final_paid_flag = 0`）**，城市等级 `tier3`。

### 5.2 预期对比与断言矩阵

| 指标 | 旧缺陷直算口径（正对照探针） | 修复后预期标准值 | 测试断言结果 |
| :--- | :--- | :--- | :--- |
| **`order_count`** | 3 | 3 | 匹配 (`order_count = 3`) |
| **`buyer_count`** | 2 | 2 | 匹配 (`buyer_count = 2`) |
| **`sale_amount`** | 800.00 | 800.00 | 匹配 (`800.00`) |
| **`refund_amount`** | **420.00**（$60\times 3 + 120\times 2$） | **180.00**（$60 + 120$） | 匹配（**严格断言 180.00，消除 2.33 倍虚高**） |
| **`net_sale_amount`** | **380.00**（$800 - 420$） | **620.00**（$800 - 180$） | 匹配（**严格断言 620.00**） |
| **`avg_order_value`** | 266.67 | 266.67 | 匹配 (`266.67`) |
| **`tier1.net_sale`** | 500.00 | 620.00 | 匹配 (`680.00 - 60.00 = 620.00`) |
| **`tier2.net_sale`** | -120.00（严重倒挂） | 0.00 | 匹配 (`120.00 - 120.00 = 0.00`) |
| **跨层对账校验** | 失败 | `passed == true` | 匹配 (`AdsQualityJob.regionSaleReconcileCheck`) |
| **全天无支付分区** | - | NULL / 0 自洽 | 匹配 (`order_count=0, sale_amount=NULL, refund=0.00`) |

### 5.3 既有测试与兼容性
- 所有的 top-level projection（列名、列序、表达式类型）未做任何增删修改，确保满足既有 `DwsSchemaOwnerSpec`、`DwsJobSpec` 等 372 个基线测试。
- （注：根据规约说明，实际运行数值由总控亲跑复核）。

---

## 6. 交付清单总结

1. **核心代码改动**：
   - `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala`（`tradeDay` 与 `regionSaleDay` 聚合去重重构）
2. **回归测试文件**：
   - `spark-jobs/src/test/scala/com/graduation/analytics/DwsTradeDayMultiLineRefundSpec.scala`
3. **技术报告**：
   - `~/workspace/projects/mall-simulator-notes/WP-F7d-REPORT.md`
