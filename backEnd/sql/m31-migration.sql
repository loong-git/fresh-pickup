-- ================================================
-- M31 增量迁移（F-124 评价强幂等：review.client_request_id 唯一键）
-- 本期唯一动作（契约 F-124 §1.1 逐字）：
--   · review 加 1 列 client_request_id VARCHAR(64) NULL
--     —— 客户端幂等标识：同值重复提交返回首次评价，不再落重复行；
--     NULL = 历史/种子数据/不带键的旧前端（向后兼容，UNIQUE 允许多行 NULL，与 orders.uk_client_request_id 同型）。
--   · 加唯一索引 uk_review_client_request_id。
-- 存量行零 UPDATE、零回填（全 NULL，不占唯一键）；执行者=主会话（破坏性 SQL 纪律，2026-10-28 已在本地库执行）。
-- ================================================

ALTER TABLE review
    ADD COLUMN client_request_id VARCHAR(64) NULL COMMENT '客户端幂等标识（F-124）：同值重复提交返回首次评价；NULL=历史/种子/不带键的旧前端' AFTER content,
    ADD UNIQUE INDEX uk_review_client_request_id (client_request_id);
