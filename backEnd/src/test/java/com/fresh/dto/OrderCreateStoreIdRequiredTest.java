package com.fresh.dto;

import com.fresh.common.GlobalExceptionHandler;
import com.fresh.common.R;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-80/S1 下单入参 storeId 收必填的校验单测（不起 Spring 容器、不连库、不发请求）。
 *
 * 姿势逐字照 controller 目录下的信封契约单测：Spring MVC 处理 @Valid 时背后就是
 * {@link LocalValidatorFactoryBean}，脱容器 afterPropertiesSet 一次即与线上同一个校验器；
 * 违约用 {@link BeanPropertyBindingResult} 承接，再交给真实的
 * {@link GlobalExceptionHandler#handleValid} 出信封——这样「注解改了 → 出参跟着改」这条链
 * 在本单测里是闭合的，不依赖重启后端就能静态证明。
 *
 * 四条判据（1/2 是双向自证，缺一边等于没验）：
 * ① 真例：缺 storeId 时报出的消息恰为注解上那句；
 * ② 假例：带合法 storeId 时**整份入参一条违约都不报**（不断言这条，① 的「不报」无从区分「合法」
 *    与「fixture 本身就没喂对」）；
 * ③ 零新增文案：新挂的 {@code NotNull} 消息必须与同字段既有 {@code Min} 消息逐字相同——
 *    手抄一份期望值会随注解漂移假绿，故期望值反射读注解，摘掉 {@code NotNull} 直接抛
 *    {@link IllegalStateException} 让本批判据变红（反向自验用）。
 * ④ 失败链出口：该违约经 {@link GlobalExceptionHandler#handleValid} 仍是 code:400 + 同一句 message，
 *    信封形状与 HTTP 恒 200 的既有纪律不受影响。
 */
class OrderCreateStoreIdRequiredTest {

    /** MVC 校验 @Valid 时背后的同一个校验器；脱容器手工初始化一次后复用 */
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
        Field field = OrderCreateDTO.class.getDeclaredField(fieldName);
        for (Annotation a : field.getAnnotations()) {
            if (annotationType.isInstance(a)) {
                return (String) annotationType.getMethod("message").invoke(a);
            }
        }
        throw new IllegalStateException("OrderCreateDTO." + fieldName + " 上找不到 " + annotationType.getSimpleName());
    }

    /** 除 storeId 外全部合法的下单入参：address/couponId 仍是可选项，不参与本批判据 */
    private static OrderCreateDTO dtoWithStoreId(Long storeId) {
        OrderCreateDTO.OrderItemDTO item = new OrderCreateDTO.OrderItemDTO();
        item.setDishId(1L);
        item.setQuantity(1);

        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setClientRequestId("f80-storeid-check");
        dto.setPhone("13800001234");
        dto.setItems(List.of(item));
        dto.setStoreId(storeId);
        return dto;
    }

    private static BindingResult validate(OrderCreateDTO dto) {
        BindingResult result = new BeanPropertyBindingResult(dto, "orderCreateDTO");
        VALIDATOR.validate(dto, result);
        return result;
    }

    private static List<String> messagesOf(BindingResult result, String field) {
        List<String> messages = new ArrayList<>();
        for (FieldError fe : result.getFieldErrors()) {
            if (field.equals(fe.getField())) {
                messages.add(fe.getDefaultMessage());
            }
        }
        return messages;
    }

    // —— ① 真例：缺 storeId 必须被报出来，且只报这一条 ——

    @Test
    void missingStoreIdIsRejectedWithTheMessageAlreadyOnTheField() throws Exception {
        String required = annotationMessage("storeId", NotNull.class);

        BindingResult result = validate(dtoWithStoreId(null));

        assertEquals(List.of(required), messagesOf(result, "storeId"), "缺 storeId 必须且只能报注解上那一句");
        assertEquals(1, result.getAllErrors().size(), "其余入参都合法，总违约数必须恰为 1：" + result.getAllErrors());
    }

    // —— ② 假例：带合法 storeId 时一条违约都不报（①的对照组，缺了它①无从证伪） ——

    @Test
    void legalStoreIdIsAcceptedWithoutAnyViolation() {
        BindingResult result = validate(dtoWithStoreId(1L));

        assertTrue(messagesOf(result, "storeId").isEmpty(), "合法 storeId 不该再被报：" + messagesOf(result, "storeId"));
        assertEquals(0, result.getAllErrors().size(), "这份入参除 storeId 外全合法，报出任何违约都说明假例失效：" + result.getAllErrors());
    }

    // —— ③ 零新增文案：收必填只是把同一句话多挂一个注解 ——

    @Test
    void requiredMessageIsTheSameLiteralAlreadyUsedByTheRangeCheck() throws Exception {
        String min = annotationMessage("storeId", Min.class);
        String notNull = annotationMessage("storeId", NotNull.class);

        assertEquals(min, notNull, "@NotNull 必须复用同字段 @Min 的既有串，本片不许为收必填新增用户可见文案");
    }

    // —— ④ 失败链出口：违约仍走统一信封的 400 支，文案原样透出 ——

    @Test
    void rejectedStoreIdStillComesOutAsTheUnifiedBadRequestEnvelope() throws Exception {
        BindingResult result = validate(dtoWithStoreId(null));

        R<Void> body = new GlobalExceptionHandler().handleValid(new BindException(result));

        assertEquals(R.CODE_BAD_REQUEST, body.getCode(), "校验失败出口仍是信封 400（HTTP 恒 200 的纪律不变）");
        assertEquals(annotationMessage("storeId", NotNull.class), body.getMessage(), "message 即 DTO 注解上的原句");
        assertNull(body.getData(), "400 支的 data 留空键，与其它校验失败同形");
    }
}
