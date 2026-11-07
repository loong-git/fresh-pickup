-- ================================================
-- M33 增量迁移（P10-5 轨道 B 模拟转正 B3 片：m1x 保证金层——merchant_deposit/deposit_flow 两表
--   + settlement.deposit_amount 代扣列）
-- 契约 docs/十期规划/P10-5-轨道B模拟转正契约.md D7/D8/D9；两表 DDL 逐字基线照
-- docs/商家端规划/06-冲突分析与数据模型演进.md §4.6「m1x 保证金层草案（纸面定稿）」。
--
-- 编号登记（D9）：契约原文把本片写为 m32，但 m32 已被同契约 B2 片占用（settlement 打款状态机
-- 两列 + partner_wallet_flow 提现类型；其文件头已明文登记「B3（m1x 保证金，契约原定 m32）据此
-- 顺延为 m33」——m31 亦早被 F-124（review.client_request_id）占用）。本迁移照顺延纪律落为 m33。
--
-- 编号内偏离登记（契约 D8 两处「一并加/已建」与 B2 实际落盘的出入，均在 B3 补齐）：
--   ① settlement.deposit_amount：契约 D8 写「settlement 新列 deposit_amount 默认 0，m31 一并加」
--     （m31 在契约落笔时指打款片，后顺延为 m32），但 B2 实际落盘的 m32 只加了 paid_time/pay_channel
--     两列、未含 deposit_amount（B2 分面不含代扣）——本片照 D8 的列定义（DECIMAL(10,2) NOT NULL
--     DEFAULT 0）补建该列，代扣写路径=生成账期事务尾部追加步（MerchantDepositServiceImpl#deductOnSettlement）。
--   ② merchant_deposit.paid_amount：06 册 §4.6 草案无此列（草案以 deposit_amount 现金预缴 +
--     retained_amount 应结款留存两列承载缴存，其中 retained_amount 与纸面 m19 的
--     settlement.retain_amount 联动）。P10-5 D8 的代扣语义是「代扣即实缴」（type=2 应结款代扣
--     直接入保证金账户），与「结算留存」两套口径——为支撑 D8 代扣闭环的判断与累加（已缴额
--     paid_amount 与 required_amount 比较、代扣后 +=deduct），最小补齐本列并登记偏离；
--     草案的 deposit_amount/retained_amount 两列逐字保留（恒 0：现金预缴兜底不做、留存划转
--     留真实轨——登记，见契约 D8「现金预缴兜底不做（无真实第三方商家）」）。
--   ③ deposit_flow.channel：契约红线「资金流水通道字段一律显式 MOCK/SIM」要求流水可查通道，
--     06 册 §4.6 草案无通道列——最小补齐 channel VARCHAR(20) NOT NULL DEFAULT 'MOCK' 并登记偏离
--     （模拟轨代扣恒 MOCK，无真实资金流）。
--   除上述三处 ADD-only 补齐外，两表列清单/类型/注释/索引逐字照 06 册 §4.6 DDL 基线。
--
-- 本期唯一动作（ADD-only：只建新表只加新列，不动任何既有列）：
--   ① settlement 加 1 列：deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0（本期代扣入保证金额；
--     代扣后 payable 口径=net−deposit_amount，D8）；
--   ② CREATE TABLE IF NOT EXISTS merchant_deposit（商家保证金账户，触发式缴存，一主体一账户 uk_merchant）；
--   ③ CREATE TABLE IF NOT EXISTS deposit_flow（保证金流水，cash_flow 余额快照范式）。
-- 存量行零 UPDATE 零回填：deposit_amount 既有行 DEFAULT 0（=未代扣语义，与既有行为一致）。
--
-- 幂等写法（跑两次零报错零改动）：
--   · 两新表 CREATE TABLE IF NOT EXISTS 天然幂等（m28 同款）；
--   · deposit_amount 加列以 information_schema.COLUMNS 判存在性，已存在即跳过（m32 同款骨架）；
--   · 本文件无 DROP / TRUNCATE / DELETE / 业务 UPDATE，可整文件重复执行。
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 fresh_db < m33-migration.sql
-- 同步：init.sql 的 settlement 建表体与本文件逐字一致（新库走 init.sql 全量重建，存量库走本文件）。
-- ================================================
USE fresh_db;

-- ----------------------------
-- ① settlement 加 deposit_amount（P10-5 D8/m33：本期代扣入保证金额；幂等判定查列名，骨架照 m32 ①）
-- ----------------------------
SET @m33_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE settlement ADD COLUMN deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''本期代扣入保证金额（P10-5 D8/m33）：生成账期事务尾部代扣步写入；代扣后 payable=net−deposit_amount；06 册 §4.5 纸面无此列，为 D8 代扣闭环增列，04 册 §3.3「代扣优先」的 settlement 侧落点'' AFTER retain_amount',
        'SELECT ''[m33] settlement.deposit_amount 已存在，跳过'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement' AND COLUMN_NAME = 'deposit_amount'
);
PREPARE m33_stmt FROM @m33_ddl;
EXECUTE m33_stmt;
DEALLOCATE PREPARE m33_stmt;

-- ----------------------------
-- ② merchant_deposit 商家保证金账户（06 册 §4.6 DDL 逐字基线 + paid_amount 补列，偏离登记见文件头②）；
--    写路径：admin 设置档位（P10-5 D7 模拟触发器：运营手动置 required_amount 等价真实触发器①②③）
--    + 生成账期事务尾部代扣步（D8，deductOnSettlement）——两写都在既有事务/既有闸门内；
--    现金预缴（deposit_amount 列）无端点无入口（兜底形态不做，登记）。
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_deposit (
  id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '账户ID',
  merchant_id     BIGINT        NOT NULL COMMENT '商家ID（逻辑外键 merchant.id，一主体一账户）',
  category        VARCHAR(20)   NULL COMMENT '类目档位键（对齐 dish.category 11 类 init.sql:39）；具体金额待真实 GMV 校准，本草案不拍数值',
  required_amount DECIMAL(10,2) NULL COMMENT '当前档位应缴额；NULL=未启用触发式缴存',
  deposit_amount  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '现金预缴余额（兜底形态；P10-5 模拟轨无预缴端点，恒 0——登记）',
  retained_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '应结款留存累计（优先形态，与 settlement.retain_amount 联动；P10-5 模拟轨留存划转不启用，恒 0——登记）',
  paid_amount     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '已缴累计（P10-5 D8/m33 补列，06 册草案无此列——登记偏离）：代扣实缴额累计；D8 代扣判断 required_amount>0 且本列<required_amount → gap=required−本列、deduct=min(gap,payable)、代扣后本列+=deduct',
  status          VARCHAR(20)   NOT NULL DEFAULT 'none' COMMENT 'none未启用/active正常/arrears欠费待补/forfeited已没收/closed已清退退还（欠费→下架熔断执行口径同 §4.2「商家生命周期出口执行清单」：on_sale+seckill 清除，非仅 on_sale；P10-5 模拟轨只落 none/active/arrears 三态——熔断/没收/清退留真实轨，登记）',
  created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant (merchant_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家保证金账户（触发式缴存；P10-5 D7/D8 模拟轨：运营手动置档位=模拟触发器，代扣=生成账期事务尾部，档位数值 8000/5000/3000 为模拟轨草案值待真实 GMV 校准——04 册 §3.3）';

-- ----------------------------
-- ③ deposit_flow 保证金流水（06 册 §4.6 DDL 逐字基线 + channel 补列，偏离登记见文件头③）；
--    cash_flow 余额快照范式（balance_after=变动后已缴余额快照）；type 定方向 amount 恒正。
-- ----------------------------
CREATE TABLE IF NOT EXISTS deposit_flow (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  merchant_id   BIGINT        NOT NULL COMMENT '商家ID（逻辑外键 merchant.id）',
  type          TINYINT       NOT NULL COMMENT '1缴存 2应结款代扣 3扣罚 4退还 5没收 6留存划转（type 定方向，amount 恒正——对齐 cash_flow 惯例 init.sql:237-240）',
  amount        DECIMAL(10,2) NOT NULL COMMENT '正数金额',
  balance_after DECIMAL(10,2) NOT NULL COMMENT '变动后余额快照（cash_flow 三范式之一）',
  biz_type      VARCHAR(30)   NULL COMMENT '扣款白名单来源：claim理赔/act违约/regulatory监管连带/advance垫付（04 册 §3.4 四类）',
  biz_id        VARCHAR(64)   NULL COMMENT '来源单据号（订单号 VARCHAR(50) 口径对齐）',
  operator      VARCHAR(64)   NOT NULL COMMENT '操作主体留痕（补 AdminServiceImpl.java:93 缺口；联动 m15 admin_audit_log）',
  channel       VARCHAR(20)   NOT NULL DEFAULT 'MOCK' COMMENT '通道标注（P10-5 D8/m33 补列，06 册草案无此列——登记偏离）：模拟轨代扣恒 MOCK，无真实资金流（契约红线：资金流水通道字段一律显式 MOCK/SIM）',
  remark        VARCHAR(100)  NULL COMMENT '备注/申诉窗口指引',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_merchant (merchant_id, created_at),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='保证金流水（触发式缴存；P10-5 D8 模拟轨唯一写路径=生成账期事务尾部代扣步 type=2，channel 恒 MOCK）';

-- ----------------------------
-- 门禁断言 SELECT（只读、零副作用）
-- ----------------------------
-- 断言 1：settlement 列数必须 = 21（m32 后实测 20 + 本片 1；执行前 SELECT 复核为 20）
SELECT COUNT(*) AS settlement_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement';

-- 断言 2：merchant_deposit 列数必须到位（06 册 §4.6 九列 + paid_amount 补列 = 10 列）
SELECT COUNT(*) AS merchant_deposit_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'merchant_deposit';

-- 断言 3：deposit_flow 列数必须到位（06 册 §4.6 十列 + channel 补列 = 11 列）
SELECT COUNT(*) AS deposit_flow_columns
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deposit_flow';

-- 断言 4：三枚关键索引必须齐（merchant_deposit.uk_merchant/idx_status + deposit_flow 的两枚 KEY）
SELECT COUNT(*) AS deposit_key_indexes
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((TABLE_NAME = 'merchant_deposit' AND INDEX_NAME IN ('uk_merchant', 'idx_status'))
     OR (TABLE_NAME = 'deposit_flow' AND INDEX_NAME IN ('idx_merchant', 'idx_biz')));

-- 断言 5：两新表必须 0 行（写路径只有生成账期代扣步与 admin 置档位，本文件零写；
--         出现行即本文件之外有人写过）
SELECT COUNT(*) AS merchant_deposit_rows FROM merchant_deposit;
SELECT COUNT(*) AS deposit_flow_rows FROM deposit_flow;

-- 断言 6：settlement 既有行未代扣语义保持——存量行零回填（出现非 0 即本文件之外有人写过）
SELECT COUNT(*) AS settlement_deposit_amount_nonzero FROM settlement WHERE deposit_amount <> 0;
