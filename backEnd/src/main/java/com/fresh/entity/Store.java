package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自提点门店（T-M4-01）：GET /api/stores 匿名可读；
 * status=closed 由前端置灰不可选（pickup 页），下单服务端仅按 id 落 name/address 快照。
 */
@Data
@TableName("store")
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
    /** 创建时间：DB DEFAULT CURRENT_TIMESTAMP 兜底（MetaObjectHandler 只填 createTime/updateTime，不走 fill） */
    private LocalDateTime createdAt;
}
