package com.fresh.service;

import java.util.List;
import java.util.Map;

/**
 * 主理人端（partner）服务（F-18.8 第 4 条，三端点：名下门店 / 核销清单 / 核销）。
 *
 * <p>鉴权前提全部在 AuthInterceptor 的 {@code /api/partner/} 新分支收口（F-18.8 第 2 条）：
 * 登录 → 按 token userId 查 {@code store.owner_user_id} 取门店 id 集合 → 集合为空即 403 →
 * 非空注入 {@code ATTR_PARTNER_STORE_IDS}。本接口不重复角色判定（RF：主理人不占 user.role，
 * 归属就是那份集合），但每个方法首行自守卫「集合为 null/空 → 403」，
 * 杜绝「集合为空就不加条件」退化成全表读（同 MerchantOrderServiceImpl#assertMerchant 的纵深防御）。
 *
 * <p>归属一律服务端解析：入参里没有任何 storeId/merchantId 之类的归属字段，
 * storeIds 只能来自 {@code @RequestAttribute}（契约 F-18.8 第 4 条 + 07 册 :119）。
 */
public interface PartnerOrderService {

    /**
     * 名下门店（{@code GET /api/partner/stores}）。
     *
     * @param storeIds 归属集合（ATTR_PARTNER_STORE_IDS，恒非空）
     * @return {@code [{id, name, address, status, service}]} —— 出参白名单逐键重建，
     *         **不含 owner_user_id / contact_phone / photos / daily_capacity / accepting**
     *         （D-12 同口径；m18 前那几列还不存在，本来也不许出）
     */
    List<Map<String, Object>> stores(List<Long> storeIds);

    /**
     * 核销清单（{@code GET /api/partner/orders}）：明细行级，分页手写。
     *
     * @param pickupDate 可选 {@code yyyy-MM-dd} 过滤；为空 = 不限自提日期（无默认值、不回显）；
     *                   形状/真实日期不合 → 400
     * @param status     白名单 {@code all|pending_pickup|completed}；<b>非空且不在白名单 → 400</b>
     *                   （与商家侧「未知兜底 all」刻意不同：主理人拿的是核销台视图，静默放宽等于多给数据）；
     *                   null 与空串按默认值 {@code all}；三个取值都恒排除 cancelled
     * @param page       默认 1，{@code <1} 归一 1（夹而不拒）
     * @param size       默认 20，夹到 [1,50]（夹而不拒），生效值回显
     * @return {@code {list:[{orderId(后6位), dishName, quantity, price, storeId, storeName, pickupDate,
     *         status, payStatus, maskedPhone, createTime}], page, size, total, hasMore}}
     *         —— 无 pickupCode（D-13）/无 address/无 userId（RD）
     */
    Map<String, Object> orders(List<Long> storeIds, String pickupDate, String status, Integer page, Integer size);

    /**
     * 核销（{@code POST /api/partner/orders/verify}）：D-14 锁 → 码形状 → 定性预读 → D-6 四条件 UPDATE。
     *
     * @param userId     操作者（ATTR_USER_ID），D-14 锁按它计失败次数
     * @param storeIds   归属集合
     * @param pickupCode 用户口播的 6 位码
     * @return {@code {orderId(后6位), status:"completed", idempotent:bool}} ——
     *         不回显码本身；{@code idempotent=true} 表示这单此前已被核销（幂等成功，未写 ops_log）
     * @throws com.fresh.common.BizException 429（锁定期）、400（码非 6 位数字 / 已取消或未支付）、
     *                                       404（查无此码 / 非名下门店的单）
     */
    Map<String, Object> verify(Long userId, List<Long> storeIds, String pickupCode);
}
