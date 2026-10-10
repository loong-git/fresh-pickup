package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家主体表 merchant（F-12/G-01，m15-migration.sql:17-29）：零物理外键，归属全靠逻辑外键。
 * 种子 id=1「平台自营」；contact_phone 为登录号快照（权威绑定在 user.merchant_id，此列仅展示/检索，不设 UNIQUE）。
 */
@Data
@TableName("merchant")
public class Merchant {

    /** 商家ID：数据库自增（平台自营固定 id=1） */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 联系手机号（出参须脱敏，R4 红线） */
    private String contactPhone;

    /** 联系人 */
    private String contactName;

    /** 生命周期状态：active 正常 / suspended 停业整顿 / terminated 清退 */
    private String status;

    /** 信用分（三期预留，一期 NULL） */
    private String creditStatus;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updateTime;
}
