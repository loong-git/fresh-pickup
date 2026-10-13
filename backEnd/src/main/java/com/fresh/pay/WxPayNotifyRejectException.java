package com.fresh.pay;

import org.springframework.http.HttpStatus;

/**
 * 微信支付回调的「拒收承载」异常（F-21.8 Step 2c / C-1b）：
 * 只服务 {@code POST /api/pay/{orderId}/wx-notify} 这一个端点，用来把
 * {@link WxPayNotifyService#accept} 的判定结论（**该回哪个 HTTP 码 + 哪句文案**）跨过方法边界送到 controller。
 *
 * <h3>为什么要有它（而不是复用 {@code BizException}）</h3>
 * 本端点是全仓唯一「HTTP 状态码即语义」的地方（D-21-4：微信只看状态码，失败也回 200 = 它以为投递成功
 * = 永久停止重发 = 丢单）。而 Step 2c 把编排下沉进 {@link WxPayNotifyService} 之后，
 * {@code accept} 只判定、不在 Web 层、也不再返回 {@code ResponseEntity}，失败只能靠抛。
 * 若复用 {@code BizException}：它会被 {@code GlobalExceptionHandler} 兜底洗成 HTTP 200 + {@code R{code:400}}，
 * 正是 F-21 立项要消灭的那类谎报。故本异常的存在意义就是「带状态码」且**绝不能被兜底器认识**。
 *
 * <h3>契约（越界即返工）</h3>
 * <ul>
 *   <li>唯一 catch 点：{@code PayController#wxNotify}（按具体类型 catch，见 C-5）；</li>
 *   <li>不进 {@code GlobalExceptionHandler}、不被其它端点/服务抛出、不与 {@code BizException} 发生关系；</li>
 *   <li>{@code message} 就是应答给微信的 {@code body.message}，文案是钉死的 16 条之一（不得新造、不得改写）；</li>
 *   <li>抛出点全在 settle 之前或在事务方法边界上（结算失败那条由
 *       {@link WxPayNotifyService#settleInTransaction} 包装），
 *       异常穿出 {@code @Transactional} 代理边界 → 先回滚 → controller 才 catch → 再应答 5xx。</li>
 * </ul>
 */
public class WxPayNotifyRejectException extends RuntimeException {

    /** 该回的 HTTP 状态码（应答表钉死的映射：400 / 401 / 404 / 500 / 503），不允许由调用方再自行判定 */
    private final HttpStatus httpStatus;

    public WxPayNotifyRejectException(HttpStatus httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /** 带上底层原因（DB 抖动 / 解密异常一类）：日志要看得到因果，应答里依旧只出文案（不透出堆栈） */
    public WxPayNotifyRejectException(HttpStatus httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
