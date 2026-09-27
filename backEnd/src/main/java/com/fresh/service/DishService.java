package com.fresh.service;

import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import java.util.List;

public interface DishService {

    /**
     * 商品列表（T-M3-02）：category 与 keyword 可独立可组合——
     * category 对齐字典 11 类 code；keyword 对名称 LIKE '%kw%' 模糊匹配。
     */
    List<Dish> getDishes(String category, String keyword);

    Dish getDishById(Long id);

    /**
     * 精选推荐（F-02 破坏性升级）：算法整体移交 RecommendService（五信号加权打分，
     * 算法定稿见 docs/上线任务文档.md F-02.1），本接口仅保留路由委托。
     *
     * @param userId    登录用户ID（token 身份，可空=游客）；个性化一律按 token，F-02.2 不接受调试参数
     * @param excludeId 需排除的商品ID，可空
     * @param limit     返回条数，默认 5，上限沿用 MAX_RECOMMEND_LIMIT
     * @return strategy（personalized/best-seller）+ items（F-02.2 契约，原为裸数组）
     */
    RecommendVO recommend(Long userId, Long excludeId, Integer limit);

    /**
     * 秒杀场商品集（T-M4-03）：seckill=1 的商品，id 升序稳定输出，
     * 直接携带 DB 真实字段（seckillPrice/soldCount/limitBuy/tags/goodRate）。
     */
    List<Dish> listSeckill();
}
