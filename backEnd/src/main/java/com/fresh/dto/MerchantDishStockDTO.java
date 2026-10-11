package com.fresh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商家端库存增量入参（F-13/G-02 Step 1，契约 F-13.2 表行 5）：body {delta}，正数补库、负数减库。
 * 类型钉死为 Integer 且区间 [-1000,1000]——这是服务端能把 delta 安全拼进 `stock = stock + (n)` 的前提
 * （R7' 增量 UPDATE；拼进去的只有数字与可选负号，不存在任何字符串入参，见 MerchantDishServiceImpl#adjustStock）。
 */
@Data
public class MerchantDishStockDTO {

    @NotNull(message = "请填写库存调整数量")
    @Min(value = -1000, message = "单次库存调整不能超过 ±1000")
    @Max(value = 1000, message = "单次库存调整不能超过 ±1000")
    private Integer delta;
}
