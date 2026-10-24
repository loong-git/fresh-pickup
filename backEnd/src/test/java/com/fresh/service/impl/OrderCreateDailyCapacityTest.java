package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Store;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-111 日容量拦截单测：满容拒单（含 M月d日 文案）、取消/超时关单释放容量、三种不拦分支、
 * 幂等命中优先于容量、拒单零副作用。
 *
 * <p><b>本类测不成什么</b>：selectCount 的 SQL 口径（store_id + pickup_date + status != 'cancelled'，
 * completed 计入）在 mock 下不执行真 SQL，只能由 logs/verify-daily-capacity.sh 走真库验收——
 * <b>§4 用例 7「completed 占容」因此降级为脚本 7b 步</b>（mock 下与「pending 占容拒单」同构，测了也只是重复），
 * 造过期单（用例 3）在 mock 下零成本，不降级。并发不超发（FOR UPDATE 锁区间）同样归脚本/真库，
 * 单测 mock 不经过锁管理器。
 */
class OrderCreateDailyCapacityTest {

    private static final Long USER_ID = 4L;
    private static final Long STORE_ID = 1L;
    private static final long DISH_ID = 5L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);

    private OrderServiceImpl orderService;

    /**
     * 容量 COUNT 走 LambdaQueryWrapper，其列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 DishSeckillFilterTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        // 与 OrderCreateOffSaleMessageTest 同一套 mock 基建：@Autowired 字段注入、无构造器，逐个灌 mock，不起容器不落库
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(orderService, "arrivalBatchMapper", arrivalBatchMapper);
        ReflectionTestUtils.setField(orderService, "couponService", couponService);
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(2));
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish());
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);
    }

    /** 单件购物车，指向 STORE_ID；clientRequestId 由调用方区分（幂等键） */
    private static OrderCreateDTO cart(String clientRequestId) {
        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(DISH_ID);
        item.setQuantity(1);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId(clientRequestId);
        dto.setPhone("13800001234");
        dto.setStoreId(STORE_ID);
        dto.setItems(List.of(item));
        return dto;
    }

    private static Store store(Integer dailyCapacity) {
        Store s = new Store();
        s.setId(STORE_ID);
        s.setName("容桂示范店");
        s.setAddress("容桂示范路1号");
        s.setDailyCapacity(dailyCapacity);
        return s;
    }

    private static Dish dish() {
        Dish d = new Dish();
        d.setId(DISH_ID);
        d.setName("有机菠菜");
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(1);
        return d;
    }

    private static Order order(String id) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(USER_ID);
        return o;
    }

    /** §4-1 基础拦截：容量 2，第 1、2 单成功，第 3 单 400 且文案含「已约满」+ M月d日 日期 */
    @Test
    void 容量2前两单成功第三单拒且文案含已约满() {
        when(orderMapper.selectCount(any())).thenReturn(0L, 1L, 2L);

        assertNotNull(orderService.createOrder(cart("f111-cap-a"), USER_ID));
        assertNotNull(orderService.createOrder(cart("f111-cap-b"), USER_ID));

        BizException ex = assertThrows(BizException.class, () -> orderService.createOrder(cart("f111-cap-c"), USER_ID));
        assertEquals(400, ex.getCode(), "走 BizException(String) 的默认 400，与「自提点不存在」同款");
        assertTrue(ex.getMessage().contains("已约满"), "拒单文案必须含「已约满」");
        assertTrue(ex.getMessage().matches("该自提点 \\d+月\\d+日 已约满.*"),
                "日期必须是 M月d日 形态，实际=" + ex.getMessage());
    }

    /** §4-2 取消释放：容量 1 拒单 → 取消占容首单 → 新 clientRequestId 单成功 */
    @Test
    void 取消占容单后容量释放新单可下() {
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(1));
        when(orderMapper.selectCount(any())).thenReturn(1L, 0L); // 首查已满 1 单，取消后 0 单

        assertThrows(BizException.class, () -> orderService.createOrder(cart("f111-cancel-b1"), USER_ID));

        when(orderMapper.selectById("ord-first")).thenReturn(order("ord-first"));
        when(orderMapper.update(any(), any())).thenReturn(1, 0); // 状态机置 cancelled 生效；pay_status 回退 0 行（未支付单）
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        orderService.cancelOrder("ord-first", USER_ID);

        assertNotNull(orderService.createOrder(cart("f111-cancel-b2"), USER_ID), "取消释放后同店新单必须能下");
    }

    /** §4-3 超时关单释放：mock 造过期单（create_time 超阈值 1 分钟），closeTimeoutOrders 关单后容量可复用 */
    @Test
    void 超时关单释放后同店可再下单() {
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(1));
        when(orderMapper.selectCount(any())).thenReturn(1L, 0L);

        assertThrows(BizException.class, () -> orderService.createOrder(cart("f111-timeout-b1"), USER_ID));

        Order stale = order("ord-stale");
        stale.setPayStatus(0);
        stale.setStatus(OrderServiceImpl.STATUS_PENDING_PICKUP);
        stale.setCreateTime(LocalDateTime.now().minusMinutes(OrderServiceImpl.ORDER_TIMEOUT_MINUTES + 1));
        when(orderMapper.selectList(any())).thenReturn(List.of(stale));
        when(orderMapper.update(any(), any())).thenReturn(1, 0);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        assertEquals(1, orderService.closeTimeoutOrders(), "过期未支付单应被关成 cancelled");

        assertNotNull(orderService.createOrder(cart("f111-timeout-b2"), USER_ID), "超时关单释放后同店新单必须能下");
    }

    /** §4-4a：storeId 为空 → 不锁、不 COUNT、不拦，链路与改动前一致 */
    @Test
    void storeId为空时容量链路整条不走() {
        OrderCreateDTO dto = cart("f111-nostore");
        dto.setStoreId(null);

        assertNotNull(orderService.createOrder(dto, USER_ID));

        verify(storeMapper, never()).selectByIdForUpdate(any());
        verify(orderMapper, never()).selectCount(any());
    }

    /** §4-4b：dailyCapacity=NULL 视为不限容，不 COUNT 不拦 */
    @Test
    void dailyCapacity为null不拦() {
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(null));

        assertNotNull(orderService.createOrder(cart("f111-nullcap"), USER_ID));

        verify(orderMapper, never()).selectCount(any());
    }

    /** §4-4c：dailyCapacity=0 视为不限容，不 COUNT 不拦 */
    @Test
    void dailyCapacity为0不拦() {
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(0));

        assertNotNull(orderService.createOrder(cart("f111-zerocap"), USER_ID));

        verify(orderMapper, never()).selectCount(any());
    }

    /** §4-5 幂等优先：容量 1 已满，同 clientRequestId 重复提交仍返回首次订单，不锁行不计数不 400 */
    @Test
    void 幂等命中优先于容量校验返回首次订单() {
        when(orderMapper.selectOne(any())).thenReturn(order("ord-first")); // 幂等快查命中（模拟已满容）
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart("f111-dup"), USER_ID);

        assertEquals("ord-first", result.getId(), "重复提交必须返回首次订单");
        verify(storeMapper, never()).selectByIdForUpdate(any()); // 连 store 行锁都不必拿（契约 §2-3 明示允许）
        verify(orderMapper, never()).selectCount(any());         // 已存在的单绝不能被容量拦住
    }

    /** §4-6 拒单零副作用：超容拒单不扣库存、无订单/明细落库，连商品循环都没进 */
    @Test
    void 超容拒单零副作用() {
        when(storeMapper.selectByIdForUpdate(STORE_ID)).thenReturn(store(1));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> orderService.createOrder(cart("f111-full"), USER_ID));

        verify(dishMapper, never()).decreaseStock(any(), any()); // dish.stock 零扣减（校验前置于扣库存）
        verify(dishMapper, never()).selectById(any());           // 商品循环未进入
        verify(orderMapper, never()).insert(any(Order.class));   // 无订单落库
        verify(orderItemMapper, never()).insert(any(OrderItem.class)); // 无明细落库
    }
}
