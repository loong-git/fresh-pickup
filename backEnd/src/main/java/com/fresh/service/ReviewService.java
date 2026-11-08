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
}
