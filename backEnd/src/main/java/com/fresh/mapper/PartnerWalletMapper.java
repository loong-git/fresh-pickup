package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.PartnerWallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 主理人佣金钱包 mapper（F-119）：BaseMapper 承担读（本人钱包/运营列表）；
 * 余额变动只有一条原子语句（下方 upsertAdjust），无任何按值覆写 balance 的 UPDATE——
 * 钱包数值只允许「在现值上原子加/减」，杜绝 check-then-act 丢更新（照 UserMapper#addBalance 的
 * {@code SET balance = balance + #{amount}} 原子累加先例）。
 */
@Mapper
public interface PartnerWalletMapper extends BaseMapper<PartnerWallet> {

    /**
     * 佣金余额原子增减 + upsert（F-119 §2「upsert partner_wallet（不存在则建，balance 原子加）」逐字）：
     * 钱包不存在则随首笔记账建行（balance 初值=本笔 delta），存在则原位加/减——并发跑批由 InnoDB
     * 行锁串行化，无「先读后写」窗口。delta 正入（grant）负冲（reversal）；不加任何下限谓词：
     * 冲回可把余额减至负（契约 §2-2「余额不足减至负也如实记」），列无 UNSIGNED。
     * 返回影响行数恒 1（INSERT 或 UPDATE 二者居一），调用方不依赖该值。
     */
    @Insert("INSERT INTO partner_wallet (user_id, balance) VALUES (#{userId}, #{delta}) "
            + "ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)")
    int upsertAdjust(@Param("userId") Long userId, @Param("delta") BigDecimal delta);
}
