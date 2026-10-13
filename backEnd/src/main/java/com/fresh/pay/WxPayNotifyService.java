package com.fresh.pay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.entity.Order;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.impl.OrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 微信支付 v3 回调的编排服务（F-21.8 Step 2c / C-1）：Step 2b 落在 {@code PayController#wxNotify}
 * 里的整段判定（验签 → 时间戳新鲜度 → 解密 → 三比对 → 产出待结算订单）整体搬到这里，
 * controller 缩成映射层（C-5）。判定逻辑与失败语义**逐字沿用 Step 2b 已落地的版本**（含注释里的因果），
 * 本批只做了三件事：① 宿主类换掉、② 补时间戳新鲜度窗口（C-3）、③ payTime 改取渠道 {@code success_time}（C-4）。
 *
 * <h3>装配：普通 {@code @Service}，**绝不可加条件装配注解（ConditionalOnProperty）**（F-21.7 实测教训）</h3>
 * {@link WxPayService} 正因为带 {@code pay.provider=wx} 条件，默认 {@code provider=mock} 下 Bean 不存在；
 * controller 按类型注入它会让容器起不来。本类必须**恒装配**——{@code wx-notify} 端点恒存在，
 * 默认配置（无商户号）下也要能回「微信支付未配置」那条真实 400，而不是 404/500。
 *
 * <h3>事务边界（C-2，本类存在的另一half理由）</h3>
 * {@link #accept} **不标** {@code @Transactional}：只判定、一行都不写，把判定包进事务只会让 5xx 应答
 * 落进「回滚歧义」里。{@link #settleInTransaction} 才是回调链路的**事务入口**
 * （{@code PaymentSettlement} 自己不标，F-21.3 第 2 条钉死；三条 settle 调用点各有自己的入口：
 * mock 是 {@code MockPayService#payResult}、免费领是 {@code FreeServiceImpl#claim}、回调是这里）。
 * Spring 自调用陷阱：{@code @Transactional} 靠代理生效，同类内部 {@code this.xxx()} 不经过代理 → 注解完全无效，
 * 所以注解必须标在**由 controller 从外部调用的公开方法**上，不能标在被 accept 调用的私有方法里。
 *
 * <h3>两个方法的调用协议（controller 只照此执行，零判定逻辑）</h3>
 * <ol>
 *   <li>{@code Accepted a = accept(...)}：任何失败抛 {@link WxPayNotifyRejectException}（**绝不返回 null 表示失败**）；</li>
 *   <li>{@code settleInTransaction(a.order(), a.payTime())}：异常穿出代理边界（先回滚）后才被 controller catch，
 *       于是「先回滚、再应答 5xx」成立 → 微信按重试节奏重投 → 重投经条件更新 {@code updated==0} 幂等收敛。</li>
 * </ol>
 *
 * <h3>判定顺序（先后是安全属性，不是风格，一字不许改）</h3>
 * 配置闸门 → 验签头齐备 → 报文是 JSON → 取平台公钥 → 验签 → **时间戳新鲜度** → 解密 →
 * {@code out_trade_no} 比对 → {@code trade_state} → {@code amount.total} 存在 → 查单 → 订单存在 →
 * 未取消 → 金额相等 → 产出 {@link Accepted}。先验签、后解密；结算不在本方法内（见 C-2）。
 */
@Slf4j
@Service
public class WxPayNotifyService {

    /**
     * 官方防重放窗口 5 分钟（C-3）：{@code |now - Wechatpay-Timestamp| > 300s} 即拒。
     * 微信给的 {@code Wechatpay-Timestamp} 是**秒级 epoch 的字符串**。
     */
    private static final long NOTIFY_TIMESTAMP_TOLERANCE_SECONDS = 300;

    private final OrderMapper orderMapper;
    private final PaymentSettlement settlement;
    private final WxPayCertificateSource certificateSource;

    /**
     * 构造器注入（照 {@code PaymentSettlement:61-67} 写法）：三枚依赖全部 mock 友好，
     * 单测直接 {@code new WxPayNotifyService(orderMapper, settlement, certificateSource)}，不起 Spring 容器、不连库。
     * 两枚配置值（mchid / api-v3-key）**由方法入参传入**（controller 继续持有那两枚 {@code @Value}），
     * 使本类不依赖 {@code @Value} 注入——离线单测因此能直接传常量制造「未配置」那条分支。
     */
    public WxPayNotifyService(OrderMapper orderMapper, PaymentSettlement settlement,
                              WxPayCertificateSource certificateSource) {
        this.orderMapper = orderMapper;
        this.settlement = settlement;
        this.certificateSource = certificateSource;
    }

    /**
     * 判定通过的产物：待结算的订单行 + 支付成功时刻（{@code success_time}，见 C-4）。
     * 同文件嵌套 record，**不新增第三个 Spring Bean**。
     * 语义上没有「失败形态」：失败一律抛 {@link WxPayNotifyRejectException}，绝不拿 null 当哨兵。
     */
    public record Accepted(Order order, LocalDateTime payTime) {
    }

    /**
     * 只判定、不写库：验签 → 时间戳新鲜度 → 解密 → 三比对，全过才产出 {@link Accepted}。
     *
     * @param pathOrderId  路径上的 {@code /api/pay/{orderId}/wx-notify}（与解密出的 {@code out_trade_no} 比对用）
     * @param body         回调**原始报文串**（验签名串必须用它，重新序列化即验不过）
     * @param timestamp    {@code Wechatpay-Timestamp} 头（秒级 epoch 字符串）
     * @param nonce        {@code Wechatpay-Nonce} 头
     * @param signature    {@code Wechatpay-Signature} 头（Base64）
     * @param serial       {@code Wechatpay-Serial} 头（定位平台证书）
     * @param mchid        商户号（controller 的 {@code @Value} 传入；空 → 未配置闸门）
     * @param apiV3Key     APIv3 密钥（同上；空 → 未配置闸门，同时是 AEAD 解密的 key）
     * @return 判定通过的订单行与渠道支付时刻
     * @throws WxPayNotifyRejectException 任何一条拒收分支（带该回的 HTTP 码与钉死文案）
     */
    public Accepted accept(String pathOrderId, String body, String timestamp, String nonce,
                           String signature, String serial, String mchid, String apiV3Key) {

        // —— 0) 配置闸门：未配置状态下**绝不能返回 200**（200 = 微信以为投递成功 = 丢单）。
        //      骨架期这里回 R.fail(400,"微信支付未配置") 走 HTTP 200，本身正是 F-21.6 纠偏的那处错；
        //      状态码是真实 400、文案「微信支付未配置」逐字保留（不新造、不改写既有文案）。——
        if (isBlank(mchid) || isBlank(apiV3Key)) {
            throw reject(HttpStatus.BAD_REQUEST, "微信支付未配置");
        }
        // 四个验签头 + 原始报文缺一不可：v3 用 Wechatpay-Serial 定位平台证书，没有它连验什么都无法确定
        if (isBlank(body) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature) || isBlank(serial)) {
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调缺少验签头");
        }

        // Jackson 随 spring-boot-starter-web 已在类路径（非新增依赖）。回调量级极小，方法内现建即可。
        // TODO 若通知量大，改注入容器里那枚 ObjectMapper。
        ObjectMapper mapper = new ObjectMapper();
        JsonNode notify;
        try {
            notify = mapper.readTree(body);
        } catch (Exception e) {
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调报文不合法");
        }

        // —— 1) 平台证书验签（先验签、后解密：未过签的报文一个字节都不该被当 JSON 解析）——
        PublicKey platformKey;
        try {
            platformKey = certificateSource.getPublicKey(serial);
        } catch (RuntimeException e) {
            // 未配置 / 序列号未知且下载失败：503（可重试），不是 4xx——问题在商户侧，别让它变成丢单
            log.error("微信支付回调取平台证书失败: serial={}, cause={}", serial, e.getMessage());
            throw reject(HttpStatus.SERVICE_UNAVAILABLE, "微信支付平台证书不可用");
        }
        // 验签名串用**原始 body**（不是重新序列化的 JsonNode）：字段顺序或空白一变即验签失败
        String verifyMessage = WxpaySignature.buildVerifyMessage(timestamp, nonce, body);
        if (!WxpaySignature.verify(verifyMessage, signature, platformKey)) {
            log.warn("微信支付回调验签失败: orderId={}, serial={}", pathOrderId, serial);
            throw reject(HttpStatus.UNAUTHORIZED, "微信支付回调验签失败");
        }

        // —— 1b) 时间戳新鲜度窗口（C-3，补 F-21.7 登记的边界②）——
        //      位置为什么在**验签之后、解密之前**：验签前拒会让一个伪造头就能打出 400（把「验签才是门槛」降级）；
        //      而能拿出真实签名的人本来就有重放能力，窗口主要挡的是「旧通知被再次投递」这一类。
        //      放在验签后既不放松前置门槛，也不给畸形头多一次取证书的机会。
        long notifyEpochSecond;
        try {
            notifyEpochSecond = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            // 微信给的是秒级 epoch 字符串；不是数字就无从判定新鲜度（Long.parseLong("abc") 抛 NumberFormatException）
            log.warn("微信支付回调时间戳不是秒级 epoch: orderId={}, timestamp={}", pathOrderId, timestamp);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调时间戳超出允许范围");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - notifyEpochSecond);
        if (skew > NOTIFY_TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("微信支付回调时间戳超出允许范围: orderId={}, skew={}s, 容忍={}s",
                    pathOrderId, skew, NOTIFY_TIMESTAMP_TOLERANCE_SECONDS);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调时间戳超出允许范围");
        }

        // —— 2) 解密 resource（AES-256-GCM：key=APIv3 密钥、IV=resource.nonce、AAD=associated_data）——
        JsonNode resource = notify.path("resource");
        String algorithm = resource.path("algorithm").asText("");
        String cipherText = resource.path("ciphertext").asText("");
        if (!WxpayAead.ALGORITHM.equals(algorithm) || cipherText.isEmpty()) {
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调加密报文不合法");
        }
        String transactionJson;
        try {
            transactionJson = WxpayAead.decrypt(apiV3Key, resource.path("nonce").asText(""),
                    resource.path("associated_data").asText(""), cipherText);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 错 APIv3 密钥（典型 AEADBadTagException）/ 密文或 AAD 被篡改 / nonce 长度不合规：
            // GCM 的 tag 校验本身就是「报文没被动过」的证明。此分支在 settle 之前，订单一个字节都没动
            log.warn("微信支付回调解密失败: orderId={}, cause={}", pathOrderId, e.getClass().getSimpleName());
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调解密失败");
        }
        JsonNode transaction;
        try {
            transaction = mapper.readTree(transactionJson);
        } catch (JsonProcessingException e) {
            log.warn("微信支付回调解密结果不是合法 JSON: orderId={}", pathOrderId);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调解密失败");
        }

        // —— 3) 三比对：out_trade_no / trade_state / amount.total（全过才认定这笔支付成功）——
        String outTradeNo = transaction.path("out_trade_no").asText("");
        if (outTradeNo.isEmpty() || !outTradeNo.equals(pathOrderId)) {
            // 路径 orderId 是 notify_url 的一部分，两者不符 = 这条通知不是发给这一单的（或有人在拼单号）
            log.warn("微信支付回调订单号不符: path={}, out_trade_no={}", pathOrderId, outTradeNo);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调订单号不匹配");
        }
        String tradeState = transaction.path("trade_state").asText("");
        if (!"SUCCESS".equals(tradeState)) {
            log.warn("微信支付回调非成功态: orderId={}, trade_state={}", pathOrderId, tradeState);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付结果非成功状态");
        }
        JsonNode totalNode = transaction.path("amount").path("total");
        if (!totalNode.isNumber()) {
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调缺少金额");
        }
        int paidFen = totalNode.asInt();

        Order order;
        try {
            order = orderMapper.selectById(outTradeNo);
        } catch (RuntimeException e) {
            // DB 抖动一类：真实 5xx，让微信按它的重试节奏再投（绝不能回 200 把它糊过去）
            log.error("微信支付回调查单失败: orderId={}", outTradeNo, e);
            throw reject(HttpStatus.INTERNAL_SERVER_ERROR, "微信支付回调处理失败");
        }
        if (order == null) {
            // 验签与解密都过了却查无此单：单号属于别的商户/环境，或本地数据被清；
            // 回调无登录态，这里只断「这单存在」，不做归属比对（F-21.6 第 3c 条）
            log.warn("微信支付回调订单不存在: orderId={}", outTradeNo);
            throw reject(HttpStatus.NOT_FOUND, "订单不存在");
        }
        if (OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus())) {
            // 已扣款但本地单已取消：不自动结算复活一张取消单（销量/取货码/邀请账都会算错），
            // 拒收并留 ERROR 给运营人工退款。TODO 凭据到手后接查单兜底 + 退款链路（F-21.8 §0.2）。
            log.error("已取消订单收到微信支付成功通知，需人工处理: orderId={}, amount={}", outTradeNo, paidFen);
            throw reject(HttpStatus.BAD_REQUEST, "订单已取消，无法支付");
        }
        int expectedFen;
        try {
            // 金额比对的算法（F-21.6 第 3b 条钉死，禁止 double×100）：DECIMAL(10,2) 右移两位取整分；
            // setScale(0, UNNECESSARY) 保证库内万一出现三位小数是**报错**而不是悄悄舍入。
            expectedFen = order.getTotalPrice().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NullPointerException e) {
            log.error("订单金额标度异常，无法比对: orderId={}, totalPrice={}", outTradeNo, order.getTotalPrice(), e);
            throw reject(HttpStatus.INTERNAL_SERVER_ERROR, "订单金额无法比对");
        }
        if (expectedFen != paidFen) {
            log.error("微信支付回调金额与订单不符: orderId={}, 期望={}分, 通知={}分", outTradeNo, expectedFen, paidFen);
            throw reject(HttpStatus.BAD_REQUEST, "微信支付回调金额与订单不符");
        }

        // 判定到此全部通过：这笔支付在微信侧已被真实签名 + 三比对认定为成功，可以交给结算。
        // transaction_id 是渠道流水号（对账用），只在这里留痕——它属于渠道报文，不进 Accepted（判定产物只有订单行与时刻）。
        log.info("微信支付回调判定通过: orderId={}, transaction_id={}",
                outTradeNo, transaction.path("transaction_id").asText(""));
        return new Accepted(order, resolvePayTime(transaction, pathOrderId));
    }

    /**
     * 回调链路的事务入口（C-2）：判定产物在这里入账，四件副作用（置态 / 销量累加 / 取货码 / 邀请挂钩）
     * 全在 {@link PaymentSettlement#settle} 那一处接缝里（D-21-1：入账动作一行都不许新写，接缝本体一个字不改）。
     *
     * <p>为什么注解必须标在这么一枚**由 controller 从外部调用**的公开方法上：{@code @Transactional} 靠代理生效，
     * 同类内部 {@code this.xxx()} 不经过代理 → 注解完全无效；而 {@link #accept} 里既不能标它
     * （把整段判定包进事务会让 5xx 应答落进回滚歧义），也不能在其内部调一个带注解的私有方法（自调用无效）。
     *
     * <p>异常处理口径：结算接缝抛出的运行时异常在这里包成
     * {@link WxPayNotifyRejectException}（500 / 「微信支付回调处理失败」，应答表末行）。
     * 这**不影响回滚**——包装后的异常依旧是 RuntimeException 且依旧**穿出本方法的代理边界**，
     * 代理先按默认规则（RuntimeException → 回滚）整单回滚，再把异常抛给 controller，controller 才 catch 成 5xx，
     * 于是「先回滚、再应答」的顺序成立（Step 2b 那句「settle 多条 UPDATE 之间宕机 → pay_status=1 而无取货码」
     * 的残留窗口自此消掉）。不这么做的后果是原始异常漏到 {@code GlobalExceptionHandler} 被洗成 HTTP 200，
     * 而 controller 侧又被明令不许 {@code catch (RuntimeException)} 兜一切（那会把 NPE 一类编程错误洗成拒收）。
     *
     * @param order   {@link #accept} 判定通过的订单行
     * @param payTime {@link #accept} 从 {@code success_time} 解析出的支付时刻（C-4）
     * @return 结算后的订单（重放时 {@code updated==0} → 接缝只回读、零二次副作用）
     * @throws WxPayNotifyRejectException 结算内部异常（500，微信会重投；重投经幂等收敛）
     */
    @Transactional
    public Order settleInTransaction(Order order, LocalDateTime payTime) {
        try {
            Order settled = settlement.settle(order, payTime);
            log.info("微信支付回调结算完成: orderId={}, payStatus={}",
                    settled == null ? null : settled.getId(), settled == null ? null : settled.getPayStatus());
            return settled;
        } catch (RuntimeException e) {
            log.error("微信支付回调结算失败: orderId={}", order.getId(), e);
            throw new WxPayNotifyRejectException(HttpStatus.INTERNAL_SERVER_ERROR, "微信支付回调处理失败", e);
        }
    }

    /**
     * {@code payTime} 取微信 {@code transaction.success_time}（C-4，补 F-21.7 登记的边界③）：
     * 渠道权威时刻，秒级（与 {@code orders.pay_time} 同精度，不引入纳秒尾巴，也不与 mock 链路响应口径打架）。
     *
     * <p><b>缺失或解析失败不得拒收</b>——这条兜底看着像「不严格」，实为取舍，必须读懂再改：
     * 支付事实已由**验签 + 三比对**认定（钱确实到账了），{@code success_time} 只是渠道侧的一个报文字段；
     * 因它缺失/畸形就拒收，等于把一笔真实支付打成「商户没收到」→ 微信重投 15 次后放弃 → **丢单**，
     * 用户侧表现是「已扣款未到账」。故这里回落到 {@code LocalDateTime.now()} 并 {@code log.warn}
     * （含 orderId 与原始串，供运营事后校正时刻），结算照常进行。
     *
     * <p>解析口径（JDK 17 实测）：{@code OffsetDateTime.parse("2026-10-13T10:06:30+08:00").toLocalDateTime()}
     * → {@code 2026-10-13T10:06:30}；{@code "...T02:06:30Z"} → {@code 2026-10-13T02:06:30}
     * （即按报文偏移量折算后的**本地墙钟值**，与库内 DATETIME 一致）。
     */
    private LocalDateTime resolvePayTime(JsonNode transaction, String pathOrderId) {
        String successTime = transaction.path("success_time").asText("");
        if (successTime.isEmpty()) {
            log.warn("微信支付回调缺 success_time，payTime 回落服务器时间: orderId={}", pathOrderId);
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(successTime).toLocalDateTime();
        } catch (RuntimeException e) {
            // DateTimeParseException（java.time.format）本身就是 RuntimeException 的子类，catch 父类即覆盖它；
            // 兜底同上——渠道字段异常不拒收，回落 now 并留痕（含原始串，供运营事后校正时刻）
            log.warn("微信支付回调 success_time 解析失败，payTime 回落服务器时间: orderId={}, success_time={}",
                    pathOrderId, successTime, e);
            return LocalDateTime.now();
        }
    }

    /** 失败的唯一出口：带上应答表钉死的 HTTP 码与文案，由 controller 转成真实状态码（不在本类里拼 ResponseEntity） */
    private static WxPayNotifyRejectException reject(HttpStatus status, String message) {
        return new WxPayNotifyRejectException(status, message);
    }

    /** 空值判定（与 Step 2b 的局部 Predicate 同语义，下沉后收敛到一处）：null 与纯空白同判 */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
