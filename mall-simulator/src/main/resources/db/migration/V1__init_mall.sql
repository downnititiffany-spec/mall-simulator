-- =====================================================================
-- V1: 最小商城初始表结构 + 商品目录种子数据
-- 依据：项目设计文稿 V2.2 §20.1（域模型）、§21.2（金额/去重规范）
-- =====================================================================

-- 用户
CREATE TABLE mall_user (
    user_id       BIGINT       NOT NULL COMMENT '业务用户ID(雪花)',
    age_group     VARCHAR(16)  NOT NULL COMMENT '年龄段 under18/18-24/25-34/35-44/45+',
    city_level    VARCHAR(16)  NOT NULL COMMENT '城市等级 tier1/tier2/tier3/other',
    member_level  VARCHAR(16)  NOT NULL COMMENT '会员等级 normal/silver/gold/platinum',
    register_time DATETIME(3)  NOT NULL COMMENT '注册时间',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城用户（模拟属性，无真实个人信息）';

-- 商品分类（§20.1：一级 8-12 类，最多三级）
CREATE TABLE category (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id BIGINT       NOT NULL DEFAULT 0 COMMENT '0=一级分类',
    level     INT          NOT NULL COMMENT '1=一级 2=二级',
    name      VARCHAR(64)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- 商品
CREATE TABLE product (
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    category_id  BIGINT       NOT NULL COMMENT '末级分类ID',
    brand_id     BIGINT       NOT NULL,
    price        DECIMAL(18,2) NOT NULL COMMENT '售价，price >= cost',
    cost         DECIMAL(18,2) NOT NULL COMMENT '成本（模拟，供库存周转口径）',
    status       VARCHAR(16)  NOT NULL COMMENT 'on_sale/off_sale/pending',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (product_id),
    KEY idx_product_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品';

-- 库存（§20.1：乐观锁 version，库存不得为负）
CREATE TABLE inventory (
    product_id    BIGINT  NOT NULL,
    available_qty INT     NOT NULL DEFAULT 0 COMMENT '可售库存',
    reserved_qty  INT     NOT NULL DEFAULT 0 COMMENT '预留库存（下单未支付）',
    version       INT     NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存';

-- 购物车（同用户同商品唯一）
CREATE TABLE cart_item (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_user_product (user_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

-- 订单（§20.1：CREATED→PAID→COMPLETED，旁路 CANCELLED、REFUNDING→REFUNDED）
CREATE TABLE mall_order (
    order_id     BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    status       VARCHAR(16)   NOT NULL COMMENT '订单状态',
    total_amount DECIMAL(18,2) NOT NULL COMMENT '订单总额=Σ(quantity×unit_price−discount)',
    created_at   DATETIME(3)   NOT NULL,
    paid_at      DATETIME(3)   NULL,
    completed_at DATETIME(3)   NULL,
    cancelled_at DATETIME(3)   NULL,
    cancel_reason VARCHAR(255) NULL,
    PRIMARY KEY (order_id),
    KEY idx_order_user (user_id),
    KEY idx_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 订单项
CREATE TABLE order_item (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_id   BIGINT        NOT NULL,
    product_id BIGINT        NOT NULL,
    quantity   INT           NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    discount   DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    amount     DECIMAL(18,2) NOT NULL COMMENT '= quantity×unit_price−discount',
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单项';

-- 支付（首版模拟支付，不接真实渠道）
CREATE TABLE payment (
    payment_id BIGINT        NOT NULL,
    order_id   BIGINT        NOT NULL,
    user_id    BIGINT        NOT NULL,
    amount     DECIMAL(18,2) NOT NULL,
    status     VARCHAR(16)   NOT NULL COMMENT 'SUCCESS/FAILED',
    paid_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (payment_id),
    KEY idx_payment_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付';

-- 退款（退款金额不得大于已付金额）
CREATE TABLE refund (
    refund_id    BIGINT        NOT NULL,
    order_id     BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    amount       DECIMAL(18,2) NOT NULL,
    reason       VARCHAR(255)  NOT NULL,
    status       VARCHAR(16)   NOT NULL COMMENT 'CREATED/COMPLETED',
    created_at   DATETIME(3)   NOT NULL,
    completed_at DATETIME(3)   NULL,
    PRIMARY KEY (refund_id),
    KEY idx_refund_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款';

-- 事务 Outbox（§5.2.4：与业务事务同库同事务；event_id 唯一；payload 为统一 JSON 事件）
CREATE TABLE event_outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_id       VARCHAR(64)  NOT NULL COMMENT '全局唯一，DWD 按此去重',
    aggregate_type VARCHAR(32)  NOT NULL COMMENT 'user/product/order/payment/refund/inventory',
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(32)  NOT NULL COMMENT '事件类型，见事件契约',
    trace_id       VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL COMMENT '事件信封完整 JSON 字符串（TEXT 以容忍下游解析失败的坏样本，供隔离/重放研究）',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at   DATETIME(3)  NULL COMMENT '写入滚动日志时间，NULL=未发布',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_published (published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件 Outbox';

-- =====================================================================
-- 商品目录种子数据（§5.2.2：8-12 个一级分类、每类 5-15 个二级分类约束内的首版简化版）
-- SUMMER 促销基础数据；真实生成器（阶段3）会通过业务 Service 再扩展
-- =====================================================================

INSERT INTO category (id, parent_id, level, name) VALUES
 (1, 0, 1, '电子数码'), (2, 0, 1, '家居生活'), (3, 0, 1, '服饰鞋包'), (4, 0, 1, '食品生鲜'),
 (5, 0, 1, '美妆个护'), (6, 0, 1, '母婴玩具'), (7, 0, 1, '运动户外'), (8, 0, 1, '图书文娱'),
 (11, 1, 2, '手机配件'), (12, 1, 2, '耳机音箱'), (21, 2, 2, '厨房用品'), (22, 2, 2, '收纳清洁'),
 (31, 3, 2, '男装'), (32, 3, 2, '女装'), (41, 4, 2, '休闲零食'), (42, 4, 2, '水果生鲜'),
 (51, 5, 2, '护肤'), (52, 5, 2, '彩妆'), (61, 6, 2, '奶粉辅食'), (62, 6, 2, '玩具'),
 (71, 7, 2, '健身器材'), (72, 7, 2, '户外装备'), (81, 8, 2, '经管图书'), (82, 8, 2, '文具');

INSERT INTO product (product_id, product_name, category_id, brand_id, price, cost, status) VALUES
 (1001, '磁吸手机支架',   11, 101, 29.90,  18.00, 'on_sale'),
 (1002, '快充数据线套装', 11, 102, 49.00,  28.00, 'on_sale'),
 (1003, '入耳式蓝牙耳机', 12, 101, 149.00, 85.00, 'on_sale'),
 (1004, '桌面蓝牙音箱',   12, 103, 199.00, 110.00, 'on_sale'),
 (2001, '不锈钢炒锅',     21, 201, 159.00, 95.00, 'on_sale'),
 (2002, '真空保温杯',     21, 202, 89.00,  48.00, 'on_sale'),
 (2003, '压缩收纳袋 10 只', 22, 203, 39.90, 22.00, 'on_sale'),
 (2004, '免打孔置物架',   22, 204, 59.00,  32.00, 'on_sale'),
 (3001, '纯棉圆领T恤',    31, 301, 79.00,  35.00, 'on_sale'),
 (3002, '牛仔裤',        31, 302, 129.00, 68.00, 'on_sale'),
 (3003, '雪纺连衣裙',     32, 301, 169.00, 90.00, 'on_sale'),
 (3004, '针织开衫',       32, 303, 139.00, 75.00, 'on_sale'),
 (4001, '混合坚果礼盒',   41, 401, 99.00,  55.00, 'on_sale'),
 (4002, '冻干草莓脆',     41, 402, 29.90,  16.00, 'on_sale'),
 (4003, '赣南脐橙 5kg',  42, 403, 49.90,  30.00, 'on_sale'),
 (4004, '智利车厘子 2kg', 42, 404, 159.00, 98.00, 'on_sale'),
 (5001, '烟酰胺精华液',   51, 501, 119.00, 45.00, 'on_sale'),
 (5002, '氨基酸洁面乳',   51, 502, 59.00,  26.00, 'on_sale'),
 (5003, '哑光唇釉',       52, 503, 89.00,  32.00, 'on_sale'),
 (5004, '持妆粉底液',     52, 504, 179.00, 88.00, 'on_sale'),
 (6001, '婴幼儿配方奶粉', 61, 601, 268.00, 160.00, 'on_sale'),
 (6002, '儿童钙片',       61, 602, 79.00,  40.00, 'on_sale'),
 (6003, '积木拼装套装',   62, 603, 139.00, 70.00, 'on_sale'),
 (6004, '遥控小汽车',     62, 604, 99.00,  50.00, 'on_sale'),
 (7001, '瑜伽垫加厚',     71, 701, 69.00,  32.00, 'on_sale'),
 (7002, '哑铃组合套装',   71, 702, 199.00, 105.00, 'on_sale'),
 (7003, '速干运动T恤',    72, 701, 89.00,  40.00, 'on_sale'),
 (7004, '露营折叠椅',     72, 703, 119.00, 62.00, 'on_sale'),
 (8001, '大数据之路',      81, 801, 89.00,  45.00, 'on_sale'),
 (8002, 'Spark快速大数据分析', 81, 801, 79.00, 38.00, 'on_sale'),
 (8003, '钢笔礼盒',       82, 802, 69.00,  30.00, 'on_sale'),
 (8004, '日程记事本',     82, 803, 39.00,  18.00, 'on_sale');

INSERT INTO inventory (product_id, available_qty, reserved_qty, version)
SELECT product_id, 100, 0, 0 FROM product;