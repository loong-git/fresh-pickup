package com.fresh.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 主理人门店维护入参（F-18.12 第 5 条）：{@code PUT /api/partner/store}。
 *
 * <p><b>白名单只有 D-7 的七项</b>：openTime / closeTime / dailyCapacity / accepting /
 * contactPhone / service / photos。{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是这条契约的
 * 落地手段——{@code name/address/lng/lat/status} 这一类不在声明里的键，反序列化阶段就被丢掉，
 * 既<b>不报错也不生效</b>（门禁 G5 断言的就是「传进来不生效」）；要改名称/地址得走入驻申请或 Admin（D-7）。
 * 之所以「忽略」而不是「显式 400」：本端点的语义就是「七项之外的键一律不属于主理人」，
 * 小程序侧多传一个 storeName 快照就会整单失败，属于契约点名的过度收紧。
 *
 * <p><b>同样不收的东西</b>：本 DTO 里没有 {@code id}/{@code storeId} 之外的任何归属字段，
 * 而 storeId 只当 D-22 的<b>选择器</b>用（校验它 ∈ 归属集合），绝不参与 SET 列；
 * 归属集合本身只来自 {@code @RequestAttribute(ATTR_PARTNER_STORE_IDS)}（RF）。
 *
 * <p>null 一律表示「本次不改这列」（部分更新语义，照 MerchantDishServiceImpl#update 的写法）；
 * 空串/空数组表示「把该列清空为 NULL」——主理人填错电话后得有办法删掉。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartnerStoreUpdateDTO {

    /** D-22 选择器（与 {@code ?storeId=} 等价，query 参数优先）：仅用于在名下多店中选一店，不是可改列 */
    private Long storeId;

    /** 营业开始时间 HH:MM（VARCHAR(5)）；形状不符 400，仅展示、不参与 23:00 截单裁决（RE）。{@code ^$} 分支=清空该列 */
    @Pattern(regexp = "^$|^([01]?\\d|2[0-3]):[0-5]\\d$", message = "营业开始时间格式应为 HH:MM")
    private String openTime;

    /** 营业结束时间 HH:MM（VARCHAR(5)）；同上，空串=清空 */
    @Pattern(regexp = "^$|^([01]?\\d|2[0-3]):[0-5]\\d$", message = "营业结束时间格式应为 HH:MM")
    private String closeTime;

    /** 日容量（D-8：NULL/不传=不改；一期只展示，不自动降级 accepting） */
    @Min(value = 1, message = "日接单容量需在 1~99999 之间")
    @Max(value = 99999, message = "日接单容量需在 1~99999 之间")
    private Integer dailyCapacity;

    /** 满容/休市开关（0/1，与 status 的 open/closed 正交）；取值域在 Service 判（@Range 对 null 也放行，这里同样要放行 null） */
    private Integer accepting;

    /** 门店联系电话（VARCHAR(20)）：填了必须是 11 位大陆手机号（与申请侧同一条规则，空串=清空） */
    @Size(max = 20, message = "联系电话过长")
    private String contactPhone;

    /** 服务说明（VARCHAR(50)） */
    @Size(max = 50, message = "服务说明不能超过 50 字")
    private String service;

    /** 门店照片（D-18：上限 6 张、每张 /static/images/uploads/ 前缀白名单，后缀 ≤140 → 整体必落 VARCHAR(1000)；空数组=清空） */
    @Size(max = 6, message = "门店照片最多 6 张")
    private List<@NotNull(message = "门店照片不能为空")
            @Pattern(regexp = "^/static/images/uploads/.{1,140}$",
                     message = "门店照片必须是平台上传件路径（/static/images/uploads/ 开头，且不超过 162 字符）") String> photos;
}
