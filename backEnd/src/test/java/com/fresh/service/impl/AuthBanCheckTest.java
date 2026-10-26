package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.PasswordUtil;
import com.fresh.common.R;
import com.fresh.entity.User;
import com.fresh.mapper.UserMapper;
import com.fresh.service.InviteService;
import com.fresh.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-122 登录侧封禁判定单测（照 AuthSmsCodeFailLockTest 的装配方式：直接 new + 反射注入，不起容器不落库）。
 *
 * <p><b>要证明的四件事</b>：
 * ① banned 用户验证码登录 → 403「账号已被封禁」，且不签发 token；
 * ② banned 用户密码登录 → 同 403/同文案（判封禁在密码校验之后，不构成手机号状态探测面）；
 * ③ active 用户验证码/密码两路照常签发；
 * ④ {@code status=null}（m30 迁移前存量行语义）按正常处理，两路均可登录。
 *
 * <p><b>本类测不成什么</b>：已签发 token 的即时失效在 {@code AuthInterceptor}（每请求查 user.status），
 * 本类只覆盖登录入口；拦截器行为由真库接口验收覆盖。
 */
class AuthBanCheckTest {

    private static final String PHONE = "13800001234";
    private static final String DEV_CODE = "123456";
    private static final String PASSWORD = "f122-Passw0rd";
    private static final Long USER_ID = 7L;
    private static final String TOKEN = "test-token";

    private UserMapper userMapper;
    private JwtUtil jwtUtil;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        userMapper = mock(UserMapper.class);
        jwtUtil = mock(JwtUtil.class);
        // 登录成功分支末尾是 Map.of("token", token, ...)，Map.of 不容 null 值 → 必须给非 null token
        when(jwtUtil.createToken(anyLong())).thenReturn(TOKEN);

        service = new AuthServiceImpl();
        inject(service, "userMapper", userMapper);
        inject(service, "orderService", mock(OrderService.class));
        inject(service, "inviteService", mock(InviteService.class));
        inject(service, "jwtUtil", jwtUtil);
        inject(service, "devCode", DEV_CODE);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static User user(String status) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(PHONE);
        u.setStatus(status);
        return u;
    }

    private static User passwordUser(String status) {
        User u = user(status);
        u.setPasswordHash(PasswordUtil.encode(PASSWORD));
        return u;
    }

    /** 验证码登录：先发码再验码，保证打到的是「验码通过后的封禁判定」而非发码/验码分支 */
    private Map<String, Object> codeLogin() {
        service.sendCode(PHONE);
        return service.login(PHONE, DEV_CODE, null);
    }

    /** ① banned 用户验证码登录：403「账号已被封禁」，零签发 */
    @Test
    void banned用户验证码登录403且不签发token() {
        when(userMapper.selectOne(any())).thenReturn(user(User.STATUS_BANNED));

        BizException ex = assertThrows(BizException.class, this::codeLogin);

        assertEquals(R.CODE_FORBIDDEN, ex.getCode());
        assertEquals("账号已被封禁", ex.getMessage());
        verify(jwtUtil, never()).createToken(anyLong());
    }

    /** ② banned 用户密码登录：与验证码同 403/同文案，零签发 */
    @Test
    void banned用户密码登录403且不签发token() {
        when(userMapper.selectOne(any())).thenReturn(passwordUser(User.STATUS_BANNED));

        BizException ex = assertThrows(BizException.class, () -> service.loginPassword(PHONE, PASSWORD));

        assertEquals(R.CODE_FORBIDDEN, ex.getCode());
        assertEquals("账号已被封禁", ex.getMessage());
        verify(jwtUtil, never()).createToken(anyLong());
    }

    /** ③ active 用户两路照常签发 */
    @Test
    void active用户验证码与密码两路均正常签发() {
        when(userMapper.selectOne(any())).thenReturn(user(User.STATUS_ACTIVE));
        Map<String, Object> byCode = codeLogin();
        assertEquals(TOKEN, byCode.get("token"));

        when(userMapper.selectOne(any())).thenReturn(passwordUser(User.STATUS_ACTIVE));
        Map<String, Object> byPassword = service.loginPassword(PHONE, PASSWORD);
        assertEquals(TOKEN, byPassword.get("token"));

        verify(jwtUtil, times(2)).createToken(USER_ID);
    }

    /** ④ status=null（迁移前存量语义）按正常处理：两路均不拦、正常签发 */
    @Test
    void status为null按正常处理() {
        when(userMapper.selectOne(any())).thenReturn(user(null));
        Map<String, Object> byCode = codeLogin();
        assertNotNull(byCode.get("token"));

        when(userMapper.selectOne(any())).thenReturn(passwordUser(null));
        Map<String, Object> byPassword = service.loginPassword(PHONE, PASSWORD);
        assertEquals(TOKEN, byPassword.get("token"));

        verify(jwtUtil, times(2)).createToken(USER_ID);
    }
}
