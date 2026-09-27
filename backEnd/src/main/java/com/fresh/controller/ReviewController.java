package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.ReviewCreateDTO;
import com.fresh.entity.Review;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评价接口（T-M1-04 加固 + T-M1-10 去除 @CrossOrigin，全局 CORS 由 config/CorsConfig 统一管理）。
 * T-M2-02/06：提交评价需登录（AuthInterceptor 校验），限流 1 次/10s/用户。
 * M6：userId 由 AuthInterceptor 注入（@RequestAttribute 读取，同 OrderController 口径），
 * 购买归属校验与 reviewer 服务端生成（脱敏手机号）收口在 service 层，前端直传昵称废弃不读。
 * M7：新增 GET /api/reviews/mine（我的评价，需登录）——原类级 /api/dishes/{dishId}/reviews 前缀
 * 下沉为各方法全路径，既有评价列表/提交两接口 URL 与行为均不变；匿名列表规则见 AuthInterceptor。
 */
@RestController
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    /** 评价列表（签名保持现状） */
    @GetMapping("/api/dishes/{dishId}/reviews")
    public Map<String, Object> getReviews(@PathVariable Long dishId) {
        List<Review> list = reviewService.getReviewsByDishId(dishId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        result.put("total", list.size());
        return result;
    }

    /** 提交评价：@Valid 校验入参（dishId 由路径变量必填），失败返回 400 人话 message；
     *  限流 T-M2-06：1 次/10s/用户；M6 归属校验见 ReviewService#addReview */
    @RateLimit(limit = 1, windowSeconds = 10)
    @PostMapping("/api/dishes/{dishId}/reviews")
    public Map<String, Object> addReview(@PathVariable Long dishId,
                                         @Valid @RequestBody ReviewCreateDTO dto,
                                         BindingResult bindingResult,
                                         @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        Map<String, Object> result = new HashMap<>();
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("；"));
            result.put("code", 400);
            result.put("message", message);
            return result;
        }
        Review review = new Review();
        review.setDishId(dishId);
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());
        // M6：reviewer/userId 均由服务端落（登录手机号脱敏/token 用户），不再取 dto.getReviewer()
        reviewService.addReview(review, userId);
        result.put("code", 200);
        result.put("message", "评价成功");
        result.put("data", review);
        return result;
    }

    /** 我的评价（M7）：/api/reviews/mine 需登录（AuthInterceptor 权限矩阵），userId 同 @RequestAttribute 口径 */
    @GetMapping("/api/reviews/mine")
    public R<List<Map<String, Object>>> myReviews(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(reviewService.listByUser(userId));
    }
}
