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

    /** 审计分页的一页（契约 F-20.9 #3）：offset/size 由 Service 算好并校验过界（越界脏值 400、不夹到边界），本条不做任何兜底 */
    @Select("SELECT id, actor, action, target_type AS targetType, target_id AS targetId, detail, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt "
            + "FROM admin_audit_log "
            + "ORDER BY id DESC "
            + "LIMIT #{offset}, #{size}")
    List<Map<String, Object>> selectAuditLogsPage(@Param("offset") long offset, @Param("size") int size);

    /** 审计总数（独立 COUNT，WHERE 与上方一条逐字相同——本表无过滤条件，故两条都只有表名） */
    @Select("SELECT COUNT(*) FROM admin_audit_log")
    long countAuditLogs();
}
