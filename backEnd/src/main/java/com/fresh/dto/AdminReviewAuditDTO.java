package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理端评价审核入参：body {action}，仅允许 approve（过审展示）/ reject（驳回删除），
 * 取值白名单在 AdminServiceImpl 内校验（注解只能表达"非空"，无法表达业务白名单语义）。
 */
@Data
public class AdminReviewAuditDTO {

    @NotBlank(message = "action 不能为空")
    private String action;
}
