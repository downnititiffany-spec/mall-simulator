-- S3-08（设计 §11.2 L425「收藏/加购 | 对应行为事件数，用户转化时另算去重用户数 | 行为」；
-- 字典 `metric-dictionary.md:21/:22` = count(favorite 事件) / count(cart_add 事件)，源表 dwd_user_behavior_detail）
-- `ads_operation_overview_m` 末尾追加「收藏次数 / 加购次数」两列：
--   fav_cnt       = 当日 favorite 行为**事件条数**（不是收藏人数）
--   cart_add_cnt  = 当日 cart_add 行为**事件条数**（不是加购人数；cart_remove 不计）
--
-- 为什么是"次数"而不是"人数"：设计 L425 明确要求对应**行为事件数**，并把"用户转化时另算去重用户数"
-- 列为另一件事；字典也是 `count(... 事件)`。人数口径已有 uv（浏览去重用户）与漏斗侧
-- `cart_rate`/`buy_rate` 的分母，若把人数搬到这里当次数用，会出现"同一列既像次数又像人数"的双口径，
-- 页面「收藏/加购」卡片就会在两处不一致。故本迁移只承载次数，人数另算。
--
-- 为什么落在概览表而不是漏斗表/商品表：
--   1) 漏斗表（`ads_behavior_funnel`）的 stage 行是**去重用户数**语义，且设计 §11.3 L441 把漏斗阶段
--      固定为 view/intent/order/pay 四个，不得为收藏/加购新增第五个阶段行；
--   2) 商品表 `ads_hot_product` 已有按商品的 `fav`/`cart`，那是"按商品热度权重用的计数"，
--      不是"全站当日收藏/加购次数"这一独立指标；
--   3) 概览表与 `pv` 同表同粒度（日、全站、事件数），口径同型，取数与对账路径最短。
--
-- 为什么是**加性 ALTER**而不是改 V2 的建表语句：V2/V6 都是已发布迁移，改动会破坏 Flyway checksum
-- （治理门 ③）。Hive 侧的 `ALTER TABLE … ADD COLUMNS` 只能追加列，故 MySQL 也追加在同一位置，
-- 与 spark-jobs 的 `MetricAdsSpec`（列真源）和 `MetricAdsCatalog`（写入白名单）末尾列序保持一致。
--
-- 空值语义：两列允许 NULL。**无该行为事件的业务日，Spark 侧写入 0 而不是 NULL**（与同表 `pv` 同型：
-- 不存在即 0）；NULL 只表示"这一行是历史快照、当时还没算过这两列"（未计算占位），不得当成真实 0。
ALTER TABLE ads_operation_overview_m
    ADD COLUMN fav_cnt BIGINT NULL COMMENT '收藏次数=当日 favorite 行为事件条数（次数口径，不是收藏人数）',
    ADD COLUMN cart_add_cnt BIGINT NULL COMMENT '加购次数=当日 cart_add 行为事件条数（次数口径，不是加购人数）';
