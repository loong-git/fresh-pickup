-- ================================================
-- 生鲜配送小程序 - 数据库初始化脚本
-- 执行方式：mysql -u root -p < init.sql
-- ================================================
-- 结构同步声明（F-13/G-02 Step 0 并库，已同步至 m21-migration.sql）：全新库仅执行本文件即可撑起全部现有链路，
-- 无需再跑 m3~m21 增量；本文件对既有列定义与既有种子数值零改动（只做加列/加表/加索引/加种子）。
--   m3  dish.on_sale + orders.pickup_date + 六类补齐商品种子（category 注释/image 口径同 m3）
--   m4  store + user_coupon 表 + orders 自提点快照三列 + dish 秒杀六列 + review.audit_status
--   m5  user.password_hash
--   m6  user_coupon.expire_at + review.user_id + review.idx_user_id
--   m7  free_activity + free_claim + 活动种子
--   m8  free_activity.threshold + free_goods_pool（含 6 行池种子）
--   m9  user.invite_code + uk_user_invite_code + invite_relation
--   m10 dish.ft_dish_search（FULLTEXT ngram）+ dish_alias（含 8 行别名种子）
--   m11 user.balance + user.cash_rewarded + cash_flow
--   m12 store.lng + store.lat（列注释在 m13 定稿口径基础上补充说明）
--   m13 store 四家门店坐标真源值（本文件 UPDATE 直落 m13 定稿值）
--   m14 user.avatar
--   m15 merchant + merchant_profile + admin_audit_log + user.role + user.merchant_id + uk_user_merchant（含平台自营主体种子）
--   m16 merchant_store + dish.merchant_id + order_item.merchant_id + store.owner_user_id + idx_merchant/idx_owner
--   m17 orders.pickup_code + uk_pickup_code + order_item.fulfill_status + ops_log
--       （F-18/G-04 Step 1；存量取货码回填只在 m17-migration.sql 里做，本文件不回填——见 init.sql 订单明细表注释先例）
--   m18 store 六列 open_time/close_time/daily_capacity/accepting/contact_phone/photos + store_apply（含 BE-3 生成列
--       pending_guard + 唯一键 uk_user_pending(user_id, pending_guard)，防并发双 pending）
--       + arrival_batch（uk_store_date）+ arrival_item（含明日/planned 演示批次两条种子，F-18.10/G-04 Step 4a）
--       （m18 ⑪ 的演示主理人归属归位 UPDATE 不并入本文件，且那条链在 m18 里受 @m18_demo_owner 开关控制、默认 0 不执行（BE-1）：
--        新库无待归位的历史绑定，store 种子直接带 owner_user_id 值即可——分工：迁移侧靠开关、init 侧靠种子带值，
--        同订单表 pickup_code 回填注释先例；store 种子 INSERT 的列清单加 owner_user_id，既有 5 列数值零改动）
--   m19 admin_user（运营账号表，F-20/G-06 Step 1：username + UNIQUE uk_admin_username + password_hash PBKDF2
--       + status/last_login_at；零种子——账号由部署侧建，口令只落 gitignore 的本地文件）
--   m20 admin_audit_log 的 idx_action(action) 与 idx_actor_action(actor, action)（F-49：运营端审计页四枚
--       「常用筛选」预设 + 独立 COUNT 的索引，选型与实测数字见该表上方注释与 m20-migration.sql）
--   m21 merchant_apply（F-106 商家自助入驻 · 片 A）：生成列 pending_guard + 唯一键 uk_user_pending(user_id, pending_guard)
--       照 store_apply 的并发范式，封死同用户并发双 pending；结构与 m21-migration.sql 逐字一致
--   m22 merchant_apply.licenses + merchant_profile.licenses（F-107 多证照扩展）：两列均 TEXT NULL，
--       申请侧存证照列表 JSON 文本、过审时原样复制；单图 business_license 保留不动（兼容老客户端/老读路径）
--   m23 dict_group + dict_item（F-108 字典本地化）：结构逐字照契约 §0.2，本文件只同步两处建表体；
--       4 组 / 23 条词条回填只存在于 m23-migration.sql 一处真源（INSERT IGNORE 幂等），重建后随 m3~m23 重放落库
--       （F-115 起新增 seckill_session 组的 3 行回填例外：并库见下方 m25 段）
--   m24 coupon_template（F-113 券模板表化）：结构 + 4 行回填与 m24-migration.sql 逐字一致
--       （回填值照旧 CouponServiceImpl.TEMPLATES 常量；title 留 NULL 走前端「满X减Y」拼装）；
--       切表后 TEMPLATES/isLifetimeOnce 删除，表为唯一真源；limit_per_user=1 与常量时代 lifetime 语义逐字等价
--   m25 dict_group/dict_item 各回填（F-115 秒杀场次契约化）：seckill_session 组 1 行 + start/end 词条 2 行
--       （INSERT ... SELECT WHERE NOT EXISTS 幂等），与 m25-migration.sql 逐字一致；definition 与原
--       SeckillController 常量逐字一致（00:00/23:00），迁移保真 = C 端出参零变化；m23 的 23 条种子
--       仍只在 m23-migration.sql 一处真源，m25 这 3 行是契约 §1 点名的并库例外
--   m19 admin_user 运营账号表（F-20/G-06 Step 1，D-4 列口径）：username + uk_admin_username + password_hash（复用 PasswordUtil 的
--       PBKDF2 现格式）+ display_name + status（active/disabled）+ last_login_at + create_time/update_time；零外键、无手机号邮箱；
--       **本文件与 m19-migration.sql 都不放任何 admin_user 种子**（明文口令入仓=RD 红线，连 dev 默认账号也不建），
--       账号由主会话用一次性 SQL 造；两处 CREATE 体逐字一致（同 m17 ops_log / m18 store_apply 的先例，改结构须两处同步）
-- ================================================

CREATE DATABASE IF NOT EXISTS fresh_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE fresh_db;

-- ----------------------------
-- 用户表（T-M2-01）：手机号登录账号，新手机号登录时静默注册
-- password_hash（m5-migration.sql 同步）/ invite_code（m9-migration.sql 同步）/
-- balance + cash_rewarded（m11-migration.sql 同步，F-04）：新库全量重建与存量增量两路结构一致
-- role + merchant_id + uk_user_merchant（m15-migration.sql 同步，F-12/G-01 商家化地基）：
--   存量行 ALTER 后由 DEFAULT 语义自动 role='user'，零 UPDATE 零回填；merchant_id NULL=非商家
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
    avatar        TEXT         NULL                          COMMENT '头像（R8）：data:image/(jpeg|png|webp);base64, 前缀，≤60000 字符；NULL=未设置（前端兜默认 SVG 头像）',
    role          VARCHAR(20)  NOT NULL DEFAULT 'user'       COMMENT '角色（F-12/m15）：user 普通用户 / merchant 商家（admin 预留不启用——平台 Admin 走 X-Admin-Key 独立线）',
    merchant_id   BIGINT       NULL                          COMMENT '归属商家ID（F-12/m15，逻辑外键 merchant.id）；NULL=非商家；uk_user_merchant 一人一主体',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_phone (phone),
    UNIQUE INDEX uk_user_invite_code (invite_code),
    UNIQUE KEY uk_user_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- 菜品表
-- ft_dish_search（m10-migration.sql 同步，F-03 搜索升级）：ngram 为 MySQL 内置中文解析器（默认
-- ngram_token_size=2，按二元切分），对 name/description/tags 三列建全文索引，替代 name 单列 LIKE；
-- 后端 DishMapper.java:55-57 的 MATCH ... AGAINST 直接依赖本索引，缺失则搜索链路报错（FULLTEXT
-- 由 MySQL 随 DML 自动维护，管理改商品即时生效，无需刷索引）
-- merchant_id（m16-migration.sql 同步，F-12/G-01 归属层）：NOT NULL DEFAULT 1，dish 种子 INSERT 不含该列时
-- 由 DEFAULT 语义自动落 1（平台自营），与存量库 ALTER 后行为逐行一致；C 端读链路不带归属条件（F-13 R1'）
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
    merchant_id BIGINT          NOT NULL    DEFAULT 1        COMMENT '归属商家（F-12/m16，逻辑外键 merchant.id）；1=平台自营；存量行由 DEFAULT 语义自动覆盖零 UPDATE',
    bg_color    VARCHAR(20)     DEFAULT ''                   COMMENT '背景颜色',
    create_time DATETIME        DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_time DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_category (category),
    INDEX idx_merchant (merchant_id),
    FULLTEXT INDEX ft_dish_search (name, description, tags) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='菜品表';

-- ----------------------------
-- 订单表
-- pickup_code + uk_pickup_code（m17-migration.sql 同步，F-18/G-04 取货码）：6 位数字全局唯一，支付置态事务内生成；
-- 可空而非 NOT NULL——MySQL UNIQUE 允许多行 NULL（uk_client_request_id 同型先例），未支付/已取消单留 NULL；
-- 本文件是新库全量重建，无存量已支付待自提单，故不做 m17 的哈希回填（回填只在 m17 存量迁移里做，同 order_item 注释先例）
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
    pay_status        TINYINT       NOT NULL    DEFAULT 0        COMMENT '支付状态（T-M2-05）：0未支付/1已支付/2已退款（2 由「已支付单被取消」写入，用户端 OrderServiceImpl#restockAfterCancel 与管理端 AdminServiceImpl#updateOrderStatus 同口径走条件 UPDATE 1→2。退款时间不单设列，orders 表无 refund_time，退款时刻由 update_time 隐含）',
    pay_time          DATETIME      NULL                        COMMENT '支付时间（T-M2-05）',
    pickup_code       CHAR(6)       NULL                        COMMENT '取货码（G-04/m17，M-12）：6 位数字全局唯一，支付置态事务内生成（D-3）；存量已支付待自提单按 sha2(id+盐) 派生回填（D-2）；核销成功后不清空，幂等判定依据；NULL=未支付或已取消',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_client_request_id (client_request_id),
    UNIQUE KEY uk_pickup_code (pickup_code),
    INDEX idx_phone (phone),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- ----------------------------
-- 订单明细表
-- merchant_id（m16-migration.sql 同步，F-12/G-01 归属层）：下单时自 dish.merchant_id 落的供货商家快照
-- （对齐 orders.store_name 下单快照惯例），防商家改归属/删品后历史履约与对账漂移；
-- 可空而非 NOT NULL DEFAULT 1 是语义正确性优先——新库无历史明细，故本文件不做 m16 的 JOIN 回填
-- fulfill_status（m17-migration.sql 同步，F-18/G-04 履约旁路列）：NOT NULL DEFAULT 'normal'，与订单三态正交（D-10 不加中间态）；
-- 存量行靠 DEFAULT 自动为 normal，m17 亦无回填 UPDATE；一期唯一写入方=商家缺货登记 PUT /api/merchant/order-items/{id}/shortage
-- ----------------------------
DROP TABLE IF EXISTS order_item;
CREATE TABLE order_item (
    id          BIGINT          NOT NULL    AUTO_INCREMENT  COMMENT '明细ID',
    order_id    VARCHAR(50)     NOT NULL                    COMMENT '订单ID',
    dish_id     BIGINT          NOT NULL                    COMMENT '菜品ID',
    dish_name   VARCHAR(100)    NOT NULL                    COMMENT '菜品名称',
    price       DECIMAL(10,2)   NOT NULL                    COMMENT '单价',
    quantity    INT             NOT NULL                    COMMENT '数量',
    merchant_id BIGINT          NULL                        COMMENT '供货商家快照（F-12/m16，下单时自 dish.merchant_id 落库；NULL=历史行回填兜底）',
    fulfill_status VARCHAR(20)  NOT NULL DEFAULT 'normal'   COMMENT '履约旁路状态（M-19/D-10）：normal/shortage，与订单三态正交；一期唯一写入方=商家缺货登记 PUT /api/merchant/order-items/{id}/shortage',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

-- ----------------------------
-- 自提点门店表（T-M4-01）：lng/lat 列（m12-migration.sql 同步），坐标值高德 GCJ-02 真源（m13-migration.sql 同步）
-- owner_user_id（m16-migration.sql 同步，F-12/G-01 归属层）：主理人 user.id，NULL=平台直营；
-- 自 m18 起下方种子显式带该列（id=1 → user 1 承包，2/3/4 → NULL 平台直营/停业未入驻），不再是「种子不含该列、零回填」；
-- 逻辑外键无 FOREIGN KEY 约束（全库惯例），故不依赖 user 表是否已建行
-- 门店信息六列（m18-migration.sql 同步，F-18.10/G-04 Step 4a）：open_time/close_time（仅展示，不参与 23:00 全局截单=RE 红线）、
-- daily_capacity（D-8 NULL=不限，一期只展示不自动降级）、accepting（满容/休市细粒度开关，与 status 的 open/closed 正交）、
-- contact_phone/photos（RD/D-12：GET /api/stores 一律不下发这两列，photos 一期无上传端点 D-18）
-- m18 ⑪ 的归属归位 UPDATE 不并入本文件——新库无「门禁把 store 1 临时绑到 uid 40/43/45」的待归位状态，
-- 种子直接带目标值即可，不需要 m18 里的 @m18_demo_owner 开关（该开关只服务存量演示库归位场景，默认 0=不改数据，BE-1）
-- ----------------------------
DROP TABLE IF EXISTS store;
CREATE TABLE store (
    id         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '自提点ID',
    name       VARCHAR(100) NOT NULL                 COMMENT '门店名称',
    address    VARCHAR(255) NOT NULL                 COMMENT '门店地址',
    service    VARCHAR(50)  NULL                     COMMENT '服务说明（如 冷冻冷藏）',
    status     VARCHAR(20)  NOT NULL DEFAULT 'open'  COMMENT '营业状态（T-M4-01）：open营业/closed停业',
    lng        DECIMAL(10,6) NULL                    COMMENT '经度（F-06 地图）：高德 GCJ-02 真源，与底图同源',
    lat        DECIMAL(10,6) NULL                    COMMENT '纬度（F-06 地图）：高德 GCJ-02 真源，与底图同源',
    owner_user_id BIGINT     NULL                    COMMENT '主理人 user.id（F-12/m16，逻辑外键）；NULL=平台直营',
    open_time  VARCHAR(5)    NULL                     COMMENT '营业开始时间 HH:MM（G-04/m18）：仅展示用，不参与 23:00 全局截单裁决（RE 红线，截单链只在服务端 orders 侧）；NULL=未设置',
    close_time VARCHAR(5)    NULL                     COMMENT '营业结束时间 HH:MM（G-04/m18）：与 open_time 同为展示口径，不产生接单/截单判定；NULL=未设置',
    daily_capacity INT       NULL                     COMMENT '日接单容量上限（G-04/m18，D-8）：NULL=不限；一期只展示不自动降级，超容仍走 accepting 手工开关',
    accepting  TINYINT       NOT NULL DEFAULT 1       COMMENT '是否接单细粒度开关（G-04/m18）：1接单/0临时满容或休市；与 status 的 open/closed 正交（status 是门店存续，本列是当日产能）',
    contact_phone VARCHAR(20) NULL                    COMMENT '门店联系电话（G-04/m18）：长度对齐 orders.phone 的 VARCHAR(20) 惯例（非 CHAR(11)，容纳区号/分机/境外号）；对外只脱敏下发（RD），GET /api/stores 不下发本列（D-12）',
    photos     VARCHAR(1000) NULL                     COMMENT '门店照片（G-04/m18，D-18）：JSON 数组串（形态对齐 dish.tags 惯例），上限 6 张，每张以 /static/images/uploads/ 开头；一期无上传端点、列先建语义先定，拍照上传与 FileStorageService 抽组件同批留 G-05',
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自提点门店表';

-- 种子 4 家（F-90 脱敏：原为真实门店名与门牌级地址、且法定代表人真名同时写在前端执照页，2026-10-20 经用户批准
-- 全数换成演示值；用户限定「位置必须在容桂、不得超出」，故名/址/坐标三者一律取「容桂 + 示范」形态的虚构点）。
-- 显式 id 保证快照引用稳定：orders.store_id 已引用 1/2，**id 不许重排**。
-- owner_user_id（m18-migration.sql 同步，05 册 A2.1 演示主理人形态）：id=1 绑 user 1（主理人工作台演示店），
-- 2/3 平台直营、4 停业未入驻均为 NULL——与 m18 ⑪ 归位段同口径
INSERT INTO store (id, name, address, service, status, owner_user_id) VALUES
(1, '容桂东堤示范店(请自备袋子谢谢)', '广东省佛山市顺德区容桂示范路1号', '支持冷冻/冷藏', 'open', 1),
(2, '容桂中堤示范店', '广东省佛山市顺德区容桂示范东路12号', '冷冻冷藏', 'open', NULL),
(3, '容桂西堤示范店', '广东省佛山市顺德区容桂示范西街8号', '冷冻冷藏', 'open', NULL),
(4, '容桂南堤示范店', '广东省佛山市顺德区容桂示范南路38号', '冷冻冷藏', 'closed', NULL);

-- 坐标真源（m13-migration.sql 同步）：GCJ-02，与前端高德底图同源（门店坐标直用不转换）；
-- F-90 起这 4 个点是**演示值**，不再是高德对真实门牌的实测结果。取值方式必须写成可复算的，否则下一个人
-- 无法判断它们是否"随便填的"：把原 id=1(113.341634,22.779372) 与 id=4(113.243995,22.757834) 连成一条线
-- （斜率 0.220588 度lat/度lng），在 lng 上取 113.332 / 113.310 / 113.290 / 113.270 四个等距点反算 lat。
-- 这样四点必落在原门店带（容桂街道境内、西北—东南主轴）上，高德路径规划有路网，
-- 而又不与任何真实门牌重合。UPDATE 幂等可重跑
UPDATE store SET lng = 113.332000, lat = 22.777247 WHERE id = 1;
UPDATE store SET lng = 113.310000, lat = 22.772394 WHERE id = 2;
UPDATE store SET lng = 113.290000, lat = 22.767982 WHERE id = 3;
UPDATE store SET lng = 113.270000, lat = 22.763570 WHERE id = 4;

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
-- 券模板表（F-113 表化，m24-migration.sql 同步，DDL 逐字照契约 §1）：
-- 模板从 CouponServiceImpl.TEMPLATES 代码常量迁表（表为唯一真源，双源必漂移）；
-- limit_per_user 泛化 lifetime 语义（1=与常量时代逐字等价，0=不限）；
-- total_quota/issued_count 条件 UPDATE 原子扣减（照 user.cash_rewarded 封顶闸门范式）；
-- 发放时 threshold/amount/expire_at(=now+valid_days) 全取表行快照落 user_coupon，核销不回读模板。
-- 回填 4 行照旧 TEMPLATES 常量逐字：coupon_1(19,4)/coupon_2(30,5)/coupon_3(50,21)/newbie_gift(30,8)；
-- title 留 NULL（前端按满X减Y拼，出参原样透传）；存量库 issued_count=0 不回填历史计数是刻意口径
--（total_quota=NULL 下不参与判定），见 m24 头注。
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

-- 回填（照 m24-migration.sql 逐字；WHERE NOT EXISTS 幂等，重放不翻倍）
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
-- threshold（m8-migration.sql 同步，M8 满额赠）：当日实付满此金额可领，免费单不计入；
-- 用户可自选的候选商品见 free_goods_pool（池内第一行 id 升序=展示默认免费商品）
-- ----------------------------
DROP TABLE IF EXISTS free_activity;
CREATE TABLE free_activity (
    id            BIGINT      NOT NULL AUTO_INCREMENT       COMMENT '活动ID（单活动模型，种子固定 1）',
    dish_id       BIGINT      NOT NULL                      COMMENT '今日免费商品ID（须 on_sale=1；种子指向非秒杀商品，免费领取销量口径见 MockPayService）',
    daily_quota   INT         NOT NULL DEFAULT 100          COMMENT '每日限量份数（>0 才可领，置 0 等效暂停）',
    daily_date    DATE        NULL                          COMMENT '计数器所属日期：与当日一致才累计，NULL/过去日期在首次领取时原子重置',
    daily_claimed INT         NOT NULL DEFAULT 0            COMMENT '当日已领份数（原子条件 UPDATE 自增）',
    threshold     DECIMAL(10,2) NOT NULL DEFAULT 50.00      COMMENT '满额门槛（当日实付满此金额可领）',
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
-- 免费领商品候选池表（M8，m8-migration.sql 同步）：uk_activity_dish(activity_id, dish_id) 数据库级去重
-- （同一活动同一商品只入池一次，INSERT IGNORE 可重跑）；池本身不存商品快照，展示/领取时联 dish 取
-- 在售数据，下架商品不进下发列表（claim 侧另有 on_sale 校验）；DDL 照 m8-migration.sql:26-32 逐字，
-- 种子见文件末尾（须在 dish 种子之后，dish_id 引用 dish 表实际行）
-- ----------------------------
CREATE TABLE IF NOT EXISTS free_goods_pool (
    id          BIGINT NOT NULL AUTO_INCREMENT COMMENT '候选池记录ID',
    activity_id BIGINT NOT NULL                      COMMENT '活动ID（free_activity.id，单活动模型种子固定 1）',
    dish_id     BIGINT NOT NULL                      COMMENT '候选商品ID（dish.id，须 on_sale=1，领取时校验）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_dish (activity_id, dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='免费领商品候选池表';

-- ----------------------------
-- 搜索别名表（F-03，m10-migration.sql 同步）：alias → dish_id 人工维护语义别名（如 洋芋/马铃薯→高山土豆），
-- uk_alias 一别名仅指向一商品；搜索时别名命中的 dish_id 集合以 IN 并入 MATCH 结果（DishMapper.java:64
-- 按 alias 精确查 dish_id）；DDL 照 m10-migration.sql:36-43 逐字，种子见文件末尾（须在 dish 种子之后）
-- ----------------------------
CREATE TABLE IF NOT EXISTS dish_alias (
    id      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    alias   VARCHAR(50) NOT NULL                COMMENT '搜索别名',
    dish_id BIGINT      NOT NULL                COMMENT '命中商品',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alias (alias),
    KEY idx_dish (dish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='搜索别名（F-03）';

-- ----------------------------
-- 商家主体表（F-12/G-01，m15-migration.sql:17-29 同步）：商家化地基的归属锚点，零物理外键、
-- 归属全靠逻辑外键（对齐全库惯例）；平台自营主体固定 id=1（dish/order_item 的归属列 DEFAULT 1 所指主体
-- 必须先存在，种子见文件末尾）；status 生命周期出口执行清单归 G-06，一期仅口径声明
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商家ID（平台自营固定 id=1）',
  name          VARCHAR(100) NOT NULL                COMMENT '商家名称',
  contact_phone VARCHAR(20)  NOT NULL                COMMENT '联系手机号（登录号快照；权威绑定在 user.merchant_id，此列仅展示/检索，不设 UNIQUE）',
  contact_name  VARCHAR(50)  NULL                    COMMENT '联系人',
  status        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active正常/suspended停业整顿/terminated清退（执行口径见 06 §4.2「商家生命周期出口执行清单」：on_sale 下架+seckill 清除等六步，非仅 on_sale 熔断）',
  credit_status VARCHAR(20)  NULL                    COMMENT '信用分（三期预留，对齐 review.audit_status 三态范式 init.sql:161；一期 NULL）',
  remark        VARCHAR(255) NULL                    COMMENT '备注',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家主体表（零物理外键，归属全靠逻辑外键——对齐全库惯例）';

-- ----------------------------
-- 商家资质/入驻信息表（F-12/G-01，m15-migration.sql:34-44 同步）：与 merchant 1:1（主键即关系，
-- 便于二期多证照扩展）；audit_status 三态复用 review.audit_status 范式；一期平台代开通=直接 approved
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_profile (
  merchant_id       BIGINT       NOT NULL COMMENT '商家ID（逻辑外键 merchant.id，1:1 主键即关系）',
  business_license  VARCHAR(255) NULL     COMMENT '营业执照图 URL（依赖 POST /api/merchant/uploads；VARCHAR(255) 对齐 dish.image 惯例 init.sql:37）',
  legal_person      VARCHAR(50)  NULL     COMMENT '法人姓名',
  address           VARCHAR(255) NULL     COMMENT '经营地址',
  settlement_account VARCHAR(64) NULL     COMMENT '结算账户（三期结算启用；列位预留代价为零）',
  audit_status      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected（复用评价审核三态范式；一期平台代开通=直接 approved）',
  audit_remark      VARCHAR(255) NULL     COMMENT '驳回理由',
  audited_at        DATETIME     NULL     COMMENT '审核时间',
  licenses          TEXT         NULL     COMMENT '同 merchant_apply.licenses；审核通过时复制',
  PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家资质/入驻信息';

-- ----------------------------
-- 管理/商家操作审计表（F-12/G-01 M-04 补课，m15-migration.sql:89-101 同步）：现状日志不记操作者
-- （AdminServiceImpl.java:93），本表 actor 前缀区分 admin:<key摘要> / merchant:<userId>；
-- G-02 商家端写端点随建随写本表（F-13 R6'）。
-- 索引口径与 m20-migration.sql 同步（F-49，两处同步改：新库走本 CREATE 体全量重建，存量库走 m20 幂等加索引）：
--   下面 idx_action (action) 与 idx_actor_action (actor, action) 这两枚是为运营端审计页（AuditLogs.vue，F-45）建的——
--   四个精确等值筛选框 + 四枚「常用筛选」预设（AuditLogs.vue 的 QUICK_FILTERS：三枚只按 action 筛、
--   一枚按 actor+action 筛），且每次查询跑两条 SQL（一页 + 同一 WHERE 的独立 COUNT，
--   AdminAuditLogMapper#AUDIT_FILTER_WHERE 与它的 #selectAuditLogsPage / #countAuditLogs 两条 @Select），
--   COUNT 那一半在没有 action/actor 索引时必然全表。
--   为什么恰好是这两条（主会话在 scratch 库 scratch_f49 实测：同一份建表体 + 50,000 行合成数据，action 六值均匀分布
--   → dish.update 命中 8,333 行）：加索引前所有带筛选的形态都是 type=index key=PRIMARY extra=Using where;
--   Backward index scan（倒序全表扫）；加索引后 action 单筛 → type=ref key=idx_action rows=15410；同一 WHERE 的
--   COUNT(*) 跑 20 次，有索引 Handler_read_next=166,660（=20×8,333，只扫该 action 的索引区间）vs 无索引
--   Handler_read_rnd_next=1,000,020（=20×50,000，全表）→ 扫描行数 6 倍差；深翻页 LIMIT 40000,20 跑 20 次
--   544ms vs 无索引 857ms。短路形态不掉索引：Mapper 里 (#{x} IS NULL OR x = #{x}) 那种静态 WHERE 与纯等值写法
--   执行计划逐字相同，故本批不改任何 SQL/Java。
--   取复合 idx_actor_action(actor, action) 而不是单列 idx_actor：actor+action 双筛下本方案 key=idx_actor_action
--   rows=1 且 Extra 里没有 Using where，单列方案 key=idx_actor rows=24564 extra=Using where（要回表再滤）；
--   actor 单筛由本复合索引的最左前缀覆盖，故不另建第三枚 idx_actor——本表只增不删（每次后台写动作、F-44 起
--   每一条支付回调拒收都落一行，actor='system:wx-notify'），多一枚索引就是白付一份写入成本。
--   注：本批只同步 init.sql 与 m20-migration.sql 两处，**m15-migration.sql:89-101 那份 CREATE 体的 KEY 列表未动**，
--   因此不再与本处逐字相同；两条建库路径仍收敛到同一索引集合——先 init.sql 再重放 m3~m20 时 m15 的
--   CREATE TABLE IF NOT EXISTS 整句跳过（表已存在），只走 m3~m20 时由 m20 补上这两枚索引，殊途同归。
-- ----------------------------
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  actor       VARCHAR(64)  NOT NULL COMMENT '操作主体：admin:<key摘要> / merchant:<userId>（现状日志不记操作者，AdminServiceImpl.java:93）',
  action      VARCHAR(50)  NOT NULL COMMENT 'dish.update/order.status/review.audit/merchant.create/deposit.deduct…',
  target_type VARCHAR(30)  NOT NULL COMMENT 'dish/order/review/merchant/deposit',
  target_id   VARCHAR(64)  NOT NULL COMMENT '目标ID（订单号 VARCHAR(50) 口径对齐 init.sql:62）',
  detail      VARCHAR(500) NULL     COMMENT '旧值→新值 JSON',
  ip          VARCHAR(45)  NULL     COMMENT '操作来源 IP（IPv6 容量）',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_target (target_type, target_id),
  KEY idx_created (created_at),
  KEY idx_action (action),
  KEY idx_actor_action (actor, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理/商家操作审计';

-- ----------------------------
-- 商家-自提点供货关系表（F-12/G-01，m16-migration.sql:74-83 同步）：uk_merchant_store 数据库级去重
-- （对齐 free_goods_pool uk_activity_dish 惯例）；status 软停供保历史轨迹；一期数据维护=平台，商家端只读
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_store (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  merchant_id BIGINT      NOT NULL COMMENT '商家ID（逻辑外键 merchant.id）',
  store_id    BIGINT      NOT NULL COMMENT '自提点ID（逻辑外键 store.id）',
  status      VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active供货中/offline已停供（软停供保历史轨迹）',
  created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '建立时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_store (merchant_id, store_id),
  KEY idx_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家-自提点供货关系（一期数据维护=平台，商家端只读）';

-- ----------------------------
-- 现场动作审计表（F-18/G-04，m17-migration.sql:85-99 同步）：D-5 两表边界
-- 本表只收商家/主理人/申请人现场动作（核销/到货确认/门店信息变更/缺货登记/入驻申请提交），operator_user_id 即操作者 userId；
-- 平台侧动作（store_apply 审核、Admin 核销）一律走 admin_audit_log（m15:89-101，actor=admin:<摘要8>）——
-- 该列是 BIGINT，装不下 'admin:<key摘要>' 字符串，故两表不合并（05 册 C3 注已收窄生效）
-- ----------------------------
CREATE TABLE IF NOT EXISTS ops_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    operator_user_id BIGINT       NOT NULL COMMENT '操作者 userId（语义收窄：商家/主理人/申请人现场动作；平台操作员不写本表）',
    action           VARCHAR(50)  NOT NULL COMMENT '现场动作白名单（BE-13，OpsLogWriter 收口）：verify_order/register_shortage/confirm_arrival/update_store/submit_store_apply',
    target_id        VARCHAR(64)  NOT NULL COMMENT '目标对象ID',
    detail           VARCHAR(500) NULL     COMMENT '变更摘要',
    created_at       DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_target (action, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营操作审计（D-5 边界：本表只收商家/主理人/申请人现场动作——核销/到货确认/门店信息变更/缺货登记/入驻申请提交；平台侧动作一律走 admin_audit_log）';

-- ----------------------------
-- 主理人入驻申请表（F-18.10/G-04 Step 4a，m18-migration.sql 同步，列口径逐字照该文件 ⑦）：
-- D-20 申请侧端点挂 /api/store-applies/（登录即可、无归属要求）——申请时名下无店，挂 /api/partner/ 会因归属集合为空 403 自锁入口；
--      审核侧挂 Admin，有店后的维护才挂 /api/partner/（这是对 05 册 A3.2 的必要修正）；
-- D-21 一用户一期最多 1 条 pending = 应用层校验 + BE-3（F-18.20 C 行）库侧闸门双保险：生成列 pending_guard（status='pending' 时为 'p'，
--      其余 NULL）+ 唯一键 uk_user_pending(user_id, pending_guard)——NULL 不参与唯一性，rejected/approved 历史行仍放行
--      （「rejected → 再提交」不受影响），但同用户并发第二条 pending 直接 1062，由应用层转 400 文案；
-- proof_photos 一期无上传端点（D-18，列先建语义先定）；审核动作写 admin_audit_log（D-5 两表边界，不落 ops_log）
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
    pending_guard    VARCHAR(1)    GENERATED ALWAYS AS (IF(status = 'pending', 'p', NULL)) STORED COMMENT '并发防护生成列（BE-3/G-04/m18）：status=pending 时为 p、其余为 NULL；NULL 不参与唯一性，配合 uk_user_pending 封死同用户并发双 pending',
    audit_remark     VARCHAR(255)  NULL COMMENT '审核意见（驳回原因供申请人展示）',
    audited_at       DATETIME      NULL COMMENT '审核时间；NULL=未审',
    created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    UNIQUE KEY uk_user_pending (user_id, pending_guard)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主理人入驻申请（D-20 申请侧 /api/store-applies/ 登录即可；D-21 同用户最多 1 条 pending=应用层校验 + uk_user_pending 库侧闸门双保险；审核动作写 admin_audit_log，见 D-5 两表边界）';

-- ----------------------------
-- 商家入驻申请表（F-106 片 A，m21-migration.sql 同步，结构与迁移文件逐字一致）：
-- 申请侧 /api/merchant-applies 登录即可（申请时名下无商家，入口不能挂 /api/merchant/，否则角色闸门自锁）；
-- 审核侧 /api/admin/merchant-applies/{id}/audit——approve 同事务五步：查申请/幂等 → INSERT merchant
-- （contact_phone 取申请人 user.phone，不取表单）→ INSERT merchant_profile(approved) → 条件 UPDATE user
-- （WHERE merchant_id IS NULL，影响行数≠1 回滚）→ 置申请态 + admin_audit_log；
-- 并发范式照 store_apply（m18 ⑦b）：生成列 pending_guard + uk_user_pending(user_id, pending_guard)，
-- NULL 不参与唯一性，封死同用户并发双 pending，rejected/approved 历史行仍放行（驳回后可再次申请）。
-- ----------------------------
CREATE TABLE IF NOT EXISTS merchant_apply (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  user_id            BIGINT       NOT NULL COMMENT '申请人 user.id（逻辑外键 user.id；申请时名下无商家，故入口不能挂 /api/merchant/）',
  merchant_name      VARCHAR(100) NOT NULL COMMENT '拟用商家名称',
  contact_name       VARCHAR(50)  NULL COMMENT '联系人姓名',
  business_license   VARCHAR(255) NULL COMMENT '营业执照图 URL（/static/images/uploads/ 前缀，单张）',
  legal_person       VARCHAR(50)  NULL COMMENT '法人姓名',
  address            VARCHAR(255) NULL COMMENT '经营地址',
  settlement_account VARCHAR(64)  NULL COMMENT '结算账户（三期结算启用，列位预留）',
  status             VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending待审核/approved通过/rejected驳回',
  pending_guard      VARCHAR(1) GENERATED ALWAYS AS (IF(status='pending','p',NULL)) STORED COMMENT '并发防护生成列',
  audit_remark       VARCHAR(255) NULL COMMENT '审核意见（驳回原因供申请人展示）',
  audited_at         DATETIME     NULL COMMENT '审核时间；NULL=未审',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  licenses           TEXT         NULL COMMENT '证照列表 JSON：[{"type":"business_license","url":"/static/images/uploads/x.jpg"}]，最多 6 条',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_pending (user_id, pending_guard),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商家入驻申请';

-- ----------------------------
-- 到货批次表（F-18.10/G-04 Step 4a，m18-migration.sql 同步，列口径逐字照该文件 ⑧）：
-- D-19 一期只做「读 + 确认」（主理人查询/确认端点=Step 4b），故本文件同步带一条演示种子（见文件末尾 M18 种子块）；
--    F-60 订正：原注写「批次生成器属运营端 G-05」是双重指错——G-05 实为「商家端营销参与态+数据看板」（07 册），
--    运营端是 G-06，两期都没有批次生成页；全系统对 arrival_batch 至今零写入端点，批次只能由平台直接写库。
--    另：种子的 batch_date 是写死日期，装库次日起即过期，不可当作长期验收依据（详见 m18 同处注释）。
-- UNIQUE KEY uk_store_date (store_id, batch_date) 双重作用：一期一店一日一批次 + 让演示种子可 INSERT IGNORE 幂等
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
-- 到货批次明细表（F-18.10/G-04 Step 4a，m18-migration.sql 同步，列口径逐字照该文件 ⑨）：
-- 本表无唯一键（同菜品可能多批次多行），故演示种子必须用 WHERE NOT EXISTS 守卫，不能裸 INSERT——
-- 该写法与 m18 一致，保证新库重建与存量库增量两路都只落 1 行（RC 可重复执行且零破坏）
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
-- 运营账号表（F-20/G-06 Step 1，m19-migration.sql ① 同步，列口径逐字照该文件与 F-20.2 D-4）：
-- 独立于 user 表的运营域账号——零外键、无手机号、无邮箱、不给 user 加 role（D-4 / 07 册 G-06 范围⑧：两域物理隔离）；
-- admin_user.id 与 user.id 都是 BIGINT 自增、小 id 必然并存，故任何鉴权校验绝不允许只看 JWT subject（D-6，双轨 typ=admin 判定在 Step 2）；
-- password_hash 复用 common/PasswordUtil.java 的 PBKDF2WithHmacSHA256 现格式（约 69 字符，列宽同 user.password_hash m5），不引入新依赖；
-- 🔴 本文件**不放任何 admin_user 种子**（连 dev 默认账号也不建，明文口令入仓=RD 红线）：账号由主会话用一次性 SQL 造，口令不入仓不入日志；
-- 登录动作写 admin_audit_log（m15:89-101，actor=admin_user:<id>，D-5/D-8 两表边界——ops_log 只收商家/主理人/申请人现场动作，不许为「顺手统一」放宽）
-- ----------------------------
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '运营账号ID（BIGINT 自增）；与 user.id 同为自增、小 id 必然并存（D-6：任何鉴权校验绝不允许只看 JWT subject，必须靠 typ=admin claim + 本表存在性 + status=active 双轨判定）',
    username      VARCHAR(50)  NOT NULL COMMENT '运营登录名（唯一键 uk_admin_username）：只用于登录定位账号，非 PII；与 user.phone 的 C 端账号域物理无交叉（D-4）',
    password_hash VARCHAR(128) NOT NULL COMMENT '口令哈希：common/PasswordUtil.java 的 PBKDF2WithHmacSHA256 现格式 base64(salt):base64(hash)（约 69 字符，列宽对齐 user.password_hash m5）；**恒不明文入库、不明文出参**（RD），全仓唯一允许读本列的是 AdminUserMapper.selectByUsername 那一条 @Select',
    display_name  VARCHAR(50)  NULL COMMENT '展示名（管理界面顶部与审计页显示用）；NULL=未设置，前端回退显示 username；不含手机号/邮箱等 PII（D-4/RD）',
    status        VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT '账号状态：active可用/disabled停用（两态，无第三态）；D-2 双轨校验第②条要求 status=active 才放行运营 JWT，disabled=立即失效（可停用而不删行，留审计轨迹）',
    last_login_at DATETIME     NULL COMMENT '最近登录成功时间（D-5：登录成功链路由 AdminUserMapper.touchLastLogin 写入）；NULL=从未成功登录过；本列是 admin_user 里除 username 外唯一对外可见的时间字段，不再往里塞任何 PII（RD）',
    create_time   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '账号创建时间：DB DEFAULT 落值，应用侧不填（照本仓 created_at/create_time 无 fill 注解的既有纪律，MetaObjectHandler 只填带 @TableField(fill) 的字段）',
    update_time   DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '行更新时间：DB ON UPDATE 自动维护，改密/停用等动作无需应用侧显式赋值',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运营账号（F-20/G-06 Step 1，D-4）：独立于 user 表的运营域账号，零外键零手机号零邮箱；口令仅 PBKDF2 哈希（PasswordUtil 现格式）；**无任何默认种子**，账号由主会话一次性 SQL 造、口令不入仓（RD）；登录动作写 admin_audit_log（D-5/D-8 两表边界，不写 ops_log）';

-- ----------------------------
-- 邀请新人关系表（F-01，m9-migration.sql 同步）：uk_invitee 是"一人仅可被邀请一次"的权威约束
-- （invitee_id 显式 NOT NULL：MySQL UNIQUE 对 NULL 不去重，防刷约束不可空置，对齐 free_claim 惯例）；
-- status 0→1 由新人首单支付成功后原子置位（影响行数=1 才继续，防并发重复处理）；
-- invitee_phone 为脱敏展示快照（138****1235），表内无原手机号，无手机号枚举面（F-01.0 攻击面裁定）；
-- 券模板 newbie_gift[30,8] 为代码常量（CouponServiceImpl.TEMPLATES），无库表种子；
-- invite_reward 已随 F-05 券轨下线停发（存量已发券仍可核销至过期）
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

-- ----------------------------
-- 字典分组表（F-108 字典本地化，m23-migration.sql ① 同步，DDL 逐字照契约 §0.2）：
-- 上游字典服务降级为「同步源」——本地 dict_item 有词条时 DictServiceImpl#getGroup 不再调上游；
-- enabled=1 启用 / 0 停用（停用=该组按空返回，不回退上游）；upstream_category_id 仅同步用（值照 GROUP_ID）。
-- 词条种子只在 m23-migration.sql 里（4 组 / 23 条 INSERT IGNORE），本文件不并入，避免两份真源。
-- ----------------------------
CREATE TABLE IF NOT EXISTS dict_group (
  code VARCHAR(32) NOT NULL COMMENT '分组代码（与 DictServiceImpl.GROUP_ID 的键一致）',
  name VARCHAR(50) NOT NULL COMMENT '中文名',
  upstream_category_id INT NULL COMMENT '上游 categoryId（仅同步用；本地有数据时不走上游）',
  sort INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用=该组按空返回，不回退上游）',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典分组';

-- ----------------------------
-- 字典词条表（F-108 字典本地化，m23-migration.sql ② 同步，DDL 逐字照契约 §0.2）：
-- uk_group_word 是回填 INSERT IGNORE 的幂等依据；idx_group_sort 支撑读路径的 sort_order DESC 排序。
-- ----------------------------
CREATE TABLE IF NOT EXISTS dict_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  group_code VARCHAR(32) NOT NULL,
  word VARCHAR(100) NOT NULL COMMENT '显示名',
  definition VARCHAR(500) NULL COMMENT '取值/定义（券组是 JSON 字符串）',
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_group_word (group_code, word),
  KEY idx_group_sort (group_code, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典词条';

-- ----------------------------
-- 秒杀场次组回填（F-115 契约化，m25-migration.sql 同步逐字一致；NOT EXISTS 幂等，重放不翻倍）：
-- SeckillController 场次改读此组（契约 §2.1），definition 与常量时代逐字一致保证出参零变化；
-- upstream_category_id=NULL = 纯本地组，GROUP_ID 无此键，读路径不回退上游、同步端点恒 400。
-- ----------------------------
INSERT INTO dict_group (code, name, upstream_category_id, sort, enabled)
SELECT 'seckill_session', '秒杀场次', NULL, 50, 1
WHERE NOT EXISTS (SELECT 1 FROM dict_group WHERE code = 'seckill_session');

INSERT INTO dict_item (group_code, word, definition, sort_order)
SELECT 'seckill_session', 'start', '00:00', 20
WHERE NOT EXISTS (SELECT 1 FROM dict_item WHERE group_code = 'seckill_session' AND word = 'start');

INSERT INTO dict_item (group_code, word, definition, sort_order)
SELECT 'seckill_session', 'end', '23:00', 10
WHERE NOT EXISTS (SELECT 1 FROM dict_item WHERE group_code = 'seckill_session' AND word = 'end');

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
-- threshold=50.00（m8-migration.sql 同步）：当日实付满 50.00 可领，换门槛直接 UPDATE 本行，代码不写死金额
-- ================================================
INSERT INTO free_activity (id, dish_id, daily_quota, daily_claimed, threshold, status) VALUES
(1, 1, 100, 0, 50.00, 'online');

-- ================================================
-- M8 免费商品候选池种子（与 sql/m8-migration.sql:38-44 同一套，需在 dish 种子之后执行；
-- 活动 id=1 候选池 6 个在售 dish（优先非秒杀、含蔬菜/水果类），INSERT IGNORE 可重跑；
-- 首行 dish_id=1 精品五花肉 = 默认免费商品（与 free_activity.dish_id=1 同品，页面大卡形象延续）
-- ================================================
INSERT IGNORE INTO free_goods_pool (activity_id, dish_id) VALUES
(1, 1),
(1, 10),
(1, 13),
(1, 16),
(1, 20),
(1, 22);

-- ================================================
-- M10 搜索别名种子（与 sql/m10-migration.sql:49-57 同一套，需在 dish 种子之后执行；
-- 5 组 8 条语义别名，INSERT IGNORE 可重跑；别名词已对 name/description/tags LIKE 全表核对：
-- 除「番茄」外均为三列外纯增量召回词（见 m10 文件头说明）
-- ================================================
INSERT IGNORE INTO dish_alias (alias, dish_id) VALUES
('洋芋',   16),  -- → 高山土豆：云贵高原俗称，库内三列均无此词
('马铃薯', 16),  -- → 高山土豆：学名，库内三列均无此词
('番茄',   13),  -- → 本地西红柿：文档 DoD 西红柿↔番茄场景（description 已含「番茄味」，别名兜底保证精确命中）
('柴鸡蛋', 9),   -- → 散养土鸡蛋：柴鸡蛋俗称，库内三列均无此词
('笨鸡蛋', 9),   -- → 散养土鸡蛋：东北俗称，库内三列均无此词
('泡面',   23),  -- → 老坛酸菜牛肉面五连包：泡面俗称（description 仅有「方便面」），库内三列均无「泡面」
('毛蟹',   6),   -- → 鲜活大闸蟹：中华绒螯蟹俗称，库内三列均无此词
('河蟹',   6);   -- → 鲜活大闸蟹：中华绒螯蟹俗称，库内三列均无此词

-- ================================================
-- M15 商家主体种子（与 sql/m15-migration.sql:106-107 同一套，INSERT IGNORE 可重跑）：
-- 平台自营主体 id=1 + 资质行 audit_status='approved'（一期平台代开通口径）；
-- dish.merchant_id / order_item 归属的 DEFAULT 1 与 NULL 兜底 1 均指向本行，故须在四张商家表建好后执行
-- 注：m16 的两条 `UPDATE order_item … 回填`（JOIN dish 落快照 + 孤儿置 1）仅存量库需要——新库无历史明细，
-- 回填是空操作，故本文件不并入；存量库增量仍走 sql/m16-migration.sql（R8' 并库不删迁移）
-- ================================================
INSERT IGNORE INTO merchant (id, name, contact_phone, status) VALUES (1, '平台自营', '00000000000', 'active');
INSERT IGNORE INTO merchant_profile (merchant_id, audit_status, audited_at) VALUES (1, 'approved', NOW());

-- ================================================
-- M18 到货批次演示种子（与 sql/m18-migration.sql ⑩ 同一套，幂等可重跑，F-18.10/D-19）：
-- store 1 / 明日 / planned 的批次 + 一条明细（dish 1 预计 5 份）——缺这两行，核销台的「当日未确认」警示条
-- 在全量重建的演示库里永远演示不出来（Step 4b 主理人查询/确认端点与 Step 5 验收现场都依赖它）；
-- 批次行靠 uk_store_date 撞唯一键被 IGNORE 吞掉而幂等；arrival_item 无唯一键，故用 NOT EXISTS 守卫（同 m18 形态，二次执行不翻倍）
-- 注：m18 ⑪ 的演示主理人归属归位 UPDATE 不并入本文件——新库无待归位的历史绑定，store 种子已直接带 owner_user_id（见自提点门店表段）
-- ================================================
INSERT IGNORE INTO arrival_batch (store_id, batch_date, status) VALUES (1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 'planned');

INSERT IGNORE INTO arrival_item (batch_id, dish_id, expected_qty)
SELECT b.id, 1, 5
  FROM arrival_batch b
 WHERE b.store_id = 1
   AND b.batch_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY)
   AND NOT EXISTS (
        SELECT 1 FROM arrival_item ai WHERE ai.batch_id = b.id AND ai.dish_id = 1
   );
