-- ================================================
-- M27 增量迁移（F-117 三期结算层数据模型 + 供货价真源）
-- 本期三个动作（契约 §1；settlement DDL 照抄 docs/商家端规划/06-冲突分析与数据模型演进.md §4.5 纸面定稿）：
--   ① dish 加 supply_price DECIMAL(10,2) NULL —— 供货价真源（F-117/§6-1 定稿）：
--     NULL=未设（不进结算聚合，F-118 聚合基数语义）；运营端维护；不回填种子
--     （避免制造「平台已确认供货价」假象）。
--   ② order_item 加 settle_id BIGINT NULL + supply_price DECIMAL(10,2) NULL + idx_settle：
--     settle_id 归属结算单（逻辑外键 settlement.id；NULL=未结算——06 册 §4.5 原文口径），
--     本片只建列零写路径（F-118 跑批才写）；supply_price 是下单时供货价快照，
--     与成交价快照同模式（dish 侧 NULL 则快照 NULL）。
--   ③ settlement 表照抄 06 册 §4.5 纸面定稿：列清单/金额口径/三枚索引逐字照册；
--     仅两处注释级差异（DDL 账务语义一字不改）：
--     · status 枚举本片只登记 accounted（记账态）——08 册 §2.2-5「settlement.status 停在 accounted，
--       paid 态不实现等轨道 B」；06 册 §4.5 纸面枚举 pending/confirmed/paid/closed 不启用，
--       DEFAULT 随单态口径落 'accounted'（06 册纸面 DEFAULT 'pending' 指向不启用的枚举值）；
--     · 注释编号更新为 F-117 并注明出处（册内 markdown 链接转纯文本「04/05 册 §N」）。
--     本片 settlement 零读写（无端点无服务逻辑），表先立、F-118 才有生成入口。
--
-- 幂等写法（跑两次零报错、零改动，照 m26 的错误码定性写法 / m18 ① 加列先例）：
--   · ①②以 information_schema.COLUMNS 判目标列是否存在；不存在才执行 ALTER；
--     各合并为一条多子句 ALTER（m18 BE-10 裁定：逐列多条 ALTER = 多次表重建；
--     一条 ALTER 原子，判一列即知同船已到——②的三样（两列一索引）同船，索引引用同语句新加列合法）；
--   · ③ CREATE TABLE IF NOT EXISTS 天然幂等；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量写数、无动态 SQL 拼注入面，可整文件重复执行。
--   · 06 册 §4.5 纸面给 order_item.settle_id 标过 AFTER merchant_id——那是「语义定义」段的示意位，
--     物理位非约束；本迁移按本仓 m18/m26 加列惯例无 AFTER 子句、列追加表尾，
--     init.sql 建表体照迁移后物理列序摆放，两路结构逐列一致（m26 同款同步纪律）。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m27-migration.sql
-- 同步：init.sql 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① dish 一条 ALTER 加 supply_price（幂等判定查 dish.supply_price；骨架照 m18 ①/m26 ①：information_schema 判 + PREPARE 动态执行）
-- ----------------------------
SET @m27_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE dish
            ADD COLUMN supply_price DECIMAL(10,2) NULL
              COMMENT ''F-117 供货价真源：NULL=未设（不进结算聚合，F-118 聚合基数语义）；运营端维护，不回填种子''',
        'SELECT ''[m27] dish.supply_price 已存在（判 supply_price），跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish' AND COLUMN_NAME = 'supply_price'
);
PREPARE m27_stmt FROM @m27_ddl;
EXECUTE m27_stmt;
DEALLOCATE PREPARE m27_stmt;

-- ----------------------------
-- ② order_item 一条 ALTER 加齐 2 列 + 1 索引（幂等判定查 order_item.settle_id；settle_id/supply_price/idx_settle 同船）
-- ----------------------------
SET @m27_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE order_item
            ADD COLUMN settle_id BIGINT NULL
              COMMENT ''归属结算单（逻辑外键 settlement.id）；NULL=未结算（F-117/m27，06 册 §4.5 原文口径；本片只建列零写路径，F-118 跑批才写）'',
            ADD COLUMN supply_price DECIMAL(10,2) NULL
              COMMENT ''供货价快照（F-117，下单时自 dish.supply_price 落库，与成交价快照同模式；dish 侧 NULL 则快照 NULL）'',
            ADD INDEX idx_settle (settle_id)',
        'SELECT ''[m27] order_item 结算两列已存在（判 settle_id），跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'settle_id'
);
PREPARE m27_stmt FROM @m27_ddl;
EXECUTE m27_stmt;
DEALLOCATE PREPARE m27_stmt;

-- ----------------------------
-- ③ settlement 商家结算单表（F-117/m27，DDL 照抄 06 册 §4.5 纸面定稿；账务语义一字不改，见头注两处注释级差异）：
--    零物理外键（对齐全库惯例）；本片零读写——无端点无服务逻辑，表先立、F-118 跑批才有生成入口；
--    防重复结算的明细锚在 order_item.settle_id（上方 ②），uk_settle_no/uk_merchant_period
--    一期一单两枚唯一键是 06 册 §4.5「生成结算单事务」幂等模型的库侧前提。
-- ----------------------------
CREATE TABLE IF NOT EXISTS settlement (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '结算单ID',
  settle_no     VARCHAR(32)   NOT NULL COMMENT '结算单号（对账凭证）',
  merchant_id   BIGINT        NOT NULL COMMENT '商家ID（逻辑外键 merchant.id）',
  period_start  DATE          NOT NULL COMMENT '账期起（T+N 模型，N 待支付转正后定）',
  period_end    DATE          NOT NULL COMMENT '账期止',
  order_count   INT           NOT NULL DEFAULT 0 COMMENT '含该商家明细的去重订单数（一单多商家不重复计）',
  gmv_amount    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '商品口径 GMV=SUM(order_item.price×quantity)，聚合基数排除 fulfill_status=shortage 缺货明细（缺货件不计商家应结）',
  coupon_deduct DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '券抵扣分摊（与平台「已收款」SUM(total_price) 口径天然对不平，两口径分开命名展示）',
  commission    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '平台佣金（费率另定）',
  retain_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '应结款留存划转（保证金替代物，04 册 §3.2）',
  deduction     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '扣罚/理赔抵扣（联动 deposit_flow）',
  refund_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '退款冲销（逆向资金流唯一落点）：结算后取消/缺货单未打款部分在下一账期负向计入本列（备注关联原 settle_no 可追溯）；已打款（paid）单走留存抵扣，下一账期 retain_amount 同额扣减，不动已打款资金',
  payable       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '应结=GMV−券分摊−佣金−留存−扣罚−退款冲销（refund_amount）',
  status        VARCHAR(20)   NOT NULL DEFAULT 'accounted' COMMENT 'accounted记账态（已记账）——本片只登记此值（08 册 §2.2-5「status 停在 accounted」），paid 打款态不实现留轨道 B；06 册 §4.5 纸面枚举 pending/confirmed/paid/closed 不启用',
  paid_at       DATETIME      NULL COMMENT '打款时间',
  payout_voucher VARCHAR(64)  NULL COMMENT '打款凭证号（对账补课；打款走微信商家转账，与 C 端提现同通道 TODO InviteServiceImpl.java:261-294）',
  created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_settle_no (settle_no),
  UNIQUE KEY uk_merchant_period (merchant_id, period_start, period_end),
  KEY idx_merchant_status (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家结算单（仅承载商家主体；自提点主理人佣金记账不共表，走独立钱包/流水——05 册 §A7.2；F-117/m27 照 06 册 §4.5 纸面定稿建表，本片零读写、F-118 跑批才写）';

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：dish 列数必须到位（G 口径：m26 后 22 列 → 23）
SELECT COUNT(*) AS dish_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dish';

-- 断言 2：order_item 列数必须到位（G 口径：m17 后 8 列 → 10）
SELECT COUNT(*) AS order_item_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item';

-- 断言 3：settlement 表必须存在且 uk 两索引齐（uk_settle_no / uk_merchant_period；idx_merchant_status 不计）
SELECT COUNT(*) AS settlement_uk_indexes
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement'
   AND INDEX_NAME IN ('uk_settle_no', 'uk_merchant_period');

-- 断言 4：dish.supply_price 必须全 NULL（本片零回填种子、零 UPDATE；出现非 NULL 即本文件之外有人改过）
SELECT COUNT(*) AS dish_supply_price_not_null FROM dish WHERE supply_price IS NOT NULL;

-- 断言 5：order_item.settle_id 必须全 NULL（本片只建列零写路径；出现非 NULL 即本文件之外有人写过）
SELECT COUNT(*) AS order_item_settle_id_not_null FROM order_item WHERE settle_id IS NOT NULL;

-- 断言 6：settlement 必须 0 行（本片零写入；出现行即本文件之外有生成入口越权落库）
SELECT COUNT(*) AS settlement_rows FROM settlement;
