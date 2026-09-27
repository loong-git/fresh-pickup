package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.FreeClaimDTO;
import com.fresh.entity.Order;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.FreeService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 免费领商品接口（M7，方案 A：每日 0 元限量领，复用订单链路生成 0 元单并自动支付；
 * M8 满额赠：请求体 { dishId } 从候选池自选商品；M9 起请求体增 items=用户当前购物车内容
 * （dishId+quantity），满额门槛按服务端以 dish 表价格重算的合计判定，不信任前端任何金额字段）。
 * - GET /api/free/current 匿名可读（AuthInterceptor 匿名路径自动注入 userId，登录后返回个人数据）；
 * - POST /api/free/claim、GET /api/free/my-claims 需登录（AuthInterceptor 白名单），
 *   userId 一律服务端从 token 解析（@RequestAttribute），接口不收任何 phone/address/金额入参。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/free")
public class FreeController {

    @Autowired
    private FreeService freeService;

    /** 今日免费商品（匿名可读，M8 满额赠）：dish + remaining + threshold + pool + 登录态个人数据 */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    @GetMapping("/current")
    public R<Map<String, Object>> current(
            @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId) {
        return R.ok(freeService.current(userId));
    }

    /** 0 元领取（需登录）：请求体 { dishId, items }，items=用户当前购物车内容（dishId+quantity），
     *  门槛按服务端以 dish 表价格重算的合计判定（不信任前端金额）；单事务限量计数→领取记录→建单→自动支付；
     *  限流与下单同级 3 次/分钟/用户，userId 一律服务端从 token 解析 */
    @RateLimit(limit = 3, windowSeconds = 60)
    @PostMapping("/claim")
    public R<Order> claim(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @Valid @RequestBody FreeClaimDTO dto) {
        return R.ok(freeService.claim(userId, dto.getDishId(), dto.getItems()),
                "0元领取成功，凭订单到自提点自提");
    }

    /** 我的领取记录（需登录）：分页，关联订单状态随行返回（手机号脱敏口径同 getOrders） */
    @GetMapping("/my-claims")
    public R<Map<String, Object>> myClaims(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                           @RequestParam(defaultValue = "1") Integer pageNum,
                                           @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.ok(freeService.myClaims(userId, pageNum, pageSize));
    }
}
