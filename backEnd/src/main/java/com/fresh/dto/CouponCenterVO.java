package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 领券中心券项（F-05.2，GET /api/coupons/center）：TEMPLATES 单一真源直出 + 该用户已领判定，
 * 前端不做券模板硬编码；claimed 仅为展示态（置灰「已领取」），不承担领取校验职责。
 */
@Data
public class CouponCenterVO {

    /** 券模板 key：coupon_1/coupon_2/coupon_3 三档 + newbie_gift 新人见面礼（F-05 后领券中心直出） */
    private String couponKey;

    /** 使用门槛（满 X 元可用） */
    private BigDecimal threshold;

    /** 抵扣金额（减 Y 元） */
    private BigDecimal amount;

    /** 是否已领取：该用户存在任意 user_coupon 记录即 true——不筛 status、不看过期（已领即 claimed，F-05.2） */
    private Boolean claimed;
}
