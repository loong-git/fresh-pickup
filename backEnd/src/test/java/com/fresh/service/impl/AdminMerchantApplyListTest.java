package com.fresh.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.mapper.MerchantApplyMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-106 片 B2 运营端申请列表契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>（契约 §0.6 / 派发单）：
 * ① 状态筛选：值下传 mapper、大小写归一小写、空串/空白 = 全部；
 * ② 非法 status（白名单外）→ 400，且一次库都不碰；
 * ③ 分页边界：pageNum≥1、1≤pageSize≤100，缺省 1/20，越界 400 不夹取，offset 用 long 计算；
 * ④ 脱敏：applicantPhone 出 {@code 138****1234}，序列化后的整包出参<b>不出现</b>原文号码；
 * ⑤ 出参形状：行十三键（契约定序）+ 分页五键（与 AdminV2ServiceImpl#storeApplies 逐字同形）；
 * ⑥ pending 优先排序：钉住 mapper SQL 的 {@code ORDER BY (status='pending') DESC, created_at DESC, id DESC}。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 全是 mock，「pending 真的排在前面」由 DB 执行，
 * 本类只能把排序表达式钉进 SQL 文本；真实排序与 JOIN 取数由主会话真库门禁覆盖。
 */
class AdminMerchantApplyListTest {

    private static final String PHONE = "13800001234";
    private static final String MASKED = "138****1234";
    private static final String LICENSES =
            "[{\"type\":\"business_license\",\"url\":\"/static/images/uploads/license.png\"},"
                    + "{\"type\":\"food_business_license\",\"url\":\"/static/images/uploads/food.png\"}]";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final MerchantApplyMapper applyMapper = mock(MerchantApplyMapper.class);

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AdminServiceImpl();
        Field f = AdminServiceImpl.class.getDeclaredField("merchantApplyMapper");
        f.setAccessible(true);
        f.set(service, applyMapper);
    }

    /** 一行 mapper 出参：键名与本片 SQL 的别名逐字一致（applicantPhone 是 user.phone 原文） */
    private static Map<String, Object> row(Object phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5L);
        row.put("userId", 7L);
        row.put("merchantName", "演示商家");
        row.put("contactName", "张三");
        row.put("businessLicense", "/static/images/uploads/license.png");
        row.put("licenses", LICENSES);
        row.put("legalPerson", "李四");
        row.put("address", "演示路 1 号");
        row.put("settlementAccount", "acct-001");
        row.put("status", "pending");
        row.put("auditRemark", null);
        row.put("createdAt", "2026-10-22 10:00:00");
        row.put("auditedAt", null);
        row.put("applicantPhone", phone);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("list");
    }

    // ---------------------------------------------------------------- 状态筛选

    @Test
    @SuppressWarnings("unchecked")
    void 空status查全部且filters回显null() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 20)).thenReturn(List.of(row(PHONE)));

        Map<String, Object> data = service.listMerchantApplies(null, null, null);

        assertEquals(1L, data.get("total"));
        Map<String, Object> filters = (Map<String, Object>) data.get("filters");
        assertTrue(filters.containsKey("status"), "未筛选时 filters.status 键仍必须存在");
        assertNull(filters.get("status"), "未筛选时 filters.status 必须是显式 null");
        assertEquals(List.of("list", "total", "pageNum", "pageSize", "filters"), new ArrayList<>(data.keySet()),
                "分页五键与键序逐字照 AdminV2ServiceImpl#pageResult");
    }

    @Test
    void 空串与纯空白status都视为全部() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(0L);

        service.listMerchantApplies(1, 20, "");
        service.listMerchantApplies(1, 20, "   ");

        // 两次都按 null（不筛）下传，且空表时 COUNT 为 0 → 不查页
        verify(applyMapper, org.mockito.Mockito.times(2)).countMerchantApplies(null);
        verify(applyMapper, never()).selectMerchantAppliesPage(any(), anyLong(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 状态筛选归一小写后下传并回显生效值() {
        when(applyMapper.countMerchantApplies("pending")).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage("pending", 0L, 20)).thenReturn(List.of(row(PHONE)));

        Map<String, Object> data = service.listMerchantApplies(1, 20, "  PENDING ");

        verify(applyMapper).countMerchantApplies("pending");
        assertEquals("pending", ((Map<String, Object>) data.get("filters")).get("status"));
    }

    @Test
    void 非法status判四百且一次库都不碰() {
        for (String bad : new String[]{"deleted", "pending2", "0", "PENDING2"}) {
            BizException e = assertThrows(BizException.class,
                    () -> service.listMerchantApplies(1, 20, bad), "status=" + bad + " 必须 400");
            assertEquals(400, e.getCode());
            assertEquals("请求参数有误，请检查后重试", e.getMessage());
        }
        verifyNoInteractions(applyMapper);
    }

    // ---------------------------------------------------------------- 分页边界

    @Test
    void 分页缺省为1和20() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 20)).thenReturn(List.of(row(PHONE)));

        Map<String, Object> data = service.listMerchantApplies(null, null, null);

        assertEquals(1, data.get("pageNum"));
        assertEquals(20, data.get("pageSize"));
        verify(applyMapper).selectMerchantAppliesPage(null, 0L, 20);
    }

    @Test
    void 分页越界一律四百且不夹取不查库() {
        assertPagingRejected(0, 20);
        assertPagingRejected(-1, 20);
        assertPagingRejected(1, 0);
        assertPagingRejected(1, 101);
        assertPagingRejected(1, -5);
        verifyNoInteractions(applyMapper);
    }

    private void assertPagingRejected(Integer pageNum, Integer pageSize) {
        BizException e = assertThrows(BizException.class,
                () -> service.listMerchantApplies(pageNum, pageSize, null),
                "pageNum=" + pageNum + ", pageSize=" + pageSize + " 必须 400");
        assertEquals(400, e.getCode());
        assertEquals("请求参数有误，请检查后重试", e.getMessage());
    }

    @Test
    void 分页合法边界与offset计算() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1000L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 1)).thenReturn(List.of(row(PHONE)));
        when(applyMapper.selectMerchantAppliesPage(null, 200L, 100)).thenReturn(List.of(row(PHONE)));

        service.listMerchantApplies(1, 1, null);       // 下边界
        service.listMerchantApplies(3, 100, null);     // 上边界 size=100；offset=(3-1)*100=200

        verify(applyMapper).selectMerchantAppliesPage(null, 0L, 1);
        verify(applyMapper).selectMerchantAppliesPage(null, 200L, 100);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offset越过total时短路不查页() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(5L);

        Map<String, Object> data = service.listMerchantApplies(2, 20, null);

        verify(applyMapper, never()).selectMerchantAppliesPage(any(), anyLong(), anyInt());
        assertEquals(5L, data.get("total"));
        assertTrue(listOf(data).isEmpty());
    }

    // ---------------------------------------------------------------- 脱敏与出参形状

    @Test
    @SuppressWarnings("unchecked")
    void applicantPhone必须脱敏且整包不出现原号码() throws Exception {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 20)).thenReturn(List.of(row(PHONE)));

        Map<String, Object> data = service.listMerchantApplies(1, 20, null);

        Map<String, Object> line = listOf(data).get(0);
        assertEquals(MASKED, line.get("applicantPhone"));
        String payload = json.writeValueAsString(data);
        assertFalse(payload.contains(PHONE), "出参序列化后不得出现原号码: " + payload);
        assertTrue(payload.contains(MASKED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 申请人不存在时phone原样回null而非全掩码() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 20)).thenReturn(List.of(row(null)));

        Map<String, Object> data = service.listMerchantApplies(1, 20, null);

        assertNull(listOf(data).get(0).get("applicantPhone"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 行出参十四键且键序与契约逐字一致() {
        when(applyMapper.countMerchantApplies(null)).thenReturn(1L);
        when(applyMapper.selectMerchantAppliesPage(null, 0L, 20)).thenReturn(List.of(row(PHONE)));

        Map<String, Object> data = service.listMerchantApplies(1, 20, null);

        Map<String, Object> line = listOf(data).get(0);
        assertEquals(List.of("id", "userId", "merchantName", "contactName", "businessLicense", "licenses",
                "legalPerson", "address", "settlementAccount", "status", "auditRemark",
                "createdAt", "auditedAt", "applicantPhone"), new ArrayList<>(line.keySet()));
        assertEquals(7L, line.get("userId"), "userId 是契约点名的出参（与 store-applies 的 D-15 不同）");
        assertEquals("演示商家", line.get("merchantName"));
        assertEquals(LICENSES, line.get("licenses"), "F-107：licenses 字符串原样出参（不 parse、不脱敏）");
    }

    // ---------------------------------------------------------------- 排序（SQL 层）

    @Test
    void pending优先排序钉在mapperSQL上() throws Exception {
        Method page = MerchantApplyMapper.class.getMethod(
                "selectMerchantAppliesPage", String.class, long.class, int.class);
        String sql = String.join(" ", page.getAnnotation(Select.class).value());

        assertTrue(sql.contains("ORDER BY (ma.status = 'pending') DESC, ma.created_at DESC, ma.id DESC"),
                "排序必须是 pending 优先，其次 created_at DESC, id DESC；实际: " + sql);
        assertTrue(sql.contains("LEFT JOIN `user` u ON u.id = ma.user_id"), "applicantPhone 必须来自 user.phone 关联");
        assertTrue(sql.contains("u.phone AS applicantPhone"));
        assertTrue(sql.contains("ma.licenses AS licenses"), "F-107：列表 SQL 必须带出 licenses 原文");
        assertFalse(sql.contains("pending_guard"), "生成列绝不出现在 SELECT 列表");
    }

    @Test
    void 页与COUNT的WHERE共用同一常量串() throws Exception {
        Method page = MerchantApplyMapper.class.getMethod(
                "selectMerchantAppliesPage", String.class, long.class, int.class);
        Method count = MerchantApplyMapper.class.getMethod("countMerchantApplies", String.class);
        String pageSql = String.join(" ", page.getAnnotation(Select.class).value());
        String countSql = String.join(" ", count.getAnnotation(Select.class).value());

        assertTrue(pageSql.contains(MerchantApplyMapper.MERCHANT_APPLY_FILTER_WHERE));
        assertTrue(countSql.contains(MerchantApplyMapper.MERCHANT_APPLY_FILTER_WHERE));
    }
}
