package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.AdminService;
import com.fresh.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 管理端薄版服务实现（T-M3-09）：
 * - 订单状态迁移沿用用户端同一套状态机条件 UPDATE（只允许从 pending_pickup 迁出），
 *   重复核销/重复取消幂等返回；置 cancelled 时与用户取消语义一致回补库存（防库存永久少记）。
 * - 商品部分更新：仅 set DTO 中非 null 字段，配合 @Valid 范围校验。
 */
@Slf4j
@Service
public class AdminServiceImpl implements AdminService {

    /** T-M3-09：管理端仅允许置为这两种状态（核销 completed / 取消 cancelled），不可回退待自提 */
    private static final Set<String> ALLOWED_TARGETS = Set.of(
            OrderServiceImpl.STATUS_COMPLETED, OrderServiceImpl.STATUS_CANCELLED);

    /** T-M4-05 收口：评价审核动作白名单（approve 过审展示 / reject 驳回删除） */
    private static final Set<String> ALLOWED_AUDIT_ACTIONS = Set.of("approve", "reject");

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private CouponService couponService;
    @Autowired
    private ReviewMapper reviewMapper;

    @Override
    @Transactional
    public void updateOrderStatus(String orderId, String status) {
        String target = status == null ? "" : status.trim();
        if (!ALLOWED_TARGETS.contains(target)) {
            throw new BizException("status 仅允许 completed（核销）或 cancelled（取消）");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(404, "订单不存在");
        }
        if (target.equals(order.getStatus())) {
            return; // 幂等：已是目标状态（重复核销/重复取消）直接成功
        }
        // 状态机条件更新：仅 pending_pickup 可迁出，并发双击/重复提交只有一次生效
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderServiceImpl.STATUS_PENDING_PICKUP)
                .set(Order::getStatus, target));
        if (updated == 0) {
            throw new BizException("当前状态不可变更（仅待自提订单可核销/取消）");
        }
        // 取消与用户端取消（OrderServiceImpl#cancelOrder）语义一致：回补库存 + 回补优惠券（T-M4-02），
        // 保证只回补一次
        if (OrderServiceImpl.STATUS_CANCELLED.equals(target)) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
            items.forEach(item -> dishMapper.increaseStock(item.getDishId(), item.getQuantity()));
            // 已支付单取消 → 置 pay_status=2（已退款）并按明细回退销量，与用户端取消同口径：条件更新 1→2
            // 仅一次生效（0 行即未支付取消，不动 pay_status 也不回退销量）；mock 收银台无真实资金流，仅补状态语义
            boolean refunded = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getId, orderId)
                    .eq(Order::getPayStatus, 1)
                    .set(Order::getPayStatus, 2)) > 0;
            if (refunded) {
                items.forEach(item -> dishMapper.decrementSoldCount(item.getDishId(), item.getQuantity()));
            }
            couponService.restoreByOrderId(orderId);
            log.info("管理端取消订单并回补库存: orderId={}, items={}, refunded={}", orderId, items.size(), refunded);
        }
        log.info("管理端更新订单状态: orderId={}, {} -> {}", orderId, order.getStatus(), target);
    }

    @Override
    public void updateDish(Long dishId, AdminDishUpdateDTO dto) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BizException(404, "菜品不存在");
        }
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>().eq(Dish::getId, dishId);
        int setCount = 0;
        if (dto.getPrice() != null) {
            wrapper.set(Dish::getPrice, dto.getPrice());
            setCount++;
        }
        if (dto.getStock() != null) {
            wrapper.set(Dish::getStock, dto.getStock());
            setCount++;
        }
        if (dto.getOnSale() != null) {
            wrapper.set(Dish::getOnSale, dto.getOnSale());
            setCount++;
        }
        if (setCount == 0) {
            throw new BizException("无更新字段（可传 price / stock / onSale）");
        }
        dishMapper.update(null, wrapper);
        log.info("管理端更新商品: dishId={}, price={}, stock={}, onSale={}",
                dishId, dto.getPrice(), dto.getStock(), dto.getOnSale());
    }

    @Override
    public void auditReview(Long reviewId, String action) {
        String op = action == null ? "" : action.trim();
        if (!ALLOWED_AUDIT_ACTIONS.contains(op)) {
            throw new BizException("action 仅允许 approve（过审展示）或 reject（驳回删除）");
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException(404, "评价不存在");
        }
        if ("approve".equals(op)) {
            // 过审：audit_status 置 NULL 恢复展示（set 传 null 会拼 SET audit_status=NULL，
            // 与 ReviewServiceImpl 列表过滤的 IS NULL 判定配对）；按 id 覆盖写，
            // 对已过审（NULL）重复执行结果不变，天然幂等
            reviewMapper.update(null, new LambdaUpdateWrapper<Review>()
                    .eq(Review::getId, reviewId)
                    .set(Review::getAuditStatus, null));
            log.info("管理端过审评价: reviewId={}, dishId={}", reviewId, review.getDishId());
        } else {
            // 驳回：删除记录（避免违规内容留存）；重复执行因查无此评价返回 404，语义明确
            reviewMapper.deleteById(reviewId);
            log.info("管理端驳回删除评价: reviewId={}, dishId={}", reviewId, review.getDishId());
        }
    }
}
