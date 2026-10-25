package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.dto.AdminAliasCreateDTO;
import com.fresh.dto.AdminAliasUpdateDTO;
import com.fresh.dto.AdminArrivalDepartDTO;
import com.fresh.dto.AdminCouponTemplateCreateDTO;
import com.fresh.dto.AdminCouponTemplateUpdateDTO;
import com.fresh.dto.AdminDictGroupUpdateDTO;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
import com.fresh.dto.AdminDishAuditDTO;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminFreeActivityUpdateDTO;
import com.fresh.dto.AdminFreePoolUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;
import com.fresh.dto.AdminOrderStatusDTO;
import com.fresh.dto.AdminReviewAuditDTO;
import com.fresh.dto.AdminSeckillUpdateDTO;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.dto.AdminStoreUpdateDTO;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminService;
import com.fresh.service.DictService;
import com.fresh.service.PartnerWalletService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 管理端薄版（T-M3-09）：
 * - 鉴权（F-20/G-06 Step 2 起为<b>双轨</b>，D-2）：{@code X-Admin-Key} 命中静态密钥<b>或</b>
 *   {@code Authorization: Bearer} 带一枚 typ=admin 且查库 admin_user 存在并 active 的运营 JWT；
 *   key 读环境变量 ADMIN_KEY（@Value 占位符默认 admin-dev-key，生产由环境变量覆盖）；
 *   两条都不中 → 统一信封 401（HTTP 恒 200，码经 body.code 携带），文案与从前<b>逐字节相同</b>
 *   （RE 红线：运维 curl 脚本 / SQL 手册零感知）。
 *   AuthInterceptor 权限矩阵不含 /api/admin/**（唯一例外是匿名放行的 POST /api/admin/auth/login），
 *   此处自行校验，各端点方法第一行调 {@link #checkKeyOrOperator}，第二轨的 JWT 解析见 {@link #activeAdminId}。
 * - 审计可追溯（F-20.9 #1，收 R-c）：闸门除了「放不放行」还要回「本次是哪条轨放的行」，
 *   该结果包在 {@link AdminGrant} 里，其 {@code actor} 由每个端点显式传给 AdminService 写 admin_audit_log
 *   ——<b>静态密钥轨 = null（Service 侧沿用既有 admin:&lt;key 摘要 8&gt;）；运营 JWT 轨 = admin_user:&lt;id&gt;</b>。
 *   不走 ThreadLocal / request 属性（admin 面压根不注入用户属性，见 BE-20-1），也不把密钥明文/口令/token 拼进去。
 * - v2 只读面（Step 3）：{@code AdminV2Controller} 挂 /api/admin/v2/**，复用本包的 {@link #requireAdminJwtOnly}。
 * - 运营端点：订单核销/取消、商品改价/补库存/上下架（部分更新）、评价审核（T-M4-05 收口：
 *   命中软词 pending 的评价人工过审/驳回，否则列表默认不展示、永久不可见）、
 *   平台代开通商家（F-12/M-05 一期）、入驻申请审核（F-18.12 第 3 条，第五个端点）、
 *   发车只读预览与发车（F-63：arrival_batch 唯一的运行期创建方，发车即 arriving）。
 * 异常统一交给 common/GlobalExceptionHandler 脱敏转信封。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 管理 key：环境变量 ADMIN_KEY 优先，缺省 admin-dev-key（仅 dev） */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    @Autowired
    private AdminService adminService;

    /** 字典缓存清除（F-108 §0.4 运营 evict 端点）：只清该组 Caffeine，不做上游强制重拉 */
    @Autowired
    private DictService dictService;

    /** 主理人佣金钱包只读列表（F-119 §3）：直注 Service 同 dictService 先例；写路径只有跑批，本面零写端点 */
    @Autowired
    private PartnerWalletService partnerWalletService;
    /** 双轨第二轨用：解析 Bearer → claims（G-01 起的 parseClaims 通道，本类首次使用；只读 typ 与 subject） */
    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 双轨第二轨用：按 subject 查 admin_user，定「账号还在不在、是否仍 active」。
     * <p>Controller 直接注 mapper 是本仓既有分层口径（鉴权基础设施类先例：{@code AuthInterceptor}
     * 注 UserMapper/StoreMapper 做查库定角色与归属集合）——这一步属于<b>闸门本身</b>，
     * 塞进 AdminService 反而把闸门挪离闸门。
     * <p>拿到的实体<b>只读 status 一个字段</b>：它随 {@code BaseMapper.selectById} 的列清单带着
     * {@code password_hash}，所以 {@link #activeAdminId} 的返回类型是 {@code Long}（账号 id）而不是实体，
     * 实体不越过这个方法（出参面为零，G4/D-5 纪律）。
     */
    @Autowired
    private AdminUserMapper adminUserMapper;

    /** 订单核销/取消：PUT /api/admin/orders/{id}/status，body {status, reason?}（completed/cancelled；F-61 起取消必填 reason） */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // Admin 端点无 JWT userId，按 IP 维度（§2.10a）
    @PutMapping("/orders/{id}/status")
    public R<Void> updateOrderStatus(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestHeader(value = "Authorization", required = false) String authz,
                                     @PathVariable("id") String id,
                                     @Valid @RequestBody AdminOrderStatusDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateOrderStatus(id, dto.getStatus(), dto.getReason(), grant.actor());
        return R.ok(null, "订单状态已更新");
    }

    /** 商品改价/补库存/上下架：PUT /api/admin/dishes/{id}，body {price?, stock?, onSale?} 部分更新 */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // Admin 端点无 JWT userId，按 IP 维度（§2.10a）
    @PutMapping("/dishes/{id}")
    public R<Void> updateDish(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                              @RequestHeader(value = "Authorization", required = false) String authz,
                              @PathVariable Long id,
                              @Valid @RequestBody AdminDishUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateDish(id, dto, grant.actor());
        return R.ok(null, "商品已更新");
    }

    // ==================================================================
    // F-116 商品内容审核「先发后审」（一个端点，只写 dish.audit_status / audit_reason 两列；
    // 驳回/恢复一律不改 on_sale——两列正交）。闸门/限流姿势逐字照本面其余端点（F-110~F-114 模式）：
    // 双轨 checkKeyOrOperator；写档 10/60s，按 IP 维度（Admin 面无 JWT userId，§2.10a）。
    // 审计（dish.audit）在 AdminServiceImpl 内与业务同源写。
    // 红线（契约 §2.6）：免费领池不做驳回联动（契约 §0 登记边界）。
    // ==================================================================

    /**
     * 商品内容审核（F-116 §2.2）：{@code PUT /api/admin/dishes/{id}/audit}，
     * body {@code {action:'reject'|'restore', reason?}}。
     * reject：reason 必填 trim 后 1~200，置 {@code audit_status='rejected'} + 理由（on_sale 不动）；
     * restore：置 {@code 'ok'} 并清理由，body 带 reason 忽略。action 非法 → 400「action 仅允许 reject/restore」；
     * dish 不存在 → 404「菜品不存在」；已是目标态 → 幂等 200（不产生假变化，审计照记）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-116 §2.2，照 F-110）
    @PutMapping("/dishes/{id}/audit")
    public R<Void> auditDish(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                             @RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable("id") Long id,
                             @Valid @RequestBody AdminDishAuditDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.auditDish(id, dto, grant.actor());
        return R.ok(null, "商品已审核");
    }

    /** 评价审核：PUT /api/admin/reviews/{id}/audit，body {action}（approve 过审展示 / reject 驳回删除） */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // Admin 端点无 JWT userId，按 IP 维度（§2.10a）
    @PutMapping("/reviews/{id}/audit")
    public R<Void> auditReview(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable Long id,
                               @Valid @RequestBody AdminReviewAuditDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.auditReview(id, dto.getAction(), grant.actor());
        return R.ok(null, "评价已审核");
    }

    /** 平台代开通商家（F-12/M-05 一期）：POST /api/admin/merchants，body {name, phone, contactName?} */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // R6 随建随挂：Admin 新端点必带限流
    @PostMapping("/merchants")
    public R<Void> createMerchant(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                  @Valid @RequestBody AdminMerchantCreateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.createMerchant(dto, grant.actor());   // 同事务 INSERT merchant + merchant_profile(approved) + UPDATE user
        return R.ok(null, "商家已开通");
    }

    /**
     * 入驻申请审核（F-18.12 第 3 条，第 5 个端点）：PUT /api/admin/store-applies/{id}/audit，
     * body {@code {action:'approve'|'reject', remark?}}。
     * <p>approve = 同事务三写（置态 + INSERT store + 审计），rejected/approved 之后再审为幂等成功
     * （不重复发布门店），申请不存在 404、非法 action 400。
     * <p>请求体用 {@code @RequestBody Map<String, String>} 而非新建 DTO：F-18.12 的「允许新增文件」清单
     * 只点了 StoreApplyCreateDTO / PartnerStoreUpdateDTO 两个 DTO，本端点只读两个键，
     * 姿势照 PartnerController#verify 的既有先例（同理由见其类注释）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // R6 随建随挂：Admin 新端点必带限流
    @PutMapping("/store-applies/{id}/audit")
    public R<Void> auditStoreApply(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                   @RequestHeader(value = "Authorization", required = false) String authz,
                                   @PathVariable("id") Long id,
                                   @RequestBody Map<String, String> body) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.auditStoreApply(id,
                body == null ? null : body.get("action"),
                body == null ? null : body.get("remark"),
                grant.actor());
        return R.ok(null, "申请已审核");
    }

    /**
     * 商家自助入驻申请审核（F-106 片 A）：{@code PUT /api/admin/merchant-applies/{id}/audit}，
     * body {@code {action:'approve'|'reject', remark?}}。
     * <p>approve = 同事务五步（查申请/幂等 → INSERT merchant（contact_phone 取申请人 user.phone）→
     * INSERT merchant_profile(approved) → 条件 UPDATE user（影响行数≠1 回滚）→ 置申请态 + 审计）；
     * reject = remark 必填、只置态 + 审计，不删行。已审核（approved/rejected）再审为幂等成功。
     * <p>闸门与限流姿势逐字照上方 {@link #auditStoreApply}（Admin 面无 JWT userId，限流按 IP 维度）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // R6 随建随挂：Admin 新端点必带限流
    @PutMapping("/merchant-applies/{id}/audit")
    public R<Void> auditMerchantApply(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                      @RequestHeader(value = "Authorization", required = false) String authz,
                                      @PathVariable("id") Long id,
                                      @RequestBody Map<String, String> body) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.auditMerchantApply(id,
                body == null ? null : body.get("action"),
                body == null ? null : body.get("remark"),
                grant.actor());
        return R.ok(null, "申请已审核");
    }

    /**
     * 运营端商家入驻申请列表（F-106 片 B2，补 §0.6 的契约漏项）：
     * {@code GET /api/admin/merchant-applies?status=&pageNum=&pageSize=} →
     * {@code {list:[{id,userId,merchantName,contactName,businessLicense,licenses,legalPerson,address,
     * settlementAccount,status,auditRemark,createdAt,auditedAt,applicantPhone}], total, pageNum, pageSize,
     * filters:{status}}}（出参形状与 {@code AdminV2ServiceImpl#storeApplies} 逐字同源；F-107 起多 {@code licenses}）。
     * <p><b>为什么在本类而不是 AdminV2Controller</b>：v2 面是 JWT-only（{@code requireAdminJwtOnly}），
     * 本端点是运营审核台的数据源、按契约走<b>双轨</b>闸门——{@code X-Admin-Key} 与运营 JWT 都放行，
     * 与同组的 {@code PUT /merchant-applies/{id}/audit} 保持同一鉴权面。
     * <p>{@code status} 可空（空=全部），非空仅 pending/approved/rejected；分页缺省 1/20、值域 [1,100]；
     * 排序 pending 优先、其次 created_at DESC, id DESC；{@code applicantPhone} 是 user.phone 的
     * {@code PhoneMask.mask} 掩码值（绝不原样下发）。判定细节全在 {@code AdminService#listMerchantApplies}。
     * <p>限流取只读档 30/60s（照 v2 列表族，同 {@link #arrivalPreview}）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档：照 AdminV2Controller 列表端点
    @GetMapping("/merchant-applies")
    public R<Map<String, Object>> merchantApplies(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                                  @RequestParam(value = "status", required = false) String status,
                                                  @RequestParam(value = "pageNum", required = false) Integer pageNum,
                                                  @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封逐字节内容不变、只重铺泛型参数（同 arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.listMerchantApplies(pageNum, pageSize, status));
    }

    /**
     * 发车只读预览（F-63）：{@code GET /api/admin/arrivals/preview?storeId=&date=yyyy-MM-dd} →
     * {@code {storeId, storeName, date, existing, plan, orderCount, dishKinds, departable}}
     * （形状与服务语义见 {@code AdminService#arrivalPreview}；只读动作不写审计，RF 只约束写动作）。
     * <p>闸门照本面其余端点（双轨 {@link #checkKeyOrOperator}，静态密钥与运营 JWT 都放行——本页是运营台
     * 新页不是 v2 只读面，不挂 JWT-only 那道更严的闸）；限流按契约锚点取 v2 只读列表那一档（30/60s），
     * 比写端点松、与其余只读面同格。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档：照 AdminV2Controller 列表端点（F-63 锚点指定）
    @GetMapping("/arrivals/preview")
    public R<Map<String, Object>> arrivalPreview(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                 @RequestHeader(value = "Authorization", required = false) String authz,
                                                 @RequestParam("storeId") Long storeId,
                                                 @RequestParam("date") String date) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封逐字节内容不变、只重铺泛型参数：AdminGrant 的 denied 是旧 5 端点定型成的 R<Void>，
            // 闸门只有一份（不许复制 401 文案），这里按同 code/message 重建同形信封
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.arrivalPreview(storeId, date));
    }

    /**
     * 运营端「发车」（F-63）：{@code POST /api/admin/arrivals}，body {@code {storeId, batchDate, remark?}}。
     * 平台统一配货模型下批次唯一的创建方，发车即 arriving；判据顺序、幂等重算与 400/404 定性
     * 全部钉在 {@code AdminService#departArrival} 的接口注释上，本方法只做闸门与 message 二选一
     * （两句 message 逐字照契约：idempotent=true 报「已按最新订单重算明细」，false 报「已发车，批次进入到货中」）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // R6 随建随挂：Admin 新写端点必带限流（照 updateOrderStatus 档）
    @PostMapping("/arrivals")
    public R<Map<String, Object>> departArrival(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                @RequestHeader(value = "Authorization", required = false) String authz,
                                                @Valid @RequestBody AdminArrivalDepartDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            return R.fail(denied.getCode(), denied.getMessage());   // 同上：只重铺泛型，信封内容与文案一份真源
        }
        Map<String, Object> data = adminService.departArrival(dto, grant.actor());
        return R.ok(data, Boolean.TRUE.equals(data.get("idempotent"))
                ? "该店该日批次已在途，已按最新订单重算明细"
                : "已发车，批次进入到货中");
    }

    /**
     * 字典分组缓存清除（F-108 §0.4）：{@code POST /api/admin/dict/{group}/evict}。
     * <p>只清该组 Caffeine 缓存并返回 {@code {code:200}}，<b>不做</b>「从上游强制重拉」——
     * 上游当前不可用，加了只会造 400；清完后下一次 {@code GET /api/dict?group=} 会重新读本地 dict_item。
     * <p>组不存在（dict_group 无此行）→ Service 抛 BizException，对外 code=400。
     * <p>闸门与限流逐字照本面其余端点：双轨 {@link #checkKeyOrOperator}（静态密钥或运营 JWT），
     * Admin 面无 JWT userId → 限流按 IP 维度 10/60s。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // R6 随建随挂：Admin 新端点必带限流
    @PostMapping("/dict/{group}/evict")
    public R<Void> evictDict(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                             @RequestHeader(value = "Authorization", required = false) String authz,
                             @PathVariable("group") String group) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        dictService.evict(group);
        return R.ok(null, "字典缓存已清除");
    }

    /**
     * 从上游同步某分组词条（F-108 §0.8，B 语义「只增不删」）：{@code POST /api/admin/dict/{group}/sync}。
     * <p>组不存在 → 400「字典分组不存在」；组 {@code enabled=0} → 400「分组已停用，不同步」。
     * 上游拉取失败<b>原样抛它的 BizException</b>（400「字典服务暂时不可用，请稍后重试」），绝不静默当成功。
     * 成功时逐条 {@code INSERT IGNORE}（{@code uk_group_word} 判重）：本地没有的插入、已有的（含运营改过的）
     * 一行不动，不删除、不更新任何既有行；有插入才 evict 该组。返回 {@code {upstreamTotal, inserted, skipped}}，
     * 并由 Service 写审计 {@code dict.sync}。
     * <p>闸门与限流逐字照本面其余写端点：双轨 {@link #checkKeyOrOperator}，IP 维度 10/60s。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（§0.8）
    @PostMapping("/dict/{group}/sync")
    public R<Map<String, Object>> syncDict(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                           @RequestHeader(value = "Authorization", required = false) String authz,
                                           @PathVariable("group") String group) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.syncDictGroup(group, grant.actor()), "字典同步完成");
    }

    // ==================================================================
    // F-108 §0.7 片 2：字典管理端 CRUD（六个端点）
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator（静态密钥或运营 JWT）；
    // 写端点 10/60s、读端点 30/60s，均按 IP 维度（Admin 面无 JWT userId，§2.10a）。
    // 写操作成功后的 evict 在 AdminServiceImpl 内完成（与服务侧「写后自动失效」同一处，
    // 不靠调用方记得；本类只保留 §0.4 手工 evict 端点）。
    // ==================================================================

    /**
     * 字典分组列表（F-108 §0.7）：{@code GET /api/admin/dict/groups} →
     * {@code [{code,name,enabled,itemCount,sort}]}，按 {@code sort ASC, code ASC}。
     * 只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（§0.7）
    @GetMapping("/dict/groups")
    public R<List<Map<String, Object>>> dictGroups(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                   @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.dictGroups());
    }

    /**
     * 某分组全部词条（F-108 §0.7）：{@code GET /api/admin/dict/{group}/items} →
     * {@code [{id,word,definition,sortOrder}]}，不分页（最多几十条），顺序同 C 端读路径。
     * 分组不存在 → Service 抛 BizException，对外 400「字典分组不存在」。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（§0.7）
    @GetMapping("/dict/{group}/items")
    public R<List<Map<String, Object>>> dictItems(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                                  @PathVariable("group") String group) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.dictItems(group));
    }

    /**
     * 新增词条（F-108 §0.7）：{@code POST /api/admin/dict/{group}/items}，body {@code {word, definition?, sortOrder?}}。
     * word 必填（DTO {@code @NotBlank} + Service 双保险）；分组不存在 400；{@code uk_group_word} 冲突 400。
     * 成功后 Service 内自动 evict 该组并写审计 {@code dict.item.create}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（§0.7）
    @PostMapping("/dict/{group}/items")
    public R<Void> createDictItem(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                  @PathVariable("group") String group,
                                  @Valid @RequestBody AdminDictItemCreateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.createDictItem(group, dto, grant.actor());
        return R.ok(null, "词条已新增");
    }

    /**
     * 部分更新词条（F-108 §0.7）：{@code PUT /api/admin/dict/items/{id}}，
     * body {@code {word?, definition?, sortOrder?}}（null = 本次不改）。id 不存在 404。
     * 成功后 Service 内自动 evict 该词条所属组并写审计 {@code dict.item.update}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（§0.7）
    @PutMapping("/dict/items/{id}")
    public R<Void> updateDictItem(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                  @PathVariable("id") Long id,
                                  @Valid @RequestBody AdminDictItemUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateDictItem(id, dto, grant.actor());
        return R.ok(null, "词条已更新");
    }

    /**
     * 删除词条（F-108 §0.7）：{@code DELETE /api/admin/dict/items/{id}}。id 不存在 404。
     * {@code dish_category} 组做引用保护：该分类下仍有商品时 400（文案带真实商品数），
     * 其余组直接删。成功后 Service 内自动 evict 该组并写审计 {@code dict.item.delete}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（§0.7）
    @DeleteMapping("/dict/items/{id}")
    public R<Void> deleteDictItem(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                  @PathVariable("id") Long id) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.deleteDictItem(id, grant.actor());
        return R.ok(null, "词条已删除");
    }

    /**
     * 部分更新分组（F-108 §0.7）：{@code PUT /api/admin/dict/groups/{code}}，
     * body {@code {name?, enabled?}}。code 不存在 404；enabled=0 停用后 {@code GET /api/dict} 按空返回。
     * 成功后 Service 内自动 evict(code) 并写审计 {@code dict.group.update}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（§0.7）
    @PutMapping("/dict/groups/{code}")
    public R<Void> updateDictGroup(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                   @RequestHeader(value = "Authorization", required = false) String authz,
                                   @PathVariable("code") String code,
                                   @Valid @RequestBody AdminDictGroupUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateDictGroup(code, dto, grant.actor());
        return R.ok(null, "分组已更新");
    }

    // ==================================================================
    // F-109：免费领活动管理（四个端点，只动活动配置，不碰订单/价格/库存）
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator（静态密钥或运营 JWT）；
    // 读端点 30/60s、写端点 10/60s，均按 IP 维度（Admin 面无 JWT userId，§2.10a）。
    // 审计（free.activity.update / free.pool.update）在 AdminServiceImpl 内与业务同源写。
    // ==================================================================

    /**
     * 免费领活动 + 候选池（F-109 §0.2）：{@code GET /api/admin/free/activity} →
     * {@code {id,dishId,dishName,dailyQuota,dailyClaimed,dailyDate,threshold,status,
     * pool:[{id,dishId,dishName,onSale}]}}。只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-109 §0.2）
    @GetMapping("/free/activity")
    public R<Map<String, Object>> freeActivity(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                               @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.freeActivity());
    }

    /**
     * 更新免费领活动（F-109 §0.2）：{@code PUT /api/admin/free/activity}，
     * body {@code {dishId?, dailyQuota?, threshold?, status?}}。
     * <p><b>只允许改这四列</b>；{@code daily_claimed}/{@code daily_date} 是领取链路的运行时计数器，
     * DTO 没有对应字段、Service 也不写这两列——body 里塞了不会生效（超发/白送的口子封死在这里）。
     * 校验与 400 文案全部钉在 {@code AdminService#updateFreeActivity}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-109 §0.2）
    @PutMapping("/free/activity")
    public R<Void> updateFreeActivity(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                      @RequestHeader(value = "Authorization", required = false) String authz,
                                      @Valid @RequestBody AdminFreeActivityUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateFreeActivity(dto, grant.actor());
        return R.ok(null, "免费领活动已更新");
    }

    /**
     * 免费领候选池（F-109 §0.2）：{@code GET /api/admin/free/pool} →
     * {@code [{id,dishId,dishName,onSale}]}，池行 id 升序（=插入序）。只读端点不写审计。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-109 §0.2）
    @GetMapping("/free/pool")
    public R<List<Map<String, Object>>> freePool(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                 @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.freePool());
    }

    /**
     * 全量替换免费领候选池（F-109 §0.2）：{@code PUT /api/admin/free/pool}，body {@code {dishIds:[...]}}。
     * 非空、去重、每个 dish 存在且 {@code on_sale=1}，否则 400；同事务删旧插新（旧池行一条不留）。
     * 判据与文案全部钉在 {@code AdminService#updateFreePool}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-109 §0.2）
    @PutMapping("/free/pool")
    public R<Void> updateFreePool(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestHeader(value = "Authorization", required = false) String authz,
                                  @Valid @RequestBody AdminFreePoolUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateFreePool(dto, grant.actor());
        return R.ok(null, "候选池已更新");
    }

    // ==================================================================
    // F-110：搜索别名维护（四个端点，只影响搜索命中，不碰价格/库存/订单）
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator（静态密钥或运营 JWT）；
    // 读端点 30/60s、写端点 10/60s，均按 IP 维度（Admin 面无 JWT userId，§2.10a）。
    // 审计（alias.create / alias.update / alias.delete）在 AdminServiceImpl 内与业务同源写。
    // C 端搜索语义不变：DishMapper#selectDishIdsByAlias 的精确等值 SQL 本片零改动。
    // ==================================================================

    /**
     * 搜索别名全量列表（F-110 §0.2）：{@code GET /api/admin/aliases} →
     * {@code [{id,alias,dishId,dishName,onSale}]}，按 alias 升序。
     * 下架商品同样列出（onSale=0）——下架件持有别名是合法状态。
     * 只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-110 §0.2）
    @GetMapping("/aliases")
    public R<List<Map<String, Object>>> aliases(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.aliases());
    }

    /**
     * 新增搜索别名（F-110 §0.2）：{@code POST /api/admin/aliases}，body {@code {alias, dishId}}。
     * alias trim 后非空且 ≤50；dishId 必须存在（不要求在售）；{@code uk_alias} 冲突 400「该别名已存在」。
     * 成功后 Service 内写审计 {@code alias.create}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-110 §0.2）
    @PostMapping("/aliases")
    public R<Void> createAlias(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @RequestHeader(value = "Authorization", required = false) String authz,
                               @Valid @RequestBody AdminAliasCreateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.createAlias(dto, grant.actor());
        return R.ok(null, "别名已新增");
    }

    /**
     * 部分更新搜索别名（F-110 §0.2）：{@code PUT /api/admin/aliases/{id}}，
     * body {@code {alias?, dishId?}}（null/缺键 = 本次不改）。id 不存在 404；
     * 两列全空 400；alias 空白 400；改后撞 {@code uk_alias} 400。
     * 成功后 Service 内写审计 {@code alias.update}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-110 §0.2）
    @PutMapping("/aliases/{id}")
    public R<Void> updateAlias(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable("id") Long id,
                               @Valid @RequestBody AdminAliasUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateAlias(id, dto, grant.actor());
        return R.ok(null, "别名已更新");
    }

    /**
     * 删除搜索别名（F-110 §0.2）：{@code DELETE /api/admin/aliases/{id}}。id 不存在 404。
     * 成功后 Service 内写审计 {@code alias.delete}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-110 §0.2）
    @DeleteMapping("/aliases/{id}")
    public R<Void> deleteAlias(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable("id") Long id) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.deleteAlias(id, grant.actor());
        return R.ok(null, "别名已删除");
    }

    // ==================================================================
    // F-112 秒杀商品集管理（两个端点，只写 dish.seckill / seckill_price 两列）
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator（静态密钥或运营 JWT）；
    // 读端点 30/60s、写端点 10/60s，均按 IP 维度（Admin 面无 JWT userId，§2.10a）。
    // 审计（seckill.update）在 AdminServiceImpl 内与业务同源写。
    // 红线（契约 §1.3）：SeckillController 与 listSeckill 一行不改；场次配置化属 P2 本片不做。
    // ==================================================================

    /**
     * 全量 dish 的秒杀视角列表（F-112 §1.1）：{@code GET /api/admin/seckill/dishes} →
     * {@code [{id,name,price,onSale,stock,soldCount,seckill,seckillPrice,limitBuy,merchantId}]}，
     * 按 id 升序。秒杀中/未进的筛选由前端做（dish 全量 28 行，不需要服务端 filter 参数）；
     * 下架品同样列出（预先配秒杀是合法状态）。只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-112 §1.1）
    @GetMapping("/seckill/dishes")
    public R<List<Map<String, Object>>> seckillDishes(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                      @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.seckillDishes());
    }

    /**
     * 更新秒杀参与态（F-112 §1.2）：{@code PUT /api/admin/seckill/dishes/{id}}，
     * body {@code {seckill: 0|1, seckillPrice?: number}}——进秒杀（1+设价）/ 调价（1+新价）/
     * 移出（0，不回清 seckill_price）。校验顺序与 400/404 文案全部钉在
     * {@code AdminService#updateSeckillDish}（404 → seckill 值域 → 价形状 → 价低于库内现价）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-112 §1.2）
    @PutMapping("/seckill/dishes/{id}")
    public R<Void> updateSeckillDish(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestHeader(value = "Authorization", required = false) String authz,
                                     @PathVariable("id") Long id,
                                     @Valid @RequestBody AdminSeckillUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateSeckillDish(id, dto, grant.actor());
        return R.ok(null, "秒杀商品已更新");
    }

    // ==================================================================
    // F-113 券模板管理（三个端点：GET 列表 / POST 上新 / PUT 改配置，无 DELETE——
    // 已发过的模板删除 = 历史 user_coupon.coupon_key 悬挂，停用即运营语义，引用保护固化为「不提供」）。
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator；读 30/60s、写 10/60s，均按 IP。
    // 审计（coupon_template.create / coupon_template.update）在 AdminServiceImpl 内与业务同源写。
    // 红线（契约 §2.4）：CouponController 三端点签名与出参形状不变；C 端零改动（center 增量加 title 键）。
    // ==================================================================

    /**
     * 券模板全量列表（F-113 §4）：{@code GET /api/admin/coupon-templates} →
     * {@code [{id,couponKey,title,threshold,amount,validDays,limitPerUser,totalQuota,issuedCount,status}]}，
     * 按 id 升序；disabled 同样列出（停用是运营可见状态）。只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-113 §4，照 F-110）
    @GetMapping("/coupon-templates")
    public R<List<Map<String, Object>>> couponTemplates(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                        @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/seckillDishes 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.couponTemplates());
    }

    /**
     * 上新券模板（F-113 §4）：{@code POST /api/admin/coupon-templates}。key 形状/冲突、
     * title 长度、金额形状与 amount&lt;threshold（负价单拦截）、validDays 1~365、limitPerUser ≥0、
     * totalQuota NULL 或 ≥0、status 值域——校验顺序与 400 文案全部钉在
     * {@code AdminService#createCouponTemplate}。成功后写审计 {@code coupon_template.create}。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-113 §4，照 F-110）
    @PostMapping("/coupon-templates")
    public R<Void> createCouponTemplate(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                        @RequestHeader(value = "Authorization", required = false) String authz,
                                        @Valid @RequestBody AdminCouponTemplateCreateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.createCouponTemplate(dto, grant.actor());
        return R.ok(null, "券模板已新增");
    }

    /**
     * 部分更新券模板（F-113 §4）：{@code PUT /api/admin/coupon-templates/{id}}，
     * body 七列 null=不改；校验对合并后的新值判；id 不存在 404。
     * <b>coupon_key 与 issued_count 不可改</b>：body 带了这两个探测字段时忽略不落库，
     * 并在响应 message 里注明（契约 §4「带了忽略并在响应注明」）。
     * 成功后写审计 {@code coupon_template.update}（before/after 完整快照）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-113 §4，照 F-110）
    @PutMapping("/coupon-templates/{id}")
    public R<Void> updateCouponTemplate(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                        @RequestHeader(value = "Authorization", required = false) String authz,
                                        @PathVariable("id") Long id,
                                        @Valid @RequestBody AdminCouponTemplateUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateCouponTemplate(id, dto, grant.actor());
        boolean carriedIgnored = dto != null
                && (dto.getCouponKey() != null || dto.getIssuedCount() != null);
        return R.ok(null, carriedIgnored
                ? "券模板已更新（coupon_key/issued_count 不可改，已忽略）"
                : "券模板已更新");
    }

    // ==================================================================
    // F-114 自提点存量管理（两个端点：GET 全量列表 / PUT 部分更新白名单八字段；无新建无删除——
    // 门店的创建只经入驻审核 approve，删除无运营语义）。
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator；读 30/60s、写 10/60s，均按 IP。
    // 审计（store.update）在 AdminServiceImpl 内与业务同源写，before/after 只记实际变化的键。
    // 红线（契约 §3）：C 端 StoreController/StoreServiceImpl、主理人端 PartnerStoreServiceImpl、
    // OrderServiceImpl/F-111 拦截一行不改；不加表不加列不加迁移不碰 init.sql 不新增 yml 键。
    // ==================================================================

    /**
     * 自提点全量列表（F-114 §1.1）：{@code GET /api/admin/stores} →
     * {@code [{id,name,address,service,status,openTime,closeTime,dailyCapacity,accepting,
     * ownerUserId(null=平台直营),createdAt}]}，按 id 升序；closed 同样列出（停业是运营可见状态）。
     * <b>不含 contact_phone/photos/lng/lat</b>（运营页用不上，少下发）。只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-114 §1.1，照 F-110）
    @GetMapping("/stores")
    public R<List<Map<String, Object>>> stores(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                               @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 couponTemplates/seckillDishes 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.listStores());
    }

    /**
     * 部分更新自提点（F-114 §1.2）：{@code PUT /api/admin/stores/{id}}，body 白名单八列 null=不改；
     * 校验与 404 文案全部钉在 {@code AdminService#updateStore}（404「门店不存在」→ 八列全空 400 →
     * trim/形状/值域逐条）。<b>owner_user_id/contact_phone/photos/lng/lat 不可改</b>：body 带了这些
     * 探测字段时忽略不落库，并在响应 message 里注明（契约 §1.2「带了忽略并在响应注明」）。
     * 成功后写审计 {@code store.update}（before/after 只记实际变化的键）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-114 §1.2，照 F-110）
    @PutMapping("/stores/{id}")
    public R<Void> updateStore(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                               @RequestHeader(value = "Authorization", required = false) String authz,
                               @PathVariable("id") Long id,
                               @Valid @RequestBody AdminStoreUpdateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        if (grant.denied() != null) {
            return grant.denied();
        }
        adminService.updateStore(id, dto, grant.actor());
        boolean carriedIgnored = dto != null
                && (dto.getOwnerUserId() != null || dto.getContactPhone() != null
                    || dto.getPhotos() != null || dto.getLng() != null || dto.getLat() != null);
        return R.ok(null, carriedIgnored
                ? "门店已更新（owner_user_id/contact_phone/photos/lng/lat 不可改，已忽略）"
                : "门店已更新");
    }

    // ==================================================================
    // F-118 结算跑批 + 结算中心（三个端点：POST generate 写 / GET 列表 / GET 明细，无 PUT 无 DELETE——
    // settlement 只增不改是契约 §3 红线：无更新无作废端点，冲销走下一期 refund 列承载，06 册 §4.5 定稿）。
    // 闸门/限流姿势逐字照本面其余端点：双轨 checkKeyOrOperator；读 30/60s、写 10/60s，均按 IP。
    // 审计（settlement.create）在 AdminServiceImpl 内与业务同源写。
    // 红线（契约 §3）：主理人佣金（F-119）/打款（轨道 B）/保证金（m1x）零涉及；
    // 下单/取消/支付链路零改动（本段只读它们产生的数据）；不新增 yml 键。
    // ==================================================================

    /**
     * 生成账期（F-118 §1.1）：{@code POST /api/admin/settlements/generate}，
     * body {@code {merchantId, periodStart, periodEnd}}（日期 YYYY-MM-DD）。
     * 聚合口径、金额、事务与幂等（INSERT→条件 UPDATE 挂锚影响行数=候选数才成功）、
     * 404/400 判据顺序全部钉在 {@code AdminService#generateSettlement}；
     * 成功出参为生成单概要，message「账期已生成」。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 10, windowSeconds = 60)  // 写档（F-118 §1，照 F-110）
    @PostMapping("/settlements/generate")
    public R<Map<String, Object>> generateSettlement(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                     @RequestHeader(value = "Authorization", required = false) String authz,
                                                     @RequestBody AdminSettlementGenerateDTO dto) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 merchantApplies/arrivalPreview 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.generateSettlement(dto, grant.actor()), "账期已生成");
    }

    /**
     * 账期列表（F-118 §1.2）：{@code GET /api/admin/settlements?merchantId=}，全量或按商家筛。
     * 行内含实时对账标志 reconcileOk 与 paidChannelAbsent:true（记账态明示），
     * 判定与键序钉在 {@code AdminService#listSettlements}。只读端点不写审计（RF 只约束写动作）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-118 §1.2，照 F-110）
    @GetMapping("/settlements")
    public R<List<Map<String, Object>>> settlements(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                    @RequestHeader(value = "Authorization", required = false) String authz,
                                                    @RequestParam(value = "merchantId", required = false) Long merchantId) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 couponTemplates/listStores 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.listSettlements(merchantId));
    }

    /**
     * 账单明细（F-118 §1.3）：{@code GET /api/admin/settlements/{id}/items}——明细弹窗数据源，
     * 该单挂锚的 order_item 行（快照列+订单支付态，只读）。id 不存在 404「结算单不存在」。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-118 §1.3，照 F-110）
    @GetMapping("/settlements/{id}/items")
    public R<List<Map<String, Object>>> settlementItems(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                        @RequestHeader(value = "Authorization", required = false) String authz,
                                                        @PathVariable("id") Long id) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(adminService.settlementItems(id));
    }

    // ==================================================================
    // F-119 主理人佣金钱包（只读一端点）：GET /api/admin/partner-wallets 运营侧全量钱包
    // （user/店名/余额/流水数）。契约 §3 红线：无任何钱包写端点——写路径只有生成账期跑批
    // （AdminServiceImpl#generateSettlement 挂锚后的追加步 ⑦.5），人工调额=资损面不设入口，
    // 审计走 settlement.create 既有行；本端点只读档限流 IP 30/60s（照 F-110）。
    // ==================================================================

    /**
     * 主理人钱包列表（F-119 §3）：{@code GET /api/admin/partner-wallets}——只读全量，按钱包 id 升序。
     * 行内键 {@code userId,userPhone,storeNames,balance,flowCount}（userPhone 经 PhoneMask 脱敏；
     * 判定与键序钉在 {@code PartnerWalletService#listWallets}）。
     */
    @RateLimit(keyType = RateLimit.KeyType.IP, limit = 30, windowSeconds = 60)  // 只读档（F-119 §3，照 F-110）
    @GetMapping("/partner-wallets")
    public R<List<Map<String, Object>>> partnerWallets(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                                       @RequestHeader(value = "Authorization", required = false) String authz) {
        AdminGrant grant = checkKeyOrOperator(key, authz);
        R<Void> denied = grant.denied();
        if (denied != null) {
            // 信封内容不变，只重铺泛型参数（同 settlements 的既有处置）
            return R.fail(denied.getCode(), denied.getMessage());
        }
        return R.ok(partnerWalletService.listWallets());
    }

    /**
     * 双轨闸门（F-20/G-06 Step 2，D-2；替换原私有 {@code checkKey(String)}，5 个端点各调一次）：
     * <ol>
     *   <li>① {@code X-Admin-Key} 命中静态密钥 → 放行（<b>裁决顺序密钥在前</b>，D-2：curl 脚本 / SQL 手册零感知，
     *       行为与从前完全一致，只是比对换成了恒定时间，见 {@link #adminKeyMatches}）；</li>
     *   <li>② 未命中 → 读 {@code Authorization: Bearer}，须 {@code typ=admin} <b>且</b> admin_user 里该 subject
     *       存在且 {@code status=active} 才放行（{@link #activeAdminId}，D-6 的三重判定）；</li>
     *   <li>③ 两条都不中 → 返回与从前<b>同一枚 401、同一句文案</b>（RE 红线逐字节不变；也不给
     *       「是密钥错还是 JWT 错」的区分信号——③ 是唯一出口，攻击方无法据此探测哪条轨试过）。</li>
     * </ol>
     * <p><b>F-20.9 #1 的返回形态</b：判定结果包进 {@link AdminGrant}（原来只回 {@code R<Void>}，
     * 拒绝才非 null——那足够挡人，却不足以告诉调用方「这次是哪条轨放的行」，而审计 actor 需要这个信息）。
     * 调用姿势不变：{@code if (grant.denied() != null) return grant.denied();}，随后把 {@code grant.actor()}
     * 原样交给 AdminService 写审计。
     * <p>BE-20-1 起 AuthInterceptor 对 {@code /api/admin/} 前缀整体提前放行、不再解析用户 claims
     * （typ=admin 反隔离不适用于本面，ATTR_USER_ID 也不注入），本方法是旧 5 端点唯一的服务端闸门。
     */
    private AdminGrant checkKeyOrOperator(String key, String authz) {
        if (adminKeyMatches(key)) {
            return AdminGrant.byKey();
        }
        Long adminId = activeAdminId(authz);
        if (adminId != null) {
            return AdminGrant.byAdminUser(adminId);
        }
        return AdminGrant.rejected(R.fail(R.CODE_UNAUTHORIZED, "管理密钥缺失或错误"));
    }

    /**
     * 双轨闸门的放行结果（F-20.9 #1；契约给的两种形态里选了「小值对象」，因为 actor 与拒绝信封要一起给，
     * 只回 {@code Long adminId + boolean byKey} 会把「拼 actor 字符串」这件事推到 5 个调用点各拼一遍）。
     * <ul>
     *   <li>{@code denied} 非 null = 拒绝，端点原样返回这枚信封（文案逐字节不变，RE）；</li>
     *   <li>{@code actor} = 本次的审计操作者标识：<b>静态密钥轨传 null</b>（Service 侧
     *       {@code AdminServiceImpl#auditActor} 沿用既有 {@code admin:<key 摘要 8>}，逐字与从前相同，
     *       摘要算法因此保持全仓一处真源，不在本类再复制一份 SHA-256）；
     *       <b>运营 JWT 轨传 {@code admin_user:<id>}</b>（id 就是 {@link #activeAdminId} 查库确认过 active 的那枚，
     *       与 {@code AdminAuthServiceImpl#AUDIT_ACTION_LOGIN} 登录审计用的同一枚拼法）。
     *       两个取值都不含密钥明文、口令、token（RD）。</li>
     * </ul>
     */
    private record AdminGrant(String actor, R<Void> denied) {

        static AdminGrant byKey() {
            return new AdminGrant(null, null);
        }

        static AdminGrant byAdminUser(Long adminId) {
            return new AdminGrant("admin_user:" + adminId, null);
        }

        static AdminGrant rejected(R<Void> envelope) {
            return new AdminGrant(null, envelope);
        }
    }

    /**
     * v2 侧闸门（F-20.6 第 6 条，<b>Step 3 起由 {@code AdminV2Controller} 挂用</b>：
     * <b>只认运营 JWT，静态密钥在 v2 上一律 401</b>（D-2「防止新代码更弱」：
     * {@code X-Admin-Key} 是全平台共享、无个体身份、可跨环境复用的那枚串，v2 端点要求「谁做的」可追责到
     * {@code admin_user:<id>}，所以 v2 不接受它；旧 5 端点继续双轨是为兼容存量脚本，见 {@link #checkKeyOrOperator}）。
     * 401 文案沿用 RE 红线那一句「管理密钥缺失或错误」，<b>逐字节未改</b>，v2 没有另立新文案（Step 3 未获准改文案）。
     *
     * <p>方法可见性从 private 放宽到<b>包内</b>（同 {@code com.fresh.controller} 的 AdminV2Controller 复用），
     * 目的是<b>闸门只有一份</b>：三重判定（Bearer 可解析 + typ=admin + 查库存在且 active）一旦复制成第二份，
     * 将来收紧一处、漏另一处就是洞。不新建独立 guard 组件是因为本期允许的文件清单里没有那个位置。
     * <p>泛型 {@code <T>}：v2 端点的返回类型是 {@code R<Map<String,Object>>}，而旧 5 端点是 {@code R<Void>}，
     * 同一枚拒绝信封要能塞进两种返回类型（{@code R.fail} 本来就是泛型静态工厂，这里只是把类型参数透出来）。
     */
    <T> R<T> requireAdminJwtOnly(String authz) {
        return activeAdminId(authz) != null
                ? null
                : R.fail(R.CODE_UNAUTHORIZED, "管理密钥缺失或错误");
    }

    /**
     * 静态密钥比对（D-3 收掉 07 册 §待办的「X-Admin-Key 未做恒定时间比较」）：
     * 原 {@code key.equals(adminKey)} 是短路比较——第 i 个字节不同就返回，响应时延按「猜对了几个前缀字节」增长，
     * 是个可远程测量的时序侧信道（理论上可按字节前缀爆破）。换成 {@link MessageDigest#isEqual} 逐字节比到底。
     * <p>key 缺失/空串<b>一律拒</b>（D-3 逐字）；额外把 {@code adminKey} 自身空白也判不匹配——
     * 与旧行为等价（旧实现里空 key 早已被 {@code key.isBlank()} 挡掉，配了空白密钥本就等于「谁都进不来」），
     * 但把「宁可全员 401，也不做『空密钥=免登录』兜底」这条写死，防将来 ADMIN_KEY 注入意外为空时静默裸奔。
     */
    private boolean adminKeyMatches(String key) {
        if (key == null || key.isEmpty() || adminKey == null || adminKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 运营 JWT → 可用的 {@code admin_user.id}；任一环节不合回 null（调用方统一 401，不区分原因）。
     *
     * <p><b>三重判定缺一不可</b>（D-6）：
     * ① {@code Bearer } 前缀在且 token 非空且可验签解析（{@code parseClaims} 对伪造/过期一律 null）；
     * ② {@code typ=admin}（<b>没有这一步就等于放行 C 端 token</b>：{@code user.id} 与 {@code admin_user.id}
     *    都是 BIGINT 自增、从 1 起，小 id 必然并存，C 端 token 的 subject 撞上某个运营 id 是完全正常的现象；
     *    实测断言见门禁 G4 的「C 端用户 token 打旧端点须与无凭证同判」）；
     * ③ 查库该 subject 在 admin_user 里<b>存在</b>且 {@code status=active}（只验 typ 不查库 → 被停用的账号
     *    手里那枚 token 能一路签到自然过期，「停用」形同虚设）。
     * <p>{@code role=admin} claim 本期不参与判定（判据以契约 D-2/D-6 点名的 typ + 查库为准，不自创第四道）。
     * <p><b>实体只读 status</b>：{@code selectById} 的列清单含 password_hash（Step 1 已登记的继承面），
     * 本方法的返回类型是 {@code Long}，实体不越过这个方法一行。
     */
    private Long activeAdminId(String authz) {
        if (authz == null || !authz.startsWith("Bearer ")) {
            return null;
        }
        String token = authz.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        Claims claims = jwtUtil.parseClaims(token);
        if (claims == null || !"admin".equals(claims.get("typ"))) {
            return null;
        }
        Long adminUserId;
        try {
            adminUserId = Long.valueOf(String.valueOf(claims.getSubject()));
        } catch (IllegalArgumentException e) {
            // subject 缺失/非数字：与「typ 不合」同一出口（null → 401），不额外给信号
            return null;
        }
        AdminUser account = adminUserMapper.selectById(adminUserId);
        return account != null && "active".equals(account.getStatus()) ? adminUserId : null;
    }
}
