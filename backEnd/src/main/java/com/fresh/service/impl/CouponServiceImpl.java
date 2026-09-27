package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.dto.CouponCenterVO;
import com.fresh.entity.UserCoupon;
import com.fresh.mapper.UserCouponMapper;
import com.fresh.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CouponServiceImpl implements CouponService {

    /** 固定券模板：key → [threshold 满X元, amount 减Y元]。
     *  coupon_1~3 为券中心固定三档（契约第 2 条）；newbie_gift 为新人见面礼券（F-05：注册不再自动发放，
     *  改挂领券中心由用户自行领取）；invite_reward 邀请奖励券已随 F-05 券轨下线整体删除（存量已领券不受影响：
     *  模板只在发放时取面额，核销走券上冗余的 threshold/amount 字段），展示名约定：newbie_gift=新人见面礼
     *  （前端 mine 页映射一致） */
    private static final Map<String, BigDecimal[]> TEMPLATES = Map.of(
            "coupon_1", new BigDecimal[]{BigDecimal.valueOf(19), BigDecimal.valueOf(4)},
            "coupon_2", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(5)},
            "coupon_3", new BigDecimal[]{BigDecimal.valueOf(50), BigDecimal.valueOf(21)},
            "newbie_gift", new BigDecimal[]{BigDecimal.valueOf(30), BigDecimal.valueOf(8)});

    @Autowired
    private UserCouponMapper userCouponMapper;

    /** lifetime 闸门判定（F-05.1 修订第 3 条）：集合内券种每人限领 1 张；当前 TEMPLATES 全量纳入（固定三档+新人见面礼均属一次性领取语义），新增可重领券种时从此处移出即可 */
    private boolean isLifetimeOnce(String key) {
        return TEMPLATES.containsKey(key);
    }

    @Override
    public UserCoupon claim(Long userId, String couponKey) {
        String key = couponKey == null ? "" : couponKey.trim();
        BigDecimal[] template = TEMPLATES.get(key);
        if (template == null) {
            throw new BizException("无效的优惠券");
        }
        // F-05.1 lifetime 闸门（评审修订 V1.1）：TEMPLATES 全量为每人限领 1 张的券种——
        // 存在任意 user_coupon 记录（含 used/expired）即拒绝，堵死「领→核销→再领」与
        // 「过期即重领」两条资损路径（领券中心直出 TEMPLATES 可领区的配套必选项；
        // 旧语义「用完后可再领/过期不占名额」仅在前端硬编码 coupon_1~3 时代无害）
        if (isLifetimeOnce(key)) {
            Long lifetime = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponKey, key));
            if (lifetime != null && lifetime > 0) {
                throw new BizException("每人限领 1 张");
            }
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
    public List<CouponCenterVO> center(Long userId) {
        // 该用户已持有的全部券 key：不筛 status、不筛 expire_at——已领即 claimed（F-05.2），
        // used/过期记录同样算「已领」，避免「用完/过期即重领」在领券中心展示上与 claim 口径打架；
        // 一次查询内存判重，替代逐模板 count（TEMPLATES 仅 4 项，两写法等价，取查询次数少的）
        Set<String> ownedKeys = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                        .select(UserCoupon::getCouponKey)
                        .eq(UserCoupon::getUserId, userId)).stream()
                .map(UserCoupon::getCouponKey)
                .collect(Collectors.toSet());
        List<CouponCenterVO> result = new ArrayList<>();
        // 遍历 TEMPLATES 单一真源直出（F-05.2）：前端不做券模板硬编码
        for (Map.Entry<String, BigDecimal[]> entry : TEMPLATES.entrySet()) {
            CouponCenterVO item = new CouponCenterVO();
            item.setCouponKey(entry.getKey());
            item.setThreshold(entry.getValue()[0]);
            item.setAmount(entry.getValue()[1]);
            item.setClaimed(ownedKeys.contains(entry.getKey()));
            result.add(item);
        }
        return result;
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
