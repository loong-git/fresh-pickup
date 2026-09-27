package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface DishMapper extends BaseMapper<Dish> {

    /**
     * 条件 UPDATE 原子扣减（T-M1-05）：stock>=quantity 不满足时影响行数为 0，
     * 并发下由行锁串行化判定，杜绝超卖；影响行数=0 → 上层抛"库存不足"并回滚事务。
     */
    @Update("UPDATE dish SET stock = stock - #{quantity} WHERE id = #{dishId} AND stock >= #{quantity}")
    int decreaseStock(@Param("dishId") Long dishId, @Param("quantity") Integer quantity);

    /** 取消订单回补库存（T-M1-07） */
    @Update("UPDATE dish SET stock = stock + #{quantity} WHERE id = #{dishId}")
    int increaseStock(@Param("dishId") Long dishId, @Param("quantity") Integer quantity);

    /** 支付成功累加销量（T-M4-03）：在支付成功点（MockPayService updated>0 分支）按明细调用，未来 wx-notify 同点复用 */
    @Update("UPDATE dish SET sold_count = sold_count + #{quantity} WHERE id = #{dishId}")
    int increaseSoldCount(@Param("dishId") Long dishId, @Param("quantity") Integer quantity);

    /** 取消已支付单回退销量（T-M4-03）：pay_status 1→2 置退款成功后按明细调用，未支付取消不调；GREATEST 兜底不为负 */
    @Update("UPDATE dish SET sold_count = GREATEST(sold_count - #{quantity}, 0) WHERE id = #{dishId}")
    int decrementSoldCount(@Param("dishId") Long dishId, @Param("quantity") Integer quantity);

    /**
     * 按真实评价聚合回写好评率（好评口径：rating>=4 占比）。聚合在派生表内完成，UPDATE 仅命中该 dish 行；
     * 待审评价（audit_status=pending）不计入，与评价列表展示口径一致——IS NULL 必须显式判断。
     * 无有效评价时派生表无行、JOIN 不命中，影响行数为 0，good_rate 保留 init.sql 种子公式兜底值。
     */
    @Update("UPDATE dish d JOIN ("
            + "SELECT dish_id, SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS rate"
            + " FROM review WHERE dish_id = #{dishId} AND (audit_status IS NULL OR audit_status <> 'pending')"
            + " GROUP BY dish_id) r ON r.dish_id = d.id"
            + " SET d.good_rate = CONCAT(CAST(ROUND(r.rate, 1) AS DECIMAL(4,1)), '%')"
            + " WHERE d.id = #{dishId}")
    int refreshGoodRate(@Param("dishId") Long dishId);

    /**
     * F-03.2 ≥2 字关键词：三列正文全文检索（m10 索引 ft_dish_search，ngram 解析器按二元切分），
     * BOOLEAN MODE 取命中行 id 与相关性分（SELECT 与 WHERE 重复 MATCH 仅为取分，同一表达式可命中索引）。
     * 约束：MATCH 列顺序必须与索引定义 (name, description, tags) 完全一致，否则用不上该 FULLTEXT 索引；
     * #{kw} 参数化（B.3 规约：自写 SQL 一律 #{}，禁 ${} 拼接）；BOOLEAN MODE 下多个词为 OR 语义，
     * 三列检索的召回 ⊇ 原 LIKE(name) 单列。
     */
    @Select("SELECT id, MATCH(name, description, tags) AGAINST(#{kw} IN BOOLEAN MODE) AS score"
            + " FROM dish"
            + " WHERE MATCH(name, description, tags) AGAINST(#{kw} IN BOOLEAN MODE)")
    List<Map<String, Object>> selectIdsByFulltext(@Param("kw") String kw);

    /**
     * F-03.2 别名命中：dish_alias.alias 精确等值（uk_alias 保证一别名仅指向一商品），
     * 返回指向的 dish_id 集合，由服务层与 MATCH 命中并集去重后以 IN 取行（F-03.2「IN 并入」）。
     */
    @Select("SELECT dish_id FROM dish_alias WHERE alias = #{alias}")
    List<Long> selectDishIdsByAlias(@Param("alias") String alias);
}
