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
}
