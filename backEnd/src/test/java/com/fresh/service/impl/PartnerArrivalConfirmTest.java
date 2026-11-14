package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.ArrivalItemMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.OpsLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * P11-2 审计轮 3 P1 缺口 #5：主理人到货确认终态零覆盖补测（PartnerStoreServiceImpl#confirmArrival
 * :331-378，READ_COMMITTED 隔离设计 + BE-12 归属判定在 SQL 里）。
 *
 * <p>照 PartnerVerifyTest / PartnerCommissionTest 的桩范式：不起容器、不落库、{@code @Autowired}
 * 字段逐个灌 mock。confirmArrival 链路全部走手写 SQL（selectForConfirm/confirmById），
 * 不经 MP wrapper，无需灌 TableInfo 缓存。
 *
 * <p>覆盖面（4 例，实读裁定）：
 * ① 成功确认置态：预读 planned → confirmById 1 行 → 出参 status=confirmed/confirmed=true/
 *    idempotent=false，confirmTime 取确认后回查值；ops_log 恰写一条 confirm_arrival，
 *    detail 含 from→to 状态迁移；
 * ② 非本店批次 404：selectForConfirm 归属过滤落 null（他店与不存在不可区分）→ 404「批次不存在」，
 *    confirmById 与 ops_log 零触达；
 * ③ 重复确认幂等：预读已 confirmed → 幂等成功（idempotent=true，confirmTime 原值回显），
 *    不再触发条件 UPDATE、不重复写 ops_log（S4b-G6：重复确认不重复写日志）；
 * ④ 短少批次 400：预读 short → 400「该批次已登记短少，不可直接确认」（短少不被确认到货抹平；
 *    短少登记本体在商家侧 fulfill 链，不在本方法内，此处只钉 400 闸门）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库四条件 UPDATE 原子性、并发抢先 0 行后的再读定性（BE-4）、
 * READ_COMMITTED 快照语义——mock 不经过锁与事务管理器，由主会话终验兜底。
 */
class PartnerArrivalConfirmTest {

    private static final Long OPERATOR_ID = 9L;
    private static final Long BATCH_ID = 5L;
    private static final List<Long> STORE_IDS = List.of(1L);
    private static final String CONFIRM_TIME = "2026-11-14 10:00:00";

    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final OpsLogWriter opsLogWriter = mock(OpsLogWriter.class);

    private PartnerStoreServiceImpl partnerStoreService;

    @BeforeEach
    void setUp() {
        partnerStoreService = new PartnerStoreServiceImpl();
        ReflectionTestUtils.setField(partnerStoreService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(partnerStoreService, "arrivalBatchMapper", arrivalBatchMapper);
        ReflectionTestUtils.setField(partnerStoreService, "arrivalItemMapper", arrivalItemMapper);
        ReflectionTestUtils.setField(partnerStoreService, "opsLogWriter", opsLogWriter);
    }

    /** 定性预读行（selectForConfirm 出参键：id/storeId/status/confirmTime；HashMap 为容 null 时间） */
    private static Map<String, Object> row(String status, Object confirmTime) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", BATCH_ID);
        row.put("storeId", 1L);
        row.put("status", status);
        row.put("confirmTime", confirmTime);
        return row;
    }

    /** ① 成功确认置态：planned → confirmed，出参取确认后回查值，ops_log 写状态迁移 */
    @Test
    void 成功确认_置confirmed_出参回查值_opsLog写入() {
        when(arrivalBatchMapper.selectForConfirm(BATCH_ID, STORE_IDS))
                .thenReturn(row("planned", null),                          // 定性预读
                        row("confirmed", CONFIRM_TIME));                   // 确认后回查
        when(arrivalBatchMapper.confirmById(BATCH_ID, STORE_IDS)).thenReturn(1);

        Map<String, Object> data = partnerStoreService.confirmArrival(OPERATOR_ID, STORE_IDS, BATCH_ID);

        assertEquals("confirmed", data.get("status"));
        assertEquals(Boolean.TRUE, data.get("confirmed"));
        assertEquals(Boolean.FALSE, data.get("idempotent"), "首次确认非幂等命中");
        assertEquals(CONFIRM_TIME, data.get("confirmTime"), "confirmTime 必须是确认后回查的落库权威值");
        verify(arrivalBatchMapper, times(1)).confirmById(BATCH_ID, STORE_IDS); // 四条件 UPDATE 恰一次
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(opsLogWriter, times(1)).write(eq(OPERATOR_ID), eq("confirm_arrival"), eq("5"), detail.capture());
        assertTrue(detail.getValue().contains("\"from\":\"planned\"")
                && detail.getValue().contains("\"to\":\"confirmed\""), "detail 记状态迁移");
    }

    /** ② 非本店批次 404：归属过滤落 null（他店与不存在不可区分），confirmById 与 ops_log 零触达 */
    @Test
    void 非本店批次_404批次不存在_不触发确认不写日志() {
        when(arrivalBatchMapper.selectForConfirm(BATCH_ID, STORE_IDS)).thenReturn(null);
        Map<String, Object> foreign = row("planned", null);
        foreign.put("storeId", 999L); // 纵深分支用：预读回了行但 store_id 不在归属集合
        when(arrivalBatchMapper.selectForConfirm(6L, STORE_IDS)).thenReturn(foreign);

        BizException ex1 = assertThrows(BizException.class,
                () -> partnerStoreService.confirmArrival(OPERATOR_ID, STORE_IDS, BATCH_ID));
        assertEquals(404, ex1.getCode());
        assertEquals("批次不存在", ex1.getMessage(), "他店批次与不存在同码同文案，不泄漏行存在性");

        BizException ex2 = assertThrows(BizException.class,
                () -> partnerStoreService.confirmArrival(OPERATOR_ID, STORE_IDS, 6L));
        assertEquals(404, ex2.getCode());
        assertEquals("批次不存在", ex2.getMessage(), "store_id 越集合的纵深分支同样 404");

        verify(arrivalBatchMapper, never()).confirmById(any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any());
    }

    /** ③ 重复确认幂等：预读已 confirmed → 幂等成功、不重复写 ops_log、不再触发条件 UPDATE */
    @Test
    void 重复确认_幂等成功_不重复写日志() {
        when(arrivalBatchMapper.selectForConfirm(BATCH_ID, STORE_IDS))
                .thenReturn(row("confirmed", CONFIRM_TIME));

        Map<String, Object> data = partnerStoreService.confirmArrival(OPERATOR_ID, STORE_IDS, BATCH_ID);

        assertEquals(Boolean.TRUE, data.get("idempotent"), "已 confirmed 返回幂等成功");
        assertEquals("confirmed", data.get("status"));
        assertEquals(CONFIRM_TIME, data.get("confirmTime"), "幂等命中回显已确认时间");
        verify(arrivalBatchMapper, never()).confirmById(any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any()); // 重复确认不重复写日志（S4b-G6）
    }

    /** ④ 短少批次 400：short 不可被「确认到货」抹平（短少登记本体在商家侧链路，此处只钉 400 闸门） */
    @Test
    void 短少批次_400不可直接确认_不触发确认() {
        when(arrivalBatchMapper.selectForConfirm(BATCH_ID, STORE_IDS)).thenReturn(row("short", null));

        BizException ex = assertThrows(BizException.class,
                () -> partnerStoreService.confirmArrival(OPERATOR_ID, STORE_IDS, BATCH_ID));

        assertEquals(400, ex.getCode());
        assertEquals("该批次已登记短少，不可直接确认", ex.getMessage());
        verify(arrivalBatchMapper, never()).confirmById(any(), any());
        verify(opsLogWriter, never()).write(any(), any(), any(), any());
    }
}
