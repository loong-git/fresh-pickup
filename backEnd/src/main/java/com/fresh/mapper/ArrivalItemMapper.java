package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.ArrivalItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 到货批次明细 mapper（F-18.12 第 6 条「清单出参」）。
 *
 * <p>一期只读（D-19：确认动作只翻批次状态，不写 actual_qty/short_reason），故本类只有一条批量取明细的
 * 手写 SQL：按上一跳拿到的 batchIds 一次取回，<b>不在循环里查库</b>（N+1）。
 * {@code LEFT JOIN dish} 只为把菜名带出来（ arrival_item 只有 dish_id，光给 id 主理人看不出是什么货）；
 * LEFT JOIN 而非 INNER：dish 被平台删掉时明细仍要出得来（expected_qty 是批次快照语义）。
 */
@Mapper
public interface ArrivalItemMapper extends BaseMapper<ArrivalItem> {

    /**
     * 明细列表：只取清单需要的六列（不含 short_reason 之外的隐私，且本表根本没有手机号/地址类列）。
     * ORDER BY ai.id ASC → 与 m18 种子的插入顺序一致，出参全序稳定。
     */
    @Select("<script>SELECT ai.id AS id, ai.batch_id AS batchId, ai.dish_id AS dishId, d.name AS dishName, "
            + "ai.expected_qty AS expectedQty, ai.actual_qty AS actualQty "
            + "FROM arrival_item ai LEFT JOIN dish d ON d.id = ai.dish_id "
            + "WHERE ai.batch_id IN <foreach item='bid' collection='batchIds' open='(' separator=',' close=')'>#{bid}</foreach> "
            + "ORDER BY ai.id ASC</script>")
    List<Map<String, Object>> selectItemsByBatchIds(@Param("batchIds") List<Long> batchIds);
}
