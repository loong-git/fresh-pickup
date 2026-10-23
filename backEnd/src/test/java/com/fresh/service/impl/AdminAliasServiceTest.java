package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.AdminAliasCreateDTO;
import com.fresh.dto.AdminAliasUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.entity.DishAlias;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishAliasMapper;
import com.fresh.mapper.DishMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-110 搜索别名四操作契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 列表出参五键 {@code id/alias/dishId/dishName/onSale}，保持 mapper 的 alias 升序，
 *    下架商品如实回 onSale=0（下架件持有别名是合法状态）；
 * ② 新增：alias trim 入库、dishId 存在即可（不要求在售）、写审计 {@code alias.create}；
 * ③ 别名重复（预查命中与 uk_alias 并发兜底两条路）→ 400「该别名已存在」，不落库不写审计；
 * ④ dishId 不存在 → 400，不落库；别名空白/超长 → 400；
 * ⑤ 部分更新：只落传入列、两列全空 400、id 不存在 404、改后撞 uk_alias 400；
 * ⑥ 删除：id 不存在 404；成功写审计 {@code alias.delete}，新值固定 deleted。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真实 uk_alias 的 1062 行为、MySQL 对
 * utf8mb4_unicode_ci 的等值判定、mapper 的 ORDER BY alias 是否真按库排序——本类用
 * DuplicateKeyException 模拟唯一键冲突、用 mock 的 selectAllOrdered 返回既定顺序；
 * 真库行为由主会话的 MySQL 门禁覆盖。
 */
class AdminAliasServiceTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";

    private final DishAliasMapper aliasMapper = mock(DishAliasMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "dishAliasMapper", aliasMapper);
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static DishAlias alias(long id, String alias, long dishId) {
        DishAlias row = new DishAlias();
        row.setId(id);
        row.setAlias(alias);
        row.setDishId(dishId);
        return row;
    }

    private static Dish dish(long id, String name, int onSale) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setOnSale(onSale);
        return d;
    }

    private static AdminAliasCreateDTO createDto(String alias, Long dishId) {
        AdminAliasCreateDTO dto = new AdminAliasCreateDTO();
        dto.setAlias(alias);
        dto.setDishId(dishId);
        return dto;
    }

    private static AdminAliasUpdateDTO updateDto(String alias, Long dishId) {
        AdminAliasUpdateDTO dto = new AdminAliasUpdateDTO();
        dto.setAlias(alias);
        dto.setDishId(dishId);
        return dto;
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- ① 列表

    @Test
    void 列表出参五键_保持mapper顺序_下架商品onSale为0() {
        when(aliasMapper.selectAllOrdered()).thenReturn(List.of(
                alias(1L, "土豆", 16L),
                alias(2L, "洋芋", 16L),
                alias(3L, "西红柿", 9L)));
        when(dishMapper.selectBatchIds(any())).thenReturn(List.of(
                dish(16L, "高山土豆", 1),
                dish(9L, "沙瓤西红柿", 0)));

        List<Map<String, Object>> out = service.aliases();

        assertEquals(3, out.size());
        assertEquals(List.of("id", "alias", "dishId", "dishName", "onSale"),
                new ArrayList<>(out.get(0).keySet()), "列表键序固定为 id/alias/dishId/dishName/onSale");
        assertEquals(1L, out.get(0).get("id"));
        assertEquals("土豆", out.get(0).get("alias"));
        assertEquals(16L, out.get(0).get("dishId"));
        assertEquals("高山土豆", out.get(0).get("dishName"));
        assertEquals(1, out.get(0).get("onSale"));
        assertEquals("洋芋", out.get(1).get("alias"));
        assertEquals("沙瓤西红柿", out.get(2).get("dishName"));
        assertEquals(0, out.get(2).get("onSale"), "下架商品必须如实回 onSale=0");
    }

    @Test
    void 列表为空时返回空数组且不查商品() {
        when(aliasMapper.selectAllOrdered()).thenReturn(List.of());

        List<Map<String, Object>> out = service.aliases();

        assertTrue(out.isEmpty());
        verify(dishMapper, never()).selectBatchIds(any());
    }

    // ---------------------------------------------------------------- ② 新增

    @Test
    void 新增正例_aliasTrim入库_下架商品可挂别名_写审计aliasCreate() {
        when(dishMapper.selectById(16L)).thenReturn(dish(16L, "高山土豆", 0));
        when(aliasMapper.selectByAlias("洋芋2")).thenReturn(null);
        when(aliasMapper.insert(any(DishAlias.class))).thenAnswer(inv -> {
            inv.getArgument(0, DishAlias.class).setId(99L);   // 模拟 MP 自增 id 回填
            return 1;
        });

        service.createAlias(createDto("  洋芋2  ", 16L), ADMIN_USER_ACTOR);

        ArgumentCaptor<DishAlias> captor = ArgumentCaptor.forClass(DishAlias.class);
        verify(aliasMapper).insert(captor.capture());
        DishAlias inserted = captor.getValue();
        assertEquals("洋芋2", inserted.getAlias(), "alias 必须 trim 后入库");
        assertEquals(16L, inserted.getDishId());
        assertEquals("洋芋2", inserted.getAlias());

        AdminAuditLog audit = capturedAudit();
        assertEquals("alias.create", audit.getAction());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor(), "运营 JWT 轨 actor 原样采用 grant.actor()");
        assertEquals("dish_alias", audit.getTargetType());
        assertEquals("99", audit.getTargetId());
        assertTrue(audit.getDetail().contains("alias=洋芋2"), "审计 detail 必须含新值");
    }

    @Test
    void 新增别名重复400_不落库不写审计() {
        when(dishMapper.selectById(16L)).thenReturn(dish(16L, "高山土豆", 1));
        when(aliasMapper.selectByAlias("洋芋")).thenReturn(alias(1L, "洋芋", 16L));

        BizException e = assertThrows(BizException.class,
                () -> service.createAlias(createDto("洋芋", 16L), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该别名已存在", e.getMessage(), "文案必须是契约逐字那句");
        verify(aliasMapper, never()).insert(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 新增撞uk_alias并发兜底同样400() {
        when(dishMapper.selectById(16L)).thenReturn(dish(16L, "高山土豆", 1));
        when(aliasMapper.selectByAlias("洋芋2")).thenReturn(null);
        when(aliasMapper.insert(any(DishAlias.class))).thenThrow(new DuplicateKeyException("uk_alias"));

        BizException e = assertThrows(BizException.class,
                () -> service.createAlias(createDto("洋芋2", 16L), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该别名已存在", e.getMessage());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 新增dishId不存在400_不落库不写审计() {
        when(dishMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.createAlias(createDto("洋芋2", 404L), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("商品不存在", e.getMessage());
        verify(aliasMapper, never()).insert(any(DishAlias.class));
        verify(aliasMapper, never()).selectByAlias(any());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 新增别名空白400_不落库不写审计() {
        BizException e = assertThrows(BizException.class,
                () -> service.createAlias(createDto("   ", 16L), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("别名不能为空", e.getMessage());
        verify(aliasMapper, never()).insert(any(DishAlias.class));
        verifyNoInteractions(dishMapper);
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 新增别名超50字符400() {
        BizException e = assertThrows(BizException.class,
                () -> service.createAlias(createDto("x".repeat(51), 16L), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("别名最长 50 个字符", e.getMessage());
        verify(aliasMapper, never()).insert(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ③ 更新

    @Test
    void 更新正例_只落传入列_写审计aliasUpdate() {
        when(aliasMapper.selectById(5L)).thenReturn(alias(5L, "洋芋", 16L));
        when(aliasMapper.selectByAlias("洋芋头")).thenReturn(null);
        when(aliasMapper.updateById(any(DishAlias.class))).thenReturn(1);

        service.updateAlias(5L, updateDto("  洋芋头  ", null), ADMIN_USER_ACTOR);

        ArgumentCaptor<DishAlias> captor = ArgumentCaptor.forClass(DishAlias.class);
        verify(aliasMapper).updateById(captor.capture());
        DishAlias patch = captor.getValue();
        assertEquals(5L, patch.getId());
        assertEquals("洋芋头", patch.getAlias(), "alias 必须 trim");
        assertNull(patch.getDishId(), "未传的 dishId 必须保持 null（updateById 的 NOT_NULL 策略 = 不落该列）");
        verify(dishMapper, never()).selectById(any());

        AdminAuditLog audit = capturedAudit();
        assertEquals("alias.update", audit.getAction());
        assertEquals("dish_alias", audit.getTargetType());
        assertEquals("5", audit.getTargetId());
        assertTrue(audit.getDetail().contains("alias=洋芋"), "旧值取方法开头快照");
        assertTrue(audit.getDetail().contains("alias=洋芋头"), "新值取合并后的结果");
        assertTrue(audit.getDetail().contains("dishId=16"), "未改的 dishId 也进新值，审计看到完整两列");
    }

    @Test
    void 更新只改dishId_下架商品也允许() {
        when(aliasMapper.selectById(5L)).thenReturn(alias(5L, "洋芋", 16L));
        when(dishMapper.selectById(9L)).thenReturn(dish(9L, "沙瓤西红柿", 0));
        when(aliasMapper.updateById(any(DishAlias.class))).thenReturn(1);

        service.updateAlias(5L, updateDto(null, 9L), ADMIN_USER_ACTOR);

        ArgumentCaptor<DishAlias> captor = ArgumentCaptor.forClass(DishAlias.class);
        verify(aliasMapper).updateById(captor.capture());
        assertNull(captor.getValue().getAlias(), "未传的 alias 不落列");
        assertEquals(9L, captor.getValue().getDishId());
        assertEquals("alias.update", capturedAudit().getAction());
    }

    @Test
    void 更新id不存在404() {
        when(aliasMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateAlias(404L, updateDto("洋芋头", null), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("别名不存在", e.getMessage());
        verify(aliasMapper, never()).updateById(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新两列全空400() {
        when(aliasMapper.selectById(5L)).thenReturn(alias(5L, "洋芋", 16L));

        BizException e = assertThrows(BizException.class,
                () -> service.updateAlias(5L, updateDto(null, null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("无更新字段（可传 alias / dishId）", e.getMessage());
        verify(aliasMapper, never()).updateById(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新别名空白400() {
        when(aliasMapper.selectById(5L)).thenReturn(alias(5L, "洋芋", 16L));

        BizException e = assertThrows(BizException.class,
                () -> service.updateAlias(5L, updateDto("  ", null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("别名不能为空", e.getMessage());
        verify(aliasMapper, never()).updateById(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新改后撞uk_alias400_不落库不写审计() {
        when(aliasMapper.selectById(5L)).thenReturn(alias(5L, "洋芋", 16L));
        when(aliasMapper.selectByAlias("土豆")).thenReturn(alias(1L, "土豆", 16L));

        BizException e = assertThrows(BizException.class,
                () -> service.updateAlias(5L, updateDto("土豆", null), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("该别名已存在", e.getMessage());
        verify(aliasMapper, never()).updateById(any(DishAlias.class));
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ④ 删除

    @Test
    void 删除正例_写审计aliasDelete() {
        when(aliasMapper.selectById(3L)).thenReturn(alias(3L, "洋芋", 16L));
        when(aliasMapper.deleteById(3L)).thenReturn(1);

        service.deleteAlias(3L, ADMIN_USER_ACTOR);

        verify(aliasMapper).deleteById(3L);
        AdminAuditLog audit = capturedAudit();
        assertEquals("alias.delete", audit.getAction());
        assertEquals("dish_alias", audit.getTargetType());
        assertEquals("3", audit.getTargetId());
        assertTrue(audit.getDetail().contains("alias=洋芋"), "删除审计要留旧值");
        assertTrue(audit.getDetail().contains("\"new\":\"deleted\""), "新值固定 deleted");
    }

    @Test
    void 删除id不存在404_不写审计() {
        when(aliasMapper.selectById(404L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.deleteAlias(404L, ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("别名不存在", e.getMessage());
        verify(aliasMapper, never()).deleteById(404L);
        verifyNoInteractions(auditMapper);
    }
}
