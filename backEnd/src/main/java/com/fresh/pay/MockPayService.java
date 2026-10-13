package com.fresh.pay;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.impl.OrderServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Mock 支付实现（T-M2-05，契约 M2-5）：模拟收银台，本类只做「这笔支付能不能认定成功」的判定，
 * 判定通过即把入账动作整体交给 {@link PaymentSettlement#settle}（F-21.3 Step 1 / D-21-1 抽出的唯一接缝）。
 * - 幂等：已支付重复调用直接返回成功，不重复"入账"（条件更新 pay_status=0 → 1 保证并发只生效一次）；
 * - 本人订单校验：非本人（含 user_id 未认领存量单）一律 404，不泄露他人订单存在性；
 * - 已取消订单不可支付；
 * - 销量累加、取货码（F-18.6/D-3）、邀请新人挂钩（F-01.2）等支付成功副作用集**不在本类**，
 *   全部在 PaymentSettlement#settle 内（原本就在同一个 if (updated > 0) 支付成功点，抽取只是换了宿主类），
 *   本类不再单独持有取货码算法与销量累加逻辑；MockPayService:33 那句「真实支付转正后在同一个
 *   if (updated > 0) 支付成功点做对偶挂钩」已由 F-21.3 Step 1 实践：未来 wx-notify 回调在自己的事务里
 *   调同一个 settle，两渠道共用同一处入账；
 * - 事务边界（F-21.3 第 2 条）：{@code @Transactional} 留在本方法上（mock 链路的事务入口），接缝类自身不标，
 *   置态/销量/取货码/邀请入账随本方法的事务同生同死——取货码生成失败抛 BizException 即整单回滚；
 * - 装配条件（F-21.3/D-21-2）：{@code @ConditionalOnProperty(pay.provider, havingValue="mock", matchIfMissing=true)}。
 *   dev 显式写了 {@code pay.provider: mock}、prod yml 里没有这个键（只有 {@code pay.mock-enabled: false}），
 *   两种形态都命中本条件（后者走 matchIfMissing）→ 装配结果与加注解前逐字相同；一旦设成 {@code pay.provider=wx}，
 *   本 Bean 自动让位给 WxPayService，按 PayService 类型注入的只剩 PayController:25 一处，歧义随之消失（FreeServiceImpl 自 F-21.5 Step 2a 起直依赖 PaymentSettlement，不再按该类型注入）；
 * - prod 环境对外表现：仍由 {@code pay.mock-enabled=false} 在 PayController 层把 /api/pay/{orderId}/mock-pay
 *   打成 404「资源不存在」——本 Bean 在 prod 照样装配（不再按 profile 装配），关闭只发生在端点层，这一点与从前一致。
 */
@Service
@ConditionalOnProperty(name = "pay.provider", havingValue = "mock", matchIfMissing = true)
public class MockPayService implements PayService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private PaymentSettlement settlement;

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
        // 支付成功点：条件置态 → 销量累加 → 取货码 → 邀请挂钩 → 内存回写，全在 PaymentSettlement 内
        // （本方法是 mock 链路的事务入口，payTime 在这里取一次，落库与回写内存同值）
        return settlement.settle(order, LocalDateTime.now());
    }
}
