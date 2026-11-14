package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.mapper.PartnerOrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.OpsLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 审计轮 3 P1 缺口 #4：主理人取货码核销终态零覆盖补测（PartnerOrderServiceImpl#verify :216-297，
 * D-6 四条件核销 + D-13 出参纪律 + D-14 防枚举）。
 *
 * <p>照 PartnerCommissionTest / OrderCreateIdempotentOwnerTest 的桩范式：不起容器、不落库、
 * {@code @Autowired} 字段逐个灌 mock。verify 链路全部走手写 SQL（selectForVerify/completeByPickupCode），
 * 不经 MP wrapper，无需灌 TableInfo 缓存。
 *
 * <p>覆盖面（4 例）：
 * ① 正确码核销成功：completeByPickupCode 1 行 → 出参「单号后 6 位 + status=completed +
 *    idempotent=false」（D-13），ops_log 恰写一条 verify_order，detail 含状态迁移且不落取货码；
 * ② 他店码 404：storeId 不在归属集合 → 404「订单不存在」（他店码不得泄漏「码其实是对的」），
 *    条件 UPDATE 与 ops_log 零触达；
 * ③ 未支付单 400：pay_status=0 → 400「请求参数有误，请检查后重试」（与已取消共用同一句，不给
 *    持码者二分信号），data 带 remainingAttempts=5（阈值-当前计数），不写入枚举失败（F-123 收窄）；
 * ④ 重复核销幂等：预读已是 completed → 幂等成功（idempotent=true）且不重复写 ops_log、
 *    不再触发条件 UPDATE（D-6/G4：重复确认不重复写日志）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库四条件 UPDATE 的原子性、READ_COMMITTED 下并发输家的
 * 再读定性（BE-4）——mock 不经过锁与事务管理器，由主会话终验兜底。
 */
class PartnerVerifyTest {

    private static final Long OPERATOR_ID = 9L;
    private static final Long OWN_STORE_ID = 1L;
    private static final List<Long> STORE_IDS = List.of(1L);
    private static final String ORDER_ID = "ORD20261114001";
    private static final String CODE = "123456";

    private final PartnerOrderMapper partnerOrderMapper = mock(PartnerOrderMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final OpsLogWriter opsLogWriter = mock(OpsLogWriter.class);

    private PartnerOrderServiceImpl partnerOrderService;

    @BeforeEach
    void setUp() {
        partnerOrderService = new PartnerOrderServiceImpl();
        ReflectionTestUtils.setField(partnerOrderService, "partnerOrderMapper", partnerOrderMapper);
        ReflectionTestUtils.setField(partnerOrderService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(partnerOrderService, "opsLogWriter", opsLogWriter);
    }

    /** 定性预读行（selectForVerify 出参键：orderId/storeId/status/payStatus） */
    private static Map<String, Object> row(Object storeId, String status, Object payStatus) {
        return Map.of("orderId", ORDER_ID, "storeId", storeId, "status", status, "payStatus", payStatus);
    }

    /** ① 正确码核销成功：status=completed + 单号后 6 位出参 + ops_log 现场动作写入 */
    @Test
    void 正确码核销成功_completed出参_opsLog写入() {
        when(partnerOrderMapper.selectForVerify(CODE)).thenReturn(row(OWN_STORE_ID, "pending_pickup", 1));
        when(partnerOrderMapper.completeByPickupCode(ORDER_ID, CODE, STORE_IDS)).thenReturn(1);

        Map<String, Object> data = partnerOrderService.verify(OPERATOR_ID, STORE_IDS, CODE);

        assertEquals("114001", data.get("orderId"), "出参只给单号后 6 位（D-13：ORD20261114001 → 114001）");
        assertEquals("completed", data.get("status"));
        assertEquals(Boolean.FALSE, data.get("idempotent"), "首次核销非幂等命中");
        verify(partnerOrderMapper, times(1)).completeByPickupCode(ORDER_ID, CODE, STORE_IDS);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(opsLogWriter, times(1)).write(eq(OPERATOR_ID), eq("verify_order"), eq(ORDER_ID), detail.capture());
        assertTrue(detail.getValue().contains("\"from\":\"pending_pickup\"")
                && detail.getValue().contains("\"to\":\"completed\""), "detail 记状态迁移");
        assertTrue(detail.getValue().contains("\"storeId\":1"), "detail 记归属店");
        assertFalse(detail.getValue().contains(CODE), "审计 detail 绝不落取货码（RD）");
    }

    /** ② 他店码 404：storeId 不在名下集合 → 与「码不存在」同码同文案，条件 UPDATE 与 ops_log 零触达 */
    @Test
    void 他店码_404订单不存在_不触发核销不写日志() {
        when(partnerOrderMapper.selectForVerify(CODE)).thenReturn(row(999L, "pending_pickup", 1));

        BizException ex = assertThrows(BizException.class,
                () -> partnerOrderService.verify(OPERATOR_ID, STORE_IDS, CODE));

        assertEquals(404, ex.getCode());
        assertEquals("订单不存在", ex.getMessage(), "他店码不得泄漏「码其实是对的」");
        verify(partnerOrderMapper, never()).completeByPickupCode(any(), any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any());
    }

    /** ③ 未支付单 400：文案与已取消共用一句（不给持码者状态二分信号），data 带 remainingAttempts=5 */
    @Test
    void 未支付单_400参数有误_带剩余次数_不计枚举失败() {
        when(partnerOrderMapper.selectForVerify(CODE)).thenReturn(row(OWN_STORE_ID, "pending_pickup", 0));

        BizException ex = assertThrows(BizException.class,
                () -> partnerOrderService.verify(OPERATOR_ID, STORE_IDS, CODE));

        assertEquals(400, ex.getCode());
        assertEquals("请求参数有误，请检查后重试", ex.getMessage());
        assertTrue(ex.getData() instanceof Map, "400 信封 data 必须带 remainingAttempts（F-123）");
        assertEquals(5, ((Map<?, ?>) ex.getData()).get("remainingAttempts"),
                "无历史失败计数时按满额阈值 5 回");
        verify(partnerOrderMapper, never()).completeByPickupCode(any(), any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any());
    }

    /** ④ 重复核销幂等：预读已 completed → 幂等成功且不重复写 ops_log、不再触发条件 UPDATE */
    @Test
    void 重复核销_幂等成功_不重复写日志不触发核销() {
        when(partnerOrderMapper.selectForVerify(CODE)).thenReturn(row(OWN_STORE_ID, "completed", 1));

        Map<String, Object> data = partnerOrderService.verify(OPERATOR_ID, STORE_IDS, CODE);

        assertEquals(Boolean.TRUE, data.get("idempotent"), "已 completed 返回幂等成功");
        assertEquals("completed", data.get("status"));
        assertEquals("114001", data.get("orderId"));
        verify(partnerOrderMapper, never()).completeByPickupCode(any(), any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any()); // 幂等命中不重复写日志（D-6/G4）
    }
}
