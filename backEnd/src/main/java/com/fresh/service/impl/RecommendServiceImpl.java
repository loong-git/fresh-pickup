package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.RecommendService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐服务实现（F-02.1 算法定稿）：五信号加权打分，纯内存计算、无新表、全程确定性可复现。
 *
 * 约束依据（docs/上线任务文档.md）：
 * - F-02.1 候选集：on_sale=1 硬过滤 + excludeId 硬剔除；
 * - F-02.1 打分：score = Σ wi×si ∈ [0,1]，五信号权重 s1=0.35 / s2=0.25 / s3=0.20 / s4=0.10 / s5=0.10；
 * - F-02.1 降权：候选 ∈ 用户已购 D → score×0.5（复购降权不剔除）；
 * - F-02.1 打散：score 降序后贪心扫描，同 category 连续 &gt;2 与下一个异类目项交换（尾段不足则保持）；
 * - F-02.1 tiebreak：score desc → sold_count desc → id asc，禁止 Random/时间参与排序；
 * - F-02.1 策略标签：用户无任何订单 → 跳过 s1/s2，strategy="best-seller"；否则 "personalized"；
 * - F-02.4 边界：28 商品量级下每请求实时计算可接受（全量订单明细/商品映射直接内存聚合），
 *   数据量增长后再引入缓存；评分仅取过审评论，与线上展示口径一致。
 *
 * 确定性保证：排序仅依赖 Comparator 链（score/sold_count/id），无 Random、无时间源，
 * 同输入（同用户数据 + 同参数）两次调用输出全等（F-02.2 单测断言、F-02.3 回归场景 3）。
 */
@Service
public class RecommendServiceImpl implements RecommendService {

    /** 推荐默认条数（F-02.1"返回 limit 截断（默认 5...）"，与旧 recommend 行为一致） */
    private static final int DEFAULT_LIMIT = 5;

    /** T-M3-13 推荐条数上界：防大 limit 拖库（F-02.1"上限沿用 MAX_RECOMMEND_LIMIT"） */
    private static final int MAX_RECOMMEND_LIMIT = 20;

    // F-02.1 五信号权重（score = Σ wi×si ∈ [0,1]；personalized 全权重和恰为 1，
    // best-seller 跳过 s1/s2 后剩余权重和 0.4，score 上界仍 ≤1）
    /** s1 类目偏好权重 w1=0.35（F-02.1 信号表） */
    private static final double W1_CATEGORY = 0.35;
    /** s2 协同分权重 w2=0.25（F-02.1 信号表） */
    private static final double W2_COLLABORATIVE = 0.25;
    /** s3 热度权重 w3=0.20（F-02.1 信号表） */
    private static final double W3_POPULARITY = 0.20;
    /** s4 运营位权重 w4=0.10（F-02.1 信号表） */
    private static final double W4_OPERATION = 0.10;
    /** s5 口碑权重 w5=0.10（F-02.1 信号表） */
    private static final double W5_REVIEW = 0.10;

    /** 无信号中性值（F-02.1：s1 游客无订单 0.5 / s3 全体最大销量为 0 时全 0.5 / s5 无过审评论 0.5） */
    private static final double NEUTRAL = 0.5;

    /** F-02.1 降权系数：候选 ∈ 用户已购 D → score×0.5（复购降权不剔除） */
    private static final double PURCHASED_DECAY = 0.5;

    /** F-02.1 打散上限：同 category 连续至多 2 项 */
    private static final int MAX_SAME_CATEGORY_RUN = 2;

    /** T-M3-12 字典 11 类：s1 拉普拉斯平滑分母 +11（F-02.1"(cnt+1)/(total+11类数)"） */
    private static final int CATEGORY_DICT_SIZE = 11;

    private final DishMapper dishMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ReviewMapper reviewMapper;

    /** 构造器注入：四个 mapper 全部 mock 友好（RecommendServiceTest 直接 new） */
    public RecommendServiceImpl(DishMapper dishMapper, OrderMapper orderMapper,
                                OrderItemMapper orderItemMapper, ReviewMapper reviewMapper) {
        this.dishMapper = dishMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.reviewMapper = reviewMapper;
    }

    /** 打分中间载体：score 仅供排序，不外泄（F-02.2 响应只含 strategy + items） */
    private record Scored(Dish dish, double score) {
    }

    @Override
    public RecommendVO recommend(Long userId, Long excludeId, Integer limit) {
        // F-02.1：limit 默认 5、上限 MAX_RECOMMEND_LIMIT（沿用旧 recommend 的钳制口径）
        int n = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_RECOMMEND_LIMIT);

        // F-02.1 候选集：on_sale=1 硬过滤 + excludeId 硬剔除（SQL 层表达）。
        // 查询返回后再做一次内存兜底过滤（幂等）：防脏数据（on_sale 非 0/1），并保证单测 mock 路径下
        // 过滤语义同样成立（mock 不执行 SQL 条件）。
        List<Dish> candidates = new ArrayList<>(dishMapper.selectList(new LambdaQueryWrapper<Dish>()
                .eq(Dish::getOnSale, 1)
                .ne(excludeId != null && excludeId > 0, Dish::getId, excludeId)));
        candidates.removeIf(d -> d.getOnSale() == null || d.getOnSale() != 1
                || (excludeId != null && excludeId > 0 && excludeId.equals(d.getId())));
        if (candidates.isEmpty()) {
            return RecommendVO.of(RecommendVO.STRATEGY_BEST_SELLER, new ArrayList<>());
        }

        // —— 用户历史信号加载（游客不查订单表，F-02.2：个性化一律按 token 身份，匿名=游客）——
        List<OrderItem> userItems = List.of();
        boolean personalized = false;
        if (userId != null) {
            List<Order> userOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId));
            if (!userOrders.isEmpty()) {
                List<String> orderIds = userOrders.stream().map(Order::getId).toList();
                userItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds));
                // F-02.1 策略判定：用户存在任何订单明细 → personalized；无订单明细（含有单无明细的脏数据）
                // → best-seller 冷启动，跳过 s1/s2
                personalized = !userItems.isEmpty();
            }
        }

        // s1 输入：用户已购 dishId 集合 D（F-02.1 s2 的 D 即此集合）与明细按类目聚合计数
        Set<Long> purchased = new HashSet<>();
        Map<String, Long> categoryCount = new HashMap<>();
        long historyTotal = 0;
        Map<Long, String> dishCategory = new HashMap<>();
        Map<Long, Map<Long, Integer>> cooccurrence = new HashMap<>();
        if (personalized) {
            // s1 需要"用户买过的全部 dish"的类目（可能含已下架/被 excludeId 排除的商品），
            // 按 ids 点查建映射（F-02.4：28 商品量级实时计算可接受）
            Set<Long> userDishIds = userItems.stream()
                    .map(OrderItem::getDishId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!userDishIds.isEmpty()) {
                dishMapper.selectBatchIds(userDishIds).forEach(d -> dishCategory.put(d.getId(), d.getCategory()));
            }
            // F-02.1 s1：用户全部订单明细按 dish.category 聚合占比，拉普拉斯平滑 (cnt+1)/(total+11类数)。
            // 明细行 dishId 查不到类目（脏数据）时整行不计入 cnt/total，避免占比失真
            for (OrderItem it : userItems) {
                if (it.getDishId() == null) {
                    continue;
                }
                purchased.add(it.getDishId());
                String cat = dishCategory.get(it.getDishId());
                if (cat != null) {
                    categoryCount.merge(cat, 1L, Long::sum);
                    historyTotal++;
                }
            }
            // F-02.1 s2：item-CF 共现矩阵 co(d,c)=同 order_id 共现次数（订单内 dish 对、行去重），
            // 基于全体订单明细统计（selectList(null)=全表）；F-02.4 认可该量级下每请求实时计算
            orderItemMapper.selectList(null).stream()
                    .filter(it -> it.getOrderId() != null && it.getDishId() != null)
                    .collect(Collectors.groupingBy(OrderItem::getOrderId))
                    .values()
                    .forEach(items -> {
                        List<Long> dishIds = items.stream().map(OrderItem::getDishId).distinct().toList();
                        for (int i = 0; i < dishIds.size(); i++) {
                            for (int j = 0; j < dishIds.size(); j++) {
                                if (i != j) {
                                    cooccurrence.computeIfAbsent(dishIds.get(i), k -> new HashMap<>())
                                            .merge(dishIds.get(j), 1, Integer::sum);
                                }
                            }
                        }
                    });
        }

        // F-02.1 s2 两步：先对每个候选算原始共现分 Σ_{d∈D} co(d,c)（c∈D 不计，复购由 ×0.5 表达），
        // 再按候选集内最大共现分 log1p 归一化。共现全 0 → s2=0：分子本身为 0（无协同信号），
        // 与 s3"最大为 0 时全 0.5"不同——s2 文档只规定了"D 空=0.5"，而 personalized 下 D 必非空
        Map<Long, Integer> rawCf = new HashMap<>();
        if (personalized) {
            for (Dish c : candidates) {
                if (purchased.contains(c.getId())) {
                    continue; // F-02.1：c∉D 才计
                }
                int sum = 0;
                for (Long d : purchased) {
                    Map<Long, Integer> row = cooccurrence.get(d);
                    if (row != null) {
                        sum += row.getOrDefault(c.getId(), 0);
                    }
                }
                rawCf.put(c.getId(), sum);
            }
        }
        int maxCf = rawCf.values().stream().max(Integer::compare).orElse(0);

        // F-02.1 s5 输入：候选的过审评论按 dish 聚合 rating 均值。
        // 口径裁定：文档字面写"audit_status=approved"，但本系统审核流不存在 'approved' 值——
        // 评价正常入库为 NULL、软词命中为 pending（ReviewServiceImpl:98-103）、管理端过审置 NULL、
        // 驳回物理删除（AdminServiceImpl:134-145）。按 F-02.4"评分仅取过审评论、与线上展示口径一致"，
        // 裁定为"audit_status 非 pending"，与评价列表过滤（ReviewServiceImpl:44-45）及
        // 好评率回写（DishMapper#refreshGoodRate）同口径；无过审评论 → 0.5 中性
        Map<Long, Double> reviewScore = new HashMap<>();
        List<Long> candidateIds = candidates.stream().map(Dish::getId).toList();
        reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                        .in(Review::getDishId, candidateIds)
                        .and(w -> w.isNull(Review::getAuditStatus).or().ne(Review::getAuditStatus, Review.AUDIT_PENDING)))
                .stream()
                // 内存兜底过滤 pending（幂等）：SQL 条件在 mock 单测路径下不执行，
                // 与候选集 on_sale 兜底过滤同模式，保证两条路径"仅取过审评论"口径一致
                .filter(r -> r.getDishId() != null && r.getRating() != null
                        && !Review.AUDIT_PENDING.equals(r.getAuditStatus()))
                .collect(Collectors.groupingBy(Review::getDishId, Collectors.summarizingInt(Review::getRating)))
                .forEach((dishId, stat) -> {
                    // 防御 rating 脏值越界，均值钳制到 [0,5] 后 /5 归一到 [0,1]
                    double avg = Math.max(0, Math.min(stat.getAverage(), 5));
                    reviewScore.put(dishId, avg / 5.0);
                });

        // F-02.1 s3 归一化分母：候选集内最大 sold_count；最大为 0 时全 0.5 中性
        int maxSold = candidates.stream()
                .mapToInt(d -> d.getSoldCount() == null ? 0 : d.getSoldCount())
                .max().orElse(0);
        double logMaxSold = Math.log1p(maxSold);

        // —— 逐候选打分：score = Σ wi×si ——
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Dish d : candidates) {
            // s3 热度（F-02.1）：log1p(sold_count)/log1p(全体最大 sold_count)，最大为 0 时全 0.5
            double s3 = maxSold <= 0 ? NEUTRAL
                    : Math.log1p(d.getSoldCount() == null ? 0 : d.getSoldCount()) / logMaxSold;
            // s4 运营位（F-02.1）：seckill=1 → 1，否则 0
            double s4 = d.getSeckill() != null && d.getSeckill() == 1 ? 1.0 : 0.0;
            // s5 口碑（F-02.1）：过审评论 rating 均值/5；无过审评论 = 0.5 中性
            double s5 = reviewScore.getOrDefault(d.getId(), NEUTRAL);
            double score;
            if (personalized) {
                // s1 类目偏好（F-02.1）：拉普拉斯平滑 (cnt+1)/(total+11类数)；
                // best-seller 分支不出现 s1，故游客的 0.5 中性值仅存于文档语义，不需计算
                double s1 = (categoryCount.getOrDefault(d.getCategory(), 0L) + 1)
                        / (double) (historyTotal + CATEGORY_DICT_SIZE);
                // s2 协同分（F-02.1）：log1p(共现分)/log1p(最大共现)；共现全 0 → 0
                double s2 = maxCf <= 0 ? 0.0
                        : Math.log1p(rawCf.getOrDefault(d.getId(), 0)) / Math.log1p(maxCf);
                score = W1_CATEGORY * s1 + W2_COLLABORATIVE * s2
                        + W3_POPULARITY * s3 + W4_OPERATION * s4 + W5_REVIEW * s5;
            } else {
                // F-02.1：用户无任何订单 → 跳过 s1/s2（best-seller 冷启动，两信号不参与加权）
                score = W3_POPULARITY * s3 + W4_OPERATION * s4 + W5_REVIEW * s5;
            }
            if (purchased.contains(d.getId())) {
                // F-02.1 降权：候选 ∈ 用户已购 D → score×0.5（复购降权不剔除）
                score *= PURCHASED_DECAY;
            }
            scored.add(new Scored(d, score));
        }

        // F-02.1 tiebreak（确定性）：score desc → sold_count desc → id asc；
        // 禁止 Random/时间参与排序（全程无时钟、无随机源，同输入同输出）
        Comparator<Scored> byRank = Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.dish().getSoldCount() == null ? 0 : s.dish().getSoldCount(),
                        Comparator.reverseOrder())
                .thenComparing(s -> s.dish().getId());
        scored.sort(byRank);

        // F-02.1 打散：score 降序序列上贪心扫描，同 category 连续 >2 时
        // 将当前项与下一个异类目项交换；尾段无可用异类目则保持原位（近似打散，允许残留）
        for (int i = MAX_SAME_CATEGORY_RUN; i < scored.size(); i++) {
            String cur = scored.get(i).dish().getCategory();
            String prev1 = scored.get(i - 1).dish().getCategory();
            String prev2 = scored.get(i - 2).dish().getCategory();
            if (cur != null && cur.equals(prev1) && cur.equals(prev2)) {
                int j = i + 1;
                while (j < scored.size() && cur.equals(scored.get(j).dish().getCategory())) {
                    j++;
                }
                if (j < scored.size()) {
                    Collections.swap(scored, i, j);
                }
            }
        }

        List<Dish> items = scored.stream().map(Scored::dish).limit(n).collect(Collectors.toList());
        String strategy = personalized ? RecommendVO.STRATEGY_PERSONALIZED : RecommendVO.STRATEGY_BEST_SELLER;
        return RecommendVO.of(strategy, items);
    }
}
