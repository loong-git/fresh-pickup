package com.fresh.controller;

import com.fresh.annotation.RateLimit;
import com.fresh.common.JwtUtil;
import com.fresh.common.R;
import com.fresh.dto.AdminArrivalDepartDTO;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;
import com.fresh.dto.AdminOrderStatusDTO;
import com.fresh.dto.AdminReviewAuditDTO;
import com.fresh.entity.AdminUser;
import com.fresh.mapper.AdminUserMapper;
import com.fresh.service.AdminService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
