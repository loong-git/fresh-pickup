package com.fresh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fresh.common.PhoneMask;
import com.fresh.entity.Order;
import com.fresh.entity.PartnerWallet;
import com.fresh.entity.PartnerWalletFlow;
import com.fresh.entity.Store;
import com.fresh.entity.User;
import com.fresh.mapper.OrderItemMapper;
import com.fresh.mapper.OrderMapper;
import com.fresh.mapper.PartnerWalletFlowMapper;
import com.fresh.mapper.PartnerWalletMapper;
import com.fresh.mapper.StoreMapper;
import com.fresh.mapper.UserMapper;
import com.fresh.service.PartnerWalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 主理人佣金钱包实现（F-119）。
 *
 * <p><b>记账的并发与幂等姿势（契约 §2）</b>：余额变动一律走
 * {@link PartnerWalletMapper#upsertAdjust} 原子加/减（无先读后写窗口）；流水防重双保险——
 * <b>预查</b>（selectCount 命中 uk_order_biz 即跳过，主护栏，钱包零触碰）+ <b>撞键兜底</b>
 * （并发双开时预查拦不住，INSERT 抛 {@link DuplicateKeyException} → 回退本事务内已做的余额变动后
 * 跳过该单，钱包净变动归零）。uk 命中是幂等语义不是错误，绝不向上抛。
 *
 * <p><b>冲回同批（05 册 A7.2）</b>：冲销窗口与 F-118 的 sumRefundAfterAnchor 逐字同源
 * （OrderItemMapper#selectRefundedAnchoredOrderIds），冲回对象按<b>该单 grant 流水</b>定位——
 * 负向同额减回当时收款的那只钱包（flow.user_id），门店改绑后钱也不会退错地方。
 *
 * <p><b>事务边界</b>：{@link #recordForSettlement} 挂 {@code @Transactional}（REQUIRED）——
 * 被 F-118 generateSettlement 的主事务调用时归并同一事务，本类任何异常上抛即账单整体回滚
 * （挂锚也回滚，契约 §2-4「账单与佣金原子一体」）；本类不设独立提交点。
 */
@Slf4j
@Service
public class PartnerWalletServiceImpl implements PartnerWalletService {

    /** 佣金费率 3%（08 册 §6 已批；流水行逐单快照 rate_snapshot，改率不追溯） */
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.03");

    /** 清单 size 默认 20、上限 50（夹而不拒并回显生效值，照 PartnerOrderServiceImpl 同款范式） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private PartnerWalletMapper partnerWalletMapper;
    @Autowired
    private PartnerWalletFlowMapper partnerWalletFlowMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private StoreMapper storeMapper;
    @Autowired
    private UserMapper userMapper;

    // ---------------------------------------------------------------
    // F-119 §2 跑批记账（F-118 generateSettlement 挂锚成功后的同事务追加步）
    // ---------------------------------------------------------------

    @Override
    @Transactional // REQUIRED：随 F-118 主事务执行；本类异常上抛即账单整体回滚（§2-4 原子一体）
    public BigDecimal recordForSettlement(Long merchantId, Long settlementId, Collection<String> orderIds,
                                          LocalDate refundAnchorAfter) {
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        // ① grant 入账：去重订单逐单（入参序=调用方候选序，确定性），owner 非空才记。
        //    orderIds 空集只跳过 grant 段——冲回段与本期候选无关（只认冲销窗口），照常执行。
        if (orderIds != null && !orderIds.isEmpty()) {
            // 订单实付与门店归属一次批量取（无 N+1）：total_price 是佣金基数（契约定稿）、
            // store.owner_user_id 非空是记账开关（空=平台直营店不记账）
            Map<String, Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                            .in(Order::getId, orderIds))
                    .stream()
                    .collect(Collectors.toMap(Order::getId, o -> o, (a, b) -> a, LinkedHashMap::new));
            Set<Long> storeIds = orders.values().stream()
                    .map(Order::getStoreId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<Long, Store> stores = storeIds.isEmpty()
                    ? Map.of()
                    : storeMapper.selectBatchIds(storeIds).stream()
                            .collect(Collectors.toMap(Store::getId, s -> s, (a, b) -> a));

            for (String orderId : orderIds) {
                Order order = orders.get(orderId);
                if (order == null || order.getStoreId() == null) {
                    continue;   // orders 行缺失（理论不可达，挂锚刚验过）或无店单无从归因主理人
                }
                Store store = stores.get(order.getStoreId());
                Long owner = store == null ? null : store.getOwnerUserId();
                if (owner == null) {
                    continue;   // 平台直营店（owner 空）不记账（契约 §2-1 逐字）
                }
                BigDecimal total = order.getTotalPrice() == null ? BigDecimal.ZERO : order.getTotalPrice();
                BigDecimal commission = total.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
                if (commission.compareTo(BigDecimal.ZERO) == 0) {
                    continue;   // 0 元单（免费领等）无佣金可记，不占 uk_order_biz 位
                }
                if (flowExists(orderId, PartnerWalletFlow.BIZ_GRANT)) {
                    continue;   // 一单至多一条 grant（uk_order_biz 预查主护栏）：跨商家同单/跑批重入即跳，钱包零触碰
                }
                if (grant(owner, orderId, settlementId, commission)) {
                    net = net.add(commission);
                }
            }
        }

        // ② reversal 冲回同批（05 册 A7.2）：冲销窗口内退款订单若已记佣金 → 负向同额减回原钱包；
        //    refundAnchorAfter=null（首期，无账期历史）与 F-118 的 refund 恒 0 同判，不触发冲回段
        if (refundAnchorAfter != null) {
            for (String orderId : orderItemMapper.selectRefundedAnchoredOrderIds(merchantId, refundAnchorAfter)) {
                PartnerWalletFlow grantFlow = partnerWalletFlowMapper.selectOne(new LambdaQueryWrapper<PartnerWalletFlow>()
                        .eq(PartnerWalletFlow::getOrderId, orderId)
                        .eq(PartnerWalletFlow::getBizType, PartnerWalletFlow.BIZ_GRANT));
                if (grantFlow == null) {
                    continue;   // 该单此前未记佣金（直营店/0 元单/owner 当时为空），无可冲回
                }
                if (flowExists(orderId, PartnerWalletFlow.BIZ_REVERSAL)) {
                    continue;   // 已冲回过（uk_order_biz 预查），一单至多一条 reversal
                }
                if (reverse(grantFlow, settlementId)) {
                    net = net.add(grantFlow.getAmount().negate());
                }
            }
        }
        log.info("F-119 跑批记账完成: merchantId={}, settlementId={}, orders={}, commissionNet={}",
                merchantId, settlementId, orderIds == null ? 0 : orderIds.size(), net);
        return net;
    }

    /**
     * 单笔 grant：钱包 upsert 原子加 → 回读快照 → 落流水。返回 false=撞 uk 兜底跳过（余额已回退）。
     */
    private boolean grant(Long userId, String orderId, Long settlementId, BigDecimal commission) {
        partnerWalletMapper.upsertAdjust(userId, commission);
        BigDecimal balanceAfter = readBalance(userId);
        PartnerWalletFlow flow = new PartnerWalletFlow();
        flow.setUserId(userId);
        flow.setBizType(PartnerWalletFlow.BIZ_GRANT);
        flow.setOrderId(orderId);
        flow.setSettlementId(settlementId);
        flow.setAmount(commission);
        flow.setBalanceAfter(balanceAfter);
        flow.setRateSnapshot(PartnerWalletFlow.RATE_SNAPSHOT);
        try {
            partnerWalletFlowMapper.insert(flow);
            log.info("佣金入账: userId={}, orderId={}, amount={}, balanceAfter={}, settlementId={}",
                    userId, orderId, commission, balanceAfter, settlementId);
            return true;
        } catch (DuplicateKeyException e) {
            // uk_order_biz 并发兜底：另一跑批先落了本单 grant——回退本事务余额变动后跳过，钱包净变动归零
            partnerWalletMapper.upsertAdjust(userId, commission.negate());
            log.info("佣金入账撞 uk_order_biz，回退余额后跳过: orderId={}, userId={}", orderId, userId);
            return false;
        }
    }

    /**
     * 单笔 reversal：按 grant 流水负向同额冲回（减回当时收款的那只钱包）→ 回读快照 → 落流水。
     * 余额减至负也如实记（契约 §2-2：佣金已付情形轨道 B 处理，本期如实记账）。返回 false=撞 uk 兜底跳过。
     */
    private boolean reverse(PartnerWalletFlow grantFlow, Long settlementId) {
        BigDecimal amount = grantFlow.getAmount().negate();
        Long userId = grantFlow.getUserId();
        partnerWalletMapper.upsertAdjust(userId, amount);
        BigDecimal balanceAfter = readBalance(userId);
        PartnerWalletFlow flow = new PartnerWalletFlow();
        flow.setUserId(userId);
        flow.setBizType(PartnerWalletFlow.BIZ_REVERSAL);
        flow.setOrderId(grantFlow.getOrderId());
        flow.setSettlementId(settlementId);
        flow.setAmount(amount);
        flow.setBalanceAfter(balanceAfter);
        flow.setRateSnapshot(PartnerWalletFlow.RATE_SNAPSHOT);
        try {
            partnerWalletFlowMapper.insert(flow);
            log.info("佣金冲回: userId={}, orderId={}, amount={}, balanceAfter={}, settlementId={}",
                    userId, grantFlow.getOrderId(), amount, balanceAfter, settlementId);
            return true;
        } catch (DuplicateKeyException e) {
            // uk_order_biz 并发兜底：他批已冲回——回退本事务扣减后跳过，钱包净变动归零
            partnerWalletMapper.upsertAdjust(userId, grantFlow.getAmount());
            log.info("佣金冲回撞 uk_order_biz，回退余额后跳过: orderId={}, userId={}", grantFlow.getOrderId(), userId);
            return false;
        }
    }

    /** uk_order_biz 预查（幂等主护栏：命中即跳过，钱包零触碰） */
    private boolean flowExists(String orderId, String bizType) {
        Long count = partnerWalletFlowMapper.selectCount(new LambdaQueryWrapper<PartnerWalletFlow>()
                .eq(PartnerWalletFlow::getOrderId, orderId)
                .eq(PartnerWalletFlow::getBizType, bizType));
        return count != null && count > 0;
    }

    /** 记账后回读余额（同事务读自家写）：balance_after 快照真源；行/列缺失兜 0（列 NOT NULL DEFAULT 0，纯防御） */
    private BigDecimal readBalance(Long userId) {
        PartnerWallet wallet = partnerWalletMapper.selectOne(new LambdaQueryWrapper<PartnerWallet>()
                .eq(PartnerWallet::getUserId, userId));
        return wallet == null || wallet.getBalance() == null ? BigDecimal.ZERO.setScale(2) : wallet.getBalance();
    }

    // ---------------------------------------------------------------
    // F-119 §3 只读两端点
    // ---------------------------------------------------------------

    @Override
    public Map<String, Object> walletView(Long userId, Integer page, Integer size) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long offset = (long) (pageNo - 1) * pageSize;

        PartnerWallet wallet = partnerWalletMapper.selectOne(new LambdaQueryWrapper<PartnerWallet>()
                .eq(PartnerWallet::getUserId, userId));
        BigDecimal balance = wallet == null || wallet.getBalance() == null
                ? BigDecimal.ZERO.setScale(2) : wallet.getBalance();
        Long total = partnerWalletFlowMapper.selectCount(new LambdaQueryWrapper<PartnerWalletFlow>()
                .eq(PartnerWalletFlow::getUserId, userId));
        long totalLong = total == null ? 0 : total;
        // page 无上界，巨大 offset 不必再白跑一条查询（PartnerOrderServiceImpl 同款短路）
        List<PartnerWalletFlow> flows = (totalLong > 0 && offset < totalLong)
                ? partnerWalletFlowMapper.selectList(new LambdaQueryWrapper<PartnerWalletFlow>()
                        .eq(PartnerWalletFlow::getUserId, userId)
                        .orderByDesc(PartnerWalletFlow::getCreatedAt)
                        .orderByDesc(PartnerWalletFlow::getId)
                        .last("LIMIT " + offset + ", " + pageSize))
                : List.of();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("balance", balance);
        data.put("total", totalLong);
        data.put("page", pageNo);
        data.put("size", pageSize);
        data.put("hasMore", offset + flows.size() < totalLong);
        data.put("flows", flows.stream().map(PartnerWalletServiceImpl::toFlowView).toList());
        return data;
    }

    /** 流水出参白名单（只读展示类；键序与 PartnerWalletService 契约注释逐字一致） */
    private static Map<String, Object> toFlowView(PartnerWalletFlow flow) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", flow.getId());
        item.put("bizType", flow.getBizType());
        item.put("orderId", flow.getOrderId());
        item.put("settlementId", flow.getSettlementId());
        item.put("amount", flow.getAmount());
        item.put("balanceAfter", flow.getBalanceAfter());
        item.put("rateSnapshot", flow.getRateSnapshot());
        item.put("createdAt", flow.getCreatedAt());
        return item;
    }

    @Override
    public List<Map<String, Object>> listWallets() {
        List<PartnerWallet> wallets = partnerWalletMapper.selectList(
                new LambdaQueryWrapper<PartnerWallet>().orderByAsc(PartnerWallet::getId));
        if (wallets.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = wallets.stream().map(PartnerWallet::getUserId).toList();
        // 流水数一次分组聚合（无 N+1）；无流水的主理人以 0 兜底
        Map<Long, Long> flowCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : partnerWalletFlowMapper.countFlowsByUser()) {
            flowCounts.put(((Number) row.get("userId")).longValue(), ((Number) row.get("flowCount")).longValue());
        }
        // 名下门店名（一次批量；一主理人可能多店，全列如实显示）
        Map<Long, List<String>> storeNames = new LinkedHashMap<>();
        for (Store store : storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .select(Store::getId, Store::getName, Store::getOwnerUserId)
                .in(Store::getOwnerUserId, userIds)
                .orderByAsc(Store::getId))) {
            storeNames.computeIfAbsent(store.getOwnerUserId(), k -> new ArrayList<>())
                    .add(store.getName());
        }
        // 属主手机号（RD：任何出参只以 PhoneMask.mask 结果出现；user 行已删的悬挂钱包显 null）
        Map<Long, String> phones = new LinkedHashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            phones.put(user.getId(), PhoneMask.mask(user.getPhone()));
        }

        List<Map<String, Object>> out = new ArrayList<>(wallets.size());
        for (PartnerWallet wallet : wallets) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("userId", wallet.getUserId());
            line.put("userPhone", phones.get(wallet.getUserId()));
            line.put("storeNames", storeNames.getOrDefault(wallet.getUserId(), List.of()));
            line.put("balance", wallet.getBalance());
            line.put("flowCount", flowCounts.getOrDefault(wallet.getUserId(), 0L));
            out.add(line);
        }
        return out;
    }
}
