-- ================================================
-- M20 增量迁移（F-49｜admin_audit_log 补两条筛选索引：idx_action (action) + idx_actor_action (actor, action)）
-- 本期唯一动作 = 给既有表 admin_audit_log 加且只加下面这两条二级索引：不加列、不建新表、不改任何一行数据，
--   不改 AdminAuditLogMapper 的任何 SQL 形态、不动任何 Java（依据见下「短路形态不掉索引」那条实测）。
-- 为什么现在做、不再"等表长大"：
--   ① 运营端审计页（front-admin/src/views/AuditLogs.vue，F-45 上线）有四个精确等值筛选框 + 四枚「常用筛选」预设
--      （AuditLogs.vue 的 QUICK_FILTERS：一枚 actor+action、三枚只 action），这四条形态在无索引时全是倒序全表扫。
--   ② 本表只增不删：每一次后台写动作、每一条微信支付回调拒收都会写一行（F-44 起每拒一条落一行
--      actor='system:wx-notify'，写入点 AdminServiceImpl#NOTIFY_REJECT_ACTOR）。
--   ③ 审计页每次查询跑**两条 SQL**——一页 + 同一 WHERE 的独立 COUNT（AdminAuditLogMapper#AUDIT_FILTER_WHERE
--      常量，#selectAuditLogsPage 与 #countAuditLogs 两条 @Select 各自拼它）；
--      COUNT 那一半在没有 action/actor 索引时必然全表。
--   ④ 现状（主会话 SHOW INDEX FROM admin_audit_log 实测）只有三枚：PRIMARY(id)、idx_created(created_at)、
--      idx_target(target_type, target_id)；建表体见 init.sql 的 admin_audit_log（本批并库后含下面这两枚 KEY）。
-- 实测数据与选型（主会话在 scratch 库 scratch_f49：用 init.sql 抽出的同一份建表体建表、灌 50,000 行合成数据，
--   action 取六值均匀分布 → dish.update 命中 8,333 行；下列数字均为该现场原文，非估算、非口号）：
--   · 加索引前：所有带筛选的形态都是 type=index key=PRIMARY extra=Using where; Backward index scan，即倒序全表扫。
--   · 加索引后：action 单筛 → type=ref key=idx_action rows=15410 extra=Backward index scan。
--   · 短路形态不掉索引（本批不改 SQL 的实测依据）：同一 WHERE 的「纯等值写法」与「(#{x} IS NULL OR x = #{x})
--     参数为空短路写法」执行计划**逐字相同**（两者都是 key=idx_action rows=15410）——MySQL 把 'dish.update' IS NULL
--     折叠成 FALSE、把 NULL IS NULL 折叠成 TRUE，剩下的就是 action = 'dish.update'。
--     所以本批不改 SQL、不引动态 <script> WHERE、不动任何 Java。
--   · 同一 WHERE 的 COUNT(*) 跑 20 次：有索引 Handler_read_next=166,660（=20×8,333，只扫该 action 的索引区间）；
--     无索引 Handler_read_rnd_next=1,000,020（=20×50,000，全表）→ 扫描行数 6 倍差。
--   · 深翻页 LIMIT 40000,20 跑 20 次：有索引 544ms / 无索引 857ms。
-- 索引选型 A/B（同样实测，结论落方案二）：
--   · 方案一 idx_action(action) + idx_actor(actor)：actor+action 双筛 → key=idx_actor rows=24564 extra=Using where（回表再滤）。
--   · 方案二 idx_action(action) + idx_actor_action(actor, action)：同一查询 → key=idx_actor_action rows=1，Extra 里没有 Using where。
--   · 方案二下 actor 单筛显示 type=index key=PRIMARY rows=40：那是优化器对「25% 选择率 + ORDER BY id DESC LIMIT 20」
--     主动选了 PK 倒序早停，不是没走索引，也不是缺陷。
--   · 结论：落方案二，只加 idx_action (action) 与 idx_actor_action (actor, action) 两枚。
--     **不再单独加 idx_actor**——idx_actor_action 的最左前缀已覆盖 actor 单筛，第三枚索引对一张只增不删的表是白付写入成本。
-- 适用与同步：仅对已按 init.sql + m3~m19-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
--   两处的索引名与列序逐字一致（idx_action (action)、idx_actor_action (actor, action)），随本文件一并写进
--   init.sql 的 admin_audit_log 建表体（同 m16/m17 的并库先例，两处同步改）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m20-migration.sql
-- 可重复执行：MySQL 的加索引语法**并不支持** IF NOT EXISTS（m10-migration.sql 的 FULLTEXT 那段注释已明写过这一点），
--   故两条索引各以 information_schema.STATISTICS 判断 COUNT(DISTINCT INDEX_NAME) = 0 + PREPARE 动态执行，
--   逐字同构 m16-migration.sql 的 idx_merchant 那组骨架
--   的既有惯例；索引已存在则只打印一条 note 跳过，二次执行不撞 1061、既有结构与数据零改动。
-- 验收提醒（主会话实测踩过的）：加完索引**必须先 ANALYZE TABLE admin_audit_log 再读 EXPLAIN**。
--   主会话第一轮对照就因此误读过——未 ANALYZE 时 `action` 单筛显示 key=PRIMARY（像是"这索引没用"），
--   ANALYZE 之后同一查询稳定走 key=idx_action；拿未刷新的统计去判"索引无效"会把正确的选型否掉。
--   会话变量名与语句名一律用 m20_ 前缀（@m20_ddl / m20_stmt），不复用 m16 的同名——重放整条 m3~m20 时同名会话变量会互相顶掉。
--   本文件无 ADD COLUMN、无 UPDATE 回填、无 CREATE TABLE、无任何 DROP / 清表 / 删行 / 建库动作。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① idx_action (action)：审计页三个「只按 action 筛」的预设与 action 手填框（实测见头部）
-- ----------------------------
SET @m20_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE admin_audit_log ADD INDEX idx_action (action)',
        'SELECT ''[m20] admin_audit_log.idx_action 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log' AND INDEX_NAME = 'idx_action'
);
PREPARE m20_stmt FROM @m20_ddl;
EXECUTE m20_stmt;
DEALLOCATE PREPARE m20_stmt;

-- ----------------------------
-- ② idx_actor_action (actor, action)：actor+action 双筛（「微信回调拒收（系统）」那枚预设）走这一枚；
--    actor 单筛由它的最左前缀 actor 覆盖，故不另建 idx_actor（选型依据见头部 A/B 实测）
-- ----------------------------
SET @m20_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE admin_audit_log ADD INDEX idx_actor_action (actor, action)',
        'SELECT ''[m20] admin_audit_log.idx_actor_action 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_audit_log' AND INDEX_NAME = 'idx_actor_action'
);
PREPARE m20_stmt FROM @m20_ddl;
EXECUTE m20_stmt;
DEALLOCATE PREPARE m20_stmt;
