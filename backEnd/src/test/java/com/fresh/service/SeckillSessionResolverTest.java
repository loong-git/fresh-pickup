package com.fresh.service;

import com.fresh.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P11-2 P2-D 秒杀计价窗口判定源 SeckillSessionResolver 直测（不起容器、不落库，mock DictService）：
 * 回退口径与 F-115 契约 §2.1 逐字（组不存在/词条缺失/definition 非 HH:MM → 该侧回退默认值），
 * 窗口边界 [start, end)（含 start 不含 end，与 C 端「end 过点已切明日场次」展示口径一致），
 * start ≥ end 非法配置恒 false（保守按原价，不授予折扣）。SeckillController 出参行为由
 * SeckillSessionConfigTest 回归（同一 mock 驱动），本类锁判定源本身。
 */
class SeckillSessionResolverTest {

    private final DictService dictService = mock(DictService.class);
    private SeckillSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SeckillSessionResolver();
        ReflectionTestUtils.setField(resolver, "dictService", dictService);
    }

    /** getGroup 出参模拟：null 侧=词条缺失（不进列表），形态照 SeckillSessionConfigTest#session */
    private static List<Map<String, Object>> session(String start, String end) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (start != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("word", "start");
            m.put("definition", start);
            list.add(m);
        }
        if (end != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("word", "end");
            m.put("definition", end);
            list.add(m);
        }
        return list;
    }

    // ---------------------------------------------------------------- 窗口边界 [start, end)

    @Test
    void 窗口边界_含start不含end_与C端已切明日场次口径一致() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("06:00", "20:00"));

        assertFalse(resolver.inWindow(LocalTime.parse("05:59")), "start 前一刻不在窗口内");
        assertTrue(resolver.inWindow(LocalTime.parse("06:00")), "start 时刻本身在窗口内（含 start）");
        assertTrue(resolver.inWindow(LocalTime.parse("19:59")), "end 前一刻在窗口内");
        assertFalse(resolver.inWindow(LocalTime.parse("20:00")), "end 时刻不在窗口内（不含 end）");
        assertFalse(resolver.inWindow(LocalTime.parse("23:30")), "end 之后不在窗口内");
    }

    // ---------------------------------------------------------------- 回退三分支（§2.1 逐字）

    @Test
    void 组不存在_回退默认00点到23点() {
        when(dictService.getGroup("seckill_session")).thenThrow(new BizException("字典分组不存在"));

        assertEquals("00:00", resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START),
                "组不存在 → start 回退默认值");
        assertEquals("23:00", resolver.sessionTime("end", SeckillSessionResolver.DEFAULT_END),
                "组不存在 → end 回退默认值");
        assertTrue(resolver.inWindow(LocalTime.parse("12:00")), "默认窗口覆盖正午");
        assertFalse(resolver.inWindow(LocalTime.parse("23:00")), "默认窗口不含 23:00（end 边界）");
    }

    @Test
    void 词条缺失_该侧回退默认值() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("06:00", null));

        assertEquals("06:00", resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START),
                "start 词条在 → 原值透传");
        assertEquals("23:00", resolver.sessionTime("end", SeckillSessionResolver.DEFAULT_END),
                "end 词条缺失 → 回退默认值");
    }

    @Test
    void definition非HHMM_该侧回退默认值() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("25:00", "9:00"));

        assertEquals("00:00", resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START),
                "\"25:00\" 不匹配 HH:MM → start 回退默认值");
        assertEquals("23:00", resolver.sessionTime("end", SeckillSessionResolver.DEFAULT_END),
                "\"9:00\" 不匹配 HH:MM → end 回退默认值");
    }

    // ---------------------------------------------------------------- 非法配置保守侧

    @Test
    void start大于end_窗口恒false_非法配置不授予秒杀价() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("23:30", "08:00"));

        assertFalse(resolver.inWindow(LocalTime.parse("12:00")), "同日内区间恒为空");
        assertFalse(resolver.inWindow(LocalTime.parse("23:30")), "start 时刻同样判 false（无有效窗口）");
        assertFalse(resolver.inWindow(LocalTime.parse("08:00")), "end 时刻同样判 false");
    }

    @Test
    void start等于end_窗口恒false() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("08:00", "08:00"));

        assertFalse(resolver.inWindow(LocalTime.parse("08:00")), "零长窗口不判 true（含 start 不含 end 自相消）");
    }

    // ---------------------------------------------------------------- 出参透传

    @Test
    void 合法词条definition原值透传() {
        when(dictService.getGroup("seckill_session")).thenReturn(session("06:00", "20:00"));

        assertEquals("06:00", resolver.sessionTime("start", SeckillSessionResolver.DEFAULT_START));
        assertEquals("20:00", resolver.sessionTime("end", SeckillSessionResolver.DEFAULT_END));
    }
}
