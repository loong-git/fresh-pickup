package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 搜索别名 dish_alias（F-110）：{@code id / alias VARCHAR(50) / dish_id}，
 * {@code UNIQUE KEY uk_alias(alias)} 保证一个别名只指向一个商品，{@code KEY idx_dish(dish_id)}。
 *
 * <p>语义边界：别名只影响 C 端搜索命中（{@code DishMapper#selectDishIdsByAlias} 精确等值），
 * 不参与价格/库存/订单；下架商品持有别名是合法状态（命中后仍走既有下架过滤）。
 */
@Data
@TableName("dish_alias")
public class DishAlias {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 搜索别名（trim 后入库，最长 50 字符；uk_alias 唯一） */
    private String alias;

    /** 命中商品 id（逻辑外键 dish.id；存在即可，不要求在售） */
    private Long dishId;
}
