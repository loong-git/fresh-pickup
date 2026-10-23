package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.MerchantProfile;
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantApplyMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-106 片 A 商家入驻审核契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① approve 同事务五步顺序钉死：查申请 → INSERT merchant → INSERT merchant_profile(approved)
 *    → 条件 UPDATE user → 置申请态 + 审计；且 {@code contact_phone} 取申请人 {@code user.phone}；
 * ② 条件 UPDATE user 影响行数≠1 → 400 且申请态/审计都不写（TOCTOU 回滚信号）；
 * ③ 已 approved/rejected 再审为幂等成功：不重复 INSERT merchant、不写第二行审计；
 * ④ reject 必填 remark（null/空白 → 400 且不查库）、只置 rejected + 审计，绝不 INSERT merchant/改 user、
 *    也绝不删除申请行；
 * ⑤ 申请不存在 → 404；action 不在白名单 → 400（复用 ALLOWED_AUDIT_ACTIONS）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 都是 mock，所以「事务真的回滚了第②③步」
 * 与「uk_user_pending / uk_user_merchant 的真实 1062」不在证明力内——本类只能钉住 Service 在
 * 影响行数≠1 时抛异常（真事务回滚的前提），真实回滚由主会话真库门禁覆盖。
 */
class AdminMerchantApplyAuditTest {

    private static final Long APPLY_ID = 5L;
    private static final Long APPLICANT_ID = 7L;
    private static final String PHONE = "13800001234";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final MerchantApplyMapper applyMapper = mock(MerchantApplyMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantProfileMapper profileMapper = mock(MerchantProfileMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl service;

    /**
     * {@code LambdaUpdateWrapper<MerchantApply/User>} 的 {@code eq/set} 是即时解析列名的，
     * 纯 Mockito 环境没有容器建 TableInfo 缓存，直接跑会抛 can not find lambda cache
     * （同 MerchantDishSeckillPriceGuardTest 的既有处置）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, MerchantApply.class);
        TableInfoHelper.initTableInfo(assistant, User.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminServiceImpl();
        inject("merchantApplyMapper", applyMapper);
        inject("userMapper", userMapper);
        inject("merchantMapper", merchantMapper);
        inject("merchantProfileMapper", profileMapper);
        inject("adminAuditLogMapper", auditMapper);
        inject("adminKey", "test-admin-key");
    }

    private void inject(String name, Object value) throws Exception {
        Field f = AdminServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static MerchantApply apply(String status) {
        MerchantApply a = new MerchantApply();
        a.setId(APPLY_ID);
        a.setUserId(APPLICANT_ID);
        a.setMerchantName("演示商家");
        a.setContactName("张三");
        a.setBusinessLicense("/static/images/uploads/license.png");
        a.setLegalPerson("李四");
        a.setAddress("演示路 1 号");
        a.setSettlementAccount("acct-001");
        a.setStatus(status);
        return a;
    }

    private static User applicant() {
        User u = new User();
        u.setId(APPLICANT_ID);
        u.setPhone(PHONE);
        u.setRole("user");
        u.setMerchantId(null);
        return u;
    }

    private void stubApproveHappyPath() {
        stubApproveHappyPath(apply("pending"));
    }

    private void stubApproveHappyPath(MerchantApply apply) {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply);
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            inv.getArgument(0, Merchant.class).setId(30L);   // 模拟 MP 自增 id 回填
            return 1;
        });
        when(userMapper.update(any(), any())).thenReturn(1);
        when(applyMapper.update(any(), any())).thenReturn(1);
    }

    private AdminAuditLog captureAudit() {
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ---------------------------------------------------------------- approve

    @Test
    void approve五步顺序且contact_phone取申请人userPhone() {
        stubApproveHappyPath();

        service.auditMerchantApply(APPLY_ID, " approve ", "  资料齐全  ", "admin_user:2");

        ArgumentCaptor<Merchant> merchantCap = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantMapper).insert(merchantCap.capture());
        Merchant merchant = merchantCap.getValue();
        assertEquals("演示商家", merchant.getName());
        assertEquals(PHONE, merchant.getContactPhone(), "contact_phone 必须取申请人 user.phone，不取申请表单");
        assertEquals("张三", merchant.getContactName());
        assertEquals("active", merchant.getStatus());

        ArgumentCaptor<MerchantProfile> profileCap = ArgumentCaptor.forClass(MerchantProfile.class);
        verify(profileMapper).insert(profileCap.capture());
        MerchantProfile profile = profileCap.getValue();
        assertEquals(Long.valueOf(30L), profile.getMerchantId());
        assertEquals("/static/images/uploads/license.png", profile.getBusinessLicense());
        assertEquals("李四", profile.getLegalPerson());
        assertEquals("演示路 1 号", profile.getAddress());
        assertEquals("acct-001", profile.getSettlementAccount());
        assertEquals("approved", profile.getAuditStatus());
        assertNotNull(profile.getAuditedAt());

        // 五步顺序（第②步的 user 查询是取可信手机号的必要动作，排在第①步之后）
        InOrder order = inOrder(applyMapper, userMapper, merchantMapper, profileMapper, auditMapper);
        order.verify(applyMapper).selectById(APPLY_ID);
        order.verify(userMapper).selectById(APPLICANT_ID);
        order.verify(merchantMapper).insert(any(Merchant.class));
        order.verify(profileMapper).insert(any(MerchantProfile.class));
        order.verify(userMapper).update(any(), any());
        order.verify(applyMapper).update(any(), any());
        order.verify(auditMapper).insert(any(AdminAuditLog.class));

        // 三写真的带着目标值：user SET role='merchant', merchant_id=30；申请 SET status='approved'
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<User>> userCap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper).update(any(), userCap.capture());
        Map<String, Object> userParams = userCap.getValue().getParamNameValuePairs();
        assertTrue(userParams.containsValue("merchant"));
        assertTrue(userParams.containsValue(30L));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<MerchantApply>> applyCap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(any(), applyCap.capture());
        assertTrue(applyCap.getValue().getParamNameValuePairs().containsValue("approved"));

        AdminAuditLog row = captureAudit();
        assertEquals("admin_user:2", row.getActor());
        assertEquals("merchant_apply.audit", row.getAction());
        assertEquals("merchant_apply", row.getTargetType());
        assertEquals(String.valueOf(APPLY_ID), row.getTargetId());
        JsonNode detail = readDetail(row);
        assertEquals("status=pending", detail.get("old").asText());
        assertTrue(detail.get("new").asText().contains("status=approved"));
        assertTrue(detail.get("new").asText().contains("merchantId=30"));
        assertTrue(detail.get("new").asText().contains("remark=资料齐全"));
    }

    @Test
    void approve复制licenses且business_license取第一条营业执照url() {
        MerchantApply apply = apply("pending");
        String licenses = "[{\"type\":\"food_business_license\",\"url\":\"/static/images/uploads/food.png\"},"
                + "{\"type\":\"business_license\",\"url\":\"/static/images/uploads/license-new.png\"},"
                + "{\"type\":\"business_license\",\"url\":\"/static/images/uploads/license-2.png\"}]";
        apply.setLicenses(licenses);
        stubApproveHappyPath(apply);

        service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2");

        ArgumentCaptor<MerchantProfile> profileCap = ArgumentCaptor.forClass(MerchantProfile.class);
        verify(profileMapper).insert(profileCap.capture());
        MerchantProfile profile = profileCap.getValue();
        assertEquals(licenses, profile.getLicenses(), "F-107：申请 licenses 必须原样复制进 merchant_profile");
        assertEquals("/static/images/uploads/license-new.png", profile.getBusinessLicense(),
                "单图列取第一条 type=business_license 的 url（跳过 food_*）");
    }

    @Test
    void 老申请licenses为空时profile_licenses为null且单图照旧() {
        stubApproveHappyPath();   // apply() 默认 licenses=null

        service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2");

        ArgumentCaptor<MerchantProfile> profileCap = ArgumentCaptor.forClass(MerchantProfile.class);
        verify(profileMapper).insert(profileCap.capture());
        assertNull(profileCap.getValue().getLicenses(), "申请行没有 licenses → profile 也落 NULL");
        assertEquals("/static/images/uploads/license.png", profileCap.getValue().getBusinessLicense(),
                "licenses 为空时 business_license 回退申请行原值");
    }

    @Test
    void licenses脏串时过审不中断且business_license回退原值() {
        MerchantApply apply = apply("pending");
        apply.setLicenses("not-json");
        stubApproveHappyPath(apply);

        // 不抛异常：复制路径只兜底，不因脏 JSON 阻断过审
        service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2");

        ArgumentCaptor<MerchantProfile> profileCap = ArgumentCaptor.forClass(MerchantProfile.class);
        verify(profileMapper).insert(profileCap.capture());
        assertEquals("not-json", profileCap.getValue().getLicenses(), "脏串也原样复制（留档，不静默清洗）");
        assertEquals("/static/images/uploads/license.png", profileCap.getValue().getBusinessLicense());
    }

    @Test
    void licenses里没有营业执照时business_license回退申请行原值() {
        MerchantApply apply = apply("pending");
        apply.setLicenses("[{\"type\":\"food_business_license\",\"url\":\"/static/images/uploads/food.png\"}]");
        stubApproveHappyPath(apply);

        service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2");

        ArgumentCaptor<MerchantProfile> profileCap = ArgumentCaptor.forClass(MerchantProfile.class);
        verify(profileMapper).insert(profileCap.capture());
        assertEquals("/static/images/uploads/license.png", profileCap.getValue().getBusinessLicense(),
                "数组里解析不出营业执照 → 回退申请行原有的 business_license");
    }

    @Test
    void approve时user条件更新零行则四百且不置申请态不写审计() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            inv.getArgument(0, Merchant.class).setId(30L);
            return 1;
        });
        when(userMapper.update(any(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该用户已绑定商家，请刷新后重试", e.getMessage());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 已approved再审幂等成功不重复发布() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("approved"));

        service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2");

        verify(merchantMapper, never()).insert(any());
        verify(profileMapper, never()).insert(any());
        verify(userMapper, never()).update(any(), any());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 已rejected再审同样幂等成功不翻转结论() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("rejected"));

        service.auditMerchantApply(APPLY_ID, "reject", "再驳一次", "admin_user:2");

        verify(merchantMapper, never()).insert(any());
        verify(userMapper, never()).update(any(), any());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- reject

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reject只置态加审计且不删行不碰商家与user() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(applyMapper.update(any(), any())).thenReturn(1);

        service.auditMerchantApply(APPLY_ID, "reject", "  资料不全  ", null);

        verify(merchantMapper, never()).insert(any());
        verify(profileMapper, never()).insert(any());
        verify(userMapper, never()).update(any(), any());
        verify(applyMapper, never()).deleteById(any(java.io.Serializable.class));

        ArgumentCaptor<LambdaUpdateWrapper<MerchantApply>> cap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applyMapper).update(any(), cap.capture());
        Map<String, Object> params = cap.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("rejected"), "SET status='rejected'");
        assertTrue(params.containsValue("资料不全"), "SET audit_remark=trim(remark)");

        AdminAuditLog row = captureAudit();
        assertEquals("merchant_apply.audit", row.getAction());
        assertEquals("merchant_apply", row.getTargetType());
        JsonNode detail = readDetail(row);
        assertEquals("status=pending", detail.get("old").asText());
        assertEquals("status=rejected,remark=资料不全", detail.get("new").asText());
        // 静态密钥轨 actor 落 admin:<摘要8>，绝不含密钥明文
        assertTrue(row.getActor().startsWith("admin:"));
        assertTrue(!row.getActor().contains("test-admin-key"));
    }

    @Test
    void reject不填remark判四百且一次库都不碰() {
        BizException nullRemark = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "reject", null, "admin_user:2"));
        BizException blankRemark = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "reject", "   ", "admin_user:2"));

        assertEquals(400, nullRemark.getCode());
        assertEquals("驳回时必须填写审核意见", nullRemark.getMessage());
        assertEquals(400, blankRemark.getCode());
        assertEquals("驳回时必须填写审核意见", blankRemark.getMessage());
        verify(applyMapper, never()).selectById(any());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void remark超长判四百且不查库() {
        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "reject", "长".repeat(256), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("审核意见不能超过 255 字", e.getMessage());
        verify(applyMapper, never()).selectById(any());
    }

    // ---------------------------------------------------------------- 入参与并发

    @Test
    void 非法action判四百且不查库() {
        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "delete", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        verify(applyMapper, never()).selectById(any());
        verify(merchantMapper, never()).insert(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 申请不存在判四百零四() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(404, e.getCode());
        assertEquals("申请不存在", e.getMessage());
        verify(merchantMapper, never()).insert(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 置申请态条件更新零行时回滚信号为四百且不写审计() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(applicant());
        when(merchantMapper.insert(any(Merchant.class))).thenAnswer(inv -> {
            inv.getArgument(0, Merchant.class).setId(30L);
            return 1;
        });
        when(userMapper.update(any(), any())).thenReturn(1);
        when(applyMapper.update(any(), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该申请已审核完成，请刷新后重试", e.getMessage());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 申请人被清时拒绝凭空发布商家() {
        when(applyMapper.selectById(APPLY_ID)).thenReturn(apply("pending"));
        when(userMapper.selectById(APPLICANT_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () ->
                service.auditMerchantApply(APPLY_ID, "approve", null, "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("申请人不存在，无法开通商家", e.getMessage());
        verify(merchantMapper, never()).insert(any());
        verify(profileMapper, never()).insert(any());
        verify(applyMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    /** audit detail 是合法 JSON（writeAudit 的 Jackson 组装），解析失败即失败 */
    private JsonNode readDetail(AdminAuditLog row) {
        try {
            return json.readTree(row.getDetail());
        } catch (Exception e) {
            throw new AssertionError("审计 detail 不是合法 JSON: " + row.getDetail(), e);
        }
    }
}
