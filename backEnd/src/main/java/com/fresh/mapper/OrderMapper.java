package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
