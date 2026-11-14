package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.CouponCenterVO;
import com.fresh.dto.CouponClaimDTO;
import com.fresh.entity.UserCoupon;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口（T-M4-02）：/api/coupons** 均需登录（AuthInterceptor 权限矩阵）。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    @Autowired
    private CouponService couponService;

    /**
     * 领取优惠券：固定三档模板，同 key 同人仅 1 张可用。
     * P11-2 P3-G：本类是最后一个未挂 @RateLimit 的写端点——补挂写档 10/60s（家族先例：商家写端点
     * MerchantDishController.java:66/75/86/96 与 MerchantApplyController.java:104/191 的「写 10/分」档；
     * keyType 缺省 USER 维度，与上述家族一致，登录用户按 userId 分桶）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)  // 写档 10/60s（P3-G，照商家写端点家族）
    @PostMapping("/claim")
    public R<UserCoupon> claim(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                               @Valid @RequestBody CouponClaimDTO dto) {
        return R.ok(couponService.claim(userId, dto.getCouponKey()), "领取成功");
    }

    /** 领券中心（F-05.2 独立页数据源）：TEMPLATES 全量直出 + 该用户已领判定（claimed 仅展示态，置灰「已领取」）。
     *  P11-2 P3-J：登录读补 USER 档 30/60s（家族先例：MerchantDishController 列表读档 :48）。 */
    @RateLimit(limit = 30, windowSeconds = 60)  // 读档 30/60s（P3-J，USER 维度）
    @GetMapping("/center")
    public R<List<CouponCenterVO>> center(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(couponService.center(userId));
    }

    /** 我的优惠券列表（确认订单页可用券下拉数据源，含 used 供记录展示）。
     *  P11-2 P3-J：登录读补 USER 档 30/60s（同 center）。 */
    @RateLimit(limit = 30, windowSeconds = 60)  // 读档 30/60s（P3-J，USER 维度）
    @GetMapping
    public R<List<UserCoupon>> myCoupons(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(couponService.listByUser(userId));
    }
}
