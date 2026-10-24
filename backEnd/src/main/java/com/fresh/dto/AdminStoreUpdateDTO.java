package com.fresh.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端自提点部分更新入参（F-114 §1.2，{@code PUT /api/admin/stores/{id}}）：
 * body {@code {name?, address?, service?, status?, openTime?, closeTime?, dailyCapacity?, accepting?}}——
 * null/缺键 = 本次不改；白名单八列全空 400。
 *
 * <p><b>红线字段不可改</b>（契约 §1.2）：owner_user_id（账号归属，NULL=平台直营）、
 * contact_phone（PII，主理人端维护）、photos（上传链路）、lng/lat（F-06 GCJ-02 地图真源）。
 * DTO 仍带这些<b>探测字段</b>：body 传了不落库，由 Controller 在响应 message 里注明已忽略
 * （「带了忽略并在响应注明」），让运营端 UI 不必前置隐藏、后端始终是白名单——
 * 姿势照 AdminCouponTemplateUpdateDTO 的 couponKey/issuedCount。
 *
 * <p>校验（契约 §1.2 逐条）全部在 Service 内按序判，DTO 不挂 Bean Validation 注解（同族先例）。
 */
@Data
public class AdminStoreUpdateDTO {

    // ---- 探测字段：传了也不落库（红线四组），仅用于响应注明已忽略 ----

    /** 探测字段：账号归属不可改（主理人转移不在本片语义内；NULL=平台直营） */
    private Long ownerUserId;

    /** 探测字段：PII，主理人端维护（PUT /api/partner/store），运营端出参不下发本列（D-12/RD） */
    private String contactPhone;

    /** 探测字段：门店照片走上传链路，一期无上传端点（D-18 留 G-05） */
    private List<String> photos;

    /** 探测字段：经度（F-06 GCJ-02 地图真源，与底图同源） */
    private BigDecimal lng;

    /** 探测字段：纬度（F-06 同 lng 口径） */
    private BigDecimal lat;

    // ---- 白名单八字段：null/缺键 = 本次不改 ----

    /** 门店名称：null=不改；传则 trim 后 1~100 */
    private String name;

    /** 门店地址：null=不改；传则 trim 后 1~255 */
    private String address;

    /** 服务说明：null=不改；传则 trim 后 ≤50（可空列） */
    private String service;

    /** 营业状态：null=不改；传则 open / closed（closed 后 C 端该店置灰不可选，既有语义零改动） */
    private String status;

    /** 营业开始时间 HH:MM：null=不改；传则须匹配 {@code ^([01]\d|2[0-3]):[0-5]\d$}。
     *  「传 null 清空」契约未开口子（Jackson null 与缺键不分）——要清空只能不传，存量 NULL 列不受影响 */
    private String openTime;

    /** 营业结束时间 HH:MM：null=不改；传则同 openTime 形状 */
    private String closeTime;

    /** 日接单容量：null=不改；传则 ≥0。<b>0 与 null 语义均为不限容</b>（对齐 F-111 拦截条件 &gt;0 才拦），
     *  运营改它真实生效到下单拒单（OrderServiceImpl 读列自然联动，本片零改动） */
    private Integer dailyCapacity;

    /** 满容/休市细粒度开关：null=不改；传则 0/1（与 status 的 open/closed 正交） */
    private Integer accepting;
}
