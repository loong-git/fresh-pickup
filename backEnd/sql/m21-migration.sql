-- ================================================
-- M21 增量迁移（F-106 商家自助入驻 · 片 A：后端申请与审核链路）
-- 本期唯一动作 = 新建 merchant_apply 表：申请侧 /api/merchant-applies（登录即可，申请时名下无商家，
--   故入口不能挂 /api/merchant/）、审核侧 /api/admin/merchant-applies/{id}/audit（运营）。
-- 并发范式逐字照 store_apply（m18 ⑦b）：生成列 pending_guard（status='pending' 时为 'p'，其余 NULL）
--   + 唯一键 uk_user_pending(user_id, pending_guard)——NULL 不参与唯一性，封死同用户并发双 pending，
--   rejected/approved 历史行仍放行（驳回后可再次申请）。
-- 审核通过时 merchant.contact_phone 取申请人 user.phone（可信绑定），不取申请表单。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m21-migration.sql
-- 可重复执行：CREATE TABLE IF NOT EXISTS，第二次静默跳过，既有结构与数据零改动。
-- 同步：init.sql 的 merchant_apply 建表体与本文件逐字一致；新库直接走 init.sql 全量重建。
-- ================================================
USE fresh_db;

CREATE TABLE IF NOT EXISTS merchant_apply (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  user_id            BIGINT       NOT NULL COMMENT '申请人 user.id（逻辑外键 user.id；申请时名下无商家，故入口不能挂 /api/merchant/）',
  merchant_name      VARCHAR(100) NOT NULL COMMENT '拟用商家名称',
  contact_name       VARCHAR(50)  NULL COMMENT '联系人姓名',
  business_license   VARCHAR(255) NULL COMMENT '营业执照图 URL（/static/images/uploads/ 前缀，单张）',
  legal_person       VARCHAR(50)  NULL COMMENT '法人姓名',
  address            VARCHAR(255) NULL COMMENT '经营地址',
  settlement_account VARCHAR(64)  NULL COMMENT '结算账户（三期结算启用，列位预留）',
  status             VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending待审核/approved通过/rejected驳回',
  pending_guard      VARCHAR(1) GENERATED ALWAYS AS (IF(status='pending','p',NULL)) STORED COMMENT '并发防护生成列',
  audit_remark       VARCHAR(255) NULL COMMENT '审核意见（驳回原因供申请人展示）',
  audited_at         DATETIME     NULL COMMENT '审核时间；NULL=未审',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_pending (user_id, pending_guard),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家入驻申请';
