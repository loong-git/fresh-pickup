-- ================================================
-- M7 增量迁移（免费领商品：每日 0 元领限量商品，复用订单链路生成 0 元单并自动支付）
-- 免费领商品活动配置 free_activity（dish_id/daily_quota/daily_date/daily_claimed/status）
-- + 领取记录 free_claim（user_id/dish_id/order_id/claim_date，UNIQUE(user_id, claim_date) 封一人一天）
-- 仅对已按 init.sql + m3~m6-migration.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m7-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- 免费领商品活动表（M7）：当前单活动模型，种子固定 id=1；跨日首次领取由
-- FreeActivityMapper.tryClaimQuota 条件 UPDATE 原子重置 daily_date/daily_claimed（无 COUNT+INSERT 竞态）
-- ----------------------------
CREATE TABLE IF NOT EXISTS free_activity (
    id            BIGINT      NOT NULL AUTO_INCREMENT       COMMENT '活动ID（单活动模型，种子固定 1）',
    dish_id       BIGINT      NOT NULL                      COMMENT '今日免费商品ID（须 on_sale=1；种子指向非秒杀商品，免费领取销量口径见 MockPayService）',
    daily_quota   INT         NOT NULL DEFAULT 100          COMMENT '每日限量份数（>0 才可领，置 0 等效暂停）',
    daily_date    DATE        NULL                          COMMENT '计数器所属日期：与当日一致才累计，NULL/过去日期在首次领取时原子重置',
    daily_claimed INT         NOT NULL DEFAULT 0            COMMENT '当日已领份数（原子条件 UPDATE 自增）',
    status        VARCHAR(20) NOT NULL DEFAULT 'online'     COMMENT '活动状态：online进行/offline下线',
    create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    update_time   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_dish_id (dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品活动表';

-- ----------------------------
-- 免费领商品领取记录表（M7）：uk_user_date(user_id, claim_date) 是"一人一天一份"的权威约束
-- （MySQL UNIQUE 对 NULL 不去重，故 user_id/claim_date 显式 NOT NULL，防刷约束不可空置）；
-- 并发冲突插入报 DuplicateKey → FreeService 转"今日已领取"并回滚整个领取事务（含限量计数/库存/订单）
-- ----------------------------
CREATE TABLE IF NOT EXISTS free_claim (
    id          BIGINT      NOT NULL AUTO_INCREMENT       COMMENT '领取记录ID',
    user_id     BIGINT      NOT NULL                      COMMENT '领取用户ID（token 解析，服务端写入）',
    dish_id     BIGINT      NOT NULL                      COMMENT '领取商品ID',
    order_id    VARCHAR(64) NOT NULL                      COMMENT '关联0元订单号（orders.id 雪花串，建单前预生成回写）',
    claim_date  DATE        NOT NULL                      COMMENT '领取自然日（服务器时区，事务内单次采样）',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP     COMMENT '领取时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, claim_date),
    INDEX idx_claim_date (claim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品领取记录表';

-- 种子活动：INSERT IGNORE 可重跑（对齐 m4-migration.sql 惯例），指向在售非秒杀商品 dish id=1（精品五花肉），
-- 每日限量 100 份；换品/调量直接 UPDATE 本行（运营口径），代码不写死商品
INSERT IGNORE INTO free_activity (id, dish_id, daily_quota, daily_claimed, status) VALUES
(1, 1, 100, 0, 'online');
