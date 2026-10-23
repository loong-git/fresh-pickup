package com.fresh.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端更新字典词条入参（F-108 §0.7，{@code PUT /api/admin/dict/items/{id}}）：
 * body {@code {word?, definition?, sortOrder?}}，<b>部分更新</b>——null 表示本次不改该列。
 *
 * <p>与新增 DTO 分开的原因：新增时 word 必填（{@code @NotBlank}），更新时三列都可省略
 * （全部省略由 Service 回 400「无更新字段」），同一个类的注解无法同时表达两种语义。
 */
@Data
public class AdminDictItemUpdateDTO {

    /** 新显示名（可空 = 不改；传空白串仍按 400「词条名称不能为空」处理） */
    @Size(max = 100, message = "词条名称最长 100 个字符")
    private String word;

    /** 新定义（可空 = 不改；允许空串，用于清成空定义） */
    @Size(max = 500, message = "词条定义最长 500 个字符")
    private String definition;

    /** 新排序值（可空 = 不改） */
    private Integer sortOrder;
}
