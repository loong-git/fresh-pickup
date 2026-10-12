package com.fresh.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入驻申请入参（F-18.12 第 1 条）：{@code POST /api/store-applies} body
 * {@code {proposedName, proposedAddress, lng, lat, service?, contactName?, contactPhone?, proofPhotos?}}。
 *
 * <p><b>没有 userId 字段，也不接受</b>（RF）：申请人身份唯一来源是 token（Controller 的
 * {@code @RequestAttribute(ATTR_USER_ID)}），body 里塞 userId 只会变成一个「替别人申请」的口子。
 *
 * <p>校验全在服务端（前端校验只是体验层）：非法一律 400 且文案指到具体字段——
 * 长度上限逐列对齐 m18 的 store_apply 列宽（proposed_name VARCHAR(100)、proposed_address VARCHAR(255)、
 * service/contact_name VARCHAR(50)、contact_phone VARCHAR(20)），装不下是 1406 而非 400，所以必须在这儿拦。
 *
 * <p><b>坐标口径</b>：GCJ-02 中国粗框 {@code lng∈[73,135]、lat∈[3,54]}，<b>不做坐标转换</b>
 * （转换只发生在用户定位那一侧，HANDOFF §8 的不对称口径；本期 store_apply/store 两侧存的都是真源值）。
 *
 * <p><b>D-18 前缀白名单</b>：proofPhotos 每张必须以 {@code /static/images/uploads/} 开头
 * （平台上传件唯一合法入库形态，拒外链），后缀长度 1~140 字符。写在元素约束上而不是 Service 里，有两个好处：
 * ① 违规元素由 GlobalExceptionHandler 直接给出「凭证照片必须是平台上传件」的人话文案（门禁 G2 要的就是指到字段）；
 * ② 元素上限 140 × 6 张 + 前缀 22 + JSON 引号逗号 ≈ 989 字符，仍在 proof_photos 的 VARCHAR(1000) 内
 *    ——列宽溢出（严格模式 1406 → 500）这条路被「≤6 张 + 每张 ≤140」两条一起堵死。
 * 同一条正则 PartnerStoreUpdateDTO.photos 也挂了一次——两处都是各自 DTO 的入参契约，不是业务逻辑的复制。
 */
@Data
public class StoreApplyCreateDTO {

    @NotBlank(message = "请填写门店名称")
    @Size(max = 100, message = "门店名称不能超过 100 字")
    private String proposedName;

    @NotBlank(message = "请填写门店地址")
    @Size(max = 255, message = "门店地址不能超过 255 字")
    private String proposedAddress;

    @NotNull(message = "请填写门店经度")
    @DecimalMin(value = "73", message = "门店经度需在中国范围（73~135）")
    @DecimalMax(value = "135", message = "门店经度需在中国范围（73~135）")
    private BigDecimal lng;

    @NotNull(message = "请填写门店纬度")
    @DecimalMin(value = "3", message = "门店纬度需在中国范围（3~54）")
    @DecimalMax(value = "54", message = "门店纬度需在中国范围（3~54）")
    private BigDecimal lat;

    @Size(max = 50, message = "服务说明不能超过 50 字")
    private String service;

    @Size(max = 50, message = "联系人姓名不能超过 50 字")
    private String contactName;

    /** 选填：填了就必须是 11 位大陆手机号（形态校验在 Service，因为 null 与空串都表示「未填」，@Pattern 会把空串判死） */
    @Size(max = 20, message = "联系电话过长")
    private String contactPhone;

    @Size(max = 6, message = "凭证照片最多 6 张")
    private List<@NotNull(message = "凭证照片不能为空")
            // BE-11（F-18.20 J 行）：原 `.{1,140}` 的 `.` 能吃 `/` 与 `?` → `../../` 目录穿越和查询串都过关；
            // 改 (?!.*\.\.) 拒任何一段 `..`，[^?#] 拒查询串/锚点——意图钉回「只允许平台上传件本路径」
            @Pattern(regexp = "^/static/images/uploads/(?!.*\\.\\.)[^?#]{1,140}$",
                     message = "凭证照片必须是平台上传件路径（/static/images/uploads/ 开头，且不超过 162 字符）") String> proofPhotos;
}
