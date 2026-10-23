package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.dto.AdminDictGroupUpdateDTO;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
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
 * F-108 §0.7 片 2 字典管理端六端点契约单测（不起 Spring 容器、不落库）。
 *
 * <p><b>要证明的事</b>：
 * ① 六个端点（groups / items / create / update-item / delete-item / update-group）静态密钥轨全部放行并正确委派；
 * ② 无凭证（或错 key）六端点统一 401「管理密钥缺失或错误」，且一次 Service 都不触达；
 * ③ 运营 JWT 轨把 {@code admin_user:<id>} 作为 actor 显式传给 Service（grant.actor() 的唯一取值通道）；
 * ④ 限流档位逐端点钉死：读 30/60s、写 10/60s、keyType=IP（§0.7）；
 * ⑤ DTO 校验：新增 word 必填（@NotBlank）、word/definition/name 列宽与 enabled 值域。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：路由映射、@Valid 由 MVC 触发、限流拦截器真实入桶——
 * 本类直接调控制器方法，所以这里只断注解属性与委派；真实 HTTP 行为由主会话真后端门禁覆盖。
 */
class AdminDictControllerTest {

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

    private static AdminDictItemCreateDTO createDto(String word, String definition, Integer sortOrder) {
        AdminDictItemCreateDTO dto = new AdminDictItemCreateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private static AdminDictItemUpdateDTO updateDto(String word, String definition, Integer sortOrder) {
        AdminDictItemUpdateDTO dto = new AdminDictItemUpdateDTO();
        dto.setWord(word);
        dto.setDefinition(definition);
        dto.setSortOrder(sortOrder);
        return dto;
    }

    private static AdminDictGroupUpdateDTO groupDto(String name, Integer enabled) {
        AdminDictGroupUpdateDTO dto = new AdminDictGroupUpdateDTO();
        dto.setName(name);
        dto.setEnabled(enabled);
        return dto;
    }

    // ---------------------------------------------------------------- ① 六端点正例（静态密钥轨）

    @Test
    void 分组列表端点放行并透传service出参() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", "dish_category");
        row.put("name", "商品分类");
        row.put("enabled", 1);
        row.put("itemCount", 11);
        row.put("sort", 10);
        List<Map<String, Object>> data = List.of(row);
        when(adminService.dictGroups()).thenReturn(data);

        R<List<Map<String, Object>>> result = controller.dictGroups(KEY, null);

        assertEquals(200, result.getCode());
        assertSame(data, result.getData());
        verify(adminService).dictGroups();
    }

    @Test
    void 词条列表端点放行并透传分组() {
        when(adminService.dictItems("dish_category")).thenReturn(List.of());

        R<List<Map<String, Object>>> result = controller.dictItems(KEY, null, "dish_category");

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isEmpty());
        verify(adminService).dictItems("dish_category");
    }

    @Test
    void 新增词条端点放行且静态密钥轨actor为null() {
        AdminDictItemCreateDTO dto = createDto("海鲜", "code:seafood", 1);

        R<Void> result = controller.createDictItem(KEY, null, "dish_category", dto);

        assertEquals(200, result.getCode());
        assertEquals("词条已新增", result.getMessage());
        verify(adminService).createDictItem("dish_category", dto, null);
    }

    @Test
    void 更新词条端点放行且静态密钥轨actor为null() {
        AdminDictItemUpdateDTO dto = updateDto("海鲜", null, 120);

        R<Void> result = controller.updateDictItem(KEY, null, 5L, dto);

        assertEquals(200, result.getCode());
        assertEquals("词条已更新", result.getMessage());
        verify(adminService).updateDictItem(5L, dto, null);
    }

    @Test
    void 删除词条端点放行且静态密钥轨actor为null() {
        R<Void> result = controller.deleteDictItem(KEY, null, 5L);

        assertEquals(200, result.getCode());
        assertEquals("词条已删除", result.getMessage());
        verify(adminService).deleteDictItem(5L, null);
    }

    @Test
    void 更新分组端点放行且静态密钥轨actor为null() {
        AdminDictGroupUpdateDTO dto = groupDto("食品分类", 0);

        R<Void> result = controller.updateDictGroup(KEY, null, "dish_category", dto);

        assertEquals(200, result.getCode());
        assertEquals("分组已更新", result.getMessage());
        verify(adminService).updateDictGroup("dish_category", dto, null);
    }

    // ---------------------------------------------------------------- ② 401 统一出口

    @Test
    void 无凭证时六端点统一401且不触达service() {
        List<R<?>> results = List.of(
                controller.dictGroups(null, null),
                controller.dictItems(null, null, "dish_category"),
                controller.createDictItem(null, null, "dish_category", createDto("海鲜", null, null)),
                controller.updateDictItem(null, null, 5L, updateDto("海鲜", null, null)),
                controller.deleteDictItem(null, null, 5L),
                controller.updateDictGroup(null, null, "dish_category", groupDto("食品分类", null)));

        for (R<?> result : results) {
            assertEquals(401, result.getCode());
            assertEquals("管理密钥缺失或错误", result.getMessage());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void 错误密钥同样401且不触达service() {
        R<List<Map<String, Object>>> result = controller.dictGroups("wrong-key", null);

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
        AdminDictItemCreateDTO dto = createDto("海鲜", "code:seafood", 1);

        R<Void> result = controller.createDictItem(null, AUTHZ, "dish_category", dto);

        assertEquals(200, result.getCode());
        verify(adminService).createDictItem("dish_category", dto, "admin_user:2");
    }

    // ---------------------------------------------------------------- ④ 限流档位

    @Test
    void 六端点限流档位符合契约() {
        Map<String, int[]> expected = Map.of(
                "dictGroups", new int[]{30, 60},
                "dictItems", new int[]{30, 60},
                "createDictItem", new int[]{10, 60},
                "updateDictItem", new int[]{10, 60},
                "deleteDictItem", new int[]{10, 60},
                "updateDictGroup", new int[]{10, 60});
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

        assertEquals(expected.keySet(), seen, "六个端点必须全部挂上限流（少一个都算漏挂）");
    }

    // ---------------------------------------------------------------- ⑤ DTO 校验

    @Test
    void 新增DTO的word必填且列宽受控() {
        assertFalse(validator.validate(createDto("   ", null, null)).isEmpty(), "空白 word 必须被 @NotBlank 拦下");
        assertFalse(validator.validate(createDto(null, null, null)).isEmpty(), "缺 word 必须被拦下");
        assertFalse(validator.validate(createDto("x".repeat(101), null, null)).isEmpty(), "word 超 100 必须被拦下");
        assertFalse(validator.validate(createDto("海鲜", "x".repeat(501), null)).isEmpty(), "definition 超 500 必须被拦下");
        assertTrue(validator.validate(createDto("海鲜", "code:seafood", 1)).isEmpty());
    }

    @Test
    void 分组DTO的enabled仅允许0或1且name不超50() {
        assertFalse(validator.validate(groupDto("商品分类", 2)).isEmpty(), "enabled=2 必须被拦下");
        assertFalse(validator.validate(groupDto("x".repeat(51), 1)).isEmpty(), "name 超 50 必须被拦下");
        assertTrue(validator.validate(groupDto("商品分类", 0)).isEmpty());
    }
}
