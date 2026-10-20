package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.service.AdminV2Service;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运营端 v2 只读实现（F-20.9 #2/#3 + F-20.16 + F-20.18 #1~#4）。六个方法都只做
 * 「校验 + 取数 + 白名单重拼」，不参与任何口径计算。
 *
 * <p><b>大盘口径（F-20.9 #2 逐字照 {@code docs/日常运营SQL手册.md:111-115} 的今日经营概况）</b>：
 * {@code COUNT(*)} / {@code IFNULL(SUM(total_price),0)} / {@code SUM(status='pending_pickup')} /
 * {@code IFNULL(SUM(CASE WHEN pay_status=1 THEN total_price ELSE 0 END),0)}，
 * 窗口是 {@code [当日 00:00, 次日 00:00)} 半开区间，与手册的
 * {@code create_time >= CURDATE() AND create_time < CURDATE() + INTERVAL 1 DAY} 同一个区间。
 * 这是<b>订单口径</b>，与 G-05 商家看板的「券前明细口径」（{@code order_item.price × quantity}）分列、互不换算
 * （D-7），本类不出现任何明细级聚合。
 * <p>与手册的唯一一处差异：手册里待自提那列是裸 {@code SUM(status = 'pending_pickup')}，空数据日它回 NULL，
 * 而契约要求「空数据日四个 0 值不是 null」，故 SQL 侧补了一层 {@code IFNULL(...,0)}——谓词与表达式本体一字未动。
 *
 * <p><b>大盘第五、六键（F-50，D-50-1/D-50-3/D-50-4）</b>：当日「微信支付回调拒收」的条数与最后一次时间。
 * 它<b>不是订单口径</b>，读的是 {@code admin_audit_log}（F-44 落的那批行），但<b>窗口与前四键逐字同源</b>——
 * 同一个已过双重日期闸门的 {@code day}、同一对 {@code IFNULL(#{day}, CURDATE())} 半开区间边界，
 * 只是时间列换成该表的 {@code created_at}；同一天切换日期选择器，那一格与四个订单指标说的是同一个自然日。
 * 一条 SQL 出两列（不做两次往返），Java 侧依旧不取时钟。当日 0 条时 {@code notifyRejectLastAt} 是<b>显式 null</b>，
 * 键必须在（F-42b 信封纪律）；那一格的 HH:mm 由后端 {@code DATE_FORMAT('%H:%i')} 定形，前端只展示（D-50-7）。
 *
 * <p><b>「今日」由 SQL 侧 {@code CURDATE()} 决定</b>（同 F-19/D-3 的时间窗纪律）：date 缺省时本类
 * <b>不</b>调 {@code LocalDate.now()}——那等于把「今天」交给应用服务器时区，与库时区可能差一天，
 * 也会凭空多出第二个窗口口径。只有调用方显式给了某一天，才把那一天作为绑定参数交给同一条 SQL 的
 * {@code IFNULL(#{day}, CURDATE())}。
 *
 * <p><b>取数通道（F-20.18 收 N-20-2，本类的妥协已还清）</b>：Step 3 时大盘那条聚合走的是实体 mapper
 * <b>继承来的</b>通用条件构造通道（当时允许清单不含 {@code OrderMapper.java}，是一个登记过的妥协）；
 * 本期清单含了，于是它搬成 {@link OrderMapper#selectOverviewDaily(LocalDate)} 一条手写 {@code @Select}
 * （四个表达式与别名逐字照搬原常量），<b>本类不再有任何条件构造器</b>，也不再有那条「读 SQL 不在 mapper 里」的例外。
 * 六条读路径（overview / audit-logs / orders / dishes / reviews / store-applies）现在<b>全部</b>是各自
 * mapper 里的手写 {@code @Select}，「读 SQL 全在 mapper 里」这条可 grep 的纪律不打折。
 *
 * <p><b>脱敏 / 最小暴露（RD）</b>：出参六个方法都是逐键重拼的手工白名单 Map，绝不吐实体、绝不吐 SQL 行的原始 Map。
 * 具体到列（「不输出」写成「不查询」，SQL 层连列都不选，泄露面才是零）：
 * 审计不选 {@code ip}；大盘只取聚合值，不取 orders 的任何一行（F-50 那两键同样只是 {@code admin_audit_log}
 * 上的 {@code COUNT}/{@code MAX} 两列，一条明细都不落进大盘出参）；
 * orders 面不选 {@code pickup_code}/{@code user_id}/{@code client_request_id}（D-15），
 * {@code phone} 取原文后<b>只</b>经 {@link PhoneMask#mask} 出掩码值（契约点名用这个既有类，
 * 不在本类再写第四份 substring/CONCAT 实现，也不在 SQL 里 CONCAT 出第二口径）；
 * reviews 面不选 {@code user_id}；store-applies 面不选 {@code proof_photos}（§9 悬项）、
 * 不选生成列 {@code pending_guard}，{@code contact_phone} 同 {@code phone} 走 {@link PhoneMask#mask}。
 *
 * <p><b>分页（RB）</b>：本工程没注册 MP 的内置分页拦截器，用那一套分页对象会静默返回全量
 * 且 total=0，故五条列表都是「页 + 独立 COUNT」两条手写 SQL（两条 WHERE 逐字相同，见各 mapper 的那个
 * 编译期常量串），offset 在此算；越界与脏值一律 400（不夹到边界，理由同 G-05 的 days：
 * 把 999 夹成 100 会让「100 条」和「999 条」出同一个数，比报错更难发现）。
 *
 * <p><b>筛选（D-9/D-11/D-17）</b>：一律精确等值 + 白名单枚举，本批零 LIKE、零关键字搜索；
 * 值域/形状闸门都在本类（SQL 侧只认绑定值），白名单外 → 400 复用 {@code BAD_REQUEST_MSG} 那一句、不新造文案；
 * 形状与值域都合法但库里没这个值 → 正常回 {@code {list:[],total:0}}，不在本层拦。
 * 形状闸门分两枚，各管各的（F-20.21 BE-G6-1）：auditLogs 那四枚（本就 ASCII 码）走 {@link #FILTER_PATTERN}，
 * {@code dishes} 的 {@code category}（商家自由写入、库里存在中文取值）走 {@link #CATEGORY_PATTERN}，只挡乱码不当数据字典。
 * 四枚<b>枚举</b>筛选（orders.status / dishes.onSale / reviews.auditStatus / store-applies.status）
 * 在过白名单<b>之前</b>先按 {@code Locale.ROOT} 归一小写（F-20.21 BE-G6-2），白名单仍只收小写字面量、
 * {@code filters} 回显的是归一后真正生效的那一枚；数值型的 onSale 归一是 no-op，绑定类型不变。
 * 四条新列表与 auditLogs 共用的分页/筛选/重拼动作各收成一个私有方法，五个端点不各写一遍。
 */
@Slf4j
@Service
public class AdminV2ServiceImpl implements AdminV2Service {

    /** date 出参形状闸门 + 交 LocalDate 裁决真日期（见 {@link #parseDate}） */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /**
     * 审计筛选值的形状闸门（F-20.16 D-11）：四个精确等值筛选 actor/action/targetType/targetId 的取值白名单——
     * 字母/数字/下划线/点/冒号/短横，1~64 字符；{@code -} 放在字符类<b>末尾</b>免被正则读成范围。
     * 非 null 的取值不过此形状 → 400；空串（长度 0）也不合 {@code {1,64}} → 400（前端 withQuery 本就不下发空串，这条纪律不绕）。
     * <p><b>本枚只留给这四枚，不再管 {@code dishes} 的 {@code category}</b>（F-20.21 BE-G6-1 收口）：
     * 这四枚按构造就是 ASCII 码（{@code admin_user:2} / {@code dish.update} / {@code dish} / 雪花单号串），
     * 放宽只会丢掉「挡乱码」这层能力，所以逐字不动；而 {@code category} 是商家侧自由写入的列，
     * 实况里就存在非 ASCII 取值（本机读数 {@code dish.id=44} 的 category 是中文「蔬菜」，12 种取值中 11 种 ASCII、
     * 这一行例外），拿 ASCII 字符类去当数据字典会把列表自己展示给运营的分类判成脏参（{@code ?category=蔬菜} → 400，
     * 点即报错的陷阱）。那一行<b>数据</b>不在本批改（是商家侧真实写入的脏值，改数等于掩盖问题，登记 §9 悬项由主会话拍），
     * 放宽的是<b>闸门</b>：值一律 {@code #{}} 绑定 + 精确等值，无拼接面，故放宽没有注入面。category 的新闸门见
     * {@link #CATEGORY_PATTERN}。
     */
    private static final Pattern FILTER_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    /**
     * {@code dishes} 面 {@code category} 的形状闸门（F-20.21 BE-G6-1）：长度 1~64 且<b>不含控制字符</b>。
     * <p>裁定口径「闸门的作用是挡乱码，不是当数据字典」——所以这一枚只拒「不可能作为分类值」的形状：
     * 换行/回车/制表/NUL/DEL（{@code \p{Cntrl}} 是 ASCII 那 33 枚）、C1 控制字符 U+0080~U+009F
     * （{@code \p{Cc}} 补齐 {@code \p{Cntrl}} 覆盖不到的那一段，含 NEL U+0085）、
     * 以及零宽一类（{@code \p{Cf}}：U+200B 零宽空格、U+200D ZWJ、U+FEFF BOM/零宽不换行——
     * 这些在 Java 里<b>不属于</b> {@code \p{Cntrl}}，只写 {@code \P{Cntrl}} 会被放过，故三枚并列）。
     * <p>中文/带重音拉丁字母/空格/{@code %}/emoji 一律放过：值只经 {@code #{}} 绑定进
     * {@code (#{category} IS NULL OR d.category = #{category})} 的精确等值，不参与任何 SQL 文本拼装，
     * 放宽不产生注入面；形状合法但库里没这个值 → 正常回 {@code {list:[],total:0}}，不在本层拦。
     * <p>锚点用 {@code \A…\z} 而非 {@code ^…$}：后者在 Java 里 {@code $} 可落在末尾换行之前，
     * {@code \z} 是绝对的输入末尾——即便有人日后把 {@code matches()} 换成 {@code find()}，末尾换行也进不来。
     * <p>本枚<b>只管</b> {@code category} 一枚；auditLogs 那四枚仍走 {@link #FILTER_PATTERN}。
     */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("\\A[^\\p{Cntrl}\\p{Cc}\\p{Cf}]{1,64}\\z");

    /**
     * 筛选值域白名单（F-20.18 D-17：等值 + 白名单枚举，白名单外一律 400）。
     * 四枚集合<b>本身</b>仍是大小写敏感的精确集合判定（{@link Set#contains}），但送进去的那一枚
     * <b>已先归一小写</b>（F-20.21 BE-G6-2，见 {@link #validatedEnumChoice}）——于是集合里只收小写字面量，
     * 而调用方写 {@code PENDING_PICKUP}/{@code Pending} 也进得来，与同一张 v2 面上 audit-logs 那四枚
     * 「等值由列 collation 决定、天然不区分大小写」的用法语义一致（同一面不留两种大小写规矩）。
     * 归一只发生在 Java 侧的白名单入口，不改 SQL、不改绑定类型；闸门决定「这一档存不存在」，
     * collation 决定「库里怎么算相等」，两层依旧互不替代。
     * <ul>
     *   <li>{@code ORDERS}：orders.status 三态（init.sql:113，DEFAULT 'pending_pickup'）；</li>
     *   <li>{@code DISHES}：dish.on_sale 只有 0/1 两值（TINYINT，1 上架 / 0 下架）；
     *       这一枚是 {@code Integer}，没有大小写可归（{@link #validatedChoice} 对它即原判定，归一为 no-op）；</li>
     *   <li>{@code REVIEWS}：UI 伪值两枚——库里 {@code audit_status} 只有 {@code 'pending'} 与 {@code NULL} 两态，
     *       {@code visible} 是契约 D-17 给的「展示中」伪值，映射（{@code IS NULL}）发生在 SQL 侧，
     *       <b>不在这里映射</b>；也没有第三档「已驳回」可筛（reject 是物理删行，库里无对应行）；</li>
     *   <li>{@code STORE_APPLIES}：store_apply.status 三态（见 init.sql 的 store_apply，DEFAULT 'pending'）。</li>
     * </ul>
     */
    private static final Set<String> ORDER_STATUS_FILTERS = Set.of("pending_pickup", "completed", "cancelled");
    private static final Set<Integer> DISH_ON_SALE_FILTERS = Set.of(0, 1);
    private static final Set<String> REVIEW_AUDIT_FILTERS = Set.of("pending", "visible");
    private static final Set<String> STORE_APPLY_STATUS_FILTERS = Set.of("pending", "approved", "rejected");

    /** 400 文案：复用既有那一句（GlobalExceptionHandler:55 同串），本类不新造句子 */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    /** 分页（D-16，五个列表同一套）：pageNum 下限 1（无上限，越界的巨大 offset 由短路兜住）；pageSize 缺省 20、值域 [1,100] */
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int MIN_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    /** 金额出参精度：2 位、HALF_UP，成串（同 {@code MerchantOrderServiceImpl} 的 MONEY_SCALE 那组口径） */
    private static final int MONEY_SCALE = 2;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;
    /** F-20.18 四条列表的取数通道（读 SQL 全在 mapper 里，N-20-2 收口后的形态） */
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private StoreApplyMapper storeApplyMapper;

    @Override
    public Map<String, Object> overview(String date) {
        // 「未传日期」的口径与 F-20.9 #2 逐字相同（null/空白 = 今日窗口）。
        // 与 Step 3 的唯一形态差别：不再按「有没有给日期」分两套窗口写法，
        // 而是把 null 直接交给 SQL 侧的 IFNULL(#{day}, CURDATE())，一条语句同时覆盖两档。
        LocalDate day = dateAbsent(date) ? null : parseDate(date.trim());

        // F-20.18 收 N-20-2：这条聚合搬进 OrderMapper.selectOverviewDaily 的手写 @Select，
        // 「今日」仍由 SQL 侧 CURDATE() 给（day=null 时 IFNULL 退回 CURDATE()），本类只在给了某一天时
        // 把那一天（已过 parseDate 双重闸门）作为绑定参数交下去——本方法只剩「校验 + 取数 + 重拼」三步。
        Map<String, Object> row = orderMapper.selectOverviewDaily(day);
        // 无 GROUP BY 的聚合恒回 1 行，取不到行在这里不可达；留这行只为万一时不把 null 传进取值方法
        Map<String, Object> agg = row == null ? Map.of() : row;

        // 键序即契约 F-20.9 #2 的四指标顺序（单量 / 总金额 / 待自提单数 / 已收款金额）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCount", count(agg.get("orderCount")));
        data.put("totalAmount", moneyText(agg.get("totalAmount")));
        data.put("pendingPickupCount", count(agg.get("pendingPickupCount")));
        data.put("paidAmount", moneyText(agg.get("paidAmount")));

        // F-50（D-50-1）：第五、六键——当日「微信支付回调拒收」的条数与最后一次的时间。
        // 不新增端点、不新增限流档：这一格挂在本方法已有的 /overview 上，读的正是运营每天进来看的那一页。
        // 窗口与前四键<b>逐字同源</b>（D-50-3）：把上面已经过双重日期闸门的同一个 day 原样交下去，
        // SQL 侧同样是 IFNULL(#{day}, CURDATE()) 的 [当日 00:00, 次日 00:00) 半开区间，Java 侧不取时钟。
        // 一条 SQL 出两列（D-50-4）：条数与「最近一次几点」同一句、同一 WHERE，不会各说各话。
        Map<String, Object> rejectRow = adminAuditLogMapper.selectNotifyRejectSummary(
                AdminAuditLogMapper.ACTION_PAY_NOTIFY_REJECT, day);
        // 无 GROUP BY 的聚合恒回 1 行，取不到行在这里不可达；留这行只为万一时不把 null 传进取值方法
        Map<String, Object> reject = rejectRow == null ? Map.of() : rejectRow;
        // rejectCount 用既有 count() 归一（MySQL COUNT 回 Long，不许假设是 Integer）
        data.put("notifyRejectCount", count(reject.get("rejectCount")));
        // F-42b 信封纪律：<b>缺键与显式 null 对客户端是两种语义</b>。当日无拒收时 SQL 回的是
        // lastAtText=NULL，而 MyBatis 对 Map 结果默认不写 null 键，所以这里必须无条件 put——
        // 「这一日确实没有拒收」要能被前端区分成「后端没给这一列」。
        // 值是后端 DATE_FORMAT('%H:%i') 定好的 HH:mm 串，本层不二次格式化、不做时区换算（D-50-7）。
        Object lastAtText = reject.get("lastAtText");
        data.put("notifyRejectLastAt", lastAtText == null ? null : lastAtText.toString());
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> auditLogs(Integer pageNum, Integer pageSize,
                                         String actor, String action, String targetType, String targetId) {
        // 越界/脏值 → 400（契约 F-20.9 #3：pageNum 下限 1、pageSize 缺省 20 上限 100）。
        // ?pageNum=abc 这类绑不进 Integer 的脏参在进本方法之前已由 GlobalExceptionHandler 转 400。
        // F-20.18 起这一段搬进 validatedPageNum/validatedPageSize 两个私有方法（五个列表共用，判据与文案一字未改）。
        int pageNo = validatedPageNum(pageNum);
        int size = validatedPageSize(pageSize);

        // F-20.16 D-11 形状闸门：一个私有方法搞定四个值（不写四遍）。null/未传 → null（该条件 SQL 侧短路不生效）；
        // 非 null 不过 FILTER_PATTERN → 400（复用 BAD_REQUEST_MSG 那一句，不新造文案）。
        final String fActor = validatedFilter(actor);
        final String fAction = validatedFilter(action);
        final String fTargetType = validatedFilter(targetType);
        final String fTargetId = validatedFilter(targetId);

        // D-10：total 走「带同一组筛选 WHERE」的独立 COUNT（与页那条逐字相同，见 AdminAuditLogMapper），
        // 否则会出现「共 0 条」却画着 20 行。
        long total = adminAuditLogMapper.countAuditLogs(fActor, fAction, fTargetType, fTargetId);
        long offset = offsetOf(pageNo, size);
        // offset >= total 直接短路（同 MerchantOrderServiceImpl:219-223 的 G-3/L4 修法）：
        // pageNum 无上限，999999999 会算出巨大 offset，短路就不再白跑一条 LIMIT 查询；
        // D-13：短路判据用的是<b>筛选后的 total</b>；空表 / 筛选无命中（total=0）时任何页都走这一支 → list:[] 而不是 500。
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : adminAuditLogMapper.selectAuditLogsPage(fActor, fAction, fTargetType, fTargetId, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 逐键重拼白名单（RD）：SQL 侧没查 ip，这里也不补；七键恒定存在
            // （MyBatis 对 Map 结果默认不写 null 键，callSettersOnNulls=false，detail 可为 NULL 列，
            //  在此显式 put 才保证出参形状稳定）
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("actor", row.get("actor"));
            line.put("action", row.get("action"));
            line.put("targetType", row.get("targetType"));
            line.put("targetId", row.get("targetId"));
            line.put("detail", row.get("detail"));
            line.put("createdAt", row.get("createdAt"));
            list.add(line);
        }

        // D-12：回显生效筛选值——未传的键回 null（不是空串、不是缺键）。用回显而非让前端自维护第二处真值
        // （F-20.11 的 overview 不回显 date 已逼出过一次别扭，这次不再犯）。键序 actor/action/targetType/targetId。
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("actor", fActor);
        filters.put("action", fAction);
        filters.put("targetType", fTargetType);
        filters.put("targetId", fTargetId);

        // 既有四键一字不动（旧前端零感知），filters 是新增的第五键。
        // 出参五键的拼装走 pageResult（五个列表同一处、同一键序），本方法的行为与 Step 5a 门禁留证逐字一致。
        return pageResult(list, total, pageNo, size, filters);
    }

    /**
     * 运营端订单列表（F-20.18，订单管理页数据源）。与 auditLogs 同一套骨架：分页闸门 → 筛选闸门 →
     * 独立 COUNT → 越界短路 → 取页 → 逐键重拼 → 回显 filters。
     * <p>脱敏只在这一层发生：SQL 取回的是 {@code phone} 原文（否则就得在 SQL 里 CONCAT 出第二份掩码口径，
     * 而 D-15 点名复用 {@link PhoneMask}），出参前一行 {@code PhoneMask.mask} 把它换成 {@code 138****1234}，
     * 明文不进 Map、不进日志。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> orders(Integer pageNum, Integer pageSize, String status, Long storeId, String date) {
        int pageNo = validatedPageNum(pageNum);
        int size = validatedPageSize(pageSize);

        // 枚举白名单前先按 Locale.ROOT 归一小写（BE-G6-2）：?status=PENDING_PICKUP 与 ?status=pending_pickup
        // 同义；fStatus 拿的是归一后的值，D-19 回显的也就是它（下面 filters.put("status", fStatus) 一字不改即成立）。
        final String fStatus = validatedEnumChoice(status, ORDER_STATUS_FILTERS);
        // storeId 不收形状闸门而是直接交绑定：Spring 把 "abc" 这种绑不进 Long 的脏参在进本方法前
        // 已由 GlobalExceptionHandler:70 转 400（同本类 pageNum=abc 的既有姿势），值只走 #{} 绑定、无拼接面。
        final Long fStoreId = storeId;
        // date 与 overview 共用同一道闸门（先 DATE_PATTERN 形状、再交 LocalDate 裁决真日期，非法 → 400）。
        // 缺省口径照 overview 实况：null/空白 = 不限（这一条与 ?status= 空串得 400 不同，是继承 F-20.9 #2
        // 既有的 date 语义，不是本批新造的分支）。
        final LocalDate fDay = dateAbsent(date) ? null : parseDate(date.trim());

        long total = orderMapper.countOrders(fStatus, fStoreId, fDay);
        long offset = offsetOf(pageNo, size);
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : orderMapper.selectOrdersPage(fStatus, fStoreId, fDay, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 逐键重拼白名单（D-15）：pickup_code / user_id / client_request_id 在 SQL 层就没选，这里也不补；
            // 十三键恒定存在（MyBatis 对 Map 结果不写 null 键，store_name/pay_time 等可空列须显式 put 才形状稳定）
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("totalPrice", moneyText(row.get("totalPrice")));
            line.put("address", row.get("address"));
            line.put("phone", PhoneMask.mask((String) row.get("phone")));
            line.put("pickupDate", row.get("pickupDate"));
            line.put("status", row.get("status"));
            line.put("createTime", row.get("createTime"));
            line.put("updateTime", row.get("updateTime"));
            line.put("storeId", row.get("storeId"));
            line.put("storeName", row.get("storeName"));
            line.put("storeAddress", row.get("storeAddress"));
            line.put("payStatus", row.get("payStatus"));
            line.put("payTime", row.get("payTime"));
            list.add(line);
        }

        // D-19：回显生效筛选值，未传的键回 null。date 回的是规范日期串（过闸门的输入本就是 YYYY-MM-DD，
        // 故回显与传入逐字相同），不回 SQL 侧的 day/day+1 区间两端。
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", fStatus);
        filters.put("storeId", fStoreId);
        filters.put("date", fDay == null ? null : fDay.toString());

        return pageResult(list, total, pageNo, size, filters);
    }

    /**
     * 运营端商品列表（F-20.18，商品管理台数据源；一期只改不建，故这里也只有读）。
     * <p>{@code merchantId} 在本面是<b>筛选条件</b>而非归属闸门（F-20.17 事实 1：运营要全量语义）；
     * 它绝不许被当成商家侧接口来用——商家侧那四条恒带 {@code d.merchant_id = #{merchantId}} 归属谓词的
     * 读路径与本面互不相干。
     * <p>三个筛选的闸门分工（F-20.21 BE-G6-1/BE-G6-2）：{@code onSale} 是 {@code Integer}，直接过白名单
     * （数值无大小写可归，绑定类型不许动）；{@code category} 过 {@link #CATEGORY_PATTERN}——只挡控制字符/零宽，
     * 中文分类值（库里实况）是合法筛选值而不是 400；{@code merchantId} 不过形状闸门，同 orders.storeId 交绑定。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> dishes(Integer pageNum, Integer pageSize, Long merchantId, Integer onSale, String category) {
        int pageNo = validatedPageNum(pageNum);
        int size = validatedPageSize(pageSize);

        final Long fMerchantId = merchantId;
        // onSale 是 Integer（SQL 侧 #{onSale} 绑 TINYINT，绑定类型不许动）：数值没有大小写可归，
        // 所以这一枚走不带归一的 validatedChoice——对 Integer 而言「归一」本就是 no-op，不是漏了这一枚。
        final Integer fOnSale = validatedChoice(onSale, DISH_ON_SALE_FILTERS);
        // category 用自己的那一枚闸门（BE-G6-1）：只挡控制字符/零宽，不再拿 ASCII 字符类当数据字典，
        // 于是 ?category=蔬菜（库里 dish.id=44 的真实取值）从 400 变成正常的等值命中。
        final String fCategory = validatedCategory(category);

        long total = dishMapper.countDishes(fMerchantId, fOnSale, fCategory);
        long offset = offsetOf(pageNo, size);
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : dishMapper.selectDishesPage(fMerchantId, fOnSale, fCategory, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 白名单逐键点名（RD）：description/tags/emoji/bg_color 在 SQL 层就没选。
            // on_sale / seckill 出 0/1 原值（D-18：映射文案归页面，本类不产出「在售/下架」这种第二真源）。
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("name", row.get("name"));
            line.put("price", moneyText(row.get("price")));
            line.put("stock", row.get("stock"));
            line.put("onSale", row.get("onSale"));
            line.put("soldCount", row.get("soldCount"));
            line.put("category", row.get("category"));
            line.put("unit", row.get("unit"));
            line.put("image", row.get("image"));
            line.put("goodRate", row.get("goodRate"));
            line.put("seckill", row.get("seckill"));
            line.put("seckillPrice", row.get("seckillPrice") == null
                    ? null : moneyText(row.get("seckillPrice")));
            line.put("limitBuy", row.get("limitBuy"));
            line.put("merchantId", row.get("merchantId"));
            line.put("createTime", row.get("createTime"));
            line.put("updateTime", row.get("updateTime"));
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("merchantId", fMerchantId);
        filters.put("onSale", fOnSale);
        filters.put("category", fCategory);

        return pageResult(list, total, pageNo, size, filters);
    }

    /**
     * 运营端评价审核队列（F-20.18）。
     * <p>{@code auditStatus} 这一枚<b>不在本层映射</b>：UI 伪值（pending/visible）在<b>白名单入口归一大小写之后</b>
     * （F-20.21 BE-G6-2，只折大小写、不折语义）原样交下去，
     * {@code visible → audit_status IS NULL} 的翻译钉死在 {@code ReviewMapper.REVIEW_FILTER_WHERE} 里（D-17），
     * 于是页 SQL 与 COUNT SQL 用的是同一个已映射形态（D-16），而 D-19 要的「回显 UI 伪值本身」
     * 也就自动成立——Java 侧全程只握着那一枚伪值（归一后的那一枚）。
     * <p>行里的 {@code auditStatus} 出库里原值（'pending' 或 null），null 就是「展示中」（D-18 出原值纪律）。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> reviews(Integer pageNum, Integer pageSize, String auditStatus) {
        int pageNo = validatedPageNum(pageNum);
        int size = validatedPageSize(pageSize);

        // 归一小写后再过白名单（BE-G6-2）：?auditStatus=PENDING 与 ?auditStatus=VISIBLE 都能进来，
        // 归一后的伪值原样交下去，visible → audit_status IS NULL 的映射仍只在 SQL 侧（D-17）。
        final String fAuditStatus = validatedEnumChoice(auditStatus, REVIEW_AUDIT_FILTERS);

        long total = reviewMapper.countReviews(fAuditStatus);
        long offset = offsetOf(pageNo, size);
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : reviewMapper.selectReviewsPage(fAuditStatus, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 白名单（D-15）：user_id 在 SQL 层就没选，这里也不补。七键恒定存在。
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("dishId", row.get("dishId"));
            line.put("rating", row.get("rating"));
            line.put("content", row.get("content"));
            line.put("reviewer", row.get("reviewer"));
            line.put("auditStatus", row.get("auditStatus"));
            line.put("createTime", row.get("createTime"));
            list.add(line);
        }

        // D-19 逐字：回的是 UI 伪值本身（不回 SQL 映射后的形态）。
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("auditStatus", fAuditStatus);

        return pageResult(list, total, pageNo, size, filters);
    }

    /**
     * 运营端入驻申请列表（F-20.18，入驻审核工作台数据源）。
     * <p>{@code contactPhone} 与 orders 面的 {@code phone} 同一处理：SQL 取原文、出参前过 {@link PhoneMask}
     * （该列可空，{@code mask(null)} 原样回 null；非 11 位脏值由 PhoneMask 回全掩码，本类不兜第二层）。
     * {@code proof_photos} 与生成列 {@code pending_guard} 在 SQL 层就不查（D-15 + 契约「绝不 SELECT 生成列」）。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> storeApplies(Integer pageNum, Integer pageSize, String status) {
        int pageNo = validatedPageNum(pageNum);
        int size = validatedPageSize(pageSize);

        // 同样先归一再过白名单（BE-G6-2）：?status=PENDING → 归一为 pending → 命中，回显也是 pending。
        final String fStatus = validatedEnumChoice(status, STORE_APPLY_STATUS_FILTERS);

        long total = storeApplyMapper.countStoreApplies(fStatus);
        long offset = offsetOf(pageNo, size);
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : storeApplyMapper.selectStoreAppliesPage(fStatus, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 十二键恒定存在；lng/lat 是本表 DECIMAL(10,6) 原值（坐标不是金额，不套 moneyText 的两位定形，
            // 否则把点位精度削到小数点后两位＝挪动一公里多，那是新的谎）
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("proposedName", row.get("proposedName"));
            line.put("proposedAddress", row.get("proposedAddress"));
            line.put("lng", row.get("lng"));
            line.put("lat", row.get("lat"));
            line.put("service", row.get("service"));
            line.put("contactName", row.get("contactName"));
            line.put("contactPhone", PhoneMask.mask((String) row.get("contactPhone")));
            line.put("status", row.get("status"));
            line.put("auditRemark", row.get("auditRemark"));
            line.put("auditedAt", row.get("auditedAt"));
            line.put("createdAt", row.get("createdAt"));
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", fStatus);

        return pageResult(list, total, pageNo, size, filters);
    }

    /**
     * 分页闸门（F-20.18 D-16 照抄 auditLogs）：pageNum 缺省 1、下限 1；pageSize 缺省 20、值域 [1,100]。
     * 越界与脏值一律 400、<b>不夹取</b>（把 999 夹成 100 会让「100 条」和「999 条」出同一个数，比报错更难发现，
     * 同 G-05 的 days 判据）。判据与文案与 Step 3/5a 的 auditLogs 内联写法逐字相同——本批把那段抽成这一个
     * 方法给五个列表共用，auditLogs 的行为因此一字未变。
     */
    private int validatedPageNum(Integer pageNum) {
        int pageNo = pageNum == null ? DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < MIN_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        return pageNo;
    }

    /** 分页闸门另一半（与 {@link #validatedPageNum} 同一条 400 文案，不新造句子） */
    private int validatedPageSize(Integer pageSize) {
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        return size;
    }

    /**
     * offset 在这里算，用 {@code long}（同 auditLogs）：pageNum 无上限，int 乘法会溢出成负数、
     * 负 offset 会被 MySQL 当 0 用从而把第 99999999 页画成第一页——越界短路就失效了。
     */
    private long offsetOf(int pageNo, int size) {
        return (long) (pageNo - 1) * size;
    }

    /**
     * 出参五键的唯一拼装点（D-12/D-19 + F-20.16「既有四键一字不动」）：键序固定
     * {@code list/total/pageNum/pageSize/filters}，五个列表同源，不各写一遍。
     */
    private Map<String, Object> pageResult(List<Map<String, Object>> list, long total,
                                           int pageNo, int size, Map<String, Object> filters) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        data.put("filters", filters);
        return data;
    }

    /** date 是否「未传」（与 overview 既有口径逐字相同：null 或全空白 = 不限/今日，不是 400） */
    private boolean dateAbsent(String date) {
        return date == null || date.trim().isEmpty();
    }

    /**
     * 审计筛选值形状闸门（F-20.16 D-11）：一个私有方法搞定四个值，绝不写四遍。
     * <p>null/未传 → 返回 null（该条件在 SQL 侧 {@code (#{x} IS NULL OR ...)} 短路，不筛）；
     * 非 null 必须先过 {@code ^[A-Za-z0-9_.:-]{1,64}$}（{@code -} 在字符类末尾），
     * 不合形状（含空格、含 {@code %}、超 64 字符、空串）→ 400、复用 {@code BAD_REQUEST_MSG} 那一句，本类不新造文案。
     * <p>形状合但库里没这个值 → 交 SQL 正常回空（{@code {list:[],total:0}}），不在本层拦。
     * <p><b>不做大小写归一</b>：等值匹配的不区分大小写来自列 collation（见 mapper 注释），本层原样回显、原样绑定。
     * <p>适用范围（F-20.21 BE-G6-1 收窄）：只有 auditLogs 的 actor/action/targetType/targetId 这四枚——
     * 它们按构造就是 ASCII 码，放宽只会丢掉挡乱码的能力。{@code dishes} 的 {@code category} 已挪去
     * {@link #validatedCategory}（库里存在中文取值，ASCII 字符类会把列表自己展示的分类判成脏参）。
     */
    private String validatedFilter(String raw) {
        return validatedValue(raw, FILTER_PATTERN);
    }

    /**
     * {@code dishes} 面 {@code category} 的形状闸门（F-20.21 BE-G6-1）：除闸门换成
     * {@link #CATEGORY_PATTERN}（长度 1~64 且不含控制字符/零宽）以外，其余判据、文案、null 语义与
     * {@link #validatedFilter} 逐字相同——两枚闸门共用 {@link #validatedValue} 这一段判定体，不写两遍。
     * <p>与 ASCII 那枚的唯一差别是放过什么：中文/空格/{@code %}/emoji 都算合法形状，
     * 换行/制表/NUL/DEL/C1/零宽仍拒。值域不做字典校验（本面从来就没有 category 白名单），
     * 库里没有该分类 → 走 SQL 回空集，不是 400。
     * <p>与 audit-logs 那四枚一样<b>不做大小写归一</b>：category 是自由文本列，等值大小写由列 collation 决定，
     * 本层原样回显、原样绑定（BE-G6-2 的归一只针对<b>枚举</b>白名单那四枚）。
     */
    private String validatedCategory(String raw) {
        return validatedValue(raw, CATEGORY_PATTERN);
    }

    /**
     * 字符串等值筛选的形状判定体：null → null（该条件不生效）；非 null 不过 {@code gate} → 400
     * （复用 {@code BAD_REQUEST_MSG}，本类不新造文案）；过则<b>原样</b>返回——既不 trim 也不改大小写，
     * 回显与绑定的都是调用方送进来的那一枚。
     * <p>闸门由调用方给（{@link #FILTER_PATTERN} 或 {@link #CATEGORY_PATTERN}），判定体只有一份。
     */
    private String validatedValue(String raw, Pattern gate) {
        if (raw == null) {
            return null;
        }
        if (!gate.matcher(raw).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        return raw;
    }

    /**
     * 筛选<b>值域</b>白名单闸门（F-20.18 D-17）：一枚私有方法搞定 orders.status / dish.on_sale /
     * review.audit_status / store_apply.status 四枚枚举的「在不在集合里」判定，绝不写四遍。
     * <p>null/未传 → null（该条件 SQL 侧短路不生效）；非 null 必须是集合成员（对<b>已归一</b>的值做精确判定），
     * 否则 400、复用 {@code BAD_REQUEST_MSG} 那一句。
     * <p>泛型是为了 {@code onSale} 那枚 Integer 与三枚 String 共用同一段判据——白名单是「这一档存不存在」的
     * 语义，与列的 Java 类型无关；绑定仍只走 {@code #{}}，本方法不产出任何 SQL 片段。
     * <p>两个入口：三枚 String 枚举经 {@link #validatedEnumChoice}（先归一小写）进来；
     * {@code onSale} 那枚 Integer 直接调本方法——数值没有大小写可归，归一对它天然是 no-op，
     * 故不许为「统一」把它改成 String，SQL 侧 {@code #{onSale}} 的绑定类型也不动。
     */
    private <T> T validatedChoice(T raw, Set<T> allowed) {
        if (raw == null) {
            return null;
        }
        if (!allowed.contains(raw)) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        return raw;
    }

    /**
     * 枚举止筛的<b>归一 + 白名单</b>入口（F-20.21 BE-G6-2）：{@code raw.toLowerCase(Locale.ROOT)} 之后
     * 才交给 {@link #validatedChoice} 判集合成员。
     * <p>为什么归一在这一层、不在 SQL 侧：库里那三列的值本就按小写字面量存（init.sql 的枚举 DEFAULT 即小写），
     * 白名单也<b>仍只收那几枚小写字面量</b>——归一只是把「调用方大小写随意」这件事接在进白名单之前，
     * 不新增可筛值、也不把「白名单外 → 400」松成「白名单外 → 空集」（那两档语义各自留着，一条没动）。
     * <p>为什么必须归一：同一张 v2 面上 audit-logs 的等值筛因列 collation（utf8mb4_unicode_ci）天然
     * 不区分大小写（{@code actor=ADMIN_USER:2} 实测命中），而这四枚却按大小写敏感的白名单卡 400，
     * 两种语义并存会让 curl 使用者踩坑。
     * <p>为什么是 {@code Locale.ROOT}：不用 {@code toLowerCase()} 的默认 Locale——那在土耳其语环境的机器上
     * 会把 {@code I} 折成带点的 {@code ı}，把「同一份代码两个结果」留在闸门里。
     * <p>返回的是归一后的值，故 {@code filters} 回显的即「实际生效的那个」，不是用户原样输入（D-19 的口径不变）；
     * 大小写不敏感的<b>命中集</b>也不因归一改变——绑下去的是小写值，而库里存的就是小写值。
     */
    private String validatedEnumChoice(String raw, Set<String> allowed) {
        if (raw == null) {
            return null;
        }
        return validatedChoice(raw.toLowerCase(Locale.ROOT), allowed);
    }

    /**
     * date 解析照 {@code MerchantOrderServiceImpl#parseDate} 的既有写法：形状不过或形状符但真日期不存在
     * （2026-13-99、2026-02-30）都是 400、同一条文案。用 DateTimeException 兜住 parse，
     * 不让 DateTimeParseException 逃到全局兜底变成 500。
     * <p><b>年 &ge; 1 下限（F-20.29 BE-G6-4）</b>：Java 的 ISO历接受<b>年 0</b>（0000 按 400 的倍数算闰年），
     * {@code 0000-02-29} 能过 {@code LocalDate.parse}；但 MySQL DATETIME <b>没有年 0</b>，JDBC 层把 0000
     * 归一成 0001，而 0001 非闰年 → {@code 0001-02-29} 在库里报
     * {@code SQLException: Incorrect DATETIME value}——实测 {@code /orders?date=0000-02-29} 因此出 500
     * （overview 同值当时侥幸 200，两条 SQL 的 {@code #{day}} 绑定路径不同，不指望它）。
     * 收在 Java 层：{@code getYear() >= 1} 否则 400（复用 {@code BAD_REQUEST_MSG}，不新造文案）——
     * 年 0 一族（{@code 0000-01-01}、{@code 0000-02-29}…）两面统一变 400；
     * 其余读数一律不变（{@code 0001-01-01}/{@code 0004-02-29}/{@code 0099-12-31}/{@code 1000-01-01}/
     * {@code 1582-10-01}/{@code 9999-12-31} 仍 200，{@code 0999-02-29} 仍 400——那枚本来就由 parse 拦）。
     */
    private LocalDate parseDate(String raw) {
        if (!DATE_PATTERN.matcher(raw).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        try {
            LocalDate parsed = LocalDate.parse(raw);
            // BizException 是 RuntimeException，不会被下面的 catch (DateTimeException) 吞掉重包，语义直抵全局兜底 400
            if (parsed.getYear() < 1) {
                throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
            }
            return parsed;
        } catch (DateTimeException e) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
    }

    /**
     * 计数列归一：MySQL 的 {@code COUNT()} 回 Long、{@code SUM()} 回 BigDecimal、INT 列回 Integer
     * ——别假设是 Integer（同 {@code MerchantOrderServiceImpl#toInteger} 的理由）。
     * <p>null → 0（G5 口径：空数据日不许出 null；SQL 侧 IFNULL 已兜一层，这层兜的是「键缺失」）。
     */
    private Integer count(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /**
     * 金额定形成串（契约 F-20.9 #2，与 G-05 的 N-1 同类要求）：位数钉成 2、HALF_UP，
     * 于是 {@code 12.3} / {@code 12.300000} 都变 {@code 12.30}；串出而不是裸数值，
     * 前端不再补零、也不被 JS number 抹尾零。
     * 走 {@code number.toString()} 再构造，绝不 {@code new BigDecimal(double)}（浮点误差会渗进金额）。
     * null 兜 {@code "0.00"}（同 {@code MerchantOrderServiceImpl#moneyText}）。
     */
    private String moneyText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
        }
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
