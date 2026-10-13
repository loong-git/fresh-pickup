package com.fresh.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.InviteService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付成功结算接缝单测（F-21.3 Step 1 第 4 条）：Mockito mock 掉 OrderMapper/OrderItemMapper/DishMapper/
 * InviteService 四个协作者，直接 new PaymentSettlement，**不连库、不起 Spring 容器**
 * （形态照存量唯一的 RecommendServiceTest：纯内存断言，期望值全部手工可推）。
 *
 * 覆盖 F-21.3 第 4 条列的三组断言：
 * ① updated>0（首次支付成功）：increaseSoldCount / attachPickupCode / onOrderPaid 恰好各调一次，
 *    且返回的就是传入的那个 order 对象（S3-0 内存回写：码写库的值 == 响应 data.pickupCode）；
 * ② updated==0（并发窗口内已被支付）：三者调用数全为 0，只做一次回读，幂等不二次入账；
 * ③ 取货码 5 次全撞 DuplicateKeyException：抛「取货码生成失败，请稍后重试」（400，文案逐字），
 *    且邀请挂钩在其之前未执行——调用方事务回滚后不留「已支付无码」半态。
 * 另附 BE-6 两条重试续跑路径：撞唯一键后换码成功、码已被占用（count>0）先跳过再写。
 * 另附 F-21.10（L-21-3）两条 payTime 精度归一：带纳秒入参截到整秒后回写、且截断**不进位**
 * （{@code .999999999} 仍是 09:30:00）；「库值 == 响应值」不在单测可证范围内，由真库门禁实测。
 *
 * 取货码由 SecureRandom 现生成（D-4），断言一律不依赖具体码值：只用 captor 取「实际写进库的那个码」，
 * 再校验它 6 位数字且与回写进内存 order 的值相等。
 */
class PaymentSettlementTest {

    private static final String ORDER_ID = "2104094865859330050";
    private static final Long USER_ID = 4L;
    private static final LocalDateTime PAY_TIME = LocalDateTime.of(2026, 10, 13, 9, 30, 0);

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final InviteService inviteService = mock(InviteService.class);

    private PaymentSettlement settlement;

    /**
     * MP 的 {@code LambdaUpdateWrapper.set(getter, value)} 是**即时**解析列名的（`.eq(...)` 那条是懒解析、
     * 所以 RecommendServiceTest 不需要这一步），解析依赖实体的 TableInfo/列缓存——生产里这份缓存由
     * MyBatis-Plus 启动扫 mapper 时装配，本测试不起容器也不连库，故手工灌 Order 一次
     * （TableInfoHelper 是 MP 官方入口，只建内存缓存、不碰任何连接）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        settlement = new PaymentSettlement(orderMapper, orderItemMapper, dishMapper, inviteService);
    }

    // —— fixture 工具 ——

    /** 待支付订单行（pay_status=0 / 无取货码），即调用方（MockPayService#payResult）校验通过后交进来的那个对象 */
    private Order unpaidOrder() {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setUserId(USER_ID);
        o.setStatus("pending_pickup");
        o.setPayStatus(0);
        o.setTotalPrice(new BigDecimal("15.00"));
        return o;
    }

    private OrderItem item(long dishId, int quantity) {
        OrderItem it = new OrderItem();
        it.setOrderId(ORDER_ID);
        it.setDishId(dishId);
        it.setQuantity(quantity);
        return it;
    }

    /** 条件更新影响行数：>0 = 本次调用抢到「首次置态」；0 = 并发窗口内已被别的请求置成 1 */
    private void stubUpdateRows(int rows) {
        when(orderMapper.update(isNull(), any())).thenReturn(rows);
    }

    // —— ① updated>0：四件副作用各恰一次 ——

    @Test
    void firstPaymentRunsEachSideEffectExactlyOnce() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(7L, 3)));
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString())).thenReturn(1);

        Order order = unpaidOrder();
        Order returned = settlement.settle(order, PAY_TIME);

        // 内存回写（S3-0）：返回的就是传进来的那个对象，置态与支付时间就地生效、且与落库同一个 payTime
        assertSame(order, returned);
        assertEquals(Integer.valueOf(1), returned.getPayStatus());
        assertEquals(PAY_TIME, returned.getPayTime());

        // 销量累加：一条明细一次，dishId/quantity 原样透传
        verify(dishMapper, times(1)).increaseSoldCount(7L, 3);
        // 取货码：写库一次，且响应里的码 = 实际写进库的那个码（不依赖具体码值，只断言一致且 6 位数字）
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderMapper, times(1)).attachPickupCode(eq(ORDER_ID), codeCaptor.capture());
        String writtenCode = codeCaptor.getValue();
        assertNotNull(writtenCode);
        assertTrue(writtenCode.matches("\\d{6}"), "取货码须为 6 位数字（D-3）：" + writtenCode);
        assertEquals(writtenCode, returned.getPickupCode(), "写库码必须回写进即将返回的内存 order（S3-0）");
        // 邀请挂钩：一次，userId 取自订单行（mock 链路已校验过它等于登录 userId）
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
        // 条件更新本身就是那唯一一次；成功分支不回读（与抽取前的 MockPayService 一致）
        verify(orderMapper, times(1)).update(isNull(), any());
        verify(orderMapper, never()).selectById(any());
    }

    // —— ② updated==0：三者调用数全为 0，只回读 ——

    @Test
    void concurrentSecondSettleHasNoSideEffect() {
        stubUpdateRows(0);
        Order paidFromDb = unpaidOrder();
        paidFromDb.setPayStatus(1);
        paidFromDb.setPayTime(PAY_TIME);
        paidFromDb.setPickupCode("123456");
        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidFromDb);

        Order returned = settlement.settle(unpaidOrder(), PAY_TIME);

        // 幂等：并发窗口内已被支付 → 原样回读返回，四件副作用一件都不做
        assertSame(paidFromDb, returned);
        verify(dishMapper, never()).increaseSoldCount(anyLong(), anyInt());
        verify(orderMapper, never()).attachPickupCode(anyString(), anyString());
        verify(inviteService, never()).onOrderPaid(any());
        // 连「探测码占用」都不该发生（码只在首次置态那一次生成）
        verify(orderMapper, never()).countByPickupCode(anyString());
        verify(orderItemMapper, never()).selectList(any());
        verify(orderMapper, times(1)).selectById(ORDER_ID);
    }

    // —— ③ 取货码 5 次全撞唯一键：抛 400 文案逐字、邀请挂钩未执行 ——

    @Test
    void pickupCodeExhaustingFiveRetriesThrowsBizException() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(7L, 1)));
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_pickup_code'"));

        Order order = unpaidOrder();
        BizException ex = assertThrows(BizException.class, () -> settlement.settle(order, PAY_TIME));

        assertEquals("取货码生成失败，请稍后重试", ex.getMessage(), "文案逐字（F-18.6/D-3）");
        assertEquals(R.CODE_BAD_REQUEST, ex.getCode());
        // D-3 的重试上限 5 次真的兜得住：5 轮各探测一次、各尝试写一次
        verify(orderMapper, times(5)).countByPickupCode(anyString());
        verify(orderMapper, times(5)).attachPickupCode(eq(ORDER_ID), anyString());
        // 抛出点在内存回写与邀请挂钩之前 → 调用方事务回滚，不出现「已支付无码」半态、也不入邀请账
        assertNull(order.getPickupCode());
        verify(inviteService, never()).onOrderPaid(any());
    }

    // —— 附加：BE-6 撞唯一键后继续下一轮并成功（1062 不打成 500） ——

    @Test
    void duplicateKeyOnFirstAttemptRetriesWithNextCodeAndSucceeds() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_pickup_code'"))
                .thenReturn(1);

        Order order = unpaidOrder();
        Order returned = settlement.settle(order, PAY_TIME);

        assertSame(order, returned);
        verify(orderMapper, times(2)).attachPickupCode(eq(ORDER_ID), anyString());
        assertNotNull(returned.getPickupCode());
        assertTrue(returned.getPickupCode().matches("\\d{6}"));
        // 换码成功即照常走完剩下的挂钩（一次，不多不少）
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
    }

    // —— 附加：码已被占用（count>0）先跳过、不把 1062 打进事务（F-18.6 概率前移） ——

    @Test
    void occupiedCodeIsSkippedBeforeWriting() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.countByPickupCode(anyString())).thenReturn(1, 0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString())).thenReturn(1);

        Order returned = settlement.settle(unpaidOrder(), PAY_TIME);

        // 第一轮探测到已占用 → continue（不写库）；第二轮才写
        verify(orderMapper, times(2)).countByPickupCode(anyString());
        verify(orderMapper, times(1)).attachPickupCode(eq(ORDER_ID), anyString());
        assertNotNull(returned.getPickupCode());
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
    }

    // —— 附加（F-21.10 / L-21-3）：payTime 精度归一到整秒 ——

    /**
     * ① 带纳秒的入参被截到整秒后再回写内存 order：即时响应里不再出现 {@code 09:30:00.675} 这种小数秒。
     * <p>
     * 为什么必须有这条：{@code orders.pay_time} 是 {@code DATETIME(0)}，而 MySQL 对小数秒**四舍五入**
     * （实测 {@code CAST('...11:28:47.675' AS DATETIME)} = {@code 48}），带纳秒的 {@code now()} 直接落库
     * 就成了「响应说 47.675、库里存 48」——同一事实两个表示互相矛盾，还差整整 1 秒。
     * 注：本条只证「归一 + 内存回写无纳秒」；「库值 == 响应值」要由真库门禁证（update stub 不解析
     * LambdaUpdateWrapper 的 set 值，见 F-21.10 S-4）。
     */
    @Test
    void payTimeNanosAreTruncatedToWholeSecond() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString())).thenReturn(1);

        Order order = unpaidOrder();
        Order returned = settlement.settle(order, LocalDateTime.of(2026, 10, 13, 9, 30, 0, 675_000_000));

        assertEquals(0, returned.getPayTime().getNano(), "回写内存/响应的 payTime 不该带小数秒（L-21-3）");
        assertEquals(PAY_TIME, returned.getPayTime(), "09:30:00.675 归一后就是 09:30:00");
    }

    /**
     * ② 恰好会进位的纳秒（{@code .999999999}）也只能归一到 {@code 09:30:00}，不得跳到 {@code 09:30:01}：
     * 钉住实现是**截断**而不是「四舍五入/对齐」——将来有人把它改成 {@code plusNanos(500_000_000)} 之类的
     * 写法会在 Java 侧多进一位（MySQL 那侧本来就还会再进一次），这条立刻变红。
     */
    @Test
    void payTimeTruncationDoesNotRoundUp() {
        stubUpdateRows(1);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(eq(ORDER_ID), anyString())).thenReturn(1);

        Order order = unpaidOrder();
        Order returned = settlement.settle(order, LocalDateTime.of(2026, 10, 13, 9, 30, 0, 999_999_999));

        assertEquals(0, returned.getPayTime().getNano(), "归一后纳秒恒为 0");
        assertEquals(30, returned.getPayTime().getMinute(), "分钟不受小数秒扰动");
        assertEquals(0, returned.getPayTime().getSecond(), "截断不是四舍五入：.999999999 不得进位成 09:30:01");
        assertEquals(PAY_TIME, returned.getPayTime());
    }
}
