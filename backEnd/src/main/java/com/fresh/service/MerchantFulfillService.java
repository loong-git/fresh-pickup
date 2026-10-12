package com.fresh.service;

/**
 * 商家侧履约现场动作（F-18.8 第 5 条/D-15）。
 *
 * <p>本期只有一个方法：缺货登记。它是 {@code order_item.fulfill_status}（m17）在一期的**唯一写入方**
 * （D-9 已裁定短少联动到货确认一期不做），所以这个类只管写旁路列 + 落 ops_log，不碰订单三态（D-10/RE）。
 *
 * <p>为什么不并进 MerchantOrderService：那个类自陈「RC 只读、无写方法、不落运营审计」
 * （MerchantOrderController:25 与 MerchantOrderServiceImpl 类注释），把 PUT 塞进去等于让那句注释变成假话；
 * D-15 已裁定新建本类。
 */
public interface MerchantFulfillService {

    /**
     * 缺货登记（{@code PUT /api/merchant/order-items/{id}/shortage}）：
     * 条件 UPDATE {@code SET fulfill_status='shortage' WHERE id=? AND merchant_id=?}。
     *
     * <p>归属：merchantId 只来自 {@code AuthInterceptor.ATTR_MERCHANT_ID}（前缀分支查库定角色后注入），
     * 不收任何归属入参（RF）。
     *
     * <p>定性（D-6 范式）：影响行数 &gt;0 → 成功并写 ops_log；
     * 影响行数 =0 时按 selectById 定性——行不存在、或 {@code merchant_id} 不是自己（含 NULL）→ <b>404</b>
     * （不泄漏「他人行存在」）；条件 UPDATE 的 WHERE 只有 id+merchant_id 两个字段，
     * 所以「是自己的行却没改动」只剩一种可能：已经是 shortage → <b>幂等成功</b>且不重复写日志。
     *
     * @param userId      操作者（ATTR_USER_ID）：D-5 裁定缺货登记的操作者是商家 userId，写 ops_log 而非 admin_audit_log
     * @param merchantId  归属商家 id
     * @param orderItemId 订单明细 id
     */
    void registerShortage(Long userId, Long merchantId, Long orderItemId);
}
