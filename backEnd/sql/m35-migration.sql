-- ================================================
-- M35 增量迁移（P11-1a 商家回复评价：review 加回复三列）
-- 契约 docs/十一期规划/P11-1-商家回复评价与批量审核契约.md 片 1 D1。
--
-- 本期唯一动作（ADD-only：只加新列，不动任何既有列定义，零 UPDATE 零回填）：
--   review 加 3 列（一评一回，重复回复=覆盖更新 UPDATE 三列保留最新一条，契约 D1）：
--     · reply_content VARCHAR(500) NULL —— 商家回复内容，入库前 HTML 转义（照评价 content
--       同款防存储型 XSS，ReviewServiceImpl#addReview 先例）；评价行被 reject 物理删时随行消失（同表同行）；
--     · reply_time DATETIME NULL —— 商家回复时间，每次回复写入 NOW()；NULL=未回复；
--     · replyer VARCHAR(50) NULL —— 回复人=商家主体名，服务端按 merchant.name 落库，不收前端直传
--       （照评价 reviewer 服务端生成口径）；NULL=未回复。
-- 存量行零 UPDATE 零回填：三列全 NULL（=未回复语义，与既有全部评价行一致）。
--
-- 幂等写法（跑两次零报错零改动，照 m32-migration.sql 的 information_schema 判存 + PREPARE 骨架）：
--   · 三列以 information_schema.COLUMNS 判存在性，已存在即跳过；
--   · 本文件无 DROP / TRUNCATE / DELETE / 业务 UPDATE，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m35-migration.sql
-- 同步：init.sql 的 review 建表体与本文件列定义逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① review 加 reply_content（幂等判定查 reply_content）
-- ----------------------------
SET @m35_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE review ADD COLUMN reply_content VARCHAR(500) NULL COMMENT ''商家回复内容（P11-1a D1/m35）：一评一回，重复回复覆盖更新保留最新一条；入库前 HTML 转义照评价 content 同款防存储型 XSS；评价行 reject 物理删时随行消失（同表同行）'' AFTER audit_status',
        'SELECT ''[m35] review.reply_content 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'reply_content'
);
PREPARE m35_stmt FROM @m35_ddl;
EXECUTE m35_stmt;
DEALLOCATE PREPARE m35_stmt;

-- ----------------------------
-- ② review 加 reply_time（幂等判定查 reply_time）
-- ----------------------------
SET @m35_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE review ADD COLUMN reply_time DATETIME NULL COMMENT ''商家回复时间（P11-1a D1/m35）：每次回复写入 NOW()；NULL=未回复'' AFTER reply_content',
        'SELECT ''[m35] review.reply_time 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'reply_time'
);
PREPARE m35_stmt FROM @m35_ddl;
EXECUTE m35_stmt;
DEALLOCATE PREPARE m35_stmt;

-- ----------------------------
-- ③ review 加 replyer（幂等判定查 replyer）
-- ----------------------------
SET @m35_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE review ADD COLUMN replyer VARCHAR(50) NULL COMMENT ''回复人=商家主体名（P11-1a D1/m35）：服务端按 merchant.name 落库，不收前端直传（照评价 reviewer 服务端生成口径）；NULL=未回复'' AFTER reply_time',
        'SELECT ''[m35] review.replyer 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'replyer'
);
PREPARE m35_stmt FROM @m35_ddl;
EXECUTE m35_stmt;
DEALLOCATE PREPARE m35_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：review 列数必须 = 12（迁移前实测 9 + 本片 3；m34 门禁断言 4 的「列数 9」是 m34 执行时点状态，本片 ADD-only 加 3 列演进为 12）
SELECT COUNT(*) AS review_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review';

-- 断言 2：三新列类型/可空性逐字核对（契约 D1：VARCHAR(500) NULL / DATETIME NULL / VARCHAR(50) NULL）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review'
   AND COLUMN_NAME IN ('reply_content', 'reply_time', 'replyer')
 ORDER BY ORDINAL_POSITION;

-- 断言 3：存量行零回填（出现非 NULL 即本文件之外有人写过；三列由回复端点按需写）
SELECT COUNT(*) AS review_reply_content_not_null FROM review WHERE reply_content IS NOT NULL;
SELECT COUNT(*) AS review_reply_time_not_null FROM review WHERE reply_time IS NOT NULL;
SELECT COUNT(*) AS review_replyer_not_null FROM review WHERE replyer IS NOT NULL;
