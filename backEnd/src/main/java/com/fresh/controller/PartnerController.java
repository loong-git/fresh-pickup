package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.PartnerOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 主理人端接口（F-18.8 第 4 条，Step 3 的三个新端点）。
 *
 * <p>独立类挂 {@code /api/partner}：与商家端（/api/merchant）不同前缀、不同身份，
 * 因为 D-1 已裁定「主理人不占 user.role」，两套身份不能塞进同一个闸门。鉴权全在
 * AuthInterceptor 的 /api/partner/ 分支收口（登录 → 按 token userId 查 store.owner_user_id →
 * 集合为空 403 → 注入 ATTR_PARTNER_STORE_IDS），本类不重复角色判定。
 *
 * <p>归属只从 {@code @RequestAttribute} 取，**没有任何归属入参**（RF 红线）：三个方法的参数里
 * 找不到 storeId/merchantId/operatorUserId 这种可由客户端伪造的字段。
 *
 * <p>限流按 RF 口径读 30/写 10 每分（维度默认 USER，故不写 keyType）；核销台被刷的兜底还有
 * Service 里的 D-14 防枚举锁，两层不同源（令牌桶按 userId+路径，锁按 userId 计失败次数）。
 *
 * <p>出参形态见 PartnerOrderService 注释：清单不含 pickupCode/address/userId（D-13/RD），
 * 电话只以 maskedPhone 出现。核销请求体用 {@code @RequestBody Map<String, String>} 而非新建 DTO
 * （本批只读一个键，不值得多一个文件；缺键即 null → Service 判形状后 400）。
 */
@RestController
@RequestMapping("/api/partner")
public class PartnerController {

    @Autowired
    private PartnerOrderService partnerOrderService;

    /**
     * 名下门店（GET /api/partner/stores）：出参 id/name/address/status/service，
     * 不含 owner_user_id/contact_phone/photos/daily_capacity/accepting（D-12 同口径）。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/stores")
    public R<List<Map<String, Object>>> stores(
            @RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds) {
        return R.ok(partnerOrderService.stores(storeIds));
    }

    /**
     * 核销清单（GET /api/partner/orders）：pickupDate 可选（yyyy-MM-dd，非法 400）；
     * status 白名单 all|pending_pickup|completed（非法值 400，与商家侧兜底 all 不同）；
     * page/size 夹而不拒并回显。cancelled 三个取值下一律排除。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/orders")
    public R<Map<String, Object>> orders(@RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                         @RequestParam(required = false) String pickupDate,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return R.ok(partnerOrderService.orders(storeIds, pickupDate, status, page, size));
    }

    /**
     * 核销（POST /api/partner/orders/verify），body {@code {pickupCode}}。
     * 定性码由 Service 给：200 核销成功／200 幂等成功、400 码形状或状态不允许、
     * 404 查无此码或不属于名下门店、429 命中 D-14 防枚举锁（含锁定期内的正确码）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PostMapping("/orders/verify")
    public R<Map<String, Object>> verify(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                         @RequestBody Map<String, String> body) {
        return R.ok(partnerOrderService.verify(userId, storeIds, body == null ? null : body.get("pickupCode")));
    }
}
