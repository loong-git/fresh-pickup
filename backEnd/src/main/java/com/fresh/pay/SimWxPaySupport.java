package com.fresh.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟微信支付支撑件（P10-5 轨道 B / D2）：运行时生成 RSA 密钥对扮「模拟平台证书 + 商户 API 证书」，
 * 姿势照测试先例 {@code WxPayNotifyTest}（密钥一律运行时自造，仓库与磁盘不落任何 PEM），本类把它带进产品侧——
 * 模拟轨的回调不再是测试代码里的一次性构造，而是由 sim-pay 端点在真实运行时以「微信服务器」身份签发。
 *
 * <h3>两对密钥各扮什么（与 {@code WxPayNotifyTest} 的角色分工同构）</h3>
 * <ul>
 *   <li><b>平台证书对</b>：私钥签 wx-notify 回调（{@link #buildPaidNotify}），公钥经
 *       {@link SimCertificateSource} 供 {@link WxPayNotifyService#accept} 按平台侧流程验签——
 *       模拟回调因此要过「真实验签」这一关，与真实微信回调唯一的差别只是证书是自己发的；</li>
 *   <li><b>商户 API 证书对</b>：私钥签 sim-create 的模拟 jsapi 下单五参数（{@link #buildJsapiParams}，
 *       签名串照 {@link WxpaySignature#buildRequestMessage} 官方口径），公钥暴露出来供测试核对签名——
 *       真实轨里这对私钥对应 apiclient_key.pem（凭据到手后接入，见 WxPayCertificateSource 的 TODO）。</li>
 * </ul>
 *
 * <h3>密钥生命周期：懒生成（double-checked on volatile）</h3>
 * 本类是普通 {@code @Component}（不按 sim-enabled 条件装配，供 {@link SimCertificateSource} 稳定注入），
 * 但 RSA 2048 密钥对生成有毫秒级开销——prod（sim-enabled 恒缺省）一辈子用不上它，故首次真正用到才生成，
 * 不给 prod 启动路径添成本；生成后进程内复用，重启即换新（模拟证书本来就不需要跨进程稳定）。
 *
 * <h3>APIv3 密钥与 mchid：读的就是 wx-notify 用的那两枚键</h3>
 * 回调 resource 的 AES-256-GCM 加密 key 读 {@code wxpay.api-v3-key}（与 PayController 的
 * {@code wxpayApiV3Key} 同一枚键——加密与解密必须同 key，GCM tag 校验才过得去）；业务报文 mchid 读
 * {@code wxpay.mchid}。两键在 dev yml 给的是<b>模拟凭据</b>（登记见 application-dev.yml），空值/长度不对时
 * {@link #buildPaidNotify} 直接抛 BizException（fail-fast），不让一笔注定过不了解密闸门的回调发出去。
 *
 * <h3>零新依赖</h3>
 * 签名/验签只走 {@link WxpaySignature}（JDK Signature）、加解密只走 {@link WxpayAead}（javax.crypto）、
 * HTTP 由调用方 {@code SimWxPayService} 用 JDK {@code java.net.http.HttpClient}——红线不引 SDK。
 */
@Slf4j
@Component
public class SimWxPaySupport {

    /**
     * 模拟平台证书序列号：{@link SimCertificateSource} 只认它。
     * 带 SIM- 前缀的形态真实微信序列号（40 位十六进制）不可能撞上，天然防「真实回调被模拟证书误验」。
     */
    public static final String SIM_SERIAL = "SIM-PLATFORM-CERT-SERIAL-0001";

    /** 回调 resource 的 AAD：交易通知恒为 {@code transaction}（官方口径，accept 侧解密用同值） */
    private static final String NOTIFY_AAD = "transaction";
    /** 模拟 jsapi 下单 package 形态：prepay_id=sim_prepay_xxx（真实微信即 prepay_id=wx...，前缀换掉防误认真单号） */
    private static final String SIM_PREPAY_ID_PREFIX = "sim_prepay_";
    /** 模拟交易单号前缀：进回调业务报文的 transaction_id 字段（accept 只留痕不比对，见 :240 注释） */
    private static final String SIM_TRANSACTION_ID_PREFIX = "SIM";
    /** 随机串字符集（小写十六进制形态，官方 nonce 即十六进制风格） */
    private static final String NONCE_ALPHABET = "0123456789abcdef";
    /** AES-GCM IV 必须恰 12 字节（WxpayAead 硬校验），resource.nonce 生成即按 12 位字符 */
    private static final int RESOURCE_NONCE_LENGTH = 12;
    /** jsapi 五参数里的 nonceStr：官方 32 位字符串 */
    private static final int JSAPI_NONCE_LENGTH = 32;
    /** APIv3 密钥字节长度（与 WxpayAead 的 KEY_LENGTH_BYTE 同值，此处独立常量防依赖私有符号） */
    private static final int API_V3_KEY_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** APIv3 密钥：与 PayController#wxpayApiV3Key 读同一枚键（回调加密与 wx-notify 解密必须同 key） */
    @Value("${wxpay.api-v3-key:}")
    private String apiV3Key;

    /** 商户号：回调业务报文 mchid 字段（accept 侧只做非空闸门，比对的是 out_trade_no） */
    @Value("${wxpay.mchid:}")
    private String wxpayMchid;

    /** 扮「平台证书」：私钥签回调、公钥由 SimCertificateSource 供验签（volatile + 懒生成） */
    private volatile KeyPair platformKeyPair;
    /** 扮「商户 API 证书」：私钥签模拟 jsapi 五参数（volatile + 懒生成） */
    private volatile KeyPair merchantKeyPair;

    /**
     * 构造 wx-notify 的完整 v3 回调（D2 的核心）：业务报文（解密层）→ APIv3 密钥 AES-256-GCM 加密进
     * {@code resource.ciphertext} → 外层通知报文 → 平台私钥签「{@code timestamp \n nonce \n body \n}」。
     * 产物四头一体（body 与签名串用的 body 逐字同串），由 {@code SimWxPayService} 原样 POST 本机 wx-notify——
     * 验签、时间戳新鲜度、解密、三比对全部走 {@link WxPayNotifyService#accept} 的真实链路，此处一行判定都不做。
     *
     * @param orderId   订单号（= orders.id，进 out_trade_no 与 wx-notify 路径，accept 会做两者比对）
     * @param amountFen 应付金额分（调用方按 {@code totalPrice.movePointRight(2)} 口径算出，accept 比对它）
     * @return 可直接投递的一条回调（body + 四头）
     * @throws BizException APIv3 密钥不是 32 字节（配置错，fail-fast，不发注定被拒的回调）
     */
    public SimCallback buildPaidNotify(String orderId, int amountFen) {
        KeyPair platform = ensurePlatformKeys();
        String apiV3Key = this.apiV3Key;
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != API_V3_KEY_BYTES) {
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付未配置：wxpay.api-v3-key 须为 32 位字符串");
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String resourceNonce = randomChars(RESOURCE_NONCE_LENGTH);

        // 业务报文（解密后的那一层）：字段形状照官方 TRANSACTION.SUCCESS 的 transaction 实体，
        // WxPayNotifyService 读的只有 out_trade_no / trade_state / amount.total / success_time 四处
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("mchid", wxpayMchid == null ? "" : wxpayMchid);
        transaction.put("appid", "wx-sim-appid");
        transaction.put("out_trade_no", orderId);
        transaction.put("transaction_id", SIM_TRANSACTION_ID_PREFIX + timestamp + randomChars(10));
        transaction.put("trade_state", "SUCCESS");
        // success_time：渠道权威时刻（C-4），+08:00 偏移形态是 accept 实测支持的一种
        transaction.put("success_time",
                OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", amountFen);
        amount.put("payer_total", amountFen);
        amount.put("currency", "CNY");
        transaction.put("amount", amount);
        transaction.put("payer", Map.of("openid", "o-sim-openid"));

        // 加密 + 外层报文 + 平台私钥签名（签名串用原始 body 串，与 POST 出去的逐字同串）
        try {
            String transactionJson = MAPPER.writeValueAsString(transaction);
            String ciphertext = WxpayAead.encrypt(apiV3Key, resourceNonce, NOTIFY_AAD, transactionJson);
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("original_type", "transaction");
            resource.put("algorithm", WxpayAead.ALGORITHM);
            resource.put("ciphertext", ciphertext);
            resource.put("associated_data", NOTIFY_AAD);
            resource.put("nonce", resourceNonce);
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("id", "sim-notify-" + orderId);
            bodyMap.put("create_time", OffsetDateTime.now(ZoneOffset.ofHours(8))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            bodyMap.put("event_type", "TRANSACTION.SUCCESS");
            bodyMap.put("resource_type", "transaction");
            bodyMap.put("summary", "sim payment success");
            bodyMap.put("resource", resource);
            String body = MAPPER.writeValueAsString(bodyMap);
            String signature = WxpaySignature.sign(
                    WxpaySignature.buildVerifyMessage(timestamp, resourceNonce, body), platform.getPrivate());
            return new SimCallback(body, timestamp, resourceNonce, signature, SIM_SERIAL);
        } catch (GeneralSecurityException e) {
            // 算法/密钥不可用属环境级故障：换不成 400（不是报文问题），照 settle 失败同思路给人话 + 留痕
            log.error("模拟回调构造失败（AES-GCM）: orderId={}, cause={}", orderId, e.getClass().getSimpleName());
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付回调构造失败，请稍后重试");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("模拟回调报文序列化失败: orderId={}", orderId);
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付回调构造失败，请稍后重试");
        }
    }

    /**
     * 构造模拟 jsapi 下单五参数（sim-create 返回体）：timeStamp / nonceStr / package / signType / paySign，
     * 外加 amountFen 供前端与收银台展示金额对账（防串单）。paySign 照官方请求签名口径——
     * {@link WxpaySignature#buildRequestMessage}("POST", "/v3/pay/transactions/jsapi", …) 后用商户私钥签。
     * 模拟轨不产生真实 prepay_id，package 里的单号带 sim 前缀防与真实微信单号混淆。
     */
    public Map<String, Object> buildJsapiParams(String orderId, int amountFen) {
        KeyPair merchant = ensureMerchantKeys();
        String timeStamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = randomChars(JSAPI_NONCE_LENGTH);
        String prepayId = SIM_PREPAY_ID_PREFIX + orderId;
        String packageValue = "prepay_id=" + prepayId;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("appid", "wx-sim-appid");
        request.put("mchid", wxpayMchid == null ? "" : wxpayMchid);
        request.put("out_trade_no", orderId);
        request.put("prepay_id", prepayId);
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", amountFen);
        amount.put("currency", "CNY");
        request.put("amount", amount);
        String requestBody;
        try {
            requestBody = MAPPER.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付参数构造失败，请稍后重试");
        }
        String paySign = WxpaySignature.sign(
                WxpaySignature.buildRequestMessage("POST", "/v3/pay/transactions/jsapi", timeStamp, nonceStr, requestBody),
                merchant.getPrivate());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("timeStamp", timeStamp);
        params.put("nonceStr", nonceStr);
        params.put("package", packageValue);
        params.put("signType", "RSA");
        params.put("paySign", paySign);
        params.put("amountFen", amountFen);
        return params;
    }

    /** 模拟平台证书公钥（SimCertificateSource 按序列号取的正是它） */
    public PublicKey platformPublicKey() {
        return ensurePlatformKeys().getPublic();
    }

    /** 商户 API 证书公钥（测试核对 paySign 用；真实轨对应 apiclient_cert.pem 的公钥） */
    public PublicKey merchantPublicKey() {
        return ensureMerchantKeys().getPublic();
    }

    /** 序列号是否本模拟证书的（SimCertificateSource 的认证书逻辑，未知序列号由它抛） */
    public boolean isSimSerial(String serial) {
        return SIM_SERIAL.equals(serial);
    }

    /** 平台密钥对懒生成（double-checked on volatile：RSA 2048 有开销，prod 用不到就不付这个成本） */
    private KeyPair ensurePlatformKeys() {
        KeyPair pair = platformKeyPair;
        if (pair == null) {
            synchronized (this) {
                if (platformKeyPair == null) {
                    platformKeyPair = generateRsaKeyPair();
                    log.info("模拟平台证书密钥对已生成（运行时，serial={}）", SIM_SERIAL);
                }
                pair = platformKeyPair;
            }
        }
        return pair;
    }

    /** 商户密钥对懒生成（同上） */
    private KeyPair ensureMerchantKeys() {
        KeyPair pair = merchantKeyPair;
        if (pair == null) {
            synchronized (this) {
                if (merchantKeyPair == null) {
                    merchantKeyPair = generateRsaKeyPair();
                    log.info("模拟商户 API 证书密钥对已生成（运行时）");
                }
                pair = merchantKeyPair;
            }
        }
        return pair;
    }

    /** RSA 2048 密钥对生成（照 WxPayNotifyTest#generateKeysAndPrimeMybatisPlusColumnCache 的参数） */
    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            // JDK 无 RSA = 环境级故障，直接硬失败（签名体系整个不可用，没有降级可言）
            throw new IllegalStateException("JDK 缺少 RSA 算法，无法生成模拟支付密钥对", e);
        }
    }

    /** SecureRandom 小写十六进制随机串（回调 resource.nonce 与 jsapi nonceStr 共用） */
    private static String randomChars(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NONCE_ALPHABET.charAt(SECURE_RANDOM.nextInt(NONCE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 一条可直接投递的 wx-notify 回调：body 与签名串同源（{@code buildPaidNotify} 内一次成型），
     * 四个头逐字对应 {@code PayController#wxNotify} 的四个 {@code @RequestHeader} 入参。
     */
    public record SimCallback(String body, String timestamp, String nonce, String signature, String serial) {
    }
}
