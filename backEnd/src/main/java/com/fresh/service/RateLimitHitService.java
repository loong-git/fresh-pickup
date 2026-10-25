package com.fresh.service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 限流观测服务（F-121 §2）：两个消费者——运营聚合端点（只读）与每日清理任务（DELETE）。
 * 纯只读观测面，无任何写操作端点（契约 §2：审计不适用）。
 */
public interface RateLimitHitService {

    /**
     * 运营端命中聚合（F-121 §2）：days ∈ {1,7,30} 白名单（缺省 7，白名单外 400），
     * 出参 {@code {days, total, list:[{route, bucketKey, count, lastHitAt}]}}——
     * list 按 count 倒序 top 100，total 为该时间档内命中总次数（全量计数，不受截断影响）。
     */
    Map<String, Object> hits(Integer days);

    /** 删除指定时刻之前的全部命中行（供清理任务；返回删除行数，任务侧记日志） */
    int deleteHitsBefore(LocalDateTime before);
}
