package com.fresh.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（T-M2-06）：挂在控制器方法上，由 RateLimitInterceptor 读取执行。
 * 令牌桶语义：每 windowSeconds 秒补充 limit 个令牌（Refill.greedy），平滑限流。
 * 契约 M2-6 三个挂载点：下单 3 次/分钟/用户、评价 1 次/10s/用户、字典代理 10 次/分钟/IP。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 窗口内允许的请求次数 */
    int limit();

    /** 窗口长度（秒） */
    int windowSeconds();

    /** 限流维度：USER=登录用户（匿名回退 IP 兜底）；IP=客户端IP+路径 */
    KeyType keyType() default KeyType.USER;

    enum KeyType {
        USER,
        IP
    }
}
