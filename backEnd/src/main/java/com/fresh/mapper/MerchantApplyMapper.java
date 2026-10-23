package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.MerchantApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 商家入驻申请 mapper（F-106 片 A / 片 B2）。
 *
 * <p>面向申请人的读走下方手写白名单取数（同 StoreApplyMapper 的纪律：把「不输出」写成「不查询」）：
 * {@code mine} 只取 id/merchant_name/status/audit_remark/created_at/audited_at，连 user_id 都不 SELECT。
 * Admin 审核按主键 {@code selectById}（无归属概念）。
 *
 * <p>列名与 m21-migration.sql / init.sql 的 merchant_apply 建表逐字对齐；{@code pending_guard} 是生成列，
 * 只作唯一键载体，绝不进 SELECT 列表。
 *
 * <p><b>运营端列表（片 B2）</b>：页 + 独立 COUNT 两条手写 SQL（本工程无 MP 分页插件，
 * {@code selectPage}/{@code Page<T>} 会静默回全量且 total=0，红线同 StoreApplyMapper），
 * WHERE 共用下方编译期常量串。{@code applicantPhone} 来自 {@code LEFT JOIN user}——
 * 申请行没有电话列，申请人电话的唯一可信来源是 {@code user.phone}；该列取原文交给
 * {@code PhoneMask.mask} 脱敏（不在 SQL 里 CONCAT 出第二口径），明文不出 Service 层。
 */
@Mapper
public interface MerchantApplyMapper extends BaseMapper<MerchantApply> {

    /**
     * 运营端列表的筛选 WHERE（页与 COUNT 两条 SQL 逐字共用）。
     * <p>{@code #{status}} 为 null 时短路为全部；非 null 由 Service 归一小写并过白名单后才绑定。
     */
    String MERCHANT_APPLY_FILTER_WHERE = "WHERE (#{status} IS NULL OR ma.status = #{status})";

    /**
     * 该用户最新一条申请（{@code GET /api/merchant-applies/mine}）：id 自增单调，
     * {@code ORDER BY id DESC LIMIT 1} 即「最新一条」，同秒并列时 id 才可靠。
     * 只取七个白名单列（F-107 起含 {@code licenses} 原文），不取 user_id / contact_name / business_license / legal_person / address / settlement_account。
     */
    @Select("SELECT id AS id, merchant_name AS merchantName, status AS status, "
            + "audit_remark AS auditRemark, created_at AS createdAt, audited_at AS auditedAt, "
            + "licenses AS licenses "
            + "FROM merchant_apply WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    Map<String, Object> selectLatestByUser(@Param("userId") Long userId);

    /** 该用户在审（pending）条数——应用层闸门（走 uk_user_pending 的前缀 user_id） */
    @Select("SELECT COUNT(*) FROM merchant_apply WHERE user_id = #{userId} AND status = 'pending'")
    long countPendingByUser(@Param("userId") Long userId);

    /**
     * 运营端列表的一页（片 B2，{@code GET /api/admin/merchant-applies}）：
     * 十四键白名单（契约 §0.6 逐字点名 + F-107 的 {@code licenses} 原文），{@code applicantPhone} 取 {@code u.phone} 原文——
     * 脱敏在 Service 侧过 {@code PhoneMask.mask}，本 SQL 不负责掩码；
     * {@code licenses} 字符串原样带出（不 parse、不脱敏），由运营端自己解析展示。
     * <p><b>排序</b>：{@code pending} 优先（运营先看要处理的），其次 {@code created_at DESC, id DESC}。
     * 自增主键是唯一末列，分页全序稳定。
     * <p>生成列 {@code pending_guard} 绝不 SELECT（同 {@code mine} 的纪律）。
     * <p>offset/size 由 Service 校验并算好（越界脏值 400、不夹到边界），本 SQL 不做兜底。
     */
    @Select("SELECT ma.id AS id, ma.user_id AS userId, ma.merchant_name AS merchantName, "
            + "ma.contact_name AS contactName, ma.business_license AS businessLicense, "
            + "ma.licenses AS licenses, "
            + "ma.legal_person AS legalPerson, ma.address AS address, "
            + "ma.settlement_account AS settlementAccount, ma.status AS status, "
            + "ma.audit_remark AS auditRemark, "
            + "DATE_FORMAT(ma.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt, "
            + "DATE_FORMAT(ma.audited_at, '%Y-%m-%d %H:%i:%s') AS auditedAt, "
            + "u.phone AS applicantPhone "
            + "FROM merchant_apply ma LEFT JOIN `user` u ON u.id = ma.user_id "
            + MERCHANT_APPLY_FILTER_WHERE + " "
            + "ORDER BY (ma.status = 'pending') DESC, ma.created_at DESC, ma.id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectMerchantAppliesPage(@Param("status") String status,
                                                        @Param("offset") long offset,
                                                        @Param("size") int size);

    /** 运营端列表总数（独立 COUNT）：WHERE 与 {@link #selectMerchantAppliesPage} 逐字相同 */
    @Select("SELECT COUNT(*) FROM merchant_apply ma " + MERCHANT_APPLY_FILTER_WHERE)
    long countMerchantApplies(@Param("status") String status);
}
