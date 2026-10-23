package com.fresh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端更新字典分组入参（F-108 §0.7，{@code PUT /api/admin/dict/groups/{code}}）：
 * body {@code {name?, enabled?}}，null 表示本次不改该列；全部省略由 Service 回 400「无更新字段」。
 *
 * <p>{@code enabled} 只有 0/1 两态（0=停用，{@code GET /api/dict} 按空返回且不回退上游；1=启用）。
 */
@Data
public class AdminDictGroupUpdateDTO {

    /** 新中文名（可空 = 不改；空白串由 Service 回 400） */
    @Size(max = 50, message = "分组名称最长 50 个字符")
    private String name;

    /** 启停开关（可空 = 不改）：1 启用 / 0 停用 */
    @Min(value = 0, message = "enabled 仅允许 0（停用）或 1（启用）")
    @Max(value = 1, message = "enabled 仅允许 0（停用）或 1（启用）")
    private Integer enabled;
}
