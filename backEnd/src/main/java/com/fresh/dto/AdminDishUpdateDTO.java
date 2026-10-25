package com.fresh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端商品更新入参（T-M3-09 薄版）：body {price?, stock?, onSale?, supplyPrice?} 部分更新——
 * price/stock/onSale 三字段可选，仅更新传入（非 null）的字段；全部未传在 Service 层拒绝（400 无更新字段）。
 * supplyPrice（F-117）三态语义由「键是否出现在 JSON」区分（Jackson 标准行为：显式 null 也调 setter、
 * 键缺失不调）：显式 null=清除回 NULL（运营编辑弹窗「留空=清除」），带值=设置（>0 两位小数），
 * 键缺失=不参与本次更新（既有调用方/历史脚本不带该键零影响——部分更新语义对它们保持不变）。
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

    /** 供货价（F-117，可选）：null=清除回 NULL（不进结算聚合），合法值 >0 两位小数（DECIMAL(10,2) 列口径）；
     *  键缺失时 present=false 不参与 SET（照 price/stock/onSale 的部分更新口径） */
    @DecimalMin(value = "0.01", message = "供货价必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "供货价最多两位小数")
    private BigDecimal supplyPrice;

    /** JSON 里是否出现 supplyPrice 键（见类注释三态语义）；显式 null 也会置 true */
    private boolean supplyPricePresent;

    /** 手写 setter 捕捉「键出现过」（含显式 null）——lombok 对已手写的 setter 不再生成 */
    public void setSupplyPrice(BigDecimal supplyPrice) {
        this.supplyPrice = supplyPrice;
        this.supplyPricePresent = true;
    }
}
