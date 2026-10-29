package com.fresh.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fresh.common.R;
import com.fresh.dto.ReviewCreateDTO;
import com.fresh.entity.Review;
import com.fresh.service.ReviewService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F-51 写接口信封契约单测（不起 Spring 容器、零写库）：
 * ReviewController.java 的 addReview 由手工 Map 收进统一信封 R 后（T-M1-02 最后一个已知漏口），
 * 两个出口（400 校验失败支 / 200 成功支）序列化出的顶层键集合都恰为 {code, data, message}，
 * 且 code 与 message 的取值与收信封前逐字相同——本批改的只有「用哪个容器装它们」。
 *
 * 三条承重判据（对应派单「测试」节 1/2/3）：
 * 1) 两出口三键齐；400 支的 data 必须是<b>显式 null 键</b>而不是缺键（F-42b 定的信封纪律：缺键与
 *    显式 null 对客户端是两种语义）。改前那支配对只有 code/message 两键，正是要消掉的形态，故这里
 *    用 has()（= containsKey 语义）断言，不用「值非 null」代替。
 * 2) message 与 DTO 注解<b>同源</b>：不手抄文案——违约走 Spring MVC 处理 @Valid 时背后那同一个校验器
 *    LocalValidatorFactoryBean 灌进 BindingResult，期望值则从 ReviewCreateDTO 注解的 message() 属性上
 *    <b>反射读出来</b>逐字比对；多条违约时断言分隔符是中文全角分号「；」且切出的段数与违约条数一致。
 * 3) 零写库：reviewService 全程 mock。400 支必须一次都没碰过 addReview（校验失败不落库是既有语义，
 *    不能因为改信封而丢）；200 支必须恰好碰一次，且入参实体仍是「路径 dishId + dto rating/content」。
 *
 * 序列化用 Jackson2ObjectMapperBuilder.json()，即 Spring Boot MVC 消息转换器同款构造路径
 * （与同目录 ReadEnvelopeContractTest 一致）。全仓无任何 NON_NULL / default-property-inclusion 配置，
 * 所以 null 一律输出——R#fail 只赋 code/message，data 留字段默认 null，序列化即显式 data:null。
 */
class WriteEnvelopeContractTest {

    /** 信封三键及其期望键序（R 的字段声明序 code/data/message，Jackson 按此出键） */
    private static final String ENV_KEYS_IN_ORDER = "[code, data, message]";
    private static final String SUCCESS_MESSAGE = "评价成功";

    /** MVC 消息转换器同款 ObjectMapper：null 不省略、非 ASCII 不转义 */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    /** Spring MVC 校验 @Valid 时背后的同一个校验器；脱容器手工初始化一次后复用 */
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

    /** 字段注入的 @Autowired 依赖在纯单测里手工塞入（无容器），照 ReadEnvelopeContractTest 的姿势 */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ReviewController controllerWith(ReviewService svc) throws Exception {
        ReviewController controller = new ReviewController();
        inject(controller, "reviewService", svc);
        return controller;
    }

    /**
     * 用真实校验器把 @Valid 的违约落成 BindingResult——与 addReview 形参里 Spring 塞进来的那个同源。
     * 顺序按「属性名+文案」稳定化，免得断言依赖校验器未规定的遍历序。
     */
    private static BindingResult validateInto(ReviewCreateDTO dto) {
        BindingResult raw = new BeanPropertyBindingResult(dto, "reviewCreateDTO");
        VALIDATOR.validate(dto, raw);
        List<FieldError> sorted = new ArrayList<>(raw.getFieldErrors());
        sorted.sort((a, b) -> (a.getField() + a.getDefaultMessage()).compareTo(b.getField() + b.getDefaultMessage()));
        BeanPropertyBindingResult ordered = new BeanPropertyBindingResult(dto, "reviewCreateDTO");
        for (FieldError fe : sorted) {
            ordered.addError(fe);
        }
        return ordered;
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

    private static ReviewCreateDTO dto(Integer rating, String content) {
        ReviewCreateDTO dto = new ReviewCreateDTO();
        dto.setRating(rating);
        dto.setContent(content);
        dto.setReviewer("用户1234");
        return dto;
    }

    private static Review fixtureReview() {
        Review r = new Review();
        r.setId(3L);
        r.setDishId(1L);
        r.setUserId(9L);
        r.setRating(5);
        r.setContent("很好");
        r.setReviewer("王**");
        r.setAuditStatus(null);
        r.setCreateTime(LocalDateTime.of(2026, 10, 12, 8, 0, 0));
        return r;
    }

    private static List<String> keysOf(JsonNode root) {
        List<String> keys = new ArrayList<>();
        root.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 顶层键判据：键集合恰为信封三键，且键序就是 code,data,message（顺序断言顺带把容器指纹也钉住） */
    private JsonNode assertEnvelopeKeys(JsonNode root) {
        assertNotNull(root, "序列化结果不应为 null");
        assertTrue(root.isObject(), "顶层应为 JSON 对象");
        assertEquals(ENV_KEYS_IN_ORDER, keysOf(root).toString(), "顶层键集合与键序都应恰为信封三键");
        return root;
    }

    // ==================== 判据 1：两个出口都是三键，400 支 data 显式 null 且键存在 ====================

    @Test
    void validationFailureBranchCarriesThreeKeysWithExplicitNullData() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(99, null);

        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(400, root.get("code").asInt());
        // 关键判据：data 是显式 null 键，不是缺键。has() 即 containsKey 语义，不许用「值非 null」代替
        assertTrue(root.has("data"), "400 支必须带 data 键");
        assertTrue(root.get("data").isNull(), "400 支的 data 必须是 null 值");
        assertFalse(root.has("total"), "信封顶层不该冒出 total 之类的旁键");
    }

    @Test
    void validationFailureRawJsonCarriesExplicitDataNullKey() throws Exception {
        String notBlankMsg = annotationMessage("content", NotBlank.class);
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(5, null);   // 只违约一条，原始串可整体逐字比对

        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);
        assertEquals("{\"code\":400,\"data\":null,\"message\":\"" + notBlankMsg + "\"}",
                mapper.writeValueAsString(body), "400 出参原始 JSON 串应显式带 data:null 且键序为 code,data,message");
    }

    @Test
    void successBranchCarriesThreeKeysAndPreservesCodeAndMessageLiterals() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        // F-124：addReview 改为返回落库/幂等命中的评价行、控制器照实回传——桩按同参回传入参实体，
        // 维持本类「data 即 service 收到的实体」原判据（旧 void 语义下控制器本地实体就是那份实体）
        doAnswer(inv -> inv.getArgument(0)).when(svc).addReview(any(), eq(9L));
        ReviewCreateDTO in = dto(5, "很好");

        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);
        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));

        assertEquals(200, root.get("code").asInt());
        assertEquals(SUCCESS_MESSAGE, root.get("message").asText(), "成功支 message 必须逐字是收信封前那句");
        assertTrue(root.get("data").isObject(), "成功支 data 仍是评价实体对象本体");
        assertFalse(root.has("total"), "顶层不应有 total 键");
    }

    // ==================== 判据 2：message 与 DTO 注解同源，多条用全角分号连接 ====================

    @Test
    void singleViolationMessageIsTheAnnotationTextVerbatim() throws Exception {
        String notBlankMsg = annotationMessage("content", NotBlank.class);
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(5, null);          // 只触发 @NotBlank 一条
        BindingResult br = validateInto(in);
        assertEquals(1, br.getFieldErrors().size(), "本用例前提：只有一条违约");

        String message = controllerWith(svc).addReview(1L, in, br, 9L).getMessage();

        assertEquals(notBlankMsg, message, "单条违约时 message 应恰为注解文案，一字不多不少");
        assertFalse(message.contains("；"), "单条违约不该带分隔符");
    }

    @Test
    void multiViolationMessagesAreJoinedByFullWidthSemicolonAndMatchAnnotationTexts() throws Exception {
        String maxMsg = annotationMessage("rating", Max.class);
        String notBlankMsg = annotationMessage("content", NotBlank.class);
        // @Min/@Max 共用同一句文案（本仓既有写法）；先钉住它没被悄悄改成两种说法，否则下面的集合断言会失配
        assertEquals(annotationMessage("rating", Min.class), maxMsg, "@Min 与 @Max 的文案应仍是同一句");

        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(99, null);         // @Max + @NotBlank 两条
        BindingResult br = validateInto(in);
        assertEquals(2, br.getFieldErrors().size(), "本用例前提：两条违约");

        String message = controllerWith(svc).addReview(1L, in, br, 9L).getMessage();
        String[] parts = message.split("；");
        assertEquals(2, parts.length, "两条违约应被全角分号恰切成两段，实得 " + message);
        assertEquals(Set.of(maxMsg, notBlankMsg), Set.of(parts), "每一段都必须与 DTO 注解文案逐字相同");
        assertFalse(message.contains(";"), "分隔符是中文全角分号，不得混入半角");
    }

    // ==================== 判据 3：零写库——400 支一次都不碰 service，200 支恰好一次 ====================

    @Test
    void validationFailureBranchNeverTouchesTheReviewService() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(99, null);

        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);

        assertEquals(400, body.getCode());
        verify(svc, never()).addReview(any(), any());
        verifyNoInteractions(svc);
    }

    @Test
    void successBranchCallsTheServiceExactlyOnceWithServerAssembledEntity() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(4, "新鲜现切");

        R<Review> body = controllerWith(svc).addReview(7L, in, validateInto(in), 9L);

        assertEquals(200, body.getCode());
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(svc, times(1)).addReview(captor.capture(), eq(9L));
        Review passed = captor.getValue();
        // dishId 来自路径变量、rating/content 来自 dto——载荷口径与收信封前一致
        assertEquals(7L, passed.getDishId().longValue());
        assertEquals(4, passed.getRating().intValue());
        assertEquals("新鲜现切", passed.getContent());
        // M6 语义：控制器不再把 dto.reviewer 塞进实体，落库昵称归 service 生成，这里必须是 null
        assertTrue(passed.getReviewer() == null, "控制器不该把 dto 的昵称带进实体（M6 已废弃前端直传昵称）");
    }

    // ==================== 成功支载荷未被信封动过 ====================

    @Test
    void nullEntityFieldsStayExplicitNullKeysInsideData() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        // F-124：桩按同参回传入参实体，见 successBranchCarriesThreeKeysAndPreservesCodeAndMessageLiterals 处说明
        doAnswer(inv -> inv.getArgument(0)).when(svc).addReview(any(), eq(9L));
        ReviewCreateDTO in = dto(5, "很好");
        // 控制器手上这只实体的 id/userId/reviewer/auditStatus/createTime 全是 null（service 未真落库），
        // 断言这些 null 不会把 data 键本身挤掉——收信封最怕的就是载荷里某个 null 变成整键消失
        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);

        JsonNode root = assertEnvelopeKeys(mapper.valueToTree(body));
        assertTrue(root.has("data"), "data 键必须恒在，即使实体字段有 null");
        JsonNode data = root.get("data");
        assertTrue(data.has("createTime") && data.get("createTime").isNull(), "实体自身的 null 字段也应是显式 null 键");
        assertTrue(data.has("reviewer") && data.get("reviewer").isNull());
        assertTrue(data.has("id") && data.get("id").isNull());
    }

    @Test
    void successBranchDataSubtreeEqualsTheEntityTheServiceGot() throws Exception {
        ReviewService svc = mock(ReviewService.class);
        ReviewCreateDTO in = dto(5, "很好");
        // 让 mock 像真 service 那样把 id/脱敏昵称/时间回填进传入实体（M6 的时间线），再验信封没动载荷；
        // F-124 起 addReview 返回该实体、控制器照实回传，故这里同参回传（旧 void 语义下返回 null）
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        doAnswer(invocation -> {
            Review target = invocation.getArgument(0);
            target.setId(3L);
            target.setUserId(9L);
            target.setReviewer("王**");
            target.setCreateTime(LocalDateTime.of(2026, 10, 12, 8, 0, 0));
            return target;
        }).when(svc).addReview(captor.capture(), eq(9L));

        R<Review> body = controllerWith(svc).addReview(1L, in, validateInto(in), 9L);
        Review passed = captor.getValue();

        assertEquals(mapper.writeValueAsString(passed), mapper.valueToTree(body).get("data").toString(),
                "data 子树应与 service 收到的实体单独序列化结果逐字节一致（信封只是外层包装）");
        // 夹具自检：同一形态实体单独走一遍，键集合不因换容器而增减
        assertEquals(keysOf(mapper.valueToTree(fixtureReview())).toString(),
                keysOf(mapper.valueToTree(passed)).toString(), "实体键集合应与既有夹具同形");
    }

    // ==================== 负对照：键判据不是橡皮章 ====================

    @Test
    void preChangeManualMapShapesAreRejectedByTheSameKeyPredicate() throws Exception {
        // 改前 400 支就是这两键——判据必须能认出「缺 data 键」不是信封，否则上面那条 has("data") 恒真无意义
        Map<String, Object> oldFailResp = new LinkedHashMap<>();
        oldFailResp.put("code", 400);
        oldFailResp.put("message", annotationMessage("rating", Max.class));
        JsonNode failNode = mapper.valueToTree(oldFailResp);
        assertEquals("[code, message]", keysOf(failNode).toString(), "改前 400 支本来就缺 data 键");
        assertFalse(failNode.has("data"), "判据要能识别改前那个「缺 data 键」");
        assertFalse(ENV_KEYS_IN_ORDER.equals(keysOf(failNode).toString()), "改前 400 支不该被判成信封");

        // 改前成功支三键齐但容器是手工 Map，键序是 code,message,data：值集合看着像信封，键序才是它的指纹。
        // 也就是说本类的鉴别力来自上面的缺键对照与两个出口的键序断言，不来自「三键」本身——如实钉住这点。
        Map<String, Object> oldOkResp = new LinkedHashMap<>();
        oldOkResp.put("code", 200);
        oldOkResp.put("message", SUCCESS_MESSAGE);
        oldOkResp.put("data", fixtureReview());
        JsonNode okNode = mapper.valueToTree(oldOkResp);
        assertEquals("[code, message, data]", keysOf(okNode).toString(),
                "改前成功支的键序是 code,message,data，收信封后是 code,data,message");
    }
}
