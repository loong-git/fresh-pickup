-- ================================================
-- M4 增量迁移（T-M4-01 多门店 / T-M4-02 券核销 / T-M4-03 秒杀真实字段 / T-M4-05 敏感词）
-- 仅对已按 init.sql + m3-migration.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m4-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- T-M4-01 多门店：store 表（契约：id/name/address/service/status/created_at）
-- ----------------------------
CREATE TABLE IF NOT EXISTS store (
    id         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自提点ID',
    name       VARCHAR(100) NOT NULL                 COMMENT '门店名称',
    address    VARCHAR(255) NOT NULL                 COMMENT '门店地址',
    service    VARCHAR(50)  NULL                     COMMENT '服务说明（如 冷冻冷藏）',
    status     VARCHAR(20)  NOT NULL DEFAULT 'open'  COMMENT '营业状态（T-M4-01）：open营业/closed停业',
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自提点门店表';

-- 种子 4 家：沿用前端现有自提点数据（3 家营业 + 1 家停业），显式 id 保证快照引用稳定
INSERT IGNORE INTO store (id, name, address, service, status) VALUES
(1, '高黎柱发士多店(请自备袋子谢谢)', '广东省佛山市顺德区容桂高黎环涌西路桥灵坊23号', '支持冷冻/冷藏', 'open'),
(2, '高黎惠民百货店', '广东省佛山市顺德区容桂高黎惠民路12号', '冷冻冷藏', 'open'),
(3, '容桂天佑城自提点', '广东省佛山市顺德区容桂街道天佑城B座1层', '冷冻冷藏', 'open'),
(4, '朝阳社区便利店', '广东省佛山市顺德区容桂朝阳路38号', '冷冻冷藏', 'closed');

-- orders 加自提点快照三列（下单服务端按 storeId 查 store 落 name/address，无 storeId 兼容不落）
ALTER TABLE orders
    ADD COLUMN store_id      BIGINT       NULL COMMENT '自提点ID（T-M4-01，下单入参可选，快照来源）' AFTER user_id,
    ADD COLUMN store_name    VARCHAR(100) NULL COMMENT '自提点名称快照（T-M4-01，下单时落库）' AFTER store_id,
    ADD COLUMN store_address VARCHAR(255) NULL COMMENT '自提点地址快照（T-M4-01，下单时落库）' AFTER store_name;

-- ----------------------------
-- T-M4-02 券核销：user_coupon 表（契约：id/user_id/coupon_key/threshold/amount/status/order_id/created_at）
-- ----------------------------
CREATE TABLE IF NOT EXISTS user_coupon (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '券记录ID',
    user_id    BIGINT        NOT NULL                COMMENT '持券用户ID',
    coupon_key VARCHAR(50)   NOT NULL                COMMENT '券模板标识：coupon_1/coupon_2/coupon_3',
    threshold  DECIMAL(10,2) NOT NULL                COMMENT '使用门槛（满 X 元可用）',
    amount     DECIMAL(10,2) NOT NULL                COMMENT '抵扣金额（减 Y 元）',
    status     VARCHAR(20)   NOT NULL DEFAULT 'available' COMMENT '券状态：available可用/used已使用',
    order_id   VARCHAR(64)   NULL                    COMMENT '核销订单号（置 used 时回写；订单取消回补时清空）',
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表';

-- ----------------------------
-- T-M4-03 秒杀真实字段：dish 加六列（字段迁移：后端直出，前端 decorateDish 合成逻辑删除）
-- ----------------------------
ALTER TABLE dish
    ADD COLUMN seckill       TINYINT       NOT NULL DEFAULT 0 COMMENT '秒杀标记（T-M4-03）：1秒杀/0普通（种子按旧前端合成规则 id%3==0 迁移）' AFTER on_sale,
    ADD COLUMN seckill_price DECIMAL(10,2) NULL               COMMENT '秒杀价（T-M4-03）=ROUND(price*0.78,2)，非秒杀为 NULL' AFTER seckill,
    ADD COLUMN sold_count    INT           NOT NULL DEFAULT 0 COMMENT '销量（T-M4-03）：前端进度条按 sold_count/(sold_count+stock) 真实比例渲染' AFTER seckill_price,
    ADD COLUMN limit_buy     INT           NOT NULL DEFAULT 5 COMMENT '限购数量（T-M4-03）：[2,5,8] 按 id 循环' AFTER sold_count,
    ADD COLUMN tags          VARCHAR(255)  NULL              COMMENT '卖点标签（T-M4-03）：JSON 数组串，按分类写死' AFTER limit_buy,
    ADD COLUMN good_rate     VARCHAR(10)   NULL              COMMENT '好评率文案（T-M4-03）：如 94.4%' AFTER tags;

-- 种子迁移：秒杀集与秒杀价（契约第 3 条：seckill=id%3==0、seckill_price=ROUND(price*0.78,2)）
UPDATE dish SET seckill = CASE WHEN id % 3 = 0 THEN 1 ELSE 0 END;
UPDATE dish SET seckill_price = ROUND(price * 0.78, 2) WHERE seckill = 1;
UPDATE dish SET seckill_price = NULL WHERE seckill = 0;

-- 销量按 id 伪随机：复刻旧前端 soldText 伪随机种子 seed=(id*37+53)%97+3
-- （偶数 id 旧文案为 X.X 万件 → seed*1000；奇数 id 旧文案为 已卖 N 件 → seed*37）
UPDATE dish SET sold_count = CASE WHEN id % 2 = 0
    THEN (((id * 37 + 53) % 97) + 3) * 1000
    ELSE (((id * 37 + 53) % 97) + 3) * 37 END;

-- 限购：[2,5,8] 按 id 循环（与后端限购表 LIMIT_BUY_TABLE 逐位对齐）
UPDATE dish SET limit_buy = CASE WHEN id % 3 = 0 THEN 2 WHEN id % 3 = 1 THEN 5 ELSE 8 END;

-- 卖点标签：按分类写死（JSON 数组串，接口直出数组）
UPDATE dish SET tags = '["冷链配送","新鲜现切"]' WHERE category = 'meat';
UPDATE dish SET tags = '["农户直供","当日现摘"]' WHERE category = 'vegetable';
UPDATE dish SET tags = '["方便速食","即冲即食"]' WHERE category = 'fastfood';
UPDATE dish SET tags = '["冰镇更佳","正品保障"]' WHERE category = 'wine';
UPDATE dish SET tags = '["产地直采","香甜多汁"]' WHERE category = 'fruit';
UPDATE dish SET tags = '["鲜活直达","破损包赔"]' WHERE category = 'fresh';
UPDATE dish SET tags = '["锁鲜冷链","整箱包邮"]' WHERE category = 'frozen';
UPDATE dish SET tags = '["独立包装","每日一袋"]' WHERE category = 'snack';
UPDATE dish SET tags = '["冷鲜配送","原生高钙"]' WHERE category = 'dairy';
UPDATE dish SET tags = '["当季新粮","颗粒饱满"]' WHERE category = 'grain';
UPDATE dish SET tags = '["臻选组合","精美礼盒"]' WHERE category = 'general';

-- 好评率：'9x.x%'，复刻旧前端公式 93 + (id*13)%70/10（范围 93.0~99.9%）
UPDATE dish SET good_rate = CONCAT(CAST(93 + ((id * 13) % 70) / 10 AS DECIMAL(4,1)), '%');

-- ----------------------------
-- T-M4-05 敏感词：review 加审核状态（命中软词 pending，列表默认不展示）
-- ----------------------------
ALTER TABLE review ADD COLUMN audit_status VARCHAR(20) NULL
    COMMENT '审核状态（T-M4-05）：NULL 正常展示 / pending 命中软词待人工复核（默认不展示）' AFTER reviewer;
