package com.fresh.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Review;
import com.fresh.entity.ReviewImage;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.ReviewImageMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.AdminService;
import com.fresh.service.AdminV2Service;
import com.fresh.service.impl.AdminServiceImpl;
import com.fresh.service.impl.AdminV2ServiceImpl;
import com.fresh.service.impl.ReviewServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-1b D7 评价批量审核契约单测：不起 Spring 容器、不落库
 * （姿势照 {@code ReviewImageUploadTest} 的 mock mapper + ReflectionTestUtils 注入 +
 * MP TableInfo 内存缓存灌法；真链路 reject 删行删图用真 AdminServiceImpl/ReviewServiceImpl 接 mock mapper）。
 *
 * <p>覆盖 ask 自验五判据 + 端点双轨：approve 批量逐条置态（含 action trim 与 actor 透传）/
 * reject 批量真链路删行删图删文件 / 空 ids 400 / 非法 action 400（零调用）/
 * 超长 ids 400 / 单条失败不断批（{id,reason} 形状）/ 端点双轨放行与 401 文案逐字。
 * 不证明：真实 HTTP 绑定与限流计数（外层 mvn 全量 + live 验收覆盖）。
 */
class AdminV2BatchAuditTest {

    private static final String ACTOR = "admin_user:7";

    @TempDir
    Path tempDir;

    /**
     * MP 的 {@code LambdaUpdateWrapper.set(getter, value)} 即时解析列名，依赖实体 TableInfo/列缓存
     * ——真链路 approve 分支会走到 {@code AdminServiceImpl#auditReview} 的 lambda wrapper
     * （照 {@code ReviewImageUploadTest#primeMybatisPlusColumnCache} 的手工灌法：只建内存缓存、不碰任何连接）。
     */
    @BeforeAll
    static void primeMybatisPlusColumnCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.fresh.mapper");
        TableInfoHelper.initTableInfo(assistant, Review.class);
    }

    private final AdminService adminService = mock(AdminService.class);
    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final ReviewImageMapper reviewImageMapper = mock(ReviewImageMapper.class);
    private final AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);

    /** 批量业务体（mock AdminService：批量层语义——逐条透传/计数/不断批/形状闸门） */
    private AdminV2ServiceImpl batchService() {
        AdminV2ServiceImpl service = new AdminV2ServiceImpl();
        ReflectionTestUtils.setField(service, "adminService", adminService);
        return service;
    }

    /** 真 AdminServiceImpl + 真 ReviewServiceImpl（reject 真链路：删行+删图行+文件尽力删） */
    private AdminServiceImpl realAdminService() {
        ReviewServiceImpl reviewService = new ReviewServiceImpl();
        ReflectionTestUtils.setField(reviewService, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(reviewService, "reviewImageMapper", reviewImageMapper);
        ReflectionTestUtils.setField(reviewService, "uploadDir", tempDir.toString());
        AdminServiceImpl admin = new AdminServiceImpl();
        ReflectionTestUtils.setField(admin, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(admin, "reviewService", reviewService);
        ReflectionTestUtils.setField(admin, "adminAuditLogMapper", adminAuditLogMapper);
        return admin;
    }

    /** 批量业务体接真 AdminServiceImpl（真链路用例：批量层透传 + 单条真删在同一测试内贯通） */
    private AdminV2ServiceImpl batchServiceWithRealAdmin() {
        AdminV2ServiceImpl service = new AdminV2ServiceImpl();
        ReflectionTestUtils.setField(service, "adminService", realAdminService());
        return service;
    }

    /** 待审评价桩：id/dishId 就位（approve/reject 的真链路都要 selectById 命中） */
    private Review reviewOf(long id) {
        Review review = new Review();
        review.setId(id);
        review.setDishId(1L);
        return review;
    }

    private static byte[] pngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
    }

    /** 在 tempDir 落一张真图 + 返回它的展示路径（/uploads/reviews/202611/{name}.png） */
    private Path seedImage(String name) throws Exception {
        Path img = tempDir.resolve("reviews").resolve("202611").resolve(name);
        Files.createDirectories(img.getParent());
        Files.write(img, pngBytes());
        return img;
    }

    private ReviewImage imageRow(long rowId, long reviewId, String path) {
        ReviewImage row = new ReviewImage();
        row.setId(rowId);
        row.setReviewId(reviewId);
        row.setFilePath(path);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runBatch(List<Long> ids, String action) {
        return batchService().batchAuditReviews(ids, action, ACTOR);
    }

    // ================================================================
    // ① approve 批量：逐条独立执行、action trim、actor 透传、succeeded 计数
    // ================================================================

    @Test
    void approve批量_逐条独立执行_action带空白被trim_actor透传() {
        Map<String, Object> data = runBatch(List.of(11L, 12L, 13L), " approve ");

        assertEquals(3, data.get("succeeded"), "三条全部成功");
        assertEquals(List.of(), data.get("failed"), "failed 恒为数组，全成功时是空数组不出 null");
        verify(adminService, times(1)).auditReview(11L, "approve", ACTOR);
        verify(adminService, times(1)).auditReview(12L, "approve", ACTOR);
        verify(adminService, times(1)).auditReview(13L, "approve", ACTOR);
        verify(adminService, times(3)).auditReview(any(), eq("approve"), eq(ACTOR));
    }

    // ================================================================
    // ② reject 批量：真链路删评价行 + 删图行 + 文件尽力删（复用单条语义，逐条独立事务由
    //    生产环境的跨 bean 代理保证；本测试在无容器下验证业务贯通与最终效果）
    // ================================================================

    @Test
    void reject批量_真链路_评价行图行文件全删_succeeded计数() throws Exception {
        // 两条评价各带一张真图（图行 mock 出、文件真落盘）
        Path img1 = seedImage("a.png");
        Path img2 = seedImage("b.png");
        when(reviewMapper.selectById(21L)).thenReturn(reviewOf(21L));
        when(reviewMapper.selectById(22L)).thenReturn(reviewOf(22L));
        when(reviewImageMapper.selectList(any())).thenReturn(
                List.of(imageRow(101L, 21L, "/uploads/reviews/202611/a.png")),
                List.of(imageRow(102L, 22L, "/uploads/reviews/202611/b.png")));

        Map<String, Object> data = batchServiceWithRealAdmin()
                .batchAuditReviews(List.of(21L, 22L), "reject", ACTOR);

        assertEquals(2, data.get("succeeded"), "两条全部驳回成功");
        assertEquals(List.of(), data.get("failed"));
        // 评价行删除（既有单条语义：deleteById）
        verify(reviewMapper, times(1)).deleteById(21L);
        verify(reviewMapper, times(1)).deleteById(22L);
        // 图行删除：两次 deleteBatchIds 各收一张图的 id
        ArgumentCaptor<Collection<Long>> idsCaptor =
                (ArgumentCaptor<Collection<Long>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Collection.class);
        verify(reviewImageMapper, times(2)).deleteBatchIds(idsCaptor.capture());
        List<Long> deletedImageIds = idsCaptor.getAllValues().stream()
                .flatMap(Collection::stream).sorted().toList();
        assertEquals(List.of(101L, 102L), deletedImageIds, "两张图行一并删");
        // 文件尽力删：两文件全部消失
        assertFalse(Files.exists(img1), "图文件 a 必须一并删除：" + img1);
        assertFalse(Files.exists(img2), "图文件 b 必须一并删除：" + img2);
    }

    // ================================================================
    // ③ 形状闸门：空 ids / 超长 ids / 非法 action 全在批量层前置 400
    // ================================================================

    @Test
    void 空ids判四百且零调用() {
        BizException nullIds = assertThrows(BizException.class,
                () -> runBatch(null, "approve"));
        assertEquals(400, nullIds.getCode());
        assertEquals("ids 不能为空（须为数字数组，1~100 条）", nullIds.getMessage());

        BizException emptyIds = assertThrows(BizException.class,
                () -> runBatch(List.of(), "approve"));
        assertEquals(400, emptyIds.getCode());

        BizException nonNumericShape = assertThrows(BizException.class,
                () -> runBatch(null, "reject"), "端点归一失败的 null 与空数组同一句文案");
        assertEquals(400, nonNumericShape.getCode());

        verify(adminService, never()).auditReview(any(), any(), any());
    }

    @Test
    void 超长ids判四百且零调用() {
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            tooMany.add(i);
        }
        BizException e = assertThrows(BizException.class, () -> runBatch(tooMany, "approve"));
        assertEquals(400, e.getCode());
        assertEquals("ids 一次最多 100 条", e.getMessage());
        verify(adminService, never()).auditReview(any(), any(), any());
    }

    @Test
    void 非法action判四百文案与单条端点逐字同句且零调用() {
        BizException e = assertThrows(BizException.class, () -> runBatch(List.of(1L, 2L), "delete"));
        assertEquals(400, e.getCode());
        assertEquals("action 仅允许 approve（过审展示）或 reject（驳回删除）", e.getMessage());
        verify(adminService, never()).auditReview(any(), any(), any());

        BizException blank = assertThrows(BizException.class, () -> runBatch(List.of(1L), "  "));
        assertEquals(400, blank.getCode(), "纯空白 trim 后等同缺省，一样 400");
    }

    // ================================================================
    // ④ 单条失败不断批：{id,reason} 形状、其余条继续执行
    // ================================================================

    @Test
    void 单条失败不断批_failed记id与reason_其余条照常执行() {
        // auditReview 返回 void：stub 走 doThrow().when()（when(void 调用) 不成表达式）
        doThrow(new BizException(404, "评价不存在"))
                .when(adminService).auditReview(eq(2L), any(), any());

        Map<String, Object> data = runBatch(List.of(1L, 2L, 3L), "approve");

        assertEquals(2, data.get("succeeded"), "1、3 两条成功，失败的不拖累他条");
        Object failedObj = data.get("failed");
        assertTrue(failedObj instanceof List<?> failed && failed.size() == 1, "恰一条失败记录");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) failedObj).get(0);
        assertEquals(2L, row.get("id"));
        assertEquals("评价不存在", row.get("reason"));
        // 失败条之后的那条也被执行了（不断批的直接证据）
        verify(adminService, times(1)).auditReview(1L, "approve", ACTOR);
        verify(adminService, times(1)).auditReview(2L, "approve", ACTOR);
        verify(adminService, times(1)).auditReview(3L, "approve", ACTOR);
    }

    @Test
    void 非业务意外也记failed不断批_reason兜底异常类简名不出null() {
        doThrow(new IllegalStateException("boom"))
                .when(adminService).auditReview(eq(9L), any(), any());

        Map<String, Object> data = runBatch(List.of(8L, 9L, 10L), "reject");

        assertEquals(2, data.get("succeeded"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) data.get("failed");
        assertEquals(1, failed.size());
        assertEquals(9L, failed.get(0).get("id"));
        assertEquals("boom", failed.get(0).get("reason"));
    }

    // ================================================================
    // ⑤ 端点层：双轨闸门（X-Admin-Key 放行 / 无凭证 401 文案逐字）+ ids 归一
    // ================================================================

    private AdminController endpointWithMockService() {
        AdminController controller = new AdminController();
        ReflectionTestUtils.setField(controller, "adminKey", "admin-dev-key");
        ReflectionTestUtils.setField(controller, "adminV2Service", adminV2Service);
        return controller;
    }

    private final AdminV2Service adminV2Service = mock(AdminV2Service.class);

    @Test
    void 端点_XAdminKey轨放行_ids元素归一Long_action原样透传() {
        // JSON 数字经 Jackson 绑进 Map<String,Object> 后是 Integer：端点归一必须收成 Long
        Map<String, Object> body = Map.of("ids", List.of(31, 32), "action", "reject");

        R<Map<String, Object>> response = endpointWithMockService()
                .batchAuditReviews("admin-dev-key", null, body);

        assertEquals(200, response.getCode(), "HTTP 恒 200、业务码 200");
        verify(adminV2Service, times(1)).batchAuditReviews(List.of(31L, 32L), "reject", null);
    }

    @Test
    void 端点_ids含非数字元素_归一为null交service层400() {
        Map<String, Object> body = Map.of("ids", List.of(1, "a"), "action", "approve");

        endpointWithMockService().batchAuditReviews("admin-dev-key", null, body);

        verify(adminV2Service, times(1)).batchAuditReviews(isNull(), eq("approve"), isNull());
    }

    @Test
    void 端点_无凭证_401文案逐字_业务体零调用() {
        R<Map<String, Object>> response = endpointWithMockService()
                .batchAuditReviews(null, null, Map.of("ids", List.of(1), "action", "approve"));

        assertEquals(401, response.getCode());
        assertEquals("管理密钥缺失或错误", response.getMessage(), "RE 红线文案逐字节不变");
        verify(adminV2Service, never()).batchAuditReviews(any(), any(), any());
    }

    @Test
    void 端点_错误的key_401_业务体零调用() {
        R<Map<String, Object>> response = endpointWithMockService()
                .batchAuditReviews("wrong-key", null, Map.of("ids", List.of(1), "action", "approve"));

        assertEquals(401, response.getCode());
        verify(adminV2Service, never()).batchAuditReviews(any(), any(), any());
    }
}
