package com.fresh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.entity.Merchant;
import com.fresh.entity.Order;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-62「货已到点」派生契约单测（不起 Spring 容器、不落库、绝不写库）。
 *
 * <p><b>要证明的六件事</b>：
 * ① 命中「自提点ID 与 批次日期 拼成的键」的单被标 {@code true}，未命中的标<b>显式 {@code false}</b>（不是漏填）；
 * ② 只有「{@code pending_pickup} + 已支付 + {@code store_id} 非空」三类条件同时成立的单参与判定，
 *    已完成/已取消/未支付/无自提点一律留 {@code null}；
 * ③ 页内无一可判定时<b>一次批次查询都不发</b>（省掉的是真 SQL，可被 {@code verify(never())} 钉死）；
 * ④ 送进两枚 IN 的集合<b>去重</b>且不含 null（null 参与 IN 会让整个条件永不成立且不报错，属最难查的静默失效）；
 * ⑤ Java 侧拼键的字面格式被钉成 {@code 1#2026-10-17}：正向由 ①、反向由「异型格式一律 miss」那条共同夹住；
 * ⑥ 未赋值的行序列化出来是 {@code "goodsArrived":null} 而非键缺失——本仓没有任何 jackson 包含策略配置，
 *    这条就是前端判据必须写 {@code === true} 的由来。
 *
 * <p><b>本类测不成什么（免得留下一张假收据）</b>：mapper 是 mock，所以
 * 「SQL 里 {@code status='confirmed'} 真的排掉了 planned/arriving/short」与
 * 「MySQL 侧 {@code CONCAT(store_id,'#',batch_date)} 的输出与 Java 侧逐字相等」两件事不在证明力之内
 * ——后者尤其不能靠本类自证，因为本类的键是测试自己写的。
 * 这两条由 {@code logs/verify-f62.cjs} 用真库真接口双向收口：批次 confirmed 时出参 true，
 * 改成 planned 时出参 false，用完把原值写回。
 */
class OrderGoodsArrivedFlagTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private OrderServiceImpl newService(OrderMapper orderMapper, OrderItemMapper itemMapper,
                                        ArrivalBatchMapper batchMapper) throws Exception {
        OrderServiceImpl service = new OrderServiceImpl();
        inject(service, "orderMapper", orderMapper);
        inject(service, "orderItemMapper", itemMapper);
        inject(service, "dishMapper", mock(DishMapper.class));
        inject(service, "merchantMapper", activeMerchantMapper()); // F-122：本类不走下单链路，仍补齐新增依赖防 NPE
        inject(service, "storeMapper", mock(StoreMapper.class));
        inject(service, "couponService", mock(CouponService.class));
        inject(service, "arrivalBatchMapper", batchMapper);
        return service;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** F-122 新增依赖：默认商家 active，保持既有用例（不涉商家状态）的行为不变 */
    private static MerchantMapper activeMerchantMapper() {
        MerchantMapper merchantMapper = mock(MerchantMapper.class);
        Merchant active = new Merchant();
        active.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(active);
        return merchantMapper;
    }

    private static Order order(String id, String status, Integer payStatus,
                               Long storeId, String pickupDate) {
        Order o = new Order();
        o.setId(id);
        o.setStatus(status);
        o.setPayStatus(payStatus);
        o.setStoreId(storeId);
        o.setPickupDate(pickupDate == null ? null : LocalDate.parse(pickupDate));
        o.setPhone("13800001234");
        return o;
    }

    /** 一条可参与判定的单：待自提 + 已支付 + 有自提点 */
    private static Order payableOrder(String id, Long storeId, String pickupDate) {
        return order(id, OrderServiceImpl.STATUS_PENDING_PICKUP, 1, storeId, pickupDate);
    }

    /** 打桩读路径：count 回 1（保证不被空列表短路），列表回入参这几行，明细回空 */
    private void stubReads(OrderMapper orderMapper, OrderItemMapper itemMapper, List<Order> page) {
        when(orderMapper.selectCount(any())).thenReturn(1L);
        when(orderMapper.selectList(any())).thenReturn(page);
        when(itemMapper.selectList(any())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Order> rowsOf(OrderServiceImpl service) {
        return (List<Order>) service.getOrders(7L, 1, 10, null).get("list");
    }

    @Test
    void 命中已确认批次的那单标true未命中标显式false() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(
                payableOrder("2111000000000000001", 1L, "2026-10-17"),
                payableOrder("2111000000000000002", 2L, "2026-10-17")));
        when(batchMapper.selectConfirmedArrivalKeys(anyCollection(), anyCollection()))
                .thenReturn(List.of("1#2026-10-17"));

        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));

        assertEquals(Boolean.TRUE, rows.get(0).getGoodsArrived(), "store 1 + 2026-10-17 有已确认批次 → true");
        assertEquals(Boolean.FALSE, rows.get(1).getGoodsArrived(), "store 2 无批次 → 显式 false，不是漏填");
    }

    @Test
    void 异型拼键格式一律算未命中() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(payableOrder("2111000000000000001", 1L, "2026-10-17")));
        // 三种「看着像但不是」的形态：任一侧拼错（日期非 ISO、补零、分隔符）都必须落到未命中
        when(batchMapper.selectConfirmedArrivalKeys(anyCollection(), anyCollection()))
                .thenReturn(List.of("1#17-10-2026", "01#2026-10-17", "1_2026-10-17"));

        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));

        assertEquals(Boolean.FALSE, rows.get(0).getGoodsArrived(),
                "本断言只管 Java 侧不接受异型格式；MySQL 侧输出是否是 ISO 由 verify-f62.cjs 真库实测");
    }

    @Test
    void 同店同日两单共用一次判定() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(
                payableOrder("2111000000000000001", 1L, "2026-10-17"),
                payableOrder("2111000000000000002", 1L, "2026-10-17"),
                payableOrder("2111000000000000003", 1L, "2026-10-18")));
        when(batchMapper.selectConfirmedArrivalKeys(anyCollection(), anyCollection()))
                .thenReturn(List.of("1#2026-10-17"));

        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));

        assertEquals(List.of(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE),
                Arrays.asList(rows.get(0).getGoodsArrived(), rows.get(1).getGoodsArrived(), rows.get(2).getGoodsArrived()));
        verify(batchMapper).selectConfirmedArrivalKeys(anyCollection(), anyCollection());
    }

    @Test
    void 送进IN的两枚集合去重且不含null() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(
                payableOrder("2111000000000000001", 1L, "2026-10-17"),
                payableOrder("2111000000000000002", 1L, "2026-10-17"),
                payableOrder("2111000000000000003", 3L, "2026-10-19"),
                payableOrder("2111000000000000004", null, "2026-10-17")));
        when(batchMapper.selectConfirmedArrivalKeys(anyCollection(), anyCollection())).thenReturn(List.of());

        ArgumentCaptor<Collection<Long>> storeCap = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<LocalDate>> dateCap = ArgumentCaptor.forClass(Collection.class);
        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));

        verify(batchMapper).selectConfirmedArrivalKeys(storeCap.capture(), dateCap.capture());
        assertEquals(Set.of(1L, 3L), Set.copyOf(storeCap.getValue()), "store_id 去重");
        assertFalse(storeCap.getValue().contains(null), "null 进 IN 会让条件永不成立且不报错，必须剔除");
        assertEquals(Set.of(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-19")),
                Set.copyOf(dateCap.getValue()), "pickup_date 去重后只剩两枚");
        assertNull(rows.get(3).getGoodsArrived(), "无自提点的单不参与判定，留 null 让出参自己说明「无从归因」");
    }

    @Test
    void 页内无一可判定时一次批次查询都不发() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(
                order("2111000000000000001", OrderServiceImpl.STATUS_COMPLETED, 1, 1L, "2026-10-17"),
                order("2111000000000000002", OrderServiceImpl.STATUS_CANCELLED, 1, 1L, "2026-10-17"),
                order("2111000000000000003", OrderServiceImpl.STATUS_PENDING_PICKUP, 0, 1L, "2026-10-17"),
                order("2111000000000000004", OrderServiceImpl.STATUS_PENDING_PICKUP, 1, 1L, null)));
        when(batchMapper.selectConfirmedArrivalKeys(anyCollection(), anyCollection())).thenReturn(List.of("1#2026-10-17"));

        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));

        verify(batchMapper, never()).selectConfirmedArrivalKeys(anyCollection(), anyCollection());
        assertEquals(Arrays.asList(null, null, null, null), Arrays.asList(
                rows.get(0).getGoodsArrived(), rows.get(1).getGoodsArrived(),
                rows.get(2).getGoodsArrived(), rows.get(3).getGoodsArrived()),
                "已完成/已取消/未支付/无自提日四行全留 null：核销完成的单再提示「去拿货」是假指引");
    }

    @Test
    void 未赋值的行序列化出null键而非缺键() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        stubReads(orderMapper, itemMapper, List.of(
                order("2111000000000000001", OrderServiceImpl.STATUS_COMPLETED, 1, 1L, "2026-10-17")));

        List<Order> rows = rowsOf(newService(orderMapper, itemMapper, batchMapper));
        JsonNode node = mapper.readTree(mapper.writeValueAsString(rows.get(0)));

        assertTrue(node.has("goodsArrived"), "本仓没配任何 jackson 包含策略 → null 会真写进 JSON");
        assertTrue(node.get("goodsArrived").isNull(), "该键是 JSON null，故前端只能写 === true");
    }

    @Test
    void 零单页不触发批次查询() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        ArrivalBatchMapper batchMapper = mock(ArrivalBatchMapper.class);
        when(orderMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> data = newService(orderMapper, mock(OrderItemMapper.class), batchMapper)
                .getOrders(7L, 1, 10, null);

        assertEquals(Integer.valueOf(0), data.get("total"), "空结果早退分支回的是字面量 int 0，此处按实际类型断言");
        verify(batchMapper, never()).selectConfirmedArrivalKeys(anyCollection(), anyCollection());
    }
}
