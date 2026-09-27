package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请新人关系（F-01，invite_relation 表，m9-migration.sql 建表）：
 * - 绑定时机（F-01.0 裁定）：被邀人验证码登录创建新账号的同一事务内写入，不做独立 bind 接口；
 *   uk_invitee（invitee_id UNIQUE）一人仅可被邀请一次，重复携带邀请码登录不重复绑定；
 * - 奖励时点（F-01.0 裁定）：邀请人在新人首单支付成功后得奖励券——status 0→1 由
 *   InviteService#onOrderPaid 原子置位（影响行数=1 才发券，防并发重复发券）；
 * - invitee_phone 存脱敏快照（138****1235，F-01.0 攻击面裁定）：表内无原手机号，无手机号枚举面。
 */
@Data
@TableName("invite_relation")
public class InviteRelation {

    /** 已注册未完成首单（status 初始值） */
    public static final int STATUS_PENDING = 0;
    /** 已完成首单（奖励已处理；置位时写 rewarded_at） */
    public static final int STATUS_REWARDED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 邀请人 user.id（idx_inviter 统计/列表） */
    private Long inviterId;
    /** 被邀新人 user.id（uk_invitee 一人仅一次） */
    private Long inviteeId;
    /** 被邀人手机号脱敏快照（138****1235，绑定即定型，仅用于邀请人侧展示） */
    private String inviteePhone;
    /** 0=已注册未完成首单 1=已完成首单（奖励已处理） */
    private Integer status;
    /** 绑定时间（DB DEFAULT CURRENT_TIMESTAMP 兜底） */
    private LocalDateTime createdAt;
    /** 邀请人奖励发放时间（首单支付置位 status=1 时写入；NULL=未发奖） */
    private LocalDateTime rewardedAt;
}
