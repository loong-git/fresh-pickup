package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.AdminDictGroupUpdateDTO;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.service.DictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-108 §0.7 片 2 字典管理端六操作契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 分组列表出参五键 {@code code/name/enabled/itemCount/sort}，无词条组 itemCount=0；
 * ② 词条列表出参四键 {@code id/word/definition/sortOrder}，顺序沿用 mapper；
 * ③ 新增：trim 入库、sortOrder 缺省 0、分组不存在 400、word 空白 400、uk 冲突 400 一句中文；
 * ④ 更新：部分更新只落传入列、id 不存在 404、三列全空 400、改后撞 uk 冲突 400；
 * ⑤ 删除：dish_category 有商品引用 → 400 且文案带真实数量、不删不 evict 不审计；
 *    非 dish_category 组直接删、<b>不查 dish</b>；dish_category 零引用可删；id 不存在 404；
 * ⑥ 每个写操作成功后 {@code DictService#evict} 被调用，且 admin_audit_log 落对应 action/actor；
 * ⑦ actor 双轨归一：运营 JWT 轨原样采用 {@code admin_user:<id>}，静态密钥轨（null）落摘要、不含明文。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真实 uk_group_word 的 1062 行为、DishMapper 的 SQL 是否真按
 * category 等值（由主会话真库门禁覆盖）；本类用 DuplicateKeyException 模拟唯一键冲突、
 * 用 mock 的 countByCategory 返回值模拟引用态。
 */
class AdminDictServiceTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";
    private static final String KEY = "test-admin-key";

    private final DictGroupMapper groupMapper = mock(DictGroupMapper.class);
    private final DictItemMapper itemMapper = mock(DictItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final DictService dictService = mock(DictService.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "dictGroupMapper", groupMapper);
        ReflectionTestUtils.setField(service, "dictItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "dictService", dictService);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", KEY);
    }

    // ---------------------------------------------------------------- fixtures

    private static DictGroup group(String code, String name, int enabled, int sort) {
        DictGroup g = new DictGroup();
        g.setCode(code);
        g.setName(name);
        g.setEnabled(enabled);
        g.setSort(sort);
        return g;
    }

    private static DictItem item(long id, String groupCode, String word, String definition, int sortOrder) {
        DictItem it = new DictItem();
        it.setId(id);
        it.setGroupCode(groupCode);
        it.setWord(word);
        it.setDefinition(definition);
        it.setSortOrder(sortOrder);
        return it;
    }

    private static AdminDictItemCreateDTO createDto(String word, String definition, Integer sortOrder) {
        AdminDictItemCreateDTO dto = new AdminDictItemCreateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private static AdminDictItemUpdateDTO updateDto(String word, String definition, Integer sortOrder) {
        AdminDictItemUpdateDTO dto = new AdminDictItemUpdateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private static AdminDictGroupUpdateDTO groupDto(String name, Integer enabled) {
        AdminDictGroupUpdateDTO dto = new AdminDictGroupUpdateDTO();
        dto.setName(name);
        dto.setEnabled(enabled);
        return dto;
    }

    private static Map<String, Object> countRow(String groupCode, long itemCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupCode", groupCode);
        row.put("itemCount", itemCount);
        return row;
    }

    private static List<String> keysOf(Map<String, Object> map) {
        return new ArrayList<>(map.keySet());
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- ① 分组列表

    @Test
    void 分组列表出参五键且无词条组计数为零() {
        when(groupMapper.selectAllOrdered()).thenReturn(List.of(
                group("dish_category", "商品分类", 1, 10),
                group("coupon_template", "优惠券配置", 0, 20)));
        when(itemMapper.countByGroup()).thenReturn(List.of(countRow("dish_category", 11L)));

        List<Map<String, Object>> out = service.dictGroups();

        assertEquals(2, out.size());
        assertEquals(List.of("code", "name", "enabled", "itemCount", "sort"), keysOf(out.get(0)),
                "分组列表键序固定为 code/name/enabled/itemCount/sort");
        assertEquals("dish_category", out.get(0).get("code"));
        assertEquals(11, out.get(0).get("itemCount"));
        assertEquals(0, out.get(1).get("itemCount"), "没有词条的组必须回 0");
        assertEquals(0, out.get(1).get("enabled"), "停用组如实回 0");
    }

    // ---------------------------------------------------------------- ② 词条列表

    @Test
    void 词条列表出参四键且保持mapper顺序() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));
        when(itemMapper.selectByGroup("dish_category")).thenReturn(List.of(
                item(2L, "dish_category", "新鲜蔬菜", "code:vegetable", 100),
                item(1L, "dish_category", "肉蛋水产", "code:meat", 110)));

        List<Map<String, Object>> out = service.dictItems("dish_category");

        assertEquals(2, out.size());
        assertEquals(List.of("id", "word", "definition", "sortOrder"), keysOf(out.get(0)));
        assertEquals(2L, out.get(0).get("id"));
        assertEquals("新鲜蔬菜", out.get(0).get("word"));
        assertEquals("code:vegetable", out.get(0).get("definition"));
        assertEquals(100, out.get(0).get("sortOrder"));
    }

    @Test
    void 词条列表分组不存在400() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.dictItems("nope"));

        assertEquals(400, e.getCode());
        assertEquals("字典分组不存在", e.getMessage());
        verify(itemMapper, never()).selectByGroup(anyString());
    }

    // ---------------------------------------------------------------- ③ 新增

    @Test
    void 新增词条正例_trim入库_sortOrder缺省0_写后evict并审计() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));
        when(itemMapper.insert(any(DictItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, DictItem.class).setId(99L);   // 模拟 MP 自增 id 回填
            return 1;
        });

        service.createDictItem("dish_category", createDto("  海鲜  ", " code:seafood ", null), ADMIN_USER_ACTOR);

        ArgumentCaptor<DictItem> itemCaptor = ArgumentCaptor.forClass(DictItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        DictItem inserted = itemCaptor.getValue();
        assertEquals("dish_category", inserted.getGroupCode());
        assertEquals("海鲜", inserted.getWord(), "word 必须 trim 后入库");
        assertEquals("code:seafood", inserted.getDefinition(), "definition 必须 trim 后入库");
        assertEquals(0, inserted.getSortOrder(), "sortOrder 缺省 0");

        verify(dictService).evict("dish_category");
        AdminAuditLog audit = capturedAudit();
        assertEquals("dict.item.create", audit.getAction());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor(), "运营 JWT 轨 actor 原样采用 grant.actor()");
        assertEquals("dict_item", audit.getTargetType());
        assertEquals("99", audit.getTargetId());
        assertTrue(audit.getDetail().contains("word=海鲜"), "审计 detail 必须含新值");
    }

    @Test
    void 新增词条分组不存在400且不落库不evict() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.createDictItem("nope", createDto("海鲜", null, 1), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("字典分组不存在", e.getMessage());
        verify(itemMapper, never()).insert(any(DictItem.class));
        verifyNoInteractions(dictService);
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 新增词条word空白400且不落库() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));

        BizException e = assertThrows(BizException.class,
                () -> service.createDictItem("dish_category", createDto("   ", null, null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("词条名称不能为空", e.getMessage());
        verify(itemMapper, never()).insert(any(DictItem.class));
        verifyNoInteractions(dictService);
    }

    @Test
    void 新增撞唯一键回400一句中文且不evict不审计() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));
        when(itemMapper.insert(any(DictItem.class))).thenThrow(new DuplicateKeyException("uk_group_word"));

        BizException e = assertThrows(BizException.class,
                () -> service.createDictItem("dish_category", createDto("海鲜", "code:seafood", 1), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该分组下已存在同名词条", e.getMessage());
        verify(dictService, never()).evict(anyString());
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ④ 更新

    @Test
    void 更新词条正例_只落传入列_写后evict并审计() {
        when(itemMapper.selectById(5L))
                .thenReturn(item(5L, "dish_category", "肉蛋水产", "code:meat", 110));
        when(itemMapper.updateById(any(DictItem.class))).thenReturn(1);

        service.updateDictItem(5L, updateDto(" 海鲜 ", null, 120), ADMIN_USER_ACTOR);

        ArgumentCaptor<DictItem> patchCaptor = ArgumentCaptor.forClass(DictItem.class);
        verify(itemMapper).updateById(patchCaptor.capture());
        DictItem patch = patchCaptor.getValue();
        assertEquals(5L, patch.getId());
        assertEquals("海鲜", patch.getWord(), "word 必须 trim");
        assertNull(patch.getDefinition(), "未传的列必须保持 null（updateById 的 NOT_NULL 策略 = 不落该列）");
        assertEquals(120, patch.getSortOrder());

        verify(dictService).evict("dish_category");
        AdminAuditLog audit = capturedAudit();
        assertEquals("dict.item.update", audit.getAction());
        assertEquals("5", audit.getTargetId());
        assertTrue(audit.getDetail().contains("肉蛋水产"), "detail 旧值取方法开头快照");
        assertTrue(audit.getDetail().contains("word=海鲜"), "detail 新值取合并后的结果");
    }

    @Test
    void 更新词条id不存在404() {
        when(itemMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateDictItem(404L, updateDto("海鲜", null, null), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("词条不存在", e.getMessage());
        verify(itemMapper, never()).updateById(any(DictItem.class));
        verifyNoInteractions(dictService);
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新词条三列全空400() {
        when(itemMapper.selectById(5L))
                .thenReturn(item(5L, "dish_category", "肉蛋水产", "code:meat", 110));

        BizException e = assertThrows(BizException.class,
                () -> service.updateDictItem(5L, updateDto(null, null, null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("无更新字段（可传 word / definition / sortOrder）", e.getMessage());
        verify(itemMapper, never()).updateById(any(DictItem.class));
        verifyNoInteractions(dictService);
    }

    @Test
    void 更新撞唯一键回400一句中文且不evict不审计() {
        when(itemMapper.selectById(5L))
                .thenReturn(item(5L, "dish_category", "肉蛋水产", "code:meat", 110));
        when(itemMapper.updateById(any(DictItem.class))).thenThrow(new DuplicateKeyException("uk_group_word"));

        BizException e = assertThrows(BizException.class,
                () -> service.updateDictItem(5L, updateDto("新鲜蔬菜", null, null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该分组下已存在同名词条", e.getMessage());
        verify(dictService, never()).evict(anyString());
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ⑤ 删除 + 引用保护

    @Test
    void 删除dish_category被引用时400且带真实数量_不删不evict不审计() {
        when(itemMapper.selectById(3L))
                .thenReturn(item(3L, "dish_category", "肉蛋水产", "code:meat", 110));
        when(dishMapper.countByCategory("meat")).thenReturn(28L);

        BizException e = assertThrows(BizException.class,
                () -> service.deleteDictItem(3L, ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该分类下还有 28 个商品，删除后它们会变成无主分类", e.getMessage(),
                "文案必须是契约逐字那句，N 为真实引用数");
        verify(dishMapper).countByCategory("meat");
        verify(itemMapper, never()).deleteById(3L);
        verify(dictService, never()).evict(anyString());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 删除非dish_category组直接删_不查dish并写后evict() {
        when(itemMapper.selectById(8L))
                .thenReturn(item(8L, "service_tags", "次日达", "tag:nextday", 10));
        when(itemMapper.deleteById(8L)).thenReturn(1);

        service.deleteDictItem(8L, ADMIN_USER_ACTOR);

        verify(dishMapper, never()).countByCategory(anyString());
        verify(itemMapper).deleteById(8L);
        verify(dictService).evict("service_tags");
        AdminAuditLog audit = capturedAudit();
        assertEquals("dict.item.delete", audit.getAction());
        assertEquals("dict_item", audit.getTargetType());
        assertEquals("8", audit.getTargetId());
        assertTrue(audit.getDetail().contains("次日达"), "删除审计要留旧值");
        assertTrue(audit.getDetail().contains("\"new\":\"deleted\""), "新值固定 deleted");
    }

    @Test
    void 删除dish_category零引用可删() {
        when(itemMapper.selectById(3L))
                .thenReturn(item(3L, "dish_category", "肉蛋水产", "code:meat", 110));
        when(dishMapper.countByCategory("meat")).thenReturn(0L);
        when(itemMapper.deleteById(3L)).thenReturn(1);

        service.deleteDictItem(3L, ADMIN_USER_ACTOR);

        verify(dishMapper).countByCategory("meat");
        verify(itemMapper).deleteById(3L);
        verify(dictService).evict("dish_category");
        assertEquals("dict.item.delete", capturedAudit().getAction());
    }

    @Test
    void 删除词条id不存在404() {
        when(itemMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.deleteDictItem(404L, ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("词条不存在", e.getMessage());
        verify(itemMapper, never()).deleteById(404L);
        verifyNoInteractions(dishMapper);
        verifyNoInteractions(dictService);
    }

    // ---------------------------------------------------------------- ⑥ 更新分组

    @Test
    void 更新分组正例_写后evict并审计() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));
        when(groupMapper.updateById(any(DictGroup.class))).thenReturn(1);

        service.updateDictGroup("dish_category", groupDto(" 食品分类 ", 0), ADMIN_USER_ACTOR);

        ArgumentCaptor<DictGroup> patchCaptor = ArgumentCaptor.forClass(DictGroup.class);
        verify(groupMapper).updateById(patchCaptor.capture());
        DictGroup patch = patchCaptor.getValue();
        assertEquals("dish_category", patch.getCode());
        assertEquals("食品分类", patch.getName(), "name 必须 trim");
        assertEquals(0, patch.getEnabled());

        verify(dictService).evict("dish_category");
        AdminAuditLog audit = capturedAudit();
        assertEquals("dict.group.update", audit.getAction());
        assertEquals("dict_group", audit.getTargetType());
        assertEquals("dish_category", audit.getTargetId());
        assertTrue(audit.getDetail().contains("enabled=0"));
    }

    @Test
    void 更新分组code不存在404() {
        when(groupMapper.selectById("nope")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateDictGroup("nope", groupDto("新名", 1), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("字典分组不存在", e.getMessage());
        verify(groupMapper, never()).updateById(any(DictGroup.class));
        verifyNoInteractions(dictService);
    }

    @Test
    void 更新分组无字段400() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));

        BizException e = assertThrows(BizException.class,
                () -> service.updateDictGroup("dish_category", groupDto(null, null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("无更新字段（可传 name / enabled）", e.getMessage());
        verify(groupMapper, never()).updateById(any(DictGroup.class));
        verifyNoInteractions(dictService);
    }

    // ---------------------------------------------------------------- ⑥.5 §0.8 上游同步

    private static Map<String, Object> syncResult(int upstreamTotal, int inserted, int skipped) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("upstreamTotal", upstreamTotal);
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        return result;
    }

    @Test
    void 同步正例_委派DictService并按三个计数写dict_sync审计() {
        Map<String, Object> result = syncResult(5, 2, 3);
        when(dictService.syncFromUpstream("dish_category")).thenReturn(result);

        Map<String, Object> out = service.syncDictGroup("dish_category", ADMIN_USER_ACTOR);

        assertSame(result, out, "端点出参就是 DictService 返回的那三个数字");
        verify(dictService).syncFromUpstream("dish_category");
        AdminAuditLog audit = capturedAudit();
        assertEquals("dict.sync", audit.getAction());
        assertEquals("dict_group", audit.getTargetType());
        assertEquals("dish_category", audit.getTargetId());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor());
        assertTrue(audit.getDetail().contains("upstreamTotal=5"), "detail 带上游总条数");
        assertTrue(audit.getDetail().contains("inserted=2"), "detail 带本次新增数");
        assertTrue(audit.getDetail().contains("skipped=3"), "detail 带跳过数");
    }

    @Test
    void 同步上游失败原样上抛且不写审计() {
        BizException upstream = new BizException("字典服务暂时不可用，请稍后重试");
        when(dictService.syncFromUpstream("dish_category")).thenThrow(upstream);

        BizException e = assertThrows(BizException.class,
                () -> service.syncDictGroup("dish_category", ADMIN_USER_ACTOR));

        assertSame(upstream, e, "必须原样抛 fetchFromUpstream 的 BizException，不许换文案/吞掉");
        assertEquals(400, e.getCode());
        verifyNoInteractions(auditMapper, groupMapper, itemMapper);
    }

    // ---------------------------------------------------------------- ⑦ actor 双轨归一

    @Test
    void 静态密钥轨审计actor落摘要且不含明文() {
        when(groupMapper.selectById("dish_category"))
                .thenReturn(group("dish_category", "商品分类", 1, 10));
        when(itemMapper.insert(any(DictItem.class))).thenAnswer(inv -> {
            inv.getArgument(0, DictItem.class).setId(100L);
            return 1;
        });

        service.createDictItem("dish_category", createDto("海鲜", "code:seafood", 1), null);

        String actor = capturedAudit().getActor();
        assertTrue(actor.startsWith("admin:"), "静态密钥轨沿用 admin:<key 摘要 8>");
        assertFalse(actor.contains(KEY), "审计 actor 绝不含密钥明文");
    }
}
