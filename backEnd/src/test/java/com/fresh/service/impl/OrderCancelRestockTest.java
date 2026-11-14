package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 审计轮 3 P1 缺口 #1：取消/回补/退款链路补测（OrderServiceImpl#cancelOrder :272-286、
 * #restockAfterCancel :294-313、#closeTimeoutOrders :366-393 此前零覆盖）。
 *
 * <p>照 OrderCreateIdempotentOwnerTest / OrderCreateDailyCapacityTest 的桩范式：不起容器、不落库、
 * {@code @Autowired} 字段逐个灌 mock；LambdaUpdateWrapper 的 {@code set()} 解析列名依赖实体 TableInfo，
 * {@code @BeforeAll} 手工灌内存缓存（同两先例）。
 *
 * <p>覆盖面（8 例）：
 * ① 非本人取消（含 user_id 未认领的存量单）→ 404「订单不存在」，零写库（T-M2-03 归属校验）；
 * ② 状态机条件 UPDATE 0 行（并发双击/已终态）→ 400「当前状态不可取消」，回补链零触发；
 * ③ 已支付单取消：pay_status 1→2 退款语义置位（两枚条件 UPDATE wrapper 的绑定值取证）；
 * ④ 取消回补：increaseStock 按明细（dishId, quantity）逐条调用；
 * ⑤ 已支付单取消回退销量：decrementSoldCount 按明细调用（未支付取消不回退由 ④ 的 never 断言钉住）；
 * ⑥ 券回补联动：couponService.restoreByOrderId(orderId) 恰调一次；
 * ⑦ closeTimeoutOrders 已支付单防误杀：行为上置态 0 行不回补 + 源码结构断言「扫描与置态两处 WHERE
 *    都带 pay_status=0」与「回补只随置态成功执行」；
 * ⑧ 扫描-置态并发窗口 UPDATE 0 行 → 不回补、不计 closed（restock 只随状态迁移成功执行一次）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库状态机原子性、库存/销量的真实增减、事务回滚——
 * mock 不经过 SQL 执行与事务管理器；WHERE 条件的真库行为由 ⑦ 的结构断言 + 主会话终验兜底。
 */
class OrderCancelRestockTest {

    private static final Long USER_ID = 4L;
    private static final Long OTHER_USER_ID = 5L;
    private static final String ORDER_ID = "ord-1001";
    private static final long DISH_A = 2L;
    private static final long DISH_B = 5L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);

    private OrderServiceImpl orderService;

    /**
     * LambdaUpdateWrapper 的 set() 列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，按 MP 官方入口手工灌一次内存缓存（形态照 OrderCreateIdempotentOwnerTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(orderService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(orderService, "arrivalBatchMapper", arrivalBatchMapper);
        ReflectionTestUtils.setField(orderService, "couponService", couponService);
        // 默认：本人未支付待自提单 + 两件明细（菠菜×2、白菜×1），各用例按需覆盖
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, 0));
        when(orderItemMapper.selectList(any()))
                .thenReturn(List.of(item(DISH_A, 2), item(DISH_B, 1)));
    }

    // ---------------------------------------------------------------- fixtures

    private static Order order(Long userId, Integer payStatus) {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setUserId(userId);
        o.setStatus(OrderServiceImpl.STATUS_PENDING_PICKUP);
        o.setPayStatus(payStatus);
        return o;
    }

    private static OrderItem item(long dishId, int quantity) {
        OrderItem item = new OrderItem();
        item.setDishId(dishId);
        item.setQuantity(quantity);
        return item;
    }

    // ---------------------------------------------------------------- ①②

    /** ① 非本人订单（含 user_id 未认领的存量单）取消 → 404「订单不存在」，不泄露存在性、零写库 */
    @Test
    void 非本人或未认领单取消_404订单不存在_零写库() {
        Order other = order(OTHER_USER_ID, 0);
        Order unclaimed = order(null, 0);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(other, unclaimed);

        BizException ex1 = assertThrows(BizException.class,
                () -> orderService.cancelOrder(ORDER_ID, USER_ID));
        BizException ex2 = assertThrows(BizException.class,
                () -> orderService.cancelOrder(ORDER_ID, USER_ID));

        assertEquals(404, ex1.getCode(), "非本人订单一律 404");
        assertEquals("订单不存在", ex1.getMessage());
        assertEquals(404, ex2.getCode(), "user_id 未认领的存量单同口径 404");
        assertEquals("订单不存在", ex2.getMessage());
        verify(orderMapper, never()).update(any(), any()); // 状态机 UPDATE 未触达
        verify(dishMapper, never()).increaseStock(any(), any());
        verify(couponService, never()).restoreByOrderId(any());
    }

    /** ② 状态机条件 UPDATE 0 行（并发双击/已终态）→ 400「当前状态不可取消」，回补链零触发 */
    @Test
    void 状态机0行_400当前状态不可取消_回补链零触发() {
        when(orderMapper.update(any(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.cancelOrder(ORDER_ID, USER_ID));

        assertEquals(400, ex.getCode(), "走 BizException(String) 的默认 400");
        assertEquals("当前状态不可取消", ex.getMessage());
        verify(orderItemMapper, never()).selectList(any()); // restockAfterCancel 未进入
        verify(dishMapper, never()).increaseStock(any(), any());
        verify(dishMapper, never()).decrementSoldCount(any(), any());
        verify(couponService, never()).restoreByOrderId(any());
    }

    // ---------------------------------------------------------------- ③⑤

    /** ③ 已支付单取消：第二枚条件 UPDATE 必须「条件 pay_status=1 → 置 pay_status=2」退款语义置位 */
    @Test
    void 已支付取消_退款置位payStatus1到2_wrapper绑定值取证() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, 1));
        when(orderMapper.update(any(), any())).thenReturn(1, 1); // 状态机命中 + 退款置位命中

        orderService.cancelOrder(ORDER_ID, USER_ID);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Order>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper, times(2)).update(any(), captor.capture());
        LambdaUpdateWrapper<Order> stateMachine = (LambdaUpdateWrapper) captor.getAllValues().get(0);
        LambdaUpdateWrapper<Order> refund = (LambdaUpdateWrapper) captor.getAllValues().get(1);
        // 先渲染强制参数绑定（MP 的 eq 绑定随段求值落参，F124ReviewIdempotencyTest/AdminFreeServiceTest 同序先例）
        String stateSet = stateMachine.getSqlSet();
        String stateWhere = stateMachine.getSqlSegment();
        String refundSet = refund.getSqlSet();
        String refundWhere = refund.getSqlSegment();
        assertTrue(stateSet.startsWith("status="), "第一枚 UPDATE 必须置 status 列：" + stateSet);
        assertTrue(stateWhere.contains("status"), "第一枚 UPDATE 条件必须含 status 列：" + stateWhere);
        assertTrue(stateMachine.getParamNameValuePairs().containsValue(OrderServiceImpl.STATUS_PENDING_PICKUP),
                "第一枚 UPDATE 条件必须是 status=pending_pickup");
        assertTrue(stateMachine.getParamNameValuePairs().containsValue(OrderServiceImpl.STATUS_CANCELLED),
                "第一枚 UPDATE 必须把状态置为 cancelled");
        assertTrue(refundSet.startsWith("pay_status="), "第二枚 UPDATE 必须置 pay_status 列：" + refundSet);
        assertTrue(refundWhere.contains("pay_status"),
                "退款 UPDATE 的 WHERE 必须以 pay_status 为条件（1→2 语义）：" + refundWhere);
        assertTrue(refund.getParamNameValuePairs().containsValue(1),
                "退款 UPDATE 条件里必须是 pay_status=1（仅已支付单触发退款语义）");
        assertTrue(refund.getParamNameValuePairs().containsValue(2),
                "退款 UPDATE 必须置 pay_status=2（已退款）");
        assertFalse(refund.getParamNameValuePairs().containsValue(OrderServiceImpl.STATUS_CANCELLED),
                "退款 UPDATE 只动 pay_status，不改 status");
        // 退款命中的直接后果：销量回退（⑤ 正向），券回补照常
        verify(dishMapper, times(1)).decrementSoldCount(DISH_A, 2);
        verify(dishMapper, times(1)).decrementSoldCount(DISH_B, 1);
        verify(couponService, times(1)).restoreByOrderId(ORDER_ID);
    }

    /** ⑤ 已支付单取消回退销量：decrementSoldCount 按明细逐条调用 */
    @Test
    void 已支付取消_按明细回退销量() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(USER_ID, 1));
        when(orderMapper.update(any(), any())).thenReturn(1, 1);

        orderService.cancelOrder(ORDER_ID, USER_ID);

        verify(dishMapper, times(1)).decrementSoldCount(DISH_A, 2);
        verify(dishMapper, times(1)).decrementSoldCount(DISH_B, 1);
    }

    // ---------------------------------------------------------------- ④⑥

    /** ④ 未支付取消：increaseStock 按明细（dishId, quantity）逐条回补；退款分支未命中不回销量 */
    @Test
    void 未支付取消_按明细回补库存_不回销量() {
        when(orderMapper.update(any(), any())).thenReturn(1, 0); // 状态机命中；pay_status 1→2 未命中（未支付）

        orderService.cancelOrder(ORDER_ID, USER_ID);

        verify(dishMapper, times(1)).increaseStock(DISH_A, 2);
        verify(dishMapper, times(1)).increaseStock(DISH_B, 1);
        verify(dishMapper, never()).decrementSoldCount(any(), any()); // 未支付单从未计过销量
        verify(orderItemMapper, times(1)).selectList(any()); // 明细恰查一次，回补按其逐条调用
    }

    /** ⑥ 券回补联动：取消成功后 restoreByOrderId 按订单号恰调一次（同一状态迁移仅执行一次） */
    @Test
    void 取消联动券回补_restoreByOrderId按订单号恰调一次() {
        when(orderMapper.update(any(), any())).thenReturn(1, 0);

        orderService.cancelOrder(ORDER_ID, USER_ID);

        verify(couponService, times(1)).restoreByOrderId(ORDER_ID);
    }

    // ---------------------------------------------------------------- ⑦⑧

    /**
     * ⑦ closeTimeoutOrders 已支付单防误杀：支付成功只置 pay_status=1 不改 status，扫描与置态两处
     * WHERE 都必须带 pay_status=0。行为断言（已支付单出现在扫描结果里也因置态 0 行而关不掉）+
     * 源码结构断言（mock 下 SQL 不执行，WHERE 红线用源码取证，手法照 PartnerCommissionTest）。
     */
    @Test
    void 超时关单_已支付单防误杀_扫描与置态双payStatus0闸门在位() throws IOException {
        Order paidStale = order(USER_ID, 1); // 已支付：status 仍是 pending_pickup（settle 只置 pay_status）
        paidStale.setCreateTime(LocalDateTime.now().minusMinutes(OrderServiceImpl.ORDER_TIMEOUT_MINUTES + 1));
        when(orderMapper.selectList(any())).thenReturn(List.of(paidStale));
        when(orderMapper.update(any(), any())).thenReturn(0); // 置态 UPDATE 带 pay_status=0 → 已支付单必 0 行

        assertEquals(0, orderService.closeTimeoutOrders(), "已支付单绝不被超时关单误杀");
        verify(dishMapper, never()).increaseStock(any(), any());
        verify(couponService, never()).restoreByOrderId(any());

        String src = mainJavaSource("com/fresh/service/impl/OrderServiceImpl.java");
        int start = src.indexOf("public int closeTimeoutOrders()");
        int end = src.indexOf("// ---------------- private", start);
        assertTrue(start >= 0 && end > start, "closeTimeoutOrders 方法区可定位");
        String block = src.substring(start, end);
        int first = block.indexOf(".eq(Order::getPayStatus, 0)");
        int second = first < 0 ? -1 : block.indexOf(".eq(Order::getPayStatus, 0)", first + 1);
        assertTrue(first >= 0 && second > first,
                "扫描查询与置态 UPDATE 两处 WHERE 都必须带 pay_status=0（防误杀已支付单）");
        int gated = block.indexOf("if (updated > 0)");
        int restock = block.indexOf("restockAfterCancel");
        assertTrue(gated >= 0 && restock > gated,
                "回补只允许随置态 UPDATE 成功执行（并发窗口 0 行不回补）");
    }

    /** ⑧ 扫描-置态并发窗口：置态 UPDATE 0 行（扫描后恰好完成支付/被取消）→ 不回补、不计 closed */
    @Test
    void 扫描置态并发窗口0行_不回补不计closed() {
        Order stale = order(USER_ID, 0);
        stale.setCreateTime(LocalDateTime.now().minusMinutes(OrderServiceImpl.ORDER_TIMEOUT_MINUTES + 1));
        when(orderMapper.selectList(any())).thenReturn(List.of(stale));
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertEquals(0, orderService.closeTimeoutOrders(), "0 行命中不得计入关单数");

        verify(orderItemMapper, never()).selectList(any());
        verify(dishMapper, never()).increaseStock(any(), any());
        verify(dishMapper, never()).decrementSoldCount(any(), any());
        verify(couponService, never()).restoreByOrderId(any());
    }

    // ---------------------------------------------------------------- 辅助（照 PartnerCommissionTest 手法）

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

    /** 读 main 源码（结构性文本断言基元，照 PartnerCommissionTest#mainJavaSource 手法） */
    private static String mainJavaSource(String relative) throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path file = mainJava.resolve(relative);
        assumeTrue(Files.isRegularFile(file), "未找到 " + relative + "，本条跳过");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
