package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Merchant;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Settlement;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.SettlementMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6-3 §1 运营端 CSV 导出单测（不起 Spring 容器、不落库）：
 * ① orders 导出：复用 F-20.18 同一组 SQL（桩钉住零筛选全量透传）、BOM 与中文表头、
 *    手机号脱敏（明文零出现，PII 面不扩大）、商品明细「品名×数量」/ 连接、状态文案定形、
 *    RFC 4180 最小转义（品名含逗号/引号）、5000 上限截断尾注、审计恰 1 行（order.export）。
 * ② settlements 导出：逐页复用 listSettlements（对账 ✓/✗ 定形）、筛选三参透传、
 *    分页拉全、5000 上限、审计 detail 含行数与筛选（bill.export）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库聚合语义（对账三条件归 verify 脚本与
 * MerchantSettlementViewTest）、Controller 限流/401（拦截器与闸门层，归 AdminExportControllerTest）。
 */
class AdminCsvExportTest {

    private static final String ACTOR = "admin_user:2";

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);

    private AdminServiceImpl adminService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 MerchantSettlementViewTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(adminService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(adminService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(adminService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", adminAuditLogMapper);
        // 静态密钥轨审计 actor 需要（本测试走 JWT 轨 actor，不为它设值也不会走摘要；设上防误触）
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    /** 订单列表页一行的 Map 形状（键照 OrderMapper.selectOrdersPage 的 SQL 别名） */
    private static Map<String, Object> orderRow(String id, String phone, String status, String storeName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("totalPrice", new BigDecimal("12.50"));
        row.put("address", "幸福路 1 号");
        row.put("phone", phone);
        row.put("pickupDate", "2026-10-31");
        row.put("status", status);
        row.put("createTime", "2026-10-31 10:00:00");
        row.put("updateTime", "2026-10-31 10:00:00");
        row.put("storeId", 1L);
        row.put("storeName", storeName);
        row.put("storeAddress", "幸福路 2 号");
        row.put("payStatus", 1);
        row.put("payTime", null);
        return row;
    }

    private static OrderItem item(String dishName, int qty) {
        OrderItem item = new OrderItem();
        item.setDishName(dishName);
        item.setQuantity(qty);
        return item;
    }

    /** 一行已生成账单（照 MerchantSettlementViewTest.storedSettlement 的列口径） */
    private static Settlement settlement(long id, long merchantId, String gmv) {
        Settlement row = new Settlement();
        row.setId(id);
        row.setSettleNo("SET" + id);
        row.setMerchantId(merchantId);
        row.setPeriodStart(LocalDate.parse("2026-10-01"));
        row.setPeriodEnd(LocalDate.parse("2026-10-15"));
        row.setOrderCount(2);
        row.setGmvAmount(new BigDecimal(gmv));
        row.setRefundAmount(BigDecimal.ZERO);
        row.setRetainAmount(BigDecimal.ZERO);
        row.setPayable(new BigDecimal(gmv));
        row.setCommission(BigDecimal.ZERO);
        row.setStatus("accounted");
        return row;
    }

    private static Merchant merchant(long id, String name) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setName(name);
        return m;
    }

    /** 挂锚聚合行（selectAnchoredAggregates 出参行的 Map 形状，键照 mapper 别名） */
    private static Map<String, Object> agg(long settleId, long lineCount, long orderCount,
                                           String gross, long orphanCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("settleId", settleId);
        m.put("lineCount", lineCount);
        m.put("orderCount", orderCount);
        m.put("gross", new BigDecimal(gross));
        m.put("orphanCount", orphanCount);
        return m;
    }

    private static String csvText(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 审计恰 1 行的公共断言：insert 恰一次并回传该行（P6-3 §3「审计恰 1 行」） */
    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    // ================================================================
    // ① orders 导出
    // ================================================================

    @Test
    void orders导出_零筛选全量透传_BOM与中文表头_手机号脱敏明文零出现() {
        when(orderMapper.countOrders(isNull(), isNull(), isNull())).thenReturn(1L);
        when(orderMapper.selectOrdersPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenReturn(List.of(orderRow("O1", "13800001234", "pending_pickup", "东门店")));
        when(orderItemMapper.selectList(any()))
                .thenReturn(List.of(item("苹果", 2), item("香蕉", 1)));

        byte[] body = adminService.exportOrdersCsv(ACTOR);
        String text = csvText(body);

        // 复用既有列表口径的接线：零筛选全量（status/storeId/day 皆 null）透传同一条 SQL
        verify(orderMapper).countOrders(isNull(), isNull(), isNull());
        verify(orderMapper).selectOrdersPage(isNull(), isNull(), isNull(), eq(0L), anyInt());

        // BOM 三字节（EF BB BF，Excel 中文兼容）+ 中文表头恰为契约 §1 列集
        assertEquals((byte) 0xEF, body[0]);
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);
        assertTrue(text.startsWith("\uFEFF订单号,下单时间,状态,自提日期,自提点,手机号,商品明细,合计金额\r\n"),
                "首行必须是 BOM + 契约列集的中文表头");

        // PII 红线：脱敏值在、明文零出现
        assertTrue(text.contains("138****1234"), "手机号列必须是 PhoneMask 掩码值");
        assertFalse(text.contains("13800001234"), "明文手机号绝不允许出现在导出结果中");

        // 商品明细「品名×数量」/ 连接 + 状态文案 + 金额两位小数
        assertTrue(text.contains("O1,2026-10-31 10:00:00,待自提,2026-10-31,东门店,138****1234,苹果×2/香蕉×1,12.50"),
                "数据行逐列照契约列序：实际为\n" + text);
    }

    @Test
    void orders导出_状态三态文案映射_未知值原样透传() {
        when(orderMapper.countOrders(isNull(), isNull(), isNull())).thenReturn(2L);
        when(orderMapper.selectOrdersPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenReturn(List.of(orderRow("O1", "13800001234", "completed", "东门店"),
                        // O2 复用已裁定演示号（f28 §10c 手机号形态判据只放行裁定集合，本测试断言的是状态列透传与号码值无关）
                        orderRow("O2", "13800001234", "future_state", "西门店")));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        String text = csvText(adminService.exportOrdersCsv(ACTOR));

        assertTrue(text.contains("O1,2026-10-31 10:00:00,已完成,"), "completed → 已完成（照运营页 STATUS_TEXT）");
        assertTrue(text.contains("O2,2026-10-31 10:00:00,future_state,"), "未知状态原样透传不编词");
        // 空明细出空串列（两连逗号），不编内容
        assertTrue(text.contains("138****1234,,"), "无明细时商品明细列为空串");
    }

    @Test
    void orders导出_RFC4180最小转义_品名含逗号引号() {
        when(orderMapper.countOrders(isNull(), isNull(), isNull())).thenReturn(1L);
        when(orderMapper.selectOrdersPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenReturn(List.of(orderRow("O1", "13800001234", "pending_pickup", "东门店")));
        when(orderItemMapper.selectList(any()))
                .thenReturn(List.of(item("草莓,罐头", 1), item("\"有机\"菜", 2)));

        String text = csvText(adminService.exportOrdersCsv(ACTOR));

        // 含逗号 → 整格加引号；含双引号 → 整格加引号且 " 双写（RFC 4180 最小集）
        assertTrue(text.contains("\"草莓,罐头×1"), "含逗号的明细格必须整体加引号");
        assertTrue(text.contains("\"\"有机\"\"菜×2"), "含双引号的明细格必须加引号且引号双写");
        // 不含特殊字符的格不加引号（最小集，只包到问题格，不是全行包裹）
        assertTrue(text.contains("待自提,2026-10-31,东门店,138****1234,\""), "无特殊字符的格保持裸值");
    }

    @Test
    void orders导出_5000上限截断_尾注一行_审计恰1行含行数与截断标志() {
        final long total = 5100L;
        when(orderMapper.countOrders(isNull(), isNull(), isNull())).thenReturn(total);
        when(orderMapper.selectOrdersPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    long offset = inv.getArgument(3);
                    int size = inv.getArgument(4);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (long i = offset; i < Math.min(offset + size, total); i++) {
                        rows.add(orderRow("O" + i, "13800001234", "pending_pickup", "店"));
                    }
                    return rows;
                });
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item("菜", 1)));

        String text = csvText(adminService.exportOrdersCsv(ACTOR));

        // 表头 1 + 数据恰 5000（硬顶）+ 尾注 1；行尾 CRLF 的尾空串由 split -1 保留
        String[] lines = text.split("\r\n", -1);
        assertEquals(1 + 5000 + 1 + 1, lines.length, "表头+5000 数据行+尾注+尾空串");
        assertEquals("\uFEFF订单号,下单时间,状态,自提日期,自提点,手机号,商品明细,合计金额", lines[0],
                "首行是 BOM + 表头（BOM 只在文件头一处）");
        assertFalse(lines[5000].isEmpty(), "第 5000 数据行必须满额（硬顶不缺角）");
        assertTrue(lines[5001].contains("5100") && lines[5001].contains("5000"),
                "尾注必须注明数据源总数与导出行数：" + lines[5001]);

        // 审计恰 1 行：action/detail（行数与截断标志）/actor（JWT 轨）
        AdminAuditLog audit = capturedAudit();
        assertEquals("order.export", audit.getAction());
        assertEquals("order", audit.getTargetType());
        assertEquals(ACTOR, audit.getActor());
        assertTrue(audit.getDetail().contains("\"rows\":5000"), "detail 记导出行数：" + audit.getDetail());
        assertTrue(audit.getDetail().contains("\"truncated\":true"), "超限导出 detail 必须带截断标志");
        assertTrue(audit.getDetail().contains("\"filters\":{}"), "orders 无筛选参数，filters 为空对象");
    }

    // ================================================================
    // ② settlements 导出
    // ================================================================

    @Test
    void settlements导出_表头与对账勾叉_审计恰1行含筛选() {
        when(settlementMapper.countSettlements(isNull(), isNull(), isNull())).thenReturn(2L);
        when(settlementMapper.selectSettlementsPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenReturn(List.of(settlement(1L, 1L, "20.00"), settlement(2L, 2L, "30.00")));
        when(merchantMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(merchant(1L, "商家一"), merchant(2L, "商家二")));
        // 单 2 的账面 gross(30.00) ≠ 实时挂锚 Σ(25.00) → 对账 ✗（照 Settlements.vue 同款符号）
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(1L, 2, 2, "20.00", 0), agg(2L, 3, 3, "25.00", 0)));

        byte[] body = adminService.exportSettlementsCsv(null, null, null, ACTOR);
        String text = csvText(body);

        assertEquals((byte) 0xEF, body[0]);
        assertTrue(text.startsWith("\uFEFF账单号,商家,账期起,账期止,gross,refund,net,佣金,明细数,状态,对账\r\n"),
                "首行必须是 BOM + 契约列集的中文表头（十一列）");
        assertTrue(text.contains("SET1,商家一,2026-10-01,2026-10-15,20.00,0.00,20.00,0.00,2,记账态,✓"),
                "对上账的行出 ✓：\n" + text);
        assertTrue(text.contains("SET2,商家二,2026-10-01,2026-10-15,30.00,0.00,30.00,0.00,3,记账态,✗"),
                "账面与实时 Σ 不符的行出 ✗");
        assertFalse(text.contains("true"), "对账列是 ✓/✗ 文本，不出 Boolean 字面量");

        // 审计恰 1 行：action/detail（行数与三个筛选条件）
        AdminAuditLog audit = capturedAudit();
        assertEquals("bill.export", audit.getAction());
        assertEquals("settlement", audit.getTargetType());
        assertEquals(ACTOR, audit.getActor());
        assertTrue(audit.getDetail().contains("\"rows\":2"), "detail 记导出行数：" + audit.getDetail());
        assertTrue(audit.getDetail().contains("\"truncated\":false"));
        assertTrue(audit.getDetail().contains("\"filters\":{\"merchantId\":null,\"dateStart\":null,\"dateEnd\":null}"),
                "detail 记筛选条件（本例全空）：" + audit.getDetail());
    }

    @Test
    void settlements导出_筛选三参透传_审计filters记归一后值() {
        when(settlementMapper.countSettlements(eq(1L), eq("2026-10-01"), eq("2026-10-31"))).thenReturn(1L);
        when(settlementMapper.selectSettlementsPage(eq(1L), eq("2026-10-01"), eq("2026-10-31"), anyLong(), anyInt()))
                .thenReturn(List.of(settlement(1L, 1L, "20.00")));
        when(merchantMapper.selectBatchIds(anyCollection())).thenReturn(List.of(merchant(1L, "商家一")));
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(1L, 2, 2, "20.00", 0)));

        adminService.exportSettlementsCsv(1L, "2026-10-01", "2026-10-31", ACTOR);

        // 筛选与 F-125 列表同参透传（闸门在 listSettlements 内，导出侧只做空白归 null）
        verify(settlementMapper).countSettlements(eq(1L), eq("2026-10-01"), eq("2026-10-31"));
        AdminAuditLog audit = capturedAudit();
        assertTrue(audit.getDetail().contains("\"filters\":{\"merchantId\":1,\"dateStart\":\"2026-10-01\",\"dateEnd\":\"2026-10-31\"}"),
                "detail 的 filters 记归一后筛选：" + audit.getDetail());
    }

    @Test
    void settlements导出_分页拉全_5000上限截断() {
        final long total = 5050L;
        when(settlementMapper.countSettlements(isNull(), isNull(), isNull())).thenReturn(total);
        when(settlementMapper.selectSettlementsPage(isNull(), isNull(), isNull(), anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    long offset = inv.getArgument(3);
                    int size = inv.getArgument(4);
                    List<Settlement> rows = new ArrayList<>();
                    for (long i = offset; i < Math.min(offset + size, total); i++) {
                        rows.add(settlement(i + 1, 1L, "20.00"));
                    }
                    return rows;
                });
        when(merchantMapper.selectBatchIds(anyCollection())).thenReturn(List.of(merchant(1L, "商家一")));
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        String text = csvText(adminService.exportSettlementsCsv(null, null, null, ACTOR));

        String[] lines = text.split("\r\n", -1);
        assertEquals(1 + 5000 + 1 + 1, lines.length, "表头+5000 数据行+尾注+尾空串");
        assertTrue(lines[5001].contains("5050") && lines[5001].contains("5000"),
                "尾注注明数据源总数与导出行数：" + lines[5001]);

        AdminAuditLog audit = capturedAudit();
        assertEquals("bill.export", audit.getAction());
        assertTrue(audit.getDetail().contains("\"rows\":5000"));
        assertTrue(audit.getDetail().contains("\"truncated\":true"));
    }
}
