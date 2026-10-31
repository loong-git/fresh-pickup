package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.BizException;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P6-3 §1 两只导出端点契约单测（不起 Spring 容器、不落库，姿势照 AdminDictControllerTest）：
 * ① 双轨闸门：无凭证/错 key/C 端 token（typ≠admin）一律 401「管理密钥缺失或错误」且一次 Service
 *    都不触达；静态密钥轨与运营 JWT 轨分别放行且 actor 通道正确（null / admin_user:&lt;id&gt;）。
 *    <b>401/403 的边界说明</b>：本面双轨闸门是「③ 两条都不中 → 唯一出口 401」（不给区分信号，
 *    防探测），403 属 AuthInterceptor 的用户面矩阵、不作用于 /api/admin/**——故本组只有 401 分支可测。
 * ② 导出响应定形：200 + text/csv;charset=UTF-8 + attachment 文件名（orders-/settlements-YYYYMMDD.csv）；
 *    闸门拒绝经 BizException 上抛（交 GlobalExceptionHandler 转同款 JSON 信封，HTTP 恒 200 惯例不变）。
 * ③ 限流注解：GET 读档 30/60s、keyType=IP（照 v2 列表族）。
 * ④ settlements 导出三参（merchantId/dateStart/dateEnd）透传。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：真实 HTTP 行为（限流入桶、produces 协商、下载体验）
 * 由主会话真后端门禁覆盖；CSV 内容与审计语义归 AdminCsvExportTest。
 */
class AdminExportControllerTest {

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

    // ---------------------------------------------------------------- fixtures

    /** CSV 字节样例（内容语义归 AdminCsvExportTest，这里只验响应定形） */
    private static byte[] csvBody() {
        return "\uFEFF订单号,下单时间,状态\r\nO1,2026-10-31 10:00:00,待自提\r\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    private void stubOperatorJwt() {
        Claims claims = mock(Claims.class);
        when(claims.get("typ")).thenReturn("admin");
        when(claims.getSubject()).thenReturn("2");
        when(jwtUtil.parseClaims("admin-token")).thenReturn(claims);
        AdminUser account = new AdminUser();
        account.setId(2L);
        account.setStatus("active");
        when(adminUserMapper.selectById(2L)).thenReturn(account);
    }

    // ---------------------------------------------------------------- ① 双轨闸门

    @Test
    void 两导出端点无凭证统一401_文案逐字_不触达service() {
        for (Runnable call : new Runnable[]{
                () -> controller.exportOrdersCsv(null, null),
                () -> controller.exportSettlementsCsv(null, null, null, null, null)}) {
            BizException e = assertThrows(BizException.class, call::run);
            assertEquals(401, e.getCode());
            assertEquals("管理密钥缺失或错误", e.getMessage());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void 两导出端点错误密钥同样401_不触达service() {
        for (Runnable call : new Runnable[]{
                () -> controller.exportOrdersCsv("wrong-key", null),
                () -> controller.exportSettlementsCsv("wrong-key", null, null, null, null)}) {
            BizException e = assertThrows(BizException.class, call::run);
            assertEquals(401, e.getCode());
            assertEquals("管理密钥缺失或错误", e.getMessage());
        }
        verifyNoInteractions(adminService);
    }

    @Test
    void C端用户token打导出端点_与无凭证同判401() {
        // typ≠admin（G-01 typ 隔离）：admin_user.id 与 user.id 从 1 起并存，C 端 token 必须与无凭证明等
        Claims claims = mock(Claims.class);
        when(claims.get("typ")).thenReturn("user");
        when(jwtUtil.parseClaims("admin-token")).thenReturn(claims);

        BizException e = assertThrows(BizException.class, () -> controller.exportOrdersCsv(null, AUTHZ));
        assertEquals(401, e.getCode());
        assertEquals("管理密钥缺失或错误", e.getMessage());
        verifyNoInteractions(adminService);
    }

    @Test
    void 被停用运营账号的token同样401_三重判定查库扣() {
        stubOperatorJwt();
        AdminUser account = new AdminUser();
        account.setId(2L);
        account.setStatus("suspended");
        when(adminUserMapper.selectById(2L)).thenReturn(account);

        BizException e = assertThrows(BizException.class, () -> controller.exportOrdersCsv(null, AUTHZ));
        assertEquals(401, e.getCode());
        verifyNoInteractions(adminService);
    }

    // ---------------------------------------------------------------- ② 放行与响应定形

    @Test
    void 静态密钥轨放行_响应200与csv头_BOM透传_actor为null() {
        byte[] body = csvBody();
        when(adminService.exportOrdersCsv(null)).thenReturn(body);

        ResponseEntity<byte[]> re = controller.exportOrdersCsv(KEY, null);

        assertEquals(200, re.getStatusCode().value());
        assertEquals("text/csv;charset=UTF-8", re.getHeaders().getContentType().toString());
        assertTrue(re.getHeaders().getFirst("Content-Disposition")
                        .matches("attachment; filename=orders-\\d{8}\\.csv"),
                "文件名必须是 orders-YYYYMMDD.csv：" + re.getHeaders().getFirst("Content-Disposition"));
        assertEquals(body, re.getBody());
        assertEquals((byte) 0xEF, re.getBody()[0], "Service 产出的 BOM 首字节原样透传");
        verify(adminService).exportOrdersCsv(null);
    }

    @Test
    void 运营JWT轨放行_actor透传admin_user加id_账单端点三参透传() {
        stubOperatorJwt();
        byte[] body = csvBody();
        when(adminService.exportSettlementsCsv(1L, "2026-10-01", "2026-10-31", "admin_user:2"))
                .thenReturn(body);

        ResponseEntity<byte[]> re =
                controller.exportSettlementsCsv(null, AUTHZ, 1L, "2026-10-01", "2026-10-31");

        assertEquals(200, re.getStatusCode().value());
        assertEquals("text/csv;charset=UTF-8", re.getHeaders().getContentType().toString());
        assertTrue(re.getHeaders().getFirst("Content-Disposition")
                        .matches("attachment; filename=settlements-\\d{8}\\.csv"),
                "文件名必须是 settlements-YYYYMMDD.csv：" + re.getHeaders().getFirst("Content-Disposition"));
        verify(adminService).exportSettlementsCsv(1L, "2026-10-01", "2026-10-31", "admin_user:2");
    }

    @Test
    void 账单端点筛选可空_全空参数原样透传null() {
        stubOperatorJwt();
        when(adminService.exportSettlementsCsv(null, null, null, "admin_user:2"))
                .thenReturn(csvBody());

        ResponseEntity<byte[]> re = controller.exportSettlementsCsv(null, AUTHZ, null, null, null);

        assertEquals(200, re.getStatusCode().value());
        verify(adminService).exportSettlementsCsv(null, null, null, "admin_user:2");
    }

    // ---------------------------------------------------------------- ③ 限流档位

    @Test
    void 两导出端点限流档位为GET读档30每60秒且按IP() {
        for (String method : new String[]{"exportOrdersCsv", "exportSettlementsCsv"}) {
            RateLimit rateLimit = declaredRateLimit(method);
            assertNotNull(rateLimit, method + " 必须挂 @RateLimit");
            assertEquals(30, rateLimit.limit(), method + " 的 limit（GET 读档）");
            assertEquals(60, rateLimit.windowSeconds(), method + " 的 windowSeconds");
            assertEquals(RateLimit.KeyType.IP, rateLimit.keyType(), method + " 必须按 IP 维度");
        }
    }

    private RateLimit declaredRateLimit(String name) {
        for (Method method : AdminController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method.getAnnotation(RateLimit.class);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- ④ 拒绝信封真源

    @Test
    void 拒绝信封经BizException携带denied原code原message_不另立文案() {
        // 闸门拒绝时的 code/message 必须来自 checkKeyOrOperator 的那枚 R.fail（真源唯一），
        // 端点签名是 CSV 字节面没有 R 出参位，故经 BizException 带同值上抛交全局处理器转信封
        BizException e = assertThrows(BizException.class, () -> controller.exportSettlementsCsv(null, null, 1L, null, null));
        assertEquals(R.CODE_UNAUTHORIZED, e.getCode());
        assertEquals("管理密钥缺失或错误", e.getMessage());
        verifyNoInteractions(adminService);
    }
}
