package com.fresh.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId → MDC 过滤器（F-42a ①，补 T-M1-02 DoD③「服务端日志可查到该异常完整堆栈（traceId 对应）」的实测漏口）。
 * <p>
 * 职责只有三件事，别的什么都不做：请求进来生成一个短 traceId、{@code MDC.put("traceId", …)}、
 * {@code finally} 里 {@code MDC.remove("traceId")}。remove 必须在 finally：Tomcat 线程是复用的，
 * 少清一次就会把上一个请求的 traceId 粘到下一个无关请求的日志行上，那比没有 traceId 更坏（误导排查）。
 * <p>
 * 与既有链路的关系（为什么不破坏 {@link com.fresh.config.CorsConfig} 的注册顺序语义）：
 * Filter 在 Servlet 之前、DispatcherServlet 的拦截器链整体落在 {@code chain.doFilter} 这一次调用里，
 * 所以本过滤器与 CorsConfig 的「认证在前（注入 userId）→ 限流在后（按用户维度取桶）」次序
 * <b>没有交叉，也不参与该次序</b>——它只保证 AuthInterceptor/RateLimitInterceptor 的 WARN
 * 与 GlobalExceptionHandler 的 ERROR 行都自带 traceId（这些日志点一行代码都不用改，
 * pattern 由 logback-spring.xml 的 {@code %X{traceId}} 统一带上）。
 * {@code @Order(HIGHEST_PRECEDENCE)} 只在本 Filter 与「将来可能新增的其它 Filter」之间定序，
 * 意图是 traceId 永远包在最外层；本仓当前没有任何其它 Filter（注册范式与 AuthInterceptor/
 * RateLimitInterceptor 同为 {@code @Component} 扫描，不引入 FilterRegistrationBean/web.xml）。
 * <p>
 * 【traceId 不进 body，前端不读】对外仍是统一信封 {@code R{code,data,message}} 且 HTTP 恒 200
 * （契约见 {@link R} 与 {@link ResponseUtil}），本过滤器一个字都不碰响应体；
 * traceId 只出现在响应头 {@link #TRACE_ID_HEADER} 与服务端日志里。该头也<b>没有</b>加进
 * CorsConfig 的 exposedHeaders，故跨域浏览器脚本读不到它（这是有意的：前端零改动），
 * 它的用途是给 curl -i / 网关日志 / 运维抓包对上服务端那一行堆栈。
 * <p>
 * 已知边界（如实登记，不藏）：① MDC 是 ThreadLocal，本仓无异步/并发端点（无 {@code @Async}、
 * 无 DeferredResult/Callable），故不存在「子线程丢 traceId」的现实路径；将来加了要在此显式传递。
 * ② {@code @Scheduled} 的 {@link com.fresh.task.OrderTimeoutTask} 不走 HTTP，日志行的 {@code %X{traceId}}
 * 为空（形如 {@code []}）——那是调度线程，本就对应不到任何对外响应。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键名：与 logback-spring.xml 的 {@code %X{traceId}} 一字对应，改这里必须同步改 pattern（TraceIdFilterTest 锁住这条对齐） */
    public static final String MDC_KEY = "traceId";

    /** 响应头名：只加头、不改 body（见类注释「traceId 不进 body」） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * traceId 长度=8：UUID 全串（36 字符）在日志里读不动也抄不动，8 位十六进制够在一屏里对齐。
     * 代价是 32 位空间，理论上同一时间窗内会撞——撞上时靠日志行的「时间+线程+URI」三要素区分，
     * traceId 是排障线索而不是唯一键；要更强唯一性只需调大本常量（字符集不变）。
     */
    static final int TRACE_ID_LENGTH = 8;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = newTraceId();
        MDC.put(MDC_KEY, traceId);
        // 在 chain 之前写头：此时响应尚未 commit；ResponseUtil/GlobalExceptionHandler 后续只写 body，不会覆盖该头
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 生成 traceId：取 {@code UUID.randomUUID()} 去横线后的前 8 位（全串里前 8 位都是随机 nibble，
     * 版本位在第 13 位，不在这 8 位内，故取到的是纯随机 8 个 {@code [0-9a-f]}）。
     * 纯静态、不碰 Spring 上下文、不碰 servlet——离线单测（不起容器）直接测它。
     */
    static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
    }
}
