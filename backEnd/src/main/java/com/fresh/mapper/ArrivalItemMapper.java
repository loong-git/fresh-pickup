package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.ArrivalItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 到货批次明细 mapper（F-18.12 第 6 条「清单出参」；F-63 起运营端发车是本表唯一的运行期写入方）。
 *
 * <p>确认动作只翻批次状态、不写 actual_qty/short_reason（D-19）；读仍是一条批量取明细的手写 SQL：
 * 按上一跳拿到的 batchIds 一次取回，<b>不在循环里查库</b>（N+1）。写侧同守：幂等重算只走「按 batch_id 删 + 一条 foreach 批量插」两步（本表无唯一键，撞不了键）。
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

    /**
     * 发车重算第一步（F-63）：按批次删旧明细。arrival_item 实测只有 PRIMARY 与 idx_batch、无唯一键，
     * 幂等重算不能指望撞键去重，只能先删后插；新批次删 0 行无害——两条路径同形，不走分支。
     */
    @Delete("DELETE FROM arrival_item WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);

    /**
     * 发车重算第二步（F-63）：一条 foreach 批量插入整批明细——硬要求禁循环单条 INSERT（N+1），
     * 与上方读侧「不在循环里查库」是同一条纪律的两面。
     * <p>列清单不含 actual_qty/short_reason：发车只产应发数（expected_qty），清点仍留给确认之后的事。
     * 调用方保证 items 非空（空车在判据第 ⑤ 步已 400），空集合会拼出非法 SQL，不给它发生的机会。
     */
    @Insert("<script>INSERT INTO arrival_item (batch_id, dish_id, expected_qty) VALUES "
            + "<foreach item='it' collection='items' separator=','>"
            + "(#{it.batchId}, #{it.dishId}, #{it.expectedQty})</foreach></script>")
    int insertBatchItems(@Param("items") List<ArrivalItem> items);

    /**
     * 预览用（F-63）：批次应发件数 = 该批次 Σ expected_qty（与发车出参 itemCount 同一口径，
     * 都是件数不是行数——明细一行是一道菜，行数已由 dishKinds 表达）。
     * SUM 在 MySQL 下回 BigDecimal、IFNULL 兜 0，Service 侧显式转（本仓 Map 结果集既有口径）。
     */
    @Select("SELECT IFNULL(SUM(expected_qty), 0) FROM arrival_item WHERE batch_id = #{batchId}")
    long sumExpectedQtyByBatchId(@Param("batchId") Long batchId);
}
