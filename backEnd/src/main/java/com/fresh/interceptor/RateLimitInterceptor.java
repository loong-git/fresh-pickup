package com.fresh.interceptor;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.common.ResponseUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;

/**
 * 限流拦截器（T-M2-06，契约 M2-6）：读取控制器方法上的 @RateLimit，按维度取令牌桶判定。
 * - 桶按 key（userId 或 IP+路径）隔离，Caffeine 缓存 10 分钟不访问自动回收 + 总量上限，防内存无界增长；
 * - 超限 → 统一信封 429（写 response JSON，与 AuthInterceptor 401 同一套路）；
 * - 注册在 AuthInterceptor 之后：需登录接口的 userId attribute 已就绪，匿名接口（如字典）天然走 IP 维度。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 限流桶缓存：key → 令牌桶；10 分钟无访问回收，总量上限 10w 条 */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rl = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RateLimit.class);
        if (rl == null) {
            return true;
        }
        String key = bucketKey(rl, request, handlerMethod);
        Bucket bucket = buckets.get(key, k -> newBucket(rl));
        // 拦截时 WARN（含 key 便于定位滥用来源）；放行不打日志，避免高频接口刷日志
        if (bucket != null && !bucket.tryConsume(1)) {
            log.warn("[RateLimit] 限流拦截: {} {} key={}", request.getMethod(), request.getRequestURI(), key);
            ResponseUtil.writeJson(response, R.CODE_TOO_MANY_REQUESTS, R.MSG_TOO_MANY_REQUESTS);
            return false;
        }
        return true;
    }

    /**
     * 限流 key（回归缺陷修复：原 USER 维度仅 "user:{userId}"，下单/评价共享同一桶且桶参数
     * 以首次创建者为准，互相吃配额）。现按"维度 + 接口"隔离：
     * - 接口身份取 Controller类#方法 而非 requestURI：评价接口 URI 含 dishId
     *   （/api/dishes/{dishId}/reviews），按 URI 隔离会退化为"每菜品各 1 次/10s"，
     *   违反契约"评价 1 次/10s/用户"的接口级配额语义；方法级 key 使同接口各注解实例桶参数各自生效。
     * - USER 维度用登录 userId（AuthInterceptor 注入；匿名兜底 IP），IP 维度用客户端 IP。
     */
    private String bucketKey(RateLimit rl, HttpServletRequest request, HandlerMethod handlerMethod) {
        String api = handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        if (rl.keyType() == RateLimit.KeyType.IP) {
            return "ip:" + clientIp(request) + ":" + api;
        }
        Object userId = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return "user:" + (userId != null ? userId : "ip:" + clientIp(request)) + ":" + api;
    }

    /** 令牌桶：每 windowSeconds 秒补充 limit 个令牌 */
    private Bucket newBucket(RateLimit rl) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(rl.limit(),
                        Refill.greedy(rl.limit(), Duration.ofSeconds(rl.windowSeconds()))))
                .build();
    }

    /** 客户端 IP：优先 X-Forwarded-For 首跳（反代场景），否则直连地址 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
