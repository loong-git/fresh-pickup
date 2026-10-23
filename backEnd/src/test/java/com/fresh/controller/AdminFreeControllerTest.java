package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.dto.AdminFreeActivityUpdateDTO;
import com.fresh.dto.AdminFreePoolUpdateDTO;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F-109 §0.2 免费领活动管理四端点契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 四个端点（GET/PUT activity、GET/PUT pool）静态密钥轨全部放行并正确委派，写端点 message 逐字；
 * ② 无凭证四端点统一 401「管理密钥缺失或错误」，且一次 Service 都不触达；
 * ③ 运营 JWT 轨把 {@code admin_user:<id>} 作为 actor 显式传给 Service（grant.actor() 的唯一取值通道）；
 * ④ 限流档位逐端点钉死：读 30/60s、写 10/60s、keyType=IP。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：路由映射、@Valid 由 MVC 触发、限流拦截器真实入桶，
 * 以及 body 里塞 dailyClaimed 的真实 HTTP 反序列化——本类直接调控制器方法；
 * 未知字段忽略与落库 patch 不写计数器由 {@code AdminFreeServiceTest} 覆盖，
 * 真实 HTTP 行为由主会话真后端门禁覆盖。
 */
class AdminFreeControllerTest {

    private static final String KEY = "test-admin-key";
    private static final String AUTHZ = "Bearer admin-token";

    private final AdminService adminService = mock(AdminService.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AdminUserMapper adminUserMapper = mock(AdminUserMapper.class);

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController();
        ReflectionTestUtils.setField(controller, "adminKey", KEY);
        ReflectionTestUtils.setField(controller, "adminService", adminService);
        ReflectionTestUtils.setField(controller, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(controller, "adminUserMapper", adminUserMapper);
    }

    private static AdminFreeActivityUpdateDTO activityDto() {
        AdminFreeActivityUpdateDTO dto = new AdminFreeActivityUpdateDTO();
        dto.setDailyQuota(10);
        dto.setThreshold(new BigDecimal("20.00"));
        return dto;
    }

    private static AdminFreePoolUpdateDTO poolDto() {
        AdminFreePoolUpdateDTO dto = new AdminFreePoolUpdateDTO();
        dto.setDishIds(List.of(1L, 2L));
        return dto;
    }

    // ---------------------------------------------------------------- ① 四端点正例（静态密钥轨）

    @Test
    void 读活动端点放行并透传service出参() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 1L);
        data.put("status", "online");
        when(adminService.freeActivity()).thenReturn(data);

        R<Map<String, Object>> result = controller.freeActivity(KEY, null);

        assertEquals(200, result.getCode());
        assertSame(data, result.getData());
        verify(adminService).freeActivity();
    }

    @Test
    void 读候选池端点放行并透传service出参() {
        List<Map<String, Object>> data = List.of(Map.of("id", 11L, "dishId", 1L, "dishName", "精品五花肉", "onSale", 1));
        when(adminService.freePool()).thenReturn(data);

        R<List<Map<String, Object>>> result = controller.freePool(KEY, null);

        assertEquals(200, result.getCode());
        assertSame(data, result.getData());
        verify(adminService).freePool();
    }

    @Test
    void 更新活动端点放行且静态密钥轨actor为null() {
        AdminFreeActivityUpdateDTO dto = activityDto();

        R<Void> result = controller.updateFreeActivity(KEY, null, dto);

        assertEquals(200, result.getCode());
        assertEquals("免费领活动已更新", result.getMessage());
        verify(adminService).updateFreeActivity(dto, null);
    }

    @Test
    void 更新候选池端点放行且静态密钥轨actor为null() {
        AdminFreePoolUpdateDTO dto = poolDto();

        R<Void> result = controller.updateFreePool(KEY, null, dto);

        assertEquals(200, result.getCode());
        assertEquals("候选池已更新", result.getMessage());
        verify(adminService).updateFreePool(dto, null);
    }

    // ---------------------------------------------------------------- ② 401 统一出口

    @Test
    void 无凭证时四端点统一401且不触达service() {
        List<R<?>> results = List.of(
                controller.freeActivity(null, null),
                controller.updateFreeActivity(null, null, activityDto()),
                controller.freePool(null, null),
                controller.updateFreePool(null, null, poolDto()));

        for (R<?> result : results) {
            assertEquals(401, result.getCode());
            assertEquals("管理密钥缺失或错误", result.getMessage());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void 错误密钥同样401且不触达service() {
        R<Map<String, Object>> result = controller.freeActivity("wrong-key", null);

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
        AdminFreePoolUpdateDTO dto = poolDto();

        R<Void> result = controller.updateFreePool(null, AUTHZ, dto);

        assertEquals(200, result.getCode());
        verify(adminService).updateFreePool(dto, "admin_user:2");
    }

    // ---------------------------------------------------------------- ④ 限流档位

    @Test
    void 四端点限流档位符合契约() {
        Map<String, int[]> expected = Map.of(
                "freeActivity", new int[]{30, 60},
                "updateFreeActivity", new int[]{10, 60},
                "freePool", new int[]{30, 60},
                "updateFreePool", new int[]{10, 60});
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
}
