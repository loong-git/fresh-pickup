package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import com.fresh.service.DictService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 字典服务（T-M2-07 代理，F-108 字典本地化后为「本地优先、上游兜底」）：
 * - sk_ 密钥只存后端（dict.api-key），前端一律走 /api/dict 匿名代理，不再持有 key；
 * - F-108 起上游降级为「同步源」：读路径优先本地 dict_item（m23 已回填 4 组 / 23 条，与
 *   front/api/dict.js 的 FALLBACK 逐条一致），本地空且组 enabled=1 才回退上游；
 * - 组 enabled=0 → 直接返回空列表，不回退上游（契约 §0.3 的停用语义）；
 * - 本地结果与上游结果共用同一个 Caffeine 缓存（10 分钟；失败/异常不写缓存）；
 * - 上游分支保持 T-M2-07 原逻辑不变：GET {base-url}/merchant/items?categoryId=，Header X-Api-Key，
 *   3s 连接/读取超时，失败/结构异常/空词条抛 BizException 且不写缓存（前端仍保留 FALLBACK 兜底）。
 */
@Slf4j
@Service
public class DictServiceImpl implements DictService {

    /** 分组代码 → 字典服务 categoryId（契约 M2-7；映射权威在本类——front/api/dict.js 只有 GROUP_NAME、不含 id） */
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

    /** 字典分组（读 enabled 闸门 + evict 的存在性校验；本片只读） */
    @Autowired
    private DictGroupMapper dictGroupMapper;

    /** 字典词条（本地词条是 F-108 起的主读路径；上游降级为同步源） */
    @Autowired
    private DictItemMapper dictItemMapper;

    public DictServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) UPSTREAM_TIMEOUT.toMillis());
        factory.setReadTimeout((int) UPSTREAM_TIMEOUT.toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * F-108 §0.3 读路径（优先级从高到低）：
     * <ol>
     *   <li>组 enabled=0 → 空列表（<b>不回退上游</b>；每次读都过 dict_group 主键，停用立即生效）；</li>
     *   <li>Caffeine 命中（10 分钟内已算出过的结果）→ 直接返回；</li>
     *   <li>本地 dict_item 有词条 → 映射成三键出参并写缓存（<b>不调上游</b>）；</li>
     *   <li>本地空且组 enabled=1（或组行不存在，兼容 m23 未落库）→ 原样回退上游
     *       （{@code fetchFromUpstream} 一行未改：失败不写缓存、BizException 文案不变）；</li>
     *   <li>组既不在 dict_group 也不在 GROUP_ID → BizException「字典分组不存在」（改造前语义）。</li>
     * </ol>
     */
    @Override
    public List<Map<String, Object>> getGroup(String group) {
        // ① 停用闸门：enabled=0 的组恒返空，且不走上游（契约 §0.2「停用=该组按空返回，不回退上游」）
        DictGroup groupRow = dictGroupMapper.selectById(group);
        if (groupRow != null && groupRow.getEnabled() != null && groupRow.getEnabled() == 0) {
            return List.of();
        }
        // ② Caffeine：本地与上游的成功结果共用同一缓存键（组代码）
        List<Map<String, Object>> cached = cache.getIfPresent(group);
        if (cached != null) {
            return cached;
        }
        // ③ 本地优先：有词条就返回本地，上游连调都不调
        List<Map<String, Object>> local = loadLocal(group);
        if (!local.isEmpty()) {
            cache.put(group, local);
            return local;
        }
        // ④ 本地空：未知分组保持改造前的 400 语义；已知分组原样回退上游
        Integer categoryId = GROUP_ID.get(group);
        if (categoryId == null) {
            throw new BizException("字典分组不存在");
        }
        List<Map<String, Object>> upstream = cache.get(group, g -> fetchFromUpstream(categoryId));
        return upstream == null ? List.of() : upstream;
    }

    /**
     * 本地词条 → 三键出参（{@code word} / {@code definition} / {@code sortOrder}，键序固定）：
     * 排序 {@code sort_order DESC, id ASC}（与 {@code DictItemMapper#selectByGroup} 的 SQL 同口径，
     * 内存再排一次是防 DB/排序规则漂移的兜底）；NULL 语义照上游分支——word/definition 归一成空串、
     * sortOrder 归一成 0L，保证出参与改造前逐字同形。
     */
    private List<Map<String, Object>> loadLocal(String group) {
        List<DictItem> items = dictItemMapper.selectByGroup(group);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DictItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
                .comparingInt((DictItem it) -> it.getSortOrder() == null ? 0 : it.getSortOrder())
                .reversed()
                .thenComparing(DictItem::getId, Comparator.nullsLast(Long::compareTo)));
        List<Map<String, Object>> list = new ArrayList<>(sorted.size());
        for (DictItem it : sorted) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", it.getWord() == null ? "" : it.getWord());
            item.put("definition", it.getDefinition() == null ? "" : it.getDefinition());
            item.put("sortOrder", it.getSortOrder() == null ? 0L : it.getSortOrder().longValue());
            list.add(item);
        }
        return list;
    }

    /**
     * evict（契约 §0.4）：组不存在 → BizException（对外 400）；存在 → 只清该组 Caffeine 缓存。
     * <b>不做</b>「从上游强制重拉」——上游当前不可用，加这条只会造 400；清完后下一次 getGroup
     * 会重新读本地 dict_item（本地仍空时才按 §0.3 回退上游）。
     */
    @Override
    public void evict(String group) {
        if (dictGroupMapper.selectById(group) == null) {
            throw new BizException("字典分组不存在");
        }
        cache.invalidate(group);
    }

    /**
     * 上游同步（F-108 §0.8，B 语义「只增不删」）：组存在且 enabled=1 才拉上游；拉取失败由
     * {@link #fetchFromUpstream} 原样抛 BizException（不吞、不当成功）。成功时逐条 {@code INSERT IGNORE}，
     * 由 {@code uk_group_word} 判重：返回 1 计 inserted，返回 0 计 skipped；全程<b>没有 UPDATE / DELETE</b>，
     * 本地已有的行（含运营改过的 definition/sortOrder）一行都不动。有插入才 {@link #evict(String)}。
     */
    @Override
    public Map<String, Object> syncFromUpstream(String group) {
        DictGroup groupRow = group == null ? null : dictGroupMapper.selectById(group);
        if (groupRow == null) {
            throw new BizException("字典分组不存在");
        }
        if (groupRow.getEnabled() != null && groupRow.getEnabled() == 0) {
            throw new BizException("分组已停用，不同步");
        }
        Integer categoryId = GROUP_ID.get(group);
        if (categoryId == null) {
            // 有 dict_group 行但没有上游映射（将来新增的本地组）：没有可拉取的来源，明确 400 而不是拼出 categoryId=null
            throw new BizException("该分组没有上游来源，无法同步");
        }
        // 上游失败在这里抛出（BizException 400），此前未写过任何一行
        List<Map<String, Object>> upstream = fetchFromUpstream(categoryId);
        int inserted = 0;
        for (Map<String, Object> item : upstream) {
            DictItem row = new DictItem();
            row.setGroupCode(group);
            row.setWord((String) item.get("word"));
            row.setDefinition((String) item.get("definition"));
            Object sortOrder = item.get("sortOrder");
            row.setSortOrder(sortOrder instanceof Number n ? n.intValue() : 0);
            inserted += dictItemMapper.insertIgnore(row);
        }
        if (inserted > 0) {
            evict(group);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upstreamTotal", upstream.size());
        result.put("inserted", inserted);
        result.put("skipped", upstream.size() - inserted);
        return result;
    }

    /** 调上游拉取词条并归一化；任何失败转为业务异常（BizException(String) 单参默认 R.CODE_BAD_REQUEST，经全局异常处理器对外输出 code=400，前端降级 FALLBACK） */
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
