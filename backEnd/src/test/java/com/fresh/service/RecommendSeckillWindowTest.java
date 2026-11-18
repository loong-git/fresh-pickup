package com.fresh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.Dish;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.impl.RecommendServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-6 I2 推荐出参秒杀窗口态装饰单测（不起容器、不落库）：候选集首算阶段经
 * {@code DishServiceImpl#decorateSeckillWindow}（单一实现复用）下发 seckillActive/seckillStartAt，
 * 非秒杀件两键不出键（字段级 {@code @JsonInclude(NON_NULL)}，detail 推荐卡据此切原价/隐藏秒标）。
 *
 * <p><b>resolver 用 Mockito mock 而非真实 resolver + 字典桩</b>（范式照 DishSeckillWindowTest）：
 * 装饰点采样 {@code LocalTime.now()}（服务端时钟），真实 resolver 靠字典词条围「当前时刻」在
 * 23:59 边界分钟有 1/1440 翻车面；mock 让窗口内/外两侧均确定性成立。resolver 判定逻辑本身由
 * {@code SeckillSessionResolverTest} 直测锁死，本类只锁「推荐接线 + 缓存采样口径」。</p>
 *
 * <p><b>缓存交互锁（P11-6 I2 / P11-3 R1 承诺）</b>：60s 推荐缓存命中回包直接复用首算采样值、
 * 不二次装饰——resolver 恰采样一次（inWindow/sessionTime 各 1 次），且中途翻转 stub 后命中回包
 * 仍为首算值，证明「不可变副本防污染」承诺未被装饰破坏（装饰只作用于首算新建的 candidates）。</p>
 */
class RecommendSeckillWindowTest {

    /** LocalDateTime 字段恒 null 的出参形态：裸 ObjectMapper 即可序列化（照 DishSeckillWindowTest） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final SeckillSessionResolver resolver = mock(SeckillSessionResolver.class);

    private RecommendServiceImpl recommendService;

    @BeforeEach
    void setUp() {
        recommendService = new RecommendServiceImpl(dishMapper, orderMapper, orderItemMapper, reviewMapper, resolver);
    }

    // —— fixture 工具（照 RecommendServiceTest 的候选集口径：on_sale=1 + audit ok） ——

    private static Dish dish(long id, int soldCount, int seckill) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("D" + id);
        d.setCategory("catA");
        d.setSoldCount(soldCount);
        d.setSeckill(seckill);
        d.setOnSale(1);
        d.setAuditStatus(Dish.AUDIT_OK);
        d.setPrice(new BigDecimal("10.00"));
        if (seckill == 1) {
            d.setSeckillPrice(new BigDecimal("7.80"));
        }
        return d;
    }

    /** 一非秒杀件 + 一秒杀件（游客冷启动即可，不 stub 订单系 mapper） */
    private void stubCandidates() {
        when(dishMapper.selectList(anyWrapper()))
                .thenReturn(new ArrayList<>(List.of(dish(1, 100, 0), dish(2, 50, 1))));
    }

    private static Dish byId(RecommendVO vo, long id) {
        return vo.getItems().stream().filter(d -> d.getId() == id).findFirst().orElseThrow();
    }

    private static List<Long> ids(RecommendVO vo) {
        return vo.getItems().stream().map(Dish::getId).toList();
    }

    /** mock 候选查询共用的 wrapper 匹配器（消除 raw type 警告，照 RecommendServiceTest） */
    @SuppressWarnings("unchecked")
    private <T> com.baomidou.mybatisplus.core.conditions.Wrapper<T> anyWrapper() {
        return (com.baomidou.mybatisplus.core.conditions.Wrapper<T>) org.mockito.ArgumentMatchers.any(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
    }

    // ---------------------------------------------------------------- 出参窗口态

    @Test
    void 窗口内_推荐秒杀件seckillActive为true且startAt下发() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        stubCandidates();

        RecommendVO vo = recommendService.recommend(null, null, null);

        Dish sec = byId(vo, 2L);
        assertEquals(Boolean.TRUE, sec.getSeckillActive(), "窗口内秒杀件 seckillActive=true（与列表/详情同口径）");
        assertEquals("06:00", sec.getSeckillStartAt(), "预告数据 seckillStartAt=字典 start");
    }

    @Test
    void 窗口外_秒杀件seckillActive为false_非秒杀件两键不出键() throws Exception {
        when(resolver.inWindow(any())).thenReturn(false);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        stubCandidates();

        RecommendVO vo = recommendService.recommend(null, null, null);

        Dish plain = byId(vo, 1L);
        assertNull(plain.getSeckillActive(), "非秒杀件装饰器内跳过");
        assertNull(plain.getSeckillStartAt(), "非秒杀件装饰器内跳过");
        String plainJson = MAPPER.writeValueAsString(plain);
        assertFalse(plainJson.contains("seckillActive"), "NON_NULL 注解：非秒杀件 JSON 不出 seckillActive 键");
        assertFalse(plainJson.contains("seckillStartAt"), "NON_NULL 注解：非秒杀件 JSON 不出 seckillStartAt 键");
        String secJson = MAPPER.writeValueAsString(byId(vo, 2L));
        assertTrue(secJson.contains("\"seckillActive\":false"), "窗口外秒杀件 JSON 下发 seckillActive:false");
        assertTrue(secJson.contains("\"seckillStartAt\":\"06:00\""), "窗口外仍下发 startAt——前端预告文案要它");
    }

    // ---------------------------------------------------------------- 缓存交互（R1 不可变副本防污染 + D5 一次采样）

    /**
     * 同参数两次调用：首算装饰采样一次，缓存命中回包不再触碰 resolver 也不重打 mapper。
     * 中途翻转 inWindow stub——若命中回包被二次装饰，seckillActive 会翻成 true；
     * 断言仍为首算的 false，证明缓存条目只读复用、装饰只作用于首算新建的 candidates。
     */
    @Test
    void 缓存命中回包复用首算采样值_不重复装饰() {
        when(resolver.inWindow(any())).thenReturn(false);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        stubCandidates();

        RecommendVO first = recommendService.recommend(null, null, null);
        assertEquals(Boolean.FALSE, byId(first, 2L).getSeckillActive(), "首算采样：窗口外 false");

        when(resolver.inWindow(any())).thenReturn(true); // 中途翻 stub，制造「二次装饰会露馅」的差异面
        RecommendVO second = recommendService.recommend(null, null, null);

        assertEquals(Boolean.FALSE, byId(second, 2L).getSeckillActive(),
                "缓存命中回包=首算采样值（60s TTL 内窗口态快照，刷新生效）——不二次装饰");
        assertEquals(ids(first), ids(second), "缓存命中输出与首算全等（排序口径不动）");
        verify(resolver, times(1)).inWindow(any());
        verify(resolver, times(1)).sessionTime(any(), any());
        verify(dishMapper, times(1)).selectList(anyWrapper());
    }
}
