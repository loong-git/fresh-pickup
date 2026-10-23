-- ================================================
-- M22 增量迁移（F-107 多证照扩展）
-- 本期唯一动作 = 给 merchant_apply 与 merchant_profile 各加一列 licenses TEXT NULL：
--   · merchant_apply.licenses：申请人提交的证照列表 JSON 文本（前端序列化后原样上传，服务端只校验不重组）；
--   · merchant_profile.licenses：审核通过时从申请行原样复制，供商家/运营后续读取多证照。
-- 单图 business_license VARCHAR(255) 列与既有读路径一律保留不动（兼容老客户端/老数据）：
--   approve 时 business_license 仍写值，取「第一条 type=business_license 的 url」，解析不出回退申请行原值。
-- 幂等写法逐字照 m15-migration.sql 的 ADD COLUMN 骨架：information_schema.COLUMNS 判存在 +
--   PREPARE 动态执行；列已存在则只打印一条 note 跳过，二次执行不撞 1060、既有结构与数据零改动。
-- 会话变量名与语句名用 m22_ 前缀（@m22_ddl / m22_stmt），不复用 m15/m20 的同名（重放整条 m3~m22 时同名会互相顶掉）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m22-migration.sql
-- 同步：init.sql 的 merchant_profile 与 merchant_apply 两处建表体随本文件各加一列（列位同为表尾，
--   与 ALTER ADD COLUMN 的追加位置一致）；新库直接走 init.sql 全量重建，存量库走本文件。
-- 本文件无 UPDATE 回填、无 CREATE TABLE、无任何 DROP / 清表 / 删行 / 建库动作。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① merchant_apply.licenses：申请侧证照列表（JSON 数组文本）
-- ----------------------------
SET @m22_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE merchant_apply ADD COLUMN licenses TEXT NULL COMMENT ''证照列表 JSON：[{"type":"business_license","url":"/static/images/uploads/x.jpg"}]，最多 6 条''',
        'SELECT ''[m22] merchant_apply.licenses 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_apply' AND COLUMN_NAME = 'licenses'
);
PREPARE m22_stmt FROM @m22_ddl;
EXECUTE m22_stmt;
DEALLOCATE PREPARE m22_stmt;

-- ----------------------------
-- ② merchant_profile.licenses：审核通过时从申请行原样复制
-- ----------------------------
SET @m22_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE merchant_profile ADD COLUMN licenses TEXT NULL COMMENT ''同 merchant_apply.licenses；审核通过时复制''',
        'SELECT ''[m22] merchant_profile.licenses 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_profile' AND COLUMN_NAME = 'licenses'
);
PREPARE m22_stmt FROM @m22_ddl;
EXECUTE m22_stmt;
DEALLOCATE PREPARE m22_stmt;
