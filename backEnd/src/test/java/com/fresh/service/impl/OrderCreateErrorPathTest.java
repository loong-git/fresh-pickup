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
import com.fresh.entity.UserCoupon;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 审计轮 3 P1 缺口 #3：下单错误路径族补测（OrderServiceImpl#createOrder :175-208 校验段、
 * :236-265 券校验/insert/核销段此前仅有正向与幂等覆盖，错误路径零覆盖）。
 *
 * <p>照 OrderCreateIdempotentOwnerTest 的桩范式：不起容器、不落库、{@code @Autowired} 字段逐个灌 mock，
 * 单件购物车不传 storeId（避开锁读与容量链路，聚焦错误路径）；{@code @BeforeAll} 手工灌
 * Order/OrderItem 的 TableInfo 内存缓存。
 *
 * <p>覆盖面（6 例）：
 * ① dish 查不到 → 400「包含已下架商品，请重新选择」，零写库；
 * ② 审核驳回品（onSale=1 且 audit_status=rejected，F-116 专属分支）→ 与下架同款 400 文案，
 *    且校验前置于扣库存（decreaseStock never）；
 * ③ 限购超量（dish.limit_buy=2 传 3）→ 400「「X」限购2件」，扣库存零触达；
 * ④ 库存不足（decreaseStock 0 行）→ 400「「X」库存不足」且无半写（主单/明细零落库）；
 * ⑤ getUsable 抛错 → 异常原样传播（整单回滚语义），insert 未触达（券校验在 insert 之前）；
 * ⑥ markUsed 返回 0（券并发核销失败）→ 400「优惠券已被使用，请重新下单」，无任何回补动作，
 *    抵扣后金额 8.00 落库快照正确（真库半态由事务回滚兜底，mock 侧断言调用序与零回补）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库条件 UPDATE 行数语义、事务回滚对已扣库存/已插主单的
 * 真实复原——mock 不经过事务管理器，由主会话终验兜底（与 OrderCreateIdempotentOwnerTest 同一登记）。
 */
class OrderCreateErrorPathTest {

    private static final Long USER_ID = 4L;
    private static final long DISH_ID = 5L;
    private static final Long COUPON_ID = 9L;
    private static final String KEY = "err-key-1";

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final ArrivalBatchMapper arrivalBatchMapper = mock(ArrivalBatchMapper.class);
    private final CouponService couponService = mock(CouponService.class);
    private final SeckillSessionResolver seckillSessionResolver = mock(SeckillSessionResolver.class);

    private OrderServiceImpl orderService;

    /**
     * createOrder 的幂等快查/容量/券链路涉及 Lambda wrapper 与实体 TableInfo（生产由 MP 启动装配），
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
        ReflectionTestUtils.setField(orderService, "seckillSessionResolver", seckillSessionResolver);
        // 默认健康桩：幂等快查未命中、商家 active、在售品可扣库存；各用例按需覆盖
        when(orderMapper.selectOne(any())).thenReturn(null);
        Merchant activeMerchant = new Merchant();
        activeMerchant.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(activeMerchant);
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish());
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);
    }

    // ---------------------------------------------------------------- fixtures

    private static OrderCreateDTO cart(int quantity) {
        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(DISH_ID);
        item.setQuantity(quantity);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId(KEY);
        dto.setPhone("13800001234");
        dto.setItems(List.of(item));
        return dto;
    }

    private static Dish dish() {
        Dish d = new Dish();
        d.setId(DISH_ID);
        d.setName("有机菠菜");
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(1);
        d.setMerchantId(1L);
        return d;
    }

    /** insert 桩：mock ASSIGN_ID 回填雪花订单号，返回 1 行 */
    private void stubInsertAssignsId(String id) {
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(id);
            return 1;
        });
    }

    // ---------------------------------------------------------------- ①②③

    /** ① dish 查不到（已删品残留购物车）→ 400「包含已下架商品，请重新选择」，零写库 */
    @Test
    void dish查不到_400包含已下架商品_零写库() {
        when(dishMapper.selectById(DISH_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart(1), USER_ID));

        assertEquals(400, ex.getCode(), "走 BizException(String) 的默认 400");
        assertEquals("包含已下架商品，请重新选择", ex.getMessage());
        verify(dishMapper, never()).decreaseStock(any(), any()); // 未进扣库存
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    /** ② 审核驳回品（onSale=1 且 audit_status=rejected）下单拦截：与下架同款 400 文案，且前置于扣库存（F-116） */
    @Test
    void 审核驳回品_同款下架文案拦截_前置扣库存零触达() {
        Dish rejected = dish();
        rejected.setAuditStatus(Dish.AUDIT_REJECTED); // onSale 仍为 1 → 命中 F-116 专属分支而非下架分支
        when(dishMapper.selectById(DISH_ID)).thenReturn(rejected);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart(1), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「有机菠菜」已下架，请重新选择", ex.getMessage(), "F-116 §2.5：驳回品对用户表现即下架品，文案不新增");
        verify(dishMapper, never()).decreaseStock(any(), any()); // 致命校验前置于扣库存（零副作用）
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    /** ③ 限购超量（limit_buy=2 传 3）→ 400「「X」限购2件」，扣库存零触达 */
    @Test
    void 限购超量_400限购件数文案_扣库存零触达() {
        Dish limited = dish();
        limited.setLimitBuy(2);
        when(dishMapper.selectById(DISH_ID)).thenReturn(limited);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart(3), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「有机菠菜」限购2件", ex.getMessage(), "限购值取 dish.limit_buy 真实列");
        verify(dishMapper, never()).decreaseStock(any(), any());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    // ---------------------------------------------------------------- ④⑤⑥

    /** ④ 库存不足：decreaseStock 条件 UPDATE 0 行 → 400「「X」库存不足」且无半写（主单/明细零落库） */
    @Test
    void 库存不足_0行400库存不足_无半写() {
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(cart(1), USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("「有机菠菜」库存不足", ex.getMessage());
        verify(orderMapper, never()).insert(any(Order.class)); // 主单零落库（insert 在扣库存之后）
        verify(orderItemMapper, never()).insert(any(OrderItem.class)); // 明细零落库
    }

    /** ⑤ getUsable 抛错 → 异常原样传播（真库随事务回滚已扣库存），insert 零触达（券校验在 insert 之前） */
    @Test
    void 券校验抛错_异常传播_主单明细零落库() {
        OrderCreateDTO dto = cart(1);
        dto.setCouponId(COUPON_ID);
        // 文案实读 CouponServiceImpl#getUsable（归属不符分支）
        when(couponService.getUsable(eq(USER_ID), eq(COUPON_ID), any()))
                .thenThrow(new BizException("优惠券不存在或不可用"));

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(dto, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("优惠券不存在或不可用", ex.getMessage(), "getUsable 的业务异常必须原样传播，不被吞不换文案");
        verify(dishMapper, times(1)).decreaseStock(DISH_ID, 1); // 已扣一次：真库随事务回滚复原
        verify(orderMapper, never()).insert(any(Order.class)); // insert 在券校验之后，未触达
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
    }

    /** ⑥ markUsed 返回 0（券并发核销失败）→ 400 且无任何回补动作；抵扣后金额落库快照正确 */
    @Test
    void 券并发核销失败_markUsed0行_400_无回补() {
        OrderCreateDTO dto = cart(1);
        dto.setCouponId(COUPON_ID);
        UserCoupon coupon = new UserCoupon();
        coupon.setId(COUPON_ID);
        coupon.setUserId(USER_ID);
        coupon.setAmount(new BigDecimal("2.00"));
        when(couponService.getUsable(eq(USER_ID), eq(COUPON_ID), any())).thenReturn(coupon);
        stubInsertAssignsId("ord-err-6");
        when(couponService.markUsed(eq(COUPON_ID), eq(USER_ID), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> orderService.createOrder(dto, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("优惠券已被使用，请重新下单", ex.getMessage());
        verify(couponService, times(1)).markUsed(COUPON_ID, USER_ID, "ord-err-6"); // 核销带的是落库后的订单号
        verify(orderMapper, times(1)).insert(any(Order.class)); // insert 先于核销：真库半态随事务回滚
        verify(orderItemMapper, times(1)).insert(any(OrderItem.class));
        verify(couponService, never()).restoreByOrderId(any()); // 失败路径无任何券回补动作
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals(0, new BigDecimal("8.00").compareTo(orderCaptor.getValue().getTotalPrice()),
                "服务端重算 10.00 − 券 2.00 = 8.00 落库快照");
    }
}
