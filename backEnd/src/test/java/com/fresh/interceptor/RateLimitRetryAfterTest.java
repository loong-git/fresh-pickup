package com.fresh.interceptor;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-22：429 的 {@code data.retryAfterSeconds} 换算离线单测（契约 BE-429-4）。
 * 只测 {@link RateLimitInterceptor#retryAfterSeconds(long)} 这根纯换算 + 真 bucket 的字段读数，
 * <b>不起 Spring 容器、不连库、不 mock servlet</b>（本仓测试全离线是既有性质）。
 * 信封出参（HTTP 200 + code=429 + data.retryAfterSeconds）由主会话门禁以真实 curl 验，不在此重复。
 */
class RateLimitRetryAfterTest {

    @Test
    @DisplayName("nanos<=0（含负数）仍回 1，绝不回 0——0 等于说「现在就能再试」，是骗人")
    void 零与负数也回一秒() {
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(0L));
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(-1L));
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(Long.MIN_VALUE));
    }

    @Test
    @DisplayName("1 纳秒向上取整成 1 秒：只要不足一秒也算「还要等一秒」")
    void 一纳秒回一秒() {
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(1L));
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(999_999_999L));
    }

    @Test
    @DisplayName("整秒不越过（1s→1），越过 1 纳秒即进位（1s+1ns→2）")
    void 整秒边界() {
        assertEquals(1L, RateLimitInterceptor.retryAfterSeconds(1_000_000_000L));
        assertEquals(2L, RateLimitInterceptor.retryAfterSeconds(1_000_000_001L));
    }

    @Test
    @DisplayName("非整秒一律 ceil：2.5s→3，59.999999999s→60（不会四舍五入报小）")
    void 非整秒向上取整() {
        assertEquals(3L, RateLimitInterceptor.retryAfterSeconds(2_500_000_000L));
        assertEquals(60L, RateLimitInterceptor.retryAfterSeconds(59_999_999_999L));
        assertEquals(120L, RateLimitInterceptor.retryAfterSeconds(119_000_000_001L));
    }

    /**
     * 真桶（契约点名的最小档：2 枚 / 4s greedy，即每 2s 回 1 枚）。
     * 证两件我们靠它说话的事实：① 桶满时 probe.isConsumed() 为真；
     * ② 配额吃光后第 3 次确实拿不到令牌，且 getNanosToWaitForRefill() 是个正在倒数的正数。
     */
    @Test
    @DisplayName("真桶 2/4s greedy：连吃 3 枚后第 3 次未消费，回速纳秒 > 0 且换算成 1~2 秒")
    void 真桶回速字段真的会动() {
        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.classic(2, Refill.greedy(2, Duration.ofSeconds(4))))
                .build();

        assertTrue(bucket.tryConsumeAndReturnRemaining(1).isConsumed(), "第 1 枚应有令牌");
        assertTrue(bucket.tryConsumeAndReturnRemaining(1).isConsumed(), "第 2 枚应有令牌");

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        assertFalse(probe.isConsumed(), "容量 2 的第 3 枚应被拒");
        assertTrue(probe.getNanosToWaitForRefill() > 0, "被拒时回速纳秒必须为正，实测=" + probe.getNanosToWaitForRefill());
        long seconds = RateLimitInterceptor.retryAfterSeconds(probe.getNanosToWaitForRefill());
        assertTrue(seconds >= 1 && seconds <= 2, "0.5 枚/秒的回速应落在 1~2 秒，实测=" + seconds);
    }

    /**
     * 防取错字段的断言（主会话门禁 4/5 的离线版）：用 StoreController:27 的真实档
     * （{@code @RateLimit(keyType=IP, limit=30, windowSeconds=60)} ⇒ 30/60s greedy，每 2s 回 1 枚）。
     * 下发的是「最早可再试」（Refill 维度，约 2s），不是「整个窗口重置」（Reset 维度，约 60s）。
     * 变异自验（2026-10-13 实测）：把 :98 的 {@code getNanosToWaitForRefill()} 换成
     * {@code getNanosToWaitForReset()} → 本条变红，断言消息读数 <b>60</b>（正常为 2）。
     * 注：生产 preHandle 里同一表达式被换成 Reset 时，本套离线测试全绿（87/87）——
     * 契约禁起容器/禁 mock servlet，preHandle 不可离线触达，该回归只能由门禁 4 的真实 curl 抓。
     */
    @Test
    @DisplayName("真桶 30/60s greedy：retryAfterSeconds 取 Refill（≤2）而远非 Reset（≈60）")
    void 取的是回速而不是整窗重置() {
        Bucket bucket = Bucket.builder()
                .addLimit(Bandwidth.classic(30, Refill.greedy(30, Duration.ofSeconds(60))))
                .build();

        ConsumptionProbe probe = null;
        for (int i = 0; i < 31; i++) {
            probe = bucket.tryConsumeAndReturnRemaining(1);
        }
        assertFalse(probe.isConsumed(), "30 枚吃光后第 31 次应被拒");
        assertTrue(probe.getNanosToWaitForRefill() < probe.getNanosToWaitForReset(),
                "两根字段必须可区分：refill=" + probe.getNanosToWaitForRefill() + " reset=" + probe.getNanosToWaitForReset());

        long refillSeconds = RateLimitInterceptor.retryAfterSeconds(probe.getNanosToWaitForRefill());
        assertTrue(refillSeconds >= 1 && refillSeconds <= 2,
                "greedy 30/60 的回速是每 2 秒 1 枚，下发值必须明显小于 60，实测=" + refillSeconds);
    }
}
