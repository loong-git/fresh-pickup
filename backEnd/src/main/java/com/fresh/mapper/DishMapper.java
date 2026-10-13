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

    /** 支付成功累加销量（T-M4-03）：在支付成功点（PaymentSettlement#settle 的 updated>0 分支）按明细调用，未来 wx-notify 同点复用 */
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
    // 形态说明：这里用的是 d / fa / fp 的**单表与多表 JOIN**，不需要 OrderItemMapper 那条 STRAIGHT_JOIN——
    // 那条钉驱动表是为了躲 orders.idx_status 的全库 range（G-6/M1），本组两侧都是主键/idx_dish_id 等值，
    // 没有那种成本面。免费领两条的驱动表在 G-05 找茬里被改过（BE-G5-1，F-19.8 S-1）：契约 D-6 原文那句
    // `free_activity fa JOIN dish d ON d.id = fa.dish_id` 选错了驱动表（见下方 selectFreeActivities 注释），
    // 契约本体已由主会话就地订正为以 free_goods_pool 驱动，本组照订正后的原文逐字、不做「优化」。
    // 本期零参与数据（merchant 2/3 实测 seckill=0、免费领池 6 行全属 merchant 1 平台自营）→ 空集是正常出参，
    // 故 count 与 items 分两条方法：count 给真总数、select 给明细（D-6 的 LIMIT 20 是展示上限，不是分页）。
    // ------------------------------------------------------------------

    /**
     * 秒杀参与明细（契约 D-6 逐字列清单）：参与位就是 {@code dish.seckill}（TINYINT，非 is_seckill/seckill_flag）
     * 与 {@code dish.seckill_price}；{@code ORDER BY d.id ASC LIMIT 20}（D-6 原文的固定序 + 展示上限）。
     * <p>{@code soldCountTotal} 这个别名是 D-7 点名的改名列：{@code dish.sold_count} 是**历史累计**销量
     * （由 PaymentSettlement/退款链路增减，与看板窗口无关），把它当窗口值展示是这类看板最常见的说谎方式，
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
     * 免费领参与明细（BE-G5-1 驱动表订正，契约 D-6 已按 F-19.8 S-1 就地改文）：**从 free_goods_pool 出发**。
     * <p>为什么原来那条（{@code FROM free_activity fa JOIN dish d ON d.id = fa.dish_id}）是错的：
     * 免费领没有「商家报名表」，C 端真正的可领集合就是候选池 {@code free_goods_pool}——领取闸门
     * {@code FreeServiceImpl.java:145-147} 判的是「dishId 是否在池内」，下发列表 {@code :302-312} 也是按池取的。
     * {@code free_activity} 只有 1 行（单活动模型，主菜 dish_id=1），而池实测 6 行、其中 5 行不是活动主菜，
     * 所以从 fa 出发①平台自营少报 5 件；②更严重的是反向说谎：某商家的菜只在池里 → 本查询 0 行 →
     * {@code count=0} → 页面逐字念「本店商品未进入免费领池」，而 C 端此刻真能领他家的菜。
     * <p>归属仍然只来自 dish：{@code JOIN dish d ON d.id = fp.dish_id WHERE d.merchant_id = #{merchantId}}，
     * 一条池行只对应一个 dish、uk_activity_dish(activity_id, dish_id) 保证一活动一商品只一行，
     * 再 {@code JOIN free_activity fa ON fa.id = fp.activity_id}（主键等值）不放大行数——
     * 行数就是本店在池里的商品数，与 countFreeActivities 同 FROM/WHERE 天然对齐（U-2 的标题/行内矛盾由此起）。
     * <p>出参键变化：{@code inPool} 那列（原 EXISTS 子查询）删掉——驱动表就是池，池行恒「在池」，
     * 该列恒真即无信息量，而它当时还助长了「卡头说已参与 N 件、行内说不在候选池」的自相矛盾；
     * 改出 {@code activityStatus = fa.status}（online/offline），让页面如实说明这条池行挂在哪个状态的活动上。
     * <p>{@code dailyClaimed} 定形（BE-G5-2 跨日谎报）：裸 {@code fa.daily_claimed} 是**计数器所属日**的值，
     * 跨日不会自己归零——重置只在有人领取时惰性发生（{@code FreeActivityMapper.java:20-24} 那条条件 UPDATE），
     * C 端自己的口径是 {@code FreeServiceImpl.java:429-443}「daily_date 为 NULL/过去 → 视为满额重置」。
     * 页面标签写的是「今日已领」，SQL 就必须只在今日口径下给出份数，否则 00:00 之后仍然念出昨天的数。
     * <b>日期判定只能在 SQL 里做（{@code CURDATE()}），不许搬到 Java</b>：本期 D-3 红线是页面与服务侧都不自推日期，
     * 挪到 Java 就等于凭空多出第二个窗口口径（应用服务器时区与库时区可能差一天，D-3 纪律见
     * {@code MerchantOrderServiceImpl} 类注释的时间口径段）。
     * daily_date 为未来的脏数据/预置未开始同样落 ELSE 0，与领取侧「活动暂未开始」口径一致。
     * <p>ORDER BY fa.id ASC, fp.id ASC LIMIT 20：与秒杀块同一套「固定序 + 展示上限」（D-6 的 20 是展示上限，
     * 不是分页）；末尾的 fp.id 是稳定键——fa.id 在本库恒为同一个值（单活动模型），只按它排等于没排，
     * 池行之间没有任何可依赖的次序，两次刷新就会换序、移动端行会跳。
     */
    @Select("SELECT fa.id AS activityId, fp.dish_id AS dishId, d.name AS name, "
            + "fa.status AS activityStatus, "
            + "fa.daily_quota AS dailyQuota, "
            + "CASE WHEN fa.daily_date = CURDATE() THEN fa.daily_claimed ELSE 0 END AS dailyClaimed, "
            + "fa.threshold AS threshold "
            + "FROM free_goods_pool fp "
            + "JOIN dish d ON d.id = fp.dish_id "
            + "JOIN free_activity fa ON fa.id = fp.activity_id "
            + "WHERE d.merchant_id = #{merchantId} "
            + "ORDER BY fa.id ASC, fp.id ASC "
            + "LIMIT 20")
    List<Map<String, Object>> selectFreeActivities(@Param("merchantId") Long merchantId);

    /**
     * 免费领参与位**真总数**（D-6「count=真总数」）：FROM/JOIN/WHERE 与上方明细条逐字相同，
     * 仅去掉选择列与 ORDER BY/LIMIT——同一驱动表、同一归属谓词，卡头数与明细条数不可能再互相打脸
     * （U-2 那条「已参与 N 件 vs 行内不在候选池」的矛盾，根子在两条 SQL 的 FROM 不同）。
     */
    @Select("SELECT COUNT(*) FROM free_goods_pool fp "
            + "JOIN dish d ON d.id = fp.dish_id "
            + "JOIN free_activity fa ON fa.id = fp.activity_id "
            + "WHERE d.merchant_id = #{merchantId}")
    long countFreeActivities(@Param("merchantId") Long merchantId);

    // ------------------------------------------------------------------
    // F-20.18 Step 5b-1｜运营端商品列表（GET /api/admin/v2/dishes，只读）。纪律与上方 F-19 那一组、
    // 与 AdminAuditLogMapper 的 F-20.16 一组同构，此处只列新增要点：
    // 1) <b>没有归属条件</b>是本批的语义本身（F-20.17 事实 1：运营要的是全量，商家侧那些恒带
    //    d.merchant_id = #{merchantId} 的读路径绝不许拿过来凑数），所以 merchantId 在这里只是<b>筛选</b>，
    //    不是归属闸门；闸门在 Controller 那把 requireAdminJwtOnly。
    // 2) RB：页 + 独立 COUNT 两条手写 SQL；D-16：两条 WHERE 逐字相同 = 下面这一个编译期常量串。
    // 3) D-9/D-17：筛选一律精确等值（merchantId/onSale/category），零 LIKE、零 <script>、零 @SelectProvider；
    //    形态 (#{x} IS NULL OR col = #{x})，null 短路；同参数绑两次是既定代价。值只走 #{} 绑定、列名一律常量。
    // 4) D-18：create_time/update_time 一律 DATE_FORMAT 取串（createdAt 判例）；price/seckill_price 取原始 DECIMAL，
    //    由 Service 的 moneyText 统一两位小数字符串。on_sale/seckill 出 0/1 原值，映射文案归页面（D-18）。
    // 5) 本批不做商品关键字搜索（D-17 钉死「登记为边界，不许顺手加个搜索框」）——上方 selectIdsByFulltext 是
    //    <b>C 端</b>检索链路用的，与运营列表是两套口径，这里既不引它、也不在此开 LIKE。
    // ------------------------------------------------------------------

    /**
     * F-20.18 D-16：dish 面 COUNT 与页两条 SQL 的 WHERE <b>必须逐字相同</b>，用同一个编译期常量串作唯一来源。
     * 三个筛选全可 null（null = 该条件短路不生效）：{@code merchantId} 等值、{@code onSale} ∈ {0,1}
     * （值域在 Service 拦白名单，本层只等值）、{@code category} 等值（现库 category 是字典 code：
     * meat/vegetable/…，但库里<b>确有</b>一行非 ASCII 的脏值——见 Service 侧 category 形状闸门的注释）。
     */
    String DISH_FILTER_WHERE = "WHERE (#{merchantId} IS NULL OR d.merchant_id = #{merchantId}) "
            + "AND (#{onSale} IS NULL OR d.on_sale = #{onSale}) "
            + "AND (#{category} IS NULL OR d.category = #{category})";

    /**
     * 运营端商品列表的一页（契约 F-20.18）：逐键点名，<b>不选</b> {@code description}（长文本，列表页不展示、
     * 也不需要它才能改价/补库存/上下架）、不选 {@code tags}/{@code emoji}/{@code bg_color}（纯 C 端渲染装饰位）。
     * 这是 RD 最小暴露：出参是白名单，不是「整行减几个键」。
     * <p>{@code soldCount} 出的是 {@code dish.sold_count} 的<b>历史累计</b>销量（与看板窗口无关）——
     * 同 {@link #selectSeckillDishes} 里 D-7 点名的那条：别名不带 Total 是因为这一列在本页面就是「累计」语义，
     * 页面文案必须照写「累计销量」，不许当窗口值用。
     * <p>{@code ORDER BY d.id ASC}：与 selectSeckillDishes 同一套「固定序」（BIGINT 自增主键 = 全序稳定，
     * 翻页不重复不漏）。不按 update_time 排：那是 ON UPDATE 自动列，改价即跳页首，页面会逐字打脸「上一页还看到的行
     * 下一页又出现一次」。
     * <p>offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底。
     */
    @Select("SELECT d.id AS id, d.name AS name, d.price AS price, d.stock AS stock, "
            + "d.on_sale AS onSale, d.sold_count AS soldCount, d.category AS category, d.unit AS unit, "
            + "d.image AS image, d.good_rate AS goodRate, d.seckill AS seckill, "
            + "d.seckill_price AS seckillPrice, d.limit_buy AS limitBuy, d.merchant_id AS merchantId, "
            + "DATE_FORMAT(d.create_time, '%Y-%m-%d %H:%i:%s') AS createTime, "
            + "DATE_FORMAT(d.update_time, '%Y-%m-%d %H:%i:%s') AS updateTime "
            + "FROM dish d "
            + DISH_FILTER_WHERE + " "
            + "ORDER BY d.id ASC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectDishesPage(@Param("merchantId") Long merchantId,
                                               @Param("onSale") Integer onSale,
                                               @Param("category") String category,
                                               @Param("offset") long offset,
                                               @Param("size") int size);

    /**
     * 运营端商品列表的总数（独立 COUNT）：WHERE 与 {@link #selectDishesPage} 逐字相同
     * （同 {@link #DISH_FILTER_WHERE}），仅去掉 SELECT 列表/ORDER BY/LIMIT；三个筛选参数与页那条签名顺序一致。
     */
    @Select("SELECT COUNT(*) FROM dish d " + DISH_FILTER_WHERE)
    long countDishes(@Param("merchantId") Long merchantId,
                     @Param("onSale") Integer onSale,
                     @Param("category") String category);
}
