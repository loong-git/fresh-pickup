package com.fresh.controller;

import com.fresh.common.R;
import com.fresh.entity.Order;
import com.fresh.interceptor.AuthInterceptor;
import com.fresh.pay.PayService;
import com.fresh.pay.WxPayNotifyRejectException;
import com.fresh.pay.WxPayNotifyService;
import com.fresh.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 支付接口（T-M2-05，契约 M2-5）：/api/pay/** 需登录（AuthInterceptor 校验并注入 userId）。
 * 例外：{@code /api/pay/{orderId}/wx-notify} 是微信服务器直连，无登录态（见该方法注释的已知边界①）。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay")
public class PayController {

    @Autowired
    private PayService payService;

    /**
     * 微信支付回调的编排服务（F-21.8 Step 2c / C-1）：验签、时间戳新鲜度、解密、三比对、结算
     * 全在它里面，本类只剩「HTTP 映射」。Step 2b 被迫加在本类的三枚协作者字段
     * （{@code orderMapper} / {@code settlement} / {@code certificateSource}）随本批下沉**已删除**——
     * 那是当时文件白名单不含新 Service 的最小增量，本批正是把它们移走的那一步。
     * 本类因此不再直接碰结算接缝（既没有对 {@code PaymentSettlement} 的字段注入，也没有对它的调用）。
     */
    @Autowired
    private WxPayNotifyService notifyService;

    /**
     * 拒收留痕（F-44）：回调被拒时往 {@code admin_audit_log} 写一行，让运营在 front-admin 审计页查得到
     * （此前只有服务器日志）。字段注入照成本类既有姿势（{@code payService}/{@code notifyService} 都是
     * {@code @Autowired} 字段，无构造器）——<b>实测无环</b>：{@code AdminServiceImpl} 只依赖 mapper 与
     * {@code CouponService}，其传递闭包不回到本控制器（同目录 {@code AdminController} 才是它的调用方），
     * 且 Spring 的单例字段注入本身就允许环，不会重演 F-21.7 那类条件装配起不来的事故。
     * 全仓审计只有这一个 writer（{@code AdminService#recordNotifyReject} 复用
     * {@code AdminServiceImpl#writeAudit} 那唯一一份写入核心）。
     */
    @Autowired
    private AdminService adminService;

    /**
     * 客户端 IP 取值口径开关：<b>读的是 {@code RateLimitInterceptor:53} 与
     * {@code AdminAuthServiceImpl:173} 已有的那一枚键</b>（{@code rate-limit.trust-proxy}，默认 false），
     * 不新增键、不改任何 yml。
     */
    @Value("${rate-limit.trust-proxy:false}")
    private boolean trustProxy;

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
     * 微信支付 v3 支付结果回调（F-21.6 Step 2b 真实现 → F-21.8 Step 2c 把编排下沉）：
     * 本方法是<b>映射层</b>——两步调用（{@code notifyService.accept(...)} →
     * {@code notifyService.settleInTransaction(...)}）加一条<b>具体类型</b>的 catch，把拒收结论转成真实 HTTP 码。
     * <b>验签 → 时间戳新鲜度 → 解密 → 三比对 → 结算</b>的判定顺序活在
     * {@code com.fresh.pay.WxPayNotifyService} 里，本文件不再出现任何判定分支与结算调用。
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
     *   <tr><td>时间戳与服务器时刻偏差 &gt; 300 秒，或根本不是秒级 epoch（Step 2c C-3 新增分支：官方防重放窗口 5 分钟，判定位置在验签之后、解密之前）</td><td>400</td><td>FAIL / 微信支付回调时间戳超出允许范围</td></tr>
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
     * 三比对全过才调 {@code WxPayNotifyService#settleInTransaction}，它里面就是那枚既有接缝
     * {@code PaymentSettlement#settle(Order, LocalDateTime)}——置态 / 销量累加 /
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
     * ② 时间戳新鲜度（官方防重放窗口 5 分钟）<b>已于 Step 2c 落地</b>：{@code |now - Wechatpay-Timestamp| > 300 秒}
     *    即真实 400（「微信支付回调时间戳超出允许范围」），非数字时间戳同样 400；判定位置在<b>验签之后、解密之前</b>
     *    ——验签前拒会让一个伪造头就能打出 400，而能拿出真实签名的人本来就有重放能力，
     *    窗口主要挡的是「旧通知被再次投递」这一类（放在验签后既不放松前置门槛，也不给畸形头多一次取证书的机会）。
     * ③ payTime 口径<b>已于 Step 2c 改为渠道权威时刻</b>：取解密出的 {@code transaction.success_time} 经
     *    {@code OffsetDateTime.parse(...).toLocalDateTime()}（{@code +08:00} 与 {@code Z} 两种形态 JDK 17 实测支持）。
     *    <b>缺失或解析失败不拒收</b>——支付事实已由验签 + 三比对认定，拒了等于把真实扣款打成丢单，
     *    故回落 {@code LocalDateTime.now()} 并 log.warn（{@code orders.pay_time} 与 {@code success_time} 同为秒级，
     *    精度口径与 mock 链路一致，不引入纳秒尾巴）。
     * ④ 事务入口<b>已于 Step 2c 真实存在</b>：本方法<b>自己不标</b> {@code @Transactional}，结算走
     *    {@code WxPayNotifyService#settleInTransaction}（那枚方法上标了 Spring 的
     *    {@code org.springframework.transaction.annotation.Transactional}）。
     *    为什么标在方法上而不是标在 {@code accept} 里：{@code @Transactional} 靠代理生效，
     *    同类内部 {@code this.xxx()} 不经过代理 → 注解完全无效，所以它必须落在<b>由本方法从外部调用</b>的公开方法上；
     *    而把整段判定包进事务只会让 5xx 应答落进「到底回滚了什么」的歧义里，故 {@code accept} 保持无事务。
     *    于是失败路径是：异常穿出 {@code settleInTransaction} 的代理边界（代理先整单回滚）→ 本方法 catch
     *    {@code WxPayNotifyRejectException} → 应答真实 5xx → 微信按重试节奏重投 → 重投经 {@code updated==0} 幂等收敛。
     *    Step 2b 那个残留窗口（settle 多条 UPDATE 之间宕机 → {@code pay_status=1} 而无取货码/未计销量）自此消掉。
     *    <b>取舍如实登记</b>：catch 只认 {@code WxPayNotifyRejectException} 这一枚具体类型，不许
     *    {@code catch (RuntimeException)} 兜一切（那会把 NPE 一类编程错误洗成「400 拒收」）；
     *    万一还有未受控异常漏出来，它会冒到 {@code GlobalExceptionHandler} 表现为 HTTP 200——
     *    这是已知取舍，本批不顺手改兜底器。
     * ⑤ <b>查单兜底仍未做</b>（不是遗漏，是 F-21.8 §0.2 的裁定）：{@code GET /v3/pay/transactions/out-trade-no/{no}}
     *    要商户 API 证书私钥才能签名，无凭据时一行都跑不起来；写出来只会得到「编译通过但从未被执行过的对账代码」，
     *    它会在 prod 里静默什么都不做——比不写更危险。连同 {@code WxPayCertificateSource} 的真实实现一起
     *    归入「凭据到手后」那批。回调拒收留痕（L-21-2）<b>已于 F-44 落地</b>：本方法的 catch 是全仓唯一写入点，
     *    按 {@code admin_audit_log}（平台侧动作的归属表，依据 {@code init.sql:392} 的 ops_log 表注释）
     *    落 {@code actor=system:wx-notify / action=pay.notify_reject / target_type=order /
     *    target_id=订单号或 '-' / detail={"code","httpStatus"} 两键}，运营在 front-admin 审计页直接可查；
     *    判定层那 18 条 reject 分支一处都不自己写，成功结算路径一行都不写。
     */
    @PostMapping("/{orderId}/wx-notify")
    public ResponseEntity<Map<String, String>> wxNotify(@PathVariable("orderId") String orderId,
                                                        @RequestBody(required = false) String body,
                                                        @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
                                                        @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
                                                        @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
                                                        @RequestHeader(value = "Wechatpay-Serial", required = false) String serial) {
        // 映射层：判定与结算两步都在 WxPayNotifyService 里（accept 只判定、settleInTransaction 才是事务入口），
        // 本方法一条判定分支都不写、一次结算调用都不出现——于是「回滚」与「应答」的先后顺序由代理边界天然保证。
        try {
            WxPayNotifyService.Accepted accepted = notifyService.accept(
                    orderId, body, timestamp, nonce, signature, serial, wxpayMchid, wxpayApiV3Key);
            // 重放到这里时 updated==0 → 接缝只回读、零副作用，照样回 200（幂等，见方法注释的分工段）
            notifyService.settleInTransaction(accepted.order(), accepted.payTime());
        } catch (WxPayNotifyRejectException e) {
            // **具体类型**才 catch：绝不用 catch (RuntimeException) 兜一切（那会把 NPE 一类编程错误洗成「400 拒收」）。
            // 走到这里说明异常已穿出 settleInTransaction 的代理边界（该回滚的早已回滚），应答可放心给 4xx/5xx。
            //
            // —— F-44：拒收留痕。全仓唯一写入点：判定层 18 条 reject 分支 + 结算包装那 1 条，
            //    都只在这一个 catch 里落审计，绝不在各 throw 点各写一遍（两处写 = 两套口径）。
            //    成功结算路径一行都不写，否则审计表被正常流量灌满、这个功能立刻失去可用性。
            //
            //    【为什么整段必须包在 try/catch 里、失败只 log.error】钉死在这里，不许被"顺手加个兜底"改掉：
            //    本端点的 HTTP 码是「已扣款订单不丢单」的唯一保障（200/204 = 微信永久停止重发；失败必须非 200，
            //    而它的重试节奏约 24 小时 / 15 次之后就放弃）。审计写挂了如果连带把应答改掉，
            //    就等于让一次本可重试的投递变成永久丢单——那比"少一行审计"严重两个数量级。
            //    catch 的是 RuntimeException（不是 Exception）：与既有取舍一致，Error 一类不许在这里被吞。
            try {
                adminService.recordNotifyReject(e.getCode().name(), e.getHttpStatus().value(), orderId, clientIp());
            } catch (RuntimeException auditFailure) {
                log.error("微信支付回调拒收审计写入失败（不影响对微信的应答）: orderId={}, code={}, httpStatus={}",
                        orderId, e.getCode(), e.getHttpStatus().value(), auditFailure);
            }
            return ResponseEntity.status(e.getHttpStatus())
                    .body(Map.of("code", "FAIL", "message", e.getMessage()));
        }
        // 成功：200 且不带业务 JSON（官方：200 或 204 都算正常接收，微信据此停止重发）
        return ResponseEntity.ok().build();
    }

    /**
     * 客户端 IP（F-44 拒收留痕用）——<b>逐条镜像 {@code RateLimitInterceptor#clientIp} 与
     * {@code AdminAuthServiceImpl#clientIp} 那两处的既有口径</b>：默认直取
     * {@code request.getRemoteAddr()}（TCP 对端地址，不可伪造）；仅 {@code rate-limit.trust-proxy=true}
     * （显式声明部署在可信反代后）才解析 {@code X-Forwarded-For} 首跳。读的是同一枚既有键，
     * <b>不新增配置、不改任何 yml</b>，也不新造第三种口径（比如无条件信 XFF，那早被实测过可完全绕过）。
     *
     * <p>request 的拿法走 {@code RequestContextHolder}（本仓 Service/非端点签名处取 request 的既有姿势，
     * 见 {@code AdminServiceImpl#currentIp}）——这样本端点的方法签名一个字不用动，
     * 「六枚 String 入参 + 返回 ResponseEntity」那条反射形态断言（{@code WxPayNotifyTest}）也继续成立。
     * 取不到（离线单测、非请求线程）就回 null：{@code admin_audit_log.ip} 是可空列，
     * <b>绝不为审计去猜一个假 IP</b>。
     *
     * <p>【债登记】本仓第 4 份同族实现（第 1 份 {@code RateLimitInterceptor:129}、第 2 份
     * {@code AdminAuthServiceImpl:384}、第 3 份 {@code AdminServiceImpl#currentIp} 不解析 XFF 也不受
     * trust-proxy 控制），与那两处同样指向「抽公共工具随 G-07」——越界改存量链路反而扩大回归面。
     */
    private String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
