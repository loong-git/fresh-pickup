-- ================================================
-- M37 增量迁移（P11-3 轮6 R3/R4：orders 加复合索引 idx_store_pickup (store_id, pickup_date)）
-- 动机 = P11-3 性能审计轮6 两项同源：
--   R4 下单热路径容量 COUNT（OrderServiceImpl#createOrder :159-169）：每单一次
--     `WHERE store_id=? AND pickup_date=? AND status<>'cancelled'` 的 orders 全表扫
--     （此前 store_id/pickup_date 无索引、status<>'cancelled' 又是不等值），下单是 C 端最热写路径，
--     十万行级 orders 时每次下单都拖一次全表 COUNT；
--   R3 运营发车两条聚合（OrderItemMapper#selectArrivalPlanByStore/#countArrivalOrdersForStore，
--     :316-339）：F-63 平台视角两条无 merchant_id 归属条件，此前 STRAIGHT_JOIN 钉死 oi
--     先走 idx_merchant 全扫——该索引论证只对商家端 8 条带 merchant_id 的同类成立（保留不动），
--     平台视角本就该 orders 先用 (store_id, pickup_date) 等值裁剪（两侧谓词恰是本索引前两列）。
-- 一处索引同消 R3/R4（issue 清单轮6 修法原文）。
--
-- 本期唯一动作（ADD-only：只加二级索引，不动任何既有列定义，零 UPDATE 零回填）：
--   orders 加 INDEX idx_store_pickup (store_id, pickup_date)
--     —— 左前缀 store_id 等值 + 第二列 pickup_date 等值/范围皆可走；
--     R4 的 COUNT 与 R3 的两条聚合 WHERE 都以这两列等值开头，尾缀 status<>'cancelled'
--     在索引过滤后的小行集上回表判断（大表下回表量≈该店该日单数，量级有界）。
--     store_id 可 NULL（历史无店单），NULL 不进索引等值匹配，与本查询语义一致（store_id 恒等值入参）。
--
-- 幂等写法（跑两次零报错零改动，照 m36 的 information_schema.STATISTICS 判存 + PREPARE 骨架）：
--   · 索引以 information_schema.STATISTICS 判存在性，已存在即跳过；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，可整文件重复执行。
--
-- 门禁断言（照 m36 P3-5 修正后的写法）：COUNT 钉该索引列数恰为 2（防多列/缺列漂移），
--   MIN/MAX(SEQ_IN_INDEX) 钉 seq 集合恰为 {1,2}，逐位 CASE 再钉 seq→列名映射（防两列换位静默漂移）；
--   information_schema.STATISTICS 列名 SEQ_IN_INDEX/COLUMN_NAME 照 MySQL 8.0 实读。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m37-orders-store-pickup-idx.sql
-- 同步：init.sql 的 orders 建表体索引段与本文件同键同列序（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① idx_store_pickup 复合索引（骨架照 m36 ① uk_user_coupon 原文形态；ADD INDEX 非 UNIQUE，无 NULL 唯一性边界）
-- ----------------------------
SET @m37_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE orders ADD INDEX idx_store_pickup (store_id, pickup_date)',
        'SELECT ''[m37] idx_store_pickup 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_store_pickup'
);
PREPARE m37_stmt FROM @m37_ddl;
EXECUTE m37_stmt;
DEALLOCATE PREPARE m37_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：idx_store_pickup 必须已存在，且列序被断言钉死为注释宣称的 (store_id:1, pickup_date:2)——
--   与 m36 门禁同一逐位 CASE 口径：COUNT 钉列数、MIN/MAX 钉 seq 集合、CASE 钉 seq→列名映射
SELECT CONCAT('[m37] orders index check: ',
       IF((SELECT COUNT(*) = 2
                AND MIN(SEQ_IN_INDEX) = 1
                AND MAX(SEQ_IN_INDEX) = 2
                AND MIN(CASE WHEN SEQ_IN_INDEX = 1 THEN COLUMN_NAME END) = 'store_id'
                AND MIN(CASE WHEN SEQ_IN_INDEX = 2 THEN COLUMN_NAME END) = 'pickup_date'
           FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
             AND INDEX_NAME = 'idx_store_pickup'),
           'idx_store_pickup OK (store_id:1, pickup_date:2)',
           'idx_store_pickup MISSING OR COLUMN ORDER WRONG!')) AS note;
