package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.entity.Dish;
import com.fresh.mapper.DishMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code listSeckill} 的收录口径单测（F-74 B1）：秒杀集只收「打了秒杀标、在售、有库存、已填秒杀价」的行。
 *
 * <p><b>证明方式</b>：mapper 是 mock，但它不返回固定列表，而是拿<b>生产代码真渲染出的那段 SQL</b>
 * （wrapper 的 {@code getSqlSegment()} 与 {@code getParamNameValuePairs()}）在内存表上逐行求值。
 * 断言喂给的是生产判据本身，而不是测试重抄一遍条件——重抄会让「代码改了、测试没跟着改」双双失真。
 *
 * <p><b>求值器只认 {@code col = ?} / {@code col > ?} / {@code col IS [NOT] NULL} 与 AND 连接</b>：
 * 认不出的子句直接抛断言失败而不是放行，否则恒假过滤（{@code 1=0}）或一条本类看不懂的谓词都能伪装成全绿。
 *
 * <p><b>每条用例都带正向对照</b>：断的是「存活的恰好是正常那一件」，不是「脏件不在结果里」。
 *
 * <p><b>本类测不成什么</b>：MySQL 对 {@code stock > 0} / {@code IS NOT NULL} 的真实执行，
 * 以及 {@code ORDER BY} 实际回传的行序（求值器只解释 WHERE，排序段只钉字面）；
 * 那两件事由 {@code GET /api/seckill/current} 的接口验收收口。
 */
class DishSeckillFilterTest {

    /** MP 渲染出的参数占位符：{@code #{ew.paramNameValuePairs.MPGENVAL1}} */
    private static final String PARAM_REF = "#\\{(?:ew\\.)?paramNameValuePairs\\.(\\w+)}";
    /** {@code col = #{...}} / {@code col > #{...}} 一类比较子句 */
    private static final Pattern COMPARISON = Pattern.compile("^(\\w+)\\s*(>=|<=|<>|!=|=|>|<)\\s*" + PARAM_REF + "$");
    private static final Pattern IS_NOT_NULL = Pattern.compile("^(\\w+)\\s+IS\\s+NOT\\s+NULL$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IS_NULL = Pattern.compile("^(\\w+)\\s+IS\\s+NULL$", Pattern.CASE_INSENSITIVE);

    /** dish 表的列名 → Dish 属性名，取自 MP 的真映射而非本类按规则猜 */
    private static final Map<String, String> COLUMN_TO_PROPERTY = new HashMap<>();

    /** 当前用例的"内存表"内容 */
    private List<Dish> table = List.of();
    /** 生产代码交进 mapper 的 wrapper，用于事后抽渲染结果 */
    private LambdaQueryWrapper<Dish> lastWrapper;

    private final DishMapper dishMapper = mock(DishMapper.class);
    private DishServiceImpl dishService;

    /**
     * LambdaQueryWrapper 的列名解析依赖实体 TableInfo（生产由 MP 启动扫 mapper 时装配），
     * 本测试不起容器也不连库，故按 MP 官方入口手工灌一次内存缓存（形态照 PaymentSettlementTest）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfo info = TableInfoHelper.initTableInfo(assistant, Dish.class);
        info.getFieldList().forEach(f -> COLUMN_TO_PROPERTY.put(f.getColumn(), f.getProperty()));
        COLUMN_TO_PROPERTY.put(info.getKeyColumn(), info.getKeyProperty());
    }

    @BeforeEach
    void setUp() {
        dishService = new DishServiceImpl();
        ReflectionTestUtils.setField(dishService, "dishMapper", dishMapper);
        when(dishMapper.selectList(any())).thenAnswer(inv -> {
            lastWrapper = inv.getArgument(0);
            return runWhereOnMemoryTable();
        });
    }

    // —— fixture ——

    /** 正常秒杀件：秒杀标 + 在售 + 有库存 + 秒杀价非空 + 审核态 ok，即正向对照那一行（F-116 起秒杀集为四条件） */
    private static Dish seckillDish(long id, String name) {
        Dish d = new Dish();
        d.setId(id);
        d.setName(name);
        d.setPrice(new BigDecimal("10.00"));
        d.setSeckill(1);
        d.setOnSale(1);
        d.setStock(20);
        d.setSeckillPrice(new BigDecimal("7.80"));
        d.setAuditStatus(Dish.AUDIT_OK);
        return d;
    }

    private static List<Long> idsOf(List<Dish> rows) {
        return rows.stream().map(Dish::getId).toList();
    }

    // —— 四条收录口径 ——

    @Test
    void 下架的秒杀件不进秒杀集() {
        Dish offSale = seckillDish(11L, "下架秒杀件");
        offSale.setOnSale(0);
        table = List.of(seckillDish(10L, "正常秒杀件"), offSale);

        assertEquals(List.of(10L), idsOf(dishService.listSeckill()),
                "下架件塞进首页秒杀卡，用户点进去必吃一个 400；渲染出的条件：" + rendered());
    }

    @Test
    void 卖空的秒杀件不进秒杀集() {
        Dish soldOut = seckillDish(12L, "卖空秒杀件");
        soldOut.setStock(0);
        table = List.of(seckillDish(10L, "正常秒杀件"), soldOut);

        assertEquals(List.of(10L), idsOf(dishService.listSeckill()),
                "每笔下单都在扣 stock，卖空是必然事件而非假设；渲染出的条件：" + rendered());
    }

    @Test
    void 未填秒杀价的件不进秒杀集() {
        Dish noPrice = seckillDish(13L, "打标未填价件");
        noPrice.setSeckillPrice(null);
        table = List.of(seckillDish(10L, "正常秒杀件"), noPrice);

        assertEquals(List.of(10L), idsOf(dishService.listSeckill()),
                "计价判据是秒杀标加秒杀价非空，这种行会打秒杀标却按原价收款；渲染出的条件：" + rendered());
    }

    @Test
    void 驳回的秒杀件不进秒杀集() {
        // F-116 §2.1：运营驳回件同样不进首页秒杀卡（详情直链与下单拦截另有对齐口径）
        Dish rejected = seckillDish(14L, "驳回秒杀件");
        rejected.setAuditStatus(Dish.AUDIT_REJECTED);
        table = List.of(seckillDish(10L, "正常秒杀件"), rejected);

        assertEquals(List.of(10L), idsOf(dishService.listSeckill()),
                "驳回品 C 端即刻不可见不可买，秒杀卡不能漏；渲染出的条件：" + rendered());
    }

    @Test
    void 正常秒杀件照常出现且未打标的一向不收() {
        Dish notSeckill = seckillDish(9L, "未打秒杀标的在售件");
        notSeckill.setSeckill(0);
        table = List.of(seckillDish(21L, "正常秒杀件乙"), seckillDish(20L, "正常秒杀件甲"), notSeckill);

        List<Dish> result = dishService.listSeckill();

        assertEquals(List.of(21L, 20L), idsOf(result),
                "正向对照：三条过滤任一条写过头（或整条写成恒假）时这条必红；渲染出的条件：" + rendered());
        assertFalse(idsOf(result).contains(9L),
                "补过滤不该顺手把未打秒杀标的行收进来；渲染出的条件：" + rendered());
        assertTrue(renderedOrderBy().matches("(?i)^\\s*id\\s+ASC\\s*$"),
                "orderByAsc(Dish::getId) 必须仍在链尾，秒杀卡顺序不能变；渲染出的整段：" + rendered());
    }

    // —— 内存表求值：只吃生产代码真渲染出的那段 SQL ——

    /** 把 wrapper 的 WHERE 用在内存表上，返回满足全部条件的行（保持表内原序，本类不断行序） */
    private List<Dish> runWhereOnMemoryTable() {
        Map<String, Object> params = lastWrapper.getParamNameValuePairs();
        String where = extractedWhere();
        if (where.isEmpty()) {
            // 有参数却抽不出 WHERE 文本 = 本类覆盖不了这种渲染形态，绝不能退化成"全表返回"伪装成过滤生效
            assertTrue(params.isEmpty(), "抽不出 WHERE 但参数表非空：" + rendered());
            return new ArrayList<>(table);
        }
        assertFalse(where.toUpperCase().contains(" OR "),
                "求值器只保证 AND 语义，出现 OR 须显式改写本类而不是猜：where=" + where);
        List<Dish> kept = new ArrayList<>();
        for (Dish row : table) {
            boolean allHold = true;
            for (String clause : splitOnAnd(where)) {
                if (!holds(clause, params, row)) {
                    allHold = false;
                    break;
                }
            }
            if (allHold) {
                kept.add(row);
            }
        }
        return kept;
    }

    /** 抽 WHERE：剥尾随 ORDER BY、外层括号与可能的前置 WHERE 关键字 */
    private String extractedWhere() {
        String segment = rendered();
        int orderAt = indexOfIgnoreCase(segment, "ORDER BY");
        if (orderAt >= 0) {
            segment = segment.substring(0, orderAt);
        }
        segment = stripOuterParens(segment);
        if (segment.toUpperCase().startsWith("WHERE")) {
            segment = segment.substring("WHERE".length());
        }
        return stripOuterParens(segment);
    }

    /** 抽 ORDER BY 之后的部分：整段里没有就退回 expression 自己的 orderBy 段（那种形态即"链尾没排序"，断言会红） */
    private String renderedOrderBy() {
        String segment = rendered();
        int orderAt = indexOfIgnoreCase(segment, "ORDER BY");
        String order = orderAt >= 0
                ? segment.substring(orderAt + "ORDER BY".length())
                : lastWrapper.getExpression().getOrderBy().getSqlSegment();
        int kw = indexOfIgnoreCase(order, "ORDER BY");
        return stripOuterParens(kw >= 0 ? order.substring(kw + "ORDER BY".length()) : order);
    }

    private String rendered() {
        return lastWrapper.getSqlSegment();
    }

    private static List<String> splitOnAnd(String where) {
        List<String> clauses = new ArrayList<>();
        for (String piece : where.split("(?i)\\s+AND\\s+")) {
            String clause = piece.trim();
            assertFalse(clause.contains("(") || clause.contains(")"),
                    "带括号的嵌套条件本求值器不解释，须显式改写本类：clause=" + clause);
            clauses.add(clause);
        }
        return clauses;
    }

    /** 单子句求值；认不出的形态直接失败，不猜、不默认放行 */
    private static boolean holds(String clause, Map<String, Object> params, Dish row) {
        Matcher notNull = IS_NOT_NULL.matcher(clause);
        if (notNull.matches()) {
            return columnValue(notNull.group(1), row) != null;
        }
        Matcher nullish = IS_NULL.matcher(clause);
        if (nullish.matches()) {
            return columnValue(nullish.group(1), row) == null;
        }
        Matcher cmp = COMPARISON.matcher(clause);
        if (!cmp.matches()) {
            throw new AssertionError("求值器认不出的子句，拒绝猜测放行：clause=" + clause);
        }
        String key = cmp.group(3);
        assertTrue(params.containsKey(key), "占位符没有对应参数值：clause=" + clause + " params=" + params);
        Object actual = columnValue(cmp.group(1), row);
        Object expected = params.get(key);
        if (actual == null || expected == null) {
            return false; // SQL 三值逻辑：与 NULL 比出 UNKNOWN，WHERE 不成立
        }
        String op = cmp.group(2);
        if (actual instanceof Number && expected instanceof Number) {
            int order = new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString()));
            return switch (op) {
                case "=" -> order == 0;
                case ">" -> order > 0;
                case ">=" -> order >= 0;
                case "<" -> order < 0;
                case "<=" -> order <= 0;
                default -> order != 0; // <> 与 !=
            };
        }
        boolean sameValue = actual.toString().equals(expected.toString());
        if ("=".equals(op)) {
            return sameValue;
        }
        if ("<>".equals(op) || "!=".equals(op)) {
            return !sameValue;
        }
        throw new AssertionError("非数值却用比大小，本求值器不解释：clause=" + clause);
    }

    /** 列名经 TableInfo 的真实映射（on_sale→onSale）取实体字段值 */
    private static Object columnValue(String column, Dish row) {
        String property = COLUMN_TO_PROPERTY.get(column);
        assertTrue(property != null, "渲染出的列在 Dish 的 TableInfo 里没有对应字段：column=" + column);
        try {
            var field = Dish.class.getDeclaredField(property);
            field.setAccessible(true);
            return field.get(row);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("字段取不到：column=" + column + ", property=" + property, e);
        }
    }

    private static String stripOuterParens(String text) {
        String t = text.trim();
        if (t.startsWith("(") && t.endsWith(")") && outerParensWrapAll(t)) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** 首尾那对括号是否真包住整段（避免把 (a AND b) AND (c) 拆坏） */
    private static boolean outerParensWrapAll(String t) {
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i != t.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static int indexOfIgnoreCase(String text, String keyword) {
        return text.toUpperCase().indexOf(keyword.toUpperCase());
    }
}
