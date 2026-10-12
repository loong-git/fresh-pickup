package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.OrderMapper;
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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 运营端 v2 只读实现（F-20.9 #2/#3）。两个方法都只做「校验 + 取数 + 白名单重拼」，不参与任何口径计算。
 *
 * <p><b>大盘口径（F-20.9 #2 逐字照 {@code docs/日常运营SQL手册.md:111-115} 的今日经营概况）</b>：
 * {@code COUNT(*)} / {@code IFNULL(SUM(total_price),0)} / {@code SUM(status='pending_pickup')} /
 * {@code IFNULL(SUM(CASE WHEN pay_status=1 THEN total_price ELSE 0 END),0)}，
 * 窗口是 {@code [当日 00:00, 次日 00:00)} 半开区间，与手册的
 * {@code create_time >= CURDATE() AND create_time < CURDATE() + INTERVAL 1 DAY} 同一个区间。
 * 这是<b>订单口径</b>，与 G-05 商家看板的「券前明细口径」（{@code order_item.price × quantity}）分列、互不换算
 * （D-7），本类不出现任何明细级聚合。
 * <p>与手册的唯一一处差异：手册里待自提那列是裸 {@code SUM(status = 'pending_pickup')}，空数据日它回 NULL，
 * 而契约要求「空数据日四个 0 值不是 null」，故此处补一层 {@code IFNULL(...,0)}——谓词与表达式本体一字未动。
 *
 * <p><b>「今日」由 SQL 侧 {@code CURDATE()} 决定</b>（同 F-19/D-3 的时间窗纪律）：date 缺省时本类
 * <b>不</b>调 {@code LocalDate.now()}——那等于把「今天」交给应用服务器时区，与库时区可能差一天，
 * 也会凭空多出第二个窗口口径。只有调用方显式给了某一天，才把那一天搬成区间的两端。
 *
 * <p><b>取数通道（本类唯一的实现妥协，如实登记）</b>：本期允许改/新增的文件清单里没有
 * {@code mapper/OrderMapper.java}，也没有新建「第四张 mapper」的位置，所以上面那条聚合 SQL 不是
 * OrderMapper 里的 {@code @Select}，而是走 OrderMapper <b>继承来的</b> {@code BaseMapper#selectMaps}
 * ＋一个 {@code QueryWrapper}（SELECT 列表是常量字符串、无外部输入拼接；窗口两端是绑定参数）。
 * {@code admin_audit_log} 那两条则是 {@code AdminAuditLogMapper} 里追加的手写 {@code @Select}
 * （分页形态照 {@code OrderItemMapper} 的 F-17 那一组）。若主会话希望大盘也收进 mapper 手写 SQL，
 * 需要放开 {@code OrderMapper} 这个文件，本期不擅自越界。
 *
 * <p><b>脱敏 / 最小暴露（RD）</b>：出参两个方法都是逐键重拼的手工白名单 Map，绝不吐实体、绝不吐聚合行的原始 Map；
 * 审计那条 SQL <b>连 {@code ip} 列都没查</b>；大盘那条 SQL 只取聚合值，不取 orders 的任何一行
 * （{@code phone/address/user_id/pickup_code} 既没进 SELECT 也没进条件）。
 *
 * <p><b>分页（RB）</b>：本工程没注册 MP 的内置分页拦截器，用那一套分页对象会静默返回全量
 * 且 total=0，故审计页是「页 + 独立 COUNT」两条手写 SQL，offset 在此算；越界与脏值一律 400（不夹到边界，
 * 理由同 G-05 的 days：把 999 夹成 100 会让「100 条」和「999 条」出同一个数，比报错更难发现）。
 */
@Slf4j
@Service
public class AdminV2ServiceImpl implements AdminV2Service {

    /**
     * 大盘四列（一次聚合取回，恒 1 行；写法即手册那四条表达式，别名换成本仓出参惯用的驼峰）。
     * 走 {@code QueryWrapper#select(String...)} 的单元素形态——MP 内部就是 {@code String.join(",")},
     * 传一条自带逗号的常量串等价于逐列点名的 SELECT 列表。
     */
    private static final String OVERVIEW_COLUMNS = "COUNT(*) AS orderCount, "
            + "IFNULL(SUM(total_price), 0) AS totalAmount, "
            + "IFNULL(SUM(status = 'pending_pickup'), 0) AS pendingPickupCount, "
            + "IFNULL(SUM(CASE WHEN pay_status = 1 THEN total_price ELSE 0 END), 0) AS paidAmount";

    /** date 缺省时的窗口（手册逐字口径：今日由库侧 CURDATE() 给，不由 Java 时钟给） */
    private static final String OVERVIEW_WINDOW_TODAY =
            "create_time >= CURDATE() AND create_time < CURDATE() + INTERVAL 1 DAY";

    /** 出参 date 的形状闸门（照 {@code MerchantOrderServiceImpl:73} 等三处既有先例的写法；先形状再交 LocalDate 裁决真日期） */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 400 文案：复用既有那一句（GlobalExceptionHandler:55 同串），本类不新造句子 */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    /** 审计分页：pageNum 下限 1（无上限，越界的巨大 offset 由下面的短路兜住）；pageSize 缺省 20、值域 [1,100] */
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

    @Override
    public Map<String, Object> overview(String date) {
        boolean dateAbsent = date == null || date.trim().isEmpty();
        LocalDate day = dateAbsent ? null : parseDate(date.trim());

        QueryWrapper<Order> wrapper = new QueryWrapper<Order>().select(OVERVIEW_COLUMNS);
        if (dateAbsent) {
            wrapper.apply(OVERVIEW_WINDOW_TODAY);
        } else {
            // 显式给了某一天：区间 [day 00:00, day+1 00:00)，与手册的 CURDATE() 半开区间同形。
            // 这里的 plusDays 只是把「调用方指定的那个自然日」搬成DateTime 上界（不含时区语义），
            // 与 D-3 禁的那件事（用 Java 时钟决定「今日」）不是同一件事。
            wrapper.ge("create_time", day.atStartOfDay())
                   .lt("create_time", day.plusDays(1).atStartOfDay());
        }

        List<Map<String, Object>> rows = orderMapper.selectMaps(wrapper);
        // 无 GROUP BY 的聚合恒回 1 行，取不到行在这里不可达；留这行只为万一时不把 null 传进取值方法
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        // 键序即契约 F-20.9 #2 的四指标顺序（单量 / 总金额 / 待自提单数 / 已收款金额）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCount", count(row.get("orderCount")));
        data.put("totalAmount", moneyText(row.get("totalAmount")));
        data.put("pendingPickupCount", count(row.get("pendingPickupCount")));
        data.put("paidAmount", moneyText(row.get("paidAmount")));
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> auditLogs(Integer pageNum, Integer pageSize) {
        // 越界/脏值 → 400（契约 F-20.9 #3：pageNum 下限 1、pageSize 缺省 20 上限 100）。
        // ?pageNum=abc 这类绑不进 Integer 的脏参在进本方法之前已由 GlobalExceptionHandler 转 400。
        int pageNo = pageNum == null ? DEFAULT_PAGE_NUM : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (pageNo < MIN_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }

        long total = adminAuditLogMapper.countAuditLogs();
        long offset = (long) (pageNo - 1) * size;
        // offset >= total 直接短路（同 MerchantOrderServiceImpl:219-223 的 G-3/L4 修法）：
        // pageNum 无上限，999999999 会算出巨大 offset，短路就不再白跑一条 LIMIT 查询；
        // 空表（total=0）时任何页都走这一支 → list:[] 而不是 500。
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : adminAuditLogMapper.selectAuditLogsPage(offset, size);

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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        return data;
    }

    /**
     * date 解析照 {@code MerchantOrderServiceImpl#parseDate} 的既有写法：形状不过或形状符但真日期不存在
     * （2026-13-99、2026-02-30）都是 400、同一条文案。用 DateTimeException 兜住 parse，
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
