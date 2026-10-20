package com.fresh.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.InviteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 支付成功结算接缝（F-21.3 Step 1 / D-21-1）：本工程里「支付成功」这件事的**唯一落点**。
 *
 * 来源与时序（逻辑逐字搬自 {@code MockPayService#payResult} 的支付成功分支，未作任何行为改写）：
 * 条件置态 pay_status=0→1（+ pay_time）→ 明细累加 sold_count → 取货码挂载并回写内存 order →
 * 邀请新人挂钩 → 日志；updated==0 分支只回读返回、不重复任何副作用。
 * 调用方：{@link MockPayService#payResult}（mock 渠道，现网唯一支付通道）与 FreeServiceImpl:250
 * （免费领 0 元单直调本方法，F-21.5 Step 2a 起不再经 {@code PayService} 渠道接口）；
 * 未来微信支付转正后由 wx-notify 回调链路（验签/解密/三比对通过的那个事务里）调同一方法（D-21-1 的对偶挂钩），
 * 渠道侧只负责「能不能认定这笔支付成功」的判定与鉴权，入账动作全部收在这一处。
 *
 * 事务边界（F-21.3 第 2 条钉死）：本类**不标** {@code @Transactional}。事务入口归调用方——
 * mock 是 {@code MockPayService#payResult}、免费领 0 元单是 {@code FreeServiceImpl#claim}（Step 2a 起直调）、
 * 未来回调是它自己的 Service 方法；接缝若自标事务注解，
 * 被已在事务中的调用方复用时会与调用方边界混叠（传播语义漂移），故置态/销量/取货码/邀请入账
 * 的「同生同死」由各调用方的事务保证，本类只保证「要么全做到、要么由调用方回滚」。
 *
 * 幂等依据（与抽取前一致，两处叠加）：① 条件 UPDATE 只在 pay_status=0 时生效，并发/重复调用的
 * 二次进来影响行数=0 → 走回读分支，四件副作用一件都不做（不加销量、不换码、不入邀请账）；
 * ② 取货码写入带 {@code pickup_code IS NULL} 条件（OrderMapper.attachPickupCode），并发二次置态不换码。
 *
 * 失败即整单回滚（F-18.6/D-3）：取货码 5 次仍生成不出来时抛 BizException，调用方事务整体回滚，
 * 杜绝「已支付无码」半态；免费领 0 元单经 FreeServiceImpl:250 直调本方法（{@code settle}）复用同一条链路自动获得取货码。
 *
 * 入参约束：{@code order} 必须是调用方**已加载并完成归属/可支付校验**的那一行（本类不做鉴权，
 * 「非本人 404」「已取消拒」留在各渠道入口）；置态成功后就地回写这个对象并原样返回，
 * 让 mock-pay / free/claim 的即时响应 data.pickupCode 与库里一致（S3-0 的教训）。
 */
@Slf4j
@Component
public class PaymentSettlement {

    /** 取货码随机源（D-4）：SecureRandom 而非 Random——码只有 1e6 空间且核销只靠它+归属判定，可预测即等于可枚举他人单 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DishMapper dishMapper;
    private final InviteService inviteService;

    /** 构造器注入：四个依赖全部 mock 友好（PaymentSettlementTest 直接 new，不连库、不起 Spring 容器） */
    public PaymentSettlement(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                             DishMapper dishMapper, InviteService inviteService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.dishMapper = dishMapper;
        this.inviteService = inviteService;
    }

    /**
     * 结算一笔支付成功（原 {@code MockPayService#payResult} 里的支付成功整段）。
     *
     * @param order   调用方已加载并校验过（本人 / 未支付 / 未取消）的订单行
     * @param payTime 支付成功时间：方法入口先归一到整秒（{@code truncatedTo(ChronoUnit.SECONDS)}），
     *                落库（orders.pay_time）与回写内存 order 用的是**同一枚归一后的值**，故响应与库逐字同值。
     *                归一是必需的：{@code orders.pay_time} 是 {@code DATETIME(0)}，而 MySQL 对小数秒是四舍五入
     *                （L-21-3：带纳秒的 now() 直接落库会出现「响应说 11:28:47.675、库里存 11:28:48」）
     * @return 支付成功后的订单：{@code updated>0} 返回入参 order 本身（已回写 payStatus/payTime/pickupCode）；
     *         {@code updated==0}（并发窗口内已被支付）回读库返回，不重复任何副作用
     */
    public Order settle(Order order, LocalDateTime payTime) {
        // 精度归一（L-21-3）：orders.pay_time 是 DATETIME(0)，而 MySQL 对小数秒是「四舍五入」不是截断
        // （实测 CAST('...:47.675' AS DATETIME)=48、'...:47.4'=47），调用方传的 LocalDateTime.now() 带纳秒，
        // 不归一就会出现「响应 47.675 / 库 48」这种同一事实两个表示互相矛盾；这里截到整秒（不是进位对齐），
        // 下面落库与回写内存共用这一枚值，两侧表示必然逐字一致。
        LocalDateTime normalizedPayTime = payTime.truncatedTo(ChronoUnit.SECONDS);
        String orderId = order.getId();
        // 条件更新防并发双击：只有 pay_status=0 才置 1，影响行数 0 说明并发窗口内已被支付 → 幂等返回
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, 0)
                .set(Order::getPayStatus, 1)
                .set(Order::getPayTime, normalizedPayTime));
        if (updated > 0) {
            order.setPayStatus(1);
            order.setPayTime(normalizedPayTime);
            // 销量闭环（T-M4-03）：支付成功即按明细累加 sold_count（下单不计销量，取消已支付单按同明细回退）；
            // 置态与累加同事务防"已支付未计销量"半态，行锁同时串行化取消链路的退款判定，未来 wx-notify 同点复用
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
            items.forEach(item -> dishMapper.increaseSoldCount(item.getDishId(), item.getQuantity()));
            // 取货码生成（F-18.6/D-3）：与上面的置态+销量累加同事务、同一 if (updated > 0) 支付成功点，
            // 幂等由 OrderMapper.attachPickupCode 的 `pickup_code IS NULL` 条件兜住（二次支付不换码）；
            // 失败抛 BizException → 调用方事务整体回滚，不出现「已支付无码」半态。
            // S3-0（F-18.7 第 3 条裁定的收口）：生成成功要把码回写进**内存里即将返回的那个 order 对象**，
            // 否则 mock-pay / free/claim 的即时响应 data.pickupCode 恒为 null（库里已有码），
            // 与 F-18.6 承诺的「C 端拿到码」不一致——C 端首屏读的正是这条响应。
            String pickupCode = attachPickupCode(orderId);
            order.setPickupCode(pickupCode);
            // 邀请新人挂钩（F-01.2 / F-04.2；F-05 后现金轨唯一激励）：支付成功事务内调 InviteService.onOrderPaid——
            // 首单（已支付订单数==1）→ invite_relation 原子置位 0→1
            // + 冻结现金入账（cash_flow status 0→1 → 邀请人 balance+5，F-04.2 修订②注册只冻结不入账）。
            // userId 取自 order 行：mock 链路上 payResult 已校验过 order.userId 与登录 userId 相等，二者恒等，
            // 而未来 wx 回调没有登录态，只能从订单行取——同一接缝两种调用方都成立。
            inviteService.onOrderPaid(order.getUserId());
            log.info("支付成功结算: orderId={}, userId={}, amount={}", orderId, order.getUserId(), order.getTotalPrice());
            return order;
        }
        return orderMapper.selectById(orderId);
    }

    /**
     * 取货码挂载（算法照 F-18.6 第 3 条原文，D-3/D-4）：6 位数字、最多重试 5 次；
     * 先用 countByPickupCode 避开已占用的码（撞唯一键的概率前移成一次读，不把 1062 打进事务），
     * 再走条件 UPDATE（WHERE id=#{orderId} AND pickup_code IS NULL）幂等写入——已支付单二次调用不会走到这里
     * （payResult 开头早退 + updated==0 回读两条路径都不重新生成），条件本身也保证并发二次置态不换码。
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
