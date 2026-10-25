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

import java.util.List;

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
     * F-42b 收信封：手工 Map → R.ok(list)；原顶层 total（=list.size()，零信息量）一并去掉，
     * data 仍是数组本体（不包成 {list,total}，否则 C 端 decorateDishListResponse 的
     * Array.isArray(res.data) 判据失效）。
     */
    @GetMapping
    public R<List<Dish>> getDishes(@RequestParam(required = false) String category,
                                   @RequestParam(required = false) String keyword) {
        return R.ok(dishService.getDishes(category, keyword));
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

    /**
     * 菜品详情（F-42b 收信封）：成功支 R.ok(dish)；不存在支走 R 的 404 工厂 R.fail(CODE_NOT_FOUND, ...)，
     * 与 401/403/429/500 同一条信封构造路径 —— 出参显式带 data:null（不是缺键），见 R 的字段声明顺序
     * 与 f28 门禁「401 仍显式带 data:null（不是缺键）」同源判据。
     */
    @GetMapping("/{id}")
    public R<Dish> getDishDetail(@PathVariable Long id) {
        Dish dish = dishService.getDishById(id);
        if (dish != null) {
            // F-116a 出参收口（契约复核算子1）：auditReason 置 null 后出参——驳回理由只给商家端链路
            // （商家端走同一实体出参，需要看到理由），C 端详情直链不下发运营内部审核意见；
            // auditStatus 保留，前端可用它渲染下架态。不能用 @JsonIgnore：那会把商家端的理由展示一起抹掉。
            // C 端列表/秒杀/推荐的出参路径驳回品本就不可见（读路径已过滤），且非驳回品 audit_reason 恒 NULL，无泄露面。
            dish.setAuditReason(null);
            return R.ok(dish);
        }
        return R.fail(R.CODE_NOT_FOUND, "菜品不存在");
    }
}
