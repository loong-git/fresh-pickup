package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.Dish;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Settlement;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.SettlementMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-120 商家端结算单只读视图 + 供货价展示单测（不起 Spring 容器、不落库；F-120 §4 三组逐条 + P5-2 §1 分页）：
 * ① 列表归属与分页：merchant_id 等值恒透传 mapper（桩的 eq 匹配器钉住，照 F-125 商家筛修桩先例）、
 *    出参恰为商家自见十三键子集（运营管理字段不下发），netAmount==payable、paidChannelAbsent 恒 true、
 *    reconcileOk 三条件照运营端口径（正常 true / 金额改 false / 孤儿 false / 聚合缺失 false）；
 *    分页闸门 400 族 / 全缺省封口四键无 filters / 越界页空 list 真值 total / ASC 页方法防误复用 DESC。
 * ② 明细归属：他人账单与不存在同 404「账单不存在」（不泄露存在性、不发明细查询）；
 *    本人账单明细行键与库一致、orphan 归一 Boolean、孤儿行 payStatus 显 null；
 * ③ 商品列表出参补 supplyPrice（NULL 与设值两态；@JsonIgnore 的 merchantId 仍封）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库归属过滤与 ASC/DESC 排序（mock 只能钉透传与页方法
 * 接线在位，真库语义归 verify 脚本）、Controller 限流/401/403（拦截器层，照 SettlementGenerateTest 先例不在本类）、
 * Spring 全局 ObjectMapper 的日期定形（测试注入 findAndRegisterModules 的裸 mapper，只断键不断时间形态）。
 */
class MerchantSettlementViewTest {

    private static final Long MERCHANT_ID = 1L;
    private static final Long OTHER_MERCHANT_ID = 2L;

    private final SettlementMapper settlementMapper = mock(SettlementMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);

    private MerchantSettlementServiceImpl settlementService;
    private MerchantDishServiceImpl dishService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 SettlementGenerateTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Settlement.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
        TableInfoHelper.initTableInfo(assistant, Dish.class);
    }

    @BeforeEach
    void setUp() {
        settlementService = new MerchantSettlementServiceImpl();
        ReflectionTestUtils.setField(settlementService, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(settlementService, "orderItemMapper", orderItemMapper);
        dishService = new MerchantDishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        // 只读路径不触达 merchantService/adminAuditLogMapper（写闸门与审计都在写路径），留空即可
        ReflectionTestUtils.setField(dishService, "objectMapper", new ObjectMapper().findAndRegisterModules());
    }

    // ---------------------------------------------------------------- fixtures

    /** 一行已生成账单（gmv=20、order_count=2、payable=20），merchantId 可换（归属用例） */
    private static Settlement storedSettlement(Long id, Long merchantId) {
        Settlement row = new Settlement();
        row.setId(id);
        row.setSettleNo("SET" + id);
        row.setMerchantId(merchantId);
        row.setPeriodStart(LocalDate.parse("2026-10-01"));
        row.setPeriodEnd(LocalDate.parse("2026-10-15"));
        row.setOrderCount(2);
        row.setGmvAmount(new BigDecimal("20.00"));
        row.setRefundAmount(BigDecimal.ZERO);
        row.setRetainAmount(BigDecimal.ZERO);
        row.setPayable(new BigDecimal("20.00"));
        row.setCommission(BigDecimal.ZERO);
        row.setStatus("accounted");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 16, 9, 30));
        return row;
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

    /** 正常明细行（SQL 侧 orphan 是 1/0 整数）与孤儿行（orders 行被删 → payStatus null、orphan=1） */
    private static Map<String, Object> anchoredRow(long itemId, String orderId, int orphan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", itemId);
        row.put("dishName", "测试菜");
        row.put("supplyPrice", new BigDecimal("10.00"));
        row.put("qty", 1);
        row.put("subtotal", new BigDecimal("10.00"));
        row.put("orderId", orderId);
        row.put("payStatus", orphan == 1 ? null : 1);
        row.put("orphan", orphan);
        row.put("fulfillStatus", "normal");
        return row;
    }

    private static Dish dish(Long id, BigDecimal supplyPrice) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("测试菜");
        d.setPrice(new BigDecimal("9.90"));
        d.setMerchantId(99L);   // 故意给一个值：商家出参不得因补列链路泄露归属键（@JsonIgnore 必须仍生效）
        d.setSupplyPrice(supplyPrice);
        d.setCreateTime(LocalDateTime.of(2026, 10, 1, 8, 0));
        return d;
    }

    // ================================================================
    // ① 列表归属与分页（F-120 §4-1 + P5-2 §1）
    // ================================================================

    /**
     * 分页化后取列表行的等价辅助（{@code list(merchantId)} 旧签名调用的判据等价迁移）：
     * 即 {@code (List) list(merchantId, null, null).get("list")}，对账四用例共用。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRows(Long merchantId) {
        return (List<Map<String, Object>>) settlementService.list(merchantId, null, null).get("list");
    }

    /** 列表页的通用桩：total=1（countSettlements 复用）+ ASC 页方法一行（eq 匹配器钉住 merchantId 透传） */
    private void stubListPageOneRow() {
        when(settlementMapper.countSettlements(eq(MERCHANT_ID), any(), any())).thenReturn(1L);
        when(settlementMapper.selectSettlementsPageAsc(eq(MERCHANT_ID), anyLong(), anyInt()))
                .thenReturn(List.of(storedSettlement(100L, MERCHANT_ID)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 列表归属_merchantId恒透传mapper_出参恰为商家自见十三键() {
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(100L, 2, 2, "20.00", 0)));

        Map<String, Object> out = settlementService.list(MERCHANT_ID, null, null);
        // 归属透传在位（mock 层可钉的一半）：total 与页查询都以本人 merchantId 命中桩（传 null/别值则桩不命中、list 落空）；
        // 且 total 复用 countSettlements、账期两参恒 null（商家侧无筛选维度）
        verify(settlementMapper).countSettlements(eq(MERCHANT_ID), isNull(), isNull());
        verify(settlementMapper).selectSettlementsPageAsc(eq(MERCHANT_ID), anyLong(), anyInt());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");

        // 出参=商家自见子集（F-120 §1 键清单），运营管理字段（merchantId/merchantName/gross/refund/net/retain/itemCount）不下发
        assertEquals(Set.of("id", "settleNo", "periodStart", "periodEnd", "gmvAmount", "refundAmount",
                        "netAmount", "orderCount", "commission", "status", "createdAt",
                        "reconcileOk", "paidChannelAbsent"),
                rows.get(0).keySet());
        assertEquals(100L, rows.get(0).get("id"));
        assertEquals("SET100", rows.get(0).get("settleNo"));
        assertEquals("2026-10-01", rows.get(0).get("periodStart"), "日期串服务端定形（D-18 同手势）");
        assertEquals("2026-10-15", rows.get(0).get("periodEnd"));
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) rows.get(0).get("gmvAmount")));
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) rows.get(0).get("netAmount")),
                "netAmount==payable（契约口径）");
        assertEquals("accounted", rows.get(0).get("status"));
        assertEquals("2026-10-16T09:30", rows.get(0).get("createdAt"), "createdAt 出参为服务端定形串");
        assertEquals(Boolean.TRUE, rows.get(0).get("paidChannelAbsent"), "记账态明示：打款通道未接恒 true");
    }

    @Test
    void 对账标志_正常单true() {
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(100L, 2, 2, "20.00", 0)));

        assertEquals(Boolean.TRUE, listRows(MERCHANT_ID).get(0).get("reconcileOk"),
                "gross 与 Σ 一致且计数一致且无孤儿 → true（三条件照运营端口径）");
    }

    @Test
    void 对账标志_账面金额被改false() {
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(100L, 2, 2, "15.00", 0)));

        assertEquals(Boolean.FALSE, listRows(MERCHANT_ID).get(0).get("reconcileOk"),
                "①打穿：账面 gross ≠ 实时挂锚 Σ");
    }

    @Test
    void 对账标志_孤儿明细false_金额订单数皆对仍打穿() {
        // 2026-10-25 运营端验收裁决的第三条件平行进商家端：孤儿态即数据事故，宁可错杀不可漏报
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList()))
                .thenReturn(List.of(agg(100L, 2, 2, "20.00", 1)));

        assertEquals(Boolean.FALSE, listRows(MERCHANT_ID).get(0).get("reconcileOk"),
                "③打穿：挂锚明细存在孤儿（orders 行被删）");
    }

    @Test
    void 对账标志_聚合行缺失false_照运营端宁可错杀() {
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        assertEquals(Boolean.FALSE, listRows(MERCHANT_ID).get(0).get("reconcileOk"));
    }

    // ================================================================
    // ①' 列表分页（P5-2 §1）：闸门 / 封口四键无 filters / 越界短路 / ASC 页方法
    // ================================================================

    @Test
    void 分页闸门_pageNum0与pageSize出界皆400_闸门先于查库() {
        assertEquals("请求参数有误，请检查后重试", assertThrows(BizException.class,
                () -> settlementService.list(MERCHANT_ID, 0, 20)).getMessage());
        assertEquals(400, assertThrows(BizException.class,
                () -> settlementService.list(MERCHANT_ID, 1, 0)).getCode());
        assertEquals(400, assertThrows(BizException.class,
                () -> settlementService.list(MERCHANT_ID, 1, 101)).getCode());
        // 闸门先于一切查库（400 的三种输入都不触 mapper）
        verify(settlementMapper, never()).countSettlements(any(), any(), any());
        verify(settlementMapper, never()).selectSettlementsPageAsc(any(), anyLong(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 全缺省_出参四键无filters_缺省页码1页容20() {
        stubListPageOneRow();
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        Map<String, Object> out = settlementService.list(MERCHANT_ID, null, null);
        assertEquals(Set.of("list", "total", "pageNum", "pageSize"), out.keySet(),
                "出参封口四键，无 filters（商家侧无筛选维度，契约 §1-4 明定）");
        assertEquals(1, out.get("pageNum"), "pageNum 缺省 1");
        assertEquals(20, out.get("pageSize"), "pageSize 缺省 20");
        assertEquals(1L, out.get("total"));
        assertEquals(1, ((List<Map<String, Object>>) out.get("list")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 越界页_offset不小于total_空list且total真值_不查页() {
        // offset=(2-1)*20=20 >= total=1 → List.of() 短路（照 F-125 运营端同款），total 仍真值
        when(settlementMapper.countSettlements(eq(MERCHANT_ID), any(), any())).thenReturn(1L);

        Map<String, Object> out = settlementService.list(MERCHANT_ID, 2, 20);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertTrue(rows.isEmpty(), "offset ≥ total 短路回空 list");
        assertEquals(1L, out.get("total"), "total 仍真值");
        assertEquals(2, out.get("pageNum"));
        verify(settlementMapper, never()).selectSettlementsPageAsc(any(), anyLong(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 列表页走ASC页方法_两行首id为1_不复用运营端DESC页方法() {
        // 两行 mock 按 id 1,2 返回（ASC 页方法的返回序）：出参 list 首行 id=1 防误接 selectSettlementsPage 的 DESC 序
        when(settlementMapper.countSettlements(eq(MERCHANT_ID), any(), any())).thenReturn(2L);
        when(settlementMapper.selectSettlementsPageAsc(eq(MERCHANT_ID), anyLong(), anyInt()))
                .thenReturn(List.of(storedSettlement(1L, MERCHANT_ID), storedSettlement(2L, MERCHANT_ID)));
        when(orderItemMapper.selectAnchoredAggregates(anyList())).thenReturn(List.of());

        Map<String, Object> out = settlementService.list(MERCHANT_ID, 1, 20);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("list");
        assertEquals(2, rows.size());
        assertEquals(1L, rows.get(0).get("id"), "首行 id=1：商家端 id 升序（F-120 §1 刻意差异）");
        assertEquals(2L, rows.get(1).get("id"));
        verify(settlementMapper).selectSettlementsPageAsc(eq(MERCHANT_ID), anyLong(), anyInt());
        verify(settlementMapper, never()).selectSettlementsPage(any(), any(), any(), anyLong(), anyInt());
    }

    // ================================================================
    // ② 明细归属（契约 §4-2）
    // ================================================================

    @Test
    void 明细归属_他人账单404同文案_不泄露存在性也不发明细查询() {
        when(settlementMapper.selectById(200L)).thenReturn(storedSettlement(200L, OTHER_MERCHANT_ID));

        BizException ex = assertThrows(BizException.class, () -> settlementService.items(MERCHANT_ID, 200L));
        assertEquals(404, ex.getCode());
        assertEquals("账单不存在", ex.getMessage());
        // 归属校验失败绝不下推明细查询：他人账单有没有明细，调用方一无所知
        verify(orderItemMapper, never()).selectAnchoredItems(any());
    }

    @Test
    void 明细归属_账单不存在同样404_两种成因不可区分() {
        when(settlementMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> settlementService.items(MERCHANT_ID, 999L));
        assertEquals(404, ex.getCode());
        assertEquals("账单不存在", ex.getMessage());
        verify(orderItemMapper, never()).selectAnchoredItems(any());
    }

    @Test
    void 本人账单明细_行键照库_orphan归一Boolean_孤儿payStatus显null() {
        when(settlementMapper.selectById(100L)).thenReturn(storedSettlement(100L, MERCHANT_ID));
        when(orderItemMapper.selectAnchoredItems(100L))
                .thenReturn(List.of(anchoredRow(11L, "O1001", 0), anchoredRow(12L, "O1002", 1)));

        List<Map<String, Object>> out = settlementService.items(MERCHANT_ID, 100L);

        assertEquals(2, out.size(), "孤儿行保留不静默丢行（照运营端 LEFT JOIN 口径）");
        // 正常行：九键（七项 + orphan 归一 Boolean + fulfillStatus），快照值逐键透传
        Map<String, Object> normal = out.get(0);
        assertEquals(Set.of("itemId", "dishName", "supplyPrice", "qty", "subtotal",
                "orderId", "payStatus", "orphan", "fulfillStatus"), normal.keySet());
        assertEquals(11L, normal.get("itemId"));
        assertEquals("测试菜", normal.get("dishName"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) normal.get("supplyPrice")));
        assertEquals("O1001", normal.get("orderId"));
        assertEquals(1, normal.get("payStatus"));
        assertEquals(Boolean.FALSE, normal.get("orphan"), "SQL 侧 0 归一为 false");
        assertEquals("normal", normal.get("fulfillStatus"));
        // 孤儿行：orphan=true、payStatus 显 null（页面据此标「订单已不存在」），其余快照键照常透传
        Map<String, Object> orphanRow = out.get(1);
        assertEquals(Boolean.TRUE, orphanRow.get("orphan"));
        assertNull(orphanRow.get("payStatus"), "orders 行已删，pay_status 无从取，显 null 不编值");
        assertEquals("O1002", orphanRow.get("orderId"));
        assertEquals("测试菜", orphanRow.get("dishName"));
    }

    // ================================================================
    // ③ 商品列表出参补 supplyPrice（契约 §4-3）
    // ================================================================

    @Test
    @SuppressWarnings("unchecked")
    void 商品列表出参_supplyPrice设值与NULL两态_merchantId仍封() {
        when(dishMapper.selectList(any()))
                .thenReturn(List.of(dish(11L, new BigDecimal("3.50")), dish(12L, null)));

        List<Map<String, Object>> out = dishService.listView(MERCHANT_ID, "all", null, null);

        assertEquals(2, out.size());
        // wrapper 仍走 list 同一条（merchant_id 恒第一条件）：listView 不是绕开归属的第二条读路径
        ArgumentCaptor<LambdaQueryWrapper<Dish>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(dishMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("merchant_id"),
                "listView 复用 list 的 wrapper，归属条件在位");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(MERCHANT_ID));

        // 设值态：supplyPrice 原样出（@JsonIgnore 封的是 C 端，商家列表按契约补回）
        assertEquals(0, new BigDecimal("3.50").compareTo((BigDecimal) out.get(0).get("supplyPrice")));
        // NULL 态：键在、值 null（前端据此显「—」），不是键缺失（那会被读成「后端没回」）
        assertTrue(out.get(1).containsKey("supplyPrice"), "NULL 原样：键存在值为 null");
        assertNull(out.get(1).get("supplyPrice"));
        // 补列链路不得破 C 端封口：merchantId 的 @JsonIgnore 在转换侧同样生效
        assertFalse(out.get(0).containsKey("merchantId"), "@JsonIgnore 的 merchantId 不因 Map 铺开而泄露");
    }
}
