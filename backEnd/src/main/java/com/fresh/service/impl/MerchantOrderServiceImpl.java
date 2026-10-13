package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.service.MerchantOrderService;
import com.fresh.service.MerchantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 商家端只读接口实现：G-03 备货单两页（F-17）＋ G-05 数据看板与营销参与态（F-19）。
 * 纯读、无事务写、不落运营审计（RC 红线，与 G-02 六个端点
 * 「四个写才审计」口径一致）。G-05 找茬后 {@code stat}/{@code marketing} 各套一层
 * {@code @Transactional(readOnly = true)}（BE-G5-5：同一请求内多条聚合 SQL 共用一个快照），
 * 那是**只读事务**，本类仍然没有任何写语句、也没有改全局隔离级别。
 *
 * <p>停业闸门（D-9，本期的口径变化点）：F-19 的两个新方法**读侧也判** {@code merchant.status<>'active'}，
 * 判据与文案直接调既有的 {@link MerchantService#assertActive}（= MerchantDishServiceImpl 四个写端点用的
 * 同一条实现：null → 403、查无主体 → 404「商家不存在」、非 active → 403 R.MSG_FORBIDDEN），
 * 本类不新写任何判据或文案。G-03 的 pickupPlan/orderItems 行为不变——那两个仍是「读放行」
 * （停业商家仍需看自己的历史备货单去收尾备货，契约 F-17.2 #8）。
 *
 * <p>归属（R3 同型，本期为读）：取数只经 OrderItemMapper 那 11 条 @Select（F-17 的 8 条 + F-19 的 3 条）
 * 与 DishMapper 的 4 条 F-19 @Select，每一条都恒带
 * {@code oi.merchant_id = #{merchantId}} / {@code d.merchant_id = #{merchantId}}；本类不存在任何绕过这些
 * 方法的读路径，也没有「merchantId 为空就不加条件」的分支——首行判空直接 403（同 MerchantDishServiceImpl）。
 * storeId 只作过滤、不作归属；本类也没有任何 merchantId 入参（D-2，值只来自 @RequestAttribute 注入）。
 *
 * <p>分页（RB 红线）：本工程没注册 MP 的内置分页拦截器，用那一套分页对象会静默返回全量且 total=0，
 * 故 G-03 走「页 + 独立 COUNT」两条手写 SQL，offset 自己算（母版是 OrderServiceImpl#getOrders 的
 * {@code wrapper.last("LIMIT ...")}，本类改用 @Select 的 {@code LIMIT #{offset}, #{size}} 以便带 JOIN）。
 * F-19 两个端点干脆没有分页：Top 5 与参与位明细各 LIMIT 20/5 写死，count 由独立 COUNT 给真总数。
 *
 * <p>脱敏（RD 红线）：orders.phone 是明文列，只经 PhoneMask.mask 后以 maskedPhone 出参；
 * 出参 map 是逐键重新拼的（四个方法都是手工白名单 Map，绝不直接吐实体或吐聚合行的原始 Map），
 * 源行的 phone 键**不进**返回体，address/user_id/pickup_code 连查都没查。
 *
 * <p>时间口径（D-3，本类最要紧的一条纪律）：看板窗口只在 SQL 里算
 * （{@code DATE_SUB(CURDATE(), INTERVAL (#{days}-1) DAY)}），Java 侧零日期运算——
 * 用 LocalDate.now() 另算一次就等于把窗口交给应用服务器时区，与 CURDATE() 所在库时区可能差一天。
 * 出参 {@code windowStart} 是同一条 SUM 语句里同式取回的字符串，天然与谓词同步。
 * 这与 G-03 的 {@code pickup_date}（自提自然日，Service 用 LocalDate 算「明天」）是两套口径，
 * 契约点名的「不许混用」在本类里就是：看板的 days/windowStart 绝不碰 pickupDate 那条链路。
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

    /** 400 文案（RF 红线：逐字复用 GlobalExceptionHandler:55 已有的那条，不新增句子） */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    /**
     * 看板窗口天数（契约 D-3）：缺省 7、值域 [1,90]、越界 400。
     * 「天数需在 1 到 90 之间」是 D-3 逐字钉死的文案，也是本期唯一新增句（RF 的「不新增句子」
     * 指的是不复用/不改写既有文案，契约点名的新值域校验必须有新文案，此处照契约原文）。
     */
    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int MIN_WINDOW_DAYS = 1;
    private static final int MAX_WINDOW_DAYS = 90;
    private static final String DAYS_RANGE_MSG = "天数需在 1 到 90 之间";

    /**
     * 金额列的展示精度：一律 2 位、HALF_UP（契约 F-19.4 #4）。
     * DECIMAL(10,2) 的列本已是 2 位，setScale 是给 SUM 结果兜底（MySQL 的 SUM 会把精度加宽），
     * 并保证绝不出现 {@code 1.0} / {@code 1.000} 这类位数不定的串。
     */
    private static final int MONEY_SCALE = 2;

    @Autowired
    private OrderItemMapper orderItemMapper;

    /** F-19 营销参与态取数（dish 侧 4 条恒带归属条件的 @Select） */
    @Autowired
    private DishMapper dishMapper;

    /** F-19/D-9 读侧停业闸门：复用商家端既有写闸门的判据与文案，本类不重写一遍 */
    @Autowired
    private MerchantService merchantService;

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
            // F-18.8 第 5 条显式新增字段：商家要看得到自己登记过的缺货行（fulfill_status 一期唯一写入方
            // = MerchantFulfillService）；旁路列与 status 三态正交，pickup-plan 的聚合口径不受影响（S3-G7）。
            line.put("fulfillStatus", row.get("fulfillStatus"));
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
     * 数据看板（F-19/G-05，契约 D-3/D-4）。三个数字 + 取消单数 + Top 5 全部来自 OrderItemMapper
     * 那三条恒带 {@code oi.merchant_id} 的 @Select，本方法只做「校验 + 白名单重拼 + 金额定形」，
     * 不参与任何口径计算（尤其不参与日期计算，见类注释的 D-3 段）。
     * <p>{@code @Transactional(readOnly = true)}（BE-G5-5，F-19.8 S-6）：三条 SQL 收进同一个只读事务后
     * 共用一个一致性快照，于是卡片之间不再各自看见不同时刻的库状态——原来三条各自自动提交，
     * 会出现 {@code sum(top) > gmv}、count 与明细条数互相矛盾，以及跨 00:00 的请求里
     * {@code windowStart} 与实际窗口差一天（每条语句各算一次 CURDATE()）。
     * <p>如实标注残留面：事务冻住的是**可见行**，{@code CURDATE()} 仍是逐语句取值，
     * 只有恰好在 00:00:00 那一刻跨语句才会碰到；本期不为此把日期搬进 Java（D-3 红线，搬了就是凭空多出
     * 第二个窗口口径），三条 SQL 的窗口谓词逐字同式仍是唯一真源。
     * <p>本批是**纯读**，没有任何「UPDATE 回 0 行后再读定性」的逻辑，所以 readOnly 事务安全，
     * 不会重演 G-04 那个 RR 快照坑（那条纪律只适用于「条件 UPDATE 命中 0 行后还要再读定性」的写路径，
     * 见 PartnerOrderServiceImpl/PartnerStoreServiceImpl 的 {@code READ_COMMITTED} 事务）；
     * 这里不改全局隔离级别、不动 application-*.yml。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> stat(Long merchantId, Integer days) {
        assertMerchant(merchantId);
        // D-9：只读侧也判停业（判据/文案复用 MerchantService#assertActive，不另写句子）
        merchantService.assertActive(merchantId);

        // days 缺省 7 并回显生效值（同 G-03「夹而不拒 + 回显」的防「静默改口径」姿势）；
        // 越界直接 400（契约 D-3 原文，不夹到边界——窗口口径是商家主动选的，夹了会让「近 90 天」
        // 与「近 999 天」出同一个数，比报错更难发现）。abc 这类绑不进 Integer 的脏参在进本方法之前
        // 已由 GlobalExceptionHandler(BE-14) 转 400「参数格式不正确」。
        int windowDays = days == null ? DEFAULT_WINDOW_DAYS : days;
        if (windowDays < MIN_WINDOW_DAYS || windowDays > MAX_WINDOW_DAYS) {
            throw new BizException(R.CODE_BAD_REQUEST, DAYS_RANGE_MSG);
        }

        Map<String, Object> summary = orderItemMapper.selectStatSummary(merchantId, windowDays);
        // 无 GROUP BY 的聚合恒回 1 行，null 在这里不可达；留这行只为万一时不把 null 传进下面的取值方法
        // （绝不用 Java 算日期给 windowStart 兜底——那等于凭空多出第二个窗口口径，D-3 明令禁止）
        Map<String, Object> row = summary == null ? Map.of() : summary;
        long refundCount = orderItemMapper.selectStatCancelledCount(merchantId, windowDays);
        List<Map<String, Object>> topRows = orderItemMapper.selectStatTopDishes(merchantId, windowDays);

        List<Map<String, Object>> top = new ArrayList<>(topRows.size());
        for (Map<String, Object> line : topRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dishId", line.get("dishId"));
            // dishName 是 order_item 的下单快照列，本方法全程没有 JOIN dish（D-4：改菜名不污染历史单）
            item.put("dishName", line.get("dishName"));
            item.put("quantity", toInteger(line.get("quantity")));
            item.put("amount", moneyText(line.get("amount")));
            top.add(item);
        }

        // 键序即契约 F-19.4 #4 出参形状的顺序；LinkedHashMap 保证序列化后逐键对齐（前端下一轮按这个写）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", windowDays);
        data.put("windowStart", row.get("windowStart"));
        data.put("gmvBeforeCoupon", moneyText(row.get("gmvBeforeCoupon")));
        data.put("orderCount", toInteger(row.get("orderCount")));
        data.put("itemCount", toInteger(row.get("itemCount")));
        data.put("refundCount", refundCount);
        data.put("top", top);
        return data;
    }

    /**
     * 营销参与态（F-19/G-05，契约 D-6/D-7）：本店被平台设置的秒杀件与免费领活动，纯只读、零报名口。
     * count 与 items 来自两条方法（count 是真总数、items 上限 20），count=0 时短路明细条——
     * 本仓 merchant 2/3 两个参与位实测恒 0 行，空态就是本期真实形态（G8），绝不为了页面好看造数据。
     * <p>{@code @Transactional(readOnly = true)}（BE-G5-5，F-19.8 S-6）：本方法四条 SQL 同快照，
     * 「卡头 count=0 的短路」与明细条数不再可能来自库的两个不同时刻（U-2 那类标题与行内自相矛盾，
     * 根因之一是 count 与 items 各自自动提交）；纯读，理由与 G-04 的坑无关，见 stat() 上那段同口径注释。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> marketing(Long merchantId) {
        assertMerchant(merchantId);
        // D-9：同上，读侧也判停业
        merchantService.assertActive(merchantId);

        long seckillCount = dishMapper.countSeckillDishes(merchantId);
        List<Map<String, Object>> seckillItems = seckillCount == 0
                ? List.of()
                : seckillItemList(dishMapper.selectSeckillDishes(merchantId));

        long freeCount = dishMapper.countFreeActivities(merchantId);
        List<Map<String, Object>> freeItems = freeCount == 0
                ? List.of()
                : freeClaimList(dishMapper.selectFreeActivities(merchantId));

        Map<String, Object> seckill = new LinkedHashMap<>();
        seckill.put("count", seckillCount);
        seckill.put("items", seckillItems);

        Map<String, Object> freeClaim = new LinkedHashMap<>();
        freeClaim.put("count", freeCount);
        freeClaim.put("items", freeItems);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seckill", seckill);
        data.put("freeClaim", freeClaim);
        return data;
    }

    /** 秒杀块白名单重拼（D-6 键清单）：实体/Mapper 行一律不外泄，未列的列（stock/tags/…）天然不出 */
    private List<Map<String, Object>> seckillItemList(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dishId", row.get("dishId"));
            item.put("name", row.get("name"));
            // N-1：列值型金额也出两位小数字符串（DECIMAL 经 Jackson 会掉成 6.6 这种一位小数）。
            // 与四卡（gmvBeforeCoupon / top[].amount）同一形态，前端就不再需要补零——把口径搬到展示层，
            // G-03 已因此返工过一次；仍然只走既有的 moneyText()，不另起一份格式化
            item.put("price", moneyText(row.get("price")));
            // seckill_price 是可空列（init.sql 的 dish.seckill_price NULL）：真为 NULL 就出 null，
            // 这是「平台只打了标记没填价」的事实形态，兜成 0.00 反而要商家按 0 元理解（不发明修法）。
            // 所以这一列只在有值时定形出串，null 分支保留（F-19.5 裁定 ③「真 NULL 不兜 0」仍然生效）
            item.put("seckillPrice", row.get("seckillPrice") == null
                    ? null : moneyText(row.get("seckillPrice")));
            item.put("limitBuy", toInteger(row.get("limitBuy")));
            // onSale 走 toInteger 而不是直出：tinyint 有被 Connector/J 回成 Boolean 的一档
            // （下方 toInteger 注释同口径），出参必须是数字 0/1，前端才敢做 ===1 判定
            item.put("onSale", toInteger(row.get("onSale")));
            // D-7：soldCountTotal = dish.sold_count 的**历史累计**，不是本窗口值，
            // 故改名带 Total 且不与 /stat 的任何数并列比较
            item.put("soldCountTotal", toInteger(row.get("soldCountTotal")));
            items.add(item);
        }
        return items;
    }

    /**
     * 免费领块白名单重拼（D-6 键清单，按 F-19.8 S-1/S-2 订正后）：daily_date 不在契约键清单里，故不取也不出。
     * <p>键位变化两处（与 DishMapper 那两条 SQL 同批）：
     * ① {@code inPool} 删掉——明细条的驱动表已改成 free_goods_pool，池行恒「在池」，该列恒真即无信息量，
     * 且它当时还与卡头「已参与 N 件」互相打脸（U-2）；
     * ② 活动状态键名由 {@code status} 改为 {@code activityStatus}——取的是 {@code fa.status}（online/offline），
     * 名字里必须带 activity，否则容易被读成池行状态或商品在售状态（页面标签见 FE-G5-4）。
     * <p>{@code dailyClaimed} 直接取 SQL 里 {@code CASE WHEN fa.daily_date = CURDATE() THEN ... ELSE 0 END}
     * 的结果（BE-G5-2 跨日谎报）：本类零日期运算（D-3），跨日归零在库里判完，Service 只做取值与定形。
     */
    private List<Map<String, Object>> freeClaimList(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("activityId", row.get("activityId"));
            item.put("dishId", row.get("dishId"));
            item.put("name", row.get("name"));
            item.put("activityStatus", row.get("activityStatus"));
            item.put("dailyQuota", toInteger(row.get("dailyQuota")));
            item.put("dailyClaimed", toInteger(row.get("dailyClaimed")));
            // N-1 同秒杀侧：门槛也出两位小数字符串。free_activity.threshold 是 NOT NULL DEFAULT 50.00
            // （init.sql:251），不存在「真 NULL」那一档，故不必像 seckillPrice 那样留 null 分支
            item.put("threshold", moneyText(row.get("threshold")));
            items.add(item);
        }
        return items;
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
     * 文案逐字复用 GlobalExceptionHandler:55 那条。用 DateTimeException 兜住 parse，
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

    /**
     * 金额定形（契约 F-19.4 #4）：把位数钉成 2、HALF_UP，于是 {@code 12.3} / {@code 12.300000} 都变 {@code 12.30}。
     * 走 {@code number.toString()} 再构造，绝不 {@code new BigDecimal(double)}（浮点误差会渗进金额）。
     * null 原样出 null（唯一可达来源是可空列 dish.seckill_price，见调用处注释）。
     * <p>出参形态由调用方定：本期四个金额读数全部经 {@link #moneyText} 成串出，本方法只承担「定形」这一步
     * （N-1 之前 marketing 侧用它直出裸数值，实测被序列化成一位小数，故收敛到串）。
     */
    private BigDecimal money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return null;
    }

    /**
     * 聚合金额的出参形态：定形后转字符串（契约 F-19.4 #4 的出参形状里 {@code gmvBeforeCoupon}
     * 与 {@code top[].amount} 两处就是带引号的 {@code "12.30"}）。为什么要成串：SUM 出来的 BigDecimal 位数
     * 由 MySQL 决定，而看板要的是「永远两位」的展示值；前端拿到串就不再受 JS number 抹尾零的影响（12.30 → 12.3）。
     * <p>N-1 起本方法也是 marketing 侧三个列值型金额（{@code price}/{@code seckillPrice}/{@code threshold}）
     * 的出串口——DECIMAL 列直接进 JSON 会被写成 {@code 6.6} 这种一位小数，与四卡不同形，
     * 故一律在此定形后成串，前端不再补零（把口径搬到展示层，G-03 已因此返工过一次）。
     * null 兜 {@code "0.00"}（G5：零单商家不许出 null；SQL 侧 IFNULL 已兜过一层，这里是第二层，
     * 兜的是「列缺失」而不是「值为 0」，两者出参一致）。可空列要保 null 的调用方自己先判（见 seckillItemList）。
     */
    private String moneyText(Object value) {
        BigDecimal scaled = money(value);
        return scaled == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()
                : scaled.toPlainString();
    }
}
