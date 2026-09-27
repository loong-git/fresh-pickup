-- ================================================
-- M5 增量迁移（T-M5 密码登录：POST /api/auth/login-password、POST /api/auth/set-password）
-- 仅对已按 init.sql + m3-migration.sql + m4-migration.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m5-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- T-M5 密码登录：user 加密码哈希列（NULL=未设置过密码，密码登录时引导"请先使用验证码登录"）
-- 存储格式 base64(salt):base64(hash)，PBKDF2WithHmacSHA256（见 com.fresh.common.PasswordUtil），
-- base64 后总长约 69 字符，VARCHAR(128) 为换算法/调参数留余量
-- ----------------------------
ALTER TABLE user ADD COLUMN password_hash VARCHAR(128) NULL
    COMMENT '密码哈希（T-M5）：PBKDF2WithHmacSHA256 存储串 base64(salt):base64(hash)，NULL=未设置过密码' AFTER phone;
