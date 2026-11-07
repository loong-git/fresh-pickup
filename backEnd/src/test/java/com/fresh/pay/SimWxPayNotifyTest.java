package com.fresh.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.pay.WxPayNotifyRejectException.RejectCode;
import com.fresh.service.InviteService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import static org.mockito.Mockito.when;

/**
 * 模拟微信支付链路单测（P10-5 轨道 B / B1 任务书第 2 条，照 {@code WxPayNotifyTest} 先例——
 * <b>全部离线、不连库、不起 Spring 容器、不发真实 HTTP</b>）：用产品侧的 {@link SimWxPaySupport}
 * （运行时 RSA 密钥对扮模拟平台证书 + 商户 API 证书、APIv3 密钥 AES-GCM 加密）构造「微信 v3 回调」，
 * 直接打 {@link WxPayNotifyService#accept}/{@code settleInTransaction}——
 * 「模拟签名的回调必须过真实验签/解密/三比对，入账必须走唯一接缝」就在这一层证。
 *
 * 断言集（对应任务书三条）：
 * ① sim 密钥对构造的<b>合法签名回调</b> → accept 通过（产物=订单行+payTime）且
 *    {@code settleInTransaction} 置态成功（pay_status=1 + 6 位取货码 + 销量累加 + 邀请挂钩各恰一次）；
 * ② <b>篡改金额</b>（amountFen 与订单应付差 1 分，签名/加密均自洽）→ 拒：真实 400
 *    「微信支付回调金额与订单不符」，订单一行未动——证明判定用的是 accept 的三比对，不是「谁都能签」；
 * ③ <b>坏签名</b>两形态：报文签名后被动过一个字节 → 401「微信支付回调验签失败」；
 *    拿非模拟平台私钥（本测试自造的一对）签同一段报文 → 同样 401，订单零副作用；
 * ④ {@link SimCertificateSource} 只认 SIM 序列号：未知序列号 → 503「微信支付平台证书不可用」
 *    （RuntimeException 被 accept 包成 5xx 让投递方重投，与 Unconfigured 同性质）；
 * ⑤ sim-create 的模拟 jsapi 五参数：官方五键形状齐 + paySign 可用模拟商户公钥验过
 *    （签名串=buildRequestMessage("POST","/v3/pay/transactions/jsapi",…) 官方口径）；
 * ⑥ APIv3 密钥长度不对 → buildPaidNotify fail-fast 抛 BizException（不发注定被拒的回调）。
 *
 * 测试床（逐字照 WxPayNotifyTest）：协作 mapper 全 mock、PaymentSettlement 用真实例、
 * 条件更新语义由 {@code pay_status==0 才生效} 的桩复刻；MybatisPlus 列缓存手工灌一次
 * （{@code LambdaUpdateWrapper.set(Order::getPayStatus,…)} 是即时解析，无容器就必须先喂 TableInfo）。
 * 未测成（如实登记）：sim-pay 端点的 HTTP 自调用全链路（HttpClient→本机 wx-notify）属容器行为，
 * 离线单测不覆盖，由主会话 live 验收（契约 §5：C 端下单→收银台 sim 链路全流程）；
 * {@code SimWxPayService} 的鉴权/幂等快路依赖容器装配，同归 live 验收。
 */
class SimWxPayNotifyTest {

    private static final String MCHID = "SIM-MCHID-0001"; // 微信官方文档示例商户号字面量会触发 GitHub 密钥扫描（误报），改显式模拟值
    /** 测试常量 APIv3 密钥（32 位 ASCII，与 support 里 @Value 注入的是同一个值——加密与解密必须同 key） */
    private static final String API_V3_KEY = "0123456789abcdef".repeat(2); // GitHub 密钥扫描误报归零：字面量改运行时派生（同值 32 字节），2026-11-07
    private static final String ORDER_ID = "2104094865859330050";
    private static final Long USER_ID = 4L;
    private static final long DISH_ID = 44L;
    private static final int QUANTITY = 2;
    /** unpaidOrder 的 15.00 元对应的应付分数 */
    private static final int CORRECT_FEN = 1500;

    /** 产品侧支撑件：密钥懒生成进程内复用，两枚 @Value 用 ReflectionTestUtils 离线注入（static 省重复生成） */
    private static final SimWxPaySupport support = new SimWxPaySupport();
    /** 「攻击者」密钥对：专门用来证「拿非模拟平台私钥签的报文验不过」 */
    private static KeyPair foreignKeyPair;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final InviteService inviteService = mock(InviteService.class);

    private final AtomicInteger soldCountDelta = new AtomicInteger();
    private Order dbRow;

    private PaymentSettlement settlement;

    @BeforeAll
    static void configureSupportAndPrimeMybatisPlusColumnCache() throws Exception {
        ReflectionTestUtils.setField(support, "apiV3Key", API_V3_KEY);
        ReflectionTestUtils.setField(support, "wxpayMchid", MCHID);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        foreignKeyPair = generator.generateKeyPair();

        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() {
        freshFixture();
    }

    /** RequestContextHolder 本测试没往里塞东西，但照先例收尾清一次，防别的测试类在同线程留残渣 */
    @AfterEach
    void clearRequestContextHolder() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 每条用例复位假库与桩（照 WxPayNotifyTest#freshFixture，语义逐字相同、审计侧无） */
    private void freshFixture() {
        Mockito.reset(orderMapper, orderItemMapper, dishMapper, inviteService);
        settlement = new PaymentSettlement(orderMapper, orderItemMapper, dishMapper, inviteService);
        dbRow = unpaidOrder(ORDER_ID, "15.00");
        soldCountDelta.set(0);

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

    // —— fixture（照 WxPayNotifyTest） ——

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

    /** 被测服务：真实 PaymentSettlement + 产品侧 SimCertificateSource（认 SimWxPaySupport 的模拟公钥） */
    private WxPayNotifyService service() {
        return new WxPayNotifyService(orderMapper, settlement, new SimCertificateSource(support));
    }

    /** 用 sim 密钥对构造一条合法支付成功回调（产品代码自己造，测试不复制签名/加密逻辑） */
    private SimWxPaySupport.SimCallback simCallback(int amountFen) {
        return support.buildPaidNotify(ORDER_ID, amountFen);
    }

    private WxPayNotifyService.Accepted accept(SimWxPaySupport.SimCallback cb, String serial) {
        return service().accept(ORDER_ID, cb.body(), cb.timestamp(), cb.nonce(), cb.signature(), serial,
                MCHID, API_V3_KEY);
    }

    /** 「零副作用」共用断言（照 WxPayNotifyTest#assertOrderUntouched） */
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

    // —— ① 合法签名回调：accept 通过 + settleInTransaction 置态成功（任务书第 2 条主断言） ——

    @Test
    void simSignedCallbackPassesAcceptAndSettlesExactlyOnce() {
        SimWxPaySupport.SimCallback cb = simCallback(CORRECT_FEN);

        WxPayNotifyService.Accepted accepted = accept(cb, SimWxPaySupport.SIM_SERIAL);

        assertNotNull(accepted, "sim 密钥对签的合法回调必须过真实验签/解密/三比对（accept 产物不为 null）");
        assertSame(dbRow, accepted.order(), "accept 交出的就是查到的那一行");
        assertNotNull(accepted.payTime(), "payTime 来自回调 success_time（C-4 渠道权威时刻）");

        service().settleInTransaction(accepted.order(), accepted.payTime());

        assertEquals(Integer.valueOf(1), dbRow.getPayStatus(), "置态成功：pay_status 0→1");
        assertEquals(Integer.valueOf(1), dbRow.getPayTime() == null ? 0 : 1, "pay_time 已落");
        assertNotNull(dbRow.getPickupCode(), "取货码已挂载（S3-0：支付成功即有码）");
        assertTrue(dbRow.getPickupCode().matches("\\d{6}"), "取货码 6 位数字（D-3）：" + dbRow.getPickupCode());
        assertEquals(QUANTITY, soldCountDelta.get(), "销量按明细累加恰一次");
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
        verify(orderMapper, times(1)).update(isNull(), any());
    }

    // —— ② 篡改金额：签名自洽但三比对不过 → 拒、零副作用 ——

    @Test
    void tamperedAmountIsRejectedAndOrderStaysUntouched() {
        SimWxPaySupport.SimCallback tampered = simCallback(CORRECT_FEN + 1);

        WxPayNotifyRejectException e = assertThrows(WxPayNotifyRejectException.class,
                () -> accept(tampered, SimWxPaySupport.SIM_SERIAL));

        assertEquals(RejectCode.AMOUNT_MISMATCH, e.getCode());
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, e.getHttpStatus(),
                "篡改金额必须真实 4xx");
        assertEquals("微信支付回调金额与订单不符", e.getMessage(), "文案沿用 wx-notify 应答表，不新造");
        assertOrderUntouched();
    }

    // —— ③ 坏签名：报文被动过 / 非模拟平台私钥所签 → 401、零副作用 ——

    @Test
    void bodyTamperedAfterSimSigningIsRejected() {
        SimWxPaySupport.SimCallback cb = simCallback(CORRECT_FEN);

        WxPayNotifyRejectException e = assertThrows(WxPayNotifyRejectException.class,
                () -> service().accept(ORDER_ID, cb.body() + " ", cb.timestamp(), cb.nonce(),
                        cb.signature(), cb.serial(), MCHID, API_V3_KEY));

        assertEquals(RejectCode.SIGNATURE_VERIFY_FAILED, e.getCode());
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, e.getHttpStatus());
        assertEquals("微信支付回调验签失败", e.getMessage());
        assertOrderUntouched();
    }

    @Test
    void signatureFromAnotherPrivateKeyIsRejected() {
        SimWxPaySupport.SimCallback cb = simCallback(CORRECT_FEN);
        // 拿「攻击者」私钥重签同一段报文（验签名串构造一致，只换钥匙）→ 模拟公钥验不过
        String forgedSignature = WxpaySignature.sign(
                WxpaySignature.buildVerifyMessage(cb.timestamp(), cb.nonce(), cb.body()), foreignKeyPair.getPrivate());

        WxPayNotifyRejectException e = assertThrows(WxPayNotifyRejectException.class,
                () -> service().accept(ORDER_ID, cb.body(), cb.timestamp(), cb.nonce(),
                        forgedSignature, cb.serial(), MCHID, API_V3_KEY));

        assertEquals(RejectCode.SIGNATURE_VERIFY_FAILED, e.getCode());
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, e.getHttpStatus());
        assertNotEquals(cb.signature(), forgedSignature, "重签产物与原签名必须不同（否则造签失败）");
        assertOrderUntouched();
    }

    // —— ④ SimCertificateSource 只认 SIM 序列号（未知 → 503 可重试，不是 4xx） ——

    @Test
    void unknownSerialIsServiceUnavailable() {
        SimWxPaySupport.SimCallback cb = simCallback(CORRECT_FEN);

        WxPayNotifyRejectException e = assertThrows(WxPayNotifyRejectException.class,
                () -> accept(cb, "SERIAL-NOT-SIM"));

        assertEquals(RejectCode.PLATFORM_CERT_UNAVAILABLE, e.getCode());
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.getHttpStatus(),
                "证书取不到是瞬时/证书侧问题，回 5xx 让投递方重投（与 Unconfigured 同性质）");
        assertEquals("微信支付平台证书不可用", e.getMessage());
        assertOrderUntouched();
    }

    // —— ⑤ sim-create 五参数：官方形状 + paySign 可用模拟商户公钥验过 ——

    /**
     * paySign 验签需要精确复原签名串：请求体由 {@code buildJsapiParams} 内部用 LinkedHashMap 定序序列化，
     * 测试按同一字段顺序重建（字段集/顺序变了这条就红——那是签名契约变了，本该红）。
     */
    @Test
    void simJsapiParamsCarryOfficialFiveKeysAndVerifiablePaySign() throws Exception {
        Map<String, Object> params = support.buildJsapiParams(ORDER_ID, CORRECT_FEN);

        assertEquals("RSA", params.get("signType"));
        assertEquals(CORRECT_FEN, params.get("amountFen"), "金额分照单透出，前端拿它与收银台金额对账");
        assertEquals("prepay_id=sim_prepay_" + ORDER_ID, params.get("package"));
        Object timeStampObj = params.get("timeStamp");
        Object nonceStrObj = params.get("nonceStr");
        assertNotNull(timeStampObj);
        assertNotNull(nonceStrObj);
        assertEquals(32, String.valueOf(nonceStrObj).length(), "nonceStr 32 位（官方口径）");
        assertTrue(String.valueOf(timeStampObj).matches("\\d{10}"), "timeStamp 秒级 epoch：" + timeStampObj);

        // 复原签名串并验签：证明 paySign 确为模拟商户私钥所签（不是随手 Base64）
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("appid", "wx-sim-appid");
        request.put("mchid", MCHID);
        request.put("out_trade_no", ORDER_ID);
        request.put("prepay_id", "sim_prepay_" + ORDER_ID);
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", CORRECT_FEN);
        amount.put("currency", "CNY");
        request.put("amount", amount);
        String requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        String verifyMessage = WxpaySignature.buildRequestMessage("POST", "/v3/pay/transactions/jsapi",
                String.valueOf(timeStampObj), String.valueOf(nonceStrObj), requestBody);
        assertTrue(WxpaySignature.verify(verifyMessage, String.valueOf(params.get("paySign")),
                        support.merchantPublicKey()),
                "paySign 必须可用模拟商户公钥按官方请求签名串验过");
        // 平台公钥验商户签名必须不过（两把钥匙不互通）
        assertFalse(WxpaySignature.verify(verifyMessage, String.valueOf(params.get("paySign")),
                        support.platformPublicKey()),
                "商户签名用平台公钥验必须失败（密钥角色分工没串）");
    }

    // —— ⑥ APIv3 密钥长度不对：fail-fast，不发注定被拒的回调 ——

    @Test
    void wrongLengthApiV3KeyFailsFastBeforeBuildingCallback() {
        String original = API_V3_KEY;
        try {
            ReflectionTestUtils.setField(support, "apiV3Key", "too-short");

            BizException e = assertThrows(BizException.class, () -> simCallback(CORRECT_FEN));

            assertTrue(e.getMessage().contains("32 位"), "fail-fast 文案要指认配置错：" + e.getMessage());
            verify(orderMapper, never()).selectById(any());
        } finally {
            ReflectionTestUtils.setField(support, "apiV3Key", original);
        }
        // 恢复后照常可构造（密钥对复用，不走重复生成的慢路径也能过）
        assertNotNull(simCallback(CORRECT_FEN).signature());
        assertNotNull(Base64.getDecoder().decode(simCallback(CORRECT_FEN).signature()), "签名头是合法 Base64");
        // 时间戳窗口内：构造到消费间隔毫秒级，绝不越过 300s（此断言防将来把 timestamp 换成别的口径）
        assertTrue(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(simCallback(CORRECT_FEN).timestamp())) <= 300);
    }
}
