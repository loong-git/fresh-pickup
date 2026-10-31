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
import com.fresh.dto.AdminSeckillUpdateDTO;
import com.fresh.dto.AdminSettlementGenerateDTO;
import com.fresh.dto.AdminStoreUpdateDTO;
import com.fresh.entity.AdminAuditLog;
import com.fresh.entity.ArrivalBatch;
import com.fresh.entity.ArrivalItem;
import com.fresh.entity.CouponTemplate;
import com.fresh.entity.DictGroup;
import com.fresh.entity.DictItem;
import com.fresh.entity.Dish;
import com.fresh.entity.DishAlias;
import com.fresh.entity.FreeActivity;
import com.fresh.entity.FreeGoodsPool;
import com.fresh.entity.Merchant;
import com.fresh.entity.MerchantApply;
import com.fresh.entity.MerchantProfile;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Review;
import com.fresh.entity.Settlement;
import com.fresh.entity.Store;
import com.fresh.entity.StoreApply;
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.ArrivalBatchMapper;
import com.fresh.mapper.ArrivalItemMapper;
import com.fresh.mapper.DictGroupMapper;
import com.fresh.mapper.DictItemMapper;
import com.fresh.mapper.DishAliasMapper;
import com.fresh.mapper.CouponTemplateMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.FreeActivityMapper;
import com.fresh.mapper.FreeGoodsPoolMapper;
import com.fresh.mapper.MerchantApplyMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
import com.fresh.mapper.SettlementMapper;
import com.fresh.mapper.StoreApplyMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.AdminService;
import com.fresh.service.CouponService;
import com.fresh.service.DictService;
import com.fresh.service.PartnerWalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
     * 审计 detail 列宽与预算口径（admin_audit_log.detail VARCHAR(500)，见 init.sql 的 admin_audit_log.detail 列；BE-2 同型修复）：
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
     * {@code target_id} 列宽（{@code admin_audit_log.target_id VARCHAR(64) NOT NULL}，见 init.sql 的 admin_audit_log.target_id 列）。
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
        // F-117 供货价（三态语义见 AdminDishUpdateDTO 类注释）：键出现（present=true）才参与 SET——
        // 显式 null 清除回 NULL（运营弹窗「留空=清除」）、带值设置（>0 两位小数由 @DecimalMin/@Digits 在绑定时拦）；
        // 键缺失（present=false，既有调用方/历史脚本）零影响
        if (dto.isSupplyPricePresent()) {
            wrapper.set(Dish::getSupplyPrice, dto.getSupplyPrice());
            setCount++;
        }
        if (setCount == 0) {
            throw new BizException("无更新字段（可传 price / stock / onSale / supplyPrice）");
        }
        dishMapper.update(null, wrapper);
        log.info("管理端更新商品: dishId={}, price={}, stock={}, onSale={}, supplyPrice={}",
                dishId, dto.getPrice(), dto.getStock(), dto.getOnSale(),
                dto.isSupplyPricePresent() ? dto.getSupplyPrice() : "(未表态)");
        // R6 补课：旧值取方法开头 selectById 快照、新值取本次提交列（未传的列为 null，JSON 里显式留痕）；
        // supplyPrice 键缺失时 new 侧如实记 null（未表态=没有列变更，与 price/stock/onSale 未传同款留痕口径）
        writeAudit(auditActor(actor), "dish.update", "dish", String.valueOf(dishId),
                "price=" + dish.getPrice() + ",stock=" + dish.getStock() + ",onSale=" + dish.getOnSale()
                        + ",supplyPrice=" + dish.getSupplyPrice(),
                "price=" + dto.getPrice() + ",stock=" + dto.getStock() + ",onSale=" + dto.getOnSale()
                        + ",supplyPrice=" + dto.getSupplyPrice());
    }

    // ==================================================================
    // F-116 商品内容审核「先发后审」（一个写操作，只写 dish.audit_status / audit_reason 两列）：
    // 闸门/限流在 AdminController（照 F-110~F-114 模式双轨 + IP 10/60s）；审计 dish.audit 与业务同源写。
    // 红线（契约 §2.2/§2.6）：驳回/恢复一律不改 on_sale（两列正交，上下架仍归 updateDish/商家端）；
    // 免费领池不做驳回联动（契约 §0 登记边界，运营自行移池）。
    // ==================================================================

    /** F-116 审核动作白名单（§2.2：reject 驳回 / restore 恢复，两枚；其余 400） */
    private static final Set<String> ALLOWED_DISH_AUDIT_ACTIONS = Set.of("reject", "restore");

    /** F-116 action 非法的 400 文案（契约 §2.2 逐字） */
    private static final String DISH_AUDIT_ACTION_INVALID_MSG = "action 仅允许 reject/restore";

    /** F-116 reject 理由的 400 文案（契约只定形状「必填 trim 后 1~200」，文案一处真源） */
    private static final String DISH_AUDIT_REASON_INVALID_MSG = "驳回理由必填，且不超过 200 个字符";

    /** F-116 审计动作名（契约 §2.2 点名 dish.audit；admin_audit_log.action VARCHAR(50)） */
    private static final String AUDIT_DISH_AUDIT = "dish.audit";

    /** F-116 驳回理由长度上界（契约 §2.2：trim 后 1~200；列宽 audit_reason VARCHAR(200) 同口径） */
    private static final int DISH_AUDIT_REASON_MAX = 200;

    @Override
    public void auditDish(Long dishId, AdminDishAuditDTO dto, String actor) {
        String op = dto == null || dto.getAction() == null ? "" : dto.getAction().trim();
        if (!ALLOWED_DISH_AUDIT_ACTIONS.contains(op)) {
            throw new BizException(DISH_AUDIT_ACTION_INVALID_MSG);
        }
        boolean reject = "reject".equals(op);
        // reject 的理由缺失是请求自身的缺陷（同 updateOrderStatus 取消理由的判据位置）：
        // 先于查库 400，不花那次 SELECT；restore 的 reason 即使带了也忽略（§2.2）
        String why = dto == null || dto.getReason() == null ? "" : dto.getReason().trim();
        if (reject && (why.isEmpty() || why.length() > DISH_AUDIT_REASON_MAX)) {
            throw new BizException(DISH_AUDIT_REASON_INVALID_MSG);
        }
        Dish dish = dishId == null ? null : dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BizException(404, "菜品不存在");
        }
        String target = reject ? Dish.AUDIT_REJECTED : Dish.AUDIT_OK;
        String newStatus = dish.getAuditStatus();
        String newReason = dish.getAuditReason();
        if (!target.equals(dish.getAuditStatus())) {
            LambdaUpdateWrapper<Dish> wrapper = new LambdaUpdateWrapper<Dish>().eq(Dish::getId, dishId)
                    .set(Dish::getAuditStatus, target);
            if (reject) {
                wrapper.set(Dish::getAuditReason, why);   // 驳回置理由（trim 后）
            } else {
                // set 传 null 拼 SET audit_reason=NULL：清理由（auditReview approve 清 audit_status 同款姿势）
                wrapper.set(Dish::getAuditReason, null);
            }
            dishMapper.update(null, wrapper);
            newStatus = target;
            newReason = reject ? why : null;
            log.info("管理端商品审核: dishId={}, action={}, auditStatus: {} -> {}",
                    dishId, op, dish.getAuditStatus(), target);
        }
        // 幂等分支（已是目标态）不写库、不覆盖既有理由，但审计照记（§2.2「审计照记」）——
        // 留痕的是「运营再次确认同一态」这一动作本身，new 侧如实记库内保持的原值
        writeAudit(auditActor(actor), AUDIT_DISH_AUDIT, AUDIT_TARGET_DISH, String.valueOf(dishId),
                dishAuditDetail(op, dish.getAuditStatus(), dish.getAuditReason()),
                dishAuditDetail(op, newStatus, newReason));
    }

    /** F-116 审计 detail 片段：before/after 各记 {auditStatus, auditReason} + action（短串，超 500 由既有预算截断兜底） */
    private static String dishAuditDetail(String action, String auditStatus, String auditReason) {
        return "action=" + action + ",auditStatus=" + auditStatus + ",auditReason=" + auditReason;
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

    // ---------------------------- F-115 §2.2 秒杀场次组特判（只影响该组，其余组零变化） ----------------------------

    /** 场次配置组（SeckillController 读同一组；m25 回填种子 start=00:00 / end=23:00） */
    private static final String DICT_GROUP_SECKILL_SESSION = "seckill_session";

    /** 该组仅有的两个合法 word（契约 §2.2 第 1 条逐字） */
    private static final String SESSION_WORD_START = "start";
    private static final String SESSION_WORD_END = "end";

    /** 场次时刻合法格式（与 SeckillController 读侧同一正则：HH:MM，00~23 时 / 00~59 分） */
    private static final Pattern SESSION_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** F-115 §2.2 三条校验的 400 文案（第 2 条按契约要求含格式说明） */
    private static final String SESSION_WORD_LIMIT_MSG = "该组仅允许 start/end 词条";
    private static final String SESSION_TIME_FORMAT_MSG = "场次时刻须为 HH:MM 格式（00:00~23:59，如 06:00）";
    private static final String SESSION_ORDER_MSG = "场次开始须早于结束";

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
    /** §0.8 上游同步（B 语义只增不删；组不存在/停用/上游失败均不写审计） */
    private static final String AUDIT_DICT_SYNC = "dict.sync";

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
     * F-115 §2.2：{@code seckill_session} 组在落库前过组特判三条校验（word 白名单 / definition
     * HH:MM / 写入后 start&lt;end），其余组零变化。
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
        if (DICT_GROUP_SECKILL_SESSION.equals(group)) {
            validateSeckillSessionItem(item.getWord(), item.getDefinition());
            validateSeckillSessionOrder(null, item.getWord(), item.getDefinition());
        }
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
     * F-115 §2.2：{@code seckill_session} 组在落库前过组特判三条校验（按 word/definition 的
     * 「生效值」= 传入值 ?? 旧值 判），其余组零变化。
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
        if (DICT_GROUP_SECKILL_SESSION.equals(existing.getGroupCode())) {
            String effectiveWord = word != null ? word : existing.getWord();
            String effectiveDefinition = definition != null ? definition : existing.getDefinition();
            validateSeckillSessionItem(effectiveWord, effectiveDefinition);
            validateSeckillSessionOrder(existing, effectiveWord, effectiveDefinition);
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

    /**
     * 从上游同步（F-108 §0.8）：组校验/拉取/INSERT IGNORE/evict 全在 {@link DictService#syncFromUpstream}，
     * 本方法只做「成功后写审计」这一件事——上游失败时它抛 BizException（400）原样上抛，此处不写审计、
     * 不吞异常，运营点同步必须知道拉没拉到。审计 detail 只放三个计数，不放词条内容。
     */
    @Override
    public Map<String, Object> syncDictGroup(String group, String actor) {
        Map<String, Object> result = dictService.syncFromUpstream(group);
        writeAudit(auditActor(actor), AUDIT_DICT_SYNC, AUDIT_TARGET_DICT_GROUP, group,
                null, dictSyncDetail(result));
        return result;
    }

    /** 审计 detail（同步）：上游条数/新增/跳过三段；上游失败不会走到这里（无成功计数可写） */
    private static String dictSyncDetail(Map<String, Object> result) {
        return "upstreamTotal=" + result.get("upstreamTotal")
                + ",inserted=" + result.get("inserted")
                + ",skipped=" + result.get("skipped");
    }

    /** 分组存在性闸门（400）：列表/新增词条共用；改分组端点的 404 在 updateDictGroup 内单独判。 */
    private DictGroup requireDictGroup(String group) {
        DictGroup row = group == null ? null : dictGroupMapper.selectById(group);
        if (row == null) {
            throw new BizException(DICT_GROUP_NOT_FOUND_MSG);
        }
        return row;
    }

    // ---------------------------- F-115 §2.2 秒杀场次组特判的两条前置校验 ----------------------------

    /**
     * 词条自身两条校验（契约第 1/2 条）：word 仅 {@code start}/{@code end}、definition 必须匹配
     * HH:MM 正则（与 {@code SeckillController} 读侧同一正则，写严读宽，读侧对非法值另有回退）。
     * create 传归一化后的入库值；update 传「生效值」（传入 ?? 旧值），未改列同样要过闸。
     */
    private static void validateSeckillSessionItem(String word, String definition) {
        if (!SESSION_WORD_START.equals(word) && !SESSION_WORD_END.equals(word)) {
            throw new BizException(SESSION_WORD_LIMIT_MSG);
        }
        if (definition == null || !SESSION_TIME_PATTERN.matcher(definition).matches()) {
            throw new BizException(SESSION_TIME_FORMAT_MSG);
        }
    }

    /**
     * 跨词条时序校验（契约第 3 条）：按「写入后」组内 start/end 两词条的 definition 对比，
     * start ≥ end → 400（相等也拒）。合并口径：先取组内现有行，剔除被本次写入替换的行
     * （update 传 existing 按主键剔；create 传 null），再并入本次 (newWord, newDefinition)；
     * 任一侧缺词条不在此拦（读侧回退默认值兜底，此处只校验双值齐时的时序）。
     * HH:MM 格式已被 {@link #validateSeckillSessionItem} 约束为定长补零，字典序即时间序。
     */
    private void validateSeckillSessionOrder(DictItem replaced, String newWord, String newDefinition) {
        String start = null;
        String end = null;
        for (DictItem row : dictItemMapper.selectByGroup(DICT_GROUP_SECKILL_SESSION)) {
            if (replaced != null && row.getId() != null && row.getId().equals(replaced.getId())) {
                continue; // update：旧行将被本次写入覆盖，不参与合并
            }
            if (SESSION_WORD_START.equals(row.getWord())) {
                start = row.getDefinition();
            } else if (SESSION_WORD_END.equals(row.getWord())) {
                end = row.getDefinition();
            }
        }
        if (SESSION_WORD_START.equals(newWord)) {
            start = newDefinition;
        } else if (SESSION_WORD_END.equals(newWord)) {
            end = newDefinition;
        }
        if (start != null && end != null && start.compareTo(end) >= 0) {
            throw new BizException(SESSION_ORDER_MSG);
        }
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

    // ==================================================================
    // F-109：免费领活动管理（四个端点，只动活动配置，不碰订单/价格/库存）。
    // 追加式落码在文件末尾（常量 + 依赖 + 方法），理由同 F-63/F-108 段注释。
    // C 端 FreeServiceImpl 读路径无任何缓存（逐次查 free_activity/free_goods_pool/dish），
    // 故写后无需 evict；本段也不新增缓存。
    // ==================================================================

    /** 审计动作（契约 §0.2 逐字两枚；写侧唯一真源） */
    private static final String AUDIT_FREE_ACTIVITY_UPDATE = "free.activity.update";
    private static final String AUDIT_FREE_POOL_UPDATE = "free.pool.update";

    /** 审计目标类型（admin_audit_log.target_type VARCHAR(30)） */
    private static final String AUDIT_TARGET_FREE_ACTIVITY = "free_activity";
    private static final String AUDIT_TARGET_FREE_POOL = "free_pool";

    /** 单活动模型（种子固定 id=1）：无行属数据异常，读/写统一 404 */
    private static final String FREE_ACTIVITY_NOT_FOUND_MSG = "免费领活动不存在";

    /** 换品 400 文案（契约「dish 存在且 on_sale=1，否则 400」） */
    private static final String FREE_DISH_INVALID_MSG = "免费领商品不存在或已下架";

    /** 候选池 400 文案（任一商品不存在/已下架即整单拒绝，不静默剔除） */
    private static final String FREE_POOL_DISH_INVALID_MSG = "候选商品不存在或已下架";

    /** 空池 400 文案（C 端无候选可选会整页置灰） */
    private static final String FREE_POOL_EMPTY_MSG = "候选池至少保留一件商品";

    /** 全空更新 400 文案 */
    private static final String FREE_ACTIVITY_NO_FIELDS_MSG =
            "无更新字段（可传 dishId / dailyQuota / threshold / status）";

    /** F-109 的两个新依赖（只被本段方法使用；追加式字段声明同 F-63/F-108 那批） */
    @Autowired
    private FreeActivityMapper freeActivityMapper;
    @Autowired
    private FreeGoodsPoolMapper freeGoodsPoolMapper;

    /**
     * 活动 + 候选池出参：键序固定 {@code id,dishId,dishName,dailyQuota,dailyClaimed,dailyDate,
     * threshold,status,pool}；pool 行含已下架商品（带 onSale=0），管理端要看见池里有没有失效项。
     */
    @Override
    public Map<String, Object> freeActivity() {
        FreeActivity activity = requireFreeActivity();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", activity.getId());
        data.put("dishId", activity.getDishId());
        Dish dish = activity.getDishId() == null ? null : dishMapper.selectById(activity.getDishId());
        data.put("dishName", dish == null ? null : dish.getName());
        data.put("dailyQuota", activity.getDailyQuota() == null ? 0 : activity.getDailyQuota());
        data.put("dailyClaimed", activity.getDailyClaimed() == null ? 0 : activity.getDailyClaimed());
        data.put("dailyDate", activity.getDailyDate());
        data.put("threshold", activity.getThreshold() == null ? BigDecimal.ZERO : activity.getThreshold());
        data.put("status", activity.getStatus());
        data.put("pool", freePoolRows(activity.getId()));
        return data;
    }

    /**
     * 部分更新活动：<b>只 set 四个可管理列</b>，null 字段由 MP 的 NOT_NULL 策略跳过；
     * daily_claimed/daily_date 既不在 DTO 里、也不在 patch 里，落库路径上不存在写它们的分支。
     * 判据顺序：活动行 → 全空 → dishId/on_sale → quota → threshold → status（全部校验先于写）。
     */
    @Override
    public void updateFreeActivity(AdminFreeActivityUpdateDTO dto, String actor) {
        FreeActivity activity = requireFreeActivity();
        if (dto == null) {
            throw new BizException(FREE_ACTIVITY_NO_FIELDS_MSG);
        }
        Long dishId = dto.getDishId();
        Integer dailyQuota = dto.getDailyQuota();
        BigDecimal threshold = dto.getThreshold();
        String status = dto.getStatus();
        if (dishId == null && dailyQuota == null && threshold == null && status == null) {
            throw new BizException(FREE_ACTIVITY_NO_FIELDS_MSG);
        }
        if (dishId != null) {
            Dish dish = dishMapper.selectById(dishId);
            if (dish == null || dish.getOnSale() == null || dish.getOnSale() != 1) {
                throw new BizException(FREE_DISH_INVALID_MSG);
            }
        }
        if (dailyQuota != null && dailyQuota < 0) {
            throw new BizException("每日限量不能为负数");
        }
        if (threshold != null && threshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("满额门槛不能为负数");
        }
        if (status != null
                && !FreeActivity.STATUS_ONLINE.equals(status)
                && !FreeActivity.STATUS_OFFLINE.equals(status)) {
            throw new BizException("活动状态仅允许 online 或 offline");
        }
        FreeActivity patch = new FreeActivity();
        patch.setId(activity.getId());
        patch.setDishId(dishId);
        patch.setDailyQuota(dailyQuota);
        patch.setThreshold(threshold);
        patch.setStatus(status);
        freeActivityMapper.updateById(patch);
        writeAudit(auditActor(actor), AUDIT_FREE_ACTIVITY_UPDATE, AUDIT_TARGET_FREE_ACTIVITY,
                String.valueOf(activity.getId()),
                freeActivityDetail(activity),
                freeActivityDetail(dishId != null ? dishId : activity.getDishId(),
                        dailyQuota != null ? dailyQuota : activity.getDailyQuota(),
                        threshold != null ? threshold : activity.getThreshold(),
                        status != null ? status : activity.getStatus()));
    }

    /** 候选池只读：池行 id 升序（=插入序，第一行即 C 端默认免费商品） */
    @Override
    public List<Map<String, Object>> freePool() {
        return freePoolRows(requireFreeActivity().getId());
    }

    /**
     * 全量替换候选池：同事务删旧插新。校验（活动行/非空/去重/null 项/逐个存在且在售）
     * 全部先于删除；unique 已去重，插入不会撞 {@code uk_activity_dish}。
     * 旧池行<b>一条不留</b>（delete 只按 activity_id 过滤，不带任何保留/排除名单）。
     */
    @Override
    @Transactional
    public void updateFreePool(AdminFreePoolUpdateDTO dto, String actor) {
        FreeActivity activity = requireFreeActivity();
        List<Long> dishIds = dto == null ? null : dto.getDishIds();
        if (dishIds == null || dishIds.isEmpty()) {
            throw new BizException(FREE_POOL_EMPTY_MSG);
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long dishId : dishIds) {
            if (dishId == null) {
                throw new BizException(FREE_POOL_DISH_INVALID_MSG);
            }
            unique.add(dishId);
        }
        if (unique.isEmpty()) {
            throw new BizException(FREE_POOL_EMPTY_MSG);
        }
        List<Long> ordered = new ArrayList<>(unique);
        Map<Long, Dish> dishes = new HashMap<>();
        for (Dish dish : dishMapper.selectBatchIds(ordered)) {
            dishes.put(dish.getId(), dish);
        }
        for (Long dishId : ordered) {
            Dish dish = dishes.get(dishId);
            if (dish == null || dish.getOnSale() == null || dish.getOnSale() != 1) {
                throw new BizException(FREE_POOL_DISH_INVALID_MSG);
            }
        }
        List<FreeGoodsPool> before = freeGoodsPoolMapper.selectList(new LambdaQueryWrapper<FreeGoodsPool>()
                .eq(FreeGoodsPool::getActivityId, activity.getId())
                .orderByAsc(FreeGoodsPool::getId));
        freeGoodsPoolMapper.delete(new LambdaQueryWrapper<FreeGoodsPool>()
                .eq(FreeGoodsPool::getActivityId, activity.getId()));
        for (Long dishId : ordered) {
            FreeGoodsPool row = new FreeGoodsPool();
            row.setActivityId(activity.getId());
            row.setDishId(dishId);
            freeGoodsPoolMapper.insert(row);
        }
        writeAudit(auditActor(actor), AUDIT_FREE_POOL_UPDATE, AUDIT_TARGET_FREE_POOL,
                String.valueOf(activity.getId()),
                freePoolDetail(beforeIds(before)),
                freePoolDetail(ordered));
    }

    /** 单活动模型：取 id 最小的活动行（与 C 端 FreeServiceImpl#currentActivity 同口径） */
    private FreeActivity requireFreeActivity() {
        FreeActivity activity = freeActivityMapper.selectOne(new LambdaQueryWrapper<FreeActivity>()
                .orderByAsc(FreeActivity::getId).last("LIMIT 1"));
        if (activity == null) {
            throw new BizException(R.CODE_NOT_FOUND, FREE_ACTIVITY_NOT_FOUND_MSG);
        }
        return activity;
    }

    /** 池行 + 商品名/在售态；商品被删时 dishName=null、onSale=0（池行仍列出，运营看得见脏项） */
    private List<Map<String, Object>> freePoolRows(Long activityId) {
        List<FreeGoodsPool> pool = freeGoodsPoolMapper.selectList(new LambdaQueryWrapper<FreeGoodsPool>()
                .eq(FreeGoodsPool::getActivityId, activityId)
                .orderByAsc(FreeGoodsPool::getId));
        Map<Long, Dish> dishes = new HashMap<>();
        if (!pool.isEmpty()) {
            List<Long> ids = new ArrayList<>(pool.size());
            for (FreeGoodsPool row : pool) {
                if (row.getDishId() != null) {
                    ids.add(row.getDishId());
                }
            }
            if (!ids.isEmpty()) {
                for (Dish dish : dishMapper.selectBatchIds(ids)) {
                    dishes.put(dish.getId(), dish);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(pool.size());
        for (FreeGoodsPool row : pool) {
            Dish dish = row.getDishId() == null ? null : dishes.get(row.getDishId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("dishId", row.getDishId());
            line.put("dishName", dish == null ? null : dish.getName());
            line.put("onSale", dish != null && dish.getOnSale() != null && dish.getOnSale() == 1 ? 1 : 0);
            out.add(line);
        }
        return out;
    }

    /** 审计 detail（活动）：只含四个可管理列——daily_claimed/daily_date 不是管理面，不进审计新旧值 */
    private static String freeActivityDetail(FreeActivity activity) {
        return freeActivityDetail(activity.getDishId(), activity.getDailyQuota(),
                activity.getThreshold(), activity.getStatus());
    }

    private static String freeActivityDetail(Long dishId, Integer dailyQuota, BigDecimal threshold, String status) {
        return "dishId=" + dishId + ",dailyQuota=" + dailyQuota
                + ",threshold=" + threshold + ",status=" + status;
    }

    private static List<Long> beforeIds(List<FreeGoodsPool> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (FreeGoodsPool row : rows) {
            ids.add(row.getDishId());
        }
        return ids;
    }

    /** 审计 detail（候选池）：按池顺序拼 dishIds；超 500 由 writeAudit 的既有预算截断兜底 */
    private static String freePoolDetail(List<Long> dishIds) {
        StringBuilder sb = new StringBuilder("dishIds=");
        for (int i = 0; i < dishIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(dishIds.get(i));
        }
        return sb.toString();
    }

    // ==================================================================
    // F-110 搜索别名维护（四个操作，只影响搜索命中，不碰价格/库存/订单）
    // 读端点不写审计；写端点成功后统一走本类唯一那份 writeAudit（旁路）：
    //   alias.create / alias.update / alias.delete。
    // 别名语义不变：C 端搜索仍是 DishMapper#selectDishIdsByAlias 的精确等值，本段不改那条 SQL。
    // 写操作均为单条 DML，不加 @Transactional：C 端别名读路径无缓存，没有 cache-aside 顺序问题；
    // 审计是旁路，写失败不打断主流程。
    // ==================================================================

    /** 审计动作（契约 §0.2 逐字三枚；写侧唯一真源） */
    private static final String AUDIT_ALIAS_CREATE = "alias.create";
    private static final String AUDIT_ALIAS_UPDATE = "alias.update";
    private static final String AUDIT_ALIAS_DELETE = "alias.delete";

    /** 审计目标类型（admin_audit_log.target_type VARCHAR(30)） */
    private static final String AUDIT_TARGET_DISH_ALIAS = "dish_alias";

    /** 别名列宽（dish_alias.alias VARCHAR(50)）：trim 后再判，按字符数 */
    private static final int ALIAS_MAX_LENGTH = 50;

    private static final String ALIAS_EMPTY_MSG = "别名不能为空";
    private static final String ALIAS_TOO_LONG_MSG = "别名最长 50 个字符";
    /** uk_alias 冲突的 400 文案（契约逐字，不把 DB 错误串透给前端） */
    private static final String ALIAS_DUPLICATE_MSG = "该别名已存在";
    private static final String ALIAS_NOT_FOUND_MSG = "别名不存在";
    private static final String ALIAS_DISH_NOT_FOUND_MSG = "商品不存在";
    private static final String ALIAS_NO_FIELDS_MSG = "无更新字段（可传 alias / dishId）";

    /** F-110 的新依赖（只被本段方法使用；追加式字段声明同 F-63/F-108/F-109 那批） */
    @Autowired
    private DishAliasMapper dishAliasMapper;

    /**
     * 别名全量列表：键序固定 {@code id,alias,dishId,dishName,onSale}，顺序即 mapper 的 {@code alias ASC}。
     * 一次别名查询 + 一次商品批量查询，不按行循环查；商品行被清（LEFT JOIN 不到）时 dishName=null、
     * onSale=0，但别名行仍返回（运营看得见脏项）。
     */
    @Override
    public List<Map<String, Object>> aliases() {
        List<DishAlias> rows = dishAliasMapper.selectAllOrdered();
        Map<Long, Dish> dishes = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> ids = new ArrayList<>(rows.size());
            for (DishAlias row : rows) {
                if (row.getDishId() != null) {
                    ids.add(row.getDishId());
                }
            }
            if (!ids.isEmpty()) {
                for (Dish dish : dishMapper.selectBatchIds(ids)) {
                    dishes.put(dish.getId(), dish);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (DishAlias row : rows) {
            Dish dish = row.getDishId() == null ? null : dishes.get(row.getDishId());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("alias", row.getAlias());
            line.put("dishId", row.getDishId());
            line.put("dishName", dish == null ? null : dish.getName());
            line.put("onSale", dish != null && dish.getOnSale() != null && dish.getOnSale() == 1 ? 1 : 0);
            out.add(line);
        }
        return out;
    }

    /**
     * 新增别名：alias trim 后非空且 ≤50；dishId 必须存在（<b>不要求在售</b>）；
     * 预查 uk_alias 冲突 → 400，并发下由 DB 唯一键兜底成同一句 400。
     * 成功后写审计 {@code alias.create}（target_id = 新行 id）。
     */
    @Override
    public void createAlias(AdminAliasCreateDTO dto, String actor) {
        String alias = requiredAlias(dto == null ? null : dto.getAlias());
        Long dishId = dto == null ? null : dto.getDishId();
        if (dishId == null) {
            throw new BizException("商品不能为空");
        }
        requireDishExists(dishId);
        if (dishAliasMapper.selectByAlias(alias) != null) {
            throw new BizException(ALIAS_DUPLICATE_MSG);
        }
        DishAlias row = new DishAlias();
        row.setAlias(alias);
        row.setDishId(dishId);
        try {
            dishAliasMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // uk_alias 的并发兜底：先查后插挡不住两人同时提交同一个别名
            throw new BizException(ALIAS_DUPLICATE_MSG);
        }
        writeAudit(auditActor(actor), AUDIT_ALIAS_CREATE, AUDIT_TARGET_DISH_ALIAS,
                String.valueOf(row.getId()), null, aliasDetail(alias, dishId));
    }

    /**
     * 部分更新别名：id 不存在 404；两列全空 400；alias 传空白串 400、超长 400；
     * dishId 传值须存在（不要求在售）；改后撞 uk_alias → 400（排除自身）。
     * 成功后写审计 {@code alias.update}，detail 的旧/新值均为「合并后的完整两列」。
     */
    @Override
    public void updateAlias(Long id, AdminAliasUpdateDTO dto, String actor) {
        DishAlias existing = id == null ? null : dishAliasMapper.selectById(id);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, ALIAS_NOT_FOUND_MSG);
        }
        if (dto == null) {
            throw new BizException(ALIAS_NO_FIELDS_MSG);
        }
        String alias = dto.getAlias() == null ? null : requiredAlias(dto.getAlias());
        Long dishId = dto.getDishId();
        if (alias == null && dishId == null) {
            throw new BizException(ALIAS_NO_FIELDS_MSG);
        }
        if (dishId != null) {
            requireDishExists(dishId);
        }
        if (alias != null) {
            DishAlias same = dishAliasMapper.selectByAlias(alias);
            if (same != null && !id.equals(same.getId())) {
                throw new BizException(ALIAS_DUPLICATE_MSG);
            }
        }
        DishAlias patch = new DishAlias();
        patch.setId(id);
        patch.setAlias(alias);
        patch.setDishId(dishId);
        try {
            dishAliasMapper.updateById(patch);
        } catch (DuplicateKeyException e) {
            throw new BizException(ALIAS_DUPLICATE_MSG);
        }
        writeAudit(auditActor(actor), AUDIT_ALIAS_UPDATE, AUDIT_TARGET_DISH_ALIAS, String.valueOf(id),
                aliasDetail(existing),
                aliasDetail(alias != null ? alias : existing.getAlias(),
                        dishId != null ? dishId : existing.getDishId()));
    }

    /**
     * 删除别名：id 不存在 404；查无之后、删除之前被并发删掉也按 404 出口（没删掉任何行，不写审计）。
     * 成功后写审计 {@code alias.delete}，新值固定 {@code deleted}。
     */
    @Override
    public void deleteAlias(Long id, String actor) {
        DishAlias existing = id == null ? null : dishAliasMapper.selectById(id);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, ALIAS_NOT_FOUND_MSG);
        }
        int removed = dishAliasMapper.deleteById(id);
        if (removed == 0) {
            throw new BizException(R.CODE_NOT_FOUND, ALIAS_NOT_FOUND_MSG);
        }
        writeAudit(auditActor(actor), AUDIT_ALIAS_DELETE, AUDIT_TARGET_DISH_ALIAS, String.valueOf(id),
                aliasDetail(existing), "deleted");
    }

    /** 别名归一：null/空白 → 400；trim 后按字符数 ≤50（不让 MySQL 严格模式的 1406 变 500） */
    private static String requiredAlias(String raw) {
        if (raw == null) {
            throw new BizException(ALIAS_EMPTY_MSG);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ALIAS_EMPTY_MSG);
        }
        if (trimmed.length() > ALIAS_MAX_LENGTH) {
            throw new BizException(ALIAS_TOO_LONG_MSG);
        }
        return trimmed;
    }

    /** 商品存在性闸门：存在即可，<b>不要求在售</b>（下架商品持有别名是合法状态） */
    private void requireDishExists(Long dishId) {
        if (dishMapper.selectById(dishId) == null) {
            throw new BizException(ALIAS_DISH_NOT_FOUND_MSG);
        }
    }

    /** 审计 detail（别名）：两列定长短串；超 500 由 writeAudit 的既有预算截断兜底 */
    private static String aliasDetail(DishAlias row) {
        return aliasDetail(row.getAlias(), row.getDishId());
    }

    private static String aliasDetail(String alias, Long dishId) {
        return "alias=" + alias + ",dishId=" + dishId;
    }

    // ==================================================================
    // F-112 秒杀商品集管理（两个操作，只写 dish.seckill / dish.seckill_price 两列）
    // 读端点不写审计；写端点成功后统一走本类唯一那份 writeAudit（旁路）：seckill.update。
    // 红线（契约 §1.3）：SeckillController 与 DishServiceImpl#listSeckill（三条件过滤
    // on_sale=1/stock>0/seckill_price 非 NULL）一行不改；OrderServiceImpl 计价判据不动；
    // 商家端 DTO 白名单继续排除 seckill。本段不碰价格/库存/在售列，也不新增任何读路径。
    // 写操作为单条 DML，不加 @Transactional（同 F-110 段口径）：审计是旁路，写失败不打断主流程。
    // ==================================================================

    /** 审计动作（契约 §1.2 逐字一枚；写侧唯一真源；admin_audit_log.action VARCHAR(50)） */
    private static final String AUDIT_SECKILL_UPDATE = "seckill.update";

    /** 审计目标类型（admin_audit_log.target_type VARCHAR(30)；与既有 dish.update 审计同一目标串） */
    private static final String AUDIT_TARGET_DISH = "dish";

    private static final String SECKILL_DISH_NOT_FOUND_MSG = "商品不存在";
    private static final String SECKILL_FLAG_INVALID_MSG = "seckill 仅允许 0（移出秒杀）或 1（进秒杀）";
    private static final String SECKILL_PRICE_INVALID_MSG = "秒杀价必须为正数（最多两位小数）";

    /**
     * 秒杀视角全量列表：键序固定 {@code id,name,price,onSale,stock,soldCount,seckill,seckillPrice,
     * limitBuy,merchantId}，顺序即 mapper 的 id 升序。
     * 手工组 Map 而非直出实体：merchantId 在 Dish 上挂 @JsonIgnore（不参与任何 JSON 面），
     * 与 aliases() 同一处置——出参白名单由本方法逐键定义，实体装饰注解不牵着出参走。
     */
    @Override
    public List<Map<String, Object>> seckillDishes() {
        List<Dish> rows = dishMapper.selectList(
                new LambdaQueryWrapper<Dish>().orderByAsc(Dish::getId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Dish dish : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", dish.getId());
            line.put("name", dish.getName());
            line.put("price", dish.getPrice());
            line.put("onSale", dish.getOnSale());
            line.put("stock", dish.getStock());
            line.put("soldCount", dish.getSoldCount());
            line.put("seckill", dish.getSeckill());
            line.put("seckillPrice", dish.getSeckillPrice());
            line.put("limitBuy", dish.getLimitBuy());
            line.put("merchantId", dish.getMerchantId());
            out.add(line);
        }
        return out;
    }

    /**
     * 更新秒杀参与态：校验顺序即报错优先级（契约 §1.2 逐条）——
     * ① dish 不存在 404（先于一切 body 判定：id 是路径参数，先告诉调用方目标都不存在）；
     * ② seckill 非 0/1 → 400；③ seckill=1 时价必填、&gt;0、最多两位小数 → 400；
     * ④ seckill=1 时价 ≥ 库内现价 → 400（划线价语义：C 端折扣率 = (price−seckill_price)/price，
     * 倒挂即负折扣；现价由本方法查库取得，不信任前端传参）。
     * seckill=0 时不回清 seckill_price：patch 该列留 null，updateById 的 NOT_NULL 策略
     * 即「不落该列」= 原值保留（两列解耦是既有语义，与 F-110 updateAlias 未传列不落同一机制）。
     * 成功后写审计 {@code seckill.update}，detail 的旧/新值均为「两列完整快照」
     * （移出时新值里的 seckillPrice 记保留后的原值，如实反映库内状态）。
     */
    @Override
    public void updateSeckillDish(Long dishId, AdminSeckillUpdateDTO dto, String actor) {
        Dish dish = dishId == null ? null : dishMapper.selectById(dishId);
        if (dish == null) {
            throw new BizException(R.CODE_NOT_FOUND, SECKILL_DISH_NOT_FOUND_MSG);
        }
        Integer seckill = dto == null ? null : dto.getSeckill();
        if (seckill == null || (seckill != 0 && seckill != 1)) {
            throw new BizException(SECKILL_FLAG_INVALID_MSG);
        }
        BigDecimal newPrice = dto.getSeckillPrice();
        if (seckill == 1) {
            if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0
                    || newPrice.stripTrailingZeros().scale() > 2) {
                throw new BizException(SECKILL_PRICE_INVALID_MSG);
            }
            if (newPrice.compareTo(dish.getPrice()) >= 0) {
                throw new BizException("秒杀价必须低于原价 "
                        + dish.getPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
        }
        Dish patch = new Dish();
        patch.setId(dishId);
        patch.setSeckill(seckill);
        if (seckill == 1) {
            patch.setSeckillPrice(newPrice);   // 进秒杀设价与调价同一条落库路径
        }
        // seckill=0 时 patch.seckillPrice 保持 null → 不落该列 → 历史价原样保留（§1.2 第 5 条）
        dishMapper.updateById(patch);
        writeAudit(auditActor(actor), AUDIT_SECKILL_UPDATE, AUDIT_TARGET_DISH, String.valueOf(dishId),
                seckillDetail(dish.getSeckill(), dish.getSeckillPrice()),
                seckillDetail(seckill, seckill == 1 ? newPrice : dish.getSeckillPrice()));
    }

    /** 审计 detail（秒杀）：两列定长短串；超 500 由 writeAudit 的既有预算截断兜底 */
    private static String seckillDetail(Integer seckill, BigDecimal seckillPrice) {
        return "seckill=" + seckill + ",seckillPrice=" + seckillPrice;
    }

    // ==================================================================
    // F-113 券模板管理（三个操作，只写 coupon_template 一张表）
    // 读端点不写审计；写端点成功后统一走本类唯一那份 writeAudit（旁路）：
    //   coupon_template.create / coupon_template.update。
    // 红线（契约 §2.4/§4）：不提供 DELETE——已发过的模板删除 = 历史 user_coupon.coupon_key
    // 悬挂，停用（status=disabled）即运营语义；issued_count 与 coupon_key 不在落库白名单。
    // 写操作为单条 DML，不加 @Transactional（同 F-110/F-112 段口径）：审计是旁路，
    // 写失败不打断主流程。C 端 claim/center 直读同表，改动下一次请求即生效（无缓存）。
    // ==================================================================

    /** 审计动作（契约 §4 逐字两枚；写侧唯一真源；admin_audit_log.action VARCHAR(50)） */
    private static final String AUDIT_COUPON_TEMPLATE_CREATE = "coupon_template.create";
    private static final String AUDIT_COUPON_TEMPLATE_UPDATE = "coupon_template.update";

    /** 审计目标类型（admin_audit_log.target_type VARCHAR(30)） */
    private static final String AUDIT_TARGET_COUPON_TEMPLATE = "coupon_template";

    private static final String CT_KEY_INVALID_MSG = "key 须为 2-50 位小写字母/数字/下划线";
    /** uk_coupon_key 冲突的 400 文案（契约逐字「key 已存在」） */
    private static final String CT_KEY_DUPLICATE_MSG = "key 已存在";
    private static final String CT_TITLE_TOO_LONG_MSG = "名称最长 50 个字符";
    private static final String CT_MONEY_INVALID_MSG = "门槛与抵扣须为正数（最多两位小数）";
    /** 抵扣≥门槛=负价单（契约 §4 点名的防资损校验） */
    private static final String CT_AMOUNT_GE_THRESHOLD_MSG = "抵扣金额必须低于使用门槛";
    private static final String CT_VALID_DAYS_INVALID_MSG = "有效天数须为 1-365";
    private static final String CT_LIMIT_INVALID_MSG = "每人限领须为大于等于 0 的整数";
    private static final String CT_QUOTA_INVALID_MSG = "总量配额须为大于等于 0 的整数（不限则不传）";
    private static final String CT_STATUS_INVALID_MSG = "status 仅允许 active/disabled";
    private static final String CT_NOT_FOUND_MSG = "券模板不存在";
    private static final String CT_NO_FIELDS_MSG = "无更新字段（可传 title/threshold/amount/validDays/limitPerUser/totalQuota/status）";

    /** F-113 的新依赖（只被本段方法使用；追加式字段声明同 F-109/F-110/F-112 那批） */
    @Autowired
    private CouponTemplateMapper couponTemplateMapper;

    /**
     * 模板全量列表：键序固定 {@code id,couponKey,title,threshold,amount,validDays,limitPerUser,
     * totalQuota,issuedCount,status}，顺序即 mapper 的 id 升序（=迁移回填顺序）。
     * 手工组 Map 而非直出实体：出参白名单由本方法逐键定义（同 seckillDishes/aliases 的处置）。
     */
    @Override
    public List<Map<String, Object>> couponTemplates() {
        List<CouponTemplate> rows = couponTemplateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>().orderByAsc(CouponTemplate::getId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (CouponTemplate row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("couponKey", row.getCouponKey());
            line.put("title", row.getTitle());
            line.put("threshold", row.getThreshold());
            line.put("amount", row.getAmount());
            line.put("validDays", row.getValidDays());
            line.put("limitPerUser", row.getLimitPerUser());
            line.put("totalQuota", row.getTotalQuota());
            line.put("issuedCount", row.getIssuedCount());
            line.put("status", row.getStatus());
            out.add(line);
        }
        return out;
    }

    /**
     * 上新模板：校验顺序即报错优先级（契约 §4 逐条，见接口注释）；key 冲突预查 + DB 唯一键
     * 并发兜底成同一句 400。成功后写审计 {@code coupon_template.create}（target_id = 新行 id）。
     */
    @Override
    public void createCouponTemplate(AdminCouponTemplateCreateDTO dto, String actor) {
        if (dto == null) {
            throw new BizException(CT_KEY_INVALID_MSG);
        }
        String key = dto.getCouponKey() == null ? null : dto.getCouponKey().trim();
        if (key == null || !key.matches("^[a-z0-9_]{2,50}$")) {
            throw new BizException(CT_KEY_INVALID_MSG);
        }
        if (couponTemplateMapper.selectByCouponKey(key) != null) {
            throw new BizException(CT_KEY_DUPLICATE_MSG);
        }
        String title = validateTitle(dto.getTitle());
        BigDecimal threshold = dto.getThreshold();
        BigDecimal amount = dto.getAmount();
        validateMoney(threshold, amount);
        Integer validDays = validateValidDays(dto.getValidDays());
        Integer limitPerUser = validateLimitPerUser(dto.getLimitPerUser());
        Integer totalQuota = validateTotalQuota(dto.getTotalQuota());
        String status = validateStatus(dto.getStatus());

        CouponTemplate row = new CouponTemplate();
        row.setCouponKey(key);
        row.setTitle(title);
        row.setThreshold(threshold);
        row.setAmount(amount);
        row.setValidDays(validDays);
        row.setLimitPerUser(limitPerUser);
        row.setTotalQuota(totalQuota);
        row.setIssuedCount(0);
        row.setStatus(status);
        try {
            couponTemplateMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // uk_coupon_key 的并发兜底：先查后插挡不住两人同时提交同一个 key
            throw new BizException(CT_KEY_DUPLICATE_MSG);
        }
        writeAudit(auditActor(actor), AUDIT_COUPON_TEMPLATE_CREATE, AUDIT_TARGET_COUPON_TEMPLATE,
                String.valueOf(row.getId()), null, templateDetail(row));
    }

    /**
     * 部分更新模板：id 不存在 404；七列全空 400；校验对<b>合并后的新值</b>判
     * （amount&lt;threshold 以合并后门槛为参照——只改其一也不放过倒挂）。
     * 落库白名单只有七列：coupon_key/issued_count 的 DTO 探测字段不进 patch，
     * updateById 的 NOT_NULL 策略下 null = 不落该列 = 原值保留。
     * 成功后写审计 {@code coupon_template.update}，before/after 均为合并后完整快照。
     */
    @Override
    public void updateCouponTemplate(Long id, AdminCouponTemplateUpdateDTO dto, String actor) {
        CouponTemplate existing = id == null ? null : couponTemplateMapper.selectById(id);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, CT_NOT_FOUND_MSG);
        }
        if (dto == null) {
            throw new BizException(CT_NO_FIELDS_MSG);
        }
        // 合并新值（null=不改；title 允许显式传空串=清空展示名回「满X减Y」拼装）
        String title = dto.getTitle() == null ? existing.getTitle() : validateTitle(dto.getTitle());
        BigDecimal threshold = dto.getThreshold() == null ? existing.getThreshold() : dto.getThreshold();
        BigDecimal amount = dto.getAmount() == null ? existing.getAmount() : dto.getAmount();
        Integer validDays = dto.getValidDays() == null ? existing.getValidDays() : dto.getValidDays();
        Integer limitPerUser = dto.getLimitPerUser() == null ? existing.getLimitPerUser() : dto.getLimitPerUser();
        Integer totalQuota = dto.getTotalQuota() == null ? existing.getTotalQuota() : dto.getTotalQuota();
        String status = dto.getStatus() == null ? existing.getStatus() : dto.getStatus();
        if (dto.getTitle() == null && dto.getThreshold() == null && dto.getAmount() == null
                && dto.getValidDays() == null && dto.getLimitPerUser() == null
                && dto.getTotalQuota() == null && dto.getStatus() == null) {
            throw new BizException(CT_NO_FIELDS_MSG);
        }
        validateMoney(threshold, amount);   // 合并后判：旧值原本合法，等价于对新值判
        validateValidDays(validDays);
        validateLimitPerUser(limitPerUser);
        validateTotalQuota(totalQuota);
        validateStatus(status);

        CouponTemplate patch = new CouponTemplate();
        patch.setId(id);
        patch.setTitle(title);
        patch.setThreshold(threshold);
        patch.setAmount(amount);
        patch.setValidDays(validDays);
        patch.setLimitPerUser(limitPerUser);
        patch.setTotalQuota(totalQuota);
        patch.setStatus(status);
        // 刻意不 set couponKey/issuedCount：白名单落库，探测字段带了也不生效
        couponTemplateMapper.updateById(patch);
        writeAudit(auditActor(actor), AUDIT_COUPON_TEMPLATE_UPDATE, AUDIT_TARGET_COUPON_TEMPLATE,
                String.valueOf(id), templateDetail(existing), templateDetail(patch));
    }

    /** title 归一：null=不设（走「满X减Y」拼装）；trim 后 ≤50 字符（空串合法=显式清空） */
    private static String validateTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 50) {
            throw new BizException(CT_TITLE_TOO_LONG_MSG);
        }
        return trimmed;
    }

    /** 金额形状 + 倒挂校验：threshold/amount 均 &gt;0、最多两位小数，且 amount &lt; threshold（负价单拦截）。
     *  update 侧传入的已是合并后的最终值，create 侧即入参本身。 */
    private static void validateMoney(BigDecimal threshold, BigDecimal amount) {
        if (threshold == null || amount == null
                || threshold.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(BigDecimal.ZERO) <= 0
                || threshold.stripTrailingZeros().scale() > 2 || amount.stripTrailingZeros().scale() > 2) {
            throw new BizException(CT_MONEY_INVALID_MSG);
        }
        if (amount.compareTo(threshold) >= 0) {
            throw new BizException(CT_AMOUNT_GE_THRESHOLD_MSG);
        }
    }

    private static Integer validateValidDays(Integer validDays) {
        if (validDays == null || validDays < 1 || validDays > 365) {
            throw new BizException(CT_VALID_DAYS_INVALID_MSG);
        }
        return validDays;
    }

    private static Integer validateLimitPerUser(Integer limitPerUser) {
        if (limitPerUser == null || limitPerUser < 0) {
            throw new BizException(CT_LIMIT_INVALID_MSG);
        }
        return limitPerUser;
    }

    private static Integer validateTotalQuota(Integer totalQuota) {
        if (totalQuota != null && totalQuota < 0) {
            throw new BizException(CT_QUOTA_INVALID_MSG);
        }
        return totalQuota;
    }

    private static String validateStatus(String status) {
        if (!CouponTemplate.STATUS_ACTIVE.equals(status) && !CouponTemplate.STATUS_DISABLED.equals(status)) {
            throw new BizException(CT_STATUS_INVALID_MSG);
        }
        return status;
    }

    /** 审计 detail（券模板）：八键定长短串（title=null 如实透传）；超 500 由 writeAudit 的既有预算截断兜底 */
    private static String templateDetail(CouponTemplate row) {
        return "key=" + row.getCouponKey() + ",title=" + row.getTitle()
                + ",threshold=" + row.getThreshold() + ",amount=" + row.getAmount()
                + ",validDays=" + row.getValidDays() + ",limitPerUser=" + row.getLimitPerUser()
                + ",totalQuota=" + row.getTotalQuota() + ",status=" + row.getStatus();
    }

    // ==================================================================
    // F-114 自提点存量管理（两个操作：全量列表 / 部分更新白名单八字段，复用本类既有的
    // storeMapper——入驻审核 approve 已在用）。写端点成功后写审计 store.update，
    // before/after 只记实际变化的键（契约 §1.2，与 F-113 的完整快照口径不同）。
    // 红线（契约 §3）：C 端读路径、主理人端链路、OrderServiceImpl/F-111 拦截一行不改——
    // 本段只写 store 列，拦截读列（dailyCapacity >0 才 COUNT）自然联动。
    // ==================================================================

    private static final String AUDIT_STORE_UPDATE = "store.update";
    private static final String AUDIT_TARGET_STORE = "store";

    private static final String STORE_NAME_INVALID_MSG = "门店名称须为 1-100 个字符";
    private static final String STORE_ADDRESS_INVALID_MSG = "门店地址须为 1-255 个字符";
    private static final String STORE_SERVICE_TOO_LONG_MSG = "服务说明最长 50 个字符";
    private static final String STORE_STATUS_INVALID_MSG = "status 仅允许 open/closed";
    private static final String STORE_OPEN_TIME_INVALID_MSG = "营业开始时间格式应为 HH:MM";
    private static final String STORE_CLOSE_TIME_INVALID_MSG = "营业结束时间格式应为 HH:MM";
    private static final String STORE_DAILY_CAPACITY_INVALID_MSG = "日接单容量须为大于等于 0 的整数";
    private static final String STORE_ACCEPTING_INVALID_MSG = "accepting 仅允许 0/1";
    private static final String STORE_NO_FIELDS_MSG =
            "无更新字段（可传 name/address/service/status/openTime/closeTime/dailyCapacity/accepting）";

    /** 营业时间形状（契约 §1.2 逐字；比主理人端 PartnerStoreUpdateDTO 的 {@code ^$|...} 严——
     *  本端点 null=不改，不存在「传空串清空」的语义，空串即形状非法） */
    private static final Pattern STORE_TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /**
     * 自提点全量列表：键序固定 {@code id,name,address,service,status,openTime,closeTime,
     * dailyCapacity,accepting,ownerUserId,createdAt}，顺序即 mapper 的 id 升序。
     * 手工组 Map 而非直出实体：出参白名单由本方法逐键定义（同 couponTemplates/seckillDishes 的处置）——
     * Store 实体上 C 端六列挂 {@code @JsonIgnore}（Store 类注释 D-23），本段根本不把实体丢出参。
     */
    @Override
    public List<Map<String, Object>> listStores() {
        List<Store> rows = storeMapper.selectList(
                new LambdaQueryWrapper<Store>().orderByAsc(Store::getId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Store row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("name", row.getName());
            line.put("address", row.getAddress());
            line.put("service", row.getService());
            line.put("status", row.getStatus());
            line.put("openTime", row.getOpenTime());
            line.put("closeTime", row.getCloseTime());
            line.put("dailyCapacity", row.getDailyCapacity());
            line.put("accepting", row.getAccepting());
            line.put("ownerUserId", row.getOwnerUserId());
            line.put("createdAt", row.getCreatedAt());
            out.add(line);
        }
        return out;
    }

    /**
     * 部分更新自提点：id 不存在 404「门店不存在」（先于一切 body 判定，照 updateCouponTemplate 的顺序）；
     * 白名单八列全空 400；校验对<b>传入值</b>判（trim/形状/值域），null=不改、不校验存量值
     * （存量本就合法）。落库白名单只有八列：owner_user_id/contact_phone/photos/lng/lat 的 DTO
     * 探测字段不进 patch，updateById 的 NOT_NULL 策略下 null = 不落该列 = 原值保留。
     * 成功后写审计 {@code store.update}，before/after 只记实际变化的键（值相同的键不进 detail）。
     */
    @Override
    public void updateStore(Long id, AdminStoreUpdateDTO dto, String actor) {
        Store existing = id == null ? null : storeMapper.selectById(id);
        if (existing == null) {
            throw new BizException(R.CODE_NOT_FOUND, "门店不存在");
        }
        if (dto == null) {
            throw new BizException(STORE_NO_FIELDS_MSG);
        }
        if (dto.getName() == null && dto.getAddress() == null && dto.getService() == null
                && dto.getStatus() == null && dto.getOpenTime() == null && dto.getCloseTime() == null
                && dto.getDailyCapacity() == null && dto.getAccepting() == null) {
            // 探测字段（ownerUserId 等）带了不算更新字段——白名单之外不存在「只改红线」的请求
            throw new BizException(STORE_NO_FIELDS_MSG);
        }
        // 合并新值（null=不改；name/address/service trim 后落库，校验在 trim 后的值上判）
        String name = dto.getName() == null ? existing.getName() : validateStoreName(dto.getName());
        String address = dto.getAddress() == null ? existing.getAddress() : validateStoreAddress(dto.getAddress());
        String service = dto.getService() == null ? existing.getService() : validateStoreService(dto.getService());
        String status = dto.getStatus() == null ? existing.getStatus() : validateStoreStatus(dto.getStatus());
        String openTime = dto.getOpenTime() == null ? existing.getOpenTime()
                : validateStoreTime(dto.getOpenTime(), STORE_OPEN_TIME_INVALID_MSG);
        String closeTime = dto.getCloseTime() == null ? existing.getCloseTime()
                : validateStoreTime(dto.getCloseTime(), STORE_CLOSE_TIME_INVALID_MSG);
        Integer dailyCapacity = dto.getDailyCapacity() == null
                ? existing.getDailyCapacity() : validateStoreDailyCapacity(dto.getDailyCapacity());
        Integer accepting = dto.getAccepting() == null
                ? existing.getAccepting() : validateStoreAccepting(dto.getAccepting());

        // before/after 只记实际变化的键（契约 §1.2）：值相同的键不进审计 detail
        List<String> changedOld = new ArrayList<>();
        List<String> changedNew = new ArrayList<>();
        collectStoreChange(changedOld, changedNew, "name", existing.getName(), name);
        collectStoreChange(changedOld, changedNew, "address", existing.getAddress(), address);
        collectStoreChange(changedOld, changedNew, "service", existing.getService(), service);
        collectStoreChange(changedOld, changedNew, "status", existing.getStatus(), status);
        collectStoreChange(changedOld, changedNew, "openTime", existing.getOpenTime(), openTime);
        collectStoreChange(changedOld, changedNew, "closeTime", existing.getCloseTime(), closeTime);
        collectStoreChange(changedOld, changedNew, "dailyCapacity", existing.getDailyCapacity(), dailyCapacity);
        collectStoreChange(changedOld, changedNew, "accepting", existing.getAccepting(), accepting);

        Store patch = new Store();
        patch.setId(id);
        patch.setName(name);
        patch.setAddress(address);
        patch.setService(service);
        patch.setStatus(status);
        patch.setOpenTime(openTime);
        patch.setCloseTime(closeTime);
        patch.setDailyCapacity(dailyCapacity);
        patch.setAccepting(accepting);
        // 刻意不 set ownerUserId/contactPhone/photos/lng/lat：白名单落库，探测字段带了也不生效
        storeMapper.updateById(patch);
        writeAudit(auditActor(actor), AUDIT_STORE_UPDATE, AUDIT_TARGET_STORE,
                String.valueOf(id), String.join(",", changedOld), String.join(",", changedNew));
    }

    /** name 校验：trim 后 1~100（契约 §1.2），返回 trim 值落库 */
    private static String validateStoreName(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new BizException(STORE_NAME_INVALID_MSG);
        }
        return trimmed;
    }

    /** address 校验：trim 后 1~255（契约 §1.2），返回 trim 值落库 */
    private static String validateStoreAddress(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new BizException(STORE_ADDRESS_INVALID_MSG);
        }
        return trimmed;
    }

    /** service 校验：trim 后 ≤50 可空（契约 §1.2），返回 trim 值落库 */
    private static String validateStoreService(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() > 50) {
            throw new BizException(STORE_SERVICE_TOO_LONG_MSG);
        }
        return trimmed;
    }

    private static String validateStoreStatus(String status) {
        if (!"open".equals(status) && !"closed".equals(status)) {
            throw new BizException(STORE_STATUS_INVALID_MSG);
        }
        return status;
    }

    private static String validateStoreTime(String raw, String message) {
        if (!STORE_TIME_PATTERN.matcher(raw).matches()) {
            throw new BizException(message);
        }
        return raw;
    }

    /** dailyCapacity 校验：null 或 ≥0（契约 §1.2）；0 合法——与 null 语义均为不限容（F-111 拦截条件 &gt;0 才拦） */
    private static Integer validateStoreDailyCapacity(Integer dailyCapacity) {
        if (dailyCapacity < 0) {
            throw new BizException(STORE_DAILY_CAPACITY_INVALID_MSG);
        }
        return dailyCapacity;
    }

    private static Integer validateStoreAccepting(Integer accepting) {
        if (accepting != 0 && accepting != 1) {
            throw new BizException(STORE_ACCEPTING_INVALID_MSG);
        }
        return accepting;
    }

    /** 变化收集（契约 §1.2「before/after 只记实际变化的键」）：Objects.equals 判等，值相同的键不进审计 detail */
    private static void collectStoreChange(List<String> oldKeys, List<String> newKeys,
                                           String key, Object oldVal, Object newVal) {
        if (!Objects.equals(oldVal, newVal)) {
            oldKeys.add(key + "=" + oldVal);
            newKeys.add(key + "=" + newVal);
        }
    }

    // ==================================================================
    // F-118 结算跑批 + 结算中心（三个操作，只写 settlement 一张表 + order_item.settle_id 一列；
    // settlement 只增不改——本段无任何 UPDATE/DELETE settlement 路径，冲销走下一期 refund 列承载，
    // 06 册 §4.5 定稿照抄；主理人佣金自 F-119 起在挂锚成功后同事务并入（⑦.5 追加步，单列 commission
    // 事务内回填，无作废/重开语义）、打款（轨道 B）/保证金（m1x）零涉及；
    // 下单/取消/支付链路零改动，本段只读它们产生的数据）。
    // 读端点不写审计；generate 成功后写审计 settlement.create（旁路走本类唯一那份 writeAudit）。
    // 新依赖与常量一律追加在文件末尾（本类被仓内行号指针引用的追加式纪律，同 F-63/F-113 段头注）。
    // ==================================================================

    /** 审计动作与目标类型（契约 §1.1「审计 settlement.create」；写侧唯一真源，action 列宽 VARCHAR(50)） */
    private static final String AUDIT_SETTLEMENT_CREATE = "settlement.create";
    private static final String AUDIT_TARGET_SETTLEMENT = "settlement";

    /** 记账态唯一运行期值（08 册 §2.2-5「settlement.status 停在 accounted」，m27 DDL DEFAULT 同值） */
    private static final String SETTLEMENT_STATUS_ACCOUNTED = "accounted";

    /** 生成账期四句 400/404（契约 §1.1 定性；404 文案照 updateSeckillDish 的「商品不存在」同构） */
    private static final String SETTLE_MERCHANT_NOT_FOUND_MSG = "商家不存在";
    private static final String SETTLE_PERIOD_ORDER_MSG = "账期起止无效（start 须早于 end）";
    private static final String SETTLE_PERIOD_DUPLICATE_MSG = "该商家此账期已生成";
    private static final String SETTLE_NO_CANDIDATES_MSG = "该商家本期无可结算明细";
    /** 挂锚影响行数不足（并发跑批双开的落败方）的对外文案：400 + 整单回滚（06 册定稿的回滚分支） */
    private static final String SETTLE_ANCHOR_RACE_MSG = "本期候选明细已被其他账单结算（并发冲突），请刷新后重试";
    private static final String SETTLE_DATE_SHAPE_MSG = "账期日期格式应为 YYYY-MM-DD";

    /** 账期日期形状闸门（照 BATCH_DATE_PATTERN 同一枚正则口径，不另立形状） */
    private static final Pattern SETTLE_DATE_PATTERN = BATCH_DATE_PATTERN;

    /** F-118 的新依赖（只被本段方法使用；追加式字段声明同 F-63/F-113 那批） */
    @Autowired
    private SettlementMapper settlementMapper;

    /** F-119 追加依赖：佣金记账（generateSettlement 挂锚成功后的同事务追加步 ⑦.5 调用，见该处注释） */
    @Autowired
    private PartnerWalletService partnerWalletService;

    /**
     * 生成账期（F-118 §1.1，判据顺序见接口注释）。聚合口径<b>逐字照契约</b>（selectSettleCandidates 的
     * 四条谓词），金额 gross=Σ(supply_price×qty)、refund=冲销、retain 恒 0、net=gross−refund−retain。
     * <p><b>事务与幂等（06 册 §4.5 定稿照抄）</b>：整个方法 {@code @Transactional}——
     * INSERT settlement → 挂锚条件 UPDATE，<b>影响行数=候选数才成功</b>，不足即抛异常整单回滚
     * （并发跑批双开只有一个成功，另一个全回滚，settlement 与挂锚两写同生同灭）。
     * uk_merchant_period 预查挡住「完全相同期重跑」，并发兜底 catch DuplicateKeyException 转同一句 400
     * （照 createCouponTemplate 的先查后插 + 并发兜底双保险姿势）。
     * <p>settle_no：06 册只定列与 uk、未给明文格式，按对账凭证语义取 "SET"+19 位雪花（≤VARCHAR(32)）。
     */
    @Override
    @Transactional
    public Map<String, Object> generateSettlement(AdminSettlementGenerateDTO dto, String actor) {
        // ① 请求自身缺陷（先于一切查库）：merchantId 缺失 / 日期形状或真日期非法
        Long merchantId = dto == null ? null : dto.getMerchantId();
        if (merchantId == null || merchantId <= 0) {
            throw new BizException("merchantId 必填");
        }
        LocalDate start = parseSettleDate(dto.getPeriodStart());
        LocalDate end = parseSettleDate(dto.getPeriodEnd());

        // ② merchant 存在（404，先于期序校验：目标都不存在谈不上账期）
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(R.CODE_NOT_FOUND, SETTLE_MERCHANT_NOT_FOUND_MSG);
        }

        // ③ start < end（400）
        if (!start.isBefore(end)) {
            throw new BizException(SETTLE_PERIOD_ORDER_MSG);
        }

        // ④ 同期重跑 → 400（uk_merchant_period 预查 + 并发兜底，幂等护栏之一）
        Long samePeriod = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getMerchantId, merchantId)
                .eq(Settlement::getPeriodStart, start)
                .eq(Settlement::getPeriodEnd, end));
        if (samePeriod != null && samePeriod > 0) {
            throw new BizException(SETTLE_PERIOD_DUPLICATE_MSG);
        }

        // ⑤ 聚合候选明细（契约 §1.1 逐字四谓词），空集 → 400「不生成空账单」
        List<Map<String, Object>> candidates = orderItemMapper.selectSettleCandidates(merchantId);
        if (candidates.isEmpty()) {
            throw new BizException(SETTLE_NO_CANDIDATES_MSG);
        }

        // 金额：gross=Σ(supply_price×qty)；order_count=去重订单数；itemCount=明细行数（对账口径之一）
        BigDecimal gross = BigDecimal.ZERO;
        Set<Long> candidateIds = new LinkedHashSet<>();
        Set<String> orderIds = new LinkedHashSet<>();
        for (Map<String, Object> line : candidates) {
            BigDecimal supply = (BigDecimal) line.get("supplyPrice");
            int qty = ((Number) line.get("quantity")).intValue();
            gross = gross.add(supply.multiply(BigDecimal.valueOf(qty)));
            candidateIds.add(((Number) line.get("id")).longValue());
            orderIds.add(String.valueOf(line.get("orderId")));
        }

        // 冲销（下一期 refund 列承载，06 册定稿）：上一账期止之后发生退款（pay_status 1→2）的已挂锚明细
        LocalDate prevPeriodEnd = settlementMapper.selectMaxPeriodEnd(merchantId);
        BigDecimal refund = prevPeriodEnd == null
                ? BigDecimal.ZERO : orderItemMapper.sumRefundAfterAnchor(merchantId, prevPeriodEnd);
        BigDecimal retain = BigDecimal.ZERO;   // 轨道 B 留存抵扣才有值，本片恒 0（列位保留）
        BigDecimal net = gross.subtract(refund).subtract(retain);

        // ⑥ INSERT settlement（status=accounted；gmv_amount 即 gross——F-118 §1.1 供货价基数口径）
        Settlement row = new Settlement();
        row.setSettleNo("SET" + com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr());
        row.setMerchantId(merchantId);
        row.setPeriodStart(start);
        row.setPeriodEnd(end);
        row.setOrderCount(orderIds.size());
        row.setGmvAmount(gross);
        row.setCouponDeduct(BigDecimal.ZERO);
        row.setCommission(BigDecimal.ZERO);
        row.setRetainAmount(retain);
        row.setDeduction(BigDecimal.ZERO);
        row.setRefundAmount(refund);
        row.setPayable(net);
        row.setStatus(SETTLEMENT_STATUS_ACCOUNTED);
        try {
            settlementMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // uk_merchant_period / uk_settle_no 的并发兜底：预查挡不住两人同时生成
            throw new BizException(SETTLE_PERIOD_DUPLICATE_MSG);
        }

        // ⑦ 挂锚条件 UPDATE（06 册定稿）：影响行数=候选数才成功，不足即抛异常整单回滚——
        // settlement INSERT 与挂锚同事务，回滚不留半截账单（并发跑批双开的落败方在这里败出）
        int anchored = orderItemMapper.attachSettle(row.getId(), new ArrayList<>(candidateIds));
        if (anchored != candidateIds.size()) {
            throw new BizException(SETTLE_ANCHOR_RACE_MSG);
        }

        // ⑦.5 佣金记账（F-119 §2 追加步，只新增不动上方任何既有行）：挂锚成功后同事务并入——
        // 去重订单按 orders.total_price×3% 记 grant、本期窗口（prevPeriodEnd）退款单已记佣金的负向冲回
        // （reversal 同批，05 册 A7.2）；返回净值；钱包/流水任何失败异常上抛 → @Transactional 整体回滚
        // （挂锚也回滚，账单与佣金原子一体，契约 §2-4）。settlement.commission 回填（§2-3）：净值落本账单
        // commission 列（m27 建列，F-118 时代恒 0）——单列事务内回填，F-118「只增不改」红线针对的
        // 作废/重开与 status·refund 改写不涉；净值=Σ(settlement_id 锚到本单的 flow.amount)，与钱包流水可互核。
        BigDecimal commissionNet = partnerWalletService.recordForSettlement(
                merchantId, row.getId(), orderIds, prevPeriodEnd);
        settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .eq(Settlement::getId, row.getId())
                .set(Settlement::getCommission, commissionNet));

        // ⑧ 审计 settlement.create（含 merchant/期/三金额/明细数；旁路，写失败不打断主事务）
        writeAudit(auditActor(actor), AUDIT_SETTLEMENT_CREATE, AUDIT_TARGET_SETTLEMENT,
                String.valueOf(row.getId()), null,
                "merchantId=" + merchantId
                        + ",period=" + start + "~" + end
                        + ",gross=" + gross + ",refund=" + refund + ",net=" + net
                        + ",items=" + candidateIds.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("settleNo", row.getSettleNo());
        out.put("merchantId", merchantId);
        out.put("merchantName", merchant.getName());
        out.put("periodStart", start.toString());
        out.put("periodEnd", end.toString());
        out.put("gross", gross);
        out.put("refund", refund);
        out.put("net", net);
        out.put("itemCount", candidateIds.size());
        out.put("orderCount", orderIds.size());
        out.put("status", row.getStatus());
        return out;
    }

    /** 账期日期解析：形状正则 + LocalDate 裁决真日期（照 AdminV2ServiceImpl#parseDate 的两道闸口径，非法 → 400） */
    private static LocalDate parseSettleDate(String raw) {
        if (raw == null || !SETTLE_DATE_PATTERN.matcher(raw.trim()).matches()) {
            throw new BizException(SETTLE_DATE_SHAPE_MSG);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeException e) {
            throw new BizException(SETTLE_DATE_SHAPE_MSG);
        }
    }

    /**
     * 账期列表（F-118 §1.2 + F-125 §1.2 分页/账期筛选）：按 id DESC 新账期在前返回一页。
     * 参数闸门逐字照 {@link #merchants} 族（pageNum/pageSize 缺省 1/20、值域 [1,100]、越界 400 不夹取）；
     * dateStart/dateEnd 空串归 null、非空过 {@link #parseSettleDate}（形状+真日期两道闸，非法 400），
     * 两值都非空且 start &gt; end 抛 400（start==end 是合法单日窗口，与生成端 start&lt;end 判据刻意不同）；
     * merchantId 可空，空=全量，非空只做过滤不校验存在（照 listStores 全量先例）。
     * merchantId/账期窗口过滤全部下沉 SettlementMapper#countSettlements/#selectSettlementsPage
     * （闭区间交叠语义见该 WHERE 常量注释）；offset 用 long 防 int 溢出，offset &gt;= total 短路回空列表
     * （照 merchants 族），total 仍真值。出参封口照 merchants 族：
     * {@code {list,total,pageNum,pageSize,filters:{merchantId,dateStart,dateEnd}}}，
     * filters 回显归一后的三值（date 为 null 或 YYYY-MM-DD 字符串）。
     * reconcileOk 实时算：一次批量聚合（selectAnchoredAggregates）拿各单挂锚行数 / 去重订单数 /
     * Σ(supply_price×qty) / 孤儿行数——三条件皆真才 true，不落列、每请求现算：
     * 明细金额被改（①打穿）、行被删（②打穿）、挂锚明细的 orders 行被删即孤儿（③打穿，
     * 2026-10-25 主会话验收裁决补的第三条件——此前孤儿态下弹窗空而对账仍 true，两口径矛盾）。
     * paidChannelAbsent 恒 true（记账态明示防前端漏显）。
     * 商家名批量查（selectBatchIds）不逐行查。periodStart/periodEnd 落 String（服务端定形，照 D-18）。
     */
    @Override
    public Map<String, Object> listSettlements(Long merchantId, Integer pageNum, Integer pageSize, String dateStart, String dateEnd) {
        int pageNo = pageNum == null ? LIST_DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < LIST_DEFAULT_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        int size = pageSize == null ? LIST_DEFAULT_PAGE_SIZE : pageSize;
        if (size < LIST_MIN_PAGE_SIZE || size > LIST_MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        // 账期窗口归一：空串（含纯空白）归 null；非空过 parseSettleDate 的两道闸（形状正则 + LocalDate 真日期）
        LocalDate startDate = dateStart == null || dateStart.trim().isEmpty() ? null : parseSettleDate(dateStart);
        LocalDate endDate = dateEnd == null || dateEnd.trim().isEmpty() ? null : parseSettleDate(dateEnd);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BizException(SETTLE_PERIOD_ORDER_MSG);
        }
        String fStart = startDate == null ? null : startDate.toString();
        String fEnd = endDate == null ? null : endDate.toString();

        long total = settlementMapper.countSettlements(merchantId, fStart, fEnd);
        long offset = (long) (pageNo - 1) * size;
        List<Settlement> rows = offset >= total
                ? List.of()
                : settlementMapper.selectSettlementsPage(merchantId, fStart, fEnd, offset, size);
        // 商家名映射（一次批量，无 N+1）
        Map<Long, String> merchantNames = new LinkedHashMap<>();
        Set<Long> merchantIds = new LinkedHashSet<>();
        Set<Long> settleIds = new LinkedHashSet<>();
        for (Settlement row : rows) {
            merchantIds.add(row.getMerchantId());
            settleIds.add(row.getId());
        }
        if (!merchantIds.isEmpty()) {
            for (Merchant m : merchantMapper.selectBatchIds(merchantIds)) {
                merchantNames.put(m.getId(), m.getName());
            }
        }
        // 对账聚合（一次批量，只对本页 settleIds；无账单时短路不下发空 IN）
        Map<Long, Map<String, Object>> anchored = new LinkedHashMap<>();
        if (!settleIds.isEmpty()) {
            for (Map<String, Object> agg : orderItemMapper.selectAnchoredAggregates(new ArrayList<>(settleIds))) {
                anchored.put(((Number) agg.get("settleId")).longValue(), agg);
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Settlement row : rows) {
            Map<String, Object> agg = anchored.get(row.getId());
            // reconcileOk 实时算（契约 §1.2，账面 vs 实时三条件皆真才 true）：
            // ① 账面 gross（gmv_amount）== 实时挂锚明细 Σ(supply_price×qty)；
            // ② 账面计数 == 实时挂锚计数——契约「明细数==itemCount」的账面明细行数在 settlement 表
            //    （06 册 §4.5 定稿列清单）无 item_count 落点，计数对账以表内唯一计数列 order_count
            //    对实时去重订单数承载（明细行被删/改锚在该半条件打穿）；
            // ③ 挂锚明细无孤儿——孤儿（orders 行被删）明细的弹窗读不到 pay_status，与「对得上」
            //    天然矛盾，orphanCount>0 即数据事故 → false（宁可错杀不可漏报）。
            // 无聚合行（锚被清，理论不可达）也 false——对账标志宁可错杀不可漏报。
            boolean reconcileOk = false;
            if (agg != null) {
                BigDecimal anchoredGross = (BigDecimal) agg.get("gross");
                long anchoredOrders = ((Number) agg.get("orderCount")).longValue();
                long orphanCount = ((Number) agg.get("orphanCount")).longValue();
                reconcileOk = anchoredGross != null
                        && anchoredGross.compareTo(row.getGmvAmount()) == 0
                        && anchoredOrders == row.getOrderCount()
                        && orphanCount == 0;
            }
            // itemCount 出参取实时挂锚行数（账面无列，实时即真值；明细弹窗行数与此天然一致）
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.getId());
            line.put("settleNo", row.getSettleNo());
            line.put("merchantId", row.getMerchantId());
            line.put("merchantName", merchantNames.get(row.getMerchantId()));
            line.put("periodStart", row.getPeriodStart() == null ? null : row.getPeriodStart().toString());
            line.put("periodEnd", row.getPeriodEnd() == null ? null : row.getPeriodEnd().toString());
            line.put("gross", row.getGmvAmount());
            line.put("refund", row.getRefundAmount());
            line.put("retain", row.getRetainAmount());
            line.put("net", row.getPayable());
            // F-119 §3：commission 列显值（F-118 列已建 m27，F-118 时代恒 0；F-119 起为跑批佣金净值）
            line.put("commission", row.getCommission());
            line.put("itemCount", agg == null ? 0 : ((Number) agg.get("lineCount")).intValue());
            line.put("status", row.getStatus());
            line.put("reconcileOk", reconcileOk);
            line.put("paidChannelAbsent", Boolean.TRUE);   // 记账态明示：打款通道未接（轨道 B）
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("merchantId", merchantId);
        filters.put("dateStart", fStart);
        filters.put("dateEnd", fEnd);

        // 键名与键序逐字照 merchants 族的分页封口（不新造形状）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        data.put("filters", filters);
        return data;
    }

    /**
     * 账单明细（F-118 §1.3）：结算单不存在 404「结算单不存在」（先于任何查询下推——
     * id 连行都没有，明细集为空与单不存在是两种语义）；行键照契约七项
     * {@code itemId,dishName,supplyPrice,qty,subtotal,orderId,payStatus,fulfillStatus}
     * + {@code orphan}（2026-10-25 主会话验收裁决：orders 行被删的孤儿行不再被 JOIN 静默丢掉，
     * pay_status 显 null、orphan 标记显 true，前端标「订单已不存在」警示）。
     * SQL 侧 orphan 是 1/0 整数，本方法归一为 Boolean 下发（出参键序与 SQL 列序同源）。
     */
    @Override
    public List<Map<String, Object>> settlementItems(Long id) {
        if (id == null || settlementMapper.selectById(id) == null) {
            throw new BizException(R.CODE_NOT_FOUND, "结算单不存在");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : orderItemMapper.selectAnchoredItems(id)) {
            Map<String, Object> line = new LinkedHashMap<>(raw);
            Object orphan = raw.get("orphan");
            line.put("orphan", orphan != null && ((Number) orphan).intValue() != 0);
            out.add(line);
        }
        return out;
    }

    // ==================================================================
    // F-122 封禁体系（用户/商家列表 + 封禁解封，全部追加到文件末尾——
    // 本类被仓内行号指针引用，只追加不插行，理由同 F-106 片 B2 / F-107 段注释）。
    // 读端点不写审计；写端点只在<b>实际状态迁移</b>时写一行 ban.create / unban
    // （幂等 no-op 不留痕，契约 §7-8，与 auditDish「幂等照记」刻意不同）。
    // 红线：C 端可见性零改动（封禁商家商品照常展示，仅下单由 OrderServiceImpl 拦）；
    // 两域零级联——封 user 不动其 merchant/store/settlement/order，封 merchant 不动其 user 账号；
    // merchant 封禁复用既有 status='suspended'，terminated 不可经本组方法变更。
    // ==================================================================

    /** 用户状态白名单（F-122 §3.4.1 逐字两态） */
    private static final Set<String> USER_STATUSES = Set.of("active", "banned");

    /** 商家状态白名单（F-122 §3.4.2 逐字三态，与 merchant 实体既有枚举同集合） */
    private static final Set<String> MERCHANT_STATUSES = Set.of("active", "suspended", "terminated");

    /** 封禁动作白名单与 400 文案（契约 §3.4.1/§3.4.2 逐字） */
    private static final String ACTION_BAN = "ban";
    private static final String ACTION_UNBAN = "unban";
    private static final String BAN_ACTION_MSG = "action 仅允许 ban/unban";

    /** 商家 terminated/脏值不可变更封禁态的 400 文案（契约 §3.4.2 逐字） */
    private static final String MERCHANT_LOCKED_MSG = "该商家已清退，不可变更封禁状态";

    /**
     * 运营端用户列表（F-122 §3.4.1）：参数闸门、排序与出参口径见接口注释。
     *
     * <p>分页照 {@link #listMerchantApplies} 范式：无 MP 分页插件，页 + 独立 COUNT 两条手写 SQL；
     * 越界脏值一律 400、不夹取，offset 用 long 防 int 溢出，offset &gt;= total 短路回空列表。
     * <p>status 空=全部，非空 Locale.ROOT 归一小写后过 {active,banned} 白名单；
     * phone trim 后精确等值（不做 LIKE）；filters 回显归一后的三值。
     * <p>行内 phone 必过 {@link PhoneMask#mask}；password_hash/invite_code/balance/avatar
     * 等列在 Mapper 的 SELECT 白名单里根本不存在（把「不输出」写成「不查询」）。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> users(Integer pageNum, Integer pageSize, Long userId, String phone, String status) {
        int pageNo = pageNum == null ? LIST_DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < LIST_DEFAULT_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        int size = pageSize == null ? LIST_DEFAULT_PAGE_SIZE : pageSize;
        if (size < LIST_MIN_PAGE_SIZE || size > LIST_MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        if (userId != null && userId <= 0) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }

        String fPhone = phone == null ? null : phone.trim();
        if (fPhone != null && fPhone.isEmpty()) {
            fPhone = null;
        }
        String fStatus = status == null ? null : status.trim();
        if (fStatus != null && fStatus.isEmpty()) {
            fStatus = null;
        }
        if (fStatus != null) {
            fStatus = fStatus.toLowerCase(Locale.ROOT);
            if (!USER_STATUSES.contains(fStatus)) {
                throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
            }
        }

        long total = userMapper.countUsers(userId, fPhone, fStatus);
        long offset = (long) (pageNo - 1) * size;
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : userMapper.selectUsersPage(userId, fPhone, fStatus, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("phone", PhoneMask.mask((String) row.get("phone")));
            line.put("role", row.get("role"));
            line.put("merchantId", row.get("merchantId"));
            line.put("status", row.get("status"));
            line.put("createdAt", row.get("createdAt"));
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("userId", userId);
        // 脱敏回显：筛选输入是明文，但响应全文不得出现明文手机号（契约 §3.4.1/§5.2 红线）
        filters.put("phone", PhoneMask.mask(fPhone));
        filters.put("status", fStatus);

        // 键名与键序逐字照 AdminV2ServiceImpl#pageResult / listMerchantApplies（不新造形状）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        data.put("filters", filters);
        return data;
    }

    /**
     * 封禁/解封用户（F-122 §3.4.1）：action trim 后仅 ban/unban；用户不存在 404「用户不存在」；
     * ban → banned、unban → active。已是目标态即幂等成功：不写库、不写审计。
     * 实际迁移时写审计 ban.create / unban，detail 走 {@link #buildAuditDetail} 的 {"old","new"}。
     * <b>零级联</b>：不动该用户的 merchant/store/settlement/order，也不动 token——
     * 已签发 token 由 {@code AuthInterceptor} 每请求查 {@code user.status} 实时失效。
     */
    @Override
    @Transactional
    public String updateUserStatus(Long userId, String action, String actor) {
        String act = action == null ? "" : action.trim();
        if (!ACTION_BAN.equals(act) && !ACTION_UNBAN.equals(act)) {
            throw new BizException(R.CODE_BAD_REQUEST, BAN_ACTION_MSG);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(R.CODE_NOT_FOUND, "用户不存在");
        }
        String old = user.getStatus();
        String target;
        if (ACTION_BAN.equals(act)) {
            if (User.STATUS_BANNED.equals(old)) {
                return "用户已处于封禁状态"; // 幂等 no-op：不写库、不写审计
            }
            target = User.STATUS_BANNED;
        } else {
            if (User.STATUS_ACTIVE.equals(old)) {
                return "用户已处于正常状态"; // 幂等 no-op：不写库、不写审计
            }
            target = User.STATUS_ACTIVE;
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getStatus, target));
        writeAudit(auditActor(actor), ACTION_BAN.equals(act) ? "ban.create" : "unban",
                "user", String.valueOf(userId), old, target);
        return ACTION_BAN.equals(act) ? "用户已封禁" : "用户已解封";
    }

    /**
     * 运营端商家列表（F-122 §3.4.2）：出参形状同 {@link #users}；行 {@code {id,name,contactPhone,
     * contactName,status,createdAt}}，contactPhone 必过 {@link PhoneMask#mask}。
     * status 白名单 {active,suspended,terminated}，其余闸门与用户列表同款；排序 id ASC。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> merchants(Integer pageNum, Integer pageSize, Long merchantId, String status) {
        int pageNo = pageNum == null ? LIST_DEFAULT_PAGE_NUM : pageNum;
        if (pageNo < LIST_DEFAULT_PAGE_NUM) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        int size = pageSize == null ? LIST_DEFAULT_PAGE_SIZE : pageSize;
        if (size < LIST_MIN_PAGE_SIZE || size > LIST_MAX_PAGE_SIZE) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }
        if (merchantId != null && merchantId <= 0) {
            throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
        }

        String fStatus = status == null ? null : status.trim();
        if (fStatus != null && fStatus.isEmpty()) {
            fStatus = null;
        }
        if (fStatus != null) {
            fStatus = fStatus.toLowerCase(Locale.ROOT);
            if (!MERCHANT_STATUSES.contains(fStatus)) {
                throw new BizException(R.CODE_BAD_REQUEST, BAD_REQUEST_MSG);
            }
        }

        long total = merchantMapper.countMerchants(merchantId, fStatus);
        long offset = (long) (pageNo - 1) * size;
        List<Map<String, Object>> rows = offset >= total
                ? List.of()
                : merchantMapper.selectMerchantsPage(merchantId, fStatus, offset, size);

        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", row.get("id"));
            line.put("name", row.get("name"));
            line.put("contactPhone", PhoneMask.mask((String) row.get("contactPhone")));
            line.put("contactName", row.get("contactName"));
            line.put("status", row.get("status"));
            line.put("createdAt", row.get("createdAt"));
            list.add(line);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("merchantId", merchantId);
        filters.put("status", fStatus);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("pageNum", pageNo);
        data.put("pageSize", size);
        data.put("filters", filters);
        return data;
    }

    /**
     * 封禁/解封商家（F-122 §3.4.2）：ban → suspended、unban → active；已是目标态即幂等成功
     * （不写库、不写审计）；terminated/脏值 → 400「该商家已清退，不可变更封禁状态」；
     * 商家不存在 404「商家不存在」。
     * <b>零级联</b>：只写 merchant.status 一列——不下架商品、不清秒杀/免费领池、不动 merchant_store
     * 与其 user 账号；商家端写闸门由既有 {@code MerchantServiceImpl#requireActive} 联动。
     */
    @Override
    @Transactional
    public String updateMerchantStatus(Long merchantId, String action, String actor) {
        String act = action == null ? "" : action.trim();
        if (!ACTION_BAN.equals(act) && !ACTION_UNBAN.equals(act)) {
            throw new BizException(R.CODE_BAD_REQUEST, BAN_ACTION_MSG);
        }
        Merchant merchant = merchantMapper.selectById(merchantId);
        if (merchant == null) {
            throw new BizException(R.CODE_NOT_FOUND, "商家不存在");
        }
        String old = merchant.getStatus();
        String target;
        if (ACTION_BAN.equals(act)) {
            if ("suspended".equals(old)) {
                return "商家已处于封禁状态"; // 幂等 no-op：不写库、不写审计
            }
            if (!"active".equals(old)) {
                throw new BizException(R.CODE_BAD_REQUEST, MERCHANT_LOCKED_MSG);
            }
            target = "suspended";
        } else {
            if ("active".equals(old)) {
                return "商家已处于正常营业状态"; // 幂等 no-op：不写库、不写审计
            }
            if (!"suspended".equals(old)) {
                throw new BizException(R.CODE_BAD_REQUEST, MERCHANT_LOCKED_MSG);
            }
            target = "active";
        }
        merchantMapper.update(null, new LambdaUpdateWrapper<Merchant>()
                .eq(Merchant::getId, merchantId)
                .set(Merchant::getStatus, target));
        writeAudit(auditActor(actor), ACTION_BAN.equals(act) ? "ban.create" : "unban",
                "merchant", String.valueOf(merchantId), old, target);
        return ACTION_BAN.equals(act) ? "商家已封禁" : "商家已解封";
    }

    // ==================================================================
    // P6-3 §1 运营端 CSV 导出（两个导出方法与配套私有件，全部追加到文件末尾——
    // 本文件被仓内多条「AdminServiceImpl:行号」指针引用，只追加不插行，理由同 F-122 段注释）。
    // 【审计偏离登记】（契约 §1 逐字要求写明）：order.export / bill.export 两只读端点各写一条
    // 审计——这是对「只读端点不写审计」惯例的显式偏离。理由：批量数据出库问责，出库无痕则
    // 「谁在何时以什么筛选拉走了多少行」无从追责，审计面只盯写入不看流出是缺口
    // （完整表述见 AdminService 接口同段登记）。
    // 红线：既有列表端点一行不改（orders 取数走 F-20.18 同一组 SQL、settlements 逐页复用
    // listSettlements 本方法）；orders 导出手机号必过 PhoneMask（PII 面不扩大）；
    // 手写 StringBuilder 拼 CSV、不用第三方库；无 DDL 无 yml。
    // ==================================================================

    /** 单次导出行数上限（契约 §1 逐字 5000）：分页循环拉全的硬顶，超出截断并在 CSV 尾注一行 */
    private static final int EXPORT_MAX_ROWS = 5000;

    /** 导出分页步长：取列表族 pageSize 值域上限（{@link #LIST_MAX_PAGE_SIZE}，settlements 侧闸门允许的最大页容） */
    private static final int EXPORT_PAGE_SIZE = LIST_MAX_PAGE_SIZE;

    /** 审计动作与订单目标类型（契约 §1「order.export / bill.export」逐字；action 列宽 VARCHAR(50)） */
    private static final String AUDIT_ORDER_EXPORT = "order.export";
    private static final String AUDIT_BILL_EXPORT = "bill.export";
    private static final String AUDIT_TARGET_ORDER = "order";
    // 账单导出的目标类型复用本类既有的 AUDIT_TARGET_SETTLEMENT（"settlement"，与 settlement.create 同一真源）

    /** 导出审计 target_id 的占位符：批量出库无单一目标行，列 NOT NULL 落 "-"（同 recordNotifyReject 口径） */
    private static final String AUDIT_EXPORT_TARGET_ID_PLACEHOLDER = "-";

    /** orders 导出无筛选参数：审计 filters 恒空对象（detail 恒定三键形状，兜底同形状） */
    private static final String EXPORT_FILTERS_EMPTY = "{}";

    /** CSV 首字符 BOM（契约 §1 \uFEFF）：Excel 打开 UTF-8 中文不乱码 */
    private static final char CSV_BOM = '\uFEFF';

    /** CSV 行分隔符照 RFC 4180 用 CRLF */
    private static final String CSV_EOL = "\r\n";

    @Override
    public byte[] exportOrdersCsv(String actor) {
        // 复用 F-20.18 订单列表同一组 SQL（countOrders + selectOrdersPage），无筛选全量：
        // WHERE/ORDER BY/列别名与 AdminV2ServiceImpl#orders 逐字同源，本批零新增 SQL
        long total = orderMapper.countOrders(null, null, null);
        long limit = Math.min(total, EXPORT_MAX_ROWS);
        StringBuilder csv = new StringBuilder().append(CSV_BOM)
                .append("订单号,下单时间,状态,自提日期,自提点,手机号,商品明细,合计金额").append(CSV_EOL);
        long exported = 0;
        while (exported < limit) {
            List<Map<String, Object>> rows = orderMapper.selectOrdersPage(
                    null, null, null, exported, EXPORT_PAGE_SIZE);
            if (rows.isEmpty()) {
                break;   // 并发删空兜底：COUNT 与页之间数据消失即止，不再空转
            }
            for (Map<String, Object> row : rows) {
                if (exported >= limit) {
                    break;
                }
                csv.append(csvCell(row.get("id"))).append(',')
                        .append(csvCell(row.get("createTime"))).append(',')
                        .append(csvCell(orderStatusText((String) row.get("status")))).append(',')
                        .append(csvCell(row.get("pickupDate"))).append(',')
                        .append(csvCell(row.get("storeName"))).append(',')
                        // PII 红线：手机号出库前必过 PhoneMask，明文零出现（同 v2 orders 面）
                        .append(csvCell(PhoneMask.mask((String) row.get("phone")))).append(',')
                        .append(csvCell(orderItemsText((String) row.get("id")))).append(',')
                        .append(csvCell(row.get("totalPrice"))).append(CSV_EOL);
                exported++;
            }
        }
        appendTruncationNote(csv, total, exported);
        writeExportAudit(AUDIT_ORDER_EXPORT, AUDIT_TARGET_ORDER, actor, exported, total, EXPORT_FILTERS_EMPTY);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] exportSettlementsCsv(Long merchantId, String dateStart, String dateEnd, String actor) {
        // 筛选归一只做空白归 null（与 listSettlements 第一道归一同款）；非法日期/起止倒挂由
        // listSettlements 的闸门 400——导出失败即不写审计（只有成功出库才留痕）
        String fStart = dateStart == null || dateStart.trim().isEmpty() ? null : dateStart.trim();
        String fEnd = dateEnd == null || dateEnd.trim().isEmpty() ? null : dateEnd.trim();
        StringBuilder csv = new StringBuilder().append(CSV_BOM)
                .append("账单号,商家,账期起,账期止,gross,refund,net,佣金,明细数,状态,对账").append(CSV_EOL);
        long exported = 0;
        long total = 0;
        // 逐页复用 listSettlements 拉全：对账三条件/商家名映射/日期闸门与列表同源，防口径漂移
        for (int pageNo = 1; exported < EXPORT_MAX_ROWS; pageNo++) {
            Map<String, Object> page = listSettlements(merchantId, pageNo, EXPORT_PAGE_SIZE, fStart, fEnd);
            total = ((Number) page.get("total")).longValue();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("list");
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows) {
                if (exported >= EXPORT_MAX_ROWS) {
                    break;
                }
                csv.append(csvCell(row.get("settleNo"))).append(',')
                        .append(csvCell(row.get("merchantName"))).append(',')
                        .append(csvCell(row.get("periodStart"))).append(',')
                        .append(csvCell(row.get("periodEnd"))).append(',')
                        .append(csvCell(row.get("gross"))).append(',')
                        .append(csvCell(row.get("refund"))).append(',')
                        .append(csvCell(row.get("net"))).append(',')
                        .append(csvCell(row.get("commission"))).append(',')
                        .append(csvCell(row.get("itemCount"))).append(',')
                        .append(csvCell(settlementStatusText((String) row.get("status")))).append(',')
                        // 对账列（契约 §1）：Boolean→✓/✗ 的导出层定形，照 Settlements.vue 页面同款
                        .append(Boolean.TRUE.equals(row.get("reconcileOk")) ? "✓" : "✗").append(CSV_EOL);
                exported++;
            }
            if (exported >= EXPORT_MAX_ROWS || (long) pageNo * EXPORT_PAGE_SIZE >= total) {
                break;
            }
        }
        appendTruncationNote(csv, total, exported);
        String filtersJson = "{\"merchantId\":" + (merchantId == null ? "null" : merchantId)
                + ",\"dateStart\":" + jsonValue(fStart) + ",\"dateEnd\":" + jsonValue(fEnd) + "}";
        writeExportAudit(AUDIT_BILL_EXPORT, AUDIT_TARGET_SETTLEMENT, actor, exported, total, filtersJson);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 截断尾注（契约 §1「超限截断并在 CSV 尾注一行」）：仅超限时追加一行，注明数据源总行数与
     * 实际导出行数；尾注不含英文逗号/引号/换行，无需引号包裹（RFC 4180 最小转义集内零特殊字符）。
     */
    private static void appendTruncationNote(StringBuilder csv, long total, long exported) {
        if (total > EXPORT_MAX_ROWS) {
            csv.append("注：数据源共 ").append(total).append(" 行，超出单次导出上限 ")
                    .append(EXPORT_MAX_ROWS).append(" 行，本文件仅含前 ").append(exported).append(" 行").append(CSV_EOL);
        }
    }

    /**
     * 导出审计（本面唯一给只读端点写审计的地方，偏离理由见本段登记）：走本类唯一那份七参
     * writeAudit 旁路（写失败不打断导出主流程），detail 恒定三键
     * {@code {"rows":N,"truncated":b,"filters":…}}，兜底同形状三键（rows=-1 表未知，不虚构截断事实）；
     * truncated 与尾注同一判据（total &gt; 上限），恰达上限不误报截断。
     */
    private void writeExportAudit(String action, String targetType, String actor,
                                  long rows, long total, String filtersJson) {
        String detail = "{\"rows\":" + rows + ",\"truncated\":" + (total > EXPORT_MAX_ROWS)
                + ",\"filters\":" + filtersJson + "}";
        writeAudit(auditActor(actor), action, targetType, AUDIT_EXPORT_TARGET_ID_PLACEHOLDER,
                detail, "{\"rows\":-1,\"truncated\":false,\"filters\":{}}", currentIp());
    }

    /** 单元格定形（RFC 4180 最小转义）：null 出空串；BigDecimal 出两位小数（金额列同 moneyText 口径）；值含 " , CR LF 才整体加引号且 " 双写 */
    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof BigDecimal decimal
                ? decimal.setScale(2, RoundingMode.HALF_UP).toPlainString()
                : String.valueOf(value);
        boolean needQuote = text.indexOf('"') >= 0 || text.indexOf(',') >= 0
                || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0;
        return needQuote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    /** 某单的商品明细定形：order_item 行的「品名×数量」以 / 连接（契约 §1；空明细出空串） */
    private String orderItemsText(String orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        StringBuilder text = new StringBuilder();
        for (OrderItem item : items) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(item.getDishName()).append('×').append(item.getQuantity());
        }
        return text.toString();
    }

    /**
     * 订单状态文案（导出层定形，照运营页 Orders.vue 的 STATUS_TEXT 同款三态；未知值原样透传不编词——
     * 状态机的唯一真源仍是 orders.status 列，本方法只做「人读」符号映射，照对账列 ✓/✗ 同一性质）
     */
    private static String orderStatusText(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "pending_pickup" -> "待自提";
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            default -> status;
        };
    }

    /** 账单状态文案（导出层定形，照 Settlements.vue 的 accounted→记账态同款；其余值原样透传） */
    private static String settlementStatusText(String status) {
        if (status == null) {
            return "";
        }
        return "accounted".equals(status) ? "记账态" : status;
    }
}
