package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

    /**
     * 运营端商家列表的筛选 WHERE（F-122 §3.4.2，页与 COUNT 两条 SQL 逐字共用）：
     * 两参为 null 即短路为全部；status 非 null 由 Service 归一小写并过三态白名单后才绑定。
     */
    String MERCHANT_LIST_WHERE = "WHERE (#{merchantId} IS NULL OR id = #{merchantId})"
            + " AND (#{status} IS NULL OR status = #{status})";

    /**
     * 运营端商家列表的一页（F-122 §3.4.2，{@code GET /api/admin/merchants}）：
     * 只 SELECT 白名单六列（credit_status/remark/update_time 等不查询）；
     * contact_phone 原文交 Service 过 {@code PhoneMask.mask}，明文不出 Service 层。
     * 本工程无 MP 分页插件（{@code selectPage}/{@code Page<T>} 会静默回全量且 total=0），页面与
     * COUNT 走两条手写 SQL；{@code ORDER BY id ASC}（商家少，与 F-114 /stores 的 id 升序一致）。
     */
    @Select("SELECT id AS id, name AS name, contact_phone AS contactPhone, contact_name AS contactName, "
            + "status AS status, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt "
            + "FROM merchant " + MERCHANT_LIST_WHERE + " ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectMerchantsPage(@Param("merchantId") Long merchantId,
                                                  @Param("status") String status,
                                                  @Param("offset") long offset,
                                                  @Param("size") int size);

    /** 运营端商家总数（独立 COUNT）：WHERE 与 {@link #selectMerchantsPage} 逐字相同 */
    @Select("SELECT COUNT(*) FROM merchant " + MERCHANT_LIST_WHERE)
    long countMerchants(@Param("merchantId") Long merchantId,
                        @Param("status") String status);
}
