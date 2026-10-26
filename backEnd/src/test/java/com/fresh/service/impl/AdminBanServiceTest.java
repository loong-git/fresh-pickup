package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-122 运营端封禁服务契约单测（照 AdminMerchantApplyListTest 的 mock 风格：直接 new，不起 Spring 容器）。
 *
 * <p><b>要证明的事</b>（契约 §3.4 / §5.1）：
 * ① 用户 ban/unban 的状态迁移与返回文案；实际迁移才写审计（action/targetType/targetId/detail 逐键）；
 * ② 用户幂等 no-op（已 banned 再 ban / 已 active 再 unban）不写库、不写审计；
 * ③ 404「用户不存在」、action 白名单外 400 且零库操作；
 * ④ 商家 ban→suspended / unban→active 与 no-op 文案；terminated 两端点均 400「该商家已清退，不可变更封禁状态」；
 * ⑤ 用户/商家列表五键形状、行六键与键序、phone/contactPhone 脱敏（序列化整包无明文）、filters 回显归一值；
 * ⑥ 分页边界/主键 0/status 白名单外一律 400 且 mapper 零调用；商家 status 三态白名单均放行。
 *
 * <p><b>本类测不成什么</b>：mapper 是 mock，分页 SQL 的 ORDER BY、WHERE 精确等值与真库脱敏取数不在证明力内，
 * 由 {@code logs/verify-ban-system.sh} 真库验收覆盖。
 */
class AdminBanServiceTest {

    private static final String ACTOR = "admin_user:1";
    private static final String PHONE = "13800001234";
    private static final String MASKED = "138****1234";
    private static final String ACTION_MSG = "action 仅允许 ban/unban";
    private static final String MERCHANT_LOCKED_MSG = "该商家已清退，不可变更封禁状态";
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final UserMapper userMapper = mock(UserMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    /**
     * 封禁/解封走 LambdaUpdateWrapper，其列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 装配）；
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 SettlementModelTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, User.class);
        TableInfoHelper.initTableInfo(assistant, Merchant.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        // actor 显式传 admin_user:1 时 auditActor 原样采用，无需读 adminKey；仍灌一枚防未来分支误取默认
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
    }

    private static User user(long id, String status) {
        User u = new User();
        u.setId(id);
        u.setPhone(PHONE);
        u.setStatus(status);
        return u;
    }

    private static Merchant merchant(long id, String status) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setName("F-122测试商家");
        m.setContactPhone(PHONE);
        m.setContactName("张三");
        m.setStatus(status);
        return m;
    }

    /** 用户列表一行：键名与 Mapper SELECT 别名逐字一致（phone 是原文） */
    private static Map<String, Object> userRow(String phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9L);
        row.put("phone", phone);
        row.put("role", "user");
        row.put("merchantId", null);
        row.put("status", "banned");
        row.put("createdAt", "2026-10-22 10:00:00");
        return row;
    }

    /** 商家列表一行：键名与 Mapper SELECT 别名逐字一致（contactPhone 是原文） */
    private static Map<String, Object> merchantRow(String phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 3L);
        row.put("name", "F-122测试商家");
        row.put("contactPhone", phone);
        row.put("contactName", "张三");
        row.put("status", "suspended");
        row.put("createdAt", "2026-10-22 10:00:00");
        return row;
    }

    private AdminAuditLog captureSingleAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("list");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filtersOf(Map<String, Object> data) {
        return (Map<String, Object>) data.get("filters");
    }

    private static void assertBadRequest(BizException ex, String expectedMessage) {
        assertEquals(R.CODE_BAD_REQUEST, ex.getCode());
        assertEquals(expectedMessage, ex.getMessage());
    }

    // ================================================================
    // 用户封禁/解封
    // ================================================================

    /** active → banned：返回「用户已封禁」，落库一列并写 ban.create / user 审计 */
    @Test
    void 用户封禁迁移状态并写审计() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, User.STATUS_ACTIVE));
        when(userMapper.update(isNull(), any())).thenReturn(1);

        String message = service.updateUserStatus(9L, " ban ", ACTOR);

        assertEquals("用户已封禁", message);
        verify(userMapper).update(isNull(), any());

        AdminAuditLog row = captureSingleAudit();
        assertEquals(ACTOR, row.getActor());
        assertEquals("ban.create", row.getAction());
        assertEquals("user", row.getTargetType());
        assertEquals("9", row.getTargetId());
        assertEquals("{\"old\":\"active\",\"new\":\"banned\"}", row.getDetail());
        assertTrue(row.getDetail().contains("\"old\":\"active\""));
        assertTrue(row.getDetail().contains("\"new\":\"banned\""));
    }

    /** 已 banned 再 ban：幂等 no-op，不写库不写审计 */
    @Test
    void 用户重复封禁幂等且零写零审计() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, User.STATUS_BANNED));

        assertEquals("用户已处于封禁状态", service.updateUserStatus(9L, "ban", ACTOR));

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** banned → active：返回「用户已解封」，写 unban 审计 */
    @Test
    void 用户解封迁移状态并写审计() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, User.STATUS_BANNED));
        when(userMapper.update(isNull(), any())).thenReturn(1);

        assertEquals("用户已解封", service.updateUserStatus(9L, "unban", ACTOR));

        AdminAuditLog row = captureSingleAudit();
        assertEquals("unban", row.getAction());
        assertEquals("user", row.getTargetType());
        assertEquals("9", row.getTargetId());
        assertEquals("{\"old\":\"banned\",\"new\":\"active\"}", row.getDetail());
    }

    /** 已 active 再 unban：幂等 no-op，不写库不写审计 */
    @Test
    void 用户重复解封幂等且零写零审计() {
        when(userMapper.selectById(9L)).thenReturn(user(9L, User.STATUS_ACTIVE));

        assertEquals("用户已处于正常状态", service.updateUserStatus(9L, "unban", ACTOR));

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** 用户不存在：404「用户不存在」，零写零审计 */
    @Test
    void 用户不存在返回404() {
        when(userMapper.selectById(999999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.updateUserStatus(999999L, "ban", ACTOR));

        assertEquals(R.CODE_NOT_FOUND, ex.getCode());
        assertEquals("用户不存在", ex.getMessage());
        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** action 白名单：null/空串/空白/大写/其它值一律 400，且一次库都不碰 */
    @Test
    void 用户非法action返回400且零库操作() {
        for (String bad : new String[]{null, "", "   ", "BAN", "delete", "ban2"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.updateUserStatus(9L, bad, ACTOR), "action=" + bad + " 必须 400");
            assertBadRequest(ex, ACTION_MSG);
        }
        verifyNoInteractions(userMapper, auditMapper);
    }

    // ================================================================
    // 商家封禁/解封
    // ================================================================

    /** active → suspended：返回「商家已封禁」，写 ban.create / merchant 审计 */
    @Test
    void 商家封禁迁移到停业整顿并写审计() {
        when(merchantMapper.selectById(3L)).thenReturn(merchant(3L, "active"));
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        assertEquals("商家已封禁", service.updateMerchantStatus(3L, "ban", ACTOR));

        AdminAuditLog row = captureSingleAudit();
        assertEquals(ACTOR, row.getActor());
        assertEquals("ban.create", row.getAction());
        assertEquals("merchant", row.getTargetType());
        assertEquals("3", row.getTargetId());
        assertEquals("{\"old\":\"active\",\"new\":\"suspended\"}", row.getDetail());
    }

    /** suspended → active：返回「商家已解封」，写 unban 审计 */
    @Test
    void 商家解封迁移到正常营业并写审计() {
        when(merchantMapper.selectById(3L)).thenReturn(merchant(3L, "suspended"));
        when(merchantMapper.update(isNull(), any())).thenReturn(1);

        assertEquals("商家已解封", service.updateMerchantStatus(3L, "unban", ACTOR));

        AdminAuditLog row = captureSingleAudit();
        assertEquals("unban", row.getAction());
        assertEquals("merchant", row.getTargetType());
        assertEquals("3", row.getTargetId());
        assertEquals("{\"old\":\"suspended\",\"new\":\"active\"}", row.getDetail());
    }

    /** 已 suspended 再 ban：幂等文案，零写零审计 */
    @Test
    void 商家重复封禁幂等且零写零审计() {
        when(merchantMapper.selectById(3L)).thenReturn(merchant(3L, "suspended"));

        assertEquals("商家已处于封禁状态", service.updateMerchantStatus(3L, "ban", ACTOR));

        verify(merchantMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** 已 active 再 unban：幂等文案，零写零审计 */
    @Test
    void 商家重复解封幂等且零写零审计() {
        when(merchantMapper.selectById(3L)).thenReturn(merchant(3L, "active"));

        assertEquals("商家已处于正常营业状态", service.updateMerchantStatus(3L, "unban", ACTOR));

        verify(merchantMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** terminated：ban 与 unban 均 400 固定文案，零写零审计 */
    @Test
    void 已清退商家ban与unban均400且零写零审计() {
        when(merchantMapper.selectById(3L)).thenReturn(merchant(3L, "terminated"));

        for (String action : new String[]{"ban", "unban"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.updateMerchantStatus(3L, action, ACTOR), "terminated + " + action + " 必须 400");
            assertBadRequest(ex, MERCHANT_LOCKED_MSG);
        }

        verify(merchantMapper, never()).update(any(), any());
        verifyNoInteractions(auditMapper);
    }

    /** 商家 action 白名单外一律 400，且一次库都不碰 */
    @Test
    void 商家非法action返回400且零库操作() {
        for (String bad : new String[]{null, "", "SUSPEND", "delete"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.updateMerchantStatus(3L, bad, ACTOR), "action=" + bad + " 必须 400");
            assertBadRequest(ex, ACTION_MSG);
        }
        verifyNoInteractions(merchantMapper, auditMapper);
    }

    // ================================================================
    // 用户列表
    // ================================================================

    /** 五键/行六键/脱敏/filters 回显：归一后的 status 与原文 phone 均逐值回显 */
    @Test
    void 用户列表五键行六键且phone脱敏filters回显() throws Exception {
        when(userMapper.countUsers(7L, PHONE, "banned")).thenReturn(1L);
        when(userMapper.selectUsersPage(7L, PHONE, "banned", 0L, 20)).thenReturn(List.of(userRow(PHONE)));

        Map<String, Object> data = service.users(1, 20, 7L, " " + PHONE + " ", " BANNED ");

        assertEquals(List.of("list", "total", "pageNum", "pageSize", "filters"), new ArrayList<>(data.keySet()),
                "分页五键与键序逐字照 listMerchantApplies");
        assertEquals(1L, data.get("total"));
        assertEquals(1, data.get("pageNum"));
        assertEquals(20, data.get("pageSize"));

        Map<String, Object> line = listOf(data).get(0);
        assertEquals(List.of("id", "phone", "role", "merchantId", "status", "createdAt"),
                new ArrayList<>(line.keySet()), "行六键与键序逐字照契约 §3.4.1");
        assertEquals(MASKED, line.get("phone"));
        assertEquals("banned", line.get("status"));

        Map<String, Object> filters = filtersOf(data);
        assertEquals(List.of("userId", "phone", "status"), new ArrayList<>(filters.keySet()));
        assertEquals(7L, filters.get("userId"));
        assertEquals(MASKED, filters.get("phone"), "filters.phone 以 PhoneMask 后的形态回显（契约 §3.4.1）");
        assertEquals("banned", filters.get("status"), "filters 回显归一小写后的生效值");

        String payload = json.writeValueAsString(data);
        assertFalse(payload.contains(PHONE), "出参序列化后不得出现明文手机号: " + payload);
        assertTrue(payload.contains(MASKED));
    }

    /** 缺省分页 1/20；空表时 COUNT=0 → 不查页；filters 三键均显式 null */
    @Test
    void 用户列表缺省分页与空表短路() {
        when(userMapper.countUsers(null, null, null)).thenReturn(0L);

        Map<String, Object> data = service.users(null, null, null, "   ", "   ");

        assertEquals(1, data.get("pageNum"));
        assertEquals(20, data.get("pageSize"));
        assertEquals(0L, data.get("total"));
        assertTrue(listOf(data).isEmpty());
        verify(userMapper, never()).selectUsersPage(any(), any(), any(), anyLong(), anyInt());

        Map<String, Object> filters = filtersOf(data);
        assertNull(filters.get("userId"));
        assertNull(filters.get("phone"));
        assertNull(filters.get("status"));
    }

    /** 分页边界/主键 0/status 白名单外：一律 400 且 mapper 零调用 */
    @Test
    void 用户列表非法参数一律400且零库操作() {
        assertBadRequest(assertThrows(BizException.class, () -> service.users(0, 20, null, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, 0, null, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, 101, null, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, -5, null, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, 20, 0L, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, 20, -1L, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.users(1, 20, null, null, "deleted")), BAD_REQUEST_MSG);

        verifyNoInteractions(userMapper);
    }

    // ================================================================
    // 商家列表
    // ================================================================

    /** 五键/行六键/contactPhone 脱敏/filters 回显；序列化整包无明文 */
    @Test
    void 商家列表五键行六键且contactPhone脱敏filters回显() throws Exception {
        when(merchantMapper.countMerchants(null, "suspended")).thenReturn(1L);
        when(merchantMapper.selectMerchantsPage(null, "suspended", 0L, 20))
                .thenReturn(List.of(merchantRow(PHONE)));

        Map<String, Object> data = service.merchants(1, 20, null, " Suspended ");

        assertEquals(List.of("list", "total", "pageNum", "pageSize", "filters"), new ArrayList<>(data.keySet()));
        Map<String, Object> line = listOf(data).get(0);
        assertEquals(List.of("id", "name", "contactPhone", "contactName", "status", "createdAt"),
                new ArrayList<>(line.keySet()), "行六键与键序逐字照契约 §3.4.2");
        assertEquals(MASKED, line.get("contactPhone"));
        assertEquals("suspended", line.get("status"));

        Map<String, Object> filters = filtersOf(data);
        assertEquals(List.of("merchantId", "status"), new ArrayList<>(filters.keySet()));
        assertNull(filters.get("merchantId"));
        assertEquals("suspended", filters.get("status"));

        String payload = json.writeValueAsString(data);
        assertFalse(payload.contains(PHONE), "出参序列化后不得出现明文联系电话: " + payload);
        assertTrue(payload.contains(MASKED));
    }

    /** merchantId 精确筛：原值下传 mapper 并在 filters 回显；status 未筛显式 null */
    @Test
    void 商家列表merchantId筛选透传并回显() {
        when(merchantMapper.countMerchants(3L, null)).thenReturn(1L);
        when(merchantMapper.selectMerchantsPage(3L, null, 0L, 20)).thenReturn(List.of(merchantRow(PHONE)));

        Map<String, Object> data = service.merchants(1, 20, 3L, null);

        verify(merchantMapper).countMerchants(3L, null);
        verify(merchantMapper).selectMerchantsPage(3L, null, 0L, 20);
        assertEquals(3L, filtersOf(data).get("merchantId"));
        assertNull(filtersOf(data).get("status"), "未筛选时 filters.status 必须是显式 null");
    }

    /** status 三态白名单均放行，且归一小写后下传 mapper */
    @Test
    void 商家status三态白名单均放行() {
        for (String status : new String[]{"ACTIVE", "SUSPENDED", "TERMINATED"}) {
            Map<String, Object> data = service.merchants(1, 20, null, status);
            assertEquals(0L, data.get("total"));
        }
        verify(merchantMapper).countMerchants(null, "active");
        verify(merchantMapper).countMerchants(null, "suspended");
        verify(merchantMapper).countMerchants(null, "terminated");
        verify(merchantMapper, never()).selectMerchantsPage(any(), any(), anyLong(), anyInt());
    }

    /** 分页边界/主键 0/status 白名单外：一律 400 且 mapper 零调用 */
    @Test
    void 商家列表非法参数一律400且零库操作() {
        assertBadRequest(assertThrows(BizException.class, () -> service.merchants(0, 20, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.merchants(1, 0, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.merchants(1, 101, null, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.merchants(1, 20, 0L, null)), BAD_REQUEST_MSG);
        assertBadRequest(assertThrows(BizException.class, () -> service.merchants(1, 20, null, "banned")), BAD_REQUEST_MSG);

        verifyNoInteractions(merchantMapper);
    }
}
