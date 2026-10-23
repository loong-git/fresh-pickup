package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家入驻申请 merchant_apply（F-106 片 A，m21-migration.sql 建表）。
 *
 * <p>三态 pending/approved/rejected（口径照 store_apply / merchant_profile.audit_status）。
 * 同用户最多 1 条 pending 由应用层 {@code countPendingByUser} 给友好 400，并发窗口由库侧
 * 生成列 {@code pending_guard} + 唯一键 {@code uk_user_pending(user_id, pending_guard)} 封死
 * （照 store_apply 的范式）。生成列不在实体声明，INSERT 语句不含它，由 DB 按 status 自动落值。
 *
 * <p><b>不存在 contact_phone 字段</b>：审核通过时 merchant.contact_phone 必须取申请人
 * {@code user.phone}（可信绑定），绝不取申请表单里的电话——表单可以由申请人替别人填。
 */
@Data
@TableName("merchant_apply")
public class MerchantApply {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人 user.id：唯一可信来源是 AuthInterceptor.ATTR_USER_ID（token 解析），绝不收 body 里的 userId */
    private Long userId;

    /** 拟用商家名称 VARCHAR(100) NOT NULL */
    private String merchantName;

    /** 联系人姓名 VARCHAR(50) */
    private String contactName;

    /** 营业执照图 URL VARCHAR(255)：平台上传件（/static/images/uploads/ 前缀） */
    private String businessLicense;

    /** 法人姓名 VARCHAR(50) */
    private String legalPerson;

    /** 经营地址 VARCHAR(255) */
    private String address;

    /** 结算账户 VARCHAR(64)：三期结算启用，列位预留 */
    private String settlementAccount;

    /** 审核状态 pending/approved/rejected */
    private String status;

    /** 审核意见 VARCHAR(255)（驳回原因供申请人在 mine 出参里看到） */
    private String auditRemark;

    /** 审核时间：NULL=未审 */
    private LocalDateTime auditedAt;

    /** 申请时间：DB DEFAULT CURRENT_TIMESTAMP 落值，实体不填 */
    private LocalDateTime createdAt;

    /**
     * 证照列表 JSON 文本（F-107/m22，TEXT NULL）：前端序列化后原样提交，服务端只校验不重组；
     * NULL=老客户端未传。单图 {@link #businessLicense} 继续保留（兼容读路径）。
     */
    private String licenses;
}
