package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Settlement;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.SettlementMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-118 结算跑批单测（不起 Spring 容器、不落库；契约 §4 五组逐条）：
 * ① 正常生成：候选聚合（未设供货价/未支付/shortage 三类排除在 SQL 口径，文本级断言谓词逐字在位）
 *    → gross 断言 + 挂锚断言（attachSettle 的 IN 只含候选行，排除行永不进列表、settle_id 保持 NULL）；
 * ② 幂等/并发：同期重跑 uk 撞 400；DuplicateKeyException 并发兜底转同句 400；
 *    挂锚条件 UPDATE 影响行数不足 → 400 整单回滚（mock 影响行数&lt;候选数场景；
 *    事务回滚本身由 @Transactional 边界承担，mock 层断言「不足即抛」+ 无第二行 settlement，
 *    实库双开互斥由 verify 脚本跑）；
 * ③ 冲销：上一账期止之后发生退款（pay_status 1→2）的已挂锚明细负向计入下一期 refund_amount、
 *    net=gross−refund 正确（冲销窗口左锚=selectMaxPeriodEnd 传参断言）；
 * ④ 空集 400；merchant 404；start≥end 400（含日期形状非法）；
 * ⑤ 对账标志：正常单 true；mock 改一明细金额（聚合 Σ&lt;&gt;账面）→ false；行数变化（order_count 对不上）→ false；
 *    paidChannelAbsent 恒 true。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 uk_merchant_period 互斥、@Transactional 实滚回、
 * 挂锚 UPDATE 在真库的影响行数语义、双轨闸门/限流（Controller 层）——由 verify 脚本与主会话门禁覆盖。
 */
class SettlementGenerateTest {

    private static final Long MERCHANT_ID = 1L;
    private static final String MERCHANT_NAME = "平台自营";
    private static final String START = "2026-10-01";
    private static final String END = "2026-10-15";

    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
    /** F-119 追加依赖（生成账期 ⑦.5 佣金记账步）：本类不测它（PartnerCommissionTest 专测），注空 mock 保既有断言面不变 */
    private final com.fresh.service.PartnerWalletService partnerWalletService =
            mock(com.fresh.service.PartnerWalletService.class);

    private AdminServiceImpl adminService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 SettlementModelTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(adminService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(adminService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
        ReflectionTestUtils.setField(adminService, "partnerWalletService", partnerWalletService);
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant());
    }

    // ---------------------------------------------------------------- fixtures

    private static Merchant merchant() {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setName(MERCHANT_NAME);
        m.setStatus("active");
        return m;
    }

    /** 生成请求体（默认期 2026-10-01~15，merchant 1） */
    private static AdminSettlementGenerateDTO dto(String start, String end) {
        AdminSettlementGenerateDTO dto = new AdminSettlementGenerateDTO();
        dto.setMerchantId(MERCHANT_ID);
        dto.setPeriodStart(start);
        dto.setPeriodEnd(end);
        return dto;
    }

    /** 一行候选明细（selectSettleCandidates 出参行的 Map 形状，列名照 mapper 别名） */
    private static Map<String, Object> candidate(long id, String orderId, BigDecimal supply, int qty) {
        return Map.of("id", id, "orderId", orderId, "dishName", "测试菜",
                "supplyPrice", supply, "quantity", qty, "payStatus", 1, "fulfillStatus", "normal");
    }

    /** 真库 insert 会回填 AUTO id，mock 手工回填（断言挂锚 settleId 与审计 targetId 都跟得上） */
    private void insertBackfillsId(long id) {
        doAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(id);
            return 1;
        }).when(settlementMapper).insert(any(Settlement.class));
    }

    // ================================================================
    // ① 正常生成（契约 §4-1）
    // ================================================================

    @Test
    void 正常生成_gross与挂锚与审计_三明细只一进账() {
        // 候选 1 行（supply 10 × 1）；未设供货价/未支付/shortage 三类被 SQL 口径排除（谓词见下方文本断言）
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID))
                .thenReturn(List.of(candidate(11L, "O1001", new BigDecimal("10.00"), 1)));
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        insertBackfillsId(100L);
        when(orderItemMapper.attachSettle(eq(100L), anyList())).thenReturn(1);

        Map<String, Object> out = adminService.generateSettlement(dto(START, END), "admin_user:7");

        // gross=Σ(supply×qty)=10.00；refund=0（无账期历史）；net=10.00
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) out.get("gross")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("refund")));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) out.get("net")));
        assertEquals(1, out.get("itemCount"));
        assertEquals(1, out.get("orderCount"));
        assertEquals("accounted", out.get("status"));
        assertTrue(String.valueOf(out.get("settleNo")).startsWith("SET")
                        && String.valueOf(out.get("settleNo")).length() <= 32,
                "settle_no=SET+雪花（≤VARCHAR(32)，uk_settle_no 兜底）：" + out.get("settleNo"));

        // 落库行：status=accounted、gmv_amount 即 gross、payable 即 net、retain/coupon/commission/deduction 恒 0
        ArgumentCaptor<Settlement> rowCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(rowCaptor.capture());
        Settlement row = rowCaptor.getValue();
        assertEquals("accounted", row.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(row.getGmvAmount()));
        assertEquals(0, new BigDecimal("10.00").compareTo(row.getPayable()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getRetainAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getCouponDeduct()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getCommission()));
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getDeduction()));

        // 挂锚断言：IN 列表恰为候选行 id 集——被排除三行（未设供价/未支付/shortage）不在列表，
        // settle_id 保持 NULL；SQL 侧 AND settle_id IS NULL 同时保证已挂锚行永不被改锚（文本断言见下）
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderItemMapper).attachSettle(eq(100L), idsCaptor.capture());
        assertEquals(List.of(11L), idsCaptor.getValue());

        // 审计 settlement.create（含 merchant/期/三金额/明细数）
        ArgumentCaptor<AdminAuditLog> auditCaptor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(auditCaptor.capture());
        AdminAuditLog audit = auditCaptor.getValue();
        assertEquals("settlement.create", audit.getAction());
        assertEquals("settlement", audit.getTargetType());
        assertEquals("100", audit.getTargetId());
        assertTrue(audit.getDetail().contains("merchantId=1"));
        assertTrue(audit.getDetail().contains("period=2026-10-01~2026-10-15"));
        assertTrue(audit.getDetail().contains("gross=10.00"));
        assertTrue(audit.getDetail().contains("items=1"));
    }

    @Test
    void 聚合口径谓词逐字在位_排除三类不进候选() throws IOException {
        // 聚合发生在 SQL（mock 测不到谓词），照本仓文本级断言先例核 OrderItemMapper 源码（契约 §1.1 逐字四谓词）
        String sql = mapperSource();
        assertTrue(sql.contains("oi.settle_id IS NULL"), "settle_id 空才进候选");
        assertTrue(sql.contains("oi.supply_price IS NOT NULL"), "未设供货价排除");
        assertTrue(sql.contains("o.pay_status = 1"), "未支付单排除");
        assertTrue(sql.contains("oi.fulfill_status != 'shortage'"), "缺货明细排除");
        // 挂锚 SQL 的防复写保险：已挂锚行（排除行的另一侧语义）永不被第二张账单改锚
        assertTrue(sql.contains("AND settle_id IS NULL"), "挂锚必须带 settle_id IS NULL");
    }

    // ================================================================
    // ② 幂等/并发（契约 §4-2）
    // ================================================================

    @Test
    void 同期重跑_uk预查撞400_不再insert() {
        when(settlementMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(START, END), null));
        assertEquals(400, ex.getCode());
        assertEquals("该商家此账期已生成", ex.getMessage());
        verify(settlementMapper, never()).insert(any(Settlement.class));
        verify(orderItemMapper, never()).attachSettle(any(), anyList());
    }

    @Test
    void 并发双开_uk兜底DuplicateKey转同句400() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID))
                .thenReturn(List.of(candidate(11L, "O1001", new BigDecimal("10.00"), 1)));
        when(settlementMapper.insert(any(Settlement.class)))
                .thenThrow(new DuplicateKeyException("uk_merchant_period"));

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(START, END), null));
        assertEquals(400, ex.getCode());
        assertEquals("该商家此账期已生成", ex.getMessage());
    }

    @Test
    void 挂锚影响行数不足_400整单回滚() {
        // mock 影响行数<候选数场景（并发跑批双开的落败方）：抛 400，@Transactional 边界生产侧整单回滚
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID)).thenReturn(List.of(
                candidate(11L, "O1001", new BigDecimal("10.00"), 1),
                candidate(12L, "O1001", new BigDecimal("10.00"), 1)));
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        insertBackfillsId(100L);
        when(orderItemMapper.attachSettle(eq(100L), anyList())).thenReturn(1);

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(START, END), null));
        assertEquals(400, ex.getCode());
        assertEquals("本期候选明细已被其他账单结算（并发冲突），请刷新后重试", ex.getMessage());
        // mock 层只能断言「不足即抛」；INSERT 的回滚由 @Transactional 承担，实库双开互斥归 verify 脚本
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    // ================================================================
    // ③ 冲销（契约 §4-3）
    // ================================================================

    @Test
    void 冲销_上一期后退款负向计入refund_net正确() {
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID)).thenReturn(List.of(
                candidate(11L, "O1001", new BigDecimal("10.00"), 1),
                candidate(12L, "O1002", new BigDecimal("10.00"), 1)));
        // 上一账期止 2026-10-15 → 冲销窗口左锚；退款（pay_status 1→2）已挂锚明细 Σ=10
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(LocalDate.parse(END));
        when(orderItemMapper.sumRefundAfterAnchor(MERCHANT_ID, LocalDate.parse(END)))
                .thenReturn(new BigDecimal("10.00"));
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        insertBackfillsId(100L);
        when(orderItemMapper.attachSettle(eq(100L), anyList())).thenReturn(2);

        Map<String, Object> out = adminService.generateSettlement(dto("2026-10-16", "2026-10-31"), null);

        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) out.get("gross")));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) out.get("refund")));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) out.get("net")),
                "net=gross−refund−retain=20−10−0");

        ArgumentCaptor<Settlement> rowCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(rowCaptor.capture());
        assertEquals(0, new BigDecimal("10.00").compareTo(rowCaptor.getValue().getRefundAmount()));
        assertEquals(0, new BigDecimal("10.00").compareTo(rowCaptor.getValue().getPayable()));
    }

    @Test
    void 冲销_无账期历史_不查冲销_refund恒0() {
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID))
                .thenReturn(List.of(candidate(11L, "O1001", new BigDecimal("10.00"), 1)));
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        insertBackfillsId(100L);
        when(orderItemMapper.attachSettle(eq(100L), anyList())).thenReturn(1);

        Map<String, Object> out = adminService.generateSettlement(dto(START, END), null);

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("refund")));
        verify(orderItemMapper, never()).sumRefundAfterAnchor(any(), any());
    }

    /**
     * 跨商家冲销锚不串（F-118 端到端二跑裁决补的防回归用例）：商家 A（id=1）已有账期止 2026-10-31 时，
     * 商家 B（id=2）首期生成的冲销窗口必须只认 B 自己的账期历史（无历史 → 锚 null → 不触发冲销查询、
     * refund 恒 0），绝不被 A 的最晚止日顶到未来。隔离的 SQL 级证据 =
     * {@code SettlementMapper#selectMaxPeriodEnd} 的 {@code WHERE merchant_id = #{merchantId}} 谓词
     * （mock 层只能钉「传给冲销查询的锚=本商家锚查询返回值」，谓词在位由文本级断言承担，照上方聚合口径手法）。
     */
    @Test
    void 跨商家冲销锚不串_商家B窗口只认自己的账期历史() throws IOException {
        Merchant merchantB = merchant();
        merchantB.setId(2L);
        merchantB.setName("F-118验收测试商家");
        when(merchantMapper.selectById(2L)).thenReturn(merchantB);
        when(orderItemMapper.selectSettleCandidates(2L))
                .thenReturn(List.of(candidate(21L, "O2001", new BigDecimal("10.00"), 1)));
        when(settlementMapper.selectMaxPeriodEnd(2L)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(200L);
            return 1;
        }).when(settlementMapper).insert(any(Settlement.class));
        when(orderItemMapper.attachSettle(eq(200L), anyList())).thenReturn(1);

        AdminSettlementGenerateDTO dtoB = dto(START, END);
        dtoB.setMerchantId(2L);
        Map<String, Object> out = adminService.generateSettlement(dtoB, null);

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("refund")),
                "商家 B 无账期历史 → refund 恒 0（不被商家 A 的 2026-10-31 锚顶穿）");
        verify(orderItemMapper, never()).sumRefundAfterAnchor(any(), any());
        assertTrue(settlementMapperSource().contains(
                        "SELECT MAX(period_end) FROM settlement WHERE merchant_id = #{merchantId}"),
                "冲销窗口锚聚合必须按 merchant_id 过滤（跨商家不串）：SettlementMapper#selectMaxPeriodEnd");
    }

    // ================================================================
    // ④ 空集 / 404 / start≥end（契约 §4-4）
    // ================================================================

    @Test
    void 空集400_不生成空账单() {
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID)).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(START, END), null));
        assertEquals(400, ex.getCode());
        assertEquals("该商家本期无可结算明细", ex.getMessage());
        verify(settlementMapper, never()).insert(any(Settlement.class));
        verify(orderItemMapper, never()).attachSettle(any(), anyList());
    }

    @Test
    void merchant404() {
        when(merchantMapper.selectById(999L)).thenReturn(null);
        AdminSettlementGenerateDTO missing = dto(START, END);
        missing.setMerchantId(999L);

        BizException ex = assertThrows(BizException.class, () -> adminService.generateSettlement(missing, null));
        assertEquals(404, ex.getCode());
        assertEquals("商家不存在", ex.getMessage());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    void startGeEnd400() {
        BizException equal = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(END, END), null));
        assertEquals(400, equal.getCode());
        assertEquals("账期起止无效（start 须早于 end）", equal.getMessage());

        BizException reversed = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(END, START), null));
        assertEquals(400, reversed.getCode());

        // 形状与真日期闸门（先于查库）
        assertEquals(400, assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto("2026/10/01", END), null)).getCode());
        assertEquals(400, assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto("2026-02-30", END), null)).getCode());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    // ================================================================
    // ⑤ 对账标志（契约 §4-5）
    // ================================================================

    /** 一行已生成账单（gmv=20、order_count=2），挂锚聚合行由测试给（正常 / 被改 / 行数变） */
    private Settlement storedSettlement() {
        Settlement row = new Settlement();
        row.setId(100L);
        row.setSettleNo("SET100");
        row.setMerchantId(MERCHANT_ID);
        row.setPeriodStart(LocalDate.parse(START));
        row.setPeriodEnd(LocalDate.parse(END));
        row.setOrderCount(2);
        row.setGmvAmount(new BigDecimal("20.00"));
        row.setRefundAmount(BigDecimal.ZERO);
        row.setRetainAmount(BigDecimal.ZERO);
        row.setPayable(new BigDecimal("20.00"));
        row.setStatus("accounted");
        return row;
    }

    private List<Map<String, Object>> listOne(Settlement row) {
        when(settlementMapper.selectList(any())).thenReturn(List.of(row));
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(merchant()));
        List<Map<String, Object>> out = adminService.listSettlements(null);
        assertEquals(1, out.size());
        return out;
    }

    @Test
    void 对账正常单_true_paidChannelAbsent恒true() {
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of(
                Map.of("settleId", 100L, "lineCount", 2L, "orderCount", 2L,
                        "gross", new BigDecimal("20.00"), "orphanCount", 0L)));

        Map<String, Object> line = listOne(storedSettlement()).get(0);
        assertEquals(Boolean.TRUE, line.get("reconcileOk"), "gross 与 Σ 一致且计数一致且无孤儿 → true");
        assertEquals(Boolean.TRUE, line.get("paidChannelAbsent"), "记账态明示：打款通道未接恒 true");
        assertEquals(2, line.get("itemCount"), "itemCount 出参=实时挂锚行数");
        assertEquals(MERCHANT_NAME, line.get("merchantName"));
        assertEquals(START, line.get("periodStart"));
        assertEquals("accounted", line.get("status"));
    }

    @Test
    void 人为改一明细金额_对账false() {
        // 测试内 mock：聚合 Σ=15.00 ≠ 账面 20.00（模拟明细金额被改）
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of(
                Map.of("settleId", 100L, "lineCount", 2L, "orderCount", 2L,
                        "gross", new BigDecimal("15.00"), "orphanCount", 0L)));

        assertEquals(Boolean.FALSE, listOne(storedSettlement()).get(0).get("reconcileOk"),
                "金额对不上即数据事故，对账必须 false");
    }

    @Test
    void 挂锚行数变化_order_count对不上_对账false() {
        // 一行明细被删：去重订单数 2→1（金额另一半也可能变，这里单测计数半条件）
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of(
                Map.of("settleId", 100L, "lineCount", 1L, "orderCount", 1L,
                        "gross", new BigDecimal("20.00"), "orphanCount", 0L)));

        assertEquals(Boolean.FALSE, listOne(storedSettlement()).get(0).get("reconcileOk"),
                "计数对不上即数据事故，对账必须 false");
    }

    @Test
    void 孤儿明细_对账false_金额订单数皆对仍打穿() {
        // 2026-10-25 主会话验收裁决第三条件：挂锚明细的 orders 行被删（孤儿）→ orphanCount=1 → false——
        // LEFT JOIN 不丢行（孤儿行仍留在 Σ 与去重数里，金额/订单数两个半条件都可对上），但孤儿态即数据事故
        // （明细弹窗读不到该行的订单支付态，与「对得上」天然矛盾），对账必须 false
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of(
                Map.of("settleId", 100L, "lineCount", 2L, "orderCount", 2L,
                        "gross", new BigDecimal("20.00"), "orphanCount", 1L)));

        assertEquals(Boolean.FALSE, listOne(storedSettlement()).get(0).get("reconcileOk"),
                "孤儿态即数据事故，对账必须 false");
    }

    @Test
    void 聚合行缺失_锚被清_对账false_itemCount兜0() {
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        Map<String, Object> line = listOne(storedSettlement()).get(0);
        assertEquals(Boolean.FALSE, line.get("reconcileOk"));
        assertEquals(0, line.get("itemCount"));
    }

    @Test
    void 明细接口_孤儿行orphanTrue_payStatus空_正常行orphanFalse() {
        // 2026-10-25 主会话验收裁决：明细接口 LEFT JOIN orders——孤儿行保留、payStatus 显 null、
        // orphan 归一 Boolean 下发（SQL 侧是 1/0 整数），页面据此标「订单已不存在」警示；正常行 orphan=false。
        when(settlementMapper.selectById(100L)).thenReturn(storedSettlement());
        Map<String, Object> normal = Map.of("itemId", 11L, "dishName", "测试菜",
                "supplyPrice", new BigDecimal("10.00"), "qty", 1, "subtotal", new BigDecimal("10.00"),
                "orderId", "O1001", "payStatus", 1, "orphan", 0, "fulfillStatus", "normal");
        Map<String, Object> orphanRow = new java.util.HashMap<>();
        orphanRow.put("itemId", 12L);
        orphanRow.put("dishName", "测试菜");
        orphanRow.put("supplyPrice", new BigDecimal("10.00"));
        orphanRow.put("qty", 1);
        orphanRow.put("subtotal", new BigDecimal("10.00"));
        orphanRow.put("orderId", "O1002");
        orphanRow.put("payStatus", null);            // orders 行已删，pay_status 无从取
        orphanRow.put("orphan", 1);                  // SQL 侧 (o.id IS NULL) 的 1/0
        orphanRow.put("fulfillStatus", "normal");
        when(orderItemMapper.selectAnchoredItems(100L)).thenReturn(List.of(normal, orphanRow));

        List<Map<String, Object>> out = adminService.settlementItems(100L);
        assertEquals(2, out.size());
        assertEquals(Boolean.FALSE, out.get(0).get("orphan"), "正常行 orphan=false");
        assertEquals(1, out.get(0).get("payStatus"));
        assertEquals(Boolean.TRUE, out.get(1).get("orphan"), "孤儿行 orphan=true（页面标「订单已不存在」）");
        assertNull(out.get(1).get("payStatus"), "孤儿行 payStatus 显 null（不静默丢行）");
        assertEquals("O1002", out.get(1).get("orderId"), "孤儿行其余快照键照常透传");
    }

    @Test
    void 商家筛_listSettlements按merchantId过滤() {
        Settlement row = storedSettlement();
        when(settlementMapper.selectList(any())).thenReturn(List.of(row));
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(merchant()));
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        // merchantId 非空时传给 mapper 的 wrapper 带等值过滤（mock 层断言调用发生、出参照行渲染）
        List<Map<String, Object>> out = adminService.listSettlements(MERCHANT_ID);
        assertEquals(1, out.size());
        assertEquals(MERCHANT_ID, out.get(0).get("merchantId"));
    }

    // ---------------------------------------------------------------- 文本级断言辅助

    private static Path locateMainJava() {
        String[] candidates = {"src/main/java", "backEnd/src/main/java", "../backEnd/src/main/java"};
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /** 读 OrderItemMapper.java 源码（聚合/挂锚谓词的文本级断言基元，照 SettlementModelTest 的 locateSql 手法） */
    private static String mapperSource() throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path mapper = mainJava.resolve("com/fresh/mapper/OrderItemMapper.java");
        assumeTrue(Files.isRegularFile(mapper), "未找到 OrderItemMapper.java，本条跳过");
        return Files.readString(mapper, StandardCharsets.UTF_8);
    }

    /** 读 SettlementMapper.java 源码（冲销窗口锚按 merchant 过滤的文本级断言基元，手法同 mapperSource） */
    private static String settlementMapperSource() throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path mapper = mainJava.resolve("com/fresh/mapper/SettlementMapper.java");
        assumeTrue(Files.isRegularFile(mapper), "未找到 SettlementMapper.java，本条跳过");
        return Files.readString(mapper, StandardCharsets.UTF_8);
    }
}
