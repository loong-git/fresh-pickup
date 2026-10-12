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
 * 商家端只读接口（G-03 备货单两页 = F-17/G-03 Step 1；G-05 数据看板 + 营销参与态 = F-19/G-05 Step 1）。
 *
 * <p>独立类挂 /api/merchant（与 MerchantController 同前缀、路径互不重叠：那边 /me、/profile、/uploads，
 * 本类 /pickup-plan、/order-items、/stat、/marketing）。不塞进 MerchantDishController 是因为那个类已 6 个端点且主题是商品管理，
 * 本期是履约与经营读数。前缀仍命中 AuthInterceptor 的 /api/merchant/ 分支：登录 → 查库定 role=merchant →
 * merchant_id 为空即 403 → 注入 ATTR_MERCHANT_ID；角色校验本类一律不重复（R2/契约 F-17.2 #8），
 * 拿到的 merchantId 恒非 null。
 *
 * <p>RC「只读」：本类四个映射**全是** @GetMapping（G-03 两条 + G-05 两条，D-1 明令不新建 Controller），
 * POST/PUT/DELETE 三类写映射一个都不出现；也不落运营审计表（RF：本期无现场动作）。
 * 闸门口径有个分期差异，别读混：G-03 两条不经商家侧写闸门（停业商家照样要能看自己的历史备货，
 * 契约 F-17.2 #8）；G-05 两条按 D-9 **读侧也判停业**（merchant.status&lt;&gt;'active' → 403），
 * 判据与文案复用 MerchantService#assertActive，本类不加任何新文案。
 *
 * <p>参数绑定用 @RequestParam 而非 DTO：四个都是纯读、0-4 个参数，与 MerchantDishController#list:45-48
 * 同形态（DTO 是写端点的范式）。归属 merchantId 只从 @RequestAttribute 取，**不收任何归属入参**（R3 同型、
 * F-19 的 D-2 再次钉死）；storeId/date/status/page/size/days 都只是过滤与窗口，不是归属。
 *
 * <p>限流 USER 30/60s 四条同档：G-03 两条靠注解默认维度（keyType 默认即 USER）故未显式写；
 * G-05 两条按 D-10 原文把 keyType 显式写全（档位一致，只是可 grep 性更强）。
 * 非法 date 由 Service 抛 400，复用既有文案；类型不匹配（如 storeId=abc、days=abc）由
 * GlobalExceptionHandler 转同一条 400 文案（BE-14）。
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

    /**
     * F-19/G-05 数据看板（契约 D-1/D-3/D-4）：滚动自然日窗口内的四卡 + Top 5 商品。
     * 出参 {@code {days, windowStart, gmvBeforeCoupon, orderCount, itemCount, refundCount, top:[…]}}。
     *
     * <p>参数只有 {@code days}（窗口天数，缺省 7、值域 [1,90]，越界 400「天数需在 1 到 90 之间」，
     * {@code ?days=abc} 由 GlobalExceptionHandler(BE-14) 转 400「参数格式不正确」）。
     * **没有任何归属入参**（D-2）：merchantId 只从 @RequestAttribute 取，与上方两个端点同姿势。
     * 窗口口径与 {@code /pickup-plan} 的 {@code date}（自提自然日）分列，两者不可混用（D-3）。
     *
     * <p>限流按 D-10 写全三个属性（{@code keyType} 显式声明，虽然默认值就是 USER——
     * 契约把档位钉成 USER/30/60s，写全比依赖默认更容易被 grep 核验）。
     */
    @RateLimit(keyType = RateLimit.KeyType.USER, limit = 30, windowSeconds = 60)
    @GetMapping("/stat")
    public R<Map<String, Object>> stat(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                       @RequestParam(defaultValue = "7") Integer days) {
        return R.ok(merchantOrderService.stat(merchantId, days));
    }

    /**
     * F-19/G-05 营销参与态（契约 D-6/D-7）：本店被平台设置的秒杀件与免费领活动，**只读、本期无报名口**。
     * 出参 {@code {seckill:{count, items:[{dishId,name,price,seckillPrice,limitBuy,onSale,soldCountTotal}]}},
     * freeClaim:{count, items:[{activityId,dishId,name,status,dailyQuota,dailyClaimed,threshold,inPool}]}}}。
     *
     * <p>无参数（连 days 都没有——营销侧是「当前参与状态」的快照，不是窗口统计，
     * 其中 {@code soldCountTotal} 是历史累计值，D-7 点名不许当窗口数看）。
     * 本仓 merchant 2/3 两个参与位都是 0 行，故 {@code count:0 / items:[]} 是正常出参而非降级。
     */
    @RateLimit(keyType = RateLimit.KeyType.USER, limit = 30, windowSeconds = 60)
    @GetMapping("/marketing")
    public R<Map<String, Object>> marketing(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId) {
        return R.ok(merchantOrderService.marketing(merchantId));
    }
}
