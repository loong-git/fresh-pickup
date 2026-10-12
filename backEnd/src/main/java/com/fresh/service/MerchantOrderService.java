package com.fresh.service;

import java.util.Map;

/**
 * 商家端只读备货单服务（F-17/G-03 Step 1，契约 F-17.3 端点表两行）
 * ＋ 数据看板与营销参与态（F-19/G-05 Step 1，契约 F-19.4）。
 *
 * <p>与 MerchantDishService 同一套鉴权前提：登录与 role=merchant 判定已在 AuthInterceptor 的
 * /api/merchant/ 前缀分支收口（查库定角色 + merchant_id 为空即 403 + ATTR_MERCHANT_ID 注入），
 * 本接口不重复角色校验，但每个以 merchantId 为参的方法首行自守卫判空回 403（纵深防御，
 * m16 的 order_item.merchant_id 可空，「参数为空即不加条件」一旦写成动态 SQL 就是全表泄露）。
 *
 * <p>R3 同型但本期为**读**：取数只经 OrderItemMapper 那 11 条恒带
 * {@code oi.merchant_id = #{merchantId}} 的 @Select（F-17 的 8 条 + F-19 的 3 条）与
 * DishMapper 那 4 条恒带 {@code d.merchant_id = #{merchantId}} 的 @Select（F-19 营销参与态），
 * 不存在任何不带归属条件的读路径；
 * 入参 storeId 是过滤条件不是归属条件，不能替代 merchant_id。
 *
 * <p>RC「只读」：本接口没有写方法，也不经过商家侧的写闸门判定（读放行是 G-02 已定口径——
 * 停业商家仍要能看自己的历史备货单去收尾备货），也不落运营审计表。
 * 唯一例外是 F-19/G-05 的两个新方法：契约 D-9 裁定「只读侧也判停业」，故 {@link #stat} 与
 * {@link #marketing} 调 {@code MerchantService#assertActive}（403），判据与文案逐字复用既有那一处、
 * 不新增句子；G-03 的两个方法行为一字未动。
 */
public interface MerchantOrderService {

    /**
     * 备货单聚合（端点①）：按自提点分组给出该日各菜品的备货总量。
     *
     * @param date    {@code yyyy-MM-dd}；<b>为空时默认「明天」</b>（{@code LocalDate.now().plusDays(1)}，
     *                与 23:00 截单的 T+1 波次一致），且实际生效值必须回显在返回体的 {@code date} 键上
     *                （防「静默改口径」）；非空但不匹配该格式 → 400
     * @param storeId 可选自提点过滤；null 表示不限
     * @return {@code {date: 实际生效日期, groups: [{storeId, storeName, orderCount,
     *         items: [{dishId, dishName, quantity}]}]}}；
     *         storeName 为 null（含 store_id 为 NULL 的那一组）时出 {@code "未指定自提点"}，不丢行
     */
    Map<String, Object> pickupPlan(Long merchantId, String date, Long storeId);

    /**
     * 供货明细分页（端点②）：明细行 join orders，每行给 maskedPhone，不给 phone 明文/address/user_id。
     *
     * @param date   可选 {@code yyyy-MM-dd} 过滤；<b>为空表示不限自提日期</b>（与端点①不同，本端点无默认值，
     *               出参也不回显 date）；非空但不匹配该格式 → 400
     * @param status {@code pending_pickup} / {@code completed} / {@code all}；
     *               <b>未知取值与 null 一律兜底 all</b>（同 G-02 边界 14 口径，不报错）；
     *               三个取值都恒排除 cancelled
     * @param page   默认 1；{@code <1} 归一为 1（夹而不拒）
     * @param size   默认 20；夹到 {@code [1, 50]}（夹而不拒），实际生效值回显在出参 size 键
     * @return {@code {list: [{orderItemId, orderId, dishId, dishName, quantity, price, storeId, storeName,
     *         pickupDate, status, payStatus, maskedPhone, createTime}], page, size, total, hasMore}}
     */
    Map<String, Object> orderItems(Long merchantId, String date, String status, Integer page, Integer size);

    /**
     * 数据看板（F-19/G-05 端点③，契约 D-3/D-4）：滚动自然日窗口内的四卡 + Top 5 商品。
     *
     * <p>停业闸门（D-9）：本方法**读侧也判** {@code merchant.status<>'active'} → 403，
     * 判据与文案复用 {@code MerchantService#assertActive}（与 G-03 两个方法的「读放行」口径不同，
     * 是 F-19 契约点名的例外）。
     *
     * @param days 窗口天数：{@code [1,90]}；null → 缺省 <b>7</b>（生效值回显在出参 days 键）；
     *             越界 → 400「天数需在 1 到 90 之间」（D-3 钉死的文案，本期唯一新增句）
     * @return {@code {days, windowStart:"YYYY-MM-DD", gmvBeforeCoupon:"12.30", orderCount, itemCount,
     *         refundCount, top:[{dishId, dishName, quantity, amount:"12.30"}]}}。
     *         {@code windowStart} 由 SQL 侧与窗口谓词同式取回（D-3：绝不在 Java 用 LocalDate/时区另算）；
     *         金额列 {@code setScale(2, HALF_UP)}；零单商家四卡全 0、{@code top:[]}（G5，不出 null）；
     *         {@code top[].dishName} 取 order_item 的下单快照，不 JOIN dish（D-4）
     */
    Map<String, Object> stat(Long merchantId, Integer days);

    /**
     * 营销参与态（F-19/G-05 端点④，契约 D-6/D-7）：本店被平台设置的秒杀件与免费领活动，只读、零报名口。
     *
     * <p>停业闸门同 {@link #stat}（D-9）。
     *
     * @return {@code {seckill:{count, items:[{dishId, name, price, seckillPrice, limitBuy, onSale,
     *         soldCountTotal}]}, freeClaim:{count, items:[{activityId, dishId, name, activityStatus,
     *         dailyQuota, dailyClaimed, threshold}]}}}。
     *         {@code count} 是真总数、{@code items} 上限 20 条（D-6）；
     *         {@code soldCountTotal} 是 dish.sold_count 的**历史累计**、不是本窗口值（D-7 点名的改名列）；
     *         本仓现状 merchant 2/3 两个参与位都是 0 行 → {@code count:0} 且 {@code items:[]}
     *         是正常出参，空集合绝不塌成 null
     */
    Map<String, Object> marketing(Long merchantId);
}
