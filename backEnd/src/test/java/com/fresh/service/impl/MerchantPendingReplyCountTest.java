package com.fresh.service.impl;

import com.fresh.common.R;
import com.fresh.controller.MerchantController;
import com.fresh.mapper.ReviewMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-8 片③ 商家待回复评价计数单测（不起 Spring 容器、不落库；mock mapper 姿势照
 * MerchantReviewListTest 同款：ReflectionTestUtils 直灌依赖）。
 *
 * <p>覆盖契约 D6 判据：归属接缝（merchantId 原样传给 COUNT——归属谓词在 SQL 层收口，
 * 服务端只把「当前商家ID」交下去）、无待回复回 0、他人商家不串号；controller 端点出参 {count}；
 * 另加结构性断言钉死不漂移面——计数 SQL 的归属 JOIN/WHERE 与商家列表逐字同源
 * （同一 MERCHANT_REVIEW_WHERE 常量），「展示中」判据 audit_status IS NULL 与
 * REVIEW_FILTER_WHERE 的 visible 翻译分支同形，SQL 内不出现 'visible' 字面值。
 *
 * <p><b>本类测不成什么（免得留假收据）</b>：SQL 谓词的真库筛选行为（已 reply 排除/pending
 * 待审不计/他人商家评价不计是 mock 喂不出来的库侧行为）——由结构性断言钉 SQL 文本 +
 * 主会话浏览器验收（徽标计数与评价管理列表实数一致）覆盖，与 MerchantReviewListTest
 * 的边界声明同口径。
 */
class MerchantPendingReplyCountTest {

    private static final long MERCHANT_ID = 7L;
    private static final long OTHER_MERCHANT_ID = 9L;

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);

    private ReviewServiceImpl service() {
        ReviewServiceImpl service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        return service;
    }

    @BeforeEach
    void setUp() {
        // 归属隔离对照组的桩：两家商家各自计数互不影响（默认 0，正例测试自行覆盖）
        when(reviewMapper.countMerchantPendingReplyReviews(OTHER_MERCHANT_ID)).thenReturn(0L);
    }

    // ================================================================
    // ① 归属接缝：merchantId 原样传给 COUNT（归属谓词在 SQL 层收口，服务层零内存过滤）
    // ================================================================

    @Test
    void 计数_merchantId原样传给COUNT_正例计入() {
        when(reviewMapper.countMerchantPendingReplyReviews(MERCHANT_ID)).thenReturn(5L);

        long count = service().pendingReplyCount(MERCHANT_ID);

        verify(reviewMapper).countMerchantPendingReplyReviews(MERCHANT_ID);
        assertEquals(5L, count);
    }

    @Test
    void 计数_无待回复回0() {
        assertEquals(0L, service().pendingReplyCount(MERCHANT_ID));
    }

    // ================================================================
    // ② 归属隔离：他人商家不串号（对照：另一商家的计数路径互不影响）
    // ================================================================

    @Test
    void 计数_他人商家不串号_只按注入的商家ID查() {
        long count = service().pendingReplyCount(OTHER_MERCHANT_ID);

        verify(reviewMapper).countMerchantPendingReplyReviews(OTHER_MERCHANT_ID);
        verify(reviewMapper, never()).countMerchantPendingReplyReviews(MERCHANT_ID);
        assertEquals(0L, count);
    }

    // ================================================================
    // ③ controller 端点：R.ok 透出单键 {count}（照 MerchantReviewListTest 端点例）
    // ================================================================

    @Test
    void controller端点_R_ok透出单键count() {
        ReviewServiceImpl impl = service();
        when(reviewMapper.countMerchantPendingReplyReviews(MERCHANT_ID)).thenReturn(5L);
        MerchantController controller = new MerchantController();
        ReflectionTestUtils.setField(controller, "reviewService", impl);

        R<Map<String, Object>> body = controller.pendingReplyCount(MERCHANT_ID);

        assertEquals(200, body.getCode(), "HTTP 恒 200 业务码惯例");
        assertEquals(1, body.getData().size(), "恰单键 count");
        assertEquals(5L, ((Number) body.getData().get("count")).longValue());
    }

    @Test
    void controller端点_空态count为0() {
        MerchantController controller = new MerchantController();
        ReflectionTestUtils.setField(controller, "reviewService", service());

        R<Map<String, Object>> body = controller.pendingReplyCount(OTHER_MERCHANT_ID);

        assertEquals(200, body.getCode());
        assertEquals(0L, ((Number) body.getData().get("count")).longValue());
    }

    // ================================================================
    // ④ 结构性断言：计数 SQL 归属与待回复判据同源（文本级，照 PartnerCommissionTest 手法）
    // ================================================================

    @Test
    void 结构性断言_计数SQL归属JOIN与待回复判据同源() throws IOException, NoSuchMethodException {
        String sql = ReviewMapper.class
                .getMethod("countMerchantPendingReplyReviews", Long.class)
                .getAnnotation(Select.class)
                .value()[0];
        // 归属：JOIN dish 与 WHERE 与 selectMerchantReviewsPage 逐字同源（MERCHANT_REVIEW_WHERE
        // 常量在编译期已拼进本 SQL 文本）——徽标计数与列表永远同一归属边界
        assertTrue(sql.contains("JOIN dish d ON r.dish_id = d.id"), "归属 JOIN 与商家列表同源");
        assertTrue(sql.contains("WHERE d.merchant_id = #{merchantId}"), "归属谓词=列表同一常量");
        // 待回复判据：展示中（audit_status IS NULL，与 REVIEW_FILTER_WHERE 的 'visible'
        // 翻译分支同形）AND 未回复（reply_content IS NULL，Review#replyContent 的 NULL 语义）
        assertTrue(sql.contains("r.audit_status IS NULL"), "展示中判据在位");
        assertTrue(sql.contains("r.reply_content IS NULL"), "未回复判据在位");
        assertFalse(sql.contains("visible"), "库侧不存在 'visible' 字面值（伪值→NULL 的翻译口径）");

        // 源码级同源：REVIEW_FILTER_WHERE 的 visible 分支与计数 SQL 用的是同一句判据文本
        String mapperSource = mainJavaSource("com/fresh/mapper/ReviewMapper.java");
        assertTrue(mapperSource.contains("(#{auditStatus} = 'visible' AND r.audit_status IS NULL)"),
                "visible 伪值翻译分支（REVIEW_FILTER_WHERE）在位，判据真源不漂移");
        assertTrue(mapperSource.contains("+ MERCHANT_REVIEW_WHERE + "),
                "计数 SQL 归属谓词经 MERCHANT_REVIEW_WHERE 常量拼接（禁第二套归属字面量）");
    }

    // ---------------------------------------------------------------- 辅助

    private static Path locateMainJava() {
        String[] candidates = {"src/main/java", "backEnd/src/main/java", "../backEnd/src/main/java"};
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /** 读 main 源码（结构性文本断言基元，照 PartnerCommissionTest 同款手法） */
    private static String mainJavaSource(String relative) throws IOException {
        Path mainJava = locateMainJava();
        assumeTrue(mainJava != null, "未定位到 src/main/java（工作目录非 backEnd/ 或仓库根），本条跳过");
        Path file = mainJava.resolve(relative);
        assumeTrue(Files.isRegularFile(file), "未找到 " + relative + "，本条跳过");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
