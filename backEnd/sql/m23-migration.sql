-- ================================================
-- M23 增量迁移（F-108 字典本地化）
-- 本期唯一动作 = 新建 dict_group / dict_item 两表并回填 4 组 / 23 条词条：
--   · 上游字典服务（GET /api/dict?group=*）当前四组全挂（{"code":400,"message":"字典服务暂时不可用"}），
--     故把 FALLBACK 词表落到本地表，DictServiceImpl#getGroup 改为「本地有词条 → 本地；本地空且组
--     enabled=1 → 回退上游（上游降级为同步源）；组 enabled=0 → 空」；
--   · 回填数据逐条照抄 front/api/dict.js 的 FALLBACK 常量（word/definition/sortOrder 一字不改，
--     其中 coupon_template 的 definition 是 JSON 字符串，SQL 里用单引号包裹、内部双引号原样保留）；
--   · dict_group.upstream_category_id 逐字照 DictServiceImpl.GROUP_ID（dish_category→4、
--     coupon_template→3、kingkong_entry→5、service_tags→2），本片仅在回退上游时读取它。
--
-- 幂等写法（跑两次零报错、零改动）：
--   · 建表一律 CREATE TABLE IF NOT EXISTS —— 二次执行不撞 1050，既有表结构零改动；
--   · 回填一律 INSERT IGNORE —— dict_group 撞主键 code、dict_item 撞唯一键 uk_group_word 即跳过，
--     二次执行零报错；也意味着运营后续改过的 enabled/词条不会被重放覆盖；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量、无动态 SQL，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m23-migration.sql
-- 同步：init.sql 的两处建表体与本文件 DDL 逐字一致（新库走 init.sql 全量重建；
--   词条回填只有本文件一份真源，init.sql 不并入种子，重建后仍需重放 m3~m23）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- 字典分组表（DDL 逐字照契约 §0.2）
-- ----------------------------
CREATE TABLE IF NOT EXISTS dict_group (
  code VARCHAR(32) NOT NULL COMMENT '分组代码（与 DictServiceImpl.GROUP_ID 的键一致）',
  name VARCHAR(50) NOT NULL COMMENT '中文名',
  upstream_category_id INT NULL COMMENT '上游 categoryId（仅同步用；本地有数据时不走上游）',
  sort INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用=该组按空返回，不回退上游）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典分组';

-- ----------------------------
-- 字典词条表（DDL 逐字照契约 §0.2）
-- ----------------------------
CREATE TABLE IF NOT EXISTS dict_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  group_code VARCHAR(32) NOT NULL,
  word VARCHAR(100) NOT NULL COMMENT '显示名',
  definition VARCHAR(500) NULL COMMENT '取值/定义（券组是 JSON 字符串）',
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_group_word (group_code, word),
  KEY idx_group_sort (group_code, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典词条';

-- ----------------------------
-- 分组回填（4 条；name 照 front/api/dict.js 的 GROUP_NAME，upstream_category_id 照 GROUP_ID；
-- sort 为运营端展示顺序预留位，本片不消费）
-- ----------------------------
INSERT IGNORE INTO dict_group (code, name, upstream_category_id, sort, enabled) VALUES
('dish_category',   '商品分类',     4, 10, 1),
('coupon_template', '优惠券配置',   3, 20, 1),
('kingkong_entry',  '金刚区入口',   5, 30, 1),
('service_tags',    '服务保障标签', 2, 40, 1);

-- ----------------------------
-- 词条回填（合计 23 条 = 11 + 3 + 5 + 4，逐条照抄 front/api/dict.js 的 FALLBACK）
-- ----------------------------
-- dish_category 11 条
INSERT IGNORE INTO dict_item (group_code, word, definition, sort_order) VALUES
('dish_category', '肉蛋水产', 'code:meat',      110),
('dish_category', '新鲜蔬菜', 'code:vegetable', 100),
('dish_category', '速食',     'code:fastfood',   90),
('dish_category', '酒水',     'code:wine',       80),
('dish_category', '水果',     'code:fruit',      70),
('dish_category', '生鲜',     'code:fresh',      60),
('dish_category', '速冻',     'code:frozen',     50),
('dish_category', '零食',     'code:snack',      40),
('dish_category', '乳饮',     'code:dairy',      30),
('dish_category', '粮油',     'code:grain',      20),
('dish_category', '百货',     'code:general',    10);

-- coupon_template 3 条（definition 为 JSON 字符串，内部双引号原样保留）
INSERT IGNORE INTO dict_item (group_code, word, definition, sort_order) VALUES
('coupon_template', '满19减4',  '{"threshold":19,"amount":4,"state":"available"}',  30),
('coupon_template', '满30减5',  '{"threshold":30,"amount":5,"state":"locked1"}',    20),
('coupon_template', '满50减21', '{"threshold":50,"amount":21,"state":"locked2"}',   10);

-- kingkong_entry 5 条
INSERT IGNORE INTO dict_item (group_code, word, definition, sort_order) VALUES
('kingkong_entry', '超级秒杀',   'code:seckill',       50),
('kingkong_entry', '免费领商品', 'code:free',          40),
('kingkong_entry', '领券中心',   'code:coupon_center', 30),
('kingkong_entry', '邀请赚现金', 'code:invite',        20),
('kingkong_entry', '其他',       'code:more',          10);

-- service_tags 4 条
INSERT IGNORE INTO dict_item (group_code, word, definition, sort_order) VALUES
('service_tags', '坏了包退', 'code:refund_bad',         40),
('service_tags', '晚到必赔', 'code:late_pay',           30),
('service_tags', '极速退款', 'code:fast_refund',        20),
('service_tags', '缺货退款', 'code:out_of_stock_refund', 10);
