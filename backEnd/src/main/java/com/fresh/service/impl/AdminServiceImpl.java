package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantProfile;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.entity.Store;
import com.fresh.entity.StoreApply;
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.AdminService;
import com.fresh.service.CouponService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 管理端薄版服务实现（T-M3-09）：
 * - 订单状态迁移沿用用户端同一套状态机条件 UPDATE（只允许从 pending_pickup 迁出），
 *   重复核销/重复取消幂等返回；置 cancelled 时与用户取消语义一致回补库存（防库存永久少记）。
 * - 商品部分更新：仅 set DTO 中非 null 字段，配合 @Valid 范围校验。
 * - 审计 actor（F-20.9 #1，R-c 收口）：五个写方法的 actor 不再恒为 {@code admin:<key 摘要>}，
 *   改由 Controller 显式传入本次是哪条轨放行的行（{@link #auditActor} 归一，静态密钥轨的取值逐字不变）。
 */
@Slf4j
@Service
public class AdminServiceImpl implements AdminService {

    /** T-M3-09：管理端仅允许置为这两种状态（核销 completed / 取消 cancelled），不可回退待自提 */
    private static final Set<String> ALLOWED_TARGETS = Set.of(
            OrderServiceImpl.STATUS_COMPLETED, OrderServiceImpl.STATUS_CANCELLED);

    /** T-M4-05 收口：评价审核动作白名单（approve 过审展示 / reject 驳回删除） */
    private static final Set<String> ALLOWED_AUDIT_ACTIONS = Set.of("approve", "reject");

    /** S-4：审计 detail 值序列化用（Jackson ObjectMapper 线程安全，同 common/ResponseUtil 姿势静态复用） */
    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    /**
     * 审计 detail 列宽与预算口径（admin_audit_log.detail VARCHAR(500)，init.sql:319；BE-2 同型修复）：
     * MerchantDishServiceImpl 那边按「Jackson 最坏 2 倍」推预算的前提已被 F-13.10 #2 实测证伪——
     * 控制码转成六字符转义串（反斜杠 + u0001）是 **6 倍**，100 个 U+0001 序列化后 602 字符 > 500，STRICT_TRANS_TABLES 下 INSERT 报 1406，
     * 被本方法旧的「catch → log.warn」吞掉 → 业务写照常提交、审计行凭空消失。
     * 本类的 detail 虽然全部来自 @Size 界定过的短值（order 状态、dish 三个数值列、review 动作、
     * 商家名 ≤100 + 脱敏手机号），但同名「100 字符全控制码」的推演同样成立，故按同一口径收口：
     * 先序列化、再按剩余预算整体截断，兜底必须仍落一行。
     */
    private static final int AUDIT_DETAIL_MAX = 500;

    /** 兜底 detail：两侧都装不下时落这一行——「审计行必须存在」优先于「审计行有内容」（BE-2 的硬要求） */
    private static final String AUDIT_MINIMAL_DETAIL = "{\"old\":\"audit truncated\",\"new\":\"audit truncated\"}";

    /** 三键留痕的兜底（F-61）：形状必须与正常写入一致，兜底不许偷偷换形状（理由正是这次要留住的东西） */
    private static final String AUDIT_MINIMAL_DETAIL_WITH_REASON =
            "{\"old\":\"audit truncated\",\"new\":\"audit truncated\",\"reason\":\"audit truncated\"}";

    // ---- F-44（微信支付回调拒收留痕）的四枚钉死列值 + 两枚兜底常量 ----
    //      为什么落在 admin_audit_log：init.sql:392 的 ops_log 表注释明写「本表只收商家/主理人/申请人现场动作
    //      ——平台侧动作一律走 admin_audit_log」，回调裁定是平台侧事件。列形状全部落在
    //      AdminV2ServiceImpl#FILTER_PATTERN（^[A-Za-z0-9_.:-]{1,64}$）内，运营端零改动即可筛。

    /** 拒收留痕的操作主体：平台自己（回调无登录态、无管理密钥参与，故<b>绝不</b>用 {@code admin:<key 摘要>} 那套） */
    private static final String NOTIFY_REJECT_ACTOR = "system:wx-notify";

    /** 拒收留痕的动作名（{@code admin_audit_log.action VARCHAR(50)}，本值 17 字符）；F-50（D-50-2）把字面量本身搬到 {@link AdminAuditLogMapper#ACTION_PAY_NOTIFY_REJECT} 这一处公共出口，写侧与读侧（大盘当日拒收计数）同源、值与语义一字未改。本注释刻意<b>不扩行</b>：本文件被仓内多条「AdminServiceImpl:行号」指针引用，净零行变化才不会把那些指针推漂 */
    private static final String NOTIFY_REJECT_ACTION = AdminAuditLogMapper.ACTION_PAY_NOTIFY_REJECT;

    /** 拒收留痕的目标类型：一律是订单（{@code target_type VARCHAR(30)}） */
    private static final String NOTIFY_REJECT_TARGET_TYPE = "order";

    /** {@code target_id} 拿不到订单号时的占位符：该列 {@code VARCHAR(64) NOT NULL}，写空串会直接让整行审计插不进去 */
    private static final String NOTIFY_REJECT_TARGET_ID_PLACEHOLDER = "-";

    /** code 为空/空白时的归一值（正常链路走不到：controller 传的是 {@code RejectCode#name()}） */
    private static final String NOTIFY_REJECT_CODE_UNSET = "UNSPECIFIED";

    /**
     * {@code target_id} 列宽（{@code admin_audit_log.target_id VARCHAR(64) NOT NULL}，init.sql:353）。
     * 路径段是请求方可控的（{@code /api/pay/<随便一串>/wx-notify} 没有长度闸门），超 64 字符会让整行审计
     * 撞 1406 被本类那两层 catch 静默吞掉——正是 BE-2 说过的「不可发现的脱审」。
     * 按 BE-2 的既有裁定处理：<b>「审计行必须存在」优先于「审计行有内容」</b>，截到列宽而不是丢行。
     * （正常订单号是 {@code orders.id VARCHAR(50)}，见 init.sql 的 orders，永远走不到截断这一支。）
     */
    private static final int AUDIT_TARGET_ID_MAX = 64;

    // ---- F-18.12 第 3 条（入驻申请审核链）新增常量与依赖（那一批没有改动任何既有方法体；
    //      F-20.9 #1 才给五个写方法各加了 {@code String actor} 形参，见类注释与 {@link #auditActor}）----

    /** store_apply 三态（m18 ⑦ 建表口径，照 m15 merchant_profile.audit_status 的三态范式） */
    private static final String APPLY_PENDING = "pending";
    private static final String APPLY_APPROVED = "approved";
    private static final String APPLY_REJECTED = "rejected";

    /** 审核动作里的 approve 一侧（白名单本身复用上方 ALLOWED_AUDIT_ACTIONS，不再长第二份集合） */
    private static final String ACTION_APPROVE = "approve";

    /** 过审发布的门店固定置 open（A2.1：过审即营业；停业走 Admin 或后续门店维护，本期 partner 不可改 status） */
    private static final String STORE_STATUS_OPEN = "open";

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private CouponService couponService;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private MerchantProfileMapper merchantProfileMapper;
    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;
    /** F-18.12 第 3 条：入驻申请审核链的两个依赖（只被下面新增的 auditStoreApply 使用） */
    @Autowired
    private StoreApplyMapper storeApplyMapper;
    @Autowired
    private StoreMapper storeMapper;

    /** 管理密钥：与 AdminController.checkKey 同源配置（ADMIN_KEY 环境变量优先），仅用于算摘要做审计 actor */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    @Override
    @Transactional
    public void updateOrderStatus(String orderId, String status, String reason, String actor) {
        String target = status == null ? "" : status.trim();
        if (!ALLOWED_TARGETS.contains(target)) {
            throw new BizException("status 仅允许 completed（核销）或 cancelled（取消）");
        }
        // F-61：取消必填理由。判据放在查库之前——理由缺失是请求自身的缺陷，
        // 不该先花一次 SELECT 再告诉调用方"你少填了一个字段"，也不该让 404 与 400 抢先后。
        String why = reason == null ? "" : reason.trim();
        if (OrderServiceImpl.STATUS_CANCELLED.equals(target) && why.isEmpty()) {
            throw new BizException("取消订单必须填写原因（会回补库存并置已退款，需可追责）");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(404, "订单不存在");
        }
        if (target.equals(order.getStatus())) {
            return; // 幂等：已是目标状态（重复核销/重复取消）直接成功
        }
        // 状态机条件更新：仅 pending_pickup 可迁出，并发双击/重复提交只有一次生效
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, OrderServiceImpl.STATUS_PENDING_PICKUP)
                .set(Order::getStatus, target));
        if (updated == 0) {
            throw new BizException("当前状态不可变更（仅待自提订单可核销/取消）");
        }
        // 取消与用户端取消（OrderServiceImpl#cancelOrder）语义一致：回补库存 + 回补优惠券（T-M4-02），
        // 保证只回补一次
        if (OrderServiceImpl.STATUS_CANCELLED.equals(target)) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
            items.forEach(item -> dishMapper.increaseStock(item.getDishId(), item.getQuantity()));
            // 已支付单取消 → 置 pay_status=2（已退款）并按明细回退销量，与用户端取消同口径：条件更新 1→2
            // 仅一次生效（0 行即未支付取消，不动 pay_status 也不回退销量）；mock 收银台无真实资金流，仅补状态语义
            boolean refunded = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getId, orderId)
                    .eq(Order::getPayStatus, 1)
                    .set(Order::getPayStatus, 2)) > 0;
            if (refunded) {
                items.forEach(item -> dishMapper.decrementSoldCount(item.getDishId(), item.getQuantity()));
            }
            couponService.restoreByOrderId(orderId);
            log.info("管理端取消订单并回补库存: orderId={}, items={}, refunded={}", orderId, items.size(), refunded);
        }
        log.info("管理端更新订单状态: orderId={}, {} -> {}", orderId, order.getStatus(), target);
        // R6 补课：审计在置态成功后写（幂等早退分支无状态变化，不产生留痕）
        // F-61：有理由才落第三键——核销通常无理由，此时 detail 仍是 {"old","new"} 两键，
        // 与既有运营端渲染和 SQL 手册口径逐字相同；空串冒充"填了但没内容"是要不得的。
        if (why.isEmpty()) {
            writeAudit(auditActor(actor), "order.status", "order", orderId, order.getStatus(), target);
        } else {
            writeAuditWithReason(auditActor(actor), "order.status", "order", orderId, order.getStatus(), target, why);
        }
    }

    @Override
    public void updateDish(Long dishId, AdminDishUpdateDTO dto, String actor) {
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BizException(404, "菜品不存在");
        }
        LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>().eq(Dish::getId, dishId);
        int setCount = 0;
        if (dto.getPrice() != null) {
            wrapper.set(Dish::getPrice, dto.getPrice());
            setCount++;
        }
        if (dto.getStock() != null) {
            wrapper.set(Dish::getStock, dto.getStock());
            setCount++;
        }
        if (dto.getOnSale() != null) {
            wrapper.set(Dish::getOnSale, dto.getOnSale());
            setCount++;
        }
        if (setCount == 0) {
            throw new BizException("无更新字段（可传 price / stock / onSale）");
        }
        dishMapper.update(null, wrapper);
        log.info("管理端更新商品: dishId={}, price={}, stock={}, onSale={}",
                dishId, dto.getPrice(), dto.getStock(), dto.getOnSale());
        // R6 补课：旧值取方法开头 selectById 快照、新值取本次提交列（未传的列为 null，JSON 里显式留痕）
        writeAudit(auditActor(actor), "dish.update", "dish", String.valueOf(dishId),
                "price=" + dish.getPrice() + ",stock=" + dish.getStock() + ",onSale=" + dish.getOnSale(),
                "price=" + dto.getPrice() + ",stock=" + dto.getStock() + ",onSale=" + dto.getOnSale());
    }

    @Override
    public void auditReview(Long reviewId, String action, String actor) {
        String op = action == null ? "" : action.trim();
        if (!ALLOWED_AUDIT_ACTIONS.contains(op)) {
            throw new BizException("action 仅允许 approve（过审展示）或 reject（驳回删除）");
        }
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BizException(404, "评价不存在");
        }
        if ("approve".equals(op)) {
            // 过审：audit_status 置 NULL 恢复展示（set 传 null 会拼 SET audit_status=NULL，
            // 与 ReviewServiceImpl 列表过滤的 IS NULL 判定配对）；按 id 覆盖写，
            // 对已过审（NULL）重复执行结果不变，天然幂等
            reviewMapper.update(null, new LambdaUpdateWrapper<Review>()
                    .eq(Review::getId, reviewId)
                    .set(Review::getAuditStatus, null));
            log.info("管理端过审评价: reviewId={}, dishId={}", reviewId, review.getDishId());
        } else {
            // 驳回：删除记录（避免违规内容留存）；重复执行因查无此评价返回 404，语义明确
            reviewMapper.deleteById(reviewId);
            log.info("管理端驳回删除评价: reviewId={}, dishId={}", reviewId, review.getDishId());
        }
        // R6 补课：审核动作成对留痕（旧值 audit_status 可能为 null=已过审，新值记本次动作）
        writeAudit(auditActor(actor), "review.audit", "review", String.valueOf(reviewId),
                review.getAuditStatus(), op);
    }

    @Override
    @Transactional
    public void createMerchant(AdminMerchantCreateDTO dto, String actor) {
        // 1) 按 phone 查 user；查无 → 400（决策点②：不静默建号——静默建号绕过验证码验证，
        //    建出的 user 无 token 无会话，商家还得再登录一次；拒绝 400 保证绑定的是真实可登录账号）
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (u == null) {
            throw new BizException(R.CODE_BAD_REQUEST, "该手机号尚未注册，请让商家先用验证码登录一次再开通");
        }
        // 2) 已绑定商家 → 400（决策点②幂等）
        if (u.getMerchantId() != null) {
            throw new BizException(R.CODE_BAD_REQUEST, "该手机号已绑定商家");
        }
        // 3) 同事务：INSERT merchant → INSERT merchant_profile(approved) → 条件 UPDATE user role/merchant_id
        Merchant m = new Merchant();
        m.setName(dto.getName());
        m.setContactPhone(dto.getPhone());
        m.setContactName(dto.getContactName());
        m.setStatus("active");
        merchantMapper.insert(m);                       // 自增 id 回填 m.getId()

        MerchantProfile mp = new MerchantProfile();
        mp.setMerchantId(m.getId());
        mp.setAuditStatus("approved");                  // 一期平台代开通=直接 approved（03 §4.2）
        mp.setAuditedAt(LocalDateTime.now());
        merchantProfileMapper.insert(mp);

        int rows;
        try {
            // S-3 TOCTOU 修法：条件 UPDATE（项目既有幂等范式，同 OrderServiceImpl 状态机 /
            // FreeServiceImpl#decreaseStock 影响行数判定）。真正的防线是 WHERE merchant_id IS NULL：
            // 并发双开通时后一笔在行锁重读后条件不成立 → 0 行 → 下方抛异常回滚本事务刚插的
            // merchant + merchant_profile，不留 updateById 后写覆盖造成的孤儿主体脏数据。
            // uk_user_merchant 是 UNIQUE(merchant_id)，两笔并发拿到的是不同自增 id，UNIQUE 根本不冲突，
            // 兜不住「一人一主体」——别再依赖它（旧注释在此误导过）。
            rows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, u.getId())
                    .isNull(User::getMerchantId)
                    .set(User::getRole, "merchant")          // 绑定与解锁分离（06 §5.3.3）：代开通即过审即解锁
                    .set(User::getMerchantId, m.getId()));
        } catch (DuplicateKeyException e) {
            // 残留兜底：同一 user 被并发绑到同一 merchant_id 时仍撞 uk_user_merchant
            // → 与本方法开头的前置判定同文案（幂等语义一致）
            throw new BizException(R.CODE_BAD_REQUEST, "该手机号已绑定商家");
        }
        if (rows == 0) {
            // 影响 0 行 = 该 user 的 merchant_id 已被并发请求写入 → 与前置判定同文案；抛异常触发事务回滚
            throw new BizException(R.CODE_BAD_REQUEST, "该手机号已绑定商家");
        }

        // 4) 审计（R6 红线：Admin 新端点随建随写 admin_audit_log）
        writeAudit(auditActor(actor), "merchant.create", "merchant", String.valueOf(m.getId()),
                null, "name=" + dto.getName() + ",phone=" + maskPhone(dto.getPhone()));
    }

    /**
     * 入驻申请审核（F-18.12 第 3 条，本类唯一新增的写方法；当时上面四个既有方法体一字未改——
     * F-20.9 #1 起四个方法各多一个 {@code String actor} 形参、审计那一行的 actor 表达式换成
     * {@link #auditActor}，除此之外业务体仍是那一批的原样）。
     *
     * <p><b>同事务三写</b>（{@code @Transactional} 覆盖整方法，任一步抛异常整体回滚，不留「已发布却没置态」半态）：
     * ① 条件 UPDATE store_apply 置 approved/rejected + audited_at=NOW()（+ remark，见下方「remark 为 null 时不 SET」）；
     * ② approve 才 INSERT store（名称/地址/坐标/服务/联系电话全部取自申请行，
     *    {@code owner_user_id=申请人} 是这家店归属的<b>唯一</b>生产写入点，m18 ⑪ 那段 UPDATE 只是演示库归位种子）；
     * ③ admin_audit_log（actor 由 {@link #auditActor} 归一：静态密钥轨={@code admin:<key 摘要 8>}、
     *    运营 JWT 轨={@code admin_user:<id>}，F-20.9 #1；D-5 把平台侧动作划给这张表）。
     *
     * <p><b>为什么把条件 UPDATE 排在 INSERT store 前面</b：两者在同一事务里，成败都整体回滚，顺序不影响原子性；
     * 先做带 {@code status='pending'} 的那条 UPDATE 可以让「并发二次审核」在第一步就回 0 行短路掉，
     * 连一个白烧的自增 id 都不留（S-3 的姿势，同 {@link #createMerchant} 里的条件 UPDATE）。
     *
     * <p><b>幂等</b>（照 {@code updateOrderStatus:114-116}）：已是目标态 → 直接成功、<b>不重复 INSERT store</b>、
     * 不写第二行审计（无状态变化就不该有留痕，同 :144 那行的既有注释口径）。
     * 审核结论不可翻转（approved 再 reject / rejected 再 approve → 400）：翻转要连带处理「已发布的店」，
     * 那是二期 Admin 下架链的事，本期封死入口；rejected 后申请人重新提交=新行（D-21）。
     *
     * <p>审计 detail 里的联系电话只出 {@link PhoneMask#mask} 结果（RD：admin_audit_log 也不许落明文手机号）。
     */
    @Override
    @Transactional
    public void auditStoreApply(Long applyId, String action, String remark, String actor) {
        String op = action == null ? "" : action.trim();
        // 动作白名单复用 ALLOWED_AUDIT_ACTIONS（:61 那份，评价审核与本端点共用，不复制第二份集合）
        if (!ALLOWED_AUDIT_ACTIONS.contains(op)) {
            throw new BizException(R.CODE_BAD_REQUEST, "action 仅允许 approve（过审并发布门店）或 reject（驳回）");
        }
        String trimmedRemark = remark == null ? null : remark.trim();
        if (trimmedRemark != null && trimmedRemark.length() > 255) {
            // audit_remark VARCHAR(255)：严格模式下超长是 1406 而非 400，必须在写库前拦（同列宽纪律见本类 :67-75）
            throw new BizException(R.CODE_BAD_REQUEST, "审核意见不能超过 255 字");
        }
        boolean approve = ACTION_APPROVE.equals(op);
        String targetStatus = approve ? APPLY_APPROVED : APPLY_REJECTED;

        StoreApply apply = storeApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BizException(R.CODE_NOT_FOUND, "申请不存在");
        }
        if (targetStatus.equals(apply.getStatus())) {
            // 幂等早退：已 approved 再 approve 不再 INSERT store；已 rejected 再 reject 不再刷 audited_at
            log.info("管理端入驻申请审核幂等命中: applyId={}, status={}", applyId, apply.getStatus());
            return;
        }
        if (!APPLY_PENDING.equals(apply.getStatus())) {
            throw new BizException(R.CODE_BAD_REQUEST, "该申请已审核完成，不可再次审核");
        }

        // ① 条件 UPDATE 置态：WHERE id + status='pending' 是并发闸门（本仓未开 useAffectedRows，
        //    matched-rows 语义下少了 status 条件，并发第二笔也会回 1 行 → 重复发布门店）
        int rows = storeApplyMapper.update(null, new LambdaUpdateWrapper<StoreApply>()
                .eq(StoreApply::getId, applyId)
                .eq(StoreApply::getStatus, APPLY_PENDING)
                .set(StoreApply::getStatus, targetStatus)
                // remark 为 null 时不 SET：pending 行的 audit_remark 按构造必为 NULL（只有本方法写它，
                // 而本方法要求 status='pending'），不 SET 与 SET NULL 等价，却避开了 MP 绑无 jdbcType 的 null 参数
                // （F-17.2 #4 实测过的 JdbcType OTHER 坑）
                .set(trimmedRemark != null, StoreApply::getAuditRemark, trimmedRemark)
                .set(StoreApply::getAuditedAt, LocalDateTime.now()));
        if (rows == 0) {
            StoreApply now = storeApplyMapper.selectById(applyId);
            if (now != null && targetStatus.equals(now.getStatus())) {
                // 并发窗口内另一笔已把状态翻到同一目标态 → 与开头那次幂等早退同义（不重复发布、不重复留痕）
                log.info("管理端入驻申请审核并发幂等命中: applyId={}, status={}", applyId, targetStatus);
                return;
            }
            // 行在两次读之间消失（Admin 手工清库等极端情形）：与「申请不存在」同码同文案
            throw new BizException(R.CODE_NOT_FOUND, "申请不存在");
        }

        String newSide = "status=" + targetStatus + ",remark=" + trimmedRemark
                + ",maskedPhone=" + PhoneMask.mask(apply.getContactPhone());
        Long publishedStoreId = null;
        if (approve) {
            // ② INSERT store：这是门店进 GET /api/stores 的**唯一**发布通道（D-12/A2.1 的数据纪律）
            Store store = new Store();
            store.setName(apply.getProposedName());
            store.setAddress(apply.getProposedAddress());
            store.setLng(apply.getLng());
            store.setLat(apply.getLat());
            store.setService(apply.getService());
            store.setStatus(STORE_STATUS_OPEN);
            // 归属唯一来源：申请行的 user_id（服务端 token 解析后落库的值），绝不收任何请求侧归属入参（RF）
            store.setOwnerUserId(apply.getUserId());
            store.setContactPhone(apply.getContactPhone());
            // open_time/close_time/daily_capacity/photos 不填 → 列 NULL；accepting 不填 → DB DEFAULT 1（D-8 一期只展示）
            storeMapper.insert(store);
            publishedStoreId = store.getId();
            newSide = newSide + ",storeId=" + publishedStoreId;
        }
        log.info("管理端审核入驻申请: applyId={}, action={}, applicantUserId={}, publishedStoreId={}",
                applyId, op, apply.getUserId(), publishedStoreId);

        // ③ 审计（写门店与写申请同事务，审计本身是旁路：writeAudit 双层 try-catch 吞异常，绝不打断主事务）
        writeAudit(auditActor(actor), "store_apply.audit", "store_apply", String.valueOf(applyId),
                "status=" + apply.getStatus(), newSide);
    }

    /**
     * 微信支付回调拒收留痕（F-44）——本类<b>唯一</b>不为「商家/主理人/申请人现场动作」服务的审计入口，
     * 归 {@code admin_audit_log} 的依据见接口注释（ops_log 表注释把平台侧动作一律指向本表）。
     *
     * <p>列值钉死：{@code actor=system:wx-notify} / {@code action=pay.notify_reject} /
     * {@code target_type=order} / {@code target_id=订单号或 '-'} / {@code detail} 只有两键。
     * 四枚值都过 {@code AdminV2ServiceImpl#FILTER_PATTERN}（{@code ^[A-Za-z0-9_.:-]{1,64}$}），
     * 运营端现有那四个输入框就能筛，前端零改动。
     *
     * <p><b>actor 不带任何密钥摘要</b>：本场景没有 key 参与（回调无登录态、无管理密钥），
     * 所以绝不复用 {@link #auditActor} 那套 {@code admin:<sha8>}——那会把「一把根本没参加的密钥」写进审计，
     * 还会让运营误判成人工操作。
     *
     * <p>写入走既有那唯一一份审计核心 {@link #writeAudit(String, String, String, String, String, String, String)}
     * （两趟兜底 + Jackson 转义姿势与其余五处审计<b>完全同一份代码</b>，本仓不养第二个 writer）。
     * 兜底 detail 传的是<b>本方法自己那枚两键 JSON</b>而不是 {@link #AUDIT_MINIMAL_DETAIL}：
     * 本 detail 最长约 60 字符（{@code code} ≤27 + 3 位状态码），根本走不到超长那条路；
     * 万一仍因别的原因失败，第二趟必须<b>保持形状不变</b>再试一次——换成 {@code {"old","new"}}
     * 会把「detail 只有两键」这条运营端与后续脚本依赖的形状破掉。
     */
    @Override
    public void recordNotifyReject(String code, int httpStatus, String orderId, String ip) {
        String detail = notifyRejectDetail(code, httpStatus);
        writeAudit(NOTIFY_REJECT_ACTOR, NOTIFY_REJECT_ACTION, NOTIFY_REJECT_TARGET_TYPE,
                notifyRejectTargetId(orderId), detail, detail, ip);
    }

    /**
     * {@code target_id} 归一（F-44）：拿不到订单号写 {@code '-'}（列 NOT NULL，空串整行插不进去），
     * 超列宽截到 64（理由与依据见 {@link #AUDIT_TARGET_ID_MAX}）——两条兜底都是「宁可内容退化，也不丢行」。
     */
    private String notifyRejectTargetId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return NOTIFY_REJECT_TARGET_ID_PLACEHOLDER;
        }
        String trimmed = orderId.trim();
        return trimmed.length() <= AUDIT_TARGET_ID_MAX ? trimmed : trimmed.substring(0, AUDIT_TARGET_ID_MAX);
    }

    /**
     * 拒收审计的 detail（F-44 安全红线）：<b>只允许</b> {@code code} 与 {@code httpStatus} 两键。
     * 拒收原因天生带着报文、签名、平台证书 serial、解密明文、金额、手机号——一条都不许顺手写进来
     * （这一列在运营端是原文可查的，任何可重放字段落进来就等于把回调链路又开了一个读接口）。
     * 序列化走本类既有那枚静态 {@link #AUDIT_MAPPER}（S-4 的全量转义姿势），不手拼字符串。
     */
    private String notifyRejectDetail(String code, int httpStatus) {
        ObjectNode detail = AUDIT_MAPPER.createObjectNode();
        // code 由调用方（controller 的 catch）从 RejectCode 枚举带 name()，理论上不可能为空；
        // 归一成 UNSPECIFIED 而不是留 null/空串：空串会让这行审计在筛选里彻底查不到，等于没留痕
        detail.put("code", code == null || code.isBlank() ? NOTIFY_REJECT_CODE_UNSET : code.trim());
        detail.put("httpStatus", httpStatus);
        return detail.toString();
    }

    /**
     * 审计落库（R6/M-04）：actor/action/target_type/target_id/detail(旧值→新值 JSON)/ip。
     * 审计是旁路——写失败绝不打断主事务（契约 §2.10b 红线）。
     * <p>BE-2 同型修复（本仓母版同样只 log.warn 过）：detail 先序列化、再按列宽剩余预算整体截断，
     * 且**失败也要再落一行**——第一趟完整 detail，任一层原因（1406 超长、1366 孤立代理、其它列宽…）
     * 失败即退成 AUDIT_MINIMAL_DETAIL 重试一次，两趟都失败才 warn。旧写法丢的那一行是不可发现的脱审。
     * <p>F-44 起本方法是 {@link #writeAudit(String, String, String, String, String, String, String)} 的薄封装
     * （业务面「旧值→新值」那五处审计的入口与形状一字未变，ip 仍走 {@link #currentIp()}）；
     * 抽出来的那枚核心是<b>全类唯一</b>的审计写入路径，回调拒收留痕也走它，不再开第二个 writer。
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String oldVal, String newVal) {
        writeAudit(actor, action, targetType, targetId,
                buildAuditDetail(oldVal, newVal), AUDIT_MINIMAL_DETAIL, currentIp());
    }

    /**
     * 带理由的写留痕（F-61，当前只有 {@code order.status} 的取消用）：detail 变三键
     * {@code {"old","new","reason"}}。兜底常量必须<b>同形状</b>也是三键——理由恰恰是这次改动
     * 唯一要留住的东西，截断时退化成两键就等于把它删了。
     */
    /**
     * 带理由的写留痕（F-61，当前只有 {@code order.status} 的取消用）：detail 变三键
     * {@code {"old","new","reason"}}。兜底常量必须<b>同形状</b>也是三键——理由恰恰是这次改动
     * 唯一要留住的东西，截断时退化成两键就等于把它删了。
     * <p>刻意<b>不叫 writeAudit</b>：既有那个「detail + minimalDetail + ip」的七参重载签名与本方法
     * 逐字相同（七个 String），同名会直接撞车。
     */
    private void writeAuditWithReason(String actor, String action, String targetType, String targetId,
                                      String oldVal, String newVal, String reason) {
        writeAudit(actor, action, targetType, targetId,
                buildAuditDetail(oldVal, newVal, reason), AUDIT_MINIMAL_DETAIL_WITH_REASON, currentIp());
    }

    /**
     * 审计写入核心（本类唯一一份，两趟兜底）：detail 由调用方给完整形状，装不下时退成 {@code minimalDetail} 再试一次。
     *
     * @param detail        第一趟写入的完整 detail（必须是合法 JSON）
     * @param minimalDetail 兜底 detail：必须仍是<b>同一形状的合法 JSON</b>——
     *                      业务面用 {@link #AUDIT_MINIMAL_DETAIL}（{@code {"old","new"}}），
     *                      拒收留痕用它自己的两键 JSON（形状是运营端与脚本的依赖，兜底不许换形状）
     * @param ip            来源 IP；业务面传 {@link #currentIp()}（{@code RequestContextHolder} 的 remoteAddr），
     *                      回调拒收传调用点按 {@code rate-limit.trust-proxy} 口径算出的值（列可空，null 就是取不到）
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String detail, String minimalDetail, String ip) {
        try {
            insertAudit(actor, action, targetType, targetId, detail, ip);
            return;
        } catch (Exception e) {
            log.warn("审计写入失败（旁路，不影响主事务），改用最小 detail 重试: action={}, targetType={}, targetId={}",
                    action, targetType, targetId, e);
        }
        try {
            insertAudit(actor, action, targetType, targetId, minimalDetail, ip);
        } catch (Exception e) {
            log.warn("审计重试仍失败（旁路，不影响主事务）: action={}, targetType={}, targetId={}",
                    action, targetType, targetId, e);
        }
    }

    private void insertAudit(String actor, String action, String targetType, String targetId,
                             String detail, String ip) {
        AdminAuditLog row = new AdminAuditLog();
        row.setActor(actor);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setDetail(detail);
        row.setIp(ip);
        adminAuditLogMapper.insert(row);
    }

    /**
     * detail 组装（BE-2）：产出必落在 VARCHAR(500) 内且仍是合法 JSON（JSON_VALID(detail)=1 是 DoD⑦ 硬断言）。
     * 常规值两侧原样保留；超限时 old 侧塌成 "truncated"，把预算让给「本次改了什么」的 new 侧，
     * new 侧仍装不下就按 clipJsonLiteral 的 token 边界截。
     */
    private String buildAuditDetail(String oldVal, String newVal) {
        String oldJson = jsonValue(dropUnpairedSurrogates(oldVal));
        String newJson = jsonValue(dropUnpairedSurrogates(newVal));
        String detail = "{\"old\":" + oldJson + ",\"new\":" + newJson + "}";
        if (detail.length() <= AUDIT_DETAIL_MAX) {
            return detail;
        }
        String head = "{\"old\":\"truncated\",\"new\":";
        String clippedNew = clipJsonLiteral(newJson, AUDIT_DETAIL_MAX - head.length() - 1);
        return clippedNew != null ? head + clippedNew + "}" : AUDIT_MINIMAL_DETAIL;
    }

    /**
     * 三键版（F-61）：{@code {"old","new","reason"}}。预算分配与两键版相反——<b>先牺牲 reason</b>，
     * 因为 old/new 那一对是"改了什么"的主证，reason 是"为什么"的补强；整体仍装不下才落三键最小兜底。
     */
    private String buildAuditDetail(String oldVal, String newVal, String reason) {
        String oldJson = jsonValue(dropUnpairedSurrogates(oldVal));
        String newJson = jsonValue(dropUnpairedSurrogates(newVal));
        String reasonJson = jsonValue(dropUnpairedSurrogates(reason));
        String detail = "{\"old\":" + oldJson + ",\"new\":" + newJson + ",\"reason\":" + reasonJson + "}";
        if (detail.length() <= AUDIT_DETAIL_MAX) {
            return detail;
        }
        String head = "{\"old\":" + oldJson + ",\"new\":" + newJson + ",\"reason\":";
        String clippedReason = clipJsonLiteral(reasonJson, AUDIT_DETAIL_MAX - head.length() - 1);
        return clippedReason != null ? head + clippedReason + "}" : AUDIT_MINIMAL_DETAIL_WITH_REASON;
    }

    /**
     * 把「已序列化的 JSON 值」截到 maxLen 字符内且仍是合法 JSON 字符串：按 token 边界前进扫描
     * （\\uXXXX 6 字符、\\x 2 字符、代理对 2 字符），截点绝不落在转义序列或代理对中间。
     * 不可截时返回 null，交调用方走最小 detail 兜底。
     */
    private String clipJsonLiteral(String literal, int maxLen) {
        if (literal == null || literal.length() <= maxLen) {
            return literal;
        }
        if (literal.length() < 2 || literal.charAt(0) != '"' || maxLen < 2) {
            return null;
        }
        int bodyEnd = maxLen - 1;                 // 末位留给收尾双引号
        int i = 1;
        int safeEnd = 1;
        while (i < bodyEnd) {
            char c = literal.charAt(i);
            int step = 1;
            if (c == '\\') {
                step = (i + 1 < literal.length() && literal.charAt(i + 1) == 'u') ? 6 : 2;
            } else if (Character.isHighSurrogate(c)) {
                step = 2;
            }
            if (i + step > bodyEnd) {
                break;
            }
            i += step;
            safeEnd = i;
        }
        return literal.substring(0, safeEnd) + "\"";
    }

    /**
     * 孤立代理项换成 U+FFFD：Jackson 走 Writer 序列化对不成对代理是**原样透传**（不报错），
     * 而 utf8mb4 列存不下它 → MySQL 1366 → 整行审计消失（与超长 1406 同一类不可发现脱审）。
     */
    private String dropUnpairedSurrogates(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                if (sb != null) {
                    sb.append(c).append(value.charAt(i + 1));
                }
                i++;                            // 合法代理对：两个一起走
                continue;
            }
            if (!Character.isSurrogate(c)) {
                if (sb != null) {
                    sb.append(c);
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(value.length());
                sb.append(value, 0, i);
            }
            sb.append('\uFFFD');
        }
        return sb == null ? value : sb.toString();
    }

    /**
     * 审计 actor 归一（F-20.9 #1，收 R-c「审计 actor 失真」；五个写点的旧表达式 {@code "admin:" + adminKeyDigest()}
     * 就搬到这里，一处不复制）：
     * <ul>
     *   <li>{@code caller} 为空 = 本次由<b>静态密钥</b>放行 → 落 {@code admin:<key 摘要 8>}，
     *       与 Step 3 之前<b>逐字节相同</b>（旧 curl 脚本 / SQL 手册零感知）；</li>
     *   <li>{@code caller} 非空 = 本次由<b>运营 JWT</b>放行，Controller 已传 {@code admin_user:<id>}，原样采用。</li>
     * </ul>
     * 判空用 isBlank 而非 null：闸门只会给 null 或 {@code admin_user:<id>}，但万一将来某个调用方漏传（传成空），
     * 退化的方向是「回到今天这枚 actor」而不是「审计行写成 null/空串」——列是 NOT NULL，空串会直接 1048 让整行审计写不进。
     * <p>本参数<b>只来自 Controller 的显式实参</b>：这里不去 request 属性里翻（BE-20-1 起 admin 面压根不注入
     * 用户属性，翻不到就是翻不到），也不读 ThreadLocal。
     */
    private String auditActor(String caller) {
        return caller == null || caller.isBlank() ? "admin:" + adminKeyDigest() : caller;
    }

    /** 管理密钥摘要（R8 敏感清单纪律）：SHA-256 取前 8 位十六进制，绝不明文落库/入日志 */
    private String adminKeyDigest() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(adminKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备 SHA-256，走不到；兜底也不回退成明文密钥
            return "unknown";
        }
    }

    /** 138****1234（逐字复刻 OrderServiceImpl#maskPhone；代开通 DTO @Pattern 保证 11 位，同既有三份实现不加守卫） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 操作来源 IP：Service 层拿不到 request，走 RequestContextHolder 取 remoteAddr；非请求线程返回 null（列可空） */
    private String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRemoteAddr();
        }
        return null;
    }

    /**
     * detail JSON 值序列化：null → null，其余交 Jackson writeValueAsString 全量转义。
     * S-4：旧实现只 replace 反斜杠/双引号，商家名里的 \n、\r、\t 及 <0x20 控制码会原样落进 detail，
     * 写出的审计记录是不可解析 JSON（引号已防结构注入，此项伤的是审计可读性）；Jackson 一并正确转义。
     */
    private String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return AUDIT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // String 序列化实际不可失败；兜底退回旧的仅转义反斜杠/双引号实现（调用方 writeAudit 另有 try-catch，
            // 绝不把异常抛出去打断主事务——审计是旁路，见 writeAudit 的 R6/M-04 注释）
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
