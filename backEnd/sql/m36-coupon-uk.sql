-- ================================================
-- M36 增量迁移（P11-2 P1-B 领券唯一键兜底：user_coupon 加 uk_user_coupon）
-- 动机 = P11-2 审计 P1-B：CouponServiceImpl.claim 的 lifetime 防线（selectCount 计数 → insert）
-- 是 check-then-act，并发双击双请求都读到 count=0 → 各插一张，limit_per_user=1 三档模板可超领。
-- 配额防线 tryIssueQuota 是原子条件 UPDATE（无竞态），竞态点仅 lifetime 防线——本迁移给它加
-- 库侧唯一键兜底（服务侧 insert 处 catch DuplicateKeyException → 400「每人限领 1 张」，
-- selectCount 检查保留不动，双防线）。
--
-- 本期唯一动作（ADD-only：只加唯一索引，不动任何既有列定义，零 UPDATE 零回填）：
--   user_coupon 加 UNIQUE KEY uk_user_coupon (user_id, coupon_key)
--     —— 每人每 key 至多一行 user_coupon；与 claim 的 lifetime「全量计数该 user 该 key」口径
--     同构（含 used/expired 行，唯一键同样不区分状态）。
--     coupon_key NOT NULL / user_id NOT NULL（init.sql 列定义），无 NULL 进唯一键的边界。
--
-- 存量去重预检（应用前必跑，预期 0 行；若有重复行须先人工处理再应用，唯一键创建会失败）：
--   SELECT user_id, coupon_key, COUNT(*) c FROM user_coupon GROUP BY user_id, coupon_key HAVING c > 1;
--
-- 幂等写法（跑两次零报错零改动，照 m17 ② uk_pickup_code 的 information_schema.STATISTICS
-- 判存 + PREPARE 骨架）：
--   · 索引以 information_schema.STATISTICS 判存在性，已存在即跳过；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，可整文件重复执行。
--
-- 已知副作用登记：CouponServiceImpl.grant（遗留路径，invite_reward 已随 F-05 下线，main 源码
-- 无调用方）语义是「不去重不占额」——加唯一键后同 user 同 key 的第二次 grant insert 将撞键抛
-- DuplicateKeyException（不再静默多插一行）；无调用方，不处理。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m36-coupon-uk.sql
-- 同步：init.sql 的 user_coupon 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① uk_user_coupon 唯一索引（骨架照 m17 ② uk_pickup_code 原文形态；两列均 NOT NULL，无 NULL 边界）
-- ----------------------------
SET @m36_ddl = (
    SELECT IF(COUNT(DISTINCT INDEX_NAME) = 0,
        'ALTER TABLE user_coupon ADD UNIQUE KEY uk_user_coupon (user_id, coupon_key)',
        'SELECT ''[m36] uk_user_coupon 已存在，跳过'' AS note')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon' AND INDEX_NAME = 'uk_user_coupon'
);
PREPARE m36_stmt FROM @m36_ddl;
EXECUTE m36_stmt;
DEALLOCATE PREPARE m36_stmt;

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：uk_user_coupon 必须已存在（列 seq 1=user_id, 2=coupon_key）
SELECT CONCAT('[m36] user_coupon index check: ',
       IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_coupon'
                   AND INDEX_NAME = 'uk_user_coupon'),
           'uk_user_coupon OK', 'uk_user_coupon MISSING!')) AS note;
