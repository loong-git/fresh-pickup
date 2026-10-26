package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-122 下单侧商家封禁拦截单测（照 OrderCreateDailyCapacityTest 的 mock 基建：直接 new + 反射注入）。
 *
 * <p><b>要证明的五件事</b>：
 * ① 商家 active → 正常下单、扣库存、落主单；
 * ② 商家 suspended / terminated / 行缺失 → 400 精确文案「「菜名」所属商家暂停营业，请重新选择」，
 *    且扣库存与订单/明细落库零调用（拦截前置于一切写操作，零副作用）；
 * ③ 购物车两件同商家商品 → {@code merchantMapper.selectById} 只查 1 次（请求内缓存）；
 * ④ 幂等快查命中既有订单 → 完全不查 merchant，商家后来被封也不影响幂等返回；
 * ⑤ 封禁商家商品仍可被 dishMapper 读出（C 端可见性零改动由本链路不碰查询条件侧证）。
 *
 * <p><b>本类测不成什么</b>：mapper 是 mock，真库里 merchant.status 的实际取值与“封禁后商品是否仍在
 * 列表/搜索里”由 {@code logs/verify-ban-system.sh} 真库验收——本类只能钉住下单分支的判定与副作用边界。
 */
class OrderCreateMerchantBanTest {

    private static final Long USER_ID = 4L;
    private static final long MERCHANT_ID = 1L;
    private static final long DISH_ID = 5L;
    private static final long DISH_ID_2 = 6L;
    private static final String DISH_NAME = "有机菠菜";

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);

    private OrderServiceImpl orderService;

    /**
     * 幂等快查与商家状态缓存都走 MP wrapper/实体列解析，依赖实体 TableInfo（生产由 MP 启动扫 mapper 装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 OrderCreateDailyCapacityTest）。
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
        // 与 OrderCreateDailyCapacityTest 同一套 mock 基建：@Autowired 字段注入、无构造器，逐个灌 mock，不起容器不落库
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(orderService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(orderService, "arrivalBatchMapper", arrivalBatchMapper);
        ReflectionTestUtils.setField(orderService, "couponService", couponService);
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        when(dishMapper.decreaseStock(any(), anyInt())).thenReturn(1);
    }

    /** 单件购物车（指向 DISH_ID） */
    private static OrderCreateDTO cart(String clientRequestId) {
        return cart(clientRequestId, DISH_ID);
    }

    /** 指定菜品 id 的购物车；多件商品用多次调用 items 组装 */
    private static OrderCreateDTO cart(String clientRequestId, long... dishIds) {
        List<OrderCreateDTO.OrderItemDTO> items = new java.util.ArrayList<>();
        for (long dishId : dishIds) {
            OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
            item.setDishId(dishId);
            item.setQuantity(1);
            items.add(item);
        }
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId(clientRequestId);
        dto.setPhone("13800001234");
        dto.setItems(items);
        return dto;
    }

    private static Dish dish(long id, String name) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(1);
        d.setMerchantId(MERCHANT_ID);
        return d;
    }

    private static Merchant merchant(String status) {
        Merchant m = new Merchant();
        m.setId(MERCHANT_ID);
        m.setName("F-122测试商家");
        m.setStatus(status);
        return m;
    }

    private static Order existingOrder(String id) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(USER_ID);
        return o;
    }

    /** ① 商家 active：链路照常，扣库存 + 主单落库各一次 */
    @Test
    void 商家active时下单成功() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant("active"));
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(DISH_ID, DISH_NAME));
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        Order result = orderService.createOrder(cart("f122-active"), USER_ID);

        assertNotNull(result, "active 商家商品必须能正常下单");
        verify(merchantMapper).selectById(MERCHANT_ID);
        verify(dishMapper).decreaseStock(DISH_ID, 1);
        verify(orderMapper).insert(any(Order.class));
    }

    /** ②a 商家 suspended：400 精确文案，扣库存/主单/明细零调用 */
    @Test
    void 商家suspended时拒单且零副作用() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant("suspended"));
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(DISH_ID, DISH_NAME));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart("f122-suspended"), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「" + DISH_NAME + "」所属商家暂停营业，请重新选择", ex.getMessage());
        verify(dishMapper).selectById(DISH_ID); // 商品本身照常读出（封禁不改 C 端可见性），只是下单被拦
        verify(dishMapper, never()).decreaseStock(any(), anyInt());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    /** ②b 商家 terminated：与 suspended 同文案同零副作用（清退商家同样不可下单） */
    @Test
    void 商家terminated时拒单且零副作用() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant("terminated"));
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(DISH_ID, DISH_NAME));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart("f122-terminated"), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「" + DISH_NAME + "」所属商家暂停营业，请重新选择", ex.getMessage());
        verify(dishMapper, never()).decreaseStock(any(), anyInt());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    /** ②c 商家行缺失（selectById 返回 null）：fail-closed 同文案同零副作用 */
    @Test
    void 商家行缺失时拒单且零副作用() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(null);
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(DISH_ID, DISH_NAME));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart("f122-missing"), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「" + DISH_NAME + "」所属商家暂停营业，请重新选择", ex.getMessage());
        verify(dishMapper, never()).decreaseStock(any(), anyInt());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    /** ③ 同商家两件商品：请求内缓存生效，merchantMapper.selectById 只查 1 次 */
    @Test
    void 同商家两件商品只查一次商家() {
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant("active"));
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(DISH_ID, DISH_NAME));
        when(dishMapper.selectById(DISH_ID_2)).thenReturn(dish(DISH_ID_2, "有机番茄"));
        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        Order result = orderService.createOrder(cart("f122-two-items", DISH_ID, DISH_ID_2), USER_ID);

        assertNotNull(result);
        verify(merchantMapper, times(1)).selectById(MERCHANT_ID);
        verify(dishMapper).decreaseStock(DISH_ID, 1);
        verify(dishMapper).decreaseStock(DISH_ID_2, 1);
        verify(orderItemMapper, times(2)).insert(any(OrderItem.class));
    }

    /** ④ 幂等快查命中：直接返回既有订单，完全不查 merchant（商家后来被封也不影响幂等返回） */
    @Test
    void 幂等命中时完全不查商家() {
        when(orderMapper.selectOne(any())).thenReturn(existingOrder("ord-first"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart("f122-dup"), USER_ID);

        assertEquals("ord-first", result.getId(), "重复提交必须返回首次订单");
        verify(merchantMapper, never()).selectById(any());
        verify(dishMapper, never()).selectById(any());
        verify(dishMapper, never()).decreaseStock(any(), anyInt());
        verify(orderMapper, never()).insert(any(Order.class));
    }
}
