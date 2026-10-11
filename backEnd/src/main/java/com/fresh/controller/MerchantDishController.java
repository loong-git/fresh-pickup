package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.dto.MerchantDishCreateDTO;
import com.fresh.dto.MerchantDishOnSaleDTO;
import com.fresh.dto.MerchantDishStockDTO;
import com.fresh.dto.MerchantDishUpdateDTO;
import com.fresh.entity.Dish;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.MerchantDishService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端商品接口（F-13/G-02 Step 2，契约 F-13.2 六端点）。
 * 独立类挂 /api/merchant/dishes（不塞进 MerchantController，避免单类膨胀），前缀仍命中
 * AuthInterceptor 的 /api/merchant/ 分支：登录 → 查库定 role=merchant → merchant_id 为空即 403 →
 * 注入 ATTR_MERCHANT_ID。角色校验本类一律不重复（R2/契约 §2.8 裁决），拿到的 merchantId 恒非 null。
 * 归属：六个端点全部 @RequestAttribute 取值，不收任何归属入参（R2/R3——路径里的 {id} 只是目标行，
 * 是否属于当前商家由 Service 的 WHERE id=? AND merchant_id=? 判定，他商家统一 404「商品不存在」）。
 * 限流：读 30/分、写 10/分（R6' 随建随挂；USER 维度是注解默认，故不写 keyType）。
 */
@RestController
@RequestMapping("/api/merchant/dishes")
public class MerchantDishController {

    @Autowired
    private MerchantDishService merchantDishService;

    /** 商品列表：tab=on_sale/off_sale/all（缺省 all）+ category + keyword（名称 LIKE），一期不分页 */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping
    public R<List<Dish>> list(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                              @RequestParam(required = false) String tab,
                              @RequestParam(required = false) String category,
                              @RequestParam(required = false) String keyword) {
        return R.ok(merchantDishService.list(merchantId, tab, category, keyword));
    }

    /** 商品详情：id + merchant_id 双条件，查无 404 */
    @RateLimit(limit = 30, windowSeconds = 60)
    @GetMapping("/{id}")
    public R<Dish> detail(@RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                          @PathVariable Long id) {
        return R.ok(merchantDishService.detail(merchantId, id));
    }

    /** 新增商品：归属由服务端注入（DTO 无 merchantId/id 字段，R5'），返回落库后的完整行 */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PostMapping
    public R<Dish> create(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                          @Valid @RequestBody MerchantDishCreateDTO dto) {
        return R.ok(merchantDishService.create(userId, merchantId, dto), "商品已创建");
    }

    /** 编辑商品：部分更新（全空 400）；库存列不在本接口（R7'，走 /stock 增量） */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PutMapping("/{id}")
    public R<Void> update(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                          @PathVariable Long id,
                          @Valid @RequestBody MerchantDishUpdateDTO dto) {
        merchantDishService.update(userId, merchantId, id, dto);
        return R.ok(null, "商品已更新");
    }

    /** 库存增量调整（正补负减，|delta|≤1000，减成负数 400）：data 为调整后的真实库存 */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PutMapping("/{id}/stock")
    public R<Integer> adjustStock(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                  @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                                  @PathVariable Long id,
                                  @Valid @RequestBody MerchantDishStockDTO dto) {
        return R.ok(merchantDishService.adjustStock(userId, merchantId, id, dto), "库存已更新");
    }

    /** 上下架：body {onSale: 1|0}（dish.on_sale 是 TINYINT，口径修正①用 Integer 不用 boolean） */
    @RateLimit(limit = 10, windowSeconds = 60)
    @PutMapping("/{id}/on-sale")
    public R<Void> setOnSale(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                             @RequestAttribute(AuthInterceptor.ATTR_MERCHANT_ID) Long merchantId,
                             @PathVariable Long id,
                             @Valid @RequestBody MerchantDishOnSaleDTO dto) {
        merchantDishService.setOnSale(userId, merchantId, id, dto);
        return R.ok(null, "商品状态已更新");
    }
}
