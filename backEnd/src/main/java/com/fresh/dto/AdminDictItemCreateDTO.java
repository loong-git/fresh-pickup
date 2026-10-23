package com.fresh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端新增字典词条入参（F-108 §0.7，{@code POST /api/admin/dict/{group}/items}）：
 * body {@code {word, definition?, sortOrder?}}。
 *
 * <p>{@code word} 必填（not blank）；{@code definition}/{@code sortOrder} 可空，sortOrder 缺省落 0。
 * 列宽与 DDL 对齐：word VARCHAR(100)、definition VARCHAR(500)；超长在入参层就回 400，
 * 不让 MySQL 严格模式的 1406 变成 500。
 */
@Data
public class AdminDictItemCreateDTO {

    /** 显示名（必填；服务端 trim 后入库） */
    @NotBlank(message = "词条名称不能为空")
    @Size(max = 100, message = "词条名称最长 100 个字符")
    private String word;

    /** 取值/定义（可空；dish_category 组形如 {@code code:meat}） */
    @Size(max = 500, message = "词条定义最长 500 个字符")
    private String definition;

    /** 排序值（可空，缺省 0；读路径按该值 DESC、id ASC） */
    private Integer sortOrder;
}
