package com.fresh.pay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * 微信支付 APIv3 的 RSA 签名 / 验签（F-21.6 Step 2b 第 2 条）。
 *
 * 零新依赖（红线）：只用 JDK 的 {@link java.security.Signature}（{@code SHA256withRSA}）与
 * {@link java.util.Base64}，**不引** wechatpay-java / wechatpay-apache-httpclient 等外部 SDK——
 * 引入即把「到底验了什么」变成不可 grep 的黑盒，且违反本仓不新增依赖的红线（pom.xml 禁改）。
 *
 * 纯静态、无状态：不读配置、不碰网络、不认识 Spring，因此可脱离容器单测
 * （{@code WxpaySignatureTest} 运行时自造两对 RSA 密钥，仓内不落任何 PEM）。
 *
 * 两个签名串构造器对应微信支付的两个方向（口径逐字按官方）：
 * - 回调验签（微信 → 商户，本批主用途）：{@code Wechatpay-Timestamp \n Wechatpay-Nonce \n 报文 body \n}，
 *   用**平台证书公钥**验 {@code Wechatpay-Signature} 头（Base64 后的 SHA256withRSA）；
 * - 请求签名（商户 → 微信，统一下单侧将来用，D-21-5）：
 *   {@code HTTP-Method \n URL(含 query) \n timestamp \n nonce \n body \n}，用**商户 API 证书私钥**签，
 *   结果进 {@code Authorization} 头（schema {@code WECHATPAY2-SHA256-RSA2048}）。本批只实现 + 单测覆盖，不发网络请求。
 *
 * 尾随的 {@code \n} 是签名串的一部分（官方明文），少一个换行就验不过——故两个构造器都把换行写成显式分隔符，
 * 空 body 也保留尾部换行（GET / 无体请求即 {@code ....\n\n}）。
 */
public final class WxpaySignature {

    /** 官方 Authorization 头的 schema 字面值（下单侧拼头用；同时是「签名算法只此一种」的单一真源） */
    public static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    /** JDK Signature 算法名：SHA256 摘要 + RSA（即官方 SHA256-RSA2048） */
    public static final String SIGN_ALGORITHM = "SHA256withRSA";

    private WxpaySignature() {
    }

    /**
     * 回调验签名串（官方三行 + 每行尾换行）：{@code timestamp \n nonce \n body \n}。
     * body 必须是**收到的原始字节串**（不得先解析 JSON 再重新序列化：字段顺序/空白一变即验签失败）。
     */
    public static String buildVerifyMessage(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
    }

    /**
     * 请求签名串（下单侧）：{@code method \n urlPath \n timestamp \n nonce \n body \n}。
     * urlPath 是含 query 的**路径**（如 {@code /v3/pay/transactions/jsapi}），不含 host；
     * method 大写归一（官方要求 GET/POST 大写）。
     */
    public static String buildRequestMessage(String method, String urlPath, String timestamp,
                                             String nonce, String body) {
        return method.trim().toUpperCase() + "\n" + urlPath + "\n"
                + timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
    }

    /**
     * 商户私钥签名（下单侧 TODO 第 2/3 步要用：请求 Authorization 头 + 小程序拉起支付的 paySign）。
     *
     * @param message   签名串（{@link #buildRequestMessage} / {@link #buildVerifyMessage} 产出）
     * @param privateKey 商户 API 证书私钥（apiclient_key.pem 解析所得）
     * @return Base64 编码的签名值（直接进头，不再加引号/前缀）
     * @throws IllegalStateException 私钥不可用 / JDK 无该算法（配置或环境问题，属编程错误，交由调用方打成 5xx）
     */
    public static String sign(String message, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(SIGN_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("微信支付商户签名失败", e);
        }
    }

    /**
     * 平台证书公钥验签。
     * <p><b>失败一律回 {@code false}，不抛异常</b>：签名头不是合法 Base64、签名字节长度不对、公钥为 null
     * 等等都属「这条通知不可信」，与「验签不通过」必须同性质、同处置（回调侧拒收），
     * 否则一条畸形报文会把端点打成异常、把「拒收」这条语义洗成 5xx 之外的形态。
     *
     * @param message    {@link #buildVerifyMessage} 构造的验签名串
     * @param signature  {@code Wechatpay-Signature} 头原文（Base64）
     * @param publicKey  平台证书公钥（{@link WxPayCertificateSource#getPublicKey(String)} 按 serial 取）
     */
    public static boolean verify(String message, String signature, PublicKey publicKey) {
        if (publicKey == null || signature == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // IllegalArgumentException 来自 Base64 解码（头被截断/非 Base64），同属「不可信报文」
            return false;
        }
    }
}
