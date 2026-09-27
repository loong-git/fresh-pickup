-- ================================================
-- M10 增量迁移（F-03 搜索升级：dish 三列 FULLTEXT ngram 索引 + dish_alias 搜索别名表 + 种子别名）
-- ① dish 加 FULLTEXT INDEX ft_dish_search (name, description, tags) WITH PARSER ngram：
--    ngram 为 MySQL 内置中文解析器（默认 ngram_token_size=2，按二元切分），三列正文全文检索
--    替代原 name 单列 LIKE（后端 DishServiceImpl 改造见 F-03.2，接口契约不变）；FULLTEXT 由
--    MySQL 随 DML 自动维护，管理改商品即时生效，无需刷索引（F-03.4）。
-- ② 新表 dish_alias：alias → dish_id 人工维护语义别名（如 洋芋/马铃薯→高山土豆），
--    uk_alias 一别名仅指向一商品；搜索时别名命中的 dish_id 集合以 IN 并入 MATCH 结果（F-03.2）。
-- ③ 种子别名 5 组 8 条（连库核实 28 商品实际名称后落库，INSERT IGNORE 可重跑，对齐 m4/m7/m8 惯例）：
--    除 番茄→本地西红柿(13) 的「番茄」已存在于该商品 description（「番茄味浓郁」，FULLTEXT 本可命中）
--    纯为 DoD 西红柿↔番茄场景保底外，其余 7 条均不在 name/description/tags 中，为纯增量召回词。
-- 仅对已按 init.sql + m3~m9-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m10-migration.sql
-- 可重复执行：FULLTEXT 索引因 MySQL 不支持 ADD INDEX 的 IF NOT EXISTS，以 information_schema.STATISTICS
-- 判断 + PREPARE 动态执行（对齐 m9 惯例），已存在即跳过；新表 CREATE TABLE IF NOT EXISTS；种子 INSERT IGNORE。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① 三列联合全文索引（F-03.1，ngram 内置解析器，中文按二元切分）
-- ----------------------------
SET @m10_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE dish ADD FULLTEXT INDEX ft_dish_search (name, description, tags) WITH PARSER ngram',
        'SELECT ''[m10] ft_dish_search 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish' AND INDEX_NAME = 'ft_dish_search'
);
PREPARE m10_stmt FROM @m10_ddl;
EXECUTE m10_stmt;
DEALLOCATE PREPARE m10_stmt;

-- ----------------------------
-- ② 搜索别名表（F-03.1）：alias → dish_id（如 洋芋/马铃薯→高山土豆）
-- ----------------------------
CREATE TABLE IF NOT EXISTS dish_alias (
    id      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    alias   VARCHAR(50) NOT NULL                COMMENT '搜索别名',
    dish_id BIGINT      NOT NULL                COMMENT '命中商品',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alias (alias),
    KEY idx_dish (dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='搜索别名（F-03）';

-- ----------------------------
-- ③ 种子别名（F-03.1）：连库核实实际商品名后落 5 组语义别名（INSERT IGNORE 可重跑，对齐 m4/m7/m8 惯例）；
--    别名词已对 name/description/tags LIKE 全表核对：除「番茄」外均为三列外纯增量词（见文件头说明）。
-- ----------------------------
INSERT IGNORE INTO dish_alias (alias, dish_id) VALUES
('洋芋',   16),  -- → 高山土豆：云贵高原俗称，库内三列均无此词
('马铃薯', 16),  -- → 高山土豆：学名，库内三列均无此词
('番茄',   13),  -- → 本地西红柿：文档 DoD 西红柿↔番茄场景（description 已含「番茄味」，别名兜底保证精确命中）
('柴鸡蛋', 9),   -- → 散养土鸡蛋：柴鸡蛋俗称，库内三列均无此词
('笨鸡蛋', 9),   -- → 散养土鸡蛋：东北俗称，库内三列均无此词
('泡面',   23),  -- → 老坛酸菜牛肉面五连包：泡面俗称（description 仅有「方便面」），库内三列均无「泡面」
('毛蟹',   6),   -- → 鲜活大闸蟹：中华绒螯蟹俗称，库内三列均无此词
('河蟹',   6);   -- → 鲜活大闸蟹：中华绒螯蟹俗称，库内三列均无此词
