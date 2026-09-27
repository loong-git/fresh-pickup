package com.fresh.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码哈希工具（T-M5）：JDK 自带 PBKDF2WithHmacSHA256，不引入新依赖。
 * 存储格式 base64(salt):base64(hash)，落 user.password_hash（VARCHAR(128)，现格式约 69 字符）。
 * encode 每次随机 16 字节盐（同明文不同编码）；verify 用 MessageDigest.isEqual 恒时比较，
 * 防 dump 库离线爆破与验密时序侧信道。
 */
public final class PasswordUtil {

    /** PBKDF2 迭代次数：单次验密约百毫秒级，防离线爆破与登录延迟的折中 */
    private static final int ITERATIONS = 120_000;
    /** 随机盐长度（字节） */
    private static final int SALT_BYTES = 16;
    /** 派生密钥长度（位）：256bit → base64 后 44 字符 */
    private static final int KEY_BITS = 256;
    /** 算法名：SunJCE 自带，无需 Provider */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 存储串分隔符：base64 标准字母表不含 ':'，可安全用 indexOf 切分 */
    private static final char SEPARATOR = ':';

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    /** 明文 → "base64(salt):base64(hash)"；盐随机，同一明文两次编码结果不同 */
    public static String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt);
        return Base64.getEncoder().encodeToString(salt) + SEPARATOR + Base64.getEncoder().encodeToString(hash);
    }

    /** 明文与存储串是否匹配；存储串格式非法一律判不匹配（不给攻击方格式探测信号） */
    public static boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        int sep = stored.indexOf(SEPARATOR);
        if (sep <= 0) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(stored.substring(0, sep));
            byte[] expected = Base64.getDecoder().decode(stored.substring(sep + 1));
            return MessageDigest.isEqual(pbkdf2(rawPassword, salt), expected);
        } catch (IllegalArgumentException e) {
            // base64 非法字符：视为哈希不匹配
            return false;
        }
    }

    /** PBKDF2 派生：明文 + 盐 → 定长哈希；用后清除明文副本 */
    private static byte[] pbkdf2(String rawPassword, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用", e);
        } finally {
            spec.clearPassword();
        }
    }
}
