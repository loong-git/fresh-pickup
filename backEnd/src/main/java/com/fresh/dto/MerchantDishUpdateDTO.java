package com.fresh.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商家端编辑商品入参（F-13/G-02 Step 1，契约 F-13.2 表行 4：字段同新增、全部选填做部分更新）。
 * 与 MerchantDishCreateDTO 同白名单，R5' 的四列（seckill/seckillPrice/soldCount/goodRate）与
 * 归属/主键（merchantId/id）同样不存在于本类——归属只从 @RequestAttribute 来（R2/R3）。
 * 各字段上限与新建同口径（列宽为准），差别只在「不校验必填」：未传（null）的列本次不动。
 */
@Data
public class MerchantDishUpdateDTO {

    @Size(max = 100, message = "商品名称不能超过 100 个字")
    private String name;

    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "价格最多 8 位整数、2 位小数")
    private BigDecimal price;

    @Size(max = 255, message = "图片地址过长")
    private String image;

    @Size(max = 2000, message = "描述不能超过 2000 个字")
    private String description;

    @Size(max = 20, message = "分类取值过长")
    private String category;

    @Size(max = 20, message = "单位不能超过 20 个字")
    private String unit;

    /**
     * 库存：本字段存在只为把误用引到正确端点，编辑接口绝不拿它精确 set（R7'：商家端只开增量、
     * 精确 set 库存一期归 Admin）。传了非 null 值在 MerchantDishService#update 里判 400——
     * 刻意不做「静默忽略」，那会让调用方以为改成功（同 F-13 决策①批判的静默假成功反模式）。
     */
    @Min(value = 0, message = "库存不能为负数")
    @Max(value = 99999, message = "库存不能超过 99999")
    private Integer stock;

    @Size(max = 50, message = "表情符号过长")
    private String emoji;

    @Size(max = 20, message = "背景色值过长")
    private String bgColor;

    @Size(max = 5, message = "标签最多 5 个")
    private List<@Size(max = 12, message = "单个标签不能超过 12 个字") String> tags;

    @Min(value = 1, message = "限购数量至少为 1")
    @Max(value = 20, message = "限购数量不能超过 20")
    private Integer limitBuy;
}
