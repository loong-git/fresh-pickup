package com.fresh.dto;

import lombok.Data;

/**
 * 运营端封禁/解封入参（F-122 §3.4，{@code PUT /api/admin/users/{id}/status} 与
 * {@code PUT /api/admin/merchants/{id}/status} 共用）：body {@code {action}}。
 *
 * <p>action 仅允许 ban/unban，白名单在 Service 判（照 AdminDishAuditDTO 的姿势：DTO 刻意不带
 * 校验注解，@Valid 的全局文案插不进契约的校验链）；null/缺失同样由 Service 判 400，不让 NPE。
 */
@Data
public class AdminBanActionDTO {

    /** 封禁动作：ban（封禁）/ unban（解封）；null 与其余值由 Service 判 400「action 仅允许 ban/unban」 */
    private String action;
}
