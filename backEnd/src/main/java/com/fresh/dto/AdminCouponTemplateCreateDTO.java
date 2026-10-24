package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端券模板上新入参（F-113 §4，{@code POST /api/admin/coupon-templates}）：
 * body {@code {couponKey, title?, threshold, amount, validDays, limitPerUser, totalQuota?, status}}。
 *
 * <p>DTO 刻意不带校验注解：契约 §4 钉死校验顺序（key 形状 → key 冲突 → title 长度 →
 * 金额形状 → amount&lt;threshold → validDays → limitPerUser → totalQuota → status），
 * 全部在 Service 内按序判，@Valid 的全局文案插不进这条链（同 F-110/F-112 的姿势）。
 */
@Data
public class AdminCouponTemplateCreateDTO {

    /** 券模板标识：^[a-z0-9_]{2,50}$，且不与现存冲突（冲突 400「key 已存在」） */
    private String couponKey;

    /** 展示名：可空（NULL 时前端按满X减Y拼）；≤50 字符 */
    private String title;

    /** 使用门槛：&gt;0、最多两位小数 */
    private BigDecimal threshold;

    /** 抵扣金额：&gt;0、最多两位小数，且必须 &lt; threshold（抵扣≥门槛=负价单） */
    private BigDecimal amount;

    /** 领取后有效天数：1~365 */
    private Integer validDays;

    /** 每人全史限领：≥0（0=不限） */
    private Integer limitPerUser;

    /** 总量配额：NULL=不限；传则 ≥0 */
    private Integer totalQuota;

    /** 状态：active / disabled */
    private String status;
}
