package com.fresh.service;

import java.util.Map;

/**
 * 运营端 v2 只读服务（F-20.9 #2/#3，G-06 Step 3）：大盘四指标 + 审计分页，<b>全部只读</b>。
 *
 * <p>闸门不在本类：{@code /api/admin/v2/**} 只认运营 JWT（D-2「防止新代码更弱」），
 * 由 {@code AdminV2Controller} 调 {@code AdminController#requireAdminJwtOnly} 收口，
 * 静态密钥在这两个端点上是一律 401。本类因此<b>没有任何「调用者是谁」的入参</b>——
 * 只读动作不写审计（RF 只约束写动作），也就不需要 actor。
 *
 * <p>旧 5 个写端点的业务口径仍在 {@link AdminService}，本期不在 v2 重复造写接口（D-7）。
 */
public interface AdminV2Service {

    /**
     * 只读大盘四指标（{@code GET /api/admin/v2/overview}）：单量 / 总金额 / 待自提单数 / 已收款金额，
     * 口径逐字照 {@code docs/日常运营SQL手册.md:111-115} 的「今日经营概况」那条 SELECT（订单口径，
     * 与 G-05 商家看板的券前明细口径分列、互不换算，D-7）。
     *
     * @param date {@code YYYY-MM-DD}；null/空 = 今日（今日在 <b>SQL 侧</b>用 CURDATE() 解析，不在 Java 取时钟）；
     *             形状不符或形状符但不是真日期（2026-02-30）→ 400
     * @return 手工白名单 Map {@code {orderCount, totalAmount, pendingPickupCount, paidAmount}}；
     *         金额为两位小数字符串，空数据日四个 0 值（{@code 0}/{@code "0.00"}），绝不出 null
     */
    Map<String, Object> overview(String date);

    /**
     * 审计日志分页（{@code GET /api/admin/v2/audit-logs}）：{@code admin_audit_log}，{@code ORDER BY id DESC}。
     * 手写「页 + 独立 COUNT」两条 SQL（RB：本工程没装 MP 分页插件）。
     *
     * @param pageNum  缺省 1；<b>下限 1</b>，越界或脏值 → 400（姿势同 G-05 的 days「越界直接 400，不夹到边界」）
     * @param pageSize 缺省 20；值域 [1,100]，越界 → 400
     * @return {@code {list:[{id, actor, action, targetType, targetId, detail, createdAt}], total, pageNum, pageSize}}；
     *         <b>不含 ip</b>（RD 最小暴露）；空表 → {@code list:[]} 且 {@code total:0}，不是 500
     */
    Map<String, Object> auditLogs(Integer pageNum, Integer pageSize);
}
