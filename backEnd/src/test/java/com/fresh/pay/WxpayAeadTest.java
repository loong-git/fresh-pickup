package com.fresh.pay;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APIv3 密钥 AES-256-GCM 加解密单测（F-21.6 Step 2b 第 2、3 条）：加解密成对实现正是为了让
 * 「错 key 解不开」成为可钉死的断言——GCM 的 tag 校验就是「报文没被动过」的证明。
 *
 * 密钥是**固定测试常量**（32 位 ASCII，只存在于本测试文件里，不落 src/test/resources 任何文件），
 * 与运行时自造的 RSA 密钥对同性质：仓里没有一条真凭据。
 *
 * 钉住的六件事：
 * ① round-trip 逐字相等（含中文/JSON 转义，UTF-8 两端一致）；
 * ② 密文 ≠ 明文、Base64 可解、且比明文多 **16 字节**（128 位 tag 附在尾部——官方格式）；
 * ③ 错 APIv3 密钥 → {@code AEADBadTagException}（微信回调解不开就是这一条，回调侧必须 4xx/5xx）；
 * ④ 密文 / nonce / associated_data(AAD) 任一被动一个比特 → 同样解不开（AAD 参与认证，
 *    这就是官方为什么要在 AAD 里放 {@code transaction}）；
 * ⑤ 参数长度闸门：key 必须 32 字节、nonce 必须 12 字节（不截断不补齐不派生，长度错就硬失败）；
 * ⑥ algorithm 字面值 {@code AEAD_AES_256_GCM}（解密前先比对，防止换了算法还按 AES-256 解）。
 */
class WxpayAeadTest {

    /** 32 位 APIv3 密钥（测试常量，非任何环境用过的值） */
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    /** 另一把 32 位密钥：用来扮演「APIv3 密钥配错了 / 对不上商户」 */
    private static final String OTHER_API_V3_KEY = "fedcba9876543210fedcba9876543210";
    /** 12 字节 nonce（微信 resource.nonce 的固定长度） */
    private static final String NONCE = "abc123def456";
    /** 交易通知的 AAD 字面值 */
    private static final String AAD = "transaction";
    private static final String PLAIN = "{\"out_trade_no\":\"2104094865859330050\",\"trade_state\":\"SUCCESS\","
            + "\"amount\":{\"total\":880,\"currency\":\"CNY\"},\"payer\":{\"openid\":\"测试openid\"}}";

    // —— ① round-trip ——

    @Test
    void decryptOfOwnCiphertextReturnsExactPlainText() throws Exception {
        String cipher = WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN);

        assertEquals(PLAIN, WxpayAead.decrypt(API_V3_KEY, NONCE, AAD, cipher), "UTF-8 两端一致：中文与 JSON 转义逐字回原");
    }

    @Test
    void emptyAssociatedDataIsSupported() throws Exception {
        String cipher = WxpayAead.encrypt(API_V3_KEY, NONCE, "", PLAIN);

        assertEquals(PLAIN, WxpayAead.decrypt(API_V3_KEY, NONCE, "", cipher));
        assertEquals(PLAIN, WxpayAead.decrypt(API_V3_KEY, NONCE, null, cipher));
    }

    // —— ② 密文形态 ——

    @Test
    void ciphertextIsBase64AndCarriesA128BitTagAtTheTail() throws Exception {
        String cipher = WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN);
        byte[] raw = Base64.getDecoder().decode(cipher);

        assertNotEquals(PLAIN, cipher, "密文绝不能长得像明文");
        assertFalse(cipher.contains("out_trade_no"), "明文片段不得漏进 Base64 串（GCM 是流加密，漏了就是没加密）");
        assertEquals(PLAIN.getBytes(StandardCharsets.UTF_8).length + 16, raw.length,
                "128 位 tag = 16 字节，附在密文尾部（与微信 ciphertext 同形）");
    }

    // —— ③ 错 key ——

    @Test
    void wrongApiV3KeyCannotDecrypt() throws Exception {
        String cipher = WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN);

        // GCM 认证失败：解不开就是解不开，不给半个字节明文（回调侧据此拒收）
        assertThrows(AEADBadTagException.class, () -> WxpayAead.decrypt(OTHER_API_V3_KEY, NONCE, AAD, cipher));
    }

    // —— ④ 篡改（密文 / nonce / AAD 三个方向） ——

    @Test
    void flippedCipherByteIsDetected() throws Exception {
        byte[] raw = Base64.getDecoder().decode(WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN));
        raw[0] ^= 0x01;   // 只改一个比特
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThrows(AEADBadTagException.class, () -> WxpayAead.decrypt(API_V3_KEY, NONCE, AAD, tampered));
    }

    @Test
    void changedNonceOrAadIsDetected() throws Exception {
        String cipher = WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN);

        assertThrows(AEADBadTagException.class, () -> WxpayAead.decrypt(API_V3_KEY, "abc123def457", AAD, cipher));
        assertThrows(AEADBadTagException.class, () -> WxpayAead.decrypt(API_V3_KEY, NONCE, "refund", cipher));
    }

    @Test
    void truncatingTheTagIsDetected() throws Exception {
        byte[] raw = Base64.getDecoder().decode(WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN));
        byte[] withoutTag = new byte[raw.length - 16];
        System.arraycopy(raw, 0, withoutTag, 0, withoutTag.length);

        assertThrows(Exception.class, () -> WxpayAead.decrypt(API_V3_KEY, NONCE, AAD,
                Base64.getEncoder().encodeToString(withoutTag)), "砍掉 tag 必须失败，不能当明文吐出去");
    }

    // —— ⑤ 长度闸门 ——

    @Test
    void keyMustBeExactly32BytesAndNonceExactly12Bytes() {
        assertEquals(32, API_V3_KEY.getBytes(StandardCharsets.UTF_8).length, "测试常量本身就是 32 字节");
        assertEquals(12, NONCE.getBytes(StandardCharsets.UTF_8).length);

        IllegalArgumentException shortKey = assertThrows(IllegalArgumentException.class,
                () -> WxpayAead.encrypt(API_V3_KEY.substring(0, 31), NONCE, AAD, PLAIN));
        assertTrue(shortKey.getMessage().contains("32"), "报错要说清是长度问题：" + shortKey.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> WxpayAead.encrypt(API_V3_KEY + "x", NONCE, AAD, PLAIN));
        // 32 个中文字符 = 96 字节：按 UTF-8 字节数算，不是按 char 数（真密钥只可能是 ASCII）
        assertThrows(IllegalArgumentException.class,
                () -> WxpayAead.encrypt("微信支付接口密钥测试".repeat(4), NONCE, AAD, PLAIN));
        assertThrows(IllegalArgumentException.class, () -> WxpayAead.encrypt(null, NONCE, AAD, PLAIN));

        assertThrows(IllegalArgumentException.class, () -> WxpayAead.encrypt(API_V3_KEY, "short", AAD, PLAIN));
        assertThrows(IllegalArgumentException.class, () -> WxpayAead.encrypt(API_V3_KEY, null, AAD, PLAIN));
        assertThrows(IllegalArgumentException.class, () -> WxpayAead.decrypt(API_V3_KEY, NONCE + "x", AAD, "AAAAAAAAAAAAAAAAAAAAAA=="));
    }

    @Test
    void cipherTextMustBeBase64() {
        assertThrows(IllegalArgumentException.class,
                () -> WxpayAead.decrypt(API_V3_KEY, NONCE, AAD, "@@@不是 Base64@@@"));
    }

    // —— ⑥ 官方字面值 ——

    @Test
    void algorithmLiteralMatchesOfficialResourceAlgorithm() {
        assertEquals("AEAD_AES_256_GCM", WxpayAead.ALGORITHM);
    }

    @Test
    void sameKeySameNonceEncryptsDeterministically() throws Exception {
        // IV 由调用方给（微信 resource.nonce 给什么就用什么），商户侧从不自主生成 nonce，
        // 故 encrypt 对同参可复现（测试里造密文要的就是这一点）；不同明文必然不同密文。
        assertEquals(WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN), WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN));
        assertNotEquals(WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN + "x"),
                WxpayAead.encrypt(API_V3_KEY, NONCE, AAD, PLAIN));
    }
}
