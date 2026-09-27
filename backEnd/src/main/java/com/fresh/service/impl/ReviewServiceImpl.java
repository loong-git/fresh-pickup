package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.common.BizException;
import com.fresh.common.WordFilter;
import com.fresh.entity.Dish;
import com.fresh.entity.Review;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private WordFilter wordFilter;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;

    @Override
    public List<Review> getReviewsByDishId(Long dishId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getDishId, dishId);
        // T-M4-05：命中软词待审（audit_status=pending）的评价默认不展示；
        // audit_status 为 NULL（正常）或其他值照常展示——IS NULL 必须显式判断，NULL <> 'pending' 不成立
        wrapper.and(w -> w.isNull(Review::getAuditStatus)
                .or().ne(Review::getAuditStatus, Review.AUDIT_PENDING));
        wrapper.orderByDesc(Review::getCreateTime);
        return reviewMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> listByUser(Long userId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getUserId, userId);
        // 我的评价不过滤 audit_status：本人可见自己待审（pending）评价；驳回为物理删除（AdminServiceImpl#auditReview），自然不出现
        wrapper.orderByDesc(Review::getCreateTime);
        List<Review> reviews = reviewMapper.selectList(wrapper);
        if (reviews.isEmpty()) {
            return Collections.emptyList();
        }
        // dish 名批量填充：去重 dishId 一次 selectBatchIds，两查询收敛，不做逐条循环查库
        List<Long> dishIds = reviews.stream().map(Review::getDishId).distinct().collect(Collectors.toList());
        Map<Long, String> dishNames = dishMapper.selectBatchIds(dishIds).stream()
                .collect(Collectors.toMap(Dish::getId, Dish::getName));
        // 字段按现有 Review 实体风格直出；时间键契约要求 createdAt（对齐 getMyCoupons 的 UserCoupon.createdAt 口径），
        // 与实体风格键 createTime 同值双写，兼容两端字段读取；dishId 无对应 dish（历史脏数据）时 dishName 为 null
        return reviews.stream().map(review -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", review.getId());
            item.put("dishId", review.getDishId());
            item.put("dishName", dishNames.get(review.getDishId()));
            item.put("userId", review.getUserId());
            item.put("rating", review.getRating());
            item.put("content", review.getContent());
            item.put("reviewer", review.getReviewer());
            item.put("auditStatus", review.getAuditStatus());
            item.put("createTime", review.getCreateTime());
            item.put("createdAt", review.getCreateTime());
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional // 评价落库与好评率回写同事务：任一失败整体回滚，good_rate 不滞留旧值
    public void addReview(Review review, Long userId) {
        // M6 购买归属校验：本人订单（任意状态）须含该菜品明细，防登录后对任意商品刷评
        if (orderItemMapper.countByUserIdAndDishId(userId, review.getDishId()) <= 0) {
            throw new BizException("请先购买该商品后再评价");
        }
        // M6 reviewer 服务端按登录手机号脱敏生成（138****1234），忽略前端直传昵称；user 须存在
        User user = userMapper.selectById(userId);
        if (user == null) {
            // userId 来自已验证 token，正常必命中；兜底防静默失败（同 AuthServiceImpl 口径）
            throw new BizException("用户不存在，请重新登录");
        }
        review.setUserId(userId);
        review.setReviewer(maskPhone(user.getPhone()));
        // T-M4-05 敏感词过滤（对原文匹配，先于 HTML 转义）：
        // 硬词 → 400"评价包含违规内容"（前端保留输入不清空）；软词 → audit_status=pending 待人工复核；正常 → NULL
        if (wordFilter.matchHard(review.getContent()) != null) {
            throw new BizException("评价包含违规内容");
        }
        review.setAuditStatus(wordFilter.matchSoft(review.getContent()) != null
                ? Review.AUDIT_PENDING : null);
        // T-M1-04：评价内容入库前 HTML 转义，防存储型 XSS
        if (review.getContent() != null) {
            review.setContent(HtmlUtils.htmlEscape(review.getContent()));
        }
        reviewMapper.insert(review);
        // 落库后按该 dish 真实评价聚合（rating>=4 占比）回写好评率，替代 init.sql 种子公式静态假值；
        // 无有效评价时回写影响行数 0，good_rate 保留种子兜底文案，详见 DishMapper.refreshGoodRate
        dishMapper.refreshGoodRate(review.getDishId());
    }

    /** 138****1234（与 OrderServiceImpl 脱敏口径一致；登录手机号已按 1[3-9]\d{9} 强校验，无需长度防御） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
