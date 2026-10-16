-- S3-01（V3.0 阶段 3「指标计算」；设计 §11.4 L447「记录 R/F/M **原值**、score、segment、窗口、rule_version，
-- 不仅存标签」＋ §9.3 L334「ads_user_profile / ads_user_profile_m：历史已发布，**RFM 完整性需补**」）。
-- 动机：此前画像表只落五分位分档（r/f/m）与标签，原值与窗口全丢 ——「r=5」究竟是「昨天买过」还是
-- 「窗口内最早一天买过」无从判断，也无法回答"这批用户的观察期是哪个区间"，即"只存标签"。
-- 口径：R 原值 = 观察窗口末日 − 末次购买日（天）；F 原值 = 窗口内有效支付订单数；M 原值 = 窗口内有效支付金额。
-- 窗口取**本次评分实际使用的窗口**（Spark 侧 SQL 参数派生），格式 yyyy-MM-dd，与同表
-- last_buy_date/last_active_date 同形，便于 `r_days = DATEDIFF(period_end, last_buy_date)` 直接校验；
-- 上游 dws_user_trade_period 的 period_start/period_end 保留原始 yyyyMMdd 口径不动（两处口径各自稳定）。
-- 兼容性：全部为**末尾追加**列，V3 已发布的 12 列名字/类型/顺序一格未动；旧行升级后取 DEFAULT
-- （NOT NULL + DEFAULT 是 DEF-10 的约定：避免 NULL 插入被拒）。

ALTER TABLE ads_user_profile_m
    ADD COLUMN r_days       INT           NOT NULL DEFAULT 0  COMMENT 'R 原值：观察窗口末日-末次购买日（天，越小越近）',
    ADD COLUMN f_count      BIGINT        NOT NULL DEFAULT 0  COMMENT 'F 原值：观察窗口内有效支付订单数',
    ADD COLUMN m_amount     DECIMAL(18,2) NOT NULL DEFAULT 0  COMMENT 'M 原值：观察窗口内有效支付金额',
    ADD COLUMN period_start VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '观察窗口起（yyyy-MM-dd，本次评分实际使用）',
    ADD COLUMN period_end   VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '观察窗口止（yyyy-MM-dd，本次评分实际使用）';
