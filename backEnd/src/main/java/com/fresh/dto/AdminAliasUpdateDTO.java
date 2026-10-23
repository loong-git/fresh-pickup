package com.fresh.dto;

import lombok.Data;

/**
 * 管理端更新搜索别名入参（F-110 §0.2，{@code PUT /api/admin/aliases/{id}}）：
 * body {@code {alias?, dishId?}}，<b>部分更新</b>——null/缺键表示本次不改该列。
 *
 * <p>{@code alias} 传值时 trim 后必须非空且 ≤50（空白串 400，不当作「清空」）；
 * {@code dishId} 传值时须存在，但<b>不要求在售</b>。两个键都不传 → 400「无更新字段」。
 */
@Data
public class AdminAliasUpdateDTO {

    /** 搜索别名（可空 = 不改；传空白串仍 400） */
    private String alias;

    /** 命中商品 id（可空 = 不改；存在即可，不要求在售） */
    private Long dishId;
}
