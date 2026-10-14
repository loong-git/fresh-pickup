package com.fresh.service;

import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;

/**
 * 管理端薄版服务（T-M3-09）：鉴权（F-20 Step 2 起为双轨）在 Controller 层完成，此处只做业务。
 * F-12/G-01 M-04 补课：本类全部写操作随做随写 admin_audit_log（R6）。
 *
 * <p><b>F-20.9 #1（收 R-c「审计 actor 失真」）：五个写方法各追加一个 {@code String actor} 形参</b>，
 * 由 {@code AdminController} 依据双轨闸门本次走的是哪一条腿来决定传哪一枚：
 * <ul>
 *   <li>静态密钥命中 → 传 {@code null}，Service 侧沿用既有口径落 {@code admin:<key 摘要 8>}，
 *       <b>与 Step 3 之前逐字节相同</b>（旧 curl 脚本 / SQL 手册零感知，本仓也不为此再复制一份 SHA-256 实现——
 *       摘要算法只有 {@code AdminServiceImpl#adminKeyDigest} 一处真源）；</li>
 *   <li>运营 JWT 命中 → 传 {@code admin_user:<id>}（id 来自闸门查库确认过 status=active 的那枚 subject）。</li>
 * </ul>
 * actor 一律是「摘要或 id」，<b>绝不</b>是密钥明文、口令、token（RD 红线：不入代码/注释/日志/异常消息/detail）。
 * <p>本参数<b>不是</b>从 ThreadLocal 或 request 属性里偷偷取的：BE-20-1 起 AuthInterceptor 对
 * {@code /api/admin/} 前缀提前放行且<b>不注入</b>任何用户属性，那条隐式通道在本面根本不存在，
 * 取值只有「Controller 显式传」这一条路（传 null 即静态密钥轨，语义见上）。
 */
public interface AdminService {

    /**
     * 订单状态更新：仅允许 pending_pickup → completed（核销）/ cancelled（取消，回补库存）
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateOrderStatus(String orderId, String status, String actor);

    /**
     * 商品部分更新：price / stock / onSale 仅更新传入字段
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void updateDish(Long dishId, AdminDishUpdateDTO dto, String actor);

    /**
     * 评价审核（T-M4-05 收口）：命中软词入库 pending 默认不展示，此处人工放行/驳回。
     * approve → audit_status 置 NULL 恢复展示；reject → 删除评价记录。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditReview(Long reviewId, String action, String actor);

    /**
     * 平台代开通商家（F-12/M-05 一期，决策点②）：按 phone 查已注册 user，同事务
     * INSERT merchant + INSERT merchant_profile(approved) + UPDATE user role/merchant_id，并写审计。
     *
     * @param actor 本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void createMerchant(AdminMerchantCreateDTO dto, String actor);

    /**
     * 入驻申请审核（F-18.12 第 3 条，Step 4b）：action 白名单 approve/reject（复用本类 ALLOWED_AUDIT_ACTIONS）。
     * <ul>
     *   <li>approve：同事务三写——条件 UPDATE store_apply 置 approved（+audited_at）→ INSERT store
     *       （owner_user_id=申请人，这是门店进 {@code GET /api/stores} 的唯一发布通道，D-12/A2.1）→ admin_audit_log；</li>
     *   <li>reject：只置 rejected + audit_remark（申请主体保留，申请人可改后重新提交=新行，D-21）→ admin_audit_log；</li>
     *   <li>幂等：已是目标态再审 → 直接成功，<b>不重复 INSERT store</b>、不写第二行审计；</li>
     *   <li>申请不存在 → 404；已审完再翻转结论 → 400。</li>
     * </ul>
     *
     * @param remark 审核意见（可空，落 audit_remark VARCHAR(255)；超长 400）
     * @param actor  本次调用者身份，见类注释（null = 静态密钥轨）
     */
    void auditStoreApply(Long applyId, String action, String remark, String actor);

    /**
     * 微信支付回调拒收留痕（F-44）：把「哪一单、因为哪一条原因、回了微信什么码」落进
     * {@code admin_audit_log}，让运营在 front-admin 审计页查得到（此前只有服务器日志）。
     *
     * <p><b>为什么落在 admin_audit_log 而不是 ops_log</b>：{@code init.sql:392} 的 ops_log 表注释明写
     * 「本表只收商家/主理人/申请人现场动作——平台侧动作一律走 admin_audit_log」。回调裁定是平台侧事件，
     * 按本仓既有边界就归本表，<b>不新建表、不加迁移</b>。
     *
     * <p><b>唯一调用点</b>：{@code PayController#wxNotify} 的那一处
     * {@code catch (WxPayNotifyRejectException)}——判定层 18 条 {@code reject(...)} 抛出点一条都不许自己写审计
     * （两处写 = 两套口径）。成功结算路径<b>不调本方法</b>，否则审计表被正常流量灌满、功能立刻失去可用性。
     *
     * <p><b>本方法是旁路</b>：写失败绝不影响调用方对微信的应答（那枚 HTTP 状态码是「已扣款订单不丢单」的
     * 唯一保障）。落库动作复用本面既有那唯一一份审计写入核心 {@code AdminServiceImpl#writeAudit}，
     * 不开第二个 writer。
     *
     * @param code       稳定 ASCII 拒收码（{@code WxPayNotifyRejectException.RejectCode#name()}，
     *                   与拒收原因一一对应；null/空一律归一成 {@code UNSPECIFIED}，不留空串）
     * @param httpStatus 实际回给微信的 HTTP 状态码（只作留痕，不参与任何判定）
     * @param orderId    路径上的订单号，落 {@code target_id}；null/空白落占位符 {@code '-'}
     *                   （该列 {@code VARCHAR(64) NOT NULL}，空串行插不进去）；
     *                   超过列宽则截到 64 字符——「审计行必须存在」优先于「审计行有内容」（BE-2 既有裁定）
     * @param ip         来源 IP，口径与限流闸门一致（{@code rate-limit.trust-proxy}），null 即列可空
     */
    void recordNotifyReject(String code, int httpStatus, String orderId, String ip);
}
