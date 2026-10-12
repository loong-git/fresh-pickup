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
 * 自提点门店（T-M4-01）：GET /api/stores 匿名可读；
 * status=closed 由前端置灰不可选（pickup 页），下单服务端仅按 id 落 name/address 快照。
 *
 * <p><b>D-23（F-18.12 第 2 条裁定，本轮最易翻车处）</b>：m18 给本表加的六列
 * （open_time/close_time/daily_capacity/accepting/contact_phone/photos）在本实体上<b>逐个挂
 * Jackson 的 {@code JsonIgnore} 注解</b>，与 {@link #ownerUserId} 同一先例——唯一的 {@code GET /api/stores}
 * 走的是 StoreServiceImpl#listAll:21 的 {@code storeMapper.selectList(...)}，<b>返回的就是实体本身</b>，
 * 少挂一个注解就等于把该列直接下发给 C 端（D-12/RD 破口）。
 * 门禁断言：{@code GET /api/stores} 的键集合必须仍等于
 * {@code id,name,address,service,status,lng,lat,createdAt}（8 键）。
 * 主理人侧要读这六列，走 PartnerStoreService 的<b>白名单 Map 重建</b>，绝不把本实体丢给前端。
 */
@Data
@TableName(value = "store", autoResultMap = true) // photos 走 JacksonTypeHandler，查询需 autoResultMap 回映射（照 Dish:16 先例）
public class Store {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String address;
    /** 服务说明（如 冷冻冷藏） */
    private String service;
    /** 营业状态：open 营业 / closed 停业 */
    private String status;
    /** 经度（F-06 地图，m12）：NULL=历史数据，前端不进地图不参与距离排序 */
    private BigDecimal lng;
    /** 纬度（F-06 地图，m12）：NULL 同 lng 口径 */
    private BigDecimal lat;
    @JsonIgnore
    private Long ownerUserId;

    // ---- 以下六列 = m18-migration.sql ①（F-18.10 第 1 条），逐列挂 Jackson 忽略注解（D-23/D-12/RD）----

    /** 营业开始时间 HH:MM（仅展示，不参与 23:00 全局截单裁决=RE 红线）；NULL=未设置 */
    @JsonIgnore
    private String openTime;

    /** 营业结束时间 HH:MM（与 openTime 同为展示口径，不产生接单/截单判定）；NULL=未设置 */
    @JsonIgnore
    private String closeTime;

    /** 日接单容量（D-8：NULL=不限，一期只展示、不自动降级 accepting） */
    @JsonIgnore
    private Integer dailyCapacity;

    /** 满容/休市细粒度开关（m18:accepting TINYINT NOT NULL DEFAULT 1），与 status 的 open/closed 正交 */
    @JsonIgnore
    private Integer accepting;

    /** 门店联系电话（VARCHAR(20)，对齐 orders.phone 惯例）；RD：任何出参只能以 PhoneMask.mask 结果出现 */
    @JsonIgnore
    private String contactPhone;

    /** 门店照片（D-18：VARCHAR(1000) 存 JSON 数组串，上限 6 张、每张 /static/images/uploads/ 前缀；一期无上传端点） */
    @JsonIgnore
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> photos;

    /** 创建时间：DB DEFAULT CURRENT_TIMESTAMP 兜底（MetaObjectHandler 只填 createTime/updateTime，不走 fill） */
    private LocalDateTime createdAt;
}
