package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.MerchantApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 商家入驻申请 mapper（F-106 片 A）。
 *
 * <p>面向申请人的读走下方手写白名单取数（同 StoreApplyMapper 的纪律：把「不输出」写成「不查询」）：
 * {@code mine} 只取 id/merchant_name/status/audit_remark/created_at/audited_at，连 user_id 都不 SELECT。
 * Admin 审核按主键 {@code selectById}（无归属概念）。
 *
 * <p>列名与 m21-migration.sql / init.sql 的 merchant_apply 建表逐字对齐；{@code pending_guard} 是生成列，
 * 只作唯一键载体，绝不进 SELECT 列表。
 */
@Mapper
public interface MerchantApplyMapper extends BaseMapper<MerchantApply> {

    /**
     * 该用户最新一条申请（{@code GET /api/merchant-applies/mine}）：id 自增单调，
     * {@code ORDER BY id DESC LIMIT 1} 即「最新一条」，同秒并列时 id 才可靠。
     * 只取六个白名单列，不取 user_id / contact_name / business_license / legal_person / address / settlement_account。
     */
    @Select("SELECT id AS id, merchant_name AS merchantName, status AS status, "
            + "audit_remark AS auditRemark, created_at AS createdAt, audited_at AS auditedAt "
            + "FROM merchant_apply WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    Map<String, Object> selectLatestByUser(@Param("userId") Long userId);

    /** 该用户在审（pending）条数——应用层闸门（走 uk_user_pending 的前缀 user_id） */
    @Select("SELECT COUNT(*) FROM merchant_apply WHERE user_id = #{userId} AND status = 'pending'")
    long countPendingByUser(@Param("userId") Long userId);
}
