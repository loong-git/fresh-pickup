package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
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
import com.fresh.entity.User;
import com.fresh.mapper.AdminAuditLogMapper;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.MerchantMapper;
import com.fresh.mapper.MerchantProfileMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.ReviewMapper;
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
 */
@Slf4j
@Service
public class AdminServiceImpl implements AdminService {

    /** T-M3-09：管理端仅允许置为这两种状态（核销 completed / 取消 cancelled），不可回退待自提 */
    private static final Set<String> ALLOWED_TARGETS = Set.of(
            OrderServiceImpl.STATUS_COMPLETED, OrderServiceImpl.STATUS_CANCELLED);

    /** T-M4-05 收口：评价审核动作白名单（approve 过审展示 / reject 驳回删除） */
    private static final Set<String> ALLOWED_AUDIT_ACTIONS = Set.of("approve", "reject");

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

    /** 管理密钥：与 AdminController.checkKey 同源配置（ADMIN_KEY 环境变量优先），仅用于算摘要做审计 actor */
    @Value("${ADMIN_KEY:admin-dev-key}")
    private String adminKey;

    @Override
    @Transactional
    public void updateOrderStatus(String orderId, String status) {
        String target = status == null ? "" : status.trim();
        if (!ALLOWED_TARGETS.contains(target)) {
            throw new BizException("status 仅允许 completed（核销）或 cancelled（取消）");
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
        writeAudit("admin:" + adminKeyDigest(), "order.status", "order", orderId, order.getStatus(), target);
    }

    @Override
    public void updateDish(Long dishId, AdminDishUpdateDTO dto) {
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
        writeAudit("admin:" + adminKeyDigest(), "dish.update", "dish", String.valueOf(dishId),
                "price=" + dish.getPrice() + ",stock=" + dish.getStock() + ",onSale=" + dish.getOnSale(),
                "price=" + dto.getPrice() + ",stock=" + dto.getStock() + ",onSale=" + dto.getOnSale());
    }

    @Override
    public void auditReview(Long reviewId, String action) {
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
        writeAudit("admin:" + adminKeyDigest(), "review.audit", "review", String.valueOf(reviewId),
                review.getAuditStatus(), op);
    }

    @Override
    @Transactional
    public void createMerchant(AdminMerchantCreateDTO dto) {
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
        // 3) 同事务：INSERT merchant → INSERT merchant_profile(approved) → UPDATE user role/merchant_id
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

        u.setRole("merchant");                          // 绑定与解锁分离（06 §5.3.3）：代开通即过审即解锁
        u.setMerchantId(m.getId());
        try {
            userMapper.updateById(u);                   // uk_user_merchant UNIQUE 兜底防并发重复绑定
        } catch (DuplicateKeyException e) {
            // 并发双开通：第二笔 UPDATE 撞 UNIQUE → 与本方法开头的前置判定同文案（幂等语义一致）
            throw new BizException(R.CODE_BAD_REQUEST, "该手机号已绑定商家");
        }

        // 4) 审计（R6 红线：Admin 新端点随建随写 admin_audit_log）
        writeAudit("admin:" + adminKeyDigest(), "merchant.create", "merchant", String.valueOf(m.getId()),
                null, "name=" + dto.getName() + ",phone=" + maskPhone(dto.getPhone()));
    }

    /**
     * 审计落库（R6/M-04）：actor/action/target_type/target_id/detail(旧值→新值 JSON)/ip。
     * 审计是旁路——整体 try-catch，写失败只 log.warn，绝不抛异常打断主事务（契约 §2.10b 红线）。
     */
    private void writeAudit(String actor, String action, String targetType, String targetId,
                            String oldVal, String newVal) {
        try {
            AdminAuditLog row = new AdminAuditLog();
            row.setActor(actor);
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setDetail("{\"old\":" + jsonValue(oldVal) + ",\"new\":" + jsonValue(newVal) + "}");
            row.setIp(currentIp());
            adminAuditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("审计写入失败（旁路，不影响主事务）: action={}, targetType={}, targetId={}",
                    action, targetType, targetId, e);
        }
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

    /** 138****1234（逐字复刻 OrderServiceImpl#maskPhone:347-349；代开通 DTO @Pattern 保证 11 位，同既有三份实现不加守卫） */
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

    /** detail JSON 值序列化：null → null，其余带引号并转义反斜杠/双引号（值均为服务端可控短串） */
    private String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
