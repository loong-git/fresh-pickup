package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fresh.common.GlobalExceptionHandler;
import com.fresh.common.R;
import com.fresh.common.WordFilter;
import com.fresh.dto.ReviewCreateDTO;
import com.fresh.entity.Review;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.UserMapper;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-124 评价强幂等契约单测（不起 Spring 容器、不落库；契约 §5 四判据逐条 + 控制器出口）：
 * ① 带键首提正常落库——插入路径原样（键随实体落库、归属校验/脱敏/好评率回写一条不少），
 *    快查 wrapper 真渲染断言 eq client_request_id（照 DishAuditFlowTest 的真渲染口径，不重抄条件）；
 * ② 带键重提返回首次评价行——不再 insert、不再回写好评率，且连购买归属校验都短路
 *    （幂等命中在一切校验之前，重复提交零写库零额外读）；
 * ③ 不带键行为与旧完全一致（兼容支）——不发快查、不冒键，insert/好评率回写照旧；
 *    纯空白键同口径归一为 null（不占 uk_review_client_request_id 唯一索引）；
 * ④ DTO clientRequestId 超长 64 被 @Size 拒绝——真校验器灌违约、期望文案反射读注解不手抄，
 *    违约经 GlobalExceptionHandler#handleValid 出口仍是 code:400（信封纪律不受影响）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：uk_review_client_request_id 真库唯一约束与
 * 并发同键双写的数据库侧兜底、限流 1 次/10s（Controller 注解层，由主会话门禁覆盖）——
 * mock 侧只断言 Service 交付的查询/写侧意图与 Controller 出参。
 */
class F124ReviewIdempotencyTest {

    // ts 段用 10 位秒级值：13 位毫秒串的任意 11 位连续子段恒命中 f28 §10c 的手机号形态判据
    // （1[3-9]\d{9}），秒级打断连续数字段——mock 断言对键值本身无依赖，仅作任意幂等键样本。
    private static final String KEY = "r-1-1769600000-ab12";
    private static final String SUCCESS_MESSAGE = "评价成功";

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    private final WordFilter wordFilter = new WordFilter(); // 脱容器词表为空 → 硬/软词均不命中，零 IO

    private ReviewServiceImpl service() {
        ReviewServiceImpl service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "orderItemMapper", orderItemMapper);
        return service;
    }

    /** 购买归属校验放行 + 登录用户存在（13800001234 → 脱敏 138****1234）的默认桩 */
    private void stubPurchaseAndUser() {
        when(orderItemMapper.countByUserIdAndDishId(eq(9L), eq(1L))).thenReturn(1);
        User user = new User();
        user.setPhone("13800001234");
        when(userMapper.selectById(9L)).thenReturn(user);
    }

    private static Review review(String clientRequestId) {
        Review review = new Review();
        review.setDishId(1L);
        review.setRating(5);
        review.setContent("很新鲜");
        review.setClientRequestId(clientRequestId);
        return review;
    }

    /** insert 桩：mock 自增回填 id，返回 1 行 */
    private void stubInsertAssignsId(long id) {
        when(reviewMapper.insert(any(Review.class))).thenAnswer(inv -> {
            inv.getArgument(0, Review.class).setId(id);
            return 1;
        });
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<Review> capturedKeyQueryWrapper() {
        ArgumentCaptor<Wrapper<Review>> captor =
                (ArgumentCaptor<Wrapper<Review>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
        verify(reviewMapper).selectOne(captor.capture());
        return (QueryWrapper<Review>) captor.getValue();
    }

    // ================================================================
    // ① 带键首提：正常落库，插入路径原样
    // ================================================================

    @Test
    void 带键首提正常落库_键随实体写入_归属校验与好评率回写照旧() {
        stubPurchaseAndUser();
        when(reviewMapper.selectOne(any())).thenReturn(null); // 幂等快查未命中
        stubInsertAssignsId(5L);
        when(dishMapper.refreshGoodRate(1L)).thenReturn(1);

        Review in = review(KEY);
        Review out = service().addReview(in, 9L);

        assertSame(in, out, "未命中幂等时返回落库后的同一实体（Controller 回传的就是它）");
        assertEquals(5L, out.getId().longValue());
        QueryWrapper<Review> wrapper = capturedKeyQueryWrapper();
        assertTrue(wrapper.getSqlSegment().contains("client_request_id"),
                "快查必须按 client_request_id 列查：segment=" + wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(KEY),
                "快查必须绑定入参键本身：params=" + wrapper.getParamNameValuePairs());
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).insert(captor.capture());
        assertEquals(KEY, captor.getValue().getClientRequestId(), "键必须随实体落库");
        assertEquals(9L, captor.getValue().getUserId().longValue());
        assertEquals("138****1234", captor.getValue().getReviewer(), "M6 脱敏昵称照旧由服务端生成");
        verify(dishMapper, times(1)).refreshGoodRate(1L);
    }

    // ================================================================
    // ② 带键重提：返回首次行，零写库
    // ================================================================

    @Test
    void 带键重提返回首次评价行_不再insert_好评率只回写一次() {
        Review first = review(KEY);
        first.setId(3L);
        // P11-2 P2-C 桩适配：归属收窄后幂等命中仅限「同用户同键」，首次行须带提交者 userId
        // 才构成本人同键（补桩数据不改断言语义；未带 userId 的他人键行为由下方跨用户用例单独锁）
        first.setUserId(9L);
        when(reviewMapper.selectOne(any())).thenReturn(first); // 同键已存在 → 幂等命中

        Review out = service().addReview(review(KEY), 9L);

        assertSame(first, out, "同键重提必须返回首次评价行（code:200 同一 id，与订单幂等同构）");
        assertEquals(3L, out.getId().longValue());
        verify(reviewMapper, never()).insert(any(Review.class));
        verify(dishMapper, never()).refreshGoodRate(any());
        // 幂等命中在购买归属校验之前短路：重复提交连归属校验都不再跑
        verify(orderItemMapper, never()).countByUserIdAndDishId(any(), any());
    }

    // ================================================================
    // ②b P11-2 P2-C 跨用户撞键：他人键不返回他人行，B 建自己的新评价行
    // ================================================================

    @Test
    void 跨用户撞键_他人键视为新键置null_B建自己的新评价行() {
        stubPurchaseAndUser();
        Review aRow = review(KEY);
        aRow.setId(7L);
        aRow.setUserId(8L); // A 已落的评价行（键归属 A）
        when(reviewMapper.selectOne(any())).thenReturn(aRow);
        stubInsertAssignsId(9L);
        when(dishMapper.refreshGoodRate(1L)).thenReturn(1);

        Review in = review(KEY); // B（userId=9）用 A 的键提交
        Review out = service().addReview(in, 9L);

        assertSame(in, out, "B 拿到的是自己落库的新行，绝不是 A 的评价行");
        assertEquals(9L, out.getId().longValue(), "新评价行 id 是本次 insert 回填的，不是 A 行的 7");
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).insert(captor.capture());
        assertNull(captor.getValue().getClientRequestId(),
                "他人键必须置 null 落库（评价键可空先例，不占 uk_review_client_request_id 唯一索引）");
        assertEquals(9L, captor.getValue().getUserId().longValue(), "新行归属 B 本人");
        verify(dishMapper, times(1)).refreshGoodRate(1L); // 正常插入路径照跑：好评率回写不缺
        verify(orderItemMapper, times(1)).countByUserIdAndDishId(eq(9L), eq(1L)); // 购买归属校验照跑
        verify(reviewMapper, never()).deleteById(anyLong()); // A 的行零改动
    }

    // ================================================================
    // ②c P11-2 登记遗留：并发同键首发撞 uk_review_client_request_id（快查双双未命中，
    //     对方事务先 insert 占键 → 本事务 insert 抛 DuplicateKeyException）。mock 侧测 Service
    //     交付的 catch 分支意图：FOR UPDATE 回查胜出行 → 本人幂等返回 / 他人置空键重插；
    //     真库唯一约束与锁行为仍是「本类测不成什么」口径（见类注释）
    // ================================================================

    @Test
    void 并发撞键本人胜出_幂等返回首次行_零重复回写零重插() {
        Review first = review(KEY);
        first.setId(3L);
        first.setUserId(9L); // 并发胜出行=本人首发行
        stubPurchaseAndUser();
        // 第一次 selectOne=幂等快查未命中；第二次=撞键后 FOR UPDATE 回查命中本人首发行
        when(reviewMapper.selectOne(any())).thenReturn(null, first);
        when(reviewMapper.insert(any(Review.class)))
                .thenThrow(new DuplicateKeyException("uk_review_client_request_id")); // 并发对方已占键

        Review in = review(KEY);
        Review out = service().addReview(in, 9L);

        assertSame(first, out, "胜出行是本人 → 幂等返回首次评价行（与快查命中同语义）");
        assertEquals(3L, out.getId().longValue());
        // 回查必须带 FOR UPDATE（锁定读绕过 REPEATABLE READ 快照才能读到并发已提交的胜出行）
        ArgumentCaptor<Wrapper<Review>> wrappers =
                (ArgumentCaptor<Wrapper<Review>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
        verify(reviewMapper, times(2)).selectOne(wrappers.capture());
        assertTrue(((QueryWrapper<Review>) wrappers.getAllValues().get(1)).getSqlSegment().contains("FOR UPDATE"),
                "撞键回查必须是锁定读：segment=" + wrappers.getAllValues().get(1).getSqlSegment());
        verify(reviewMapper, times(1)).insert(any(Review.class)); // 只有失败那次，无重插
        verify(dishMapper, never()).refreshGoodRate(any());       // 零重复回写（好评率由首提事务回写）
    }

    @Test
    void 并发撞键非本人胜出_置空键重插_B建自己的新评价_好评率恰好一次() {
        stubPurchaseAndUser();
        Review aRow = review(KEY);
        aRow.setId(7L);
        aRow.setUserId(8L); // A 的并发胜出行（键归属 A）
        // 第一次 selectOne=幂等快查未命中；第二次=撞键后 FOR UPDATE 回查命中 A 行
        when(reviewMapper.selectOne(any())).thenReturn(null, aRow);
        // 键值必须按「调用时刻」记录：service 复用同一实体并在重插前置 null，ArgumentCaptor 存引用，
        // 事后读到的只会是终态 null，读不出首插时的原键
        List<String> keyAtInsert = new ArrayList<>();
        when(reviewMapper.insert(any(Review.class))).thenAnswer(inv -> {
            keyAtInsert.add(inv.getArgument(0, Review.class).getClientRequestId());
            if (keyAtInsert.size() == 1) {
                throw new DuplicateKeyException("uk_review_client_request_id"); // 首插撞 A 的键
            }
            inv.getArgument(0, Review.class).setId(10L); // 置空键重插成功
            return 1;
        });
        when(dishMapper.refreshGoodRate(1L)).thenReturn(1);

        Review in = review(KEY); // B（userId=9）与 A 并发用同一首发键
        Review out = service().addReview(in, 9L);

        assertSame(in, out, "B 拿到的是自己重插落库的新行，绝不是 A 的评价行");
        assertEquals(10L, out.getId().longValue(), "新行 id 是重插回填的，不是 A 行的 7");
        verify(reviewMapper, times(2)).insert(any(Review.class));
        assertEquals(java.util.Arrays.asList(KEY, null), keyAtInsert,
                "首插带原键（撞键前已认领）；胜出行非本人 → 置 null 重插（同快查他人键口径，不占他人键）");
        assertEquals(9L, out.getUserId().longValue(), "重插行归属 B 本人");
        verify(dishMapper, times(1)).refreshGoodRate(1L); // 好评率恰好一次（不因重试双计）
        verify(orderItemMapper, times(1)).countByUserIdAndDishId(eq(9L), eq(1L)); // M6 校验恰跑一次，无重跑
    }

    // ================================================================
    // ③ 不带键（兼容支）：行为与旧完全一致
    // ================================================================

    @Test
    void 不带键不发快查不冒键_插入与好评率回写与旧行为一致() {
        stubPurchaseAndUser();
        stubInsertAssignsId(6L);
        when(dishMapper.refreshGoodRate(1L)).thenReturn(1);

        Review in = review(null); // 旧前端不带键
        Review out = service().addReview(in, 9L);

        assertSame(in, out);
        verify(reviewMapper, never()).selectOne(any());
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).insert(captor.capture());
        assertNull(captor.getValue().getClientRequestId(), "不带键不得凭空生出键值");
        verify(dishMapper, times(1)).refreshGoodRate(1L);
    }

    @Test
    void 纯空白键归一为null_不占唯一索引不发快查() {
        stubPurchaseAndUser();
        stubInsertAssignsId(7L);

        Review in = review("   ");
        service().addReview(in, 9L);

        verify(reviewMapper, never()).selectOne(any());
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).insert(captor.capture());
        assertNull(captor.getValue().getClientRequestId(), "空白键归一为 null（可空 UNIQUE 允许多行 NULL）");
    }

    // ================================================================
    // ④ DTO @Size(64) 超长 400（照 OrderCreateStoreIdRequiredTest 的校验器姿势）
    // ================================================================

    private static final LocalValidatorFactoryBean VALIDATOR = initValidator();

    private static LocalValidatorFactoryBean initValidator() {
        try {
            LocalValidatorFactoryBean lvfb = new LocalValidatorFactoryBean();
            lvfb.afterPropertiesSet();
            return lvfb;
        } catch (Exception e) {
            throw new IllegalStateException("LocalValidatorFactoryBean 初始化失败", e);
        }
    }

    /** 从 DTO 注解上反射读原始文案（不手抄；读不到就当场失败，防止期望值与注解悄悄分家） */
    private static String annotationMessage(String fieldName, Class<? extends Annotation> annotationType) throws Exception {
        Field field = ReviewCreateDTO.class.getDeclaredField(fieldName);
        for (Annotation a : field.getAnnotations()) {
            if (annotationType.isInstance(a)) {
                return (String) annotationType.getMethod("message").invoke(a);
            }
        }
        throw new IllegalStateException("ReviewCreateDTO." + fieldName + " 上找不到 " + annotationType.getSimpleName());
    }

    private static BindingResult validate(ReviewCreateDTO dto) {
        BindingResult result = new BeanPropertyBindingResult(dto, "reviewCreateDTO");
        VALIDATOR.validate(dto, result);
        return result;
    }

    private static ReviewCreateDTO dtoWithKey(String clientRequestId) {
        ReviewCreateDTO dto = new ReviewCreateDTO();
        dto.setRating(5);
        dto.setContent("很新鲜");
        dto.setClientRequestId(clientRequestId);
        return dto;
    }

    @Test
    void DTO幂等键超长64被拒绝_经统一异常出口仍是400() throws Exception {
        String sizeMsg = annotationMessage("clientRequestId", Size.class);

        BindingResult result = validate(dtoWithKey("k".repeat(65)));

        List<String> messages = new ArrayList<>();
        for (FieldError fe : result.getFieldErrors()) {
            if ("clientRequestId".equals(fe.getField())) {
                messages.add(fe.getDefaultMessage());
            }
        }
        assertEquals(List.of(sizeMsg), messages, "超长键必须且只能报注解上那一句");
        assertEquals(1, result.getAllErrors().size(), "其余入参全合法，总违约数必须恰为 1：" + result.getAllErrors());

        R<Void> body = new GlobalExceptionHandler().handleValid(new BindException(result));
        assertEquals(R.CODE_BAD_REQUEST, body.getCode(), "校验失败出口仍是信封 400（HTTP 恒 200 的纪律不变）");
        assertEquals(sizeMsg, body.getMessage(), "message 即 DTO 注解上的原句");
    }

    @Test
    void DTO合法长度幂等键零违约_可空不强制() throws Exception {
        assertTrue(validate(dtoWithKey(KEY)).getAllErrors().isEmpty(), "64 字内键不违约：dto=" + dtoWithKey(KEY));
        assertTrue(validate(dtoWithKey(null)).getAllErrors().isEmpty(), "不带键（null）也不违约——可空不强制，旧前端向后兼容");
    }

    // ================================================================
    // 控制器出口：幂等命中返回 R.ok(首次评价行, 原句 message)
    // ================================================================

    @Test
    void 控制器幂等命中返回首次评价行_message维持原句() throws Exception {
        com.fresh.controller.ReviewController controller = new com.fresh.controller.ReviewController();
        com.fresh.service.ReviewService svc = mock(com.fresh.service.ReviewService.class);
        ReflectionTestUtils.setField(controller, "reviewService", svc);
        Review first = review(KEY);
        first.setId(3L);
        when(svc.addReview(any(), eq(9L))).thenReturn(first);

        R<Review> body = controller.addReview(1L, dtoWithKey(KEY), validate(dtoWithKey(KEY)), 9L);

        assertEquals(200, body.getCode());
        assertEquals(SUCCESS_MESSAGE, body.getMessage(), "message 维持「评价成功」原句，前端无感知");
        assertSame(first, body.getData(), "data 恰为 service 返回的首次评价行");
        verify(svc, times(1)).addReview(any(), eq(9L));
    }
}
