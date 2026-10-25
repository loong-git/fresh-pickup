package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-117 结算层数据模型契约单测（不起 Spring 容器、不落库；契约 §5 三组逐条）：
 * ① 下单快照：dish.supply_price=7.5 下单 → order_item.supply_price=7.5；dish NULL → 落 NULL；
 *    供货价不影响 totalPrice 计价（成交价口径断言）。
 * ② 运营端点：PUT 带 supplyPrice=8.8 落库（SET 含 supply_price + 审计 detail 自然带）；显式 null 清除回 NULL；
 *    键缺失不参与 SET（部分更新语义对既有调用方零影响——Jackson「显式 null 调 setter / 键缺失不调」是三态语义根基）；
 *    0/负数/三位小数 → bean validation 400；dish 999999 → 404。
 * ③ m27 重放幂等（照 m26 门禁断言模式做文本级断言——本仓测试全离线无真库，重放与真库断言由 verify 脚本跑）
 *    + settlement DDL 关键约束存在性断言（uk_settle_no / uk_merchant_period 两索引）
 *    + init.sql 与 m27-migration.sql 的 settlement 建表体逐字一致（两处同步纪律的机器保证）
 *    + 红线（契约 §4）：OrderItem 实体不映射 settle_id（零写路径）、src/main/java 无 settlement 表 SQL 触点（零读写）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真库 DECIMAL(10,2) 落值/DEFAULT 兜底、m27 在真库的重放幂等、
 * 双轨闸门/限流（Controller 层）——由 logs/verify-settlement-model.sh 与主会话门禁覆盖。
 */
class SettlementModelTest {

    private static final Long USER_ID = 4L;
    private static final long DISH_ID = 5L;

    /** jakarta validator（@Valid 的同一套约束，不起容器直接驱动——hibernate-validator 随 starter-web 在 test classpath） */
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final AdminAuditLogMapper auditMapper = mock(AdminAuditLogMapper.class);

    /**
     * LambdaUpdateWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 DishAuditFlowTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Dish.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    private OrderServiceImpl orderService;
    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderItemMapper", orderItemMapper);
        ReflectionTestUtils.setField(orderService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(orderService, "storeMapper", mock(StoreMapper.class));
        ReflectionTestUtils.setField(orderService, "arrivalBatchMapper", mock(ArrivalBatchMapper.class));
        ReflectionTestUtils.setField(orderService, "couponService", mock(CouponService.class));
        when(orderMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中 → 进入校验段

        adminService = new AdminServiceImpl();
        ReflectionTestUtils.setField(adminService, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(adminService, "adminAuditLogMapper", auditMapper);
        ReflectionTestUtils.setField(adminService, "adminKey", "test-admin-key");
    }

    // ---------------------------------------------------------------- fixtures

    private static Dish dish(BigDecimal supplyPrice) {
        Dish d = new Dish();
        d.setId(DISH_ID);
        d.setName("供价测试品");
        d.setPrice(new BigDecimal("10.00"));
        d.setStock(20);
        d.setOnSale(1);
        d.setSeckill(0);
        d.setMerchantId(1L);
        d.setSupplyPrice(supplyPrice);
        return d;
    }

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

    /** 下单并捕获落库明细（mock 主单 insert 不生成雪花号，断言只看明细内容与金额，不依赖 id） */
    private OrderItem createOrderAndCaptureItem() {
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(new BigDecimal("7.5")));
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);
        orderService.createOrder(cart("f117-snapshot"), USER_ID);
        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ================================================================
    // ① 下单快照（契约 §5-1 / §2）
    // ================================================================

    @Test
    void 下单快照_供价7_5落明细_成交价计价不受供价影响() {
        OrderItem item = createOrderAndCaptureItem();

        assertEquals(0, new BigDecimal("7.5").compareTo(item.getSupplyPrice()),
                "order_item.supply_price 必须快照 dish.supply_price=7.5：item=" + item.getSupplyPrice());
        assertEquals(0, new BigDecimal("10.00").compareTo(item.getPrice()),
                "成交单价仍是 dish 现价（供价正交）：item=" + item.getPrice());
    }

    @Test
    void 下单快照_dish未设供价_明细落null_计价照常() {
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish(null));
        when(dishMapper.decreaseStock(DISH_ID, 1)).thenReturn(1);

        Order order = orderService.createOrder(cart("f117-snapshot-null"), USER_ID);

        ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(captor.capture());
        assertNull(captor.getValue().getSupplyPrice(),
                "dish.supply_price NULL → 快照落 NULL（MP 默认 NOT_NULL 策略下该列不进 INSERT，由 DB NULL 兜底）");
        assertEquals(0, new BigDecimal("10.00").compareTo(order.getTotalPrice()),
                "totalPrice 按成交价计（供价 NULL/非 NULL 都不参与）：order=" + order.getTotalPrice());
    }

    @Test
    void 下单快照_mp插入语句不含settle_id_零写路径() {
        createOrderAndCaptureItem();

        // settle_id 零写路径的实体侧证据：OrderItem 不映射该列 → MP 为 insert 生成的列集合里没有它
        Set<String> entityFields = Arrays.stream(OrderItem.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet());
        assertFalse(entityFields.contains("settleId"),
                "OrderItem 绝不允许映射 settle_id（F-118 跑批才写；本片只建列零写路径，契约 §4）：fields=" + entityFields);
        assertTrue(entityFields.contains("supplyPrice"), "快照列必须映射才能落库");
    }

    // ================================================================
    // ② 运营端点（契约 §5-2 / §3）
    // ================================================================

    private AdminDishUpdateDTO dtoWithSupplyPrice(String json) throws IOException {
        // 走真 Jackson 反序列化（三态语义的根基在 Jackson 的键缺失/显式 null 行为，直 set 会绕过它）
        return new ObjectMapper().readValue(json, AdminDishUpdateDTO.class);
    }

    private Dish storedDish(BigDecimal supplyPrice) {
        Dish d = dish(supplyPrice);
        d.setId(7L);
        when(dishMapper.selectById(7L)).thenReturn(d);
        when(dishMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        return d;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Dish> capturedUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<Dish>> captor =
                (ArgumentCaptor<LambdaUpdateWrapper<Dish>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dishMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private AdminAuditLog capturedAudit() {
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditMapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void supplyPrice8_8落库_SET含supply_price_审计自然带() throws IOException {
        storedDish(null);
        AdminDishUpdateDTO dto = dtoWithSupplyPrice("{\"supplyPrice\":\"8.8\"}");

        adminService.updateDish(7L, dto, "admin_user:7");

        LambdaUpdateWrapper<Dish> wrapper = capturedUpdateWrapper();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("supply_price"), "SET 必须含 supply_price：SET=" + sqlSet);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(new BigDecimal("8.8")),
                "supply_price 绑 8.8：params=" + wrapper.getParamNameValuePairs());
        assertTrue(capturedAudit().getDetail().contains("supplyPrice=8.8"),
                "dish.update 审计 changed 清单自然带 supplyPrice：detail=" + capturedAudit().getDetail());
    }

    @Test
    void supplyPrice显式null清除_SET回NULL() throws IOException {
        Dish stored = storedDish(new BigDecimal("8.8"));
        AdminDishUpdateDTO dto = dtoWithSupplyPrice("{\"supplyPrice\":null}");

        adminService.updateDish(7L, dto, "admin_user:7");

        LambdaUpdateWrapper<Dish> wrapper = capturedUpdateWrapper();
        assertTrue(wrapper.getSqlSet().contains("supply_price"), "显式 null 也要 SET（清除回 NULL）：SET=" + wrapper.getSqlSet());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(null),
                "set 传 null 拼 SET supply_price=NULL（restore 清理由同款姿势）：params=" + wrapper.getParamNameValuePairs());
        assertTrue(capturedAudit().getDetail().contains("supplyPrice=8.8,supplyPrice=null")
                        || capturedAudit().getDetail().contains("supplyPrice=null"),
                "审计 new 侧如实记清除：detail=" + capturedAudit().getDetail());
        assertEquals(new BigDecimal("8.8"), stored.getSupplyPrice(), "old 侧取 selectById 快照");
    }

    @Test
    void supplyPrice键缺失不参与SET_既有调用零影响() throws IOException {
        storedDish(new BigDecimal("8.8"));
        // 旧式请求体（只带 price）：supplyPrice 键缺失 → present=false → 绝不 SET supply_price（部分更新语义保持）
        AdminDishUpdateDTO dto = dtoWithSupplyPrice("{\"price\":\"9.90\"}");
        assertFalse(dto.isSupplyPricePresent(), "键缺失时 present 必须 false（Jackson 对缺失键不调 setter）");

        adminService.updateDish(7L, dto, "admin_user:7");

        assertFalse(capturedUpdateWrapper().getSqlSet().contains("supply_price"),
                "键缺失绝不 SET supply_price（否则历史脚本改价会顺手清掉供货价）：SET=" + capturedUpdateWrapper().getSqlSet());
    }

    @Test
    void supplyPrice零负数三位小数_validation400() throws IOException {
        // 键缺失自身合法（present=false 是正常分支）；非法值由 @DecimalMin/@Digits 在绑定时拦
        assertTrue(VALIDATOR.validate(dtoWithSupplyPrice("{}")).isEmpty());

        Set<ConstraintViolation<AdminDishUpdateDTO>> zero =
                VALIDATOR.validate(dtoWithSupplyPrice("{\"supplyPrice\":\"0\"}"));
        Set<ConstraintViolation<AdminDishUpdateDTO>> negative =
                VALIDATOR.validate(dtoWithSupplyPrice("{\"supplyPrice\":\"-1\"}"));
        Set<ConstraintViolation<AdminDishUpdateDTO>> threeScale =
                VALIDATOR.validate(dtoWithSupplyPrice("{\"supplyPrice\":\"7.555\"}"));
        Set<ConstraintViolation<AdminDishUpdateDTO>> ok =
                VALIDATOR.validate(dtoWithSupplyPrice("{\"supplyPrice\":\"8.8\"}"));

        assertFalse(zero.isEmpty(), "0 必须 400（>0 口径）");
        assertFalse(negative.isEmpty(), "负数必须 400（>0 口径）");
        assertFalse(threeScale.isEmpty(), "三位小数必须 400（DECIMAL(10,2) 列口径）");
        assertTrue(ok.isEmpty(), "8.8 合法");
        // 400 文案与 front-admin WRITE_CODE_HINTS 转述的两句对齐（一处真源）
        assertTrue(threeScale.stream().anyMatch(v -> "供货价最多两位小数".equals(v.getMessage())));
        assertTrue(zero.stream().anyMatch(v -> "供货价必须大于 0".equals(v.getMessage())));
    }

    @Test
    void dish不存在404() throws IOException {
        when(dishMapper.selectById(999999L)).thenReturn(null);

        com.fresh.common.BizException ex = assertThrows(com.fresh.common.BizException.class,
                () -> adminService.updateDish(999999L, dtoWithSupplyPrice("{\"supplyPrice\":\"8.8\"}"), "admin_user:7"));
        assertEquals(404, ex.getCode());
        assertEquals("菜品不存在", ex.getMessage());
        verify(dishMapper, never()).update(any(), any());
    }

    // ================================================================
    // ③ m27 重放幂等 + settlement DDL 断言（契约 §5-3，文本级；真库重放归 verify 脚本）
    // ================================================================

    /** 在两种常见工作目录里定位 sql 文件（Maven 模块目录 backEnd/ / 仓库根），找不到即 assumption 跳过（照 TraceIdFilterTest） */
    private static Path locateSql(String relative) {
        String[] candidates = {"sql/" + relative, "backEnd/sql/" + relative, "../backEnd/sql/" + relative};
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static String migrationSql() throws IOException {
        Path path = locateSql("m27-migration.sql");
        assumeTrue(path != null, "未找到 m27-migration.sql（工作目录非 backEnd/ 或仓库根），本条跳过");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String initSql() throws IOException {
        Path path = locateSql("init.sql");
        assumeTrue(path != null, "未找到 init.sql（工作目录非 backEnd/ 或仓库根），本条跳过");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 提取建表体：从 CREATE TABLE IF NOT EXISTS settlement 起到本条语句分号止（两处逐字一致的比较基元） */
    private static String settlementDdlOf(String sql) {
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS settlement");
        assertTrue(start >= 0, "settlement 建表体必须存在");
        int end = sql.indexOf(';', start);
        assertTrue(end > start, "settlement 建表体必须以分号收尾");
        return sql.substring(start, end + 1);
    }

    @Test
    void m27幂等骨架在位_判存加PREPARE_零破坏性DML() throws IOException {
        String sql = migrationSql();

        // 幂等骨架（照 m26 错误码定性写法）：information_schema 判列存在才 ALTER + PREPARE 动态执行
        assertTrue(sql.contains("information_schema.COLUMNS"), "加列幂等判定必须查 information_schema.COLUMNS");
        assertEquals(2, countOccurrences(sql, "PREPARE m27_stmt FROM @m27_ddl"),
                "两段加列各一套 PREPARE（dish / order_item）");
        assertEquals(2, countOccurrences(sql, "DEALLOCATE PREPARE m27_stmt;"), "PREPARE 必须成对释放");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS settlement"), "settlement 用 IF NOT EXISTS 天然幂等");
        // 可整文件重复执行的静态面：无破坏性 DML 语句（注释行以 -- 开头不会命中行首判定）
        for (String line : sql.split("\n")) {
            String trimmed = line.stripLeading();
            assertFalse(trimmed.matches("(?i)(DROP|TRUNCATE|DELETE|UPDATE)\\b.*"),
                    "m27 不允许出现破坏性 DML 语句（幂等声明）：line=" + line);
        }
    }

    @Test
    void m27门禁断言照m26模式在位() throws IOException {
        String sql = migrationSql();
        // m26 尾部「门禁断言 SELECT（只读、零副作用）」同款六条：列数 ×2 + uk 索引 + 三列零值/零行
        assertTrue(sql.contains("AS dish_columns"), "断言 1 dish 列数在位");
        assertTrue(sql.contains("AS order_item_columns"), "断言 2 order_item 列数在位");
        assertTrue(sql.contains("AS settlement_uk_indexes"), "断言 3 settlement uk 两索引在位");
        assertTrue(sql.contains("AS dish_supply_price_not_null"), "断言 4 dish.supply_price 零回填在位");
        assertTrue(sql.contains("AS order_item_settle_id_not_null"), "断言 5 order_item.settle_id 零写在位");
        assertTrue(sql.contains("AS settlement_rows"), "断言 6 settlement 零行在位");
    }

    @Test
    void settlementDDL_uk两索引存在_且init与迁移两处逐字一致() throws IOException {
        String migration = migrationSql();
        String init = initSql();

        // §5-3 关键约束存在性：uk_settle_no / uk_merchant_period 两索引（06 册 §4.5 纸面定稿点名的幂等模型前提）
        String ddl = settlementDdlOf(migration);
        assertTrue(ddl.contains("UNIQUE KEY uk_settle_no (settle_no)"), "uk_settle_no 必须在位：DDL=" + ddl);
        assertTrue(ddl.contains("UNIQUE KEY uk_merchant_period (merchant_id, period_start, period_end)"),
                "uk_merchant_period 一期一单必须在位：DDL=" + ddl);
        assertTrue(ddl.contains("KEY idx_merchant_status (merchant_id, status)"), "idx_merchant_status 必须在位");
        assertTrue(ddl.contains("DEFAULT 'accounted'"), "status 本片只登记 accounted（08 册 §2.2-5）：DDL=" + ddl);

        // 「同步：init.sql 建表体与本文件逐字一致」的机器保证（m26 同款两处同步纪律）
        assertEquals(ddl, settlementDdlOf(init),
                "init.sql 与 m27-migration.sql 的 settlement 建表体必须逐字一致（改结构须两处同步）");
    }

    @Test
    void initSql并库三段在位_列序照迁移后物理序() throws IOException {
        String init = initSql();

        // dish 建表体（audit_reason 之后表尾追加）——列名与列型间是建表体的多空格对齐，断言用 \s+ 不绑死空格数；
        // 位置比较必须用「列定义」正则的 start（裸 indexOf 会命中头注/注释里的同名词）
        java.util.regex.Matcher dishSupply = Pattern.compile("supply_price\\s+DECIMAL\\(10,2\\)").matcher(init);
        assertTrue(dishSupply.find(), "dish.supply_price 必须并库");
        assertTrue(dishSupply.start() > init.indexOf("audit_reason VARCHAR(200)"),
                "dish 侧列序照迁移后物理序：supply_price 在 audit_reason 之后（表尾）");
        // order_item 建表体（fulfill_status 之后表尾追加 + idx_settle）
        java.util.regex.Matcher settleId = Pattern.compile("settle_id\\s+BIGINT").matcher(init);
        assertTrue(settleId.find(), "order_item.settle_id 必须并库");
        assertTrue(init.contains("INDEX idx_settle (settle_id)"), "idx_settle 必须并库");
        assertTrue(settleId.start() > init.indexOf("fulfill_status VARCHAR(20)"),
                "order_item 侧列序照迁移后物理序：settle_id 在 fulfill_status 之后（表尾）");
        // settlement 表零种子零 UPDATE（本片零读写）
        assertFalse(init.matches("(?s).*INSERT INTO settlement.*"),
                "settlement 不允许有任何种子（本片零写入）");
    }

    @Test
    void 红线_settlement零读写_主代码无SQL触点() throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");

        // 契约 §4：settlement 表本片零读写（无端点无服务逻辑）——主代码不允许出现该表的任何 SQL 触点/实体映射
        try (Stream<Path> paths = Files.walk(mainJava)) {
            List<String> violations = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .filter(text -> text.contains("@TableName(\"settlement\"")
                            || text.contains("FROM settlement")
                            || text.contains("INTO settlement")
                            || text.contains("UPDATE settlement"))
                    .toList();
            assertTrue(violations.isEmpty(),
                    "settlement 表必须零读写（F-118 才有生成入口）：命中 " + violations.size() + " 个文件");
        }
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
