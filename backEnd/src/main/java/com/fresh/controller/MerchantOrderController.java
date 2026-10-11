package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.MerchantOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商家端只读备货单接口（F-17/G-03 Step 1，契约 F-17.3 端点表两行）。
 *
 * <p>独立类挂 /api/merchant（与 MerchantController 同前缀、路径互不重叠：那边 /me、/profile、/uploads，
 * 本类 /pickup-plan、/order-items）。不塞进 MerchantDishController 是因为那个类已 6 个端点且主题是商品管理，
 * 本期是履约主题。前缀仍命中 AuthInterceptor 的 /api/merchant/ 分支：登录 → 查库定 role=merchant →
 * merchant_id 为空即 403 → 注入 ATTR_MERCHANT_ID；角色校验本类一律不重复（R2/契约 F-17.2 #8），
 * 拿到的 merchantId 恒非 null。
 *
 * <p>RC「只读」：本类只有两个 @GetMapping，POST/PUT/DELETE 三类写映射一个都不出现；
 * 也不经任何写闸门（停业商家照样要能看自己的历史备货，契约 F-17.2 #8），不落运营审计表。
 *
 * <p>参数绑定用 @RequestParam 而非 DTO：两个都是纯读、2-4 个参数，与 MerchantDishController#list:45-48
 * 同形态（DTO 是写端点的范式）。归属 merchantId 只从 @RequestAttribute 取，**不收任何归属入参**（R3 同型）；
 * storeId/date/status/page/size 都只是过滤与分页。
 *
 * <p>限流 USER 30/60s（注解默认维度即 USER，故不写 keyType）。非法 date 由 Service 抛 400，
 * 复用既有文案；类型不匹配（如 storeId=abc）由 GlobalExceptionHandler 转同一条 400 文案。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantOrderController {

    @Autowired
    private MerchantOrderService merchantOrderService;

    /**
     * 备货单聚合：按自提点分组的当日各菜品备货总量。
     * date 为空时 Service 默认「明天」并把生效值回显在 data.date；storeId 为空表示不限自提点。
     * 出参 {@code {date, groups:[{storeId, storeName, orderCount, items:[{dishId, dishName, quantity}]}]}}，
     * 聚合里**不含电话**（备货拣货不需要联系电话，最小暴露，契约 F-17.2 #6）。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/pickup-plan")
    public R<Map<String, Object>> pickupPlan(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) Long storeId) {
        return R.ok(merchantOrderService.pickupPlan(merchantId, date, storeId));
    }

    /**
     * 供货明细分页：每行给 maskedPhone（不出 phone 明文），不含 address / user_id。
     * date 为空 = 不限自提日期（本端点无默认值）；status 值域
     * pending_pickup/completed/all，未知取值兜底 all；cancelled 三个取值下一律排除；
     * size 夹到 [1,50]、page<1 归一 1（夹而不拒并回显生效值）。
     * 出参 {@code {list:[...], page, size, total, hasMore}}。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/order-items")
    public R<Map<String, Object>> orderItems(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return R.ok(merchantOrderService.orderItems(merchantId, date, status, page, size));
    }
}
