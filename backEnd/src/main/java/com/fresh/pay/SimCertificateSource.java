package com.fresh.pay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * 模拟平台证书公钥来源（P10-5 轨道 B / D2）：{@code pay.sim-enabled=true} 时装配，
 * 把 wx-notify 的平台证书验签引到 {@link SimWxPaySupport} 运行时生成的模拟公钥上——
 * 于是模拟回调（sim-pay 端点自签自投）也要过 {@link WxPayNotifyService#accept} 的真实验签，
 * 平台证书来源这一环不再是「永远 503 的骨架」，而是模拟轨里真实工作的那一环。
 *
 * <h3>与 {@link WxPayCertificateSource.Unconfigured} 的共存与优先级（装配选择，登记）</h3>
 * {@code Unconfigured} 是接口文件里的嵌套 {@code @Component}、无条件常驻；本类再注册一个实现后，
 * {@link WxPayNotifyService}（<b>恒装配</b>，F-21.7 教训绝不可加条件）的构造器按类型注入就会出现两个候选。
 * 三种消歧手段里：改 Unconfigured 加 {@code @ConditionalOnMissingBean} 要动
 * {@code WxPayCertificateSource.java}——<b>不在本片文件白名单内，一字不动</b>；按名字注入要改
 * WxPayNotifyService——红线禁改。故选 <b>{@code @Primary}</b>：sim 启用时模拟公钥胜出（模拟轨的回调
 * 只可能由本仓 SimWxPaySupport 签发，验签就该用模拟公钥）；sim 关闭（prod 缺省）时本 Bean 不装配，
 * 候选回到唯一的 Unconfigured，行为与加入本类之前逐字一致——两条装配路径容器都起得来。
 *
 * <h3>安全边界（登记）</h3>
 * {@code pay.sim-enabled=true} 时真实微信回调若真的打进来，验签会用模拟公钥 → 401 拒收（不会误入账，
 * 真实签名对模拟公钥验不过）。因此本类只允许出现在 dev（application-dev.yml），prod 绝不可开 sim-enabled；
 * 模拟轨转真实轨的那一步 = 关掉 sim-enabled，本 Bean 随条件注解自动退场，回调链路零改动。
 *
 * <p>未知序列号的处理照 {@code WxPayNotifyTest} 测试实现同性质：抛 {@link IllegalStateException}，
 * accept 会转 503「微信支付平台证书不可用」（问题在证书侧，让投递方重投），不伪装成 4xx。
 */
@Component
@Primary
@ConditionalOnProperty(name = "pay.sim-enabled", havingValue = "true")
public class SimCertificateSource implements WxPayCertificateSource {

    private final SimWxPaySupport support;

    /** 构造器注入（照 PaymentSettlement:61 姿势）：SimWxPaySupport 是无条件 @Component，恒可注入 */
    public SimCertificateSource(SimWxPaySupport support) {
        this.support = support;
    }

    @Override
    public PublicKey getPublicKey(String serial) {
        if (!support.isSimSerial(serial)) {
            throw new IllegalStateException("非模拟平台证书序列号：" + serial);
        }
        return support.platformPublicKey();
    }
}
