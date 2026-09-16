-- S3-04（设计 §11.2 L432「buy_rate/cart_rate = 支付/加购去重用户数 ÷ 浏览去重用户数」/ 字典 `metric-dictionary.md:23`
-- 加购率 = 加购用户数 ÷ 浏览用户数，源表 dws_behavior_funnel_day）
-- `ads_behavior_funnel_m` 补加购率列 `overall_cart_rate`：
--   分子 = 当日 `cart_add` 去重用户数（**不含 favorite**，与 intent_users 区分，见 DwsSql.funnelDay 的 cart_users）；
--   分母 = 当日 `view` 去重用户数；与同表的 `overall_buy_rate` 同型（整体率，四行同值）。
--
-- 为什么加购率要落在漏斗表而不是新增阶段行：设计 §11.3 L441 的阶段集合恒为 view/intent/order/pay
-- （「禁止 min 截断」），加购不是第五阶段；因此它是一个**整体率列**，随四行重复携带，与 `overall_buy_rate` 完全同型。
--
-- 为什么是**加性 ALTER**而不是改 V2 的建表语句：V2 是已发布迁移，改动会破坏 Flyway checksum（治理门 ③）。
-- Hive 侧的 `ALTER TABLE … ADD COLUMNS` 只能追加列，故 MySQL 也追加在同一位置，
-- 与 spark-jobs 的 `MetricAdsSpec`（列真源）和 `MetricAdsCatalog`（白名单）末尾列序保持一致。
--
-- 空值语义：允许 NULL —— 该业务日**浏览用户为 0** 时分母为 0，加购率无定义（不写 0 冒充「没人加购」；
-- 此时 `cart_users` 仍如实落库，`DwsSql.funnelDay` 只在分母为 0 时置 NULL，绝不除零）。
-- 历史快照行同样为 NULL，表示「未计算」而非「加购率 0」；需要历史加购率时应重跑发布，
-- 由 Hive ADS 重算并覆盖写入。
ALTER TABLE ads_behavior_funnel_m
    ADD COLUMN overall_cart_rate DECIMAL(8,4) NULL COMMENT '整体加购率：cart_add 去重用户数 ÷ view 去重用户数（四行同值）';
