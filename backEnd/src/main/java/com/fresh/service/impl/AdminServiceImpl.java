package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fresh.common.BizException;
import com.fresh.common.PhoneMask;
import com.fresh.common.R;
import com.fresh.dto.AdminArrivalDepartDTO;
import com.fresh.dto.AdminDictGroupUpdateDTO;
import com.fresh.dto.AdminDictItemCreateDTO;
import com.fresh.dto.AdminDictItemUpdateDTO;
import com.fresh.dto.AdminDishUpdateDTO;
import com.fresh.dto.AdminMerchantCreateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.ArrivalBatch;
import com.fresh.entity.ArrivalItem;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.entity.Dish;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.MerchantProfile;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.entity.Store;
import com.fresh.entity.StoreApply;
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.ArrivalItemMapper;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantApplyMapper;
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
import com.fresh.service.DictService;
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
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
     * 审计 detail 列宽与预算口径（admin_audit_log.detail VARCHAR(500)，init.sql:383；BE-2 同型修复）：
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
    //      为什么落在 admin_audit_log：init.sql 里 ops_log 的表注释明写「本表只收商家/主理人/申请人现场动作
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
     * {@code target_id} 列宽（{@code admin_audit_log.target_id VARCHAR(64) NOT NULL}，init.sql:382）。
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

    /** F-106 片 A：商家自助入驻申请审核（只被 auditMerchantApply 使用） */
    @Autowired
    private MerchantApplyMapper merchantApplyMapper;

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
     * <p><b>幂等</b>（照 {@code updateOrderStatus:191-193}）：已是目标态 → 直接成功、<b>不重复 INSERT store</b>、
     * 不写第二行审计（无状态变化就不该有留痕，同 :221 那行的既有注释口径）。
     * 审核结论不可翻转（approved 再 reject / rejected 再 approve → 400）：翻转要连带处理「已发布的店」，
     * 那是二期 Admin 下架链的事，本期封死入口；rejected 后申请人重新提交=新行（D-21）。
     *
     * <p>审计 detail 里的联系电话只出 {@link PhoneMask#mask} 结果（RD：admin_audit_log 也不许落明文手机号）。
     */
    @Override
    @Transactional
    public void auditStoreApply(Long applyId, String action, String remark, String actor) {
        String op = action == null ? "" : action.trim();
        // 动作白名单复用 ALLOWED_AUDIT_ACTIONS（:80 那份，评价审核与本端点共用，不复制第二份集合）
        if (!ALLOWED_AUDIT_ACTIONS.contains(op)) {
            throw new BizException(R.CODE_BAD_REQUEST, "action 仅允许 approve（过审并发布门店）或 reject（驳回）");
        }
        String trimmedRemark = remark == null ? null : remark.trim();
        if (trimmedRemark != null && trimmedRemark.length() > 255) {
            // audit_remark VARCHAR(255)：严格模式下超长是 1406 而非 400，必须在写库前拦（同列宽纪律见本类 :85-94）
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
     * 商家自助入驻申请审核（F-106 片 A）：approve 同事务五步、顺序不可调换；reject 只置态 + 审计、不删行。
     *
     * <p><b>五步</b>：① 查申请（不存在 404；action 白名单 400；已 approved/rejected → 幂等成功直接返回）
     * → ② INSERT merchant（{@code contact_phone} 取申请人 {@code user.phone}，<b>绝不取申请表单</b>——
     * 表单可以由申请人替别人填号，user 行才是可信绑定）→ ③ INSERT merchant_profile(audit_status='approved')
     * → ④ 条件 UPDATE user（{@code WHERE id=? AND merchant_id IS NULL}，影响行数≠1 抛异常回滚，
     * 同 {@code createMerchant} 的 TOCTOU 姿势）→ ⑤ 条件 UPDATE 申请置 approved + admin_audit_log。
     *
     * <p><b>为什么第⑤步仍带 {@code status='pending'} 条件</b>：本仓未开 useAffectedRows，matched-rows 语义下
     * 少了这个条件，并发第二笔也会回 1 行、重复通过。带上后并发第二笔在此回 0 → 抛异常整体回滚，
     * 连第②③步刚插入的 merchant / merchant_profile 一起回滚，不留「已发布却没置态」的半态。
     *
     * <p><b>幂等</b>：已 approved/rejected 再审直接成功，不重复 INSERT merchant、不写第二行审计——
     * 契约 §0.2② 明确「已审核一律幂等返回」，与本类 {@code auditStoreApply} 的「翻转结论 400」不同，
     * 这里按商家申请契约执行，不复制 store_apply 的翻转判定。
     *
     * <p>审计 detail 只放状态与 merchantId/remark，<b>绝不放手机号</b>（RD：admin_audit_log 也不许落明文手机号）。
     */
    @Override
    @Transactional
    public void auditMerchantApply(Long applyId, String action, String remark, String actor) {
        String op = action == null ? "" : action.trim();
        // 动作白名单复用 ALLOWED_AUDIT_ACTIONS（:80 那份，评价审核与本端点共用，不复制第二份集合）
        if (!ALLOWED_AUDIT_ACTIONS.contains(op)) {
            throw new BizException(R.CODE_BAD_REQUEST, "action 仅允许 approve（过审并开通商家）或 reject（驳回）");
        }
        String trimmedRemark = remark == null ? null : remark.trim();
        if (trimmedRemark != null && trimmedRemark.isEmpty()) {
            trimmedRemark = null;
        }
        if (trimmedRemark != null && trimmedRemark.length() > 255) {
            // audit_remark VARCHAR(255)：严格模式下超长是 1406 而非 400，必须在写库前拦
            throw new BizException(R.CODE_BAD_REQUEST, "审核意见不能超过 255 字");
        }
        boolean approve = ACTION_APPROVE.equals(op);
        if (!approve && trimmedRemark == null) {
            // reject 必填：驳回原因要回给申请人看（契约 §0.2②）
            throw new BizException(R.CODE_BAD_REQUEST, "驳回时必须填写审核意见");
        }

        // ① 查申请 + 幂等：已 approved/rejected 直接成功（不再重复发布）
        MerchantApply apply = merchantApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BizException(R.CODE_NOT_FOUND, "申请不存在");
        }
        if (!APPLY_PENDING.equals(apply.getStatus())) {
            log.info("管理端商家入驻申请审核幂等命中: applyId={}, status={}", applyId, apply.getStatus());
            return;
        }

        if (approve) {
            // ② INSERT merchant：contact_phone 取申请人 user.phone，不取申请表单
            User applicant = userMapper.selectById(apply.getUserId());
            if (applicant == null) {
                // 逻辑外键失配（user 被清）时不允许凭空造出无主商家；抛异常回滚
                throw new BizException(R.CODE_BAD_REQUEST, "申请人不存在，无法开通商家");
            }
            Merchant m = new Merchant();
            m.setName(apply.getMerchantName());
            m.setContactPhone(applicant.getPhone());
            m.setContactName(apply.getContactName());
            m.setStatus("active");
            merchantMapper.insert(m);                       // 自增 id 回填 m.getId()

            // ③ INSERT merchant_profile(approved)
            MerchantProfile mp = new MerchantProfile();
            mp.setMerchantId(m.getId());
            // F-107：证照列表原样复制；单图 business_license 取第一条营业执照 url（解析不出回退申请行原值），
            // 既有「单图」读路径继续可用，出参形状零改动。
            mp.setBusinessLicense(firstBusinessLicenseUrl(apply.getLicenses(), apply.getBusinessLicense()));
            mp.setLicenses(apply.getLicenses());
            mp.setLegalPerson(apply.getLegalPerson());
            mp.setAddress(apply.getAddress());
            mp.setSettlementAccount(apply.getSettlementAccount());
            mp.setAuditStatus(APPLY_APPROVED);
            mp.setAuditedAt(LocalDateTime.now());
            merchantProfileMapper.insert(mp);

            // ④ 条件 UPDATE user：真正的防线是 WHERE merchant_id IS NULL；影响行数≠1 → 抛异常回滚
            int rows;
            try {
                rows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                        .eq(User::getId, apply.getUserId())
                        .isNull(User::getMerchantId)
                        .set(User::getRole, "merchant")
                        .set(User::getMerchantId, m.getId()));
            } catch (DuplicateKeyException e) {
                // 残留兜底：同一 user 被并发绑到同一 merchant_id 时仍撞 uk_user_merchant
                throw new BizException(R.CODE_BAD_REQUEST, "该用户已绑定商家，请刷新后重试");
            }
            if (rows != 1) {
                throw new BizException(R.CODE_BAD_REQUEST, "该用户已绑定商家，请刷新后重试");
            }

            // ⑤ 置申请态（条件 UPDATE status='pending'）+ 审计
            int applyRows = merchantApplyMapper.update(null, new LambdaUpdateWrapper<MerchantApply>()
                    .eq(MerchantApply::getId, applyId)
                    .eq(MerchantApply::getStatus, APPLY_PENDING)
                    .set(MerchantApply::getStatus, APPLY_APPROVED)
                    .set(trimmedRemark != null, MerchantApply::getAuditRemark, trimmedRemark)
                    .set(MerchantApply::getAuditedAt, LocalDateTime.now()));
            if (applyRows != 1) {
                throw new BizException(R.CODE_BAD_REQUEST, "该申请已审核完成，请刷新后重试");
            }
            writeAudit(auditActor(actor), "merchant_apply.audit", "merchant_apply", String.valueOf(applyId),
                    "status=" + apply.getStatus(),
                    "status=" + APPLY_APPROVED + ",merchantId=" + m.getId() + ",remark=" + trimmedRemark);
            log.info("管理端商家入驻申请过审: applyId={}, applicantUserId={}, merchantId={}",
                    applyId, apply.getUserId(), m.getId());
            return;
        }

        // reject：只置态 + 审计，不删除申请行（留档；pending_guard 变 NULL 后该用户可再次申请）
        int applyRows = merchantApplyMapper.update(null, new LambdaUpdateWrapper<MerchantApply>()
                .eq(MerchantApply::getId, applyId)
                .eq(MerchantApply::getStatus, APPLY_PENDING)
                .set(MerchantApply::getStatus, APPLY_REJECTED)
                .set(MerchantApply::getAuditRemark, trimmedRemark)
                .set(MerchantApply::getAuditedAt, LocalDateTime.now()));
        if (applyRows != 1) {
            throw new BizException(R.CODE_BAD_REQUEST, "该申请已审核完成，请刷新后重试");
        }
        writeAudit(auditActor(actor), "merchant_apply.audit", "merchant_apply", String.valueOf(applyId),
                "status=" + apply.getStatus(),
                "status=" + APPLY_REJECTED + ",remark=" + trimmedRemark);
        log.info("管理端商家入驻申请驳回: applyId={}, applicantUserId={}", applyId, apply.getUserId());
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

    // ==================================================================
    // F-63 运营端「发车」：本类第六个写方法，也是 arrival_batch 唯一的运行期创建方
    //（平台统一配货模型下，中央仓就是平台自己，运营点发车业务上有资格）。
    // 新依赖与常量一律追加在文件末尾、中段零改动——本类被仓内行号指针引用，
    // 净零行变化的纪律见 NOTIFY_REJECT_ACTION 那条注释原文（本次唯一例外是 import 段加行）。
    // ==================================================================

    /** 发车后的批次态（决策点 1：发车即 arriving，不提供 planned 入口；arriving 此前是运行期不可达态） */
    private static final String BATCH_STATUS_ARRIVING = "arriving";

    /** 可发车的既有批次态（幂等重算明细，决策点 3 的一半）；与 ArrivalBatchMapper#markArriving 的 IN 白名单逐字相同 */
    private static final Set<String> BATCH_STATUSES_DEPARTABLE = Set.of("planned", BATCH_STATUS_ARRIVING);

    /** 货已收/已登短少的态：再发车是造假，400 拒（决策点 3 的另一半；short 至今没有任何运行期写入方） */
    private static final Set<String> BATCH_STATUSES_CLOSED = Set.of("confirmed", "short");

    /** 批次日期形状闸门（与商家面、v2 面同一枚正则，不另立口径） */
    private static final Pattern BATCH_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 400 脏参文案（RF 红线：逐字复用 GlobalExceptionHandler 已有的那句，不新增句子） */
    private static final String BAD_REQUEST_MSG = "请求参数有误，请检查后重试";

    /** F-63 发车链的两个新依赖（只被下面两个方法使用；追加式写法同上方 F-18.12 那批） */
    @Autowired
    private ArrivalBatchMapper arrivalBatchMapper;
    @Autowired
    private ArrivalItemMapper arrivalItemMapper;

    /**
     * 发车只读预览（F-63）：判据与出参形状的完整口径见接口注释。只读不写审计（RF），
     * 也不进事务——三条 SELECT 读到的既有态本来就允许在响应后立刻被别人改写，快照一致性不是本片的要求。
     */
    @Override
    public Map<String, Object> arrivalPreview(Long storeId, String date) {
        LocalDate day = parseBatchDate(date);
        Store store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new BizException(R.CODE_NOT_FOUND, "门店不存在");
        }
        List<Map<String, Object>> batches = arrivalBatchMapper.selectBatchesByStoreAndDate(storeId, day);
        Map<String, Object> existing = null;
        // departable 必须与 departArrival 的拒判据**同集合**：日期这一维此前只落在 depart 侧，
        // 于是过去日期会得到「预览说可发车、点下去回 400」的自相矛盾。停业不在这里判——
        // 全站对 closed 的口径是「前端置灰、服务端不校验」（Store 类注释原文），此处不破例。
        boolean departable = !day.isBefore(LocalDate.now());
        if (!batches.isEmpty()) {
            Map<String, Object> batch = batches.get(0);
            String status = (String) batch.get("status");
            departable = departable && BATCH_STATUSES_DEPARTABLE.contains(status);
            existing = new LinkedHashMap<>();
            existing.put("id", batch.get("id"));
            existing.put("status", status);
            existing.put("confirmTime", batch.get("confirmTime"));
            existing.put("itemCount", arrivalItemMapper.sumExpectedQtyByBatchId(toLong(batch.get("id"))));
        }
        List<Map<String, Object>> planRows = orderItemMapper.selectArrivalPlanByStore(day, storeId);
        List<Map<String, Object>> plan = new ArrayList<>(planRows.size());
        for (Map<String, Object> row : planRows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("dishId", row.get("dishId"));
            line.put("dishName", row.get("dishName"));
            line.put("qty", toInteger(row.get("quantity")));
            plan.add(line);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeId", storeId);
        out.put("storeName", store.getName());
        out.put("date", day.toString());
        out.put("existing", existing);
        out.put("plan", plan);
        out.put("orderCount", orderItemMapper.countArrivalOrdersForStore(day, storeId));
        out.put("dishKinds", plan.size());
        out.put("departable", departable);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> departArrival(AdminArrivalDepartDTO dto, String actor) {
        LocalDate day = parseBatchDate(dto.getBatchDate());
        // ②「早于今天」在一切查库之前：日期过期是请求自身的缺陷，不该先烧一次 SELECT 再拒（单测钉死）
        if (day.isBefore(LocalDate.now())) {
            throw new BizException("批次日期不能早于今天");
        }
        // ③ 批次归属与出参店名都钉在门店行上，查无即 404，不往下花任何查询
        Store store = storeMapper.selectById(dto.getStoreId());
        if (store == null) {
            throw new BizException(R.CODE_NOT_FOUND, "门店不存在");
        }
        // ④ uk_store_date 保证一店一日至多一行：先查后插（第 ⑥ 步的 INSERT 以这次读为前提）
        List<Map<String, Object>> batches =
                arrivalBatchMapper.selectBatchesByStoreAndDate(dto.getStoreId(), day);
        Map<String, Object> batch = batches.isEmpty() ? null : batches.get(0);
        if (batch != null && BATCH_STATUSES_CLOSED.contains((String) batch.get("status"))) {
            throw new BizException("该店该日货已收货或已登记短少，不可再发车");
        }
        // ⑤ 应发数唯一来源 = 该店该日订单明细按菜汇总（决策点 2，不收数字入参；口径见接口注释的一致性声明）
        List<Map<String, Object>> planRows = orderItemMapper.selectArrivalPlanByStore(day, dto.getStoreId());
        if (planRows.isEmpty()) {
            throw new BizException("该店该日没有可发车的订单");
        }
        // 店级去重单数走孪生条：按 dish 分组行里的「含该菜订单数」会把一笔多菜订单在多行各计一次，
        // 拿它求和当单数就是虚报（OrderItemMapper 上方登记过的坑）
        long orderCount = orderItemMapper.countArrivalOrdersForStore(day, dto.getStoreId());

        // ⑥ 写批次：查无才插；已有 planned/arriving 则翻到 arriving 后幂等重算明细
        Long batchId;
        boolean idempotent = batch != null;
        if (idempotent) {
            batchId = toLong(batch.get("id"));
            // 种子行的 planned 也借发车翻到 arriving（发车直达 arriving，不提供 planned 入口）；
            // 条件白名单挡的是「第 ④ 步读过后、并发窗口里刚被主理人确认到货」——0 行与第 ④ 步同判据同文案
            if (arrivalBatchMapper.markArriving(batchId) == 0) {
                throw new BizException("该店该日货已收货或已登记短少，不可再发车");
            }
        } else {
            ArrivalBatch newBatch = new ArrivalBatch();
            newBatch.setStoreId(dto.getStoreId());
            newBatch.setBatchDate(day);
            newBatch.setStatus(BATCH_STATUS_ARRIVING);
            // remark 留白归一为 null：null 字段不进 INSERT（MP 默认 NOT_NULL 策略）→ 列走 DEFAULT NULL，
            // 空串不是「没填」，是「填了个空」，这一区分留给主理人端批次页的展示
            newBatch.setRemark(dto.getRemark() == null || dto.getRemark().isBlank()
                    ? null : dto.getRemark().trim());
            try {
                arrivalBatchMapper.insert(newBatch);   // 自增 id 回填 newBatch.getId()
            } catch (DuplicateKeyException e) {
                // 撞 uk_store_date 的唯一可能：④ 查无之后、INSERT 之前被人抢先建了同店同日批次——
                // 如实 400 让操作者刷新重看现场，不吞异常、更不改成「替别人重算明细」（那是越权覆盖）
                throw new BizException("该店该日已有批次，请刷新后重试");
            }
            batchId = newBatch.getId();
        }

        List<ArrivalItem> items = new ArrayList<>(planRows.size());
        int itemCount = 0;
        for (Map<String, Object> row : planRows) {
            ArrivalItem item = new ArrivalItem();
            item.setBatchId(batchId);
            item.setDishId(toLong(row.get("dishId")));
            item.setExpectedQty(toInteger(row.get("quantity")));
            items.add(item);
            itemCount += item.getExpectedQty();
        }
        // 明细幂等重算 = 按批次删 + 一条 foreach 批量插：本表无唯一键，撞不了键也防不住并发撞键，
        // 只能先删后插（硬要求：禁止循环单条 INSERT，那是 N+1）
        arrivalItemMapper.deleteByBatchId(batchId);
        arrivalItemMapper.insertBatchItems(items);

        log.info("管理端发车: batchId={}, storeId={}, batchDate={}, itemCount={}, dishKinds={}, orderCount={}, idempotent={}",
                batchId, dto.getStoreId(), day, itemCount, planRows.size(), orderCount, idempotent);

        // 兜底 detail 传它自己（recordNotifyReject 同纪律）：四键定长短值、离列宽 500 远着呢，
        // 真走到第二趟也必须保持同一形状，换成 {"old","new"} 就把契约钉死的 detail 形状破了
        String detail = departAuditDetail(dto.getStoreId(), day, itemCount, idempotent);
        writeAudit(auditActor(actor), "arrival.depart", "arrival_batch", String.valueOf(batchId),
                detail, detail, currentIp());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("status", BATCH_STATUS_ARRIVING);
        out.put("itemCount", itemCount);
        out.put("dishKinds", planRows.size());
        out.put("orderCount", orderCount);
        out.put("idempotent", idempotent);
        return out;
    }

    /**
     * 批次日期解析：形状闸门 + 交 LocalDate 裁决真日期（2026-02-30 这类假日期 400），
     * 年小于 1 也挡在 Java 层——MySQL 的 DATE 没有年 0，放 0000 一族进 SQL 比较是 500 不是 400
     * （F-20.29 BE-G6-4 同纪律）。BizException 是 RuntimeException，不会被下面的 catch 吞掉重包。
     */
    private LocalDate parseBatchDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!BATCH_DATE_PATTERN.matcher(value).matches()) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) {
                throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
            }
            return parsed;
        } catch (DateTimeException e) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
    }

    /** 发车审计 detail：契约钉死四键（store / date / items / idempotent），全定值短串，上界远小于列宽 500 */
    private String departAuditDetail(Long storeId, LocalDate day, int itemCount, boolean idempotent) {
        ObjectNode detail = AUDIT_MAPPER.createObjectNode();
        detail.put("store", storeId);
        detail.put("date", day.toString());
        detail.put("items", itemCount);
        detail.put("idempotent", idempotent);
        return detail.toString();
    }

    // ==================================================================
    // F-106 片 B2：运营端商家入驻申请列表（GET /api/admin/merchant-applies）。
    // 追加式落码（常量 + 方法都在文件末尾），理由同 F-63 段注释：本类被仓内行号指针引用。
    // ==================================================================

    /** 列表状态白名单（契约 §0.6 逐字三态；与 AdminV2ServiceImpl 的 store-applies 同集合、同语义） */
    private static final Set<String> MERCHANT_APPLY_LIST_STATUSES = Set.of("pending", "approved", "rejected");

    /** 分页缺省与值域（照 AdminV2ServiceImpl 的列表族：缺省 1/20，pageNum≥1，1≤pageSize≤100） */
    private static final int LIST_DEFAULT_PAGE_NUM = 1;
    private static final int LIST_DEFAULT_PAGE_SIZE = 20;
    private static final int LIST_MIN_PAGE_SIZE = 1;
    private static final int LIST_MAX_PAGE_SIZE = 100;

    /**
     * 运营端商家入驻申请列表（F-106 片 B2）：参数闸门、排序与出参口径见接口注释。
     *
     * <p><b>分页</b>：本工程无 MP 分页插件，页 + 独立 COUNT 两条手写 SQL（RB 红线同 {@code AdminV2ServiceImpl}）；
     * {@code pageNum}/{@code pageSize} 越界与脏值一律 400、<b>不夹取</b>，offset 用 {@code long} 防 int 溢出。
     * <p><b>status</b>：null 或纯空白 = 全部（契约「空=全部」，前端 {@code ?status=} 直接可用）；
     * 非空先 {@code Locale.ROOT} 归一小写再过三态白名单（照 v2 列表族 BE-G6-2），
     * {@code filters.status} 回显归一后真正生效的那一枚。
     * <p><b>脱敏</b>：{@code applicantPhone} 是 {@code user.phone} 的原文（LEFT JOIN 取数），
     * 出参前<b>必须</b>过 {@link PhoneMask#mask}；本方法不产生第二份掩码实现。申请人行不存在时该键为 null。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMerchantApplies(Integer pageNum, Integer pageSize, String status) {
        int pageNo = pageNum == null ? LIST_DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < LIST_DEFAULT_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        int size = pageSize == null ? LIST_DEFAULT_PAGE_SIZE : pageSize;
        if (size < LIST_MIN_PAGE_SIZE || size > LIST_MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }

        // 空串/纯空白 = 未筛选（契约「status 可空，空=全部」）；大小写归一后才进白名单，
        // 与 AdminV2ServiceImpl#storeApplies 的 BE-G6-2 口径一致。
        String fStatus = status == null ? null : status.trim();
        if (fStatus != null && fStatus.isEmpty()) {
            fStatus = null;
        }
        if (fStatus != null) {
            fStatus = fStatus.toLowerCase(Locale.ROOT);
            if (!MERCHANT_APPLY_LIST_STATUSES.contains(fStatus)) {
                throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
            }
        }

        long total = merchantApplyMapper.countMerchantApplies(fStatus);
        long offset = (long) (pageNo - 1) * size;
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : merchantApplyMapper.selectMerchantAppliesPage(fStatus, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // 十四键恒定存在（契约定序 + F-107 licenses）；applicantPhone 是唯一需要再加工的一列
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("userId", row.get("userId"));
            line.put("merchantName", row.get("merchantName"));
            line.put("contactName", row.get("contactName"));
            line.put("businessLicense", row.get("businessLicense"));
            // F-107：证照列表字符串原样出参（不 parse、不脱敏），前端自己解析
            line.put("licenses", row.get("licenses"));
            line.put("legalPerson", row.get("legalPerson"));
            line.put("address", row.get("address"));
            line.put("settlementAccount", row.get("settlementAccount"));
            line.put("status", row.get("status"));
            line.put("auditRemark", row.get("auditRemark"));
            line.put("createdAt", row.get("createdAt"));
            line.put("auditedAt", row.get("auditedAt"));
            line.put("applicantPhone", PhoneMask.mask((String) row.get("applicantPhone")));
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("status", fStatus);

        // 键名与键序逐字照 AdminV2ServiceImpl#pageResult（不新造形状）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        data.put("filters", filters);
        return data;
    }

    /** Map 结果集 BIGINT 列归一（照 PartnerStoreServiceImpl 的既有写法）：非 Number 即缺列，回 null 交调用方定性 */
    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 聚合列转 Integer：SUM 在 MySQL 下回 BigDecimal、COUNT 回 Long，绝不假设 Integer（同 MerchantOrderServiceImpl#toInteger 的理由） */
    private static int toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    // ==================================================================
    // F-107 多证照：approve 复制路径的解析兜底（追加式落码在文件末尾，理由同 F-106 片 B2 段注释）
    // ==================================================================

    /**
     * 从申请行 {@code licenses} JSON 里取「第一条 type=business_license 的 url」（F-107 §0.3⑤）。
     *
     * <p>licenses 为空、非法 JSON、非数组、数组里没有营业执照、url 非文本/空白——任一情况都回退
     * 申请行原有的单图 {@code business_license}。<b>绝不因证照 JSON 脏而阻断过审</b>：提交侧已按
     * §0.3 拦过，这里只是复制路径的兜底（老数据/手工写库都可能出现脏串）。
     *
     * <p>类型比对与提交侧同口径（trim + equalsIgnoreCase），不建第二套枚举。
     */
    private static String firstBusinessLicenseUrl(String licensesJson, String fallback) {
        if (licensesJson == null || licensesJson.isBlank()) {
            return fallback;
        }
        try {
            JsonNode root = AUDIT_MAPPER.readTree(licensesJson);
            if (root != null && root.isArray()) {
                for (JsonNode item : root) {
                    if (item == null || !item.isObject()) {
                        continue;
                    }
                    JsonNode type = item.get("type");
                    JsonNode url = item.get("url");
                    if (type != null && type.isTextual()
                            && "business_license".equalsIgnoreCase(type.asText().trim())
                            && url != null && url.isTextual()) {
                        String candidate = url.asText().trim();
                        if (!candidate.isEmpty()) {
                            return candidate;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 不打印 licenses 原文（无必要扩大日志面），只留回退事实；不影响过审
            log.warn("商家入驻申请 licenses 无法解析，merchant_profile.business_license 回退申请行原值");
        }
        return fallback;
    }

    // ==================================================================
    // F-108 §0.7 片 2：字典管理端 CRUD + 引用保护（六个端点）。
    // 追加式落码在文件末尾（常量 + 依赖 + 方法），理由同 F-63/F-106 片 B2 段注释。
    // 本组方法刻意<b>不加 @Transactional</b>：每个写操作只有一条业务 DML，先提交再 evict，
    // cache-aside 的顺序天然正确（事务内 evict 会让并发读在提交前把旧值重新写回 Caffeine）；
    // 审计走本类唯一那份 writeAudit（旁路，写失败不打断主流程）。
    // ==================================================================

    /** 唯一做引用保护的组（契约「只做 dish_category，其余组允许删并在页面显式提示」） */
    private static final String DICT_GROUP_DISH_CATEGORY = "dish_category";

    /** dish_category 词条 definition 的取值前缀（definition 形如 code:meat） */
    private static final String DICT_CATEGORY_CODE_PREFIX = "code:";

    /** uk_group_word 冲突的 400 文案（一句中文；不把 DB 错误串透给前端） */
    private static final String DICT_WORD_DUPLICATE_MSG = "该分组下已存在同名词条";

    /** 分组不存在：读/建端点 400、改分组端点 404（契约 §0.7 两处定性不同，文案同一句） */
    private static final String DICT_GROUP_NOT_FOUND_MSG = "字典分组不存在";

    /** 词条不存在（PUT/DELETE 的 404） */
    private static final String DICT_ITEM_NOT_FOUND_MSG = "词条不存在";

    /** 审计动作（契约 §0.7 逐字四枚；写侧唯一真源） */
    private static final String AUDIT_DICT_ITEM_CREATE = "dict.item.create";
    private static final String AUDIT_DICT_ITEM_UPDATE = "dict.item.update";
    private static final String AUDIT_DICT_ITEM_DELETE = "dict.item.delete";
    private static final String AUDIT_DICT_GROUP_UPDATE = "dict.group.update";

    /** 审计目标类型（admin_audit_log.target_type VARCHAR(30)） */
    private static final String AUDIT_TARGET_DICT_ITEM = "dict_item";
    private static final String AUDIT_TARGET_DICT_GROUP = "dict_group";

    /** F-108 片 2 的三个新依赖（只被本段方法使用；追加式字段声明同 F-18.12/F-63 那批） */
    @Autowired
    private DictGroupMapper dictGroupMapper;
    @Autowired
    private DictItemMapper dictItemMapper;
    /** 写后自动 evict：只清该组 Caffeine，不做上游强制重拉（同 §0.4 语义） */
    @Autowired
    private DictService dictService;

    /**
     * 字典分组列表（F-108 §0.7）：一次分组查询 + 一次 GROUP BY 计数，不按组循环查。
     * 出参键序固定 {@code code,name,enabled,itemCount,sort}（LinkedHashMap 保序）；
     * 无词条的组 {@code itemCount=0}，NULL 列归一成 0。
     */
    @Override
    public List<Map<String, Object>> dictGroups() {
        List<DictGroup> groups = dictGroupMapper.selectAllOrdered();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : dictItemMapper.countByGroup()) {
            Object code = row.get("groupCode");
            if (code != null) {
                counts.put(String.valueOf(code), toInteger(row.get("itemCount")));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(groups.size());
        for (DictGroup group : groups) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("code", group.getCode());
            line.put("name", group.getName());
            line.put("enabled", group.getEnabled() == null ? 0 : group.getEnabled());
            line.put("itemCount", counts.getOrDefault(group.getCode(), 0));
            line.put("sort", group.getSort() == null ? 0 : group.getSort());
            out.add(line);
        }
        return out;
    }

    /**
     * 某分组全部词条（不分页）：出参 {@code [{id,word,definition,sortOrder}]}，顺序取 mapper 的
     * {@code sort_order DESC, id ASC}（与 C 端读路径同口径）。definition 原样出参（可为 null）。
     * 分组不存在 → 400（与 evict 端点同一句文案）。
     */
    @Override
    public List<Map<String, Object>> dictItems(String group) {
        requireDictGroup(group);
        List<DictItem> items = dictItemMapper.selectByGroup(group);
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (DictItem item : items) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", item.getId());
            line.put("word", item.getWord());
            line.put("definition", item.getDefinition());
            line.put("sortOrder", item.getSortOrder() == null ? 0 : item.getSortOrder());
            out.add(line);
        }
        return out;
    }

    /**
     * 新增词条：分组不存在 400；word trim 后必空校验；definition 可空（trim 后入库）；
     * sortOrder 缺省 0。撞 {@code uk_group_word} → 400 一句中文。
     * 成功后 evict(group) 并写 {@code dict.item.create}。
     */
    @Override
    public void createDictItem(String group, AdminDictItemCreateDTO dto, String actor) {
        requireDictGroup(group);
        if (dto == null) {
            throw new BizException("词条名称不能为空");
        }
        DictItem item = new DictItem();
        item.setGroupCode(group);
        item.setWord(requiredDictWord(dto.getWord()));
        item.setDefinition(normalizeDictDefinition(dto.getDefinition()));
        item.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        try {
            dictItemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // uk_group_word(group_code, word) 的并发兜底：先查后插挡不住两人同时提交同名词条
            throw new BizException(DICT_WORD_DUPLICATE_MSG);
        }
        dictService.evict(group);
        writeAudit(auditActor(actor), AUDIT_DICT_ITEM_CREATE, AUDIT_TARGET_DICT_ITEM,
                String.valueOf(item.getId()), null, dictItemDetail(item));
    }

    /**
     * 部分更新词条：id 不存在 404；null 字段表示本次不改（MyBatis-Plus updateById 的 NOT_NULL 策略）；
     * 三列全空 400；word 传空白串仍按 400「词条名称不能为空」。
     * 改后撞 {@code uk_group_word} → 400。成功后 evict(该词条所属组) 并写 {@code dict.item.update}。
     */
    @Override
    public void updateDictItem(Long id, AdminDictItemUpdateDTO dto, String actor) {
        DictItem existing = id == null ? null : dictItemMapper.selectById(id);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, DICT_ITEM_NOT_FOUND_MSG);
        }
        if (dto == null) {
            throw new BizException("无更新字段（可传 word / definition / sortOrder）");
        }
        String word = optionalDictWord(dto.getWord());
        String definition = normalizeDictDefinition(dto.getDefinition());
        if (word == null && definition == null && dto.getSortOrder() == null) {
            throw new BizException("无更新字段（可传 word / definition / sortOrder）");
        }
        DictItem patch = new DictItem();
        patch.setId(id);
        if (word != null) {
            patch.setWord(word);
        }
        if (definition != null) {
            patch.setDefinition(definition);
        }
        if (dto.getSortOrder() != null) {
            patch.setSortOrder(dto.getSortOrder());
        }
        try {
            dictItemMapper.updateById(patch);
        } catch (DuplicateKeyException e) {
            throw new BizException(DICT_WORD_DUPLICATE_MSG);
        }
        dictService.evict(existing.getGroupCode());
        writeAudit(auditActor(actor), AUDIT_DICT_ITEM_UPDATE, AUDIT_TARGET_DICT_ITEM, String.valueOf(id),
                dictItemDetail(existing),
                dictItemDetail(word != null ? word : existing.getWord(),
                        definition != null ? definition : existing.getDefinition(),
                        dto.getSortOrder() != null ? dto.getSortOrder() : existing.getSortOrder()));
    }

    /**
     * 删除词条：id 不存在 404；{@code dish_category} 组先做引用保护（见
     * {@link #protectDishCategoryReference}），其余组直接删。
     * 成功后 evict(该词条所属组) 并写 {@code dict.item.delete}。
     */
    @Override
    public void deleteDictItem(Long id, String actor) {
        DictItem item = id == null ? null : dictItemMapper.selectById(id);
        if (item == null) {
            throw new BizException(R.CODE_NOT_FOUND, DICT_ITEM_NOT_FOUND_MSG);
        }
        protectDishCategoryReference(item);
        int removed = dictItemMapper.deleteById(id);
        if (removed == 0) {
            // 查无之后、删除之前被并发删掉：与「id 不存在」同一出口，且不写审计（没删掉任何行）
            throw new BizException(R.CODE_NOT_FOUND, DICT_ITEM_NOT_FOUND_MSG);
        }
        dictService.evict(item.getGroupCode());
        writeAudit(auditActor(actor), AUDIT_DICT_ITEM_DELETE, AUDIT_TARGET_DICT_ITEM, String.valueOf(id),
                dictItemDetail(item), "deleted");
    }

    /**
     * 部分更新分组：code 不存在 404（与读/建端点的 400 不同，契约钉死）；{@code name}/{@code enabled}
     * 全空 400；name 空白串 400；enabled 仅 0/1。成功后 evict(code) 并写 {@code dict.group.update}。
     * 停用后 {@code GET /api/dict} 直接返空（读路径的 enabled 闸门在缓存之前，无需额外失效动作）。
     */
    @Override
    public void updateDictGroup(String code, AdminDictGroupUpdateDTO dto, String actor) {
        DictGroup existing = code == null ? null : dictGroupMapper.selectById(code);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, DICT_GROUP_NOT_FOUND_MSG);
        }
        if (dto == null) {
            throw new BizException("无更新字段（可传 name / enabled）");
        }
        String name = optionalDictGroupName(dto.getName());
        Integer enabled = dto.getEnabled();
        if (enabled != null && enabled != 0 && enabled != 1) {
            throw new BizException("enabled 仅允许 0（停用）或 1（启用）");
        }
        if (name == null && enabled == null) {
            throw new BizException("无更新字段（可传 name / enabled）");
        }
        DictGroup patch = new DictGroup();
        patch.setCode(code);
        if (name != null) {
            patch.setName(name);
        }
        if (enabled != null) {
            patch.setEnabled(enabled);
        }
        dictGroupMapper.updateById(patch);
        dictService.evict(code);
        writeAudit(auditActor(actor), AUDIT_DICT_GROUP_UPDATE, AUDIT_TARGET_DICT_GROUP, code,
                dictGroupDetail(existing),
                dictGroupDetail(name != null ? name : existing.getName(),
                        enabled != null ? enabled : existing.getEnabled()));
    }

    /** 分组存在性闸门（400）：列表/新增词条共用；改分组端点的 404 在 updateDictGroup 内单独判。 */
    private DictGroup requireDictGroup(String group) {
        DictGroup row = group == null ? null : dictGroupMapper.selectById(group);
        if (row == null) {
            throw new BizException(DICT_GROUP_NOT_FOUND_MSG);
        }
        return row;
    }

    /** 新增时的 word：null/空白 → 400；trim 后超列宽（VARCHAR(100)）→ 400（不让 1406 变 500） */
    private static String requiredDictWord(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new BizException("词条名称不能为空");
        }
        return checkDictWordLength(raw.trim());
    }

    /** 更新时的 word：null = 不改；传了就必须是非空且不超列宽（空白串 400，不是「不传」） */
    private static String optionalDictWord(String raw) {
        return raw == null ? null : requiredDictWord(raw);
    }

    /** 词条列宽校验（word VARCHAR(100)） */
    private static String checkDictWordLength(String word) {
        if (word.length() > 100) {
            throw new BizException("词条名称最长 100 个字符");
        }
        return word;
    }

    /** definition：null = 不改/不填；trim 后入库；空串允许（等价清空）；超列宽 500 → 400 */
    private static String normalizeDictDefinition(String raw) {
        if (raw == null) {
            return null;
        }
        String definition = raw.trim();
        if (definition.length() > 500) {
            throw new BizException("词条定义最长 500 个字符");
        }
        return definition;
    }

    /** 分组名：null = 不改；传了就必须是非空白且不超列宽（VARCHAR(50)） */
    private static String optionalDictGroupName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw new BizException("分组名称不能为空");
        }
        if (name.length() > 50) {
            throw new BizException("分组名称最长 50 个字符");
        }
        return name;
    }

    /**
     * dish_category 引用保护（契约 §0.7 唯一做保护的一组）：definition 里 {@code code:} 之后的部分
     * 就是 {@code dish.category} 的取值，查 {@code SELECT COUNT(*) FROM dish WHERE category=?}。
     * <p>definition 为 null / 不含 {@code code:} / 前缀后为空 → 视为不指向任何分类（0 引用），允许删。
     * 只有 count&gt;0 才 400；其余组（coupon_template / kingkong_entry / service_tags）直接放行，
     * 由运营页显式提示「该组无引用保护，删除后前端会回退 FALLBACK」。
     */
    private void protectDishCategoryReference(DictItem item) {
        if (!DICT_GROUP_DISH_CATEGORY.equals(item.getGroupCode())) {
            return;
        }
        String category = dishCategoryCode(item.getDefinition());
        if (category == null) {
            return;
        }
        long count = dishMapper.countByCategory(category);
        if (count > 0) {
            // 逐字照契约：「该分类下还有 N 个商品，删除后它们会变成无主分类」
            throw new BizException("该分类下还有 " + count + " 个商品，删除后它们会变成无主分类");
        }
    }

    /** 取 definition 里 {@code code:} 之后的部分并 trim；无法解析回 null（= 不指向分类，放行删除） */
    private static String dishCategoryCode(String definition) {
        if (definition == null) {
            return null;
        }
        int index = definition.indexOf(DICT_CATEGORY_CODE_PREFIX);
        if (index < 0) {
            return null;
        }
        String code = definition.substring(index + DICT_CATEGORY_CODE_PREFIX.length()).trim();
        return code.isEmpty() ? null : code;
    }

    /** 审计 detail（词条）：word/definition/sortOrder 三段短串；超 500 由 writeAudit 的既有预算截断兜底 */
    private static String dictItemDetail(DictItem item) {
        return dictItemDetail(item.getWord(), item.getDefinition(), item.getSortOrder());
    }

    private static String dictItemDetail(String word, String definition, Integer sortOrder) {
        return "word=" + word + ",definition=" + definition + ",sortOrder=" + sortOrder;
    }

    /** 审计 detail（分组）：name/enabled 两段短串 */
    private static String dictGroupDetail(DictGroup group) {
        return dictGroupDetail(group.getName(), group.getEnabled());
    }

    private static String dictGroupDetail(String name, Integer enabled) {
        return "name=" + name + ",enabled=" + enabled;
    }
}
