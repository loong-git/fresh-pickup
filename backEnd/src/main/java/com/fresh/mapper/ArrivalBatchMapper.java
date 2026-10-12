package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.ArrivalBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 到货批次 mapper（F-18.12 第 6 条）：一条按归属集合 + 日期读批次、一条四条件确认 UPDATE。
 *
 * <p>手写 SQL 的必要性同 PartnerOrderMapper：确认那条 UPDATE 的 WHERE 必须<b>同时</b>含
 * 归属集合（{@code store_id IN (…)}）与状态白名单，且「影响行数 0」要能被 Service 定性成
 * 幂等/404/400 三种结果——BaseMapper 的 {@code updateById} 给不了这个形状。
 * 归属集合值只来自 {@code AuthInterceptor.ATTR_PARTNER_STORE_IDS}（服务端按 token userId 查 store.owner_user_id），
 * 不收任何归属入参（RF）；集合为空在拦截器已 403，Service 侧再自守卫一次，绝不退化成「不加条件」的全表读写。
 */
@Mapper
public interface ArrivalBatchMapper extends BaseMapper<ArrivalBatch> {

    /**
     * 某店某日的批次（uk_store_date 保证至多一行，故无分页；RB 只禁 selectPage/Page，这里根本不需要分页）。
     * 只取批次展示需要的列， {@code supplier_type} 一期恒 NULL 不取（D-9 预留位）。
     */
    @Select("SELECT id AS id, store_id AS storeId, batch_date AS batchDate, status AS status, "
            + "confirm_time AS confirmTime, remark AS remark "
            + "FROM arrival_batch WHERE store_id = #{storeId} AND batch_date = #{date} ORDER BY id ASC")
    List<Map<String, Object>> selectBatchesByStoreAndDate(@Param("storeId") Long storeId,
                                                          @Param("date") LocalDate date);

    /**
     * 到货确认（F-18.12 第 6 条四条件逐字）：
     * {@code SET status='confirmed', confirm_time=NOW() WHERE id=? AND store_id IN (归属集合) AND status IN ('planned','arriving')}。
     * 状态判据只当条件不当动作（同 PartnerOrderMapper#completeByPickupCode 的姿势）；
     * 本表没有 update_time 列，故不需要额外补 NOW()。
     * <p>{@code status IN ('planned','arriving')} 同时是「已 confirmed 再确认」的幂等闸门：
     * 本仓数据源 URL 未带 useAffectedRows=true（application-dev.yml:5），Connector/J 按 <b>matched rows</b> 回影响行数，
     * 少了这一段状态条件，重复确认会回 1 行、进而<b>再写一条 ops_log</b>，正面撞 S4b-G6「重复确认幂等不重复写日志」
     * （与 MerchantFulfillServiceImpl 类注释登记的同一成因）。
     */
    @Update("<script>UPDATE arrival_batch SET status = 'confirmed', confirm_time = NOW() "
            + "WHERE id = #{id} "
            + "AND store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach> "
            + "AND status IN ('planned', 'arriving')</script>")
    int confirmById(@Param("id") Long id, @Param("storeIds") List<Long> storeIds);

    /**
     * 确认失败后的定性预读：<b>只取 id/store_id/status/confirm_time 四列</b>，按主键 + <b>归属集合</b>取
     * （BE-12，F-18.20 K 行：判定写进 SQL——{@code store_id IN (…)} 与主键同条，摘不掉；
     * Service 侧内存比集合保留作纵深）。「他店批次」与「不存在」都落 null → 同一个 404 文案，不泄漏行存在性。
     * confirm_time 一并取是为了幂等分支也能把已确认时间回给出参（重复确认不再写第二行 ops_log）。
     * <p>形态照本文件 confirmById：{@code <script>} + foreach（归属只来自 ATTR_PARTNER_STORE_IDS，RF）。
     */
    @Select("<script>SELECT id AS id, store_id AS storeId, status AS status, confirm_time AS confirmTime "
            + "FROM arrival_batch WHERE id = #{id} "
            + "AND store_id IN <foreach item='sid' collection='storeIds' open='(' separator=',' close=')'>#{sid}</foreach></script>")
    Map<String, Object> selectForConfirm(@Param("id") Long id, @Param("storeIds") List<Long> storeIds);
}
