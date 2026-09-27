package com.fresh.service;

import com.fresh.dto.RecommendVO;

/**
 * 推荐服务（迭代任务 F-02）：多信号加权排序，纯内存计算、无新表。
 * 算法定稿见 docs/上线任务文档.md F-02.1；接口契约见 F-02.2。
 */
public interface RecommendService {

    /**
     * 推荐：对在售商品（on_sale=1，剔除 excludeId）按五信号加权打分排序。
     *
     * @param userId    当前登录用户ID；null=游客（走 best-seller 冷启动，个性化一律按 token 身份，
     *                  由 Controller 从 AuthInterceptor 注入的 request attribute 读取，F-02.2 不接受调试参数）
     * @param excludeId 需排除的商品ID（详情页"看了又看"场景排除当前商品），可空
     * @param limit     返回条数，默认 5，上限沿用 MAX_RECOMMEND_LIMIT（T-M3-13）
     * @return strategy（personalized/best-seller）+ items
     */
    RecommendVO recommend(Long userId, Long excludeId, Integer limit);
}
