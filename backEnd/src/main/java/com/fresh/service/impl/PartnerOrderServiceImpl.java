package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.entity.Store;
import com.fresh.mapper.PartnerOrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.OpsLogWriter;
import com.fresh.service.PartnerOrderService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主理人端实现（F-18.8 第 4 条）。三块逻辑各有一条红线，下面逐块写清了为什么长这样。
 *
 * <p><b>清单（RD/D-13）</b>：取数只走 PartnerOrderMapper 那四条恒带 {@code o.store_id IN (归属集合)} 的手写
 * SQL；出参 map 逐键重建，源行的 phone 键不复制过来、只出 {@code PhoneMask.mask} 结果，
 * 单号只给后 6 位；<b>pickupCode/address/userId 一个都不出</b>——SQL 层就连这三列都没查
 * （把「不输出」写成「不查询」，泄露面才是零）。分页 {@code LIMIT #{offset}, #{size}} + 独立 COUNT（RB）。
 *
 * <p><b>核销（D-6）</b>：定性预读 + 四条件 UPDATE 两步在同一方法里，定性分支按契约逐条给码：
 * 查无此码/非名下门店的单 → 404（他店码不得泄漏「码其实是对的」）；已 completed → 幂等成功且不写日志；
 * cancelled 或未支付 → 400；UPDATE 回 0 行时按码再读定性（BE-4）——并发已被他人核销成 completed = 幂等成功且不计数，
 * 其余维持 404（见方法内注释）。锁定期 429 的 data 带 retryAfterSeconds（BE-5）。
 *
 * <p><b>防枚举（D-14，F-123 收窄计数口径）</b>：按<b>主理人 userId</b> 计失败次数，5 次/10 分钟上锁；锁定期内
 * <b>连正确码也拒</b>（否则枚举可以搭「偶尔猜中」的车续命）；一次成功核销（含幂等成功）即清零。
 * 收窄后只罚「猜码」类失败——{@code code_not_found} / {@code code_not_in_owned_stores} /
 * {@code state_changed_after_read} 三支计数；{@code order_cancelled} / {@code order_unpaid} 是
 * 合法码 + 明确业务状态、正常主理人会连着遇到，不再计数，但 400 经 data 带
 * {@code remainingAttempts}（阈值-当前计数）；404 两支 data 恒 null（回剩余次数=给攻击方报数，红线不回）；
 * 429 锁定信封 data 带 {@code remainingAttempts: 0} 与 {@code retryAfterSeconds} 并列。
 * 计数存内存（Caffeine，写法照 {@code RateLimitInterceptor:40} 的 {@code Cache.newBuilder()}，不引新表）——
 * <b>单实例有效是已登记的边界</b>，多实例部署要换 Redis（G-07/部署批次）。
 */
@Slf4j
@Service
public class PartnerOrderServiceImpl implements PartnerOrderService {

    /** 清单 size 默认 20、上限 50（夹而不拒并回显生效值，同 MerchantOrderServiceImpl:69-70 范式） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** 出参 date 的形状闸门（照 MerchantOrderServiceImpl:73 的 Pattern），形状过再交 LocalDate 裁决真日期 */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 取货码形状：6 位数字（D-4）；形状不符 = 参数不对，走 400 且**不计入**枚举失败次数 */
    private static final Pattern PICKUP_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    /** status 白名单（注意与商家侧不同：这里非法取值 400，不兜底 all，见接口注释） */
    private static final String STATUS_ALL = "all";
    private static final String STATUS_PENDING_PICKUP = "pending_pickup";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final Set<String> STATUS_VALUES =
            Set.of(STATUS_PENDING_PICKUP, STATUS_COMPLETED, STATUS_ALL);

    /** pay_status=1 才算已支付（orders.pay_status 是 tinyint，驱动可能回 Integer/Long/Boolean，统一走 toInteger） */
    private static final int PAY_STATUS_PAID = 1;

    /**
     * 400/404 文案：404 逐字复用 MockPayService:49 那条「订单不存在」；
     * 400 复用 GlobalExceptionHandler:55 的「请求参数有误，请检查后重试」。
     * 「已取消」与「未支付」两种 400 <b>故意共用这一句</b>：给主理人的错误文案不该反过来
     * 告诉持码者这单此刻的支付/取消状态（枚举者能据此二分码空间）。内部定性差异只进日志与 ops_log。
     */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";
    private static final String NOT_FOUND_MSG = "订单不存在";

    // ---------------------------------------------------------------
    // D-14 防枚举锁（常量名即门禁 S3-G5 要改的那两处）
    // ---------------------------------------------------------------

    /** 触发上锁的连续失败次数阈值（D-14 原值：<b>5</b> 次） */
    private static final int VERIFY_FAIL_LOCK_THRESHOLD = 5;

    /**
     * 失败计数窗口 = 锁定期时长（D-14 原值：<b>10 分钟</b>）。
     * <p><b>【一处可改】S3-G5 实测</b>：把本行的 {@code Duration.ofMinutes(10)} 临时改成
     * {@code Duration.ofSeconds(20)}，即可在 20 秒后观察到解锁；计数窗口与锁定期共用这一个常量，
     * 改这一行两处同时生效，<b>验完必须改回 {@code Duration.ofMinutes(10)}</b>（原值见上一行注释）。
     */
    private static final Duration VERIFY_FAIL_LOCK_TTL = Duration.ofMinutes(10);

    /** 失败计数：key=主理人 userId；expireAfterWrite=TTL，每次写入刷新窗口（比「距首次失败 10 分钟」略宽，方向不变） */
    private final Cache<Long, Integer> verifyFailCounts = Caffeine.newBuilder()
            .expireAfterWrite(VERIFY_FAIL_LOCK_TTL)
            .maximumSize(100_000)
            .build();

    /**
     * 锁定期：key=主理人 userId，命中即拒（含正确码）；与计数同 TTL，写入即开始计时。
     * BE-5（F-18.20 E 行）：value 由 Boolean 改存<b>上锁时刻（epoch millis）</b>，
     * 429 信封的 data.retryAfterSeconds 据此按 Caffeine 剩余 TTL 如实回给前端（前端 FE-5 按它起锁）。
     */
    private final Cache<Long, Long> verifyLockouts = Caffeine.newBuilder()
            .expireAfterWrite(VERIFY_FAIL_LOCK_TTL)
            .maximumSize(100_000)
            .build();

    /** BE-5 兜底常量：剩余 TTL 取不到（读取与计算之间的竞态）时按锁的全长 600s 回，宁可多等不可反复撞墙 */
    private static final long LOCK_RETRY_FALLBACK_SECONDS = 600L;

    /**
     * 400 分支 data 里剩余可尝试次数的键名（F-123）。只在「合法码 + 明确业务拒绝」的两支 400 上带；
     * 404 面向「码不存在」，回剩余次数等于告诉攻击方还剩几发——防枚举红线，不回。
     */
    private static final String REMAINING_ATTEMPTS_KEY = "remainingAttempts";

    @Autowired
    private PartnerOrderMapper partnerOrderMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private OpsLogWriter opsLogWriter;

    @Override
    public List<Map<String, Object>> stores(List<Long> storeIds) {
        assertStoreIds(storeIds);

        List<Store> rows = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .select(Store::getId, Store::getName, Store::getAddress, Store::getStatus, Store::getService)
                .in(Store::getId, storeIds)
                .orderByAsc(Store::getId));

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Store row : rows) {
            // 逐键重建：ownerUserId 连 select 列都没取（D-12），这里也不给它任何出口
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("name", row.getName());
            line.put("address", row.getAddress());
            line.put("status", row.getStatus());
            line.put("service", row.getService());
            list.add(line);
        }
        return list;
    }

    @Override
    public Map<String, Object> orders(List<Long> storeIds, String pickupDate, String status,
                                      Integer page, Integer size) {
        assertStoreIds(storeIds);

        boolean dateAbsent = pickupDate == null || pickupDate.trim().isEmpty();
        LocalDate effectiveDate = dateAbsent ? null : parseDate(pickupDate.trim());

        String tab = requireStatus(status);

        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) (pageNo - 1) * pageSize;

        long total = dateAbsent
                ? partnerOrderMapper.countOrdersAllDates(storeIds, tab)
                : partnerOrderMapper.countOrdersByDate(storeIds, effectiveDate, tab);
        // 短路条件 offset >= total（照 MerchantOrderServiceImpl:219-223 的 G-3/L4 修法）：
        // page 无上界，巨大 offset 不必再白跑一条 JOIN 查询；total==0 天然被它覆盖
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : dateAbsent
                ? partnerOrderMapper.selectOrdersAllDates(storeIds, tab, offset, pageSize)
                : partnerOrderMapper.selectOrdersByDate(storeIds, effectiveDate, tab, offset, pageSize);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderId", lastSix(row.get("orderId")));          // D-13：只给后 6 位
            line.put("dishName", row.get("dishName"));
            line.put("quantity", toInteger(row.get("quantity")));
            line.put("price", row.get("price"));
            line.put("storeId", row.get("storeId"));
            line.put("storeName", row.get("storeName"));
            line.put("pickupDate", row.get("pickupDate"));
            line.put("status", row.get("status"));
            line.put("payStatus", toInteger(row.get("payStatus")));
            line.put("maskedPhone", PhoneMask.mask((String) row.get("phone")));  // RD：只出脱敏值
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
     * 核销（D-6 + D-14）。事务只包「条件 UPDATE」这一步（{@code @Transactional} 覆盖整个方法，
     * 但除 UPDATE 外没有别的写）；ops_log 是旁路，写失败被 OpsLogWriter 吞掉，不回滚核销。
     * <b>隔离级别显式降为 READ_COMMITTED</b>：并发输家的「按码再读」（BE-4）必须看到赢家已提交的 completed，
     * 而 MySQL 默认 REPEATABLE READ 会把这次再读锁在事务快照上；条件 UPDATE 仍用当前读，四条件裁决不变。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> verify(Long userId, List<Long> storeIds, String pickupCode) {
        assertStoreIds(storeIds);

        // 1) D-14 闸门在最前面：锁定期内连正确码也拒（429 文案复用 R.MSG_TOO_MANY_REQUESTS，不新增句子）。
        //    BE-5：data 里如实回剩余锁定时长（从上锁时刻与 TTL 推算；取不到按常量 600），文案不动。
        Long lockedAt = userId == null ? null : verifyLockouts.getIfPresent(userId);
        if (lockedAt != null) {
            long remainingMs = VERIFY_FAIL_LOCK_TTL.toMillis() - (System.currentTimeMillis() - lockedAt);
            long retryAfterSeconds = remainingMs > 0
                    ? (long) Math.ceil(remainingMs / 1000.0)
                    : LOCK_RETRY_FALLBACK_SECONDS;
            log.warn("核销锁定期内被拒: operatorUserId={}, 剩余锁定时长={}s", userId, retryAfterSeconds);
            // F-123：锁定中 remainingAttempts 恒 0（与 retryAfterSeconds 并列）——前端可读「已无可再错次数」；
            // message 恒 MSG_TOO_MANY_REQUESTS 逐字不动。
            throw new BizException(R.CODE_TOO_MANY_REQUESTS, R.MSG_TOO_MANY_REQUESTS,
                    Map.of("retryAfterSeconds", retryAfterSeconds, REMAINING_ATTEMPTS_KEY, 0));
        }

        // 2) 码形状：非 6 位数字 = 参数不对 → 400，且**不计入**枚举失败次数（否则用户口误一次就记一笔）
        String code = pickupCode == null ? "" : pickupCode.trim();
        if (!PICKUP_CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }

        // 3) 定性预读（只回 id/store_id/status/pay_status 四列）
        Map<String, Object> row = partnerOrderMapper.selectForVerify(code);
        if (row == null) {
            // F-123 收窄后仍计数：码不存在=可能枚举；404 不带 remainingAttempts（防枚举红线，见 failCountedAs）
            return failCountedAs(userId, R.CODE_NOT_FOUND, NOT_FOUND_MSG, "code_not_found");
        }
        String orderId = asString(row.get("orderId"));
        Object storeId = row.get("storeId");
        String status = asString(row.get("status"));
        Integer payStatus = toInteger(row.get("payStatus"));

        // 归属不在名下（含 store_id 为 NULL 的单）→ 404：他店码不得泄漏「码本身是对的」
        if (storeId == null || !storeIds.contains(storeId)) {
            // F-123 收窄裁定：连打别家码同样是枚举行为，「码不存在/不属于你门店」两类都算猜码 → 保留计数；
            // 同样是 404，不带 remainingAttempts
            return failCountedAs(userId, R.CODE_NOT_FOUND, NOT_FOUND_MSG, "code_not_in_owned_stores");
        }
        // 已核销 → 幂等成功、不写 ops_log（D-6/G4），并清失败计数（一次成功即清零）
        if (STATUS_COMPLETED.equals(status)) {
            clearVerifyState(userId);
            log.info("核销幂等命中: orderId={}, operatorUserId={}", orderId, userId);
            return result(orderId, true);
        }
        // 已取消 或 未支付 → 400（文案共用 BAD_REQUEST_MSG，见常量注释）。
        // F-123 收窄：合法码 + 明确业务状态 = 正常主理人会连着遇到的场景，不再计入防枚举失败；
        // 但 400 带 remainingAttempts（=阈值-当前计数），主理人需要知道「再错几次会锁」。
        if (STATUS_CANCELLED.equals(status)) {
            return failWithoutCounting(userId, R.CODE_BAD_REQUEST, BAD_REQUEST_MSG, "order_cancelled");
        }
        if (payStatus == null || payStatus != PAY_STATUS_PAID) {
            return failWithoutCounting(userId, R.CODE_BAD_REQUEST, BAD_REQUEST_MSG, "order_unpaid");
        }

        // 4) 四条件 UPDATE（D-6 逐字）：归属集合 + 码 + pending_pickup + 已支付
        int updated = partnerOrderMapper.completeByPickupCode(orderId, code, storeIds);
        if (updated == 0) {
            // BE-4（F-18.20 D 行，🟠）：预读说该翻、UPDATE 却回 0 行 = 并发窗口内已被他人核销或状态被改。
            // 码在核销成功后不清空（D-4），按码再读一次即可拿到终态定性：
            // 已 completed → 幂等成功（回 idempotent:true），**不计入枚举失败、不写 ops_log**（两台设备连点不再把自己锁 10 分钟）；
            // 其余（读不到/被改成别的态）维持原 404 定性并计一次失败（并发改态=预读与 UPDATE 间被抢先，
            // 同属「码对但没成」的模糊带，F-123 收窄后仍算猜码一类；404 不带 remainingAttempts）。
            Map<String, Object> reread = partnerOrderMapper.selectForVerify(code);
            if (reread != null && STATUS_COMPLETED.equals(asString(reread.get("status")))) {
                clearVerifyState(userId);
                log.info("核销幂等命中（并发抢先）: orderId={}, operatorUserId={}", orderId, userId);
                return result(orderId, true);
            }
            return failCountedAs(userId, R.CODE_NOT_FOUND, NOT_FOUND_MSG, "state_changed_after_read");
        }

        // 5) 成功：写现场动作审计（detail 只放状态迁移与 storeId，无手机号/地址/码本身）+ 清零计数
        opsLogWriter.write(userId, "verify_order", orderId,
                "{\"from\":\"" + STATUS_PENDING_PICKUP + "\",\"to\":\"" + STATUS_COMPLETED
                        + "\",\"storeId\":" + storeId + "}");
        clearVerifyState(userId);
        log.info("核销成功: orderId={}, storeId={}, operatorUserId={}", orderId, storeId, userId);
        return result(orderId, false);
    }

    // ---------------------------------------------------------------
    // 支撑方法
    // ---------------------------------------------------------------

    /**
     * 归属集合自守卫（纵深防御，非可达分支）：AuthInterceptor 的 /api/partner/ 分支已在集合为空时
     * 收口 403，Controller 拿到的 storeIds 恒非空；留这一行是给「万一有不经该前缀的内部调用」兜底，
     * 不让空集合退化成的 IN () 或「不加条件」的全表读。403 与拦截器同文案（R.MSG_FORBIDDEN）。
     */
    private void assertStoreIds(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            throw new BizException(R.CODE_FORBIDDEN, R.MSG_FORBIDDEN);
        }
    }

    /**
     * status 白名单：null 与空串按<b>默认值 all</b>（清单端点最常见形态就是不带 status，
     * 且 `status=` 空串是小程序侧拼 URL 的常客，判它 400 等于把一个正常请求打成错误）；
     * <b>非空但不在白名单的取值 → 400</b>（与商家侧「未知兜底 all」刻意不同：核销台视图不静默放宽，
     * 传 `status=cancelled` 想捞取消单必须给明确的拒绝，而不是悄悄给他 all）。
     */
    private String requireStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return STATUS_ALL;
        }
        String normalized = status.trim();
        if (!STATUS_VALUES.contains(normalized)) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        return normalized;
    }

    /**
     * 计数失败收口（F-123 收窄后仅两处调用点：{@code code_not_found} 与 {@code state_changed_after_read}，
     * 外加 {@code code_not_in_owned_stores}——「码不存在/不属于你门店」两类都算猜码，收窄裁定口径）：
     * 记一笔 D-14 失败计数后抛原定性的码。404/400 都可能是这一类；**data 恒 null**——404 面向
     * 「码不存在」，回剩余次数等于告诉攻击方还剩几发（防枚举红线，契约 §3 明文不回）。
     * 码形状错不走这里（在 step 2 直接抛，不计数）。
     */
    private Map<String, Object> failCountedAs(Long userId, int code, String message, String reason) {
        log.warn("核销失败: reason={}, operatorUserId={}, code={}", reason, userId, code);
        recordVerifyFailure(userId);
        throw new BizException(code, message);
    }

    /**
     * 不计数失败收口（F-123 收窄新增，调用点 {@code order_cancelled} / {@code order_unpaid}）：
     * 合法码 + 明确业务状态，正常主理人会连着遇到，不再计入防枚举失败；但 400 经 data 带
     * {@code remainingAttempts = 阈值 - 当前计数}，主理人需要知道「再错几次会锁」。
     * message 恒 BAD_REQUEST_MSG 逐字不动（不给持码者支付/取消状态的二分信号）。
     */
    private Map<String, Object> failWithoutCounting(Long userId, int code, String message, String reason) {
        log.warn("核销失败（不计数）: reason={}, operatorUserId={}, code={}", reason, userId, code);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(REMAINING_ATTEMPTS_KEY, remainingAttemptsOf(userId));
        throw new BizException(code, message, data);
    }

    /**
     * 剩余可尝试次数（F-123）= 阈值 - 当前失败计数；计数已含本次？不含——两个调用点都不计数，
     * 这里的「当前计数」是该主理人此前累计的猜测类失败数。userId 为 null（理论不可达的自守卫路径）
     * 或无记录时按满额阈值回。竞态下多算/少算一次均无害：前端只拿它拼「再错 N 次将锁定」的提示文案。
     */
    private int remainingAttemptsOf(Long userId) {
        if (userId == null) {
            return VERIFY_FAIL_LOCK_THRESHOLD;
        }
        Integer current = verifyFailCounts.getIfPresent(userId);
        int remaining = VERIFY_FAIL_LOCK_THRESHOLD - (current == null ? 0 : current);
        return Math.max(remaining, 0);
    }

    /** 达阈值即上锁：计数 +1，≥{@link #VERIFY_FAIL_LOCK_THRESHOLD} 写入锁定期并清计数（解锁后从 0 开始） */
    private void recordVerifyFailure(Long userId) {
        if (userId == null) {
            return;
        }
        int fails = verifyFailCounts.asMap().compute(userId, (key, current) -> current == null ? 1 : current + 1);
        if (fails >= VERIFY_FAIL_LOCK_THRESHOLD) {
            // BE-5：上锁时刻（epoch millis）作为 value——429 信封的 retryAfterSeconds 由它推算剩余 TTL
            verifyLockouts.put(userId, System.currentTimeMillis());
            verifyFailCounts.invalidate(userId);
            log.warn("取货码防枚举上锁: operatorUserId={}, fails={}, lockFor={}", userId, fails, VERIFY_FAIL_LOCK_TTL);
        }
    }

    /** 成功（含幂等成功）清零：失败计数与锁定态一并作废 */
    private void clearVerifyState(Long userId) {
        if (userId == null) {
            return;
        }
        verifyFailCounts.invalidate(userId);
        verifyLockouts.invalidate(userId);
    }

    /** 核销出参：只有单号后 6 位 + 终态 + 幂等标记；不回显码、不回显电话/地址/userId */
    private Map<String, Object> result(String orderId, boolean idempotent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", lastSix(orderId));
        data.put("status", STATUS_COMPLETED);
        data.put("idempotent", idempotent);
        return data;
    }

    /** 单号只给后 6 位（D-13）：不足 6 位原样给（现库是 19 位雪花串，走不到这个分支，留作不抛错的兜底） */
    private String lastSix(Object orderId) {
        String raw = asString(orderId);
        if (raw == null) {
            return null;
        }
        return raw.length() <= 6 ? raw : raw.substring(raw.length() - 6);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** date 解析照 MerchantOrderServiceImpl#parseDate：形状不过或真日期不过都 400，同一条文案 */
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
     * 聚合/驱动类型归一（照 MerchantOrderServiceImpl#toInteger）：
     * SUM 回 BigDecimal、COUNT 回 Long、INT 列回 Integer，tinyint 在 tinyInt1isBit 下可能回 Boolean，
     * 一律 Number/Boolean 判定，不做强转。
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
