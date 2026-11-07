package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.DepositFlow;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantDeposit;
import com.fresh.entity.Settlement;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DepositFlowMapper;
import com.fresh.mapper.MerchantDepositMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.SettlementMapper;
import com.fresh.service.MerchantDepositService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P10-5 轨道 B B3 片单测（不起 Spring 容器、不落库；照 SettlementPayoutWithdrawTest 夹具姿势）：
 * ① D8 代扣（MerchantDepositServiceImpl#deductOnSettlement 直测）：
 *    全代扣（gap≤net → deduct=gap、status=active）、部分代扣（gap&gt;net → deduct=net、status=arrears）、
 *    无档位四路跳过（无账户行 / required≤0 / 已缴足 / net≤0——settlement 与 flow 零触碰）；
 *    流水六键断言（type=2 应结款代扣 / amount 恒正 / balance_after 快照 / biz_id=settleNo /
 *    channel=MOCK 显式 / operator=SYSTEM）；applyDeduct 落 0 行（并发改写）→ 500 上抛整单回滚语义。
 * ② D7 置档位（setRequired）：启用建户（arrears）/调档（已缴足→active）/停用（required=0→none）；
 *    形状闸门（负数/三位小数 400）与 category 白名单（非 11 类 400）、merchant 404。
 * ③ 生成账期 ⑦.6 接线（AdminServiceImpl#generateSettlement）：deduct&gt;0 → deposit_amount 与
 *    payable=net−deposit 同条回填 + 审计 settlement.deposit（MOCK 显式）+ 出参 deposit 键；
 *    deduct=0 → 回填/审计零触发；同期重跑 400 时 deductOnSettlement 零触发（幂等护栏在代扣之前）。
 * ④ 结构性断言：m33/init.sql 双路建表同步（deposit_amount/paid_amount/channel 三补列与 DDL 基线）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 @Transactional 实滚回（代扣失败账单整体回滚）、
 * uk_merchant 并发互斥、乐观条件 UPDATE 的行锁语义——由 mvn 全量、verify 脚本与主会话 live 验收覆盖。
 */
class MerchantDepositDeductTest {

    private static final Long MERCHANT_ID = 1L;
    private static final Long ACCOUNT_ID = 55L;
    private static final Long SETTLE_ID = 100L;
    private static final String SETTLE_NO = "SET100";
    private static final String START = "2026-11-01";
    private static final String END = "2026-11-15";

    private final MerchantDepositMapper depositMapper = mock(MerchantDepositMapper.class);
    private final DepositFlowMapper flowMapper = mock(DepositFlowMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    /** AdminServiceImpl ⑦.6 的下游（mock 隔离：deduct 判定已在本类 ①② 直测，接线层只验证回填/审计/出参） */
    private final MerchantDepositService depositServiceMock = mock(MerchantDepositService.class);

    private MerchantDepositServiceImpl depositService;
    private AdminServiceImpl adminService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，照 SettlementPayoutWithdrawTest 同款手法手工灌内存缓存。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, MerchantDeposit.class);
        TableInfoHelper.initTableInfo(assistant, DepositFlow.class);
        TableInfoHelper.initTableInfo(assistant, Merchant.class);
    }

    @BeforeEach
    void setUp() {
        depositService = new MerchantDepositServiceImpl();
        ReflectionTestUtils.setField(depositService, "merchantDepositMapper", depositMapper);
        ReflectionTestUtils.setField(depositService, "depositFlowMapper", flowMapper);
        ReflectionTestUtils.setField(depositService, "merchantMapper", merchantMapper);

        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(adminService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(adminService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
        ReflectionTestUtils.setField(adminService, "partnerWalletService",
                mock(com.fresh.service.PartnerWalletService.class));
        ReflectionTestUtils.setField(adminService, "merchantDepositService", depositServiceMock);
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant());
    }

    // ---------------------------------------------------------------- fixtures

    private static Merchant merchant() {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setName("平台自营");
        m.setStatus("active");
        return m;
    }

    /** 保证金账户行（required/paid 由用例定形并统一 scale=2——生产 DECIMAL(10,2) 读出即两位，
     *  而 Mockito eq() 走 BigDecimal.equals 对 scale 敏感，不定形会让条件 UPDATE 桩永不命中）；
     *  无账户行用例不 stub selectOne */
    private static MerchantDeposit account(BigDecimal required, BigDecimal paid) {
        MerchantDeposit d = new MerchantDeposit();
        d.setId(ACCOUNT_ID);
        d.setMerchantId(MERCHANT_ID);
        d.setCategory("meat");
        d.setRequiredAmount(required == null ? null : required.setScale(2, java.math.RoundingMode.HALF_UP));
        d.setDepositAmount(BigDecimal.ZERO);
        d.setRetainedAmount(BigDecimal.ZERO);
        d.setPaidAmount(paid == null ? null : paid.setScale(2, java.math.RoundingMode.HALF_UP));
        d.setStatus(required != null && paid != null && paid.compareTo(required) >= 0 ? "active" : "none");
        return d;
    }

    private static AdminSettlementGenerateDTO dto() {
        AdminSettlementGenerateDTO dto = new AdminSettlementGenerateDTO();
        dto.setMerchantId(MERCHANT_ID);
        dto.setPeriodStart(START);
        dto.setPeriodEnd(END);
        return dto;
    }

    /** 生成账期 happy path 桩（两候选 gross=20.00、refund=0 → net=20.00；照 PartnerCommissionTest 同款） */
    private void stubGenerateHappyPath() {
        when(orderItemMapper.selectSettleCandidates(MERCHANT_ID)).thenReturn(List.of(
                Map.of("id", 11L, "orderId", "O1", "dishName", "甲菜", "supplyPrice", new BigDecimal("10.00"),
                        "quantity", 1, "payStatus", 1, "fulfillStatus", "normal"),
                Map.of("id", 12L, "orderId", "O2", "dishName", "乙菜", "supplyPrice", new BigDecimal("10.00"),
                        "quantity", 1, "payStatus", 1, "fulfillStatus", "normal")));
        when(settlementMapper.selectMaxPeriodEnd(MERCHANT_ID)).thenReturn(null);
        when(settlementMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(SETTLE_ID);
            return 1;
        }).when(settlementMapper).insert(any(Settlement.class));
        when(orderItemMapper.attachSettle(eq(SETTLE_ID), anyList())).thenReturn(2);
        // settleNo 是动态雪花（SET+IdWorker.getIdStr()），stub 只能按 merchantId 匹配、settleNo 放 anyString()
        when(depositServiceMock.deductOnSettlement(eq(MERCHANT_ID), anyString(), any()))
                .thenReturn(Map.of("deduct", BigDecimal.ZERO, "paidAfter", BigDecimal.ZERO,
                        "status", "none", "required", BigDecimal.ZERO, "gap", BigDecimal.ZERO));
    }

    // ================================================================
    // ① D8 代扣直测
    // ================================================================

    @Test
    void 代扣_全代扣_gap小于net_扣足档位_状态active_流水MOCK通道() {
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("80.00"), BigDecimal.ZERO));
        when(depositMapper.applyDeduct(eq(ACCOUNT_ID), eq(new BigDecimal("0.00")),
                eq(new BigDecimal("80.00")), eq("active"))).thenReturn(1);

        Map<String, Object> out = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, new BigDecimal("100.00"));

        // deduct=min(gap=80, net=100)=80；paidAfter=80=required → active
        assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) out.get("deduct")));
        assertEquals(0, new BigDecimal("80.00").compareTo((BigDecimal) out.get("paidAfter")));
        assertEquals("active", out.get("status"));

        // 账户乐观落库断言（旧值比对进 WHERE）
        verify(depositMapper, times(1)).applyDeduct(ACCOUNT_ID,
                new BigDecimal("0.00"), new BigDecimal("80.00"), "active");

        // 流水六键：type=2 应结款代扣 / amount 恒正 / balance_after 快照 / biz_id=settleNo /
        // channel=MOCK 显式（红线：资金流水不得无通道标注）/ operator=SYSTEM 跑批留痕
        ArgumentCaptor<DepositFlow> flowCaptor = ArgumentCaptor.forClass(DepositFlow.class);
        verify(flowMapper, times(1)).insert(flowCaptor.capture());
        DepositFlow flow = flowCaptor.getValue();
        assertEquals(2, flow.getType());
        assertEquals(0, new BigDecimal("80.00").compareTo(flow.getAmount()));
        assertEquals(0, new BigDecimal("80.00").compareTo(flow.getBalanceAfter()));
        assertEquals(SETTLE_NO, flow.getBizId());
        assertNull(flow.getBizType(), "type=2 是缴存形态非扣罚白名单，biz_type 恒 null");
        assertEquals("MOCK", flow.getChannel());
        assertEquals("SYSTEM", flow.getOperator());
        assertTrue(flow.getRemark() != null && flow.getRemark().contains("MOCK"),
                "备注同步带 MOCK 通道标注");
    }

    @Test
    void 代扣_部分代扣_net小于gap_扣光应结_状态欠费待补() {
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("80.00"), BigDecimal.ZERO));
        when(depositMapper.applyDeduct(eq(ACCOUNT_ID), eq(new BigDecimal("0.00")),
                eq(new BigDecimal("50.00")), eq("arrears"))).thenReturn(1);

        Map<String, Object> out = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, new BigDecimal("50.00"));

        // deduct=min(gap=80, net=50)=50；paidAfter=50<80 → arrears（欠费待补）
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) out.get("deduct")));
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) out.get("paidAfter")));
        assertEquals("arrears", out.get("status"));
        verify(depositMapper, times(1)).applyDeduct(ACCOUNT_ID,
                new BigDecimal("0.00"), new BigDecimal("50.00"), "arrears");
        verify(flowMapper, times(1)).insert(any(DepositFlow.class));
    }

    @Test
    void 代扣_无档位四路全跳过_settlement与flow零触碰() {
        // 路 1：无账户行
        when(depositMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> out1 = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, new BigDecimal("100.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out1.get("deduct")));

        // 路 2：required=0（运营停用）
        when(depositMapper.selectOne(any())).thenReturn(
                account(BigDecimal.ZERO, BigDecimal.ZERO));
        Map<String, Object> out2 = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, new BigDecimal("100.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out2.get("deduct")));

        // 路 3：已缴足（paid>=required）
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("80.00"), new BigDecimal("80.00")));
        Map<String, Object> out3 = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, new BigDecimal("100.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out3.get("deduct")));

        // 路 4：net<=0（无可扣应结）
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("80.00"), BigDecimal.ZERO));
        Map<String, Object> out4 = depositService.deductOnSettlement(
                MERCHANT_ID, SETTLE_NO, BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out4.get("deduct")));

        // 四路全跳过：账户零写、流水零落（契约红线：无档位商家既有行为零变化）
        verify(depositMapper, never()).applyDeduct(any(), any(), any(), any());
        verify(flowMapper, never()).insert(any(DepositFlow.class));
    }

    @Test
    void 代扣_账户并发改写落库零行_500上抛流水零落() {
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("80.00"), BigDecimal.ZERO));
        when(depositMapper.applyDeduct(any(), any(), any(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> depositService.deductOnSettlement(MERCHANT_ID, SETTLE_NO, new BigDecimal("100.00")));
        assertEquals(R.CODE_SERVER_ERROR, ex.getCode(), "落库冲突=500（调用方 @Transactional 整单回滚）");
        verify(flowMapper, never()).insert(any(DepositFlow.class));
    }

    // ================================================================
    // ② D7 置档位
    // ================================================================

    @Test
    void 置档位_首次启用建户_欠费待补() {
        when(depositMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> out = depositService.setRequired(MERCHANT_ID, new BigDecimal("8000"), "meat");

        assertEquals("arrears", out.get("status"), "首次启用且已缴 0 < 8000 → 欠费待补");
        ArgumentCaptor<MerchantDeposit> captor = ArgumentCaptor.forClass(MerchantDeposit.class);
        verify(depositMapper, times(1)).insert(captor.capture());
        MerchantDeposit row = captor.getValue();
        assertEquals(MERCHANT_ID, row.getMerchantId());
        assertEquals(0, new BigDecimal("8000.00").compareTo(row.getRequiredAmount()));
        assertEquals("meat", row.getCategory());
        assertEquals("arrears", row.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(row.getPaidAmount()), "建户已缴从 0 起");
        verify(depositMapper, never()).updateById(any(MerchantDeposit.class));
    }

    @Test
    void 置档位_已缴足新档位_状态active_停用归none() {
        // 已缴 8000，新档 8000 → active（updateById 全字段实体更新）
        when(depositMapper.selectOne(any())).thenReturn(
                account(new BigDecimal("8000.00"), new BigDecimal("8000.00")));
        Map<String, Object> out = depositService.setRequired(MERCHANT_ID, new BigDecimal("8000"), null);
        assertEquals("active", out.get("status"));
        verify(depositMapper, times(1)).updateById(any(MerchantDeposit.class));

        // 停用（required=0）：required 置 0、status 回 none、已缴记录保留
        Map<String, Object> outStop = depositService.setRequired(MERCHANT_ID, BigDecimal.ZERO, null);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) outStop.get("requiredAmount")));
        assertEquals("none", outStop.get("status"));
        verify(depositMapper, times(2)).updateById(any(MerchantDeposit.class));
    }

    @Test
    void 置档位_形状与白名单与404闸门() {
        // 负数 / 三位小数 → 400（照提现同款形状闸门）
        assertEquals(R.CODE_BAD_REQUEST, assertThrows(BizException.class,
                () -> depositService.setRequired(MERCHANT_ID, new BigDecimal("-1"), null)).getCode());
        assertEquals(R.CODE_BAD_REQUEST, assertThrows(BizException.class,
                () -> depositService.setRequired(MERCHANT_ID, new BigDecimal("8000.001"), null)).getCode());
        // category 非 11 类 → 400
        assertEquals(R.CODE_BAD_REQUEST, assertThrows(BizException.class,
                () -> depositService.setRequired(MERCHANT_ID, new BigDecimal("8000"), "toys")).getCode());
        // requiredAmount 缺失 → 400
        assertEquals(R.CODE_BAD_REQUEST, assertThrows(BizException.class,
                () -> depositService.setRequired(MERCHANT_ID, null, null)).getCode());
        // merchant 404（照生成账期同文案）
        when(merchantMapper.selectById(999L)).thenReturn(null);
        BizException notFound = assertThrows(BizException.class,
                () -> depositService.setRequired(999L, new BigDecimal("8000"), null));
        assertEquals(R.CODE_NOT_FOUND, notFound.getCode());
        assertEquals("商家不存在", notFound.getMessage());
        // 全部闸门拒绝：账户与流水零触碰
        verify(depositMapper, never()).insert(any(MerchantDeposit.class));
        verify(depositMapper, never()).updateById(any(MerchantDeposit.class));
        verify(flowMapper, never()).insert(any(DepositFlow.class));
    }

    // ================================================================
    // ③ 生成账期 ⑦.6 接线
    // ================================================================

    @Test
    void 生成账期_代扣生效_deposit与payable回填_审计deposit_MOCK显式() {
        stubGenerateHappyPath();
        when(depositServiceMock.deductOnSettlement(eq(MERCHANT_ID), anyString(),
                eq(new BigDecimal("20.00"))))
                .thenReturn(Map.of("deduct", new BigDecimal("20.00"), "paidAfter", new BigDecimal("20.00"),
                        "status", "active", "required", new BigDecimal("20.00"),
                        "gap", new BigDecimal("20.00")));

        Map<String, Object> out = adminService.generateSettlement(dto(), "admin_user:7");

        // 出参 deposit 键=代扣额；net 键仍是聚合净额 20（聚合逻辑零改动的面）
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) out.get("deposit")));
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) out.get("net")));

        // settlement UPDATE 共两次（⑦.5 commission 单列 + ⑦.6 deposit/payable 回填）；
        // ⑦.6 的 wrapper 参数里找 20.00（deposit）与 0.00（payable=net−deposit）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Settlement>> wrapperCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(settlementMapper, times(2)).update(any(), wrapperCaptor.capture());
        List<Wrapper<Settlement>> wrappers = wrapperCaptor.getAllValues();
        AbstractWrapper<Settlement, ?, ?> depositWrapper = (AbstractWrapper) wrappers.get(1);
        List<BigDecimal> nums = depositWrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof BigDecimal)
                .map(v -> (BigDecimal) v)
                .toList();
        assertTrue(nums.stream().anyMatch(b -> new BigDecimal("20.00").compareTo(b) == 0),
                "deposit_amount 回填=代扣额 20.00");
        assertTrue(nums.stream().anyMatch(b -> BigDecimal.ZERO.compareTo(b) == 0),
                "payable 回填=net−deposit=0.00");

        // 审计两条：settlement.deposit（⑦.6 实际代扣才写，MOCK 显式——⑦.6 先于 ⑧ 执行）+
        // settlement.create（既有 ⑧）；插入序=执行序，deposit 在前
        ArgumentCaptor<AdminAuditLog> auditCaptor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper, times(2)).insert(auditCaptor.capture());
        List<AdminAuditLog> audits = auditCaptor.getAllValues();
        assertEquals("settlement.deposit", audits.get(0).getAction());
        assertTrue(audits.get(0).getDetail().contains("MOCK"),
                "代扣审计 detail 显式 MOCK 通道");
        assertEquals("settlement.create", audits.get(1).getAction(), "既有 ⑧ 审计仍在其位");
    }

    @Test
    void 生成账期_无档位零代扣_回填与审计零触发_出参deposit为0() {
        stubGenerateHappyPath(); // deductOnSettlement 默认 stub 返回 deduct=0

        Map<String, Object> out = adminService.generateSettlement(dto(), "admin_user:7");

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("deposit")));
        // update 只 ⑦.5 commission 一次；审计只 settlement.create 一条（⑦.6 零触发=既有行为零变化）
        verify(settlementMapper, times(1)).update(any(), any());
        verify(auditMapper, times(1)).insert(any(AdminAuditLog.class));
    }

    @Test
    void 生成账期_同期重跑400_代扣零触发() {
        stubGenerateHappyPath();
        when(settlementMapper.selectCount(any())).thenReturn(1L); // uk_merchant_period 预查命中

        BizException ex = assertThrows(BizException.class,
                () -> adminService.generateSettlement(dto(), "admin_user:7"));
        assertEquals(R.CODE_BAD_REQUEST, ex.getCode());
        assertEquals("该商家此账期已生成", ex.getMessage());
        // 幂等护栏在代扣之前：deductOnSettlement 零触发（重跑绝不二次代扣）
        verify(depositServiceMock, never()).deductOnSettlement(any(), any(), any());
        verify(flowMapper, never()).insert(any(DepositFlow.class));
    }

    // ================================================================
    // ④ 结构性断言（照 SettlementPayoutWithdrawTest#sqlSource 手法）
    // ================================================================

    @Test
    void 结构性断言_m33与init双路保证金层同步() throws IOException {
        String migration = sqlSource("sql/m33-migration.sql");
        String init = sqlSource("sql/init.sql");

        // 两表两路都在：merchant_deposit（paid_amount 补列）+ deposit_flow（channel 补列）
        for (String src : List.of(migration, init)) {
            assertTrue(src.contains("CREATE TABLE IF NOT EXISTS merchant_deposit"), "merchant_deposit 建表在位");
            assertTrue(src.contains("CREATE TABLE IF NOT EXISTS deposit_flow"), "deposit_flow 建表在位");
            assertTrue(src.contains("paid_amount"), "D8 已缴列（06 册草案无，补列登记偏离）在位");
            assertTrue(src.contains("'MOCK'"), "channel 默认 MOCK（红线：资金流水显式通道）在位");
            assertTrue(src.contains("uk_merchant"), "一主体一账户唯一键在位");
            assertTrue(src.contains("idx_biz"), "来源单据索引在位");
        }
        // settlement.deposit_amount 双路同步 + 代扣后口径注释
        assertTrue(migration.contains("ADD COLUMN deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0"),
                "m33 加列语句在位");
        assertTrue(init.contains("deposit_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '本期代扣入保证金额"),
                "init.sql settlement 建表体同步该列");
        // m33 幂等骨架（information_schema 判存 + PREPARE，照 m32）
        assertTrue(migration.contains("PREPARE m33_stmt"));
        // 编号顺延纪律：m32 头注明文登记 B3=m33
        assertTrue(sqlSource("sql/m32-migration.sql").contains("顺延为 m33"),
                "m32 头注的 B3 编号顺延登记在位");
    }

    /** 读 SQL 源码（双路同步断言基元；backEnd 工作目录下 sql/ 与 src/ 同根） */
    private static String sqlSource(String relative) throws IOException {
        Path path = Path.of(relative);
        if (!Files.isRegularFile(path)) {
            path = Path.of("backEnd").resolve(relative);
        }
        assumeTrue(Files.isRegularFile(path), "未找到 " + relative + "，本条跳过");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
