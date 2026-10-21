package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 当日实付累计（M8 免费领门槛口径）：该用户当日创建且已支付（pay_status=1）订单的 total_price SUM，
     * 免费单（client_request_id LIKE 'free-%'，0 元单）不计入门槛；IFNULL 兜住无单返回 0。
     */
    @Select("SELECT IFNULL(SUM(total_price), 0) FROM orders "
            + "WHERE user_id = #{userId} AND pay_status = 1 "
            + "AND client_request_id NOT LIKE 'free-%' AND DATE(create_time) = #{today}")
    BigDecimal sumTodayPaid(@Param("userId") Long userId, @Param("today") LocalDate today);

    /**
     * 取货码占用探测（F-18.6/D-3）：随机 6 位码写入前先读一次是否已被他人占用，命中即换码重试——
     * 把撞 uk_pickup_code 从「写进去抛 1062、脏了整个支付事务」前移成一次普通读。
     * 表名/参数写法照 OrderItemMapper.countByUserIdAndDishId 的既有形态（COUNT(*) 回 int）。
     */
    @Select("SELECT COUNT(*) FROM orders WHERE pickup_code = #{code}")
    int countByPickupCode(@Param("code") String code);

    /**
     * 取货码挂载（F-18.6/D-3，只在 PaymentSettlement#settle 的支付成功事务内调用）：
     * 条件必须带 {@code pickup_code IS NULL} —— 这是幂等的全部依据：重复支付、并发二次置态都不会换码
     * （D-4 核销幂等判定要靠这个码稳定）；影响行数 0 由上层重试、5 次仍失败则抛 BizException 回滚整单支付。
     */
    @Update("UPDATE orders SET pickup_code = #{code} WHERE id = #{orderId} AND pickup_code IS NULL")
    int attachPickupCode(@Param("orderId") String orderId, @Param("code") String code);

    // ------------------------------------------------------------------
    // F-20.18 Step 5b-1 追加（既有三条一字未动）。本段的全部纪律与 AdminAuditLogMapper 的 F-20.16 一组同构：
    // 1) RB 分页：本工程没注册 MP 的内置分页拦截器，用 selectPage/Page<T> 会静默回全量且 total=0，
    //    故「页 + 独立 COUNT」两条手写 SQL，offset 由 Service 算并校验（越界脏值 400、不夹取）。
    // 2) D-16：两条 WHERE <b>逐字相同</b>——共用下面这一个编译期常量串 ORDER_FILTER_WHERE，
    //    从代码结构上杜绝「复制后手改一处」的漂移（total 必须是同一筛选条件下的总数，否则页面「共 0 条」却画着 20 行）。
    // 3) D-9/D-17：筛选一律精确等值，零 LIKE、零 <script>、零 @SelectProvider、零字符串拼接；
    //    形态 (#{x} IS NULL OR col = #{x})——传值等值过滤、传 null 整条短路。同一参数在同一句里出现两次
    //    （IS NULL 一次、等值一次）是这一形态的既定代价、非笔误；AdminAuditLogMapper 已实测该形态
    //    在 Java null 经 MyBatis setNull(Types.OTHER) 落到 MySQL Connector/J 8 <b>不抛异常</b>（F-20.20 首条读数），
    //    故本批沿用同形态、不另加 jdbcType。等值匹配不区分大小写来自列 collation utf8mb4_unicode_ci，不是本层加的。
    // 4) D-18 形态纪律：DATETIME 一律 DATE_FORMAT(...,'%Y-%m-%d %H:%i:%s') 取串（裸 DATETIME 进 Map 会拿到
    //    java.sql.Timestamp，Jackson 按时间戳序列化，出参形状就不合契约了——createdAt 判例）；
    //    pickup_date 同理必须 DATE_FORMAT 成 '%Y-%m-%d'（java.sql.Date 也是同一个坑）。
    //    金额不在这里定形：total_price 取原始 DECIMAL，由 Service 的 moneyText 统一两位小数字符串
    //    （与 G-05/G-03 同口径，避免两处各自 setScale）。
    // 5) 列别名一律驼峰且不含下划线（MP 对 Map 结果不做下划线转驼峰，键名就是 SQL 里的别名）。
    // ------------------------------------------------------------------

    /**
     * 大盘四列聚合（F-20.18 收 N-20-2：这条原来是 {@code AdminV2ServiceImpl} 里用继承来的
     * {@code BaseMapper#selectMaps + QueryWrapper} 跑的妥协实现，「读 SQL 全在 mapper 里」这条可 grep 的纪律
     * 当时打了折，本期清单含 OrderMapper 故搬正）。四个表达式与别名逐字照搬前处常量的原文，
     * 口径仍 = {@code docs/日常运营SQL手册.md:125-132}：{@code COUNT(*)} / {@code IFNULL(SUM(total_price),0)} /
     * {@code IFNULL(SUM(status='pending_pickup'),0)} /
     * {@code IFNULL(SUM(CASE WHEN pay_status=1 THEN total_price ELSE 0 END),0)}，一行恒回。
     * <p><b>窗口</b>：{@code [当日 00:00, 次日 00:00)} 半开区间，与手册的
     * {@code create_time >= CURDATE() AND create_time < CURDATE() + INTERVAL 1 DAY} 同一个区间；
     * {@code day} 为 null（调用方没指定某一天）时两端都退回 {@code CURDATE()}，即「今日」继续由 <b>SQL 侧</b>裁决，
     * Java 侧不调 {@code LocalDate.now()}（D-3：那等于把「今天」交给应用服务器时区，与库时区可能差一天）。
     * {@code IFNULL(#{day}, CURDATE())} 因此是这条 SQL 的唯一窗口真源——写两遍同一个表达式，
     * 上界就是「同一下一个自然日」，不可能与下界各说各话。
     * <p>{@code day} 非 null 时是 Service 已过 {@code DATE_PATTERN} + {@code LocalDate.parse} 双重闸门的规范值，
     * 只经 {@code #{}} 绑定；{@code day + INTERVAL 1 DAY} 是 DATE 加一天，与 {@code day.atStartOfDay().plusDays(1)} 同刻。
     * <p>无 GROUP BY 的聚合恒回 1 行；{@code pendingPickupCount} 那列在手册里是裸 {@code SUM(status = 'pending_pickup')}，
     * 空数据日回 NULL，而契约要求「空数据日四个 0 值不是 null」，故保留既有那层 {@code IFNULL(...,0)}——谓词本体一字未动。
     */
    @Select("SELECT COUNT(*) AS orderCount, "
            + "IFNULL(SUM(total_price), 0) AS totalAmount, "
            + "IFNULL(SUM(status = 'pending_pickup'), 0) AS pendingPickupCount, "
            + "IFNULL(SUM(CASE WHEN pay_status = 1 THEN total_price ELSE 0 END), 0) AS paidAmount "
            + "FROM orders "
            + "WHERE create_time >= IFNULL(#{day}, CURDATE()) "
            + "AND create_time < IFNULL(#{day}, CURDATE()) + INTERVAL 1 DAY")
    Map<String, Object> selectOverviewDaily(@Param("day") LocalDate day);

    /**
     * F-20.18 D-16：orders 面的 COUNT 与页两条 SQL 的 WHERE <b>必须逐字相同</b>，这里用同一个编译期常量串作唯一来源。
     * 三个筛选全可 null（null = 该条件短路不生效）：
     * <ul>
     *   <li>{@code status}：三态白名单（{@code pending_pickup}/{@code completed}/{@code cancelled}），值域在
     *       Service 拦（白名单外 → 400），本层只做等值；</li>
     *   <li>{@code storeId}：自提点 ID 等值（{@code store_id} 可为 NULL——现库 69 笔里 39 笔 NULL，
     *       这类行只在「不限 storeId」时出现，不会被 {@code storeId} 传值筛到，是等值语义本来的样子）；</li>
     *   <li>{@code day}：按 {@code create_time} 自然日，与 overview 同窗口口径（D-17），半开区间
     *       {@code [day, day+1)}；写成区间而非 {@code DATE(create_time) = #{day}} 是为了不废掉 idx_create_time，
     *       且与上面 selectOverviewDaily 的窗口表达式同形。{@code #{day}} 在同一句里绑三次（IS NULL / 下界 / 上界），
     *       值恒等，同 D-9 那「既定代价」。</li>
     * </ul>
     */
    String ORDER_FILTER_WHERE = "WHERE (#{status} IS NULL OR o.status = #{status}) "
            + "AND (#{storeId} IS NULL OR o.store_id = #{storeId}) "
            + "AND (#{day} IS NULL OR (o.create_time >= #{day} AND o.create_time < #{day} + INTERVAL 1 DAY))";

    /**
     * 运营端订单列表的一页（{@code GET /api/admin/v2/orders}，契约 F-20.18 D-15 白名单逐键点名）：
     * <b>不选</b> {@code pickup_code}（取货码是核销凭证，只发单主本人，运营页不需要它）、
     * <b>不选</b> {@code user_id}、<b>不选</b> {@code client_request_id}（幂等内部键）——「不输出」写成「不查询」，
     * 泄露面才是零（同 StoreApplyMapper.selectLatestByUser 的纪律）。
     * {@code phone} 在这里<b>必须</b>取原文，因为脱敏走既有 {@code common/PhoneMask.mask}（D-15 点名用它、
     * 不再写第四份掩码实现，也就不能在 SQL 里 CONCAT 出一份第二口径）；掩码在 Service 侧完成，明文不出 Service。
     * {@code store_address}（自提点地址快照）按 D-15 明示可下发；它是自提点地址而非下单人住址。
     * <p>{@code ORDER BY o.create_time DESC, o.id DESC}：最新单在前（运营看的是刚刚发生的履约），
     * 末列 {@code id} 是主键、全序唯一，翻页不重复不漏（同 AdminAuditLogMapper 用 id 收尾的理由：
     * create_time 有同秒并列风险）。注意 {@code orders.id} 是雪花 19 位数字<b>串</b>，
     * 现库全等长，串序与数值序一致，故 DESC 稳定。
     * <p>offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底。
     */
    @Select("SELECT o.id AS id, o.total_price AS totalPrice, o.address AS address, o.phone AS phone, "
            + "DATE_FORMAT(o.pickup_date, '%Y-%m-%d') AS pickupDate, o.status AS status, "
            + "DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i:%s') AS createTime, "
            + "DATE_FORMAT(o.update_time, '%Y-%m-%d %H:%i:%s') AS updateTime, "
            + "o.store_id AS storeId, o.store_name AS storeName, o.store_address AS storeAddress, "
            + "o.pay_status AS payStatus, "
            + "DATE_FORMAT(o.pay_time, '%Y-%m-%d %H:%i:%s') AS payTime "
            + "FROM orders o "
            + ORDER_FILTER_WHERE + " "
            + "ORDER BY o.create_time DESC, o.id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectOrdersPage(@Param("status") String status,
                                               @Param("storeId") Long storeId,
                                               @Param("day") LocalDate day,
                                               @Param("offset") long offset,
                                               @Param("size") int size);

    /**
     * 运营端订单列表的总数（独立 COUNT）：WHERE 与 {@link #selectOrdersPage} 逐字相同
     * （同 {@link #ORDER_FILTER_WHERE}），仅去掉 SELECT 列表/ORDER BY/LIMIT；三个筛选参数与页那条签名顺序一致。
     */
    @Select("SELECT COUNT(*) FROM orders o " + ORDER_FILTER_WHERE)
    long countOrders(@Param("status") String status,
                     @Param("storeId") Long storeId,
                     @Param("day") LocalDate day);
}
