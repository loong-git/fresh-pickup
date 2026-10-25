package com.fresh.service;

import java.util.List;
import java.util.Map;

/**
 * 商家端结算单只读服务（F-120 §1）：与 MerchantDishService 同一套鉴权前提——登录与 role=merchant
 * 判定已在 AuthInterceptor 收口（/api/merchant/** 前缀分支 + 查库定角色 + ATTR_MERCHANT_ID 注入），
 * 本接口不重复角色校验，但每个以 merchantId 为参的方法自守卫（纵深防御，同 MerchantServiceImpl 口径）。
 * <p>R3' 归属铁律：组内不存在任何不带 merchant_id 条件或归属校验的读路径——列表恒以 merchant_id
 * 等值为 wrapper 第一条件；明细先 selectById 再比对 merchant_id，他商家账单与不存在的账单
 * 同判 404「账单不存在」（不泄露他人账单存在性，照订单归属校验惯例）。
 * <p>只读红线（F-120 §3）：本服务无任何写方法——settlement 只增不改（F-118 契约 §3），
 * 商家对结算单没有生成/冲销/改额的任何入口，跑批与运营端是唯一写方。
 */
public interface MerchantSettlementService {

    /**
     * 本人账单列表（F-120 §1）：merchant_id 等值过滤、id 升序（运营端 DESC 是管理视角，商家端照契约升序），
     * 出参=商家自见子集 {@code id,settleNo,periodStart,periodEnd,gmvAmount,refundAmount,netAmount(==payable),
     * orderCount,commission,status,createdAt} + {@code reconcileOk,paidChannelAbsent}——
     * 运营管理字段（merchantId/merchantName/gross/refund/net/retain/itemCount）不下发。
     * reconcileOk 实时算照运营端口径（三条件，见实现类）；paidChannelAbsent 恒 true（记账态明示）。
     */
    List<Map<String, Object>> list(Long merchantId);

    /**
     * 本人某账单的挂锚明细（F-120 §1）：行键照运营端七项 + orphan 归一 Boolean，语义照运营端口径。
     * 归属校验：账单不存在或 merchant_id ≠ 本人 → 404「账单不存在」（同一文案，两种成因不可区分）。
     */
    List<Map<String, Object>> items(Long merchantId, Long id);
}
