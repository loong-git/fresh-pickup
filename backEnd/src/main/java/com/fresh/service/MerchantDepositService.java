package com.fresh.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商家保证金服务（P10-5 轨道 B D7/D8，m33 两表）：模拟轨的两个写路径与两端只读展示的取数收口。
 * <p>写路径只有两处（写路径收口，见 MerchantDeposit 实体注释）：
 * <ul>
 *   <li>代扣 {@link #deductOnSettlement}——生成账期事务尾部追加步（照 PartnerWalletServiceImpl
 *       #recordForSettlement 的 REQUIRED 同事务并入先例，异常上抛即账单整体回滚，不新增第二事务）；</li>
 *   <li>置档位 {@link #setRequired}——D7 模拟触发器（真实触发器①②③由运营手动置等价）。</li>
 * </ul>
 * <p>无档位（无 merchant_deposit 行、required_amount 为 NULL 或 ≤0）的商家在代扣里<b>全跳过</b>
 * ——生成账期既有行为零变化（契约红线）。
 */
public interface MerchantDepositService {

    /**
     * D8 代扣（生成账期事务尾部调用，跟随主事务边界）：
     * <ol>
     *   <li>无档位（无行 / required_amount NULL 或 ≤0）→ 跳过返回 deduct=0；</li>
     *   <li>已缴（paid_amount）≥ required → 跳过；</li>
     *   <li>net（本期聚合净额，扣留存冲销后）≤ 0 → 跳过（无可扣应结）；</li>
     *   <li>gap = required − paid；deduct = min(gap, net)；</li>
     *   <li>merchant_deposit.paid_amount 乐观置 paidAfter（条件 UPDATE，0 行=并发改写 → 抛异常整单回滚）；</li>
     *   <li>status 按 paidAfter 与 required 比较维护（≥required → active，否则 arrears）；</li>
     *   <li>deposit_flow 落 type=2 应结款代扣一行（amount=deduct、balance_after=paidAfter、
     *       biz_id=settleNo、channel=MOCK、operator=SYSTEM）。</li>
     * </ol>
     * settlement 侧（deposit_amount=deduct、payable=net−deduct 回填）与审计由调用方
     * （AdminServiceImpl#generateSettlement 追加步）承接——本方法只动 deposit 两表。
     *
     * @param merchantId 商家ID（生成账期的目标商家）
     * @param settleNo   本期结算单号（deposit_flow.biz_id 来源锚）
     * @param net        本期聚合净额（gross−refund−retain，调用方已算毕；本方法不改它）
     * @return {@code {deduct, paidAfter, status, required, gap}}；未代扣时 deduct=BigDecimal.ZERO
     *         且其余键为账户现状（无账户行则全 0/null）
     */
    Map<String, Object> deductOnSettlement(Long merchantId, String settleNo, BigDecimal net);

    /**
     * 运营端保证金账户列表（D7 管理区数据源，只读）：全量账户按 id 升序，
     * 行键 {@code merchantId,merchantName,category,requiredAmount,paidAmount,status,updatedAt}；
     * merchant 名缺行时回 {@code null}（前端兜 '#' + id 展示，照账期列表同款）。
     */
    List<Map<String, Object>> listAccounts();

    /**
     * 单账户读（运营端审计 old 值留痕 / 商家端缴存状态展示共用，只读）：
     * 无账户行回 {@code null}；行键同 {@link #listAccounts} 行。
     */
    Map<String, Object> accountOf(Long merchantId);

    /**
     * 运营端设置类目档位（D7）：merchantId 必填且商家须存在（404「商家不存在」，照生成账期同文案）；
     * requiredAmount 形状闸门（非数值/负数/超两位小数 400，照提现同款 DECIMAL(10,2) 定形）；
     * requiredAmount=0 → 停用（required_amount=0、status='none'，保留账户行与已缴记录）；
     * &gt;0 → upsert 档位（category 可空，非空须在 dish.category 11 类内否则 400），
     * status 按「已缴 vs 新 required」落 active/arrears（首次启用建户 status 同口径）。
     * 审计（deposit.required）由调用方承接；本方法只动 merchant_deposit 一表。
     *
     * @return {@code {merchantId, category, requiredAmount, paidAmount, status}}
     */
    Map<String, Object> setRequired(Long merchantId, BigDecimal requiredAmount, String category);
}
