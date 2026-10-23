package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端新增搜索别名入参（F-110 §0.2，{@code POST /api/admin/aliases}）：
 * body {@code {alias, dishId}}。
 *
 * <p>{@code alias} trim 后必须非空且 ≤50（列宽 {@code VARCHAR(50)}）；{@code dishId} 必须指向
 * 真实存在的商品，但<b>不要求在售</b>——下架商品持有别名是合法状态，搜索命中后由既有下架过滤兜底。
 * 唯一键 {@code uk_alias(alias)} 冲突由 Service 转成 400「该别名已存在」。
 */
@Data
public class AdminAliasCreateDTO {

    /** 搜索别名（必填；服务端 trim 后入库） */
    @NotBlank(message = "别名不能为空")
    private String alias;

    /** 命中商品 id（必填；存在即可，不要求在售） */
    @NotNull(message = "商品不能为空")
    private Long dishId;
}
