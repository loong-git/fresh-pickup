-- ================================================
-- M11 增量迁移（F-04 邀请赚现金：user 加现金余额/封顶计数两列 + 新表 cash_flow 邀请现金流水）
-- 按 F-04.1 评审修订版（2026-10-03 V1.1）落库，四处修订中与库表相关的两处：
-- ① user 加列 balance DECIMAL(10,2) NOT NULL DEFAULT 0：邀请现金余额。注册只冻结不入账、
--    不产生任何可提现金额（F-04.2），余额仅在新人首单支付后由入账挂钩增加（对齐 F-01.0 防刷裁定）；
--    独立钱包，不与下单抵扣打通（F-04.5）。
-- ② user 加列 cash_rewarded INT NOT NULL DEFAULT 0：已发邀请现金人数计数（封顶原子闸门）——
--    原封顶判定「SELECT COUNT(*) < 10 → INSERT/UPDATE」为 check-then-act，并发注册可绕过 10 人上限，
--    改单条条件 UPDATE（UPDATE user SET cash_rewarded=cash_rewarded+1 WHERE id=? AND cash_rewarded<10）
--    原子闸门（范式同 InviteServiceImpl.java:202-210 的 status 0→1 条件置位，及口径裁定 #4 条件 UPDATE 路线）。
-- ③ 新表 cash_flow：邀请现金流水，type 1=邀请奖励 2=提现；status 承载「冻结→入账」两态
--    （type=1 注册时 status=0 冻结，新人首单支付后 0→1 入账，F-04.2 修订）；idx_invitee 支撑入账按 invitee 检索。
-- 常量 CASH_REWARD_AMOUNT=5.00 / CASH_REWARD_MAX=10 / WITHDRAW_MIN=20.00 为 InviteService 代码常量，无库表种子（同 m9 惯例）。
-- 仅对已按 init.sql + m3~m10-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m11-migration.sql
-- 可重复执行：新表 CREATE TABLE IF NOT EXISTS；两处 ALTER 因 MySQL 不支持 ADD COLUMN 的 IF NOT EXISTS，
-- 以 information_schema 判断 + PREPARE 动态执行（对齐 m9 惯例），已存在即跳过。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① user 加现金余额列（F-04.1 修订版）：注册时不动此列（只冻结不入账），
--    入账挂钩在 cash_flow status 0→1 置位 rows==1 后 balance=balance+5（F-04.2）
-- ----------------------------
SET @m11_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN balance DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''邀请现金余额（F-04）'' AFTER invite_code',
        'SELECT ''[m11] user.balance 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'balance'
);
PREPARE m11_stmt FROM @m11_ddl;
EXECUTE m11_stmt;
DEALLOCATE PREPARE m11_stmt;

-- ----------------------------
-- ② user 加封顶原子闸门计数列（F-04.1 修订版）：freezeCashToInviter 注册事务内条件 UPDATE 自增，
--    rows==0 即已满 10 人封顶，记日志跳过发冻结流水、绑定照常（F-04.2）
-- ----------------------------
SET @m11_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN cash_rewarded INT NOT NULL DEFAULT 0 COMMENT ''已发邀请现金人数计数（封顶原子闸门，F-04）'' AFTER balance',
        'SELECT ''[m11] user.cash_rewarded 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'cash_rewarded'
);
PREPARE m11_stmt FROM @m11_ddl;
EXECUTE m11_stmt;
DEALLOCATE PREPARE m11_stmt;

-- ----------------------------
-- ③ 邀请现金流水表（F-04.1 修订版）：status 列承载「冻结→入账」两态（防并发重复入账靠
--    UPDATE cash_flow SET status=1 WHERE invitee_id=? AND type=1 AND status=0 条件置位）；
--    idx_invitee(user_id 之外) 支撑入账挂钩按 invitee_id 检索冻结流水；
--    amount 恒为正数，type=2 提现为支出方向（记提现时全部余额，balance_after=0，满 20 提全部余额口径）。
-- ----------------------------
CREATE TABLE IF NOT EXISTS cash_flow (
    id            BIGINT        NOT NULL AUTO_INCREMENT            COMMENT '记录ID',
    user_id       BIGINT        NOT NULL                           COMMENT '归属用户（邀请人/提现人）',
    type          TINYINT       NOT NULL                           COMMENT '1=邀请奖励 2=提现',
    status        TINYINT       NOT NULL DEFAULT 1                 COMMENT '1=已入账 0=冻结（type=1 注册时冻结，新人首单支付后 0→1 入账）',
    amount        DECIMAL(10,2) NOT NULL                           COMMENT '正数金额；type=2 提现为支出（记提现时全部余额）',
    balance_after DECIMAL(10,2) NOT NULL                           COMMENT '变动后余额快照',
    invitee_id    BIGINT        NULL                               COMMENT 'type=1 时的新人 user.id',
    remark        VARCHAR(100)  NULL                               COMMENT '备注（如 提现）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id, created_at),
    KEY idx_invitee (invitee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请现金流水（F-04）';
