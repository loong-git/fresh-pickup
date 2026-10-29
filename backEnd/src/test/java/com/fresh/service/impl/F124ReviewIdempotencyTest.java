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

    private static final String KEY = "r-1-1769600000000-ab12";
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
