package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 免费领商品领取记录（M7）：uk_user_date(user_id, claim_date) 数据库级"一人一天一份"权威约束
 * （MySQL UNIQUE 对 NULL 不去重，故 user_id/claim_date 均为 NOT NULL）。
 * 订单取消/退款不回补领取资格：封死"领取→取消→再领"刷单循环（见 FreeServiceImpl#claim 注释）。
 */
@Data
@TableName("free_claim")
public class FreeClaim {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 领取用户ID（token 解析，服务端写入） */
    private Long userId;
    /** 领取商品ID */
    private Long dishId;
    /** 关联0元订单号（orders.id 雪花串，建单前预生成回写） */
    private String orderId;
    /** 领取自然日（服务器时区，事务内单次采样） */
    private LocalDate claimDate;
    /** 领取时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
