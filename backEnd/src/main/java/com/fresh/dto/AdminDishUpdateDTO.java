package com.fresh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端商品更新入参（T-M3-09 薄版）：body {price?, stock?, onSale?} 部分更新——
 * 三个字段均可选，仅更新传入（非 null）的字段；全部未传在 Service 层拒绝（400 无更新字段）。
 */
@Data
public class AdminDishUpdateDTO {

    /** 新单价（可选） */
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    /** 新库存（可选） */
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 在售开关（可选）：1 上架 / 0 下架 */
    @Min(value = 0, message = "onSale 仅允许 0（下架）或 1（上架）")
    @Max(value = 1, message = "onSale 仅允许 0（下架）或 1（上架）")
    private Integer onSale;
}
