-- ================================================
-- M14 增量迁移（F-06.7.4 R8 用户头像：user 加 avatar 列）
-- 契约：docs/上线任务文档.md F-06.7.4 R8 第 1 条——
--   ALTER TABLE user ADD COLUMN avatar TEXT NULL COMMENT '头像（data URI，R8）'
-- 头像存 data:image/(jpeg|png|webp);base64, 前缀的 data URI（前端 canvas 128×128 JPEG 0.85 压缩，
-- 字符长度上限 60000 由 POST /api/user/avatar 服务端校验——与列 TEXT 65535 字节容量对齐，
-- 契约原稿 300000 为笔误已修正（核验 B②，2026-10-06）），NULL=未设置（前端兜默认 SVG 头像）。
-- 仅对已按 init.sql + m3~m13-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m14-migration.sql
-- 可重复执行：MySQL 不支持 ADD COLUMN 的 IF NOT EXISTS，
-- 以 information_schema 判断 + PREPARE 动态执行（对齐 m9/m11 惯例），已存在即跳过。
-- ================================================
USE fresh_db;

-- ----------------------------
-- user 加头像列（R8）：TEXT 与契约原文一致（不擅自升 MEDIUMTEXT）。
-- 口径：服务端校验上限 60000 字符 < TEXT 列 65535 字节容量——前端 fileToAvatarDataURI
-- 固定输出 128×128 JPEG 0.85（实测量级 5~30KB，远低于 60000），正常链路不会触顶；
-- 原「300000 校验上限」为契约笔误已修正（核验 B②，2026-10-06），500 边界段随之作废删除。
-- ----------------------------
SET @m14_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN avatar TEXT NULL COMMENT ''头像（data URI，R8）'' AFTER cash_rewarded',
        'SELECT ''[m14] user.avatar 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'avatar'
);
PREPARE m14_stmt FROM @m14_ddl;
EXECUTE m14_stmt;
DEALLOCATE PREPARE m14_stmt;
