package com.fresh.service.impl;

import com.fresh.dto.FreeClaimDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.FreeActivity;
import com.fresh.entity.Order;
import com.fresh.entity.Store;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.FreeActivityMapper;
import com.fresh.mapper.FreeClaimMapper;
import com.fresh.mapper.FreeGoodsPoolMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.common.BizException;
import com.fresh.pay.PayService;
import com.fresh.pay.PaymentSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 免费领 0 元单的结算走向单测（F-21.5 Step 2a）：Mockito mock 掉八个 Mapper + {@link PaymentSettlement}，
 * 直接 new FreeServiceImpl 并按字段名灌进 @Autowired 槽位，**不连库、不起 Spring 容器**
 * （形态照 {@code PaymentSettlementTest} / {@code RecommendServiceTest}：纯内存断言，期望值手工可推）。
 *
 * 要钉住的四件事：
 * ① 0 元领取走到结算接缝：{@code settlement.settle(order, payTime)} 恰调一次，送进去的是**刚 INSERT 的那一行**
 *    （同订单号、pay_status=0），且是回读出来的 DB 行（totalPrice 标度 0.00）——这正是 Step 2a 选「回读一次」
 *    的理由：改前 {@code MockPayService#payResult} 内部本来就 selectById 一次，SELECT 条数与响应字段值双双不变；
 * ② 免费领不再按 {@code PayService} 接口注入渠道实现（F-21.4 第 4 条 🔴：一旦 {@code pay.provider=wx}，
 *    经接口注入会拿到 WxPayService → 无条件抛「微信支付未配置，请联系管理员」→ 0 元领取全挂）。
 *    本类的 @Autowired 字段集合里只剩 PaymentSettlement，渠道开关从此打不到免费领；
 * ③ 判定顺序不动：门槛未满足时在进接缝之前就拒（文案逐字「再挑选40.10元，今天带走免费商品」，
 *    与上线任务文档 F-21.4 G-F 行的实测读数同值），结算接缝零交互、连回读都不发生。
 * ④ F-80/S2 建单必须有店：服务端自选选空（全店停业）即抛 BizException，主单连 INSERT 都不发——
 *    收口前这条支路会静默落 store_id=NULL，那种单核销侧永远查不到。
 *
 * 未覆盖（如实登记）：{@code @Transactional} 回滚语义（取货码失败整单回滚）归 PaymentSettlementTest 与
 * 主会话端到端；本单测的 mock 不执行 SQL，也不经过 MP 的 MetaObjectHandler 填充。
 */
class FreeServiceImplSettleTest {

    private static final Long USER_ID = 4L;
    private static final Long FREE_DISH_ID = 13L;
    private static final Long CART_DISH_ID = 10L;
    private static final BigDecimal THRESHOLD = new BigDecimal("50.00");

    private final FreeActivityMapper freeActivityMapper = mock(FreeActivityMapper.class);
    private final FreeClaimMapper freeClaimMapper = mock(FreeClaimMapper.class);
    private final FreeGoodsPoolMapper freeGoodsPoolMapper = mock(FreeGoodsPoolMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PaymentSettlement settlement = mock(PaymentSettlement.class);

    private FreeServiceImpl freeService;

    @BeforeEach
    void setUp() {
        freeService = new FreeServiceImpl();
        // FreeServiceImpl 是 @Autowired 字段注入（无构造器），故逐个灌 mock；RecommendServiceTest 那种
        // 构造器注入在这里换成 ReflectionTestUtils（spring-test，随 spring-boot-starter-test 已在测试类路径）
        ReflectionTestUtils.setField(freeService, "freeActivityMapper", freeActivityMapper);
        ReflectionTestUtils.setField(freeService, "freeClaimMapper", freeClaimMapper);
        ReflectionTestUtils.setField(freeService, "freeGoodsPoolMapper", freeGoodsPoolMapper);
        ReflectionTestUtils.setField(freeService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(freeService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(freeService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(freeService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(freeService, "userMapper", userMapper);
        ReflectionTestUtils.setField(freeService, "settlement", settlement);
    }

    /**
     * 铺到「建单 + 扣库存完成、即将结算」：资格/门槛/限量/白名单各按通过态桩好。
     *
     * @param cartDishPrice 购物车里那件商品的现价（门槛合计 = price × quantity 由服务端重算）
     */
    private void stubPathUpToSettlement(String cartDishPrice) {
        FreeActivity activity = new FreeActivity();
        activity.setId(1L);
        activity.setStatus(FreeActivity.STATUS_ONLINE);
        activity.setThreshold(THRESHOLD);
        activity.setDailyQuota(20);
        when(freeActivityMapper.selectOne(any())).thenReturn(activity);
        when(freeActivityMapper.tryClaimQuota(eq(1L), any(LocalDate.class))).thenReturn(1);
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);      // dishId 在候选池白名单内
        when(freeClaimMapper.selectCount(any())).thenReturn(0L);          // 今日未领（uk_user_date 未占）
        when(dishMapper.selectById(FREE_DISH_ID)).thenReturn(dish(FREE_DISH_ID, "9.90", 5));
        when(dishMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(dish(CART_DISH_ID, cartDishPrice, 99)));
        when(dishMapper.decreaseStock(eq(FREE_DISH_ID), eq(1))).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setPhone("13800001234");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(orderMapper.selectOne(any())).thenReturn(null);              // client_request_id 未命中 → 走新建
        when(storeMapper.selectOne(any())).thenReturn(openStore());        // F-80 起建单的必需前提：选不出营业门店即显式失败（反向见 ④）
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        // 回读「刚插入的那一行」：pay_status=0、total_price 按 DECIMAL(10,2) 读出 0.00（改前 payResult 内那次 selectById 同值）
        when(orderMapper.selectById(any())).thenAnswer(inv -> dbRowOf(inv.getArgument(0)));
        when(settlement.settle(any(Order.class), any(LocalDateTime.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 营业中门店行（服务端自选的那一家；F-80 起它是建单的前提而非可选项） */
    private Store openStore() {
        Store s = new Store();
        s.setId(1L);
        s.setName("自提点一");
        s.setAddress("广东省佛山市顺德区");
        s.setStatus("open");
        return s;
    }

    /** 在售商品行（免费商品与购物车商品共用构造，仅价格/库存不同） */
    private Dish dish(Long id, String price, int stock) {
        Dish d = new Dish();
        d.setId(id);
        d.setName("商品" + id);
        d.setPrice(new BigDecimal(price));
        d.setStock(stock);
        d.setOnSale(1);
        d.setMerchantId(1L);
        return d;
    }

    /** 模拟库里那一行：单号取入参，其余按 FreeServiceImpl 刚写入的 0 元待支付态 */
    private Order dbRowOf(String orderId) {
        Order row = new Order();
        row.setId(orderId);
        row.setUserId(USER_ID);
        row.setStatus(OrderServiceImpl.STATUS_PENDING_PICKUP);
        row.setPayStatus(0);
        row.setTotalPrice(new BigDecimal("0.00"));
        row.setPhone("13800001234");
        row.setAddress("");
        row.setPickupDate(LocalDate.now().plusDays(1));
        return row;
    }

    private FreeClaimDTO.ClaimItemDTO cartItem(Long dishId, int quantity) {
        FreeClaimDTO.ClaimItemDTO it = new FreeClaimDTO.ClaimItemDTO();
        it.setDishId(dishId);
        it.setQuantity(quantity);
        return it;
    }

    // —— ① 0 元单进的是结算接缝，且进去的是刚建的那一行 ——

    @Test
    void claimSettlesTheZeroYuanOrderThroughPaymentSettlement() {
        stubPathUpToSettlement("25.00"); // 25.00 × 2 = 50.00 ≥ threshold 50.00（门槛按 dish 现价重算）

        Order result = freeService.claim(USER_ID, FREE_DISH_ID, List.of(cartItem(CART_DISH_ID, 2)));

        ArgumentCaptor<Order> settled = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<LocalDateTime> payTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(settlement, times(1)).settle(settled.capture(), payTime.capture());
        assertNotNull(payTime.getValue(), "payTime 由调用方（免费领事务入口）取一次");

        ArgumentCaptor<Order> inserted = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(inserted.capture());
        assertEquals(inserted.getValue().getId(), settled.getValue().getId(),
                "送进接缝的必须是刚 INSERT 的那一单（同订单号）");
        assertEquals(Integer.valueOf(0), settled.getValue().getPayStatus(), "接缝收到的是 pay_status=0 的待支付行");
        assertEquals(new BigDecimal("0.00"), settled.getValue().getTotalPrice(),
                "回读的是 DB 行（DECIMAL(10,2) 读出 0.00）：响应标度与 Step 2a 之前逐字相同");
        assertEquals(USER_ID, settled.getValue().getUserId());

        // SELECT 条数不变：改前那一次 selectById 在 payResult 内部，现在由本类自己做一次，不多不少
        verify(orderMapper, times(1)).selectById(any());

        // 响应形状不变：settle 的返回对象原样出去，items 照旧挂上那条 0 元明细（取货码回写由接缝负责，S3-0）
        assertSame(settled.getValue(), result);
        assertEquals(1, result.getItems().size());
        assertEquals(0, result.getItems().get(0).getPrice().compareTo(BigDecimal.ZERO),
                "0 元单明细的成交单价快照仍为 0");
        assertEquals(Integer.valueOf(1), result.getItems().get(0).getQuantity());
    }

    // —— ② 依赖面上已无任何支付渠道实现：pay.provider=wx 打不到免费领 ——

    @Test
    void freeServiceHasNoPayServiceDependencyAnymore() {
        List<Class<?>> autowiredTypes = Arrays.stream(FreeServiceImpl.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Autowired.class))
                .map(Field::getType)
                .toList();

        assertTrue(autowiredTypes.contains(PaymentSettlement.class),
                "免费领必须直接依赖结算接缝：" + autowiredTypes);
        assertTrue(autowiredTypes.stream().noneMatch(PayService.class::isAssignableFrom),
                "免费领不得再按 PayService 接口注入渠道实现（F-21.4 第 4 条：provider=wx 时 0 元单会落到 WxPayService）："
                        + autowiredTypes);
    }

    // —— ③ 门槛未满足：进接缝之前就拒，文案逐字，结算接缝零交互 ——

    @Test
    void thresholdNotMetIsRejectedBeforeAnySettlement() {
        stubPathUpToSettlement("9.90"); // 9.90 × 1 < 50.00 → 差额 40.10

        BizException ex = assertThrows(BizException.class,
                () -> freeService.claim(USER_ID, FREE_DISH_ID, List.of(cartItem(CART_DISH_ID, 1))));

        assertEquals("再挑选40.10元，今天带走免费商品", ex.getMessage(), "凑单引导文案逐字不动（M9）");
        verify(settlement, never()).settle(any(Order.class), any(LocalDateTime.class));
        verify(orderMapper, never()).selectById(any());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    // —— ④ F-80/S2：一家营业中门店都选不出来时显式失败，不再静默插 store_id=NULL ——
    // 与 fixture ①（有店→走到结算）配成双向：只验一边等于没验。
    @Test
    void claimFailsLoudlyWhenNoOpenStoreIsSelectable() {
        stubPathUpToSettlement("25.00");                        // 门槛/限量/白名单全通过，唯独没有营业门店
        when(storeMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> freeService.claim(USER_ID, FREE_DISH_ID, List.of(cartItem(CART_DISH_ID, 2))));

        assertEquals("自提点不存在，请重新选择", ex.getMessage(), "复用下单主链路既有串，本片零新增文案");
        verify(orderMapper, never()).insert(any(Order.class));   // NULL 单连插都不许插
        verify(settlement, never()).settle(any(Order.class), any(LocalDateTime.class));
    }
}
