package com.fresh.service;

import com.fresh.entity.Review;
import java.util.List;
import java.util.Map;

public interface ReviewService {
    List<Review> getReviewsByDishId(Long dishId);

    /**
     * 我的评价（M7）：查当前登录用户全部评价（含 pending 待审），按评价时间倒序；
     * 逐条填充 dishName（按 dish_id 批量查 dish 表，两查询收敛，杜绝循环查库）
     */
    List<Map<String, Object>> listByUser(Long userId);

    /**
     * 提交评价（M6）：userId 由登录态注入，须存在本人订单明细（任意订单状态）方可评价；
     * reviewer 由服务端按登录手机号脱敏生成，前端直传昵称废弃
     */
    void addReview(Review review, Long userId);
}
