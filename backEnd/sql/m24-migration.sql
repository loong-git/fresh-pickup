-- ================================================
-- M24 增量迁移（F-113 券模板表化）
-- 本期唯一动作 = 新建 coupon_template 表并回填 4 行（照 CouponServiceImpl.TEMPLATES 常量逐字）：
--   · 券模板从代码常量迁表（05 册 B3④ 设计）：limit_per_user 泛化 lifetime 语义、
--     total_quota/issued_count 条件 UPDATE 原子扣减（照 user.cash_rewarded 封顶闸门范式 init.sql:63）；
--   · 回填值照 TEMPLATES 逐字：coupon_1(19,4)/coupon_2(30,5)/coupon_3(50,21)/newbie_gift(30,8)；
--     limit_per_user=1、valid_days=7、total_quota=NULL、issued_count=0、status=active；title 不回填
--     （NULL 时前端按满X减Y拼，出参原样透传）；
--   · 切表后 CouponServiceImpl.TEMPLATES 与 isLifetimeOnce 删除（表为唯一真源，双源必漂移）。
--
-- 幂等写法（重放两次零报错，契约 §1 点名）：
--   · DROP TABLE IF EXISTS + CREATE —— 二次执行重建表后回到回填态；
--   · 回填 INSERT ... SELECT ... WHERE NOT EXISTS（按 coupon_key 判存在）。
--   · ⚠ DROP 是破坏性语句：存量库上重放会清掉运营改过的模板与 issued_count 计数——
--     重放验证只在主会话按 skill fresh-db-migration-rebuild-gate 的口径做（备份→重放两次→smoke→复原），
--     日常存量库不重放本文件。
--
-- 存量库口径（契约 §1 注明）：回填后 issued_count=0 而库里已有 user_coupon 历史行——这是刻意的：
--   total_quota=NULL（不限）下 issued_count 不参与任何判定，不回填历史计数；
--   运营将来设配额时按当时库存估算。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m24-migration.sql
-- 同步：init.sql 的建表体与回填与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- 券模板表（DDL 逐字照契约 §1）
-- ----------------------------
DROP TABLE IF EXISTS coupon_template;
CREATE TABLE coupon_template (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    coupon_key     VARCHAR(50)   NOT NULL                COMMENT '券模板标识（C 端 claim/center 的 key）',
    title          VARCHAR(50)   NULL                    COMMENT '展示名；NULL 时前端按满X减Y拼（出参原样透传）',
    threshold      DECIMAL(10,2) NOT NULL                COMMENT '使用门槛',
    amount         DECIMAL(10,2) NOT NULL                COMMENT '抵扣金额',
    valid_days     INT           NOT NULL DEFAULT 7      COMMENT '领取后有效天数',
    limit_per_user INT           NOT NULL DEFAULT 1      COMMENT '每人全史限领 N 张（含 used/expired）；0=不限',
    total_quota    INT           NULL                    COMMENT '总量配额；NULL=不限',
    issued_count   INT           NOT NULL DEFAULT 0      COMMENT '已发计数（配额原子扣减用）',
    status         VARCHAR(20)   NOT NULL DEFAULT 'active' COMMENT 'active 启用 / disabled 停用（claim/center 均不可见）',
    create_time    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_key (coupon_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='券模板表（F-113 表化，替代代码常量）';

-- ----------------------------
-- 回填 4 行（值照 TEMPLATES 常量逐字；title 留 NULL 走前端「满X减Y」拼装）
-- ----------------------------
INSERT INTO coupon_template (coupon_key, threshold, amount, valid_days, limit_per_user, total_quota, issued_count, status)
SELECT 'coupon_1', 19.00, 4.00, 7, 1, NULL, 0, 'active'
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE coupon_key = 'coupon_1');

INSERT INTO coupon_template (coupon_key, threshold, amount, valid_days, limit_per_user, total_quota, issued_count, status)
SELECT 'coupon_2', 30.00, 5.00, 7, 1, NULL, 0, 'active'
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE coupon_key = 'coupon_2');

INSERT INTO coupon_template (coupon_key, threshold, amount, valid_days, limit_per_user, total_quota, issued_count, status)
SELECT 'coupon_3', 50.00, 21.00, 7, 1, NULL, 0, 'active'
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE coupon_key = 'coupon_3');

INSERT INTO coupon_template (coupon_key, threshold, amount, valid_days, limit_per_user, total_quota, issued_count, status)
SELECT 'newbie_gift', 30.00, 8.00, 7, 1, NULL, 0, 'active'
WHERE NOT EXISTS (SELECT 1 FROM coupon_template WHERE coupon_key = 'newbie_gift');
