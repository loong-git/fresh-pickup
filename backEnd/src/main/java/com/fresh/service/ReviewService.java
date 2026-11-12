package com.fresh.service;

import com.fresh.entity.Review;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

public interface ReviewService {
    List<Review> getReviewsByDishId(Long dishId);

    /**
     * 我的评价（M7）：查当前登录用户全部评价（含 pending 待审），按评价时间倒序；
     * 逐条填充 dishName（按 dish_id 批量查 dish 表，两查询收敛，杜绝循环查库）
     */
    List<Map<String, Object>> listByUser(Long userId);

    /**
     * 提交评价（M6）：userId 由登录态注入，须存在本人订单明细（任意订单状态）方可评价；
     * reviewer 由服务端按登录手机号脱敏生成，前端直传昵称废弃。
     * F-124 幂等：入参实体带 clientRequestId 时先按其快查，命中即返回首次评价行（不再落库、不再回写好评率）；
     * 未带键或未命中则正常插入并返回落库后的同一实体
     */
    Review addReview(Review review, Long userId);

    /**
     * 评价图上传（P10-6b D3/m34）：登录用户须为 review 归属本人 + review.dish_id 与路径 dishId 一致
     * + 该 review 现有图数 <3；校验链照 MerchantController#upload（扩展名白名单 → 大小 ≤5MB → 魔数），
     * 落盘 {fresh.upload-dir}/reviews/{yyyyMM}/{uuid}.{ext}（越界防护：最终路径 normalize 后必须仍在
     * upload-dir 内）+ 落行 review_image，返回 /uploads/reviews/... 出参路径（契约 D7）。
     */
    String addImage(Long userId, Long dishId, Long reviewId, MultipartFile file);

    /**
     * 按 reviewId 批量填充评价图出参（P10-6b D3）：一条 IN 查询防 N+1，无图恒为空数组。
     */
    void fillImages(List<Review> reviews);

    /**
     * 删除某评价的全部图行 + 文件尽力删（P10-6b D4）：图行删除随调用方事务回滚，
     * 文件删除失败仅日志不回滚、不抛出（绝不打断既有驳回链路）。
     */
    void deleteImagesByReviewId(Long reviewId);

    /**
     * 商家回复评价（P11-1a D2-D3/m35）：merchantId 由 AuthInterceptor /api/merchant/** 分支注入
     * （@RequestAttribute 读取，照家族口径）；归属校验 review.dish_id → dish.merchant_id = 自己，
     * review 不存在 / 非自家评价一律 404 同文案不泄露存在性（照家族口径）。
     * 内容软词命中 → 400 拒绝提交（契约 D3 登记偏离评价 pending 管线：回复即时生效、无中间态展示需求，
     * 拒绝让商家改词重提是最短路径；硬词同 400）；合法则对原文先转义再 UPSERT 三列
     * （reply_content/reply_time/replyer，一评一回：重复回复=覆盖更新保留最新一条）。
     */
    void replyReview(Long merchantId, Long reviewId, String content);

    /**
     * 商家端评价管理列表（P11-1a D5/D6）：分页出自己菜品的全部评价（audit_status 不过滤，
     * 待审评价也在列——商家据此理解评分波动；C 端列表只出可见态的语义不变）。
     * 归属谓词 SQL 层收口（C6 纪律）：JOIN dish WHERE dish.merchant_id = merchantId
     * （AuthInterceptor /api/merchant/** 分支注入），他人评价与不存在同形（空页）不泄露存在性。
     * 出参 {@code {list,page,size,total,hasMore}}（形状照商家端 order-items 家族），
     * list 行键照 {@link #listByUser} 全键 + dishName（JOIN 取）+ images（一条 IN 批量组装防 N+1，
     * 照 fillImages 纪律）。分页照运营端 v2 家族口径：page 缺省 1、size 缺省 20 值域 [1,100]，
     * 越界/脏值 400 不夹取（夹取会让「要 50 拿到 20」静默变形；巨大 offset 由空页短路兜住）。
     * 注：契约 D2 只定义了回复端点、漏定义商家列表端点，本方法端点与形状由工作流裁决补齐（openIssues 已登记）。
     */
    Map<String, Object> listByMerchant(Long merchantId, Integer page, Integer size);
}
