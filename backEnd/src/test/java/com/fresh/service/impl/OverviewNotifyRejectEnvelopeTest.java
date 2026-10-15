package com.fresh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.R;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-50 大盘信封契约单测（不起 Spring 容器、不落库、绝不写库）：
 * {@code GET /api/admin/v2/overview} 的 {@code data} 从四键变 <b>恰六键</b>，
 * 新增的 {@code notifyRejectCount} / {@code notifyRejectLastAt} 两键<b>必须恒在</b>，
 * 当日无拒收时后者是<b>显式 null</b> 而不是缺键（F-42b 信封纪律：缺键与显式 null 对客户端是两种语义，
 * 所以这里一律用 {@code containsKey} 断言，不用「值非 null」代替——那会把两件事混成一谈）。
 *
 * <p><b>本类能测成什么、测不成什么（先把话说清，免得留下一张假收据）</b>：
 * mapper 是 mock，所以 {@code IFNULL(#{day}, CURDATE())} 那对<b>半开区间边界的活体行为不在本类的证明力之内</b>
 * （插两行边界数据、验完删的活体证明由主会话在 fresh_db 做）。
 * 本类只断言两件事：① Service 层的<b>取值与信封形状</b>（键集合、null 语义、Long→Integer 归一、HH:mm 串原样透传）；
 * ② <b>同一个 day 交给了两条 SQL</b>——那是 D-50-3「窗口与其余四键逐字同源」在 Java 侧的那一半
 *（另一半在 SQL 文本里，见 {@link AdminAuditLogMapper#selectNotifyRejectSummary}，与
 * {@code OrderMapper#selectOverviewDaily} 同形）。这条用 ArgumentCaptor 真抓参数，不是摆设。
 *
 * <p>第三组断言盯 D-50-2：{@code pay.notify_reject} 这串字面量在 Java 侧只有一个出口
 *（{@link AdminAuditLogMapper#ACTION_PAY_NOTIFY_REJECT}），写侧 {@code AdminServiceImpl} 引用的就是它。
 * 配套的「全仓 grep 只剩 1 行」由回报贴原文证明，本类只保证两枚常量同源。
 */
class OverviewNotifyRejectEnvelopeTest {

    /** 大盘出参的六键与键序（前四键 = F-20.9 #2 既有顺序，后两键 = F-50 追加） */
    private static final List<String> DATA_KEYS = List.of(
            "orderCount", "totalAmount", "pendingPickupCount", "paidAmount",
            "notifyRejectCount", "notifyRejectLastAt");

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** 字段注入的 @Autowired 依赖在纯单测里手工塞入（无容器）；overview 只用到两枚 mapper，其余三枚留 null 不碰 */
    private AdminV2ServiceImpl newService(OrderMapper orderMapper, AdminAuditLogMapper auditMapper) throws Exception {
        AdminV2ServiceImpl service = new AdminV2ServiceImpl();
        inject(service, "orderMapper", orderMapper);
        inject(service, "adminAuditLogMapper", auditMapper);
        return service;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 大盘前四键的固定回包（金额按 SQL 实况给 BigDecimal、计数按 MySQL 实况给 Long） */
    private static Map<String, Object> orderAgg() {
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("orderCount", 1L);
        agg.put("totalAmount", new BigDecimal("577.4"));
        agg.put("pendingPickupCount", 2L);
        agg.put("paidAmount", new BigDecimal("100"));
        return agg;
    }

    /**
     * 判据本体：data 恰六键、键序即契约顺序，且两枚新键 containsKey 成立。
     * 「值非 null」不许代替 containsKey——所以这里先比键集合，再单独点验两枚键在不在。
     */
    private static void assertSixKeyEnvelope(Map<String, Object> data) {
        assertEquals(DATA_KEYS, new ArrayList<>(data.keySet()), "overview 的 data 应恰为六键且键序不变");
        assertTrue(data.containsKey("notifyRejectCount"), "notifyRejectCount 键必须存在");
        assertTrue(data.containsKey("notifyRejectLastAt"), "notifyRejectLastAt 键必须存在（值可以为 null，键不行）");
    }

    @Test
    void overviewCarriesExactlySixKeysWithRejectCountAndLastTime() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("rejectCount", 3L);
        reject.put("lastAtText", "09:41");
        when(auditMapper.selectNotifyRejectSummary(any(), any())).thenReturn(reject);

        Map<String, Object> data = newService(orderMapper, auditMapper).overview(null);

        assertSixKeyEnvelope(data);
        // 前四键的形状与取值一律未动（金额仍是两位小数字符串、计数仍是 Integer）
        assertEquals(1, data.get("orderCount"));
        assertEquals("577.40", data.get("totalAmount"));
        assertEquals(2, data.get("pendingPickupCount"));
        assertEquals("100.00", data.get("paidAmount"));
        // COUNT 在 MySQL 回 Long：走既有 count() 归一成 Integer，不许把 Long 原样吐给前端
        assertEquals(3, data.get("notifyRejectCount"));
        assertInstanceOf(Integer.class, data.get("notifyRejectCount"));
        // HH:mm 由后端 DATE_FORMAT 定好，Service 原样透传，不做二次格式化（D-50-7）
        assertEquals("09:41", data.get("notifyRejectLastAt"));
    }

    /**
     * count=0 支·形态一：mapper 把 lastAtText 显式给成 null。
     * 这一支的判据是「键在 + 值为 null」<b>同时</b>成立，只验其中一个都算没验。
     */
    @Test
    void zeroRejectDayKeepsLastAtKeyAsExplicitNull() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        Map<String, Object> reject = new LinkedHashMap<>();
        reject.put("rejectCount", 0L);
        reject.put("lastAtText", null);
        when(auditMapper.selectNotifyRejectSummary(any(), any())).thenReturn(reject);

        Map<String, Object> data = newService(orderMapper, auditMapper).overview(null);

        assertSixKeyEnvelope(data);
        assertEquals(0, data.get("notifyRejectCount"));
        assertTrue(data.containsKey("notifyRejectLastAt"), "当日无拒收时这一键仍必须在");
        assertNull(data.get("notifyRejectLastAt"), "当日无拒收时这一键的值必须是显式 null");
    }

    /**
     * count=0 支·形态二（<b>本仓实况的那一条</b>）：MyBatis 对 Map 结果默认不写 null 键
     *（callSettersOnNulls=false，见 AdminV2ServiceImpl#auditLogs 的同款注释），
     * MAX(created_at) 为 NULL 时 SQL 行里<b>根本没有 lastAtText 这个键</b>。
     * Service 侧仍须把它补成显式 null——这一支才是「缺键不会被误当值非 null 蒙混过去」的真正防线。
     */
    @Test
    void missingLastAtTextKeyFromMybatisIsNormalizedToExplicitNullKey() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        when(auditMapper.selectNotifyRejectSummary(any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("rejectCount", 0L)));

        Map<String, Object> data = newService(orderMapper, auditMapper).overview("2026-10-15");

        assertSixKeyEnvelope(data);
        assertEquals(0, data.get("notifyRejectCount"));
        assertTrue(data.containsKey("notifyRejectLastAt"), "SQL 行缺键时出参仍要补出这一键");
        assertNull(data.get("notifyRejectLastAt"));
    }

    /**
     * mapper 整行取不到（防御支，理论上无 GROUP BY 的聚合恒回 1 行、走不到）时，
     * 两枚新键仍须在且退化成 0 / null——出参形状不许随取数结果浮动。
     */
    @Test
    void nullRowFromMapperStillYieldsSixKeyEnvelope() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        when(auditMapper.selectNotifyRejectSummary(any(), any())).thenReturn(null);

        Map<String, Object> data = newService(orderMapper, auditMapper).overview(null);

        assertSixKeyEnvelope(data);
        assertEquals(0, data.get("notifyRejectCount"));
        assertNull(data.get("notifyRejectLastAt"));
    }

    /**
     * D-50-3 在 Java 侧的那一半：拒收计数与四个订单指标拿的是<b>同一个</b> day 参数——
     * 传日期时两边同那一天，不传日期时两边同为 null（由 SQL 侧 CURDATE() 裁决今日，Java 不取时钟）。
     * 用 ArgumentCaptor 真抓两条 SQL 的入参，不是照着 mock 编一句「看起来像 SQL」的断言。
     */
    @Test
    void bothSqlsReceiveExactlyTheSameWindowArgument() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        when(auditMapper.selectNotifyRejectSummary(any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("rejectCount", 1L)));

        Map<String, Object> given = newService(orderMapper, auditMapper).overview("2026-03-05");
        assertEquals(6, given.size(), "带 date 的分支同样必须六键");

        ArgumentCaptor<LocalDate> orderDay = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> rejectDay = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> actionArg = ArgumentCaptor.forClass(String.class);
        verify(orderMapper).selectOverviewDaily(orderDay.capture());
        verify(auditMapper).selectNotifyRejectSummary(actionArg.capture(), rejectDay.capture());
        assertEquals(LocalDate.of(2026, 3, 5), orderDay.getValue(), "订单聚合按选定自然日");
        assertEquals(orderDay.getValue(), rejectDay.getValue(), "拒收计数与订单指标必须同一个 day（同窗口口径）");
        assertEquals(AdminAuditLogMapper.ACTION_PAY_NOTIFY_REJECT, actionArg.getValue(),
                "拒收计数的 action 绑定值就是那唯一一枚常量");
    }

    /** date 缺省（未传）那一支：两条 SQL 都收 null，「今日」继续由 SQL 侧 CURDATE() 决定 */
    @Test
    void absentDatePassesNullDayToBothQueriesSoSqlSideDecidesToday() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        when(auditMapper.selectNotifyRejectSummary(any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("rejectCount", 0L)));

        newService(orderMapper, auditMapper).overview("   ");

        ArgumentCaptor<LocalDate> orderDay = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> rejectDay = ArgumentCaptor.forClass(LocalDate.class);
        verify(orderMapper).selectOverviewDaily(orderDay.capture());
        verify(auditMapper).selectNotifyRejectSummary(any(), rejectDay.capture());
        assertNull(orderDay.getValue(), "未传日期时订单聚合绑 null");
        assertNull(rejectDay.getValue(), "未传日期时拒收计数绑同一个 null（同一条 IFNULL 退回 CURDATE()）");
    }

    /**
     * D-50-2：字面量只有一个出口——读侧引用的那枚常量，必须与写侧 {@code AdminServiceImpl#recordNotifyReject}
     * 用的那枚 {@code NOTIFY_REJECT_ACTION} 同源（写侧字段是 private，用反射取值，不改其可见性）。
     */
    @Test
    void notifyRejectActionLiteralHasASingleSource() throws Exception {
        Field written = AdminServiceImpl.class.getDeclaredField("NOTIFY_REJECT_ACTION");
        written.setAccessible(true);
        Object actionUsedByWriter = written.get(null);

        assertEquals("pay.notify_reject", AdminAuditLogMapper.ACTION_PAY_NOTIFY_REJECT,
                "常量值自 F-44 起一字未变，本次只搬出处");
        assertEquals(AdminAuditLogMapper.ACTION_PAY_NOTIFY_REJECT, actionUsedByWriter,
                "写侧与读侧必须引用同一枚字面量，不许各写一遍");
    }

    /**
     * 信封级判据：两枚新键要活到序列化的 JSON 里，当日无拒收那一支必须是 {@code "notifyRejectLastAt":null}
     *（与 F-42b 的 {@code data:null} 同一形态），而不是整键消失。
     */
    @Test
    void serializedEnvelopeKeepsExplicitNullForEmptyRejectDay() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(orderMapper.selectOverviewDaily(any())).thenReturn(orderAgg());
        when(auditMapper.selectNotifyRejectSummary(any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("rejectCount", 0L)));

        R<Map<String, Object>> body = R.ok(newService(orderMapper, auditMapper).overview(null));
        String json = mapper.writeValueAsString(body);

        assertTrue(json.contains("\"notifyRejectCount\":0"), "序列化后的 data 必须带拒收计数：" + json);
        assertTrue(json.contains("\"notifyRejectLastAt\":null"),
                "当日无拒收时序列化的必须是显式 null 键，不是整键消失：" + json);
    }

    /**
     * 负对照（证明上面的键集合判据不是橡皮章）：改前那种只有四键的 data 喂进同一个判据，
     * 必须被 {@code containsKey} 判成不合格。若这一条反过来成立，说明前面几测毫无鉴别力。
     */
    @Test
    void preChangeFourKeyMapIsRejectedByTheSamePredicate() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("orderCount", 1);
        before.put("totalAmount", "577.40");
        before.put("pendingPickupCount", 2);
        before.put("paidAmount", "100.00");

        assertFalse(before.containsKey("notifyRejectCount"), "四键形态本来就没有这一键，判据必须能识别");
        assertFalse(before.containsKey("notifyRejectLastAt"));
        assertEquals(DATA_KEYS.subList(0, 4), new ArrayList<>(before.keySet()));
        assertFalse(DATA_KEYS.equals(new ArrayList<>(before.keySet())), "改前的四键不该被判成六键");
    }
}
