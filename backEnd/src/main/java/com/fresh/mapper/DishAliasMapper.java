package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DishAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 搜索别名 mapper（F-110 运营端维护）。
 *
 * <p>本接口<b>不含</b> C 端搜索语句：别名命中仍走 {@code DishMapper#selectDishIdsByAlias}
 * （F-110 红线：搜索语义不变）。这里只有运营列表与唯一键预查两条只读语句；
 * 写路径走继承的 BaseMapper（insert / updateById / deleteById），不新增手写 SQL。
 */
@Mapper
public interface DishAliasMapper extends BaseMapper<DishAlias> {

    /**
     * 运营端别名全量列表：按 {@code alias ASC}（契约 §0.2 的固定序）。
     * 别名与实体字段逐字对齐（本工程 map-underscore-to-camel-case=true，别名只是把口径写显式）。
     */
    @Select("SELECT id AS id, alias AS alias, dish_id AS dishId "
            + "FROM dish_alias ORDER BY alias ASC")
    List<DishAlias> selectAllOrdered();

    /**
     * 按别名预查（uk_alias 保证至多一行）：用于新增/改名前的 400「该别名已存在」判定。
     * 唯一键仍是并发兜底（两个请求同时通过预查时由 DB 报 1062，Service 再转同一句 400）。
     */
    @Select("SELECT id AS id, alias AS alias, dish_id AS dishId "
            + "FROM dish_alias WHERE alias = #{alias} LIMIT 1")
    DishAlias selectByAlias(@Param("alias") String alias);
}
