package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 邀请现金封顶原子闸门（F-04.1 评审修订）：cash_rewarded 计数列条件自增，
     * cash_rewarded &lt; maxCount 不满足时影响行数为 0，并发注册由行锁串行化判定——
     * 替代原「SELECT COUNT(*) &lt; 10 → INSERT」check-then-act（COUNT 与 INSERT 无锁无原子性，可被并发绕过）。
     * 影响行数=1 → 上层写冻结流水；=0 → 已满 CASH_REWARD_MAX 封顶，记日志跳过（绑定照常）。
     */
    @Update("UPDATE user SET cash_rewarded = cash_rewarded + 1 WHERE id = #{id} AND cash_rewarded < #{maxCount}")
    int increaseCashRewarded(@Param("id") Long id, @Param("maxCount") int maxCount);

    /** 邀请现金入账（F-04.2 入账挂钩）：新人首单支付后原子累加，与 cash_flow status 0→1 置位同事务防半态 */
    @Update("UPDATE user SET balance = balance + #{amount} WHERE id = #{id}")
    int addBalance(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * 提现原子清零（F-04.2 修订③ 定稿口径：满 20 提现全部余额）：balance >= minBalance 才置 0，
     * 并发双提第二笔影响行数 0 → 上层不写流水、不透支；行锁串行化保证扣减与流水一致。
     */
    @Update("UPDATE user SET balance = 0 WHERE id = #{id} AND balance >= #{minBalance}")
    int clearBalanceIfEnough(@Param("id") Long id, @Param("minBalance") BigDecimal minBalance);
}
