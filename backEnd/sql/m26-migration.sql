-- ================================================
-- M26 增量迁移（F-116 商品内容审核「先发后审」）
-- 本期唯一动作 = dish 加 2 列（契约 §1 逐字列口径）：
--   · audit_status VARCHAR(20) NOT NULL DEFAULT 'ok' —— 先发后审：发布即上架（现状不变），
--     运营可驳回（rejected，C 端即刻不可见不可买）与恢复（restore，唯一入口=运营端 PUT /api/admin/dishes/{id}/audit）；
--   · audit_reason VARCHAR(200) NULL —— 驳回理由（reject 必填 1~200，restore 清空；商家端展示）。
-- 存量全部 ok（DEFAULT 兜底，无需 UPDATE）；on_sale 与审核态两列正交（驳回不改 on_sale，契约 §2.2）。
--
-- 幂等写法（跑两次零报错、零改动，照 m18 ① 等加列先例的错误码定性写法）：
--   · 以 information_schema.COLUMNS 判 dish.audit_status 是否存在；不存在才执行 ALTER；
--   · 两条子句合并为一条多子句 ALTER（m18 BE-10 裁定：逐列多条 ALTER = 多次表重建；
--     一条 ALTER 原子，判一列即知两列同船已到）；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量写数、无动态 SQL 拼注入面，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m26-migration.sql
-- 同步：init.sql 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① dish 一条 ALTER 加齐 2 列（幂等判定查 dish.audit_status；骨架照 m18 ①：information_schema 判 + PREPARE 动态执行）
-- ----------------------------
SET @m26_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE dish
            ADD COLUMN audit_status VARCHAR(20) NOT NULL DEFAULT ''ok''
              COMMENT ''F-116 先发后审：ok 正常 / rejected 运营驳回（驳回即 C 端不可见不可买；恢复唯一入口=运营端）'',
            ADD COLUMN audit_reason VARCHAR(200) NULL
              COMMENT ''F-116 驳回理由（reject 必填 1~200，restore 清空；商家端展示）''',
        'SELECT ''[m26] dish 审核两列已存在（判 dish.audit_status），跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish' AND COLUMN_NAME = 'audit_status'
);
PREPARE m26_stmt FROM @m26_ddl;
EXECUTE m26_stmt;
DEALLOCATE PREPARE m26_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：两列必须齐（G 口径：dish 加列前实况 20 列 → 22）
SELECT COUNT(*) AS dish_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish';

-- 断言 2：存量行必须全部 audit_status='ok'（DEFAULT 兜底，零 UPDATE；出现非 ok 即本文件之外有人改过）
SELECT COUNT(*) AS audit_status_not_ok FROM dish WHERE audit_status <> 'ok';

-- 断言 3：audit_reason 必须全 NULL（存量零回填）
SELECT COUNT(*) AS audit_reason_not_null FROM dish WHERE audit_reason IS NOT NULL;
