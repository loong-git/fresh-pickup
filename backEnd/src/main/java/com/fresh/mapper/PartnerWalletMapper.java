package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.PartnerWallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 主理人佣金钱包 mapper（F-119）：BaseMapper 承担读（本人钱包/运营列表）；
 * 余额变动只有两条原子语句（下方 upsertAdjust 跑批记账 + deductIfSufficient P10-5 提现扣减），
 * 无任何按值覆写 balance 的 UPDATE——钱包数值只允许「在现值上原子加/减」，杜绝 check-then-act
 * 丢更新（照 UserMapper#addBalance 的 {@code SET balance = balance + #{amount}} 原子累加先例）。
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

    /**
     * 提现条件扣减（P10-5 轨道 B D6/m32，余额充足的<b>并发真闸</b>）：
     * {@code SET balance = balance - #{amount} WHERE user_id = #{userId} AND balance >= #{amount}}——
     * 影响行数 1=扣减成功，0=余额不足（含钱包不存在与并发双提的落败方），调用方一律 400 拒绝。
     * 与 upsertAdjust 刻意不同：带下限谓词（提现绝不可透支减至负，与冲回「如实记负」是两种语义）、
     * 无 upsert（提现不为零余额钱包建行）。仍是「现值上原子减」，无按值覆写。
     */
    @Update("UPDATE partner_wallet SET balance = balance - #{amount} "
            + "WHERE user_id = #{userId} AND balance >= #{amount}")
    int deductIfSufficient(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
