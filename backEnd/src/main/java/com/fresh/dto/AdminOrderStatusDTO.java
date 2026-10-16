package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端订单状态更新入参（T-M3-09 薄版 → F-61 加取消理由）：body {@code {status, reason?}}，
 * 仅允许 completed / cancelled，取值白名单在 AdminServiceImpl 内校验（注解只能表达"非空"，
 * 无法表达业务白名单语义）。
 *
 * <p><b>reason 为什么是"注解选填、服务层按 status 强制"而不是一枚 @NotBlank</b>：
 * 核销（completed）是现场履约收尾，运营点下去时货已经在顾客手里，逼他编一句话只会得到"核销"两个字；
 * 取消（cancelled）是单方面终止一笔已付款的交易，必须留原因。同一个字段承载两种强度，
 * 所以形状层只封顶长度，"取消必填"这条业务判据落在 {@code AdminServiceImpl#updateOrderStatus}。
 *
 * <p><b>上限 255 与入驻审核意见同值</b>（{@code auditStoreApply} 那句「审核意见不能超过 255 字」），
 * 不另立一套长度口径；两个数不同源的话，早晚会出现"一处能存下、另一处存不下"的同名不同命。
 */
@Data
public class AdminOrderStatusDTO {

    @NotBlank(message = "status 不能为空")
    private String status;

    /** 取消理由（F-61）：status=cancelled 时必填，由服务层判；completed 时可空，空则审计里不落这一键 */
    @Size(max = 255, message = "取消原因不能超过 255 字")
    private String reason;
}
