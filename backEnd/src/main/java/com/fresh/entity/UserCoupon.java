package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券（T-M4-02）：从券模板领取（F-113 表化后模板真源 = coupon_template 表；
 * 同 key 同人限领张数由模板 limit_per_user 决定），下单服务端核销（used + order_id 回写），
 * 订单取消回补（available + order_id 清空）。threshold/amount/expire_at 为发放时表行快照，
 * 核销不回读模板（运营改面额只影响之后的新领取）。
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_USED = "used";

    /**
     * 领取后有效天数（M6 时代的常量）：F-113 表化后 claim/grant 不再引用——
     * 有效期真源 = coupon_template.valid_days（发放时写 expire_at = 当前时间 + 表列天数）。
     * 常量保留仅作历史口径的注脚（表回填 valid_days=7 即取自它）。
     */
    public static final int VALID_DAYS = 7;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 持券用户ID（归属校验依据） */
    private Long userId;
    /** 券模板标识：coupon_1/coupon_2/coupon_3（券中心三档）+ newbie_gift（新人见面礼，F-05 挂领券中心）
     *  + 运营上新（F-113）；invite_reward（F-05 券轨下线已停发，存量券仍可核销）——
     *  面额真源 coupon_template 表（F-113 表化，发放时快照落本行 threshold/amount） */
    private String couponKey;
    /** 使用门槛（满 X 元可用） */
    private BigDecimal threshold;
    /** 抵扣金额（减 Y 元） */
    private BigDecimal amount;
    /** 券状态：available 可用 / used 已使用 */
    private String status;
    /** 核销订单号（置 used 时回写；订单取消回补时清空） */
    private String orderId;
    /** 领取时间：DB DEFAULT CURRENT_TIMESTAMP 兜底 */
    private LocalDateTime createdAt;
    /** 过期时间（M6）：NULL=永不过期（存量券回填远期值前兜底），getUsable/前端展示据此拦截或置灰 */
    private LocalDateTime expireAt;
}
