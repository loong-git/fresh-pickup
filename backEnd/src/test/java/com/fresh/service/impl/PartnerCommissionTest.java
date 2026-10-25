package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.PartnerWallet;
import com.fresh.entity.PartnerWalletFlow;
import com.fresh.entity.Settlement;
import com.fresh.entity.Store;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.PartnerWalletFlowMapper;
import com.fresh.mapper.PartnerWalletMapper;
import com.fresh.mapper.SettlementMapper;
import com.fresh.mapper.StoreMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-119 主理人佣金记账单测（不起 Spring 容器、不落库；契约 §5 四组逐条）：
 * ① 记账：两单（各有主理人店）→ 钱包余额=Σ(total_price×3%)（HALF_UP 两位）、grant 流水各一、
 *    rate_snapshot=0.0300、balance_after 快照、settlement.commission 回填净值；
 *    平台直营店订单（owner 空/无店）不产生流水；0 元单不占 uk 位。
 * ② 冲回：冲销窗口内退款订单 → reversal 负向同额流水、balance 原子减（减至负也如实记）、
 *    uk 防重（已冲回预查跳过 / 并发撞 uk 回退余额跳过）；未记佣金的单不冲回。
 * ③ 幂等：跑批重入（同账单 uk_merchant_period 400 前置即拦，佣金步零触达）；
 *    流水层 uk 再兜底（已记佣金 grant 预查跳过 / 撞 uk 回退跳过）。
 * ④ 原子：流水插入失败（mock 非 uk 异常）→ 异常上抛（不吞不补偿）；
 *    佣金步失败 → generateSettlement 异常上抛、审计零写入（真库整单回滚由 @Transactional 边界与
 *    verify 脚本承担——mock 层只断言「失败即上抛」+ 调用序在挂锚之后，不留假收据）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 uk_order_biz / uk_user_wallet 互斥、@Transactional 实滚回、
 * upsertAdjust 的 ON DUPLICATE KEY UPDATE 语义、限流/鉴权（Controller 层）——由 verify 脚本与主会话门禁覆盖。
 */
class PartnerCommissionTest {

    private static final Long MERCHANT_ID = 1L;
    private static final Long SETTLE_ID = 100L;
    private static final Long OWNER_A = 7L;
    private static final Long OWNER_B = 8L;
    private static final String ORDER_A = "O1001";
    private static final String ORDER_B = "O1002";
    private static final LocalDate P1_END = LocalDate.parse("2026-10-15");

    private final PartnerWalletMapper walletMapper = mock(PartnerWalletMapper.class);
    private final PartnerWalletFlowMapper flowMapper = mock(PartnerWalletFlowMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final com.fresh.mapper.UserMapper userMapper = mock(com.fresh.mapper.UserMapper.class);

    /** 生成账期接线测试用（佣金逻辑本体走 PartnerWalletServiceImpl 真实现，非 mock） */
    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    private PartnerWalletServiceImpl walletService;
    private AdminServiceImpl adminService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，照 SettlementGenerateTest 同款手法手工灌内存缓存。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, Store.class);
        TableInfoHelper.initTableInfo(assistant, PartnerWallet.class);
        TableInfoHelper.initTableInfo(assistant, PartnerWalletFlow.class);
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        walletService = new PartnerWalletServiceImpl();
        ReflectionTestUtils.setField(walletService, "partnerWalletMapper", walletMapper);
        ReflectionTestUtils.setField(walletService, "partnerWalletFlowMapper", flowMapper);
        ReflectionTestUtils.setField(walletService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(walletService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(walletService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(walletService, "userMapper", userMapper);

        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(adminService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(adminService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
        ReflectionTestUtils.setField(adminService, "partnerWalletService", walletService);
    }

    // ---------------------------------------------------------------- fixtures

    private static Order order(String id, BigDecimal totalPrice, Long storeId) {
        Order o = new Order();
        o.setId(id);
        o.setTotalPrice(totalPrice);
        o.setStoreId(storeId);
        return o;
    }

    private static Store store(Long id, Long ownerUserId) {
        Store s = new Store();
        s.setId(id);
        s.setName("测试店" + id);
        s.setOwnerUserId(ownerUserId);
        return s;
    }

    private static PartnerWallet wallet(Long userId, BigDecimal balance) {
        PartnerWallet w = new PartnerWallet();
        w.setId(userId * 10);
        w.setUserId(userId);
        w.setBalance(balance);
        return w;
    }

    private static PartnerWalletFlow grantFlow(String orderId, Long userId, BigDecimal amount) {
        PartnerWalletFlow f = new PartnerWalletFlow();
        f.setId(900L);
        f.setUserId(userId);
        f.setBizType(PartnerWalletFlow.BIZ_GRANT);
        f.setOrderId(orderId);
        f.setSettlementId(99L);
        f.setAmount(amount);
        f.setBalanceAfter(amount);
        f.setRateSnapshot(PartnerWalletFlow.RATE_SNAPSHOT);
        return f;
    }

    /** 默认记账场景的两单去重订单集（入参序=候选序，流水序断言依赖它） */
    private static Set<String> twoOrderIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(ORDER_A);
        ids.add(ORDER_B);
        return ids;
    }

    /** 默认记账场景的批量读回桩：两单各有主理人店（A→owner 7 / B→owner 8） */
    private void stubTwoOwnedOrders() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(ORDER_A, new BigDecimal("100.00"), 1L),
                order(ORDER_B, new BigDecimal("50.00"), 2L)));
        when(storeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                store(1L, OWNER_A), store(2L, OWNER_B)));
        when(flowMapper.selectCount(any())).thenReturn(0L);
    }

    /** 余额回读桩：按调用序给 owner 7 → 3.00、owner 8 → 4.50（快照随单滚动） */
    private void stubBalanceReadback() {
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(OWNER_A, new BigDecimal("3.00")),
                        wallet(OWNER_B, new BigDecimal("4.50")));
    }

    /** 接线测试的生成账期前置：候选两单 + 挂锚成功 + 首期（无冲销历史） */
    private void stubGenerateHappyPath() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant());
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID)).thenReturn(List.of(
                Map.of("id", 11L, "orderId", ORDER_A, "dishName", "甲菜", "supplyPrice", new BigDecimal("10.00"),
                        "quantity", 1, "payStatus", 1, "fulfillStatus", "normal"),
                Map.of("id", 12L, "orderId", ORDER_B, "dishName", "乙菜", "supplyPrice", new BigDecimal("10.00"),
                        "quantity", 1, "payStatus", 1, "fulfillStatus", "normal")));
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(SETTLE_ID);
            return 1;
        }).when(settlementMapper).insert(any(Settlement.class));
        when(orderItemMapper.attachSettle(eq(SETTLE_ID), anyList())).thenReturn(2);
    }

    // ================================================================
    // ① 记账（契约 §5-1）
    // ================================================================

    @Test
    void 记账_两单各有主理人店_grant各一_余额快照与净值正确() {
        stubTwoOwnedOrders();
        stubBalanceReadback();

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID, twoOrderIds(), P1_END);

        // 佣金 = total_price×3% HALF_UP 两位：100.00→3.00、50.00→1.50，净值 4.50（settlement.commission 回填值）
        assertEquals(0, new BigDecimal("4.50").compareTo(net));

        // 钱包 upsert 原子加两次（正入）
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("3.00"));
        verify(walletMapper).upsertAdjust(OWNER_B, new BigDecimal("1.50"));

        // grant 流水各一：bizType/orderId/金额正/balance_after 快照/费率快照 0.0300/settlement_id 锚
        ArgumentCaptor<PartnerWalletFlow> flowCaptor = ArgumentCaptor.forClass(PartnerWalletFlow.class);
        verify(flowMapper, times(2)).insert(flowCaptor.capture());
        List<PartnerWalletFlow> flows = flowCaptor.getAllValues();
        assertEquals(PartnerWalletFlow.BIZ_GRANT, flows.get(0).getBizType());
        assertEquals(ORDER_A, flows.get(0).getOrderId());
        assertEquals(OWNER_A, flows.get(0).getUserId());
        assertEquals(0, new BigDecimal("3.00").compareTo(flows.get(0).getAmount()));
        assertEquals(0, new BigDecimal("3.00").compareTo(flows.get(0).getBalanceAfter()), "余额快照=变动后真值（回读）");
        assertEquals(0, new BigDecimal("0.0300").compareTo(flows.get(0).getRateSnapshot()), "费率快照 0.0300");
        assertEquals(SETTLE_ID, flows.get(0).getSettlementId(), "流水锚定产生它的账单");
        assertEquals(PartnerWalletFlow.BIZ_GRANT, flows.get(1).getBizType());
        assertEquals(ORDER_B, flows.get(1).getOrderId());
        assertEquals(0, new BigDecimal("1.50").compareTo(flows.get(1).getAmount()));
        assertEquals(0, new BigDecimal("4.50").compareTo(flows.get(1).getBalanceAfter()), "第二笔快照含第一笔累加");
    }

    @Test
    void 记账_平台直营店与无店单不产生流水_净值零() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("O2001", new BigDecimal("100.00"), 3L),   // owner 空=平台直营
                order("O2002", new BigDecimal("100.00"), null), // 无店单无从归因
                order("O2003", new BigDecimal("100.00"), 4L))); // store 行缺失同直营口径
        when(storeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(store(3L, null)));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID, twoOrderIds(), P1_END);

        assertEquals(0, BigDecimal.ZERO.compareTo(net));
        verify(walletMapper, never()).upsertAdjust(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 记账_零元单不占uk位_佣金HALF_UP两位() {
        // 0 元单（免费领）：无佣金可记、不落流水；19.99×3%=0.5997 → HALF_UP 两位=0.60
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order("O3001", BigDecimal.ZERO, 1L),
                order("O3002", new BigDecimal("19.99"), 1L)));
        when(storeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(store(1L, OWNER_A)));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(OWNER_A, new BigDecimal("0.60")));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(List.of("O3001", "O3002")), P1_END);

        assertEquals(0, new BigDecimal("0.60").compareTo(net));
        verify(walletMapper, times(1)).upsertAdjust(eq(OWNER_A), any());
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("0.60"));
        verify(flowMapper, times(1)).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 记账_settlement_commission回填净值_接线断言() {
        stubTwoOwnedOrders();
        stubBalanceReadback();
        stubGenerateHappyPath();

        adminService.generateSettlement(dto("2026-10-01", "2026-10-15"), null);

        // commission 回填 = recordForSettlement 返回的净值 4.50（从 LambdaUpdateWrapper 参数对里取证）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Settlement>> wrapperCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(settlementMapper).update(any(), wrapperCaptor.capture());
        @SuppressWarnings({"unchecked", "rawtypes"})
        AbstractWrapper<Settlement, ?, ?> wrapper = (AbstractWrapper) wrapperCaptor.getValue();
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                        .anyMatch(v -> v instanceof BigDecimal b && new BigDecimal("4.50").compareTo(b) == 0),
                "settlement.commission 事务内回填=佣金净值 4.50");
    }

    // ================================================================
    // ② 冲回（契约 §5-2）
    // ================================================================

    @Test
    void 冲回_退款单负向同额_余额减_冲销窗口与F118同源() {
        // 窗口内退款单 ORDER_A 此前已记佣金 3.00（owner 7）→ reversal -3.00、余额减至 0（grant 段候选空集）
        when(orderItemMapper.selectRefundedAnchoredOrderIds(MERCHANT_ID, P1_END))
                .thenReturn(List.of(ORDER_A));
        when(flowMapper.selectOne(any())).thenReturn(grantFlow(ORDER_A, OWNER_A, new BigDecimal("3.00")));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(OWNER_A, new BigDecimal("0.00")));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(), P1_END);

        assertEquals(0, new BigDecimal("-3.00").compareTo(net), "净值=Σ(本账单流水)：负向冲回 -3.00");
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("-3.00"));
        ArgumentCaptor<PartnerWalletFlow> flowCaptor = ArgumentCaptor.forClass(PartnerWalletFlow.class);
        verify(flowMapper).insert(flowCaptor.capture());
        PartnerWalletFlow reversal = flowCaptor.getValue();
        assertEquals(PartnerWalletFlow.BIZ_REVERSAL, reversal.getBizType());
        assertEquals(ORDER_A, reversal.getOrderId());
        assertEquals(OWNER_A, reversal.getUserId(), "冲回减回当时收款的那只钱包（按 grant 流水定位）");
        assertEquals(0, new BigDecimal("-3.00").compareTo(reversal.getAmount()), "负向同额");
        assertEquals(0, new BigDecimal("0.00").compareTo(reversal.getBalanceAfter()), "余额减后快照");
        assertEquals(SETTLE_ID, reversal.getSettlementId(), "冲回流水的产生账单=当期");
        assertEquals(0, new BigDecimal("0.0300").compareTo(reversal.getRateSnapshot()));
    }

    @Test
    void 冲回_余额不足减至负_如实记账() {
        when(orderItemMapper.selectRefundedAnchoredOrderIds(MERCHANT_ID, P1_END))
                .thenReturn(List.of(ORDER_A));
        when(flowMapper.selectOne(any())).thenReturn(grantFlow(ORDER_A, OWNER_A, new BigDecimal("3.00")));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(OWNER_A, new BigDecimal("-1.00")));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(), P1_END);

        assertEquals(0, new BigDecimal("-3.00").compareTo(net));
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("-3.00"));
        ArgumentCaptor<PartnerWalletFlow> flowCaptor = ArgumentCaptor.forClass(PartnerWalletFlow.class);
        verify(flowMapper).insert(flowCaptor.capture());
        assertEquals(0, new BigDecimal("-1.00").compareTo(flowCaptor.getValue().getBalanceAfter()),
                "余额不足减至负也如实快照（契约 §2-2：佣金已付情形轨道 B 处理，本期如实记账）");
    }

    @Test
    void 冲回_未记佣金或已冲回_均跳过() {
        // 无 grant 流水（直营店/0 元单口径）→ 不冲回；已冲回（uk 预查命中）→ 不重复冲回
        when(orderItemMapper.selectRefundedAnchoredOrderIds(MERCHANT_ID, P1_END))
                .thenReturn(List.of("O9001", ORDER_B));
        when(flowMapper.selectOne(any())).thenReturn(null);
        when(flowMapper.selectCount(any())).thenReturn(1L);

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(), P1_END);

        assertEquals(0, BigDecimal.ZERO.compareTo(net));
        verify(walletMapper, never()).upsertAdjust(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 冲回_并发撞uk_回退余额后跳过_不上抛() {
        when(orderItemMapper.selectRefundedAnchoredOrderIds(MERCHANT_ID, P1_END))
                .thenReturn(List.of(ORDER_A));
        when(flowMapper.selectOne(any())).thenReturn(grantFlow(ORDER_A, OWNER_A, new BigDecimal("3.00")));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(OWNER_A, new BigDecimal("0.00")));
        when(flowMapper.insert(any(PartnerWalletFlow.class)))
                .thenThrow(new DuplicateKeyException("uk_order_biz"));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(), P1_END);

        // 幂等语义不是错误：净值不含本笔，钱包扣减已回退（+3.00），不上抛
        assertEquals(0, BigDecimal.ZERO.compareTo(net));
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("-3.00"));
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("3.00"));
    }

    // ================================================================
    // ③ 幂等（契约 §5-3）
    // ================================================================

    @Test
    void 跑批重入_同账单uk前置400_佣金步零触达() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant());
        when(settlementMapper.selectCount(any())).thenReturn(1L);   // uk_merchant_period 预查命中

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto("2026-10-01", "2026-10-15"), null));
        assertEquals(400, ex.getCode());
        assertEquals("该商家此账期已生成", ex.getMessage());
        verify(settlementMapper, never()).insert(any(Settlement.class));
        // 佣金记账只发生在挂锚成功之后——前置即拦时挂锚与佣金步都零触达
        verify(orderItemMapper, never()).attachSettle(any(), anyList());
        verify(walletMapper, never()).upsertAdjust(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 流水层uk兜底_已记佣金grant预查跳过_并发撞uk回退() {
        // 预查命中：跨商家同单/跑批重入已记 grant → 跳过，钱包零触碰
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(ORDER_A, new BigDecimal("100.00"), 1L)));
        when(storeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(store(1L, OWNER_A)));
        when(flowMapper.selectCount(any())).thenReturn(1L);

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(List.of(ORDER_A)), P1_END);
        assertEquals(0, BigDecimal.ZERO.compareTo(net));
        verify(walletMapper, never()).upsertAdjust(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 流水层uk兜底_grant并发撞uk_回退余额后跳过() {
        // 预查未中但 INSERT 撞 uk（并发双开兜底）：回退余额后跳过，净值不含本笔
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(ORDER_A, new BigDecimal("100.00"), 1L)));
        when(storeMapper.selectBatchIds(anyCollection())).thenReturn(List.of(store(1L, OWNER_A)));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        when(walletMapper.selectOne(any())).thenReturn(wallet(OWNER_A, new BigDecimal("3.00")));
        when(flowMapper.insert(any(PartnerWalletFlow.class)))
                .thenThrow(new DuplicateKeyException("uk_order_biz"));

        BigDecimal net = walletService.recordForSettlement(MERCHANT_ID, SETTLE_ID,
                new LinkedHashSet<>(List.of(ORDER_A)), P1_END);
        assertEquals(0, BigDecimal.ZERO.compareTo(net));
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("3.00"));
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("-3.00"));
    }

    // ================================================================
    // ④ 原子（契约 §5-4）
    // ================================================================

    @Test
    void 原子_流水插入失败_异常上抛不吞不补偿() {
        // 非 uk 失败（库不可用等真故障）：绝不补偿、绝不吞——上抛随账单事务整体回滚
        stubTwoOwnedOrders();
        stubBalanceReadback();
        when(flowMapper.insert(any(PartnerWalletFlow.class)))
                .thenThrow(new RuntimeException("模拟流水落库失败"));

        assertThrows(RuntimeException.class, () -> walletService.recordForSettlement(
                MERCHANT_ID, SETTLE_ID, twoOrderIds(), P1_END));
        // 余额加过但流水没落上：不做任何补偿（补偿=伪造成功），回滚交给事务边界
        verify(walletMapper).upsertAdjust(OWNER_A, new BigDecimal("3.00"));
    }

    @Test
    void 原子_佣金步失败_账单整体回滚_挂锚不留半态() {
        stubTwoOwnedOrders();
        stubBalanceReadback();
        when(flowMapper.insert(any(PartnerWalletFlow.class)))
                .thenThrow(new RuntimeException("模拟流水落库失败"));
        stubGenerateHappyPath();

        // 佣金失败异常穿透 generateSettlement（@Transactional 生产侧整单回滚：账单+挂锚+佣金同生同灭）
        assertThrows(RuntimeException.class,
                () -> adminService.generateSettlement(dto("2026-10-01", "2026-10-15"), null));
        // 回滚不留半态的 mock 侧证据：审计从未写出（账单作废即无 settlement.create 行）
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    // ================================================================
    // 结构性/文本级断言（照 SettlementGenerateTest#mapperSource 手法）
    // ================================================================

    @Test
    void 结构性断言_佣金步在挂锚成功后审计前_冲销窗口SQL与F118同源() throws IOException {
        // ① AdminServiceImpl 调用序 挂锚(attachSettle) → 佣金(recordForSettlement) → 审计(writeAudit)——
        //    「只追加步骤、既有事务结构一行不改」的结构证据；
        // ② settlement.commission 单列回填在位；③ 冲销窗口谓词与 sumRefundAfterAnchor 同源同窗。
        String adminSource = mainJavaSource("com/fresh/service/impl/AdminServiceImpl.java");
        int anchor = adminSource.indexOf("orderItemMapper.attachSettle");
        int commission = adminSource.indexOf("partnerWalletService.recordForSettlement");
        // writeAudit(auditActor(actor) 在本类多个既有写方法里出现（F-118 段是文件末尾最后一个），
        // 取 lastIndexOf 才锚到 generateSettlement 的那一处
        int audit = adminSource.lastIndexOf("writeAudit(auditActor(actor)");
        assertTrue(anchor >= 0 && commission >= 0 && audit >= 0, "三个调用点都应在位");
        assertTrue(anchor < commission && commission < audit,
                "佣金记账必须追加在挂锚成功之后、审计之前（契约 §2「挂锚成功后同事务追加」）");
        assertTrue(adminSource.contains("Settlement::getCommission, commissionNet"),
                "settlement.commission 净值回填在位");

        String itemMapper = mainJavaSource("com/fresh/mapper/OrderItemMapper.java");
        int sumRefund = itemMapper.indexOf("BigDecimal sumRefundAfterAnchor");
        int refunded = itemMapper.indexOf("List<String> selectRefundedAnchoredOrderIds");
        assertTrue(sumRefund > 0 && refunded > sumRefund, "F-119 追加查询在 F-118 组之后（追加式纪律）");
        // 切片从该查询的 SQL 文本起（签名行之前是 @Select 注解，谓词在注解里）
        int sqlStart = itemMapper.indexOf("SELECT DISTINCT oi.order_id");
        assertTrue(sqlStart > 0 && sqlStart < refunded, "冲销订单集查询的 SQL 在位");
        String refundedSql = itemMapper.substring(sqlStart);
        assertTrue(refundedSql.contains("oi.settle_id IS NOT NULL")
                        && refundedSql.contains("o.pay_status = 2")
                        && refundedSql.contains("DATE_ADD(#{anchorAfter}, INTERVAL 1 DAY)"),
                "冲销窗口谓词与 sumRefundAfterAnchor 逐字同源（同窗拆两条，口径不漂移）");
    }

    @Test
    void 结构性断言_两表uk在两路SQL逐字在位_钱包红线无违例() throws IOException {
        // m28 与 init.sql 双路建表结构一致（本仓两处同步纪律）
        String migration = sqlSource("sql/m28-migration.sql");
        String init = sqlSource("sql/init.sql");
        for (String source : List.of(migration, init)) {
            assertTrue(source.contains("UNIQUE KEY uk_order_biz (order_id, biz_type)"),
                    "同单同类型至多一条流水（跑批幂等库侧护栏）");
            assertTrue(source.contains("UNIQUE KEY uk_user_wallet (user_id)"), "一人一钱包");
            assertTrue(source.contains("KEY idx_user_time (user_id, created_at)"), "本人流水分页索引");
            assertTrue(source.contains("rate_snapshot DECIMAL(5,4)"), "费率快照列");
            assertTrue(source.contains("balance_after DECIMAL(10,2) NOT NULL"), "余额快照列（cash_flow 范式）");
        }
        // 红线（契约 §4）：余额只走原子加减、无按值覆写；user.balance 零涉及（两钱包物理隔离）
        String walletImpl = mainJavaSource("com/fresh/service/impl/PartnerWalletServiceImpl.java");
        assertFalse(walletImpl.contains(".setBalance("), "钱包余额绝无按值覆写（只允许原子加减）");
        assertFalse(walletImpl.contains("user.balance"), "user.balance 零涉及（两钱包物理隔离）");
    }

    // ---------------------------------------------------------------- 辅助

    private static AdminSettlementGenerateDTO dto(String start, String end) {
        AdminSettlementGenerateDTO dto = new AdminSettlementGenerateDTO();
        dto.setMerchantId(MERCHANT_ID);
        dto.setPeriodStart(start);
        dto.setPeriodEnd(end);
        return dto;
    }

    private static Merchant merchant() {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setName("平台自营");
        m.setStatus("active");
        return m;
    }

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

    /** 读 main 源码（结构性文本断言基元，照 SettlementGenerateTest#mapperSource 手法） */
    private static String mainJavaSource(String relative) throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path file = mainJava.resolve(relative);
        assumeTrue(Files.isRegularFile(file), "未找到 " + relative + "，本条跳过");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 读 SQL 源码（两路建表同步断言基元；backEnd 工作目录下 sql/ 与 src/ 同根） */
    private static String sqlSource(String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.isRegularFile(path)) {
            path = Path.of("backEnd").resolve(relative);
        }
        assumeTrue(Files.isRegularFile(path), "未找到 " + relative + "，本条跳过");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
