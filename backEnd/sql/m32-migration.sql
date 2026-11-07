-- ================================================
-- M32 增量迁移（P10-5 轨道 B 模拟转正 B2 片：settlement 打款状态机两列 + partner_wallet_flow 提现类型）
-- 契约 docs/十期规划/P10-5-轨道B模拟转正契约.md D5/D6/D9；08 册挂账「paid 态不实现」由本片解除。
--
-- 编号登记：契约 D9 原名 m31-migration.sql，但 m31 已被 F-124（review.client_request_id，e72ec70）
-- 占用（2026-10-29 提交，早于契约落笔 2026-11-06），本迁移照顺延纪律落为 m32；
-- B3（m1x 保证金，契约原定 m32）据此顺延为 m33。
--
-- 本期唯一动作（ADD-only：加列只加不改既有列定义；唯一一处既有列改动是 biz_type 的 COMMENT 元数据，
-- 契约 D6/任务书明许「类型列是 VARCHAR 直接可用则只改注释」——数据零变化）：
--   ① settlement 加 2 列：
--      · paid_time   DATETIME     NULL —— 模拟轨打款时间：「确认打款」条件 UPDATE（status='accounted'
--        AND paid_time IS NULL → status='paid'）写入 NOW()；06 册纸面预留列 paid_at（打款时间）/
--        payout_voucher（微信商家转账凭证）留真实轨不启用，两套列不混写；
--      · pay_channel VARCHAR(20) NULL —— 打款通道，模拟轨恒 'MOCK_SIM'（显式 MOCK 标注：无真实
--        资金流，杜绝「演示冒充结算」；真实轨另行登记通道值）。
--   ② partner_wallet_flow.biz_type 类型注释补 withdraw（D6 主理人提现）：
--      biz_type 本是 VARCHAR(20) 无 ENUM/CHECK 约束的普通列——值空间由写侧常量收口
--      （PartnerWalletFlow.BIZ_WITHDRAW），列上无需任何 DDL 级约束，登记处理方式=仅改列注释。
-- 存量行零 UPDATE 零回填：paid_time/pay_channel 全 NULL（=未打款语义，与既有行 accounted 态一致）；
-- biz_type 注释改写对数据零影响（既有 commission_grant/commission_reversal 行原样）。
--
-- 幂等写法（跑两次零报错零改动，照 m30-migration.sql 的 information_schema 判存 + PREPARE 骨架）：
--   · 两列以 information_schema.COLUMNS 判存在性，已存在即跳过；
--   · biz_type 注释以 COLUMN_COMMENT 判是否已含 withdraw 关键字，已改即跳过；
--   · 本文件无 DROP / TRUNCATE / DELETE / 业务 UPDATE，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m32-migration.sql
-- 同步：init.sql 的 settlement / partner_wallet_flow 建表体与本文件逐字一致（新库走 init.sql
--       全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① settlement 加 paid_time（模拟轨打款时间；幂等判定查 paid_time，骨架照 m30 ①）
-- ----------------------------
SET @m32_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE settlement ADD COLUMN paid_time DATETIME NULL COMMENT ''模拟轨打款时间（P10-5 D5/m32）：「确认打款」条件 UPDATE（status=accounted 且本列 IS NULL → status=paid）写入 NOW()；06 册纸面预留列 paid_at/payout_voucher 留真实轨不启用，两套列不混写'' AFTER payout_voucher',
        'SELECT ''[m32] settlement.paid_time 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement' AND COLUMN_NAME = 'paid_time'
);
PREPARE m32_stmt FROM @m32_ddl;
EXECUTE m32_stmt;
DEALLOCATE PREPARE m32_stmt;

-- ----------------------------
-- ① settlement 加 pay_channel（模拟通道显式 MOCK 标注；幂等判定查 pay_channel）
-- ----------------------------
SET @m32_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE settlement ADD COLUMN pay_channel VARCHAR(20) NULL COMMENT ''打款通道（P10-5 D5/m32）：模拟轨恒 MOCK_SIM——显式 MOCK 标注，无真实资金流，防「演示冒充结算」；真实轨另行登记通道值'' AFTER paid_time',
        'SELECT ''[m32] settlement.pay_channel 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement' AND COLUMN_NAME = 'pay_channel'
);
PREPARE m32_stmt FROM @m32_ddl;
EXECUTE m32_stmt;
DEALLOCATE PREPARE m32_stmt;

-- ----------------------------
-- ② partner_wallet_flow.biz_type 列注释补 withdraw（D6：类型列无 ENUM/CHECK 约束，仅改元数据；
--    幂等判定查 COLUMN_COMMENT 是否已含 withdraw，已改即跳过；列类型/约束逐字保持 VARCHAR(20) NOT NULL）
-- ----------------------------
SET @m32_ddl = (
    SELECT IF(COLUMN_COMMENT LIKE '%withdraw%',
        'SELECT ''[m32] partner_wallet_flow.biz_type 注释已含 withdraw，跳过'' AS note',
        'ALTER TABLE partner_wallet_flow MODIFY COLUMN biz_type VARCHAR(20) NOT NULL COMMENT ''commission_grant 佣金入账 / commission_reversal 佣金冲回 / withdraw 提现（P10-5 D6/m32：负向即时入账，余额条件扣减，通道 MOCK_SIM 标注在 remark；订单锚位放提现单号 WD+雪花；写侧常量收口 PartnerWalletFlow）''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'partner_wallet_flow' AND COLUMN_NAME = 'biz_type'
);
PREPARE m32_stmt FROM @m32_ddl;
EXECUTE m32_stmt;
DEALLOCATE PREPARE m32_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：settlement 列数必须 = 20（迁移前实测 18 + 本片 2；dev DB 2026-11-07 执行前 SELECT 复核为 18）
SELECT COUNT(*) AS settlement_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement';

-- 断言 2：settlement 既有行未打款语义保持——存量行零回填（出现非 NULL 即本文件之外有人写过）
SELECT COUNT(*) AS settlement_paid_time_not_null FROM settlement WHERE paid_time IS NOT NULL;
SELECT COUNT(*) AS settlement_pay_channel_not_null FROM settlement WHERE pay_channel IS NOT NULL;

-- 断言 3：biz_type 注释已含 withdraw 关键字（1=已生效）
SELECT IF(COLUMN_COMMENT LIKE '%withdraw%', 1, 0) AS biz_type_comment_has_withdraw
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'partner_wallet_flow' AND COLUMN_NAME = 'biz_type';
