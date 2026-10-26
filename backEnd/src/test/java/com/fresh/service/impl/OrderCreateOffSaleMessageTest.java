package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.entity.Order;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单撞上已下架商品时的 400 文案单测（F-74 B2）。
 *
 * <p><b>要钉的事</b>：下架这条 400 必须报出是哪一件。同一个方法里限购与库存不足两条 400 本来就带商品名，
 * 只下架那条不带，用户面对一车商品无从知道该剔哪一个。
 *
 * <p><b>顺带钉住前置顺序</b>：下架校验发生在扣库存之前——错序会先做一次注定回滚的 UPDATE，
 * 并把「库存不足」这个更误导的文案抛出来。
 *
 * <p><b>本类测不成什么</b>：文案经 {@code GlobalExceptionHandler} 装进信封后的出参形态、
 * 以及真库里该商品是否真的处于 {@code on_sale=0}，归接口验收。
 */
class OrderCreateOffSaleMessageTest {

    private static final Long USER_ID = 4L;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        // 与 OrderGoodsArrivedFlagTest 同理：@Autowired 字段注入、无构造器，逐个灌 mock，不起容器不落库
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(orderService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(orderService, "arrivalBatchMapper", arrivalBatchMapper);
        ReflectionTestUtils.setField(orderService, "couponService", couponService);
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        // F-122：默认商家 active；本类断言的「已下架」校验前置于商家状态判定，原语义不变
        Merchant activeMerchant = new Merchant();
        activeMerchant.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(activeMerchant);
    }

    /** 购物车里只有一件：dishId + 数量 */
    private static OrderCreateDTO cartOf(long dishId, int quantity) {
        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(dishId);
        item.setQuantity(quantity);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId("f74-offsale-" + dishId);
        dto.setPhone("13800001234");
        dto.setItems(List.of(item));
        return dto;
    }

    private static Dish dish(long id, String name, Integer onSale) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(onSale);
        return d;
    }

    @Test
    void 下架件下单的400文案点名报出是哪一件() {
        when(dishMapper.selectById(5L)).thenReturn(dish(5L, "有机菠菜", 0));

        BizException ex = assertThrows(BizException.class, () -> orderService.createOrder(cartOf(5L, 1), USER_ID));

        assertEquals("「有机菠菜」已下架，请重新选择", ex.getMessage(),
                "同方法内限购与库存不足两条 400 都带商品名，下架这条不能只说「包含已下架商品」");
        assertEquals(400, ex.getCode(), "仍走 BizException(String) 的 400，不新增状态码");
        verify(dishMapper, never()).decreaseStock(any(), any());
        verify(orderMapper, never()).insert(any(Order.class));
    }
}
