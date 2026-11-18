package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.mapper.DishMapper;
import com.fresh.service.DishService;
import com.fresh.service.RecommendService;
import com.fresh.service.SeckillSessionResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
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

    /** P11-4 片① D2 秒杀窗口判定源（@Autowired 照 OrderServiceImpl 先例）：与下单计价
     *  （OrderServiceImpl#createOrder）/ 秒杀卡出参（SeckillController#current）共用同一判定，
     *  杜绝三处口径漂移——展示态与计价态永远一致，深夜不出现「页面显秒价、下单按原价」价差感 */
    @Autowired
    private SeckillSessionResolver seckillSessionResolver;

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
        // category 与 keyword 组合语义不变（F-03.2⑤）：category 等值条件继续叠加在命中集合上；
        // F-116 §2.1：C 端可见性恒 audit_status='ok'（运营驳回品即刻从搜索命中里消失，与列表同口径）
        List<Dish> dishes = dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .in(Dish::getId, mergedIds)
                .eq(hasCategory, Dish::getCategory, category)
                .eq(Dish::getAuditStatus, Dish.AUDIT_OK));
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
        return decorateSeckillWindow(dishes, seckillSessionResolver);
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
        // F-116 §2.1：全量不含审核驳回品——C 端可见性恒 audit_status='ok'（下架品照旧出现，现状不变）
        wrapper.eq(Dish::getAuditStatus, Dish.AUDIT_OK);
        wrapper.orderByDesc(Dish::getId);
        return decorateSeckillWindow(dishMapper.selectList(wrapper), seckillSessionResolver);
    }

    @Override
    public Dish getDishById(Long id) {
        Dish dish = dishMapper.selectById(id);
        if (dish != null) {
            // P11-4 片① D2：详情出参同点装饰（与列表同一采样口径）；非秒杀件装饰器内跳过、两键不出键
            decorateSeckillWindow(List.of(dish), seckillSessionResolver);
        }
        return dish;
    }

    @Override
    public RecommendVO recommend(Long userId, Long excludeId, Integer limit) {
        // F-02 破坏性升级：推荐算法整体移交 RecommendService（五信号加权打分，见 F-02.1），
        // 本类仅做委托转发；路由与参数不变，userId 由 Controller 按 token 身份传入（可空=游客）
        return recommendService.recommend(userId, excludeId, limit);
    }

    @Override
    public List<Dish> listSeckill() {
        // T-M4-03 秒杀场商品集（契约第 3 条）：打了秒杀标、且在售/有库存/已填秒杀价的商品，id 升序稳定输出；
        // 响应直接携带 DB 真实字段（seckill/seckillPrice/soldCount/limitBuy/tags/goodRate，JSON 驼峰）
        return dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getSeckill, 1)
                // 只按秒杀标收录会把下架件塞进首页秒杀卡，用户点进去必吃一个 400
                .eq(Dish::getOnSale, 1)
                // F-116 §2.1：运营驳回件同样不进秒杀卡（与三条件过滤同位；详情直链/下单拦截另有对齐口径）
                .eq(Dish::getAuditStatus, Dish.AUDIT_OK)
                // stock 每笔下单都经 DishMapper#decreaseStock 扣减，卖空是必然事件而非假设
                .gt(Dish::getStock, 0)
                // 计价判据是 seckill==1 且 seckillPrice 非空（OrderServiceImpl#createOrder）：只打标不写价的行会在秒杀卡里打秒杀标却按原价收款
                .isNotNull(Dish::getSeckillPrice)
                .orderByAsc(Dish::getId));
    }

    /**
     * P11-4 片① D2/D3：C 端列表/详情出参窗口态装饰——秒杀件（dish.seckill==1）下发
     * seckillActive（{@link SeckillSessionResolver#inWindow} 服务端时钟判定，单一真源）与
     * seckillStartAt（resolver.sessionTime("start", 默认)，「HH:mm」）；非秒杀件两键保持 null，
     * 经字段级 {@code @JsonInclude(NON_NULL)} 不出键（出参与本片改动前逐字相同）。
     * 两键在窗口采样点一次算出后循环赋值（同一请求同一时刻口径），字典读走 resolver 内
     * 本地优先 + Caffeine 缓存，批量装饰无额外字典压力。
     *
     * <p>D5 实时性口径（契约登记）：窗口态在本装饰执行时刻（出参组装时）一次采样，页面停留
     * 跨窗口切换不实时翻转——刷新/onShow 重取出参后生效。前端不自判窗口，避免两端时钟/
     * 字典不同步。</p>
     *
     * <p>F-112 契约 §1.3 红线关系：{@link #listSeckill} 三条件收录口径与本装饰无关、零改动
     * （秒杀卡项级窗口态由 SeckillController#current 直接填 active 键，不走本装饰）；
     * 商家端/运营端出参不经本装饰，无新键。推荐出参自 P11-6 I2 起在候选集首算阶段经本装饰
     * （RecommendServiceImpl 注入 resolver 调本静态实现，键值随首算采样写入其 60s 结果缓存，
     * 缓存命中回包不二次装饰——D5 一次采样口径的自然延长）。</p>
     *
     * <p>P11-6 I5：likeSearch 是 LIKE 回退路径的<b>唯一</b>装饰层（外层四条路径的同名包裹
     * 已删除，避免同请求两次 LocalTime 采样违反「一次采样」口径）；调用方传 resolver 而非读
     * 实例字段，使 RecommendServiceImpl 无需注入 DishService 即可复用同一实现——
     * 否则 DishService↔RecommendService 构成循环依赖（Boot 3.2 默认禁循环引用，启动即挂）。</p>
     */
    static List<Dish> decorateSeckillWindow(List<Dish> dishes, SeckillSessionResolver resolver) {
        boolean inWindow = resolver.inWindow(LocalTime.now());
        String startAt = resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START);
        for (Dish d : dishes) {
            if (d != null && Integer.valueOf(1).equals(d.getSeckill())) {
                d.setSeckillActive(inWindow);
                d.setSeckillStartAt(startAt);
            }
        }
        return dishes;
    }
}
