package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.MerchantFulfillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家侧履约现场动作接口（F-18.8 第 5 条/D-15）。
 *
 * <p><b>为什么是新类而不是往 MerchantOrderController 里加一个 PUT</b>：那个类的类注释自陈
 * 「本类只有两个 {@code @GetMapping}，POST/PUT/DELETE 三类写映射一个都不出现」（真实在
 * MerchantOrderController:25），读侧那句自证是 G-03 交付时钉下的口径——把写映射塞进去会让它变成假话。
 * D-15 已裁定：新建本类，同挂 {@code /api/merchant} 前缀、路径 {@code /order-items/{id}/shortage}，
 * 与那两条 GET 互不重叠，鉴权仍走 AuthInterceptor 同一个 merchant 分支（role=merchant +
 * merchant_id 非空，否则 403），ATTR_MERCHANT_ID 恒非 null。
 *
 * <p>无请求体：PUT 的是「这一行明细进入缺货状态」这一个既定动作，没有可变字段，
 * 收 {@code @RequestBody} 只会多一个可被伪造的入参面（fulfill_status 的取值由服务端钉死为 shortage）。
 * 归属 merchantId 与操作者 userId 都只从 {@code @RequestAttribute} 取（RF 红线）。
 *
 * <p>限流按 RF「写 10/分」；审计由 Service 落 ops_log（D-5：现场动作，操作者是商家 userId）。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantFulfillController {

    @Autowired
    private MerchantFulfillService merchantFulfillService;

    /**
     * 缺货登记（{@code PUT /api/merchant/order-items/{id}/shortage}）。
     * 200=已登记或幂等命中（重复登记不报错，也不落第二行 ops_log）；404=行不存在或不是自己的行；
     * 403=拦截器层 merchant_id 为空（本类拿不到 null 的 merchantId）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PutMapping("/order-items/{id}/shortage")
    public R<Void> registerShortage(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                    @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                    @PathVariable Long id) {
        merchantFulfillService.registerShortage(userId, merchantId, id);
        return R.ok(null, "已登记缺货");
    }
}
