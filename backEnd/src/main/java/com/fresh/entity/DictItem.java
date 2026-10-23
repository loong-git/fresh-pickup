package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典词条 dict_item（F-108 字典本地化，m23-migration.sql 建表）。
 *
 * <p>唯一键 {@code uk_group_word(group_code, word)} 是 m23 回填 {@code INSERT IGNORE} 的幂等依据；
 * 读路径按 {@code sort_order DESC, id ASC} 出参（契约 §0.3），出参形状在 Service 侧映射为
 * {@code {word, definition, sortOrder}} 三键，与上游分支逐字同形。
 *
 * <p>本片无写入端点（管理页与引用保护属片 2），实体只承担读路径载体的角色。
 */
@Data
@TableName("dict_item")
public class DictItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分组代码（逻辑外键 dict_group.code） */
    private String groupCode;

    /** 显示名（如 肉蛋水产） */
    private String word;

    /** 取值/定义（券组是 JSON 字符串，如 {"threshold":19,"amount":4,"state":"available"}） */
    private String definition;

    /** 排序值（读路径按本列 DESC、id ASC 排序） */
    private Integer sortOrder;
}
