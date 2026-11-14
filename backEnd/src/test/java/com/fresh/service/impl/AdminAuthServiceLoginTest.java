package com.fresh.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.PasswordUtil;
import com.fresh.common.R;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.AdminUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营口令登录（F-20/G-06 Step 2，{@code AdminAuthServiceImpl#login}）契约单测
 * （不起 Spring 容器、不落库；JwtUtil 用真实实例，验密走真实 PBKDF2）。
 *
 * <p><b>补测动机</b>：P11-2 审计轮 3 P2 缺口 #4——AdminAuthService 此前零直测。要钉的四件事（D-5 实读）：
 * ① 口令不符 → 401 且<b>唯一文案</b>「用户名或密码错误」（失败原因只进服务端日志）；
 * ② 停用账号 / 无此账号 → 同一码同一句（三失败收敛，防枚举）；两个旁路写（last_login_at、admin_audit_log）
 *    在失败分支零发生；
 * ③ 成功 → 签发 typ=admin 运营 JWT（subject=admin_user.id，claims 含 typ=admin/role=admin/uname=用户名），
 *    出参白名单 {token, displayName, adminUserId}（实体零透传，passwordHash 无出口），
 *    审计行 admin.login（actor=admin_user:&lt;id&gt;、ip 落列、detail 只有 username）；
 * ④ 签出的 token 走 {@code JwtUtil#parseClaims} 回环校验通过；篡改任一字符即判 null（HS256 签名闸门）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：HTTP 闸门（双轨/限流）、Caffeine 锁的 TTL 行为、
 * 真 MySQL 的 last_login_at 落库不在证明力内（锁阈值≥5 次的本类用例只失败 1 次，不会触发上锁）。
 */
class AdminAuthServiceLoginTest {

    private static final String PASSWORD = "Sup3rSecret!";
    private static final Long ADMIN_ID = 1L;
    private static final String USERNAME = "ops";
    /** 真实 PBKDF2 哈希，静态惰性一次（单次 encode 约百毫秒，全类共用） */
    private static final String ADMIN_HASH = PasswordUtil.encode(PASSWORD);

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    private final AdminUserMapper adminUserMapper = mock(AdminUserMapper.class);
    private final AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private AdminAuthServiceImpl service;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        service = new AdminAuthServiceImpl();
        jwtUtil = new JwtUtil("unit-test-secret-please-change-0123456789", 7);
        ReflectionTestUtils.setField(service, "adminUserMapper", adminUserMapper);
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", adminAuditLogMapper);
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(service, "trustProxy", false);   // 直连口径：clientIp=remoteAddr
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
    }

    private static AdminUser account(String status) {
        AdminUser u = new AdminUser();
        u.setId(ADMIN_ID);
        u.setUsername(USERNAME);
        u.setPasswordHash(ADMIN_HASH);
        u.setDisplayName("运营小李");
        u.setStatus(status);
        return u;
    }

    private JsonNode readDetail(AdminAuditLog row) {
        try {
            return json.readTree(row.getDetail());
        } catch (Exception e) {
            throw new AssertionError("审计 detail 不是合法 JSON: " + row.getDetail(), e);
        }
    }

    // ---------------------------------------------------------------- ① 口令不符

    @Test
    void 口令不符收敛同一句四百零一且旁路零写() {
        when(adminUserMapper.selectByUsername(USERNAME)).thenReturn(account("active"));

        BizException e = assertThrows(BizException.class, () ->
                service.login(USERNAME, "wrong-password", request));

        assertEquals(R.CODE_UNAUTHORIZED, e.getCode());
        assertEquals("用户名或密码错误", e.getMessage(), "D-5：失败原因只进服务端日志，对外唯一文案");
        verify(adminUserMapper, never()).touchLastLogin(any(), any(LocalDateTime.class));
        verify(adminAuditLogMapper, never()).insert(any(AdminAuditLog.class));
    }

    // ---------------------------------------------------------------- ② 停用/无此账号收敛

    @Test
    void 停用与无此账号收敛同一码同一句() {
        // status=disabled：密码正确也拦（三重判定的 status 一环）
        when(adminUserMapper.selectByUsername(USERNAME)).thenReturn(account("disabled"));
        BizException disabled = assertThrows(BizException.class, () ->
                service.login(USERNAME, PASSWORD, request));
        assertEquals(R.CODE_UNAUTHORIZED, disabled.getCode());
        assertEquals("用户名或密码错误", disabled.getMessage(), "不给「已停用」这种可区分信号（D-5）");

        // 账号不存在：同一码同一句（BE-20-2 时延抹平的 verify 对 DUMMY_HASH 照跑，本单测不测时延）
        when(adminUserMapper.selectByUsername("ghost")).thenReturn(null);
        BizException ghost = assertThrows(BizException.class, () ->
                service.login("ghost", PASSWORD, request));
        assertEquals(R.CODE_UNAUTHORIZED, ghost.getCode());
        assertEquals("用户名或密码错误", ghost.getMessage());

        verify(adminUserMapper, never()).touchLastLogin(any(), any(LocalDateTime.class));
        verify(adminAuditLogMapper, never()).insert(any(AdminAuditLog.class));
    }

    // ---------------------------------------------------------------- ③ 成功签发 + 审计

    @Test
    void 登录成功签发typ_admin的jwt_出参白名单_审计落库() {
        when(adminUserMapper.selectByUsername(USERNAME)).thenReturn(account("active"));

        Map<String, Object> out = service.login(USERNAME, PASSWORD, request);

        // 出参白名单三键（G4/D-5）：实体一个字段都不透传，passwordHash 无出口
        assertEquals(3, out.size(), "出参就 {token, displayName, adminUserId} 三键：out=" + out.keySet());
        assertNotNull(out.get("token"));
        assertEquals("运营小李", out.get("displayName"));
        assertEquals(ADMIN_ID, out.get("adminUserId"));

        // JWT 载荷（D-6）：subject=admin_user.id + typ=admin + role=admin + uname=用户名
        Claims claims = jwtUtil.parseClaims((String) out.get("token"));
        assertNotNull(claims, "token 必须能被同一 JwtUtil 校验通过");
        assertEquals(String.valueOf(ADMIN_ID), claims.getSubject());
        assertEquals("admin", claims.get("typ", String.class));
        assertEquals("admin", claims.get("role", String.class));
        assertEquals(USERNAME, claims.get("uname", String.class));

        // 旁路写 1：last_login_at（成功链路）
        verify(adminUserMapper).touchLastLogin(eq(ADMIN_ID), any(LocalDateTime.class));

        // 旁路写 2：admin_audit_log 的 admin.login（D-5/D-8：Admin 侧动作不写 ops_log）
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogMapper).insert(cap.capture());
        AdminAuditLog row = cap.getValue();
        assertEquals("admin_user:1", row.getActor());
        assertEquals("admin.login", row.getAction());
        assertEquals("admin_user", row.getTargetType());
        assertEquals(String.valueOf(ADMIN_ID), row.getTargetId());
        assertEquals("203.0.113.9", row.getIp(), "客户端 IP 落 ip 列（BE-G6-5：不再进 detail）");
        JsonNode detail = readDetail(row);
        assertEquals(USERNAME, detail.get("username").asText(), "detail 只有 username 一键");
        assertTrue(!row.getDetail().contains(PASSWORD) && !row.getDetail().contains(ADMIN_HASH),
                "审计绝不落口令/哈希（RD）");
    }

    // ---------------------------------------------------------------- ④ 回环与篡改

    @Test
    void 签出token回环校验通过_篡改即判null() {
        when(adminUserMapper.selectByUsername(USERNAME)).thenReturn(account("active"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = service.login(USERNAME, PASSWORD, request);
        String token = (String) out.get("token");

        // 回环：签出的 token 走校验方法通过，subject 数值=运营账号 id
        assertEquals(ADMIN_ID, jwtUtil.parseUserId(token), "parseUserId 读 subject 通过（回环）");
        Claims claims = jwtUtil.parseClaims(token);
        assertNotNull(claims);
        assertEquals("admin", claims.get("typ", String.class), "回环读 typ=admin claim");

        // 篡改：改一个字符（签名不匹配）→ 一律 null（由调用方统一 401，不区分原因）
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? "B" : "A");
        assertNull(jwtUtil.parseClaims(tampered), "篡改 token 必须判 null（HS256 签名闸门）");
        assertNull(jwtUtil.parseClaims(token.substring(0, token.length() - 4)), "截断 token 同样 null");
        assertNull(jwtUtil.parseUserId(tampered));
    }
}
