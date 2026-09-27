package com.fresh.pay;

import com.fresh.entity.Order;

/**
 * 支付服务接口（T-M2-05，契约 M2-5）。
 * 真实支付渠道（微信支付等）后续以独立实现接入；当前仅 MockPayService（模拟收银台）。
 */
public interface PayService {

    /**
     * 发起支付并返回支付结果（支付后的订单，含 payStatus/payTime）。
     *
     * @param orderId 订单号
     * @param userId  当前登录用户ID（服务端校验本人订单，非本人一律 404）
     */
    Order payResult(String orderId, Long userId);
}
