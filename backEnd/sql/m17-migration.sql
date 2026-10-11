-- ================================================
-- M17 增量迁移（F-18/G-04 Step 1：orders 取货码 + order_item 履约旁路列 + ops_log 现场动作审计）
-- ① orders 加 pickup_code CHAR(6) NULL AFTER pay_time：必须 NULLable（MySQL UNIQUE 允许多行 NULL，
--    uk_client_request_id 已是同型先例，见并库后的 init.sql orders CREATE :91-120）——未支付/已取消单留 NULL；
-- ② uk_pickup_code 全局唯一索引（命名风格 uk_+蛇形，对齐 uk_user_merchant m15:75-84 / uk_merchant_store m16:81 惯例）；
-- ③ 存量回填（D-2 裁定=回填而非留 NULL）：status='pending_pickup' 且 pay_status=1 的存量单按 sha2(id+@m17_salt) 派生 6 位数字码，
--    幂等条件 pickup_code IS NULL（已回填的不动）；随后三条断言 SELECT 即库侧门禁判据（filled=distinct_code、重复码 0 行、still_null_paid=0）；
-- ④ order_item 加 fulfill_status VARCHAR(20) NOT NULL DEFAULT 'normal' AFTER merchant_id（D-10：与订单三态正交的旁路列，
--    不加中间态、不动 status）：存量行靠 DEFAULT 语义自动为 normal，零 UPDATE 零回填；
-- ⑤ ops_log 商家/主理人现场动作审计表（D-5 边界：核销/到货确认/门店信息变更/缺货登记写本表，平台侧动作一律写 m15 admin_audit_log）。
-- 仅对已按 init.sql + m3~m16-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建（init.sql 不做存量回填）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m17-migration.sql
-- 可重复执行：新表 CREATE TABLE IF NOT EXISTS；ALTER 以 information_schema 判断 + PREPARE 动态执行；回填 UPDATE...WHERE pickup_code IS NULL 幂等。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ⓪ 取货码派生盐（D-2）：码 = LPAD(CAST(CONV(SUBSTRING(SHA2(CONCAT(id, @m17_salt), 256), 1, 5), 16, 10) AS UNSIGNED) % 1000000, 6, '0')
--    禁止用订单号尾 6 位（可由单号推出=可猜）。
--    撞唯一键（ERROR 1062 Duplicate entry on uk_pickup_code）时改这一行的值（v1→v2）整文件重跑即可——
--    幂等条件 pickup_code IS NULL 保证已回填成功的行不受影响，只对剩余 NULL 行重新派生。
-- ----------------------------
SET @m17_salt = 'v1';

-- ----------------------------
-- ① orders 加 pickup_code 列（information_schema + PREPARE 幂等，骨架照 m15:50-59 加列原文形态）
-- ----------------------------
SET @m17_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE orders ADD COLUMN pickup_code CHAR(6) NULL COMMENT ''取货码（G-04/m17，M-12）：6 位数字全局唯一，支付置态事务内生成（D-3）；存量已支付待自提单按 sha2(id+盐) 派生回填（D-2）；核销成功后不清空，幂等判定依据；NULL=未支付或已取消'' AFTER pay_time',
        'SELECT ''[m17] orders.pickup_code 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'pickup_code'
);
PREPARE m17_stmt FROM @m17_ddl;
EXECUTE m17_stmt;
DEALLOCATE PREPARE m17_stmt;

-- ----------------------------
-- ② uk_pickup_code 唯一索引（骨架照 m15:75-84 加索引原文形态；NULL 不参与唯一性去重，多行 NULL 合法）
-- ----------------------------
SET @m17_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE orders ADD UNIQUE INDEX uk_pickup_code (pickup_code)',
        'SELECT ''[m17] uk_pickup_code 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'uk_pickup_code'
);
PREPARE m17_stmt FROM @m17_ddl;
EXECUTE m17_stmt;
DEALLOCATE PREPARE m17_stmt;

-- ----------------------------
-- ③ 存量回填（D-2）：幂等 UPDATE...WHERE pickup_code IS NULL，对齐 m16:54-58 回填兜底惯例
-- ----------------------------
UPDATE orders
   SET pickup_code = LPAD(CAST(CONV(SUBSTRING(SHA2(CONCAT(id, @m17_salt), 256), 1, 5), 16, 10) AS UNSIGNED) % 1000000, 6, '0')
 WHERE status = 'pending_pickup' AND pay_status = 1 AND pickup_code IS NULL;

-- 门禁断言 1：两数必须相等（回填出来的码全部互不相同）
SELECT COUNT(*) AS filled, COUNT(DISTINCT pickup_code) AS distinct_code FROM orders WHERE pickup_code IS NOT NULL;

-- 门禁断言 2：必须 0 行；非 0 行=撞车 → 按 D-2 改文件顶部 @m17_salt 整文件重跑
SELECT pickup_code, COUNT(*) c FROM orders WHERE pickup_code IS NOT NULL GROUP BY pickup_code HAVING c > 1;

-- 门禁断言 3：必须 0（已支付待自提的存量单不许留无码，否则核销闭环与「上线即清」都看不到）
SELECT COUNT(*) AS still_null_paid FROM orders WHERE status='pending_pickup' AND pay_status=1 AND pickup_code IS NULL;

-- ----------------------------
-- ④ order_item 加 fulfill_status 列（NOT NULL DEFAULT 'normal'，存量行靠 DEFAULT 自动覆盖，零回填）
--    D-10：status 三态不动，履约细节全走旁路列
-- ----------------------------
SET @m17_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE order_item ADD COLUMN fulfill_status VARCHAR(20) NOT NULL DEFAULT ''normal'' COMMENT ''履约旁路状态（M-19/D-10）：normal/shortage，与订单三态正交；一期唯一写入方=商家缺货登记 PUT /api/merchant/order-items/{id}/shortage'' AFTER merchant_id',
        'SELECT ''[m17] order_item.fulfill_status 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'fulfill_status'
);
PREPARE m17_stmt FROM @m17_ddl;
EXECUTE m17_stmt;
DEALLOCATE PREPARE m17_stmt;

-- ----------------------------
-- ⑤ ops_log 现场动作审计表（列与注释照 05 册 C3 草案 :260-268；D-5 边界见下方表注释）
--    与 admin_audit_log（m15:89-101）的分工：平台侧动作（store_apply 审核、Admin 核销）一律走 admin_audit_log，
--    本表只收商家/主理人现场动作（核销/到货确认/门店信息变更/缺货登记），operator_user_id 为 BIGINT 装不下
--    admin:<key摘要> 字符串，故两表不合并。
-- ----------------------------
CREATE TABLE IF NOT EXISTS ops_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    operator_user_id BIGINT       NOT NULL COMMENT '操作者 userId（语义收窄：商家/主理人；平台操作员不写本表）',
    action           VARCHAR(50)  NOT NULL COMMENT 'verify_order/update_dish/audit_review/...',
    target_id        VARCHAR(64)  NOT NULL COMMENT '目标对象ID',
    detail           VARCHAR(500) NULL     COMMENT '变更摘要',
    created_at       DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_target (action, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营操作审计（D-5 边界：本表只收商家/主理人现场动作——核销/到货确认/门店信息变更/缺货登记；平台侧动作一律走 admin_audit_log）';
