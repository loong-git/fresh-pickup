package com.fresh.service;

import com.fresh.dto.StoreApplyCreateDTO;

import java.util.Map;

/**
 * 主理人入驻申请服务（F-18.12 第 1~2 条，D-20）。
 *
 * <p><b>为什么不挂 /api/partner/**</b>（D-20）：申请时申请人名下还没有店，而 {@code /api/partner/} 的闸门是
 * 「登录 + 归属集合非空」，挂上去等于 403 把自己入口锁死。故本服务只要求「登录」，
 * 归属在审核通过那一刻才由 Admin 写入 {@code store.owner_user_id}。
 */
public interface StoreApplyService {

    /**
     * 提交申请（{@code POST /api/store-applies}）。
     *
     * @param userId 申请人，唯一来源 {@code @RequestAttribute(ATTR_USER_ID)}（RF：不收 body 里的归属入参）
     * @return {@code {id, status:"pending"}} —— 除此之外不出任何列（contactPhone/proofPhotos 连查都不查，RD）
     * @throws com.fresh.common.BizException 400 校验不过（文案指到具体字段）/ D-21 已有在审申请
     */
    Map<String, Object> create(Long userId, StoreApplyCreateDTO dto);

    /**
     * 我的最新申请（{@code GET /api/store-applies/mine}）。
     *
     * @return 平铺七键 {@code id/proposedName/proposedAddress/status/auditRemark/createdAt/auditedAt}
     *         + {@code historyCount}（该用户申请总条数）；<b>从未申请过时七键显式为 null、historyCount=0</b>
     *         （不返 404——「还没申请过」是正常状态，前端按 {@code id==null} 走申请表单）。
     *         不出 contactPhone/proofPhotos（F-18.12 第 2 条的最小暴露口径）
     */
    Map<String, Object> mine(Long userId);
}
