package com.fresh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交评价入参 DTO（T-M1-04）
 * 校验规则与统一契约对齐：rating 限 1~5，content 1~500 字，dishId 由路径变量必填传入。
 * 长度上限同时与 review 表列定义对齐：content varchar(500)、reviewer varchar(50)。
 */
@Data
public class ReviewCreateDTO {

    /** 评分，限 1~5 */
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分需在 1~5 之间")
    @Max(value = 5, message = "评分需在 1~5 之间")
    private Integer rating;

    /** 评价内容，1~500 字 */
    @NotBlank(message = "评价内容不能为空")
    @Size(min = 1, max = 500, message = "评价内容需在 1~500 字之间")
    private String content;

    /** 评价人昵称（M6 起服务端按登录手机号脱敏生成，本字段已废弃不读，保留仅为兼容旧前端入参） */
    @Size(max = 50, message = "评价人昵称过长")
    private String reviewer;
}
