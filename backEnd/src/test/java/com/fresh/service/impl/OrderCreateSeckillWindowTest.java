package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import com.fresh.service.SeckillSessionResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 P2-D 秒杀计价窗口判定单测（用户拍板①：计价处按服务端时钟判场次窗口，窗口外按原价，
 * 秒杀标识 dish.seckill 本身不动——展示/结算/销量口径不变，只影响计价；照
 * OrderCreateDailyCapacityTest 的桩范式：不起容器、不落库、字段灌 mock）。
 *
 * <p><b>要证明的事</b>：
 * <ul>
 *   <li>① 窗口内（SeckillSessionResolver.inWindow=true，与 SeckillController 同一判定源）→
 *       秒杀件按 seckill_price 计价（存量行为回归）；</li>
 *   <li>② 窗口外（inWindow=false）→ 同一件秒杀 dish 按 price 原价计价，明细成交单价快照同口径；</li>
 *   <li>③ 秒杀标识不动：dish.seckill 字段计价分支之外零改动（本类只锁计价金额，标识透传
 *       由 DishService 读路径负责，不在本类断言范围）。</li>
 * </ul>
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：resolver 与字典组的真实读取（Caffeine 缓存、
 * m25 回填词条）——resolver 自身口径由 SeckillSessionResolverTest 直测；本类 mock resolver
 * 锁 OrderServiceImpl 侧「计价判据引用了窗口判定」这一接缝。
 */
class OrderCreateSeckillWindowTest {

    private static final Long USER_ID = 4L;
    private static final long DISH_ID = 5L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);
    private final SeckillSessionResolver seckillSessionResolver = mock(SeckillSessionResolver.class);

    private OrderServiceImpl orderService;

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
        ReflectionTestUtils.setField(orderService, "seckillSessionResolver", seckillSessionResolver);
        Merchant activeMerchant = new Merchant();
        activeMerchant.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(activeMerchant);
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        when(dishMapper.selectById(DISH_ID)).thenReturn(seckillDish());
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId("ord-seckill");
            return 1;
        });
    }

    /** 单件秒杀购物车（seckill=1 + seckillPrice=6.00，price=10.00）；无 storeId 聚焦计价 */
    private static OrderCreateDTO cart(String clientRequestId) {
        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(DISH_ID);
        item.setQuantity(1);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId(clientRequestId);
        dto.setPhone("13800001234");
        dto.setItems(List.of(item));
        return dto;
    }

    private static Dish seckillDish() {
        Dish d = new Dish();
        d.setId(DISH_ID);
        d.setName("秒杀菠菜");
        d.setPrice(new BigDecimal("10.00"));
        d.setSeckillPrice(new BigDecimal("6.00"));
        d.setSeckill(1);
        d.setStock(20);
        d.setOnSale(1);
        return d;
    }

    /** ① 窗口内 → 按 seckill_price 计价（存量行为回归） */
    @Test
    void 窗口内秒杀件按seckill_price计价() {
        when(seckillSessionResolver.inWindow(any())).thenReturn(true);

        Order result = orderService.createOrder(cart("p2d-in"), USER_ID);

        assertNotNull(result);
        assertTrue(new BigDecimal("6.00").compareTo(result.getTotalPrice()) == 0,
                "窗口内按秒杀价计价：expected=6.00 but was=" + result.getTotalPrice().toPlainString());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(1)).insert(captor.capture());
        assertEquals(new BigDecimal("6.00"), captor.getValue().getItems().get(0).getPrice(),
                "明细成交单价快照同口径（秒杀件落秒杀价）");
    }

    /** ② 窗口外 → 按 price 原价计价（P11-2 P2-D 用户拍板①） */
    @Test
    void 窗口外秒杀件按原价计价() {
        when(seckillSessionResolver.inWindow(any())).thenReturn(false);

        Order result = orderService.createOrder(cart("p2d-out"), USER_ID);

        assertNotNull(result);
        assertTrue(new BigDecimal("10.00").compareTo(result.getTotalPrice()) == 0,
                "窗口外必须按原价计价：expected=10.00 but was=" + result.getTotalPrice().toPlainString());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(1)).insert(captor.capture());
        assertEquals(new BigDecimal("10.00"), captor.getValue().getItems().get(0).getPrice(),
                "明细成交单价快照同口径（窗口外落原价）");
    }

    /** ③ 计价分支确实经过窗口判定（inWindow 恰被调用一次）——防止接缝被静默移除 */
    @Test
    void 秒杀件计价必经窗口判定恰好一次() {
        when(seckillSessionResolver.inWindow(any())).thenReturn(false);

        orderService.createOrder(cart("p2d-seam"), USER_ID);

        verify(seckillSessionResolver, times(1)).inWindow(any());
    }
}
