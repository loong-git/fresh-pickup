package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.StoreApplyCreateDTO;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.StoreApplyService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主理人入驻申请接口（F-18.12 第 1~2 条，D-20）。
 *
 * <p><b>前缀选择是设计要点</b>：挂 {@code /api/store-applies}，<b>不挂</b> {@code /api/partner/}（D-20）——
 * 申请阶段名下无店，{@code /api/partner/} 的「归属集合非空」闸门会把入口自己锁死。
 * 鉴权只要求「登录」：AuthInterceptor#requiresLogin 里新增的那一段（归属块一字未动），
 * role=user 也能用（S4b-G1 的核心断言），无 token 401 由拦截器给。
 *
 * <p>归属唯一来源是 token 解析出的 {@code @RequestAttribute(ATTR_USER_ID)}，
 * 两个方法的参数里都没有 userId/storeId 这类可由客户端伪造的字段（RF 红线）。
 *
 * <p>限流按 RF 口径读 30/写 10 每分，维度默认 USER（登录用户 + 接口身份分桶，见 RateLimitInterceptor#bucketKey）。
 */
@RestController
@RequestMapping("/api/store-applies")
public class StoreApplyController {

    @Autowired
    private StoreApplyService storeApplyService;

    /**
     * 提交入驻申请（{@code POST /api/store-applies}），body 见 StoreApplyCreateDTO。
     * 出参只有 {@code {id, status:"pending"}}；过审前不产生 store 行（D-12/A2.1）。
     */
    @RateLimit(limit = 10, windowSeconds = 60)   // 写 10/分（RF 档位）
    @PostMapping
    public R<Map<String, Object>> create(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                         @Valid @RequestBody StoreApplyCreateDTO dto) {
        return R.ok(storeApplyService.create(userId, dto), "申请已提交，审核结果会在「我的申请」里显示");
    }

    /**
     * 我的最新申请（{@code GET /api/store-applies/mine}）：七键 + historyCount，
     * 不出 contactPhone/proofPhotos（最小暴露）；从未申请过时各键为 null、historyCount=0。
     */
    @RateLimit(limit = 30, windowSeconds = 60)   // 读 30/分（RF 档位）
    @GetMapping("/mine")
    public R<Map<String, Object>> mine(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        return R.ok(storeApplyService.mine(userId));
    }
}
