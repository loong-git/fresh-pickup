-- ================================================
-- M19 增量迁移（F-20/G-06 Step 1：admin_user 运营账号表）
-- 本期唯一动作 = 新建 admin_user 一张表（F-20.2 D-4 钉死的列口径，逐列照抄，不加不减）：
--   ① id BIGINT AUTO_INCREMENT PK + username VARCHAR(50) NOT NULL + UNIQUE uk_admin_username（命名风格 uk_+蛇形，
--      对齐 uk_user_pending m18:⑦b / uk_store_date m18:⑧ / uk_pickup_code m17:② 惯例）；
--   ② password_hash VARCHAR(128) NOT NULL：直接复用 common/PasswordUtil.java 的 PBKDF2WithHmacSHA256 现格式
--      base64(salt):base64(hash)（约 69 字符，与 user.password_hash m5 同列宽，不引入新依赖、不改工具类）；
--   ③ status VARCHAR(20) NOT NULL DEFAULT 'active'（active/disabled 两态）+ last_login_at DATETIME NULL；
--   ④ 无手机号、无邮箱、不与 user 表任何外键关系（D-4/RD：运营域与 C 端域物理隔离，07 册 G-06 范围⑧——
--      不给 user 加 role；user.id 与 admin_user.id 都是 BIGINT 自增，小 id 必然并存，故校验绝不允许只看 subject，D-6）。
-- 仅对已按 init.sql + m3~m18-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建（两处的 CREATE 体逐字一致）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m19-migration.sql
-- 可重复执行（RC 红线）：本文件只有一条 CREATE TABLE IF NOT EXISTS admin_user——二次执行撞 MySQL 1050 告警
--   （Table 'admin_user' already exists，属告警不属错误，EXIT=0 恒成立），既有结构与数据零改动；
--   新表场景无加列、无改索引、无回填，故本文件**不用** information_schema + PREPARE 动态 DDL（那套骨架是 m15/m17/m18
--   为「既有表加列/加键」准备的，在此纯属用不上的重量）；亦无 @mNN_salt（盐是 m17 取货码专用）。
--   无任何 DROP / ALTER 既有表 / 清表 / 删行 / 建库动作（RC 六项全规避）。
-- 🔴 种子策略（F-20.2 D-4 与本期红线，零例外）：本文件与 init.sql 都**不写任何 admin_user 行**，
--   连 dev 默认账号都不建——明文口令入仓是本仓红线（RD），且本表除 uk_admin_username 外无业务唯一键可依凭去重，
--   撞键 IGNORE 只会静默掩盖真实账号，幂等语义反而失真。运营账号由主会话用一次性 SQL 造，口令只在现场输入、
--   不进仓库、不进日志、不进异常消息；本文件末尾的断言 4 因此**期望恒 0 行**，非 0 即有人往迁移里塞了账号。
-- 与 init.sql 的同步关系（G-02 并库先例）：本表的列/约束/COMMENT 与 sql/init.sql 的 admin_user CREATE 体**逐字一致**，
--   两处同步改（同 m17 ops_log / m18 store_apply+arrival_* 的先例写法）；存量库走本文件，新库走 init.sql。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① admin_user 运营账号表（列口径=F-20.2 D-4 逐字；本期 Step 1 只建表 + 实体 + Mapper，
--    登录端点/JWT 双轨/前端一律在 Step 2/3/4，本文件不预置任何账号，见头部种子策略）
-- ----------------------------
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '运营账号ID（BIGINT 自增）；与 user.id 同为自增、小 id 必然并存（D-6：任何鉴权校验绝不允许只看 JWT subject，必须靠 typ=admin claim + 本表存在性 + status=active 双轨判定）',
    username      VARCHAR(50)  NOT NULL COMMENT '运营登录名（唯一键 uk_admin_username）：只用于登录定位账号，非 PII；与 user.phone 的 C 端账号域物理无交叉（D-4）',
    password_hash VARCHAR(128) NOT NULL COMMENT '口令哈希：common/PasswordUtil.java 的 PBKDF2WithHmacSHA256 现格式 base64(salt):base64(hash)（约 69 字符，列宽对齐 user.password_hash m5）；**恒不明文入库、不明文出参**（RD），全仓唯一允许读本列的是 AdminUserMapper.selectByUsername 那一条 @Select',
    display_name  VARCHAR(50)  NULL COMMENT '展示名（管理界面顶部与审计页显示用）；NULL=未设置，前端回退显示 username；不含手机号/邮箱等 PII（D-4/RD）',
    status        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '账号状态：active可用/disabled停用（两态，无第三态）；D-2 双轨校验第②条要求 status=active 才放行运营 JWT，disabled=立即失效（可停用而不删行，留审计轨迹）',
    last_login_at DATETIME     NULL COMMENT '最近登录成功时间（D-5：登录成功链路由 AdminUserMapper.touchLastLogin 写入）；NULL=从未成功登录过；本列是 admin_user 里除 username 外唯一对外可见的时间字段，不再往里塞任何 PII（RD）',
    create_time   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '账号创建时间：DB DEFAULT 落值，应用侧不填（照本仓 created_at/create_time 无 fill 注解的既有纪律，MetaObjectHandler 只填带 @TableField(fill) 的字段）',
    update_time   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时间：DB ON UPDATE 自动维护，改密/停用等动作无需应用侧显式赋值',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营账号（F-20/G-06 Step 1，D-4）：独立于 user 表的运营域账号，零外键零手机号零邮箱；口令仅 PBKDF2 哈希（PasswordUtil 现格式）；**无任何默认种子**，账号由主会话一次性 SQL 造、口令不入仓（RD）；登录动作写 admin_audit_log（D-5/D-8 两表边界，不写 ops_log）';

-- ----------------------------
-- 门禁断言 SELECT（S1-G1/G2/G3 的库侧读数，只读、零副作用）
-- ----------------------------
-- 断言 1：表必须恰 1 行（重复执行时 CREATE TABLE IF NOT EXISTS 走「已存在，跳过」语义——不重建、不报错、结构逐字节不变）
SELECT IF(COUNT(*) = 1,
          '[m19] admin_user 表已在：重复执行走「已存在，跳过」（CREATE TABLE IF NOT EXISTS，不重建不报错）',
          '[m19] 断言失败：admin_user 不存在') AS note
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_user';

-- 断言 2：必须恰 8 列（D-4 列清单 id/username/password_hash/display_name/status/last_login_at/create_time/update_time，不加不减）
SELECT COUNT(*) AS admin_user_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_user';

-- 断言 3：必须恰 1 行且 NON_UNIQUE = 0、key_parts = 1（uk_admin_username 是真唯一键，账号名不可重复）
SELECT NON_UNIQUE, COUNT(*) AS key_parts
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_user' AND INDEX_NAME = 'uk_admin_username'
 GROUP BY NON_UNIQUE;

-- 断言 4：必须 0 行（本文件与 init.sql 均无种子，G3 口径；出现任何行数即有人违反 RD 把账号写进了迁移）
SELECT COUNT(*) AS admin_user_rows FROM admin_user;
