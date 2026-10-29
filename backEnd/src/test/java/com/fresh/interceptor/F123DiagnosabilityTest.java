package com.fresh.interceptor;

import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.entity.User;
import com.fresh.mapper.PartnerOrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.InviteService;
import com.fresh.service.OpsLogWriter;
import com.fresh.service.OrderService;
import com.fresh.service.impl.AuthServiceImpl;
import com.fresh.service.impl.PartnerOrderServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;/**
 * F-123 失败可诊断性契约单测（契约 §5 ①②③④，不起 Spring 容器、不落库、绝不写库；
 * 装配姿势照 AuthSmsCodeFailLockTest：直接 new + 反射注入 mock mapper）。
 *
 * <p><b>要证明的四件事</b>：
 * ① AuthInterceptor 的 403 机读原因 {@link AuthInterceptor#forbiddenReasonOf} 五枚举
 *    （admin_token_rejected / user_banned / user_missing / not_merchant / no_store）按判定事实逐一对号；
 * ② D-14 收窄：cancel/unpaid 两支<b>不计数</b>（连打多张未支付/已取消码不再上锁），
 *    code_not_found / code_not_in_owned_stores / state_changed_after_read 三支<b>仍计数</b>；
 * ③ remainingAttempts 形状：400 两支带 {@code remainingAttempts = 阈值 - 当前计数} 且随计数递减；
 *    404 两支 data 恒 null（防枚举红线：不向攻击方报剩余发数）；
 * ④ 发码两支 429 的 data.reason（sms_send_interval / sms_send_daily_limit）与 retryAfterSeconds 形状。
 *
 * <p><b>本类测不成什么</b>：拦截器写 response 的出参字节（禁 mock servlet，全仓离线测试的既有性质），
 * ①用它的静态纯函数测；真实 403/429 body 由主会话门禁 curl 验（契约 §6 ①②④）。
 */
class F123DiagnosabilityTest {

    private static final String PHONE = "13800001234";
    private static final String DEV_CODE = "123456";
    private static final Long USER_ID = 7L;
    private static final List<Long> OWNED_STORES = List.of(10L, 11L);
    private static final int THRESHOLD = 5;

    // ---------------------------------------------------------------
    // ① 拦截器 403 reason 五枚举（静态纯函数直测，不起容器不 mock servlet）
    // ---------------------------------------------------------------

    @Test
    void reason五枚举按判定事实逐一对号() {
        User active = new User();
        active.setStatus(User.STATUS_ACTIVE);
        User banned = new User();
        banned.setStatus(User.STATUS_BANNED);

        // typ=admin token 打用户面（F-12/G-01 typ 隔离）
        assertEquals(AuthInterceptor.FORBIDDEN_ADMIN_TOKEN_REJECTED,
                AuthInterceptor.forbiddenReasonOf(active, true, false, null));
        // token 有效但用户行已删（fail-closed）
        assertEquals(AuthInterceptor.FORBIDDEN_USER_MISSING, AuthInterceptor.forbiddenReasonOf(null, false, false, null));
        // banned（F-122 已签发旧 token 即时失效）
        assertEquals(AuthInterceptor.FORBIDDEN_USER_BANNED, AuthInterceptor.forbiddenReasonOf(banned, false, false, null));
        // 非 merchant 角色或 merchant_id 空（F-12/G-01 查库定角色）
        assertEquals(AuthInterceptor.FORBIDDEN_NOT_MERCHANT, AuthInterceptor.forbiddenReasonOf(active, false, true, null));
        // 名下无店（F-18.8 归属集合为空）
        assertEquals(AuthInterceptor.FORBIDDEN_NO_STORE, AuthInterceptor.forbiddenReasonOf(active, false, false, List.of()));
    }

    @Test
    void reason五枚举值逐字等于契约白名单() {
        // 机读键必须与 F-123 契约 §1.1 的白名单逐字一致（camelCase 稳定不动）
        assertEquals("admin_token_rejected", AuthInterceptor.FORBIDDEN_ADMIN_TOKEN_REJECTED);
        assertEquals("user_banned", AuthInterceptor.FORBIDDEN_USER_BANNED);
        assertEquals("user_missing", AuthInterceptor.FORBIDDEN_USER_MISSING);
        assertEquals("not_merchant", AuthInterceptor.FORBIDDEN_NOT_MERCHANT);
        assertEquals("no_store", AuthInterceptor.FORBIDDEN_NO_STORE);
        // 429/403 共用键名 reason；写侧限流的枚举值由本拦截器族定义
        assertEquals("ip_rate_limit", RateLimitInterceptor.REASON_IP_RATE_LIMIT);
    }

    // ---------------------------------------------------------------
    // ② D-14 收窄：cancel/unpaid 不计数，猜码三支仍计数
    // ---------------------------------------------------------------

    @Test
    void 已取消与未支付连打不再上锁() throws Exception {
        // F-123 收窄依据：合法码 + 明确业务状态 = 正常主理人会连着遇到的场景，不再计入防枚举失败。
        // 旧基线是 cancel/unpaid 同样走计数（5 张未支付码即 429 锁定），收窄后连打 6 张必须不锁。
        PartnerOrderServiceImpl service = newServiceForVerify();
        stubRow(service, "400001", Map.of(
                "orderId", "3001", "storeId", 10L, "status", "cancelled", "payStatus", 1));
        stubRow(service, "400002", Map.of(
                "orderId", "3002", "storeId", 10L, "status", "pending_pickup", "payStatus", 0));

        for (int i = 0; i < 3; i++) {
            BizException cancelled = expectVerifyThrow(service, "400001");
            assertEquals(R.CODE_BAD_REQUEST, cancelled.getCode(), "已取消仍是 400，文案不动");
            BizException unpaid = expectVerifyThrow(service, "400002");
            assertEquals(R.CODE_BAD_REQUEST, unpaid.getCode(), "未支付仍是 400，文案不动");
        }
        assertTrue(verifyLockouts(service).asMap().isEmpty(), "cancel/unpaid 不计数，连打 6 张也不得上锁");
        assertTrue(verifyFailCounts(service).asMap().isEmpty(), "cancel/unpaid 不计数，失败计数不得留下记录");
    }

    @Test
    void 猜空码仍计数且满阈值上锁() throws Exception {
        // 码不存在 = 可能枚举，收窄后仍计数：5 次即上锁（D-14 本意不变）
        PartnerOrderServiceImpl service = newServiceForVerify();
        for (int i = 0; i < THRESHOLD - 1; i++) {
            assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "999999").getCode());
        }
        assertTrue(verifyLockouts(service).asMap().isEmpty(), "4 次未达阈值不应上锁");
        assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "999999").getCode());
        assertTrue(verifyLockouts(service).asMap().containsKey(USER_ID), "第 5 次猜空码必须上锁");
    }

    @Test
    void 他店码仍计数且满阈值上锁() throws Exception {
        // 他店码：store_id 不在归属集合 → 404，仍计数（「码对但不是你的」同样是枚举行为，收窄裁定口径）
        PartnerOrderServiceImpl service = newServiceForVerify();
        stubRow(service, "444444", Map.of(
                "orderId", "3003", "storeId", 99L, "status", "pending_pickup", "payStatus", 1));
        for (int i = 0; i < THRESHOLD; i++) {
            assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "444444").getCode());
        }
        assertTrue(verifyLockouts(service).asMap().containsKey(USER_ID), "连打他店码同样要上锁");
    }

    @Test
    void 并发改态仍计数且满阈值上锁() throws Exception {
        // 并发改态（BE-4）：预读说该翻、UPDATE 回 0 行、按码再读仍非 completed → 404，仍计数
        PartnerOrderServiceImpl service = newServiceForVerify();
        stubRow(service, "555555", Map.of(
                "orderId", "3004", "storeId", 10L, "status", "pending_pickup", "payStatus", 1));
        when(mapperOf(service).completeByPickupCode(anyString(), anyString(), anyList())).thenReturn(0);
        for (int i = 0; i < THRESHOLD; i++) {
            assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "555555").getCode());
        }
        assertTrue(verifyLockouts(service).asMap().containsKey(USER_ID), "state_changed_after_read 仍要计数上锁");
    }

    // ---------------------------------------------------------------
    // ③ remainingAttempts：400 带且随计数递减；404 data 恒 null
    // ---------------------------------------------------------------

    @Test
    void 未支付400带remainingAttempts且随计数递减() throws Exception {
        // 阈值 5：先猜错 1 次空码（计数=1）→ 未支付 400 应回 remainingAttempts=4；
        // 再猜错 1 次（计数=2）→ 应回 3，两读之间必须递减
        PartnerOrderServiceImpl service = newServiceForVerify();
        stubRow(service, "777777", Map.of(
                "orderId", "3005", "storeId", 10L, "status", "pending_pickup", "payStatus", 0));

        assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "999999").getCode());
        BizException first = expectVerifyThrow(service, "777777");
        assertEquals(R.CODE_BAD_REQUEST, first.getCode());
        assertEquals(THRESHOLD - 1, remainingOf(first), "1 次猜码在先，未支付拒绝应回「还剩 4 发」");

        assertEquals(R.CODE_NOT_FOUND, expectVerifyThrow(service, "999999").getCode());
        BizException second = expectVerifyThrow(service, "777777");
        assertEquals(THRESHOLD - 2, remainingOf(second), "计数累加后 remainingAttempts 必须递减");
    }

    @Test
    void 已取消400也带remainingAttempts() throws Exception {
        PartnerOrderServiceImpl service = newServiceForVerify();
        stubRow(service, "888888", Map.of(
                "orderId", "3006", "storeId", 10L, "status", "cancelled", "payStatus", 1));

        BizException e = expectVerifyThrow(service, "888888");
        assertEquals(R.CODE_BAD_REQUEST, e.getCode());
        assertEquals(THRESHOLD, remainingOf(e), "无前置失败时剩余次数 = 满额阈值 5");
    }

    @Test
    void 三支404的data恒null不回剩余次数() throws Exception {
        // 防枚举红线（契约 §3）：404 面向「码不存在」，回 remainingAttempts = 告诉攻击方还剩几发，绝不回
        PartnerOrderServiceImpl notFound = newServiceForVerify();
        BizException e1 = expectVerifyThrow(notFound, "999999");
        assertEquals(R.CODE_NOT_FOUND, e1.getCode());
        assertNull(e1.getData(), "code_not_found 的 404 不得带 data");

        PartnerOrderServiceImpl foreign = newServiceForVerify();
        stubRow(foreign, "444444", Map.of(
                "orderId", "3003", "storeId", 99L, "status", "pending_pickup", "payStatus", 1));
        BizException e2 = expectVerifyThrow(foreign, "444444");
        assertEquals(R.CODE_NOT_FOUND, e2.getCode());
        assertNull(e2.getData(), "code_not_in_owned_stores 的 404 同样不得带 data");

        PartnerOrderServiceImpl raced = newServiceForVerify();
        stubRow(raced, "555555", Map.of(
                "orderId", "3004", "storeId", 10L, "status", "pending_pickup", "payStatus", 1));
        when(mapperOf(raced).completeByPickupCode(anyString(), anyString(), anyList())).thenReturn(0);
        BizException e3 = expectVerifyThrow(raced, "555555");
        assertEquals(R.CODE_NOT_FOUND, e3.getCode());
        assertNull(e3.getData(), "state_changed_after_read 的 404 不得带 data");
    }

    @Test
    void 锁定期429带remainingAttempts为零与retryAfterSeconds并列() throws Exception {
        PartnerOrderServiceImpl service = newServiceForVerify();
        for (int i = 0; i < THRESHOLD; i++) {
            expectVerifyThrow(service, "999999");
        }
        BizException e = expectVerifyThrow(service, "999999");
        assertEquals(R.CODE_TOO_MANY_REQUESTS, e.getCode());
        assertEquals(R.MSG_TOO_MANY_REQUESTS, e.getMessage(), "message 逐字不变（F-123 红线）");
        Map<?, ?> data = dataOf(e);
        assertEquals(0, remainingOf(e), "锁定中的 remainingAttempts 恒 0");
        Object seconds = data.get("retryAfterSeconds");
        assertNotNull(seconds, "既有 retryAfterSeconds 必须仍在（BE-5 语义不变）");
        long n = ((Number) seconds).longValue();
        assertTrue(n >= 1 && n <= 600, "retryAfterSeconds 应落在 (0, 600] 内，实际=" + n);
    }

    // ---------------------------------------------------------------
    // ④ 发码两支 429 的 data.reason 与 retryAfterSeconds 形状
    // ---------------------------------------------------------------

    @Test
    void 发码60s频控429带sms_send_interval() throws Exception {
        AuthServiceImpl service = newAuthService();
        service.sendCode(PHONE);
        BizException e = assertThrows(BizException.class, () -> service.sendCode(PHONE));
        assertEquals(429, e.getCode());
        assertEquals("发送太频繁，请 1 分钟后再试", e.getMessage(), "message 逐字不变（F-123 红线）");
        Map<?, ?> data = dataOf(e);
        assertEquals("sms_send_interval", data.get("reason"));
        assertEquals(60L, ((Number) data.get("retryAfterSeconds")).longValue(), "频控档回 60s 真值");
    }

    @Test
    void 发码日上限429带sms_send_daily_limit与现算秒数() throws Exception {
        AuthServiceImpl service = newAuthService();
        // 桩 100 条发送打点=22h 前：60s 频控（比较最后一条）不命中，24h 窗仍满额 → 精确打到日上限一支，
        // 现算秒数 ≈ 24h - 22h = 2h（绝不是 3600 兜底；也远大于 60s，证明不是频控档）
        long twentyTwoHoursAgo = System.currentTimeMillis() - 22 * 3600_000L;
        sendTimestampsOf(service).put(PHONE,
                new ArrayList<>(Collections.nCopies(100, twentyTwoHoursAgo)));

        BizException e = assertThrows(BizException.class, () -> service.sendCode(PHONE));
        assertEquals(429, e.getCode());
        assertEquals("今日发送次数已达上限，请明日再试", e.getMessage(), "message 逐字不变（F-123 红线）");
        Map<?, ?> data = dataOf(e);
        assertEquals("sms_send_daily_limit", data.get("reason"));
        long seconds = ((Number) data.get("retryAfterSeconds")).longValue();
        // 现算真值 ≈ 2h（含 1s 起算偏移）；夹住 1h < 秒数 ≤ 2h+1s，排除 3600 兜底与频控档
        assertTrue(seconds > 3600L && seconds <= 2 * 3600L + 1,
                "日上限档应回「窗内最早打点 + 24h」的现算秒数（约 2h）而非 3600 兜底，实际=" + seconds);
    }

    // ---------------------------------------------------------------
    // 装配与桩（照 AuthSmsCodeFailLockTest 的纯 mock 姿势）
    // ---------------------------------------------------------------

    private PartnerOrderServiceImpl newServiceForVerify() throws Exception {
        PartnerOrderServiceImpl service = new PartnerOrderServiceImpl();
        PartnerOrderMapper mapper = mock(PartnerOrderMapper.class);
        when(mapper.selectForVerify(anyString())).thenReturn(null);
        when(mapper.completeByPickupCode(anyString(), anyString(), anyList())).thenReturn(1);
        inject(service, "partnerOrderMapper", mapper);
        inject(service, "storeMapper", mock(StoreMapper.class));
        inject(service, "opsLogWriter", mock(OpsLogWriter.class));
        return service;
    }

    /** 给定码打桩定性预读行（四列同 mapper 真出参：orderId/storeId/status/payStatus） */
    private static void stubRow(PartnerOrderServiceImpl service, String code, Map<String, Object> row) throws Exception {
        when(mapperOf(service).selectForVerify(code)).thenReturn(row);
    }

    @SuppressWarnings("unchecked")
    private static PartnerOrderMapper mapperOf(PartnerOrderServiceImpl service) throws Exception {
        return (PartnerOrderMapper) fieldOf(service, "partnerOrderMapper");
    }

    private BizException expectVerifyThrow(PartnerOrderServiceImpl service, String code) {
        try {
            service.verify(USER_ID, OWNED_STORES, code);
            throw new AssertionError("verify 应抛 BizException: code=" + code);
        } catch (BizException e) {
            return e;
        }
    }

    private AuthServiceImpl newAuthService() throws Exception {
        AuthServiceImpl service = new AuthServiceImpl();
        inject(service, "userMapper", mock(UserMapper.class));
        inject(service, "orderService", mock(OrderService.class));
        inject(service, "inviteService", mock(InviteService.class));
        inject(service, "jwtUtil", mock(JwtUtil.class));
        inject(service, "devCode", DEV_CODE);
        return service;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    private static com.github.benmanes.caffeine.cache.Cache<Long, Long> verifyLockouts(PartnerOrderServiceImpl service) {
        try {
            return (com.github.benmanes.caffeine.cache.Cache<Long, Long>) fieldOf(service, "verifyLockouts");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static com.github.benmanes.caffeine.cache.Cache<Long, Integer> verifyFailCounts(PartnerOrderServiceImpl service) {
        try {
            return (com.github.benmanes.caffeine.cache.Cache<Long, Integer>) fieldOf(service, "verifyFailCounts");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Long>> sendTimestampsOf(AuthServiceImpl service) {
        try {
            return (Map<String, List<Long>>) fieldOf(service, "sendTimestamps");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int remainingOf(BizException e) {
        Object remaining = dataOf(e).get("remainingAttempts");
        assertNotNull(remaining, "400/429 收口必须带 remainingAttempts");
        return ((Number) remaining).intValue();
    }

    private static Map<?, ?> dataOf(BizException e) {
        Object data = e.getData();
        assertTrue(data instanceof Map, "F-123 收口必须经 data 带结构化载荷，实际=" + data);
        return (Map<?, ?>) data;
    }
}
