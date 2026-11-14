package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.StoreApplyCreateDTO;
import com.fresh.entity.OpsLog;
import com.fresh.entity.StoreApply;
import com.fresh.mapper.OpsLogMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.service.OpsLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入驻申请提交（{@code StoreApplyServiceImpl#create}）非硬词分支契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>补测动机</b>：P11-2 审计轮 3 P2 缺口 #2——create 的四个此前零直测分支：
 * ① :71-73 contactPhone「填了才校验」的形态判定（{@code @Pattern} 表达不了的空串语义）；
 * ② :84-85 D-21 一用户最多 1 条在审的应用层闸门（{@code countPendingByUser}）；
 * ③ :102-108 BE-3 并发双 pending 撞 uk_user_pending 的 1062→400 转译（应用层判定被并发绕过时的库侧防线）；
 * ④ :113-115 ops_log 审计落点（D-5：申请提交是「申请人现场动作」，写 ops_log 不写 admin_audit_log）。
 * 硬词一票 400 分支已由 {@code P3FFreeTextWordFilterTest} 覆盖，本类不重复；词表照其姿势给
 * 空词表实例（不调 load()，恒放行），使既有断言只钉本类目标分支。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 与 OpsLogWriter 底层的 OpsLogMapper 都是 mock
 * （OpsLogWriter 本身是真实实例，走生产同一条白名单+双趟兜底路径；词表给空表实例恒放行），
 * uk_user_pending 的真实 1062 与 @Transactional 回滚不在证明力内——本类钉住 Service 对
 * DuplicateKeyException 的转译与审计写入意图，真库行为由主会话门禁覆盖。
 */
class StoreApplyCreateTest {

    private static final Long USER_ID = 4L;
    private static final Long APPLY_ID = 9L;

    private final StoreApplyMapper storeApplyMapper = mock(StoreApplyMapper.class);
    private final OpsLogMapper opsLogMapper = mock(OpsLogMapper.class);

    private StoreApplyServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new StoreApplyServiceImpl();
        inject("storeApplyMapper", storeApplyMapper);
        // OpsLogWriter 用真实实例（白名单校验 submit_store_apply 在 ALLOWED_ACTIONS 内才落库），
        // 底层 OpsLogMapper 换 mock，行内容由 captor 断言
        OpsLogWriter opsLogWriter = new OpsLogWriter();
        inject(opsLogWriter, OpsLogWriter.class.getDeclaredField("opsLogMapper"), opsLogMapper);
        inject("opsLogWriter", opsLogWriter);
        // 空词表实例（不调 load()，两表恒 List.of()）= 恒放行：本类不测硬词分支（P3FFreeTextWordFilterTest 负责）
        inject("wordFilter", new com.fresh.common.WordFilter());
    }

    private void inject(String name, Object value) throws Exception {
        Field f = StoreApplyServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void inject(Object target, Field field, Object value) throws Exception {
        field.setAccessible(true);
        field.set(target, value);
    }

    private static StoreApplyCreateDTO dto() {
        StoreApplyCreateDTO dto = new StoreApplyCreateDTO();
        dto.setProposedName("悦邻生鲜店");
        dto.setProposedAddress("佛山市顺德区悦邻路 1 号");
        dto.setLng(new BigDecimal("113.123456"));
        dto.setLat(new BigDecimal("22.654321"));
        return dto;
    }

    private void stubHappyInsert() {
        when(storeApplyMapper.countPendingByUser(USER_ID)).thenReturn(0L);
        when(storeApplyMapper.insert(any(StoreApply.class))).thenAnswer(inv -> {
            inv.getArgument(0, StoreApply.class).setId(APPLY_ID);   // 模拟 MP 自增 id 回填
            return 1;
        });
    }

    // ---------------------------------------------------------------- ① 手机号形态

    @Test
    void 联系电话形态非法判四百且不进在审判定() {
        StoreApplyCreateDTO bad = dto();
        bad.setContactPhone("12345");   // 填了但不是 11 位大陆手机号

        BizException e = assertThrows(BizException.class, () -> service.create(USER_ID, bad));

        assertEquals(400, e.getCode());
        assertEquals("联系电话需为 11 位大陆手机号", e.getMessage());
        verify(storeApplyMapper, never()).countPendingByUser(any());
        verify(storeApplyMapper, never()).insert(any(StoreApply.class));
        verify(opsLogMapper, never()).insert(any(OpsLog.class));
    }

    // ---------------------------------------------------------------- ② D-21 在审重复

    @Test
    void 同用户在审pending重复提交判四百() {
        when(storeApplyMapper.countPendingByUser(USER_ID)).thenReturn(1L);   // D-21 应用层闸门命中

        BizException e = assertThrows(BizException.class, () -> service.create(USER_ID, dto()));

        assertEquals(400, e.getCode());
        assertEquals("已有申请在审核中，请等待结果", e.getMessage());
        verify(storeApplyMapper, never()).insert(any(StoreApply.class));
        verify(opsLogMapper, never()).insert(any(OpsLog.class));
    }

    // ---------------------------------------------------------------- ③ 并发撞 uk_user_pending

    @Test
    void 并发撞uk_user_pending转四百同文案() {
        when(storeApplyMapper.countPendingByUser(USER_ID)).thenReturn(0L);   // 应用层判定被并发绕过
        when(storeApplyMapper.insert(any(StoreApply.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '4' for key 'uk_user_pending'"));

        BizException e = assertThrows(BizException.class, () -> service.create(USER_ID, dto()));

        assertEquals(400, e.getCode());
        assertEquals("已有申请在审核中，请等待结果", e.getMessage(),
                "BE-3：撞 uk_user_pending 转 400，文案与 D-21 应用层判定逐字一致");
        verify(opsLogMapper, never()).insert(any(OpsLog.class));
    }

    // ---------------------------------------------------------------- ④ 成功落库 + ops_log 审计

    @Test
    void 成功提交落库申请行并写ops_log审计() {
        stubHappyInsert();
        StoreApplyCreateDTO in = dto();
        in.setProposedName("  悦邻生鲜店  ");   // 落库前 trim
        in.setProofPhotos(java.util.List.of("/static/images/uploads/a.png", "/static/images/uploads/b.png"));

        Map<String, Object> out = service.create(USER_ID, in);

        assertEquals(APPLY_ID, out.get("id"), "出参 {id,status} 白名单：id=回填后的申请主键");
        assertEquals("pending", out.get("status"), "status 显式写 pending，出参与落库值同源");

        // 申请行：userId 只来自 token 入参；自由文本 trim；status 显式 pending
        ArgumentCaptor<StoreApply> applyCap = ArgumentCaptor.forClass(StoreApply.class);
        verify(storeApplyMapper).insert(applyCap.capture());
        StoreApply row = applyCap.getValue();
        assertEquals(USER_ID, row.getUserId(), "user_id 唯一来源=服务端 token 解析（RF）");
        assertEquals("悦邻生鲜店", row.getProposedName(), "proposed_name 落库前 trim");
        assertEquals("佛山市顺德区悦邻路 1 号", row.getProposedAddress());
        assertEquals(new BigDecimal("113.123456"), row.getLng());
        assertEquals(new BigDecimal("22.654321"), row.getLat());
        assertEquals("pending", row.getStatus());
        assertTrue(row.getContactPhone() == null, "未填联系号归一 NULL 落列（「填了才校验」语义）");

        // 审计落点：ops_log（申请人现场动作，D-5），detail 只放状态与凭证张数，绝不放手机号/地址/照片路径（RD）
        ArgumentCaptor<OpsLog> logCap = ArgumentCaptor.forClass(OpsLog.class);
        verify(opsLogMapper).insert(logCap.capture());
        OpsLog audit = logCap.getValue();
        assertEquals(USER_ID, audit.getOperatorUserId(), "operator_user_id=申请人本人");
        assertEquals("submit_store_apply", audit.getAction());
        assertEquals(String.valueOf(APPLY_ID), audit.getTargetId());
        assertEquals("{\"status\":\"pending\",\"proofCount\":2}", audit.getDetail(),
                "detail 只放 status 与 proofCount（RD：不放手机号/照片路径/地址）");
        // 未填字段归一 null 落列（「填了才校验」语义：contactPhone null 与空串都是未填）
        assertTrue(row.getContactName() == null);
        assertTrue(row.getProofPhotos() != null && row.getProofPhotos().size() == 2,
                "凭证照原样落申请行（数量参与 proofCount 审计）");
    }
}
