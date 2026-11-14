package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.fresh.entity.OrderItem;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 免费领 claim 错误路径与幂等键单测（不起 Spring 容器、不落库；mock 姿势照
 * {@code FreeServiceImplSettleTest}——九个 Mapper + PaymentSettlement 逐字段灌入）。
 *
 * <p><b>补测动机</b>：P11-2 审计轮 3 P2 缺口 #5——{@code FreeServiceImpl#claim} 的七个此前零直测分支
 * （行号实读）：
 * ① :197-205 原子限量计数 tryClaimQuota 回 0 → 回查区分三态文案，在线且未预置未来日期 →
 *    「今日已抢完，明天再来」；
 * ② :153-155 活动缺失/下线 → 「活动暂未开始」（单活动模型的状态机入口判定）；
 * ③ :192-194 hasClaimed 资格已领（uk_user_date 的业务读口径）→ 「今日已领取，明天再来」；
 * ④ :215-219 并发撞 uk_user_date：INSERT free_claim 抛 DuplicateKeyException → 转
 *    「今日已领取，明天再来」（方法出栈触发整个事务回滚：限量计数/订单/库存一并撤销）；
 * ⑤ :188-190 stock=0 → 「今日免费商品已抢完，明天再来」（判据在限量计数之前）；
 * ⑥ :158-162 池外/未传 dishId → 「无效的免费商品」（M8 自选白名单，不信任前端入参）；
 * ⑦ :141-149 free-键幂等：clientRequestId='free-{userId}-{yyyyMMdd}' 命中既有单 → 返回首次订单，
 *    资格/限量/建单全链零交互。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：mapper 都是 mock，@Transactional 回滚与真库 1062
 * 不在证明力内——本类钉住 Service 的分支语义与「零写」意图，真库行为由主会话门禁覆盖。
 */
class FreeClaimErrorPathTest {

    private static final Long USER_ID = 4L;
    private static final Long FREE_DISH_ID = 13L;
    private static final Long CART_DISH_ID = 10L;
    private static final BigDecimal THRESHOLD = new BigDecimal("50.00");
    private static final String CLIENT_REQUEST_ID =
            "free-" + USER_ID + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

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
     * claim 链的 LambdaQueryWrapper（Order/FreeGoodsPool/FreeClaim/FreeActivity）即时解析列名，
     * 纯 Mockito 环境没有容器建 TableInfo 缓存（照 DishAuditFlowTest/AdminMerchantApplyAuditTest 的处置）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        // ⑦ 幂等分支回填明细时构造 LambdaQueryWrapper<OrderItem>.eq(OrderItem::getOrderId, ...)
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
        TableInfoHelper.initTableInfo(assistant, FreeGoodsPool.class);
        TableInfoHelper.initTableInfo(assistant, FreeClaim.class);
        TableInfoHelper.initTableInfo(assistant, FreeActivity.class);
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

    private static FreeActivity onlineActivity() {
        FreeActivity a = new FreeActivity();
        a.setId(1L);
        a.setStatus(FreeActivity.STATUS_ONLINE);
        a.setThreshold(THRESHOLD);
        a.setDailyQuota(20);
        return a;
    }

    private static Dish freeDish(int stock) {
        Dish d = new Dish();
        d.setId(FREE_DISH_ID);
        d.setName("免费菜");
        d.setPrice(new BigDecimal("9.90"));
        d.setStock(stock);
        d.setOnSale(1);
        d.setMerchantId(1L);
        return d;
    }

    private static Dish cartDish(String price) {
        Dish d = new Dish();
        d.setId(CART_DISH_ID);
        d.setName("购物车菜");
        d.setPrice(new BigDecimal(price));
        d.setStock(99);
        d.setOnSale(1);
        d.setMerchantId(1L);
        return d;
    }

    /** 铺到「资格/限量判定前的公共通过态」：门槛 50.00 恰好达标，个别用例再覆写指定桩 */
    private void stubCommonPath() {
        when(orderMapper.selectOne(any())).thenReturn(null);              // free-键幂等未命中 → 走新建
        when(freeActivityMapper.selectOne(any())).thenReturn(onlineActivity());
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(1L);      // dishId 在候选池白名单内
        when(dishMapper.selectById(FREE_DISH_ID)).thenReturn(freeDish(5));
        when(merchantMapper.selectById(1L)).thenReturn(activeMerchant());
        when(dishMapper.selectBatchIds(anyCollection())).thenReturn(List.of(cartDish("50.00")));
        when(freeClaimMapper.selectCount(any())).thenReturn(0L);          // 今日未领
        when(freeActivityMapper.tryClaimQuota(eq(1L), any(LocalDate.class))).thenReturn(1);
    }

    private static Merchant activeMerchant() {
        Merchant m = new Merchant();
        m.setId(1L);
        m.setName("平台自营");
        m.setStatus("active");
        return m;
    }

    private static FreeClaimDTO.ClaimItemDTO cartItem(Long dishId, int quantity) {
        FreeClaimDTO.ClaimItemDTO it = new FreeClaimDTO.ClaimItemDTO();
        it.setDishId(dishId);
        it.setQuantity(quantity);
        return it;
    }

    private List<FreeClaimDTO.ClaimItemDTO> cartPassingThreshold() {
        return List.of(cartItem(CART_DISH_ID, 1));   // 50.00 × 1 = threshold 50.00
    }

    // ---------------------------------------------------------------- ① 限量 0 行

    @Test
    void 限量计数零行且回查在线则今日已抢完() {
        stubCommonPath();
        when(freeActivityMapper.tryClaimQuota(eq(1L), any(LocalDate.class))).thenReturn(0);
        // 回查行仍在线且 dailyDate 未预置未来 → 不是「未开始」，如实报「已抢完」
        when(freeActivityMapper.selectById(1L)).thenReturn(onlineActivity());

        BizException e = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));

        assertEquals(400, e.getCode());
        assertEquals("今日已抢完，明天再来", e.getMessage());
        // 限量失败发生在一切写之前：领取记录/订单零写
        verify(freeClaimMapper, never()).insert(any(FreeClaim.class));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(dishMapper, never()).decreaseStock(any(), any());
    }

    // ---------------------------------------------------------------- ② 活动状态机

    @Test
    void 活动缺失或下线一律活动暂未开始() {
        // 无活动行
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(freeActivityMapper.selectOne(any())).thenReturn(null);
        BizException empty = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));
        assertEquals(400, empty.getCode());
        assertEquals("活动暂未开始", empty.getMessage());

        // 活动下线（offline）
        FreeActivity offline = onlineActivity();
        offline.setStatus(FreeActivity.STATUS_OFFLINE);
        when(freeActivityMapper.selectOne(any())).thenReturn(offline);
        BizException offlineEx = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));
        assertEquals(400, offlineEx.getCode());
        assertEquals("活动暂未开始", offlineEx.getMessage());

        // 状态机入口判定先于白名单：池判定/商品读零发生
        verify(freeGoodsPoolMapper, never()).selectCount(any());
        verify(dishMapper, never()).selectById(any());
    }

    // ---------------------------------------------------------------- ③ 资格已领

    @Test
    void 今日已领资格判定四百且不消耗限量() {
        stubCommonPath();
        when(freeClaimMapper.selectCount(any())).thenReturn(1L);   // hasClaimed：uk_user_date 业务读口径命中

        BizException e = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));

        assertEquals(400, e.getCode());
        assertEquals("今日已领取，明天再来", e.getMessage());
        verify(freeActivityMapper, never()).tryClaimQuota(any(), any());
        verify(freeClaimMapper, never()).insert(any(FreeClaim.class));
    }

    // ---------------------------------------------------------------- ④ 并发撞 uk_user_date

    @Test
    void 并发撞uk_user_date转今日已领且整单零建() {
        stubCommonPath();
        when(freeClaimMapper.insert(any(FreeClaim.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_user_date'"));

        BizException e = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));

        assertEquals(400, e.getCode());
        assertEquals("今日已领取，明天再来", e.getMessage(),
                "uk_user_date 并发唯一冲突转「今日已领取」（方法出栈触发整个事务回滚）");
        // 回滚信号：领取记录插不进，订单/明细/库存扣减零发生
        verify(orderMapper, never()).insert(any(Order.class));
        verify(orderItemMapper, never()).insert(any(OrderItem.class));
        verify(dishMapper, never()).decreaseStock(any(), any());
        verify(settlement, never()).settle(any(Order.class), any());
    }

    // ---------------------------------------------------------------- ⑤ 库存 0

    @Test
    void 库存为零判今日免费商品已抢完且不消耗限量() {
        stubCommonPath();
        when(dishMapper.selectById(FREE_DISH_ID)).thenReturn(freeDish(0));   // stock=0

        BizException e = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold()));

        assertEquals(400, e.getCode());
        assertEquals("今日免费商品已抢完，明天再来", e.getMessage());
        verify(freeActivityMapper, never()).tryClaimQuota(any(), any());
        verify(freeClaimMapper, never()).insert(any(FreeClaim.class));
    }

    // ---------------------------------------------------------------- ⑥ 池外 dishId

    @Test
    void 池外商品判无效的免费商品() {
        stubCommonPath();
        when(freeGoodsPoolMapper.selectCount(any())).thenReturn(0L);   // 白名单未命中

        BizException e = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, 999L, cartPassingThreshold()));

        assertEquals(400, e.getCode());
        assertEquals("无效的免费商品", e.getMessage(), "M8：池外一律无效（不信任前端入参）");
        verify(dishMapper, never()).selectById(any());

        // dishId=null 同支同文案（|| 短路：dishId==null 直接判无效，连池查询都不发起）
        BizException nullDish = assertThrows(BizException.class, () ->
                freeService.claim(USER_ID, null, cartPassingThreshold()));
        assertEquals(400, nullDish.getCode());
        assertEquals("无效的免费商品", nullDish.getMessage());
    }

    // ---------------------------------------------------------------- ⑦ free-键幂等

    @Test
    void free键幂等返回首单_资格限量建单全链零交互() {
        // 既有 0 元单命中 free-{userId}-{yyyyMMdd} 键
        Order existing = new Order();
        existing.setId("F-EXIST");
        existing.setUserId(USER_ID);
        existing.setClientRequestId(CLIENT_REQUEST_ID);
        OrderItem line = new OrderItem();
        line.setOrderId("F-EXIST");
        line.setDishId(FREE_DISH_ID);
        line.setQuantity(1);
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(line));

        Order result = freeService.claim(USER_ID, FREE_DISH_ID, cartPassingThreshold());

        assertSame(existing, result, "幂等命中返回首次那笔订单（同一对象语义）");
        assertEquals(1, result.getItems().size(), "幂等分支回填明细（claim 出参带 items）");

        // 幂等键形状：clientRequestId 绑定值逐字 = free-{userId}-{yyyyMMdd}
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<Order>> cap =
                (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectOne(cap.capture());
        // MP 的 eq 参数是惰性绑定（ISqlSegment 渲染时才写入 paramNameValuePairs，与 UpdateWrapper.set
        // 的立即绑定不同）：先 getSqlSegment() 强制渲染再读 params（照 OrderCancelRestockTest ③ 修正先例）
        String rendered = cap.getValue().getSqlSegment();
        assertTrue(rendered.contains("client_request_id"),
                "幂等查询必须按 client_request_id 等值渲染：sql=" + rendered);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(CLIENT_REQUEST_ID),
                "幂等键必须逐字为 free-{userId}-{BASIC_ISO_DATE}：params="
                        + cap.getValue().getParamNameValuePairs());

        // 全链零交互：资格/限量/领取记录/建单/结算一概不发生
        verify(freeActivityMapper, never()).selectOne(any());
        verify(freeActivityMapper, never()).tryClaimQuota(any(), any());
        verify(freeClaimMapper, never()).insert(any(FreeClaim.class));
        verify(orderMapper, never()).insert(any(Order.class));
        verify(dishMapper, never()).decreaseStock(any(), any());
        verify(settlement, never()).settle(any(Order.class), any());
    }
}
