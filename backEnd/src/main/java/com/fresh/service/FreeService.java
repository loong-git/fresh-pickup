package com.fresh.service;

import com.fresh.dto.FreeClaimDTO;
import com.fresh.entity.Order;

import java.util.List;
import java.util.Map;

/**
 * 免费领商品服务（M7）：每日 0 元限量领一份指定商品，复用订单链路生成 0 元单并自动支付。
 * 防刷三件套：free_claim UNIQUE(user_id, claim_date) 一人一天（数据库级）、
 * free_activity 原子限量计数（无竞态）、clientRequestId 幂等（双击/重试返回同一订单）。
 */
public interface FreeService {

    /**
     * 今日免费商品（匿名可读，M8 满额赠契约字段）：activityStatus + remaining（限量与库存取小）+
     * dish（默认免费商品=候选池第一个）+ threshold 满额门槛 + pool 候选池全量商品 +
     * todayPaid/remainingToThreshold/claimed/claimedOrder（登录态个人数据，匿名固定 0/0/false/null）。
     */
    Map<String, Object> current(Long userId);

    /**
     * 0 元领取（需登录，M8 起从候选池自选商品）：单事务内完成 候选池/门槛校验 → 原子限量计数 →
     * 写领取记录 → 扣库存建单 → 自动支付。返回已支付（pay_status=1）的 0 元订单；
     * 满额门槛按 items（用户当前购物车内容）以 dish 表价格服务端重算合计判定（绝不信任前端金额），
     * 未满 threshold 抛差额文案；候选池外（无效的免费商品）/门槛不足/资格/限量/库存任一不满足抛 BizException 并整体回滚。
     */
    Order claim(Long userId, Long dishId, List<FreeClaimDTO.ClaimItemDTO> items);

    /** 我的领取记录（需登录）：分页，关联订单状态随行返回（手机号脱敏口径同 getOrders） */
    Map<String, Object> myClaims(Long userId, Integer pageNum, Integer pageSize);
}
