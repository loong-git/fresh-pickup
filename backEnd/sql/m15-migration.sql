-- ================================================
-- M15 增量迁移（F-12/G-01 商家化地基：merchant 主体 + merchant_profile 资质 + user 加 role/merchant_id + admin_audit_log 审计）
-- ① merchant：商家主体表，种子显式 id=1「平台自营」（归属列 DEFAULT 1 所指主体必须先存在，对齐 store 种子惯例 init.sql:116-121）；
-- ② merchant_profile：资质/入驻信息 1:1（主键即关系，便于二期多证照扩展）；audit_status 三态复用 review.audit_status 范式 init.sql:161；
-- ③ user 加两列：role VARCHAR(20) NOT NULL DEFAULT 'user'（存量 63 行 ALTER 后自动 role='user'，零 UPDATE 零回填）
--    + merchant_id BIGINT NULL + UNIQUE uk_user_merchant（NULL=非商家；逻辑外键 merchant.id；一人一主体）；
-- ④ admin_audit_log：管理/商家操作审计（M-04 补课，现状日志不记操作者 AdminServiceImpl.java:93）。
-- 仅对已按 init.sql + m3~m14-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m15-migration.sql
-- 可重复执行：新表 CREATE TABLE IF NOT EXISTS；ALTER 以 information_schema 判断 + PREPARE 动态执行；种子 INSERT IGNORE。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① 商家主体表（零物理外键，归属全靠逻辑外键——对齐全库惯例）
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商家ID（平台自营固定 id=1）',
  name          VARCHAR(100) NOT NULL                COMMENT '商家名称',
  contact_phone VARCHAR(20)  NOT NULL                COMMENT '联系手机号（登录号快照；权威绑定在 user.merchant_id，此列仅展示/检索，不设 UNIQUE）',
  contact_name  VARCHAR(50)  NULL                    COMMENT '联系人',
  status        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active正常/suspended停业整顿/terminated清退（执行口径见 06 §4.2「商家生命周期出口执行清单」：on_sale 下架+seckill 清除等六步，非仅 on_sale 熔断）',
  credit_status VARCHAR(20)  NULL                    COMMENT '信用分（三期预留，对齐 review.audit_status 三态范式 init.sql:161；一期 NULL）',
  remark        VARCHAR(255) NULL                    COMMENT '备注',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家主体表（零物理外键，归属全靠逻辑外键——对齐全库惯例）';

-- ----------------------------
-- ② 商家资质/入驻信息（1:1，主体分离便于二期多证照扩展）
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_profile (
  merchant_id       BIGINT       NOT NULL COMMENT '商家ID（逻辑外键 merchant.id，1:1 主键即关系）',
  business_license  VARCHAR(255) NULL     COMMENT '营业执照图 URL（依赖 POST /api/merchant/uploads；VARCHAR(255) 对齐 dish.image 惯例 init.sql:37）',
  legal_person      VARCHAR(50)  NULL     COMMENT '法人姓名',
  address           VARCHAR(255) NULL     COMMENT '经营地址',
  settlement_account VARCHAR(64) NULL     COMMENT '结算账户（三期结算启用；列位预留代价为零）',
  audit_status      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected（复用评价审核三态范式；一期平台代开通=直接 approved）',
  audit_remark      VARCHAR(255) NULL     COMMENT '驳回理由',
  audited_at        DATETIME     NULL     COMMENT '审核时间',
  PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家资质/入驻信息';

-- ----------------------------
-- ③ user 加 role 列（information_schema + PREPARE 幂等，对齐 m9-migration.sql:20-29 惯例）
--    存量 63 行 ALTER 后自动 role='user'，零 UPDATE 零回填
-- ----------------------------
SET @m15_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''user'' COMMENT ''角色（F-12/m15）：user 普通用户 / merchant 商家（admin 预留不启用——平台 Admin 走 X-Admin-Key 独立线）'' AFTER avatar',
        'SELECT ''[m15] user.role 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role'
);
PREPARE m15_stmt FROM @m15_ddl;
EXECUTE m15_stmt;
DEALLOCATE PREPARE m15_stmt;

-- ----------------------------
-- ③ user 加 merchant_id 列 + UNIQUE uk_user_merchant（NULL=非商家；MySQL UNIQUE 允许多行 NULL，与惰性绑定兼容）
-- ----------------------------
SET @m15_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE user ADD COLUMN merchant_id BIGINT NULL COMMENT ''归属商家ID（F-12/m15，逻辑外键 merchant.id）；NULL=非商家；uk_user_merchant 一人一主体'' AFTER role',
        'SELECT ''[m15] user.merchant_id 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'merchant_id'
);
PREPARE m15_stmt FROM @m15_ddl;
EXECUTE m15_stmt;
DEALLOCATE PREPARE m15_stmt;

SET @m15_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE user ADD UNIQUE KEY uk_user_merchant (merchant_id)',
        'SELECT ''[m15] uk_user_merchant 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'uk_user_merchant'
);
PREPARE m15_stmt FROM @m15_ddl;
EXECUTE m15_stmt;
DEALLOCATE PREPARE m15_stmt;

-- ----------------------------
-- ④ 操作审计表（随商家端一期补课，M-04）
-- ----------------------------
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  actor       VARCHAR(64)  NOT NULL COMMENT '操作主体：admin:<key摘要> / merchant:<userId>（现状日志不记操作者，AdminServiceImpl.java:93）',
  action      VARCHAR(50)  NOT NULL COMMENT 'dish.update/order.status/review.audit/merchant.create/deposit.deduct…',
  target_type VARCHAR(30)  NOT NULL COMMENT 'dish/order/review/merchant/deposit',
  target_id   VARCHAR(64)  NOT NULL COMMENT '目标ID（订单号 VARCHAR(50) 口径对齐 init.sql:62）',
  detail      VARCHAR(500) NULL     COMMENT '旧值→新值 JSON',
  ip          VARCHAR(45)  NULL     COMMENT '操作来源 IP（IPv6 容量）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_target (target_type, target_id),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理/商家操作审计';

-- ----------------------------
-- ⑤ 种子：平台自营主体（INSERT IGNORE 对齐 m8:35/m10:49 惯例，可重跑防演练/回滚重放 Duplicate entry）
-- ----------------------------
INSERT IGNORE INTO merchant (id, name, contact_phone, status) VALUES (1, '平台自营', '00000000000', 'active');
INSERT IGNORE INTO merchant_profile (merchant_id, audit_status, audited_at) VALUES (1, 'approved', NOW());
