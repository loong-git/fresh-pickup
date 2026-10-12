package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.StoreApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
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

    // ------------------------------------------------------------------
    // F-20.18 Step 5b-1｜运营端入驻审核工作台（GET /api/admin/v2/store-applies，只读）。
    // 上方三条既有语句一字未动。本组纪律与 AdminAuditLogMapper 的 F-20.16 一组同构：
    // 页 + 独立 COUNT（RB，禁 selectPage/Page<T>）、两条 WHERE 逐字相同（D-16 = 下面那个编译期常量串）、
    // 零 LIKE / 零 <script> / 零 @SelectProvider / 值只走 #{} 绑定 / 列名一律常量。
    // ⚠ pending_guard 是 STORED 生成列（uk_user_pending 的库侧闸门，见 init.sql:415 / m18 ⑦b），
    //   <b>绝不进 SELECT 列表</b>：它只是约束的载体，没有任何展示价值，把它选出来等于给出一份
    //   「同一用户是否已有在审申请」的第二处真源（那一判据的权威解释在应用层 countPendingByUser + D-21）。
    // D-18：created_at / audited_at 一律 DATE_FORMAT(...,'%Y-%m-%d %H:%i:%s') 取串（本表是 created_at，
    //   与 orders 的 create_time 不同名，别名照本表列名驼峰，跨表不统一是实况、不是笔误）。
    // ------------------------------------------------------------------

    /**
     * F-20.18 D-16：store-applies 面 COUNT 与页两条 SQL 的 WHERE <b>必须逐字相同</b>，用同一个编译期常量串作唯一来源。
     * 唯一筛选 {@code status} 是三态白名单（{@code pending}/{@code approved}/{@code rejected}，
     * DEFAULT 'pending'，见 init.sql:414），值域在 Service 拦（白名单外 → 400），本层只做等值；
     * 传 null 时该条件整体短路（= 不限状态，含三态全部）。
     * 同一参数绑两次是 D-9 那形态的既定代价、非笔误；等值不区分大小写来自列 collation，不是本层加的。
     */
    String STORE_APPLY_FILTER_WHERE = "WHERE (#{status} IS NULL OR sa.status = #{status})";

    /**
     * 运营端入驻申请列表的一页（契约 F-20.18 D-15 白名单逐键点名）：
     * <b>不选</b> {@code proof_photos}（执照图匿名映射问题仍未拍板，见契约 §9 悬项——「不输出」写成「不查询」，
     * 与本文件上方 mine 端点同一条纪律）、<b>不选</b> {@code user_id}（D-15 之外另加的最小暴露：
     * 审核动作 {@code PUT /api/admin/store-applies/{id}/audit} 只需要申请 id，申请人内部 id 不是它的前置信息）、
     * 不选 {@code pending_guard}（见上方段落的理由）。
     * <p>{@code contact_phone} 在这里<b>必须</b>取原文，因为脱敏走既有 {@code common/PhoneMask.mask}
     * （D-15 点名用它，不在 SQL 里 CONCAT 出第二口径）；掩码在 Service 侧完成，明文不出 Service。
     * 该列是 VARCHAR(20) 且<b>可空</b>（init.sql:412），PhoneMask 对 null 回 null、对非 11 位的脏值回全掩码。
     * <p>{@code lng}/{@code lat} 可下发：高德 GCJ-02 坐标，与已发布的 {@code store.lng/lat} 同口径，
     * 是「这个申请点位在哪」的展示必需位，不是个人信息。
     * <p>{@code ORDER BY sa.id DESC}：BIGINT 自增主键 = 全序稳定，最新申请在前（同 AdminAuditLogMapper 判据）。
     * <p>现库 {@code store_apply} <b>0 行</b>（F-20.18 前置事实）→ 空表是实况，本条与下方 COUNT 都要正常回
     * {@code {list:[], total:0}} 而不是 500；fixture 由主会话造。
     * <p>offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底。
     */
    @Select("SELECT sa.id AS id, sa.proposed_name AS proposedName, sa.proposed_address AS proposedAddress, "
            + "sa.lng AS lng, sa.lat AS lat, sa.service AS service, sa.contact_name AS contactName, "
            + "sa.contact_phone AS contactPhone, sa.status AS status, sa.audit_remark AS auditRemark, "
            + "DATE_FORMAT(sa.audited_at, '%Y-%m-%d %H:%i:%s') AS auditedAt, "
            + "DATE_FORMAT(sa.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt "
            + "FROM store_apply sa "
            + STORE_APPLY_FILTER_WHERE + " "
            + "ORDER BY sa.id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectStoreAppliesPage(@Param("status") String status,
                                                    @Param("offset") long offset,
                                                    @Param("size") int size);

    /**
     * 运营端入驻申请列表的总数（独立 COUNT）：WHERE 与 {@link #selectStoreAppliesPage} 逐字相同
     * （同 {@link #STORE_APPLY_FILTER_WHERE}），仅去掉 SELECT 列表/ORDER BY/LIMIT。
     */
    @Select("SELECT COUNT(*) FROM store_apply sa " + STORE_APPLY_FILTER_WHERE)
    long countStoreApplies(@Param("status") String status);
}
