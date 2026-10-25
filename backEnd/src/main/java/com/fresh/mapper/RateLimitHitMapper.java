package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.RateLimitHit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 限流命中日志 mapper（F-121）：BaseMapper 承担拦截器命中旁路的 INSERT；
 * 三条追加语句全走 idx_hit_time 单列区间——清理 DELETE（hit_time &lt; cutoff）、
 * 运营聚合（GROUP BY route,bucket_key，排序与 top-100 截断在 Service 侧做，保持离线可断言）、
 * 窗口总数 COUNT（total 卡不受 top-100 截断影响）。观测低频读 + 命中即写的高频写面，
 * 不为 GROUP BY 建第二份索引（同 m28 选型口径）。
 */
@Mapper
public interface RateLimitHitMapper extends BaseMapper<RateLimitHit> {

    /**
     * 时间档内命中聚合（F-121 §2）：按 route + bucket_key 分组，count = 命中次数，lastHitAt = 最近命中。
     * 别名照本仓聚合 Map 出参惯例（PartnerWalletFlowMapper#countFlowsByUser 同款 userId/flowCount 姿势）。
     * lastHitAt 用 DATE_FORMAT 定形 "YYYY-MM-DD HH:mm:ss" 串（AdminAuditLogMapper#selectAuditLogsPage 同款
     * 理由：裸 DATETIME 进 Map 会拿到 java.sql.Timestamp，序列化形状不可控；运营页直接显示不做二次格式化）。
     * 排序（count 倒序）与 top-100 截断由 Service 侧执行——SQL 只管分组，离线测试可真实断言排序语义。
     */
    @Select("SELECT route, bucket_key AS bucketKey, COUNT(*) AS hitCount, "
            + "DATE_FORMAT(MAX(hit_time), '%Y-%m-%d %H:%i:%s') AS lastHitAt "
            + "FROM rate_limit_hit WHERE hit_time >= #{since} GROUP BY route, bucket_key")
    List<Map<String, Object>> selectAggregates(@Param("since") LocalDateTime since);

    /** 时间档内命中总行数（total 真源；全量计数，不被 top-100 截断） */
    @Select("SELECT COUNT(*) FROM rate_limit_hit WHERE hit_time >= #{since}")
    long countHitsSince(@Param("since") LocalDateTime since);

    /** 清理指定时刻之前的全部命中行（每日任务专用；返回删除行数，日志含行数）。只动 hit_time 之前的行。 */
    @Delete("DELETE FROM rate_limit_hit WHERE hit_time < #{before}")
    int deleteByHitTimeBefore(@Param("before") LocalDateTime before);
}
