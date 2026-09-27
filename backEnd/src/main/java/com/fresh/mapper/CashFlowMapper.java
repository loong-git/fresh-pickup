package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.CashFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface CashFlowMapper extends BaseMapper<CashFlow> {

    /**
     * 按类型+状态聚合流水金额（契约 F-04.2 GET /api/invite/cash）：
     * frozenTotal = type=1 AND status=0（冻结中），totalEarned = type=1 AND status=1（累计已入账）。
     * IFNULL 兜底无流水返回 0；DECIMAL SUM 保留 2 位小数。#{} 参数化（B.3 规约：自写 SQL 一律 #{}，禁 ${}）。
     */
    @Select("SELECT IFNULL(SUM(amount), 0) FROM cash_flow"
            + " WHERE user_id = #{userId} AND type = #{type} AND status = #{status}")
    BigDecimal sumAmount(@Param("userId") Long userId, @Param("type") int type, @Param("status") int status);
}
