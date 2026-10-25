package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.CouponTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 按唯一键取模板行（F-113 §2.1 第 1 步查表）：不筛 status——「不存在」与「停用」
     * 在 claim/grant 里同一出口 400「无效的优惠券」（文案与常量时代逐字一致），
     * 不在 SQL 里合并是为了让 service 侧日志/语义能区分两者（测试与 verify 脚本按行为判）。
     */
    @Select("SELECT * FROM coupon_template WHERE coupon_key = #{couponKey}")
    CouponTemplate selectByCouponKey(@Param("couponKey") String couponKey);

    /**
     * 配额原子扣减（F-113 §2.1 第 3 步，契约 SQL 逐字）：条件 UPDATE issued_count+1，
     * WHERE 复核 status='active' + total_quota IS NOT NULL + issued_count &lt; total_quota——
     * 0 行 = 已罄（或并发停用），上层 400「该券已领完」；1 行 = 占额成功。
     * 范式照 user.cash_rewarded 封顶闸门（见 init.sql 的 user.cash_rewarded 列）：判定与自增同一条 UPDATE，
     * 无 COUNT+UPDATE 竞态；与 user_coupon insert 同事务，insert 后任何失败回滚时配额自增一并回滚。
     */
    @Update("UPDATE coupon_template SET issued_count = issued_count + 1 "
            + "WHERE coupon_key = #{couponKey} AND status = 'active' "
            + "AND total_quota IS NOT NULL AND issued_count < total_quota")
    int tryIssueQuota(@Param("couponKey") String couponKey);
}
