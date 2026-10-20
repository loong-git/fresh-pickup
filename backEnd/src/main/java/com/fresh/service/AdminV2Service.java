package com.fresh.service;

import java.util.Map;

/**
 * 运营端 v2 只读服务（F-20.9 #2/#3 大盘四指标 + 审计分页，F-20.18 再加四条运营列表：
 * orders / dishes / reviews / store-applies），<b>六个方法全部只读</b>。
 *
 * <p>闸门不在本类：{@code /api/admin/v2/**} 只认运营 JWT（D-2「防止新代码更弱」），
 * 由 {@code AdminV2Controller} 调 {@code AdminController#requireAdminJwtOnly} 收口，
 * 静态密钥在这六个端点上是一律 401。本类因此<b>没有任何「调用者是谁」的入参</b>——
 * 只读动作不写审计（RF 只约束写动作），也就不需要 actor。
 *
 * <p>旧 5 个写端点的业务口径仍在 {@link AdminService}，本期不在 v2 重复造写接口（D-7）；
 * 四个 P0 页要的<b>列表数据源</b>在 F-20.18 那四条，二者不重叠。
 */
public interface AdminV2Service {

    /**
     * 只读大盘（{@code GET /api/admin/v2/overview}）：单量 / 总金额 / 待自提单数 / 已收款金额四指标，
     * 口径逐字照 {@code docs/日常运营SQL手册.md:111-115} 的「今日经营概况」那条 SELECT（订单口径，
     * 与 G-05 商家看板的券前明细口径分列、互不换算，D-7）；
     * F-50 再加两键当日回调拒收读数（见下面 @return 的第五、六键）。
     *
     * @param date {@code YYYY-MM-DD}；null/空 = 今日（今日在 <b>SQL 侧</b>用 CURDATE() 解析，不在 Java 取时钟）；
     *             形状不符或形状符但不是真日期（2026-02-30）→ 400
     * @return 手工白名单 Map 恰六键
     *         {@code {orderCount, totalAmount, pendingPickupCount, paidAmount, notifyRejectCount, notifyRejectLastAt}}；
     *         前四键不变：金额为两位小数字符串，空数据日四个 0 值（{@code 0}/{@code "0.00"}），绝不出 null。
     *         <b>后两键（F-50，D-50-1/D-50-3）</b>：当日窗口（与前四键同一个 {@code IFNULL(#{day}, CURDATE())}
     *         半开区间）内 {@code admin_audit_log} 里 {@code action = pay.notify_reject} 的行数，
     *         与该行里最后一次拒收的 {@code HH:mm} 串；
     *         <b>当日没有拒收时 {@code notifyRejectLastAt} 是显式 null、键必须在</b>（F-42b 信封纪律：
     *         缺键与显式 null 对客户端是两种语义），{@code notifyRejectCount} 恒为数字 0 而不是 null。
     */
    Map<String, Object> overview(String date);

    /**
     * 审计日志分页（{@code GET /api/admin/v2/audit-logs}）：{@code admin_audit_log}，{@code ORDER BY id DESC}。
     * 手写「页 + 独立 COUNT」两条 SQL（RB：本工程没装 MP 分页插件），两条 WHERE 逐字相同（F-20.16 D-10）。
     *
     * <p>F-20.16（收 G-06 DoD ④）：增四个<b>可选、精确等值</b>筛选 {@code actor}/{@code action}/
     * {@code targetType}/{@code targetId}（全可 null；null/未传 = 该条件不生效）。等值匹配<b>不区分大小写</b>
     * （来自列 collation {@code utf8mb4_unicode_ci}，非本层归一）。非 null 的取值先过形状闸门
     * {@code ^[A-Za-z0-9_.:-]{1,64}$}，不合形状 → 400（复用既有那一句文案，不新造）；形状合但库里没这个值 →
     * 正常回空，不是 400（F-20.16 D-11）。
     *
     * @param pageNum  缺省 1；<b>下限 1</b>，越界或脏值 → 400（姿势同 G-05 的 days「越界直接 400，不夹到边界」）
     * @param pageSize 缺省 20；值域 [1,100]，越界 → 400
     * @param actor     操作者精确等值筛选，可 null（例：{@code admin_user:2}）
     * @param action    动作精确等值筛选，可 null（例：{@code dish.update}）
     * @param targetType 目标类型精确等值筛选，可 null（例：{@code dish}）
     * @param targetId  目标 ID 精确等值筛选，可 null（字符串口径，订单号也走这一列）
     * @return {@code {list:[{id, actor, action, targetType, targetId, detail, createdAt}], total, pageNum, pageSize, filters}}；
     *         {@code total} 是<b>同一筛选条件下</b>的独立 COUNT 结果（D-10）；
     *         {@code filters} 是生效筛选值的回显 {@code {actor, action, targetType, targetId}}，<b>未传的键回 null</b>
     *         （不是空串、不是缺键，D-12）；既有四键 {@code list/total/pageNum/pageSize} 一字不动。
     *         <b>不含 ip</b>（RD 最小暴露）；空表 / 无命中 → {@code list:[]} 且 {@code total:0}，不是 500
     */
    Map<String, Object> auditLogs(Integer pageNum, Integer pageSize,
                                  String actor, String action, String targetType, String targetId);

    // ------------------------------------------------------------------
    // F-20.18 Step 5b-1｜四条只读列表（收 F-20.17 的 5b-1）。四个方法的共同纪律（逐条与 auditLogs 同构，
    // 这里是索引，细节见各方法与 AdminV2Controller / 各 mapper 的注释）：
    // · 闸门不在本类（D-14），本类因此没有任何「调用者是谁」的入参；只读动作不写审计（RF 只约束写动作）。
    // · 分页 D-16：pageNum>=1、pageSize∈[1,100] 缺省 20，<b>越界与脏值一律 400 不夹取</b>；
    //   offset>=total 短路回空 list（用的是<b>筛选后</b>的 total）；回显生效的 pageNum/pageSize。
    // · 取数 D-16/RB：页 + 独立 COUNT 两条手写 SQL，两条 WHERE 逐字相同（同一个编译期常量串）。
    // · 筛选 D-17：一律精确等值 + 白名单枚举，本批<b>不开 LIKE、不做关键字搜索</b>；白名单外 → 400。
    //   形状闸门两枚各管各的（F-20.21 BE-G6-1）：auditLogs 四枚 = ASCII 字符类；dishes.category = 「1~64 字符且
    //   不含控制字符/零宽」（只挡乱码、不当数据字典，库里确有中文分类值）。
    //   枚举止筛（orders.status / reviews.auditStatus / store-applies.status）过白名单<b>前</b>先按 Locale.ROOT
    //   归一小写、回显归一后的值；dishes.onSale 是 Integer，归一对它是 no-op，绑定类型不许动（F-20.21 BE-G6-2）。
    // · 出参 D-15：逐键重拼白名单 Map，绝不吐实体、绝不吐 SQL 行的原始 Map；禁下发键在 SQL 层就「不查询」。
    // · 形态 D-18：金额服务端定形两位小数字符串（moneyText）、时间 DATE_FORMAT 串、布尔列出 0/1 原值。
    // · 回显 D-19：第五条键 filters，未传的键回 null（不是空串、不是缺键）。
    // · 空表 / 无命中：{list:[], total:0}，不是 500（review 与 store_apply 现库就是 0 行，这是实况）。
    // ------------------------------------------------------------------

    /**
     * 运营端订单列表（{@code GET /api/admin/v2/orders}），订单管理页的数据源。
     *
     * @param pageNum  缺省 1；&lt;1 或脏值 → 400（不夹到边界）
     * @param pageSize 缺省 20；值域 [1,100]，越界 → 400
     * @param status   等值筛选，白名单 {@code pending_pickup}/{@code completed}/{@code cancelled}；
     *                 null/未传 = 不限；白名单外 → 400（D-17）。
     *                 <b>白名单入口不区分大小写</b>（F-20.21 BE-G6-2）：{@code PENDING_PICKUP}/{@code Pending_Pickup}
     *                 与 {@code pending_pickup} 同义——先按 {@code Locale.ROOT} 归一小写再过白名单，
     *                 {@code filters.status} 回的是<b>归一后</b>的那一枚（即实际生效值，不是用户原样输入）
     * @param storeId  自提点 ID 等值筛选，可 null（= 不限）。{@code orders.store_id} 现库 69 笔里 39 笔为 NULL，
     *                 那些行只在「不限」时出现——等值语义本来的样子，不是漏数据
     * @param date     {@code YYYY-MM-DD}，按 {@code create_time} <b>自然日</b>（与 overview 同窗口口径，D-17）；
     *                 null/空 = 不限；形状不符或形状符但不是真日期 → 400（复用 overview 那两道闸门）
     * @return {@code {list:[{id, totalPrice, address, phone, pickupDate, status, createTime, updateTime,
     *         storeId, storeName, storeAddress, payStatus, payTime}], total, pageNum, pageSize,
     *         filters:{status, storeId, date}}}；
     *         <b>{@code phone} 出的是 {@code PhoneMask.mask} 的掩码值</b>（D-15，运营面不是免脱敏通行证）；
     *         <b>禁下发</b> {@code pickup_code}/{@code user_id}/{@code client_request_id}（D-15，SQL 层连列都没查）；
     *         金额 {@code totalPrice} 是两位小数字符串，时间一律 {@code yyyy-MM-dd HH:mm:ss} 串
     */
    Map<String, Object> orders(Integer pageNum, Integer pageSize, String status, Long storeId, String date);

    /**
     * 运营端商品列表（{@code GET /api/admin/v2/dishes}），商品管理台的数据源（一期<b>只改不建</b>：
     * 平台侧没有「新建商品」端点，F-20.17 事实 3 附注）。
     *
     * @param pageNum    缺省 1；&lt;1 或脏值 → 400
     * @param pageSize   缺省 20；值域 [1,100]，越界 → 400
     * @param merchantId 归属商家 ID 等值筛选，可 null（= 不限全量）。注意这一列在<b>本面是筛选条件</b>，
     *                   不是商家侧那种归属闸门（F-20.17 事实 1：运营要全量语义，运营页绝不许拿商家端接口凑数）
     * @param onSale     在售位等值筛选，白名单 0/1（1 上架 / 0 下架）；null = 不限；其余值 → 400。
     *                   这一枚是<b>数值</b>，无大小写可归（枚举归一只针对 String 那三枚）。
     *                   现库 31 件全 {@code on_sale=1}，故 {@code onSale=0} 现在是「合法筛选 + 空结果」那一档，
     *                   回 {@code {list:[],total:0}} 而不是 400
     * @param category   分类等值筛选，可 null；过 category 自己的形状闸门（F-20.21 BE-G6-1）：
     *                   长度 1~64 且不含控制字符/零宽即合法——{@code ?category=蔬菜} 是<b>正常筛选</b>而非 400
     *                   （库里 {@code dish.id=44} 的 category 就是中文「蔬菜」，列表自己会展示它；
     *                   auditLogs 那四枚的 ASCII 闸门不适用于这一列，详见 {@code AdminV2ServiceImpl.CATEGORY_PATTERN}）。
     *                   不做值域字典校验：形状合法但库里没这个分类 → 回空集不是 400
     * @return {@code {list:[{id, name, price, stock, onSale, soldCount, category, unit, image, goodRate,
     *         seckill, seckillPrice, limitBuy, merchantId, createTime, updateTime}], total, pageNum, pageSize,
     *         filters:{merchantId, onSale, category}}}；
     *         不输出 {@code description}/{@code tags}/{@code emoji}/{@code bg_color}（RD 最小暴露，SQL 层就没选）；
     *         {@code onSale}/{@code seckill} 出 0/1 原值由页面映射文案（D-18）；
     *         {@code price}/{@code seckillPrice} 是两位小数字符串（{@code seckillPrice} 非秒杀件为 null）
     */
    Map<String, Object> dishes(Integer pageNum, Integer pageSize, Long merchantId, Integer onSale, String category);

    /**
     * 运营端评价审核队列（{@code GET /api/admin/v2/reviews}）。
     * 现库 {@code review} <b>0 行</b>（F-20.18 前置事实）——空表回 {@code {list:[],total:0}} 不是 500，
     * 门禁 fixture 由主会话造，执行者不许写库。
     *
     * @param pageNum     缺省 1；&lt;1 或脏值 → 400
     * @param pageSize    缺省 20；值域 [1,100]，越界 → 400
     * @param auditStatus <b>UI 伪值</b>白名单 {@code pending}（待人工复审）/{@code visible}（展示中）；
     *                    null = 不限。<b>SQL 侧映射钉死</b>（D-17）：{@code pending} → {@code audit_status='pending'}、
     *                    {@code visible} → {@code audit_status IS NULL}；其余值 → 400。
     *                    <b>白名单入口不区分大小写</b>（F-20.21 BE-G6-2，归一在映射之前）：{@code VISIBLE} 与
     *                    {@code visible} 同义，{@code filters.auditStatus} 回的是归一后生效的那一枚（仍是 UI 伪值）。
     *                    没有「已驳回」这一档可筛：{@code AdminServiceImpl#auditReview} 的 reject 是物理删行，
     *                    库里不存在对应行（别为不存在的东西设计筛选值）
     * @return {@code {list:[{id, dishId, rating, content, reviewer, auditStatus, createTime}], total,
     *         pageNum, pageSize, filters:{auditStatus}}}；
     *         <b>不下发 {@code user_id}</b>（D-15，SQL 层连列都没查）；
     *         行里的 {@code auditStatus} 是<b>库里原值</b>（{@code 'pending'} 或 {@code null}=展示中，D-18 出原值），
     *         而 {@code filters.auditStatus} 回的是<b>UI 伪值本身</b>（D-19，不回 SQL 映射后的形态）
     */
    Map<String, Object> reviews(Integer pageNum, Integer pageSize, String auditStatus);

    /**
     * 运营端入驻申请列表（{@code GET /api/admin/v2/store-applies}），入驻审核工作台的数据源。
     * 现库 {@code store_apply} <b>0 行</b>（同 {@link #reviews}：空表是实况，回空不回 500）。
     *
     * @param pageNum  缺省 1；&lt;1 或脏值 → 400
     * @param pageSize 缺省 20；值域 [1,100]，越界 → 400
     * @param status   等值筛选，三态白名单 {@code pending}/{@code approved}/{@code rejected}
     *                 （DEFAULT 'pending'，见 init.sql 的 store_apply）；null = 不限；白名单外 → 400。
     *                 <b>白名单入口不区分大小写</b>（F-20.21 BE-G6-2）：{@code Pending} 归一为 {@code pending} 后进白名单，
     *                 {@code filters.status} 回显归一后的生效值
     * @return {@code {list:[{id, proposedName, proposedAddress, lng, lat, service, contactName, contactPhone,
     *         status, auditRemark, auditedAt, createdAt}], total, pageNum, pageSize, filters:{status}}}；
     *         <b>{@code contactPhone} 出的是 {@code PhoneMask.mask} 的掩码值</b>（D-15；该列可空，
     *         null 原样回 null）；<b>不下发 {@code proof_photos}</b>（§9 悬项未拍板）、
     *         <b>不下发 {@code user_id}</b>、<b>不选生成列 {@code pending_guard}</b>——三者都在 SQL 层就不查
     */
    Map<String, Object> storeApplies(Integer pageNum, Integer pageSize, String status);
}
