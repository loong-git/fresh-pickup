package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.StoreApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 入驻申请 mapper（F-18.12 第 1~2 条）。
 *
 * <p>读走 BaseMapper 的只有一个地方（Admin 审核取申请行按主键，无归属概念），
 * 面向申请人的两条读全是下方手写白名单取数，理由与 PartnerOrderMapper 同源：
 * <b>把「不输出」写成「不查询」</b>——{@code mine} 端点禁出 {@code contact_phone/proof_photos/user_id}（F-18.12 第 2 条、RD），
 * SQL 层连列都不取，泄露面才是零；实体那两个 {@code @JsonIgnore} 只是第二道闸。
 *
 * <p>分页纪律（RB）：本工程无 MP 分页插件，{@code LIMIT 1} 手写 + 独立 COUNT，禁 {@code selectPage}/{@code Page<T>}。
 * 列名与 m18-migration.sql ⑦ / init.sql 的 store_apply 建表逐字对齐。
 */
@Mapper
public interface StoreApplyMapper extends BaseMapper<StoreApply> {

    /**
     * 该用户最新一条申请（{@code GET /api/store-applies/mine}）：只取七个白名单列，
     * <b>不取 user_id / contact_phone / proof_photos / lng / lat / service / contact_name</b>。
     * id 自增单调 → {@code ORDER BY id DESC LIMIT 1} 就是「最新一条」，无需按 created_at 排（同秒并列时 id 才可靠）。
     */
    @Select("SELECT id AS id, proposed_name AS proposedName, proposed_address AS proposedAddress, "
            + "status AS status, audit_remark AS auditRemark, "
            + "created_at AS createdAt, audited_at AS auditedAt "
            + "FROM store_apply WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    Map<String, Object> selectLatestByUser(@Param("userId") Long userId);

    /** 该用户申请总条数（historyCount；与上方一条分开查 = RB 的「列表 + 独立 COUNT」口径） */
    @Select("SELECT COUNT(*) FROM store_apply WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    /** 该用户在审（pending）条数——D-21 的应用层闸门（走 idx_user_status 前缀 user_id + status 等值） */
    @Select("SELECT COUNT(*) FROM store_apply WHERE user_id = #{userId} AND status = 'pending'")
    long countPendingByUser(@Param("userId") Long userId);
}
