package com.fresh.interceptor;

import com.fresh.annotation.RateLimit;
import com.fresh.common.R;
import com.fresh.common.ResponseUtil;
import com.fresh.entity.RateLimitHit;
import com.fresh.mapper.RateLimitHitMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 限流拦截器（T-M2-06，契约 M2-6）：读取控制器方法上的 @RateLimit，按维度取令牌桶判定。
 * - 桶按 key（userId 或 IP+路径）隔离，Caffeine 缓存 10 分钟不访问自动回收 + 总量上限，防内存无界增长；
 * - 超限 → 统一信封 429（写 response JSON，与 AuthInterceptor 401 同一套路）；
 * - 注册在 AuthInterceptor 之后：需登录接口的 userId attribute 已就绪，匿名接口（如字典）天然走 IP 维度；
 * - F-22：429 的 data 现在带 {@code retryAfterSeconds}，语义是「桶回速下最早可再试的整秒数（向上取整，下限 1）」。
 *   注意它取自 bucket4j 的 NanosToWaitForRefill（回速维度）而非 Reset（整窗重置维度），
 *   而本仓的桶是 {@code Refill.greedy}（30/60 ⇒ 约每 2 秒回 1 枚），故该值<b>通常远小于 windowSeconds</b>——
 *   页面/运营若按「要等一分钟」理解就是错的，照实转述这个数字即可。
 * - F-121：命中分支（现有 log.warn 同点）同步旁路 INSERT 一行到 rate_limit_hit（观测轻表，见 recordHitSafely）——
 *   落库失败只 warn，拦截判定/令牌桶零改动。
 * - F-123：429 的 data 增补 {@code reason=ip_rate_limit}（纯增键，全站所有 @RateLimit 生效，可接受），
 *   供三端把「写侧限流」与「防枚举锁」区分开；retryAfterSeconds 语义与取值一字未动，
 *   F-121 观测旁路（recordHitSafely）不受影响。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /**
     * 429 data 的机读原因值（F-123）：令牌桶写侧限流统一给 {@code ip_rate_limit}，
     * 与 AuthServiceImpl/PartnerOrderServiceImpl 那类防枚举锁 429（各自带锁语义 reason/字段）相区分。
     * key 名 {@code reason} 与 AuthInterceptor 的 403 机读键同名词，三端消费侧按 (code, reason) 二元组分流。
     */
    static final String REASON_IP_RATE_LIMIT = "ip_rate_limit";
    /** 限流桶缓存：key → 令牌桶；10 分钟无访问回收，总量上限 10w 条 */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    /**
     * 是否信任反向代理头 X-Forwarded-For 取客户端 IP（默认 false，见 application-dev.yml 同名配置）。
     * XFF 由请求方随意伪造：默认信任它时，轮换 XFF 头即可让每次请求落入不同限流桶，
     * IP 维度闸门（send-code/login 5 次/分/IP，防短信轰炸/防无限造号刷邀请冻结）完全失效。
     * 故默认直取 request.getRemoteAddr()（TCP 对端地址，不可伪造）；仅当部署在可信反代
     * （nginx 等）之后并显式配置 rate-limit.trust-proxy=true 时才解析 XFF，
     * 此时取值链路的真实性由可信代理保证（代理需覆写而非追加该头）。
     */
    @Value("${rate-limit.trust-proxy:false}")
    private boolean trustProxy;

    /** F-121 落库旁路：命中行写入 rate_limit_hit（观测表，与拦截判定零耦合） */
    @Autowired
    private RateLimitHitMapper rateLimitHitMapper;

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
        // F-22：改用 tryConsumeAndReturnRemaining，把「桶回速下最早可再试的整秒数」经 data.retryAfterSeconds
        // 如实下发（同一通道：AdminAuthServiceImpl#lockedFailure + GlobalExceptionHandler#handleBiz），
        // 页面不再猜「稍后」是几秒。取的是 Refill 字段（NanosToWaitForRefill）而非 Reset：greedy 下它远小于 windowSeconds。
        if (bucket != null) {
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (probe != null && !probe.isConsumed()) {
                long seconds = retryAfterSeconds(probe.getNanosToWaitForRefill());
                log.warn("[RateLimit] 限流拦截: {} {} key={} retryAfterSeconds={}",
                        request.getMethod(), request.getRequestURI(), key, seconds);
                // F-121 落库旁路（现有 log.warn 同点）：观测写失败绝不影响拦截语义（见 recordHitSafely）
                recordHitSafely(request, key, (int) seconds);
                // F-123：data 增补 reason=ip_rate_limit 机读键（纯增键，前端不读时行为不变）；
                // 本拦截器管全站 @RateLimit 的 429，此处即「写侧限流」与「防枚举锁」的区分位（P4-5 登记③）。
                // F-121 落库旁路不受影响：recordHitSafely 在本行之前照旧执行，只吃同一个 seconds。
                ResponseUtil.writeJson(response, R.CODE_TOO_MANY_REQUESTS, R.MSG_TOO_MANY_REQUESTS,
                        Map.of("retryAfterSeconds", seconds, "reason", REASON_IP_RATE_LIMIT));
                return false;
            }
        }
        return true;
    }

    /**
     * 纳秒 → 「还要等几秒」的向上取整改写（F-22，静态方法便于离线单测：不起容器、不 mock servlet）。
     * <b>下限是 1</b>：nanos<=0 也回 1——回 0 等于告诉调用方「现在就能再试」，而 probe 非 consumed
     * 恰恰说明此刻不能试，那是骗人。向上取整（ceil）而非四舍五入：0.1s 报 1s 只会让运营提前重试一次，
     * 0.6s 报 0/1 之外报小了才误导。
     */
    static long retryAfterSeconds(long nanosToWaitForRefill) {
        if (nanosToWaitForRefill <= 0) {
            return 1L;
        }
        return (nanosToWaitForRefill + 999_999_999L) / 1_000_000_000L;
    }

    /**
     * F-121 落库旁路（契约 §2，在命中分支现有 log.warn 同点同步调用）：往 rate_limit_hit 写一行观测记录——
     * route = method + 空格 + URI（现成变量 request.getMethod()/getRequestURI()）、bucket_key 现成变量、
     * retryAfter 即 429 下发的同一秒数、hit_time = 命中时刻。
     * <b>观测是旁路，绝不因观测挂限流</b>：任何异常（表未建/连接抖动/字段超长截断失败…）只 log.warn，
     * 异常绝不向上抛——本方法返回时 429 的 writeJson 与 return false 照常执行，拦截语义逐字节不变
     * （契约 §4 红线：拦截判定/令牌桶/注解体系零改动，本方法不触碰桶与 probe 的任何状态）。
     */
    private void recordHitSafely(HttpServletRequest request, String bucketKeyValue, int retryAfterSeconds) {
        try {
            RateLimitHit hit = new RateLimitHit();
            hit.setRoute(request.getMethod() + " " + request.getRequestURI());
            hit.setBucketKey(bucketKeyValue);
            hit.setRetryAfter(retryAfterSeconds);
            hit.setHitTime(LocalDateTime.now());
            rateLimitHitMapper.insert(hit);
        } catch (Exception e) {
            log.warn("[RateLimit] 命中落库失败（观测旁路，不影响拦截）: {}", e.getMessage());
        }
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

    /**
     * 客户端 IP（V1 修复）：默认直取 TCP 对端地址 request.getRemoteAddr()（不可伪造）；
     * 仅 trustProxy=true（显式声明部署在可信反代后）才解析 X-Forwarded-For 首跳，
     * 防止调用方伪造/轮换 XFF 头绕过 IP 维度限流（原实现无条件信任 XFF 首跳，实测可完全绕过）。
     */
    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
