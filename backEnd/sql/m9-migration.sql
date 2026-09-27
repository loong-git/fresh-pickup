-- ================================================
-- M9 增量迁移（F-01 邀请新人：user 加邀请码列 + 新表 invite_relation 绑定/首单奖励记录）
-- ① user 加列 invite_code VARCHAR(8) NULL + 唯一键 uk_user_invite_code：
--    邀请码惰性生成（首次 GET /api/invite/me 生成写回），存量用户不回填、保持 NULL（F-01.0 裁定）；
--    8 位去混淆字符集（去除 0/O/1/I）；UNIQUE 允许多行 NULL，与惰性生成兼容；
-- ② 新表 invite_relation：验证码登录建新号同一事务写入绑定关系，uk_invitee 一人仅可被邀请一次；
--    status 0→1 由新人首单支付成功后原子置位（影响行数=1 才发邀请人奖励券，防并发重复发券）；
--    invitee_phone 为脱敏展示快照（138****1235），邀请链接只含邀请码，无手机号枚举面（F-01.0 攻击面裁定）。
-- 奖励券模板 invite_reward[50,15] / newbie_gift[30,8] 为代码常量（CouponServiceImpl.TEMPLATES），无库表种子。
-- 仅对已按 init.sql + m3~m8-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m9-migration.sql
-- 可重复执行：新表 CREATE TABLE IF NOT EXISTS；两处 ALTER 因 MySQL 不支持 ADD COLUMN/ADD INDEX
-- 的 IF NOT EXISTS，以 information_schema 判断 + PREPARE 动态执行，已存在即跳过。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① user 加邀请码列（F-01）：NULL=尚未生成（惰性生成，见 F-01.0），追加在 created_at 之后
-- ----------------------------
SET @m9_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN invite_code VARCHAR(8) NULL COMMENT ''邀请码（F-01）：8 位去混淆字符集（无 0/O/1/I），首次 GET /api/invite/me 时惰性生成；NULL=尚未生成'' AFTER created_at',
        'SELECT ''[m9] user.invite_code 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'invite_code'
);
PREPARE m9_stmt FROM @m9_ddl;
EXECUTE m9_stmt;
DEALLOCATE PREPARE m9_stmt;

-- ----------------------------
-- ① 唯一键 uk_user_invite_code：DB 层兜底防惰性生成竞态重码（应用侧生成后查重写回，见 F-01.2）
-- ----------------------------
SET @m9_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE user ADD UNIQUE KEY uk_user_invite_code (invite_code)',
        'SELECT ''[m9] uk_user_invite_code 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'uk_user_invite_code'
);
PREPARE m9_stmt FROM @m9_ddl;
EXECUTE m9_stmt;
DEALLOCATE PREPARE m9_stmt;

-- ----------------------------
-- ② 邀请新人关系表（F-01）：uk_invitee 是"一人仅可被邀请一次"的权威约束
--    （invitee_id 显式 NOT NULL：MySQL UNIQUE 对 NULL 不去重，防刷约束不可空置，对齐 m7 free_claim 惯例）
-- ----------------------------
CREATE TABLE IF NOT EXISTS invite_relation (
    id            BIGINT      NOT NULL AUTO_INCREMENT            COMMENT '记录ID',
    inviter_id    BIGINT      NOT NULL                           COMMENT '邀请人 user.id',
    invitee_id    BIGINT      NOT NULL                           COMMENT '被邀新人 user.id（uk_invitee 一人仅可被邀请一次）',
    invitee_phone VARCHAR(20) NOT NULL                           COMMENT '被邀人手机号快照（对外仅脱敏展示 138****1235，见 F-01.0）',
    status        TINYINT     NOT NULL DEFAULT 0                 COMMENT '0=已注册未完成首单 1=已完成首单（奖励已处理）',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间（验证码登录建号同事务写入）',
    rewarded_at   DATETIME    NULL                               COMMENT '邀请人奖励发放时间（首单支付置位 status=1 时写入；NULL=未发奖）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitee (invitee_id),
    KEY idx_inviter (inviter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请新人关系（F-01）';
