package com.fresh.controller;

import com.fresh.common.R;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminOrderStatusDTO;
import com.fresh.dto.AdminReviewAuditDTO;
import com.fresh.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端薄版（T-M3-09）：
 * - 鉴权：X-Admin-Key 请求头校验，key 读环境变量 ADMIN_KEY（@Value 占位符默认 admin-dev-key，
 *   生产由环境变量覆盖）；缺失/错误 → 统一信封 401（HTTP 恒 200，码经 body.code 携带）。
 *   AuthInterceptor 权限矩阵不含 /api/admin/**，此处自行校验，不走 JWT。
 * - 仅三个运营端点：订单核销/取消、商品改价/补库存/上下架（部分更新）、评价审核（T-M4-05 收口：
 *   命中软词 pending 的评价人工过审/驳回，否则列表默认不展示、永久不可见）。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 管理 key：环境变量 ADMIN_KEY 优先，缺省 admin-dev-key（仅 dev） */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    @Autowired
    private AdminService adminService;

    /** 订单核销/取消：PUT /api/admin/orders/{id}/status，body {status}（completed/cancelled） */
    @PutMapping("/orders/{id}/status")
    public R<Void> updateOrderStatus(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @PathVariable("id") String id,
                                     @Valid @RequestBody AdminOrderStatusDTO dto) {
        R<Void> denied = checkKey(key);
        if (denied != null) {
            return denied;
        }
        adminService.updateOrderStatus(id, dto.getStatus());
        return R.ok(null, "订单状态已更新");
    }

    /** 商品改价/补库存/上下架：PUT /api/admin/dishes/{id}，body {price?, stock?, onSale?} 部分更新 */
    @PutMapping("/dishes/{id}")
    public R<Void> updateDish(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @PathVariable Long id,
                              @Valid @RequestBody AdminDishUpdateDTO dto) {
        R<Void> denied = checkKey(key);
        if (denied != null) {
            return denied;
        }
        adminService.updateDish(id, dto);
        return R.ok(null, "商品已更新");
    }

    /** 评价审核：PUT /api/admin/reviews/{id}/audit，body {action}（approve 过审展示 / reject 驳回删除） */
    @PutMapping("/reviews/{id}/audit")
    public R<Void> auditReview(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @PathVariable Long id,
                               @Valid @RequestBody AdminReviewAuditDTO dto) {
        R<Void> denied = checkKey(key);
        if (denied != null) {
            return denied;
        }
        adminService.auditReview(id, dto.getAction());
        return R.ok(null, "评价已审核");
    }

    /** key 校验：通过返回 null；缺失/错误返回 401 信封（与 AuthInterceptor 同一套码表文案风格） */
    private R<Void> checkKey(String key) {
        if (key == null || key.isBlank() || !key.equals(adminKey)) {
            return R.fail(R.CODE_UNAUTHORIZED, "管理密钥缺失或错误");
        }
        return null;
    }
}
