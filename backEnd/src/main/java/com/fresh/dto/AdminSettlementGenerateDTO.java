package com.fresh.dto;

import lombok.Data;

/**
 * 生成账期入参（F-118 §1.1，{@code POST /api/admin/settlements/generate}）：
 * body {@code {merchantId, periodStart, periodEnd}}，日期 YYYY-MM-DD。
 * 校验（merchant 404 / start&lt;end 400 / 同期 uk 撞 400）全在 Service 内按序判——
 * DTO 不挂 Bean Validation 注解（照 AdminStoreUpdateDTO 同族先例：校验顺序即报错优先级，归 Service 一处真源）。
 */
@Data
public class AdminSettlementGenerateDTO {

    /** 商家ID：null/非正 → 400（Service 判）；不存在 → 404「商家不存在」 */
    private Long merchantId;

    /** 账期起 YYYY-MM-DD：形状或真日期非法 → 400 */
    private String periodStart;

    /** 账期止 YYYY-MM-DD：形状或真日期非法 → 400；与 start 相等或更早 → 400（start&lt;end） */
    private String periodEnd;
}
