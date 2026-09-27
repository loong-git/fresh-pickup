package com.fresh.controller;

import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.pay.PayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（T-M2-05，契约 M2-5）：/api/pay/** 需登录（AuthInterceptor 校验并注入 userId）。
 */
@RestController
@RequestMapping("/api/pay")
public class PayController {

    @Autowired
    private PayService payService;

    /** Mock 支付开关（T-M2-05）：dev=true；prod 配 false → 端点对外表现 404（信封 code:404） */
    @Value("${pay.mock-enabled:true}")
    private boolean mockEnabled;

    /** 微信支付商户号（需求 A 占位配置）：为空视为微信支付未配置，回调直接 400 */
    @Value("${wxpay.mchid:}")
    private String wxpayMchid;

    /** 模拟收银台（契约 M2-5）：登录 + 本人订单 → pay_status=1 + pay_time；幂等，重复调用返回成功 */
    @PostMapping("/{orderId}/mock-pay")
    public R<Order> mockPay(@PathVariable("orderId") String orderId,
                            @RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
        if (!mockEnabled) {
            // prod 关闭 Mock：按资源不存在返回，不暴露端点能力
            return R.fail(R.CODE_NOT_FOUND, "资源不存在");
        }
        return R.ok(payService.payResult(orderId, userId), "支付成功");
    }

    /**
     * 微信支付回调端点（需求 A 骨架占位）：微信服务器支付成功后 POST 本端点（无 token，匿名可达，
     * AuthInterceptor 已对 wx-notify 回调路径豁免登录）。未来统一下单时 notify_url 指向这里。
     * 当前未实现验签：wxpay.mchid 为空（占位配置）一律返回 R{code:400, message:"微信支付未配置"}。
     */
    @PostMapping("/{orderId}/wx-notify")
    public R<Void> wxNotify(@PathVariable("orderId") String orderId,
                            @RequestBody(required = false) String body,
                            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
                            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
                            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce) {
        // 占位守卫：商户号未配置 → 不处理任何回调
        if (wxpayMchid == null || wxpayMchid.isBlank()) {
            return R.fail(R.CODE_BAD_REQUEST, "微信支付未配置");
        }
        // TODO 微信支付回调验签实现步骤（接入时按序补齐，全部通过后才允许置支付状态）：
        //  1) 平台证书验签：构造验签名串（Wechatpay-Timestamp\n Wechatpay-Nonce\n body\n），
        //     用微信支付「平台证书」公钥对 Wechatpay-Signature 做 SHA256-RSA 验签；
        //     平台证书经 GET /v3/certificates 下载（证书/签名字段值用 APIv3 密钥 AES-256-GCM 解密获得），
        //     需缓存并随平台证书轮换自动更新；
        //  2) 验签通过后解密 resource：AES-256-GCM（key=APIv3 密钥，nonce=resource.nonce，
        //     associated_data=resource.associated_data）解出订单结果 JSON（out_trade_no/amount.total/trade_state）；
        //  3) 比对订单金额：out_trade_no 与 path 的 orderId 一致、amount.total（分）与服务端订单
        //     total_price×100 一致、trade_state=SUCCESS，三者全满足才置 pay_status=1 + pay_time
        //     （复用 MockPayService 的条件更新 pay_status=0→1 思路保证幂等与并发安全）；
        //  4) 处理成功按官方要求返回 {"code":"SUCCESS"}（当前统一信封 R 为骨架期占位形态，
        //     接入真实支付时本端点返回值需改为官方应答结构）。
        // 骨架期占位返回：验签未实现，不区分已配置与否一律同文案（需求 A 约定）
        return R.fail(R.CODE_BAD_REQUEST, "微信支付未配置");
    }
}
