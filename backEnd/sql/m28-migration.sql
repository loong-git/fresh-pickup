-- ================================================
-- M28 增量迁移（F-119 主理人佣金记账：m28 钱包两表 + 跑批并入 + 我的佣金页）
-- 本期两个动作（契约 §1；两表 DDL 列清单/唯一键照 docs/F-119-主理人佣金记账契约.md §1 逐字）：
--   ① partner_wallet 主理人佣金钱包：一人一钱包（uk_user_wallet）；
--     balance 是「记账态」余额——提现属轨道 B，本表与 user.balance（邀请现金钱包，m11）物理隔离、
--     两钱包互不读写（契约 §4 红线「user.balance 零涉及」）。
--   ② partner_wallet_flow 佣金流水：cash_flow 余额快照范式（balance_after=变动后余额）；
--     biz_type 两值 commission_grant 佣金入账 / commission_reversal 佣金冲回（正入负冲）；
--     佣金锚=订单（order_id，VARCHAR(64) 同 orders.id 口径）；settlement_id 记产生它的账单（可追溯）；
--     rate_snapshot 费率快照 0.0300（05 册 A7.1 防改率追溯歧义）。
--     唯一防重 uk_order_biz (order_id, biz_type)：同单同类型至多一条流水——一单至多记一次佣金
--     （佣金基数=订单实付 orders.total_price 整单口径，跨商家同单二次记账被本键拦下）、
--     一单至多冲回一次；跑批重入时 INSERT 撞 uk 即跳过该单，配合 settlement uk_merchant_period 双保险。
--
-- 幂等写法（跑两次零报错、零改动，照 m27 ③ settlement 的建表守卫）：
--   · 两表 CREATE TABLE IF NOT EXISTS 天然幂等（本文件无 ALTER——两表全为本期新表，无存量加列面）；
--   · 索引选型：查询路径全部由既有键覆盖、不建冗余第二份——user_id 等值走 idx_user_time 最左前缀，
--     order_id 等值走 uk_order_biz 最左前缀（同 m27 settlement 的 uk 即查询索引口径）；
--   · 本文件无 DROP / TRUNCATE / DELETE / UPDATE，无会话变量写数、无动态 SQL 拼注入面，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m28-migration.sql
-- 同步：init.sql 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① partner_wallet 主理人佣金钱包（F-119/m28；写路径只有 F-118 生成账期跑批并入的记账步，
--    无人工调额/提现端点——契约 §3 红线「运营不可手改钱包，防资损」）
-- ----------------------------
CREATE TABLE IF NOT EXISTS partner_wallet (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '钱包ID',
  user_id     BIGINT        NOT NULL COMMENT '主理人 user.id（唯一钱包，逻辑外键 user.id；主理人不占 user.role，归属由 store.owner_user_id 语义承载）',
  balance     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '佣金余额（记账态；提现属轨道 B；可被冲回减至负值——佣金已付情形轨道 B 处理，本期如实记账）',
  created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_wallet (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主理人佣金钱包（F-119/m28；05 册 A7.2 独立载体：不与 settlement 共表，也不与 user.balance 邀请现金钱包互通；写路径只有生成账期跑批记账，无人工调额端点）';

-- ----------------------------
-- ② partner_wallet_flow 佣金流水（F-119/m28；cash_flow 余额快照范式）：
--    amount 正入负冲——grant 恒正 / reversal 恒负（绝对值同额，冲回冲的就是那一笔 grant）；
--    balance_after NOT NULL：冲回减至负也如实快照；uk_order_biz 是跑批幂等的库侧护栏。
-- ----------------------------
CREATE TABLE IF NOT EXISTS partner_wallet_flow (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  user_id       BIGINT        NOT NULL COMMENT '钱包属主 user.id（逻辑外键 user.id；idx_user_time 最左前缀支撑本人流水分页）',
  biz_type      VARCHAR(20)   NOT NULL COMMENT 'commission_grant 佣金入账 / commission_reversal 佣金冲回（写侧常量收口 PartnerWalletFlow）',
  order_id      VARCHAR(64)   NOT NULL COMMENT '佣金锚=订单号（orders.id 同款 VARCHAR 雪花串；uk_order_biz 最左前缀支撑按单检索）',
  settlement_id BIGINT        NULL COMMENT '产生它的账单 settlement.id（逻辑外键，可追溯；grant/reversal 都记当期账单，Σ amount 即 settlement.commission 可互核）',
  amount        DECIMAL(10,2) NOT NULL COMMENT '变动金额：正入负冲（grant 恒正 / reversal 恒负）',
  balance_after DECIMAL(10,2) NOT NULL COMMENT '变动后余额快照（cash_flow 范式；冲回可减至负，如实记）',
  rate_snapshot DECIMAL(5,4)  NOT NULL COMMENT '费率快照 0.0300（防改率追溯歧义，05 册 A7.1）',
  remark        VARCHAR(200)  NULL COMMENT '备注（预留轨道 B 提现等；本片两类流水不填）',
  created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_biz (order_id, biz_type),
  KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主理人佣金流水（F-119/m28；cash_flow 余额快照范式；写路径只有生成账期跑批记账——grant 入账/reversal 冲回同批，uk_order_biz 同单同类型至多一条）';

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：partner_wallet 列数必须到位（G 口径：本期后 5 列）
SELECT COUNT(*) AS partner_wallet_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'partner_wallet';

-- 断言 2：partner_wallet_flow 列数必须到位（G 口径：本期后 10 列）
SELECT COUNT(*) AS partner_wallet_flow_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'partner_wallet_flow';

-- 断言 3：两枚关键索引必须齐（uk_order_biz / idx_user_time；uk_user_wallet 在 partner_wallet 表不计入本条）
SELECT COUNT(*) AS partner_wallet_flow_key_indexes
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'partner_wallet_flow'
   AND INDEX_NAME IN ('uk_order_biz', 'idx_user_time');

-- 断言 4：partner_wallet 必须 0 行（本片只建表零写入；出现行即本文件之外有人写过）
SELECT COUNT(*) AS partner_wallet_rows FROM partner_wallet;

-- 断言 5：partner_wallet_flow 必须 0 行（写路径只有跑批记账，本文件零写）
SELECT COUNT(*) AS partner_wallet_flow_rows FROM partner_wallet_flow;
