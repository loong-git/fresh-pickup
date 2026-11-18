package com.fresh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.entity.Dish;
import com.fresh.mapper.DishMapper;
import com.fresh.service.RecommendService;
import com.fresh.service.SeckillSessionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-4 片① D2/D3 秒杀窗口态出参装饰单测（不起容器、不落库）：
 * {@link DishServiceImpl#decorateSeckillWindow} 给秒杀件下发 seckillActive/seckillStartAt，
 * 非秒杀件两键不出键（字段级 {@code @JsonInclude(NON_NULL)}）。
 *
 * <p><b>resolver 用 Mockito mock 而非真实 resolver + 字典桩</b>：装饰点采样
 * {@code LocalTime.now()}（服务端时钟），真实 resolver 须靠字典词条围住「当前时刻」，
 * 「窗口内」一侧在 23:59 边界分钟有 1/1440 的翻车面（flaky）；mock 注入让窗口内/外
 * 两侧均确定性成立（任务口径明确允许 mock resolver）。resolver 判定逻辑本身由
 * {@code SeckillSessionResolverTest} 直测锁死，本类只锁「装饰接线」。</p>
 *
 * <p><b>同时锁住</b>：startAt 与 active 同点采样；{@code listSeckill} 不经装饰
 * （F-112 契约 §1.3 收录口径零改动，秒杀卡项级 active 由 SeckillController#current 填充）。</p>
 */
class DishSeckillWindowTest {

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final RecommendService recommendService = mock(RecommendService.class);
    private final SeckillSessionResolver resolver = mock(SeckillSessionResolver.class);

    private DishServiceImpl dishService;

    @BeforeEach
    void setUp() {
        dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(dishService, "recommendService", recommendService);
        ReflectionTestUtils.setField(dishService, "seckillSessionResolver", resolver);
    }

    /** LocalDateTime 字段恒 null 的出参形态：裸 ObjectMapper 即可序列化（null 值不走类型 serializer） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Dish dish(long id, int seckill) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("dish-" + id);
        d.setPrice(new BigDecimal("10.00"));
        d.setSeckill(seckill);
        if (seckill == 1) {
            d.setSeckillPrice(new BigDecimal("7.80"));
        }
        return d;
    }

    // ---------------------------------------------------------------- 列表出参（likeSearch 路径）

    @Test
    void 窗口内_列表秒杀件seckillActive为true且startAt下发() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1), dish(2L, 0))));

        List<Dish> out = dishService.getDishes(null, null);

        assertEquals(Boolean.TRUE, out.get(0).getSeckillActive(), "窗口内秒杀件 seckillActive=true（D2）");
        assertEquals("06:00", out.get(0).getSeckillStartAt(), "预告数据 seckillStartAt=字典 start（D3）");
    }

    @Test
    void 窗口外_列表秒杀件seckillActive为false且startAt仍下发() {
        when(resolver.inWindow(any())).thenReturn(false);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1), dish(2L, 0))));

        List<Dish> out = dishService.getDishes(null, null);

        assertEquals(Boolean.FALSE, out.get(0).getSeckillActive(), "窗口外秒杀件 seckillActive=false——C 端切原价展示");
        assertEquals("06:00", out.get(0).getSeckillStartAt(), "窗口外仍下发 startAt——预告文案「每日 06:00 开抢」要它");
    }

    // ---------------------------------------------------------------- 非秒杀件不出键

    @Test
    void 非秒杀件_两键保持null_序列化不出键_秒杀件窗口外出false() throws Exception {
        when(resolver.inWindow(any())).thenReturn(false);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(2L, 0), dish(1L, 1))));

        List<Dish> out = dishService.getDishes(null, null);

        assertNull(out.get(0).getSeckillActive(), "非秒杀件装饰器内跳过");
        assertNull(out.get(0).getSeckillStartAt(), "非秒杀件装饰器内跳过");
        String plainJson = MAPPER.writeValueAsString(out.get(0));
        assertFalse(plainJson.contains("seckillActive"), "NON_NULL 注解：非秒杀件 JSON 不出 seckillActive 键");
        assertFalse(plainJson.contains("seckillStartAt"), "NON_NULL 注解：非秒杀件 JSON 不出 seckillStartAt 键");
        String secJson = MAPPER.writeValueAsString(out.get(1));
        assertTrue(secJson.contains("\"seckillActive\":false"), "秒杀件 JSON 下发 seckillActive:false");
        assertTrue(secJson.contains("\"seckillStartAt\":\"06:00\""), "秒杀件 JSON 下发 seckillStartAt");
    }

    // ---------------------------------------------------------------- 详情出参

    @Test
    void 详情_秒杀件同点装饰_非秒杀件保持null() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectById(1L)).thenReturn(dish(1L, 1));
        when(dishMapper.selectById(2L)).thenReturn(dish(2L, 0));

        Dish sec = dishService.getDishById(1L);
        assertEquals(Boolean.TRUE, sec.getSeckillActive(), "详情秒杀件与列表同口径（D2）");
        assertEquals("06:00", sec.getSeckillStartAt(), "详情预告数据与列表同口径（D3）");

        Dish plain = dishService.getDishById(2L);
        assertNull(plain.getSeckillActive(), "详情非秒杀件不出键");
        assertNull(plain.getSeckillStartAt(), "详情非秒杀件不出键");
    }

    // ---------------------------------------------------------------- listSeckill 不装饰（F-112 红线）

    @Test
    void listSeckill_收录路径不经窗口装饰_两键保持null() {
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1))));

        List<Dish> out = dishService.listSeckill();

        assertNull(out.get(0).getSeckillActive(),
                "秒杀收录口径（F-112 §1.3）零改动——项级窗口态由 SeckillController#current 填 active");
        assertNull(out.get(0).getSeckillStartAt(),
                "预告数据走 /api/seckill/current 顶层 start（D3），listSeckill 不重复下发");
    }

    // ---------------------------------------------------------------- P11-6 I5 双重装饰移除回归

    /**
     * P11-6 I5：getDishes 四条 LIKE 路径的外层 {@code decorateSeckillWindow(likeSearch(...))} 包裹
     * 已删除，likeSearch 内部装饰是列表路径唯一装饰层。本组三例锁「外层删除后内层仍在」
     * （防删错层导致列表裸奔）+ 每请求恰一次采样（防双重装饰回潮——双重装饰会触发
     * inWindow 两次，与「一次采样」口径相悖）。
     */

    @Test
    void 空白keyword全量路径_装饰仍生效且仅一次采样() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1))));

        List<Dish> out = dishService.getDishes(null, null);

        assertEquals(Boolean.TRUE, out.get(0).getSeckillActive(),
                "全量路径装饰生效（内层 likeSearch 装饰在，外层包裹删除后不得裸奔）");
        verify(resolver, times(1)).inWindow(any());
        verify(resolver, times(1)).sessionTime(anyString(), anyString());
    }

    @Test
    void 单字keywordLIKE路径_装饰仍生效且仅一次采样() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1), dish(2L, 0))));

        List<Dish> out = dishService.getDishes(null, "土");

        assertEquals(Boolean.TRUE, out.get(0).getSeckillActive(),
                "单字 LIKE 兜底路径装饰生效（内层装饰在）");
        verify(resolver, times(1)).inWindow(any());
        verify(resolver, times(1)).sessionTime(anyString(), anyString());
    }

    @Test
    void 多字keyword零命中回退路径_装饰仍生效且仅一次采样() {
        when(resolver.inWindow(any())).thenReturn(true);
        when(resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START)).thenReturn("06:00");
        // MATCH+别名均 0 命中 → 回退 likeSearch（F-03.2③ 路径）
        when(dishMapper.selectIdsByFulltext(any())).thenReturn(List.of());
        when(dishMapper.selectDishIdsByAlias(any())).thenReturn(List.of());
        when(dishMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(dish(1L, 1), dish(2L, 0))));

        List<Dish> out = dishService.getDishes(null, "土豆");

        assertEquals(Boolean.TRUE, out.get(0).getSeckillActive(),
                "0 命中回退 LIKE 路径装饰生效（内层装饰在）");
        verify(resolver, times(1)).inWindow(any());
        verify(resolver, times(1)).sessionTime(anyString(), anyString());
    }
}
