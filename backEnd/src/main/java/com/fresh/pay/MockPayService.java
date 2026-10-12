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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Mock 支付实现（T-M2-05，契约 M2-5）：模拟收银台，直接置 pay_status=1 + pay_time。
 * - 幂等：已支付重复调用直接返回成功，不重复"入账"（条件更新 pay_status=0 → 1 保证并发只生效一次）；
 * - 本人订单校验：非本人（含 user_id 未认领存量单）一律 404，不泄露他人订单存在性；
 * - 已取消订单不可支付；
 * - 支付成功即按订单明细累加商品销量（T-M4-03 销量闭环，唯一累加点；取消已支付单时按同明细回退）；
 * - 取货码（F-18.6/D-3）：支付成功事务内生成 6 位数字码（attachPickupCode），与置态、销量累加同事务，
 *   生成失败即抛 BizException 让整单支付回滚，杜绝「已支付无码」半态；免费领 0 元单经
 *   FreeServiceImpl:241 复用本方法自动获得取货码，不另写生成逻辑。真实支付转正后（WxPayService 现为骨架、
 *   无实体置态代码，本期零改动）在同一个 if (updated > 0) 支付成功点做对偶挂钩；
 * - prod 由 pay.mock-enabled=false 在 PayController 层直接 404，本 Bean 不再单独按 profile 装配。
 */
@Slf4j
@Service
public class MockPayService implements PayService {

    /** 取货码随机源（D-4）：SecureRandom 而非 Random——码只有 1e6 空间且核销只靠它+归属判定，可预测即等于可枚举他人单 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            // 取货码生成（F-18.6/D-3）：与上面的置态+销量累加同事务、同一 if (updated > 0) 支付成功点，
            // 幂等由 OrderMapper.attachPickupCode 的 `pickup_code IS NULL` 条件兜住（二次支付不换码）；
            // 失败抛 BizException → 本事务整体回滚，不出现「已支付无码」半态。
            // S3-0（F-18.7 第 3 条裁定的收口）：生成成功要把码回写进**内存里即将返回的那个 order 对象**，
            // 否则 mock-pay / free/claim 的即时响应 data.pickupCode 恒为 null（库里已有码），
            // 与 F-18.6 承诺的「C 端拿到码」不一致——C 端首屏读的正是这条响应。
            String pickupCode = attachPickupCode(orderId);
            order.setPickupCode(pickupCode);
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

    /**
     * 取货码挂载（算法照 F-18.6 第 3 条原文，D-3/D-4）：6 位数字、最多重试 5 次；
     * 先用 countByPickupCode 避开已占用的码（撞唯一键的概率前移成一次读，不把 1062 打进事务），
     * 再走条件 UPDATE（WHERE id=#{orderId} AND pickup_code IS NULL）幂等写入——已支付单二次调用不会走到这里
     * （payResult 开头早退 + else 回读两条路径都不重新生成），条件本身也保证并发二次置态不换码。
     * 禁止用订单号尾 6 位替代：可由单号推出 = 可猜。
     *
     * @return 实际写进库的那 6 位码（S3-0：调用方把它回写到内存 order，让 mock-pay / free/claim
     *         的即时响应与库里一致）
     */
    private String attachPickupCode(String orderId) {
        for (int i = 0; i < 5; i++) {
            String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
            // BE-6（F-18.20 F 行）：countByPickupCode 与条件 UPDATE 之间仍可能被并发单抢先占码——
            // 真撞 uk_pickup_code(1062/DuplicateKeyException) 时继续下一轮重试（D-3 的 5 次重试自此才真正兜得住），
            // 而不是让未捕获的 DuplicateKeyException 把整笔支付打成 500。1062 只回滚该语句，事务本身可继续。
            try {
                if (orderMapper.countByPickupCode(code) > 0) { continue; }
                if (orderMapper.attachPickupCode(orderId, code) > 0) { return code; }
            } catch (DuplicateKeyException e) {
                // 码值本身不进日志（与 ops_log 同口径：pickupCode 属核销凭据）
                log.warn("取货码撞 uk_pickup_code，换码重试: orderId={}, attempt={}", orderId, i + 1);
            }
        }
        throw new BizException("取货码生成失败，请稍后重试");
    }
}
