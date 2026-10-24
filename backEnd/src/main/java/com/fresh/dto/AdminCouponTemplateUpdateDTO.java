package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端券模板部分更新入参（F-113 §4，{@code PUT /api/admin/coupon-templates/{id}}）：
 * body {@code {title?, threshold?, amount?, validDays?, limitPerUser?, totalQuota?, status?}}——
 * null/缺键 = 本次不改；七列全空 400。
 *
 * <p><b>coupon_key 与 issued_count 不可改</b>（契约 §4：已发过的模板改 key = 历史
 * user_coupon.coupon_key 悬挂；issued_count 是配额原子扣减的运行时计数，运营改它=凭空虚增/虚减配额）。
 * DTO 仍带这两个<b>探测字段</b>：body 传了不落库，由 Controller 在响应 message 里注明已忽略
 * （「带了忽略并在响应注明」），让运营端 UI 不必前置隐藏、后端始终是白名单。
 *
 * <p>校验同 POST 但对<b>合并后的新值</b>判（threshold 与 amount 可能只改其一），
 * 全部在 Service 内按序判（同 Create 的姿势）。
 */
@Data
public class AdminCouponTemplateUpdateDTO {

    /** 探测字段：传了也不落库（key 不可改），仅用于响应注明已忽略 */
    private String couponKey;

    /** 探测字段：传了也不落库（运行时计数不可改），仅用于响应注明已忽略 */
    private Integer issuedCount;

    /** 展示名：null=不改；传则 ≤50 字符（空串=清空展示名，回「满X减Y」拼装） */
    private String title;

    /** 使用门槛：null=不改；传则 &gt;0、最多两位小数 */
    private BigDecimal threshold;

    /** 抵扣金额：null=不改；传则 &gt;0、最多两位小数，且合并后必须 &lt; 合并后门槛 */
    private BigDecimal amount;

    /** 领取后有效天数：null=不改；传则 1~365 */
    private Integer validDays;

    /** 每人全史限领：null=不改；传则 ≥0（0=不限） */
    private Integer limitPerUser;

    /** 总量配额：null=不改；传则 ≥0。「改回 NULL=不限」契约未开口子（Jackson null 与缺键不分）——
     *  要停发走 status=disabled 或 totalQuota=0，要恢复不限只能新建模板，回填四档的 NULL 是存量语义 */
    private Integer totalQuota;

    /** 状态：null=不改；传则 active / disabled */
    private String status;
}
