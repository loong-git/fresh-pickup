-- ================================================
-- M29 增量迁移（F-121 限流观测：m29 轻表 + 运营页 + 30 天清理）
-- 本期一个动作（契约 §1）：rate_limit_hit 限流命中日志轻表——
--   拦截器命中分支（RateLimitInterceptor 现有 log.warn 同点）同步旁路 INSERT 一行：
--   route = method + 空格 + URI（如 POST /api/orders；参数化 URI 如 /api/dishes/1/reviews 按实落库——
--   观测侧如实记，接口身份维度仍由 bucket_key 的控制器类名与方法名段承载，不在此归一）；
--   bucket_key = 限流维度键原样（ip:1.2.3.4:控制器类名与方法名 / user:11:控制器类名与方法名）；
--   retry_after = 429 下发的 retryAfterSeconds（INT，现成变量直落）；hit_time = 命中时刻（idx_hit_time）。
--   表是纯观测旁路：写路径只有拦截器命中旁路 + 每日清理任务 DELETE，无任何业务链路涉及；
--   审计不适用（契约 §2：纯只读观测，无写操作端点）。
--
-- 幂等写法（跑两次零报错、零改动，照 m28 的建表守卫）：
--   · CREATE TABLE IF NOT EXISTS 天然幂等（本文件无 ALTER——全新单表，无存量加列面）；
--   · 索引选型：本表三条查询路径——清理 DELETE（hit_time < cutoff）与运营聚合（hit_time >= since
--     的 WHERE）都走 idx_hit_time 单列区间；聚合的 GROUP BY route,bucket_key 不建第二份索引
--     （观测低频读 + 命中即写的高频写面，多一枚索引白付一份写入成本，同 m28 选型口径）；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量写数、无动态 SQL 拼注入面，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m29-migration.sql
-- 同步：init.sql 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① rate_limit_hit 限流命中日志轻表（F-121/m29；观测旁路表：写只有拦截器命中 INSERT + 每日清理 DELETE，
--    读只有运营聚合端点 GET /api/admin/rate-limit/hits——三路全走 idx_hit_time）
-- ----------------------------
CREATE TABLE IF NOT EXISTS rate_limit_hit (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '命中记录ID',
  route       VARCHAR(200) NOT NULL COMMENT 'method + 空格 + URI（如 POST /api/orders；拦截器旁路取 request.getMethod() 拼空格拼 request.getRequestURI()）',
  bucket_key  VARCHAR(200) NOT NULL COMMENT '限流维度键原样（RateLimitInterceptor#bucketKey 产物：ip:1.2.3.4:OrderController#createOrder / user:11:OrderController#createOrder）',
  retry_after INT          NULL COMMENT '429 下发的 retryAfterSeconds（桶回速最早可再试整秒数，F-22 口径）',
  hit_time    DATETIME     NOT NULL COMMENT '命中时刻（拦截器旁路写入；idx_hit_time 支撑清理 DELETE 与运营聚合 WHERE）',
  PRIMARY KEY (id),
  KEY idx_hit_time (hit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='限流命中日志（F-121/m29；纯观测旁路轻表：命中即异常流量信号；拦截判定零涉及，插入失败只 warn 不吞拦截；30 天前由 RateLimitHitCleanupTask 每日清理）';

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：rate_limit_hit 列数必须到位（G 口径：本期后 5 列）
SELECT COUNT(*) AS rate_limit_hit_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rate_limit_hit';

-- 断言 2：idx_hit_time 必须在（清理与聚合两条区间查询的索引前提）
SELECT COUNT(*) AS rate_limit_hit_hit_time_index
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rate_limit_hit'
   AND INDEX_NAME = 'idx_hit_time';

-- 断言 3：rate_limit_hit 必须 0 行（写路径只有拦截器旁路与清理任务，本文件零写；出现行即本文件之外有人写过）
SELECT COUNT(*) AS rate_limit_hit_rows FROM rate_limit_hit;
