package com.fresh.pay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.impl.OrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 模拟微信支付端点服务（P10-5 轨道 B / D1+D2+D4）：sim 通道的两个端点服务——
 * <ul>
 *   <li>{@link #simCreate}：模拟 jsapi 下单，本人 + pay_status=0 + 未取消校验后返回模拟五参数与金额分；</li>
 *   <li>{@link #simPay}：以「微信服务器」身份构造完整 v3 回调（{@link SimWxPaySupport} 签发），
 *       经 JDK {@code java.net.http.HttpClient} POST <b>本机</b> {@code /api/pay/{orderId}/wx-notify}，
 *       验签/新鲜度/解密/三比对/事务结算全部走 {@link WxPayNotifyService} 的真实链路（D2：自调用绕过
 *       HTTP 会绕过验签面，链路就不真；HttpClient 零新依赖）。2xx 视为置态成功回读订单返回（含 pickupCode），
 *       4xx/5xx 把判定结果透传给前端（脱敏：只带 wx-notify 应答表钉死的人话 message 与 HTTP 码）。</li>
 * </ul>
 *
 * <h3>装配与边界（D1：模拟通道并存而非替换）</h3>
 * 本类<b>不是</b> {@link PayService} 实现（pay.provider 键与 MockPayService 零改动，smoke.sh 走的
 * mock-pay 原链路不受影响），由 PayController 按<b>具体类型</b>可选注入（{@code required=false}）；
 * 条件 {@code pay.sim-enabled=true} 与 PayController 的闸门布尔读同一枚键——关闸时 Bean 不存在且
 * 端点先回 404 信封，两条防线互为兜底。
 *
 * <h3>事务边界：本类不标 {@code @Transactional}</h3>
 * 本类自己<b>一行都不写库</b>：置态/销量/取货码/邀请入账全部发生在 wx-notify 请求自己的事务里
 * （{@code WxPayNotifyService#settleInTransaction} 那枚事务入口，2xx 应答 = 已提交），
 * 成功后回读拿到的就是提交后的行（含 pickupCode）。给本方法标事务只会凭空套一层空事务。
 *
 * <h3>幂等（D4）</h3>
 * {@code pay_status≠0} 直接返回当前态（与接缝 {@code updated==0} 的回读分支同语义的前置快路）；
 * 即便并发双击穿过快路，wx-notify 侧的条件更新仍只生效一次，第二次 200 也是零二次副作用的幂等重放。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "pay.sim-enabled", havingValue = "true")
public class SimWxPayService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 回调 POST 的应答预算：官方给微信回调的窗口是 5s，本机自调用远用不满，5s 兜底防悬挂 */
    private static final Duration NOTIFY_TIMEOUT = Duration.ofSeconds(5);

    private final OrderMapper orderMapper;
    private final SimWxPaySupport support;

    /** 进程内共享的 HttpClient（线程安全；connect 3s 兜底，本机回环地址连不上即快速失败） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** wx-notify 本机端口：读 server.port（application.yml = 3001），自调用打回环地址 */
    @Value("${server.port:3001}")
    private int serverPort;

    /** 构造器注入（照 PaymentSettlement:61 姿势，测试可离线 new） */
    public SimWxPayService(OrderMapper orderMapper, SimWxPaySupport support) {
        this.orderMapper = orderMapper;
        this.support = support;
    }

    /**
     * 模拟 jsapi 下单（sim-create 端点服务）：校验集照 mock-pay——非本人（含 user_id 未认领存量单）404
     * 不泄露他人订单存在性；已取消拒；已支付如实拒（用户在另一窗口已付完，不再发一套模拟参数，
     * 文案「订单已支付，请勿重复支付」为 sim 通道新造，不动任何既有文案）。
     *
     * @return 模拟五参数（timeStamp/nonceStr/package/signType/paySign）+ amountFen（金额分，
     *         照 {@code totalPrice.movePointRight(2)} 口径，前端与收银台展示金额对账防串单）
     */
    public Map<String, Object> simCreate(String orderId, Long userId) {
        Order order = loadOwnOrder(orderId, userId);
        if (order.getPayStatus() != null && order.getPayStatus() == 1) {
            throw new BizException(R.CODE_BAD_REQUEST, "订单已支付，请勿重复支付");
        }
        if (OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BizException("订单已取消，无法支付");
        }
        return support.buildJsapiParams(orderId, amountFenOf(order));
    }

    /**
     * 模拟支付确认（sim-pay 端点服务）：校验同 sim-create（D4：pay_status≠0 幂等返回当前态），
     * 然后以微信服务器身份把一条合法回调打在本机 wx-notify 上，同步等应答。
     *
     * @return 2xx 时回读的订单行（pay_status=1 + pickupCode；幂等重放场景=当前态原样返回）
     * @throws BizException 4xx/5xx 应答 → 透传判定结果（code=真实 HTTP 码，message=应答表钉死人话，脱敏）；
     *                       网络/中断 → 500 人话
     */
    public Order simPay(String orderId, Long userId) {
        Order order = loadOwnOrder(orderId, userId);
        // D4 幂等快路：pay_status≠0（1=已支付 / 2=已退款）直接返回当前态，不再发回调
        if (order.getPayStatus() != null && order.getPayStatus() != 0) {
            return order;
        }
        if (OrderServiceImpl.STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BizException("订单已取消，无法支付");
        }
        int amountFen = amountFenOf(order);
        SimWxPaySupport.SimCallback callback = support.buildPaidNotify(orderId, amountFen);
        log.info("模拟支付回调发起: orderId={}, amountFen={}", orderId, amountFen);
        // 2xx 才返回（4xx/5xx 在 postNotify 内已透传判定结果）；2xx = accept 判定 + settleInTransaction
        // 事务都已在 wx-notify 请求内完成（异常会先回滚再给 5xx，200 即提交），回读返回提交后的行
        //（含 pickupCode，与 mock-pay 即时响应 data.pickupCode 同口径）
        postNotify(orderId, callback);
        Order settled = orderMapper.selectById(orderId);
        if (settled == null) {
            // 理论不可达（回调判定过查单在先）；防御：不让 NPE 洗成 200 信封
            log.error("模拟支付回调 2xx 但回读订单为空: orderId={}", orderId);
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付回调失败，请稍后重试");
        }
        log.info("模拟支付完成: orderId={}, payStatus={}", orderId, settled.getPayStatus());
        return settled;
    }

    /** 本人订单装载（逐字照 MockPayService#payResult 的 404 语义：非本人不泄露存在性） */
    private Order loadOwnOrder(String orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getUserId() == null || !order.getUserId().equals(userId)) {
            throw new BizException(R.CODE_NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 金额分（照 {@code WxPayNotifyService:228} 的算法口径：DECIMAL 右移两位取整分、禁 double×100、
     * 三位小数报错不悄悄舍入）。这是**计算**不是判定——判定（金额相等比对）仍只活在 accept 里。
     */
    private int amountFenOf(Order order) {
        try {
            return order.getTotalPrice().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NullPointerException e) {
            log.error("模拟支付订单金额标度异常: orderId={}, totalPrice={}", order.getId(), order.getTotalPrice());
            throw new BizException(R.CODE_SERVER_ERROR, "订单金额无法比对");
        }
    }

    /**
     * POST 本机 wx-notify：四头（Wechatpay-Timestamp/Nonce/Signature/Serial）+ 原始 body 逐字投递，
     * Content-Type 显式 charset=UTF-8（回调体含中文也不怕链路转码；验签名串用的就是这份原始串）。
     * 2xx 正常返回；4xx/5xx 抛 BizException 透传判定结果（脱敏）；网络/中断抛 BizException 500。
     */
    private void postNotify(String orderId, SimWxPaySupport.SimCallback callback) {
        String url = "http://localhost:" + serverPort + "/api/pay/" + orderId + "/wx-notify";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Wechatpay-Timestamp", callback.timestamp())
                .header("Wechatpay-Nonce", callback.nonce())
                .header("Wechatpay-Signature", callback.signature())
                .header("Wechatpay-Serial", callback.serial())
                .timeout(NOTIFY_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(callback.body(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 连不上/超时：后端自身故障，不是「支付被拒」，给 500 人话留痕，不猜具体原因透给前端
            log.warn("模拟支付回调网络失败: orderId={}, err={}", orderId, e.getClass().getSimpleName());
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付回调失败，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(R.CODE_SERVER_ERROR, "模拟支付回调失败，请稍后重试");
        }
        int status = response.statusCode();
        log.info("模拟支付回调应答: orderId={}, httpStatus={}", orderId, status);
        if (status < 200 || status >= 300) {
            // 4xx/5xx：把 wx-notify 应答表的人话 message 解出来透传（脱敏——只取 message 一项，
            // 绝不透出原始报文/头/堆栈）
            throw rejectOf(orderId, status, response.body());
        }
    }

    /**
     * 4xx/5xx 应答 → BizException（GlobalExceptionHandler 转统一信封）：code=真实 HTTP 码（把「判定失败」
     * 的档位如实带给前端），message=应答 body 里 {@code {"code":"FAIL","message":…}} 的 message——
     * 那是 wx-notify 应答表钉死的人话（验签失败/金额不符/订单已取消……），本身已脱敏；
     * body 缺失/不合法时兜底一句不含内部细节的人话。
     */
    private BizException rejectOf(String orderId, int status, String responseBody) {
        String message = "模拟支付回调被拒（HTTP " + status + "）";
        if (responseBody != null) {
            try {
                JsonNode body = MAPPER.readTree(responseBody);
                String fromNotify = body.path("message").asText("");
                if (!fromNotify.isBlank()) {
                    message = fromNotify;
                }
            } catch (Exception e) {
                // body 不是合法 JSON：保持兜底文案，绝不透出原文
                log.warn("模拟支付回调失败应答体不可解析: orderId={}, httpStatus={}", orderId, status);
            }
        }
        log.info("模拟支付回调被拒: orderId={}, httpStatus={}, message={}", orderId, status, message);
        return new BizException(status, message);
    }
}
