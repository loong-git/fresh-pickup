package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.PartnerWallet;
import com.fresh.entity.PartnerWalletFlow;
import com.fresh.entity.Settlement;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.PartnerWalletFlowMapper;
import com.fresh.mapper.PartnerWalletMapper;
import com.fresh.mapper.SettlementMapper;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P10-5 轨道 B B2 片单测（不起 Spring 容器、不落库；照 PartnerCommissionTest 夹具姿势）：
 * ① 打款状态机（D5）：「确认打款」两连调只生效一次（第二次幂等返回当前态、markPaid 零再触发）；
 *    已 paid 幂等直返；并发落败方（条件 UPDATE 影响行数 0）幂等返回不报错；
 *    404（单不存在）/400（非 accounted 状态）判据；审计 settlement.payout 实际置态才写且 detail 显式 MOCK。
 * ② 主理人提现（D6）：余额充足→条件扣减+WITHDRAW 负向流水落库断言（bizType/负额/快照/占位费率/
 *    MOCK 通道标注 remark/WD 单号锚）；余额不足 400 拒绝（钱包与流水零触碰）；并发条件扣减落败方
 *    同 400；金额形状（null/≤0/超两位小数/非数值）400 且零触碰。
 * ③ 结构性断言：markPaid 条件 UPDATE 与 MOCK_SIM 字面量在位；m32/init.sql 双路新列同步。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库条件 UPDATE 的行锁互斥、@Transactional 实滚回、
 * NOW() 服务端时钟、限流/鉴权（Controller 层）——由 mvn 全量、verify 脚本与主会话 live 验收覆盖。
 */
class SettlementPayoutWithdrawTest {

    private static final Long SETTLE_ID = 100L;
    private static final Long MERCHANT_ID = 1L;
    private static final Long OWNER = 7L;

    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
    private final PartnerWalletMapper walletMapper = mock(PartnerWalletMapper.class);
    private final PartnerWalletFlowMapper flowMapper = mock(PartnerWalletFlowMapper.class);

    private AdminServiceImpl adminService;
    private PartnerWalletServiceImpl walletService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，照 PartnerCommissionTest 同款手法手工灌内存缓存。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, PartnerWallet.class);
        TableInfoHelper.initTableInfo(assistant, PartnerWalletFlow.class);
    }

    @BeforeEach
    void setUp() {
        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");

        walletService = new PartnerWalletServiceImpl();
        ReflectionTestUtils.setField(walletService, "partnerWalletMapper", walletMapper);
        ReflectionTestUtils.setField(walletService, "partnerWalletFlowMapper", flowMapper);
    }

    // ---------------------------------------------------------------- fixtures

    private static Settlement settleRow(String status) {
        Settlement s = new Settlement();
        s.setId(SETTLE_ID);
        s.setSettleNo("SET100");
        s.setMerchantId(MERCHANT_ID);
        s.setStatus(status);
        s.setPayable(new BigDecimal("88.00"));
        return s;
    }

    /** 行的防御性拷贝（selectById 桩用——全字段复制，杜绝测试内两处引用同一实体互相污染） */
    private static Settlement copyOf(Settlement s) {
        Settlement c = settleRow(s.getStatus());
        c.setPayChannel(s.getPayChannel());
        c.setPaidTime(s.getPaidTime());
        return c;
    }

    private static PartnerWallet wallet(BigDecimal balance) {
        PartnerWallet w = new PartnerWallet();
        w.setId(OWNER * 10);
        w.setUserId(OWNER);
        w.setBalance(balance);
        return w;
    }

    /**
     * markPaid 条件 UPDATE 的有状态仿真（真库语义：WHERE status='accounted' AND paid_time IS NULL
     * 才置 paid——满足才改状态回 1，不满足零改动回 0）。selectById 恒回当前状态行的防御性拷贝，
     * 杜绝测试内两处引用同一实体互相污染。
     */
    private void stubConditionalMarkPaid(AtomicReference<Settlement> rowState) {
        when(settlementMapper.selectById(SETTLE_ID))
                .thenAnswer(inv -> copyOf(rowState.get()));
        when(settlementMapper.markPaid(SETTLE_ID)).thenAnswer(inv -> {
            Settlement cur = rowState.get();
            if ("accounted".equals(cur.getStatus()) && cur.getPaidTime() == null) {
                cur.setStatus("paid");
                cur.setPayChannel("MOCK_SIM");
                cur.setPaidTime(LocalDateTime.now());
                return 1;
            }
            return 0;
        });
    }

    // ================================================================
    // ① 打款状态机（D5）
    // ================================================================

    @Test
    void 打款_两连调只生效一次_第二次幂等返回当前态() {
        AtomicReference<Settlement> rowState = new AtomicReference<>(settleRow("accounted"));
        stubConditionalMarkPaid(rowState);

        // 第一调：实际置态（accounted→paid），transitioned=true
        var first = adminService.confirmSettlementPayout(SETTLE_ID, null);
        assertEquals("paid", first.get("status"));
        assertEquals(Boolean.TRUE, first.get("transitioned"));
        assertEquals("MOCK_SIM", first.get("payChannel"));

        // 第二调：已 paid 幂等返回当前态，不报错；markPaid 未被再次触发（条件 UPDATE 只生效一次）
        var second = adminService.confirmSettlementPayout(SETTLE_ID, null);
        assertEquals("paid", second.get("status"));
        assertEquals(Boolean.FALSE, second.get("transitioned"));
        assertEquals("MOCK_SIM", second.get("payChannel"));
        verify(settlementMapper, times(1)).markPaid(SETTLE_ID);
        // 审计只在实际置态那一次写（幂等重调不写 settlement.payout）
        verify(auditMapper, times(1)).insert(any(AdminAuditLog.class));
    }

    @Test
    void 打款_已paid直返当前态_markPaid与审计零触发() {
        when(settlementMapper.selectById(SETTLE_ID)).thenReturn(settleRow("paid"));

        var out = adminService.confirmSettlementPayout(SETTLE_ID, null);

        assertEquals("paid", out.get("status"));
        assertEquals(Boolean.FALSE, out.get("transitioned"));
        verify(settlementMapper, never()).markPaid(any());
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void 打款_并发落败方_条件UPDATE零行_幂等回读当前态不报错() {
        // 读时 accounted、写时他端已置 paid（markPaid 条件不满足回 0，回读已是 paid）——幂等语义非错误
        when(settlementMapper.selectById(SETTLE_ID))
                .thenReturn(settleRow("accounted"), settleRow("paid"));
        when(settlementMapper.markPaid(SETTLE_ID)).thenReturn(0);

        var out = adminService.confirmSettlementPayout(SETTLE_ID, null);

        assertEquals("paid", out.get("status"), "落败方回读到的是他端置好的 paid 当前态");
        assertEquals(Boolean.FALSE, out.get("transitioned"));
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void 打款_单不存在404_非法id400() {
        when(settlementMapper.selectById(SETTLE_ID)).thenReturn(null);
        BizException notFound = assertThrows(BizException.class,
                () -> adminService.confirmSettlementPayout(SETTLE_ID, null));
        assertEquals(404, notFound.getCode());
        assertEquals("结算单不存在", notFound.getMessage());

        BizException badId = assertThrows(BizException.class,
                () -> adminService.confirmSettlementPayout(0L, null));
        assertEquals(400, badId.getCode());
    }

    @Test
    void 打款_非accounted状态400_钱包与审计零触碰() {
        when(settlementMapper.selectById(SETTLE_ID)).thenReturn(settleRow("closed"));

        BizException ex = assertThrows(BizException.class,
                () -> adminService.confirmSettlementPayout(SETTLE_ID, null));
        assertEquals(400, ex.getCode());
        assertEquals("仅记账态账单可确认打款", ex.getMessage());
        verify(settlementMapper, never()).markPaid(any());
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void 打款_审计settlement_payout_detail显式MOCK通道() {
        when(settlementMapper.selectById(SETTLE_ID)).thenReturn(settleRow("accounted"));
        when(settlementMapper.markPaid(SETTLE_ID)).thenReturn(1);

        adminService.confirmSettlementPayout(SETTLE_ID, null);

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        AdminAuditLog audit = captor.getValue();
        assertEquals("settlement.payout", audit.getAction());
        assertEquals("settlement", audit.getTargetType());
        assertEquals(String.valueOf(SETTLE_ID), audit.getTargetId());
        assertTrue(audit.getDetail().contains("MOCK_SIM"), "通道显式 MOCK 落在审计 detail（红线）");
        assertTrue(audit.getDetail().contains("SET100"), "单号可追溯");
    }

    // ================================================================
    // ② 主理人提现（D6）
    // ================================================================

    @Test
    void 提现_余额充足_条件扣减_WITHDRAW负向流水落库断言() {
        // 余额 10.00 提 4.00：预查 10.00 → 扣减成功 → 快照回读 6.00
        when(walletMapper.selectOne(any()))
                .thenReturn(wallet(new BigDecimal("10.00")), wallet(new BigDecimal("6.00")));
        when(walletMapper.deductIfSufficient(eq(OWNER), eq(new BigDecimal("4.00")))).thenReturn(1);

        var out = walletService.withdraw(OWNER, "4");

        // 出参：扣减后余额 + 提现单号 + 负向流水额 + 模拟通道显式标注
        assertEquals(0, new BigDecimal("6.00").compareTo((BigDecimal) out.get("balance")));
        assertEquals("MOCK_SIM", out.get("payChannel"));
        assertEquals(0, new BigDecimal("-4.00").compareTo((BigDecimal) out.get("amount")));
        assertTrue(String.valueOf(out.get("withdrawNo")).startsWith("WD"), "提现单号 WD+雪花");

        // WITHDRAW 流水落库断言（bizType/负额/快照/占位费率/MOCK remark/WD 锚）
        ArgumentCaptor<PartnerWalletFlow> captor = ArgumentCaptor.forClass(PartnerWalletFlow.class);
        verify(flowMapper).insert(captor.capture());
        PartnerWalletFlow flow = captor.getValue();
        assertEquals(PartnerWalletFlow.BIZ_WITHDRAW, flow.getBizType());
        assertEquals(OWNER, flow.getUserId());
        assertEquals(0, new BigDecimal("-4.00").compareTo(flow.getAmount()), "提现流水恒负（负向即时入账）");
        assertEquals(0, new BigDecimal("6.00").compareTo(flow.getBalanceAfter()), "balance_after=扣减后快照");
        assertEquals(0, new BigDecimal("0.0000").compareTo(flow.getRateSnapshot()),
                "withdraw 无费率语义，占位 0.0000（与佣金 0.0300 区分）");
        assertTrue(flow.getOrderId().startsWith("WD"), "订单锚位放提现单号（非订单）");
        assertNull(flow.getSettlementId(), "提现与账单无关，settlement_id 为空");
        assertTrue(flow.getRemark() != null && flow.getRemark().contains("MOCK_SIM"),
                "通道标注显式 MOCK（红线：资金流水不得无通道标注）");
    }

    @Test
    void 提现_余额不足400拒绝_钱包与流水零触碰() {
        when(walletMapper.selectOne(any())).thenReturn(wallet(new BigDecimal("3.00")));

        BizException ex = assertThrows(BizException.class, () -> walletService.withdraw(OWNER, "5.00"));
        assertEquals(400, ex.getCode());
        assertEquals("余额不足", ex.getMessage());
        verify(walletMapper, never()).deductIfSufficient(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 提现_并发条件扣减落败方_同样400余额不足() {
        // 预查时余额充足、条件扣减 0 行（并发双提落败方）——统一 400，绝无透支
        when(walletMapper.selectOne(any())).thenReturn(wallet(new BigDecimal("10.00")));
        when(walletMapper.deductIfSufficient(eq(OWNER), eq(new BigDecimal("4.00")))).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> walletService.withdraw(OWNER, "4.00"));
        assertEquals(400, ex.getCode());
        assertEquals("余额不足", ex.getMessage());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    @Test
    void 提现_金额形状四类400_零触碰() {
        // null / ≤0 / 超两位小数 / 非数值——形状闸全 400，钱包与流水零触碰
        for (String bad : new String[]{null, "0", "-1", "1.999", "abc", " "}) {
            assertThrows(BizException.class, () -> walletService.withdraw(OWNER, bad),
                    "金额 " + bad + " 应被形状闸拒绝");
        }
        verify(walletMapper, never()).deductIfSufficient(any(), any());
        verify(flowMapper, never()).insert(any(PartnerWalletFlow.class));
    }

    // ================================================================
    // ③ 结构性/文本级断言（照 PartnerCommissionTest#mainJavaSource/sqlSource 手法）
    // ================================================================

    @Test
    void 结构性断言_打款条件UPDATE与模拟通道字面量在位() throws IOException {
        String mapperSource = mainJavaSource("com/fresh/mapper/SettlementMapper.java");
        assertTrue(mapperSource.contains("status = 'accounted' AND paid_time IS NULL"),
                "markPaid 必须是条件 UPDATE（幂等护栏：accounted 且未打款才置态）");
        assertTrue(mapperSource.contains("pay_channel = 'MOCK_SIM'"),
                "模拟通道显式 MOCK 落在 SQL 字面量（防演示冒充结算）");
        assertTrue(mapperSource.contains("paid_time = NOW()"), "打款时间由服务端 NOW() 定形");
        // 通道常量与 SQL 字面量同源（AdminServiceImpl 侧）
        String adminSource = mainJavaSource("com/fresh/service/impl/AdminServiceImpl.java");
        assertTrue(adminSource.contains("SETTLEMENT_PAY_CHANNEL_MOCK_SIM = \"MOCK_SIM\""));
    }

    @Test
    void 结构性断言_m32与init双路新列同步_withdraw类型注释在位() throws IOException {
        String migration = sqlSource("sql/m32-migration.sql");
        String init = sqlSource("sql/init.sql");
        for (String source : List.of(migration, init)) {
            assertTrue(source.contains("paid_time"), "settlement.paid_time（模拟轨打款时间）");
            assertTrue(source.contains("pay_channel"), "settlement.pay_channel（模拟通道标注）");
            assertTrue(source.contains("MOCK_SIM"), "通道注释显式 MOCK");
            assertTrue(source.contains("withdraw"), "biz_type 注释补 withdraw（D6）");
        }
        // m32 幂等骨架（照 m30：information_schema 判存 + PREPARE）
        assertTrue(migration.contains("information_schema.COLUMNS"));
        assertTrue(migration.contains("PREPARE m32_stmt"));
    }

    @Test
    void 结构性断言_提现流水在扣减成功后落库_条件扣减带下限谓词() throws IOException {
        // ① 调用序：条件扣减（deductIfSufficient）→ 回读快照（readBalance）→ 流水 insert——
        //    扣减先行，流水与扣减同事务（@Transactional 由真库滚回覆盖，此处锁调用序）；
        // ② 条件扣减 SQL 必须带 balance >= 下限谓词（提现绝不透支，与冲回「如实记负」两种语义）。
        String walletImpl = mainJavaSource("com/fresh/service/impl/PartnerWalletServiceImpl.java");
        int deduct = walletImpl.indexOf("deductIfSufficient(userId, amount)");
        int snapshot = walletImpl.indexOf("BigDecimal balanceAfter = readBalance(userId)", deduct);
        int insert = walletImpl.indexOf("partnerWalletFlowMapper.insert(flow)", snapshot);
        assertTrue(deduct > 0 && snapshot > deduct && insert > snapshot, "扣减→快照→流水的调用序在位");
        assertTrue(walletImpl.contains("@Transactional"), "提现方法挂事务（扣减与流水同生同灭）");

        String mapperSource = mainJavaSource("com/fresh/mapper/PartnerWalletMapper.java");
        assertTrue(mapperSource.contains("AND balance >= #{amount}"), "条件扣减带余额下限谓词");
    }

    @Test
    void 结构性断言_列表出参通道键按状态互斥() throws IOException {
        // P10-5 D5：paid 行出 payChannel、其余行出 paidChannelAbsent——对账 reconcileOk 逻辑零改动
        String adminSource = mainJavaSource("com/fresh/service/impl/AdminServiceImpl.java");
        int reconcile = adminSource.indexOf("line.put(\"reconcileOk\", reconcileOk)");
        int channel = adminSource.indexOf("line.put(\"payChannel\", row.getPayChannel())");
        int absent = adminSource.indexOf("line.put(\"paidChannelAbsent\", Boolean.TRUE)");
        assertTrue(reconcile > 0 && channel > reconcile && absent > channel,
                "通道键改造只追加在既有行装配内，对账逻辑在其前不动");
    }

    // ---------------------------------------------------------------- 辅助

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

    /** 读 main 源码（结构性文本断言基元，照 PartnerCommissionTest 手法） */
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
