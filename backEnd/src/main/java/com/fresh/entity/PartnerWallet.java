package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主理人佣金钱包 partner_wallet（F-119/m28 建表，列清单与 init.sql 的建表体逐字一致）：
 * 一人一钱包（uk_user_wallet），独立载体（05 册 A7.2）——不与 settlement 共表，也不与
 * {@code user.balance}（邀请现金钱包，m11）互通，两钱包物理隔离（F-119 契约 §4 红线）。
 * <p><b>写路径只有跑批</b>（契约 §3）：balance 只被 F-118 生成账期事务并入的记账步原子加/减
 * （{@code PartnerWalletMapper#upsertAdjust}，grant 正入 / reversal 负冲）；无人工调额、无提现
 * （轨道 B），运营端与主理人端各只有只读端点。余额可被冲回减至负值（佣金已付情形轨道 B 处理，如实记账）。
 */
@Data
@TableName("partner_wallet")
public class PartnerWallet {

    /** 钱包ID：数据库自增（m28 DDL AUTO_INCREMENT，与 settlement/merchant 同款） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主理人 user.id（uk_user_wallet 唯一钱包；主理人不占 user.role，归属由 store.owner_user_id 语义承载） */
    private Long userId;

    /** 佣金余额（记账态；提现属轨道 B；可被冲回减至负值） */
    private BigDecimal balance;

    /** 创建时间（DB DEFAULT CURRENT_TIMESTAMP 兜底） */
    private LocalDateTime createdAt;

    private LocalDateTime updateTime;
}
