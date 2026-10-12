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

    // ------------------------------------------------------------------
    // F-19/G-05 Step 1 商家端「营销参与态」只读两端点（契约 F-19.4 #2：四条追加在本接口，不起新文件）。
    // 与 OrderItemMapper 的只读组同一套归属纪律：每一条都带 `d.merchant_id = #{merchantId}`（D-2/D-6），
    // 值只来自 AuthInterceptor.ATTR_MERCHANT_ID；不收任何归属入参、没有任何绕过本组的读路径。
    // 形态说明：这里用的是 d / fa 的**单表与两表 JOIN**，不需要 OrderItemMapper 那条 STRAIGHT_JOIN——
    // 那条钉驱动表是为了躲 orders.idx_status 的全库 range（G-6/M1），本组两侧都是主键/idx_dish_id 等值，
    // 契约 D-6 原文写的也是 `free_activity fa JOIN dish d ON d.id = fa.dish_id`，照契约逐字、不做「优化」。
    // 本期零参与数据（merchant 2/3 实测 seckill=0、free_activity 全属 merchant 1）→ 空集是正常出参，
    // 故 count 与 items 分两条方法：count 给真总数、select 给明细（D-6 的 LIMIT 20 是展示上限，不是分页）。
    // ------------------------------------------------------------------

    /**
     * 秒杀参与明细（契约 D-6 逐字列清单）：参与位就是 {@code dish.seckill}（TINYINT，非 is_seckill/seckill_flag）
     * 与 {@code dish.seckill_price}；{@code ORDER BY d.id ASC LIMIT 20}（D-6 原文的固定序 + 展示上限）。
     * <p>{@code soldCountTotal} 这个别名是 D-7 点名的改名列：{@code dish.sold_count} 是**历史累计**销量
     * （由 MockPayService/退款链路增减，与看板窗口无关），把它当窗口值展示是这类看板最常见的说谎方式，
     * 所以键名里直接写死 Total，Service 侧也不再对它做任何窗口解释。
     * <p>不 JOIN order_item：本期只出「平台把我哪些菜设成了秒杀」这一事实，销量口径见上一段。
     */
    @Select("SELECT d.id AS dishId, d.name AS name, d.price AS price, d.seckill_price AS seckillPrice, "
            + "d.limit_buy AS limitBuy, d.on_sale AS onSale, d.sold_count AS soldCountTotal "
            + "FROM dish d "
            + "WHERE d.merchant_id = #{merchantId} "
            + "AND d.seckill = 1 "
            + "ORDER BY d.id ASC "
            + "LIMIT 20")
    List<Map<String, Object>> selectSeckillDishes(@Param("merchantId") Long merchantId);

    /** 秒杀参与位**真总数**（D-6「count=真总数」）：与上方明细条 WHERE 逐字相同，仅去掉 ORDER BY/LIMIT */
    @Select("SELECT COUNT(*) FROM dish d "
            + "WHERE d.merchant_id = #{merchantId} "
            + "AND d.seckill = 1")
    long countSeckillDishes(@Param("merchantId") Long merchantId);

    /**
     * 免费领参与明细（契约 D-6 第二块，JOIN 与列清单逐字）：免费领没有「商家报名表」，参与位就是
     * {@code free_activity.dish_id} 落到自家菜品上（故必须 JOIN dish 拿归属，这是唯一的归属来源）；
     * 活动自身的额度/计数器（daily_quota/daily_date/daily_claimed/threshold/status）都在 free_activity 一侧，
     * 出参按契约键名给，不取 daily_date（窗口口径与看板无关，且契约未列该键）。
     * <p>{@code inPool} = D-6 的 EXISTS 子查询原文（该 dish 是否也在 free_goods_pool 候选池里，
     * 池是 M8 的「今日可领候选」层）。EXISTS 回 1/0，Service 侧按本仓 tinyint/flag 惯例出数字不出布尔。
     * <p>ORDER BY fa.id ASC LIMIT 20：与秒杀块同一套「固定序 + 展示上限」，单活动模型现库恒 1 行，
     * 上限只是防未来多活动撑爆移动端。
     */
    @Select("SELECT fa.id AS activityId, fa.dish_id AS dishId, d.name AS name, fa.status AS status, "
            + "fa.daily_quota AS dailyQuota, fa.daily_claimed AS dailyClaimed, fa.threshold AS threshold, "
            + "EXISTS(SELECT 1 FROM free_goods_pool fp WHERE fp.activity_id = fa.id AND fp.dish_id = fa.dish_id) AS inPool "
            + "FROM free_activity fa JOIN dish d ON d.id = fa.dish_id "
            + "WHERE d.merchant_id = #{merchantId} "
            + "ORDER BY fa.id ASC "
            + "LIMIT 20")
    List<Map<String, Object>> selectFreeActivities(@Param("merchantId") Long merchantId);

    /** 免费领参与位**真总数**：与上方明细条同 FROM/WHERE，仅去掉 ORDER BY/LIMIT 与选择列 */
    @Select("SELECT COUNT(*) FROM free_activity fa JOIN dish d ON d.id = fa.dish_id "
            + "WHERE d.merchant_id = #{merchantId}")
    long countFreeActivities(@Param("merchantId") Long merchantId);
}
