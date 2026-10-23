package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.dto.AdminAliasCreateDTO;
import com.fresh.dto.AdminAliasUpdateDTO;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminService;
import com.fresh.service.DictService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-110 搜索别名四端点契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 四个端点（列表 / 新增 / 更新 / 删除）静态密钥轨全部放行并正确委派，成功 message 固定；
 * ② 无凭证（或错 key）四端点统一 401「管理密钥缺失或错误」，且一次 Service 都不触达；
 * ③ 运营 JWT 轨把 {@code admin_user:<id>} 作为 actor 显式传给 Service；
 * ④ 限流档位逐端点钉死：列表 30/60s、三个写端点 10/60s、keyType=IP（F-110 §0.2）；
 * ⑤ 新增 DTO 校验：alias 必填（@NotBlank）、dishId 必填（@NotNull）。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：路由映射、@Valid 由 MVC 触发、限流拦截器真实入桶——
 * 本类直接调控制器方法，所以这里只断注解属性与委派；真实 HTTP 行为由主会话真后端门禁覆盖。
 */
class AdminAliasControllerTest {

    private static final String KEY = "test-admin-key";
    private static final String AUTHZ = "Bearer admin-token";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final AdminService adminService = mock(AdminService.class);
    private final DictService dictService = mock(DictService.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AdminUserMapper adminUserMapper = mock(AdminUserMapper.class);

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController();
        ReflectionTestUtils.setField(controller, "adminKey", KEY);
        ReflectionTestUtils.setField(controller, "adminService", adminService);
        ReflectionTestUtils.setField(controller, "dictService", dictService);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(controller, "adminUserMapper", adminUserMapper);
    }

    // ---------------------------------------------------------------- fixtures

    private static AdminAliasCreateDTO createDto(String alias, Long dishId) {
        AdminAliasCreateDTO dto = new AdminAliasCreateDTO();
        dto.setAlias(alias);
        dto.setDishId(dishId);
        return dto;
    }

    private static AdminAliasUpdateDTO updateDto(String alias, Long dishId) {
        AdminAliasUpdateDTO dto = new AdminAliasUpdateDTO();
        dto.setAlias(alias);
        dto.setDishId(dishId);
        return dto;
    }

    // ---------------------------------------------------------------- ① 四端点正例（静态密钥轨）

    @Test
    void 别名列表端点放行并透传service出参() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("alias", "洋芋");
        row.put("dishId", 16L);
        row.put("dishName", "高山土豆");
        row.put("onSale", 1);
        List<Map<String, Object>> data = List.of(row);
        when(adminService.aliases()).thenReturn(data);

        R<List<Map<String, Object>>> result = controller.aliases(KEY, null);

        assertEquals(200, result.getCode());
        assertSame(data, result.getData());
        verify(adminService).aliases();
    }

    @Test
    void 新增别名端点放行且静态密钥轨actor为null() {
        AdminAliasCreateDTO dto = createDto("洋芋2", 16L);

        R<Void> result = controller.createAlias(KEY, null, dto);

        assertEquals(200, result.getCode());
        assertEquals("别名已新增", result.getMessage());
        verify(adminService).createAlias(dto, null);
    }

    @Test
    void 更新别名端点放行且静态密钥轨actor为null() {
        AdminAliasUpdateDTO dto = updateDto("洋芋头", null);

        R<Void> result = controller.updateAlias(KEY, null, 5L, dto);

        assertEquals(200, result.getCode());
        assertEquals("别名已更新", result.getMessage());
        verify(adminService).updateAlias(5L, dto, null);
    }

    @Test
    void 删除别名端点放行且静态密钥轨actor为null() {
        R<Void> result = controller.deleteAlias(KEY, null, 5L);

        assertEquals(200, result.getCode());
        assertEquals("别名已删除", result.getMessage());
        verify(adminService).deleteAlias(5L, null);
    }

    // ---------------------------------------------------------------- ② 401 统一出口

    @Test
    void 无凭证时四端点统一401且不触达service() {
        List<R<?>> results = List.of(
                controller.aliases(null, null),
                controller.createAlias(null, null, createDto("洋芋2", 16L)),
                controller.updateAlias(null, null, 5L, updateDto("洋芋头", null)),
                controller.deleteAlias(null, null, 5L));

        for (R<?> result : results) {
            assertEquals(401, result.getCode());
            assertEquals("管理密钥缺失或错误", result.getMessage());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void 错误密钥同样401且不触达service() {
        R<List<Map<String, Object>>> result = controller.aliases("wrong-key", null);

        assertEquals(401, result.getCode());
        verifyNoInteractions(adminService);
    }

    // ---------------------------------------------------------------- ③ 运营 JWT 轨 actor

    @Test
    void 运营JWT轨actor为admin_user加id() {
        Claims claims = mock(Claims.class);
        when(claims.get("typ")).thenReturn("admin");
        when(claims.getSubject()).thenReturn("2");
        when(jwtUtil.parseClaims("admin-token")).thenReturn(claims);
        AdminUser account = new AdminUser();
        account.setId(2L);
        account.setStatus("active");
        when(adminUserMapper.selectById(2L)).thenReturn(account);
        AdminAliasCreateDTO dto = createDto("洋芋2", 16L);

        R<Void> result = controller.createAlias(null, AUTHZ, dto);

        assertEquals(200, result.getCode());
        verify(adminService).createAlias(dto, "admin_user:2");
    }

    // ---------------------------------------------------------------- ④ 限流档位

    @Test
    void 四端点限流档位符合契约() {
        Map<String, int[]> expected = Map.of(
                "aliases", new int[]{30, 60},
                "createAlias", new int[]{10, 60},
                "updateAlias", new int[]{10, 60},
                "deleteAlias", new int[]{10, 60});
        Set<String> seen = new HashSet<>();

        for (Method method : AdminController.class.getDeclaredMethods()) {
            int[] spec = expected.get(method.getName());
            if (spec == null) {
                continue;
            }
            RateLimit rateLimit = method.getAnnotation(RateLimit.class);
            assertNotNull(rateLimit, method.getName() + " 必须挂 @RateLimit");
            assertEquals(spec[0], rateLimit.limit(), method.getName() + " 的 limit");
            assertEquals(spec[1], rateLimit.windowSeconds(), method.getName() + " 的 windowSeconds");
            assertEquals(RateLimit.KeyType.IP, rateLimit.keyType(), method.getName() + " 必须按 IP 维度");
            seen.add(method.getName());
        }

        assertEquals(expected.keySet(), seen, "四个端点必须全部挂上限流（少一个都算漏挂）");
    }

    // ---------------------------------------------------------------- ⑤ DTO 校验

    @Test
    void 新增DTO的alias与dishId必填() {
        assertFalse(validator.validate(createDto("   ", 16L)).isEmpty(), "空白 alias 必须被 @NotBlank 拦下");
        assertFalse(validator.validate(createDto(null, 16L)).isEmpty(), "缺 alias 必须被拦下");
        assertFalse(validator.validate(createDto("洋芋2", null)).isEmpty(), "缺 dishId 必须被 @NotNull 拦下");
        assertTrue(validator.validate(createDto("洋芋2", 16L)).isEmpty());
    }
}
