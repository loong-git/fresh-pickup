package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Review {
    /** 审核状态-待复核（T-M4-05）：命中软词入库标记，评价列表默认不展示 */
    public static final String AUDIT_PENDING = "pending";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dishId;
    /** 评价用户ID（M6）：服务端按登录用户落库（溯源/支撑"我的评价"）；NULL=历史/种子数据（归属不可考） */
    private Long userId;
    private Integer rating;
    private String content;
    private String reviewer;
    /** 审核状态（T-M4-05）：NULL 正常展示 / pending 命中软词待人工复核（默认不展示） */
    private String auditStatus;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
