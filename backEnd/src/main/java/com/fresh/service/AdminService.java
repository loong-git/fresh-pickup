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
}
