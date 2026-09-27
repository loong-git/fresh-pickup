package com.fresh.dto;

import com.fresh.entity.Dish;
import lombok.Data;

import java.util.List;

/**
 * 推荐接口响应体（F-02.2 接口契约，破坏性升级）：
 * 原 GET /api/dishes/recommend 返回裸 List&lt;Dish&gt;，升级为 {strategy, items} 信封内结构，
 * strategy 标注本次推荐采用的策略，前端按 items 取列表（detail.vue loadRecommend 同步改取 res.data.items）。
 */
@Data
public class RecommendVO {

    /** 策略标签-个性化（F-02.1：用户存在任何订单明细，s1/s2 参与打分） */
    public static final String STRATEGY_PERSONALIZED = "personalized";

    /** 策略标签-热销兜底（F-02.1：游客或用户无任何订单 → 跳过 s1/s2 冷启动） */
    public static final String STRATEGY_BEST_SELLER = "best-seller";

    /** 本次推荐策略：personalized / best-seller */
    private String strategy;

    /** 推荐商品列表（已按 limit 截断，字段与 Dish 实体一致，JSON 驼峰） */
    private List<Dish> items;

    public static RecommendVO of(String strategy, List<Dish> items) {
        RecommendVO vo = new RecommendVO();
        vo.strategy = strategy;
        vo.items = items;
        return vo;
    }
}
