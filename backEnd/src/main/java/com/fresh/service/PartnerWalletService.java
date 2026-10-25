package com.fresh.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 主理人佣金钱包服务（F-119）。三个方法对应三条路径，域内只有「跑批记账」一条写路径：
 * <ul>
 *   <li>{@link #recordForSettlement}：F-118 生成账期事务的<b>追加步</b>（契约 §2「挂锚成功后同事务追加」）——
 *       grant 入账 + reversal 冲回同批，返回本账单佣金净值供 settlement.commission 回填；</li>
 *   <li>{@link #walletView}：主理人端本人钱包只读视图（{@code GET /api/partner/wallet}）；</li>
 *   <li>{@link #listWallets}：运营端全量钱包只读列表（{@code GET /api/admin/partner-wallets}）。</li>
 * </ul>
 * <p><b>红线（契约 §3/§4）</b>：无任何钱包写端点——人工调额=资损面不设入口，运营不可手改钱包；
 * 钱包载体独立，本服务零涉及 {@code user.balance}（邀请现金钱包）；提现属轨道 B 不实现。
 */
public interface PartnerWalletService {

    /**
     * 生成账期事务内记账（F-119 §2 追加步；在调用方 {@code @Transactional} 内执行，REQUIRED 归并主事务，
     * 任何失败异常上抛随账单整体回滚——账单与佣金原子一体）。
     *
     * <ol>
     *   <li><b>grant 入账</b>：对账单去重订单集逐单——{@code store.owner_user_id} 非空才记
     *       （owner 空=平台直营店不记账），佣金 = {@code orders.total_price × 3%}（两位 HALF_UP）；
     *       已有 grant 流水（uk_order_biz 预查命中，跨商家同单/跑批重入）或 0 元单（无佣金可记）跳过；
     *       落 flow(grant, amount 正、balance_after 快照、rate_snapshot=0.0300、settlement_id 锚)；</li>
     *   <li><b>reversal 冲回同批</b>（05 册 A7.2）：冲销窗口（{@code refundAnchorAfter} 非空时）内退款
     *       订单若此前已记佣金 → flow(reversal, 负向同额、balance 原子减，减至负也如实记)；
     *       无 grant 流水或已冲回过（uk 预查）跳过；INSERT 撞 uk（并发兜底）回退本事务余额变动后跳过；</li>
     *   <li>返回净值 = 本账单实际落库流水的 Σ amount（grant 正 + reversal 负；settlement_id 都锚本单，
     *       与钱包流水可互核，即 settlement.commission 的回填值）。</li>
     * </ol>
     *
     * @param merchantId       账单商家（冲销窗口退款订单集的归属谓词）
     * @param settlementId     本账单 id（flow.settlement_id 锚）
     * @param orderIds         本账单挂锚明细的去重订单集（调用方维持候选序）
     * @param refundAnchorAfter 冲销窗口左锚=上一账期止；null=首期（无账期历史，不触发冲回段，与 F-118 的 refund 恒 0 同判）
     * @return 本账单佣金净值（两位小数；无记账发生时 0.00）
     */
    BigDecimal recordForSettlement(Long merchantId, Long settlementId, Collection<String> orderIds,
                                   LocalDate refundAnchorAfter);

    /**
     * 本人钱包只读视图（{@code GET /api/partner/wallet}，F-119 §3）：余额 + 流水分页。
     *
     * @param userId 主理人 user.id（token 解析，钱包按 user 归属）
     * @param page   页码（null/&lt;1 → 1）
     * @param size   页长（null/&lt;1 → 20，&gt;50 → 50；夹而不拒并回显生效值）
     * @return {@code {userId, balance, total, page, size, hasMore,
     *         flows:[{id, bizType, orderId, settlementId, amount, balanceAfter, rateSnapshot, createdAt}]}}
     *         ——从无佣金钱包的主理人余额 0、流水空集（200，不是错误态）
     */
    Map<String, Object> walletView(Long userId, Integer page, Integer size);

    /**
     * 运营只读全量钱包列表（{@code GET /api/admin/partner-wallets}，F-119 §3）：user/店名/余额/流水数。
     *
     * @return 每行 {@code {userId, userPhone, storeNames, balance, flowCount}}——userPhone 经
     *         PhoneMask 脱敏（RD）；userPhone 可 null（user 行已删的悬挂钱包如实显 null）；
     *         flowCount 无流水为 0；按钱包 id 升序
     */
    List<Map<String, Object>> listWallets();
}
