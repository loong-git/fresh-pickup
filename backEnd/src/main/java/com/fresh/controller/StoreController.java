package com.fresh.controller;

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
 * 响应 R{code, data:[{id,name,address,service,status,createdAt}]}；status=closed 前端置灰不可选。
 */
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    @Autowired
    private StoreService storeService;

    @GetMapping
    public R<List<Store>> list() {
        return R.ok(storeService.listAll());
    }
}
