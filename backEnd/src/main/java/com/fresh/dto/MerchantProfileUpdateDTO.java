package com.fresh.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家资料更新入参白名单（F-12/G-01 Step 4d，07 G-01 范围⑧）：仅四个可编辑字段，
 * 归属 merchant_id 一律由 @RequestAttribute 服务端解析（R2/R3），本 DTO 不收任何归属入参。
 */
@Data
public class MerchantProfileUpdateDTO {
    @Size(max = 50) private String contactName;      // 联系人
    @Size(max = 255) private String address;         // 经营地址
    @Size(max = 255) private String businessLicense; // 营业执照图 URL（依赖 uploads 端点）
    @Size(max = 50) private String legalPerson;      // 法人
    // 主体名 name / 结算账户 settlement_account 一期锁定平台维护，不在白名单（03 §8.2）
}
