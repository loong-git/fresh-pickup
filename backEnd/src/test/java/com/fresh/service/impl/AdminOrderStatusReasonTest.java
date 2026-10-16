package com.fresh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Order;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.CouponService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-61 取消理由留痕契约单测（不起 Spring 容器、不落库、绝不写库）。
 *
 * <p><b>要证明的四件事</b>：
 * ① 取消必填理由，且这一判据<b>发生在查库之前</b>（理由缺失是请求自身的缺陷，不该先花一次 SELECT）；
 * ② 理由进审计 detail 后是<b>三键合法 JSON</b> {@code {"old","new","reason"}}，且值被 trim；
 * ③ 核销（无理由）时 detail <b>仍是原来的两键形状、不含 reason 键</b>——这是向后兼容的硬断言：
 *    运营端渲染、SQL 手册与 F-54 收成单一出口的审计列格式化函数都吃这个形状；
 * ④ 理由长到装不下时，牺牲的是 reason 而不是 old/new，且<b>兜底仍是三键</b>
 *    （换回两键就等于把这次改动唯一要留住的东西删了）。
 *
 * <p><b>本类测不成什么（免得留下一张假收据）</b>：mapper 是 mock，所以「取消真的回补了库存、
 * pay_status 真的从 1 变 2」这类活体行为不在证明力之内（那由 F-57 的接口级联调与 DB 断言覆盖）；
 * 本类只管 Service 层的判据顺序与 detail 的 JSON 形状。
 */
class AdminOrderStatusReasonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    private AdminServiceImpl newService(OrderMapper orderMapper, OrderItemMapper itemMapper,
                                        DishMapper dishMapper, CouponService couponService,
                                        AdminAuditLogMapper auditMapper) throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        inject(service, "orderMapper", orderMapper);
        inject(service, "orderItemMapper", itemMapper);
        inject(service, "dishMapper", dishMapper);
        inject(service, "couponService", couponService);
        inject(service, "adminAuditLogMapper", auditMapper);
        inject(service, "adminKey", "test-admin-key");
        return service;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Order pendingOrder() {
        Order o = new Order();
        o.setId("2111000000000000001");
        o.setStatus(OrderServiceImpl.STATUS_PENDING_PICKUP);
        return o;
    }

    /** 全链路打桩：selectById 给一条待自提单，两次条件 UPDATE 都命中 1 行 */
    private void stubOrderMapper(OrderMapper orderMapper) {
        when(orderMapper.selectById(any())).thenReturn(pendingOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);
    }

    private AdminAuditLog captureAudit(AdminAuditLogMapper auditMapper) {
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    void 取消不填理由被拒且一次库都不碰() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        AdminServiceImpl service = newService(orderMapper, mock(OrderItemMapper.class),
                mock(DishMapper.class), mock(CouponService.class), auditMapper);

        BizException e = assertThrows(BizException.class, () ->
                service.updateOrderStatus("2111000000000000001", "cancelled", "   ", "admin_user:2"));

        assertEquals("取消订单必须填写原因（会回补库存并置已退款，需可追责）", e.getMessage());
        // 判据在查库之前：selectById 一次都不该被调用（纯空白也算没填，不许用 trim 前的非空蒙过判据）
        verify(orderMapper, never()).selectById(any());
        verify(auditMapper, never()).insert(any());
    }

    @Test
    void 理由进三键detail且被trim并仍是合法JSON() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        stubOrderMapper(orderMapper);
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        AdminServiceImpl service = newService(orderMapper, itemMapper,
                mock(DishMapper.class), mock(CouponService.class), auditMapper);

        service.updateOrderStatus("2111000000000000001", "cancelled", "  顾客要求退款  ", "admin_user:2");

        AdminAuditLog row = captureAudit(auditMapper);
        assertEquals("order.status", row.getAction());
        JsonNode detail = mapper.readTree(row.getDetail());
        List<String> keys = new java.util.ArrayList<>();
        detail.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("old", "new", "reason"), keys, "键序固定 old→new→reason，与 buildAuditDetail 的拼接顺序同源");
        assertEquals("pending_pickup", detail.get("old").asText());
        assertEquals("cancelled", detail.get("new").asText());
        assertEquals("顾客要求退款", detail.get("reason").asText());
    }

    @Test
    void 核销不带理由时detail形状与改动前逐字相同() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        stubOrderMapper(orderMapper);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        AdminServiceImpl service = newService(orderMapper, mock(OrderItemMapper.class),
                mock(DishMapper.class), mock(CouponService.class), auditMapper);

        service.updateOrderStatus("2111000000000000001", "completed", null, "admin_user:2");

        String detail = captureAudit(auditMapper).getDetail();
        assertEquals("{\"old\":\"pending_pickup\",\"new\":\"completed\"}", detail);
        assertFalse(detail.contains("reason"), "无理由时不许出现 reason 键，更不许写空串冒充「填了但没内容」");
    }

    @Test
    void 超长理由牺牲自己但兜底仍是三键合法JSON() throws Exception {
        OrderMapper orderMapper = mock(OrderMapper.class);
        stubOrderMapper(orderMapper);
        AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);
        AdminServiceImpl service = newService(orderMapper, mock(OrderItemMapper.class),
                mock(DishMapper.class), mock(CouponService.class), auditMapper);

        service.updateOrderStatus("2111000000000000001", "cancelled", "长".repeat(600), "admin_user:2");

        String detail = captureAudit(auditMapper).getDetail();
        assertTrue(detail.length() <= 500, "detail 必须落在 VARCHAR(500) 内，实际 " + detail.length());
        JsonNode node = mapper.readTree(detail);
        assertTrue(node.has("reason"), "截断兜底不许把 reason 键整体丢掉（那等于删掉本次要留的东西）");
        assertEquals("pending_pickup", node.get("old").asText());
        assertEquals("cancelled", node.get("new").asText());
    }
}
