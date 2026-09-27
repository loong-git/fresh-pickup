package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.service.DictService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字典代理服务（T-M2-07，契约 M2-7）：
 * - sk_ 密钥只存后端（dict.api-key），前端一律走 /api/dict 匿名代理，不再持有 key；
 * - 上游 GET {base-url}/merchant/items?categoryId=，Header X-Api-Key 鉴权，返回 {code:0, data:[...]}；
 * - Caffeine 缓存 10 分钟；失败/超时/结构异常/空词条不写缓存并抛业务异常（前端保留 FALLBACK 降级）；
 * - 3s 连接/读取超时，与前端原 dictRequest 超时语义一致。
 */
@Slf4j
@Service
public class DictServiceImpl implements DictService {

    /** 分组代码 → 字典服务 categoryId（契约 M2-7，与 front/api/dict.js GROUP_ID 一致，省一次分类列表请求） */
    private static final Map<String, Integer> GROUP_ID = Map.of(
            "dish_category", 4,
            "coupon_template", 3,
            "kingkong_entry", 5,
            "service_tags", 2);

    /** 本地缓存 TTL：10 分钟（契约 M2-7） */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** 上游超时：连接/读取各 3s */
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(3);

    @Value("${dict.base-url}")
    private String baseUrl;

    @Value("${dict.api-key}")
    private String apiKey;

    /** 分组 → 词条缓存；loader 抛异常即不写入（与前端"失败不缓存、保留 FALLBACK"策略对齐） */
    private final Cache<String, List<Map<String, Object>>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(100)
            .build();

    private final RestTemplate restTemplate;

    public DictServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) UPSTREAM_TIMEOUT.toMillis());
        factory.setReadTimeout((int) UPSTREAM_TIMEOUT.toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<Map<String, Object>> getGroup(String group) {
        Integer categoryId = GROUP_ID.get(group);
        if (categoryId == null) {
            throw new BizException("字典分组不存在");
        }
        List<Map<String, Object>> cached = cache.get(group, g -> fetchFromUpstream(categoryId));
        return cached == null ? List.of() : cached;
    }

    /** 调上游拉取词条并归一化；任何失败转为业务异常（500 兜底由全局异常处理器输出，前端降级 FALLBACK） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> fetchFromUpstream(Integer categoryId) {
        String url = baseUrl + "/merchant/items?categoryId=" + categoryId;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", apiKey);
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = resp.getBody();
            Object code = body == null ? null : body.get("code");
            Object data = body == null ? null : body.get("data");
            // 上游约定：{code:0, data:[...]}
            if (!(code instanceof Number codeNum) || codeNum.intValue() != 0 || !(data instanceof List)) {
                throw new IllegalStateException("字典上游返回结构异常");
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object o : (List<?>) data) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> raw = (Map<?, ?>) o;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("word", raw.get("word") == null ? "" : String.valueOf(raw.get("word")));
                item.put("definition", raw.get("definition") == null ? "" : String.valueOf(raw.get("definition")));
                item.put("sortOrder", raw.get("sortOrder") instanceof Number n ? n.longValue() : 0L);
                list.add(item);
            }
            list.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("sortOrder")).reversed());
            if (list.isEmpty()) {
                throw new IllegalStateException("字典词条为空");
            }
            return list;
        } catch (Exception e) {
            // 不透出上游细节（key/URL/堆栈），前端按失败降级 FALLBACK；不写缓存便于恢复后自动重试
            log.warn("字典上游拉取失败: categoryId={}, err={}", categoryId, e.getClass().getSimpleName());
            throw new BizException("字典服务暂时不可用，请稍后重试");
        }
    }
}
