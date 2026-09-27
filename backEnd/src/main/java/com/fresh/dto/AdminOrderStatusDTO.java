package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理端订单状态更新入参（T-M3-09 薄版）：body {status}，仅允许 completed / cancelled，
 * 取值白名单在 AdminServiceImpl 内校验（注解只能表达"非空"，无法表达业务白名单语义）。
 */
@Data
public class AdminOrderStatusDTO {

    @NotBlank(message = "status 不能为空")
    private String status;
}
