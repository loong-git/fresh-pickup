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
     * 本人账单列表（F-120 §1 键集与归属口径零改动，P5-2 §1 分页化）：merchant_id 等值过滤恒在
     * （下沉 SettlementMapper，归属恒由 Controller 的 @RequestAttribute 服务端定、不收归属入参），
     * id 升序（运营端 DESC 是管理视角，商家端照契约升序）；分页闸门照 F-125 运营端逐字——
     * pageNum/pageSize 缺省 1/20、pageNum≥1、1≤pageSize≤100，越界 400「请求参数有误，请检查后重试」
     * 不夹取，offset ≥ total 短路空 list、total 仍真值。
     * 出参封口 {@code {list,total,pageNum,pageSize}}（无 filters——商家侧无筛选维度，契约 §1-4 明定），
     * list 行仍为商家自见子集 {@code id,settleNo,periodStart,periodEnd,gmvAmount,refundAmount,netAmount(==payable),
     * orderCount,commission,status,createdAt} + {@code reconcileOk,paidChannelAbsent}——
     * 运营管理字段（merchantId/merchantName/gross/refund/net/retain/itemCount）不下发。
     * reconcileOk 实时算照运营端口径（三条件，见实现类，聚合只喂当页 settleIds）；paidChannelAbsent 恒 true（记账态明示）。
     */
    Map<String, Object> list(Long merchantId, Integer pageNum, Integer pageSize);

    /**
     * 本人某账单的挂锚明细（F-120 §1）：行键照运营端七项 + orphan 归一 Boolean，语义照运营端口径。
     * 归属校验：账单不存在或 merchant_id ≠ 本人 → 404「账单不存在」（同一文案，两种成因不可区分）。
     */
    List<Map<String, Object>> items(Long merchantId, Long id);
}
