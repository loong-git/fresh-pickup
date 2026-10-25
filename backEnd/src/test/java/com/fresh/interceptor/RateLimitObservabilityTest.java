package com.fresh.interceptor;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.entity.RateLimitHit;
import com.fresh.mapper.RateLimitHitMapper;
import com.fresh.service.RateLimitHitService;
import com.fresh.service.impl.RateLimitHitServiceImpl;
import com.fresh.task.RateLimitHitCleanupTask;
import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-121 限流观测离线单测（契约 §5 三组；不起 Spring 容器、不连库——本仓测试全离线是既有性质，
 * servlet 对象用 spring-test 的 Mock 实现离线触达 preHandle，注解档位/SQL 文本用反射读，同
 * AdminDictControllerTest ④「注解属性钉死」的姿势）。
 *
 * <p><b>要证明的事</b>：
 * ① 命中落库（契约 §5-1）：拦截器命中路径（真桶耗尽后第 2 次被拒）旁路 INSERT 参数正确——
 *    route=method+空格+URI、bucket_key=拦截器 key 原样、retryAfter=429 下发的同一秒数、hit_time 非空；
 *    <b>插入抛异常 → 拦截仍返回 429</b>（旁路不吞拦截，契约 §2）；
 * ② 聚合（契约 §5-2）：days 白名单 {1,7,30}（缺省 7），白名单外 400；分组出参映射 + count 倒序 +
 *    平局确定性 + top-100 截断 + total 全量；
 * ③ 清理（契约 §5-3）：任务传给 DELETE 的 cutoff = now−30 天（mock 日期断言），且 DELETE 边界严格小于
 *    （只删 30 天前，30 天内一行不动——SQL 侧语义以注解文本钉住）。
 */
class RateLimitObservabilityTest {

    // ---------------------------------------------------------------- 组 1：命中落库

    /** 离线触达 preHandle 用的假控制器方法：挂一枚最小档 @RateLimit（1 枚/60s，首吃即空） */
    static class DummyLimitedController {

        @RateLimit(keyType = RateLimit.KeyType.IP, limit = 1, windowSeconds = 60)
        public void limited() {
        }
    }

    private RateLimitHitMapper hitMapper;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        hitMapper = mock(RateLimitHitMapper.class);
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "rateLimitHitMapper", hitMapper);
        // trustProxy 保持字段默认 false（@Value 只在容器里生效），clientIp 走 getRemoteAddr()=127.0.0.1
    }

    private Method limitedMethod() throws Exception {
        return DummyLimitedController.class.getMethod("limited");
    }

    private static MockHttpServletRequest postOrders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/orders");
        return request;
    }

    @Test
    @DisplayName("命中落库：真桶耗尽后第 2 次被拒，INSERT 参数=method+URI / key 原样 / 同一 retryAfter；放行不落库")
    void 命中路径旁路落库参数正确() throws Exception {
        HandlerMethod handler = new HandlerMethod(new DummyLimitedController(), limitedMethod());

        // 第 1 次：桶里唯一令牌被吃，放行——放行不落库（旁路只在命中分支）
        assertTrue(interceptor.preHandle(postOrders(), new MockHttpServletResponse(), handler),
                "limit=1 的首请求应放行");
        verify(hitMapper, never()).insert(any(RateLimitHit.class));

        // 第 2 次：桶空被拒——log.warn 同点旁路 INSERT 一行
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(postOrders(), response, handler), "第 2 次应被 429 拒绝");

        ArgumentCaptor<RateLimitHit> captor = ArgumentCaptor.forClass(RateLimitHit.class);
        verify(hitMapper, times(1)).insert(captor.capture());
        RateLimitHit hit = captor.getValue();
        assertEquals("POST /api/orders", hit.getRoute(), "route = method + 空格 + URI（契约 §2 现成变量）");
        assertEquals("ip:127.0.0.1:DummyLimitedController#limited", hit.getBucketKey(),
                "bucket_key = 拦截器 key 原样（IP 维度 + Controller#method）");
        assertNotNull(hit.getHitTime(), "hit_time 必须由旁路写入");
        // retryAfter 与 429 信封 data.retryAfterSeconds 同源（同一 seconds 变量）：1/60s greedy 回一枚 ≈ 60s
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":429"), "429 信封 code 经 body 携带，实际=" + body);
        assertTrue(body.contains("\"retryAfterSeconds\":" + hit.getRetryAfter()),
                "落库 retryAfter 必须与下发秒数同值，body=" + body + " hit=" + hit.getRetryAfter());
        assertTrue(hit.getRetryAfter() >= 1 && hit.getRetryAfter() <= 60,
                "1 枚/60s 档的回速秒数应落在 [1,60]，实际=" + hit.getRetryAfter());
    }

    @Test
    @DisplayName("旁路不吞拦截：INSERT 抛异常 → 拦截仍返回 429、信封文案逐字节同形")
    void 落库失败不影响拦截语义() throws Exception {
        when(hitMapper.insert(any(RateLimitHit.class))).thenThrow(new RuntimeException("db down"));
        HandlerMethod handler = new HandlerMethod(new DummyLimitedController(), limitedMethod());

        assertTrue(interceptor.preHandle(postOrders(), new MockHttpServletResponse(), handler),
                "首请求应放行（放行不触达 insert，异常不影响）");

        // 第 2 次命中：旁路 insert 抛异常——异常必须被旁路吞掉，拦截语义原样（429 + return false）
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(postOrders(), response, handler),
                "落库失败时第 2 次仍应被 429 拒绝（观测绝不因观测挂限流）");
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":429"), "429 信封照常写出，实际=" + body);
        assertTrue(body.contains("操作过于频繁，请稍后再试"), "429 文案与既有逐字节同形（R.MSG_TOO_MANY_REQUESTS）");
        assertTrue(body.contains("\"retryAfterSeconds\":"), "retryAfterSeconds 载荷照常下发，实际=" + body);
    }

    // ---------------------------------------------------------------- 组 2：聚合

    private RateLimitHitService newService() {
        RateLimitHitServiceImpl service = new RateLimitHitServiceImpl();
        ReflectionTestUtils.setField(service, "rateLimitHitMapper", hitMapper);
        return service;
    }

    private static Map<String, Object> aggRow(String route, String bucketKey, long hitCount, String lastHitAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("route", route);
        row.put("bucketKey", bucketKey);
        row.put("hitCount", hitCount);
        row.put("lastHitAt", lastHitAt);
        return row;
    }

    @Test
    @DisplayName("聚合白名单：days=5 → 400 且不触达 mapper；1/30 合法、缺省 7，窗口随档走（mock 日期断言）")
    void 天数档白名单外四百() {
        RateLimitHitService service = newService();

        BizException ex = assertThrows(BizException.class, () -> service.hits(5), "白名单外 400（契约 §5-2）");
        assertEquals(400, ex.getCode(), "days=5 → 400");
        verify(hitMapper, never()).selectAggregates(any());

        when(hitMapper.selectAggregates(any())).thenReturn(List.of());
        when(hitMapper.countHitsSince(any())).thenReturn(0L);

        // 缺省 7：since ≈ now-7 天（容差 5s）
        ArgumentCaptor<LocalDateTime> sinceDefault = ArgumentCaptor.forClass(LocalDateTime.class);
        service.hits(null);
        verify(hitMapper).selectAggregates(sinceDefault.capture());
        assertTrue(Duration.between(sinceDefault.getValue(), LocalDateTime.now().minusDays(7)).getSeconds() < 5,
                "缺省窗口应是 now-7 天，实际=" + sinceDefault.getValue());

        // days=1（独立 captor：Mockito captor 跨 verify 会累积 getAllValues，各档各用一只）
        ArgumentCaptor<LocalDateTime> since1 = ArgumentCaptor.forClass(LocalDateTime.class);
        service.hits(1);
        verify(hitMapper, times(2)).selectAggregates(since1.capture());
        assertTrue(Duration.between(since1.getAllValues().get(1), LocalDateTime.now().minusDays(1)).getSeconds() < 5,
                "days=1 窗口应是 now-1 天，实际=" + since1.getAllValues().get(1));

        // days=30
        ArgumentCaptor<LocalDateTime> since30 = ArgumentCaptor.forClass(LocalDateTime.class);
        service.hits(30);
        verify(hitMapper, times(3)).selectAggregates(since30.capture());
        assertTrue(Duration.between(since30.getAllValues().get(2), LocalDateTime.now().minusDays(30)).getSeconds() < 5,
                "days=30 窗口应是 now-30 天，实际=" + since30.getAllValues().get(2));
    }

    @Test
    @DisplayName("聚合分组与排序：count 倒序、平局按维度键升序、出参恰四键、top-100 截断、total 全量")
    @SuppressWarnings("unchecked")
    void 聚合分组排序与截断() {
        RateLimitHitService service = newService();

        when(hitMapper.selectAggregates(any())).thenReturn(List.of(
                aggRow("POST /api/orders", "ip:9.9.9.9:OrderController#createOrder", 1, "2026-10-25 01:00:00"),
                aggRow("GET /api/seckill/sessions", "ip:1.1.1.1:SeckillController#sessions", 9, "2026-10-25 09:00:00"),
                aggRow("POST /api/orders", "user:11:OrderController#createOrder", 5, "2026-10-25 05:00:00")));
        when(hitMapper.countHitsSince(any())).thenReturn(15L);

        Map<String, Object> data = service.hits(7);
        assertEquals(7, data.get("days"));
        assertEquals(15L, data.get("total"), "total 取独立 COUNT（全量），不受 top-100 影响");

        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(3, list.size());
        assertEquals("ip:1.1.1.1:SeckillController#sessions", list.get(0).get("bucketKey"), "count=9 第一");
        assertEquals("user:11:OrderController#createOrder", list.get(1).get("bucketKey"), "count=5 第二");
        assertEquals("ip:9.9.9.9:OrderController#createOrder", list.get(2).get("bucketKey"), "count=1 第三");
        assertEquals(9L, list.get(0).get("count"));
        assertEquals("GET /api/seckill/sessions", list.get(0).get("route"));
        assertEquals("2026-10-25 09:00:00", list.get(0).get("lastHitAt"));
        // 出参恰四键（route/bucketKey/count/lastHitAt），SQL 侧 hitCount 别名不外泄
        assertEquals(4, list.get(0).size());

        // 平局确定性：同 count 按维度键升序
        when(hitMapper.selectAggregates(any())).thenReturn(List.of(
                aggRow("POST /api/orders", "ip:2.2.2.2:OrderController#createOrder", 3, "2026-10-25 02:00:00"),
                aggRow("POST /api/orders", "ip:1.1.1.1:OrderController#createOrder", 3, "2026-10-25 03:00:00")));
        List<Map<String, Object>> tie = (List<Map<String, Object>>) service.hits(7).get("list");
        assertEquals("ip:1.1.1.1:OrderController#createOrder", tie.get(0).get("bucketKey"), "平局按 bucketKey 升序（确定性）");
        assertEquals("ip:2.2.2.2:OrderController#createOrder", tie.get(1).get("bucketKey"));

        // top-100 截断：105 行全异 count → 恰出 100 行且首位是最大者
        List<Map<String, Object>> many = new ArrayList<>();
        for (long i = 1; i <= 105; i++) {
            many.add(aggRow("POST /api/orders", "ip:10.0.0." + i + ":OrderController#createOrder", i, "2026-10-25 00:00:00"));
        }
        when(hitMapper.selectAggregates(any())).thenReturn(many);
        List<Map<String, Object>> capList = (List<Map<String, Object>>) service.hits(7).get("list");
        assertEquals(100, capList.size(), "top 100 截断（契约 §2）");
        assertEquals(105L, capList.get(0).get("count"), "截断后首位仍是最大 count");
    }

    // ---------------------------------------------------------------- 组 3：清理

    @Test
    @DisplayName("清理：cutoff = now-30 天（mock 日期断言）；DELETE 边界严格小于 → 30 天内一行不动")
    void 清理删三十天前且窗口内不动() throws Exception {
        RateLimitHitService service = mock(RateLimitHitService.class);
        RateLimitHitCleanupTask task = new RateLimitHitCleanupTask();
        ReflectionTestUtils.setField(task, "rateLimitHitService", service);
        when(service.deleteHitsBefore(any())).thenReturn(7);

        task.cleanupExpiredHits();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(service, times(1)).deleteHitsBefore(cutoff.capture());
        assertTrue(Duration.between(cutoff.getValue(), LocalDateTime.now().minusDays(30)).getSeconds() < 5,
                "cutoff 必须 ≈ now-30 天（契约 §2），实际=" + cutoff.getValue());

        // DELETE 语义钉注解文本：严格小于（hit_time < #{before}）→ 30 天内的行（>= cutoff）不在删除面
        Delete delete = RateLimitHitMapper.class
                .getMethod("deleteByHitTimeBefore", LocalDateTime.class)
                .getAnnotation(Delete.class);
        assertNotNull(delete, "清理 SQL 必须挂在 deleteByHitTimeBefore");
        assertTrue(delete.value()[0].contains("DELETE FROM rate_limit_hit WHERE hit_time < #{before}"),
                "只删 hit_time 严格早于 cutoff 的行（30 天内一行不动），实际=" + delete.value()[0]);
    }
}
