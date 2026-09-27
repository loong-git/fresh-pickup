package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 领券入参（T-M4-02）：couponKey 对应固定三档模板 coupon_1/coupon_2/coupon_3 */
@Data
public class CouponClaimDTO {

    @NotBlank(message = "请选择要领取的优惠券")
    @Size(max = 50, message = "券标识不合法")
    private String couponKey;
}
