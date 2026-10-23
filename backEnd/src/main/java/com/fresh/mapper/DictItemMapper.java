package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.DictItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 字典词条 mapper（F-108 字典本地化）。
 *
 * <p>读路径唯一入口 {@link #selectByGroup(String)}：排序直接钉在 SQL 上（契约 §0.3
 * 「按 sort_order DESC, id ASC」），走 {@code idx_group_sort}；Service 侧另做一次同口径内存排序，
 * 保证即使 SQL 被改坏或 DB 排序规则变化，出参顺序也不漂。
 *
 * <p>片 2 追加 {@link #countByGroup()} 供运营端分组列表的 {@code itemCount}；写路径走继承的
 * BaseMapper（{@code insert} / {@code updateById} / {@code deleteById}），不新增手写语句。
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

    /**
     * 每组词条数（运营端分组列表的 {@code itemCount}）：一次 GROUP BY 取全部分组，不按组循环查。
     * 返回键名是列别名 {@code groupCode} / {@code itemCount}（Map 结果不走驼峰自动映射）。
     * 没有词条的组不会出现在结果里，由 Service 侧按 0 兜底。
     */
    @Select("SELECT group_code AS groupCode, COUNT(*) AS itemCount "
            + "FROM dict_item GROUP BY group_code")
    List<Map<String, Object>> countByGroup();

    /**
     * 上游同步的唯一写语句（F-108 §0.8）：{@code INSERT IGNORE} 靠 {@code uk_group_word(group_code, word)}
     * 判重——本地没有的行插入并返回 1；本地已有（含运营改过的 definition/sortOrder）的行被唯一键挡下、
     * 返回 0 且<b>一行都不动</b>。刻意不写 {@code ON DUPLICATE KEY UPDATE}：B 语义「只增不删」的硬约束
     * 就是这条语句里没有任何 UPDATE 分支。
     *
     * @return 1 = 本次真的插入了；0 = 撞 {@code uk_group_word} 被跳过
     */
    @Insert("INSERT IGNORE INTO dict_item (group_code, word, definition, sort_order) "
            + "VALUES (#{groupCode}, #{word}, #{definition}, #{sortOrder})")
    int insertIgnore(DictItem item);
}
