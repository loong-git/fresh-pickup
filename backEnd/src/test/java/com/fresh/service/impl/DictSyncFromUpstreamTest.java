package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * F-108 §0.8 上游同步（B 语义「只增不删」）契约单测（不起 Spring 容器、不落库、不碰真上游）。
 *
 * <p><b>要证明的事</b>：
 * ① 本地缺 2 条时 {@code inserted=2 / skipped=N}，且只发 {@code insertIgnore} 这一种写，
 *    <b>没有任何 update/delete/insert</b>（本地既有行含运营改过的 definition/sortOrder 一行不动）；
 * ② 上游抛异常 → 原样抛 {@code fetchFromUpstream} 的 BizException（code=400，文案逐字），
 *    且本地<b>一行未动</b>（itemMapper 零交互）；
 * ③ 停用组 → 400「分组已停用，不同步」，不拉上游、不写本地；组不存在 → 400「字典分组不存在」；
 * ④ 有插入才 evict 该组缓存（无插入不 evict）；
 * ⑤ {@code insertIgnore} 的 SQL 文本是纯 {@code INSERT IGNORE}，不含 UPDATE / REPLACE / DELETE 分支——
 *    「B 语义」在语句层就没有「改已有行」的可能。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真实 MySQL 上 {@code uk_group_word} 的 1062→IGNORE 行为、
 * 真实上游返回结构（当前上游不可用）由主会话真库/真接口门禁覆盖；本类用 mock 的
 * {@code insertIgnore} 返回值模拟「本地已有=0 / 本地缺=1」。
 */
class DictSyncFromUpstreamTest {

    private static final String GROUP = "dish_category";

    private final DictGroupMapper groupMapper = mock(DictGroupMapper.class);
    private final DictItemMapper itemMapper = mock(DictItemMapper.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);

    private DictServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DictServiceImpl();
        ReflectionTestUtils.setField(service, "dictGroupMapper", groupMapper);
        ReflectionTestUtils.setField(service, "dictItemMapper", itemMapper);
        // fetchFromUpstream 内部持有构造期创建的 RestTemplate；这里替换成 mock 以模拟上游成功/失败
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "baseUrl", "http://upstream.test");
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static DictGroup group(String code, int enabled) {
        DictGroup g = new DictGroup();
        g.setCode(code);
        g.setEnabled(enabled);
        return g;
    }

    private static Map<String, Object> upstreamItem(String word, String definition, long sortOrder) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("word", word);
        item.put("definition", definition);
        item.put("sortOrder", sortOrder);
        return item;
    }

    private static DictItem localItem(long id, String word, String definition, int sortOrder) {
        DictItem it = new DictItem();
        it.setId(id);
        it.setGroupCode(GROUP);
        it.setWord(word);
        it.setDefinition(definition);
        it.setSortOrder(sortOrder);
        return it;
    }

    /** 模拟上游 {code:0, data:[...]} 成功响应（与 fetchFromUpstream 的解析口径一致） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void upstreamReturns(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", items);
        ResponseEntity<Map> response = ResponseEntity.ok(body);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);
    }

    // ---------------------------------------------------------------- ① 只增不删

    @Test
    void 本地缺两条时inserted为2且已有词条一行未动() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        // 上游 5 条：前三条与本地同名（本地已被运营改过），后两条本地没有
        upstreamReturns(List.of(
                upstreamItem("肉蛋水产", "code:meat-upstream", 999),
                upstreamItem("新鲜蔬菜", "code:vegetable-upstream", 888),
                upstreamItem("水果", "code:fruit-upstream", 777),
                upstreamItem("海鲜", "code:seafood", 60),
                upstreamItem("熟食", "code:cooked", 50)));
        Set<String> existingWords = Set.of("肉蛋水产", "新鲜蔬菜", "水果");
        when(itemMapper.insertIgnore(any(DictItem.class))).thenAnswer(inv -> {
            String word = inv.getArgument(0, DictItem.class).getWord();
            return existingWords.contains(word) ? 0 : 1;
        });
        // 本地既有行快照：运营手工改过的 definition / sortOrder（同步后必须逐字仍在）
        DictItem existingMeat = localItem(1L, "肉蛋水产", "code:meat-local-edited", 120);
        DictItem existingVeg = localItem(2L, "新鲜蔬菜", "code:vegetable-local-edited", 115);

        Map<String, Object> result = service.syncFromUpstream(GROUP);

        assertEquals(List.of("upstreamTotal", "inserted", "skipped"), new ArrayList<>(result.keySet()),
                "出参键序固定 upstreamTotal/inserted/skipped");
        assertEquals(5, result.get("upstreamTotal"));
        assertEquals(2, result.get("inserted"), "本地缺的海鲜/熟食两条应计入 inserted");
        assertEquals(3, result.get("skipped"), "本地已有的三条应计入 skipped");

        ArgumentCaptor<DictItem> captor = ArgumentCaptor.forClass(DictItem.class);
        verify(itemMapper, times(5)).insertIgnore(captor.capture());
        for (DictItem row : captor.getAllValues()) {
            assertEquals(GROUP, row.getGroupCode());
            assertNotNull(row.getWord());
        }
        // 唯一写语句就是 5 次 INSERT IGNORE：没有 update / delete / insert / selectByGroup
        verifyNoMoreInteractions(itemMapper);

        assertEquals("code:meat-local-edited", existingMeat.getDefinition(), "本地已有 definition 逐字未变");
        assertEquals(120, existingMeat.getSortOrder(), "本地已有 sortOrder 逐字未变");
        assertEquals("code:vegetable-local-edited", existingVeg.getDefinition());
        assertEquals(115, existingVeg.getSortOrder());
    }

    @Test
    void insertIgnore的SQL是纯INSERT_IGNORE不含更新分支() throws Exception {
        Insert insert = DictItemMapper.class.getMethod("insertIgnore", DictItem.class).getAnnotation(Insert.class);
        assertNotNull(insert, "insertIgnore 必须带 @Insert");
        String sql = String.join(" ", insert.value()).toUpperCase(Locale.ROOT);
        assertTrue(sql.startsWith("INSERT IGNORE INTO DICT_ITEM"), "实际: " + sql);
        assertFalse(sql.contains("UPDATE"), "B 语义不允许 ON DUPLICATE KEY UPDATE，实际: " + sql);
        assertFalse(sql.contains("REPLACE"), "REPLACE 会先删后插，等价改已有行，实际: " + sql);
        assertFalse(sql.contains("DELETE"), "同步不许删除任何既有行，实际: " + sql);
    }

    // ---------------------------------------------------------------- ② 上游失败不破坏本地

    @Test
    void 上游失败原样抛BizException400且本地一行未动() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        BizException e = assertThrows(BizException.class, () -> service.syncFromUpstream(GROUP));

        assertEquals(400, e.getCode());
        assertEquals("字典服务暂时不可用，请稍后重试", e.getMessage(), "fetchFromUpstream 的文案必须原样上抛");
        // 关键断言：失败时没有 insert / update / delete / 任何本地读写
        verifyNoInteractions(itemMapper);
    }

    @Test
    void 上游返回结构异常同样400且本地一行未动() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        Map<String, Object> badBody = new LinkedHashMap<>();
        badBody.put("code", 500);
        badBody.put("data", List.of());
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) badBody));

        BizException e = assertThrows(BizException.class, () -> service.syncFromUpstream(GROUP));

        assertEquals(400, e.getCode());
        assertEquals("字典服务暂时不可用，请稍后重试", e.getMessage());
        verifyNoInteractions(itemMapper);
    }

    // ---------------------------------------------------------------- ③ 闸门

    @Test
    void 停用组400且不拉上游不写本地() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 0));

        BizException e = assertThrows(BizException.class, () -> service.syncFromUpstream(GROUP));

        assertEquals(400, e.getCode());
        assertEquals("分组已停用，不同步", e.getMessage());
        verifyNoInteractions(restTemplate, itemMapper);
    }

    @Test
    void 组不存在400且不拉上游不写本地() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.syncFromUpstream("nope"));

        assertEquals(400, e.getCode());
        assertEquals("字典分组不存在", e.getMessage());
        verifyNoInteractions(restTemplate, itemMapper);
    }

    @Test
    void 有分组行但无上游映射时400且不拉上游() {
        when(groupMapper.selectById("local_only")).thenReturn(group("local_only", 1));

        BizException e = assertThrows(BizException.class, () -> service.syncFromUpstream("local_only"));

        assertEquals(400, e.getCode());
        assertEquals("该分组没有上游来源，无法同步", e.getMessage());
        verifyNoInteractions(restTemplate, itemMapper);
    }

    // ---------------------------------------------------------------- ④ evict 时机

    @Test
    void 有插入才evict缓存_无插入保持缓存() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                localItem(1L, "肉蛋水产", "code:meat", 110)));
        // 先读一次把本地结果放进 Caffeine
        assertEquals(1, service.getGroup(GROUP).size());

        // 上游只返回本地已有的那一条 → inserted=0 → 不应 evict
        upstreamReturns(List.of(upstreamItem("肉蛋水产", "code:meat", 110)));
        when(itemMapper.insertIgnore(any(DictItem.class))).thenReturn(0);
        Map<String, Object> noInsert = service.syncFromUpstream(GROUP);
        assertEquals(0, noInsert.get("inserted"));
        service.getGroup(GROUP);
        verify(itemMapper, times(1)).selectByGroup(GROUP);   // 命中缓存，未重读

        // 上游多一条本地没有的 → inserted=1 → 必须 evict，下一次读到新增后的本地表
        upstreamReturns(List.of(
                upstreamItem("肉蛋水产", "code:meat", 110),
                upstreamItem("海鲜", "code:seafood", 60)));
        when(itemMapper.insertIgnore(any(DictItem.class))).thenAnswer(inv ->
                "海鲜".equals(inv.getArgument(0, DictItem.class).getWord()) ? 1 : 0);
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                localItem(1L, "肉蛋水产", "code:meat", 110),
                localItem(9L, "海鲜", "code:seafood", 60)));
        Map<String, Object> inserted = service.syncFromUpstream(GROUP);
        assertEquals(1, inserted.get("inserted"));

        assertEquals(2, service.getGroup(GROUP).size(), "evict 后应重读本地表并看到新增词条");
        verify(itemMapper, times(2)).selectByGroup(GROUP);
    }
}
