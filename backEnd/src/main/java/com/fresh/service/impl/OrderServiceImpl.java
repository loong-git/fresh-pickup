package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.OrderCreateDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Store;
import com.fresh.entity.UserCoupon;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.service.CouponService;
import com.fresh.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    /** 订单状态三态（T-M1-07） */
    public static final String STATUS_PENDING_PICKUP = "pending_pickup";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    /**
     * 限购兜底表：T-M4-03 起 dish.limit_buy 列为权威数据源（种子 [2,5,8] 按 id 循环），
     * 列值为空/非法时退回本表，保证校验不落空。
     */
    private static final int[] LIMIT_BUY_TABLE = {2, 5, 8};

    /** 分页上界：防大 pageSize 拖库 */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 未支付订单超时关单阈值（T-超时关单，运营口径可调）：status=pending_pickup 且
     * pay_status=0 且 create_time 距今超过该分钟数即自动关单回补库存/券；
     * 扫描频率由 OrderTimeoutTask 控制（60 秒，生产可调大）
     */
    public static final int ORDER_TIMEOUT_MINUTES = 30;

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private CouponService couponService;

    @Override
    @Transactional
    public Order createOrder(OrderCreateDTO dto, Long userId) {
        // T-M2-03 归属：userId 由服务端从 token 解析（拦截器保证非空，此处兜底），DTO 不收 userId
        if (userId == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        String clientRequestId = dto.getClientRequestId().trim();

        // T-M1-09 幂等快查：同 clientRequestId 重复提交直接返回首次订单（code:200 同一 id）
        Order existing = selectByClientRequestId(clientRequestId, false);
        if (existing != null) {
            loadItems(Collections.singletonList(existing));
            return existing;
        }

        // —— 以下全部为校验/读取，首个写操作是下方 insert 主单 ——
        // T-M1-03 服务端计价：按 dish 现价重算，不信任前端任何金额字段
        BigDecimal totalPrice = BigDecimal.ZERO;
        Order order = new Order();
        order.setItems(new ArrayList<>());
        order.setClientRequestId(clientRequestId);
        order.setPhone(dto.getPhone());
        order.setUserId(userId);
        order.setPayStatus(0); // T-M2-05 初始未支付
        order.setStatus(STATUS_PENDING_PICKUP);
        order.setAddress(dto.getAddress() == null ? "" : dto.getAddress().trim());
        // T-M4-01 自提点快照：传 storeId 才查 store 落 name/address（不校验营业状态——契约服务端仅快照，
        // 停业置灰由 pickup 页负责）；未传兼容旧链路不落。storeId 无效直接 400，避免落脏快照。
        if (dto.getStoreId() != null) {
            Store store = storeMapper.selectById(dto.getStoreId());
            if (store == null) {
                throw new BizException("自提点不存在，请重新选择");
            }
            order.setStoreId(store.getId());
            order.setStoreName(store.getName());
            order.setStoreAddress(store.getAddress());
        }
        // T-M3-04 截单裁决：服务端按服务器当前时间裁决自提日期（不信任前端传参）——
        // 23:00 前下单 → 明天自提；23:00 及之后 → 后天自提。落库 pickup_date 并随下单响应返回。
        order.setPickupDate(resolvePickupDate(LocalDateTime.now()));

        for (OrderCreateDTO.OrderItemDTO itemDTO : dto.getItems()) {
            Dish dish = dishMapper.selectById(itemDTO.getDishId());
            if (dish == null) {
                throw new BizException("包含已下架商品，请重新选择");
            }
            int quantity = itemDTO.getQuantity();
            // T-M4-03 限购以 dish.limit_buy 真实列为准（种子 [2,5,8] 循环），空值退回兜底表
            Integer limitBuyCol = dish.getLimitBuy();
            int limitBuy = (limitBuyCol != null && limitBuyCol > 0)
                    ? limitBuyCol : LIMIT_BUY_TABLE[(int) (dish.getId() % LIMIT_BUY_TABLE.length)];
            if (quantity > limitBuy) {
                throw new BizException("「" + dish.getName() + "」限购" + limitBuy + "件");
            }
            // T-M1-05 条件 UPDATE 原子扣减：影响行数=0 即库存不足 → 抛出后整单回滚
            int decreased = dishMapper.decreaseStock(dish.getId(), quantity);
            if (decreased == 0) {
                throw new BizException("「" + dish.getName() + "」库存不足");
            }
            // T-M4-03 秒杀计价：以本事务读出的 dish 行为准，秒杀件按 seckill_price 与前端购物车同口径，
            // 否则现价；服务端重算原则不变（不信任前端任何金额字段），券门槛金额随秒杀价口径联动。
            // 销量不在下单时累加：统一在支付成功点（MockPayService updated>0 分支）计，未支付/取消不虚计
            BigDecimal unitPrice = (dish.getSeckill() != null && dish.getSeckill() == 1 && dish.getSeckillPrice() != null)
                    ? dish.getSeckillPrice() : dish.getPrice();
            totalPrice = totalPrice.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));

            OrderItem item = new OrderItem();
            item.setDishId(dish.getId());
            item.setDishName(dish.getName());
            item.setPrice(unitPrice); // 明细落成交单价快照（秒杀件为秒杀价），与 totalPrice 同口径
            item.setQuantity(quantity);
            item.setMerchantId(dish.getMerchantId()); // F-12/m16 供货商家快照（下单时自 dish.merchant_id 落库，防商家改归属/删品后历史漂移）
            order.getItems().add(item);
        }

        // T-M4-02 券校验（只读）：归属本人 + available + 小计满足 threshold，否则 400；
        // 金额重算与库存扣减同一事务，券不满足时在此抛错 → 已扣库存随事务回滚
        BigDecimal couponAmount = BigDecimal.ZERO;
        UserCoupon coupon = null;
        if (dto.getCouponId() != null) {
            coupon = couponService.getUsable(userId, dto.getCouponId(), totalPrice);
            couponAmount = coupon.getAmount();
        }
        // 抵扣后金额不低于 0（契约）
        order.setTotalPrice(totalPrice.subtract(couponAmount).max(BigDecimal.ZERO));

        try {
            orderMapper.insert(order); // ASSIGN_ID 此处生成雪花订单号
        } catch (DuplicateKeyException e) {
            // 并发同 clientRequestId 落到唯一约束：本事务尚无写操作，无副作用。
            // FOR UPDATE 锁定读绕过 REPEATABLE READ 快照，必能读到已提交的胜出订单。
            Order winner = selectByClientRequestId(clientRequestId, true);
            if (winner == null) {
                // 对方事务回滚的极端场景：让调用方重试
                throw new BizException("下单冲突，请重试");
            }
            loadItems(Collections.singletonList(winner));
            return winner;
        }
        order.getItems().forEach(item -> item.setOrderId(order.getId()));
        order.getItems().forEach(orderItemMapper::insert);
        // T-M4-02 券核销（条件 UPDATE 原子置 used + 回写 order_id）：并发共用一张券只有一次成功；
        // 0 行 → 抛错回滚整单（订单/明细/库存扣减一并回滚），与库存扣减同事务联动
        if (coupon != null && couponService.markUsed(coupon.getId(), userId, order.getId()) == 0) {
            throw new BizException("优惠券已被使用，请重新下单");
        }
        return order;
    }

    @Override
    @Transactional
    public void cancelOrder(String orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        // T-M2-03 归属校验：非本人订单（含 user_id 未认领的存量单）一律 404，不泄露他人订单存在性
        if (order == null || order.getUserId() == null || !order.getUserId().equals(userId)) {
            throw new BizException(404, "订单不存在");
        }
        // 状态机条件更新：并发双击取消只有一次生效，保证库存只回补一次
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, STATUS_PENDING_PICKUP)
                .set(Order::getStatus, STATUS_CANCELLED));
        if (updated == 0) {
            throw new BizException("当前状态不可取消");
        }
        restockAfterCancel(orderId);
    }

    /**
     * 取消/超时关单共用的回补链路：回补库存 + 已支付单退款语义（pay_status 1→2、回退销量）
     * + 回补券。前置条件：调用方已通过状态机条件更新把订单置为 cancelled（保证本方法
     * 随状态迁移仅执行一次，不双重回补），且与后续写操作同事务。
     * 超时关单只处理 pay_status=0 的单，refunded 恒为 false，退款分支自然跳过。
     */
    private void restockAfterCancel(String orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        items.forEach(item -> dishMapper.increaseStock(item.getDishId(), item.getQuantity()));
        // 已支付单取消 → 置 pay_status=2（已退款）并按明细回退销量：条件更新 1→2 与支付/并发取消互斥
        // 仅一次生效（0 行即未支付取消，不动 pay_status 也不回退销量——未支付单从未计过销量）。
        // mock 收银台无真实资金流，仅补状态语义；orders 表无独立 refund_time 列，退款时刻由
        // update_time（ON UPDATE CURRENT_TIMESTAMP）隐含记录
        boolean refunded = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getPayStatus, 1)
                .set(Order::getPayStatus, 2)) > 0;
        if (refunded) {
            items.forEach(item -> dishMapper.decrementSoldCount(item.getDishId(), item.getQuantity()));
        }
        // T-M4-02 取消回补：券按核销订单号回补 available 并清空 order_id（同一事务，
        // 仅随状态迁移成功执行一次，与库存回补共用幂等保护）
        couponService.restoreByOrderId(orderId);
        log.info("订单已取消并回补库存: orderId={}, items={}, refunded={}", orderId, items.size(), refunded);
    }

    @Override
    public Map<String, Object> getOrders(Long userId, Integer pageNum, Integer pageSize, String status) {
        // T-M2-03 归属过滤：按 token userId 过滤（phone 查询参数废弃；userId 缺失兜底空列表，封死全量导出）
        if (userId == null) {
            return Map.of("list", List.of(), "total", 0);
        }
        int pn = pageNum == null ? 1 : Math.max(1, pageNum);
        int ps = pageSize == null ? 10 : Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));

        LambdaQueryWrapper<Order> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(Order::getUserId, userId);
        if (isValidStatus(status)) {
            countWrapper.eq(Order::getStatus, status);
        }
        Long total = orderMapper.selectCount(countWrapper);
        if (total == null || total == 0) {
            return Map.of("list", List.of(), "total", 0);
        }

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        if (isValidStatus(status)) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreateTime);
        wrapper.last("LIMIT " + (long) (pn - 1) * ps + "," + ps);
        List<Order> orders = orderMapper.selectList(wrapper);

        // 明细批量 IN 查询（消 N+1，T-M1-08 验收⑤）
        loadItems(orders);
        // 手机号脱敏 138****1234
        orders.forEach(o -> o.setPhone(maskPhone(o.getPhone())));

        return Map.of("list", orders, "total", total);
    }

    @Override
    public int claimOrdersByPhone(String phone, Long userId) {
        // T-M2-03 登录认领存量：同手机号历史订单归属到本用户；user_id 已认领的不重复改动
        return orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getPhone, phone)
                .isNull(Order::getUserId)
                .set(Order::getUserId, userId));
    }

    @Override
    @Transactional
    public int closeTimeoutOrders() {
        // 只扫待支付单：支付成功只置 pay_status=1 不改 status（MockPayService），已支付单
        // status 仍是 pending_pickup，故必须叠加 pay_status=0，否则会误杀已支付订单
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(ORDER_TIMEOUT_MINUTES);
        List<Order> timeoutOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, STATUS_PENDING_PICKUP)
                .eq(Order::getPayStatus, 0)
                .lt(Order::getCreateTime, deadline));
        int closed = 0;
        for (Order order : timeoutOrders) {
            // 状态机条件更新：与手动取消互斥保证只关一次；pay_status=0 参与条件封住
            // "扫描查询之后、置态之前恰好完成支付"的并发窗口（此场景 0 行命中，不关单）
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getStatus, STATUS_PENDING_PICKUP)
                    .eq(Order::getPayStatus, 0)
                    .set(Order::getStatus, STATUS_CANCELLED));
            if (updated > 0) {
                // 复用 cancel 的回补链路（库存/券；pay_status=0 时退款分支自然跳过），
                // 状态置为既有终态 cancelled，不新增状态值，前端订单页四状态 tab 零改动
                restockAfterCancel(order.getId());
                closed++;
                log.info("超时未支付自动关单: orderId={}, createTime={}, 阈值={}分钟",
                        order.getId(), order.getCreateTime(), ORDER_TIMEOUT_MINUTES);
            }
        }
        return closed;
    }

    // ---------------- private ----------------

    /**
     * T-M3-04 截单裁决：按服务器当前时间决定自提日期。
     * 23:00（含整点）前 → 明天；23:00 及之后（跨日截单）→ 后天。
     * 日期与时刻取自同一 LocalDateTime 采样，避免 23:59:59 边界两次 now() 跨日竞态；
     * 独立成方法便于单测注入。
     */
    static LocalDate resolvePickupDate(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return now.getHour() < 23 ? today.plusDays(1) : today.plusDays(2);
    }

    private boolean isValidStatus(String status) {
        return status != null && (STATUS_PENDING_PICKUP.equals(status)
                || STATUS_COMPLETED.equals(status) || STATUS_CANCELLED.equals(status));
    }

    /** @param forUpdate true 时用锁定读，读并发事务已提交的最新版本而非本事务快照 */
    private Order selectByClientRequestId(String clientRequestId, boolean forUpdate) {
        QueryWrapper<Order> wrapper = new QueryWrapper<>();
        wrapper.eq("client_request_id", clientRequestId);
        if (forUpdate) {
            wrapper.last("FOR UPDATE");
        }
        return orderMapper.selectOne(wrapper);
    }

    /** 批量 IN 一次查回页内全部明细并按 orderId 挂载（单订单场景同样适用） */
    private void loadItems(List<Order> orders) {
        List<String> ids = orders.stream().map(Order::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        Map<String, List<OrderItem>> grouped = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, ids))
                .stream()
                .sorted(Comparator.comparing(OrderItem::getId))
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        orders.forEach(o -> o.setItems(grouped.getOrDefault(o.getId(), List.of())));
    }

    /** 138****1234 */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
