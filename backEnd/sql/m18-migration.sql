-- ================================================
-- M18 增量迁移（F-18.10/G-04 Step 4a：store 门店信息 6 列 + store_apply 入驻申请 + arrival_batch/arrival_item 到货批次）
-- ① store 加 6 列（AFTER owner_user_id 依次排，除 accepting 带 DEFAULT 1 外全部 NULLable，存量 4 店行不会被 NOT NULL 无默认卡死）：
--    open_time / close_time（HH:MM，仅展示，不参与 23:00 全局截单裁决=RE 红线）、daily_capacity（NULL=不限，一期只展示不自动降级 D-8）、
--    accepting（满容/休市细粒度开关，与 status 的 open/closed 正交）、contact_phone（VARCHAR(20) 对齐 orders.phone 惯例，不是 CHAR(11)）、
--    photos（VARCHAR(1000) JSON 数组串，D-18 一期无上传端点、列先建语义先定）；
-- ② store_apply 入驻申请表（D-20：申请侧端点挂 /api/store-applies/ 登录即可、无归属要求——挂 /api/partner/ 会因归属集合为空 403 自锁入口；
--    D-21 一用户一期最多 1 条 pending 由应用层校验，不建唯一键——rejected 后再提交会产生历史行，
--    且 MySQL 不支持 (user_id, status='pending') 的部分唯一约束）；
-- ③ arrival_batch + arrival_item 到货批次（D-19：一期只做读 + 确认，批次生成器属运营端 G-05，故本文件必须带演示种子，
--    否则核销台的「当日未确认」警示条在验收现场永远演示不出来）；uk_store_date (store_id, batch_date) 既钉死一店一日一批次，
--    也是演示种子 INSERT IGNORE 幂等的唯一依据；
-- ④ 演示主理人归属归位段（⑪，显式覆盖、刻意不写成只填空位形态，风险与用途都写在该段注释里）。
-- 仅对已按 init.sql + m3~m17-migration.sql 建库的存量库执行；新库直接用同步后的 init.sql 全量重建（init.sql 不写 ⑪ 的归位 UPDATE）。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m18-migration.sql
-- 可重复执行（RC 红线）：新表 CREATE TABLE IF NOT EXISTS；ALTER 以 information_schema 判断 + PREPARE 动态执行；
--    arrival_batch 种子靠 uk_store_date 撞键 IGNORE，arrival_item 无唯一键故用 WHERE NOT EXISTS 守卫（裸 INSERT 二次执行会翻倍）。
--    本文件只做加列 / 建新表 / 加唯一键与普通索引 / 加种子，无任何删表、建库、清表、删行、删列动作（RC 六项全规避）；
--    m18 无哈希派生步骤，故不设 @mNN_salt（盐是 m17 取货码专用），仅沿用 @m18_ddl 动态 DDL 变量，形态与 m15/m16/m17 一致。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① store 加 open_time（VARCHAR(5) NULL；仅展示，不参与 23:00 全局截单=RE 红线；骨架照 m17:28-37 / m16:88-97）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN open_time VARCHAR(5) NULL COMMENT ''营业开始时间 HH:MM（G-04/m18）：仅展示用，不参与 23:00 全局截单裁决（RE 红线，截单链只在服务端 orders 侧）；NULL=未设置'' AFTER owner_user_id',
        'SELECT ''[m18] store.open_time 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'open_time'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ② store 加 close_time（营业结束时间，与 open_time 同为展示口径，不产生接单判定）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN close_time VARCHAR(5) NULL COMMENT ''营业结束时间 HH:MM（G-04/m18）：与 open_time 同为展示口径，不产生接单/截单判定；NULL=未设置'' AFTER open_time',
        'SELECT ''[m18] store.close_time 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'close_time'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ③ store 加 daily_capacity（D-8：NULL=不限；一期只展示，不做自动降级）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN daily_capacity INT NULL COMMENT ''日接单容量上限（G-04/m18，D-8）：NULL=不限；一期只展示不自动降级，超容仍走 accepting 手工开关'' AFTER close_time',
        'SELECT ''[m18] store.daily_capacity 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'daily_capacity'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ④ store 加 accepting（TINYINT NOT NULL DEFAULT 1：存量行由 DEFAULT 语义自动为 1，零回填；与 status 的 open/closed 正交）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN accepting TINYINT NOT NULL DEFAULT 1 COMMENT ''是否接单细粒度开关（G-04/m18）：1接单/0临时满容或休市；与 status 的 open/closed 正交（status 是门店存续，本列是当日产能）'' AFTER daily_capacity',
        'SELECT ''[m18] store.accepting 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'accepting'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ⑤ store 加 contact_phone（VARCHAR(20) 对齐 orders.phone 的 VARCHAR(20) 惯例——即 init.sql orders CREATE 里的 phone 列，
--    契约 F-18.10 标注的 init.sql:65 是行号漂移，该列实际在 :101（并 m18 后 :105）；不是 CHAR(11)；出参不下发，RD/D-12）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN contact_phone VARCHAR(20) NULL COMMENT ''门店联系电话（G-04/m18）：长度对齐 orders.phone 的 VARCHAR(20) 惯例（非 CHAR(11)，容纳区号/分机/境外号）；对外只脱敏下发（RD），GET /api/stores 不下发本列（D-12）'' AFTER accepting',
        'SELECT ''[m18] store.contact_phone 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'contact_phone'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ⑥ store 加 photos（D-18：一期不建 partner 上传端点，列先建语义先定，JSON 数组串口径对齐 init.sql dish CREATE 的 tags 列）
-- ----------------------------
SET @m18_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE store ADD COLUMN photos VARCHAR(1000) NULL COMMENT ''门店照片（G-04/m18，D-18）：JSON 数组串（形态对齐 dish.tags 惯例），上限 6 张，每张以 /static/images/uploads/ 开头；一期无上传端点、列先建语义先定，拍照上传与 FileStorageService 抽组件同批留 G-05'' AFTER contact_phone',
        'SELECT ''[m18] store.photos 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'photos'
);
PREPARE m18_stmt FROM @m18_ddl;
EXECUTE m18_stmt;
DEALLOCATE PREPARE m18_stmt;

-- ----------------------------
-- ⑦ store_apply 入驻申请表（列口径=F-18.10 第 2 条；status 三态照 m15 merchant_profile.audit_status 的三态口径；
--    D-20 申请侧挂 /api/store-applies/（登录即可、无归属要求），审核侧挂 Admin，有店后的维护才挂 /api/partner/；
--    D-21 一用户一期最多 1 条 pending 由应用层校验，故只有 idx_user_status 普通索引、无唯一键）
-- ----------------------------
CREATE TABLE IF NOT EXISTS store_apply (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '申请ID',
    user_id          BIGINT        NOT NULL COMMENT '申请人 user.id（逻辑外键 user.id；申请时名下无店，故入口不能挂 /api/partner/ —— D-20）',
    proposed_name    VARCHAR(100)  NOT NULL COMMENT '拟用门店名称',
    proposed_address VARCHAR(255)  NOT NULL COMMENT '拟用门店地址',
    lng              DECIMAL(10,6) NULL COMMENT '经度：高德 GCJ-02 真源，与 store.lng 同口径（F-06）',
    lat              DECIMAL(10,6) NULL COMMENT '纬度：高德 GCJ-02 真源，与 store.lat 同口径（F-06）',
    service          VARCHAR(50)   NULL COMMENT '服务说明（如 支持冷冻/冷藏），对齐 store.service',
    contact_name     VARCHAR(50)   NULL COMMENT '联系人姓名',
    contact_phone    VARCHAR(20)   NULL COMMENT '联系电话（VARCHAR(20) 同 store.contact_phone 口径）；响应只能脱敏展示（RD）',
    proof_photos     VARCHAR(1000) NULL COMMENT '凭证照片（D-18）：JSON 数组串，上限 6 张，每张以 /static/images/uploads/ 开头；一期无上传端点',
    status           VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT '审核状态：pending待审核/approved通过/rejected驳回（三态口径照 m15 merchant_profile.audit_status）',
    audit_remark     VARCHAR(255)  NULL COMMENT '审核意见（驳回原因供申请人展示）',
    audited_at       DATETIME      NULL COMMENT '审核时间；NULL=未审',
    created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主理人入驻申请（D-20 申请侧 /api/store-applies/ 登录即可；D-21 同用户最多 1 条 pending 由应用层保证，不建唯一键；审核动作写 admin_audit_log，见 D-5 两表边界）';

-- ----------------------------
-- ⑧ arrival_batch 到货批次表（D-19：一期只有读 + 确认，批次生成器不在本期）
--    UNIQUE KEY uk_store_date (store_id, batch_date) 双重作用：钉死一店一日一批次 + 让 ⑩ 的演示种子可 INSERT IGNORE 幂等
-- ----------------------------
CREATE TABLE IF NOT EXISTS arrival_batch (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '批次ID',
    store_id      BIGINT       NOT NULL COMMENT '自提点ID（逻辑外键 store.id）',
    batch_date    DATE         NOT NULL COMMENT '批次日期（一期=到货日）',
    status        VARCHAR(20)  NOT NULL DEFAULT 'planned' COMMENT '批次状态：planned计划/arriving在途/confirmed已到货确认/short短少（D-19 一期只读+确认）',
    confirm_time  DATETIME     NULL COMMENT '主理人确认到货时间（Step 4b 端点写入，同时落 ops_log）；NULL=未确认',
    remark        VARCHAR(255) NULL COMMENT '批次备注',
    supplier_type VARCHAR(20)  NULL COMMENT '供货来源（D-9 预留位，一期恒 NULL）',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '批次创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_date (store_id, batch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='到货批次（D-19：一期只有读+确认，生成器属运营端 G-05；uk_store_date=一店一日一批次，亦为演示种子幂等依据）';

-- ----------------------------
-- ⑨ arrival_item 到货批次明细（无唯一键 → ⑩ 的第 2 条种子必须用 WHERE NOT EXISTS 守卫，不能裸 INSERT）
-- ----------------------------
CREATE TABLE IF NOT EXISTS arrival_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '明细ID',
    batch_id     BIGINT       NOT NULL COMMENT '批次ID（逻辑外键 arrival_batch.id）',
    dish_id      BIGINT       NULL COMMENT '菜品ID（逻辑外键 dish.id）；NULL=非菜品项预留',
    expected_qty INT          NOT NULL DEFAULT 0 COMMENT '预计到货数量',
    actual_qty   INT          NULL COMMENT '实际到货数量；NULL=尚未清点（确认端点写入）',
    short_reason VARCHAR(255) NULL COMMENT '短少原因（status=short 时填写）',
    PRIMARY KEY (id),
    KEY idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='到货批次明细（D-19 一期只读+确认；本表无唯一键，幂等由上层种子的 WHERE NOT EXISTS 保证）';

-- ----------------------------
-- ⑩ 演示种子两条（D-19 硬要求：无种子则核销台的当日未确认警示条演示不出来）
--    种子 1 幂等依据=uk_store_date：二次执行撞唯一键被 IGNORE 吞掉，arrival_batch 仍 1 行
-- ----------------------------
INSERT IGNORE INTO arrival_batch (store_id, batch_date, status) VALUES (1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'planned');

-- 种子 2：配套一条明细（dish 1 预计 5 份）
-- arrival_item 无唯一键，INSERT IGNORE 在这里不产生去重效果，故必须带 NOT EXISTS 守卫——裸 INSERT 二次执行会翻倍（违反 RC）
INSERT IGNORE INTO arrival_item (batch_id, dish_id, expected_qty)
SELECT b.id, 1, 5
  FROM arrival_batch b
 WHERE b.store_id = 1
   AND b.batch_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)
   AND NOT EXISTS (
        SELECT 1 FROM arrival_item ai WHERE ai.batch_id = b.id AND ai.dish_id = 1
   );

-- ----------------------------
-- ⑪ 演示主理人归属归位段（F-18.9 D 节登记的待归位状态 → 05 册 A2.1 演示目标形态）
--    (a) 为什么要覆盖、且为什么不能写成 WHERE owner_user_id IS NULL 的只填空位形态：
--        现网 fresh_db 的 store 1 在 Step 3 门禁里被临时绑到 uid 40 / 43 / 45（F-18.9 B 节 G2 用 45 跑通归属清单，
--        F-18.9 D 节明确登记为「Step 4 前要归位」，现值 45）。若本段只填空位，
--        store 1 会永远停在 uid 45（该位非 NULL，守卫直接跳过），归位根本不发生；
--        故这两条刻意不带 IS NULL 守卫，直接按 A2.1 目标形态覆写：store 1 = user 1 承包，2/3 平台直营，4 停业未入驻。
--        幂等性成立：重复执行结果恒为同一目标值，数字不漂移（RC）。
--    (b) 边界与风险（诚实登记）：本段是演示库种子（demo seed），不是生产迁移语义。
--        真实生产的门店归属只能由入驻审核链写入——Step 4b 的 Admin audit 把 store_apply 置 approved 时才写 store.owner_user_id，
--        绝不由迁移脚本决定。本段若在生产库执行会把真实归属刷成 user 1，这是 F-18.10 第 6 条明确要求写入并接受的风险，
--        执行前须确认目标库是演示库 fresh_db。
--        init.sql 不并入这两条 UPDATE（新库无待归位的历史绑定，种子 INSERT 直接带 owner_user_id 值即可，见 init.sql store 种子注释）。
-- ----------------------------
UPDATE store SET owner_user_id = 1 WHERE id = 1;
UPDATE store SET owner_user_id = NULL WHERE id IN (2, 3, 4);

-- ----------------------------
-- 门禁断言 SELECT（S4a-G2/G3/G4/G5 的库侧读数，只读、零副作用）
-- ----------------------------
-- 断言 1：store 必须 15 列（G4 口径：现网 9 → 15）
SELECT COUNT(*) AS store_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store';

-- 断言 2：必须 0 行（accepting 存量行由 DEFAULT 1 自动覆盖，零回填；出现非 1 即本文件之外有人改过）
SELECT COUNT(*) AS accepting_not_1 FROM store WHERE accepting <> 1;

-- 断言 3：必须 3（store_apply / arrival_batch / arrival_item 齐；G2 口径 m18 后全库表数 21）
SELECT COUNT(*) AS m18_new_tables
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('store_apply', 'arrival_batch', 'arrival_item');

-- 断言 4：必须恰 1 行且 NON_UNIQUE = 0、key_parts = 2（uk_store_date 是真唯一键，种子幂等靠它）
SELECT NON_UNIQUE, COUNT(*) AS key_parts
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'arrival_batch' AND INDEX_NAME = 'uk_store_date'
 GROUP BY NON_UNIQUE;

-- 断言 5：种子二次不翻倍（G3 期望 batch_rows=1 且 item_rows=1；G4 现网首跑期望同为 1/1）
SELECT (SELECT COUNT(*) FROM arrival_batch
         WHERE store_id = 1 AND batch_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)) AS batch_rows,
       (SELECT COUNT(*) FROM arrival_item ai
          JOIN arrival_batch b ON ai.batch_id = b.id
         WHERE b.store_id = 1 AND b.batch_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)) AS item_rows;

-- 断言 6：归属归位读数（G4 期望 id=1 的 owner_user_id 由 45 归位为 1，2/3/4 为 NULL）
SELECT id, owner_user_id, accepting, open_time, daily_capacity FROM store WHERE id IN (1, 2, 3, 4) ORDER BY id;
