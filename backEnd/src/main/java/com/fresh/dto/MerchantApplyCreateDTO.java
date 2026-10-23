package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家入驻申请入参（F-106 片 A；F-107 起加 {@code licenses}）：{@code POST /api/merchant-applies} body
 * {@code {merchantName, contactName?, businessLicense?, licenses?, legalPerson?, address?, settlementAccount?}}。
 *
 * <p><b>没有 userId，也没有联系电话</b>：申请人身份唯一来源是 token（Controller 的
 * {@code @RequestAttribute(ATTR_USER_ID)}）；商家联系电话在过审时取申请人 {@code user.phone}，
 * 不接受申请人替别人填号码。长度上限逐列对齐 m21 的 merchant_apply 列宽，装不下是 1406 而非 400
 * （{@code licenses} 是 TEXT，另按契约给 4000 字业务上限）。
 */
@Data
public class MerchantApplyCreateDTO {

    @NotBlank(message = "请填写商家名称")
    @Size(max = 100, message = "商家名称不能超过 100 字")
    private String merchantName;

    @Size(max = 50, message = "联系人姓名不能超过 50 字")
    private String contactName;

    @Size(max = 255, message = "营业执照图地址不能超过 255 字")
    private String businessLicense;

    @Size(max = 50, message = "法人姓名不能超过 50 字")
    private String legalPerson;

    @Size(max = 255, message = "经营地址不能超过 255 字")
    private String address;

    @Size(max = 64, message = "结算账户不能超过 64 字")
    private String settlementAccount;

    /**
     * 证照列表（F-107）：JSON 数组文本，元素形如 {@code {"type":"business_license","url":"/static/images/uploads/x.jpg"}}，
     * 1~6 条且至少一条营业执照。<b>原样接收、原样落库</b>（服务端只校验不重组）；
     * 留空/不传 = 兼容老客户端，单图 {@code businessLicense} 字段照旧生效。
     */
    @Size(max = 4000, message = "证照列表不能超过 4000 字")
    private String licenses;
}
