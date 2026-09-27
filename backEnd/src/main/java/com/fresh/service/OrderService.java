package com.fresh.service;

import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Order;

import java.util.Map;

public interface OrderService {

    /**
     * 下单（T-M1-01/03/05/09 + T-M2-03）：白名单入参、服务端计价、原子扣减库存、clientRequestId 幂等；
     * userId 由服务端从 JWT token 解析传入（DTO 不收 userId），落库 user_id。
     * 返回落库后的订单（含 id 与明细）。
     */
    Order createOrder(OrderCreateDTO dto, Long userId);

    /**
     * 订单分页查询（T-M1-08 + T-M2-03）：按 token userId 归属过滤（phone 查询参数已废弃，
     * 防全量导出），返回 {list, total}，list 内手机号脱敏、明细批量 IN 查询。
     */
    Map<String, Object> getOrders(Long userId, Integer pageNum, Integer pageSize, String status);

    /** 取消订单（T-M1-07 + T-M2-03）：仅本人 pending_pickup 订单可取消，取消后回补库存 */
    void cancelOrder(String orderId, Long userId);

    /**
     * 登录认领存量订单（T-M2-03，契约 M2-4）：UPDATE orders SET user_id=?
     * WHERE phone=? AND user_id IS NULL，返回认领条数。
     */
    int claimOrdersByPhone(String phone, Long userId);

    /**
     * 未支付订单超时自动关单（T-超时关单）：status=pending_pickup 且 pay_status=0
     * 且 create_time 距今超 30 分钟的订单，复用取消回补链路（库存/券）置为既有终态
     * cancelled（不新增状态值）；支付成功只置 pay_status=1 不改 status，故待支付判定
     * 必须叠加 pay_status=0，已支付订单绝不触碰。关单走状态机条件更新，与手动取消/
     * 支付并发互斥，只关一次不双重回补。返回本次实际关单数（供调度日志）。
     */
    int closeTimeoutOrders();
}
