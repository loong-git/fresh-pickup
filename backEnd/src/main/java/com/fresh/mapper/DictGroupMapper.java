package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DictGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典分组 mapper（F-108 字典本地化）。
 *
 * <p>本片只有两条读用法：{@code DictServiceImpl#getGroup} 按主键 {@code selectById(code)} 判
 * 「分组是否存在 + enabled 是否停用」，{@code DictServiceImpl#evict} 用它做组存在性校验
 * （不存在 → BizException 400）。写路径属片 2 的管理页，本片不提供。
 */
@Mapper
public interface DictGroupMapper extends BaseMapper<DictGroup> {
}
