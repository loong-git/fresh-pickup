package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.PartnerStoreUpdateDTO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.PartnerOrderService;
import com.fresh.service.PartnerStoreService;
import com.fresh.service.PartnerWalletService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
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
 *
 * <p><b>Step 4b 追加的四条（F-18.12 第 4~6 条，门店维护 + 到货批次）</b>：委托 PartnerStoreService，
 * 归属仍只从 {@code ATTR_PARTNER_STORE_IDS} 取；{@code storeId} 是 D-22 的<b>选择器</b>
 * （只用来在名下多店里选一家，服务端校验它 ∈ 集合、不在即 404），不是归属入参。
 * 门店出参是白名单 Map（D-23）——{@code Store} 实体那六列全挂着 {@code @JsonIgnore}，
 * 主理人要读它们只能走本类的重建路径。Step 3 那三个端点的方法体一字未改（G7 回归断言的行为不变）。
 */
@RestController
@RequestMapping("/api/partner")
public class PartnerController {

    @Autowired
    private PartnerOrderService partnerOrderService;
    @Autowired
    private PartnerStoreService partnerStoreService;
    @Autowired
    private PartnerWalletService partnerWalletService;

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

    /**
     * 门店信息（F-18.12 第 4 条，GET /api/partner/store）：D-23 白名单 Map，
     * 含六个新列 + name/address/service/status/lng/lat；contactPhone 只出脱敏值（RD）。
     * storeId 为 D-22 选择器，缺省取名下 id 最小的一家。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/store")
    public R<Map<String, Object>> store(@RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                        @RequestParam(required = false) Long storeId) {
        return R.ok(partnerStoreService.storeDetail(storeIds, storeId));
    }

    /**
     * 门店维护（F-18.12 第 5 条，PUT /api/partner/store）：body 只接受 D-7 的七项
     * （openTime/closeTime/dailyCapacity/accepting/contactPhone/service/photos），
     * 其余键（含 name/address/lng/lat/status）忽略不生效；写 ops_log action=update_store（detail 内手机号已脱敏）。
     * storeId 走查询参数（body 里的 storeId 也能当选择器，但它在白名单之外、绝不参与写列）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PutMapping("/store")
    public R<Map<String, Object>> updateStore(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                              @RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                              @RequestParam(required = false) Long storeId,
                                              @Valid @RequestBody PartnerStoreUpdateDTO dto) {
        return R.ok(partnerStoreService.updateStore(userId, storeIds, storeId, dto), "门店信息已更新");
    }

    /**
     * 到货批次清单（F-18.12 第 6 条，GET /api/partner/arrivals?date=yyyy-MM-dd，缺省=明日）：
     * 每行带 confirmed 布尔位，供前端出「当日批次未确认」警示条（D-9）。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/arrivals")
    public R<Map<String, Object>> arrivals(@RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                           @RequestParam(required = false) Long storeId,
                                           @RequestParam(required = false) String date) {
        return R.ok(partnerStoreService.arrivals(storeIds, storeId, date));
    }

    /**
     * 到货确认（F-18.12 第 6 条，POST /api/partner/arrivals/{id}/confirm）：
     * 四条件 UPDATE（id + 归属集合 + status∈{planned,arriving}），已 confirmed → 幂等成功且不重复写 ops_log，
     * 他店/不存在 → 404（同文案，不泄漏行存在性）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PostMapping("/arrivals/{id}/confirm")
    public R<Map<String, Object>> confirmArrival(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                                 @RequestAttribute(AuthInterceptor.ATTR_PARTNER_STORE_IDS) List<Long> storeIds,
                                                 @PathVariable("id") Long id) {
        return R.ok(partnerStoreService.confirmArrival(userId, storeIds, id), "到货已确认");
    }

    /**
     * 我的佣金（F-119 §3，GET /api/partner/wallet）：本人钱包余额 + 流水分页。
     * 归属=token userId（{@code ATTR_USER_ID}）——钱包按主理人 user 建（uk_user_wallet 一人一钱包），
     * 与门店归属集合正交；拦截器的 /api/partner/ 分支已验「登录 + 名下有店」，本方法不重复归属判定。
     * page/size 夹而不拒并回显生效值（照上方 orders 的既有姿势）。只读展示类：本功能无任何钱包写端点
     * （写路径只有生成账期跑批记账，契约 §3 红线），余额卡「记账态：提现通道未接入」由前端恒显。
     */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/wallet")
    public R<Map<String, Object>> wallet(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return R.ok(partnerWalletService.walletView(userId, page, size));
    }
}
