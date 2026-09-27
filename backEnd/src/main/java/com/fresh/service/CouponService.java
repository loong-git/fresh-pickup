package com.fresh.service;

import com.fresh.entity.UserCoupon;

import java.math.BigDecimal;
import java.util.List;

/** 优惠券服务（T-M4-02）：领取 / 我的券 / 下单核销 / 取消回补 */
public interface CouponService {

    /** 领券：按固定三档模板发券，同 key 同人仅 1 张 available（重复领取 400） */
    UserCoupon claim(Long userId, String couponKey);

    /**
     * 奖励发券（F-01 邀请新人）：按模板直接 insert 一张 available 券（expire_at=now+7d），
     * 不做 claim 的"同 key 同人仅 1 张 available"去重——邀请奖励随多个被邀人可累计多张。
     * 仅供服务端系统触发场景（新人见面礼/邀请奖励），不暴露为领券端点。
     */
    UserCoupon grant(Long userId, String couponKey);

    /** 我的券列表（确认订单页可用券下拉数据源），id 降序新券在前 */
    List<UserCoupon> listByUser(Long userId);

    /**
     * 校验并返回可核销的券：归属本人 + status=available + 小计满足 threshold，否则抛 BizException(400)。
     * 只读校验，不落写；真正置 used 由 {@link #markUsed} 条件 UPDATE 完成。
     */
    UserCoupon getUsable(Long userId, Long couponId, BigDecimal subtotal);

    /**
     * 核销：条件 UPDATE（id+userId+available → used+order_id），并发重复核销影响行数=0。
     * 必须在下单事务内调用，失败由上层抛错回滚整单（含库存扣减）。
     */
    int markUsed(Long couponId, Long userId, String orderId);

    /**
     * 取消回补：按核销订单号定位（status=used 才回补），置 available 并清空 order_id。
     * 仅随订单状态迁移成功的事务执行一次。
     */
    int restoreByOrderId(String orderId);
}
