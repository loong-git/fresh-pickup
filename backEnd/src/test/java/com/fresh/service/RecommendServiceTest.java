package com.fresh.service;

import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.impl.RecommendServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 推荐算法单测（F-02.2）：Mockito mock 四个 mapper 固定 fixture，纯内存校验 RecommendServiceImpl。
 * 覆盖 F-02.2 列出的七类断言：
 * ① 冷启动策略标签与热销排序；② 个性化改变排序；③ excludeId 剔除；④ limit 截断；
 * ⑤ 同输入同输出（确定性）；⑥ 已购 ×0.5；⑦ 同品类连续 ≤2。
 * 另附：口碑信号仅取过审评论、on_sale=0 过滤（F-02.1 候选集口径）。
 * 期望序列均按 F-02.1 公式手工推算（推算过程见各测试注释），不走数据库、不起 Spring 容器。
 */
class RecommendServiceTest {

    private static final Long USER_ID = 7L;

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);

    private RecommendServiceImpl recommendService;

    @BeforeEach
    void setUp() {
        recommendService = new RecommendServiceImpl(dishMapper, orderMapper, orderItemMapper, reviewMapper);
    }

    // —— fixture 工具 ——

    private Dish dish(long id, String category, int soldCount, int seckill) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("D" + id);
        d.setCategory(category);
        d.setSoldCount(soldCount);
        d.setSeckill(seckill);
        d.setOnSale(1);
        return d;
    }

    private Order order(String id, Long userId) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        return o;
    }

    private OrderItem item(String orderId, long dishId) {
        OrderItem it = new OrderItem();
        it.setOrderId(orderId);
        it.setDishId(dishId);
        it.setQuantity(1);
        return it;
    }

    private Review review(long dishId, int rating, String auditStatus) {
        Review r = new Review();
        r.setDishId(dishId);
        r.setRating(rating);
        r.setAuditStatus(auditStatus);
        return r;
    }

    private List<Long> ids(RecommendVO vo) {
        return vo.getItems().stream().map(Dish::getId).toList();
    }

    private void stubDishes(List<Dish> dishes) {
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(dishes);
    }

    /** mock 候选查询/用户订单/用户明细共用的 wrapper 匹配器（消除 raw type 警告） */
    @SuppressWarnings("unchecked")
    private <T> com.baomidou.mybatisplus.core.conditions.Wrapper<T> anyWrapper() {
        return (com.baomidou.mybatisplus.core.conditions.Wrapper<T>) org.mockito.ArgumentMatchers.any(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
    }

    /**
     * T1 冷启动 fixture（游客场景 4 候选）：无用户历史 → score = 0.20×s3 + 0.10×s4 + 0.10×s5（s5 全 0.5）。
     * log1p100=4.61512 / log1p80=4.39445 / log1p50=3.93183：
     * id4(sold80+秒杀)=0.190422+0.1+0.05=0.340422 > id1(sold100)=0.25 > id3(sold80)=0.240422 > id2(sold50)=0.220414
     * → 期望 [4,1,3,2]（秒杀+高销第一，热度主导）。
     */
    private List<Dish> coldStartDishes() {
        return new ArrayList<>(List.of(
                dish(1, "catA", 100, 0),
                dish(2, "catB", 50, 0),
                dish(3, "catA", 80, 0),
                dish(4, "catC", 80, 1)));
    }

    // —— ① 冷启动策略标签与热销排序 ——

    @Test
    void guestBestSellerStrategyAndPopularityOrder() {
        stubDishes(coldStartDishes());
        // 游客（userId=null）不查订单表，无需 stub order 系 mapper
        RecommendVO vo = recommendService.recommend(null, null, null);
        assertEquals(RecommendVO.STRATEGY_BEST_SELLER, vo.getStrategy());
        assertEquals(List.of(4L, 1L, 3L, 2L), ids(vo));
        assertFalse(vo.getItems().isEmpty());
    }

    @Test
    void newUserWithoutOrdersAlsoBestSeller() {
        stubDishes(coldStartDishes());
        // 登录但无任何订单 → 同为 best-seller 冷启动（F-02.1：无订单跳过 s1/s2）
        when(orderMapper.selectList(anyWrapper())).thenReturn(List.of());
        RecommendVO vo = recommendService.recommend(USER_ID, null, null);
        assertEquals(RecommendVO.STRATEGY_BEST_SELLER, vo.getStrategy());
        assertEquals(List.of(4L, 1L, 3L, 2L), ids(vo));
    }

    // —— ② 个性化改变排序（s1 类目偏好 + s2 协同 + 已购降权联合生效）——

    /**
     * T2 个性化 fixture（6 候选）：用户 USER_ID 一单买了 dish10(catA)+dish3(catC)。
     * 共现（全体明细）：order_1 内 (10,3)/(3,10)，order_2 内 (2,10)/(10,2)
     * → 候选 id2 原始共现 = co(10,2)=1（d∈D={10,3}），maxCf=1 → s2(id2)=1，其余 0。
     * s1：catA/catC 各 cnt=1、total=2 → (1+1)/(2+11)=0.15385；catB=(0+1)/13=0.07692。
     * s3 分母 log1p60=4.11087：s3(id1)=0.58329、id2=1、id3=0.90335、id4=0.74063、id9/id10=0.43580。
     * personalized：id2=0.35×0.07692+0.25×1+0.2×1+0.05=0.52692（第一）；
     * id3∈D：raw 0.28452×0.5=0.14226；id10∈D：raw 0.19101×0.5=0.09551（末位）
     * → 期望 [2,4,1,9,3,10]。
     * 游客（同 fixture）：0.2×s3+0.05 → id2=0.25 > id3=0.23067 > id4=0.19813 > id1=0.16666
     * > id9=id10=0.13716（tie → id asc）→ [2,3,4,1,9,10]，两者 top 序不同。
     */
    private List<Dish> personalizedDishes() {
        return new ArrayList<>(List.of(
                dish(1, "catA", 10, 0),
                dish(2, "catB", 60, 0),
                dish(3, "catC", 40, 0),
                dish(4, "catA", 20, 0),
                dish(9, "catB", 5, 0),
                dish(10, "catA", 5, 0)));
    }

    private void stubPersonalizedHistory() {
        when(orderMapper.selectList(anyWrapper())).thenReturn(List.of(order("order_1", USER_ID)));
        List<OrderItem> userItems = List.of(item("order_1", 10), item("order_1", 3));
        when(orderItemMapper.selectList(anyWrapper())).thenReturn(userItems);
        // 全体订单明细（含他人 order_2）：item-CF 共现数据源（selectList(null)=全表）
        when(orderItemMapper.selectList(isNull()))
                .thenReturn(List.of(item("order_1", 10), item("order_1", 3),
                        item("order_2", 2), item("order_2", 10)));
    }

    @Test
    void personalizedChangesOrdering() {
        stubDishes(personalizedDishes());
        stubPersonalizedHistory();
        // limit=6 取全量 6 候选，完整校验个性化全序（默认 limit=5 会截掉末位）
        RecommendVO vo = recommendService.recommend(USER_ID, null, 6);
        assertEquals(RecommendVO.STRATEGY_PERSONALIZED, vo.getStrategy());
        assertEquals(List.of(2L, 4L, 1L, 9L, 3L, 10L), ids(vo));
    }

    @Test
    void personalizedDiffersFromGuestOrdering() {
        stubDishes(personalizedDishes());
        stubPersonalizedHistory();
        List<Long> personalized = ids(recommendService.recommend(USER_ID, null, 6));
        List<Long> guest = ids(recommendService.recommend(null, null, 6));
        assertEquals(List.of(2L, 3L, 4L, 1L, 9L, 10L), guest);
        assertFalse(personalized.equals(guest), "个性化排序应与游客热销兜底至少一项不同（F-02.3 场景 2）");
    }

    // —— ③ excludeId 剔除 ——

    @Test
    void excludeIdRemovedFromResult() {
        stubDishes(coldStartDishes());
        RecommendVO vo = recommendService.recommend(null, 2L, null);
        assertFalse(ids(vo).contains(2L), "excludeId 硬剔除（F-02.1）");
        assertEquals(3, vo.getItems().size());
        assertEquals(List.of(4L, 1L, 3L), ids(vo));
    }

    // —— ④ limit 截断 ——

    /**
     * T4 fixture：T1 基础上追加 id5(sold30)/id6(sold20)。
     * s3(id5)=log1p30/log1p100=3.43399/4.61512=0.74407→0.19881；s3(id6)=3.04452/4.61512=0.65969→0.18194
     * → 全序 [4,1,3,2,5,6]。
     */
    private List<Dish> sixDishes() {
        List<Dish> dishes = coldStartDishes();
        dishes.add(dish(5, "catB", 30, 0));
        dishes.add(dish(6, "catC", 20, 0));
        return dishes;
    }

    @Test
    void limitTruncatesAndDefaultsToFive() {
        stubDishes(sixDishes());
        assertEquals(5, recommendService.recommend(null, null, null).getItems().size(), "默认 5 条（F-02.1）");
        assertEquals(List.of(4L, 1L, 3L), ids(recommendService.recommend(null, null, 3)), "limit=3 恰 3 条");
        assertEquals(List.of(4L, 1L, 3L, 2L, 5L), ids(recommendService.recommend(null, null, 5)));
        assertEquals(5, recommendService.recommend(null, null, 0).getItems().size(), "limit<1 回落默认 5");
    }

    // —— ⑤ 确定性（同输入同输出）——

    @Test
    void deterministicSameInputSameOutput() {
        stubDishes(personalizedDishes());
        stubPersonalizedHistory();
        for (int i = 0; i < 3; i++) {
            RecommendVO vo = recommendService.recommend(USER_ID, null, 6);
            assertEquals(RecommendVO.STRATEGY_PERSONALIZED, vo.getStrategy());
            assertEquals(List.of(2L, 4L, 1L, 9L, 3L, 10L), ids(vo), "同输入第 " + (i + 1) + " 次结果必须全等");
        }
    }

    // —— ⑥ 已购 ×0.5 降权 ——

    /**
     * T6 fixture：两候选完全同质（catA/sold100/非秒杀/无评论），用户买过 id1。
     * 两候选 score 相等（s1 同类同值、s3 同 1、s2 全 0）→ 若无降权应 tiebreak id asc=[1,2]；
     * 已购 id1 ×0.5 → [2,1]，以此区分降权是否生效。
     */
    @Test
    void purchasedDishDownWeightedByHalf() {
        List<Dish> dishes = List.of(dish(2, "catA", 100, 0), dish(1, "catA", 100, 0));
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(dishes);
        when(orderMapper.selectList(anyWrapper())).thenReturn(List.of(order("order_1", USER_ID)));
        when(orderItemMapper.selectList(anyWrapper())).thenReturn(List.of(item("order_1", 1)));
        // 全体明细仅该单一行：无 dish 对 → 共现全 0 → s2 恒 0，隔离出 ×0.5 的效果
        when(orderItemMapper.selectList(isNull())).thenReturn(List.of(item("order_1", 1)));

        RecommendVO vo = recommendService.recommend(USER_ID, null, null);
        assertEquals(List.of(2L, 1L), ids(vo), "已购商品应被 score×0.5 降到同质未购商品之后（复购降权不剔除）");
        assertTrue(ids(vo).contains(1L), "已购商品保留在结果中（降权而非剔除）");
    }

    // —— ⑦ 同品类连续 ≤2 打散 ——

    /**
     * T7 fixture：4×catA + 1×catB，score 降序 [1,2,3,4]。
     * i=2 处 catA 三连 → 与下一个异类目项（idx3=catB）交换 → [1,2,4,3]，
     * 打散后任意连续 3 项不再全同品类。
     */
    @Test
    void sameCategoryRunBrokenUp() {
        List<Dish> dishes = List.of(
                dish(1, "catA", 100, 0),
                dish(2, "catA", 90, 0),
                dish(3, "catA", 80, 0),
                dish(4, "catB", 70, 0));
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);

        RecommendVO vo = recommendService.recommend(null, null, null);
        assertEquals(List.of(1L, 2L, 4L, 3L), ids(vo));
        // 不变量：任意连续 3 项不同为全同品类（同品类连续 ≤2，F-02.1）
        List<Dish> items = vo.getItems();
        for (int i = 2; i < items.size(); i++) {
            String c = items.get(i).getCategory();
            assertFalse(c.equals(items.get(i - 1).getCategory()) && c.equals(items.get(i - 2).getCategory()),
                    "位置 " + (i - 2) + "~" + i + " 出现同品类三连");
        }
    }

    /**
     * T7b 尾段保持：5×catA + 1×catB，全序 [1,2,3,4,5,6]。
     * i=2 处三连触发 → 与 idx5(catB) 交换 → [1,2,6,4,5,3]；
     * i=5 处 [4,5,3] 再成 catA 三连，但 j 越界无可用异类目 → 尾段保持（F-02.1"尾段不足则保持"），
     * 残留三连是文档贪心规则的既定行为，此处只断言交换发生且不崩、长度不变。
     */
    @Test
    void interleaveKeepsTailWhenNoForeignCategoryLeft() {
        List<Dish> dishes = List.of(
                dish(1, "catA", 100, 0),
                dish(2, "catA", 95, 0),
                dish(3, "catA", 90, 0),
                dish(4, "catA", 85, 0),
                dish(5, "catA", 80, 0),
                dish(6, "catB", 70, 0));
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);

        // 显式 limit=6 取全量（默认 5 会截断，见 limitTruncatesAndDefaultsToFive；
        // 尾段保持需观察完整 6 项全序，同 personalizedChangesOrdering 的做法）
        RecommendVO vo = recommendService.recommend(null, null, 6);
        assertEquals(6, vo.getItems().size());
        assertEquals(List.of(1L, 2L, 6L, 4L, 5L, 3L), ids(vo));
    }

    // —— 附加：s5 口碑仅取过审评论（非 pending）——

    /**
     * T8 fixture：三候选同质（catA/sold50），id1 有 2 条过审评论（rating5）→ s5=1；
     * id2 无评论 → s5=0.5 中性；id3 仅 1 条 pending(rating5) → 不计入 → s5=0.5。
     * score：id1=0.2+0.1=0.3 > id2=id3=0.25（tie → id asc）→ [1,2,3]。
     * 若 pending 被误计入，id3 的 s5=1 → 0.3 → 序列变 [1,3,2]，与期望相悖，构成区分。
     */
    @Test
    void reviewSignalCountsApprovedOnly() {
        List<Dish> dishes = List.of(
                dish(1, "catA", 50, 0),
                dish(2, "catA", 50, 0),
                dish(3, "catA", 50, 0));
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);
        when(reviewMapper.selectList(anyWrapper())).thenReturn(List.of(
                review(1, 5, null),
                review(1, 5, null),
                review(3, 5, Review.AUDIT_PENDING)));

        RecommendVO vo = recommendService.recommend(null, null, null);
        assertEquals(List.of(1L, 2L, 3L), ids(vo), "口碑分仅取过审评论（pending 不计入），无过审评论 0.5 中性");
    }

    // —— 附加：on_sale=0 硬过滤（内存兜底路径）——

    /**
     * T9 fixture：T1 基础上混入下架商品 id7(sold999)。
     * 若未过滤，maxSold=999 会重写 s3 归一化且 id7 排第一；过滤后期望与 T1 一致 [4,1,3,2]。
     */
    @Test
    void offSaleDishFilteredOut() {
        List<Dish> dishes = new ArrayList<>(coldStartDishes());
        Dish offSale = dish(7, "catA", 999, 0);
        offSale.setOnSale(0);
        dishes.add(offSale);
        when(dishMapper.selectList(anyWrapper())).thenReturn(dishes);

        RecommendVO vo = recommendService.recommend(null, null, null);
        assertFalse(ids(vo).contains(7L), "on_sale=0 硬过滤（F-02.1 候选集口径）");
        assertEquals(List.of(4L, 1L, 3L, 2L), ids(vo), "maxSold 须按过滤后的候选集计算");
    }
}
