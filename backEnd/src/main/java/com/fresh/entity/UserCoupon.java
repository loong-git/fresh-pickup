package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券（T-M4-02）：从固定三档模板领取（同 key 同人仅 1 张 available），
 * 下单服务端核销（used + order_id 回写），订单取消回补（available + order_id 清空）。
 * 有效期（M6）：领取时写 expire_at = 当前时间 + VALID_DAYS 天，过期券 getUsable 拦截不可用。
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_USED = "used";

    /** 领取后有效天数（M6）：claim 写 expire_at 的唯一依据，调整有效期只改这里 */
    public static final int VALID_DAYS = 7;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 持券用户ID（归属校验依据） */
    private Long userId;
    /** 券模板标识：coupon_1/coupon_2/coupon_3（券中心三档）；invite_reward/newbie_gift（F-01 邀请奖励，CouponServiceImpl.TEMPLATES） */
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
