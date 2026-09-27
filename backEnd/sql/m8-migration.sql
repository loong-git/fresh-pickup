-- ================================================
-- M8 增量迁移（满额赠：候选池自选免费商品 + 当日实付满额门槛）
-- ① free_activity 加列 threshold：满额门槛（当日实付满此金额可领，免费单不计入）；
-- ② 新表 free_goods_pool：免费商品候选池（uk_activity_dish 一活动一商品一行），
--    用户领取的 dishId 必须在池内；展示默认免费商品=池内第一行（id 升序=插入序）；
-- ③ 种子：活动 id=1 候选池 6 个在售 dish（优先非秒杀、含蔬菜/水果类，对照库内 dish 现有数据选定：
--    1 精品五花肉/meat、10 泰国金枕榴莲/fruit、13 本地西红柿/vegetable、16 高山土豆/vegetable、
--    20 奶油生菜/vegetable、22 新疆哈密瓜/fruit，均 on_sale=1 且 seckill=0），threshold 置 50.00。
-- 仅对已按 init.sql + m3~m7-migration.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m8-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① 满额门槛列（M8）：DECIMAL(10,2) 与 total_price 同精度，杜绝浮点比价误差；
--    NOT NULL DEFAULT 50.00 存量行一次性带上门槛，代码侧不再判空兜底
-- ----------------------------
ALTER TABLE free_activity
    ADD COLUMN threshold DECIMAL(10,2) NOT NULL DEFAULT 50.00 COMMENT '满额门槛（当日实付满此金额可领）';

-- ----------------------------
-- ② 免费商品候选池表（M8）：uk_activity_dish(activity_id, dish_id) 数据库级去重
--    （同一活动同一商品只入池一次，INSERT IGNORE 可重跑）；池本身不存商品快照，
--    展示/领取时联 dish 取在售数据，下架商品不进下发列表（claim 侧另有 on_sale 校验）
-- ----------------------------
CREATE TABLE IF NOT EXISTS free_goods_pool (
    id          BIGINT NOT NULL AUTO_INCREMENT COMMENT '候选池记录ID',
    activity_id BIGINT NOT NULL                      COMMENT '活动ID（free_activity.id，单活动模型种子固定 1）',
    dish_id     BIGINT NOT NULL                      COMMENT '候选商品ID（dish.id，须 on_sale=1，领取时校验）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_dish (activity_id, dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品候选池表';

-- ----------------------------
-- ③ 种子：活动 id=1 候选池 6 个在售 dish（INSERT IGNORE 可重跑，对齐 m4/m7 惯例）；
--    首行 dish_id=1 精品五花肉 = 默认免费商品（与 free_activity.dish_id=1 同品，页面大卡形象延续）
-- ----------------------------
INSERT IGNORE INTO free_goods_pool (activity_id, dish_id) VALUES
(1, 1),
(1, 10),
(1, 13),
(1, 16),
(1, 20),
(1, 22);

-- 门槛种子：当日实付满 50.00 可领（换门槛直接 UPDATE 本行，代码不写死金额）
UPDATE free_activity SET threshold = 50.00 WHERE id = 1;
