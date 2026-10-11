package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 当日实付累计（M8 免费领门槛口径）：该用户当日创建且已支付（pay_status=1）订单的 total_price SUM，
     * 免费单（client_request_id LIKE 'free-%'，0 元单）不计入门槛；IFNULL 兜住无单返回 0。
     */
    @Select("SELECT IFNULL(SUM(total_price), 0) FROM orders "
            + "WHERE user_id = #{userId} AND pay_status = 1 "
            + "AND client_request_id NOT LIKE 'free-%' AND DATE(create_time) = #{today}")
    BigDecimal sumTodayPaid(@Param("userId") Long userId, @Param("today") LocalDate today);

    /**
     * 取货码占用探测（F-18.6/D-3）：随机 6 位码写入前先读一次是否已被他人占用，命中即换码重试——
     * 把撞 uk_pickup_code 从「写进去抛 1062、脏了整个支付事务」前移成一次普通读。
     * 表名/参数写法照 OrderItemMapper.countByUserIdAndDishId 的既有形态（COUNT(*) 回 int）。
     */
    @Select("SELECT COUNT(*) FROM orders WHERE pickup_code = #{code}")
    int countByPickupCode(@Param("code") String code);

    /**
     * 取货码挂载（F-18.6/D-3，只在 MockPayService 支付成功事务内调用）：
     * 条件必须带 {@code pickup_code IS NULL} —— 这是幂等的全部依据：重复支付、并发二次置态都不会换码
     * （D-4 核销幂等判定要靠这个码稳定）；影响行数 0 由上层重试、5 次仍失败则抛 BizException 回滚整单支付。
     */
    @Update("UPDATE orders SET pickup_code = #{code} WHERE id = #{orderId} AND pickup_code IS NULL")
    int attachPickupCode(@Param("orderId") String orderId, @Param("code") String code);
}
