package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLog> {

    // ------------------------------------------------------------------
    // F-20.9 #3 审计查询页（GET /api/admin/v2/audit-logs，只读）新增的两条，既有语句零改动
    //（本接口在 Step 1~2 期间没有任何手写语句，只有继承来的 BaseMapper，所以下面两条是纯追加）。
    // 分页形态逐字照 OrderItemMapper 的 F-17 那一组（selectOrderItemsByDate + countOrderItemsByDate）：
    // 本工程没注册 MP 的内置分页拦截器，用那一套分页对象会静默返回全量且 total=0（RB 红线），
    // 故「页 + 独立 COUNT」两条手写 SQL，offset 由 Service 算。
    // 列别名一律写成驼峰且不含下划线（map-underscore-to-camel-case 对 Map 结果不生效，
    // 键名就是 SQL 里的列别名，见该组既有姿势）。
    // RD 最小暴露：本查询<b>不选 ip 列</b>（审计查询页展示不需要来源 IP），连查都没查；
    // detail 侧的明文手机号在写入时就已经 PhoneMask 过（AdminServiceImpl 的 RD 注释），出参不再兜第二层。
    // ORDER BY id DESC：BIGINT 自增主键，全序稳定，翻页不重复不漏（created_at 有同秒并列风险）。
    // DATE_FORMAT(created_at, ...) 同 OrderItemMapper.selectStatSummary 的 windowStart 那条理由：
    // 裸 DATETIME 进 Map 会拿到 java.sql.Timestamp，Jackson 默认按时间戳序列化，出参形状就不合契约了。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // F-20.16 Step 5a（收 G-06 DoD ④）：审计查询增四个可选<b>精确等值</b>筛选 actor/action/targetType/targetId。
    // D-9 只做精确等值、不做 LIKE/模糊——形态一律 {@code AND (#{x} IS NULL OR col = #{x})}：
    //   传值时按值等值过滤、传 null 时该条件整体短路（主会话已在 MySQL 8.0.36 用 PREPARE/EXECUTE 实测，
    //   logs/out/g06_pred.sql：actor='ADMIN_USER:2' 与 'admin_user:2' 都命中 9 行、不存在的值命中 0 行而非报错）。
    //   同一参数在同一句里出现两次（{@code #{x} IS NULL} 与 {@code col = #{x}}），MyBatis 各绑一次、值相同，是本形态的既定代价、非笔误。
    //   全静态 SQL、零 {@code <script>}、零 {@code @SelectProvider}（本仓零先例，不为四个筛选首创）；筛选值只走 {@code #{}} 绑定，列名一律常量。
    // ⚠ 等值匹配<b>不区分大小写</b>来自列 collation {@code utf8mb4_unicode_ci} 本身，<b>不是本层加的</b>（本层不做 lower/upper 归一）。
    //   与 OrderItemMapper:33 那条「拆两条方法」判例的差别：那条针对 Long/LocalDate 传 null 的 JdbcType OTHER 问题，
    //   本处四个参数全是 String，且 D-9 明令钉死本形态、不得重开。
    // ------------------------------------------------------------------

    /**
     * F-20.16 D-10：COUNT 与页两条 SQL 的 WHERE <b>必须逐字相同</b>（RB 纪律的延伸，G-05 已立判例）——
     * {@code total} 必须是「同一筛选条件下的总数」，否则页面会出现「共 0 条」却画着 20 行。
     * 这里用<b>同一个编译期常量串</b>作为唯一来源，两条 @Select 各自拼上它，从代码结构上杜绝「复制后手改一处」的漂移。
     * 四个筛选参数在两条语句里的 {@code #{}} 名字、列名、顺序逐字一致。
     */
    String AUDIT_FILTER_WHERE = "WHERE (#{actor} IS NULL OR actor = #{actor}) "
            + "AND (#{action} IS NULL OR action = #{action}) "
            + "AND (#{targetType} IS NULL OR target_type = #{targetType}) "
            + "AND (#{targetId} IS NULL OR target_id = #{targetId})";

    /**
     * 审计分页的一页（契约 F-20.9 #3 + F-20.16）：offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底。
     * 四个筛选参数全可 null（null = 该条件短路不生效），取值只经 {@code #{}} 绑定；WHERE 见 {@link #AUDIT_FILTER_WHERE}（与 countAuditLogs 逐字相同）。
     */
    @Select("SELECT id, actor, action, target_type AS targetType, target_id AS targetId, detail, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt "
            + "FROM admin_audit_log "
            + AUDIT_FILTER_WHERE + " "
            + "ORDER BY id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectAuditLogsPage(@Param("actor") String actor,
                                                  @Param("action") String action,
                                                  @Param("targetType") String targetType,
                                                  @Param("targetId") String targetId,
                                                  @Param("offset") long offset,
                                                  @Param("size") int size);

    /**
     * 审计总数（独立 COUNT）：WHERE 与 {@link #selectAuditLogsPage} 逐字相同（同 {@link #AUDIT_FILTER_WHERE}），仅去掉 SELECT 列表/ORDER BY/LIMIT。
     * 四个筛选参数与页那条签名顺序一致；null 传值时条件短路，语义同页。
     */
    @Select("SELECT COUNT(*) FROM admin_audit_log " + AUDIT_FILTER_WHERE)
    long countAuditLogs(@Param("actor") String actor,
                        @Param("action") String action,
                        @Param("targetType") String targetType,
                        @Param("targetId") String targetId);
}
