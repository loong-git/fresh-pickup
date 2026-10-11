package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.service.MerchantOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 商家端只读备货单实现（F-17/G-03 Step 1）。纯读、无事务写、不落运营审计（RC 红线，与 G-02 六个端点
 * 「四个写才审计」口径一致），也**不经过**商家侧的写闸门判定——读放行是 G-02 已定口径，
 * 停业商家仍需看自己的历史备货单去收尾备货（契约 F-17.2 #8）。
 *
 * <p>归属（R3 同型，本期为读）：取数只经 OrderItemMapper 那 8 条 @Select（F-17 找茬轮 G-1 加了
 * 带 storeId 的计数孪生条），每一条都恒带
 * {@code oi.merchant_id = #{merchantId}}；本类不存在任何绕过这些方法的读路径，
 * 也没有「merchantId 为空就不加条件」的分支——首行判空直接 403（同 MerchantDishServiceImpl:107-109）。
 * storeId 只作过滤、不作归属。
 *
 * <p>分页（RB 红线）：本工程没注册 MP 的内置分页拦截器，用那一套分页对象会静默返回全量且 total=0，
 * 故这里走「页 + 独立 COUNT」两条手写 SQL，offset 自己算（母版是 OrderServiceImpl#getOrders:257 的
 * {@code wrapper.last("LIMIT ...")}，本类改用 @Select 的 {@code LIMIT #{offset}, #{size}} 以便带 JOIN）。
 *
 * <p>脱敏（RD 红线）：orders.phone 是明文列，只经 PhoneMask.mask 后以 maskedPhone 出参；
 * 出参 map 是逐键重新拼的，源行的 phone 键**不进**返回体，address/user_id 连查都没查。
 */
@Slf4j
@Service
public class MerchantOrderServiceImpl implements MerchantOrderService {

    /** 端点① date 缺省口径：明天（与 23:00 截单的 T+1 波次一致，契约 F-17.3） */
    private static final int DEFAULT_PICKUP_DAY_OFFSET = 1;

    /** 端点② size 默认 20、上限 50（夹而不拒，实际生效值回显） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** 出参 date 的格式闸门（契约 F-17.3）：先形状，再交 LocalDate 裁决真日期（2026-13-99 形状过、值不过，同样 400） */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** status 值域；null 与非值域取值一律兜底 all（同 G-02 边界 14 口径：未知 tab 不报错，只退化为不限） */
    private static final String STATUS_ALL = "all";
    private static final Set<String> STATUS_VALUES = Set.of("pending_pickup", "completed", STATUS_ALL);

    /**
     * store_id / store_name 为 NULL 时的组名（契约 F-17.2 #3：现库 2 笔待提单里就有 1 笔 store_id NULL，
     * 必须单独成组、不丢行、不 500）。这是展示文案不是错误文案，不受 RF「错误文案零新增」约束。
     */
    private static final String UNASSIGNED_STORE_NAME = "未指定自提点";

    /** 400 文案（RF 红线：逐字复用 GlobalExceptionHandler:51 已有的那条，不新增句子） */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Override
    public Map<String, Object> pickupPlan(Long merchantId, String date, Long storeId) {
        assertMerchant(merchantId);

        // date 为空 → 默认明天，且必须把生效值回显（防「静默改口径」）；非空 → 严格校验
        boolean dateAbsent = date == null || date.trim().isEmpty();
        LocalDate effectiveDate = dateAbsent
                ? LocalDate.now().plusDays(DEFAULT_PICKUP_DAY_OFFSET)
                : parseDate(date.trim());

        // storeId 为空/非空走两条不同 SQL：Long 传 null 给 (#{storeId} IS NULL OR ...) 会因缺 jdbcType
        // 抛 JdbcType OTHER（契约 F-17.2 #4），故 Service 选方法，任何一条 SQL 都不绑定 null
        List<Map<String, Object>> rows = storeId == null
                ? orderItemMapper.selectPickupPlan(merchantId, effectiveDate)
                : orderItemMapper.selectPickupPlanByStore(merchantId, effectiveDate, storeId);

        // 空结果短路（G-1/M2）：该日无货是稳态最常见形态，rows 为空时店级计数必然也用不上，
        // 直接返回空组、跳过第二条聚合 SQL——写法对齐端点② total==0 短路那条查询的同型口径。
        if (rows.isEmpty()) {
            Map<String, Object> emptyData = new LinkedHashMap<>();
            emptyData.put("date", effectiveDate.toString());
            emptyData.put("groups", List.of());
            return emptyData;
        }

        // 组头「N 单」= 店级去重单数。聚合行的 orderCount 是 dish 级（一笔含多菜的订单会在多个 dish 行里
        // 各计一次），当组头数会重复计数，故单独按店取。
        // storeId 非空时谓词下推：走带 store_id 等值的孪生条（同「storeId 二选一」既有模式，不绑 null），
        // 与「全店版按 key 取那一行」逐字相等（G-1/M2）。
        List<Map<String, Object>> storeCounts = storeId == null
                ? orderItemMapper.countPickupOrdersPerStore(merchantId, effectiveDate)
                : orderItemMapper.countPickupOrdersForStore(merchantId, effectiveDate, storeId);
        Map<Object, Integer> orderCountByStore = new LinkedHashMap<>();
        for (Map<String, Object> row : storeCounts) {
            Integer count = toInteger(row.get("orderCount"));
            orderCountByStore.put(row.get("storeId"), count == null ? 0 : count);
        }

        // key = store_id（**可能是 null**）→ 用允许 null key 的 LinkedHashMap，既 NPE 不了也丢不了行。
        // 组键恒为 store_id 单列（G-2/M3）：SQL 的 GROUP BY 还带 store_name（admin 改店名后同一店
        // 新旧快照会裂成多条聚合行），若展示层按 (store_id, store_name) 建组，同店会出双组头、
        // 且各自取同一个**店级** orderCount → 组头之和 > 真实单数；mandated 聚合口径不能动，
        // 故在这里按 store_id 合键——同店多快照的 items 并进同一组，组名取该 store_id 的第一条
        // 非 null 店名快照，store_id 为 NULL 才落「未指定自提点」（storeName()）。
        Map<Object, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<Object, List<Map<String, Object>>> itemsByStore = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object storeKey = row.get("storeId");
            Map<String, Object> group = groups.get(storeKey);
            if (group == null) {
                group = new LinkedHashMap<>();
                group.put("storeId", storeKey);
                group.put("storeName", row.get("storeName"));
                group.put("orderCount", orderCountByStore.getOrDefault(storeKey, 0));
                groups.put(storeKey, group);
                itemsByStore.put(storeKey, new ArrayList<>());
            } else if (group.get("storeName") == null && row.get("storeName") != null) {
                // 首行店名为 null（同 store_id 下裂出的另一快照行）→ 用第一条非 null 快照补位
                group.put("storeName", row.get("storeName"));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dishId", row.get("dishId"));
            item.put("dishName", row.get("dishName"));
            item.put("quantity", toInteger(row.get("quantity")));
            itemsByStore.get(storeKey).add(item);
        }

        List<Map<String, Object>> groupList = new ArrayList<>(groups.size());
        for (Map.Entry<Object, Map<String, Object>> entry : groups.entrySet()) {
            Map<String, Object> group = entry.getValue();
            group.put("storeName", storeName(group.get("storeName")));
            group.put("items", itemsByStore.get(entry.getKey()));
            groupList.add(group);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", effectiveDate.toString());
        data.put("groups", groupList);
        return data;
    }

    @Override
    public Map<String, Object> orderItems(Long merchantId, String date, String status, Integer page, Integer size) {
        assertMerchant(merchantId);

        // 端点② 的 date 只是过滤器，**没有默认值**（端点①才默认明天并回显），为空 = 不限自提日期。
        // 同样为了不把 null 绑进 SQL，带 date / 不带 date 各一条方法。
        boolean dateAbsent = date == null || date.trim().isEmpty();
        LocalDate pickupDate = dateAbsent ? null : parseDate(date.trim());

        // status 缺省（null）与未知取值都兜底 all。**判 null 必须写在 contains 之前**：
        // JDK17 的 Set.of(...).contains(null) 直接抛 NPE（本机 java 单文件实测），而「不带 status」
        // 恰是本端点最常见的调用形态，写成 STATUS_VALUES.contains(status) 会让默认请求 500。
        String tab = status != null && STATUS_VALUES.contains(status) ? status : STATUS_ALL;

        // 夹而不拒：page<1 归一 1，size 夹到 [1,50]，生效值随出参回显
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) (pageNo - 1) * pageSize;

        long total = dateAbsent
                ? orderItemMapper.countOrderItemsAllDates(merchantId, tab)
                : orderItemMapper.countOrderItemsByDate(merchantId, pickupDate, tab);
        // 短路条件从 total==0 扩为 offset >= total（G-3/L4）：page 无上界，page=999999999 会算出
        // 巨大 offset 仍进 JOIN 白跑一条 LIMIT 查询。offset >= total 时本页必然空
        // （total==0 恒满足，天然覆盖原短路）；page 回显仍按「夹而不拒」语义不动，
        // hasMore 因 rows 为空自然为 false，total 保持真值。
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : dateAbsent
                ? orderItemMapper.selectOrderItemsAllDates(merchantId, tab, offset, pageSize)
                : orderItemMapper.selectOrderItemsByDate(merchantId, pickupDate, tab, offset, pageSize);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 逐键重建出参：源行的 phone 键不复制过来，只出 maskedPhone（RD：绝不回明文键名/键值）
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderItemId", row.get("orderItemId"));
            line.put("orderId", row.get("orderId"));
            line.put("dishId", row.get("dishId"));
            line.put("dishName", row.get("dishName"));
            line.put("quantity", toInteger(row.get("quantity")));
            line.put("price", row.get("price"));
            line.put("storeId", row.get("storeId"));
            line.put("storeName", row.get("storeName"));
            line.put("pickupDate", row.get("pickupDate"));
            line.put("status", row.get("status"));
            line.put("payStatus", toInteger(row.get("payStatus")));
            line.put("maskedPhone", PhoneMask.mask((String) row.get("phone")));
            line.put("createTime", row.get("createTime"));
            list.add(line);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("page", pageNo);
        data.put("size", pageSize);
        data.put("total", total);
        data.put("hasMore", offset + rows.size() < total);
        return data;
    }

    /**
     * 归属自守卫（纵深防御，非可达分支）：AuthInterceptor:83-87 已在 merchant_id 为空时收口 403，
     * 控制器拿到的 merchantId 恒非 null；这里留一行是为了万一出现不经 /api/merchant/ 前缀的内部调用时，
     * 也不会退化成「条件为空 = 不加条件」的全表读。403 与拦截器同文案（R.MSG_FORBIDDEN），不新增文案。
     */
    private void assertMerchant(Long merchantId) {
        if (merchantId == null) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
    }

    /**
     * date 解析：形状不符或形状符但日期不存在（2026-13-99、2026-02-30）都 400，
     * 文案逐字复用 GlobalExceptionHandler:51 那条。用 DateTimeException 兜住 parse，
     * 不让 DateTimeParseException 逃到全局兜底变成 500。
     */
    private LocalDate parseDate(String raw) {
        if (!DATE_PATTERN.matcher(raw).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeException e) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
    }

    /**
     * 组名：store_name 非 null 用快照原值，null → 「未指定自提点」（契约 F-17.2 #3 的出参映射，
     * 覆盖 store_id 为 NULL 的那一组——下单时 store_id/store_name/store_address 三列同源于
     * Store 查询，未选自提点则三列一起为 NULL，故按 store_name 判 null 与按 store_id 判 null 等价，
     * 而这里取值的就是 store_name 本身）。空串仍按原值输出，不额外发挥。
     */
    private String storeName(Object storeName) {
        return storeName instanceof String name ? name : UNASSIGNED_STORE_NAME;
    }

    /**
     * 聚合列取值归一：MySQL 的 {@code SUM()} 回 <b>BigDecimal</b>、{@code COUNT()} 回 Long、
     * INT 列回 Integer——**别假设是 Integer**（契约 F-17.2 表 #5），一律走 Number 转 int，不做强转。
     * 顺带兜住 Connector/J 把 tinyint 当 Boolean 回的情况（pay_status 现列类型是 tinyint，
     * 但一旦改成 tinyint(1) 且驱动开 tinyInt1isBit，getObject 会给 Boolean），
     * 保证出参 payStatus 恒为 0/1 数字而不是 true/false（前端 plan-orders 要判 payStatus===0）。
     */
    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        return null;
    }
}
