-- ================================================
-- M34 增量迁移（P10-6b 评价带图 A5 片：review_image 表）
-- 契约 docs/十期规划/P10-6-收尾三片与演示数据清理契约.md 片 2 D2。
--
-- 列清单逐字照契约 D2：review_image(id BIGINT AI, review_id BIGINT NOT NULL,
--   file_path VARCHAR(255) NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
--   INDEX idx_review_id)；review 本体零加列（图是新增附着物，红线「review 既有提交/
--   审核语义零改动」的库侧落点）。
--
-- 本期唯一动作（ADD-only：只建新表，不动任何既有表既有列）：
--   CREATE TABLE IF NOT EXISTS review_image（评价图，一评价 ≤3 张，服务端计数拒绝）。
-- 存量行零 UPDATE 零回填：新表无历史数据。
--
-- 幂等写法（跑两次零报错零改动）：
--   · CREATE TABLE IF NOT EXISTS 天然幂等（m28/m33 同款）；
--   · 本文件无 DROP / TRUNCATE / DELETE / 业务 UPDATE，可整文件重复执行。
--
-- 写路径：POST /api/dishes/{dishId}/reviews/{reviewId}/images（登录 + review 归属本人
--   + dish 匹配 + 图数 <3，校验链照 MerchantController#upload：扩展名白名单 → 大小
--   ≤5MB → 魔数 → UUID 落盘名）；删除路径：AdminServiceImpl#auditReview reject 物理删
--   review 行时联动删本表行 + 文件尽力删（契约 D4）。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m34-migration.sql
-- 同步：init.sql 的 review_image 建表体与本文件列定义逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- review_image 评价图（P10-6b D2/m34）
-- ----------------------------
CREATE TABLE IF NOT EXISTS review_image (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价图ID',
  review_id   BIGINT       NOT NULL COMMENT '评价ID（逻辑外键 review.id；评价 reject 物理删行时联动删除，契约 D4）',
  file_path   VARCHAR(255) NOT NULL COMMENT '图片路径（/uploads/reviews/{yyyyMM}/{uuid}.{ext}；类型白名单 jpg/jpeg/png/webp 魔数校验，契约 D1）',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (id),
  INDEX idx_review_id (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评价图（P10-6b D2/m34：一评价≤3张服务端计数拒绝；review 本体零加列）';

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：review_image 列数必须 = 4（契约 D2 四列）
SELECT COUNT(*) AS review_image_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_image';

-- 断言 2：idx_review_id 索引必须到位
SELECT COUNT(*) AS review_image_key_indexes
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_image' AND INDEX_NAME = 'idx_review_id';

-- 断言 3：新表必须 0 行（写路径只有评价图上传端点，本文件零写）
SELECT COUNT(*) AS review_image_rows FROM review_image;

-- 断言 4：review 表未被本片加列（列数保持 9，红线「review 本体零加列」）
SELECT COUNT(*) AS review_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review';
