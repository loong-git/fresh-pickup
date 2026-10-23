package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DictGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字典分组 mapper（F-108 字典本地化）。
 *
 * <p>读用法三处：{@code DictServiceImpl#getGroup} / {@code AdminServiceImpl#requireDictGroup}
 * 按主键 {@code selectById(code)} 判「分组是否存在 + enabled 是否停用」；
 * {@code DictServiceImpl#evict} 用它做组存在性校验（不存在 → BizException 400）；
 * {@code AdminServiceImpl#dictGroups} 用 {@link #selectAllOrdered()} 出运营端分组列表。
 * 写用法只有 {@code AdminServiceImpl#updateDictGroup} 的 {@code updateById}（片 2）。
 */
@Mapper
public interface DictGroupMapper extends BaseMapper<DictGroup> {

    /**
     * 全部分组，按展示顺序 {@code sort ASC, code ASC}（code 是唯一末列，同值排序稳定）。
     * 列别名与实体字段逐字对齐（本工程 map-underscore-to-camel-case=true，别名只是把口径写显式）。
     */
    @Select("SELECT code AS code, name AS name, upstream_category_id AS upstreamCategoryId, "
            + "sort AS sort, enabled AS enabled, create_time AS createTime, update_time AS updateTime "
            + "FROM dict_group ORDER BY sort ASC, code ASC")
    List<DictGroup> selectAllOrdered();
}
