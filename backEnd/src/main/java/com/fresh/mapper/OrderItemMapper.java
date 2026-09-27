package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 购买关系校验（M6）：统计本人订单（任意状态）中指定菜品的明细条数；
     * 归属依据 orders.user_id（order_item 无 user_id，须 JOIN），>0 即存在购买记录
     */
    @Select("SELECT COUNT(*) FROM order_item oi JOIN orders o ON oi.order_id = o.id "
            + "WHERE o.user_id = #{userId} AND oi.dish_id = #{dishId}")
    int countByUserIdAndDishId(@Param("userId") Long userId, @Param("dishId") Long dishId);
}
