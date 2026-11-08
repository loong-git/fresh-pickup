package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

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
    /** 客户端幂等标识（F-124），uk_review_client_request_id 唯一约束：同值重复提交返回首次评价不再落重复行；NULL=历史/种子/不带键的旧前端 */
    private String clientRequestId;
    private String reviewer;
    /** 审核状态（T-M4-05）：NULL 正常展示 / pending 命中软词待人工复核（默认不展示） */
    private String auditStatus;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 评价图路径列表（P10-6b D3/m34）：非持久化展示字段（review 本体零加列，图落 review_image 表），
     * 仅列表出参由 ReviewServiceImpl 批量填充（一条 IN 查询防 N+1），无图恒为空数组不出 null */
    @TableField(exist = false)
    private List<String> images;
}
