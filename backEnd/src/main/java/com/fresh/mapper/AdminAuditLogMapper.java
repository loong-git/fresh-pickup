package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLog> {

    // ------------------------------------------------------------------
    // F-50（D-50-2）：本接口是 action 字面量 {@code pay.notify_reject} 在 Java 侧的<b>唯一真值出口</b>。
    // 接口常量天然 public static final，写侧（AdminServiceImpl#recordNotifyReject）与读侧
    //（AdminV2ServiceImpl#overview 的当日拒收计数）都引用这一枚，不再各写一遍字符串——
    // 两处各写一遍时，将来改名必有一处静默失配，而这条链的用途恰恰是「出事时看得见」：
    // 读侧失配的结果是<b>大盘那一格永远显示 0</b>，比没有这一格更坏。
    // 值与语义自 F-44（f4cd15b）起一字未变，本次只搬「写在哪里」。
    // ------------------------------------------------------------------

    /** 微信回调被拒时落进 {@code admin_audit_log.action} 的那个动作名（唯一字面量，见上方注释） */
    String ACTION_PAY_NOTIFY_REJECT = "pay.notify_reject";

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

    // ------------------------------------------------------------------
    // F-50（D-50-1/D-50-3/D-50-4）：当日「微信支付回调拒收」计数 + 最后一次的时间，供只读大盘那一格用。
    // <b>不新增端点</b>：这条由 AdminV2ServiceImpl#overview 在同一次请求里调，结果并进既有 /overview 的 data。
    // <b>一条 SQL 两列，不做两次往返</b>（D-50-4）：COUNT 与 MAX(created_at) 同一句、同一 WHERE，
    // 于是「有几个」与「最后一次是几点」必然出自同一个窗口，不会各说各话。
    // <b>窗口口径与大盘其余四键逐字同源</b>（D-50-3）：同一个 #{day}、同一对
    //   {@code IFNULL(#{day}, CURDATE())} / {@code + INTERVAL 1 DAY} 半开区间（原样照 OrderMapper
    //   #selectOverviewDaily 那两条边界），只是时间列换成本表的 created_at（F-44 的写入点用的就是这一列）。
    //   写成区间而不是 DATE(created_at)=#{day} 是为了不废掉 idx_created/idx_action（a6648c4 刚给这两列建好索引，
    //   列上套函数就等于白建）；day 为 null 时两端都退回 CURDATE()，「今日」继续由 SQL 侧裁决，
    //   Java 侧不调 LocalDate.now()（同 F-20.9 #2 / D-3 的时间窗纪律）。
    // lastAtText 用 DATE_FORMAT(...,'%H:%i')：一是裸 DATETIME 进 Map 会拿到 java.sql.Timestamp、
    //   Jackson 按时间戳序列化，出参形状就不合契约（本文件上方 createdAt 判例）；
    //   二是这一列给页面的只是「最近一次几点」，格式化在后端一次做定，前端只展示、不许自己换算时区（D-50-7）。
    //   当日没有拒收时 MAX(created_at) 是 NULL，DATE_FORMAT(NULL,...) 仍是 NULL，MyBatis 不写这个键，
    //   由 Service 显式 put null（F-42b 信封纪律：缺键与显式 null 对客户端是两种语义）。
    // action 走 #{action} 绑定（值来自 ACTION_PAY_NOTIFY_REJECT 那一枚常量），零拼接面、零 <script>。
    // ------------------------------------------------------------------

    /**
     * 当日窗口内回调拒收的条数与最后一次的时间（一行恒回，无 GROUP BY 的聚合）。
     *
     * @param action 要数的动作名；本仓的调用方一律传 {@link #ACTION_PAY_NOTIFY_REJECT}
     * @param day    自然日；null = 今日（SQL 侧 {@code IFNULL} 退回 {@code CURDATE()}）
     * @return {@code {rejectCount, lastAtText}}；{@code lastAtText} 在「当日无拒收」时为 null 键（由 Service 补 put）
     */
    @Select("SELECT COUNT(*) AS rejectCount, DATE_FORMAT(MAX(created_at), '%H:%i') AS lastAtText "
            + "FROM admin_audit_log "
            + "WHERE action = #{action} "
            + "AND created_at >= IFNULL(#{day}, CURDATE()) "
            + "AND created_at < IFNULL(#{day}, CURDATE()) + INTERVAL 1 DAY")
    Map<String, Object> selectNotifyRejectSummary(@Param("action") String action,
                                                  @Param("day") LocalDate day);
}
