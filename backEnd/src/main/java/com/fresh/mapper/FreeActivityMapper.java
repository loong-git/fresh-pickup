package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.FreeActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface FreeActivityMapper extends BaseMapper<FreeActivity> {

    /**
     * 每日限量原子计数（M7）：跨日重置与限量判定在同一条条件 UPDATE 内完成，杜绝 COUNT+INSERT 竞态，
     * 并发领取由行锁串行化判定。影响行数=0 即今日不可领（下线/未开始/已抢完），上层重读行区分三态文案。
     * 语义：daily_date=当日 → 份数未满才 +1；daily_date 为 NULL/过去（跨日首领）→ 重置为 1；
     * daily_date 为未来（脏数据/预置未开始）→ 恒不命中。
     */
    @Update("UPDATE free_activity SET daily_claimed = IF(daily_date = #{today}, daily_claimed + 1, 1), "
            + "daily_date = #{today} "
            + "WHERE id = #{id} AND status = 'online' AND daily_quota > 0 "
            + "AND ((daily_date = #{today} AND daily_claimed < daily_quota) "
            + "OR daily_date IS NULL OR daily_date < #{today})")
    int tryClaimQuota(@Param("id") Long id, @Param("today") LocalDate today);
}
