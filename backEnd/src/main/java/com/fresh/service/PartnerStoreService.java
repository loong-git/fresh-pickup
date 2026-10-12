package com.fresh.service;

import com.fresh.dto.PartnerStoreUpdateDTO;

import java.util.List;
import java.util.Map;

/**
 * 主理人门店维护 + 到货批次服务（F-18.12 第 4~6 条）。
 *
 * <p>【到货确认只落一处，本类的选择】契约给了两个候选（新建本类 / 并入 PartnerOrderService）。
 * 本批实现把 arrival 两条端点收在<b>本类</b>，理由三条：
 * ① 端点语义就是「门店维度的读与写」——门店信息维护与到货批次都以 D-22 的门店选择器为入口，同一套归属解析；
 * ② {@code PartnerOrderService} 是 Step 3 已过 S3 门禁的交付物，G7 要断言它那三个端点行为不变，
 *    能不动就不动（少一个回归面）；
 * ③ 契约原文里 arrival 的第三种落点（新建 MerchantArrivalService）与「新建 PartnerStoreService」
 *    在文件数上等值，选后者可让「主理人管店」这件事只有一個入口类。
 *
 * <p>鉴权前提全在 AuthInterceptor 的 {@code /api/partner/} 分支（登录 + 归属集合非空 → 注入 ATTR_PARTNER_STORE_IDS），
 * 本接口不重复角色判定，但每个方法首行自守卫「集合为 null/空 → 403」，杜绝退化成无条件的全表读写（RF）。
 *
 * <p><b>D-23</b>：门店出参一律是本接口<b>重建的白名单 Map</b>，绝不返回 {@code Store} 实体——
 * 实体那六列（含 contactPhone/photos/accepting）全挂了 {@code @JsonIgnore}，是给匿名 {@code GET /api/stores} 用的；
 * 主理人侧要读它们，只能走这里的显式键集合。
 */
public interface PartnerStoreService {

    /**
     * 门店信息（{@code GET /api/partner/store}）。
     *
     * @param storeIds   归属集合（ATTR_PARTNER_STORE_IDS，恒非空）
     * @param storeIdArg D-22 选择器：{@code ?storeId=}，<b>仅用于在名下多店中选一店</b>；
     *                   不在集合内 → 404；null → 取集合里 id 最小的一家（缺省分支，演示库 user 1 只有 store 1）
     * @return {@code {id, name, address, service, status, lng, lat, openTime, closeTime, dailyCapacity,
     *         accepting, contactPhone, photos}} —— contactPhone 只以 {@code PhoneMask.mask} 结果出现（RD），
     *         不含 ownerUserId/createdAt
     */
    Map<String, Object> storeDetail(List<Long> storeIds, Long storeIdArg);

    /**
     * 门店维护（{@code PUT /api/partner/store}）：白名单只有 D-7 的七项，其余键忽略不生效。
     *
     * @return 与 {@link #storeDetail} 同形（写后回查，出参是落库后的权威值）；
     *         本次没有任何白名单字段要改时<b>不 UPDATE、不写 ops_log</b>，仍回当前视图（200）
     * @throws com.fresh.common.BizException 400 形状/取值域非法、404 门店不存在或不在名下
     */
    Map<String, Object> updateStore(Long userId, List<Long> storeIds, Long queryStoreId, PartnerStoreUpdateDTO dto);

    /**
     * 到货批次清单（{@code GET /api/partner/arrivals?date=}，缺省=<b>明日</b>，与 m18 演示种子同日）。
     *
     * @param date {@code yyyy-MM-dd}；形状或真日期不合 → 400
     * @return {@code {storeId, date, list:[{id, storeId, batchDate, status, confirmed, confirmTime, remark,
     *         items:[{id, batchId, dishId, dishName, expectedQty, actualQty}]}]}}
     *         —— {@code confirmed} 供前端出「当日批次未确认」警示条（D-9）
     */
    Map<String, Object> arrivals(List<Long> storeIds, Long storeIdArg, String date);

    /**
     * 到货确认（{@code POST /api/partner/arrivals/{id}/confirm}）：四条件 UPDATE（D-19/F-18.12 第 6 条）。
     *
     * @return {@code {id, status:"confirmed", confirmed:true, confirmTime, idempotent}}
     * @throws com.fresh.common.BizException 404 批次不存在或非名下门店（两者同码同文案）、
     *                                       400 批次已是 short（短少批次不可确认）
     */
    Map<String, Object> confirmArrival(Long userId, List<Long> storeIds, Long batchId);
}
