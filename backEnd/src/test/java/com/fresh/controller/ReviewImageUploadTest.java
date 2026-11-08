package com.fresh.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.entity.Review;
import com.fresh.entity.ReviewImage;
import com.fresh.mapper.ReviewImageMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.service.impl.AdminServiceImpl;
import com.fresh.service.impl.ReviewServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P10-6b 评价带图（m34）契约单测：不起 Spring 容器、不落库，图文件落 {@code @TempDir}
 * （姿势照 MerchantApplyControllerUploadTest 的临时目录 + F124ReviewIdempotencyTest 的 mock mapper）。
 *
 * <p>覆盖契约 D6 点名的六判据：成功上传落盘落行出参 / 超 3 张拒 / 类型不符拒（伪造扩展名）/
 * 非本人拒 / review 不存在 404 / reject 删图行（含文件尽力删与失败仅日志）。
 * 不证明：真实 multipart 解析与 Spring max-file-size 前置拦截（真链路 curl multipart 验收覆盖）。
 */
class ReviewImageUploadTest {

    private static final long USER_ID = 9L;
    private static final long DISH_ID = 1L;
    private static final long REVIEW_ID = 5L;

    @TempDir
    Path tempDir;

    /**
     * MP 的 {@code LambdaUpdateWrapper.set(getter, value)} 即时解析列名，依赖实体 TableInfo/列缓存
     * ——approve 分支会走到 {@code AdminServiceImpl#auditReview} 的 lambda wrapper（照
     * PaymentSettlementTest 的手工灌法：TableInfoHelper 只建内存缓存、不碰任何连接）。
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

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final ReviewImageMapper reviewImageMapper = mock(ReviewImageMapper.class);

    private ReviewServiceImpl service() {
        ReviewServiceImpl service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "reviewImageMapper", reviewImageMapper);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        return service;
    }

    private static byte[] pngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
    }

    /** 归属本人在售评价的默认桩（dish 匹配 + 图数 0） */
    private void stubOwnedReview() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        review.setUserId(USER_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        when(reviewImageMapper.selectCount(any())).thenReturn(0L);
    }

    private static MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", pngBytes());
    }

    // ================================================================
    // ① 成功上传：落盘 reviews/{yyyyMM}/、落行 review_image、出参 path
    // ================================================================

    @Test
    void 成功上传_落盘落行_出参path在uploadsReviews下() throws Exception {
        stubOwnedReview();
        when(reviewImageMapper.insert(any(ReviewImage.class))).thenReturn(1);

        String path = service().addImage(USER_ID, DISH_ID, REVIEW_ID, pngFile());

        assertTrue(path.startsWith("/uploads/reviews/"), "契约 D7：path 必须 /uploads/reviews/ 前缀：" + path);
        assertTrue(path.endsWith(".png"));
        // 落盘：/uploads/reviews/{yyyyMM}/{uuid}.png → tempDir/reviews/{yyyyMM}/{uuid}.png
        String rel = path.substring("/uploads/".length());
        Path onDisk = tempDir.resolve(rel);
        assertTrue(Files.exists(onDisk), "文件必须已落盘：" + onDisk);
        assertTrue(Files.readAllBytes(onDisk).length > 0);
        // 落行：reviewId + 展示路径
        ArgumentCaptor<ReviewImage> captor = ArgumentCaptor.forClass(ReviewImage.class);
        verify(reviewImageMapper, times(1)).insert(captor.capture());
        assertEquals(REVIEW_ID, captor.getValue().getReviewId());
        assertEquals(path, captor.getValue().getFilePath());
        // 中间必须有一层 yyyyMM 月目录（契约 D1 存储）
        String[] segments = rel.split("/");
        assertEquals(3, segments.length, "reviews/{yyyyMM}/{文件} 三段：" + rel);
        assertTrue(segments[1].matches("\\d{6}"), "月目录六位 yyyyMM：" + segments[1]);
    }

    @Test
    void controller端点出参按契约data带path键() throws Exception {
        stubOwnedReview();
        ReviewServiceImpl svc = service();
        when(reviewImageMapper.insert(any(ReviewImage.class))).thenReturn(1);
        com.fresh.controller.ReviewController controller = new com.fresh.controller.ReviewController();
        ReflectionTestUtils.setField(controller, "reviewService", svc);

        R<java.util.Map<String, String>> body =
                controller.addImage(DISH_ID, REVIEW_ID, pngFile(), USER_ID);

        assertEquals(200, body.getCode());
        assertEquals("上传成功", body.getMessage());
        assertTrue(body.getData().containsKey("path"));
        assertTrue(body.getData().get("path").startsWith("/uploads/reviews/"));
    }

    // ================================================================
    // ② 超 3 张拒（服务端计数）
    // ================================================================

    @Test
    void 已有3张再传判四百且不落盘() throws Exception {
        stubOwnedReview();
        when(reviewImageMapper.selectCount(any())).thenReturn(3L);

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, pngFile()));

        assertEquals(400, e.getCode());
        assertEquals("每条评价最多上传3张图片", e.getMessage());
        verify(reviewImageMapper, never()).insert(any(ReviewImage.class));
        try (var files = Files.walk(tempDir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(), "拒绝路径不得落下半截文件");
        }
    }

    // ================================================================
    // ③ 类型不符拒：伪造扩展名（后缀 png 魔数不是 / 后缀在白名单外）
    // ================================================================

    @Test
    void 伪造扩展名_后缀png内容非png判四百() throws Exception {
        stubOwnedReview();

        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-an-image".getBytes());

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, fake));

        assertEquals(400, e.getCode());
        assertEquals("文件类型不合法", e.getMessage());
        verify(reviewImageMapper, never()).insert(any(ReviewImage.class));
    }

    @Test
    void 白名单外后缀gif判四百() throws Exception {
        stubOwnedReview();

        MockMultipartFile gif = new MockMultipartFile(
                "file", "x.gif", "image/gif", pngBytes());

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, gif));

        assertEquals(400, e.getCode());
        assertEquals("文件类型不合法", e.getMessage());
    }

    // ================================================================
    // ④ 非本人拒（403）；归属不可考（user_id NULL）同样拒
    // ================================================================

    @Test
    void 非本人评价上传判403() {
        stubOwnedReview();

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(12345L, DISH_ID, REVIEW_ID, pngFile()));

        assertEquals(403, e.getCode());
        assertEquals(R.MSG_FORBIDDEN, e.getMessage());
        verify(reviewImageMapper, never()).insert(any(ReviewImage.class));
    }

    @Test
    void 历史评价user_id归属不可考一律拒() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        review.setUserId(null);   // init.sql 种子/历史数据：归属不可考
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, pngFile()));

        assertEquals(403, e.getCode());
    }

    // ================================================================
    // ⑤ review 不存在 404；dish 不匹配 400
    // ================================================================

    @Test
    void review不存在判404() {
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, pngFile()));

        assertEquals(404, e.getCode());
        assertEquals("评价不存在", e.getMessage());
    }

    @Test
    void 路径dishId与评价归属dish不符判四百() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(2L);
        review.setUserId(USER_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);

        BizException e = assertThrows(BizException.class,
                () -> service().addImage(USER_ID, DISH_ID, REVIEW_ID, pngFile()));

        assertEquals(400, e.getCode());
        assertEquals("评价与菜品不匹配", e.getMessage());
    }

    // ================================================================
    // ⑥ reject 删图行（契约 D4）：评价行与图行同链路删除 + 文件尽力删
    // ================================================================

    private AdminServiceImpl adminService() {
        AdminServiceImpl admin = new AdminServiceImpl();
        ReflectionTestUtils.setField(admin, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(admin, "reviewService", service());
        return admin;
    }

    @Test
    void reject驳回_评价行图行同链路删除_文件一并删() throws Exception {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        review.setUserId(USER_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        // 两张真图落盘 + 两行记录
        Path img1 = tempDir.resolve("reviews").resolve("202611").resolve("a.png");
        Path img2 = tempDir.resolve("reviews").resolve("202611").resolve("b.png");
        Files.createDirectories(img1.getParent());
        Files.write(img1, pngBytes());
        Files.write(img2, pngBytes());
        ReviewImage row1 = new ReviewImage();
        row1.setId(101L);
        row1.setReviewId(REVIEW_ID);
        row1.setFilePath("/uploads/reviews/202611/a.png");
        ReviewImage row2 = new ReviewImage();
        row2.setId(102L);
        row2.setReviewId(REVIEW_ID);
        row2.setFilePath("/uploads/reviews/202611/b.png");
        when(reviewImageMapper.selectList(any())).thenReturn(List.of(row1, row2));

        adminService().auditReview(REVIEW_ID, "reject", "admin:1");

        // 评价行删除（既有语义零改动）
        verify(reviewMapper, times(1)).deleteById(REVIEW_ID);
        // 图行删除（deleteBatchIds 收的是 id 列表，与实现一致）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids =
                (ArgumentCaptor<Collection<Long>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Collection.class);
        verify(reviewImageMapper, times(1)).deleteBatchIds(ids.capture());
        assertEquals(List.of(101L, 102L), ids.getValue().stream().toList(), "两张图行一并删");
        // 文件尽力删：两文件全部消失
        assertFalse(Files.exists(img1), "图文件必须一并删除：" + img1);
        assertFalse(Files.exists(img2), "图文件必须一并删除：" + img2);
    }

    @Test
    void reject驳回_图行路径越界形态_文件跳过仅日志_行照删() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        review.setUserId(USER_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        ReviewImage weird = new ReviewImage();
        weird.setId(201L);
        weird.setReviewId(REVIEW_ID);
        weird.setFilePath("/static/images/uploads/not-uploads-dir.png");   // 非 /uploads/ 前缀：跳过文件删
        when(reviewImageMapper.selectList(any())).thenReturn(List.of(weird));

        adminService().auditReview(REVIEW_ID, "reject", "admin:1");

        verify(reviewMapper, times(1)).deleteById(REVIEW_ID);
        verify(reviewImageMapper, times(1)).deleteBatchIds(anyCollection());
        // 越界/异常路径不抛出、不回滚：文件系统零落点即证明未误删 upload-dir 外目标
    }

    @Test
    void approve过审_不触碰图行与文件() throws Exception {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        review.setUserId(USER_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        Path img = tempDir.resolve("reviews").resolve("202611").resolve("keep.png");
        Files.createDirectories(img.getParent());
        Files.write(img, pngBytes());
        ReviewImage row = new ReviewImage();
        row.setId(301L);
        row.setReviewId(REVIEW_ID);
        row.setFilePath("/uploads/reviews/202611/keep.png");
        when(reviewImageMapper.selectList(any())).thenReturn(List.of(row));

        adminService().auditReview(REVIEW_ID, "approve", "admin:1");

        verify(reviewMapper, times(1)).update(any(), any());
        verify(reviewImageMapper, never()).deleteBatchIds(anyCollection());
        verify(reviewImageMapper, never()).selectList(any());
        assertTrue(Files.exists(img), "过审不动图：" + img);
    }
}
