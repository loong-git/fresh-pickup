package com.fresh.common;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * JWT 工具（T-M2-02）：HS256 签发/解析，secret 与有效期走配置（jwt.secret / jwt.expire-days）。
 * 契约 M2-2：Header 形如 Authorization: Bearer &lt;token&gt;，有效期 7 天。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-days:7}") int expireDays) {
        try {
            // jjwt 对 HS256 要求密钥 ≥ 256bit；对任意长度配置 secret 做 SHA-256 派生成定长 32 字节，
            // 兼容 dev 的 "dev-secret-please-change"（24 字节）等短值，避免 WeakKeyException
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JWT 密钥派生失败", e);
        }
        this.expireMillis = expireDays * 24L * 3600L * 1000L;
    }

    /** 签发以 userId 为主题的 token，有效期 jwt.expire-days 天 */
    public String createToken(Long userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析 token → userId；无/伪造/过期/格式错一律返回 null（由 AuthInterceptor 统一 401，不区分原因） */
    public Long parseUserId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Long.valueOf(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析 token → 完整 Claims（F-12/G-01：供 AuthInterceptor 读 typ/role claim 做双向隔离与查库定角色前置判断）；
     * 无/伪造/过期/格式错一律返回 null（与 parseUserId 同口径，由调用方统一 401）。
     * 原 parseUserId 仅读 subject（:51-63），typ/role claim 无读取通道——本重载补齐，不影响存量链路。
     */
    public io.jsonwebtoken.Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 签发运营（admin）token（F-20/G-06 Step 2，D-6）：subject={@code admin_user.id} +
     * {@code typ=admin} + {@code role=admin} + {@code uname=<username>}。
     * 有效期与签名密钥<b>沿用本类既有配置</b>（{@code expireMillis} / {@code signWith(key)}），与 C 端同参，不新增键。
     *
     * <p><b>本方法只加不改</b>：{@link #createToken(Long)} 与 {@link #parseUserId(String)} 一字未动（D-6/C 端链路零影响）。
     *
     * <p><b>为什么必须带 typ 而不能只靠 subject</b>：{@code user.id} 与 {@code admin_user.id} 都是 BIGINT 自增、
     * 从 1 起，小 id 必然并存——只看 subject 等于让 C 端 token 冒充同数值运营账号（07 册 G-06 范围⑧写死的理由，D-6）。
     * 所以判定侧永远是三件套：{@code typ=admin} claim + 查 {@code admin_user} 存在 + {@code status=active}
     * （读取侧见 {@code AdminController#activeAdminId}），反向隔离见 {@code AuthInterceptor#preHandle}（typ=admin 一律拒那一段）。
     *
     * <p>{@code uname} 只是给人看的可读名（排障/界面），<b>不参与任何权限判定</b>；
     * {@code role=admin} 是给未来「按角色授权」留的位，本期判定不读它（判定只认 typ + 查库）。
     * 本方法不接收也不写入口令/哈希/密钥：token 本身只进响应体，绝不落库、不落日志（RD）。
     */
    public String createAdminToken(Long adminUserId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(adminUserId))
                .claim("typ", "admin")
                .claim("role", "admin")
                .claim("uname", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }
}
