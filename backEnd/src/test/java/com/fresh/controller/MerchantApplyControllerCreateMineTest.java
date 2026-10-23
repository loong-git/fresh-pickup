package com.fresh.controller;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.MerchantApplyCreateDTO;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.User;
import com.fresh.mapper.MerchantApplyMapper;
import com.fresh.mapper.UserMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-106 片 A 商家入驻申请（提交 / 我的）契约单测：不起 Spring 容器、不落库。
 *
 * <p>按契约 §0.2③「不新建 Service 接口实现类」，两件薄业务落在 MerchantApplyController，
 * 本类直接测控制器（mapper 注入，同 AdminArrivalDepartTest 的 mock 姿势）。
 *
 * <p><b>要证明的事</b>：
 * ① 正常提交：userId 取服务端入参（拦截器注入的 token 属性），status 显式 pending，可选字段 trim / 空白归一 null；
 * ② 已绑定商家 → 400「您已是商家，无需重复申请」，且一次 pending 查询/INSERT 都不发生；
 * ③ 已有 pending → 400「已有待审核的入驻申请，请等待审核结果」，INSERT 不发生；
 * ④ 并发撞 uk_user_pending（DuplicateKeyException）→ 同一句 400，不裸 500；
 * ⑤ mine 只出六键白名单、从未申请时六键显式 null（不 404）。
 *
 * <p><b>本类测不成什么</b>：mapper 是 mock，真实唯一键 1062、DB DEFAULT created_at 与生成列
 * pending_guard 的落值行为不在证明力内（由主会话的真库门禁覆盖）。
 */
class MerchantApplyControllerCreateMineTest {

    private static final Long USER_ID = 7L;
    private static final String PHONE = "13800001234";

    private final MerchantApplyMapper applyMapper = mock(MerchantApplyMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);

    private MerchantApplyController controller;

    @BeforeEach
    void setUp() {
        controller = new MerchantApplyController();
        ReflectionTestUtils.setField(controller, "merchantApplyMapper", applyMapper);
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
    }

    private static User user(Long merchantId) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(PHONE);
        u.setRole("user");
        u.setMerchantId(merchantId);
        return u;
    }

    private static MerchantApplyCreateDTO dto() {
        MerchantApplyCreateDTO dto = new MerchantApplyCreateDTO();
        dto.setMerchantName("  演示商家  ");
        dto.setContactName("  张三  ");
        dto.setBusinessLicense("  /static/images/uploads/license.png  ");
        dto.setLegalPerson("李四");
        dto.setAddress("   ");
        dto.setSettlementAccount("  acct-001  ");
        return dto;
    }

    @Test
    void 正常提交落pending并trim可选字段() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(null));
        when(applyMapper.countPendingByUser(USER_ID)).thenReturn(0L);
        when(applyMapper.insert(any(MerchantApply.class))).thenAnswer(inv -> {
            inv.getArgument(0, MerchantApply.class).setId(11L);   // 模拟 MP 自增 id 回填
            return 1;
        });

        R<Map<String, Object>> result = controller.create(USER_ID, dto());

        assertEquals(200, result.getCode());
        assertEquals(11L, result.getData().get("id"));
        assertEquals("pending", result.getData().get("status"));
        ArgumentCaptor<MerchantApply> cap = ArgumentCaptor.forClass(MerchantApply.class);
        verify(applyMapper).insert(cap.capture());
        MerchantApply saved = cap.getValue();
        assertEquals(USER_ID, saved.getUserId(), "申请人唯一来源是服务端 userId，不收 body 归属");
        assertEquals("演示商家", saved.getMerchantName());
        assertEquals("张三", saved.getContactName());
        assertEquals("/static/images/uploads/license.png", saved.getBusinessLicense());
        assertEquals("李四", saved.getLegalPerson());
        assertNull(saved.getAddress(), "纯空白归一为 NULL，不落空串");
        assertEquals("acct-001", saved.getSettlementAccount());
        assertEquals("pending", saved.getStatus(), "status 显式写，与出参同源");
    }

    @Test
    void 已是商家直接四百且不查pending不插行() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(2L));

        BizException e = assertThrows(BizException.class, () -> controller.create(USER_ID, dto()));

        assertEquals(400, e.getCode());
        assertEquals("您已是商家，无需重复申请", e.getMessage());
        verify(applyMapper, never()).countPendingByUser(any());
        verify(applyMapper, never()).insert(any());
    }

    @Test
    void 已有pending申请直接四百且不插行() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(null));
        when(applyMapper.countPendingByUser(USER_ID)).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> controller.create(USER_ID, dto()));

        assertEquals(400, e.getCode());
        assertEquals("已有待审核的入驻申请，请等待审核结果", e.getMessage());
        verify(applyMapper, never()).insert(any());
    }

    @Test
    void 并发撞唯一键转同一句四百不裸五百() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(null));
        when(applyMapper.countPendingByUser(USER_ID)).thenReturn(0L);
        when(applyMapper.insert(any(MerchantApply.class)))
                .thenThrow(new DuplicateKeyException("uk_user_pending"));

        BizException e = assertThrows(BizException.class, () -> controller.create(USER_ID, dto()));

        assertEquals(400, e.getCode());
        assertEquals("已有待审核的入驻申请，请等待审核结果", e.getMessage());
    }

    @Test
    void 申请人不存在时按未登录四百零一收口() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.create(USER_ID, dto()));

        assertEquals(401, e.getCode());
        verify(applyMapper, never()).insert(any());
    }

    @Test
    void mine只出七键白名单且licenses原样带出() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 11L);
        row.put("merchantName", "演示商家");
        row.put("status", "rejected");
        row.put("auditRemark", "资料不全");
        row.put("createdAt", "2026-10-22 10:00:00");
        row.put("auditedAt", "2026-10-22 11:00:00");
        row.put("licenses", "[{\"type\":\"business_license\",\"url\":\"/static/images/uploads/license.png\"}]");
        when(applyMapper.selectLatestByUser(USER_ID)).thenReturn(row);

        R<Map<String, Object>> result = controller.mine(USER_ID);

        assertEquals(200, result.getCode());
        Map<String, Object> out = result.getData();
        assertEquals(7, out.size(), "mine 出契约点名的七键（F-107 起加 licenses）");
        assertEquals(11L, out.get("id"));
        assertEquals("演示商家", out.get("merchantName"));
        assertEquals("rejected", out.get("status"));
        assertEquals("资料不全", out.get("auditRemark"));
        assertEquals("2026-10-22 10:00:00", out.get("createdAt"));
        assertEquals("2026-10-22 11:00:00", out.get("auditedAt"));
        assertEquals("[{\"type\":\"business_license\",\"url\":\"/static/images/uploads/license.png\"}]",
                out.get("licenses"), "licenses 字符串原样出参，不 parse");
    }

    @Test
    void mine的SQL带出licenses原文且仍不出单图字段() throws Exception {
        Method latest = MerchantApplyMapper.class.getMethod("selectLatestByUser", Long.class);
        String sql = String.join(" ", latest.getAnnotation(Select.class).value());

        assertTrue(sql.contains("licenses AS licenses"), "F-107：mine 必须从库里带出 licenses 原文");
        assertFalse(sql.contains("business_license"), "mine 的出参白名单不含单图字段，维持既有形状");
    }

    @Test
    void 从未申请时mine返回null而不是全空壳对象() {
        when(applyMapper.selectLatestByUser(USER_ID)).thenReturn(null);

        Map<String, Object> out = controller.mine(USER_ID).getData();

        // 契约：data 为 null（不是六键全空的对象——空壳分不清"没申请"与"字段恰好都空"）
        assertNull(out, "从未申请过时 data 必须是 null");
    }
}
