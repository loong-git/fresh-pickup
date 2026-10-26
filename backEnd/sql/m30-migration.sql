-- ================================================
-- M30 增量迁移（F-122 封禁体系：user.status + 登录拒/旧 token 即时失效 + 运营封禁端点）
-- 本期唯一动作（契约 §2.1 逐字）：
--   · user 加 1 列 status VARCHAR(20) NOT NULL DEFAULT 'active'
--     —— active 正常 / banned 封禁；封禁即登录拒（两路登录 403「账号已被封禁」），
--     且已签发 token 在全部需登录请求上 403（AuthInterceptor 每请求查一次本列）。
-- 存量行由 DEFAULT 自动 active，零 UPDATE、零回填；列物理位置在表尾（ALTER 无 AFTER 子句）。
--
-- 商家封禁：复用既有 merchant.status='suspended'（active/suspended/terminated 三态），
-- **本片对 merchant 表零 DDL、零注释改动**——不新增枚举值、不加列、不动既有列注释
-- （存量库 COMMENT 不可达债见契约 §7，不在本文件扩大）。
--
-- 幂等写法（跑两次零报错、零改动，照 m26-migration.sql 的 information_schema 判存 + PREPARE 骨架）：
--   · 以 information_schema.COLUMNS 判 user.status 是否存在；不存在才执行 ALTER；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量写数、无动态 SQL 拼注入面，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m30-migration.sql
-- 同步：init.sql 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① user 加 status 一列（幂等判定查 user.status；骨架照 m26 ①）
-- ----------------------------
SET @m30_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `user`
            ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''active''
              COMMENT ''F-122 封禁体系：active正常/banned封禁（封禁即登录拒 + 已签发 token 的需登录请求 403）''',
        'SELECT ''[m30] user.status 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'status'
);
PREPARE m30_stmt FROM @m30_ddl;
EXECUTE m30_stmt;
DEALLOCATE PREPARE m30_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：user 列数必须 = 11（迁移前实测 10）
SELECT COUNT(*) AS user_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user';

-- 断言 2：迁移时刻非 active 行必须 = 0（DEFAULT 兜底，零 UPDATE；出现非 active 即本文件之外有人改过）
SELECT COUNT(*) AS user_status_not_active FROM `user` WHERE status <> 'active';

-- 断言 3：merchant 列数必须 = 9（证明 merchant 零 DDL，迁移前实测 9）
SELECT COUNT(*) AS merchant_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant';
