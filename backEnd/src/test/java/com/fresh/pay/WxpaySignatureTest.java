package com.fresh.pay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付签名 / 验签单测（F-21.6 Step 2b 第 2、3 条）：纯内存、零容器、零网络。
 *
 * 密钥一律**运行时自造**（{@link KeyPairGenerator} 2048 位 RSA 两对：一对扮商户 API 证书、
 * 一对扮微信支付平台证书），**不在 src/test/resources 落任何 PEM/密钥文件**——
 * 这条就地推翻上一稿的 {@code wxpay-test/} 目录方案（D-21-3 修订：仓库里连"测试用凭据"都不该出现）。
 *
 * 钉住的四件事：
 * ① 签名串形状：验签串 {@code timestamp\n nonce\n body\n}、请求串 {@code method\n url\n timestamp\n nonce\n body\n}
 *    ——尾随换行是签名的一部分（官方明文），断言写死到字面量，将来谁改了分隔符立刻红；
 * ② 自签自验闭环（D-21-5「本地私钥验自己的签名串」）：商户私钥签的请求串用商户公钥验得过、
 *    平台私钥签的回调串用平台公钥验得过——这就是"验签到底验了什么"可 grep 的全部内容；
 * ③ 三条失败路径一律**验不过**（不是抛异常）：body 被动一个字符、换了公钥（商户↔平台互不通用）、
 *    签名头不是合法 Base64 / 公钥为 null；
 * ④ 同密钥同报文签名可复现（SHA256withRSA PKCS#1 v1.5 确定性），且产物是 Base64（直接进头）。
 */
class WxpaySignatureTest {

    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "abcdef123456";
    private static final String BODY = "{\"id\":\"test-notify-1\",\"resource\":{\"ciphertext\":\"x\"}}";

    private static KeyPair merchantKeyPair;
    private static KeyPair platformKeyPair;

    @BeforeAll
    static void generateKeyPairsAtRuntime() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchantKeyPair = generator.generateKeyPair();
        platformKeyPair = generator.generateKeyPair();
    }

    // —— ① 签名串形状 ——

    @Test
    void verifyMessageIsTimestampNonceBodyEachWithTrailingNewline() {
        assertEquals(TIMESTAMP + "\n" + NONCE + "\n" + BODY + "\n",
                WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY));
        // 字面量再钉一次：三行、每行行尾各一个 \n，不多不少（少一个换行就是验签失败）
        assertEquals("1700000000\nabcdef123456\n" + BODY + "\n",
                WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY));
    }

    @Test
    void emptyBodyKeepsItsTrailingNewline() {
        // GET /v3/certificates 这类无体请求：末尾两个连续换行是官方明文，不能被 trim 掉
        assertEquals("t\nn\n\n", WxpaySignature.buildVerifyMessage("t", "n", ""));
        assertEquals("t\nn\n\n", WxpaySignature.buildVerifyMessage("t", "n", null));
    }

    @Test
    void requestMessageIsMethodUrlTimestampNonceBody() {
        assertEquals("POST\n/v3/pay/transactions/jsapi\n" + TIMESTAMP + "\n" + NONCE + "\n{\"a\":1}\n",
                WxpaySignature.buildRequestMessage("post", "/v3/pay/transactions/jsapi", TIMESTAMP, NONCE, "{\"a\":1}"));
        assertEquals("GET\n/v3/certificates\n1\n2\n\n",
                WxpaySignature.buildRequestMessage(" GET ", "/v3/certificates", "1", "2", ""));
    }

    // —— ② 自签自验闭环 ——

    @Test
    void notificationSignedByPlatformKeyVerifiesWithPlatformPublicKey() {
        String message = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY);
        String signature = WxpaySignature.sign(message, platformKeyPair.getPrivate());

        assertTrue(WxpaySignature.verify(message, signature, platformKeyPair.getPublic()),
                "平台私钥签的回调串必须能被平台公钥验过（否则将来所有真通知都进不来）");
    }

    @Test
    void requestSignedByMerchantKeyVerifiesWithMerchantPublicKey() {
        String message = WxpaySignature.buildRequestMessage(
                "POST", "/v3/pay/transactions/jsapi", TIMESTAMP, NONCE, BODY);
        String signature = WxpaySignature.sign(message, merchantKeyPair.getPrivate());

        assertTrue(WxpaySignature.verify(message, signature, merchantKeyPair.getPublic()),
                "下单侧（D-21-5）：本地私钥能验自己的签名串，不发网络请求也闭环");
    }

    @Test
    void signatureIsDeterministicBase64OfItsMessage() {
        String message = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY);
        String first = WxpaySignature.sign(message, platformKeyPair.getPrivate());
        String second = WxpaySignature.sign(message, platformKeyPair.getPrivate());

        assertEquals(first, second, "SHA256withRSA(PKCS#1 v1.5) 无随机数：同密钥同串必须同签名，否则测试不可复现");
        assertNotEquals(first, WxpaySignature.sign(message + " ", platformKeyPair.getPrivate()),
                "签名串差一个空格就是另一条报文");
        // 产物是 Base64（原样进 Wechatpay-Signature / Authorization 头，不加前缀引号），
        // 且解码后 256 字节 = 2048 位 RSA 的签名长度（不是截断的短串）
        assertDoesNotThrow(() -> Base64.getDecoder().decode(first));
        assertEquals(256, Base64.getDecoder().decode(first).length);
    }

    // —— ③ 失败路径：一律 false，绝不抛 ——

    @Test
    void oneCharacterChangeInBodyBreaksVerification() {
        String message = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY);
        String signature = WxpaySignature.sign(message, platformKeyPair.getPrivate());
        // 报文里多个空格（真实攻击面：中间件重排 JSON 空白）→ 验签名串变了 → 拒
        String tampered = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY.replace("\"id\"", "\"id\" "));

        assertFalse(WxpaySignature.verify(tampered, signature, platformKeyPair.getPublic()));
    }

    @Test
    void merchantAndPlatformKeysAreNotInterchangeable() {
        String message = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY);
        // 商户私钥签的东西，平台公钥验不过——反之亦然：验签认的是「平台证书」这把钥匙，不是任意一把
        String signedByMerchant = WxpaySignature.sign(message, merchantKeyPair.getPrivate());

        assertFalse(WxpaySignature.verify(message, signedByMerchant, platformKeyPair.getPublic()),
                "冒充者拿自己的密钥签也必须验不过（这就是回调鉴权的全部依据）");
    }

    @Test
    void malformedSignatureOrMissingKeyIsRejectedWithoutThrowing() {
        String message = WxpaySignature.buildVerifyMessage(TIMESTAMP, NONCE, BODY);

        assertFalse(WxpaySignature.verify(message, "@@@不是 Base64@@@", platformKeyPair.getPublic()));
        assertFalse(WxpaySignature.verify(message, "", platformKeyPair.getPublic()));
        assertFalse(WxpaySignature.verify(message, Base64.getEncoder().encodeToString(new byte[64]), platformKeyPair.getPublic()));
        assertFalse(WxpaySignature.verify(message, null, platformKeyPair.getPublic()));
        assertFalse(WxpaySignature.verify(message, WxpaySignature.sign(message, platformKeyPair.getPrivate()), null));
        assertFalse(WxpaySignature.verify(message + "\n", WxpaySignature.sign(message, platformKeyPair.getPrivate()),
                platformKeyPair.getPublic()));
    }

    @Test
    void algorithmLiteralIsSha256WithRsaAndSchemaMatchesOfficial() {
        assertEquals("SHA256withRSA", WxpaySignature.SIGN_ALGORITHM);
        assertEquals("WECHATPAY2-SHA256-RSA2048", WxpaySignature.SCHEMA);
    }
}
