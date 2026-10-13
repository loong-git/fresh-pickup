package com.fresh.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.controller.PayController;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.service.InviteService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 微信支付回调端点端到端单测（F-21.6 Step 2b / D-21-3 断言集，**全部离线、不连库、不起 Spring 容器**）：
 * 测试代码扮演「微信服务器」，把 PayController#wxNotify 当普通方法直接打。
 *
 * 测试床（D-21-3 修订版：密钥一律运行时自造，src/test/resources 里不落任何 PEM/密钥文件）：
 * - 两对运行时生成的 2048 位 RSA：一对扮**平台证书**（签回调、{@link WxPayCertificateSource} 供公钥），
 *   一对扮**商户 API 证书**（专门用来制造「拿错钥匙签」那条失败）；
 * - APIv3 密钥用固定测试常量（32 位 ASCII）加密 {@code resource.ciphertext}（{@link WxpayAead#encrypt}），
 *   另一把常量专门用来制造「错 key 解不开」那条失败；
 * - 协作者：{@link OrderMapper}/{@link OrderItemMapper}/{@link DishMapper}/{@link InviteService} 全 mock，
 *   但 {@link PaymentSettlement} 用**真实例**（照 {@code PaymentSettlementTest} 那套 mock mapper 喂法）——
 *   这样「重放同一条通知 → 幂等、零二次副作用」不是断言 mock 调用次数，而是**跑到了接缝的条件更新上**；
 * - 假库：{@code dbRow} 就是那一行 orders，条件更新语义由 {@code pay_status==0 才生效} 复刻，
 *   销量增量、取货码写入都只在真语句走到时才动，于是「订单状态/销量/取货码一字不变」是可读的断言。
 *
 * 断言集（逐条对应 D-21-3 与 F-21.6 的官方纠偏）：
 * ① 合法通知 → HTTP 200 且**不带业务 JSON**、结算四件事各恰一次；
 * ② 重放同一条 → 幂等（接缝被再次调用但 {@code updated==0} 回读、销量/取货码/payTime 一个都不再变）；
 * ③ 篡改 {@code amount.total} → 真实 4xx 且订单一字不变；
 * ④ {@code out_trade_no} 与路径 orderId 不一致 → 拒（且连查库都不发生）；
 * ⑤ {@code trade_state != SUCCESS} → 拒；
 * ⑥ 签名头缺失 → 拒、零副作用；⑦ 验签失败（错钥匙 / 报文被动过 / 签名头畸形）→ 拒、零副作用；
 * ⑧ 用错的 APIv3 密钥加密的报文 → 解密失败 → 拒；
 * ⑨ 订单查不到 → 真实 404（不置态）；
 * ⑩ 未配置（mchid 或 api-v3-key 空）→ **真实 4xx，绝不 200**（骨架期那条 R.fail(400) 走 HTTP 200 正是丢单根因）；
 * ⑪ 平台证书取不到 / 查单与结算瞬时故障 → 真实 5xx（可让微信重投），同样不是 200；
 * ⑫ 金额算法：DECIMAL 右移两位（8.80 必须等于 880 分，double×100 会得 879/881）；
 *   库内出现三位小数 → 报错而不是悄悄舍入（{@code RoundingMode.UNNECESSARY}）。
 *
 * 未测成（如实登记）：与 api.mch.weixin.qq.com 的真联调、真实平台证书下载/轮换、
 * {@code wechatpay-java} 之外的报文实样、@Transactional 回滚语义（本批回调链路未标该注解，见 wxNotify 注释④）。
 */
class WxPayNotifyTest {

    private static final String MCHID = "1900000001";
    /** 测试常量 APIv3 密钥（32 位 ASCII，不属于任何环境） */
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";
    /** 另一把：扮演「商户侧配错了 APIv3 密钥」 */
    private static final String WRONG_API_V3_KEY = "fedcba9876543210fedcba9876543210";
    private static final String SERIAL = "PLATFORM-CERT-SERIAL-TEST-01";
    private static final String ORDER_ID = "2104094865859330050";
    private static final String OTHER_ORDER_ID = "2104094865859339999";
    private static final Long USER_ID = 4L;
    private static final String RESOURCE_NONCE = "abc123def456";
    private static final String AAD = "transaction";
    private static final long DISH_ID = 44L;
    private static final int QUANTITY = 2;
    /** 合法通知的应付分数（与 unpaidOrder 的 15.00 元一一对应） */
    private static final int CORRECT_FEN = 1500;

    private static KeyPair merchantKeyPair;
    private static KeyPair platformKeyPair;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final InviteService inviteService = mock(InviteService.class);

    private final AtomicInteger soldCountDelta = new AtomicInteger();
    private Order dbRow;

    private PaymentSettlement settlement;

    /** 测试实现：只认自造的那把平台公钥；序列号不认就抛（真实骨架实现恒抛未配置，同性质 → 端点必须回 5xx） */
    private final WxPayCertificateSource certificateSource = serial -> {
        if (!SERIAL.equals(serial)) {
            throw new IllegalStateException("未知平台证书序列号：" + serial);
        }
        return platformKeyPair.getPublic();
    };

    /**
     * 真实 PaymentSettlement 用到 {@code LambdaUpdateWrapper.set(Order::getPayStatus, 1)}，那是**即时**解析列名、
     * 依赖实体 TableInfo 的（{@code PaymentSettlementTest#primeMybatisPlusColumnCache} 同因）：
     * 本测试不起容器也不连库，故手工灌一次内存缓存。
     */
    @BeforeAll
    static void generateKeysAndPrimeMybatisPlusColumnCache() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        merchantKeyPair = generator.generateKeyPair();
        platformKeyPair = generator.generateKeyPair();

        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        settlement = new PaymentSettlement(orderMapper, orderItemMapper, dishMapper, inviteService);
        dbRow = unpaidOrder(ORDER_ID, "15.00");

        when(orderMapper.selectById(any())).thenAnswer(inv -> ORDER_ID.equals(inv.getArgument(0)) ? dbRow : null);
        // 条件更新语义复刻（幂等的全部依据）：pay_status=0 才生效，二次进来影响行数 0
        when(orderMapper.update(isNull(), any())).thenAnswer(inv -> {
            if (dbRow.getPayStatus() != null && dbRow.getPayStatus() == 0) {
                dbRow.setPayStatus(1);
                dbRow.setPayTime(LocalDateTime.now());
                return 1;
            }
            return 0;
        });
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem()));
        when(orderMapper.countByPickupCode(anyString())).thenReturn(0);
        when(orderMapper.attachPickupCode(anyString(), anyString())).thenAnswer(inv -> {
            if (dbRow.getPickupCode() == null) {
                dbRow.setPickupCode(inv.getArgument(1));
                return 1;
            }
            return 0;
        });
        when(dishMapper.increaseSoldCount(anyLong(), anyInt())).thenAnswer(inv -> {
            soldCountDelta.addAndGet(inv.getArgument(1, Integer.class));
            return 1;
        });
    }

    // —— fixture ——

    /** 待支付订单行（pay_status=0 / 无取货码 / 未取消） */
    private Order unpaidOrder(String id, String totalPrice) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(USER_ID);
        o.setStatus("pending_pickup");
        o.setPayStatus(0);
        o.setTotalPrice(new BigDecimal(totalPrice));
        return o;
    }

    private OrderItem orderItem() {
        OrderItem it = new OrderItem();
        it.setOrderId(ORDER_ID);
        it.setDishId(DISH_ID);
        it.setQuantity(QUANTITY);
        return it;
    }

    private PayController controller() {
        return controller(MCHID, API_V3_KEY);
    }

    private PayController controller(String mchid, String apiV3Key) {
        PayController c = new PayController();
        ReflectionTestUtils.setField(c, "wxpayMchid", mchid);
        ReflectionTestUtils.setField(c, "wxpayApiV3Key", apiV3Key);
        ReflectionTestUtils.setField(c, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(c, "settlement", settlement);
        ReflectionTestUtils.setField(c, "certificateSource", certificateSource);
        return c;
    }

    /** 一条通知的可控要素（签名一定和 body 自洽，测试只改自己关心的那一项） */
    private record Notification(String body, String timestamp, String nonce, String signature, String serial) {
    }

    /** 默认参数 = 一条完全合法的通知 */
    private Notification notification(String outTradeNo, int amountFen, String tradeState) throws Exception {
        return notification(outTradeNo, amountFen, tradeState, API_V3_KEY, platformKeyPair.getPrivate());
    }

    /**
     * 扮演微信服务器：业务 JSON → APIv3 密钥 AES-256-GCM 加密进 {@code resource.ciphertext} →
     * 外层通知报文 → 平台私钥签「时间戳\n随机串\n原始报文\n」。
     */
    private Notification notification(String outTradeNo, int amountFen, String tradeState,
                                      String encryptKey, PrivateKey signingKey) throws Exception {
        String transaction = "{\"appid\":\"wxtestappid000000000\",\"mchid\":\"" + MCHID + "\","
                + "\"out_trade_no\":\"" + outTradeNo + "\","
                + "\"transaction_id\":\"420000202610131006000001\","
                + "\"trade_state\":\"" + tradeState + "\",\"trade_state_desc\":\"支付成功\","
                + "\"success_time\":\"2026-10-13T10:06:30+08:00\","
                + "\"amount\":{\"total\":" + amountFen + ",\"payer_total\":" + amountFen + ",\"currency\":\"CNY\"},"
                + "\"payer\":{\"openid\":\"o-test-openid\"}}";
        String ciphertext = WxpayAead.encrypt(encryptKey, RESOURCE_NONCE, AAD, transaction);
        String body = "{\"id\":\"test-notify-" + outTradeNo + "\","
                + "\"create_time\":\"2026-10-13T10:06:30+08:00\","
                + "\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"transaction\",\"summary\":\"支付成功\","
                + "\"resource\":{\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"ciphertext\":\"" + ciphertext + "\","
                + "\"associated_data\":\"" + AAD + "\",\"nonce\":\"" + RESOURCE_NONCE + "\"}}";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = WxpaySignature.sign(
                WxpaySignature.buildVerifyMessage(timestamp, RESOURCE_NONCE, body), signingKey);
        return new Notification(body, timestamp, RESOURCE_NONCE, signature, SERIAL);
    }

    private ResponseEntity<Map<String, String>> post(String pathOrderId, Notification n) {
        return controller().wxNotify(pathOrderId, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());
    }

    /** 失败应答必须是 {@code {"code":"FAIL","message":"…"}}（官方 v3 失败形态，且 HTTP 状态码已非 2xx） */
    private void assertFailBody(ResponseEntity<Map<String, String>> response, String expectedMessage) {
        assertNotNull(response.getBody(), "失败应答要带 body 供排查定性");
        assertEquals("FAIL", response.getBody().get("code"));
        assertEquals(expectedMessage, response.getBody().get("message"));
        assertNotEquals(HttpStatus.OK.value(), response.getStatusCode().value(),
                "任何失败都不能回 200：微信只看 HTTP 状态码，200 = 永久停止重发 = 丢单");
    }

    /** 「零副作用」共用断言：一行都没被写过，接缝一次都没进 */
    private void assertOrderUntouched() {
        assertEquals(Integer.valueOf(0), dbRow.getPayStatus(), "pay_status 必须还是 0");
        assertNull(dbRow.getPayTime(), "pay_time 必须还是 NULL");
        assertNull(dbRow.getPickupCode(), "取货码必须还是 NULL");
        assertEquals(0, soldCountDelta.get(), "销量一分都不该累加");
        verify(orderMapper, never()).update(any(), any());
        verify(orderMapper, never()).attachPickupCode(anyString(), anyString());
        verify(inviteService, never()).onOrderPaid(any());
        verify(dishMapper, never()).increaseSoldCount(anyLong(), anyInt());
    }

    // —— ① 合法通知：200 且不带业务 JSON，结算恰一次 ——

    @Test
    void validNotificationIsAcceptedAndSettledExactlyOnce() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, CORRECT_FEN, "SUCCESS"));

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        assertNull(response.getBody(), "成功应答不带业务 JSON（官方只看 200/204，不看体）");
        // 结算进了接缝且只进了一次：条件更新一次、销量一次、取货码一次、邀请一次
        verify(orderMapper, times(1)).update(isNull(), any());
        assertEquals(QUANTITY, soldCountDelta.get());
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
        assertNotNull(dbRow.getPickupCode());
        assertTrue(dbRow.getPickupCode().matches("\\d{6}"), "取货码 6 位数字（D-3）：" + dbRow.getPickupCode());
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
        assertNotNull(dbRow.getPayTime());
    }

    // —— ② 重放同一条：幂等、无二次副作用 ——

    /**
     * 选定的口径（D-21-3 第 2 条给了两种，这里取「第一条」并顺带把第二条也钉上）：
     * 同一条通知**真的再走一遍** handler → 结算接缝被第二次调用、条件更新 {@code updated==0} 走回读分支，
     * 同时断言「订单状态 / 销量 / 取货码 / payTime 四项一字不变」——两条口径一起钉，重放才是可证的幂等。
     */
    @Test
    void replayingTheSameNotificationIsIdempotentWithNoSecondSideEffect() throws Exception {
        Notification notification = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> first = post(ORDER_ID, notification);
        String codeAfterFirst = dbRow.getPickupCode();
        LocalDateTime payTimeAfterFirst = dbRow.getPayTime();
        assertEquals(HttpStatus.OK.value(), first.getStatusCode().value());

        ResponseEntity<Map<String, String>> replay = post(ORDER_ID, notification);

        assertEquals(HttpStatus.OK.value(), replay.getStatusCode().value(), "重放也要回 200：这一笔确实已经处理成功，让微信停止重发");
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
        assertEquals(codeAfterFirst, dbRow.getPickupCode(), "二次支付不换码（D-4 核销凭据必须稳定）");
        assertEquals(payTimeAfterFirst, dbRow.getPayTime(), "pay_time 不被第二次通知改写");
        assertEquals(QUANTITY, soldCountDelta.get(), "销量只加一次（第二次不能重复累加）");
        // 接缝被调用两次（第二次 updated==0 → 只回读），四件副作用各只发生一次
        verify(orderMapper, times(2)).update(isNull(), any());
        verify(orderMapper, times(1)).attachPickupCode(eq(ORDER_ID), anyString());
        verify(orderMapper, times(1)).countByPickupCode(anyString());
        verify(dishMapper, times(1)).increaseSoldCount(DISH_ID, QUANTITY);
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
    }

    // —— ③ 篡改金额 ——

    @Test
    void tamperedAmountTotalIsRejectedAndOrderStaysUntouched() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, CORRECT_FEN + 1, "SUCCESS"));

        assertTrue(response.getStatusCode().is4xxClientError(), "篡改金额必须真实 4xx，实际 " + response.getStatusCode());
        assertFailBody(response, "微信支付回调金额与订单不符");
        assertOrderUntouched();
        // 金额比对用的是解密后的业务字段，验签仍然通过——说明「验签过 ≠ 可以入账」，三比对才是闸门
        verify(orderMapper, times(1)).selectById(ORDER_ID);
    }

    @Test
    void amountCompareUsesDecimalShiftNotFloatingPointMultiply() throws Exception {
        dbRow = unpaidOrder(ORDER_ID, "8.80");   // double 8.80×100 = 879.9999…，int 截断即 879 → 会误拒真通知

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, 880, "SUCCESS"));

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(), "8.80 元必须等于 880 分（禁止 double×100）");
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
    }

    @Test
    void unexpectedThirdDecimalIsReportedNotSilentlyRounded() throws Exception {
        dbRow = unpaidOrder(ORDER_ID, "1.005");  // 库里出现三位小数 = 数据事故，RoundingMode.UNNECESSARY 必须报错

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, 101, "SUCCESS"));

        assertTrue(response.getStatusCode().is5xxServerError(), "标度异常是服务端问题（500），不是「报文不合法」：" + response.getStatusCode());
        assertFailBody(response, "订单金额无法比对");
        assertOrderUntouched();
    }

    // —— ④ out_trade_no 与路径 orderId 不一致 ——

    @Test
    void mismatchedOutTradeNoIsRejectedWithoutLookingUpTheOrder() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(OTHER_ORDER_ID, CORRECT_FEN, "SUCCESS"));

        assertTrue(response.getStatusCode().is4xxClientError());
        assertFailBody(response, "微信支付回调订单号不匹配");
        assertOrderUntouched();
        // 拼别人单号的探测：比对在查库之前，连 SELECT 都不发生
        verify(orderMapper, never()).selectById(any());
    }

    // —— ⑤ trade_state != SUCCESS ——

    @ParameterizedTest
    @ValueSource(strings = {"NOTPAY", "PAYERROR", "REFUND", "CLOSED", "USERPAYING"})
    void nonSuccessTradeStateIsRejected(String tradeState) throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, CORRECT_FEN, tradeState));

        assertTrue(response.getStatusCode().is4xxClientError(), "非成功态必须拒，实际 " + response.getStatusCode());
        assertFailBody(response, "微信支付结果非成功状态");
        assertOrderUntouched();
    }

    // —— ⑥ 签名头缺失 ——

    @Test
    void missingSignatureHeadersAreRejectedWithZeroSideEffects() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
        PayController c = controller();

        ResponseEntity<Map<String, String>> noSignature =
                c.wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), null, n.serial());
        ResponseEntity<Map<String, String>> noTimestamp =
                c.wxNotify(ORDER_ID, n.body(), null, n.nonce(), n.signature(), n.serial());
        ResponseEntity<Map<String, String>> noNonce =
                c.wxNotify(ORDER_ID, n.body(), n.timestamp(), null, n.signature(), n.serial());
        ResponseEntity<Map<String, String>> noSerial =
                c.wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), null);
        ResponseEntity<Map<String, String>> noBody =
                c.wxNotify(ORDER_ID, null, n.timestamp(), n.nonce(), n.signature(), n.serial());
        // 空串形态（有的中间件把缺失头变成 ""）与 null 同判
        ResponseEntity<Map<String, String>> blankSignature =
                c.wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), "   ", n.serial());

        for (ResponseEntity<Map<String, String>> r : List.of(noSignature, noTimestamp, noNonce, noSerial, noBody, blankSignature)) {
            assertFailBody(r, "微信支付回调缺少验签头");
            assertEquals(HttpStatus.BAD_REQUEST.value(), r.getStatusCode().value());
        }
        // 零副作用：缺头连查库都不发生（更谈不上验签/解密/结算）
        verify(orderMapper, never()).selectById(any());
        verifyNoInteractions(dishMapper, inviteService);
        assertOrderUntouched();
    }

    // —— ⑦ 验签失败 ——

    @Test
    void signatureFromAnotherPrivateKeyIsRejected() throws Exception {
        // 攻击者（这里用商户自己那对密钥冒充）没有平台私钥，签出来的东西验不过
        Notification forged = notification(ORDER_ID, CORRECT_FEN, "SUCCESS", API_V3_KEY, merchantKeyPair.getPrivate());

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, forged);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调验签失败");
        assertOrderUntouched();
    }

    @Test
    void bodyTamperedAfterSigningIsRejected() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        // 原样签名 + 报文尾加一个空格（验签名串里的 body 变了）：改一个字都不过
        ResponseEntity<Map<String, String>> response =
                controller().wxNotify(ORDER_ID, n.body() + " ", n.timestamp(), n.nonce(), n.signature(), n.serial());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调验签失败");
        assertOrderUntouched();
    }

    @Test
    void malformedSignatureHeaderIsRejectedNotThrown() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = controller().wxNotify(ORDER_ID, n.body(), n.timestamp(),
                n.nonce(), "@@@不是合法 Base64@@@", n.serial());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调验签失败");
        assertOrderUntouched();
    }

    @Test
    void signedBodyThatIsNotJsonIsRejected() throws Exception {
        String body = "这一串根本不是 JSON";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = WxpaySignature.sign(
                WxpaySignature.buildVerifyMessage(timestamp, RESOURCE_NONCE, body), platformKeyPair.getPrivate());

        ResponseEntity<Map<String, String>> response =
                controller().wxNotify(ORDER_ID, body, timestamp, RESOURCE_NONCE, signature, SERIAL);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调报文不合法");
        assertOrderUntouched();
    }

    // —— ⑧ 错的 APIv3 密钥 → 解密失败 ——

    @Test
    void ciphertextEncryptedWithWrongApiV3KeyCannotBeDecryptedAndIsRejected() throws Exception {
        // 签名完全合法（平台私钥签的），只是 resource 用另一把 APIv3 密钥加密 → GCM tag 校验必炸
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS", WRONG_API_V3_KEY, platformKeyPair.getPrivate());

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, n);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调解密失败");
        assertOrderUntouched();
    }

    @Test
    void resourceAlgorithmMismatchIsRejectedBeforeDecrypting() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
        String swapped = n.body().replace("\"algorithm\":\"AEAD_AES_256_GCM\"", "\"algorithm\":\"AES_CBC\"");
        String signature = WxpaySignature.sign(
                WxpaySignature.buildVerifyMessage(n.timestamp(), n.nonce(), swapped), platformKeyPair.getPrivate());

        ResponseEntity<Map<String, String>> response =
                controller().wxNotify(ORDER_ID, swapped, n.timestamp(), n.nonce(), signature, n.serial());

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调加密报文不合法");
        assertOrderUntouched();
    }

    // —— ⑨ 订单查不到 ——

    @Test
    void unknownOrderIsReal404AndNothingIsWritten() throws Exception {
        ResponseEntity<Map<String, String>> response = post(OTHER_ORDER_ID, notification(OTHER_ORDER_ID, CORRECT_FEN, "SUCCESS"));

        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value(), "查不到单必须真实 4xx（骨架期这里也是 HTTP 200）");
        assertFailBody(response, "订单不存在");
        assertOrderUntouched();
    }

    @Test
    void cancelledOrderIsRejectedWithoutRevivingIt() throws Exception {
        dbRow.setStatus("cancelled");

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notification(ORDER_ID, CORRECT_FEN, "SUCCESS"));

        assertTrue(response.getStatusCode().is4xxClientError());
        assertFailBody(response, "订单已取消，无法支付");
        assertOrderUntouched();
    }

    // —— ⑩ 未配置：底线是「绝不能返回 200」 ——

    @Test
    void unconfiguredMerchantIdIsReal4xxNever200() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = controller("", API_V3_KEY)
                .wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付未配置");
        assertOrderUntouched();
        // 未配置这道闸门在验签之前：连平台证书都不去取（骨架实现恒抛未配置，取它只是白费一次）
        verify(orderMapper, never()).selectById(any());
    }

    @Test
    void unconfiguredApiV3KeyIsReal4xxNever200() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response =
                controller(MCHID, "").wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value(),
                "未配置状态下的骨架期行为是 HTTP 200（R.fail(400)）——那等于把「没配支付」伪装成「已收到」，本批改成真实 400");
        assertFailBody(response, "微信支付未配置");
        assertOrderUntouched();
        verify(orderMapper, never()).selectById(any());
    }

    // —— ⑪ 瞬时故障：必须真实 5xx，让微信按 15s/15s/30s/… 的节奏重投 ——

    @Test
    void unknownPlatformSerialIsServiceUnavailableNot200() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response =
                controller().wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), "SERIAL-NOT-CACHED");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getStatusCode().value(),
                "平台证书还没缓存上是瞬时状态，回 5xx 让微信重投（回 4xx 会误导成报文问题）");
        assertFailBody(response, "微信支付平台证书不可用");
        assertOrderUntouched();
    }

    @Test
    void orderLookupFailureIsReal500() throws Exception {
        when(orderMapper.selectById(any())).thenThrow(new QueryTimeoutException("数据库抖动"));
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, n);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调处理失败");
        assertEquals(Integer.valueOf(0), dbRow.getPayStatus());
    }

    @Test
    void settlementFailureIsReal500SoWeChatRetries() throws Exception {
        when(orderMapper.update(isNull(), any())).thenThrow(new QueryTimeoutException("数据库抖动"));
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, n);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调处理失败");
        // 结算没成 → 一行都没写成（本批回调链路未标 @Transactional，见类注释「未测成」段与 wxNotify 注释④）
        verify(orderMapper, times(1)).update(isNull(), any());
        assertEquals(0, soldCountDelta.get());
        verify(inviteService, never()).onOrderPaid(any());
    }

    // —— 收口：端点签名形状（信封例外的物理证据） ——

    /**
     * wxNotify 的返回类型必须是 {@code ResponseEntity}（能说话的状态码），而 mockPay 仍回 {@code R} 信封：
     * 「本仓唯一破例」这件事从此有反射断言兜着，将来谁把它改回 R 就是红。
     */
    @Test
    void wxNotifyBreaksTheEnvelopeWhileMockPayStillUsesIt() throws Exception {
        Method notify = PayController.class.getMethod("wxNotify", String.class, String.class,
                String.class, String.class, String.class, String.class);
        assertEquals(ResponseEntity.class, notify.getReturnType());

        Method mockPay = PayController.class.getMethod("mockPay", String.class, Long.class);
        assertEquals(com.fresh.common.R.class, mockPay.getReturnType(), "其余端点的统一信封一律不动");
    }
}
