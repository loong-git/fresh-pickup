-- ================================================
-- M6 增量迁移（优惠券有效期：user_coupon 加 expire_at，claim 写领取时间+7 天，过期券下单拦截；
-- 评价购买归属：review 加 user_id，提交评价须本人订单明细，reviewer 服务端脱敏生成）
-- 仅对已按 init.sql + m3/m4/m5-migration.sql 建库的存量库执行一次；新库直接用同步后的 init.sql 全量重建。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m6-migration.sql
-- ================================================
USE fresh_db;

-- ----------------------------
-- 优惠券有效期：user_coupon 加 expire_at（NULL=永不过期，未回填前兜底）
-- ----------------------------
ALTER TABLE user_coupon ADD COLUMN expire_at DATETIME NULL
    COMMENT '过期时间（M6）：领取时写 created_at+7 天（见 UserCoupon.VALID_DAYS）；NULL=永不过期' AFTER created_at;

-- 存量券回填远期过期时间（2099 年）：只约束新发券，老券不追溯过期，向后兼容
UPDATE user_coupon SET expire_at = '2099-12-31 23:59:59' WHERE expire_at IS NULL;

-- ----------------------------
-- 评价购买归属（M6）：review 加 user_id 列，服务端按 token 用户落库（评价可溯源，支撑后续"我的评价"）；
-- 允许 NULL 标记历史数据（sql/review.sql 种子评价不回填归属），存量评价展示不受影响，
-- 归属校验只对新增评价生效（无归属的历史评价仍可正常展示）
-- ----------------------------
ALTER TABLE review ADD COLUMN user_id BIGINT NULL
    COMMENT '评价用户ID（M6）：服务端按登录用户落库；NULL=历史/种子数据（归属不可考）' AFTER dish_id;

ALTER TABLE review ADD INDEX idx_user_id (user_id);
