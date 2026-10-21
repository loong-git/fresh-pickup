package com.fresh.service;

import com.fresh.entity.OpsLog;
import com.fresh.mapper.OpsLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ops_log 唯一写入口（F-18.8 第 3 条）：形态逐字照 {@code AdminServiceImpl#writeAudit :546-561} 的
 * **旁路双层 try-catch**——第一趟写完整 detail，任一层原因（1406 超长、字符集、列宽…）失败即退成
 * 最小 detail 重试一次，两趟都失败才 warn；**审计失败绝不影响主流程**（调用点已在事务内，
 * 这里吞掉异常等于「不因审计回滚核销/登记」）。
 *
 * <p>【为什么是独立一份，而不是各 Service 里各抄一段】契约 F-18.8 第 3 条的前提是「本期只有 1 个调用方」，
 * 实际本期有 2 个（partner 核销 verify_order + 商家缺货登记 register_shortage，见 F-18.8 第 5 条），
 * 各写一份 = 立刻造出「maskPhone 四份」那种债（契约点名的反面教材）。故收成这一个 Bean，
 * 由两个 ServiceImpl 各注入一次；差异只在参数，不在实现。已在回报的「契约疑点」登记。
 */
@Slf4j
@Component
public class OpsLogWriter {

    /** ops_log.detail 列宽（m17-migration.sql:83 VARCHAR(500)）：超长是 1406 的头号成因，写前先夹 */
    private static final int DETAIL_MAX = 500;

    /** 兜底 detail：完整行装不下时落这一行——「审计行必须存在」优先于「审计行有内容」（照母版 BE-2 硬要求） */
    private static final String MINIMAL_DETAIL = "{\"note\":\"audit detail truncated\"}";

    /**
     * action 白名单（BE-13，F-18.20 L 行）：submit_store_apply 由 role=user 的申请人写入后，
     * 「本表=商家/主理人现场动作」的口径已扩为「商家/主理人/申请人现场动作」（m17/init 列注释同批改）。
     * 白名单之外的 action 说明调用方在造第 6 种现场动作或误用本入口——只告警不落库，
     * 保住 ops_log 作为「现场动作过滤器」的定性能力（平台侧动作请走 admin_audit_log，D-5）。
     */
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "verify_order", "register_shortage", "confirm_arrival", "update_store", "submit_store_apply");

    @Autowired
    private OpsLogMapper opsLogMapper;

    /**
     * @param operatorUserId 操作者 userId（D-5：商家/主理人本人；调用方只传 ATTR_USER_ID，不收前端值）
     * @param action         verify_order / register_shortage / ...
     * @param targetId       目标对象ID（订单号或明细 id，字符串口径，VARCHAR(64)）
     * @param detail         无隐私摘要（**不许**放手机号/地址/取货码，RD 红线）；超长自动退成兜底行
     */
    public void write(Long operatorUserId, String action, String targetId, String detail) {
        // BE-13：白名单收口在唯一写入口——非白名单 action 只告警、不落库（绝不静默扩表语义）
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            log.warn("ops_log 非白名单 action 被拒绝落库（只告警）: action={}, targetId={}", action, targetId);
            return;
        }
        String safeDetail = detail != null && detail.length() <= DETAIL_MAX ? detail : MINIMAL_DETAIL;
        try {
            insert(operatorUserId, action, targetId, safeDetail);
            return;
        } catch (Exception e) {
            log.warn("ops_log 写入失败（旁路，不影响主事务），改用最小 detail 重试: action={}, targetId={}",
                    action, targetId, e);
        }
        try {
            insert(operatorUserId, action, targetId, MINIMAL_DETAIL);
        } catch (Exception e) {
            log.warn("ops_log 重试仍失败（旁路，不影响主事务）: action={}, targetId={}", action, targetId, e);
        }
    }

    /**
     * createdAt 不填：ops_log.created_at 由 DB {@code DEFAULT CURRENT_TIMESTAMP} 落值
     * （m17:84；实体里也是裸声明，照 AdminAuditLog:40，不走 MetaObjectHandler 的 fill）。
     */
    private void insert(Long operatorUserId, String action, String targetId, String detail) {
        OpsLog row = new OpsLog();
        row.setOperatorUserId(operatorUserId);
        row.setAction(action);
        row.setTargetId(targetId);
        row.setDetail(detail);
        opsLogMapper.insert(row);
    }
}
