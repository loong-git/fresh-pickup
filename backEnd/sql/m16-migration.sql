-- ================================================
-- M16 增量迁移（F-12/G-01 归属层：dish.merchant_id + order_item.merchant_id 快照 + merchant_store 供货关系 + store.owner_user_id）
-- 🔴 动 C 端四链路全量读取核心表（dish/order_item）——必须 dev 全量演练 + C 端四链路冒烟（06 §六.2/§七.1）。
-- ① dish.merchant_id NOT NULL DEFAULT 1：存量 28 SKU 由 DEFAULT 语义自动覆盖=1，零 UPDATE（修正早期草稿「一行 UPDATE 回填」口径）；
--    加列方案否定 dish_merchant 关系表（06 §4.3 论证：一品一供 + C 端四链路零改动硬承诺 + m3~m14 列级演进先例）；
-- ② order_item.merchant_id NULL + JOIN dish 回填 + 孤儿置 1（对齐 orders.store_name 下单快照惯例 init.sql:73-74，
--    防商家改归属/删品后历史履约/对账漂移）；NULL 可空而非 NOT NULL DEFAULT 1——语义正确性优先，回填后收尾 UPDATE 兜底等效 NOT NULL；
-- ③ merchant_store 商家↔自提点供货关系（uk_merchant_store 对齐 free_goods_pool uk_activity_dish 惯例 m8:31；一期数据维护=平台，商家端只读）；
-- ④ store.owner_user_id NULL（NULL=平台直营；存量 4 店零回填；一店一主理人单列够用）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m16-migration.sql
-- 可重复执行：新表 CREATE TABLE IF NOT EXISTS；ALTER/UPDATE 以 information_schema 判断 + PREPARE；回填 UPDATE...WHERE IS NULL 幂等。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① dish 加 merchant_id 列 + idx_merchant（information_schema + PREPARE 幂等，对齐 m9 惯例）
-- ----------------------------
SET @m16_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE dish ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''归属商家（F-12/m16，逻辑外键 merchant.id）；1=平台自营；存量行由 DEFAULT 语义自动覆盖零 UPDATE'' AFTER emoji',
        'SELECT ''[m16] dish.merchant_id 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish' AND COLUMN_NAME = 'merchant_id'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;

SET @m16_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE dish ADD INDEX idx_merchant (merchant_id)',
        'SELECT ''[m16] dish.idx_merchant 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish' AND INDEX_NAME = 'idx_merchant'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;

-- ----------------------------
-- ② order_item 加 merchant_id 列（NULL 可空，快照语义）+ idx_merchant
-- ----------------------------
SET @m16_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE order_item ADD COLUMN merchant_id BIGINT NULL COMMENT ''供货商家快照（F-12/m16，下单时自 dish.merchant_id 落库；NULL=历史行回填兜底）'' AFTER quantity',
        'SELECT ''[m16] order_item.merchant_id 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'merchant_id'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;

-- 回填（对齐 m6-migration.sql:26 UPDATE...WHERE IS NULL 兜底惯例，幂等可重跑）：
UPDATE order_item oi JOIN dish d ON oi.dish_id = d.id
  SET oi.merchant_id = d.merchant_id WHERE oi.merchant_id IS NULL;
-- 孤儿明细兜底（dish 行已被物理删除的历史明细）：
UPDATE order_item SET merchant_id = 1 WHERE merchant_id IS NULL;

SET @m16_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE order_item ADD INDEX idx_merchant (merchant_id)',
        'SELECT ''[m16] order_item.idx_merchant 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND INDEX_NAME = 'idx_merchant'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;

-- ----------------------------
-- ③ merchant_store 商家↔自提点供货关系
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_store (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  merchant_id BIGINT      NOT NULL COMMENT '商家ID（逻辑外键 merchant.id）',
  store_id    BIGINT      NOT NULL COMMENT '自提点ID（逻辑外键 store.id）',
  status      VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active供货中/offline已停供（软停供保历史轨迹）',
  created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '建立时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_store (merchant_id, store_id),
  KEY idx_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家-自提点供货关系（一期数据维护=平台，商家端只读）';

-- ----------------------------
-- ④ store 加 owner_user_id 列 + idx_owner（NULL=平台直营；存量 4 店零回填）
-- ----------------------------
SET @m16_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN owner_user_id BIGINT NULL COMMENT ''主理人 user.id（F-12/m16，逻辑外键）；NULL=平台直营'' AFTER lat',
        'SELECT ''[m16] store.owner_user_id 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'owner_user_id'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;

SET @m16_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE store ADD INDEX idx_owner (owner_user_id)',
        'SELECT ''[m16] store.idx_owner 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND INDEX_NAME = 'idx_owner'
);
PREPARE m16_stmt FROM @m16_ddl;
EXECUTE m16_stmt;
DEALLOCATE PREPARE m16_stmt;
