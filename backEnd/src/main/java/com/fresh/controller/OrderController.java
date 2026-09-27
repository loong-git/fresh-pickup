package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Order;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单接口（@CrossOrigin 已删，T-M1-10 全局白名单见 config/CorsConfig）。
 * T-M2-02/03：三个端点均需登录（AuthInterceptor 校验并注入 userId），
 * 下单/查询/取消全部以 token userId 为归属依据，phone 查询参数废弃。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /** 下单（T-M1-01/03/05/09 + T-M2-03）：白名单 @Valid 校验 + 服务端计价 + 幂等 + token userId 落库；
     *  限流 T-M2-06：3 次/分钟/用户 */
    @RateLimit(limit = 3, windowSeconds = 60)
    @PostMapping
    public R<Order> createOrder(@Valid @RequestBody OrderCreateDTO dto,
                                @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        Order order = orderService.createOrder(dto, userId);
        return R.ok(order, "下单成功");
    }

    /** 订单分页查询（T-M1-08 + T-M2-03）：按 token userId 过滤（phone 参数废弃）+ 分页 + 手机号脱敏 */
    @GetMapping
    public R<Map<String, Object>> getOrders(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                            @RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                            @RequestParam(required = false) String status) {
        return R.ok(orderService.getOrders(userId, pageNum, pageSize, status));
    }

    /** 取消订单（T-M1-07 + T-M2-03）：仅本人 pending_pickup 订单可取消，取消回补库存；其他状态 400 */
    @PostMapping("/{id}/cancel")
    public R<Void> cancelOrder(@PathVariable("id") String id,
                               @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        orderService.cancelOrder(id, userId);
        return R.ok(null, "取消成功");
    }
}
