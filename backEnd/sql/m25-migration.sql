-- ================================================
-- M25 增量迁移（F-115 秒杀场次契约化）
-- 本期唯一动作 = 字典回填 1 组 / 2 条（纯 INSERT，无 DDL / 无 UPDATE / 无 DELETE，无新表无新列）：
--   · SeckillController 场次从常量 SESSION_START/SESSION_END 改读字典组 seckill_session
--     （契约 §2.1）：本回填的 definition 与常量时代逐字一致（start='00:00' / end='23:00'），
--     存量库跑完迁移 C 端 /api/seckill/current 出参逐字不变，向后兼容；
--   · upstream_category_id=NULL 是刻意的：纯本地组，DictServiceImpl.GROUP_ID 无此键，
--     本地词条在时读路径不回退上游；syncFromUpstream 对它恒 400「该分组没有上游来源」，
--     杜绝被上游同步覆盖场次值；
--   · 运营写入口（AdminServiceImpl）对该组特判校验：word 仅 start/end、definition 须 HH:MM、
--     写入后 start<end（契约 §2.2），本迁移的种子值天然合法。
--
-- 幂等写法（跑两次零报错、零改动，契约 §1 点名 INSERT ... SELECT WHERE NOT EXISTS）：
--   · dict_group 撞主键 code、dict_item 撞唯一键 uk_group_word(group_code, word) 的行先被
--     NOT EXISTS 探掉；即运营后续改过的 definition 不会被重放覆盖；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量、无动态 SQL，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m25-migration.sql
-- 同步：init.sql 同段回填与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- 分组回填（1 行；列名照 m23-migration.sql 建表体：code/name/upstream_category_id/sort/enabled。
-- sort=50 排在既有四组 10/20/30/40 之后，仅运营端展示用，读路径不消费）
-- ----------------------------
INSERT INTO dict_group (code, name, upstream_category_id, sort, enabled)
SELECT 'seckill_session', '秒杀场次', NULL, 50, 1
WHERE NOT EXISTS (SELECT 1 FROM dict_group WHERE code = 'seckill_session');

-- ----------------------------
-- 词条回填（2 行；word/definition 与原 SeckillController 常量逐字一致，sort_order 任意合理值——
-- 读路径 loadLocal 按 sort_order DESC, id ASC 排序，本组两条顺序不参与任何语义）
-- ----------------------------
INSERT INTO dict_item (group_code, word, definition, sort_order)
SELECT 'seckill_session', 'start', '00:00', 20
WHERE NOT EXISTS (SELECT 1 FROM dict_item WHERE group_code = 'seckill_session' AND word = 'start');

INSERT INTO dict_item (group_code, word, definition, sort_order)
SELECT 'seckill_session', 'end', '23:00', 10
WHERE NOT EXISTS (SELECT 1 FROM dict_item WHERE group_code = 'seckill_session' AND word = 'end');
