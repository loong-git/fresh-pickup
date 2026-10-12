package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 评价 mapper。既有零手写语句（只有继承来的 BaseMapper），本文件下方 F-20.18 那组是纯追加。
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    // ------------------------------------------------------------------
    // F-20.18 Step 5b-1｜运营端评价审核队列（GET /api/admin/v2/reviews，只读）。
    // 纪律同 AdminAuditLogMapper 的 F-20.16 一组：页 + 独立 COUNT（RB，本工程无 MP 分页插件，
    // 用 selectPage/Page<T> 会静默回全量且 total=0）、两条 WHERE 逐字相同（D-16，共用下面那个编译期常量串）、
    // 零 LIKE / 零 <script> / 零 @SelectProvider / 值只走 #{} 绑定 / 列名一律常量。
    // 列别名一律驼峰且不含下划线（MP 对 Map 结果不做下划线转驼峰，键名就是别名）。
    // D-18：create_time 一律 DATE_FORMAT(...,'%Y-%m-%d %H:%i:%s') 取串（裸 DATETIME 进 Map 会被 Jackson
    // 序列化成时间戳，createdAt 判例）。
    // ------------------------------------------------------------------

    /**
     * F-20.18 D-17 钉死的「SQL 侧映射」：UI 只有 {@code pending}（待人工复审）与 {@code visible}（展示中）
     * 两枚伪值，而库侧 {@code review.audit_status} 的真形态是 <b>NULL=展示中</b> 与 <b>'pending'=待复审</b> 两态，
     * 且 {@code AdminServiceImpl#auditReview} 的 reject 是 {@code deleteById} <b>物理删行</b>——
     * 库里根本不存在「已驳回」这一态，所以这里绝不为它设计筛选值（F-20.18 前置事实第 3 条）。
     * <p>映射写成三个 OR 分支，全部只比较常量与绑定值，零拼接：
     * <ul>
     *   <li>传 null → {@code #{auditStatus} IS NULL} 为真 → 该条件整体短路，不筛；</li>
     *   <li>传 'pending' → 第二分支生效 → {@code audit_status = 'pending'}；</li>
     *   <li>传 'visible' → 第三分支生效 → {@code audit_status IS NULL}（伪值到 NULL 的翻译就发生在这里，
     *       这也是 D-19 说「filters 回的是 UI 伪值本身、不回映射后的形态」的原因——映射是 SQL 侧的事，
     *       Java 侧从头到尾只握着那一枚伪值）。</li>
     * </ul>
     * 白名单外的值由 Service 先行 400；即使闸门失手，这一串也会回 0 行而不是报错或回全量。
     * {@code #{auditStatus}} 在同一句里绑三次（IS NULL 一次、两个分支各一次），值恒等，
     * 是 D-9 那形态的既定代价、非笔误。等值匹配不区分大小写来自列 collation {@code utf8mb4_unicode_ci}，
     * 不是本层加的（本层不做 lower/upper 归一，且值域闸门在 Service 是大小写敏感的白名单）。
     */
    String REVIEW_FILTER_WHERE = "WHERE (#{auditStatus} IS NULL "
            + "OR (#{auditStatus} = 'pending' AND r.audit_status = 'pending') "
            + "OR (#{auditStatus} = 'visible' AND r.audit_status IS NULL))";

    /**
     * 运营端评价列表的一页（契约 F-20.18 D-15）：逐键点名，<b>不选 {@code user_id}</b>
     * ——「不输出」写成「不查询」，泄露面才是零（同 StoreApplyMapper 的纪律）。
     * 不 JOIN dish：契约给的 review 真列清单里只有 {@code dish_id}，运营页要的是「这一条挂在哪个菜品」的<b>指针</b>，
     * 菜品名可由同批的 dishes 列表在前端对齐；把 JOIN 塞进来会让本条的 total 与页对齐关系多一张表的行数放大风险
     * （dish 行被删/改归属时 COUNT 与页的语义要重新论证），本批不开。
     * <p>{@code auditStatus} 出的是<b>库里原值</b>（'pending' 或 null），不是 UI 伪值 'visible'（D-18 出原值、
     * 页面自己映射文案那条纪律）；null 就是「展示中」，页面须如实写。
     * <p>{@code ORDER BY r.id DESC}：BIGINT 自增主键、全序稳定，最新评价在前（同 AdminAuditLogMapper 的判据）。
     * <p>现库 {@code review} <b>0 行</b>（F-20.18 前置事实）——空表是实况，本条与下方 COUNT 都要正常回
     * {@code {list:[], total:0}}，不是 500；门禁的 fixture 由主会话造，执行者不许为「让页面有数据」写库。
     * <p>offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底。
     */
    @Select("SELECT r.id AS id, r.dish_id AS dishId, r.rating AS rating, r.content AS content, "
            + "r.reviewer AS reviewer, r.audit_status AS auditStatus, "
            + "DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i:%s') AS createTime "
            + "FROM review r "
            + REVIEW_FILTER_WHERE + " "
            + "ORDER BY r.id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectReviewsPage(@Param("auditStatus") String auditStatus,
                                                @Param("offset") long offset,
                                                @Param("size") int size);

    /**
     * 运营端评价列表的总数（独立 COUNT）：WHERE 与 {@link #selectReviewsPage} 逐字相同
     * （同 {@link #REVIEW_FILTER_WHERE}），仅去掉 SELECT 列表/ORDER BY/LIMIT。
     */
    @Select("SELECT COUNT(*) FROM review r " + REVIEW_FILTER_WHERE)
    long countReviews(@Param("auditStatus") String auditStatus);
}
