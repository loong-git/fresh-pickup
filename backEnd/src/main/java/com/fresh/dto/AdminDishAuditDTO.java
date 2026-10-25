package com.fresh.dto;

import lombok.Data;

/**
 * 管理端商品内容审核入参（F-116 §2.2，{@code PUT /api/admin/dishes/{id}/audit}）：
 * body {@code {action: 'reject' | 'restore', reason?}}。
 *
 * <p>两种语义：驳回（reject + 必填理由 trim 后 1~200，置 {@code audit_status='rejected'}，
 * <b>不改 on_sale</b>——两列正交）/ 恢复（restore，置 {@code 'ok'} 并清理由，body 带 reason 忽略）。
 * action 非法与 reason 形状由 Service 按序判（同 F-112 AdminSeckillUpdateDTO 的姿势：
 * DTO 刻意不带校验注解，@Valid 的全局文案插不进契约的校验链）。
 */
@Data
public class AdminDishAuditDTO {

    /** 审核动作：reject（驳回）/ restore（恢复）；null 与其余值由 Service 判 400「action 仅允许 reject/restore」 */
    private String action;

    /** 驳回理由：reject 必填、trim 后 1~200 字符；restore 忽略（传了不生效） */
    private String reason;
}
