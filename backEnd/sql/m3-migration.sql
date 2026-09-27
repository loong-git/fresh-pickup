-- ================================================
-- M3 增量迁移（T-M3-12 / T-M3-01 / T-M3-04 / T-M3-09）
-- 仅对已按 init.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m3-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- T-M3-12 分类对齐字典 11 类 code（按商品名称语义重分类，无孤儿）
-- 对齐 front/api/dict.js 分类语义：meat=肉蛋水产 vegetable=新鲜蔬菜 fastfood=速食
-- wine=酒水 fruit=水果 fresh=生鲜(鲜活水产/豆制品) frozen=速冻 snack=零食 dairy=乳饮 grain=粮油 general=百货
-- ----------------------------
-- category 列注释同步 11 类 code 说明
ALTER TABLE dish MODIFY COLUMN category VARCHAR(20) NOT NULL
    COMMENT '分类（T-M3-12 对齐字典 code）：meat/vegetable/fastfood/wine/fruit/fresh/frozen/snack/dairy/grain/general';
-- 3 澳洲肥牛卷：描述明确 -18°C 冷冻保存 → 速冻
UPDATE dish SET category = 'frozen' WHERE id = 3;
-- 6 鲜活大闸蟹 / 7 深海鲈鱼 / 11 阿根廷红虾：鲜活水产 → 生鲜
UPDATE dish SET category = 'fresh' WHERE id IN (6, 7, 11);
-- 10 泰国金枕榴莲 / 22 新疆哈密瓜：水果
UPDATE dish SET category = 'fruit' WHERE id = 10;
UPDATE dish SET category = 'fruit' WHERE id = 22;
-- 14 嫩豆腐：现磨豆制品 → 生鲜（蔬菜语义不含豆腐）
UPDATE dish SET category = 'fresh' WHERE id = 14;
-- 保持不变：1/2/4/5/8/9=meat（五花肉/鸡胸/排骨/梅花肉/林下参/土鸡蛋），12/13/15~21=vegetable

-- ----------------------------
-- T-M3-01 真实图片字段：image 收窄对齐口径 VARCHAR(255) NULL
-- （存量列为 VARCHAR(500) DEFAULT ''，现值全空，收窄无损）
-- ----------------------------
ALTER TABLE dish MODIFY COLUMN image VARCHAR(255) NULL DEFAULT NULL
    COMMENT '图片URL（T-M3-01：/static/images/dish-<id>.svg，空值前端回退 emoji 占位）';
-- 种子图：id 1~11 由前端 SVG 占位图脚本生成同名文件
UPDATE dish SET image = CONCAT('/static/images/dish-', id, '.svg') WHERE id BETWEEN 1 AND 11;

-- ----------------------------
-- T-M3-09 在售开关
-- ----------------------------
ALTER TABLE dish ADD COLUMN on_sale TINYINT NOT NULL DEFAULT 1
    COMMENT '是否在售（T-M3-09）：1上架/0下架' AFTER stock;

-- ----------------------------
-- T-M3-04 自提日期：下单服务端按服务器时间裁决（<23:00 明天 / >=23:00 后天）
-- ----------------------------
ALTER TABLE orders ADD COLUMN pickup_date DATE NULL
    COMMENT '自提日期（T-M3-04，服务端裁决：<23:00 明天 / >=23:00 后天）' AFTER phone;

-- ----------------------------
-- T-M3-12 补齐缺失类目种子（fastfood/wine/dairy/snack/grain/general 各 1，
-- 现有 22 商品语义无法归入这 6 类，保证 11 类每类至少 1 商品）
-- ----------------------------
INSERT INTO dish (name, price, image, description, category, unit, stock, emoji, bg_color) VALUES
('老坛酸菜牛肉面五连包', 12.90, '', '【经典速食】老坛酸菜风味方便面，酸爽开胃，面饼劲道弹滑，料包酸菜脆嫩足量。\n\n【烹饪推荐】热水冲泡3-5分钟即可食用，加个鸡蛋或几片青菜更营养；也可煮食，汤面更浓郁。\n\n【储存建议】常温干燥处保存，保质期6个月。', 'fastfood', '包', 60, '🍜', '#FFF8E7'),
('精酿白啤500ml×6罐', 39.90, '', '【德式工艺】全麦芽精酿白啤，酒体浑浊呈金黄色，泡沫细腻持久，带丁香与香蕉酯香。\n\n【饮用建议】冰镇后饮用口感最佳，佐餐海鲜、烧烤风味更佳。\n\n【储存建议】阴凉避光保存，请勿冷冻。', 'wine', '箱', 40, '🍺', '#FFF3E0'),
('冷鲜纯牛奶250ml×12盒', 49.00, '', '【每日鲜配】生牛乳含量100%，巴氏杀菌工艺锁住鲜活营养，奶香醇厚，口感清爽微甜。\n\n【营养价值】每100ml含3.6g优质乳蛋白、120mg原生高钙。\n\n【储存建议】2-6°C冷鲜保存，开封后请当日饮用完毕。', 'dairy', '箱', 50, '🥛', '#F0F8FF'),
('每日坚果混合装30袋', 69.90, '', '【科学配比】核桃、腰果、巴旦木、蔓越莓、蓝莓干等6种果干坚果独立小包，每日一袋均衡营养。\n\n【口感特点】坚果香脆、果干软糯，不添加香精与防腐剂。\n\n【储存建议】阴凉干燥处保存，开封后请尽快食用。', 'snack', '盒', 45, '🥜', '#FFF5E8'),
('五常长粒香大米5kg', 42.80, '', '【稻花香产区】黑龙江五常核心产区长粒香大米，米粒修长饱满，蒸煮后饭香四溢、绵软回甘。\n\n【食用建议】蒸煮前淘洗1-2遍，米水比约1:1.2，焖煮10分钟口感更佳。\n\n【储存建议】阴凉干燥处密封保存，防潮防虫。', 'grain', '袋', 35, '🍚', '#FFFAF0'),
('生鲜臻选组合礼盒', 128.00, '', '【送礼佳品】精选当季生鲜组合：进口水果、谷饲牛肉卷、海鲜水产等混搭装，配精致礼盒与保温冰袋。\n\n【适用场景】节日送礼、探亲访友、家庭聚餐。\n\n【储存建议】收货后请尽快分装冷藏或冷冻保存。', 'general', '份', 20, '🎁', '#F5FFF5');
