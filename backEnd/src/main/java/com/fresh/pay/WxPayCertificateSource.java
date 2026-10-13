package com.fresh.pay;

import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * 微信支付「平台证书」公钥来源（F-21.6 Step 2b 第 2 条）：回调验签需要按 {@code Wechatpay-Serial} 头
 * 取到对应的平台证书公钥，这一步要联网下载且涉及证书轮换，故抽象成接口——
 * 验签逻辑（{@link WxpaySignature} + PayController#wxNotify）只依赖本接口，
 * **测试实现用运行时自造的公钥**（{@code WxPayNotifyTest} 里一对 RSA 密钥扮平台证书），真实实现走
 * {@code GET /v3/certificates}。
 *
 * 本期（无商户号）真实实现只是骨架并抛「未配置」：拿到商户号前无法联调，但接口形状与失败语义现在就钉死，
 * 将来替换实现不需要动回调链路的任何一行判定代码。
 */
public interface WxPayCertificateSource {

    /**
     * 按证书序列号取平台证书公钥。
     *
     * @param serial {@code Wechatpay-Serial} 头值
     * @return 平台证书（wechatpay_certificate）的公钥
     * @throws RuntimeException 取不到（未配置 / 序列号未知且下载失败 / 网络异常）——
     *                          回调侧据此回**真实 5xx**（不是 200），让微信继续重试，绝不把「拿不到证书」
     *                          伪装成「已收到」；异常类型不做要求（{@link IllegalStateException} 等运行时异常即可）
     */
    PublicKey getPublicKey(String serial);

    /**
     * 本期装配的唯一实现（骨架）：恒抛「未配置」。
     * <p>为什么是 {@code @Component} 常驻而不是随 {@code pay.provider=wx} 条件装配：
     * {@code PayController} 恒装配并按类型注入本接口，条件装配会让容器在默认 {@code provider=mock} 下起不来；
     * 而「未配置」的真实拦截点在 wxNotify 的配置闸门（mchid / api-v3-key 为空 → 400），
     * 走到这里说明配置已齐、只差证书下载，抛错即回 5xx，微信继续重投，行为安全。
     * <p>TODO 真实实现（凭据到手后替换本类，Step 2c 一并做）：
     * <ol>
     *   <li>{@code GET https://api.mch.weixin.qq.com/v3/certificates}，Authorization 头用
     *       {@code WxpaySignature.buildRequestMessage("GET","/v3/certificates",timestamp,nonce,"")} +
     *       {@code WxpaySignature.sign(串, 商户私钥)}，凭 {@code wxpay.cert-path} 下 apiclient_key.pem；
     *       网络层用 JDK {@code java.net.http.HttpClient}（零新依赖，红线）；
     *   <li>响应 {@code certificates[i].encrypt_certificate} 的 ciphertext 用 APIv3 密钥经
     *       {@code WxpayAead.decrypt} 解出平台证书 PEM，{@code CertificateFactory} 取公钥，
     *       序列号 → 公钥入内存缓存（{@code serialNo} 用 encrypt_certificate.serial_no）；
     *   <li>轮换：定时（官方建议 12h）刷新缓存；收到缓存里没有的 serial 时现拉一次再取（拉不到才 5xx），
     *       并保留旧证书一段时间，避免轮换窗口内把正常通知全打成丢单；
     *   <li>失败不落缓存、不缓存 null，避免一次网络抖动把错误序列号钉住。
     * </ol>
     */
    @Component
    class Unconfigured implements WxPayCertificateSource {

        @Override
        public PublicKey getPublicKey(String serial) {
            throw new IllegalStateException("微信支付平台证书未配置：需经 GET /v3/certificates 下载并解密缓存（本期骨架）");
        }
    }
}
