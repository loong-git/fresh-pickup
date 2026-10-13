package com.fresh.pay;

import com.fresh.common.BizException;
import com.fresh.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 微信支付骨架占位（需求 A）：PayService 的真实渠道实现，当前仅骨架、不实现任何真实支付逻辑。
 * - 装配条件：pay.provider=wx 时本 Bean 才创建；默认 pay.provider=mock 走 MockPayService，
 *   本类不参与装配（MockPayService 及 /api/pay/{orderId}/mock-pay 链路原样保留，不受影响）；
 * - 应用配置（application-dev.yml 占位空值，申请流程见该文件注释）：
 *   wxpay.mchid 商户号 / wxpay.appid 小程序 AppID / wxpay.cert-path 商户 API 证书路径 / wxpay.api-v3-key APIv3 密钥；
 * - 装配歧义已由 matchIfMissing 收口（F-21.3/D-21-2）：本类条件为 havingValue="wx"，MockPayService 条件为
 *   havingValue="mock" + matchIfMissing=true，两者互斥——设成 pay.provider=wx 后 mock Bean 自动退场，
 *   PayController 的 {@code @Autowired PayService} 按类型注入只会命中一个实现，NoUniqueBeanDefinitionException
 *   不再发生；不设该键（prod yml 现况）或设为 mock 时装配结果与收口前逐字相同，本类不参与装配；
 * - 回调端点见 PayController#wxNotify（验签/解密/金额三比对已由 F-21.6 Step 2b 实现，本期无商户号故不可联调）；
 * - TODO 邀请新人挂钩（F-01.2）：真实支付置 pay_status=1 时需与 MockPayService 同点补调
 *   InviteService.onOrderPaid(userId)（实现步骤见 payResult 内 TODO 第 5 条），接入时勿遗漏。
 *   F-21.3 Step 1 起「同一个支付成功点」已抽成 pay/PaymentSettlement#settle（置态 + 销量累加 + 取货码 +
 *   邀请挂钩 + 内存回写五件事都在里面，userId 从订单行取），回调侧无需再逐件手写、在自己的
 *   {@code @Transactional} 内调 settlement.settle(order, payTime) 即自动生效——勿绕过它直接 UPDATE pay_status。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "pay.provider", havingValue = "wx")
public class WxPayService implements PayService {

    /** 商户号（占位空值：为空时视为微信支付未配置） */
    @Value("${wxpay.mchid:}")
    private String mchid;

    /** 小程序 AppID（占位空值） */
    @Value("${wxpay.appid:}")
    private String appid;

    /** 商户 API 证书路径（占位空值） */
    @Value("${wxpay.cert-path:}")
    private String certPath;

    /** APIv3 密钥（占位空值；生产改环境变量注入） */
    @Value("${wxpay.api-v3-key:}")
    private String apiV3Key;

    @Override
    public Order payResult(String orderId, Long userId) {
        // 骨架占位（需求 A）：微信支付未配置/未实现，一律拒绝，前端支付分叉当前仍走 mock 链路。
        if (mchid == null || mchid.isBlank()) {
            throw new BizException("微信支付未配置，请联系管理员");
        }
        // TODO 微信支付 JSAPI 下单实现步骤（接入时按序补齐）：
        //  1) 校验本人订单（同 MockPayService：非本人/已取消一律拒绝，不泄露他人订单存在性）；
        //  2) 调用统一下单 API：POST https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi，
        //     body 携带 appid/mchid/description/out_trade_no(=orderId)/notify_url
        //     （https://<域名>/api/pay/{orderId}/wx-notify，即 PayController#wxNotify）/amount.total（单位：分），
        //     请求前用商户私钥（cert-path 下 apiclient_key.pem）对请求做 SHA256-RSA 签名并携带 Authorization 头；
        //  3) 取响应 prepay_id，按小程序端拉起支付的签名规则二次签名
        //     （appId/timeStamp/nonceStr/package=prepay_id=xxx，again SHA256-RSA 商户私钥），
        //     返回前端 wx.requestPayment 所需五参数；
        //  4) 支付结果不以此同步返回为准：以 wx-notify 回调（验签+解密+比对金额后）置 pay_status=1，
        //     本方法同步返回的 Order 为"待支付"态即可，前端轮询订单状态确认到账；
        //  5) 邀请新人挂钩（F-01.2，与 MockPayService 同点）：置 pay_status=1 的同一事务内
        //     调用 InviteService.onOrderPaid(userId)——首单判定 + invite_relation 原子置位 +
        //     邀请现金入账（F-04.2 冻结流水 status 0→1 → balance+5，F-05 后现金轨唯一激励，
        //     已在 onOrderPaid 内实现，随挂钩自动生效，接入时勿遗漏）；
        //     回调端点见 PayController#wxNotify，挂在其置态事务内；置态本身已由 F-21.3 抽成
        //     PaymentSettlement#settle（置态 + 销量累加 + 取货码 + 本条邀请挂钩一体），回调侧调它即含本条。
        throw new BizException("微信支付未配置，请联系管理员");
    }
}
