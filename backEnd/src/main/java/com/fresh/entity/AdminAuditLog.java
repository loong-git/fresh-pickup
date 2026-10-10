package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理/商家操作审计 admin_audit_log（F-12/G-01 M-04 补课，m15-migration.sql:89-101）：
 * 现状日志不记操作者（AdminServiceImpl.java:93 反面教材），本期随建随写。
 * 审计是旁路：写失败只告警，绝不影响主事务。
 */
@Data
@TableName("admin_audit_log")
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作主体：admin:&lt;key 摘要&gt;（R8：绝不明文落库密钥） */
    private String actor;

    /** 动作：dish.update / order.status / review.audit / merchant.create */
    private String action;

    /** 目标类型：dish / order / review / merchant */
    private String targetType;

    /** 目标ID（订单号为 VARCHAR 口径，故本列字符串） */
    private String targetId;

    /** 旧值→新值 JSON */
    private String detail;

    /** 操作来源 IP（IPv6 容量；取不到为 NULL） */
    private String ip;

    private LocalDateTime createdAt;
}
