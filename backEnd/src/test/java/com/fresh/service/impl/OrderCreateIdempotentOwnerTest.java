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
import com.fresh.service.SeckillSessionResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-2 P1-A 下单幂等键归属校验单测（照 OrderCreateDailyCapacityTest 的桩范式：不起容器、
 * 不落库、@Autowired 字段逐个灌 mock）。
 *
 * <p><b>要证明的事</b>（语义红线：幂等命中仅限「同用户同键」；B 用 A 的键提交必须 B 自己成功
 * 建单（键置 null）或 400，绝不能拿到 A 的行）：
 * <ul>
 *   <li>① B 用 A 已落的键提交 → B 拿到自己的新单（落库 clientRequestId 为 null，不占
 *       uk_client_request_id），绝不是 A 的 orderId——快查分支归属收窄；</li>
 *   <li>② 同用户同键 → 幂等返回原单（存量行为回归）；</li>
 *   <li>③ DuplicateKey 兜底分支 winner 归属非本人 → 400「下单冲突，请重试」（并发窗口下
 *       B 撞上 A 正在提交的键；实读结论：decreaseStock 在 insert 之前，本事务已有写操作，
 *       非本人不重插、已扣库存随事务回滚复原）；</li>
 *   <li>④ DuplicateKey 兜底分支 winner 归属本人（同用户并发双提交同键）→ 照旧幂等返回 winner
 *       （存量行为回归）。</li>
 * </ul>
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 uk_client_request_id 唯一约束的并发撞键、
 * 事务回滚对 decreaseStock 的真实复原——mock 不经过锁管理器与事务管理器，真库行为由主会话
 * live 双用户撞键复测覆盖（P11-2 清单验收项）。
 */
class OrderCreateIdempotentOwnerTest {

    private static final Long USER_A = 31L;
    private static final Long USER_B = 32L;
    private static final long DISH_ID = 5L;
    private static final String KEY_A = "ord-key-a-1";

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
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，按 MP 官方入口手工灌一次内存缓存（形态照 OrderCreateDailyCapacityTest；
     * OrderItem 是 loadItems 的 wrapper 实体，一并灌入防列名解析落空）。
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
        // 本类 dish 不带秒杀标，inWindow 不会被调用；mock resolver 只为字段非 null（防未来 fixture 加秒杀标 NPE）
        Merchant activeMerchant = new Merchant();
        activeMerchant.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(activeMerchant);
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish());
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);
    }

    /** 单件购物车，不传 storeId（避开锁读与容量链路，聚焦幂等键归属语义） */
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

    private static Dish dish() {
        Dish d = new Dish();
        d.setId(DISH_ID);
        d.setName("有机菠菜");
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(1);
        return d;
    }

    /** A 已落的幂等行（userId 可指定，复现「键归属」两种形态） */
    private static Order orderOf(String id, Long userId) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        o.setClientRequestId(KEY_A);
        return o;
    }

    /** insert 桩：mock ASSIGN_ID 回填雪花号，返回 1 行 */
    private void stubInsertAssignsId(String id) {
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(id);
            return 1;
        });
    }

    /** ① B 用 A 已落的键提交 → B 拿到自己的新单（键置 null 落库），绝不是 A 的行 */
    @Test
    void 跨用户撞键_B走全新建单_键置null_拿不到A的行() {
        when(orderMapper.selectOne(any())).thenReturn(orderOf("ord-a-first", USER_A)); // 快查命中 A 的行
        stubInsertAssignsId("ord-b-new");
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart(KEY_A), USER_B);

        assertNotNull(result, "B 必须自己成功建单（非本人键对 B 表现为全新请求）");
        assertEquals("ord-b-new", result.getId(), "返回的必须是 B 的新单，绝不是 A 的 orderId");
        assertEquals(USER_B, result.getUserId(), "新单归属 B 本人");
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(1)).insert(captor.capture());
        assertNull(captor.getValue().getClientRequestId(),
                "他人键必须置 null 落库（uk_client_request_id 全局唯一，不置 null 后续 insert 必撞键）");
        verify(dishMapper, times(1)).decreaseStock(DISH_ID, 1); // 正常建单流程照跑（库存照扣）
        verify(orderMapper, never()).selectCount(any()); // 无 storeId，容量链路本就不走
    }

    /** ② 快查分支同用户同键 → 幂等返回原单（存量行为回归） */
    @Test
    void 同用户同键_幂等返回原单_零写库() {
        when(orderMapper.selectOne(any())).thenReturn(orderOf("ord-a-first", USER_B)); // 键归属本人
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart(KEY_A), USER_B);

        assertEquals("ord-a-first", result.getId(), "同用户同键重复提交必须返回首次订单（存量行为）");
        verify(orderMapper, never()).insert(any(Order.class)); // 零写库
        verify(dishMapper, never()).decreaseStock(any(), any()); // 连库存都不扣
    }

    /** ③ DuplicateKey 兜底分支 winner 归属非本人 → 400「下单冲突，请重试」（并发窗口场景） */
    @Test
    void 并发撞键winner归属非本人_400下单冲突_不返回他人行() {
        Order winnerA = orderOf("ord-a-winner", USER_A); // A 正在提交的键（快查时未提交，insert 撞键）
        when(orderMapper.selectOne(any())).thenReturn(null, winnerA); // 第1次快查未命中，第2次 FOR UPDATE 读到 A 的行
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_client_request_id"));

        BizException ex = assertThrows(BizException.class, () -> orderService.createOrder(cart(KEY_A), USER_B));

        assertEquals(400, ex.getCode(), "走 BizException(String) 的默认 400");
        assertEquals("下单冲突，请重试", ex.getMessage(), "非本人 winner 必须拒返（本事务已扣库存，随事务回滚复原）");
        verify(orderItemMapper, never()).insert(any(OrderItem.class)); // 明细未落（对方行不是本单）
    }

    /** ④ DuplicateKey 兜底分支 winner 归属本人（同用户并发双提交同键）→ 照旧幂等返回（存量行为回归） */
    @Test
    void 并发撞键winner归属本人_幂等返回winner() {
        Order winnerB = orderOf("ord-b-winner", USER_B);
        when(orderMapper.selectOne(any())).thenReturn(null, winnerB);
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_client_request_id"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart(KEY_A), USER_B);

        assertEquals("ord-b-winner", result.getId(), "本人 winner 照旧幂等返回（存量行为）");
        verify(orderMapper, times(1)).insert(any(Order.class)); // insert 恰好试了一次（撞键后不再重试插入）
    }

    /** ⑤ 归属比对用 equals 语义：A 键 + B 提交 → 置 null 建单（键不落库）已有 ① 覆盖；此处补 B 拿不到 A 手机号的旁证 */
    @Test
    void 跨用户撞键_B的新单快照与A行无关() {
        Order aRow = orderOf("ord-a-first", USER_A);
        aRow.setPhone("13800138000"); // A 的敏感快照（f28 demo_num_exclude 演示号）
        when(orderMapper.selectOne(any())).thenReturn(aRow);
        stubInsertAssignsId("ord-b-new");
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Order result = orderService.createOrder(cart(KEY_A), USER_B);

        assertEquals("13800001234", result.getPhone(), "B 的新单手机号是 B 自己的，绝不是 A 行快照");
        assertEquals(1, result.getItems().size(), "明细是本次请求重建的（恰 1 条，不来自 A 行）");
        assertEquals(DISH_ID, result.getItems().get(0).getDishId(), "明细 dishId 是本次请求的菜品");
    }
}
