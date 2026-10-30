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
 * F-126 免费领链路驳回拦截单测（纯 mock 姿势照 {@code FreeServiceImplSettleTest} / {@code AdminFreeServiceTest}：
 * mock 掉 Mapper 与 {@link PaymentSettlement}，直接 new FreeServiceImpl 按字段名灌 @Autowired 槽位，
 * 不连库、不起 Spring 容器、期望值手工可推）。
 *
 * <p>要钉住的三件事：
 * ① 候选池混合池（驳回+在售 排池首、ok+在售 在后）经 current 下发只含 ok——驳回件既不进自选列表，
 *    也不顶进默认免费商品位（C 端免费页即下发渲染，前端零改动即生效）；
 * ② claim 撞上 驳回+在售 → 400「活动商品已下架，明天再来」逐字——与下架同支同句，不透出「被驳回」
 *    语义、不新增文案（契约 §1-2）；且拦截前置于一切写操作与门槛重算；
 * ③ 对照：ok+在售 照常领成 0 元单——防 ①② 的判据写成恒真/恒假时本类还假绿。
 *
 * <p>本类测不成什么（免得留假收据）：mapper 是 mock，「真库里驳回件被 selectBatchIds 捞回再被过滤」
 * 的 SQL 行为、@Transactional 回滚、settle 履约细节不在本类证明力之内（后者归 PaymentSettlementTest
 * 与 FreeServiceImplSettleTest）；poolDishes 经 current(null) 走匿名视角驱动（putPersonal 对
 * userId=null 走中性缺省，不触个人数据桩），个人数据分支另有契约覆盖。
 */
class F126FreeAuditTest {

    private static final Long USER_ID = 4L;
    private static final Long REJECTED_DISH_ID = 11L;
    private static final Long OK_DISH_ID = 12L;
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

    /**
     * {@code LambdaQueryWrapper} 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 {@code AdminFreeServiceTest}）。
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

    /** 在售商品行：onSale 恒 1（把驳回判据与在售判据解耦——拦截必须由 audit 支命中，而非 on_sale 顺手拦下） */
    private static Dish dish(long id, String name, String auditStatus) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("9.90"));
        d.setStock(5);
        d.setOnSale(1);
        d.setAuditStatus(auditStatus);
        return d;
    }

    /** 购物车商品行：只进门槛重算（recalcCartTotal），价格与库存独立于免费件构造 */
    private static Dish cartDish(String price) {
        Dish d = dish(CART_DISH_ID, "购物车商品", Dish.AUDIT_OK);
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
    void 混合池下发只含审核正常件_驳回件也不顶进默认免费商品位() {
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        // 驳回件排在池首（池 id 升序=插入序，第一行即默认免费商品）：漏拦它会同时霸占默认商品位
        when(freeGoodsPoolMapper.selectList(any())).thenReturn(List.of(
                poolRow(21L, 1L, REJECTED_DISH_ID), poolRow(22L, 1L, OK_DISH_ID)));
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                dish(REJECTED_DISH_ID, "驳回免费件", Dish.AUDIT_REJECTED),
                dish(OK_DISH_ID, "正常免费件", Dish.AUDIT_OK)));

        Map<String, Object> out = freeService.current(null);

        assertEquals(FreeActivity.STATUS_ONLINE, out.get("activityStatus"),
                "池内尚有 ok 件，活动不得被误判成 offline（防过滤写成恒真）");
        Dish defaultDish = (Dish) out.get("dish");
        assertEquals(OK_DISH_ID, defaultDish.getId(), "默认免费商品必须是过滤后剩下的 ok 件");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pool = (List<Map<String, Object>>) out.get("pool");
        assertEquals(List.of(OK_DISH_ID), pool.stream().map(row -> row.get("dishId")).toList(),
                "驳回+在售 与 ok+在售 的混合池，下发只含 ok 件");
    }

    // ---------------------------------------------------------------- ② claim 拒驳回+在售

    @Test
    void claim拒绝驳回在售件_文案与下架同句逐字() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);   // dishId 在候选池白名单内
        when(dishMapper.selectById(REJECTED_DISH_ID)).thenReturn(
                dish(REJECTED_DISH_ID, "驳回免费件", Dish.AUDIT_REJECTED));

        BizException ex = assertThrows(BizException.class,
                () -> freeService.claim(USER_ID, REJECTED_DISH_ID, List.of(cartItem(CART_DISH_ID, 5))));

        assertEquals(400, ex.getCode());
        assertEquals("活动商品已下架，明天再来", ex.getMessage(), "驳回语义只落商家端，C 端复用本链路既有下架句");
        // 拦截前置于一切写操作与门槛重算：连购物车合计的批量查都不发生，更不许建单/扣库存/结算
        verify(dishMapper, never()).selectBatchIds(anyCollection());
        verify(orderMapper, never()).insert(any(Order.class));
        verify(dishMapper, never()).decreaseStock(eq(REJECTED_DISH_ID), eq(1));
        verify(settlement, never()).settle(any(Order.class), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------------- ③ 对照：ok+在售 照常领成

    @Test
    void claim放行审核正常在售件_照常领成零元单进结算() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeActivityMapper.tryClaimQuota(eq(1L), any(LocalDate.class))).thenReturn(1);
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);
        when(freeClaimMapper.selectCount(any())).thenReturn(0L);          // 今日未领
        when(dishMapper.selectById(OK_DISH_ID)).thenReturn(dish(OK_DISH_ID, "正常免费件", Dish.AUDIT_OK));
        when(dishMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(cartDish("25.00")));
        when(dishMapper.decreaseStock(eq(OK_DISH_ID), eq(1))).thenReturn(1);
        User user = new User();
        user.setId(USER_ID);
        user.setPhone("13800001234");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(storeMapper.selectOne(any())).thenReturn(openStore());
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        // 回读「刚插入的那一行」再进结算接缝（Step 2a 口径，同 FreeServiceImplSettleTest）
        when(orderMapper.selectById(any())).thenAnswer(inv -> dbRowOf(inv.getArgument(0)));
        when(settlement.settle(any(Order.class), any(LocalDateTime.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = freeService.claim(USER_ID, OK_DISH_ID, List.of(cartItem(CART_DISH_ID, 2))); // 25.00×2=50.00 达标

        ArgumentCaptor<Order> inserted = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(inserted.capture());
        verify(settlement, times(1)).settle(any(Order.class), any(LocalDateTime.class));
        assertEquals(inserted.getValue().getId(), result.getId(), "结算送进的是刚 INSERT 的那一单");
        assertEquals(new BigDecimal("0.00"), result.getTotalPrice(), "对照支仍是 0 元单，金额语义未被本片触碰");
        assertEquals(1, result.getItems().size());
        assertEquals(0, result.getItems().get(0).getPrice().compareTo(BigDecimal.ZERO),
                "明细成交单价快照仍为 0");
    }
}
