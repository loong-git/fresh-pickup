package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家资质/入驻信息 merchant_profile（F-12/G-01，m15-migration.sql:34-44）：与 merchant 1:1。
 * 主键即关系——本表无自增 id 列，主键就是 merchant_id，故 @TableId 必须显式指列且用 INPUT（值由 merchant.id 供给）。
 */
@Data
@TableName("merchant_profile")
public class MerchantProfile {

    /** 商家ID（逻辑外键 merchant.id，主键即 1:1 关系，非自增） */
    @TableId(value = "merchant_id", type = IdType.INPUT)
    private Long merchantId;

    /** 营业执照图 URL（依赖 POST /api/merchant/uploads） */
    private String businessLicense;

    /** 法人姓名 */
    private String legalPerson;

    /** 经营地址 */
    private String address;

    /** 结算账户（三期结算启用，列位预留；一期平台维护不对商家开放） */
    private String settlementAccount;

    /** 审核状态：pending / approved / rejected（复用评价审核三态范式；一期平台代开通=直接 approved） */
    private String auditStatus;

    /** 驳回理由 */
    private String auditRemark;

    /** 审核时间 */
    private LocalDateTime auditedAt;
}
