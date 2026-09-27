package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.mapper.DishMapper;
import com.fresh.service.DishService;
import com.fresh.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private RecommendService recommendService;

    @Override
    public List<Dish> getDishes(String category, String keyword) {
        boolean hasCategory = category != null && !category.isEmpty();
        String kw = keyword == null ? "" : keyword.trim();
        // F-03.2⑤：空白 keyword 行为完全不变——不加 keyword 条件，仅 category 等值过滤全量（T-M3-12）
        if (kw.isEmpty()) {
            return likeSearch(hasCategory, category, kw);
        }
        // F-03.2①：单字保持原 LIKE(name) 路径——ngram_token_size 默认 2，单字切不出 token，
        // MATCH 对单字必然 0 命中，FULLTEXT 路径不可用，必须 LIKE 兜底
        if (kw.length() == 1) {
            return likeSearch(hasCategory, category, kw);
        }
        // F-03.2②：≥2 字 → FULLTEXT 三列 MATCH（m10 索引 ft_dish_search）∪ 别名精确命中
        // BOOLEAN MODE 中 + - > < ( ) ~ * " 是检索运算符，用户输入携带会改变匹配语义（如「-土豆」变成排除式检索），
        // 故先剔除再参与 MATCH（只影响 MATCH 词；别名等值与回退 LIKE 一律用原始 kw）；剔除后为空按 0 命中处理 → 走回退
        String matchKw = kw.replaceAll("[+\\-<>()~*\"]", "").trim();
        Map<Long, Double> scoreById = new LinkedHashMap<>();
        if (!matchKw.isEmpty()) {
            for (Map<String, Object> row : dishMapper.selectIdsByFulltext(matchKw)) {
                scoreById.put(((Number) row.get("id")).longValue(), ((Number) row.get("score")).doubleValue());
            }
        }
        // 别名命中：dish_alias.alias 精确等值（如 洋芋→高山土豆）得 dish_id 集合；与 MATCH 命中并集去重后
        // 一次性 IN 取行（沿用 MP 标准查询，tags 的 autoResultMap 映射不受自定义 SQL 影响）
        List<Long> aliasIds = dishMapper.selectDishIdsByAlias(kw);
        if (scoreById.isEmpty() && aliasIds.isEmpty()) {
            // F-03.2③：MATCH+别名均 0 命中 → 回退原 LIKE(name)，召回不得低于现状（向后兼容铁律）
            return likeSearch(hasCategory, category, kw);
        }
        Set<Long> aliasSet = new HashSet<>(aliasIds);
        Set<Long> mergedIds = new LinkedHashSet<>(scoreById.keySet());
        mergedIds.addAll(aliasIds);
        // category 与 keyword 组合语义不变（F-03.2⑤）：category 等值条件继续叠加在命中集合上
        List<Dish> dishes = dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .in(Dish::getId, mergedIds)
                .eq(hasCategory, Dish::getCategory, category));
        if (dishes.isEmpty()) {
            // 命中集合被 category 过滤后为空（该类目下无 FULLTEXT/别名命中）→ 同样回退 LIKE，维持现状召回
            return likeSearch(hasCategory, category, kw);
        }
        // F-03.2④ 排序：别名命中无相关性分——它是人工维护的强意图映射（alias 等值指向唯一商品），
        // 并入方式=整体视作最高优先级组排最前、组内 id desc；其余 MATCH 命中按相关性分 desc、同分 id desc
        dishes.sort((a, b) -> {
            boolean aAlias = aliasSet.contains(a.getId());
            boolean bAlias = aliasSet.contains(b.getId());
            if (aAlias != bAlias) {
                return aAlias ? -1 : 1;
            }
            if (!aAlias) {
                int byScore = Double.compare(
                        scoreById.getOrDefault(b.getId(), 0D), scoreById.getOrDefault(a.getId(), 0D));
                if (byScore != 0) {
                    return byScore;
                }
            }
            return Long.compare(b.getId(), a.getId());
        });
        return dishes;
    }

    /**
     * 原 LIKE(name) 路径（F-03 前现状，T-M3-02）：keyword 非空对 name 模糊匹配，与 category 等值组合生效，
     * id desc 稳定输出；空白 keyword 全量、单字兜底（F-03.2①）、0 命中回退（F-03.2③）三条路径共用本方法。
     */
    private List<Dish> likeSearch(boolean hasCategory, String category, String kw) {
        LambdaQueryWrapper<Dish> wrapper = new LambdaQueryWrapper<>();
        // T-M3-12：category 对齐字典 11 类 code，传值直接等值过滤
        wrapper.eq(hasCategory, Dish::getCategory, category);
        wrapper.like(!kw.isEmpty(), Dish::getName, kw);
        // 仅推在售商品由 recommend 控制；列表保持全量（下架商品详情页仍可访问由 onSale 标记）
        wrapper.orderByDesc(Dish::getId);
        return dishMapper.selectList(wrapper);
    }

    @Override
    public Dish getDishById(Long id) {
        return dishMapper.selectById(id);
    }

    @Override
    public RecommendVO recommend(Long userId, Long excludeId, Integer limit) {
        // F-02 破坏性升级：推荐算法整体移交 RecommendService（五信号加权打分，见 F-02.1），
        // 本类仅做委托转发；路由与参数不变，userId 由 Controller 按 token 身份传入（可空=游客）
        return recommendService.recommend(userId, excludeId, limit);
    }

    @Override
    public List<Dish> listSeckill() {
        // T-M4-03 秒杀场商品集（契约第 3 条）：seckill=1 的商品，id 升序稳定输出；
        // 响应直接携带 DB 真实字段（seckill/seckillPrice/soldCount/limitBuy/tags/goodRate，JSON 驼峰）
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getSeckill, 1)
                .orderByAsc(Dish::getId));
    }
}
