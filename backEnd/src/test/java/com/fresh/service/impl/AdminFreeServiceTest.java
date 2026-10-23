package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.dto.AdminFreeActivityUpdateDTO;
import com.fresh.dto.AdminFreePoolUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.entity.FreeActivity;
import com.fresh.entity.FreeGoodsPool;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.FreeActivityMapper;
import com.fresh.mapper.FreeGoodsPoolMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-109 §0.2 免费领活动管理四操作契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① GET 活动出参九键固定且池行带 dishName/onSale（含已下架项，onSale=0）；
 * ② PUT 活动只写 dishId/dailyQuota/threshold/status 四列——body 里塞 dailyClaimed/dailyDate
 *    （驼峰与蛇形都塞）会被 Jackson 当未知字段忽略，落库 patch 的两列恒为 null；
 * ③ 换品下架/不存在 400、quota 负数 400、threshold 负数 400、status 非法 400、四列全空 400，
 *    且这些负例一次都不触达 update 与审计；
 * ④ 池空 400；池内下架商品 400，且负例都不删旧池；
 * ⑤ 池全量替换：先去重、同事务先删后插、delete 只按 activity_id 过滤（旧行一条不留）、插入顺序=去重后的入参顺序；
 * ⑥ 每个写操作成功后 admin_audit_log 落对应 action/targetType/actor，detail 是可解析 JSON 的 old/new；
 * ⑦ 单活动模型无行时读/写统一 404。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 是 mock，所以「真库 DELETE 后旧行确实消失」、
 * 「@Transactional 真的整体回滚」与「uk_activity_dish 在并发下的事实行为」不在本类证明力之内，
 * 由主会话真库门禁（改池 → 池表行数变）与 Spring 事务代理收口；
 * 另外 Spring MVC 的 @Valid 触发链也不在本类，DTO 注解值域由控制器测试的反射/校验器用例补。
 */
class AdminFreeServiceTest {

    private static final String KEY = "test-admin-key";
    private static final String ACTOR = "admin_user:7";

    /** Spring Boot MVC 的默认口径：未知 JSON 字段忽略（不是报错）——本类用它模拟「body 塞了 dailyClaimed」 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final FreeActivityMapper activityMapper = mock(FreeActivityMapper.class);
    private final FreeGoodsPoolMapper poolMapper = mock(FreeGoodsPoolMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    /**
     * {@code LambdaQueryWrapper<FreeActivity/FreeGoodsPool>} 的 eq/orderBy 是即时解析列名的，
     * 纯 Mockito 环境没有容器建 TableInfo 缓存，直接跑会抛 can not find lambda cache
     * （同 AdminMerchantApplyAuditTest 的既有处置）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, FreeActivity.class);
        TableInfoHelper.initTableInfo(assistant, FreeGoodsPool.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "freeActivityMapper", activityMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "freeGoodsPoolMapper", poolMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adminKey", KEY);
    }

    // ---------------------------------------------------------------- fixtures

    private static FreeActivity activity(Long dishId, Integer quota, Integer claimed,
                                         String date, String threshold, String status) {
        FreeActivity a = new FreeActivity();
        a.setId(1L);
        a.setDishId(dishId);
        a.setDailyQuota(quota);
        a.setDailyClaimed(claimed);
        a.setDailyDate(date == null ? null : LocalDate.parse(date));
        a.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        a.setStatus(status);
        return a;
    }

    private static Dish dish(long id, String name, int onSale) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setOnSale(onSale);
        return d;
    }

    private static FreeGoodsPool poolRow(long id, long activityId, long dishId) {
        FreeGoodsPool p = new FreeGoodsPool();
        p.setId(id);
        p.setActivityId(activityId);
        p.setDishId(dishId);
        return p;
    }

    private static AdminFreeActivityUpdateDTO activityDto(Long dishId, Integer quota, String threshold, String status) {
        AdminFreeActivityUpdateDTO dto = new AdminFreeActivityUpdateDTO();
        dto.setDishId(dishId);
        dto.setDailyQuota(quota);
        dto.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        dto.setStatus(status);
        return dto;
    }

    private static AdminFreePoolUpdateDTO poolDto(List<Long> dishIds) {
        AdminFreePoolUpdateDTO dto = new AdminFreePoolUpdateDTO();
        dto.setDishIds(dishIds);
        return dto;
    }

    private static List<String> keysOf(Map<String, Object> map) {
        return new ArrayList<>(map.keySet());
    }

    private AdminAuditLog captureAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static JsonNode readDetail(String detail) throws Exception {
        return JSON.readTree(detail);
    }

    // ---------------------------------------------------------------- ① GET 活动

    @Test
    void 活动出参九键固定且池行带商品名与在售态() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 3, "2026-10-23", "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectById(1L)).thenReturn(dish(1L, "精品五花肉", 1));
        when(poolMapper.selectList(any())).thenReturn(List.of(
                poolRow(11L, 1L, 1L), poolRow(12L, 1L, 2L)));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(1L, "精品五花肉", 1), dish(2L, "下架菜", 0)));

        Map<String, Object> out = service.freeActivity();

        assertEquals(List.of("id", "dishId", "dishName", "dailyQuota", "dailyClaimed",
                "dailyDate", "threshold", "status", "pool"), keysOf(out), "活动出参键序固定");
        assertEquals(1L, out.get("id"));
        assertEquals(1L, out.get("dishId"));
        assertEquals("精品五花肉", out.get("dishName"));
        assertEquals(100, out.get("dailyQuota"));
        assertEquals(3, out.get("dailyClaimed"));
        assertEquals(LocalDate.parse("2026-10-23"), out.get("dailyDate"));
        assertEquals(new BigDecimal("50.00"), out.get("threshold"));
        assertEquals(FreeActivity.STATUS_ONLINE, out.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pool = (List<Map<String, Object>>) out.get("pool");
        assertEquals(2, pool.size());
        assertEquals(List.of("id", "dishId", "dishName", "onSale"), keysOf(pool.get(0)), "池行四键固定");
        assertEquals(11L, pool.get(0).get("id"));
        assertEquals(1L, pool.get(0).get("dishId"));
        assertEquals("精品五花肉", pool.get(0).get("dishName"));
        assertEquals(1, pool.get(0).get("onSale"));
        assertEquals(0, pool.get(1).get("onSale"), "池里的下架商品必须如实回 onSale=0");
        verifyNoInteractions(auditMapper);   // 只读不写审计
    }

    @Test
    void 池列表出参只含四键且保持池顺序() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(poolMapper.selectList(any())).thenReturn(List.of(
                poolRow(11L, 1L, 2L), poolRow(12L, 1L, 1L)));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(1L, "精品五花肉", 1), dish(2L, "下架菜", 0)));

        List<Map<String, Object>> pool = service.freePool();

        assertEquals(List.of(2L, 1L), pool.stream().map(row -> row.get("dishId")).toList(),
                "池行 id 升序=插入序，不按 dishId 重排");
        assertEquals(List.of("id", "dishId", "dishName", "onSale"), keysOf(pool.get(0)));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 无活动行时读活动与池都返回404() {
        when(activityMapper.selectOne(any())).thenReturn(null);

        BizException read = assertThrows(BizException.class, () -> service.freeActivity());
        assertEquals(404, read.getCode());
        assertEquals("免费领活动不存在", read.getMessage());

        BizException write = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(null, 1, null, null), ACTOR));
        assertEquals(404, write.getCode());
        verifyNoInteractions(poolMapper, auditMapper);
    }

    // ---------------------------------------------------------------- ② PUT 活动：daily_claimed 不可被改

    @Test
    void 更新活动只落四个可管理列且body塞dailyClaimed不生效() throws Exception {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 3, "2026-10-23", "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectById(2L)).thenReturn(dish(2L, "时令蔬菜", 1));

        // 攻击面原样：驼峰 + 蛇形 + dailyDate 全塞进 body，目标是让管理端把计数器改小再领
        String body = "{\"dishId\":2,\"dailyQuota\":5,\"threshold\":30.5,\"status\":\"offline\","
                + "\"dailyClaimed\":0,\"daily_claimed\":0,\"dailyDate\":\"1999-01-01\","
                + "\"daily_date\":\"1999-01-01\"}";
        AdminFreeActivityUpdateDTO dto = JSON.readValue(body, AdminFreeActivityUpdateDTO.class);

        service.updateFreeActivity(dto, ACTOR);

        ArgumentCaptor<FreeActivity> patchCap = ArgumentCaptor.forClass(FreeActivity.class);
        verify(activityMapper).updateById(patchCap.capture());
        FreeActivity patch = patchCap.getValue();
        assertEquals(1L, patch.getId());
        assertEquals(2L, patch.getDishId());
        assertEquals(5, patch.getDailyQuota());
        assertEquals(0, patch.getThreshold().compareTo(new BigDecimal("30.5")));
        assertEquals(FreeActivity.STATUS_OFFLINE, patch.getStatus());
        // 承重判据：body 里塞的两列在落库实体上是 null（MP NOT_NULL 策略 ⇒ SQL 不含这两列）
        assertNull(patch.getDailyClaimed(), "daily_claimed 必须不可被管理端写入");
        assertNull(patch.getDailyDate(), "daily_date 必须不可被管理端写入");

        AdminAuditLog audit = captureAudit();
        assertEquals("free.activity.update", audit.getAction());
        assertEquals("free_activity", audit.getTargetType());
        assertEquals("1", audit.getTargetId());
        assertEquals(ACTOR, audit.getActor());
        JsonNode detail = readDetail(audit.getDetail());
        assertEquals("dishId=1,dailyQuota=100,threshold=50.00,status=online", detail.get("old").asText());
        assertEquals("dishId=2,dailyQuota=5,threshold=30.5,status=offline", detail.get("new").asText());
        assertFalse(audit.getDetail().contains("dailyClaimed"), "审计新旧值也不带运行时计数器");
    }

    @Test
    void 静态密钥轨审计actor为key摘要且不含明文() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        service.updateFreeActivity(activityDto(null, 10, null, null), null);

        AdminAuditLog audit = captureAudit();
        assertTrue(audit.getActor().startsWith("admin:"), "静态密钥轨落 admin:<摘要>");
        assertEquals("admin:".length() + 8, audit.getActor().length(), "摘要取 8 位十六进制");
        assertFalse(audit.getActor().contains(KEY), "审计 actor 绝不含密钥明文");
    }

    @Test
    void 换品下架返回400且不写库不写审计() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectById(2L)).thenReturn(dish(2L, "下架菜", 0));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(2L, null, null, null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("免费领商品不存在或已下架", e.getMessage());
        verify(activityMapper, never()).updateById(any());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 换品不存在返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectById(99L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(99L, null, null, null), ACTOR));

        assertEquals(400, e.getCode());
        verify(activityMapper, never()).updateById(any());
    }

    @Test
    void 每日限量负数返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(null, -1, null, null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("每日限量不能为负数", e.getMessage());
        verify(activityMapper, never()).updateById(any());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 门槛负数返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(null, null, "-0.01", null), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("满额门槛不能为负数", e.getMessage());
        verify(activityMapper, never()).updateById(any());
    }

    @Test
    void 状态非法返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(null, null, null, "paused"), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("活动状态仅允许 online 或 offline", e.getMessage());
        verify(activityMapper, never()).updateById(any());
    }

    @Test
    void 四列全空返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreeActivity(activityDto(null, null, null, null), ACTOR));

        assertEquals(400, e.getCode());
        verify(activityMapper, never()).updateById(any());
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ④⑤ PUT 池

    @Test
    void 池空返回400且不删旧池() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException empty = assertThrows(BizException.class,
                () -> service.updateFreePool(poolDto(List.of()), ACTOR));
        BizException nullList = assertThrows(BizException.class,
                () -> service.updateFreePool(poolDto(null), ACTOR));
        BizException emptyDto = assertThrows(BizException.class,
                () -> service.updateFreePool(null, ACTOR));

        assertEquals(400, empty.getCode());
        assertEquals("候选池至少保留一件商品", empty.getMessage());
        assertEquals("候选池至少保留一件商品", nullList.getMessage());
        assertEquals("候选池至少保留一件商品", emptyDto.getMessage());
        verify(poolMapper, never()).delete(any());
        verify(poolMapper, never()).insert(any());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 池全量替换先去重再同事务删旧插新且旧行一条不留() throws Exception {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(2L, "时令蔬菜", 1), dish(3L, "新鲜水果", 1)));
        when(poolMapper.selectList(any())).thenReturn(List.of(
                poolRow(11L, 1L, 1L), poolRow(12L, 1L, 9L)));

        service.updateFreePool(poolDto(List.of(2L, 3L, 2L)), ACTOR);

        // 删除只按 activity_id 过滤：不带 dish_id 保留名单，旧池行一条不留
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<FreeGoodsPool>> deleteCap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(poolMapper).delete(deleteCap.capture());
        String where = deleteCap.getValue().getSqlSegment();
        assertTrue(where.contains("activity_id"), "删除条件必须命中活动维度：" + where);
        assertFalse(where.contains("dish_id"), "删除条件不得带 dish_id 过滤（全量替换）：" + where);
        assertTrue(deleteCap.getValue().getParamNameValuePairs().containsValue(1L));

        // 去重后按入参顺序插两行（2,3），重复的 2 不再插入
        ArgumentCaptor<FreeGoodsPool> insertCap = ArgumentCaptor.forClass(FreeGoodsPool.class);
        verify(poolMapper, times(2)).insert(insertCap.capture());
        List<FreeGoodsPool> inserted = insertCap.getAllValues();
        assertEquals(2L, inserted.get(0).getDishId());
        assertEquals(3L, inserted.get(1).getDishId());
        assertEquals(1L, inserted.get(0).getActivityId());
        assertEquals(1L, inserted.get(1).getActivityId());

        InOrder order = inOrder(poolMapper, auditMapper);
        order.verify(poolMapper).delete(any());
        order.verify(poolMapper, times(2)).insert(any());
        order.verify(auditMapper).insert(any(AdminAuditLog.class));

        AdminAuditLog audit = captureAudit();
        assertEquals("free.pool.update", audit.getAction());
        assertEquals("free_pool", audit.getTargetType());
        assertEquals("1", audit.getTargetId());
        assertEquals(ACTOR, audit.getActor());
        JsonNode detail = readDetail(audit.getDetail());
        assertEquals("dishIds=1,9", detail.get("old").asText(), "旧池快照来自删除前的一次查询");
        assertEquals("dishIds=2,3", detail.get("new").asText());
    }

    @Test
    void 池内下架商品返回400且不删旧池() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(2L, "时令蔬菜", 1), dish(3L, "下架菜", 0)));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreePool(poolDto(List.of(2L, 3L)), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("候选商品不存在或已下架", e.getMessage());
        verify(poolMapper, never()).delete(any());
        verify(poolMapper, never()).insert(any());
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 池内商品不存在返回400且不删旧池() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(dish(2L, "时令蔬菜", 1)));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreePool(poolDto(List.of(2L, 99L)), ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("候选商品不存在或已下架", e.getMessage());
        verify(poolMapper, never()).delete(any());
        verify(poolMapper, never()).insert(any());
    }

    @Test
    void 池内null元素返回400() {
        when(activityMapper.selectOne(any())).thenReturn(
                activity(1L, 100, 0, null, "50.00", FreeActivity.STATUS_ONLINE));

        BizException e = assertThrows(BizException.class,
                () -> service.updateFreePool(poolDto(java.util.Arrays.asList(2L, null)), ACTOR));

        assertEquals(400, e.getCode());
        verify(poolMapper, never()).delete(any());
        verify(poolMapper, never()).insert(any());
    }
}
