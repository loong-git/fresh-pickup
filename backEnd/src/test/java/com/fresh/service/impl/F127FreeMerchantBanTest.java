package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.dto.FreeClaimDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.FreeActivity;
import com.fresh.entity.FreeClaim;
import com.fresh.entity.FreeGoodsPool;
import com.fresh.entity.Merchant;
import com.fresh.entity.Order;
import com.fresh.entity.Store;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.FreeActivityMapper;
import com.fresh.mapper.FreeClaimMapper;
import com.fresh.mapper.FreeGoodsPoolMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.pay.PaymentSettlement;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-127 免费领链路封禁商家拦截单测（纯 mock 姿势照 {@code F126FreeAuditTest} / {@code FreeServiceImplSettleTest}：
 * mock 掉 Mapper 与 {@link PaymentSettlement}，直接 new FreeServiceImpl 按字段名灌 @Autowired 槽位，
 * 不连库、不起 Spring 容器、期望值手工可推）。
 *
 * <p>要钉住的三件事：
 * ① 候选池混合池（suspended 商家件排池首、active 商家件在后）经 current 下发只含 active 商家的商品——
 *    封禁商家的商品既不进自选列表，也不顶进默认免费商品位（C 端免费页即下发渲染，前端零改动即生效），
 *    且商家状态一次批量查（消 N+1）；
 * ② claim 撞上 suspended 商家在售件与商家行缺失件 → 400「「X」所属商家暂停营业，请重新选择」逐字——
 *    与 OrderServiceImpl#createOrder 的 F-122 拦截同句，不新造文案；且拦截前置于门槛重算与一切写操作；
 * ③ 对照：active 商家在售件照常领成 0 元单——防 ①② 的判据写成恒真/恒假时本类还假绿。
 *
 * <p>本类测不成什么（免得留假收据）：mapper 是 mock，「真库里 suspended 商家件被批量捞出再被过滤」
 * 的 SQL 行为、@Transactional 回滚、settle 履约细节不在本类证明力之内（后者归 PaymentSettlementTest
 * 与 FreeServiceImplSettleTest）；poolDishes 经 current(null) 走匿名视角驱动（putPersonal 对
 * userId=null 走中性缺省，不触个人数据桩），个人数据分支另有契约覆盖。
 */
class F127FreeMerchantBanTest {

    private static final Long USER_ID = 4L;
    private static final Long SUSPENDED_MERCHANT_ID = 9L;
    private static final Long ACTIVE_MERCHANT_ID = 1L;
    private static final Long MISSING_MERCHANT_ID = 99L;
    private static final Long SUSPENDED_DISH_ID = 11L;
    private static final Long ACTIVE_DISH_ID = 12L;
    private static final Long MISSING_MERCHANT_DISH_ID = 13L;
    private static final Long CART_DISH_ID = 10L;
    private static final BigDecimal THRESHOLD = new BigDecimal("50.00");

    private final FreeActivityMapper freeActivityMapper = mock(FreeActivityMapper.class);
    private final FreeClaimMapper freeClaimMapper = mock(FreeClaimMapper.class);
    private final FreeGoodsPoolMapper freeGoodsPoolMapper = mock(FreeGoodsPoolMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PaymentSettlement settlement = mock(PaymentSettlement.class);

    private FreeServiceImpl freeService;

    /**
     * {@code LambdaQueryWrapper} 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 {@code F126FreeAuditTest}）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, FreeActivity.class);
        TableInfoHelper.initTableInfo(assistant, FreeGoodsPool.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, FreeClaim.class);
        TableInfoHelper.initTableInfo(assistant, Store.class);
    }

    @BeforeEach
    void setUp() {
        freeService = new FreeServiceImpl();
        ReflectionTestUtils.setField(freeService, "freeActivityMapper", freeActivityMapper);
        ReflectionTestUtils.setField(freeService, "freeClaimMapper", freeClaimMapper);
        ReflectionTestUtils.setField(freeService, "freeGoodsPoolMapper", freeGoodsPoolMapper);
        ReflectionTestUtils.setField(freeService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(freeService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(freeService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(freeService, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(freeService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(freeService, "userMapper", userMapper);
        ReflectionTestUtils.setField(freeService, "settlement", settlement);
    }

    // ---------------------------------------------------------------- fixtures

    /** 在线活动：门槛 50.00、日限量 20、dailyDate 空（跨日重置前的满额态，remaining 按库存取小） */
    private static FreeActivity onlineActivity() {
        FreeActivity a = new FreeActivity();
        a.setId(1L);
        a.setStatus(FreeActivity.STATUS_ONLINE);
        a.setThreshold(THRESHOLD);
        a.setDailyQuota(20);
        return a;
    }

    /**
     * 在售商品行：onSale 恒 1、audit 恒 ok（把封禁判据与在售/驳回判据解耦——拦截必须由 merchant 支命中，
     * 而非 on_sale/audit 顺手拦下）；merchantId 归属随入参构造
     */
    private static Dish dish(long id, String name, long merchantId) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("9.90"));
        d.setStock(5);
        d.setOnSale(1);
        d.setAuditStatus(Dish.AUDIT_OK);
        d.setMerchantId(merchantId);
        return d;
    }

    /** 商家行：status 走 F-122 值域（active/suspended/terminated），id 归属随入参构造 */
    private static Merchant merchant(long id, String status) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setName("商家" + id);
        m.setStatus(status);
        return m;
    }

    /** 购物车商品行：只进门槛重算（recalcCartTotal，只看价格），与免费件的商家状态解耦 */
    private static Dish cartDish(String price) {
        Dish d = dish(CART_DISH_ID, "购物车商品", ACTIVE_MERCHANT_ID);
        d.setPrice(new BigDecimal(price));
        d.setStock(99);
        return d;
    }

    private static FreeGoodsPool poolRow(long id, long activityId, long dishId) {
        FreeGoodsPool p = new FreeGoodsPool();
        p.setId(id);
        p.setActivityId(activityId);
        p.setDishId(dishId);
        return p;
    }

    private static FreeClaimDTO.ClaimItemDTO cartItem(Long dishId, int quantity) {
        FreeClaimDTO.ClaimItemDTO it = new FreeClaimDTO.ClaimItemDTO();
        it.setDishId(dishId);
        it.setQuantity(quantity);
        return it;
    }

    /** 营业中门店行（claim 建单的必需前提，F-80 起） */
    private static Store openStore() {
        Store s = new Store();
        s.setId(1L);
        s.setName("自提点一");
        s.setAddress("广东省佛山市顺德区");
        s.setStatus("open");
        return s;
    }

    /** 模拟库里那一行：单号取入参，0 元待支付态（与 FreeServiceImplSettleTest#dbRowOf 同构） */
    private static Order dbRowOf(String orderId) {
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

    // ---------------------------------------------------------------- ① poolDishes 过滤

    @Test
    void 混合池下发只含营业中商家的商品_封禁商家件不顶进默认免费商品位() {
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        // 封禁商家件排池首（池 id 升序=插入序，第一行即默认免费商品）：漏拦它会同时霸占默认商品位
        when(freeGoodsPoolMapper.selectList(any())).thenReturn(List.of(
                poolRow(21L, 1L, SUSPENDED_DISH_ID), poolRow(22L, 1L, ACTIVE_DISH_ID)));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(SUSPENDED_DISH_ID, "停业商家免费件", SUSPENDED_MERCHANT_ID),
                dish(ACTIVE_DISH_ID, "营业商家免费件", ACTIVE_MERCHANT_ID)));
        when(merchantMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                merchant(SUSPENDED_MERCHANT_ID, "suspended"), merchant(ACTIVE_MERCHANT_ID, "active")));

        Map<String, Object> out = freeService.current(null);

        assertEquals(FreeActivity.STATUS_ONLINE, out.get("activityStatus"),
                "池内尚有 active 商家件，活动不得被误判成 offline（防过滤写成恒真）");
        Dish defaultDish = (Dish) out.get("dish");
        assertEquals(ACTIVE_DISH_ID, defaultDish.getId(), "默认免费商品必须是过滤后剩下的 active 商家件");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pool = (List<Map<String, Object>>) out.get("pool");
        assertEquals(List.of(ACTIVE_DISH_ID), pool.stream().map(row -> row.get("dishId")).toList(),
                "suspended+在售 与 active+在售 的混合池，下发只含 active 商家的商品");
        verify(merchantMapper, times(1)).selectBatchIds(anyCollection()); // 一次批量查商家（消 N+1），不逐 dish 查
    }

    // ---------------------------------------------------------------- ② claim 拒 suspended+在售

    @Test
    void claim拒绝停业商家在售件_文案与下单拦截同句逐字() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);   // dishId 在候选池白名单内
        when(dishMapper.selectById(SUSPENDED_DISH_ID)).thenReturn(
                dish(SUSPENDED_DISH_ID, "停业商家免费件", SUSPENDED_MERCHANT_ID));
        when(merchantMapper.selectById(SUSPENDED_MERCHANT_ID)).thenReturn(
                merchant(SUSPENDED_MERCHANT_ID, "suspended"));

        BizException ex = assertThrows(BizException.class,
                () -> freeService.claim(USER_ID, SUSPENDED_DISH_ID, List.of(cartItem(CART_DISH_ID, 5))));

        assertEquals(400, ex.getCode());
        assertEquals("「停业商家免费件」所属商家暂停营业，请重新选择", ex.getMessage(),
                "与 OrderServiceImpl#createOrder 的 F-122 拦截同句逐字，不新造文案");
        // 拦截前置于门槛重算与一切写操作：连购物车合计的批量查都不发生，更不许建单/扣库存/结算
        verify(dishMapper, never()).selectBatchIds(anyCollection());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(dishMapper, never()).decreaseStock(eq(SUSPENDED_DISH_ID), eq(1));
        verify(settlement, never()).settle(any(Order.class), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------------- ③ claim 拒商家行缺失

    @Test
    void claim拒绝商家行缺失的在售件_宁缺勿错() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);
        when(dishMapper.selectById(MISSING_MERCHANT_DISH_ID)).thenReturn(
                dish(MISSING_MERCHANT_DISH_ID, "悬空商家免费件", MISSING_MERCHANT_ID));
        when(merchantMapper.selectById(MISSING_MERCHANT_ID)).thenReturn(null);   // 逻辑外键悬空

        BizException ex = assertThrows(BizException.class,
                () -> freeService.claim(USER_ID, MISSING_MERCHANT_DISH_ID, List.of(cartItem(CART_DISH_ID, 5))));

        assertEquals(400, ex.getCode());
        assertEquals("「悬空商家免费件」所属商家暂停营业，请重新选择", ex.getMessage(),
                "商家行缺失与封禁同支同句（宁缺勿错，同下单「缺失/null 一律拒」口径）");
        // 行缺失同样必须前置于一切写操作
        verify(dishMapper, never()).selectBatchIds(anyCollection());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(dishMapper, never()).decreaseStock(eq(MISSING_MERCHANT_DISH_ID), eq(1));
        verify(settlement, never()).settle(any(Order.class), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------------- ④ 对照：active+在售 照常领成

    @Test
    void claim放行营业商家在售件_照常领成零元单进结算() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeActivityMapper.tryClaimQuota(eq(1L), any(LocalDate.class))).thenReturn(1);
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);
        when(freeClaimMapper.selectCount(any())).thenReturn(0L);          // 今日未领
        when(dishMapper.selectById(ACTIVE_DISH_ID)).thenReturn(dish(ACTIVE_DISH_ID, "营业商家免费件", ACTIVE_MERCHANT_ID));
        when(merchantMapper.selectById(ACTIVE_MERCHANT_ID)).thenReturn(merchant(ACTIVE_MERCHANT_ID, "active"));
        when(dishMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(cartDish("25.00")));
        when(dishMapper.decreaseStock(eq(ACTIVE_DISH_ID), eq(1))).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setPhone("13800001234");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(storeMapper.selectOne(any())).thenReturn(openStore());
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        // 回读「刚插入的那一行」再进结算接缝（Step 2a 口径，同 F126FreeAuditTest）
        when(orderMapper.selectById(any())).thenAnswer(inv -> dbRowOf(inv.getArgument(0)));
        when(settlement.settle(any(Order.class), any(LocalDateTime.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = freeService.claim(USER_ID, ACTIVE_DISH_ID, List.of(cartItem(CART_DISH_ID, 2))); // 25.00×2=50.00 达标

        ArgumentCaptor<Order> inserted = ArgumentCaptor.forClass(Order.class);
        verify(merchantMapper).selectById(ACTIVE_MERCHANT_ID);   // 对照支确实穿过 merchant 校验支
        verify(orderMapper).insert(inserted.capture());
        verify(settlement, times(1)).settle(any(Order.class), any(LocalDateTime.class));
        assertEquals(inserted.getValue().getId(), result.getId(), "结算送进的是刚 INSERT 的那一单");
        assertEquals(new BigDecimal("0.00"), result.getTotalPrice(), "对照支仍是 0 元单，金额语义未被本片触碰");
        assertEquals(1, result.getItems().size());
        assertEquals(0, result.getItems().get(0).getPrice().compareTo(BigDecimal.ZERO),
                "明细成交单价快照仍为 0");
    }
}
