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

    /** 评价列表（签名保持现状）
     *  F-42b 收信封：手工 Map → R.ok(list)；原顶层 total（=list.size()，零信息量）一并去掉，
     *  data 仍是数组本体（C 端两处调用方均按 reviewRes.data 直接取数组）。 */
    @GetMapping("/api/dishes/{dishId}/reviews")
    public R<List<Review>> getReviews(@PathVariable Long dishId) {
        return R.ok(reviewService.getReviewsByDishId(dishId));
    }

    /** 提交评价：@Valid 校验入参（dishId 由路径变量必填），失败返回 400 人话 message；
     *  限流 T-M2-06：1 次/10s/用户；M6 归属校验见 ReviewService#addReview
     *  F-51 收信封（T-M1-02 最后一个已知漏口）：本方法是 f42b 那批刻意留下的写接口——当时「收它需要 POST
     *  真评价写库取证」，取证已由 F-51 的离线信封契约单测补齐（见 controller/ReadEnvelopeContractTest 同形态）。
     *  两出口由手工 Map 换成 R.fail/R.ok，code 与 message 的取值一字未改；400 支因此从「缺 data 键」
     *  变成「显式 data:null」，C 端 request 层只读 body.code/body.message，不受影响。 */
    @RateLimit(limit = 1, windowSeconds = 10)
    @PostMapping("/api/dishes/{dishId}/reviews")
    public R<Review> addReview(@PathVariable Long dishId,
                               @Valid @RequestBody ReviewCreateDTO dto,
                               BindingResult bindingResult,
                               @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        if (bindingResult.hasErrors()) {
            String message = bindingResult.getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("；"));
            return R.fail(R.CODE_BAD_REQUEST, message);
        }
        Review review = new Review();
        review.setDishId(dishId);
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());
        // M6：reviewer/userId 均由服务端落（登录手机号脱敏/token 用户），不再取 dto.getReviewer()
        reviewService.addReview(review, userId);
        return R.ok(review, "评价成功");
    }

    /** 我的评价（M7）：/api/reviews/mine 需登录（AuthInterceptor 权限矩阵），userId 同 @RequestAttribute 口径 */
    @GetMapping("/api/reviews/mine")
    public R<List<Map<String, Object>>> myReviews(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(reviewService.listByUser(userId));
    }
}
