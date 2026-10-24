package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 领券中心券项（F-05.2，GET /api/coupons/center）：coupon_template 表 active 全量直出
 * （F-113 表化，旧 TEMPLATES 常量已删）+ 该用户已领判定，
 * 前端不做券模板硬编码；claimed 仅为展示态（置灰「已领取」），不承担领取校验职责。
 */
@Data
public class CouponCenterVO {

    /** 券模板 key：coupon_1/coupon_2/coupon_3 三档 + newbie_gift 新人见面礼 + 运营上新（F-113 起表直出） */
    private String couponKey;

    /**
     * 展示名（F-113 增量字段）：coupon_template.title 原样透传，NULL=未配置——
     * 前端不读此字段，按「满X减Y」拼装（C 端零改动）；出参形状增量加键，旧客户端不受影响。
     */
    private String title;

    /** 使用门槛（满 X 元可用） */
    private BigDecimal threshold;

    /** 抵扣金额（减 Y 元） */
    private BigDecimal amount;

    /** 是否已领取：该用户存在任意 user_coupon 记录即 true——不筛 status、不看过期（已领即 claimed，F-05.2） */
    private Boolean claimed;
}
