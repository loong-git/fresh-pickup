package com.fresh.service.impl;

import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.common.WordFilter;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.entity.Review;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.ReviewMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11-1a 商家回复评价（m35）契约单测：不起 Spring 容器、不落库，mock mapper 姿势照
 * F124ReviewIdempotencyTest / ReviewImageUploadTest 同款（ReflectionTestUtils 直灌依赖）。
 *
 * <p>覆盖任务书点名的五判据：回复成功（三列落值 + 一评一回走 UPDATE 不插行）/
 * 覆盖更新（二连后仍一行且内容为第二版）/ 非自家评价 404 不泄露 / 软词命中 400 拒绝 /
 * review 不存在 404；另补 dish 缺失 404、空/超长内容 400、XSS 转义、controller 端点出参。
 * 不证明：真实 HTTP 链路与拦截器商家ID注入（AuthInterceptor 既有分支零改动，live 验收覆盖）。
 */
class MerchantReviewReplyTest {

    private static final long MERCHANT_ID = 7L;
    private static final long DISH_ID = 1L;
    private static final long REVIEW_ID = 5L;

    private final ReviewMapper reviewMapper = mock(ReviewMapper.class);
    private final DishMapper dishMapper = mock(DishMapper.class);
    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final WordFilter wordFilter = mock(WordFilter.class);

    private ReviewServiceImpl service() {
        ReviewServiceImpl service = new ReviewServiceImpl();
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "dishMapper", dishMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "wordFilter", wordFilter);
        return service;
    }

    /** 归属自家菜品的默认桩：review 存在 + dish.merchant_id=自己 + 商家主体名在档 + 软硬词未命中 */
    private void stubOwnedReview() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        Dish dish = new Dish();
        dish.setId(DISH_ID);
        dish.setMerchantId(MERCHANT_ID);
        when(dishMapper.selectById(DISH_ID)).thenReturn(dish);
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT_ID);
        merchant.setName("测试商家");
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(merchant);
        // mock 默认 matchSoft/matchHard 返回 null（未命中），无需显式 stub
    }

    // ================================================================
    // ① 回复成功：三列落值（内容转义 / 时间 / 商家主体名）且走 UPDATE 不插行
    // ================================================================

    @Test
    void 回复成功_三列落值_走update不插行() {
        stubOwnedReview();

        service().replyReview(MERCHANT_ID, REVIEW_ID, "感谢您的评价，欢迎再次光临");

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).updateById(captor.capture());
        verify(reviewMapper, never()).insert(any(Review.class));
        Review upd = captor.getValue();
        assertEquals(REVIEW_ID, upd.getId(), "覆盖更新的定位行=被回复评价");
        assertEquals("感谢您的评价，欢迎再次光临", upd.getReplyContent(), "普通文本转义后原样");
        assertNotNull(upd.getReplyTime(), "回复时间必须落值");
        assertTrue(!upd.getReplyTime().isAfter(LocalDateTime.now().plusSeconds(1)), "回复时间不超前");
        assertEquals("测试商家", upd.getReplyer(), "回复人=merchant.name 服务端落库");
        // 评价本体绝不被拖进更新集：实体只带 id+三列，其余键为 null（NOT_NULL 策略下不入 UPDATE SET）
        assertNull(upd.getContent(), "评价本体 content 不得被回复端点改写");
        assertNull(upd.getAuditStatus(), "评价本体 audit_status 不得被回复端点改写");
    }

    @Test
    void 回复内容含HTML字符_入库前转义_照评价先例() {
        stubOwnedReview();

        service().replyReview(MERCHANT_ID, REVIEW_ID, "<script>alert(1)</script> & \"引号\"");

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(1)).updateById(captor.capture());
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;引号&quot;",
                captor.getValue().getReplyContent(), "HtmlUtils.htmlEscape 转义（照评价 content 同款防存储型 XSS）");
    }

    @Test
    void controller端点_出参R_ok_带回复成功文案() {
        stubOwnedReview();
        com.fresh.controller.MerchantController controller = new com.fresh.controller.MerchantController();
        ReflectionTestUtils.setField(controller, "reviewService", service());

        R<Void> body = controller.replyReview(MERCHANT_ID, REVIEW_ID,
                java.util.Map.of("content", "谢谢支持"));

        assertEquals(200, body.getCode());
        assertEquals("回复成功", body.getMessage());
        verify(reviewMapper, times(1)).updateById(any(Review.class));
    }

    // ================================================================
    // ② 覆盖更新（契约 D1 一评一回）：二连后仍一行（零 insert）且内容为第二版
    // ================================================================

    @Test
    void 重复回复_覆盖更新_仍一行且内容为第二版() {
        stubOwnedReview();

        service().replyReview(MERCHANT_ID, REVIEW_ID, "第一版回复");
        service().replyReview(MERCHANT_ID, REVIEW_ID, "第二版回复");

        // 一评一回=覆盖更新：只 UPDATE 不 INSERT，行数不增（INSERT 调用 0 次即库侧仍原一行）
        verify(reviewMapper, never()).insert(any(Review.class));
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewMapper, times(2)).updateById(captor.capture());
        List<Review> ups = captor.getAllValues();
        assertEquals("第一版回复", ups.get(0).getReplyContent());
        assertEquals("第二版回复", ups.get(1).getReplyContent(), "重复回复保留最新一条（第二版）");
        assertEquals(REVIEW_ID, ups.get(0).getId());
        assertEquals(REVIEW_ID, ups.get(1).getId(), "两次都落同一行");
    }

    // ================================================================
    // ③ 非自家评价 404 不泄露（契约 D2）：dish.merchant_id ≠ 自己 → 同「评价不存在」文案
    // ================================================================

    @Test
    void 非自家评价_判404同文案_不泄露存在性且不落更新() {
        stubOwnedReview();
        Dish others = new Dish();
        others.setId(DISH_ID);
        others.setMerchantId(999L);
        when(dishMapper.selectById(DISH_ID)).thenReturn(others);

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "越权回复"));

        assertEquals(404, e.getCode());
        assertEquals("评价不存在", e.getMessage(), "与 review 不存在同文案，不泄露他人评价存在性");
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    @Test
    void 评价挂在已删菜品_dish缺失_同样404不泄露() {
        Review review = new Review();
        review.setId(REVIEW_ID);
        review.setDishId(DISH_ID);
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(review);
        when(dishMapper.selectById(DISH_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "回复"));

        assertEquals(404, e.getCode());
        assertEquals("评价不存在", e.getMessage());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    // ================================================================
    // ④ 软词命中 400 拒绝提交（契约 D3，登记偏离评价 pending 管线）
    // ================================================================

    @Test
    void 软词命中_判400拒绝提交_不落更新不走pending() {
        stubOwnedReview();
        when(wordFilter.matchSoft("垃圾回复")).thenReturn("垃圾");

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "垃圾回复"));

        assertEquals(400, e.getCode(), "回复软词=400 拒绝（区别于评价的 pending 中间态，契约 D3）");
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    @Test
    void 硬词命中_同样400拒绝() {
        stubOwnedReview();
        when(wordFilter.matchHard("违禁词回复")).thenReturn("违禁词");

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "违禁词回复"));

        assertEquals(400, e.getCode());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    // ================================================================
    // ⑤ review 不存在 404（同 addImage 文案口径）
    // ================================================================

    @Test
    void review不存在_判404评价不存在() {
        when(reviewMapper.selectById(REVIEW_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "回复"));

        assertEquals(404, e.getCode());
        assertEquals("评价不存在", e.getMessage());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    // ================================================================
    // ⑥ 内容闸门：空 400 / 超长 400（与列 VARCHAR(500) 对齐）
    // ================================================================

    @Test
    void 空内容_判400() {
        stubOwnedReview();

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "   "));

        assertEquals(400, e.getCode());
        assertEquals("回复内容不能为空", e.getMessage());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    @Test
    void 超过500字_判400与列定义对齐() {
        stubOwnedReview();

        BizException e = assertThrows(BizException.class,
                () -> service().replyReview(MERCHANT_ID, REVIEW_ID, "长".repeat(501)));

        assertEquals(400, e.getCode());
        assertEquals("回复内容需在 1~500 字之间", e.getMessage());
        verify(reviewMapper, never()).updateById(any(Review.class));
    }

    @Test
    void 正好500字_放行() {
        stubOwnedReview();

        service().replyReview(MERCHANT_ID, REVIEW_ID, "评".repeat(500));

        verify(reviewMapper, times(1)).updateById(any(Review.class));
    }
}
