package com.fresh.controller;

import com.fresh.common.R;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品接口（匿名可读，权限矩阵见 AuthInterceptor）。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/dishes")
public class DishController {

    @Autowired
    private DishService dishService;

    /**
     * 商品列表：category（字典 11 类 code）与 keyword（名称模糊）可独立可组合（T-M3-02/T-M3-12）。
     */
    @GetMapping
    public Map<String, Object> getDishes(@RequestParam(required = false) String category,
                                         @RequestParam(required = false) String keyword) {
        List<Dish> list = dishService.getDishes(category, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        result.put("total", list.size());
        return result;
    }

    /**
     * 精选推荐（F-02 破坏性升级）：五信号加权排序（类目偏好/协同/热度/运营位/口碑，
     * 算法定稿见 docs/上线任务文档.md F-02.1），响应升级为 R&lt;{strategy, items}&gt;（F-02.2，原为裸数组）。
     * 个性化一律按 token 身份：AuthInterceptor 对匿名路径（GET /api/dishes**）带有效 token 时
     * 注入 ATTR_USER_ID，无 token/无效 token 不注入 → userId=null → 游客 best-seller 冷启动；
     * 不设 phone 等调试参数（F-02.2 明确不带）。路由与参数不变（excludeId/limit）。
     * 注意路由顺序：Spring 精确路径 /recommend 优先于 /{id} 匹配，无冲突。
     */
    @GetMapping("/recommend")
    public R<RecommendVO> recommend(@RequestParam(required = false) Long excludeId,
                                    @RequestParam(defaultValue = "5") Integer limit,
                                    @RequestAttribute(value = AuthInterceptor.ATTR_USER_ID, required = false) Long userId) {
        return R.ok(dishService.recommend(userId, excludeId, limit));
    }

    @GetMapping("/{id}")
    public Map<String, Object> getDishDetail(@PathVariable Long id) {
        Dish dish = dishService.getDishById(id);
        Map<String, Object> result = new HashMap<>();
        if (dish != null) {
            result.put("code", 200);
            result.put("data", dish);
        } else {
            result.put("code", 404);
            result.put("message", "菜品不存在");
        }
        return result;
    }
}
