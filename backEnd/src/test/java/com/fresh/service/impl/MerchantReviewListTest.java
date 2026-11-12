package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.controller.MerchantController;
import com.fresh.entity.ReviewImage;
import com.fresh.mapper.ReviewImageMapper;
import com.fresh.mapper.ReviewMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-1a 商家端评价管理列表（D5/D6）契约单测：不起 Spring 容器、不落库，mock mapper 姿势照
 * MerchantReviewReplyTest 同款（ReflectionTestUtils 直灌依赖）。
 *
 * <p>覆盖工作流裁决点名的两判据：归属接缝（merchantId 原样传给 COUNT 与页 SQL——归属谓词在
 * SQL 层收口，服务端只负责把「当前商家ID」交给那两条语句）/ 分页 hasMore 正确；另补出参五键形状、
 * 空页短路（offset>=total 不执行页 SQL）、page/size 越界 400、images 一条 IN 组装（有图两元素/无图空数组）、
 * controller 端点出参。不证明：SQL 谓词本身（@Select 串的 JOIN/WHERE 由 live/集成验收覆盖，与
 * ReviewMapper 运营组同口径）。
 */
class MerchantReviewListTest {

    private static final long MERCHANT_ID = 7L;

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final ReviewImageMapper reviewImageMapper = mock(ReviewImageMapper.class);

    private ReviewServiceImpl service() {
        ReviewServiceImpl service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "reviewImageMapper", reviewImageMapper);
        return service;
    }

    /** 造一页 Map 行（手写 SQL 的返回形状：列别名驼峰键） */
    private Map<String, Object> row(long id, String dishName, String replyContent) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("dishId", 1L);
        row.put("dishName", dishName);
        row.put("rating", 5);
        row.put("content", "新鲜，好评");
        row.put("replyContent", replyContent);
        return row;
    }

    // ================================================================
    // ① 归属接缝（裁决判据一）：merchantId 原样传给 COUNT 与页 SQL——归属谓词在 SQL 层收口，
    //   服务层只把「当前商家ID」交下去，自己不做任何内存过滤（内存过滤=把越界面搬进 Java）
    // ================================================================

    @Test
    void 归属接缝_merchantId原样传给COUNT与页SQL_不做内存过滤() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(1L);
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 0L, 20))
                .thenReturn(List.of(row(5L, "有机菠菜", null)));

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, 1, 20);

        // 两条 SQL 都拿到的必须是注入的商家ID本身（SQL 层 WHERE d.merchant_id=#{merchantId} 的接缝）
        verify(reviewMapper).countMerchantReviews(MERCHANT_ID);
        verify(reviewMapper).selectMerchantReviewsPage(MERCHANT_ID, 0L, 20);
        assertEquals(1, ((List<?>) data.get("list")).size());
    }

    // ================================================================
    // ② 分页 hasMore（裁决判据二）：total=25/size=20 → 第 1 页 hasMore=true、第 2 页 false
    // ================================================================

    @Test
    void 分页hasMore_首页真末页假_offset按页计算() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(25L);
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 0L, 20))
                .thenReturn(List.of(row(25L, "菜A", null)));
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 20L, 20))
                .thenReturn(List.of(row(5L, "菜B", null)));

        Map<String, Object> first = service().listByMerchant(MERCHANT_ID, 1, 20);
        assertEquals(Boolean.TRUE, first.get("hasMore"), "25 条取前 20 条，还有第 2 页");
        assertEquals(25L, first.get("total"));
        assertEquals(1, first.get("page"));
        assertEquals(20, first.get("size"));

        // 第 2 页 offset = (2-1)*20 = 20（< 25 仍执行页 SQL），20+20 >= 25 → hasMore=false
        Map<String, Object> second = service().listByMerchant(MERCHANT_ID, 2, 20);
        verify(reviewMapper).selectMerchantReviewsPage(MERCHANT_ID, 20L, 20);
        assertEquals(Boolean.FALSE, second.get("hasMore"), "末页 5 条已取完，无更多");
    }

    // ================================================================
    // ③ 出参五键形状（{list,page,size,total,hasMore}，照 order-items 家族）
    // ================================================================

    @Test
    void 出参形状_五键齐全() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(1L);
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 0L, 20))
                .thenReturn(List.of(row(5L, "有机菠菜", "谢谢支持")));

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, 1, 20);

        assertEquals(5, data.size(), "恰五键：list/page/size/total/hasMore");
        assertTrue(data.containsKey("list"));
        assertTrue(data.containsKey("page"));
        assertTrue(data.containsKey("size"));
        assertTrue(data.containsKey("total"));
        assertTrue(data.containsKey("hasMore"));
    }

    // ================================================================
    // ④ 空页短路：offset>=total 不执行页 SQL（照运营端 v2 同款），空归属回空页不 500
    // ================================================================

    @Test
    void 空页短路_无评价不执行页SQL_回空列表hasMore假() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(0L);

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, 1, 20);

        verify(reviewMapper, never()).selectMerchantReviewsPage(any(), anyLong(), anyInt());
        assertEquals(0, ((List<?>) data.get("list")).size());
        assertEquals(0L, data.get("total"));
        assertEquals(Boolean.FALSE, data.get("hasMore"));
    }

    @Test
    void 页码越界_巨大offset短路_回空页hasMore假() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(5L);

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, 9, 20);

        verify(reviewMapper, never()).selectMerchantReviewsPage(any(), anyLong(), anyInt());
        assertEquals(0, ((List<?>) data.get("list")).size());
        assertEquals(Boolean.FALSE, data.get("hasMore"));
    }

    // ================================================================
    // ⑤ 分页闸门（照运营端 v2 家族：缺省 1/20、size 值域 [1,100]、越界 400 不夹取）
    // ================================================================

    @Test
    void 页码小于1_判400不查库() {
        BizException e = assertThrows(BizException.class,
                () -> service().listByMerchant(MERCHANT_ID, 0, 20));
        assertEquals(400, e.getCode());
        verify(reviewMapper, never()).countMerchantReviews(any());
    }

    @Test
    void 页码缺省_按1与20回显() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(0L);

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, null, null);

        // 缺省 1/20：出参回显生效值（前端翻页以后端回显为准，照 order-items 家族纪律）
        assertEquals(1, data.get("page"));
        assertEquals(20, data.get("size"));
        verify(reviewMapper).countMerchantReviews(MERCHANT_ID);
    }

    @Test
    void 每页数量0或超100_判400不查库() {
        BizException e1 = assertThrows(BizException.class,
                () -> service().listByMerchant(MERCHANT_ID, 1, 0));
        assertEquals(400, e1.getCode());
        BizException e2 = assertThrows(BizException.class,
                () -> service().listByMerchant(MERCHANT_ID, 1, 101));
        assertEquals(400, e2.getCode());
        verify(reviewMapper, never()).countMerchantReviews(any());
    }

    // ================================================================
    // ⑥ images 一条 IN 组装：有图两元素按上传序、无图空数组不缺键
    // ================================================================

    @Test
    void 评价图一条IN组装_有图两元素无图空数组() {
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(2L);
        Map<String, Object> withImg = row(5L, "菜A", null);
        Map<String, Object> noImg = row(6L, "菜B", "谢谢支持");
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 0L, 20))
                .thenReturn(Arrays.asList(withImg, noImg));
        ReviewImage img1 = new ReviewImage();
        img1.setId(11L);
        img1.setReviewId(5L);
        img1.setFilePath("/uploads/reviews/202611/a.jpg");
        ReviewImage img2 = new ReviewImage();
        img2.setId(12L);
        img2.setReviewId(5L);
        img2.setFilePath("/uploads/reviews/202611/b.jpg");
        when(reviewImageMapper.selectList(any())).thenReturn(Arrays.asList(img1, img2));

        Map<String, Object> data = service().listByMerchant(MERCHANT_ID, 1, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertEquals(Arrays.asList("/uploads/reviews/202611/a.jpg", "/uploads/reviews/202611/b.jpg"),
                list.get(0).get("images"), "同一评价的图按 review_image.id 升序=上传顺序");
        assertEquals(List.of(), list.get(1).get("images"), "无图恒为空数组不缺键（前端免判缺键）");
    }

    // ================================================================
    // ⑦ controller 端点出参（照 MerchantReviewReplyTest 的端点例）：R.ok 包形状原样透出
    // ================================================================

    @Test
    void controller端点_R_ok透出分页形状() {
        ReviewServiceImpl impl = service();
        when(reviewMapper.countMerchantReviews(MERCHANT_ID)).thenReturn(1L);
        when(reviewMapper.selectMerchantReviewsPage(MERCHANT_ID, 0L, 20))
                .thenReturn(List.of(row(5L, "有机菠菜", null)));
        MerchantController controller = new MerchantController();
        ReflectionTestUtils.setField(controller, "reviewService", impl);

        R<Map<String, Object>> body = controller.merchantReviews(MERCHANT_ID, 1, 20);

        assertEquals(200, body.getCode());
        assertEquals(Boolean.FALSE, body.getData().get("hasMore"));
        assertEquals(1L, body.getData().get("total"));
    }
}
