package com.fresh.controller;

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

    /** 领取优惠券：固定三档模板，同 key 同人仅 1 张可用 */
    @PostMapping("/claim")
    public R<UserCoupon> claim(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                               @Valid @RequestBody CouponClaimDTO dto) {
        return R.ok(couponService.claim(userId, dto.getCouponKey()), "领取成功");
    }

    /** 领券中心（F-05.2 独立页数据源）：TEMPLATES 全量直出 + 该用户已领判定（claimed 仅展示态，置灰「已领取」） */
    @GetMapping("/center")
    public R<List<CouponCenterVO>> center(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(couponService.center(userId));
    }

    /** 我的优惠券列表（确认订单页可用券下拉数据源，含 used 供记录展示） */
    @GetMapping
    public R<List<UserCoupon>> myCoupons(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(couponService.listByUser(userId));
    }
}
