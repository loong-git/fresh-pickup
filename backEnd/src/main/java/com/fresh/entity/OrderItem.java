package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
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
}
