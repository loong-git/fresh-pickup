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
    // F-17/G-03 商家端只读备货单（三条硬约束，逐条可 grep 核验）：
    // 1) 归属：下面 8 条 @Select 每一条都带 `oi.merchant_id = #{merchantId}`，值只来自
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
    // F-18.8/D-15 缺货登记（本接口在一期的**唯一写方法**；上面 8 条 @Select 的只读口径不变，
    // 上面注释第 1 条「每条都带 oi.merchant_id = #{merchantId}」对这条 UPDATE 同样成立）。
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
