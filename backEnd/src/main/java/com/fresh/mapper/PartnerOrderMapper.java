package com.fresh.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 主理人（partner）侧手写 SQL（F-18.8 第 4 条）。
 *
 * <p>为什么不 extends BaseMapper&lt;Order&gt;：本期 partner 侧一条读、一条定性读、一条条件 UPDATE 全是手写
 * 白名单取数，没有任何地方需要 MP 的通用 CRUD（避免给 Order 实体挂第二个 Mapper，也杜绝「顺手 selectById
 * 把整行含 pickup_code/address 带出去」的泄露面）。三条硬约束照 OrderItemMapper 的既成范式：
 * <ol>
 *   <li><b>归属恒在</b>：每条列表 SQL 都带 {@code o.store_id IN (…)}，集合值只来自
 *       {@code AuthInterceptor.ATTR_PARTNER_STORE_IDS}（服务端按 token userId 查 store.owner_user_id），
 *       Controller/Service 不收任何归属入参（RF 红线）。集合为空在拦截器已 403，Service 侧再自守卫一次，
 *       绝不允许「集合为空 = 不加条件」的全表读。</li>
 *   <li><b>可选参数拆两条方法</b>：LocalDate 传 null 会因缺 jdbcType 抛 JdbcType OTHER
 *       （契约 F-17.2 #4 已踩），故带 date / 不带 date 各一条，Service 选方法，SQL 里永不绑 null。
 *       status 由 Service 归一为非 null 的三值之一，{@code (#{status} = 'all' OR o.status = #{status})}
 *       是常量比较，不需拆。</li>
 *   <li><b>分页手写</b>：{@code LIMIT #{offset}, #{size}} + 独立 COUNT（红线 RB：本工程没注册 MP 分页插件，
 *       用 selectPage/Page&lt;T&gt; 会静默回全量且 total=0）。ORDER BY 末列 oi.id 唯一 → 分页全序稳定。</li>
 * </ol>
 *
 * <p>{@code store_id IN} 需要 foreach，故这几条是 {@code <script>}：XML 里 {@code <>} 会被当标签起始，
 * 一律写 {@code !=}（MySQL 等价）；这也让「排除 cancelled」在四条 SQL 里逐字可 grep。
 *
 * <p>取列口径（RD 红线）：列表取 {@code o.phone} 明文只为让 Service 经 PhoneMask 脱敏后输出 maskedPhone，
 * **不取 o.address、不取 o.user_id、不取 o.pickup_code**（D-13：取货码不进清单，
 * 摊开整店客户的码等于让主理人绕过用户自行核销）。
 */
@Mapper
public interface PartnerOrderMapper {

    /**
     * 清单（带 pickupDate 过滤）。orderId 出完整值，后 6 位由 Service 截（截断动作留在出参白名单那一段，
     * 免得 SQL 里藏一层语义）；quantity/price 走 order_item 下单快照，不 JOIN dish（历史不漂移）。
     */
    @Select("<script>SELECT o.id AS orderId, oi.dish_name AS dishName, oi.quantity AS quantity, "
            + "oi.price AS price, o.store_id AS storeId, o.store_name AS storeName, "
            + "o.pickup_date AS pickupDate, o.status AS status, o.pay_status AS payStatus, "
            + "o.phone AS phone, o.create_time AS createTime "
            + "FROM orders o JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND o.status != 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status}) "
            + "AND o.pickup_date = #{date} "
            + "ORDER BY o.pickup_date DESC, o.id DESC, oi.id ASC "
            + "LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> selectOrdersByDate(@Param("storeIds") List<Long> storeIds,
                                                 @Param("date") LocalDate date,
                                                 @Param("status") String status,
                                                 @Param("offset") long offset,
                                                 @Param("size") int size);

    /** 清单（date 缺省 = 不限自提日期）：与上方一条只差去掉 pickup_date 等值 */
    @Select("<script>SELECT o.id AS orderId, oi.dish_name AS dishName, oi.quantity AS quantity, "
            + "oi.price AS price, o.store_id AS storeId, o.store_name AS storeName, "
            + "o.pickup_date AS pickupDate, o.status AS status, o.pay_status AS payStatus, "
            + "o.phone AS phone, o.create_time AS createTime "
            + "FROM orders o JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND o.status != 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status}) "
            + "ORDER BY o.pickup_date DESC, o.id DESC, oi.id ASC "
            + "LIMIT #{offset}, #{size}</script>")
    List<Map<String, Object>> selectOrdersAllDates(@Param("storeIds") List<Long> storeIds,
                                                   @Param("status") String status,
                                                   @Param("offset") long offset,
                                                   @Param("size") int size);

    /** 计数（带 date）：WHERE 与 selectOrdersByDate 逐字相同，仅去掉 ORDER BY/LIMIT */
    @Select("<script>SELECT COUNT(*) FROM orders o JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND o.status != 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status}) "
            + "AND o.pickup_date = #{date}</script>")
    long countOrdersByDate(@Param("storeIds") List<Long> storeIds,
                           @Param("date") LocalDate date,
                           @Param("status") String status);

    /** 计数（date 缺省）：WHERE 与 selectOrdersAllDates 逐字相同 */
    @Select("<script>SELECT COUNT(*) FROM orders o JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND o.status != 'cancelled' "
            + "AND (#{status} = 'all' OR o.status = #{status})</script>")
    long countOrdersAllDates(@Param("storeIds") List<Long> storeIds,
                             @Param("status") String status);

    /**
     * 核销定性预读（D-6）：按码取那一行，**只取定性需要的四列**（id/store_id/status/pay_status），
     * 不取 phone/address/pickup_code（这行的存在本身已经证明码可用，出参一律不回显码）。
     * 码是 uk_pickup_code 唯一列（m17）→ 至多一行。查无此行由 Service 定性成 404。
     */
    @Select("SELECT id AS orderId, store_id AS storeId, status AS status, pay_status AS payStatus "
            + "FROM orders WHERE pickup_code = #{code}")
    Map<String, Object> selectForVerify(@Param("code") String code);

    /**
     * 核销条件 UPDATE（D-6 四条件逐字）：归属集合 + 码 + 待提 + 已支付，四条全在 WHERE 里，
     * 影响行数 1 才算真把这一单从 pending_pickup 翻成 completed；并发重复核销时第二次回 0 行，
     * 由 Service 定性成幂等成功，绝不「先 set 再判」——状态判据不能既当条件又当动作。
     * update_time 显式 NOW()：MP 的 fill 只在 wrapper 走实体时生效，手写 SQL 里不自动带上。
     */
    @Update("<script>UPDATE orders SET status = 'completed', update_time = NOW() "
            + "WHERE id = #{orderId} "
            + "AND store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND pickup_code = #{code} "
            + "AND status = 'pending_pickup' "
            + "AND pay_status = 1</script>")
    int completeByPickupCode(@Param("orderId") String orderId,
                             @Param("code") String code,
                             @Param("storeIds") List<Long> storeIds);
}
