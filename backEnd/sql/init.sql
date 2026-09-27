-- ================================================
-- 生鲜配送小程序 - 数据库初始化脚本
-- 执行方式：mysql -u root -p < init.sql
-- ================================================

CREATE DATABASE IF NOT EXISTS fresh_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fresh_db;

-- ----------------------------
-- 用户表（T-M2-01）：手机号登录账号，新手机号登录时静默注册
-- password_hash（m5-migration.sql 同步）/ invite_code（m9-migration.sql 同步）/
-- balance + cash_rewarded（m11-migration.sql 同步，F-04）：新库全量重建与存量增量两路结构一致
-- ----------------------------
DROP TABLE IF EXISTS user;
CREATE TABLE user (
    id            BIGINT       NOT NULL AUTO_INCREMENT       COMMENT '用户ID',
    phone         VARCHAR(20)  NOT NULL                      COMMENT '手机号（登录账号，UNIQUE）',
    password_hash VARCHAR(128) NULL                          COMMENT '密码哈希（T-M5）：PBKDF2WithHmacSHA256 存储串 base64(salt):base64(hash)，NULL=未设置过密码',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP     COMMENT '注册时间',
    invite_code   VARCHAR(8)   NULL                          COMMENT '邀请码（F-01）：8 位去混淆字符集（无 0/O/1/I），首次 GET /api/invite/me 时惰性生成；NULL=尚未生成',
    balance       DECIMAL(10,2) NOT NULL DEFAULT 0           COMMENT '邀请现金余额（F-04）：注册只冻结不入账，新人首单支付后入账挂钩增加；独立钱包不与下单抵扣打通',
    cash_rewarded INT          NOT NULL DEFAULT 0            COMMENT '已发邀请现金人数计数（F-04，封顶原子闸门）：冻结发放时条件自增 WHERE cash_rewarded<10',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_phone (phone),
    UNIQUE INDEX uk_user_invite_code (invite_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- 菜品表
-- ----------------------------
DROP TABLE IF EXISTS dish;
CREATE TABLE dish (
    id          BIGINT          NOT NULL    AUTO_INCREMENT  COMMENT '菜品ID',
    name        VARCHAR(100)    NOT NULL                    COMMENT '菜品名称',
    price       DECIMAL(10,2)   NOT NULL                    COMMENT '单价',
    image       VARCHAR(255)    NULL                         COMMENT '图片URL（T-M3-01：/static/images/dish-<id>.svg，空值前端回退 emoji 占位）',
    description TEXT                                 COMMENT '详细描述',
    category    VARCHAR(20)     NOT NULL                    COMMENT '分类（T-M3-12 对齐字典 code）：meat/vegetable/fastfood/wine/fruit/fresh/frozen/snack/dairy/grain/general',
    unit        VARCHAR(20)     NOT NULL                    COMMENT '单位：斤/盒/只/个/份/根/块/棵',
    stock       INT             NOT NULL    DEFAULT 0        COMMENT '库存',
    on_sale     TINYINT         NOT NULL    DEFAULT 1        COMMENT '是否在售（T-M3-09）：1上架/0下架',
    seckill     TINYINT         NOT NULL    DEFAULT 0        COMMENT '秒杀标记（T-M4-03）：1秒杀/0普通（种子按旧前端合成规则 id%3==0 迁移）',
    seckill_price DECIMAL(10,2) NULL                         COMMENT '秒杀价（T-M4-03）=ROUND(price*0.78,2)，非秒杀为 NULL',
    sold_count  INT             NOT NULL    DEFAULT 0        COMMENT '销量（T-M4-03）：前端进度条按 sold_count/(sold_count+stock) 真实比例渲染',
    limit_buy   INT             NOT NULL    DEFAULT 5        COMMENT '限购数量（T-M4-03）：[2,5,8] 按 id 循环',
    tags        VARCHAR(255)    NULL                         COMMENT '卖点标签（T-M4-03）：JSON 数组串，按分类写死',
    good_rate   VARCHAR(10)     NULL                         COMMENT '好评率文案（T-M4-03）：如 94.4%',
    emoji       VARCHAR(50)     DEFAULT ''                   COMMENT 'Emoji占位符',
    bg_color    VARCHAR(20)     DEFAULT ''                   COMMENT '背景颜色',
    create_time DATETIME        DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_time DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品表';

-- ----------------------------
-- 订单表
-- ----------------------------
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
    id                VARCHAR(50)   NOT NULL                    COMMENT '订单号（服务端雪花生成，ASSIGN_ID）',
    total_price       DECIMAL(10,2) NOT NULL                    COMMENT '总金额（服务端按 dish 现价重算）',
    address           VARCHAR(500)  NOT NULL                    COMMENT '自提点地址（自提点快照 M4 实体化前由下单入参可选携带）',
    phone             VARCHAR(20)   NOT NULL                    COMMENT '提货人手机号（归属过滤凭证，查询接口脱敏输出）',
    pickup_date       DATE          NULL                        COMMENT '自提日期（T-M3-04，服务端裁决：<23:00 明天 / >=23:00 后天）',
    status            VARCHAR(20)   NOT NULL    DEFAULT 'pending_pickup' COMMENT '订单状态三态：pending_pickup待自提/completed已完成/cancelled已取消',
    create_time       DATETIME      DEFAULT CURRENT_TIMESTAMP    COMMENT '下单时间',
    update_time       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    client_request_id VARCHAR(64)   NULL                        COMMENT '客户端幂等标识（T-M1-09），同值重复提交返回首次订单',
    user_id           BIGINT        NULL                        COMMENT '下单用户ID（JWT token 解析落库，T-M2-03；登录时认领 user_id IS NULL 的存量单）',
    store_id          BIGINT        NULL                        COMMENT '自提点ID（T-M4-01，下单入参可选，快照来源）',
    store_name        VARCHAR(100)  NULL                        COMMENT '自提点名称快照（T-M4-01，下单时按 storeId 查 store 落库）',
    store_address     VARCHAR(255)  NULL                        COMMENT '自提点地址快照（T-M4-01，下单时按 storeId 查 store 落库）',
    pay_status        TINYINT       NOT NULL    DEFAULT 0        COMMENT '支付状态（T-M2-05）：0未支付/1已支付',
    pay_time          DATETIME      NULL                        COMMENT '支付时间（T-M2-05）',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_client_request_id (client_request_id),
    INDEX idx_phone (phone),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- ----------------------------
-- 订单明细表
-- ----------------------------
DROP TABLE IF EXISTS order_item;
CREATE TABLE order_item (
    id          BIGINT          NOT NULL    AUTO_INCREMENT  COMMENT '明细ID',
    order_id    VARCHAR(50)     NOT NULL                    COMMENT '订单ID',
    dish_id     BIGINT          NOT NULL                    COMMENT '菜品ID',
    dish_name   VARCHAR(100)    NOT NULL                    COMMENT '菜品名称',
    price       DECIMAL(10,2)   NOT NULL                    COMMENT '单价',
    quantity    INT             NOT NULL                    COMMENT '数量',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

-- ----------------------------
-- 自提点门店表（T-M4-01）
-- ----------------------------
DROP TABLE IF EXISTS store;
CREATE TABLE store (
    id         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自提点ID',
    name       VARCHAR(100) NOT NULL                 COMMENT '门店名称',
    address    VARCHAR(255) NOT NULL                 COMMENT '门店地址',
    service    VARCHAR(50)  NULL                     COMMENT '服务说明（如 冷冻冷藏）',
    status     VARCHAR(20)  NOT NULL DEFAULT 'open'  COMMENT '营业状态（T-M4-01）：open营业/closed停业',
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自提点门店表';

-- 种子 4 家：沿用前端现有自提点数据（3 家营业 + 1 家停业），显式 id 保证快照引用稳定
INSERT INTO store (id, name, address, service, status) VALUES
(1, '高黎柱发士多店(请自备袋子谢谢)', '广东省佛山市顺德区容桂高黎环涌西路桥灵坊23号', '支持冷冻/冷藏', 'open'),
(2, '高黎惠民百货店', '广东省佛山市顺德区容桂高黎惠民路12号', '冷冻冷藏', 'open'),
(3, '容桂天佑城自提点', '广东省佛山市顺德区容桂街道天佑城B座1层', '冷冻冷藏', 'open'),
(4, '朝阳社区便利店', '广东省佛山市顺德区容桂朝阳路38号', '冷冻冷藏', 'closed');

-- ----------------------------
-- 用户优惠券表（T-M4-02）
-- ----------------------------
DROP TABLE IF EXISTS user_coupon;
CREATE TABLE user_coupon (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '券记录ID',
    user_id    BIGINT        NOT NULL                COMMENT '持券用户ID',
    coupon_key VARCHAR(50)   NOT NULL                COMMENT '券模板标识：coupon_1/coupon_2/coupon_3',
    threshold  DECIMAL(10,2) NOT NULL                COMMENT '使用门槛（满 X 元可用）',
    amount     DECIMAL(10,2) NOT NULL                COMMENT '抵扣金额（减 Y 元）',
    status     VARCHAR(20)   NOT NULL DEFAULT 'available' COMMENT '券状态：available可用/used已使用',
    order_id   VARCHAR(64)   NULL                    COMMENT '核销订单号（置 used 时回写；订单取消回补时清空）',
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    expire_at  DATETIME      NULL                    COMMENT '过期时间（M6）：领取时写 created_at+7 天（见 UserCoupon.VALID_DAYS）；NULL=永不过期',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表';

-- ----------------------------
-- 评价表（T-M4-05 加 audit_status；Mock 评价数据见 sql/review.sql）
-- ----------------------------
DROP TABLE IF EXISTS review;
CREATE TABLE review (
    id           BIGINT       NOT NULL    AUTO_INCREMENT  COMMENT '评价ID',
    dish_id      BIGINT       NOT NULL                    COMMENT '菜品ID',
    user_id      BIGINT       NULL                        COMMENT '评价用户ID（M6）：服务端按登录用户落库；NULL=历史/种子数据（归属不可考）',
    rating       TINYINT      NOT NULL    DEFAULT 5       COMMENT '评分1-5',
    content      VARCHAR(500) NOT NULL                    COMMENT '评价内容',
    reviewer     VARCHAR(50)  NOT NULL    DEFAULT '匿名用户' COMMENT '评价人',
    audit_status VARCHAR(20)  NULL                        COMMENT '审核状态（T-M4-05）：NULL 正常展示 / pending 命中软词待人工复核（默认不展示）',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP    COMMENT '评价时间',
    PRIMARY KEY (id),
    INDEX idx_dish_id (dish_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评价表';

-- ----------------------------
-- 免费领商品活动表（M7）：单活动模型（种子固定 id=1），跨日首次领取由
-- FreeActivityMapper.tryClaimQuota 条件 UPDATE 原子重置 daily_date/daily_claimed（无 COUNT+INSERT 竞态）
-- ----------------------------
DROP TABLE IF EXISTS free_activity;
CREATE TABLE free_activity (
    id            BIGINT      NOT NULL AUTO_INCREMENT       COMMENT '活动ID（单活动模型，种子固定 1）',
    dish_id       BIGINT      NOT NULL                      COMMENT '今日免费商品ID（须 on_sale=1；种子指向非秒杀商品，免费领取销量口径见 MockPayService）',
    daily_quota   INT         NOT NULL DEFAULT 100          COMMENT '每日限量份数（>0 才可领，置 0 等效暂停）',
    daily_date    DATE        NULL                          COMMENT '计数器所属日期：与当日一致才累计，NULL/过去日期在首次领取时原子重置',
    daily_claimed INT         NOT NULL DEFAULT 0            COMMENT '当日已领份数（原子条件 UPDATE 自增）',
    status        VARCHAR(20) NOT NULL DEFAULT 'online'     COMMENT '活动状态：online进行/offline下线',
    create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    update_time   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_dish_id (dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品活动表';

-- ----------------------------
-- 免费领商品领取记录表（M7）：uk_user_date(user_id, claim_date) 是"一人一天一份"的权威约束
-- （MySQL UNIQUE 对 NULL 不去重，故 user_id/claim_date 显式 NOT NULL，防刷约束不可空置）
-- ----------------------------
DROP TABLE IF EXISTS free_claim;
CREATE TABLE free_claim (
    id          BIGINT      NOT NULL AUTO_INCREMENT       COMMENT '领取记录ID',
    user_id     BIGINT      NOT NULL                      COMMENT '领取用户ID（token 解析，服务端写入）',
    dish_id     BIGINT      NOT NULL                      COMMENT '领取商品ID',
    order_id    VARCHAR(64) NOT NULL                      COMMENT '关联0元订单号（orders.id 雪花串，建单前预生成回写）',
    claim_date  DATE        NOT NULL                      COMMENT '领取自然日（服务器时区，事务内单次采样）',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP     COMMENT '领取时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, claim_date),
    INDEX idx_claim_date (claim_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品领取记录表';

-- ----------------------------
-- 邀请新人关系表（F-01，m9-migration.sql 同步）：uk_invitee 是"一人仅可被邀请一次"的权威约束
-- （invitee_id 显式 NOT NULL：MySQL UNIQUE 对 NULL 不去重，防刷约束不可空置，对齐 free_claim 惯例）；
-- status 0→1 由新人首单支付成功后原子置位（影响行数=1 才发邀请人奖励券，防并发重复发券）；
-- invitee_phone 为脱敏展示快照（138****1235），表内无原手机号，无手机号枚举面（F-01.0 攻击面裁定）；
-- 奖励券模板 invite_reward[50,15] / newbie_gift[30,8] 为代码常量（CouponServiceImpl.TEMPLATES），无库表种子
-- ----------------------------
DROP TABLE IF EXISTS invite_relation;
CREATE TABLE invite_relation (
    id            BIGINT      NOT NULL AUTO_INCREMENT            COMMENT '记录ID',
    inviter_id    BIGINT      NOT NULL                           COMMENT '邀请人 user.id',
    invitee_id    BIGINT      NOT NULL                           COMMENT '被邀新人 user.id（uk_invitee 一人仅可被邀请一次）',
    invitee_phone VARCHAR(20) NOT NULL                           COMMENT '被邀人手机号快照（对外仅脱敏展示 138****1235，见 F-01.0）',
    status        TINYINT     NOT NULL DEFAULT 0                 COMMENT '0=已注册未完成首单 1=已完成首单（奖励已处理）',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间（验证码登录建号同事务写入）',
    rewarded_at   DATETIME    NULL                               COMMENT '邀请人奖励发放时间（首单支付置位 status=1 时写入；NULL=未发奖）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invitee (invitee_id),
    KEY idx_inviter (inviter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请新人关系（F-01）';

-- ----------------------------
-- 邀请现金流水表（F-04，m11-migration.sql 同步）：
-- type 1=邀请奖励 2=提现；amount 恒为正数，type=2 提现为支出方向（记提现时全部余额，满 20 提全部余额口径）；
-- status 承载「冻结→入账」两态（F-04.1 评审修订）：type=1 注册时 status=0 冻结（不动 user.balance），
-- 新人首单支付后 0→1 入账（条件置位防并发重复入账）；type=2 提现恒为 1；
-- idx_invitee 支撑入账挂钩按 invitee_id 检索冻结流水。
-- 现金常量 CASH_REWARD_AMOUNT=5.00 / CASH_REWARD_MAX=10 / WITHDRAW_MIN=20.00 为 InviteService 代码常量，无库表种子
-- ----------------------------
DROP TABLE IF EXISTS cash_flow;
CREATE TABLE cash_flow (
    id            BIGINT        NOT NULL AUTO_INCREMENT            COMMENT '记录ID',
    user_id       BIGINT        NOT NULL                           COMMENT '归属用户（邀请人/提现人）',
    type          TINYINT       NOT NULL                           COMMENT '1=邀请奖励 2=提现',
    status        TINYINT       NOT NULL DEFAULT 1                 COMMENT '1=已入账 0=冻结（type=1 注册时冻结，新人首单支付后 0→1 入账）',
    amount        DECIMAL(10,2) NOT NULL                           COMMENT '正数金额；type=2 提现为支出（记提现时全部余额）',
    balance_after DECIMAL(10,2) NOT NULL                           COMMENT '变动后余额快照',
    invitee_id    BIGINT        NULL                               COMMENT 'type=1 时的新人 user.id',
    remark        VARCHAR(100)  NULL                               COMMENT '备注（如 提现）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id, created_at),
    KEY idx_invitee (invitee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请现金流水（F-04）';

-- ================================================
-- Mock 数据 - 肉蛋水产/速冻/生鲜/水果（11条，T-M3-12 分类对齐字典 code）
-- ================================================
INSERT INTO dish (name, price, image, description, category, unit, stock, emoji, bg_color) VALUES
('精品五花肉', 25.80, '/static/images/dish-1.svg','【产地直采】精选优质五花肉，肥瘦相间，层次分明，口感细腻。五层腹脂比例完美，炖煮后油脂化开而不腻，肉香浓郁。\n\n【烹饪推荐】红烧肉、回锅肉、蒜泥白肉、烤肉片。切块冷水下锅焯水去腥，再用热油煸炒至表面微焦，加生抽、老抽、冰糖慢炖40分钟即可。\n\n【储存建议】0-4°C 冷藏可保存3天，-18°C 冷冻可保存6个月。', 'meat', '斤', 50, '🥩', '#FFF0F0'),

('鲜嫩鸡胸肉', 18.50, '/static/images/dish-2.svg','【低脂高蛋白】精选谷物饲养白羽鸡鸡胸肉，肉质细嫩，纹理清晰，每100g含蛋白质约23g，脂肪仅1.2g，是健身人群和健康饮食的理想选择。\n\n【烹饪推荐】香煎鸡排、鸡胸肉沙拉、宫保鸡丁、咖喱鸡肉。腌制时加入料酒、姜片去腥，低温慢煎至两面金黄，口感最嫩。\n\n【储存建议】0-4°C 冷藏可保存2天，建议切块分装后冷冻保存。', 'meat', '斤', 80, '🍗', '#FFF5EE'),

('澳洲肥牛卷', 45.00, '/static/images/dish-3.svg','【进口优质】澳洲天然牧场谷饲牛肉，精选上脑部位，脂肪分布均匀如雪花，入口即化。\n\n【规格】每盒250g，约18-20片，厚度约2mm，适合快速涮烫。\n\n【烹饪推荐】涮火锅、肥牛盖饭、爆炒肥牛。涮锅时建议在沸水中轻轻翻滚3-5秒即可，蘸麻酱或沙茶酱风味更佳。\n\n【储存建议】-18°C 冷冻保存，解冻后不可再次冷冻，建议即食。', 'frozen', '盒', 30, '🥓', '#FFF8F0'),

('新鲜排骨', 32.00, '/static/images/dish-4.svg','【当天现宰】精选农家土猪排骨，肉质紧实有弹性，骨髓丰富饱满，炖汤营养价值极高。\n\n【烹饪推荐】排骨莲藕汤、红烧排骨、糖醋排骨、烤排骨。焯水时加入姜片和料酒去腥，大火煮开后撇去浮沫，再小火慢炖1小时以上。\n\n【储存建议】0-4°C 冷藏可保存2-3天，冷冻可保存3个月。', 'meat', '斤', 40, '🍖', '#FFF5F5'),

('黑猪梅花肉', 38.80, '/static/images/dish-5.svg','【散养黑猪】采用林下散养模式，黑猪运动量大，肉质紧实不柴，大理石花纹清晰美观，肉香醇厚。\n\n【推荐吃法】香煎猪排、韩式烤肉、叉烧肉。梅花肉位于猪肩胛部，瘦肉中夹着细密的脂肪，煎烤后油脂香气四溢，口感鲜嫩多汁。\n\n【储存建议】0-4°C 冷藏保存，建议3天内食用，口感最佳。', 'meat', '斤', 25, '🥩', '#FDF5E6'),

('鲜活大闸蟹', 68.00, '/static/images/dish-6.svg','【阳澄湖产地直发】正宗阳澄湖大闸蟹，膏黄饱满醇厚，蟹肉细腻鲜甜，每只蟹都经过精挑细选，腿脚完整有力。\n\n【规格】公蟹3.5-4两/只，母蟹2.5-3两/只，独立捆绑，配冰袋保鲜。\n\n【烹饪推荐】清蒸大闸蟹是最经典的吃法，蟹腹朝上，姜片垫底，沸水蒸15-20分钟，配上姜丝香醋，回味无穷。\n\n【储存建议】收到后请立即食用，若需保存请放入冰箱冷藏室，保持湿度，2天内食用最佳。', 'fresh', '只', 20, '🦀', '#FFF0F5'),

('深海鲈鱼', 28.80, '/static/images/dish-7.svg','【东海野生捕捞】东海深海鲈鱼，海水养殖环境下自然生长，鱼肉洁白如玉，刺少肉嫩，腥味极低。\n\n【营养价值】高蛋白低脂肪，富含DHA和EPA，对大脑发育和心血管健康有益。\n\n【烹饪推荐】清蒸鲈鱼（最推荐）、红烧鲈鱼、香煎鱼排。清蒸时鱼身划几刀便于入味，葱姜铺底，大火蒸8-10分钟，淋上热油和蒸鱼豉油。\n\n【储存建议】请尽快食用，0-4°C 冷藏可保存1-2天。', 'fresh', '斤', 35, '🐟', '#F0F8FF'),

('东北林下参', 88.00, '/static/images/dish-8.svg','【长白山野山参】来自东北长白山原始森林，野外自然生长环境，无人工干预，吸收天地灵气，滋补价值极高。\n\n【功效】大补元气、复脉固脱、补脾益肺、生津养血。适合体虚欲脱、久病虚弱者滋补。\n\n【食用方法】切片含服、炖汤、泡酒均可。每次用量3-9克，炖汤时宜文火慢炖1小时以上，使有效成分充分析出。\n\n【储存建议】置于阴凉干燥处保存，注意防虫防霉。', 'meat', '根', 15, '🌿', '#F5FFF5'),

('散养土鸡蛋', 22.00, '/static/images/dish-9.svg','【农家散养】正宗农家散养土鸡，自然觅食五谷杂粮，蛋黄橙红饱满，色泽金黄，蛋香浓郁，口感比普通鸡蛋更香更细腻。\n\n【规格】30枚/盒，每枚约50-55g，个头比普通鸡蛋略小，但营养密度更高。\n\n【烹饪推荐】白水煮蛋、荷包蛋、蛋炒饭、蒸蛋羹。煮蛋时冷水下锅，水开后小火煮6-8分钟，蛋黄软嫩凝固，口感最佳。\n\n【储存建议】大头朝上，存入冰箱冷藏室，15°C以下可保存30天。', 'meat', '盒', 60, '🥚', '#FFFAF0'),

('泰国金枕榴莲', 39.80, '/static/images/dish-10.svg','【泰国尖竹汶府】正宗泰国金枕头榴莲，产地直采，液氮速冻技术锁鲜，到家解冻后口感与新鲜榴莲无异。肉厚核小，香味浓郁，甜度极高。\n\n【品质保证】每箱约2.5-3斤，出肉率约35%，含3-4房果肉，开箱即可见金黄色果肉。\n\n【食用建议】室温解冻约2-3小时，或冰箱冷藏解冻过夜。直接食用果肉绵密香甜，也可制作榴莲披萨、榴莲蛋糕等甜品。\n\n【储存建议】未开封冷冻可保存3个月，冷藏解冻后请24小时内食用。', 'fruit', '斤', 18, '🥭', '#FFFDE0'),

('阿根廷红虾', 55.00, '/static/images/dish-11.svg','【南美野生捕捞】阿根廷南部海域纯净无污染，野生红虾在低温海水中自然生长，虾身呈自然橙红色，虾肉紧实弹牙，鲜甜无比。\n\n【规格】30/40规格，即每斤30-40只，大小均匀，虾身完整。\n\n【烹饪推荐】白灼红虾（最能保留原味）、蒜蓉粉丝蒸虾、香煎红虾、黄油焗虾。白灼时水开后下锅，加姜片去腥，大火煮3-4分钟即熟。\n\n【储存建议】-18°C 冷冻保存，解冻后请勿再次冷冻，建议即食。', 'fresh', '盒', 28, '🦐', '#F0F8FF');

-- ================================================
-- Mock 数据 - 蔬菜/生鲜/水果（11条，T-M3-12 分类对齐字典 code）
-- ================================================
INSERT INTO dish (name, price, image, description, category, unit, stock, emoji, bg_color) VALUES
('有机西兰花', 8.80, '', '【有机认证】有机种植西兰花，花球紧实饱满，色泽翠绿欲滴，未经农药和化肥污染，开袋即用，安全放心。\n\n【营养价值】维C含量是柑橘的2倍，膳食纤维丰富，有助于肠道健康，还含有萝卜硫素等抗氧化成分。\n\n【烹饪推荐】蒜蓉西兰花（最经典）、西兰花炒虾仁、白灼西兰花。清炒时大火快炒2-3分钟，保持脆嫩口感和翠绿色泽。\n\n【储存建议】0-4°C 冷藏可保存4-5天，建议购买后尽快食用。', 'vegetable', '颗', 100, '🥦', '#F0FFF0'),

('本地西红柿', 5.50, '', '【农户直供】本地合作农户当日采摘，自然成熟后才采摘，不使用催红剂，酸甜多汁，番茄味浓郁，是记忆中小时候的味道。\n\n【品种】粉红沙瓢，掰开后沙瓤明显，汁水丰富，甜中带酸，生吃和炒菜皆宜。\n\n【烹饪推荐】西红柿炒鸡蛋（国民菜）、西红柿蛋花汤、糖拌西红柿。西红柿炒蛋时先炒鸡蛋再炒西红柿，避免过度翻炒破坏口感。\n\n【储存建议】常温保存即可，成熟西红柿请在3天内食用，未熟的可在室温下放置2-3天。', 'vegetable', '斤', 120, '🍅', '#FFF5F5'),

('嫩豆腐', 3.50, '', '【传统工艺】精选东北非转基因黄豆，清晨现磨现做，采用卤水点浆传统工艺，豆腐口感滑嫩，豆香浓郁。\n\n【规格】约400g/块，厚度约5cm，质地细腻弹性好。\n\n【烹饪推荐】小葱拌豆腐（最简单）、麻婆豆腐、鲫鱼豆腐汤、煎豆腐。烹饪前用盐水浸泡10分钟可以增加豆腐韧性，不易碎。\n\n【储存建议】0-4°C 冷藏可保存2-3天，请浸泡在清水中保存（每天换水一次）。', 'fresh', '块', 200, '🧈', '#FFFDE7'),

('新鲜菠菜', 4.80, '', '【清晨采摘】当日清晨采摘的新鲜菠菜，叶片肥厚深绿，茎秆挺拔脆嫩，根部红艳饱满，菠菜独有的清甜香气明显。\n\n【营养价值】含铁量极高，每100g含铁约2.9mg，还富含叶酸、维生素A、C、K，是营养最丰富的绿叶蔬菜之一。\n\n【烹饪推荐】蒜蓉菠菜（最推荐）、菠菜蛋花汤、凉拌菠菜。焯水时加入少许盐和油，可以保持翠绿颜色，焯水时间控制在30秒以内。\n\n【储存建议】0-4°C 冷藏可保存2-3天，建议购买后尽早食用。', 'vegetable', '斤', 90, '🥬', '#F0FFF0'),

('高山土豆', 3.00, '', '【高海拔种植】来自云贵高原海拔1500米以上山区，昼夜温差大，土豆淀粉积累充分，口感软糯绵密，蒸熟后香气扑鼻。\n\n【品种】黄心小土豆，个头均匀，约鸡蛋大小，适合各种烹饪方式。\n\n【烹饪推荐】酸辣土豆丝（最经典）、红烧土豆、蒸土豆、狼牙土豆。土豆丝切好后用清水浸泡去除表面淀粉，炒制时大火爆炒，口感更加爽脆。\n\n【储存建议】阴凉干燥处保存，避免阳光直射，可存放10-15天。', 'vegetable', '斤', 150, '🥔', '#FFF8E1'),

('紫甘蓝', 6.50, '', '【进口品种】精选欧美进口品种紫甘蓝，叶球紧实，颜色紫红艳丽，含有普通甘蓝没有的花青素，抗氧化能力超强。\n\n【营养价值】花青素含量极高，具有抗氧化、延缓衰老功效，还含有丰富的维生素C和纤维素，热量极低，是减脂人群的理想食材。\n\n【烹饪推荐】凉拌紫甘蓝（最推荐）、紫甘蓝沙拉、紫甘蓝泡菜。生食时用刀切成细丝，加少许盐糖醋拌匀，腌制10分钟即可，口感脆爽。\n\n【储存建议】0-4°C 冷藏可保存7-10天，切开后请用保鲜膜包裹尽快食用。', 'vegetable', '颗', 80, '🥗', '#F5F0FF'),

('云南野生菌', 58.00, '', '【云南高原野生】来自云南香格里拉、丽江等高海拔原始森林，当日上山采摘，当夜冷链发出，保证绝对新鲜。品种包括松茸、牛肝菌、鸡枞菌、青头菌等随机搭配。\n\n【口感特点】香气馥郁，口感各异：松茸清甜、牛肝菌滑嫩、鸡枞菌鲜脆，每一口都是山野精华。\n\n【烹饪推荐】野生菌炖鸡（最经典）、菌菇火锅、爆炒牛肝菌。炖汤时只需加少许盐，最大程度保留菌菇本味。\n\n【储存建议】收到后请立即食用，或放入冰箱冷藏保存2-3天。野生菌请务必确保完全炒熟后再食用。', 'vegetable', '份', 25, '🍄', '#FFF8F5'),

('宁夏枸杞芽', 12.80, '', '【宁夏枸杞嫩芽】精选宁夏中卫枸杞树嫩芽，春分时节采摘，芽叶细嫩，清热去火，明目护肝，是春季限定的时令野菜。\n\n【口感】入口微苦回甘，带有枸杞特有的清香，嫩芽入口即化，无粗纤维感。\n\n【烹饪推荐】凉拌枸杞芽（最推荐）、枸杞芽蛋花汤、清炒枸杞芽。凉拌时焯水后过凉水，挤干水分，加蒜末、香油、生抽拌匀即可。\n\n【储存建议】0-4°C 冷藏可保存1-2天，春季时令，错过需等来年。', 'vegetable', '斤', 40, '🌱', '#F0FFF5'),

('奶油生菜', 7.20, '', '【水培种植】引进荷兰水培技术，全程温室大棚种植，无土栽培隔绝土壤污染，植株干净无泥，开袋即食，免洗即可直接食用。\n\n【品种特点】叶片翠绿饱满，质地脆嫩，中心呈淡黄色，口感清甜，几乎没有普通生菜的苦涩味。\n\n【烹饪推荐】直接生食做沙拉（最推荐）、三明治夹心、汉堡配菜。搭配千岛酱或凯撒酱，风味更佳。\n\n【储存建议】0-4°C 冷藏保存，建议3天内食用，确保最佳脆嫩口感。', 'vegetable', '棵', 110, '🥬', '#F5FFF0'),

('铁棍山药', 9.90, '', '【河南温县特产】正宗温县铁棍山药，国家地理标志产品，表皮有铁锈色斑点，故名"铁棍"，黏液蛋白含量极高，健脾养胃效果显著。\n\n【辨别真伪】正品铁棍山药笔直细长，表皮粗糙有锈斑，断面黏液丰富，拉丝明显，口感绵密微甜。\n\n【烹饪推荐】山药排骨汤（最经典）、蓝莓山药、炒山药片。处理时记得戴手套，山药黏液可能引起皮肤过敏。\n\n【储存建议】阴凉干燥处保存，冬季可存放15-20天，夏季建议尽快食用。', 'vegetable', '斤', 70, '🍠', '#FFF5E8'),

('新疆哈密瓜', 15.00, '', '【吐鲁番核心产区】新疆吐鲁番火焰山脚下种植，白天高温40°C+，夜晚降至20°C，昼夜温差极大，糖分积累充分，含糖量高达15%以上。\n\n【品种】西州蜜25号，网纹清晰，瓜肉橙黄，脆甜多汁，瓜香浓郁，入口即化。\n\n【食用建议】切开后请尽快食用，口感最佳。冰镇后食用风味更佳，夏日消暑圣品。\n\n【储存建议】整个瓜在阴凉处可保存10-15天，切开后请用保鲜膜包裹放入冰箱，2天内食用。', 'fruit', '个', 45, '🍈', '#FFFFF0');

-- ================================================
-- Mock 数据 - 补齐字典缺失类目（T-M3-12：fastfood/wine/dairy/snack/grain/general 各 1，
-- 保证 11 类每类至少 1 商品）
-- ================================================
INSERT INTO dish (name, price, image, description, category, unit, stock, emoji, bg_color) VALUES
('老坛酸菜牛肉面五连包', 12.90, '', '【经典速食】老坛酸菜风味方便面，酸爽开胃，面饼劲道弹滑，料包酸菜脆嫩足量。\n\n【烹饪推荐】热水冲泡3-5分钟即可食用，加个鸡蛋或几片青菜更营养；也可煮食，汤面更浓郁。\n\n【储存建议】常温干燥处保存，保质期6个月。', 'fastfood', '包', 60, '🍜', '#FFF8E7'),
('精酿白啤500ml×6罐', 39.90, '', '【德式工艺】全麦芽精酿白啤，酒体浑浊呈金黄色，泡沫细腻持久，带丁香与香蕉酯香。\n\n【饮用建议】冰镇后饮用口感最佳，佐餐海鲜、烧烤风味更佳。\n\n【储存建议】阴凉避光保存，请勿冷冻。', 'wine', '箱', 40, '🍺', '#FFF3E0'),
('冷鲜纯牛奶250ml×12盒', 49.00, '', '【每日鲜配】生牛乳含量100%，巴氏杀菌工艺锁住鲜活营养，奶香醇厚，口感清爽微甜。\n\n【营养价值】每100ml含3.6g优质乳蛋白、120mg原生高钙。\n\n【储存建议】2-6°C冷鲜保存，开封后请当日饮用完毕。', 'dairy', '箱', 50, '🥛', '#F0F8FF'),
('每日坚果混合装30袋', 69.90, '', '【科学配比】核桃、腰果、巴旦木、蔓越莓、蓝莓干等6种果干坚果独立小包，每日一袋均衡营养。\n\n【口感特点】坚果香脆、果干软糯，不添加香精与防腐剂。\n\n【储存建议】阴凉干燥处保存，开封后请尽快食用。', 'snack', '盒', 45, '🥜', '#FFF5E8'),
('五常长粒香大米5kg', 42.80, '', '【稻花香产区】黑龙江五常核心产区长粒香大米，米粒修长饱满，蒸煮后饭香四溢、绵软回甘。\n\n【食用建议】蒸煮前淘洗1-2遍，米水比约1:1.2，焖煮10分钟口感更佳。\n\n【储存建议】阴凉干燥处密封保存，防潮防虫。', 'grain', '袋', 35, '🍚', '#FFFAF0'),
('生鲜臻选组合礼盒', 128.00, '', '【送礼佳品】精选当季生鲜组合：进口水果、谷饲牛肉卷、海鲜水产等混搭装，配精致礼盒与保温冰袋。\n\n【适用场景】节日送礼、探亲访友、家庭聚餐。\n\n【储存建议】收货后请尽快分装冷藏或冷冻保存。', 'general', '份', 20, '🎁', '#F5FFF5');

-- ================================================
-- T-M4-03 秒杀字段种子迁移（与 sql/m4-migration.sql 同一套确定性公式，
-- 保证新库 init 与存量库迁移后的 dish 数据逐行一致）
-- ================================================
-- 图片补全：M3 起全部 28 商品均有 SVG 占位图（/static/images/dish-<id>.svg），
-- 与存量库实际数据对齐（存量库 12~28 的 image 已由 M3 执行时补齐）
UPDATE dish SET image = CONCAT('/static/images/dish-', id, '.svg');
-- 秒杀集与秒杀价：契约第 3 条 seckill=id%3==0、seckill_price=ROUND(price*0.78,2)
UPDATE dish SET seckill = CASE WHEN id % 3 = 0 THEN 1 ELSE 0 END;
UPDATE dish SET seckill_price = ROUND(price * 0.78, 2) WHERE seckill = 1;
UPDATE dish SET seckill_price = NULL WHERE seckill = 0;

-- 销量按 id 伪随机：复刻旧前端 soldText 伪随机种子 seed=(id*37+53)%97+3
-- （偶数 id 旧文案为 X.X 万件 → seed*1000；奇数 id 旧文案为 已卖 N 件 → seed*37）
UPDATE dish SET sold_count = CASE WHEN id % 2 = 0
    THEN (((id * 37 + 53) % 97) + 3) * 1000
    ELSE (((id * 37 + 53) % 97) + 3) * 37 END;

-- 限购：[2,5,8] 按 id 循环（与后端限购兜底表 LIMIT_BUY_TABLE 逐位对齐）
UPDATE dish SET limit_buy = CASE WHEN id % 3 = 0 THEN 2 WHEN id % 3 = 1 THEN 5 ELSE 8 END;

-- 卖点标签：按分类写死（JSON 数组串，接口直出数组）
UPDATE dish SET tags = '["冷链配送","新鲜现切"]' WHERE category = 'meat';
UPDATE dish SET tags = '["农户直供","当日现摘"]' WHERE category = 'vegetable';
UPDATE dish SET tags = '["方便速食","即冲即食"]' WHERE category = 'fastfood';
UPDATE dish SET tags = '["冰镇更佳","正品保障"]' WHERE category = 'wine';
UPDATE dish SET tags = '["产地直采","香甜多汁"]' WHERE category = 'fruit';
UPDATE dish SET tags = '["鲜活直达","破损包赔"]' WHERE category = 'fresh';
UPDATE dish SET tags = '["锁鲜冷链","整箱包邮"]' WHERE category = 'frozen';
UPDATE dish SET tags = '["独立包装","每日一袋"]' WHERE category = 'snack';
UPDATE dish SET tags = '["冷鲜配送","原生高钙"]' WHERE category = 'dairy';
UPDATE dish SET tags = '["当季新粮","颗粒饱满"]' WHERE category = 'grain';
UPDATE dish SET tags = '["臻选组合","精美礼盒"]' WHERE category = 'general';

-- 好评率：'9x.x%'，复刻旧前端公式 93 + (id*13)%70/10（范围 93.0~99.9%）
UPDATE dish SET good_rate = CONCAT(CAST(93 + ((id * 13) % 70) / 10 AS DECIMAL(4,1)), '%');

-- ================================================
-- M7 免费领商品活动种子（与 sql/m7-migration.sql 同一套，需在 dish 种子之后执行；
-- 指向在售非秒杀商品 dish id=1（精品五花肉），每日限量 100 份，换品/调量直接 UPDATE 本行
-- ================================================
INSERT INTO free_activity (id, dish_id, daily_quota, daily_claimed, status) VALUES
(1, 1, 100, 0, 'online');
