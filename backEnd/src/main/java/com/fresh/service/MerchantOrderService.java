package com.fresh.service;

import java.util.Map;

/**
 * 商家端只读备货单服务（F-17/G-03 Step 1，契约 F-17.3 端点表两行）。
 *
 * <p>与 MerchantDishService 同一套鉴权前提：登录与 role=merchant 判定已在 AuthInterceptor 的
 * /api/merchant/ 前缀分支收口（查库定角色 + merchant_id 为空即 403 + ATTR_MERCHANT_ID 注入），
 * 本接口不重复角色校验，但每个以 merchantId 为参的方法首行自守卫判空回 403（纵深防御，
 * m16 的 order_item.merchant_id 可空，「参数为空即不加条件」一旦写成动态 SQL 就是全表泄露）。
 *
 * <p>R3 同型但本期为**读**：两个方法都只经 OrderItemMapper 那 7 条恒带
 * {@code oi.merchant_id = #{merchantId}} 的 @Select 取数，不存在任何不带归属条件的读路径；
 * 入参 storeId 是过滤条件不是归属条件，不能替代 merchant_id。
 *
 * <p>RC「只读」：本接口没有写方法，也不经过商家侧的写闸门判定（读放行是 G-02 已定口径——
 * 停业商家仍要能看自己的历史备货单去收尾备货），也不落运营审计表。
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
}
