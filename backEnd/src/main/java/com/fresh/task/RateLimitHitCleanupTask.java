package com.fresh.task;

import com.fresh.service.RateLimitHitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 限流命中日志每日清理调度（F-121 §2，照 OrderTimeoutTask 模式，@EnableScheduling 在 FreshApplication 开启）：
 * - cron 每日一次（03:30 运营低峰，写死常量——契约 §4 红线不新增 yml 键）；
 * - 删除口径钉在 RateLimitHitMapper#deleteByHitTimeBefore：DELETE WHERE hit_time &lt; cutoff
 *   （cutoff = 现在 − 30 天），只动窗口外旧行、30 天内一行不碰；删除行数进本类日志（一行含行数）；
 * - 异常不打断调度：Spring 默认 ErrorHandler 会记录 ERROR 日志，下一日照常执行。
 */
@Slf4j
@Component
public class RateLimitHitCleanupTask {

    /** 保留窗口（天）：契约 §2 定稿 30 天 */
    private static final int RETENTION_DAYS = 30;

    /** 每日 03:30 清理（cron 六位：秒 分 时 日 月 周） */
    private static final String DAILY_CRON = "0 30 3 * * ?";

    @Autowired
    private RateLimitHitService rateLimitHitService;

    @Scheduled(cron = DAILY_CRON)
    public void cleanupExpiredHits() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = rateLimitHitService.deleteHitsBefore(cutoff);
        log.info("限流命中日志清理完成: cutoff={}, deleted={}", cutoff, deleted);
    }
}
