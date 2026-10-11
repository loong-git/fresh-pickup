package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
    // F-17/G-03 商家端只读备货单（三条硬约束，逐条可 grep 核验）：
    // 1) 归属：下面 7 条 @Select 每一条都带 `oi.merchant_id = #{merchantId}`，值只来自
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
     */
    @Select("SELECT o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "oi.dish_id AS dishId, oi.dish_name AS dishName, "
            + "SUM(oi.quantity) AS quantity, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id, o.store_name, o.pickup_date, oi.dish_id, oi.dish_name "
            + "ORDER BY o.store_id ASC, quantity DESC")
    List<Map<String, Object>> selectPickupPlan(@Param("merchantId") Long merchantId,
                                               @Param("date") LocalDate date);

    /** 端点① 备货单聚合（带 storeId 过滤）：与上方一条只差 store_id 等值，口径逐字相同 */
    @Select("SELECT o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "oi.dish_id AS dishId, oi.dish_name AS dishName, "
            + "SUM(oi.quantity) AS quantity, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.store_id = #{storeId} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id, o.store_name, o.pickup_date, oi.dish_id, oi.dish_name "
            + "ORDER BY o.store_id ASC, quantity DESC")
    List<Map<String, Object>> selectPickupPlanByStore(@Param("merchantId") Long merchantId,
                                                      @Param("date") LocalDate date,
                                                      @Param("storeId") Long storeId);

    /**
     * 端点① 每组「N 单」的**店级**去重单数：上方聚合的 GROUP BY 含 dish_id，其 orderCount 是
     * 「含该菜品的订单数」（一笔多菜订单会在多行里各计一次），拿它当组头的店级单数会重复计数。
     * 故按店单独 GROUP BY o.store_id 取 COUNT(DISTINCT o.id)。
     * 一条方法覆盖带/不带 storeId 两种请求：本查询每店一行、店与店互不影响，Service 在 storeId
     * 非空时按 key 取那一行即可，与「加 store_id 等值条件」结果逐字相等。
     * NULL 店同样成一行（key 为 null），Service 用允许 null key 的 LinkedHashMap 接住。
     */
    @Select("SELECT o.store_id AS storeId, COUNT(DISTINCT o.id) AS orderCount "
            + "FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "GROUP BY o.store_id")
    List<Map<String, Object>> countPickupOrdersPerStore(@Param("merchantId") Long merchantId,
                                                        @Param("date") LocalDate date);

    /**
     * 端点② 明细页（带 date 过滤）：明细列取 order_item 的下单快照（dish_name/price），
     * **不 JOIN dish**（商家改归属/删品后历史不漂移，契约 F-17.2 #7）。
     * status 参数由 Service 归一为非 null 的三值之一（pending_pickup/completed/all），
     * 'all' 走常量比较、不绑定 null，故无需再拆方法；`o.status <> 'cancelled'` 恒在，
     * 三个取值都排除了 cancelled（all 语义 = 全部非取消）。
     * 出参含 o.phone 明文，仅供 Service 经 PhoneMask 脱敏后输出 maskedPhone；
     * **不取 o.address（用户私有地址，任何商家出参一律不含）、不取 o.user_id**。
     * ORDER BY 三列且末列 oi.id 唯一 → 分页全序稳定，不重不漏。
     */
    @Select("SELECT oi.id AS orderItemId, oi.order_id AS orderId, oi.dish_id AS dishId, "
            + "oi.dish_name AS dishName, oi.quantity AS quantity, oi.price AS price, "
            + "o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "o.status AS status, o.pay_status AS payStatus, o.phone AS phone, "
            + "o.create_time AS createTime "
            + "FROM order_item oi JOIN orders o ON oi.order_id = o.id "
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
            + "o.store_id AS storeId, o.store_name AS storeName, o.pickup_date AS pickupDate, "
            + "o.status AS status, o.pay_status AS payStatus, o.phone AS phone, "
            + "o.create_time AS createTime "
            + "FROM order_item oi JOIN orders o ON oi.order_id = o.id "
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
    @Select("SELECT COUNT(*) FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.pickup_date = #{date} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status})")
    long countOrderItemsByDate(@Param("merchantId") Long merchantId,
                               @Param("date") LocalDate date,
                               @Param("status") String status);

    /** 端点② 计数（date 缺省）：WHERE 与 selectOrderItemsAllDates 逐字相同 */
    @Select("SELECT COUNT(*) FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE oi.merchant_id = #{merchantId} "
            + "AND o.status <> 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status})")
    long countOrderItemsAllDates(@Param("merchantId") Long merchantId,
                                 @Param("status") String status);
}
