package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家/主理人现场动作审计 ops_log（F-18.8 第 3 条/D-5，m17-migration.sql:90-99 建表）。
 *
 * <p>与 {@link AdminAuditLog}（m15）的分工是收窄过的（D-5）：本表只收**现场动作**——
 * 核销（verify_order）、缺货登记（register_shortage）、到货确认、门店信息变更；
 * 平台侧动作（store_apply 审核、Admin 改单）一律走 admin_audit_log，两表不合并
 * （operator_user_id 是 BIGINT，装不下 {@code admin:<摘要8>} 那种字符串主体）。
 *
 * <p>写法逐字照 {@link AdminAuditLog}：{@code createdAt} 是**裸声明**、不加
 * {@code @TableField(fill = ..)}——该列由 DB {@code DEFAULT CURRENT_TIMESTAMP} 落值
 * （AdminAuditLog:40 同型；MP 的 MetaObjectHandler 只填 createTime/updateTime，不走 fill）。
 * 也没有 target_type 列（m17 建表就没有，D-5 边界由表注释承担），别按 AdminAuditLog 多写一列。
 *
 * <p>detail 是**无隐私摘要**（RD 红线）：只放状态迁移与 storeId 这类内部标识，
 * 不许放手机号/地址/取货码本身。
 */
@Data
@TableName("ops_log")
public class OpsLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者 userId（D-5：商家/主理人本人，取 AuthInterceptor.ATTR_USER_ID，不收前端传的「操作者」） */
    private Long operatorUserId;

    /** 动作：verify_order / register_shortage / ...（VARCHAR(50)） */
    private String action;

    /** 目标对象ID（VARCHAR(64)：订单号是 19 位雪花字符串、明细 id 是数字，统一按字符串落） */
    private String targetId;

    /** 变更摘要（VARCHAR(500) 且 NULLable，m17:95）；无隐私内容，见类注释 */
    private String detail;

    /** 操作时间：DB DEFAULT CURRENT_TIMESTAMP 落值，实体不填、无 fill 注解（照 AdminAuditLog:40） */
    private LocalDateTime createdAt;
}
