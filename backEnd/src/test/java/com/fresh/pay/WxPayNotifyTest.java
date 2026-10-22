package com.fresh.pay;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.controller.PayController;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.interceptor.RateLimitInterceptor;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.pay.WxPayNotifyRejectException.RejectCode;
import com.fresh.service.AdminService;
import com.fresh.service.InviteService;
import com.fresh.service.impl.AdminServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 微信支付回调链路端到端单测（F-21.6 Step 2b / D-21-3 断言集 + F-21.8 Step 2c 的 C-2/C-3/C-4 断言集，
 * **全部离线、不连库、不起 Spring 容器**）：测试代码扮演「微信服务器」，
 * 把 {@code PayController#wxNotify} 当普通方法直接打（HTTP 码断言留在 controller 层）；
 * Step 2c 下沉出来的 {@link WxPayNotifyService} 也可直接 {@code new} 出来只测判定（payTime 口径就测在这一层）。
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
 * - 装配（Step 2c 改造，C-6）：{@code new WxPayNotifyService(orderMapper, settlement, certificateSource)}
 *   而两枚配置（mchid / api-v3-key）**走方法入参**；controller 用 {@code new PayController()} 反射注入
 *   {@code notifyService} 与那两枚 {@code @Value} —— Step 2b 往 controller 塞的 5 个字段随编排下沉减到 3 个。
 *
 * 断言集（逐条对应 D-21-3、F-21.6 的官方纠偏与 F-21.8 的 C-1b/C-2/C-3/C-4）：
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
 *   库内出现三位小数 → 报错而不是悄悄舍入（{@code RoundingMode.UNNECESSARY}）；
 * ⑬（C-3）时间戳新鲜度窗口：{@code +60s} 与 {@code -300s}（恰等于容忍值 → 不拒）放行，
 *   {@code ±301s} 与非数字串一律真实 400「微信支付回调时间戳超出允许范围」且零副作用；
 * ⑭（C-4）{@code payTime} 取渠道 {@code success_time}：{@code +08:00} 与 {@code Z} 两种形态各解析成对应本地值；
 *   字段缺失 / 畸形串 → **不拒收**，回落服务器时间且结算照常完成（丢单比时刻不准更贵，这是取舍不是不严格）；
 * ⑮（C-1b / C-2 / C-1）形态核对：{@code accept} 失败抛的是**带 HTTP 码的受控异常**
 *   {@link WxPayNotifyRejectException}（不是 null 哨兵、不是 BizException）；
 *   {@code @Transactional} 只标在 {@code settleInTransaction} 且是 Spring 那枚、{@code accept} 上没有；
 *   {@link WxPayNotifyService} 是恒装配的普通 {@code @Service}（带 {@code @ConditionalOnProperty} 会让容器起不来）。
 *
 * 未测成（如实登记，绝不写成「已通过」）：与 api.mch.weixin.qq.com 的真联调、真实平台证书下载/轮换、
 * {@code wechatpay-java} 之外的报文实样；**容器级代理是否真的接住了 {@code @Transactional}**——
 * 纯 {@code new} 出来的对象没有代理，{@code TransactionSynchronizationManager.isActualTransactionActive()}
 * 在离线单测里恒 {@code false}，所以本类只做反射核对（⑮），真回滚语义由主会话门禁的
 * {@code @SpringBootTest} 容器探针证（F-21.8「门禁」节，含摘掉注解必须变红的变异自验）；
 * 真实 MySQL 上的事务回滚也未测（本批全程 mock mapper，不连库）。
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
    /**
     * 业务报文里 {@code success_time} 的默认片段（带结尾逗号，便于整段替换成「缺字段」）。
     * C-4 之后这个值就是 payTime 的来源，不再由服务器时间决定。
     */
    private static final String DEFAULT_SUCCESS_TIME_FIELD = "\"success_time\":\"2026-10-13T10:06:30+08:00\",";

    private static KeyPair merchantKeyPair;
    private static KeyPair platformKeyPair;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final InviteService inviteService = mock(InviteService.class);

    private final AtomicInteger soldCountDelta = new AtomicInteger();
    private Order dbRow;

    private PaymentSettlement settlement;

    // —— F-44 拒收留痕的测试床（仍是离线：不起容器、不连库、不发网络，只把审计 mapper 换成 mock）——

    /**
     * 审计写入的假库：{@code AdminServiceImpl} 用真身（Jackson 转义、两趟兜底都是产品代码），
     * 只有最末那枚 {@code AdminAuditLogMapper} 是 mock，插入动作被录进 {@link #auditRows} 供逐列断言。
     */
    private final AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);
    private final List<AdminAuditLog> auditRows = new ArrayList<>();

    /** detail 解析用（测试侧自己解析被测产物，不复用被测类的 mapper） */
    private final ObjectMapper auditJson = new ObjectMapper();

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
        freshFixture();
    }

    /** {@code RequestContextHolder} 是线程静态的：本类几条用例往里塞过假 request，收尾必须清干净 */
    @AfterEach
    void clearRequestContextHolder() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 把「一条拒收分支」跑干净所需的复位动作（F-44 抽出来复用）：
     * 逐条驱动 18 条分支时，分支之间会互相污染（{@code dbRow} 的状态、{@code selectById} 的桩），
     * 每条之前重放一次本方法即可隔离——语义与原来那个 {@code @BeforeEach} 逐字相同，
     * 只是多了审计侧的复位（假库清空 + 请求上下文清空，后者防的是上一条用例留下的 IP 泄进下一条断言）。
     */
    private void freshFixture() {
        // 复位必须显式 reset：上一条分支可能把 selectById/update 桩成了「一调就抛」，
        // 而下面这些 when(...) 本身就要真调一次 mock —— 不 reset 就先被上一条的桩炸到（实测踩过）。
        Mockito.reset(orderMapper, orderItemMapper, dishMapper, inviteService, adminAuditLogMapper);
        settlement = new PaymentSettlement(orderMapper, orderItemMapper, dishMapper, inviteService);
        dbRow = unpaidOrder(ORDER_ID, "15.00");
        auditRows.clear();
        RequestContextHolder.resetRequestAttributes();
        // 审计假库：insert 动作录进 auditRows（返回值 1 只为了让 MyBatis-Plus 的回填不报错）
        when(adminAuditLogMapper.insert(any(AdminAuditLog.class))).thenAnswer(inv -> {
            auditRows.add(inv.getArgument(0));
            return 1;
        });

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

    /**
     * 被测服务（C-1 的落点）：纯 {@code new}，不起容器、不连库。
     * 三枚协作者构造器注入，两枚配置值（mchid / api-v3-key）留在方法入参上——
     * 正因为本类不依赖 {@code @Value}，离线单测才能直接传常量制造「未配置」那条分支。
     */
    private WxPayNotifyService service() {
        return new WxPayNotifyService(orderMapper, settlement, certificateSource);
    }

    private PayController controller() {
        return controller(MCHID, API_V3_KEY);
    }

    /**
     * controller 层装配（C-6）：Step 2b 往本类反射塞 5 个字段（2 枚配置 + 3 枚协作者），
     * 编排下沉后协作者归服务，controller 只剩 {@code notifyService} + 2 枚 {@code @Value} = 3 个字段。
     * F-44 起多两枚：{@code adminService}（拒收留痕，真 {@code AdminServiceImpl} + mock 审计 mapper）与
     * {@code trustProxy}（IP 口径开关，默认 false，与 application-dev.yml 的既有取值一致）。
     * HTTP 状态码断言仍留在这一层（映射层负责的就是状态码），判定层的口径另测（见 payTime 那组）。
     */
    private PayController controller(String mchid, String apiV3Key) {
        return controller(mchid, apiV3Key, auditWritingService(), false);
    }

    /**
     * 一枚「一调就抛」的 {@code AdminService}：专门用来证第 4 点——留痕挂了也不能改对外应答。
     * 挂的是最外层那个公开方法（controller 唯一调的那一个），与它内部怎么写库无关。
     */
    private AdminService explodingAuditWriter() {
        AdminService exploding = mock(AdminService.class);
        doThrow(new IllegalStateException("审计写入器故意挂掉")).when(exploding)
                .recordNotifyReject(any(), anyInt(), any(), any());
        return exploding;
    }

    /**
     * 真 {@code AdminServiceImpl}（不是 mock）：detail 的 Jackson 转义、两键形状、两趟兜底、
     * {@code target_id} 的空白归一这些都必须测产品代码本身，mock 掉就等于测了自己的桩。
     */
    private AdminService auditWritingService() {
        AdminServiceImpl service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", adminAuditLogMapper);
        return service;
    }

    private PayController controller(String mchid, String apiV3Key, AdminService adminService, boolean trustProxy) {
        PayController c = new PayController();
        ReflectionTestUtils.setField(c, "notifyService", service());
        ReflectionTestUtils.setField(c, "wxpayMchid", mchid);
        ReflectionTestUtils.setField(c, "wxpayApiV3Key", apiV3Key);
        ReflectionTestUtils.setField(c, "adminService", adminService);
        ReflectionTestUtils.setField(c, "trustProxy", trustProxy);
        return c;
    }

    /** 一条通知的可控要素（签名一定和 body 自洽，测试只改自己关心的那一项） */
    private record Notification(String body, String timestamp, String nonce, String signature, String serial) {
    }

    /** 默认参数 = 一条完全合法的通知 */
    private Notification notification(String outTradeNo, int amountFen, String tradeState) throws Exception {
        return notification(outTradeNo, amountFen, tradeState, API_V3_KEY, platformKeyPair.getPrivate());
    }

    private Notification notification(String outTradeNo, int amountFen, String tradeState,
                                      String encryptKey, PrivateKey signingKey) throws Exception {
        return build(outTradeNo, amountFen, tradeState, encryptKey, signingKey,
                DEFAULT_SUCCESS_TIME_FIELD, currentTimestamp());
    }

    /**
     * 只换 {@code Wechatpay-Timestamp}（C-3 用）：时间戳是验签名串的**第一行**，改它必须**重新签名**，
     * 否则测到的是「验签失败」而不是「新鲜度超窗」——这两条分支的 HTTP 码与文案都不同，混了就白测。
     */
    private Notification notificationWithTimestamp(long offsetSeconds) throws Exception {
        return notificationWithTimestamp(String.valueOf(currentEpochSecondAfterAligning() + offsetSeconds));
    }

    /** 时间戳头给任意原文（{@code "abc"} 一类非数字形态就走 NumberFormatException 分支） */
    private Notification notificationWithTimestamp(String rawTimestamp) throws Exception {
        return build(ORDER_ID, CORRECT_FEN, "SUCCESS", API_V3_KEY, platformKeyPair.getPrivate(),
                DEFAULT_SUCCESS_TIME_FIELD, rawTimestamp);
    }

    /** 只换业务报文里的 {@code success_time} 片段（C-4 用）：传 {@code ""} 即造「字段缺失」 */
    private Notification notificationWithSuccessTime(String successTimeField) throws Exception {
        return build(ORDER_ID, CORRECT_FEN, "SUCCESS", API_V3_KEY, platformKeyPair.getPrivate(),
                successTimeField, currentTimestamp());
    }

    private String currentTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    /**
     * 边界值（{@code -300} / {@code +301}）为什么要把执行时刻对齐到整秒之后：
     * 代码里的判定是 {@code skew > 300}，而 {@code skew} 用的是 {@code accept} 内**那一刻**的
     * {@code Instant.now()}；从构造时间戳到那一刻若跨过一个整秒，{@code -300} 会变成 301（该放的拒了）、
     * {@code +301} 会变成 300（该拒的放了）→ 假红/假绿。对齐到整秒后 headroom 有 ~900ms，
     * 而 RSA 签名 + AES 加密只有毫秒级开销，判定因此是确定的。
     */
    private long currentEpochSecondAfterAligning() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long nano = Instant.now().getNano();
            if (nano < 100_000_000L) {
                return Instant.now().getEpochSecond();
            }
            Thread.sleep(10);
        }
        return Instant.now().getEpochSecond();
    }

    /**
     * 扮演微信服务器：业务 JSON → APIv3 密钥 AES-256-GCM 加密进 {@code resource.ciphertext} →
     * 外层通知报文 → 平台私钥签「时间戳\n随机串\n原始报文\n」。
     * 全参形态：{@code successTimeField} 原样插进业务 JSON（含结尾逗号，传 "" 造缺字段），
     * {@code timestamp} 同时用于签名与回头的事务参数（保证「签名与报文自洽」这条前提不被破坏）。
     */
    private Notification build(String outTradeNo, int amountFen, String tradeState,
                               String encryptKey, PrivateKey signingKey,
                               String successTimeField, String timestamp) throws Exception {
        return signedNotification(transactionJson(outTradeNo, amountFen, tradeState, successTimeField),
                timestamp, encryptKey, signingKey);
    }

    /** 业务报文（解密后的那一层）本体（F-44 抽出：两条分支要的是「报文形状」而不是「字段值」） */
    private String transactionJson(String outTradeNo, int amountFen, String tradeState, String successTimeField) {
        return "{\"appid\":\"wxtestappid000000000\",\"mchid\":\"" + MCHID + "\","
                + "\"out_trade_no\":\"" + outTradeNo + "\","
                + "\"transaction_id\":\"420000202610131006000001\","
                + "\"trade_state\":\"" + tradeState + "\",\"trade_state_desc\":\"支付成功\","
                + successTimeField
                + "\"amount\":{\"total\":" + amountFen + ",\"payer_total\":" + amountFen + ",\"currency\":\"CNY\"},"
                + "\"payer\":{\"openid\":\"o-test-openid\"}}";
    }

    /**
     * 拿任意业务明文走一遍「加密 → 外层通知 → 平台私钥签名」：签名与报文始终自洽，测试只改明文本身。
     * 外层 {@code id} 是固定测试标签（{@code WxPayNotifyService} 从不读它，读的是密文解出来的业务字段）。
     */
    private Notification signedNotification(String transaction, String timestamp,
                                            String encryptKey, PrivateKey signingKey) throws Exception {
        String ciphertext = WxpayAead.encrypt(encryptKey, RESOURCE_NONCE, AAD, transaction);
        String body = "{\"id\":\"test-notify-" + ORDER_ID + "\","
                + "\"create_time\":\"2026-10-13T10:06:30+08:00\","
                + "\"event_type\":\"TRANSACTION.SUCCESS\",\"resource_type\":\"transaction\",\"summary\":\"支付成功\","
                + "\"resource\":{\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"ciphertext\":\"" + ciphertext + "\","
                + "\"associated_data\":\"" + AAD + "\",\"nonce\":\"" + RESOURCE_NONCE + "\"}}";
        String signature = WxpaySignature.sign(
                WxpaySignature.buildVerifyMessage(timestamp, RESOURCE_NONCE, body), signingKey);
        return new Notification(body, timestamp, RESOURCE_NONCE, signature, SERIAL);
    }

    /**
     * 解密结果不是合法 JSON（{@code WxPayNotifyService:182} 那条分支）：
     * 签名与加密都完全正常，只是明文是一句人话——「解密成功但取不出字段」只能这样造。
     */
    private Notification notificationWithUnparseablePlainText() throws Exception {
        return signedNotification("这一段是合法密文解出来的明文，但不是 JSON",
                currentTimestamp(), API_V3_KEY, platformKeyPair.getPrivate());
    }

    /**
     * 缺 {@code amount.total}（{@code :199} 那条分支）：{@code amount} 对象还在、
     * {@code payer_total}/{@code currency} 都还在，只少数值型 {@code total}——
     * 这样造才证得了判定用的是 {@code isNumber()} 而不是「amount 缺失」。
     */
    private Notification notificationWithoutAmountTotal() throws Exception {
        String transaction = transactionJson(ORDER_ID, CORRECT_FEN, "SUCCESS", DEFAULT_SUCCESS_TIME_FIELD)
                .replace("\"total\":" + CORRECT_FEN + ",", "");
        return signedNotification(transaction, currentTimestamp(), API_V3_KEY, platformKeyPair.getPrivate());
    }

    /** 调用服务层判定的样板（C-4 / C-1b 那两条直测 accept 的断言用，配置走方法入参） */
    private WxPayNotifyService.Accepted accept(Notification n) {
        return service().accept(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial(),
                MCHID, API_V3_KEY);
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
        // 结算没成 → 一行都没写成。Step 2c 起事务入口真实存在（settleInTransaction 上标了 @Transactional）：
        // 底层 QueryTimeoutException 在事务方法内被包成 WxPayNotifyRejectException **穿出代理边界**
        // → 代理先回滚 → controller 才 catch 成 500（离线单测里代理不存在，故这里能证的只有「回读不到就一行不写」
        //   与「应答码是真实 500」；回滚本身归主会话的容器探针，见类注释「未测成」段）
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

    // —— ⑬ C-3：时间戳新鲜度窗口（官方防重放 5 分钟；判定位置在验签之后、解密之前）——

    /**
     * +60s：远在 300s 窗口内 → 必须放行并结算。
     * 这一条同时是「窗口没把正常通知挡掉」的证据（时钟略有偏差是常态，防重放不能做成防自己）。
     */
    @Test
    void timestampSixtySecondsAheadIsInsideTheWindowAndSettles() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notificationWithTimestamp(60));

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(), "+60s 在窗口内，绝不能拒");
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
    }

    /** -300s：**恰好等于**容忍值（代码是 {@code skew > 300} 才拒）→ 边界内侧，必须放行 */
    @Test
    void timestampExactlyThreeHundredSecondsBehindIsStillInsideTheWindow() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notificationWithTimestamp(-300));

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(), "-300s 是边界内侧（>300 才拒），必须放行");
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
    }

    /** -301s：越界一秒 → 真实 400 + 新文案，且**零副作用**（此刻连解密都还没发生） */
    @Test
    void timestampThreeHundredAndOneSecondsBehindIsRejectedWithZeroSideEffects() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notificationWithTimestamp(-301));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调时间戳超出允许范围");
        assertOrderUntouched();
    }

    /** +301s（未来时刻）：同样越界 → 同一句 400。窗口是绝对值，不是「只挡过去」 */
    @Test
    void timestampThreeHundredAndOneSecondsAheadIsRejectedWithZeroSideEffects() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notificationWithTimestamp(301));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调时间戳超出允许范围");
        assertOrderUntouched();
    }

    /**
     * 时间戳根本不是秒级 epoch（{@code "abc"}）→ 无从判定新鲜度 → 400 同一句文案。
     * 注意签名是**拿这个畸形时间戳算出来的**（平台私钥自洽），所以走到这一步说明「窗口确实挡在验签之后」，
     * 而不是把畸形头降级成 401 验签失败。
     */
    @Test
    void nonNumericTimestampIsRejectedAndNeverReachesDecrypting() throws Exception {
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, notificationWithTimestamp("abc"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调时间戳超出允许范围");
        assertOrderUntouched();
        // 位置核对：超窗/畸形时间戳在解密之前被拒，out_trade_no 一次都不该被比对（不查库）
        verify(orderMapper, never()).selectById(any());
    }

    // —— ⑭ C-4：payTime 取解密出的 success_time（渠道权威时刻）——

    /** {@code +08:00} 形态：{@code OffsetDateTime.parse(...).toLocalDateTime()} → 本地墙钟 10:06:30 */
    @Test
    void payTimeIsSuccessTimeWithPlusEightOffset() throws Exception {
        WxPayNotifyService.Accepted accepted = accept(notification(ORDER_ID, CORRECT_FEN, "SUCCESS"));

        assertEquals(LocalDateTime.of(2026, 10, 13, 10, 6, 30), accepted.payTime(),
                "payTime 必须来自 success_time，不再是服务器时间（C-4）");
        assertSame(dbRow, accepted.order(), "accept 交出的就是查到的那一行，结算不重新查库");
    }

    /** {@code Z}（UTC）形态：按偏移量折算后的本地值 02:06:30（JDK 17 实测口径） */
    @Test
    void payTimeIsSuccessTimeExpressedInUtc() throws Exception {
        WxPayNotifyService.Accepted accepted =
                accept(notificationWithSuccessTime("\"success_time\":\"2026-10-13T02:06:30Z\","));

        assertEquals(LocalDateTime.of(2026, 10, 13, 2, 6, 30), accepted.payTime(),
                "Z 形态要按 UTC 折算，不能把 02:06:30 当成东八区墙钟");
    }

    /**
     * **缺** {@code success_time}：不得拒收（支付事实已由验签 + 三比对认定，拒了等于把真实扣款打成丢单），
     * 回落服务器时间且结算照常完成。这条兜底看着像「不严格」，实为取舍——别顺手改成拒收。
     */
    @Test
    void missingSuccessTimeFallsBackToServerTimeAndStillSettles() throws Exception {
        Notification n = notificationWithSuccessTime("");
        LocalDateTime before = LocalDateTime.now();

        WxPayNotifyService.Accepted accepted = accept(n);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(accepted.payTime(), "缺字段也要有一个可用时刻（回落 now）");
        assertFalse(accepted.payTime().isBefore(before) || accepted.payTime().isAfter(after),
                "回落的必须是服务器时间，实际 " + accepted.payTime());
        // 仍然结算成功：一条真实支付绝不能因为渠道少发了一个字段而丢
        service().settleInTransaction(accepted.order(), accepted.payTime());
        assertEquals(Integer.valueOf(1), dbRow.getPayStatus());
        assertNotNull(dbRow.getPickupCode());
        assertEquals(QUANTITY, soldCountDelta.get());
        verify(inviteService, times(1)).onOrderPaid(USER_ID);
    }

    /** 畸形 {@code success_time}（{@code "not-a-time"}）：与缺失同处置——不拒收、回落 now、结算照常 */
    @Test
    void unparseableSuccessTimeFallsBackToServerTimeAndStillSettles() throws Exception {
        Notification n = notificationWithSuccessTime("\"success_time\":\"not-a-time\",");
        LocalDateTime before = LocalDateTime.now();

        WxPayNotifyService.Accepted accepted = accept(n);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(accepted.payTime());
        assertFalse(accepted.payTime().isBefore(before) || accepted.payTime().isAfter(after),
                "解析失败必须回落服务器时间，实际 " + accepted.payTime());
        ResponseEntity<Map<String, String>> response = post(ORDER_ID, n);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value(), "解析失败绝不是拒收理由");
    }

    // —— ⑮ C-1b / C-2 / C-1 的形态核对（结构即契约，改坏了这里就红）——

    /**
     * {@code accept} 的失败只能由**受控异常**承载（带 HTTP 码 + 钉死文案），绝不返回 null 表示失败；
     * controller 只 catch 这一枚具体类型，才有「先回滚、再应答」的顺序（C-5）。
     */
    @Test
    void acceptFailuresAreCarriedByTheControlledRejectExceptionNeverNull() throws Exception {
        Notification forged = notification(ORDER_ID, CORRECT_FEN, "SUCCESS", API_V3_KEY, merchantKeyPair.getPrivate());

        WxPayNotifyRejectException signatureFailure = assertThrows(WxPayNotifyRejectException.class,
                () -> service().accept(ORDER_ID, forged.body(), forged.timestamp(), forged.nonce(),
                        forged.signature(), forged.serial(), MCHID, API_V3_KEY));
        assertEquals(HttpStatus.UNAUTHORIZED, signatureFailure.getHttpStatus(), "应答表：验签失败 = 401");
        assertEquals("微信支付回调验签失败", signatureFailure.getMessage(), "文案逐字沿用，不许新造");

        WxPayNotifyRejectException configurationFailure = assertThrows(WxPayNotifyRejectException.class,
                () -> service().accept(ORDER_ID, forged.body(), forged.timestamp(), forged.nonce(),
                        forged.signature(), forged.serial(), "", API_V3_KEY));
        assertEquals(HttpStatus.BAD_REQUEST, configurationFailure.getHttpStatus());
        assertEquals("微信支付未配置", configurationFailure.getMessage());
        assertOrderUntouched();
    }

    /**
     * 事务边界的**形状**（C-2 的可测部分）：{@code @Transactional} 只许标在 {@code settleInTransaction} 上，
     * 且必须是 Spring 那枚（全仓 13 处统一 {@code org.springframework.transaction.annotation.Transactional}）；
     * {@code accept} 上必须没有——把整段判定包进事务会让 5xx 应答落进回滚歧义。
     * 代理是否真的接住了它不属本批断言集（纯 new 无代理），归主会话的容器探针。
     */
    @Test
    void transactionAnnotationSitsOnSettleMethodOnly() throws Exception {
        Method settle = WxPayNotifyService.class.getMethod("settleInTransaction", Order.class, LocalDateTime.class);
        assertNotNull(settle.getAnnotation(Transactional.class),
                "回调链路必须有事务入口（Step 2b 缺的正是它：settle 多条 UPDATE 之间宕机就是半态）");
        for (Annotation present : settle.getAnnotations()) {
            if ("Transactional".equals(present.annotationType().getSimpleName())) {
                assertEquals("org.springframework.transaction.annotation.Transactional",
                        present.annotationType().getName(), "注解类型必须是 Spring 那枚，不是 javax/jakarta 的");
            }
        }
        Method accept = WxPayNotifyService.class.getMethod("accept", String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class);
        assertNull(accept.getAnnotation(Transactional.class), "accept 只判定不写库，标了事务反而把 5xx 拖进回滚歧义");
    }

    /**
     * 恒装配（C-1 红线，F-21.7 实测过的坑）：{@code WxPayService} 正因带 {@code pay.provider=wx} 条件，
     * 默认 {@code provider=mock} 下 Bean 不存在；回调编排服务若照抄那枚注解，容器直接起不来
     * （{@code wx-notify} 端点恒存在，默认配置下要能回「微信支付未配置」那条 400）。
     */
    @Test
    void notifyServiceIsPlainServiceNeverConditionallyWired() {
        assertTrue(WxPayNotifyService.class.isAnnotationPresent(Service.class), "普通 @Service");
        for (Annotation present : WxPayNotifyService.class.getAnnotations()) {
            assertNotEquals("org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                    present.annotationType().getName(),
                    "带上条件注解 = 默认 provider=mock 下按类型注入的 controller 起不来");
            assertNotEquals("ConditionalOnProperty", present.annotationType().getSimpleName());
        }
    }

    // —— ⑯ F-44：拒收留痕（每条拒收分支恰好一行审计；detail 只有 code/httpStatus 两键）——

    /** 一条拒收分支的驱动动作（可能抛 checked 异常：报文构造要签名/加密） */
    private interface RejectBranchCall {
        ResponseEntity<Map<String, String>> call() throws Exception;
    }

    /** 同一条分支换一枚审计写入器再打一遍（留痕挂没挂的对照用） */
    private interface BranchOnAuditWriter {
        ResponseEntity<Map<String, String>> run(AdminService auditWriter) throws Exception;
    }

    /** 分支用例：标签只进断言消息（人读），其余四项是应答表那一行 + 审计列的期望值 */
    private record RejectBranchCase(String label, RejectCode expectedCode, HttpStatus expectedStatus,
                                    String expectedMessage, String expectedTargetId, RejectBranchCall call) {
    }

    /**
     * 18 条 {@code throw reject(...)} 分支各一条驱动动作，顺序与 {@code WxPayNotifyService#accept}
     * 源码自上而下一致（{@code :108 :112 :122 :132 :138 :151 :157 :165 :175 :182 :190 :195 :199 :209 :215 :221 :230 :234}）。
     * 结算包装那条（{@code settleInTransaction:274}）不在本表内，由
     * {@link #settlementFailureRejectAlsoWritesExactlyOneAuditRow()} 单独驱动——
     * 判据的分母是 {@code grep -c 'throw reject('} 实测的 <b>18</b>，把第 19 条塞进这张表会让「覆盖分支数 == N」失去意义。
     */
    private List<RejectBranchCase> rejectBranchCases() throws Exception {
        Notification valid = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
        Notification forged = notification(ORDER_ID, CORRECT_FEN, "SUCCESS", API_V3_KEY, merchantKeyPair.getPrivate());
        Notification wrongKeyCiphertext = notification(ORDER_ID, CORRECT_FEN, "SUCCESS",
                WRONG_API_V3_KEY, platformKeyPair.getPrivate());
        Notification staleTimestamp = notificationWithTimestamp(-301);
        Notification epochlessTimestamp = notificationWithTimestamp("abc");
        Notification otherOrder = notification(OTHER_ORDER_ID, CORRECT_FEN, "SUCCESS");
        Notification tamperedAmount = notification(ORDER_ID, CORRECT_FEN + 1, "SUCCESS");
        Notification notPaidState = notification(ORDER_ID, CORRECT_FEN, "NOTPAY");
        Notification noAmountTotal = notificationWithoutAmountTotal();
        Notification unparseablePlainText = notificationWithUnparseablePlainText();

        // 「报文不是合法 JSON」这条在验签之前（判定顺序是安全属性），签名自不自洽都能走到，
        // 但仍照既有那条用例的做法签一次，免得被误读成「畸形头就能打出 400」
        String notJsonBody = "这一串根本不是 JSON";
        String notJsonTimestamp = String.valueOf(Instant.now().getEpochSecond());
        String notJsonSignature = WxpaySignature.sign(WxpaySignature.buildVerifyMessage(
                notJsonTimestamp, RESOURCE_NONCE, notJsonBody), platformKeyPair.getPrivate());

        // 「加密报文不合法」：换掉 algorithm 后必须重新签名，否则测到的是验签失败那条
        String swappedAlgorithmBody = valid.body()
                .replace("\"algorithm\":\"AEAD_AES_256_GCM\"", "\"algorithm\":\"AES_CBC\"");
        String swappedAlgorithmSignature = WxpaySignature.sign(WxpaySignature.buildVerifyMessage(
                valid.timestamp(), valid.nonce(), swappedAlgorithmBody), platformKeyPair.getPrivate());

        return List.of(
                new RejectBranchCase("01 mchid/api-v3-key 未配置", RejectCode.WX_PAY_NOT_CONFIGURED,
                        HttpStatus.BAD_REQUEST, "微信支付未配置", ORDER_ID,
                        () -> controller("", API_V3_KEY).wxNotify(ORDER_ID, valid.body(), valid.timestamp(),
                                valid.nonce(), valid.signature(), valid.serial())),
                new RejectBranchCase("02 验签头缺失", RejectCode.NOTIFY_HEADERS_MISSING,
                        HttpStatus.BAD_REQUEST, "微信支付回调缺少验签头", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, valid.body(), valid.timestamp(), valid.nonce(),
                                null, valid.serial())),
                new RejectBranchCase("03 报文不是合法 JSON", RejectCode.NOTIFY_BODY_NOT_JSON,
                        HttpStatus.BAD_REQUEST, "微信支付回调报文不合法", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, notJsonBody, notJsonTimestamp, RESOURCE_NONCE,
                                notJsonSignature, SERIAL)),
                new RejectBranchCase("04 平台证书取不到", RejectCode.PLATFORM_CERT_UNAVAILABLE,
                        HttpStatus.SERVICE_UNAVAILABLE, "微信支付平台证书不可用", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, valid.body(), valid.timestamp(), valid.nonce(),
                                valid.signature(), "SERIAL-NOT-CACHED")),
                new RejectBranchCase("05 验签不通过", RejectCode.SIGNATURE_VERIFY_FAILED,
                        HttpStatus.UNAUTHORIZED, "微信支付回调验签失败", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, forged.body(), forged.timestamp(), forged.nonce(),
                                forged.signature(), forged.serial())),
                new RejectBranchCase("06 时间戳不是秒级 epoch", RejectCode.TIMESTAMP_NOT_EPOCH,
                        HttpStatus.BAD_REQUEST, "微信支付回调时间戳超出允许范围", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, epochlessTimestamp.body(),
                                epochlessTimestamp.timestamp(), epochlessTimestamp.nonce(),
                                epochlessTimestamp.signature(), epochlessTimestamp.serial())),
                new RejectBranchCase("07 时间戳超出 300s 窗口", RejectCode.TIMESTAMP_OUT_OF_WINDOW,
                        HttpStatus.BAD_REQUEST, "微信支付回调时间戳超出允许范围", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, staleTimestamp.body(), staleTimestamp.timestamp(),
                                staleTimestamp.nonce(), staleTimestamp.signature(), staleTimestamp.serial())),
                new RejectBranchCase("08 resource 加密信封不合法", RejectCode.RESOURCE_ENVELOPE_INVALID,
                        HttpStatus.BAD_REQUEST, "微信支付回调加密报文不合法", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, swappedAlgorithmBody, valid.timestamp(),
                                valid.nonce(), swappedAlgorithmSignature, valid.serial())),
                new RejectBranchCase("09 AEAD 解密抛错", RejectCode.AEAD_DECRYPT_FAILED,
                        HttpStatus.BAD_REQUEST, "微信支付回调解密失败", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, wrongKeyCiphertext.body(), wrongKeyCiphertext.timestamp(),
                                wrongKeyCiphertext.nonce(), wrongKeyCiphertext.signature(), wrongKeyCiphertext.serial())),
                new RejectBranchCase("10 解密结果不是合法 JSON", RejectCode.DECRYPTED_PAYLOAD_NOT_JSON,
                        HttpStatus.BAD_REQUEST, "微信支付回调解密失败", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, unparseablePlainText.body(),
                                unparseablePlainText.timestamp(), unparseablePlainText.nonce(),
                                unparseablePlainText.signature(), unparseablePlainText.serial())),
                new RejectBranchCase("11 out_trade_no 与路径不符", RejectCode.OUT_TRADE_NO_MISMATCH,
                        HttpStatus.BAD_REQUEST, "微信支付回调订单号不匹配", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, otherOrder.body(), otherOrder.timestamp(),
                                otherOrder.nonce(), otherOrder.signature(), otherOrder.serial())),
                new RejectBranchCase("12 trade_state 非 SUCCESS", RejectCode.TRADE_STATE_NOT_SUCCESS,
                        HttpStatus.BAD_REQUEST, "微信支付结果非成功状态", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, notPaidState.body(), notPaidState.timestamp(),
                                notPaidState.nonce(), notPaidState.signature(), notPaidState.serial())),
                new RejectBranchCase("13 报文缺 amount.total", RejectCode.AMOUNT_TOTAL_MISSING,
                        HttpStatus.BAD_REQUEST, "微信支付回调缺少金额", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, noAmountTotal.body(), noAmountTotal.timestamp(),
                                noAmountTotal.nonce(), noAmountTotal.signature(), noAmountTotal.serial())),
                new RejectBranchCase("14 查单抛错（DB 抖动）", RejectCode.ORDER_LOOKUP_FAILED,
                        HttpStatus.INTERNAL_SERVER_ERROR, "微信支付回调处理失败", ORDER_ID,
                        () -> {
                            when(orderMapper.selectById(any())).thenThrow(new QueryTimeoutException("数据库抖动"));
                            return controller().wxNotify(ORDER_ID, valid.body(), valid.timestamp(),
                                    valid.nonce(), valid.signature(), valid.serial());
                        }),
                new RejectBranchCase("15 查无此单", RejectCode.ORDER_NOT_FOUND,
                        HttpStatus.NOT_FOUND, "订单不存在", OTHER_ORDER_ID,
                        () -> controller().wxNotify(OTHER_ORDER_ID, otherOrder.body(), otherOrder.timestamp(),
                                otherOrder.nonce(), otherOrder.signature(), otherOrder.serial())),
                new RejectBranchCase("16 订单已取消", RejectCode.ORDER_CANCELLED,
                        HttpStatus.BAD_REQUEST, "订单已取消，无法支付", ORDER_ID,
                        () -> {
                            dbRow.setStatus("cancelled");
                            return controller().wxNotify(ORDER_ID, valid.body(), valid.timestamp(),
                                    valid.nonce(), valid.signature(), valid.serial());
                        }),
                new RejectBranchCase("17 库内金额标度异常", RejectCode.ORDER_AMOUNT_UNCOMPARABLE,
                        HttpStatus.INTERNAL_SERVER_ERROR, "订单金额无法比对", ORDER_ID,
                        () -> {
                            dbRow = unpaidOrder(ORDER_ID, "1.005");
                            return controller().wxNotify(ORDER_ID, tamperedAmount.body(), tamperedAmount.timestamp(),
                                    tamperedAmount.nonce(), tamperedAmount.signature(), tamperedAmount.serial());
                        }),
                new RejectBranchCase("18 通知金额与订单不符", RejectCode.AMOUNT_MISMATCH,
                        HttpStatus.BAD_REQUEST, "微信支付回调金额与订单不符", ORDER_ID,
                        () -> controller().wxNotify(ORDER_ID, tamperedAmount.body(), tamperedAmount.timestamp(),
                                tamperedAmount.nonce(), tamperedAmount.signature(), tamperedAmount.serial())));
    }

    /**
     * 判据主用例：<b>18 条拒收分支逐条驱动 → 每条恰好一行审计</b>，且
     * {@code actor/action/target_type/target_id/detail/ip} 六列钉死、detail 只有两键、code 互不相同。
     * 两个判据数（分支数 N 与覆盖分支数）都打印到 stdout（surefire 报告里可读）。
     */
    @Test
    void everyRejectBranchProducesExactlyOneAuditRow() throws Exception {
        List<RejectBranchCase> cases = rejectBranchCases();
        System.out.println("F-44 判据读数：拒收分支数 N（grep -c 'throw reject(' 实测）= 18，本用例待驱动分支数 = "
                + cases.size());

        Set<String> distinctCodes = new LinkedHashSet<>();
        List<String> allDetails = new ArrayList<>();
        int coveredBranches = 0;

        for (RejectBranchCase branchCase : cases) {
            // 分支之间会互相污染（dbRow 的状态、selectById 的桩、上一条留下的审计行），逐条复位
            freshFixture();
            ResponseEntity<Map<String, String>> response = branchCase.call().call();

            // (a) 对外契约一个字都没动：HTTP 码与文案仍是应答表那一行（留痕不许把它改成别的样子）
            assertEquals(branchCase.expectedStatus().value(), response.getStatusCode().value(),
                    branchCase.label() + " 的 HTTP 状态码");
            assertFailBody(response, branchCase.expectedMessage());

            // (b) 恰好一行审计——不多（写了两个 writer 就会是 2 行）不少（漏分支就是 0 行）
            assertEquals(1, auditRows.size(),
                    branchCase.label() + " 必须恰好产出一行审计，实际 " + auditRows.size() + " 行");
            AdminAuditLog row = auditRows.get(0);
            assertEquals("system:wx-notify", row.getActor(),
                    branchCase.label() + "：actor 恒为 system:wx-notify，绝不带任何密钥摘要（本场景没有 key 参与）");
            assertEquals("pay.notify_reject", row.getAction(), branchCase.label() + "：action 钉死");
            assertEquals("order", row.getTargetType(), branchCase.label() + "：target_type 钉死");
            assertEquals(branchCase.expectedTargetId(), row.getTargetId(),
                    branchCase.label() + "：target_id 是路径上的订单号（NOT NULL 列，空串一律换成 '-'）");
            assertNull(row.getIp(), "离线单测没有请求线程：ip 取不到就是 NULL，绝不为此编一个假 IP");

            // (c) detail 只有两键，且值就是这一条分支自己的 code 与实际回出去的 HTTP 码
            JsonNode detail = auditJson.readTree(row.getDetail());
            Set<String> keys = new LinkedHashSet<>();
            detail.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("code", "httpStatus"), keys,
                    branchCase.label() + " 的 detail 只允许 code/httpStatus 两键，实际 " + keys);
            assertEquals(branchCase.expectedCode().name(), detail.get("code").asText(),
                    branchCase.label() + " 的 code 必须是它自己那一枚");
            assertTrue(detail.get("httpStatus").isNumber(),
                    branchCase.label() + " 的 httpStatus 必须是 JSON 数字而不是字符串：" + row.getDetail());
            assertEquals(branchCase.expectedStatus().value(), detail.get("httpStatus").asInt(),
                    branchCase.label() + " 的 httpStatus 必须等于真实回出去的那个码");

            distinctCodes.add(detail.get("code").asText());
            allDetails.add(row.getDetail());
            coveredBranches++;
        }

        System.out.println("F-44 判据读数：覆盖分支数 = " + coveredBranches + " / 分支数 N = 18，"
                + "distinct code 数 = " + distinctCodes.size() + "，枚举总数 = " + RejectCode.values().length);

        // (d) 判据本体：是「覆盖分支数 == N」，不是「我加了几个用例」
        assertEquals(18, coveredBranches, "判据是覆盖分支数 == grep 实测的 18 条分支");
        assertEquals(cases.size(), coveredBranches, "本表列了几条分支就必须驱动几条");
        assertEquals(18, distinctCodes.size(), "同一个 code 不得被两条不同原因复用（18 条必须 18 枚）");
        // 一一对应的另一半：枚举总数 = 18 条 accept 分支 + 结算包装那 1 条（多一枚少一枚都是红）
        assertEquals(RejectCode.values().length, coveredBranches + 1,
                "RejectCode 必须与拒收原因一一对应：18 条 accept 分支 + settleInTransaction 那 1 条");

        // (e) 安全红线：detail 里不许出现报文原文、签名、密文、密钥、商户号、证书序列号、流水号、openid、金额
        Notification leakProbe = notification(ORDER_ID, CORRECT_FEN + 1, "SUCCESS");
        String ciphertext = auditJson.readTree(leakProbe.body()).path("resource").path("ciphertext").asText();
        List<String> forbiddenFragments = List.of(API_V3_KEY, WRONG_API_V3_KEY, MCHID, SERIAL, RESOURCE_NONCE, AAD,
                leakProbe.body(), leakProbe.signature(), ciphertext,
                "420000202610131006000001", "o-test-openid", "wxtestappid000000000",
                "out_trade_no", "trade_state", "amount", "1500", "1501", "15.00");
        for (String detail : allDetails) {
            for (String forbidden : forbiddenFragments) {
                assertFalse(detail.contains(forbidden),
                        "detail 里出现了敏感串「" + forbidden + "」：拒收原因天然带着报文/签名/金额，"
                                + "这一列在运营端是原文可查的，落进去等于把回调链路又开了一个读接口。实际 " + detail);
            }
        }
    }

    /**
     * 第 19 个抛出点（{@code settleInTransaction} 的结算包装）：同一条 catch 里也必须留痕，
     * 且用的是它自己的 {@code SETTLEMENT_FAILED}——它与「查单失败」共用同一句文案和同一枚 500，
     * code 不分开的话运营就永远分不清「钱还没入账」和「入账动作半态」。
     */
    @Test
    void settlementFailureRejectAlsoWritesExactlyOneAuditRow() throws Exception {
        when(orderMapper.update(isNull(), any())).thenThrow(new QueryTimeoutException("数据库抖动"));
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = post(ORDER_ID, n);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatusCode().value());
        assertFailBody(response, "微信支付回调处理失败");
        assertEquals(1, auditRows.size(), "结算失败也是拒收，必须恰好一行审计");
        AdminAuditLog row = auditRows.get(0);
        assertEquals("system:wx-notify", row.getActor());
        assertEquals("pay.notify_reject", row.getAction());
        assertEquals("order", row.getTargetType());
        assertEquals(ORDER_ID, row.getTargetId());
        JsonNode detail = auditJson.readTree(row.getDetail());
        Set<String> keys = new LinkedHashSet<>();
        detail.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code", "httpStatus"), keys, "结算失败那行的 detail 同样只许两键");
        assertEquals("SETTLEMENT_FAILED", detail.get("code").asText());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), detail.get("httpStatus").asInt());
        // 与「查单失败」那枚 code 必须是两枚（两条原因同文案同状态码，靠 code 区分）
        assertNotEquals("ORDER_LOOKUP_FAILED", detail.get("code").asText());
    }

    /**
     * 路径订单号拿不到（空白/null）时 {@code target_id} 写 {@code '-'} 而不是空串：
     * 该列 {@code VARCHAR(64) NOT NULL}，空串行插不进去（1048），宁留占位符也不能脱审。
     */
    @Test
    void missingPathOrderIdIsAuditedWithDashPlaceholder() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        // List.of 不许 null 元素，这里要的就是「拿不到订单号」的三种形态，含 null
        for (String pathOrderId : java.util.Arrays.asList("", "   ", null)) {
            auditRows.clear();
            ResponseEntity<Map<String, String>> response = controller().wxNotify(
                    pathOrderId, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());

            // 路径单号与 out_trade_no 不符 → 走的仍是拒收分支（不是 200、不是 500 崩掉）
            assertFailBody(response, "微信支付回调订单号不匹配");
            assertEquals(1, auditRows.size(), "路径订单号为「" + pathOrderId + "」时也必须留下一行审计");
            AdminAuditLog row = auditRows.get(0);
            assertEquals("-", row.getTargetId(), "拿不到订单号就写 '-'（列 NOT NULL，空串会让整行审计插不进去）");
            assertNotEquals("", row.getTargetId(), "绝不允许空串");
            JsonNode detail = auditJson.readTree(row.getDetail());
            Set<String> keys = new LinkedHashSet<>();
            detail.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("code", "httpStatus"), keys, "占位符只影响 target_id，detail 形状不变");
            assertEquals("OUT_TRADE_NO_MISMATCH", detail.get("code").asText());
        }
    }

    /**
     * 成功结算路径 0 行审计（否则审计表被正常流量灌满，这个功能立刻失去可用性）。
     * 尾段带一条<b>反向对照</b>：同一套装配下换一条拒收分支必须写得出 1 行——
     * 没有这条对照，「0 行」可能只是审计根本没接通的假绿。
     */
    @Test
    void successfulSettlementWritesNoAuditRow() throws Exception {
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> first = post(ORDER_ID, n);
        assertEquals(HttpStatus.OK.value(), first.getStatusCode().value());
        assertNull(first.getBody(), "成功应答不带业务 JSON");
        assertTrue(auditRows.isEmpty(), "成功结算一行审计都不许写");

        ResponseEntity<Map<String, String>> replay = post(ORDER_ID, n);
        assertEquals(HttpStatus.OK.value(), replay.getStatusCode().value(), "幂等重放同样算成功");
        assertTrue(auditRows.isEmpty(), "重放同样一行都不写");
        verify(adminAuditLogMapper, never()).insert(any(AdminAuditLog.class));

        // 反向对照：同一 controller 装配，拒收分支必须写得出 1 行（证明上面那个 0 是「不写」而不是「没接通」）
        freshFixture();
        ResponseEntity<Map<String, String>> rejected = post(ORDER_ID, notification(ORDER_ID, CORRECT_FEN + 1, "SUCCESS"));
        assertFailBody(rejected, "微信支付回调金额与订单不符");
        assertEquals(1, auditRows.size(), "对照面：审计通路是通的，成功路径的 0 行才有意义");
    }

    /**
     * 第 4 点的存在理由，必须有实证：<b>审计写入器抛异常时，HTTP 状态码与响应文案与不抛时逐字一致</b>。
     * 五条分支覆盖应答表里全部五枚码（400/401/404/500/503）——「可重试」那条尤其不能因为留痕挂了就变成丢单。
     */
    @Test
    void auditWriterFailureNeverChangesHttpStatusOrBody() throws Exception {
        // 探针只管「这条分支怎么打」，controller 由探针自己按分支需要装配（mchid 未配置那条必须用空 mchid），
        // 只有审计写入器是外面换进来的——这正是本用例唯一的自变量
        List<Map.Entry<String, BranchOnAuditWriter>> probes = List.of(
                Map.entry("400 未配置", (BranchOnAuditWriter) writer -> {
                    Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
                    return controller("", API_V3_KEY, writer, false)
                            .wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());
                }),
                Map.entry("401 验签失败", (BranchOnAuditWriter) writer -> {
                    Notification forged = notification(ORDER_ID, CORRECT_FEN, "SUCCESS",
                            API_V3_KEY, merchantKeyPair.getPrivate());
                    return controller(MCHID, API_V3_KEY, writer, false)
                            .wxNotify(ORDER_ID, forged.body(), forged.timestamp(), forged.nonce(),
                                    forged.signature(), forged.serial());
                }),
                Map.entry("404 订单不存在", (BranchOnAuditWriter) writer -> {
                    Notification other = notification(OTHER_ORDER_ID, CORRECT_FEN, "SUCCESS");
                    return controller(MCHID, API_V3_KEY, writer, false)
                            .wxNotify(OTHER_ORDER_ID, other.body(), other.timestamp(), other.nonce(),
                                    other.signature(), other.serial());
                }),
                Map.entry("500 结算内部异常", (BranchOnAuditWriter) writer -> {
                    when(orderMapper.update(isNull(), any())).thenThrow(new QueryTimeoutException("数据库抖动"));
                    Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
                    return controller(MCHID, API_V3_KEY, writer, false)
                            .wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());
                }),
                Map.entry("503 平台证书不可用", (BranchOnAuditWriter) writer -> {
                    Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");
                    return controller(MCHID, API_V3_KEY, writer, false)
                            .wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), n.signature(), "SERIAL-NOT-CACHED");
                }));

        for (Map.Entry<String, BranchOnAuditWriter> probe : probes) {
            String label = probe.getKey();
            BranchOnAuditWriter branch = probe.getValue();

            // 正常 writer（真 AdminServiceImpl + 假库）：确确实实写了 1 行（否则这条对照什么都没在测）
            freshFixture();
            ResponseEntity<Map<String, String>> withAudit = branch.run(auditWritingService());
            assertEquals(1, auditRows.size(), label + "：对照的前半段——审计通路必须是通的");

            // 挂掉的 writer：同一条分支再打一遍
            freshFixture();
            ResponseEntity<Map<String, String>> withoutAudit = branch.run(explodingAuditWriter());
            assertEquals(0, auditRows.size(), label + "：挂掉的 writer 一行都没写成（否则没在测「失败」）");

            // 实证：状态码与 body 与不抛时**完全一致**
            assertEquals(withAudit.getStatusCode().value(), withoutAudit.getStatusCode().value(),
                    label + "：审计挂了不得改 HTTP 码——本端点的码是「已扣款订单不丢单」的唯一保障");
            assertEquals(withAudit.getBody(), withoutAudit.getBody(), label + "：审计挂了不得改响应文案");
            assertNotEquals(HttpStatus.OK.value(), withoutAudit.getStatusCode().value(),
                    label + "：留痕挂掉也绝不能把一次可重试的拒收洗成 200（200 = 微信永久停止重发 = 丢单）");
        }
    }

    /**
     * 审计的 ip 走<b>本仓既有限流口径</b>（{@code rate-limit.trust-proxy}），不是新造的一套：
     * false（默认）直取 TCP 对端、{@code X-Forwarded-For} 一概不认；true 才取 XFF 首跳。
     * 判据不是「我猜一个值」，而是拿 {@code RateLimitInterceptor#clientIp} 的<b>同一枚 request</b>
     * 反射跑一遍，两处取值必须相等——限流桶与审计行对不上号，排障就是白排。
     */
    @Test
    void auditIpFollowsTheExistingTrustProxyRule() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.11.12.13");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RateLimitInterceptor rateLimit = new RateLimitInterceptor();
        Method interceptorClientIp = RateLimitInterceptor.class.getDeclaredMethod("clientIp", HttpServletRequest.class);
        interceptorClientIp.setAccessible(true);
        ReflectionTestUtils.setField(rateLimit, "trustProxy", false);
        String expectedWithoutProxy = (String) interceptorClientIp.invoke(rateLimit, request);
        ReflectionTestUtils.setField(rateLimit, "trustProxy", true);
        String expectedWithProxy = (String) interceptorClientIp.invoke(rateLimit, request);
        assertEquals("10.11.12.13", expectedWithoutProxy, "限流侧基准值：false 时不认 XFF");
        assertEquals("203.0.113.7", expectedWithProxy, "限流侧基准值：true 时取 XFF 首跳");

        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        auditRows.clear();
        controller().wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), null, n.serial());
        assertEquals(1, auditRows.size());
        assertEquals(expectedWithoutProxy, auditRows.get(0).getIp(),
                "默认 trust-proxy=false：审计 ip 必须与限流桶同一取值（XFF 是可伪造头，认了就能污染审计）");

        auditRows.clear();
        controller(MCHID, API_V3_KEY, auditWritingService(), true)
                .wxNotify(ORDER_ID, n.body(), n.timestamp(), n.nonce(), null, n.serial());
        assertEquals(1, auditRows.size());
        assertEquals(expectedWithProxy, auditRows.get(0).getIp(),
                "显式声明在可信反代后：取 XFF 首跳，判定顺序与 RateLimitInterceptor#clientIp 逐字相同");
    }

    /**
     * 路径订单号超 {@code ops_log.target_id} 列宽（VARCHAR(64)）时<b>截到列宽而不是丢行</b>：
     * 路径段完全由请求方控制，超长串正是最该留下审计行的探测流量；让它撞 1406 被静默吞掉，
     * 就是 BE-2 在本类里已经消灭过的那类「不可发现的脱审」。
     * 对外应答（400 + 那句文案）不因截断而变——审计是旁路，这条是它的存在前提。
     */
    @Test
    void overlongPathOrderIdIsClippedToColumnWidthAndNeverLosesTheRow() throws Exception {
        String overlongOrderId = "9".repeat(100);
        Notification n = notification(ORDER_ID, CORRECT_FEN, "SUCCESS");

        ResponseEntity<Map<String, String>> response = controller().wxNotify(
                overlongOrderId, n.body(), n.timestamp(), n.nonce(), n.signature(), n.serial());

        assertFailBody(response, "微信支付回调订单号不匹配");
        assertEquals(1, auditRows.size(), "超长订单号绝不能让整行审计消失（宁截断，不脱审）");
        AdminAuditLog row = auditRows.get(0);
        assertEquals(64, row.getTargetId().length(), "截到 target_id 列宽（VARCHAR(64)）");
        assertEquals(overlongOrderId.substring(0, 64), row.getTargetId());
        JsonNode detail = auditJson.readTree(row.getDetail());
        Set<String> keys = new LinkedHashSet<>();
        detail.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code", "httpStatus"), keys, "截断只影响 target_id，detail 形状不变");
        assertEquals("OUT_TRADE_NO_MISMATCH", detail.get("code").asText());
    }

    /**
     * 兜底那一趟也不许换形状：审计 core 的 BE-2 兜底原本是 {@code {"old","new"}}，
     * 拒收留痕两趟写的都必须是<b>同一枚两键 JSON</b>（形状是运营端筛选与后续脚本的依赖）。
     * 同时验「两趟都失败也只是 warn，绝不把异常冒出去」——本方法在 controller 的 catch 里，
     * 但 AdminServiceImpl 自己那层兜底同样不许把留痕失败变成业务失败（契约 §2.10b 红线）。
     */
    @Test
    void notifyAuditKeepsItsTwoKeyDetailEvenWhenInsertKeepsFailing() throws Exception {
        AdminAuditLogMapper alwaysFailing = mock(AdminAuditLogMapper.class);
        when(alwaysFailing.insert(any(AdminAuditLog.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("1406 列宽不够"));
        AdminServiceImpl service = new AdminServiceImpl();
        ReflectionTestUtils.setField(service, "adminAuditLogMapper", alwaysFailing);

        service.recordNotifyReject("AMOUNT_MISMATCH", 400, ORDER_ID, "10.0.0.1");

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(alwaysFailing, times(2)).insert(captor.capture());
        for (AdminAuditLog row : captor.getAllValues()) {
            JsonNode detail = auditJson.readTree(row.getDetail());
            Set<String> keys = new LinkedHashSet<>();
            detail.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("code", "httpStatus"), keys,
                    "兜底那趟必须保持两键形状，不许退成 {\"old\",\"new\"}：" + row.getDetail());
            assertEquals("AMOUNT_MISMATCH", detail.get("code").asText());
            assertEquals(400, detail.get("httpStatus").asInt());
        }
    }
}
