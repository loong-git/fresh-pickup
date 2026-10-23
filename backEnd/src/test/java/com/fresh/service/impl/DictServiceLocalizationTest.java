package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-108 字典本地化读路径契约单测（不起 Spring 容器、不落库、不碰真上游）。
 *
 * <p><b>要证明的事</b>（契约 §0.3 / §0.4 / §0.6）：
 * ① 本地有词条 → 返回本地且<b>不调上游</b>（{@code baseUrl} 故意留 null：真走上游必快速失败成
 *    BizException，成功返回即反证没走）；
 * ② 本地空且组 enabled=1 → 回退上游，失败仍抛原文案「字典服务暂时不可用，请稍后重试」且<b>不缓存</b>
 *    （连读两次 → 组查询/本地查询各两次）；
 * ③ 组 enabled=0 → 返空，即使本地有词条也不出、也不回退上游；
 * ④ 排序 {@code sort_order DESC, id ASC}：DB 侧钉 mapper SQL 文本，内存侧用乱序 fixture 断真实出参顺序；
 * ⑤ 出参三键 {@code word/definition/sortOrder} 且键序固定，NULL 归一成 ""/0L（与上游分支同形）；
 * ⑥ evict 清缓存后再读命中本地（缓存命中时不再查 dict_item；evict 后重新查并读到新数据）；
 * ⑦ 未知组 / evict 未知组 → BizException「字典分组不存在」（改造前语义不变）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：上游分支的真实 HTTP 行为与 Caffeine 10 分钟过期由
 * TTL 配置与主会话真库/真接口门禁覆盖；本类只把「本地优先、上游兜底、停用返空」的优先级钉死。
 */
class DictServiceLocalizationTest {

    private static final String GROUP = "dish_category";

    private final DictGroupMapper groupMapper = mock(DictGroupMapper.class);
    private final DictItemMapper itemMapper = mock(DictItemMapper.class);

    private DictServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DictServiceImpl();
        inject("dictGroupMapper", groupMapper);
        inject("dictItemMapper", itemMapper);
        // dict.base-url / dict.api-key 保持 null：任何走上游的用例都会在 fetchFromUpstream 内
        // 以「URI is not absolute」快速收口成 BizException("字典服务暂时不可用，请稍后重试")
    }

    private void inject(String field, Object value) throws Exception {
        Field f = DictServiceImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static DictGroup group(String code, int enabled) {
        DictGroup g = new DictGroup();
        g.setCode(code);
        g.setEnabled(enabled);
        return g;
    }

    private static DictItem item(long id, String word, String definition, int sortOrder) {
        DictItem it = new DictItem();
        it.setId(id);
        it.setGroupCode(GROUP);
        it.setWord(word);
        it.setDefinition(definition);
        it.setSortOrder(sortOrder);
        return it;
    }

    private static List<String> words(List<Map<String, Object>> list) {
        List<String> words = new ArrayList<>(list.size());
        for (Map<String, Object> m : list) {
            words.add((String) m.get("word"));
        }
        return words;
    }

    // ---------------------------------------------------------------- ① 本地优先

    @Test
    void 本地有词条时直接返回本地且不调上游() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                item(2L, "新鲜蔬菜", "code:vegetable", 100),
                item(1L, "肉蛋水产", "code:meat", 110)));

        List<Map<String, Object>> list = service.getGroup(GROUP);

        assertEquals(2, list.size(), "本地两条词条应原样返回");
        assertEquals("肉蛋水产", list.get(0).get("word"), "110 应排在 100 前");
        verify(itemMapper).selectByGroup(GROUP);
    }

    // ---------------------------------------------------------------- ② 本地空回退上游

    @Test
    void 本地空时回退上游且失败不缓存() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of());

        BizException first = assertThrows(BizException.class, () -> service.getGroup(GROUP));
        assertEquals("字典服务暂时不可用，请稍后重试", first.getMessage(),
                "上游分支的 BizException 文案一字不改");
        assertEquals(400, first.getCode(), "单参 BizException 默认 400");

        // 第二次仍抛同一文案，且组/本地查询各再走一遍 → 失败结果没有写进 Caffeine
        BizException second = assertThrows(BizException.class, () -> service.getGroup(GROUP));
        assertEquals("字典服务暂时不可用，请稍后重试", second.getMessage());
        verify(groupMapper, times(2)).selectById(GROUP);
        verify(itemMapper, times(2)).selectByGroup(GROUP);
    }

    // ---------------------------------------------------------------- ③ 停用返空

    @Test
    void 组停用时返空且即使本地有词条也不回退上游() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 0));
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                item(1L, "肉蛋水产", "code:meat", 110)));

        List<Map<String, Object>> list = service.getGroup(GROUP);

        assertTrue(list.isEmpty(), "enabled=0 的组必须按空返回（契约 §0.2 停用语义）");
        verifyNoInteractions(itemMapper);
    }

    // ---------------------------------------------------------------- ④ 排序

    @Test
    void 本地结果按sortOrder降序且同值按id升序() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        // 故意乱序输入：50/9、50/7、10/5 → 期望 50/7、50/9、10/5
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                item(5L, "粮油", "code:grain", 10),
                item(9L, "水果", "code:fruit", 50),
                item(7L, "生鲜", "code:fresh", 50)));

        List<Map<String, Object>> list = service.getGroup(GROUP);

        assertEquals(List.of("生鲜", "水果", "粮油"), words(list),
                "sort_order DESC，同值 id ASC（7 在 9 前）");
        assertEquals(50L, list.get(0).get("sortOrder"));
        assertEquals(10L, list.get(2).get("sortOrder"));
    }

    @Test
    void mapperSQL本身钉住sort_order降序id升序() throws Exception {
        Select select = DictItemMapper.class
                .getMethod("selectByGroup", String.class)
                .getAnnotation(Select.class);
        assertTrue(select != null, "selectByGroup 必须带 @Select");
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("ORDER BY sort_order DESC, id ASC"),
                "DB 侧排序口径必须是 sort_order DESC, id ASC，实际: " + sql);
    }

    // ---------------------------------------------------------------- ⑤ 出参三键

    @Test
    void 出参恒为wordDefinitionSortOrder三键且NULL归一() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        DictItem nulls = item(1L, null, null, 0);
        when(itemMapper.selectByGroup(GROUP)).thenReturn(List.of(
                item(2L, "肉蛋水产", "code:meat", 110), nulls));

        List<Map<String, Object>> list = service.getGroup(GROUP);

        Map<String, Object> first = list.get(0);
        assertEquals(List.of("word", "definition", "sortOrder"), new ArrayList<>(first.keySet()),
                "键与键序逐字照改造前出参");
        assertEquals("肉蛋水产", first.get("word"));
        assertEquals("code:meat", first.get("definition"));
        assertEquals(110L, first.get("sortOrder"), "sortOrder 与上游分支同为 Long");

        Map<String, Object> second = list.get(1);
        assertEquals("", second.get("word"), "word NULL 归一成空串");
        assertEquals("", second.get("definition"), "definition NULL 归一成空串");
        assertEquals(0L, second.get("sortOrder"), "sortOrder NULL 归一成 0L");
    }

    // ---------------------------------------------------------------- ⑥ evict

    @Test
    void evict清缓存后再读命中本地表() {
        when(groupMapper.selectById(GROUP)).thenReturn(group(GROUP, 1));
        when(itemMapper.selectByGroup(GROUP))
                .thenReturn(List.of(item(1L, "新鲜蔬菜", "code:vegetable", 100)))
                .thenReturn(List.of(
                        item(1L, "新鲜蔬菜", "code:vegetable", 100),
                        item(2L, "肉蛋水产", "code:meat", 110)));

        assertEquals(1, service.getGroup(GROUP).size(), "首次读本地（1 条）并写缓存");
        // 第二次：Caffeine 命中，不再查本地表（即便 mapper 第二次会返回 2 条）
        assertEquals(1, service.getGroup(GROUP).size(), "10 分钟内命中缓存");
        verify(itemMapper, times(1)).selectByGroup(GROUP);

        service.evict(GROUP);

        assertEquals(2, service.getGroup(GROUP).size(), "evict 后重新读本地表拿到新词条");
        verify(itemMapper, times(2)).selectByGroup(GROUP);
    }

    @Test
    void evict未知分组返回400() {
        when(groupMapper.selectById("no_such_group")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.evict("no_such_group"));

        assertEquals("字典分组不存在", e.getMessage());
        assertEquals(400, e.getCode());
    }

    // ---------------------------------------------------------------- ⑦ 未知组语义不变

    @Test
    void 未知分组仍按改造前语义返回400() {
        when(groupMapper.selectById("no_such_group")).thenReturn(null);
        when(itemMapper.selectByGroup("no_such_group")).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.getGroup("no_such_group"));

        assertEquals("字典分组不存在", e.getMessage());
    }
}
