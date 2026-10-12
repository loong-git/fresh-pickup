package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 主理人入驻申请 store_apply（F-18.10 第 2 条建表 / F-18.12 第 1~3 条读写，m18-migration.sql ⑦）。
 *
 * <p>状态机三态 pending/approved/rejected（口径照 m15 的 merchant_profile.audit_status）；
 * <b>D-21</b>「一用户一期最多 1 条 pending」由应用层校验（无唯一键，rejected 后重新提交=新行）。
 *
 * <p><b>D-23 同款纪律</b>：{@code contactPhone}/{@code proofPhotos} 也挂 {@code @JsonIgnore}——
 * 本类没有任何「直接返回实体」的出口（申请出参 {@code {id,status}}、{@code mine} 出参、Admin 审核出参
 * 全是白名单 Map/空数据），但先例（Store:31 的 ownerUserId）证明「靠调用方自觉」迟早失手，
 * 把「不出参」写在实体上才是零泄露面（RD）。
 *
 * <p>{@code lng/lat} 是高德 GCJ-02 真源，与 {@link Store#getLng()} 同口径，服务端只做中国粗框
 * （lng∈[73,135]、lat∈[3,54]）范围校验，<b>不做坐标转换</b>（HANDOFF §8 的不对称口径）。
 */
@Data
@TableName(value = "store_apply", autoResultMap = true) // proof_photos 走 JacksonTypeHandler，查询需 autoResultMap
public class StoreApply {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人 user.id：唯一可信来源是 AuthInterceptor.ATTR_USER_ID（token 解析），绝不收 body 里的 userId（RF） */
    private Long userId;

    /** 拟用门店名称（VARCHAR(100)）；过审前它只是申请内容，绝不出现在任何门店列表里 */
    private String proposedName;

    /** 拟用门店地址（VARCHAR(255)） */
    private String proposedAddress;

    /** 经度 DECIMAL(10,6) NULL（与 store.lng 同口径） */
    private BigDecimal lng;

    /** 纬度 DECIMAL(10,6) NULL（与 store.lat 同口径） */
    private BigDecimal lat;

    /** 服务说明 VARCHAR(50)，对齐 store.service */
    private String service;

    /** 联系人姓名 VARCHAR(50) */
    private String contactName;

    /** 联系电话 VARCHAR(20)：任何响应/审计 detail 只能出脱敏值（RD） */
    @JsonIgnore
    private String contactPhone;

    /** 凭证照片（D-18）：JSON 数组串列，映射为字符串数组；上限 6 张、前缀白名单见 StoreApplyCreateDTO */
    @JsonIgnore
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> proofPhotos;

    /** 审核状态：pending / approved / rejected（VARCHAR(20) NOT NULL DEFAULT 'pending'） */
    private String status;

    /** 审核意见 VARCHAR(255)（驳回原因供申请人在 mine 出参里看到） */
    private String auditRemark;

    /** 审核时间：NULL=未审（approve/reject 两条路径同事务写入） */
    private LocalDateTime auditedAt;

    /** 申请时间：DB DEFAULT CURRENT_TIMESTAMP 落值，实体不填、无 fill 注解（照 OpsLog:46 / AdminAuditLog:40） */
    private LocalDateTime createdAt;
}
