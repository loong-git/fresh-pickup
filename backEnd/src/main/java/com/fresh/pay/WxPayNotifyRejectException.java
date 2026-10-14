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
 *
 * <h3>F-44 新增：{@link RejectCode code}（稳定 ASCII 拒收码，审计的唯一可筛选载体）</h3>
 * 本类原有两枚字段（{@code httpStatus} + {@code message}）都是<b>对外契约</b>，一字未动；
 * 新增的 {@code code} 只服务<b>对内留痕</b>——{@code PayController#wxNotify} 的那一处 catch 把它连同
 * {@code httpStatus} 写进 {@code admin_audit_log}，运营在 front-admin 审计页按
 * {@code actor=system:wx-notify} / {@code action=pay.notify_reject} 就能查到「哪一单、因为哪一条、回了什么码」。
 * <p>为什么必须有它而不是靠 {@code message}：文案是中文且<b>多条分支共用同一句</b>
 * （实测 19 个抛出点只有 16 句文案——「时间戳超出允许范围」「回调解密失败」「回调处理失败」各被两条分支用着），
 * 拿它当筛选条件必然歧义。{@code code} 与<b>原因</b>一一对应（{@link RejectCode} 逐枚钉死），
 * 且全 ASCII 下划线大写，能过运营端列表的形状闸门 {@code AdminV2ServiceImpl#FILTER_PATTERN}。
 * <p>新增分支必须同时新增一枚 code，<b>不许复用</b>既有枚举（复用 = 两条原因在同一枚筛选值下糊在一起，
 * 审计立刻失去可定位性）。
 */
public class WxPayNotifyRejectException extends RuntimeException {

    /** 该回的 HTTP 状态码（应答表钉死的映射：400 / 401 / 404 / 500 / 503），不允许由调用方再自行判定 */
    private final HttpStatus httpStatus;

    /**
     * 稳定 ASCII 拒收码（F-44，见 {@link RejectCode}）：与<b>拒收原因</b>一一对应，
     * 进 {@code admin_audit_log.detail.code}。构造点必须显式带入，不提供默认值——默认值等于漏码。
     */
    private final RejectCode code;

    public WxPayNotifyRejectException(RejectCode code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** 带上底层原因（DB 抖动 / 解密异常一类）：日志要看得到因果，应答里依旧只出文案（不透出堆栈） */
    public WxPayNotifyRejectException(RejectCode code, HttpStatus httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public RejectCode getCode() {
        return code;
    }

    /**
     * 拒收原因码（F-44）：{@code WxPayNotifyService} 的每一个 {@code reject(...)} 抛出点各占一枚，
     * <b>一一对应、绝不复用</b>（一条 code = 一条原因 = 审计里一个可精确定位的筛选值）。
     *
     * <p>命名风格照本仓既有枚举（{@code RateLimit.KeyType} 的 {@code USER}/{@code IP}：大写下划线、
     * 嵌套在宿主类里、不新开文件也不加 Spring Bean）。
     *
     * <p>【不变式】枚举<b>没有常量体</b>，且 {@code WxPayNotifyService} 的全部抛出点都是 {@code reject(code, status, msg)}
     * 的直接调用、无任何 code 集合/遍历逻辑 → 「一条拒收分支配一枚 code」这件事由
     * {@code WxPayNotifyTest#everyRejectBranchProducesExactlyOneAuditRow} 的反射断言守住
     * （{@code RejectCode.values().length} 必须等于 {@code accept} 的分支数 + 结算包装那 1 条，
     * 并且逐枚被真实驱动到）。将来若给本枚举加常量体/字段，那条断言会被编译期形态约束，届时需同步复核。
     *
     * <p>{@code init.sql} 的 {@code admin_audit_log.action} 是 VARCHAR(50)、{@code detail} 是 VARCHAR(500)，
     * 本组最长成员名 27 字符（{@code RESOURCE_ENVELOPE_INVALID}），装得下、不会被截断成半个码。
     */
    public enum RejectCode {
        /** {@code WxPayNotifyService:108}：{@code mchid} 或 {@code api-v3-key} 未配置（400） */
        WX_PAY_NOT_CONFIGURED,
        /** {@code :112}：原始报文或四个验签头任一缺失/纯空白（400） */
        NOTIFY_HEADERS_MISSING,
        /** {@code :122}：回调报文不是合法 JSON（400） */
        NOTIFY_BODY_NOT_JSON,
        /** {@code :132}：按 {@code Wechatpay-Serial} 取不到平台证书（503，可重试） */
        PLATFORM_CERT_UNAVAILABLE,
        /** {@code :138}：验签不通过（非平台私钥所签 / 报文被改 / 签名头不是合法 Base64）（401） */
        SIGNATURE_VERIFY_FAILED,
        /** {@code :151}：{@code Wechatpay-Timestamp} 不是秒级 epoch 字符串，无从判定新鲜度（400）。
         *  与 {@link #TIMESTAMP_OUT_OF_WINDOW} <b>同一句中文文案、同一枚 400</b>，但原因不同，故两枚码 */
        TIMESTAMP_NOT_EPOCH,
        /** {@code :157}：时间戳与服务器时刻偏差超过官方防重放窗口 300 秒（400） */
        TIMESTAMP_OUT_OF_WINDOW,
        /** {@code :165}：{@code resource.algorithm} 不是 AEAD_AES_256_GCM 或缺 {@code ciphertext}（400） */
        RESOURCE_ENVELOPE_INVALID,
        /** {@code :175}：AEAD 解密抛错（APIv3 密钥不符 / 密文或 AAD 被篡改 / nonce 长度不合规）（400）。
         *  与 {@link #DECRYPTED_PAYLOAD_NOT_JSON} <b>同一句中文文案、同一枚 400</b>，但原因不同，故两枚码 */
        AEAD_DECRYPT_FAILED,
        /** {@code :182}：解密出来的明文不是合法 JSON，取不出交易字段（400） */
        DECRYPTED_PAYLOAD_NOT_JSON,
        /** {@code :190}：{@code out_trade_no} 与路径 {@code orderId} 不一致（400） */
        OUT_TRADE_NO_MISMATCH,
        /** {@code :195}：{@code trade_state != SUCCESS}（400） */
        TRADE_STATE_NOT_SUCCESS,
        /** {@code :199}：解密报文里没有数值型 {@code amount.total}（400） */
        AMOUNT_TOTAL_MISSING,
        /** {@code :209}：查单（{@code selectById}）抛错，DB 抖动一类（500，可重试）。
         *  与 {@link #SETTLEMENT_FAILED} <b>同一句中文文案、同一枚 500</b>，但一个在结算前、一个在结算内，故两枚码 */
        ORDER_LOOKUP_FAILED,
        /** {@code :215}：验签与解密都过了却查无此单（404） */
        ORDER_NOT_FOUND,
        /** {@code :221}：本地单已取消，不自动结算复活（400，需人工退款） */
        ORDER_CANCELLED,
        /** {@code :230}：库内 {@code total_price} 标度异常（三位小数一类），金额比对无法进行（500，服务端问题） */
        ORDER_AMOUNT_UNCOMPARABLE,
        /** {@code :234}：通知金额与订单应付不等额（400） */
        AMOUNT_MISMATCH,
        /** {@code WxPayNotifyService#settleInTransaction}：结算接缝内部异常（500，微信重投后走幂等收敛） */
        SETTLEMENT_FAILED
    }
}
