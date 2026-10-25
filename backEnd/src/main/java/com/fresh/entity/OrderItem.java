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
     *  @JsonIgnore 封 C 端订单明细出参——供货价是平台-商家核算维度，不随 C 端列表/详情下发 */
    @JsonIgnore
    private BigDecimal supplyPrice;
    /** 归属结算单（F-118 启用写路径，m27 建列；F-117 片「实体不映射」的临时态随本片到期）：
     *  唯一写入口=AdminServiceImpl#generateSettlement 的挂锚条件 UPDATE（OrderItemMapper#attachSettle，
     *  06 册 §4.5「影响行数=候选数才成功」幂等模型），NULL=未结算；
     *  @JsonIgnore 封 C 端出参——内部结算锚，与 supplyPrice 同一处置 */
    @JsonIgnore
    private Long settleId;
}
