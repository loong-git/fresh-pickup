package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 邀请现金流水（F-04，cash_flow 表，m11-migration.sql 建表）：
 * - type 1=邀请奖励 2=提现；amount 恒为正数，type=2 提现为支出方向（记提现时全部余额，F-04.2 修订③）；
 * - status 承载「冻结→入账」两态（F-04.1 评审修订）：type=1 注册时 status=0 冻结——注册时不动
 *   user.balance、不产生任何可提现金额（F-04.2 修订②，堵脚本批量注册薅现金），新人首单支付后由
 *   InviteService#onOrderPaid 条件置位 0→1 入账并原子累加余额；type=2 提现恒为 1（无冻结语义）；
 * - status 条件置位（UPDATE ... SET status=1 WHERE ... AND status=0）防并发重复入账，
 *   影响行数=1 才加余额，范式同 InviteRelation 0→1 置位（InviteServiceImpl#onOrderPaid）；
 * - balance_after 为变动后余额快照：type=1 冻结时=当时余额（未变动），type=2 提现后恒为 0（满 20 提全部余额）。
 */
@Data
@TableName("cash_flow")
public class CashFlow {

    /** 邀请奖励（type） */
    public static final int TYPE_REWARD = 1;
    /** 提现（type） */
    public static final int TYPE_WITHDRAW = 2;

    /** 冻结（status，type=1 注册时初始态；新人首单支付后 0→1 入账） */
    public static final int STATUS_FROZEN = 0;
    /** 已入账（status；type=2 提现恒为此值） */
    public static final int STATUS_CREDITED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（邀请人/提现人），idx_user(user_id, created_at) 支撑流水列表 */
    private Long userId;

    /** 1=邀请奖励 2=提现 */
    private Integer type;

    /** 1=已入账 0=冻结（type=1 注册时冻结，新人首单支付后 0→1 入账） */
    private Integer status;

    /** 正数金额；type=2 提现为支出（记提现时全部余额） */
    private BigDecimal amount;

    /** 变动后余额快照（type=1 冻结时=当时余额未变动；type=2 提现后=0） */
    private BigDecimal balanceAfter;

    /** type=1 时的新人 user.id（idx_invitee 支撑入账挂钩按 invitee 检索冻结流水）；type=2 为 NULL */
    private Long inviteeId;

    /** 备注（如 提现；不出现内部词，F-04.2 修订④） */
    private String remark;

    /** 创建时间（DB DEFAULT CURRENT_TIMESTAMP 兜底） */
    private LocalDateTime createdAt;
}
