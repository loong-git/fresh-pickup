package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Store;
import com.fresh.entity.StoreApply;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.mapper.StoreMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-18.12 第 3 条 门店申请审核契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>补测动机</b>：P11-2 审计轮 3 P2 缺口 #1——{@code AdminServiceImpl#auditStoreApply}
 * （approve→INSERT store 是门店进 {@code GET /api/stores} 的<b>唯一</b>发布通道，D-12/A2.1 数据纪律）
 * 此前零测试；桩与断言口径照 {@code AdminMerchantApplyAuditTest}（同族入驻审核先例）。
 *
 * <p><b>要证明的事</b>（逐支实读 :504-579）：
 * ① action 不在 {@code ALLOWED_AUDIT_ACTIONS} 白名单 → 400 且不查库；
 * ② reject 意见超 255 字 → 400 且不查库（audit_remark VARCHAR(255) 列宽纪律）；缺失则<b>不拦</b>
 *    （本方法与 {@code auditMerchantApply} 不同：reject 不强制 remark，实读规则如实钉死）；
 * ③ 申请不存在 → 404「申请不存在」；
 * ④ 已 approved 再 approve → 幂等早退成功：零 UPDATE、零 INSERT store、零审计；
 * ⑤ pending→approve 成功：条件 UPDATE（status='pending' 闸门）→ INSERT store 且
 *    name/address/lng/lat/service/owner_user_id/contact_phone 全部取自申请行快照、status='open'，
 *    审计 {@code store_apply.audit} 的 detail 只出脱敏手机号；
 * ⑥ 审核结论不可翻转（rejected→approve / approved→reject）→ 400「该申请已审核完成，不可再次审核」；
 * ⑦ 条件 UPDATE 0 行 → 回查：已翻到同一目标态 = 并发幂等成功（不重复发布、不写审计）；
 * ⑧ 回查行已消失 → 与「申请不存在」同码 404。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 都是 mock，事务回滚与 uk 的真实 1062
 * 不在证明力内——本类只钉 Service 的分支语义与写侧意图，真实回滚由主会话真库门禁覆盖。
 */
class AdminStoreApplyAuditTest {

    private static final Long APPLY_ID = 9L;
    private static final Long OWNER_ID = 7L;
    private static final String PHONE = "13800001234";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final StoreApplyMapper applyMapper = mock(StoreApplyMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    /**
     * {@code LambdaUpdateWrapper<StoreApply>} 的 eq/set 即时解析列名，纯 Mockito 环境没有容器建
     * TableInfo 缓存（照 AdminMerchantApplyAuditTest 的既有处置）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, StoreApply.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminServiceImpl();
        inject("storeApplyMapper", applyMapper);
        inject("storeMapper", storeMapper);
        inject("adminAuditLogMapper", auditMapper);
        inject("adminKey", "test-admin-key");
    }

    private void inject(String name, Object value) throws Exception {
        Field f = AdminServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** 申请行 fixture：过审后 store 行的全部字段都取自这里（快照来源断言的锚点） */
    private static StoreApply apply(String status) {
        StoreApply a = new StoreApply();
        a.setId(APPLY_ID);
        a.setUserId(OWNER_ID);
        a.setProposedName("悦邻生鲜店");
        a.setProposedAddress("佛山市顺德区悦邻路 1 号");
        a.setLng(new BigDecimal("113.123456"));
        a.setLat(new BigDecimal("22.654321"));
        a.setService("冷冻冷藏");
        a.setContactName("张三");
        a.setContactPhone(PHONE);
        a.setStatus(status);
        return a;
    }

    private AdminAuditLog captureAudit() {
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(cap.capture());
        return cap.getValue();
    }

    private JsonNode readDetail(AdminAuditLog row) {
        try {
            return json.readTree(row.getDetail());
        } catch (Exception e) {
            throw new AssertionError("审计 detail 不是合法 JSON: " + row.getDetail(), e);
        }
    }

    // ---------------------------------------------------------------- 入参闸门

    @Test
    void 非法action判四百且一次库都不查() {
        BizException e = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "delete", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("action 仅允许 approve（过审并发布门店）或 reject（驳回）", e.getMessage());
        verify(applyMapper, never()).selectById(any());
        verify(applyMapper, never()).update(any(), any());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void reject意见超长判四百且不查库_缺失则合法置态() {
        // 超长（256 字 > audit_remark VARCHAR(255)）：判据在查库之前，不花那次 SELECT
        BizException e = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "reject", "长".repeat(256), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("审核意见不能超过 255 字", e.getMessage());
        verify(applyMapper, never()).selectById(any());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));

        // 实读规则：本方法（不同于 auditMerchantApply）不强制 reject 必填 remark——缺失/空白照常驳回成功
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(applyMapper.update(any(), any())).thenReturn(1);

        service.auditStoreApply(APPLY_ID, "reject", null, "admin_user:2");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<StoreApply>> cap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(any(), cap.capture());
        Map<String, Object> params = cap.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("rejected"), "SET status='rejected'：params=" + params);
        assertTrue(params.values().stream().anyMatch(v -> v instanceof java.time.LocalDateTime),
                "SET audited_at=NOW()：params=" + params);
        assertTrue(!params.containsValue(null),
                "audit_remark 不 SET（remark 为 null 时不产生 null 绑定，避 MP JdbcType OTHER 坑）：params=" + params);
        verify(storeMapper, never()).insert(any(Store.class));
    }

    // ---------------------------------------------------------------- 404 / 幂等

    @Test
    void 申请不存在判四百零四且零写() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(404, e.getCode());
        assertEquals("申请不存在", e.getMessage());
        verify(applyMapper, never()).update(any(), any());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void 已approved再审幂等早退零写零发布() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("approved"));

        service.auditStoreApply(APPLY_ID, "approve", "重复点了一遍", "admin_user:2");

        verify(applyMapper, never()).update(any(), any());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    // ---------------------------------------------------------------- approve 成功

    @Test
    void pending过审发布门店_字段取申请快照_审计脱敏留痕() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(applyMapper.update(any(), any())).thenReturn(1);
        when(storeMapper.insert(any(Store.class))).thenAnswer(inv -> {
            inv.getArgument(0, Store.class).setId(30L);   // 模拟 MP 自增 id 回填
            return 1;
        });

        service.auditStoreApply(APPLY_ID, " approve ", "  资料齐全  ", "admin_user:2");

        // ① 条件 UPDATE 真带着 pending 闸门与目标值
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<StoreApply>> applyCap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(any(), applyCap.capture());
        Map<String, Object> params = applyCap.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("approved"), "SET status='approved'：params=" + params);
        assertTrue(params.containsValue("资料齐全"), "SET audit_remark=trim(remark)：params=" + params);
        assertTrue(params.values().stream().anyMatch(v -> v instanceof java.time.LocalDateTime),
                "SET audited_at=NOW()：params=" + params);

        // ② INSERT store：名称/地址/坐标/服务/联系电话/归属全部取自申请行，绝不收请求侧归属入参
        ArgumentCaptor<Store> storeCap = ArgumentCaptor.forClass(Store.class);
        verify(storeMapper).insert(storeCap.capture());
        Store store = storeCap.getValue();
        assertEquals("悦邻生鲜店", store.getName());
        assertEquals("佛山市顺德区悦邻路 1 号", store.getAddress());
        assertEquals(new BigDecimal("113.123456"), store.getLng());
        assertEquals(new BigDecimal("22.654321"), store.getLat());
        assertEquals("冷冻冷藏", store.getService());
        assertEquals("open", store.getStatus(), "过审即营业（A2.1：过审发布固定置 open）");
        assertEquals(OWNER_ID, store.getOwnerUserId(), "owner_user_id 唯一来源=申请行 user_id（RF）");
        assertEquals(PHONE, store.getContactPhone());

        // ③ 审计：action/target 固定，old=置态前快照，new 含状态+remark+脱敏手机号+storeId
        AdminAuditLog row = captureAudit();
        assertEquals("admin_user:2", row.getActor());
        assertEquals("store_apply.audit", row.getAction());
        assertEquals("store_apply", row.getTargetType());
        assertEquals(String.valueOf(APPLY_ID), row.getTargetId());
        JsonNode detail = readDetail(row);
        assertEquals("status=pending", detail.get("old").asText());
        String newSide = detail.get("new").asText();
        assertTrue(newSide.contains("status=approved"), "new=" + newSide);
        assertTrue(newSide.contains("remark=资料齐全"), "new=" + newSide);
        assertTrue(newSide.contains("maskedPhone=138****1234"), "联系电话只出脱敏值（RD）：new=" + newSide);
        assertTrue(newSide.contains("storeId=30"), "new=" + newSide);
        assertTrue(!row.getDetail().contains(PHONE), "审计 detail 绝不落明文手机号（RD）");

        // 同事务三写顺序：查申请 → 条件 UPDATE → INSERT store → 审计
        InOrder order = inOrder(applyMapper, storeMapper, auditMapper);
        order.verify(applyMapper).selectById(APPLY_ID);
        order.verify(applyMapper).update(any(), any());
        order.verify(storeMapper).insert(any(Store.class));
        order.verify(auditMapper).insert(any(AdminAuditLog.class));
    }

    // ---------------------------------------------------------------- 翻转 / 并发回查

    @Test
    void 审核结论不可翻转_rejected再审approve判四百() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("rejected"));

        BizException e = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该申请已审核完成，不可再次审核", e.getMessage());
        verify(applyMapper, never()).update(any(), any());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));

        // 反向同理：approved 再 reject 同样封死（翻转要连带处理已发布的店，本期不提供入口）
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("approved"));
        BizException reverse = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "reject", null, "admin_user:2"));
        assertEquals(400, reverse.getCode());
        assertEquals("该申请已审核完成，不可再次审核", reverse.getMessage());
        verify(storeMapper, never()).insert(any(Store.class));
    }

    @Test
    void 条件更新零行回查已同目标态则并发幂等成功零写() {
        // 并发窗口内另一笔已翻到同一目标态：UPDATE 0 行 → 回查命中 → 与开头幂等早退同义
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"),
                apply("approved"));
        when(applyMapper.update(any(), any())).thenReturn(0);

        service.auditStoreApply(APPLY_ID, "approve", null, "admin_user:2");

        verify(applyMapper, times(2)).selectById(APPLY_ID);
        verify(applyMapper, times(1)).update(any(), any());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void 条件更新零行且回查行消失判四百零四() {
        // 行在两次读之间消失（Admin 手工清库等极端情形）：与「申请不存在」同码同文案
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"),
                (StoreApply) null);
        when(applyMapper.update(any(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () ->
                service.auditStoreApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(404, e.getCode());
        assertEquals("申请不存在", e.getMessage());
        verify(storeMapper, never()).insert(any(Store.class));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }
}
