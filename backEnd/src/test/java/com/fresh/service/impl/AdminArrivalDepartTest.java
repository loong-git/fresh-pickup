package com.fresh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.dto.AdminArrivalDepartDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.ArrivalBatch;
import com.fresh.entity.ArrivalItem;
import com.fresh.entity.Store;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.ArrivalItemMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.StoreMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-63 运营端「发车」契约单测（不起 Spring 容器、不落库、绝不写库）。
 *
 * <p><b>要证明的七件事</b>：
 * ① 判据顺序钉死：过去日期与非法形状的 400 发生在<b>一切查库之前</b>（{@code verify(storeMapper, never())} 钉住），
 *    门店 404 拦在批次查询之前，confirmed/short 的 400 拦在汇总查询之前——404 与 400 永远不抢先后；
 * ② 无批次建 {@code arriving}（决策点 1，不提供 planned 入口），明细走「先删 + 一条批量插」两步同形；
 * ③ 已有 arriving 批次<b>幂等重算</b>：不再建批次、明细挂原 batchId 重灌、出参与审计的 idempotent=true；
 * ④ 翻态 0 行（并发窗口被主理人确认）与撞 uk_store_date（并发抢插）都如实 400，不吞、不越权覆盖；
 * ⑤ 空车 400 不建空批次；confirmed 分支<b>库与审计都不动</b>；
 * ⑥ 审计四元组与 detail 四键定形（store/date/items/idempotent 合法 JSON、远小于列宽 500）；
 * ⑦ 预览的 departable 白名单（无批次或 planned/arriving）与 existing.itemCount 取应发件数口径。
 *
 * <p><b>本类测不成什么（免得留下一张假收据）</b>：mapper 是 mock，所以「按菜汇总与商家备货单
 * 逐菜相等」（F-63 的存在理由，一致性硬断言）与「SQL 里的状态白名单真的只放 planned/arriving」
 * 都不在本类证明力之内——前者由真库对账收口（基准值已归档在 logs/out/f63/），
 * 后者由跨端闭环实测收口（发车 → 批次页「到货中」→ 确认 → C 端「已到店，去拿货」）。
 * 另外 {@code BaseMapper#insert} 的自增 id 回填是 MP 运行期行为，本类用打桩模拟——
 * 真实回填链路不在单测覆盖面里。
 */
class AdminArrivalDepartTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** 发车判据第②步用「今天」做比较，用例日期必须跟着系统日走，不能写死 */
    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    private AdminServiceImpl newService(StoreMapper storeMapper, OrderItemMapper orderItemMapper,
                                        ArrivalBatchMapper batchMapper, ArrivalItemMapper arrivalItemMapper,
                                        AdminAuditLogMapper auditMapper) throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        inject(service, "storeMapper", storeMapper);
        inject(service, "orderItemMapper", orderItemMapper);
        inject(service, "arrivalBatchMapper", batchMapper);
        inject(service, "arrivalItemMapper", arrivalItemMapper);
        inject(service, "adminAuditLogMapper", auditMapper);
        inject(service, "adminKey", "test-admin-key");
        return service;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static AdminArrivalDepartDTO departDto(Long storeId, String date, String remark) {
        AdminArrivalDepartDTO dto = new AdminArrivalDepartDTO();
        dto.setStoreId(storeId);
        dto.setBatchDate(date);
        dto.setRemark(remark);
        return dto;
    }

    private static Store store() {
        Store s = new Store();
        s.setId(1L);
        s.setName("测试自提点");
        return s;
    }

    /** 真库回法：SUM(quantity) 是 BigDecimal，绝不自创 Integer（本打桩就是在钉这个转换） */
    private static Map<String, Object> planRow(Long dishId, String dishName, String sumQuantity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dishId", dishId);
        row.put("dishName", dishName);
        row.put("quantity", new BigDecimal(sumQuantity));
        return row;
    }

    private static Map<String, Object> batchRow(Long id, String status, LocalDateTime confirmTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("status", status);
        row.put("confirmTime", confirmTime);
        return row;
    }

    private AdminAuditLog captureAudit(AdminAuditLogMapper auditMapper) {
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(cap.capture());
        return cap.getValue();
    }

    /** 两条汇总查询的打桩：两道菜 5+3 件、店级去重 2 单（与 logs/out/f63 真库基准同形） */
    private void stubPlan(OrderItemMapper orderItemMapper) {
        when(orderItemMapper.selectArrivalPlanByStore(any(LocalDate.class), any()))
                .thenReturn(List.of(planRow(44L, "土鸡蛋", "5"), planRow(45L, "黄瓜", "3")));
        when(orderItemMapper.countArrivalOrdersForStore(any(LocalDate.class), any())).thenReturn(2L);
    }

    // ---------------------------------------------------------------- 判据顺序

    @Test
    void 过去日期判四百且一次库都不碰() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                mock(ArrivalItemMapper.class), auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, YESTERDAY.toString(), null), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("批次日期不能早于今天", e.getMessage());
        // 契约钉死「日期早于今天」发生在查库之前：门店查询一次都不该被调用
        verify(storeMapper, never()).selectById(any());
        verify(orderItemMapper, never()).selectArrivalPlanByStore(any(LocalDate.class), any());
        verify(batchMapper, never()).selectBatchesByStoreAndDate(any(), any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 非法日期形状判四百且一次库都不碰() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        AdminServiceImpl service = newService(storeMapper, mock(OrderItemMapper.class),
                mock(ArrivalBatchMapper.class), mock(ArrivalItemMapper.class),
                mock(AdminAuditLogMapper.class));

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, "2026-10-1", null), "admin_user:2"));

        assertEquals(400, e.getCode());
        // 脏参文案逐字复用既有那句，不新增句子（RF）
        assertEquals("请求参数有误，请检查后重试", e.getMessage());
        verify(storeMapper, never()).selectById(any());
    }

    @Test
    void 门店不存在判四百零四且不再查批次与订单() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(null);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                mock(ArrivalItemMapper.class), auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, TOMORROW.toString(), null), "admin_user:2"));

        assertEquals(404, e.getCode());
        assertEquals("门店不存在", e.getMessage());
        verify(batchMapper, never()).selectBatchesByStoreAndDate(any(), any());
        verify(orderItemMapper, never()).selectArrivalPlanByStore(any(LocalDate.class), any());
        verify(auditMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- 状态机四支

    @Test
    void 已有批次已确认时拒四百且库与审计都不动() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW))
                .thenReturn(List.of(batchRow(13L, "confirmed", LocalDateTime.now())));
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, TOMORROW.toString(), null), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该店该日货已收货或已登记短少，不可再发车", e.getMessage());
        // 判据第④步先于第⑤步：confirmed 分支连汇总查询都不该发（货已收，再算应发数是造假的前奏）
        verify(orderItemMapper, never()).selectArrivalPlanByStore(any(LocalDate.class), any());
        verify(batchMapper, never()).insert(any());
        verify(batchMapper, never()).markArriving(any());
        verify(arrivalItemMapper, never()).deleteByBatchId(any());
        verify(arrivalItemMapper, never()).insertBatchItems(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 空车拒四百且不建空批次() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW)).thenReturn(List.of());
        when(orderItemMapper.selectArrivalPlanByStore(TOMORROW, 1L)).thenReturn(List.of());
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, TOMORROW.toString(), null), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该店该日没有可发车的订单", e.getMessage());
        // 汇总为空的短路在店级单数查询之前：不为一笔发不出去的单多烧一条 COUNT
        verify(orderItemMapper, never()).countArrivalOrdersForStore(any(LocalDate.class), any());
        verify(batchMapper, never()).insert(any());
        verify(arrivalItemMapper, never()).deleteByBatchId(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 无批次时建arriving并按先删后批量插灌明细() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW)).thenReturn(List.of());
        stubPlan(orderItemMapper);
        when(batchMapper.insert(any(ArrivalBatch.class))).thenAnswer(inv -> {
            inv.getArgument(0, ArrivalBatch.class).setId(14L);   // 模拟 MP 自增 id 回填
            return 1;
        });
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        Map<String, Object> out = service.departArrival(
                departDto(1L, TOMORROW.toString(), "  明早六点送到  "), "admin_user:2");

        ArgumentCaptor<ArrivalBatch> batchCap = ArgumentCaptor.forClass(ArrivalBatch.class);
        verify(batchMapper).insert(batchCap.capture());
        ArrivalBatch saved = batchCap.getValue();
        assertEquals("arriving", saved.getStatus(), "发车直达 arriving，不提供 planned 入口（决策点 1）");
        assertEquals(Long.valueOf(1L), saved.getStoreId());
        assertEquals(TOMORROW, saved.getBatchDate());
        assertEquals("明早六点送到", saved.getRemark(), "remark 要 trim 后入库");
        assertNull(saved.getConfirmTime());
        // 新建批次也走「先删后插」同一形状（删 0 行无害），翻态 SQL 不参与无批次路径
        verify(batchMapper, never()).markArriving(any());
        verify(arrivalItemMapper).deleteByBatchId(14L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArrivalItem>> itemCap = ArgumentCaptor.forClass(List.class);
        verify(arrivalItemMapper).insertBatchItems(itemCap.capture());
        List<ArrivalItem> items = itemCap.getValue();
        assertEquals(2, items.size());
        assertEquals(Long.valueOf(14L), items.get(0).getBatchId());
        assertEquals(Long.valueOf(44L), items.get(0).getDishId());
        assertEquals(Integer.valueOf(5), items.get(0).getExpectedQty(),
                "BigDecimal 的 SUM 必须被显式转成 Integer 入库（打桩就是按真库回法给的）");
        assertEquals(Integer.valueOf(3), items.get(1).getExpectedQty());

        assertEquals(14L, out.get("batchId"));
        assertEquals("arriving", out.get("status"));
        assertEquals(8, out.get("itemCount"), "itemCount=Σ件数（5+3），不是明细行数");
        assertEquals(2, out.get("dishKinds"));
        assertEquals(2L, out.get("orderCount"), "店级去重单数取孪生条，不是按菜行的加总");
        assertEquals(Boolean.FALSE, out.get("idempotent"));

        AdminAuditLog row = captureAudit(auditMapper);
        assertEquals("admin_user:2", row.getActor());
        assertEquals("arrival.depart", row.getAction());
        assertEquals("arrival_batch", row.getTargetType());
        assertEquals("14", row.getTargetId());
        JsonNode detail = mapper.readTree(row.getDetail());
        assertEquals(1L, detail.get("store").asLong());
        assertEquals(TOMORROW.toString(), detail.get("date").asText());
        assertEquals(8, detail.get("items").asInt());
        assertEquals(false, detail.get("idempotent").asBoolean());
        assertEquals(4, detail.size());
        assertEquals("{\"store\":1,\"date\":\"" + TOMORROW + "\",\"items\":8,\"idempotent\":false}",
                row.getDetail());
    }

    @Test
    void 已有arriving批次幂等重算不重建批次且报true() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW))
                .thenReturn(List.of(batchRow(13L, "arriving", null)));
        when(batchMapper.markArriving(13L)).thenReturn(1);   // 同值重写 matched rows 回 1，不算失败
        stubPlan(orderItemMapper);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        Map<String, Object> out = service.departArrival(
                departDto(1L, TOMORROW.toString(), null), null);

        verify(batchMapper, never()).insert(any());
        verify(arrivalItemMapper).deleteByBatchId(13L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ArrivalItem>> itemCap = ArgumentCaptor.forClass(List.class);
        verify(arrivalItemMapper).insertBatchItems(itemCap.capture());
        for (ArrivalItem item : itemCap.getValue()) {
            assertEquals(Long.valueOf(13L), item.getBatchId(), "重算明细必须挂回原批次，不产生孤儿行");
        }

        assertEquals(13L, out.get("batchId"));
        assertEquals(Boolean.TRUE, out.get("idempotent"));
        // 静态密钥轨：actor 落 admin:<摘要8>，绝不含密钥明文
        AdminAuditLog row = captureAudit(auditMapper);
        assertEquals("13", row.getTargetId());
        JsonNode detail = mapper.readTree(row.getDetail());
        assertEquals(true, detail.get("idempotent").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(row.getActor().startsWith("admin:"));
        org.junit.jupiter.api.Assertions.assertFalse(row.getActor().contains("test-admin-key"));
    }

    @Test
    void 翻态零行说明并发已被确认到货按四百拒() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        // 第④步读到 planned（白名单内、放行），翻态时已被主理人确认 → 条件 UPDATE 0 行
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW))
                .thenReturn(List.of(batchRow(13L, "planned", null)));
        when(batchMapper.markArriving(13L)).thenReturn(0);
        stubPlan(orderItemMapper);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, TOMORROW.toString(), null), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该店该日货已收货或已登记短少，不可再发车", e.getMessage());
        verify(arrivalItemMapper, never()).deleteByBatchId(any());
        verify(arrivalItemMapper, never()).insertBatchItems(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 并发抢插撞唯一键按四百刷新重试不吞异常() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW)).thenReturn(List.of());
        stubPlan(orderItemMapper);
        when(batchMapper.insert(any(ArrivalBatch.class)))
                .thenThrow(new DuplicateKeyException("uk_store_date"));
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.departArrival(departDto(1L, TOMORROW.toString(), null), "admin_user:2"));

        assertEquals(400, e.getCode());
        assertEquals("该店该日已有批次，请刷新后重试", e.getMessage());
        verify(arrivalItemMapper, never()).deleteByBatchId(any());
        verify(auditMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------- 只读预览

    @Test
    void 预览门店不存在判四百零四() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        when(storeMapper.selectById(9L)).thenReturn(null);
        AdminServiceImpl service = newService(storeMapper, mock(OrderItemMapper.class),
                mock(ArrivalBatchMapper.class), mock(ArrivalItemMapper.class),
                mock(AdminAuditLogMapper.class));

        BizException e = assertThrows(BizException.class, () -> service.arrivalPreview(9L, TOMORROW.toString()));

        assertEquals(404, e.getCode());
        assertEquals("门店不存在", e.getMessage());
    }

    @Test
    void 预览无批次时departable为true且汇总与店级单数各取各的查询() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW)).thenReturn(List.of());
        stubPlan(orderItemMapper);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                mock(ArrivalItemMapper.class), auditMapper);

        Map<String, Object> out = service.arrivalPreview(1L, TOMORROW.toString());

        assertEquals(1L, out.get("storeId"));
        assertEquals("测试自提点", out.get("storeName"));
        assertEquals(TOMORROW.toString(), out.get("date"));
        assertNull(out.get("existing"), "无既有批次时 existing 是显式 null（键必在）");
        assertEquals(Boolean.TRUE, out.get("departable"));
        assertEquals(2, out.get("dishKinds"));
        assertEquals(2L, out.get("orderCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plan = (List<Map<String, Object>>) out.get("plan");
        assertEquals(2, plan.size());
        assertEquals(44L, plan.get(0).get("dishId"));
        assertEquals(5, plan.get(0).get("qty"), "qty 是 Σ件数（BigDecimal 显式转 Integer）");
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 预览过去日期时departable为false即使一条批次都没有() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        String yesterday = LocalDate.now().minusDays(1).toString();
        when(storeMapper.selectById(1L)).thenReturn(store());
        when(batchMapper.selectBatchesByStoreAndDate(1L, LocalDate.parse(yesterday))).thenReturn(List.of());
        stubPlan(orderItemMapper);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                mock(ArrivalItemMapper.class), mock(AdminAuditLogMapper.class));

        Map<String, Object> out = service.arrivalPreview(1L, yesterday);

        assertEquals(Boolean.FALSE, out.get("departable"),
                "预览的 departable 必须与发车的拒判据同集合：漏了日期这一维就会「预览说可发车、点下去回 400」");
    }

    @Test
    void 预览已确认批次时departable为false且itemCount取应发件数() throws Exception {
        StoreMapper storeMapper = mock(StoreMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        ArrivalItemMapper arrivalItemMapper = mock(ArrivalItemMapper.class);
        when(storeMapper.selectById(1L)).thenReturn(store());
        LocalDateTime confirmTime = LocalDateTime.of(2026, 10, 17, 8, 30);
        when(batchMapper.selectBatchesByStoreAndDate(1L, TOMORROW))
                .thenReturn(List.of(batchRow(13L, "confirmed", confirmTime)));
        when(arrivalItemMapper.sumExpectedQtyByBatchId(13L)).thenReturn(5L);
        when(orderItemMapper.selectArrivalPlanByStore(TOMORROW, 1L)).thenReturn(List.of());
        when(orderItemMapper.countArrivalOrdersForStore(TOMORROW, 1L)).thenReturn(0L);
        AdminServiceImpl service = newService(storeMapper, orderItemMapper, batchMapper,
                arrivalItemMapper, mock(AdminAuditLogMapper.class));

        Map<String, Object> out = service.arrivalPreview(1L, TOMORROW.toString());

        assertEquals(Boolean.FALSE, out.get("departable"), "confirmed 不在 planned/arriving 白名单（决策点 3）");
        @SuppressWarnings("unchecked")
        Map<String, Object> existing = (Map<String, Object>) out.get("existing");
        assertEquals(13L, existing.get("id"));
        assertEquals("confirmed", existing.get("status"));
        assertEquals(confirmTime, existing.get("confirmTime"));
        assertEquals(5L, existing.get("itemCount"), "existing.itemCount=Σ expected_qty（件数口径，与发车出参同源）");
        verify(batchMapper, never()).insert(any());
        verify(arrivalItemMapper, never()).deleteByBatchId(any());
    }
}
