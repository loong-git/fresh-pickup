package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderId;
    private Long dishId;
    private String dishName;
    private BigDecimal price;
    private Integer quantity;
    private Long merchantId;
    /** 供货价快照（F-117/m27）：下单时自 dish.supply_price 落库（与成交价快照同模式；dish 侧 NULL 则快照 NULL）；
     *  settle_id（m27 同批列）本片零写路径（F-118 跑批才写），故实体不映射——照 fulfill_status「列先建、实体后映射」先例；
     *  @JsonIgnore 封 C 端订单明细出参——供货价是平台-商家核算维度，不随 C 端列表/详情下发 */
    @JsonIgnore
    private BigDecimal supplyPrice;
}
