package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.dto.CouponCenterVO;
import com.fresh.entity.CouponTemplate;
import com.fresh.entity.UserCoupon;
import com.fresh.mapper.CouponTemplateMapper;
import com.fresh.mapper.UserCouponMapper;
import com.fresh.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠券服务（F-113 表化）：券模板唯一真源 = coupon_template 表（旧 TEMPLATES 常量与
 * isLifetimeOnce 已删，双源必漂移）；发放时 threshold/amount/expireAt(=now+validDays)
 * 全取表行快照落 user_coupon，核销走 user_coupon 行上快照（getUsable/markUsed 一行不改）。
 * 三道防资损防线全在 claim，顺序即语义（换序即资损）：查表 → lifetime → 配额原子 → 快照落券。
 */
@Slf4j
@Service
public class CouponServiceImpl implements CouponService {

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    @Override
    @Transactional
    public UserCoupon claim(Long userId, String couponKey) {
        String key = couponKey == null ? "" : couponKey.trim();
        // 防线〇（§2.1 第 1 步）查表：不存在/停用同一出口 400「无效的优惠券」（文案与常量时代逐字一致）
        CouponTemplate template = couponTemplateMapper.selectByCouponKey(key);
        if (template == null || !CouponTemplate.STATUS_ACTIVE.equals(template.getStatus())) {
            throw new BizException("无效的优惠券");
        }
        // 防线一（§2.1 第 2 步）lifetime：limit_per_user>0 时全量计数该 user 该 key 的 user_coupon
        // （含 used/expired——selectCount 只按 user+key 过滤，与常量时代同口径）；
        // limit_per_user=1 时代码路径与旧语义逐字等价，0=不限（跳过本闸门）
        if (template.getLimitPerUser() != null && template.getLimitPerUser() > 0) {
            Long lifetime = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponKey, key));
            if (lifetime != null && lifetime >= template.getLimitPerUser()) {
                throw new BizException("每人限领 1 张");
            }
        }
        // 防线三（§2.1 第 3 步）配额原子：total_quota 非 NULL 时条件 UPDATE 占额（判定与自增同一条
        // UPDATE，无 COUNT+UPDATE 竞态）；0 行=已罄 400「该券已领完」。与下方 insert 同事务，
        // insert 后任何失败回滚时配额自增一并回滚——取消/失败路径无独立扣减点，不存在双扣
        if (template.getTotalQuota() != null) {
            if (couponTemplateMapper.tryIssueQuota(key) == 0) {
                throw new BizException("该券已领完");
            }
        }
        // 防线二（§2.1 第 4 步）快照落券：threshold/amount/expireAt(=now+valid_days) 全取表行，
        // 不再用 UserCoupon.VALID_DAYS——运营改面额只影响之后的新领取，已发券核销按券上快照
        UserCoupon coupon = new UserCoupon();
        coupon.setUserId(userId);
        coupon.setCouponKey(key);
        coupon.setThreshold(template.getThreshold());
        coupon.setAmount(template.getAmount());
        coupon.setStatus(UserCoupon.STATUS_AVAILABLE);
        coupon.setExpireAt(LocalDateTime.now().plusDays(template.getValidDays()));
        userCouponMapper.insert(coupon);
        log.info("发券成功: userId={}, couponKey={}, threshold={}, amount={}",
                userId, key, template.getThreshold(), template.getAmount());
        return coupon;
    }

    @Override
    public UserCoupon grant(Long userId, String couponKey) {
        // 遗留路径（无外部调用方，invite_reward 已随 F-05 下线）：同 claim 第 1/4 步——
        // 查表 + 快照落券，无 lifetime、无配额（与旧 grant 语义对齐：不去重不占额）
        CouponTemplate template = couponTemplateMapper.selectByCouponKey(couponKey);
        if (template == null || !CouponTemplate.STATUS_ACTIVE.equals(template.getStatus())) {
            throw new BizException("无效的优惠券");
        }
        UserCoupon coupon = new UserCoupon();
        coupon.setUserId(userId);
        coupon.setCouponKey(couponKey);
        coupon.setThreshold(template.getThreshold());
        coupon.setAmount(template.getAmount());
        coupon.setStatus(UserCoupon.STATUS_AVAILABLE);
        coupon.setExpireAt(LocalDateTime.now().plusDays(template.getValidDays()));
        userCouponMapper.insert(coupon);
        log.info("奖励发券成功: userId={}, couponKey={}, threshold={}, amount={}",
                userId, couponKey, template.getThreshold(), template.getAmount());
        return coupon;
    }

    @Override
    public List<CouponCenterVO> center(Long userId) {
        // 该用户已持有的全部券 key：不筛 status、不筛 expire_at——已领即 claimed（F-05.2），
        // used/过期记录同样算「已领」，与 claim 的 lifetime 口径同源；
        // 一次查询内存判重，替代逐模板 count
        Set<String> ownedKeys = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                        .select(UserCoupon::getCouponKey)
                        .eq(UserCoupon::getUserId, userId)).stream()
                .map(UserCoupon::getCouponKey)
                .collect(Collectors.toSet());
        // 表直出（F-113 §2.2）：active 全量按 id 升序（=迁移回填顺序 coupon_1/2/3/newbie_gift，
        // C 端展示序稳定）；title NULL 透传，前端不读不影响（C 端零改动）
        List<CouponTemplate> templates = couponTemplateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>()
                        .eq(CouponTemplate::getStatus, CouponTemplate.STATUS_ACTIVE)
                        .orderByAsc(CouponTemplate::getId));
        List<CouponCenterVO> result = new ArrayList<>(templates.size());
        for (CouponTemplate template : templates) {
            CouponCenterVO item = new CouponCenterVO();
            item.setCouponKey(template.getCouponKey());
            item.setTitle(template.getTitle());
            item.setThreshold(template.getThreshold());
            item.setAmount(template.getAmount());
            item.setClaimed(ownedKeys.contains(template.getCouponKey()));
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
