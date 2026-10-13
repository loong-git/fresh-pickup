package com.fresh.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.mapper.OrderMapper;
import com.fresh.pay.PayService;
import com.fresh.pay.PaymentSettlement;
import com.fresh.pay.WxPayCertificateSource;
import com.fresh.pay.WxpayAead;
import com.fresh.pay.WxpaySignature;
import com.fresh.service.impl.OrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 支付接口（T-M2-05，契约 M2-5）：/api/pay/** 需登录（AuthInterceptor 校验并注入 userId）。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay")
public class PayController {

    @Autowired
    private PayService payService;

    /**
     * 微信支付回调链路的三个协作者（F-21.6 Step 2b）。加在类上是**被迫的最小增量**：
     * wxNotify 要查单、要结算、要取平台证书公钥，而本批文件白名单不含新 Service，
     * 编排只能落在这个端点里，协作者只能按 Spring 惯例注入到本类字段。
     * mockPay 与其余端点一个字节都没用到它们。
     */
    @Autowired
    private OrderMapper orderMapper;

    /** 支付成功结算接缝（D-21-1）：回调只判定「能不能认定这笔支付成功」，入账动作全部在这一个组件里 */
    @Autowired
    private PaymentSettlement settlement;

    /** 平台证书公钥来源：本期装配的是骨架 {@code WxPayCertificateSource.Unconfigured}（恒抛未配置），测试注入自造公钥 */
    @Autowired
    private WxPayCertificateSource certificateSource;

    /** Mock 支付开关（T-M2-05）：dev=true；prod 配 false → 端点对外表现 404（信封 code:404） */
    @Value("${pay.mock-enabled:true}")
    private boolean mockEnabled;

    /** 微信支付商户号（需求 A 占位配置）：为空视为微信支付未配置，回调直接拒收 */
    @Value("${wxpay.mchid:}")
    private String wxpayMchid;

    /** APIv3 密钥（32 位字符串，占位空值；真实值只走环境变量注入，仓库里永远是空）：回调 resource 的 AES-256-GCM 解密用 */
    @Value("${wxpay.api-v3-key:}")
    private String wxpayApiV3Key;

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
     * 微信支付 v3 支付结果回调（F-21.6 Step 2b 真实现，替换骨架期占位）：
     * <b>验签 → 解密 → 三比对 → 调既有结算接缝 → 按 HTTP 状态码应答</b>。
     *
     * <h3>为什么这一处必须破「统一信封」的例（D-21-4 定案，本仓唯一例外）</h3>
     * 本仓铁律是「HTTP 恒 200、语义只在 {@code body.code}」（见 {@code common/R} 类注释），
     * 套到本端点上是<b>致命的</b>。微信支付官方开发指引原文：
     * <b>「支付通知 HTTP 应答码为 200 或 204 才会当作正常接收，当回调处理异常时，应答的 HTTP 状态码应为 500，或者 4xx」</b>。
     * 微信只看 HTTP 状态码——失败也回 200 就是告诉它「商户已成功接收」→ <b>永久停止重发</b>：
     * 任何一次瞬时故障（DB 抖动、取货码 5 次撞码、平台证书还没缓存上）都会让这笔支付<b>永远丢单</b>，
     * 运营和用户只看到「已扣款未到账」。故本方法内<b>不走 {@code R} 信封</b>：
     * 成功 → {@code 200}（官方也接受 204）且<b>不带业务 JSON</b>；任何失败 → 真实 {@code 4xx/5xx} +
     * body {@code {"code":"FAIL","message":"…"}}。其余端点（含 mockPay）信封一律不动。
     * 官方重试节奏 {@code 15s/15s/30s/3m/10m/20m/30m/30m/30m/60m/3h/3h/3h/6h/6h}
     * （总计 24h4m、最多 15 次），且官方明说<b>「不保证通知最终成功」</b>→ 查单兜底是必配项，
     * 登记为 Step 2c（见 docs/上线任务文档.md F-21.6 第 5 条），本期未做。
     *
     * <h3>应答表（HTTP 状态码是唯一语义载体）</h3>
     * <table>
     *   <tr><th>情形</th><th>HTTP</th><th>body</th></tr>
     *   <tr><td>三比对通过并结算成功（含合法重放：幂等、零二次副作用）</td><td>200</td><td>无（空体）</td></tr>
     *   <tr><td>mchid / api-v3-key 未配置（骨架期同文案，状态码从 200 改真实 400）</td><td>400</td><td>FAIL / 微信支付未配置</td></tr>
     *   <tr><td>验签头或报文缺失</td><td>400</td><td>FAIL / 微信支付回调缺少验签头</td></tr>
     *   <tr><td>报文不是合法 JSON</td><td>400</td><td>FAIL / 微信支付回调报文不合法</td></tr>
     *   <tr><td>平台证书取不到（骨架未实现 / 序列号未知 / 网络异常）</td><td>503</td><td>FAIL / 微信支付平台证书不可用</td></tr>
     *   <tr><td>验签不通过（含签名被改、非平台私钥所签、头里不是合法 Base64）</td><td>401</td><td>FAIL / 微信支付回调验签失败</td></tr>
     *   <tr><td>resource 缺字段 / algorithm 不是 AEAD_AES_256_GCM</td><td>400</td><td>FAIL / 微信支付回调加密报文不合法</td></tr>
     *   <tr><td>解密失败（APIv3 密钥不符、密文被篡改、nonce 长度不合规）</td><td>400</td><td>FAIL / 微信支付回调解密失败</td></tr>
     *   <tr><td>{@code out_trade_no} 与路径 orderId 不一致</td><td>400</td><td>FAIL / 微信支付回调订单号不匹配</td></tr>
     *   <tr><td>{@code trade_state != SUCCESS}</td><td>400</td><td>FAIL / 微信支付结果非成功状态</td></tr>
     *   <tr><td>缺 {@code amount.total}</td><td>400</td><td>FAIL / 微信支付回调缺少金额</td></tr>
     *   <tr><td>订单查不到（回调无登录态，单号即主键）</td><td>404</td><td>FAIL / 订单不存在</td></tr>
     *   <tr><td>已取消订单收到成功通知（文案沿用 mock 同一条，不新造）</td><td>400</td><td>FAIL / 订单已取消，无法支付</td></tr>
     *   <tr><td>金额与订单应付不符（篡改 amount.total）</td><td>400</td><td>FAIL / 微信支付回调金额与订单不符</td></tr>
     *   <tr><td>库内金额标度异常，无法比对（服务端自己的问题，故 500 不是 400）</td><td>500</td><td>FAIL / 订单金额无法比对</td></tr>
     *   <tr><td>结算内部异常</td><td>500</td><td>FAIL / 微信支付回调处理失败</td></tr>
     * </table>
     * message 只给人话与定性，绝不透出堆栈/SQL（与 {@code GlobalExceptionHandler} 同纪律）。
     *
     * <h3>判定与结算的分工（D-21-1：入账动作一行都不许新写）</h3>
     * 三比对全过才调 {@link PaymentSettlement#settle(Order, LocalDateTime)}——置态 / 销量累加 /
     * 取货码 / 邀请挂钩四件事只活在那一处接缝里（mock 渠道与免费领 0 元单共用）。
     * 幂等依据（官方「同样的通知可能会多次发送给商户系统，商户系统必须能够正确处理重复的通知」）：
     * 重放同一条通知时条件更新 {@code pay_status=0→1} 影响行数 0 → 接缝只回读、一件副作用都不做，
     * 本方法照样回 200（这一笔确实已经处理成功，回执 200 让微信停止重发）。
     *
     * <h3>5 秒预算（官方应答窗口）</h3>
     * 结算体是几条走索引（主键 + idx）的 UPDATE/SELECT，同步做可接受。
     * TODO 若将来在结算里加慢操作（发消息 / 调第三方 / 批量对账），必须改成
     *      「先验签 → 应答 200 → 再异步处理业务」（官方建议的顺序）；超窗会让微信重投，幂等路径变常态。
     *
     * <h3>已知边界（本期无商户号、不做联调，如实登记）</h3>
     * ① 回调没有登录态：order 一律由 {@code out_trade_no}（= orders.id，雪花串）查库得到，查不到 → 404；
     *    <b>绝不</b>复用 mock 的「非本人 404」那条校验（没有 token 可比，比对的是「这单存在、未支付、未取消」）。
     * ② 未做时间戳新鲜度校验（官方防重放窗口 5 分钟）：伪造通知拿不到合法签名、合法重放已被幂等吸收，
     *    本期不实现这条分支。TODO 接入时补「{@code |now - Wechatpay-Timestamp| > 5 分钟即拒}」。
     * ③ payTime 取服务器时间；微信 {@code success_time} 才是渠道权威时刻（秒级差异，本期不引入解析失败面）。
     *    TODO Step 2c 改 {@code OffsetDateTime.parse(success_time).toLocalDateTime()}。
     * ④ 本方法<b>不标</b> {@code @Transactional}：事务入口本该是回调自己的 Service 方法
     *    （见 PaymentSettlement 类注释的边界口径），但本批文件白名单不含新 Service；
     *    而标在本方法上又必须方法内 catch-all（内部异常按官方必须真实 5xx，见应答表末行），
     *    catch 之后代理层看不到异常 → 永不回滚，加了只是假象。故本期不加注解，
     *    改由「失败即真实 5xx 让微信重投 + 重投经条件更新幂等收敛」兜住。
     *    TODO Step 2c：把整段编排下沉到 {@code @Transactional} 的 WxPayNotifyService，
     *    异常在事务边界<b>外</b>转成 5xx（先回滚、再应答），彻底消掉「settle 多条 UPDATE 之间宕机 →
     *    pay_status=1 而无取货码/未计销量」这个残留窗口（重投会走 updated==0 分支，补不回那几件副作用）。
     */
    @PostMapping("/{orderId}/wx-notify")
    public ResponseEntity<Map<String, String>> wxNotify(@PathVariable("orderId") String orderId,
                                                        @RequestBody(required = false) String body,
                                                        @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
                                                        @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
                                                        @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
                                                        @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        // 两个局部函数：本批白名单只允许改这个方法，故不新增类级私有方法（失败应答与空值判定各收敛到一处）。
        BiFunction<HttpStatus, String, ResponseEntity<Map<String, String>>> reject =
                (status, message) -> ResponseEntity.status(status).body(Map.of("code", "FAIL", "message", message));
        Predicate<String> blank = s -> s == null || s.isBlank();

        // —— 0) 配置闸门：未配置状态下**绝不能返回 200**（200 = 微信以为投递成功 = 丢单）。
        //      骨架期这里回 R.fail(400,"微信支付未配置") 走 HTTP 200，本身正是 F-21.6 纠偏的那处错；
        //      本批把状态码改成真实 400、message 文案「微信支付未配置」逐字保留（不新造、不改写既有文案）。——
        if (blank.test(wxpayMchid) || blank.test(wxpayApiV3Key)) {
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付未配置");
        }
        // 四个验签头 + 原始报文缺一不可：v3 用 Wechatpay-Serial 定位平台证书，没有它连验什么都无法确定
        if (blank.test(body) || blank.test(timestamp) || blank.test(nonce) || blank.test(signature) || blank.test(serial)) {
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调缺少验签头");
        }

        // Jackson 随 spring-boot-starter-web 已在类路径（非新增依赖）。回调量级极小，方法内现建即可。
        // TODO 若通知量大，改注入容器里那枚 ObjectMapper。
        ObjectMapper mapper = new ObjectMapper();
        JsonNode notify;
        try {
            notify = mapper.readTree(body);
        } catch (Exception e) {
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调报文不合法");
        }

        // —— 1) 平台证书验签（先验签、后解密：未过签的报文一个字节都不该被当 JSON 解析）——
        PublicKey platformKey;
        try {
            platformKey = certificateSource.getPublicKey(serial);
        } catch (RuntimeException e) {
            // 未配置 / 序列号未知且下载失败：503（可重试），不是 4xx——问题在商户侧，别让它变成丢单
            log.error("微信支付回调取平台证书失败: serial={}, cause={}", serial, e.getMessage());
            return reject.apply(HttpStatus.SERVICE_UNAVAILABLE, "微信支付平台证书不可用");
        }
        // 验签名串用**原始 body**（不是重新序列化的 JsonNode）：字段顺序或空白一变即验签失败
        String verifyMessage = WxpaySignature.buildVerifyMessage(timestamp, nonce, body);
        if (!WxpaySignature.verify(verifyMessage, signature, platformKey)) {
            log.warn("微信支付回调验签失败: orderId={}, serial={}", orderId, serial);
            return reject.apply(HttpStatus.UNAUTHORIZED, "微信支付回调验签失败");
        }

        // —— 2) 解密 resource（AES-256-GCM：key=APIv3 密钥、IV=resource.nonce、AAD=associated_data）——
        JsonNode resource = notify.path("resource");
        String algorithm = resource.path("algorithm").asText("");
        String cipherText = resource.path("ciphertext").asText("");
        if (!WxpayAead.ALGORITHM.equals(algorithm) || cipherText.isEmpty()) {
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调加密报文不合法");
        }
        String transactionJson;
        try {
            transactionJson = WxpayAead.decrypt(wxpayApiV3Key, resource.path("nonce").asText(""),
                    resource.path("associated_data").asText(""), cipherText);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 错 APIv3 密钥（典型 AEADBadTagException）/ 密文或 AAD 被篡改 / nonce 长度不合规：
            // GCM 的 tag 校验本身就是「报文没被动过」的证明。此分支在 settle 之前，订单一个字节都没动
            log.warn("微信支付回调解密失败: orderId={}, cause={}", orderId, e.getClass().getSimpleName());
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调解密失败");
        }
        JsonNode transaction;
        try {
            transaction = mapper.readTree(transactionJson);
        } catch (JsonProcessingException e) {
            log.warn("微信支付回调解密结果不是合法 JSON: orderId={}", orderId);
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调解密失败");
        }

        // —— 3) 三比对：out_trade_no / trade_state / amount.total（全过才认定这笔支付成功）——
        String outTradeNo = transaction.path("out_trade_no").asText("");
        if (outTradeNo.isEmpty() || !outTradeNo.equals(orderId)) {
            // 路径 orderId 是 notify_url 的一部分，两者不符 = 这条通知不是发给这一单的（或有人在拼单号）
            log.warn("微信支付回调订单号不符: path={}, out_trade_no={}", orderId, outTradeNo);
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调订单号不匹配");
        }
        String tradeState = transaction.path("trade_state").asText("");
        if (!"SUCCESS".equals(tradeState)) {
            log.warn("微信支付回调非成功态: orderId={}, trade_state={}", orderId, tradeState);
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付结果非成功状态");
        }
        JsonNode totalNode = transaction.path("amount").path("total");
        if (!totalNode.isNumber()) {
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调缺少金额");
        }
        int paidFen = totalNode.asInt();

        Order order;
        try {
            order = orderMapper.selectById(outTradeNo);
        } catch (RuntimeException e) {
            // DB 抖动一类：真实 5xx，让微信按它的重试节奏再投（绝不能回 200 把它糊过去）
            log.error("微信支付回调查单失败: orderId={}", outTradeNo, e);
            return reject.apply(HttpStatus.INTERNAL_SERVER_ERROR, "微信支付回调处理失败");
        }
        if (order == null) {
            // 验签与解密都过了却查无此单：单号属于别的商户/环境，或本地数据被清；
            // 回调无登录态，这里只断「这单存在」，不做归属比对（F-21.6 第 3c 条）
            log.warn("微信支付回调订单不存在: orderId={}", outTradeNo);
            return reject.apply(HttpStatus.NOT_FOUND, "订单不存在");
        }
        if (OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus())) {
            // 已扣款但本地单已取消：不自动结算复活一张取消单（销量/取货码/邀请账都会算错），
            // 拒收并留 ERROR 给运营人工退款。TODO Step 2c 查单兜底时一并接退款链路。
            log.error("已取消订单收到微信支付成功通知，需人工处理: orderId={}, amount={}", outTradeNo, paidFen);
            return reject.apply(HttpStatus.BAD_REQUEST, "订单已取消，无法支付");
        }
        int expectedFen;
        try {
            // 金额比对的算法（F-21.6 第 3b 条钉死，禁止 double×100）：DECIMAL(10,2) 右移两位取整分；
            // setScale(0, UNNECESSARY) 保证库内万一出现三位小数是**报错**而不是悄悄舍入。
            expectedFen = order.getTotalPrice().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NullPointerException e) {
            log.error("订单金额标度异常，无法比对: orderId={}, totalPrice={}", outTradeNo, order.getTotalPrice(), e);
            return reject.apply(HttpStatus.INTERNAL_SERVER_ERROR, "订单金额无法比对");
        }
        if (expectedFen != paidFen) {
            log.error("微信支付回调金额与订单不符: orderId={}, 期望={}分, 通知={}分", outTradeNo, expectedFen, paidFen);
            return reject.apply(HttpStatus.BAD_REQUEST, "微信支付回调金额与订单不符");
        }

        // —— 4) 结算：入账动作全在接缝里，本方法只负责「能不能认定这笔支付成功」——
        //      重放到这里时 updated==0 → 只回读、零副作用，照样回 200（幂等，见方法注释的分工段）
        try {
            Order settled = settlement.settle(order, LocalDateTime.now());
            log.info("微信支付回调结算完成: orderId={}, payStatus={}, transactionId={}",
                    outTradeNo, settled == null ? null : settled.getPayStatus(),
                    transaction.path("transaction_id").asText(""));
        } catch (RuntimeException e) {
            log.error("微信支付回调结算失败: orderId={}", outTradeNo, e);
            return reject.apply(HttpStatus.INTERNAL_SERVER_ERROR, "微信支付回调处理失败");
        }
        // 成功：200 且不带业务 JSON（官方：200 或 204 都算正常接收，微信据此停止重发）
        return ResponseEntity.ok().build();
    }
}
