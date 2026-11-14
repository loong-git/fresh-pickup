package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.entity.OpsLog;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.OpsLogMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.service.OpsLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家缺货登记（F-18.8 第 5 条/D-15，{@code MerchantFulfillServiceImpl#registerShortage}）契约单测
 * （不起 Spring 容器、不落库）。
 *
 * <p><b>补测动机</b>：P11-2 审计轮 3 P2 缺口 #3——registerShortage 此前零直测。定性只有两种出口
 * 且都不泄漏他人行存在性（类注释实读）：
 * ① 条件 UPDATE（WHERE id + merchant_id + fulfill_status != 'shortage'，后者是已登记偏差）
 *    影响行数 &gt;0 → 成功 + ops_log（{@code register_shortage}，detail 只放状态迁移）；
 * ② 影响行数 =0 → selectById 定性：行不存在 / merchant_id 不是自己（含历史行 NULL）→ 404
 *    与「订单不存在」同码同文案（不枚举他人行）；是自己的行 → 已是 shortage → 幂等成功、
 *    不写第二行日志（D-6/S3-G7）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper/OpsLogWriter 底层都是 mock，
 * matched-rows 语义与 @Transactional 不在证明力内；SQL 形状（归属双条件）由生产 SQL 文本与真库门禁覆盖。
 */
class MerchantFulfillShortageTest {

    private static final Long USER_ID = 8L;
    private static final Long MERCHANT_ID = 2L;
    private static final Long ITEM_ID = 66L;

    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final OpsLogMapper opsLogMapper = mock(OpsLogMapper.class);

    private MerchantFulfillServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new MerchantFulfillServiceImpl();
        ReflectionTestUtils.setField(service, "orderItemMapper", orderItemMapper);
        // OpsLogWriter 用真实实例（底层 OpsLogMapper 换 mock），行内容由 captor 断言（照 StoreApplyCreateTest 姿势）
        OpsLogWriter opsLogWriter = new OpsLogWriter();
        Field f = OpsLogWriter.class.getDeclaredField("opsLogMapper");
        f.setAccessible(true);
        f.set(opsLogWriter, opsLogMapper);
        ReflectionTestUtils.setField(service, "opsLogWriter", opsLogWriter);
    }

    private static OrderItem item(Long merchantId) {
        OrderItem row = new OrderItem();
        row.setId(ITEM_ID);
        row.setMerchantId(merchantId);
        return row;
    }

    // ---------------------------------------------------------------- ① 成功 + 审计

    @Test
    void 登记成功置shortage并写ops_log审计() {
        when(orderItemMapper.markShortage(ITEM_ID, MERCHANT_ID)).thenReturn(1);   // 归属双条件命中

        service.registerShortage(USER_ID, MERCHANT_ID, ITEM_ID);

        ArgumentCaptor<OpsLog> cap = ArgumentCaptor.forClass(OpsLog.class);
        verify(opsLogMapper).insert(cap.capture());
        OpsLog audit = cap.getValue();
        assertEquals(USER_ID, audit.getOperatorUserId(), "operator_user_id=商家本人（D-5：只传 ATTR_USER_ID）");
        assertEquals("register_shortage", audit.getAction());
        assertEquals(String.valueOf(ITEM_ID), audit.getTargetId());
        assertEquals("{\"from\":\"normal\",\"to\":\"shortage\"}", audit.getDetail(),
                "detail 只放状态迁移（RD：不放手机号/地址/订单号）");
    }

    // ---------------------------------------------------------------- ② 归属谓词 404

    @Test
    void 他人行与不存在行同码四百零四零审计() {
        when(orderItemMapper.markShortage(ITEM_ID, MERCHANT_ID)).thenReturn(0);   // 条件 UPDATE 零行 → 回查定性

        // 行不存在
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(null);
        BizException missing = assertThrows(BizException.class, () ->
                service.registerShortage(USER_ID, MERCHANT_ID, ITEM_ID));
        assertEquals(404, missing.getCode());
        assertEquals("订单不存在", missing.getMessage(), "不存在与「不是自己的」同文案，不泄漏行存在性");

        // 他人行（merchant_id 不是自己）
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item(999L));
        BizException foreign = assertThrows(BizException.class, () ->
                service.registerShortage(USER_ID, MERCHANT_ID, ITEM_ID));
        assertEquals(404, foreign.getCode());
        assertEquals("订单不存在", foreign.getMessage());

        // 历史行 merchant_id 为 NULL：同样按「不是自己的」定性（类注释实读的归属谓词）
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item(null));
        BizException legacy = assertThrows(BizException.class, () ->
                service.registerShortage(USER_ID, MERCHANT_ID, ITEM_ID));
        assertEquals(404, legacy.getCode());
        assertEquals("订单不存在", legacy.getMessage());

        // 三种定性都是 404：现场动作审计一行都不落
        verify(opsLogMapper, never()).insert(any(OpsLog.class));
    }

    // ---------------------------------------------------------------- ③ 幂等成功

    @Test
    void 已登记重复提交幂等成功不写第二行日志() {
        when(orderItemMapper.markShortage(ITEM_ID, MERCHANT_ID)).thenReturn(0);   // fulfill_status 已 shortage → 0 行
        when(orderItemMapper.selectById(ITEM_ID)).thenReturn(item(MERCHANT_ID));  // 回查是自己的行 → 幂等成功

        assertDoesNotThrow(() -> service.registerShortage(USER_ID, MERCHANT_ID, ITEM_ID),
                "已是 shortage 的自己的行：幂等成功，不抛异常");

        verify(opsLogMapper, never()).insert(any(OpsLog.class));   // D-6：幂等成功不重复写日志
    }
}
