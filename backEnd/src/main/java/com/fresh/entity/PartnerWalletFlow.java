package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主理人佣金流水 partner_wallet_flow（F-119/m28 建表，cash_flow 余额快照范式）：
 * biz_type 两值（本类常量为写侧唯一真源）；amount 正入负冲——grant 恒正 / reversal 恒负（绝对值同额，
 * 冲回冲的就是那一笔 grant）；balance_after 变动后余额快照（冲回减至负也如实记）；
 * rate_snapshot 费率快照 0.0300（05 册 A7.1，防改率追溯歧义）。
 * <p><b>uk_order_biz (order_id, biz_type) 是跑批幂等的库侧护栏</b>：同单同类型至多一条流水——
 * 佣金基数=订单实付 orders.total_price 整单口径，一单至多记一次佣金（跨商家同单二次记账被本键拦下）、
 * 一单至多冲回一次；跑批重入时 INSERT 撞 uk 即跳过该单，配合 settlement uk_merchant_period 双保险。
 * 写路径只有 F-118 生成账期跑批记账，无人工写入端点。
 */
@Data
@TableName("partner_wallet_flow")
public class PartnerWalletFlow {

    /** 佣金入账（biz_type）：amount 恒正 */
    public static final String BIZ_GRANT = "commission_grant";
    /** 佣金冲回（biz_type）：amount 恒负，绝对值与该单 grant 同额 */
    public static final String BIZ_REVERSAL = "commission_reversal";

    /** 费率快照真源（3%，08 册 §6 已批；DECIMAL(5,4) 定形四位小数） */
    public static final BigDecimal RATE_SNAPSHOT = new BigDecimal("0.0300");

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 钱包属主 user.id（idx_user_time 最左前缀支撑本人流水分页） */
    private Long userId;

    /** commission_grant 佣金入账 / commission_reversal 佣金冲回 */
    private String bizType;

    /** 佣金锚=订单号（orders.id 同款 VARCHAR 雪花串） */
    private String orderId;

    /** 产生它的账单 settlement.id（可追溯；Σ amount 按 settlement_id 归组即 settlement.commission 可互核） */
    private Long settlementId;

    /** 变动金额：正入负冲 */
    private BigDecimal amount;

    /** 变动后余额快照（cash_flow 范式；冲回可减至负，如实记） */
    private BigDecimal balanceAfter;

    /** 费率快照（0.0300，写侧恒取 {@link #RATE_SNAPSHOT}） */
    private BigDecimal rateSnapshot;

    /** 备注（预留轨道 B 提现等；本片两类流水不填） */
    private String remark;

    /** 创建时间（DB DEFAULT CURRENT_TIMESTAMP 兜底） */
    private LocalDateTime createdAt;
}
