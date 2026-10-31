package com.fresh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fresh.entity.Settlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 商家结算单 mapper（F-118）：BaseMapper 承担 INSERT（generate）与 SELECT（列表/详情）；
 * 冲销金额与对账统计读 order_item（锚所在的家），归 OrderItemMapper，本面不复制第二份 order_item SQL。
 * <b>无 UPDATE/DELETE 方法</b>（F-118 契约 §3 红线：settlement 只增不改，冲销走下一期 refund 列承载）。
 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /**
     * 该商家既有账期的最晚止日（冲销窗口的左锚，F-118 §1.1「自上一账期 period_end 之后发生退款」）。
     * 无账期历史回 null（=首期，冲销恒 0）。MAX 聚合恒回 1 行、无行回 null，两类都由调用方按 null 判。
     */
    @Select("SELECT MAX(period_end) FROM settlement WHERE merchant_id = #{merchantId}")
    LocalDate selectMaxPeriodEnd(@Param("merchantId") Long merchantId);

    /**
     * 运营端结算列表的筛选 WHERE（F-125 §1.2，COUNT 与页两条 SQL 逐字共用同一常量源，
     * 照 MerchantMapper#MERCHANT_LIST_WHERE 的手法：从代码结构上杜绝两条 SQL 复制后手改一处的漂移）。
     * 三参为 null 即不拼对应条件（Service 已把空串归一 null、归一前过 AdminServiceImpl#parseSettleDate
     * 验形，本层只做 null 分支）；账期窗口是<b>闭区间交叠</b>语义：{@code period_start &lt;= dateEnd
     * AND period_end &gt;= dateStart}——账单是区间实体，命中「与筛选窗口 [dateStart,dateEnd] 有交叠」
     * 的账单。{@code <where>} 元素剥离首条件前的 AND，三参全 null 时整段 WHERE 不拼（=全量）。
     */
    String SETTLEMENT_LIST_WHERE = "<where>"
            + "<if test='merchantId != null'>AND merchant_id = #{merchantId}</if> "
            + "<if test='dateStart != null'>AND period_end &gt;= #{dateStart}</if> "
            + "<if test='dateEnd != null'>AND period_start &lt;= #{dateEnd}</if>"
            + "</where>";

    /**
     * 运营端结算列表总数（F-125 §1.2，{@code GET /api/admin/settlements} 的独立 COUNT）：
     * 本工程无 MP 分页插件，页与 COUNT 走两条手写 SQL；WHERE 与 {@link #selectSettlementsPage} 逐字相同。
     * 只读方法（本类「无 UPDATE/DELETE 方法」红线不变）。
     */
    @Select("<script>SELECT COUNT(*) FROM settlement " + SETTLEMENT_LIST_WHERE + "</script>")
    long countSettlements(@Param("merchantId") Long merchantId,
                          @Param("dateStart") String dateStart,
                          @Param("dateEnd") String dateEnd);

    /**
     * 运营端结算列表的一页（F-125 §1.2，{@code ORDER BY id DESC} 新账期在前，照既有 listSettlements 排序）：
     * 只 SELECT 行渲染消费的白名单十二列（coupon_deduct/deduction/paid_at/payout_voucher/created_at/
     * update_time 等不查询——把「不输出」写成「不查询」，照 MerchantMapper#selectMerchantsPage 口径）。
     * LIMIT #{offset}, #{size} 的 offset 由调用方用 long 算（防 int 溢出）。
     */
    @Select("<script>SELECT id AS id, settle_no AS settleNo, merchant_id AS merchantId, "
            + "period_start AS periodStart, period_end AS periodEnd, order_count AS orderCount, "
            + "gmv_amount AS gmvAmount, refund_amount AS refundAmount, retain_amount AS retainAmount, "
            + "payable AS payable, commission AS commission, status AS status "
            + "FROM settlement " + SETTLEMENT_LIST_WHERE
            + " ORDER BY id DESC LIMIT #{offset}, #{size}</script>")
    List<Settlement> selectSettlementsPage(@Param("merchantId") Long merchantId,
                                           @Param("dateStart") String dateStart,
                                           @Param("dateEnd") String dateEnd,
                                           @Param("offset") long offset,
                                           @Param("size") int size);

    /**
     * 商家端结算列表的一页（P5-2 §1，{@code GET /api/merchant/settlements}）：
     * {@code WHERE merchant_id = #{merchantId}} 归属恒等值；{@code ORDER BY id ASC}——商家端 F-120 §1
     * 刻意差异（旧账期在前照商家页既有顺序），与运营端 {@link #selectSettlementsPage} 的
     * {@code ORDER BY id DESC} 刻意不同，两方法互不复用、各自成行防漂移；
     * 白名单十二列与别名照 {@link #selectSettlementsPage} 逐字（商家行渲染只消费这十二列）。
     * 只读方法（本类「无 UPDATE/DELETE 方法」红线不变）。
     */
    @Select("SELECT id AS id, settle_no AS settleNo, merchant_id AS merchantId, "
            + "period_start AS periodStart, period_end AS periodEnd, order_count AS orderCount, "
            + "gmv_amount AS gmvAmount, refund_amount AS refundAmount, retain_amount AS retainAmount, "
            + "payable AS payable, commission AS commission, status AS status "
            + "FROM settlement WHERE merchant_id = #{merchantId} "
            + "ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<Settlement> selectSettlementsPageAsc(@Param("merchantId") Long merchantId,
                                              @Param("offset") long offset,
                                              @Param("size") int size);
}
