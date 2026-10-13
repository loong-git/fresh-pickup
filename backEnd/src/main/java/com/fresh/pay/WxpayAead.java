package com.fresh.pay;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 微信支付 APIv3 密钥的 AES-256-GCM 加解密（F-21.6 Step 2b 第 2 条）：
 * 回调报文 {@code resource} 的解密（真用途），以及测试里「扮演微信服务器」造密文（{@link #encrypt}）。
 *
 * 官方参数逐字对账（不做任何自由发挥）：
 * - 算法 {@code AEAD_AES_256_GCM} → JDK 变换串 {@code AES/GCM/NoPadding}；
 * - key = APIv3 密钥的 **UTF-8 字节**（设置时就是 32 位字符串，故恒 32 字节 = AES-256，不做派生/不截断）；
 * - IV = {@code resource.nonce} 的 UTF-8 字节，**长度必须 12**（GCM 标准 nonce 长度，微信给的即是 12 位字符）；
 * - tag = 128 位，且微信的 {@code ciphertext} Base64 解码后**尾部 16 字节就是 tag**（JDK GCM 输出同形，
 *   故 encrypt 的产物可直接当 ciphertext 用、解密亦然——加解密成对实现正是为了「错 key 解不开」可测）；
 * - AAD = {@code resource.associated_data}（交易通知恒为 {@code transaction}）；空串不调 updateAAD（等价、少一处歧义）。
 *
 * 零新依赖：只用 {@code javax.crypto}。纯静态无状态，可脱离 Spring 单测（{@code WxpayAeadTest}）。
 */
public final class WxpayAead {

    /** 官方 {@code resource.algorithm} 的字面值：解密前拿去比对，不匹配即拒收（防止报文换了算法还按 AES-256 解） */
    public static final String ALGORITHM = "AEAD_AES_256_GCM";

    private static final String JDK_TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 认证标签位长（官方 128 位 = 16 字节，附在密文尾部） */
    private static final int TAG_LENGTH_BIT = 128;

    /** GCM nonce 字节长度：微信的 resource.nonce 恒 12 位字符 */
    private static final int NONCE_LENGTH_BYTE = 12;

    /** APIv3 密钥字节长度：32 位字符串 → 32 字节（AES-256） */
    private static final int KEY_LENGTH_BYTE = 32;

    private WxpayAead() {
    }

    /**
     * 解密（回调侧）：{@code resource.ciphertext}（Base64）→ 业务 JSON 明文。
     *
     * @param apiV3Key       APIv3 密钥（32 位字符串）
     * @param nonce          {@code resource.nonce}（12 字节）
     * @param associatedData {@code resource.associated_data}（AAD，可为空）
     * @param ciphertext     {@code resource.ciphertext}（Base64，含尾部 16 字节 tag）
     * @return 明文（UTF-8）
     * @throws GeneralSecurityException 密钥算法不可用 / **tag 校验不通过**（错 key、密文被篡改、AAD 不符都落这里，
     *                                  典型为 {@code AEADBadTagException}）——GCM 的完整性校验即「报文没被动过」的证明
     * @throws IllegalArgumentException APIv3 密钥不是 32 字节 / nonce 不是 12 字节 / ciphertext 非合法 Base64
     *                                  （配置或报文形态错误，与解密失败同性质：调用方一律拒收）
     */
    public static String decrypt(String apiV3Key, String nonce, String associatedData, String ciphertext)
            throws GeneralSecurityException {
        byte[] cipherBytes = Base64.getDecoder().decode(ciphertext);
        Cipher cipher = init(Cipher.DECRYPT_MODE, apiV3Key, nonce, associatedData);
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    /**
     * 加密（本期唯一使用方是测试：扮演微信服务器把业务 JSON 灌进 {@code resource.ciphertext}）。
     * 与 {@link #decrypt} 成对，参数含义一致；产物为 Base64（明文 + 尾部 tag）。
     *
     * @throws GeneralSecurityException 算法/密钥不可用
     * @throws IllegalArgumentException 密钥或 nonce 长度不合规
     */
    public static String encrypt(String apiV3Key, String nonce, String associatedData, String plainText)
            throws GeneralSecurityException {
        Cipher cipher = init(Cipher.ENCRYPT_MODE, apiV3Key, nonce, associatedData);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
    }

    /** 两个方向共用的一次性 Cipher 装配：key/IV/AAD 三处口径只此一份，杜绝「加密一套、解密另一套」 */
    private static Cipher init(int mode, String apiV3Key, String nonce, String associatedData)
            throws GeneralSecurityException {
        if (apiV3Key == null) {
            throw new IllegalArgumentException("APIv3 密钥为空");
        }
        byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != KEY_LENGTH_BYTE) {
            // 不截断、不补齐、不派生：长度不对就是配置错，硬失败比「悄悄能用」安全得多
            throw new IllegalArgumentException("APIv3 密钥须为 " + KEY_LENGTH_BYTE + " 字节，实际 " + keyBytes.length);
        }
        if (nonce == null) {
            throw new IllegalArgumentException("resource.nonce 为空");
        }
        byte[] iv = nonce.getBytes(StandardCharsets.UTF_8);
        if (iv.length != NONCE_LENGTH_BYTE) {
            throw new IllegalArgumentException("resource.nonce 须为 " + NONCE_LENGTH_BYTE + " 字节，实际 " + iv.length);
        }
        Cipher cipher = Cipher.getInstance(JDK_TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        if (associatedData != null && !associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        return cipher;
    }
}
