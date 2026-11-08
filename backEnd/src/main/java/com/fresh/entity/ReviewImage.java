package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价图（P10-6b D2/m34）：review 本体零加列，图作为新增附着物落本表。
 * 一评价 ≤3 张（服务端计数拒绝，ReviewServiceImpl#addImage）；
 * review 被 reject 物理删行时联动删本表行 + 文件尽力删（AdminServiceImpl#auditReview，契约 D4）。
 */
@Data
public class ReviewImage {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 评价ID（逻辑外键 review.id；无 DB 级 FK，与全仓惯例一致，归属一致性由 service 校验） */
    private Long reviewId;
    /** 图片路径：/uploads/reviews/{yyyyMM}/{uuid}.{ext}（契约 D7 出参形状；/uploads/** 静态映射已覆盖子目录） */
    private String filePath;
    /** 上传时间（MetaObjectHandler insertFill 自动填充，同 Review.createTime 口径） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
