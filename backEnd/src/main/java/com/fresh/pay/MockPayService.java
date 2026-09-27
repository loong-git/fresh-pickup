package com.fresh.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.InviteService;
import com.fresh.service.impl.OrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mock 支付实现（T-M2-05，契约 M2-5）：模拟收银台，直接置 pay_status=1 + pay_time。
 * - 幂等：已支付重复调用直接返回成功，不重复"入账"（条件更新 pay_status=0 → 1 保证并发只生效一次）；
 * - 本人订单校验：非本人（含 user_id 未认领存量单）一律 404，不泄露他人订单存在性；
 * - 已取消订单不可支付；
 * - 支付成功即按订单明细累加商品销量（T-M4-03 销量闭环，唯一累加点；取消已支付单时按同明细回退）；
 * - prod 由 pay.mock-enabled=false 在 PayController 层直接 404，本 Bean 不再单独按 profile 装配。
 */
@Slf4j
@Service
public class MockPayService implements PayService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private InviteService inviteService;

    @Override
    @Transactional
    public Order payResult(String orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getUserId() == null || !order.getUserId().equals(userId)) {
            throw new BizException(R.CODE_NOT_FOUND, "订单不存在");
        }
        // 幂等：已支付直接返回，不重复入账
        if (order.getPayStatus() != null && order.getPayStatus() == 1) {
            return order;
        }
        if (OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BizException("订单已取消，无法支付");
        }
        // 条件更新防并发双击：只有 pay_status=0 才置 1，影响行数 0 说明并发窗口内已被支付 → 幂等返回
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, 0)
                .set(Order::getPayStatus, 1)
                .set(Order::getPayTime, LocalDateTime.now()));
        if (updated > 0) {
            order.setPayStatus(1);
            order.setPayTime(LocalDateTime.now());
            // 销量闭环（T-M4-03）：支付成功即按明细累加 sold_count（下单不计销量，取消已支付单按同明细回退）；
            // 置态与累加同事务防"已支付未计销量"半态，行锁同时串行化取消链路的退款判定，未来 wx-notify 同点复用
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
            items.forEach(item -> dishMapper.increaseSoldCount(item.getDishId(), item.getQuantity()));
            // 邀请新人挂钩（F-01.2 / F-04.2；F-05 后现金轨唯一激励）：支付成功事务内调 InviteService.onOrderPaid——
            // 首单（已支付订单数==1）→ invite_relation 原子置位 0→1
            // + 冻结现金入账（cash_flow status 0→1 → 邀请人 balance+5，F-04.2 修订②注册只冻结不入账）
            inviteService.onOrderPaid(userId);
            log.info("Mock 支付成功: orderId={}, userId={}, amount={}", orderId, userId, order.getTotalPrice());
        } else {
            order = orderMapper.selectById(orderId);
        }
        return order;
    }
}
