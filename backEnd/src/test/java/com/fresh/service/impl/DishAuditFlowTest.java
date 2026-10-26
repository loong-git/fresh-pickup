package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.dto.AdminDishAuditDTO;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.dto.RecommendVO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.AdminService;
import com.fresh.service.AdminV2Service;
import com.fresh.service.MerchantDishService;
import com.fresh.service.RecommendService;
import com.fresh.dto.MerchantDishUpdateDTO;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-116 商品内容审核「先发后审」契约单测（不起 Spring 容器、不落库；契约 §3 五组逐条）：
 * ① C 端过滤：rejected 品不出现在列表/搜索/秒杀/推荐——wrapper 型读路径断言「生产代码真渲染出的 SQL
 *    段含 audit_status 条件且绑 'ok'」（照 DishSeckillFilterTest 的真渲染断言口径，不重抄条件）；
 *    推荐候选集另验内存兜底（mock 回含 rejected 的集，出参必无它）；DISH_FILTER_WHERE 的 SQL 文本断言
 *    含 audit 片段且页/COUNT 两条 @Select 同源（D-16）。
 * ② 下单拦截：rejected 品下单 400 且文案与下架同款、零扣库存（照 OrderCreateOffSaleMessageTest 口径）。
 * ③ 运营端点：reject 无 reason / reason 201 字 / action 非法 → 400；dish 999999 → 404；
 *    restore 清 reason；幂等（重复 reject 200 且 update 零次、reason 不被空覆盖）；审计行 dish.audit。
 * ④ 商家改品：rejected 品走 update 后 SET 片段不含审核两列（防"随手保存洗白"）；新建默认 ok
 *    （实体审核两列恒 null，落库走 DB DEFAULT 'ok' 兜底）。
 * ⑤ 运营列表：audit 参数 NULL 全量 / 'rejected' 只筛驳回品（归一小写后下传，白名单外 400）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：MySQL 三值逻辑与 DEFAULT 'ok' 的真库落库、
 * 双轨闸门/限流（Controller 层，由 verify 脚本与主会话门禁覆盖）——mock 侧只断言 Service 交付的
 * SQL/参数/写侧意图。
 */
class DishAuditFlowTest {

    private static final String ADMIN_USER_ACTOR = "admin_user:7";

    /** MP 渲染出的参数占位符：{@code #{ew.paramNameValuePairs.MPGENVAL1}} */
    private static final String PARAM_REF = "#\\{(?:ew\\.)?paramNameValuePairs\\.(\\w+)}";

    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 DishSeckillFilterTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Dish.class);
    }

    // ---------------------------------------------------------------- fixtures

    /** F-122 新增依赖：默认商家 active，保留下单链路既有断言语义（本类测的是审核拦截） */
    private static MerchantMapper activeMerchantMapper() {
        MerchantMapper merchantMapper = mock(MerchantMapper.class);
        Merchant active = new Merchant();
        active.setStatus("active");
        when(merchantMapper.selectById(any())).thenReturn(active);
        return merchantMapper;
    }

    private static Dish dish(long id, String name, Integer onSale, String auditStatus) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(onSale);
        d.setAuditStatus(auditStatus);
        d.setCategory("meat");
        d.setSoldCount(50);
        d.setSeckill(0);
        d.setMerchantId(1L);
        return d;
    }

    /** 秒杀件：三条件全过 + 审核态可指定（F-116 后秒杀集=四条件） */
    private static Dish seckillDish(long id, String name, String auditStatus) {
        Dish d = dish(id, name, 1, auditStatus);
        d.setSeckill(1);
        d.setSeckillPrice(new BigDecimal("7.80"));
        return d;
    }

    private static AdminDishAuditDTO auditDto(String action, String reason) {
        AdminDishAuditDTO dto = new AdminDishAuditDTO();
        dto.setAction(action);
        dto.setReason(reason);
        return dto;
    }

    /** wrapper 的 WHERE 段是否含「audit_status = 绑定 'ok'」这一条（真渲染断言，不重抄条件） */
    private void assertAuditOkClause(LambdaQueryWrapper<Dish> wrapper) {
        String segment = wrapper.getSqlSegment();
        assertTrue(segment.contains("audit_status"),
                "C 端读路径必须渲染出 audit_status 条件：segment=" + segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(Dish.AUDIT_OK),
                "audit_status 条件必须绑 'ok'（C 端恒 ok）：params=" + wrapper.getParamNameValuePairs());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Dish> capturedQueryWrapper(int times) {
        ArgumentCaptor<LambdaQueryWrapper<Dish>> captor =
                (ArgumentCaptor<LambdaQueryWrapper<Dish>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dishMapper, times(times)).selectList(captor.capture());
        return captor.getValue();
    }

    // ================================================================
    // ① C 端过滤（契约 §3.1）
    // ================================================================

    @Test
    void C端列表不含驳回件_渲染SQL含audit_ok条件() {
        // 列表走 likeSearch（空白 keyword）；mapper 是 mock，过滤语义在 Service 组装的 wrapper 里，
        // 断言真渲染段而不是重抄条件——重抄会让「代码改了、测试没跟着改」双双失真
        DishServiceImpl dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        when(dishMapper.selectList(any())).thenReturn(List.of(dish(1L, "正常件", 1, Dish.AUDIT_OK)));

        dishService.getDishes(null, null);

        assertAuditOkClause(capturedQueryWrapper(1));
    }

    @Test
    void C端搜索FULLTEXT路径同样只收audit_ok() {
        DishServiceImpl dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        when(dishMapper.selectIdsByFulltext(any())).thenReturn(List.of(Map.of("id", 1L, "score", 1.0)));
        when(dishMapper.selectDishIdsByAlias(any())).thenReturn(List.of());
        // 返回非空可变集（getDishes 会原地 sort），同时避免 0 命中回退 likeSearch 造成第二次 selectList 干扰捕获
        when(dishMapper.selectList(any()))
                .thenReturn(new java.util.ArrayList<>(List.of(dish(1L, "命中件", 1, Dish.AUDIT_OK))));

        dishService.getDishes(null, "有机菠菜");

        // 命中集合取行那条 selectList（in(mergedIds)）也必须叠 audit 条件，否则 FULLTEXT 命中的驳回品照样漏出
        assertAuditOkClause(capturedQueryWrapper(1));
    }

    @Test
    void 秒杀集渲染SQL含audit_ok且三条件不被挤掉() {
        DishServiceImpl dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);

        dishService.listSeckill();

        LambdaQueryWrapper<Dish> wrapper = capturedQueryWrapper(1);
        assertAuditOkClause(wrapper);
        String segment = wrapper.getSqlSegment();
        assertTrue(segment.contains("on_sale"), "与三条件过滤同位：on_sale 条件必须在；segment=" + segment);
        assertTrue(segment.contains("stock"), "stock>0 条件必须在；segment=" + segment);
        assertTrue(segment.contains("seckill_price IS NOT NULL"), "秒杀价非空条件必须在；segment=" + segment);
    }

    @Test
    void 推荐候选集_SQL加条件_内存兜底剔除mock返回的rejected件() {
        ReviewMapper reviewMapper = mock(ReviewMapper.class);
        RecommendService recommendService = new RecommendServiceImpl(
                dishMapper, mock(OrderMapper.class), mock(OrderItemMapper.class), reviewMapper);
        Dish ok = dish(1L, "正常件", 1, Dish.AUDIT_OK);
        Dish rejected = dish(2L, "驳回件", 1, Dish.AUDIT_REJECTED);
        Dish offSale = dish(3L, "下架件", 0, Dish.AUDIT_OK);
        // mock 不执行 SQL 条件 → rejected/offSale 原样进候选集，内存兜底必须把它们剔掉
        when(dishMapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(ok, rejected, offSale)));
        when(reviewMapper.selectList(any())).thenReturn(List.of());

        RecommendVO vo = recommendService.recommend(null, null, null);

        assertEquals(List.of(1L), vo.getItems().stream().map(Dish::getId).toList(),
                "rejected 品不出现在推荐（mock 返回含 rejected 的集，验 Service 层过滤）");
        assertAuditOkClause(capturedQueryWrapper(1));
    }

    @Test
    void DISH_FILTER_WHERE含audit片段_且页与COUNT两条Select同源() {
        // 契约 §2.1-1 逐字片段
        assertTrue(DishMapper.DISH_FILTER_WHERE.contains("(#{auditStatus} IS NULL OR d.audit_status = #{auditStatus})"),
                "DISH_FILTER_WHERE 必须含可空 audit 条件（C 端恒 ok / 运营 NULL 全量·指定态筛选）："
                        + DishMapper.DISH_FILTER_WHERE);
        // D-16：COUNT 与页两 SQL 共用同一常量（@Select 的编译期常量串里必须原样出现 DISH_FILTER_WHERE）
        try {
            Method page = DishMapper.class.getMethod("selectDishesPage",
                    Long.class, Integer.class, String.class, String.class, long.class, int.class);
            Method count = DishMapper.class.getMethod("countDishes",
                    Long.class, Integer.class, String.class, String.class);
            String pageSql = String.join("", page.getAnnotation(Select.class).value());
            String countSql = String.join("", count.getAnnotation(Select.class).value());
            assertTrue(pageSql.contains(DishMapper.DISH_FILTER_WHERE), "页 SQL 必须内嵌 DISH_FILTER_WHERE");
            assertTrue(countSql.contains(DishMapper.DISH_FILTER_WHERE), "COUNT SQL 必须内嵌 DISH_FILTER_WHERE（D-16）");
            assertTrue(pageSql.contains("d.audit_status AS auditStatus")
                            && pageSql.contains("d.audit_reason AS auditReason"),
                    "页出参必须带审核态与驳回理由（运营页 tag + tooltip 数据源）");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("DishMapper 页/COUNT 方法签名与预期不符（auditStatus 参数缺失？）", e);
        }
    }

    @Test
    void 驳回品详情直链出参不带驳回理由_审核态保留() {
        // F-116a（契约复核算子1/3）：getDishDetail 直出实体是 C 端唯一能带回 rejected 行的出参路径，
        // Controller 收口 setAuditReason(null)——理由只给商家端链路；auditStatus 保留供前端渲染下架态
        com.fresh.controller.DishController controller = new com.fresh.controller.DishController();
        DishServiceImpl dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(controller, "dishService", dishService);
        Dish rejected = dish(9L, "驳回品", 1, Dish.AUDIT_REJECTED);
        rejected.setAuditReason("图片涉违规");
        when(dishMapper.selectById(9L)).thenReturn(rejected);

        com.fresh.common.R<Dish> out = controller.getDishDetail(9L);

        assertEquals(Dish.AUDIT_REJECTED, out.getData().getAuditStatus(),
                "auditStatus 保留（前端可用它渲染下架态）");
        assertNull(out.getData().getAuditReason(),
                "驳回理由只给商家端链路，C 端详情直链不下发（F-116a 出参收口）");
    }

    // ================================================================
    // ② 下单拦截（契约 §3.2）
    // ================================================================

    @Test
    void 驳回品下单400_文案与下架同款_零扣库存() {
        OrderServiceImpl orderService = new OrderServiceImpl();
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "merchantMapper", activeMerchantMapper());
        ReflectionTestUtils.setField(orderService, "storeMapper", mock(com.fresh.mapper.StoreMapper.class));
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段
        Dish rejected = dish(9L, "问题菜品", 1, Dish.AUDIT_REJECTED);
        when(dishMapper.selectById(9L)).thenReturn(rejected);

        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(9L);
        item.setQuantity(1);
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId("f116-audit-reject");
        dto.setPhone("13800001234");
        dto.setItems(List.of(item));

        BizException ex = assertThrows(BizException.class, () -> orderService.createOrder(dto, 4L));

        assertEquals("「问题菜品」已下架，请重新选择", ex.getMessage(),
                "驳回品对用户表现即下架品：文案与 on_sale=0 同款不新增（契约 §2.5）");
        assertEquals(400, ex.getCode());
        verify(dishMapper, never()).decreaseStock(any(), any());
        verify(orderMapper, never()).insert(any(com.fresh.entity.Order.class));
    }

    // ================================================================
    // ③ 运营端点（契约 §3.3）
    // ================================================================

    private AdminServiceImpl adminServiceWithDish(Dish stored) {
        AdminServiceImpl service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(service, "adminKey", "test-admin-key");
        when(dishMapper.selectById(stored == null ? 999999L : stored.getId())).thenReturn(stored);
        return service;
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Dish> capturedUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<Dish>> captor =
                (ArgumentCaptor<LambdaUpdateWrapper<Dish>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dishMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    @Test
    void reject缺理由与超长与非法action一律400() {
        AdminService service = adminServiceWithDish(dish(5L, "待审品", 1, Dish.AUDIT_OK));

        BizException noReason = assertThrows(BizException.class,
                () -> service.auditDish(5L, auditDto("reject", "   "), ADMIN_USER_ACTOR));
        assertEquals(400, noReason.getCode());
        BizException longReason = assertThrows(BizException.class,
                () -> service.auditDish(5L, auditDto("reject", "违".repeat(201)), ADMIN_USER_ACTOR));
        assertEquals(400, longReason.getCode());
        BizException badAction = assertThrows(BizException.class,
                () -> service.auditDish(5L, auditDto("ban", "理由"), ADMIN_USER_ACTOR));
        assertEquals(400, badAction.getCode());
        assertEquals("action 仅允许 reject/restore", badAction.getMessage(), "契约 §2.2 逐字文案");

        verify(dishMapper, never()).update(any(), any());
        verify(auditMapper, never()).insert(any(AdminAuditLog.class));
    }

    @Test
    void dish不存在404() {
        AdminService service = adminServiceWithDish(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.auditDish(999999L, auditDto("reject", "理由"), ADMIN_USER_ACTOR));

        assertEquals(404, ex.getCode());
        assertEquals("菜品不存在", ex.getMessage());
        verify(dishMapper, never()).update(any(), any());
    }

    @Test
    void reject置rejected与理由_不碰onSale_写审计dishAudit() {
        AdminService service = adminServiceWithDish(dish(5L, "待审品", 1, Dish.AUDIT_OK));

        service.auditDish(5L, auditDto("reject", "图片涉违规"), ADMIN_USER_ACTOR);

        LambdaUpdateWrapper<Dish> wrapper = capturedUpdateWrapper();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("audit_status") && sqlSet.contains("audit_reason"),
                "只写审核两列：SET=" + sqlSet);
        assertFalse(sqlSet.contains("on_sale"), "驳回不改 on_sale（两列正交，契约 §2.2）：SET=" + sqlSet);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(Dish.AUDIT_REJECTED) && params.containsValue("图片涉违规"),
                "audit_status 绑 rejected、理由绑定 trim 后原文：params=" + params);

        AdminAuditLog audit = capturedAudit();
        assertEquals("dish.audit", audit.getAction());
        assertEquals("dish", audit.getTargetType());
        assertEquals("5", audit.getTargetId());
        assertTrue(audit.getDetail().contains("action=reject")
                        && audit.getDetail().contains("auditStatus=rejected")
                        && audit.getDetail().contains("auditReason=图片涉违规"),
                "before/after 记 {auditStatus, auditReason} + action：detail=" + audit.getDetail());
    }

    @Test
    void restore置ok并清理由_带reason忽略() {
        Dish rejected = dish(6L, "驳回品", 1, Dish.AUDIT_REJECTED);
        rejected.setAuditReason("图片涉违规");
        AdminService service = adminServiceWithDish(rejected);

        service.auditDish(6L, auditDto("restore", "这段会被忽略"), ADMIN_USER_ACTOR);

        LambdaUpdateWrapper<Dish> wrapper = capturedUpdateWrapper();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(Dish.AUDIT_OK), "restore 置 ok：params=" + params);
        assertTrue(params.containsValue(null), "restore 清理由（set null → SET audit_reason=NULL）：params=" + params);
        assertFalse(params.containsValue("这段会被忽略"), "body 带 reason 忽略（契约 §2.2）：params=" + params);
    }

    @Test
    void 重复reject幂等_零写库_既有reason不被空覆盖_审计照记() {
        Dish rejected = dish(7L, "已驳回品", 1, Dish.AUDIT_REJECTED);
        rejected.setAuditReason("原有理由");
        AdminService service = adminServiceWithDish(rejected);

        service.auditDish(7L, auditDto("reject", "新理由"), ADMIN_USER_ACTOR);

        verify(dishMapper, never()).update(any(), any());
        AdminAuditLog audit = capturedAudit();
        assertTrue(audit.getDetail().contains("auditReason=原有理由"),
                "幂等不产生假变化：new 侧如实记库内保持的原值，既有理由不被空覆盖：detail=" + audit.getDetail());
    }

    @Test
    void restore幂等同样零写库_审计照记() {
        AdminService service = adminServiceWithDish(dish(8L, "正常品", 1, Dish.AUDIT_OK));

        service.auditDish(8L, auditDto("restore", null), ADMIN_USER_ACTOR);

        verify(dishMapper, never()).update(any(), any());
        assertTrue(capturedAudit().getDetail().contains("action=restore"));
    }

    // ================================================================
    // ④ 商家改品不碰审核两列（契约 §3.4）
    // ================================================================

    private MerchantDishServiceImpl merchantService() {
        MerchantDishServiceImpl service = new MerchantDishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "merchantService", mock(com.fresh.service.MerchantService.class));
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", auditMapper);
        return service;
    }

    private static MerchantDishUpdateDTO updateDto() {
        MerchantDishUpdateDTO dto = new MerchantDishUpdateDTO();
        dto.setDescription("商家改描述");
        return dto;
    }

    @Test
    void 驳回品商家改品_SET片段不含审核两列() {
        MerchantDishService service = merchantService();
        Dish rejected = dish(10L, "驳回品", 1, Dish.AUDIT_REJECTED);
        rejected.setAuditReason("图片涉违规");
        when(dishMapper.selectOne(any())).thenReturn(rejected);   // findOwned 双条件读
        when(dishMapper.update(isNull(), any())).thenReturn(1);   // 无 tags → patch=null 纯 wrapper SET

        service.update(11L, 2L, 10L, updateDto());

        LambdaUpdateWrapper<Dish> wrapper = capturedUpdateWrapper();
        String sqlSet = wrapper.getSqlSet();
        assertFalse(sqlSet.contains("audit_status") || sqlSet.contains("audit_reason"),
                "改品绝不碰审核态两列（防「随手保存洗白」，恢复唯一入口=运营 restore）：SET=" + sqlSet);
        assertTrue(sqlSet.contains("description"));
    }

    @Test
    void 商家新建商品_实体审核两列留null_落库走DEFAULT_ok() {
        MerchantDishServiceImpl service = merchantService();
        com.fresh.dto.MerchantDishCreateDTO createDto = new com.fresh.dto.MerchantDishCreateDTO();
        createDto.setName("新商品");
        createDto.setPrice(new BigDecimal("9.90"));
        createDto.setCategory("meat");
        createDto.setUnit("斤");
        createDto.setStock(30);
        when(dishMapper.insert(any(Dish.class))).thenAnswer(inv -> {
            Dish d = inv.getArgument(0);
            d.setId(99L);
            return 1;
        });
        when(dishMapper.selectOne(any())).thenReturn(dish(99L, "新商品", 1, null));

        Dish created = service.create(11L, 2L, createDto);

        ArgumentCaptor<Dish> captor = ArgumentCaptor.forClass(Dish.class);
        verify(dishMapper).insert(captor.capture());
        assertNull(captor.getValue().getAuditStatus(),
                "新建默认 ok 由 DB DEFAULT 'ok' 兜底（先发后审：发布即上架），Service 不写审核列");
        assertNull(captor.getValue().getAuditReason());
        assertEquals(99L, created.getId());
    }

    // ================================================================
    // ⑤ 运营列表 audit 参数（契约 §3.5）
    // ================================================================

    private AdminV2ServiceImpl adminV2Service() {
        AdminV2ServiceImpl service = new AdminV2ServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        return service;
    }

    @Test
    void audit参数NULL全量_页出参与filters带审核两键() {
        AdminV2Service service = adminV2Service();
        when(dishMapper.countDishes(any(), any(), any(), any())).thenReturn(1L);
        when(dishMapper.selectDishesPage(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(new HashMap<>(Map.of(
                        "id", 5L, "name", "待审品", "auditStatus", Dish.AUDIT_OK))));

        Map<String, Object> out = service.dishes(1, 20, null, null, null, null);

        verify(dishMapper).countDishes(null, null, null, null);
        verify(dishMapper).selectDishesPage(isNull(), isNull(), isNull(), isNull(), anyLong(), anyInt());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("list");
        assertTrue(list.get(0).containsKey("auditStatus") && list.get(0).containsKey("auditReason"),
                "出参行必须带审核态与驳回理由两键：line=" + list.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) out.get("filters");
        assertTrue(filters.containsKey("auditStatus"), "filters 回显 audit 参数（D-19）：filters=" + filters);
    }

    @Test
    void audit参数rejected只筛驳回件_且归一小写后下传() {
        AdminV2Service service = adminV2Service();
        when(dishMapper.countDishes(any(), any(), any(), any())).thenReturn(0L);

        service.dishes(1, 20, null, null, null, "REJECTED");

        // BE-G6-2 同款：归一小写后才交白名单与 SQL（库里存的就是小写字面量）
        verify(dishMapper).countDishes(null, null, null, Dish.AUDIT_REJECTED);
        verify(dishMapper, never()).selectDishesPage(any(), any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void audit参数白名单外400() {
        AdminV2Service service = adminV2Service();

        BizException ex = assertThrows(BizException.class,
                () -> service.dishes(1, 20, null, null, null, "pending"));

        assertEquals(400, ex.getCode(), "审核态筛选只认 ok/rejected，复用 v2 列表族那句 400");
        verify(dishMapper, never()).countDishes(any(), any(), any(), any());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
