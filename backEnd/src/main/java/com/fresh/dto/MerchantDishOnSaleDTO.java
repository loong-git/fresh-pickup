package com.fresh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商家端上下架入参（F-13/G-02 Step 1，契约 F-13.2 表行 6）。
 * 用 Integer 不用 boolean：dish.on_sale 实际是 TINYINT、实体是 Integer onSale（init.sql:70），
 * 与 AdminDishUpdateDTO.onSale 同一套类型与文案，避免布尔↔tinyint 转换口径分叉。
 */
@Data
public class MerchantDishOnSaleDTO {

    /** 在售开关：1 上架 / 0 下架 */
    @NotNull(message = "请选择上架或下架")
    @Min(value = 0, message = "onSale 仅允许 0（下架）或 1（上架）")
    @Max(value = 1, message = "onSale 仅允许 0（下架）或 1（上架）")
    private Integer onSale;
}
