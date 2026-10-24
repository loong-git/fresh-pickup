package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.AdminCouponTemplateCreateDTO;
import com.fresh.dto.AdminCouponTemplateUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.CouponTemplate;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.CouponTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * F-113 券模板管理契约单测（不起 Spring 容器、不落库，照 AdminSeckillManageTest 的姿势）。
 *
 * <p><b>要证明的事</b>（契约 §4 逐条）：
 * ① 列表十键键序固定、id 升序透传、disabled 原样列出；
 * ② 上新：合法体落库 + 审计 coupon_template.create；key 形状 / key 冲突 / title 超长 /
 *    金额形状 / amount≥threshold（负价单）/ validDays / limitPerUser / totalQuota / status
 *    各分支 400 且不落库不写审计；
 * ③ 更新：七列合并后校验（只改 amount 也拦倒挂）；id 不存在 404；七列全空 400；
 *    coupon_key/issued_count 探测字段不进落库 patch（白名单）；审计 coupon_template.update
 *    before/after 完整快照。
 *
 * <p><b>本类测不成什么</b>：updateById 的 NOT_NULL 策略拼 SQL、真库唯一键并发兜底——
 * 由主会话 mvn 门禁与 §5 verify 脚本覆盖。
 */
class AdminCouponTemplateManageTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";

    private final CouponTemplateMapper couponTemplateMapper = mock(CouponTemplateMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "couponTemplateMapper", couponTemplateMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static CouponTemplate row(long id, String key, String threshold, String amount,
                                      int validDays, int limitPerUser, Integer totalQuota, String status) {
        CouponTemplate row = new CouponTemplate();
        row.setId(id);
        row.setCouponKey(key);
        row.setTitle(null);
        row.setThreshold(new BigDecimal(threshold));
        row.setAmount(new BigDecimal(amount));
        row.setValidDays(validDays);
        row.setLimitPerUser(limitPerUser);
        row.setTotalQuota(totalQuota);
        row.setIssuedCount(3);
        row.setStatus(status);
        return row;
    }

    private static AdminCouponTemplateCreateDTO createDto(String key, String title, String threshold,
                                                          String amount, Integer validDays,
                                                          Integer limitPerUser, Integer totalQuota, String status) {
        AdminCouponTemplateCreateDTO dto = new AdminCouponTemplateCreateDTO();
        dto.setCouponKey(key);
        dto.setTitle(title);
        dto.setThreshold(threshold == null ? null : new BigDecimal(threshold));
        dto.setAmount(amount == null ? null : new BigDecimal(amount));
        dto.setValidDays(validDays);
        dto.setLimitPerUser(limitPerUser);
        dto.setTotalQuota(totalQuota);
        dto.setStatus(status);
        return dto;
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- ① 列表

    @Test
    void 列表出参十键_id升序透传_disabled原样列出() {
        when(couponTemplateMapper.selectList(any())).thenReturn(List.of(
                row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"),
                row(5L, "test_quota", "10.00", "2.00", 3, 0, 2, "disabled")));

        List<Map<String, Object>> out = service.couponTemplates();

        assertEquals(2, out.size());
        assertEquals(List.of("id", "couponKey", "title", "threshold", "amount", "validDays",
                        "limitPerUser", "totalQuota", "issuedCount", "status"),
                List.copyOf(out.get(0).keySet()), "列表键序固定为契约 §4 十键");
        assertEquals(1L, out.get(0).get("id"));
        assertEquals("coupon_1", out.get(0).get("couponKey"));
        assertNull(out.get(0).get("title"), "title NULL 透传");
        assertEquals("disabled", out.get(1).get("status"), "disabled 同样列出（运营可见状态）");
        assertEquals(2, out.get(1).get("totalQuota"));
        assertEquals(3, out.get(1).get("issuedCount"), "issued_count 原样透传（只读）");
    }

    // ---------------------------------------------------------------- ② 上新

    @Test
    void 上新_合法体落库_issuedCount起0_写审计create() {
        when(couponTemplateMapper.selectByCouponKey("new_key")).thenReturn(null);
        when(couponTemplateMapper.insert(any(CouponTemplate.class))).thenReturn(1);

        service.createCouponTemplate(createDto("new_key", "周年庆券", "40.00", "10.00",
                30, 2, 100, "active"), ADMIN_USER_ACTOR);

        ArgumentCaptor<CouponTemplate> captor = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponTemplateMapper).insert(captor.capture());
        CouponTemplate inserted = captor.getValue();
        assertEquals("new_key", inserted.getCouponKey());
        assertEquals("周年庆券", inserted.getTitle());
        assertEquals(new BigDecimal("40.00"), inserted.getThreshold());
        assertEquals(new BigDecimal("10.00"), inserted.getAmount());
        assertEquals(30, inserted.getValidDays());
        assertEquals(2, inserted.getLimitPerUser());
        assertEquals(100, inserted.getTotalQuota());
        assertEquals(0, inserted.getIssuedCount(), "上新 issued_count 恒起 0");
        assertEquals("active", inserted.getStatus());

        AdminAuditLog audit = capturedAudit();
        assertEquals("coupon_template.create", audit.getAction());
        assertEquals(ADMIN_USER_ACTOR, audit.getActor());
        assertEquals("coupon_template", audit.getTargetType());
        assertTrue(audit.getDetail().contains("key=new_key"), "审计 detail 含 key");
    }

    @Test
    void 上新_key形状不合_400_且不查冲突不落库() {
        for (String bad : new String[]{null, "", "Bad-Key", "键", "a", "x".repeat(51)}) {
            BizException e = assertThrows(BizException.class, () -> service.createCouponTemplate(
                    createDto(bad, null, "19.00", "4.00", 7, 1, null, "active"), ADMIN_USER_ACTOR));
            assertEquals(400, e.getCode());
            assertEquals("key 须为 2-50 位小写字母/数字/下划线", e.getMessage(), "bad=" + bad);
        }
        verify(couponTemplateMapper, never()).insert(any(CouponTemplate.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 上新_key冲突_400同一句文案() {
        when(couponTemplateMapper.selectByCouponKey("coupon_1"))
                .thenReturn(row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"));

        BizException e = assertThrows(BizException.class, () -> service.createCouponTemplate(
                createDto("coupon_1", null, "19.00", "4.00", 7, 1, null, "active"), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("key 已存在", e.getMessage(), "契约逐字文案");
        verify(couponTemplateMapper, never()).insert(any(CouponTemplate.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 上新_校验分支逐条400_不落库不写审计() {
        when(couponTemplateMapper.selectByCouponKey(anyString())).thenReturn(null);
        // title 超长 / 金额缺失 / 金额非正 / 三位小数 / amount≥threshold（负价单）/ validDays 越界 /
        // limitPerUser 负 / totalQuota 负 / status 非法
        AdminCouponTemplateCreateDTO[] bads = {
                createDto("k1", "长".repeat(51), "19.00", "4.00", 7, 1, null, "active"),
                createDto("k1", null, null, "4.00", 7, 1, null, "active"),
                createDto("k1", null, "19.00", "0", 7, 1, null, "active"),
                createDto("k1", null, "19.001", "4.00", 7, 1, null, "active"),
                createDto("k1", null, "19.00", "19.00", 7, 1, null, "active"),
                createDto("k1", null, "19.00", "4.00", 0, 1, null, "active"),
                createDto("k1", null, "19.00", "4.00", 366, 1, null, "active"),
                createDto("k1", null, "19.00", "4.00", 7, -1, null, "active"),
                createDto("k1", null, "19.00", "4.00", 7, 1, -1, "active"),
                createDto("k1", null, "19.00", "4.00", 7, 1, null, "frozen")};
        String[] messages = {
                "名称最长 50 个字符",
                "门槛与抵扣须为正数（最多两位小数）",
                "门槛与抵扣须为正数（最多两位小数）",
                "门槛与抵扣须为正数（最多两位小数）",
                "抵扣金额必须低于使用门槛",
                "有效天数须为 1-365",
                "有效天数须为 1-365",
                "每人限领须为大于等于 0 的整数",
                "总量配额须为大于等于 0 的整数（不限则不传）",
                "status 仅允许 active/disabled"};
        for (int i = 0; i < bads.length; i++) {
            final AdminCouponTemplateCreateDTO bad = bads[i];
            BizException e = assertThrows(BizException.class,
                    () -> service.createCouponTemplate(bad, ADMIN_USER_ACTOR), "case=" + i);
            assertEquals(400, e.getCode(), "case=" + i);
            assertEquals(messages[i], e.getMessage(), "case=" + i);
        }
        verify(couponTemplateMapper, never()).insert(any(CouponTemplate.class));
        verifyNoInteractions(auditMapper);
    }

    // ---------------------------------------------------------------- ③ 更新

    @Test
    void 更新_合并后校验_只改amount倒挂也拦() {
        when(couponTemplateMapper.selectById(1L))
                .thenReturn(row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"));
        AdminCouponTemplateUpdateDTO dto = new AdminCouponTemplateUpdateDTO();
        dto.setAmount(new BigDecimal("19.00"));       // 门槛不动（19），amount 提到 19 = 倒挂

        BizException e = assertThrows(BizException.class,
                () -> service.updateCouponTemplate(1L, dto, ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertEquals("抵扣金额必须低于使用门槛", e.getMessage(), "amount<threshold 对合并后的新值判");
        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新_白名单落库_key与issuedCount带了不生效_审计含旧新值() {
        CouponTemplate existing = row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active");
        when(couponTemplateMapper.selectById(1L)).thenReturn(existing);
        AdminCouponTemplateUpdateDTO dto = new AdminCouponTemplateUpdateDTO();
        dto.setCouponKey("hacked_key");               // 探测字段：带了也不落库
        dto.setIssuedCount(999);                      // 探测字段：带了也不落库
        dto.setThreshold(new BigDecimal("25.00"));
        dto.setAmount(new BigDecimal("6.00"));

        service.updateCouponTemplate(1L, dto, ADMIN_USER_ACTOR);

        ArgumentCaptor<CouponTemplate> captor = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponTemplateMapper).updateById(captor.capture());
        CouponTemplate patch = captor.getValue();
        assertEquals(1L, patch.getId());
        assertEquals(new BigDecimal("25.00"), patch.getThreshold());
        assertEquals(new BigDecimal("6.00"), patch.getAmount());
        assertNull(patch.getCouponKey(), "coupon_key 不在落库白名单（null=NOT_NULL 策略不落列）");
        assertNull(patch.getIssuedCount(), "issued_count 不在落库白名单");

        AdminAuditLog audit = capturedAudit();
        assertEquals("coupon_template.update", audit.getAction());
        assertTrue(audit.getDetail().contains("threshold=19.00"), "旧值进审计");
        assertTrue(audit.getDetail().contains("threshold=25.00"), "新值进审计");
    }

    @Test
    void 更新_id不存在404_先于一切body判定() {
        when(couponTemplateMapper.selectById(999999L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.updateCouponTemplate(
                999999L, new AdminCouponTemplateUpdateDTO(), ADMIN_USER_ACTOR));

        assertEquals(404, e.getCode());
        assertEquals("券模板不存在", e.getMessage());
        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
        verifyNoInteractions(auditMapper);
    }

    @Test
    void 更新_七列全空400() {
        when(couponTemplateMapper.selectById(1L))
                .thenReturn(row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"));

        BizException e = assertThrows(BizException.class, () -> service.updateCouponTemplate(
                1L, new AdminCouponTemplateUpdateDTO(), ADMIN_USER_ACTOR));

        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().startsWith("无更新字段"), "空 body（或全 null）必须 400 而非空更新");
        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
    }

    @Test
    void 更新_启停走同一落库路径_审计记status旧新() {
        when(couponTemplateMapper.selectById(1L))
                .thenReturn(row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"));
        AdminCouponTemplateUpdateDTO dto = new AdminCouponTemplateUpdateDTO();
        dto.setStatus("disabled");

        service.updateCouponTemplate(1L, dto, ADMIN_USER_ACTOR);

        ArgumentCaptor<CouponTemplate> captor = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponTemplateMapper).updateById(captor.capture());
        assertEquals("disabled", captor.getValue().getStatus());
        AdminAuditLog audit = capturedAudit();
        assertTrue(audit.getDetail().contains("status=active") && audit.getDetail().contains("status=disabled"),
                "启停审计 before=active after=disabled");
    }

    @Test
    void 更新_status非法400() {
        when(couponTemplateMapper.selectById(1L))
                .thenReturn(row(1L, "coupon_1", "19.00", "4.00", 7, 1, null, "active"));
        AdminCouponTemplateUpdateDTO dto = new AdminCouponTemplateUpdateDTO();
        dto.setStatus("paused");

        BizException e = assertThrows(BizException.class,
                () -> service.updateCouponTemplate(1L, dto, ADMIN_USER_ACTOR));

        assertEquals("status 仅允许 active/disabled", e.getMessage());
        verify(couponTemplateMapper, never()).updateById(any(CouponTemplate.class));
    }
}
