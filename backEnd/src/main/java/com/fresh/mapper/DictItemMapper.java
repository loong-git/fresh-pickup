package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DictItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字典词条 mapper（F-108 字典本地化）。
 *
 * <p>读路径唯一入口 {@link #selectByGroup(String)}：排序直接钉在 SQL 上（契约 §0.3
 * 「按 sort_order DESC, id ASC」），走 {@code idx_group_sort}；Service 侧另做一次同口径内存排序，
 * 保证即使 SQL 被改坏或 DB 排序规则变化，出参顺序也不漂。
 */
@Mapper
public interface DictItemMapper extends BaseMapper<DictItem> {

    /**
     * 某分组全部词条：{@code sort_order DESC, id ASC}（id 是唯一末列，同值排序稳定）。
     * 别名与实体字段逐字对齐（本工程 map-underscore-to-camel-case=true，别名只是把口径写显式）。
     */
    @Select("SELECT id AS id, group_code AS groupCode, word AS word, definition AS definition, "
            + "sort_order AS sortOrder "
            + "FROM dict_item WHERE group_code = #{groupCode} "
            + "ORDER BY sort_order DESC, id ASC")
    List<DictItem> selectByGroup(@Param("groupCode") String groupCode);
}
