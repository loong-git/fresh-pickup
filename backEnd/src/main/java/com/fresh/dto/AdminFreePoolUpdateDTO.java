package com.fresh.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理端全量替换免费领候选池入参（F-109 §0.2，{@code PUT /api/admin/free/pool}）：
 * body {@code {dishIds:[...]}}。非空、去重、每个商品存在且 {@code on_sale=1}，否则 400；
 * 全量替换（同事务删旧插新）。空列表不允许——C 端无候选可选会整页置灰。
 */
@Data
public class AdminFreePoolUpdateDTO {

    /** 候选商品 ID 列表（保持顺序，服务端去重后按此顺序插入=展示顺序） */
    private List<Long> dishIds;
}
