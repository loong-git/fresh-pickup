package com.fresh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字典分组 dict_group（F-108 字典本地化，m23-migration.sql 建表）。
 *
 * <p>主键是分组代码 {@code code}（VARCHAR，非自增），与 {@code DictServiceImpl.GROUP_ID} 的键、
 * 前端 {@code front/api/dict.js} 的分组代码三方同名。{@code enabled=0} 表示该组停用：
 * {@code GET /api/dict} 按空返回且不回退上游（契约 §0.2/§0.3）。
 *
 * <p>{@code upstreamCategoryId} 只在「本地无词条且组启用」的回退分支里消费（上游已降级为同步源）；
 * 本片没有管理页，写入面为零，实体仅供读路径与 evict 存在性校验使用。
 */
@Data
@TableName("dict_group")
public class DictGroup {

    /** 分组代码（PK，外部赋值；与 GROUP_ID 的键一致） */
    @TableId(value = "code", type = IdType.INPUT)
    private String code;

    /** 中文名（front/api/dict.js 的 GROUP_NAME 同值） */
    private String name;

    /** 上游 categoryId（仅同步用；本地有数据时不走上游） */
    private Integer upstreamCategoryId;

    /** 运营端展示顺序预留位，本片不消费 */
    private Integer sort;

    /** 1 启用 / 0 停用（停用=该组按空返回，不回退上游） */
    private Integer enabled;

    /** DB DEFAULT CURRENT_TIMESTAMP 落值，应用侧不填 */
    private LocalDateTime createTime;

    /** DB ON UPDATE 维护，应用侧不填 */
    private LocalDateTime updateTime;
}
