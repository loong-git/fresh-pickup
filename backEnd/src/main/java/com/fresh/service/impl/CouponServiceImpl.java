package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.entity.UserCoupon;
import com.fresh.mapper.UserCouponMapper;
import com.fresh.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CouponServiceImpl implements CouponService {

    /** 固定券模板：key → [threshold 满X元, amount 减Y元]。
     *  coupon_1~3 为券中心固定三档（契约第 2 条）；invite_reward/newbie_gift 为邀请奖励券（F-01.0），
     *  展示名约定：invite_reward=邀请奖励券、newbie_gift=新人见面礼（前端 mine 页映射一致） */
    private static final Map<String, BigDecimal[]> TEMPLATES = Map.of(
            "coupon_1", new BigDecimal[]{BigDecimal.valueOf(19), BigDecimal.valueOf(4)},
            "coupon_2", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(5)},
            "coupon_3", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(21)},
            "invite_reward", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(15)},
            "newbie_gift", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(8)});

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Override
    public UserCoupon claim(Long userId, String couponKey) {
        String key = couponKey == null ? "" : couponKey.trim();
        BigDecimal[] template = TEMPLATES.get(key);
        if (template == null) {
            throw new BizException("无效的优惠券");
        }
        // 同 key 同人仅 1 张可用（契约）：已领未用的不再重复发；用完（used）后可再次领取。
        // 过期（M6）的 available 券不占用名额：否则过期券拦截使用后，该档位将永久无法再领
        Long available = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponKey, key)
                .eq(UserCoupon::getStatus, UserCoupon.STATUS_AVAILABLE)
                .and(w -> w.isNull(UserCoupon::getExpireAt).or().gt(UserCoupon::getExpireAt, LocalDateTime.now())));
        if (available != null && available > 0) {
            throw new BizException("该优惠券已领取，请先使用");
        }
        UserCoupon coupon = new UserCoupon();
        coupon.setUserId(userId);
        coupon.setCouponKey(key);
        coupon.setThreshold(template[0]);
        coupon.setAmount(template[1]);
        coupon.setStatus(UserCoupon.STATUS_AVAILABLE);
        // 有效期（M6）：领取即写过期时间，下单 getUsable 拦截、前端券中心据此展示
        coupon.setExpireAt(LocalDateTime.now().plusDays(UserCoupon.VALID_DAYS));
        userCouponMapper.insert(coupon);
        log.info("发券成功: userId={}, couponKey={}, threshold={}, amount={}",
                userId, key, template[0], template[1]);
        return coupon;
    }

    @Override
    public UserCoupon grant(Long userId, String couponKey) {
        BigDecimal[] template = TEMPLATES.get(couponKey);
        if (template == null) {
            throw new BizException("无效的优惠券");
        }
        // 与 claim 的差异：不去重（F-01 邀请奖励随多个被邀人累计多张）、不抛"已领取"；
        // 有效期同现有券（UserCoupon.VALID_DAYS=7，F-01.0 裁定）
        UserCoupon coupon = new UserCoupon();
        coupon.setUserId(userId);
        coupon.setCouponKey(couponKey);
        coupon.setThreshold(template[0]);
        coupon.setAmount(template[1]);
        coupon.setStatus(UserCoupon.STATUS_AVAILABLE);
        coupon.setExpireAt(LocalDateTime.now().plusDays(UserCoupon.VALID_DAYS));
        userCouponMapper.insert(coupon);
        log.info("奖励发券成功: userId={}, couponKey={}, threshold={}, amount={}",
                userId, couponKey, template[0], template[1]);
        return coupon;
    }

    @Override
    public List<UserCoupon> listByUser(Long userId) {
        return userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId));
    }

    @Override
    public UserCoupon getUsable(Long userId, Long couponId, BigDecimal subtotal) {
        UserCoupon coupon = userCouponMapper.selectById(couponId);
        // 归属校验：非本人券一律按不存在处理，不泄露他人持券信息
        if (coupon == null || !userId.equals(coupon.getUserId())) {
            throw new BizException("优惠券不存在或不可用");
        }
        if (!UserCoupon.STATUS_AVAILABLE.equals(coupon.getStatus())) {
            throw new BizException("优惠券已使用或已失效");
        }
        // 过期拦截（M6）：expire_at 为 NULL 视为永不过期（存量券兜底）
        if (coupon.getExpireAt() != null && coupon.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException("优惠券已过期");
        }
        if (subtotal.compareTo(coupon.getThreshold()) < 0) {
            throw new BizException("未满足满减条件");
        }
        return coupon;
    }

    @Override
    public int markUsed(Long couponId, Long userId, String orderId) {
        // 条件 UPDATE 原子核销：并发用同一张券只有一次成功；0 行 → 上层抛错回滚整单
        return userCouponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                .eq(UserCoupon::getId, couponId)
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, UserCoupon.STATUS_AVAILABLE)
                .set(UserCoupon::getStatus, UserCoupon.STATUS_USED)
                .set(UserCoupon::getOrderId, orderId));
    }

    @Override
    public int restoreByOrderId(String orderId) {
        // 按 order_id 定位 + used 条件：防重复回补；同事务随订单状态迁移仅成功一次
        return userCouponMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                .eq(UserCoupon::getOrderId, orderId)
                .eq(UserCoupon::getStatus, UserCoupon.STATUS_USED)
                .set(UserCoupon::getStatus, UserCoupon.STATUS_AVAILABLE)
                .set(UserCoupon::getOrderId, null));
    }
}
