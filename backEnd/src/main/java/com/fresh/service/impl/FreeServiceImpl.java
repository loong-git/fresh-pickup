package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fresh.common.BizException;
import com.fresh.common.R;
import com.fresh.dto.FreeClaimDTO;
import com.fresh.entity.Dish;
import com.fresh.entity.FreeActivity;
import com.fresh.entity.FreeClaim;
import com.fresh.entity.FreeGoodsPool;
import com.fresh.entity.Order;
import com.fresh.entity.OrderItem;
import com.fresh.entity.Store;
import com.fresh.entity.User;
import com.fresh.mapper.DishMapper;
import com.fresh.mapper.FreeActivityMapper;
import com.fresh.mapper.FreeClaimMapper;
import com.fresh.mapper.FreeGoodsPoolMapper;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.pay.PayService;
import com.fresh.service.FreeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 免费领商品服务实现（M7 方案 A：每日 0 元限量领，复用订单链路生成 0 元单并自动支付；
 * M8 满额赠：候选池自选商品；M9 起满额门槛按 items=当前购物车内容以 dish 表价格重算合计判定）。
 * 新链路对存量下单零侵入：不走 createOrder 计价分支（免费商品按 dish.price 会计出原价），
 * 自建精简建单，仅复用 Mapper 原语（decreaseStock 原子扣减/条件更新）与 payResult 支付闭环。
 * 0 元单生命周期自洽：closeTimeoutOrders 只扫 pay_status=0（自动支付后不会被误杀）；
 * 取消已支付 0 元单走既有退款语义（pay_status 1→2 + 回退销量），但 free_claim 资格不恢复——
 * 封死"领取→取消→再领"刷单循环。
 */
@Slf4j
@Service
public class FreeServiceImpl implements FreeService {

    /** 分页上界：防大 pageSize 拖库（与 getOrders 同口径） */
    private static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private FreeActivityMapper freeActivityMapper;
    @Autowired
    private FreeClaimMapper freeClaimMapper;
    @Autowired
    private FreeGoodsPoolMapper freeGoodsPoolMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PayService payService;

    @Override
    public Map<String, Object> current(Long userId) {
        FreeActivity activity = currentActivity();
        Map<String, Object> data = new LinkedHashMap<>();
        // 满额门槛（M8）：迁移后 NOT NULL，此处对潜在脏行兜底 0（门槛 0 等效不限额可领）
        BigDecimal threshold = activity == null || activity.getThreshold() == null
                ? BigDecimal.ZERO : activity.getThreshold();
        // today 单次采样：跨 0 点时限量重置日/claim_date/todayPaid 展示口径不漂移
        LocalDate today = LocalDate.now();
        // 单活动模型：无行/下线/候选池全下架统一 activityStatus 信号，前端置灰"活动暂未开始"，
        // 与 remaining=0 的"今日已抢完"区分开；响应字段全量固定（匿名个人数据 0/0/false/null），前端免判空
        if (activity == null || !FreeActivity.STATUS_ONLINE.equals(activity.getStatus())) {
            data.put("activityStatus", activity == null ? "empty" : activity.getStatus());
            data.put("remaining", 0);
            data.put("dish", null);
            data.put("threshold", threshold);
            putPersonal(data, userId, threshold, today);
            data.put("pool", List.of());
            return data;
        }
        // 候选池（M8）：池 id 升序=插入序，联 dish 取在售商品；空池/全下架沿用 offline 信号
        List<Dish> pool = poolDishes(activity.getId());
        if (pool.isEmpty()) {
            data.put("activityStatus", FreeActivity.STATUS_OFFLINE);
            data.put("remaining", 0);
            data.put("dish", null);
            data.put("threshold", threshold);
            putPersonal(data, userId, threshold, today);
            data.put("pool", List.of());
            return data;
        }
        Dish dish = pool.get(0); // 默认免费商品=候选池第一个
        data.put("activityStatus", FreeActivity.STATUS_ONLINE);
        data.put("remaining", remainingOf(activity, dish, today));
        data.put("dish", dish);
        data.put("threshold", threshold);
        putPersonal(data, userId, threshold, today);
        data.put("pool", pool.stream().map(this::poolItem).collect(Collectors.toList()));
        return data;
    }

    @Override
    @Transactional
    public Order claim(Long userId, Long dishId, List<FreeClaimDTO.ClaimItemDTO> items) {
        // 归属：userId 一律服务端从 token 解析（拦截器保证非空，此处兜底），不信任前端任何字段
        if (userId == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, R.MSG_UNAUTHORIZED);
        }
        // today 事务内单次采样：限量计数重置日与 claim_date 同源，防跨 0 点错位一天
        LocalDate today = LocalDate.now();
        // 幂等（T-M1-09 同思路）：clientRequestId='free-{userId}-{yyyyMMdd}'，双击/重试返回同一订单
        String clientRequestId = "free-" + userId + "-" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
        Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getClientRequestId, clientRequestId));
        if (existing != null) {
            existing.setItems(orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, existing.getId())));
            return existing;
        }

        // —— 以下为资格与限量判定（前置于一切写操作，失败尽早、回滚成本最低）——
        FreeActivity activity = currentActivity();
        if (activity == null || !FreeActivity.STATUS_ONLINE.equals(activity.getStatus())) {
            throw new BizException("活动暂未开始");
        }
        // M8 自选商品：dishId 必须在候选池白名单内（不信任前端入参；池内商品下架由下方 on_sale 校验兜文案），
        // 池外一律"无效的免费商品"
        if (dishId == null || freeGoodsPoolMapper.selectCount(new LambdaQueryWrapper<FreeGoodsPool>()
                .eq(FreeGoodsPool::getActivityId, activity.getId())
                .eq(FreeGoodsPool::getDishId, dishId)) == 0) {
            throw new BizException("无效的免费商品");
        }
        Dish dish = dishMapper.selectById(dishId);
        if (dish == null || dish.getOnSale() == null || dish.getOnSale() != 1) {
            throw new BizException("活动商品已下架，明天再来");
        }
        // M9 满额门槛：按 items（用户当前购物车内容）以 dish 表价格重算合计判定（绝不信任前端金额），
        // 未满 threshold 拒绝，差额保留两位小数引导凑单；免费单走独立 0 元链路不进购物车，
        // 0 元单（totalPrice=0）天然不推高合计，"0元单凑门槛"刷单面与旧 sumTodayPaid 口径等价封死
        BigDecimal threshold = activity.getThreshold() == null ? BigDecimal.ZERO : activity.getThreshold();
        BigDecimal cartTotal = recalcCartTotal(items);
        if (cartTotal.compareTo(threshold) < 0) {
            throw new BizException("再挑选" + threshold.subtract(cartTotal)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString() + "元，今天带走免费商品");
        }
        if (dish.getStock() == null || dish.getStock() <= 0) {
            throw new BizException("今日免费商品已抢完，明天再来");
        }
        // 资格友好前置检查（权威约束在 free_claim.uk_user_date 数据库层，强于券的 selectCount 业务检查）
        if (hasClaimed(userId, today)) {
            throw new BizException("今日已领取，明天再来");
        }

        // 原子限量计数：跨日重置 + 限量判定同条 SQL，行锁串行化并发领取；0 行=今日不可领
        if (freeActivityMapper.tryClaimQuota(activity.getId(), today) == 0) {
            // 重读行区分三态文案：下线/预置未来日期（未开始）与已抢完不说反话
            FreeActivity latest = freeActivityMapper.selectById(activity.getId());
            if (latest == null || !FreeActivity.STATUS_ONLINE.equals(latest.getStatus())
                    || (latest.getDailyDate() != null && latest.getDailyDate().isAfter(today))) {
                throw new BizException("活动暂未开始");
            }
            throw new BizException("今日已抢完，明天再来");
        }

        // 写领取记录：UNIQUE(user_id, claim_date) 并发唯一冲突 → 转"今日已领取"，
        // 方法出栈触发整个事务回滚（限量计数/订单/库存扣减一并撤销，无半态）
        String orderId = IdWorker.getIdStr(); // 预生成雪花订单号：free_claim.order_id 建单前回写（ASSIGN_ID 同源）
        FreeClaim claim = new FreeClaim();
        claim.setUserId(userId);
        claim.setDishId(dish.getId());
        claim.setOrderId(orderId);
        claim.setClaimDate(today);
        try {
            freeClaimMapper.insert(claim);
        } catch (DuplicateKeyException e) {
            throw new BizException("今日已领取，明天再来");
        }

        // phone 服务端按 token userId 查 user 表落登录手机号（LoginDTO @Pattern '1[3-9]\d{9}' 恒 11 位，
        // getOrders/maskPhone 脱敏安全）；claim 接口不收任何 phone/address 入参
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(R.CODE_UNAUTHORIZED, "用户不存在，请重新登录");
        }

        // 精简建单：金额 0 由服务端写死；address 落空串（createOrder 同款兜底）；自提点快照三列
        // 取营业中门店（claim 不收 phone/address 入参，门店仍服务端确定性选择）；pickup_date 复用 resolvePickupDate
        // 同一 23:00 截单裁决，防免费单自提日期与普通单规则漂移
        Order order = new Order();
        order.setId(orderId);
        order.setClientRequestId(clientRequestId);
        order.setUserId(userId);
        order.setPhone(user.getPhone());
        order.setAddress("");
        order.setStatus(OrderServiceImpl.STATUS_PENDING_PICKUP);
        order.setPayStatus(0);
        order.setTotalPrice(BigDecimal.ZERO);
        order.setPickupDate(OrderServiceImpl.resolvePickupDate(LocalDateTime.now()));
        Store store = storeMapper.selectOne(new LambdaQueryWrapper<Store>()
                .eq(Store::getStatus, "open").orderByAsc(Store::getId).last("LIMIT 1"));
        if (store != null) {
            order.setStoreId(store.getId());
            order.setStoreName(store.getName());
            order.setStoreAddress(store.getAddress());
        }
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setDishId(dish.getId());
        item.setDishName(dish.getName());
        item.setPrice(BigDecimal.ZERO); // 明细落成交单价快照：0 元单明细与主单同口径
        item.setQuantity(1);
        orderItemMapper.insert(item);

        // 条件 UPDATE 原子扣减（T-M1-05 同思路）：影响行数=0 即库存不足 → 抛错整单回滚
        if (dishMapper.decreaseStock(dish.getId(), 1) == 0) {
            throw new BizException("「" + dish.getName() + "」库存不足");
        }

        // 0 元支付闭环：复用 MockPayService 条件更新 pay_status 0→1 + pay_time + 按明细累加销量
        // （幂等可重入）；自动支付后 pay_status=1，超时关单只扫 pay_status=0 不会误杀
        Order paid = payService.payResult(order.getId(), userId);
        paid.setItems(List.of(item));
        log.info("0元领取成功: userId={}, orderId={}, dishId={}, claimDate={}", userId, orderId, dish.getId(), today);
        return paid;
    }

    @Override
    public Map<String, Object> myClaims(Long userId, Integer pageNum, Integer pageSize) {
        int pn = pageNum == null ? 1 : Math.max(1, pageNum);
        int ps = pageSize == null ? 10 : Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        Long total = freeClaimMapper.selectCount(new LambdaQueryWrapper<FreeClaim>()
                .eq(FreeClaim::getUserId, userId));
        if (total == null || total == 0) {
            return Map.of("list", List.of(), "total", 0);
        }
        LambdaQueryWrapper<FreeClaim> wrapper = new LambdaQueryWrapper<FreeClaim>()
                .eq(FreeClaim::getUserId, userId)
                .orderByDesc(FreeClaim::getClaimDate)
                .orderByDesc(FreeClaim::getId);
        wrapper.last("LIMIT " + (long) (pn - 1) * ps + "," + ps);
        List<FreeClaim> claims = freeClaimMapper.selectList(wrapper);

        // 关联订单批量 IN 查询（消 N+1），手机号脱敏口径同 getOrders（free 单 phone 恒为登录手机号 11 位）
        List<String> orderIds = claims.stream().map(FreeClaim::getOrderId).distinct().collect(Collectors.toList());
        Map<String, Order> orders = orderIds.isEmpty() ? Map.of()
                : orderMapper.selectList(new LambdaQueryWrapper<Order>().in(Order::getId, orderIds))
                        .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        orders.values().forEach(o -> o.setPhone(maskPhone(o.getPhone())));
        // 商品名随行返回（前端"我的领取记录"列表展示）
        List<Long> dishIds = claims.stream().map(FreeClaim::getDishId).distinct().collect(Collectors.toList());
        Map<Long, Dish> dishes = dishIds.isEmpty() ? Map.of()
                : dishMapper.selectBatchIds(dishIds).stream()
                        .collect(Collectors.toMap(Dish::getId, Function.identity()));

        List<Map<String, Object>> list = claims.stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("dishId", c.getDishId());
            row.put("dishName", dishes.containsKey(c.getDishId()) ? dishes.get(c.getDishId()).getName() : null);
            row.put("orderId", c.getOrderId());
            row.put("claimDate", c.getClaimDate());
            row.put("createTime", c.getCreateTime());
            row.put("order", orders.get(c.getOrderId()));
            return row;
        }).collect(Collectors.toList());
        return Map.of("list", list, "total", total);
    }

    // ---------------- private ----------------

    /** 单活动模型：取 id 最小的活动行（种子固定 id=1） */
    private FreeActivity currentActivity() {
        return freeActivityMapper.selectOne(new LambdaQueryWrapper<FreeActivity>()
                .orderByAsc(FreeActivity::getId).last("LIMIT 1"));
    }

    /**
     * 活动候选池联 dish（M8）：池行按 id 升序=插入序（第一行即默认免费商品），
     * 已下架/被删的候选商品不进下发列表（领取侧另有 on_sale 校验兜"已下架"文案）
     */
    private List<Dish> poolDishes(Long activityId) {
        List<FreeGoodsPool> pool = freeGoodsPoolMapper.selectList(new LambdaQueryWrapper<FreeGoodsPool>()
                .eq(FreeGoodsPool::getActivityId, activityId)
                .orderByAsc(FreeGoodsPool::getId));
        if (pool.isEmpty()) {
            return List.of();
        }
        List<Long> dishIds = pool.stream().map(FreeGoodsPool::getDishId).collect(Collectors.toList());
        Map<Long, Dish> dishes = dishMapper.selectBatchIds(dishIds).stream()
                .collect(Collectors.toMap(Dish::getId, Function.identity()));
        return pool.stream()
                .map(p -> dishes.get(p.getDishId()))
                .filter(d -> d != null && d.getOnSale() != null && d.getOnSale() == 1)
                .collect(Collectors.toList());
    }

    /** 候选池商品行（M8 契约固定 7 字段）：前端自选列表直接渲染，不透出库存/销量等内部字段 */
    private Map<String, Object> poolItem(Dish dish) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dishId", dish.getId());
        row.put("name", dish.getName());
        row.put("image", dish.getImage());
        row.put("unit", dish.getUnit());
        row.put("price", dish.getPrice());
        row.put("emoji", dish.getEmoji());
        row.put("bgColor", dish.getBgColor());
        return row;
    }

    /**
     * 满额门槛重算合计（M9）：按 dish 表现价 Σ price×quantity，绝不信任前端任何金额字段；
     * 批量 IN 查 dish（消 N+1，同 poolDishes 口径），购物车中已删/脏条目按 0 计——
     * 合计只少不多（不会因脏条目误放行），也不因购物车脏数据阻断领取
     */
    private BigDecimal recalcCartTotal(List<FreeClaimDTO.ClaimItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Long> dishIds = items.stream()
                .map(FreeClaimDTO.ClaimItemDTO::getDishId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (dishIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<Long, Dish> dishes = dishMapper.selectBatchIds(dishIds).stream()
                .collect(Collectors.toMap(Dish::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO;
        for (FreeClaimDTO.ClaimItemDTO item : items) {
            Dish dish = item.getDishId() == null ? null : dishes.get(item.getDishId());
            int quantity = item.getQuantity() == null ? 0 : item.getQuantity(); // @Min(1) 已挡，防御性兜 0
            if (dish == null || dish.getPrice() == null || quantity <= 0) {
                continue; // 已删/价格脏/数量脏条目按 0 计：合计只少不多，安全方向
            }
            total = total.add(dish.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        return total;
    }

    /**
     * 登录态个人数据（M8 契约固定字段，匿名给中性缺省 0/0/false/null 保持响应形状稳定）：
     * todayPaid 当日实付累计（OrderMapper#sumTodayPaid 口径，免费单不计入）、
     * remainingToThreshold=max(0, threshold-todayPaid)、claimed 今日已领、claimedOrder 今日 0 元单摘要。
     */
    private void putPersonal(Map<String, Object> data, Long userId, BigDecimal threshold, LocalDate today) {
        BigDecimal todayPaid = BigDecimal.ZERO;
        boolean claimed = false;
        Map<String, Object> claimedOrder = null;
        if (userId != null) {
            todayPaid = orderMapper.sumTodayPaid(userId, today);
            claimed = hasClaimed(userId, today);
            if (claimed) {
                claimedOrder = claimedOrderSummary(userId, today);
            }
        }
        BigDecimal remainingToThreshold = threshold.subtract(todayPaid);
        if (remainingToThreshold.compareTo(BigDecimal.ZERO) < 0) {
            remainingToThreshold = BigDecimal.ZERO; // 已达标：max(0, threshold - todayPaid)
        }
        data.put("todayPaid", todayPaid);
        data.put("remainingToThreshold", remainingToThreshold);
        data.put("claimed", claimed);
        data.put("claimedOrder", claimedOrder);
    }

    /**
     * 今日 0 元单摘要（M8 claimedOrder）：单号/商品/状态/自提快照够前端成功卡直接渲染；
     * 手工摘要不透出 phone（脱敏口径同 myClaims）；领取记录在但订单行缺失（异常数据）返回 null 兜底
     */
    private Map<String, Object> claimedOrderSummary(Long userId, LocalDate today) {
        FreeClaim claim = freeClaimMapper.selectOne(new LambdaQueryWrapper<FreeClaim>()
                .eq(FreeClaim::getUserId, userId)
                .eq(FreeClaim::getClaimDate, today)
                .orderByDesc(FreeClaim::getId)
                .last("LIMIT 1"));
        if (claim == null) {
            return null;
        }
        Order order = orderMapper.selectById(claim.getOrderId());
        if (order == null) {
            return null;
        }
        Dish dish = dishMapper.selectById(claim.getDishId());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", order.getId());
        summary.put("dishId", claim.getDishId());
        summary.put("dishName", dish == null ? null : dish.getName());
        summary.put("status", order.getStatus());
        summary.put("payStatus", order.getPayStatus());
        summary.put("totalPrice", order.getTotalPrice());
        summary.put("pickupDate", order.getPickupDate());
        summary.put("storeName", order.getStoreName());
        summary.put("storeAddress", order.getStoreAddress());
        summary.put("createTime", order.getCreateTime());
        return summary;
    }

    /** 同用户同日是否已领取（uk_user_date 的业务读口径） */
    private boolean hasClaimed(Long userId, LocalDate today) {
        Long count = freeClaimMapper.selectCount(new LambdaQueryWrapper<FreeClaim>()
                .eq(FreeClaim::getUserId, userId)
                .eq(FreeClaim::getClaimDate, today));
        return count != null && count > 0;
    }

    /**
     * 剩余可领份数：限量口径 quota-claimed，与商品现库存取小（限量 > 库存时不放空炮）；
     * 跨日未重置（daily_date 为 NULL/过去）按满额计（重置由领取时条件 UPDATE 原子完成）；
     * daily_date 为未来（脏数据/预置未开始）按 0 计，与领取侧"活动暂未开始"文案同口径。
     */
    private int remainingOf(FreeActivity activity, Dish dish, LocalDate today) {
        int quota = activity.getDailyQuota() == null ? 0 : activity.getDailyQuota();
        if (quota <= 0 || dish.getStock() == null || dish.getStock() <= 0) {
            return 0;
        }
        LocalDate dailyDate = activity.getDailyDate();
        int quotaLeft;
        if (dailyDate == null || dailyDate.isBefore(today)) {
            quotaLeft = quota;
        } else if (dailyDate.isEqual(today)) {
            quotaLeft = quota - (activity.getDailyClaimed() == null ? 0 : activity.getDailyClaimed());
        } else {
            return 0;
        }
        return Math.max(0, Math.min(quotaLeft, dish.getStock()));
    }

    /** 138****1234（口径同 OrderServiceImpl#maskPhone：free 单 phone 恒 11 位，substring 安全） */
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
