package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.entity.Store;
import com.fresh.service.StoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 自提点门店接口（T-M4-01，匿名可读——AuthInterceptor 权限矩阵白名单外默认放行 GET）。
 * 响应 R{code, data:[{id,name,address,service,status,lng,lat,createdAt}]}（八键，与 entity/Store 的 D-23 门禁断言同口径）；status=closed 前端置灰不可选。
 */
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    @Autowired
    private StoreService storeService;

    /** BE-8（F-18.20 G 行）：匿名 GET 挂读档限流 30/分/IP（RF 纪律：新端点一律挂限流注解，读 30/写 10 每分） */
    @GetMapping
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)
    public R<List<Store>> list() {
        return R.ok(storeService.listAll());
    }
}
