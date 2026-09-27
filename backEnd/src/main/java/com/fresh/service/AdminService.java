package com.fresh.service;

import com.fresh.dto.AdminDishUpdateDTO;

/**
 * 管理端薄版服务（T-M3-09）：X-Admin-Key 鉴权在 Controller 层完成，此处只做业务。
 */
public interface AdminService {

    /** 订单状态更新：仅允许 pending_pickup → completed（核销）/ cancelled（取消，回补库存） */
    void updateOrderStatus(String orderId, String status);

    /** 商品部分更新：price / stock / onSale 仅更新传入字段 */
    void updateDish(Long dishId, AdminDishUpdateDTO dto);

    /**
     * 评价审核（T-M4-05 收口）：命中软词入库 pending 默认不展示，此处人工放行/驳回。
     * approve → audit_status 置 NULL 恢复展示；reject → 删除评价记录。
     */
    void auditReview(Long reviewId, String action);
}
