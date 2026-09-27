package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品表 dish。T-M4-03 字段迁移：秒杀/销量/限购/标签/好评率六列后端真实存储直出，
 * 前端 decorateDish 的合成逻辑删除；tags 列存 JSON 数组串，经 JacksonTypeHandler 映射字符串数组。
 */
@Data
@TableName(value = "dish", autoResultMap = true) // tags 走 JacksonTypeHandler，查询需 autoResultMap 回映射
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private BigDecimal price;
    private String image;
    private String description;
    private String category;
    private String unit;
    private Integer stock;
    /** 是否在售（T-M3-09）：1 上架 / 0 下架，管理端可改 */
    private Integer onSale;
    /** 秒杀标记（T-M4-03）：1 秒杀 / 0 普通；种子按旧前端合成规则 id%3==0 迁移 */
    private Integer seckill;
    /** 秒杀价（T-M4-03）：种子 ROUND(price*0.78,2)，非秒杀为 null */
    private BigDecimal seckillPrice;
    /** 销量（T-M4-03）：前端进度条按 soldCount/(soldCount+stock) 真实比例渲染 */
    private Integer soldCount;
    /** 限购数量（T-M4-03）：种子 [2,5,8] 按 id 循环，下单校验以此为准 */
    private Integer limitBuy;
    /** 卖点标签（T-M4-03）：种子按分类写死，DB 存 JSON 数组串、接口直出字符串数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;
    /** 好评率文案（T-M4-03）：如 94.4% */
    private String goodRate;
    private String emoji;
    private String bgColor;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
