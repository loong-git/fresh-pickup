package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 购买关系校验（M6）：统计本人订单（任意状态）中指定菜品的明细条数；
     * 归属依据 orders.user_id（order_item 无 user_id，须 JOIN），>0 即存在购买记录
     */
    @Select("SELECT COUNT(*) FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE o.user_id = #{userId} AND oi.dish_id = #{dishId}")
    int countByUserIdAndDishId(@Param("userId") Long userId, @Param("dishId") Long dishId);

    // ------------------------------------------------------------------
    // F-17/G-03 商家端只读备货单（三条硬约束，逐条可 grep 核验；F-19/G-05 追加的 3 条看板 @Select
    // 沿用同一套约束，见文件末尾那一组）：
    // 1) 归属：下面每一条 @Select（F-17 的 8 条 + F-19 的 3 条）都带 `oi.merchant_id = #{merchantId}`，值只来自
    //    AuthInterceptor.ATTR_MERCHANT_ID（Controller @RequestAttribute）。不存在不带该条件的读路径。
    //    storeId / date / status 都只是**过滤**条件，绝不替代归属条件。
    // 2) JOIN 键 oi.order_id = o.id（两侧同为 varchar(50) 雪花字符串，逐字照上方
    //    countByUserIdAndDishId:16-18 的写法，不做 CAST——CAST 会让 idx_order_id 失效）。
    // 3) 可选过滤参数一律「拆成两条方法」而非 `(#{x} IS NULL OR ...)`：Long/LocalDate 传 null 时
    //    MyBatis 缺 jdbcType 会抛 JdbcType OTHER，Service 按参数是否为空选一条方法，
    //    于是任何一条 SQL 都不会绑定 null 参数（契约 F-17.2 #4）。
    // 本工程未注册 MP 的内置分页拦截器，分页一律「页 + 独立 COUNT」两条手写 SQL，offset 由 Service 算。
    // ------------------------------------------------------------------

    /**
     * 端点① 备货单聚合（不带 storeId 过滤，契约 F-17.3「聚合 SQL」逐字口径）：
     * 按 (店, 菜品) 分组给出 SUM(quantity) 与该菜品被多少笔订单包含（COUNT(DISTINCT o.id)）。
     * store_id 可为 NULL（现库 2 笔待提单里就有 1 笔 NULL），GROUP BY 天然把 NULL 归一组，
     * 不丢行；出参侧的组名映射由 Service 负责（null → 「未指定自提点」）。
     * cancelled 恒排除。返回 List&lt;Map&gt;：MP 对未声明 resultMap 的聚合列按别名映射，
     * 且 <b>SUM 在 MySQL 下回 BigDecimal、COUNT 回 Long</b>，Service 侧显式转 Integer。
     * STRAIGHT_JOIN（G-6/M1，零 schema 降成本）：默认计划让优化器用 o.idx_status 的 range 驱动，
     * 单请求成本≈全库非取消订单数、且商家间共享；钉死 oi 先走 idx_merchant 裁掉本人明细，
     * 再以 PRIMARY eq_ref 回查 o。本机 EXPLAIN 前后对比：驱动表 o(range,idx_status) → oi(ref,idx_merchant,const)，
     * 同入参逐组数字与改前一致（quantity/orderCount 全等）。
     * ORDER BY 首列 store_id IS NULL（G-5/L6）：MySQL 升序 NULL 最小，不加此键「未指定自提点」
     * 会永远霸占备货单第一组；IS NULL 为 0/1，先把 NULL 组沉底，组内仍按 quantity 降序。
     */
    @Select("SELECT o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "oi.dish_id AS dishId, oi.dish_name AS dishName, "
            + "SUM(oi.quantity) AS quantity, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id, o.store_name, o.pickup_date, oi.dish_id, oi.dish_name "
            + "ORDER BY o.store_id IS NULL, o.store_id ASC, quantity DESC")
    List<Map<String, Object>> selectPickupPlan(@Param("merchantId") Long merchantId,
                                               @Param("date") LocalDate date);

    /** 端点① 备货单聚合（带 storeId 过滤）：与上方一条只差 store_id 等值，口径逐字相同（含 STRAIGHT_JOIN 与 NULL 组沉底） */
    @Select("SELECT o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "oi.dish_id AS dishId, oi.dish_name AS dishName, "
            + "SUM(oi.quantity) AS quantity, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.store_id = #{storeId} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id, o.store_name, o.pickup_date, oi.dish_id, oi.dish_name "
            + "ORDER BY o.store_id IS NULL, o.store_id ASC, quantity DESC")
    List<Map<String, Object>> selectPickupPlanByStore(@Param("merchantId") Long merchantId,
                                                      @Param("date") LocalDate date,
                                                      @Param("storeId") Long storeId);

    /**
     * 端点① 每组「N 单」的**店级**去重单数：上方聚合的 GROUP BY 含 dish_id，其 orderCount 是
     * 「含该菜品的订单数」（一笔多菜订单会在多行里各计一次），拿它当组头的店级单数会重复计数。
     * 故按店单独 GROUP BY o.store_id 取 COUNT(DISTINCT o.id)。
     * 不带 storeId 的本条返回每店一行（NULL 店 key 为 null，Service 用允许 null key 的
     * LinkedHashMap 接住）；带 storeId 时 Service 改调下方「孪生」一条把谓词下推进 SQL
     * （G-1/M2：与本查询同仓的「storeId 二选一」既有模式，不绑 null 参数），
     * 两条结果与「Java 侧按 key 取那一行」逐字相等。
     */
    @Select("SELECT o.store_id AS storeId, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id")
    List<Map<String, Object>> countPickupOrdersPerStore(@Param("merchantId") Long merchantId,
                                                        @Param("date") LocalDate date);

    /** 端点① 店级去重单数（带 storeId 过滤的孪生条）：与上方一条只差 store_id 等值，其余口径逐字相同 */
    @Select("SELECT o.store_id AS storeId, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.store_id = #{storeId} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id")
    List<Map<String, Object>> countPickupOrdersForStore(@Param("merchantId") Long merchantId,
                                                        @Param("date") LocalDate date,
                                                        @Param("storeId") Long storeId);

    /**
     * 端点② 明细页（带 date 过滤）：明细列取 order_item 的下单快照（dish_name/price），
     * **不 JOIN dish**（商家改归属/删品后历史不漂移，契约 F-17.2 #7）。
     * status 参数由 Service 归一为非 null 的三值之一（pending_pickup/completed/all），
     * 'all' 走常量比较、不绑定 null，故无需再拆方法；`o.status <> 'cancelled'` 恒在，
     * 三个取值都排除了 cancelled（all 语义 = 全部非取消）。
     * 出参含 o.phone 明文，仅供 Service 经 PhoneMask 脱敏后输出 maskedPhone；
     * **不取 o.address（用户私有地址，任何商家出参一律不含）、不取 o.user_id**。
     * ORDER BY 三列且末列 oi.id 唯一 → 分页全序稳定，不重不漏。
     * STRAIGHT_JOIN 同端点①（G-6/M1）：默认计划按 o.idx_status 驱动 ≈ 全库非取消订单成本，
     * 钉死 oi.idx_merchant 先裁商家。
     */
    @Select("SELECT oi.id AS orderItemId, oi.order_id AS orderId, oi.dish_id AS dishId, "
            + "oi.dish_name AS dishName, oi.quantity AS quantity, oi.price AS price, "
            + "oi.fulfill_status AS fulfillStatus, "
            + "o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "o.status AS status, o.pay_status AS payStatus, o.phone AS phone, "
            + "o.create_time AS createTime "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status}) "
            + "ORDER BY o.pickup_date DESC, o.id DESC, oi.id ASC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectOrderItemsByDate(@Param("merchantId") Long merchantId,
                                                     @Param("date") LocalDate date,
                                                     @Param("status") String status,
                                                     @Param("offset") long offset,
                                                     @Param("size") int size);

    /** 端点② 明细页（date 缺省 = 不限自提日期）：与上方一条只差去掉 pickup_date 等值 */
    @Select("SELECT oi.id AS orderItemId, oi.order_id AS orderId, oi.dish_id AS dishId, "
            + "oi.dish_name AS dishName, oi.quantity AS quantity, oi.price AS price, "
            + "oi.fulfill_status AS fulfillStatus, "
            + "o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "o.status AS status, o.pay_status AS payStatus, o.phone AS phone, "
            + "o.create_time AS createTime "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status}) "
            + "ORDER BY o.pickup_date DESC, o.id DESC, oi.id ASC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectOrderItemsAllDates(@Param("merchantId") Long merchantId,
                                                       @Param("status") String status,
                                                       @Param("offset") long offset,
                                                       @Param("size") int size);

    /** 端点② 计数（带 date 过滤）：WHERE 与 selectOrderItemsByDate 逐字相同，仅去掉 ORDER BY/LIMIT */
    @Select("SELECT COUNT(*) FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status})")
    long countOrderItemsByDate(@Param("merchantId") Long merchantId,
                               @Param("date") LocalDate date,
                               @Param("status") String status);

    /** 端点② 计数（date 缺省）：WHERE 与 selectOrderItemsAllDates 逐字相同 */
    @Select("SELECT COUNT(*) FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status})")
    long countOrderItemsAllDates(@Param("merchantId") Long merchantId,
                                 @Param("status") String status);

    // ------------------------------------------------------------------
    // F-19/G-05 Step 1 商家端数据看板（契约 F-19.4 #1：三条 @Select，形态逐字照上方 F-17 那 8 条）：
    // 恒带 oi.merchant_id = #{merchantId}（D-2 归属只来自注入）、STRAIGHT_JOIN 钉 oi 为驱动表（上方
    // selectPickupPlan 那条 G-6/M1 注释的理由在本组同样成立：默认计划会用 o.idx_status/o.idx_create_time 驱动，
    // 成本≈全库订单数且商家间共享）、
    // JOIN 键不做 CAST（orders.id 是 varchar 雪花串）。时间窗 D-3 只在 SQL 侧算（DATE_SUB(CURDATE(), INTERVAL
    // (#{days}-1) DAY)，自然日含今日），Java 侧不参与任何日期运算（免服务器时区把窗口算歪）。
    // 本组仍是只读，上方注释第 1 条「每条都带 oi.merchant_id」对这三条同样成立。
    // ------------------------------------------------------------------

    /**
     * 看板汇总（契约 F-19.2 D-4 的 ①②③，一行四列）：
     * ① {@code gmvBeforeCoupon} = {@code IFNULL(SUM(oi.price*oi.quantity),0)}——**券前**口径：
     *   {@code oi.price} 是下单时的成交单价快照（秒杀件为秒杀价，OrderServiceImpl:146），
     *   而 {@code orders.total_price} 是券抵扣后的订单级金额（OrderServiceImpl:161），两者天然差一张券，
     *   所以本列不叫「已收款」，出参文案随 D-5 口径隔离卡说明。
     * ② {@code orderCount} = {@code COUNT(DISTINCT oi.order_id)}（明细级去重，一笔多菜订单只算一单；
     *   上方 countPickupOrdersPerStore 那条注释说过「聚合行里的 dish 级 orderCount 不能当单级数用」，
     *   这里本来就是单级口径）。
     * ③ {@code itemCount} = {@code IFNULL(SUM(oi.quantity),0)}。数值列一律 IFNULL 兜 0（G5：零单商家
     *   必须出 0 不出 null），三列共同 WHERE = 归属 + {@code o.pay_status=1} + {@code o.status<>'cancelled'}
     *   + 窗口 + {@code oi.price > 0}（见下方 BE-G5-4 段）。
     * ④ {@code windowStart} = 与 WHERE 里那条窗口谓词**同式**的 DATE_SUB 取回（D-3/F-19.4 #4：窗口口径
     *   只有一处真源，绝不在 Java 用 LocalDate/时区另算一遍）。外面套 DATE_FORMAT 是为了让 SQL 直接回
     *   {@code 'YYYY-MM-DD'} 字符串——裸 DATE 经 Map 结果集会拿到 java.sql.Date，Jackson 默认按时间戳序列化，
     *   G6 的 {@code \d{4}-\d{2}-\d{2}} 断言就会失败（值本身仍是同一天，但出参形状不合契约）。
     * 无 GROUP BY 的聚合恒回 1 行（零明细时由 IFNULL/COUNT 兜 0），故本方法不会返回空集。
     * <p><b>{@code AND oi.price > 0}（BE-G5-4，F-19.8 S-5）——本期销售口径的决定，三条 SQL 同加</b>：
     * 免费领的 0 元单在 {@code FreeServiceImpl#claim}（价写死 {@code BigDecimal.ZERO}、{@code pay_status} 由 {@code PaymentSettlement#settle} 置 1）是 {@code pay_status=1} 且 {@code price=0} 的
     * 真实成交单，不加这一条它会进 {@code orderCount}/{@code itemCount} 却不进 {@code gmvBeforeCoupon}。
     * 实测 merchant 1 就是 2 单 / 2 件 / 0.00 元，出参组合是「订单数 2、商品件数 2、销售额 0.00」——
     * D-5 那三行口径说明解释不了这个组合，商家只会以为系统算错了。0 元领取单没有成交金额，
     * 就不进销售额口径；这一判据改的是谓词，F-19.5 G4 留过的读数（{@code refund=19}、{@code gmv=41.80} 等）
     * 由主会话重跑整套对账再归档。**取消/退款金额口径与结算（佣金/应收）本期一律不立**，随 m19+ 另议，
     * 所以这里只做「排除 0 元单」这一件事，不顺手加任何金额分类谓词。
     */
    @Select("SELECT IFNULL(SUM(oi.price * oi.quantity), 0) AS gmvBeforeCoupon, "
            + "COUNT(DISTINCT oi.order_id) AS orderCount, "
            + "IFNULL(SUM(oi.quantity), 0) AS itemCount, "
            + "DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY), '%Y-%m-%d') AS windowStart "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pay_status = 1 "
            + "AND o.status <> 'cancelled' "
            + "AND oi.price > 0 "
            + "AND o.create_time >= DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY)")
    Map<String, Object> selectStatSummary(@Param("merchantId") Long merchantId,
                                          @Param("days") Integer days);

    /**
     * 看板第四卡 refundCount（契约 D-4 ④）：同归属、同窗口，但取 {@code o.status='cancelled'} 的
     * 去重单数——「要让商家看到被取消了多少单，别只报好消息」。窗口谓词与上方汇总条逐字同式，
     * 两卡才是同一个窗口；此处**不加** pay_status 条件（D-4 ④ 原文只换了 status 这一条谓词，
     * 未支付的取消单同样是一单取消，契约 S-4 已实测这点：全库 38 笔取消单里 37 笔从未支付，
     * 所以谓词不动、由前端文案承担「含未付款自动关单」的说明，改判据会推翻已留证读数）。
     * {@code AND oi.price > 0} 与上方汇总条同一口径（BE-G5-4：0 元领取单不进本看板），
     * 两条 SQL 的 WHERE 差异仍只有 status 那一处。
     * COUNT 回 BIGINT，方法签名用 long（对齐上方 countOrderItemsByDate 的既有写法）。
     */
    @Select("SELECT COUNT(DISTINCT oi.order_id) "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.status = 'cancelled' "
            + "AND oi.price > 0 "
            + "AND o.create_time >= DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY)")
    long selectStatCancelledCount(@Param("merchantId") Long merchantId,
                                  @Param("days") Integer days);

    /**
     * 看板 Top 5 商品（契约 D-4 末段逐字口径）：WHERE 与汇总条同一套（归属 + 已支付 + 非取消 + 窗口
     * + {@code oi.price > 0}，BE-G5-4 同口径，否则 Top 的合计与 gmvBeforeCoupon 的分母又不一致），
     * <p><b>{@code GROUP BY oi.dish_id} 单列（BE-G5-3，F-19.8 S-3）</b>：上一批这里是
     * {@code GROUP BY oi.dish_id, oi.dish_name}，并留注释称「加 dish_name 是 ONLY_FULL_GROUP_BY 的必要项」——
     * <b>那条理由是错的，注释一并改掉</b>：本库 {@code @@sql_mode} 确实含 ONLY_FULL_GROUP_BY，但
     * {@code ANY_VALUE()} 正是该模式下官方给的逃生口，实测可用（代理在带 ONLY_FULL_GROUP_BY 的本机库上跑通）。
     * 多带 dish_name 的实际后果是活洞：商家改一次菜名，历史明细里新旧两个快照名就把同一 dish_id 劈成两行——
     * 实测 {@code keys=["d33","d44","d33"]} 重复出 3 行，前端 {@code rowKey} 只认 dishId（{@code dashboard.vue} 的 {@code rowKey(r, i)}）
     * → Vue 重复键、切窗口后金额与标签错行；且劈出来的多余行会挤掉真 Top 5 的第 5 名。
     * <p>dishName 仍取 {@code order_item} 的下单快照列（init.sql 的 dish_name），**禁止 JOIN dish 取名**，
     * 否则改名/删品后历史榜单会跟着漂移（上方 selectOrderItemsByDate 同口径）；{@code ANY_VALUE} 在这里
     * 的语义就是「该 dish_id 的任一名快照」，配合按 dish_id 聚合，一行一名、dishId 恢复唯一。
     * ORDER BY 必须两列：{@code amount DESC} 之后用 {@code oi.dish_id ASC} 兜底，
     * 否则并列 GMV 的两次刷新会换序，看板上的条形图每次跳。LIMIT 5 写死（本期不做 Top 分页，RB 红线）。
     */
    @Select("SELECT oi.dish_id AS dishId, ANY_VALUE(oi.dish_name) AS dishName, "
            + "IFNULL(SUM(oi.quantity), 0) AS quantity, "
            + "IFNULL(SUM(oi.price * oi.quantity), 0) AS amount "
            + "FROM order_item oi STRAIGHT_JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pay_status = 1 "
            + "AND o.status <> 'cancelled' "
            + "AND oi.price > 0 "
            + "AND o.create_time >= DATE_SUB(CURDATE(), INTERVAL (#{days} - 1) DAY) "
            + "GROUP BY oi.dish_id "
            + "ORDER BY amount DESC, oi.dish_id ASC "
            + "LIMIT 5")
    List<Map<String, Object>> selectStatTopDishes(@Param("merchantId") Long merchantId,
                                                  @Param("days") Integer days);

    // ------------------------------------------------------------------
    // F-18.8/D-15 缺货登记（本接口在一期的**唯一写方法**；上方 F-17 的 8 条与 F-19 的 3 条 @Select
    // 只读口径不变，注释第 1 条「每条都带 oi.merchant_id = #{merchantId}」对这条 UPDATE 同样成立）。
    // fulfill_status 是 m17 的履约旁路列（normal/shortage），与 orders.status 三态正交（D-10/RE：状态机不动）。
    // ------------------------------------------------------------------

    /**
     * 缺货登记条件 UPDATE（契约 F-18.8 第 5 条）：归属双条件 {@code id + merchant_id}，
     * 值 shortage 由服务端钉死、不收任何入参。
     * <p>额外的 {@code AND fulfill_status != 'shortage'} 是<b>已登记偏差</b>（理由写在
     * {@code MerchantFulfillServiceImpl} 类注释）：Connector/J 默认按 matched rows 回影响行数
     * （application-dev.yml:5 的 URL 没带 useAffectedRows=true），少了这一条，重复登记会回 1 行、
     * 进而落第二行 ops_log，与 D-6「幂等成功不重复写日志」冲突。定性结果（200/404/幂等）与契约一致。
     */
    @Update("UPDATE order_item SET fulfill_status = 'shortage' "
            + "WHERE id = #{orderItemId} AND merchant_id = #{merchantId} "
            + "AND fulfill_status != 'shortage'")
    int markShortage(@Param("orderItemId") Long orderItemId, @Param("merchantId") Long merchantId);
}
