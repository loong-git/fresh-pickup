package com.fresh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端更新免费领活动入参（F-109 §0.2，{@code PUT /api/admin/free/activity}）：
 * body {@code {dishId?, dailyQuota?, threshold?, status?}}，<b>部分更新</b>——null 表示本次不改该列。
 *
 * <p><b>本 DTO 刻意只有这四列</b>：{@code daily_claimed}/{@code daily_date} 是领取链路的运行时计数器，
 * 由 {@code FreeActivityMapper#tryClaimQuota} 原子维护；管理端一旦能写它们就是超发/白送的口子，
 * 所以这里根本没有对应字段——请求体里塞了也只会被 Jackson 当未知字段忽略（Spring Boot 默认
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}），落库路径上更不存在写这两列的分支。
 */
@Data
public class AdminFreeActivityUpdateDTO {

    /** 免费商品 ID（可空 = 不改）：须存在且 {@code on_sale=1}，否则 400 */
    private Long dishId;

    /** 每日限量（可空 = 不改）：≥0，0 等效暂停 */
    @Min(value = 0, message = "每日限量不能为负数")
    private Integer dailyQuota;

    /** 满额门槛（可空 = 不改）：≥0 */
    @DecimalMin(value = "0", message = "满额门槛不能为负数")
    private BigDecimal threshold;

    /** 活动状态（可空 = 不改）：仅 online / offline（常量在 FreeActivity.STATUS_ONLINE/OFFLINE） */
    private String status;
}
