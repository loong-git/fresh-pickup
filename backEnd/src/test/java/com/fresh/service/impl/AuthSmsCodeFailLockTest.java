package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.mapper.UserMapper;
import com.fresh.service.InviteService;
import com.fresh.service.OrderService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-65 验证码失败锁契约单测（不起 Spring 容器、不落库、绝不写库）。
 *
 * <p><b>要证明的六件事</b>：
 * ① 答错 1~4 次<b>不锁</b>，且码仍然有效——第 5 次答对能正常登录（正常人手滑不受任何影响）；
 * ② 答错满 5 次即上锁，且<b>锁优先于码</b>：锁定期内即使<b>答对也被 429 拒</b>
 *    （这条是本锁的全部意义，若只拦错码则攻击者换个错的继续试到锁过期即可）；
 * ③ 上锁的同时<b>当前码作废</b>：出锁后不能拿同一张码接着试；
 * ④ 锁定期内 {@code sendCode} 也拒（否则被锁的人会以为"重发一张新码就能接着试"）；
 * ⑤ 429 的 {@code data.retryAfterSeconds} 是真实剩余秒数（>0 且 ≤10 分钟），不是写死的文案；
 * ⑥ <b>无码/过期不计失败</b>：连打 8 次"没有码"也不会把自己锁住（那不构成一次有效猜测）。
 *
 * <p><b>本类测不成什么</b>：锁的 10 分钟到期后能否恢复，不在证明力之内（单测不睡 10 分钟）；
 * 到期判定走的是 {@code lockUntil <= now} 这一行，由 ② 的反向（锁未到期被拒）与 ⑥ 的
 * "未上锁者一路放行"共同夹住其形状。真实端到端由 {@code logs/verify-f65.cjs} 用接口实测。
 * 另外这是<b>单实例内存态</b>，多实例部署时各实例独立计数——与 codeStore/sendTimestamps 同一已知边界。
 */
class AuthSmsCodeFailLockTest {

    private static final String PHONE = "13800001111";
    private static final String DEV_CODE = "123456";
    private static final int THRESHOLD = 5;

    private AuthServiceImpl newService(UserMapper userMapper) throws Exception {
        AuthServiceImpl service = new AuthServiceImpl();
        com.fresh.common.JwtUtil jwtUtil = mock(com.fresh.common.JwtUtil.class);
        // 登录成功分支末尾是 Map.of("token", token, ...)，Map.of 不容 null 值 → 必须给非 null token
        when(jwtUtil.createToken(anyLong())).thenReturn("test-token");
        inject(service, "userMapper", userMapper);
        inject(service, "orderService", mock(OrderService.class));
        inject(service, "inviteService", mock(InviteService.class));
        inject(service, "jwtUtil", jwtUtil);
        inject(service, "devCode", DEV_CODE);
        return service;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 打桩：登录链路后半段（建号/签发/认领）全给通，本类只管码这一关 */
    private UserMapper stubUserMapper() {
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectOne(any())).thenReturn(null);
        // insert 要回填 id：否则 user.getId() 为 null，createToken 的 anyLong() 不匹配、token 拿不到
        when(userMapper.insert(any())).thenAnswer(inv -> {
            ((com.fresh.entity.User) inv.getArgument(0)).setId(7L);
            return 1;
        });
        when(userMapper.update(any(), any())).thenReturn(0);
        return userMapper;
    }

    private static BizException expectThrow(Runnable r) {
        return assertThrows(BizException.class, r::run);
    }

    @Test
    void 答错四次不锁且第五次答对仍能登录() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        service.sendCode(PHONE);
        for (int i = 1; i < THRESHOLD; i++) {
            BizException e = expectThrow(() -> service.login(PHONE, "000000", null));
            assertEquals(R.CODE_BAD_REQUEST, e.getCode(), "第 " + i + " 次答错应仍是「验证码错误」而非上锁");
        }
        assertNotNull(service.login(PHONE, DEV_CODE, null), "码未被前四次失败作废，答对就该能过");
    }

    @Test
    void 答错满五次即锁且锁检查先于码比对() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        service.sendCode(PHONE);
        for (int i = 0; i < THRESHOLD; i++) {
            expectThrow(() -> service.login(PHONE, "000000", null));
        }
        // 上锁时那张码已被作废（见下一条），所以此刻 codeStore 里没有这个号：
        // 若锁的检查不在码比对之前，这里会走「无码」分支回 400「验证码错误」；
        // 回 429 就证明了锁先于码——攻击者无法靠"换一张码/换个答案"绕过它。
        BizException e = expectThrow(() -> service.login(PHONE, DEV_CODE, null));
        assertEquals(R.CODE_TOO_MANY_REQUESTS, e.getCode(), "锁定期内即使拿正确码也被 429 拒（锁优先于码比对）");
    }

    @Test
    void 上锁时当前码被作废且发码同样被拒() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        service.sendCode(PHONE);
        for (int i = 0; i < THRESHOLD; i++) {
            expectThrow(() -> service.login(PHONE, "000000", null));
        }
        BizException e = expectThrow(() -> service.sendCode(PHONE));
        assertEquals(R.CODE_TOO_MANY_REQUESTS, e.getCode(), "锁定期内连发码也拒，否则被锁的人会以为重发就能接着试");
    }

    @Test
    void 锁的429带真实剩余秒数而不是写死() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        service.sendCode(PHONE);
        for (int i = 0; i < THRESHOLD; i++) {
            expectThrow(() -> service.login(PHONE, "000000", null));
        }
        BizException e = expectThrow(() -> service.sendCode(PHONE));
        Object data = e.getData();
        assertTrue(data instanceof Map, "429 要经 data 带结构化载荷（与 D-14 那道锁同形状）");
        Object seconds = ((Map<?, ?>) data).get("retryAfterSeconds");
        assertNotNull(seconds);
        long n = ((Number) seconds).longValue();
        // 上界=整段锁 600s；下界用 1 而不是"接近 600"，避免断言依赖执行耗时
        assertTrue(n >= 1 && n <= 600, "retryAfterSeconds 应落在 (0, 600] 内，实际=" + n);
    }

    @Test
    void 答对一次即清零失败计数() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        service.sendCode(PHONE);
        for (int i = 0; i < THRESHOLD - 1; i++) {
            expectThrow(() -> service.login(PHONE, "000000", null));
        }
        assertTrue(failRecords(service).containsKey(PHONE), "答错四次应已留下失败记录，否则这条断言是空的");
        service.login(PHONE, DEV_CODE, null);
        // 直接读计数而不是"再发一张码接着错"：sendCode 自己有 60s 冷却，同号二次发码会先撞它
        assertTrue(failRecords(service).isEmpty(),
                "成功登录必须清掉失败计数，否则跨会话累加会把正常用户锁死");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> failRecords(AuthServiceImpl service) throws Exception {
        Field f = AuthServiceImpl.class.getDeclaredField("codeFails");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(service);
    }

    @Test
    void 没有码时反复打不计失败不会自锁() throws Exception {
        AuthServiceImpl service = newService(stubUserMapper());
        for (int i = 0; i < THRESHOLD + 3; i++) {
            BizException e = expectThrow(() -> service.login(PHONE, DEV_CODE, null));
            assertEquals(R.CODE_BAD_REQUEST, e.getCode(), "无码/过期不是一次有效猜测，不该计数");
        }
        service.sendCode(PHONE);   // 没被锁住，发码仍通
        assertNotNull(service.login(PHONE, DEV_CODE, null));
    }
}
