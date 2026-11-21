package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.R;
import com.fresh.controller.PartnerController;
import com.fresh.entity.PartnerWalletFlow;
import com.fresh.entity.Store;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.StoreMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-8 片① 待结算佣金预估单测（不起 Spring 容器、不落库；mock mapper 姿势照
 * PartnerCommissionTest 同款：ReflectionTestUtils 直灌依赖 + TableInfo 内存缓存）。
 *
 * <p>覆盖契约 D1/D2 判据：owner 店集归属（wrapper 绑定登录 userId）、候选订单计入、
 * 逐单 total_price×COMMISSION_RATE 两位 HALF_UP 求和、0 元单佣金计 0、空店/空候选 200 空态、
 * 无店短路不进订单 SQL；另加两条结构性断言钉死不漂移面——候选 SQL 四条件与
 * {@code selectSettleCandidates} 逐字同族、grant 排除与 flowExists 同判据（常量拼接无第二字面量）、
 * 费率单源（实现类无第二处 0.03 字面量）与 controller rate 展示形态=rate_snapshot。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：SQL 谓词的真库筛选行为（未支付排除/已挂锚排除/
 * 明细全缺货排除/已记 grant 排除是 mock 喂不出来的库侧行为）——由结构性断言钉 SQL 文本 +
 * 主会话浏览器验收（真库跑批前后预估归零）覆盖，与 MerchantReviewListTest 的边界声明同口径。
 */
class PartnerWalletPendingEstimateTest {

    private static final Long OWNER_A = 7L;
    private static final Long STORE_A = 1L;
    private static final Long STORE_B = 2L;

    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);

    private PartnerWalletServiceImpl walletService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，照 PartnerCommissionTest 同款手法手工灌内存缓存
     * （本路径只对 Store 建 wrapper：select/eq 两个 lambda 都解析 Store 的列）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Store.class);
    }

    @BeforeEach
    void setUp() {
        walletService = new PartnerWalletServiceImpl();
        ReflectionTestUtils.setField(walletService, "storeMapper", storeMapper);
        ReflectionTestUtils.setField(walletService, "orderItemMapper", orderItemMapper);
    }

    // ---------------------------------------------------------------- fixtures

    private static Store store(Long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("测试店" + id);
        s.setOwnerUserId(OWNER_A);
        return s;
    }

    /** 候选订单行（mapper 出参形状：列别名驼峰键 {id, totalPrice}） */
    private static Map<String, Object> candidateRow(String orderId, String totalPrice) {
        return Map.of("id", orderId, "totalPrice", new BigDecimal(totalPrice));
    }

    private PartnerController controllerWithService() {
        PartnerController controller = new PartnerController();
        ReflectionTestUtils.setField(controller, "partnerWalletService", walletService);
        return controller;
    }

    // ================================================================
    // ① 正例：owner 店候选订单计入，逐单 HALF_UP 两位求和（契约验收例 42.80 → 1.28）
    // ================================================================

    @Test
    void 预估_owner两店候选订单计入_逐单HALF_UP两位求和() {
        when(storeMapper.selectList(any())).thenReturn(List.of(store(STORE_A), store(STORE_B)));
        when(orderItemMapper.selectPendingCommissionOrders(List.of(STORE_A, STORE_B))).thenReturn(List.of(
                candidateRow("O1001", "42.80"),   // 42.80×3%=1.284 → HALF_UP 两位 1.28（契约 D3 验收例）
                candidateRow("O1002", "33.50"),   // 33.50×3%=1.005 → HALF_UP 1.01（HALF_EVEN 会是 1.00，此例钉死 HALF_UP）
                candidateRow("O1003", "10.00"))); // 10.00×3%=0.30

        Map<String, Object> data = walletService.estimatePending(OWNER_A);

        assertEquals(3L, ((Number) data.get("pendingCount")).longValue(), "候选订单数=去重订单数");
        assertEquals(0, new BigDecimal("2.59").compareTo((BigDecimal) data.get("pendingCommission")),
                "Σ=1.28+1.01+0.30=2.59（逐单两位 HALF_UP 后再求和）");
    }

    // ================================================================
    // ② 空态（契约 D2：无 owner 店/无候选订单 → {0, 0.00} 恒 200，空态不是错误）
    // ================================================================

    @Test
    void 预估_owner无店_短路不进订单SQL_回0与0点00() {
        when(storeMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> data = walletService.estimatePending(OWNER_A);

        assertEquals(0L, ((Number) data.get("pendingCount")).longValue());
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) data.get("pendingCommission")));
        verify(orderItemMapper, never()).selectPendingCommissionOrders(any());
    }

    @Test
    void 预估_有店无候选订单_回0与0点00() {
        when(storeMapper.selectList(any())).thenReturn(List.of(store(STORE_A)));
        when(orderItemMapper.selectPendingCommissionOrders(List.of(STORE_A))).thenReturn(List.of());

        Map<String, Object> data = walletService.estimatePending(OWNER_A);

        assertEquals(0L, ((Number) data.get("pendingCount")).longValue());
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) data.get("pendingCommission")));
    }

    // ================================================================
    // ③ 0 元单：仍是候选订单（pay_status=1 且含候选明细），佣金计 0
    // ================================================================

    @Test
    void 预估_0元单计入候选_佣金计0() {
        when(storeMapper.selectList(any())).thenReturn(List.of(store(STORE_A)));
        when(orderItemMapper.selectPendingCommissionOrders(List.of(STORE_A))).thenReturn(List.of(
                candidateRow("O2001", "0.00")));

        Map<String, Object> data = walletService.estimatePending(OWNER_A);

        assertEquals(1L, ((Number) data.get("pendingCount")).longValue(), "0 元单也是待结算候选订单");
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) data.get("pendingCommission")),
                "0 元单佣金计 0（total_price×费率=0）");
    }

    // ================================================================
    // ④ 归属隔离：店集绑定 owner 谓词（wrapper 只绑登录 userId），订单 SQL 只收 owner 店集
    // ================================================================

    @Test
    void 预估_归属隔离_店集只由owner谓词定_订单SQL只收owner店集() {
        when(storeMapper.selectList(any())).thenReturn(List.of(store(STORE_A)));
        when(orderItemMapper.selectPendingCommissionOrders(List.of(STORE_A))).thenReturn(List.of(
                candidateRow("O1001", "42.80")));

        walletService.estimatePending(OWNER_A);

        // 店集查询的 wrapper 只绑登录 userId 本身（store.owner_user_id=userId 的接缝），
        // 不存在任何可由客户端传入的 storeId/owner 入参——归属面服务端定死。
        // MP 的 eq 谓词值是懒绑定（渲染 SQL 时才进 paramNameValuePairs），先渲染再取证
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Store>> wrapperCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(storeMapper).selectList(wrapperCaptor.capture());
        AbstractWrapper<Store, ?, ?> wrapper = (AbstractWrapper) wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("owner_user_id"),
                "店集谓词列必须是 owner_user_id");
        assertEquals(1, wrapper.getParamNameValuePairs().size(), "只绑一个谓词值");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(OWNER_A),
                "绑定的必须是登录 userId 本身");
        // 订单 SQL 只收 owner 店集（他店 id 从未进入任何查询入参）
        verify(orderItemMapper).selectPendingCommissionOrders(List.of(STORE_A));
    }

    // ================================================================
    // ⑤ controller 端点：出参三键，rate 展示形态与流水 rate_snapshot 一致
    // ================================================================

    @Test
    void controller端点_出参三键_rate展示形态与rate_snapshot一致() {
        when(storeMapper.selectList(any())).thenReturn(List.of(store(STORE_A)));
        when(orderItemMapper.selectPendingCommissionOrders(List.of(STORE_A))).thenReturn(List.of(
                candidateRow("O1001", "42.80")));

        R<Map<String, Object>> body = controllerWithService().walletPendingEstimate(OWNER_A);

        assertEquals(200, body.getCode(), "HTTP 恒 200 业务码惯例");
        assertEquals(1L, ((Number) body.getData().get("pendingCount")).longValue());
        assertEquals(0, new BigDecimal("1.28").compareTo((BigDecimal) body.getData().get("pendingCommission")));
        assertEquals("0.0300", body.getData().get("rate"), "rate 展示位字面量");
        assertEquals(PartnerWalletFlow.RATE_SNAPSHOT.toPlainString(), body.getData().get("rate"),
                "rate 展示形态必须与流水 rate_snapshot 展示形态一致");
    }

    @Test
    void controller端点_空态三键齐全_count0佣金0点00() {
        when(storeMapper.selectList(any())).thenReturn(List.of());

        R<Map<String, Object>> body = controllerWithService().walletPendingEstimate(OWNER_A);

        assertEquals(200, body.getCode());
        assertEquals(3, body.getData().size(), "恰三键：pendingCount/pendingCommission/rate");
        assertEquals(0L, ((Number) body.getData().get("pendingCount")).longValue());
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) body.getData().get("pendingCommission")));
        assertEquals("0.0300", body.getData().get("rate"));
    }

    // ================================================================
    // ⑥ 结构性断言（照 PartnerCommissionTest#mapperSource 手法）：SQL 文本钉死不漂移面
    // ================================================================

    @Test
    void 结构性断言_候选SQL四条件与selectSettleCandidates逐字同族_grant排除与flowExists同判据()
            throws IOException, NoSuchMethodException {
        String sql = selectAnnotationValue(
                OrderItemMapper.class, "selectPendingCommissionOrders", List.class);
        String batchSql = selectAnnotationValue(
                OrderItemMapper.class, "selectSettleCandidates", Long.class);
        // 明细四条件逐字同族（预估与跑批共用同一候选判据，两处永不漂移）
        for (String predicate : List.of("oi.settle_id IS NULL", "oi.supply_price IS NOT NULL",
                "oi.fulfill_status != 'shortage'", "o.pay_status = 1")) {
            assertTrue(batchSql.contains(predicate), "跑批候选 SQL 应含四条件：" + predicate);
            assertTrue(sql.contains(predicate), "预估候选 SQL 应含同族四条件：" + predicate);
        }
        // 订单级聚合面：EXISTS 表达「订单含至少一条候选明细」
        assertTrue(sql.contains("EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id = o.id"),
                "候选明细 EXISTS 在位");
        // grant 排除与 recordForSettlement 的 flowExists 同判据：biz_type 经
        // PartnerWalletFlow.BIZ_GRANT 常量拼进 SQL（uk_order_biz 先到先得，跑批后预估归零）
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM partner_wallet_flow f WHERE f.order_id = o.id"),
                "grant 排除 NOT EXISTS 在位");
        assertTrue(sql.contains("f.biz_type = '" + PartnerWalletFlow.BIZ_GRANT + "'"),
                "biz_type 判据=PartnerWalletFlow.BIZ_GRANT（与 flowExists 同源）");

        // 源码级单源断言：mapper 文本不出现第二处 'commission_grant' 字面量
        String mapperSource = mainJavaSource("com/fresh/mapper/OrderItemMapper.java");
        assertFalse(mapperSource.contains("'commission_grant'"),
                "biz_type 字面量禁第二处（只允许经 PartnerWalletFlow.BIZ_GRANT 常量拼接）");
        assertTrue(mapperSource.contains("PartnerWalletFlow.BIZ_GRANT"),
                "预估 SQL 的 biz_type 判据经常量拼接");
    }

    @Test
    void 结构性断言_费率单源_实现类无第二处0点03字面量() throws IOException {
        String implSource = mainJavaSource("com/fresh/service/impl/PartnerWalletServiceImpl.java");
        long rateLiterals = implSource.split("0\\.03", -1).length - 1;
        assertEquals(1, rateLiterals,
                "费率字面量只允许 COMMISSION_RATE 常量一处（预估与记账同源，禁第二处 0.03）");
        // 预估实现引用同一常量（不另立费率）
        assertTrue(implSource.contains("commissionSum.add(total.multiply(COMMISSION_RATE)"),
                "预估逐单佣金必须乘 COMMISSION_RATE 单源常量");
        assertTrue(implSource.contains("RoundingMode.HALF_UP"),
                "舍入与 recordForSettlement 同为 HALF_UP");
    }

    // ---------------------------------------------------------------- 辅助

    /** 读注解里的运行期 SQL 文本（常量拼接已在编译期展开，文本级断言基元） */
    private static String selectAnnotationValue(Class<?> mapper, String method, Class<?> paramType)
            throws NoSuchMethodException {
        return mapper.getMethod(method, paramType).getAnnotation(Select.class).value()[0];
    }

    private static Path locateMainJava() {
        String[] candidates = {"src/main/java", "backEnd/src/main/java", "../backEnd/src/main/java"};
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /** 读 main 源码（结构性文本断言基元，照 PartnerCommissionTest 同款手法） */
    private static String mainJavaSource(String relative) throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path file = mainJava.resolve(relative);
        assumeTrue(Files.isRegularFile(file), "未找到 " + relative + "，本条跳过");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
