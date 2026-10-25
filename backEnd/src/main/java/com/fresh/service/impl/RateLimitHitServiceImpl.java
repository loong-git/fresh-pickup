package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.mapper.RateLimitHitMapper;
import com.fresh.service.RateLimitHitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 限流观测实现（F-121）：聚合的排序与 top-100 截断在 Java 侧执行（SQL 只做 WHERE + GROUP BY），
 * 排序语义离线单测可真实断言（契约 §5-2「聚合分组与排序断言」）；两条查询共用同一 since 窗口。
 * 白名单三档 {1,7,30}（缺省 7），白名单外 400——照 AdminV2Service 的脏参数处置（复用既有 400 信封）。
 */
@Slf4j
@Service
public class RateLimitHitServiceImpl implements RateLimitHitService {

    /** 天数档白名单（契约 §2：默认 7，允许 1/7/30 三档） */
    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 7, 30);
    private static final int DEFAULT_DAYS = 7;

    /** 聚合出参上限（契约 §2：按 count 倒序 top 100） */
    private static final int TOP_LIMIT = 100;

    @Autowired
    private RateLimitHitMapper rateLimitHitMapper;

    @Override
    public Map<String, Object> hits(Integer days) {
        int windowDays = days == null ? DEFAULT_DAYS : days;
        if (!ALLOWED_DAYS.contains(windowDays)) {
            throw new BizException(R.CODE_BAD_REQUEST, "days 仅支持 1/7/30");
        }
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);
        // 分组行 → count 倒序（平局按 bucketKey 升序保证确定性）→ top 100 截断
        List<Map<String, Object>> rows = new ArrayList<>(rateLimitHitMapper.selectAggregates(since));
        Comparator<Map<String, Object>> byCountDesc =
                Comparator.comparingLong((Map<String, Object> row) -> ((Number) row.get("hitCount")).longValue())
                        .reversed();
        rows.sort(byCountDesc.thenComparing(row -> String.valueOf(row.get("bucketKey"))));
        List<Map<String, Object>> list = rows.size() > TOP_LIMIT ? rows.subList(0, TOP_LIMIT) : rows;

        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Map<String, Object> row : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("route", row.get("route"));
            item.put("bucketKey", row.get("bucketKey"));
            item.put("count", ((Number) row.get("hitCount")).longValue());
            item.put("lastHitAt", row.get("lastHitAt"));
            out.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", windowDays);
        data.put("total", rateLimitHitMapper.countHitsSince(since));
        data.put("list", out);
        return data;
    }

    @Override
    public int deleteHitsBefore(LocalDateTime before) {
        return rateLimitHitMapper.deleteByHitTimeBefore(before);
    }
}
